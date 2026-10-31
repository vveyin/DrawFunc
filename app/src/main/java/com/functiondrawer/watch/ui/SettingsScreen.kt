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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.CalcPrecision
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.round
import androidx.compose.ui.graphics.Color

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

/**
 * 「更多菜单」**入口行**的高度：0.16 屏高（233dp 屏上 ≈ 37dp）。
 *
 * 比普通设置行（[ROW_H] ≈ 26.8dp）高一截。入口行只剩**单行标题**
 * （副标题按用户要求去掉），但四个大块仍是这一屏的主角 ——
 * 行高给足才像"大块"，和普通行拉开一眼的层级差。
 */
private const val ENTRY_H = 0.16f

/** 入口行右侧那颗指向箭头的尺寸（0.05 屏高 ≈ 11.6dp，和滑条滑块同径）。 */
private const val ENTRY_CHEVRON = 0.050f

// ---- 灵敏度滑条的几何（「表冠适配」子页专用，自己画）----
//
// 为什么不复用 `SliderRow.kt` 那个滑条：它是函数行（列表界面）那一套 —— 带
// 删除/播放键、长按改属性、滑轨嵌在行里；这里要的是"标题 + 值 + 一条裸滑轨"，
// 拿它来改比新写一个更绕。样式参照 `ZoomBar` 的轨道与 `SliderRow` 的滑轨：
// 同一条圆角轨道、同一个 primary 分段画法。

/** 滑轨高度（233dp 屏上 ≈ 5.8dp，和 ZoomBar 的缩放条一个量级）。 */
private const val TRACK_H = 0.025f

/** 滑块直径（≈11.6dp）：比滑轨粗一截，手指与视线都找得到。 */
private const val THUMB_D = 0.050f

/** 滑块被按下时放大的倍数。 */
private const val THUMB_PRESS_SCALE = 1.25f

/**
 * 灵敏度行比普通行高出的那一截（0.09 屏高 ≈ 21dp）：滑轨 ≈5.8dp +
 * 标题与滑轨之间的缝 ≈5dp + 上下呼吸位。整行 ≈ 26.8 + 21 = 47.8dp，
 * 仍和「按键缩放栏」那些行同一个量级，不会在列表里显得突兀。
 */
private const val CROWN_ROW_H_EXTRA = 0.09f

/**
 * 分组标题行的高度（占屏高，3.40）。
 *
 * 只有一行小字，所以取普通行（[ROW_H] = 0.115）的一半左右：
 * 再高就会让"模糊背景"这组在列表里显得松垮。
 */
private const val HEADING_H = 0.062f

/** 标题排与滑轨之间的缝（≈5dp）。 */
private const val CROWN_TITLE_GAP = 0.022f

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

/**
 * 设置页里的一项 —— 三种形状（五屏共用）。
 *
 * 用户本轮原话："把那些设置放到长按设置键的更多菜单里，分四个大块，点进去再设置，
 * 短按设置只打开函数设置界面。"
 *
 * 于是设置不再是"一屏平铺七行"，而是五屏（见本文件末尾的五屏结构说明）。
 * 每一屏都只是**一份 entry 清单**，版面/动画/手势/宽度自适应全部在
 * [SettingsScaffold] 里做一遍 —— 分屏不复制版面，这是这次重构的核心。
 */
private sealed interface SettingEntry {
    /** LazyColumn 的 key：三种 entry 各自唯一，滚动回收时不会认错行。 */
    val key: Any

    /**
     * 普通设置行：左标题、右取值 chip，**点一下切到下一个取值**。
     *
     * @param gate 非空 = 这项有系统版本门槛；本机版本不够时**整行不显示**
     *             （见 `supportsSetting`）
     */
    data class Row(
        val title: String,
        val value: String,
        val gate: GatedSetting? = null,
        val onClick: () -> Unit
    ) : SettingEntry {
        override val key: Any get() = title
    }

    /**
     * 「更多菜单」的**入口行**：只有标题 + 右箭头，点进去是另一屏。
     *
     * 用户本轮原话："更多设置界面的每个设置条都不要显示具体内容，这个软件是设计给
     * 手表用的，显示具体内容放不下。" —— 所以**没有副标题**：
     * 233dp 的圆表上，一行塞两行字（标题 + "里面有什么"）连标题都会被挤成省略号。
     * 当前值点进子页第一眼就能看到，不需要在这一行重复一遍。
     */
    data class Nav(
        val title: String,
        val onClick: () -> Unit
    ) : SettingEntry {
        override val key: Any get() = "nav:$title"
    }

    /**
     * **二选一的选项行**（本轮新增，目前只给「屏幕形状」用）：
     * 标题 + 选中时亮一颗对勾，整行高亮成强调色。
     *
     * 和 [Row] 的区别：那个是"点一下切到下一个值"（右侧 chip 告诉你当前值），
     * 而这里两个选项是**并列摆出来的**，用户一眼就能看到另一个也能选 ——
     * 所以选中状态直接体现在整行底色上，不靠右侧 chip。
     */
    data class Choice(
        val title: String,
        val selected: Boolean,
        val onClick: () -> Unit
    ) : SettingEntry {
        override val key: Any get() = "choice:$title"
    }

    /**
     * 灵敏度滑条行（「表冠适配」子页第二项）：标题 + 当前值 + 一条可拖的滑轨。
     *
     * 为什么要单独一种形状而不是复用 [Row]：滑条要**拖动**（跟手、实时回调），
     * 而 [Row] 是"点一下切下一个值"。硬塞进去只会让两者都变扭。
     */
    data class Speed(
        val speed: Float,
        val onChange: (Float) -> Unit
    ) : SettingEntry {
        override val key: Any get() = "speed"
    }

    /**
     * **分组标题**：一行居中的小字，不占整条行高（3.40）。
     *
     * 用户原话："在模糊背景的设置条上面加个居中的标题，内容是模糊背景。"
     *
     * 它和 [Row] 的标题不同 —— 不是"左侧标题 + 右侧取值"，而是一条纯粹的
     * 分区标签：居中、无底板、无点击、比正文小一号。用它来把"模糊背景"
     * 和它下面的开关 / 程度条捆成一组。
     */
    data class Heading(val title: String) : SettingEntry {
        override val key: Any get() = "heading:$title"
    }

