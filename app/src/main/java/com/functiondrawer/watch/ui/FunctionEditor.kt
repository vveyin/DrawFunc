// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.ui.keyboard.KeyAction
import com.functiondrawer.watch.ui.keyboard.KeyPage
import com.functiondrawer.watch.ui.keyboard.LetterCase
import com.functiondrawer.watch.ui.keyboard.editText
import com.functiondrawer.watch.ui.theme.PillShape
import com.functiondrawer.watch.ui.theme.watchShapeOf
import kotlinx.coroutines.flow.Flow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// 纵向布局（比例都是**真实屏高**的占比；圆表上真实屏高 == 短边，所以一个数都没变）。
//
// 这一套比例是照着 233dp / 466px 圆屏排出来的，四种模式**共用同一套**：
// 顶部工具条 → 表达式卡片（永远只有一行）→ 选择栏 → KEYBOARD_ROWS(3) 行按键网格。
// 范围预设不单独占一行，而是变成选择栏末尾的第 5 个 chip ——
// 这样各模式布局完全一致，按键也不会被挤小。
//
// ⚠️ 本轮改的是这些比例的**换算基准**（原来是 `sizeDp × 比例`，`sizeDp` 是**短边**）：
// 圆表上短边 == 屏高，所以下面这一整套推导、这些数字、以及圆表上的画面**逐像素不变**；
// 手机上短边是**屏宽**，原来整套分区只占真机 850dp 高的上半截（键盘最后一行排到
// 388dp 就没了，底下空 460dp）——用户原话"函数编辑页面也没有全屏显示"。
// 现在位置一律 `geometry.sy(比例)`（真实屏高），控件高度/字号仍按短边缩放。
//
// 纵向位置是"夹"出来的：圆屏越靠上下越窄，所以工具条不能贴着顶边
// （否则两个图标按钮的顶角会被表壳切掉），按键网格的底边也要收在
// 0.945 而不是更靠下 —— 否则最下面那行会窄到点不准。
//
// **「一个格子高」只有一个来源**：选择栏那 1 行和按键那 3 行是同一档高度，
// 选择栏与网格之间的缝也等于行与行之间的缝（都是 GAP_FRACTION）：
//   (GRID_BOTTOM − TAB_Y) / (1 + KEYBOARD_ROWS) = (0.945 − 0.322) / 4 = 0.15575  ← 一格的行距 pitch
//   格子高 = 0.15575 − GAP_FRACTION(0.008) = 0.14775                              ← TAB_H 与按键行高同一个数
//   GRID_TOP = TAB_Y + TAB_H + GAP_FRACTION = 0.322 + 0.14775 + 0.008 = 0.47775
//   （注意 pitch 和 GRID_TOP 都没变：缝变宽多少，格子高就矮多少，
//     两者的和恒等于 pitch —— 所以调缝不会把整体分区挤乱。）
//
// 于是四段互不重叠（括号里是 233dp 屏上的 dp）：
//   工具条 0.088 ~ 0.180    （21.4dp 高，顶在 20.5dp）
//   输入框 0.192 ~ 0.307    （26.8dp 高，顶在 44.7dp）
//   选择栏 0.322 ~ 0.46975  （34.4dp 高，顶在 75.0dp）
//   网格   0.47775 ~ 0.937  （3 行 × 34.4dp，行缝 1.86dp，顶在 111.3dp）
//   段与段之间的缝：工具条→输入框 0.012（2.8dp）、输入框→选择栏 0.015（3.5dp）、
//   选择栏→网格 0.008（1.86dp = 一个 GAP_FRACTION）。
//   GRID_BOTTOM(0.945) 是外沿上界：最后一行到 0.9370 为止，底下还剩 1.86dp 余量。
//   三行按键各自的上下沿（行 i 上沿 = GRID_TOP + i × pitch，下沿 = 上沿 + TAB_H）：
//     第 1 行 0.47775 ~ 0.6255、第 2 行 0.6335 ~ 0.78125、第 3 行 0.78925 ~ 0.9370
//   行高和行缝**不在这里写死**：MathKeyboard 按
//   `rowHeight = sizeDp × ((gridBottom − gridTop)/KEYBOARD_ROWS − gapFraction)`
//   现算（**非圆屏**上换成按真实屏高铺满、并被"按键最多 1.25 倍键宽"夹住，
//   见 `MathKeyboard.keyGridMetrics`），行间距由 `Arrangement.spacedBy(gap)` 给 ——
//   这里只负责把 TAB_H / GRID_TOP 反解对，三个数就是同一个 pitch 的三种切法。
//
// 两轮真机需求叠在这套分区上：
//   * 「所有页签改成 3 行 × 4 列（删掉第 4 行）」：少了一行，pitch 从 0.1246
//     涨到 0.15575，格子高从 0.1226（28.6dp）涨到 0.14775（≈34.4dp）——
//     选择栏 chip 和按键永远等高，所以它一起变高。
//   * 「按键参考 `D:\Culculator` 的风格」：那边是完整胶囊 + 每键 2dp 边距，
//     所以 GAP_FRACTION 从 0.002 提到 0.008（缝 0.47dp → 1.86dp），
//     格子高相应从 35.8dp 变成 34.4dp —— pitch 和 GRID_TOP 不动。
//
// 更早两轮的真机反馈："输入框太高、红字不要了、按键上的字细一点"：
//   * 输入框卡片从 0.175 降到 0.115（永远一行高，⌫ 与它等高），
//     报错不再画在卡片里（点确定之后由调用方弹 toast），所以没有第二种高度。
//   * 选择栏不再是 0.056 那种"矮条"：它和按键一样高，看起来就是网格的第 1 行。
//   * 按键、⌫ 的字重统一成 Normal；按键字号是 fontSmall
//     （选择栏 chip 仍是 fontKey，见 MathKeyboard 的 KeyButton / TabChip）。
// ---------------------------------------------------------------------------
private const val TOP_Y = 0.088f
private const val TOP_H = 0.092f
private const val FIELD_Y = 0.192f
/** 表达式卡片的高度 —— 卡片**永远只有一行**（报错不再显示在卡片里）。 */
private const val FIELD_H = 0.115f
/**
 * 行距：按键行与行之间、以及选择栏与网格之间，用的是同一个值。
 *
 * **参考 `D:\Culculator` 之后从 0.002 提到 0.008**（0.47dp → 1.86dp）：
 * 那边按键是完整胶囊，每个键四周留 2dp 边距（相邻两键之间就是 4dp 缝），
 * 胶囊形靠这条缝才读得出来。我们原来 0.47dp 的缝配近胶囊圆角还行，
 * 换成完整胶囊之后左右两颗会挤成一条"花生"，看不出是一颗颗键了。
 * 1.86dp 大约是 Culculator 那 4dp 的一半，既让胶囊分明、又没把缝吃掉。
 *
 * 注意：改了它 [TAB_H] 必须同步改（TAB_H = pitch − GAP_FRACTION），
 * 否则选择栏和按键不再等高 —— 这是上一轮真机反馈过的问题。
 */
