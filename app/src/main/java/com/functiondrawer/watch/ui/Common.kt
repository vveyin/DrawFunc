// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// 按压反馈
//
// 手表上没地方放涟漪（1.5 寸圆屏，波纹会糊掉半个控件），所以全应用统一用
// 「缩小一档 + 底色提亮一点」来表达"按到了"。
//
// 两条铁律：
//   1. 只动 graphicsLayer —— 缩放/高亮都不参与布局，控件的位置、
//      尺寸、命中区永远不变，绝不会因为动画把点击区挪走；
//   2. 动画值只在 graphicsLayer 的 lambda 里读 —— 这样每帧只让图层失效，
//      不触发重组（手表上这点开销值得省）。
// ---------------------------------------------------------------------------

/** 按下时缩到 0.88（1 - 0.12）。幅度给足一点才有"弹"的手感。 */
private const val PRESS_SCALE = 0.12f

/**
 * 按下时盖在底色上的那层白色的强度。
 * 0.16 差不多是"亮一档"：深灰底（#222629）和 primary 底都会明显变亮，但不刺眼。
 * 用它而不是整体降 alpha：AMOLED 黑底上降 alpha 是"变暗"，读起来像禁用而不是按下。
 */
private const val GLOW_ALPHA = 0.16f

/**
 * 按压反馈：[source] 交给 `clickable`，另外两个动画值由调用方在
 * `graphicsLayer { }` 里读。
 *
 * 为什么两个动画分开：
 *   * [scale] 用 `spring(dampingRatio = 0.45f, stiffness = 900f)` ——
 *     松手时有一点回弹，这点过冲就是"灵动"的来源（约 250ms，不拖沓）；
 *   * [glowAlpha] 用 120ms 的 tween —— 高亮若跟着弹簧过冲会"闪"一下，
 *     它只要快一点跟上就好。
 */
internal class PressFeedback(
    /** 传给 `Modifier.clickable(interactionSource = ...)`。 */
    val source: MutableInteractionSource,
    /** 0 → 未按下，1 → 完全按下（弹簧会略微过冲）。**在 graphicsLayer 里读。** */
    val progress: State<Float>,
    private val scaleAmount: Float,
    private val glowAmount: Float,
    private val glowProgress: State<Float>
) {
    /**
     * 当前缩放：1.0 → 1 - scaleAmount。
     * **在 `graphicsLayer { }` 里读**，别在 composable 主体里读（那会每帧重组）。
     */
    val scale: Float get() = 1f - scaleAmount * progress.value

    /**
     * 按下时那层白色高亮该用的图层 alpha。
     * 同样**在 `graphicsLayer { }` 里读**。
     */
    val glowAlpha: Float get() = glowAmount * glowProgress.value
}

/**
 * 记住一套按压反馈。见 [PressFeedback]。
 *
 * @param scaleAmount 按下时的缩小幅度：0.12 → 缩到 0.88
 * @param glowAmount  高亮强度；传 0f 表示这个组件不要高亮
 *                    （这时高亮动画一直停在 0，不消耗任何时间）
 */
@Composable
internal fun rememberPressFeedback(
    scaleAmount: Float = PRESS_SCALE,
    glowAmount: Float = GLOW_ALPHA
): PressFeedback {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()

    val progress = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f)
    )
    val glowProgress = animateFloatAsState(
        targetValue = if (pressed && glowAmount > 0f) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
    )

    return PressFeedback(source, progress, scaleAmount, glowAmount, glowProgress)
}

// ---------------------------------------------------------------------------
// 浮层淡出（气泡 / 卡片 / 对话框共用）
// ---------------------------------------------------------------------------

/**
 * 浮层的淡出 + 可选缩放。**必须写在 `.border(...)` / `.background(...)` 的左边（前面）**，
 * 否则描边/底色画不进这一层 —— 这正是"白边框瞬间消失"的真因。
 *
 * 真因（用户原话："函数列表里面函数前面的颜色的长按唤起的菜单的消失动画：浮窗是有动画
 * 消失的，但是白边框是突然不见的，白边框和浮窗的动画应该绑定在一起。"）：
 * `graphicsLayer { alpha = … }` 只影响**它右边（内层）**画出来的东西 —— Modifier 链是
 * 外层先画、内层后画，图层 alpha 罩住的只是"进到这一层里"的绘制。所以写成
 *
 * ```
 * .background(KeyPalette.bubble)
 * .border(1.dp, KeyPalette.bubbleBorder, shape)
 * .graphicsLayer { alpha = anim.value }   // ← 太靠右了
 * ```
 *
 * 时，那圈 1dp 白描边落在图层**外面**：浮窗自己的底色（`Surface` 的 background 是在它
 * 自己传进来的 modifier **之后**才加的，所以在图层里）淡出正常，描边却"啪"一下消失。
 * 报出来的那处就是这么写的：`Surface(color = KeyPalette.bubble, modifier = Modifier
 * … .border(1.dp, KeyPalette.bubbleBorder, shape).graphicsLayer { alpha = anim.value })`。
 * 这不是动画时长或 easing 的问题（两处用的是同一个 anim 值），纯粹是链上的位置问题。
 *
 * 正确写法（同一个机制只写一遍，免得每个调用点各写一次 graphicsLayer、各踩一次坑）：
 *
 * ```
 * Modifier
 *     .bubbleFade(alpha = anim.value, scale = 0.92f)   // ← 放最前面
 *     .background(KeyPalette.bubble)
 *     .border(1.dp, KeyPalette.bubbleBorder, shape)
 * ```
 *
 * @param alpha 图层透明度。**会夹到 0..1**：弹簧类动画会过冲（值短暂 > 1 或 < 0），
 *   直接喂给图层会闪一下白/黑边。
 * @param scale 缩放，默认 1f = 只淡出。它和 alpha 一样只在图层里生效，
 *   所以浮层的**占位尺寸、位置、命中区**在动画期间一个像素都不变
 *   （和 [PressFeedback] 的缩放同一条铁律）。
 */
