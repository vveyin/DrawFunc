// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 拖动只关心哪个轴。
 *
 * 两个轴分别用在：
 *   * [DragAxis.HORIZONTAL] —— 行左滑删除、功能界面右边缘左滑；
 *   * [DragAxis.VERTICAL] —— 功能界面顶部下滑。
 */
internal enum class DragAxis { HORIZONTAL, VERTICAL }

/**
 * 「单向拖动」判定：**自己数位移**，完全不用 foundation 的
 * `detectHorizontalDragGestures` / `detectVerticalDragGestures`，也不用
 * `awaitHorizontalTouchSlopOrCancellation` / `awaitVerticalTouchSlopOrCancellation`。
 *
 * ## 为什么必须自己写（这是一个真机 bug 的修复，不是"风格选择"）
 *
 * 用户报："删除函数的滑动、从第二屏返回到第一屏和函数图像全部失效。"
 * 对着仓库数一遍就很清楚：
 *
 * | 手势 | 实现 | 真机 |
 * |---|---|---|
 * | 列表左边缘右滑、列表上滑区、图上平移、滑轨、缩放条滑轨 | 库函数 `detect*DragGestures` | **能用** |
 * | 行左滑删除、功能界面顶部下滑、右边缘左滑、对象行滑删 | 手写的 `await*TouchSlopOrCancellation` | **全死** |
 *
 * 也就是说：**凡是走库函数的手势都活着，凡是手写 slop 判定的都死了**。
 * 那四个手写版本还各自加了一句"只在某个方向时 `consume()`"的条件消费，
 * 而 `await*TouchSlopOrCancellation` 的契约是"**只有回调里消费了才返回非 null**"
 * （1.7 的字节码：回调返回后判 `isConsumed`，没消费就 `TouchSlopDetector.reset()` 再等下一帧；
 * 另外它一进来就有一句 `currentEvent.isPointerUp(pointerId) → null`）。
 * 契约本身没问题，问题是我们不该把一个"能不能开始"的判定，压在这套
 * "消费 + 内部累计 + 每帧 reset"的组合语义上 —— 它出问题时**一点反馈都没有**
 * （手势被静默丢弃），而且没法在本机复现验证。
 *
 * 所以这里只留下三个语义最简单、最好推理的原语：
 *   * `awaitPointerEvent()` —— 拿下一帧事件；
 *   * `change.positionChangeIgnoreConsumed()` —— 这一帧位移了多少（见下一节，**不是**
 *     `positionChange()`）；
 *   * `change.consume()` —— 我要了这一串（自己决定，不受库内部状态影响）。
 *
 * ## 谁在消费这一串事件（第二条真机 bug："纯水平左滑删不掉、斜着滑就能删"）
 *
 * 用户第二次报的原话："不对我发现问题了，是横向滑动不能删，斜向滑动可以删。"
 * 方向依赖说明问题**不在"手势挂没挂"**，而在**事件流被谁吃掉了**。把本机依赖里
 * 那份 Compose 1.7 的 class 反编译出来对一遍，三条事实拼起来就是完整机制：
 *
 * 1. **`positionChange()` 在"这一帧已被消费"时返回 0**：
 *    ```
 *    positionChangeInternal(change, ignoreConsumed = false) =
 *        if (!ignoreConsumed && change.isConsumed()) Offset.Zero else offset
 *    ```
 *    另有 `positionChangeIgnoreConsumed()` 永不返回 0。而"消费"是一个**每帧共享**的标记
 *    （`PointerInputChange.consume()` 把 `consumed.downChange / positionChange` 置真，
 *    `Node.buildCache` 给每个节点做的副本**共用同一个 `ConsumedData` 实例**），
 *    所以**任何一个节点在这一帧消费过，之后被派发到的节点读到的位移都是 0**
 *    （下一帧会重置，`PointerInputChangeEventProducer` 里 `isInitiallyConsumed = false`）。
 * 2. **库函数 `detectHorizontalDragGestures` 一旦越过水平容差，就在之后每一帧
 *    `consume()` 这一串事件，与调用方是否处理无关**：反编译出来的 drag 回调是
 *    ```
 *    onHorizontalDrag(it, positionChange(it).x); it.consume();
 *    ```
 *    —— 我们回调里那句 `if (!armed) return` 挡不住后面那句 `consume()`。
 * 3. **`Node.dispatchMainEventPass` 的顺序是"自己 Initial → 子节点 → 自己 Main"**，
 *    也就是 Main 阶段**子节点先收到、祖先随后收到**；祖先想抢在子节点之前，只有走
 *    Initial 阶段（我们这两屏都没有那样的节点）。另外**库手势的容差判定用的是
 *    `position - previousPosition` 原始位移**（`TouchSlopDetector.addPointerInputChange`），
 *    即**天然不受消费影响** —— 只有我们这份自己写的判定当时用了消费敏感的
 *    `positionChange()`。
 *
 * 于是真机上：
 *   * **纯水平左滑**：整屏根节点上那条"左边缘右滑进功能界面"用的正是库函数
 *     `detectHorizontalDragGestures`（见 `FunctionList.kt`），它越过了水平容差就开始
 *     **每帧消费**这一串；我们的行删除手势判定当时读的是 `positionChange()` →
 *     从那帧起**每帧读到 0** → 累计位移冻在触摸容差附近（≈24px），
 *     **永远到不了"行宽 1/3"** → 不认领、不跟手、松手也不删。用户看到的就是
 *     "横向滑不动"。它跟"这是函数行还是对象行"无关（那条手势挂在整屏根节点上）。
 *   * **带斜度的左滑**：纵向分量让**列表自己的竖直滚动**先越过容差并认领（列表只认
 *     竖直轴），库手势的通用约定是"别人消费了这一串我就退出"，
 *     于是**没有谁在每帧 consume 我们的横向位移**了 → 累计恢复正常 → 滑够 1/3 →
 *     认领 → 跟手 → 松手删除 ✔。
 *   * **纯竖直上下滑**：我们的横向累计 ≈ 0，越过容差时方向不对 → **零消费退出**，
 *     列表照常滚动、绝不删除。
 *
 * 所以本文件这两条规矩是**成对**的，谁拆开谁复发：
 *   * **判定只看向"我们自己的累计位移 + 方向"**（[oneWayDragStep] /
 *     [oneWayDragClaims]，实现上用 `positionChangeIgnoreConsumed()`）——
 *     别人在别的轴上消费，不关我们的事；
 *   * **方向不对就零消费退出**（[claimOpposite] 是唯一例外，见它的说明）——
 *     这样两条方向相反的手势（左滑删行 / 左边缘右滑进功能界面）**互不消费对方的
 *     事件流**，谁也不抢谁。
 *
 * ## 语义（和库里那套等价，但由我们说了算）
 *
 *   * 手指落下 → 先看 [startsIn]（起手点是否在允许的区域），不在就**一个字都不碰**；
 *   * [skipIfDownConsumed] 为 true 时，若这次的 down **已经被子节点消费**
 *     （例如按在"按住才生效"的按钮上），同样直接放行不管 —— 这是给
 *     `HoldToConfirm` 那种按钮留的活路；
 *   * 累计位移**带符号**：越过触摸容差时看方向，方向对（[positive]）就"认领"
 *     这一串手势（之后每帧 `consume()` 并回调累计位移）；方向不对就**立刻退出、
 *     一个字节都不消费**，把这一串原样让给别人（父节点的手势、列表滚动都靠这个）；
 *   * 累计**只按我们自己的位移**加（`positionChangeIgnoreConsumed()`，见上面那一节）：
 *     **别人消费了这一帧也不影响我们的累计与认领** —— 这正是"纯水平左滑删不掉"
 *     那个 bug 的解药；
 *   * 认领之后**不再看 `isConsumed`**：我们自己每帧都在消费，再去判它只会自锁；
 *   * 手指抬起时，若认领过就回调 [onEnd]（最终累计位移），由调用方判定"够不够远"。
 *
 * @param axis 只累计这个轴的位移。
 * @param positive 认哪个方向：水平 true = 往右、false = 往左；竖直 true = 往下、false = 往上。
 * @param startsIn 起手点是否在允许区域内（默认哪里都行）。坐标是**本节点内**的 px。
 * @param skipIfDownConsumed down 已被别人消费时是否直接放弃（见上）。
 * @param claimOpposite 方向**不对**时要不要也认领（默认 false = 零消费退出）。
 *   打开它的唯一用处：**让"这一行的水平拖动"整串归这一行**，于是子节点
 *   `combinedClickable` 的点击判定会因为"这一串被别人消费了"而取消 ——
 *   行上"往右拖一下"就不会被当成"点了一下这一行"（去打开编辑器/属性页）。
 *   [onDrag]/[onEnd] 拿到的仍然是**带符号**的累计位移，所以"够不够删"的判定
 *   （往左为正条件）不受影响。⚠️ 打开它以后，本手势就**不再是**"方向不对就让给别人"，
 *   只该用在"这一整条轴上的拖动本来就属于我"的地方（行左滑删除就是）。
 * @param abandonIfCrossAxisWins 交叉轴（另一个轴）先一步越过触摸容差、而且比主轴大时，
 *   要不要**永久放弃**这一串（默认 false = 老行为，一个字都不变）。
 *
 *   ⚠️ 打开它的只有**行左滑删除**这一处，它修的是用户报的误删 ——
 *   "上下滑动之后，如果再左右滑动，会唤起这个开始按住的对象的删除"。
 *   判定本体在 [oneWayDragLostToOtherAxis]（纯函数、离线单测钉着），
 *   这里只负责"每帧累计交叉轴 + 一旦为真就 break"。
 *
 *   为什么必须是 break（而不是"这一帧不认领、下帧再看"）：用户明确要求
 *   "**之后的横向移动也不能再复活它**"。一旦列表的竖直滚动把这一串拿走了，
 *   这一串就与"删除"无关了；继续观察只会让它在下一次横向移动时复活 ——
 *   那还是同一类误删。
 *
 *   为什么它不会弄坏"纯水平左滑"（上一轮刚修好的那条）：纯水平滑动里，
 *   横向一直领着纵向、纵向抖动也到不了容差，[oneWayDragLostToOtherAxis] 恒为 false。
 *   真机上手感的完整结论写在 `PlotMetrics.oneWayDragLostToOtherAxis` 的 KDoc 里。
 * @param onDrag 认领之后每帧回调，参数是**从落点算起的累计位移**（px，带符号）。
 * @param onEnd 手势结束（抬手）且认领过时回调，参数是最终累计位移。
 */
