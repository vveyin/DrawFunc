// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.SnapKind
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * 「功能界面」（用户需求 #9）。
 *
 * ## 它是什么
 *
 * 函数列表**左边**的一屏：从函数列表屏幕左边缘往右滑进入（手势在 [FunctionListScreen] 里）。
 * 这一屏**没有设置键、没有 `+` 键**（用户明确要求），能返回图像界面；
 * 从图像界面回到这一屏的路子是"底部往上滑"（靠 `GraphState.lastToolsScreen` 记住来路）。
 *
 * ## 界面上有什么
 *
 * ```
 *        ●            ← 顶部抓手（暗示可以下滑回列表；和列表顶部的抓手同一套语言）
 *   📁             🗑  ← 左上角文件夹（函数文件列表）/ 右上角清屏（只清几何对象）
 *   ┌ 基本工具 ┐       ← 小标题（metrics.fontTiny，用户要求"字号设小一点"）
 *   [ 移动 ][ 描点 ]
 *   ┌ 度量 ┐
 *   [ 角度 ][ 距离 ]
 *   [ 面积 ]
 *   ... 变换 / 作图 / 直线
 *   ═══                ← 底部抓手 + 上滑热区：回图像界面
 * ```
 *
 * **界面上一个标题都没有**（用户反复要求过），只有各栏的小标题。
 *
 * ## 为什么不用 `WatchIconButton` 画工具
 *
 * 工具名字是**两个字到四个字**（"角平分线"），图标表达不了；而且用户给的名字
 * 就是工具名，直接写出来最不容易误会。所以每一栏用一个两列的小 chip 网格。
 * chip 的配色和键盘按键同源（未选中 = `KeyPalette.normal`、选中 = `KeyPalette.accent`），
 * 但**不是** `Common.kt` 的 `MiniChip`：那一颗的底色是瞬间切换的，而这一屏要的是
 * "选中底色渐染"（用户点名过要它、也点名删掉了原来那层发光，见 [ToolChip]）。
 */

/**
 * 顶部下滑 → **回图像界面**的热区高度（占**真实屏高**）与阈值（占**短边**）。
 *
 * 用户要求："第二屏上面的条应该是返回函数图像的"。
 * （原来这里是"下滑回函数列表"，和列表那条"下滑回图像"语义正好相反，容易记错。）
 *
 * ## 本轮把热区的"口径"改成和第一屏一样（用户要求"第二屏的屏高要和第一屏一样"）
 *
 * 这一条原来算的是 `metrics.sizeDp × SWIPE_DOWN_ZONE_H`，而 `sizeDp` 是**短边**
 * （`WatchMetrics.sizeDp = min(可用宽, 可用高)`）—— 和上一轮右边缘那条带子、
 * 以及第一屏工具条/列表上沿是**同一个偏差**：写的是"屏幕高度的百分之几"，
 * 算的却是短边。三种屏上的后果：
 *
 * | 设备 | 短边 | 屏高 | 改前（0.15 × 短边） | 改后（0.20 × 真实屏高） |
 * |---|---|---|---|---|
 * | 圆表 466px / d2 | 233dp | 233dp | 34.95dp（**短边就等于屏高**） | **46.6dp** |
 * | 方表 400px / d2 | 200dp | 200dp | 30.0dp（同上） | **40.0dp** |
 * | 手机 411 × 850dp | 411dp | 850dp | 61.65dp（只占真实屏高 **7.3%**） | **170.0dp（20%）** |
 *
 * ⚠️ **圆表上这一条是变大的（0.15 → 0.20），不是变小**：第一屏那个比例本来就是 0.20
 * （`FunctionList.SWIPE_DOWN_ZONE_H = 0.20f`），所以"和第一屏一样"= 比例取 0.20。
 * 圆表上短边 == 屏高，于是**口径**的改动在圆表上不产生任何像素差异，唯一的差别就是
 * 这个比例本身：34.95dp → 46.6dp。手机上则是口径 + 比例一起改（61.65 → 170dp），
 * 用户关心的"热区只有真实屏高的百分之几"到此消掉（7.3% → 20%）。
 *
 * ### 第一屏也在同一轮改成了真实屏高 —— 两屏现在**严格**一致
 *
 * 这一条改完时，第一屏那块热区 Box 还在用 `metrics.sizeDp * 0.20f`（**短边**）——
 * 也就是"屏高口径"这件事当时只统一了一半：手机上第一屏 82.2dp（真实屏高的 9.7%）、
 * 第二屏 170dp（20%），两屏**还是不一样**。所以第一屏那处
 * （`FunctionList.kt`：热区 Box 的 `.height(...)`）随后也改成了
 * `geometry.sy(SWIPE_DOWN_ZONE_H)`。现在两屏在**任何**屏形上都是"严格 0.20 × 真实屏高"：
 *
 * | 设备 | 第一屏 | 第二屏（本文件） |
 * |---|---|---|
 * | 圆表 233dp | 46.6dp | 46.6dp |
 * | 方表 200dp | 40.0dp | 40.0dp |
 * | 手机 411 × 850dp | 170.0dp | 170.0dp |
 *
 * 触发距离 [SWIPE_DOWN_THRESHOLD] **一个字都没改**：两屏都是 `0.09 × 短边`
 * （圆表 21dp、手机 37dp）—— 和第一屏的 0.10（圆表 23.3dp、手机 41.1dp）是同一个量级，
 * 量级一致是两屏原本就有的取舍，本轮没动。
 *
 * ⚠️ [SWIPE_DOWN_ZONE_H] 在本文件里**只有** `topZonePx` 一个使用者（原来是那块
 * 透明 Box 的 `height`，上一轮起改成根节点手势的 `startsIn` 阈值）—— 所以它现在是
 * 纯粹的"占真实屏高百分之几"，不会再被当成"盒子的 dp 高度"读错。
 */
private const val SWIPE_DOWN_ZONE_H = 0.20f
private const val SWIPE_DOWN_THRESHOLD = 0.09f

/**
 * 右边缘竖条：往左滑 → **回函数列表**，并播放左右切换动画。
 *
 * 用户要求："下面的条不要，右边放个条，滑了播放左右动画，返回到函数列表。"
 * 所以底部那条整块撤掉，改成右边一条竖直热区 + 一道看得见的竖抓手。
 * 方向是**往左**：功能界面在列表左边，把当前屏往左推走、列表从右边滑回来，
 * 和 `GraphScreen` 里那条横向转场是同一个空间隐喻。
 *
 * ## 与第一屏"切屏手势"的统一（用户本轮要求）
 *
 * 用户原话："而且在手机上，函数列表划到功能界面的时候，左边所有边缘都可以滑，
 * 但是从功能界面划到函数列表只有右边固定的高度的边缘可以滑，请你统一成函数列表
 * 划到功能界面的样子。"
 *
 * ### 真因：这条带子的"高度"是拿**短边**当屏高算出来的
 *
 * 老代码是 `y ∈ (0.16 × sizeDp, 0.90 × sizeDp)`，而 `sizeDp` 是**短边**
 * （`WatchMetrics.sizeDp = min(可用宽, 可用高)`：圆表上等于直径、手机上等于屏宽）：
 *   * **圆表 233dp**：短边就是真实屏高 → 带子确实覆盖 0.16~0.90 的屏高（74%），所以
 *     圆表上一直没暴露问题；
 *   * **手机 411 × 850dp**：带子变成 y ≈ 65.8dp ~ 369.9dp —— 只占**真实屏高的
 *     7.7% ~ 43.5%**，也就是"只有上面一小截右边能滑"。用户那句"只有右边固定的高度"
 *     说的就是这个：带子的**起点和终点都是按短边算的死数**，屏一长就全挤在顶部。
 * 第一屏那条被用户当成标准，是因为它一直用 `size.height`（**节点真实高度**）× 0.12~0.92
 * → 手机上覆盖真实屏高的 12%~92%，看着就是"整条左边都能滑"。
 *
 * ### 现在这一条（和第一屏同一套写法）
 *
 * | | 起手热区（占**屏宽** × 占**真实屏高**） | 方向 | 触发距离 |
 * |---|---|---|---|
 * | 第一屏 `FunctionList` | 0 ~ 26% × 12% ~ 92% | 往**右**滑 → 进功能界面 | 0.09 × 短边 |
 * | 第二屏（本文件） | **74% ~ 100% × 0% ~ 100%** | 往**左**滑 → 回函数列表 | 0.09 × 短边 |
 *
 * 两屏都用"**节点自己的真实尺寸** × 百分比"来判（第一屏 `size.width` / `size.height`，
 * 这一屏同样是 `size.width`，见下面的 `startsIn`），所以同一句话在任何屏形上都是
 * 同一个手感 —— 这就是"统一"的全部含义。本屏不再按 0.16/0.90 截断高度：用户要求
 * "至少要覆盖整条右边的高度"，所以是**整条右边**。
 * 触发距离两屏**本来就一致**（都是 `0.09 × 短边`：圆表 21dp、手机 37dp），没动。
 *
 * ### 具体数值
 *
 * | 设备 | 本屏（右边缘） | 第一屏（左边缘，对照） |
 * |---|---|---|
 * | 圆表 466×466 / d2（233dp，短边 = 屏高） | x 172.4 ~ 233dp（74%~100% 屏宽）、y **全高 0~233dp** | x 0 ~ 60.6dp、y 27.96 ~ 214.4dp |
 * | 手机 411 × 850dp | x 304.1 ~ 411dp、y **全高 0~850dp** | x 0 ~ 106.9dp、y 102 ~ 782dp |
 * | 方表 400×400 / d2（200dp，短边 = 屏高） | x 148 ~ 200dp、y **全高 0~200dp** | x 0 ~ 52dp、y 24 ~ 184dp |
 *
 * 圆表上这条从"0.16~0.90 屏高"变成"整条屏高"是有意的：左右两条因此都是
 * "贴着一整条边往里滑"，手机上更是从"顶部一小截"变成"整条右边"。
 */
private const val EDGE_ZONE_W = 0.26f
private const val EDGE_THRESHOLD = 0.09f

// LIST_TOP（列表上沿，占真实屏高）= 0.245：和第一屏**同值**（清屏按钮和第一屏的 `+`
// 必须落在同一行）。这里必须**再写一份**：`FunctionList.kt` 里那个是 `private`，
// 而同一个包里已经有 `AlphabetKeyboard` / `SavesScreen` / `SettingsScreen` 三个
// 同名的 private `LIST_TOP`，谁把它升成 `internal` 都会撞名报错（改第一屏的列表分区时
// 要记得两边一起改这个数）。原来这里还有 LIST_BOTTOM：它把列表在 0.94 屏高处截断了
// （手机上就是"下面全空"），现在由 `geometry.listHeight` 一路铺到屏幕底部。
private const val LIST_TOP = 0.245f

/**
 * 工具 chip 的高度（占**屏宽** —— 和 [SECTION_GAP] / [SECTION_TITLE_H] 一样都按短边走，
 * 也就是"控件尺寸"那一类，只在位置上才用真实屏高）。
 *
 * 0.092 × 233dp ≈ 21dp：比列表行（0.15 ≈ 35dp）矮一档 —— 这一屏有五栏、
 * 十行 chip（长条屏不需要滚动，五栏本来就排得下）。
 * chip 的高度**不跟屏高走**：手机屏高是短边的 2.2 倍，按屏高算会得到 80dp 高的
 * 大按钮，和同一屏里的字号/圆点完全不成比例。
 */
private const val CHIP_H = 0.092f

/** 每栏之间的小标题与网格的间距（占屏宽，理由同 [CHIP_H]）。 */
private const val SECTION_GAP = 0.012f

/** 小节标题占屏宽的比例（比 chip 略矮一点，字号是 fontTiny）。 */
private const val SECTION_TITLE_H = 0.055f