fun Modifier.bubbleFade(alpha: Float, scale: Float = 1f): Modifier {
    // 为什么先把两个形参落到局部量再进 lambda：`GraphicsLayerScope` 自己也有一个 `alpha`
    // （还有 `scaleX`/`scaleY`），而这里的形参偏偏也叫 `alpha`。把两个同名符号写进同一条
    // 赋值（`this.alpha = alpha…`）读起来分不清谁是谁 —— 一旦被读成"图层 alpha 赋值给自己"，
    // 整条淡出就会**静默失效**（浮窗和描边一起不动），这种 bug 不会报错、只会"看起来没修好"。
    // 用 `a` / `s` 之后，"赋进去的是外面传进来的值"是写在字面上的。
    val a = alpha.coerceIn(0f, 1f)
    val s = scale
    return graphicsLayer {
        this.alpha = a
        scaleX = s
        scaleY = s
    }
}

/**
 * 极简 chip。
 *
 * 没有直接用 MD3 的 FilterChip：它自带的 32dp 最小高度和 8dp 内边距
 * 在 1.5 寸屏上会把文字挤没。这里只保留 MD3 的配色语义
 * （选中 = 强调色 KeyPalette.accent，未选中 = 普通键深灰 KeyPalette.normal）。
 */
@Composable
fun MiniChip(
    label: String,
    selected: Boolean,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    selectedColor: Color? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colors
    // 配色统一到 KeyPalette（参考 D:\Culculator 的那套扁平低饱和色）：
    // 选中 = 强调色，未选中 = 普通键底色；文字纯白 / 80% 白。
    // selectedColor 是给"用曲线颜色高亮"的调用方留的口子（那是亮色调，
    // 所以配深色文字而不是白字）。
    val container = when {
        selected && selectedColor != null -> selectedColor
        selected -> KeyPalette.accent
        else -> KeyPalette.normal
    }
    val content = when {
        selected && selectedColor != null -> scheme.onPrimary
        selected -> KeyPalette.text
        else -> KeyPalette.textMuted
    }
    val press = rememberPressFeedback()

    Surface(
        color = container,
        contentColor = content,
        shape = PillShape,
        modifier = modifier
            .graphicsLayer {
                // 缩小只发生在图层里：chip 的占位尺寸不变，旁边的 chip 不会被挤动。
                scaleX = press.scale
                scaleY = press.scale
            }
            // 用自己的缩放做反馈，所以关掉涟漪（indication = null）。
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 按下时叠一层很淡的白 = 提亮一档。
            // 用图层 alpha（而不是 background(color.copy(alpha))）是为了让它每帧
            // 只失效图层、不重组；画在 Text 之前，所以文字始终在最上面。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = press.glowAlpha }
                    .background(Color.White)
            )
            Text(
                text = label,
                fontSize = metrics.fontTiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 手表尺寸的图标按钮。
 *
 * MD3 的 IconButton 固定 48dp —— 在 233dp 的屏上占掉五分之一宽，
 * 所以这里用 Surface 自己画一个，尺寸全由 [metrics] 决定。
 *
 * **最终尺寸与形状是写死的比例**：宽 `0.18 × 短边`、高 `宽 × [ICON_BTN_ASPECT]`
 * （= `0.125 × 短边`，也就是 1.44 : 1），圆角走 [PillShape]（= 半高），
 * 所以圆表 / 方表 / 手机上它是**同一个形状**，只是整体大小不同。
 * 这条比例同时也是 `HoldRing.kt` 里线圈的基准（线圈按
 * `sidePx = metrics.iconButton` 算），两边必须一起看。
 *
 * [onLongClick] 可选：给了就启用长按（用 combinedClickable），
 * 没给就走原来的单击路径（少一层长按判定，手势更干脆）。
 *
 * ⚠️ 本轮（"长按必须留在按钮自己的矩形里"那条要求）之后，**这一个实现内部**就把长按
 * 收紧了：`onLongClick` 非空时会多挂一个 [Modifier.pressInsideGuard]（零消费的观察者），
 * 手指在按住期间离开这颗键的矩形（含 [PRESS_INSIDE_SLACK_FRACTION] 那个极小容差）
 * 长按就作废。调用点什么都不用做 —— 所有图标键（含将来新加的）自动适用这一条。
 * （当前仓库里还没有调用点传 `onLongClick`：⚙/`+`/🗑 的长按走的是 `HoldToConfirm`。）
 */
@Composable
fun WatchIconButton(
    icon: ImageVector,
    contentDescription: String?,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    // 同 KeyPalette：填充态 = 强调色，普通态 = 深灰；图标一律纯白。
    val bg = containerColor ?: if (filled) KeyPalette.accent else KeyPalette.normal
    val fg = contentColor ?: KeyPalette.text
    val press = rememberPressFeedback()
    // 长按的"有没有出界"观察者（只在这个按钮真的带长按时才挂，见上面那段说明）。
    val longPressGuard = rememberPressInsideGuard(contentDescription)

    val tapModifier = if (onLongClick != null) {
        Modifier
            .pressInsideGuard(longPressGuard)
            .combinedClickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
                // 出界过 → 这一次长按作废（短按不受影响，走 onClick）
                onLongClick = { if (longPressGuard.stayedInside) onLongClick() }
            )
    } else {
        Modifier.clickable(
            interactionSource = press.source,
            indication = null,
            onClick = onClick
        )
    }

    Surface(
        color = bg,
        contentColor = fg,
        // 形状 = **按比例**的胶囊（[PillShape] = RoundedCornerShape(percent = 50)），
        // 不再是固定 14dp 的 `MaterialTheme.shapes.small`。
        //
        // 先澄清一句旧注释：原来这里写"图标按钮**保持圆角方形**、用户说过不许变成圆形"。
        // 那句话和实情不符 —— 按钮实际是 0.18 × 0.125 个短边（1.44 : 1 的扁矩形），
        // 14dp 圆角在圆表上（高 0.125 × 233 = 29.1dp）等于**半高的 96%**，
        // 画出来本来就是近胶囊。所以旧注释不是"被违反了"，而是一开始就写错了。
        //
        // 真正的规矩是：**圆角必须跟着尺寸走，固定 dp 的圆角在尺寸变化时会比例失调。**
        // 同一颗按钮在三块屏上（圆角都是固定 14dp）：
        //   * 圆表 466px/d2：高 29.1dp → 14 / 14.55 = 半高的 96%（≈ 胶囊）；
        //   * 方表 400px/d2：高 25dp  → 14dp 已经**超过半高 12.5dp**，上下两个圆角会互相
        //     压过去、画不出来，实际只能画成半高的圆角 —— 也就是胶囊；
        //   * 手机 411dp/d2.75：高 51.4dp → 14dp 只有半高的 54%，看着就是"方"的。
        // 于是"手表上圆、手机上方"——用户原话："手机上的设置键、+ 键、删除键和文件键……
        // 都比手表上要方"。percent = 50 让圆角永远等于半高，三块屏上是**同一个形状**；
        // 这和 Theme.kt 里键盘按键用 `watchShapeOf(height)` 是同一条理由
        // （那边注释原话：固定 dp 的圆角在高度变化时会比例失调）。
        //
        // 圆表观感几乎不变：换成 percent = 50 后圆角 = 29.1 / 2 = 14.55dp，
        // 比原来的 14dp 只差 **0.55dp**；这次改的是手机和方表，不是手表。
        // 顺带和 `MiniChip` / 键盘按键 / `HoldRing` 的线圈（矩形 + 两个半圆）统一到
        // 同一套"胶囊语言"上。
        shape = PillShape,
        modifier = modifier
            // 尺寸**显式**算出来：宽 = 0.18 短边（[WatchMetrics.iconButton]），
            // 高 = 宽 × [ICON_BTN_ASPECT]（= 0.18 × 0.125/0.18 = 0.125 短边）。
            //
            // 原来写的是 `Modifier.size(metrics.iconButton)`：`Modifier.size()` 会被父约束
            // **夹小**（不会溢出），工具条行高正好是 0.125 短边，于是高度被"偶然"夹成
            // 0.125 —— 和上面那个乘积恰好相等，所以一直"看起来是对的"。
            // 但这层依赖很脆：任何一处把按钮放进**比 0.125 高**的容器，它立刻变回
            // 0.18 × 0.18 的**正方形**（`SavesScreen` 那两颗就套在
            // `Modifier.size(keySide = iconButton)` 的 Box 里 —— 那个 Box 的高度现在
            // 也是被工具条行高夹成 0.125 的，行高一改，按钮和线圈的形状就一起错位）。
            // 显式写死之后，形状/比例只由这里决定，与父布局无关。
            //
            // ⚠️ **谁说了算**：`modifier` 参数写在前面 = **外层**，而 Compose 里
            // 外层的尺寸约束**先落地**（内层拿到固定约束后只能 coerce 回去），
            // 所以调用方在自己传进来的 modifier 里设了尺寸时，**以调用方为准**
            // （本函数算的宽高会被夹到调用方的值）。现状：
            //   * `FunctionEditor`（2 处）与 `PropertyEditor`（1 处）自己传了
            //     `0.16 短边 × 0.85 × 行高` → 按钮比标准尺寸更扁（约 2 : 1），
            //     但仍然是一颗胶囊，可接受，本轮不动那两个文件；
            //   * 其余调用点都不设尺寸：`FunctionList` / `FunctionsScreen` / `SavesScreen`
            //     的行高都是 0.125 短边 = 0.18 × ICON_BTN_ASPECT → **一点不被夹**，
            //     最终尺寸就是 0.18 × 0.125 短边；
            //   * `SettingsScreen` 的行高是 0.095（比 0.125 矮）→ 按钮会被继续夹矮成
            //     0.18 × 0.095（更扁的胶囊）。同样可接受：形变的是"高"，圆角按半高走，
            //     所以它还是一颗胶囊，不会变回方圆角。
            //
            // ⚠️ 不许用 `requiredSize`：它会**突破**父约束，多出来的部分被祖先裁掉
            // （项目踩过这个坑，见 HoldRing.kt 顶部注释）。
            .width(metrics.iconButton)
            .height(metrics.iconButton * ICON_BTN_ASPECT)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .then(tapModifier)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 同一层高亮：深灰底（未 filled）变亮一档，primary 底（filled）也会更亮一点，
            // 一个机制覆盖两种情况，不用为 filled 单独写配色分支。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = press.glowAlpha }
                    .background(Color.White)
            )
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = fg,
                modifier = Modifier.size(metrics.iconButton * 0.52f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 「底部浮出的胶囊」—— **toast 专属的外形**（撤销条本轮已删）
//
// ⚠️ 本轮变化：原来这一节是"toast 与撤销条共用同一种外形"，第一屏底部那条
// 2 秒「已删除 · 点此撤销」胶囊是**第二个使用者**。用户本轮要求"把所有的撤销的
// toast 提示都给删掉"，那条胶囊（连同它的 `showUndo` / `onUndo` 参数与状态）**删了**，
// 所以现在 [FloatPill] 的**唯一**使用者是 `GraphScreen` 的 toast。
// 外形与常量**一个数都没改**（下面这些说明保留着，因为它们解释的是"为什么长这样"，
// 而那几条理由是跟着命名/几何走的，不是跟着使用者数量走的）。
//
// 用户原话（上一轮）："然后 toast 提示的样式统一成撤销删除的样式，颜色用原来的颜色。
// 现在圆角太大了不好看。"
//
// 真因：toast 原来**自己撑高**（`maxLines = 3` + 上下内边距），而它的形状也是
// `PillShape`（= `RoundedCornerShape(percent = 50)`）—— 百分比圆角是**跟着高度**走的，
// 于是"文字一长 → 胶囊变高 → 圆角半径跟着变成半个高度"，看起来就是一团圆滚滚的东西。
// 那条撤销条之所以好看：它是**定高**的一行（[FLOAT_PILL_H]），百分比圆角算出来就是
// "半个行高"那一档，永远不变。
//
// 所以这一节把"浮出胶囊"抽成**唯一一处**：[FloatPill] 的形状/高度/内边距/字号/对齐
// 全部在这里定义，调用方只传**颜色**和宽度。
//
// ⚠️ 已知的"同款"：`SavesScreen.kt` 里那条「已删除 N 个 · 点此撤销」是这段代码的
// **手抄副本**（定高 0.105、PillShape、居中、fontTiny 全都一样，
// 但宽度还是"短边 × 弧宽"、位置在 `TopCenter + 0.835 短边`）。它**不归本轮可改范围**，
// 所以这一轮**没有动**它 —— 但按用户"删掉所有撤销提示"的要求，那一条同样该删：
// 最小改动方案写在报告里（删 `SavesScreen.kt` 的 3 处：`batch`/`batchShown` 状态、
// 第 822–880 行那段浮出胶囊、以及第 447 行那个 2/4 秒到期 effect）。
// ---------------------------------------------------------------------------

/**
 * 浮出胶囊的**高度**（占**短边**）—— 底部这一族胶囊唯一的那个数。
 *
 * 为什么是"占短边"而不是"跟屏宽走"：它描述的是**控件尺寸**（一行小字多高），
 * 按项目那条长期规则的例外条款，尺寸继续按 `metrics.sizeDp` 缩放；
 * 只有**宽度**和**位置**才换基准。
 *
 * 0.105 × 233dp ≈ 24.5dp：一行 `fontTiny`（≈9.8dp）上下各留 ≈7dp，
 * 正好是"胶囊里一行小字"的呼吸位。
 */
internal const val FLOAT_PILL_H = 0.105f

/**
 * 浮出胶囊的**横向内边距**（占**短边**）。
 *
 * 0.03 × 233dp ≈ 7dp：文字离两端那圈圆角留一点缝（不然长句会贴到弧上）。
 */
internal const val FLOAT_PILL_PAD_H = 0.03f

/**
 * 一句话在胶囊里大概占多少"字宽单位"（中日韩全角字符算 1、其余算 0.55）。
 *
 * 只用来决定**要不要换行 / 缩一档字号**（纯展示，不参与任何判定）：
 * 数"字符个数"会让 `已删除` 和 `abcde` 看起来一样长，而它们实际宽度差近一倍。
 */
internal fun floatPillWidthUnits(text: String): Float {
    var units = 0f
    for (c in text) {
        units += if (c.code >= 0x2E80) 1f else 0.55f
    }
    return units
}

/**
 * 这句话在胶囊里要排几行：短句一行，长句两行（**绝不更多** —— 高度是定死的）。
 *
 * 阈值 15 个"字宽单位"：圆表上胶囊内宽 ≈ `0.674 × 233 − 2 × 7 ≈ 143dp`，
 * 而 `fontTiny` 一个全角字 ≈ 9.8dp —— 15 个单位正好是"一行放得下"的边界
 * （「已删除 a(x)」这类提示 ≈ 8 个单位，稳在一行里）。
 */
internal fun floatPillLines(text: String): Int =
    if (floatPillWidthUnits(text) <= 15f) 1 else 2

/**
 * 胶囊里这句话的字号。
 *
 * 两行时缩到 `0.94 × fontTiny`：两行 `fontTiny` 的行高 ≈ 23.5dp，
 * 而胶囊内高只有 ≈ 24.5dp（定高），缩一档排得下、不贴边。
 * 一行时就是 `fontTiny` 本身。
 *
 * ⚠️ 字号**不跟着屏宽走**（它是"字多高"，属于控件尺寸那一类，按短边缩放），
 * 跟着屏宽变的是**胶囊自己的宽度**（自适应），所以长句在手机上更宽松 ——
 * 这也正是这条算式存在的意义：宁可手机上一行放得下，圆表上换两行，也不截断。
 */
internal fun floatPillFont(metrics: WatchMetrics, text: String): TextUnit =
    if (floatPillLines(text) == 1) metrics.fontTiny else metrics.fontTiny * 0.94f

/**
 * **底部浮出的胶囊**（toast 唯一的实现 —— 撤销条本轮已删，见本节开头）。
 *
 * 外形四件事是定死的（上一轮从那条撤销条上"照搬"下来的，所以两者曾经长得一模一样）：
 *
 * | 项目 | 值 |
 * |---|---|
 * | 形状 / 圆角 | [PillShape]（`RoundedCornerShape(percent = 50)`） |
 * | 高度 | [FLOAT_PILL_H]（占短边，定高 —— 圆角因此永远等于半个高度） |
 * | 内边距 | [FLOAT_PILL_PAD_H]（横向，居中排版） |
 * | 文字 | [floatPillFont] + `TextAlign.Center` + 一行（长句两行） |
 *
 * 颜色由调用方传（用户明确说"颜色用原来的颜色"）：toast 是 `KeyPalette.accent`。
 *
 * 宽度也由调用方给（走自适应那一套：`bandWidth`），这里只要求"高度用 [FLOAT_PILL_H]"。
 *
 * @param text 胶囊上那句话（`null`/空由调用方判掉，不要传进来 —— 这一层不做空态）。
 * @param container 底色（toast = 强调色）。
 * @param onClick 非 null 时整条可点。**toast 一律传 null** —— 用户本轮要求
 *   "所有的撤销提示都删掉"之后，底部这条胶囊是一条**纯提示**，
 *   可点的那种（"点此撤销"）已经不存在了；留这个参数只是给将来别的可点浮层用。
 */
@Composable
internal fun FloatPill(
    text: String,
    container: Color,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val lines = floatPillLines(text)
    Surface(
        color = container,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * FLOAT_PILL_PAD_H),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = floatPillFont(metrics, text),
                // 定高胶囊里**只排一到两行**：超出的部分宁可省略号，也不许把胶囊撑高
                // （撑高 = 百分比圆角跟着变大 = 用户说的"圆角太大"）
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 数值显示。刻度、坐标这类地方要短，而且不能受系统区域设置影响
 * （某些地区用逗号做小数点，会直接毁掉数学表达式的可读性）。
 */
fun formatNumber(v: Double): String = when {
    v.isNaN() -> "—"
    !v.isFinite() -> if (v > 0) "∞" else "−∞"
    v == 0.0 -> "0"
    kotlin.math.abs(v) >= 1e5 || kotlin.math.abs(v) < 1e-4 ->
        String.format(java.util.Locale.US, "%.1e", v)
    else -> {
        val rounded = kotlin.math.round(v * 1000.0) / 1000.0
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }
}

// ---------------------------------------------------------------------------
// 顶部工具条上那一条「从一颗键滑到另一颗键」唤出撤销 / 重做（本轮新增）
//
// ## 用户要的是什么（含本轮那条**关键补充**）
//
// 原话："给这个软件加一个撤销的功能，在函数列表界面，从设置滑到加号。按键可以在设置和
// 加号那一条的地方显示出撤销菜单有左箭头和右箭头。然后在功能列表界面从文件夹滑到删除
// 也是一样的道理。从加号滑到设置和从删除滑到文件夹，也可以调出来撤销按钮。
// **注意添加丰富美观的动画**。"
//
// 补充（本轮）："**按出撤销菜单的条件是从顶部的一个按钮滑到另一个按钮，请不要给顶部的
// 按钮还没有滑到另一个按钮的时候就加动画。**"
//
// 于是本文件提供**唯一一份**实现，两屏各调一次：
//   * [FunctionListScreen]（第一屏）：工具条上是 ⚙ 与 `+`；
//   * [FunctionsScreen]（第二屏）：工具条上是 📁 与 🗑。
//
// ## 手势：判据是**几何**的，不是"拖了多远"
//
// | 项 | 值 | 为什么 |
// |---|---|---|
// | 用什么原语 | `DragGestures.awaitKeyToKeySwipe`（本轮新增） | **不能**用 `awaitOneWayDrag`：它越过触摸容差就认领并消费，而"被消费"会取消子节点（按钮 `clickable`）的点击 —— 于是"在 ⚙ 上拖了十几像素又没滑到 `+`"会变成"我点了一下设置，没反应"。新原语**在滑到另一颗键之前一个字节都不消费**，所以短按/长按全都不受影响 |
// | 起手区 | **必须落在某一颗键的矩形里**（⚙ 或 `+` / 📁 或 🗑） | 用户那句"从一个按钮滑到另一个按钮"里的"从"；落在两道键中间那道空当时**直接返回**（那一段是留给"顶部下滑回图像"的活路） |
// | 达成判据 | 手指**进入另一颗键的矩形**（`PlotMetrics.toolbarMenuCrossedToOtherKey`） | 用户那句"越过两颗键之间的距离、进入另一颗键所在的区域"；**没有**任何距离阈值常量 —— 最短行程就是那道空当（圆表 37.3dp / 方表 32.0dp / 手机 238.4dp），几何本身卡住了"随便拖一点" |
// | 什么时候触发 | **手指进入另一颗键的那一刻**（手指还按着就展开） | 用户倾向这个（"反馈更直接"）；而且它是**位置**判定，不是"松手时算总账"，所以不会出现"到了又走了、松手却没反应" |
// | 落点消费 | **达成前完全不消费** | 保证 ⚙/`+`/📁/🗑 的短按、长按（线圈）、以及"从 ⚙ 按住 0.9 秒进应用信息页"在没跨到另一颗键时**逐字照旧** |
// | 达成后 | 每帧 `consume()` | 这一次手势归工具条：滑到 `+` 上松手**不会**开编辑器、滑到 🗑 上松手**不会**清屏 |
// | 一次手势几次切换 | **最多一次**（内部闩） | 见下面"滑过去又滑回来"那一段 |
//
// ## 为什么"达成之前一帧动画都没有"（用户那条要求的落点）
//
// 新原语**不报告任何进度**：它没有 `onDrag(total)` 那样的回调，只有一次 `onCross()`。
// 而菜单的**全部**可见变化（本体 alpha/缩放、两端键的让位、抓手淡出）都只由
// [ToolbarUndoMenu.open]（一个 `Boolean`）驱动 —— 展开动画是"跨过去之后整段播"，
// 不是"拖多少展开多少"。
// 所以"还没滑到另一颗键"这一路上**没有任何可画的中间态**：不淡入、不半开、
// 不预先位移、不缩放。这是**结构性**的，不是靠调阈值调出来的。
//
// ## "滑过去又滑回来但没松手"会怎样（一次手势最多切换一次）
//
// 手指进入另一颗键的那一刻菜单就展开（并开始播动画）；此后手指**退回**原来那颗键上方、
// 甚至继续来回晃，菜单**保持展开**，不会跟着翻。松手也一样（不会翻转）。
// 理由：用户嫌的正是"抽搐"；跟着手指在边界上来回切就是另一种抽搐。所以
// "一次手势 = 一次决定"，要撤回就**再滑一下**（同一个手势是切换）。
// ⚠️ 副作用一条（诚实写在这里）：这一次横滑**已经归了工具条**，所以
// "滑到 `+` 又退回 ⚙ 上松手"不会触发 ⚙ 的点击 —— 但也不会触发别的任何东西。
//
// ## 动画清单（全部只动 `graphicsLayer` / 颜色，布局一帧都不抖）
//
// | 元素 | 动的量 | 曲线 / 时长 |
// |---|---|---|
// | 撤销菜单本体（两颗箭头键那一行） | alpha 0→1、scaleX 0.30→1.00、scaleY 0.60→1.00 | `tween(220, FastOutSlowInEasing)` |
// | 两端的键（⚙/`+`、📁/🗑） | alpha 1→0.18、scale 1→0.82、横向朝屏幕外让 4% 行宽 | 同一个 Animatable（同一条 220ms 曲线） |
// | 工具条上方那道细横线（抓手） | alpha 1→0 | 同上（见 [toolbarMenuHandleAlpha]） |
// | 箭头键"可用 ↔ 变暗" | 底色 / 图标色渐变 | `tween(140, FastOutSlowInEasing)` |
// | 收起 | 全部反向，且更快 | `tween(140, FastOutSlowInEasing)` |
//
// 缓动与时长全部沿用项目语言（`tween` + `FastOutSlowInEasing`、120~260ms）；
// "错开 20~40ms"这一条在这里**不需要**：两颗箭头键是**一起**从中间长出来的，
// 错开反而会让"展开"看起来像两块东西拼起来（错开留给的是列表行入场那种
// "依次到位"的场合，见 `RowEntrance` / `ToolSectionBlock`）。
//
// ## 什么时候收起（用户让我自己判断，这里是最终决定）
//
//   1. **再滑一次**（再从一颗键滑到另一颗键）→ 收起（同一个手势是切换）；
//   2. **展开之后 4 秒没有任何动作** → 自己收起（每次点箭头都会重新计时）；
//   3. **离开这一屏**（路由一变，这一屏的组合被丢掉，菜单状态自然跟着消失）；
//   4. **点别的地方**：不加"全屏遮罩"——这一条是**有意的取舍**。项目里调色板那种
//      模态浮层配了一层全屏透明遮罩（点空白处收起），但撤销菜单**不是模态的**：
//      它只占工具条那一条，下面整个列表照常能滚、能点。如果给它加遮罩，
//      用户误滑出来之后**列表会先吃掉一次点击**（第一下只是"关掉菜单"），
//      那种"点了没反应"正是本项目最忌讳的体验。所以：点行 / 点 ⚙ / 点 `+`
//      全都照常生效（它们各自会换屏或改状态，菜单要么跟着这一屏消失、
//      要么在 4 秒后自己收）。
//
// ⚠️ 收起动画播完之前，菜单**仍然在组合里**（否则没有退场动画），
// 但这期间两颗箭头键还按得动 —— 这是可接受的（140ms，而且按下去真的会撤销）。
// 收起动画一结束（[ToolbarUndoMenu.present] 变 false）就**不再组合**，
// 于是工具条中间不会留下一对"看不见但点得到"的键（那才是真 bug：
// 点一下空白处结果撤销了一步，用户完全无法理解）。
// ---------------------------------------------------------------------------

/** 撤销菜单展开时长（ms）：和列表行入场（220）、调色板（210）同一个量级。 */
private const val TOOLBAR_MENU_IN_MS = 220

/** 收起时长（ms）：比展开快一档 —— 项目里所有"退场"都比"入场"短（不拖沓）。 */
private const val TOOLBAR_MENU_OUT_MS = 140

/** 展开之后多久没人动就自己收起（ms）。4 秒：够连按三四次撤销，又不至于一直杵在那。 */
private const val TOOLBAR_MENU_AUTO_MS = 4000L

/** 箭头键"可用 ↔ 变暗"渐变的时长（ms）。 */
private const val TOOLBAR_MENU_KEY_MS = 140

/**
 * 「从一颗键滑到另一颗键唤出撤销 / 重做」的全部状态 —— 两屏共用（[rememberToolbarUndoMenu] 造）。
 *
 * 它是**每次组合重新构造的一个值对象**（不是长期持有的状态机），
 * 真正的状态（展开与否、动画值、手势）都在 [rememberToolbarUndoMenu] 的 `remember` 里。
 */
@Stable
internal class ToolbarUndoMenu internal constructor(
    /**
     * 菜单**要不要组合** = 展开中、或者收起动画还没播完。
     *
     * 为什么需要它：菜单收起之后那两颗箭头键必须从组合里**拿掉** ——
     * 否则它们虽然 `alpha = 0`，命中测试照样吃点击（`graphicsLayer` 的 alpha
     * 不参与命中判定），工具条中间就会出现一对"看不见但点得到"的键。
     */
    val present: Boolean,
    /** 目标态：true = 应该展开（箭头键可见）。**它是唯一的动画驱动源**。 */
    val open: Boolean,
    /** 展开进度 0..1（动画值 —— **只该在 `graphicsLayer` 里读**）。 */
    val progress: State<Float>,
    /** 挂在工具条那一条上的手势（从一颗键滑到另一颗键 = 切换菜单）。 */
    val gesture: Modifier,
    /** 立刻收起（点箭头之后、或者想给别的地方让位时用）。 */
    val close: () -> Unit,
    /** 记一次"用户还在用这个菜单"——重新开始那 4 秒的自动收起计时。 */
    val noteInteraction: () -> Unit
)

/**
 * 造一份工具条撤销菜单的状态（两屏各调一次，实现只有这一份）。
 *
 * @param metrics 只用来取**单颗图标键的真实宽度**（[WatchMetrics.iconButton]，
 *   也就是 ⚙/`+`/📁/🗑 那两颗键的实际绘制宽度）—— "两颗键在工具条上的位置"靠它推，
 *   行宽则由手势节点**自己量到的** `size.width` 给（见 `awaitKeyToKeySwipe`）。
 *   所以这套几何在三块屏上都成立：圆屏行宽是弧宽、非圆屏是"屏宽 − 左右各 3%"。
 */
@Composable
internal fun rememberToolbarUndoMenu(metrics: WatchMetrics): ToolbarUndoMenu {
    // `mutableStateOf` 而不是 `by`：手势协程里要读写它，而手势块是用
    // `remember(keyWidthPx)` 建一次的 —— 它捕获的必须是**稳定对象**（状态本身），
    // 不能是每一帧重新装箱的那个值（那是"闭包吃旧值"的经典坑，
    // 本项目在 `GraphScreen` 的 `pointerInput(Unit)` 上踩过一次）。
    val openState = remember { mutableStateOf(false) }
    var present by remember { mutableStateOf(false) }
    val interaction = remember { mutableStateOf(0) }
    val anim = remember { Animatable(0f) }
    // 单颗键的真实宽度（px）。它是手势判据的**唯一外部输入**：行宽由节点自己量。
    val keyWidthPx = with(LocalDensity.current) { metrics.iconButton.toPx() }

    // ---- 展开 / 收起：一个 Animatable 驱动三个派生量（宽 / 高 / 透明度）----
    // 收起时**不立刻**把 present 置 false：先让动画跑完，再摘掉（才有退场动画）。
    LaunchedEffect(openState.value) {
        if (openState.value) {
            present = true
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = TOOLBAR_MENU_IN_MS, easing = FastOutSlowInEasing)
            )
        } else {
            anim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = TOOLBAR_MENU_OUT_MS, easing = FastOutSlowInEasing)
            )
            present = false
        }
    }

    // ---- 自动收起（见文件里"什么时候收起"那一节）----
    // key 里带 `interaction`：每点一次箭头都会重新开始计时 ——
    // 用户在连按撤销的时候，菜单不会在他按到第三下时突然消失。
    LaunchedEffect(openState.value, interaction.value) {
        if (openState.value) {
            delay(TOOLBAR_MENU_AUTO_MS)
            openState.value = false
        }
    }

    // ---- 手势：**从一颗键滑到另一颗键** = 切换菜单 ----
    //
    // ⚠️ 用的是本轮新写的 `DragGestures.awaitKeyToKeySwipe`，**不是** `awaitOneWayDrag`：
    // 判据是"手指进了另一颗键的矩形"（位置），而不是"拖了多远"（距离），
    // 而且**达成之前一个字节都不消费** —— 那一条正是"没跨到另一颗键时，
    // ⚙/`+`/📁/🗑 的短按与长按必须照旧工作"的保证（含"从 ⚙ 上按住 0.9 秒进应用信息页"）。
    // 详细理由写在那个原语的 KDoc 与本节文件头那张表里。
    val gesture = remember(keyWidthPx) {
        Modifier.pointerInput(keyWidthPx) {
            awaitEachGesture {
                awaitKeyToKeySwipe(
                    keyWidthPx = keyWidthPx,
                    // 达成的那一刻（手指**还按着**就展开，用户要的直接反馈）；
                    // 一次手势最多到这里一次（内部有闩），往回退不会再切回去。
                    onCross = { openState.value = !openState.value }
                )
            }
        }
    }

    return ToolbarUndoMenu(
        present = present,
        open = openState.value,
        progress = anim.asState(),
        gesture = gesture,
        close = { openState.value = false },
        noteInteraction = { interaction.value++ }
    )
}

