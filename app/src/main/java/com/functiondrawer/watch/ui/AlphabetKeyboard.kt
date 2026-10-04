// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
// ExperimentalComposeUiApi 是 LocalConfiguration.current.isScreenRound（判断圆屏）用的。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.keyboard.ALPHABET_COLUMNS
import com.functiondrawer.watch.ui.keyboard.AlphabetKey
import com.functiondrawer.watch.ui.keyboard.KeyTone
import com.functiondrawer.watch.ui.keyboard.LetterCase
import com.functiondrawer.watch.ui.keyboard.alphabetKeySpec
import com.functiondrawer.watch.ui.keyboard.alphabetRows
import com.functiondrawer.watch.ui.keyboard.insertTextOf
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlin.math.round

// ---------------------------------------------------------------------------
// 纵向分区：**和数学键盘占同一块地方**（FunctionEditor 里的 TAB_Y ~ GRID_BOTTOM）。
//
// 字母表键盘是在编辑器里"顶掉"选择栏 + 数学按键网格的那一块，输入框（含它右边的 ⌫）
// 一点不动 —— 需求原话："显示在输入框下面"。输入框的下沿是 0.307，
// 所以列表从 0.322（= 原选择栏的顶边）开始，底边沿用网格的 0.945。
//
// 这两个数**照抄 FunctionEditor 的 TAB_Y / GRID_BOTTOM**，没有自己另定一套分区：
// 两边占的是同一块地方，将来那边调分区，这里跟着改同一个数即可。
//
// ⚠️ **下沿 0.945 只在圆屏上还是这个数**（那里逐像素不变）：它是圆屏拱口的结论
// （再往下那几排会被表壳切掉）。非圆屏（手机）没有拱口，列表就一路铺到屏幕底边、
// 只留 `LIST_BOTTOM_PAD` —— 否则手机上键盘还是只占屏幕上半截（用户："函数编辑
// 页面也没有全屏显示"）。上沿两边都是**真实屏高**的 0.322（圆屏上逐像素不变）。
// ---------------------------------------------------------------------------
private const val LIST_TOP = 0.322f
private const val LIST_BOTTOM = 0.945f

/**
 * 一行按键的高度。
 *
 * **有意偏离需求里"照示例 chip 的行高"那一句**，取的是 `FunctionList` 里
 * `ROW_H = 0.15f`（示例区里**函数行**的高度），而不是预设 chip 的 0.085：
 *
 *   * 0.085 + 0.018 的行距下，6 排一共 139.8dp，而可视区是 145.1dp ——
 *     **全部塞得下、根本滚不动**，而需求明确要求"做成可以滚动的列表
 *     （注意适配表冠）"，表冠一转没反应就等于这个要求没做。
 *   * 0.15（34.95dp）和数学键盘的键高（34.4dp）同一档，圆屏上更好点中；
 *     6 排共 230.7dp，可滚动 ≈86dp（约 2.5 排），表冠有明确反馈。
 *   * 拉高的这一档不是自己编的数：`ROW_H = 0.15f` 就是 FunctionList 示例区
 *     里每一行的行高。
 *
 * 如果确实要回到"示例 chip 的 0.085"（6 排一屏全见、滚不动），
 * 把这个常量改成 0.085f 即可，其它什么都不用动。
 */
private const val ROW_H = 0.15f

/**
 * 行距 = 行内键距 = 0.018 屏高（≈4.2dp）。
 *
 * 和示例区完全一样：FunctionList 里预设行的高度是 0.085、行内
 * `Arrangement.spacedBy(sizeDp * 0.018f)`、LazyColumn 的纵向间距也是 0.018。
 */
private const val ROW_GAP = 0.018f

// ---- 行的宽度（圆弧缩放）：接下来三个常数和算法都照抄 FunctionList 的示例行 ----
/** 用满圆内宽度时留一点余量，免得行角正好压在表壳边缘上。 */
private const val ARC_MARGIN = 0.95f
/** 方屏（或拿不到屏幕形状时）用的行宽 —— 方屏没有"越靠边越窄"的问题。 */
private const val SQUARE_ROW_WIDTH = 0.90f
/** 圆屏上允许的最窄行宽：再窄下去 6 颗键就挤成一团了。 */
private const val MIN_ROW_WIDTH = 0.52f

