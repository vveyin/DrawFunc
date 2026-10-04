// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.round

// ---------------------------------------------------------------------------
// 版面几何
//
// ⚠️ 这里**刻意不再全部用「占屏短边的比例」**（本文件原来那一套，也是 WatchMetrics
// 的约定）：用户这一轮的要求是"每行的高度变成原来的 48dp"——48dp 是 Material 的
// 标准触控高度，它描述的是**手指的物理尺寸**（≈9mm），不是"这块表的百分之几"。
// 用真正的 `.dp` 写，恰好就是 WatchMetrics 想要的效果：同一块 466px 的屏，
// density 报 2.0 时 48dp = 24px、报 1.0 时 48dp = 48px，两种情况下在**手上**一样大。
// 所以"行高 / 行内小控件"这几处改用真正的 dp；而"列表上下沿、行宽的圆弧收窄"
// 继续用比例 —— 那些是圆屏几何，本来就和 density 无关。
//
// 另一件必须一起改的事：**行数不再参与几何计算**。
// 上一版把"实际显示的行数"（本机版本不够的行不显示）拿去平分固定的 0.683 屏高，
// 好在行数变化时不在底部留空洞 —— 那是"不能滚动"这个前提下才需要的补丁。
// 现在列表能滚、每行固定 48dp，行数多一行少一行只是"内容高一点/矮一点"，
// 滚动自己消化，几何不用跟着变（`rowHeight` / `listHeight` 因此都删掉了）。
// ---------------------------------------------------------------------------

/** 顶部工具条占屏高的 0.10 ~ 0.195。 */
private const val TOOLBAR_TOP = 0.10f
private const val TOOLBAR_H = 0.095f

/**
 * 工具条的下沿（0.195）。
 *
 * 单独写出来，只为了让 [LIST_TOP] **看着它**定 —— 这样"第一行不被返回键压住"
 * 就是算式保证的，而不是靠两个数碰巧对得上（改工具条位置时列表会跟着动）。
 */
private const val TOOLBAR_BOTTOM = TOOLBAR_TOP + TOOLBAR_H

/** 工具条与列表第一行之间的缝（占屏高）：0.02 × 233dp ≈ **4.7dp**。 */
private const val TOOLBAR_GAP = 0.02f

/**
 * 可滚动列表占屏高的 0.215 ~ 0.90（**上沿按真实屏高；下沿只在圆屏上还是 0.90**）。
 *
 * **上沿 = [TOOLBAR_BOTTOM] + [TOOLBAR_GAP] = 0.215**：工具条下沿是 0.195，
 *   中间留 4.7dp 的缝 —— 用户明确要求"第一行不能被工具条压住"，
 *   缝要看得见才算留了缝。原来这里是 0.20（只比工具条低 0.005 ≈ 1.2dp，
 *   看起来就是贴着返回键）。
 *
 * **下沿 0.90（只对圆屏有效）**：这个数是**按行宽反推**的，不是随手取的圆整值。
 *   圆屏在高度 y 处的可用宽度是 `circleWidthFraction(y)`，越靠下越窄；
 *   而设置行里最长的一行是"屏蔽系统手势 + 显示"（六个汉字 + 两个汉字），
 *   按这块屏的字号算约 129dp 宽（算式见 [ROW_PAD]）。
 *   * 一行**完整可见**的最低位置 = 它的下沿贴在 0.90 → 行中心在
 *     `0.90 - 24dp/233dp ≈ 0.797`，那里 `0.8045 × 0.95 × 233dp ≈ 178dp`，
 *     比 129dp 富余约 49dp —— 圆屏收口收掉的那点宽度完全够用；
 *   * 再往下放（原来这里是 0.883，函数列表那一屏甚至到 0.95）就只剩"半行露出来"
 *     的情形了：那种行的中心已经落到圆屏的窄口上，行宽会被夹到 [MIN_ROW_WIDTH]
 *     那个下限（121dp），"屏蔽系统手势"六个字就会出省略号。
 *     半行露出来的行本来就只看得见一条边，看不清无所谓，但既然下沿一个数就能
 *     避开，就没必要让它在滚动经过时闪一下省略号。
 *
 * ⚠️ **非圆屏（手机）上这个 0.90 不再参与**：它不是"屏幕高度的百分之几"，
 *   而是"圆屏拱口收到哪里为止"这个**圆屏几何**的结论，在矩形的屏上完全不成立。
 *   所以那一支换成"从 [LIST_TOP] 一路铺到屏幕底边、只留 [LIST_BOTTOM_PAD]"，
 *   也就是用户这一轮要的"设置页也铺满全屏"（见下面 `listHeight` 那三行）。
 *   手机上原来列表只到 `0.90 × 411dp = 370dp`，而屏高 850dp ——
 *   底下 480dp 全是空的，这就是"设置页还是没有全屏显示"。
 *
 * 列表**高度**：圆屏 = `sy(0.90) − sy(0.215)` = 0.685 屏高 ≈ 159.6dp（**逐像素不变**）；
 *   非圆屏 = `screenH − sy(0.215) − 0.05 × 屏宽`（手机上 ≈ 646dp）。
 *   七行内容一共 7×26.8 + 6×2.3 = 201dp（手机上 7×47.3 + 6×4.1 = 356dp），
 *   而行距是 0.115 + 0.010 = 0.125 屏高（圆表上 29.1dp）：
 *   圆屏可视区 159.6dp ≈ **5.5 行**，所以列表**必须能滚**（表冠 + 手指，见
 *   [ROTARY_SCROLL_FACTOR]）；手机上可视区 646dp，7 行（356dp）一屏全见、不用滚。
 */
