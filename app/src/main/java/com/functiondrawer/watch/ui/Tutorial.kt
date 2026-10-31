// 手表 UI 大量用到实验性接口，文件级统一 OptIn（和同目录其它文件一致）。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.keyboard.KeyPage
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 新手教程（Tutorial）
//
// 这一套东西分成四块：
//   1. [TutorialAnchors]：界面各处把"某个控件在屏幕上的矩形"报到一张表里，
//      教程浮层据此画出高亮圈（**不靠 id 猜位置**，界面改版面教程自动跟上）；
//   2. [Tutorial]：38 步的**状态机**。推进只有两条路 —— 用户点到了某个锚点控件
//      （界面里上报 signal），或者路由变了（onRoute 的规则表）；
//   3. [TutorialOverlay]：纯浮层。**没有碰撞体积**：除了卡片里的三颗胶囊按钮，
//      卡片本身不拦触摸；高亮圈之外铺四块"挡板"把散落的触摸吃掉；
//   4. 各屏的接线（`tutorialAnchor(...)` + `Tutorial.report(...)`）。
//
// ⚠️ 这一整套**不碰** `engine/`：它只读 `Route` / `PlotFunction` 这些已经在用的类型。
// ---------------------------------------------------------------------------

/**
 * 一步教程要求用户做的**动手手势**。浮层据此画一个小小的手势提示图标。
 */
internal enum class TutorialGesture {
    TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_RIGHT, SWIPE_LEFT, NONE
}

/**
 * 卡片相对**高亮圈**的位置。
 *
 * 之所以要这个枚举：圆表一共就这么大，卡片放到哪都可能盖住被高亮的按键 /
 * 坐标轴上的数字。每一步在 [Tutorial.steps] 里点名一个位置，浮层再兜一层
 * "整块卡片必须留在屏内"的夹取。
 */
internal enum class TutorialCardSpot { TOP, BOTTOM, CENTER, BELOW, ABOVE }

/**
 * 一步教程的全部内容。
 *
 * @param title 标题。**必须单行、不写逗号**（圆表上一行放不下）。
 * @param body 正文，1~2 句，尽量短。
 * @param gesture 要求用户做的手势（只影响提示图标）。
 * @param spot 卡片相对高亮圈的位置；null = 让浮层按锚点自己挑。
 * @param anchor [TutorialAnchors] 里的锚点 id；null = 没有具体锚点（整屏手势）。
 * @param action 软件**自动编排**的动作名（reveal / coordinates / toast / tools_scroll）；
 *               空 = 这一步等用户操作或路由推进。
 * @param signal 推进信号名。`Tutorial.report(signal)` 命中才会 `advance()`；
 *               空串 = 这一步不靠 signal（靠路由或自动动作）。
 * @param autoFillName 保存对话框里要自动填进去的文件名（第 24 步用 "test"）。
 */
internal data class TutorialStep(
    val title: String,
    val body: String,
    val gesture: TutorialGesture = TutorialGesture.NONE,
    val spot: TutorialCardSpot? = null,
    val anchor: String? = null,
    val action: String? = null,
    val signal: String = "",
    val autoFillName: String? = null,
    /**
     * 这一步要求**长按**某颗键时，点一下它必须什么都不做（1.3.11）。
     *
     * 用户原话："新手教程长按 sin 这一步应该不允许用户点击 sin 输入 sin()"。
     * 之前点一下会真的把 `sin(` 插进式子，用户还得先退格清掉才能继续。
     */
    val tapBlockedKey: String? = null
)

/**
 * 界面上所有"教程可能要指的地方"的矩形登记表。
 *
 * 为什么用一张表而不是每步各写一套 `offset`：版面是按屏占比算出来的，
 * 各屏、圆屏 / 非圆屏还不一样；写死坐标迟早和真实控件对不上。
 * 这里由控件自己在 `onGloballyPositioned` 时报一次，浮层只负责读。
 */
internal object TutorialAnchors {
    /** 键 = 锚点 id，值 = 控件在**根节点**坐标系里的矩形（px）。 */
    val rects = mutableStateMapOf<String, Rect>()

    const val ADD = "add"
    const val GEAR = "gear"
    const val TOOLBAR = "toolbar"
    /** 图像界面底部那条「拉抽屉的提示条」——长按 2 秒打开截图界面（1.3.11）。 */
    const val HINT_BAR = "hint_bar"
    const val CAPTURE_CANCEL = "capture_cancel"
    const val CAPTURE_CONFIRM = "capture_confirm"
    const val TAB_ELEMENTARY = "tab_elementary"
    const val TAB_DIGITS = "tab_digits"
    const val KEY_SIN = "key_sin"
    const val VARIANT_SINH = "variant_sinh"
    const val KEY_X = "key_x"
    const val DONE = "done"
    const val COLOR_PALETTE = "color_palette"
    const val SETTING_NUMBERS = "setting_numbers"
    const val SETTING_THEME = "setting_theme"
    const val DIALOG_CONFIRM = "dialog_confirm"
    const val TOAST = "toast"
    const val TOOLS_FILE = "tools_file"
    const val SAVES_ROW_TEST = "saves_row_test"
    const val SAVES_DELETE = "saves_delete"
    const val SAVES_BACK = "saves_back"
    const val UNDO_BUTTON = "undo_button"
    const val BACK = "back"

    /** 「函数行上的色点」——每一条函数一个（id 带函数 id）。 */
    fun curveDot(id: Long): String = "curve_dot_$id"

    /** 「函数行本体」——左滑删除时高亮整行。 */
    fun row(id: Long): String = "row_$id"

    /** 「文件列表里的一行」——按不带扩展名的文件名区分（第 29~31 步的 test）。 */
    fun savesRow(baseName: String): String = "saves_row_$baseName"

    fun report(id: String, rect: Rect) {
        rects[id] = rect
    }

    fun get(id: String?): Rect? = if (id == null) null else rects[id]

    fun clear() {
        rects.clear()
    }
}

/**
 * 把一个控件登记成教程锚点。
 *
 * 只加一层 `onGloballyPositioned`（**不参与布局、不参与命中**），所以往任何控件上
 * 挂都不会改变它的大小 / 位置 / 点击行为。
 */
internal fun Modifier.tutorialAnchor(id: String?): Modifier =
    if (id == null) {
        this
    } else {
        this.onGloballyPositioned { TutorialAnchors.report(id, it.boundsInRoot()) }
    }

/**
 * 教程状态机。
 *
 * 一句话说明它的边界：**它不碰界面状态**（不改路由、不删文件、不开关色盘）。
 * 所有"真的去做点什么"都通过下面那几个 `onXxx` 钩子交给 `GraphScreen` / 各屏，
 * 这样教程只是"编排 + 指路"，业务逻辑仍然只有一份。
 */
internal object Tutorial {
    /** 教程是否正在跑（浮层、删除暂存、禁滚动都以它为准）。 */
    var active by mutableStateOf(false)
        private set

    /** 当前第几步（0 起）。 */
    var step by mutableStateOf(0)
        private set

    /** 每换一步就 +1：`LaunchedEffect(step, revision)` 靠它重启动画编排。 */
    var revision by mutableStateOf(0)
        private set

    /** 开始教程时所在的路由。用户乱跑（[fail]）时退回这里。 */
    var entryRoute: Route? = null

    /** 上一次观察到的路由（`onRoute` 里更新，用来判"上一屏是什么"）。 */
    var lastRoute: Route? = null

    /** 当前这一步是否要压暗幕。自动编排（reveal / coordinates / tools_scroll）期间为 true。 */
    var spotlight by mutableStateOf(false)

    /** 曲线"被画出来"这一步是否已经播完（浮层据此收暗幕 → 推进）。 */
    var revealDone by mutableStateOf(false)

    /** 是否正在等曲线绘制动画播完（`GraphRoute` 用）。 */
    var holdReveal by mutableStateOf(false)

    /**
     * 第 8 步的"先藏起来"开关（1.2.53）。
     *
     * 用户要求的顺序：**先减弱阴影 → 再播绘制动画 → 再提高阴影**，
     * 而动画开始之前曲线上不能已经有东西 —— 教程新建的这条函数在点 ✓ 回到
     * 图像界面时本来会按正常逻辑立刻画出来，这一步是**教程特例**：
     * 进入第 8 步先把曲线藏起来（`GraphRoute` 看到它为 true 就把揭示进度钉在 0），
     * 等暗幕降完、`replayReveal` 才放行，于是"减弱阴影"这段里屏幕上没有曲线。
     */
    var revealArmed by mutableStateOf(false)

