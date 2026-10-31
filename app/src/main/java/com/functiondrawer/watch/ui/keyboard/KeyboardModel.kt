package com.functiondrawer.watch.ui.keyboard

import com.functiondrawer.watch.engine.PlotMode

/**
 * 数学键盘的数据模型。
 *
 * 这个文件**刻意不依赖 Compose**：它只有纯 Kotlin 的数据类型和纯函数，
 * 所以可以直接用本机 kotlinc + JUnit 离线编译运行（见 tools/run-keyboard-tests.ps1）。
 * 布局和"按下某个键会得到什么字符串"这类容易写错的逻辑，全部在这里被测试覆盖；
 * ui/MathKeyboard.kt 只负责把这里的行列画出来。
 */

/** 按键被按下后要执行的动作。 */
sealed interface KeyAction {
    /**
     * 在光标处插入文本。
     *
     * [cursorBack] 用于把光标挪到插入内容中间，
     * 比如 `||` 插入 `||` 后要退一格，光标正好在两个竖线之间；
     * `sin` 插入 `sin()` 后也要退一格，光标落在括号内。
     */
    data class Insert(val text: String, val cursorBack: Int = 0) : KeyAction

    data object Backspace : KeyAction
    data object Clear : KeyAction
    data object CursorLeft : KeyAction
    data object CursorRight : KeyAction

    /**
     * 切换角度制（RAD ↔ DEG）—— OTHER 页那个显当前制的键。
     *
     * 它**不往输入框里插任何字符**：改的是求值时的角度语义（引擎那一侧的事），
     * 所以 [editText] 收到它时原样返回 `text to cursor`（这样 `when` 也是穷尽的）。
     *
     * 按键上显示的文字**不在这里**：模型给的是占位标签（见 [ANGLE_TOGGLE_PLACEHOLDER]），
     * 真正画出来的是调用方传进 MathKeyboard 的 `angleUnitLabel`
     * （模型是纯函数、拿不到"现在是什么制"，所以只能由 UI 层替换）。
     */
    data object ToggleAngleUnit : KeyAction

    /**
     * 打开内置的英文（字母表）键盘 —— OTHER 页第 3 行、空格键**右边**那个 `abc` 键。
     *
     * 和 [ToggleAngleUnit] 完全同类：**动作键**，不往输入框里插任何字符，
     * 只把"用户要切到字母表键盘"这件事交给调用方
     * （见 MathKeyboard 的 `onOpenAlphabet` 回调）。
     *
     * [editText] 收到它时原样返回 `text to cursor`（这样 `when` 也是穷尽的）：
     * 字母表键盘"要插入什么"是另一套模型的事，见 ui/keyboard/AlphabetModel.kt
     * 的 [AlphabetKey] / [LetterCase]。
     */
    data object OpenAlphabet : KeyAction
}

/**
 * 按键配色分档。
 *
 * 不直接写颜色：配色由 UI 层从 MaterialTheme.colors 里取，
 * 这样模型测试不需要任何 Android/Compose 依赖。
 */
enum class KeyTone { Normal, Accent, Danger }

data class KeySpec(
    val label: String,
    val action: KeyAction,
    val tone: KeyTone = KeyTone.Normal,
    /**
     * 这个键在**一行里占几份宽**（默认 1，也就是"和其它键一样宽"）。
     *
     * 存在的唯一理由：OTHER 页第 3 行要放"空格键（3/4 宽）+ `abc` 键（1/4 宽）"这一对。
     * 行内的宽度是 `Row` 的 `Modifier.weight(...)` 分的，把权重写进模型（3 和 1）
     * 就能一次说清"这一行谁宽谁窄"，不用在 MathKeyboard 里对着某个动作做特判。
     * 缝仍然是同一个 gap（Row 的 `Arrangement.spacedBy`），所以这两颗键之间、
     * 以及它们和别的行的键之间，间隔都是同一个值（需求："中间留和按键相同的缝"）。
     */
    val weight: Int = 1
)

/**
 * 键盘页签。
 *
 * 数字放第一个：输入频率最高，进来就在这一页。
 * 页签是**固定高度的一排**，按键网格永远是 3 行 ——
 * 切页签时按键大小、位置完全不跳变，手指的肌肉记忆不会失效。
 */
enum class KeyPage(val label: String) {
    /** 数字 + 主变量 + 小数点 */
    DIGITS("123"),

    /** 初等函数：四则运算、幂/根/绝对值、三角、对数 */
    ELEMENTARY("初等"),

    /** 字母：变量、常量、括号逗号这类符号 */
    LETTERS("字母"),

    /** 其他函数：取模、多参数函数、取整符号、**取坐标**（`x(A)` / `y(A)`）、角度制切换 */
    OTHER("其他")
}

/**
 * 按键网格固定 3 行（原来 5 行 → 4 行 → 这一轮 3 行）。
 *
 * 真机上原本是 5 行：第 5 行是全局编辑行（`← → C ⌫`）。
 * 实测下来那一行的三个键几乎没人按 —— 光标基本停在末尾、改错就退格，
 * 而 `⌫` 现在挪到输入框右边（见 ui/MathKeyboard.kt 的 `BackspaceKey`）、
 * 清空改成**长按 ⌫**。省下的那一行高度分给按键和输入框。
 *
 * 这一轮再从 4 行收到 3 行，省下来的高度**全部变成按键高度**：
 * 格子高从 0.1226 屏高（28.6dp）涨到 0.15375（≈35.8dp），
 * 高度由 FunctionEditor 的纵向分区推出来（`(GRID_BOTTOM − TAB_Y) / (1 + KEYBOARD_ROWS)`），
 * 这里只是那个推导里的分母。
 */
const val KEYBOARD_ROWS = 3

/**
 * 一行的**标准**键数。
 *
 * 除了 OTHER 页最后一行（空格键 + `abc` 键），别的行都是这个数；
 * 那一行只有 2 个键，靠 [KeySpec.weight] 的 3 : 1 把它切成 3/4 + 1/4
 * （[keyboardRows] 的返回类型 `List<List<KeySpec>>` 本来就允许各行的长度不一样，
 * 见那边的说明）。
 */
const val KEYBOARD_COLUMNS = 4

/**
 * 空格键在它那一行里的宽度份数（= 3/4 行宽）。
 *
 * 另一半 1 份给右边的 `abc` 键（[KeySpec.weight] 默认就是 1）。
 * 之所以要"空格只占 3/4"：真机需求是在同一行右边再放一个 `abc` 键，
 * 而这一行的总宽是定死的（圆屏最下面那一行只有 ≈100dp）。
 */
private const val SPACE_KEY_WEIGHT = 3

// ---------------------------------------------------------------------------
// 按键构造小工具
// ---------------------------------------------------------------------------

/** 插入一个"函数名 + 空括号"的键，光标落在括号里。 */
private fun fn(label: String): KeySpec =
    KeySpec(label, KeyAction.Insert("$label()", cursorBack = 1))

/** 数字键。 */
private fun digit(d: Char): KeySpec = KeySpec(d.toString(), KeyAction.Insert(d.toString()))

/** 运算符 / 符号键。 */
private fun op(label: String, insert: String = label): KeySpec =
    KeySpec(label, KeyAction.Insert(insert))

/** 变量键。 */
private fun variable(name: String): KeySpec =
    KeySpec(name, KeyAction.Insert(name), KeyTone.Accent)