/**
 * 表冠一格对应滚动多少像素（和 FunctionList 里那个常数同一个值和同一个理由）：
 * OPPO 表一次事件报的是"滚轮像素"，量级几十到一百多，1.0 倍大约一格半屏，手感刚好。
 */
private const val ROTARY_SCROLL_FACTOR = 1.0f

/**
 * 大小写切换键在它那一排里占几份宽（**3 份 = 半排**）。
 *
 * 为什么这颗键要宽：它的标签是两个汉字（`小写` / `大写` / `锁定`），
 * 而圆屏最下面那一排的每份宽只有 ≈21dp（一行 6 份），1 份放不下两个字
 * （fontSmall 12.12sp 下约 24dp）——会被裁成 `小…`。
 * 给它 3 份（≈63~72dp）之后两个汉字宽宽裕裕，而且"宽的切换键"本来就是
 * 键盘上的通用样子（Shift / 大写键都是长条）。
 * 这一排正好是 `3 + 1 + 1 + 1 = 6` 份，所以不需要任何占位补齐。
 * （属性界面里不显示这颗键时，那一排就只剩 `( ) ,` 三份，右边的 3 份
 * 由下面那段"等宽占位"自动补上 —— 短排补位本来就有，不用另写逻辑。）
 */
private const val CASE_KEY_UNITS = 3