    /** 是否正在收尾（最后一步点了「确定」）。 */
    var finishing by mutableStateOf(false)

    /** 「曲线画完了」的版本号：`GraphRoute` 播完动画时 +1。 */
    var revealVersion by mutableStateOf(0)
        private set

    /** 第 27 步（自动滚工具列表）期间禁止用户自己滑列表。 */
    var blocksScroll by mutableStateOf(false)

    // ---- 交给界面的钩子 ----
    /** 撤销"上一步"的实际操作（参数是**被撤销的那一步**的步号）。 */
    var onUndoStep: ((Int) -> Unit)? = null

    /** 教程结束（true = 走完全程，false = 中途退出）。 */
    var onStop: ((Boolean) -> Unit)? = null

    /** 把路由退回 [entryRoute]（乱跑兜底）。 */
    var onFailRestore: (() -> Unit)? = null

    /** 关掉函数列表的调色板浮层。 */
    var closeColorPanel: (() -> Unit)? = null

    /** 收起工具条上的撤销菜单（撤销第 15 步"划出菜单"用）。 */
    var closeUndoMenu: (() -> Unit)? = null

    /** 把编辑器的键盘切到某一页（撤销第 5 步用）。 */
    var setEditorPage: ((KeyPage) -> Unit)? = null

    /** 恢复文件列表的选中状态（撤销第 27/28 步用）。 */
    var onRestoreSavesSelection: ((SavesRestore) -> Unit)? = null

    /** 把教程期间"名义删除"掉的文件放回来。 */
    var onRestoreDeletedFile: (() -> Unit)? = null

    /**
     * 第 27 步的自动滚动。
     *
     * `LazyListState` 只有功能界面自己拿得到，所以由它登记这段动作，
     * 压暗幕 / 挡手指 / 播完推进仍归状态机（GraphScreen 的编排）管。
     */
    var scrollTools: (suspend () -> Unit)? = null

    /**
     * 教程第 0 步 `+` 新建出来的那条函数的 id。
     *
     * 色点 / 函数行那几步的锚点里带着**函数的 id**，而 id 要到用户真的按下 `+`
     * 之后才知道 —— 所以步骤表里先用 0 占位，由 [currentAnchor] 换成真的 id。
     */
    var tutorialFnId by mutableStateOf(0L)

    /**
     * 第 8 步重播"曲线被画出来"的动画。
     *
     * 揭示动画由 `GraphRoute` 的 `revealIds` 驱动，只有它拿得到那个状态；
     * 所以到教程里由它登记"重播一次"，编排仍然归 GraphScreen。
     */
    var replayReveal: (() -> Unit)? = null

    /** 文件列表选中状态要恢复成什么样。 */
    enum class SavesRestore { Clear, SelectTest }

    /**
     * 暗幕降下 / 升起的时长（ms）。
     *
     * 第 8 步要等暗幕**彻底降完**才播曲线，所以那一步给足 1460ms；
     * 其余步骤 460ms。两个方向（降 / 升）同长 —— 用户要求"升起来也别太快"。
     */
    val dimDownMs: Int
        get() = if (currentSpec?.action == "reveal") 1460 else 460

    val currentSpec: TutorialStep?
        get() = steps.getOrNull(step)

    /** 当前这一步真正要圈的锚点 id（把步骤表里 0 占位的色点 / 行换成真 id）。 */
    val currentAnchor: String?
        get() {
            val anchor = currentSpec?.anchor ?: return null
            return when (anchor) {
                TutorialAnchors.curveDot(0L) -> TutorialAnchors.curveDot(tutorialFnId)
                TutorialAnchors.row(0L) -> TutorialAnchors.row(tutorialFnId)
                else -> anchor
            }
        }

    /** 第 24 步要自动填进保存对话框的文件名。 */
    fun autoFillName(): String? = currentSpec?.autoFillName

    // ---- 38 步 ----
    val steps: List<TutorialStep> = listOf(
        TutorialStep(
            "从一条曲线开始",
            "点右下角的 + 新建一条函数。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.ADD
        ),
        TutorialStep(
            "先打开初等",
            "点顶部的「初等」页签。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.TAB_ELEMENTARY,
            signal = "page_elementary"
        ),
        TutorialStep(
            "同一个键藏着更多",
            "按住 sin 别松手。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.KEY_SIN,
            signal = "longpress_sin",
            // 用户原话："新手教程长按 sin 这一步应该不允许用户点击 sin 输入 sin()"。
            tapBlockedKey = "sin"
        ),
        TutorialStep(
            "认识双曲正弦",
            "在展开的选项里点 sinh。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.VARIANT_SINH,
            signal = "pick_sinh"
        ),
        TutorialStep(
            "把 sinh 写进式子",
            "现在点一下 sin 键。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.KEY_SIN,
            signal = "key_sinh"
        ),
        TutorialStep(
            "切到数字页",
            "点「123」页签找自变量。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.TAB_DIGITS,
            signal = "page_digits"
        ),
        TutorialStep(
            "告诉它自变量",
            "点 x 键把它插进去。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.KEY_X,
            signal = "key_x"
        ),
        TutorialStep(
            "收笔看图",
            "点右上角 ✓ 完成这条函数。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.DONE
        ),
        TutorialStep(
            "看 它被画出来了",
            "曲线会按真实形状一点点画出来。",
            TutorialGesture.NONE,
            spot = TutorialCardSpot.BOTTOM,
            action = "reveal"
        ),
        // ---- 1.3.11 新增的两步：截图界面（用户点名插在"画完"与"拉抽屉"之间）----
        TutorialStep(
            "长按提示条截图",
            "按住底部那条横杠 2 秒 就能打开截图界面。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.HINT_BAR,
            signal = "capture_opened"
        ),
        TutorialStep(
            "划到底取消截图",
            "把界面划到最底下 点左边的 × 就能取消这次截图。",
            TutorialGesture.SWIPE_UP,
            spot = TutorialCardSpot.CENTER,
            signal = "capture_cancelled"
        ),
        TutorialStep(
            "抽屉拉上来",
            "从屏幕下沿往上滑 打开函数列表。",
            TutorialGesture.SWIPE_UP,
            spot = TutorialCardSpot.CENTER
        ),
        TutorialStep(
            "点一下色点藏起来",
            "点函数左边的色点把它藏起来。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.curveDot(0L),
            signal = "hidden"
        ),
        TutorialStep(
            "再点一下叫回来",
            "再点一次同一个色点。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.curveDot(0L),
            signal = "shown"
        ),
        TutorialStep(
            "长按色点换件衣服",
            "按住色点别松手 等调色板出来。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.curveDot(0L),
            signal = "palette_opened"
        ),
        TutorialStep(
            "挑一个喜欢的颜色",
            "在调色板里点一个颜色。",
            TutorialGesture.TAP,
            spot = TutorialCardSpot.ABOVE,
            anchor = TutorialAnchors.COLOR_PALETTE,
            signal = "color_picked"
        ),
        TutorialStep(
            "往左划删掉它",
            "按住这一行往左划到底。",
            TutorialGesture.SWIPE_LEFT,
            anchor = TutorialAnchors.row(0L),
            signal = "row_deleted"
        ),
        TutorialStep(
            "两颗键之间横划",
            "从设置、加号、文件、删除四颗键里任意一颗划向另一颗 都能唤出撤销菜单。",
            TutorialGesture.SWIPE_RIGHT,
            anchor = TutorialAnchors.TOOLBAR,
            signal = "undo_menu"
        ),
        TutorialStep(
            "点撤销找回来",
            "点左边那颗撤销箭头。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.UNDO_BUTTON,
            signal = "undo_done"
        ),
        // 撤销之后、点设置之前插的一步：撤销键还开着，用同一套"两颗键之间横划"
        // 手势把它收起来（划到另一颗键就是切换菜单，见 `rememberToolbarUndoMenu`）。
        // 锚点取 TOOLBAR —— 它是"⚙ 与 + 两颗键 + 中间的撤销菜单"合起来的矩形，
        // 比单指某一颗键更贴合"从 + 划向齿轮"这条轨迹。
        TutorialStep(
            "隐藏撤销",
            "从 + 划向齿轮 撤销键就收起来了。",
            TutorialGesture.SWIPE_LEFT,
            spot = TutorialCardSpot.BOTTOM,
            anchor = TutorialAnchors.TOOLBAR,
            signal = "undo_menu_closed"
        ),
        TutorialStep(
            "短按齿轮看设置",
            "点左上角的齿轮。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.GEAR
        ),
        TutorialStep(
            "让坐标轴上长数字",
            "把「坐标数字」打开。",
            TutorialGesture.TAP,
            spot = TutorialCardSpot.ABOVE,
            anchor = TutorialAnchors.SETTING_NUMBERS,
            signal = "numbers_on"
        ),
        TutorialStep(
            "设置完成退出",
            "点左上角返回 回到函数列表。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.BACK
        ),
        TutorialStep(
            "坐标轴上现在有数字了",
            "坐标轴旁边已经标上了数字。",
            TutorialGesture.NONE,
            spot = TutorialCardSpot.BOTTOM,
            action = "coordinates"
        ),
        TutorialStep(
            "再拉一次抽屉",
            "从屏幕下沿往上滑 把抽屉再拉上来。",
            TutorialGesture.SWIPE_UP,
            spot = TutorialCardSpot.CENTER
        ),
        TutorialStep(
            "长按加号存个档",
            "按住 + 键 转满一圈就把当前函数存成文件。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.ADD,
            signal = "save_dialog"
        ),
        TutorialStep(
            "敲下勾存成 test",
            "文件名已经填好 直接点 ✓。",
            TutorialGesture.TAP,
            spot = TutorialCardSpot.ABOVE,
            anchor = TutorialAnchors.DIALOG_CONFIRM,
            signal = "saved",
            autoFillName = "test"
        ),
        TutorialStep(
            "存好了",
            "文件已经存进函数文件夹。",
            TutorialGesture.NONE,
            spot = TutorialCardSpot.ABOVE,
            anchor = TutorialAnchors.TOAST,
            action = "toast"
        ),
        TutorialStep(
            "去功能界面转转",
            "从左边缘往右滑 进功能界面。",
            TutorialGesture.SWIPE_RIGHT,
            spot = TutorialCardSpot.BOTTOM
        ),
        TutorialStep(
            "看看这里的全部工具",
            "这里的工具会自己上下滚一遍给你看。",
            TutorialGesture.NONE,
            spot = TutorialCardSpot.TOP,
            action = "tools_scroll"
        ),
        TutorialStep(
            "打开函数文件",
            "点左上角的文件夹。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.TOOLS_FILE
        ),
        TutorialStep(
            "选中刚存的 test",
            "点一下 test 那一行。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.SAVES_ROW_TEST,
            signal = "saves_selected"
        ),
        TutorialStep(
            "再点一下取消选中",
            "再点一次同一行 选中就取消了。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.SAVES_ROW_TEST,
            signal = "saves_deselected"
        ),
        TutorialStep(
            "长按 test 进多选",
            "按住 test 那一行 进入多选。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.SAVES_ROW_TEST,
            signal = "saves_multi"
        ),
        TutorialStep(
            "长按垃圾桶删掉它",
            "按住右上角的垃圾桶 转满一圈。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.SAVES_DELETE,
            signal = "saves_deleted"
        ),
        TutorialStep(
            "退出文件列表",
            "点左上角返回 回到功能界面。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.SAVES_BACK
        ),
        TutorialStep(
            "回到函数列表",
            "从右边缘往左滑 回函数列表。",
            TutorialGesture.SWIPE_LEFT,
            spot = TutorialCardSpot.BOTTOM
        ),
        TutorialStep(
            "长按齿轮进更多设置",
            "按住齿轮别松手。",
            TutorialGesture.LONG_PRESS,
            anchor = TutorialAnchors.GEAR
        ),
        TutorialStep(
            "打开主题设置",
            "点「主题设置」那一行。",
            TutorialGesture.TAP,
            anchor = TutorialAnchors.SETTING_THEME
        ),
        TutorialStep(
            "恭喜你出师了",
            "你已经会画曲线、存文件、改设置了。想再看一遍 可以长按设置键往下滑 找到重温新手教程。",
            TutorialGesture.NONE,
            spot = TutorialCardSpot.CENTER
        )
    )

