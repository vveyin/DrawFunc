package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 数学引擎的单元测试。
 *
 * 引擎刻意写成零 Android 依赖，所以这些用例全部是纯 JVM 测试，
 * 跑 `gradlew :app:testDebugUnitTest` 就能验证 —— 不需要表、不需要模拟器。
 */
class ExpressionTest {

    private fun e1(src: String, x: Double = 0.0): Double =
        Expression.compile(src, listOf("x")).eval(x)

    private fun e2(src: String, x: Double, y: Double): Double =
        Expression.compile(src, listOf("x", "y")).eval(x, y)

    @Test
    fun `运算符优先级与结合性`() {
        assertEquals(14.0, e1("2+3*4"), 1e-12)
        assertEquals(9.0, e1("(1+2)*3"), 1e-12)
        assertEquals(2.5, e1("10/4"), 1e-12)
        // 幂是右结合
        assertEquals(512.0, e1("2^3^2"), 1e-12)
        // 一元负号优先级低于幂
        assertEquals(-4.0, e1("-2^2"), 1e-12)
        assertEquals(4.0, e1("(-2)^2"), 1e-12)
        assertEquals(0.5, e1("2^-1"), 1e-12)
        // 数学取模：结果符号跟随除数
        assertEquals(1.0, e1("7%3"), 1e-12)
        assertEquals(2.0, e1("-1%3"), 1e-12)
    }

    @Test
    fun `隐式乘法`() {
        assertEquals(6.0, e1("2x", 3.0), 1e-12)
        assertEquals(0.0, e1("3sin(x)", 0.0), 1e-12)
        assertEquals(8.0, e1("(x+1)(x-1)", 3.0), 1e-12)
        assertEquals(2 * PI, e1("2π"), 1e-12)
        assertEquals(10.0, e2("xy", 2.0, 5.0), 1e-12)
    }

    @Test
    fun `LaTeX 语法可以求值`() {
        // 用户原话："LaTeX语法没有实装。"
        // 输入侧先翻成普通表达式（见 Latex），再走原来的词法/语法。
        assertEquals(0.5, e1("\\frac{1}{2}"), 1e-12)
        assertEquals(2.0, e1("\\frac{x+1}{2}", 3.0), 1e-12)
        assertEquals(3.0, e1("\\sqrt{9}"), 1e-12)
        assertEquals(2.0, e1("\\sqrt[3]{8}"), 1e-12)
        assertEquals(0.5, e1("\\sqrt{\\frac{1}{4}}"), 1e-12)
        assertEquals(2 * PI, e1("2\\pi"), 1e-12)
        assertEquals(1.0, e1("\\sin x", PI / 2), 1e-12)
        assertEquals(4.0, e1("\\left(x+1\\right)", 3.0), 1e-12)
        assertEquals(8.0, e1("x^{3}", 2.0), 1e-12)
        // 底数写成下标的对数：LaTeX 是"底数在下标"，引擎是"底数在前"
        assertEquals(3.0, e1("\\log_{2}{8}"), 1e-12)
        assertEquals(3.0, e1("\\log_2 8"), 1e-12)
        assertEquals(3.0, e1("\\log_2(8)"), 1e-12)
        // 装饰命令只留内容（引擎没有这些结构）
        assertEquals(3.0, e1("\\overline{x+1}", 2.0), 1e-12)
        assertEquals(3.0, e1("\\vec{x}", 3.0), 1e-12)
        // 角度符号不是上标
        assertEquals(30.0, e1("30^\\circ"), 1e-12)
        // 定界符 / 关系符至少能翻出来（求值会给出可读报错）。
        // 命令名后面的空格照原样留着（引擎跳过空白，不影响求值），比较时先剔掉。
        assertEquals("|x|", Latex.expand("\\lvert{x}\\rvert"))
        assertEquals("x≤1", Latex.expand("x\\le 1").replace(" ", ""))
        assertEquals("⌊x⌋", Latex.expand("\\lfloor x\\rfloor").replace(" ", ""))
        // 普通表达式一个字符都不动（快速路径）
        assertEquals("x^2+1", Latex.expand("x^2+1"))
    }

    @Test
    fun `函数可以省略括号`() {
        assertEquals(1.0, e1("sin x", PI / 2), 1e-12)
        assertEquals(3.0, e1("√9"), 1e-12)
        assertEquals(2.0, e1("√(4)"), 1e-12)
        assertEquals(2.0 * 3.0, e1("2√9"), 1e-12)
    }

    @Test
    fun `函数名和变量粘在一起也能拆开`() {
        // 用户原话："a·sinx 在没打乘号的时候被识别成 asinx，很影响体验。"
        // 词法器把连着的字母读成一个名字，所以这里要按"函数名前缀"再拆一次。
        assertEquals(1.0, e1("sinx", PI / 2), 1e-12)
        // 前缀优先：`asinx` 是 a·sin(x)（a 默认 1.0），不是反三角 asin(x)
        assertEquals(1.0, e1("asinx", PI / 2), 1e-12)
        // 参数 a 真的参与运算
        val e = Expression.compile("asinx", listOf("x"), parameters = ParameterSet.of("a" to 3.0))
        assertEquals(3.0, e.eval(PI / 2), 1e-12)
        // 最长函数名优先：`sinhx` 是 sinh(x)，不是 sin(hx)
        assertEquals(kotlin.math.sinh(1.0), e1("sinhx", 1.0), 1e-12)
        // 拆不动的照旧报错（atan2 没有单参形态）
        assertThrows(ExpressionException::class.java) { e1("atan2x") }
    }

    @Test
    fun `内置函数`() {
        assertEquals(1.0, e1("sin(π/2)"), 1e-12)
        assertEquals(1.0, e1("cos(0)"), 1e-12)
        assertEquals(1.0, e1("ln(e)"), 1e-12)
        assertEquals(2.0, e1("log(100)"), 1e-12)
        assertEquals(3.0, e1("log(2,8)"), 1e-12)
        assertEquals(4.0, e1("sqrt(16)"), 1e-12)
        assertEquals(-2.0, e1("cbrt(-8)"), 1e-12)
        assertEquals(3.0, e1("|(-3)|"), 1e-12)
        assertEquals(2.0, e1("|x|", -2.0), 1e-12)
        assertEquals(120.0, e1("5!"), 1e-12)
        assertEquals(7.0, e1("max(1,7,3)"), 1e-12)
        assertEquals(1.0, e1("min(1,7,3)"), 1e-12)
        assertEquals(3.0, e1("clamp(5,0,3)"), 1e-12)
        assertEquals(2.0, e1("mod(-1,3)"), 1e-12)
        assertEquals(-2.0, e1("root(-8,3)"), 1e-9)
        assertEquals(1.0, e1("sinc(0)"), 1e-12)
        assertEquals(0.0, e1("erf(0)"), 1e-12)
        assertEquals(24.0, e1("gamma(5)"), 1e-9)
        assertEquals(PI / 4, e1("atan2(1,1)"), 1e-12)
        assertEquals(5.0, e1("hypot(3,4)"), 1e-12)
        assertEquals(180.0, e1("deg(π)"), 1e-12)
    }

    @Test
    fun `也能吃下手写数学符号`() {
        assertEquals(6.0, e1("2×3"), 1e-12)
        assertEquals(6.0, e1("2·3"), 1e-12)
        assertEquals(3.0, e1("6÷2"), 1e-12)
        assertEquals(1.0, e1("2−1"), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, e1("∞"), 0.0)
        assertEquals(-2.0, e1("∛(-8)"), 1e-12)
        assertEquals(4.0, e1("2**2"), 1e-12)
    }

    @Test
    fun `除零得到无穷而不是异常`() {
        assertEquals(Double.POSITIVE_INFINITY, e1("1/0"), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, e1("-1/0"), 0.0)
        assertTrue(e1("sqrt(-1)").isNaN())
    }

    @Test
    fun `等式会被移项成左边减右边`() {
        val e = Expression.compileEquation("x^2+y^2=25", listOf("x", "y"))
        assertEquals(0.0, e.eval(3.0, 4.0), 1e-12)
        assertEquals(-9.0, e.eval(4.0, 0.0), 1e-12)

        val noEq = Expression.compileEquation("x^2+y^2", listOf("x", "y"))
        assertEquals(25.0, noEq.eval(3.0, 4.0), 1e-12)
    }

    @Test
    fun `报错要带位置而且说人话`() {
        assertThrows(ExpressionException::class.java) { e1("x+") }
        assertThrows(ExpressionException::class.java) { e1("(x") }
        assertThrows(ExpressionException::class.java) { e1("sin(x") }
        assertThrows(ExpressionException::class.java) { e1("foo(x)") }
        assertThrows(ExpressionException::class.java) { e1("1.2.3") }
        assertThrows(ExpressionException::class.java) { e1("sin(1,2)") }
        assertThrows(ExpressionException::class.java) { e1("") }
        assertThrows(ExpressionException::class.java) { e1("|x") }

        // 用错变量名时要提示可用变量，而不是悄悄当成 0
        val e = assertThrows(ExpressionException::class.java) {
            Expression.compile("y", listOf("x"))
        }
        assertTrue(e.message!!, e.message!!.contains("可用的变量"))

        // 函数漏括号要给专门提示（sinx / asinx 这种能拆的已经在
        // 「函数名和变量粘在一起也能拆开」里救回来了，这里用拆不动的 atan2）
        val e2 = assertThrows(ExpressionException::class.java) { e1("atan2x") }
        assertTrue(e2.message!!, e2.message!!.contains("括号"))
    }

    @Test
    fun `出错位置指向出问题的字符`() {
        val e = assertThrows(ExpressionException::class.java) { e1("1+2*cookie") }
        assertEquals(4, e.position)
    }

    // ------------------------------------------------------------------
    // 角度制（DEG）/ 弧度制（RAD）
    //
    // 手表键盘上有一个 DEG/RAD 切换键，切换后三角函数的求值必须真的跟着变。
    // 换算在**编译期**折进语法树，所以这里的断言全都是一次编译、一次求值。
    // ------------------------------------------------------------------

    /** 角度制下求值：`deg("sin(x)", 90.0)` = 1。 */
    private fun deg(src: String, x: Double): Double =
        Expression.compile(src, listOf("x"), degrees = true).eval(x)

    private fun deg2(src: String, x: Double, y: Double): Double =
        Expression.compile(src, listOf("x", "y"), degrees = true).eval(x, y)

