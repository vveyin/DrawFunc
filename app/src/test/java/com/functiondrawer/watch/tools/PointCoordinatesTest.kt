package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.Expression
import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.lineOf
import com.functiondrawer.watch.ui.GraphLineObject
import com.functiondrawer.watch.ui.GraphMeasureObject
import com.functiondrawer.watch.ui.GraphPointObject
import com.functiondrawer.watch.ui.MeasureKind
import com.functiondrawer.watch.ui.ObjectKind
import com.functiondrawer.watch.ui.ObjectNameCase
import com.functiondrawer.watch.ui.bindableParametersIn
import com.functiondrawer.watch.ui.isPointName
import com.functiondrawer.watch.ui.isScalarName
import com.functiondrawer.watch.ui.isValidObjectName
import com.functiondrawer.watch.ui.lineDomainText
import com.functiondrawer.watch.ui.lineEquationSummary
import com.functiondrawer.watch.ui.lineEquationText
import com.functiondrawer.watch.ui.nameChoicesIn
import com.functiondrawer.watch.ui.objectKindOf
import com.functiondrawer.watch.ui.objectNameCase
import com.functiondrawer.watch.ui.objectNameError
import com.functiondrawer.watch.ui.renameIdentifierIn
import com.functiondrawer.watch.ui.resolveScalarValues
import com.functiondrawer.watch.ui.substitutePointCoordinates
import com.functiondrawer.watch.ui.substituteScalarValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「取坐标」+「标量值」文本替换的单元测试（`ui/PointCoordinates.kt`）。
 *
 * 需求原话：
 *   * "括号里输入一个点的名字，把这个点的 x 坐标作为数值参与运算。"（`x(A)`）
 *   * "就是这些标量能直接写进表达式当数值用。"（角度/距离/面积的名字，比如 `f*x`）
 *
 * 为什么要单独一个测试文件：替换是在**送进引擎之前**改用户写的字，
 * 判错的两种后果都很糟：
 *   * **多改**了（把 `ax(A)` 当成取坐标、或者把 `1e5` 里的 `e` 当成标量）→
 *     用户写的式子被悄悄改成另一个式子（甚至直接坏掉），
 *     图上画出来的是别的东西，而报错信息会指向他根本没写过的表达式；
 *   * **少改**了（空格、负号、括号里多一个字符）→ 图上直接一条线都不出来，
 *     用户只会看到一句"不认识的名字 'A'"，完全猜不到是空格的问题。
 * 这两种错在真机上表现为"图不对"，靠肉眼看曲线是查不出来的，所以在这里钉死。
 *
 * 这些断言全部离线可跑：`PointCoordinates.kt` 只 import `engine.GPoint`，
 * 没有任何 Android/Compose 依赖（由 `tools/run-tool-tests.ps1` 编译运行）。
 */
class PointCoordinatesTest {

    /** 一张典型的点名表：A 是普通的点，B 带负数，C 在原点，AA 是双字母点名。 */
    private val points = mapOf(
        "A" to GPoint(1.5, 2.5),
        "B" to GPoint(-2.5, 0.0),
        "C" to GPoint(0.0, -3.25),
        "AA" to GPoint(10.0, 20.0)
    )

    private fun sub(src: String) = substitutePointCoordinates(src, points)

    // ---- 基本语义：取到的是"坐标值"，不是"函数调用" ----

    @Test
    fun `x of a point becomes its abscissa and y becomes its ordinate`() {
        // 需求：`x(A)` = 点 A 的横坐标、`y(A)` = 点 A 的纵坐标，都是**数值常量**
        assertEquals("(1.5)", sub("x(A)"))
        assertEquals("(2.5)", sub("y(A)"))
        assertEquals("(-2.5)", sub("x(B)"))
        assertEquals("(0.0)", sub("y(B)"))
        assertEquals("(0.0)", sub("x(C)"))
        assertEquals("(-3.25)", sub("y(C)"))
    }

    @Test
    fun `the replacement is a bracketed literal so it keeps its meaning in an expression`() {
        // 替换结果**一定带括号**：负数不带括号会和前面的减号粘成 `--`，
        // 和幂/隐式乘粘起来也说不清。下面这几条钉的就是"括号没被吃掉"。
        assertEquals("(1.5)+2", sub("x(A)+2"))
        assertEquals("2(1.5)", sub("2x(A)"))
        assertEquals("(-2.5)^2", sub("x(B)^2"))
        assertEquals("2-(1.5)", sub("2-x(A)"))
        // 负数在幂里也必须是对的：(-2.5)^2 = 6.25，而不是 -(2.5^2)
        assertTrue(sub("x(B)^2").startsWith("(-2.5)"))
    }

    @Test
    fun `several coordinate calls in one expression are all replaced`() {
        assertEquals("(1.5)+(2.5)", sub("x(A)+y(A)"))
        assertEquals("(1.5)*(2.5)", sub("x(A)*y(A)"))
        // 嵌套在别的调用里也一样（括号是配平的，替换不会破坏配对）
        assertEquals("sin((1.5))", sub("sin(x(A))"))
        assertEquals("max((1.5),(2.5))", sub("max(x(A),y(A))"))
    }

    @Test
    fun `multi letter point names work as a whole`() {
        // 点名是"大写字母序列"（A、B、…、Z、AA…），不是只有单个字母
        assertEquals("(10.0)", sub("x(AA)"))
        // 名字必须**整段**匹配：表里只有 AA、没有 A1，所以这个原样留着
        assertEquals("x(A1)", sub("x(A1)"))
        // 前缀不算命中：`x(A` 后面跟着 `A` 才构成 AA
        assertEquals("(20.0)", sub("y(AA)"))
    }

    // ---- 空格：括号内外都要容忍 ----

    @Test
    fun `spaces around the name and around the brackets are tolerated`() {
        assertEquals("(1.5)", sub("x( A )"))
        assertEquals("(1.5)", sub("x(  A  )"))
        assertEquals("(1.5)", sub("x (A)"))
        assertEquals("(1.5)", sub("x ( A )"))
        assertEquals("(1.5)", sub("x\t(\tA\t)"))
        // 式子中间也一样，而且**只吃掉调用本身**，两边的空格原样留着
        assertEquals("1 + (1.5) + 2", sub("1 + x( A ) + 2"))
    }

    // ---- 不该动的写法：一律原样留下 ----

    @Test
    fun `an unknown point name is left alone for the engine to report`() {
        // 名字不认识时**绝不瞎改**：原样送进引擎，它会报"不认识的名字 'Z'"。
        // 这个报错是可接受的（用户自己没描这个点），比悄悄算出一个数好得多。
        assertEquals("x(Z)", sub("x(Z)"))
        assertEquals("y(Z)", sub("y(Z)"))
        // 一条式子里一半认得、一半不认得时**逐个处理**：认得的那半照替换，
        // 不认得的那半原样留着（脚本按出现顺序从左到右扫，互不影响）
        assertEquals("(1.5)+x(Z)", sub("x(A)+x(Z)"))
    }