    /** 开始（或重新开始）教程。 */
    fun start(from: Route? = null) {
        entryRoute = from ?: lastRoute ?: Route.List
        step = 0
        revision++
        active = true
        spotlight = false
        revealDone = false
        holdReveal = false
        revealArmed = false
        finishing = false
        blocksScroll = false
        // ⚠️ 这里**不要**清空锚点表：锚点由控件在 `onGloballyPositioned` 里上报，
        // 而清空之后这些控件并不会重新布局，也就不会再报一次 ——
        // 第一步的高亮圈会直接消失（真机复现过）。表里的旧值由新一帧覆盖。
    }

    /** 中途退出。 */
    fun stop() {
        endTutorial(completed = false)
    }

    /** 走完全程（最后一步的「确定」）。 */
    fun finish() {
        finishing = true
        endTutorial(completed = true)
    }

    private fun endTutorial(completed: Boolean) {
        if (!active) return
        active = false
        spotlight = false
        blocksScroll = false
        revealDone = false
        holdReveal = false
        revealArmed = false
        onStop?.invoke(completed)
        onStop = null
        finishing = false
    }

    /** 前进一步。已经在最后一步就收尾。 */
    fun advance() {
        if (!active) return
        if (step >= steps.lastIndex) {
            finish()
            return
        }
        resetStepVisuals()
        step++
        revision++
        // 进入"看它被画出来了"这一步：立刻把曲线藏起来（见 [revealArmed]）。
        // 在这里赋值而不是等 `GraphScreen` 的编排协程 —— 编排在重组之后才跑，
        // 那时图像界面已经先按正常逻辑把新函数画出来了，正好是用户看到的那一帧。
        if (currentSpec?.action == "reveal") revealArmed = true
    }

    /** 退回上一步，并让界面把上一步的**实际操作**撤掉。 */
    fun back() {
        if (!active || step <= 0) return
        val undone = step - 1
        // 步骤表插过两次：先在"撤销"与"设置"之间插了 1 步（"隐藏撤销"），
        // 1.3.11 又在第 9 位插了 2 步（截图界面的两步）。所以当前步号比
        // [Tutorial.onUndoStep] 那张旧表（写在 GraphScreen 里，按插入前的步号）
        // 大 2 或 3 —— 交回去之前先按位置减掉，否则"上一步"会撤错一步。
        val oldUndone = when {
            // 截图界面的两步（新步号 9 / 10）：退回时一律回到图像界面
            // （还在截图界面就关掉它、抽屉拉开了就收起抽屉）。-1 是给
            // GraphScreen 那张表约定的"回图像界面"哨兵值（见那边的说明）。
            undone == 9 || undone == 10 -> -1
            undone >= SHIFT_TWICE_FROM -> undone - 3
            undone >= SHIFT_ONCE_FROM -> undone - 2
            else -> undone
        }
        onUndoStep?.invoke(oldUndone)
        resetStepVisuals()
        step--
        revision++
    }

    /**
     * 从这一步起，步号比 [Tutorial.onUndoStep] 的旧表大 **2**（1.3.11 插入的两步）。
     */
    private const val SHIFT_ONCE_FROM = 11

    /**
     * 从这一步起，步号比旧表大 **3**（再叠上更早插入的"隐藏撤销"那一步）。
     *
     * 只用于 [back] 里那次换算；以后若再往中间插步骤，这里和那张表要一起改。
     */
    private const val SHIFT_TWICE_FROM = 19

    private fun resetStepVisuals() {
        spotlight = false
        revealDone = false
        holdReveal = false
        revealArmed = false
        blocksScroll = false
    }

    /** 当前这一步的 `signal` 命中才推进。 */
    fun report(signal: String) {
        if (!active) return
        if (signal.isEmpty()) return
        if (currentSpec?.signal == signal) advance()
    }

    // ---- 各屏的便捷入口 ----

    /** 键盘切页（页签名映射到 signal 名）。 */
    fun onPageSelected(page: KeyPage) {
        report(
            when (page) {
                KeyPage.DIGITS -> "page_digits"
                KeyPage.ELEMENTARY -> "page_elementary"
                KeyPage.LETTERS -> "page_letters"
                KeyPage.OTHER -> "page_other"
            }
        )
    }

