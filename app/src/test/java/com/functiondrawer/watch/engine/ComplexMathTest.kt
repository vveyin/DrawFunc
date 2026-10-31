package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * [ComplexMath.kt] 的单元测试：复数类型、复数表达式求值器、复平面点标签。
 *
 * 纯 JVM 测试，跑 `tools\run-engine-tests.ps1` 即可，不需要表、不需要 Gradle。
 *
 * 断言的取向（和 [GeometryTest] 一致）：
 *  * **能用解析值就用解析值** —— `exp(iπ) = -1`、`√(-1) = i`、`ln e = 1`、
 *    `sin(i) = i·sinh 1`、`(2+3i)(1-i) = 5+i`、`(1+i)² = 2i` 这种手算/已知值，
 *    而不是把实现算出来的数抄一遍（抄下来的期望值只能证明"代码没变"，
 *    证明不了"代码是对的"）；
 *  * 少数几个函数（`sin`/`cos` 的加法公式）再补一条**恒等式交叉验证**
 *    （如 `sin z = (e^{iz} − e^{−iz})/(2i)`，走的是 exp + 除法这两条独立路径）；
 *  * 浮点比较**一律**带容差，绝不写 `==`；边界场合断言是不是 inf / NaN。
 */
class ComplexMathTest {

    // ---------------------------------------------------------------- 测试脚手架

    /** 按实部/虚部分别做容差断言。失败信息里带上紧凑标签，一眼能看出算成了什么。 */
    private fun assertComplex(
        expRe: Double,
        expIm: Double,
        act: Complex,
        delta: Double = 1e-9,
        msg: String = ""
    ) {
        assertEquals("$msg 实部不对（实际结果 ${resultLabel(act)}）", expRe, act.re, delta)
        assertEquals("$msg 虚部不对（实际结果 ${resultLabel(act)}）", expIm, act.im, delta)
    }

    /** 结果应当是实数的场合（abs / arg / re / im）。 */
    private fun assertReal(exp: Double, act: Complex, delta: Double = 1e-9, msg: String = "") {
        assertComplex(exp, 0.0, act, delta, msg)
    }

    /** 直接对一个表达式求值并做容差断言。 */
    private fun assertEval(
        expRe: Double,
        expIm: Double,
        src: String,
        z: Complex = Complex.ZERO,
        params: Map<String, Complex> = emptyMap(),
        delta: Double = 1e-9
    ) {
        assertComplex(expRe, expIm, evaluate(src, z, params), delta, "表达式『$src』")
    }

    /**
     * 断言某个表达式抛 [ExpressionException]，且报错信息里点名了关键内容。
     *
     * 顺便检查 position 落在源码范围内 —— UI 要靠它把出错位置标红，超出范围会直接崩。
     */
    private fun assertSyntaxError(
        src: String,
        contains: String,
        z: Complex = Complex.ZERO,
        params: Map<String, Complex> = emptyMap()
    ) {
        try {
            val v = evaluate(src, z, params)
            fail("表达式『$src』本该报错，却算出了 ${resultLabel(v)}")
        } catch (e: ExpressionException) {
            assertTrue(
                "表达式『$src』的报错信息里应当出现『$contains』，实际是：${e.message}",
                e.message.orEmpty().contains(contains)
            )
            assertTrue(
                "表达式『$src』的报错位置越界：position=${e.position}，源码长度=${src.length}",
                e.position in 0..src.length
            )
        }
    }

    // ---------------------------------------------------------------- 1. 基本类型

    @Test
    fun `虚数单位的平方是负一`() {
        assertComplex(-1.0, 0.0, Complex.I * Complex.I)
        assertComplex(-1.0, 0.0, Complex.I.pow(2))
        assertComplex(1.0, 0.0, Complex.I.pow(4))
        assertComplex(-1.0, 0.0, Complex.I.pow(6))
        assertComplex(0.0, 1.0, Complex.I.pow(5))
    }

    @Test
    fun `常量与实数互操作`() {
        assertComplex(0.0, 0.0, Complex.ZERO)
        assertComplex(1.0, 0.0, Complex.ONE)
        assertComplex(0.0, 1.0, Complex.I)
        // Double.toComplex() 把实数抬成虚部为 0 的复数
        assertComplex(3.0, 0.0, 3.0.toComplex())
        assertComplex(-0.5, 0.0, (-0.5).toComplex())
        // 和实数混算的语法糖
        assertComplex(3.0, 4.0, Complex(1.0, 4.0) + 2.0)
        assertComplex(3.0, 8.0, 2.0 * Complex(1.5, 4.0))
    }

    @Test
    fun `有限性判断`() {
        assertTrue(Complex(1.0, -2.0).isFinite)
        assertTrue(Complex.ZERO.isFinite)
        assertFalse(Complex(Double.NaN, 0.0).isFinite)
        assertFalse(Complex(0.0, Double.NaN).isFinite)
        assertFalse(Complex(Double.POSITIVE_INFINITY, 0.0).isFinite)
        assertFalse(Complex(0.0, Double.NEGATIVE_INFINITY).isFinite)
    }

