// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// 版面比例 —— [SliderRow] 的排版、它的长按判定（排除播放键那一格）
// 和"播放键那一格算不算删除起手区"**共用这一份数字**。
//
// ⚠️ 这几个数**已经搬进 `PlotMetrics`**（`SLIDER_ROW_PAD` / `SLIDER_PARAM_COL_W` /
// `SLIDER_ROW_GAP` / `SLIDER_PLAY_KEY_H` / `SLIDER_PLAY_KEY_SCALE`），这里只是别名：
//   * 它们同时被"版面""长按判定""播放键边界"三处用到，而三处各抄一份数字迟早会漂；
//   * `PlotMetrics.kt` 是**纯 Kotlin**，常量住在那里才能被 `tools\run-tool-tests.ps1`
//     那套离线单测钉住（"三块屏上删除起手区到底多宽"因此不必装到设备上试）。
// 名字保持 `ROW_PAD` 这些短名，是为了让下面的版面代码一个字都不用改。
// ---------------------------------------------------------------------------

/** 整行左右内边距（× 屏宽 dp）。 */
private const val ROW_PAD = SLIDER_ROW_PAD

/** 参数字母 + 当前值那一栏的宽度（× 屏宽 dp）。 */
private const val PARAM_COL_W = SLIDER_PARAM_COL_W

/** 行内各元素之间的缝（× 屏宽 dp）。 */
private const val ROW_GAP = SLIDER_ROW_GAP

/** 播放键高度（× 行高）。 */
private const val PLAY_KEY_H = SLIDER_PLAY_KEY_H

/** 播放键宽度相对高度的倍数：略宽于正方形，画出来是个小胶囊。 */
private const val PLAY_KEY_SCALE = SLIDER_PLAY_KEY_SCALE

