// 同上：手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * 手表尺寸自适应。
 *
 * 为什么不能直接用固定 dp：
 *   国产"原屏"手表汇报的 density 极不统一 —— 同一块 466×466 的屏，
 *   有的报 density 2.0（=233dp 逻辑宽），有的报 1.0（=466dp）。
 *   前者按 48dp 的 MD3 控件做出来刚好，后者会小到几乎点不中。
 *
 * 所以这里的一切尺寸都按**屏占比**算：先量出可用边长，
 * 再乘以比例系数。这样无论 density 怎么报，观感都一致。
 *
 * 参考基准：233dp / 466px 的圆屏。
 */
@Immutable
class WatchMetrics(
    /** 可用区域的短边（dp） */
    val sizeDp: Dp,
    /** 可用区域的短边（像素） */
    val sizePx: Float,
    private val density: Density
) {

    private fun frac(f: Float): Dp = sizeDp * f

    /** 像素转 sp：按屏占比算字号，同时把用户的字体缩放也算进去。 */
    private fun sp(f: Float): TextUnit = with(density) { (sizePx * f).toSp() }

    // ---- 圆屏安全区 ----
    /** 圆屏四角是看不见的，内容必须往内收。 */
    val edgePad: Dp = frac(0.11f)

    /** 普通内容边距。 */
    val contentPad: Dp = frac(0.055f)

    // ---- 控件尺寸 ----
    val fab: Dp = frac(0.21f)

    /** 工具条图标按钮的**宽**（占可用短边）。高 = 宽 × [ICON_BTN_ASPECT]（见那个常量）。 */
    val iconButton: Dp = frac(0.18f)
    val chipHeight: Dp = frac(0.13f)
    val rowHeight: Dp = frac(0.155f)
    val keyHeight: Dp = frac(0.135f)
    val keyGap: Dp = frac(0.016f)
    val dot: Dp = frac(0.05f)
    val caretWidth: Dp = frac(0.011f)

    // ---- 线宽（像素）----
    val strokeGrid: Float = sizePx * 0.0045f
    val strokeAxis: Float = sizePx * 0.0075f
    val strokeCurve: Float = sizePx * 0.013f

    // ---- 字号 ----
    val fontTiny: TextUnit = sp(0.042f)
    val fontSmall: TextUnit = sp(0.052f)
    val fontBody: TextUnit = sp(0.062f)
    val fontTitle: TextUnit = sp(0.075f)
    val fontKey: TextUnit = sp(0.070f)
    val fontExpr: TextUnit = sp(0.075f)

    /**
     * 圆屏在某个高度处的可用宽度，占屏宽的比例（0~1）。
     *
     * [yFraction] 是该高度在屏幕上的位置（0=顶部，1=底部）。
     *
     * 这是圆屏布局的核心工具：表盘是个圆，所以越靠上／下的位置，
     * 能用的水平空间越少。所有靠近顶/底的控件（尤其键盘两端的行）
     * 都必须按这个宽度收窄，否则会被表壳物理切掉。
     */
    fun circleWidthFraction(yFraction: Float): Float {
        val d = ((yFraction - 0.5f) / 0.5f).coerceIn(-1f, 1f)
        val inside = 1f - d * d
        return if (inside <= 0f) 0f else sqrt(inside)
    }
}

// ---------------------------------------------------------------------------
// 工具条图标按钮的**高宽比**（唯一来源）
// ---------------------------------------------------------------------------

/**
 * 工具条图标按钮的高宽比 = 工具条**行高比例** ÷ 按钮**宽比例** = `0.125 / 0.18` ≈ 0.6944
 * （也就是宽 : 高 ≈ 1.44 : 1）。
 *
 * **为什么它是个与屏形无关的常数**：两项都按"同一个可用短边"算 ——
 * 按钮宽 = [WatchMetrics.iconButton] = `0.18 × 短边`，工具条行高 = `0.125 × 短边`
 * （各屏各自那份 `private const val TOOLBAR_H = 0.125f`：`FunctionList` /
 * `FunctionsScreen` / `SavesScreen`；`SettingsScreen` 是 0.095、`PropertyEditor`
 * 是 0.092、`FunctionEditor` 的 `TOP_H` 是 0.092）—— 短边一约分就没了。
 * 所以圆表（短边 233dp）、方表（200dp）、手机（411dp）上这个比值**本来就是同一个数**，
 * 变的只是它乘的那个短边。
 *
 * **为什么必须把它抽出来当一个显式常量**：`WatchIconButton` 原来写
 * `Modifier.size(metrics.iconButton)`，高度是**被父布局的行高偶然夹出来的**
 * （`Modifier.size()` 会被父约束夹小、不会溢出：行高 0.125 < 0.18，于是实际高度成了
 * 0.125 —— 恰好等于这个乘积，所以一直"看起来是对的"）。这层偶然有两处会翻车：
 *   1. 只要把按钮放进一个**比 0.125 短边更高**的容器（`SavesScreen` 那两颗套在
 *      `Modifier.size(keySide = iconButton)` 的 Box 里，那个 Box 的高度现在也是被工具条
 *      行高夹成 0.125 的），按钮就变回 `0.18 × 0.18` 的**正方形** —— 同一颗按钮在不同
 *      容器里形状不同；
 *   2. 长按线圈（`HoldRing.kt`）是按 `sidePx = metrics.iconButton` 这个"边长"画的，
 *      按钮一旦不是 `0.18 × 0.125`，线圈就不再与按键同比例。
 * 所以尺寸改成显式算（见 `WatchIconButton`），而这个常量是"按钮形状"和"线圈形状"
 * 两边共同的来源。改 [WatchMetrics.iconButton] 的 0.18 时，这里要一起动。
 */
const val ICON_BTN_ASPECT = 0.125f / 0.18f

val LocalWatchMetrics = staticCompositionLocalOf<WatchMetrics> {
    error("LocalWatchMetrics 没有提供 —— 请把界面放在 WatchMetricsProvider 里面")
}

/** 兜底默认值，主要给 @Preview 用。 */
fun defaultMetrics(): WatchMetrics =
    WatchMetrics(sizeDp = 233.dp, sizePx = 466f, density = Density(2f, 1f))