    /**
     * **百分比滑条行**（3.40）：标题 + `NN%` + 滑轨，0%~100%。
     *
     * 用户原话："在模糊背景的设置条下面加个调表冠灵敏度同款的滑动设置条，
     * 内容是模糊程度，调整从0%到100%。"
     *
     * 和 [Speed] 是同一个形状、同一套手势（点一下跳、拖动跟手、松手量化），
     * 只是刻度**线性**而不是对数：0% 到 100% 每一格的心理距离一样。
     */
    data class Percent(
        val title: String,
        val value: Float,
        val onChange: (Float) -> Unit,
        /**
         * **松手那一下**的额外回调（3.45）。
         *
         * 用户原话："在设置调整模糊程度的之后（松开调整模糊程度的滑动条）……
         * 在设置页就要重新绘制模糊的背景。"
         *
         * [onChange] 在拖动中每一步都会回调（那是 UI 跟手必须的），
         * 但"重做一张模糊背景"是一次 RenderScript 卷积，拖动中每格都做会卡。
         * 所以重绘挂在**松手**这一次：拖动中只更新数值，手一松立刻出一张新图。
         */
        val onCommit: ((Float) -> Unit)? = null
    ) : SettingEntry {
        override val key: Any get() = "percent:$title"
    }
}

/**
 * 教程锚点：设置里只有这两行会被圈住（见 [TutorialAnchors]），其余行返回 null。
 *
 * 按**标题**认而不是给每个 `SettingEntry` 加字段：锚点只在教程里有意义，
 * 让数据类为了浮层多背一个字段并不划算。
 */
private fun tutorialAnchorFor(title: String): String? = when (title) {
    "坐标数字" -> TutorialAnchors.SETTING_NUMBERS
    "主题设置" -> TutorialAnchors.SETTING_THEME
    else -> null
}

/**
 * 「函数设置」这一屏真正会显示的行（短按 ⚙ 进来）。
 *
 * 顺序 = 用户看到的顺序（坐标系 / 网格 / 坐标轴 / 坐标数字 / 函数示例 /
 * 屏蔽系统手势 / 按键缩放栏）；被版本门槛滤掉的那些**不留空位**，后面的行自然上移
 * （过滤在 [SettingsScaffold] 里统一做）。
 *
 * ⚠️ 剩下的那些行（主题、表冠、应用信息）**不在这一屏** —— 它们在长按 ⚙ 的
 * 更多菜单里，各占一个子页。
 */
private fun functionSettingsEntries(
    settings: WatchSettings,
    onChange: (WatchSettings) -> Unit
): List<SettingEntry> = listOf(
    SettingEntry.Row("坐标系", settings.coordinateSystem.label) {
        onChange(settings.copy(coordinateSystem = settings.coordinateSystem.next()))
    },
    // 1.2.71：用户要求"在坐标系设置下加一栏：切换坐标系删除绘制图形（是/否）"。
    // 是 → 切坐标系时连功能界面画出来的对象一起清掉；
    // 否 → 保留对象，只把它们的**方程**换成新坐标系的写法（见 `ObjectEquations.kt`）。
    SettingEntry.Row("切换坐标系删除绘制图形", yesNoLabel(settings.clearObjectsOnCoordSwitch)) {
        onChange(settings.copy(clearObjectsOnCoordSwitch = !settings.clearObjectsOnCoordSwitch))
    },
    SettingEntry.Row("网格", onOffLabel(settings.showGrid)) {
        onChange(settings.copy(showGrid = !settings.showGrid))
    },
    SettingEntry.Row("坐标轴", onOffLabel(settings.showAxes)) {
        onChange(settings.copy(showAxes = !settings.showAxes))
    },
    SettingEntry.Row("坐标数字", onOffLabel(settings.showNumbers)) {
        onChange(settings.copy(showNumbers = !settings.showNumbers))
        // 教程第 18 步：**打开**坐标轴数字才算完成（关掉的时候不推进）
        if (!settings.showNumbers) Tutorial.report("numbers_on")
    },
    SettingEntry.Row("函数示例", onOffLabel(settings.showPresets)) {
        onChange(settings.copy(showPresets = !settings.showPresets))
    },
    // 说明：这里原来还有「屏蔽系统手势」和「按键缩放栏」两行，已按用户要求删除。
    //
    // 只删了**界面入口**，字段（`WatchSettings.blockSystemGestures` /
    // `showZoomBar`）和它们的行为都保留：
    //   * 屏蔽系统手势仍然是"默认开、整屏排除"（`GraphScreen` 里那段没动），
    //     只是不再让用户关掉 —— 这一屏回到 5 行的样子；
    //   * 缩放栏同理，行为按存档里的值走，老存档里的设置继续生效。
    // 这样做的好处是**存档格式不用改**，老用户升级后手感不变。
)

/**
 * 「函数设置」这一屏（**短按 ⚙** 打开）。
 *
 * 用户本轮原话："短按设置只打开函数设置界面。"
 *
 * 五行：坐标系 / 网格 / 坐标轴 / 坐标数字 / 函数示例。
 * （"屏蔽系统手势""按键缩放栏"两行已按用户要求删除，字段与行为保留。）
 *
 * 交互只有一个：**每一行点一下就切到下一个取值**。
 * 不用 Switch / Checkbox —— 圆表上开关控件太占地方，而且这里每项
 * 最多三两个取值，循环切换比"先瞄准再拨动"快得多。
 * 右侧用 primaryContainer 的小 chip 显示当前值，明确告诉用户"这里能点"。
 *
 * 这里**故意不放**任何"重置 / 清空"入口：设置页离"清除数据"太近，
 * 手表上误触的代价不可逆。
 *
 * @param settings 当前设置（来自 [GraphState.settings]；本机不支持的项已经在模型层
 *                 被压成安全值，这里再按版本门槛决定显示哪几行）
 * @param metrics 由 [LocalWatchMetrics] 提供的尺寸基准
 * @param onChange 任何一行被点击后，交回**完整的新设置**
 * @param onBack 返回上一屏
 * @param rotary 表冠 / 滚轮事件流：在这一屏**只用来滚动列表**
 *               （和函数列表、文件列表、字母键盘同一个接法、同一个系数）
 */
@Composable
fun SettingsScreen(
    settings: WatchSettings,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（见 [SettingsScaffold] 的同名参数）。 */
    backdropVisible: Boolean = false
) {
    SettingsScaffold(
        entries = functionSettingsEntries(settings, onChange),
        metrics = metrics,
        onBack = onBack,
        rotary = rotary,
        backdropVisible = backdropVisible
    )
}