    // ---------------------------------------------------------------- 2. 四则运算

    @Test
    fun `四则运算的手算例子`() {
        // (2+3i)(1-i) = 2 - 2i + 3i - 3i² = (2+3) + (-2+3)i = 5 + i
        assertComplex(5.0, 1.0, Complex(2.0, 3.0) * Complex(1.0, -1.0))
        // (1+2i)(3-4i) = 3 - 4i + 6i - 8i² = 11 + 2i
        assertComplex(11.0, 2.0, Complex(1.0, 2.0) * Complex(3.0, -4.0))
        assertComplex(3.0, 2.0, Complex(2.0, 3.0) + Complex(1.0, -1.0))
        assertComplex(1.0, 4.0, Complex(2.0, 3.0) - Complex(1.0, -1.0))
        // (1+i)/(1-i) = (1+i)²/2 = 2i/2 = i
        assertComplex(0.0, 1.0, Complex(1.0, 1.0) / Complex(1.0, -1.0))
        // (5+i)/(2+3i) = (5+i)(2-3i)/13 = (13-13i)/13 = 1 - i
        assertComplex(1.0, -1.0, Complex(5.0, 1.0) / Complex(2.0, 3.0))
        // 1/i = -i
        assertComplex(0.0, -1.0, Complex.ONE / Complex.I)
        // 取负
        assertComplex(-1.0, 2.0, -Complex(1.0, -2.0))
        assertComplex(-1.0, 2.0, -(Complex(1.0, -2.0) * Complex.ONE))
    }

    @Test
    fun `整数幂走平方乘得到精确结果`() {
        // (1+i)² = 2i，(1+i)⁸ = ((1+i)²)⁴ = (2i)⁴ = 16
        assertComplex(0.0, 2.0, Complex(1.0, 1.0).pow(2), 1e-12)
        assertComplex(16.0, 0.0, Complex(1.0, 1.0).pow(8), 1e-12)
        assertComplex(1024.0, 0.0, Complex(2.0, 0.0).pow(10), 1e-12)
        // z⁰ = 1（含 0⁰ = 1）
        assertComplex(1.0, 0.0, Complex(3.0, 4.0).pow(0))
        assertComplex(1.0, 0.0, Complex.ZERO.pow(0))
        // 负指数 = 取倒数：1/(1+i) = (1-i)/2
        assertComplex(0.5, -0.5, Complex(1.0, 1.0).pow(-1), 1e-12)
    }

    @Test
    fun `除零不抛异常而是给出IEEE的inf与NaN`() {
        // 分子是实数：和实数除法同一条语义（1/0 = ∞），标签也能显示成 "∞"
        val a = Complex.ONE / Complex.ZERO
        assertTrue("1/0 的实部应当是 +∞，实际是 ${resultLabel(a)}", a.re == Double.POSITIVE_INFINITY)
        assertEquals("1/0 的虚部应当是 0", 0.0, a.im, 0.0)
        assertEquals("∞", resultLabel(a))

        val b = Complex.ZERO / Complex.ZERO
        assertTrue("0/0 的实部应当是 NaN", b.re.isNaN())
        assertEquals("0/0 的虚部按实数除法给 0", 0.0, b.im, 0.0)

        val c = Complex(-1.0, 0.0) / Complex.ZERO
        assertTrue("-1/0 的实部应当是 -∞", c.re == Double.NEGATIVE_INFINITY)

        // 分子带非零虚部：两个分量各自发散（点跑到无穷远，方向不定）
        val d = Complex(1.0, 2.0) / Complex.ZERO
        assertTrue("(1+2i)/0 的实部应当是 ∞", d.re == Double.POSITIVE_INFINITY)
        assertTrue("(1+2i)/0 的虚部应当是 ∞", d.im == Double.POSITIVE_INFINITY)
        assertEquals("∞+∞i", resultLabel(d))

        // 除以实数 0 和除以 Complex.ZERO 走同一套语义
        assertEquals(resultLabel(a), resultLabel(Complex.ONE / 0.0))

        // 大数相除不因为 c²+d² 上溢而变成 NaN（Smith 算法的意义所在）
        val big = Complex(1.0e300, 1.0e300) / Complex(2.0e300, 0.0)
        assertComplex(0.5, 0.5, big, 1e-12)
    }

    // ---------------------------------------------------------------- 3. 模 / 辐角 / 共轭

    @Test
    fun `模的解析值`() {
        assertEquals(5.0, Complex(3.0, 4.0).abs, 1e-15)
        assertEquals(5.0, Complex(-3.0, -4.0).abs, 1e-15)
        assertEquals(0.0, Complex.ZERO.abs, 1e-15)
        assertEquals(1.0, Complex.I.abs, 1e-15)
        assertEquals(2.0, Complex(0.0, -2.0).abs, 1e-15)
        // |3+4i|² = 25，省一次开方
        assertEquals(25.0, Complex(3.0, 4.0).normSquared, 1e-12)
        // 极大分量不因平方上溢（hypot 做缩放），用相对误差比较
        assertEquals(
            "|1e200 + 1e200 i| 应当等于 √2·1e200",
            1.0,
            Complex(1.0e200, 1.0e200).abs / (sqrt(2.0) * 1.0e200),
            1e-12
        )
    }