    @Test
    fun `DEG 模式下 sin(90) 就等于 1`() {
        assertEquals(1.0, Expression.compile("sin(x)", listOf("x"), degrees = true).eval(90.0), 1e-9)
        assertEquals(1.0, deg("sin(x)", 90.0), 1e-9)
        assertEquals(0.0, deg("sin(x)", 0.0), 1e-9)
        assertEquals(0.5, deg("sin(x)", 30.0), 1e-9)
        assertEquals(-1.0, deg("sin(x)", 270.0), 1e-9)
        // 变量、常量、复合表达式一律按度理解
        assertEquals(1.0, deg("sin(2x)", 45.0), 1e-9)
        assertEquals(0.0, Expression.compile("sin(180)", listOf("x"), degrees = true).eval(0.0), 1e-9)
    }

    @Test
    fun `同一个表达式在弧度模式下仍然是老行为`() {
        assertEquals(1.0, Expression.compile("sin(x)", listOf("x"), degrees = false).eval(PI / 2), 1e-9)
        // 不传 degrees（默认参数）必须是弧度 —— 这条是向后兼容的底线
        assertEquals(1.0, Expression.compile("sin(x)", listOf("x")).eval(PI / 2), 1e-9)
        assertEquals(1.0, e1("sin(x)", PI / 2), 1e-9)
        // 反过来钉一下：弧度模式下 sin(90) 明显不是 1，
        // 免得上面那几条变成"两种模式其实一模一样"的假通过
        assertTrue(abs(Expression.compile("sin(x)", listOf("x")).eval(90.0) - 1.0) > 0.1)
    }

    @Test
    fun `DEG 模式下 cos 与 tan`() {
        assertEquals(0.5, deg("cos(x)", 60.0), 1e-9)
        assertEquals(1.0, deg("tan(x)", 45.0), 1e-9)
        assertEquals(0.0, deg("cos(x)", 90.0), 1e-9)
        assertEquals(-1.0, deg("cos(x)", 180.0), 1e-9)
        // 派生三角函数也一起换算
        assertEquals(1.0, deg("cot(x)", 45.0), 1e-9)
        assertEquals(2.0, deg("sec(x)", 60.0), 1e-9)
        assertEquals(2.0, deg("csc(x)", 30.0), 1e-9)
        // 三角恒等式在度制下同样成立（换算没把参数/结果搞错位）
        assertEquals(1.0, deg("sin(x)^2+cos(x)^2", 37.0), 1e-12)
        assertEquals(1.0, deg("tan(x)/sin(x)*cos(x)", 37.0), 1e-9)
    }

    @Test
    fun `DEG 模式下反三角函数返回度数`() {
        assertEquals(90.0, deg("asin(x)", 1.0), 1e-9)
        assertEquals(60.0, deg("acos(x)", 0.5), 1e-9)
        assertEquals(45.0, deg("atan(x)", 1.0), 1e-9)
        assertEquals(30.0, deg("atan(x)", 1.0 / kotlin.math.sqrt(3.0)), 1e-9)
        assertEquals(0.0, deg("asin(x)", 0.0), 1e-9)
        // 与正向互为逆运算：asin(sin(30°)) = 30°
        assertEquals(30.0, deg("asin(sin(x))", 30.0), 1e-9)
    }

    @Test
    fun `DEG 模式下 atan2 只换结果不换参数`() {
        // 两个参数都是坐标（普通数），只有结果从弧度变成度
        assertEquals(45.0, deg2("atan2(y,x)", 1.0, 1.0), 1e-9)
        assertEquals(135.0, deg2("atan2(y,x)", -1.0, 1.0), 1e-9)
        assertEquals(90.0, deg2("atan2(y,x)", 0.0, 1.0), 1e-9)
        // 参数绝不能被当成角度再乘一遍 π/180
        assertEquals(
            kotlin.math.atan2(1.0, 1.0) * 180.0 / PI,
            deg2("atan2(y,x)", 1.0, 1.0),
            1e-12
        )
    }

    @Test
    fun `双曲函数不受角度制影响`() {
        val srcs = listOf("sinh(x)", "cosh(x)", "tanh(x)", "coth(x)", "asinh(x)", "acosh(x)", "atanh(x)")
        for (src in srcs) {
            val rad = Expression.compile(src, listOf("x"))
            val d = Expression.compile(src, listOf("x"), degrees = true)
            for (v in listOf(-0.5, 0.0, 1.0, 1.5, 3.0)) {
                assertEquals("$src($v) 被角度制影响了", rad.eval(v), d.eval(v), 0.0)
            }
        }
        // 反双曲函数绝不能像反三角那样被乘 180/π
        assertEquals(
            kotlin.math.asinh(1.0),
            Expression.compile("asinh(x)", listOf("x"), degrees = true).eval(1.0),
            0.0
        )
    }

    @Test
    fun `deg 与 rad 两个显式换算函数不受开关影响`() {
        val srcs = listOf("deg(x)", "rad(x)", "deg(π)", "rad(180)")
        for (src in srcs) {
            val rad = Expression.compile(src, listOf("x"))
            val d = Expression.compile(src, listOf("x"), degrees = true)
            assertFalse(rad.degrees)
            assertTrue(d.degrees)
            for (v in listOf(-1.0, 0.0, 1.0, 90.0, 180.0)) {
                assertEquals("$src($v)", rad.eval(v), d.eval(v), 0.0)
            }
        }
        // 具体值再钉一遍，免得上面那段变成"两边一起错"
        assertEquals(180.0, Expression.compile("deg(π)", listOf("x"), degrees = true).eval(0.0), 1e-12)
        assertEquals(PI, Expression.compile("rad(180)", listOf("x"), degrees = true).eval(0.0), 1e-12)
    }

    @Test
    fun `deg 与 rad 不会豁免外层三角函数的换算`() {
        // 已知的、刻意保留的行为：deg/rad 自身的语义不变，
        // 但外层 sin 仍然按"当前单位制"理解它拿到的参数。
        // 所以 DEG 模式下要写 sin(deg(θ)) 才能把 θ 当弧度喂给 sin。
        val d = Expression.compile("sin(deg(x))", listOf("x"), degrees = true)
        assertEquals(1.0, d.eval(PI / 2), 1e-9)
        // 弧度模式下它就是"把 x 当度转成弧度再 sin"，跟以前一模一样
        val r = Expression.compile("sin(deg(x))", listOf("x"))
        assertEquals(kotlin.math.sin(PI / 2 * (180.0 / PI)), r.eval(PI / 2), 0.0)
    }

    @Test
    fun `等号写法与隐函数上下文也能带角度制`() {
        val e = Expression.compileEquation("y=sin(x)", listOf("x", "y"), degrees = true)
        assertEquals(0.0, e.eval(90.0, 1.0), 1e-9)
        assertTrue(e.degrees)
        // 与弧度版在同一批点上求值必须不一样，否则说明 degrees 根本没传下去
        val r = Expression.compileEquation("y=sin(x)", listOf("x", "y"))
        assertTrue(abs(r.eval(90.0, 1.0)) > 0.1)

        // 不带等号的分支同样要透传
        val noEq = Expression.compileEquation("sin(x)", listOf("x"), degrees = true)
        assertEquals(1.0, noEq.eval(90.0), 1e-9)

        // 与角度无关的等式在两种模式下完全一致
        val plainDeg = Expression.compileEquation("x^2+y^2=25", listOf("x", "y"), degrees = true)
        val plainRad = Expression.compileEquation("x^2+y^2=25", listOf("x", "y"))
        assertEquals(plainRad.eval(3.0, 4.0), plainDeg.eval(3.0, 4.0), 0.0)
    }

    @Test
    fun `不传 degrees 时与旧行为逐位一致`() {
        val cases = listOf(
            "sin(x)" to listOf(-2.0, -0.5, 0.0, 0.5, 1.0, PI / 2),
            "cos(x)+tan(x)" to listOf(-1.0, 0.0, 0.7, 2.0),
            "asin(x)" to listOf(-1.0, -0.5, 0.0, 0.5, 1.0),
            "atan(x)" to listOf(-3.0, 0.0, 1.0),
            "2sin(x)cos(x)" to listOf(-2.0, 0.0, 1.0, 3.0),
            "sinh(x)+cosh(x)" to listOf(-1.0, 0.0, 2.0),
            "deg(x)+rad(x)" to listOf(-180.0, 0.0, 45.0),
            "sinc(x)" to listOf(0.0, 1.0, 2.0),
            "sqrt(x^2)+|x|" to listOf(-4.0, 9.0),
            "x!" to listOf(0.0, 1.0, 5.0),
            "2^3^2+x%3" to listOf(0.0, 7.0)
        )
        for ((src, vals) in cases) {
            val implicitRad = Expression.compile(src, listOf("x"))
            val explicitRad = Expression.compile(src, listOf("x"), degrees = false)
            assertFalse(implicitRad.degrees)
            assertFalse(explicitRad.degrees)
            for (v in vals) {
                // delta = 0.0：要求逐位相同，不是"差不多"
                assertEquals("$src @ x=$v", implicitRad.eval(v), explicitRad.eval(v), 0.0)
            }
        }
        assertEquals(2 * PI, Expression.compile("2π").eval(0.0), 0.0)
    }

    @Test
    fun `角度制是编译期开关不是全局状态`() {
        // 两个表达式共存、交替求值，互不影响（Expression 会被后台线程并发求值）
        val inDegrees = Expression.compile("sin(x)+cos(x)", listOf("x"), degrees = true)
        val inRadians = Expression.compile("sin(x)+cos(x)", listOf("x"), degrees = false)
        assertTrue(inDegrees.degrees)
        assertFalse(inRadians.degrees)
        repeat(3) {
            assertEquals(kotlin.math.sqrt(2.0), inDegrees.eval(45.0), 1e-9)
            assertEquals(1.0, inRadians.eval(0.0), 1e-9)
            assertEquals(kotlin.math.sin(90.0) + kotlin.math.cos(90.0), inRadians.eval(90.0), 0.0)
        }
        // 先编弧度、后编角度，先前那个不受任何影响
        val before = Expression.compile("sin(x)", listOf("x"))
        Expression.compile("sin(x)", listOf("x"), degrees = true)
        assertEquals(1.0, before.eval(PI / 2), 1e-9)
    }