// ===========================================================================
// 屏二：更多菜单（长按 ⚙）
// ===========================================================================

/**
 * 「更多菜单」（**长按 ⚙** 打开）。
 *
 * 用户本轮原话："把那些设置放到长按设置键的更多菜单里，分四个大块，点进去再设置。"
 *
 * 所以这一屏**不再平铺设置行**，只有四个入口行：表冠适配 / 触摸设置 /
 * 主题设置 / 应用信息。入口行**只写标题 + 右箭头**，不写副标题 ——
 * 用户原话："更多设置界面的每个设置条都不要显示具体内容，这个软件是设计给手表用的，
 * 显示具体内容放不下。"
 *
 * 入口行自己不含任何设置操作，点了只是去另一屏。
 *
 * @param onCrown / onTouch / onTheme / onAbout 四个大块各自的去向
 * @param rotary 表冠流：这一屏只用来滚动列表
 */
@Composable
fun MoreSettingsScreen(
    metrics: WatchMetrics,
    onBack: () -> Unit,
    onCrown: () -> Unit,
    onCalc: () -> Unit,
    onTouch: () -> Unit,
    onTheme: () -> Unit,
    onAbout: () -> Unit,
    onReplayTutorial: () -> Unit,
    /** 开发者模式开着时，这一屏末尾才多出「开发者模式」那一栏。 */
    developerMode: Boolean = false,
    onDeveloper: () -> Unit = {},
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（见 [SettingsScaffold] 的同名参数）。 */
    backdropVisible: Boolean = false
) {
    // 「重温新手教程」原来点了就从头开始；用户要求先给一个确定界面。
    // 这里只负责"问一句"，确认后仍然走原来的 `onReplayTutorial`（启动方式不变）。
    var replayConfirm by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(
            entries = listOf(
                SettingEntry.Nav("手表适配", onCrown),
                // 「计算设置」紧跟在「手表适配」下面（用户指定的位置）
                SettingEntry.Nav("计算设置", onCalc),
                SettingEntry.Nav("触摸设置", onTouch),
                SettingEntry.Nav("主题设置", onTheme),
                SettingEntry.Nav("应用信息", onAbout),
                // 教程的入口藏在这里：退出教程的确认框里就写着这句指路。
                SettingEntry.Nav("重温新手教程") { replayConfirm = true }
            ) + if (developerMode) {
                // 入口只在开发者模式开着时出现；关掉它的那一刻就退出这一屏，
                // 所以这里不会出现"点了之后入口还在"的中间态。
                listOf(SettingEntry.Nav("开发者模式", onDeveloper))
            } else {
                emptyList()
            },
            metrics = metrics,
            onBack = onBack,
            rotary = rotary,
            backdropVisible = backdropVisible
        )

        // 确认卡复用教程退出确认那一张（[TutorialConfirmCard]），不另做一套样式。
        if (replayConfirm) {
            TutorialConfirmCard(
                metrics = metrics,
                style = rememberTutorialStyle(),
                title = "重温新手教程？",
                body = "会从新建函数开始 再走一遍画图、存文件和改设置。",
                confirmText = "开始",
                confirmBackground = KeyPalette.accent,
                // 用户 1.2.67 原话："把重温新手教程的确定界面的确定键和取消键的按钮高度
                // 变成原来的两倍。" —— 和退出教程的确认卡（`TutorialExitConfirm`）同一个倍数，
                // 两处确认卡的手感因此一致。
                pillHeightScale = 2f,
                onCancel = { replayConfirm = false },
                onConfirm = {
                    replayConfirm = false
                    onReplayTutorial()
                }
            )
        }
    }
}

// ===========================================================================
// 屏三：表冠适配子页
// ===========================================================================

/**
 * 「手表适配」子页（更多菜单第一个大块）。
 *
 * 上下两段：
 *   * **屏幕形状** —— 「方屏 / 圆屏」两个并列选项，点了**立刻**写进应用级偏好
 *     （[AppPrefs.setScreenShape]），全场布局跟着换；
 *   * **表冠适配** —— 表冠模式（OPPO ⇄ 华为 / 三星）+ 灵敏度滑条
 *     （0.1 ~ 10.0，默认 1.0；对数刻度，理由见 [crownSpeedForPosition]）。
 *
 * 两种模式的检测机制差异写在 `GraphState.CrownMode` 的 KDoc 里 —— 简单说：
 * OPPO 的表冠走普通 MotionEvent 的滚轮轴，华为 / 三星走
 * `ACTION_SCROLL` + `SOURCE_ROTARY_ENCODER`，一套代码吃不下两种，
 * 所以让用户选一次，而不是猜。
 *
 * 改动**即时生效**（不等退出、不进草稿）：表冠没有"退出才生效"的语义，
 * 而模式 / 灵敏度恰恰要马上能试手感。落盘由调用方负责。
 */
@Composable
internal fun CrownSettingsScreen(
    settings: WatchSettings,
    /** 当前生效的屏幕形状（[AppPrefs.screenShape]）。 */
    shape: ScreenShape,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onScreenShape: (ScreenShape) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（见 [SettingsScaffold] 的同名参数）。 */
    backdropVisible: Boolean = false
) {
    SettingsScaffold(
        entries = listOf(
            // ---- 上半段：屏幕形状（应用级偏好，不属于 WatchSettings）----
            // 用户本轮要求："手表适配里屏幕形状的设置……改成和其他设置一样的设置条。"
            // 也就是**左标题 + 右取值**的一行，点一下切到下一个形状，
            // 不再是那种"两个整行选项 + 对勾"的排版。
            SettingEntry.Row("屏幕形状", shape.label) {
                onScreenShape(shape.next())
            },
            // ---- 下半段：表冠适配（还是原来那两项）----
            SettingEntry.Heading("表冠适配"),
            SettingEntry.Row("表冠模式", settings.crownMode.label) {
                onChange(settings.copy(crownMode = settings.crownMode.next()))
            },
            SettingEntry.Speed(
                speed = settings.crownSpeed,
                onChange = { s -> onChange(settings.copy(crownSpeed = s)) }
            )
        ),
        metrics = metrics,
        onBack = onBack,
        rotary = rotary,
        backdropVisible = backdropVisible
    )
}

