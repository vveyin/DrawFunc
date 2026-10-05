// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// 列表从顶部工具条下面一直排到**屏幕底部**。
//
// ⚠️ 这里只剩"上沿"一个数（占**真实屏高**），底下**不再有 `LIST_BOTTOM`**：
// 手机上暴露的"列表没铺满全屏"就是它 —— 原来 `height = sizeDp × (0.95 − 0.245)`
// 里的 `sizeDp` 是**短边**（411dp 宽的手机上就只有 411dp），于是列表在真机
// 891dp 高的屏上只排到 y ≈ 357dp，下面 534dp 全是空的；而且改一行高度还要
// 两边一起改。现在改成"从工具条下面一路铺到底"（见 [ScreenGeometry.listHeight]），
// 任何屏幕比例都自动成立，不需要新常数。
//
// ⚠️ 这个数在 `FunctionsScreen.kt` 里**另有一份同值的副本**（那边顶部那两颗按钮
// 必须和本屏的 ⚙ / + 落在同一行），而且**不能**改成 internal 共用：
// `AlphabetKeyboard` / `SavesScreen` / `SettingsScreen` 里各有一个同名的
// `private const val LIST_TOP`，同一个包里再出一个 internal 的就会撞名报错。
private const val LIST_TOP = 0.245f
/** 单行的高度（占**屏宽** —— 行宽也是按屏宽算的，两者必须同一个基准，见 [ScreenGeometry.rowWidth]）。 */
private const val ROW_H = 0.15f

/**
 * 行与行之间的竖直间距（占屏宽）。
 *
 * 为什么跟**屏宽**而不是屏高走：行高是屏宽的 0.15，间距若按屏高算，
 * 手机这种长条屏上"行高不变、间距却翻倍"，列表会变得稀稀拉拉。
 * 和行高同一基准之后，列表的疏密在所有比例下都是同一套观感。
 */
private const val LIST_GAP = 0.018f

// ---------------------------------------------------------------------------
// 真实屏幕尺寸（不再是"短边"）
//
// `WatchMetrics` 的一切都建立在**短边** `sizeDp` 上（那是圆表的安全区基准，
// 也是整个 App 字体/线宽/表冠系数的基准）。方表上短边 == 屏高、圆表上短边 == 直径，
// 所以"按短边算"在手表上一直是对的；手机上短边是**屏宽**，于是：
//   * 写成"屏高百分之几"的那些偏移（工具条、列表上下沿、撤销条、调色板）全部被压到
//     顶部一个方格里；
//   * 行宽 0.90 × 短边 在 411dp 宽的手机上只剩 370dp，而屏宽 411dp —— 看起来是一根窄柱。
//
// 所以这两屏自己量一次真实可用区域（`BoxWithConstraints` 的约束），
// 把"按屏幕比例写的"和"按控件大小写的"两件事分开：
//   * 比例量（工具条位置、列表上沿、撤销条、浮层位置）→ 按真实屏高/屏宽换算；
//   * 控件尺寸（行高、按钮、圆点、内边距、字号）→ 继续走 `metrics.sizeDp`，
//     也就是继续按短边缩放 —— 这样控件的大小仍随设备短边变化，
//     **不会**在长条屏上被拉成一块巨大的方块。
//
// ⚠️ 为什么不干脆把 [WatchMetrics.sizeDp] 改成"真实屏高"：那是**共享**类型，
// 字体（`sp(0.042f)`）、线宽、键盘行高、`Viewport` 都吃它，改一下整个 App
// （包括我本轮不许动的那些文件）会一起变形。所以只在**这两屏**里加这一层换算。
// ---------------------------------------------------------------------------
/** 水平方向上的安全余量（占屏宽）：屏宽减去左右各这一份，才是可以铺满的宽度。
 *  `internal` 而不是 private：这一条**两屏共用**（第二屏的 chip 行、顶部按钮、
 *  属性页卡片都要和第一屏的行同一个口径），所以它在文件外也被引用。 */
internal const val ROW_SIDE_INSET = 0.03f
/** 列表底部留的余量（占屏宽）：既让最后一行不贴着屏幕边，也躲开系统手势条。
 *  两屏共用（`FunctionsScreen` 引用它算列表高度）。 */
internal const val LIST_BOTTOM_PAD = 0.05f
/**
 * （这里原来有一条 `UNDO_BOTTOM_PAD = 0.06f`：第一屏底部那条「已删除 · 点此撤销」
 *  胶囊离屏幕底边的距离，后来搬成了 `Common.kt` 的 `FLOAT_PILL_BOTTOM_PAD`。
 *  **本轮那条撤销条被用户要求整套删掉**，`FLOAT_PILL_BOTTOM_PAD` 也随之删除 ——
 *  这一屏底部现在没有任何浮层，所以这里只留下一句说明，不再有常量。）
 */

/**
 * 这两屏的真实几何：**整个屏幕**（不是短边）的 dp / px 尺寸。
 *
 * 每个成员都写成"真实值优先、拿不到约束时退回 [WatchMetrics] 的短边"，
 * 所以在 `@Preview` 那种没有约束的场合也不会算出 0。
 *
 * `internal` 而不是 `private`：`FunctionsScreen.kt`（第二屏）要用同一套换算 ——
 * 那里也有一批"按屏高百分比写的位置"和"按短边算的行宽"。这个类型**不 export 到
 * 别处**（除了那两屏 + 它们各自的行组件），所以它不是新的公共 API。
 */
internal class ScreenGeometry(
    val screenW: Dp,
    val screenH: Dp,
    private val density: Density
) {
    /** 真实屏高 × f（dp）。用来算"占屏高百分之几"的那些位置。 */
    fun sy(f: Float): Dp = screenH * f

    /** 真实屏宽 × f（dp）。 */
    fun sx(f: Float): Dp = screenW * f

    /** 真实屏高 × f（px）。手势阈值用的是像素。 */
    fun syPx(f: Float): Float = with(density) { (screenH * f).toPx() }

    /** 非圆屏上"铺满可用宽度"以后的行（占屏宽的比例），见 [ScreenGeometry.rowWidth]。 */
    private fun fullWidthFraction(): Float {
        val inset = sx(ROW_SIDE_INSET).value
        return if (screenW.value <= 0f) 1f else (screenW.value - inset * 2f) / screenW.value
    }

    /**
     * 列表可视区高度 = **从列表上沿到屏幕底部**（只留下边距）。
     *
     * 这是"铺到屏幕底部"这句用户要求的落点：不再有 `LIST_BOTTOM` 那种"屏高百分比"
     * 截断，屏幕上还剩多少高度就吃多少。`coerceAtLeast` 是给极端短屏兜底的，
     * 免得算出负高度让 `LazyColumn` 直接崩。
     */
    fun listHeight(metrics: WatchMetrics, listTop: Dp): Dp =
        (screenH - listTop - sx(LIST_BOTTOM_PAD)).coerceAtLeast(metrics.sizeDp * 0.2f)

    /**
     * 一行（或工具条、浮层）**实际**该有多宽。
     *
     * 圆屏：`metrics.sizeDp` × 弧宽比例 —— **一个字节都没改**（这一支是圆表专用的，
     * 弧宽公式、`derivedStateOf` 量化、行高都在别处原样保留）；
     * 非圆屏（手机 / 方表）：**铺满屏宽减左右各 [ROW_SIDE_INSET]**，不再被短边压成窄柱。
     */
    fun rowWidth(metrics: WatchMetrics, ringFraction: Float, isRound: Boolean): Dp =
        if (isRound) metrics.sizeDp * ringFraction else screenW - sx(ROW_SIDE_INSET) * 2f

    /**
     * 圆屏给弧宽比、非圆屏给 [fullWidthFraction]。
     *
     * 只在"要的是**一个比例**而不是一个 dp 宽度"的地方用（例如将来有人要拿它去乘
     * 别的基准）；行宽本身一律走 [rowWidth]，别把这两条路混着用。
     */
    fun widthFraction(isRound: Boolean, roundFraction: Float): Float =
        if (isRound) roundFraction else fullWidthFraction()
}

/** 量一次真实尺寸。`maxWidth` / `maxHeight` 在 `BoxWithConstraints` 里是 `Dp`，直接用。 */
@Composable
internal fun rememberScreenGeometry(maxWidth: Dp, maxHeight: Dp, metrics: WatchMetrics): ScreenGeometry {
    val density = LocalDensity.current
    return remember(maxWidth, maxHeight, density, metrics.sizeDp) {
        ScreenGeometry(
            screenW = if (maxWidth > 0.dp) maxWidth else metrics.sizeDp,
            screenH = if (maxHeight > 0.dp) maxHeight else metrics.sizeDp,
            density = density
        )
    }
}

// ---- 顶部工具条（⚙ / + 两颗按钮）的几何 ----
//
// 这一行**不滚动**，所以它是全文件唯一"行在哪"可以写死的地方；但**宽度照样按圆屏算**，
// 见 [toolbarWidthFraction]。和「功能界面」那一屏用的是同一套数（那边的两颗按钮
// 就摆在完全相同的位置上），改这里必须两边一起改。
/** 工具条行顶（占屏高）。 */
private const val TOOLBAR_TOP = 0.10f
/** 工具条行高（占屏高）。0.125 × 233dp ≈ 29.1dp。 */
private const val TOOLBAR_H = 0.125f
/**
 * 工具条那一条的**下沿**（占**真实屏高**）= [TOOLBAR_TOP] + [TOOLBAR_H] = **0.225**。
 *
 * 它只有**一个**使用者：**切屏手势的起手判据** —— 起手点落在这一条高度带里时，
 * "左边缘往右滑进功能界面"不算数（那一条让给工具条上的撤销菜单手势）。
 * 用户原话："在右边缘手势的起手判据里把工具条那一段（**屏高 10%~22.5%**）排掉。"
 * （这一屏与第二屏**同一个口径**，只是这一屏让的是左边缘那条。）
 *
 * 为什么由 `TOOLBAR_TOP + TOOLBAR_H` 现推：这条带就是工具条那两个常量描述的上下沿，
 * 自己再写一个 0.225 迟早会漂；圆表上 `screenH == sizeDp`，所以算出来的带
 * 与工具条的真实上下沿**逐像素相等**，非圆屏（手机）按真实屏高取 22.5%。
 */
private const val TOOLBAR_BOTTOM = TOOLBAR_TOP + TOOLBAR_H
/**
 * 图标按钮的边长占屏比 —— **必须等于 `WatchMetrics.iconButton` 的 0.18**。
 * 单独抄一份是因为宽度公式要按"按钮的真实上下沿"算，而 [WatchMetrics] 那边
 * 只暴露了 dp 值；抄错的后果是宽度收得太少、按钮角被表壳切掉（有注释提醒）。
 */
private const val ICON_BTN_H = 0.18f

// ---- 入场（只在首屏第一次出现时播一次）----
/** 单行淡入 + 上移的时长。220ms 内走完，滚动手感不会被拖住。 */
private const val ROW_IN_MS = 220
/** 每行比上一行晚 45ms —— 交错感来自这里，差值再大就显得磨蹭了。 */
private const val STAGGER_MS = 45
/** 上移距离 = 行高的 5%（466px 屏上约 3.5px ≈ 1.8dp）：只做"轻轻抬一下"。 */
private const val ROW_IN_SLIDE = 0.05f
/** 「示例」区淡入延迟的上限：函数多的时候也不会让示例库等太久。 */
private const val TAIL_DELAY_MAX_MS = 260
/** 顶部工具条（⚙ / +）的入场时长：比列表行略快一点，先它站稳、行再依次跟上。 */
private const val TOOLBAR_IN_MS = 180

// ---- 行的"被扔掉"（左滑删除确认之后）----
//
// ⚠️ 这三个数（时长 / 甩出距离 / 缩小比例）**已经搬到共用件里**：
// `PlotMetrics.DELETE_OUT_MS` / `DELETE_OUT_SHIFT` / `DELETE_OUT_SHRINK`。
// 原来函数行和对象行各有一份（这里叫 `DELETE_OUT_*`、`FunctionsScreen.kt` 里叫
// `DELETE_OBJECT_*`），值虽然一样，但两份常量迟早会漂 —— 而用户不看常量表、
// 只看"函数能删、别的对象不能删"。现在两行调同一个
// [rememberRowSwipeDelete]，动画也只有一份。

// ---- 圆点脉冲 ----
private const val DOT_PULSE = 1.35f
private const val DOT_PULSE_UP_MS = 90
private const val DOT_PULSE_DOWN_MS = 130

