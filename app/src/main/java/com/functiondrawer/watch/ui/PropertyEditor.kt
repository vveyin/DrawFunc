// 手表 UI 大量用到实验性接口，文件级统一 OptIn：
//   * ExperimentalComposeUiApi —— LocalConfiguration.isScreenRound（圆屏判断）；
//   * ExperimentalFoundationApi —— 数字小键盘 ⌫ 的 combinedClickable（长按清空）。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.LineStyle
import com.functiondrawer.watch.engine.ParameterSet
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.ui.keyboard.KeyAction
import com.functiondrawer.watch.ui.keyboard.LetterCase
import com.functiondrawer.watch.ui.keyboard.editText
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.PillShape
import com.functiondrawer.watch.ui.theme.watchShapeOf
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

// ===========================================================================
// 属性编辑界面（用户需求 #4）
//
// 入口有两个，都在**函数列表**里长按：
//   * 长按函数行 → 函数/方程/点的属性：名称 / 线型 / 线径
//   * 长按滑动条行 → 滑动条的属性：名称 / 下界 / 上界 / 步长
//
// ## 为什么是**两个 public composable + 一个私有骨架**，而不是一个带开关的大界面
//
// 两侧真正重合的只有三件事：顶部那颗 ✓、名称输入框（列表态在第一行、编辑态
// 顶到最上面）、以及"下面那块地方显示哪种键盘"。这三件事都在 [PropertyEditorShell]
// 里，只写一遍。
// 而两侧**不一样**的是数据：函数侧改的是 `PlotFunction.name/lineStyle/widthScale`，
// 滑动条侧改的是 `Slider.param/min/max/step`，连"合法值"的定义都不同
// （函数名随便起，参数字母是一位小写字母且不能和别的滑动条重名 ——
// 本轮起不再限制在 a/b/c/d，见下面"滑动条的名称"那一节）。
// 硬凑成一个 composable 的话，参数表里会同时出现两套 onChange 和两个 nullable
// 的目标对象，每个分支都要先判"现在到底在改哪一边" —— 那不是共用，是把两份
// 逻辑叠在一起。所以：**骨架共用、两端的业务各写各的**。
//
// ## 布局（全部是"占屏短边的比例"，和 WatchMetrics / FunctionEditor 同一套约定）
//
// 那些数**照抄 FunctionEditor / SettingsScreen**，没有自己另定一套：
// ```
// 工具条  0.088 ~ 0.180          （FunctionEditor 的 TOP_Y / TOP_H）
// 名称栏  0.205 ~ 0.320  ←列表态 （SettingsScreen 的 LIST_TOP / ROW_H）
//         0.192 ~ 0.307  ←编辑态 （FunctionEditor 的 FIELD_Y / FIELD_H，
//                                  右边同时出现 ⌫）
// 键盘    0.322 ~ 0.945          （FunctionEditor 的 TAB_Y / GRID_BOTTOM：
//                                  字母表键盘和数字小键盘都占这一段）
// 其余各栏 0.336 起，每栏 0.115 + 0.016 的缝（名称栏下面依次排）
// ```
// 两个状态的名称栏高度都是 0.115（恰好 `ROW_H == FIELD_H`），所以"移动到顶部"
// 只是 y 从 0.205 挪到 0.192 —— 位移很小，但**必须动**：编辑态下面显示的是键盘，
// 输入框留在第一行的位置会和键盘挨得太近，而且需求明确要求"强制移动到屏幕顶部
// 能看完全的位置"。位移用 animateDpAsState 做了 200ms 的过渡而不是硬跳。
//
// ## 名称的焦点语义（"失焦"到底指什么）
//
// 需求原话是"点击这个输入框之后在右边显示删除键，失去焦点时隐藏"。手表上没有
// 系统输入法、也没有真正的焦点系统，所以这里把它定义成**一个显式的编辑态**：
//   * 进入（聚焦）：点名称输入框；
//   * 退出（失焦）：① 再点一次这个输入框本身（"收起来"），
//                   ② 点顶部 ✓（完成并离开本界面），
//                   ③ 系统返回（等同于 ✓）。
// 编辑态下其余各栏都被键盘顶掉了，所以界面上其实**不存在"点别的地方"** ——
// 用"再点一次自己"来承担"失焦"这个动作，是这块表上唯一说得通的做法
// （字母表键盘左上角那颗「返回」键走的也是这条路：onClose = 同一个 onNameTap）。
// 失焦之后 ⌫ 立刻隐藏、键盘收起、各栏回来。
//
// ## 数字栏的输入语义（取舍）
//
// 数字小键盘**没有输入框**（用户原话"没有数字按键和输入框之间的选择栏"，
// 同时这一栏也不给输入框）—— 那么"我正在打什么"就必须看得见，否则用户按了
// 数字不知道有没有生效。所以：
//   * 被编辑的那一栏会**贴到最上面**（0.205，就是名称栏那一格），
//     它右边的值 chip 显示的是**缓冲区**（也就是用户正在打的那串字符），
//     而不是模型里的旧值；
//   * 一边按一边实时校验：**合法就立刻写回模型**（曲线马上跟着变），
//     不合法就保留缓冲区、把 chip 标成危险色、并把这一栏的标题换成原因
//     （比如"上界必须大于下界"）—— 明确反馈，而且**绝不抛异常、绝不写坏模型**；
//   * 缓冲区里还允许存在"还没打完"的中间态（`""` / `-` / `.` / `1.`）：
//     它们只改缓冲区，不动模型；
//   * 退出数值编辑时缓冲区里没被接受的内容**一律丢弃**：模型永远是最后一次
//     被接受的值，所以界面不可能出现"显示 3、实际还是 5"这种分裂状态。
//
// ## 滑动条的名称（= 参数字母）
//
// 滑动条的名字**就是它绑定的参数字母**（列表里 `a` 是参数、`a(x)` 是函数 ——
// 靠有没有括号区分，见 Sliders.kt），所以它不是"随便起个标签"，而是**改绑定**：
// 只能是一位小写字母，而且不能和别的滑动条重名。这里同样用"缓冲区 +
// 校验"：不合法就不写回模型（模型一直是那个合法的旧字母），界面上给出原因。
//
// **本轮放宽过**（用户："标量和滑动条的名字可以不用严格分出使用的字母……
// 标量也可以用 a，滑动条也可以用 f"）：从"只能是 a/b/c/d"改成"任何单个小写字母"。
// 叫 `f` 之类的滑动条，引擎**自己**不认识那个参数，所以它的值要靠用户在函数编辑器里
// 把 `f` 指认给它（`a：滑动条` 那颗候选 chip），采样前折成数值 ——
// 配套的两处见 `GraphState.parameters()` / `GraphState.validSliders`。
//
// ## 实时生效
//
// 名字 / 线型 / 线径都只是"外观"，渲染层每帧实时读（见 GraphScreen 里的 curveStyles），
// 所以改完只要 `state.updateFunction(...)` 就立刻生效，**不需要**（也不该）
// 触发重新采样 —— 这里一个 replot 都不调。
// ===========================================================================

// ---------------------------------------------------------------------------
// 版面几何
// ---------------------------------------------------------------------------

/** 顶部工具条（和 FunctionEditor 的 TOP_Y / TOP_H 是同一个值、同一个理由）。 */
private const val TOOLBAR_TOP = 0.088f
private const val TOOLBAR_H = 0.092f

/** 名称栏的两处落点：编辑态用 FunctionEditor 的输入框位置，列表态用 SettingsScreen 的第一行。 */
private const val FIELD_Y = 0.192f
private const val FIELD_H = 0.115f
private const val ROW_TOP = 0.205f
private const val ROW_H = 0.115f
private const val ROW_GAP = 0.016f

/**
 * 键盘区：和 FunctionEditor 的 TAB_Y / GRID_BOTTOM 完全同一段。
 *
 * 字母表键盘就画在这里（它自己的 LIST_TOP/LIST_BOTTOM 也是这两个数）；
 * 数字小键盘也一样 —— 少掉"选择栏"那一条之后，这 4 行正好落满这一段，
 * 而且每颗键的大小和数学键盘一模一样（见 [NumberPad] 的推导）。
 */
private const val KEY_TOP = 0.322f
private const val KEY_BOTTOM = 0.945f
private const val KEY_GAP_FRACTION = 0.008f

/** 可用宽度在圆弧宽度之外再乘的余量（和 SettingsScreen / FunctionEditor 同一个值）。 */
private const val WIDTH_MARGIN = 0.93f

/** 名称栏里 ⌫ 的宽度 / 它和输入框之间的缝（照抄 FunctionEditor）。 */
private const val DEL_W_FRACTION = 0.155f
private const val FIELD_GAP_FRACTION = 0.02f

/** 值的 chip：高度与横向内边距（照抄 SettingsScreen 的 CHIP_H / CHIP_PAD）。 */
private const val CHIP_H = 0.095f
private const val CHIP_PAD = 0.028f

/** 每一栏的左右内边距（照抄 SettingsScreen 的 ROW_PAD）。 */
private const val ROW_PAD = 0.03f

// ---- 入场动画（和设置页同一套：淡入 + 从下方极小的上移，逐栏错开）----
private const val ROW_IN_MS = 220
private const val ROW_STAGGER_MS = 35
private const val ROW_IN_SLIDE = 0.015f
/** 名称栏在两个落点之间的移动时长。 */
private const val NAME_MOVE_MS = 200

// ---------------------------------------------------------------------------
// 线径的取值范围
//
// 0.4 ~ 3.0、步长 0.1 —— 这是**自己定的**（需求只说"滑动调节曲线粗细"）：
//   * `widthScale` 是**倍数**（见 engine/PlotModel.kt），渲染时乘基础线宽
//     `metrics.strokeCurve`（466px 屏上约 6.06px）；
//   * 下界 0.4 → 约 2.4px：细到能一眼看出"变细了"，又还没细到在圆屏上断断续续；
//   * 上界 3.0 → 约 18px：需求明确说过"渲染层已经能画很粗的线"，
//     3 倍宽正好是"四条曲线叠在一起还分得清"的极限；
//   * 默认 1.0 落在区间内偏下（(1.0-0.4)/2.6 ≈ 23%），
//     往两边都有足够行程，不会出现"往右只剩一点点能拖"的感觉；
//   * 步长 0.1：6.06px 的基础线宽下，0.1 倍 ≈ 0.6px —— 再细的步长在表上
//     根本看不出来，反而要点很多次。
// ---------------------------------------------------------------------------
private const val WIDTH_MIN = 0.4f
private const val WIDTH_MAX = 3.0f
private const val WIDTH_STEP = 0.1f

/** 滑轨的宽度份数：这一行里除标题和读数之外的剩余宽度**全部**给它。 */
private const val TRACK_WEIGHT = 1f

// ===========================================================================
// 公开入口
// ===========================================================================

/**
 * **函数 / 方程 / 点**的属性编辑界面（需求 #4）。
 *
 * 三栏：名称（点一下就唤起内置英文键盘）、线型（点一下在实线和三档虚线之间循环，
 * 图案表示）、线径（滑动条）。
 *
 * @param fn 被编辑的函数。**外观改动立刻生效**，因为渲染层每帧实时读
 *   `lineStyle` / `widthScale` / `name`（见 GraphScreen 的 curveStyles）。
 * @param rotary 表冠：字母表键盘是一个可滚动列表，必须能转（#5 的要求）。
 * @param onChange 交回**完整的新函数**，调用方直接 `state.updateFunction(it)`。
 * @param onDone 点 ✓：完成并返回列表。
 */