    @Test
    fun `point names are case sensitive`() {
        // 表里只有大写点名。小写 `a` 是**可绑定参数**，完全另一回事：
        // 它不该被当成"点 A"，也不该被替换（引擎会按参数去算）。
        assertEquals("x(a)", sub("x(a)"))
        assertEquals("y(a)", sub("y(a)"))
        // 反过来也一样：表里有 A，写 AA 之外的大小写变体都不命中
        assertEquals("x(Aa)", sub("x(Aa)"))
    }

    @Test
    fun `a bare variable or a call to a user function is not touched`() {
        // 最要紧的一条：光秃秃的 `x` / `y` / `2x` 必须**一个字符都不动**
        // （那是采样变量和隐式乘法，是这个 App 最常见的一类输入）
        assertEquals("x", sub("x"))
        assertEquals("2x", sub("2x"))
        assertEquals("x+1", sub("x+1"))
        assertEquals("y", sub("y"))
        assertEquals("sin(x)", sub("sin(x)"))
        assertEquals("x^2+y^2=1", sub("x^2+y^2=1"))
        // `a(x)` 是**调用用户自定义的函数 a**（引擎里那条 callables 通道）：
        // 我们的轴名字母在它里面根本不是独立的词，不能动
        assertEquals("a(x)", sub("a(x)"))
        assertEquals("2a(x)", sub("2a(x)"))
        // `x(x)` 是"调用用户函数 x"，后面那个括号里没有点名
        assertEquals("x(x)", sub("x(x)"))
    }

    @Test
    fun `a letter directly before the axis name blocks the replacement`() {
        // `ax(A)`：词法层读到的是**一个**名字 `ax`，那个 x 不是轴名。
        // 替换它就是改用户写的东西 —— 保守放过，让引擎去报"不认识的名字 'ax'"。
        assertEquals("ax(A)", sub("ax(A)"))
        // 下划线也一样：词法层的标识符允许下划线
        assertEquals("_x(A)", sub("_x(A)"))
        // 但**数字不算**：`2x(A)` 里 `2` 是独立的数字字面量、`x` 是独立的名字，
        // 所以那种写法要认（隐式乘法，见上面 the replacement is a bracketed literal…）
        assertEquals("2(1.5)", sub("2x(A)"))
        // `(` / `)` / 运算符之类本来就隔开了，也认：
        // 外面那层括号是用户自己写的，要原样留着（替换只吃掉 `x(A)` 三个字符）
        assertEquals("((1.5))", sub("(x(A))"))
    }

    @Test
    fun `uppercase axis names are not the axes`() {
        // 轴名只认小写的 `x` / `y`（大写 X / Y 不是引擎的采样变量，
        // 用户写 `X(A)` 本来就该报错），这里不能替它兜底
        assertEquals("X(A)", sub("X(A)"))
        assertEquals("Y(A)", sub("Y(A)"))
    }

    @Test
    fun `incomplete or malformed calls are left alone`() {
        // 键盘刚插完 `x()` 就是这个状态 —— 用户还没打点名，什么都不该发生
        assertEquals("x()", sub("x()"))
        assertEquals("x( )", sub("x( )"))
        // 括号没闭上
        assertEquals("x(A", sub("x(A"))
        // 括号里的东西不是一个纯点名
        assertEquals("x(A+1)", sub("x(A+1)"))
        assertEquals("x(A B)", sub("x(A B)"))
        assertEquals("x(1.5)", sub("x(1.5)"))
        // 方括号不是这里的括号（键盘插的一律是圆括号）
        assertEquals("x[A]", sub("x[A]"))
    }

    @Test
    fun `non finite coordinates are not substituted`() {
        // NaN / ±Infinity 一律**不替换**：硬塞一个 "NaN" 进去，引擎只会报
        // "数字格式不正确：NaN" 这种让人一头雾水的错；原样留着 `x(A)`
        // 反而还能让用户看见"这里有个东西算不出来"（行为说明见 PointCoordinates.kt）
        val broken = mapOf(
            "N" to GPoint(Double.NaN, 1.0),
            "I" to GPoint(Double.POSITIVE_INFINITY, 1.0),
            "J" to GPoint(1.0, Double.NEGATIVE_INFINITY)
        )
        assertEquals("x(N)", substitutePointCoordinates("x(N)", broken))
        assertEquals("x(I)", substitutePointCoordinates("x(I)", broken))
        assertEquals("y(J)", substitutePointCoordinates("y(J)", broken))
        // 同一张表里正常的那一半照常替换（坏值不牵连别的点）
        assertEquals("(1.5)+x(N)", substitutePointCoordinates("x(A)+x(N)", broken + points))
    }

    // ---- 空输入 / 短路 ----

    @Test
    fun `an empty table or empty source returns the source unchanged`() {
        assertEquals("x(A)", substitutePointCoordinates("x(A)", emptyMap()))
        assertEquals("", substitutePointCoordinates("", points))
        // 表里有东西但文本里没有取坐标：**连一个空格都不该动**
        val src = "sin( x ) + 2 y + a( x )"
        assertEquals(src, sub(src))
    }

    @Test
    fun `a y prefix in the source does not confuse the axis scan`() {
        // `y =` 前缀在 GraphState 里是**先剥掉**再替换的，但这里也顺手钉一下：
        // 前缀里的那个 `y` 后面是 `=`（不是括号），所以它不会被当成取坐标
        assertEquals("y = (2.5)", sub("y = y(A)"))
    }

    // ---- 点名规则本身 ----

    @Test
    fun `only uppercase letter sequences count as point names`() {
        // 这条规则是整个替换的"名字合法性"判据，单独钉住：
        //   * 大写字母序列 = 点名（A、B、AA…，和 GraphState.nextObjectName() 同形）；
        //   * 小写、数字、混写、空串都不是 —— 复平面里那些用**结果文本**
        //     （`2`、`2+3i`）当名字的点，因此不会被误当成点名。
        for (ok in listOf("A", "B", "Z", "AA", "ABC")) {
            assertTrue("$ok 应该是合法的点名", isPointName(ok))
        }
        for (bad in listOf("", "a", "Aa", "A1", "1", "2+3i", "A B", "x", "π")) {
            assertTrue("$bad 不该算点名", !isPointName(bad))
        }
    }

    @Test
    fun `a point named like a complex result label never matches`() {
        // 复平面模式下，数值运算算出来的点用**结果文本**当名字。
        // 用户写 `x(2)` 想的是"x 乘 2"，不是"取某个点的横坐标" ——
        // 表里就算真有这个键，替换器也只认大写字母序列，所以不会命中。
        val withComplexLabels = mapOf("2" to GPoint(9.0, 9.0), "2+3i" to GPoint(1.0, 2.0))
        assertEquals("x(2)", substitutePointCoordinates("x(2)", withComplexLabels))
        assertEquals("x(2+3i)", substitutePointCoordinates("x(2+3i)", withComplexLabels))
    }