internal suspend fun AwaitPointerEventScope.awaitOneWayDrag(
    axis: DragAxis,
    positive: Boolean,
    startsIn: (Offset) -> Boolean = { true },
    skipIfDownConsumed: Boolean = false,
    claimOpposite: Boolean = false,
    abandonIfCrossAxisWins: Boolean = false,
    onDrag: (Float) -> Unit = {},
    onEnd: (Float) -> Unit = {}
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!startsIn(down.position)) return
    if (skipIfDownConsumed && down.isConsumed) return

    val slopPx = viewConfiguration.touchSlop
    var total = 0f
    /** 交叉轴上的同一份累计（行删除时是竖直位移）。只在 [abandonIfCrossAxisWins] 时用。 */
    var crossTotal = 0f
    var claimed = false

    while (true) {
        val event = awaitPointerEvent()
        // 这个指针不在事件里了就当作结束（多指、被打断）
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        val isUp = change.changedToUpIgnoreConsumed()

        if (!isUp) {
            // ⚠️ `positionChangeIgnoreConsumed()`，**不是** `positionChange()`：
            // 后者在这一帧已被任何人消费时返回 0（1.7 的字节码），于是父节点一抢着消费，
            // 我们的累计就被每帧清零、永远够不着阈值 —— 真机上"纯水平左滑删不掉、
            // 带斜度就能删"就是它（详见文件头那一节）。认领与否只该由
            // **我们自己的累计位移 + 方向**决定。
            val move = change.positionChangeIgnoreConsumed()
            val delta = if (axis == DragAxis.HORIZONTAL) move.x else move.y
            // 交叉轴用**同一把尺子**量（同样是 `…IgnoreConsumed()`）：它只用来回答
            // "这一串是不是已经被另一个轴拿走了"，不参与"够不够远"的判定。
            val crossDelta = if (axis == DragAxis.HORIZONTAL) move.y else move.x
            total = oneWayDragStep(total, delta)
            crossTotal = oneWayDragStep(crossTotal, crossDelta)

            if (!claimed) {
                if (abandonIfCrossAxisWins &&
                    oneWayDragLostToOtherAxis(total, crossTotal, slopPx)
                ) {
                    // 交叉轴先越过了容差、而且比主轴大 = 列表竖直滚动（或父节点的
                    // 那个轴）已经拿走这一串 → **永久放弃**：不认领、不消费、
                    // 也不再回头看（用户："之后的横向移动也不能再复活它"）。
                    break
                }
                // 判据本身抽在 `PlotMetrics.oneWayDragClaims`（纯函数，离线单测钉着）：
                // 越过容差 + 方向对 = 认领；越过容差但方向不对 = 默认这一串到此为止。
                if (oneWayDragClaims(total, positive, slopPx)) {
                    claimed = true
                } else if (abs(total) >= slopPx) {
                    if (!claimOpposite) {
                        // 方向不对：让给别人，这一串到此为止（不消费任何事件）
                        break
                    }
                    // 方向不对但这一整条轴归我们（见 `claimOpposite`）：也认领，
                    // 只是 onDrag/onEnd 拿到的是带符号的值，调用方自己判"算不算数"。
                    claimed = true
                }
            }
            if (claimed) {
                change.consume()
                onDrag(total)
            }
        }

        if (isUp) break
    }

    if (claimed) onEnd(total)
}

