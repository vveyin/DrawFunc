package com.functiondrawer.watch.ui.keyboard

/**
 * 内置英文（字母表）键盘的数据模型。
 *
 * 和 [KeyboardModel] 一样，这个文件**刻意不依赖 Compose / Android**：
 * 它只有纯 Kotlin 的数据类型和纯函数（整个文件连一行 import 都没有，
 * 用到的 [KeyTone] / [KeyAction] / [editText] 都在同一个包里）。
 * 所以它能用本机 kotlinc + JUnit 离线编译运行 —— 见
 * tools/run-keyboard-tests.ps1 的编译文件列表（这一轮把本文件加进去了）。
 *
 * 为什么放在 ui.keyboard 包里、而不是另开一层：
 *   字母表键盘**不是另一个功能，而是同一个输入框上的另一种键盘**。
 *   它插进去的文本要和数学键盘走**同一条编辑管线**（见 [applyTo] 直接复用
 *   [editText]），配色分档也要和数学键盘用同一套 [KeyTone]。
 *   放在同一个包里，这两件事都不用额外抽象。
 *
 * 分工：
 *   * 这个文件：网格长什么样（[alphabetRows]）、每个键此刻显示什么（[labelOf]）、
 *     按下去要插入什么（[insertTextOf] / [applyTo]）、大小写怎么循环（[LetterCase]）。
 *   * ui/AlphabetKeyboard.kt：只负责把上面这些画出来 + 接表冠滚动。
 */

// ---------------------------------------------------------------------------
// 网格形状
// ---------------------------------------------------------------------------

/**
 * 字母表每排的格数。
 *
 * 需求原话"每排 6 个字母"。它同时也是 [alphabetRows] 里唯一的换行依据，
 * 所以 6 这个数只在这里出现一次；UI 层用它把短排（`x y z` 那排只有 3 格）
 * **用等宽占位补齐**，这样每颗键的宽度和别的排完全一样。
 */
const val ALPHABET_COLUMNS = 6

/** 第 1 排第 1 格那个键（回常规数学键盘）的标签。 */
const val ALPHABET_CLOSE_LABEL = "返回"

/**
 * 退格键的标签。
 *
 * **当前网格上没有退格键**（需求没要求，输入框右边那个 `⌫` 一直都在、随时能按），
 * 但 [AlphabetKey.Backspace] 这个语义保留着（[applyTo] 支持它，将来想加键位、
 * 或者想把它挂到某个键的长按上都行），[labelOf] 也就要能给出一个说得过去的标签。
 */
const val ALPHABET_BACKSPACE_LABEL = "⌫"

// ---------------------------------------------------------------------------
// 大小写模式
// ---------------------------------------------------------------------------

/**
 * 大小写模式：**小写 / 大写 / 大写锁定**三种，循环顺序
 * `小写 → 大写 → 大写锁定 → 小写`（见 [next]）。
 *
 * 三种模式都是**持续状态**：打一个字母**不会**自动回落。
 * 这是有意的选择，理由：
 *   1. 手表上没有 Shift 键、也没有第二个功能键，"大写"没法表达成
 *      "按住 Shift 再点字母"；能用的只有"点切换键改模式"。
 *   2. 手机上那种"打一个大写字母就自动回小写"的习惯用法，在表上代价特别高：
 *      表盘只有 1.5 寸、按键又小又密，每打一个大写字母就要回去再点一次切换键，
 *      而连续大写（`Sin`、`RAD`、缩写）恰恰是最常见的场景。
 *   3. 自动回落还会让键盘状态变得**不可预测**："我明明点了大写，怎么打出来是小写"
 *      —— 手表上的输入反馈本来就弱（没有键盘高亮、光标的可见度也一般），
 *      状态必须一眼可见、点一下变一次。
 *
 * 于是 [Upper] 和 [Lock] **插入的字母完全一样**（都是大写），
 * 区别只有一处：**再点一次切换键去哪一个模式**（[next]）——
 *   * [Upper]（大写）是"我接下来要打几个大写字母"，再点一次进入锁定；
 *   * [Lock]（锁定）是"我要一直大写"，再点一次**直接回到小写**（跳过 [Upper]）。
 * 也就是说"大写"是循环里的中间站，"锁定"是终点站 —— 退出锁定永远只要点一次。
 * 两者在界面上的区别是**标签不同**（`大写` / `锁定`）+ 字母键本身显示的大小写，
 * 这已经满足需求里"每种模式下的视觉要能区分（标签不同即可）"。
 */
enum class LetterCase(val label: String) {
    /** 小写：所有字母输出小写。默认模式（表上绝大多数输入是小写字母 + 数字）。 */
    Lower("小写"),

    /** 大写（临时 / 持续）：字母输出大写，再点一次切换键进入 [Lock]。 */
    Upper("大写"),