// ---- 整行按压 ----
private const val ROW_PRESS_SCALE = 0.03f
private const val ROW_PRESS_DIM = 0.10f

// ---- 手势 ----
/**
 * 删除手势的**位移量**（往左滑够行宽的 1/3 才删）。
 *
 * ⚠️ 这个常量**已经搬到共用件里**（`PlotMetrics.SWIPE_DELETE_TRAVEL`），
 * 因为对象行要用**同一个数** —— 两行各写一个 1/3 和 0.3333f 正是"函数能删、
 * 别的对象不能删"那类漂移的温床。判定函数也是共用的
 * （`PlotMetrics.swipeDeleteTravelPx` / `swipeDeleteReached`）。
 *
 * 真机历史（保留在这里，因为它是"起手位置不许再限制"这条结论的来处）：
 * 起手位置**不再有限制**（原来这里还有一个 `SWIPE_START_ZONE = 2/3`）。
 * 用户真机反馈："滑动删除函数的功能又没了，现在根本划不动" ——
 * 要求"必须从右侧 1/3 那条窄带起手"就是划不动的原因：在行中间或文字上起手时，
 * 事件被静默丢弃、一点反馈都没有。现在只按**方向**区分两个手势
 * （往左 = 删除并消费事件；往右 = 完全不管，让给外层的"左边缘右滑"）。
 */

// ---- 行的宽度（圆弧缩放）----
/**
 * 圆屏上允许的**最宽**行宽（同时是"算不出弧宽时"的兜底值）。
 *
 * 圆屏没有"越靠边越窄"的问题，所以弧宽公式的结果被夹在
 * [MIN_ROW_WIDTH] ~ 这个值之间，列表从上到下自然形成"中间宽、两头窄"。
 *
 * ⚠️ **非圆屏不再用这个值**：那一支现在由 [ScreenGeometry.rowWidth] 直接给
 * "屏宽 − 左右各 [ROW_SIDE_INSET]"，不再是"短边 × 0.90" —— 手机上行宽被短边
 * 压成一根窄柱就是它造成的。这个常数在非圆屏上唯一剩下的用处是
 * [rowWidthFraction] 里"这一项还没被测到"时的兜底（下一帧就有真值，
 * 而兜底值随屏宽走，不会闪出一个比例不对的窄行）。
 */
private const val SQUARE_ROW_WIDTH = 0.90f
/** 圆屏上允许的最窄行宽：再窄下去文字就没法看了。 */
private const val MIN_ROW_WIDTH = 0.52f
/** 用满圆内宽度时留一点余量，免得行角正好压在表壳边缘上。 */
private const val ARC_MARGIN = 0.95f

/**
 * 顶部下滑返回的判定**阈值**（占**短边**）与手势区**高度**（占**真实屏高**）。
 *
 * ## 两个数为什么不是一个基准（别顺手"统一"它们）
 *
 *   * [SWIPE_DOWN_THRESHOLD] 是**触发距离**：从上往下搓够 `0.10 × 短边`
 *     （圆表 ≈ 23.3dp、手机 ≈ 41.1dp）才回图像界面。它跟短边走是**有意的** ——
 *     和第二屏 `FunctionsScreen` 的 `SWIPE_DOWN_THRESHOLD`（0.09 × 短边）同一个量级，
 *     两屏"要搓多远"因此一样。
 *   * [SWIPE_DOWN_ZONE_H] 是**起手热区的高度**：这个数表达的是"屏幕高度的百分之几"，
 *     所以必须按**真实屏高**算（见下面 `.height(geometry.sy(SWIPE_DOWN_ZONE_H))`）。
 *
 * ⚠️ 热区这一处原来写的是 `metrics.sizeDp * SWIPE_DOWN_ZONE_H`（**短边**）：手表上
 * 短边 == 屏高，所以一直没暴露；手机上短边是**屏宽**，热区只有真实屏高的 9.7%。
 * 而第二屏上一轮已经改成按真实屏高算了 —— 于是"两屏统一"当时只做了一半。
 * 现在两屏都是 `geometry.sy(0.20f)`：
 *
 * | 设备 | 短边 | 真实屏高 | 改前（0.20 × 短边） | 改后（0.20 × 真实屏高） |
 * |---|---|---|---|---|
 * | 圆表 466px / d2 | 233dp | 233dp | 46.6dp | **46.6dp（逐像素不变）** |
 * | 方表 400px / d2 | 200dp | 200dp | 40.0dp | **40.0dp（逐像素不变）** |
 * | 手机 411 × 850dp | 411dp | 850dp | **82.2dp（仅真实屏高 9.7%）** | **170.0dp（20%）** |
 *
 * 圆表/方表上 `geometry.screenH == metrics.sizeDp`（见 [rememberScreenGeometry]：
 * `screenH = if (maxHeight > 0.dp) maxHeight else metrics.sizeDp`，而"短边 == 屏高"
 * 的屏上这两个值本来就相等），所以这两支上换算式**一个像素都不产生**差异。
 */
private const val SWIPE_DOWN_THRESHOLD = 0.10f
private const val SWIPE_DOWN_ZONE_H = 0.20f

// ---- 左边缘往右滑 → 进「功能界面」（#9 的入口手势）----
/**
 * 左边缘热区的宽度（占屏宽）。
 *
 * **0.26 × 233dp ≈ 61dp**：原来是 0.20（47dp），真机上用户反馈"左滑进功能界面滑不出来"。
 * 两个原因一起改掉了：
 *   1. 热区太窄 —— 圆表上从边上搓一下很容易偏出去；
 *   2. **热区被 `LazyColumn` 压在下面**（它排在列表前面 = z 序更低），
 *      而列表在竖直方向上是滚动消费者；所以这一块挪到了 Box 的**最后**
 *      （见下面那段注释），现在它在最上层，水平拖动会先到它手里。
 * 加宽到 0.26 仍然不会吃掉列表行的左端（行居中放、最宽 0.90 屏宽，
 * 左端离屏幕边还有 0.05 屏宽；而且它只消费**水平拖动**，正常的点击照旧穿透到行上）。
 *
 * 竖直范围也顺势放宽到 0.12~0.92：让"从左边任意位置往右搓"都成立，
 * 只避开最上面那条（下滑回图像）和最下面那条（上滑回图像 + 撤销条）。
 */
private const val SWIPE_EDGE_W = 0.26f
private const val SWIPE_EDGE_TOP = 0.12f
private const val SWIPE_EDGE_BOTTOM = 0.92f

/** 进功能界面的判定阈值（占屏宽）。0.09 × 233dp ≈ 21dp，短促一划就够。 */
private const val SWIPE_TOOLS_THRESHOLD = 0.09f

/**
 * 表冠一格对应滚动多少像素。
 *
 * 这个系数取决于设备上报的像素量级（OPPO 表的表冠一次事件给的是"滚轮像素"，
 * 量级约几十到一百多）。1.0 倍在这种量级下大约一格滑动半屏，手感刚好；
 * 真机上如果觉得太灵敏或太钝，只改这一个数就行。
 */
private const val ROTARY_SCROLL_FACTOR = 1.0f

/**
 * 算出某一项**当前**应该有多宽（占屏宽的比例）。
 *
 * 圆屏上"越靠中间越宽"，所以行宽必须跟着它在屏幕上的**实际竖直位置**走，
 * 而不是按序号算 —— 后者一滚动就不对了。
 *
 * 性能上有个关键点：这里用 `derivedStateOf` 包了一层，并且把结果量化到
 * 百分位。`LazyListState.layoutInfo` 在滚动时每帧都在变，直接读它会让
 * 每个可见行每帧都重组；包一层之后只有"算出来的宽度真的变了"才会重组
 * （滚动一整屏也就变几十次）。
 */
@Composable
private fun rowWidthFraction(
    listState: LazyListState,
    itemKey: Any,
    listTopPx: Float,
    screenPx: Float,
    metrics: WatchMetrics,
    isRound: Boolean
): Float {
    // 非圆屏（手机 / 方表）：宽度不在这里算 —— 由 [ScreenGeometry.rowWidth] 直接给
    // "铺满屏宽减左右余量"。这里返回的 1f 只是个"占位"（调用方在非圆屏上会拿它当
    // "忽略这个比例"的信号），见 `Row` / `FunctionRow` / `ToolSectionBlock` 里
    // 对 `geometry.rowWidth(...)` 的用法。
    if (!isRound) return 1f
    val fraction by remember(itemKey) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
            // 这一项还没被测到（第一次组合那一帧）：弧宽公式**在 0.5 屏高处的值**
            // 就是全宽，乘余量后正是这个上限 —— 用它兜底，下一帧就有真值了，
            // 中间不会闪一条比正常行更宽（= 可能被表壳切角）的行。
            if (info == null) {
                return@derivedStateOf (kotlin.math.round(ARC_MARGIN * 100f) / 100f)
                    .coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
            }
            val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx
            val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
            (kotlin.math.round(raw * 100f) / 100f)
                .coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
        }
    }
    return fraction
}

/**
 * 顶部工具条（⚙ / +）这一对的宽度（占屏宽）。
 *
 * 规则和列表行**完全同一套**（[rowWidthFraction]）：圆屏按该高度处的圆内宽度收窄、
 * 乘 [ARC_MARGIN] 留余量、夹在 [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间。
 *
 * ⚠️ **非圆屏（手机 / 方表）不再用短边算**：这一支返回 1f 当占位，真实宽度由
 * [ScreenGeometry.rowWidth] 给"铺满屏宽减左右余量" —— 手机上原来那行按钮只占
 * 屏宽的 90%（还是按**屏宽**算出来的 0.90 × 411dp = 370dp），和列表行的口径对不上。
 * 现在这两处的口径一致：圆屏两处都走弧宽、非圆屏两处都走满宽。
 *
 * 取的是这一对按钮**上下两端里更窄**的那个，而不是行中心 —— 按钮是**方块**
 * （`MaterialTheme.shapes.small`，圆角很小），它的上边两个角比行中心更靠近圆的外沿。
 * 按行中心算（那是 [rowWidthFraction] 对"一行文字"的做法）会让角落到表壳外面：
 * 233dp 基准上中心 0.1625 处 f = 0.7379，行宽 163dp，按钮外沿落在 x = 34.9dp，
 * 上角到圆心 √(81.65² + 93.2²) = 123.9dp > 半径 116.5dp —— 圆屏上那一小块角
 * 是真的看不见的（用户说的"按键没有自动适配屏幕宽度"）。
 *
 * 高度取 `max(TOOLBAR_H, ICON_BTN_H) / 2`（= 0.09）而不是只用 `TOOLBAR_H / 2`：
 * 图标按钮是 `Modifier.size(...)`，`size()` 会被**行约束**夹住，所以按钮的真实
 * 上下沿只会落在"行高"和"按钮高"这两个范围之内。按更大的那个算，两种情况下
 * 都在圆内 —— 不会因为换了 Compose 版本、约束行为变了就切角。
 *
 * 233dp 圆屏的实算：范围 y ∈ [0.0725, 0.2525]，f(0.0725) = 0.5186、
 * f(0.2525) = 0.8689 → 取小的那个 × 0.95 = 0.4927，被 [MIN_ROW_WIDTH] 抬到 **0.52**
 * → 行宽 **121.2dp**：两颗按钮各 0.18 × 233 = 41.9dp，中间还剩 **37.3dp** 空当
 * （左右对称，足够分别点中）；右端落在 76.0% 屏宽。
 */
private fun toolbarWidthFraction(metrics: WatchMetrics, isRound: Boolean): Float {
    // 非圆屏：宽度交给 [ScreenGeometry.rowWidth]（铺满屏宽减左右余量），这里只要个占位。
    if (!isRound) return 1f
    val center = TOOLBAR_TOP + TOOLBAR_H * 0.5f
    val halfSpan = maxOf(TOOLBAR_H, ICON_BTN_H) * 0.5f
    val raw = minOf(
        metrics.circleWidthFraction(center - halfSpan),
        metrics.circleWidthFraction(center + halfSpan)
    ) * ARC_MARGIN
    return raw.coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
}

