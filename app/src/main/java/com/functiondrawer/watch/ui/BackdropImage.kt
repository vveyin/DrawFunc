// Compose 互操作 API（`LocalView` 等）在 Compose 1.7 里仍标着实验性，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

/**
 * 全局背景：**一张已经糊好的静态图**，所有页面共用（3.37 / 3.39 修订）。
 *
 * ## 用户的原话（需求）
 *
 * > "把模糊后的背景作为静态图片放到背景这里来，作为全局背景……
 * >  只有从函数图像界面滑动提示条的时候重新绘制一帧并作为静态图片作为新背景"
 *
 * 3.39 用户又补了两条边界：
 *
 * > "拿来渲染的元素只有坐标轴、函数曲线、和线段、点之类的对象"
 * > "这个背景是要当做全局背景，但是不要当函数图像界面的背景"
 * > "在放置新的图片的时候没有销毁旧图片，有残影叠加"
 *
 * ## 背景图里该有什么、不该有什么（3.39 的核心修正）
 *
 * 3.37/3.38 用 `PixelCopy` 抓**整个窗口**——合成帧里除了画布，还有设置按钮、
 * `+`、函数卡片，甚至上一张模糊背景本身。结果就是用户截图里那样：
 * 模糊背景里能看到一排模糊的按钮残影，重做一帧就叠一层。
 *
 * 修正：抓帧源换成 [GraphCapture]——它挂在 **`GraphCanvas` 那一层**上
 * （`drawWithContent` 每帧 `record`），录到的**只有网格、坐标轴、刻度数字、
 * 曲线和几何对象**，一个 UI 元素都不会进来。旧图被新图**整体替换**
 * （`image = bitmap` 是赋值不是叠加），残影的来源就断掉了。
 *
 * ## 三步分离
 *
 * | 步骤 | 什么时候发生 | 代价 |
 * |---|---|---|
 * | ① 取图 | 手指碰到提示条那一刻 | 一次 `GraphicsLayer.toImageBitmap()` |
 * | ② 糊 | 后台线程，BlurView 的 RenderScriptBlur | 一次卷积 |
 * | ③ 画 | 每一帧 | `Image(bitmap)`，零卷积 |
 */
internal object BackdropImage {

    /** 当前那张糊好的图。三处背景层都读它。 */
    var image by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** 上次生成失败的原因（"关于"页的诊断行用）。 */
    var lastError: String? = null
        private set

    /** 上一次生成用的半径（诊断用）。 */
    var lastRadius: Float = 0f
        private set

    /** 上一次**真正送去卷积的小图尺寸**（诊断用）。null = 没缩过（0% 不糊那条路）。 */
    var lastScaledSize: String? = null
        internal set

    /**
     * **最近一帧原始画布**（未糊）的软件位图（3.45）。
     *
     * ## 为什么必须存一份
     *
     * 设置页 / 主题设置子页是**整屏路由**：`AnimatedContent` 切走时
     * `TabDeck`（连画布）整块离开组合树，`DrawnTicker` 永远不前进 ——
     * 于是"在设置里调模糊程度"时，取图那一步必然超时，一张都做不出来。
     * 这就是用户说的"改完要等退回函数列表才重绘"。
     *
     * 但"重新糊一遍"其实**不需要**重新取图：手上已经有最近一帧原图了，
     * 换一个半径再卷积一次就行。所以每次成功取图后把它留一份，
     * 设置页里改程度就直接用它 —— 不需要画布在树里，也不用等。
     *
     * 尺寸：手表 466×466 ARGB_8888 ≈ 0.87MB，只留一张，可接受。
     */
    var sourceFrame: Bitmap? = null
        internal set

    /** 记下最近一帧原始画布（由 [BackdropProducer] 在取图成功后调用）。 */
    internal fun rememberSource(bitmap: Bitmap) {
        sourceFrame = bitmap
    }

    /**
     * **这一张的年龄**（3.40）：每次 [publish] +1。
     *
     * `GlobalBackdrop` 拿它当 `key`，让"新图上场"这件事可被观察到 ——
     * 中心渐显动画就是靠它在 `LaunchedEffect` 里重新起一次。
     */
    var generation by mutableStateOf(0)
        private set

    /**
     * **这一张要不要播晕染动画**（3.44 定稿）。
     *
     * 用户 3.44 把语义说到底了：
     *
     * > "我不是说只有开屏第一次和在设置里打开模糊背景开关之后的第一次和
     * >  调整模糊程度之后的第一次才有绘制动画吗，你怎么写得每次都有。"
     *
     * 也就是**只有三种"开张"**配一段晕染，其余（碰提示条、切主题、退出设置、
     * 退出编辑器……）一律直接出现。判定在 [shouldAnimateFor]，它看的是
     * "这一次请求的原因 + 上一次播过没有"，不再只看"出没出过图"。
     */
    var animated by mutableStateOf(false)
        private set

    /** 已经出过图了没有。保留给诊断行看，不再参与动画判定。 */
    private var everPublished = false

    /**
     * 已经为哪一种"开张"播过晕染（3.44）。
     *
     * "打开开关后第一次"和"调程度后第一次"要播，播过就记下来；
     * 关掉开关 / 再调一次时由 [clearReveal] 清掉那一闩。
     */
    private val revealPlayed = mutableSetOf<BackdropReason>()

    /** 下一次请求的原因（由调用方在 [requestFor] 里带进来）。 */
    internal var pendingReason = BackdropReason.OTHER

    /** 由 [rememberBackdropProducer] 写：请求"取一帧画布并糊成新背景"。 */
    internal var request: (() -> Unit)? = null

    /**
     * 手指在提示条上 / 抽屉在动 → true（1.2.42）。
     *
     * 抓帧那一步（`GraphicsLayer.toImageBitmap()`）**只能在主线程**跑，
     * 而且要把整层画布重新录一遍到软件位图 —— 手表上几十到上百毫秒。
     * 如果它正好落在一次拖动的开头，手指的移动事件就排在这一坨计算后面，
     * 表现就是"抽屉拉动有延迟"。
     *
     * 所以手势期间 [BackdropProducer.request] 直接**丢弃**请求，不占产线；
     * 松手落位后 260ms 由抽屉那边的 `LaunchedEffect` 再要一张。
     */
    internal var hold: Boolean = false

    /**
     * 有一次抓帧 / 卷积**正在做**（1.2.47）。
     *
     * 用户原话："如果手太快，在背景还没刷新的时候就拉动抽屉，背景就还是松手才刷新，
     * 你做成在背景刷新完的时候抽屉才会跟手动。"
     *
     * 所以这是抽屉的"门"：为真时 [DrawerController.dragTo] 只记住手指要求的进度、
     * 不真的移动抽屉；[publish] 之后门打开，抽屉才补上那一格（见 `DrawerDeck`
     * 里那个 `LaunchedEffect(refreshing)`）。
     */
    var refreshing by mutableStateOf(false)
        internal set

    /**
     * 画面**已经变了**，但 [image] 还是旧的（1.2.48）。
     *
     * 用户原话："我是说背景没刷新抽屉就不动，等到背景刷新完背景就快速跟上手。
     * 一定要确保背景刷新。"
     *
     * 上一版只靠 [refreshing] 当门，可那个请求早在"变动后 100ms"就已经发过了、
     * 也许已经做完 —— 于是抽屉照旧立刻跟手，图上还是旧的那张。
     *
     * 所以还要一个"脏"标记：画面一变就置 true，[publish] 时置 false。
     * 手指碰到提示条时如果还是脏的，就在**这一刻**补发一次请求
     * （那时 `hold` 还没置位，不会被丢），抽屉的门随之关上，等它画完再追手。
     */
    var stale by mutableStateOf(true)
        internal set

    /**
     * 同上，但**带原因**（3.44）。
     *
     * 为什么要有两份：[request] 是手势那条路（碰提示条），它永远不该播晕染；
     * 设置里开开关 / 调程度那两条要播**第一次**。塞进一个带默认值的参数会让
     * 手势那边也得写一句没意义的实参，所以干脆分成两个入口。
     */
    internal var requestFor: ((BackdropReason) -> Unit)? = null

    /**
     * 同上，但**显式带一个模糊程度**（3.45）。
     *
     * 场景：设置页滑条松手。那一刻草稿里的 `blurStrength` 刚被写进去，
     * 但 `requestFor` 读的是 `rememberUpdatedState` 里的值 —— 它要等下一次
     * 组合才更新，同一次事件里拿到的还是旧值。所以必须把"松手时那一个数"
     * 直接递进来。
     */
    internal var requestAt: ((Float, BackdropReason) -> Unit)? = null