@Composable
fun FunctionPropertyEditorScreen(
    fn: PlotFunction,
    metrics: WatchMetrics,
    rotary: Flow<Float>,
    onChange: (PlotFunction) -> Unit,
    onDone: () -> Unit
) {
    // 名称的草稿 + 光标：名字**没有任何合法性约束**（它只是列表上的一个标签，
    // 空名字也有兜底 —— `PlotFunction.title()` 会退回表达式摘要），
    // 所以可以直接一边打一边写回模型，不需要校验、也不需要警告。
    //
    // 留一份草稿（而不是直接读 fn.name）是为了不让两次按键落在同一帧里时
    // 丢掉一个字符：模型要等下一次重组才回到这个参数上。
    // 名称的草稿 = **只有 `(x)` 前面那一截**（用户要求尾巴不可编辑），
    // 光标也只在这一截里活动；写回模型时再把尾巴拼回去。
    //
    // **尾巴必须冻结**（`remember(fn.id)`，而不是每次由 `fn.name` 现算）：
    // 用户反馈"把原有函数名删掉再按字母，`(x)` 就没了" —— 原因就是名字一旦被写成
    // 空串，下一次重组时 `fn.name.endsWith("(x)")` 变成 false，尾巴凭空消失。
    // 尾巴在一次编辑会话里是**不变量**：进来时什么样，出去时还是什么样。
    val nameSuffix = remember(fn.id) {
        if (fn.name.endsWith(NAME_SUFFIX)) NAME_SUFFIX else ""
    }
    val initialBase = if (nameSuffix.isEmpty()) fn.name else fn.name.dropLast(NAME_SUFFIX.length)
    var nameDraft by remember(fn.id) { mutableStateOf(initialBase) }
    var cursor by remember(fn.id) { mutableStateOf(initialBase.length) }
    var editingName by remember(fn.id) { mutableStateOf(false) }
    var entered by remember(fn.id) { mutableStateOf(false) }
    // 入场动画只播一次。key 用 fn.id 而不是 Unit：万一这一屏被复用到另一个函数上
    // （换了路由过来），entered 会被 remember(fn.id) 一起重置成 false ——
    // 那时这个 LaunchedEffect 必须跟着重跑，否则各栏会永远停在 alpha = 0 上。
    LaunchedEffect(fn.id) { entered = true }

    /**
     * 名字的**强制大小写**。
     *
     * 用户先要求"点和方程强制使用大写"，后来明确指出函数要反过来：
     * "对于函数应该锁定小写才对" —— 因为函数的自动命名是 `a(x)`、`b(x)`（小写），
     * 只有方程/点才是 `A`、`B`（大写，见 `GraphState.nextUpperName`）。
     * 所以这一屏按模式分：
     *   * 隐函数（方程 `F(x,y)=0`）→ 锁定大写；
     *   * 显函数 / 极坐标 / 描点（曲线）→ 锁定小写。
     *
     * 实现方式仍然是"传一个固定的 LetterCase"：模型侧本来就是"取值时才套大小写"
     * （`insertTextOf` 按模式给字母），键盘那边 `showCaseToggle = false`
     * 不显示切换键、`onCaseChange` 也没接回状态 —— 用户没有任何途径改变它。
     */
    val forcedCase = if (fn.mode == PlotMode.IMPLICIT) LetterCase.Lock else LetterCase.Lower

    fun applyNameKey(action: KeyAction) {
        val (text, next) = editText(nameDraft, cursor, action)
        // 名字里只留字母与数字：尾巴 `(x)` 是固定的，要是还能打括号就会变成 `a((x)`；
        // 运算符、逗号同理（它们属于表达式，不属于名字）。
        val cleaned = text.filter { it.isLetterOrDigit() }
        if (cleaned == nameDraft) {
            // 这一次按键被滤掉了（打了括号/运算符）：缓冲区和光标都不动，
            // 免得出现"按了没反应但光标跳了一格"的怪现象
            cursor = cursor.coerceIn(0, cleaned.length)
        } else {
            nameDraft = cleaned
            cursor = next.coerceIn(0, cleaned.length)
            // 空名字写回空串：列表那边有兜底（`PlotFunction.title()` 会退回表达式摘要），
            // 不会出现一个光秃秃的 `(x)`
            onChange(fn.copy(name = if (cleaned.isEmpty()) "" else cleaned + nameSuffix))
        }
    }

    // 真实可用尺寸要自己量一次（和 FunctionEditor / 设置页同一套）：
    // `WatchMetrics.sizeDp` 是**短边**，手机上它等于屏宽 —— 原来这一屏的工具条、
    // 名称栏、各栏全部按短边定位，于是整屏内容只落在 y = 0~388dp 那一块里
    // （真机 850dp 高的屏上底下 462dp 全空，用户："函数编辑页面也没有全屏显示"）；
    // 各栏宽度是 313~374dp（屏宽 411dp），非圆屏现在铺满 386dp。
    // 现在位置按真实屏高（`geometry.sy`）、宽度非圆屏铺满（`bandWidth`）。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = LocalConfiguration.current.isScreenRound

        PropertyEditorShell(
            metrics = metrics,
            geometry = geometry,
            isRound = isRound,
            rotary = rotary,
            name = nameDraft,
            nameCursor = cursor,
            // 名字栏本身就是一个输入框（第一栏 = 名称，用户原话），没有另配标签；
            // 名字空掉时会退回这个提示，让用户知道这里能填什么
            nameHint = "输入名称",
            nameCase = forcedCase,
            // 括号改由**固定尾巴**负责（函数名显示成 `输入框 + (x)`），
            // 所以键盘上不再需要括号键 —— 打了也会被滤掉（见 applyNameKey）。
            // 方程/点本来就不显示括号，这里同样为 false。
            nameBrackets = false,
            nameSuffix = nameSuffix,
            nameEditing = editingName,
            onNameTap = {
                if (editingName) {
                    editingName = false
                } else {
                    // 进编辑态：光标落到末尾 —— 和 FunctionEditor 里点输入框的语义一致
                    // （改名是"接着改"，不是"从头插"；ExpressionField 的点击也不带位置）。
                    cursor = nameDraft.length
                    editingName = true
                }
            },
            onNameKey = ::applyNameKey,
            numberEditing = false,
            onLiftedTap = {},
            onNumberKey = {},
            onDone = onDone,
            lifted = null,
            rows = {
                // ---- 第二栏：线型（点一下切到下一个，**图案表示**）----
                PropertyRow(
                    title = "线型",
                    metrics = metrics,
                    width = listRowWidth(0, metrics, geometry, isRound),
                    entered = entered,
                    index = 0,
                    onClick = { onChange(fn.copy(lineStyle = fn.lineStyle.next())) }
                ) {
                    // 图案用**这条曲线自己的颜色**、并且按当前线径画：
                    // 线径那一栏一拖，这里立刻能看出粗细变化，不用回图像界面确认。
                    LineStyleSketch(
                        style = fn.lineStyle,
                        color = CurvePalette[fn.colorIndex.mod(CurvePalette.size)],
                        strokeWidth = metrics.strokeCurve * fn.widthScale,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }

                // ---- 第三栏：线径 ----
                PropertyRow(
                    title = "线径",
                    metrics = metrics,
                    width = listRowWidth(1, metrics, geometry, isRound),
                    entered = entered,
                    index = 1,
                    // 这一栏**整行不可点**：滑轨自己处理点击和拖动，
                    // 再给整行挂一个 onClick 会和拖动抢手势。
                    onClick = null
                ) {
                    WidthTrack(
                        value = fn.widthScale,
                        metrics = metrics,
                        rowHeight = metrics.sizeDp * ROW_H,
                        onValue = { onChange(fn.copy(widthScale = snapWidth(it))) },
                        modifier = Modifier.weight(TRACK_WEIGHT).fillMaxHeight()
                    )
                    // 读数：1.5 寸屏上光看滑块位置读不出"现在几倍"，
                    // 所以右边给一个小 chip（和设置页的取值 chip 同一个样式）。
                    // 它和滑轨之间的缝就是这一行自己的 ROW_GAP（Row 的 spacedBy 给的），
                    // 不再另加 Spacer —— 否则那一处会比别的间隔宽一倍。
                    ValueChip(text = "${formatEditable(fn.widthScale.toDouble())}×", metrics = metrics)
                }
            }
        )
    }
}

/**
 * **滑动条**的属性编辑界面（需求 #4）。
 *
 * 名称 + 下界 + 上界 + 步长，共四栏。
 *
 * ### 为什么这里没有"线型 / 线径"两栏（**有意的偏离**）
 *
 * 需求原话是"如果进入到的是滑动条的编辑界面，在这个界面下面再增加下界、上界和步长"，
 * 字面上像是"名称 + 线型 + 线径 + 上下界 + 步长"六栏。但 [Slider]（ui/Sliders.kt）
 * 里**根本没有 lineStyle / widthScale 这两个字段**，而那个文件本轮明确不许改；
 * 画两个点了没反应的栏目比不画更糟（用户会以为界面坏了）。所以滑动条的属性界面
 * 是四栏，六栏那种排法在 233dp 的圆屏上其实也放不下（0.115 的行高 × 6 栏
 * 已经超过圆屏 0.945 的下沿）。
 *
 * ### 名称 = 参数字母
 *
 * 见文件头"滑动条的名称"那一节：这里改的是绑定本身，所以有硬约束
 * （一位小写字母、不能和别的滑动条重名），不合法就不写回模型。
 *
 * @param takenParams **别的**滑动条已经占用的参数字母（重名会被拒绝并给出原因）。
 * @param onChange 交回完整的新滑动条。
 */