/** 常量键 —— 和变量一样用 Accent 色，和数字、函数区分开。 */
private fun constant(name: String): KeySpec =
    KeySpec(name, KeyAction.Insert(name), KeyTone.Accent)

/** 绝对值键：插入 `||` 并退一格，光标停在两个竖线之间。标签也是 `||`。 */
private fun absBar(): KeySpec = KeySpec("||", KeyAction.Insert("||", cursorBack = 1))

/**
 * `log` 键：本引擎的 `log` 是**两参数**的 `log(底数, 真数)`。
 *
 * 这一版键盘没有光标左右移动键，所以不能只插 `log()` 让用户自己去找底数：
 * 插 `"log(,)"` 并把光标放在**第一个参数的位置**（`(` 之后、`,` 之前）——
 * 用户先输底数、再输真数，逗号和右括号都已经就位。
 *
 * 为什么是 `cursorBack = 2`（而不是需求原文里写的 3）：
 * `editText` 的光标公式是 `cursor = 插入点 + 文本长度 − cursorBack`，
 * `"log(,)"` 长度 6，所以
 *   cursorBack = 1 → 光标 5（`log(,)` 的最后那个 `)` 上）
 *   cursorBack = 2 → 光标 4 ← **`(` 之后、`,` 之前**，正是第一个参数的位置
 *   cursorBack = 3 → 光标 3（`g` 之后、`(` **之前**，也就是落在整个调用外面）
 * 需求原文要的是"( 之后、, 之前"、"光标在第 4 个字符处"（下标 4），
 * 按 3 会落到括号外面、用户打的底数会变成 `log2(,)`，所以按语义取 2。
 * （`fn("sin")` 的 `cursorBack = 1` 是同一个公式：`"sin()"` 长度 5 → 光标 4，
 *   正好夹在括号之间。）
 *
 * `log(x)` 单参数仍然是常用对数，但键盘给的是能表达两个参数的那一种。
 */
private fun logKey(): KeySpec = KeySpec("log", KeyAction.Insert("log(,)", cursorBack = 2))

/**
 * 空格键：插入一个空格。
 *
 * 它**不再独占整行**：权重 [SPACE_KEY_WEIGHT]（3），右边那颗 `abc` 键权重 1，
 * 于是空格占这一行的 3/4、`abc` 占 1/4，两颗键之间是和别处一样的 gap
 * （真机需求："第三列的空格右边增加一个 abc 按钮"）。
 */
private fun spaceKey(): KeySpec =
    KeySpec("空格", KeyAction.Insert(" "), weight = SPACE_KEY_WEIGHT)

/**
 * `abc` 键：OTHER 页第 3 行**空格右边**那一格，按下去打开内置的英文（字母表）键盘。
 *
 *   * 它是**动作键**（[KeyAction.OpenAlphabet]）——不插文本，
 *     真正的插入语义在 ui/keyboard/AlphabetModel.kt 里；
 *   * 权重 1：和空格键的 3 一起把这一行切成 3 : 1；
 *   * 标签 `abc` 用三个小写字母（"字母表"的通用写法）。这一行最窄处只有
 *     ≈105dp 宽（圆屏收口），4 份里的 1 份是 ≈26dp，`abc` 在 fontSmall
 *     下约 18dp，放得下；
 *   * 配色 Accent —— 和 RAD/DEG 键同一档：它们都是"按下去换界面/状态"的动作键，
 *     不是输入键，用强调色和周围的输入键区分开；
 *   * **没有长按变体**（`variantsFor("abc")` 返回空），所以它也不会弹气泡。
 */
private fun alphabetKey(): KeySpec =
    KeySpec("abc", KeyAction.OpenAlphabet, KeyTone.Accent)

/**
 * DEG/RAD 切换键的**占位**标签。
 *
 * 模型不知道"现在是 DEG 还是 RAD"（它是纯函数，没有状态），所以这里放一个占位；
 * MathKeyboard 画这个键时会把标签换成调用方给的 `angleUnitLabel`
 * （判据是 `key.action is KeyAction.ToggleAngleUnit`）。
 * 占位值取 RAD —— 项目里求值的默认制就是弧度。
 */
private const val ANGLE_TOGGLE_PLACEHOLDER = "RAD"

private fun angleToggleKey(): KeySpec =
    KeySpec(ANGLE_TOGGLE_PLACEHOLDER, KeyAction.ToggleAngleUnit, KeyTone.Accent)

/**
 * 字母页第 1 行变量不够 4 个时补位的键，**按这个顺序**：先 `y`，再常量 `π` `e`。
 *
 * 真机反馈（用户原话）："你编辑函数的界面，只有参数 x 的时候键盘不显示 y，
 * 而是显示了一个 tao（希腊字母），请你把这个 tao 去掉，并且给键盘固定一个 y。"
 *
 * 用户说的那个"tao"就是原来的第三个补位常量 `τ`（希腊字母 tau）。
 * 这一轮的处理：
 *   * **`τ` 整个从 App 里拿掉**（原来是 `π e τ φ` 补位）—— 用户不认识它、
 *     也用不上它，而它占的正是最该放变量的那一格；
 *   * 空出来的那一格改成**固定的 `y`**：四种模式的第 1 行**都**能按到 `y`，
 *     不再取决于当前模式有几个变量。
 *
 * 于是第 1 行变成：
 * ```
 * EXPLICIT   x y π e      （用户要的就是这一行）
 * IMPLICIT   x y π e      （`y` 本来就是它的变量，[letterRow] 去重后不重复）
 * PARAMETRIC t y π e
 * POLAR      θ t y π      （两格被变量占了，`e` 让位）
 * ```
 *
 * **有意违反的一条旧规则**：`y` 在 EXPLICIT / POLAR / PARAMETRIC 里不是合法变量，
 * 按下去引擎会报"不认识的名字"（旧注释因此明确禁止把 `y` 放进第 1 行）。
 * 用户的取舍很清楚：他要的是"键盘上**固定**有一个 y"，而不是"当前模式恰好合法才出现"。
 * 所以这条旧规则本轮作废，测试 `letters page offers exactly the variables of the mode`
 * 也为 `y` 开了口子（那里有说明）。
 */
private val PADDING_KEY_ORDER = listOf("y", "π", "e")

// ---------------------------------------------------------------------------
// 各页签的内容（每页 3 行）
// ---------------------------------------------------------------------------

/**
 * 数字页：
 * ```
 * 7 8 9 x
 * 4 5 6 .
 * 1 2 3 0
 * ```
 *
 * 这一轮定下来的内容：**只有数字、小数点和一个主变量**。
 * 加减乘除在初等页（真机上误按 `+` 的频率远高于按它）；
 * 括号、逗号、π 挪到了字母页 / 其他页 —— 数字页的每一格都留给数字，
 * 因为绝大多数输入都是数字。`x` 那一格是**当前模式的主变量**
 * （EXPLICIT / IMPLICIT 是 `x`，POLAR 是 `θ`，PARAMETRIC 是 `t`）。
 */
private fun digitsRows(mode: PlotMode, complex: Boolean): List<List<KeySpec>> = listOf(
    listOf(
        digit('7'), digit('8'), digit('9'),
        // 复平面：这里直接打的是复数，最后一格给虚数单位 `i`
        // （用户 2026-10-31："复平面的函数创建界面的数字一栏的x键内容改成i"）。
        if (complex) constant("i") else variable(primaryVariable(mode))
    ),
    listOf(digit('4'), digit('5'), digit('6'), op(".")),
    listOf(digit('1'), digit('2'), digit('3'), digit('0'))
)