// ===========================================================================
// 屏三 B：计算设置子页
// ===========================================================================

/**
 * 「计算设置」子页（更多菜单里紧跟「手表适配」）。
 *
 * 只有一件事：**调整计算精度** —— 保留 3 / 8 / 16 位小数。
 * 三个档位收敛成一条设置条（[SettingEntry.Row]）：左边标题、右边当前档位，
 * 点一下切到下一档（3 → 8 → 16 → 3 循环）。
 * 点一下**即时生效**（和表冠设置同一条路，不进草稿），落盘由调用方负责。
 */
@Composable
fun CalcSettingsScreen(
    settings: WatchSettings,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（见 [SettingsScaffold] 的同名参数）。 */
    backdropVisible: Boolean = false
) {
    SettingsScaffold(
        entries = listOf(
            SettingEntry.Heading("计算设置"),
            // 用户本轮要求："计算设置里面的设置改成和其他设置一样的设置条。"
            // 三个精度档位收敛成一条：左标题 + 右取值，点一下切下一档。
            SettingEntry.Row("计算精度", settings.precision.label) {
                onChange(
                    settings.copy(
                        precision = CalcPrecision.entries[
                            (settings.precision.ordinal + 1) % CalcPrecision.entries.size
                        ]
                    )
                )
            }
        ),
        metrics = metrics,
        onBack = onBack,
        rotary = rotary,
        backdropVisible = backdropVisible
    )
}

// ===========================================================================
// 屏四：触摸设置子页
// ===========================================================================

/**
 * 「触摸设置」子页（更多菜单第二个大块）。
 *
 * 两行：
 *   * **屏蔽系统手势**：把 App 的窗口**整屏**排除在系统手势之外
 *     （`View.setSystemGestureExclusionRects` + 覆盖整个窗口的矩形，
 *     实现见 `GraphScreen` 顶层的 `systemGestureExclusionRects` 那块）。
 *     手表上系统把"边缘上滑 / 侧滑"留给了回表盘、通知抽屉，而本 App 也有边缘手势
 *     （底部上滑回列表、左缘右滑进功能界面）—— 不排除就会互相抢。
 *   * **按键缩放栏**：底部那条 +/− 与滑动条。
 */
@Composable
fun TouchSettingsScreen(
    settings: WatchSettings,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（见 [SettingsScaffold] 的同名参数）。 */
    backdropVisible: Boolean = false
) {
    SettingsScaffold(
        entries = listOf(
            SettingEntry.Row(
                title = "屏蔽系统手势",
                value = yesNoLabel(settings.blockSystemGestures),
                gate = GatedSetting.BLOCK_SYSTEM_GESTURES
            ) {
                onChange(settings.copy(blockSystemGestures = !settings.blockSystemGestures))
            },
            SettingEntry.Row("按键缩放栏", onOffLabel(settings.showZoomBar)) {
                onChange(settings.copy(showZoomBar = !settings.showZoomBar))
            }
        ),
        metrics = metrics,
        onBack = onBack,
        rotary = rotary,
        backdropVisible = backdropVisible
    )
}

// ===========================================================================
// 屏五：主题设置子页
// ===========================================================================

/**
 * 「主题设置」子页（更多菜单第三个大块）。
 *
 * 一行：**夜间模式**。
 *
 * 用户 2026-10-17 原话："背景还是黑的。你在主题设置里面加个夜间模式的开关吧。"
 * 后来又补了一句把方向彻底说清：
 *
 * > "**我原来做的那一套 ui 应该是夜间模式的**。你要给白天模式的 ui 重新写一套配色，
 * >  而不是只把背景变成白色。"
 *
 * 所以这一行的语义是**字面意思**，不再反：
 *   * 「是」（默认）= **夜间** —— 原来那套黑底 UI（纯黑画布 + 粉彩曲线 + 深灰按键）；
 *   * 「否」= **白天模式** —— 白纸画布 + 深饱和曲线 + 浅灰按键，整套重新配过。
 *
 * 内部字段叫 `dayMode`、界面显示 `!dayMode`：
 * 字段名按"是否白天"取，是为了让 `FunctionDrawerTheme(dayMode = …)` 这个调用
 * 一眼能读对（上一版叫 `nightMode` 却 `true = 白底`，读代码的人必然会误会）。
 * 两者之间的那一次取反**只出现在这一行**。
 *
 * 切换**即时生效**（点一下当场换，不用退出去确认）：设置页自己也跟着换底色，
 * 用户在这一屏上就能看到效果 —— 而且换的是**整屏**，包括侧边的返回键、行底板、
 * 按钮的按压反馈，不是只有背景。
 */