    // =============================================================================
    // 标量值：把度量对象的名字（角度/距离/面积）直接当数值写进表达式
    // =============================================================================
    //
    // 需求原话："就是这些标量能直接写进表达式当数值用" —— 量出一段距离叫 `f` 之后，
    // 用户可以在别的函数里写 `f*x`、`x^2+f`，求值时拿那个度量当前的值参与运算。
    //
    // 走的是和取坐标**同一条路**（`substitutePointCoordinates` 的同一遍扫描），
    // 所以这一段的断言和上面那一段是同一个量级的重要：多替换一个字符，
    // 用户写的一条完全正常的式子就会变成另一个东西（甚至打不开），而真机上
    // 只表现为"图画错了/这条函数坏了"，肉眼根本查不出来。

    /** 一张典型的标量表：f 是普通正数、g 是负数、h 是面积这种大数、aa 是双字母名。 */
    private val scalars = mapOf(
        "f" to 3.0,
        "g" to -2.5,
        "h" to 12.25,
        "aa" to 0.5
    )

    /** 只替换标量（没有几何点）。 */
    private fun subScalar(src: String, table: Map<String, Double> = scalars) =
        substituteScalarValues(src, table)

    @Test
    fun `a scalar name becomes its value`() {
        // 需求：`f` 就是那个度量的数值，和 `x(A)` 取坐标一样是**数字常量**
        assertEquals("(3.0)", subScalar("f"))
        assertEquals("(-2.5)", subScalar("g"))
        assertEquals("(12.25)", subScalar("h"))
        // 双字母名要整段匹配（和点名 AA 一样）
        assertEquals("(0.5)", subScalar("aa"))
    }

    @Test
    fun `a scalar participates in arithmetic as a bracketed literal`() {
        // 和取坐标同一条规矩：替换结果**一定带括号**。
        // 负数尤其必须 —— `g^2` 不带括号会算成 -(2.5^2)，而用户要的是 (-2.5)^2。
        assertEquals("(3.0)*x", subScalar("f*x"))
        assertEquals("x^2+(3.0)", subScalar("x^2+f"))
        assertEquals("(-2.5)^2", subScalar("g^2"))
        assertEquals("2-(-2.5)", subScalar("2-g"))
        assertEquals("(3.0)*(0.5)", subScalar("f*aa"))
        // 括号是配平的，嵌在别的调用里也没问题
        assertEquals("sin((3.0))", subScalar("sin(f)"))
        assertEquals("max((3.0),(-2.5))", subScalar("max(f,g)"))
        // 一条式子里出现多次、和取坐标混着写也各自替换
        assertEquals("(3.0)+(3.0)", subScalar("f+f"))
    }

    @Test
    fun `a digit on the left of a scalar name is implicit multiplication`() {
        // 左边是**数字**照样认：词法层把 `2` 读成数字字面量、`f` 读成独立的名字，
        // 那就是隐式乘法（和 `2x(A)` 那条规矩一模一样）
        assertEquals("2(3.0)", subScalar("2f"))
        assertEquals("2(3.0)+1", subScalar("2f+1"))
        // 小数点开头的数字也一样（词法层认 `.5` 这种写法）
        assertEquals(".5(3.0)", subScalar(".5f"))
    }

    @Test
    fun `a letter next to a scalar name blocks the replacement`() {
        // 左边是字母/下划线 ⇒ 词法层读到的是**一个**更长的名字，动它就是在改用户的意思
        assertEquals("af", subScalar("af"))
        assertEquals("_f", subScalar("_f"))
        // 右边是字母/数字 ⇒ 同理（`f2` 在词法层是一个名字，不是 f 乘 2）
        assertEquals("fx", subScalar("fx"))
        assertEquals("f2", subScalar("f2"))
        assertEquals("af+b", subScalar("af+b"))
        // 名字不认识 ⇒ 原样留下，交给引擎报"不认识的名字"
        assertEquals("q*x", subScalar("q*x"))
        assertEquals("z+1", subScalar("z+1"))
    }

    @Test
    fun `a scalar followed by a bracket is a function call not a value`() {
        // 右边是 `(` ⇒ 那是**调用用户自定义的函数 f**（引擎里那条 callables 通道），
        // 不能替换成数值。空格也要算（词法层在 `(` 前跳过空白，`f (x)` 同样是调用）。
        assertEquals("f(x)", subScalar("f(x)"))
        assertEquals("f (x)", subScalar("f (x)"))
        assertEquals("f ( x )", subScalar("f ( x )"))
        assertEquals("2f(x)", subScalar("2f(x)"))
        // 方括号也一样：词法层把 `[` 当成圆括号（`Lexer.readSymbol` 里 `'('[` → LPAREN），
        // 所以 `f[2]` 在引擎眼里同样是"调用 f"
        assertEquals("f[2]", subScalar("f[2]"))
    }

    @Test
    fun `scientific notation is never touched`() {
        // 这一条专门为科学计数法写的：`1e5` 里的那个 `e` 是**数字字面量的一部分**，
        // 替换掉会得到 `1(3.0)5` —— 一个语法错误，可用户写的是完全合法的数。
        // 判定与引擎词法层 `Lexer.readNumber` 一字不差：左边紧挨数字/小数点、
        // 右边（可隔一个正负号）确实跟着数字，才算指数部分。
        val withE = mapOf("e" to 3.0)
        assertEquals("1e5", subScalar("1e5", withE))
        assertEquals("1e+5", subScalar("1e+5", withE))
        assertEquals("1e-5", subScalar("1e-5", withE))
        assertEquals("1.5e-3", subScalar("1.5e-3", withE))
        assertEquals("2*1e5+x", subScalar("2*1e5+x", withE))
        // 大写 E 也一样（复数引擎的常量表里 `E` 就是欧拉数，形状上同样不能动）
        assertEquals("1E5", subScalar("1E5", withE))
        // 反过来：**后面没有数字**时，词法层自己就把 `2e` 读成 `2 * e`（见 Lexer.readNumber
        // 的注释），所以这时该替换 —— 这条是"让路规则没有矫枉过正"的对照
        assertEquals("2(3.0)", subScalar("2e", withE))
    }

    @Test
    fun `a scalar with a non finite value is left alone`() {
        // NaN / ±Infinity 一律不替换（和取坐标同一条理由）：
        // 塞一个 "NaN" 进表达式，引擎只会报"数字格式不正确"，用户完全看不懂
        val broken = mapOf("f" to Double.NaN, "g" to Double.POSITIVE_INFINITY)
        assertEquals("f*x", subScalar("f*x", broken))
        assertEquals("g", subScalar("g", broken))
        // 同一张表里正常的那一半照常替换（坏值不牵连别的标量）
        assertEquals("(12.25)+(3.0)", subScalar("h+f", broken + scalars))
    }

    @Test
    fun `scalars and point coordinates are substituted in the same pass`() {
        // 两张表一起给的时候（GraphState.withPointCoordinates 就是这么调的），
        // 一条式子里两种写法都要各就各位
        assertEquals("(1.5)*(3.0)", substitutePointCoordinates("x(A)*f", points, scalars))
        assertEquals("(2.5)+(0.5)", substitutePointCoordinates("y(A)+aa", points, scalars))
        // 表只有一张的时候，另一种写法必须**原样不动**：
        // 没有几何点时 `x(A)` 不会被当成任何东西（引擎会报"不认识的名字 'x'"，
        // 那是可接受的报错），只有标量 `f` 被替换
        assertEquals("f*x", substitutePointCoordinates("f*x", points, emptyMap()))
        assertEquals("x(A)*(3.0)", substitutePointCoordinates("x(A)*f", emptyMap(), scalars))
    }