/**
 * 函数列表 + 示例库。**这是首屏**（用户明确要求第一次打开就是列表）。
 *
 * 按用户要求，这一屏**不写标题**，也**不放删除/编辑按钮**，全靠手势：
 *
 * | 手势 | 效果 |
 * |---|---|
 * | 点一行 | **编辑这个函数的表达式**（进函数编辑器） |
 * | **长按一行** | **进属性编辑界面**（#4：名称 / 线型 / 线径） |
 * | **任意位置往左滑够 1/3 行宽** | 删除这一行（函数 / 对象 / 度量**同一套**）—— 撤销走工具条上那对箭头，删完弹一条纯提示的 toast（本轮起**不再**有底部「点此撤销」条） |
 * | **滑动条行：滑轨和播放键以外的地方**往左滑够 1/3 行宽 | 同样删除这一行（#3）—— 滑轨上拖动仍然只改参数值、**绝不删除**，见下一条 |
 * | 上部往下滑 | 回图像 |
 * | 点左上角 ⚙ / 右上角 + | 设置 / 新建函数 |
 * | **在顶部工具条那一条上滑动，从 ⚙ 滑到 `+`（或反过来）** | **唤出 / 收起撤销菜单**（中间浮出一对箭头键：← 撤销、→ 重做，本轮新增）—— 判据是"手指真的进了另一颗键的矩形"，随便横拖一点不算 |
 * | **长按左上角 ⚙** | **应用信息页**（本轮新增：应用图标 / 名字 / 版本号 / Version Code / 开发信息，无标题） |
 * | 长按右上角 + | 把当前全部函数信息存成一个文件（浮出命名框） |
 * | 长按圆点 | 调色板 |
 * | 短按对象行文字区 | **进详情页**（只读：颜色 / 名字 / 完整数据，本轮新增）—— 列表里放不下的长数值在这一屏显示完全 |
 * | 长按对象行文字区 | 属性屏（**可改名字**；和函数行**同一条**回调 `onProperties(id)`） |
 * | 长按滑动条行 | 滑动条的属性编辑界面（#4：名称 / 下界 / 上界 / 步长） |
 *
 * ⚠️ 那条"左滑删除"**没有起手位置限制**（曾经写过"必须从右侧 1/3 起手"，那是
 * "根本划不动"的来处；对象行上一版还多让了一条右边带出去，见
 * `FunctionsScreen.GraphObjectRow` 里那段真因）。三行现在调的是同一个共用件
 * （`DragGestures.rememberRowSwipeDelete`）。
 *
 * ⚠️ **唯一**的例外是滑动条行 —— 它多传一个 `skipIfDownConsumed = true`：
 * 那一行的横向拖动本来就有主人（**滑轨上拖动 = 改参数值**，用户明确要求
 * "在滑轨上拖动永远不会误删"）。所以它的起手区**不是按 x 坐标切的**，
 * 而是"谁在手指底下谁说了算"：按在滑轨或播放键上 → 那两块地方自己消费 down →
 * 删除手势根本不开始（不消费、不跟手、松手也不删），拖动整串留给改值；
 * 按在参数字母/当前值徽标、下界/上界文字、内边距上 → 没人消费 down →
 * 删除手势照常起手，左滑稳定删除。
 * 完整的划分表、为什么不能按 x 切、以及三块屏上的实际 dp 数写在
 * `PlotMetrics` 末尾那一节（`SLIDER_*` / `sliderPlayKey*`）。
 *
 * 短按 = 改式子、长按 = 改外观/改参数，这个分工是用户需求 #4 定下来的：
 * 编辑表达式是最高频的动作，必须一击即中；属性界面是"偶尔调一次"的，
 * 放在长按上既不会误触，也不用在行里再塞一个按钮（1.5 寸屏上塞不下）。
 *
 * 行的宽度按圆屏圆弧动态缩放：越靠屏幕中心越宽，越靠上下两端越窄。
 * 方屏没有这个问题，直接给接近满宽。
 *
 * 示例库放在这一层而不是编辑器里：手表上打字是最贵的操作，
 * 能"点一下就有图看"的入口必须放在最外层。
 */