    @Test
    fun `辐角的解析值`() {
        assertEquals(0.0, Complex(1.0, 0.0).arg, 1e-15)
        assertEquals(PI / 2.0, Complex.I.arg, 1e-15)
        assertEquals(PI, Complex(-1.0, 0.0).arg, 1e-15)
        assertEquals(-PI / 4.0, Complex(1.0, -1.0).arg, 1e-15)
        assertEquals(PI / 4.0, Complex(1.0, 1.0).arg, 1e-15)
        assertEquals(PI / 2.0, Complex(0.0, 5.0).arg, 1e-15)
        assertEquals(-PI / 2.0, Complex(0.0, -5.0).arg, 1e-15)
        assertEquals("arg(3) 就是 0", 0.0, Complex(3.0, 0.0).arg, 1e-15)
        // arg 0 未定义，这里约定取 0（免得 UI 上冒出 NaN）
        assertEquals(0.0, Complex(0.0, 0.0).arg, 1e-15)
    }

    @Test
    fun `辐角主值恒落在负π到π之间`() {
        val samples = listOf(
            Complex(1.0, 0.0), Complex(-1.0, 0.0), Complex(0.0, 1.0), Complex(0.0, -1.0),
            Complex(-1.0, -0.0), Complex(1.0, -1.0), Complex(-1.0, 1.0), Complex(0.0, 0.0),
            Complex(-1.0e-300, -1.0e-300), Complex(1.0, 1.0e300)
        )
        for (z in samples) {
            val a = z.arg
            assertTrue("arg(${resultLabel(z)}) = $a 超出了 (-π, π]", a > -PI && a <= PI)
        }
        // 负实轴上"y 是负零"的边界被归一成 +π（而不是 -π）
        assertEquals(PI, Complex(-1.0, -0.0).arg, 0.0)
    }

    @Test
    fun `共轭的恒等式`() {
        val z = Complex(3.0, -4.0)
        assertComplex(3.0, 4.0, z.conjugate)
        assertComplex(3.0, -4.0, z.conjugate.conjugate)
        // z·conj(z) = |z|²（实部等于模方，虚部为 0）
        val p = z * z.conjugate
        assertComplex(25.0, 0.0, p, 1e-12)
        // conj 对加法线性：conj(z + (1+2i)) = conj(z) + (1−2i) = 4+2i
        assertComplex(4.0, 2.0, (z + Complex(1.0, 2.0)).conjugate, 1e-12)
        assertComplex(4.0, 2.0, z.conjugate + Complex(1.0, -2.0), 1e-12)
    }

    // ---------------------------------------------------------------- 4. 解析值函数

    @Test
    fun `指数与对数的解析值`() {
        // e^(iπ) = -1（欧拉公式）
        assertComplex(-1.0, 0.0, Complex(0.0, PI).exp, 1e-15)
        assertComplex(1.0, 0.0, Complex.ZERO.exp, 1e-15)
        assertEquals(0.0, Complex(0.0, PI).exp.im, 1e-15)
        // e^(1+i) = e·(cos1 + i·sin1) = 1.4686939399158851 + 2.2873552871788423i
        assertComplex(1.4686939399158851, 2.2873552871788423, Complex(1.0, 1.0).exp, 1e-12)
        // ln e = 1
        assertComplex(1.0, 0.0, Complex(E, 0.0).log, 1e-15)
        // log(-1) = iπ（支割线在负实轴上，取主值）
        assertComplex(0.0, PI, Complex(-1.0, 0.0).log, 1e-15)
        // log(1+i) = ln√2 + iπ/4 = 0.5·ln2 + iπ/4
        assertComplex(0.5 * ln(2.0), PI / 4.0, Complex(1.0, 1.0).log, 1e-15)
        // e^(log z) = z（主值分支内）
        assertComplex(1.0, 1.0, Complex(1.0, 1.0).log.exp, 1e-15)
        assertComplex(-2.0, 0.5, Complex(-2.0, 0.5).log.exp, 1e-12)
        // log 0 = -∞ + 0i
        val l0 = Complex.ZERO.log
        assertTrue("log(0) 的实部应当是 -∞", l0.re == Double.NEGATIVE_INFINITY)
        assertEquals("log(0) 的虚部应当是 0", 0.0, l0.im, 0.0)
    }

