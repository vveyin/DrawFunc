// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
// ExperimentalFoundationApi 是给按键和 ⌫ 的 combinedClickable（长按）用的。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.ui.keyboard.KEYBOARD_COLUMNS
import com.functiondrawer.watch.ui.keyboard.KEYBOARD_ROWS
import com.functiondrawer.watch.ui.keyboard.KeyAction
import com.functiondrawer.watch.ui.keyboard.KeyPage
import com.functiondrawer.watch.ui.keyboard.KeySpec
import com.functiondrawer.watch.ui.keyboard.KeyTone
import com.functiondrawer.watch.ui.keyboard.applyVariantSelection
import com.functiondrawer.watch.ui.keyboard.currentVariantIndex
import com.functiondrawer.watch.ui.keyboard.keyboardRows
import com.functiondrawer.watch.ui.keyboard.resolvedKey
import com.functiondrawer.watch.ui.keyboard.variantsFor
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 长按变体气泡的常数。
//
// 照 Culculator 的 FunctionBubbleView：220ms 的出现/收起动画。
// 位置在真机上定过一轮：气泡现在**盖在输入框上面**（见 MathKeyboard 的
// fieldCenterFraction 参数的说明），不再浮在网格上方那片空白里。
// ---------------------------------------------------------------------------

/** 气泡出现 / 收起的动画时长（毫秒）。 */
private const val BUBBLE_ANIM_MS = 220

/** 出现动画的插值：略微过冲，对应 Culculator 的 `OvershootInterpolator(1.4)`。 */
private val BubbleAppear = CubicBezierEasing(0.22f, 1.40f, 0.48f, 1f)

/** 收起动画的插值：减速，对应 Culculator 的 `DecelerateInterpolator`。 */
private val BubbleDismiss = LinearOutSlowInEasing

/**
 * 正在显示的长按变体气泡。
 *
 * [baseLabel] 是**基键**的 label（网格里给出的那个），不是它此刻显示的变体名：
 * 用户可能已经把 sin 换成了 asinh，再长按它要能换回 sin，
 * 所以必须记住"这个键原本是哪个"，[variantsFor] 也只认基键。
 *
 * [currentLabel] 是这个键此刻实际代表的名字，用来高亮气泡里的一项。
 */
private data class BubbleState(
    val baseLabel: String,
    val variants: List<KeySpec>,
    val currentLabel: String
)

// ---------------------------------------------------------------------------
// 切换选择栏时按键网格的过渡（真机反馈："在选择栏切换下面的键盘也加一个丰富美观的动画"）。
//
// 200ms 落在真机反馈要的 180~240ms 区间里：比这更慢会显得键盘"黏"，
// 更快则看不出方向。位移只有内容宽度的 1/3 —— 表盘直径才 466px，
// 整屏平移会让按键飞出圆弧，1/3 已经足够表达"往哪边翻页"。
// ---------------------------------------------------------------------------

/** 切页过渡时长（毫秒）。 */
private const val PAGE_ANIM_MS = 200

/** 入场的滑入距离 = 内容宽度的 1/3，出场朝相反方向滑同样远。 */
private const val PAGE_SLIDE_DIVISOR = 3

/** 新页入场的缩放起点（轻微放大，和 GraphScreen 的转场同一个量级）。 */
private const val PAGE_INITIAL_SCALE = 0.94f

/** 旧页出场时略微放大回去，避免两页在同一个位置"叠住"。（与 scaleIn 对称） */
private const val PAGE_TARGET_SCALE = 1.04f

/**
 * 选择栏汉字字号的缩放系数 = 2/3。
 *
 * 用户要求"函数编辑界面的选择栏的汉字调小 1/3"。基准是 [WatchMetrics.fontKey]，
 * 乘 2/3 之后「初等 / 字母 / 其他」两个汉字在 ≈50dp 宽的 chip 里才有余量
 * （换行/省略号都不会触发）。
 */
private const val TAB_FONT_SCALE = 2f / 3f

// ---------------------------------------------------------------------------
// 「这一段该多宽」和「按键这一行该多高」—— **整套换算只有这两个入口**。
//
// 用户这一轮的第二、三条原话：
//   * "函数编辑页面也没有全屏显示"；
//   * "函数编辑页面的按键应该自动适应屏幕的宽度，进行宽度的自动调整，
//     而不是只有圆屏的按键宽度，这样子很难看的。"
//
// 真因和第一屏那句"手机上只剩顶部一个方格"是同一个：下面这两个函数原来都只用
// `WatchMetrics.sizeDp`（**短边**）——圆表上短边 == 屏高、方表上也相等，所以手表上
// 一直看不出问题；手机上短边是**屏宽**（411dp），于是（全部按 411dp 实算）：
//   * 键盘的 4 列键仍然按"该高度处的圆内宽度 × 0.93"排，三行宽度成了
//     **370 / 316 / 186dp**：第 3 行只有屏宽的 45%（两边各空 113dp、4 颗键每颗 44dp），
//     第 1 行看着还行、越往下越窄 —— 屏幕上就是"几颗小键浮在中间"，
//     正是用户说的"按键不是自动适应屏幕宽度，这样子很难看"；
//   * 键盘那一段的高度也按短边算，所以整块键盘只落在 y = 196dp ~ 388dp 里，
//     真机 850dp 高的屏上**底下 462dp（54%）全是空的**。
//
// 所以这里把两件事分开（和 `FunctionList.ScreenGeometry` 上面那段说明同一套规矩）：
//   * **位置 / 分段高度**（选择栏在哪、网格到哪）→ 按真实屏高 `geometry.sy(...)`；
//   * **宽度** → 圆屏走原来的弧宽公式（**一个数都不改**），非圆屏走
//     `ScreenGeometry.rowWidth`（屏宽 − 左右各 3%），于是 4 列按键自然铺满整宽
//     （手机上三行都是 386dp、每颗键 92dp）；
//   * **按键行高** → 圆屏仍是 `sizeDp × (pitch − gap)`（逐像素不变），非圆屏最多长到
//     [KEY_ASPECT_MAX_NONROUND] 倍键宽（绝不成"竖长条"，见那个常量的说明）。
//
// 为什么放在 MathKeyboard.kt：`FunctionList.kt` 里那个 [ScreenGeometry] 本轮只读复用，
// 不许往里加方法；而这套换算**四块屏共用**（数学键盘、数字小键盘、字母表键盘、
// 设置列表），同一包内直接调用，正好统一在这个键盘文件里。
// ---------------------------------------------------------------------------

/**
 * 非圆屏上按键**行高**相对**键宽**的倍数上限。
 *
 * 1.25 的含义是"按键最多比它自己宽 25%" —— 再往上就该叫用户说的"竖长条"了
 * （手表上按键是 50.9 × 34.4dp，**宽**比高多 48%；手机上一旦让键盘吃满整段高度，
 * 键就会变成 94 × 129dp 的竖长条，那正是用户明确不要的）。
 *
 * 这个上限只在"长条屏"上起作用：手机上键盘那一段高 397dp，而 3 行按键按短边缩放
 * 只有 182dp —— 中间那一大块高度**先按比例给按键**（键从 60.7dp 长到 117.7dp），
 * 给到 1.25 倍键宽就停，剩下的 ≈31dp 留作键盘下边距（见 [keyGridMetrics]）。
 * 想让键盘完全贴到底（那时按键 94 × 129dp、明显是竖长条），把这个数调到 1.5 即可。
 * 圆屏/方表上这个常量**永远不会被用到**（那里 `screenH == sizeDp`，短边行高本来就
 * 远小于 1.25 倍键宽），所以调它不影响手表。
 */
