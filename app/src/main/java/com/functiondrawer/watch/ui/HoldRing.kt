// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 线圈的**固定几何**（用户要求："单独写一个形状，不要让线圈自动适合形状，
// 这样太容易出 bug 了"）。
//
// 下面这几个数是**写死的**：线圈永远是"矩形 + 左右两个半圆"（胶囊 / 跑道形）+
// 固定线宽，不读按钮的形状、不按按钮的圆角推导、不做任何 clamp。
// 唯一用到外部信息的是**按钮的边长**（[WatchMetrics.iconButton]）—— 用它把线圈摆到
// 按钮正中间、并让外沿落在按钮里侧，所以线圈总与按钮同心、看起来是"绕着按键"。
//
// 为什么不再自适应：上一版把"圆角 = 按钮圆角 − 半个线宽"这类推导写进代码，
// 一旦按钮形状/尺寸变了、或者画布被父布局压成非正方，线圈立刻跟着变形，
// 真机上表现为"椭圆 / 正方形 / 位置歪掉"三种 bug。写死之后这三类都不可能出现。
//
// ---- 这一轮为什么要重新配这三个数（用户报的 bug）----
//
// 用户原话："手机上的设置键、+ 键、删除键和文件键……都比手表上要方，长按动画的线圈
// 不能围绕按键了，请你让按键强制保持在一个形状。" 两件事一起修：
//   1. 按键的形状：`Common.kt` 的 `WatchIconButton` 从"固定 14dp 圆角 + 被父行高
//      偶然夹出来的高度"改成**显式** `0.18 × ICON_BTN_ASPECT` 的胶囊（见
//      `WatchMetrics.ICON_BTN_ASPECT`）；
//   2. 线圈：外沿**整体收进按键里侧**。原来写 0.96 / 0.70，配上 0.075 的线宽后
//      外沿是宽 1.035、高 0.775 —— 就算按键正好是 0.18 × 0.125（宽 1.0、高 0.6944 个边长），
//      **四边也已经全都露出去了**（宽每侧 0.0175、高每侧 0.04 个边长，圆表上约
//      0.73dp / 1.7dp），这就是"线圈没能绕住按键"的一条真因。新的 0.92 / 0.615
//      把外沿压到 0.995 / 0.69，两个方向都在按键以内（算式见下）。
//
// 两个量先写清楚（下面所有数都是"**按钮边长**"的倍数，边长 = [WatchMetrics.iconButton]）：
//
//   * **按键的最终尺寸与比例**：宽 `0.18` 个可用短边、高 `0.125` 个可用短边
//     （= 宽 × [ICON_BTN_ASPECT]），也就是**一颗 1.44 : 1 的胶囊**，圆角 = 半高
//     （`PillShape`）。手机上"短边"大得多，但**比例**是同一个数 ——
//     这正是用户要的"强制保持在一个形状"。
//   * **线圈相对它的大小**：宽 `0.92`、高 `0.615`、线宽 `0.075` 个边长。
//     线是**压在路径上**画的（`Stroke` 居中，两侧各出一半），所以外沿 = 尺寸 + 线宽：
//       外沿宽 = 0.92  + 0.075 = 0.995  ≤ 1.0     （按键宽 = 1.0 个边长）
//       外沿高 = 0.615 + 0.075 = 0.69   ≤ 0.6944  （按键高 = 0.125/0.18 个边长）
//     → **任何屏形、任何 density 都包得住**，线圈一定在按键里侧。
//     （`StrokeCap.Round` 的圆头也不会顶出这个包围盒：路径的起点/终点在上边中点，
//     圆头正好落在描边带之内。）
//
// 这两个"≤"就是选数时的全部依据：宽度只能由 `0.92 + 0.075` 压到 1.0 以内，
// 高度只能由 `0.615 + 0.075` 压到 `ICON_BTN_ASPECT` 以内。余量确实很薄
// （宽 0.005、高 0.0044 个边长，圆表上约 0.2dp / 0.18dp），但它是**算出来的**、
// 不是估的：只要上面两个比例和 `ICON_BTN_ASPECT` 不变，换屏换 density 都成立。
//
// 为什么高宽取 0.92 / 0.615 而不是别的：必须让**中间那两段直线**够长，
// 否则左右两个半圆会连成一整个圆 —— 那就又回到用户不想要的"圆形"了。
// 现在直边段 = 0.92 − 0.615 = 0.305 个边长，占线圈宽的 **33%**，
// 一眼能看出是"矩形 + 两个半圆"而不是圆。
// ---------------------------------------------------------------------------