    /**
     * 由调用方写：**让取图源那一层重绘一次**（3.41）。
     *
     * 就是 `graphCapture.invalidate()`。它必须由这里统一触发，因为
     * "画布输入没变但我们需要一张新帧"的入口有好几个（按下提示条、
     * 设置里改程度、开开关、换主题），漏一个就是"那个入口改了没反应"。
     */
    internal var invalidateSource: (() -> Unit)? = null

    /**
     * 发布一张新图。
     *
     * ## ⚠️ 赋值顺序是有讲究的（3.44 用户点出来的）
     *
     * 用户原话："我推测是，绘制是绘制了，但是你没有把图片替换的顺序调过来。"
     *
     * 旧顺序是 `image = bitmap` → `animated = ...` → `generation++`。
     * 看上去最后写 `generation` 没问题，但 Compose 的 `Snapshot` 是**批量提交**的：
     * 三个写在同一帧内一起生效，`GlobalBackdrop` 的
     * `LaunchedEffect(generation, animated)` 重跑时读到的 `image` 确实是新的。
     * 那为什么还是"松手才换"？因为**取图那一步**根本不在按下那一拍完成 ——
     * 见 [DrawnTicker] 与 [BackdropProducer.request]。顺序不是病根，但按"先定原因、
     * 最后换图"写更不容易出错，这里就改成这个顺序。
     *
     * @param reason 这一次是为什么出的图（决定要不要播晕染）。
     */
    internal fun publish(
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
        radius: Float,
        reason: BackdropReason? = null
    ) {
        val why = reason ?: pendingReason
        // ① 先定"这张要不要播"：它只依赖原因和闩，与图无关。
        val wantReveal = shouldAnimateFor(why, revealPlayed)
        if (wantReveal) revealPlayed += why
        animated = wantReveal
        lastRadius = radius
        lastError = null
        // ② 最后才换图 + 推进代次：读图的一方看到新代次时，图一定已经是新的。
        // 直接替换：旧的那张从这一刻起没有任何引用，交给 GC。
        // 残影（3.39）的根因不是这里叠加，而是抓帧把上一张背景也拍进去了。
        image = bitmap
        everPublished = true
        generation++
        // 1.2.48：这张就是"当前画面"，抽屉的门可以开了。
        stale = false
        pendingReason = BackdropReason.OTHER
    }

    internal fun fail(reason: String) {
        lastError = reason
        Log.w("BackdropImage", reason)
    }

    internal fun reset() {
        // ---- 3.45：**保留** image 和 sourceFrame，只清错误 ----
        //
        // 以前这里把 `image` 置空。后果是：在设置里把模糊背景关掉再打开，
        // 从"关"到"新图做出来"之间那一小段里没有任何图可画 —— 用户看到白底。
        // 而他恰恰是在设置页里操作的，画布不在组合树、取不到新帧，
        // 这段空白可能一直持续到退出设置。
        //
        // 保留旧图没有副作用：关着的时候 `GlobalBackdrop` / 抽屉背板都不组合，
        // 没人读它。打开时先用旧图顶上，新图做出来再整体替换。
        lastError = null
        // ⚠️ 不动 `everPublished` / `revealPlayed`：
        // 关掉开关再打开**要**再播一次（用户 3.44 明确要求），那一闩由
        // [clearReveal] 在"关"的那一刻清；"进 App 第一次"这一闩
        // 属于进程生命周期，绝不在这里被动。
        generation++
    }

    /** 允许"下一次 [reason] 类的出图"重新播一次晕染。 */
    internal fun clearReveal(reason: BackdropReason) {
        revealPlayed -= reason
    }
}

/**
 * 在**根 Composable** 里调用一次：把"取一帧画布 → 糊 → 发布"的机制挂起来。
 *
 * ## 取图源：`GraphCapture`（3.39 起）
 *
 * | 版本 | 取图方式 | 结果 |
 * |---|---|---|
 * | 3.36 | `GraphicsLayer`（挂在抽屉层） | 帧外调用 → `SurfaceControl.screenshot` 崩溃 |
 * | 3.37/3.38 | `PixelCopy` 抓整窗 | 不崩了，但**把按钮/卡片/旧背景一起糊了进去**（残影） |
 * | 3.39 | [GraphCapture]（挂在 `GraphCanvas` 上） | 只有坐标轴/曲线/点/线段 |
 *
 * ## 3.41 修的三件事
 *
 * | 现象 | 根因 | 修法 |
 * |---|---|---|
 * | "松手才重绘" | 3.40 用"投一个主线程消息"等绘制，方向反了（消息比绘制**早**）；3.41 上一版又改成"按下冻结、松手后抽屉收回再补一次"，那正是"松手才重绘"本身 | 按下就发起请求，由 `withFrameNanos` 等到**这一帧画完之后**再取（见 [BackdropProducer.request]）。冻结 / 重放那一套已全部删除 |
 * | 设置里改程度 / 开开关不重绘 | ① 关掉模糊背景时画布被移出组合树，图层为空；② 画布是纯 Canvas，设置变了不重绘；③ "上一张还在糊"被当成失败 | ① 画布常驻；② `graphInvalidate` 计数 + 绘制阶段读；③ `RequestResult.BUSY` 单独一态，不算失败 |
 * | 晕染每次都播 | 动画跟着"换图"走 | 只有 `everPublished == false` 的那一张才播（[BackdropImage.animated]） |
 *
 * @param enabled 模糊背景开关。关的时候什么都不做。
 * @param themeKey 主题变化时自动重做一次（底色不同，糊出来的观感不同）。
 * @param themeBackground 当前主题底色（ARGB），压暗层用它。
 * @param snapshot 取一帧"只有绘图内容"的图；取不到（画布还没画出来）返回 null。
 * @param strength 模糊程度 0f~1f；0 = 不糊，1 = 3.20 那一档。
 * @param startupReason 首次组合时那次请求算哪种"开张"。默认 [BackdropReason.STARTUP]；
 *        设置页里可以用 [BackdropReason.STRENGTH] 等覆盖（见 `GraphScreen`）。
 */