// ---------------------------------------------------------------------------
// 顶部两颗按钮（左上「函数文件」/ 右上「清屏」）的几何
//
// 用户原话："功能界面的按键……没有和函数列表的一样自动适配屏幕宽度。"
// 所以这一对按钮**逐字照抄**函数列表那一行的规则（`FunctionList.toolbarWidthFraction`）：
// 行所在的上下沿 → 圆内宽度 → 乘 ARC_MARGIN → 夹在 MIN_CHIP_WIDTH ~ SQUARE_ROW_WIDTH。
// 两屏用的是同一组数，两颗按钮因此**严丝合缝地对齐**（原来这里写的是
// `circleWidthFraction(0.16f) * 0.93f` 这种"抄过来的魔数"，改成从下面这两个常量推）。
// ---------------------------------------------------------------------------
/** 工具条行顶（占屏高）。和函数列表同一个数。 */
private const val TOOLBAR_TOP = 0.10f
/** 工具条行高（占屏高）。0.125 × 233dp ≈ 29.1dp。 */
private const val TOOLBAR_H = 0.125f
/** 图标按钮边长占屏比 —— 必须等于 `WatchMetrics.iconButton` 的 0.18。 */
private const val ICON_BTN_H = 0.18f

// ---- 圆屏宽度自适应（和 FunctionList / SettingsScreen 同一套数）----
/**
 * 圆屏上允许的**最宽**宽度（同时是"算不出弧宽时"的兜底值）。
 *
 * ⚠️ **非圆屏不再用它当行宽**：那一支现在由 `FunctionList.ScreenGeometry.rowWidth`
 * 直接给"屏宽 − 左右各 [ROW_SIDE_INSET]"。手机上原来 chip 行和顶部两颗按钮都是
 * `0.90 × 短边`，在 411dp 宽的手机上只有 370dp —— 屏上留下窄窄一列。
 */
private const val SQUARE_ROW_WIDTH = 0.90f
/** 圆屏上允许的最窄宽度：再窄下去 chip 上的字就没法看了（也保住最小点击目标）。 */
private const val MIN_CHIP_WIDTH = 0.52f
/** 用满圆内宽度时留一点余量，免得控件角正好压在表壳边缘上。 */
private const val ARC_MARGIN = 0.95f

// ---- 动画 ----
/**
 * 每一栏的入场：200ms 淡入 + 从 0.94 放大到 1，第 index 栏再晚 40ms。
 *
 * 和函数列表那边（220 / 45）是同一个量级、同一个"很轻"的意图：
 * 这一屏只有五栏，交错感比列表弱一点才不显得磨蹭。
 */
private const val SECTION_IN_MS = 200
private const val SECTION_STAGGER_MS = 40
/** 顶部那两颗按钮的入场：比栏略快（180ms），先它站稳、栏再依次跟上。 */
private const val TOOLBAR_IN_MS = 180
/** 新选中的 chip 弹一下：1 → 1.06 → 1（合计 200ms，和设置页的取值 chip 同一套）。 */
private const val CHIP_POP = 1.06f
private const val CHIP_POP_DOWN_MS = 130
/** 选中态那圈外发光淡入淡出的时长。 */
private const val CHIP_SEL_MS = 160
/** 清屏键"可用 / 压暗"之间过渡的时长。 */
private const val TRASH_SWITCH_MS = 180
// （这里原来还有 `BUBBLE_IN_MS` / `PROPERTY_TOP` / `PROPERTY_MAX_H` —— 全是给
//  "对象属性浮层"（`ObjectPropertiesBubble`）用的：出现时长、卡片顶边、卡片高度上界。
//  浮层本轮整个删了（入口也删了），这三个数在本文件里**没有任何使用者**，
//  所以一起删掉；对象属性屏的进出转场现在由路由那一侧（`GraphScreen` / `PropertyEditor`）
//  决定，不在这一屏里。）

// ---- 对象行的"被扔掉"（左滑删除确认之后）----
//
// ⚠️ 这三个数（`DELETE_OBJECT_MS` / `DELETE_OBJECT_SHIFT` / `DELETE_OBJECT_SHRINK`）
// **已经删掉，改用共用件里那一份**：`PlotMetrics.DELETE_OUT_MS` / `DELETE_OUT_SHIFT` /
// `DELETE_OUT_SHRINK`。它们和 `FunctionList.kt` 里那三个是**同一个东西的两份抄写**
// （值一样、名字不一样），而两行各拿一份常量正是"函数能删、对象不能删"那类漂移的温床 ——
// 用户报的 bug 里对象行还多了一条起手位置限制，就是"各写一套"的直接后果。

/**
 * 功能界面。
 *
 * @param activeTool 当前选中的工具（默认「移动」，状态在 `GraphState` 里）
 * @param objectCount 已经画了几个几何对象（清屏键用它决定要不要"看起来可点"）
 * @param onSelectTool 选了一个工具：状态层会**自动回图像界面**开始取点
 * @param onClearObjects 右上角垃圾桶短按：清掉所有几何对象（不动函数/方程）
 * @param onClearAll 右上角垃圾桶**长按**：连函数/方程一起清掉（原来"长按 +"的能力）
 * @param onBackToGraph 顶部下滑：回图像界面（用户要求的「上面的条」）
 * @param onSwipeDown 右侧竖条左滑：回函数列表（并播左右切换动画）
 * @param onOpenSaves 左上角**文件夹**按钮：进「函数文件」列表（`Route.Saves`）
 * @param rotary 表冠：在这一屏只用来滚动工具列表
 */