private const val LIST_TOP = TOOLBAR_BOTTOM + TOOLBAR_GAP
private const val LIST_BOTTOM = 0.90f

/**
 * 行高 **0.115 屏高**（233dp 屏上 ≈ 26.8dp）与行间距 **0.010 屏高**（≈ 2.3dp）。
 *
 * 用户原话："设置里单项高度 48dp 还是太高了，一开始是多少 dp？改成一开始的高度"，
 * 追问后确认要的是 **26.8dp** —— 也就是"**一开始**"那个值：加「屏蔽系统手势 /
 * 按键缩放栏」这两行之前、设置页只有 5 行时的行高（0.115 屏高）。
 * （为了 7 行全塞下，中间曾缩到 20.7dp；我上一轮又被"48dp"带偏过。现在回到 26.8dp。）
 *
 * 为什么又变回"屏高比例"而不是 `dp`：这一屏的所有几何（工具条、列表上下界、
 * 弧宽、行宽）本来都是按屏算比例的，行高是**唯一**一个曾经写成绝对 dp 的值；
 * 回到比例写法，整屏的尺寸关系就又是同一套算法。
 *
 * 26.8dp 行高下 7 行内容约 **201dp**，比可视区（≈160dp）高 ——
 * 所以列表**需要滚动**，这正好和"形式变成可滚动的列表"那条要求对上
 * （列表是 `LazyColumn`，手指能滚、表冠也能滚，见 [ROTARY_SCROLL_FACTOR]）。
 */
private const val ROW_H = 0.115f

/**
 * 行间距：一开始就是 0.010 屏高（≈2.3dp）。
 *
 * 上一版被放大到 4dp；行高回到 26.8dp 之后那个缝显得太宽，所以一起回到原值 ——
 * 用户要的是"一开始的高度"，缝也该跟着回去，否则看起来仍然不是记忆里那个设置页。
 */
private const val ROW_GAP = 0.010f

/**
 * 行的左右内边距 **0.026 屏高**（≈6.1dp），标题与取值胶囊之间的间隔 **0.020**（≈4.7dp）。
 *
 * 和行高一样回到一开始的比例值。
 */
private const val ROW_PAD = 0.026f
private const val ROW_INNER_GAP = 0.020f

/**
 * 行宽在圆弧宽度之外再乘的余量（现在只剩顶部工具条在用它）。
 * 表壳是圆的，控件贴到数学边界上会被切掉一条边，留 7% 的余量更安全。
 */
private const val WIDTH_MARGIN = 0.93f

/**
 * 右侧「当前值」小胶囊的高度 / 横向内边距。
 *
 * **0.095 屏高（≈22.1dp）/ 0.024（≈5.6dp）** —— 回到一开始的值：
 *   行高是 0.115 时胶囊 0.095（26.8dp 的行里放 22.1dp 的胶囊，上下各留 ≈2.3dp）；
 *   行高缩到 0.089 时它也被压到 0.076，我上一轮又放到 26dp。
 * 这两个数**必须配套**：胶囊比行高就会顶出行的胶囊边界（26dp 挂在 20.7dp 的行上就是这样）。
 */
private const val CHIP_H = 0.095f
private const val CHIP_PAD = 0.024f