@Composable
internal fun rememberBackdropProducer(
    enabled: Boolean,
    themeKey: Any,
    themeBackground: Int,
    snapshot: (suspend () -> androidx.compose.ui.graphics.ImageBitmap?)?,
    strength: Float = 1f,
    /**
     * "自动重做"的 key 里用的程度值（3.45）。
     *
     * 默认等于 [strength]。设置页里要传**已提交**的值（`state.settings.blurStrength`）：
     * 草稿在拖动中每格都变，若拿它当 key，自动 effect 会在拖动全程反复重启
     * 并请求重糊 —— 手表上一张 466×466 的 RenderScript 卷积 20~40ms，
     * 逐格做必然掉帧。传已提交值，自动重做就只在"真正生效"时发生一次，
     * 而"松手立刻重绘"由设置页的 `onStrengthCommit` → `requestAt` 负责。
     */
    autoStrengthKey: Any? = strength,
    /**
     * 让取图源那一层重绘一次（一般是 `graphCapture.invalidate()`）。
     *
     * 为什么需要：画布是纯 Canvas，视口/曲线/设置不变就一个像素都不重画，
     * `GraphicsLayer` 里便一直是上次录下的内容。取图前必须先把它赶脏。
     */
    invalidateSource: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current

    // ---- ⚠️ 3.45：producer 只能建一次，不能 `remember(snapshot)` ----
    //
    // 用户报："只有第一次按住提示条能立刻重绘，第二次又变回松手才重绘。"
    //
    // 根因就在这里：`snapshot` 是调用方每次重组**新建**的 lambda（它捕获
    // `graphCapture`，写在 `GraphScreen` 的参数位上）。拿它当 `remember` 的 key，
    // 等于"每次重组都造一个新的 producer"：
    //
    //   * 新 producer 的 `running` 是 false、`pending` 是空的；
    //   * 上一轮按下时正在跑的那个 producer 被丢弃，但它的协程还活着，
    //     会在稍后自己也 publish 一次（时序全乱）；
    //   * `DisposableEffect` 的 key 跟着变 → 每次重组 dispose 一遍，
    //     `BackdropImage.request` 被"清空 → 重设"一次。
    //
    // 第一次按下之所以看起来成功，是因为那次恰好触发了重组，
    // **重组本身带来了一次重绘**，`awaitAdvance` 被这次重绘满足了；
    // 第二次按下时界面完全静止、没有重组，于是就卡在等帧里直到超时，
    // 等手指一动（松手前的那一下）才终于有帧可拍 —— 这就是"第二次变回松手才重绘"。
    //
    // 修法：生产者和它的两个回调都用 `rememberUpdatedState` 包住，
    // `remember` 不再带任何 key。producer 全程只有一个，`running` 才有意义。
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentInvalidate by rememberUpdatedState(invalidateSource)
    val producer = remember {
        BackdropProducer.withContext(context) { currentSnapshot?.invoke() }
    }

    // 卷积线程与 RenderScript 上下文跟着这一屏走：离开时收掉。
    DisposableEffect(producer) {
        onDispose { producer.dispose() }
    }

    // ---- 3.44：晕染"第一次"的闩由 [BackdropImage.revealPlayed] 统一持有 ----
    var lastStrength by remember { mutableStateOf(strength) }

    // ---- ⚠️ 3.44：requestFor 里的 strength 必须读**最新值** ----
    //
    // `DisposableEffect` 的 key 不含 `strength`（它只在 enabled / producer /
    // invalidateSource 变化时重建），所以 lambda 会**捕获创建那一刻的 strength**。
    // 用户在设置页里把程度从 70% 拨到 30% 时，`requestFor` 仍然拿 0.7 去做图 ——
    // 表现就是"拨了半天，出来的还是旧的糊度"，而且因为那一次确实出了图，
    // 看起来像"生效了一半"。
    //
    // `rememberUpdatedState` 让闭包里读到的永远是**最近一次组合**的值。
    val currentStrength by rememberUpdatedState(strength)
    val currentBackground by rememberUpdatedState(themeBackground)

    // ---- 3.45：把 View 交给 DrawnTicker，见那边的 `viewInvalidator` ----
    //
    // 这是"第二次按住不重绘"的另一半修法：`drawRequests++` 只让绘制节点失效，
    // 而"谁来开这一帧"并不由它决定。`view.invalidate()` 是 View 层的强制失效，
    // 一定会排一次 traversal —— 下一帧必到。
    DisposableEffect(view) {
        DrawnTicker.viewInvalidator = { view.invalidate() }
        onDispose { DrawnTicker.viewInvalidator = null }
    }

    // key 刻意只留 `enabled`：`producer` 已经稳定（`remember` 无 key），
    // `invalidateSource` 走 `currentInvalidate` 读最新值，不再当 key。
    DisposableEffect(producer, enabled) {
        BackdropImage.invalidateSource = { currentInvalidate() }
        if (enabled && snapshot != null) {
            // 请求体存成一份可重放的闭包：手势那边按下时只调它，
            // 参数（底色 / 程度）永远是**调用那一刻**的最新值。
            val body: (BackdropReason) -> Unit = { why ->
                BackdropImage.pendingReason = why
                producer.request(currentBackground, currentStrength, why)
            }
            BackdropImage.requestFor = body
            // 带显式程度的入口：设置页滑条松手时用（见 `requestAt` 的注释）。
            BackdropImage.requestAt = { s, why ->
                BackdropImage.pendingReason = why
                producer.request(currentBackground, s, why)
            }
            // 手势那条路固定 OTHER（碰提示条绝不播晕染）。
            BackdropImage.request = { body(BackdropReason.OTHER) }
        } else {
            BackdropImage.request = null
            BackdropImage.requestFor = null
            BackdropImage.requestAt = null
        }
        onDispose {
            BackdropImage.request = null
            BackdropImage.requestFor = null
            BackdropImage.requestAt = null
            BackdropImage.invalidateSource = null
        }
    }

    // ---- 3.44：晕染"第一次"的闩由 [BackdropImage.revealPlayed] 统一持有 ----
    //
    // 三种开张各有一个"已经播过"的记号，规则见 [shouldAnimateFor]：
    //   * 进 App 第一张：整个进程只有一次；
    //   * 打开开关后的第一次：**关掉就清闩**，所以再打开会重新播；
    //   * 调整程度后的第一次：**程度再变就清闩**，所以每调一次播一次。
    var lastEnabled by remember { mutableStateOf(enabled) }
    LaunchedEffect(enabled, strength) {
        if (!enabled) {
            BackdropImage.clearReveal(BackdropReason.TOGGLED_ON)
        }
        if (strength != lastStrength) {
            BackdropImage.clearReveal(BackdropReason.STRENGTH)
            lastStrength = strength
        }
        lastEnabled = enabled
    }

    // 开关刚打开 / 刚进 App / 换了主题 / 拖完模糊程度：自动做一帧。
    //
    // 重点有两个（3.41）：
    //   ① **失败要重试**。画布还没画过时取图是 null，等一会儿再试，
    //      直到成功或超过 [BACKDROP_RETRY_DELAYS_MS] 的总时长。
    //   ② **"上一张还在糊"不算失败**，等它糊完就够 —— 以前把它当失败，
    //      于是"改完程度立刻又改一次"会让第二次悄悄不生效。
    //
    // ⚠️ 这里不再延迟：`BackdropProducer` 自己会等"绘制完成"再取图，
    // 早一点发起反而取到的是新图。延迟只会让"改了没反应"多持续几百毫秒。
    //
    // ⚠️ 3.44：这个 effect 只负责 `enabled=true` 时的**首帧兜底**（进 App、
    // 换主题、画布刚重建）。它一律报 `OTHER`（不播晕染）——
    // "打开开关 / 调程度"那两种带原因的请求，走的是 [BackdropImage.requestFor]，
    // 由设置页显式发起（见 `GraphScreen.requestBackdropForSettings`）。
    // 这样就不会出现"某个 effect 顺手又播了一次动画"。
    LaunchedEffect(enabled, themeKey, autoStrengthKey) {
        if (!enabled || snapshot == null) {
            BackdropImage.reset()
            return@LaunchedEffect
        }
        var tried = 0
        for (delayMs in BACKDROP_RETRY_DELAYS_MS) {
            if (tried > 0) kotlinx.coroutines.delay(delayMs)
            tried++
            when (producer.request(themeBackground, strength, BackdropReason.OTHER)) {
                // ⚠️ BUSY 也要**排队补一次**（见 `BackdropProducer.pending`）：
                // 手上那张是用旧参数糊的，等它落地后再做一次新的，
                // 否则"快速拨滑条"的最后一下会永远不生效。
                RequestResult.STARTED, RequestResult.BUSY -> return@LaunchedEffect
                RequestResult.NO_SOURCE -> Unit                  // 再等一轮
            }
        }
        BackdropImage.fail("画布一直没画出来（试了 $tried 次）")
    }
}

/**
 * "自动做一帧"的重试节奏（毫秒）。
 *
 * 400 = 首帧画完；再 400 = 设置页刚退出、图像界面才回到前台；
 * 再 600 = 慢机型上画布重建。三档足够，再等用户已经在看空背景了。
 */
internal val BACKDROP_RETRY_DELAYS_MS = longArrayOf(400L, 400L, 600L)

/**
 * 等一帧绘制完成的上限（毫秒）。
 *
 * ## 3.45：120 → 200，而且理由变了
 *
 * 旧注释说"8 帧余量就够"。但那一版是**一次性唤醒**（注册一个 Deferred 等它被
 * 完成），超时意味着"这一轮彻底放弃"。现在改成轮询（见 [DrawnTicker.awaitAdvance]），
 * 多等几十毫秒不影响正确性 —— 拿到帧就立刻返回，只有"真的没在画"才会用满。
 *
 * 而"真的没在画"有两种情况必须容忍：
 *   * 慢机型上 `RenderScript` 初始化 + 图层读回可能让这一帧拖到 150ms 以上；
 *   * 按下提示条那一帧正好撞上一次设置页的退场转场。
 *
 * 200ms 仍然远低于人能察觉"卡了一下"的阈值（约 300ms），
 * 而它换来的是"第一次和第二次都一样快"，不会偶尔退回松手才重绘。
 */
internal const val FRAME_WAIT_TIMEOUT_MS = 100L

/**
 * "直接取图层"这一步的最长等待（2026-10-28）。
 *
 * `GraphicsLayer.toImageBitmap()` 是挂起函数（图层是异步资源）。正常情况下它
 * 几十毫秒就回来；但一旦设备上某次读回卡住，**整个产线会被它占死**：
 * `running` 一直是 true，之后每一次按下都只能拿到 BUSY 排进 `pending`，
 * 于是表现成"按住一直不刷新"。给它一个上限，超时就退回缓存帧，
 * 至少保证每次按下都能出一张、产线不会卡死。
 */
internal const val CAPTURE_TIMEOUT_MS = 250L

