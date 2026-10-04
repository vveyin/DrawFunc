// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.functiondrawer.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 对话框的几何 / 动画常数（全是**屏占比**，见 `WatchMetrics` 那套"为什么不用固定 dp"）。
// 基准：233dp 逻辑宽 / 466px 的圆屏（`sizeDp = 233dp`）。
// ---------------------------------------------------------------------------

/** 卡片圆角：0.085 × 233dp ≈ **19.8dp**（近胶囊的大圆角）。 */
private const val CARD_CORNER = 0.085f
/** 卡片左右内边距：≈ 10.5dp。 */
private const val CARD_PAD_H = 0.045f
/** 卡片上下内边距：≈ 7.5dp。 */
private const val CARD_PAD_V = 0.032f
/** 卡片的最小高度（兜底）：0.20 × 233dp ≈ 46.6dp —— 内容再少也不像一条细线。 */
private const val CARD_MIN_H = 0.20f
/** 标题 / 错误行、以及按钮行 / 路径小字之间的间距：≈ 3.3dp。 */
private const val CARD_GAP = 0.014f
/** 输入框和 ×/✓ 行两侧的间距：≈ 3.7dp。 */
private const val CARD_GAP_FIELD = 0.016f
/** 错误行的最小高度（**永远占位**，见 KDoc）：≈ 12.8dp。 */
private const val ERROR_MIN_H = 0.055f

/** 输入框 / ×✓ 行的高度：0.125 × 233dp ≈ **29.1dp**。 */
private const val FIELD_H = 0.125f
/** 输入框里文字的左右内边距：≈ 8.9dp。 */
private const val FIELD_PAD_H = 0.038f

/** 取消 / 确定之间的缝：≈ 5.6dp。 */
private const val KEY_GAP = 0.024f
/** 按键上图标的边长：0.088 × 233dp ≈ **20.5dp**。 */
private const val KEY_ICON = 0.088f
/** 按键按下时缩到 0.94（幅度比列表行大一点：按键小，反馈要看得见）。 */
private const val KEY_PRESS_SCALE = 0.06f
/** 按下时那层白光的强度（14% ≈ "亮一档"，和 MiniChip/WatchIconButton 同一个量级）。 */
private const val KEY_GLOW = 0.14f

/**
 * 算卡片宽度用的**高度**（占屏高）。
 *
 * 取 0.21 而不是 0.5（垂直中心）：`circleWidthFraction(0.5) = 1`，拿中心行算等于没算；
 * 卡片居中之后真正会切到它的是**上（下）沿**那一行。0.21 是"内容 0.55 屏高时上沿
 * 落在 0.225、错误占两行时落在 0.197"再往下留的余量，算式写在 `NameDialog` 的 KDoc 里。
 */
private const val CARD_ARC_Y = 0.21f
/** 用满圆内弦宽时留的余量（和 `FunctionList` 的 `ARC_MARGIN` / `SettingsScreen` 的 `WIDTH_MARGIN` 同一档）。 */
private const val CARD_ARC_MARGIN = 0.93f
/** 方屏（或拿不到屏幕形状时）用的卡片宽度：0.92 屏宽。 */
private const val SQUARE_CARD_WIDTH = 0.92f

/** 遮罩浓度：75% 黑（比调色板的 72% 略深一点，这是模态框）。 */
private const val DIALOG_MASK_ALPHA = 0.75f
/** 进场时长（用户给的区间 140~220ms 的中段）。 */
private const val DIALOG_IN_MS = 180
/** 退场时长：比进场短一点，收起来要干脆。 */
private const val DIALOG_OUT_MS = 140
/** 进场时卡片的起始缩放：0.90 → 1.0。 */
private const val DIALOG_IN_SCALE = 0.90f
/** 进场时卡片从下方多少屏高处浮上来：0.035 × 233dp ≈ 8.2dp。 */
private const val DIALOG_IN_RISE = 0.035f
/** 输入框聚焦高亮 / 按键渐暗的时长。 */
private const val FOCUS_MS = 160