@Composable
fun FunctionsScreen(
    activeTool: FunctionTool,
    objectCount: Int,
    metrics: WatchMetrics,
    onSelectTool: (FunctionTool) -> Unit,
    onClearObjects: () -> Unit,
    /** 垃圾桶**长按**：函数/方程也一起清（接替原来"长按 + 清空全部"的能力）。 */
    onClearAll: () -> Unit,
    onBackToGraph: () -> Unit,
    onSwipeDown: () -> Unit,
    /** 左上角文件夹按钮：打开应用的 saves 目录（函数文件列表）。 */
    onOpenSaves: () -> Unit,
    rotary: Flow<Float>
) {
    val scheme = MaterialTheme.colors
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // ---- 真实可用尺寸（不是短边）----
    //
    // 手机上暴露的第二个问题：这一屏的工具栏也只占了屏幕顶部的一个方格。
    // 原因和函数列表同一处 —— `WatchMetrics.sizeDp` 是**短边**，方表上它等于屏高、
    // 圆表上等于直径，所以"按短边算"在手表上一直成立；手机（长条屏）上就全塌了。
    // 所以这里量一次真实可用区域，把"占屏幕百分之几的位置"换成真实尺寸算，
    // 详见 `FunctionList.ScreenGeometry` 上面那段说明（两屏共用这一层）。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val swipeEdgePx = with(density) { (metrics.sizeDp * EDGE_THRESHOLD).toPx() }
        val swipeDownPx = with(density) { (metrics.sizeDp * SWIPE_DOWN_THRESHOLD).toPx() }
        // 顶部那条热区的矩形（原来是块透明 Box 摆出来的，现在改成"根节点按起手位置自己判"，
        // 所以要把同样的矩形换算成 px 阈值 —— 语义和原来一模一样，只是不再需要那块透明 Box 占位）。
        //
        // ⚠️ 这里改的是**换算基准**：原来乘的是短边（`metrics.sizeDp`），现在乘的是
        // **真实屏高**。第一屏同一轮也改成了同一个口径（那边热区 Box 的
        // `.height(geometry.sy(SWIPE_DOWN_ZONE_H))`）—— 两屏现在都是
        // "0.20 × 真实屏高"，任何屏形上都严格相等。
        // 这里用 `syPx` 而不是 `with(density) { geometry.sy(...).toPx() }`：同一回事
        // （第一屏用的是后者、本屏用的是前者，两者只差一次 dp↔px round-trip，
        // 在真实设备上同值），但和下面 `swipeDownPx` 的写法一致。
        val topZonePx = geometry.syPx(SWIPE_DOWN_ZONE_H)
        // ⚠️ 右边缘那条**故意不在这里换算**：它的矩形必须按**节点自己的真实尺寸**判，
        // 否则手机上又会退回"拿短边当屏高"的老毛病（见 [EDGE_ZONE_W] 那段真因）。
        // 它的判据写在下面 `startsIn` 里，直接用 `size.width` —— 和第一屏逐字同一套。

        // ---- 宽度自适应要用的两个量（和 FunctionList / SettingsScreen 同一套）----
        /** 圆屏还是方屏：方屏没有"越靠边越窄"的问题，直接给接近满宽。 */
        val isRound = LocalConfiguration.current.isScreenRound
        /**
         * 列表上沿在屏幕上的 y（像素）。
         * 算某一项**此刻**该多宽要靠它把"项在列表里的偏移"换算成"屏幕高度的百分之几"
         * （见 [chipRowWidthFractions]）。
         *
         * ⚠️ 分母是**真实屏高**（原来用的 `metrics.sizePx` 是短边）：圆表上两者相等，
         * 手机上不换分母的话，圆屏弧宽会按错误的"屏幕高度"去算。
         */
        val listTopPx = with(density) { geometry.sy(LIST_TOP).toPx() }
        val screenHeightPx = with(density) { geometry.screenH.toPx() }

        // ---- 入场：每次打开这一屏播一次（和设置页同一个取舍：不留 saveable，
        //      这样"重新进来一次"就再看一遍，属于"打开就有反应"）----
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val topIn by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(durationMillis = TOOLBAR_IN_MS, easing = FastOutSlowInEasing)
        )

        // 清屏键的"红得有多实"：有东西可清 = 满档，没东西 = 压暗一档
        // （但仍然看得出是红的）。两档的数值**不再写在这里**，而是共用
        // `KeyPalette.dangerAlpha(...)` / `KeyPalette.DANGER_DIM_ALPHA` ——
        // "函数文件"那一屏的删除键用的是**同一个暗档**（`KeyPalette.dangerDim`），
        // 那个 0.45 只要在两处各写一遍就迟早会漂。
        // 中间用 tween 过渡 —— 用户画完第一个几何对象 / 清完屏时，这颗键是渐变亮/暗的。
        val trashAlpha by animateFloatAsState(
            targetValue = KeyPalette.dangerAlpha(objectCount > 0),
            animationSpec = tween(durationMillis = TRASH_SWITCH_MS, easing = FastOutSlowInEasing)
        )

        // 表冠滚动工具列表。用 collectLatest：连续转动时只处理最新一次，
        // 否则会排出一长串滚动动画、松手之后还在滚。
        LaunchedEffect(rotary) {
            rotary.collectLatest { amount ->
                listState.scrollBy(-amount * ROTARY_TOOL_FACTOR)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // ==================================================================
                // 两条边缘手势：**挂在根节点上**，不再用"盖在最上层的透明热区"
                //
                // 这两条原来是两块 `zIndex(1f)` 的透明 Box（顶部下滑、右边缘左滑）。
                // 抬到最上层确实让它们拿到了拖动，但 Compose 的命中测试只走最上面那一条
                // 兄弟链 —— 于是它们把落在自己矩形里的**所有**指针事件都截住了，
                // 而它们只处理某一个方向的拖动，其余一概丢弃。真机上的表现就是"点了没反应"
                // （下面两条按它们**当时**的矩形说；现在的矩形见各自的 `startsIn`）：
                //   * 顶部那条（当年是 y < 0.15 × 短边）压住了工具条
                //     （那颗行在 y = 0.10 ~ 0.225 屏高）——
                //     **左上角「函数文件」和右上角「清屏」两颗按钮的上半部分（约 40% 高）
                //     点不动**；清屏是"按住才生效"的，按在它上半部分按住会毫无反应；
                //   * 右边缘那条（当年是最右 0.20 屏宽、y 0.16~0.90 × **短边**）压住了工具 chip：
                //     第 2、3 栏的 chip 一直伸到 x ≈ 0.94，**右边约四成宽度点不动**；
                //     用户就得多往左偏一点才点得中，"右边的工具经常点了没反应"。
                // （和 `FunctionList.kt` 里那条"左边缘右滑"热区是同一个坑，那边已经这么改了。）
                //
                // 现在挂到根节点上：根节点是所有按钮/chip 的**祖先**，和它们在同一条命中链上
                // （子节点先收到事件、祖先随后收到），所以
                //   * 该给按钮/chip 的点击、长按一个都不会被挡（它们先拿到事件）；
                //   * 没被消费的拖动照样会传到我们这里 —— 边缘手势照旧能触发；
                //   * 起手位置仍然由下面各自判一次（顶部那条带 / 右边缘那条带），
                //     和原来那两块 Box 的矩形**完全一致**，不会抢别处的拖动。
                // 另外"顶部下滑会不会被工具列表的滚动吃掉"这个担心也不再成立：
                // 列表从 0.245 才开始排，而顶部热区到 0.20 就结束了，两者根本不重叠。
                //
                // ⚠️ 这一轮热区从 0.15 改到 0.20（= 和第一屏同一个比例，见 [SWIPE_DOWN_ZONE_H]），
                // 于是它的下沿有可能压到工具条那一行（0.10 ~ 0.225）—— 这正是下面那条
                // `skipIfDownConsumed = true` 要挡的情形：两颗按钮的 `clickable`（清屏外面还套着
                // `HoldToConfirm` 那一圈**自己消费 down** 的长按）会消费 down，落到按钮上的按下
                // 一律归按钮（短按照旧、清屏照旧"按住转满一圈"），只有从按钮**中间那块空当**
                // （两颗之间剩 37.3dp @圆表）或两旁空白处起手的下滑才回图像界面。
                // 三种屏上热区下沿 vs 按钮下沿（按钮下沿 = TOOLBAR_TOP × 屏高 + 0.125 × 短边）：
                //   * 圆表 233dp：热区 46.6dp **<** 按钮下沿 52.4dp —— 两颗按钮整体在热区之外，
                //     圆表上连"重叠"都不会发生（热区只压在多出来的那 5.8dp 空白上）；
                //   * 方表 200dp：热区 40.0dp **<** 按钮下沿 45.0dp —— 同上；
                //   * 手机 411 × 850dp：热区 0 ~ 170dp、按钮 85 ~ 136.4dp —— **真的重叠**，
                //     由 `skipIfDownConsumed` 按同一套语义挡住。
                // 手机这一条和第一屏是同一个结果：那边是把热区 Box 排在工具条**之前**组合
                // （按钮盖在热区上面、先拿到事件），这边靠"down 已被消费就放行" —— 两屏的
                // 外部行为一致，改的只是**几何**（这也是本轮唯一被允许改的东西）。
                // ==================================================================
                .pointerInput(Unit) {
                    // ---- 顶部下滑 → 回图像界面 ----
                    awaitEachGesture {
                        awaitOneWayDrag(
                            axis = DragAxis.VERTICAL,
                            positive = true,                       // 只认**往下**拖
                            startsIn = { it.y <= topZonePx },      // 起手必须在顶部那条带里
                            // 按在**会自己消费 down 的子节点**上（工具条里那两颗按钮的 `clickable`）
                            // → 这一串归那颗按钮，我们连跟都不跟。
                            // 为什么需要这一句：清屏那颗是"**按住**才生效"的（要按住转满一圈），
                            // 而按住两三秒手指必然会飘 —— 不做这个判断的话，手指往下飘过触摸容差
                            // 就被这里当成"顶部下滑"，直接退回图像界面、同时把那一圈按住取消掉
                            // （用户会觉得"我按住清屏它老是退出到图像页"）。
                            // 注意：顶部那条带的**中间**没有按钮（左边文件夹、右边清屏），
                            // 所以从中间往下一划照样能回图像界面。
                            skipIfDownConsumed = true,
                            onEnd = { total ->
                                if (total > swipeDownPx) onBackToGraph()
                            }
                        )
                    }
                }
                .pointerInput(Unit) {
                    // ---- 右边缘往左滑 → 回函数列表（并播左右切换动画）----
                    awaitEachGesture {
                        awaitOneWayDrag(
                            axis = DragAxis.HORIZONTAL,
                            positive = false,                      // 只认**往左**滑
                            // ---- 起手：**整条右边**（右 26% 屏宽 × 全高）----
                            //
                            // 判据用节点自己的真实宽度（`size.width`，就是整屏宽），
                            // 和第一屏那条左边缘手势（`FunctionList.kt`：
                            // `offset.x < size.width * SWIPE_EDGE_W`）**逐字同一套写法** ——
                            // 两屏因此都是"贴着一整条边往里滑"，不再有"拿短边当屏高"的偏差。
                            // 高度**不设上下限**：用户要求"至少要覆盖整条右边的高度"。
                            startsIn = { p -> p.x >= size.width * (1f - EDGE_ZONE_W) },
                            // **有意不设** `skipIfDownConsumed`：这条带里主要是工具列表，
                            // 一旦列表的滚动节点也消费 down、加了这句就会让整条手势**静默失效**
                            // （比"从 chip 上起手左滑会回列表"严重得多）。
                            //
                            // ---- 热区变这么大，为什么没有吃掉点击 / 长按 / 滚动？----
                            // 保证不来自"热区小"，而来自 [awaitOneWayDrag] 的**方向判据**：
                            //   1. 手指落下时它**一个事件都不消费**，只看起手点；
                            //   2. 只有当**水平方向**的累计位移越过触摸容差、而且方向是
                            //      **往左**时，它才"认领"这一串（之后每帧消费并回调）；
                            //   3. 方向不对（往右 / 竖直）→ **立刻退出、一个字节都不消费**，
                            //      整串手势原样让给子节点（按钮的 clickable、列表的滚动）。
                            // 于是：点一下（位移不过容差）永远走按钮；上下滚动（水平位移≈0）
                            // 永远归列表；只有"明确的左滑"才会被这条手势吃掉 ——
                            // 而"明确的左滑"在功能界面上本来就是"回列表"这个动作。
                            // 唯一的代价是"从 chip 上起手左滑也会回列表"，那正是这条手势的语义。
                            onEnd = { total ->
                                if (total < -swipeEdgePx) onSwipeDown()
                            }
                        )
                    }
                }
        ) {

            // ---- 顶部抓手：和函数列表顶部那颗同一套语言（暗示可以滑动） ----
            //
            // ⚠️ 这一颗的三个数（位置 0.038、宽 0.16、高 0.011）和第一屏
            // `FunctionList.kt` 里那颗**逐字相同**，而且**刻意都按短边**：
            // 它表达的是"离开屏幕上沿多远/多长一道小条"（控件尺寸 + 贴着边缘的位置），
            // 不是"屏幕高度的百分之几"。两屏本来就是同一个式子，所以**本来就对齐**，
            // 手机上也不会错位 —— 这一条不是"短边当屏高"那类偏差，别顺手改成 `geometry.sy`：
            // 圆表上 `sy(0.038)` = 32.3dp（vs 现在 8.85dp）会把这道小条从顶部挪到
            // 工具条和列表**中间**，圆表观感整个变了（而用户对本轮的硬要求是圆表逐像素不变）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = metrics.sizeDp * 0.038f)
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * 0.011f)
                    .clip(CircleShape)
                    .background(scheme.outlineVariant)
            )

            // ---- 顶部两颗：左上角「函数文件」文件夹、右上角清屏 ----
            //
            // 用户反馈："清屏图标和下面的功能 UI 重叠了，应该位置和样式都和第一屏的 `+` 一样，
            // 并且颜色应该是红色的。" 所以位置（行顶 [TOOLBAR_TOP]、行高 [TOOLBAR_H]）和
            // 第一屏**逐字一致**。
            //
            // 宽度这一轮改成**按这两颗按钮所在高度算的圆内宽度**（[toolbarWidthFraction]）：
            // 原来写的是 `circleWidthFraction(0.16f) * 0.93f` 这种抄过来的魔数，
            // 结果按钮的**上角**落在圆外（233dp 屏上角到圆心 123.9dp > 半径 116.5dp），
            // 而且右端伸到 85% 屏宽 —— 正好压住下面那条"最右 20% 往左滑回列表"的边缘手势。
            // 现在两颗按钮整体收进圆内（行宽 0.52 ≈ 121.2dp、右端 76%），
            // 仍然是 `SpaceBetween` 顶到两端 → **左右对称、中间剩 37.3dp** 的空当。
            //
            // 这一轮用户要求"左上角加一个按键，图标是文件夹，点击打开应用的 saves 目录"，
            // 用的是**同一行、同一套尺寸**：圆表上左右对称的一对按钮，
            // 比"左边一个小的、右边一个大的"稳当得多。
            // 底色是普通深色（`KeyPalette.normal`，`WatchIconButton` 的默认值）——
            // 它只做"打开一个列表"这件事，不像清屏那样会毁东西，所以不该抢红色。
            //
            // 位置与宽度这一轮和函数列表**同一个口径**：
            //   * 行顶按**真实屏高**算（圆屏上等于原来的 0.10 × sizeDp）；
            //   * 宽度：圆屏仍是"这两颗按钮上下沿里更窄的那处圆内宽度"，
            //     非圆屏（手机 / 方表）**铺满屏宽减左右余量** —— 两颗按钮因此
            //     和第一屏的 ⚙ / + 严丝合缝地对齐，手机上也不会再缩在中间一小条里。
            //
            // ==================================================================
            // 📁 / 🗑 这两颗的**形状**：和第一屏的 ⚙ / + 必须是同一个形状
            //
            // 用户原话："手机上的设置键、+键、删除键和文件键四个按键的位置上的所有按键
            // 都比手表上要方，长按动画的线圈不能围绕按键了，请你让按键强制保持在一个形状。"
            //
            // 形状由 `WatchIconButton`（Common.kt）强制，两屏共用同一份实现：
            //   宽 = `metrics.iconButton`（= 0.18 × 短边）
            //   高 = 宽 × ICON_BTN_ASPECT（= 0.125/0.18 ≈ 0.6944）→ **固定 1.44:1 的胶囊**
            //
            // 为什么这个比例在圆表 / 方表 / 手机上**都是同一个形状**（也就是这一处调用点
            // 为什么连一个尺寸都不许传）：
            //   * 按钮的两个数是"短边的 0.18 / 0.125"，只有大小跟着短边变；
            //   * 本行行高 = [TOOLBAR_H] × 短边 = **0.125 × 短边**，
            //     而按钮高 0.18 × 0.6944 = 0.125 —— **正好等于行高**。
            //
            // 所以父约束**夹不到**它（`Modifier.width/height` 只会按父给的上限夹一次，
            // 而上限恰好等于按钮要的尺寸），最终尺寸逐像素就是 0.18 × 0.125 个短边：
            //
            //   | 设备 | 短边 | 最终尺寸 | 形状 |
            //   |---|---|---|---|
            //   | 圆表 466px / d2 | 233dp | 41.9 × 29.1dp（83.9 × 58.3px） | 1.44:1 |
            //   | 方表 400px / d2 | 200dp | 36.0 × 25.0dp（72.0 × 50.0px） | 1.44:1 |
            //   | 手机 411dp / d2.75~3 | 411dp | 74.0 × 51.4dp（203.4 × 141.3px @2.75） | 1.44:1 |
            //
            // ⚠️ 这两个调用点（以及它们外面套的 `HoldToConfirm`）**只准传
            // icon / metrics / 颜色 / 回调**：任何 `Modifier.width/size` 都会盖掉上面这套推导。
            //
            // `HoldToConfirm` 那一侧同样不用改：它的线圈几何全按 `metrics.iconButton` 算
            // （`HoldRing` 的 0.92 / 0.615 / 0.075），外沿 = 0.995 × 0.69 个按钮边长
            // = 0.1791 × 0.1242 个短边 —— **完整落在按钮里侧**（按钮 0.18 × 0.125），
            // 所以在任何屏形上"线圈都绕着按键"，不会像用户报的那样跑到按钮外面/变成椭圆。
            // ==================================================================
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = geometry.sy(TOOLBAR_TOP))
                    .width(
                        geometry.rowWidth(
                            metrics = metrics,
                            ringFraction = toolbarWidthFraction(metrics, isRound),
                            isRound = isRound
                        )
                    )
                    .height(metrics.sizeDp * TOOLBAR_H)
                    // 入场：淡入 + 从 0.90 放大，和五栏的入场同一个写法（只是不等延迟）
                    .graphicsLayer {
                        alpha = topIn
                        val s = 0.90f + 0.10f * topIn
                        scaleX = s
                        scaleY = s
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「函数文件」：进 saves 目录（列表里点开/长按改名/左滑删除）
                WatchIconButton(
                    icon = FolderIcon,
                    contentDescription = "函数文件",
                    metrics = metrics,
                    onClick = onOpenSaves
                )

                // 清屏（垃圾桶）：**红色**，而且**长按才生效**（防误触，用户要求），
                // 长按的动画与 `+` 键那颗完全一样（同一个 HoldToConfirm 组件：
                // 从正上方开始**逆时针**画满一圈才触发）。
                // 短按刻意什么都不做 —— 这一颗是"会删东西"的键，宁可多按一会儿。
                //
                // 短按的 `onClick` 是空的，所以这里**不需要** `+` 键那个闩
                // （HoldRing 里的两道锁照旧生效，只是这里没有"短按动作"可误触发）。
                HoldToConfirm(
                    metrics = metrics,
                    ringColor = KeyPalette.danger,
                    onConfirm = onClearAll
                ) {
                    WatchIconButton(
                        icon = Icons.Filled.Delete,
                        contentDescription = "清屏（按住不放才清）",
                        metrics = metrics,
                        // 有东西可清 → 满亮的红；没东西 → 压暗一档（但仍然看得出是"红的那颗"）。
                        // 两档的数值来自 `KeyPalette.dangerAlpha` / `DANGER_DIM_ALPHA`
                        // （和"函数文件"那一屏的删除键**同一个暗档**）。
                        // 这一档**渐变过去**（180ms）：画完图形/清完屏时这颗键的"可用感"
                        // 是慢慢亮/暗的，不会啪一下换色。
                        containerColor = KeyPalette.danger.copy(alpha = trashAlpha),
                        contentColor = KeyPalette.text,
                        // 短按不做事：防误触。清空能力全在长按上（见 onClearAll）。
                        onClick = {}
                    )
                }
            }

            // ---- 五栏工具 ----
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // 上沿：真实屏高 × LIST_TOP（圆屏上逐像素等于原来的 0.245 × sizeDp）
                    .offset(y = geometry.sy(LIST_TOP))
                    .fillMaxWidth()
                    // 左右余量只在非圆屏上生效：圆屏上 chip 行的宽度本来就是按弧宽收过的
                    // （最宽 0.90 屏宽，离屏幕边还有 0.05 屏宽），再加边距等于把圆表上的
                    // chip 又缩窄一圈 —— 圆屏这一支**一点都不能动**。
                    .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))
                    // ---- 高度：从上面这个上沿一路铺到**屏幕底部**（只留下边距）----
                    //
                    // 这一屏在圆表上原来还有 0.06 屏高没用上（LIST_BOTTOM = 0.94）；
                    // 现在把这块也让给列表，底部余量取同一个物理距离 ——
                    // 于是"五栏到底还是排得完"的结论不变，而手机上五栏能真正铺满全屏，
                    // 长条屏下面不会再有空白。
                    .height(geometry.listHeight(metrics, geometry.sy(LIST_TOP))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * SECTION_GAP)
            ) {
                // 五栏的**顺序**就是用户列的顺序（基本工具 → 度量 → 变换 → 作图 → 直线），
                // 也就是 `ToolSection` 的声明顺序，不另外排。
                itemsIndexed(ToolSection.entries.toList(), key = { _, s -> s.name }) { index, section ->
                    // 这一栏里**每一个 chip 行**的宽度：按它此刻在屏幕上的高度算。
                    // 列表能滚，所以"第几栏"和"在屏幕的哪个高度"不是一回事 ——
                    // 必须读 `listState.layoutInfo` 里这一栏**当前**的偏移，见
                    // [chipRowWidthFractions]（上一版是按 sectionIndex 估算的固定值，
                    // 用户说的"按键没有自动适配屏幕宽度"就是它）。
                    val rowFractions = chipRowWidthFractions(
                        listState = listState,
                        itemKey = section.name,
                        rowCount = (FunctionTool.bySection(section).size + 1) / 2,
                        listTopPx = listTopPx,
                        screenPx = screenHeightPx,
                        metrics = metrics,
                        isRound = isRound
                    )
                    // 这一栏的入场进度：整屏 `entered` 一翻就把五栏依次点亮。
                    // 状态**放在这一项里面**（不在屏幕层共享）：LazyColumn 的行会被
                    // 回收重建，而 `entered` 那时已经是 true，重进来的栏直接就是 1 ——
                    // 所以来回滚不会重播（和函数列表那边 `RowEntrance` 同一个坑、同一个解法）。
                    val entrance by animateFloatAsState(
                        targetValue = if (entered) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = SECTION_IN_MS,
                            delayMillis = index * SECTION_STAGGER_MS,
                            easing = FastOutSlowInEasing
                        )
                    )
                    ToolSectionBlock(
                        section = section,
                        activeTool = activeTool,
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        widthFractions = rowFractions,
                        entrance = entrance,
                        onSelectTool = onSelectTool
                    )
                }
            }

            // ---- 右侧竖条（**只剩看得见的抓手**）----
            //
            // 用户要求："下面的条不要，右边放个条，滑了播放左右动画，返回到函数列表。"
            // 方向是**往左**（把这一屏往左推走、列表从右边滑回来），和 `GraphScreen`
            // 里那条横向转场是同一个空间隐喻。
            //
            // 手势本体已经挪到**根节点**上（见本函数开头那段注释）。这里只剩一道看得见的
            // 竖抓手 —— 手势藏得太深用户不知道有这个屏，和顶部/左侧那几道是同一套
            // "这里能滑"的语言。
            //
            // 几何**跟着热区走**（用户本轮要求"至少要覆盖整条右边的高度"，见 [EDGE_ZONE_W]）：
            //   * 容器 = 整条屏高（`fillMaxHeight`）× 右 26% **真实屏宽**（`geometry.sx`，
            //     不再是 `sizeDp` —— 手机上短边是屏宽，用它算出来的带子和热区会对不上）；
            //   * 抓手胶囊画在这个容器的**竖直正中**（`CenterEnd`），和第一屏左边那道
            //     `Alignment.CenterStart` 的小竖条是同一种摆法（那道也是"短条 + 宽热区"）。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(geometry.sx(EDGE_ZONE_W)),
                contentAlignment = Alignment.CenterEnd
            ) {
                // 看得见的竖抓手（和顶部/左侧那几道同一套语言：小圆角条）
                Box(
                    modifier = Modifier
                        .padding(end = metrics.sizeDp * 0.012f)
                        .width(metrics.sizeDp * 0.011f)
                        .height(metrics.sizeDp * 0.13f)
                        .clip(CircleShape)
                        .background(scheme.outlineVariant)
                )
            }
        }
    }
}