@Composable
fun ThemeSettingsScreen(
    settings: WatchSettings,
    metrics: WatchMetrics,
    onChange: (WatchSettings) -> Unit,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（3.45：这一屏也要能看到那张糊图）。 */
    backdropVisible: Boolean = false,
    /**
     * 模糊程度"松手提交"（3.45）。
     *
     * 拖动中只走 [onChange] 更新数值，手一松调这个 —— 那里才重做一张背景图。
     */
    onStrengthCommit: ((Float) -> Unit)? = null
) {
    // 界面上显示的是"夜间模式"，内部字段是"白天模式" —— 取反只在这一处。
    val nightMode = !settings.dayMode
    SettingsScaffold(
        entries = listOf(
            // 用户要求："在夜间模式前面加一条主题设置"
            // 取值直接显示 `Material` / `液态玻璃`（枚举自己带 label，不在界面里另写一套词）。
            SettingEntry.Row("主题风格", settings.themeStyle.label) {
                onChange(settings.copy(themeStyle = settings.themeStyle.next()))
            },
            SettingEntry.Row("夜间模式", yesNoLabel(nightMode)) {
                // 点一下 = 在"夜间 / 白天"之间翻一档（等价于翻转 dayMode）
                onChange(settings.copy(dayMode = !settings.dayMode))
            },
            // ---- 模糊背景开关（本轮新增）----
            //
            // 用户原话："在主题设置里面加一个模糊背景的开关吧，这样让 material 主题
            // 也能使用模糊背景，让毛玻璃主题也能不使用模糊背景。"
            //
            // 它**紧跟在夜间模式后面**，因为这三行是同一件事的三个维度：
            // 主题风格（质感）／夜间模式（明暗）／模糊背景（背透不透）。
            // 把它塞进"主题风格"那一行做成三态会绕：用户要的是"玻璃 + 不糊"
            // 和"Material + 糊"这两种组合都能表达，两个独立开关才表达得全
            // （见 `WatchSettings.blurBackdrop` 那张四象限表）。
            //
            // ---- 3.40：这一组加了标题 + 模糊程度滑条 ----
            //
            // 用户原话："在主题设置里面，模糊背景的设置条上面加个居中的标题，
            // 内容是模糊背景，然后在模糊背景的设置条下面加个调表冠灵敏度同款的
            // 滑动设置条，内容是模糊程度，调整从0%到100%。"
            //
            // 标题用的是 [SettingEntry.Heading]（居中、无底板、只占一行字高），
            // 程度条用 [SettingEntry.Percent]（和表冠灵敏度同款，线性刻度）。
            SettingEntry.Heading("模糊背景"),
            SettingEntry.Row("模糊背景", yesNoLabel(settings.blurBackdrop)) {
                onChange(settings.copy(blurBackdrop = !settings.blurBackdrop))
            },
            SettingEntry.Percent(
                title = "模糊程度",
                value = settings.blurStrength,
                onChange = { onChange(settings.copy(blurStrength = it.coerceIn(0f, 1f))) },
                // 松手才重做背景（拖动中每格都卷积会卡）。
                onCommit = { v ->
                    onChange(settings.copy(blurStrength = v.coerceIn(0f, 1f)))
                    onStrengthCommit?.invoke(v.coerceIn(0f, 1f))
                }
            ),
            // ---- 按钮下阴影强度（用户点名：默认 10%）----
            //
            // 和「模糊程度」同一种滑动条（[SettingEntry.Percent]）。
            // 它只改液态玻璃按键的投影透明度，不涉及任何布局/交互，
            // 所以拖动中直接写进设置即可，不需要"松手才提交"。
            SettingEntry.Percent(
                title = "按钮下阴影强度",
                value = settings.keyShadowStrength,
                onChange = {
                    onChange(settings.copy(keyShadowStrength = it.coerceIn(0f, 1f)))
                }
            )
        ),
        metrics = metrics,
        onBack = onBack,
        rotary = rotary,
        backdropVisible = backdropVisible
    )
}

// ===========================================================================
// 五屏共用的骨架
// ===========================================================================

/**
 * 设置家族五屏共用的骨架：顶部返回键 + 可滚动列表 + 表冠滚动 + 圆屏行宽自适应。
 *
 * 每个子屏只是一份 [SettingEntry] 清单（外加几个去向回调），版面、动画、
 * 手势、版本门槛过滤全在这里做一遍 —— 分屏不复制版面。
 *
 * @param entries 这一屏的列表项（会被版本门槛过滤：本机不支持的 [SettingEntry.Row]
 *                整行不显示、不留空位）
 * @param rotary 表冠 / 滚轮事件流：**只用来滚动列表**（和函数列表、文件列表、
 *               字母键盘同一个接法、同一个系数）。给了默认值（空流），
 *               所以调用方漏传也能编译、也能跑，只是表冠不滚（不会更糟）。
 */