// ---- 行的宽度（圆屏收口，整套照抄 FunctionList.kt / SavesScreen.kt）----
//
// 用户要求"照抄它们的做法，别自己发明"，所以这里一个数都没改：
//   * 方屏（或拿不到屏幕形状时）行就直接占 [SQUARE_ROW_WIDTH]；
//   * 圆屏按行**当前**所在高度的圆弧宽度收窄，再乘 [ARC_MARGIN] 留点余量，
//     最后夹在 [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间，
//     于是列表从上到下天然"中间宽、两头窄"；
//   * 宽度量化到百分位 —— 理由写在 [rowWidthFraction] 的注释里（不量化的话，
//     滚动时每一帧的布局变化会让每个可见行每帧重组）。
// 没有直接 import 那两个文件的常数：它们是 `private const`，而本轮禁改那两个文件，
// 复制数值是唯一不越界的做法（代价是将来改分区要改三处，已写进报告）。
/** 方屏（或拿不到屏幕形状时）用满的宽度。 */
private const val SQUARE_ROW_WIDTH = 0.90f
/** 圆屏上允许的最窄行宽：再窄下去文字就没法看了。 */
private const val MIN_ROW_WIDTH = 0.52f
/** 用满圆内宽度时留一点余量，免得行角正好压在表壳边缘上。 */
private const val ARC_MARGIN = 0.95f

/**
 * 表冠一格对应滚动多少像素。
 *
 * 和 FunctionList / SavesScreen / AlphabetKeyboard 是**同一个值、同一个理由**
 * （那块表的表冠一次事件给的是几十到一百多像素，1.0 倍大约一格滑半屏）：
 * 几个可滚动列表用同一个系数，转表冠的手感才是同一套。
 */
private const val ROTARY_SCROLL_FACTOR = 1.0f

// ---- 动画 ----
/** 每行入场：单行 220ms，第 index 行再晚 35ms —— "很轻"的交错。 */
private const val ROW_IN_MS = 220
private const val ROW_STAGGER_MS = 35
/** 入场时从下方上移的距离（占屏短边的比例，233dp 屏上约 3.5dp）。 */
private const val ROW_IN_SLIDE = 0.015f
/** 按下整行时缩到 0.98：这一行很大，缩一点点就够。 */
private const val ROW_PRESS_SCALE = 0.02f
/** 取值 chip 的弹跳峰值与两段时长（合计 200ms）。 */
private const val CHIP_POP = 1.18f
private const val CHIP_POP_UP_MS = 80
private const val CHIP_POP_DOWN_MS = 120

// ---------------------------------------------------------------------------
// 「这条设置在本机能不能用」的统一规则
//
// **判定表在 `GraphState.kt`**（`GatedSetting` / `minSdkFor` / `supportsSetting`），
// 因为模型那边也要用同一张表去 coerce 取值（本机不支持的设置要压成安全默认值）——
// 判定只写一份，界面和模型读的是同一个数。
//
// 这一屏只负责**规则 1：本机版本不够的那一行干脆不显示**（不占位、也不排进列表内容）。
// 行数因此不再是固定的 7 —— 但列表现在**可滚动**，所以行数变化只是"内容高一点/矮一点"，
// 不需要像上一版那样把行高按行数平分（那一套 `rowHeight`/`listHeight` 已经随这一轮的
// 48dp + 滚动列表一起去掉了）。
//
// 历史说明：上一版这里是"点一下弹 toast 拒绝切换、并把整行压暗"。用户否掉了那种交互 ——
// 一行既然用不了，就不该出现在设置页里让用户去点（他看不到这一项，也就不会误会）。
// 所以 `onMessage` / 压暗用的 `enabled` / 提示文案那一套**全部删掉了**。
// ---------------------------------------------------------------------------

/** 设置页里的一行：标题、当前取值、点击后怎么改、以及它要不要系统版本支持。 */
private class SettingRowSpec(
    val title: String,
    val value: String,
    /** 非空 = 这项有系统版本门槛；本机版本不够时**整行不显示**（见 `supportsSetting`）。 */
    val gate: GatedSetting? = null,
    val onClick: () -> Unit
)

/**
 * 这一屏真正会显示的行（按顺序）。
 *
 * 顺序 = 用户看到的顺序（坐标系 / 网格 / 坐标轴 / 坐标数字 / 函数示例 /
 * 屏蔽系统手势 / 按键缩放栏）；被版本门槛滤掉的那些**不留空位**，后面的行自然上移。
 */