@Composable
fun SliderPropertyEditorScreen(
    slider: Slider,
    metrics: WatchMetrics,
    rotary: Flow<Float>,
    takenParams: Set<String>,
    onChange: (Slider) -> Unit,
    onDone: () -> Unit
) {
    // 名字的缓冲区：**不能直接写模型** —— 打字途中一定会经过 "a" → "ab" 这种
    // 非法状态，直接写进去会先污染模型再改回来（列表里的滑动条会闪一下、
    // 参数绑定也会短暂跑到非法字母上）。所以草稿留在界面里，只有合法时才提交。
    var nameDraft by remember(slider.id) { mutableStateOf(slider.param) }
    var cursor by remember(slider.id) { mutableStateOf(slider.param.length) }
    var nameWarning by remember(slider.id) { mutableStateOf<String?>(null) }
    var editingName by remember(slider.id) { mutableStateOf(false) }

    // 数值编辑：哪一栏 + 它的缓冲区 + 当前这条缓冲区的拒绝原因
    var numberField by remember(slider.id) { mutableStateOf<SliderField?>(null) }
    var numberBuffer by remember(slider.id) { mutableStateOf("") }
    var numberWarning by remember(slider.id) { mutableStateOf<String?>(null) }

    var entered by remember(slider.id) { mutableStateOf(false) }
    LaunchedEffect(slider.id) { entered = true }

    /**
     * 滑动条的名字**强制小写**：和函数那边一样，靠"传一个固定的 LetterCase +
     * 不显示切换键"实现。模型只给得出小写字母（[AlphabetKey.Letter] 只接受
     * 小写字母），所以这里再兜一次 lowercase 是防"以后有人换了键盘"。
     */
    val forcedCase = LetterCase.Lower

    /**
     * 一个参数字母能不能用。返回非 null = 不能用，字符串是给用户看的原因。
     *
     * ## 尺子本轮放宽过一格（用户原话）
     *
     * "标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
     * 标量也可以用 a，滑动条也可以用 f。"
     *
     * 所以以前那条"只能是 `ParameterSet.BINDABLE`（a/b/c/d）"**撤掉了**：
     * 现在任何**单个小写字母**都能当滑动条的名字。
     *
     * 代价与配套（两件事必须同时成立，否则叫 `f` 的滑动条会让采样崩掉）：
     *   * `GraphState.parameters()` 只把 `a`~`d` 送进 `ParameterSet` ——
     *     引擎对不认识的名字是 `require` 抛异常的；
     *   * 叫别的字母的滑动条靠用户在**函数编辑器**里指认（`a=滑动条` 那颗候选 chip），
     *     采样前折成数值，和标量走同一条路。
     * 也就是说：滑动条叫 `f` 之后，**表达式里那个 `f` 必须先被指认过一次**，
     * 否则引擎会报"不认识的名字 'f'"（这是明说的，不是悄悄算错）。
     *
     * 另外两条约束留着，它们来自数据结构而不是引擎的字母表：
     *   * 一位（`ab` 在表达式里是一个更长的标识符，绑不上任何东西）；
     *   * 同一个参数字母最多一条滑动条 —— 两条 `a` 会各自认为自己才是 `a` 的值，
     *     表达式到底用哪一个就变成看列表顺序了（而 `toParameterSet()` 是后写的赢）。
     */
    fun checkParam(candidate: String): String? = when {
        candidate.isEmpty() -> "名称不能为空"
        candidate.length > 1 -> "只能是一个字母"
        candidate.first() !in 'a'..'z' -> "只能是小写字母"
        candidate in takenParams -> "已经有 $candidate 了"
        else -> null
    }

    fun applyNameKey(action: KeyAction) {
        val (text, next) = editText(nameDraft, cursor, action)
        // 不管合不合法，先把它打进草稿（用户要看见自己打的东西），
        // 但**只有合法时才写回模型**。
        val candidate = text.lowercase()
        nameDraft = candidate
        cursor = next
        val reason = checkParam(candidate)
        nameWarning = reason
        if (reason == null) onChange(slider.copy(param = candidate))
    }

    /**
     * 把某一栏的数值写回模型。返回非 null = **不接受**，字符串是原因。
     *
     * 取舍写在文件头"数字栏的输入语义"那一节：不合法**不写模型、不抛异常**，
     * 把原因交回界面，由界面标红显示。
     */
    fun commitNumber(field: SliderField, value: Double): String? {
        val next = when (field) {
            SliderField.MIN -> slider.copy(min = value)
            SliderField.MAX -> slider.copy(max = value)
            SliderField.STEP -> slider.copy(step = value)
        }
        val reason = when {
            // step 是 Slider.snap 的除数，也是播放时每一步的增量：<= 0 会让吸附
            // 退化、让播放算不出下一步 —— 必须在进模型之前挡住。
            next.step <= 0.0 -> "步长必须大于 0"
            // 轨道跨度 <= 0 的滑动条拖不动、也画不出来（SliderTrack 里 span 会退化成 1）。
            next.min >= next.max ->
                if (field == SliderField.MIN) "下界必须小于上界" else "上界必须大于下界"
            else -> null
        }
        if (reason != null) return reason
        // 范围一改，当前值可能已经落在新区间之外（比如上界从 5 改成 2，而值是 5）。
        // 这里顺手 snap 一次：界面上的滑块位置、折进表达式的参数值、列表里的读数
        // 三者才是一致的（不然会出现"滑块贴着右端、参数却是 5"的鬼状态）。
        onChange(next.copy(value = next.snap(next.value)))
        return null
    }

    fun pressNumberKey(key: NumberPadKey) {
        val next = when (key) {
            is NumberPadKey.Text -> numberBuffer + key.text
            NumberPadKey.Backspace -> numberBuffer.dropLast(1)
            NumberPadKey.Clear -> ""
        }
        // 语法上都不成立的串（`1.2.3`、`1-2`）**不可能是手滑的中间态**，
        // 所以这一次按键直接吞掉，连缓冲区都不动。
        if (!isNumberSyntaxOk(next)) return
        numberBuffer = next
        val field = numberField ?: return
        // `""` / `-` / `.` 这种"还没打完"：只改缓冲区，不动模型。
        val parsed = parseNumberBuffer(next) ?: return
        numberWarning = commitNumber(field, parsed)
    }

    // 收起数值编辑（贴顶那一栏再点一下）。缓冲区一律丢弃，模型不动。
    fun exitNumberEdit() {
        numberField = null
        numberBuffer = ""
        numberWarning = null
    }

    // 和函数属性屏同一套：自己量真实尺寸，位置按真实屏高、宽度非圆屏铺满
    // （理由见 FunctionPropertyEditorScreen 里那段说明）。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = LocalConfiguration.current.isScreenRound

        PropertyEditorShell(
            metrics = metrics,
            geometry = geometry,
            isRound = isRound,
            rotary = rotary,
            name = nameDraft,
            nameCursor = cursor,
        // 名字栏本身就是一个输入框（第一栏 = 名称，用户原话），所以这里不放"名称"
        // 两个字当标签，而是让占位提示自己说清楚能填什么：滑动条的名字就是参数字母，
        // 所以提示直接写可选范围 —— 用户把字母删空时，看到的就是规则本身
        // （同时工具条那侧会显示"名称不能为空"）。
        //
        // 本轮从 `a/b/c/d` 改成 `a~z`（用户："滑动条也可以用 f"）。占位提示跟着改，
        // 否则界面上写的规则和实际收的字母不一致 —— 用户会以为 `f` 打不进去是坏了。
        nameHint = "a~z",
        nameCase = forcedCase,
        // 滑动条的名字是纯字母，**不显示括号**（用户："只有函数的编辑显示括号"）。
        nameBrackets = false,
        nameEditing = editingName,
        onNameTap = {
            if (editingName) {
                // 退出名称编辑（= 需求里的"失焦"）：草稿里**没被接受**的内容丢掉，
                // 输入框回到模型里真正生效的那个字母。这样界面上永远不会出现
                // "显示着 ab、实际绑的还是 a"这种分裂状态（模型全程没被污染过）。
                nameDraft = slider.param
                cursor = slider.param.length
                nameWarning = null
            } else {
                cursor = nameDraft.length
            }
            editingName = !editingName
        },
        onNameKey = ::applyNameKey,
        // 名称栏在编辑时，输入框右边那颗小 chip 也要显示"为什么不行"
        nameWarning = nameWarning,
        numberEditing = numberField != null,
        onLiftedTap = ::exitNumberEdit,
        onNumberKey = ::pressNumberKey,
        onDone = onDone,
        lifted = numberField?.let { field ->
            {
                // 贴到顶部的那一栏：显示**缓冲区**（用户正在打的串），
                // 而不是模型里的旧值 —— 否则按了数字看不出反应。
                NumberRow(
                    field = field,
                    text = numberBuffer.ifEmpty { "—" },
                    metrics = metrics,
                    // 贴顶时它占的是**名称栏**那一格，所以宽度也按名称栏算
                    width = nameRowWidth(metrics, geometry, isRound),
                    warning = numberWarning,
                    entered = true,
                    index = field.ordinal,
                    onClick = ::exitNumberEdit
                )
            }
        },
        rows = {
            SliderField.entries.forEach { field ->
                NumberRow(
                    field = field,
                    // 列表态显示的是**模型里的真值**（编辑态的缓冲区只属于贴顶那一栏）
                    text = formatEditable(field.read(slider)),
                    metrics = metrics,
                    width = listRowWidth(field.ordinal, metrics, geometry, isRound),
                    warning = null,
                    entered = entered,
                    index = field.ordinal,
                    onClick = {
                        // 点某一栏 = 开始编辑它：缓冲区初值就是当前值
                        // （接着改比从零重打省事，要重打就长按 ⌫ 清空）。
                        numberField = field
                        numberBuffer = formatEditable(field.read(slider))
                        numberWarning = null
                    }
                )
            }
        }
        )
    }
}

// ===========================================================================
// 几何对象的属性编辑界面（本轮新增）
// ===========================================================================

/**
 * 一栏里那颗色块的边长（占屏比）。
 *
 * 和画布上那张对象卡原来的 12 色条**同一个值**（0.045）—— 一处是"卡片里塞得下 12 颗"
 * 算出来的：12 × 0.045 ≈ 0.54 屏宽，而那一栏的内容区在圆表上还有 ≈0.70 屏宽。
 * 调大就真的放不下了。
 *
 * ⚠️ **本轮起这一屏不再用它**：颜色那一栏按用户要求删掉了（改由列表行长按色点
 * 唤起调色板）。它和 [SWATCH_RING] 一起留着，是因为它们描述的是"12 色条"这一套
 * 配方的**尺寸依据**（那两个数一旦和列表那边漂掉，将来把颜色栏加回来时就会放不下）
 * —— 和本文件里 `snapLabelAnchor` 那种"留作参照实现"的处理是同一类取舍。
 * 编译器会给一条"未使用"的 warning，那是有意的，不是漏改。
 */
private const val SWATCH_SIZE = 0.045f

/**
 * 色块当前色那圈描边的宽度（占屏比）。和画布卡片同一档。
 *
 * ⚠️ 同 [SWATCH_SIZE]：本轮起这一屏不再用到它（颜色栏已删）。
 */
private const val SWATCH_RING = 0.008f

/**
 * 开关胶囊"选中态"渐染的时长（毫秒）。
 *
 * 160 是**全应用"选中"的统一时长**（`FunctionsScreen` 的 `CHIP_SEL_MS`、
 * 「功能界面」的工具 chip、列表里色点/`123` 的显隐渐染都是它）。那边那个是
 * `private`，引用不到 —— 所以照抄一份并注明来源，两处必须同一个值，
 * 扫一眼的手感才是同一套（和本文件头"照抄 SettingsScreen 的 CHIP_H"同一个做法）。
 */
private const val CHIP_SEL_MS = 160

/**
 * **几何对象**（点 / 直线·线段·射线 / 角度 / 距离 / 面积）的属性编辑界面。
 *
 * 用户原话："给所有函数列表里面的对象都加长按菜单，直接调用函数的长按菜单，
 * 但是注意要适配各个对象的特征，比如点的名字只能是大写，诸如此类。"
 *
 * ## 为什么复用 [PropertyEditorShell]（而不是把 `ObjectPropertiesBubble` 那种浮层搬过来）
 *
 * 函数属性屏和滑动条属性屏已经是"**骨架共用、两端的业务各写各的**"（见文件头那一段），
 * 几何对象是第三端：它要的骨架恰好一模一样 ——
 * 顶部一颗 ✓、名称栏（点一下唤起字母键盘、编辑时顶到最上面）、下面各栏按
 * `ROW_H + ROW_GAP` 依次排开。所以这里**只加一个公开 composable，不加第二套界面语言**：
 * 名称栏、⌫、字母键盘、各栏的胶囊（[PropertyRow]）、值 chip（[ValueChip]）、
 * 行宽（[listRowWidth] 那套按圆屏弧宽算的函数）全部照旧。
 *
 * ## 栏目**按对象类型给**（这就是用户说的"适配各个对象的特征"）
 *
 * ## ⚠️ 本轮又删了三样（用户原话）
 *
 * "然后所有对象的长按显示菜单，都不要显示它的数据、颜色和是否显示的开关。"
 *
 * 所以这一屏现在**只剩两样**：
 *
 * | 栏目 | 点 | 直线/线段/射线 | 角度/距离/面积（标量） |
 * |---|---|---|---|
 * | 名字 | 可改，大写 | 可改，大写 | 可改，小写 |
 * | 类型 | "点" | "直线 · 垂线"… | "角度"/"距离"/"面积" |
 *
 * 删掉的**不是能力**，而是入口的重复 —— 那三样都有更近的地方：
 *
 * | 删掉的 | 现在去哪儿 |
 * |---|---|
 * | 数据（坐标 / 方程 / 数值） | **短按列表行 → 详情页**（只读、显示完全、不省略号）；列表行第二行也还写着摘要 |
 * | 颜色（12 色条） | **长按列表行的色点**（调色板浮层） |
 * | 图形显示/隐藏、数值显示/隐藏 | **列表行的色点**（单击切显隐）与 **`123`**（数值）；还有图像界面上点中对象那张属性卡上的 `123` |
 *
 * 也就是说：长按这一屏 = "改名字"（+ 看类型），其余全在列表行上 ——
 * 这也是用户连着两轮在表达的那个方向："这和在函数列表里面的功能重合了。"
 *
 * ⚠️ 列表行上那两处（色点、`123`）**是被保留下来的**：用户点名要删的是
 * **这一屏里**的三栏，不是列表行的入口。所以这里只删 `rows` 里的三栏，
 * 一个回调都没从状态层摘掉（[onPickColor] / [onToggleVisible] / [onToggleValue]
 * 三个参数**故意留在签名里**：它们在调用点 `GraphScreen.kt` 传着，
 * 而且"哪天用户又想要回一栏"时不用动调用方那一处）。
 *
 * ## 为什么最多只有四栏（这一条现在是历史约束，留着是因为它解释了排版上限）
 *
 * 骨架里各栏是从 `ROW_TOP + ROW_H + ROW_GAP`（= 0.336 屏高）往下按 0.115 + 0.016
 * 排的，第 5 栏就会落到 0.860~0.975 —— 那一段圆屏的弦宽只剩约 0.31 屏宽，
 * 而 [listRowWidth] 是按**栏中心**算的（≈0.55 屏宽），四角会越过圆弧被表壳切掉。
 * 所以上限是 4 栏（上一版的两个开关就是为这个并排挤在一栏里的）。
 * 本轮只剩两栏，离上限更远了 —— 但这条约束对**将来加回来的栏**依然有效。
 *
 * ## 名字（唯一一处与函数/滑动条都不同的地方）
 *
 *   * **字符集**：点、直线、线段、射线**只能是大写字母**（[ObjectNameCase.UPPER]），
 *     角度/距离/面积**只能是小写字母**（[ObjectNameCase.LOWER]）——
 *     依据是 `x(A)` 这种"取坐标"写法靠 [isPointName] 只认大写来识别，
 *     而标量要能直接写进表达式当数值（`a*x`），引擎里"像变量的名字"就是小写字母序列。
 *     实现上仍然是"传一个固定的 `LetterCase` + 键盘不显示切换键"（[forcedCase]），
 *     和函数/滑动条那两屏**完全同一个做法**；
 *   * **一边打字一边过滤**：滤掉数字、符号和另一个大小写的字母，
 *     所以键盘上即使有括号键（这里 `nameBrackets = false`，本来就不显示）也打不进名字；
 *   * **不合法就不写回模型**：超长（> [MAX_OBJECT_NAME_LEN]）、空、重名、
 *     或者用了 `x/y/t/e/i/z` 这种在表达式里有固定含义的字母时，
 *     草稿留在界面上、模型一动不动，原因 **直接显示**在工具条右侧
 *     （就是 [objectNameError] 返回的那句话，界面一个字都不改写）。
 *     退出编辑时草稿一律丢弃、回到模型里真正生效的那个名字 ——
 *     界面上不可能出现"显示着 E、实际还是 A"的分裂状态。
 *
 * ## 改名的后果（**同步改引用**，实现在 `GraphState.renameObject`）
 *
 * 提交那一瞬间是**一次原子操作**：对象自己的名字 + 所有函数表达式里的旧名字
 * + 度量副标题里的点名，一次改完。为什么必须原子、两种引用（按 id 的绑定表 vs
 * 按名字的表达式文本）各是什么命运，全写在那个方法的 KDoc 里，这里不重复。
 * 界面这一侧只做两件事：**校验**（[objectNameError]）和**把结果画出来**。
 *
 * @param taken 别的占用者的名字（其它对象名 + 函数名/可调用名 + 滑动条的参数字母，
 *   也就是 `objectNameError` 那份契约里的三样）。**不能包含这个对象自己的名字** ——
 *   否则"点进去直接退出"也会被报成重名
 *   （`GraphState.takenObjectNames(id)` 就是按这条规矩给的）。
 * @param onRename 交回**新名字**（已经过校验），调用方直接
 *   `state.renameObject(obj.id, it)`。只在合法时调用，所以那边不会失败。
 * @param onPickColor 12 色里点了一颗：落到 `state.setObjectColor`。
 *   ⚠️ **本轮起这一屏不再调用它**（颜色那一栏删了，改由列表行长按色点的调色板负责）——
 *   参数留在签名里见上面那段说明。
 * @param onToggleVisible 图形显示/隐藏（[GraphObject.visible]）。同上，本轮不再调用。
 * @param onToggleValue 数值显示/隐藏（[GraphMeasureObject.showValue]）。同上，本轮不再调用。
 */