/**
 * 内置英文（字母表）键盘：**可滚动的 6 列按键列表**。
 *
 * 布局（每排 6 格，短排左对齐、右边用等宽占位补满，所以每颗键一样宽）：
 * ```
 * 返回  a  b  c  d  e
 *  f    g  h  i  j  k
 *  l    m  n  o  p  q
 *  r    s  t  u  v  w
 *  x    y  z
 * 小写  (  )  ,
 * ```
 * 网格形状（排法、顺序、每排几格）全在 ui/keyboard/AlphabetModel.kt 的
 * [alphabetRows] 里，这里只负责画：**这个文件没有任何布局规则**，
 * 所以"每排 6 个 / 返回键在第一个 / 字母按字母表顺序 / 切换键在最左下"
 * 这些都能被离线单元测试盯住（见 AlphabetModelTest）。
 *
 * 最后一排的键位由调用方用 [showCaseToggle] / [showBrackets] 挑
 * （属性编辑界面里大小写是强制的、括号只有函数名才要，见下面两个参数的说明）；
 * 无论怎么挑，字母排和返回键都不变。
 *
 * 几点设计说明：
 *   * **圆屏上按键大小随圆屏弧宽变化**，用的是和 FunctionList 示例区**完全同一套**
 *     算法与常数（[rowWidthFraction]：按行的**中心高度**取
 *     `WatchMetrics.circleWidthFraction`，量化到百分位后在
 *     `MIN_ROW_WIDTH`~`SQUARE_ROW_WIDTH` 之间夹紧）。
 *     那边这个函数是 private，本轮又不许改 FunctionList.kt，所以这里照抄了一份，
 *     连常数（0.95 / 0.90 / 0.52）都一样 —— 圆屏上"中间宽、两头窄"的观感统一。
 *     **非圆屏（手机）不再这么算**：没有拱口就没有"越靠边越窄"，
 *     6 列按键直接铺满整宽（见 [rowWidthFraction] 的说明），
 *     上沿/高度也改按**真实屏高** —— 手机上这一块原来是 y = 132~388dp
 *     （真机 850dp 高的屏上只占上半截、7 排里只看得见 3 排半），
 *     现在是 y = 274~829dp（7 排全见）；行宽 370 → 386dp。
 *   * **配色和形状用已经统一好的那套**：`KeyPalette.normal` / `KeyPalette.accent`
 *     和完整胶囊 `PillShape`，没有 MD3 的 surfaceContainerHigh / primaryContainer，
 *     也没有 `MaterialTheme.shapes.small`。
 *   * **表冠（rotary）**：和 FunctionList 一样收一个 `Flow<Float>`，
 *     在 LaunchedEffect 里 `listState.scrollBy(...)`；手指直接拖列表也照常能滚
 *     （LazyColumn 自带手势）。
 *
 * 大小写模式**由调用方持有**（[letterCase] + [onCaseChange]，和 MathKeyboard 的
 * `page` / `onSelectPage` 是同一个写法）：这样关掉再打开字母表键盘时模式还在
 * （`remember` 在调用方那一层，不会随键盘一起被丢掉），
 * 调用方也能顺手把"现在是大写"显示到别的地方。
 *
 * @param letterCase 当前大小写模式（见 [LetterCase] 的三种模式与循环顺序）
 * @param onCaseChange 点了大小写切换键：把 `letterCase.next` 交回去，调用方存起来
 * @param onInsert 要插入的文本，**已经按当前模式定好大小写**（字母 1 个字符、
 *   符号 `(` `)` `,`）。调用方拿它走既有的编辑管线即可，例如
 *   `applyKey(KeyAction.Insert(text))` —— 这样逗号跳槽（填 `log(,)` 那种模板）
 *   等语义和数学键盘完全一致。
 *   想连退格一起交给模型算的调用方，也可以用 AlphabetModel 的 `applyTo(...)`。
 * @param onClose 第一个键（返回）：回到常规数学键盘。它**不动输入框里的文本**。
 *   在函数编辑器里它的语义是"换回数学键盘"；在属性编辑界面里它是"退出名称编辑"
 *   （也就是需求里说的"失去焦点"），由调用方决定 —— 键盘自己只负责把这件事报上去。
 * @param rotary 表冠 / 滚轮事件：在这块界面上**只滚动这个列表**
 * @param showCaseToggle 最后一排要不要那个大小写切换键。
 *   **默认 true**（函数编辑器那一侧的现有调用一行都不用改）。
 *   属性界面里传 false：函数/点/方程的名字强制大写、滑动条的名字强制小写，
 *   大小写**不是**用户能选的，所以连键都不显示 ——
 *   留一个按下去没反应的键，用户只会以为键盘坏了。
 * @param showBrackets 最后一排要不要 `(` `)` `,` 三个符号键。
 *   **默认 true**。用户明确要求"只有函数的编辑显示括号"：
 *   函数名本来就是 `a(x)` 这种带括号的形状；点/方程/滑动条的名字是纯字母
 *   （`A` / `a`），给它们括号只会让用户打出非法名字。
 *   它和 [showCaseToggle] 都为 false 时，最后一排**整排不画**（见 [alphabetRows]）。
 * @param modifier 调用方给 `Modifier.fillMaxSize()`（和 MathKeyboard 同一个约定：
 *   自己量真实尺寸、内部用绝对偏移定位，自己画在 0.322~0.945 那一段）。
 *   本轮起这里用的是 `BoxWithConstraints`：**真实屏高/屏宽要自己量**，
 *   否则手机上这一块又只占屏幕上半截（调用方那一侧不需要多传任何参数）。
 */