private fun settingsRows(
    settings: WatchSettings,
    onChange: (WatchSettings) -> Unit
): List<SettingRowSpec> = listOf(
    SettingRowSpec("坐标系", settings.coordinateSystem.label) {
        onChange(settings.copy(coordinateSystem = settings.coordinateSystem.next()))
    },
    SettingRowSpec("网格", onOffLabel(settings.showGrid)) {
        onChange(settings.copy(showGrid = !settings.showGrid))
    },
    SettingRowSpec("坐标轴", onOffLabel(settings.showAxes)) {
        onChange(settings.copy(showAxes = !settings.showAxes))
    },
    SettingRowSpec("坐标数字", onOffLabel(settings.showNumbers)) {
        onChange(settings.copy(showNumbers = !settings.showNumbers))
    },
    SettingRowSpec("函数示例", onOffLabel(settings.showPresets)) {
        onChange(settings.copy(showPresets = !settings.showPresets))
    },
    // ---- 需求 #6：屏蔽系统手势（默认开）----
    // 门槛 = Android 10（`View.setSystemGestureExclusionRects`）。
    // 低版本上：这一行不显示，而且值已经被 `WatchSettings.coercedForThisDevice`
    // 压成 false，`GraphScreen` 里那个调用也不会发生 —— **完全不做任何事**（最安全的默认）。
    SettingRowSpec(
        title = "屏蔽系统手势",
        value = yesNoLabel(settings.blockSystemGestures),
        gate = GatedSetting.BLOCK_SYSTEM_GESTURES
    ) {
        onChange(settings.copy(blockSystemGestures = !settings.blockSystemGestures))
    },
    // ---- 需求 #7：底部按键 / 滑动条缩放栏（默认关）----
    SettingRowSpec("按键缩放栏", onOffLabel(settings.showZoomBar)) {
        onChange(settings.copy(showZoomBar = !settings.showZoomBar))
    }
).filter { it.gate == null || supportsSetting(it.gate) }

/**
 * 设置界面。
 *
 * 交互只有一个：**每一行点一下就切到下一个取值**。
 * 不用 Switch / Checkbox —— 圆表上开关控件太占地方，而且这里每项
 * 最多三两个取值，循环切换比"先瞄准再拨动"快得多。
 *
 * 右侧用 primaryContainer 的小 chip 显示当前值，明确告诉用户"这里能点"。
 *
 * 这里**故意不放**任何"重置 / 清空"入口：设置页离"清除数据"太近，
 * 手表上误触的代价不可逆。
 *
 * 版面（这一轮改的）：**每行 48dp、整块列表可滚动**。七行 × 48dp = 336dp，
 * 比整块屏（233dp）还高，所以"能滚"是这个版面的前提而不是附加功能：
 * 手指拖列表能滚，**表冠也能滚**（往下拨 = 往下滚）。
 * 圆屏的左右收口照抄 FunctionList / SavesScreen：每一行的宽度按它**此刻**在屏幕上的
 * 高度算（见 [rowWidthFraction]），所以滚动时会看到"中间宽、两头窄"。
 *
 * @param settings 当前设置（来自 [GraphState.settings]；本机不支持的项已经在模型层
 *                 被压成安全值，这里再按版本门槛决定显示哪几行）
 * @param metrics 由 [LocalWatchMetrics] 提供的尺寸基准
 * @param onChange 任何一行被点击后，交回**完整的新设置**
 * @param onBack 返回上一屏
 * @param rotary 表冠 / 滚轮事件流：在这一屏**只用来滚动列表**（和函数列表、文件列表、
 *               字母键盘同一个接法、同一个系数）。
 *               **给了默认值（一股空流）**，因为外壳 `GraphScreen` 目前还没把
 *               `rotary = rotary` 传进来 —— 这是本轮唯一需要调用方配合的一行：
 *               在 `Route.Settings` 那一支的 `SettingsScreen(...)` 里补上
 *               `rotary = rotary` 就生效。默认值的作用是"没补这一行也能编译、也能跑"，
 *               只是那种情况下表冠不滚动（和改动之前的行为一样，不会更糟）。
 */