    /** 长按了一颗键（`sin` → "longpress_sin"）。 */
    fun onLongPress(label: String) {
        report("longpress_" + label.lowercase())
    }

    /** 按下了一颗键（`sinh` → "key_sinh"）。 */
    fun onKeyPressed(label: String) {
        report("key_" + label.lowercase())
    }

    /**
     * 这一步是否**禁止点击**某颗键（1.3.11）。
     *
     * 用户原话："新手教程长按 sin 这一步应该不允许用户点击 sin 输入 sin()"。
     * 只对点名了 [TutorialStep.tapBlockedKey] 的那一步生效 —— 后面"现在点一下 sin 键"
     * 那一步照样能点。
     */
    fun blocksKeyTap(label: String): Boolean =
        active && currentSpec?.tapBlockedKey?.equals(label, ignoreCase = true) == true

    /** 在长按气泡里挑了一个变体（`sinh` → "pick_sinh"）。 */
    fun onVariantPicked(label: String) {
        report("pick_" + label.lowercase())
    }

    /** 编辑器点了右上角 ✓。 */
    fun onConfirm() {
        report("confirm")
    }

    /**
     * 这一次长按要不要**吃掉**（不许走原行为）。
     *
     * 目前一律放行 —— 教程里每一次长按都是用户真的需要按下去的
     * （选变体 / 调色板 / 保存 / 多选 / 删除 / 更多设置）。
     * 留这个入口是为了让"某一步禁止长按"以后能一处改掉，而不是散在键盘里。
     */
    fun suppressLongPress(): Boolean = false

    /** 把"画完了"这件事记下来（`GraphRoute` 在动画播完时调）。 */
    fun onRevealFinished() {
        revealVersion++
    }

    /**
     * 路由变了。
     *
     * 规则表只列"必须靠路由推进"的那几步，其余全部靠 signal；
     * 用户自己乱跑（去了一个本步不该去的主路由）就 [fail]：路由退回起点，
     * 本步的视觉状态复位，但**步号不变**。
     */
    fun onRoute(route: Route) {
        val prev = lastRoute
        lastRoute = route
        if (!active) return

        val advanced = when (step) {
            // 0：+ 键 → 进编辑器
            0 -> route is Route.Editor
            // 7：编辑器 ✓ → 回到图像界面
            7 -> route is Route.Graph && prev is Route.Editor
            // 11：抽屉拉上来
            11 -> route is Route.List
            // 20：短按齿轮 → 设置（第 19 步"隐藏撤销"靠 signal 推进）
            20 -> route is Route.Settings
            // 22：设置返回 → 列表（上一屏必须是设置）
            22 -> route is Route.List && prev is Route.Settings
            // 24：再把抽屉拉上来（第 23 步由教程自己把抽屉收回去看坐标数字，
            //     所以这里认的是"抽屉又展开了"）
            24 -> route is Route.List
            // 28：左滑 → 功能界面
            28 -> route is Route.Tools
            // 30：打开函数文件
            30 -> route is Route.Saves
            // 35：文件列表返回 → 功能界面
            35 -> route is Route.Tools
            // 36：右滑 → 函数列表
            36 -> route is Route.List
            // 37：长按齿轮 → 更多设置
            37 -> route is Route.MoreSettings
            // 38：主题设置
            38 -> route is Route.ThemeSettings
            else -> false
        }
        if (advanced) {
            advance()
            return
        }
        if (step != steps.lastIndex && !allowedRoute(step, route)) fail()
    }

    /** 本步允许停留的主路由（用于判"乱跑"）。 */
    private fun allowedRoute(step: Int, route: Route): Boolean = when (step) {
        0 -> route is Route.List || route is Route.Editor
        in 1..6 -> route is Route.Editor
        7 -> route is Route.Editor || route is Route.Graph
        8 -> route is Route.Graph
        // 9、10（1.3.11 新增）：长按提示条打开截图界面 → 在截图界面里划到底点 ×。
        // 1.3.17 起截图界面改回**整屏路由**（`Route.Capture`），这两步必须放行它 ——
        // 否则 `report("capture_opened")` 一推进就被判"乱跑"，教程直接失败。
        9, 10 -> route is Route.Graph || route is Route.Capture
        // 11：刚取消完截图还站在图像界面上（等用户上滑），之后才要求列表。
        11 -> route is Route.List || route is Route.Graph
        in 12..18 -> route is Route.List
        // 19：插进来的"隐藏撤销"仍在函数列表上（用户提前点齿轮会被 fail 退回）
        19 -> route is Route.List
        20, 21 -> route is Route.Settings
        in 22..23 -> route is Route.List || route is Route.Graph
        24 -> route is Route.Graph
        in 25..27 -> route is Route.List
        in 28..29 -> route is Route.Tools
        in 30..34 -> route is Route.Saves
        35 -> route is Route.Tools
        36 -> route is Route.Tools || route is Route.List
        37 -> route is Route.MoreSettings
        else -> true
    }

    /** 用户跑到了别的**主路由**上：退回起点，本步视觉复位（步号不动）。 */
    fun fail() {
        if (!active) return
        resetStepVisuals()
        onFailRestore?.invoke()
    }
}

// ---------------------------------------------------------------------------
// 浮层
// ---------------------------------------------------------------------------

/**
 * 教程浮层。
 *
 * 结构（从下往上）：
 *   压暗幕 → 四块挡板 → 高亮圈 / 手势提示（**不接触摸**）→ 卡片（只有三颗胶囊按钮接触摸）
 *   → 退出确认框。
 *
 * ⚠️ 卡片**不能**用 Material `Surface`：它自带 `pointerInput`，会拦掉整块卡片的触摸，
 * 用户点"卡片下面那颗键"就点不到了。所以这里全部是普通 `Box` + `clip` + `background` + `border`。
 */