/**
 * 通用的**命名对话框**：**保存函数文件**和**重命名文件**共用这一个。
 *
 * 两个调用方：`GraphScreen.kt`（长按 `+` 保存函数文件）和
 * `SavesScreen.kt`（普通模式下双击一行重命名）。文件名仍然叫 `SaveImageDialog.kt`
 * （历史原因，`ImageExport.kt` 也原样留着，将来要存图时把
 * `GraphCapture.savePng` 接回来即可）。
 *
 * ## 位置：**屏幕正中心**（用户原话："应该在屏幕中心位置而不是上面"）
 *
 * 卡片用 `Alignment.Center` 摆在屏幕正中。上一版是"贴顶 0.075 屏高"，
 * 那时把"取消/确定"放在**最上面**是为了躲系统输入法；现在按钮挪到输入框
 * **下面**（用户要求），躲键盘这件事改由 `imePadding()` + 可滚动兜底 + 三条退路
 * 一起接管（见下）。
 *
 * ## 宽度：**按圆屏弧宽自适应**（和 `FunctionList.rowWidthFraction` 同一套算法）
 *
 * 表壳是圆的，卡片越靠上下越容易被切掉，所以宽度这样算（基准 233dp / 466px 圆表）：
 *
 *   `宽 = sizeDp × circleWidthFraction(0.21) × 0.93 = 233dp × 0.8146 × 0.93 ≈ **176dp**`
 *
 * 为什么基准高度取 **0.21** 而不是"垂直中心那一行"：卡片是**居中**的、上下沿对称，
 * 而 `circleWidthFraction(0.5) = 1`（圆心那一行永远是全宽）—— 拿中心行算等于没算。
 * 真正会切到卡片的是**上（下）沿**那一行：
 *
 *   * 卡片内容高 ≈ **128dp ≈ 0.55 屏高**（标题 16 + 错误行 ≥13 + 输入框 29.1 +
 *     ×/✓ 行 29.1 + 路径小字 12 + 四处间距 10.7 + 上下内边距 15），
 *     居中后上沿落在 **y ≈ 0.225**，那里 `circleWidthFraction ≈ 0.836`（弦宽 ≈ 195dp）；
 *   * 错误文字占两行时卡片长到 ≈141dp，上沿到 y ≈ 0.197，那里弦宽 ≈ 185dp；
 *   * 取 0.21（`circleWidthFraction ≈ 0.8146`）当基准再乘 0.93 → **176dp**：
 *     比上面两种弦宽分别窄 9dp / 4.5dp —— 卡片四角永远在圆内，而 176dp 也仍然是
 *     一张"卡片"（圆屏左右收口，**写死宽度必被表壳切**）；
 *   * **方屏**（或拿不到屏幕形状时）退回 `0.92 屏宽`：没有收口问题，太窄反而难看。
 *
 * 卡片高度**不写死**：只有 `heightIn(min = 0.20 屏高)` 兜底，高度由内容撑开
 * （老代码写死 `.height(0.212 屏高 ≈ 49dp)`，而内容比卡片高一倍多，`Column` 把排在
 * 后面的兄弟压成 0 高 —— 那就是用户报的"没写确定和取消按键，退不出来"）。
 *
 * ## 输入方式：**系统输入法**（用户真机反馈后改的）
 *
 * 用户原话："调用的也不是系统输入法，应该是系统输入法的。"
 * 所以这里**不再用自绘的字母表键盘**（`AlphabetKeyboard`）和自绘的 `⌫`：
 * 输入框是真正的 `BasicTextField`，**点输入框（或框子任意一处）**就
 * `FocusRequester.requestFocus()` + `LocalSoftwareKeyboardController.show()`，
 * 退格、选词、光标全交给系统 IME。
 * 顺带把 `rotary` 参数也去掉了：那是给"可滚动的字母表键盘"用的，
 * 系统 IME 不吃表冠事件，留着就是死参数。
 *
 * ## 版面顺序（上 → 下）：标题 → 错误行 → **输入框 → 取消(×) / 确定(✓)** → 路径小字
 *
 * 用户原话："浮窗上的取消键和确定键应该在输入框下面，取消和确定的图标应该是 × 和 ✓"。
 * 具体数值（基准 233dp / 466px，卡片宽 176dp ≈ 0.7576 屏宽）：
 *
 *   * 卡片左右内边距 `0.045 × sizeDp ≈ 10.5dp`、上下 `0.032 ≈ 7.5dp`；
 *   * 标题：`fontSmall`（≈12.1sp）、**居中**（卡片居中，标题居中最稳）、单行省略；
 *   * 错误行：`fontTiny`（≈9.8sp）、`MaterialTheme.colors.error`、居中、最多两行，
 *     **永远占位**（没错误时是一行空文字 + `heightIn(min = 0.055 × sizeDp ≈ 12.8dp)`）：
 *     校验失败时卡片只长高一点点，×/✓ 不会整体往下跳；
 *   * 输入框与 ×/✓ 行**同高** `0.125 × sizeDp ≈ 29.1dp`，两行之间留 `0.016 ≈ 3.7dp`；
 *   * ×/✓ 各占一半宽：`(176 - 2×10.5 - 0.024×233) / 2 ≈ **74.9dp × 29.1dp**` 的宽胶囊，
 *     圆表上手指按得中；图标 `Icons.Filled.Close` / `Icons.Filled.Done`，
 *     边长 `0.088 × sizeDp ≈ 20.5dp`（这两个都在 `material-icons-core` 里，
 *     **不引** `material-icons-extended`）；
 *   * 卡片圆角 `RoundedCornerShape(0.085 × sizeDp ≈ 19.8dp)`；配色沿用 `KeyPalette`：
 *     底 `bubble`（#F21F2124）+ 1dp `bubbleBorder`（20% 白）描边，取消键 `normal`
 *     深灰、确定键 `accent` 强调色，图标一律纯白；
 *   * 最下面一行是 `pathHint` 小字（存盘位置 / 原名），`fontTiny` + 50% 白。
 *
 * ## 键盘遮挡：三件事一起做（"退不出来"和"看不见输入框"都不该再发生）
 *
 *   1. **根容器 `.imePadding()`**：IME insets 派发时，可用区会被压到键盘之上，
 *      而卡片现在是**居中**的，所以整块卡片会跟着**往上移**（这一版它真的有用：
 *      贴顶布局下 `imePadding()` 只压容器底部、顶不开卡片，那正是上一版把按钮
 *      放在最上面的原因）。注意 `MainActivity` 是
 *      `setDecorFitsSystemWindows(window, false)` 的沉浸式全屏、manifest 里也没有
 *      `adjustResize` —— **IME insets 不一定派发**，所以下面两条也是必须的；
 *   2. **卡片自己挂 `.verticalScroll()`**：万一这块表的系统输入法把可用区压得比卡片
 *      还矮（项目里 `MathKeyboard.ExpressionField` 的注释写过"输入法能占满整块屏"），
 *      卡片变成**可滚的**：标题和输入框先露出来，往上拖就能看到 ×/✓，
 *      而不是被压成 0 高、或者整块跑出屏幕；
 *   3. **退路三条**（见下）：卡片里的 **×**、点**遮罩**、**系统返回键**。
 *
 * ## 退路有三条（"退不出来"这件事不能再发生）
 *
 *   1. 卡片里的 **取消** 按钮（永远可见，见上）；
 *   2. 点卡片外的**遮罩**（遮罩是 clickable 的，会吃掉点击并取消）；
 *   3. **系统返回键**（`BackHandler`）。
 *
 * ## 名字为空 / 重名怎么办（**明确拒绝，不静默兜底**）
 *
 * 规则在 `FunctionFileFormat.baseNameError`（纯函数、离线有测试）：
 * 空名字 → "名字不能为空"、带 `/ \ : * ? " < > |` 或控制字符 → 说明是哪类字符、
 * 超过 40 字 → "名字太长"、以点开头 → "名字不能以点开头"（隐藏文件）、
 * 与已有文件重名（不分大小写）→ "已有同名文件"。
 *
 * 这时对话框**不关闭**，把那句话显示在标题下面那行（红字）—— 用户原话是
 * "要明确拒绝并说明"，所以这里**不**像 `sanitizeFileName` 那样悄悄退回时间戳名字：
 * 用户回头在列表里根本认不出哪个文件是自己起的名字。
 *
 * [saving] 为 true 时两个按钮一起**渐暗到 45%**、标题变"正在保存…"：写盘是挂起调用，
 * 点了没反应会让人连点。
 *
 * ## 动画清单（用户要求"唤起这两个浮窗的时候应该有动画"）
 *
 * | 元素 | 动画 | 曲线 / 时长 |
 * |---|---|---|
 * | 背景遮罩 | alpha 0 → 1（底色本身是 75% 黑） | `tween(180)` `FastOutSlowInEasing` |
 * | 卡片本体 | alpha 0 → 1 | 同上（同一条进度，一起走完） |
 * | 卡片缩放 | 0.90 → 1.0 | 同上 |
 * | 卡片位移 | 从下方 `0.035 × sizeDp ≈ 8.2dp` 浮上来 | 同上 |
 * | 退场 | 上面四项**反向**播 140ms，**播完才**回调 `onCancel` | `tween(140)` 同一曲线 |
 * | 输入框聚焦 | 底色 深灰 → 强调色、描边 → 50% 白 | `tween(160)` 同上 |
 * | ×/✓ 在 saving 时 | 一起渐暗到 45% | `tween(160)` 同上 |
 * | 按下 ×/✓ | 缩到 0.94 + 一层 14% 白光 | `spring(0.45, 900)` / `tween(120)`（`rememberPressFeedback`） |
 *
 * 遮罩淡入、卡片缩放、卡片位移这三个值**只在 `graphicsLayer` 的 lambda 里读**：每帧只失效
 * 图层、不触发重组（和 `Common.kt` 里那两条"铁律"一致）。**例外**是"输入框聚焦底色"
 * 和"按键渐暗"—— 它们是 `Surface(color = …)` 和组件参数，只能在组合里读，所以那 160ms
 * 会重组（一次性、只在状态真的变化时发生，代价可以接受）。
 *
 * 退场为什么要"先播完再回调"：调用方是在 `onCancel` 里把这个对话框从组合中摘掉的，
 * 所以这里必须自己先把退场播完。关闭途中的连点由 `closing` 拦掉。
 *
 * ## 已知限制：退场动画只覆盖"取消"这一路
 *
 * 确认（✓）是**调用方**决定什么时候摘掉对话框的（保存要等写盘、重命名要等改名结果），
 * 而校验失败时对话框必须留着 —— 所以"先播退场再回调"这条在确认那一侧做不到，
 * 确认成功那一刻是"直接消失"（保存至少还有"正在保存…"那一小段可以看）。
 */