@Composable
private fun SettingsScaffold(
    entries: List<SettingEntry>,
    metrics: WatchMetrics,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /**
     * 背后有没有一张模糊背景图可透（3.44）。
     *
     * 由调用方从 `state.settings.blurBackdrop` 传进来 —— **不能**读
     * [LocalBlurBackdrop]，那个 CompositionLocal 只在 `TabDeck` 内部提供，
     * 而设置页是整屏路由、在 `TabDeck` 外面，读到的永远是默认值。
     */
    backdropVisible: Boolean = false
) {

    // 各行的入场：本屏**第一次组合**时播一次（也就是"每次打开这一屏播一次"，
    // 这正是想要的"打开就有反应"）。
    //
    // 状态放在屏幕这一层，各行共用同一个目标值：行自己不留状态，
    // 所以换取值、行内重排都不会各自重播，只有整屏重新打开才会再播一次。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // ---- 列表状态与"这一屏在屏幕上的几何" ----
    val density = LocalDensity.current
    val isRound = isRoundScreen()
    val listState = rememberLazyListState()

    // ---- 表冠（rotary）----
    //
    // 和 FunctionList / SavesScreen / AlphabetKeyboard **一字不差**的接法：
    // 收集调用方转进来的滚动量，`scrollBy(-amount)`（往下拨 = 往下滚），
    // 系数用同一个 [ROTARY_SCROLL_FACTOR]，几个列表界面的手感才是同一套。
    // 手指直接拖列表也照常能滚 —— 那是 LazyColumn 自带的手势，和表冠互不干扰。
    LaunchedEffect(rotary) {
        rotary.collect { amount ->
            listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
        }
    }

    // ---- 本机实际要显示的项（版本不够的那些在这里被滤掉）----
    //
    // 行数**不写死**：行高一律按比例算，行数多一行少一行只是"内容高一点/矮一点"，
    // 由下面的 LazyColumn 自己消化 —— 能滚，所以不需要按行数平分屏高。
    val visibleEntries = entries.filter {
        it !is SettingEntry.Row || it.gate == null || supportsSetting(it.gate)
    }

    BoxWithConstraints(
        // ---- 1.2.48：这一屏**自己画**那张全局糊图 ----
        //
        // 用户原话："在设置里做出任何设置的时候会产生一层白色背景，遮蔽或者替换
        // 原来的模糊背景，你修复一下。"
        //
        // 以前这里只给自己刷一层 `background.copy(alpha = GLASS_PAGE_MASK_ALPHA)`：
        // 全靠父层（`GraphScreen` 的 `GlobalBackdrop`）在后面顶着。可只要父层那一条
        // `if (blurBackdrop)` 在设置变更的那次重组里走到 `else`（或是那张图画不出来），
        // 顶上来的就是一块实色底 —— 看上去就是"白层把糊图盖住了"。
        //
        // 现在改成自己在**同一层**里画 `GlobalBackdrop`（和函数列表是同一张图、
        // 同一个 10% 压暗，不再多刷一遍底色），父层怎么样都影响不到这一屏。
        Modifier
            .fillMaxSize()
    ) {
        if (backdropVisible) {
            GlobalBackdrop(modifier = Modifier.fillMaxSize(), playReveal = false)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background))
        }

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
                onClick = onBack,
                modifier = Modifier.tutorialAnchor(TutorialAnchors.BACK)
            )
        }

        // ---- 内容：**可滚动**列表（行数随本机支持情况变）----
        //
        // 三件和 FunctionList / SavesScreen 对齐的事：
        //   1. 容器 `fillMaxWidth()`，**行自己**按所在高度的圆弧宽度收窄并居中
        //      （靠 horizontalAlignment）。行宽不能用"第几行"算 —— 列表会滚，
        //      必须按每一行**当前**在屏幕上的高度算，见 [rowWidthFraction]；
        //   2. 行间距交给 `Arrangement.spacedBy(ROW_GAP)`，不自己塞占位行；
        //   3. `key` 用每一项自己的 `key`（三种 entry 各自唯一）：
        //      滚动回收重建时条目才不会认错自己，也不会把"另一行的入场进度 /
        //      chip 弹跳"套到这一行上。
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
            itemsIndexed(visibleEntries, key = { _, entry -> entry.key }) { index, entry ->
                val widthFraction = rowWidthFraction(
                    listState = listState,
                    itemKey = entry.key,
                    listTopPx = listTopPx,
                    screenPx = screenHeightPx,
                    metrics = metrics,
                    isRound = isRound
                )
                when (entry) {
                    is SettingEntry.Row -> SettingsRow(
                        title = entry.title,
                        value = entry.value,
                        widthFraction = widthFraction,
                        // index 只用来做入场交错（整屏第一次组合时各行依次淡入）
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        onClick = entry.onClick,
                        anchorId = tutorialAnchorFor(entry.title)
                    )

                    is SettingEntry.Nav -> EntryRow(
                        title = entry.title,
                        widthFraction = widthFraction,
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        onClick = entry.onClick,
                        anchorId = tutorialAnchorFor(entry.title)
                    )

                    is SettingEntry.Choice -> ChoiceRow(
                        title = entry.title,
                        selected = entry.selected,
                        widthFraction = widthFraction,
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound,
                        onClick = entry.onClick
                    )

                    is SettingEntry.Speed -> CrownSpeedRow(
                        speed = entry.speed,
                        onChange = entry.onChange,
                        widthFraction = widthFraction,
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        geometry = geometry,
                        isRound = isRound
                    )

                    is SettingEntry.Heading -> SectionHeadingRow(
                        title = entry.title,
                        widthFraction = widthFraction,
                        index = index,
                        entered = entered,
                        metrics = metrics
                    )

                    is SettingEntry.Percent -> PercentSliderRow(
                        title = entry.title,
                        value = entry.value,
                        onChange = entry.onChange,
                        onCommit = entry.onCommit,
                        widthFraction = widthFraction,
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        isRound = isRound,
                        geometry = geometry
                    )
                }
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
    onClick: () -> Unit,
    /** 教程要圈住这一行时给的锚点 id（其余行为 null，不加任何修饰符）。 */
    anchorId: String? = null
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
        // 统一到 KeyPalette：设置行就是一颗深灰胶囊（和函数列表行、键盘按键同一套色）。
        // 液态玻璃主题下换成半透明亮面，让底下的玻璃与模糊透上来（只动颜色）。
        color = if (LocalGlassTheme.current) {
            GlassTokens.surface
        } else {
            KeyPalette.normal
        },
        shape = PillShape,
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
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
            .tutorialAnchor(anchorId)
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
 * 「更多菜单」的**入口行**：标题 + 右侧箭头，**整行可点**。
 *
 * 和 [SettingsRow] 的区别：右边**不是取值 chip 而是一颗指向箭头** ——
 * 这一行不"改一个值"，它"去另一屏"。
 *
 * **只有一行标题，没有副标题**（用户原话："更多设置界面的每个设置条都不要显示具体
 * 内容，这个软件是设计给手表用的，显示具体内容放不下"）。
 * 四个大块是这一屏的主角，行高仍比普通行高一截（[ENTRY_H]），
 * 单行标题在 37dp 的行里垂直居中，读起来就是一颗"大块按钮"。
 *
 * 箭头是**装饰性**的（contentDescription = null）：标题已经说清去向，
 * 读屏软件再念一遍"前进"反而是噪音。
 *
 * 宽度走的是和 [SettingsRow] **同一句** `geometry.rowWidth(metrics, widthFraction, isRound)`
 * —— 圆屏弧宽收窄 / 非圆屏铺满这件事只有 [rowWidthFraction] 一处，这里不再判断一次。
 */
@Composable
private fun EntryRow(
    title: String,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    onClick: () -> Unit,
    /** 教程要圈住这一行时给的锚点 id（其余行为 null）。 */
    anchorId: String? = null
) {
    // 按下整行缩到 0.98（和 SettingsRow 同一套反馈）。
    val press = rememberPressFeedback(scaleAmount = ROW_PRESS_SCALE, glowAmount = 0f)

    // 入场：和设置行同一套（淡入 + 从下方极小的上移，交错 delay 逐行 +35ms）。
    // 同样走 graphicsLayer —— 行的位置、尺寸、点击区从第一帧起就定死。
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )

    Surface(
        color = KeyPalette.normal,
        shape = PillShape,
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            .height(metrics.sizeDp * ENTRY_H)
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
                scaleX = press.scale
                scaleY = press.scale
            }
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
            .tutorialAnchor(anchorId)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_INNER_GAP)
        ) {
            // 左：单行标题，占据剩余空间，把箭头顶到右端
            Text(
                text = title,
                fontSize = metrics.fontSmall,
                color = KeyPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 右：指向箭头（装饰性，不抢点击）。
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = KeyPalette.textMuted,
                modifier = Modifier.size(metrics.sizeDp * ENTRY_CHEVRON)
            )
        }
    }
}

/**
 * **二选一的选项行**（「手表适配 → 屏幕形状」用）。
 *
 * 版面语言和 [SettingsRow] / [EntryRow] 完全一致（同宽、同高、同胶囊、同一套按压反馈
 * 与入场动画），只改了两件事：
 *   * 选中时整行底色换成 [KeyPalette.accent] —— "当前选中"一眼可见；
 *   * 右端放一颗对勾（装饰性，读屏不需要再念一遍）。
 */
