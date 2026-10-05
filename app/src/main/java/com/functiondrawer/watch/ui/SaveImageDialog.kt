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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// 卡片宽度上限要用到它（"四角在圆内"那条算式，见 CARD_WIDTH_LIMIT）
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// 对话框的几何 / 动画常数（全是**屏占比**，见 `WatchMetrics` 那套"为什么不用固定 dp"）。
// 基准：233dp 逻辑宽 / 466px 的圆屏（`sizeDp = 233dp`）。
// ---------------------------------------------------------------------------

/** 卡片圆角：0.085 × 233dp ≈ **19.8dp**（近胶囊的大圆角）。**它同时是"四角在不在圆内"算式里的 r**。 */
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

/** 输入框 / **导出** / ×✓ 三行的**共同高度**：0.125 × 233dp ≈ **29.1dp**（三行一样高，卡片才整齐）。 */
private const val FIELD_H = 0.125f
/** 输入框里文字的左右内边距：≈ 8.9dp。 */
private const val FIELD_PAD_H = 0.038f

/** 取消 / 确定之间的缝：≈ 5.6dp。 */
private const val KEY_GAP = 0.024f
/** 按键上图标的边长：0.088 × 233dp ≈ **20.5dp**。 */
private const val KEY_ICON = 0.088f
/** 导出那一行里图标与文字之间的缝：0.02 × 233dp ≈ 4.7dp。 */
private const val EXPORT_LABEL_GAP = 0.02f
/** 导出那一行的文字标签（用户原话就叫它"导出文件的按钮"，所以这回把话**写在键上**）。 */
private const val EXPORT_LABEL = "导出文件"
/** 按键按下时缩到 0.94（幅度比列表行大一点：按键小，反馈要看得见）。 */
private const val KEY_PRESS_SCALE = 0.06f
/** 按下时那层白光的强度（14% ≈ "亮一档"，和 MiniChip/WatchIconButton 同一个量级）。 */
private const val KEY_GLOW = 0.14f

// ---------------------------------------------------------------------------
// 卡片高度：**逐项加起来**，不是一个估出来的数。
// 这样"四角在不在圆内"那条宽度算式（[CARD_WIDTH_LIMIT]）会跟着这些常数自动走 ——
// 谁改了任何一行的高度，宽度上限跟着变，不会出现"版面改了、算式还是旧的"。
// ---------------------------------------------------------------------------
/** `WatchMetrics.fontSmall` 的比例（那边写的是 `sp(0.052f)`）。 */
private const val FONT_SMALL = 0.052f
/** `WatchMetrics.fontTiny` 的比例（那边写的是 `sp(0.042f)`）。 */
private const val FONT_TINY = 0.042f
/**
 * 一行文字的行高 ≈ 字号 × [LINE_H_RATIO]。
 *
 * 平台默认行距没有公开常数，这里取一个**偏大**的估值 —— 方向是刻意的：估大了，卡片会被
 * 算得更高、宽度上限就更保守；真机上文字其实更矮，四角只会更安全（反过来就会切角）。
 */
private const val LINE_H_RATIO = 1.33f
/** 标题一行（`fontSmall`）。 */
private const val TITLE_H = FONT_SMALL * LINE_H_RATIO
/** 错误行一行（`fontTiny`；它比 [ERROR_MIN_H] 略高，所以"永远占位"的高度就是它）。 */
private const val ERROR_H_ONE = FONT_TINY * LINE_H_RATIO
/** 错误行两行 —— `maxLines = 2`，校验失败那句"名字里不能有 …"真的会折行，这就是**最坏情况**。 */
private const val ERROR_H_TWO = 2f * ERROR_H_ONE
/** 路径小字一行（`fontTiny`）。 */
private const val PATH_H = FONT_TINY * LINE_H_RATIO

/** 内容 + 上下内边距的总高（错误行**一行**时）：标题 / 错误行 / 输入框 / 导出 / ×✓ / 路径小字。 */
private const val CARD_H_ONE_ERROR =
    CARD_PAD_V * 2f + // 上下内边距
        TITLE_H + // 标题
        CARD_GAP +
        ERROR_H_ONE + // 错误行（一行）
        CARD_GAP_FIELD +
        FIELD_H + // 输入框
        CARD_GAP_FIELD +
        FIELD_H + // 导出（用户要求：**不和 ×✓ 同一行**，自己一行）
        CARD_GAP_FIELD +
        FIELD_H + // × / ✓
        CARD_GAP +
        PATH_H // 路径小字