    /** 大写锁定：字母输出大写，再点一次切换键**直接回到 [Lower]**。 */
    Lock("锁定");

    /**
     * 这个模式下字母要不要输出大写。
     *
     * [Upper] 和 [Lock] 都是 true —— 两者的差别不在"字母长什么样"，
     * 而在 [next]（再点一次切换键去哪）。
     */
    val isUppercase: Boolean get() = this != Lower

    /** 点一下大小写切换键之后进入的模式：`小写 → 大写 → 锁定 → 小写`。 */
    val next: LetterCase get() = entries[(ordinal + 1) % entries.size]
}

// ---------------------------------------------------------------------------
// 键
// ---------------------------------------------------------------------------

/**
 * 字母表上的一个键。
 *
 * **字母键只描述字母本身，而且只记小写**：显示成什么、插入什么由 [LetterCase]
 * 在**取值时**决定（见 [labelOf] / [insertTextOf]）。
 * 这样"当前是什么模式"在整个程序里只有一份状态（UI 层的 `letterCase`），
 * 模型里不会出现第二份会漂移的副本 —— 也就不会有"模式已经切了、但网格里
 * 还留着上一次算好的大写标签"这种错误。
 *
 * 反过来，如果网格直接产出 `List<List<KeySpec>>`（像数学键盘那样），
 * 模型的输出就会和某个时刻的模式绑死，切模式必须整体重建网格。
 */
sealed interface AlphabetKey {
    /**
     * 字母键。[letter] 必须是 `'a'..'z'` 里的小写字母 ——
     * 大写由 [LetterCase] 给，构造时传大写就破坏了"取值时决定大小写"这个约定
     * （两个地方都能决定大小写，迟早会不一致），所以这里直接拒绝。
     */
    data class Letter(val letter: Char) : AlphabetKey {
        init {
            require(letter in 'a'..'z') {
                "字母键只描述小写字母本身（大小写由 LetterCase 决定），收到 '$letter'"
            }
        }
    }

    /** 符号键。当前只有 `(` `)` `,` 三个（都在最后一排、切换键的右边）。 */
    data class Symbol(val symbol: Char) : AlphabetKey

    /** 返回常规数学键盘（第 1 排第 1 格）。它**不插文本**，由调用方切界面。 */
    data object Close : AlphabetKey

    /** 切换大小写模式（最后一排最左）。它**不插文本**，只改 [LetterCase]。 */
    data object ToggleCase : AlphabetKey

    /** 退格。**当前网格上没有它的格子**，但语义保留（见 [ALPHABET_BACKSPACE_LABEL]）。 */
    data object Backspace : AlphabetKey
}

/**
 * 网格的形状，**与大小写模式无关**（需求：每排 6 个字母、返回键在第一个、
 * 字母按字母表顺序、最后另起一排放切换键和 `( ) ,`）：
 * ```
 * 返回  a  b  c  d  e
 *  f    g  h  i  j  k
 *  l    m  n  o  p  q
 *  r    s  t  u  v  w
 *  x    y  z
 * 小写  (  )  ,
 * ```
 * 几点说明：
 *   * 第 1 排第 1 格是**返回键**，所以第 1 排只放得下 5 个字母（`a`~`e`），
 *     之后每排整齐 6 个，最后剩 `x y z` 三个，**短排**就留在那里
 *     （返回类型 `List<List<AlphabetKey>>` 本来就允许各排不等长，
 *      数学键盘那边空格键那一排也是短排）——不用为了凑满 6 格去挪字母顺序，
 *     "按字母表依次排布"优先。
 *   * 最后**另起一排**（需求原话）：切换键在最左，右边依次是 `(` `)` `,`。
 *   * 结构是常量：网格不随模式/文本变，每次重组都重建一遍纯属浪费，
 *     所以缓存成一个只读常量重复返回（里面的数据类都是不可变的，不会被改坏）。
 *
 * ## 两个开关（用户需求 #4：属性编辑界面的名称栏）
 *
 * 属性界面里的名字编辑**不是**普通表达式输入，它有两个特殊约束，
 * 所以这里让调用方把"最后那一排要哪些键"选出来：
 *
 *   * [showCaseToggle] = false —— 属性界面**不显示大小写切换键**：
 *     函数/点/方程的名字强制大写、滑动条的名字强制小写，大小写不是用户能选的，
 *     留一个按下去没反应的键只会让人以为坏了（见 ui/PropertyEditor.kt 里
 *     `forcedCase` 的说明）。
 *   * [showBrackets] = false —— **只有"函数"的名字才显示括号**：
 *     函数名本来就是 `a(x)` 这种带括号的形状，而点/方程/滑动条的名字
 *     （`A`、`B`、`a`）纯字母，给它们括号只会让用户打出非法名字。
 *
 * 两个开关都只裁剪**最后一排**（动作排），字母排、返回键、字母顺序
 * 在任何组合下都**一个都不变** —— 属性界面里打字的手感和编辑器里完全一致。
 * 括号和切换键都不显示时（滑动条 / 点 / 方程的名字），那一排就没有内容，
 * 直接**整排消失**（不留一排 6 格的空白，那会白占 0.15 屏高的地方，
 * 而且看起来像"键盘少了一块"）。
 *
 * 四个组合各建一份、只建一次（和原来的单份常量同一个理由：网格是常量，
 * UI 每帧都会要它一次），所以同一个组合重复调用返回的仍是**同一个实例**。
 */