private const val GAP_FRACTION = 0.008f
/** 选择栏的顶边。 */
private const val TAB_Y = 0.322f
/** 选择栏那一个 chip 的高度 = **一个按键的高度** = pitch − GAP_FRACTION（推导见上面的注释）。 */
private const val TAB_H = 0.14775f
private const val GRID_TOP = 0.47775f
private const val GRID_BOTTOM = 0.945f

/**
 * 表达式那一行里 `⌫` 的宽度（占**可用宽度基准** [keyWidthBase] 的比例）。
 *
 * 它和输入框**同一行、等高**（高度走 `Modifier.fillMaxHeight()`，由 `fieldHeight`
 * 决定 —— 输入框是 0.115 屏高，⌫ 跟着一起是 ≈26.8dp 高）。
 * 宽度 0.155 × 基准 ≈ 36.1dp（圆表），比键盘网格里最窄的键（最下面那行 ≈24.8dp）
 * 还宽一点，因为它是按得最多的键之一。
 *
 * ⚠️ 比例乘的是 [keyWidthBase] 而**不是** `metrics.sizeDp`：圆屏上两者相等（逐像素不变），
 * 非圆屏上基准是"真实屏宽 − 左右边距" —— 这就是用户那条长期规则
 * "写所有按键都要加上对屏幕宽度的自适应"在这一处的落点（见 README 那一节）。
 */
private const val DEL_W_FRACTION = 0.155f

/** 输入框和 `⌫` 之间、参数方程两个输入框之间的缝（占 [keyWidthBase]）。 */
private const val FIELD_GAP_FRACTION = 0.02f

/**
 * 顶部工具条中间那颗**模式 chip**（`y=f(x)` / `r(θ)` / `F(x,y)=0` / `描点`）的宽度。
 *
 * 0.18 × [keyWidthBase] —— 和 [WatchMetrics.iconButton] 的 0.18 是同一个比例，
 * 所以它看上去就是"和两边图标按钮一样宽的一颗键"。
 * 快照值：圆表 41.9dp（**和原来一样**）、手机 74.0 → **69.5dp**（基准从短边 411dp
 * 换成"屏宽 − 左右各 3%"的 386.3dp，仍然按屏宽成比例）。
 */
private const val MODE_W_FRACTION = 0.18f

/**
 * 输入框右边那颗**名字提示 chip**（`a=滑动条✓`）的宽度，占 [keyWidthBase]。
 *
 * 和 `⌫` 同一个比例（0.155），所以那一行是"输入框 + 提示 chip + ⌫"三段，
 * 两段的宽度口径一致。绝大多数输入（`sin(x)`、`2x+1`）里这颗 chip 根本不出现
 * （`NameHintChip` 在 `nameChoices` 为空时整个不占位）。
 */
private const val HINT_W_FRACTION = 0.155f

/**
 * 在当前坐标系**允许的**模式里循环。
 *
 * 不能无脑按 `y=f(x) → r(θ) → 参数 → F(x,y)` 转一圈：全局坐标系是笛卡尔时
 * 切到 r(θ) 是没有意义的（引擎会报错，用户会以为 App 坏了），反之亦然。
 * 所以循环范围由 [CoordinateSystem.modes] 决定。
 *
 * 当前模式不在 [allowed] 里就退到第一个 —— 正常不该发生
 * （切换坐标系时状态层已经把所有函数 coerce 过一遍了），
 * 但老数据 / 恢复备份这类路径上留个兜底，比崩掉或者卡在非法模式上强。
 */