/**
 * 工具条上一颗"要让位"的键（⚙ / `+` / 📁 / 🗑）在菜单展开时该加的那一层。
 *
 * 三个量，**全部只动图层**（键的位置、尺寸、命中区一个像素都不动 ——
 * 这是项目里 `graphicsLayer` 那两条铁律之一，见本文件开头）：
 *   1. 暗到 [TOOLBAR_MENU_END_KEY_ALPHA]（0.18）—— "退到后面去"；
 *   2. 缩到 [TOOLBAR_MENU_END_KEY_SCALE]（0.82）；
 *   3. 朝屏幕外让开 4% 行宽（[toolbarMenuEndKeyShiftPx]），把中间让给撤销菜单。
 *
 * 为什么"让位"而不是"移走/藏起来"：它们的位置就是用户记住的位置 ——
 * 菜单开着的时候点那颗位置，仍然应该是设置（⚙）/ 函数文件（📁），不该点空。
 *
 * ⚠️ 传进 `HoldToConfirm(modifier = …)` 而不是套一层 `Box`：
 * 长按的**线圈**画在 `HoldToConfirm` 自己那个 Box 里（和按钮是兄弟），
 * 套在外面的图层**罩不住线圈** —— 那会出现"按键暗下去了、线圈还亮着"。
 * 传成它的 `modifier` 则两者一起动，而且不多一个布局节点（圆屏逐像素不变）。
 *
 * @param progress 展开进度（**在 `graphicsLayer` 里读**，所以收的是 `State`）。
 * @param toRight 这颗键在行的**右边**吗（`+` / 🗑 = true，⚙ / 📁 = false）。
 * @param rowWidthPx 工具条行宽（px）—— "让开多少"按它的比例算。
 */