internal const val KEY_ASPECT_MAX_NONROUND = 1.25f

/**
 * 一整段高度上的可用宽度 —— **"圆屏按弧宽收窄 / 非圆屏铺满屏宽"的唯一一处**。
 *
 * 圆屏那一支逐字就是原来的 `spanWidth`：取这一段**上下边缘里更窄的那一处**
 * （只按中心高度算是不够的 —— 圆的宽度随高度递减，行两端比中心更靠外，
 * 用中心宽度会让控件四角越过圆弧被表壳切掉），再乘 [arcMargin] 留一点余量。
 * 所以圆表上换成这个函数**一个像素都不产生差异**。
 *
 * 非圆屏（手机 / 方表 / 横屏）没有"越靠边越窄"这回事，交给
 * [ScreenGeometry.rowWidth] 给"屏宽 − 左右各 [ROW_SIDE_INSET]（3%）"，也就是用户要的
 * "按键自动适应屏幕的宽度"。实算（手机 411 × 850dp）：键盘三行
 * 370 / 316 / **186**dp → **386 / 386 / 386dp**（每颗键 44~90dp → **92dp**）。
 *
 * ## 它和"唯一参照实现"的关系（用户原话："我说的宽度适配是像函数列表的示例的那些按钮一样"）
 *
 * 参照实现 = `FunctionList.kt` 的**示例按钮行**（`itemsIndexed(presets.chunked(2))` 那一段），
 * 它的宽度就是一句：
 * ```kotlin
 * .width(geometry.rowWidth(metrics = metrics, ringFraction = wf, isRound = isRound))
 * ```
 * 本函数**调用的就是同一个 [ScreenGeometry.rowWidth]**，非圆屏那一支逐字相同
 * （`screenW - sx(ROW_SIDE_INSET) * 2f`，用的是**同一个共享常量** `ROW_SIDE_INSET`，
 * 不是抄一份 0.03）；差别只在**喂进去的 `ringFraction`**：
 *
 * | | 弧宽比例怎么来 | 用在哪 |
 * |---|---|---|
 * | 参照实现（示例按钮 / 设置行 / 字母表行） | 取该项**此刻在列表里的中心高度** `centerY`：`cwf(centerY) × 0.95`，量化到百分位，夹 `[0.52, 0.90]` | 会滚动的列表行 |
 * | 本函数（键盘网格、选择栏、编辑器工具条/输入框行） | 取这一段**上下沿里更窄的那一处**：`min(cwf(yTop), cwf(yBottom)) × 0.93`（**改动前就是这个数**） | 位置固定的分段 |
 *
 * 两者都是"按所在行高度取弧宽"，差别只是**保守程度**：分段是一整块矩形，
 * 下沿的角最容易被表壳切，所以取更窄的下沿、余量也留得更多（0.93 < 0.95）。
 * ⚠️ **不要顺手把这一支换成参照实现那一支**：换成 `centerY × 0.95 + 量化 + 夹` 之后
 * 圆表键盘三行会从 **209.8 / 179.2 / 105.3dp 变成 209.7 / 200.4 / 151.5dp**
 * （第 3 行 +44%），圆屏"逐像素不变"当场作废 —— 那是一条硬约束。
 *
 * @param yTop / yBottom 这一段在**屏幕高度**上的上下沿（0~1）——非圆屏上不参与计算
 *   （只影响圆屏的弧宽），但调用方一律照传，圆屏那一支才和改动前完全一致。
 */
internal fun bandWidth(
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    yTop: Float,
    yBottom: Float,
    arcMargin: Float = 0.93f
): Dp = geometry.rowWidth(
    metrics = metrics,
    ringFraction = minOf(
        metrics.circleWidthFraction(yTop),
        metrics.circleWidthFraction(yBottom)
    ) * arcMargin,
    isRound = isRound
)

/**
 * **按键 / 可点控件的宽度基准** —— 用户这条长期规则在代码里的唯一入口：
 *
 * > "以后你写所有按键都要加上对屏幕宽度的自适应。"
 *
 * 规则全文与自查办法写在 `README.md` 的「硬约定：按键 / 可点控件的宽度一律按
 * **真实可用宽度**自适应」那一节，这里只说它在代码里怎么落地：
 *
 *   * **圆屏**：`metrics.sizeDp` —— 短边 == 屏高 == 直径，和这个函数出现之前
 *     `metrics.sizeDp * 比例` 那种写法**逐像素相同**；
 *   * **非圆屏**：`ScreenGeometry.rowWidth` 给的"真实屏宽 − 左右各 3%"。
 *
 * 于是"键宽 = 基准 × 比例"（`DEL_W_FRACTION`、`0.18f`、`0.16f` 这些）在两个屏形上
 * 都是**按可用宽度成比例**，而不是"按短边写死"。为什么非要换基准：`sizeDp` 是**短边**，
 * 圆表上短边 == 屏高所以看不出问题；横屏手机/平板上短边是**屏高**，
 * 拿它当宽度基准算出来的键会缩成一根窄柱、两边空一片（手机上已经报过几次，
 * 见 [bandWidth] 上面那段真因）。
 *
 * ## 它就是参照实现里那一句（用户："像函数列表的示例的那些按钮一样"）
 *
 * `keyWidthBase(metrics, geometry, isRound)` 与示例按钮那一行的宽度
 * `geometry.rowWidth(metrics, ringFraction = 1f, isRound = isRound)`
 * **是同一个表达式**：
 *   * 非圆屏：`screenW − 2 × sx(ROW_SIDE_INSET)` = **386.3dp**（手机）——
 *     示例按钮那一行、设置页行、字母键盘行、以及这里的"单颗键基准"全都等于它；
 *   * 圆屏：`metrics.sizeDp × 1f` = 短边（示例按钮那一行在圆屏上还要再乘它自己的
 *     弧宽比例，因为它是会滚动的列表行；固定分段的键走 [bandWidth]）。
 *
 * ⚠️ 新按键请一律用 `keyWidthBase(...) * 比例` 或直接 `Modifier.weight(...)` 挂在
 * 已经自适应的行里；**不要再出现** `Modifier.width(48.dp)` 或
 * `Modifier.width(metrics.sizeDp * 比例)` 这种写法（后者在圆屏上等价、在别的屏形上退化）。
 */
internal fun keyWidthBase(metrics: WatchMetrics, geometry: ScreenGeometry, isRound: Boolean): Dp =
    // ringFraction = 1f：圆屏这一支就是 `sizeDp * 1f`（原样），非圆屏那一支给满宽。
    geometry.rowWidth(metrics = metrics, ringFraction = 1f, isRound = isRound)

/** 按键网格的"一行多高、行与行之间多大缝"。两者永远一起给，免得各自为政。 */
internal class KeyGridMetrics(val rowHeight: Dp, val gap: Dp)