    @Test
    fun `an empty scalar table leaves everything alone`() {
        // 没量过任何东西（或名字是坏数据）时，这条路必须完全透明
        assertEquals("f*x+1", substituteScalarValues("f*x+1", emptyMap()))
        assertEquals("", substituteScalarValues("", scalars))
        // 复数结果标签那种不是纯小写字母的名字，本来就不是标量名，不参与替换
        assertEquals("2+3i", substituteScalarValues("2+3i", mapOf("2+3i" to 9.0)))
    }

    @Test
    fun `only lower case letter sequences count as scalar names`() {
        // 和 `isPointName` 对称的一条判据，单独钉住
        for (ok in listOf("f", "g", "z", "aa", "abc")) {
            assertTrue("$ok 应该是合法的标量名", isScalarName(ok))
        }
        for (bad in listOf("", "A", "F", "f1", "θ", "π", "2+3i", "f g", "_f")) {
            assertTrue("$bad 不该算标量名", !isScalarName(bad))
        }
    }

    // =============================================================================
    // 「标量是"不能调的变量"」：它绝不能长出滑动条
    // =============================================================================
    //
    // 用户原话："它和滑动条本质上都是变量，但是滑动条可以调，这个不能调。"
    // 也就是说标量和可绑定参数 `a`~`d` 是同一个概念的两面：
    //   * `a`~`d` = 可调的变量（拖滑动条）→ `bindableParametersIn` 必须认得出来；
    //   * `f`、`g`… = 量出来的变量（只读）→ 必须**一个都认不出来**，否则列表里会
    //     凭空多出一条拖了也没用的滑动条。
    // 最阴的一处是**多字母标量名**：名字是双射 26 进制，`f`~`w` 这些单字母用完就进位成
    // `aa`、`ab`… —— 里面含字母 `a`/`b`，逐字符扫描会把它拆成两个参数。

    @Test
    fun `scalar names never need a slider`() {
        // 单字母标量名（正常情况）
        for (name in listOf("f", "g", "h", "w")) {
            assertTrue("$name 不该需要滑动条", bindableParametersIn("$name*x").isEmpty())
            assertTrue("$name 不该需要滑动条", bindableParametersIn("x^2+$name").isEmpty())
        }
        // 多字母标量名（第 17 个度量之后就会出现 `aa`、`ab`…）：
        // 整段匹配之后它们只是"一个不是参数的名字"，绝不能拆出 a / b 来
        for (name in listOf("aa", "ab", "ba", "abc")) {
            assertTrue("$name 里含 a/b，但整段不是参数", bindableParametersIn("$name*x").isEmpty())
        }
        // 一整条式子里混着写也一样
        assertTrue(bindableParametersIn("f*x+g").isEmpty())
        assertTrue(bindableParametersIn("sin(f)*x(A)").isEmpty())
    }

    @Test
    fun `bindable parameters are still recognized`() {
        // 反过来：真正该有滑动条的写法一条都不能少（这是原有需求 #5 的行为）
        assertEquals(listOf("a"), bindableParametersIn("a"))
        assertEquals(listOf("a"), bindableParametersIn("2a"))
        assertEquals(listOf("a"), bindableParametersIn("y=a"))
        assertEquals(listOf("a", "b"), bindableParametersIn("a*x+b"))
        assertEquals(listOf("a"), bindableParametersIn("sin(a)"))
        // 右边有括号 = 函数调用 → 不是参数
        assertTrue(bindableParametersIn("a(x)").isEmpty())
        // 采样变量/点名不是参数
        assertTrue(bindableParametersIn("x+1").isEmpty())
        assertTrue(bindableParametersIn("x(A)").isEmpty())
        // 同一段标识符里的字母不算参数（引擎会直接把 `ax` 报成不认识的名字）
        assertTrue(bindableParametersIn("ax").isEmpty())
        assertTrue(bindableParametersIn("a2").isEmpty())
        // 顺序按出现顺序，且去重
        assertEquals(listOf("b", "a"), bindableParametersIn("b*x+a+2b"))
    }

    /** 标量值的端到端：替换结果要能被**真引擎**算成一个数。 */
    @Test
    fun `the substituted scalar really evaluates to the measure value`() {
        // `f*x` 在 x = 2 时 = 6
        assertEquals(6.0, valueOf(subScalar("f*x"), at = 2.0), 1e-12)
        // `x^2+g` 在 x = 1 时 = 1 + (-2.5) = -1.5
        assertEquals(-1.5, valueOf(subScalar("x^2+g"), at = 1.0), 1e-12)
        // 负标量的幂：`g^2` = 6.25（括号没套上会算成 -6.25 —— 这条就是为它写的）
        assertEquals(6.25, valueOf(subScalar("g^2")), 1e-12)
        // 左边是数字的隐式乘法：`2f+1` = 7
        assertEquals(7.0, valueOf(subScalar("2f+1")), 1e-12)
        // 取坐标和标量混在一条式子里：`x(A)*f` = 1.5*3 = 4.5
        assertEquals(
            4.5,
            valueOf(substitutePointCoordinates("x(A)*f", points, scalars)),
            1e-12
        )
    }

    // ---- 端到端：替换出来的文本要能真的被**引擎**算成一个数 ----

    /**
     * 上面那些断言比的都是**字符串**。字符串对了不代表引擎算得对 ——
     * 数字格式（`1.0E20` 这种）、负数的括号、隐式乘法（`2(1.5)`）这三件事
     * 只要有一件不合词法层的口味，"取坐标"就是空的。
     * 所以这一段直接拿真引擎（`engine.Expression`，工具测试的 classpath 里就有）
     * 把替换结果编译出来求值。
     */
    private fun valueOf(substituted: String, at: Double = 0.0): Double =
        Expression.compile(substituted, listOf("x")).eval(at)

    @Test
    fun `the substituted source really evaluates to the point coordinate`() {
        // 横坐标当数值用：`x(A)` → 1.5
        assertEquals(1.5, valueOf(sub("x(A)")), 1e-12)
        // 参与运算：`2x(A)+1` → 2*1.5+1 = 4（`2(1.5)` 走的是隐式乘法）
        assertEquals(4.0, valueOf(sub("2x(A)+1")), 1e-12)
        // 纵坐标：`y(A)` → 2.5
        assertEquals(2.5, valueOf(sub("y(A)")), 1e-12)
        // `y(C)` → -3.25（负数是 `(-3.25)`：不带括号的话 `2-y(C)` 会变成 `2--3.25`）
        assertEquals(-3.25, valueOf(sub("y(C)")), 1e-12)
        assertEquals(5.25, valueOf(sub("2-y(C)")), 1e-12)
        // 负数的幂：`x(B)^2` = (-2.5)^2 = 6.25。
        // 括号要是没套上，`-2.5^2` 会被算成 -6.25 —— 这条就是为它写的
        assertEquals(6.25, valueOf(sub("x(B)^2")), 1e-12)
        // 一条式子里取多次
        assertEquals(4.0, valueOf(sub("x(A)+y(A)")), 1e-12)
    }

