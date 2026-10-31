package com.functiondrawer.watch.keyboard

import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.ui.keyboard.ALPHABET_BACKSPACE_LABEL
import com.functiondrawer.watch.ui.keyboard.ALPHABET_CLOSE_LABEL
import com.functiondrawer.watch.ui.keyboard.ALPHABET_COLUMNS
import com.functiondrawer.watch.ui.keyboard.AlphabetKey
import com.functiondrawer.watch.ui.keyboard.KeyAction
import com.functiondrawer.watch.ui.keyboard.KeyPage
import com.functiondrawer.watch.ui.keyboard.KeySpec
import com.functiondrawer.watch.ui.keyboard.KeyTone
import com.functiondrawer.watch.ui.keyboard.LetterCase
import com.functiondrawer.watch.ui.keyboard.alphabetKeySpec
import com.functiondrawer.watch.ui.keyboard.alphabetRows
import com.functiondrawer.watch.ui.keyboard.applyTo
import com.functiondrawer.watch.ui.keyboard.editText
import com.functiondrawer.watch.ui.keyboard.insertTextOf
import com.functiondrawer.watch.ui.keyboard.keyboardRows
import com.functiondrawer.watch.ui.keyboard.labelOf
import com.functiondrawer.watch.ui.keyboard.toneOf
import com.functiondrawer.watch.ui.keyboard.variantsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置英文（字母表）键盘模型的单元测试。
 *
 * 和 KeyboardModelTest 一样是**离线可跑**的（纯 Kotlin + JUnit），
 * 覆盖四类最容易写错、又最难在手表上肉眼发现的东西：
 *   1. 网格形状 —— 每排 6 格、返回键在第一个、字母按字母表顺序、最后一排是
 *      「大小写切换 + `( ) ,`」；
 *   2. 三种大小写模式 —— 循环顺序、哪些模式输出大写、标签怎么变；
 *   3. "这个键此刻要插入什么文本" —— 字母键只记小写字母，大小写取值时才套上；
 *   4. 编辑语义 —— applyTo 必须和数学键盘的 editText **完全一致**
 *      （逗号跳槽、退格的"整体删掉一个调用"都不能漂移）。
 *
 * 另外还有一组盯着**数学键盘那边新加的 `abc` 入口键**的测试（文件末尾那一节）：
 * 它是这一轮唯一动到 KeyboardModel.kt 的地方，放这里和字母表键盘一起看更清楚。
 */
class AlphabetModelTest {

    private val modes = listOf(
        LetterCase.Lower,
        LetterCase.Upper,
        LetterCase.Lock
    )

    private fun rows() = alphabetRows()

    /** 网格里所有键（按排铺平）。 */
    private fun cells(): List<AlphabetKey> = rows().flatten()

    /** 网格里所有字母键，按出现顺序。 */
    private fun letters(): List<Char> =
        cells().filterIsInstance<AlphabetKey.Letter>().map { it.letter }

    // ---- 网格形状 ----

    @Test
    fun `the grid is six rows and the first row starts with the back key`() {
        // 需求原话："第一个按钮放返回键，每排 6 个，字母按字母表依次排布"。
        // 返回键占掉第 1 排的第 1 格，所以第 1 排只剩 5 格放字母（a~e）。
        val grid = rows()
        assertEquals("排数", 6, grid.size)
        assertEquals(
            "第 1 排",
            listOf(
                AlphabetKey.Close,
                AlphabetKey.Letter('a'),
                AlphabetKey.Letter('b'),
                AlphabetKey.Letter('c'),
                AlphabetKey.Letter('d'),
                AlphabetKey.Letter('e')
            ),
            grid[0]
        )
        assertEquals("第 1 排第 1 格必须是返回键", AlphabetKey.Close, grid[0].first())
    }