/**
 * 当前模式的主变量 —— 也就是数字页第 1 行最后那一格要放的变量。
 *
 * EXPLICIT / IMPLICIT 是 `x`，POLAR 是 `θ`，PARAMETRIC 是 `t`。
 * `variables` 理论上不会空，这里仍然兜一个 `x`：宁可多一个按下去会报错的键，
 * 也好过键盘在某个模式下直接崩掉。
 */
private fun primaryVariable(mode: PlotMode): String = mode.variables.firstOrNull() ?: "x"

/**
 * 初等页：
 * ```
 * sin cos tan log
 * ^ √ || =
 * + − × ÷
 * ```
 *
 * **第 1 行和第 3 行这一轮对调了**（原来 `+ − × ÷` 在第 1 行、`sin cos tan log` 在第 3 行）。
 * 理由是圆屏越往下越窄，三行的键宽核算是
 *   第 1 行 ≈50.9dp/格、第 2 行 ≈43.0dp/格、第 3 行 ≈23.7dp/格
 * （233dp 屏、gap 1.86dp；算法和数字都在 ui/MathKeyboard.kt 的 `KeyButton` 上面那段）。
 * `sin` / `cos` / `tan` / `log` 是**三个字符**的标签，塞在最窄的 23.7dp 里本来就紧；
 * 用户长按把它换成 `asinh` 之后键上要写 5 个字符（≈32.8dp）—— 那个格子一定装不下。
 * 而 `+ − × ÷` 每格只有**一个字符**，放在第 3 行正合适。
 * 用户原话："初等栏的 +-×÷ 和 sin、cos、tan、log 换个位置，不然显示不下。"
 *
 * 中间那一行（`^ √ || =`）不动。
 *
 * `ln` / `lg` 不再单独占格子：它们和 `log` 是同一个长按变体族
 * （见 [logVariants]，顺序是 `log / ln / lg`，默认显示 `log`）——
 * 这样三个对数都还在键盘上，只是共用一个格子。
 */
private fun elementaryRows(): List<List<KeySpec>> = listOf(
    listOf(fn("sin"), fn("cos"), fn("tan"), logKey()),
    listOf(powerKey(), rootKey("√", "√()"), absBar(), op("=")),
    listOf(op("+"), op("−", "-"), op("×"), op("÷"))
)

/**
 * `^` 键：插 `"^()"` 并把光标放在括号里（`cursorBack = 1`）。
 *
 * 用户原话（2026-10-29）："指数位输入一个数之后就跳到指数外面，那我要是指数位置
 * 想输入一个式子怎么办呢？……指数位也要能输入式子啊"。
 *
 * 只插一个裸 `^` 时，指数在语法上只吃**一个幂**（`parsePower`），
 * 打完一个数再按 `+` 就落到基线上 —— 指数里永远只能放一个数。
 * 插成 `^()` 之后指数是一个**括号里的完整表达式**（`parseSum`），
 * `x^(2+1)` 这种式子就能一路打下去；括号和根号 `√()` 一样是"看不见的括号"，
 * 排版层不会把它画成真的圆括号（见 `MathRender` 的 `^(` 分支）。
 * 空骨架按一次退格整段删掉（[STRUCTURAL_TEMPLATES]）。
 */
private fun powerKey(): KeySpec =
    KeySpec("^", KeyAction.Insert("^()", cursorBack = 1))

/**
 * 字母页第一行：把当前模式的合法变量放进来，不足 4 个按 [PADDING_KEY_ORDER] 补位。
 *
 * 补位顺序保证**每种模式下都有 `y`**（见 [PADDING_KEY_ORDER] 的说明 ——
 * 这是用户明确要的"固定一个 y"，压过"不放非法变量"那条旧规则）。
 *
 * 变量最多两个（POLAR 的 θ t、IMPLICIT 的 x y），所以这一行永远补得满 4 格。
 * 已经作为变量出现过的名字**不重复补**：IMPLICIT 的 `y` 是变量，补位就跳到 `π`。
 */
private fun letterRow(vars: List<String>): List<KeySpec> {
    val row = ArrayList<KeySpec>(KEYBOARD_COLUMNS)
    vars.forEach { row += variable(it) }
    for (name in PADDING_KEY_ORDER) {
        if (row.size >= KEYBOARD_COLUMNS) break
        if (row.any { it.label == name }) continue
        // 补位键统一用 [variable]（插同名文本 + Accent 配色）。
        // `π` / `e` 用 [constant] 画出来一模一样（两个 helper 的实现相同），
        // 这里不为了语义好看把同一行拆成两种构造。
        row += variable(name)
    }
    return row
}

/**
 * 预留的**可绑定参数**键：`a` `b` `c` `d`。
 *
 * 语义和变量键**完全一样**（插入同名文本、Accent 配色、没有长按变体），
 * 所以直接复用 [variable]；单独起个名字只是为了让 [lettersRows] 那一行
 * 读起来是"参数"而不是"变量"。
 *
 * 为什么强调这一点：这四个名字**不属于任何模式的采样变量**
 * （见 engine.PlotMode.variables）—— 它们走的是引擎里另一条通道
 * `engine/Parameters.kt` 的 `ParameterSet`（四种模式都接受，没给值时按 1.0 算）。
 * 之所以不能把它们塞进 `variables`：那份列表是**采样输入**，
 * 顺序就是 `eval(...)` 的实参顺序、采样器按它的长度分配参数数组。
 *
 * 它们也不是函数名 —— 退格该逐字符删（[isKnownFunctionName] 里没有它们），
 * 所以千万别把它们塞进 [FUNCTION_NAMES] 那份函数名清单。
 *
 * 取值来源已经接好：滑动条列表（`GraphState.sliders`）驱动 `ParameterSet`，
 * 拖动时曲线实时变（见 `GraphState.parameters()`）。键盘这一侧不用知道这件事 ——
 * 它只负责插入名字，取值在编译表达式时由引擎折成常量。
 */
private fun param(name: String): KeySpec = variable(name)

/**
 * 字母页：
 * ```
 * <当前模式的变量 + 固定补 `y` `π` `e`，凑满 4 格>
 * a b c d
 * ( ) , ∞
 * ```
 *
 * 第 1 行这一轮**变了**：用户要求"把这个 tao（`τ`）去掉，并且给键盘固定一个 y"，
 * 所以补位顺序从 `π e τ φ` 改成 [`PADDING_KEY_ORDER`] 的 `y π e` ——
 * 四种模式下都能按到 `y`，`τ` 从整个 App 消失（见那份常量的说明）。
 *
 * 第 2 行是 `a b c d`（四个预留的可绑定参数，见 [param]）。
 *
 * 代价（之前那一轮明确接受的取舍）：`φ` 从此不在键盘上。
 *
 * 第 3 行是括号和逗号的入口 —— 数字页和初等页都不再放它们了，
 * 所以这一行是打 `sin(x)`、`log(2,8)` 这类式子时必按的一行。
 */