/**
 * 按键网格的纵向几何（行高 + 行缝）。
 *
 * **圆屏**：原样 —— 行高 `sizeDp × ((gridBottom − gridTop)/rows − gapFraction)`、
 * 行缝 `sizeDp × gapFraction`，和改动之前一字不差（数学键盘 34.4dp / 1.86dp）。
 *
 * **非圆屏**：网格整段按**真实屏高**落在 `[sy(gridTop), sy(gridBottom)]` 里
 * （手机上 = 406dp ~ 803dp，也就是"键盘区到底部"），行高先按"铺满"取
 * `pitch − 自然缝`，再被 [KEY_ASPECT_MAX_NONROUND] × 键宽夹住：
 *   * 手机 411×850：pitch = 132.4dp → 铺满要 129.1dp，但键宽只有 94.1dp，
 *     于是行高取 **117.7dp**（= 1.25 × 键宽），行缝按"和按键等比"取 **6.4dp**
 *     （= 自然缝 3.3dp × 117.7/60.7），剩下的 ≈31dp 就是键盘自己的下边距；
 *   * 方表 200×200：`screenH == sizeDp`（`pitch − 自然缝` 恰好等于短边行高），
 *     行高与行缝**正好落回改动前的 29.55dp / 1.6dp**，所以方表上只有"宽度"变宽
 *     （行宽 180 / 154 / 90dp → 全部 188dp）；两个 `coerceAtLeast` 是给
 *     "屏高比短边还小"这类极端比例兜底的。
 *
 * 为什么行缝要跟着行高一起放大（而不是继续用 `sizeDp × gapFraction`）：
 * 缝和按键必须**成比例**才像同一套键盘 —— 手表的缝是键高的 5.4%，手机上行高翻到
 * 1.94 倍之后缝也跟着翻（3.3 → 6.4dp），观感才是"同一块键盘放大了"，
 * 而不是"按键变大、缝没变"。
 *
 * ⚠️ **这是本文件与"唯一参照实现"（`FunctionList.kt` 的示例按钮行）唯一一处
 * 有意不同的地方**，写清楚免得下次有人当成 bug 顺手"统一"掉：
 *   * 参照实现的行**高度不随屏形变**（它是列表，行高恒为 `sizeDp × 0.085f`，
 *     非圆屏上只是"一屏看得见更多行"），所以它的缝可以恒为短边常量
 *     `sizeDp × 0.018f`；
 *   * 键盘只有 3 行、又必须铺到屏幕下半截（用户上一轮明确要求"键盘区到底部"），
 *     行高在非圆屏上会长到 ≈1.94 倍 —— 此时再按短边给缝，竖向缝只剩键高的 2.8%
 *     （手表是 5.4%），一颗颗胶囊会挤成一条。
 *   * **圆屏上两者完全等价**：`naturalGap × (naturalRowHeight / naturalRowHeight)`
 *     = `sizeDp × gapFraction`（逐像素不变）。
 *   * 想把缝也改成参照实现那种"短边常量"，把 `gap` 那一句换成 `naturalGap` 即可
 *     （一行；手机上是 6.4 → 3.3dp）。
 *
 * @param rowWidth 一行按键的实际宽度（调用方已经按屏形算好，见 [bandWidth]）。
 * @param columns 一行最多几列（[KEYBOARD_COLUMNS]）—— 用来算"键最宽能有多少"。
 */
internal fun keyGridMetrics(
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    gridTopFraction: Float,
    gridBottomFraction: Float,
    gapFraction: Float,
    rows: Int,
    rowWidth: Dp,
    columns: Int
): KeyGridMetrics {
    val naturalRowHeight =
        metrics.sizeDp * ((gridBottomFraction - gridTopFraction) / rows - gapFraction)
    val naturalGap = metrics.sizeDp * gapFraction
    // 圆屏：原样。这一支不许出现任何新算式（弧宽、行高、行缝都是圆表专用的）。
    if (isRound) return KeyGridMetrics(naturalRowHeight, naturalGap)

    val span = geometry.sy(gridBottomFraction) - geometry.sy(gridTopFraction)
    val pitch = span / rows
    // 一列键最宽能有多少（扣掉列与列的缝）：按键最多长成"略高于宽"，绝不长成竖长条。
    val columnWidth = (rowWidth - naturalGap * (columns - 1)) / columns
    val rowHeight = minOf(pitch - naturalGap, columnWidth * KEY_ASPECT_MAX_NONROUND)
        // 短屏兜底：非圆屏上 `pitch − 缝` 本来就 ≥ 短边行高（`screenH ≥ sizeDp`），
        // 这两句是防"极端比例屏"把按键算得比手表还小。
        .coerceAtLeast(naturalRowHeight)
    val gap = minOf(
        // 铺满剩下的高度全给缝（手机上被上面的上限拦住，所以走不到这一支太大的情况）
        (pitch - rowHeight).coerceAtLeast(naturalGap),
        // 缝和按键成比例：和行高一起放大
        naturalGap * (rowHeight / naturalRowHeight)
    ).coerceAtLeast(naturalGap)
    return KeyGridMetrics(rowHeight, gap)
}

/**
 * 圆屏自适应数学键盘：**一排选择栏（页签）+ 固定 3 行的按键网格**。
 *
 * 用词：最上面那排 `123 / 初等 / 字母 / 其他`（参数方程下还多一个范围 chip）
 * 在真机反馈里叫「**选择栏**」，这里统一用这个词；
 * 类型名仍然是 [KeyPage]、参数名仍然是 `tab*` / `TAB_*`，没有跟着改名。
 *
 * 为什么是选择栏而不是上下滑动的列表（这是真机上换过一次的决定）：
 *   滑动列表里按键的位置会随滚动跑掉，用户得先"找"按键再按；
 *   而手表屏幕只有 1.5 寸，一次滑动遮住的位置比露出来的还多。
 *   选择栏 + 固定网格则保证：每页的格子永远在同一个地方，
 *   切换页面时按键不跳变、手感一致。
 *
 * 网格从 4 行收到 [KEYBOARD_ROWS]（3 行）之后，行高是按
 * `pitch = (gridBottom − gridTop) / KEYBOARD_ROWS` 现算的（见 [MathKeyboard] 里
 * `pitchFraction` / `rowHeight` 的推导），所以**行数一变、行高自动跟着变**，
 * 不需要（也不该）在这里写死任何高度。
 *
 * 选择栏那一个 chip 和按键**一样高**、缝也一样宽（都是 gapFraction），
 * 所以它看起来就是网格的第 1 行，而不是一条压扁的窄条。
 *
 * 网格里**没有编辑键**：`⌫` 挪到了输入框右边（[BackspaceKey]），
 * 清空整个表达式是**长按 ⌫**。真机上那一行 `← → C ⌫` 的三个键几乎没人用，
 * 省下来的行高全部给了按键和输入框（见 FunctionEditor 的纵向分区）。
 *
 * 键的内容可以被长按气泡改写：比如把 `sin` 换成 `asin`，那个键从此显示并插入
 * `asin()`，再长按还能换回来。状态在这里用 [selections] 存，换模式时复位。
 *
 * 所有纵向参数都是**屏高比例**，不是固定 dp —— 详见 WatchMetrics 的说明。
 * 每行的宽度还要按圆弧收缩：圆屏越靠上/下越窄，用等宽会被表壳切掉按键。
 *
 * ⚠️ 本轮起"屏高比例"里的**屏高**是 [ScreenGeometry] 量的**真实屏高**（见文件里
 * [bandWidth] / [keyGridMetrics] 上面那段真因）：圆屏上 `screenH == sizeDp`，
 * 所以圆表一个像素都不变；手机上键盘那一段才真正落到下半屏、按键也才铺满整宽。
 *
 * @param geometry 真实屏幕几何（[ScreenGeometry]）。**只有非圆屏**会用到它：
 *   位置/分段高度按真实屏高、行宽按真实屏宽。圆屏那一支只吃 [metrics]，
 *   所以传进来的东西不影响圆表。
 * @param isRound 圆屏还是非圆屏。**这一位是"圆屏逐像素不变"的开关**：
 *   为 true 时下面每一处都走原来的 `metrics.sizeDp × 比例`。
 * @param tabTopFraction 选择栏的顶边
 * @param tabHeightFraction 选择栏 chip 的高度 —— 调用方给的就是**一个按键的高度**
 *   （`(gridBottom − tabTop) / (1 + KEYBOARD_ROWS) − gap`，见 FunctionEditor 的纵向分区），
 *   所以这一栏看起来就是网格的第 1 行；它到网格之间的缝也等于行距 gapFraction。
 * @param gridTopFraction 按键网格的顶边（= tabTop + tabHeight + gap）
 * @param gridBottomFraction 按键网格的底边
 * @param gapFraction 按键的行距 / 列距，选择栏与网格之间也用这个值
 * @param fieldCenterFraction **输入框的竖直中心** —— 长按变体气泡盖在它上面
 *   （真机反馈原话：「小菜单要覆盖显示在输入框上面」）。
 *   气泡高度是 keyHeight（0.135 屏高），比现在的输入框（0.115）略高一点，
 *   但上下都还有余量：气泡占 0.182~0.317，而工具条底边是 0.180、
 *   选择栏顶边是 0.322 —— 它仍然只压在"暂时不用看的旧表达式"上，
 *   不碰工具条、也不碰选择栏，更不会压到要按的键。
 *   宽度仍按气泡**中心所在高度**收窄，不被表壳切掉。
 * @param rangeLabel 非 null 时在选择栏末尾多一个"参数范围"chip（POLAR / PARAMETRIC）
 * @param onToggleAngleUnit OTHER 页那个角度制键被按下时调用 —— 它**不插文本**，
 *   只切角度制（RAD ↔ DEG）。动作已经在模型里定义成 [KeyAction.ToggleAngleUnit]，
 *   这里让调用方直接拿到"切换"这件事，不用去 [KeyAction] 里再分一次支。
 * @param angleUnitLabel 那个角度制键上要显示的**当前制**（`"RAD"` / `"DEG"`）。
 *   模型给的是占位标签（纯函数不知道当前是什么制），网格渲染时按
 *   `action is KeyAction.ToggleAngleUnit` 换成这个值 —— 见下面网格里 `shownKey` 那段。
 * @param onOpenAlphabet OTHER 页第 3 行那个 `abc` 键被按下时调用 —— 它同样**不插文本**，
 *   只把"用户要打开内置英文（字母表）键盘"这件事交给调用方（切界面）。
 *   动作在模型里定义成 [KeyAction.OpenAlphabet]，这里走**独立回调**，
 *   不用去 [KeyAction] 里再分一次支（和 [onToggleAngleUnit] 一个写法）。
 *   默认值是空实现：**已经存在的调用方（FunctionEditor）不用改就能编译**，
 *   接上字母表键盘之后再把真正的实现传进来。
 * @param onKey 其余按键：把动作交给调用方去改输入框里的文本（KeyboardModel.editText）。
 *
 * ⚠️ 这个 composable 是 `internal` 而不是 public：它的参数里有 [ScreenGeometry]，
 * 而那个类型是 `internal`（定义在 `FunctionList.kt`，本轮只读复用）。
 * 唯一的调用方是同包的 `FunctionEditorScreen`。
 */