internal fun Modifier.toolbarYield(
    progress: State<Float>,
    toRight: Boolean,
    rowWidthPx: Float
): Modifier = graphicsLayer {
    val p = progress.value
    alpha = toolbarMenuEndKeyAlpha(p)
    val s = toolbarMenuEndKeyScale(p)
    scaleX = s
    scaleY = s
    translationX = toolbarMenuEndKeyShiftPx(rowWidthPx, p, toRight)
}

/**
 * 撤销 / 重做菜单本体：工具条**正中间**的一对箭头键。
 *
 * ⚠️ 它**只占那两颗键那么大的一块**（调用方传 `Modifier.align(Alignment.Center)`），
 * 而不是"铺满整条工具条"：铺满的那种写法虽然也不会吃掉事件（一个没有
 * `pointerInput` 的 `Box` 不是命中目标），但"菜单收起动画还在播"的那 140ms 里
 * 它仍然是组合着的 —— 尺寸正好等于两颗键，才**结构性地**保证两端的 ⚙ / `+` /
 * 📁 / 🗑 在任何时刻都不会被它挡到。
 *
 * 布局上它是叠在工具条那一条上的（父 Box 的中央），所以不占任何新地方 ——
 * 工具条行、下面列表的上沿在动画期间一帧都不动。
 *
 * @param menu [rememberToolbarUndoMenu] 造出来的那一份（只读 [ToolbarUndoMenu.present] /
 *   [ToolbarUndoMenu.progress]）。
 * @param canUndo / [canRedo] 栈里还有东西吗：false → 对应的键**变暗**。
 *   变暗的键**仍然可点**（点它弹一句「没有可撤销的操作」）——
 *   这比"点了完全没反应"友好得多，用户至少知道"这个键是干什么的、现在为什么没用"。
 * @param onUndo / [onRedo] 按下去的动作（界面层负责弹 toast）。
 * @param modifier 调用方给的位置修饰符（两屏都传 `Modifier.align(Alignment.Center)`）。
 */