/**
 * **"这一帧真的画完了"的信号源**（3.41 第四版）。
 *
 * ## 为什么需要它
 *
 * `GraphicsLayer` 的内容是在**绘制阶段**录的（`Modifier.drawWithContent`）。
 * 想在"按下手指"那一拍取到"此刻屏幕上的图"，就必须等这一次绘制真的跑完 ——
 * 而 Compose 没有直接暴露这样的回调：
 *
 *   * `withFrameNanos` 在 **animation 阶段**恢复，那时还没 draw；
 *   * `Handler.post` 比绘制更早；
 *   * `ViewTreeObserver.onDrawListener` 只能读、不能挂起。
 *
 * 所以自己造一个：[modifier] 是一个 `drawWithContent`，在绘制阶段被调用时把
 * [frame] 加一，并唤醒所有等"下一次绘制"的协程。
 *
 * ## ⚠️ 必须挂在 `capture.modifier` 的**外层**
 *
 * `Modifier` 链是"外层包内层"，而 `drawContent()` 就是"把内层画出来"。
 * 所以挂在**外层**时：
 *
 * ```
 * 外层 drawWithContent {
 *     drawContent()   // ← 这一句里，内层的 layer.record 已经执行完了
 *     onFrameDrawn()  // ← 所以此刻计数，图层里一定是新的
 * }
 * ```
 *
 * 挂在内层就反了：计数时外层还没画，图层也不一定录过。
 */
internal object DrawnTicker {

    /**
     * 已经画完的帧数。
     *
     * ⚠️ **故意不用 `mutableStateOf`**：这个值会在**绘制阶段**被写，
     * 而 Compose 的规则是"绘制阶段不能写会被组合读的 state"（会触发
     * "Reading a state that was created after the snapshot was taken"
     * 或者无限重绘）。这里没人需要在组合里读它 —— 只有协程轮询它，
     * 所以用普通 `var` 即可，全是主线程，无需同步。
     */
    private var frame = 0

    /**
     * 这个 modifier 必须挂在会**每帧都被画**的那一层上。
     *
     * 内容本身不画任何东西（`drawContent()` 后面没有别的），只负责计数。
     */
    val modifier: Modifier
        get() = Modifier.drawWithContent {
            // 读一次请求计数：它变 → 本节点变脏 → 下一帧必定重跑（见 [requestDraw]）。
            if (drawRequests >= Int.MIN_VALUE) {
                this@drawWithContent.drawContent()
                onFrameDrawn()
            }
        }

    /**
     * "我需要下一帧被画一次"的记号（3.41）。
     *
     * [modifier] 本身也是 `drawWithContent`，在绘制阶段读这个计数就会被登记成依赖；
     * 值一变，它下一帧必然重跑 —— 于是 [awaitAdvance] 一定等得到。
     *
     * 为什么需要：画布是纯 Canvas，输入不变就不重绘。按下提示条时画面**确实没变**
     * （抽屉位移还是 0），不主动标脏的话，这一帧根本不会画 —— 等着"画完"的
     * 取图逻辑就会一直等到超时。这大概就是前几版"松手才重绘"的最后一环：
     * 手指按下时界面不动 → 一帧都不画 → 取图超时放弃 → 等松手抽屉动起来、
     * 画面终于重绘了，才有新帧可拍。
     */
    private var drawRequests by mutableStateOf(0)

    /**
     * 请求"下一帧务必画一次"。
     *
     * ## ⚠️ 3.44：光把 state 改一格**不够**（这才是"松手才重绘"的真凶）
     *
     * [modifier] 在**绘制阶段**读 `drawRequests`，所以 `drawRequests++` 会让那个
     * 绘制节点失效 —— 但"失效"只意味着"下次画面时重画它"，
     * **并不代表会有人开始下一次画面**。
     *
     * Compose 的帧循环由 choreographer 驱动，只在"有人明确要一帧"时启动：
     * 动画在跑、手势在分发、或者有 `withFrameNanos` / `Recomposer` 要求提交。
     * 手指**按下**提示条的那一瞬间，这三种都没有 —— 抽屉位移还是 0、
     * 没有任何状态变、也没有动画。于是：
     *
     * ```
     * 按下 → drawRequests++ → 节点脏了 → 但没人要下一帧 → 画面不动
     *      → DrawnTicker.awaitAdvance 等到超时 → 放弃
     * 手指一动 → 抽屉跟手 → 界面真的变了 → 终于开帧 → 才取到图
     * ```
     *
     * 用户看到的正是"要动了/松手了才刷新"。前几轮一直以为是"等帧的闸门放错地方"，
     * 其实是**根本没有下一帧**。
     *
     * 修法：这里主动向 choreographer 要一帧（`withFrameNanos` 只要被调用就会把
     * 帧循环拉起来）。回调本身不做任何事，它的作用就是"让这一帧发生"。
     * 用 `MainScope` 而不是组合作用域：这个方法可能在手势闭包里被调用，
     * 那一刻没有可挂起的调用方。
     */
    fun requestDraw() {
        drawRequests++
        // ① Compose 层：让绘制节点失效（上面的 state 读）。
        // ② View 层：**强制**排一次 traversal —— 这一句才是"下一帧一定会来"的保证。
        //
        // 只做 ① 不够：节点失效只表示"下次画面时重画它"，而"谁来决定开始下一次画面"
        // 是另一回事。Compose 的帧循环由 choreographer 驱动，只在有人明确要一帧时
        // 才启动（动画在跑 / 手势在分发 / 有 frame clock 请求）。手指**按下**提示条
        // 那一拍这三样都没有 —— 用户第一次按住能成，是因为那次恰好赶上一次重组，
        // 重组自带重绘；第二次界面完全静止，就卡在等帧里直到超时。
        //
        // `view.invalidate()` 是 View 层的硬保证：AndroidComposeView 会被标脏，
        // 下一帧的 traversal 必然把整棵 Compose 树画一遍（含我们要的那个图层）。
        viewInvalidator?.invoke()
        // ③ Choreographer：再挂一次帧回调兜底（某些 ROM 上 invalidate 之后
        // 若没有其它失效源，traversal 可能被合帧推迟）。
        //
        // ⚠️ 不能用 `withFrameNanos`：那个挂起函数要求协程上下文里带
        // `MonotonicFrameClock`（只有 Compose 自己的作用域有），普通
        // `CoroutineScope` 一挂就抛 IllegalStateException —— 3.44 第一版
        // 就是这么崩的。`Choreographer.postFrameCallback` 是平台层写法，不需要时钟。
        android.view.Choreographer.getInstance()
            .postFrameCallback { /* 只是拉起这一帧，不做别的 */ }
    }

    /**
     * 由 `rememberBackdropProducer` 注入：`view.invalidate()`。
     *
     * 为什么放在这里而不是让调用方自己 invalidate：`requestDraw()` 有多个入口
     * （按下提示条、开开关、调程度、退出设置补做），漏一个就是那个入口"没反应"。
     */
    internal var viewInvalidator: (() -> Unit)? = null

    private fun onFrameDrawn() {
        frame++
    }

    /**
     * 等"至少再画完一帧"。超时返回 false。
     *
     * ## ⚠️ 3.45：改成"记下起始帧号 + 轮询"，不再用一次性 Deferred
     *
     * 旧写法是注册一个 `CompletableDeferred` 然后等它被 `onFrameDrawn` 完成。
     * 问题在于**注册时机**：协程从 `request()` 到真正执行到 `waiters += d`
     * 之间隔了一次调度（`scope.launch`），如果这一帧的绘制恰好在那之前完成，
     * 唤醒就丢了 —— 然后一直等到超时。而超时之后手指一动（抽屉跟手）又画了一帧，
     * 看起来就是"松手才重绘"。第一次能成、第二次不成的间歇性，正是这种竞态。
     *
     * 现在改成：**先记下当前帧号**，再每 4ms 看一眼帧号有没有前进。
     * 没有唤醒丢失这回事 —— 只看"现在比刚才新了没有"。
     * 轮询只在"按下提示条"这一次发生，最长 [FRAME_WAIT_TIMEOUT_MS]，
     * 每次 4ms，最多 30 次判断，开销可以忽略。
     */
    suspend fun awaitAdvance(timeoutMs: Long): Boolean {
        val start = frame
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (frame != start) return true
            kotlinx.coroutines.delay(4L)
        }
        // 最后一拍再确认一次：可能刚好在 deadline 那一瞬间画完。
        return frame != start
    }
}

/** [BackdropProducer.request] 的结果。 */
internal enum class RequestResult {
    /** 已经开始做这一帧（取图 → 糊 → 发布）。 */
    STARTED,

    /** 上一张还在糊。**不是失败** —— 它落地后就是最新的样子。 */
    BUSY,