/**
 * 一栏：一个小标题 + 两列工具 chip。
 *
 * 两列是刻意的：最长的小标题/工具名是四个汉字（"角平分线"、"轴对称"），
 * 圆屏中间那一带的可用宽度是 233dp × 0.8 左右，两列时每个 chip 约 90dp ——
 * `fontTiny`（≈9.8sp）放四个汉字绰绰有余，而三列就会挤。
 *
 * **宽度不是一个数，是一行一个数**（[widthFractions]）：这一屏的列表能滚，
 * 所以同一个 chip 在滚到屏幕中间时比滚到上下两端时宽 —— 用户要的
 * "和函数列表一样自动适配屏幕宽度"就是这个。列宽因此交给调用方按**此刻的高度**算，
 * 这里只负责用它（和 `FunctionRow` 收 `widthFraction` 的分工完全一样）。
 *
 * @param widthFractions 每个 chip 行（第 0、1、2… 行）占屏宽的比例，
 *                       由 [chipRowWidthFractions] 按各行的**当前高度**算出来；
 *                       非圆屏上是一串 1f（表示"宽度交给 [ScreenGeometry.rowWidth] 铺满"）。
 * @param entrance 入场进度（0 → 1）：这一栏淡入 + 从 0.94 放到 1。
 */
@Composable
private fun ToolSectionBlock(
    section: ToolSection,
    activeTool: FunctionTool,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    widthFractions: List<Float>,
    entrance: Float,
    onSelectTool: (FunctionTool) -> Unit
) {
    val tools = FunctionTool.bySection(section)
    // 小标题宽度跟着它**下面那一行** chip 走（标题正好在那一行上方，
    // 两者在圆里占的高度几乎一样）。拿不到就退回满宽，反正下一帧就有了。
    val titleWidth = widthFractions.firstOrNull() ?: SQUARE_ROW_WIDTH

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 入场只动图层：栏的占位尺寸从头到尾不变，所以五栏不会在入场时互相挤动
            .graphicsLayer {
                alpha = entrance
                val s = 0.94f + 0.06f * entrance
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = section.title,
            fontSize = metrics.fontTiny,
            color = KeyPalette.textMuted,
            textAlign = TextAlign.Center,
            // 小标题给足一行的高度：字号是 fontTiny（≈9.8sp），
            // 给 0.055 × 短边（233dp 圆屏上 ≈12.8dp）刚好一行不挤压；
            // 手机上短边 = 屏宽（≈22.6dp），只是上下更宽松，不会挤压文字。
            modifier = Modifier
                .width(geometry.rowWidth(metrics, titleWidth, isRound))
                .height(metrics.sizeDp * SECTION_TITLE_H)
        )

        // 每两个一排（`chunked(2)`），奇数个时右边留空 —— 和示例库那边同一个写法。
        tools.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                modifier = Modifier
                    // 这一行 chip 的宽度：圆屏 = 它**此刻**所在高度的圆内宽度（见 [widthFractions]）、
                    // 非圆屏 = 铺满屏宽减左右余量（和列表行同一个口径 ——
                    // 手机上原来 chip 行只有"短边 × 0.90"宽，屏上留下窄窄一列）。
                    .width(
                        geometry.rowWidth(
                            metrics = metrics,
                            ringFraction = widthFractions.getOrNull(rowIndex) ?: SQUARE_ROW_WIDTH,
                            isRound = isRound
                        )
                    )
                    .height(metrics.sizeDp * CHIP_H)
                    .padding(vertical = metrics.sizeDp * 0.004f),
                horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.014f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pair.forEach { tool ->
                    ToolChip(
                        label = tool.label,
                        // 选中态 = 强调色（KeyPalette.accent），
                        // 圆点上再叠一圈白描边，扫一眼就能找到"现在在哪个模式"
                        selected = tool == activeTool,
                        metrics = metrics,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        onClick = { onSelectTool(tool) }
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 一颗工具 chip：选中态用**单一底色渐染**（160ms），外加"新选中时弹一下"。
 *
 * ## 本轮删掉了那层"外发光"（用户报："功能界面的工具选取之后蓝色有残影"）
 *
 * 以前这里在 chip 后面垫了一块**比它大一圈（+8% 宽 / +10% 高）的实心 accent 胶囊**，
 * `alpha = sel × 0.55`。读代码之后确认"残影"就是它，而且有**两个**来源：
 *
 *  1. **它在选中态下一直亮着**（`alpha` 跟的是 `sel`，不是"切换那一下"）——
 *     一颗本来就是 accent 底色的胶囊，外面再套一圈同色的半透明 accent，
 *     露出来的那圈边（左右各 4% 宽、上下各 5% 高）看起来就像"蓝色溢出来了、
 *     没涂干净"。用户说的"残影"就是这一圈**永远在**的蓝边。
 *  2. **切换时更明显**：旧 chip 的 `sel` 用 160ms 淡回 0，而 `MiniChip` 自己的底色是
 *     **瞬间**换回深灰的（`Common.kt` 里那个 `when` 没有任何动画），于是这 160ms 里
 *     旧 chip 是"深灰底 + 一圈正在退去的蓝"；同时新 chip 正在亮起来 ——
 *     快速连点几个工具时两端各挂一圈半透明的蓝，那正是字面意义上的"残影"。
 *
 * ## 也查过"是不是状态没归零"（免得后人以为删掉发光就万事大吉）
 *
 * `animateFloatAsState` 的 tween 会**精确**走到目标值（未选中就是 0.0），
 * `lerp(normal, accent, 0f)` 返回的就是 `KeyPalette.normal` 本身；`pop` 只在
 * "变成选中"时弹一次、切走时保持 1（不会被留在中间）。所以**没有**"停在半路"
 * 的残留状态 —— 残影不是状态 bug，是那层浮层的**存在本身**。
 * 结论：只要把它删掉、让选中态变成"一颗胶囊的底色"渐变，残影就没有载体了。
 *
 * ## 现在的语言（和项目里其它"选中"完全同一套）
 *
 * `Surface(color = lerp(normal, accent, sel))` + 文字色 `lerp(textMuted, text, sel)`，
 * 160ms + `FastOutSlowInEasing` —— 和设置页的取值 chip、函数文件列表的行选中、
 * 属性浏览器里的动作胶囊一模一样。**页面上再没有任何半透明蓝色浮层**。
 *
 * 为什么不用 `MiniChip` 了：它的底色是**瞬间**切换的（`Common.kt` 里一次 `when` 判断），
 * 而这次要的正是"底色渐染"，那个文件本轮不归我改。这里的配色、字号（`fontTiny`）、
 * 形状（`PillShape`）、按压反馈（缩放 + 一层很淡的白）全部逐条照抄 `MiniChip`，
 * 所以**静止时两者看不出区别**。
 *
 * 弹一下（1 → [CHIP_POP] → 1，130ms 收回）保留：它不是发光，而是"点到了"的手感，
 * 和圆点脉冲、设置页取值 chip 是同一套；结束时精确回到 1，同样不留痕迹。
 * 它和按压缩放都只动 `graphicsLayer`：chip 的占位尺寸不变，旁边的 chip 不会被挤动，
 * 点击区也不会在动画期间挪走。
 */
@Composable
private fun ToolChip(
    label: String,
    selected: Boolean,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = CHIP_SEL_MS, easing = FastOutSlowInEasing)
    )
    val pop = remember { Animatable(1f) }
    var popSeen by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (!popSeen) {
            popSeen = true
            return@LaunchedEffect
        }
        if (!selected) return@LaunchedEffect
        pop.snapTo(CHIP_POP)
        pop.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = CHIP_POP_DOWN_MS, easing = FastOutSlowInEasing)
        )
    }
    val press = rememberPressFeedback()

    Surface(
        // 底色就是"选中程度"本身：0 = 普通键深灰，1 = 强调色，中间是渐染的过渡帧。
        // 没有第二层、没有浮层、没有 alpha 叠加。
        color = lerp(KeyPalette.normal, KeyPalette.accent, sel),
        // 文字同色（80% 白 → 纯白），和 `MiniChip` 的取值一样，靠 contentColor 传下去
        contentColor = lerp(KeyPalette.textMuted, KeyPalette.text, sel),
        shape = PillShape,
        modifier = modifier
            .graphicsLayer {
                // 弹一下 × 按压缩放（两个都只动图层）
                scaleX = pop.value * press.scale
                scaleY = pop.value * press.scale
            }
            // 用自己的缩放做反馈，所以关掉涟漪（和 MiniChip 同一个做法）
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 按下时叠一层很淡的白 = 提亮一档（照抄 MiniChip：用图层 alpha 而不是
            // background(color.copy(alpha))，这样每帧只失效图层、不重组）
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

/** 表冠在这一屏的滚动系数（和函数列表那边同一个量级）。 */
private const val ROTARY_TOOL_FACTOR = 1.0f

/**
 * 顶部那两颗按钮（左上「函数文件」/ 右上「清屏」）这一对的宽度（占屏宽）。
 *
 * 和 `FunctionList.toolbarWidthFraction` **一字不差**（那边是首屏的 ⚙ / +）：
 * 圆屏按这一对按钮**上下沿里更窄**的那个高度取圆内宽度、乘 [ARC_MARGIN] 留余量、
 * 夹在 [MIN_CHIP_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间。
 * 非圆屏（手机 / 方表）返回 1f 当占位 —— 真实宽度由 [ScreenGeometry.rowWidth] 给
 * "铺满屏宽减左右余量"，和第一屏那两颗按钮、以及本屏的 chip 行同一个口径。
 *
 * 取两端而不是行中心，是因为按钮是**方块**（圆角很小）：中心 0.1625 处
 * f = 0.7379，按它算出来的行宽会让按钮的**上角**落到圆外
 * （233dp 屏：上角到圆心 123.9dp > 半径 116.5dp），圆屏上那块角是看不见的。
 * 高度取 `max(TOOLBAR_H, ICON_BTN_H) / 2`：按钮是 `Modifier.size(...)`，
 * `size()` 会被行约束夹住，所以真实上下沿只会落在"行高"和"按钮高"之间 ——
 * 按大的那个算，两种情况下都在圆内。
 *
 * 233dp 圆屏的实算：y ∈ [0.0725, 0.2525] → f = 0.5186 / 0.8689 → 取小的 × 0.95
 * = 0.4927 → 被 [MIN_CHIP_WIDTH] 抬到 **0.52** → 行宽 **121.2dp**：
 * 两颗按钮各 41.9dp、中间 **37.3dp** 空当（左右对称），右端落在 **76.0%** 屏宽，
 * 于是**不再压住**下面那条"最右 20% 往左滑回列表"的边缘手势（按中心算是 85.0%，
 * 按住清屏时手指往左一飘就会被那条手势当成"滑回列表"，而按住的动作还在继续）。
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
    return raw.coerceIn(MIN_CHIP_WIDTH, SQUARE_ROW_WIDTH)
}

/**
 * 一栏里**每一个 chip 行**的宽度（占屏宽），按各行**此刻在屏幕上的高度**算。
 *
 * 这是"照抄首屏那套"的地方 —— `FunctionList.rowWidthFraction` 对**一行**做的事，
 * 这里对"一栏里的每一行"做一遍，三个细节和那边保持一致：
 *
 *   * **量化到百分位**（`round(raw * 100) / 100`）：`LazyListState.layoutInfo`
 *     在滚动时每帧都在变，直接读它会让每个可见栏每帧重组；包一层 `derivedStateOf`
 *     再量化之后，只有"算出来的宽度真的变了"才重组（滚一整屏也就变几十次）；
 *   * 查不到这一栏时（还没测到它、或者已经滚出屏幕）退回 [SQUARE_ROW_WIDTH]，
 *     反正下一帧就有了，不会闪一个奇怪的窄栏；
 *   * 非圆屏（手机 / 方表）直接给 1f —— 那是"宽度不在这里算"的信号，
 *     调用方会把它交给 [ScreenGeometry.rowWidth] 铺满屏宽（顺带完全跳掉
 *     `circleWidthFraction` 那一套圆屏推算）。
 *
 * 行内的高度是**按本文件的排版常量推**出来的，不是猜的：
 * 一栏 = 小标题（[SECTION_TITLE_H]）+ 若干个 chip 行（每行 [CHIP_H]，行内上下各
 * 0.004 屏高的内边距已经算在 [CHIP_H] 里面），所以第 i 行的中心在
 * `栏顶 + SECTION_TITLE_H + CHIP_H × (i + 0.5)`。栏顶取 `layoutInfo` 里量到的
 * **真实**偏移（含栏间距），所以滚起来是跟手的。
 *
 * @param itemKey 这一栏在 LazyColumn 里的 `key`（就是 `ToolSection.name`）
 * @param rowCount 这一栏有几个 chip 行（`(工具数 + 1) / 2`）
 * @param listTopPx 列表上沿在屏幕上的 y（像素）
 * @param screenPx **真实屏高**（像素）—— 两者相除才是"屏幕高度的百分之几"。
 *                 原来是短边 `metrics.sizePx`（圆表上两者相等），手机长条屏上必须换。
 */
@Composable
private fun chipRowWidthFractions(
    listState: LazyListState,
    itemKey: Any,
    rowCount: Int,
    listTopPx: Float,
    screenPx: Float,
    metrics: WatchMetrics,
    isRound: Boolean
): List<Float> {
    // 非圆屏（手机 / 方表）：宽度不在这里算 —— 一串 1f 是"忽略这个比例"的信号，
    // 调用方会把它交给 [ScreenGeometry.rowWidth] 铺满屏宽（和第一步 `rowWidthFraction`
    // 完全同一套约定），顺带完全跳掉 `circleWidthFraction` 那一套圆屏推算。
    if (!isRound) return remember(rowCount) { List(rowCount) { 1f } }
    val fractions by remember(itemKey, rowCount) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
            // 这一栏还没被测到（第一次组合那一帧）：按"圆屏最宽处"给（弧宽在 0.5 屏高
            // 处正好是满宽，乘余量后就是上限），下一帧就有真值 ——
            // 和 `FunctionList.rowWidthFraction` 的兜底是同一个做法、同一个理由。
            if (info == null) {
                return@derivedStateOf List(rowCount) {
                    (kotlin.math.round(ARC_MARGIN * 100f) / 100f)
                        .coerceIn(MIN_CHIP_WIDTH, SQUARE_ROW_WIDTH)
                }
            }
            // 这一栏的**上沿**落在屏幕高度的百分之几处
            val sectionTop = (listTopPx + info.offset) / screenPx
            List(rowCount) { i ->
                val centerY = sectionTop + SECTION_TITLE_H + CHIP_H * (i + 0.5f)
                val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
                (kotlin.math.round(raw * 100f) / 100f)
                    .coerceIn(MIN_CHIP_WIDTH, SQUARE_ROW_WIDTH)
            }
        }
    }
    return fractions
}