@Composable
internal fun ToolbarUndoRedoMenu(
    menu: ToolbarUndoMenu,
    metrics: WatchMetrics,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!menu.present) return
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.graphicsLayer {
                val p = menu.progress.value
                alpha = toolbarMenuPanelAlpha(p)
                scaleX = toolbarMenuPanelScaleX(p)
                scaleY = toolbarMenuPanelScaleY(p)
            },
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * TOOLBAR_MENU_GAP),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarArrowKey(
                undo = true,
                enabled = canUndo,
                metrics = metrics,
                onClick = {
                    // 先"记一次动作"（重新开始自动收起计时），再交出去干活 ——
                    // 顺序反了的话，撤销刚好把这一屏换掉时这次计时就白记了。
                    menu.noteInteraction()
                    onUndo()
                }
            )
            ToolbarArrowKey(
                undo = false,
                enabled = canRedo,
                metrics = metrics,
                onClick = {
                    menu.noteInteraction()
                    onRedo()
                }
            )
        }
    }
}

/**
 * 一颗箭头键（← 撤销 / → 重做）。
 *
 * 尺寸、形状、按压反馈全部走 [WatchIconButton]（项目里"形状锁定的图标键"那一条：
 * 宽 = 0.18 × 短边、高 = 1.44:1 的胶囊，三种屏上是同一个形状），
 * 所以这两颗键的宽度**不是**新写的一个数 —— 它和 ⚙/`+`/📁/🗑 是同一套。
 *
 * 图标用 `Icons.AutoMirrored.Filled.ArrowBack` / `ArrowForward`
 * （material-icons-**core** 里就有，不引 `material-icons-extended` ——
 * 那个包会把 APK 撑大好几 MB，README 里写着这条）。
 */