/**
 * 一行「左滑删除」的**全部状态与手势** —— 函数行、几何对象行和**滑动条行**
 * 共用**这一个**实现。
 *
 * ## 为什么要有这个共用件（用户报的 bug 的真因就在"各写一套"上）
 *
 * 用户原话："除了函数之外的对象在列表都不能滑动删除，应该所有对象都能滑动删除的。"
 *
 * 对着改动前的代码数一遍，函数行（`FunctionList.FunctionRow`）和对象行
 * （`FunctionsScreen.GraphObjectRow`）的删除手势是**两段互相抄出来的代码**：
 *
 * | | 函数行 | 对象行（改动前） | 用户看到 |
 * |---|---|---|---|
 * | 阈值 | `rowWidthPx * SWIPE_DELETE_TRAVEL` | `rowWidthPx * 0.3333f` | 一样 |
 * | 动画 | `DELETE_OUT_MS/SHIFT/SHRINK` | `DELETE_OBJECT_MS/SHIFT/SHRINK` | 一样 |
 * | 起手位置 | **不限**（`startsIn` 用默认的"哪里都行"） | **行右侧那条带不算**（`startsIn = …edgeBandPx…`） | **只有函数能删** |
 *
 * 也就是说：几何上唯一让两行行为不同的，就是对象行那条**起手限制** ——
 * 它是照"第二屏右边缘往左滑 = 回函数列表"写的，可是**对象行根本不在第二屏**
 * （`GraphObjectRow` 的唯一调用点是 `FunctionList.kt` 的第一屏），而第一屏的切屏手势是
 * **从左边缘往右滑**（`SWIPE_EDGE_W`），和"往左滑删除"方向天然互斥 ——
 * 所以那条限制在第一屏上是**纯损失**：从行右侧起手的左滑，`startsIn` 返回 false，
 * `awaitOneWayDrag` 立刻返回、**一个字节都不消费、也没有任何反馈**，
 * 用户看到的就是"这条行根本划不动"（而它右侧那一带正是大家左滑删除时最自然的落点）。
 *
 * 所以这一次不是"给对象行再补一段手势"，而是**把两行合并到这一个件上**：
 * 阈值、方向判据、"先播完甩出动画再真删"、跟手位移，全部只有一份实现
 * （纯算术那一半在 `PlotMetrics.SWIPE_DELETE_*` / `swipeDelete*`，离线单测钉着）。
 * 两行现在**没有任何可调参数上的差异**，因此"函数行能删 ⟹ 对象行也能删"是结构性的，
 * 而不是靠两段代码碰巧写得一样。
 *
 * ## 第三个调用点：滑动条行（#3），以及它**唯一**的那一处差异
 *
 * 滑动条行（`SliderRow`）是本文件第三个使用者 —— 阈值、跟手、渐染、甩出、撤销
 * 走的还是同一条路（这就是"删除的语义与别的行逐项一致"的保证方式）。
 * 它只多传一个参数：[RowSwipeDelete.gesture] 的 `skipIfDownConsumed = true`。
 * 原因是那条行的横向拖动**本来就有主人**：滑轨上拖动 = 改参数值。
 * 所以划分是"**谁在手指底下，谁说了算**"：
 *   * 滑轨 / 播放键 → 它们自己的 `detectTapGestures` 消费 down → 删除手势**根本不开始**
 *     （不消费、没有跟手位移、松手也不删），横向拖动整串留给"拖值"；
 *   * 参数字母/当前值徽标、下界/上界文字、行内边距与缝隙 → 没人消费 down →
 *     删除手势照常起手（左滑删、往右什么也不做）。
 * 这一条**不是**"按 x 坐标切一刀"：滑轨的左右沿取决于下界/上界文字的实测宽度
 * （它是 `weight(1f)`），写死比例迟早会在某块屏/某种量程上把滑轨划进删除区 ——
 * 那就是"在滑轨上误删"，而用户明确要求这件事永远不能发生。
 * 三块屏的实际 dp 数、以及"为什么这样划分手感最好"的完整推导，写在 `PlotMetrics`
 * 末尾那一节（`SLIDER_*` / `sliderPlayKey*`）。
 *
 * ## 事件链（谁拿到 down → 谁认领 → 谁消费）
 *
 * 1. 手指落在行上：行的 `Surface` 是子节点（色点区 / 文字区的 `combinedClickable`）的
 *    **祖先**，命中链是"子节点 → 行 → 列表 → 根节点"，而 Compose 在 **Main pass** 上
 *    是**子节点先收到**事件；
 * 2. 子节点的 `combinedClickable` 会消费掉 down（它要判点击/长按），
 *    所以这里用 `awaitFirstDown(requireUnconsumed = false)` 起手 —— **down 被消费不影响
 *    本手势开始**，只是不认领时什么也不做（这一条是"色点单击切显隐、长按调色、
 *    长按文字区进属性屏"还能用的一半原因）；
 * 3. 头几像素抖动**不过触摸容差**：`oneWayDragClaims` 两个方向都不认领，
 *    事件原样往上传 —— 点击、长按、列表竖直滚动、根节点的边缘手势全部照旧；
 * 4. 越过容差且**往左**：认领，此后每帧 `consume()` 并回调累计位移（行跟手、渐染）；
 *    方向不对（往右 / 竖直）→ 立刻退出、**零消费**，让给别人（第一屏"左边缘往右滑
 *    进功能界面"靠的就是这里一个字节都不碰）；
 * 4.5 **竖向先越过容差**（`abandonIfCrossAxisWins`）：列表的竖直滚动已经把这一串拿走了
 *    → 本手势**永久放弃**（`break`，此后一个字节都不消费、也不再回头）。
 *    这一条修的是用户报的误删："上下滑动之后，如果再左右滑动，会唤起这个开始按住的
 *    对象的删除"。判据在 `PlotMetrics.oneWayDragLostToOtherAxis`（纯函数、离线单测钉着）：
 *    **谁的轴先越过触摸容差，这一串就归谁** —— 和平台自己的 `scrollable`/`draggable`
 *    同一条约定；
 * 5. 抬手：位移够 1/3 行宽 → 先播 [DELETE_OUT_MS] 的"甩出去"，播完（**或者动画被打断**）
 *    再调 `onDelete()` —— `finally` 里交删除，保证"滑了却没删掉"不会发生；
 *    不够 → `dragX` 归零（行弹回原位）。
 *
 * ## 撤销
 *
 * 撤销条不在这一层：删除动作仍然走调用方原来那条回调（对象行是
 * `GraphState.removeObject`，它会把被删的那一个存进 `lastDeletedObject`；
 * 滑动条行是 `GraphState.removeSlider`，存进 `lastDeletedSlider`），
 * 于是底部浮出 2 秒的「已删除 · 点此撤销」。三行在这一点上也**走同一条路**：
 * 删除 = 一次状态层调用，撤销 = 状态层自己那一份备份，谁也不在这里自己记一份。
 * ⚠️ 每路备份都必须在 `GraphScreen` 那个 2 秒到期的 effect 里被 `forget*` 掉 ——
 * 漏一路的后果不是"消失得慢"，而是"撤销条永不消失、还会把两笔删除混在一起"
 * （几何对象那一路真机踩过，见 `GraphScreen` 里那段注释）。
 */