@Composable
fun FunctionListScreen(
    functions: List<PlotFunction>,
    errors: Map<Long, String>,
    selectedId: Long,
    metrics: WatchMetrics,
    onEdit: (Long) -> Unit,
    /**
     * 长按一行 —— **函数和几何对象共用这一个入口**：进属性界面（#4）。
     *
     * 点一行仍然是 [onEdit]（函数编辑器）；**对象行的短按不做事**（本轮按用户要求
     * 把"短按唤出对象菜单"那套删掉了，只留长按）。
     * 对象 id 与函数 id 由同一个 `GraphState.nextId()` 发出、全局唯一，
     * 所以两边共用这一条回调不会撞车 —— 分辨"是谁"是路由那一侧（`GraphScreen`）的事。
     */
    onProperties: (Long) -> Unit,
    /**
     * **短按**一个几何对象行 —— 进**详情页**（用户本轮新增的那一屏）。
     *
     * 用户原话："给除了函数之外点击没有菜单的加一个点击显示详情页面，因为有些点和直线
     * 里面数值太长了，在这个详情页面只显示这个点的颜色，名字和它的坐标，直线就显示
     * 它的解析式，显示完全。"
     *
     * ⚠️ 只有**对象行**有这条回调：函数行的短按仍然是"改表达式"（[onEdit]）——
     * 用户说的是"除了函数之外"。对象 id 与函数 id 共用一个号段，
     * 所以这里同样只把 id 交出去，"这是函数还是对象"由路由那一侧分辨。
     */
    onObjectDetails: (Long) -> Unit,
    onToggleVisible: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit,
    onAddPreset: (Preset) -> Unit,
    onSettings: () -> Unit,
    /**
     * **长按** ⚙ 设置键的动作（本轮新增）：打开**应用信息页**（`Route.About`）。
     *
     * 用户原话："给长按设置键加个长按 `+` 键同款动画，线圈颜色用比设置亮一号颜色的灰色。
     * 长按之后打开应用信息页面（不要有标题）……"
     *
     * 于是这颗键变成一个"两个动作"的键，和 `+` 键**逐字同构**：
     *
     * | 手势 | 结果 |
     * |---|---|
     * | **短按** ⚙ | 进设置页（[onSettings]，**一个字都没改**） |
     * | **长按** ⚙ | 线圈（灰色 [KeyPalette.normalBright]）逆时针转满一圈 → 进应用信息页（本回调） |
     *
     * 线圈的时长**没有另定一个值**：这里和 `+` 键一样**不传** `holdMillis`，
     * 走 [HoldToConfirm] 的同一个默认值（900ms）。改那个默认值 = 两颗键一起改。
     */
    onLongPressSettings: () -> Unit,
    onSwipeDown: () -> Unit,
    /**
     * 工具条上那对箭头键（能按吗）—— false 时对应的键**变暗**。
     *
     * 撤销在本轮起**只有这一条路**：底部那条 2 秒「已删除 · 点此撤销」被用户要求
     * 整套删掉了（连同 `showUndo` / `onUndo` 两个参数）。删除动作现在只做两件事：
     * `GraphState` 在改动前 `pushUndo` 记一步、再弹一条**纯提示**的 toast。
     */
    canUndo: Boolean,
    canRedo: Boolean,
    /** 左箭头按下：撤销一步（界面层负责弹 toast）。 */
    onUndoStep: () -> Unit,
    /** 右箭头按下：重做一步。 */
    onRedoStep: () -> Unit,
    onColor: (Long, Int) -> Unit,
    /**
     * 长按 `+` 的动作。
     *
     * **原来是"清空全部函数"**，用户需求 #8 明确把它改成了"把当前图像保存成文件"
     * （提示框 + 命名输入框 + 取消/确定）。清空能力没丢：功能界面右上角那个
     * 垃圾桶（清屏）接管了它 —— 点一下清几何对象，**长按清掉全部**。
     */
    onLongPressAdd: () -> Unit,
    showPresets: Boolean,
    /** 当前坐标系配套的示例（笛卡尔/极坐标各一套，见 GraphState.presetsFor）。 */
    presets: List<Preset>,
    /** 滑动条（#3）。每一条对应一个可绑定参数 a/b/c/d。 */
    sliders: List<Slider>,
    onSliderValue: (Long, Double) -> Unit,
    onSliderPlayToggle: (Long) -> Unit,
    onSliderEdit: (Long) -> Unit,
    /**
     * 滑动条行左滑删除（#3）。
     *
     * 走的仍然是**同一条**撤销路：`GraphState.removeSlider` 在改动之前压一步快照
     * （函数 / 滑动条 / 几何对象 / 绑定表 / 对称规则全在里面），所以用工具条上的左箭头
     * 撤回来之后，那条滑动条的参数名/上下界/步长/取值/颜色/显隐**全部回到原样**。
     * 本屏**不自己记一份备份**（原来那套 `lastDeletedSlider` + 底部 2 秒撤销条
     * 本轮被用户要求删掉了），删除后只弹一条纯提示的 toast。
     */
    onSliderDelete: (Long) -> Unit,
    // ---- 几何对象（#9）：度量/描点/作图的结果也要在列表里看得见 ----
    /** 工具画出来的点/线/度量。变换出来的曲线折线碎片已经在外面过滤掉了。 */
    objects: List<GraphObject>,
    onObjectColor: (Long, Int) -> Unit,
    onObjectToggleVisible: (Long) -> Unit,
    /**
     * 切"数值是否在图像界面显示"（只对度量有意义）。
     *
     * ⚠️ 本轮删掉对象属性浮层之后，这个参数在本屏里**已经没有使用者**了
     * （`123` 那颗按钮走 `FunctionsScreen.LocalObjectShowValueToggle`，因为它挂在
     * `GraphObjectRow` 里，而那一行的调用参数是别人负责的文件定的）。
     * **签名故意留着**：调用点在 `GraphScreen.kt`，删掉它那边立刻编译不过；
     * 而且属性屏（同一个 `Route.Properties(id)`）之后大概率还要用它。
     */
    onObjectToggleValue: (Long) -> Unit,
    onObjectDelete: (Long) -> Unit,
    /** 左边缘往右滑：进「功能界面」（#9）。 */
    onSwipeToTools: () -> Unit,
    /** 表冠/滚轮事件。在这个界面它**只滚动列表**，不缩放图像。 */
    rotary: Flow<Float>
) {
    val scheme = MaterialTheme.colors
    val density = LocalDensity.current
    val isRound = LocalConfiguration.current.isScreenRound
    val listState = rememberLazyListState()

    // ---- 真实可用尺寸（不是短边）----
    //
    // 这些量必须在**外层**算好（下面那些 `Modifier` 的偏移/高度都要用它们），
    // 所以整个界面套在这个 `BoxWithConstraints` 里。`maxWidth` / `maxHeight`
    // 是"这一屏实际拿到多少地方"，手机上就是 411×891dp 那一整块。
    // 详见 [ScreenGeometry] 上面那段说明。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)

        val rowHeight = metrics.sizeDp * ROW_H
        val listTopPx = with(density) { geometry.sy(LIST_TOP).toPx() }
        val swipeDownPx = geometry.syPx(SWIPE_DOWN_THRESHOLD)
        val swipeToolsPx = with(density) { (metrics.sizeDp * SWIPE_TOOLS_THRESHOLD).toPx() }

        // 入场只播一次，而且**永远**只播一次。
        // 状态放在屏幕这一层（而不是每行自己 remember）：LazyColumn 的行会随滚动
        // 销毁重建，状态留在行内的话每次滚回来都会重播，来回滚两下就一片闪烁。
        // 用 rememberSaveable：列表是首屏，看完图像按返回不该再重播一遍。
        var entered by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        // 长按圆点要调色的那一个：函数和几何对象共用同一个调色板。
        // 用 sealed 而不是两个 `Long?` 状态，是为了保证"同一时刻只有一个调色板"——
        // 两个独立状态会出现"两个都非空、两层面板叠着"的情况。
        var colorPickFor by remember { mutableStateOf<ColorTarget?>(null) }

        // ⚠️ 这里原来有一个 `objectPropsFor` 状态（本屏自己的"对象属性浮层"由它开着）。
        // 这一轮它**连着入口一起删掉了**：用户要求把"点/标量短按唤出菜单"这套整个去掉
        // （原话："改成点击唤出菜单了，把这个菜单去掉啊不是让你换个手势"），
        // 于是对象行的文字区**短按什么都不做**、**长按**和函数行走同一条路 ——
        // `onProperties(obj.id)` → `GraphScreen` 那一侧分辨函数/对象并落到属性屏。
        // 所以这一屏不再需要任何"属性浮层开给谁"的本地状态。

        // `+` 键那一串手势有没有被长按吃掉（用户报的"长按保存文件时又开了添加函数页面"）。
        // 闩本身和"为什么两道锁"都写在 HoldRing.kt；这里只负责在 onClick 里问它一句。
        val addHoldLatch = rememberHoldLatch()

        // ⚙ 键那一串手势有没有被长按吃掉（本轮新增）。
        //
        // 和 `+` 键**各用各的闩**，不能共用一个：两颗键是独立的手势（用户完全可能
        // 先长按 ⚙、再短按 `+`），共用一个闩的话"长按成立"这件事会跨键泄漏 ——
        // 表现就是"长按过设置键之后，点 `+` 没反应"（`HoldToConfirm` 只在**自己**的
        // 那次按下时把闩归位，跨键的那一次没人归位）。
        val settingsHoldLatch = rememberHoldLatch()

        // 顶部工具条的入场进度：和列表行共用同一个 `entered`，只是不等交错延迟。
        val topIn by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(durationMillis = TOOLBAR_IN_MS, easing = FastOutSlowInEasing)
        )

        // 表冠 / 滚轮：在这个界面只用来滚动列表。
        // （图像界面的缩放由 GraphScreen 里的收集器负责，两边互斥，见那边的注释。）
        LaunchedEffect(rotary) {
            rotary.collect { amount ->
                listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
            }
        }

        val tailDelay = (functions.size * STAGGER_MS).coerceAtMost(TAIL_DELAY_MAX_MS)
        val tailAlpha by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(
                durationMillis = ROW_IN_MS,
                delayMillis = tailDelay,
                easing = FastOutSlowInEasing
            )
        )

        // ---- 工具条那一条的宽度（撤销菜单"让位"的距离按它算）----
        // 和下面 Box 用的**是同一个值**（不是又算了一遍）：圆屏 = 这一对按钮所在
        // 高度的弧宽比例 × 短边、非圆屏 = 铺满屏宽减左右各 3% —— 见
        // [ScreenGeometry.rowWidth]，也就是 README 里那条硬约定的唯一实现。
        val toolbarRowWidth = geometry.rowWidth(
            metrics = metrics,
            ringFraction = toolbarWidthFraction(metrics, isRound),
            isRound = isRound
        )
        val toolbarRowWidthPx = with(density) { toolbarRowWidth.toPx() }

        /**
         * 顶部工具条那一条的**整条全宽矩形**（屏幕坐标，px）—— 切屏手势的"禁区"。
         *
         * 用户两次点名的都是"**那一条**"（"在能够唤出撤销菜单的按键的位置滑到另一个屏幕的
         * 判定应该取消掉" / "把工具条那一段（**屏高 10%~22.5%**）排掉"），所以口径是
         * **整屏宽 × [TOOLBAR_TOP, TOOLBAR_BOTTOM]** —— 上下沿由这两个常量现推、
         * 左右沿就是整屏宽。推导与"为什么比行矩形更宽的部分也一并封掉"写在
         * `PlotMetrics.ToolbarStripRect` 的注释里。
         *
         * 圆表上 = `x ∈ [0, 233]dp、y ∈ [23.30, 52.43]dp`（与工具条行的真实上下沿逐像素相等）；
         * 手机 411 × 850dp 上 = `x ∈ [0, 411]dp、y ∈ [85.00, 191.25]dp`。
         *
         * ⚠️ 它同时是下面那条左边缘手势 `pointerInput` 的 **key**（`ToolbarStripRect`
         * 是 data class，值没变就不重启手势）—— 手势读到的禁区永远是**当前**那一块。
         */
        val toolbarStrip = toolbarStripRect(
            screenWidthPx = with(density) { geometry.screenW.toPx() },
            bandTopPx = geometry.syPx(TOOLBAR_TOP),
            bandBottomPx = geometry.syPx(TOOLBAR_BOTTOM)
        )

        Box(
            Modifier
                .fillMaxSize()
                // ---- 左边缘往右滑 → 进「功能界面」（#9）----
                //
                // **挂在根节点上**，而不是像上一版那样"另开一条盖在最上层的热区"。
                // 那一版是这样写的：
                // ```
                // Box(Modifier.align(TopStart).fillMaxWidth(SWIPE_EDGE_W).height(...)
                //         .zIndex(1f).pointerInput { detectHorizontalDragGestures { … } })
                // ```
                // 抬到最上层确实让它拿到了水平拖动（原来的问题是列表 z 序更高、
                // 手势轮不到它），但 Compose 的命中测试**只走最上面那一条兄弟链** ——
                // 于是这条 0.26 屏宽、0.12~0.92 屏高的透明热区把落在它下面的
                // **所有**指针事件都截住了，而它自己只处理水平拖动，其余一概丢弃：
                //   * 函数行最左边的**色点**（点击切显隐）点不动了（#4）；
                //   * 滑动条行的参数字母徽标（长按进属性界面）按不动了（#3）；
                //   * 播放键左边那三分之一、以及这一带里的一切点击/长按/滚动都失灵。
                // （真机的"色点点击没反应""长按滑动条没反应"就是这么来的。）
                //
                // 现在改成挂在**根节点**上：根节点是所有行的祖先，和子节点在**同一条**
                // 命中链上（子节点先收到事件、祖先随后收到），所以它既能拿到没被消费的
                // 水平拖动，又不会把子节点的点击/长按挡掉。起手位置仍然按老规矩限定在
                // "左边缘 + 竖直中段"，语义和上一版一模一样。
                //
                // ==================================================================
                // ⚠️ 本轮**把它从库函数 `detectHorizontalDragGestures` 换成项目自己的
                // [awaitOneWayDrag]** —— 用户报的"纯水平左滑删不掉、带一点斜度就能删"
                // 就是这条库函数造成的：
                //   * 库函数**一旦越过水平容差，就在之后每一帧 `consume()` 这一串事件**，
                //     与调用方是否处理无关（1.7 的字节码：drag 回调是
                //     `onHorizontalDrag(it, positionChange(it).x); it.consume();`，
                //     我们回调里那句 `if (!armed) return` 挡不住后面那句 `consume()`）；
                //   * 它是**整屏的祖先节点**，于是"往左的左滑"这一串也被它整条吃掉；
                //     而行删除手势当时的判定读的是 `positionChange()`（被消费就返回 0），
                //     从那一帧起每帧读到 0 → 累计冻在触摸容差附近 → **永远滑不够
                //     1/3 行宽** → 划不动。带斜度时列表的竖直滚动先认领、这条库手势
                //     按惯例退出，于是没有谁再每帧消费 → 删除又能用了。
                //   * [awaitOneWayDrag] 的契约正好相反：**只认往右**，方向不对时
                //     **一个字节都不消费**。所以"往左（删除方向）"的一串事件它完全不碰，
                //     两条手势（左滑删行 / 左边缘右滑进功能界面）从此**互不消费**。
                //   * 起手范围（左边缘 0.26 屏宽 + 竖直中段 0.12~0.92 屏高）与触发距离
                //     （`swipeToolsPx`）与上一版**逐字不变**，变的只有"谁来判、谁消费"。
                // 完整机制（含每步的字节码依据）写在 `DragGestures.kt` 文件头。
                // ==================================================================
                .pointerInput(toolbarStrip) {
                    awaitEachGesture {
                        awaitOneWayDrag(
                            axis = DragAxis.HORIZONTAL,
                            positive = true,       // 只认**往右**
                            // 只有落在左边缘那一条带里起手才算数。
                            // y 的上下沿沿用原来的 0.12 / 0.92：再往上会和顶部
                            // "下滑回图像"的热区重叠，再往下会碰到底部区域。
                            // 判据从库函数的 `onDragStart(offset)` 挪到这里，
                            // 用的是同一条式子、同一个节点尺寸（整屏）。
                            //
                            // ⚠️ 本轮新增最后那一句：**顶部工具条那一条让给撤销菜单**。
                            // 用户原话："在能够唤出撤销菜单的按键的位置滑到另一个屏幕的
                            // 判定应该取消掉。" —— 这一条手势的起手区与工具条那一条
                            // 在边缘上重叠（圆表 `x ∈ [55.9, 60.6]dp` 那一小条），
                            // 从那里往右滑会让"唤出撤销菜单"变成"切到功能界面"。
                            // 判据 = **整屏宽 × 屏高 10%~22.5%** 那一条（用户点名的口径），
                            // 见上面 `toolbarStrip`。落在这一条**之外**的起手**逐字照旧**。
                            startsIn = { p ->
                                p.x < size.width * SWIPE_EDGE_W &&
                                    p.y > size.height * SWIPE_EDGE_TOP &&
                                    p.y < size.height * SWIPE_EDGE_BOTTOM &&
                                    !(toolbarStrip?.contains(p.x, p.y) ?: false)
                            },
                            // 触发距离仍按"从落点算起的累计位移"判（往右为正）。
                            // 与上一版唯一的差别：回搓是**扣减**而不是清零 ——
                            // 分子位移回到阈值以下就不再触发，比原来更不容易误触发。
                            onEnd = { total ->
                                if (total > swipeToolsPx) onSwipeToTools()
                            }
                        )
                    }
                }
        ) {

            // ---- 上部下滑 → 回图像 ----
            // 刻意放在顶部工具条**之前**组合：这样 ⚙ / + 两个按钮叠在它上面，
            // 点按钮仍然是按钮，只有划过空白处才算"下滑返回"。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // 高度按**真实屏高**算（不是短边）：用户要求"第二屏的屏高要和第一屏一样"，
                    // 两屏必须同一个口径。`metrics.sizeDp` 是**短边** —— 圆表上短边 == 屏高
                    // 所以看不出差别，手机上短边 == 屏宽 → 这条带子只有真实屏高的 **9.7%**
                    // （本该是 20%），于是两屏的"顶部下滑"起手区在手机上不一致。
                    // 改成 `geometry.sy(...)` 之后，两屏在任何屏形上都是**严格 0.20 真实屏高**；
                    // 圆表/方表上 `screenH == sizeDp`，所以那里逐像素不变。
                    //
                    // 「起手是否在这条带里」的判据就是**这个 Box 自己的布局高度**，
                    // 没有另写一个数字（这是这一处和第二屏唯一的实现差别 —— 第二屏把两条
                    // 边缘手势挂在根节点上，所以必须自己算一个 `topZonePx` 阈值；
                    // 本屏的 `pointerInput` 就挂在这块 Box 上，
                    // 命中测试天然只把这 0 ~ 0.20 屏高的事件交给它）。
                    // ⚠️ 所以这个高度**只准有一个来源**：改它就必须改这里，别再在
                    // `detectVerticalDragGestures` 里另加一条 y 范围判断 —— 那就会出现
                    // "盒子按真实屏高、判据按短边"的两套口径（第二屏上一轮就是这个坑）。
                    // 顺带说明下面那个 `swipeDownPx` 不是"起手判据"而是**触发距离**
                    // （`SWIPE_DOWN_THRESHOLD = 0.10 × 短边`，见常量注释）：两屏都是短边、
                    // 量级一致，本轮**没动**。
                    .height(geometry.sy(SWIPE_DOWN_ZONE_H))
                    .pointerInput(Unit) {
                        var accum = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accum = 0f },
                            onDragEnd = { accum = 0f },
                            onDragCancel = { accum = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                accum = if (dragAmount > 0f) accum + dragAmount else 0f
                                if (accum > swipeDownPx) {
                                    accum = 0f
                                    onSwipeDown()
                                }
                            }
                        )
                    }
            )

            // ---- 左边缘往右滑 → 进「功能界面」（#9）----
            //
            // 用户原话："给函数列表的左边加一屏，从屏幕左边往右滑进入
            // （放在右边可能会让函数被删除）"。
            //
            // 起手范围限定在**左边 0.26 屏宽、竖直中段 0.12~0.92**，
            // 而不是给整屏再加一个水平手势，原因有两个：
            //  * 函数行的删除手势就是**水平拖动**（从行右侧 1/3 往左滑），
            //    再来一个全屏水平手势必然打架；
            //  * 左边那一条带里的行部分是"色点 + 参数字母徽标"，
            //    本来就没什么水平手势，分给它最安全。
            //
            // ==================================================================
            // 两屏的"切屏手势"是**同一套规则**（用户本轮要求统一）
            //
            // 用户原话："在手机上，函数列表划到功能界面的时候，左边所有边缘都可以滑，
            // 但是从功能界面划到函数列表只有右边固定的高度的边缘可以滑，请你统一成
            // 函数列表划到功能界面的样子。"
            //
            // 问题出在**第二屏**（`FunctionsScreen.kt`）：它那条右边缘热区的上下沿是拿
            // `metrics.sizeDp`（**短边**）当屏高算的，手机屏一长就只剩"上面一小截右边"。
            // 这一屏（第一屏）一直是对的 —— 因为下面判据用的就是节点自己的真实尺寸：
            // `size.width / size.height`。**所以标准是这一屏，第二屏按它改**。
            //
            // | | 起手热区（占**屏宽** × 占**真实屏高**） | 方向 | 触发距离 |
            // |---|---|---|---|
            // | 第一屏（本文件） | **0 ~ 26%** × 12% ~ 92% | 往**右**滑 → 进功能界面 | 0.09 × 短边（≈21dp@圆表） |
            // | 第二屏 | **74% ~ 100%** × **0 ~ 100%**（整条边） | 往**左**滑 → 回函数列表 | 0.09 × 短边（同上） |
            //
            // 数值：
            //   * 圆表 466×466 / d2（233dp，短边 = 屏高）：本屏 x 0~60.6dp、y 27.96~214.4dp；
            //     第二屏 x 172.4~233dp、y 全高 0~233dp；
            //   * 手机 411 × 850dp：本屏 x 0~106.9dp、y 102~782dp；第二屏 x 304.1~411dp、y 全高 0~850dp。
            //
            // 为什么这样两边手感一致：两屏都只做**两件事** ——
            //   ① 起手点按"节点真实尺寸 × 百分比"判（同一句话在任何屏形上都是同一条边）；
            //   ② 只认 **自己那个方向** 的拖动，其余一个字都不消费（见 `DragGestures.kt`）。
            // ⚠️ **本屏的起手范围一个数都没改**（用户拿它当标准），本轮只改了第二屏。
            // ==================================================================
            //
            // 手势本体挂在**本屏的根节点**上（见上面 `Box(Modifier.fillMaxSize()…)`
            // 里那段），这里只剩下一个**看得见的抓手**（左边中间那道小竖条）：
            // 手势藏得太深用户根本不知道有这个屏 —— 顶部那条抓手就是这个 App
            // 的"这里有手势"语言。
            //
            // 为什么不再单独开一块盖在最上层的透明热区（上一版的做法）：
            // 见根节点那段注释 —— 它会截住它下面所有兄弟节点的指针事件，
            // 把函数行的色点点击（#4）、滑动条的长按（#3）一起吃掉。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = metrics.sizeDp * 0.012f)
                    .width(metrics.sizeDp * 0.011f)
                    .height(metrics.sizeDp * 0.13f)
                    .clip(CircleShape)
                    .background(scheme.outlineVariant)
            )

            // ---- 顶部：只有设置与新建两个按钮（不再写标题）----
            // 宽度：圆屏按**这一对按钮所在高度**的圆内宽度算（[toolbarWidthFraction]）、
            // 非圆屏（手机/方表）和列表行同一个口径 —— 铺满屏宽减左右余量。
            // 用户要求"按键也要像函数列表那样自动适配屏幕宽度"。
            //
            // ==================================================================
            // 这两颗（⚙ / +）和下面第二屏那两颗（📁 / 🗑）**形状必须完全一致**
            //
            // 用户原话："手机上的设置键、+键、删除键和文件键四个按键的位置上的所有按键
            // 都比手表上要方，长按动画的线圈不能围绕按键了，请你让按键强制保持在一个形状。"
            //
            // 形状本身由 `WatchIconButton`（Common.kt）强制，两屏都不需要各写一份：
            //   宽 = `metrics.iconButton`（= 0.18 × 短边）
            //   高 = 宽 × ICON_BTN_ASPECT（= 0.125/0.18 ≈ 0.6944）
            //   → **固定 1.44:1 的胶囊**，和行高、屏形都无关。
            //
            // 这一段解释的是**为什么这个比例在三种屏上都成立**（也就是为什么这里的
            // 调用点不需要、也**不允许**再给尺寸）：
            //   * 固定的两个数是"短边的 0.18 / 0.125"（dp 尺寸按短边缩放）；
            //   * 行的可用宽度按**屏形**二选一（圆屏 = 这一对按钮所在高度的圆内宽度、
            //     非圆屏 = 屏宽 − 左右余量），行高 = [TOOLBAR_H] × 短边。
            //
            // 关键巧合（也是这条规则能成立的原因）：0.18 × 0.6944 = **0.125** ——
            // 按钮高**正好等于行高**。`Modifier.width/height` 只会把子节点按父给的
            // 上限**夹一次**，而父给的上限恰好等于按钮自己要的尺寸，所以按钮的宽和高
            // 一分不多一分不少，最终尺寸就是 0.18 × 0.125 个短边：
            //
            //   | 设备 | 短边 | 最终尺寸 | 形状 |
            //   |---|---|---|---|
            //   | 圆表 466px / d2 | 233dp | 41.9 × 29.1dp（83.9 × 58.3px） | 1.44:1 |
            //   | 方表 400px / d2 | 200dp | 36.0 × 25.0dp（72.0 × 50.0px） | 1.44:1 |
            //   | 手机 411dp / d2.75~3 | 411dp | 74.0 × 51.4dp（203.4 × 141.3px @2.75） | 1.44:1 |
            //
            // 三行的**形状完全相同**（只有大小跟着短边走）：手机上的按钮看着比手表大，
            // 是因为手机的短边本来就大（411dp vs 233dp），那是"控件按短边缩放"这条既有
            // 规则的必然结果，**不是被父约束拉长的**。这正是用户要的"强制保持在一个形状"。
            //
            // ⚠️ 因此这两个调用点**只准传 icon / metrics / 颜色 / 回调**：任何
            // `Modifier.width/height/size/aspectRatio` 都会盖掉上面这套推导
            // （`WatchIconButton` 的 `modifier` 参数在 `.width/.height` **之前**，
            // 父约束一收窄，里面那两个数就被夹住了 —— `FunctionEditor` 的取消/完成、
            // `PropertyEditor` 的 ✓ 就是这么被写成 0.16 × 0.85 行高的扁方块的，
            // 那三处不归本轮改）。
            //
            // 顺带钉住"长按的线圈必须绕着按键"：线圈的几何（`HoldRing` 的
            // 0.92 / 0.615 / 0.075，全部相对 `metrics.iconButton`）算出来的外沿是
            // 0.995 × 0.69 个按钮边长 = 0.1791 × 0.1242 个短边，而按钮是 0.18 × 0.125 ——
            // **完整落在按钮里侧**，所以在任何屏上线圈都套着按键，不会压到按钮外面。
            // ==================================================================
            // （`toolbarRowWidth` / `toolbarRowWidthPx` 的声明已经**提到上面**了 ——
            //   根节点那条切屏手势要用它们算"工具条那一条"的禁区，
            //   而手势写在根节点的 `Modifier` 里、比这里更早。）
            /**
             * 「从一颗键滑到另一颗键」唤出撤销 / 重做 的全部状态（本轮新增）。
             *
             * 判据是**几何**的（`PlotMetrics.toolbarKeyAt` / `toolbarMenuCrossedToOtherKey`），
             * 不是"拖了多远"：**落点必须在 ⚙ 或 `+` 的矩形里**，而且手指要**真的越过
             * 两颗键之间那道空当、进入另一颗键的矩形**才算达成。圆表上的实算
             * （行宽 121.2dp、单键宽 41.9dp）：
             *
             * ```
             *   ⚙ 的矩形 [0, 41.9]      中间空当 (41.9, 79.2)      + 的矩形 [79.2, 121.2]
             *   最短行程 = 空当宽度 = 37.3dp      典型行程 = 中心距 = 79.2dp
             * ```
             *
             * 于是"在工具条上随便横拖一点点"**不可能**达成（37.3dp = 74.6px 已经是
             * 触摸容差 ≈24px 的 3 倍）—— 所以这里**没有任何距离阈值常量**。
             *
             * ⚠️ 达成之前**一帧动画都没有**：手势原语只回调"到了"这一次
             * （`DragGestures.awaitKeyToKeySwipe`，达成前一个字节都不消费），
             * 菜单的展开/让位/抓手淡出全部只由 `open` 这一个布尔驱动 —— 没有"拖多少
             * 展开多少"这种跟手中间态（用户明确要求"还没滑到另一个按钮的时候不要加动画"）。
             */
            val undoMenu = rememberToolbarUndoMenu(metrics)

            // ---- 顶部工具条那一条：⚙ / + 两颗键 + 叠在中间的撤销菜单 ----
            //
            // ⚠️ 本轮把原来那个 `Row` 换成了"同样尺寸的 `Box` 里放一个 `fillMaxSize`
            // 的 `Row`"：多出来的那一层是为了**把撤销菜单叠在同一条上**而不占新地方。
            // 三件事逐项保证圆屏逐像素不变：
            //   * Box 的修饰符链就是原来 Row 的那一条（align / offset / width / height /
            //     graphicsLayer 入场），一个字没改；
            //   * 里面的 Row 是 `fillMaxSize()` + `SpaceBetween` + 垂直居中 ——
            //     与原来"行宽 121.2dp、两颗键顶到两端"完全等价；
            //   * 新加的手势**不消费**落在按钮上的按下（见上面那段），
            //     所以两颗键的短按/长按行为不变。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // 行顶按**真实屏高**算：圆屏上等于原来的 0.10 × sizeDp，
                    // 手机上仍是"屏幕顶部往下 0.10"（而不是 41dp 那个短边比例值）。
                    .offset(y = geometry.sy(TOOLBAR_TOP))
                    .width(toolbarRowWidth)
                    // 行高继续按短边：它**正好等于按钮的高**（0.18 × ICON_BTN_ASPECT），
                    // 所以它决定了这颗胶囊能有多高，也决定"父约束夹不夹得到它"；
                    // 和字号/圆点/内边距仍是同一套基准（手机上不该被拉成一条巨大的横条）。
                    .height(metrics.sizeDp * TOOLBAR_H)
                    // 入场：和列表行一起"淡入 + 从 0.9 放大"，只是不等延迟
                    // （工具条是这一屏的"门脸"，让它先站稳，行再依次跟上）
                    .graphicsLayer {
                        alpha = topIn
                        val s = 0.90f + 0.10f * topIn
                        scaleX = s
                        scaleY = s
                    }
                    // ---- 从一颗键滑到另一颗键：唤出 / 收起撤销·重做菜单（本轮新增）----
                    //
                    // 达成判据 = "落点在某一颗键上 + 手指进了另一颗键的矩形"
                    // （见上面 `undoMenu` 那段与 `PlotMetrics.toolbarKeyAt`）。
                    // 达成之前**不消费任何事件**，所以两颗键的短按/长按照旧。
                    .then(undoMenu.gesture)
            ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ---- 左上角 ⚙：短按 = 设置（老行为）+ **长按 = 应用信息页**（本轮新增）----
                //
                // 用户原话："给长按设置键加个长按 `+` 键同款动画，线圈颜色用比设置亮一号
                // 颜色的灰色。长按之后打开应用信息页面（不要有标题）……"
                //
                // 这一处**逐字照抄**下面 `+` 键那一处（同一个 `HoldToConfirm`、同一个
                // `HoldLatch` 闩、同样"长按成立后抬手不能再触发点击"的两道锁），
                // 只有三件事不同，且都是有意的：
                //
                //   1. **线圈颜色 = `KeyPalette.normalBright`（#4A4D52）** —— 用户点名的
                //      "比设置键亮一号的灰"（设置键底色是 `KeyPalette.normal` #2D2F33）。
                //      为什么不沿用 `+` 键的 `accent`（蓝）：用户要的是**灰色**；
                //      那个常量的亮度/对比度实算写在 `WatchColors.kt` 里。
                //   2. **闩是另一个实例**（`settingsHoldLatch`）：两颗键的手势互不干扰，
                //      共用一个闩会让"长按过 ⚙"污染 `+` 的短按（理由见闩声明处）。
                //   3. **不传 `holdMillis`** —— 和 `+` 键一样走 `HoldToConfirm` 的默认
                //      900ms，也就是"两颗键的长按时长是同一个值"，不是各定各的。
                //
                // 短按这一句 `if (!settingsHoldLatch.eaten) onSettings()` 是**第 2 道锁**
                // （第 1 道是 `HoldToConfirm` 在 Initial 阶段把转满后的那一下抬手消费掉）：
                // 少写这一句，长按进应用信息页的同时还会顺带进设置页。
                //
                // ⚠️ 本轮给 `HoldToConfirm` 传了一个 `modifier`（撤销菜单展开时**让位**：
                // 暗到 0.18、缩到 0.82、朝左让开 4% 行宽）。它只往图层链上加一层
                // `graphicsLayer` —— 这颗键的尺寸、位置、命中区、以及长按线圈
                // （`HoldToConfirm` 按 `metrics.iconButton` 算的那一套）一个像素都不变；
                // 而且**不多一个布局节点**，所以圆表上逐像素不变。
                // （为什么不套一层 `Box`：线圈画在 `HoldToConfirm` 自己那个 Box 里，
                //   套在外面的图层罩不住它 —— 会出现"按键暗了、线圈还亮着"。）
                HoldToConfirm(
                    metrics = metrics,
                    ringColor = KeyPalette.normalBright,
                    modifier = Modifier.toolbarYield(
                        progress = undoMenu.progress,
                        toRight = false,
                        rowWidthPx = toolbarRowWidthPx
                    ),
                    latch = settingsHoldLatch,
                    onConfirm = onLongPressSettings
                ) {
                    WatchIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "设置（按住不放看应用信息）",
                        metrics = metrics,
                        onClick = { if (!settingsHoldLatch.eaten) onSettings() }
                    )
                }
                // 长按 `+` = 保存图像（#8）。用户要求"长按加个动画，从按钮中间的上面
                // 显示一个线圈逆时针绕着 + 键转，转满一圈进入保存" ——
                // 所以这里用 HoldToConfirm 包一层：按住才出现线圈、转满一圈才触发，
                // 顺带把"误触长按"也挡掉了。
                //
                // ⚠️ 短按（onClick）里必须先问一句 `addHoldLatch.eaten`：
                // 用户报的"长按 + 键保存文件的同时会唤出添加函数的页面"就出在这里 ——
                // 线圈转满之后手指抬起的那一下，按钮自己的 `clickable` 仍然会补一次点击。
                // 闩 + "抬手事件被长按消费掉"两道锁都在 HoldRing.kt 里，
                // 详见那边的 [com.functiondrawer.watch.ui.HoldLatch] 与 HoldToConfirm 注释。
                //
                // ⚠️ 同上：`modifier` 只加一层"让位"的 `graphicsLayer`（朝右让）——
                // 尺寸/位置/命中区/线圈全都不变，也不多一个布局节点。
                HoldToConfirm(
                    metrics = metrics,
                    // 线圈的颜色**不动**（用户说的是"按钮底色太亮"，不是线圈有问题）。
                    // 它一直是强调色，下面那颗按钮本轮改暗之后，两者的明暗就分得开了。
                    ringColor = KeyPalette.accent,
                    modifier = Modifier.toolbarYield(
                        progress = undoMenu.progress,
                        toRight = true,
                        rowWidthPx = toolbarRowWidthPx
                    ),
                    latch = addHoldLatch,
                    onConfirm = onLongPressAdd
                ) {
                    WatchIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "新建函数（按住不放保存图像）",
                        metrics = metrics,
                        // ---- 底色：**暗一档的强调蓝**（用户最终定的颜色）----
                        //
                        // 用户先说："长按+键的线圈和+键自己的颜色没区别，把+键像删除键一样
                        // 改成暗一点的色。"；看到深灰那版之后又定了颜色："+ 键用暗一点的
                        // 蓝色，风格和软件整体一致。"
                        //
                        // 真因：线圈的颜色（ringColor = KeyPalette.accent #4B586B）和按钮
                        // 原来的底色**是同一个值**（这里以前写 `filled = true`，它唯一的
                        // 作用就是把底色取成 accent）—— 线圈压在按钮上时边界消失了，
                        // 那颗按钮看起来就像"自带一圈没涂干净的蓝边"。
                        //
                        // 为什么是 `accentDeep`（#333E4D）：**同色相、亮度压一半** ——
                        // 它是 accent 的"暗调兄弟"，所以整屏的蓝色语言一致（用户要的
                        // "风格和软件整体一致"）；和线圈 accent 的相对亮度差约 2.1:1，
                        // 线圈压上去边界看得清；又比深灰 `normal` 更有"这是主操作"的分量，
                        // 和左边的 ⚙（`normal`）分得开。
                        // 为什么不用 `danger`（砖红）：它的相对亮度（≈0.105）和线圈
                        // （≈0.095）几乎一样，换上去还是一团；而且砖红是"删除"那套语义，
                        // 用在"新建/保存"上不自洽。
                        //
                        // ⚠️ 去掉 `filled = true` 只影响底色（`WatchIconButton` 里
                        // `filled` 唯一的用处就是选底色），其它一切照旧：
                        // 短按 = 立刻新建并进编辑器（这条路径**一点延迟都没加**）；
                        // 长按转满过的话这一句直接返回，绝不会再开编辑器。
                        containerColor = KeyPalette.accentDeep,
                        onClick = { if (!addHoldLatch.eaten) onAdd() }
                    )
                }
            }

            // ---- 撤销菜单：叠在这条工具条的**正中间**（本轮新增）----
            //
            // `Modifier.align(Alignment.Center)` = 只占那两颗键那么大一块（见
            // `ToolbarUndoRedoMenu` 的注释：尺寸正好等于两颗键，才结构性地保证
            // 两端的 ⚙ / `+` 在任何时刻都不会被它挡到）。
            // 它不占新地方、也不改变两颗键的位置；组合顺序是"先画键、后画菜单"。
            //
            // ⚠️ 它必须写在**同一个 Box 里面**（就是上面那个 `Box(...) { Row ... }`）：
            // 那一个 Box 就是"工具条那一条"，撤销菜单和两颗键共用它的矩形与手势。
            ToolbarUndoRedoMenu(
                menu = undoMenu,
                metrics = metrics,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = onUndoStep,
                onRedo = onRedoStep,
                modifier = Modifier.align(Alignment.Center)
            )
            }

            // ---- 顶部中间的"抓手"：暗示这里可以往下滑 ----
            // ⚠️ 撤销菜单展开时它**淡出**（只动图层）：中间同时出现"一对手势抓手"
            // 和"一对撤销箭头"会互相干扰视线（见 `PlotMetrics.toolbarMenuHandleAlpha`）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = metrics.sizeDp * 0.038f)
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * 0.011f)
                    .clip(CircleShape)
                    .background(scheme.outlineVariant)
                    .graphicsLayer { alpha = toolbarMenuHandleAlpha(undoMenu.progress.value) }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // ---- 上沿：真实屏高 × LIST_TOP（圆屏上逐像素等于原来的 0.245 × sizeDp）----
                    .offset(y = geometry.sy(LIST_TOP))
                    .fillMaxWidth()
                    // ---- 左右余量：只在**非圆屏**上生效 ----
                    //
                    // 圆屏上弧宽公式已经把行收进圆内了（行最宽 0.90 屏宽，本来就离屏幕边
                    // 还有 0.05 屏宽），再加边距等于把圆表上的行又缩窄一圈 —— 那不是用户
                    // 要的"圆屏行为一个像素都不变"，所以圆屏这一支**一动不动**；
                    // 手机/方表上没有圆角要躲，留 0.03 屏宽的呼吸位就够了。
                    .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))
                    // ---- 高度：从上面这个上沿一路铺到**屏幕底部**（只留下边距）----
                    //
                    // 不再写成"屏高的百分比"，而是"这块屏幕还剩多少就吃多少"：
                    // 手机上列表因此一直排到屏幕底部，长条屏下面不会再有空白带；
                    // 圆表上 sizeDp == 屏高、LIST_BOTTOM_PAD 又等于原来的 0.05，
                    // 所以算出来的高度和原来**完全相等**。
                    .height(geometry.listHeight(metrics, geometry.sy(LIST_TOP))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    if (isRound) metrics.sizeDp * LIST_GAP else geometry.sx(LIST_GAP)
                )
            ) {
                itemsIndexed(functions, key = { _, fn -> fn.id }) { index, fn ->
                    val wf = rowWidthFraction(
                        listState = listState,
                        itemKey = fn.id,
                        listTopPx = listTopPx,
                        screenPx = with(density) { geometry.screenH.toPx() },
                        metrics = metrics,
                        isRound = isRound
                    )
                    RowEntrance(
                        visible = entered,
                        delayMillis = index * STAGGER_MS,
                        rowHeight = rowHeight
                    ) {
                        FunctionRow(
                            fn = fn,
                            selected = fn.id == selectedId,
                            hasError = errors.containsKey(fn.id),
                            metrics = metrics,
                            geometry = geometry,
                            isRound = isRound,
                            widthFraction = wf,
                            onEdit = { onEdit(fn.id) },
                            onProperties = { onProperties(fn.id) },
                            onToggleVisible = { onToggleVisible(fn.id) },
                            onPickColor = { colorPickFor = ColorTarget.OfFunction(fn.id) },
                            onDelete = { onDelete(fn.id) }
                        )
                    }
                }

                // ---- 几何对象（#9）：描点、直线/线段/射线、角度/距离/面积 ----
                //
                // 用户原话要求度量"同步到函数列表里"，所以它们和函数排在同一列里、
                // 长得也一样（圆点 + 名字 + 一行摘要），只是**语义不同**：
                // **短按文字区 = 进详情页**（只读：颜色 / 名字 / 完整数据；本轮新增）、
                // **长按文字区 = 进属性屏**（用户要求："给所有函数列表里面的对象都加长按菜单，
                // 直接调用函数的长按菜单"），左滑删除、点色点切显隐、长按色点调色 ——
                // 后三条和函数行逐条对齐，一个字都没动。
                //
                // ⚠️ 短按这条路本轮**从"什么都不做"改成了"进详情页"**：
                // 上一轮用户把"短按唤出菜单"整套否掉了（原话"不是让你换个手势"），
                // 于是短按空着；这一轮他明确要一个**只读**的详情页，起因是
                // "有些点和直线里面数值太长了"（列表这一行是 `maxLines = 1` + 省略号，
                // 长坐标/长方程读不全）。详情页没有任何可改的东西，所以它不违反
                // 上一轮那条"别在短按上塞菜单"的要求 —— 改名字仍然只在长按那一屏。
                // 本屏不自己弹浮层：长按走 `onProperties(obj.id)`、短按走
                // `onObjectDetails(obj.id)`，两条都只是把 id 交出去，
                // "这是函数还是对象"由路由那一侧分辨。
                // 对象 id 和函数 id 由**同一个** `GraphState.nextId()`（共用一个 `idSeq`）发出、
                // 全局唯一，所以两者交到同一条路由上不会撞车。
                //
                // 变换出来的曲线折线碎片（role = FRAGMENT_ROLE）已经被过滤掉了 ——
                // 一条曲线的对称结果有上百段，全列出来列表就没法看了。
                val listObjects = objects.filter { it.appearsInList }
                itemsIndexed(listObjects, key = { _, o -> "obj-${o.id}" }) { index, obj ->
                    val key = "obj-${obj.id}"
                    val wf = rowWidthFraction(
                        listState = listState,
                        itemKey = key,
                        listTopPx = listTopPx,
                        screenPx = with(density) { geometry.screenH.toPx() },
                        metrics = metrics,
                        isRound = isRound
                    )
                    RowEntrance(
                        visible = entered,
                        // 入场延迟排在函数和滑动条后面，不抢它们的位置
                        delayMillis = (functions.size + sliders.size + index) * STAGGER_MS,
                        rowHeight = rowHeight
                    ) {
                        GraphObjectRow(
                            obj = obj,
                            metrics = metrics,
                            geometry = geometry,
                            isRound = isRound,
                            widthFraction = wf,
                            onToggleVisible = { onObjectToggleVisible(obj.id) },
                            onPickColor = { colorPickFor = ColorTarget.OfObject(obj.id) },
                            // 短按文字区 = **详情页**（用户本轮要求：只读、显示完全）。
                            // 为什么不在这里自己弹浮层：详情页是一屏（有顶部 ✓、有进出动画、
                            // 有它自己的版面），走路由最直接 —— 而且"这一行只把 id 交出去"
                            // 这条分工和长按那条完全一致（列表不判断"这是函数还是对象"）。
                            onDetails = { onObjectDetails(obj.id) },
                            // 长按文字区 = **函数行那条**属性入口（不是本屏自己弹浮层）。
                            // 传 id 而不是对象本身：路由那一侧要能分辨"这是函数还是对象"，
                            // 而 id 全局唯一（见上面那段说明）。
                            onProperties = { onProperties(obj.id) },
                            onDelete = { onObjectDelete(obj.id) }
                        )
                    }
                }

                // ---- 滑动条（#3）：跟在函数后面，每个可绑定参数一条 ----
                itemsIndexed(sliders, key = { _, s -> "slider-${s.id}" }) { index, slider ->
                    val key = "slider-${slider.id}"
                    val wf = rowWidthFraction(
                        listState = listState,
                        itemKey = key,
                        listTopPx = listTopPx,
                        screenPx = with(density) { geometry.screenH.toPx() },
                        metrics = metrics,
                        isRound = isRound
                    )
                    RowEntrance(
                        visible = entered,
                        delayMillis = (functions.size + index) * STAGGER_MS,
                        rowHeight = rowHeight
                    ) {
                        SliderRow(
                            slider = slider,
                            metrics = metrics,
                            // 行宽的唯一来源（圆屏弧宽 / 非圆屏满宽）：和函数行、对象行
                            // 同一个口径，删除阈值（1/3 行宽）也从它推。
                            rowWidth = geometry.rowWidth(
                                metrics = metrics,
                                ringFraction = wf,
                                isRound = isRound
                            ),
                            rowHeight = rowHeight,
                            onValue = { onSliderValue(slider.id, it) },
                            onPlayToggle = { onSliderPlayToggle(slider.id) },
                            onLongPress = { onSliderEdit(slider.id) },
                            // 左滑删除：和函数行/对象行**同一个**共用件，
                            // "滑轨上拖动 = 改值"由手势内部的 `skipIfDownConsumed` 保着
                            // （见 `SliderRow` 文件头那一节）。
                            onDelete = { onSliderDelete(slider.id) }
                        )
                    }
                }

                if (showPresets) {
                    item(key = "preset-title") {
                        Text(
                            text = "示例",
                            fontSize = metrics.fontTiny,
                            color = KeyPalette.textMuted,
                            // 真机反馈：这两个字要居中
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = metrics.sizeDp * 0.02f)
                                .graphicsLayer { alpha = tailAlpha }
                        )
                    }

                    itemsIndexed(presets.chunked(2), key = { i, _ -> "preset-$i" }) { i, pair ->
                    val presetKey = "preset-$i"
                    val wf = rowWidthFraction(
                        listState = listState,
                        itemKey = presetKey,
                        listTopPx = listTopPx,
                        screenPx = with(density) { geometry.screenH.toPx() },
                        metrics = metrics,
                        isRound = isRound
                    )
                    Row(
                        modifier = Modifier
                            // 宽度：圆屏走弧宽（`wf` 就是弧宽比例）、非圆屏铺满可用宽度 ——
                            // 和函数行同一个口径，手机上行不会被压成一根窄柱。
                            .width(
                                geometry.rowWidth(
                                    metrics = metrics,
                                    ringFraction = wf,
                                    isRound = isRound
                                )
                            )
                            .height(metrics.sizeDp * 0.085f)
                            .graphicsLayer { alpha = tailAlpha },
                        horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.018f)
                    ) {
                        pair.forEach { preset ->
                            MiniChip(
                                label = preset.label,
                                selected = false,
                                metrics = metrics,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = { onAddPreset(preset) }
                            )
                        }
                        // 奇数个时补一个占位，保证 chip 宽度一致
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                    }
                }
            }

            // ---- 长按圆点 → 调色板（12 色），带出现/消失动画 ----
            //
            // 动画用一个共享的 Animatable 驱动：出现时 0 → 1（淡入 + 从 0.86 放大），
            // 关闭时先把它跑回 0、**再**清掉状态 —— 这样收起也有动画。
            // （如果只是 `if (pickId != null)` 直接消失，就没有退出动画了。）
            val scope = rememberCoroutineScope()
            val panelAnim = remember { Animatable(0f) }
            LaunchedEffect(colorPickFor) {
                if (colorPickFor != null) {
                    panelAnim.snapTo(0f)
                    panelAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 210, easing = FastOutSlowInEasing)
                    )
                }
            }
            fun closeColorPanel() {
                scope.launch {
                    panelAnim.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 130)
                    )
                    colorPickFor = null
                }
            }

            val pickId = colorPickFor
            if (pickId != null) {
                // 当前颜色：函数和几何对象都从各自的列表里取，取值规则同一套
                // （`colorIndex % CurvePalette.size`）。
                val current = when (pickId) {
                    is ColorTarget.OfFunction ->
                        functions.firstOrNull { it.id == pickId.id }?.colorIndex ?: 0

                    is ColorTarget.OfObject ->
                        objects.firstOrNull { it.id == pickId.id }?.colorIndex ?: 0
                }
                // 全屏透明遮罩：点空白处收起。用 indication = null，
                // 否则整块表盘会泛起一圈水波纹。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { closeColorPanel() }
                )
                // 面板顶端：圆屏上仍是 0.355 × sizeDp（逐像素不变）；非圆屏按
                // **真实屏高**取同一个比例，并夹住"不让面板下沿跑出屏幕"
                // （0.68 是面板高度的上界：3 行色点 + 内边距 + 行间距）。
                // 不用 `geometry.rowWidth` 是因为调色板要"方方正正"：
                // 手机屏宽 411dp 下 12 颗色点铺满一整行会变成三个大圆饼，
                // 所以宽度继续按短边算（"控件尺寸"那一类），位置按真实屏高算。
                val panelTop = minOf(
                    geometry.sy(0.355f),
                    geometry.screenH - metrics.sizeDp * 0.68f
                ).coerceAtLeast(geometry.sy(0.05f))
                Surface(
                    // 调色板面板：和长按变体气泡同一套（近黑 + 1dp 20% 白描边 + 大圆角），
                    // 这样"浮起来的浮层"在三个界面里长得一样。
                    color = KeyPalette.bubble,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = panelTop)
                        .width(metrics.sizeDp * metrics.circleWidthFraction(0.5f) * 0.94f)
                        // ---- 那一圈 1dp 白描边必须**跟着浮窗一起**淡出 ----
                        //
                        // 用户原话："浮窗有消失动画、白边框突然不见。"
                        //
                        // 真因：`Modifier` 链里 `graphicsLayer` **只影响它右边（内层）**画的东西。
                        // 上一版把 `.border(...)` 写在 `graphicsLayer` **左边** ——
                        // 于是 Surface 自己的底色在图层里（跟着 `panelAnim` 淡出，一切正常），
                        // 而白描边画在图层**外面**：面板已经缩到 0.86 且 alpha = 0 了，
                        // 那圈描边还是满亮度，收起的那一帧就是"啪"地消失。
                        //
                        // 修法：换成 `bubbleFade(...)`（Common.kt，内部就是同一套 graphicsLayer：
                        // alpha + scaleX/scaleY），并把它挪到 `.border(...)` **之前** ——
                        // 描边进了图层，和底色共用同一个 alpha、同一个缩放。
                        //
                        // 时长与缩放区间**一个都没改**（进 210ms / 出 130ms、0.86 → 1.00，见上面
                        // 那两处 `animateTo`），所以圆屏上的观感与改动前逐帧一致，
                        // 变的只有"描边不再瞬切"。
                        .bubbleFade(
                            alpha = panelAnim.value,
                            scale = 0.86f + 0.14f * panelAnim.value
                        )
                        .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
                ) {
                    Column(modifier = Modifier.padding(metrics.sizeDp * 0.03f)) {
                        CurvePalette.chunked(4).forEach { rowColors ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(metrics.sizeDp * 0.105f),
                                horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.022f)
                            ) {
                                rowColors.forEach { swatch ->
                                    val index = CurvePalette.indexOf(swatch)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(swatch)
                                            .border(
                                                width = if (index == current) {
                                                    metrics.sizeDp * 0.014f
                                                } else {
                                                    0.dp
                                                },
                                                color = if (index == current) {
                                                    KeyPalette.text
                                                } else {
                                                    Color.Transparent
                                                },
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                when (pickId) {
                                                    is ColorTarget.OfFunction -> onColor(pickId.id, index)
                                                    is ColorTarget.OfObject -> onObjectColor(pickId.id, index)
                                                }
                                                closeColorPanel()
                                            }
                                    )
                                }
                            }
                            Spacer(Modifier.height(metrics.sizeDp * 0.022f))
                        }
                    }
                }
            }

            // ⚠️ 这里原来是对象行的**属性页浮层**（`FunctionsScreen.ObjectPropertiesBubble`）的调用点。
            // 本轮连同入口一起删掉了：对象行短按不再弹菜单、长按走 `onProperties(obj.id)`
            // 进属性屏（详见上面"几何对象"那一段）。这一屏因此不再需要任何属性浮层 ——
            // 也就不会有"浮层还开着、下面那一行已经被左滑删掉"这类只有浮层才有的状态问题。

            // ⚠️ 这里原来是**删除后的撤销条**（砖红胶囊「已删除 · 点此撤销」，
            // 2 秒到期、点一下撤回；配套的还有 `showUndo` / `onUndo` 两个参数、
            // `GraphState` 里五路备份、`GraphScreen` 里那个 2 秒到期 effect）。
            //
            // **本轮用户要求整套删掉**（原话："这样子就要把所有的撤销的 toast 提示都给删掉"）：
            // 撤销现在只有一条路 —— 工具条上"从一颗键滑到另一颗键"唤出的那对箭头
            // （← 撤销 / → 重做）。删掉之后这一屏底部不再有任何浮层，
            // "两条底部胶囊会不会撞在一起"这类问题也随之消失。
        }
    }
}

