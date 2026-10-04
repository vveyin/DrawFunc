// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.functiondrawer.watch.ui.theme.PillShape

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

    val tapModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = press.source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
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
// 「底部浮出的胶囊」—— **toast 与撤销条共用同一种外形**
//
// 用户原话（本轮）："然后 toast 提示的样式统一成撤销删除的样式，颜色用原来的颜色。
// 现在圆角太大了不好看。"
//
// 真因：toast 原来**自己撑高**（`maxLines = 3` + 上下内边距），而它的形状也是
// `PillShape`（= `RoundedCornerShape(percent = 50)`）—— 百分比圆角是**跟着高度**走的，
// 于是"文字一长 → 胶囊变高 → 圆角半径跟着变成半个高度"，看起来就是一团圆滚滚的东西。
// 撤销条之所以好看：它是**定高**的一行（[FLOAT_PILL_H]），百分比圆角算出来就是
// "半个行高"那一档，永远不变。
//
// 所以这一节把"浮出胶囊"抽成**唯一一处**：
//   * [FloatPill] 形状/高度/内边距/字号/对齐全部在这里定义；
//   * 两个使用者（`GraphScreen` 的 toast、`FunctionList` 的撤销条）只传**颜色**和宽度 ——
//     颜色按用户要求各用各的（toast = `KeyPalette.accent`、撤销条 = `KeyPalette.danger`）。
//
// ⚠️ 已知的第三份"同款"：`SavesScreen.kt` 里那条「已删除 N 个 · 点此撤销」
// 是这段代码的**手抄副本**（定高 0.105、PillShape、居中、fontTiny 全都一样，
// 但宽度还是"短边 × 弧宽"、位置在 `TopCenter + 0.835 短边`）。本轮**没有**动它
// （那个文件不在本轮的可改范围内）—— 收敛办法写在报告里：把它也换成 [FloatPill]，
// 位置与宽度跟列表那条对齐即可。
// ---------------------------------------------------------------------------

/**
 * 浮出胶囊的**高度**（占**短边**）—— toast 与撤销条共用的唯一一个数。
 *
 * 为什么是"占短边"而不是"跟屏宽走"：它描述的是**控件尺寸**（一行小字多高），
 * 按项目那条长期规则的例外条款，尺寸继续按 `metrics.sizeDp` 缩放；
 * 只有**宽度**和**位置**才换基准。
 *
 * 0.105 × 233dp ≈ 24.5dp：一行 `fontTiny`（≈9.8dp）上下各留 ≈7dp，
 * 正好是"胶囊里一行小字"的呼吸位（撤销条从上一轮起就是这个数，本函数只是把它变成共享的）。
 */
internal const val FLOAT_PILL_H = 0.105f

/**
 * 浮出胶囊的**横向内边距**（占**短边**）。
 *
 * 0.03 × 233dp ≈ 7dp：文字离两端那圈圆角留一点缝（不然长句会贴到弧上）。
 * 撤销条那句只有 9 个字、居中，所以这个内边距在它身上**看不出来** ——
 * 也就是说加它不会改变撤销条的观感，只是让 toast 的长句也有同样的待遇。
 */
internal const val FLOAT_PILL_PAD_H = 0.03f

/**
 * 撤销条**离屏幕底边的距离**（占**屏宽**）—— 用 `geometry.sx(...)` 换算。
 *
 * 为什么占屏宽：这一条位置语言和"屏边留白"同源（圆表 0.06 × 233dp ≈ 14dp，
 * 正好是它原来那个"0.165 屏高"）。它同时是 `GraphScreen` 里"toast 让开撤销条"
 * 那条算式的输入 —— 两处必须读**同一个常量**，否则让位算出来会差一截。
 */
internal const val FLOAT_PILL_BOTTOM_PAD = 0.06f

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
 * （撤销条那句 `已删除 · 点此撤销` ≈ 10 个单位，稳在一行里）。
 */
internal fun floatPillLines(text: String): Int =
    if (floatPillWidthUnits(text) <= 15f) 1 else 2

/**
 * 胶囊里这句话的字号。
 *
 * 两行时缩到 `0.94 × fontTiny`：两行 `fontTiny` 的行高 ≈ 23.5dp，
 * 而胶囊内高只有 ≈ 24.5dp（定高），缩一档排得下、不贴边。
 * 一行时就是 `fontTiny` 本身 —— **和撤销条逐像素同一个字号**。
 *
 * ⚠️ 字号**不跟着屏宽走**（它是"字多高"，属于控件尺寸那一类，按短边缩放），
 * 跟着屏宽变的是**胶囊自己的宽度**（自适应），所以长句在手机上更宽松 ——
 * 这也正是这条算式存在的意义：宁可手机上一行放得下，圆表上换两行，也不截断。
 */
internal fun floatPillFont(metrics: WatchMetrics, text: String): TextUnit =
    if (floatPillLines(text) == 1) metrics.fontTiny else metrics.fontTiny * 0.94f

/**
 * **底部浮出的胶囊**（toast 与撤销条唯一的实现）。
 *
 * 外形四件事**全部照搬撤销条**（用户要求"统一成撤销删除的样式"）：
 *
 * | 项目 | 值 | 来源 |
 * |---|---|---|
 * | 形状 / 圆角 | [PillShape]（`RoundedCornerShape(percent = 50)`） | 撤销条用的就是它，两处**同一个常量** |
 * | 高度 | [FLOAT_PILL_H]（占短边，定高 —— 圆角因此永远等于半个高度） | 同上 |
 * | 内边距 | [FLOAT_PILL_PAD_H]（横向，居中排版） | 同上 |
 * | 文字 | [floatPillFont] + `TextAlign.Center` + 一行（长句两行） | 同上（撤销条那句永远是一行 `fontTiny`） |
 *
 * 颜色**不共用**：调用方各自传（用户明确说"颜色用原来的颜色"）——
 * toast 是 `KeyPalette.accent`、撤销条是 `KeyPalette.danger`。
 *
 * 宽度也由调用方给（都走自适应那一套：`bandWidth` / `geometry.rowWidth`），
 * 这里只要求"高度用 [FLOAT_PILL_H]"，这样它与撤销条的圆角才是同一个数。
 *
 * @param text 胶囊上那句话（`null`/空由调用方判掉，不要传进来 —— 这一层不做空态）。
 * @param container 底色。**toast 保持原来的强调色、撤销条保持砖红**。
 * @param onClick 非 null 时整条可点（撤销条就是这样）；toast 传 null（它只是提示）。
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