@Composable
internal fun TutorialOverlay(metrics: WatchMetrics, geometry: ScreenGeometry) {
    if (!Tutorial.active) return
    val spec = Tutorial.currentSpec ?: return

    val density = LocalDensity.current
    val screenW = with(density) { geometry.screenW.toPx() }
    val screenH = with(density) { geometry.screenH.toPx() }
    val anchor = TutorialAnchors.get(Tutorial.currentAnchor)
    var exitConfirm by remember { mutableStateOf(false) }
    // 本轮：教程的四套外观（Material / 毛玻璃 × 白天 / 夜间），见 [rememberTutorialStyle]
    val style = rememberTutorialStyle()

    // 压暗幕：两个方向同长（见 Tutorial.dimDownMs）
    //
    // 2026-10-27：用户报"新手教程的界面阴影蒙版没了"。
    // 旧写法只在 `Tutorial.spotlight`（自动编排那几步）时才压 0.5，
    // 其余步骤是 0 —— 于是教程期间背景和平时一样亮，看起来"没有蒙版"。
    // 现在**每一步都压一层**：普通步骤 style.scrim、自动编排步骤 0.5。
    //
    // 绘制那一步（action = "reveal"）单独走三段：先变弱（0.10）→ 画布播揭示动画
    // → 再变强（style.scrim）。动画编排在 GraphScreen：进这一步先等 dimDownMs
    // 落定，再播 TUTORIAL_REVEAL_MS 的曲线，再等 600ms 收工；所以这里把
    // "播完"换算成 dimDownMs + 揭示时长 + 余量，到点再抬回正常浓度。
    val isRevealStep = spec.action == "reveal"
    // 「坐标轴上现在有数字了」那一步（用户 1.2.68）：
    // "阴影应该渐弱再渐强，现在是渐强再渐弱。" —— 旧写法是这一步入场就把蒙版压到
    // 0.5（渐强），停 2 秒后回到 scrim（渐弱）。现在反过来：先进"弱"档（0.10），
    // 让用户先看清坐标轴上的数字，再压回 0.5 把注意力收回到卡片。
    val isCoordinatesStep = spec.action == "coordinates"
    var revealDimRestored by remember(spec) { mutableStateOf(false) }
    var coordsDimStrong by remember(spec) { mutableStateOf(false) }
    LaunchedEffect(spec) {
        revealDimRestored = false
        coordsDimStrong = false
        if (isRevealStep) {
            delay(Tutorial.dimDownMs.toLong() + TUTORIAL_REVEAL_MS + 120L)
            revealDimRestored = true
        }
        if (isCoordinatesStep) {
            delay(TUTORIAL_COORDS_WEAK_MS)
            coordsDimStrong = true
        }
    }
    // ⚠️ `dim` 故意保持成 State、不在组合里读：读了就是每帧一次整块浮层重组。
    // 它的值只在下面 Canvas 的 draw lambda 里取（项目铁律：动画值只在 lambda 里读）。
    val dim = animateFloatAsState(
        targetValue = when {
            isCoordinatesStep -> if (coordsDimStrong) 0.5f else 0.10f
            isRevealStep -> if (revealDimRestored) style.scrim else 0.10f
            Tutorial.spotlight -> 0.5f
            else -> style.scrim
        },
        animationSpec = tween(durationMillis = Tutorial.dimDownMs, easing = FastOutSlowInEasing),
        label = "tutorialDim"
    )

    val cardSpot = resolveSpot(spec, anchor, screenW, screenH, metrics)
    val ring = anchor?.let {
        val pad = metrics.sizePx * 0.016f
        Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad)
    }

    // ---- 本轮的「阴影（蒙版）形状也要动」----
    //
    // 用户原话（两条）：
    //   1. "新手教程里提示圈的位置没有去除阴影" —— 蒙版是一整块均匀的黑，
    //      高亮的那个控件也被自己盖住，看着就是"它底下压着一层影"；
    //   2. "新手教程的阴影改变动画没做" —— 换一步时蒙版是**啪**一下换新的。
    //
    // 现在蒙版改成"挖了一个洞"的暗幕：洞里完全不压暗（所以高亮控件是干净的），
    // 洞的位置/大小随着锚点变化**用四条边各自补间**滑过去（见下面 [animatedHole]）。
    val hole = animatedHole(ring)

    Box(Modifier.fillMaxSize()) {
        // ---- 1. 压暗幕（不接触摸）：整屏黑 - 高亮圈那个洞 ----
        Canvas(Modifier.fillMaxSize()) {
            val alpha = dim.value
            if (alpha <= 0.001f) return@Canvas
            val mask = Path().apply {
                // EvenOdd：外框 + 内部圆角矩形 = 中间被挖掉
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                if (hole != null && hole.width > 1f && hole.height > 1f) {
                    addRoundRect(
                        RoundRect(
                            rect = hole,
                            cornerRadius = CornerRadius(hole.height * 0.5f)
                        )
                    )
                }
            }
            drawPath(mask, Color.Black.copy(alpha = alpha))
        }

        // ---- 2. 挡板：圈外四块（圈内一个节点都不铺）----
        // 「恭喜你出师了」是最后一步、没有锚点：整屏铺一块挡板 ——
        // 用户 1.2.68："再给结语界面做个背景不可点击，只有确定、上一步和退出按键可以点击，
        // 点击确定退出结语界面后恢复正常。" 卡片排在挡板后面组合（更靠上），
        // 所以它自己的三颗按钮照常能点；教程一结束浮层整个撤掉，点击自然恢复正常。
        val isEpilogue = Tutorial.step == Tutorial.steps.lastIndex
        if (Tutorial.spotlight) {
            // 自动编排期间整屏都锁住：用户此刻不该动界面
            TouchBlocker(0f, 0f, screenW, screenH)
        } else if (ring != null) {
            val midH = (ring.bottom - ring.top).coerceAtLeast(0f)
            TouchBlocker(0f, 0f, screenW, ring.top.coerceAtLeast(0f))
            TouchBlocker(0f, ring.bottom, screenW, (screenH - ring.bottom).coerceAtLeast(0f))
            TouchBlocker(0f, ring.top, ring.left.coerceAtLeast(0f), midH)
            TouchBlocker(ring.right, ring.top, (screenW - ring.right).coerceAtLeast(0f), midH)
        } else if (isEpilogue) {
            TouchBlocker(0f, 0f, screenW, screenH)
        }

        // ---- 3. 高亮圈 + 手势提示（都不接触摸）----
        if (ring != null) HighlightRing(ring, metrics, style)
        if (spec.gesture != TutorialGesture.NONE) {
            val hintSize = metrics.sizePx * 0.17f
            val center = hintCenter(spec.gesture, ring, cardSpot, screenW, screenH, hintSize)
            GestureHint(spec.gesture, center, hintSize, style)
        }

        // ---- 4. 卡片 ----
        TutorialCard(
            spec = spec,
            spot = cardSpot,
            anchor = anchor,
            screenW = screenW,
            screenH = screenH,
            metrics = metrics,
            style = style,
            onBack = { Tutorial.back() },
            onExit = { exitConfirm = true },
            onFinish = { Tutorial.finish() }
        )

        // ---- 5. 退出确认 ----
        if (exitConfirm) {
            TutorialExitConfirm(
                metrics = metrics,
                style = style,
                onCancel = { exitConfirm = false },
                onConfirm = {
                    exitConfirm = false
                    Tutorial.stop()
                }
            )
        }
    }
}

/** 自动滚工具列表那一步要压暗幕；其余按 spec 来。 */
private fun resolveSpot(
    spec: TutorialStep,
    anchor: Rect?,
    screenW: Float,
    screenH: Float,
    metrics: WatchMetrics
): TutorialCardSpot {
    val requested = spec.spot ?: run {
        // 没点名位置：锚点在下半屏 → 卡片放上面，反之放下面
        val cy = anchor?.center?.y ?: (screenH * 0.5f)
        if (cy > screenH * 0.5f) TutorialCardSpot.TOP else TutorialCardSpot.BOTTOM
    }
    if (anchor == null || spec.spot == null) return requested

    // 点名 BELOW / ABOVE 时，放不下就翻到另一边
    val margin = screenW * 0.05f
    val gap = metrics.sizePx * 0.035f
    val estimate = screenH * 0.34f
    return when (requested) {
        TutorialCardSpot.BELOW ->
            if (anchor.bottom + gap + estimate > screenH - margin) TutorialCardSpot.ABOVE else requested
        TutorialCardSpot.ABOVE ->
            if (anchor.top - gap - estimate < margin) TutorialCardSpot.BELOW else requested
        else -> requested
    }
}

/** 卡片里那颗胶囊按钮的高度：`fontSmall` 的行高 × 1.18（用户指定的比例）。 */
@Composable
private fun pillHeight(metrics: WatchMetrics) = with(LocalDensity.current) {
    metrics.fontSmall.toDp() * 1.18f
}

// ---------------------------------------------------------------------------
// 蒙版的"洞"（本轮新增）
// ---------------------------------------------------------------------------

/** 洞从上一个位置滑到下一个位置的时长（ms）。和暗幕升降（460）同量级，不抢戏。 */
private const val TUTORIAL_HOLE_MS = 320

/**
 * 画布上"曲线被画出来"的时长（ms）。
 *
 * 必须和 `GraphScreen` 里那个私有的 `REVEAL_MS` 同值 —— 绘制那一步的暗幕要等
 * 动画播完才恢复变强。两边各有一份，改一个记得改另一个。
 */
private const val TUTORIAL_REVEAL_MS = 520

/**
 * 「坐标轴上现在有数字了」那一步：蒙版先**渐弱**（0.10）停留多久，再**渐强**到 0.5。
 *
 * 这一步的自动编排总共停 2 秒（见 `GraphScreen` 的 `"coordinates"` 分支），
 * 900ms 大致是"变弱（460ms 动画）+ 让眼睛看清数字"那一段，剩下的一秒用来渐强与停留。
 */
private const val TUTORIAL_COORDS_WEAK_MS = 900L

/**
 * 蒙版上那个洞的**当前位置**：四条边各自补间（见 [TutorialOverlay] 里那段说明）。
 *
 * 为什么用四条独立的 `animateFloatAsState` 而不是一条 Rect 动画：
 * `Animatable<Rect>` 需要 `Rect.VectorConverter`，而这里只需要"位置/大小跟着变"
 * —— 四条边各自 tween 已经足够，而且宽度/高度变化时曲率（= 半高）会自然重算。
 *
 * 返回 null = 这一步没有锚点（洞收到 0 面积，蒙版就是一整块）。
 */