@Composable
fun AlphabetKeyboard(
    metrics: WatchMetrics,
    letterCase: LetterCase,
    onCaseChange: (LetterCase) -> Unit,
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
    rotary: Flow<Float>,
    modifier: Modifier = Modifier,
    showCaseToggle: Boolean = true,
    showBrackets: Boolean = true
) {
    val density = LocalDensity.current
    // 行高 / 行距是**控件尺寸**：继续按短边缩放（和数学键盘的按键同一档）。
    val rowHeight = metrics.sizeDp * ROW_H
    val rowGap = metrics.sizeDp * ROW_GAP
    val isRound = LocalConfiguration.current.isScreenRound
    val listState = rememberLazyListState()

    // 网格形状是常量（不随模式 / 文本变），取一次就够了。
    // 但"最后一排有哪些键"是按调用方的两个开关挑的，所以 remember 要带上它们。
    val rows = remember(showCaseToggle, showBrackets) {
        alphabetRows(showCaseToggle = showCaseToggle, showBrackets = showBrackets)
    }

    // 表冠 / 滚轮：只滚动这个列表。
    // 和 FunctionList 里那段同一个写法（包括系数 1.0）：那边的列表和这里
    // 是同一块屏上的两个界面，手感必须一致。
    LaunchedEffect(rotary) {
        rotary.collect { amount ->
            listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
        }
    }

    // 自己量一次真实可用区域（调用方只给 `Modifier.fillMaxSize()`）：
    // 上沿 / 高度按**真实屏高**、行宽非圆屏铺满即可 —— 和数学键盘、设置页同一套换算，
    // 见 `MathKeyboard.bandWidth` 上面那段真因。圆屏上 `screenH == sizeDp`、行宽仍走
    // 原来的弧宽公式，所以圆表**逐像素不变**。
    BoxWithConstraints(modifier = modifier) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val listTopPx = with(density) { geometry.sy(LIST_TOP).toPx() }
        val screenHeightPx = with(density) { geometry.screenH.toPx() }
        // 圆屏：还是 0.322~0.945（逐像素不变 —— 0.945 是拱口的结论，不是"屏高的百分之几"）；
        // 非圆屏：一路铺到屏幕底边、只留 [LIST_BOTTOM_PAD]（和函数列表、设置页同一个口径）。
        val listHeight = if (isRound) {
            geometry.sy(LIST_BOTTOM) - geometry.sy(LIST_TOP)
        } else {
            geometry.listHeight(metrics, geometry.sy(LIST_TOP))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(LIST_TOP))
                .fillMaxWidth()
                // 左右余量只在非圆屏上生效：圆屏的行宽本来就是按弧宽收过的，
                // 再加边距等于把圆表上的键盘又缩窄一圈 —— 圆屏这一支不能动。
                .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))
                .height(listHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(rowGap)
        ) {
            itemsIndexed(rows, key = { index, _ -> rowKey(index) }) { index, row ->
                val wf = rowWidthFraction(
                    listState = listState,
                    itemKey = rowKey(index),
                    listTopPx = listTopPx,
                    screenPx = screenHeightPx,
                    metrics = metrics,
                    isRound = isRound
                )
                Row(
                    modifier = Modifier
                        // 圆屏：`sizeDp × 弧宽比例`（原样）；非圆屏：铺满屏宽减左右余量，
                        // 6 列按键于是自然铺满整宽（手机上每颗键 ≈58dp，原来只有 ≈31dp）。
                        .width(geometry.rowWidth(metrics, wf, isRound))
                        .height(rowHeight),
                    horizontalArrangement = Arrangement.spacedBy(rowGap)
                ) {
                    row.forEach { key ->
                        val spec = alphabetKeySpec(key, letterCase)
                        AlphabetKeyButton(
                            label = spec.label,
                            tone = spec.tone,
                            metrics = metrics,
                            onClick = {
                                when (key) {
                                    // 返回：换回数学键盘（不动文本）
                                    AlphabetKey.Close -> onClose()
                                    // 切换大小写：三种模式循环（小写→大写→锁定→小写）
                                    AlphabetKey.ToggleCase -> onCaseChange(letterCase.next)
                                    // 退格：当前排上没有这个键（见模型的说明），
                                    // 留着这个分支是为了 when 穷尽 + 将来加键位
                                    AlphabetKey.Backspace -> Unit
                                    // 字母 / 符号：插入文本。大小写在这里才被套上去 ——
                                    // 模型里那个键只描述"小写字母本身"。
                                    is AlphabetKey.Letter, is AlphabetKey.Symbol ->
                                        insertTextOf(key, letterCase)?.let(onInsert)
                                }
                            },
                            modifier = Modifier
                                .weight(unitsOf(key).toFloat())
                                .fillMaxHeight()
                        )
                    }
                    // 短排（`x y z` 只有 3 格）用**等宽占位**补到 6 份，
                    // 这样短排里的键和别的排一样宽 —— 不补的话 weight 会把
                    // 3 颗键撑成 1/3 行宽，看起来像另一个键盘。
                    repeat(ALPHABET_COLUMNS - row.sumOf { unitsOf(it) }) {
                        Spacer(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** LazyColumn 每一项的稳定 key（也用来查它在屏幕上的实际位置，见 [rowWidthFraction]）。 */
private fun rowKey(index: Int): String = "alphabet-row-$index"

/**
 * 一个键占几份宽。
 *
 * 只有大小写切换键是宽的（[CASE_KEY_UNITS]，两个汉字的标签要地方），
 * 其余（字母 / 符号 / 返回）都是 1 份。
 */
private fun unitsOf(key: AlphabetKey): Int =
    if (key is AlphabetKey.ToggleCase) CASE_KEY_UNITS else 1

/**
 * 算出某一排**当前**应该有多宽（占屏宽的比例）。
 *
 * 这是 FunctionList 里同名函数的**同一个算法**（那边是 private，本文件不许改它，
 * 所以照抄一份，连常数都一样）：圆屏上"越靠中间越宽"，所以行宽必须跟着它在
 * 屏幕上的**实际竖直位置**走，而不是按行号算 —— 后者一滚动就不对了。
 *
 * 顺带说明为什么"照抄"是对的：`FunctionList` 那份**就是用户指定的"唯一参照实现"**
 * （"我说的宽度适配是像函数列表的示例的那些按钮一样"），示例按钮那一行用的正是
 * 这个函数（取该项中心高度 × `ARC_MARGIN = 0.95f`，量化到百分位，夹 `[0.52, 0.90]`）
 * 再交给 `geometry.rowWidth(...)`。本文件、设置页、示例按钮**是同一套算法**。
 *
 * 性能上有个关键点（也是照抄的）：用 `derivedStateOf` 包一层，并把结果量化到
 * 百分位。`LazyListState.layoutInfo` 在滚动时每帧都在变，直接读它会让可见的每一排
 * 每帧都重组；包一层之后只有"算出来的宽度真的变了"才会重组（滚动一整屏也就变几十次）。
 *
 * ⚠️ 非圆屏（手机 / 方表）**不再返回 [SQUARE_ROW_WIDTH]**（那是"短边 × 0.90"：
 * 手机上是 370dp、行本身不算窄，问题在于它和列表高度一起把键盘压在上半截）：
 * 返回 1f 当占位，真实宽度由调用方交给 `ScreenGeometry.rowWidth` 铺满屏宽减左右余量 ——
 * 和数学键盘的 4 列按键是同一个口径，手机上 6 列字母键才铺得满整宽。
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
    // 非圆屏：宽度交给 `ScreenGeometry.rowWidth`（铺满屏宽减左右余量），这里只要个占位。
    if (!isRound) return 1f
    val fraction by remember(itemKey) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf SQUARE_ROW_WIDTH
            val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx
            val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
            (round(raw * 100f) / 100f)
                .coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
        }
    }
    return fraction
}

/**
 * 字母表上的一颗键。
 *
 * 样式和数学键盘的 `KeyButton` 完全一致（**扁平底色 + 纯白字 + 完整胶囊**），
 * 但**没有**直接复用那个 composable：它在 MathKeyboard.kt 里是 private，
 * 而那一侧这一轮只许加"入口键 + 一个回调"，把它的可见性改开等于为了
 * 一个胶囊盒子去扩大那个文件的公开面。这里重写一个 20 行的私有件更省事，
 * 而且两边都只依赖同一批统一好的东西（[KeyPalette] / [PillShape] / fontSmall），
 * 观感不会漂。
 *
 * 字号仍是 [WatchMetrics.fontSmall]（12.12sp）：和数学键盘的按键同一档。
 * 每份宽在圆屏上约 21~31dp，1~2 个字符（≈7~24dp）放得下；
 * [TextOverflow.Ellipsis] 只是兜底，宁可收一个"…"也不把字拦腰切断。
 */
@Composable
private fun AlphabetKeyButton(
    label: String,
    tone: KeyTone,
    metrics: WatchMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 三档配色和数学键盘那边一一对应（见 WatchColors.KeyPalette 的表）
    val (container, content) = when (tone) {
        KeyTone.Normal -> KeyPalette.normal to KeyPalette.text
        KeyTone.Accent -> KeyPalette.accent to KeyPalette.text
        KeyTone.Danger -> KeyPalette.danger to KeyPalette.text
    }
    Surface(
        color = container,
        contentColor = content,
        shape = PillShape,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = metrics.fontSmall,
                // 和汉字标签同粗细（数学键盘那边也是 Normal，全局统一）
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