@Composable
fun NameDialog(
    /** 标题（例如"保存函数文件？"）。 */
    title: String,
    metrics: WatchMetrics,
    /** 最下面那行小字（存盘位置），原样显示。 */
    pathHint: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    initialName: String = "",
    /** 输入框为空时的灰色提示。 */
    hint: String = "输入文件名",
    /**
     * 校验失败的一句话（"名字不能为空"/"已有同名文件"）。
     *
     * 由调用方算（它才知道哪些名字已经存在），这里只负责显示 ——
     * 规则本身在 `FunctionFileFormat.baseNameError`。
     */
    errorText: String? = null,
    saving: Boolean = false,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    /** 输入框有没有拿着焦点（只用来驱动那个 160ms 的高亮渐变）。 */
    var focused by remember { mutableStateOf(false) }

    // ---- 进场 / 退场共用的一条进度（0 = 完全收起，1 = 完全展开）----
    // 为什么用 Animatable 而不是 AnimatedVisibility：这两个对话框是由**调用方**
    // 决定什么时候从组合里摘掉的（`if (saveDialogVisible)` / `if (renameFor != null)`），
    // 所以退场必须自己先播、播完再回调（见 KDoc 的动画清单）。
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    /** 退场已经在播了 —— 用来拦掉连点（遮罩 / 返回键 / × 同时碰一下）。 */
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = DIALOG_IN_MS, easing = FastOutSlowInEasing)
        )
    }

    /** 先播退场，**播完**再通知调用方把它摘掉 —— 这样"关闭"也有动画。 */
    fun dismissThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = DIALOG_OUT_MS, easing = FastOutSlowInEasing)
            )
            action()
        }
    }

    /**
     * 进对话框**不自动**把输入法叫出来（这是上一版"按钮看不见"的同一个坑的变体）。
     *
     * 这个项目自己早就观察过：这块表的**系统输入法弹出时能占满整块屏**
     * （见 `MathKeyboard.ExpressionField` 的注释）。如果一进来就 requestFocus + show()，
     * 用户第一眼看到的是输入法而不是对话框。所以顺序是：
     * **先看清对话框（五块内容都在）→ 点输入框 → 系统输入法弹出 → 打字 →
     * 返回键收起输入法 → × / ✓**。这也正是用户原话"点击输入框调起系统输入法"的动作。
     */
    // （这里刻意**没有** LaunchedEffect(Unit) { requestFocus(); show() } —— 理由见上）

    // 退路 ③：系统返回键 = 取消（输入法开着时第一次返回是收输入法，这也是系统的规矩）
    BackHandler(enabled = true) { dismissThen(onCancel) }

    // ---- 几何（数字与算式见 KDoc 的"位置 / 宽度 / 版面顺序"三节）----
    val isRound = LocalConfiguration.current.isScreenRound
    val cardWidthFraction = if (isRound) {
        // 圆屏：按**卡片上沿**那一行的圆内宽度收窄，再留 7% 余量。
        // （不取 0.5：那是圆心，circleWidthFraction(0.5) = 1，等于没算。）
        metrics.circleWidthFraction(CARD_ARC_Y) * CARD_ARC_MARGIN
    } else {
        // 方屏（或拿不到屏幕形状时）：没有"越靠边越窄"的问题，直接给接近满宽
        SQUARE_CARD_WIDTH
    }
    val cardWidth = metrics.sizeDp * cardWidthFraction
    val cardShape = RoundedCornerShape(metrics.sizeDp * CARD_CORNER)
    /** 输入框和 ×/✓ 行的**共同高度**（两行一样高，卡片才整齐）。 */
    val rowHeight = metrics.sizeDp * FIELD_H
    /** 卡片"上浮"的起始位移（像素 —— graphicsLayer 的 translationY 吃像素）。 */
    val risePx = with(LocalDensity.current) { (metrics.sizeDp * DIALOG_IN_RISE).toPx() }

    // 输入框聚焦高亮：底色 深灰 → 强调色、描边 → 50% 白（160ms 渐变，不"啪"地跳）
    val fieldColor by animateColorAsState(
        targetValue = if (focused) KeyPalette.accent else KeyPalette.normal,
        animationSpec = tween(durationMillis = FOCUS_MS, easing = FastOutSlowInEasing)
    )
    val fieldBorder by animateColorAsState(
        targetValue = if (focused) KeyPalette.text.copy(alpha = 0.5f) else KeyPalette.bubbleBorder,
        animationSpec = tween(durationMillis = FOCUS_MS, easing = FastOutSlowInEasing)
    )
    // saving 时两颗按键一起渐暗（同样是渐变，不是"啪"地变灰）
    val keyAlpha by animateFloatAsState(
        targetValue = if (saving) 0.45f else 1f,
        animationSpec = tween(durationMillis = FOCUS_MS, easing = FastOutSlowInEasing)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 用户要求：用 IME insets 把内容顶上来。卡片是**居中**的，所以这回它真的
            // 能把整块卡片往上推（贴顶布局下只压容器底部，顶不开卡片）。
            .imePadding()
    ) {

        // 退路 ②：遮罩吃掉点击并取消。**必须吃掉** —— 这个对话框会盖在函数列表 /
        // 文件列表上，遮罩没有 clickable 的话点击会穿透到下面的行（点一下进了编辑器）。
        // 淡入用 graphicsLayer 的 alpha：只失效图层、不重组（和 Common.kt 那两条"铁律"一致）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = DIALOG_MASK_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { dismissThen(onCancel) }
        )

        // 卡片：**屏幕正中心**（`Alignment.Center`），宽度按圆屏弧宽自适应，高度由内容撑开
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(cardWidth)
                .graphicsLayer {
                    // 进场：淡入 + 0.90 → 1.0 放大 + 从下方 8.2dp 浮上来（退场就反向）
                    val p = progress.value
                    alpha = p
                    val s = DIALOG_IN_SCALE + (1f - DIALOG_IN_SCALE) * p
                    scaleX = s
                    scaleY = s
                    translationY = (1f - p) * risePx
                }
                .clip(cardShape)
                .background(color = KeyPalette.bubble)
                .border(width = 1.dp, color = KeyPalette.bubbleBorder, shape = cardShape)
                // 兜底：万一键盘把可用区压得比卡片还矮，卡片变成可滚的（见 KDoc 第 2 条）
                .verticalScroll(rememberScrollState())
                // 只有下限、没有上限：高度由内容决定。0.20 屏高只是为了"空内容也不像一条线"
                .heightIn(min = metrics.sizeDp * CARD_MIN_H)
                .padding(
                    horizontal = metrics.sizeDp * CARD_PAD_H,
                    vertical = metrics.sizeDp * CARD_PAD_V
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- ① 标题（居中；保存中变成"正在保存…"）----
            Text(
                text = if (saving) "正在保存…" else title,
                fontSize = metrics.fontSmall,
                color = KeyPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(metrics.sizeDp * CARD_GAP))

            // ---- ② 错误行**永远占着位置**（没错误时是一行空文字）----
            // 这样名字校验失败时卡片只长高一点点，×/✓ 不会整体往下跳。
            Text(
                text = errorText.orEmpty(),
                fontSize = metrics.fontTiny,
                color = MaterialTheme.colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.sizeDp * ERROR_MIN_H)
            )

            Spacer(Modifier.height(metrics.sizeDp * CARD_GAP_FIELD))

            // ---- ③ 输入框：真正的 BasicTextField（系统输入法，**不是**自绘键盘）----
            // 聚焦时底色渐变到强调色、描边变 50% 白（上面那两个 animateColorAsState）。
            Surface(
                color = fieldColor,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .border(width = 1.dp, color = fieldBorder, shape = PillShape)
                    // 点框子任意一处都把焦点/输入法叫起来（某些 ROM 点文本域不自动弹）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                    }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = metrics.sizeDp * FIELD_PAD_H),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = hint,
                            fontSize = metrics.fontSmall,
                            color = KeyPalette.textMuted.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = KeyPalette.text,
                            fontSize = metrics.fontSmall
                        ),
                        cursorBrush = SolidColor(KeyPalette.text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            // 聚焦高亮那两个动画的输入
                            .onFocusChanged { focused = it.isFocused }
                    )
                }
            }

            Spacer(Modifier.height(metrics.sizeDp * CARD_GAP_FIELD))

            // ---- ④ 取消(×) / 确定(✓)：**排在输入框下面**（用户要求）----
            //
            // 两颗按键各占一半宽（233dp 屏上 ≈ 74.9dp × 29.1dp 的宽胶囊，圆表上按得中），
            // 高度和输入框一样（`rowHeight`）；按下有缩放 + 白光反馈（见 [DialogIconKey]）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * KEY_GAP)
            ) {
                DialogIconKey(
                    icon = Icons.Filled.Close,
                    contentDescription = "取消",
                    metrics = metrics,
                    containerColor = KeyPalette.normal,
                    enabled = !saving,
                    alpha = keyAlpha,
                    modifier = Modifier.weight(1f),
                    // 取消走"先播退场再回调"，所以关掉是有动画的
                    onClick = { dismissThen(onCancel) }
                )
                DialogIconKey(
                    icon = Icons.Filled.Done,
                    contentDescription = "确定",
                    metrics = metrics,
                    containerColor = KeyPalette.accent,
                    enabled = !saving,
                    alpha = keyAlpha,
                    modifier = Modifier.weight(1f),
                    // 确认**不播退场**：关不关、什么时候关由调用方说了算
                    // （校验失败时对话框必须留着），见 KDoc 的"已知限制"。
                    onClick = { onConfirm(name) }
                )
            }

            Spacer(Modifier.height(metrics.sizeDp * CARD_GAP))

            // ---- ⑤ 存盘位置 / 原名：最后一行小字 ----
            Text(
                text = pathHint,
                fontSize = metrics.fontTiny,
                color = KeyPalette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

        }
    }
}