/**
 * 线圈**宽**占按钮边长。
 *
 * 形状是用户明确定下来的："形状不是方形，是**矩形和两个半圆拼起来的**"
 * —— 也就是胶囊 / 跑道形。所以宽 > 高，两端各一个半圆，中间是两段直线。
 *
 * 0.92（原来是 0.96）：**上限**由这一条定 —— `0.92 + RING_STROKE_SCALE ≤ 1.0`
 * （按键宽 = 1.0 个边长），否则线上的外沿会从按键左右两侧漏出去。
 */
private const val RING_W_SCALE = 0.92f

/**
 * 线圈**高**占按钮边长。
 *
 * 必须**明显小于**宽（[RING_W_SCALE]），否则两个半圆会连成一整个圆 ——
 * 那就又回到用户不想要的"圆形"了。0.615 / 0.92 让中间那两段直线占线圈宽的 **33%**
 * （0.305 / 0.92），一眼能看出是"矩形 + 两个半圆"而不是圆。
 *
 * 0.615（原来是 0.70）：**上限**由这一条定 ——
 * `0.615 + RING_STROKE_SCALE = 0.69 ≤ ICON_BTN_ASPECT ≈ 0.6944`
 * （按键高 = 0.125/0.18 个边长），否则外沿会从按键上下两边漏出去。
 * 原来那个 0.70 配上 0.075 的线宽是 0.775，比按键的高（0.6944 个边长）还大 0.08 ——
 * 上下各露出去约 0.04 个边长（圆表上 ≈1.7dp），"线圈没能绕住按键"就是这么来的。
 */
private const val RING_H_SCALE = 0.615f

/** 线宽占按钮边长（写死，不由画布短边算 —— 画布一变线宽就会漂）。 */
private const val RING_STROKE_SCALE = 0.075f

// ---------------------------------------------------------------------------
// 转满那一下的"锁定"反馈（用户要求"所有功能都要配上动画"）
//
// 三个数都跟现有代码同一个量级（90~180ms 的短促 tween + FastOutSlowInEasing）：
//   * 整个按钮（含线圈）弹一下：0.10 的缩放，只动图层、不动布局，
//     所以按钮的尺寸和命中区在动画期间**一个像素都不变**；
//   * 底下那圈 22% 的淡轮廓在弹的瞬间提到全亮 = "锁上了"。
// ---------------------------------------------------------------------------

/** 转满时整个按钮弹出去多少（1 + 0.10）。 */
private const val HOLD_POP = 0.10f
/** 弹出去 / 收回来各自的时长：合计 90 + 170 = 260ms。 */
private const val HOLD_POP_UP_MS = 90
private const val HOLD_POP_DOWN_MS = 170

/**
 * 「这一次手势已经被长按吃掉了」的闩。
 *
 * 存在的理由是一个真机 bug：长按 `+`（转满线圈 = 保存文件）松手时，
 * 按钮**还会**再触发一次短按（= 新建函数并进编辑器），于是"保存文件"和
 * "添加函数页面"同时发生。用户原话："长按+键保存文件的同时会唤出添加函数的页面。"
 *
 * 为什么光靠 [HoldToConfirm] 内部的 `consume()` 还不够、还要这个闩：
 * `consume()` 依赖 Compose 内部"被消费过的 up 不算抬手"这条判定
 * （细节写在 [HoldToConfirm] 的注释里），而这条判定**不在我们的代码里** ——
 * 将来换一个 Compose 版本它就可能变。闩是**我们自己**的判据：
 * 线圈转满的同一瞬间把它立起来，调用方在 `onClick` 里问一句就返回，
 * 无论上层库怎么改都成立。两道锁互不依赖，任何一道生效都不会误开编辑器。
 *
 * 用法（见 `FunctionList.kt` 的 `+` 键）：
 * ```
 * val latch = rememberHoldLatch()
 * HoldToConfirm(metrics = …, latch = latch, onConfirm = onSave) {
 *     WatchIconButton(onClick = { if (!latch.eaten) onAdd() }, …)
 * }
 * ```
 * [eaten] 是**在点击回调里读**的（不是组合期读），所以它用快照状态也不会
 * 引起任何重组；[HoldToConfirm] 在**每次按下**的时候把它归位。
 */
@Stable
class HoldLatch internal constructor() {
    private val state = mutableStateOf(false)

    /** true = 从按下列松手这一整串手势已经被长按吃掉，短按回调不该再动作。 */
    val eaten: Boolean get() = state.value