private fun lettersRows(
    mode: PlotMode,
    complex: Boolean = false,
    polar: Boolean = false
): List<List<KeySpec>> = listOf(
    when {
        // 复平面（用户 2026-10-31）：输入框里是复数表达式，`x` / `y` 换成
        // `Re()` / `Im()`（取实部 / 虚部，引擎里的函数名就是 `re` / `im`）。
        complex -> listOf(
            KeySpec("Re()", KeyAction.Insert("Re()", cursorBack = 1)),
            KeySpec("Im()", KeyAction.Insert("Im()", cursorBack = 1)),
            constant("π"),
            constant("e")
        )
        // 极坐标（用户 2026-10-31）：第一行固定 `r θ π e`。
        polar -> listOf(
            variable("r"),
            variable("θ"),
            constant("π"),
            constant("e")
        )
        else -> letterRow(mode.variables)
    },
    listOf(param("a"), param("b"), param("c"), param("d")),
    listOf(op("("), op(")"), op(","), constant("∞"))
)

// ---------------------------------------------------------------------------
// 「取坐标」键：x(A) / y(A)
// ---------------------------------------------------------------------------

/**
 * 取坐标键的两个**取法（轴）**，顺序固定：`x` 在前、`y` 在后。
 *
 * 和三角函数 / 对数那几族一样，**只有基键是长按入口**：网格里放的是 `x(A)`，
 * `y(A)` 只能从它长按选出来（见 [variantsFor] / [resolvedKey]）。
 * 顺序决定了气泡里哪一格高亮 —— 基键在第 0 格（见 [currentVariantIndex]）。
 */
private val COORD_AXES: List<String> = listOf("x", "y")

/** 基键的那根轴 —— 网格里默认显示的取法（`x` 比 `y` 常用）。 */
private const val COORD_BASE_AXIS = "x"

/**
 * 取坐标键的标签：`x(A)` / `y(A)`。
 *
 * 括号里那个 `A` 是**示意**的点名：图形里的点默认就叫 `A` / `B` / `C`
 * （见 GraphState.nextUpperName()），这里只是让用户一眼看出"括号里要填的是点名"。
 * 它**不是可以点的东西** —— 按下去只拿到一对空括号，点名要用户自己在括号里打
 * （字母表键盘见 [alphabetKey]）。需求原话："图标默认是 x(A)"。
 */
private fun coordLabel(axis: String): String = "$axis(A)"

/**
 * 取坐标键**基键**的 label（`x(A)`）—— [variantsFor] 的入口就按它取。
 *
 * 写成"由 [COORD_BASE_AXIS] 推出来"而不是再抄一遍字面量：
 * 抄两遍的话，哪天改了标签写法就会出现"网格里是 x(A)、变体表认的是别的字符串"
 * 这种静默失效（键还在，长按却弹不出气泡）。
 */
private val COORD_BASE_LABEL: String = coordLabel(COORD_BASE_AXIS)

/**
 * 取坐标键：把**一个点的坐标**当成一个数字常量参与运算。
 *
 * 语义（需求原话："点击之后会在输入框显示 x()（这是一个整体），默认定位到括号中间，
 * 括号里输入一个点的名字，把这个点的 x 坐标作为数值参与运算，
 * 长按打开 sin 同款菜单，切换 x(A) 和 y(A)"）：
 *
 *   * **短按**插入 `"x()"` 并退一格 —— 和 [fn] 一样是 `cursorBack = 1`，
 *     光标落在括号中间，用户接着打点名（`A`）就得到 `x(A)`。
 *     插入的是一对**括号**而不是 `x(A)` 这种带示例名字的文本：
 *     点名由用户决定，键盘不替他挑。
 *   * **长按**弹出和 `sin` 完全同款的气泡（[variantsFor] 给出 [coordVariants]，
 *     选一项 = 把这个键本身换掉，见 [applyVariantSelection] / [resolvedKey]）。
 *     换成 `y(A)` 之后短按插的就是 `"y()"`。
 *
 * 配色用 [KeyTone.Accent]（和变量 / 常量 / 参数键、以及 RAD/DEG、`abc` 那两个
 * 动作键同一档），理由是这一格**不是数学函数**：它最终就是一个数
 * （"点 A 的横坐标"），语义上更接近 `x` / `π` 这类"直接给一个值"的键；
 * 而 Normal 档留给 `sin` / `max` / `%` 那种"后面还要接参数"的函数键。
 * 这样在真机上一眼就能把它和左右两颗取整符号键（Normal 档）分开。
 */
private fun coordKey(axis: String): KeySpec = KeySpec(
    label = coordLabel(axis),
    action = KeyAction.Insert("$axis()", cursorBack = 1),
    tone = KeyTone.Accent
)

/** 取坐标键的长按变体：`x(A)` / `y(A)` 两项，基键在第一格。 */
private fun coordVariants(): List<KeySpec> = COORD_AXES.map { coordKey(it) }

/**
 * 其他页：
 * ```
 * %   max    min    <DEG/RAD 切换键>
 * ⌈x⌉ ⌊x⌋   x(A)   ,
 * <─ 空格键（3/4 行宽）─> abc
 * ```
 *
 * 几条约定：
 *   * `%` 插 `"%"`：词法层已经把 `%` 当取模运算符（等价于 `mod`）。
 *   * `⌈x⌉` / `⌊x⌋` 用**符号**当标签（Unicode，Text 直接就能画），
 *     插进去的是 `ceil()` / `floor()` 并把光标放进括号 ——
 *     比在 24.8dp 宽的格子里硬塞 `floor` 五个字母读起来清楚得多。
 *   * 第 2 行的第三格是**取坐标键**（[coordKey]，默认 `x(A)`）——
 *     短按插 `x()`（光标在括号里），长按在 `x(A)` / `y(A)` 之间换。
 *   * 第 2 行的第四格是 `,`（[op] 的普通插入，位置和语义都**没动过**）。
 *   * 最后一行现在是**两个键**：空格键（3/4 行宽，[spaceKey]）+
 *     `abc` 键（1/4 行宽，[alphabetKey]）。原来空格是横跨整行的，
 *     这一轮真机需求要在它右边加一个打开英文字母表键盘的入口，
 *     所以整行按 [KeySpec.weight] 的 3 : 1 切开，缝不变。
 *   * 角度制那个键是**动作键**（[KeyAction.ToggleAngleUnit]），不插文本；
 *     `abc` 键也是动作键（[KeyAction.OpenAlphabet]），同样不插文本。
 *
 * **这一轮第 2 行只动了一格**：`abs` 撤掉、空出来的第 3 格改成取坐标键
 * `x(A)`（`abs` 的绝对值入口是**初等页第 2 行**那颗 `||`，见 [absBar]——
 * `||` 插一对竖线、光标夹在中间，比 `abs()` 更贴近手写习惯，所以其他页这一格
 * 是重复入口，用户原话："把逗号、abs 去掉吧，逗号和 abs 前面有了"）。
 *
 * **逗号按用户后来的要求留下来了，不要删**（用户原话："其他页面的逗号先别删，
 * 留着占位"）—— 所以第 2 行仍然是**标准的 4 个键**（4 列等宽，和别的行对齐），
 * 只是第 3 格从 `abs` 换成了 `x(A)`：
 * ```
 * ⌈x⌉  ⌊x⌋  x(A)  ,
 * ```
 * 逗号的插入语义一个字都没改（仍然插 `","`，含 `editText` 里那条"光标正对着
 * 模板逗号就跳过它"的规则）—— 它只是先留着占位，等以后要放别的键时再说。
 * 所以**不要**为了"少一个重复入口"把它删掉：用户的取舍是"位置先留住"。
 */