@Composable
fun ObjectPropertyEditorScreen(
    obj: GraphObject,
    metrics: WatchMetrics,
    rotary: Flow<Float>,
    taken: Set<String>,
    onRename: (String) -> Unit,
    onPickColor: (Int) -> Unit,
    onToggleVisible: () -> Unit,
    onToggleValue: () -> Unit,
    onDone: () -> Unit
) {
    // 名字的草稿 + 光标：**不能直接写模型**。理由和滑动条那一屏一样 ——
    // 打字途中一定会经过非法状态（`""`、超长、重名），直接写进去等于先污染模型再改回来，
    // 而这里比滑动条更严重：名字是**表达式引用的键**，中间那一帧图上会出现
    // "不认识的名字"。所以草稿留在界面里，只有 [objectNameError] 判为合法时才提交，
    // 提交那一步本身是 `GraphState.renameObject` 的一次原子改名。
    var nameDraft by remember(obj.id) { mutableStateOf(obj.name) }
    var cursor by remember(obj.id) { mutableStateOf(obj.name.length) }
    var editingName by remember(obj.id) { mutableStateOf(false) }
    var entered by remember(obj.id) { mutableStateOf(false) }
    // 入场动画只播一次；key 用 obj.id（理由同函数那一屏：万一这一屏被复用到
    // 另一个对象上，entered 会跟着重置，这个 effect 必须重跑）。
    LaunchedEffect(obj.id) { entered = true }

    /** 这个对象的名字该用哪一套字母（规则本体在 `GraphObjects.kt`，这里只是翻译成键盘模式）。 */
    val upperCaseName = objectNameCase(obj) == ObjectNameCase.UPPER

    /**
     * 键盘的**强制大小写**：点/线锁定大写、标量锁定小写。
     *
     * 用 [LetterCase.Lock] 而不是 [LetterCase.Upper]：两者插入的字母完全一样，
     * 区别只在"再点一次切换键去哪"，而这里 `showCaseToggle = false`，
     * 用户根本没有那个键 —— 但 `Lock` 在标签语义上就是"一直大写"，
     * 和 `FunctionPropertyEditorScreen` 给方程用的那一档是同一个选择。
     */
    val forcedCase = if (upperCaseName) LetterCase.Lock else LetterCase.Lower

    /**
     * 当前草稿为什么不能用作名字；null = 可以用。
     *
     * 草稿和模型一致时**一律不报**：模型里的旧名字可能是在更松的规则下产生的
     * （比如从 `.fdf` 读进来的），那种名字不该在用户什么都没做的时候被挑刺。
     * `taken` 是参数给的、由父层每次重组现取，所以"别的对象刚改了名"这件事
     * 立刻就能反映到这里的判断上。
     */
    val nameWarning = if (nameDraft == obj.name) null else objectNameError(obj, nameDraft, taken)

    fun applyNameKey(action: KeyAction) {
        val (text, next) = editText(nameDraft, cursor, action)
        // 名字**只有字母**，而且大小写是强制的：这里按同一个字符集口径再滤一道 ——
        // 点/线只留 `A~Z`、标量只留 `a~z`（和 `isPointName` / `isScalarName` 那把
        // 尺子**逐字一致**，所以"能打进草稿的"就是"可能是合法名字的"）。
        // 滤掉的不只是数字和符号，还有"另一个大小写的字母"——
        // 键盘那边大小写已经锁死、切换键也不显示，滤这一道是防"以后有人换了键盘"。
        val cleaned = text.filter { c -> if (upperCaseName) c in 'A'..'Z' else c in 'a'..'z' }
        if (cleaned == nameDraft) {
            // 这一次按键被滤掉了（打了数字/括号/另一个大小写的字母）：
            // 草稿和光标都不动，免得出现"按了没反应、光标却跳了一格"。
            cursor = cursor.coerceIn(0, cleaned.length)
            return
        }
        nameDraft = cleaned
        cursor = next.coerceIn(0, cleaned.length)
        // **只有合法时才写回模型**：不合法时模型一直是最后一次被接受的名字，
        // 表达式里的引用也就一直有效 —— 这正是"草稿 + 校验"这套写法要保证的事。
        if (objectNameError(obj, cleaned, taken) == null) onRename(cleaned)
    }

    // 和另外两个属性屏同一套：自己量真实尺寸（位置按真实屏高、宽度非圆屏铺满）。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = LocalConfiguration.current.isScreenRound

        PropertyEditorShell(
            metrics = metrics,
            geometry = geometry,
            isRound = isRound,
            rotary = rotary,
            name = nameDraft,
            nameCursor = cursor,
            // 占位提示写的就是这一屏**收什么字母**（和滑动条那屏写 `a~z` 同一个用意：
            // 用户把名字删空时，看到的就是规则本身；空名字的具体原因由工具条那侧说）。
            nameHint = if (upperCaseName) "A~Z" else "a~z",
            nameCase = forcedCase,
            // 点/线/标量的名字都是纯字母：**没有括号**（只有函数名才是 `a(x)` 那种形状）。
            nameBrackets = false,
            // 名字栏后面**没有**固定尾巴（`(x)` 是函数专有的）。
            nameSuffix = "",
            nameEditing = editingName,
            onNameTap = {
                if (editingName) {
                    // 退出名称编辑（= 需求里的"失焦"）：草稿里**没被接受**的内容丢掉，
                    // 输入框回到模型里真正生效的那个名字。
                    nameDraft = obj.name
                    cursor = obj.name.length
                } else {
                    // 进编辑态：光标落到末尾（改名是"接着改"，不是"从头插"）。
                    cursor = nameDraft.length
                }
                editingName = !editingName
            },
            onNameKey = ::applyNameKey,
            // 校验失败的原因显示在工具条右侧那一格（和滑动条那一屏同一个位置、
            // 同一个颜色：这一屏本来就没有别的空地，而那句话本身就是提示）。
            nameWarning = nameWarning,
            // 这一屏**没有**数值栏：几何对象的值是量出来的（点坐标、度量值），
            // 用户改不了也不该改（用户的定性："滑动条可以调，这个不能调"）。
            numberEditing = false,
            onLiftedTap = {},
            onNumberKey = {},
            onDone = onDone,
            lifted = null,
            rows = {
                // 各栏从上往下的**位置序号**（既喂 stagger 入场，也喂 listRowWidth 的弧宽核算）。
                // 本轮只剩两栏，序号照样从 0 起 —— 它是"第几行"的唯一来源，
                // 将来谁再加一栏，只要跟着 `row++` 就不会用错弧宽。
                var row = 0

                // ---- 1. 类型（只读）----
                PropertyRow(
                    title = "类型",
                    metrics = metrics,
                    width = listRowWidth(row, metrics, geometry, isRound),
                    entered = entered,
                    index = row,
                    // 只读：没有 onClick 就没有按压反馈，一眼看出"这不是个能按的东西"。
                    onClick = null
                ) {
                    ValueChip(text = propertyKindText(obj), metrics = metrics)
                }
                row++

                // ---- 2. 「删掉的那三样现在去哪儿」（说明文字，不是一栏）----
                //
                // 用户这一轮把"数据 / 颜色 / 是否显示"三样从这一屏删掉了，于是下面空出一大块。
                // 与其留一片空档，不如把"那三样现在在哪儿找"写在原处 —— 用户下次来这一屏
                // 找颜色或显隐时，一眼就知道该去列表行，而不是以为功能被删掉了。
                //
                // 它**刻意不做成一栏**：没有胶囊底、没有 onClick = 不会让人以为"点了还有下一层"
                // （项目里明确反对"点了没反应的栏目"，见上面"为什么最多只有四栏"那段旁边
                // 关于滑动条属性屏的同类取舍）。字号退一档（fontTiny）、颜色用次要色，
                // 宽度按它所在那一行的**圆弧宽度**收窄，圆表上两边不会被表壳切掉。
                Text(
                    text = "颜色 · 显示：列表行上的色点 / 123\n" +
                        "完整数据：短按列表行打开详情",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    modifier = Modifier
                        .width(listRowWidth(row, metrics, geometry, isRound))
                        .padding(horizontal = metrics.sizeDp * ROW_PAD)
                )
            }
        )
    }
}

// ===========================================================================
// 几何对象的「详情页」（本轮新增 —— 只读，**显示完全**）
// ===========================================================================

/**
 * 详情页那张"完整数据"卡**最多估多高**（占屏高）。
 *
 * 它只用来算这张卡的**宽度**：`rowSpanWidth` 要的是"这一块内容上下沿"，而卡片高度是
 * 内容撑开的（未知）。按一个足够大的上界算，得到的就是"即使它长到这么高也不会顶到
 * 圆弧外面"的那个宽度 —— 估大了只是卡片略窄一点，估小了才会被表壳切角
 * （和 `GraphScreen.OBJECT_CARD_MAX_H` 同一个取舍）。
 *
 * 0.30 的依据：一张卡 = 内边距(0.025×2) + 数据文字。字号已经按长度缩过
 * （见 [detailValueFont]），最长的那一类（`y = 2x + 1 (x ≥ -1)` 这种二十几个字符）
 * 在这张卡的宽度里排两行就到头了 —— 两行 fontTiny 约 0.09，加上内边距远不到 0.30。
 */
private const val DETAILS_CARD_MAX_H = 0.30f

/**
 * 详情页那一次淡入 + `0.94 → 1` 缩放的时长（毫秒）。
 *
 * 190 是**全应用"浮起来的东西"的统一时长**（画布那张属性卡的 `CARD_IN_MS`、
 * 列表里的调色板面板 `BUBBLE_IN_MS`、属性屏各栏的 `ROW_IN_MS` 都是它），
 * 落在项目约定的 120~260ms 区间里。
 *
 * 它和**路由那一层**的转场（`GraphScreen.AnimatedContent`：从列表离开时
 * 新屏淡入 220ms + 从 0.94 放大 260ms）是**并存**的 —— 那层负责"进出这一屏"，
 * 这层负责"这一屏里的内容浮起来"，和画布卡片那两层并存的分工完全一样。
 */
private const val DETAILS_IN_MS = 190

/** 色点与名字之间的缝（占短边）。和画布那张属性卡的同一处间距取同一个值。 */
private const val DETAILS_DOT_GAP = 0.022f

/**
 * 详情页里"完整数据"的字号：按**字符数缩到放得下**。
 *
 * ## 为什么详情页需要它（这一屏存在的理由就是"显示完全"）
 *
 * 用户原话："因为有些点和直线里面数值太长了，在这个详情页面只显示这个点的颜色、
 * 名字和它的坐标，直线就显示它的解析式，**显示完全**。" —— 列表里那一行是
 * `maxLines = 1` + `TextOverflow.Ellipsis`，长坐标/长方程一定会被省略号吃掉；
 * 这一屏必须把整串显示出来，所以：
 *
 *   * **允许换行**（`softWrap`，见调用处的 `maxLines`）——这是主要手段；
 *   * **字号按长度缩一档**（这个函数）——这是兜底：换行会把卡片撑高，
 *     而圆屏上下两端越来越窄，缩一档比"排到第四行"更好看；
 *   * **绝不省略号**（调用处 `overflow = TextOverflow.Clip`）。
 *
 * 档位取 0.92 / 0.84 / 0.76 / 0.68：每一步约 8%，肉眼看上去还是"同一行小字"，
 * 而 20 个字符和 40 个字符的宽度差了近一倍 —— 靠这一点把最长的那些收进两行。
 * 它是**纯展示用的比例**（不参与任何判定），所以按经验分档就够，
 * 不必像 `PlotMetrics` 里那些判定一样交给离线单测。
 *
 * @param text 要显示的那串数据（调用方已经取好，不会为 null）。
 * @return 这一串该用的字号（sp）。
 */