    @Test
    fun `the number text round trips through the engine lexer`() {
        // `Double.toString()` 的形状（含 `1.0E20` 这种科学计数法）必须能被词法层读回
        // **同一个** double。差一点点看不出来，但在"取坐标"里就是"这个点的坐标搬错了"。
        val odd = mapOf(
            "T" to GPoint(1.0 / 3.0, 0.0),
            "H" to GPoint(1.0E20, 0.0),
            "S" to GPoint(1.0E-20, 0.0),
            "M" to GPoint(123456789.125, 0.0)
        )
        for ((name, p) in odd) {
            assertEquals(
                "点 $name 的横坐标经过替换之后变了",
                p.x,
                valueOf(substitutePointCoordinates("x($name)", odd)),
                0.0
            )
        }
    }

    @Test
    fun `an unknown name still reaches the engine as a plain unknown name error`() {
        // 名字不认识时原样送进引擎，报错必须是**用户看得懂**的那一句
        // （说的是点名，而不是"取坐标这个功能坏了"）。
        val error = runCatching { valueOf(sub("x(Z)")) }.exceptionOrNull()
        assertTrue("本该报错，结果没报：$error", error != null)
        val message = error?.message.orEmpty()
        assertTrue("报错里该提到那个不认识的点名：$message", message.contains("Z"))
        assertTrue("报错该是「不认识的名字」那一类：$message", message.contains("不认识"))
    }

    // ===================================================================================
    // 名字绑定表（用户需求："标量也可以用 a，滑动条也可以用 f……至于 e、x、y 这些可能冲突的
    // 字母，请你给函数编辑界面输入的这些字母单独分组编 id，这样也不用担心字母冲突的问题"）
    //
    // 这一段钉的是**纯逻辑**那两件事：
    //   1. `nameChoicesIn`：表达式里哪些字母有"指谁"的问题、各有哪些候选；
    //   2. `resolveScalarValues`：绑定表 + 老规则合出来的"这一次采样用哪张表"。
    // 界面（提示 chip / 候选条）和采样（`GraphState.functionsForSampling`）都只是这两件事的调用方。
    // ===================================================================================

    /** 两个滑动条：`a` → 11（引擎认识的参数名）、`f` → 12（引擎不认识的名字）。 */
    private val sliderParams = mapOf("a" to 11L, "f" to 12L)

    /** 两个标量对象：一个叫 `a`（和滑动条同名！）、一个叫 `g`。 */
    private val scalarNames = mapOf("a" to 21L, "g" to 22L)
    private val scalarLabels = mapOf("a" to "距离", "g" to "角度")

    private fun choices(src: String, bound: Map<String, Long> = emptyMap()) =
        nameChoicesIn(src, sliderParams, scalarNames, scalarLabels, bound)

    @Test
    fun `同名滑动条和标量都要列成候选_并且标成必须选`() {
        val c = choices("a*x").single()
        assertEquals("a", c.letter)
        assertTrue("两个候选 = 冲突", c.ambiguous)
        assertTrue("冲突就必须让用户选", c.needsDecision)
        assertEquals(2, c.candidates.size)
        assertEquals("a：滑动条", c.candidates[0].chipLabel)
        assertEquals("a：距离", c.candidates[1].chipLabel)
        assertEquals(11L, c.candidates[0].objectId)
        assertEquals(21L, c.candidates[1].objectId)
    }

    @Test
    fun `引擎自己认识的可绑定参数不需要指认`() {
        // `a` 只有滑动条一个候选（没有同名标量）→ 引擎自己会认领，
        // 不提示用户（写 `a` 就跟写数字一样自然，天天弹提示会很烦）
        val only = nameChoicesIn("a*x", mapOf("a" to 11L), emptyMap())
        assertEquals(1, only.size)
        assertFalse("唯一候选 + 引擎认识它 → 不用选", only.single().needsDecision)
        assertFalse(only.single().ambiguous)
    }

    @Test
    fun `引擎不认识的滑动条名必须让用户指认`() {
        // 用户明确要的："滑动条也可以用 f" —— 引擎只把 a~d 当可绑定参数，
        // 所以叫 f 的滑动条不指认就会被报成"不认识的名字 'f'"
        val c = nameChoicesIn("f*x", mapOf("f" to 12L), emptyMap()).single()
        assertEquals("f：滑动条", c.candidates.single().chipLabel)
        assertTrue("引擎不认识 f 这个参数，必须选", c.needsDecision)
    }

    @Test
    fun `标量叫了引擎里的名字必须让用户指认`() {
        // 标量叫 x（采样变量）时：不指认的话引擎自己会把 x 当自变量，
        // 而用户以为"这是那个距离" —— 必须提示
        val c = nameChoicesIn("x+1", emptyMap(), mapOf("x" to 31L), mapOf("x" to "距离")).single()
        assertTrue(c.needsDecision)
    }

    @Test
    fun `普通标量名是自动解析的_列出来只为了给提示`() {
        val c = choices("g*x").single()
        assertEquals("g：角度", c.candidates.single().chipLabel)
        assertFalse("g 引擎里没有含义、也没有同名滑动条 → 不用选", c.needsDecision)
    }

    @Test
    fun `函数调用和长名字都不算候选`() {
        // `a(x)` 是函数调用、`ax` 是一个更长的名字 —— 两种都不该出现候选
        assertTrue(nameChoicesIn("a(x)", sliderParams, scalarNames).isEmpty())
        assertTrue(nameChoicesIn("ax+1", sliderParams, scalarNames).isEmpty())
        // 点名是大写、复平面结果是数字，都不是"标量字母"
        assertTrue(nameChoicesIn("x(A)", sliderParams, scalarNames).isEmpty())
    }

    @Test
    fun `已经指认过的字母会把当前指到谁带出来`() {
        val c = choices("a*x", bound = mapOf("a" to 21L)).single()
        assertEquals("界面据此把那一颗高亮", 21L, c.boundId)
    }

    @Test
    fun `绑定表说了算_可以盖住引擎的可绑定参数`() {
        // `a` 同时有滑动条（11，值 2.0）和标量（21，值 7.5）。
        // **绑定到标量**：`a` 就折成 7.5（这是用户要的能力：字母不再和引擎内置含义绑死）
        val toScalar = resolveScalarValues(
            auto = mapOf("a" to 7.5),
            bound = mapOf("a" to 21L),
            sliderValues = mapOf(11L to 2.0),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = setOf("a")
        )
        assertEquals(7.5, toScalar.getValue("a"), 1e-12)
        // **绑定到滑动条**：折成滑动条当前值 2.0
        val toSlider = resolveScalarValues(
            auto = mapOf("a" to 7.5),
            bound = mapOf("a" to 11L),
            sliderValues = mapOf(11L to 2.0),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = setOf("a")
        )
        assertEquals(2.0, toSlider.getValue("a"), 1e-12)
    }