/**
 * 函数列表里的一条**滑动条**行（用户需求 #3）。
 *
 * 版面按用户原话排：
 * ```
 * [ a ]  [▶]  [ −5 ]  ──────●───────  [ 5 ]
 * [0.0]
 * ```
 *   * 最左是一个两行小徽标：第一行是参数字母（小写 `a`），第二行是**当前值** ——
 *     用户只要求"左边显示下界、右边显示上界"，但滑动条不显示当前值就没法用
 *     （0.1 的步长靠肉眼看滑块位置根本读不出来）。
 *   * 紧挨着是**播放键**（用户要求"在滑动条左边放一个播放键"）。
 *   * 然后是下界、滑轨、上界。
 *
 * 长按整行 → 属性编辑界面（#4，含上下界/步长）。短按不做任何事：
 * 这条行天生就是用来拖的，误触进去反而烦。
 *
 * **左滑一行**（滑轨和播放键**以外**的地方）→ **删掉这一行**（用户要求 #3 的补充）：
 * 和函数行 / 几何对象行**同一套** —— 跟手位移 + 砖红渐染 + 滑够 1/3 行宽先播"甩出"
 * 再真删 + 底部浮出 2 秒可撤销的撤销条。分区规则见下面"左滑删除"那一节。
 *
 * ### 长按为什么不能直接用 `detectTapGestures(onLongPress = …)`（#3 的真因）
 *
 * 原来这里写的就是
 * ```kotlin
 * .pointerInput(slider.id) { detectTapGestures(onLongPress = { onLongPress() }) }
 * ```
 * 它**在滑轨和播放键上永远不触发**。原因不是层级被抢，而是
 * `detectTapGestures` 内部的 `awaitFirstDown()` 默认 `requireUnconsumed = true`：
 * 按下事件在 Compose 里是**从最里层往外派发**的（Main pass 子节点优先），
 * 而这一行里的 [SliderTrack] 和 [PlayKey] 自己都是 `detectTapGestures`，
 * 都会 `consume()` 掉那次 down —— 于是等事件轮到这一行时，down 已经是"被消费过的"，
 * 这个长按判定**根本不会开始计时**。行里能长按的只剩参数字母徽标和上下界两个文字，
 * 而它们又窄又靠边，真机上感受就是"长按滑动条没反应"，也就是用户报的
 * "滑动条的长按编辑属性菜单你没加"。
 *
 * ⚠️ 本轮只改了**这一行的长按判定**；[PlayKey] 的点击实现**一行没动**
 * （真机上点 ▶/■ 是好的、播放也验证通过，动它只会引入回归风险）。
 *
 * 所以这里改成手写判定：`requireUnconsumed = false`（不介意 down 被别人拿走过），
 * 然后盯住"长按超时"这一个条件：
 *   * 超时之前手指抬起来 → 是短按（按设计什么都不做）；
 *   * 超时之前手势被滑轨的拖动接管（事件被消费）→ `waitForUpOrCancellation`
 *     立刻返回 null，同样不是长按；
 *   * 一直按到 `longPressTimeoutMillis` 还没结束 → 长按成立。
 * 这三种情况正好互斥，拖滑轨不会被误判成长按。
 *
 * ### 播放键那一格**不算长按区**（`requireUnconsumed = false` 带来的唯一副作用）
 *
 * 因为不再要求"down 没被消费"，这一行的长按判定在**任何位置**都成立 ——
 * 包括按在 ▶ 上。而 [PlayKey] 自己的 `detectTapGestures` 没有 `onLongPress`，
 * 抬手时照样算一次点击。两件事叠在一起就是：按住 ▶ 半秒 → 先弹出属性界面，
 * 松手又把播放切了一下。而"按住播放键"是很自然的操作（手表上按键就该按住），
 * 所以这里把 ▶ 占的那一格 x 区间**排除**在长按判定之外：
 *   * 在 ▶ 上按住 → 什么也不发生，松手 = 播放/停止（和改动前完全一样）；
 *   * 在行的其它位置按住 → 进属性界面（#3 要的）。
 *
 * 区间是按 [Row] 的版面算出来的（内边距 + 参数字母栏宽 + 键间距 + 播放键宽），
 * 那几个比例提成了文件级的 `ROW_*` / `PLAY_KEY_*` 常量（真身在 `PlotMetrics`），
 * **版面和手势用的是同一份数字**，改版式时不会漂移。
 * 算式本身也抽成了纯函数 `PlotMetrics.sliderPlayKeyLeftDp` / `sliderPlayKeyRightDp`，
 * 三块屏上的实际 dp 数由离线单测钉着。
 *
 * ### 左滑删除（本轮新增）：**谁在手指底下，谁说了算**
 *
 * 用户要求"滑动条行也要能左滑删除"，可是这条行的横向拖动**本来就有主人**：
 * 滑轨上拖动 = 改参数值（`SliderTrack` 里那个 `detectHorizontalDragGestures`）。
 * 划分不是按 x 坐标切一刀，而是**看手指按在哪一块上**（详见 `PlotMetrics` 末尾那一节）：
 *
 * | 手指落在哪 | 横着拖动 = 什么 |
 * |---|---|
 * | 滑轨 | **改值**（删除手势根本不开始，一个字节都不消费） |
 * | 播放键 ▶/■ | 什么都不做（松手 = 播放/停止，和改动前逐字一致） |
 * | 参数字母/当前值徽标、下界/上界文字、内边距与缝隙 | **左滑删除**（滑够 1/3 行宽） |
 *
 * 做法：`swipe.gesture(..., skipIfDownConsumed = true)`。滑轨和播放键自己都是
 * `detectTapGestures`、都会**消费掉那次 down**（这正是上面"长按为什么不能直接用
 * `detectTapGestures`"那一节里那个真机 bug 的成因），于是删除手势在
 * `awaitFirstDown` 之后第一句就返回：不消费、不跟手、松手也不删 ——
 * **在滑轨上拖动永远不可能误删**；而徽标/上下界文字那里没人消费 down，
 * 删除手势照常起手，**左滑能稳定删掉**。
 *
 * 为什么不用"传一个 x 区间当起手区"：滑轨是 `weight(1f)`，它的左沿取决于
 * **下界文字的实测宽度** —— 同一个 233dp 圆表上 `-5~5` 和 `-0.5~0.5` 量出来的滑轨
 * 不是同一条，写死比例就会在窄文字时把滑轨划进删除区（= 在滑轨上误删）。
 * 交给命中测试分区，边界永远等于真实绘制边界。
 *
 * 短按 / 长按**一个字都没变**：没过触摸容差时删除手势两个方向都不认领、不消费任何事件
 * （`oneWayDragClaims` 第一条语义），所以"短按什么都不做""长按进滑动条属性页"
 * "按住 ▶ 半秒只切播放"三条都照旧。
 */