    @Test
    fun `开方取主值`() {
        assertComplex(0.0, 1.0, Complex(-1.0, 0.0).sqrt, 1e-15)          // √(-1) = i
        assertComplex(0.0, 2.0, Complex(-4.0, 0.0).sqrt, 1e-15)          // √(-4) = 2i
        assertComplex(2.0, 0.0, Complex(4.0, 0.0).sqrt, 1e-15)           // √4 = 2
        assertComplex(0.0, 0.0, Complex.ZERO.sqrt)                        // √0 = 0（不能是 NaN）
        assertComplex(2.0, 1.0, Complex(3.0, 4.0).sqrt, 1e-15)           // (2+i)² = 3+4i
        assertComplex(sqrt(2.0) / 2.0, sqrt(2.0) / 2.0, Complex.I.sqrt, 1e-15)
        // 虚部是 -0.0 时也取 +i 一侧（主值语义，不区分支割线上下岸）
        assertComplex(0.0, 1.0, Complex(-1.0, -0.0).sqrt, 1e-15)
        // √z 的平方回到 z（主值分支内处处成立）
        for (z in listOf(Complex(1.0, 1.0), Complex(-2.0, 0.5), Complex(0.0, -3.0))) {
            val s = z.sqrt
            assertComplex(
                z.re, z.im,
                Complex(s.re * s.re - s.im * s.im, 2.0 * s.re * s.im),
                1e-12, "√z 的平方"
            )
        }
        // 大模长时改用相对误差：绝对误差必然随量级放大，这是浮点本身的性质
        val big = Complex(1.0e8, -1.0e8)
        val bs = big.sqrt
        assertEquals(1.0, (bs.re * bs.re - bs.im * bs.im) / big.re, 1e-12)
        assertEquals(1.0, (2.0 * bs.re * bs.im) / big.im, 1e-12)
    }

    @Test
    fun `三角与双曲的解析值`() {
        // sin(i) = i·sinh 1
        assertComplex(0.0, sinh(1.0), Complex(0.0, 1.0).sin, 1e-15)
        // cos(i) = cosh 1
        assertComplex(cosh(1.0), 0.0, Complex(0.0, 1.0).cos, 1e-15)
        // sin(1+i) = sin1·cosh1 + i·cos1·sinh1 = 1.2984575814159773 + 0.6349639147847361i
        assertComplex(1.2984575814159773, 0.6349639147847361, Complex(1.0, 1.0).sin, 1e-12)
        // cos(1+i) = cos1·cosh1 − i·sin1·sinh1 = 0.8337300251311491 − 0.9888977057628651i
        assertComplex(0.8337300251311491, -0.9888977057628651, Complex(1.0, 1.0).cos, 1e-12)
        // tan(i) = i·tanh 1
        assertComplex(0.0, tanh(1.0), Complex(0.0, 1.0).tan, 1e-15)
        // tan(π/4) = 1
        assertComplex(1.0, 0.0, Complex(PI / 4.0, 0.0).tan, 1e-12)
        // cosh(i) = cos 1，sinh(i) = i·sin 1
        assertComplex(cos(1.0), 0.0, Complex(0.0, 1.0).cosh, 1e-15)
        assertComplex(0.0, sin(1.0), Complex(0.0, 1.0).sinh, 1e-15)
        // sin²+cos² = 1（复数恒等式，交叉验证加法公式）
        val z = Complex(1.0, 1.0)
        val s = z.sin
        val c = z.cos
        assertComplex(
            1.0, 0.0,
            Complex(s.re * s.re - s.im * s.im + c.re * c.re - c.im * c.im, 2.0 * s.re * s.im + 2.0 * c.re * c.im),
            1e-12
        )
    }

    @Test
    fun `指数公式交叉验证三角函数的加法公式`() {
        // sin z = (e^{iz} − e^{−iz})/(2i)，cos z = (e^{iz} + e^{−iz})/2
        // 这条路径只用到 exp 与除法 —— 它们在别的测试里是用欧拉公式/解析值单独钉住的。
        for (z in listOf(Complex(0.3, 0.7), Complex(-1.0, 2.0), Complex(2.0, -0.5))) {
            val iz = Complex(0.0, 1.0) * z
            val e1 = iz.exp
            val e2 = (-iz).exp
            val expSin = (e1 - e2) / Complex(0.0, 2.0)
            val expCos = (e1 + e2) / Complex(2.0, 0.0)
            assertComplex(z.sin.re, z.sin.im, expSin, 1e-12, "sin 与指数公式不一致")
            assertComplex(z.cos.re, z.cos.im, expCos, 1e-12, "cos 与指数公式不一致")
        }
    }

    @Test
    fun `大虚部时正切不会假溢出`() {
        // 直接用 sin/cos 相除的话这里会是 ∞/∞ = NaN；改写后的公式给的是极限值 i
        assertComplex(0.0, 1.0, Complex(0.0, 800.0).tan, 1e-12)
        assertComplex(0.0, 1.0, Complex(1.0, 700.0).tan, 1e-12)
        assertComplex(0.0, 1.0, Complex(-3.0, 1.0e300).tan, 1e-12)
        // 负的大虚部对称地给 -i
        assertComplex(0.0, -1.0, Complex(1.0, -700.0).tan, 1e-12)
        // 极点 tan(π/2) 处发散成 ∞（不是 NaN 实部）
        val pole = Complex(PI / 2.0, 0.0).tan
        assertTrue("tan(π/2) 应当发散，实际是 ${resultLabel(pole)}", !pole.isFinite)
    }

