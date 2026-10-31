// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    // imePadding()/heightIn()/verticalScroll() 那批布局 API 的标记 ——
    // 这一屏自己现在不用了（命名/重命名浮窗的键盘处理搬进了 `NameDialog`），
    // 但留着它没有任何代价，也免得以后再碰这些 API 时又踩一遍。
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.functiondrawer.watch.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.round

// ---------------------------------------------------------------------------
// 这一屏的几何常数**整套照抄 `FunctionList.kt`** —— 用户要求"行样式、弧宽收窄、
// 入场动画都照抄 FunctionList（同一屏宽下观感要一致）"，所以数值一个都没改，
// 连注释里那句"为什么"也照搬（它解释的是同一件事）。
// 没有直接把那些常数从 FunctionList 里 import 出来：它们是 `private const`，
// 而这一轮的禁改清单里包含那个文件 —— 复制一份数值是唯一不越界的做法，
// 代价是将来改分区要改三处（FunctionList / FunctionsScreen / 这里，已写进报告）。
// ---------------------------------------------------------------------------
private const val LIST_TOP = 0.245f
private const val LIST_BOTTOM = 0.95f
private const val ROW_H = 0.15f

// ---- 顶部工具条（左上返回/取消、右上打开/删除）的几何 ----
//
// 用户原话："文件界面的按键……没有和函数列表的一样自动适配屏幕宽度。"
// 所以这一行**逐字照抄 `FunctionList.toolbarWidthFraction`**：行所在的上下沿 →
// 圆内宽度 → 乘 [ARC_MARGIN] → 夹在 [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间。
// 两屏（加上「功能界面」就是三屏）用的是同一组数，三处的两颗按钮因此**严丝合缝地对齐**。
// 原来这里写的是 `circleWidthFraction(0.16f) * 0.93f` 这种"抄过来的魔数"：
// 按行中心算会让按钮的外上角落到表壳外面（233dp 基准上真的看不见那一小块）。
/** 工具条行顶（占屏高）。和函数列表同一个数。 */
private const val TOOLBAR_TOP = 0.10f
/** 工具条行高（占屏高）。0.125 × 233dp ≈ 29.1dp。 */
private const val TOOLBAR_H = 0.125f
/** 图标按钮边长占屏比 —— **必须等于 `WatchMetrics.iconButton` 的 0.18**。 */
private const val ICON_BTN_H = 0.18f

// ---- 入场（和 FunctionList 完全同一套数值：220ms 淡入 + 上移 5% 行高，每行晚 45ms）----
private const val ROW_IN_MS = 220
private const val STAGGER_MS = 45
private const val ROW_IN_SLIDE = 0.05f
/** 顶部工具条这两颗按钮的入场：比列表行略快一点，先它站稳、行再依次跟上。 */
private const val TOOLBAR_IN_MS = 180

// ---- 整行按压 ----
private const val ROW_PRESS_SCALE = 0.03f
private const val ROW_PRESS_DIM = 0.10f

// ---- 行的宽度（圆弧缩放）----
private const val SQUARE_ROW_WIDTH = 0.90f
private const val MIN_ROW_WIDTH = 0.52f
private const val ARC_MARGIN = 0.95f

// ---- 动画（用户要求"以上所有功能的添加或修改都需要适合这个软件的丰富美观的动画"）----
/**
 * 行的**选中 / 取消选中**：底色在 `KeyPalette.normal` 和 `accent` 之间渐变。
 *
 * 160ms 和「功能界面」选中 chip 的 `CHIP_SEL_MS` 是同一个数 —— 全应用的"选中"
 * 都是同一种节奏；直接换色会"啪"地一下，圆表上很扎眼。
 */
private const val SELECT_MS = 160
/** 右上角那个 ✓ 角标的出现/消失（淡入 + 从 0.6 放大到 1）。 */
private const val BADGE_MS = 170
/** 角标缩放动画的起点：0.6 → 1.0。 */
private const val BADGE_FROM = 0.6f
/** 工具条按键"换岗"（← ↔ ×、✓ ↔ 🗑）的淡入 / 淡出：交叉一下，别硬切。 */
private const val KEY_IN_MS = 160
private const val KEY_OUT_MS = 120
/** 换岗时按键的缩放起点（两颗按键交叉着"长出来 / 缩回去"）。 */
private const val KEY_SWAP_FROM = 0.7f
/**
 * **删掉的行先自己淡出、再真的从列表里摘掉**的时长。
 *
 * 为什么要有这一段：`refresh()` 一调，行就凭空没了 —— 用户要求"删除要有过渡"。
 * 这里让选中那几行先播 180ms 的淡出 + 缩小（`RowEntrance` 的 exit），
 * 播完才重新读目录；剩下的行往上合拢由 `Modifier.animateItem` 的位移动画接管。
 */
private const val ROW_OUT_MS = 180L
/** 行位移动画（`animateItem(placementSpec)`）的时长：和入场 220ms 同一个量级。 */
private const val PLACEMENT_MS = 220

/** 表冠一格对应滚动多少像素（和 FunctionList 同一个值和同一个理由）。 */
private const val ROTARY_SCROLL_FACTOR = 1.0f

/**
 * 双击判定窗口（毫秒），300 和系统的 `ViewConfiguration.getDoubleTapTimeout()` 一致。
 *
 * 为什么要自己算而不是用 `combinedClickable(onDoubleClick = …)`：
 * 后者一旦传了 `onDoubleClick`，**单击回调会被系统延后**这段时间（它要等一等看有没有第二下），
 * 而"点一下选中"是这一屏最高频的动作，慢 300ms 在手表上很明显。
 * 自己数时间戳就能做到"第一下立刻选中、第二下（300ms 内）才当重命名"。
 */
private const val DOUBLE_TAP_MS = 300L

// 这里原来有一个 `UNDO_MS`（删除后那颗"已删除 N 个 · 点此撤销"胶囊停留 2 秒）。
// 用户这一轮要求"把所有撤销的 **toast 提示**都给删掉" —— 这一屏那颗胶囊就是一条
// 带撤销的提示，所以它连同 `UNDO_MS` / `deleted` / `lastBatch` 整套一起删掉了。
// **删除本身的过渡一点没动**（那套是 `dying` + `ROW_OUT_MS`，和撤销无关，见 [deleteSelected]）。

/**
 * **文件夹图标**（用户要求"图标是文件夹"）。
 *
 * 为什么自己画一个而不是 `Icons.Filled.Folder`：**它不在 `material-icons-core` 里**。
 * 那个库（1.7.0）只有 49 个图标（Add/Delete/Settings/… 那批常用的），
 * `Folder` 属于 `material-icons-extended` —— 而引它会让 APK 从 ~1.9MB 涨到 ~50MB
 * （用户明确禁止）。所以这里按 Material 官方 `folder` 图标的 24dp 路径数据
 * 自己建一个矢量（`materialIcon` / `materialPath` 来自 core，本来就在类路径上），
 * 效果和 `Icons.Filled.Folder` 一模一样，体积代价是零。
 */
internal val FolderIcon: ImageVector by lazy {
    materialIcon(name = "FunctionDrawer.Folder") {
        materialPath {
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            // 左侧的圆角：c-1.1 0 -1.99 0.9 -1.99 2
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(2f, 18f)
            // 左下角 + 底边 + 右下角 + 右边
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            // 右上角到"文件夹盖"再到左上角的那一段斜边
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            close()
        }
    }
}