/**
 * 一行的入场：淡入 + 从下方极小的上移（alpha 0 → 1、translateY 行高 5% → 0）。
 *
 * 外面那层**固定高度**的 Box 是必需的：AnimatedVisibility 在 visible = false 时
 * 会把内容整个摘出布局，直接放进 LazyColumn，列表会在入场那 200ms 里
 * 一节一节被撑开 —— 行位跳动，看起来就是闪烁。先把行高占住，入场就只是"原地显影"。
 */
@Composable
private fun RowEntrance(
    visible: Boolean,
    delayMillis: Int,
    rowHeight: Dp,
    content: @Composable () -> Unit
) {
    // 「这一行还要不要播入场」在首次组合时就定死，之后不再变。
    // 理由：拿不准 AnimatedVisibility 首次组合时会不会补播 enter；
    // 赌错的后果是行被回收后滚回来会重播，来回滚两下就变成很吵的闪烁。
    val playEnter = remember { !visible }

    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight),
        // 行自身带宽度（圆屏上按圆弧缩放），所以这里要居中放
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (playEnter) {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = ROW_IN_MS,
                        delayMillis = delayMillis,
                        easing = FastOutSlowInEasing
                    )
                ) + slideInVertically(
                    animationSpec = tween(
                        durationMillis = ROW_IN_MS,
                        delayMillis = delayMillis,
                        easing = FastOutSlowInEasing
                    )
                ) { fullHeight -> (fullHeight * ROW_IN_SLIDE).toInt() }
            } else {
                EnterTransition.None
            },
            exit = fadeOut(
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            )
        ) {
            content()
        }
    }
}