private fun detailValueFont(metrics: WatchMetrics, text: String): TextUnit {
    val scale = when {
        text.length <= 14 -> 1f
        text.length <= 20 -> 0.92f
        text.length <= 26 -> 0.84f
        text.length <= 34 -> 0.76f
        else -> 0.68f
    }
    return metrics.fontTiny * scale
}

/**
 * **几何对象详情页**（用户本轮要求：短按列表行打开的那一屏）。
 *
 * 用户原话："给除了函数之外点击没有菜单的加一个点击显示详情页面，因为有些点和直线
 * 里面数值太长了，在这个详情页面只显示这个点的颜色，名字和它的坐标，直线就显示
 * 它的解析式，显示完全。"
 *
 * ## 这一屏**只显示三样**（多一样都不加）
 *
 * | 显示什么 | 怎么显示 |
 * |---|---|
 * | **颜色** | 对象自己的色点（`CurvePalette[colorIndex]`），和列表行左边那颗同一个色 |
 * | **名字** | 对象名（点 = `A`、线/度量 = `a`），不省略 |
 * | **完整数据** | 点 = 坐标、直线 = 解析式（`lineEquationSummary`）、度量 = 数值；**换行 + 按长度缩字号 + 绝不省略号** |
 *
 * 类型、来源（描点/交点）、显隐、颜色编辑**都不在这一屏**：类型在长按那一屏
 * （[ObjectPropertyEditorScreen]），显隐/颜色在**列表行**上（色点、`123`）。
 * 用户点名要的就是这三样，多写一样就等于把"短按"变成一个菜单 —— 那正是他删掉的东西。
 *
 * ## 和 [ObjectPropertyEditorScreen] 的分工（**两个不同的东西**）
 *
 * | | 短按（本屏） | 长按（属性屏） |
 * |---|---|---|
 * | 性质 | **只读**、无输入、无键盘 | **可改**名字（有键盘、有校验） |
 * | 目的 | 把列表里被截断的东西**看全** | 改名字 |
 *
 * 所以本屏**不复用** [PropertyEditorShell]：那个骨架带着"名称栏可点、点了弹字母键盘、
 * 数值栏点了弹数字键盘"的一整套输入语义，拿它来渲染只读内容会出现"点了没反应"
 * （项目里明确反对这个，见属性屏那边"为什么滑动条没有线型/线径"）。共用的是
 * **版面常量与浮层语言**（顶部 ✓ 的位置、`ROW_TOP` / `ROW_H` / `ROW_PAD`、
 * `listRowWidth` / `rowSpanWidth` 那两套弧宽算法、`KeyPalette` 的浮层三件套），
 * 那些东西在这个文件里都是 private，本屏直接调 —— 于是"详情页看起来就是这一家的界面"。
 *
 * ## 动画（沿用项目语言）
 *
 *   * **进出这一屏**：由路由那一层的 `AnimatedContent` 负责 —— 从列表离开时
 *     走的是和长按属性屏**完全相同**的那条分支（新屏淡入 220ms + 从 0.94 放大 260ms，
 *     `tween` + `FastOutSlowInEasing`）；
 *   * **屏内那两行浮起来**：同一个 `appear` 值喂 [bubbleFade]（alpha + `0.94 → 1` 缩放），
 *     190ms。**只动 `graphicsLayer`**：标题栏、色点的位置和命中区在动画期间一个像素都不动
 *     （和 `PressFeedback` / 画布卡片同一条铁律）。
 *
 * @param obj 要展示的几何对象。**可能已经被别处删掉** —— 那种情况由调用方
 *   （`GraphScreen` 的 `Route.Details` 分支）判掉并回列表，和 `Route.Properties` 一样。
 * @param onDone 点顶部 ✓：返回列表（系统返回键走同一条路，见 `GraphScreen` 的 `BackHandler`）。
 */
@Composable
fun ObjectDetailsScreen(
    obj: GraphObject,
    metrics: WatchMetrics,
    onDone: () -> Unit
) {
    val color = CurvePalette[obj.colorIndex.mod(CurvePalette.size)]
    val data = propertyDataText(obj) ?: "—"

    // 入场：一次性的 0 → 1（key 用 obj.id：万一这一屏被复用到另一个对象上，
    // 动画会跟着重播一次，而不是停在上一个对象已经涨到 1 的状态上）。
    var entered by remember(obj.id) { mutableStateOf(false) }
    LaunchedEffect(obj.id) { entered = true }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = DETAILS_IN_MS, easing = FastOutSlowInEasing)
    )

    // 和三个属性屏同一套：自己量真实尺寸（位置按真实屏高、宽度非圆屏铺满）。
    // 这一屏是只读的，所以只有"宽度 + 两个落点"要换算，没有任何输入路径受影响。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = LocalConfiguration.current.isScreenRound

        /** 数据卡顶：名称行（`ROW_TOP ~ ROW_TOP + ROW_H`）下面一条缝。和另外两屏同一个落点。 */
        val dataTop = ROW_TOP + ROW_H + ROW_GAP
        val nameWidth = nameRowWidth(metrics, geometry, isRound)
        // 数据卡的宽度：取它上下沿里**更窄**的那一处（圆屏越往下越窄）。
        // 上界用 DETAILS_CARD_MAX_H（卡片高度是内容撑开的，这里给一个足够大的估高）。
        val dataWidth = rowSpanWidth(metrics, geometry, isRound, dataTop, dataTop + DETAILS_CARD_MAX_H)

        // ---- 顶部：一颗 ✓（和三个属性屏的 ✓ 在同一处、同一颗按钮）----
        // 这一屏没有别的东西可点，所以它就是"离开这里"的唯一可见入口；
        // 系统返回键也回列表（BackHandler 在 GraphScreen 那一侧）。
        //
        // ## 宽度自适应（用户那条长期规则："以后你写所有按键都要加上对屏幕宽度的自适应"）
        //
        // 分两件事说 —— 这一行里"谁自适应、谁按例外处理"是 README 定好的，别自己发明：
        //
        //   * **容器行（自适应）**：宽度走 [rowSpanWidth]（=`bandWidth`）——
        //     圆屏取 `TOOLBAR_TOP ~ TOOLBAR_TOP + TOOLBAR_H` 这一段上下沿里更窄的弧宽
        //     （**逐像素等于改动前**），非圆屏由 `ScreenGeometry.rowWidth` 给
        //     "屏宽 − 左右各 3%"。按 README「硬约定」那张表，行/浮层一律走这一支。
        //   * **✓ 这颗键（README 里唯一的例外）**：它属于 `WatchIconButton` 那一类
        //     **形状锁定**的图标键（⚙ + 📁 🗑 ✓ ⌫），宽高由 `Common.kt` 按
        //     `0.18 × 短边` + `ICON_BTN_ASPECT`（1.44:1 胶囊）统一锁死 ——
        //     依据是用户更早的一条明确要求"请你让按键强制保持在一个形状"。
        //     所以调用点**只写比例、不写基准**（`0.16 × 短边`），
        //     和 `PropertyEditorShell` 顶部那颗 ✓ 逐字同款：从列表进详情、再进属性屏，
        //     那颗 ✓ 的大小和位置都不会跳。
        //     它也**不是"窄柱"**：真正自适应的是上面那一行（行宽走 `bandWidth`），
        //     所以手机上不会在它旁边留出空隙。
        //     ⚠️ 哪天真要连图标键本身也跟着屏宽走，README 指明改
        //     `Common.kt` 的 `WatchIconButton` **一处**即可（本屏这一行不用动）——
        //     那属于"改全应用图标键"，不是本屏该单独拉长一颗。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(TOOLBAR_TOP))
                .width(
                    rowSpanWidth(
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        yTop = TOOLBAR_TOP,
                        yBottom = TOOLBAR_TOP + TOOLBAR_H
                    )
                )
                .height(metrics.sizeDp * TOOLBAR_H),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "返回",
                metrics = metrics,
                modifier = Modifier
                    // 图标键的形状锁在 `Common.kt`（README「硬约定」的唯一例外），
                    // 所以这里写的是"比例 × 短边"，和 `PropertyEditorShell` 那颗 ✓ 一致。
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * TOOLBAR_H * 0.85f),
                filled = true,
                onClick = onDone
            )
        }

        // ---- 第一行：**色点 + 名字**（颜色与名字就是这两样）----
        //
        // 用"一栏"的形状（`KeyPalette.normal` + 胶囊）：它和属性屏里那些栏、
        // 以及列表行是同一套读法 —— 左边一颗色点、右边名字。
        // 名字**不省略**：对象名最多 3 个字母（见 `MAX_OBJECT_NAME_LEN`），
        // 这一行的宽度足够，`maxLines = 1` 只是兜底。
        Surface(
            color = KeyPalette.normal,
            contentColor = KeyPalette.text,
            shape = PillShape,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(ROW_TOP))
                .width(nameWidth)
                .height(metrics.sizeDp * ROW_H)
                // 入场：只动图层（alpha），位置/命中区一个像素都不动
                .bubbleFade(alpha = appear)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = metrics.sizeDp * ROW_PAD),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    // ⚠️ 它是**指示器**不是按键：`metrics.dot`（0.05 × 短边）是"色点多大"，
                    // 和列表行左边那颗、画布那张卡上那颗同一个尺寸 —— 圆点没有"宽度自适应"
                    // 这回事（用户那条长效规矩管的是**按键/可点容器的宽度**，
                    // 例如这一屏上面那一行的 `rowSpanWidth`）。
                    // 它也没有任何点击修饰符：详情页是**只读**的。
                    modifier = Modifier
                        .size(metrics.dot)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(metrics.sizeDp * DETAILS_DOT_GAP))
                Text(
                    text = obj.name,
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.text,
                    maxLines = 1
                )
            }
        }

        // ---- 第二行：**完整数据**（这一屏存在的理由）----
        //
        // 浮层语言和画布那张属性卡**完全同一套**（近黑底 + 1dp 20% 白描边 + 大圆角），
        // 所以"这是浮起来的一块信息"在三处界面里长得一样。
        //
        // ⚠️ 三条保证"显示完全"的写法，改这一屏时别拆：
        //   1. `maxLines` 给足（6 行）—— 换行是主要手段；
        //   2. `overflow = TextOverflow.Clip`，**绝不是 Ellipsis**：
        //      用户报这条需求就是因为列表里那个省略号；
        //   3. 字号按长度缩一档（[detailValueFont]）。
        //   文字颜色用纯白（`KeyPalette.text`）而不是强调色：这一屏的 KPI 是**读得清**，
        //   近黑底上白字的对比度最高（画布那张卡用强调色是因为它要和曲线信息卡统一）。
        Surface(
            color = KeyPalette.bubble,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(dataTop))
                .width(dataWidth)
                // 描边**必须**写在 bubbleFade 右边：graphicsLayer 只影响它内层画的东西，
                // 写在左边的描边不会跟着淡出（本项目为这个坑付过一次代价，
                // 见 `Common.bubbleFade` 的 KDoc）
                .bubbleFade(alpha = appear, scale = 0.94f + 0.06f * appear)
                .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
        ) {
            Text(
                text = data,
                fontSize = detailValueFont(metrics, data),
                color = KeyPalette.text,
                maxLines = 6,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(
                    horizontal = metrics.sizeDp * 0.035f,
                    vertical = metrics.sizeDp * 0.025f
                )
            )
        }
    }
}

/**
 * 属性栏里的一颗**开关**胶囊（选中 = 强调色，160ms 渐染）。
 *
 * 和 `FunctionsScreen.PropertyActionChip` 是同一个配方（底色/文字色都跟着 `active`
 * 用 `lerp` 渐染、同一个 160ms 的 tween），也和画布那张对象卡上的 `CardToggleChip`
 * 一致。之所以在这里重写一个而不是去引用那两处：它们都是各自文件里的
 * `private`，而那两个文件本轮**都不许改** —— 这个取舍和本文件里 [ValueChip]
 * 的说明是同一件事（照同一个配方抄 15 行，观感不会漂）。
 *
 * **未选中态用 [KeyPalette.normal]**（= 它所在那一栏的底色）是有意的：
 * 这颗胶囊是"栏内的一颗键"，未选中时它就该和栏底融在一起，
 * 只留下文字告诉你那里能按；选中才浮出强调色。所以这里不需要另造一个"未选中底色"。
 *
 * ⚠️ **本轮起本文件里没有调用点**：唯一用它的是对象属性屏的"显示"那一栏，
 * 而那一栏按用户要求删掉了（图形显隐改由列表行的色点负责、数值显隐由 `123`）。
 * 留着它的理由和 [SWATCH_SIZE] 那段一样 —— 它是"栏内开关"这套配方的**参照实现**
 * （画布那张属性卡上的同名件就是照它抄的），而且用户随时可能要求把"显示"加回来。
 * 编译器会给一条"未使用"的 warning，那是有意的，不是漏改。
 *
 * ## 宽度（用户本轮的长效规矩："以后你写所有按键都要加上对屏幕宽度的自适应"）
 *
 * 这个件**自己不写宽度** —— 宽度只能由 `modifier` 传进来，而调用方一律给
 * "跟着自适应行宽走"的东西：`Modifier.weight(1f)`（在满宽的行里平分）
 * 或者一个按 `bandWidth` 算出来的 `width`。它内部只固定**高度**（`0.085 × 短边`，
 * 和栏高 `ROW_H` 配套）。⚠️ 将来把它加回属性屏时，**别**给一个 dp 宽度：
 * 那就是这条规矩要根治的写法。
 */