internal class RowSwipeDelete(
    private val scope: CoroutineScope,
    private val onDelete: () -> Unit
) {
    /** 跟手位移（px，往左为负）。行画在哪全靠它。 */
    var dragX by mutableStateOf(0f)
        private set

    /**
     * 「被扔掉」那一下的进度 0..1：横向甩出、缩小、淡出都跟着它。
     *
     * 用 `Animatable` 而不是自己数帧：它可被打断、可取消，而调用方（渲染那一层）
     * 只读 [sweepShiftPx] / [sweepScale] / [sweepAlpha] 三个派生值。
     */
    private val sweepAnim = Animatable(0f)
    val sweep: Float get() = sweepAnim.value

    /** 渐染 / 触发进度 0..1（滑够 = 1）。 */
    fun progress(rowWidthPx: Float): Float = swipeDeleteProgress(dragX, rowWidthPx)

    /**
     * 「被扔掉」那一下**额外**的横向位移（px，往左为负）。
     *
     * ⚠️ 它是**带符号的负值**（继续往左甩），不是"回弹量"。所以它只能和
     * [dragX] 相**加**，绝不能相减 —— 三个调用点上一版写的都是
     * `translationX = dragX - sweepShiftPx(...)`，等于把行从左边推回右边
     * （用户报的"函数条会从左边移到右边去"）。**别再直接用它**，
     * 用 [translationXPx]（那里只有一处算式，符号修好之后就再也不会有人写反）。
     */
    fun sweepShiftPx(rowWidthPx: Float): Float =
        swipeDeleteSweepShiftPx(rowWidthPx, sweep)

    /**
     * 这一行**当前**该画在哪（px，往左为负）—— 渲染层唯一该读的横向位移。
     *
     * = 跟手位移（[dragX]，负数）+ 「被扔掉」那一下的继续甩出（[sweepShiftPx]，也是负数）。
     * 算式的**唯一来源**是 `PlotMetrics.swipeDeleteTranslationPx`（纯函数、离线单测钉着，
     * 那里解释了"上一版为什么是往右跑"）。
     *
     * 为什么把这两半合成一个函数：函数行 / 几何对象行 / 滑动条行三处的
     * `graphicsLayer` 里原来各写了一遍 `dragX - sweepShiftPx(...)`，
     * 于是同一个符号错误被抄了三份。合成之后"往左甩出"这件事只有一处实现，
     * 三行结构性地一致。
     */
    fun translationXPx(rowWidthPx: Float): Float =
        swipeDeleteTranslationPx(dragX, rowWidthPx, sweep)

    /** 「被扔掉」那一下的缩放系数。 */
    fun sweepScale(): Float = swipeDeleteSweepScale(sweep)

    /** 「被扔掉」那一下的不透明度。 */
    fun sweepAlpha(): Float = swipeDeleteSweepAlpha(sweep)

    /**
     * 挂在这一行上的左滑删除手势。
     *
     * @param rowKey 行的身份（函数 id / 对象 id / 滑动条 id）：换行时手势与位移状态一起重来。
     * @param rowWidthPx 这一行**当前**的宽度（px）—— 阈值按它算，进 key 是为了
     *   行宽变了（列表滚动时圆屏弧宽会变）就跟上新的阈值。
     * @param skipIfDownConsumed `down` 已经被行内**子节点**消费时，这一串手势直接放弃
     *   （`awaitOneWayDrag` 的同名参数，默认 false = 现有两个调用点的行为一个字节都不变）。
     *
     *   打开它的只有**滑动条行**（`SliderRow`），而且它正是"滑动条行的删除起手区"
     *   这条规则本身：手指按在**滑轨**或**播放键**上时，那两块地方自己的
     *   `detectTapGestures` 会消费 down（这是既有的、真机上验证过的行为 ——
     *   `SliderRow` 文件头那个"长按滑动条没反应"的 bug 就是它造成的），
     *   于是删除手势**根本不开始、一个字节都不消费**，横向拖动整串留给"拖参数值"。
     *   按在参数字母/当前值徽标、下界/上界文字、内边距上时没人消费 down，
     *   删除手势照常起手 —— 这就是用户要的"非滑轨区域左滑能删、滑轨上拖值绝不误删"。
     *
     *   为什么用"谁消费了 down"分区、而不是传一个 x 区间（`startsIn`）：
     *   滑轨的左右沿取决于**下界/上界文字的实测宽度**（滑轨是 `weight(1f)`），
     *   同一个 233dp 圆表上 `-5~5` 和 `-0.5~0.5` 量出来的滑轨不是同一条 ——
     *   写死比例就会在窄文字时把滑轨划进删除区，而那正是"误删"。
     *   详细的划分表与三块屏的 dp 数见 `PlotMetrics` 末尾那一节。
     */
    fun gesture(
        rowKey: Any?,
        rowWidthPx: Float,
        skipIfDownConsumed: Boolean = false
    ): Modifier =
        Modifier.pointerInput(rowKey, rowWidthPx, skipIfDownConsumed) {
            awaitEachGesture {
                // 新的一串手势：位移归零（上一次不够远时已经归零过，这里兜底）
                dragX = 0f
                awaitOneWayDrag(
                    axis = DragAxis.HORIZONTAL,
                    positive = false,          // 只认**往左**
                    // ⚠️ **没有** `startsIn`：整行哪里起手都算。
                    // 对象行上一版在这里按"右 26% 屏宽让给回列表手势"切了一刀，
                    // 而对象行只在第一屏、第一屏的切屏是**往右**滑 —— 那一刀纯属损失
                    // （详见本类上面那段）。所以共用件里根本不提供这个参数，
                    // 谁也加不回来。
                    //
                    // ⚠️ **唯一**的起手限制是滑动条行传进来的 [skipIfDownConsumed]
                    // （见参数说明）：它不是"按 x 坐标切一刀"，而是"手指底下那一块
                    // 自己说了算" —— 滑轨和播放键会消费 down，别处不会。
                    // 加这一条**没有**动摇上面那条结论：函数行、对象行照旧哪里都能起手。
                    skipIfDownConsumed = skipIfDownConsumed,
                    //
                    // ⚠️ `claimOpposite = true`：**这一行的水平拖动整串归这一行**，
                    // 包括"往右"的那一半。两个原因：
                    //   1. 往右拖时不认领的话，谁都不消费这一串，行内子节点的
                    //      `combinedClickable` 就会把它当成"点了一下这一行"
                    //      （函数行 = 打开编辑器、对象行 = 无动作），
                    //      而"拖一下却进了编辑器"是明显的误触；
                    //   2. 认领之后 [onDrag]/[onEnd] 拿到的仍是**带符号**累计值，
                    //      往右是正数，[swipeDeleteReached] 一定判否 → 行弹回原位、
                    //      不删（语义还是"只有往左才删"）。
                    claimOpposite = true,
                    // ---- 上下滑之后再左右滑，不许删（#4，本轮修的误删）----
                    //
                    // 用户原话："我在列表这里上下滑动之后，如果再左右滑动，会唤起这个
                    // 开始按住的对象的删除，但是实际上我上下滑动之后，我应该唤起不了
                    // 对象的删除。"
                    //
                    // 打开这一条之后：**谁的轴先越过触摸容差，这一串就归谁** ——
                    // 竖直先过容差（= 列表滚动已经认领）→ 本手势**永久放弃**这一串，
                    // 之后的横向移动也复活不了它（`break`，见
                    // `PlotMetrics.oneWayDragLostToOtherAxis` 的完整推导）。
                    //
                    // 三条行为（离线单测逐条钉着，真机验收表见本轮报告）：
                    //   * **纯水平左滑** → 纵向一直没过容差 → 照旧能删（上一轮刚修好的，
                    //     这一条没被动到）；
                    //   * **纯竖直上下滑** → 横轴不动，纵向先过容差 → 放弃，列表照常滚；
                    //   * **斜着滑** → 横为主（|x| 一直领着 |y|）仍算删除；
                    //     竖为主（|y| 先过容差且大于 |x|）归列表滚动。
                    abandonIfCrossAxisWins = true,
                    onDrag = { total ->
                        // 只跟手往左的部分（往右不跟手，行不会被拽出屏幕）
                        dragX = swipeDeleteFollow(total)
                    },
                    onEnd = { total ->
                        // 滑够 1/3 行宽才删（这个手势没有二次确认，随手一划不该删掉）
                        if (swipeDeleteReached(total, rowWidthPx)) {
                            // **先播完"被扔掉"再删**：`onDelete()` 一调这一行就被状态层
                            // 摘掉了，那时再想播动画就没对象了。`dragX` 故意不清零：
                            // 行停在滑到的位置上继续往外甩。
                            // finally 里交删除：万一动画被打断（这一行刚好被列表回收），
                            // 也必须把删除交出去 —— "滑了却没删掉"是最难受的 bug。
                            scope.launch {
                                try {
                                    sweepAnim.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = DELETE_OUT_MS,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                } finally {
                                    onDelete()
                                }
                            }
                        } else {
                            dragX = 0f
                        }
                    }
                )
            }
        }
}

/**
 * 记住一行左滑删除的状态（函数行 / 对象行 / 滑动条行各调一次，实现是同一份）。
 *
 * @param rowKey 行的身份（函数 id / 对象 id / 滑动条 id）：换行就换一套状态。
 * @param onDelete 滑够并**播完**甩出动画之后"真的删"那一下。调用方传的通常是一个
 *   每次重组都新建的 lambda，所以这里用 [rememberUpdatedState] 取**最新**的那一个 ——
 *   否则手势协程里会一直捏着第一次组合时的快照（这类"闭包吃旧值"的坑在本项目里
 *   已经踩过一次，见 `GraphScreen` 里那段 `pointerInput(Unit)` 的注释）。
 */
@Composable
internal fun rememberRowSwipeDelete(
    rowKey: Any?,
    onDelete: () -> Unit
): RowSwipeDelete {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onDelete)
    return remember(rowKey) { RowSwipeDelete(scope) { latest() } }
}