private fun nextModeIn(current: PlotMode, allowed: List<PlotMode>): PlotMode {
    val i = allowed.indexOf(current)
    return if (i < 0) allowed.first() else allowed[(i + 1) % allowed.size]
}

/** 极坐标 / 参数方程的取值范围预设。点一下选择栏末尾那个范围 chip 就往下循环一个。 */
private data class RangePreset(val label: String, val min: Double, val max: Double)

/**
 * 范围预设**分度制两套**。
 *
 * 同一个函数在 RAD 下量程是 0~2π，切到 DEG 之后如果还循环出 "0~2π"，
 * 用户按一下就得到 0~6.28 度的量程 —— 画出来只剩一小段弧。
 * 所以度制下给的是 0~360 / 0~720 / −180~180，标签也换成度数写法。
 */
private val RadRangePresets = listOf(
    RangePreset("0~2π", 0.0, 2 * PI),
    RangePreset("0~4π", 0.0, 4 * PI),
    RangePreset("−π~π", -PI, PI),
    RangePreset("−10~10", -10.0, 10.0),
    /**
     * `R` = 全体实数（用户要求："多加一个 R 的选项，范围为全体实数"）。
     *
     * 真·无限没法采样，所以取 **±20π（20 圈）**：
     *  * 周期曲线（`cos(3θ)` 这种）绕 20 圈还是同一个形状，和 0~2π 画出来一样；
     *  * 螺旋这类不闭合的，θ 到 20π 时半径早就冲出视野了，视觉上就是"画满了"。
     * 再放大只会让采样预算摊薄、曲线上出现折角，屏幕上看不出任何区别。
     *
     * 20π 弧度正好是 3600 度，和 [DegRangePresets] 里那个 R 严丝合缝 ——
     * 这样在编辑器里切 RAD/DEG（`GraphState.toggleAngleUnit` 会换算量程）
     * 来回切几次都不会漂移。
     */
    RangePreset("R", -20 * PI, 20 * PI)
)

private val DegRangePresets = listOf(
    RangePreset("0~360", 0.0, 360.0),
    RangePreset("0~720", 0.0, 720.0),
    RangePreset("−180~180", -180.0, 180.0),
    RangePreset("−10~10", -10.0, 10.0),
    RangePreset("R", -3600.0, 3600.0)
)

private fun rangePresets(degrees: Boolean): List<RangePreset> =
    if (degrees) DegRangePresets else RadRangePresets

private fun presetIndexOf(min: Double, max: Double, degrees: Boolean): Int =
    rangePresets(degrees).indexOfFirst {
        abs(it.min - min) < 1e-6 && abs(it.max - max) < 1e-6
    }

/** 选择栏末尾那个范围 chip 上显示的当前范围。认不出预设（比如老数据）就直接把数字写出来。 */
private fun formatRange(min: Double, max: Double, degrees: Boolean): String {
    val i = presetIndexOf(min, max, degrees)
    if (i >= 0) return rangePresets(degrees)[i].label
    fun short(v: Double): String {
        val r = (v * 100.0).roundToLong() / 100.0
        return if (abs(r - r.toLong()) < 1e-9) r.toLong().toString() else r.toString()
    }
    return "${short(min)}~${short(max)}"
}

/** 循环到下一个范围预设。认不出当前值就从该度制的第一个开始。 */
private fun nextRange(min: Double, max: Double, degrees: Boolean): RangePreset {
    val presets = rangePresets(degrees)
    val i = presetIndexOf(min, max, degrees)
    return presets[(i + 1) % presets.size]
}

/**
 * 函数编辑器。
 *
 * 改动会立刻通过 [onChange] 冒泡出去，所以边上的图形是实时跟着变的
 * —— 在手表上打字很慢，能立刻看到结果非常重要。
 *
 * [coordinateSystem] 是设置里的全局坐标系：模式 chip 只在它允许的模式里循环
 * （见 [nextModeIn]），所以这个参数**必须**跟着设置一起传进来。
 *
 * [error] 只是**透传**给 [ExpressionField]，编辑器自己不再拿它画任何东西：
 * 真机反馈"输入框下面那行红字提示不要了"，报错改成点确定之后由调用方
 * （GraphScreen）弹 toast。参数留在签名里不动调用方。
 *
 * [angleUnit] / [onToggleAngleUnit]：角度单位是**每个函数各一份**
 * （用户明确要求"不同函数可以用不同的度制"），所以真正的状态在
 * `PlotFunction.degrees` 上，编辑器只负责显示和请求切换 ——
 * 自己不留任何局部状态，否则返回列表再进来显示的单位就和实际不一致了。
 *
 * ## 「这个字母指谁」（用户需求：字母冲突由用户在编辑器里指认）
 *
 * 用户原话："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
 * 标量也可以用 a，滑动条也可以用 f。至于 e、x、y 这些可能冲突的字母，
 * 请你给函数编辑界面输入的这些字母单独分组编 id，这样也不用担心字母冲突的问题。"
 *
 * 于是命名不再跳过任何字母（标量可能就叫 `a`、滑动条可能就叫 `f`），
 * 冲突要在**这条函数**上说清。界面上的落点是两处：
 *
 *   1. **输入框右边那颗小提示 chip**（`a=距离`、`f=滑动条？`）—— 用户要的"可见的提示"，
 *      一眼知道表达式里那个字母现在被理解成什么；`？` = 还没指认、需要点一下，
 *      `✓` = 已经指认过；
 *   2. 点那颗 chip 弹出**候选条**（盖在键盘选择栏那一行上）：把每个候选列一颗 chip
 *      （`a：滑动条` / `a：距离`），点一下就把 `nameBindings["a"]` 记进这条函数
 *      （再点一次同一颗 = 取消指认，回到自动解析）。
 *
 * 候选怎么算（纯逻辑、有离线单测）在 `PointCoordinates.nameChoicesIn`；
 * 表存在 `GraphState.functionBindings` 里、采样前先查它
 * （`PointCoordinates.resolveScalarValues`）。编辑器自己**不存任何绑定状态** ——
 * 它是这份状态的显示端，状态一变（[nameChoices]）界面就跟着变，和度制那一栏同一个道理。
 */