@Composable
fun SettingsScreen(
    settings: WatchSettings,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow()
) {

    // 各行的入场：本屏**第一次组合**时播一次（也就是"每次打开设置页播一次"，
    // 这正是想要的"打开就有反应"）。
    //
    // 状态放在屏幕这一层，各行共用同一个目标值：行自己不留状态，
    // 所以换取值、行内重排都不会各自重播，只有整屏重新打开才会再播一次。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // ---- 列表状态与"这一屏在屏幕上的几何" ----
    val density = LocalDensity.current
    val isRound = LocalConfiguration.current.isScreenRound
    val listState = rememberLazyListState()

    // 列表上沿在屏幕上的 y（像素）要在 `BoxWithConstraints` 里面算（它要真实屏高），
    // 见下面 [rowWidthFraction] 处那段说明。

    // ---- 表冠（rotary）----
    //
    // 和 FunctionList / SavesScreen / AlphabetKeyboard **一字不差**的接法：
    // 收集调用方转进来的滚动量，`scrollBy(-amount)`（往下拨 = 往下滚），
    // 系数用同一个 [ROTARY_SCROLL_FACTOR]，几个列表界面的手感才是同一套。
    // 手指直接拖列表也照常能滚 —— 那是 LazyColumn 自带的手势，和表冠互不干扰。
    //
    // ⚠️ 生效的前提是调用方把外壳的表冠流传进来（`rotary = rotary`），
    // 见函数签名上那段说明：不传的话这里收到的是一股空流，列表就只剩手指能滚。
    LaunchedEffect(rotary) {
        rotary.collect { amount ->
            listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
        }
    }

    // ---- 本机实际要显示的设置行（版本不够的那些已经在 settingsRows 里被滤掉）----
    //
    // 行数**不写死**：行高一律 48dp，行数多一行少一行只是"内容高一点/矮一点"，
    // 由下面的 LazyColumn 自己消化 —— 能滚，所以不需要像上一版那样按行数平分屏高。
    val entries = settingsRows(settings, onChange)

    BoxWithConstraints(Modifier.fillMaxSize()) {

        // ---- 真实可用尺寸（不是短边）----
        //
        // 和函数列表 / 功能界面是**同一层**换算（定义在 `FunctionList.kt` 的
        // [ScreenGeometry] 上）：`WatchMetrics.sizeDp` 是**短边**——圆表上短边 == 屏高、
        // 方表上也相等，所以"按短边算"在手表上一直看不出问题；手机上短边是**屏宽**
        // （411dp），于是设置页的列表带、行宽、工具条位置全部被压到顶部一个方格里：
        //   * 列表下沿 `0.90 × 411dp = 370dp`，而屏高 850dp —— 底下 480dp 全空
        //     （用户："软件的设置页还是没有全屏显示"）；
        //   * 行宽 349~370dp（屏宽的 85~90%，还是按"短边 × 弧宽比例"算出来的）。
        // 现在：位置按真实屏高、行宽非圆屏铺满（386dp）—— 控件尺寸（行高 26.8dp、
        // 字号、取值胶囊）**一个都没改**，仍按短边缩放。
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)

        // 列表上沿在屏幕上的 y（像素）。算每一行的圆弧宽度要用它把
        // "行在列表里的偏移"换算成"行在屏幕的哪个高度"，见 [rowWidthFraction]。
        // ⚠️ 这里也换了基准：上沿按真实屏高，分母（下面传进 [rowWidthFraction] 的
        // `screenPx`）是**真实屏高**。圆表上两者相等（短边 == 屏高），所以弧宽逐像素不变。
        val listTopPx = with(density) { geometry.sy(LIST_TOP).toPx() }
        val screenHeightPx = with(density) { geometry.screenH.toPx() }

        /**
         * 列表可视区高度。
         *
         * * 圆屏：`sy(LIST_BOTTOM) − sy(LIST_TOP)` —— 逐像素等于原来的
         *   `sizeDp × (0.90 − 0.215)`（圆表上 `screenH == sizeDp`）。
         *   这里**刻意不给圆屏"铺到屏幕底部"**：[LIST_BOTTOM] 是按圆屏拱口
         *   （越靠下弦越短）反推出来的几何结论，不是"屏高的百分之几"，
         *   往下延到 0.95 会让滚到底的那一行被夹到 [MIN_ROW_WIDTH]、
         *   标题出省略号（那段推导就在 [LIST_BOTTOM] 上面）。
         * * 非圆屏：一路铺到屏幕底边、只留 [LIST_BOTTOM_PAD]（和函数列表
         *   `ScreenGeometry.listHeight` 同一个口径）—— 这就是用户要的"铺满整屏"。
         */
        val listHeight = if (isRound) {
            geometry.sy(LIST_BOTTOM) - geometry.sy(LIST_TOP)
        } else {
            geometry.listHeight(metrics, geometry.sy(LIST_TOP))
        }

        // ---- 顶部只有一颗「返回」 ----
        // 按用户要求，界面尽量不写标题：表盘就 1.5 寸，标题占的地方不如留给内容。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 位置按**真实屏高**（圆屏上逐像素不变）
                .offset(y = geometry.sy(TOOLBAR_TOP))
                // 工具条的宽度也按它所在高度的圆弧宽度收窄，否则返回键会被表壳切掉；
                // 非圆屏（手机）交给 [ScreenGeometry.rowWidth] 铺满屏宽减左右余量 ——
                // 和列表行、函数列表的工具条是同一个口径。
                .width(
                    // ⚠️ 传的是**行中心**高度（上下沿传同一个数）：原来这一处就是按
                    // 中心高度 `cwf(TOOLBAR_TOP + TOOLBAR_H/2) × 0.93` 算的，
                    // 圆屏上必须逐像素一致，所以不能改成"取上下沿里更窄的那处"。
                    bandWidth(
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        yTop = TOOLBAR_TOP + TOOLBAR_H * 0.5f,
                        yBottom = TOOLBAR_TOP + TOOLBAR_H * 0.5f,
                        arcMargin = WIDTH_MARGIN
                    )
                )
                .height(metrics.sizeDp * TOOLBAR_H),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                metrics = metrics,
                onClick = onBack
            )
        }

        // ---- 设置行：**可滚动**列表（行数随本机支持情况变）----
        //
        // 为什么换成 LazyColumn（原来是一个定高的 Column）：
        //   七行 × 48dp = 336dp，比整块屏（233dp）还高 —— 塞不进固定高度里了。
        //   用户这一轮要的就是"列表可滚动"，所以这里和 FunctionList / SavesScreen
        //   用同一个容器、同一套弧宽收窄、同一个表冠接法。
        //
        // 三件和那两个文件对齐的事：
        //   1. 容器 `fillMaxWidth()`，**行自己**按所在高度的圆弧宽度收窄并居中
        //      （靠 horizontalAlignment）。行宽不能用"第几行"算 —— 列表会滚，
        //      必须按每一行**当前**在屏幕上的高度算，见 [rowWidthFraction]；
        //   2. 行间距交给 `Arrangement.spacedBy(ROW_GAP)`，不自己塞占位行；
        //   3. `key` 用标题（各行标题互不相同）：滚动回收重建时行才不会认错自己，
        //      也不会把"另一行的入场进度/chip 弹跳"套到这一行上。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 上沿按**真实屏高**（圆屏上逐像素不变）
                .offset(y = geometry.sy(LIST_TOP))
                .fillMaxWidth()
                // 左右余量只在非圆屏上生效：圆屏上行宽本来就是按弧宽收过的
                // （最宽 0.90 屏宽，离屏幕边还有 0.05 屏宽），再加边距等于把圆表上的行
                // 又缩窄一圈 —— 圆屏这一支一点都不能动（和 FunctionList / FunctionsScreen
                // 里那两处 `.padding(horizontal = if (isRound) 0.dp else ...)` 同一个做法）。
                .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))
                // 高度：圆屏还是 0.90 下沿（逐像素不变），非圆屏一路铺到屏幕底边
                .height(listHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_GAP)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.title }) { index, entry ->
                val widthFraction = rowWidthFraction(
                    listState = listState,
                    itemKey = entry.title,
                    listTopPx = listTopPx,
                    screenPx = screenHeightPx,
                    metrics = metrics,
                    isRound = isRound
                )
                SettingsRow(
                    title = entry.title,
                    value = entry.value,
                    widthFraction = widthFraction,
                    // index 只用来做入场交错（整屏第一次组合时各行依次淡入）
                    index = index,
                    entered = entered,
                    metrics = metrics,
                    geometry = geometry,
                    isRound = isRound,
                    onClick = entry.onClick
                )
            }
        }
    }
}