    @Test
    fun `no row is longer than six cells and only the last letter row is short`() {
        val grid = rows()
        // 每排最多 6 格（"每排 6 个字母"）；允许短排存在 ——
        // 26 个字母 + 返回键 = 27 格，除不尽 6，而且最后一排是另起的动作排。
        grid.forEachIndexed { index, row ->
            assertTrue("第 ${index + 1} 排有 ${row.size} 格，超过 $ALPHABET_COLUMNS", row.size <= ALPHABET_COLUMNS)
            assertTrue("第 ${index + 1} 排是空的", row.isNotEmpty())
        }
        // 前 4 排都是满的 6 格，第 5 排是 x y z（短排），第 6 排是动作排（4 格）
        listOf(0, 1, 2, 3).forEach { i ->
            assertEquals("第 ${i + 1} 排应该满 $ALPHABET_COLUMNS 格", ALPHABET_COLUMNS, grid[i].size)
        }
        assertEquals("第 5 排", listOf('x', 'y', 'z'), grid[4].map { (it as AlphabetKey.Letter).letter })
        assertEquals("第 6 排的格数", 4, grid[5].size)
        // 总数：返回键 1 + 26 个字母 + 动作排 4
        assertEquals("网格总格数", 31, cells().size)
    }

    @Test
    fun `letters run through the alphabet exactly once in order`() {
        // 需求："按照字母表依次排布"。顺序错了在手表上很难发现（26 个小键），
        // 所以直接和 'a'..'z' 比。
        assertEquals("字母顺序", ('a'..'z').toList(), letters())
        // 每个字母只出现一次
        assertEquals("字母个数", 26, letters().distinct().size)
    }

    @Test
    fun `letters only live in the five rows above the action row`() {
        val grid = rows()
        for (i in 0..4) {
            assertTrue("第 ${i + 1} 排应该有字母", grid[i].any { it is AlphabetKey.Letter })
        }
        assertTrue(
            "最后一排（动作排）不该有字母键",
            grid[5].none { it is AlphabetKey.Letter }
        )
    }

    @Test
    fun `the last row is the case key followed by the parens and the comma`() {
        // 需求原话："最后另起一排，左下角的键放大小写切换键，
        // 大小写切换键右边放左括号和右括号还有逗号"。
        assertEquals(
            "最后一排",
            listOf(
                AlphabetKey.ToggleCase,
                AlphabetKey.Symbol('('),
                AlphabetKey.Symbol(')'),
                AlphabetKey.Symbol(',')
            ),
            rows()[5]
        )
        // "左下角"：这一排在界面上是左对齐的（右边用等宽占位补齐），
        // 所以切换键就是整排最左那一格
        assertEquals("切换键必须在最左", AlphabetKey.ToggleCase, rows()[5].first())
    }

    @Test
    fun `the grid is a cached constant instead of being rebuilt every frame`() {
        // 网格不随模式 / 文本变化，UI 每帧都会要它一次，
        // 所以它必须是一个缓存好的只读常量（重复调用返回同一个实例）。
        assertSame("alphabetRows() 应该返回同一个常量", alphabetRows(), alphabetRows())
        assertEquals(rows(), rows())
    }

    @Test
    fun `every cell of the grid renders a non blank label in every mode`() {
        for (case in modes) {
            rows().flatten().forEach { key ->
                val label = labelOf(key, case)
                assertNotNull("$case 模式下 $key 的标签是 null", label)
                assertTrue("$case 模式下 $key 的标签是空白", label.isNotBlank())
            }
        }
    }

    @Test
    fun `no alphabet label is longer than two characters`() {
        // 字号护栏：每排 6 格，圆屏最下面那一排的每一份只有 ≈21dp 宽
        // （见 ui/AlphabetKeyboard.kt 的宽度核算），fontSmall（12.12sp）下
        // 两个汉字 ≈24dp 已经是极限 —— 所以所有标签都只能 1~2 个字符。
        for (case in modes) {
            for (key in cells()) {
                assertTrue(
                    "${labelOf(key, case)} 超过 2 个字符，在 6 列布局里放不下",
                    labelOf(key, case).length <= 2
                )
            }
        }
        // 大小写切换键的标签就是三种模式名，都是 2 个字
        for (case in modes) {
            assertEquals("$case 的标签长度", 2, case.label.length)
        }
    }

    // ---- 大小写模式 ----