    /** 立闩（线圈转满的那一刻）。只给 [HoldToConfirm] 用。 */
    internal fun eat() {
        state.value = true
    }

    /** 归位（新手势按下时）。只给 [HoldToConfirm] 用。 */
    internal fun reset() {
        state.value = false
    }
}

/** 记住一个 [HoldLatch]（写在调用方那一侧，`onClick` 里要读它）。 */
@Composable
fun rememberHoldLatch(): HoldLatch = remember { HoldLatch() }

/**
 * 长按确认：按住时画一圈**固定形状**的线圈（矩形 + 左右两个半圆 = 胶囊），转满一圈才触发。
 *
 * 用户要求："从按钮中间的上面显示一个线圈逆时针绕着键转，转满一圈才进入"、
 * "贴着按钮的形状"、"单独写一个形状，不要让线圈自动适合形状"。
 *
 * 现在这几条是这样合到一起的：
 *   * 形状是**写死的胶囊**（见上面三个常量），不随按钮变形；
 *   * 尺寸按**按钮边长**（[WatchMetrics.iconButton]）缩放，外沿按算式压在按键里侧，
 *     所以它总待在按钮内部、与按钮同心 —— 这就是"绕着按键"；
 *   * 画布就是按钮自己的大小（`matchParentSize`），**不给按钮加任何尺寸约束**，
 *     所以按钮的尺寸/位置/形状与"没有动画"时逐像素一致
 *     （上一版给线圈加了更大的容器，按钮被祖先裁成"正方形"，已修）。
 *
 * ⚠️ **契约（改按钮尺寸前必须先读这一段）**：线圈的基准是
 * `sidePx = metrics.iconButton.toPx()`，同心则来自"画布就是按钮"（`matchParentSize`）。
 * 所以"**按键的宽度必须恰好等于 [WatchMetrics.iconButton]**"是线圈与按键同心、同比例的
 * 前提 —— 只要按键更宽/更窄，线圈就不再贴着外形（要么露出去、要么缩在中间）。
 * 这也正是这一轮把 `WatchIconButton` 的尺寸从"被父行高偶然夹出来"改成**显式**
 * `width(iconButton).height(iconButton * ICON_BTN_ASPECT)` 的理由。
 * 已知偏差：`FunctionEditor` / `PropertyEditor` 在自己的 `modifier` 里把按钮设成
 * `0.16 短边 × 0.85 行高`（比标准尺寸更宽更扁），但那两处**没有**包 [HoldToConfirm]，
 * 所以现在不会出现"线圈不贴按键"；将来若给它们加长按，得先把尺寸收回标准值。
 *
 * 动画：路径从**上边中点**出发**往左**绕（左 → 下 → 右 → 上），`PathMeasure`
 * 沿路径方向推进，所以看起来是从正上方开始**逆时针**转。底下还有一圈 22%
 * 透明度的淡轮廓，看得出还剩多少；中途松手快速缩回（不是硬跳）。
 * 转满的一瞬间整个按钮弹一下（[HOLD_POP]，260ms）并且淡轮廓提到全亮 = "锁上了"。
 *
 * ## 转满之后**绝不会**再被当成"点了一下"（这是一次真机 bug 的修复）
 *
 * 用户原话："长按 + 键保存文件的同时会唤出添加函数的页面，请你去掉。"
 * 起因在**组件树的层级**上：线圈的手势挂在本组件（按钮的**祖先**）上，
 * 而按钮自己的 `clickable` 在**子节点**上。Compose 的 Main 阶段是
 * "**子节点先收事件、祖先后收**"，所以抬手那一下按钮已经把点击判定跑完了，
 * 祖先再做什么都晚了 —— 光在祖先里"看到 up 就取消"是没用的。
 * 所以两道锁一起上：
 *
 * 1. **抬手事件在 Initial 阶段被我们消费掉**。[PointerEventPass.Initial] 是
 *    **祖先先收**的那个阶段，正好和上面的顺序相反。被消费过的 up 在
 *    `clickable` 眼里不算"抬手"：foundation 1.7.0 的
 *    `waitForUpOrCancellation()` 用的是 `changedToUp()`
 *    （= `!pressed && previousPressed && !isConsumed`，**带消费判定**），
 *    消费过 → 这条为 false，紧接着命中"changes 里有 `isConsumed` → 返回 null"，
 *    于是 `detectTapAndPress` 里的 `onTap` 根本不会执行
 *    （字节码 `TapGestureDetectorKt$detectTapAndPress$2$1`：拿 up 的结果
 *    `ifnull` 直接跳过 `Function1.invoke`）。**这一条是根治那一下点击的地方。**
 * 2. 再给调用方一个显式的闩 [HoldLatch]（[eaten]）：线圈转满的同一瞬间立起来，
 *    调用方在自己的 `onClick` 里问一句就直接返回。万一将来 Compose 改了第 1 条
 *    依赖的那套判定细节，第 2 条仍然兜得住。两条路都不依赖对方的实现。
 *
 * ⚠️ **不能**改用 `combinedClickable(onLongClick = …)` 来解决：它的长按超时是
 * 系统的 `ViewConfiguration.getLongPressTimeout()`（≈500ms），和这里"线圈转满"
 * 的 [holdMillis]（900ms）**不是同一个东西** —— 那样会变成两个长按阈值打架
 * （500ms 时按钮已经认定为"长按"，而线圈还剩一半），而且 `clickable` 的长按
 * 也不会去管"转满之后松手不能再点"这件事。
 *
 * @param holdMillis 转满一圈要按住多久。900ms：比系统长按阈值（500ms）长，
 *   明确表达"这是个需要按满一圈的动作"。
 * @param latch 可选（默认自己 remember 一个）：调用方想在 `onClick` 里问
 *   "这一次手势是不是已经被长按吃掉了"，就把自己 `rememberHoldLatch()` 的那个传进来。
 * @param onConfirm 转满一圈时触发。**这时候手势还没结束**（用户可能还按着）。
 */