@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(scaleAmount = ROW_PRESS_SCALE, glowAmount = 0f)
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )

    Surface(
        color = if (selected) KeyPalette.accent else KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            .height(metrics.sizeDp * ROW_H)
            .graphicsLayer {
                alpha = reveal
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
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = KeyPalette.text,
                    modifier = Modifier.size(metrics.sizeDp * 0.12f)
                )
            }
        }
    }
}

/**
 * **分组标题行**（3.40）：一行居中的小字，把下面几项归成一组。
 *
 * 用户原话："在模糊背景的设置条上面加个居中的标题，内容是模糊背景。"
 *
 * 和 [SettingsRow] 的三点不同（都是有意的）：
 *   * **居中**而不是左对齐 —— 它是标签，不是一项设置；
 *   * **没有底板**（不画胶囊面）—— 有底板就会被当成一个能点的行；
 *   * **矮**（[HEADING_H] 屏高）—— 只占一行字的高度，不跟设置项抢空间。
 *
 * 入场动画和普通行同一套（淡入 + 上滑），否则列表第一次出现时它会"提前在场"。
 */
@Composable
private fun SectionHeadingRow(
    title: String,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics
) {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )
    Box(
        modifier = Modifier
            .width(metrics.sizeDp * widthFraction.coerceIn(0f, 1f))
            .height(metrics.sizeDp * HEADING_H)
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = metrics.fontSmall,
            color = KeyPalette.text.copy(alpha = 0.62f),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * **百分比滑条行**（3.40）：`模糊程度` 0%~100%。
 *
 * 用户原话："在模糊背景的设置条下面加个调表冠灵敏度同款的滑动设置条，
 * 内容是模糊程度，调整从0%到100%。"
 *
 * 和 [CrownSpeedRow] **同款**（同一套行高、同一条轨道、同一套手势和按压反馈），
 * 三处刻意不同：
 *   * 刻度**线性**（不是对数）：0~100% 每一格的心理距离一样，对数在这里没有意义；
 *   * 松手**不量化**：百分比精度到 1 就够了，浮点值直接存（`blurStrength`）；
 *   * 取值 chip 显示 `62%`。
 *
 * 手势闭包的两个老坑这里照样躲开了（见 [CrownSpeedRow] 里那两段长注释）：
 * 回调走 `rememberUpdatedState`、松手用拖动过程记下的最后位置。
 */
@Composable
private fun PercentSliderRow(
    title: String,
    value: Float,
    onChange: (Float) -> Unit,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean,
    onCommit: ((Float) -> Unit)? = null
) {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )
    var dragging by remember { mutableStateOf(false) }
    var lastDragT by remember { mutableStateOf(value.coerceIn(0f, 1f)) }
    val onChangeNow = rememberUpdatedState(onChange)
    // 松手提交的回调用 `rememberUpdatedState` 包一层，理由同 [onChange]（见 [CrownSpeedRow]）：
    // `pointerInput(Unit)` 的 lambda 只跑一次，直接捕获会拿到首次组合时的那个实例。
    val onCommitNow = rememberUpdatedState(onCommit)
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) THUMB_PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing)
    )

    Surface(
        color = if (LocalGlassTheme.current) {
            GlassTokens.surface
        } else {
            KeyPalette.normal
        },
        shape = PillShape,
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            .height(metrics.sizeDp * (ROW_H + CROWN_ROW_H_EXTRA))
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalArrangement = Arrangement.Center
        ) {
            // ---- 上排：标题 + 当前百分比 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_INNER_GAP)
            ) {
                Text(
                    text = title,
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ValueChip(text = percentLabel(value), metrics = metrics)
            }

            Spacer(Modifier.height(metrics.sizeDp * CROWN_TITLE_GAP))

            // ---- 下排：滑轨（线性刻度）----
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.sizeDp * THUMB_D)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val t = (offset.x / size.width).coerceIn(0f, 1f)
                            lastDragT = t
                            onChangeNow.value(t)
                            onCommitNow?.value?.invoke(t)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                // 用拖动过程中记下的最后位置，不用捕获进闭包的那个 value
                                val t = lastDragT.coerceIn(0f, 1f)
                                onChangeNow.value(t)
                                onCommitNow?.value?.invoke(t)
                            },
                            onDragCancel = { dragging = false },
                            onDrag = { change, _ ->
                                val t = (change.position.x / size.width).coerceIn(0f, 1f)
                                lastDragT = t
                                onChangeNow.value(t)
                            }
                        )
                    }
            ) {
                val density = LocalDensity.current
                val trackH = metrics.sizeDp * TRACK_H
                val thumbD = metrics.sizeDp * THUMB_D
                val position = value.coerceIn(0f, 1f)
                val trackWidthPx = with(density) { maxWidth.toPx() }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(trackH)
                        .clip(PillShape)
                        .background(MaterialTheme.colors.outlineVariant)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(position)
                        .height(trackH)
                        .clip(PillShape)
                        .background(MaterialTheme.colors.primary)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(
                            x = with(density) { (trackWidthPx * position).toDp() } - thumbD / 2
                        )
                        .size(thumbD)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
                        .clip(PillShape)
                        .background(MaterialTheme.colors.primary)
                )
            }
        }
    }
}

/** 百分比文案：`62%`（四舍五入到整数）。 */
internal fun percentLabel(value: Float): String =
    "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"

/**
 * 「表冠灵敏度」滑条行（「表冠适配」子页第二项）。
 *
 * 版面：上排标题 + 当前值 chip，下排一条可拖 / 可点的滑轨。
 * 比 [SettingsRow] 高一截（[CROWN_ROW_H_EXTRA]）—— 滑轨要占地方。
 *
 * **对数刻度**：0.1 在最左、1.0 在正中间、10.0 在最右（理由见 [crownSpeedForPosition]）。
 * 拖动过程中实时回调（不量化，跟手）；松手时量化到 0.1。
 *
 * 宽度和 [SettingsRow] 同一句 `geometry.rowWidth(...)`；滑轨是**行内**元素，
 * `fillMaxWidth()` 跟着行宽走 —— 所以圆屏上越靠中间的行，滑轨也越长。
 */