    // ------------------------------------------------------------------
    // 可绑定参数 a b c d
    //
    // 键盘的"字母"页要加 a b c d 四个键，用户说"预留四个变量，等下讲干什么"。
    // 本轮只要求它们能被解析、能算出一个数：默认值 1.0 是**临时占位**
    // （现在没有任何界面能改它们的值），所以 a*x+b 必须直接画成 x+1。
    // 取值在编译期折成常量，不进采样变量数组（见 ParameterSet 的注释）。
    // ------------------------------------------------------------------

    @Test
    fun `可绑定参数 a b c d 的默认值都是 1`() {
        // 1.0 是乘法和加法的单位元：不给任何绑定时 a*x+b 必须逐位等于 x+1
        for (x in listOf(-3.0, -0.5, 0.0, 1.0, 7.25)) {
            assertEquals("x=$x", x + 1.0, e1("a*x+b", x), 0.0)
        }
        assertEquals(1.0, e1("a"), 0.0)
        assertEquals(1.0, e1("d"), 0.0)
        assertEquals(4.0, e1("a+b+c+d"), 0.0)
        assertEquals(1.0, e1("a*b*c*d"), 0.0)
        // 隐式乘法与多字母写法也要认（ax 走"拆成单个字符乘积"那条兜底）
        assertEquals(2.0, e1("2a"), 0.0)
        assertEquals(2.0, e1("ax", 2.0), 0.0)
        assertEquals(2.0, e1("xa", 2.0), 0.0)
        assertEquals(1.0, e1("ab"), 0.0)
    }

    @Test
    fun `显式绑定取值时结果真的跟着变`() {
        val p = ParameterSet.of("a" to 2.0, "b" to -3.0)
        val f = Expression.compile("a*x+b", listOf("x"), parameters = p)
        assertEquals(1.0, f.eval(2.0), 0.0)     // 2*2-3
        assertEquals(-3.0, f.eval(0.0), 0.0)    // 2*0-3
        // 没显式给值的 c d 仍然取临时占位的 1.0
        assertEquals(3.0, Expression.compile("a*x+b+c+d", listOf("x"), parameters = p).eval(2.0), 0.0)
        // 同一份源码换一组绑定必须算出别的数 —— 证明 a 不是被硬编码成 1
        val def = Expression.compile("a*x+b", listOf("x"))
        assertEquals(3.0, def.eval(2.0), 0.0)
        assertTrue(abs(def.eval(2.0) - f.eval(2.0)) > 0.5)
        // 位置参数写法（不带参数名）也要透传
        assertEquals(1.0, Expression.compile("a*x+b", listOf("x"), false, p).eval(2.0), 0.0)
        // 等号写法同样要透传
        val eq = Expression.compileEquation("y=a*x+b", listOf("x", "y"), parameters = p)
        assertEquals(0.0, eq.eval(2.0, 1.0), 0.0)
        // 编译时用的那组取值要能查出来（和 degrees 一样，只是记录）
        assertEquals(2.0, f.parameters.explicit["a"]!!, 0.0)
    }

    @Test
    fun `ParameterSet 没给值的参数取默认值，名字写错立刻报错`() {
        val p = ParameterSet.of("a" to 5.0)
        assertEquals(5.0, p.value("a")!!, 0.0)
        assertEquals(1.0, p.value("b")!!, 0.0)  // 没给 → 临时占位的 1.0
        assertNull(p.value("z"))                // 不是可绑定参数 → 交回"未知名字"那条报错路径
        assertEquals(listOf("a", "b", "c", "d"), ParameterSet.BINDABLE)
        // 一个都不传时与显式空集合完全等价
        val empty = Expression.compile("a+b+c+d", listOf("x"), parameters = ParameterSet())
        assertEquals(4.0, empty.eval(0.0), 0.0)
        // 参数名写错是**调用方**的 bug：必须立刻炸，不能悄悄忽略
        // （否则界面上会出现"拖了滑块但图没变"这种查不出来的问题）
        // 1.2.69 起小写标识符（含多字母）都合法，所以这里换成真正非法的两种：
        // 大写开头（留给几何点名）和数字开头（那是字面量）。
        val ex = assertThrows(IllegalArgumentException::class.java) { ParameterSet.of("Alpha" to 2.0) }
        assertTrue(ex.message!!, ex.message!!.contains("Alpha"))
        assertThrows(IllegalArgumentException::class.java) { ParameterSet.of("2a" to 2.0) }
    }

    @Test
    fun `ParameterSet 接受任意小写标识符做参数名`() {
        // 用户原话（1.2.69）："我要做成输入 s、r1、r2、opt、rv 这样的字母都能创建滑动条"。
        val p = ParameterSet.of("s" to 2.0, "r1" to 3.0, "opt" to 4.0, "rv" to 5.0)
        assertEquals(2.0, p.value("s")!!, 0.0)
        assertEquals(3.0, p.value("r1")!!, 0.0)
        assertEquals(4.0, p.value("opt")!!, 0.0)
        assertEquals(5.0, p.value("rv")!!, 0.0)
        // 下划线也在词法层的标识符字符集里
        assertEquals(7.0, ParameterSet.of("r_1" to 7.0).value("r_1")!!, 0.0)
        // 名字合法性判据本身也钉一下（UI 那边 `bindableParametersIn` 复用同一个函数）
        for (ok in listOf("a", "s", "r1", "opt", "r_1", "abc123")) {
            assertTrue(ok, ParameterSet.isParameterName(ok))
        }
        for (bad in listOf("", "A", "Alpha", "2a", "θ", "a b", "1e5")) {
            assertTrue(bad, !ParameterSet.isParameterName(bad))
        }
        // 多字母参数真的能折进表达式并求值
        val f = Expression.compile("s*x+r1", listOf("x"), parameters = p)
        assertEquals(7.0, f.eval(2.0), 0.0) // 2*2 + 3
    }

    @Test
    fun `四种模式的变量上下文里 a b c d 都能解析`() {
        // 每个模式允许的采样变量不同（EXPLICIT 只有 x、POLAR 是 θ/t……），
        // 但 a b c d 是可绑定参数，四种模式下都该认。
        // 期望值按"所有采样变量都喂 2、四个参数都取默认 1"算。
        val cases = listOf(
            Triple(PlotMode.EXPLICIT.variables, "a*x+b", 3.0),
            Triple(PlotMode.POLAR.variables, "a*θ+b", 3.0),
            Triple(PlotMode.PARAMETRIC.variables, "a*t+b", 3.0),
            Triple(PlotMode.IMPLICIT.variables, "a*x+b*y+c", 5.0)
        )
        for ((vars, src, expect) in cases) {
            val f = Expression.compile(src, vars)
            assertEquals(
                "$src（变量：${vars.joinToString("、")}）",
                expect,
                f.evalArray(DoubleArray(vars.size) { 2.0 }),
                0.0
            )
        }
        // 极坐标下 θ 和 t 仍然是两个独立的采样位置，没被 a b c d 顶掉
        assertEquals(8.0, Expression.compile("θ+t", PlotMode.POLAR.variables).eval(3.0, 5.0), 0.0)
    }

    @Test
    fun `z 这种真正的未知名字仍然报错而且要点名`() {
        for (name in listOf("z", "w", "zw")) {
            val e = assertThrows(ExpressionException::class.java) {
                Expression.compile("a*x+$name", listOf("x"))
            }
            assertTrue("错误信息里应该点名 $name：${e.message}", e.message!!.contains(name))
        }
        // 提示里还要能看到可用的名字：多了 a b c d 不能让提示变含糊，
        // 也不能把"未知变量"的检查放宽成"任何单个字母都行"
        val e = assertThrows(ExpressionException::class.java) { Expression.compile("z", listOf("x")) }
        assertTrue(e.message!!, e.message!!.contains("可用的变量"))
        assertTrue(e.message!!, e.message!!.contains("可绑定参数"))
    }

    @Test
    fun `加了 a b c d 之后 e π θ t x y 的老行为一点没变`() {
        // e 仍然是自然常数（不是可绑定参数），2e 也不能被当成残缺的科学计数法
        assertEquals(kotlin.math.E, e1("e"), 0.0)
        assertEquals(2.0 * kotlin.math.E, e1("2e"), 0.0)
        assertEquals(2 * PI, e1("2π"), 0.0)
        assertEquals(2.0 * PI * kotlin.math.E, e1("2πe"), 1e-12)
        // 万一有调用方自己把 a 声明成了采样变量，仍然"变量优先"，老行为不变
        assertEquals(7.0, Expression.compile("a", listOf("a")).eval(7.0), 0.0)
        // x y 的位置语义不变
        assertEquals(6.0, Expression.compile("x*y", PlotMode.IMPLICIT.variables).eval(2.0, 3.0), 0.0)
    }

    // ------------------------------------------------------------------
    // 调用**用户定义过的函数**
    //
    // 用户报的 bug：他建了 `a(x) = sin(x)`，又在 `b(x)` 里写 `2a(x)`，
    // 画出来的却是 `y = 2x`。原因是 `a` 当时只是可绑定参数（缺省 1.0），
    // 于是 `2a(x)` = `2 * a * x` = `2 * 1 * x`。
    //
    // 本轮定下来的语义：
    //   * **带括号**的名字才是调用：`a(x)`、`a(x+1)`、`2a(x)`、`a(a(x))`
    //   * 不带括号的 `a` 仍然是可绑定参数（缺省 1.0）—— `a*x+b` 这类老写法不变
    //   * 只有显函数 y=f(x) 能被调用；其它模式给明确中文报错，不静默算错
    //   * 被调用函数用它**自己的**度制 / 参数表；实参用**调用方**的采样变量
    //   * 自引用与相互调用在**编译期**就报错，绝不栈溢出
    // ------------------------------------------------------------------

    /** 求值小工具：`x` 是唯一的采样变量。 */
    private fun c(
        cs: CallableSet,
        src: String,
        x: Double,
        parameters: ParameterSet = ParameterSet.DEFAULT,
        degrees: Boolean = false
    ): Double = Expression.compile(src, listOf("x"), degrees, parameters, cs).eval(x)