    @Test
    fun `幂的解析值与零的边界约定`() {
        // 2^3 = 8
        assertComplex(8.0, 0.0, Complex(2.0, 0.0).pow(Complex(3.0, 0.0)), 1e-12)
        // i^i = e^{i·log i} = e^{i·iπ/2} = e^{−π/2} = 0.20787957635076193
        assertComplex(0.20787957635076193, 0.0, Complex.I.pow(Complex.I), 1e-15)
        // (−1)^0.5 = i（主值支割线在负实轴上）
        assertComplex(0.0, 1.0, Complex(-1.0, 0.0).pow(0.5), 1e-15)
        // (1+i)^2 走 exp/log 会有一两个 ulp 的误差，容差放宽到 1e-9
        assertComplex(0.0, 2.0, Complex(1.0, 1.0).pow(2.0), 1e-9)
        // 0 的幂：0⁰ = 1、0^正 = 0、0^负 = ∞、0^(纯虚) = NaN
        assertComplex(1.0, 0.0, Complex.ZERO.pow(Complex.ZERO))
        assertComplex(0.0, 0.0, Complex.ZERO.pow(Complex(2.0, 0.0)))
        assertComplex(0.0, 0.0, Complex.ZERO.pow(0.5))
        assertTrue(Complex.ZERO.pow(Complex(-1.0, 0.0)).re == Double.POSITIVE_INFINITY)
        assertTrue("0^(i) 没有主值，应当是 NaN", Complex.ZERO.pow(Complex(0.0, 1.0)).re.isNaN())
        assertTrue("0^(−1) 走整数幂路径按除零给 ∞", Complex.ZERO.pow(-1).re == Double.POSITIVE_INFINITY)
    }

    // ---------------------------------------------------------------- 5. 求值器：语法

    @Test
    fun `字面量常量与虚数单位`() {
        assertEval(2.0, 0.0, "2")
        assertEval(3.5, 0.0, "3.5")
        assertEval(0.5, 0.0, ".5")
        assertEval(0.001, 0.0, "1e-3")
        assertEval(0.0, 1.0, "i")
        assertEval(0.0, 2.0, "2i")
        assertEval(0.0, -3.0, "-3i")
        assertEval(PI, 0.0, "π")
        assertEval(PI, 0.0, "pi")
        assertEval(E, 0.0, "e")
        assertEval(2.0 * E, 0.0, "2e")            // 2e = 2·欧拉数，不是残缺的科学计数法
        assertEval(2.0 * PI, 0.0, "2π")
        assertEval(2.0 * PI, 0.0, "tau")
        assertEval(Double.POSITIVE_INFINITY, 0.0, "∞")
        // 空表达式/纯空白是错误
        assertSyntaxError("", "表达式是空的")
        assertSyntaxError("   ", "表达式是空的")
    }

    @Test
    fun `优先级与结合性`() {
        assertEval(7.0, 0.0, "1+2*3")
        assertEval(7.0, 0.0, "2*3+1")
        assertEval(9.0, 0.0, "(1+2)*3")
        assertEval(1.0, 0.0, "8/4/2")             // 同级左结合
        assertEval(-5.0, 0.0, "2-3-4")
        assertEval(512.0, 0.0, "2^3^2")           // 幂右结合：2^(3^2)
        assertEval(0.5, 0.0, "2^-1")              // 指数允许一元负号
        assertEval(-4.0, 0.0, "-2^2")             // 一元负号比幂低：-(2^2)
        assertEval(4.0, 0.0, "(-2)^2")
        assertEval(-1.0, 0.0, "i^2")
        assertEval(-1.0, 0.0, "i i")              // 空格隔开的隐式乘法
        // 整数指数走平方-乘，结果是**精确**的（容差 0 也能过），
        // 而不是绕道 exp(n·log z) 得到的 -1+1.22e-16i 那种
        assertEval(512.0, 0.0, "2^3^2", delta = 0.0)
        assertEval(-1.0, 0.0, "i^2", delta = 0.0)
        assertEval(1.0, 0.0, "z^2", z = Complex(-1.0, 0.0), delta = 0.0)
        assertEval(1.0, 0.0, "(-1)^2", delta = 0.0)
    }

    @Test
    fun `一元负号`() {
        assertEval(0.0, -1.0, "-i")
        assertEval(3.0, 0.0, "-(-3)")
        assertEval(5.0, 0.0, "2 - -3")
        assertEval(-1.0, -1.0, "-(1+i)")
        assertEval(-1.0, -1.0, "-z", z = Complex(1.0, 1.0))
        assertEval(1.0, 1.0, "+z", z = Complex(1.0, 1.0))
    }

    @Test
    fun `隐式乘法`() {
        assertEval(6.0, 8.0, "2z", z = Complex(3.0, 4.0))
        assertEval(3.0, 6.0, "3z", z = Complex(1.0, 2.0))
        // zi = (3+4i)·i = -4+3i
        assertEval(-4.0, 3.0, "zi", z = Complex(3.0, 4.0))
        assertEval(0.0, 3.0, "3i")
        assertEval(2.0, 0.0, "(1+i)(1-i)")
        assertEval(2.0, 2.0, "2(1+i)")
        assertEval(6.283185307179586, 0.0, "2π")
        // 2z² 按常规理解成 2·(z²)
        assertEval(-6.0, 8.0, "2z^2", z = Complex(1.0, 2.0))
        // 隐式乘法不能把两个数字粘起来：那是错字信号，必须报错
        assertSyntaxError("1.2.3", "数字")
        assertSyntaxError("2 3", "数字 '3' 前面缺少运算符")
    }