private fun otherRows(): List<List<KeySpec>> = listOf(
    listOf(op("%"), fn("max"), fn("min"), angleToggleKey()),
    listOf(
        KeySpec("⌈x⌉", KeyAction.Insert("ceil()", cursorBack = 1)),
        KeySpec("⌊x⌋", KeyAction.Insert("floor()", cursorBack = 1)),
        coordKey(COORD_BASE_AXIS),
        // 逗号：**用户要求保留的占位键**，位置（第 4 格）和语义都不动
        op(",")
    ),
    listOf(spaceKey(), alphabetKey())
)

/**
 * 某个页签在当前模式下的按键网格：**恰好 [KEYBOARD_ROWS] 行**。
 *
 * 行数固定是为了让按键在任何页签、任何模式下都落在同一个位置 ——
 * 圆屏上没有多余空间留给"某一行少一个键"导致的变形。
 *
 * 但**行内键数不再强制等于 [KEYBOARD_COLUMNS]**：OTHER 页最后一行是
 * "空格键（3/4 宽）+ `abc` 键"这一对，那一行只有 2 个键（[spaceKey] / [alphabetKey]）。
 * 所以这里只兜"行数"，不再给短行补 `||` 键 —— 补了的话空格那一行会被
 * 悄悄变成 4 个键（测试 `only the other page has a single key row` 盯着这件事）。
 *
 * 网格里**没有编辑键**：`⌫` 在输入框右边、清空是长按 `⌫`。
 */
fun keyboardRows(
    page: KeyPage,
    mode: PlotMode,
    /**
     * 复平面：数字页最后一格换成 `i`，字母页第一行换成 `Re() Im() π e`。
     *
     * 用布尔而不是直接接 `CoordinateSystem`：这个文件要能被 `kotlinc` 单独离线编译
     * （坐标类型住在带 Compose 的 `GraphState.kt` 里，引进来就编不过了）。
     */
    complex: Boolean = false,
    /**
     * 极坐标：字母页第一行固定 `r θ π e`（用户 2026-10-31 明确要求的顺序）。
     */
    polar: Boolean = false
): List<List<KeySpec>> {
    val content: List<List<KeySpec>> = when (page) {
        KeyPage.DIGITS -> digitsRows(mode, complex)
        KeyPage.ELEMENTARY -> elementaryRows()
        KeyPage.LETTERS -> lettersRows(mode, complex, polar)
        KeyPage.OTHER -> otherRows()
    }

    // 行数兜底 + 超长行截断：免得以后增删按键时整块网格错位。
    // （空行仍然补满 4 个键：一行都不画会让网格塌掉，补 `||` 至少还是能用的键）
    val body: List<List<KeySpec>> = content.take(KEYBOARD_ROWS).map { row ->
        when {
            row.isEmpty() -> List(KEYBOARD_COLUMNS) { absBar() }
            row.size > KEYBOARD_COLUMNS -> row.take(KEYBOARD_COLUMNS)
            else -> row
        }
    }.toMutableList()
    return if (body.size >= KEYBOARD_ROWS) body
    else body + List(KEYBOARD_ROWS - body.size) { List(KEYBOARD_COLUMNS) { absBar() } }
}

// ---------------------------------------------------------------------------
// 长按变体
// ---------------------------------------------------------------------------

/**
 * 长按某个按键时弹出的候选变体。返回空列表表示这个键没有变体。
 *
 * 一个键只放得下一个函数名，但三角函数/对数各有几个相近的变体
 * （sin / asin / sinh / asinh ……），手表上又不可能为它们各开一个键。
 * 于是照 Culculator 的做法：短按用当前这个，长按弹出**并排的全部变体**。
 *
 * 真机上定下来的语义和 Culculator 不同：点变体**不是立刻往输入框里塞东西**，
 * 而是把这个键本身"换成"那个变体（见 [resolvedKey] / [applyVariantSelection]）——
 * 输入框里已经有内容时，误点一下变体不该把 asinh() 插到光标处。
 *
 * 插入文本遵守本项目既有约定 —— 函数键插 `"sin()"` 并且 `cursorBack = 1`，
 * 光标落在括号里（见 [fn]）。
 *
 * 只有**组里的基键**是入口：`asin` 自己不给变体，它只能从 `sin` 长按选出来。
 *
 * `abs` **没有族**（原来有 sign / floor / ceil）：这一轮 `floor` / `ceil`
 * 已经在其他页各有一个符号键了（`⌊x⌋` / `⌈x⌉`），再挂在 abs 的长按里就是重复入口。
 *
 * `x(A)` **有族**（两个取法 `x` / `y`）：这是用户明确要的
 * "长按打开 sin 同款菜单，切换 x(A) 和 y(A)" —— 它虽然只插一对括号、
 * 不是数学函数，但"一格放不下两个取法、长按换键"这套交互和三角函数完全一样，
 * 所以直接复用同一套机制（[coordKey] / [coordVariants]）。
 */
fun variantsFor(label: String): List<KeySpec> = when (label) {
    "sin" -> trigVariants("sin")
    "cos" -> trigVariants("cos")
    "tan" -> trigVariants("tan")
    // 对数族：log 是键盘上的基键，ln / lg 从它长按出来。
    // （"ln" 这个分支留着是为了兼容老状态里以 ln 为基键的选择表；
    //   ln 已经不在网格里了，它只有这一个入口。）
    "log", "ln" -> logVariants()
    // √ 和 ∛ 是同一个键的两种根：∛ 词法层已经支持（∛ → cbrt）
    "√" -> rootVariants()
    // 取坐标键：同一个"格子"的两种取法。分支条件用的是推导出来的 label，
    // 不是再抄一遍的字面量（理由见 [COORD_BASE_LABEL]）。
    COORD_BASE_LABEL -> coordVariants()
    else -> emptyList()
}

/** 三角函数键的四件套：原函数 / 反函数 / 双曲 / 反双曲。顺序固定，和 Culculator 一致。 */
private fun trigVariants(key: String): List<KeySpec> =
    listOf(fn(key), fn("a$key"), fn("${key}h"), fn("a${key}h"))

/**
 * 对数族：`log / ln / lg`。
 *
 * **`log` 排第一个**：网格上那个键显示的是 `log`，气泡里当前项也就高亮在第一格
 * （见 [currentVariantIndex]）。
 *
 * `log` 用的是两参数写法 `log(, )`（见 [logKey]），`ln` / `lg` 是一元函数、
 * 插 `"ln()"` / `"lg()"` 并把光标放进括号。
 */
private fun logVariants(): List<KeySpec> = listOf(logKey(), fn("ln"), fn("lg"))

/**
 * 根号键：
 *   * `√` / `∛` 插 `"√()"` / `"∛()"`，`cursorBack = 1` —— `editText` 的光标公式是
 *     `光标 = 插入点 + 长度 − cursorBack`，长度 3 减 1 = 落在 `(` 之后、`)` 之前，
 *     也就是**根号里面**（和 [fn] 的 `sin()` 同一条规则）；
 *   * `ⁿ√` 插 `"root(,)"`，`cursorBack = 1` —— 长度 7 减 1 = 落在 `,` 之后、
 *     `)` 之前，也就是**次数槽位**（见 [rootVariants]）。
 *
 * **为什么要带这对括号**（用户原话："让输入根号之后在解析里面自动带根号，
 * 不然根号里不能输整式，还要加一个看不见的括号才行"）：
 * 只插一个 `√` 时，`√x+1` 按运算符优先级会被解析成 `√x` 再 `+1`；
 * 带上括号之后源码是 `√(x+1)`，而 `MathRender` 把 `√(...)` 的括号**吃掉**、
 * 只画一根要开方的横线 —— 看起来是手写根号，语义上却是一个"看不见的括号"。
 *
 * 退格会把空骨架一次删掉（见 [deleteStructuralCallBefore] 的
 * [STRUCTURAL_TEMPLATES]：符号式 `√()` 和函数式 `sqrt()` 都收），
 * 不会留下半截括号。
 */