    @Test
    fun `别的函数里可以调用用户定义过的函数`() {
        // a(x) = sin(x)，现在的 b(x) 里写 2a(x)
        val cs = CallableSet.of(CallableFn("a", "sin(x)"))
        for (x in listOf(-2.0, -0.5, 0.0, 0.3, 1.0, 2.5)) {
            // delta = 0.0：要求逐位相同，不是"差不多"
            assertEquals("a(x) @ $x", sin(x), c(cs, "a(x)", x), 0.0)
            assertEquals("2a(x) @ $x（用户报的 bug）", 2.0 * sin(x), c(cs, "2a(x)", x), 0.0)
            assertEquals("a(x+1) @ $x", sin(x + 1.0), c(cs, "a(x+1)", x), 0.0)
            assertEquals("a(a(x)) @ $x", sin(sin(x)), c(cs, "a(a(x))", x), 0.0)
            assertEquals("2a(x)+1 @ $x", 2.0 * sin(x) + 1.0, c(cs, "2a(x)+1", x), 0.0)
            assertEquals("a(x)*a(x) @ $x", sin(x) * sin(x), c(cs, "a(x)*a(x)", x), 0.0)
        }

        // 被调用函数自己也可以调用别的函数（多级链路）
        val two = CallableSet.of(CallableFn("a", "sin(x)"), CallableFn("b", "2a(x)"))
        assertEquals(2.0 * sin(1.0), c(two, "b(x)", 1.0), 0.0)
        // 实参在**调用方**的帧里算：这里的 x+1 是 b 的 x
        assertEquals(2.0 * sin(2.0), c(two, "b(x+1)", 1.0), 0.0)

        // `Expression` 也把"能调谁"记录下来（和 degrees / parameters 一样只是记录）
        assertTrue(Expression.compile("a(x)", listOf("x"), callables = cs).callables.contains("a"))
    }

    @Test
    fun `可调用函数的名字不带括号时仍然是可绑定参数`() {
        val cs = CallableSet.of(CallableFn("a", "sin(x)"))
        val p = ParameterSet.of("a" to 3.0)

        // 带括号 = 调用（参数 a=3 完全不参与）
        assertEquals(sin(2.0), c(cs, "a(x)", 2.0, p), 0.0)
        assertEquals(2.0 * sin(2.0), c(cs, "2a(x)", 2.0, p), 0.0)

        // 不带括号 = 可绑定参数：老行为一点没变
        assertEquals(3.0, c(cs, "a", 0.0, p), 0.0)
        assertEquals(6.0, c(cs, "2a", 0.0, p), 0.0)
        assertEquals(3.0 * 2.0 + 1.0, c(cs, "a*x+b", 2.0, p), 0.0)
        assertEquals(6.0, c(cs, "ax", 2.0, p), 0.0)          // 拆字母那条兜底也不受影响
        assertEquals(6.0, c(cs, "xa", 2.0, p), 0.0)
        // 缺省 1.0 也要照旧（表里有 a 也不影响）
        assertEquals(1.0, c(cs, "a", 0.0), 0.0)
        assertEquals(3.0, c(cs, "a*x+b", 2.0), 0.0)
        // 常量、变量也不受影响
        assertEquals(2 * PI, c(cs, "2π", 0.0), 0.0)
        assertEquals(2.0 * kotlin.math.E, c(cs, "2e", 0.0), 0.0)
    }

    @Test
    fun `可调用名取的是名字里括号之前那一截`() {
        assertEquals("a", CallableFn.callableNameOf("a(x)"))
        assertEquals("aa", CallableFn.callableNameOf("aa(x)"))   // 第 27 个函数叫 aa(x)
        assertEquals("a", CallableFn.callableNameOf("a (x)"))
        assertEquals("g", CallableFn.callableNameOf("g"))
        assertEquals("g", CallableFn.callableNameOf("  g  "))
        // 取不出名字的（还没命名、或者名字不像名字）→ 这条函数调不到
        assertNull(CallableFn.callableNameOf(""))
        assertNull(CallableFn.callableNameOf("   "))
        assertNull(CallableFn.callableNameOf("(x)"))
        assertNull(CallableFn.callableNameOf("3(x)"))
        assertNull(CallableFn.callableNameOf("a+b(x)"))

        // 界面上的函数对象直接转表：名字 `a(x)` → 可调用名 `a`，`y=` 前缀会被剥掉
        val cs = CallableSet.ofFunctions(
            listOf(
                PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", name = "a(x)"),
                PlotFunction(2, PlotMode.EXPLICIT, "y = x^2", name = "g"),
                PlotFunction(3, PlotMode.EXPLICIT, "x+1", name = "")     // 还没命名 → 进不了表
            )
        )
        assertEquals(setOf("a", "g"), cs.names)
        assertEquals("sin(x)", cs["a"]!!.expression)
        assertEquals("x^2", cs["g"]!!.expression)
        assertEquals(9.0, c(cs, "g(x)", 3.0), 0.0)
        // 一个用户函数当另一个的实参：g(a(x)) = sin(x)^2
        assertEquals(sin(5.0) * sin(5.0), c(cs, "g(a(x))", 5.0), 0.0)
    }

    @Test
    fun `调用不存在的函数要点名报错并列出能调的函数`() {
        val cs = CallableSet.of(CallableFn("a", "sin(x)"), CallableFn("bb", "x^2"))
        val e = assertThrows(ExpressionException::class.java) {
            Expression.compile("z(x)", listOf("x"), callables = cs)
        }
        assertTrue(e.message!!, e.message!!.contains("z"))
        assertTrue(e.message!!, e.message!!.contains("可调用的函数"))
        assertTrue(e.message!!, e.message!!.contains("a(x)"))
        assertTrue(e.message!!, e.message!!.contains("bb(x)"))
        // 多字母名字不能被拆成乘积混过去
        assertThrows(ExpressionException::class.java) {
            Expression.compile("zz(x)", listOf("x"), callables = cs)
        }
        // 没启用这个功能时，提示里不该冒出"可调用的函数"
        val e2 = assertThrows(ExpressionException::class.java) {
            Expression.compile("z(x)", listOf("x"))
        }
        assertFalse(e2.message!!, e2.message!!.contains("可调用的函数"))

        // 表里有、但没写括号 → 提示"要带括号"
        val e3 = assertThrows(ExpressionException::class.java) {
            Expression.compile("f", listOf("x"), callables = CallableSet.of(CallableFn("f", "x^2")))
        }
        assertTrue(e3.message!!, e3.message!!.contains("f(x)"))
    }

    @Test
    fun `自引用和相互调用变成中文化报错，绝不栈溢出`() {
        // 自引用：a(x) = a(x)+1
        val self = CallableSet.of(CallableFn("a", "a(x)+1"))
        for (src in listOf("a(x)", "1+a(x)", "a(a(x))")) {
            val e = assertThrows(ExpressionException::class.java) {
                Expression.compile(src, listOf("x"), callables = self)
            }
            assertTrue("$src 的报错应该点明「调用了自己」：${e.message}", e.message!!.contains("自己"))
        }

        // 相互调用：a 调 b、b 调 a
        val ping = CallableSet.of(CallableFn("a", "b(x)"), CallableFn("b", "a(x)"))
        val e2 = assertThrows(ExpressionException::class.java) {
            Expression.compile("a(x)", listOf("x"), callables = ping)
        }
        assertTrue("${e2.message}", e2.message!!.contains("循环"))
        assertTrue("要点名链上的函数：${e2.message}", e2.message!!.contains("a") && e2.message!!.contains("b"))

        // 三连环也拦得住
        val ring = CallableSet.of(
            CallableFn("a", "b(x)"), CallableFn("b", "c(x)"), CallableFn("c", "a(x)")
        )
        val e3 = assertThrows(ExpressionException::class.java) {
            Expression.compile("a(x)", listOf("x"), callables = ring)
        }
        assertTrue("${e3.message}", e3.message!!.contains("循环"))

        // 环只影响真正成环的那条链：同一张表里别的函数照样能算
        val mixed = CallableSet.of(
            CallableFn("a", "b(x)"), CallableFn("b", "a(x)"), CallableFn("g", "x+1")
        )
        assertEquals(4.0, c(mixed, "g(x)", 3.0), 0.0)
    }

    @Test
    fun `超过深度上限的链式调用在编译期就报错`() {
        // 34 层链：f0(x) → f1(x) → … → f33(x) = x。
        // 上限是 32，所以必须报"嵌套太深"，而不是递归到栈溢出。
        val n = Expression.MAX_USER_CALL_DEPTH + 2
        val chain = CallableSet.ofList(
            (0 until n).map { i ->
                CallableFn("f$i", if (i == n - 1) "x" else "f${i + 1}(x)")
            }
        )
        val e = assertThrows(ExpressionException::class.java) {
            Expression.compile("f0(x)", listOf("x"), callables = chain)
        }
        assertTrue("${e.message}", e.message!!.contains("嵌套太深"))

        // 宽度爆炸（每个函数把下一个调用两次，指数展开）也要拦得住，不能把表卡死
        val wide = CallableSet.ofList(
            (0 until 12).map { i ->
                CallableFn("w$i", if (i == 11) "x" else "w${i + 1}(x)+w${i + 1}(x)")
            }
        )
        val e2 = assertThrows(ExpressionException::class.java) {
            Expression.compile("w0(x)", listOf("x"), callables = wide)
        }
        assertTrue("${e2.message}", e2.message!!.contains("太多"))
    }

    @Test
    fun `求值期的调用深度兜底：超深的 AST 报错而不是栈溢出`() {
        // 编译期已经拦住了深嵌套，这是**兜底**那一道：手工搭一棵超过上限的
        // UserCall 链，求值必须给中文报错 —— 手表上一次 StackOverflowError 就是崩溃。
        val id = Expression.compile("x", listOf("x"))
        fun chain(depth: Int): Node {
            var node: Node = Node.Var(0)
            for (d in depth downTo 1) node = Node.UserCall("a", id, node, d)
            return node
        }
        val limit = Expression.MAX_USER_CALL_DEPTH
        // 恰好到上限：还能算
        assertEquals(5.0, Expression(chain(limit), listOf("x")).eval(5.0), 0.0)
        // 超一层：报错，而且绝不是 StackOverflowError
        val e = assertThrows(ExpressionException::class.java) {
            Expression(chain(limit + 1), listOf("x")).eval(1.0)
        }
        assertTrue("${e.message}", e.message!!.contains("深"))
    }