    /** 画布还没画过，取不到图。调用方可以过一会儿再试。 */
    NO_SOURCE
}

/**
 * 真正干活的：负责"取 → 糊 → 发布"。
 *
 * 取图在协程（主线程调度）里做，糊在后台线程，发布回主线程。
 * 同一时间只跑一次：上一张还没糊完时再点提示条，直接忽略。
 */
internal class BackdropProducer(
    private val snapshot: (suspend () -> androidx.compose.ui.graphics.ImageBitmap?)?
) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var running = false

    /**
     * 卷积专用的**常驻后台线程**（2026-10-28）。
     *
     * 以前每做一张都 `Thread(...).start()`，而 `RenderScriptBlur` 是**每次新建、
     * 用完 destroy** —— RenderScript 的上下文创建在手表上要几十到上百毫秒，
     * 等于每按一次提示条都先付一次建上下文的钱。表现就是：手指按下时还什么都没发生，
     * 等算完（手指已经抬起）才换图 —— "松手才重绘"。
     *
     * 现在线程常驻、上下文只建一次，后面每张都复用。拖动期间也就不会再和
     * UI 线程抢 RenderScript 的初始化。
     */
    private val blurThread = android.os.HandlerThread("backdrop-blur").apply { start() }
    private val blurHandler = android.os.Handler(blurThread.looper)
    private var blurAlgorithm: eightbitlab.com.blurview.RenderScriptBlur? = null

    /** 界面彻底离开时（组合被销毁）收掉线程与 RenderScript 上下文。 */
    fun dispose() {
        val handler = blurHandler
        handler.post {
            runCatching { blurAlgorithm?.destroy() }
            blurAlgorithm = null
            blurThread.quitSafely()
        }
    }

    /**
     * "正在糊的那张"还没糊完时，后来又要求的那一份参数（3.41）。
     *
     * 场景：用户在滑条上快速拨了几格，第一张还在 RenderScript 里算。
     * 旧代码把后面几次直接丢掉（BUSY），于是**最后停在哪一格反而不生效**，
     * 看上去就是"拨了半天没反应，退回去再进来才变"。
     *
     * 现在把它记下来，手上的活一结束就补一张 —— 只留**最新的一份**，
     * 中间那些过时的参数没有任何意义。
     */
    private var pending: Pair<Int, Float>? = null

    /** 排队那一份的**原因**（3.44）：排队的补做也必须沿用原来的"开张"语义。 */
    private var pendingReason: BackdropReason = BackdropReason.OTHER

    /** 上一次用的半径（诊断用）。 */
    private var lastRadius = 0f

    /**
     * 请求做一帧。
     *
     * ## "松手才重绘"的真正原因（3.41 第四版才找对）
     *
     * 前两版都以为 `withFrameNanos { }` 是"这一帧画完之后"的闸门 —— **它不是**。
     *
     * `withFrameNanos` 的回调在**帧开始时**（`Choreographer` 的 animation 阶段）
     * 恢复，而 Compose 的**绘制**发生在 measure/layout 之后的 draw 阶段。
     * 所以 `withFrameNanos { }` 回来时，这一帧的 `layer.record` 还**没跑**，
     * 图层里是上一帧的内容。等它真的录完，已经是下一次回调了 —— 而那一次
     * 往往要等手指抬起后界面再动一下才来。表现就是"松手才重绘"。
     *
     * 正确的闸门是 [DrawnTicker]：它挂在一个**绘制阶段**的
     * `drawWithContent` 上，计数前进就说明"这一帧的内容已经落到画布上了
     * （包括我们要取的图层）"。
     */
    fun request(
        themeBackground: Int,
        strength: Float,
        reason: BackdropReason = BackdropReason.OTHER
    ): RequestResult {
        if (snapshot == null) return RequestResult.NO_SOURCE
        // 手势期间不抢主线程（见 [hold]）：这一张直接不要，
        // 落位后抽屉那边会再要一次。
        if (BackdropImage.hold) return RequestResult.NO_SOURCE
        if (running) {
            // 记下这一份最新参数，手上的活结束后补做（见 `pending` 的注释）。
            //
            // ⚠️ 3.45：`OTHER` 不能盖掉一个**有原因**的排队请求。
            // 设置页里打开开关时，会先发一条 `TOGGLED_ON`（那条要播晕染），
            // 紧接着重组又让自动 effect 发一条 `OTHER`。后者只是"补一张当前画面"，
            // 若把原因覆盖掉，用户看到的就是"打开开关了但没晕染"。
            val queued = pending
            val queuedReason = pendingReason
            pending = themeBackground to strength
            BackdropImage.refreshing = true
            pendingReason = if (reason == BackdropReason.OTHER &&
                queued != null && queuedReason != BackdropReason.OTHER
            ) {
                queuedReason
            } else {
                reason
            }
            return RequestResult.BUSY
        }
        running = true
        BackdropImage.refreshing = true
        // 先"点一下"：让画布那一层必然重绘一次，图层才会重录。
        BackdropImage.invalidateSource?.invoke()
        DrawnTicker.requestDraw()
        scope.launch { runOnce(themeBackground, strength, reason) }
        return RequestResult.STARTED
    }

    /**
     * 真正做一张：等绘制完成 → 取图 → 后台糊 → 发布。
     *
     * ## 3.44：按下提示条那一刻会"自己造一帧"
     *
     * 用户第四次说"按住提示条就刷新绘制"。前几版一直以为是"等帧的闸门放错地方"，
     * 其实真正的死角在这里：**手指按下的那一拍，界面没有任何状态变化** ——
     * 抽屉位移还是 0、曲线没动、动画没跑。Compose 对"输入没变"的帧
     * 不会重绘，`DrawnTicker.awaitAdvance` 就永远等不到，只能超时放弃；
     * 而等手指一动（还在按着）抽屉开始跟手，界面终于重绘了 ——
     * 那一帧被 `DrawnTicker` 记到数，取图才成功。可那时已经是"拖动中"，
     * 用户感知成"要松手/要动起来才刷新"。
     *
     * 所以 [request] 里先 `DrawnTicker.requestDraw()` 把绘制层**标脏**，
     * 下一帧必定画一次（哪怕内容一模一样）。这样按下 → 下一帧(8~16ms) →
     * 取图，全程不需要手指移动。
     */
    private suspend fun runOnce(
        themeBackground: Int,
        strength: Float,
        reason: BackdropReason
    ) {
        // ---- 3.45：整个函数是一个"占用产线"的临界区 ----
        //
        // 任何一条路径（超时、取图失败、后台线程起不来、publish 抛异常）
        // 都必须走到 [finishAndDrain]，否则 `running` 停在 true，
        // 下一次按下只会拿到 BUSY 并被丢进 `pending` —— 而 `pending`
        // 只有 `finishAndDrain` 才会被消费，于是彻底死锁。
        //
        // 用户报的"第一次能重绘，之后每次都变回松手才重绘"，
        // 最可能就是某一次异常把产线占死了。这里用 `released` 标记
        // 保证只放行一次（后台线程那条路是异步的，不能重复放行）。
        var released = false
        fun release() {
            if (released) return
            released = true
            finishAndDrain()
        }

        try {
            // ---- 3.45：改糊度不需要重新取图，直接重糊缓存 ----
            //
            // 这是"在设置页松手就立刻看到新糊度"的关键。用户在设置页里改的是
            // **半径**，画面内容一个像素都没变；而设置页是整屏路由、画布已经
            // 离开组合树，等帧必然超时。既然原图就在手上（`sourceFrame`），
            // 直接换半径重卷积即可 —— 零等待。
            //
            // 只有"画面可能变了"的原因（碰提示条 / 退出设置补做 / 切主题 / 开屏）
            // 才需要等新的一帧。
            val reuseCache = (reason == BackdropReason.STRENGTH ||
                reason == BackdropReason.TOGGLED_ON ||
                reason == BackdropReason.THEME) &&
                BackdropImage.sourceFrame != null

            suspend fun captureFresh(): Bitmap? =
                withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    runCatching { snapshot?.invoke()?.asAndroidBitmapSafe() }
                        .onFailure { BackdropImage.fail("取画布失败：${it.message}") }
                        .getOrNull()
                }

            // ---- 1.2.43：**不再等帧**，直接取 ----
            //
            // 前几版一直"松手才刷新"，病灶就在这里：
            //
            //   * 界面静止时（没动画、没手势），`View.invalidate()` /
            //     `drawRequests++` 只是把节点标脏，**并没有谁真的开始一次绘制**；
            //   * `DrawnTicker.awaitAdvance()` 等不到帧 → 超时；
            //   * `captureFresh()` 于是拿不到新帧 → `pixels` 退回
            //     `BackdropImage.sourceFrame`（很久以前那张原图）→ 发布出去，
            //     画面看着**一点没变**（这就是"按了也不刷新"）；
            //   * 等松手、抽屉弹一段弹簧动画时帧才真的开始流，
            //     那一次的取图才成功 —— 所以永远表现为"松手才刷新"。
            //
            // 而"等这一帧"本来就不必要：预糊是在操作完毕 260ms 之后才跑的，
            // 这 260ms 里画布早就按最新状态重绘完了，图层里就是当前画面。
            // 直接取，拿到的就是这一帧。
            val fresh = if (reuseCache) null else captureFresh()
            if (fresh != null) BackdropImage.rememberSource(fresh)
            // 取不到新帧时退回"最近一帧原图"：设置页里改糊度就是靠它出图的。
            val pixels = fresh ?: BackdropImage.sourceFrame

            if (pixels == null) {
                // 连一帧原图都没有（从没渲染过）：不写 fail，交给重试循环。
                release()
                return
            }
            blurHandler.post {
                val out = runCatching { blurViaBlurView(pixels, themeBackground, strength) }
                    .getOrNull()
                scope.post {
                    try {
                        if (out != null) {
                            BackdropImage.publish(
                                bitmap = out.asImageBitmap(),
                                radius = lastRadius,
                                reason = reason
                            )
                        } else {
                            BackdropImage.fail("BlurView 没给出结果")
                        }
                    } catch (t: Throwable) {
                        BackdropImage.fail("发布背景失败：${t.message}")
                    } finally {
                        release()
                    }
                }
            }
        } catch (t: Throwable) {
            // 走到这里说明是取消 / 线程创建失败之类的意外。
            BackdropImage.fail("重做背景失败：${t.message}")
            release()
        }
    }

    /**
     * 一张做完了：放行产线，并且如果期间又有人改过参数，立刻补做**最新那一份**。
     *
     * 为什么在这里补而不是让调用方等：调用方的 `LaunchedEffect` 可能已经被
     * 新的 key 重启掉了（它自己也会再请求一次），但那个请求会撞上 `running`。
     * 只有产线自己知道"什么时候空出来"，所以补做的责任放在这里。
     */
    private fun finishAndDrain() {
        val next = pending
        val why = pendingReason
        pending = null
        pendingReason = BackdropReason.OTHER
        if (next == null) {
            running = false
            // 1.2.47：产线空了 → 抽屉的门打开（它会把手指要求的进度补上）
            BackdropImage.refreshing = false
            return
        }
        // 保持 running = true，直接接上下一次，避免"放开 → 又被别人抢走"的竞态。
        scope.launch { runOnce(next.first, next.second, why) }
    }

    /**
     * 用 BlurView 3.2.0 的**算法对象**（`RenderScriptBlur`）把 [source] 糊一遍。
     *
     * 这就是 BlurView 在 Android 11 上真正调用的那段代码
     * （`BlurView.getBlurAlgorithm()` 在 SDK < 31 时返回的就是它）——
     * 我们复用它的实现，而不是自己手写卷积。
     *
     * ## ⚠️ 3.44：必须**自己先缩小**再卷积，否则程度永远是"一样糊"
     *
     * 反编译 3.2.0 的 `RenderScriptBlur.blur()`：
     *
     * ```
     * Allocation.createFromBitmap(rs, bitmap);     // ← 原图尺寸，没有缩小
     * blurScript.setRadius(Math.min(r, 25.0f));    // ← 硬上限 25
     * blurScript.forEach(out);
     * out.copyTo(bitmap);
     * // 整个方法体包在 try/catch 里：catch (Exception e) { Log.e(...); }
     * // 然后 return bitmap;  ← **返回的是原图，而且不抛**
     * ```
     *
     * 两个后果：
     *
     * 1. **半径 25 落在原图上是"几乎看不出糊"**。这块表短边约 466px，
     *    25px 的高斯半径在这个尺度上只相当于"轻微柔化"；而滑条 70% 算出来是
     *    12.6、100% 是 25 —— 两个都在"轻微柔化"里，肉眼就是一样糊。
     *    这就是用户说的"所有程度都是一样模糊"。
     * 2. **库自己的异常是静默的**。`catch` 里只打 log，然后 return 原图 ——
     *    我们这边 `runCatching` 拿到的是"成功返回了一张没糊的图"，
     *    `lastError` 一直空着，诊断行看起来一切正常。
     *
     * 修法：**先把图缩到 1/[BLUR_DOWNSCALE] 再交给它**（这正是 BlurView 内部
     * `SizeScaler` 干的事，只不过我们那条链路上 `RenderScriptBlur` 是裸调的）。
     * 缩小 4 倍后，同样的 25px 半径覆盖的原图像素是 100px，观感才真的是"磨砂玻璃"；
     * 而且半径在小图坐标系里是 1~25 的整数档，**每一档都肉眼可分**，
     * 滑条左半段调一格就看得见。
     */
    private fun blurViaBlurView(source: Bitmap, themeBackground: Int, strength: Float): Bitmap {
        val radiusDp = glassBlurRadiusDp(rootContext(), strength)
        val radiusPx = blurViewRadius(radiusDp, densityPxPerDp(rootContext()))
        lastRadius = radiusPx

        // 强度 0（或半径被削成 0）：不卷积，直接拿原图压暗 —— 用户要的"0% 就是不糊"。
        val blurred = if (radiusPx < 0.5f) {
            BackdropImage.lastScaledSize = null
            source
        } else {
            // ① 缩到小图（这一步就是 BlurView 的 SizeScaler，我们自己做，
            //    因为下面裸调的是 RenderScriptBlur，它不缩）。
            val small = scaleBitmap(source, BLUR_DOWNSCALE)
            if (small == null) {
                BackdropImage.lastScaledSize = null
                BackdropImage.fail("缩图失败（${source.width}×${source.height}）")
                return tinted(source, themeBackground)
            }
            BackdropImage.lastScaledSize = "${small.width}×${small.height}"
            // ② 在小图坐标系里卷积。**复用**常驻的 RenderScript 上下文，
            //    不再每次新建 / destroy（那是按下后要等上百毫秒的主因）。
            val out = runCatching {
                val algorithm = blurAlgorithm ?: eightbitlab.com.blurview.RenderScriptBlur(
                    rootContext()
                ).also { blurAlgorithm = it }
                algorithm.blur(small, radiusPx) ?: small
            }.getOrElse {
                BackdropImage.fail("RenderScript 不可用：${it.message}")
                return tinted(source, themeBackground)
            }
            // ③ **不再撑回原尺寸**（2026-10-28）。
            //
            // 糊过的图本来就没有细节，`Image` 用 GPU 双线性铺满整屏即可，
            // 结果和"先放大 4 倍再贴"肉眼无差；省掉的是一次全屏位图分配 +
            // 一次全屏拷贝（手表上约 0.9MB 的分配，正好落在拖动开始那一帧，
            // 是"跟手一顿"的来源之一）。
            out
        }

        return tinted(blurred, themeBackground)
    }

    /** 压暗：10% 主题底色，和 3.20 的 tint 同口径。 */
    private fun tinted(src: Bitmap, themeBackground: Int): Bitmap {
        val full = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        Canvas(full).drawColor(
            android.graphics.Color.argb(
                (GLASS_BACKDROP_TINT * 255f).toInt(),
                android.graphics.Color.red(themeBackground),
                android.graphics.Color.green(themeBackground),
                android.graphics.Color.blue(themeBackground)
            )
        )
        return full
    }

    /** RenderScriptBlur 需要 Context（拿 RenderScript 的实例）。 */
    private var contextRef: Context? = null

    private fun rootContext(): Context =
        contextRef ?: error("BackdropProducer 没有 Context")

    companion object {
        /**
         * Compose 侧拿不到 Context 就干脆在创建时塞进来。
         * （RenderScriptBlur 的构造参数只有 Context 一个用途。）
         */
        fun withContext(context: Context, snapshot: (suspend () -> androidx.compose.ui.graphics.ImageBitmap?)?) =
            BackdropProducer(snapshot).also { it.contextRef = context.applicationContext }
    }
}