private fun rootKey(label: String, insert: String): KeySpec =
    KeySpec(label, KeyAction.Insert(insert, cursorBack = 1))

/**
 * 根号族：平方根 / 立方根 / **n 次根号**。
 *
 * `ⁿ√` 插的是 `root(,)`（引擎里的 `root(被开方数, 次数)`），
 * 光标落在**次数槽位**（`,` 之后、`)` 之前）—— 用户原话："输入 n 次根号时
 * 优先定位到次方位置"。要回去填被开方数就点一下根号下面那块（见
 * `MathRender` 里的空槽停靠点）。
 */
private fun rootVariants(): List<KeySpec> = listOf(
    rootKey("√", "√()"),
    rootKey("∛", "∛()"),
    rootKey("ⁿ√", "root(,)")
)

/**
 * 当前正在用的那个变体在 [variants] 里的下标 —— 气泡靠它把选中项高亮。
 *
 * 认不出来（比如这个键本来就不属于这一组）就退到第一个：
 * 高亮错一个总好过整排都没有高亮。
 */
fun currentVariantIndex(variants: List<KeySpec>, currentLabel: String): Int {
    val i = variants.indexOfFirst { it.label == currentLabel }
    return if (i >= 0) i else 0
}

/**
 * 记下"某个键现在被换成了哪个变体"。
 *
 * [selections] 的键是**基键的 label**（也就是 [keyboardRows] 里给出来的那个），
 * 值是这个键此刻实际代表的名字。用基键当键是因为：网格里那个键的 label
 * 会随选择变，但"它是从哪一个基键变来的"必须记得住 ——
 * 否则把 sin 换成 asinh 之后就再也长按不回 sin 了。
 *
 * 选回基键本身就把这一项删掉（而不是留一个"asinh → asinh"的项），
 * 这样 map 里永远只有真正被改过的键，测试和调试都好读。
 */
fun applyVariantSelection(
    selections: Map<String, String>,
    baseLabel: String,
    variantLabel: String
): Map<String, String> =
    if (baseLabel == variantLabel) selections - baseLabel
    else selections + (baseLabel to variantLabel)

/**
 * 把网格里的一个键"翻译"成它此刻实际代表的键。
 *
 * 没有选择过（或者选择的名字已经不在这组里，比如以后改了变体表）就返回 [base] 本身：
 * 键盘宁可退回默认的那一个键，也不能因为一个失效的选择变成空白。
 */
fun resolvedKey(base: KeySpec, selections: Map<String, String>): KeySpec {
    val picked = selections[base.label] ?: return base
    return variantsFor(base.label).firstOrNull { it.label == picked } ?: base
}

// ---------------------------------------------------------------------------
// 退格：整体删掉一个调用（数学函数 `sin()` / 取坐标 `x()`）
// ---------------------------------------------------------------------------

/**
 * **已知函数名清单** —— 退格"整体删除"要用（见 [deleteWholeCallBefore]）。
 *
 * 清单内容和引擎的 `MathFns.ARITY` 一一对应，但**故意在这里抄一份**，理由是：
 *
 *   1. `MathFns` 是 engine 包里的 `internal object`，函数表 `ARITY` 又是 **private** 的：
 *      外面根本拿不到"有哪些函数名"这份清单，只能一个名字一个名字地
 *      `isFunction(name)` 去问。要把清单暴露成公开 API 就得改 engine 里的文件 ——
 *      那是另一个改动正在动的目录，本文件不该伸手，也不该为了一件 UI 逻辑
 *      去扩大引擎的公开面。
 *   2. ui/keyboard 这一层一直是"纯 Kotlin、只依赖 engine.PlotMode 这个枚举"
 *      （见 tools/run-keyboard-tests.ps1 开头那段说明）。
 *   3. 这张表是**键盘的输入语义**，不是引擎的实现细节：它只回答
 *      "退格时该一次吃掉哪几个字符"。引擎以后加了新函数，键盘要不要整体删它，
 *      本来就是键盘自己的决定。
 *
 * 抄一份的代价是可能和引擎漂移，所以有测试盯着：
 * `every function name the grid can type is in the delete list`
 * 要求键盘上能打出来的函数名（`name()` 形式）都必须在这张表里。
 *
 * **`x` / `y` 不在这张表里**，它们在另一张表 [COORDINATE_CALL_NAMES] 里
 * （原因写在那张表的说明里，一句话：它们不是数学函数、是采样变量）。
 *
 * 顺序按长度**从长到短**：[deleteWholeCallBefore] 是按顺序找第一个匹配的，
 * 先长后短才能把 `asin()` 整个吃掉，而不是先撞上 `sin` 只删掉后半截。
 */
private val FUNCTION_NAMES: List<String> = listOf(
    "sin", "cos", "tan", "cot", "sec", "csc",
    "asin", "acos", "atan",
    "sinh", "cosh", "tanh", "coth",
    "asinh", "acosh", "atanh",
    "exp", "ln", "lg", "lb", "log",
    "sqrt", "cbrt", "abs", "sign", "floor", "ceil", "round", "trunc", "frac",
    "gamma", "erf", "sinc", "deg", "rad",
    "atan2", "pow", "mod", "root", "hypot", "min", "max", "clamp"
).sortedByDescending { it.length }

/** [FUNCTION_NAMES] 的只读查询（给 [editText] 和测试用，见那张表的说明）。 */
fun isKnownFunctionName(name: String): Boolean = name in FUNCTION_NAMES

/**
 * **"调用式变量"名字清单** —— 其他页那个取坐标键插进来的 `x()` / `y()`
 * （见 [coordKey]）。
 *
 * 为什么不把它们并进 [FUNCTION_NAMES]：
 *
 *   1. **它们在引擎里不是函数**。`x` / `y` 是**采样变量**（`PlotMode.variables`），
 *      `MathFns.ARITY` 里根本没有这两个名字 —— 混进函数名清单会让
 *      `isKnownFunctionName("x")` 说"x 是函数"，而键盘和测试都拿这个函数
 *      回答"这个名字该不该整体删"，语义就串了
 *      （测试 `every function name the grid can type is in the delete list`
 *       末尾专门断言 `x` / `y` 不是函数名，那是防漂移的钉子）。
 *   2. **行为会变坏**。整体删除的判定只看形状（`name(` 或 `name()`），
 *      名字越短越容易误撞：`x` 只有一个字符，`x(` 在用户手里经常只是
 *      "括号打在变量 x 后面"的普通写法（比如 `2x(x+1)`），
 *      把它当成一次函数调用整体删掉，退格会莫名其妙吃掉三个字符。
 *      放进单独一张表之后，"什么时候该整体删"这件事仍然是显式的、可测的。
 *
 * 那为什么还要给它们整体删除？因为**用户按一下这个键得到的是一对括号**
 * （需求原话："这是一个整体"）：`sin()` 退格能整个还回去，`x()` 没理由不能。
 * [deleteWholeCallBefore] 只关心形状，不关心这个名字在引擎里是函数还是变量，
 * 所以两张表可以走**同两条**规则 —— 合并成 [WHOLE_CALL_NAMES] 就够了，
 * 不需要再抄一份判定逻辑。
 */