    @Test
    fun `只有 y=f(x) 的函数能被调用，别的模式给明确报错`() {
        val cs = CallableSet.of(
            CallableFn("p", "2cos(3θ)", mode = PlotMode.POLAR),
            CallableFn("q", "cos(t)", mode = PlotMode.PARAMETRIC),
            CallableFn("F", "x^2+y^2-1", mode = PlotMode.IMPLICIT)
        )
        for (name in listOf("p", "q", "F")) {
            val e = assertThrows(ExpressionException::class.java) {
                Expression.compile("$name(x)+1", listOf("x"), callables = cs)
            }
            assertTrue("要点名 $name：${e.message}", e.message!!.contains(name))
            assertTrue("要说清是什么形状才行：${e.message}", e.message!!.contains("y=f(x)"))
            assertTrue("要有「不能被调用」这句话：${e.message}", e.message!!.contains("不能被调用"))
        }
        // 这些名字**不带括号**时仍然走老路径：不认识就报"不认识的名字"
        val e2 = assertThrows(ExpressionException::class.java) {
            Expression.compile("p+1", listOf("x"), callables = cs)
        }
        assertTrue("${e2.message}", e2.message!!.contains("p"))

        // 同一张表里显函数照样能调（只有非显函数被拒）
        val ok = CallableSet.of(
            CallableFn("a", "sin(x)"),
            CallableFn("p", "2cos(3θ)", mode = PlotMode.POLAR)
        )
        assertEquals(sin(1.0), c(ok, "a(x)", 1.0), 0.0)
    }

    @Test
    fun `被调用的函数用它自己的度制求值`() {
        // a 是 DEG、调用方是 RAD：a(90) 必须按 a 的设置算 = sin(90°) = 1
        val degA = CallableSet.of(CallableFn("a", "sin(x)", degrees = true))
        assertEquals(1.0, Expression.compile("a(90)", listOf("x"), callables = degA).eval(0.0), 1e-9)
        assertEquals(1.0, Expression.compile("a(x)", listOf("x"), callables = degA).eval(90.0), 1e-9)
        // 实参用调用方的语法算（这里的 x 就是调用方的 x=90）
        assertEquals(1.0, Expression.compile("a(x+0)", listOf("x"), callables = degA).eval(90.0), 1e-9)
        // 调用方自己的 sin 仍然是弧度 —— 被调用函数的度制不能倒过来污染调用方
        assertTrue(
            "调用方的 sin 不该跟着被调用函数变成度制",
            abs(Expression.compile("sin(x)", listOf("x"), callables = degA).eval(90.0) - 1.0) > 0.1
        )

        // 反过来：a 是 RAD、调用方是 DEG → a(90) = sin(90 弧度)
        val radA = CallableSet.of(CallableFn("a", "sin(x)", degrees = false))
        assertEquals(
            sin(90.0),
            Expression.compile("a(90)", listOf("x"), degrees = true, callables = radA).eval(0.0),
            0.0
        )
        // 调用方自己的 DEG 照样生效
        assertEquals(
            1.0,
            Expression.compile("sin(90)", listOf("x"), degrees = true, callables = radA).eval(0.0),
            1e-9
        )

        // degrees = null（没给）= 跟随调用方：调用方 DEG 时 a(90) 也是 1
        val follow = CallableSet.of(CallableFn("a", "sin(x)"))
        assertEquals(
            1.0,
            Expression.compile("a(90)", listOf("x"), degrees = true, callables = follow).eval(0.0),
            1e-9
        )
        assertEquals(sin(90.0), c(follow, "a(90)", 0.0), 0.0)
    }

    @Test
    fun `被调用的函数用的是当前参数表`() {
        // 函数体里也有可绑定参数：a(x) = c*x
        val cs = CallableSet.of(CallableFn("a", "c*x"))
        val p = ParameterSet.of("c" to 5.0)
        // CallableFn.parameters 没给（null）→ 跟随调用方当前那张表
        assertEquals(
            5.0 * 2.0,
            Expression.compile("a(x)", listOf("x"), parameters = p, callables = cs).eval(2.0),
            0.0
        )
        assertEquals(
            5.0 * 2.0 + 5.0,
            Expression.compile("a(x)+c", listOf("x"), parameters = p, callables = cs).eval(2.0),
            0.0
        )
        // 显式给一条函数自己的参数表也认
        val own = CallableSet.of(CallableFn("a", "c*x", parameters = ParameterSet.of("c" to 2.0)))
        assertEquals(
            2.0 * 3.0,
            Expression.compile("a(x)", listOf("x"), parameters = p, callables = own).eval(3.0),
            0.0
        )
    }

    @Test
    fun `函数体带 y= 前缀或者还没写都要有明确结果`() {
        // 用户很可能把 `y = sin(x)` 整串抄进编辑框：表那边会剥掉前缀
        val cs = CallableSet.ofFunctions(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "y = sin(x)", name = "a(x)"))
        )
        assertEquals(sin(1.0), c(cs, "a(x)", 1.0), 0.0)

        // 函数体是空的 → 报错要点名，而不是算成 0
        val empty = CallableSet.of(CallableFn("a", "   "))
        val e = assertThrows(ExpressionException::class.java) {
            Expression.compile("a(x)", listOf("x"), callables = empty)
        }
        assertTrue("${e.message}", e.message!!.contains("a(x)"))
        assertTrue("${e.message}", e.message!!.contains("还没写表达式"))

        // 参数个数不对也要说清楚（用户函数永远是一元的）
        val e2 = assertThrows(ExpressionException::class.java) {
            Expression.compile("a(x,2)", listOf("x"), callables = CallableSet.of(CallableFn("a", "x")))
        }
        assertTrue("${e2.message}", e2.message!!.contains("一个参数"))
    }

    @Test
    fun `等号写法与隐函数上下文里也能调用用户函数`() {
        val cs = CallableSet.of(CallableFn("a", "x^2"))
        // y = a(x) 在隐函数上下文里：x=3, y=9 → 9-9 = 0
        val eq = Expression.compileEquation("y=a(x)", listOf("x", "y"), callables = cs)
        assertEquals(0.0, eq.eval(3.0, 9.0), 0.0)
        // 不带等号的分支同样透传
        assertEquals(4.0, Expression.compileEquation("a(x)", listOf("x"), callables = cs).eval(2.0), 0.0)
        // 两个变量的上下文里，实参照样用调用方的变量
        assertEquals(25.0, Expression.compile("a(x)+a(y)", listOf("x", "y"), callables = cs).eval(3.0, 4.0), 0.0)
    }

    @Test
    fun `调用方是极坐标描点还是隐函数都不影响调用`() {
        // 被调用函数必须是显函数，但**调用方**是什么模式无所谓：
        // 四种模式的表达式都只是"用调用方的采样变量算出一个实参"。
        val cs = CallableSet.of(CallableFn("a", "x^2"))
        // 极坐标：θ 和 t 是两个采样位置
        val polar = Expression.compile("a(θ)", PlotMode.POLAR.variables, callables = cs)
        assertEquals(9.0, polar.eval(3.0, 0.0), 0.0)
        assertEquals(9.0, Expression.compile("a(t)", PlotMode.POLAR.variables, callables = cs).eval(0.0, 3.0), 0.0)
        // 描点：x(t) = 2a(t)
        val par = Expression.compile("2a(t)", PlotMode.PARAMETRIC.variables, callables = cs)
        assertEquals(18.0, par.eval(3.0), 0.0)
        // 隐函数：F(x,y) = a(x)+a(y)-25，x=3 y=4 → 0
        val imp = Expression.compileEquation("a(x)+a(y)=25", PlotMode.IMPLICIT.variables, callables = cs)
        assertEquals(0.0, imp.eval(3.0, 4.0), 0.0)
    }
}

class ViewportTest {

    @Test
    fun `屏幕与世界坐标可以来回换算`() {
        val vp = Viewport.forRange(200f, 300f, -5.0, 5.0)
        for (px in listOf(0.0, 37.0, 100.0, 200.0)) {
            assertEquals(px, vp.toScreenX(vp.toWorldX(px)).toDouble(), 1e-6)
        }
        for (py in listOf(0.0, 55.0, 300.0)) {
            assertEquals(py, vp.toScreenY(vp.toWorldY(py)).toDouble(), 1e-6)
        }
    }

    @Test
    fun `等比例缩放：x 和 y 的像素密度一致`() {
        val vp = Viewport.forRange(233f, 233f, -10.0, 10.0)
        val dxWorld = vp.toWorldX(100.0) - vp.toWorldX(0.0)
        val dyWorld = vp.toWorldY(0.0) - vp.toWorldY(100.0)
        assertEquals(dxWorld, dyWorld, 1e-12)
    }

    @Test
    fun `缩放时焦点下的世界坐标必须钉住不动`() {
        val vp0 = Viewport.forRange(233f, 233f, -10.0, 10.0)
        val fx = 40f
        val fy = 200f
        val wx = vp0.toWorldX(fx.toDouble())
        val wy = vp0.toWorldY(fy.toDouble())

        val vp1 = vp0.zoomBy(2.5, fx, fy)

        assertEquals(wx, vp1.toWorldX(fx.toDouble()), 1e-9)
        assertEquals(wy, vp1.toWorldY(fy.toDouble()), 1e-9)
        assertTrue(vp1.unitsPerPixel < vp0.unitsPerPixel)
    }

    @Test
    fun `缩小再放大能回到原处`() {
        val vp0 = Viewport.forRange(233f, 233f, -10.0, 10.0)
        val vp1 = vp0.zoomBy(0.5, 116f, 116f).zoomBy(2.0, 116f, 116f)
        assertEquals(vp0.centerX, vp1.centerX, 1e-9)
        assertEquals(vp0.centerY, vp1.centerY, 1e-9)
        assertEquals(vp0.unitsPerPixel, vp1.unitsPerPixel, 1e-12)
    }

    @Test
    fun `平移方向符合直觉`() {
        val vp = Viewport.forRange(100f, 100f, -10.0, 10.0)
        val upp = vp.unitsPerPixel
        // 手指向右拖 10px，视野里的世界坐标应该整体左移
        val panned = vp.panBy(10f, 0f)
        assertEquals(vp.toWorldX(50.0) - 10 * upp, panned.toWorldX(50.0), 1e-9)
        // 手指向下拖，世界坐标整体上移
        val panned2 = vp.panBy(0f, 10f)
        assertEquals(vp.toWorldY(50.0) + 10 * upp, panned2.toWorldY(50.0), 1e-9)
    }

    @Test
    fun `缩放有上限不会溢出`() {
        var vp = Viewport.forRange(233f, 233f, -10.0, 10.0)
        repeat(200) { vp = vp.zoomBy(2.0, 116f, 116f) }
        assertTrue(vp.unitsPerPixel >= Viewport.MIN_UNITS_PER_PIXEL)
        repeat(400) { vp = vp.zoomBy(0.5, 116f, 116f) }
        assertTrue(vp.unitsPerPixel <= Viewport.MAX_UNITS_PER_PIXEL)
    }