/**
 * 把 [src] 缩到 1/[factor]（BlurView 的 `SizeScaler` 同款做法）。
 *
 * 用 [Bitmap.createScaledBitmap] 的双线性插值：缩小时它本身就是一次
 * 低通滤波，等于免费多糊了一点点，观感更接近毛玻璃。
 */
private fun scaleBitmap(src: Bitmap, factor: Float): Bitmap? {
    val w = (src.width / factor).toInt().coerceAtLeast(1)
    val h = (src.height / factor).toInt().coerceAtLeast(1)
    if (w == src.width && h == src.height) return src
    return runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrNull()
}

/** HARDWARE 位图拷不动 PNG 也进不了 RenderScript，先换成软件位图。 */
private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapSafe(): Bitmap? {
    val raw: android.graphics.Bitmap = runCatching { this.asAndroidBitmap() }.getOrNull()
        ?: return null
    return runCatching {
        if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            raw
        }
    }.getOrNull()
}

/** 主线程投递（糊完发布必须回主线程——`mutableStateOf` 的写没有线程限制，但语义上收在主线程最稳）。 */
private fun CoroutineScope.post(block: () -> Unit) {
    launch { block() }
}

/**
 * [GlobalBackdrop] 的交叉淡入黑板（2026-10-27）。
 *
 * 刻意**不是** snapshot state：这里的值只在组合阶段读写，
 * 目的是让"这张是新换的"在**同一帧**就能判定出来。
 * 用 `mutableStateOf` 反而会额外触发一轮重组，且先组合后生效 ——
 * 正是那半拍造成了"背景闪一下"。
 */