private val COORDINATE_CALL_NAMES: List<String> = listOf("x", "y")

/** [COORDINATE_CALL_NAMES] 的只读查询（给测试用，见那张表的说明）。 */
fun isCoordinateCallName(name: String): Boolean = name in COORDINATE_CALL_NAMES

/**
 * 整体删除的**候选名字**：引擎函数名 + 调用式变量名。
 *
 * 两张表合并之后仍然按长度从长到短排（`sortedByDescending` 是稳定排序，
 * 所以 `x` / `y` 这两个单字符名字排在最后，不会抢在 `sin` / `abs` 前面匹配）。
 */
private val WHOLE_CALL_NAMES: List<String> =
    (FUNCTION_NAMES + COORDINATE_CALL_NAMES).sortedByDescending { it.length }

/**
 * 名字左边不能还是字母，否则光标左边那个名字其实更长：
 * `asin()` 里的 `sin` 是 `asin` 的后半截，不能当独立的函数名来删。
 *
 * 只挡字母：数字不算 —— `2sin()` 里的 `sin()` 该整体删掉（那正是隐式乘法）。
 *
 * 这条对 `x` / `y` 尤其重要：`ax()` 里的 `x` 左边是字母，所以不算一次调用 ——
 * 保守处理，宁可多按几次退格，也不要把用户没打算删的字母吃掉。
 */
private fun isNameBoundary(text: String, nameStart: Int): Boolean =
    nameStart == 0 || !text[nameStart - 1].isLetter()

/**
 * 根号类 / 绝对值按键插入的**空骨架**：`√()` / `∛()` / `root(,)`，
 * 以及它们的**函数式写法** `sqrt()` / `cbrt()` / `abs()`。
 *
 * 用户原话："像根号删除的时候就是一步删除，不要点击删除之后还露出来括号，
 * 把这个括号作隐式括号"。这几种骨架的括号是**看不见的**（排版层把它们吃掉了），
 * 所以退格也必须把它们当成一个整体一次还回去 —— 否则屏幕上会先冒出半个括号。
 *
 * 为什么同一颗根号要收两种写法：键盘插的是符号式 `√()` / `∛()`，
 * 而 `Latex.expand` 会把 LaTeX 的 `\sqrt{x}` 翻成函数式 `sqrt(x)`、
 * 用户也可能直接打出 `cbrt()` / `abs()`。两者在排版层是同一棵结构
 * （见 `MathRender` 的 `sqrt(...)` / `cbrt(...)` 分支），删除自然也要一致 ——
 * 少一种写法，那种写法按退格就会先留下半截裸括号。
 */
private val STRUCTURAL_TEMPLATES: List<String> = listOf(
    "√()", "∛()", "root(,)",
    "sqrt()", "cbrt()", "abs()",
    // 上标容器（`^` 键插的全部内容，见 [powerKey]）：空的时候一次退格整段删掉，
    // 不能留下 `x^` 或者半个 `)` —— 用户会以为"删不干净"。
    "^()"
)

/**
 * 光标左边是不是一个**完整的空骨架**（[STRUCTURAL_TEMPLATES] 之一），是就整段删掉。
 *
 * 两种情形：
 *   1. 光标正好在骨架末尾（`√()` 打完就退格）；
 *   2. 光标夹在骨架**内部**（老师先点了括号中间再退格）。
 *
 * 只认**空**骨架：`√(x+1)` 里有内容，退格应该逐字符删（用户还在编辑式子）。
 */
private fun deleteStructuralCallBefore(text: String, c: Int): Pair<String, Int>? {
    for (template in STRUCTURAL_TEMPLATES) {
        // 情形 1：整段在光标左侧
        val before = c - template.length
        if (before >= 0 && text.startsWith(template, before) &&
            isTemplateBoundary(text, before, template)
        ) {
            return (text.substring(0, before) + text.substring(c)) to before
        }
        // 情形 2：光标在模板内部（模板从光标左边某处开始、跨过光标）
        for (cut in 1 until template.length) {
            val start = c - cut
            if (start >= 0 && text.startsWith(template, start) &&
                isTemplateBoundary(text, start, template)
            ) {
                return (text.substring(0, start) + text.substring(start + template.length)) to start
            }
        }
    }
    return null
}

/**
 * 骨架起点左边算不算"边界"。
 *
 * 函数名（`sqrt()`、`abs()`）必须要求前面不是字母，否则 `asin` 会被当成 `sin`
 * 的后半截（和 [isNameBoundary] 同一个理由）。而上标容器 `^()` **恰好相反**：
 * 它前面本来就该是一个操作数（`x^()`、`2^()`），拿"前面不是字母"去卡它，
 * 一次也匹配不上。所以以 `^` 开头的模板不做这个检查。
 */
private fun isTemplateBoundary(text: String, start: Int, template: String): Boolean =
    if (template.startsWith("^")) true else isNameBoundary(text, start)

/**
 * 插入 [insert] 之前要不要先补一个 `*`（见 [editText] 的 Insert 分支）。
 *
 * 只在**同时**满足下面两条时补：
 *   1. [insert] 是一个函数名 + 左括号（`sin(`、`log(,`、`root(,` …）；
 *   2. 光标左边紧挨着字母 / 数字 / 右括号 —— 也就是词法器会把两边粘成一个名字的情形。
 *
 * 为什么不给 `√` / `∛` 补：它们不是字母，[readIdent] 读到这里就停了，
 * `a√x` 天然被切成 `a` 和 `√x` 两截，不需要乘号。
 *
 * 为什么补的是 `*` 而不是 `·`：`*` 是项目里现成的写法（见 [mul]），
 * 引擎两种都认，但源码里保留 ASCII 更容易让用户看懂自己打了什么。
 */
private fun needsTimesBefore(text: String, c: Int, insert: String): Boolean {
    if (c <= 0 || c > text.length) return false
    val name = insert.substringBefore('(')
    if (name.isEmpty() || name.length == insert.length) return false
    if (!isKnownFunctionName(name) && !isKnownFunctionName(name.lowercase())) return false
    val prev = text[c - 1]
    return prev.isLetterOrDigit() || prev == ')'
}

/**
 * 光标左边是不是一个"调用"（`name()` / `name(` + `)`），是就整体删掉。
 *
 * 候选名字是 [WHOLE_CALL_NAMES]：引擎函数名 + 取坐标键的 `x` / `y`。
 *
 * 两条规则（真机反馈："插入 `sin()` 之后按退格，应该把 `sin()` 整体删掉"）：
 *   1. 光标左边形如 `name()`，且光标正好在 `)` 之后 → 一次删掉 `name()`。
 *   2. 光标左边形如 `name(`，且光标右边紧挨着 `)`（光标在空括号中间）
 *      → 一次删掉 `name(` 和那个 `)`。
 *
 * 返回 null 表示"这里没有调用可删"，让调用方退回逐字符删除。
 *
 * 故意**不**处理的情况（按需求保持原样）：
 *   * `name` 后面没有括号（比如光秃秃一个 `sin`、或者 `2x` 末尾那个 `x`）→ 逐字符删。
 *   * 两参数函数的空骨架 `log(,)`（光标在 `(` 之后、`,` 之前）→ 逐字符删，
 *     所以退格只吃掉了 `(`，留下 `log,)`。这条是照需求"其它情况保持现在的行为"
 *     做的取舍；要改成整体删掉，在下面加一条"右边是 `,)` 就删 `name(,)`"即可。
 *   * `x(A)` 这种**括号里有内容**的写法 → 也逐字符删（和 `sin(A)` 一样）：
 *     用户这时候改的是点名，不是整个取坐标。想撤掉整个 `x()` 就先把 `A` 删掉，
 *     或者按规则 2 在空括号之间退格（一次全没）。
 */