@Composable
fun SliderRow(
    slider: Slider,
    metrics: WatchMetrics,
    /**
     * 这一行**实际**该有多宽（圆屏 = 弧宽、非圆屏 = 铺满可用宽度）。
     *
     * 唯一来源是 `ScreenGeometry.rowWidth`（和函数行、几何对象行同一个口径）——
     * 删除阈值是"行宽的 1/3"，从它推，所以**画出来的宽度**和**滑多远算够**
     * 永远同一个基准（原来这里写的是 `metrics.sizeDp × widthFraction`，
     * 非圆屏上那个值会被列表的左右内边距夹一次才落到真实宽度上，两者差 6%）。
     */
    rowWidth: Dp,
    rowHeight: Dp,
    onValue: (Double) -> Unit,
    onPlayToggle: () -> Unit,
    onLongPress: () -> Unit,
    /**
     * 左滑删除（滑够 1/3 行宽）—— 走状态层 `GraphState.removeSlider`，
     * 和别的行一样：撤销条、2 秒到期都由状态层那一路管，这里只管"手势 + 动画"。
     */
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // 播放键那一格的 x 区间（行内坐标，px）。含义见文件头"播放键那一格不算长按区"：
    // 版面用的就是下面这几个常量，所以手势和版式不会各说各话。
    // 算式在 `PlotMetrics.sliderPlayKeyLeftDp` / `sliderPlayKeyRightDp`（纯函数、离线单测钉着）：
    // 左沿 = 行左内边距 + 参数字母栏 + 一条缝；右沿 = 左沿 + 播放键宽。
    val playLeftPx = with(density) { sliderPlayKeyLeftDp(metrics.sizeDp.value).dp.toPx() }
    val playRightPx = with(density) {
        sliderPlayKeyRightDp(metrics.sizeDp.value, rowHeight.value).dp.toPx()
    }

    // ---- 左滑删除：和函数行 / 对象行**同一个共用件** ----
    //
    // 跟手位移、渐染进度、"先播完甩出动画再真删"、阈值（1/3 行宽）全部来自
    // `rememberRowSwipeDelete` + `PlotMetrics.SWIPE_DELETE_*` —— 这一行**没有**第四份
    // 搓手势的代码。唯一多传的是 `skipIfDownConsumed = true`（见文件头那一节）。
    val swipe = rememberRowSwipeDelete(slider.id, onDelete)
    val rowWidthPx = with(density) { rowWidth.toPx() }
    val swipeProgress = swipe.progress(rowWidthPx)

    // ---- 整行长按的"有没有出界"观察者（本轮新增）----
    //
    // 用户要求（全局性）："所有按钮的长按判定都改成只有在按钮的矩形框内才判定"。
    // 这一行的长按是**自己手写**的（下面那段 `withTimeoutOrNull + waitForUpOrCancellation`），
    // 所以要在长按真的触发之前加一句 [PressInsideGuard.stayedInside]：
    // 手指在按住期间离开这一行自己的矩形（含极小的 [PRESS_INSIDE_SLACK_FRACTION] 容差）
    // → 这一次长按作废，不弹滑动条属性界面。
    //
    // 为什么用观察者而不是改那段 `waitForUpOrCancellation()`：AndroidX 那个函数**也**会
    // 在出界时提前返回（于是超时不会触发），但它的边界是"节点矩形 + 系统最小触控目标补白"
    // （这一行只有 0.15 短边 ≈ 35dp 高，补白 ≈6.5dp）—— 比用户要的容差大一档。
    // 观察者是**零消费**的，所以"滑轨拖值 / 按住 ▶ / 左滑删除"三条既有语义一个都不受影响。
    val longPressGuard = rememberPressInsideGuard(slider.id)

    Surface(
        // 滑动过程中整行朝砖红渐染（和函数行、对象行同一个"松手就要删了"的暗示）
        color = lerp(KeyPalette.normal, KeyPalette.danger, swipeProgress * SWIPE_DELETE_TINT),
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
            .width(rowWidth)
            .height(rowHeight)
            .graphicsLayer {
                // 松手删除：停在滑到的位置再往外甩 + 缩小 + 淡出（= "扔掉"），
                // 三个派生值都来自共用件（三行同一套数）。
                //
                // ⚠️ 横向位移走 [com.functiondrawer.watch.ui.RowSwipeDelete.translationXPx]：
                // 跟手位移和甩出位移都是**往左的负数**（相加 = 更往左）。
                // 上一版这里写的是 `dragX - sweepShiftPx(...)`（等于往右回弹），
                // 用户报的"删除动画鬼畜、函数条从左边移到右边去"就是它 ——
                // 三行共用同一处算式之后，这一行不可能再写反。
                translationX = swipe.translationXPx(rowWidthPx)
                val s = swipe.sweepScale()
                scaleX = s
                scaleY = s
                alpha = swipe.sweepAlpha()
            }
            .pointerInput(slider.id) {
                awaitEachGesture {
                    // **不要求** down 未被消费：滑轨和播放键都会抢先消费它（原因见文件头注释）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 按在播放键上：这一格交给 [PlayKey] 自己处理，长按不成立
                    // （判定用的是和版面同一份数字算出来的边界，见上面的 `playLeftPx`）
                    val onPlayKey = sliderPressOnPlayKey(down.position.x, playLeftPx, playRightPx)
                    // 超时之前手势就结束了（抬起 / 被拖动接管）→ 不是长按。
                    // `withTimeoutOrNull` 只在**真的超时**时返回 null，所以这一个判断就够了。
                    val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        true
                    }
                    // ⚠️ 本轮新增最后那个条件（"长按必须留在这一行自己的矩形里"）：
                    // 手指中途离开过这一行 → `stayedInside == false` → 这一次长按作废。
                    // 判定本体在 `PlotMetrics.pressInsideRect`，观察者在上面 `longPressGuard`。
                    // 三个条件缺一不可：超时到了、不是按在播放键上、而且**手指一直没出界**。
                    if (finishedEarly == null && !onPlayKey && longPressGuard.stayedInside) onLongPress()
                }
            }
            // 观察者：只记"手指有没有离开过这一行"，**一个字节都不消费**（见上面那段说明）
            .pressInsideGuard(longPressGuard)
            // ---- 左滑删除（和函数行 / 对象行同一个实现，"起手区"由谁消费 down 决定）----
            //
            // ⚠️ `skipIfDownConsumed = true` 是这条行与另外两行**唯一**的差异：
            // 按在滑轨/播放键上时，down 已被它们自己消费 → 这里直接不开始，
            // 横向拖动整串留给"拖值"（绝不会出现"拖着拖着把行删了"）。
            .then(
                swipe.gesture(
                    rowKey = slider.id,
                    rowWidthPx = rowWidthPx,
                    skipIfDownConsumed = true
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_GAP)
        ) {
            // ---- 参数名 + 当前值 ----
            Column(
                modifier = Modifier.width(metrics.sizeDp * PARAM_COL_W),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = slider.param,
                    fontSize = metrics.fontSmall,
                    fontWeight = FontWeight.Medium,
                    color = KeyPalette.text,
                    maxLines = 1
                )
                Text(
                    text = formatSliderValue(slider.value),
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    maxLines = 1
                )
            }

            // ---- 播放键 ----
            // 本轮**未改动**（真机验证点 ▶/■ 正常、播放正常）。
            // 只有一件事在 [SliderRow] 那边配合：按住 ▶ 不再被整行的长按判定吃掉
            // （见文件头"播放键那一格不算长按区"）。
            PlayKey(
                playing = slider.playing,
                metrics = metrics,
                size = rowHeight * PLAY_KEY_H,
                onClick = onPlayToggle
            )

            // ---- 下界 ----
            Text(
                text = formatSliderValue(slider.min),
                fontSize = metrics.fontTiny,
                color = KeyPalette.textMuted,
                maxLines = 1,
                textAlign = TextAlign.Start
            )

            // ---- 滑轨（拖动 / 点一下跳到那儿）----
            SliderTrack(
                slider = slider,
                metrics = metrics,
                rowHeight = rowHeight,
                onValue = onValue,
                modifier = Modifier.weight(1f)
            )

            // ---- 上界 ----
            Text(
                text = formatSliderValue(slider.max),
                fontSize = metrics.fontTiny,
                color = KeyPalette.textMuted,
                maxLines = 1,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * 播放键：▶ = 播放，■ = 停止。
 *
 * 两个都是常见字形（不依赖任何图标库，APK 体积不涨）。
 * 播放中用强调色，一眼能看出"这条正在动"。
 *
 * ⚠️ 这一轮的点击实现**有意保持原样**（`pointerInput + detectTapGestures`）：
 * 真机上点 ▶/■ 是好的，播放功能也验证通过，不能为了"顺手统一写法"去动它。
 * （这颗键唯一相关的改动在 [SliderRow] 里：整行的长按判定把这一格 x 区间排除了，
 *  否则按住 ▶ 半秒会先弹属性界面、松手又切一次播放 —— 见文件头那段说明。）
 */
@Composable
private fun PlayKey(
    playing: Boolean,
    metrics: WatchMetrics,
    size: Dp,
    onClick: () -> Unit
) {
    Surface(
        color = if (playing) KeyPalette.accent else KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = Modifier
            .size(width = size * PLAY_KEY_SCALE, height = size)
            .pointerInput(playing) { detectTapGestures { onClick() } }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (playing) "■" else "▶",
                fontSize = metrics.fontTiny,
                color = KeyPalette.text,
                maxLines = 1
            )
        }
    }
}