@Composable
private fun PropertyToggleChip(
    label: String,
    active: Boolean,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = CHIP_SEL_MS, easing = FastOutSlowInEasing)
    )
    Box(
        modifier = modifier
            // 栏高是 ROW_H（0.115），胶囊占 0.085：上下各留一点缝，
            // 看起来才是"栏里的一颗键"而不是"一栏套一栏"。
            .height(metrics.sizeDp * 0.085f)
            .clip(PillShape)
            .background(lerp(KeyPalette.normal, KeyPalette.accent, t))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = metrics.fontTiny,
            // 文字在"80% 白 → 纯白"之间跟着走，和底色同步（同 PropertyActionChip）
            color = lerp(KeyPalette.textMuted, KeyPalette.text, t),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ===========================================================================
// 公共骨架
// ===========================================================================

/**
 * 属性界面的骨架：顶部一颗 ✓、名称输入框（会在两个落点之间移动）、
 * 以及下面那块"当前该显示哪种键盘"的地方。
 *
 * 状态的**归属**是刻意的：编辑态（在改名称还是在改某一栏数值）留在调用方，
 * 骨架只负责画和转发 —— 因为"提交一个值"这件事只有调用方知道该往哪个模型写。
 *
 * @param onNameTap 名称输入框被点：列表态进编辑态、编辑态退出（= 需求里的"失焦"）。
 *   字母表键盘左上角那颗「返回」键走的也是它。
 * @param lifted 数值编辑时贴到顶部的**那一栏**（名称栏此时不显示，位置让给它）。
 * @param rows 列表态的各栏（骨架把它们排成 SettingsScreen 那样的定高列表）。
 */
/**
 * 函数名的固定尾巴。
 *
 * 用户要求"把函数的名字更改变成只能改 `(x)` 前面的内容"，所以这一截在界面上
 * 是只读的（见 [PropertyEditorShell] 的 `nameSuffix`），改名时只编辑它前面的部分。
 * 自动命名（`GraphState.nextCurveName`）产出的就是 `a(x)` 这种形状，两边一致。
 */
private const val NAME_SUFFIX = "(x)"

@Composable
private fun PropertyEditorShell(
    metrics: WatchMetrics,
    /**
     * 真实屏幕几何（这一屏自己 `BoxWithConstraints` 量出来的）。
     *
     * ⚠️ 这个骨架原来把工具条、名称栏、各栏、键盘区的位置全写成 `sizeDp × 比例`
     * （短边）：圆表上短边 == 屏高所以一直是对的，手机上短边是**屏宽**，
     * 于是整屏内容都挤在顶部一个方格里（用户："函数编辑页面也没有全屏显示"）。
     * 现在位置按**真实屏高**（[ScreenGeometry.sy]）、宽度非圆屏铺满
     * （`MathKeyboard.bandWidth` 里的满宽那一支）；控件尺寸（栏高、字号、chip）
     * 一个都没动，仍按短边缩放。
     */
    geometry: ScreenGeometry,
    /** 圆屏还是非圆屏 —— 为 true 时下面每一处都走原来的 `sizeDp × 比例`（逐像素不变）。 */
    isRound: Boolean,
    rotary: Flow<Float>,
    name: String,
    nameCursor: Int,
    nameHint: String,
    nameCase: LetterCase,
    nameBrackets: Boolean,
    nameEditing: Boolean,
    onNameTap: () -> Unit,
    onNameKey: (KeyAction) -> Unit,
    /**
     * 输入框后面那个**不可编辑的固定尾巴**（函数名是 `(x)`）。
     *
     * 用户要求："把函数的名字更改变成只能改 `(x)` 前面的内容" ——
     * 所以 `(x)` 不放进可编辑缓冲区，而是当成一段只读的尾标显示在输入框右边，
     * 用户怎么按都改不到它。空串表示没有尾巴（滑动条、方程、点都用空串）。
     */
    nameSuffix: String = "",
    numberEditing: Boolean,
    onLiftedTap: () -> Unit,
    onNumberKey: (NumberPadKey) -> Unit,
    onDone: () -> Unit,
    lifted: (@Composable () -> Unit)?,
    rows: @Composable () -> Unit,
    nameWarning: String? = null
) {
    // ---- 名称输入框的两个落点与宽度 ----
    //
    // 高度不变（ROW_H 和 FIELD_H 都是 0.115），只有 y 在动：
    //   * 编辑态：y = FIELD_Y（0.192）—— 就是 FunctionEditor 里输入框那一行，
    //     下面 0.322 起是键盘，所以它一定"在键盘上面、看得全"；
    //   * 列表态：y = ROW_TOP（0.205）—— 它自己就是第一栏。
    // 位移只有 0.013 屏高（233dp 屏上 ≈3dp），**但必须做**：需求明确要求
    // "将输入框强制移动到屏幕顶部能看完全的位置"，而且编辑态下面紧跟着键盘，
    // 留在原处会和键盘贴得太近。用 animateDpAsState 做 200ms 的过渡而不是硬跳。
    //
    // 宽度**不做动画**：编辑态要让出 `缝 + ⌫` 的宽度，而 ⌫ 是**同帧出现**的
    // （见下面那颗 ⌫ 的说明），两者必须同时到位才不会让这一行在动画中间
    // 溢出圆弧（行宽是定死的一整段，子项加起来超了就会顶到表壳外面）。
    //
    // ⚠️ 落点也换了基准：圆屏上是 `sizeDp × 比例`（逐像素不变），非圆屏上是
    // `sy(比例)`（真实屏高）—— 手机上这两个落点分别在 163dp / 174dp，
    // 而按短边算只有 79dp / 84dp（挤在顶部那一格里）。
    val nameTopFraction = if (nameEditing) FIELD_Y else ROW_TOP
    val fieldY by animateDpAsState(
        targetValue = if (isRound) {
            metrics.sizeDp * nameTopFraction
        } else {
            geometry.sy(nameTopFraction)
        },
        animationSpec = tween(durationMillis = NAME_MOVE_MS, easing = FastOutSlowInEasing),
        label = "nameY"
    )

    val fieldHeight = metrics.sizeDp * FIELD_H
    /**
     * **按键宽度基准**（用户那条长期规则："以后你写所有按键都要加上对屏幕宽度的自适应"）。
     *
     * 它 == 参照实现（`FunctionList.kt` 示例按钮那一行）的非圆屏宽度表达式：
     * `geometry.rowWidth(metrics, 1f, isRound)` —— 圆屏 = `metrics.sizeDp`（逐像素不变）、
     * 非圆屏 = "真实屏宽 − 左右各 3%"（手机 386.3dp）。见 `MathKeyboard.keyWidthBase`。
     *
     * 本文件里凡是**自己设宽度**的可点控件都写成 `keyBase × 比例`：名称栏那颗 `⌫`、
     * 以及各栏里靠 `weight` 分宽的胶囊（它们吃的是已经自适应的行宽）。
     *
     * **例外**：顶部那颗 ✓ 与 `ObjectDetailsScreen` 那颗 ✓ 属于 `WatchIconButton` 那一类
     * **形状锁定**的图标键，按 README「硬约定」里那一条写 `0.16 × 短边`（理由写在两处调用点：
     * 形状由 `Common.kt` 锁死 + 用户先前"强制保持在一个形状"的要求）。
     * 高度（如 `fieldHeight`）同样是短边那一档。
     */
    val keyBase = keyWidthBase(metrics, geometry, isRound)
    val delWidth = keyBase * DEL_W_FRACTION
    val fieldGap = keyBase * FIELD_GAP_FRACTION
    /**
     * 这一行整体的可用宽度：
     *   * 列表态 = 名称栏自己那一行的圆弧宽度（和它下面那些栏同一个算法，
     *     所以第一栏看起来就是"列表的第一行"，而不是一条窄一截的东西）；
     *   * 编辑态 = 0.192~0.307 这一段的圆弧宽度（= FunctionEditor 输入框那一行）。
     * 两者都是按各自落点算出来的**常量**，不做动画 —— 见上面那段说明。
     *
     * 圆屏那一支就是上面那句话（公式和常数都没有动），非圆屏由 `bandWidth` 给满宽。
     */
    val rowWidth = if (nameEditing) {
        bandWidth(metrics, geometry, isRound, FIELD_Y, FIELD_Y + FIELD_H, WIDTH_MARGIN)
    } else {
        nameRowWidth(metrics, geometry, isRound)
    }
    /** 编辑态输入框要让出 `缝 + ⌫`；列表态它占满整行。 */
    val fieldWidth = if (nameEditing) rowWidth - fieldGap - delWidth else rowWidth

    Box(Modifier.fillMaxSize()) {

        // ---- 顶部：只有一颗 ✓（按用户要求，这一屏**不写标题**）----
        // 名称栏校验失败时的原因（目前只有滑动条会用到，比如"只能是一个字母"）
        // 放在工具条右侧：这一行本来就是空的，写在这里不占地方、也不会盖住任何
        // 控件（贴在输入框或键盘上都会压到别的元素，那一带已经没有空地了）。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 位置按**真实屏高**（圆屏上 `sy(0.088)` 逐像素等于 `sizeDp × 0.088`）；
                // 宽度圆屏走弧宽、非圆屏铺满屏宽减左右余量（和列表行同一个口径）。
                .offset(y = geometry.sy(TOOLBAR_TOP))
                .width(
                    bandWidth(
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        yTop = TOOLBAR_TOP,
                        yBottom = TOOLBAR_TOP + TOOLBAR_H,
                        arcMargin = WIDTH_MARGIN
                    )
                )
                .height(metrics.sizeDp * TOOLBAR_H),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchIconButton(
                icon = Icons.Filled.Check,
                contentDescription = "完成",
                metrics = metrics,
                modifier = Modifier
                    // 图标键的形状锁在 `Common.kt`（README「硬约定」里那一条例外），
                    // 所以这里写的是"比例 × 短边"，和 `ObjectDetailsScreen` 那颗 ✓
                    // 逐字同款 —— 从列表进详情、再进属性屏，那颗 ✓ 的大小和位置都不会跳。
                    // 它不是"窄柱"：真正自适应的是它所在的那一行（上面走 `bandWidth`）。
                    .width(metrics.sizeDp * 0.16f)
                    .height(metrics.sizeDp * TOOLBAR_H * 0.85f),
                // 填充态 = 强调色：这一屏只有这一个动作，它就是主按钮
                filled = true,
                onClick = onDone
            )
            val warning = nameWarning
            if (warning != null) {
                // 缝和被它隔开的那颗 ✓ 用**同一个基准**（圆屏逐像素不变）
                Spacer(Modifier.width(fieldGap))
                Text(
                    text = warning,
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ---- 名称输入框 + ⌫（同一行：输入框让出 `缝 + ⌫` 的位置）----
        //
        // 输入框和 ⌫ 是**同一行里排出来的**（不是各自绝对定位去凑），所以
        // 两端的留白天然对称、宽度加缝加 ⌫ 恰好等于这一行的可用宽度 ——
        // 不会因为两边各自算偏移而在圆弧上顶出去。
        //
        // ⌫ 是 `if` 直接出现/消失、没有做淡入淡出：它和"输入框让出宽度"必须
        // **同一帧**生效。分开做动画的话，退出那一刻输入框已经长回来、而 ⌫ 还在
        // 慢慢淡出，这一行会短暂地比可用宽度宽出 `缝 + ⌫`（约 40dp），
        // 在圆表上就是"⌫ 的右半边顶到表壳外面"。宁可少一个淡入，也不要这种破绽。
        if (!numberEditing) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = fieldY)
                    .width(rowWidth)
                    .height(fieldHeight),
                horizontalArrangement = Arrangement.spacedBy(fieldGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpressionField(
                    text = name,
                    cursor = nameCursor,
                    hint = nameHint,
                    metrics = metrics,
                    // 编辑态是"这个框正在被输入"（强调色 + 亮光标），列表态是普通深灰
                    focused = nameEditing,
                    onTap = onNameTap,
                    modifier = Modifier.width(fieldWidth).fillMaxHeight(),
                    // 输入框是"框"，用按高度取比例的圆角；⌫ 是"键"，用完整胶囊
                    shape = watchShapeOf(fieldHeight)
                )
                // ---- 固定尾巴（函数名的 `(x)`）----
                // 它**不是**输入框的一部分：没有光标、点了也不会进入编辑，
                // 只是把"这个名字其实是 a(x)"这件事显示出来，同时告诉用户
                // 后面那截不用他管。
                if (nameSuffix.isNotEmpty()) {
                    Text(
                        text = nameSuffix,
                        fontSize = metrics.fontSmall,
                        color = KeyPalette.textMuted,
                        maxLines = 1
                    )
                }
                if (nameEditing) {
                    // 复用数学键盘那颗 ⌫（危险色胶囊、长按 = 清空整段文本），
                    // 和 FunctionEditor 里输入框右边那颗是同一个组件、同一个手感。
                    BackspaceKey(
                        metrics = metrics,
                        onKey = onNameKey,
                        modifier = Modifier.width(delWidth).fillMaxHeight()
                    )
                }
            }
        }

        // ---- 下面这一块：显示哪一种键盘 ----
        when {
            // 名称编辑：字母表键盘（#5）显示在输入框**下面**（需求原话）。
            // 大小写切换键不显示（大小写是强制的）；括号只有函数名才显示。
            nameEditing -> AlphabetKeyboard(
                metrics = metrics,
                letterCase = nameCase,
                // 没有切换键，这个回调永远不会被调用；接上也只是把值存回去，
                // 不会改变"强制大小写"这件事（真正的强制来自上面固定的 nameCase）。
                onCaseChange = {},
                onInsert = { onNameKey(KeyAction.Insert(it)) },
                onClose = onNameTap,
                rotary = rotary,
                modifier = Modifier.fillMaxSize(),
                showCaseToggle = false,
                showBrackets = nameBrackets
            )

            // 数值编辑：数字小键盘 + 贴顶的那一栏
            numberEditing -> {
                val shown = lifted
                if (shown != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            // 贴顶那一栏的落点也按真实屏高（圆屏逐像素不变）
                            .offset(y = geometry.sy(ROW_TOP)),
                        contentAlignment = Alignment.Center
                    ) {
                        shown()
                    }
                }
                NumberPad(
                    metrics = metrics,
                    geometry = geometry,
                    isRound = isRound,
                    onKey = onNumberKey,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 平时：各栏从名称栏下面依次排开
            else -> Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(
                        // 第一栏的上沿 = 名称栏下沿 + 一条缝（真实屏高）
                        y = geometry.sy(ROW_TOP + ROW_H + ROW_GAP)
                    )
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_GAP)
            ) {
                rows()
            }
        }
    }
}