// ===================================================================================
// 列表里的「对象行」相关（用户要求"度量同步到函数列表里"，见 FunctionList.kt）
// ===================================================================================

/**
 * 调色板当前在给**谁**调色。
 *
 * 函数行和几何对象行共用同一个调色板面板（用户要求"度量在函数列表里可以调节颜色"），
 * 所以面板必须知道选出来的颜色该交给哪一路回调（`GraphState.setColor`
 * 还是 `GraphState.setObjectColor`）。用 sealed 而不是两个 `Long?`：
 * 两个独立状态会出现"两个都非空、两层面板叠在一起"的情况。
 */
internal sealed interface ColorTarget {
    val id: Long

    data class OfFunction(override val id: Long) : ColorTarget
    data class OfObject(override val id: Long) : ColorTarget
}

/**
 * 对象行第二行的小字：一句话说明"这是什么东西"。
 *
 * 分工是：第一行放名字（点是大写字母，**其余对象都是小写字母**），
 * 第二行放**看得见的数据** —— 度量写数值 + 它是拿什么量出来的
 * （`36.87° · 角度（c，d）`、`5 · 距离（A，B）`），点写坐标，
 * 线写**它的方程**（`y = 2x + 1`，见下）。列表里能直接读到数值/方程，
 * 用户就不用回图像界面数刻度了。
 *
 * ## 直线那一栏本轮起写 `y = kx + b`（用户原话：
 * "在使用任何方式生成直线时，请在函数列表里的直线一栏写出直线的 y=kx+b 方程。"）
 *
 * "任何方式"指的是**所有**会产出 `GraphLineObject` 的路：直线/线段/射线工具直接画的、
 * 垂线/平行线/中垂线/角平分线/切线工具生成的、以及轴对称/中心对称的结果。
 * 它们全都是同一类对象，所以只需要在这**唯一**一处摘要里写方程 ——
 * 生成路径一条都不用改（这也是"方程从锚点现算"的好处，见 [lineEquationSummary]）。
 *
 * 各种退化情形的写法（竖直线 `x = 3`、水平线 `y = 5`、`k = ±1` 不写 `1x`、
 * `b = 0` 不写 `+ 0`、`b < 0` 写 `- 3` 而不是 `+ -3`）都有离线单测钉着
 * （`PointCoordinatesTest` + `tools\run-tool-tests.ps1`）。
 * 线段/射线在方程后面还有定义域括号（`y = 2x + 1 (x ≥ -1)`）：只有方程的话
 * 那条"无限长的线"和图上那一段对不上。
 *
 * 排列顺序是**种类 → 方程 → role**：方程是用户这一轮点名要看的东西，
 * 放在 role（"垂线""切线"这些作图说明）前面 —— 一行小字放不下时是从右边省略的，
 * 先被截掉的必须是 role，不能是方程。
 *
 * ## 度量那一栏为什么要带上 [GraphMeasureObject.detail]
 *
 * 用户要求过："长按菜单照第 1 条删掉（点和度量对象都不弹属性气泡了）——
 * 所以'这个度量是拿哪几个点量的'这种信息**只能靠列表摘要（detail）看**，
 * 请确认摘要里仍然写得出 `角度（A，B，C）` / `距离（A，B）` 这类信息。"
 * 所以这里从原来的 `值 · 种类`（`36.87° · 角度`）改成 `值 · detail`
 * （`36.87° · 角度（C，D）`）—— `detail` 自己就含种类，所以不是加长而是**换掉**，
 * 一行小字仍然放得下（放不下就省略号，宽度本来就按圆屏圆弧算的）。
 *
 * 摘要里留着这个 `detail` 的另一层理由（本轮更明确）：对象行现在**短按什么都不做**、
 * 长按才进属性屏，所以"这个数是怎么来的"在列表上就是**唯一**能直接看到的信息 ——
 * 删掉它等于把这条信息藏进两层手势后面。
 */