@Composable
private fun CrownSpeedRow(
    speed: Float,
    onChange: (Float) -> Unit,
    widthFraction: Float,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean
) {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )
    // 拖动中不弹整行缩放，改把滑块放大（[THUMB_PRESS_SCALE]）——
    // 反馈直接长在"被按住的那个东西"上，比整行缩放更准。
    var dragging by remember { mutableStateOf(false) }

    /**
     * 拖动过程中**最后一帧**落在轨道上的比例（0..1）。
     *
     * 松手量化用**它**，不用 `speed` —— 原因见下面 `detectDragGestures` 里那段注释
     * （`pointerInput(Unit)` 会捕获首次组合时的 `speed`，松手时读到的是 1.0）。
     */
    var lastDragT by remember { mutableStateOf(0f) }

    /**
     * 永远指向"最新那个" [onChange]。
     *
     * `pointerInput(Unit)` 的 lambda 只跑一次，直接捕获 `onChange` 会拿到首次组合时的
     * 实例；虽然本项目里最终都落到同一个 `GraphState`，但换行、换设置对象之后
     * 那个实例可能已经过期。`rememberUpdatedState` 让读取永远发生在**调用那一刻**。
     */
    val onChangeNow = rememberUpdatedState(onChange)
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) THUMB_PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing)
    )

    Surface(
        color = if (LocalGlassTheme.current) {
            GlassTokens.surface
        } else {
            KeyPalette.normal
        },
        shape = PillShape,
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            // 比普通行高一截，多出来的那截正好是滑轨 + 上下呼吸位
            .height(metrics.sizeDp * (ROW_H + CROWN_ROW_H_EXTRA))
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * ROW_PAD),
            verticalArrangement = Arrangement.Center
        ) {
            // ---- 上排：标题 + 当前值 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_INNER_GAP)
            ) {
                Text(
                    text = "表冠灵敏度",
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ValueChip(text = speedLabel(speed), metrics = metrics)
            }

            Spacer(Modifier.height(metrics.sizeDp * CROWN_TITLE_GAP))

            // ---- 下排：滑轨 ----
            // 宽度 = 行宽 − 左右内边距；指针位置 → 速度的换算见
            // [crownSpeedForPosition]（对数刻度）。
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.sizeDp * THUMB_D)
                    .pointerInput(Unit) {
                        // 点一下：滑块跳到点的位置（并量化）
                        detectTapGestures { offset ->
                            val t = (offset.x / size.width).coerceIn(0f, 1f)
                            onChangeNow.value(crownSpeedForPosition(t).quantized())
                        }
                    }
                    .pointerInput(Unit) {
                        // 拖动：实时回调（不量化，跟手）；松手时量化到 0.1
                        //
                        // ⚠️ **这里踩过一个坑（用户报的"拖完弹回 1.0"）**：
                        // `pointerInput(Unit)` 的 lambda 只在**首次组合**时跑一次，
                        // 它捕获的 `speed` 永远是那一刻的值（默认 1.0）。
                        // 原来 `onDragEnd` 写的是 `onChange(speed.quantized())`，
                        // 于是每次松手都把值写回 1.0 —— 拖到哪里都白拖。
                        //
                        // 现在两件事都改掉了：
                        //   * 回调走 [rememberUpdatedState]，永远是最新的那个 lambda；
                        //   * 松手时**不用** `speed`，而是用拖动过程中记下的最后位置
                        //     [lastDragT] 重新换算 —— 它和屏幕上的滑块位置是同一个来源。
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                onChangeNow.value(
                                    crownSpeedForPosition(lastDragT).quantized()
                                )
                            },
                            onDragCancel = { dragging = false },
                            onDrag = { change, _ ->
                                val t = (change.position.x / size.width).coerceIn(0f, 1f)
                                lastDragT = t
                                onChangeNow.value(crownSpeedForPosition(t))
                            }
                        )
                    }
            ) {
                val density = LocalDensity.current
                val trackH = metrics.sizeDp * TRACK_H
                val thumbD = metrics.sizeDp * THUMB_D
                val position = crownPositionForSpeed(speed)
                val trackWidthPx = with(density) { maxWidth.toPx() }

                // 轨道底（整条）：outline 色，弱到只是"这里有一条"
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(trackH)
                        .clip(PillShape)
                        .background(MaterialTheme.colors.outlineVariant)
                )

                // 已选段（左 → 滑块）：primary，一眼看出当前档位在哪
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(position.coerceIn(0f, 1f))
                        .height(trackH)
                        .clip(PillShape)
                        .background(MaterialTheme.colors.primary)
                )

                // 滑块：坐在"已选段末端"上，拖动时放大 1.25 倍
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = with(density) { (trackWidthPx * position).toDp() } - thumbD / 2)
                        .size(thumbD)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
                        .clip(PillShape)
                        .background(MaterialTheme.colors.primary)
                )
            }
        }
    }
}

/** 灵敏度的显示文案：`1.0×`（保留一位小数，取值范围 0.1 ~ 10.0）。 */
private fun speedLabel(speed: Float): String {
    val rounded = (speed * 10f).toInt() / 10f
    return "${rounded}×"
}

/** 量化到 0.1（滑条松手后 / 点击时用，保证显示的总是 0.1 的整数倍）。 */
private fun Float.quantized(): Float =
    (this.coerceIn(0.1f, 10f) * 10f).toInt().coerceIn(1, 100) / 10f

/**
 * 滑条位置（0..1）→ 速度（0.1 .. 10.0），**对数刻度**。
 *
 * 用户给的范围是 0.1 ~ 10.0（跨越两个数量级）。线性映射的话：
 * 0.1~2.0 这段"慢速精调区"只占轨道 19%，而恰恰是它最常用（1.0 是默认值，
 * 调灵敏度的动作八成发生在 0.5~2.0 之间）—— 一整段挤在左边一点点，
 * 手指根本拨不准。对数刻度把它拉平：0.1 在最左、1.0 在**正中间**、10.0 在最右，
 * 每一"倍速"占的轨道宽度都一样。
 *
 * 内部表示：`t ∈ [0,1]`（轨道比例），`speed = 10^(-1 + 2t)`。
 */
internal fun crownSpeedForPosition(t: Float): Float =
    10f.pow(-1f + 2f * t.coerceIn(0f, 1f))

/** 速度 → 滑条位置（[crownSpeedForPosition] 的逆运算）。 */
internal fun crownPositionForSpeed(speed: Float): Float =
    (log10(speed.coerceIn(0.1f, 10f)) + 1f) / 2f

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
        modifier = Modifier
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .graphicsLayer {
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