fun alphabetRows(
    showCaseToggle: Boolean = true,
    showBrackets: Boolean = true
): List<List<AlphabetKey>> = when {
    showCaseToggle && showBrackets -> GRID_WITH_CASE_AND_BRACKETS
    !showCaseToggle && showBrackets -> GRID_BRACKETS_ONLY
    showCaseToggle -> GRID_CASE_ONLY
    else -> GRID_PLAIN
}

private val GRID_WITH_CASE_AND_BRACKETS: List<List<AlphabetKey>> =
    buildRows(showCaseToggle = true, showBrackets = true)

/** 属性界面里**函数名**用的那一份：有括号、没有大小写切换键。 */
private val GRID_BRACKETS_ONLY: List<List<AlphabetKey>> =
    buildRows(showCaseToggle = false, showBrackets = true)

/** 有切换键、没括号 —— 目前没人用，留全是为了四个组合都有确定的行为。 */
private val GRID_CASE_ONLY: List<List<AlphabetKey>> =
    buildRows(showCaseToggle = true, showBrackets = false)

/** 点/方程/滑动条的名字用的那一份：只有字母（连动作排都没有）。 */
private val GRID_PLAIN: List<List<AlphabetKey>> =
    buildRows(showCaseToggle = false, showBrackets = false)

private fun buildRows(showCaseToggle: Boolean, showBrackets: Boolean): List<List<AlphabetKey>> {
    val rows = ArrayList<List<AlphabetKey>>()
    // 第 1 格固定是返回键（需求："第一个按钮放返回键"）
    var row = ArrayList<AlphabetKey>(ALPHABET_COLUMNS)
    row += AlphabetKey.Close
    for (letter in 'a'..'z') {
        // 满一排就换行 —— 换行点由 ALPHABET_COLUMNS 唯一决定
        if (row.size == ALPHABET_COLUMNS) {
            rows += row
            row = ArrayList(ALPHABET_COLUMNS)
        }
        row += AlphabetKey.Letter(letter)
    }
    if (row.isNotEmpty()) rows += row

    // 最后一排另起：切换键在最左（也就是左下角），它右边依次是 ( ) ,
    // （两个开关只决定这一排里**有哪些**键、不改变它们的相对顺序）
    val actionRow = ArrayList<AlphabetKey>(4)
    if (showCaseToggle) actionRow += AlphabetKey.ToggleCase
    if (showBrackets) {
        actionRow += AlphabetKey.Symbol('(')
        actionRow += AlphabetKey.Symbol(')')
        actionRow += AlphabetKey.Symbol(',')
    }
    if (actionRow.isNotEmpty()) rows += actionRow
    return rows
}

// ---------------------------------------------------------------------------
// 取值：显示什么 / 插入什么
// ---------------------------------------------------------------------------

/**
 * 这个键在当前模式下**显示**什么。
 *
 * 字母键的标签随模式变（`a` / `A`）—— 这是"现在是大写"最主要的视觉信号；
 * 切换键的标签就是模式名（`小写` / `大写` / `锁定`），三者互不相同，
 * 所以每种模式的视觉都能区分（需求允许"标签不同即可"）。
 *
 * 所有标签都不超过 2 个字符：每排 6 格，最短的那颗键在圆屏收口处只有
 * ≈21dp 宽（见 ui/AlphabetKeyboard.kt 里的宽度核算），
 * 1~2 个字（fontSmall 下 ≈12~24dp）才放得下。
 */
fun labelOf(key: AlphabetKey, case: LetterCase): String = when (key) {
    is AlphabetKey.Letter ->
        if (case.isUppercase) key.letter.uppercaseChar().toString() else key.letter.toString()

    is AlphabetKey.Symbol -> key.symbol.toString()

    AlphabetKey.Close -> ALPHABET_CLOSE_LABEL

    // 切换键上写的就是当前模式名 —— 用户看到的"这个键现在代表什么模式"
    AlphabetKey.ToggleCase -> case.label

    AlphabetKey.Backspace -> ALPHABET_BACKSPACE_LABEL
}