@Composable
internal fun MathKeyboard(
    page: KeyPage,
    mode: PlotMode,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    tabTopFraction: Float,
    tabHeightFraction: Float,
    gridTopFraction: Float,
    gridBottomFraction: Float,
    gapFraction: Float,
    fieldCenterFraction: Float,
    rangeLabel: String?,
    onSelectPage: (KeyPage) -> Unit,
    onCycleRange: () -> Unit,
    onToggleAngleUnit: () -> Unit,
    angleUnitLabel: String,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlphabet: () -> Unit = {}
) {
    // ---- 行高 / 行距的唯一来源（这一轮真机反馈"选择栏下面的按钮高度更小了、
    //      间隔更大了"，所以把这三个数摊开写清楚，谁都不能再各自为政）----
    //
    //   pitchFraction = (gridBottom − gridTop) / KEYBOARD_ROWS   ← 一行的"行距"（行高 + 行缝）
    //   rowHeight     = sizeDp × (pitchFraction − gapFraction)    ← **按键行高**，
    //                    等价于 sizeDp × ((gridBottom − gridTop)/KEYBOARD_ROWS − gapFraction)
    //   行缝          = gap = sizeDp × gapFraction                ← 相邻两行之间的距离
    //
    // 也就是说：**行缝严格等于调用方给的 gapFraction**，行高严格等于 pitch 减掉
    // 那一个 gapFraction；行高**不再**取 metrics.keyHeight 那个旧常量
    // （keyHeight 现在只剩长按气泡在用，见 bubbleHeight）。
    // 调用方是按 (GRID_BOTTOM − TAB_Y) / (1 + KEYBOARD_ROWS) 反解出
    // tabHeight / gridTop 的，所以这里算出来的 pitchFraction 同时也就是
    // "选择栏到第 1 行按键"的那一段 —— 选择栏的行高、按键的行高、两种行距
    // 全都是同一个数，改任何一个都必须四个一起改。
    //
    // ⚠️ 上面这套算式现在**只在圆屏上原样生效**；非圆屏（长条屏 / 横屏）由
    // [keyGridMetrics] 按真实屏高重算行高与行缝（见那两个函数上面的真因）。
    // 圆屏上 `isRound == true` 那一支逐字是上面这套，一个数都没改。
    val pitchFraction = (gridBottomFraction - gridTopFraction) / KEYBOARD_ROWS
    // 非圆屏上每一行的宽度都一样（铺满屏宽减左右余量），拿它算"一个键最宽能有多少"；
    // 圆屏那一支不用这个数（行宽逐行按弧宽算、行高也不受键宽约束）。
    val gridRowWidth =
        bandWidth(metrics, geometry, isRound, gridTopFraction, gridBottomFraction)
    val grid = keyGridMetrics(
        metrics = metrics,
        geometry = geometry,
        isRound = isRound,
        gridTopFraction = gridTopFraction,
        gridBottomFraction = gridBottomFraction,
        gapFraction = gapFraction,
        rows = KEYBOARD_ROWS,
        rowWidth = gridRowWidth,
        columns = KEYBOARD_COLUMNS
    )
    val gap = grid.gap
    val rowHeight = grid.rowHeight

    // 选择栏（页签那一排）**就是网格的第 1 行**：高度和按键一样、缝也一样，
    // 位置由网格反推（`gridTop − 缝 − 行高`）。
    //
    // ⚠️ 这不是新算法：圆屏上 `gridTop − gap − rowHeight` 逐像素等于原来的
    // `sizeDp × TAB_Y`（0.47775 − 0.008 − 0.14775 = 0.322 = TAB_Y，注释里的推导
    // 就是这么定的），所以圆表上选择栏一个像素都不动；非圆屏上行高变高了，
    // 选择栏跟着一起变高、位置跟着一起下移，"选择栏 = 网格的第 1 行"这条关系
    // 在任何屏形上都成立（手机上原来是"选择栏在 274dp、网格从 406dp 开始"，
    // 中间白空 71dp，就是因为选择栏的高度还按短边算）。
    val tabHeight = if (isRound) metrics.sizeDp * tabHeightFraction else rowHeight
    val tabTop = if (isRound) {
        metrics.sizeDp * tabTopFraction
    } else {
        geometry.sy(gridTopFraction) - gap - tabHeight
    }

    /**
     * 按键 / 选择栏 chip 共用的圆角形状：**完整胶囊**（见 [PillShape]）。
     *
     * 这是参考 `D:\Culculator` 之后的取值：它四个按键 drawable 都写
     * `corners android:radius="50dp"`，远大于半高，等价于 `percent = 50`。
     * 不用 `MaterialTheme.shapes.small` 那种固定 dp —— 行高是算出来的，
     * 行数一变固定圆角就会失调。
     */
    val keyShape = PillShape

    // 长按变体气泡：非 null 时显示。带着"是哪个键长按出来的"，用来高亮当前项。
    var bubble by remember { mutableStateOf<BubbleState?>(null) }

    // ---- 每个键此刻被换成了哪个变体：基键 label → 当前变体 label ----
    // 用 remember(mode)：换模式时全部复位 —— 各模式的网格内容本来就不同，
    // 换个模式还留着上一轮选的变体只会让人莫名其妙。
    //
    // **这个状态必须留在 AnimatedContent 外面**：切页动画的内容 lambda 每次切换
    // 都会重新组合一份（旧页那份还要留着放完退出动画），如果 selections 写在里面，
    // 切走再切回来时它已经被初始化成空 map —— 用户长按换成 asinh 的那个键
    // 会悄悄变回 sin。放在这里，网格只是"用当前的选择重画一遍"。
    var selections by remember(mode) { mutableStateOf(emptyMap<String, String>()) }

    // ---- 气泡的几何：竖直中心对齐输入框中心、水平居中，**允许遮住输入框** ----
    // 它是"临时选择器"：弹出来的这一下，输入框里写着什么并不重要，
    // 重要的是能一次点中要换的那个变体。
    val bubbleHeight = metrics.keyHeight
    val bubbleHeightFraction = bubbleHeight / metrics.sizeDp
    val bubbleTopFraction = fieldCenterFraction - bubbleHeightFraction / 2f
    // 圆屏越靠上越窄：按气泡**中心所在高度**收窄，否则气泡两端会被表壳切掉。
    // 非圆屏（手机）走 [bandWidth] 的满宽那一支 —— 原来这里也是"圆屏弧宽"
    // （手机上 0.93 × cwf(0.2495) × 411dp = 331dp，屏宽的 80%），
    // 现在是 386dp，几个候选（asinh / acosh / atanh）排得更舒展。
    val bubbleWidth =
        bandWidth(metrics, geometry, isRound, fieldCenterFraction, fieldCenterFraction)

    Box(modifier = modifier) {

        // ---- 选择栏（页签那一排）：高度 = 一个按键的高度 ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 位置/高度：圆屏是原来的 `sizeDp × TAB_Y / TAB_H`；
                // 非圆屏由网格反推（见上面 tabTop / tabHeight 的说明），
                // 于是手机上选择栏就在第一行按键的正上方、不再和网格之间空 71dp。
                .offset(y = tabTop)
                .width(
                    bandWidth(
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        yTop = tabTopFraction,
                        yBottom = tabTopFraction + tabHeightFraction
                    )
                )
                .height(tabHeight),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            KeyPage.entries.forEach { p ->
                TabChip(
                    label = p.label,
                    selected = p == page,
                    metrics = metrics,
                    onClick = { onSelectPage(p) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = keyShape
                )
            }
            if (rangeLabel != null) {
                // 第 5 个 chip 不是页面，而是"当前参数范围"的显示 + 循环切换按钮。
                // 用第三色（tertiary）和页面 chip 区分开：按下去只换范围，不切页。
                // （仍然是 [KeyPage] 之外的独立 chip，不进 [KeyPage.entries]）
                TabChip(
                    label = rangeLabel,
                    selected = false,
                    accent = true,
                    // 数值范围 6 个字符塞不进 fontKey，退一档（见 TabChip 的说明）
                    compact = true,
                    metrics = metrics,
                    onClick = onCycleRange,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = keyShape
                )
            }
        }

        // ---- 按键网格：3 行；每行 4 个键（OTHER 页最后一行是 2 个键：
        //      空格占 3/4 + abc 占 1/4，见 KeySpec.weight）；
        //      切页时整块网格做一次有方向感的过渡 ----
        //
        // AnimatedContent 的两个注意点：
        //   1. **内容每次切换都会重建**，所以 selections / bubble 这两个状态都留在外面
        //      （见上面两处说明），内容 lambda 只负责"按当前选择把网格画一遍"。
        //   2. 四页的网格几何完全一样（行数、每行的宽度、行高都只跟行号有关，
        //      见 bandWidth（圆屏走弧宽、非圆屏满宽）；行里各键的宽度按 KeySpec.weight 分，键数不同的行
        //      —— 比如 OTHER 页那一对"空格 + abc" —— 也一样是分满整行宽），
        //      所以过渡期间新旧两页的尺寸一致，
        //      AnimatedContent 默认的 SizeTransform 实际上不产生任何尺寸动画，
        //      这里也就不需要（也不该）自己去配 SizeTransform。
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // 方向 = 新页在选择栏里的位置 − 旧页：往右翻就从右边进来，往左翻从左边进来
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (
                    slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = PAGE_ANIM_MS,
                            easing = FastOutSlowInEasing
                        )
                    ) { full -> dir * full / PAGE_SLIDE_DIVISOR } +
                        fadeIn(animationSpec = tween(PAGE_ANIM_MS, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = PAGE_INITIAL_SCALE,
                            animationSpec = tween(
                                durationMillis = PAGE_ANIM_MS,
                                easing = FastOutSlowInEasing
                            )
                        )
                    ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = PAGE_ANIM_MS,
                            easing = FastOutSlowInEasing
                        )
                    ) { full -> -dir * full / PAGE_SLIDE_DIVISOR } +
                        fadeOut(animationSpec = tween(PAGE_ANIM_MS, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            targetScale = PAGE_TARGET_SCALE,
                            animationSpec = tween(
                                durationMillis = PAGE_ANIM_MS,
                                easing = FastOutSlowInEasing
                            )
                        )
                )
            },
            label = "keyboardPage",
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 上沿按**真实屏高**（圆屏上 `sy(0.47775)` 逐像素等于 `sizeDp × 0.47775`）。
                // 手机上这就是"键盘整块落到下半屏"的那一句：406dp 起，底边到 803dp。
                .offset(y = geometry.sy(gridTopFraction))
        ) { shownPage ->
            // 布局只跟页签和模式有关，和文本无关，所以缓存住
            val rows = remember(shownPage, mode) { keyboardRows(shownPage, mode) }
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                rows.forEachIndexed { rowIndex, keys ->
                    // 这一行的上、下边沿：宽度取两者里更窄的那个（见 [bandWidth]）。
                    // ⚠️ 这两个**比例**只对圆屏有意义：非圆屏上 [bandWidth] 直接给满宽
                    // （每行都一样宽 —— 矩形的屏没有"越靠下越窄"这回事）。
                    // 它们也**不**代表非圆屏上每行的真实位置：那边每行的高度是
                    // [keyGridMetrics] 按真实屏高算出来的 dp，和 `rowIndex × pitchFraction`
                    // 不再是一回事，所以只把这两个数喂给圆屏那一支的弧宽公式。
                    val rowTopFraction = gridTopFraction + rowIndex * pitchFraction
                    val rowBottomFraction = rowTopFraction + (pitchFraction - gapFraction)
                    Row(
                        modifier = Modifier
                            .width(
                                bandWidth(
                                    metrics = metrics,
                                    geometry = geometry,
                                    isRound = isRound,
                                    yTop = rowTopFraction,
                                    yBottom = rowBottomFraction
                                )
                            )
                            .height(rowHeight),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        keys.forEach { base ->
                            // 这个键此刻实际代表什么：没被换过就是它自己。
                            // 显示和插入都用它 —— 换了变体之后连点下去插什么也跟着换。
                            // （selections 在 AnimatedContent 外面，所以切页回来不会被重置）
                            val key = resolvedKey(base, selections)
                            // 变体组按**基键**取：把 sin 换成 asinh 之后再长按它，
                            // 拿到的仍是 sin 那组，所以还能换回 sin。
                            val variants = variantsFor(base.label)
                            // 角度制那个键要显示**当前制**（RAD / DEG），而模型给的是
                            // 占位标签（模型是纯函数、没有状态）。判据是动作类型，
                            // 这样模型那边不用为"显示什么"再开一个参数。
                            val shownKey = if (key.action is KeyAction.ToggleAngleUnit) {
                                key.copy(label = angleUnitLabel)
                            } else {
                                key
                            }
                            val openBubble: () -> Unit =
                                { bubble = BubbleState(base.label, variants, key.label) }
                            KeyButton(
                                key = shownKey,
                                metrics = metrics,
                                onClick = {
                                    // 两个**动作键**各走自己的回调：它们都不插文本。
                                    //   角度制键 = RAD ↔ DEG（见 onToggleAngleUnit 的说明）；
                                    //   abc 键 = 打开内置英文字母表键盘（见 onOpenAlphabet）。
                                    // editText 里仍然给这两个动作都留了分支（原样返回
                                    // text to c），那是为了 when 穷尽 + 兜底，
                                    // 正常路径不会走到那里。
                                    when (key.action) {
                                        is KeyAction.ToggleAngleUnit -> onToggleAngleUnit()
                                        is KeyAction.OpenAlphabet -> onOpenAlphabet()
                                        else -> onKey(key.action)
                                    }
                                },
                                // 没有变体的键不给长按判定（见 KeyButton 的说明）
                                onLongClick = if (variants.isEmpty()) null else openBubble,
                                shape = keyShape,
                                // weight 只管**宽**度，必须再 fillMaxHeight：
                                // 少了它，Surface 会按文字高度收缩到 ≈16dp，
                                // 而外面这个 Row 预留的是整格 rowHeight（35.8dp）——
                                // 真机上就表现成"按键很矮、行与行之间缝很大"，
                                // 同时选择栏 chip 是 fillMaxHeight 的，看起来反而比按键高。
                                // （真机反馈的原话就是"选择栏的按钮更高、下面的按钮没变"。）
                                //
                                // 份数取自模型（KeySpec.weight）：默认 1 = 均分；
                                // OTHER 页第 3 行是「空格 3 + abc 1」，
                                // 于是空格占 3/4 行宽、abc 占 1/4，两颗键之间的缝
                                // 仍然是 Row 的 Arrangement.spacedBy(gap)，和别处一样。
                                modifier = Modifier.weight(key.weight.toFloat()).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }

        // ---- 长按变体气泡：画在最后，所以盖在输入框、选择栏和键盘上面 ----
        //
        // 它也**留在 AnimatedContent 外面**：气泡本来就盖在输入框上（不跟着网格走），
        // 放到切页动画里面会被一起平移/缩放，看起来像"气泡被甩了一下"。
        // 它自己还有出现/收起动画（见 VariantBubble），两套动画不该叠加。
        val shown = bubble
        if (shown != null) {
            VariantBubble(
                variants = shown.variants,
                currentLabel = shown.currentLabel,
                metrics = metrics,
                width = bubbleWidth,
                height = bubbleHeight,
                // 竖直位置：圆屏是原来的 `sizeDp × (输入框中心 − 气泡半高)`；
                // 非圆屏按**真实屏高**落在输入框中心（`sy(fieldCenterFraction) − 半高`）——
                // 手机上输入框中心在 187dp，气泡还是盖在输入框上，不会跑偏。
                topOffset = if (isRound) {
                    metrics.sizeDp * bubbleTopFraction
                } else {
                    geometry.sy(fieldCenterFraction) - bubbleHeight / 2f
                },
                // 点变体**不是插入**，而是把这个键换成那个变体（见 KeyboardModel 的
                // applyVariantSelection / resolvedKey）：输入框里已经有式子的时候，
                // 误点一下不该往光标处塞一个 asinh()
                onPick = { picked ->
                    selections = applyVariantSelection(selections, shown.baseLabel, picked.label)
                },
                onDismiss = { bubble = null },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * 选择栏里的一个 chip（页面页签 / 范围 chip）。
 *
 * 高度不由这里定：调用方给的高度就是**一个按键的高度**（见 [MathKeyboard] 的
 * `tabHeightFraction`），所以它和网格里的按键严丝合缝地对齐。
 *
 * 没有直接用 [MiniChip]：那个组件的 selectedColor 分支会把文字强制成 onPrimary，
 * 用在 tertiaryContainer 上对比度不对。这里把配色规则写全：
 * 选中 = primaryContainer，范围 chip = tertiaryContainer，其它 = surfaceContainerHigh。
 *
 * **字号仍是 `fontKey` + [FontWeight.Normal]**：这一轮的真机反馈只说"按键"的字号
 * 变小，选择栏这一排没有动 —— 它只有 2~3 个字符（`初等`/`123`），不像函数名那样
 * 会顶到按键边缘。一个 chip 的宽度按最窄的那一行算：233dp 屏、选择栏占
 * 0.322~0.47575 这一段时可用宽 ≈202.5dp（窄的一侧是上沿 cwf(0.322)=0.9345），
 * 4 个 chip 每个 ≈50.3dp，带上末尾的范围 chip（5 个）每个还有 ≈40.1dp ——
 * 两个字（「初等」）在 fontKey（16.31sp）下正好 2em ≈ 32.6dp，还留 7.5dp 余量。
 * （按键已经降到 [WatchMetrics.fontSmall]，两边不再同档：见 [KeyButton] 的说明。）
 *
 * [compact] 是给**末尾的范围 chip** 留的例外：它显示的是数值范围（`−10~10` 这种
 * 6 个字符），同样只有 ≈40.1dp 可用 —— 在 fontKey 下需要 ≈55dp，会被截成 `−10~1…`；
 * 退一档到 [WatchMetrics.fontTiny]（9.79sp）只要 ≈33dp，四个预设（0~2π / 0~4π /
 * −π~π / −10~10）都能完整显示。汉字标签走默认值，不受影响。
 */
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    metrics: WatchMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    compact: Boolean = false,
    /** 和按键用同一个形状（胶囊），选择栏看起来就是网格的第 1 行。 */
    shape: Shape = PillShape
) {
    val scheme = MaterialTheme.colors
    // 页签配色对齐 Culculator 的 bg_btn_dark_toggle.xml：
    // 选中 = 强调色，未选中 = 普通键底色，未选中文字 80% 白。
    // 末尾的"范围"chip 保留我们自己的第三色（深紫）—— 它既不是页签、
    // 也不是普通按键，用第三种底色才不会被误读成"第五个页签"。
    val container = when {
        selected -> KeyPalette.accent
        accent -> scheme.tertiaryContainer
        else -> KeyPalette.normal
    }
    val content = when {
        selected -> KeyPalette.text
        accent -> scheme.onTertiaryContainer
        else -> KeyPalette.textMuted
    }
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                // 选择栏这一排仍是 fontKey + Normal（选中与否只靠底色区分）——
                // 但用户要求"选择栏的汉字调小 1/3"，所以这里乘 2/3：
                // 「初等 / 字母 / 其他」两个汉字在 233dp 屏上原本会顶到 chip 边缘。
                fontSize = if (compact) metrics.fontTiny else metrics.fontKey * TAB_FONT_SCALE,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 单个按键。配色按 [KeyTone] 从 [KeyPalette] 取（参考 `D:\Culculator`），不硬编码颜色。
 *
 * 字号仍是 [WatchMetrics.fontSmall]（12.12sp），字重仍然是 [FontWeight.Normal]。
 * 但**网格从 4 行收到 3 行、行缝又从 0.47dp 放到 1.86dp 之后**，
 * 三条行的键宽要重新核算一遍（233dp 屏、density 320 → sp≈dp；
 * gap = 0.008 屏宽 = 1.86dp）：
 *
 *   * 第 1 行（0.47775~0.6255）：行宽 = 233dp × cwf(0.6255) × 0.93 = 209.1dp
 *     → 键宽 = (209.1 − 3 × 1.86) / 4 = 50.9dp（三行里最宽）
 *   * 第 2 行（0.6335~0.78125）：行宽 = 233dp × cwf(0.78125) × 0.93 = 177.4dp
 *     → 键宽 = 43.0dp
 *   * 第 3 行（0.78925~0.937）：行宽 = 233dp × cwf(0.937) × 0.93 = 100.5dp
 *     → 键宽 = 23.7dp（最窄：这一行的下沿已经进到圆屏收口里了）
 *
 * **长标签坐在宽行、短标签坐在窄行**（用户要求把初等页的 `+ − × ÷` 和
 * `sin cos tan log` 对调，理由正是"不然显示不下"）：
 * 初等页现在是第 1 行 `sin cos tan log`（50.9dp/格）、第 3 行 `+ − × ÷`（23.7dp/格）。
 * 于是 `asinh`（5 个字符 ≈2.7em ≈32.8dp）在 50.9dp 的格子里**放得下**，
 * 前面几轮"窄行放不下函数名"的问题从布局上就消掉了；
 * [TextOverflow.Ellipsis] 留作兜底（万一以后又往窄行塞长标签），
 * 宁可收一个"…"也不把最后一个字母拦腰切断。
 *
 * 选择栏的 chip 和按键**永远等高**：它用同一个 [PillShape]、
 * 高度也是同一个 `pitch − gap`（见 FunctionEditor 的纵向分区）。
 * 汉字标签乘了 [TAB_FONT_SCALE]（2/3）——用户要求"选择栏的汉字调小 1/3"。
 *
 * [onLongClick] 非 null 时用 [combinedClickable] 接长按（弹出变体气泡）。
 * 其它键仍然走普通 [clickable]：不给没有变体的键加长按判定，
 * 免得白白拖慢点击响应 —— 手表上按键的即时反馈很重要。
 */
@Composable
private fun KeyButton(
    key: KeySpec,
    metrics: WatchMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** 圆角形状：默认就是一整颗胶囊（见 [PillShape]）。 */
    shape: Shape = PillShape
) {
    // 三个色调对应 Culculator 的三个按键 drawable（见 WatchColors.KeyPalette 的表）。
    // 文字统一纯白：它的按键全是 #FFF，不分色调。
    val (container, content) = when (key.tone) {
        KeyTone.Normal -> KeyPalette.normal to KeyPalette.text
        KeyTone.Accent -> KeyPalette.accent to KeyPalette.text
        KeyTone.Danger -> KeyPalette.danger to KeyPalette.text
    }
    val tap = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .then(tap)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = key.label,
                // 比选择栏的汉字小一档：按键上要放得下函数名（见上面的键宽核算）
                fontSize = metrics.fontSmall,
                // 和汉字标签同粗细（原来是 Medium，比汉字显粗）
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                // 长标签现在都坐在第 1 行（50.9dp/格），`asinh` 这类 5 字符变体名放得下；
                // 最窄的第 3 行只剩 `+ − × ÷` 这种单字符。Ellipsis 是兜底：
                // 万一以后又往窄行塞长标签，宁可收一个"…"也别把字母拦腰切断。
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 输入框右边的退格键。
 *
 * 真机反馈把 `⌫` 从键盘网格里搬到了这里（所以 `← → C ⌫` 那一整行取消了）：
 * 退格是打字时按得最多的键之一，贴着输入框、手指不用离开输入区就能按到，
 * 比放在屏幕最下面那一行顺手得多。
 *
 * **长按 = 清空整个表达式**（[KeyAction.Clear]）：`C` 键取消了，
 * 但"这条不要了、重打"仍然需要入口，长按比再开一个键省地方。
 * 长按时先给一次震动反馈（[HapticFeedbackType.LongPress]）——
 * 清空是不可撤销的，手指要立刻知道"这一下按到了、而且是长按"。
 *
 * 配色沿用原来 `C` / `⌫` 的 Danger 档（errorContainer），
 * 在满屏按键里一眼能认出"这是会删东西的键"。
 *
 * 字号是 [WatchMetrics.fontKey] + [FontWeight.Normal]，**没有跟着网格按键降到
 * fontSmall**：它只有 1 个字形、永远不会被裁，而且它贴着的表达式是 fontExpr
 * （17.5sp）—— `⌫` 和输入框同一档才对得上。真机反馈的"所有按键字号变小"
 * 说的是网格里那些带函数名的键（见 [KeyButton] 的说明）；
 * 如果装上之后觉得它和网格按键不协调，把下面那行改成 `metrics.fontSmall` 即可。
 *
 * 高度**不在这里定**：调用方把它放在和输入框同一个 Row 里、宽度给
 * `DEL_W_FRACTION`、高度给 `fillMaxHeight()`，所以它永远和输入框等高
 * （这一轮输入框降到了 0.115 屏高 ≈ 26.8dp，⌫ 跟着一起变矮）。
 *
 * 放在这个文件里是因为它和按键共用同一套 API（[combinedClickable] 长按、
 * 主题配色、字号），而 FunctionEditor 没有 ExperimentalFoundationApi 的 OptIn。
 */
@Composable
fun BackspaceKey(
    metrics: WatchMetrics,
    onKey: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    /** 圆角形状：调用方按输入框高度算（⌫ 和输入框等高）。 */
    shape: Shape = PillShape
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        // 危险色换成 Culculator 的砖红（bg_btn_danger.xml）
        color = KeyPalette.danger,
        contentColor = KeyPalette.text,
        shape = shape,
        modifier = modifier.combinedClickable(
            onClick = { onKey(KeyAction.Backspace) },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onKey(KeyAction.Clear)
            }
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "⌫",
                fontSize = metrics.fontKey,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 长按变体气泡。
 *
 * 照 Culculator 的 `FunctionBubbleView` 来做（那边是传统 View，这里是 Compose）：
 *   * **盖在输入框上面**、水平居中：竖直中心对齐输入框中心，允许遮住输入框
 *     （真机的原话是「小菜单要覆盖显示在输入框上面」）。它只是临时选择器，
 *     弹出的这一下看不见旧式子不要紧 —— 反而不会像以前那样压住按键。
 *     气泡比输入框略高一点（0.135 vs 0.115 屏高），但上下都还有余量：
 *     它占 0.182~0.317，而工具条底边 0.180、选择栏顶边 0.322 ——
 *     所以它既不会碰到上面的工具条，也不会压到下面的选择栏和按键。
 *   * 所有候选项**并排完整显示**，点哪个选哪个，当前正在用的那个高亮。
 *   * 外面套一层**全屏透明遮罩**，点遮罩收起；遮罩**只在气泡可见时存在** ——
 *     一直留着会把整个界面的触摸都吃掉。
 *   * 出现：alpha 0→1、scale 0.8→1.0、220ms、略微过冲；收起用减速插值。
 *   * 弹出的同时给一次震动反馈。
 *
 * 注意 [onPick] 的语义是"把这个键换成那个变体"，**不是**往输入框里插入东西 ——
 * 决定这件事的是调用方（见 MathKeyboard 里的 selections）。
 *
 * 动画跑完才调 [onDismiss] 让调用方清状态，否则气泡会"啪"地消失、看不到收起动画。
 */
@Composable
private fun VariantBubble(
    variants: List<KeySpec>,
    currentLabel: String,
    metrics: WatchMetrics,
    width: Dp,
    height: Dp,
    topOffset: Dp,
    onPick: (KeySpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 0 → 1 的进度：出现和收起都靠它，alpha 和 scale 一起动
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    // 长按的那一刻高亮"这个键现在用的那一个"：
    // 基键 sin 还没换过就高亮 sin，换成 asinh 之后再长按就高亮 asinh
    val selected = currentVariantIndex(variants, currentLabel)

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = BUBBLE_ANIM_MS, easing = BubbleAppear)
        )
    }

    // 收起：先放动画再让调用方把状态清掉。closing 挡住重复触发
    // （比如动画期间连点两下遮罩），否则会清两次状态。
    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = BUBBLE_ANIM_MS, easing = BubbleDismiss)
            )
            onDismiss()
        }
    }

    Box(modifier = modifier) {

        // 全屏透明遮罩：点空白处收起。
        // indication = null —— 遮罩是整屏的，默认的水波纹会在整块表盘上炸开一圈。
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() }
                )
        )

        Surface(
            // 气泡本体照 Culculator 的 bg_log_bubble.xml：近黑底 + 1dp 20% 白描边。
            // 它是靠"描边勾边界"而不是靠 elevation 浮起来 —— 我们原来那点
            // elevation 在纯黑底上其实看不出来，还多一层阴影开销，去掉了。
            color = KeyPalette.bubble,
            contentColor = KeyPalette.text,
            shape = PillShape,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = topOffset)
                .width(width)
                .height(height)
                .border(1.dp, KeyPalette.bubbleBorder, PillShape)
                // 气泡自己吃掉落在它身上的点击（Culculator 的 setClickable(true)）：
                // 落在选项之间的空隙上不该把气泡关掉，只有点**外面**才算点空白。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                // 出现动画：alpha 0→1、scale 0.8→1.0。
                // 支点取顶边中点 —— 气泡盖在输入框上、选择栏和按键都在它下面，
                // 于是它像是顺着输入框往下"长"出来的（和 Culculator 的 pivotY 取 0 一个意思）。
                .graphicsLayer {
                    alpha = progress.value.coerceIn(0f, 1f)
                    scaleX = 0.8f + 0.2f * progress.value
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.keyGap * 0.5f, vertical = metrics.keyGap * 0.5f),
                horizontalArrangement = Arrangement.spacedBy(metrics.keyGap)
            ) {
                variants.forEachIndexed { index, variant ->
                    VariantChip(
                        label = variant.label,
                        selected = index == selected,
                        metrics = metrics,
                        onClick = {
                            // closing 也挡住"收起动画还没放完就再点一下"：
                            // 手表上误触很常见，重复插入一个 asinh() 比什么都不做更烦人
                            if (!closing) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPick(variant)
                                dismiss()
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * 气泡里的一个变体。样式和 [KeyButton] 一样：**胶囊 + 扁平底色**，
 * 选中 = 强调色（`#4B586B`，就是 Culculator 的 `bg_log_option.xml` 选中态），
 * 其它 = 普通键底色（`#2D2F33`）。
 */
@Composable
private fun VariantChip(
    label: String,
    selected: Boolean,
    metrics: WatchMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (selected) KeyPalette.accent else KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                // 最长的短名是 asinh / acosh（5 个字符），用小一号字保证一排四个都完整显示
                fontSize = metrics.fontSmall,
                // 选中与否靠底色区分（Culculator 也是只换底色），字重保持 Normal，
                // 免得圆体胶囊里加粗反而显得挤。
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 表达式显示框。
 *
 * 刻意**不用** TextField：手表上系统输入法完全不可用（弹出来能占满整块屏），
 * 所以我们自己画光标、自己做增删。光标位置由 [cursor] 给定。
 *
 * 卡片**永远只有一行高**（高度由调用方给：FunctionEditor 的 `FIELD_H`，
 * 0.115 屏高 ≈ 26.8dp）—— 真机反馈"输入框下面那行红字提示不要了"，
 * 报错改成点确定之后由调用方（GraphScreen）弹 toast，
 * 所以这里既没有第二行文字，也没有"有错时变高"的第二种高度。
 *
 * [error] 这个参数**保留但不再显示**：留着是为了不改动
 * `FunctionEditorScreen(error = ...)` 的调用方签名 —— 报错信息仍然由状态层
 * 算好、一路传到这里，只是编辑器这一轮不再画它（要恢复内联提示的话，
 * 只需要在这里重新用上它）。
 */
@Composable
fun ExpressionField(
    text: String,
    cursor: Int,
    hint: String,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    focused: Boolean = true,
    onTap: () -> Unit = {},
    error: String? = null,
    /** 圆角形状：调用方按卡片高度算，和同一行的 ⌫ 用同一个。 */
    shape: Shape = MaterialTheme.shapes.small
) {
    val scheme = MaterialTheme.colors
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val caretColor = if (focused) scheme.primary else scheme.outline

    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )

    // 打字时光标基本都在末尾，直接滚到底最省事
    LaunchedEffect(text, cursor) {
        if (cursor >= text.length) scroll.animateScrollTo(scroll.maxValue)
    }

    // 只有一档字号：卡片恒为一行高，不再为"有报错的那一档"缩字号。
    val exprFont = metrics.fontExpr
    // 上下内边距按 0.010 屏高（2.3dp）给：卡片 26.8dp 里减去 4.6dp 还剩 22.2dp，
    // 而 fontExpr（17.5sp）一行约 20.5dp —— 留出 1.6dp 余量。
    // 再大就会顶到卡片边界，字的上/下沿会被 Surface 的圆角裁掉。
    val padV = metrics.sizeDp * 0.010f

    val safeCursor = cursor.coerceIn(0, text.length)
    val before = text.substring(0, safeCursor)
    val after = text.substring(safeCursor)
    val caretHeight = with(density) { (exprFont * 1.15f).toDp() }

    Surface(
        // 输入框卡片也统一到 KeyPalette 的扁平语言：
        // 失焦 = 普通键深灰，聚焦 = 强调色（"这个框正在被输入"一眼可见）。
        // 原来的 surfaceContainerHighest/High 是 MD3 的层级灰，和键盘放一起偏"另一种设计"。
        color = if (focused) KeyPalette.accent else KeyPalette.normal,
        shape = shape,
        modifier = modifier.clickable(onClick = onTap)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.contentPad * 0.6f, vertical = padV),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = hint,
                        fontSize = exprFont,
                        color = KeyPalette.textMuted.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = before,
                        fontSize = exprFont,
                        color = KeyPalette.text,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .width(metrics.caretWidth)
                            .height(caretHeight)
                            .background(caretColor.copy(alpha = blink))
                    )
                    Text(
                        text = after,
                        fontSize = exprFont,
                        color = KeyPalette.text,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
            // 这里**故意没有**第二行报错文字：卡片只有一行高，
            // 报错由调用方在点确定之后弹 toast。
        }
    }
}