@Composable
private fun animatedHole(target: Rect?): Rect? {
    val spec = tween<Float>(durationMillis = TUTORIAL_HOLE_MS, easing = FastOutSlowInEasing)
    val l by animateFloatAsState(target?.left ?: 0f, spec, label = "tutorialHoleL")
    val t by animateFloatAsState(target?.top ?: 0f, spec, label = "tutorialHoleT")
    val r by animateFloatAsState(target?.right ?: 0f, spec, label = "tutorialHoleR")
    val b by animateFloatAsState(target?.bottom ?: 0f, spec, label = "tutorialHoleB")
    if (r - l <= 2f || b - t <= 2f) return null
    return Rect(l, t, r, b)
}

// ---------------------------------------------------------------------------
// 教程的四套外观（本轮新增）
//
// 用户原话："请为 material 主题和毛玻璃主题的白天模式和夜间模式各写一套
// 欢迎界面和新手教程的样式。"
//
// 于是教程浮层的"材质"集中到这一处：卡片/描边/强调色/蒙版浓度四件事，
// 按 (毛玻璃?, 白天?) 四种组合各给一套值；控件怎么画（是否叠玻璃、是否描边）
// 由 [glass] 这一位决定。Material 四套都不叠玻璃、蒙版更浓一点。
// ---------------------------------------------------------------------------

/** 教程浮层的一套外观。 */
internal class TutorialStyle(
    /** 是否走液态玻璃语言（卡片/胶囊叠 `glassSurface`）。 */
    val glass: Boolean,
    /** 白天模式（浅底）。 */
    val day: Boolean,
    /** 卡片底色。 */
    val card: Color,
    /** 卡片描边。 */
    val cardBorder: Color,
    /** 强调色（高亮圈、手势提示、进度条）。 */
    val accent: Color,
    /** 普通步骤的蒙版浓度。 */
    val scrim: Float
) {
    /**
     * 卡片底下的**压底**（只有毛玻璃用）。
     *
     * 用户原话（2026-10-28）："毛玻璃主题下，重温新手教程的确定框和函数创建界面框
     * 中间有异常的光亮。"
     *
     * 根因不是"光"，而是**太透**：教程卡在函数编辑那几步是盖在输入框上的，
     * `GlassTokens.surface`（夜间只有 13% 白）基本就是个窗口 ——
     * 输入框那块强调色（聚焦时的蓝）直接从卡片中间透出来，看着就是一块亮斑。
     * Material 主题的卡片是实心的，所以从来没人见过。
     *
     * 这里在 `style.card` 下面再垫一层半透的黑 / 白，把背后的内容压到看不出来，
     * 玻璃的边缘光泽、1dp 亮边、半透明观感都还在 —— 卡片不再是"窗口"，
     * 但也不是一块死板的实心板。
     */
    val backing: Color
        get() = if (glass) {
            if (day) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        }
}

/**
 * 当前该用哪一套。
 *
 * 四种组合的差别（都写在选择表里，不再散落到各控件）：
 *   * **Material 夜间**：实心深卡片 + 白 20% 描边，蒙版 0.34（对比最强）；
 *   * **Material 白天**：实心浅卡片 + 深 12% 描边，蒙版 0.24；
 *   * **毛玻璃 夜间**：半透明卡（[GlassTokens.surface]）+ 玻璃光泽，蒙版 0.28
 *     （玻璃本身已经压了一层暗，再压浓就看不清下面的曲线）；
 *   * **毛玻璃 白天**：半透明白卡 + 玻璃光泽，蒙版 0.20（浅底上太浓会显脏）。
 */
@Composable
internal fun rememberTutorialStyle(): TutorialStyle {
    val glass = LocalGlassTheme.current
    val day = GlassTokens.isLight
    return remember(glass, day) {
        when {
            glass && day -> TutorialStyle(
                glass = true, day = true,
                card = GlassTokens.surface,
                cardBorder = Color.White.copy(alpha = 0.72f),
                accent = KeyPalette.accent,
                scrim = 0.20f
            )
            glass -> TutorialStyle(
                glass = true, day = false,
                card = GlassTokens.surface,
                cardBorder = Color.White.copy(alpha = 0.34f),
                accent = KeyPalette.accent,
                scrim = 0.28f
            )
            day -> TutorialStyle(
                glass = false, day = true,
                card = KeyPalette.bubble,
                cardBorder = Color.Black.copy(alpha = 0.12f),
                accent = KeyPalette.accent,
                scrim = 0.24f
            )
            else -> TutorialStyle(
                glass = false, day = false,
                card = KeyPalette.bubble,
                cardBorder = Color.White.copy(alpha = 0.20f),
                accent = KeyPalette.accent,
                scrim = 0.34f
            )
        }
    }
}

/**
 * 高亮圈：一圈细描边 + 极淡的呼吸（**没有影**）。
 *
 * 2026-10-27（本轮）：用户说"教程里提示圈的位置没有去除阴影"。
 * 旧写法在正圈**外面**又套了一圈 `stroke × 3.2`、alpha 0.12~0.34 的宽描边 ——
 * 那一圈糊边就是用户看到的"阴影"。现在整圈宽度只比正圈宽 1px，
 * 强弱只靠 alpha 呼吸（0.18 → 0.45），不再有任何"放大的一圈"。
 */
@Composable
private fun HighlightRing(rect: Rect, metrics: WatchMetrics, style: TutorialStyle) {
    val pulse by rememberInfiniteTransition(label = "tutorialRing").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tutorialRingPulse"
    )
    val density = LocalDensity.current
    val stroke = (metrics.sizePx * 0.008f).coerceAtLeast(2f)
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(
                with(density) { rect.width.toDp() },
                with(density) { rect.height.toDp() }
            )
            .drawBehind {
                // 胶囊圆角：高的一半（键盘键 / 图标键 / 行都是胶囊）
                val corner = CornerRadius(size.height * 0.5f)
                // 呼吸的那一圈 = 正圈自己加宽 1px（不是外面再套一圈）
                drawRoundRect(
                    color = style.accent.copy(alpha = pulse * 0.5f),
                    style = Stroke(width = stroke + 1f),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = style.accent,
                    style = Stroke(width = stroke),
                    cornerRadius = corner
                )
            }
    )
}

/** 手势提示的中心点（px）。 */
private fun hintCenter(
    gesture: TutorialGesture,
    ring: Rect?,
    spot: TutorialCardSpot,
    screenW: Float,
    screenH: Float,
    sizePx: Float
): Offset {
    // ---- 本轮（用户原话："提示圈和引导点击的特效不在一个位置，引导点击的特效偏移了"）----
    //
    // 点一下 / 长按这两种手势，提示**必须正好画在高亮圈的中心** ——
    // 用户看到的就是"圈里的那个控件会被点"。旧写法特意把它挪到圈外
    // （`ring.top - 0.75 × size`），于是两个图形错开一大截。
    // 只有"滑动"类手势才需要往边缘挪（手指要从那头划到这头，画在圈心没意义）。
    if (ring != null && (gesture == TutorialGesture.TAP || gesture == TutorialGesture.LONG_PRESS)) {
        return ring.center
    }
    // 没有锚点的手势：贴着对应的屏幕边画
    when (gesture) {
        TutorialGesture.SWIPE_UP -> return Offset(screenW / 2f, screenH * 0.88f)
        TutorialGesture.SWIPE_DOWN -> return Offset(screenW / 2f, screenH * 0.12f)
        TutorialGesture.SWIPE_LEFT, TutorialGesture.SWIPE_RIGHT ->
            if (ring == null) {
                // 整屏横划那几步（25 / 33）卡片正放在屏幕正中，
                // 提示画在正中会被卡片整个盖住、等于没画 —— 挪到它指向的那条边：
                // "往右划"贴左边缘、"往左划"贴右边缘。
                val edge = if (gesture == TutorialGesture.SWIPE_RIGHT) 0.16f else 0.84f
                return Offset(screenW * edge, screenH * 0.5f)
            }
        else -> Unit
    }
    if (ring == null) return Offset(screenW / 2f, screenH * 0.5f)

    // 有锚点的滑动手势：放到**卡片对面**那一侧，别让卡片压住提示
    val cardAbove = spot == TutorialCardSpot.BOTTOM || spot == TutorialCardSpot.BELOW
    val y = if (cardAbove) ring.top - sizePx * 0.75f else ring.bottom + sizePx * 0.75f
    return Offset(
        ring.center.x.coerceIn(sizePx, screenW - sizePx),
        y.coerceIn(sizePx, screenH - sizePx)
    )
}