/**
 * 这个键在当前模式下**要插入什么文本**。
 *
 * 返回 null 表示这个键不插入任何文本（返回键 / 切换键 / 退格键）。
 * 需求要的就是这个函数：字母键在模型里只描述字母本身，
 * 大小写在这里**取值时**才被套上去。
 */
fun insertTextOf(key: AlphabetKey, case: LetterCase): String? = when (key) {
    is AlphabetKey.Letter -> labelOf(key, case)
    is AlphabetKey.Symbol -> key.symbol.toString()
    // 三个动作键都不"插入"：返回 / 切换只改界面状态，
    // 退格是**动已有文本**（由 applyTo / editText 处理），语义上不是插入。
    AlphabetKey.Close, AlphabetKey.ToggleCase, AlphabetKey.Backspace -> null
}

/**
 * 一个键在当前模式下的完整描述：标签 + 语义 + 配色分档。
 *
 * UI 层直接画它，不用自己再判一遍"这个键是什么、该用什么色"。
 */
data class AlphabetKeySpec(
    val label: String,
    val key: AlphabetKey,
    val tone: KeyTone
)

/** [labelOf] + [toneOf] 的合体，UI 每画一格就取一次。 */
fun alphabetKeySpec(key: AlphabetKey, case: LetterCase): AlphabetKeySpec =
    AlphabetKeySpec(label = labelOf(key, case), key = key, tone = toneOf(key, case))

/**
 * 配色分档。只用已经统一好的那三档（[KeyTone] → ui/WatchColors.kt 的 KeyPalette）：
 *
 *   * 字母、符号 = Normal（`#2D2F33`，和数学键盘的数字/括号/逗号同一档）；
 *   * 返回键 = Accent（`#4B586B`）—— 和数学键盘的 RAD/DEG 键同一档：
 *     它们是同一类东西（**动作键**，按下去换的是界面/状态，不是输入内容）；
 *   * 切换键 = 小写时 Normal、大写/锁定 Accent —— 强调色在这里的含义是
 *     "这个键盘现在不是默认状态"，用户不用去读那两个汉字也能看出大写开着；
 *   * 退格 = Danger（砖红，和 `⌫` 一致），当前网格上没有它。
 *
 * **不借 Danger 表达"锁定"**：砖红在这套配色里的固定含义是"会删东西"
 * （⌫ / 删除手势 / 撤销条），拿它当"大写锁定"会把这个含义搞乱。
 * 所以 `大写` 和 `锁定` 同用强调色，靠标签区分 —— 这正是需求允许的。
 */
fun toneOf(key: AlphabetKey, case: LetterCase): KeyTone = when (key) {
    is AlphabetKey.Letter, is AlphabetKey.Symbol -> KeyTone.Normal
    AlphabetKey.Close -> KeyTone.Accent
    AlphabetKey.ToggleCase -> if (case == LetterCase.Lower) KeyTone.Normal else KeyTone.Accent
    AlphabetKey.Backspace -> KeyTone.Danger
}

// ---------------------------------------------------------------------------
// 纯函数：把一次按键作用到 (text, cursor) 上
// ---------------------------------------------------------------------------

/**
 * 在光标处执行一次字母表按键。
 *
 * 返回新的文本和新的光标位置，纯函数（所有输入都以参数为唯一依据）。
 *
 * **插入和退格都直接复用数学键盘的 [editText]**，不另写一套：
 * 字母表键盘插的就是同一个表达式输入框，两套键盘必须表现一致 ——
 * 尤其是这两条已经在真机上定过的语义：
 *   * 逗号"跳槽"（光标正好停在一个已存在的逗号前面时按逗号 = 跳过它，
 *     用来填 `log(,)` 这种带槽位的模板）；
 *   * 退格在"空的 `name()` 之间 / `name()` 之后"时**整体删掉这个调用**。
 * 如果这里自己实现一份，两条语义迟早会和数学键盘漂移。
 *
 * 返回键 / 切换大小写键**不动文本**（只把光标夹进合法范围还回去）——
 * 和 [editText] 处理 `ToggleAngleUnit` 的方式完全一致：
 * 真正改界面状态的活由调用方干（`onClose` / `onCaseChange`）。
 */
fun applyTo(
    text: String,
    cursor: Int,
    key: AlphabetKey,
    case: LetterCase
): Pair<String, Int> {
    val insert = insertTextOf(key, case)
    if (insert != null) return editText(text, cursor, KeyAction.Insert(insert))

    return when (key) {
        AlphabetKey.Backspace -> editText(text, cursor, KeyAction.Backspace)
        // 返回 / 切换大小写：文本原样返回（光标只是被夹紧）
        else -> text to cursor.coerceIn(0, text.length)
    }
}
