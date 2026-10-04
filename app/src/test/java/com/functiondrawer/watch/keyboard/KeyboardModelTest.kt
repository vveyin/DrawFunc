package com.functiondrawer.watch.keyboard

import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.ui.keyboard.KEYBOARD_COLUMNS
import com.functiondrawer.watch.ui.keyboard.KEYBOARD_ROWS
import com.functiondrawer.watch.ui.keyboard.KeyAction
import com.functiondrawer.watch.ui.keyboard.KeyPage
import com.functiondrawer.watch.ui.keyboard.KeySpec
import com.functiondrawer.watch.ui.keyboard.KeyTone
import com.functiondrawer.watch.ui.keyboard.applyVariantSelection
import com.functiondrawer.watch.ui.keyboard.currentVariantIndex
import com.functiondrawer.watch.ui.keyboard.editText
import com.functiondrawer.watch.ui.keyboard.isCoordinateCallName
import com.functiondrawer.watch.ui.keyboard.isKnownFunctionName
import com.functiondrawer.watch.ui.keyboard.keyboardRows
import com.functiondrawer.watch.ui.keyboard.resolvedKey
import com.functiondrawer.watch.ui.keyboard.variantsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘数据模型的单元测试。
 *
 * 这些断言都是**离线可跑**的（纯 Kotlin + JUnit），
 * 覆盖四类最容易出错、又最难在手表上肉眼发现的东西：
 *   1. 布局本身 —— 3 行的网格（OTHER 页第 2 行 3 个键、最后一行 2 个键）、
 *      没有任何编辑键、变量是不是按模式给对了；
 *   2. 长按变体 —— 每个族有几项、顺序对不对、"点变体是换键而不是插入"
 *      （本轮多了取坐标键 `x(A)` / `y(A)` 这一族）；
 *   3. editText 的光标语义 —— 尤其是 cursorBack 有没有把光标放进括号里；
 *   4. 退格的两条特殊规则 —— `||` 成对删、`name()` 整体删
 *      （`name` 含取坐标键插的 `x()` / `y()`）。
 */
class KeyboardModelTest {

    private val modes = listOf(
        PlotMode.EXPLICIT,
        PlotMode.POLAR,
        PlotMode.PARAMETRIC,
        PlotMode.IMPLICIT
    )

    private val pages = KeyPage.entries.toList()

    /** 所有模式里出现过的变量名，用来检查"某页有没有放非法变量"。 */
    private val allVariables = modes.flatMap { it.variables }.toSet()

    /**
     * 用户要求"给键盘固定一个 y"（原话见 KeyboardModel 的 `PADDING_KEY_ORDER`），
     * 所以字母页第 1 行在**任何模式**下都补一个 `y`。
     *
     * 这让 `y` 在 EXPLICIT / POLAR / PARAMETRIC 里成了"合法的非法变量"，
     * 所以凡是"别的模式的变量一个都不许出现"的断言都要为它开一个口子 ——
     * 统一用这个常量判断，免得以后漏掉某一处。
     */
    private val FIXED_Y = "y"

    /** 有长按变体气泡的基键 —— 只有这几个是入口，别的名字都是它们长按出来的。 */
    // 这一轮的变化：
    //   * "ln" 从入口变成了**变体成员**（它只在 log 的长按里出现）；
    //   * "abs" 不再有族（floor / ceil 已经在其他页各有一个符号键了）。
    // 本轮用户要求再加一个入口：OTHER 页的**取坐标键** `x(A)` ——
    // 长按在 `x(A)` / `y(A)` 之间换，和 sin 那族是同一套机制
    // （需求原话："长按打开 sin 同款菜单，切换 x(A) 和 y(A)"）。
    private val variantEntryKeys = setOf("sin", "cos", "tan", "log", "√", "x(A)")

    /** 取坐标键的标签：基键 `x(A)`，长按变出来的那一项 `y(A)`。 */
    private val COORD_X = "x(A)"
    private val COORD_Y = "y(A)"

    /** 已经归进变体气泡、不该再单独占格子的函数名。 */
    private val variantMemberNames = setOf(
        "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
        "ln", "lg", "∛"
        // 注意：sign / floor / ceil 不在这张表里 —— 它们不是变体，
        // 而是在「其他」页各占一格（floor / ceil 是 ⌊x⌋ / ⌈x⌉ 两个符号键）。
    )

    private fun rows(page: KeyPage, mode: PlotMode) = keyboardRows(page, mode)

    /**
     * 某一页的所有按键 label（按行铺平）。
     *
     * **不能**再用"每行 4 个键"去推下标：OTHER 页最后一行只有 1 个键。
     */
    private fun labels(page: KeyPage, mode: PlotMode): List<String> =
        rows(page, mode).flatten().map { it.label }

    private fun allKeys(page: KeyPage, mode: PlotMode): List<KeySpec> =
        rows(page, mode).flatten()

    // ---- 网格结构：3 行 ----

    @Test
    fun `every page and every mode is a three row grid`() {
        for (mode in modes) {
            for (page in pages) {
                val grid = rows(page, mode)
                assertEquals("$mode / $page 的行数", KEYBOARD_ROWS, grid.size)
                assertEquals("$mode / $page 的行数", 3, grid.size)
            }
        }
    }

    @Test
    fun `every row has four keys except the space bar row of the other page`() {
        // 这一轮的真机需求：前三行 4 个键；OTHER 页第 3 行是
        // **「空格键（3/4 宽）+ abc 键（1/4 宽）」**这一对，所以那一行是 2 个键
        // —— 所以"每行恰好 4 键"这条断言换成了这个形状。
        // （abc 键本身在 AlphabetModelTest 里另有一组测试盯着。）
        //
        // **本轮用户要求又改了一次**：OTHER 页第 2 行撤掉 `abs`（"abs 前面有了"），
        // 那一格换成取坐标键 `x(A)` —— 但**逗号被用户点名留下来了**
        //（原话："其他页面的逗号先别删，留着占位"），所以那一行仍然
        // **是标准的 4 个键**（`⌈x⌉ ⌊x⌋ x(A) ,`），和其它行一样 4 列等宽。
        for (mode in modes) {
            for (page in pages) {
                rows(page, mode).forEachIndexed { index, row ->
                    val expected =
                        if (page == KeyPage.OTHER && index == 2) 2 else KEYBOARD_COLUMNS
                    assertEquals(
                        "$mode / $page 第 ${index + 1} 行的键数",
                        expected,
                        row.size
                    )
                }
            }
        }
    }

    @Test
    fun `grid constants match the produced rows`() {
        // 3 行 × 4 列（OTHER 页最后一行只有 2 个键：空格 + abc）
        assertEquals(3, KEYBOARD_ROWS)
        assertEquals(4, KEYBOARD_COLUMNS)
        for (mode in modes) {
            assertEquals("$mode 数字页的键数", 12, allKeys(KeyPage.DIGITS, mode).size)
            assertEquals("$mode 初等页的键数", 12, allKeys(KeyPage.ELEMENTARY, mode).size)
            assertEquals("$mode 字母页的键数", 12, allKeys(KeyPage.LETTERS, mode).size)
            // 仍然是 4 + 4 + 2 = 10：第 2 行只换了 1 格（abs → x(A)），
            // 逗号按用户要求留着占位，所以这一页的键数**没有变**
            assertEquals("$mode 其他页的键数（4 + 4 + 2）", 10, allKeys(KeyPage.OTHER, mode).size)
        }
    }

    @Test
    fun `no key has a blank label`() {
        for (mode in modes) {
            for (page in pages) {
                rows(page, mode).forEachIndexed { index, row ->
                    row.forEach { key ->
                        assertNotNull("$mode / $page 第 ${index + 1} 行出现 null label", key.label)
                        assertTrue(
                            "$mode / $page 第 ${index + 1} 行出现空 label —— 留空会让网格错位",
                            key.label.isNotBlank()
                        )
                    }
                }
            }
        }
    }

    // ---- 编辑键：整行都没了 ----

    @Test
    fun `no page has an edit row any more`() {
        for (mode in modes) {
            for (page in pages) {
                val ls = labels(page, mode)
                for (gone in listOf("←", "→", "C", "⌫")) {
                    assertTrue(
                        "$mode / $page 不该再有编辑键 $gone —— 它已经被搬到输入框右边 / 变成长按了",
                        !ls.contains(gone)
                    )
                }
            }
        }
    }