/**
 * 卡片的**最坏高度**（错误行占两行时）—— 它同时是卡片的高度上限（`heightIn(max = …)`）。
 *
 * 为什么要**卡一个上限**：下面"四角在圆内"的宽度上限是按这个高度算的，只有高度真的不会
 * 超过它，那个上限才成立。系统字体放大时（`WatchMetrics.sp` 会跟着 fontScale 涨）内容可能
 * 更高，那时卡片**不再长高、改为内部滚动**（`verticalScroll` 本来就在，见 KDoc 第 2 条），
 * 于是四角**一定**留在圆内 —— 这条是把"几何算对"变成"几何保证"的那一步。
 */
private const val CARD_H_MAX = CARD_H_ONE_ERROR - ERROR_H_ONE + ERROR_H_TWO

/**
 * 卡片**居中**时最坏的顶沿位置（占屏高）= `(1 − 最坏高度) / 2`。
 *
 * 为什么必须是"居中"：卡片高度固定时，能用的宽度由**上沿和下沿里更靠边的那一处**决定，
 * 而 `circleWidthFraction` 在中间最大、越往上下越小 —— 只有上下沿**对称**（也就是居中）时，
 * "更靠边的那一处"离圆心最近、可用宽度最大。所以"把上沿固定到 0.16~0.20 屏高、
 * 让高度变大也不顶到收窄区"这条路**是反的**（上沿钉在 0.16 时下沿到了 0.911，
 * 那里的弦宽只剩 `cwf(0.911) = 0.5695 → 132.7dp`）。算式见 [NameDialog] 的 KDoc。
 */
private const val CARD_TOP_WORST = (1f - CARD_H_MAX) / 2f

/**
 * 圆角矩形的"**肩点**"离圆心的**垂直**距离（占屏高）。
 *
 * 肩点 = 卡片左右最外的那两个点 `(±W/2, 上沿 + r)`：圆角把尖角削掉之后，**它们才是卡片上
 * 离圆心最远的点**（尖角本身已经不存在了）。`0.5` 是圆心所在的屏高位置。
 */
private const val SHOULDER_DY = 0.5f - CARD_TOP_WORST - CARD_CORNER

/**
 * "四角仍在圆内"给出的**卡片宽度上限**（占屏宽）。
 *
 * 把 `sizeDp` 归一化成 1（圆心 0.5、半径 0.5），肩点落在圆内就是：
 * `(W/2)² + SHOULDER_DY² ≤ (1/2)²` → `W ≤ 2 × sqrt(1/4 − SHOULDER_DY²)`。
 *
 * ⚠️ 上一版这里写的是 `circleWidthFraction(CARD_ARC_Y) × 0.93`，那用的是**尖角矩形**模型
 * （要求"整条顶边都在圆内"）—— 对圆角 19.8dp 的卡片来说**过严**：它只给 153.9dp，
 * 而肩点模型给 189.5dp。现在按卡片**真实的形状**算（实算见 [NameDialog] 的 KDoc）。
 */
private val CARD_WIDTH_LIMIT: Float = 2f * sqrt(0.25f - SHOULDER_DY * SHOULDER_DY)

/** 宽度上限上再留的余量（和 `FunctionList` 的 `ARC_MARGIN` / `SettingsScreen` 的 `WIDTH_MARGIN` 同一档）。 */
private const val CARD_ARC_MARGIN = 0.93f
// ⚠️ 这里原来还有一个 `private const val SQUARE_CARD_WIDTH = 0.92f`（"非圆屏就 0.92 × 短边"）
// —— 上一轮**删掉**了。它是 README「⚠️ 硬约定：按键 / 可点控件的宽度一律按真实可用宽度自适应」
// 那一节点名的反例（`Modifier.width(metrics.sizeDp * 0.90f)` 那一类）：手机上短边 == 屏宽看不出
// 问题，横屏/长条屏上卡片就会被压成中间一根窄柱。非圆屏现在一律走
// `ScreenGeometry.rowWidth(metrics, 1f, false)` = **真实屏宽 − 左右各 `ROW_SIDE_INSET`(3%)**。

// ---- 「导出文件」那一行的进场动画（只有这一行存在时才有意义）----
/**
 * 比卡片晚多久开始动。
 *
 * 卡片进场是 [DIALOG_IN_MS] = 180ms，这里等大半程（90ms）再动 —— 卡片先站稳、
 * 这一行再"长出来"，两段动画不会重叠到看不清谁在动。
 */