@Composable
private fun ToolbarArrowKey(
    undo: Boolean,
    enabled: Boolean,
    metrics: WatchMetrics,
    onClick: () -> Unit
) {
    // "可用 ↔ 变暗"之间**渐变**（140ms）：撤到栈底那一下是慢慢暗下去的，
    // 不是啪一下换色 —— 和清屏键的 dangerAlpha 渐变是同一个语言。
    val lit by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(durationMillis = TOOLBAR_MENU_KEY_MS, easing = FastOutSlowInEasing)
    )
    WatchIconButton(
        icon = if (undo) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = if (undo) "撤销" else "重做",
        metrics = metrics,
        // 可用 = 强调蓝的暗档（和 `+` 键同色：它也是"主操作"级别的键）；
        // 不可用 = 深灰再压暗一半（看得出是"同一个键、现在没用"）。
        containerColor = lerp(
            KeyPalette.normal.copy(alpha = 0.45f),
            KeyPalette.accentDeep,
            lit
        ),
        contentColor = lerp(KeyPalette.textMuted, KeyPalette.text, lit),
        onClick = onClick
    )
}

// ---------------------------------------------------------------------------
// 「长按必须留在控件自己的矩形里」（本轮，全局性要求）
//
// 用户原话："**还有把所有按钮的长按判定都改成只有在按钮的矩形框内才判定。**"
// 手指一旦滑出这个控件自己的布局矩形（哪怕只是飘出去）→ **长按作废**：
// 进度/线圈/闪光当场复位，抬手也不再触发那一次长按。
//
// ## 为什么要有这么一个"观察者"（而不是把 combinedClickable 全换掉）
//
// 项目里四种长按实现，分两类：
//
// | 实现 | 出界会不会取消 | 处理 |
// |---|---|---|
// | `combinedClickable(onLongClick=…)` / `detectTapGestures(onLongPress=…)`（AndroidX） | **会**，但它的边界是"节点矩形 **+ 系统的最小触控目标补白**（`extendedTouchPadding`）"—— 小控件上这个补白能到 **10dp 以上**（48dp 最小触控目标的一半减去控件半径），比用户要的"1~3px 容差"大得多 | 本文件这个**观察者**把它收紧到"节点矩形 + [PRESS_INSIDE_SLACK_FRACTION]" |
// | 项目自己手写的长按（`HoldToConfirm`、`SliderRow` 整行） | **不会**（它们本来只等抬手） | 各自在手势里加一句 [pressInsideRect] 判定（见那两个文件） |
//
// 具体到 AndroidX 那一类：**不去替换** `combinedClickable`（它同时负责涟漪、按下反馈、
// "长按之后不再补一次单击"、无障碍语义，换掉任何一个都可能带来新的回归），
// 而是在**同一个节点**上再挂一个 `pointerInput`：它**只观察手指位置、一个字节都不消费**
// （`awaitFirstDown(requireUnconsumed = false)` + 从不 `consume()`），
// 把"这一串手势有没有出界"记在一个 `MutableState` 里；长按回调在真正执行动作之前
// 问一句 [PressInsideGuard.stayedInside]。
//
// 于是三层语义各归各位：
//   * **短按**：完全不动（连出界后的行为也照旧 —— AndroidX 那套本来就是它的语义）；
//   * **长按**：出界即作废（本项目新增的判据）；
//   * **观察者本身**：零消费、零布局影响（`pointerInput` 不改变尺寸），
//     所以"从 ⚙ 滑到 `+`"那条撤销菜单手势、行左滑删除、滑轨拖值**都不受影响**。
// ---------------------------------------------------------------------------