    @Test
    fun `the three case modes cycle lower to upper to lock and back to lower`() {
        // 模型里写死的循环顺序（见 LetterCase 的说明）：
        // 小写 → 大写 → 大写锁定 → 小写
        assertEquals(LetterCase.Upper, LetterCase.Lower.next)
        assertEquals(LetterCase.Lock, LetterCase.Upper.next)
        assertEquals(LetterCase.Lower, LetterCase.Lock.next)
        // 绕着点一圈必须回到原点（三种模式、不多不少）
        var case = LetterCase.Lower
        val seen = mutableListOf(case)
        repeat(3) {
            case = case.next
            seen += case
        }
        assertEquals(listOf(LetterCase.Lower, LetterCase.Upper, LetterCase.Lock, LetterCase.Lower), seen)
        assertEquals("模式总数", 3, LetterCase.entries.size)
    }

    @Test
    fun `upper and lock both output uppercase and only lower outputs lowercase`() {
        // 两种"大写"的区别**不在字母长什么样**，而在再点一次切换键去哪（见下一条测试）
        assertTrue("小写模式不该输出大写", !LetterCase.Lower.isUppercase)
        assertTrue("大写模式要输出大写", LetterCase.Upper.isUppercase)
        assertTrue("大写锁定也要输出大写", LetterCase.Lock.isUppercase)
        for (letter in 'a'..'z') {
            val key = AlphabetKey.Letter(letter)
            assertEquals(letter.toString(), insertTextOf(key, LetterCase.Lower))
            assertEquals(letter.uppercaseChar().toString(), insertTextOf(key, LetterCase.Upper))
            assertEquals(letter.uppercaseChar().toString(), insertTextOf(key, LetterCase.Lock))
        }
    }

    @Test
    fun `the three mode labels are distinct and the toggle key shows the current one`() {
        // 需求："每种模式下的视觉要能区分（标签不同即可）"。
        // 三种模式名必须互不相同，而且切换键上显示的就是当前模式名。
        val labels = modes.map { it.label }
        assertEquals("模式名必须三个都不一样", 3, labels.distinct().size)
        for (case in modes) {
            assertEquals("$case 的切换键标签", case.label, labelOf(AlphabetKey.ToggleCase, case))
        }
    }

    @Test
    fun `letter labels follow the mode`() {
        val key = AlphabetKey.Letter('q')
        assertEquals("q", labelOf(key, LetterCase.Lower))
        assertEquals("Q", labelOf(key, LetterCase.Upper))
        assertEquals("Q", labelOf(key, LetterCase.Lock))
        // 26 个字母在三种模式下都要跟着变（不是只对某一个字母生效）
        for (letter in 'a'..'z') {
            for (case in modes) {
                val expected =
                    if (case.isUppercase) letter.uppercaseChar().toString() else letter.toString()
                assertEquals("$case 下 $letter", expected, labelOf(AlphabetKey.Letter(letter), case))
            }
        }
    }

    @Test
    fun `the case mode stays put while typing letters`() {
        // 需求里明确说过：**不要**做成"打一个字母就自动回落"。
        // 三种模式都是持续状态 —— 打一串字母之后模式一个都不许变，
        // 否则 `Sin` 这种连续大写每敲一个字母都要回去点一次切换键。
        for (case in modes) {
            var text = ""
            var cursor = 0
            for (letter in listOf('s', 'i', 'n')) {
                val (t, c) = applyTo(text, cursor, AlphabetKey.Letter(letter), case)
                text = t
                cursor = c
            }
            val expected = if (case.isUppercase) "SIN" else "sin"
            assertEquals("$case 模式下打 sin 应该得到 $expected", expected, text)
            assertEquals(3, cursor)
            // 模式本身没有 next 之外的任何"自动变化"：模式是枚举值，不会被 applyTo 改掉
            assertEquals("$case 不该被打字改掉", case, case)
        }
    }

    // ---- 属性编辑界面用的两种裁剪（用户需求 #4）----
    //
    // 属性界面里的名字编辑有三条硬约束（见 ui/PropertyEditor.kt）：
    //   * 大小写是**强制**的（函数/点/方程大写、滑动条小写）→ 不显示切换键；
    //   * **只有函数**的名字显示括号 → 点/方程/滑动条的名字不带 `( ) ,`。
    // 这两件事在模型里就是"最后一排要哪些键"，所以在这里盯住：
    // **动的只有最后一排**，字母排、返回键、字母顺序一格都不许变。

    /** 网格里所有字母键，按出现顺序（自定义网格版本）。 */
    private fun lettersOf(grid: List<List<AlphabetKey>>): List<Char> =
        grid.flatten().filterIsInstance<AlphabetKey.Letter>().map { it.letter }