private fun deleteWholeCallBefore(text: String, c: Int): Pair<String, Int>? {
    for (name in WHOLE_CALL_NAMES) {
        // 规则 1：name() —— 光标正好在 ) 之后
        val callStart = c - name.length - 2
        if (callStart >= 0 &&
            text[c - 1] == ')' &&
            text[c - 2] == '(' &&
            text.startsWith(name, callStart) &&
            isNameBoundary(text, callStart)
        ) {
            return (text.substring(0, callStart) + text.substring(c)) to callStart
        }

        // 规则 2：name( 与 ) 之间 —— 光标在空括号里
        val openStart = c - name.length - 1
        if (openStart >= 0 &&
            c < text.length &&
            text[c - 1] == '(' &&
            text[c] == ')' &&
            text.startsWith(name, openStart) &&
            isNameBoundary(text, openStart)
        ) {
            return (text.substring(0, openStart) + text.substring(c + 1)) to openStart
        }
    }
    return null
}

/**
 * 光标是不是**正好夹在一对相邻的竖线之间**：左边那个字符和右边那个字符都是 `|`。
 *
 * 这是 [editText] 里"退格成对删竖线"的判定条件。`||` 键插进来的是 `"||"`、
 * 光标落在两根竖线中间（见 [absBar]），所以在真机上刚按完这个键就是这个状态。
 *
 * 判定必须**两边都看**：只要求"左边是 `|`"会让 `|x` 末尾退格时把唯一的竖线也吞掉，
 * 只要求"右边是 `|`"会让 `x|` 一样。光标在文本开头（`cursor == 0`）或末尾
 * （`cursor == length`）时右边/左边没有字符，一律不算成对。
 */
private fun isBetweenBars(text: String, cursor: Int): Boolean =
    cursor in 1 until text.length && text[cursor - 1] == '|' && text[cursor] == '|'

/**
 * 在光标处执行一次编辑。
 *
 * 返回新的文本和新的光标位置。纯函数，方便推理：
 * 所有输入都以 (text, cursor) 为唯一依据，没有隐藏状态。
 *
 * 退格有三条规则，按这个顺序判：
 *   1. 光标在开头 → 什么都不做。
 *   2. 光标夹在一对竖线之间 → **两根一起删**（[isBetweenBars]）。真机反馈：
 *      按下 `||` 之后键盘给的是 `"||"`，只删掉左半边会留下一个孤零零的 `|`，
 *      还得再按一次 —— 一次退格把这一对还回去才符合直觉。
 *   3. 光标左边是一个调用 `name()` / `name(` + `)` → **整体删掉**
 *      （[deleteWholeCallBefore]，真机反馈："插入 `sin()` 之后按退格，
 *      应该把 `sin()` 整体删掉，而不是只删一个字母"）。
 *      候选名字含函数名和取坐标键的 `x` / `y`（见 [COORDINATE_CALL_NAMES]）。
 *   4. 其它情况一律逐字符删（开头退格无操作，光标先夹紧到合法范围）。
 *
 * [KeyAction.Clear] / [KeyAction.CursorLeft] / [KeyAction.CursorRight]
 * 虽然**键盘上已经没有对应的按键**（清空 = 长按 `⌫`，光标左右移没有了），
 * 这里仍然要处理：外部（老数据、将来的撤销、测试）依然可能送进来，
 * 而且 sealed interface 的 `when` 少一个分支就编译不过。
 */
fun editText(text: String, cursor: Int, action: KeyAction): Pair<String, Int> {
    val c = cursor.coerceIn(0, text.length)
    return when (action) {
        is KeyAction.Insert -> {
            // 逗号"跳槽"：光标正好停在一个已存在的逗号前面时，按逗号 = 跳过它，
            // 而不是再插一个。这是 log(,) 这种"带槽位模板"能填完的关键 ——
            // 3 行重排之后键盘上**没有左右移键**了（KeyPage 的编辑行被撤掉），
            // 没有这条规则，用户打完底数 `log(2,)` 就再也走不到真数槽位。
            //
            // 只在这一种情况下触发（光标左边是模板自带的逗号），所以
            // `log(10,100)` 这种"先把底数打完"的写法完全不受影响：
            // 打完 10 → 按逗号跳过 → 光标落在 `100` 该在的位置 ✓
            if (action.text == "," && c < text.length && text[c] == ',') {
                return text to (c + 1)
            }
            // 函数键**紧贴在字母 / 数字 / 右括号后面**时，补一个显式乘号。
            //
            // 用户原话："很尴尬的情况就是 a·sinx 在没打乘号的时候被识别成 asinx"。
            // 词法层（engine/Lexer.kt 的 [readIdent]）会把连着的字母读成**一个**名字，
            // 所以 `a` 后面直接按 `sin` 得到的源码 `asin()` 会被当成一个不认识的
            // 名字 `asin`，而不是 `a*sin()`。排版层没有这个问题，
            // 但求值层必须有乘号才分得开 —— 于是让键盘自己补上，
            // 用户看到的仍然是「a·sin(x)」这种手写习惯。
            val glue = if (needsTimesBefore(text, c, action.text)) "*" else ""
            val inserted = glue + action.text
            val next = text.substring(0, c) + inserted + text.substring(c)
            next to (c + inserted.length - action.cursorBack).coerceIn(0, next.length)
        }

        KeyAction.Backspace ->
            when {
                // 开头退格：什么都不做
                c == 0 -> text to 0
                // 夹在一对竖线之间：两根一起删（右边那根的下标是 c，所以要跳到 c + 1）
                isBetweenBars(text, c) ->
                    (text.substring(0, c - 1) + text.substring(c + 1)) to (c - 1)

                else -> deleteStructuralCallBefore(text, c)
                    ?: deleteWholeCallBefore(text, c)
                    ?: ((text.substring(0, c - 1) + text.substring(c)) to (c - 1))
            }

        KeyAction.Clear -> "" to 0
        KeyAction.CursorLeft -> text to (c - 1).coerceAtLeast(0)
        KeyAction.CursorRight -> text to (c + 1).coerceAtMost(text.length)
        // 角度制切换**不动文本、不动光标**：它改的是求值语义（引擎那一侧），
        // 所以这里原样返回，光标位置也只是把入参夹紧之后还回去。
        KeyAction.ToggleAngleUnit -> text to c
        // 打开字母表键盘：同样是**不动文本**的动作键（见 [KeyAction.OpenAlphabet]）。
        // 它只是告诉调用方"把界面换成字母表键盘"，插什么由那一侧的模型决定
        // （ui/keyboard/AlphabetModel.kt）。
        KeyAction.OpenAlphabet -> text to c
    }
}