    @Test
    fun `no grid key carries an edit action`() {
        // KeyAction 里的 Clear / CursorLeft / CursorRight 保留（editText 仍然要能处理），
        // 但网格里一个都不许出现：⌫ 是输入框右边那个单独的按钮，清空是长按它。
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    val action = key.action
                    assertTrue(
                        "$mode / $page 的 ${key.label} 不该是编辑动作 $action",
                        action !is KeyAction.Clear &&
                            action !is KeyAction.CursorLeft &&
                            action !is KeyAction.CursorRight &&
                            action !is KeyAction.Backspace
                    )
                }
            }
        }
    }

    // ---- 数字页 ----

    @Test
    fun `digits page has every digit exactly once`() {
        for (mode in modes) {
            val ls = labels(KeyPage.DIGITS, mode)
            for (d in 0..9) {
                val s = d.toString()
                assertEquals("$mode 数字页里 '$s' 的出现次数", 1, ls.count { it == s })
            }
        }
    }

    @Test
    fun `digits page has no arithmetic operators`() {
        // 四则运算全部搬到初等页了：数字页上按到 + 的概率比按它本身高得多
        for (mode in modes) {
            val ls = labels(KeyPage.DIGITS, mode)
            for (o in listOf("+", "−", "×", "÷")) {
                assertTrue("$mode 的数字页不该出现运算符 $o", !ls.contains(o))
            }
        }
    }

    @Test
    fun `digits page is exactly the three rows of the new layout`() {
        // ```
        // 7 8 9 x
        // 4 5 6 .
        // 1 2 3 0
        // ```
        // 括号 / 逗号 / π 都从数字页撤掉了（括号和逗号在字母页，π 在字母页），
        // 每一格都留给数字和那个主变量。
        for (mode in modes) {
            val grid = rows(KeyPage.DIGITS, mode)
            val primary = mode.variables.first()
            assertEquals(
                "$mode 数字页第 1 行",
                listOf("7", "8", "9", primary),
                grid[0].map { it.label }
            )
            assertEquals(
                "$mode 数字页第 2 行",
                listOf("4", "5", "6", "."),
                grid[1].map { it.label }
            )
            assertEquals(
                "$mode 数字页第 3 行",
                listOf("1", "2", "3", "0"),
                grid[2].map { it.label }
            )
            val ls = labels(KeyPage.DIGITS, mode)
            for (gone in listOf("(", ")", ",", "π")) {
                assertTrue("$mode 的数字页不该再有 $gone（它搬到别的页去了）", !ls.contains(gone))
            }
            // 别的模式的变量一个都不许出现（按下去就是"不认识的名字"）
            for (illegal in allVariables - mode.variables.toSet()) {
                assertTrue("$mode 数字页不该出现非法变量 $illegal", !ls.contains(illegal))
            }
        }
    }

    @Test
    fun `digits page primary variable is accent tone`() {
        // 数字页上只有主变量那一格是变量键（它插的就是这个变量本身）
        for (mode in modes) {
            val primary = mode.variables.first()
            val key = allKeys(KeyPage.DIGITS, mode).first { it.label == primary }
            assertEquals("$primary 的配色", KeyTone.Accent, key.tone)
            assertEquals(KeyAction.Insert(primary), key.action)
            // 数字、小数点都是普通档，别把整个数字页染成主色
            for (other in allKeys(KeyPage.DIGITS, mode)) {
                if (other.label != primary) {
                    assertEquals("${other.label} 的配色", KeyTone.Normal, other.tone)
                }
            }
        }
    }

    // ---- 初等函数页 ----

    @Test
    fun `elementary page has the four arithmetic operators on its third row`() {
        // 这一轮把第 1 行和第 3 行对调了：四则运算从第 1 行搬到**最窄的第 3 行**，
        // 因为 `+ − × ÷` 每格只有一个字符，而第 3 行只有 ≈23.7dp/格；
        // 三个字符的 `sin cos tan log` 搬到了最宽的第 1 行（≈50.9dp/格）。
        // 用户原话："初等栏的 +-×÷ 和 sin、cos、tan、log 换个位置，不然显示不下。"
        for (mode in modes) {
            val grid = rows(KeyPage.ELEMENTARY, mode)
            assertEquals(
                "$mode 初等页第三行",
                listOf("+", "−", "×", "÷"),
                grid[2].map { it.label }
            )
        }
        // 减号插 ASCII 的 '-'（U+2212 只是显示用），乘除插符号本身，词法层都认
        val third = rows(KeyPage.ELEMENTARY, PlotMode.EXPLICIT)[2]
        assertEquals(KeyAction.Insert("-"), third[1].action)
        assertEquals(KeyAction.Insert("×"), third[2].action)
        assertEquals(KeyAction.Insert("÷"), third[3].action)
    }

    @Test
    fun `elementary page is exactly the three rows of the new layout`() {
        // ```
        // sin cos tan log
        // ^ √ || =
        // + − × ÷
        // ```
        // 第 1 / 第 3 行这一轮对调过（见上一条测试）。
        for (mode in modes) {
            val grid = rows(KeyPage.ELEMENTARY, mode)
            assertEquals(listOf("sin", "cos", "tan", "log"), grid[0].map { it.label })
            assertEquals(listOf("^", "√", "||", "="), grid[1].map { it.label })
            assertEquals(listOf("+", "−", "×", "÷"), grid[2].map { it.label })
        }
    }

    @Test
    fun `elementary page puts the long labels on the widest row and short ones on the narrowest`() {
        // 这次对调的**唯一理由**就是键宽，所以把它钉成一条测试，
        // 免得以后有人只看"标签集合没变"就把两行换回去：
        //   * 圆屏越往下越窄：第 1 行 ≈50.9dp/格、第 2 行 ≈43.0dp/格、第 3 行 ≈23.7dp/格
        //     （233dp 屏、gap 1.86dp，算法见 ui/MathKeyboard.kt 的 KeyButton 上面那段）；
        //   * 三个字符的三角 / 对数名必须在**最宽**的那一行（长按换成 asinh 之后
        //     要写 5 个字符 ≈32.8dp，23.7dp 的格子装不下）；
        //   * 最窄的那一行只能放一个字符的键。
        val grid = rows(KeyPage.ELEMENTARY, PlotMode.EXPLICIT)
        assertEquals(
            "三个字符的 sin/cos/tan/log 应该在最宽的第 1 行",
            listOf("sin", "cos", "tan", "log"),
            grid[0].map { it.label }
        )
        for (key in grid[2]) {
            assertEquals(
                "最窄的第 3 行（≈23.7dp/格）只放单字符键，${key.label} 太长了",
                1,
                key.label.length
            )
        }
        // 换位置的方向必须对：最宽那行的标签比最窄那行的标签长。
        // （反过来的话这次对调就白做了。）
        val widest = grid[0].maxOf { it.label.length }
        val narrowest = grid[2].maxOf { it.label.length }
        assertTrue(
            "最宽的第 1 行标签（最长 $widest 字符）应该比最窄的第 3 行（最长 $narrowest）长",
            widest > narrowest
        )
    }

    @Test
    fun `elementary page no longer offers the names that moved or became variants`() {
        // exp / mod / round / trunc / frac 这些这一轮从键盘上撤掉了（引擎仍然支持），
        // ln / lg 变成 log 的长按变体，abs 挪到其他页。
        for (mode in modes) {
            val ls = labels(KeyPage.ELEMENTARY, mode)
            for (gone in listOf("exp", "mod", "ln", "lg", "abs", "round", "trunc", "frac")) {
                assertTrue("初等页不该再出现 $gone", !ls.contains(gone))
            }
            // 单个 `|` 也没有了：绝对值键的标签和插入内容都是 `||`
            assertTrue("初等页不该再出现单个 | 的键", !ls.contains("|"))
        }
    }

    @Test
    fun `elementary page does not repeat the names that live in the variant bubbles`() {
        // asin / sinh / ln / lg 这些都是长按 sin / log 选出来的，再各占一格就是浪费
        for (mode in modes) {
            val ls = labels(KeyPage.ELEMENTARY, mode)
            for (name in variantMemberNames) {
                assertTrue("初等页不该再出现 $name", !ls.contains(name))
            }
        }
    }

    // ---- 字母页 ----

    @Test
    fun `letters page offers exactly the variables of the mode`() {
        for (mode in modes) {
            val ls = labels(KeyPage.LETTERS, mode)
            // 该模式的变量必须都在
            for (v in mode.variables) {
                assertTrue("$mode 的字母页必须有变量 $v", ls.contains(v))
            }
            // 其它模式的变量一个都不能出现（按下去就是"不认识的名字"）……
            // **`y` 例外**：用户要求"给键盘固定一个 y"（原话见 KeyboardModel 的
            // PADDING_KEY_ORDER），所以 EXPLICIT / POLAR / PARAMETRIC 里
            // 也**故意**留着 `y` —— 旧规则"不放非法变量"被这一条用户需求压过。
            // 别的变量（`t` 在 EXPLICIT 里之类）仍然一个都不许出现。
            for (illegal in allVariables - mode.variables.toSet()) {
                if (illegal == FIXED_Y) continue
                assertTrue(
                    "$mode 的字母页不该出现非法变量 $illegal",
                    !ls.contains(illegal)
                )
            }
        }
    }

    @Test
    fun `letters page pads the variable row with a fixed y and constants`() {
        // 用户原话："你编辑函数的界面，只有参数 x 的时候键盘不显示 y，
        // 而是显示了一个 tao（希腊字母），请你把这个 tao 去掉，并且给键盘固定一个 y。"
        // 所以第 1 行的补位从 `π e τ φ` 改成 `y π e`：
        //   * `τ`（那个"tao"）**从整个 App 消失**；
        //   * `y` 是**固定**的 —— 四种模式的第 1 行都按得到它；
        //   * 变量占掉的格子优先，所以 POLAR 里 `e` 让位（θ t y π）。
        assertEquals(
            listOf("t", "y", "π", "e"),
            rows(KeyPage.LETTERS, PlotMode.PARAMETRIC)[0].map { it.label }
        )
        assertEquals(
            listOf("x", "y", "π", "e"),
            rows(KeyPage.LETTERS, PlotMode.EXPLICIT)[0].map { it.label }
        )
        // 两格被变量占掉：补位只补得下 `y` 和 `π`
        assertEquals(
            listOf("θ", "t", "y", "π"),
            rows(KeyPage.LETTERS, PlotMode.POLAR)[0].map { it.label }
        )
        // `y` 本来就是 IMPLICIT 的变量，补位时去重，不会出现两个 `y`
        assertEquals(
            listOf("x", "y", "π", "e"),
            rows(KeyPage.LETTERS, PlotMode.IMPLICIT)[0].map { it.label }
        )
    }

    @Test
    fun `letters page first row only carries the legal variables of the mode plus padding keys`() {
        // 第 1 行只能由"该模式的变量 + `y` / `π` / `e` 补位"组成。
        // 注意 `y` 现在**在四种模式里都补**（用户要求"固定一个 y"），
        // 所以它在 EXPLICIT / POLAR / PARAMETRIC 里也算合法内容 —— 见下面的例外。
        for (mode in modes) {
            val row = rows(KeyPage.LETTERS, mode)[0].map { it.label }
            // 该模式的变量必须一个不少地在这一行
            for (v in mode.variables) {
                assertTrue("$mode 字母页第 1 行必须有变量 $v", row.contains(v))
            }
            // 其它模式的变量一个都不许出现（`y` 例外，见上）
            for (illegal in allVariables - mode.variables.toSet()) {
                if (illegal == FIXED_Y) continue
                assertTrue("$mode 字母页第 1 行不该出现非法变量 $illegal", !row.contains(illegal))
            }
            // 剩下那些位置只能是补位键，不能是别的东西（比如第 2 行的参数）
            val allowedPadding = listOf(FIXED_Y, "π", "e")
            for (label in row) {
                assertTrue(
                    "$mode 字母页第 1 行的 $label 既不是该模式的变量，也不是补位键",
                    label in mode.variables || label in allowedPadding
                )
            }
            // 参数在**第 2 行**，第 1 行不重复放
            for (p in listOf("a", "b", "c", "d")) {
                assertTrue("$mode 字母页第 1 行不该出现参数 $p（它在第 2 行）", !row.contains(p))
            }
            // 去重：`y` 在 IMPLICIT 里是变量，不能既当变量又当补位键出现两次
            assertEquals("$mode 字母页第 1 行不该有重复键", row.size, row.toSet().size)
        }
    }

    @Test
    fun `letters page second row is the four reserved parameters in every mode`() {
        // 这一轮第 2 行从 `π e τ φ` 换成 `a b c d` —— 四个预留的可绑定参数。
        // 它们在**四种模式下都是同一行同样的四个键**（不随模式变），
        // 因为它们是留给"参数可绑定"的槽位，不是模式变量。
        for (mode in modes) {
            val row = rows(KeyPage.LETTERS, mode)[1]
            assertEquals("$mode 字母页第 2 行", listOf("a", "b", "c", "d"), row.map { it.label })
            // 每个参数键插入的就是它自己的名字（和变量键同一种语义）
            assertEquals("a", (row[0].action as KeyAction.Insert).text)
            assertEquals("b", (row[1].action as KeyAction.Insert).text)
            assertEquals("c", (row[2].action as KeyAction.Insert).text)
            assertEquals("d", (row[3].action as KeyAction.Insert).text)
            for (key in row) {
                assertEquals("参数键 ${key.label} 没有长按变体", emptyList<KeySpec>(), variantsFor(key.label))
            }
        }
    }

    @Test
    fun `reserved parameters are plain identifiers and backspace eats them one character at a time`() {
        // `a b c d` 是变量 / 参数，**不是函数名**（FUNCTION_NAMES 里没有它们），
        // 所以它们不走"整体删掉 name()"那条规则。这条测试是防漂移的：
        // 别为了别的目的把 a~d 塞进函数名清单 —— 那会让退格行为变得莫名其妙。
        for (p in listOf("a", "b", "c", "d")) {
            assertTrue("$p 不该是函数名", !isKnownFunctionName(p))
            // 从字母页第 2 行真的按一下这个键：插进去的就是它自己的名字
            val key = rows(KeyPage.LETTERS, PlotMode.EXPLICIT)[1].first { it.label == p }
            val (text, cursor) = editText("", 0, key.action)
            assertEquals(p, text)
            assertEquals(1, cursor)
            // 退格：单字符，一次删掉一个（不是"整个调用"）
            assertEquals("" to 0, editText(text, cursor, KeyAction.Backspace))
            // 式子中间也一样：`xa` 末尾退格只吃掉 a，x 留着
            assertEquals("x" to 1, editText("x$p", 2, KeyAction.Backspace))
            // 连着两个参数名，退格只吃最后一个
            assertEquals("a" to 1, editText("ab", 2, KeyAction.Backspace))
        }
    }

    @Test
    fun `letters page has constants and symbols`() {
        val ls = labels(KeyPage.LETTERS, PlotMode.EXPLICIT)
        // `π` / `e` 在第 1 行的补位里，`∞` 在第 3 行
        for (c in listOf("π", "e", "∞")) {
            assertTrue("字母页要有常量 $c", ls.contains(c))
        }
        for (s in listOf("(", ")", ",")) {
            assertTrue("字母页要有符号 $s", ls.contains(s))
        }
        // 这一轮的取舍：第 2 行换成 a b c d 之后，**φ 从键盘上消失了**。
        // 这条断言是故意写死的 —— 哪天要把它加回来，就得先想清楚放哪一格。
        for (mode in modes) {
            assertTrue(
                "$mode 的字母页不该再有 φ（第 2 行已换成 a b c d）",
                !labels(KeyPage.LETTERS, mode).contains("φ")
            )
        }
        // 而 τ（那个"tao"）这一轮被用户点名去掉了：**四种模式的键盘上都不该再有它**。
        // 同时 `y` 是固定的 —— 每种模式的字母页都必须按得到。
        for (mode in modes) {
            val ls = labels(KeyPage.LETTERS, mode)
            assertTrue(
                "$mode 的字母页不该再有 τ（用户要求去掉那个希腊字母）",
                !ls.contains("τ")
            )
            assertTrue(
                "$mode 的字母页必须有固定的 $FIXED_Y 键",
                ls.contains(FIXED_Y)
            )
        }
    }

    @Test
    fun `letters page keeps its three rows and drops the old fourth row`() {
        // 原来第 4 行是 `√ ^ | =` —— 这一轮整行删掉（√ / ^ / || / = 都在初等页）
        for (mode in modes) {
            val grid = rows(KeyPage.LETTERS, mode)
            assertEquals("$mode 字母页的行数", 3, grid.size)
            // 第 2 行：`π e τ φ` → `a b c d`（四个预留的可绑定参数）
            assertEquals(
                "$mode 字母页第 2 行",
                listOf("a", "b", "c", "d"),
                grid[1].map { it.label }
            )
            assertEquals(
                "$mode 字母页第 3 行",
                listOf("(", ")", ",", "∞"),
                grid[2].map { it.label }
            )
            val ls = labels(KeyPage.LETTERS, mode)
            for (gone in listOf("√", "^", "=", "|", "||")) {
                assertTrue("$mode 的字母页不该再有 $gone", !ls.contains(gone))
            }
        }
    }

    @Test
    fun `letters page variables constants and reserved parameters are accent tone`() {
        // 参数键和变量 / 常量一样用 Accent 档，和数字、函数、符号区分开。
        // `y` 现在**四种模式都有**（用户要求固定一个 y），所以它的配色也要钉住：
        // 它在 IMPLICIT 里是变量、在别的模式里是补位键，但外观必须是同一颗键。
        for (mode in modes) {
            for (key in allKeys(KeyPage.LETTERS, mode)) {
                if (key.label in mode.variables ||
                    key.label == FIXED_Y ||
                    key.label in listOf("π", "e", "∞") ||
                    key.label in listOf("a", "b", "c", "d")
                ) {
                    assertEquals("变量/常量/参数键 ${key.label} 的配色", KeyTone.Accent, key.tone)
                }
            }
        }
    }

    // ---- 其他函数页 ----

    @Test
    fun `other page is exactly the three rows of the new layout`() {
        // ```
        // %  max  min  <DEG/RAD>
        // ⌈x⌉ ⌊x⌋ x(A) ,
        // <─ 空格键（3/4 行宽）─> abc
        // ```
        // 第 2 行这一轮**只动了一格**（用户要求）：`abs` 撤掉（"abs 前面有了"），
        // 那一格换成取坐标键 `x(A)`；**逗号按用户后来的要求留着占位**
        //（原话："其他页面的逗号先别删，留着占位"），所以这一行仍然是 **4 个键**
        //（⌈x⌉ ⌊x⌋ x(A) ,），和其它行一样 4 列等宽。
        for (mode in modes) {
            val grid = rows(KeyPage.OTHER, mode)
            assertEquals("其他页的行数", 3, grid.size)
            assertEquals("其他页第 1 行的前三个键", listOf("%", "max", "min"), grid[0].take(3).map { it.label })
            assertEquals("其他页第 2 行", listOf("⌈x⌉", "⌊x⌋", COORD_X, ","), grid[1].map { it.label })
            // 第 3 行这一轮从"一个整行空格键"变成"空格 3/4 + abc 1/4"两个键：
            // abc 是打开内置英文字母表键盘的**动作键**（见 KeyAction.OpenAlphabet）
            assertEquals("其他页第 3 行", listOf("空格", "abc"), grid[2].map { it.label })
            // 第 1 行最后一格是角度制切换键：模型里放的是占位标签，UI 会换成当前制
            val angle = grid[0][3]
            assertTrue("角度制键的标签", angle.label in listOf("RAD", "DEG"))
            for (key in grid.flatten()) {
                assertTrue("其他页不该出现空标签", key.label.isNotBlank())
            }
        }
    }

    @Test
    fun `other page swaps abs for the coordinate key and keeps the comma as a placeholder`() {
        // 用户先后两句话合起来才是这一行的最终形状：
        //   1. "函数编辑页面的其他键盘，把逗号、abs去掉吧，逗号和abs前面有了。"
        //   2. **"其他页面的逗号先别删，留着占位。"**（后来补的，优先级最高）
        // 所以：`abs` 去掉（绝对值入口是初等页第 2 行的 `||`），
        // 它的第 3 格给取坐标键 `x(A)`，而**逗号原样留在第 4 格**。
        for (mode in modes) {
            val ls = labels(KeyPage.OTHER, mode)
            assertTrue("$mode 的其他页不该再有 abs 键（初等页的 || 就是绝对值）", !ls.contains("abs"))
            assertTrue("初等页要有 || 绝对值键", labels(KeyPage.ELEMENTARY, mode).contains("||"))
            // 逗号是**用户要求保留的占位键**：既要在这一页，位置也不能挪
            assertEquals("$mode 其他页的逗号键出现次数", 1, ls.count { it == "," })

            val row = rows(KeyPage.OTHER, mode)[1]
            assertEquals("其他页第 2 行仍然是标准的 4 个键", KEYBOARD_COLUMNS, row.size)
            assertEquals("逗号必须在**第 4 格**（原来是哪一格就留在哪一格）", ",", row[3].label)
            assertEquals("取坐标键占的是 abs 原来的第 3 格", COORD_X, row[2].label)
            // 4 个键等宽 = 宽度份数都是默认的 1（不补键、也不做 3:1 那种切分）
            for (key in row) {
                assertEquals("其他页第 2 行的 ${key.label} 应该等宽", 1, key.weight)
            }
            // 逗号的插入语义**一个字都没改**：还是插 `","`（跳槽规则见 editText）
            assertEquals("逗号的插入语义没动过", KeyAction.Insert(","), row[3].action)
            // 逗号不是长按入口（长按气泡只服务于函数族和取坐标键）
            assertTrue("逗号不该有变体", variantsFor(",").isEmpty())
        }
    }

    @Test
    fun `the coordinate key inserts a whole empty call and lets the long press swap x A for y A`() {
        // 本轮用户需求（原话）："abs 的位置改成获取坐标（图标默认是 x(A)），
        // 点击之后会在输入框显示 x()（这是一个整体），默认定位到括号中间，
        // 括号里输入一个点的名字，把这个点的 x 坐标作为数值参与运算，
        // 长按打开 sin 同款菜单，切换 x(A) 和 y(A)。"
        for (mode in modes) {
            val row = rows(KeyPage.OTHER, mode)[1]
            val coord = row[2]
            assertEquals("$mode 的取坐标键标签", COORD_X, coord.label)
            // 短按插一对空括号 + 光标退一格（落在括号中间，和 sin 的 fn 完全一样）
            assertEquals(KeyAction.Insert("x()", cursorBack = 1), coord.action)
            // 配色和变量 / 常量 / 参数键、以及那两个动作键同一档（Accent）
            assertEquals("取坐标键的配色", KeyTone.Accent, coord.tone)
            // 长按的变体：只有 x(A) 和 y(A) 两项，顺序固定（基键在第一格，气泡高亮它）
            assertEquals("$mode 取坐标键的长按变体", listOf(COORD_X, COORD_Y), variantsFor(coord.label).map { it.label })
            assertEquals("气泡默认高亮基键那一格", 0, currentVariantIndex(variantsFor(coord.label), COORD_X))
        }

        // 真机上按一下：插进去的是 "x()"，光标夹在括号之间
        val coord = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1][2]
        val (text, cursor) = editText("", 0, coord.action)
        assertEquals("x()", text)
        assertEquals(2, cursor)
        assertEquals('(', text[cursor - 1])
        assertEquals(')', text[cursor])
        // 接着打点名：`x(A)` —— 应用层会把 A 那个点的横坐标换成数值（见 GraphState）
        val (t2, c2) = editText(text, cursor, KeyAction.Insert("A"))
        assertEquals("x(A)", t2)
        assertEquals(3, c2)
    }

    @Test
    fun `picking y A from the coordinate bubble swaps that key to y`() {
        // 和 sin 的语义一致：**选变体是换键，不是往输入框插东西**。
        val coord = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1][2]
        val selections = applyVariantSelection(emptyMap(), COORD_X, COORD_Y)
        assertEquals(mapOf(COORD_X to COORD_Y), selections)
        val swapped = resolvedKey(coord, selections)
        assertEquals(COORD_Y, swapped.label)
        assertEquals(KeyAction.Insert("y()", cursorBack = 1), swapped.action)
        // 原键没被就地改掉，而且长按仍然认 **基键** —— 所以还能换回 x(A)
        assertEquals(COORD_X, coord.label)
        assertEquals(COORD_X, resolvedKey(coord, applyVariantSelection(selections, COORD_X, COORD_X)).label)

        // 换成 y(A) 之后短按插的是 "y()"、光标同样在括号中间
        val (text, cursor) = editText("", 0, swapped.action)
        assertEquals("y()", text)
        assertEquals(2, cursor)
        assertEquals(')', text[cursor])
        // `y(A)` 自己不是入口（和 asin 一样：只有基键才该有气泡）
        assertTrue("y(A) 不该有变体", variantsFor(COORD_Y).isEmpty())
    }

    @Test
    fun `other page keeps its labels short and uses the symbols for ceil and floor`() {
        // 其他页第 2 行仍然是 4 个键（≈43dp/格），`x(A)`（4 个字符）放得下；
        // 这里只钉住"标签都是短标签"，真正的宽度核算在 MathKeyboard 的 KeyButton 里。
        for (mode in modes) {
            for (key in allKeys(KeyPage.OTHER, mode)) {
                assertTrue("${key.label} 太长", key.label.length <= 5)
            }
        }
        // 取整符号的标签是**符号**，不是函数名 —— 按需求就该这样显示；
        // 第 4 格是用户要求留着的逗号占位
        val row = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1]
        assertEquals("⌈x⌉", row[0].label)
        assertEquals("⌊x⌋", row[1].label)
        assertEquals("x(A)", row[2].label)
        assertEquals(",", row[3].label)
    }

    @Test
    fun `other page does not repeat entry keys or variant members`() {
        // 注意：`x(A)` 现在**既是**其他页上的一颗键、又是长按变体入口
        // （变体族挂在它自己身上，见 variantEntryKeys），
        // 所以这条"别的页的入口不许出现在这里"的守护要把它排除掉 ——
        // 排除是用户本轮要求的结果，不是漏检：它确实该在这一页。
        for (mode in modes) {
            val ls = labels(KeyPage.OTHER, mode)
            for (name in (variantMemberNames + variantEntryKeys) - COORD_X) {
                assertTrue("其他页不该再出现 $name", !ls.contains(name))
            }
            // 反过来说得通：取坐标键就在这一页（3 行里恰好一次）
            assertEquals("$mode 其他页的取坐标键出现次数", 1, ls.count { it == COORD_X })
        }
    }

    @Test
    fun `other page keys insert the calls and symbols they stand for`() {
        val grid = rows(KeyPage.OTHER, PlotMode.EXPLICIT)
        assertEquals("取模运算符", KeyAction.Insert("%"), grid[0][0].action)
        assertEquals(KeyAction.Insert("max()", cursorBack = 1), grid[0][1].action)
        assertEquals(KeyAction.Insert("min()", cursorBack = 1), grid[0][2].action)
        // 向上取整 / 向下取整：标签是符号，插进去的是函数调用
        assertEquals(KeyAction.Insert("ceil()", cursorBack = 1), grid[1][0].action)
        assertEquals(KeyAction.Insert("floor()", cursorBack = 1), grid[1][1].action)
        // 第 3 格是取坐标键：插 `x()`，光标落在括号中间（不是 `x(A)` 那种带示例的文本）
        assertEquals(KeyAction.Insert("x()", cursorBack = 1), grid[1][2].action)
        // 第 4 格是逗号：**用户要求留着的占位键**，语义和位置都没动过
        assertEquals("逗号", KeyAction.Insert(","), grid[1][3].action)
        // 空格键在第 3 行、abc 的左边：它插一个空格（abc 是在它右边新加的入口键）
        assertEquals(KeyAction.Insert(" "), grid[2][0].action)
    }

    @Test
    fun `the space key shares its row with the abc key and inserts exactly one space`() {
        // 这一轮空格键从"横跨整行"变成"占 3/4 行宽"：右边腾出来的 1/4 给了 abc 键
        // （宽度份数见 AlphabetModelTest 里那一节）。
        for (mode in modes) {
            val row = rows(KeyPage.OTHER, mode)[2]
            val space = row[0]
            assertTrue("空格键要有可见标签", space.label.isNotBlank())
            assertEquals("空格键插一个空格", KeyAction.Insert(" "), space.action)
            assertEquals("空格键没有变体", emptyList<KeySpec>(), variantsFor(space.label))
            assertEquals("空格键的宽度份数（3/4 行宽）", 3, space.weight)
            assertEquals("abc 键的宽度份数（1/4 行宽）", 1, row[1].weight)
        }
        // 光标在中间时只在光标处插一个空格（不会吞掉右边那个字符）
        val (text, cursor) = editText("sin(2)", 5, KeyAction.Insert(" "))
        assertEquals("sin(2 )", text)
        assertEquals(6, cursor)
        // 光标在末尾就是追加
        val (t2, c2) = editText("sin(2)", 6, KeyAction.Insert(" "))
        assertEquals("sin(2) ", t2)
        assertEquals(7, c2)
    }

    @Test
    fun `the angle unit key toggles the unit instead of inserting text`() {
        for (mode in modes) {
            val angle = rows(KeyPage.OTHER, mode)[0][3]
            assertEquals(
                "$mode 的角度制键必须是 ToggleAngleUnit",
                KeyAction.ToggleAngleUnit,
                angle.action
            )
            // 它没有长按变体（长按气泡只服务于函数族）
            assertTrue("角度制键不该有变体", variantsFor(angle.label).isEmpty())
            assertEquals("角度制键用 Accent 档，和插入键区分开", KeyTone.Accent, angle.tone)
            // **它不改文本、不改光标**：editText 收到这个动作时原样返回
            assertEquals("sin(2)" to 5, editText("sin(2)", 5, KeyAction.ToggleAngleUnit))
            assertEquals("" to 0, editText("", 0, KeyAction.ToggleAngleUnit))
            // 超范围的光标也只是被夹紧，不会因为"按了角度制"而乱跳
            assertEquals("xy" to 2, editText("xy", 99, KeyAction.ToggleAngleUnit))
        }
    }

    // ---- 插入语义 ----

    @Test
    fun `function keys insert empty parentheses with the caret inside`() {
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    val action = key.action
                    if (action is KeyAction.Insert && action.text.endsWith("()")) {
                        // 标签不一定等于函数名（`⌈x⌉` 插的是 `ceil()`），所以按动作反推
                        val name = action.text.dropLast(2)
                        assertTrue(
                            "$mode / $page 的 ${key.label} 插的函数名 '$name' 必须是字母",
                            name.isNotEmpty() && name.all { it.isLetter() }
                        )
                        assertEquals(
                            "$mode / $page 的 ${key.label} 光标要落在括号内",
                            1,
                            action.cursorBack
                        )
                    }
                }
            }
        }
        // 标签是符号、插的是函数名的那几颗键单独钉住
        // （第 3 格本轮从 `abs` 换成取坐标键，插的是 `x()`）
        val other = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1]
        assertEquals(KeyAction.Insert("ceil()", cursorBack = 1), other[0].action)
        assertEquals(KeyAction.Insert("floor()", cursorBack = 1), other[1].action)
        assertEquals(KeyAction.Insert("x()", cursorBack = 1), other[2].action)
    }

    @Test
    fun `log key pre-fills both arguments and leaves the caret on the base`() {
        // 真机需求：本引擎的 log 是两参数的 log(底数, 真数)，
        // 键盘又**没有光标左右移动键**，所以一次把 "log(,)" 都插好、
        // 光标退到第一个参数的位置上。
        //
        // cursorBack 是 `editText` 的公式 `光标 = 插入点 + 长度 − cursorBack` 里的那个数：
        // "log(,)" 长度 6，要落在下标 4（`(` 之后、`,` 之前）就得是 **2**。
        // 需求原文写的是 3 —— 那是"g 之后、`(` 之前"（下标 3），会落在整个调用外面，
        // 用户打出来的底数会变成 "log2(,)"。所以这里按同一个需求里的
        // "( 之后、, 之前"、"光标在第 4 个字符处" 取 2（见 logKey 的说明）。
        val log = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "log" }
        assertEquals(KeyAction.Insert("log(,)", cursorBack = 2), log.action)

        val (text, cursor) = editText("", 0, log.action)
        assertEquals("log(,)", text)
        // "log(,)"：l0 o1 g2 (3 ,4 )5 —— 光标在下标 4，
        // 也就是 `(` 之后、`,` 之前 = 第一个参数的位置
        assertEquals("光标落在第一个参数的位置", 4, cursor)
        assertEquals('(', text[cursor - 1])
        assertEquals(',', text[cursor])
        assertEquals(")", text.substring(cursor + 1))

        // 先填底数：2 落在光标处，`,` 和 `)` 已经在后面等着
        val (t2, c2) = editText(text, cursor, KeyAction.Insert("2"))
        assertEquals("log(2,)", t2)
        assertEquals(5, c2)
        assertEquals(',', t2[c2])
    }

    @Test
    fun `typing a digit stays in the base slot instead of jumping over the comma`() {
        // 光标停在 `,` 之前，所以接着打一个数字它仍然落在逗号前面 —— 这是**故意的**：
        // 底数可能是多位数（`log(10,100)`），"看到逗号就自动跳过"会把 10 拆坏。
        // 想走到真数槽位按一下逗号键，见下一条测试。
        val (text, cursor) = editText("log(2,)", 5, KeyAction.Insert("8"))
        assertEquals("log(28,)", text)
        assertEquals(6, cursor)
    }

    @Test
    fun `the comma key skips the template comma into the argument slot`() {
        // 3 行重排之后键盘上**没有光标左右移键**（编辑行被撤掉了），
        // 所以"逗号键 = 跳到下一个槽位"是填完 log(,) 的唯一走法。
        val (t1, c1) = editText("log(2,)", 5, KeyAction.Insert(","))
        assertEquals("文本不该变（没有插进第二个逗号）", "log(2,)", t1)
        assertEquals("光标要跨过模板那个逗号", 6, c1)
        // 跨过去之后打真数，就落在第二个参数里
        val (t2, c2) = editText(t1, c1, KeyAction.Insert("8"))
        assertEquals("log(2,8)", t2)
        assertEquals(7, c2)

        // 底数是多位数也一样：log(10,100)
        var state = editText("", 0, KeyAction.Insert("log(,)", cursorBack = 2))
        for (ch in "10") state = editText(state.first, state.second, KeyAction.Insert(ch.toString()))
        state = editText(state.first, state.second, KeyAction.Insert(","))
        for (ch in "100") state = editText(state.first, state.second, KeyAction.Insert(ch.toString()))
        assertEquals("log(10,100)" to 10, state)
    }

    @Test
    fun `the comma key still inserts normally when the caret is not before a comma`() {
        // 光标在末尾 / 在 `)` 之前时，逗号就是普通插入 —— 跳槽只在"正对着模板逗号"时发生
        assertEquals("1,2" to 2, editText("12", 1, KeyAction.Insert(",")))
        assertEquals("f(1,2,)" to 6, editText("f(1,2)", 5, KeyAction.Insert(",")))
    }

    @Test
    fun `bar key inserts a pair of bars with the caret in the middle`() {
        for (mode in modes) {
            for (page in pages) {
                val bar = allKeys(page, mode).firstOrNull { it.label == "||" }
                if (bar != null) {
                    assertEquals(KeyAction.Insert("||", cursorBack = 1), bar.action)
                }
            }
        }
    }

    @Test
    fun `no key offers a single bar any more`() {
        // 真机需求：绝对值键是 `||`（插一对、光标夹在中间），不该再有单个 `|` 的键
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    assertTrue("$mode / $page 不该再出现单个 | 的键", key.label != "|")
                    val action = key.action
                    if (action is KeyAction.Insert) {
                        assertTrue(
                            "$mode / $page 的 ${key.label} 不该只插一根竖线",
                            action.text != "|"
                        )
                    }
                }
            }
        }
        // 而 `||` 键确实在，而且插的就是一对带光标进位的形式
        assertEquals(KeyAction.Insert("||", cursorBack = 1), barKey().action)
    }

    @Test
    fun `root keys insert just the root sign`() {
        // √ 和 ∛ 是符号键：插进去的就是符号，括号由用户自己按
        val root = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "√" }
        assertEquals(KeyAction.Insert("√"), root.action)
        val cbrt = variantsFor("√").first { it.label == "∛" }
        assertEquals(KeyAction.Insert("∛"), cbrt.action)
        assertEquals(0, (cbrt.action as KeyAction.Insert).cursorBack)
    }

    // ---- 长按变体 ----

    @Test
    fun `trig keys offer exactly four variants in the calculator order`() {
        // 顺序不能变：原函数 / 反函数 / 双曲 / 反双曲 —— 手指记住的是位置
        val expected = mapOf(
            "sin" to listOf("sin", "asin", "sinh", "asinh"),
            "cos" to listOf("cos", "acos", "cosh", "acosh"),
            "tan" to listOf("tan", "atan", "tanh", "atanh")
        )
        for ((key, names) in expected) {
            val variants = variantsFor(key)
            assertEquals("长按 $key 的变体", names, variants.map { it.label })
        }
    }

    @Test
    fun `the log family is log ln lg with log first`() {
        // log 是网格上的基键，所以它必须排在第一个（气泡高亮的就是第一格）；
        // ln / lg 只能从这里长按出来 —— 网格上已经没有它们的格子了。
        val expected = listOf("log", "ln", "lg")
        assertEquals(expected, variantsFor("log").map { it.label })
        // 老状态里以 ln 为基键的选择表仍然认得这一族
        assertEquals(expected, variantsFor("ln").map { it.label })
        assertEquals(variantsFor("ln"), variantsFor("log"))
        // 默认显示的就是 log：基键在族里的下标是 0
        assertEquals(0, currentVariantIndex(variantsFor("log"), "log"))
        // ln / lg 是一元函数，插的是 name() + 光标进括号
        for (label in listOf("ln", "lg")) {
            val variant = variantsFor("log").first { it.label == label }
            assertEquals(KeyAction.Insert("$label()", cursorBack = 1), variant.action)
        }
    }

    @Test
    fun `the root key offers square root and cube root`() {
        assertEquals(listOf("√", "∛"), variantsFor("√").map { it.label })
    }

    @Test
    fun `the abs key no longer has a variant family`() {
        // abs 现在**不在网格上**了（本轮用户要求把其他页那颗 `abs` 撤掉，
        // 绝对值入口是初等页的 `||`），但"没有变体族"这条结论仍然要钉住：
        // floor / ceil 在其他页各有一个符号键（⌊x⌋ / ⌈x⌉），
        // 再挂一份变体就是重复入口。
        assertTrue("abs 不该再有变体", variantsFor("abs").isEmpty())
        for (name in listOf("sign", "floor", "ceil")) {
            assertTrue("$name 不该是任何键的变体", variantsFor(name).isEmpty())
        }
    }

    @Test
    fun `a stale abs selection falls back to the abs key`() {
        // 老状态里可能还留着 "abs" → "floor"（变体族取消之前选的）：
        // 认不出来的选择必须退回基键本身，不能变成空白、也不能留下一个 floor 键。
        //
        // 注意这里**不能**再从网格里取那颗 abs 键了：本轮用户要求把其他页的 `abs`
        // 撤掉之后网格上已经没有它。所以手写一个同形的键来验兜底逻辑 ——
        // 这条逻辑和网格里有没有 abs 无关，是 resolvedKey 的性质。
        val abs = KeySpec("abs", KeyAction.Insert("abs()", cursorBack = 1))
        assertEquals(abs, resolvedKey(abs, mapOf("abs" to "floor")))
        assertEquals(KeyAction.Insert("abs()", cursorBack = 1), abs.action)
    }

    @Test
    fun `every variant of the function families inserts empty parentheses with the caret inside`() {
        // 三角函数那几族全是函数名，插进去的是 name()、光标落在括号里；
        // √ / ∛ 那一族是符号键，单独在 root keys insert just the root sign 里测；
        // 对数族里的 log 是**两参数**写法，单独在下面那一段测。
        val families = listOf("sin", "cos", "tan")
        for (label in families) {
            for (variant in variantsFor(label)) {
                val action = variant.action
                assertTrue("$label 的变体 ${variant.label} 必须是插入动作", action is KeyAction.Insert)
                action as KeyAction.Insert
                assertEquals("${variant.label} 的插入文本", "${variant.label}()", action.text)
                assertEquals("${variant.label} 的光标要落在括号内", 1, action.cursorBack)
                assertEquals(
                    "变体 ${variant.label} 的配色要和初等函数键一致",
                    KeyTone.Normal,
                    variant.tone
                )
            }
        }
        // 对数族：log 用两参数写法（光标落在第一个参数），ln / lg 是普通一元调用
        for (variant in variantsFor("log")) {
            assertEquals("变体 ${variant.label} 的配色", KeyTone.Normal, variant.tone)
            val action = variant.action
            assertTrue("变体 ${variant.label} 必须是插入动作", action is KeyAction.Insert)
            action as KeyAction.Insert
            if (variant.label == "log") {
                assertEquals("log(,)", action.text)
                assertEquals(2, action.cursorBack)
            } else {
                assertEquals("${variant.label}()", action.text)
                assertEquals(1, action.cursorBack)
            }
        }
    }

    @Test
    fun `keys without variants return an empty list`() {
        for (label in listOf(
            "7", "0", "+", "−", "×", "÷", "^", "(", ")", ".", "||", "=", "π", "e",
            "x", "t", "abs", "%", ",", "⌈x⌉", "⌊x⌋", "max", "min", "空格", "RAD", "DEG"
        )) {
            assertTrue("$label 不该有变体", variantsFor(label).isEmpty())
        }
        // 组里的非基键不是入口：asin 自己不给变体，只能从 sin 长按选出来
        for (label in listOf(
            "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "exp", "lg",
            "∛", "sign", "floor", "ceil", "", COORD_Y
        )) {
            assertTrue("$label 不该有变体", variantsFor(label).isEmpty())
        }
    }

    @Test
    fun `no key label on the grid is longer than five characters`() {
        // 字号护栏：按键统一用 fontSmall（12.12sp）。
        // 3 行布局下的三档键宽（233dp 屏、gap 1.86dp，见 ui/MathKeyboard.kt 的 KeyButton）：
        //   第 1 行 ≈50.9dp / 第 2 行 ≈43.0dp / 第 3 行 ≈23.7dp（被圆屏收口卡住）。
        // 5 个字符（asinh ≈2.7em ≈32.8dp）只有在第 1、2 行放得下，
        // 所以第 3 行只放短标签 —— 下面单独钉住那一条。
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    assertTrue(
                        "$mode / $page 的 ${key.label} 太长（超过 5 个字符），在当前字号下会被按键裁掉",
                        key.label.length <= 5
                    )
                }
            }
        }
    }

    @Test
    fun `the narrow last row only carries short labels`() {
        // 第 3 行的键宽只有 ≈23.7dp（圆屏收口），1~2 个字符是它的预算。
        // 这一轮把初等页的 `sin cos tan log` 从第 3 行换到了最宽的第 1 行
        // （见 elementary page puts the long labels on the widest row…）：
        // 现在坐在第 3 行上的是 `+ − × ÷` 这种**单字符**键，
        // 另外几页第 3 行是数字、`( ) , ∞` 和「空格」（2 字符）。
        //
        // 长按把键换成变体名（asinh 之类 5 个字符）之后键上会写 5 个字符 ——
        // 那在第 3 行一定溢出，MathKeyboard 的 KeyButton 用 TextOverflow.Ellipsis 收尾
        // （见那边的核算）；对调之后初等页的长标签不再受这一条约束。
        for (mode in modes) {
            for (page in pages) {
                rows(page, mode)[2].forEach { key ->
                    assertTrue(
                        "$mode / $page 第 3 行（最窄的一行）的 ${key.label} 超过 3 个字符",
                        key.label.length <= 3
                    )
                }
            }
        }
    }

    @Test
    fun `only the entry keys of the grid have variants`() {
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    val hasVariants = variantsFor(key.label).isNotEmpty()
                    assertEquals(
                        "$mode / $page 的 ${key.label} 该不该有变体",
                        key.label in variantEntryKeys,
                        hasVariants
                    )
                }
            }
        }
    }

    @Test
    fun `current variant index highlights the key that was pressed`() {
        // 长按 sin 且它还没被换过时高亮 sin，换成 asinh 之后再长按就高亮 asinh
        assertEquals(0, currentVariantIndex(variantsFor("sin"), "sin"))
        assertEquals(1, currentVariantIndex(variantsFor("sin"), "asin"))
        assertEquals(3, currentVariantIndex(variantsFor("sin"), "asinh"))
        // 对数族：log 在第一个，所以基键 log 高亮第 0 格
        assertEquals(0, currentVariantIndex(variantsFor("log"), "log"))
        assertEquals(1, currentVariantIndex(variantsFor("log"), "ln"))
        assertEquals(2, currentVariantIndex(variantsFor("log"), "lg"))
        assertEquals(1, currentVariantIndex(variantsFor("√"), "∛"))
        assertEquals(3, currentVariantIndex(variantsFor("tan"), "atanh"))
        // 标签在组里找不到时退回第一个而不是崩掉
        assertEquals(0, currentVariantIndex(variantsFor("sin"), "cos"))
        assertEquals(0, currentVariantIndex(emptyList(), "sin"))
        // abs 的族取消之后：任何标签都退到第一个，而不是崩
        assertEquals(0, currentVariantIndex(variantsFor("abs"), "floor"))
    }

    // ---- 变体是"换键"，不是"插入" ----

    @Test
    fun `a key without a selection is itself`() {
        val sin = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "sin" }
        assertEquals(sin, resolvedKey(sin, emptyMap()))
        for (mode in modes) {
            for (page in pages) {
                val selections = emptyMap<String, String>()
                for (key in allKeys(page, mode)) {
                    assertEquals("$mode / $page 的 ${key.label}", key, resolvedKey(key, selections))
                }
            }
        }
    }

    @Test
    fun `picking a variant swaps the content and the insert text of that key`() {
        val sin = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "sin" }
        val selections = applyVariantSelection(emptyMap(), "sin", "asinh")
        val swapped = resolvedKey(sin, selections)
        assertEquals("asinh", swapped.label)
        assertEquals(KeyAction.Insert("asinh()", cursorBack = 1), swapped.action)
        // 原来的那个键（基键）不能被就地改掉：它还是 sin
        assertEquals("sin", sin.label)

        // 真机上的典型操作：把 sin 换成 asinh 之后按它，插进去的就是 asinh()
        val (text, cursor) = editText("", 0, swapped.action)
        assertEquals("asinh()", text)
        // "asinh()" 有 7 个字符，退一格之后光标落在右括号前面
        assertEquals(6, cursor)
        assertEquals(")", text.substring(cursor))
    }

    @Test
    fun `picking ln or lg from the log bubble swaps that key too`() {
        // 网格上没有 ln / lg 的格子了，它们只能从 log 长按长出来；
        // 选完之后那个键显示并插入的就是 ln() / lg()
        val log = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "log" }
        val selections = applyVariantSelection(emptyMap(), "log", "lg")
        val swapped = resolvedKey(log, selections)
        assertEquals("lg", swapped.label)
        assertEquals(KeyAction.Insert("lg()", cursorBack = 1), swapped.action)
        // 选回 log 就回到两参数写法
        val back = resolvedKey(log, applyVariantSelection(selections, "log", "log"))
        assertEquals(log, back)
        assertEquals(KeyAction.Insert("log(,)", cursorBack = 2), back.action)
    }

    @Test
    fun `selecting the base name back puts the key back to its default`() {
        var selections = applyVariantSelection(emptyMap(), "sin", "asinh")
        assertEquals(mapOf("sin" to "asinh"), selections)
        // 长按它再选回 sin：选择表里那一项直接删掉，而不是留一条 "sin" → "sin"
        selections = applyVariantSelection(selections, "sin", "sin")
        assertTrue(selections.isEmpty())
        val sin = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "sin" }
        assertEquals(sin, resolvedKey(sin, selections))
    }

    @Test
    fun `each key keeps its own selection`() {
        var selections = applyVariantSelection(emptyMap(), "sin", "asin")
        selections = applyVariantSelection(selections, "cos", "acosh")
        val grid = rows(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).flatten()
        assertEquals("asin", resolvedKey(grid.first { it.label == "sin" }, selections).label)
        assertEquals("acosh", resolvedKey(grid.first { it.label == "cos" }, selections).label)
        // 没被动过的键一概不受影响
        assertEquals("tan", resolvedKey(grid.first { it.label == "tan" }, selections).label)
        assertEquals("√", resolvedKey(grid.first { it.label == "√" }, selections).label)
    }

    @Test
    fun `a stale selection falls back to the base key instead of going blank`() {
        val cos = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "cos" }
        // 认不出来的名字（比如以后改了变体表、或者老状态里留下的）
        assertEquals(cos, resolvedKey(cos, mapOf("cos" to "coth")))
        // 基键本身就没有变体组
        val seven = allKeys(KeyPage.DIGITS, PlotMode.EXPLICIT).first { it.label == "7" }
        assertEquals(seven, resolvedKey(seven, mapOf("7" to "8")))
    }

    @Test
    fun `inserting the old variant action still lands the caret inside its own parentheses`() {
        // editText 的语义没变：光标落在插入文本的括号里，右边原有的 ")" 还留着
        val asinh = variantsFor("sin").first { it.label == "asinh" }
        val (text, cursor) = editText("sin()", 4, asinh.action)
        assertEquals("sin(asinh())", text)
        assertEquals(10, cursor)
        assertEquals("))", text.substring(cursor))
        assertEquals(')', text[cursor])
    }

    // ---- editText：插入 ----

    @Test
    fun `insert in the middle keeps the cursor right after the insert`() {
        val (text, cursor) = editText("12", 1, KeyAction.Insert("+"))
        assertEquals("1+2", text)
        assertEquals(2, cursor)
    }

    @Test
    fun `cursorBack puts the caret inside the parentheses`() {
        val (text, cursor) = editText("", 0, KeyAction.Insert("sin()", cursorBack = 1))
        assertEquals("sin()", text)
        assertEquals(4, cursor)
        assertEquals(")", text.substring(cursor))
    }

    @Test
    fun `insert at the very end appends`() {
        val (text, cursor) = editText("x^2", 3, KeyAction.Insert("+1"))
        assertEquals("x^2+1", text)
        assertEquals(5, cursor)
    }

    @Test
    fun `insert into the middle of an expression`() {
        val (text, cursor) = editText("x^2+1", 3, KeyAction.Insert("3"))
        assertEquals("x^23+1", text)
        assertEquals(4, cursor)
    }

    /** 用键盘上的 sin 键真的打一遍 "sin()" 再把光标移进去，模拟最典型的真机操作。 */
    @Test
    fun `typing sin then a digit lands inside the brackets`() {
        val sin = allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == "sin" }
        val (t1, c1) = editText("", 0, sin.action)
        assertEquals("sin()", t1)

        val (t2, c2) = editText(t1, c1, KeyAction.Insert("2"))
        assertEquals("sin(2)", t2)
        assertEquals(5, c2)
    }

    // ---- editText：退格 ----

    @Test
    fun `backspace at the beginning does nothing`() {
        val (text, cursor) = editText("x", 0, KeyAction.Backspace)
        assertEquals("x", text)
        assertEquals(0, cursor)
    }

    @Test
    fun `backspace in the middle removes the char before the cursor`() {
        // 注意这里**不能**再用 "sin()"：光标落在括号之间时，退格现在是
        // "把整个 sin() 删掉"（见 backspace inside an empty call deletes the whole call）。
        // 所以逐字符删除用普通表达式来测。
        val (text, cursor) = editText("12+3", 3, KeyAction.Backspace)
        assertEquals("123", text)
        assertEquals(2, cursor)
    }

    @Test
    fun `backspace at the end removes the last char`() {
        val (text, cursor) = editText("x+", 2, KeyAction.Backspace)
        assertEquals("x", text)
        assertEquals(1, cursor)
    }

    @Test
    fun `backspace on empty text is a no-op`() {
        val (text, cursor) = editText("", 0, KeyAction.Backspace)
        assertEquals("", text)
        assertEquals(0, cursor)
    }

    @Test
    fun `clear empties the text and resets the cursor`() {
        // 键盘上已经没有 C 键了，但 long press ⌫ 送进来的就是 Clear，语义必须不变
        val (text, cursor) = editText("cos(2θ)", 3, KeyAction.Clear)
        assertEquals("", text)
        assertEquals(0, cursor)
    }

    // ---- 退格：整体删掉一个函数调用 ----

    /** 键盘上 `sin` 键插的就是 `"sin()"`、光标落在括号之间（见 fn）。 */
    private fun elementaryKey(label: String): KeySpec =
        allKeys(KeyPage.ELEMENTARY, PlotMode.EXPLICIT).first { it.label == label }

    @Test
    fun `backspace inside an empty call deletes the whole call`() {
        // 真机反馈：插入 sin() 之后按退格，应该把 sin() 整体删掉。
        // 刚插完 sin() 时光标就在括号之间（cursorBack = 1），所以这是最常走的那条路。
        val sin = elementaryKey("sin")
        val (t1, c1) = editText("", 0, sin.action)
        assertEquals("sin()", t1)
        assertEquals(4, c1)
        assertEquals("" to 0, editText(t1, c1, KeyAction.Backspace))

        // 式子中间的调用也一样整体删掉，两边的字符原封不动
        assertEquals("x+" to 2, editText("x+sin()", 6, KeyAction.Backspace))
        assertEquals("1+2" to 3, editText("1+2sin()", 7, KeyAction.Backspace))
    }

    @Test
    fun `backspace right after a filled call deletes the whole call`() {
        // 用户自己按了右括号、光标停在 `)` 之后 —— 同样整体删
        assertEquals("" to 0, editText("sin()", 5, KeyAction.Backspace))
        assertEquals("1+" to 2, editText("1+sin()", 7, KeyAction.Backspace))
        assertEquals("sin(2)+" to 7, editText("sin(2)+cos()", 12, KeyAction.Backspace))
        // 多参数调用也算（abs / max / log 都是函数表里的名字）
        assertEquals("" to 0, editText("abs()", 5, KeyAction.Backspace))
        assertEquals("mod(2,3)+" to 9, editText("mod(2,3)+abs()", 14, KeyAction.Backspace))
    }

    @Test
    fun `longer function names are deleted as a whole not as their suffix`() {
        // asin 里含 sin：必须整体删 asin()，不能先撞上 sin 只删掉后半截
        assertEquals("" to 0, editText("asin()", 6, KeyAction.Backspace))
        assertEquals("" to 0, editText("asin()", 5, KeyAction.Backspace))
        assertEquals("" to 0, editText("asinh()", 7, KeyAction.Backspace))
        assertEquals("" to 0, editText("acosh()", 7, KeyAction.Backspace))
        assertEquals("" to 0, editText("floor()", 7, KeyAction.Backspace))
        assertEquals("" to 0, editText("sqrt()", 6, KeyAction.Backspace))
        // 光标在括号里（空调用）时也一样
        assertEquals("" to 0, editText("asin()", 5, KeyAction.Backspace))
        assertEquals("2*" to 2, editText("2*floor()", 9, KeyAction.Backspace))
    }

    @Test
    fun `a call with arguments inside is still deleted one character at a time`() {
        // 只有"完整的 name()"和"空的 name() 中间"才整体删：
        // 括号里已经有内容时，逐字符删（用户改的是参数，不是整个调用）
        assertEquals("sin()" to 4, editText("sin(2)", 5, KeyAction.Backspace))
        assertEquals("sin(2" to 5, editText("sin(2)", 6, KeyAction.Backspace))
        assertEquals("log(,)" to 4, editText("log(2,)", 5, KeyAction.Backspace))
    }

    @Test
    fun `a bare function name without parentheses is deleted one character at a time`() {
        // 需求里的"其它情况保持现在的行为"：光秃秃的 sin 逐字符删
        assertEquals("si" to 2, editText("sin", 3, KeyAction.Backspace))
        assertEquals("sin" to 3, editText("sin(", 4, KeyAction.Backspace))
        assertEquals("sin(" to 4, editText("sin(2", 5, KeyAction.Backspace))
        assertEquals("x+si" to 4, editText("x+sin", 5, KeyAction.Backspace))
    }

    @Test
    fun `variables and constants are deleted one character at a time`() {
        // 规则 3：光标左边是字母但不是已知函数名（变量 x / θ、常量 π）→ 只删一个字符
        assertEquals("" to 0, editText("x", 1, KeyAction.Backspace))
        assertEquals("" to 0, editText("θ", 1, KeyAction.Backspace))
        assertEquals("" to 0, editText("π", 1, KeyAction.Backspace))
        assertEquals("x" to 1, editText("xy", 2, KeyAction.Backspace))
        assertEquals("π+" to 2, editText("π+x", 3, KeyAction.Backspace))
        assertEquals("sin(2)" to 5, editText("sin(2θ)", 6, KeyAction.Backspace))
    }

    @Test
    fun `the root sign is a symbol and is deleted one character at a time`() {
        // √ / ∛ 是符号键：它们不在函数名表里，退格就是普通的删一个字符
        val root = elementaryKey("√")
        val (t1, c1) = editText("", 0, root.action)
        assertEquals("√", t1)
        assertEquals(1, c1)
        assertEquals("" to 0, editText(t1, c1, KeyAction.Backspace))
        assertEquals("√" to 1, editText("√2", 2, KeyAction.Backspace))
        assertEquals("2*√" to 3, editText("2*√2", 4, KeyAction.Backspace))
    }

    @Test
    fun `a name glued to a variable is not deleted as a whole`() {
        // "xsin()" 这种写法（隐式乘法）左边挨着字母，边界判定按保守处理：
        // 只删一个字符 —— 宁可多按几次退格，也不要把用户没打算删的字母吃掉
        assertEquals("xsin(" to 5, editText("xsin()", 6, KeyAction.Backspace))
        // 数字不是标识符的一部分："2sin()" 里的 sin() 照样整体删
        assertEquals("2" to 1, editText("2sin()", 6, KeyAction.Backspace))
    }

    @Test
    fun `every function name the grid can type is in the delete list`() {
        // 退格"整体删除"靠的是 KeyboardModel 里那份函数名清单（见那里的说明）。
        // 这条测试盯着它别和键盘漂移：**键盘上能打出来的函数名必须都在清单里**。
        val typed = LinkedHashSet<String>()
        for (mode in modes) {
            for (page in pages) {
                for (key in allKeys(page, mode)) {
                    val action = key.action
                    if (action is KeyAction.Insert && action.text.endsWith("()")) {
                        typed += action.text.dropLast(2)
                    }
                }
            }
        }
        // 长按变体里也有函数名（asinh / ln / lg …）
        for (entry in variantEntryKeys) {
            for (variant in variantsFor(entry)) {
                val action = variant.action
                if (action is KeyAction.Insert && action.text.endsWith("()")) {
                    typed += action.text.dropLast(2)
                }
            }
        }
        // ⌈x⌉ / ⌊x⌋ 标签是符号、插的是 ceil() / floor()
        typed += listOf("ceil", "floor")

        assertTrue("至少要能打出 sin", typed.contains("sin"))
        for (name in typed) {
            // **取坐标键是例外**：它插的是 `x()` / `y()`，但 `x` / `y` 不是数学函数名
            // （它们是采样变量），所以它们走的不是 FUNCTION_NAMES 那张表，
            // 而是另一张表（KeyboardModel 的 COORDINATE_CALL_NAMES）—— 见下面单独那一段。
            if (isCoordinateCallName(name)) continue
            assertTrue("键盘能打 $name()，退格就该能整体删掉它", isKnownFunctionName(name))
        }
        // 反过来也要说得通：符号键、变量、以及字母页那四个**预留参数**都不是函数名。
        // 特别注意 `a` `b` `c` `d`：它们是参数 / 变量（插同名文本、退格逐字符删），
        // **不能**混进 [FUNCTION_NAMES] 那份清单 —— 否则退格会去整体删 `a()`。
        // `x` / `y` 也一样不在函数名清单里（它们只在"调用式变量"那张表里）。
        for (notFn in listOf(
            "x", "y", "t", "θ", "π", "√", "∛", "", "空格", "%", "a", "b", "c", "d"
        )) {
            assertTrue("$notFn 不是函数名", !isKnownFunctionName(notFn))
        }
        // 而 `x` / `y` 确实被登记成"调用式变量"（退格整体删 `x()` / `y()` 靠它）
        for (coord in listOf("x", "y")) {
            assertTrue("$coord 该是调用式变量名", isCoordinateCallName(coord))
            assertTrue("$coord 不该是函数名", !isKnownFunctionName(coord))
        }
        // 函数名一个都不该混进"调用式变量"那张表（防止有人图省事把两张表并起来）
        for (fn in listOf("sin", "abs", "log", "floor", "a", "π", "t")) {
            assertTrue("$fn 不该是调用式变量名", !isCoordinateCallName(fn))
        }
        assertTrue(isKnownFunctionName("asin"))
        assertTrue(isKnownFunctionName("log"))
        assertTrue(isKnownFunctionName("clamp"))
    }

    // ---- 退格：取坐标键的 x() / y() 也是一个整体 ----

    @Test
    fun `backspace deletes a coordinate call as a whole`() {
        // 用户需求原话："点击之后会在输入框显示 x()（这是一个整体）"。
        // 所以按一下取坐标键得到的 `x()` 必须能一次退掉 —— 和 `sin()` 同款两条规则：
        //   规则 2：光标在空括号**中间**（刚按完这个键就是这个状态）→ 连名字带括号一起删；
        //   规则 1：光标正好在 `)` **之后**（用户自己按了右括号）→ 整个调用一起删。
        val coord = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1][2]

        // 从键盘真的按一下，再从那个光标位置退格
        val (t1, c1) = editText("", 0, coord.action)
        assertEquals("x()", t1)
        assertEquals(2, c1)
        assertEquals("" to 0, editText(t1, c1, KeyAction.Backspace))

        // 手写的等价状态：光标在 x(|)
        assertEquals("" to 0, editText("x()", 2, KeyAction.Backspace))
        // 光标在 x() 后面
        assertEquals("" to 0, editText("x()", 3, KeyAction.Backspace))
        // 式子中间：只吃掉整个调用，两边原封不动
        assertEquals("2+" to 2, editText("2+x()", 5, KeyAction.Backspace))
        assertEquals("2+" to 2, editText("2+x()", 4, KeyAction.Backspace))
        // 套在别的调用里也一样：`sin(x())` 末尾退格删掉里面的 `x()`，
        // 外层那对括号原封不动，于是剩下一个空的 `sin()`
        assertEquals("sin()" to 4, editText("sin(x())", 7, KeyAction.Backspace))
    }

    @Test
    fun `backspace deletes a y call as a whole too`() {
        // 长按换成 y(A) 之后短按插的是 `y()` —— 整体删除的行为必须一模一样
        val coord = rows(KeyPage.OTHER, PlotMode.EXPLICIT)[1][2]
        val swapped = resolvedKey(coord, applyVariantSelection(emptyMap(), COORD_X, COORD_Y))
        val (t1, c1) = editText("", 0, swapped.action)
        assertEquals("y()", t1)
        assertEquals(2, c1)
        assertEquals("" to 0, editText(t1, c1, KeyAction.Backspace))
        assertEquals("" to 0, editText("y()", 3, KeyAction.Backspace))
        assertEquals("2*" to 2, editText("2*y()", 5, KeyAction.Backspace))
    }

    @Test
    fun `a bare x or y is still deleted one character at a time`() {
        // **这条是本次改动最要紧的护栏**：`x` / `y` 不是数学函数名，
        // 它们被登记成"调用式变量"只是为了让 `x()` 能整体删，
        // **不能**因此让光秃秃的 `x` 也变成整体删除对象
        // （那会毁掉 `2x` 这种最普通的隐式乘法输入）。
        assertEquals("" to 0, editText("x", 1, KeyAction.Backspace))
        assertEquals("2" to 1, editText("2x", 2, KeyAction.Backspace))
        assertEquals("2" to 1, editText("2y", 2, KeyAction.Backspace))
        assertEquals("x+" to 2, editText("x+y", 3, KeyAction.Backspace))
        assertEquals("xy" to 2, editText("xyz", 3, KeyAction.Backspace))
        // 光有左括号也不算调用：`x(` 末尾退格只吃掉括号（和旧行为一致）
        assertEquals("x" to 1, editText("x(", 2, KeyAction.Backspace))
        assertEquals("2x" to 2, editText("2x(", 3, KeyAction.Backspace))
        // `ax()` 里那个 `x` 左边是字母 → 边界规则挡掉，保守地逐字符删
        assertEquals("ax(" to 3, editText("ax()", 4, KeyAction.Backspace))
    }

    @Test
    fun `the x inside sin does not change how backspace works`() {
        // 需求明确要求：`sin(x)` 里的 `x` 不受影响。
        // 括号里有内容时本来就**不**整体删（见 deleteWholeCallBefore 的说明），
        // 所以在 `sin(x)` 里退格永远是逐字符删：
        //   光标 5（x 和 ) 之间）→ 只吃掉 `x`，外层那对括号留着 → "sin()"
        assertEquals("sin()" to 4, editText("sin(x)", 5, KeyAction.Backspace))
        //   光标 6（末尾，) 之后）→ 只吃掉 `)` → "sin(x"
        assertEquals("sin(x" to 5, editText("sin(x)", 6, KeyAction.Backspace))
        //   光标 4（( 和 x 之间）→ 只吃掉 `(` → "sinx)"
        assertEquals("sinx)" to 3, editText("sin(x)", 4, KeyAction.Backspace))
        assertEquals("sin(y" to 5, editText("sin(y)", 6, KeyAction.Backspace))
        // `sin(x())` 里那个 `x()` 仍然是整体删（本次新加的行为只多不少）；
        // 里面的 `x()` 没了之后外层剩一个空的 `sin()`，还能再整体删一次
        assertEquals("sin()" to 4, editText("sin(x())", 7, KeyAction.Backspace))
        // 把括号里清空之后才轮到整体删 sin()（旧行为不变）
        assertEquals("" to 0, editText("sin()", 4, KeyAction.Backspace))
    }

    // ---- 绝对值符号成对删除 ----

    /** 键盘上 `||` 键插入的就是 `"||"`，光标落在两根竖线中间（见 absBar）。 */
    private fun barKey() = elementaryKey("||")

    @Test
    fun `backspace between a pair of bars removes both`() {
        // 真机反馈：输入 | 之后（键盘插的是 "||"、光标在两根竖线中间），
        // 删掉左半边时右半边要自动一起删掉。
        val (text, cursor) = editText("||", 1, KeyAction.Backspace)
        assertEquals("", text)
        assertEquals(0, cursor)
    }

    @Test
    fun `typing the bar key then backspace leaves nothing behind`() {
        val bar = barKey()
        val (t1, c1) = editText("", 0, bar.action)
        assertEquals("||", t1)
        assertEquals(1, c1)

        val (t2, c2) = editText(t1, c1, KeyAction.Backspace)
        assertEquals("", t2)
        assertEquals(0, c2)
    }

    @Test
    fun `backspace between the bars of sin(||) removes both and keeps the cursor at the gap`() {
        // "sin(||)"：s0 i1 n2 (3 |4 |5 )6，光标 5 夹在两根竖线之间
        val (text, cursor) = editText("sin(||)", 5, KeyAction.Backspace)
        assertEquals("sin()", text)
        assertEquals(4, cursor)
        // 光标正好落回括号之间：左边 '('、右边 ')'
        assertEquals('(', text[cursor - 1])
        assertEquals(')', text[cursor])
        // 再按一次退格：这次删的是**整个空的 sin()**
        assertEquals("" to 0, editText(text, cursor, KeyAction.Backspace))
    }

    @Test
    fun `backspace with an unpaired bar only removes one character`() {
        // 光标在末尾、左边那个 | 右边没有配对的竖线 —— 只删一个
        assertEquals("|" to 1, editText("|x", 2, KeyAction.Backspace))
        assertEquals("x" to 1, editText("x|", 2, KeyAction.Backspace))
        // 光标左边是 x、右边是 |，也不成对
        assertEquals("||" to 1, editText("|x|", 2, KeyAction.Backspace))
        // 单个 | 后面什么都没有：光标在末尾，逐字符删
        assertEquals("" to 0, editText("|", 1, KeyAction.Backspace))
        // 其它成对符号（括号）保持逐字符删除 —— 成对删除只针对竖线
        assertEquals(")" to 0, editText("()", 1, KeyAction.Backspace))
    }

    @Test
    fun `three bars delete as a pair in the middle and one character at the end`() {
        // 奇数个竖线的语义（沿用上一轮定下来的规则，没有被函数名删除改掉）：
        // 只有"光标正好夹在一对相邻竖线之间"才成对删；
        // 光标在末尾时右边没有竖线，所以退回逐字符删；在开头退格是无操作。
        assertEquals("|" to 0, editText("|||", 1, KeyAction.Backspace))
        assertEquals("|" to 1, editText("|||", 2, KeyAction.Backspace))
        assertEquals("||" to 2, editText("|||", 3, KeyAction.Backspace))
        assertEquals("|||" to 0, editText("|||", 0, KeyAction.Backspace))
    }

    @Test
    fun `cursor movement is clamped to 0 until length`() {
        var text = "xy"
        var cursor = 0
        repeat(5) {
            val (t, c) = editText(text, cursor, KeyAction.CursorLeft)
            text = t
            cursor = c
        }
        assertEquals("向左移动不会越过开头", 0, cursor)
        assertEquals("xy", text)

        repeat(5) {
            val (t, c) = editText(text, cursor, KeyAction.CursorRight)
            text = t
            cursor = c
        }
        assertEquals("向右移动不会越过末尾", 2, cursor)
        assertEquals("xy", text)
    }

    @Test
    fun `cursor out of range is clamped before editing`() {
        val (text, cursor) = editText("xy", 99, KeyAction.Insert("!"))
        assertEquals("xy!", text)
        assertEquals(3, cursor)

        val (t2, c2) = editText("xy", -5, KeyAction.Insert("!"))
        assertEquals("!xy", t2)
        assertEquals(1, c2)
    }
}