// ===========================================================================
// 各栏的通用件
// ===========================================================================

/**
 * 一栏属性：左标题、右边内容，整栏是一颗深灰胶囊（和设置页的每一行长得一样）。
 *
 * 和 SettingsScreen 的 SettingsRow 是同一套写法（定高、淡入 + 从下方极小的上移、
 * 按下缩一点点、标题占剩余空间把内容顶到右端），只是这里的内容是
 * "值 chip / 滑轨 / 线型图案"三种，所以做成槽位。
 *
 * [warning] = true 时标题换成**危险色的小字**（数字栏不合法时标题放的就是原因）。
 *
 * [titleWeight] = false 时标题**按自己的字宽占位**，剩下的整幅宽度留给内容 ——
 * 给"内容自己就是一整条东西"的栏用（几何对象属性屏里的 12 色条和两颗开关胶囊：
 * 它们要的是**剩余的全部**宽度，而默认那种"标题吃掉剩余空间"的写法会把内容压掉一半，
 * 色块和胶囊上的字就被挤没了）。默认 true = 现在这三屏（函数 / 滑动条 / 对象）
 * 里绝大多数的"左标题 + 右值 chip"的排法，行为一个像素都不变。
 *
 * 入场动作用 graphicsLayer（不参与布局）：这些栏是定高的，一旦内容在入场期间
 * "不占位"，位置就会一栏一栏往下跳。
 */
@Composable
private fun PropertyRow(
    title: String,
    metrics: WatchMetrics,
    width: Dp,
    entered: Boolean,
    index: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    titleWeight: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )
    val press = rememberPressFeedback(scaleAmount = 0.02f, glowAmount = 0f)

    Surface(
        // 统一到 KeyPalette：属性栏就是一颗深灰胶囊（和设置行、列表行同一套色）
        color = KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
            .width(width)
            .height(metrics.sizeDp * ROW_H)
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
                if (onClick != null) {
                    scaleX = press.scale
                    scaleY = press.scale
                }
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = press.source,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_GAP)
        ) {
            Text(
                text = title,
                // 正常是栏目名（fontSmall / 纯白）；不合法时是原因（fontTiny / 砖红）——
                // 原因最长 8 个汉字，退一档字号才能在标题那点地方里完整显示
                fontSize = if (warning) metrics.fontTiny else metrics.fontSmall,
                color = if (warning) KeyPalette.danger else KeyPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 标题占剩余空间，把内容顶到右端；`titleWeight = false` 的栏
                // 反过来把剩余空间让给内容（见 KDoc）。
                modifier = if (titleWeight) Modifier.weight(1f) else Modifier
            )
            content()
        }
    }
}

/**
 * 值的 chip：强调色胶囊 + 纯白小字（和设置页的"当前值"chip 同一个样式）。
 *
 * 为什么不直接用 SettingsScreen 的 ValueChip / Common 的 MiniChip：
 * 那两个都是 private / 不给横向内边距（MiniChip 的文字紧贴胶囊边），
 * 而这两个文件本轮都不许改。所以照同一个配方（PillShape + KeyPalette.accent
 * + fontTiny + CHIP_PAD）在这里重写一个 15 行的件，观感不会漂。
 *
 * [danger] = true 时用砖红：数字栏的缓冲区**没被接受**时就是它
 * （砖红在这套配色里的固定含义是"这一步有问题"，和 ⌫ 同一档）。
 */
@Composable
private fun ValueChip(
    text: String,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    Surface(
        color = if (danger) KeyPalette.danger else KeyPalette.accent,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .height(metrics.sizeDp * CHIP_H)
                .padding(horizontal = metrics.sizeDp * CHIP_PAD),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = metrics.fontTiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 线型图案（需求："点击切换实线和密度不一样的虚线（**以图案表示，不要以文字表示**）"）。
 *
 * 画一小段线，虚线的疏密直接用 [LineStyle.dashOn] / [LineStyle.dashOff] 做
 * [PathEffect.dashPathEffect]。这两个数的单位**就是屏幕像素**（见
 * engine/PlotModel.kt 的 LineStyle 说明），而 Canvas 的 DrawScope 也是像素坐标系，
 * 所以原样传进去即可 —— 图案的疏密和图像界面上真画出来的曲线**完全一致**，
 * 不需要（也不该）再做一次 dp→px 换算。
 *
 * 关键是[实线不能给 dashPathEffect]：`SOLID` 的两个数都是 0，
 * 传进去等于"实线段长 0、空白段长 0"，画出来是一条**什么都没有**的空白线。
 */
@Composable
private fun LineStyleSketch(
    style: LineStyle,
    color: Color,
    strokeWidth: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val y = size.height / 2f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
            pathEffect = if (style.isDashed) {
                PathEffect.dashPathEffect(floatArrayOf(style.dashOn, style.dashOff), 0f)
            } else {
                null
            }
        )
    }
}

/**
 * 线径的滑轨。
 *
 * **有意复制 `ui/SliderRow.kt` 里那个 private 的 `SliderTrack` 的观感**
 * （白 16% 的轨道底 + 强调色的已选段 + 纯白圆滑块；拖动/点一下都跳过去）：
 * 那个文件本轮明确不许改，而它的 `SliderTrack` 又写死了 `Slider` 这个类型
 * （取值域、吸附规则都从 Slider 里读），拿不到这里"0.4~3.0 的浮点倍数"的语义。
 * 所以这里按同一套画法重写一个，坐标换算和吸附规则照旧：
 *   * 宽度用 [BoxWithConstraints] 的约束（拖动要在同一帧里把手指的 x 换算成值，
 *     用 onSizeChanged 会慢一帧，跟手会抖）；
 *   * 值永远走 [snapWidth]，所以界面上显示的值和写进模型的倍数完全一致。
 */
@Composable
private fun WidthTrack(
    value: Float,
    metrics: WatchMetrics,
    rowHeight: Dp,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // pointerInput(Unit) 里的 lambda 只会在第一次组合时捕获一次，
    // 而 onValue 每次都可能是新的闭包（它捕获了当前的 fn）。所以走
    // rememberUpdatedState 拿"最新的那一个"，否则会一直用第一帧的旧 fn。
    val callback by rememberUpdatedState(onValue)

    val trackH = rowHeight * 0.16f
    val thumb = rowHeight * 0.34f

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val widthDp = with(density) { constraints.maxWidth.toDp() }
        val span = (WIDTH_MAX - WIDTH_MIN).toDouble()
        val fraction = ((value - WIDTH_MIN) / span).coerceIn(0.0, 1.0).toFloat()
        // 滑块位置用 dp 算：轨道宽减掉滑块直径，滑到最右端时滑块才完整留在轨道里
        val thumbX = (widthDp - thumb) * fraction

        fun valueAt(x: Float): Float =
            (WIDTH_MIN + (x / widthPx).coerceIn(0f, 1f) * (WIDTH_MAX - WIDTH_MIN))

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
                    .width(thumb)
                    .height(thumb)
                    .clip(CircleShape)
                    .background(KeyPalette.text)
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures { pos -> callback(snapWidth(valueAt(pos.x))) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        callback(snapWidth(valueAt(change.position.x)))
                    }
                }
        )
    }
}

// ===========================================================================
// 数字小键盘（需求 #4 的第三部分）
// ===========================================================================

/** 数字小键盘上的一个键。 */
private sealed interface NumberPadKey {
    /** 数字 / 小数点 / 负号：原样追加到缓冲区。 */
    data class Text(val text: String) : NumberPadKey

    /** 退格：删掉缓冲区最后一个字符。 */
    data object Backspace : NumberPadKey

    /** 清空整个缓冲区（**长按 ⌫**，和数学键盘"长按 ⌫ 清空表达式"同一个语义）。 */
    data object Clear : NumberPadKey
}

/**
 * 小键盘的键位（4 行 × 4 列）：
 * ```
 * 7 8 9 ⌫
 * 4 5 6 −
 * 1 2 3 .
 * ── 0 ──
 * ```
 *
 * **和函数编辑器的 `123` 页是同一套键**，只多了三个数值输入必需的东西、
 * 换掉了那个用不上的变量键（`123` 页第 1 行最后那格是 `x`/`θ`/`t` ——
 * 在下界/上界/步长里毫无意义）：
 *   * `⌫`：没有输入框就只剩它能改错，必须占一格（长按 = 清空）；
 *   * `−`：默认下界就是 −5，打不出负号这一栏就废了；
 *   * `.`：步长 0.1、上界 2.5 这类值都要小数点。
 * 12 格放不下 13 个键，所以 `0` 单独占一整行（权重 4）—— 这也是手机拨号盘的排法。
 *
 * ## 行高是从"没有选择栏"这件事推出来的
 *
 * 需求原话是"就是函数编辑界面里的 123 栏，但是没有数字按键和输入框之间的
 * 选择栏"。少掉的正是选择栏那一条（FunctionEditor 里占 TAB_Y~GRID_TOP 一格的
 * 高度），空出来的地方刚好够第 4 行：
 * ```
 *   rowHeight = (KEY_BOTTOM − KEY_TOP) / 4 − KEY_GAP
 *             = (0.945 − 0.322) / 4 − 0.008 = 0.14775
 * ```
 * 和数学键盘按键的 0.14775 **一模一样**，所以这个小键盘按上去的手感和
 * 函数编辑器里的数字键完全一致（0.14775 × 233dp ≈ 34.4dp 高）。
 */
/**
 * 小键盘的列数（4 列，和最上面那三行一致）。
 *
 * 单独写成常量有两个用处：一是"最后那行 `0` 一颗键占满整行"的权重取它
 * （`weight = 列数`，改列数时两边一起动）；二是 [keyGridMetrics] 要用它算
 * "一个键最宽能有多少"，也就是"按键最多长成 1.25 倍宽"那个上限的分母。
 */
private const val NUMBER_PAD_COLUMNS = 4

private val NUMBER_PAD_ROWS: List<List<NumberPadKey>> = listOf(
    listOf(
        NumberPadKey.Text("7"),
        NumberPadKey.Text("8"),
        NumberPadKey.Text("9"),
        NumberPadKey.Backspace
    ),
    listOf(
        NumberPadKey.Text("4"),
        NumberPadKey.Text("5"),
        NumberPadKey.Text("6"),
        // 标签用排版减号（和数学键盘的 `op("−", "-")` 同一个字形），插进去的是 ASCII 减号
        NumberPadKey.Text("-")
    ),
    listOf(
        NumberPadKey.Text("1"),
        NumberPadKey.Text("2"),
        NumberPadKey.Text("3"),
        NumberPadKey.Text(".")
    ),
    listOf(NumberPadKey.Text("0"))
)