/**
 * 对话框里的**图标键**（取消 × / 确定 ✓）：胶囊底 + 按压缩放 + 一层白光高亮。
 *
 * 为什么不用 `Common.kt` 的 `WatchIconButton`：那个是**圆角方形**的固定边长
 * （`metrics.iconButton`，用户明确要求过"按键变成原来的样子"，不能改它），
 * 而这里要的是"和输入框一样高、各占卡片一半宽"的**宽胶囊**按键 ——
 * 用户的要求是"取消和确定的图标应该是 × 和 ✓"，两颗胶囊在圆表上更好按。
 * 按压反馈仍然是**同一套** `rememberPressFeedback`（缩放 + 白光，且只动图层）。
 *
 * @param alpha 由调用方做 `animateFloatAsState`（[NameDialog] 里保存中两颗键一起渐暗）
 * @param enabled 保存中置灰（`clickable(enabled = false)`：连按压反馈都不给）
 */
@Composable
private fun DialogIconKey(
    icon: ImageVector,
    contentDescription: String,
    metrics: WatchMetrics,
    containerColor: Color,
    enabled: Boolean,
    alpha: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(scaleAmount = KEY_PRESS_SCALE, glowAmount = KEY_GLOW)

    Surface(
        color = containerColor,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
            // 高度由外面那个 Row 夹住（`rowHeight`），这里撑满即可
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
                // 保存中渐暗：值由调用方动画驱动，这里只写图层、不参与布局
                this.alpha = alpha
            }
            .border(width = 1.dp, color = KeyPalette.bubbleBorder, shape = PillShape)
            .clickable(
                interactionSource = press.source,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 按下时叠一层很淡的白 = "亮一档"（和 MiniChip / WatchIconButton 同一套机制，
            // 画在 Icon 之前，所以图标永远在最上面）
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = press.glowAlpha }
                    .background(Color.White)
            )
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = KeyPalette.text,
                modifier = Modifier.size(metrics.sizeDp * KEY_ICON)
            )
        }
    }
}