/**
 * 「函数文件」列表（第二屏左上角那颗文件夹按钮点进来）。
 *
 * **没有标题**（用户要求；这个 App 全屏都不放标题，唯一有标题的地方是"重命名"对话框）。
 * 列表按修改时间倒序；行是"文件名（不含扩展名）+ 一行小字（里面有啥 + 多大）"。
 *
 * ## 这一屏是**普通一屏：自己的纯黑底，不要任何底图、也不要模糊**
 *
 * 用户原话纠正过一轮：他说的"以函数图像为背景、背景要有高斯模糊"指的是
 * **长按 `+` 弹出来的那个保存浮窗**，**不是**这个文件列表。
 * 上一轮把这里做成了"浮在模糊的函数图像界面上的一层"，那是做错了 ——
 * 底图和模糊整套已经从这条路由上摘掉（见 `GraphScreen` 的 `Route.Saves` 分支），
 * 这一屏的根节点**自己**兜一层不透明纯黑（见函数体第一行那个 `background`），
 * 不再指望"底下有一层图透过来"。
 *
 * # 两种模式（用户最新原话："打开文件的页面改成进入显示返回按键…长按文件，右上角显示删除按键…
 * 左上角显示取消按键…在这个状态下点击其他文件可以进行多选"）
 *
 * ## 状态机
 *
 * 状态只有三个字段：`multiSelect`（模式）、`selected`（被选中的文件路径集合）、
 * `renameFor`（正在重命名的那个，非 null = 对话框开着）。**没有第三态**：
 * 单选只是"多选集合里恰好有一个元素"，所以不存在"单选模式和多选模式打架"的可能。
 *
 * | 当前模式 | 触发 | 结果 |
 * |---|---|---|
 * | 普通 | 工具栏**左**：返回（拐弯箭头 `Icons.AutoMirrored.Filled.ArrowBack`） | 回第二屏（`onBack`） |
 * | 普通 | 工具栏**右**：打开（✓，`Icons.Filled.Done`）—— **只在"恰好选中 1 个"时存在** | 打开那个文件（`onOpen`），并把选择清空 |
 * | 普通 | **点一个文件** | 它成为唯一选中项（`selected = {它}`）→ ✓ 出现 |
 * | 普通 | **再点同一个文件** | 取消选中 → `selected = {}` → ✓ 消失 |
 * | 普通 | **点空白处**（列表之外的区域，含列表下方的空白） | 清空选中 → ✓ 消失 |
 * | 普通 | **双击一个文件**（第二下在 300ms 内） | 弹**重命名**对话框（第一下已经把它选中了） |
 * | 普通 | **长按一个文件** | 进入多选模式，并且**这个文件被选中** |
 * | 多选 | 工具栏**左**：取消（×，`Icons.Filled.Close`） | 退出多选、清空选择、**不删任何东西** |
 * | 多选 | 工具栏**右**：删除（红色垃圾桶 + `HoldToConfirm` 线圈）—— **按住转满一圈才生效**，短按不做事 | 删除**所有选中项** → 退出多选 + 清空选择 + 一条**纯提示**的 toast（**没有撤销条**：用户要求删掉所有撤销提示，删除这一步现在不可回退，所以那圈线圈是唯一的防线） |
 * | 多选 | **点一个文件**（含已选中的） | 切换该行的选中状态（＝多选） |
 * | 多选 | **长按一个文件** | 同上：切换该行的选中状态（长按已选中的＝取消选中） |
 * | 多选 | **双击一个文件** | 不做事（重命名只在普通模式；避免和"多选"这件事抢手势） |
 *
 * ## 「失去焦点」到底指什么（✓ 打开按钮什么时候消失）
 *
 * 用户原话："文件失去焦点之后打开按键隐藏吧，这样可以避免误触。"
 * 这里把"失去焦点"定义成**五件事**，全都是"这个文件不再是当前唯一选中项"：
 *
 *  1. **再点一次同一个文件**（toggle 取消）；
 *  2. **点空白处**（列表以外的区域）；
 *  3. **打开成功之后**（先清空选择再跳走 —— 回来时不该还留着一个能一键打开的选中态）；
 *  4. **打开了重命名对话框**（模态抢走了焦点；关掉对话框时选择也是空的，✓ 不会自己冒出来）；
 *  5. **进出多选模式**（模式切换会把单选选择整个重置：进多选时只选中长按的那一个，
 *     退出多选时清空）。
 *
 * 换句话说：**✓ 的显示条件就是"普通模式 && 恰好选中 1 个文件"** ——
 * 这条条件写在工具栏那一段，只有一处，所以不会出现"按钮亮着但不知道开哪个"的情况。
 *
 * ## 重命名绑在哪里（保留入口，但换了手势）
 *
 * 用户这次只说"长按 = 进删除多选"，没说不要重命名，所以**重命名保留**，绑在
 * **普通模式下双击一行**（用户自己建议的备选之一）。没有用长按（被多选占了），
 * 也没有在行里塞图标（1.5 寸屏上行内图标既难点又挤字），
 * 更没有塞进工具栏（用户这一轮明确只说了左上角返回/取消、右上角打开/删除四颗按钮，
 * 多一颗就破坏了"一眼看懂"）。
 *
 * 双击用手写时间戳实现而不是 `combinedClickable(onDoubleClick=…)`：
 * 后者会把**单击回调延后 300ms**，而"点一下选中"是最高频动作 —— 手表上那 300ms 很明显。
 * 见 [DOUBLE_TAP_MS]。
 *
 * **对话框本身这一轮换成了共用的 `NameDialog`**（调用点见下面 `renameFor != null` 那一段）：
 * 原来这一屏有一份私有复制品 `RenameDialog`。用户这一轮要求"保存文件"和"重命名"
 * 这两个浮窗都居中 / 按钮在输入框下面 / 图标是 × 和 ✓ / 唤起时有动画 ——
 * 两份实现各改一遍必然会漂，所以复制品删掉，两个入口共用 `SaveImageDialog.kt` 里那一个。
 *
 * ## 重命名浮窗里那颗「导出文件」（本轮新增，用户点名）
 *
 * 用户原话："再给文件列表里面的重命名浮窗里面再加个导出文件的按钮，点击之后会在下载文件夹
 * 里面的 DrawFunc 文件夹里面导出这个文件，如果没有 DrawFunc 文件夹自动创建。"
 *
 * 第二版用户又补了一句："保存按钮和 ×✓ 不要在同一行，另起一行。" —— 所以它现在**独占一行**
 * （整行宽的胶囊 + 手画的 ⤓ 图标 + 文字"导出文件"），×/✓ 回到平分整行。版面的重算、
 * 三块屏的数值表、以及"四角为什么还在圆内"的算式都写在 `SaveImageDialog.kt` 的 KDoc 里。
 *
 * 链路只有三段：**这一屏**（谁在改名、toast 走 `onMessage`）→ [DownloadExport]
 * （`MediaStore` 那一套，整段 `Dispatchers.IO`）→ [FileExport]（纯规则：名字 / 撞名 /
 * 失败文案，离线有测试）。源文件只被**读**了一次（`File.readBytes`，按字节复制、
 * 不经编解码），写的是下载目录里的**新**文件 —— 所以这条链路**不可能**改坏 App 存档
 * 或当前文件。
 *
 * 四条有意定下来的行为（细节见 `FileExport` 的注释）：
 *
 *   1. **用输入框里"此刻"的文本**，不是磁盘上的原名：用户可能刚改完名字还没按 ✓，
 *      这时导出应该是"我现在看到的这个名字"；草稿为空才退回原名，非法才拒绝
 *      （拒绝用的是和重命名**同一句**提示，不会出现"改名不让用、导出却让用"）；
 *   2. 目标文件夹里撞名 → **加序号（`名字 (2).fdf`）不覆盖**：导出是备份 / 分享语义，
 *      覆盖会让人措手不及，而且导出这条路没有撤销；
 *   3. 导出**不关浮窗**：用户可能接着改名，关掉反而要重新双击进来看；
 *   4. 导出挂在**这一屏**的 scope 上（见 [exporting]）：浮窗被关掉也不打断写盘。
 *
 * API 29 以下（minSdk 24 的老表 / 老手机）**有意**只给一句"当前安卓版本不支持导出到下载目录
 * （需要 Android 10 及以上）"的提示，不去申请 `WRITE_EXTERNAL_STORAGE` ——
 * 理由与影响面写在 `DownloadExport` 的类注释里。
 *
 * ## 删掉了什么
 *
 *   * **左滑删除取消**（用户要求）：删除统一走多选 + 长按垃圾桶线圈这一条路，
 *     免得两种删除手势互相打架（也免得"从右侧 1/3 往左滑"这种手势和选中点击混在一起）；
 *   * **长按重命名取消**：长按改成"进多选"；
 *   * **顶上那一条"下滑返回"取消**（用户原话："文件界面有返回按钮，不用加屏幕上方的
 *     返回条"）：删掉的是"0.20 屏高的下滑手势热区"和"顶上那道抓手小横条"两样东西，
 *     回第二屏只剩**左上角返回键**和**系统返回键**两条路（见下面 `Box` 里那段注释）。
 *
 * ## 按键宽度：**每一处都按圆屏弧宽自适应**（用户原话："文件界面的按键……没有和函数列表的
 * 一样自动适配屏幕宽度"）
 *
 *   * **列表行**：`rowWidthFraction(行中心 y)` —— 行宽跟着它**此刻**在屏幕上的高度变，
 *     滚动的行宽是连续的弧（`derivedStateOf` + 量化到百分位，见那个函数）——
 *     这一屏一直就是这么做的，本轮没动；
 *   * **顶部那两颗按键**：`toolbarWidthFraction`（同一套 `circleWidthFraction` ×
 *     `ARC_MARGIN`，取"按钮上下沿里更窄的那个"）—— 233dp 圆屏上从写死的 158.9dp
 *     收到 **121.2dp**，按钮的外上角不再被表壳切掉。数值和 `FunctionList` /
 *     `FunctionsScreen` **完全同一组**，三屏的两颗按钮严丝合缝地对齐；
 *   * **撤销条已删除**（本轮）：它原来是 0.835 屏高那颗"已删除 N 个 · 点此撤销"胶囊。
 *     用户要求"把所有撤销的 toast 提示都给删掉"，所以整颗（连同它背后的
 *     `deleted` / `lastBatch` / `UNDO_MS` 状态与 2 秒过期计时）一起删了 ——
 *     不是隐藏，是不进组合。宽度那笔账（半圆最外点离圆心 116.7dp ≈ 半径 116.5dp）
 *     随之作废，不必再算。
 *
 * ## 动画（用户原话："以上所有功能的添加或修改都需要适合这个软件的丰富美观的动画"）
 *
 * | 元素 | 动画 | 时长 / 曲线 |
 * |---|---|---|
 * | 列表行入场 | 淡入 + 从下方 5% 行高抬起来，逐行交错 45ms | 220ms `FastOutSlowInEasing`（既有） |
 * | 顶部工具条入场 | 淡入 + 从上方 0.03 屏高落下来 | 180ms 同上 |
 * | 工具条按键"换岗"（← ↔ ×、✓ ↔ 🗑） | 淡入淡出 + 0.7 ↔ 1.0 缩放（原地，不挤动另一颗） | 进 160ms / 出 120ms 同上 |
 * | 行选中 / 取消选中 | 底色 深灰 ↔ 强调色渐变 | 160ms 同上（和功能界面选中 chip 同节奏） |
 * | 行尾 ✓ 角标 | alpha 0 ↔ 1 + 0.6 ↔ 1.0 缩放（**位置永远占着**，文字不会横跳） | 170ms 同上 |
 * | 删除 | 选中的行淡出 + 缩到 0.9，**播完才**重读目录；其余行位移合拢 | 180ms 淡出 / 220ms 位移 |
 * | 空状态 | 淡入 + 0.92 → 1 缩放；第一个文件存进来时反向淡出 | 220ms / 150ms 同上 |
 */