    @Test
    fun `没绑定时同名滑动条优先_标量第二`() {
        // 用户原话："没绑定就按现在的规则（ParameterSet.BINDABLE 优先，其次是同名标量）解析"
        val withSlider = resolveScalarValues(
            auto = mapOf("a" to 7.5, "g" to 1.25),
            bound = emptyMap(),
            sliderValues = mapOf(11L to 2.0),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = setOf("a")
        )
        assertFalse("有同名滑动条 → 让给引擎，这里不替换", withSlider.containsKey("a"))
        assertEquals("没有滑动条同名的标量照旧自动解析", 1.25, withSlider.getValue("g"), 1e-12)

        // 没有那条滑动条（或它被隐藏了，`toParameterSet` 不收隐藏的）→ 标量顶上
        val noSlider = resolveScalarValues(
            auto = mapOf("a" to 7.5),
            bound = emptyMap(),
            sliderValues = emptyMap(),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = emptySet()
        )
        assertEquals(7.5, noSlider.getValue("a"), 1e-12)
    }

    @Test
    fun `绑定指向的对象没了就报错_绝不换成同名的别人`() {
        // 绑定到 99L（已经删掉的对象）：这个字母**从表里拿掉**，
        // 表达式里的它原样进引擎 → 报"不认识的名字"。
        // 绝不能回退到同名的自动值（那会在用户以为 f 是"那段距离"时悄悄换成别的东西）
        val table = resolveScalarValues(
            auto = mapOf("f" to 3.0),
            bound = mapOf("f" to 99L),
            sliderValues = emptyMap(),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = emptySet()
        )
        assertFalse("删掉的对象不能悄悄换成同名标量", table.containsKey("f"))
        // 而且替换结果里 `f` 还是 `f`（原样交给引擎）
        assertEquals("f*x", substituteScalarValues("f*x", table))
    }

    @Test
    fun `绑定的字母真的能把值折进表达式`() {
        // 端到端：绑定 `a` → 标量 21（值 7.5），表达式 `a*x` 在 x = 2 时该是 15
        val table = resolveScalarValues(
            auto = emptyMap(),
            bound = mapOf("a" to 21L),
            sliderValues = emptyMap(),
            measureValuesById = mapOf(21L to 7.5),
            sliderParams = setOf("a")
        )
        assertEquals("(7.5)*x", substituteScalarValues("a*x", table))
        assertEquals(15.0, valueOf(substituteScalarValues("a*x", table), at = 2.0), 1e-12)
    }

    // ===================================================================================
    // 几何对象的**改名规则**（`ui/GraphObjects.kt` 末尾那一节）
    //
    // 用户本轮要求："给所有函数列表里面的对象都加长按菜单，直接调用函数的长按菜单，
    // 但是注意要适配各个对象的特征，比如点的名字只能是大写，诸如此类。"
    //
    // 为什么和"取坐标"放在同一个测试文件里：对象的**名字就是表达式里的引用**
    // （`x(A)`、`a*x`），改名规则和 `x(A)` 的替换逻辑是同一件事的两半 ——
    // 改了一边、另一边没跟上，结果就是"图悄悄画成别的东西"，而且不报错。
    // 两者放一起，以后动其中一个时另一个跑不掉。
    // ===================================================================================

    private val pointA = GraphPointObject(id = 1L, name = "A", point = GPoint(1.0, 2.0))
    private val lineC = GraphLineObject(
        id = 2L,
        name = "C",
        line = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
    )
    private val scalarA = GraphMeasureObject(
        id = 3L,
        name = "a",
        kind = MeasureKind.DISTANCE,
        points = listOf(GPoint(0.0, 0.0), GPoint(3.0, 4.0)),
        value = 5.0
    )

    @Test
    fun `只有点的名字是大写字母，其余对象一律小写`() {
        // 用户本轮原话："除了点之外的所有对象的名字应该都是小写字母。"
        // 规则本体收敛在 `objectNameCase` 一处（发名字和校验改名共用），这里把它钉住。
        assertEquals(ObjectNameCase.UPPER, objectNameCase(pointA))
        // ⚠️ 这一条本轮**改了**：直线原来和点共用大写字母表，现在归小写
        assertEquals(ObjectNameCase.LOWER, objectNameCase(lineC))
        assertEquals(ObjectNameCase.LOWER, objectNameCase(scalarA))
        // 按"种类"的那个入口（发名字时对象还不存在，只能按种类问）必须是同一个答案
        assertEquals(ObjectNameCase.UPPER, objectNameCase(ObjectKind.POINT))
        assertEquals(ObjectNameCase.LOWER, objectNameCase(ObjectKind.LINE))
        assertEquals(ObjectNameCase.LOWER, objectNameCase(ObjectKind.MEASURE))
        assertEquals(ObjectKind.POINT, objectKindOf(pointA))
        assertEquals(ObjectKind.LINE, objectKindOf(lineC))
        assertEquals(ObjectKind.MEASURE, objectKindOf(scalarA))

        // 点名：合法的是大写序列（`x(A)` 的引用通道），小写不行
        assertNull(objectNameError(pointA, "B", taken = emptySet()))
        assertNull(objectNameError(pointA, "AA", taken = emptySet()))
        assertTrue(isValidObjectName(pointA, "Z"))
        assertTrue(objectNameError(pointA, "b", taken = emptySet())!!.contains("大写"))
        assertFalse(isValidObjectName(pointA, "b"))
        // 数字/符号/中文都不行：点名是"纯大写字母序列"（`isPointName` 的口径）
        assertFalse(isValidObjectName(pointA, "A1"))
        assertFalse(isValidObjectName(pointA, "A+"))
        assertFalse(isValidObjectName(pointA, "点"))
        // 空名字不行
        assertTrue(objectNameError(pointA, "", taken = emptySet())!!.contains("不能为空"))
        // 太长不行（列表行放不下）
        assertTrue(objectNameError(pointA, "ABCD", taken = emptySet())!!.contains("最多"))
    }

    @Test
    fun `直线和度量都只能用小写字母（点以外的对象全小写）`() {
        for (o in listOf(lineC, scalarA)) {
            assertEquals(ObjectNameCase.LOWER, objectNameCase(o))
            assertNull(objectNameError(o, "b", taken = emptySet()))
            assertTrue(isValidObjectName(o, "c"))
            // 大写被拒（那条报错文案本轮去掉了"标量"这个词：同一条规则也管直线）
            assertTrue(objectNameError(o, "C", taken = emptySet())!!.contains("小写"))
            assertFalse(isValidObjectName(o, "C"))
            assertFalse(isValidObjectName(o, "a1"))
            assertFalse(isValidObjectName(o, ""))
        }
        // 一条**老文件里带大写名字**的直线：读进来照用，但"改名"这条路上它
        // 不再是合法的新名字（用户要求"兼容读取、不要静默改名"——
        // 所以模型里留着 `C`，界面上草稿等于模型时也不报错，见 PropertyEditor）
        val legacyLine = GraphLineObject(
            id = 9L, name = "C", line = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        )
        assertEquals("C", legacyLine.name)
        assertTrue(objectNameError(legacyLine, "C", taken = emptySet()) != null)
    }