internal fun objectSummary(o: GraphObject): String = when (o) {
    is GraphPointObject -> "点 (${formatNumber(o.point.x)}, ${formatNumber(o.point.y)})"

    // 种类 + 方程（+ 线段/射线的定义域）+ 作图说明。
    // 方程那一截走纯函数 `lineEquationSummary`（`GraphObjects.kt`，离线单测钉着），
    // 所以"列表里写的方程"和"图上那条线"永远是同一个来源，不会各算各的。
    // 退化直线（两个锚点重合、坐标非有限）拿不到方程 —— 那种线本来就画不出来，
    // 这里退回原来那句"只有种类 + role"的摘要。
    is GraphLineObject -> buildString {
        append(
            when (o.line.kind) {
                GLineKind.LINE -> "直线"
                GLineKind.SEGMENT -> "线段"
                GLineKind.RAY -> "射线"
            }
        )
        lineEquationSummary(o.line)?.let { append(" ").append(it) }
        if (o.role.isNotBlank()) append(" · ").append(o.role)
    }

    // 值 + "拿什么量的"（角度是两条线段的名字、距离是两点名、面积是围成它的点名）
    is GraphMeasureObject -> "${o.valueText()} · ${o.detail}"
}

/** 点是怎么来的（自由描的 / 吸到特征点）—— 用在对象**属性页**里，让用户知道它是哪种点。 */
internal fun snapKindLabel(o: GraphObject): String? {
    val k = (o as? GraphPointObject)?.kind ?: return null
    return when (k) {
        SnapKind.VERTEX -> "顶点"
        SnapKind.INTERSECTION -> "交点"
        SnapKind.ZERO -> "零点"
        SnapKind.EXTREMUM -> "极值点"
        SnapKind.POINT -> "描点"
        SnapKind.OTHER -> null
    }
}

/**
 * 对象行上那颗 `123` 按钮要的动作："把某个对象的**数值显示**开关翻一下"
 * （= `GraphState.toggleObjectShowValue(id)`）。
 *
 * ## 为什么是 CompositionLocal，而不是给 [GraphObjectRow] 加一个参数
 *
 * [GraphObjectRow] 的调用点在 `FunctionList.kt`（`FunctionListScreen` 里那个
 * `itemsIndexed(listObjects)`），而那个文件**本轮不归我改** —— 给它加一个必填参数
 * 会让那一处编译不过。所以走"环境值"这条路：真正 provide 的地方只有
 * `GraphScreen` 调 `FunctionListScreen` 的那一处（把状态层的方法塞进来），
 * 对象行从环境里取。
 *
 * ⚠️ **两处必须成对**：`GraphScreen` 里 provide 的那一行没了，这颗按钮就变成
 * "点了没反应"（默认值是空动作）。所以默认值刻意写成空动作而不是抛异常 ——
 * 预览/将来别的地方复用这一行时不该崩，但**改动这一对地方时要一起看**
 * （报告里也写了"更干净的替代做法：在 `FunctionList.kt` 传一行参数"）。
 */
internal val LocalObjectShowValueToggle = staticCompositionLocalOf<(Long) -> Unit> { { _ -> } }

/**
 * 一个几何对象在列表里的样子。
 *
 * 和函数行（`FunctionRow`）**刻意分成两种行**，不把 `FunctionRow` 硬塞两套语义：
 *  * 函数行：点一下改表达式、长按改属性、左滑删除；
 *  * 对象行：点色点切显隐、**长按色点调色**、**短按文字区什么都不做**、
 *    **长按文字区进属性屏**、左滑删除。
 *
 * ## 属性入口（本轮又改了一次，"短按唤出菜单"整套被删掉了）
 *
 * 上一轮曾经做过"点/度量**短按**进属性浮层、直线那一类**长按**进"。
 * 用户这一轮把这条路整个否掉了，原话："函数列表里面点和标量的长按菜单没了，
 * 改成点击唤出菜单了，把这个菜单去掉啊不是让你换个手势。"
 *
 * 本轮用户又给短按**派了新活**（详情页），原话："给除了函数之外点击没有菜单的加一个
 * 点击显示详情页面，因为有些点和直线里面数值太长了……显示完全。"
 * —— 注意这**不是**把上一轮那个菜单加回来：那是一个**只读**页（`Route.Details`），
 * 只显示颜色 / 名字 / 完整数据，没有任何可改的东西（改名字仍然只在长按那一屏）。
 *
 * 所以现在是**一种**手势、**一个**去处：
 *
 * | 位置 | 短按 | 长按 |
 * |---|---|---|
 * | 色点区 | 切显隐（**没动**） | 调色板（**没动**） |
 * | 文字区（名字 + 摘要 + `123` 按钮以外的地方） | **进详情页**（只读、显示完全） | **进属性屏**（可改名字；所有对象统一） |
 *
 * 两个要点：
 *  1. 短按这条路的**唯一目的**是把列表里被省略号截掉的东西看全 ——
 *     列表行是 `maxLines = 1` + `Ellipsis`，而点坐标和直线方程都可能很长。
 *     所以详情页放在短按上（最高频、最省事的那一下），而"改"留在长按。
 *  2. 长按走的是**函数行那条**回调（`onProperties(obj.id)` →
 *     `GraphScreen` 的 `state.route = Route.Properties(id)`），
 *     所以"对象的属性屏"是路由那一侧的事，这一行只负责把 id 交出去。
 *     对象 id 与函数 id 出自同一个 `GraphState.nextId()`，全局唯一。
 *
 * 度量行**不能改名字**（用户早先定的）：点名是自动分配的大写字母、标量是小写字母，
 * 而 `x(A)` 这种取坐标写法**按名字引用点**，改名会改坏别人的表达式 ——
 * 属性屏那一侧要按这些特征做适配（名字只读、点只给大写、标量只给小写）。
 *
 * @param onDetails 短按文字区：进**详情页**（`Route.Details`，只读、显示完全）。
 *   它是用户本轮新增的那条路 —— "除了函数之外点击没有菜单的加一个点击显示详情页面"。
 * @param onProperties 长按文字区：进属性屏（**所有**对象都调它）
 */
@Composable
internal fun GraphObjectRow(
    obj: GraphObject,
    metrics: WatchMetrics,
    /**
     * 这一行所在的真实屏幕几何（和 `FunctionList.FunctionRow` 同一份传法）：
     * 圆屏走弧宽、非圆屏铺满屏宽减左右余量。
     */
    geometry: ScreenGeometry,
    /** 圆屏 / 非圆屏（手机、方表）。行宽的两套口径由它二选一。 */
    isRound: Boolean,
    widthFraction: Float,
    onToggleVisible: () -> Unit,
    onPickColor: () -> Unit,
    onDetails: () -> Unit,
    onProperties: () -> Unit,
    onDelete: () -> Unit
) {
    val color = CurvePalette[obj.colorIndex.mod(CurvePalette.size)]
    val press = rememberPressFeedback(scaleAmount = 0.03f, glowAmount = 0f)
    // "数值画不画"的动作从环境里取（见 [LocalObjectShowValueToggle]：调用点在别人
    // 负责的文件里，加参数会编译不过）。只有度量行会用到它。
    val toggleShowValue = LocalObjectShowValueToggle.current

    // ---- 色点的两套反馈（用户要求"色点脉冲都按项目语言"）----
    //
    //  * `dotOn`：显隐切换的**渐染**（160ms，和 [CHIP_SEL_MS] 那一套同一个时长）——
    //    填充与描边的透明度都跟着它走，所以"实心 ↔ 空心"是过渡而不是硬切；
    //  * `dotPulse`：点到了的那一下（1.25 → 1，160ms，只动 graphicsLayer）。
    //    首次组合时不弹（`pulseSeen`），否则一进列表所有色点都会弹一遍
    //    ——和 `FunctionsScreen.ToolChip` 的 `pop` 是同一个坑、同一个解法。
    val dotOn by animateFloatAsState(
        targetValue = if (obj.visible) 1f else 0f,
        animationSpec = tween(durationMillis = CHIP_SEL_MS, easing = FastOutSlowInEasing)
    )
    val dotPulse = remember(obj.id) { Animatable(1f) }
    var pulseSeen by remember(obj.id) { mutableStateOf(false) }
    LaunchedEffect(obj.visible) {
        if (!pulseSeen) {
            pulseSeen = true
            return@LaunchedEffect
        }
        dotPulse.snapTo(1.25f)
        dotPulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = CHIP_SEL_MS, easing = FastOutSlowInEasing)
        )
    }

    // ---- 左滑删除：和函数行**同一个实现** ----
    //
    // 跟手位移、渐染进度、"先播完甩出动画再真删"、阈值（1/3 行宽）全部来自共用件
    // （`DragGestures.rememberRowSwipeDelete` + `PlotMetrics.SWIPE_DELETE_*`）——
    // 原来这里是一整段和 `FunctionList.FunctionRow` 互相抄出来的代码。
    val swipe = rememberRowSwipeDelete(obj.id, onDelete)
    // 行宽的**唯一**来源（圆屏弧宽 / 非圆屏满宽）：删除阈值、甩出距离都从它推。
    val rowWidthDp = geometry.rowWidth(metrics, widthFraction, isRound)
    val rowWidthPx = with(LocalDensity.current) {
        rowWidthDp.toPx()
    }
    val swipeProgress = swipe.progress(rowWidthPx)

    Surface(
        // 滑动过程中朝砖红渐染：和函数行同一个"松手就要删了"的暗示。
        // 对象行**不高亮选中**（没有"当前对象"这个概念），所以底色只有 normal。
        color = lerp(KeyPalette.normal, KeyPalette.danger, swipeProgress * SWIPE_DELETE_TINT),
        shape = PillShape,
        modifier = Modifier
            // 宽度和 [rowWidthPx] 同一个来源（圆屏弧宽 / 非圆屏满宽）
            .width(rowWidthDp)
            .height(metrics.sizeDp * 0.15f)
            .graphicsLayer {
                // 松手删除：停在滑到的位置再往外甩 + 缩小 + 淡出（= "扔掉"），
                // 三个派生值都来自共用件（和函数行同一套数）。
                //
                // ⚠️ 横向位移只有**一处**算式（`RowSwipeDelete.translationXPx`）：
                // 跟手位移和甩出位移都是往左的负数，所以是相加。上一版三行各写了一遍
                // `dragX - sweepShiftPx(...)`（等于往右回弹）—— 同一个符号错误抄了三份，
                // 用户看到的就是"函数条从左边移到右边去"。
                translationX = swipe.translationXPx(rowWidthPx)
                val s = press.scale * swipe.sweepScale()
                scaleX = s
                scaleY = s
                alpha = (1f - 0.10f * press.progress.value) * swipe.sweepAlpha()
            }
            // ---- 左滑删除（整行上的手势，和函数行**同一个实现**）----
            //
            // 起手位置：**整行哪里都行**（色点区、文字区、最右端都算），
            // 唯一的判据仍然是**方向** —— 往左滑 = 删除。
            //
            // ⚠️⚠️ 这里本轮**删掉了一条起手限制**，它就是用户报的
            // "除了函数之外的对象在列表都不能滑动删除"的真因：
            // 上一版这里是
            //     startsIn = { p -> p.x < rowW - (右 26% 屏宽 − 行右端那段空白) }
            // 也就是"行右侧那条带让给'右边缘往左滑回函数列表'"，起手落在那条带里的左滑
            // 会被 `awaitOneWayDrag` **静默丢弃**（startsIn 返回 false → 立刻返回、
            // 一个字节都不消费、界面上一点反馈都没有，行连跟手位移都没有）。
            //   * 那条让位的理由是**第二屏**的：第二屏右边缘往左滑 = 回函数列表，
            //     和"往左滑删除"同方向，不切开就会"又删行又跳屏"；
            //   * 可是**对象行根本不在第二屏** —— `GraphObjectRow` 的唯一调用点是
            //     `FunctionList.kt` 第一屏那个 `itemsIndexed(listObjects)`；
            //   * 而第一屏的切屏手势是**从左边缘往右滑**（`SWIPE_EDGE_W`），
            //     和删除方向天然互斥，根本不需要让位。
            // 于是那条限制在第一屏上是**纯损失**：行右侧（左滑删除最自然的落点）
            // 一划就没反应，用户试几个对象都一样 → "都不能滑动删除"。
            //
            // 现在整条手势换成共用件 `swipe.gesture(...)`：**没有** `startsIn`，
            // 和函数行逐项一致（同一个阈值常量、同一套动画、同一个"播完再删"）。
            // 第二屏的删除行为不受影响：那一屏压根没有对象行，它自己的两条边缘手势
            // （顶部下滑、右边缘左滑）仍然挂在根节点上、各自的 `startsIn` 一个字没动。
            .then(swipe.gesture(obj.id, rowWidthPx))
    ) {
        // ---- 两个手势区共用的版面尺寸（下面两区都按它算）----
        //
        // 和 `FunctionList.kt` 的 `FunctionRow` 同一套做法：**点击/长按分成两块互不重叠的区域**，
        // 而且内边距算在各自的 clickable **之内**，行里不留"点了没反应"的缝。
        //   * 色点区宽 = 色点原来的点击区 0.105 + 行左内边距 0.025；
        //   * 文字区吃掉"中间那条间隔 0.02 + 行右内边距 0.025"，并且把
        //     度量角标（"数值/隐藏"）也包在里面 —— 原来长按角标也能弹属性气泡。
        // 结果：色点与文字的视觉位置和改动前**完全一致**。
        val sidePad = metrics.sizeDp * 0.025f
        val dotHitW = metrics.sizeDp * 0.105f
        val textGap = metrics.sizeDp * 0.02f

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================= 色点区（手势区 A）=================
            //
            // 圆点 = 显示/隐藏开关，**长按调色**（和函数行完全一致的两个手势）。
            //
            // ⚠️ 这一格**绝不能**开属性页：用户对函数行提的同一条要求
            // （"色点那里长按不打开属性菜单，长按其他地方才打开"）对对象行同理 ——
            // 长按色点应该出**调色板**。
            // 做法是把原来的整行 `combinedClickable` 从 Surface 上摘下来、只挂在
            // 下面的文字区上，于是两块手势区**几何上互不重叠**，不存在
            // "外层长按把内层长按吃掉"的可能（不依赖谁先拿到 down）。
            Box(
                modifier = Modifier
                    // 点击区 = 0.105（原色点点击区）+ 0.025（原行左内边距）
                    .width(dotHitW + sidePad)
                    // 高度撑满整行：这一列从上到下都是"显隐开关"，不留死角
                    .fillMaxHeight()
                    .combinedClickable(
                        onClick = onToggleVisible,
                        onLongClick = onPickColor
                    )
                    // 内边距加在 clickable **之内**：手势区含边条，内圈圆点仍居中在
                    // 原来那 0.105 的格子里 → 观感与改动前一致。
                    .padding(start = sidePad),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(metrics.dot)
                        // "点到了"的一下：色点从 1.25 弹回 1（160ms，只动 graphicsLayer）。
                        // 和工具 chip 的 pop、`123` 按钮的渐染是同一套语言 —— 这一颗是
                        // **唯一**能切"图形画不画"的入口，点下去必须有看得见的反馈。
                        .graphicsLayer {
                            scaleX = dotPulse.value
                            scaleY = dotPulse.value
                        }
                        .clip(CircleShape)
                        // 显隐切换也**渐染**（160ms）：实心 → 空心/描边是慢慢过渡的，
                        // 不是啪一下换一个样子（`dotOn` 同时驱动填充和描边的透明度）
                        .background(color.copy(alpha = dotOn))
                        .hiddenDotBorder(borderAlpha = 1f - dotOn, metrics = metrics)
                )
            }

            // ================= 文字区（手势区 B）=================
            //
            // **每个对象都是同一套手势**：`onClick = onDetails`（短按进详情页）、
            // `onLongClick = onProperties`（长按进属性屏）。
            //
            // 上一版这里是"按对象类型分叉"的：点/度量走短按、直线那一类走长按。
            // 那一版整个删掉之后短按曾经空着（"防误触"），本轮用户又给短按派了新活：
            // **进只读的详情页**（原话："给除了函数之外点击没有菜单的加一个点击显示
            // 详情页面……显示完全"）—— 起因是点和直线的数值在列表这一行里
            // （`maxLines = 1` + 省略号）根本读不全。
            // 注意它**不是**把"短按唤出菜单"加回来：详情页只读，没有任何可改的东西。
            //
            // 用 `combinedClickable` 而不是 `clickable`：必须让"长按之后不再补一次单击"
            // （`combinedClickable` 自带这个保证）—— 否则长按进属性屏之后会紧接着
            // 又被推进详情页（两层界面叠在一起）。
            // 短按也要**消费**掉这一下 —— 否则手指抬起时事件会漏给外层的左滑删除手势。
            //
            // `interactionSource` 仍然用整行的那个 `press`：按下时整行缩小/变暗的反馈
            // 保持原样（图层缩放挂在整行的 Surface 上）。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .combinedClickable(
                        interactionSource = press.source,
                        indication = null,
                        // 短按 = 详情页（只读、显示完全）
                        onClick = onDetails,
                        onLongClick = onProperties
                    )
                    // 左/右内边距同样在手势区之内 → 中间那条缝和右端边条都归它
                    .padding(start = textGap, end = sidePad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = obj.name,
                        fontSize = metrics.fontSmall,
                        color = KeyPalette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = objectSummary(obj),
                        fontSize = metrics.fontTiny,
                        color = KeyPalette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 度量：右边跟一颗**实心/空心 `123`** 的图标按钮。
                //
                // 原来这里是一枚写着"数值/隐藏"的小胶囊，而切换只能靠**长按整行**
                // 弹出来的属性气泡 —— 用户这一轮把那个菜单删了，所以开关必须
                // **自己变成能点的东西**（原话："数值条右边的'数值'二字改成可以
                // 点击的按钮，并且把显示的内容换成实心的 123 和空心的 123，
                // 点击切换，实心和虚心对应数值是否在函数图像上显示"）。
                //
                // 它管的是 [GraphMeasureObject.showValue]（图上那行数字画不画），
                // **不是**整行的显示开关 —— 那是左边色点（[GraphObject.visible]）的事。
                // 角度上这两件事正好是"扇形画不画"和"数值画不画"，互不干扰。
                if (obj is GraphMeasureObject) {
                    MeasureValueButton(
                        showValue = obj.showValue,
                        metrics = metrics,
                        onClick = { toggleShowValue(obj.id) }
                    )
                }
            }
        }
    }
}