private class CrossfadeHolder(
    /** 当前画在屏幕上的那张。 */
    var shown: androidx.compose.ui.graphics.ImageBitmap?,
    /** 上一次换图时的 [BackdropImage.generation]。 */
    var generation: Int
) {
    /** 正在淡出的上一张（垫在新图下面）。 */
    var prev: androidx.compose.ui.graphics.ImageBitmap? = null
}

/**
 * 全局背景层：一张静态图 + 压暗层。所有页面共用这一份。
 *
 * 只做两件事：贴图、压 10%。**没有任何计算**。
 *
 * ## 新图上场：从中心晕开（3.40）
 *
 * 用户原话："刚进入应用渲染模糊背景会从没有背景突然显示出背景，给进入应用
 * 和在设置里调整模糊程度、打开模糊背景之后的重新绘制加一个从中间晕染开的动画。"
 *
 * 做法：把新图按"到屏幕中心的距离"渐显 —— 中心先实、四周后实，
 * 视觉上就是一滴墨从中间洇开。
 *
 * 为什么不用 `scale + alpha` 那种缩放式入场：背景是**铺满整屏**的，
 * 缩放会让边缘露出底色、看起来像"图变小了再撑开"；按半径渐显边缘从头到尾
 * 都在原位，只是透明度不同，这才是"晕染"。
 *
 * 代价：动画期间（[BACKDROP_FADE_MS]）每一帧要画一个 `radialGradient` 遮罩。
 * 它只在换图那一刻跑一次，不是常态开销。
 */
@Composable
internal fun GlobalBackdrop(
    modifier: Modifier = Modifier,
    overlayAlpha: Float = GLASS_BACKDROP_TINT,
    /**
     * 换图时要不要播"中心晕染"（1.2.49）。
     *
     * 设置页传 `false`：那里每一格调整都会发一次请求，只要这一次被判定成
     * "要播"（开开关 / 调程度），晕染就会把整屏先用 `baseColor` 擦一遍 ——
     * 用户在设置里看到的就是"背景变成了纯色"。设置页要的是所见即所得，
     * 所以它永远直接贴图。
     */
    playReveal: Boolean = true
) {
    // 底色在组合里取一次：`drawWithContent` 的 lambda 是绘制作用域，
    // 里面读不到 `MaterialTheme`。
    val baseColor = MaterialTheme.colors.background
    Box(modifier.fillMaxSize()) {
        val bmp = BackdropImage.image
        if (bmp != null) {
            // ---- 1.2.42：换图 = **直接换**，不再"旧图垫底 + 淡入" ----
            //
            // 用户原话："抽屉拉上来会有一帧上一次的背景，你把替换的时机也放在
            // 按到提示条之前。"
            //
            // 那一帧旧图不是取图慢，而是"淡入"这个做法本身：为了让新图柔和出现，
            // 第一帧必须把旧图按 α=1 画在底下、新图从 α=0 起 —— 于是不管图什么时候
            // 发布，只要发布发生在抽屉拉开的过程中，眼睛就先看到一帧旧的。
            //
            // 现在直接替换：新图从第一帧起就是 α=1，屏幕上永远不会出现"上一张"。
            // 相邻两张内容本来就一样（只是重新糊了一次），硬切看不出来；
            // 真正要有"入场感"的三种开张仍然走下面的 `reveal` 晕染。
            val animated = BackdropImage.animated
            val gen = BackdropImage.generation

            // 用 `BackdropImage.animated` 当 key：它跟着**每一张图**走。
            val reveal = remember {
                Animatable(if (playReveal && BackdropImage.animated) BACKDROP_HIDDEN else 1f)
            }
            LaunchedEffect(gen, animated, playReveal) {
                if (!playReveal || !animated) {
                    reveal.snapTo(1f)
                    return@LaunchedEffect
                }
                reveal.snapTo(BACKDROP_HIDDEN)
                reveal.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = BACKDROP_FADE_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }

            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val r = reveal.value
                        if (r >= 1f) {
                            // 动画跑完：一个像素都不多画（常态零开销）
                            this@drawWithContent.drawContent()
                        } else {
                            // 半径从 0 涨到"能覆盖四角"的距离；边缘始终在屏外。
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val full = hypot(center.x, center.y)
                            val radius = (full * r).coerceAtLeast(0.5f)
                            drawContent()
                            // 还没被"洇到"的部分用背景色擦掉：这就是渐显。
                            drawRect(
                                brush = Brush.radialGradient(
                                    0.72f to Color.Transparent,
                                    1f to baseColor,
                                    center = center,
                                    radius = radius
                                )
                            )
                        }
                    }
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(baseColor.copy(alpha = overlayAlpha))
            )
        }
    }
}

/**
 * 一次背景重做的**原因**（3.44）。
 *
 * 用户把晕染动画的语义说到底了：
 *
 * > "我不是说只有开屏第一次和在设置里打开模糊背景开关之后的第一次和
 * >  调整模糊程度之后的第一次才有绘制动画吗，你怎么写得每次都有。"
 *
 * 所以"播不播"不能再看"出没出过图"（那个条件只能表达第一条），
 * 必须知道**是谁要的这张图**。枚举一共四类：
 */
internal enum class BackdropReason {
    /** 进 App 后的第一张（`rememberBackdropProducer` 首次组合）。 */
    STARTUP,

    /** 在设置里把「模糊背景」从关切到开。 */
    TOGGLED_ON,

    /** 在设置里挪了「模糊程度」。 */
    STRENGTH,

    /** 在设置里切夜间/日间或换了主题配色（1.2.50）：底色变了，用缓存原图重出一张。 */
    THEME,

    /** 其余一切：碰提示条、切主题、退出设置/编辑器、改显示项……**不播**。 */
    OTHER
}

/**
 * 这一张要不要播晕染（3.44）。
 *
 * 规则只有三条，且每条都是"第一次"：
 *
 * | 原因 | 什么时候播 | 之后 |
 * |---|---|---|
 * | [BackdropReason.STARTUP] | 进程里的第一张 | 永远不再播 |
 * | [BackdropReason.TOGGLED_ON] | 开关被打开的第一次 | 关掉再开才重播（由 [BackdropImage] 清闩）|
 * | [BackdropReason.STRENGTH] | 程度变了之后的第一次 | 再变一次才重播 |
 * | [BackdropReason.OTHER] | **永远不播** | —— |
 *
 * @param reason 这次请求的原因
 * @param played 已经为哪些原因播过（调用方持有，本函数只读）
 */
internal fun shouldAnimateFor(
    reason: BackdropReason,
    played: Set<BackdropReason>
): Boolean = when (reason) {
    BackdropReason.STARTUP -> BackdropReason.STARTUP !in played
    BackdropReason.TOGGLED_ON -> BackdropReason.TOGGLED_ON !in played
    BackdropReason.STRENGTH -> BackdropReason.STRENGTH !in played
    // 切夜间 / 主题：不播晕染 —— 那一屏正在被用户操作，一闪就太脏了（1.2.50）。
    BackdropReason.THEME -> false
    BackdropReason.OTHER -> false
}

/** 晕染动画时长（ms）。比页面转场（280）略长：背景应该"慢半拍"地跟上。 */
internal const val BACKDROP_FADE_MS = 420