    @Test
    fun `小写对象不许叫 x y t e i z（它们在建表达式里有固定含义）`() {
        // 这一条是**改名**特有的约束：分配器允许分到这些字母（冲突由用户在编辑器里
        // 指认一次），但用户手打一个字改的是**所有**表达式里那个字母的含义 ——
        // `x` 变成"某段距离的值"之后，已有曲线会静默变形，比报错更难发现。
        // 本轮起它管**所有小写对象**（直线、角度、距离、面积），不再只管标量。
        for (reserved in listOf("x", "y", "t", "e", "i", "z")) {
            for (o in listOf(lineC, scalarA)) {
                val err = objectNameError(o, reserved, taken = emptySet())
                assertTrue("$reserved 应当被拒绝，实际：$err", err != null && err.contains("固定含义"))
            }
        }
        // 相邻的字母仍然可以（b/c/f…）
        assertNull(objectNameError(scalarA, "f", taken = emptySet()))
        assertNull(objectNameError(lineC, "f", taken = emptySet()))
    }

    @Test
    fun `重名会被挡下，但改成自己原来的名字不算重名`() {
        val taken = setOf("B", "g")
        assertTrue(objectNameError(pointA, "B", taken)!!.contains("占用"))
        assertTrue(objectNameError(scalarA, "g", taken)!!.contains("占用"))
        // 直线和度量现在共用同一套小写字母表 → 一条叫 g 的直线和标量 g 会撞名
        assertTrue(objectNameError(lineC, "g", taken)!!.contains("占用"))
        // 原来是 A，改成 A：调用方只把"别的"名字放进 taken，所以这里必须通过
        assertNull(objectNameError(pointA, "A", taken))
    }

    // ===================================================================================
    // 直线的方程（用户原话："在使用任何方式生成直线时，请在函数列表里的直线一栏
    // 写出直线的 y=kx+b 方程。"）
    //
    // 为什么要离线钉住每一条退化情形：列表第二行是**用户唯一**能读到方程的地方，
    // 而"竖直线写成 y = 1e9x"、"水平线写成 y = 0x + 3"、"b < 0 写成 + -3" 这类
    // 输出占了列表宽度又读不懂 —— 靠肉眼看列表很难一眼发现，只能在这里钉死字符串。
    // 直线那一类对象（直线/线段/射线）**所有生成路径**都写同一个函数，所以这里测到
    // 的就是用户看到的（垂线、中垂线、切线、对称结果都走它）。
    // ===================================================================================

    /** 造一条直线：给两个锚点和种类。 */
    private fun line(p: GPoint, q: GPoint, kind: GLineKind = GLineKind.LINE) = GLine(p, q, kind)

    @Test
    fun `一般的斜线写成 y = kx + b`() {
        // k=2、b=1：过 (0,1) 和 (1,3)
        assertEquals("y = 2x + 1", lineEquationText(line(GPoint(0.0, 1.0), GPoint(1.0, 3.0))))
        // 两点都不在 y 轴上，b 要用"显示用的 k"反算出来（k=2 时过 (1,3) → b=1）
        assertEquals("y = 2x + 1", lineEquationText(line(GPoint(1.0, 3.0), GPoint(2.0, 5.0))))
        // 分数斜率取到千分位（和列表里其它数字同一位数）
        assertEquals("y = 0.5x + 2", lineEquationText(line(GPoint(0.0, 2.0), GPoint(2.0, 3.0))))
        // 负斜率
        assertEquals("y = -0.5x + 2", lineEquationText(line(GPoint(0.0, 2.0), GPoint(2.0, 1.0))))
    }

    @Test
    fun `竖直线写 x = c（斜率不存在，不能写 y = kx + b）`() {
        assertEquals("x = 3", lineEquationText(line(GPoint(3.0, 0.0), GPoint(3.0, 7.0))))
        assertEquals("x = -2.5", lineEquationText(line(GPoint(-2.5, 1.0), GPoint(-2.5, -4.0))))
        // 竖直的线段/射线也一样（方程部分与种类无关）
        assertEquals("x = 3", lineEquationText(line(GPoint(3.0, 0.0), GPoint(3.0, 7.0), GLineKind.SEGMENT)))
    }

    @Test
    fun `水平线写 y = b（绝不写 0x）`() {
        assertEquals("y = 5", lineEquationText(line(GPoint(0.0, 5.0), GPoint(9.0, 5.0))))
        assertEquals("y = -1.25", lineEquationText(line(GPoint(-3.0, -1.25), GPoint(4.0, -1.25))))
        // 浮点噪声导致的"几乎是水平"也算水平（斜率取整到千分位后是 0）
        assertEquals("y = 2", lineEquationText(line(GPoint(0.0, 2.0), GPoint(1000.0, 2.0000001))))
    }