    @Test
    fun `变量与参数`() {
        // 不传 z 时主变量是 0
        assertEval(0.0, 1.0, "z+i")
        assertEval(-5.0, 12.0, "z^2", z = Complex(2.0, 3.0))       // (2+3i)² = -5+12i
        // a b c d 缺省是 1（占位值），所以 a·z+b 等价于 z+1
        assertEval(2.0, 3.0, "a*z+b", z = Complex(1.0, 3.0))
        // 显式给参数
        val params = mapOf("a" to Complex(2.0, 0.0), "b" to Complex(0.0, 3.0))
        assertEval(2.0, 5.0, "a*z+b", z = Complex(1.0, 1.0), params = params)
        // 参数可以是复数，也可以是任意名字
        assertEval(1.0, 1.0, "w+1", params = mapOf("w" to Complex(0.0, 1.0)))
        // 多字母名字按单字母乘积拆开（和 x/y 模式里的 ax、xy 一致）
        assertEval(6.0, 0.0, "az", z = Complex(3.0, 0.0), params = mapOf("a" to Complex(2.0, 0.0)))
    }

    @Test
    fun `函数调用`() {
        assertEval(5.0, 0.0, "abs(3+4i)")
        assertEval(-PI / 2.0, 0.0, "arg(0-i)")
        assertEval(1.0, -2.0, "conj(1+2i)")
        assertEval(1.0, -2.0, "conjugate(1+2i)")
        assertEval(2.0, 0.0, "Re(2+3i)")
        assertEval(3.0, 0.0, "Im(2+3i)")
        assertEval(2.0, 0.0, "re(2+3i)")
        assertEval(3.0, 0.0, "im(2+3i)")
        // 欧拉公式走一遍完整求值器
        assertComplex(-1.0, 0.0, evaluate("exp(i*pi)"), 1e-15)
        assertEval(1.0, 0.0, "ln(e)")
        assertReal(1.0, evaluate("ln(e)"), 1e-15, "自然对数")
        assertEval(2.0, 0.0, "log(100)")          // log 与项目其它地方一致 = 常用对数
        assertReal(2.0, evaluate("log(100)"), 1e-15, "常用对数")
        assertEval(3.0, 0.0, "log(2, 8)")         // 两参数：以 2 为底
        assertEval(0.0, 1.0, "sqrt(-1)")
        assertEval(8.0, 0.0, "pow(2,3)")
        assertEval(0.0, 0.0, "sin(0)")
        assertEval(1.0, 0.0, "cos(0)")
        assertEval(1.0, 0.0, "tan(pi/4)")
        assertEval(0.0, 0.0, "sinh(0)")
        assertEval(1.0, 0.0, "cosh(0)")
        // 函数名大小写不敏感
        assertEval(2.0, 0.0, "RE(z)", z = Complex(2.0, 7.0))
        assertEval(1.0, 0.0, "ABS(1)")
    }

    @Test
    fun `省略括号与绝对值竖线`() {
        assertEval(0.0, 0.0, "sin 0")
        assertEval(2.0, 0.0, "sqrt 4")
        assertEval(5.0, 0.0, "abs z", z = Complex(3.0, 4.0))
        assertEval(5.0, 0.0, "|3+4i|")
        assertEval(1.0, 0.0, "|-1|")
        // 2|z| 里第一个竖线开启新操作数（隐式乘法），第二个是闭合符
        assertEval(2.0, 0.0, "2|i|", delta = 1e-12)
        assertEval(10.0, 0.0, "2|3+4i|", delta = 1e-12)
    }

    // ---------------------------------------------------------------- 6. 求值器：错误处理

    @Test
    fun `未知名字报错并且点名`() {
        assertSyntaxError("q", "不认识的名字")
        assertSyntaxError("q", "'q'")
        assertSyntaxError("z+q", "'q'")
        // 多字母未知名字整体点名
        assertSyntaxError("zzz+q", "'q'")
        assertSyntaxError("sinx", "函数要加括号")
        assertSyntaxError("foo(1)", "不认识的名字 'foo'")
        // 报错信息里给出可用的名字，用户知道能改什么
        try {
            evaluate("q")
            fail("应当报错")
        } catch (e: ExpressionException) {
            assertTrue("报错信息应当列出可用名字：${e.message}", e.message.orEmpty().contains("变量 z"))
        }
    }

    @Test
    fun `语法错误带位置信息`() {
        assertSyntaxError("(1+i", "括号没有闭合")
        assertSyntaxError("1+i)", "多余的右括号")
        assertSyntaxError("|1+2", "绝对值符号 | 没有成对出现")
        assertSyntaxError("1+", "表达式不完整")
        assertSyntaxError("*2", "运算符")
        assertSyntaxError("1,2", "逗号只能出现在函数参数里")
        assertSyntaxError("1+i=", "看不懂这个字符：'='")
        assertSyntaxError("2!", "看不懂这个字符：'!'")
        assertSyntaxError("5%2", "看不懂这个字符：'%'")
    }