    @Test
    fun `网格步长落在合理像素区间`() {
        for (upp in listOf(0.001, 0.01, 0.043, 1.0, 7.0, 123.0)) {
            val vp = Viewport.forRange(233f, 233f, -1.0, 1.0).copy(unitsPerPixel = upp)
            val px = vp.gridStep / upp
            assertTrue("upp=$upp 时网格间距 $px px 太离谱", px in 15.0..250.0)
        }
    }
}

class PlotterTest {

    private fun vp(size: Float = 300f, half: Double = 2.0) =
        Viewport.forRange(size, size, -half, half)

    private fun maxRadiusError(result: PlotResult, vp: Viewport, radiusWorld: Double): Double {
        val cx = vp.toScreenX(0.0).toDouble()
        val cy = vp.toScreenY(0.0).toDouble()
        val expect = radiusWorld / vp.unitsPerPixel
        var err = 0.0
        for (c in result.curves) {
            var i = 0
            while (i < c.points.size) {
                val d = hypot(c.points[i] - cx, c.points[i + 1] - cy)
                err = max(err, abs(d - expect))
                i += 2
            }
        }
        return err
    }

    @Test
    fun `显函数采样点都落在抛物线上`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val r = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "x^2")), v, Quality.NORMAL)
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(1, r.curves.size)

        val pts = r.curves[0].points
        // 覆盖整个屏幕宽度
        assertEquals(0f, pts[0], 0.001f)
        assertTrue(pts[pts.size - 2] >= 199f)

        var i = 0
        while (i < pts.size) {
            val wx = v.toWorldX(pts[i].toDouble())
            val wy = v.toWorldY(pts[i + 1].toDouble())
            // 屏幕外的采样点被钳制过，只校验屏幕内的
            if (wy > v.worldBottom + 0.5 && wy < v.worldTop - 0.5) {
                assertEquals("x=$wx", wx * wx, wy, 0.05)
            }
            i += 2
        }
    }

    @Test
    fun `显函数接受 y= 前缀`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val bare = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "x^2")), v)
        val withPrefix = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "y = x^2")), v)
        assertTrue(withPrefix.errors.isEmpty())
        assertEquals(bare.totalPoints, withPrefix.totalPoints)
    }

    @Test
    fun `竖直渐近线必须断线`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val r = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "1/x")), v, Quality.NORMAL)
        assertTrue("1/x 应该被渐近线切成至少两段，实际 ${r.curves.size} 段", r.curves.size >= 2)
    }

    @Test
    fun `tan 不会画出假的竖线`() {
        val v = Viewport.forRange(200f, 200f, -PI, PI)
        val r = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "tan(x)")), v, Quality.NORMAL)
        assertTrue(r.curves.isNotEmpty())
        val limit = 2.0 * v.heightPx + 1.0
        for (c in r.curves) {
            var i = 2
            while (i < c.points.size) {
                val dy = abs((c.points[i + 1] - c.points[i - 1]).toDouble())
                assertTrue("折线内部出现 $dy px 的跳变，说明渐近线没断开", dy <= limit)
                i += 2
            }
        }
    }

    @Test
    fun `极坐标 r=1 是圆`() {
        val v = vp()
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.POLAR, "1", tMin = 0.0, tMax = 2 * PI)),
            v,
            Quality.NORMAL
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())
        val err = maxRadiusError(r, v, 1.0)
        assertTrue("r=1 的采样点离真圆最远 $err px", err < 1.0)
    }

    @Test
    fun `极坐标接受 r= 前缀且支持 θ`() {
        val v = vp()
        val a = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.POLAR, "1+cos(θ)", tMin = 0.0, tMax = 2 * PI)),
            v
        )
        val b = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.POLAR, "r = 1+cos(t)", tMin = 0.0, tMax = 2 * PI)),
            v
        )
        assertTrue(a.errors.toString(), a.errors.isEmpty())
        assertTrue(b.errors.toString(), b.errors.isEmpty())
        assertEquals(a.totalPoints, b.totalPoints)
    }

    @Test
    fun `参数方程画单位圆`() {
        val v = vp()
        val r = Plotter.plot(
            listOf(
                PlotFunction(
                    1, PlotMode.PARAMETRIC, "cos(t)", "sin(t)",
                    tMin = 0.0, tMax = 2 * PI
                )
            ),
            v,
            Quality.NORMAL
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())
        val err = maxRadiusError(r, v, 1.0)
        assertTrue("参数方程单位圆的点偏离 $err px", err < 1.0)
    }

    @Test
    fun `参数方程接受 x= y= 前缀`() {
        val v = vp()
        val r = Plotter.plot(
            listOf(
                PlotFunction(
                    1, PlotMode.PARAMETRIC, "x = cos(t)", "y = sin(t)",
                    tMin = 0.0, tMax = 2 * PI
                )
            ),
            v
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())
    }

    @Test
    fun `参数方程缺一个分量要报错`() {
        val r = Plotter.plot(
            listOf(PlotFunction(3, PlotMode.PARAMETRIC, "cos(t)", "")),
            vp()
        )
        assertTrue(r.curves.isEmpty())
        assertNotNull(r.errors[3])
    }

    @Test
    fun `隐函数圆 x2+y2=1`() {
        val v = vp()
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.IMPLICIT, "x^2+y^2=1")),
            v,
            Quality.NORMAL
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())
        val err = maxRadiusError(r, v, 1.0)
        assertTrue("隐函数圆的采样点偏离真圆 $err px", err < 2.5)
        // 线段数量要足够多，否则就是网格粒度太粗
        assertTrue("只采到 ${r.totalPoints} 个点，太少了", r.totalPoints > 100)
    }

    @Test
    fun `隐函数也接受不带等号的写法`() {
        val v = vp()
        val withEq = Plotter.plot(listOf(PlotFunction(1, PlotMode.IMPLICIT, "x^2+y^2=1")), v)
        val without = Plotter.plot(listOf(PlotFunction(1, PlotMode.IMPLICIT, "x^2+y^2-1")), v)
        assertTrue(without.errors.isEmpty())
        assertEquals(withEq.totalPoints, without.totalPoints)
    }

    @Test
    fun `隐函数画直线 y=x`() {
        val v = vp()
        val r = Plotter.plot(listOf(PlotFunction(1, PlotMode.IMPLICIT, "y-x")), v, Quality.NORMAL)
        assertTrue(r.curves.isNotEmpty())
        // 屏幕中心应该在线上
        val cx = v.toScreenX(0.0)
        val cy = v.toScreenY(0.0)
        var near = false
        for (c in r.curves) {
            var i = 0
            while (i < c.points.size) {
                if (hypot(c.points[i] - cx, c.points[i + 1] - cy) < 3.0) near = true
                i += 2
            }
        }
        assertTrue("y=x 应该穿过屏幕中心", near)
    }

    @Test
    fun `隐藏的函数不产生曲线`() {
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "x", visible = false)),
            Viewport.forRange(100f, 100f, -5.0, 5.0)
        )
        assertTrue(r.curves.isEmpty())
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `表达式错误会被记录下来而不是抛出去`() {
        val v = Viewport.forRange(100f, 100f, -5.0, 5.0)
        val r = Plotter.plot(listOf(PlotFunction(7, PlotMode.EXPLICIT, "sin(")), v)
        assertTrue(r.curves.isEmpty())
        assertNotNull(r.errors[7])
    }

    @Test
    fun `显函数模式里写隐函数要给出明确指引`() {
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "x^2+y^2=1")),
            Viewport.forRange(100f, 100f, -5.0, 5.0)
        )
        val msg = r.errors[1]
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains("F(x,y)"))
    }

    @Test
    fun `多个函数一次画完且各自带颜色和 id`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val r = Plotter.plot(
            listOf(
                PlotFunction(11, PlotMode.EXPLICIT, "x", colorIndex = 0),
                PlotFunction(22, PlotMode.EXPLICIT, "x^2", colorIndex = 1),
                PlotFunction(33, PlotMode.IMPLICIT, "x^2+y^2=25", colorIndex = 2)
            ),
            v,
            Quality.NORMAL
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(setOf(11L, 22L, 33L), r.curves.map { it.functionId }.toSet())
        assertEquals(1, r.curves.first { it.functionId == 22L }.colorIndex)
    }

    @Test
    fun `病态表达式不会失控`() {
        // sin(1/x) 在 0 附近无穷振荡；这里只要求它能跑完且不爆内存
        val v = Viewport.forRange(200f, 200f, -1.0, 1.0)
        val r = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "sin(1/x)")), v, Quality.NORMAL)
        assertTrue(r.totalPoints in 1..500_000)
    }

    @Test
    fun `精度档位越高点数越多`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val fn = listOf(PlotFunction(1, PlotMode.IMPLICIT, "x^2+y^2=4"))
        val fast = Plotter.plot(fn, v, Quality.FAST).totalPoints
        val high = Plotter.plot(fn, v, Quality.HIGH).totalPoints
        assertTrue("FAST=$fast HIGH=$high", high > fast)
    }

    @Test
    fun `尺寸为零的视口直接返回空结果`() {
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "x")),
            Viewport(0f, 0f)
        )
        assertTrue(r.curves.isEmpty())
    }

    // ------------------------------------------------------------------
    // 角度制透传：切换 DEG 后画出来的图必须真的变
    // ------------------------------------------------------------------

    @Test
    fun `DEG 模式下正弦的峰值出现在 x=90`() {
        val v = Viewport.forRange(361f, 361f, -180.0, 180.0)
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "sin(x)")),
            v,
            Quality.NORMAL,
            degrees = true
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())

        var peak = Double.NEGATIVE_INFINITY
        var peakX = 0.0
        for (c in r.curves) {
            var i = 0
            while (i < c.points.size) {
                val wy = v.toWorldY(c.points[i + 1].toDouble())
                if (wy > peak) {
                    peak = wy
                    peakX = v.toWorldX(c.points[i].toDouble())
                }
                i += 2
            }
        }
        assertEquals(1.0, peak, 0.02)
        assertEquals(90.0, peakX, 2.0)
    }

    @Test
    fun `DEG 画 sin(x) 与弧度画 sin(πx180) 逐点重合`() {
        val v = Viewport.forRange(300f, 300f, -180.0, 180.0)
        val deg = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "sin(x)")),
            v,
            Quality.NORMAL,
            degrees = true
        )
        val rad = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "sin(πx/180)")),
            v,
            Quality.NORMAL
        )
        assertTrue(deg.errors.toString(), deg.errors.isEmpty())
        assertTrue(rad.errors.toString(), rad.errors.isEmpty())
        assertEquals(rad.curves.size, deg.curves.size)
        assertEquals(rad.totalPoints, deg.totalPoints)
        for (ci in deg.curves.indices) {
            val a = deg.curves[ci].points
            val b = rad.curves[ci].points
            assertEquals(b.size, a.size)
            for (i in a.indices) assertEquals("第 $ci 段第 $i 个分量", b[i], a[i], 1e-3f)
        }
    }

    @Test
    fun `不传 degrees 的 plot 与显式 false 逐点一致`() {
        val v = Viewport.forRange(200f, 200f, -PI, PI)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "tan(x)"),
            PlotFunction(2, PlotMode.POLAR, "1+cos(t)", tMin = 0.0, tMax = 2 * PI),
            PlotFunction(4, PlotMode.PARAMETRIC, "cos(t)", "sin(t)", tMin = 0.0, tMax = 2 * PI),
            PlotFunction(3, PlotMode.IMPLICIT, "x^2+y^2=1")
        )
        val def = Plotter.plot(fns, v)
        val explicitRad = Plotter.plot(fns, v, Quality.NORMAL, degrees = false)
        assertEquals(def.totalPoints, explicitRad.totalPoints)
        assertEquals(def.curves.size, explicitRad.curves.size)
        for (i in def.curves.indices) {
            assertTrue(
                "第 $i 段曲线变了，说明默认值不再是弧度",
                def.curves[i].points.contentEquals(explicitRad.curves[i].points)
            )
        }
    }

    @Test
    fun `DEG 也会透传到隐函数模式`() {
        // y = sin(x°) 在 90° 处经过 (90, 1)
        val v = Viewport.forRange(361f, 361f, -180.0, 180.0)
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.IMPLICIT, "y-sin(x)")),
            v,
            Quality.HIGH,
            degrees = true
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val tx = v.toScreenX(90.0).toDouble()
        val ty = v.toScreenY(1.0).toDouble()
        var near = false
        for (c in r.curves) {
            var i = 0
            while (i < c.points.size) {
                if (hypot(c.points[i] - tx, c.points[i + 1] - ty) < 3.0) near = true
                i += 2
            }
        }
        assertTrue("DEG 模式下的 y=sin(x) 没经过 (90, 1)", near)
    }

    @Test
    fun `DEG 模式下极坐标的极角也按度解释`() {
        // 钉住"表达式按度、几何按弧度"这个不一致：
        //   r = θ/90，θ ∈ [0, 350] 度。正确的实现里每个采样点都满足
        //   极径 R = 极角(度)/90 —— 这是整条曲线的强约束。
        // 若几何那边漏了度→弧度换算，θ 会被当成弧度，t=350 时极角是
        // 350 rad（≈55 圈）而极径只有 3.89，除 t≈0 外几乎没有任何点能满足
        // 这条关系，断言必然失败。
        val lo = 0.0
        val hi = 350.0
        val v = Viewport.forRange(300f, 300f, -5.0, 5.0)
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.POLAR, "θ/90", tMin = lo, tMax = hi)),
            v,
            Quality.HIGH,
            degrees = true
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())

        var checked = 0
        for (c in r.curves) {
            var i = 0
            while (i < c.points.size) {
                val x = v.toWorldX(c.points[i].toDouble())
                val y = v.toWorldY(c.points[i + 1].toDouble())
                val radius = hypot(x, y)
                var deg = Math.toDegrees(atan2(y, x))
                if (deg < 0) deg += 360.0
                val expected = deg / 90.0
                assertTrue(
                    "点 ($x, $y)：极径 $radius 与极角 $deg 度推出的 $expected 不符",
                    Math.abs(radius - expected) < 0.05
                )
                checked++
                i += 2
            }
        }
        assertTrue("采样点太少（$checked），说明没真的画出螺旋", checked > 200)
    }

    @Test
    fun `每个函数可以带自己的角度单位，且压过全局默认值`() {
        // 用户明确要求："RAD 和 DEG 的切换放在函数编辑界面，是想不同函数可以用不同的度制"。
        // 所以 PlotFunction.degrees 是**每条曲线自己**的开关：
        // 这里全局默认给 true（度制），但 1 号函数显式写 false（弧度）——
        // 两条一模一样的 sin(x) 必须画出两种不同的形状。
        val v = Viewport.forRange(400f, 400f, -180.0, 180.0)
        val mixed = Plotter.plot(
            listOf(
                PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", degrees = false),
                PlotFunction(2, PlotMode.EXPLICIT, "sin(x)", degrees = true)
            ),
            v,
            Quality.NORMAL,
            degrees = true
        )
        assertTrue(mixed.errors.toString(), mixed.errors.isEmpty())
        assertEquals(2, mixed.curves.size)

        // 参照组：单独画、只用 plot 级的开关
        val radOnly = Plotter.plot(
            listOf(PlotFunction(9, PlotMode.EXPLICIT, "sin(x)")),
            v,
            Quality.NORMAL,
            degrees = false
        )
        val degOnly = Plotter.plot(
            listOf(PlotFunction(9, PlotMode.EXPLICIT, "sin(x)")),
            v,
            Quality.NORMAL,
            degrees = true
        )
        fun pointsOf(r: PlotResult, id: Long) = r.curves.first { it.functionId == id }.points

        assertTrue(
            "显式写了 degrees=false 的曲线没压过全局的 true",
            pointsOf(mixed, 1).contentEquals(pointsOf(radOnly, 9))
        )
        assertTrue(
            "degrees=true 的曲线应该和纯度制采样逐点一致",
            pointsOf(mixed, 2).contentEquals(pointsOf(degOnly, 9))
        )
        // 两种单位画出来的形状必须真的不同，否则上面两条断言可能只是"恰好相等"
        assertFalse(pointsOf(mixed, 1).contentEquals(pointsOf(mixed, 2)))
    }

    @Test
    fun `极坐标的几何换算也按每条曲线自己的单位`() {
        // 同一条 r=θ/90：度制下 θ∈[0,350] 度绕出一圈大螺旋（最远 3.89），
        // 弧度下同样的表达式在 θ∈[0,2π] 里半径只有 0.07 —— 差 50 倍，一眼可分。
        val v = Viewport.forRange(400f, 400f, -5.0, 5.0)
        val r = Plotter.plot(
            listOf(
                PlotFunction(
                    1, PlotMode.POLAR, "θ/90",
                    tMin = 0.0, tMax = 350.0, degrees = true
                ),
                PlotFunction(
                    2, PlotMode.POLAR, "θ/90",
                    tMin = 0.0, tMax = 2 * PI, degrees = false
                )
            ),
            v,
            Quality.NORMAL
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())

        fun maxRadius(id: Long): Double {
            val c = r.curves.first { it.functionId == id }
            var peak = 0.0
            var i = 0
            while (i < c.points.size) {
                val d = hypot(
                    v.toWorldX(c.points[i].toDouble()),
                    v.toWorldY(c.points[i + 1].toDouble())
                )
                if (d > peak) peak = d
                i += 2
            }
            return peak
        }
        assertEquals(3.888, maxRadius(1), 0.05)
        assertEquals(0.0698, maxRadius(2), 0.005)
    }

    // ------------------------------------------------------------------
    // 可绑定参数 a b c d 走完整条绘图管线
    // ------------------------------------------------------------------

    @Test
    fun `a 和 b 用默认值时 a*x+b 与 x+1 逐点一致`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val bound = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "a*x+b")), v, Quality.NORMAL)
        val plain = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "x+1")), v, Quality.NORMAL)
        assertTrue(bound.errors.toString(), bound.errors.isEmpty())
        assertEquals(1, bound.curves.size)
        // 取样点有限：不能因为认不出 a b 就画成空图，更不能无休止采样
        assertTrue("只采到 ${bound.totalPoints} 个点", bound.totalPoints in 2..500_000)
        // 1.0*x+1.0 与 x+1 是同一串浮点运算，所以这里可以直接要求逐位相同
        assertEquals(plain.curves.size, bound.curves.size)
        for (i in bound.curves.indices) {
            assertTrue(
                "第 $i 段与 x+1 不一致",
                bound.curves[i].points.contentEquals(plain.curves[i].points)
            )
        }
    }

    @Test
    fun `显式传 a b 的取值后画出来的就是另一条曲线`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val bound = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "a*x+b")),
            v,
            Quality.NORMAL,
            parameters = ParameterSet.of("a" to 2.0, "b" to 3.0)
        )
        val plain = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "2x+3")), v, Quality.NORMAL)
        assertTrue(bound.errors.toString(), bound.errors.isEmpty())
        assertEquals(plain.curves.size, bound.curves.size)
        for (i in bound.curves.indices) {
            assertTrue(
                "a=2,b=3 时 a*x+b 应该和 2x+3 逐点一致",
                bound.curves[i].points.contentEquals(plain.curves[i].points)
            )
        }
        // 还必须和默认绑定（x+1）画出来的不一样，否则"绑定生效"可能只是巧合
        val def = Plotter.plot(listOf(PlotFunction(1, PlotMode.EXPLICIT, "a*x+b")), v, Quality.NORMAL)
        assertFalse(
            "换了一组绑定，曲线却一模一样",
            def.curves[0].points.contentEquals(bound.curves[0].points)
        )
    }

    @Test
    fun `四种模式都能解析 a b c d`() {
        val v = vp()
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "a*x+b"),
            PlotFunction(2, PlotMode.POLAR, "a*cos(b*θ)+c", tMin = 0.0, tMax = 2 * PI),
            PlotFunction(3, PlotMode.PARAMETRIC, "a*cos(t)", "b*sin(t)", tMin = 0.0, tMax = 2 * PI),
            PlotFunction(4, PlotMode.IMPLICIT, "a*x^2+b*y^2=c")
        )
        val r = Plotter.plot(fns, v, Quality.NORMAL)
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L), r.curves.map { it.functionId }.toSet())
    }

    @Test
    fun `隐函数里的 c 真的决定半径`() {
        val v = vp(half = 4.0)
        val r = Plotter.plot(
            listOf(PlotFunction(1, PlotMode.IMPLICIT, "a*x^2+b*y^2=c")),
            v,
            Quality.NORMAL,
            parameters = ParameterSet.of("c" to 4.0)
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertTrue(r.curves.isNotEmpty())
        // a=b=1、c=4 → x²+y²=4，也就是半径 2 的圆。
        // 不传 parameters 时 c 是 1（半径 1），偏差会大 37px 左右直接失败，
        // 所以这条断言同时钉住了"取值真的透传进了隐函数采样"。
        val err = maxRadiusError(r, v, 2.0)
        assertTrue("半径应该由 c 决定（2），采样点最远偏 $err px", err < 2.5)
    }

    @Test
    fun `PARAMETRIC 模式的标签是用户指定的描点`() {
        // 用户原话："函数编辑界面的参数改名成描点"。
        // label 显示在函数编辑页顶部的模式 chip 上（UI 读 fn.mode.label），
        // 所以文案只在 PlotMode.label 里定义一份，界面里不要另起字符串。
        // 把断言里的"参数"改成"描点"是**改需求**，不是删测试。
        assertEquals("描点", PlotMode.PARAMETRIC.label)
        // 另外三个标签不受影响
        assertEquals("y=f(x)", PlotMode.EXPLICIT.label)
        assertEquals("r(θ)", PlotMode.POLAR.label)
        assertEquals("F(x,y)", PlotMode.IMPLICIT.label)
    }

    // ------------------------------------------------------------------
    // 调用用户定义过的函数走完整条绘图管线
    //
    // 用户报的 bug 就发生在这里：`a(x)=sin(x)`、`b(x)=2a(x)`，
    // 图像上 `b(x)` 画出来是 `y=2x`。下面第一条就是它的回归测试。
    // ------------------------------------------------------------------

    @Test
    fun `b(x) 等于 2a(x) 时与 2sin(x) 逐点一致（用户报的 bug）`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", name = "a(x)"),
            PlotFunction(2, PlotMode.EXPLICIT, "2a(x)", name = "b(x)")
        )
        val r = Plotter.plot(fns, v, Quality.NORMAL, callables = CallableSet.ofFunctions(fns))
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals(setOf(1L, 2L), r.curves.map { it.functionId }.toSet())
        val b = r.curves.first { it.functionId == 2L }.points

        // 参照组：等价表达式直接写出来
        val ref = Plotter.plot(listOf(PlotFunction(9, PlotMode.EXPLICIT, "2sin(x)")), v, Quality.NORMAL)
        assertTrue(ref.errors.toString(), ref.errors.isEmpty())
        assertTrue(
            "b(x)=2a(x) 应该和 2sin(x) 逐点一致",
            b.contentEquals(ref.curves.first().points)
        )

        // 用户看到的是 y=2x —— 这条断言就是那个 bug 的墓碑
        val line = Plotter.plot(listOf(PlotFunction(8, PlotMode.EXPLICIT, "2x")), v, Quality.NORMAL)
        assertFalse(
            "b(x) 被算成了 2x：a(x) 没有被当函数调用",
            b.contentEquals(line.curves.first().points)
        )
    }

    @Test
    fun `Plotter 里 a(x+1) a(a(x)) 2a(x)+1 都与等价表达式逐点一致`() {
        val v = Viewport.forRange(200f, 200f, -6.0, 6.0)
        val cs = CallableSet.ofFunctions(
            listOf(PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", name = "a(x)"))
        )
        fun pts(src: String, id: Long): FloatArray =
            Plotter.plot(
                listOf(PlotFunction(id, PlotMode.EXPLICIT, src)), v, Quality.NORMAL, callables = cs
            ).curves.first().points

        assertTrue("a(x+1)", pts("a(x+1)", 2).contentEquals(pts("sin(x+1)", 3)))
        assertTrue("a(a(x))", pts("a(a(x))", 4).contentEquals(pts("sin(sin(x))", 5)))
        assertTrue("2a(x)+1", pts("2a(x)+1", 6).contentEquals(pts("2sin(x)+1", 7)))
    }

    @Test
    fun `被调用函数的度制走完整条绘图管线`() {
        // a 是 DEG（用户在编辑界面单独给它按了 DEG），b 是 RAD：
        // b(x)=2a(x) 必须按 a 的度制算，也就是 2sin(πx/180)
        // 视口取小一点（±10），这样"度制到底生效没有"在像素上差得很明显
        val v = Viewport.forRange(360f, 360f, -10.0, 10.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", name = "a(x)", degrees = true),
            PlotFunction(2, PlotMode.EXPLICIT, "2a(x)", name = "b(x)", degrees = false)
        )
        val r = Plotter.plot(
            fns, v, Quality.NORMAL, degrees = false,
            callables = CallableSet.ofFunctions(fns, degrees = false)
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val b = r.curves.first { it.functionId == 2L }.points

        val ref = Plotter.plot(listOf(PlotFunction(9, PlotMode.EXPLICIT, "2sin(πx/180)")), v, Quality.NORMAL)
        val refPts = ref.curves.first().points
        assertEquals(refPts.size, b.size)
        for (i in b.indices) assertEquals("第 $i 个分量", refPts[i], b[i], 1e-3f)

        // 而且绝不能和弧度版画得一样（否则"度制生效"可能只是巧合）
        val radPts = Plotter.plot(
            listOf(PlotFunction(7, PlotMode.EXPLICIT, "2sin(x)")), v, Quality.NORMAL
        ).curves.first().points
        assertEquals(radPts.size, b.size)
        var maxDiff = 0f
        for (i in b.indices) maxDiff = max(maxDiff, abs(b[i] - radPts[i]))
        assertTrue("DEG 的 a(x) 与 RAD 的结果几乎一样（最大差 $maxDiff px）", maxDiff > 10f)
    }

    @Test
    fun `没传 callables 时 a(x) 还是老行为（向后兼容的默认值）`() {
        // 默认值是空表 = 这个功能没启用：`2a(x)` 仍然是 `2*1*x`。
        // 钉住它就是为了提醒接入方：**必须**把 CallableSet.ofFunctions(functions) 传给 plot，
        // 否则用户报的那个 bug 原样还在。
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "sin(x)", name = "a(x)"),
            PlotFunction(2, PlotMode.EXPLICIT, "2a(x)", name = "b(x)")
        )
        val r = Plotter.plot(fns, v, Quality.NORMAL)
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val old = r.curves.first { it.functionId == 2L }.points
        val line = Plotter.plot(listOf(PlotFunction(8, PlotMode.EXPLICIT, "2x")), v, Quality.NORMAL)
        assertTrue("不传表时必须保持加这个功能之前的行为", old.contentEquals(line.curves.first().points))
    }

    @Test
    fun `调用极坐标描点隐函数都会变成那条曲线上的中文报错`() {
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.POLAR, "2cos(3θ)", name = "p(x)", tMin = 0.0, tMax = 2 * PI),
            PlotFunction(2, PlotMode.EXPLICIT, "p(x)+1", name = "b(x)"),
            PlotFunction(3, PlotMode.EXPLICIT, "q(x)+1", name = "c(x)"),
            PlotFunction(4, PlotMode.PARAMETRIC, "cos(t)", "sin(t)", name = "q(x)")
        )
        val r = Plotter.plot(fns, v, Quality.NORMAL, callables = CallableSet.ofFunctions(fns))
        // p、q 自己能画出来；调用它们的 b、c 各拿到一条明确的中文报错
        assertTrue(r.errors.toString(), r.curves.any { it.functionId == 1L })
        for (id in listOf(2L, 3L)) {
            val msg = r.errors[id]
            assertNotNull("函数 $id 应该有报错", msg)
            assertTrue(msg!!, msg.contains("y=f(x)"))
            assertTrue(msg!!, msg.contains("不能被调用"))
        }
    }

    @Test
    fun `自引用的函数只是那条曲线报错，不会把整次绘图搞崩`() {
        val v = Viewport.forRange(120f, 120f, -5.0, 5.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "a(x)+1", name = "a(x)"),
            PlotFunction(2, PlotMode.EXPLICIT, "x", name = "b(x)")
        )
        val r = Plotter.plot(fns, v, Quality.NORMAL, callables = CallableSet.ofFunctions(fns))
        // a 自己成环 → 只有它报错；b 照常画出来（环必须被限制在真正成环的那条链上）
        val msg = r.errors[1L]
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains("自己"))
        assertNull("b(x) 不该被 a 的环牵连", r.errors[2L])
        assertTrue(r.curves.any { it.functionId == 2L })
    }

    @Test
    fun `UI 接线形状：函数列表 + 全局度制 + 滑动条参数 一起喂进去才生效`() {
        // 这条测试是**照着 UI 的接线写的**（GraphState.callables() 与 GraphScreen 里那次采样）：
        //   CallableSet.ofFunctions(functions, settings.angleUnit.isDegrees, sliders.toParameterSet())
        //   Plotter.plot(functions, viewport, quality, settings.angleUnit.isDegrees, parameters, callables)
        // 重点覆盖"被调用函数的**函数体里还有可绑定参数**"这一层。
        val v = Viewport.forRange(200f, 200f, -10.0, 10.0)
        val parameters = ParameterSet.of("c" to 3.0)
        val fns = listOf(
            PlotFunction(1, PlotMode.EXPLICIT, "c*sin(x)", name = "a(x)"),
            PlotFunction(2, PlotMode.EXPLICIT, "2a(x)+c", name = "b(x)")
        )
        val r = Plotter.plot(
            fns, v, Quality.NORMAL,
            degrees = false,
            parameters = parameters,
            callables = CallableSet.ofFunctions(fns, degrees = false, parameters = parameters)
        )
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val b = r.curves.first { it.functionId == 2L }.points
        val ref = Plotter.plot(
            listOf(PlotFunction(9, PlotMode.EXPLICIT, "2(3sin(x))+3")), v, Quality.NORMAL
        ).curves.first().points
        assertTrue("b(x)=2a(x)+c（c=3）应该和 2(3sin(x))+3 逐点一致", b.contentEquals(ref))

        // 反证：参数表漏传给 ofFunctions（表里的函数会退回缺省的 1.0），
        // 画出来就是另一条线 —— 这就是"两个参数必须一起传"的理由。
        val forgot = Plotter.plot(
            listOf(PlotFunction(2, PlotMode.EXPLICIT, "2a(x)+c", name = "b(x)")),
            v, Quality.NORMAL,
            parameters = parameters,
            callables = CallableSet.ofFunctions(fns, degrees = false)
        ).curves.first().points
        assertFalse("参数表漏传居然画得一样", forgot.contentEquals(b))
    }
}