/**
 * 对象行圆点的描边：隐藏态画一圈 80% 白的空心圆（和函数行一致）。
 *
 * 单独抽出来只是为了让上面那段行代码读起来短一点 —— Compose 的
 * `Modifier` 链在 `if` 里再接一段会让整行缩进失控。
 *
 * [borderAlpha] 由显隐渐染的 `dotOn` 推出来（可见 = 0、隐藏 = 1），
 * 所以"填充淡出、轮廓淡入"是同一段动画 —— 圆点看起来是**化开**成空心圈的。
 */
private fun Modifier.hiddenDotBorder(borderAlpha: Float, metrics: WatchMetrics): Modifier =
    if (borderAlpha <= 0.01f) {
        this
    } else {
        this.border(
            width = metrics.sizeDp * 0.009f,
            color = KeyPalette.textMuted.copy(alpha = borderAlpha.coerceIn(0f, 1f)),
            shape = CircleShape
        )
    }

// ===================================================================================
// 数值显示开关：实心 / 空心 `123`（用户要求那一轮）
// ===================================================================================

/**
 * `123` 按钮的边长（占短边）。
 *
 * ⚠️ 和 `MeasureValueButton` 里 `Modifier.size(...)` 用的是**同一个数**：
 * 字形的目标框要按"按钮的真实可见区"算，两处各写一个 0.115 迟早会漂，
 * 而漂了的后果是"数字压到圆边上/缩得太小"这种只在部分屏形上才暴露的 bug。
 */
private const val MEASURE_123_SIDE = 0.115f

/** 按钮的内边距（占短边）。字形只允许画在这块内边距之内的画布上（同上，只写一份）。 */
private const val MEASURE_123_PAD = 0.010f

/**
 * 字形（含空心描边的**外沿**）不得越过的安全半径，占按钮边长。
 *
 * 按钮是正方形 + `PillShape` 裁剪 → 可见区其实是一个**圆**。
 * `123` 的墨迹包围盒是横长的矩形，离圆心最远的是它的**四个角**：只按"内边距之后的
 * 方形画布"去放，角会先撞上圆弧被裁掉（"3"的右下、"1"的左下最明显）。
 * 0.44 = `0.5 × 0.88`：留 12% 给描边外沿和抗锯齿 —— 这就是"按圆形可见区算，
 * 四周留出与圆相切的安全边"。
 */
private const val GLYPH_SAFE_R = 0.44f

/** 空心态的描边线宽（相对**字形高**）：0.11 是"看得出是空心轮廓、又不会把 2/3 糊成一坨"的一档。 */
private const val GLYPH_STROKE_H = 0.11f

/** 取字形用的参考字号（px）。只是一个"量包围盒"的中间量，最终字号由它按比例算出来。 */
private const val GLYPH_PROBE_TEXT_SIZE = 100f

/** 画哪几个字。手表上这颗按钮只画这三个，够用了。 */
private const val GLYPH_TEXT = "123"

/**
 * 一条**按按钮真实尺寸算好、并且已经精确居中**的 `123` 字形轮廓。
 *
 * @param path 坐标已经是画布坐标（已平移到画布正中），直接 `drawPath` 即可
 * @param strokeWidth 空心态的描边宽度（px，按字形高算出来的）
 */
private class Glyph123(val path: Path, val strokeWidth: Float)

/**
 * 取 `123` 的**字形轮廓路径**，缩放到目标框、再按**墨迹包围盒**搬到画布正中。
 *
 * ## 为什么用 `Paint.getTextPath` 而不是 `material-icons-extended`
 *
 * 用户要求"图标手画……不要用 `material-icons-extended`"（本机缓存里也确实没有那个构件）。
 * 上一版是拿七段数码管的七条线段拼出来的，问题是它**画的是数码管**：
 * 用户要的是"123 是空心的，意思是只有轮廓有线条，而不是像老式计算器上面的一样"。
 * 七段骨架无论怎么描边都还是计算器那种断开的管子，所以这一版直接取**真字体的轮廓**：
 * `android.graphics.Paint.getTextPath(...)` 拿出来的是闭合的字形路径，
 * 填充它 = 实心、描边它 = 只有轮廓 —— 一副形状数据同时满足两种形态。
 *
 * （`TextStyle.drawStyle = Stroke` + `drawText` 也能描边，但 `drawStyle` 在
 * Compose 1.7 上仍是实验性 API，而且量包围盒还要再过一层 `TextMeasurer`；
 * `getTextPath` 是老 API、`computeBounds` 直接给墨迹框，这条路更短也更可控。）
 *
 * ## 目标框与居中（"不靠估计"这两个字是这一版的重点）
 *
 * 1. **量**：按参考字号取一次路径，`computeBounds(rect, true)` 拿墨迹包围盒
 *    （`exact = true` 是"按曲线算紧包围盒" —— 我们要的就是"墨迹到哪儿"，
 *    差一点"精确居中"就变成"差不多居中"）；
 * 2. **算目标宽**：宽高比 A 定下来之后，最大的那个"外接矩形能塞进安全圆"的宽度是
 *    `w = R / (0.5·√(1 + 1/A²) + 0.5·STROKE/A)` —— 前半是包围盒的半个对角线，
 *    后半是描边往外撑出去的一半。**这一步就是"按圆形可见区算"**，
 *    而不是按方形画布估；再和画布（内边距之后的可绘区）取小的那个兜底；
 * 3. **定字号**：字形随字号线性缩放，所以 `textSize = 参考字号 × 目标宽 / 参考宽`
 *    —— 一次到位，不用迭代试算；
 * 4. **居中**：把新路径按 `包围盒.centerX/Y` **平移到画布中心**。
 *    竖直方向**必须**按墨迹包围盒居中：`FontMetrics` 的 ascent/descent 分布不均
 *    （ascent 里那一大截是给 ä 这种带符字母留的，数字"123"只占 cap height），
 *    按 baseline 或按 ascent/descent 居中都会看起来偏上。
 *
 * @param canvasSidePx 可绘区边长（= 按钮边长 − 2 × 内边距，px）
 * @param safeRadiusPx 字形（含描边外沿）不得越过的半径（px）
 * @return 取不到字形（理论上不会）时返回 null —— 调用方那时什么都不画，绝不崩
 */