    @Test
    fun `报错位置指向出错的那个字符`() {
        try {
            evaluate("1+i+*2")
            fail("应当报错")
        } catch (e: ExpressionException) {
            assertEquals("'*' 的位置应当是下标 4", 4, e.position)
        }
        try {
            evaluate("1+qq")
            fail("应当报错")
        } catch (e: ExpressionException) {
            assertEquals("'qq' 的位置应当是下标 2", 2, e.position)
        }
    }

    @Test
    fun `函数参数个数不对要报错`() {
        assertSyntaxError("sin(1,2)", "函数 sin 需要 1 个参数，现在给了 2 个")
        assertSyntaxError("pow(1)", "函数 pow 需要 2 个参数，现在给了 1 个")
        assertSyntaxError("sin()", "函数 sin 缺少参数")
        assertSyntaxError("pow(1 2)", "少了一个逗号或右括号")
        assertSyntaxError("log(1,2,3)", "函数 log 需要 1~2 个参数，现在给了 3 个")
        // 需要两个参数的函数不能省括号
        assertSyntaxError("pow 2", "要写括号")
    }

    // ---------------------------------------------------------------- 7. 求值器：数值边界

    @Test
    fun `病态输入返回inf或NaN而不是抛异常`() {
        assertTrue("1/0 应当是 ∞", evaluate("1/0").re == Double.POSITIVE_INFINITY)
        assertTrue("ln(0) 应当是 -∞", evaluate("ln(0)").re == Double.NEGATIVE_INFINITY)
        assertEquals("sqrt(0) 应当是 0", 0.0, evaluate("sqrt(0)").re, 0.0)
        assertEval(1.0, 0.0, "0^0")
        assertTrue("0^-1 应当是 ∞", evaluate("0^-1").re == Double.POSITIVE_INFINITY)
        assertTrue("1/z 在 z=0 处应当是 ∞", evaluate("1/z", Complex.ZERO).re == Double.POSITIVE_INFINITY)
        assertEquals("1/z 在 z=0 处的虚部应当是 0", 0.0, evaluate("1/z", Complex.ZERO).im, 0.0)
        // tan(π/2) 是极点
        assertTrue("tan(pi/2) 应当发散", !evaluate("tan(pi/2)").isFinite)
        // 溢出的指数：e^1000 直接给 ∞（IEEE），不抛
        assertTrue("exp(1000) 应当溢出成 ∞", evaluate("exp(1000)").re == Double.POSITIVE_INFINITY)
        // 整条链路都不抛异常，只是结果不可画
        assertTrue(evaluate("1/(z-z)").re.isInfinite() || evaluate("1/(z-z)").re.isNaN())
        // 病态结果照样能变成标签
        assertEquals("∞", resultLabel(evaluate("1/0")))
        assertEquals("-∞", resultLabel(evaluate("ln(0)")))
    }

    @Test
    fun `编译一次的表达式可以反复求值`() {
        val f = compileComplex("z^2+i")
        // z = 2+3i → z² = -5+12i，再加 i 得 -5+13i
        assertComplex(-5.0, 13.0, f.eval(Complex(2.0, 3.0)))
        assertComplex(0.0, 1.0, f.eval(Complex.ZERO))          // 默认实参 z = 0
        assertComplex(0.0, 1.0, f.eval())
        assertComplex(1.0, 1.0, f.eval(Complex.ONE))
        // 参数在编译期就折成常量，编译完与参数表脱钩
        val g = compileComplex("a*z", mapOf("a" to Complex(0.0, 2.0)))
        assertComplex(-2.0, 2.0, g.eval(Complex(1.0, 1.0)))
    }