@Composable
fun FunctionEditorScreen(
    fn: PlotFunction,
    error: String?,
    metrics: WatchMetrics,
    coordinateSystem: CoordinateSystem,
    angleUnit: AngleUnit,
    onToggleAngleUnit: () -> Unit,
    /**
     * 表达式里每个"有意义的名字字母"以及它能指到的候选
     * （`GraphState.nameChoicesOf(fn)` 算出来的；空表 = 表达式里没有这种字母，
     * 那颗提示 chip 和候选条都不会出现）。
     */
    nameChoices: List<NameChoice>,
    /** 点了候选条上的一颗 chip：指认（或再点一次取消）`letter` → `objectId`。 */
    onPickName: (String, Long) -> Unit,
    /**
     * 表冠/滚轮事件。
     *
     * 数学键盘用不到它，但**字母表键盘**（#5）是一个可滚动列表，
     * 用户明确要求"注意适配表冠" —— 所以编辑器也要把这条流转发下去。
     * 在这个界面它只滚字母表，不缩放图像。
     */
    rotary: Flow<Float>,
    onChange: (PlotFunction) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    // 每个函数进来都停在数字那一页（KeyPage.DIGITS）：绝大多数输入都是数字
    var page by remember(fn.id) { mutableStateOf(KeyPage.DIGITS) }
    // 参数方程有两个输入框，0 = x(t)，1 = y(t)
    var focus by remember(fn.id) { mutableStateOf(0) }
    var cursor1 by remember(fn.id) { mutableStateOf(fn.src1.length) }
    var cursor2 by remember(fn.id) { mutableStateOf(fn.src2.length) }
    /**
     * 候选条开着没有。
     *
     * 局部状态而不是外面传进来的：它是**这一屏的一个临时面板**（点提示 chip 打开、
     * 点一颗候选或"收起"关掉），和"绑定表里存了什么"不是一回事 ——
     * 后者在 `GraphState` 里（[nameChoices] 就是它的读数）。
     * 退出编辑器（路由变化）时这个 composable 被丢掉，面板自然也就关了。
     */
    var showNamePicker by remember(fn.id) { mutableStateOf(false) }

    // 角度单位没有局部状态：直接用入参（来源是 PlotFunction.degrees），
    // 切换请求交给调用方（GraphState.toggleAngleUnit）。
    val degrees = angleUnit.isDegrees

    // ---- 字母表键盘（#5）----
    // `abc` 键切过去、`返回` 键切回来；大小写模式在这里持有（模型是无状态的纯函数）。
    var showAlphabet by remember(fn.id) { mutableStateOf(false) }
    var letterCase by remember(fn.id) { mutableStateOf(LetterCase.Lower) }

    fun applyKey(action: KeyAction) {
        if (fn.mode == PlotMode.PARAMETRIC && focus == 1) {
            val (text, next) = editText(fn.src2, cursor2, action)
            cursor2 = next
            onChange(fn.copy(src2 = text))
        } else {
            val (text, next) = editText(fn.src1, cursor1, action)
            cursor1 = next
            onChange(fn.copy(src1 = text))
        }
    }

    // 圆屏在某个高度上的可用宽度
    //
    // ⚠️ 这两处原来写的是 `metrics.sizeDp × ...`（**短边**）：圆表上短边 == 屏高，
    // 所以一直是对的；手机上短边是**屏宽**（411dp），于是（实算）：
    //   * 宽度：工具条那一行 217dp、输入框那一行 301dp、键盘三行 370 / 316 / **186dp**
    //     ——第 3 行只有屏宽的 45%，4 颗键挤在中间；
    //   * 纵向：整套分区只落在 0 ~ 388dp（真机 850dp 高的屏上底下 462dp 全空）。
    // 现在这两件事都交给 [bandWidth]（圆屏弧宽 / 非圆屏满宽）和 [ScreenGeometry.sy]
    // （真实屏高），见 [ScreenGeometry] 上面那段说明。下面这个 `spanW` 只是
    // 把当前这一屏的 geometry / isRound 绑进去的薄壳，算式本体只有一份。

    // 卡片**永远只有一行高**：报错文字不再画在输入框下面（改由调用方弹 toast），
    // 所以这里没有"有错时变高"的第二种高度，⌫ 也就永远和它等高。
    val fieldHeight = metrics.sizeDp * FIELD_H
    // 选择栏第 5 个（紫色/第三色）chip：**只在极坐标模式下显示**。
    //
    // 用户要求："在这个界面不显示选择栏第五个紫色按钮"，
    // 同时"在极坐标系模式下…第五个紫色按钮多加一个 R 的选项"——
    // 两句话合起来就是：只有 r(θ) 才需要这个圆环范围开关。
    //   * y=f(x) / F(x,y)=0：x 范围来自视口（缩放就是改范围），这个 chip 没有意义；
    //   * 描点 (x(t),y(t))：虽然 t 也有范围，但用户明确要它在编辑器里不显示
    //     （示例库里的摆线自己带了 0~6π，够用）。
    val rangeLabel = if (fn.mode == PlotMode.POLAR) {
        formatRange(fn.tMin, fn.tMax, degrees)
    } else {
        null
    }
    // 长按变体气泡要盖在输入框上：把输入框的竖直中心告诉键盘
    // （卡片现在恒为 FIELD_H，中心就是 FIELD_Y + FIELD_H/2 = 0.2495）
    val fieldCenterFraction = FIELD_Y + FIELD_H / 2f

    /**
     * 输入框卡片和同一行的 ⌫ 共用圆角：**按卡片高度取比例**（见 watchShapeOf）。
     * 卡片只有 26.8dp 高，用固定的 14dp 会几乎变成胶囊，比例算出来是 11.3dp，
     * 和下面那些 35.8dp 高的按键是同一套圆润语言。
     */
    val fieldShape = watchShapeOf(fieldHeight)

    BoxWithConstraints(Modifier.fillMaxSize()) {

        // ---- 真实可用尺寸（不是短边）----
        //
        // 这一屏原来把"输入框 / 选择栏 / 键盘区"的位置全写成 `sizeDp × 比例`，
        // 而 `sizeDp` 是**短边**：圆表上短边 == 屏高所以一直看不出问题，
        // 手机上短边是**屏宽**（411dp），整套纵向布局就只落在真机 850dp 高的**上半截**
        // （键盘最下面一行排到 388dp 就没了），底下一大块空的 —— 用户原话
        // "函数编辑页面也没有全屏显示"。
        //
        // 所以这里量一次真实可用区域，把两类量分开（和 `FunctionList.ScreenGeometry`
        // 上面的说明同一套规矩）：
        //   * **位置 / 段落跨度**（工具条在哪、输入框在哪、键盘区到哪）→
        //     按**真实屏高** `geometry.sy(...)`；
        //   * **控件尺寸**（输入框高、⌫ 宽、字号、图标按钮）→ 继续走
        //     `metrics.sizeDp`，也就是继续按短边缩放 —— 长条屏上不会被拉成巨大的方块；
        //   * **宽度** → 圆屏走原来的弧宽公式（逐像素不变），非圆屏铺满屏宽减左右余量。
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = LocalConfiguration.current.isScreenRound

        /**
         * 给**一整段高度**算可用宽度，取上下边缘里更窄的那一处。
         *
         * 只按行的中心高度算是不够的：圆的宽度随高度递减，
         * 行的上下两端比中心更靠外，用中心宽度会让控件四角越过圆弧，
         * 在真机上表现为被表壳切掉（顶部工具条就是最容易中招的一条）。
         *
         * 圆屏这一支就是上面那句话；非圆屏（手机）由 [bandWidth] 直接给"铺满屏宽减
         * 左右各 3%" —— 用户要的"按键自动适应屏幕的宽度"就落在这一句上。
         */
        fun spanW(yTop: Float, yBottom: Float): Dp =
            bandWidth(
                metrics = metrics,
                geometry = geometry,
                isRound = isRound,
                yTop = yTop,
                yBottom = yBottom
            )

        /**
         * **按键宽度基准**（用户这条长期规则："以后你写所有按键都要加上对屏幕宽度的自适应"）。
         *
         * 它 == 参照实现（`FunctionList.kt` 示例按钮那一行）的非圆屏宽度表达式：
         * `geometry.rowWidth(metrics, 1f, isRound)` —— 圆屏 = `metrics.sizeDp`（逐像素不变）、
         * 非圆屏 = "真实屏宽 − 左右各 3%"（手机 386.3dp）。
         *
         * 这一屏里凡是**自己设宽度**的东西 —— `⌫`、模式 chip（就是示例按钮用的同一个
         * `MiniChip`）、输入框右边那颗名字提示 chip —— 全部写成 `keyBase × 比例`。
         *
         * **例外**：顶部两颗 `WatchIconButton`（取消 / 完成）按 README「硬约定」里那一条
         * 写 `0.16 × 短边`（形状锁定 + 用户先前"强制保持在一个形状"的要求），
         * 理由写在调用点。为什么不能用 `metrics.sizeDp` 当基准、以及新按键的自查办法，
         * 见 `MathKeyboard.keyWidthBase` 与 README 的「硬约定」那一节。
         */
        val keyBase = keyWidthBase(metrics, geometry, isRound)

        // `⌫` 的宽度、输入框和它之间的缝、以及名字提示 chip 的宽度：
        // 都按 `keyBase × 比例`（圆屏上 keyBase == sizeDp，所以逐像素不变）。
        val fieldGap = keyBase * FIELD_GAP_FRACTION
        val delWidth = keyBase * DEL_W_FRACTION
        val hintWidth = keyBase * HINT_W_FRACTION

        // ---- 顶部：取消 / 模式（点一下循环切换） / 完成 ----
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(TOP_Y))
                .width(spanW(TOP_Y, TOP_Y + TOP_H))
                .height(metrics.sizeDp * TOP_H),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "取消",
                metrics = metrics,
                modifier = Modifier
                    // ⚠️ **图标键是 README「硬约定」里唯一的例外**：它属于 `WatchIconButton`
                    // 那一类**形状锁定**的按键（`Common.kt` 按 `0.18 × 短边` +
                    // `ICON_BTN_ASPECT` = 1.44:1 胶囊统一锁死，依据是用户更早那条
                    // "请你让按键强制保持在一个形状"），所以调用点**只写比例、不写基准**。
                    // 它不是"窄柱"：手机上短边 == 屏宽，仍是真实屏宽的 16%；
                    // 真正自适应的是它所在的那一行（见上面 `spanW`），所以不会留空隙。
                    // 和 `PropertyEditorShell` / `ObjectDetailsScreen` 那颗 ✓ 逐字同款。
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * TOP_H * 0.85f),
                onClick = onCancel
            )
            MiniChip(
                label = fn.mode.label,
                selected = false,
                metrics = metrics,
                modifier = Modifier
                    // 模式 chip 的宽度也走"可用宽度 × 比例"（圆屏不变、非圆屏跟着屏宽走）
                    .width(keyBase * MODE_W_FRACTION)
                    .height(metrics.sizeDp * TOP_H * 0.85f),
                onClick = {
                    // 只在当前坐标系说得通的模式里转：笛卡尔下不出现 r(θ)，极坐标下不出现 y=f(x)
                    onChange(fn.copy(mode = nextModeIn(fn.mode, coordinateSystem.modes)))
                }
            )
            WatchIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "完成",
                metrics = metrics,
                modifier = Modifier
                    // 同「取消」：图标键属于 README 里那个例外，宽度=比例×短边、形状锁死
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * TOP_H * 0.85f),
                filled = true,
                onClick = onDone
            )
        }

        // ---- 表达式输入区 + 退格键（卡片恒为一行高，⌫ 与它等高）----
        // 一行三段：[输入框 weight 1] [⌫]（参数方程是 [x(t)] [y(t)] [⌫]）。
        // ⌫ 贴着表达式放：改一个字符不用把手指移到屏幕最下面那一行。
        if (fn.mode == PlotMode.PARAMETRIC) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // 纵向位置按**真实屏高**（圆屏上 `sy(0.192)` 逐像素等于 `sizeDp × 0.192`）；
                    // 高度仍是短边的 0.115（控件尺寸不跟着屏高走）
                    .offset(y = geometry.sy(FIELD_Y))
                    .width(spanW(FIELD_Y, FIELD_Y + FIELD_H))
                    .height(fieldHeight),
                horizontalArrangement = Arrangement.spacedBy(fieldGap)
            ) {
                // 报错是整条函数的，两个框都拿到同一个 error —— 虽然这轮谁都不显示它，
                // 但参数留着，焦点在哪个框上都不影响以后想恢复内联提示。
                ExpressionField(
                    text = fn.src1,
                    cursor = if (focus == 0) cursor1 else fn.src1.length,
                    hint = "x = cos t",
                    metrics = metrics,
                    focused = focus == 0,
                    error = error,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = { focus = 0; cursor1 = fn.src1.length },
                    shape = fieldShape
                )
                ExpressionField(
                    text = fn.src2,
                    cursor = if (focus == 1) cursor2 else fn.src2.length,
                    hint = "y = sin t",
                    metrics = metrics,
                    focused = focus == 1,
                    error = error,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = { focus = 1; cursor2 = fn.src2.length },
                    shape = fieldShape
                )
                NameHintChip(
                    choices = nameChoices,
                    metrics = metrics,
                    // 宽度按可用宽度基准（圆屏逐像素不变），见 HINT_W_FRACTION
                    width = hintWidth
                ) { showNamePicker = true }
                // 两个输入框共用一个 ⌫：它只作用于当前有焦点的那个框（见 applyKey）
                BackspaceKey(
                    metrics = metrics,
                    onKey = ::applyKey,
                    modifier = Modifier.width(delWidth).fillMaxHeight(),
                    // ⌫ 是"按键"，跟网格里的键一样用完整胶囊；输入框卡片是"框"，留着比例圆角
                    shape = PillShape
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = geometry.sy(FIELD_Y))
                    .width(spanW(FIELD_Y, FIELD_Y + FIELD_H))
                    .height(fieldHeight),
                horizontalArrangement = Arrangement.spacedBy(fieldGap)
            ) {
                ExpressionField(
                    text = fn.src1,
                    cursor = cursor1,
                    hint = fn.mode.primaryHint,
                    metrics = metrics,
                    focused = true,
                    error = error,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = { cursor1 = fn.src1.length },
                    shape = fieldShape
                )
                NameHintChip(
                    choices = nameChoices,
                    metrics = metrics,
                    width = hintWidth
                ) { showNamePicker = true }
                BackspaceKey(
                    metrics = metrics,
                    onKey = ::applyKey,
                    modifier = Modifier.width(delWidth).fillMaxHeight(),
                    shape = PillShape
                )
            }
        }

        // ---- 键盘：选择栏 + 固定 3 行网格（没有编辑行）----
        // 选择栏 chip 的高度就是下面按键的高度、缝就是下面的行距（见文件头的推导）
        //
        // 「其他」页第 3 行的 `abc` 键会打开**字母表键盘**（#5）。两者占同一块地方
        // （都用 TAB_Y ~ GRID_BOTTOM），所以是 if/else **互相替换**，
        // 不能同时画 —— 一起画会叠在一起。
        if (showAlphabet) {
            AlphabetKeyboard(
                metrics = metrics,
                letterCase = letterCase,
                onCaseChange = { letterCase = it },
                // 字母/括号/逗号都走 applyKey：自动落到 src1/src2 里有焦点的那个框，
                // 而且"逗号跳槽""退格整体删空调用"这些语义和数学键盘完全一致
                onInsert = { applyKey(KeyAction.Insert(it)) },
                onClose = { showAlphabet = false },
                rotary = rotary,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MathKeyboard(
                page = page,
                mode = fn.mode,
                metrics = metrics,
                // 真实屏几何 + 屏形：非圆屏靠这两位把"键盘区铺到屏幕下半截、
                // 4 列按键铺满整宽"落下去（见 MathKeyboard 里 bandWidth / keyGridMetrics
                // 上面那段真因）；圆屏那两位不产生任何差异。
                geometry = geometry,
                isRound = isRound,
                tabTopFraction = TAB_Y,
                tabHeightFraction = TAB_H,
                gridTopFraction = GRID_TOP,
                gridBottomFraction = GRID_BOTTOM,
                gapFraction = GAP_FRACTION,
                fieldCenterFraction = fieldCenterFraction,
                rangeLabel = rangeLabel,
                onSelectPage = { page = it },
                onCycleRange = {
                    val preset = nextRange(fn.tMin, fn.tMax, degrees)
                    onChange(fn.copy(tMin = preset.min, tMax = preset.max))
                },
                // OTHER 页那个角度制键：显示**这个函数**当前的单位（AngleUnit.label 就是
                // "RAD" / "DEG"）、点一下只切它自己（量程的换算在 GraphState.toggleAngleUnit 里）
                onToggleAngleUnit = onToggleAngleUnit,
                angleUnitLabel = angleUnit.label,
                // `abc` 键：切到字母表键盘
                onOpenAlphabet = { showAlphabet = true },
                onKey = ::applyKey,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- 「这个字母指谁」候选条（盖在键盘选择栏那一行上）----
        //
        // 为什么盖在 TAB_Y 那一行：那一行本来就是"选择栏"（页签 + 范围 chip），
        // 高度和它在同一个规格上（[TAB_H]），而候选条是**临时面板** ——
        // 打开时它就是这一行，收起时下面那一行照旧。放到别处要么挤掉输入框，
        // 要么盖住键盘，两者都比"临时占用选择栏"更糟。
        // 它只在用户点了输入框右边那颗提示 chip 之后出现（`showNamePicker`），
        // 而且这一条需求本身是低频的（表达式里写到标量/滑动条名字时才有）。
        if (showNamePicker && nameChoices.isNotEmpty()) {
            NamePickerBar(
                choices = nameChoices,
                metrics = metrics,
                // 上沿按**真实屏高**（圆屏上 `sy(0.322)` 逐像素等于 `sizeDp × 0.322`）
                topOffset = geometry.sy(TAB_Y),
                heightFraction = TAB_H,
                widthDp = spanW(TAB_Y, TAB_Y + TAB_H),
                onPick = { letter, id -> onPickName(letter, id) },
                onDismiss = { showNamePicker = false }
            )
        }
    }
}

/**
 * 输入框右边那颗**名字提示 chip**（用户要的"可见的提示"）。
 *
 * 显示"表达式里第一个有含义的字母现在被理解成什么"：
 * ```
 *   a=滑动条      没绑定、但引擎自己认识 a（可绑定参数）→ 自动解析
 *   f=距离？      有一个同名标量，但引擎不认识 f → **需要指认**（点一下去选）
 *   a=距离✓       用户已经指认过 → 按他选的算
 *   a=滑动条 +1   表达式里还有别的这种字母（`+1` = 除它以外还有 1 个）
 * ```
 *
 * [nameChoices] 为空（表达式里没有这类字母）时**整个 chip 不占位**，
 * 输入框的宽度和以前一模一样 —— 绝大多数输入（`sin(x)`、`2x+1`）都不会看到它。
 *
 * 为什么放在输入框和 ⌫ **中间**而不是浮在输入框上：浮层会压住用户正在看的那截式子，
 * 而这一行的宽度本来就是按圆屏圆弧算好的（`spanW`），
 * 中间塞一颗矮 chip 不会把 ⌫ 挤出去（⌫ 是固定宽度，输入框吃 weight）。
 */
@Composable
private fun NameHintChip(
    choices: List<NameChoice>,
    metrics: WatchMetrics,
    /**
     * 这颗 chip 的宽度。
     *
     * **由调用方给**（`keyBase * HINT_W_FRACTION`）而不是在这里写死 —— 这是用户那条
     * 长期规则（"写所有按键都要加上对屏幕宽度的自适应"）的一部分：圆屏上它等于
     * 原来的 `metrics.sizeDp * 0.155f`（逐像素不变），非圆屏上是"真实屏宽 − 左右边距"
     * 乘同一个比例。本组件自己**不碰**任何宽度基准，见 `keyWidthBase`。
     */
    width: Dp,
    onClick: () -> Unit
) {
    if (choices.isEmpty()) return
    val first = choices.first()
    val bound = first.candidates.firstOrNull { it.objectId == first.boundId }
    val label = bound?.label ?: first.candidates.first().label
    val mark = when {
        bound != null -> "✓"
        first.needsDecision -> "？"
        else -> ""
    }
    val extra = if (choices.size > 1) " +${choices.size - 1}" else ""
    val text = "${first.letter}=$label$mark$extra"
    // 已经指认过的（绑定表里那一条）用强调色，一眼区分"我选过了"和"还是自动的"
    val selected = bound != null

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(PillShape)
            .background(if (selected) KeyPalette.accent else KeyPalette.normal)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = metrics.fontTiny,
            color = if (selected) KeyPalette.text else KeyPalette.textMuted,
            maxLines = 1
        )
    }
}