/**
 * 布尔设置统一显示成「显示 / 隐藏」。
 *
 * ⚠️ 只有「屏蔽系统手势」那一行例外，用 [yesNoLabel]（是 / 否）——
 * 用户原话："我只说屏蔽系统手势的选项是是和否，你给其他都改成是和否干啥啊，
 * 改回来，改成显示和隐藏。"
 * 理由也说得通：「网格 / 坐标轴 / 坐标数字 / 函数示例」描述的是"画不画出来"，
 * 用显示/隐藏最自然；而「屏蔽系统手势」是"开不开某个行为"，"屏蔽 + 显示"
 * 读起来自相矛盾，所以它单独用是/否。
 */
private fun onOffLabel(on: Boolean): String = if (on) "显示" else "隐藏"

/**
 * 「屏蔽系统手势」专用的取值文案：**是 / 否**。
 *
 * 为什么这一行不能跟别的行共用 [onOffLabel]：
 *   * 标题是"屏蔽系统手势"，写「显示」就变成"屏蔽系统手势：显示"——
 *     到底是"显示了这个设置项"还是"正在屏蔽"？读不通；
 *   * 写「是 / 否」时，整行读作"屏蔽系统手势：是"，语义唯一。
 */
private fun yesNoLabel(on: Boolean): String = if (on) "是" else "否"