    @Test
    fun `编译好的表达式可以跨线程并发求值`() {
        val f = compileComplex("z^2 + a*i", mapOf("a" to Complex(3.0, 0.0)))
        val expected = resultLabel(f.eval(Complex(1.0, 1.0)))
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                repeat(250) {
                    if (resultLabel(f.eval(Complex(1.0, 1.0))) == expected) hits.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("并发求值必须给出同一结果", 1000, hits.get())
    }

    // ---------------------------------------------------------------- 8. 参数口子

    @Test
    fun `参数默认值与ParameterSet适配`() {
        // 占位默认值：a b c d 全 1（和 ParameterSet.DEFAULT_VALUE 同一个数）
        val d = defaultParameterValues()
        assertEquals("应当正好是 a b c d 四个", ParameterSet.BINDABLE.toSet(), d.keys)
        for (name in ParameterSet.BINDABLE) {
            assertComplex(1.0, 0.0, d.getValue(name), 0.0, "参数 $name")
        }
        // 现有的实数参数表能直接适配成复数参数
        val ps = ParameterSet.of("a" to 2.0, "b" to -3.0)
        val cv = parameterValuesOf(ps)
        assertComplex(2.0, 0.0, cv.getValue("a"), 0.0)
        assertComplex(-3.0, 0.0, cv.getValue("b"), 0.0)
        assertComplex(1.0, 0.0, cv.getValue("c"), 0.0, "没显式给值的参数应当取占位默认值")
        // 适配之后表达式直接用
        assertEval(1.0, 2.0, "a*z+b", z = Complex(2.0, 1.0), params = cv)
    }

    // ---------------------------------------------------------------- 9. 复平面点标签

    @Test
    fun `复平面标签的常见形态`() {
        assertEquals("2+3i", resultLabel(Complex(2.0, 3.0)))
        assertEquals("2-3i", resultLabel(Complex(2.0, -3.0)))
        assertEquals("-2-i", resultLabel(Complex(-2.0, -1.0)))
        assertEquals("1+i", resultLabel(Complex(1.0, 1.0)))
        assertEquals("i", resultLabel(Complex(0.0, 1.0)))
        assertEquals("-i", resultLabel(Complex(0.0, -1.0)))
        assertEquals("3", resultLabel(Complex(3.0, 0.0)))
        assertEquals("3i", resultLabel(Complex(0.0, 3.0)))
        assertEquals("-1.5i", resultLabel(Complex(0.0, -1.5)))
        assertEquals("2.5", resultLabel(Complex(2.5, 0.0)))
        assertEquals("-1.5", resultLabel(Complex(-1.5, 0.0)))
        // 数字最多两位小数
        assertEquals("0.33", resultLabel(Complex(1.0 / 3.0, 0.0)))
        assertEquals("2+0.33i", resultLabel(Complex(2.0, 1.0 / 3.0)))
        assertEquals("-0.5-0.25i", resultLabel(Complex(-0.5, -0.25)))
        // 零
        assertEquals("0", resultLabel(Complex.ZERO))
        assertEquals("0", resultLabel(Complex(-0.0, -0.0)))
        // 求值结果直接当标签用
        assertEquals("2+2i", resultLabel(evaluate("(1+i)*2")))
        assertEquals("-1", resultLabel(evaluate("i^2")))
    }

    @Test
    fun `标签对NaN与inf给出可读输出`() {
        assertEquals("NaN", resultLabel(Complex.NAN))
        assertEquals("NaN", resultLabel(Complex(Double.NaN, 0.0)))
        assertEquals("NaN", resultLabel(Complex(2.0, Double.NaN)))
        assertEquals("∞", resultLabel(Complex(Double.POSITIVE_INFINITY, 0.0)))
        assertEquals("-∞", resultLabel(Complex(Double.NEGATIVE_INFINITY, 0.0)))
        assertEquals("1+∞i", resultLabel(Complex(1.0, Double.POSITIVE_INFINITY)))
        assertEquals("∞i", resultLabel(Complex(0.0, Double.POSITIVE_INFINITY)))
        assertEquals("-∞i", resultLabel(Complex(0.0, Double.NEGATIVE_INFINITY)))
        // 上游算出来的病态结果照样能变成标签，不会出 "Infinity" / "null"
        assertEquals("∞", resultLabel(evaluate("1/0")))
        assertEquals("-∞", resultLabel(evaluate("ln(0)")))
    }

    @Test
    fun `标签里的大数与极小数用科学计数法`() {
        assertEquals("1.23e7", resultLabel(Complex(12345678.0, 0.0)))
        assertEquals("1e-4", resultLabel(Complex(0.0001, 0.0)))
        assertEquals("-1e-4", resultLabel(Complex(-0.0001, 0.0)))
        assertEquals("4e-3", resultLabel(Complex(0.004, 0.0)))
        assertEquals("1e20", resultLabel(Complex(1.0e20, 0.0)))
        assertEquals("2-1.5e-5i", resultLabel(Complex(2.0, -0.000015)))
        // 小量级不会被四舍五入成 "0"
        assertTrue("非零实数不能显示成 0", resultLabel(Complex(1.0e-300, 0.0)) != "0")
    }

    // ---------------------------------------------------------------- 10. 纯函数性

    @Test
    fun `运算不修改操作数`() {
        val a = Complex(1.0, 2.0)
        val b = Complex(3.0, -4.0)
        // 把能想到的运算全跑一遍，操作数必须原封不动（值类型 + 纯函数）
        a * b
        a / b
        a + b
        a - b
        -a
        a.pow(3)
        a.pow(Complex(0.5, 0.5))
        a.conjugate.sqrt
        a.exp.log
        a.sin.tan
        assertComplex(1.0, 2.0, a, 0.0, "左操作数被改动了")
        assertComplex(3.0, -4.0, b, 0.0, "右操作数被改动了")
        // 同一个表达式对象重复求值必须是同一个结果
        val f = compileComplex("z^2 + 1")
        val first = resultLabel(f.eval(Complex(1.0, 1.0)))
        repeat(50) {
            assertEquals("重复求值结果必须一致", first, resultLabel(f.eval(Complex(1.0, 1.0))))
        }
    }
}