/**
 * 一块"吃掉触摸"的挡板（px 定位）。
 *
 * 在 `Initial` 阶段就 consume，所以下面那一层的任何手势/点击都拿不到；
 * 同时它**不带任何视觉**（不带背景、不带边框），用户看不到它。
 */
@Composable
private fun TouchBlocker(xPx: Float, yPx: Float, wPx: Float, hPx: Float) {
    if (wPx <= 0.5f || hPx <= 0.5f) return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    )
}

/**
 * 手势提示：点 / 长按 / 四个方向的箭头。纯 Canvas，不接触摸。
 *
 * [style] 只用到强调色 —— 提示图形的颜色跟着教程那套外观走（见 [rememberTutorialStyle]）。
 */
@Composable
private fun GestureHint(
    gesture: TutorialGesture,
    center: Offset,
    sizePx: Float,
    style: TutorialStyle
) {
    if (gesture == TutorialGesture.NONE) return
    val density = LocalDensity.current
    val pulse by rememberInfiniteTransition(label = "tutorialHint").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tutorialHintPulse"
    )
    Canvas(
        Modifier
            .offset { IntOffset((center.x - sizePx / 2f).roundToInt(), (center.y - sizePx / 2f).roundToInt()) }
            .size(with(density) { sizePx.toDp() })
    ) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = s * 0.06f
        val color = style.accent
        when (gesture) {
            TutorialGesture.TAP -> {
                // 外圈往外扩、同时淡出（"点一下"的涟漪）
                drawCircle(
                    color = color.copy(alpha = 0.55f * (1f - pulse)),
                    radius = s * (0.17f + 0.15f * pulse),
                    style = Stroke(width = stroke)
                )
                drawCircle(color = color, radius = s * 0.09f)
            }

            TutorialGesture.LONG_PRESS -> {
                // 底圈 + 一圈"转起来"的进度弧
                drawCircle(color = color.copy(alpha = 0.35f), radius = s * 0.30f, style = Stroke(width = stroke))
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * pulse,
                    useCenter = false,
                    topLeft = Offset(c.x - s * 0.34f, c.y - s * 0.34f),
                    size = Size(s * 0.68f, s * 0.68f),
                    style = Stroke(width = stroke * 1.25f, cap = StrokeCap.Round)
                )
                drawCircle(color = color, radius = s * 0.07f)
            }

            TutorialGesture.SWIPE_RIGHT, TutorialGesture.SWIPE_LEFT -> {
                val dir = if (gesture == TutorialGesture.SWIPE_RIGHT) 1f else -1f
                val y = c.y
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(c.x - dir * s * 0.34f, y),
                    end = Offset(c.x + dir * s * 0.30f, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                val tipX = c.x + dir * s * 0.30f
                drawLine(
                    color = color,
                    start = Offset(tipX - dir * s * 0.13f, y - s * 0.13f),
                    end = Offset(tipX, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(tipX - dir * s * 0.13f, y + s * 0.13f),
                    end = Offset(tipX, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            TutorialGesture.SWIPE_UP, TutorialGesture.SWIPE_DOWN -> {
                val dir = if (gesture == TutorialGesture.SWIPE_DOWN) 1f else -1f
                val x = c.x
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(x, c.y + dir * s * 0.34f),
                    end = Offset(x, c.y - dir * s * 0.30f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                val tipY = c.y - dir * s * 0.30f
                drawLine(
                    color = color,
                    start = Offset(x - s * 0.13f, tipY + dir * s * 0.13f),
                    end = Offset(x, tipY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(x + s * 0.13f, tipY + dir * s * 0.13f),
                    end = Offset(x, tipY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            TutorialGesture.NONE -> Unit
        }
    }
}

/**
 * 教程卡片。
 *
 * ⚠️ 整块卡片**不接触摸**（没有 `clickable` / 没有 `Surface`）：
 * 落在卡片正文上的触摸会穿到下面的挡板 / 真界面；
 * 只有三颗胶囊按钮（←、↑、最后一步的「确定」）自己消费。
 */
@Composable
private fun TutorialCard(
    spec: TutorialStep,
    spot: TutorialCardSpot,
    anchor: Rect?,
    screenW: Float,
    screenH: Float,
    metrics: WatchMetrics,
    style: TutorialStyle,
    onBack: () -> Unit,
    onExit: () -> Unit,
    onFinish: () -> Unit
) {
    val density = LocalDensity.current
    val margin = screenW * 0.05f
    val gap = metrics.sizePx * 0.035f
    val cardWidth = screenW - margin * 2f
    var cardSize by remember(spec.title) { mutableStateOf(IntSize.Zero) }
    val cardHeight = if (cardSize.height > 0) cardSize.height.toFloat() else screenH * 0.32f

    // 按"这一步能用的高度"缩字号：ABOVE/BELOW 贴着圈时空间最小
    val available = when (spot) {
        TutorialCardSpot.TOP -> anchor?.top ?: (screenH * 0.45f)
        TutorialCardSpot.BOTTOM -> anchor?.let { screenH - it.bottom } ?: (screenH * 0.45f)
        TutorialCardSpot.BELOW -> anchor?.let { screenH - it.bottom } ?: (screenH * 0.45f)
        TutorialCardSpot.ABOVE -> anchor?.top ?: (screenH * 0.45f)
        TutorialCardSpot.CENTER -> screenH * 0.62f
    }
    val fontScale = (available / (screenH * 0.55f)).coerceIn(0.72f, 1f)

    val top = when (spot) {
        TutorialCardSpot.TOP -> margin
        TutorialCardSpot.CENTER -> (screenH - cardHeight) / 2f
        TutorialCardSpot.BOTTOM -> screenH - cardHeight - margin
        TutorialCardSpot.BELOW -> (anchor?.bottom ?: 0f) + gap
        TutorialCardSpot.ABOVE -> (anchor?.top ?: screenH) - gap - cardHeight
    }.coerceIn(0f, (screenH - cardHeight).coerceAtLeast(0f))

    val shape = MaterialTheme.shapes.large
    // 卡片"材质"由教程外观决定（见 [rememberTutorialStyle]）：
    //   * 毛玻璃 → 半透明底 + `glassSurface` 的亮边/高光/回光（不再额外描边，玻璃自带）；
    //   * Material → 实心底 + 1dp 细描边。
    //
    // 底色写在 `background(color, shape)` 里（自己裁形），**不**在链首加 `clip`
    // —— 否则玻璃那层"往下散的投影"会被一起裁进卡片轮廓里，等于没有影。
    val surface = if (style.glass) {
        Modifier
            .background(style.backing, shape)
            .background(style.card, shape)
            .glassSurface(shape)
    } else {
        Modifier
            .background(style.card, shape)
            .border(1.dp, style.cardBorder, shape)
    }
    Box(
        modifier = Modifier
            .offset { IntOffset(((screenW - cardWidth) / 2f).roundToInt(), top.roundToInt()) }
            .width(with(density) { cardWidth.toDp() })
            .then(surface)
            .onSizeChanged { cardSize = it }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(metrics.sizeDp * 0.034f)
        ) {
            // ---- 进度条 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(metrics.sizeDp * 0.009f)
                    .clip(CircleShape)
                    .background(KeyPalette.track)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((Tutorial.step + 1f) / Tutorial.steps.size)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(style.accent)
                )
            }

            Spacer(Modifier.height(metrics.sizeDp * 0.024f))

            // ---- 标题 + 两颗胶囊按钮（上一步 / 退出）----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.title,
                    fontSize = metrics.fontSmall * 1.12f * fontScale,
                    fontWeight = FontWeight.Bold,
                    color = KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(metrics.sizeDp * 0.018f))
                // ---- 上一步 / 退出：箭头图标必须**几何居中** ----
                //
                // 用户本轮原话：
                // > "新手教程的上一步键和退出键的图标没有在按钮中心，
                // >  并且你要把这两个按钮做大点，上一步按钮的蓝色底也没了。"
                //
                // 旧写法是往 Text 里塞字符 "←" / "↑" —— 字形在行盒里天然偏下偏右，
                // 怎么调 padding 都居不准。现在改成 [TutorialArrow] 用 Canvas 画箭头，
                // 笔画以画布中心为对称点，居中由构造保证。
                // 颜色也换了：上一步回到**强调蓝**（`KeyPalette.accent`），
                // 之前那格 `normalBright` 在玻璃主题下几乎是白色，看着就是"没底"。
                val navH = pillHeight(metrics) * 1.5f
                TutorialPill(
                    arrow = TutorialArrow.Direction.LEFT,
                    metrics = metrics,
                    background = style.accent,
                    width = navH * 1.55f,
                    height = navH,
                    glass = style.glass,
                    onClick = onBack
                )
                Spacer(Modifier.width(metrics.sizeDp * 0.016f))
                TutorialPill(
                    arrow = TutorialArrow.Direction.UP,
                    metrics = metrics,
                    background = KeyPalette.danger,
                    width = navH * 1.35f,
                    height = navH,
                    glass = style.glass,
                    onClick = onExit
                )
            }

            Spacer(Modifier.height(metrics.sizeDp * 0.02f))

            // ---- 正文 ----
            Text(
                text = spec.body,
                fontSize = metrics.fontTiny * fontScale,
                lineHeight = metrics.fontTiny * 1.4f * fontScale,
                color = KeyPalette.textMuted
            )

            // ---- 最后一步：确定（实心强调色）----
            if (Tutorial.step == Tutorial.steps.lastIndex) {
                Spacer(Modifier.height(metrics.sizeDp * 0.03f))
                TutorialPill(
                    text = "确定",
                    metrics = metrics,
                    background = style.accent,
                    width = null,
                    // 用户 1.2.68："新手教程的结语界面的确定键高度也改成原来的两倍。"
                    // 和退出确认卡里的两颗胶囊同一个 ×2，整个结语卡因此更高一截。
                    height = pillHeight(metrics) * 2f,
                    fillWidth = true,
                    glass = style.glass,
                    onClick = onFinish
                )
            }
        }
    }
}

/**
 * 教程里的一颗胶囊小按钮。
 *
 * 高度默认 = `fontSmall` 的行高 × 1.18（用户指定）；`width == null` 时按内容撑开，
 * `fillWidth = true` 时铺满卡片一行。
 *
 * 2026-10-27：新增 [arrow]。上一步 / 退出这两颗改用**画出来的箭头**而不是字符，
 * 并且可以单独给 [height]（用户要求"把这两个按钮做大点"）。
 */
@Composable
private fun TutorialPill(
    text: String? = null,
    arrow: TutorialArrow.Direction? = null,
    metrics: WatchMetrics,
    background: Color,
    width: Dp?,
    height: Dp = pillHeight(metrics),
    onClick: () -> Unit,
    fillWidth: Boolean = false,
    glass: Boolean = false
) {
    val h = height
    val modifier = Modifier
        .height(h)
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .then(if (width != null) Modifier.width(width) else Modifier)
        // 毛玻璃主题下胶囊也要"玻璃光泽 + 亮边"（和全应用的按键一致）；
        // Material 主题保持实心胶囊。
        .background(background, PillShape)
        .then(if (glass) Modifier.glassSurface(PillShape) else Modifier)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (arrow != null) {
            // 箭头边长按胶囊高度取：高度变大，图标同比变大，但仍在按钮中央。
            TutorialArrow(
                direction = arrow,
                size = h * 0.56f,
                color = KeyPalette.text
            )
        } else {
            Text(
                text = text.orEmpty(),
                fontSize = metrics.fontSmall * 0.92f,
                color = KeyPalette.text,
                maxLines = 1
            )
        }
    }
}

/**
 * 教程里的箭头图标（Canvas 手绘）。
 *
 * ## 为什么要自己画（2026-10-27）
 *
 * 用户原话："上一步键和退出键的图标没有在按钮中心"。
 * 原来用的是文字 `←` / `↑`：字符的字形在行盒里**天然偏下**（留给下伸部），
 * 水平方向也带侧bearing，塞进 `Box(contentAlignment = Center)` 仍然偏。
 *
 * 这里改成"以画布中心为对称点"画笔画：箭杆从中心向两侧对称延长，
 * 箭头两撇也与中心等距 —— 居中由构造保证，不受字体影响。
 */
private object TutorialArrow {
    enum class Direction { LEFT, UP }

    @Composable
    operator fun invoke(direction: Direction, size: Dp, color: Color) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val stroke = w * 0.15f
            // 箭杆半长：0.36 个画布边长，两侧对称
            val half = w * 0.36f
            // 箭头两撇的长度（按箭杆长度的比例）
            val head = half * 0.78f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(
                    color = color,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            when (direction) {
                Direction.LEFT -> {
                    // 箭杆：从中心左右对称
                    line(cx + half, cy, cx - half, cy)
                    // 箭头：从左端点往右上 / 右下两撇
                    line(cx - half, cy, cx - half + head, cy - head)
                    line(cx - half, cy, cx - half + head, cy + head)
                }
                Direction.UP -> {
                    line(cx, cy + half, cx, cy - half)
                    line(cx, cy - half, cx - head, cy - half + head)
                    line(cx, cy - half, cx + head, cy - half + head)
                }
            }
        }
    }
}