private fun build123Glyph(canvasSidePx: Float, safeRadiusPx: Float): Glyph123? {
    val bounds = android.graphics.RectF()

    fun outline(textSize: Float): android.graphics.Path {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // 默认（Roboto）**加粗**：细体轮廓在 26dp 的按钮里会细得像没画；
            // 加粗以后描边一圈仍然分得开（它是轮廓，不是七段那种实心管子）。
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            this.textSize = textSize
        }
        return android.graphics.Path().also { p ->
            paint.getTextPath(GLYPH_TEXT, 0, GLYPH_TEXT.length, 0f, 0f, p)
        }
    }

    // ---- ① 量：参考字号下的墨迹包围盒 ----
    val probe = outline(GLYPH_PROBE_TEXT_SIZE)
    probe.computeBounds(bounds, true)
    val probeW = bounds.width()
    val probeH = bounds.height()
    // 宽或高为 0 = 没量到字形（字体坏掉/被裁剪），直接放弃，别拿 NaN 去画
    if (probeW <= 0f || probeH <= 0f) return null
    val aspect = probeW / probeH

    // ---- ② 目标宽：外接矩形（含描边外沿）能塞进安全圆的那个宽度 ----
    val k = 0.5f * kotlin.math.sqrt(1f + 1f / (aspect * aspect)) +
        0.5f * GLYPH_STROKE_H / aspect
    // 再和可绘区取小的那个：极端比例下"圆"不是瓶颈时，别让字画出内边距
    val targetW = kotlin.math.min(canvasSidePx, safeRadiusPx / k)

    // ---- ③ 定字号：字形尺寸随字号线性缩放，一次算到位 ----
    val gp = outline(GLYPH_PROBE_TEXT_SIZE * (targetW / probeW))
    gp.computeBounds(bounds, true)
    val w = bounds.width()
    val h = bounds.height()
    if (w <= 0f || h <= 0f) return null

    // ---- ④ 居中：把**墨迹包围盒的中心**搬到画布中心（水平 + 竖直都是它）----
    gp.offset(
        canvasSidePx / 2f - bounds.centerX(),
        canvasSidePx / 2f - bounds.centerY()
    )

    return Glyph123(
        path = gp.asComposePath(),
        strokeWidth = h * GLYPH_STROKE_H
    )
}

/**
 * 度量行右端的**数值显示开关**：一颗画着 `123` 的图标按钮。
 *
 * 用户原话："数值条右边的'数值'二字改成可以点击的按钮，并且把显示的内容换成
 * 实心的 123 和空心的 123，点击切换，实心和虚心对应数值是否在函数图像上显示。"
 *
 *  * **实心 `123`** = [GraphMeasureObject.showValue] 为 true = 数值**画在图上**；
 *  * **空心 `123`** = false = 不画；
 *  * 点一下切换。它管的**只是那行数字**，整条对象行画不画是左边色点的事
 *    （角度上正好是"扇形画不画"和"数值画不画"两件事，见 `GraphObjects.kt`）。
 *
 * ## 动画（沿用本项目那套 160ms 的语言）
 *
 *   * **底色 + 数字颜色**跟着 `showValue` 用 [CHIP_SEL_MS]（160ms）的 tween 渐染
 *     （`KeyPalette.normal` ↔ `KeyPalette.accent`），和属性屏里的动作胶囊、
 *     工具 chip 的选中底色是同一个时长 —— 几处"切换"一套手感；
 *   * **字形本身**从实心渐变到空心：同一个字形上，填充层 alpha = `on`、
 *     描边层 alpha = `1 − on`，两层交叉淡入淡出 —— 所以点下去是"数字慢慢变空"，
 *     不是啪一下换一个图标；
 *   * 按压用 [rememberPressFeedback]（只动 `graphicsLayer` 的缩放），
 *     和这一屏其它按钮同一个手感。
 *
 * 字形是**真字体的轮廓**（见 [build123Glyph]），并且按包围盒精确居中 ——
 * 上一版是七段数码管骨架 + 估出来的偏移（`cellW * 0.12f` 那种），
 * 用户报的"123 没有居中显示在圆的中间"就是它。
 *
 * 动画只动颜色、alpha 和图层缩放，**按钮的占位尺寸从头到尾不变** —— 旁边的名字/摘要
 * 不会被挤动，点击区也不会在动画期间挪走。
 */
@Composable
private fun MeasureValueButton(
    showValue: Boolean,
    metrics: WatchMetrics,
    onClick: () -> Unit
) {
    val on by animateFloatAsState(
        targetValue = if (showValue) 1f else 0f,
        animationSpec = tween(durationMillis = CHIP_SEL_MS, easing = FastOutSlowInEasing)
    )
    val press = rememberPressFeedback(scaleAmount = 0.10f, glowAmount = 0f)
    // 底色和字形颜色都从"未选中"渐变到"选中"，和上面那个 t 用同一个值
    val container = lerp(KeyPalette.normal, KeyPalette.accent, on)
    val glyphColor = lerp(KeyPalette.textMuted, KeyPalette.text, on)

    // ---- 字形：从按钮的**真实尺寸**算一次，之后每帧只画 ----
    //
    // 为什么在 `remember` 里算、而不是在绘制时算：`on` 是 160ms 的动画，
    // 把"取字形路径 + 量包围盒"放进绘制等于每秒分配几十条路径和几十个 Paint；
    // 尺寸变了才重算，绘制那条路上就只剩两次 `drawPath`。
    val density = LocalDensity.current
    val sidePx = with(density) { (metrics.sizeDp * MEASURE_123_SIDE).toPx() }
    val canvasSidePx = with(density) {
        (metrics.sizeDp * (MEASURE_123_SIDE - MEASURE_123_PAD * 2f)).toPx()
    }
    val glyph123 = remember(sidePx, canvasSidePx) {
        build123Glyph(
            canvasSidePx = canvasSidePx,
            // 可见区是**圆**（正方形 + PillShape 裁剪），安全半径按按钮边长算
            safeRadiusPx = sidePx * GLYPH_SAFE_R
        )
    }

    Box(
        modifier = Modifier
            // 0.115 屏宽 ≈ 27dp：和色点的点击区同一个量级，手指够得着，
            // 而且塞得下"三个数字"（再小就只能画两个了）。
            // ⚠️ 这个数就是 [MEASURE_123_SIDE]（字形的目标框按它推），别再写字面量。
            .size(metrics.sizeDp * MEASURE_123_SIDE)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .clip(PillShape)
            .background(container)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
            // 内边距让数字离胶囊边远一点（1% 屏宽 ≈ 2.3dp）
            .padding(metrics.sizeDp * MEASURE_123_PAD),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 取不到字形（理论上不会）就什么都不画 —— 一颗空按钮也好过崩掉
            val g = glyph123 ?: return@Canvas
            // ---- 实心 ↔ 空心 = 同一个字形上的两层交叉淡入淡出 ----
            //
            // 层 alpha 用 `on` 反着来（填充 = on、描边 = 1 − on），两层共用**同一条**
            // 路径，所以过渡中间看到的是"轮廓还在、里面越来越淡"，也就是"数字慢慢变空"。
            //
            // 为什么不用上一版那套"粗线打底 + 用按钮底色盖中间"：那个做法要求
            // "盖中间"的颜色和按钮底色**逐像素相同**，而底色本身在这 160ms 里
            // 从 `normal` 渐染到 `accent` —— 只要两者有一点色差，"空心"态的数字里
            // 就残留一圈脏边（而且它怎么描都还是七段数码管，不是用户要的"轮廓"）。
            // 交叉淡入淡出只跟 alpha 有关，底色怎么变都不会露出破绽。
            if (on > 0.01f) {
                // 实心 = 填充整个字形
                drawPath(path = g.path, color = glyphColor.copy(alpha = on))
            }
            if (on < 0.99f) {
                // 空心 = **只描字形轮廓**（线宽按字形高算，见 [GLYPH_STROKE_H]）
                drawPath(
                    path = g.path,
                    color = glyphColor.copy(alpha = 1f - on),
                    style = Stroke(
                        width = g.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

// （这里原来还有一整套七段数码管的骨架：`Seg` / `SEG_A…SEG_D` / `GLYPH_123` /
//  `draw123Glyphs`。本轮全删了 —— 用户要的是"只有轮廓有线条"的真字形，
//  而那套无论怎么调都是"老式计算器"的数字；字形本身改由 [build123Glyph] 取。)

// （这里原来是 `ObjectPropertiesBubble` —— 函数列表里那个"对象属性浮层"。
//  本轮**整个删掉**了，包括它的入口：用户要的是"把这个菜单去掉啊不是让你换个手势"，
//  所以对象行的文字区短按什么都不做、长按走和函数行**同一条**回调
//  `onProperties(obj.id)` → `GraphScreen` 那一侧把 `state.route` 置成
//  `Route.Properties(id)`，并负责分辨"这是函数还是对象"、落到对应的属性屏。
//
//  删掉它的连带好处：这个浮层是"列表里唯一一处带全屏遮罩的浮层"，
//  它开着的时候下面那一行还可能被左滑删掉（浮层的 `obj` 是父层现取的，
//  删掉后 `propsObj` 变 null、浮层自己消失）—— 这类只有浮层才有的状态问题一起没了。
//
//  另外它原来也是这一屏**唯一**一处"`.border(...)` 写在 `graphicsLayer` 左边"的写法
//  （1dp 描边不跟着 `appear` 淡出，和函数列表那个调色板面板是同一个 bug），
//  删除之后这一屏**没有**同类问题了 —— 也就是说：这一屏不需要 `bubbleFade`。）

/**
 * 属性页第一行：这个对象**是什么**。
 *
 * 点写"点"、线写它的种类（+ 作图工具留下的说明，如"垂线"）、
 * 度量写它的种类（"角度"/"距离"/"面积"）。
 *
 * `internal` 而不是 `private`：画布上的**对象属性卡**（`GraphScreen.ObjectPropertyCard`）
 * 也要用它 —— 列表那侧和画布那侧对"这是什么"必须**说得一模一样**，
 * 所以只有这一份实现。
 */
internal fun propertyKindText(o: GraphObject): String = when (o) {
    is GraphPointObject -> "点"

    is GraphLineObject -> buildString {
        append(
            when (o.line.kind) {
                GLineKind.LINE -> "直线"
                GLineKind.SEGMENT -> "线段"
                GLineKind.RAY -> "射线"
            }
        )
        if (o.role.isNotBlank()) append(" · ").append(o.role)
    }

    is GraphMeasureObject -> o.kind.label
}

/**
 * 属性页第二行：这个对象**现在的数据**；没有可说的就返回 null（那一行不占位）。
 *
 *  * 点 → 坐标（+ 它是怎么来的：描点/交点/零点/极值点……）；
 *  * 度量 → `36.87° · 角度（c，d）`（数值 + **拿哪几个点/线段量出来的**，
 *    也就是列表摘要里用的那个 `detail`）；
 *  * 直线/线段/射线 → **它的方程**（本轮起不再是 null）：用户要求
 *    "在使用任何方式生成直线时，请在函数列表里的直线一栏写出直线的 y=kx+b 方程"，
 *    属性页和画布上的属性卡自然也一起写上 —— 那两处的第二行本来就是"这个对象
 *    现在的数据"，一条直线的数据就是它的方程。写法与列表行**同一个函数**
 *    （[lineEquationSummary]），不会出现两边不一致。
 *
 * `internal` 的理由同 [propertyKindText]：列表属性页与画布属性卡共用同一份说法。
 */
internal fun propertyDataText(o: GraphObject): String? = when (o) {
    is GraphPointObject ->
        "点 (${formatNumber(o.point.x)}, ${formatNumber(o.point.y)})" +
            (snapKindLabel(o)?.let { " · $it" } ?: "")

    is GraphLineObject -> lineEquationSummary(o.line)

    is GraphMeasureObject -> "${o.valueText()} · ${o.detail}"
}

// （这里原来还有 `PropertyActionChip` —— 属性浮层里那几颗动作胶囊。
//  grep 过全仓库：它的使用者**只有** `ObjectPropertiesBubble`，浮层删了它就没用了，
//  所以一起删掉。这两个 `property*Text` 函数**必须留着**：`GraphScreen.kt`
//  的画布属性卡在用它们，删了这一屏和画布那一侧编译都过不去。）