@Composable
fun SavesScreen(
    metrics: WatchMetrics,
    /** 打开某个文件（外壳层负责解析、装载、提示、跳转）。 */
    onOpen: (FunctionFileStore.Entry) -> Unit,
    /** 弹一句提示（toast）：删除结果、改名成功、失败原因都走它。 */
    onMessage: (String) -> Unit,
    /** 回「功能界面」。 */
    onBack: () -> Unit,
    /**
     * 右上角「+」调用系统文件选择器后拿到的 Uri（外壳层负责读取、解析、装载、跳转）。
     *
     * 用户 1.3.2 原话："给文件列表界面右上角的加号键加一个点击之后选取系统文件的功能。
     * 这个加号键的本意是打开存储在软件外部的函数文件。"
     */
    onPickFile: (Uri) -> Unit,
    rotary: Flow<Float>
) {
    val context = LocalContext.current
    // 系统自带的文件选择器（Storage Access Framework）。`OpenDocument` 不需要任何
    // 存储权限，而且返回的 Uri 在本进程内可直接 `openInputStream` 读取。
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPickFile) }
    val density = LocalDensity.current
    val rowHeight = metrics.sizeDp * ROW_H
    val listTopPx = with(density) { (metrics.sizeDp * LIST_TOP).toPx() }
    // 注意：`swipeDownPx`（顶部下滑的判定阈值）随那条手势一起删掉了，见下面"下滑返回已删"。
    val isRound = isRoundScreen()
    val listState = rememberLazyListState()

    // 当前列表。删完文件要重列一次（`refresh()`）。
    var entries by remember { mutableStateOf(FunctionFileStore.list(context)) }
    fun refresh() {
        entries = FunctionFileStore.list(context)
    }

    // ---- 状态机的三个字段（见上面 KDoc 的表）----
    /** false = 普通模式；true = 多选删除模式。 */
    var multiSelect by remember { mutableStateOf(false) }
    /** 被选中的文件（绝对路径）。单选就是"恰好一个元素"。 */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 正在重命名的那一个；null = 不显示对话框。 */
    var renameFor by remember { mutableStateOf<FunctionFileStore.Entry?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }

    /**
     * 「导出文件」（写进 `Download/DrawFunc/`）**正在进行中**。
     *
     * 为什么这个状态放在**这一屏**、而不是浮窗里：导出是挂起的写盘
     * （`DownloadExport` 里 `withContext(Dispatchers.IO)`），它挂在**这一屏**的
     * `scope` 上 —— 挂在浮窗上的话，用户导出途中顺手点一下 ×（浮窗退出组合、
     * 它的 `rememberCoroutineScope` 被取消）就可能留下一条 `IS_PENDING = 1` 的半截记录。
     * 它同时喂给 `NameDialog` 让那颗键渐暗、点不动（防止连点插两行）。
     */
    var exporting by remember { mutableStateOf(false) }

    // 这里原来有 `deleted`（刚才删掉的那批文件 + 内容，为了撤销）和 `lastBatch`
    // （给撤销条显示用）。用户要求删掉所有撤销提示 → 撤销这条路整个不存在了，
    // 于是也**不再把文件内容读进内存**（那一次 `FunctionFileStore.read` 唯一的用处就是撤销）。
    // 删除的过渡（`dying`）与它无关，见下面的 [deleteSelected]。

    // 入场只播一次（状态留在屏幕这一层：行会被 LazyColumn 回收重建，
    // 放在行里的话每次滚回来都会重播 —— 和 FunctionList 踩过同一个坑）。
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // 顶部工具条自己的入场：同一个开关，但更快一点（先它站稳、列表行再依次跟上）。
    // 从上往下 0.03 屏高（≈7dp）落下来 —— 和列表行的"从下往上 5% 行高"呼应。
    val toolbarIn by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = TOOLBAR_IN_MS, easing = FastOutSlowInEasing)
    )
    val toolbarRisePx = with(density) { (metrics.sizeDp * 0.03f).toPx() }

    // 工具条两颗按键"换岗"（← ↔ ×、✓ ↔ 🗑）的过渡：淡入 + 从 0.7 放大 / 淡出 + 缩回 0.7。
    // 没有它的话按键是"啪"地换成另一个图标（真机上看起来像卡了一下）。
    val keyEnter = fadeIn(tween(durationMillis = KEY_IN_MS)) +
        scaleIn(
            animationSpec = tween(durationMillis = KEY_IN_MS, easing = FastOutSlowInEasing),
            initialScale = KEY_SWAP_FROM
        )
    val keyExit = fadeOut(tween(durationMillis = KEY_OUT_MS)) +
        scaleOut(
            animationSpec = tween(durationMillis = KEY_OUT_MS, easing = FastOutSlowInEasing),
            targetScale = KEY_SWAP_FROM
        )

    /**
     * **正在播"消失"动画**的那几行（绝对路径）。
     *
     * 用户要求"删除要有过渡"：`refresh()` 一调行就凭空没了。所以先把要删的行的路径
     * 记在这里 → 那几行播 [ROW_OUT_MS] 的淡出 + 缩小（`RowEntrance` 的 exit）→
     * 播完才真的重读目录；剩下的行往上合拢那一段由 `Modifier.animateItem` 的位移接管。
     */
    var dying by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 教程"上一步"要撤销这一屏上的选择 / 删除：把两个复原入口登记给状态机。
    // 只在这一屏活着的时候登记，离开文件列表就摘掉（那时撤销步骤也不会落在这里）。
    DisposableEffect(Unit) {
        Tutorial.onRestoreSavesSelection = { restore ->
            refresh()
            when (restore) {
                // 什么都没选中（普通模式）
                Tutorial.SavesRestore.Clear -> {
                    multiSelect = false
                    selected = emptySet()
                }
                // 重新选中刚存的 test（第 28 步的效果）
                Tutorial.SavesRestore.SelectTest -> {
                    multiSelect = false
                    val test = entries.firstOrNull { it.baseName == "test" }
                    selected = if (test != null) setOf(test.file.absolutePath) else emptySet()
                }
            }
        }
        Tutorial.onRestoreDeletedFile = {
            // 名义删除的文件还回列表（撤销第 31 步 / 中途退出）
            FunctionFileStore.unstash()
            refresh()
        }
        onDispose {
            Tutorial.onRestoreSavesSelection = null
            Tutorial.onRestoreDeletedFile = null
        }
    }
    val scope = rememberCoroutineScope()

    // 这里原来有一条"撤销条 2 秒后自己消失"的计时（`LaunchedEffect(deleted)`）。
    // 撤销条删掉之后它没有任何消费者，一并删掉 —— 顺带也不会再出现
    // "计时器到点把 `deleted` 清空、撤销条闪一下"这类时序问题。

    // 表冠 / 滚轮：在这一屏只用来滚动列表（缩放图像是图像界面的事）
    LaunchedEffect(rotary) {
        rotary.collect { amount ->
            listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
        }
    }

    // 返回键：**多选模式下先退出多选**（和"取消"那颗按钮同一件事），
    // 再按一次才回第二屏 —— 否则用户在多选里按返回会连带把整个屏都退掉。
    BackHandler {
        if (multiSelect) {
            multiSelect = false
            selected = emptySet()
        } else {
            onBack()
        }
    }

    /** 退出多选：清空选择，什么都不删。 */
    fun exitMultiSelect() {
        multiSelect = false
        selected = emptySet()
    }

    /** 删除所有选中项（垃圾桶线圈转满一圈才会走到这里）。 */
    fun deleteSelected() {
        val targets = entries.filter { it.file.absolutePath in selected }
        if (targets.isEmpty()) return
        // 这里原来先 `FunctionFileStore.read` 把内容读出来留着撤销用 —— 撤销没了，
        // 这次读盘也一起去掉（少一遍同步 IO）。
        var removed = 0
        if (Tutorial.active) {
            // 教程期间**不真删**：只从列表里拿掉（盘上的文件留着，退出教程还要还回来，
            // 走完全程才由 FunctionFileStore.dropStash() 真正删掉）
            FunctionFileStore.stash(targets.map { it.file })
            removed = targets.size
        } else {
            for (e in targets) {
                if (FunctionFileStore.delete(e.file)) removed++
            }
        }
        // 教程第 31 步：垃圾桶线圈转满一圈、文件从列表里消失 = 这一步完成
        Tutorial.report("saves_deleted")
        exitMultiSelect()
        // ---- 删除的过渡（用户要求"删除要有过渡"）----
        // 顺序很讲究：托盘提示**立刻**出来（用户马上知道成功了），
        // 而列表里那几行先自己淡出/缩小 [ROW_OUT_MS]，**播完才**真的重读目录 ——
        // 直接 `refresh()` 的话行是"凭空消失"的，没有"我把它删掉了"的手感。
        // ⚠️ 这一段（`dying` + 延时 + `refresh`）**和撤销无关**，撤销条删掉之后一个字没动。
        dying = targets.map { it.file.absolutePath }.toSet()
        onMessage(
            if (removed == targets.size) {
                "已删除 $removed 个文件"
            } else {
                "已删除 $removed/${targets.size} 个文件（其余删不掉）"
            }
        )
        scope.launch {
            delay(ROW_OUT_MS)
            dying = emptySet()
            refresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // ---- 这一屏的底：**不透明纯黑**（"文件列表现在是黑底"就是这一行保证的）----
            //
            // 为什么必须显式写这一行：上一轮这一屏是被当成"浮在模糊的函数图像界面上
            // 的一层"用的，所以它的根节点**刻意没有**背景 —— 那样底图才透得出来。
            // 用户纠正之后，底图不在了，这个"没有背景"就从"配合底图"变成了**漏底**：
            // 它会透出 `AnimatedContent` 下面那一层内容（转场的 200 多毫秒里
            // 上一屏还在淡出，会从行与行之间看出来）。
            //
            // 取 `MaterialTheme.colors.background`（这个主题里就是纯黑 `#000000`，
            // 见 `watchColors()` / `WatchPalette.background`）而不是自己写死一个黑：
            // 和外壳 `GraphScreen` 最外层那一层背景读的是**同一个值**，
            // 将来换主题时两处一起变，不会出现"列表比外壳黑得不一样"。
            .background(
                // 回到 3.45 的写法：有背景可透 → 62% 底色；否则实色。
                if (LocalGlassTheme.current) {
                    MaterialTheme.colors.background.copy(alpha = GLASS_PAGE_MASK_ALPHA)
                } else {
                    MaterialTheme.colors.background
                }
            )
    ) {

        // ---- 最底层：点空白 = 取消选中（"失去焦点"的第 ② 条）----
        //
        // 它在所有东西下面，而行自己有手势，所以只有"没落在任何行上"的点击会走到这里。
        // 多选模式下**不**清空选择：那会让用户辛苦勾的几个文件被一次误点清掉，
        // 退出多选有明确的 × 按钮和返回键。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!multiSelect) selected = emptySet()
                }
        )

        // ---- 顶部那条"下滑返回"**已经删掉** ----
        //
        // 用户原话："文件界面有返回按钮，不用加屏幕上方的返回条。"
        // 删掉的是两样东西（都在这一屏的内部，`GraphScreen.kt` 的调用点一个字没动）：
        //   1. 覆盖上半屏的**下滑手势热区**（原来是 `fillMaxWidth() × 0.20 屏高` 的透明
        //      Box + `detectVerticalDragGestures`，往下划够 0.10 屏高就回第二屏）；
        //   2. 顶上那道**抓手小横条**（`0.16 × 0.011` 屏宽的小胶囊）—— 它的唯一作用就是
        //      "这里有手势"的提示，手势没了还留着，等于骗用户去滑。
        //
        // 回第二屏现在有两条路（行为和动画都没变）：
        //   * 左上角那颗**返回键**（`ArrowBack`；多选模式下变成 ×，先退出多选）；
        //   * **系统返回键**（`BackHandler`，同样是多选里先退多选）。
        //
        // 顺带解决了一个老坑：那条热区当年必须 `zIndex(1f)` 才抢得到顶部下滑，而
        // `zIndex` 会**盖过组合顺序**（真机 bug：点"返回/取消"其实点在热区上，只有
        // "点住再划一下"才触发，按钮的按压动画也跟着没了）。热区没了之后，工具栏那颗
        // `zIndex(2f)` 只剩"确保两颗按钮在列表之上"这一条保险作用。

        // ---- 工具栏：几何**照抄 `FunctionList` 顶部那一行** ----
        //
        // 用户原话："文件界面的按键……没有和函数列表的一样自动适配屏幕宽度。"
        // 所以行顶 / 行高 / 图标按钮边长三个常数和函数列表、功能界面**完全同一组**
        // （`TOOLBAR_TOP` / `TOOLBAR_H` / `ICON_BTN_H`），宽度走同一套圆屏弧宽公式
        // （[toolbarWidthFraction]：取两颗按钮上下沿里更窄的那个 × `ARC_MARGIN`）。
        // 233dp 圆屏上的实算：范围 y ∈ [0.0725, 0.2525] → f(0.0725)=0.5186 × 0.95 = 0.4927
        // → 被 `MIN_ROW_WIDTH` 抬到 **0.52**，即行宽 **121.2dp**（原来写死成
        // `circleWidthFraction(0.16f) × 0.93` = 158.9dp，按钮的外上角其实落到表壳外面了）。
        // 两颗按钮各 41.9dp，中间还剩 37.3dp，三屏的两颗按钮因此**严丝合缝地对齐**。
        //
        // 两颗按键各自再套一层**固定尺寸**的 Box（`Modifier.size(keySide)`）才进
        // `AnimatedVisibility`：这样"换岗"（← ↔ ×、✓ ↔ 🗑）时消失的那一颗是
        // **原地缩回去**，而不是把另一颗挤着走（`SpaceBetween` 下位置会跟着变）。
        //
        // 这一行是 **Box + `align(CenterStart/CenterEnd)`**，不是 `Row + SpaceBetween`：
        // `Row` 里的 `AnimatedVisibility` 会解析到 `RowScope` 那个重载，而 Compose 的
        // `@LayoutScopeMarker`（DSL marker）不允许在**内层** Box 的作用域里隐式使用它 ——
        // 会直接报 "cannot be called in this context with an implicit receiver"
        // （实测撞到过，`AnimatedVisibility` 的非 Row/Column 重载则没有这个问题）。
        // Box 版的效果和 `SpaceBetween` 完全一样：左端顶左、右端顶右。
        val keySide = metrics.iconButton
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = metrics.sizeDp * TOOLBAR_TOP)
                .width(metrics.sizeDp * toolbarWidthFraction(metrics, isRound))
                .height(metrics.sizeDp * TOOLBAR_H)
                // 工具栏的按钮必须在**列表**之上（列表排在它后面组合）
                .zIndex(2f)
                // 入场：从上方 0.03 屏高轻轻落下来 + 淡入
                .graphicsLayer {
                    alpha = toolbarIn
                    translationY = (toolbarIn - 1f) * toolbarRisePx
                }
        ) {
            // ---- 左上角：普通 = 返回（拐弯箭头） / 多选 = 取消（×）----
            Box(
                modifier = Modifier.align(Alignment.CenterStart).size(keySide),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(visible = !multiSelect, enter = keyEnter, exit = keyExit) {
                    // 普通模式左上角：返回（图标是**拐弯的箭头**）
                    WatchIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        metrics = metrics,
                        onClick = onBack,
                        modifier = Modifier.tutorialAnchor(TutorialAnchors.SAVES_BACK)
                    )
                }
                AnimatedVisibility(visible = multiSelect, enter = keyEnter, exit = keyExit) {
                    // 多选模式左上角：取消（×）—— 退出多选、清空选择、什么都不删
                    WatchIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "取消（退出多选，不删除）",
                        metrics = metrics,
                        onClick = { exitMultiSelect() }
                    )
                }
            }

            // ---- 右上角：普通 = 打开（✓，**只在恰好选中 1 个时存在**） / 多选 = 删除（🗑）----
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(keySide),
                contentAlignment = Alignment.Center
            ) {
                // 普通模式右上角：**常驻一颗键** —— 没选中文件时是「+」，选中一个文件后
                // 变成「✓」（就是原来那颗勾）。
                //
                // 用户 1.2.65 原话："你给点击文件键打开的界面的右上角加个加号键，
                // 用处到时候再说，先放，样式和函数列表界面的加号键完全一样，
                // 在文件列表点击文件之后加号键转变成确定键（就是原来的勾），
                // 做个丝滑的转换动画。"
                //
                // 用户 1.2.66 原话："你把文件列表的加号键切换成勾键的动画做成长按文件弹出来
                // 删除键同款，点击文件之后勾键就弹出来，再点一下再弹回去。"
                // → 不再用 [Crossfade]（交叉淡入淡出），改走和右上角删除键**同一套**
                // [keyEnter] / [keyExit]：淡入 + 从 0.7 放大到 1「弹出来」，再点一下
                // 淡出 + 缩回 0.7「弹回去」。两颗键各挂一层 AnimatedVisibility，
                // 和左上角 ← ↔ × 的换岗是同一个写法（消失的那颗原地缩回去，不挤动另一颗）。
                //
                // 「+」的样式照抄函数列表那颗：`KeyPalette.accentDeep` 底色（不用 `filled`）。
                //
                // 1.3.2 起用途确定了：**打开软件外部的函数文件** ——
                // 点它调起系统文件选择器（SAF），选中的文件交给外壳层解析装载。
                AnimatedVisibility(visible = !multiSelect && selected.size != 1, enter = keyEnter, exit = keyExit) {
                    WatchIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "打开外部函数文件",
                        metrics = metrics,
                        // 和函数列表的 + 键同一个底色（补丁蓝的暗调）
                        containerColor = KeyPalette.accentDeep,
                        // 部分精简版手表系统没装 DocumentsUI（没有系统文件选择器），
                        // 此时 launch 会抛 ActivityNotFoundException —— 弹一句而不是崩。
                        onClick = {
                            runCatching { pickFileLauncher.launch(arrayOf("*/*")) }
                                .onFailure { onMessage("此设备没有系统文件选择器") }
                        }
                    )
                }
                AnimatedVisibility(visible = !multiSelect && selected.size == 1, enter = keyEnter, exit = keyExit) {
                    // 选中一个文件之后：这颗键就是原来那颗「打开」的勾
                    WatchIconButton(
                        icon = Icons.Filled.Done,
                        contentDescription = "打开选中的文件",
                        metrics = metrics,
                        filled = true,
                        onClick = {
                            val path = selected.first()
                            val target = entries.firstOrNull { it.file.absolutePath == path }
                            // "失去焦点"的第 ③ 条：先把选择清掉再跳走
                            selected = emptySet()
                            if (target != null) onOpen(target)
                        }
                    )
                }
                // 多选模式右上角：删除 —— **和第二屏那颗红垃圾桶完全一样**：
                // 同一个 HoldToConfirm（红色线圈逆时针转满一圈才触发）、
                // 短按不做事（防误触）。
                //
                // ==================================================================
                // ⚠️ 底色本轮改了：**从"有选中就满档红"改成常驻暗红档**
                // （`KeyPalette.dangerDim` = `danger × 0.45`，纯黑上约 #3A2224）。
                //
                // 用户原话："文件列表的删除键的颜色没有和功能界面的一样变成暗红色。"
                // 两屏的**语义其实是一样的**（都是"有东西可操作 → 亮起"），但两边看到的
                // 常态不同，用户看到的就是"不一样"：
                //   * 功能界面那颗：只有画了几何对象才会满档亮起，用户平时看到的是**暗档**
                //     （`objectCount == 0` 时），于是那颗键在他心里就是"暗红色"；
                //   * 这一颗：**只有进了多选模式才存在**，而进多选的方式就是长按一个文件
                //     （那一下已经选中了它）→ `selected` 一进来就非空 → 这颗键**从出现的第一帧
                //     起就是满档 #824C51**，那个暗档几乎从来没被看见过。
                // 所以这里不照抄"两档 + 180ms 渐变"：这颗键**出现的那一刻就已经选中了一个
                // 文件**（进多选 = 长按某一行），"有东西可删"几乎总是成立；再去做两档动画
                // 只会让它和功能界面那颗的常驻观感继续不一样。暗档的数值和功能界面那颗
                // 共用同一个常量（`KeyPalette.DANGER_DIM_ALPHA`），不在这一屏另写一个 0.45。
                //
                // "没东西可删"这条语义没丢，只是**从底色挪到了图标**：底色一律是暗红，
                // 未选中任何文件时把**图标**压到同一档（0.45 白），看起来仍然是"这颗键现在
                // 没什么可删的"。这样"底色 = 和功能界面一致"和"状态看得出来"两件事同时成立。
                //
                // 另一个好处：`HoldToConfirm` 的线圈是**满档** [KeyPalette.danger]，
                // 底色压到暗档之后线圈才压得住、按住时转圈看得清（底色满档时线圈和底色
                // 同色，那正是 `+` 键上一轮踩过的坑）。
                // ==================================================================
                AnimatedVisibility(visible = multiSelect, enter = keyEnter, exit = keyExit) {
                    HoldToConfirm(
                        metrics = metrics,
                        ringColor = KeyPalette.danger,
                        onConfirm = { deleteSelected() }
                    ) {
                        WatchIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "删除选中的文件（按住不放才删）",
                            metrics = metrics,
                            // 常驻暗红档：和功能界面右上角那颗「清屏」没东西可清时**同一个颜色**
                            containerColor = KeyPalette.dangerDim,
                            // 选中了东西 → 纯白图标；什么都没选 → 图标也压暗一档
                            // （同一个 0.45，见 KeyPalette.DANGER_DIM_ALPHA）
                            contentColor = if (selected.isNotEmpty()) {
                                KeyPalette.text
                            } else {
                                KeyPalette.text.copy(alpha = KeyPalette.DANGER_DIM_ALPHA)
                            },
                            onClick = {},
                            // 教程第 31 步要圈住这颗垃圾桶
                            modifier = Modifier.tutorialAnchor(TutorialAnchors.SAVES_DELETE)
                        )
                    }
                }
            }
        }

        // ---- 列表 ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = metrics.sizeDp * LIST_TOP)
                .fillMaxWidth()
                .height(metrics.sizeDp * (LIST_BOTTOM - LIST_TOP)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.018f)
        ) {
            itemsIndexed(entries, key = { _, e -> e.file.absolutePath }) { index, entry ->
                val path = entry.file.absolutePath
                val wf = rowWidthFraction(
                    listState = listState,
                    itemKey = path,
                    listTopPx = listTopPx,
                    screenPx = metrics.sizePx,
                    metrics = metrics,
                    isRound = isRound
                )
                RowEntrance(
                    // "正在被删掉"的那几行先自己淡出 / 缩小（见 [dying] 和 [ROW_OUT_MS]），
                    // 播完才真的重读目录 —— 这就是"删除的过渡"
                    visible = entered && path !in dying,
                    delayMillis = index * STAGGER_MS,
                    rowHeight = rowHeight,
                    // 行**位移**的动画：删掉一行之后，下面的行往上合拢是滑上去的，
                    // 不是"啪"地跳一格（`animateItem` 是 1.7 里 `animateItemPlacement`
                    // 的替代品，只给 placementSpec，淡入淡出由 `RowEntrance` 自己管）
                    modifier = Modifier.animateItem(
                        placementSpec = tween(
                            durationMillis = PLACEMENT_MS,
                            easing = FastOutSlowInEasing
                        )
                    )
                ) {
                    FileRow(
                        entry = entry,
                        // 正在播"消失"动画的那几行**继续算选中**：`exitMultiSelect()` 已经把
                        // `selected` 清空了，如果这里只看 `selected`，它们会在淡出的同时
                        // 从强调色渐变回普通深灰（"闪一下灰"）。保持选中色 + 淡出才是干净的收场。
                        selected = path in selected || path in dying,
                        metrics = metrics,
                        widthFraction = wf,
                        onTap = {
                            // 文件已经删了、行正在淡出：这 180ms 里不接受任何手势
                            // （再点一下会"选中一个不存在的文件"，双击更会去重命名它）
                            if (path !in dying) {
                                if (multiSelect) {
                                    // 多选：切换这一行的选中状态
                                    selected = if (path in selected) selected - path else selected + path
                                } else {
                                    // 普通：单选；再点同一个 = 取消选中（"失去焦点"第 ① 条）
                                    val wasSelected = selected == setOf(path)
                                    selected = if (wasSelected) emptySet() else setOf(path)
                                    // 教程第 28/29 步：选中 / 再点一下取消
                                    Tutorial.report(if (wasSelected) "saves_deselected" else "saves_selected")
                                }
                            }
                        },
                        onLongPress = {
                            if (path !in dying) {
                                if (multiSelect) {
                                    // 多选里长按 = 切换（长按已选中的 = 取消选中）
                                    selected = if (path in selected) selected - path else selected + path
                                } else {
                                    // 普通里长按 = 进多选，并且**这个文件被选中**
                                    multiSelect = true
                                    selected = setOf(path)
                                    // 教程第 30 步：长按 test 进多选
                                    Tutorial.report("saves_multi")
                                }
                            }
                        },
                        onRename = {
                            // 重命名只绑在**普通模式的双击**上（多选里双击不做事）
                            if (!multiSelect && path !in dying) {
                                // "失去焦点"的第 ④ 条：模态抢走焦点 → 清掉选中，✓ 不会自己冒出来
                                selected = emptySet()
                                renameError = null
                                renameFor = entry
                            }
                        },
                        // 教程第 28~31 步圈的是刚存的 test 那一行
                        anchorId = if (entry.baseName == "test") {
                            TutorialAnchors.SAVES_ROW_TEST
                        } else {
                            TutorialAnchors.savesRow(entry.baseName)
                        }
                    )
                }
            }
        }

        // ---- 空状态：居中的一句话（用户建议的文案）----
        // 用 AnimatedVisibility：第一个文件存进来时它是**淡出**的（而不是"啪"地消失），
        // 删光最后一个文件时又淡入回来 —— 和列表的入场是同一套 220ms / `FastOutSlowInEasing`。
        AnimatedVisibility(
            visible = entries.isEmpty(),
            enter = fadeIn(tween(durationMillis = ROW_IN_MS, easing = FastOutSlowInEasing)) +
                scaleIn(
                    animationSpec = tween(durationMillis = ROW_IN_MS, easing = FastOutSlowInEasing),
                    initialScale = 0.92f
                ),
            exit = fadeOut(tween(durationMillis = 150, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                modifier = Modifier
                    .width(metrics.sizeDp * metrics.circleWidthFraction(0.5f) * 0.9f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "还没有保存过文件",
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.textMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(metrics.sizeDp * 0.02f))
                Text(
                    // 顺手指一下入口在哪：不然用户在这个空屏上不知道该怎么弄出一个文件来
                    text = "在函数列表长按 + 就能存一份",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ---- 这里原来是"删除后的撤销条"（0.835 屏高那颗"已删除 N 个 · 点此撤销"胶囊）----
        //
        // 用户这一轮要求"把所有撤销的 toast 提示都给删掉" —— 这颗胶囊就是一条带撤销的提示，
        // 所以**整颗删掉**（连同 `deleted` / `lastBatch` / `UNDO_MS` 与其过期计时）。
        // 留下的是删除时那一条**纯提示** toast（`onMessage("已删除 N 个文件")`，见 [deleteSelected]），
        // 那一条不带任何操作、也不会被点。

        // ---- 重命名对话框：**和"保存函数文件"是同一个浮窗**（`NameDialog`）----
        //
        // 原来这一屏自己有一个私有 `RenameDialog`，是 `NameDialog` 的复制品（注释里也写着
        // "数值也一样"）。用户这一轮要求两个浮窗"居中、按钮在输入框下面、图标是 × 和 ✓、
        // 有动画"，两边各改一遍必然会漂 —— 所以直接把复制品删掉，两个入口共用一个实现。
        // `NameDialog` 的签名只加了**两个可选参数**（`onExport` / `exporting`，都是默认值）：
        // 不传 `onExport` 时那颗"导出文件"键**根本不进组合** ——
        // 所以 `GraphScreen.kt` 那边"保存函数文件？"的调用点一个字都不用改、观感不变。
        val target = renameFor
        if (target != null) {
            NameDialog(
                // ⚠️ 浮窗必须**盖在顶部工具条之上**：工具条那边挂着 `zIndex(2f)`
                // （当年为了不让"顶部下滑热区"抢走返回键），而 `zIndex` 会**盖过组合顺序** ——
                // 用户在真机上看到的就是"重命名浮窗弹出来了，但左上角的返回键还浮在它上面"
                // （点得到、也不是灰的，层级错了）。所以这里显式给浮窗更高的层级 `3f`。
                // 为什么不直接把工具条的 `2f` 去掉：那条 `zIndex` 还承担"两颗按钮在列表之上"
                // 的保险，去掉要重新验证列表的绘制顺序；抬高浮窗是影响面最小的一处改动。
                modifier = Modifier.zIndex(3f),
                title = "重命名",
                metrics = metrics,
                // 最下面那行小字：这里显示"原名"，让用户知道自己正在改哪一个
                pathHint = "原名 ${target.baseName}",
                initialName = target.baseName,
                hint = "输入文件名",
                errorText = renameError,
                // 正在导出 → 那颗键渐暗并点不动（连点会往 MediaStore 插两行）
                exporting = exporting,
                // ---- 用户要的那颗「导出文件」（**只有这一屏传它**）----
                //
                // 用户原话："再给文件列表里面的重命名浮窗里面再加个导出文件的按钮，点击之后
                // 会在下载文件夹里面的 DrawFunc 文件夹里面导出这个文件，如果没有 DrawFunc
                // 文件夹自动创建。"
                //
                // 三段链路：这一屏（谁在改名 / toast）→ `DownloadExport`（MediaStore，IO 线程）
                // → `FileExport`（纯规则：名字 / 撞名 / 失败文案，离线有测试）。
                // 名字用**输入框里此刻的文本**（用户可能刚改完还没按 ✓），空则退原名、非法则拒绝。
                onExport = { draft ->
                    if (!exporting) {
                        exporting = true
                        // 挂在**这一屏**的 scope 上：浮窗马上被关掉也不打断这次写盘（见 [exporting]）
                        scope.launch {
                            val outcome = DownloadExport.export(
                                context = context,
                                // 源文件是**只读**的：导出是"复制一份出去"，
                                // App 自己的存档（`filesDir/saves`）一个字都不动
                                source = target.file,
                                draftName = draft,
                                originalBaseName = target.baseName
                            )
                            exporting = false
                            // 成功失败都给一句话（手表上没有别的反馈渠道），
                            // 而且**不关浮窗** —— 用户可能还要接着改名、或者再导一次
                            onMessage(outcome.message)
                        }
                    }
                },
                onCancel = {
                    renameFor = null
                    renameError = null
                },
                onConfirm = { raw ->
                    // 校验规则本身在 `FunctionFileFormat.baseNameError`（纯函数、离线有测试）：
                    // 空名字 / 非法字符 / 太长 / 以点开头 / 重名都会返回一句给用户看的话。
                    val taken = FunctionFileStore.takenBaseNames(context, except = target.file)
                    val error = FunctionFileFormat.baseNameError(raw, taken)
                    if (error != null) {
                        renameError = error
                    } else {
                        val newBase = FunctionFileFormat.normalizeBaseName(raw)
                        val renamed = FunctionFileStore.rename(target.file, newBase)
                        if (renamed == null) {
                            renameError = "改名失败：已有同名文件或磁盘出错"
                        } else {
                            renameFor = null
                            renameError = null
                            refresh()
                            onMessage("已重命名为 ${FunctionFileFormat.displayName(renamed.name)}")
                        }
                    }
                }
            )
        }
    }
}

/**
 * 一行的入场（淡入 + 从下方 5% 行高处轻轻抬起来）**和退场**（淡出 + 缩到 0.9）。
 *
 * 和 `FunctionList.RowEntrance` 是同一段代码（退场那半是这一轮加的）：外层固定高度的
 * Box 是必需的 —— `AnimatedVisibility(visible = false)` 会把内容整个摘出布局，
 * 直接放进 `LazyColumn` 的话列表会在入场那 200ms 里被一节节撑开，看起来就是闪烁。
 *
 * 退场专门给"删除"用（[dying] / [ROW_OUT_MS]）：淡出的同时缩到 0.9，
 * 看起来是"被收走了"而不是"凭空没了"。
 *
 * @param modifier 给 `LazyColumn` 的项根节点用的（调用方传 `Modifier.animateItem(...)`，
 *   管的是"行位移动画"——删完一行之后下面的行往上滑，而不是跳一格）
 */
@Composable
private fun RowEntrance(
    visible: Boolean,
    delayMillis: Int,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val playEnter = remember { !visible }
    Box(
        modifier.fillMaxWidth().height(rowHeight),
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
            // 退场 = 删除那 180ms：淡出 + 缩到 0.9（见 ROW_OUT_MS 的注释）
            exit = fadeOut(
                animationSpec = tween(durationMillis = ROW_OUT_MS.toInt(), easing = FastOutSlowInEasing)
            ) + scaleOut(
                animationSpec = tween(durationMillis = ROW_OUT_MS.toInt(), easing = FastOutSlowInEasing),
                targetScale = 0.9f
            )
        ) {
            content()
        }
    }
}

/**
 * 顶部工具条（左上返回/取消、右上打开/删除）这一对的宽度（占屏宽）。
 *
 * **逐字照抄 `FunctionList.toolbarWidthFraction`**（用户要求"和函数列表一样自动适配
 * 屏幕宽度"，那一屏的注释把这套几何的来龙去脉写全了，这里只留结论）：
 *
 *   * 取这一对按钮**上下两端里更窄**的那个，而不是行中心 —— 按钮接近方块，
 *     它的外上角比行中心更靠近圆的外沿。按行中心算（那是 [rowWidthFraction] 对
 *     "一行文字"的做法）会让角落到表壳外面：233dp 基准上中心 0.1625 处 f = 0.7379、
 *     行宽 158.9dp，按钮外沿落在 x = 79.5dp，而 y = 0.10 处圆内半宽只有 69.9dp。
 *   * 高度取 `max(TOOLBAR_H, ICON_BTN_H) / 2`：图标按钮是 `Modifier.size(...)`，
 *     `size()` 会被**行约束**夹住，所以按钮的真实上下沿只会落在这两个范围之内；
 *     按更大的那个算，换 Compose 版本、约束行为变了也不会切角。
 *   * 233dp 圆屏的实算：y ∈ [0.0725, 0.2525]，f(0.0725) = 0.5186、
 *     f(0.2525) = 0.8689 → 取小者 × 0.95 = 0.4927 → 被 [MIN_ROW_WIDTH] 抬到 **0.52**
 *     → 行宽 **121.2dp**：两颗按钮各 41.9dp，中间还剩 37.3dp。
 *
 * 这个值对一块屏来说是常量（这一行不滚动），所以**不需要** `derivedStateOf` + 量化：
 * 那套是为 [rowWidthFraction] 准备的 —— 它读的 `layoutInfo` 在滚动时每帧都在变。
 */
private fun toolbarWidthFraction(metrics: WatchMetrics, isRound: Boolean): Float {
    if (!isRound) return SQUARE_ROW_WIDTH
    val center = TOOLBAR_TOP + TOOLBAR_H * 0.5f
    val halfSpan = maxOf(TOOLBAR_H, ICON_BTN_H) * 0.5f
    val raw = minOf(
        metrics.circleWidthFraction(center - halfSpan),
        metrics.circleWidthFraction(center + halfSpan)
    ) * ARC_MARGIN
    return raw.coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
}

/**
 * 算出某一项**当前**应该有多宽（占屏宽的比例）。
 *
 * 和 `FunctionList.rowWidthFraction` 一模一样（含 `derivedStateOf` + 量化到百分位
 * 那条性能约定：直接读 `layoutInfo` 会让每个可见行每帧都重组）。
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
    if (!isRound) return SQUARE_ROW_WIDTH
    val fraction by remember(itemKey) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf SQUARE_ROW_WIDTH
            val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx
            val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
            (round(raw * 100f) / 100f).coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
        }
    }
    return fraction
}

/**
 * 一个文件在列表里的样子。
 *
 * 和函数行（`FunctionList.FunctionRow`）**同一套观感**：胶囊底、按压缩放/压暗、
 * 圆弧宽度缩放；选中态用 `KeyPalette.accent` 底色 + 纯白文字
 * （和别处的选中态一致），右边再补一个小 ✓ 角标 —— 圆表上一行只有 0.15 屏高，
 * 光靠底色在弱光下不够醒目。
 *
 * ## 手势（三种，互不冲突）
 *
 * | 手势 | 动作 |
 * |---|---|
 * | 单击 | [onTap]（普通 = 选中/取消；多选 = 切换多选） |
 * | 长按 | [onLongPress]（普通 = 进多选；多选 = 切换） |
 * | 双击（第二下在 [DOUBLE_TAP_MS] 内） | [onRename]（普通模式才有效） |
 *
 * 为什么不用 `combinedClickable`：它一旦带 `onDoubleClick`，**单击回调会被延后**
 * 300ms（要等第二下）—— "点一下选中"是最高频动作，手表上这 300ms 非常明显。
 * 所以这里手写时间戳：第一下立刻选中、第二下（300ms 内）才当重命名。
 * 按压反馈仍然保留：`onPress` 里往同一个 `InteractionSource` 发 Press/Release，
 * 和别的屏用的是同一套 `rememberPressFeedback`（缩放 + 压暗，且只动图层）。
 */
@Composable
private fun FileRow(
    entry: FunctionFileStore.Entry,
    selected: Boolean,
    metrics: WatchMetrics,
    widthFraction: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    /** 教程锚点（每行都登记；教程第 28~31 步只认 test 那一行）。 */
    anchorId: String? = null
) {
    val press = rememberPressFeedback(scaleAmount = ROW_PRESS_SCALE, glowAmount = 0f)
    // 上一次点击的时间戳（0 = 没有待配对的点击）。用 longArray 而不是 mutableStateOf：
    // 它只是手势内部的一个记事本，不需要驱动重组。
    val lastTapAt = remember(entry.file.absolutePath) { longArrayOf(0L) }
    // ---- 长按（进多选）的"有没有出界"观察者（本轮新增，用户全局性要求）----
    //
    // 用户原话："**把所有按钮的长按判定都改成只有在按钮的矩形框内才判定。**"
    // 这一行的长按走 `detectTapGestures(onLongPress=…)`：AndroidX 本身会在出界时取消，
    // 但它的边界是"节点矩形 + 系统最小触控目标补白"——这一行只有 0.115 × 短边 ≈ 26.8dp 高，
    // 补白上下各 ≈10.6dp（行与行之间只隔 ≈2.3dp），等于"手指飘到隔壁行上，这一次长按还算数"。
    // 所以这里挂一个**零消费**的观察者，把边界收紧到"这一行自己的矩形 + 极小容差"。
    val longPressGuard = rememberPressInsideGuard(entry.file.absolutePath)

    // 选中态：底色在"普通键深灰"和"强调色"之间**渐变**（160ms，和功能界面选中 chip
    // 同一个节奏）。直接换色是"啪"地一下，圆表上特别扎眼 —— 用户要求"多选切换也要有过渡"。
    //
    // 用户 1.2.67："毛玻璃主题下……文件列表的文件条没有毛玻璃主题的按钮样式。"
    // 和函数列表行（`FunctionList`）同一条规则：玻璃主题下未选中时底色换成半透明亮面
    // （`GlassTokens.surface`），选中仍然走强调色。渐变照样从当前颜色平滑过去。
    val container by animateColorAsState(
        targetValue = when {
            selected -> KeyPalette.accent
            LocalGlassTheme.current -> GlassTokens.surface
            else -> KeyPalette.normal
        },
        animationSpec = tween(durationMillis = SELECT_MS, easing = FastOutSlowInEasing)
    )
    // 右边那个 ✓ 角标：0 → 1 驱动 alpha + 缩放（从 0.6 长出来），170ms。
    // 下面那个 Box 是**永远占位**的 —— 否则选中/取消会让文件名那一行左右横跳。
    val badge by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = BADGE_MS, easing = FastOutSlowInEasing)
    )

    Surface(
        // 底色：选中 = 强调色（和列表/键盘的选中态同一套），平时 = 普通键深灰。
        // 这一屏**没有**渐染红那一套了 —— 删除不再是"滑一下"的动作（见类注释）。
        color = container,
        shape = PillShape,
        modifier = Modifier
            .width(metrics.sizeDp * widthFraction)
            .height(metrics.sizeDp * ROW_H)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
                alpha = 1f - ROW_PRESS_DIM * press.progress.value
            }
            // 和函数列表行同一段玻璃材质（投影 + 1dp 亮边 + 上沿高光）。
            // ⚠️ 必须写在 `graphicsLayer` **后面（内层）**：这样按下的缩放 / 淡出
            // 连玻璃的影和边一起动，不会出现"底色缩了、亮边钉在原地"
            //（FunctionList 1.2.53 踩过同一个坑，注释就在那边）。
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .pointerInput(entry.file.absolutePath) {
                // key 只取路径：`selected`/`multiSelect` 都是**委托到 MutableState 的局部变量**，
                // 下面的回调每次调用都会读到当前值，所以不必把它们当 key ——
                // 加了反而每选一次就重启一次手势识别器（会把按到一半的手势打断）。
                detectTapGestures(
                    // 按压反馈（和 combinedClickable 的效果一致，只是自己做）
                    onPress = { offset ->
                        val p = PressInteraction.Press(offset)
                        press.source.emit(p)
                        if (tryAwaitRelease()) {
                            press.source.emit(PressInteraction.Release(p))
                        } else {
                            press.source.emit(PressInteraction.Cancel(p))
                        }
                    },
                    onTap = {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt[0] in 1 until DOUBLE_TAP_MS) {
                            // 第二下（300ms 内）= 双击 → 重命名
                            lastTapAt[0] = 0L
                            onRename()
                        } else {
                            lastTapAt[0] = now
                            onTap()
                        }
                    },
                    onLongPress = {
                        // 长按之后 detectTapGestures 不会再补一次 onTap，所以不会重复触发
                        lastTapAt[0] = 0L
                        // ⚠️ 本轮新增：手指在按住期间离开过这一行 → 这一次长按作废
                        // （不切多选）。判据与容差见 `PlotMetrics.pressInsideRect`。
                        if (longPressGuard.stayedInside) onLongPress()
                    }
                )
            }
            // 观察者：只记"手指有没有离开过这一行"，**一个字节都不消费**
            //（所以单击 / 双击重命名 / 多选切换全都不受影响）
            .pressInsideGuard(longPressGuard)
            .tutorialAnchor(anchorId)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * 0.035f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.baseName,
                    fontSize = metrics.fontSmall,
                    // 选中态也是纯白（底色换成强调色之后对比度仍然够）
                    color = KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 第二行小字：函数个数 / 滑动条个数 / 对象个数 / 字节数
                    text = entry.summary.text(entry.bytes),
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 选中角标：一个小 ✓（只画，不参与点击 —— 点整行才是选中）。
            // 外面的 Box **永远占位**（宽度固定）：选中/取消时文件名那一行不会左右跳；
            // 里面的 Icon 只在 alpha/缩放上做动画（见上面的 [badge]）。
            Box(
                modifier = Modifier.size(metrics.sizeDp * 0.095f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = null,
                    tint = KeyPalette.text,
                    modifier = Modifier
                        .size(metrics.sizeDp * 0.075f)
                        .graphicsLayer {
                            // 只在图层里读动画值：每帧只失效图层，不重组
                            alpha = badge
                            scaleX = BADGE_FROM + (1f - BADGE_FROM) * badge
                            scaleY = BADGE_FROM + (1f - BADGE_FROM) * badge
                        }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 这一屏原来还有一个私有 `RenameDialog`，是 `NameDialog` 的复制品（连注释都写着
// "数值也一样"）。用户这一轮要求"保存文件"和"文件重命名"这两个浮窗都要：
// **屏幕居中 / 自动适应屏宽 / 取消和确定在输入框下面 / 图标是 × 和 ✓ / 唤起时有动画** ——
// 两份实现各改一遍必然会漂，所以复制品**删掉**，重命名改成调用
// `SaveImageDialog.kt` 里的 `NameDialog`（调用点就在上面 `renameFor != null` 那一段）。
//
// 为什么这次的"删掉一份"没有越界：`NameDialog` 的**签名一个字没改**
// （title / metrics / pathHint / onCancel / onConfirm / initialName / hint /
// errorText / saving / modifier），所以 `GraphScreen.kt` 那边的调用点不需要任何改动，
// 重命名这边只是多传了 `title` 和 `pathHint` 两个本来就有默认值的参数。
// ---------------------------------------------------------------------------