    @Test
    fun `without the case toggle the letters stay and only that key disappears`() {
        val grid = alphabetRows(showCaseToggle = false)
        // 函数名的编辑：**有括号、没有大小写切换键**
        assertEquals("排数", 6, grid.size)
        assertEquals(
            "最后一排只剩括号和逗号",
            listOf(
                AlphabetKey.Symbol('('),
                AlphabetKey.Symbol(')'),
                AlphabetKey.Symbol(',')
            ),
            grid[5]
        )
        // 字母一格不少、顺序不变，返回键还在第 1 排第 1 格
        assertEquals("字母顺序", ('a'..'z').toList(), lettersOf(grid))
        assertEquals("返回键", AlphabetKey.Close, grid[0].first())
        assertTrue("不该有切换键", grid.flatten().none { it == AlphabetKey.ToggleCase })
        // 前 4 排仍然是满格（裁剪只发生在最后一排）
        listOf(0, 1, 2, 3).forEach { i ->
            assertEquals("第 ${i + 1} 排", ALPHABET_COLUMNS, grid[i].size)
        }
    }

    @Test
    fun `without the brackets the symbols disappear and everything else stays`() {
        val grid = alphabetRows(showBrackets = false)
        // 这个组合目前没人用（函数编辑器要括号、属性界面两个都不要），
        // 但四个组合都要有确定的行为，所以一起测。
        assertEquals("排数", 6, grid.size)
        assertEquals(
            "最后一排只剩切换键",
            listOf(AlphabetKey.ToggleCase),
            grid[5]
        )
        assertEquals("字母顺序", ('a'..'z').toList(), lettersOf(grid))
        assertTrue("不该有符号键", grid.flatten().none { it is AlphabetKey.Symbol })
    }

    @Test
    fun `without both the whole action row is dropped instead of left blank`() {
        val grid = alphabetRows(showCaseToggle = false, showBrackets = false)
        // 点/方程/滑动条的名字：只有字母。
        // 最后一排没内容时**整排不出现** —— 留一排 6 格的空白会白占 0.15 屏高，
        // 而且看起来像"键盘少了一块"。
        assertEquals("只剩 5 排字母", 5, grid.size)
        assertEquals("总格数 = 返回键 + 26 个字母", 27, grid.flatten().size)
        assertEquals("字母顺序", ('a'..'z').toList(), lettersOf(grid))
        assertTrue("不该有切换键", grid.flatten().none { it == AlphabetKey.ToggleCase })
        assertTrue("不该有符号键", grid.flatten().none { it is AlphabetKey.Symbol })
        assertTrue("每一排都还有字母", grid.all { row -> row.any { it is AlphabetKey.Letter } })
    }