/**
 * 退出教程的确认框。
 *
 * 用户要求：退出前必须确认一次，并且告诉用户**之后怎么再找到它**。
 * 卡片本体走 [TutorialConfirmCard]（和「重温新手教程」入口共用一套）。
 */
@Composable
private fun TutorialExitConfirm(
    metrics: WatchMetrics,
    style: TutorialStyle,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    TutorialConfirmCard(
        metrics = metrics,
        style = style,
        title = "退出教程？",
        body = "退出后想再看 可以长按设置键往下滑 找到「重温新手教程」。",
        confirmText = "退出",
        // 用户明确要求：取消 / 退出两颗按钮的高度是原来的两倍。
        pillHeightScale = 2f,
        onCancel = onCancel,
        onConfirm = onConfirm
    )
}

/**
 * 通用确认卡：整屏压暗 + 卡片 + 两颗胶囊按钮。
 *
 * 「退出教程」和「重温新手教程」开始前都要先问一句 —— 两处共用这一张卡
 * （材质跟着教程外观走），不再各写一套。
 *
 * 遮罩层用 `clickable` 吃掉点击（**不用** `Initial` 阶段 consume：
 * 那样会把里面两颗胶囊按钮的点击也一起吃掉，按钮就点不动了）。
 *
 * @param pillHeightScale 胶囊高度倍数；退出确认要 ×2，其它入口保持默认 1。
 */
@Composable
internal fun TutorialConfirmCard(
    metrics: WatchMetrics,
    style: TutorialStyle,
    title: String,
    body: String,
    confirmText: String,
    confirmBackground: Color = KeyPalette.danger,
    cancelText: String = "取消",
    pillHeightScale: Float = 1f,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { },
        contentAlignment = Alignment.Center
    ) {
        val shape = MaterialTheme.shapes.large
        val surface = if (style.glass) {
            Modifier.glassSurface(shape)
        } else {
            Modifier.border(1.dp, style.cardBorder, shape)
        }
        Box(
            modifier = Modifier
                .width(metrics.sizeDp * 0.88f)
                .background(style.backing, shape)
                .background(style.card, shape)
                .then(surface)
        ) {
            Column(modifier = Modifier.padding(metrics.sizeDp * 0.04f)) {
                Text(
                    text = title,
                    fontSize = metrics.fontSmall,
                    fontWeight = FontWeight.Bold,
                    color = KeyPalette.text,
                    maxLines = 1
                )
                Spacer(Modifier.height(metrics.sizeDp * 0.02f))
                Text(
                    text = body,
                    fontSize = metrics.fontTiny,
                    lineHeight = metrics.fontTiny * 1.4f,
                    color = KeyPalette.textMuted
                )
                Spacer(Modifier.height(metrics.sizeDp * 0.032f))
                val pillH = pillHeight(metrics) * pillHeightScale
                Row {
                    Box(Modifier.weight(1f)) {
                        TutorialPill(
                            text = cancelText,
                            metrics = metrics,
                            background = KeyPalette.normalBright,
                            width = null,
                            height = pillH,
                            fillWidth = true,
                            glass = style.glass,
                            onClick = onCancel
                        )
                    }
                    Spacer(Modifier.width(metrics.sizeDp * 0.022f))
                    Box(Modifier.weight(1f)) {
                        TutorialPill(
                            text = confirmText,
                            metrics = metrics,
                            background = confirmBackground,
                            width = null,
                            height = pillH,
                            fillWidth = true,
                            glass = style.glass,
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}