/**
 * 候选条：把每个"有含义的字母"能指到的东西各列一颗 chip，点一下就指认。
 *
 * 出现动画和属性气泡、调色板面板同一套（淡入 + 从 0.94 放大、180ms、
 * 只动 `graphicsLayer`）：它是这一屏唯一一块浮层，必须一眼看出"这是刚弹出来的"。
 * 退出动画没做（收起是把 `showNamePicker` 清成 false，那一层没有"先播完再清"的余地），
 * 和 `ObjectPropertiesBubble` 是同一个取舍。
 *
 * 一行放不下时**横着滚**（最多几个候选，但 `a` 同时有滑动条和标量、再加上
 * `f`、`g` 都可能有候选，圆屏一行放不下三颗以上）。
 */
@Composable
private fun NamePickerBar(
    choices: List<NameChoice>,
    metrics: WatchMetrics,
    /**
     * 候选条的**上沿**（dp）。
     *
     * 由调用方按真实屏高算好传进来（`geometry.sy(TAB_Y)`），不再是"屏高比例乘以短边"——
     * 圆屏上两者逐像素相同，手机上按短边算会让这条浮层跑到屏幕上半截、
     * 和它盖着的那一行选择栏错位。
     */
    topOffset: Dp,
    heightFraction: Float,
    widthDp: Dp,
    onPick: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = NAME_PICKER_IN_MS, easing = FastOutSlowInEasing)
        )
    }

    // 引擎自己认领的名字要把话说在明处（选了它 = 这个函数里的它不再是引擎那个意思）
    val warned = choices.firstOrNull { c ->
        c.boundId == null && c.letter in SCALAR_NAME_RESERVED
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = appear.value
                val s = 0.94f + 0.06f * appear.value
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = KeyPalette.bubble,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                // 上沿由调用方按真实屏高算好（高度仍是短边那一档：它是一颗"键"的高度）
                .offset(y = topOffset)
                .width(widthDp)
                .height(metrics.sizeDp * heightFraction)
                .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.sizeDp * 0.018f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.012f)
                ) {
                    choices.forEach { choice ->
                        choice.candidates.forEach { cand ->
                            NameCandidateChip(
                                label = cand.chipLabel,
                                // 当前生效的那一颗高亮（用户一眼看出"现在指到谁"）
                                active = choice.boundId == cand.objectId,
                                metrics = metrics,
                                onClick = { onPick(choice.letter, cand.objectId) }
                            )
                        }
                    }
                    // "收起"永远在最后：不给它留一条出路的话，用户想反悔就得点中
                    // 某颗候选才算数（而那时他已经改了绑定）
                    NameCandidateChip(
                        label = "收起",
                        active = false,
                        metrics = metrics,
                        onClick = onDismiss
                    )
                }
                // 引擎里已经有含义的字母：说清代价（不选就按引擎的意思算）
                Text(
                    text = warned?.let { "${it.letter} 是引擎自带的名字，选了它就按你挑的那个数算" }
                        ?: "点一下选定这个字母指谁（再点一次取消）",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 候选条里的一颗胶囊（选中 = 强调色），和属性气泡里的动作胶囊同一套配色。 */
@Composable
private fun NameCandidateChip(
    label: String,
    active: Boolean,
    metrics: WatchMetrics,
    onClick: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = NAME_CHIP_MS, easing = FastOutSlowInEasing)
    )
    Box(
        modifier = Modifier
            .height(metrics.sizeDp * 0.085f)
            .clip(PillShape)
            // 底色跟着"是不是当前生效的那一颗"渐染（160ms，和这一屏其它切换同一个时长）
            .background(lerp(KeyPalette.normal, KeyPalette.accent, t))
            .clickable(onClick = onClick)
            .padding(horizontal = metrics.sizeDp * 0.02f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = metrics.fontTiny,
            color = lerp(KeyPalette.textMuted, KeyPalette.text, t),
            maxLines = 1
        )
    }
}

/** 候选条的出现时长（和 `FunctionsScreen` 的属性气泡 190ms 是同一个量级）。 */
private const val NAME_PICKER_IN_MS = 180

/** 候选 chip 的高亮渐染时长（和工具 chip 的选中态 160ms 一致）。 */
private const val NAME_CHIP_MS = 160