/**
 * 普通换帧的交叉淡入时长（ms）。
 *
 * 短于晕染：它只负责"别硬切"，太长会显得背景反应迟钝 ——
 * 用户是刚改完曲线/显隐就拉抽屉，背景应该立刻跟上。
 */
internal const val BACKDROP_CROSSFADE_MS = 220

/** 动画起点半径比例（0 = 从正中心一个点开始）。 */
private const val BACKDROP_HIDDEN = 0f

/** 屏幕短边推出毛玻璃半径（**dp**）—— 和 [blurRadiusDp] 同一口径。 */
private fun glassBlurRadiusDp(context: Context, strength: Float = 1f): Float {
    val dm = context.resources.displayMetrics
    val shortSideDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
    return blurRadiusDpFor(shortSideDp.toInt(), shortSideDp.toInt(), strength)
}

private fun densityPxPerDp(context: Context): Float = context.resources.displayMetrics.density

/**
 * BlurView 内部把画面缩到 1/[BLUR_DOWNSCALE] 再糊。
 *
 * ## 3.42 修正：6 → 4
 *
 * 之前这里写 6，注释说"钉在 2.0.6 对字节码核过"。但项目实际依赖的是
 * **BlurView 3.2.0**（`gradle` 里 `com.eightbitlab:blurview:3.2.0`），
 * 反编译 `BlurView.setupWith(BlurTarget)` 看到的是：
 *
 * ```
 * ldc  #128  // float 4.0f
 * invokevirtual setupWith(Leightbitlab/com/blurview/BlurTarget;FZ)
 * ```
 *
 * 也就是**默认 scaleFactor = 4.0**。按 6 算会让半径整体偏小 1/3，
 * 再叠加下面的 `strength²` 曲线，"中间档位"就全都糊得不够、区别也出不来。
 */
internal const val BLUR_DOWNSCALE = 4f

/** `ScriptIntrinsicBlur.setRadius` 的硬上限（0~25），库自己会 `Math.min(25, r)`。 */
internal const val BLUR_MAX_RADIUS = 25f

/**
 * 中心渐显（`Brush.radialGradient`）不受影响；这里只管卷积半径。
 *
 * ## 为什么要有"最小可分辨半径"
 *
 * 半径 1 和半径 2 在小图上只是"稍微柔一点"，眼睛几乎分不出。
 * 但**必须**允许 0（用户要求 0% = 真不糊），所以不能把下限当成全局下限 ——
 * 只在 `strength > 0` 时兜住一个**能看出糊**的最小值。
 */
internal const val BLUR_MIN_VISIBLE_RADIUS = 1.2f

/**
 * 把"屏幕上的半径（dp）"折成要传给模糊算法的值。
 *
 * ## 3.44：这里的 `÷ BLUR_DOWNSCALE` 是对的，前提是**图真的缩小了**
 *
 * 3.43 之所以"所有程度一样糊"，不是这个换算错，而是**上游根本没缩图**：
 * `RenderScriptBlur.blur()` 拿到的是原分辨率位图，同一个半径落在上面
 * 覆盖的像素少 4 倍，全都在"轻微柔化"的区间里。
 *
 * 现在 [BackdropProducer.blurViaBlurView] 会先 `scaleBitmap(÷4)` 再卷积，
 * 这个函数算出来的值就真正对上了小图坐标系：
 *
 * 1. dp → px → 除以 [BLUR_DOWNSCALE]（糊的是缩 4 倍的图）；
 * 2. 卡在 [BLUR_MAX_RADIUS]（超过 25 库也会削，事先算清楚免得"改了没反应"）；
 * 3. 非零强度兜一个 [BLUR_MIN_VISIBLE_RADIUS]（否则 1% 和 20% 是一样的）。
 */
internal fun blurViewRadius(radiusDp: Float, density: Float): Float {
    if (radiusDp <= 0f) return 0f
    val raw = radiusDp * density / BLUR_DOWNSCALE
    return raw.coerceIn(BLUR_MIN_VISIBLE_RADIUS, BLUR_MAX_RADIUS)
}

/**
 * 毛玻璃半径（dp）—— **满档**（100%）的半径。
 *
 * 3.20 那档是 `短边 × 0.075`，在 411dp 的表上只有 30.8dp，
 * 折进算法坐标系（÷4）后 ≈ 15，还在"能分辨"的范围内但不算"很糊"。
 *
 * 3.42 把满档提到 `短边 × 0.16`（≈66dp → 算法坐标 33，被 25 卡住）：
 * **100% 就是库里能给的最糊**。这样滑条右半段也还有意义（逼近上限），
 * 而 70% 落在 `0.49` 的曲线上 → 半径约 16dp → 算法坐标 8，明显比满档浅。
 */
internal fun blurRadiusDp(screenWidthDp: Int, screenHeightDp: Int): Float =
    maxOf(
        minOf(screenWidthDp, screenHeightDp).toFloat() * GLASS_BLUR_FRACTION,
        GLASS_BLUR_MIN_DP
    )

/**
 * 带**程度**的半径（3.40 / 3.42 重做）：`强度 0%~100%` → `0 ~ 满档`。
 *
 * ## 3.41 之前为什么"70% 和 100% 看不出区别"
 *
 * 旧写法是 `blurRadiusDp × strength`，再 `÷ 6` 折进算法坐标系。手表短边 411dp：
 *
 * ```
 * 30.8dp × 2.0density / 6 ≈ 10.3   ← 100%
 * 10.3 × 0.70        ≈ 7.2         ← 70%
 * ```
 *
 * `ScriptIntrinsicBlur` 的半径 7 和 10，在**已经缩小的**图上都是"糊成一片色块"。
 * 眼睛分不出 7 和 10 —— 这就是"拨了没反应"。
 *
 * 真正能分辨的区间在**小半径**一侧（1~5）：那里每 +1 都能明显更糊。
 * 所以强度到半径的映射必须**前段密、后段疏**，而不是线性。
 *
 * ## 现在的映射
 *
 * ```
 * 半径 = 满档 × strength^EXP      (EXP = 2.0)
 * ```
 *
 * 平方让 50% 只到满档的 1/4：手指在滑条左半段走的每一格都在"肉眼可分辨"的区间里。
 * 右半段继续加深直到饱和，但那时已经是很糊，再深也看不出来了 —— 所以把
 * "看得出来"的那一段全部让给左半段。
 *
 * ## 为什么不沿用 `GLASS_BLUR_MIN_DP` 下限
 *
 * 20dp 的下限是为了"再小也比没糊好看"。但滑条要求 **0% 就是真不糊**，
 * 而下限会让 1% 和 30% 糊得一模一样。所以下限只在 `strength > 0` 时**兜住**，
 * 且兜的是"算法坐标系里的最小可分辨值"（见 [blurViewRadius]），不是 20dp。
 */
internal fun blurRadiusDpFor(
    screenWidthDp: Int,
    screenHeightDp: Int,
    strength: Float
): Float {
    val s = strength.coerceIn(0f, 1f)
    if (s <= 0f) return 0f
    val full = blurRadiusDp(screenWidthDp, screenHeightDp)
    // 二次曲线：把"能分辨"的区间整体压到滑条左半段（见函数 KDoc）。
    return full * (s * s)
}

/**
 * "关于"页长按版本号翻出来的诊断行（3.37 / 3.39 / 3.44）。
 *
 * 报五件事：图在不在、多大、用的什么半径、**缩到了多大**、上次为什么失败。
 *
 * ## 3.44 加了"小图尺寸"这一项
 *
 * 用户连着几轮报"模糊程度没实装"。查下来根因是 `RenderScriptBlur` 在**原图**上
 * 卷积（半径被 25 卡住，落在 466px 画面上几乎看不出糊）。修法是先缩 4 倍。
 *
 * 那么"缩没缩"就是最关键的一条信息 —— 有了它，下次若再出现"所有档位一样糊"，
 * 一眼就能看出是缩图那步没跑（尺寸等于原图），还是缩了但半径不对。
 */
internal fun blurBackdropInfo(): String {
    val bmp = BackdropImage.image
    val size = if (bmp == null) "无图" else "${bmp.width}×${bmp.height}"
    val radius = if (BackdropImage.lastRadius <= 0f) "-"
    else String.format("%.1f", BackdropImage.lastRadius)
    val small = BackdropImage.lastScaledSize?.let { " · 小图 $it" } ?: ""
    // 3.47：按下提示条的成功率。若"按下次数"不涨 = 手势没触发；
    // 涨但"成功"不涨 = 取图/卷积那一步失败，看后面的失败原因。
    val err = BackdropImage.lastError?.let { " · 失败：$it" } ?: ""
    return "背景 $size · 半径 $radius$small$err"
}