/**
 * 滑轨本体。
 *
 * 用 [BoxWithConstraints] 拿宽度而不是 `onSizeChanged`：拖动时要在同一帧里
 * 把手指的 x 换算成数值，用约束里的宽度最直接（也不会因为状态更新慢一帧而抖）。
 *
 * 数值永远走 [Slider.snap]：夹到 `[min, max]` 并对齐到步长，
 * 所以界面上的值和引擎里算的值永远一致（不会出现 0.30000000000000004）。
 *
 * ⚠️⚠️ **下面那两个手势是"滑动条行左滑删除的起手区"的前提，别当成可有可无的装饰**：
 * 这一格里的 `detectTapGestures` 会**消费掉那次 down**，而 [SliderRow] 的删除手势
 * 正是靠 `skipIfDownConsumed = true` 读这个消费标记来判断"手指在滑轨上"的 ——
 * 于是滑轨上的横向拖动整串归改值、绝不会被行删掉。
 * 一旦这两个手势**不再消费 down**（比如有人把点一下跳到那儿的行为去掉、
 * 或换成 `requireUnconsumed = false` 且不 `consume()`），滑动条行就会变成
 * "在滑轨上拖动 = 既改值又删行"。改这里必须回头看一眼 `PlotMetrics` 末尾那张分区表。
 */