/**
 * 「这一串按下手指有没有离开过控件自己的矩形」—— 给 [Modifier.pressInsideGuard] 与
 * 长按回调共用的一格状态。
 *
 * 为什么要用 `MutableState` 而不是普通 `Boolean`：长按回调是在**手势协程**里被调用的
 * （不是在组合期），它要读到的是"**此刻**"的值；而 `mutableStateOf` 的读写在任何线程/协程
 * 里都拿到最新值，不需要额外的同步。（它不参与任何重组 —— 组合期没人读它。）
 */
@Stable
internal class PressInsideGuard internal constructor() {
    internal val state = mutableStateOf(true)

    /**
     * true = 这一串按下**始终**在控件矩形（含极小容差）里。
     *
     * 一旦出界就置 false，而且**这一串里不会再变回 true**（"手指一旦滑出…长按就作废"，
     * 不允许"出去又回来"复活那一次长按）。新手势按下时由 [Modifier.pressInsideGuard] 重置。
     */
    val stayedInside: Boolean get() = state.value
}

/**
 * 记住一个 [PressInsideGuard]。
 *
 * @param key 换一个控件就换一格状态（行 key / 控件身份）。不传 key 也能用，
 *   但列表里**一定要传行的身份** —— 否则行被回收重建时那一格状态会跟着别行跑。
 */