/**
 * 数字小键盘：只在本栏被编辑时出现，占 0.322~0.945（和字母表键盘同一段）。
 *
 * 行高 / 行缝 / 每行宽度全部走**和数学键盘同一套**换算（[keyGridMetrics] /
 * [bandWidth]）：圆屏上逐像素等于原来的
 * `rowHeight = sizeDp × ((KEY_BOTTOM − KEY_TOP)/4 − KEY_GAP_FRACTION)`（34.4dp）、
 * 行宽走弧宽；非圆屏（手机）行宽铺满屏宽减左右余量（386dp）、行高按真实屏高铺满
 * 且被"最多 1.25 倍键宽"夹住。手机上的历史值：四行宽度 357 / 371 / 316 / **190**dp
 * （最后那行只有屏宽的 46%，`0` 那颗键又宽又矮），整块小键盘只落在 y = 132~388dp
 * 里；现在是四行各 386dp、行高 117.7dp，键盘落到 y = 274~763dp。
 */
@Composable
private fun NumberPad(
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    onKey: (NumberPadKey) -> Unit,
    modifier: Modifier = Modifier
) {
    // 数字键只有 1 个字符，行宽按"满宽"那一支算出来即可（圆屏不用这个数）
    val padRowWidth = bandWidth(metrics, geometry, isRound, KEY_TOP, KEY_BOTTOM)
    val grid = keyGridMetrics(
        metrics = metrics,
        geometry = geometry,
        isRound = isRound,
        gridTopFraction = KEY_TOP,
        gridBottomFraction = KEY_BOTTOM,
        gapFraction = KEY_GAP_FRACTION,
        rows = NUMBER_PAD_ROWS.size,
        rowWidth = padRowWidth,
        columns = NUMBER_PAD_COLUMNS
    )
    val gap = grid.gap
    val rowHeight = grid.rowHeight
    // 逐行的**比例**只给圆屏的弧宽用（非圆屏每行一样宽）
    val pitch = (KEY_BOTTOM - KEY_TOP) / NUMBER_PAD_ROWS.size

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 上沿按**真实屏高**（圆屏逐像素不变）
                .offset(y = geometry.sy(KEY_TOP)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            NUMBER_PAD_ROWS.forEachIndexed { index, keys ->
                // 这一行的上下沿：宽度取两者里更窄的那个（圆屏越往下越窄）
                val top = KEY_TOP + index * pitch
                val bottom = top + (pitch - KEY_GAP_FRACTION)
                Row(
                    modifier = Modifier
                        // 圆屏：这一行上下沿里更窄的那处弧宽（原样）；非圆屏：满宽
                        .width(rowSpanWidth(metrics, geometry, isRound, top, bottom))
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    keys.forEach { key ->
                        NumberPadKeyButton(
                            key = key,
                            metrics = metrics,
                            onKey = onKey,
                            modifier = Modifier
                                // 只有 0 那行是一颗键，让它占满整行（权重 = 列数）
                                .weight(if (keys.size == 1) NUMBER_PAD_COLUMNS.toFloat() else 1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 小键盘上的一颗键。样式和数学键盘的按键一致（[KeyPalette] + [PillShape] +
 * `fontSmall`），⌫ 用危险档（砖红），和输入框右边那颗 ⌫ 是同一个含义。
 */
@Composable
private fun NumberPadKeyButton(
    key: NumberPadKey,
    metrics: WatchMetrics,
    onKey: (NumberPadKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val isBackspace = key is NumberPadKey.Backspace
    // 长按 ⌫ = 清空：清空是不可撤销的，先给一次震动反馈
    // （和 MathKeyboard 的 BackspaceKey 完全同一个做法）。
    Surface(
        color = if (isBackspace) KeyPalette.danger else KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier.combinedClickable(
            onClick = { onKey(key) },
            onLongClick = if (isBackspace) {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onKey(NumberPadKey.Clear)
                }
            } else {
                null
            }
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (key) {
                    is NumberPadKey.Text -> if (key.text == "-") "−" else key.text
                    NumberPadKey.Backspace -> "⌫"
                    NumberPadKey.Clear -> "C"
                },
                fontSize = metrics.fontSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ===========================================================================
// 数值栏
// ===========================================================================

/**
 * 滑动条属性界面里的三条数值栏。
 *
 * 做成枚举而不是三个 boolean：它们的"读"和"写回"是对称的
 * （[read] 取当前值、"点一下进编辑"要按同一个名字去找模型字段），
 * 一个枚举把这两件事放在一起，就不会出现"读了 step 却写回 max"这种错位。
 */
private enum class SliderField(val label: String) {
    MIN("下界"),
    MAX("上界"),
    STEP("步长");

    /** 这一栏当前的值。 */
    fun read(slider: Slider): Double = when (this) {
        MIN -> slider.min
        MAX -> slider.max
        STEP -> slider.step
    }
}

/**
 * 数值栏的**内容**（标题 + 值 chip）。
 *
 * 列表态和"贴到顶部的编辑态"共用它 —— 两种状态下这一栏长得一模一样，
 * 只是编辑态的那个 chip 显示的是**缓冲区**、而且可能标红。
 *
 * @param text chip 上的字：列表态是当前值，编辑态是用户正在打的串。
 * @param width 这一栏的宽度。**必须由调用方给**：两种状态落点不同，圆弧宽度也不同
 *   （贴顶时在 0.205 那一行，比列表里任何一栏都靠上、更窄），
 *   在这里自己算就会算成"列表里的宽度"，贴顶时右边会顶出圆弧。
 */
@Composable
private fun NumberRow(
    field: SliderField,
    text: String,
    metrics: WatchMetrics,
    width: Dp,
    warning: String?,
    entered: Boolean,
    index: Int,
    onClick: () -> Unit
) {
    PropertyRow(
        title = warning ?: field.label,
        metrics = metrics,
        width = width,
        entered = entered,
        index = index,
        warning = warning != null,
        onClick = onClick
    ) {
        ValueChip(text = text, metrics = metrics, danger = warning != null)
    }
}

// ===========================================================================
// 版面小工具
// ===========================================================================

/**
 * 名称栏在**列表态**的宽度：它自己占第一行（`ROW_TOP + ROW_H` 这一段）。
 *
 * 和 [listRowWidth] 分开写，因为名称栏不在"名称栏下面那几栏"的序号里：
 * 它的中心高度是 `ROW_TOP + ROW_H / 2`（0.2625），比下面任何一栏都靠上，
 * 所以圆弧宽度也更窄一点 —— 这正是这套设计语言要的"越靠边越窄"。
 *
 * ⚠️ 换成 [bandWidth] 之后，**圆屏上这一句逐像素等于原来的写法**
 * （`min(cwf(c), cwf(c)) * 0.93` = `cwf(c) * 0.93`，同一个 c）；
 * 非圆屏（手机）则由 [ScreenGeometry.rowWidth] 给"铺满屏宽减左右余量" ——
 * 原来这一屏在手机上名称栏是 336dp、下面各栏 313~374dp（都是"短边 × 弧宽比例"），
 * 现在是 386dp；**但手机上这一屏真正的问题在纵向**：整屏内容只落在 0~388dp
 * 那一块里（底下 462dp 全空），见 [PropertyEditorShell] 的 `geometry` 参数。
 */
private fun nameRowWidth(metrics: WatchMetrics, geometry: ScreenGeometry, isRound: Boolean): Dp =
    bandWidth(
        metrics = metrics,
        geometry = geometry,
        isRound = isRound,
        yTop = ROW_TOP + ROW_H * 0.5f,
        yBottom = ROW_TOP + ROW_H * 0.5f,
        arcMargin = WIDTH_MARGIN
    )

/**
 * 列表态里第 [index] 栏的宽度。
 *
 * 名称栏永远占第一行的位置，所以各栏从 `ROW_TOP + ROW_H + ROW_GAP` 往下排，
 * 第 [index] 栏说的是"名称栏下面的第几栏"（0 = 线型 / 下界）。
 *
 * 圆屏在某个高度上的可用宽度按 [WatchMetrics.circleWidthFraction] 收缩，
 * 否则最下面那栏会伸进看不见的圆角里被表壳切掉（和设置页同一个算法）；
 * 非圆屏（手机）铺满屏宽减左右余量（见 `MathKeyboard.bandWidth`）。
 */
private fun listRowWidth(
    index: Int,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean
): Dp {
    val center = ROW_TOP + ROW_H + ROW_GAP + ROW_H * 0.5f + index * (ROW_H + ROW_GAP)
    return bandWidth(
        metrics = metrics,
        geometry = geometry,
        isRound = isRound,
        yTop = center,
        yBottom = center,
        arcMargin = WIDTH_MARGIN
    )
}

/**
 * 给**一整段高度**算可用宽度，取上下边缘里更窄的那一处。
 *
 * 和 `MathKeyboard.bandWidth` / FunctionEditor 里那个 `spanW` 是**同一个算法**
 * （圆屏那一支就是原来那句 `min(cwf(yTop), cwf(yBottom)) × 0.93`，逐像素不变）：
 * 圆的宽度随高度递减，行的上下两端比中心更靠外，只看中心宽度会让控件四角
 * 越过圆弧，在真机上就是"被表壳切掉"。
 * 非圆屏（手机）由 [ScreenGeometry.rowWidth] 给满宽（386dp）—— 圆屏那一支不动。
 * 手机上的历史值：名称栏 336dp、下面各栏 313~374dp（"短边 411dp × 弧宽比例"），
 * 所以**宽度**本来就将就够用，这一轮真正被修好的是纵向（见 [PropertyEditorShell]）。
 */
private fun rowSpanWidth(
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    yTop: Float,
    yBottom: Float
): Dp = bandWidth(
    metrics = metrics,
    geometry = geometry,
    isRound = isRound,
    yTop = yTop,
    yBottom = yBottom,
    arcMargin = WIDTH_MARGIN
)

/** 线径吸附到 0.1 的网格上，并夹进 `[WIDTH_MIN, WIDTH_MAX]`。 */
private fun snapWidth(v: Float): Float {
    if (!v.isFinite()) return 1f
    val steps = round(((v - WIDTH_MIN) / WIDTH_STEP).toDouble()).toFloat()
    return (WIDTH_MIN + steps * WIDTH_STEP).coerceIn(WIDTH_MIN, WIDTH_MAX)
}

/**
 * 数值怎么写进缓冲区：最多 3 位小数，整数不带 `.0`。
 *
 * 不能直接用 `formatSliderValue`（SliderRow.kt 里那个）：它只保留**一位**小数，
 * 而步长这一栏用户完全可能打 0.25 —— 用一个显示不出真实值的格式，
 * 用户会以为"我打的 0.25 被吃掉了一位"。
 */
private fun formatEditable(v: Double): String {
    if (!v.isFinite()) return "0"
    val r = (v * 1000.0).roundToLong() / 1000.0
    return if (abs(r - r.toLong()) < 1e-9) r.toLong().toString() else r.toString()
}

/**
 * 数字缓冲区**允许的形态**（唯一一处定义）。
 *
 * 正则 = 可选负号 + 一串数字 + 可选（一个小数点 + 一串数字），四段都可以是空的。
 * 所以下面这些都算合法：
 * ```
 * ""     还没打
 * "-"    ".-" 刚按下负号 / 小数点
 * "12"   "12."  "12.5"  "-0.25"
 * ```
 * 而这些**不可能是手滑的中间态**，一律挡掉：
 * ```
 * "1.2.3"  "1-2"  "--1"  "1e5"  "abc"
 * ```
 * 用正则而不是"试着 parse 一下看看"：`toDoubleOrNull` 对 `1.`（末尾小数点）这类
 * 写法的容忍度在 Kotlin 各版本上并不一致，万一它判成 null，用户打 `1.5` 时
 * 那个小数点会被吞掉、变成 `15` —— 这种坑不值得赌，所以形状自己写死。
 */
private val NUMBER_BUFFER = Regex("^-?\\d*\\.?\\d*$")

/** 缓冲区在**语法**上是不是还可能成为一个数（见 [NUMBER_BUFFER]）。 */
private fun isNumberSyntaxOk(s: String): Boolean = NUMBER_BUFFER.matches(s)

/**
 * 把缓冲区解析成一个数。返回 null = **还没打完**（`""` / `-` / `.` / `-.`），
 * 这时只改缓冲区、不动模型。
 *
 * 末尾那个小数点单独去掉再解析：它只是个"我马上要打小数"的信号，
 * `12.` 的含义就是 12。
 */
private fun parseNumberBuffer(s: String): Double? {
    if (s.isEmpty() || s == "-" || s == "." || s == "-.") return null
    val core = if (s.endsWith(".")) s.dropLast(1) else s
    return core.toDoubleOrNull()
}