@Composable
private fun SliderTrack(
    slider: Slider,
    metrics: WatchMetrics,
    rowHeight: Dp,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackH = rowHeight * 0.16f
    val thumb = rowHeight * 0.34f

    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        // 滑块位置用 dp 算：轨道宽（px 约束 → dp）减掉滑块直径，
        // 这样滑到最右端时滑块也完整留在轨道里，不会被父容器裁掉一半。
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val span = (slider.max - slider.min).let { if (it <= 0.0) 1.0 else it }
        val fraction = ((slider.value - slider.min) / span).coerceIn(0.0, 1.0).toFloat()
        val thumbX = (widthDp - thumb) * fraction

        fun valueAt(x: Float): Double =
            slider.min + (x / widthPx).coerceIn(0f, 1f) * span

        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            // 轨道底色
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackH)
                    .align(Alignment.CenterStart)
                    .clip(PillShape)
                    .background(Color.White.copy(alpha = 0.16f))
            )
            // 已选中的部分（强调色）
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(trackH)
                    .align(Alignment.CenterStart)
                    .clip(PillShape)
                    .background(KeyPalette.accent)
            )
            // 滑块
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbX)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(KeyPalette.text)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(slider.id, slider.min, slider.max, slider.step) {
                    detectTapGestures { pos -> onValue(valueAt(pos.x)) }
                }
                .pointerInput(slider.id, slider.min, slider.max, slider.step) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onValue(valueAt(change.position.x))
                    }
                }
        )
    }
}

/** 数值显示：步长 0.1 → 最多一位小数；整数不显示 `.0`。 */
fun formatSliderValue(v: Double): String {
    val r = (v * 10.0).roundToLong() / 10.0
    return if (kotlin.math.abs(r - r.toLong()) < 1e-9) r.toLong().toString() else r.toString()
}