/**
 * 算出某一项**当前**应该有多宽（占屏宽的比例）。
 *
 * 圆屏上"越靠中间越宽"，所以行宽必须跟着它在屏幕上的**实际竖直位置**走，
 * 而不是按序号算 —— 后者一滚动就不对了（这也正是上一版
 * `rowWidth(index, rowCount, metrics)` 必须删掉的原因：列表能滚之后，
 * "第几行"和"在屏幕的哪个高度"再也不是一回事）。
 *
 * 和 `FunctionList.rowWidthFraction` / `SavesScreen.rowWidthFraction` 一模一样，
 * 只是把"为什么"重新写了一遍 —— 注意 `FunctionList` 那份**就是用户指定的"唯一参照实现"**
 * （他说"我说的宽度适配是像函数列表的示例的那些按钮一样"，而示例按钮那一行用的正是
 * 这个函数 + `geometry.rowWidth(...)`）：同样的 `ARC_MARGIN = 0.95f`、
 * 同样的量化到百分位、同样的 `[MIN_ROW_WIDTH, SQUARE_ROW_WIDTH] = [0.52, 0.90]`、
 * 非圆屏同样返回 1f 交给 `geometry.rowWidth`（= 屏宽 − 左右各 3%）。
 * 所以设置页的行和示例按钮**是同一套算法**，不是第二套。
 *   * **量化到百分位**（`round(raw * 100) / 100`）：`LazyListState.layoutInfo`
 *     在滚动时每帧都在变，直接读它会让每个可见行每帧都重组；包一层
 *     `derivedStateOf` 再量化之后，只有"算出来的宽度真的变了"才会重组 ——
 *     滚一整屏也就变几十次；
 *   * 查不到这一项时（还没测到它、或者已经滚出屏幕）退回 [SQUARE_ROW_WIDTH]，
 *     反正下一帧就有了，不会闪一个奇怪的窄行。
 *
 * @param itemKey 这一项在 LazyColumn 里的 `key`（这里就是行标题）
 * @param listTopPx 列表上沿在屏幕上的 y（像素）
 * @param screenPx **真实屏高**（像素）—— 两者相除才是"屏幕高度的百分之几"。
 *   ⚠️ 原来是 `metrics.sizePx`（**短边**）：圆表上短边 == 屏高所以一个数都不差，
 *   手机上短边是屏宽，弧宽会按错误的"屏高"去算（虽然非圆屏那一支已经提前返回了，
 *   但分母保持正确才不会有下一次踩坑）。
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
    // 非圆屏（手机 / 方表）：宽度不在这里算 —— 由调用方交给
    // `ScreenGeometry.rowWidth` 直接给"铺满屏宽减左右余量"。这里返回 1f 只是个占位
    // （调用方拿它当"忽略这个比例"的信号），和 `FunctionList.rowWidthFraction` /
    // `FunctionsScreen` 里那个同名函数**逐字同一套**。
    //
    // 原来这里返回的是 [SQUARE_ROW_WIDTH]（0.90），行宽 = `sizeDp × 0.90`：
    // 手机上那是 `411dp × 0.90 = 370dp` —— 行本身有屏宽的 90%，问题不在"窄柱"，
    // 而在**它和列表下沿一起把整屏压成了顶部一块**（列表只到 370dp、底下 480dp 全空）。
    // 现在行宽铺满（386dp），高度那件事见上面 `listHeight` 的三行。
    if (!isRound) return 1f
    val fraction by remember(itemKey) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf SQUARE_ROW_WIDTH
            // 这一行的中心落在屏幕高度的百分之几处
            val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx
            val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
            (round(raw * 100f) / 100f)
                .coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
        }
    }
    return fraction
}

/**
 * 一行设置：左标题、右当前值 chip，**整行可点**。
 *
 * 点击区覆盖整行而不是只有 chip —— 圆表上手指落点本来就飘，
 * 大目标比精确点 chip 靠谱得多。
 *
 * @param widthFraction 这一行**当前**应该占屏宽的比例（由 [rowWidthFraction] 按它
 *                      此刻在屏幕上的高度算出来）；非圆屏上是 `1f`（占位），
 *                      真实宽度由 `geometry.rowWidth` 给"铺满屏宽减左右余量"。
 *                      行自己不做任何圆屏判断，只管用这个宽度 ——
 *                      圆屏逻辑只有 [rowWidthFraction] 一处。
 * @param geometry 真实屏幕几何：非圆屏靠它把行宽铺满（圆屏上这两位不产生差异）
 * @param index 第几行（0 起）——只用于入场交错
 * @param entered 入场动画的目标值（true = 已经就位）。各行共用屏幕那一个状态，
 *                行自己不留状态，所以重建/滚动都不会重播。
 */