@Composable
fun HoldToConfirm(
    metrics: WatchMetrics,
    ringColor: Color,
    modifier: Modifier = Modifier,
    holdMillis: Int = 900,
    latch: HoldLatch = rememberHoldLatch(),
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    /** 转满那一下的"锁定"脉冲：0 → 1 → 0，同时把整个按钮弹一下。 */
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 三个几何量：按钮边长 × 写死的比例。画的时候直接用，不再做任何推导。
    //
    // ⚠️ **契约**：这里把 [WatchMetrics.iconButton] 当作"按钮边长"，而"同心"靠的是
    // 下面那个 `Box` 把画布撑成**按钮自己的大小**（`Canvas(matchParentSize)`）。
    // 两条合起来要求：**按键宽度恰好等于 `metrics.iconButton`**。
    // 只要某个调用点把按钮改宽/改窄，线圈就不再贴着外形 ——
    // 这正是 `WatchIconButton` 把尺寸显式写成 `width(iconButton).height(iconButton * ICON_BTN_ASPECT)`
    // 的原因（以前是 `Modifier.size(iconButton)` 被父行高夹出来的，容器一变就不同比例）。
    //
    // 外沿（= 尺寸 + 线宽，因为 Stroke 居中）必须落在按键里侧，否则线圈会露到按键外面：
    //   宽 0.92  + 0.075 = 0.995  ≤ 1.0    （按键宽 = 1.0 个边长）
    //   高 0.615 + 0.075 = 0.69   ≤ 0.6944 （按键高 = ICON_BTN_ASPECT = 0.125/0.18）
    val sidePx = with(density) { metrics.iconButton.toPx() }
    val ringWidthPx = sidePx * RING_W_SCALE
    val ringHeightPx = sidePx * RING_H_SCALE
    val strokePx = sidePx * RING_STROKE_SCALE

    Box(
        // **不加任何尺寸修饰符**：容器跟着按钮走，按钮因此保持原样。
        // graphicsLayer 只做"转满时弹一下"的缩放 —— 它同样不参与布局，
        // 所以按钮的位置、尺寸、命中区和没有动画时**逐像素一致**。
        modifier = modifier
            .graphicsLayer {
                val s = 1f + HOLD_POP * flash.value
                scaleX = s
                scaleY = s
            }
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 新手势开始：闩归位。上一次长按的"吃掉"绝不能影响这一次。
                    latch.reset()
                    val job = scope.launch {
                        flash.snapTo(0f)
                        progress.snapTo(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = holdMillis, easing = LinearEasing)
                        )
                        // ---- 转满一圈：先立闩，再触发动作 ----
                        // 顺序很重要：闩必须在 onConfirm() **之前**立起来，
                        // 万一 onConfirm 内部（或被它唤出的对话框）让这一串手势
                        // 提前结束，闩也已经生效了。
                        latch.eat()
                        onConfirm()
                        // 收尾两件事同时跑：线圈缩回去、按钮弹一下。
                        // 用 coroutineScope 包住，两个孩子都归 job 管 ——
                        // 中途松手时 job.cancel() 能把它们一起取消（不留半截动画）。
                        coroutineScope {
                            launch {
                                progress.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = 160,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                            launch {
                                flash.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = HOLD_POP_UP_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                flash.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = HOLD_POP_DOWN_MS,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        }
                    }
                    // 等手指抬起（或手势被取消）。
                    // ⚠️ **在 Initial 阶段等**（祖先先收）：这样"转满之后的那一下抬手"
                    // 才有机会在子节点之前被我们消费掉 —— 这是上面第 1 道锁。
                    // 平时（没转满）我们一个事件都不消费，按钮的按压反馈、
                    // 正常点击全都照旧。
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && change.changedToUpIgnoreConsumed()) {
                            // 长按成立过 → 把这一下抬手吃掉，按钮的 onClick 不会再触发
                            if (latch.eaten) change.consume()
                            break
                        }
                        // 这一条是**原来就有的**结束判定（所有指针都松了）：
                        // 多指一起按、或者 up 被别的窗口（比如刚弹出的保存对话框）抢走时，
                        // 走的是这一条 —— 保证不会在这里死等。
                        if (event.changes.none { it.pressed }) break
                    }
                    if (job.isActive) {
                        // 没按满就松手：取消这次按住，线圈缩回去。
                        // 注意**不能**在这里直接调 `progress.animateTo` ——
                        // `awaitEachGesture` 的块是"受限挂起"作用域，别的挂起函数
                        // 必须扔回外面的 scope（否则编译器直接报错）。
                        job.cancel()
                        scope.launch {
                            progress.animateTo(0f, tween(durationMillis = 180))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()

        Canvas(Modifier.matchParentSize()) {
            val p = progress.value
            if (p <= 0.001f) return@Canvas

            // ---- 形状：**矩形 + 左右两个半圆**（胶囊 / 跑道形）----
            //
            // 用户把形状定死了："形状不是方形，是矩形和两个半圆拼起来的"。
            // 所以这里就是"两段直线 + 两个半径 = 高度一半的半圆"：
            //   上半直线：上边中点 → 左半圆起点
            //   左半圆：圆心 (left+r, cy)，从 -90°(顶) 逆时针扫 -180° 到 90°(底)
            //   下半直线：→ 右半圆起点
            //   右半圆：圆心 (right-r, cy)，从 90°(底) 逆时针扫 -180° 到 -90°(顶)
            //   闭合回上边中点
            //
            // ⚠️ Compose / Android 角度约定：0° 指向 +x、正角度是**顺时针**，
            // 所以"逆时针"要用**负** sweep（上一版四角用 +90 导致圆弧反鼓、像风车）。
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfW = ringWidthPx / 2f
            val halfH = ringHeightPx / 2f
            val left = cx - halfW
            val right = cx + halfW
            val top = cy - halfH
            val bottom = cy + halfH
            val r = halfH
            // 两个半圆各自的整圆外接矩形（直径 = 2r = 高度）
            val leftCircle = Rect(left, top, left + r * 2f, bottom)
            val rightCircle = Rect(right - r * 2f, top, right, bottom)

            val outline = Path().apply {
                moveTo(cx, top)                                  // ① 上边中点（起点）
                lineTo(left + r, top)                            // ② 上半直线，往左
                arcTo(leftCircle, -90f, -180f, false)            // ③ 左半圆（顶 → 底）
                lineTo(right - r, bottom)                        // ④ 下半直线，往右
                arcTo(rightCircle, 90f, -180f, false)            // ⑤ 右半圆（底 → 顶）
                close()                                          // ⑥ 回到起点
            }

            // ---- 底环：整条轮廓，很淡 ----
            // 转满那一下这圈淡轮廓会提到全亮（flash 0→1→0），读起来就是"锁上了"，
            // 比只把进度画满更有"确认成功"的分量。
            drawPath(
                path = outline,
                color = ringColor.copy(alpha = 0.22f + 0.78f * flash.value),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // ---- 进度：沿轮廓从起点推进 ----
            val measure = PathMeasure()
            measure.setPath(outline, false)
            val total = measure.length
            if (total <= 0f) return@Canvas
            val segment = Path()
            // getSegment 要求 end > start：夹一下防浮点误差
            val end = (total * p).coerceIn(0f, total - 0.01f)
            measure.getSegment(0f, end, segment, true)
            drawPath(
                path = segment,
                color = ringColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}