@Composable
private fun FunctionRow(
    fn: PlotFunction,
    selected: Boolean,
    hasError: Boolean,
    metrics: WatchMetrics,
    /**
     * 这一行所在的真实屏幕几何（圆屏弧宽 / 非圆屏满宽都靠它分辨）。
     * 从 [FunctionListScreen] 传进来，行自己不做圆屏判断 —— 和 [widthFraction] 的分工一致。
     */
    geometry: ScreenGeometry,
    /** 圆屏 / 非圆屏（手机、方表）。行宽的两套口径由它二选一。 */
    isRound: Boolean,
    widthFraction: Float,
    onEdit: () -> Unit,
    onProperties: () -> Unit,
    onToggleVisible: () -> Unit,
    onPickColor: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colors
    val color = CurvePalette[fn.colorIndex % CurvePalette.size]

    val press = rememberPressFeedback(scaleAmount = ROW_PRESS_SCALE, glowAmount = 0f)

    // ---- 左滑删除：跟手位移 / 渐染进度 / "播完甩出动画再真删" ----
    // 全部交给**共用件**（`DragGestures.rememberRowSwipeDelete`，阈值与动画常数在
    // `PlotMetrics.SWIPE_DELETE_*`）。这里一个字都不重写 —— 对象行
    // （`FunctionsScreen.GraphObjectRow`）调的是同一个函数，所以"函数能删 ⟹ 对象也能删"
    // 是结构性的。用户报的"除了函数之外的对象都不能滑动删除"就出在**两行各写一套**上。
    val swipe = rememberRowSwipeDelete(fn.id, onDelete)
    // 行宽的**唯一**来源：圆屏 = sizeDp × 弧宽比例（逐像素不变）、非圆屏 = 铺满可用宽度。
    // 删除位移阈值、甩出距离都从它推，所以三者永远同一个基准。
    val rowWidthDp = geometry.rowWidth(metrics, widthFraction, isRound)
    val rowWidthPx = with(LocalDensity.current) { rowWidthDp.toPx() }
    // 删除手势的进度：0 = 没在滑，1 = 已经滑够、松手就删。
    val swipeProgress = swipe.progress(rowWidthPx)

    // ---- 两个长按区的"有没有出界"观察者（本轮新增，用户全局性要求）----
    //
    // "所有按钮的长按判定都改成只有在按钮的矩形框内才判定"：色点区（长按调色板）与
    // 文字区（长按进属性屏）都是 `combinedClickable(onLongClick=…)`，AndroidX 本身
    // 也会在出界时取消，但它的边界是"节点矩形 + 系统最小触控目标补白"
    // （色点区只有 ≈30×35dp，补白能到 10dp 以上）—— 比用户要的"1~3px 容差"大得多，
    // 会出现"手指飘到隔壁文字区了，调色板还是弹出来"。所以这里各自挂一个**零消费**的
    // 观察者（[Modifier.pressInsideGuard]），长按回调执行前问一句 `stayedInside`。
    //
    // ⚠️ 两个区各用各的 guard：它们的矩形不同（色点区 ≈30×35dp、文字区 ≈157×35dp），
    // 共用一格状态会让"在文字区里飘"被误判成色点区也出界。
    val dotPressGuard = rememberPressInsideGuard(fn.id)
    val textPressGuard = rememberPressInsideGuard(fn.id)

    // 圆点的显示/隐藏脉冲。首次组合吃掉一次（pulseSeen），
    // 否则每次滚进视野都会白弹一下。
    val dotPulse = remember { Animatable(1f) }
    var pulseSeen by remember { mutableStateOf(false) }
    LaunchedEffect(fn.visible) {
        if (!pulseSeen) {
            pulseSeen = true
            return@LaunchedEffect
        }
        dotPulse.snapTo(1f)
        dotPulse.animateTo(
            targetValue = DOT_PULSE,
            animationSpec = tween(durationMillis = DOT_PULSE_UP_MS, easing = FastOutSlowInEasing)
        )
        dotPulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = DOT_PULSE_DOWN_MS, easing = FastOutSlowInEasing)
        )
    }

    Surface(
        // 滑动过程中整行朝着砖红（KeyPalette.danger，就是 ⌫ 那个色）渐染，
        // 滑够为止 —— 不用确认框也能看懂"松手就要删了"。
        // 底色统一到 KeyPalette：按住/选中 = 强调色，平时 = 普通键深灰。
        color = lerp(
            if (selected) KeyPalette.accent else KeyPalette.normal,
            KeyPalette.danger,
            swipeProgress * SWIPE_DELETE_TINT
        ),
        shape = PillShape,
        modifier = Modifier
            // 宽度和 [rowWidthPx] 同一个来源（圆屏弧宽 / 非圆屏满宽），避免"算宽用一套、
            // 画宽用另一套"那种只在手机上才暴露的不一致。
            .width(rowWidthDp)
            .height(metrics.sizeDp * ROW_H)
            .graphicsLayer {
                // 松手删除时：行保留在"已经滑到的位置"，再往左甩行宽的 28%，
                // 同时缩小 + 淡出 —— 是"扔掉"而不是"凭空消失"。
                //
                // ⚠️ 位移只有**一个**来源（`RowSwipeDelete.translationXPx`）：
                // 跟手位移和甩出位移**都是往左的负数**，所以是相加。上一版这里写的是
                // `dragX - sweepShiftPx(...)`（减一个负数 = 往右推），用户看到的就是
                // "函数条从左边移到右边去"。算式的唯一实现在
                // `PlotMetrics.swipeDeleteTranslationPx`，离线单测钉着方向。
                // 缩放 / 透明度两个派生值同样来自共用件，和对象行用的是同一套数。
                translationX = swipe.translationXPx(rowWidthPx)
                val s = press.scale * swipe.sweepScale()
                scaleX = s
                scaleY = s
                alpha = (1f - ROW_PRESS_DIM * press.progress.value) * swipe.sweepAlpha()
            }
            // ---- 左滑删除（整行上的手势，和对象行**同一个实现**）----
            //
            // 起手位置**没有任何限制**：整行哪里起手都行（色点区、文字区、右端都算），
            // 唯一的判据是**方向** —— 往左滑 = 删除。
            // 下面把"点击/长按"挪进各自的子区域之后，这个删除手势仍然是**整行**的 ——
            // 子区域里的 `combinedClickable` 消费掉 down 也不影响它起手
            // （`awaitOneWayDrag` 用 `requireUnconsumed = false` 拿 down）。
            //
            // ⚠️ 这里原来是自己调 `awaitHorizontalTouchSlopOrCancellation` +
            // "只在往左时 consume"的写法，**真机上完全划不动**（用户："删除函数的滑动……
            // 全部失效"）。同一批手写 slop 判定在功能界面那两条边缘手势上也是同样的结果，
            // 而所有走库函数 `detect*DragGestures` 的手势都活着 —— 所以判定逻辑换成
            // 自己数的 `awaitOneWayDrag`（原语只有"拿事件 / 算位移 / 消费"三样）。
            // 详细的原因分析写在 `DragGestures.kt` 文件头。
            //
            // 本轮起这一段手势本身也搬进共用件（对象行那段和它逐字重复，还多了一条
            // 起手限制 —— 那正是"对象划不动"的真因）。`swipe.gesture(...)` 只做三件事：
            // 挂 [awaitOneWayDrag]、跟手、滑够时"先甩出去再删"，和对象行逐项一致。
            .then(swipe.gesture(fn.id, rowWidthPx))
    ) {
        // ---- 两个手势区共用的版面尺寸（px/dp 各一次，下面两区都按它算）----
        //
        // 为什么要专门提出来：这两个区**要刚好铺满整行、不留缝**（缝 = 点了没反应）。
        // 做法是把原来"整行统一加内边距"改成"内边距算在各自的**手势区之内**"：
        //   * 色点区宽 = 色点原来的点击区 0.105 + 行左内边距 0.025，内边距用
        //     `padding(start=…)` 加在 clickable **里面** → 点边条也算切显隐；
        //   * 文字区吃掉"中间那条间隔 0.02 + 行右内边距 0.025"，同样加在它自己的
        //     clickable 里面。
        // 结果：行的每一寸都归属某一个手势区，而且**色点与文字的视觉位置和改动前
        // 完全一致**（色点仍在 x = 0.025 + 0.105/2、文字仍从 x = 0.15 起、右端仍是 0.975）。
        val sidePad = metrics.sizeDp * 0.025f
        val dotHitW = metrics.sizeDp * 0.105f
        val textGap = metrics.sizeDp * 0.02f

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================= 色点区（手势区 A）=================
            //
            // 圆点本身就是「显示 / 隐藏」的开关：实心亮色 = 显示，空心描边 = 隐藏。
            //   * **点一下** → 切换显隐（有脉冲动画）
            //   * **长按**   → 调色板（用户要求的入口）
            //
            // ⚠️ 这一格**绝不能**弹出行属性编辑菜单：#4 用户补充得很明确 ——
            // "色点点按切换显隐就是让函数条的色点那里长按不打开属性菜单，
            //   长按其他地方才打开"。
            //
            // 做法是**把两个手势区彻底分开**（用户给的第二种、更省事的方案）：
            // 行级那段 `combinedClickable`（点=编辑 / 长按=属性）**不再挂在整行上**，
            // 而是挂到下面"文字区"那个 Column 上。于是：
            //   * 色点这一格只有色点自己的手势；
            //   * 文字区那一大块只有行级手势；
            //   * 两块**互不重叠**，不存在"外层长按把内层长按吃掉"的可能 ——
            //     不是靠派发顺序去赌谁先拿到 down，而是几何上就没有公共区域。
            // （之前挂整行时色点虽然在更里层、正常情况下会先消费 down，
            //   但两区重叠让"长按色点"的行为依赖 Compose 的派发细节；
            //   分开之后这个不确定性直接消失。）
            Box(
                modifier = Modifier
                    // 点击区 = 0.105（原色点点击区）+ 0.025（原行左内边距）
                    .width(dotHitW + sidePad)
                    // 高度撑满整行：这一列从上到下都是"显隐开关"，
                    // 行里不会留下"点了没反应"的死角。
                    .fillMaxHeight()
                    // 长按（调色板）的前提：手指**一直没离开这一格**（本轮新增，
                    // 见上面 `dotPressGuard` 那段与 `Common.PressInsideGuard`）。
                    // 观察者零消费 ⇒ 短按（切显隐）与整行的左滑删除都不受影响。
                    .pressInsideGuard(dotPressGuard)
                    .combinedClickable(
                        onClick = onToggleVisible,
                        // 出界过 → 这一次长按作废（不弹调色板）
                        onLongClick = { if (dotPressGuard.stayedInside) onPickColor() }
                    )
                    // 内边距加在 clickable **之内**：手势区含边条，而内圈圆点仍居中在
                    // 原来那 0.105 的格子里 → 观感与改动前逐像素一致。
                    .padding(start = sidePad),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(metrics.dot)
                        // 只缩内圈圆点：外面 0.105 的点击区不变，脉冲不会让开关变难点。
                        .graphicsLayer {
                            scaleX = dotPulse.value
                            scaleY = dotPulse.value
                        }
                        .clip(CircleShape)
                        .background(if (fn.visible) color else Color.Transparent)
                        .border(
                            width = if (fn.visible) 0.dp else metrics.sizeDp * 0.009f,
                            // 行底现在是深灰 #2D2F33，原来的 outlineVariant(#3F484C) 几乎看不见，
                            // 换成 80% 白（和未选中文字同一个色）
                            color = if (fn.visible) Color.Transparent else KeyPalette.textMuted,
                            shape = CircleShape
                        )
                )
            }

            // ================= 文字区（手势区 B）=================
            //
            // 行里只剩文字：**点一下 = 编辑表达式**、**长按 = 进属性编辑界面（#4）**。
            // 用 combinedClickable 而不是两个手势叠加：它保证长按之后**不再**触发
            // 单击（否则长按进属性界面时会顺带又被推进编辑器，两层界面叠在一起）。
            //
            // `interactionSource` 仍然用整行的那个 `press` —— 按下时整行缩小/变暗的
            // 反馈保持原样（图层缩放挂在整行的 Surface 上）。
            // `.fillMaxHeight()` + 垂直居中：把文字区撑满行的剩余高度，
            // "点/长按"的目标是一整块，而不是只有文字那么高。
            // 左/右内边距同样加在 clickable 之内 → 中间那条缝和右端边条都归它。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 长按（进属性屏）的前提：手指**一直没离开文字区**（本轮新增，
                    // 见上面 `textPressGuard` 那段）。零消费 ⇒ 短按（改表达式）与
                    // 整行左滑删除、色点手势都不受影响。
                    .pressInsideGuard(textPressGuard)
                    .combinedClickable(
                        interactionSource = press.source,
                        indication = null,
                        onClick = onEdit,
                        // 出界过 → 这一次长按作废（不进属性屏）
                        onLongClick = { if (textPressGuard.stayedInside) onProperties() }
                    )
                    .padding(start = textGap, end = sidePad),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    // 每个函数/方程/点都有自动分配的名字（#2：函数 a(x)、b(x)…，
                    // 方程与点 A、B…）。名字默认占第一行，表达式摘要退到第二行；
                    // 老存档没有名字时 title() 会自动退回摘要。
                    text = fn.title(),
                    fontSize = metrics.fontSmall,
                    // 报错仍然是亮红（scheme.error 在深底上可读），正常是纯白
                    color = if (hasError) scheme.error else KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 第二行只显示**表达式本身**。
                    // 用户要求："函数列表里面每个函数的表达式后面都有 · y=f(x)，请你删掉这个不必要的功能。"
                    // 那个后缀（`fn.mode.label`）在这一行里是纯冗余 —— 名字（a(x) / A）
                    // 和表达式已经把这条对象说清楚了，而模式在**编辑器里**有专门的切换键，
                    // 在列表里既不占地方也没人需要看。所以这里不再拼 `· ${fn.mode.label}`。
                    // （`PlotFunction.mode` 本身照旧保留：采样、编辑器、文件格式都要用它。）
                    text = if (hasError) "表达式有问题" else fn.summary(),
                    fontSize = metrics.fontTiny,
                    color = if (hasError) scheme.error else KeyPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