private const val EXPORT_IN_DELAY_MS = 90L
/** 这一行自己的进场时长（和卡片那 180ms 同一个量级：用户给的区间是 140~220ms）。 */
private const val EXPORT_IN_MS = 200
/** 进场时从下方多少屏高浮上来：0.05 × 233dp ≈ 11.7dp。 */
private const val EXPORT_IN_RISE = 0.05f

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
 * ## 宽度：按圆屏弧宽自适应，而且按卡片的**真实形状**（圆角矩形）算
 *
 * 表壳是圆的，卡片越靠上下越容易被切掉，所以宽度这样算（基准 233dp / 466px 圆表）：
 *
 *   `宽 = sizeDp × 上限 × 0.93 = 233dp × 0.8133 × 0.93 ≈ **176.2dp**（≈ 0.756 屏宽）`
 *
 * ### 一、卡片（最坏）多高：逐项加起来
 *
 * 常数都在上面（`CARD_H_ONE_ERROR` / `CARD_H_MAX`），基准 233dp 上的实算：
 *
 *   `上下内边距 0.032×2 + 标题 0.052×1.33 + 0.014 + 错误行(一行) 0.042×1.33 + 0.016
 *    + 输入框 0.125 + 0.016 + 导出 0.125 + 0.016 + ×✓ 0.125 + 0.014 + 路径小字 0.042×1.33
 *    = **0.6959 屏高 ≈ 162.1dp**`（错误行**两行**时 `0.6959 − 0.0559 + 0.1117 = 0.7518
 *    屏高 ≈ **175.2dp**，也就是 `CARD_H_MAX`）。行高按"字号 × 1.33"估，**估大**是刻意的
 *   （宁可把卡片算高一点，四角只会更安全）。
 *
 * ### 二、上沿落在哪：**居中是可证明最优的**
 *
 * 能用的宽度由"上沿和下沿里更靠边的那一处"决定，而 `circleWidthFraction` 在圆心最大、
 * 越往上下越小 —— 只有上下沿**对称**（居中）时那处离圆心最近、可用宽度最大。
 * 所以最坏情况（错误行两行）上沿落在 `(1 − 0.7518)/2 = **0.1241**`。
 *
 * 顺带把另一条路堵死：**"上沿固定在 0.16~0.20 屏高"比居中更糟** —— 上沿钉在 0.16 时
 * 下沿到了 `0.16 + 0.7518 = 0.9118`，那里的弦宽只剩 `cwf(0.9118) = 0.5673 → 132.2dp`。
 * 卡片是**一整块矩形**，顶和底一样会被切，把它往下挪只是把切角从上面换到下面。
 *
 * ### 三、四角在不在圆内：按圆角矩形算（这是本轮唯一改动的几何）
 *
 * 卡片是 `RoundedCornerShape(0.085 × sizeDp ≈ 19.8dp)`，尖角**已经被削掉**，所以卡片上
 * 离圆心最远的点是两个"**肩点**" `(±W/2, 上沿 + r)`。把 `sizeDp` 归一化成 1
 * （圆心 0.5、半径 0.5），肩点落在圆内就是 `(W/2)² + SHOULDER_DY² ≤ 0.25`：
 *
 *   * `SHOULDER_DY = 0.5 − 0.1241 − 0.085 = 0.2909`；
 *   * `W ≤ 2 × sqrt(0.25 − 0.2909²) = 0.8133 × sizeDp = **189.5dp**`；
 *   * 再乘 0.93 余量 → **176.2dp**（这一步就是 `CARD_WIDTH_LIMIT × CARD_ARC_MARGIN`）。
 *
 * 两条**交叉验算**（都能自己按一遍）：
 *
 *   * **尖角模型的保守界**（上一版用的那个）：`sizeDp × cwf(0.1241) = 233 × 0.6595 = 153.7dp`
 *     —— 它要求"整条顶边都在圆内"，而圆角已经把两个尖角各削掉 19.8dp，所以**过严**；
 *   * **顶边那条直边**给出的界：`153.7 + 2×19.8 = 193.3dp`（比肩点那条松，所以最终取肩点）。
 *
 * 结论：按真实形状算，**卡片不用收窄**（189.5dp 的上限比现在的 176.2dp 还宽 13dp），
 * 所以 ×/✓ 回到平分整行之后各 **74.8dp**，和"三键同行"之前基本一样。
 *
 * **非圆屏**（手机 / 方表 / 横屏）走 `ScreenGeometry.rowWidth(metrics, 1f, false)` ——
 * 也就是 README「⚠️ 硬约定」那一节唯一那份实现：**真实屏宽 − 左右各 `ROW_SIDE_INSET`(3%)**。
 * 手机 411dp 宽 → **386.3dp**（原来写死成 `0.92 × 短边` = 378.1dp，那正是"拿短边当宽度
 * 基准"的坑：横屏手机上短边只有 411dp 而屏宽 850dp，卡片会被压成中间一根窄柱）。
 *
 * 卡片高度**不写死**：`heightIn(min = 0.20 屏高, max = CARD_H_MAX ≈ 0.7518 屏高)`。
 * 下限只是"空内容也不像一条线"；**上限就是上面那条宽度算式成立的前提** ——
 * 字体放大时内容更高，卡片不再长高而改为**内部滚动**，四角因此一定留在圆内。
 * （老代码写死 `.height(0.212 屏高 ≈ 49dp)`，而内容比卡片高一倍多，`Column` 把排在后面的
 * 兄弟压成 0 高 —— 那就是用户报的"没写确定和取消按键，退不出来"。）
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
 * ## 版面顺序（上 → 下）：标题 → 错误行 → 输入框 → **导出（独占一行）** → 取消(×) / 确定(✓) → 路径小字
 *
 * 用户原话（第一版）："浮窗上的取消键和确定键应该在输入框下面，取消和确定的图标应该是 × 和 ✓"；
 * 用户原话（本轮）："保存按钮和 ×✓ 不要在同一行，另起一行。" —— 所以导出**自己占一行**，
 * ×/✓ 回到**平分整行**。
 *
 * 导出那一行放在 ×/✓ **上面**（紧挨输入框）的理由：从输入框往下读是
 * "**给这个文件起名 → 拿这个文件做点事（导出）→ 结束这次编辑（× / ✓）**" ——
 * 导出是"对文件做的事"，×/✓ 是收尾；`pathHint` 仍然在最下面一行告诉用户文件在哪。
 * （×/✓ 依然在输入框**下面**，只是中间隔了一行，上一版的那条要求没变。）
 *
 * 具体数值（基准 233dp / 466px，卡片宽 **176.2dp** ≈ 0.756 屏宽、内宽 **155.2dp**）：
 *
 *   * 卡片左右内边距 `0.045 × sizeDp ≈ 10.5dp`、上下 `0.032 ≈ 7.5dp`；
 *   * 标题：`fontSmall`（≈12.1sp）、**居中**（卡片居中，标题居中最稳）、单行省略；
 *   * 错误行：`fontTiny`（≈9.8sp）、`MaterialTheme.colors.error`、居中、最多两行，
 *     **永远占位**（没错误时是一行空文字 + `heightIn(min = 0.055 × sizeDp ≈ 12.8dp)`）：
 *     校验失败时卡片只长高一点点，×/✓ 不会整体往下跳；
 *   * **三行同高** `FIELD_H = 0.125 × sizeDp ≈ 29.1dp`：输入框 / **导出** / ×✓；
 *     行与行之间 `0.016 × sizeDp ≈ 3.7dp`（×✓ 与路径小字之间是 `0.014 ≈ 3.3dp`）；
 *   * **导出那一行是整行宽的胶囊**（`fillMaxWidth()` = 155.2dp），里面是
 *     `⤓ 图标(20.5dp) + 4.7dp 缝 + 文字"导出文件"`（`fontSmall`，4 个字 ≈ 48.5dp，合计 ≈ 74dp，
 *     只占胶囊一半）—— **满宽 + 图标 + 文字**：这是全屏最容易被忽略的一颗键
 *     （用户可能根本不知道文件能拿出来），所以既给它整行、也把话写在键上；
 *     而"一颗居中窄键"会和上下两条整行（输入框、×/✓ 行）的节奏打架；
 *   * **取消 / 确定平分整行**（`weight(1f)` × 2，缝 `0.024 × sizeDp ≈ 5.6dp`）：
 *     233dp 圆表上各 **74.8dp × 29.1dp**（`(155.2 − 5.6) / 2`）—— 取消在左、确定在右，
 *     底色分别是 `normal` 深灰 / `accent` 强调色；**导出用 `normal`**（旁路动作，
 *     不动对话框的结论，三颗里只有"确定"是强调色）；
 *   * 图标 `Icons.Filled.Close` / `Icons.Filled.Done` 都在 `material-icons-core` 里
 *     （**不引** `material-icons-extended`，那会让 APK 从 ~1.9MB 涨到 ~50MB）；
 *     导出那颗的图标 core 里没有（`Download` 不在那 49 个里），所以**手画**：见 [ExportIcon]，
 *     做法照抄 `SavesScreen.kt` 的 `FolderIcon`（用 core 里本来就在的 `materialIcon` /
 *     `materialPath` 自建矢量，体积代价为零）；图标边长一律 `0.088 × sizeDp ≈ 20.5dp`；
 *   * 卡片圆角 `RoundedCornerShape(0.085 × sizeDp ≈ 19.8dp)`；配色沿用 `KeyPalette`：
 *     底 `bubble`（#F21F2124）+ 1dp `bubbleBorder`（20% 白）描边，图标与文字一律纯白；
 *   * 最下面一行是 `pathHint` 小字（存盘位置 / 原名），`fontTiny` + 50% 白。
 *
 * ## 「导出文件」那一行（**只给"重命名"这一种用法**）
 *
 * 用户原话："再给文件列表里面的重命名浮窗里面再加个导出文件的按钮，点击之后会在下载文件夹
 * 里面的 DrawFunc 文件夹里面导出这个文件，如果没有 DrawFunc 文件夹自动创建。"
 *
 * 它由 [onExport] 这个**可选参数**控制：**默认 null 时这一行根本不进组合** ——
 * `GraphScreen.kt` 里"保存函数文件？"那个调用点一个字都不用改，观感逐像素不变。
 * [onExport] 的参数是**输入框里此刻的文本**：名字的状态在这个函数里（`name`），
 * 而"写进下载目录"那件事在调用方（它才知道这是哪个文件、也才有 toast）——
 * 所以这里把草稿递出去，而不是让调用方再猜一遍用户在框里打了什么。
 *
 * ### 三块屏的完整数值（导出**独占一行**之后重算的）
 *
 * | 屏 | 卡片宽 | 卡片高（错误行一行 / 两行） | 上沿 y（两行时） | 四角在圆内？ | × / ✓ 各 | 导出行 |
 * |---|---|---|---|---|---|---|
 * | **圆表 233dp**（主设备） | `233 × 0.8133 × 0.93 = **176.2dp**` | **162.1dp = 0.696 屏高** / **175.2dp = 0.752**（= 上限） | 0.1241 | ✅ 见下面那笔账（两行时每侧余 **6.6dp**，一行时余 9.2dp） | **74.8 × 29.1dp** | **155.2 × 29.1dp** |
 * | **方表 200dp** | `200 − 2×3% = **188.0dp**` | `0.696 × 200 = 139.2dp` / `150.3dp` | — | ✅ 方屏没有"圆内弦宽"这回事；占屏高 70% / 75%，**不溢出** | **82.6 × 25.0dp** | **170.0 × 25.0dp** |
 * | **手机 411×850dp** | `411 − 2×3% = **386.3dp**` | `0.696 × 411 = 286.0dp` / `309.0dp` | — | ✅ 同上；占屏高 34% / 36%，**不溢出** | **169.7 × 51.4dp** | **349.3 × 51.4dp** |
 *
 * 圆表 233dp 四角那笔账（最坏情况：错误行两行、`W = 176.2dp`、`r = 19.8dp`、
 * 上沿 `T = 28.9dp`、圆半径 `R = 116.5dp`，圆心在 y = 116.5）：
 *
 *   * **肩点** `(±88.1, T + r = 48.7)`：`sqrt(88.1² + (116.5 − 48.7)²) = 111.2 ≤ 116.5` ✅ **余 5.3dp**
 *   * **顶边端** `(±68.3, T = 28.9)`：`sqrt(68.3² + (116.5 − 28.9)²) = 111.1 ≤ 116.5` ✅ 余 5.4dp
 *   * **宽度上限**：`2 × sqrt(116.5² − 67.8²) = 189.5dp`，实际取 176.2dp → **每侧再留 6.6dp** ✅
 *   * 错误行一行时（卡片矮 13.1dp、上沿到 35.4）：肩点距离 107.3 → **余 9.2dp** ✅（上限还放宽到 198.2dp）
 *   * 字体放大到 1.3 倍：内容变高，但卡片被 `heightIn(max = CARD_H_MAX)` 钉住（改为内部滚动），
 *     上沿仍在 0.1241 → 上限仍是 189.5dp ✅（这就是那个上限的用处）
 *
 * **要不要把卡片收窄**：按真实形状算**不用** —— 189.5dp 的上限比现在的 176.2dp 还宽 13dp。
 * 上一轮我给的"要收窄到 ≈144dp"是**尖角矩形**那条保守界的结论（`153.9 × 0.93 = 143dp`）；
 * 尖角矩形把两个已经被圆角削掉的角也算进约束里，所以偏严（推导见上面"宽度"一节第三节）。
 * 结论：×/✓ 回到平分整行后各 **74.8dp × 29.1dp**（和"三键同行"之前的 74.9dp 一致），
 * 导出那一行 **155.2 × 29.1dp**，三块屏上都是"比工具条那颗图标键（41.9dp）宽"的量级。
 *
 * ### 动画（这一行的两条 + 按压）
 *
 * | 元素 | 动画 | 曲线 / 时长 |
 * |---|---|---|
 * | 整行进场 | 淡入 + 从下方 `0.05 × sizeDp ≈ 11.7dp` 浮上来，**比卡片晚 90ms** | `tween(200)` `FastOutSlowInEasing` |
 * | 导出中 | 渐暗到 45% + 不可点（和 `saving` 时 ×/✓ 渐暗同一档、同一个 160ms） | `tween(160)` 同上 |
 * | 按下 | 缩到 0.94 + 一层 14% 白光 | `spring(0.45, 900)` / `tween(120)`（`rememberPressFeedback`） |
 *
 * 进场**只动 alpha + 位移、不缩放**：这是一条整行宽的胶囊，缩放会像"整块纸片在呼吸"；
 * 而列表行（`SavesScreen` 的动画清单）、撤销条那几条整行动画也都是"淡入 + 位移"，一致。
 * 它自己一条时间线（`Animatable`）而不是复用卡片的 [progress]：卡片全程只有 180ms，
 * 跟着走的话这一行只剩 ≈99ms 可动，短到看不出来（那是"和卡片动画打架"的另一种形式）。
 * alpha 与 translationY **只在 `graphicsLayer` 的 lambda 里读**，所以这 200ms 一帧重组都不会发生。
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
 * | 导出那一行在 exporting 时 | 渐暗到 45%（和 saving 时 ×/✓ 同一档） | `tween(160)` 同上 |
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
    /**
     * 「导出文件」那一行的回调；**默认 null = 这一行完全不出现**（进不了组合，连动画都没有）。
     *
     * 参数是**输入框里此刻的文本**（用户可能刚改完名字还没按 ✓）—— 名字的状态在这个函数里，
     * 而"写进下载目录"那件事在调用方（它才有 `Context` 和 toast），所以草稿要递出去。
     *
     * 目前只有 `SavesScreen.kt` 的"重命名"传它；`GraphScreen.kt` 的"保存函数文件？"
     * 不传，于是那边一行都不多、观感逐像素不变。
     */
    onExport: ((String) -> Unit)? = null,
    /**
     * 导出正在进行中（= 那一行渐暗到 45% 并暂时点不动，拦住连点）。
     *
     * 由**调用方**置位/复位，因为导出是挂在调用方作用域上的挂起写盘
     * （浮窗自己的 `rememberCoroutineScope` 会在它退出组合时被取消，
     * 那可能留下一条 `IS_PENDING = 1` 的半截记录）。
     */
    exporting: Boolean = false,
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
    // 圆屏的**宽度比例**：按"四角仍在圆内"那条算式（[CARD_WIDTH_LIMIT]，圆角矩形的**真实形状**）
    // 再留 7% 余量。推导、三块屏的实算、以及"为什么居中是最优的"都写在 KDoc 的"宽度"一节。
    // 非圆屏用不到这个比例 —— 那边走 `ScreenGeometry.rowWidth(...)`（真实屏宽 − 左右各 3%），
    // 卡片宽度在下面 `BoxWithConstraints` 里一次算出来（**全仓唯一那份宽度实现**）。
    val roundWidthFraction = CARD_WIDTH_LIMIT * CARD_ARC_MARGIN
    val cardShape = RoundedCornerShape(metrics.sizeDp * CARD_CORNER)
    /** 输入框 / **导出** / ×✓ 三行的**共同高度**（三行一样高，卡片才整齐）。 */
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
    // 导出中那颗键渐暗：同一个 0.45、同一个 160ms —— 三颗键"忙起来"必须是同一种观感
    val exportKeyAlpha by animateFloatAsState(
        targetValue = if (exporting) 0.45f else 1f,
        animationSpec = tween(durationMillis = FOCUS_MS, easing = FastOutSlowInEasing)
    )
    /** 导出那一行进场上浮的距离（像素 —— `graphicsLayer` 的 `translationY` 吃像素）。 */
    val exportRisePx = with(LocalDensity.current) { (metrics.sizeDp * EXPORT_IN_RISE).toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 用户要求：用 IME insets 把内容顶上来。卡片是**居中**的，所以这回它真的
            // 能把整块卡片往上推（贴顶布局下只压容器底部，顶不开卡片）。
            .imePadding()
    ) {
        // 卡片宽度：**只从这一处出**（README「⚠️ 硬约定」那一节要求的那份实现）。
        //   * 圆屏 → `sizeDp × 弧宽比例`：和改动前**逐像素相同**（那一支一个数都没动）；
        //   * 非圆屏 → `rowWidth(...)` = **真实屏宽 − 左右各 3%**（手机 386.3dp）。
        // 为什么必须在这里量：非圆屏的基准是**真实屏宽**，只有 `BoxWithConstraints`
        // 拿得到它（`metrics.sizeDp` 在横屏手机上是短边 411dp，而屏宽是 850dp）。
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val cardWidth = geometry.rowWidth(metrics, roundWidthFraction, isRound)

        // ---- 导出键的进场：自己一条时间线（0 = 还没长出来，1 = 站好）----
        // 用 `Animatable` 而不是 `animateFloatAsState`：它的值**只在 graphicsLayer 里读**，
        // 于是这 200ms 一帧重组都不会发生（和卡片那条 `progress` 同一套做法）。
        // 晚 `EXPORT_IN_DELAY_MS` 起步，是为了不和卡片那 180ms 重叠到看不清谁在动。
        val exportEntry = remember { Animatable(if (onExport == null) 1f else 0f) }
        LaunchedEffect(Unit) {
            if (onExport != null) {
                delay(EXPORT_IN_DELAY_MS)
                exportEntry.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = EXPORT_IN_MS, easing = FastOutSlowInEasing)
                )
            }
        }

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
                // 高度：下限（"空内容也不像一条细线"）+ **上限**（= "四角在圆内"那条宽度算式的前提）。
                // ⚠️ 顺序要紧：`heightIn` 必须在 `verticalScroll` **外面**。放在里面的话，高度上限
                // 会把内容直接截掉、而且滚不动（滚动容器的"内容高度"先被夹住了）；放在外面，
                // 超高时卡片就停在上限、内容自己可以滚上来。
                .heightIn(
                    min = metrics.sizeDp * CARD_MIN_H,
                    max = metrics.sizeDp * CARD_H_MAX
                )
                // 兜底：输入法把可用区压得比卡片还矮、或者系统字体放大到内容超过上限时，
                // 卡片变成可滚的（见 KDoc 的"键盘遮挡"第 2 条）—— 四角因此一定还在圆内
                .verticalScroll(rememberScrollState())
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

            // ---- ④ 导出：**独占一行**（用户要求"不要和 ×✓ 在同一行"），排在 ×/✓ **上面** ----
            //
            // 为什么在 ×/✓ **上面**（紧挨输入框）：从输入框往下读是
            // "给这个文件起名 → 拿这个文件做点事（导出）→ 结束这次编辑（×/✓）" ——
            // 导出是"对文件做的事"、×/✓ 是收尾；而 ×/✓ 仍然在输入框**下面**（只是隔了一行）。
            // 为什么是**整行宽的胶囊 + 图标 + 文字**：这是全屏最容易被忽略的一颗键
            // （用户可能不知道文件能拿出来），满宽好按、文字"导出文件"直接把话说明白；
            // 一颗居中窄键则会和上下两条整行（输入框、×/✓ 行）的节奏打架。
            val exportAction = onExport
            if (exportAction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        // 进场只动 alpha + 位移（整行宽的东西一缩放就像"整块纸片在呼吸"）；
                        // 两个值都只在这里读 → 这 200ms 一帧重组都不会发生
                        .graphicsLayer {
                            val e = exportEntry.value
                            alpha = e
                            translationY = (1f - e) * exportRisePx
                        }
                ) {
                    DialogIconKey(
                        icon = ExportIcon,
                        contentDescription = "导出文件",
                        label = EXPORT_LABEL,
                        metrics = metrics,
                        // 旁路动作（不改对话框的结论）→ 普通键底色；三颗里只有"确定"用强调色
                        containerColor = KeyPalette.normal,
                        // 保存中 / 正在导出时都点不动（连点会往 MediaStore 插两行）
                        enabled = !saving && !exporting,
                        alpha = exportKeyAlpha,
                        modifier = Modifier.fillMaxSize(),
                        // 把**输入框里此刻的文本**递出去（用户可能刚改完还没按 ✓）；
                        // 空 / 非法怎么办不在这里判断 —— 那是 `FileExport.planExport` 的事
                        onClick = { exportAction(name) }
                    )
                }

                Spacer(Modifier.height(metrics.sizeDp * CARD_GAP_FIELD))
            }

            // ---- ⑤ 取消(×) / 确定(✓)：**平分整行**，排在导出下面（用户要求）----
            //
            // 233dp 圆表上各 ≈ **74.8dp × 29.1dp**（`(155.2 − 5.6) / 2`），比工具条那颗图标键
            // （`metrics.iconButton` = 41.9dp）宽得多，圆表上按得中；高度和输入框一样（`rowHeight`）；
            // 按下有缩放 + 白光反馈（见 [DialogIconKey]）。
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

            // ---- ⑥ 存盘位置 / 原名：最后一行小字 ----
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
 * 对话框里的**胶囊键**（取消 × / 确定 ✓ / 导出 ⤓）：胶囊底 + 按压缩放 + 一层白光高亮。
 *
 * 为什么不用 `Common.kt` 的 `WatchIconButton`：那个是**圆角方形**的固定边长
 * （`metrics.iconButton`，用户明确要求过"按键变成原来的样子"，不能改它），
 * 而这里要的是"和输入框一样高"的胶囊键 —— 用户的要求是"取消和确定的图标应该是 × 和 ✓"。
 * 按压反馈仍然是**同一套** `rememberPressFeedback`（缩放 + 白光，且只动图层）。
 *
 * [label] 非 null 时变成"图标 + 文字"的键（导出那一行用它：整行宽的胶囊上只放一个图标
 * 会显得空，而且"导出文件"这四个字比图标更能说明它是干什么的）。图标与文字之间的缝是
 * [EXPORT_LABEL_GAP]。
 *
 * @param alpha 由调用方做 `animateFloatAsState`（[NameDialog] 里 saving 时 ×/✓ 一起渐暗、
 *              导出中那一行自己渐暗）
 * @param enabled 保存中 / 导出中置灰（`clickable(enabled = false)`：连按压反馈都不给）
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
    /** 非 null → 图标右边再放一行文字（导出那一行用）。 */
    label: String? = null,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(scaleAmount = KEY_PRESS_SCALE, glowAmount = KEY_GLOW)

    Surface(
        color = containerColor,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
            // 高度由外面那个行容器夹住（`rowHeight`），这里撑满即可
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
                // 保存中 / 导出中渐暗：值由调用方动画驱动，这里只写图层、不参与布局
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
            if (label == null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = KeyPalette.text,
                    modifier = Modifier.size(metrics.sizeDp * KEY_ICON)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        // 文字本身就是标签，图标不用再抢一次读屏（`contentDescription` 仍然由
                        // 调用方传，供"只有图标"的那几颗键用）
                        contentDescription = null,
                        tint = KeyPalette.text,
                        modifier = Modifier.size(metrics.sizeDp * KEY_ICON)
                    )
                    Spacer(Modifier.width(metrics.sizeDp * EXPORT_LABEL_GAP))
                    Text(
                        text = label,
                        fontSize = metrics.fontSmall,
                        color = KeyPalette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * **「导出文件」的图标**：一个向下箭头 + 下面一个托盘（"存进盘里"）。
 *
 * 为什么自己画而不是用现成的：
 *   * `material-icons-core`（1.7.0 里只有 49 个图标）**没有** `Download` ——
 *     有的是 `Share`，但那个语义是"分享给别的 App"，和"存进下载文件夹"不是一件事，
 *     手表上用户一眼就要能分出"这颗键是往下载里放"；
 *   * `material-icons-extended` 有 `Download`，但它会让 APK 从 ~1.9MB 涨到 ~50MB
 *     （用户明确禁止，README「二、5」记着这件事）。
 *
 * 所以照 `SavesScreen.kt` 的 `FolderIcon` 那套做法：用 core 里本来就在类路径上的
 * `materialIcon` / `materialPath` **自建一个矢量**，效果与官方图标一致、体积代价为零。
 * 路径数据取自 Material 官方 `file_download` 的 24dp 图形：
 * 箭身是 `x ∈ [5,19] / y ∈ [3,9]` 那块"宽扁的柄"、尖端落在 `(12,16)`；
 * 托盘是底下 `14 × 2` 的那条横杠（`y ∈ [18,20]`）。20.5dp 的边长下也读得出"往下存"。
 */
internal val ExportIcon: ImageVector by lazy {
    materialIcon(name = "FunctionDrawer.Export") {
        materialPath {
            // 箭头：从右肩 (19,9) 绕箭身一圈，尖端落在 (12,16)
            moveTo(19f, 9f)
            horizontalLineTo(15f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            horizontalLineTo(5f)
            lineTo(12f, 16f)
            lineTo(19f, 9f)
            close()
        }
        materialPath {
            // 托盘：底部那条横杠
            moveTo(5f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(19f)
            verticalLineTo(18f)
            horizontalLineTo(5f)
            close()
        }
    }
}