@Composable
internal fun rememberPressInsideGuard(key: Any? = Unit): PressInsideGuard =
    remember(key) { PressInsideGuard() }

/**
 * 只观察"手指有没有离开本节点矩形"的修饰符（**零消费、零布局影响**）。
 *
 * 用法：挂在和长按同一个节点的 modifier 链上，然后在长按回调里问一句
 * `if (guard.stayedInside) { …真正的动作… }`。
 *
 * ⚠️ 顺序无所谓（它**从不消费**事件，所以放在 `combinedClickable` 之前或之后都一样）；
 * ⚠️ 它读的是**本节点**的实测尺寸（`size`）—— 和长按所属的那个控件是同一个节点，
 *   所以"出界"永远是相对那个控件自己的矩形说的。
 */
internal fun Modifier.pressInsideGuard(guard: PressInsideGuard): Modifier = pointerInput(guard) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 新手势：先当作"还在里面"（真正的边界每一帧都由实测尺寸重算）
        guard.state.value = true
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!pressInsideRect(
                    xPx = change.position.x,
                    yPx = change.position.y,
                    widthPx = size.width.toFloat(),
                    heightPx = size.height.toFloat()
                )
            ) {
                // 出界即作废（不再变回 true），并且**只观察、不消费**：
                // 这一次手势接下来归谁还是归谁（点击的取消是 AndroidX 自己的事）。
                guard.state.value = false
                break
            }
            if (change.changedToUpIgnoreConsumed()) break
        }
    }
}