    @Test
    fun `every combination of the two switches is cached and keeps the grid usable`() {
        // 四个组合各建一份、只建一次（UI 每帧都会要它一次，见 alphabetRows 的说明），
        // 而且任何组合下"每排不超过 6 格、标签都渲染得出来"这两条都不能破。
        for (showCase in listOf(true, false)) {
            for (brackets in listOf(true, false)) {
                val grid = alphabetRows(showCaseToggle = showCase, showBrackets = brackets)
                assertSame(
                    "($showCase, $brackets) 应该返回同一个常量",
                    grid,
                    alphabetRows(showCaseToggle = showCase, showBrackets = brackets)
                )
                assertEquals("($showCase, $brackets) 的字母顺序", ('a'..'z').toList(), lettersOf(grid))
                assertTrue("($showCase, $brackets) 不该有空格子", grid.all { it.isNotEmpty() })
                grid.forEach { row ->
                    assertTrue("($showCase, $brackets) 有超长排", row.size <= ALPHABET_COLUMNS)
                }
                for (case in modes) {
                    grid.flatten().forEach { key ->
                        assertTrue(
                            "($showCase, $brackets) 下 $key 的标签是空白",
                            labelOf(key, case).isNotBlank()
                        )
                        assertTrue(
                            "($showCase, $brackets) 下 ${labelOf(key, case)} 超过 2 个字符",
                            labelOf(key, case).length <= 2
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the default call is the full grid with the case key and the brackets`() {
        // 老调用方（FunctionEditor）一行都不用改：不带参数的调用必须还是原来那 6 排、
        // 最后一排还是「切换键 + ( ) ,」，而且和显式传两个 true 是**同一个实例**。
        assertSame(
            "默认调用应该就是 (true, true) 那一份",
            alphabetRows(),
            alphabetRows(showCaseToggle = true, showBrackets = true)
        )
        assertEquals(
            "最后一排",
            listOf(
                AlphabetKey.ToggleCase,
                AlphabetKey.Symbol('('),
                AlphabetKey.Symbol(')'),
                AlphabetKey.Symbol(',')
            ),
            alphabetRows()[5]
        )
    }

    // ---- 配色分档 ----

    @Test
    fun `letters and symbols use the normal tone and the back key uses the accent tone`() {
        for (case in modes) {
            for (key in cells()) {
                when (key) {
                    is AlphabetKey.Letter, is AlphabetKey.Symbol ->
                        assertEquals("${labelOf(key, case)} 的配色", KeyTone.Normal, toneOf(key, case))
                    // 返回键是**动作键**（按下去换界面），和数学键盘的 RAD/DEG 同一档
                    AlphabetKey.Close ->
                        assertEquals("返回键的配色", KeyTone.Accent, toneOf(key, case))
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `the case key is accented whenever the keyboard is not in the default lower mode`() {
        // 强调色在这里的含义是"这个键盘现在不是默认状态"：
        // 小写时是普通色，大写 / 锁定时是强调色 ——
        // 用户不用去读那两个汉字也能看出大写开着。
        assertEquals(KeyTone.Normal, toneOf(AlphabetKey.ToggleCase, LetterCase.Lower))
        assertEquals(KeyTone.Accent, toneOf(AlphabetKey.ToggleCase, LetterCase.Upper))
        assertEquals(KeyTone.Accent, toneOf(AlphabetKey.ToggleCase, LetterCase.Lock))
        // 而 `大写` 和 `锁定` 靠**标签**区分：两者同用强调色是故意的 ——
        // KeyPalette 里第三档是 danger（砖红），含义是"会删东西"，
        // 不能借来表达"锁定"，否则 ⌫ / 删除 / 撤销那一整套语义就乱了。
    }

    @Test
    fun `the spec of a cell combines the label and the tone`() {
        for (case in modes) {
            for (key in cells()) {
                val spec = alphabetKeySpec(key, case)
                assertEquals("$case / $key 的标签", labelOf(key, case), spec.label)
                assertEquals("$case / $key 的色调", toneOf(key, case), spec.tone)
                assertEquals("$case / $key 的语义", key, spec.key)
            }
        }
    }

    // ---- 插入什么 ----

    @Test
    fun `only letters and symbols insert text`() {
        for (case in modes) {
            for (key in cells()) {
                when (key) {
                    is AlphabetKey.Letter -> assertNotNull("$key 应该插入文本", insertTextOf(key, case))
                    is AlphabetKey.Symbol -> assertEquals(key.symbol.toString(), insertTextOf(key, case))
                    // 返回 / 切换大小写都不插文本（它们只改界面状态）
                    AlphabetKey.Close, AlphabetKey.ToggleCase ->
                        assertEquals("$key 不该插入文本", null, insertTextOf(key, case))
                    // 退格不在网格上，但它的事件同样不是"插入"
                    AlphabetKey.Backspace ->
                        assertEquals("退格不该插入文本", null, insertTextOf(AlphabetKey.Backspace, case))
                }
            }
        }
    }

    @Test
    fun `special characters insert exactly the symbols the requirement asked for`() {
        for (case in modes) {
            assertEquals("(", insertTextOf(AlphabetKey.Symbol('('), case))
            assertEquals(")", insertTextOf(AlphabetKey.Symbol(')'), case))
            assertEquals(",", insertTextOf(AlphabetKey.Symbol(','), case))
        }
    }

    @Test
    fun `the model stores only lowercase letters so a bad construction is rejected`() {
        // 字母键只描述"小写字母本身"，大小写由模式在取值时决定 ——
        // 构造一个大写字母会让两个地方都能决定大小写，迟早不一致，所以直接拒绝。
        assertThrows(IllegalArgumentException::class.java) { AlphabetKey.Letter('A') }
        assertThrows(IllegalArgumentException::class.java) { AlphabetKey.Letter('1') }
        assertThrows(IllegalArgumentException::class.java) { AlphabetKey.Letter('中') }
        // 小写字母本身是合法的
        assertEquals('a', AlphabetKey.Letter('a').letter)
        assertEquals('z', AlphabetKey.Letter('z').letter)
    }

    // ---- applyTo：插入 ----

    @Test
    fun `applyTo inserts a letter at the cursor`() {
        assertEquals("a" to 1, applyTo("", 0, AlphabetKey.Letter('a'), LetterCase.Lower))
        assertEquals("A" to 1, applyTo("", 0, AlphabetKey.Letter('a'), LetterCase.Upper))
        assertEquals("A" to 1, applyTo("", 0, AlphabetKey.Letter('a'), LetterCase.Lock))
        // 插在式子中间：光标停在插入内容之后，右边的内容不动
        assertEquals("xb1" to 2, applyTo("x1", 1, AlphabetKey.Letter('b'), LetterCase.Lower))
    }

    @Test
    fun `the first key is labelled as the way back to the math keyboard`() {
        // 第 1 个键是返回键：标签不随模式变（它不是字母键）
        for (case in modes) {
            assertEquals("$case 下返回键的标签", ALPHABET_CLOSE_LABEL, labelOf(AlphabetKey.Close, case))
        }
        assertEquals("返回", ALPHABET_CLOSE_LABEL)
    }

    @Test
    fun `the backspace key keeps a label even though it has no cell on the grid yet`() {
        // 当前网格上没有退格键（输入框右边那个 ⌫ 一直都在、随时能按），
        // 但语义和标签都保留着：applyTo 支持它，[labelOf] 也要给得出标签。
        assertEquals(ALPHABET_BACKSPACE_LABEL, labelOf(AlphabetKey.Backspace, LetterCase.Lower))
        assertEquals("⌫", ALPHABET_BACKSPACE_LABEL)
        assertTrue("网格上不该有退格键的格子", cells().none { it == AlphabetKey.Backspace })
    }

    @Test
    fun `applyTo inserts symbols and letters into a half typed expression`() {
        // 一个典型序列：大写锁定下打 Sin(2) —— 先插 S i n，再插 ( 2 )
        var text = ""
        var cursor = 0
        for (key in listOf(
            AlphabetKey.Letter('s'),
            AlphabetKey.Letter('i'),
            AlphabetKey.Letter('n'),
            AlphabetKey.Symbol('('),
            AlphabetKey.Symbol(')')
        )) {
            val (t, c) = applyTo(text, cursor, key, LetterCase.Lock)
            text = t
            cursor = c
        }
        assertEquals("SIN()", text)
        assertEquals(5, cursor)
    }

    @Test
    fun `applyTo clamps an out of range cursor before editing`() {
        assertEquals("ab" to 2, applyTo("a", 99, AlphabetKey.Letter('b'), LetterCase.Lower))
        assertEquals("ba" to 1, applyTo("a", -5, AlphabetKey.Letter('b'), LetterCase.Lower))
    }

    // ---- applyTo：动作键 ----

    @Test
    fun `applyTo backspace deletes one character and does nothing at the start`() {
        assertEquals("" to 0, applyTo("a", 1, AlphabetKey.Backspace, LetterCase.Lower))
        assertEquals("a" to 1, applyTo("ab", 2, AlphabetKey.Backspace, LetterCase.Lower))
        assertEquals("ab" to 0, applyTo("ab", 0, AlphabetKey.Backspace, LetterCase.Lower))
        assertEquals("" to 0, applyTo("", 0, AlphabetKey.Backspace, LetterCase.Lower))
        // 字母大小写不影响退格（退格只看文本）
        assertEquals("" to 0, applyTo("A", 1, AlphabetKey.Backspace, LetterCase.Lock))
    }

    @Test
    fun `applyTo backspace keeps the whole call deletion rule of the math keyboard`() {
        // 关键的一致性：字母表键盘和数学键盘插的是**同一个输入框**，
        // 所以"空的 sin() 整体删掉"这条真机上定过的语义必须一样。
        // applyTo 直接复用 editText，这条测试就是防它以后被改成自己实现一份。
        assertEquals("" to 0, applyTo("sin()", 4, AlphabetKey.Backspace, LetterCase.Lower))
        assertEquals("" to 0, applyTo("sin()", 5, AlphabetKey.Backspace, LetterCase.Lower))
        // 括号里已经有内容时退回**逐字符删**（和数学键盘那条"带参数的调用逐字符删"一致）
        assertEquals("sin()" to 4, applyTo("sin(2)", 5, AlphabetKey.Backspace, LetterCase.Lower))
    }

    @Test
    fun `applyTo reuses the comma slot jump of the math keyboard`() {
        // 逗号键在数学键盘上是"光标正对着一个已存在的逗号时跳过它"（填 log(,) 的槽位）。
        // 字母表键盘上那颗逗号是同一颗键的另一个位置，语义必须一致。
        val (text, cursor) = applyTo("log(2,)", 5, AlphabetKey.Symbol(','), LetterCase.Lower)
        assertEquals("文本不该变（没有插进第二个逗号）", "log(2,)", text)
        assertEquals("光标要跨过模板那个逗号", 6, cursor)
        // 光标不在逗号前面时就是普通插入
        assertEquals("1,2" to 2, applyTo("12", 1, AlphabetKey.Symbol(','), LetterCase.Lower))
    }

    @Test
    fun `applyTo leaves the text alone for the close and case keys`() {
        // 返回键 / 切换大小写键都**不动输入框里的文本** ——
        // 和数学键盘那边 ToggleAngleUnit 的处理方式一致：
        // 真正改界面状态的活由调用方干（onClose / onCaseChange）。
        for (key in listOf(AlphabetKey.Close, AlphabetKey.ToggleCase)) {
            for (case in modes) {
                assertEquals("sin(2)" to 5, applyTo("sin(2)", 5, key, case))
                assertEquals("" to 0, applyTo("", 0, key, case))
                // 超范围的光标也只是被夹紧，不会因为按了动作键而乱跳
                assertEquals("xy" to 2, applyTo("xy", 99, key, case))
                assertEquals("xy" to 0, applyTo("xy", -3, key, case))
            }
        }
    }

    @Test
    fun `tapping the case key three times walks through every mode and lands back on lower`() {
        // 真机操作：连点三次切换键 = 转一圈回到小写；
        // 而且点一次之后**只有模式在变**，文本不受影响。
        var case = LetterCase.Lower
        val visited = mutableListOf(case)
        repeat(3) {
            case = case.next
            visited += case
            assertEquals("切换模式不该动文本", "x" to 1, applyTo("x", 1, AlphabetKey.ToggleCase, case))
        }
        assertEquals(listOf("小写", "大写", "锁定", "小写"), visited.map { it.label })
    }

    // ---- 数学键盘上的 abc 入口键（这一轮唯一动 KeyboardModel.kt 的地方） ----

    private fun otherGrid(mode: PlotMode = PlotMode.EXPLICIT) = keyboardRows(KeyPage.OTHER, mode)

    private fun otherRow3(mode: PlotMode = PlotMode.EXPLICIT): List<KeySpec> = otherGrid(mode)[2]

    /** 某一页所有按键的 label（第 4 格那个"逗号占位"的守护要用别的页做对照）。 */
    private fun labelsOf(page: KeyPage, mode: PlotMode = PlotMode.EXPLICIT): List<String> =
        keyboardRows(page, mode).flatten().map { it.label }

    @Test
    fun `the third row of the other page is the space key plus the abc entry key`() {
        // 需求原话："在其他页面里，第三列的空格右边增加一个 abc 按钮"。
        // 注意：**"第三列"这里是"第三行"的意思** —— 空格键本来就在 OTHER 页的
        // 第 3 行，而"右边"只能是在同一行里加一格。
        for (mode in PlotMode.entries) {
            val row = otherRow3(mode)
            assertEquals("$mode 其他页第 3 行的键数", 2, row.size)
            assertEquals("$mode 第 3 行第 1 格是空格键", "空格", row[0].label)
            assertEquals("$mode 第 3 行第 2 格是 abc 键（在空格右边）", "abc", row[1].label)
        }
    }

    @Test
    fun `the space key now takes three quarters of the row and abc takes the rest`() {
        // 空格键从"整行"变成 3/4 宽、abc 占 1/4：靠 KeySpec.weight 的 3 : 1 表达，
        // MathKeyboard 把它直接交给 Row 的 Modifier.weight(...)。
        // 两者之间的缝仍然是同一个 gap，所以"中间留和按键相同的缝"是自然成立的。
        val row = otherRow3()
        assertEquals("空格键的宽度份数", 3, row[0].weight)
        assertEquals("abc 键的宽度份数", 1, row[1].weight)
        assertEquals(3 + 1, row.sumOf { it.weight })
    }

    @Test
    fun `the abc key opens the alphabet keyboard instead of inserting text`() {
        val abc = otherRow3()[1]
        assertEquals("abc 键的动作", KeyAction.OpenAlphabet, abc.action)
        // 它是**动作键**：editText 收到它时原样返回（不插文本、不动光标）
        assertEquals("sin(2)" to 5, editText("sin(2)", 5, KeyAction.OpenAlphabet))
        assertEquals("" to 0, editText("", 0, KeyAction.OpenAlphabet))
        assertEquals("xy" to 2, editText("xy", 99, KeyAction.OpenAlphabet))
        // 配色和另一个动作键（RAD/DEG）一样是 Accent 档
        assertEquals("abc 键的配色", KeyTone.Accent, abc.tone)
        // 长按气泡只服务于函数族，abc 没有变体
        assertEquals("abc 不该有长按变体", emptyList<KeySpec>(), variantsFor("abc"))
    }

    @Test
    fun `adding the abc key did not take anything away from the other page`() {
        // 加 abc 键那一轮：它是**加**出来的，不是替换（原来 9 个键 + abc = 10）。
        //
        // **本轮用户要求又动了这一页，但只动了一格**（前面的原话："把逗号、abs
        // 去掉吧，逗号和 abs 前面有了"；随后又补了一句**优先级最高**的
        // "其他页面的逗号先别删，留着占位"）：
        //   * `abs` 撤掉，它的第 3 格换成取坐标键 `x(A)`；
        //   * **逗号留在第 4 格**（占位），插入语义一个字没改。
        // 所以其他页仍然是 4 + 4 + 2 = 10 个键 —— 这条测试跟着改成
        // "除 `abs` 之外，原来那些键一个都不能少"。
        val labels = otherGrid().flatten().map { it.label }
        assertEquals("其他页的键数（4 + 4 + 2）", 10, labels.size)
        for (old in listOf("%", "max", "min", "⌈x⌉", "⌊x⌋", ",", "空格", "abc")) {
            assertTrue("其他页不该丢掉 $old", labels.contains(old))
        }
        // 本轮被用户点名撤掉的只有 `abs`（绝对值入口是**初等页**的 `||`）
        assertTrue("其他页不该再有 abs", !labels.contains("abs"))
        assertTrue("初等页要有 || 绝对值键", labelsOf(KeyPage.ELEMENTARY).contains("||"))
        // 撤掉 abs 空出来的那一格给的是取坐标键（默认标签 x(A)）
        assertTrue("其他页要有取坐标键 x(A)", labels.contains("x(A)"))
        // 角度制键还在（它是占位标签，UI 会换成当前制）
        val angle = otherGrid()[0][3]
        assertEquals(KeyAction.ToggleAngleUnit, angle.action)
        // 每排的键数约束：第 1 排 4 个、第 2 排**仍然是 4 个**（逗号占位）、第 3 排 2 个
        assertEquals(4, otherGrid()[0].size)
        assertEquals(4, otherGrid()[1].size)
        assertEquals(2, otherGrid()[2].size)
        // 逗号确实在第 4 格（用户要求"别删、留着占位"，所以位置也不能挪）
        assertEquals(",", otherGrid()[1][3].label)
    }

    @Test
    fun `only the alphabet entry key carries the open alphabet action`() {
        // OpenAlphabet 只应该出现在那一个键上：别的页、别的键都不许悄悄带上它
        for (mode in PlotMode.entries) {
            for (page in KeyPage.entries) {
                for (key in keyboardRows(page, mode).flatten()) {
                    if (key.action is KeyAction.OpenAlphabet) {
                        assertEquals("只有 OTHER 页的 abc 键该是 OpenAlphabet", KeyPage.OTHER, page)
                        assertEquals("abc", key.label)
                    }
                }
            }
        }
    }
}