@Composable
private fun SettingsRow(
    title: String,
    value: String,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    onClick: () -> Unit
) {

    // 按下整行缩到 0.98（不要高亮：整行变大块白色很吵）。
    val press = rememberPressFeedback(scaleAmount = ROW_PRESS_SCALE, glowAmount = 0f)

    // 入场：淡入 + 从下方极小的上移。
    // 这里刻意**不**用 AnimatedVisibility：各行是 spacedBy 排的定高行，
    // 一旦内容在入场期间"不占位"，行的位置就会一节节往下跳。
    // 全部走 graphicsLayer —— alpha / translationY 都不参与布局，
    // 行的位置、尺寸、点击区从第一帧到最后一帧完全一致。
    //
    // 初值：entered 一开始是 false，所以第一帧 alpha = 0（明确的"未显示"），
    // 不会先闪一下再淡入。
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )

    Surface(
        // 统一到 KeyPalette：设置行就是一颗深灰胶囊（和函数列表行、键盘按键同一套色）
        color = KeyPalette.normal,
        shape = PillShape,
        modifier = Modifier
            // 宽度按"这一行此刻在屏幕上的高度"收窄（圆屏），比例由调用方算好传进来；
            // 非圆屏（手机）走 [ScreenGeometry.rowWidth] 那一支 —— 铺满屏宽减左右余量。
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            // 行高 = 0.115 屏高 ≈ 26.8dp（一开始那套比例值，**仍按短边缩放**，
            // 本轮没改：用户明确说"行高/字号/胶囊尺寸仍按短边缩放，别改"）
            .height(metrics.sizeDp * ROW_H)
            .graphicsLayer {
                alpha = reveal
                // 往上"落"到位：reveal = 0 时整行在下方 3.5dp 处
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
                scaleX = press.scale
                scaleY = press.scale
            }
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_INNER_GAP)
        ) {
            Text(
                text = title,
                fontSize = metrics.fontSmall,
                color = KeyPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 标题占据剩余空间，把 chip 顶到行的右端
                modifier = Modifier.weight(1f)
            )

            ValueChip(text = value, metrics = metrics)
        }
    }
}

/**
 * 当前值小 chip。
 *
 * 用 primaryContainer / onPrimaryContainer —— MD3 里这是"主要、可选中的容器"，
 * 一眼就能看出它代表一个可以切换的值（不是只读文本）。
 * 不直接用 Switch：圆表上开关控件又高又占地方。
 *
 * 取值一变就弹一下：1.0 → 1.18 → 1.0，共 200ms。
 *
 * 高度固定 [CHIP_H]（0.095 屏高 ≈ 22.1dp，一开始的值）—— 行高 26.8dp，
 * 胶囊比行高就会顶出行的胶囊边界，所以这两个数必须配套。
 */
@Composable
private fun ValueChip(text: String, metrics: WatchMetrics) {

    // 以显示的文本为 key：只有**这一行**的取值真的换了才会重播。
    // 第一次组合也要吃掉（popSeen）—— 否则刚进设置页各行会一起弹，
    // 那是"吵"，不是"灵动"；入场交给整行的交错淡入。
    val pop = remember { Animatable(1f) }
    var popSeen by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (!popSeen) {
            popSeen = true
            return@LaunchedEffect
        }
        pop.snapTo(1f)
        pop.animateTo(
            targetValue = CHIP_POP,
            animationSpec = tween(durationMillis = CHIP_POP_UP_MS, easing = FastOutSlowInEasing)
        )
        pop.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = CHIP_POP_DOWN_MS, easing = FastOutSlowInEasing)
        )
    }

    Surface(
        color = KeyPalette.accent,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = Modifier.graphicsLayer {
            // 只缩放图层：chip 的占位、行里标题的宽度、整行的点击区全都不受影响。
            scaleX = pop.value
            scaleY = pop.value
        }
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