    @Test
    fun `k 是 1 或 -1 时不写 1x`() {
        assertEquals("y = x + 3", lineEquationText(line(GPoint(0.0, 3.0), GPoint(1.0, 4.0))))
        assertEquals("y = -x + 3", lineEquationText(line(GPoint(0.0, 3.0), GPoint(1.0, 2.0))))
        // 只有 k 没有 b 的时候也一样
        assertEquals("y = x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(2.0, 2.0))))
        assertEquals("y = -x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(2.0, -2.0))))
    }

    @Test
    fun `b 是 0 时不写 + 0`() {
        assertEquals("y = 2x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(1.0, 2.0))))
        assertEquals("y = -3x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(1.0, -3.0))))
        // 过原点的线：b 会算出 -0.0 / 1e-17 这类噪声，也必须按 0 处理
        assertEquals("y = 4x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(3.0, 12.0))))
    }

    @Test
    fun `b 小于 0 时写减号，不写加负数`() {
        assertEquals("y = 2x - 3", lineEquationText(line(GPoint(0.0, -3.0), GPoint(1.0, -1.0))))
        assertEquals("y = x - 1", lineEquationText(line(GPoint(0.0, -1.0), GPoint(1.0, 0.0))))
        assertEquals("y = -x - 2.5", lineEquationText(line(GPoint(0.0, -2.5), GPoint(1.0, -3.5))))
        for (text in listOf("y = 2x - 3", "y = x - 1", "y = -x - 2.5")) {
            assertFalse("方程里绝不能出现 `+ -`：$text", text.contains("+ -"))
        }
    }

    @Test
    fun `线段和射线在方程后面写定义域`() {
        val seg = GLineKind.SEGMENT
        val ray = GLineKind.RAY
        // 线段：两个端点的 x 之间（顺序按大小，用户读起来才顺）
        assertEquals(
            "y = 2x + 1 (-1 ≤ x ≤ 3)",
            lineEquationSummary(line(GPoint(-1.0, -1.0), GPoint(3.0, 7.0), seg))
        )
        // 端点反过来写，输出的区间仍然是从小到大
        assertEquals(
            "y = 2x + 1 (-1 ≤ x ≤ 3)",
            lineEquationSummary(line(GPoint(3.0, 7.0), GPoint(-1.0, -1.0), seg))
        )
        // 射线：从 p 出发朝 q 延伸 → x ≥ p.x（dx > 0）或 x ≤ p.x（dx < 0）
        assertEquals(
            "y = 0.5x (x ≥ -2)",
            lineEquationSummary(line(GPoint(-2.0, -1.0), GPoint(2.0, 1.0), ray))
        )
        assertEquals(
            "y = 0.5x (x ≤ -2)",
            lineEquationSummary(line(GPoint(-2.0, -1.0), GPoint(-6.0, -3.0), ray))
        )
        // 竖直的线段/射线改用 y 的范围（x 是常数，拿它当区间变量会变成空集）
        assertEquals(
            "x = 2 (-1 ≤ y ≤ 3)",
            lineEquationSummary(line(GPoint(2.0, 3.0), GPoint(2.0, -1.0), seg))
        )
        assertEquals(
            "x = 2 (y ≥ -1)",
            lineEquationSummary(line(GPoint(2.0, -1.0), GPoint(2.0, 5.0), ray))
        )
        assertEquals(
            "x = 2 (y ≤ -1)",
            lineEquationSummary(line(GPoint(2.0, -1.0), GPoint(2.0, -5.0), ray))
        )
        // 直线没有定义域（它本来就无限长）
        assertEquals("y = 2x + 1", lineEquationSummary(line(GPoint(0.0, 1.0), GPoint(1.0, 3.0))))
        assertNull(lineDomainText(line(GPoint(0.0, 1.0), GPoint(1.0, 3.0))))
    }

    @Test
    fun `退化直线没有方程（那种线画不出来，也不该编一个）`() {
        // 两个锚点重合
        assertNull(lineEquationText(line(GPoint(1.0, 1.0), GPoint(1.0, 1.0))))
        assertNull(lineEquationSummary(line(GPoint(1.0, 1.0), GPoint(1.0, 1.0))))
        // 坐标里有 NaN / ∞
        assertNull(lineEquationText(line(GPoint(Double.NaN, 1.0), GPoint(2.0, 1.0))))
        assertNull(lineEquationText(line(GPoint(1.0, 1.0), GPoint(1.0, Double.POSITIVE_INFINITY))))
    }

    @Test
    fun `方程里的数字位数与列表其它地方一致（千分位、整数不带小数点、极大走科学计数法）`() {
        // 和 `Common.formatNumber` 同一套规则：整数不带 ".0"
        assertEquals("y = 3x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(1.0, 3.0))))
        // 千分位以内的分数原样写
        assertEquals("y = 0.333x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(3.0, 1.0))))
        // 大但仍然读得出来的斜率照写数（不科学计数法）
        assertEquals("y = 50x", lineEquationText(line(GPoint(0.0, 0.0), GPoint(2.0, 100.0))))
        // 量级到了科学计数法那一段（|k| ≥ 1e5）就**按竖直线写**，见 NEAR_VERTICAL_SLOPE：
        // 那种线在屏幕上本来就是一条竖线（横向偏移 < 0.005px），写 `y = 1.0e+05x` 只会让人读不懂
        assertEquals("x = 0", lineEquationText(line(GPoint(0.0, 0.0), GPoint(0.001, 100.0))))
        assertEquals("x = 2", lineEquationText(line(GPoint(2.0, 0.0), GPoint(2.000001, 100.0))))
    }

    @Test
    fun `几乎竖直的线（切线的常见情形）也按竖直线写，而且定义域跟着一致`() {
        // `Geometry.tangentLine` 的斜率是数值求出来的，在 tan(x) 渐近线附近会得到一条
        // 斜率上万、但并不精确竖直的线。方程写 x = c、定义域也必须用 y 的区间，
        // 两处共用同一个判据（否则会出现 `x = 3 (-1 ≤ x ≤ 3)` 这种自相矛盾的第二行）。
        val steepSeg = line(GPoint(3.0, -1.0), GPoint(3.000001, 3.0), GLineKind.SEGMENT)
        assertEquals("x = 3", lineEquationText(steepSeg))
        assertEquals("x = 3 (-1 ≤ y ≤ 3)", lineEquationSummary(steepSeg))
        // 斜率不大不小（50000，横向偏移 ~0.01px）仍旧照写 y = kx + b —— "能读的就不改"
        val k50k = line(GPoint(0.0, 0.0), GPoint(0.002, 100.0))
        assertEquals("y = 50000x", lineEquationText(k50k))
    }

    @Test
    fun `改名时表达式里的引用同步改掉`() {
        // `x(A)` / `y(A)` 是**按名字**引用点的，改名必须跟着改，否则图上直接报
        // "不认识的名字 A"
        assertEquals("x(B)", renameIdentifierIn("x(A)", from = "A", to = "B"))
        assertEquals("x(B)+y(B)*2", renameIdentifierIn("x(A)+y(A)*2", from = "A", to = "B"))
        // 标量是按名字折成数值的（`a*x`），同理
        assertEquals("b*x", renameIdentifierIn("a*x", from = "a", to = "b"))
        assertEquals("sin(b)+b", renameIdentifierIn("sin(a)+a", from = "a", to = "b"))
    }

    @Test
    fun `改名不会误伤更长的标识符和函数调用`() {
        // 整体标识符匹配：`a` 不能改掉 `ab` / `aa` 里的那一段
        assertEquals("ab+ax", renameIdentifierIn("ab+ax", from = "a", to = "z"))
        assertEquals("AA", renameIdentifierIn("AA", from = "A", to = "B"))
        // 后面紧跟 `(` 的是**函数调用**的名字（函数和对象是两个命名空间）：
        // 用户完全可能既有函数 `a(x)` 又有标量对象 `a`
        assertEquals("a(x)", renameIdentifierIn("a(x)", from = "a", to = "b"))
        assertEquals("a(x)+b", renameIdentifierIn("a(x)+a", from = "a", to = "b"))
        // 取坐标那种"括号里面"的名字要照改（它前面是 `(`，不是标识符字符）
        assertEquals("x(AA)", renameIdentifierIn("x(A)", from = "A", to = "AA"))
        // 空串 / 没出现 / 改成自己：原样返回，不做无用的重建
        assertEquals("", renameIdentifierIn("", from = "A", to = "B"))
        assertEquals("x+1", renameIdentifierIn("x+1", from = "A", to = "B"))
        assertEquals("a*x", renameIdentifierIn("a*x", from = "a", to = "a"))
    }

    @Test
    fun `改名之后端到端仍然取得到坐标与新标量值`() {
        // 真正的验收：改名 → 表达式同步 → 仍然折得出数值（把两半合起来跑一遍）
        val src = "x(A)+a"
        val renamed = renameIdentifierIn(renameIdentifierIn(src, "A", "B"), "a", "c")
        assertEquals("x(B)+c", renamed)
        val points2 = mapOf("A" to GPoint(1.0, 2.0), "B" to GPoint(4.0, 5.0))
        assertEquals("(4.0)+c", substitutePointCoordinates(renamed, points2))
        assertEquals("(4.0)+(5.0)", substituteScalarValues(substitutePointCoordinates(renamed, points2), mapOf("c" to 5.0)))
    }
}
