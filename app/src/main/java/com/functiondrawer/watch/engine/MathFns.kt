package com.functiondrawer.watch.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * 内置函数与常量表。
 *
 * 约定（跟 Desmos 保持一致，避免用户在手表上还要猜）：
 *   log(x)   = 常用对数（以 10 为底）
 *   log(b,x) = 以 b 为底
 *   ln(x)    = 自然对数
 */
internal object MathFns {

    private val ARITY: Map<String, IntRange> = buildMap {
        put("sin", 1..1); put("cos", 1..1); put("tan", 1..1)
        put("cot", 1..1); put("sec", 1..1); put("csc", 1..1)
        put("asin", 1..1); put("acos", 1..1); put("atan", 1..1)
        put("sinh", 1..1); put("cosh", 1..1); put("tanh", 1..1); put("coth", 1..1)
        put("asinh", 1..1); put("acosh", 1..1); put("atanh", 1..1)
        put("exp", 1..1); put("ln", 1..1); put("lg", 1..1); put("lb", 1..1)
        put("log", 1..2)
        put("sqrt", 1..1); put("cbrt", 1..1)
        put("abs", 1..1); put("sign", 1..1)
        put("floor", 1..1); put("ceil", 1..1); put("round", 1..1)
        put("trunc", 1..1); put("frac", 1..1)
        put("gamma", 1..1); put("erf", 1..1); put("sinc", 1..1)
        put("deg", 1..1); put("rad", 1..1)
        put("atan2", 2..2); put("pow", 2..2); put("mod", 2..2); put("root", 2..2)
        put("hypot", 1..8); put("min", 1..8); put("max", 1..8)
        put("clamp", 3..3)
    }

    private val CONSTANTS: Map<String, Double> = mapOf(
        "pi" to PI,
        "π" to PI,
        "tau" to 2.0 * PI,
        "τ" to 2.0 * PI,
        "e" to kotlin.math.E,
        "phi" to 1.6180339887498949,
        "φ" to 1.6180339887498949,
        "inf" to Double.POSITIVE_INFINITY,
        "infinity" to Double.POSITIVE_INFINITY,
        "nan" to Double.NaN
    )

    fun arityOf(name: String): IntRange? = ARITY[name]

    fun isFunction(name: String): Boolean = ARITY.containsKey(name)

    // ---- 角度制 / 弧度制 ----
    //
    // 下面这些只在**编译期**被 Parser 用来往语法树里塞换算节点；
    // 求值期不会读任何全局开关（Expression 不可变、要能被后台线程并发求值，
    // 一个可变的全局 "当前是 DEG 还是 RAD" 会让两条线程互相污染）。

    /** 度 → 弧度：三角函数的参数要乘它。 */
    val DEG_TO_RAD: Double = PI / 180.0

    /** 弧度 → 度：反三角函数的结果要乘它。 */
    val RAD_TO_DEG: Double = 180.0 / PI

    /**
     * 参数是角度的三角函数 —— DEG 模式下参数要乘 π/180。
     *
     * 刻意**不包含**双曲函数（sinh/cosh/tanh/coth/asinh/acosh/atanh）：
     * 它们吃的是实数不是角度，双曲角的"度"没有意义。
     * 也**不包含** sinc/deg/rad：sinc 是无量纲的 sin(x)/x，
     * deg/rad 本身就是显式换算，语义不随开关变。
     */
    private val FORWARD_TRIG: Set<String> =
        setOf("sin", "cos", "tan", "cot", "sec", "csc")

    /**
     * 结果是角度的反三角函数 —— DEG 模式下结果要乘 180/π。
     *
     * atan2(y, x) 的两个参数都是普通的数（坐标），只有**结果**是角度。
     */
    private val INVERSE_TRIG: Set<String> =
        setOf("asin", "acos", "atan", "atan2")

    fun isForwardTrig(name: String): Boolean = FORWARD_TRIG.contains(name)

    fun isInverseTrig(name: String): Boolean = INVERSE_TRIG.contains(name)

    fun constantOf(name: String): Double? = CONSTANTS[name]

    /**
     * 一元函数的快速路径。
     *
     * 采样热路径上绝大多数调用都是 sin/cos/ln/abs 这类一元函数，
     * 单独走这条路可以完全避免为每次求值分配一个参数数组。
     */
    fun call1(name: String, x: Double): Double = when (name) {
        "sin" -> sin(x)
        "cos" -> cos(x)
        "tan" -> tan(x)
        "cot" -> 1.0 / tan(x)
        "sec" -> 1.0 / cos(x)
        "csc" -> 1.0 / sin(x)
        "asin" -> asin(x)
        "acos" -> acos(x)
        "atan" -> atan(x)
        "sinh" -> sinh(x)
        "cosh" -> cosh(x)
        "tanh" -> tanh(x)
        "coth" -> 1.0 / tanh(x)
        "asinh" -> asinh(x)
        "acosh" -> acosh(x)
        "atanh" -> atanh(x)
        "exp" -> exp(x)
        "ln" -> ln(x)
        "lg" -> log10(x)
        "lb" -> log2(x)
        "log" -> log10(x)
        "sqrt" -> sqrt(x)
        "cbrt" -> cbrt(x)
        "abs" -> abs(x)
        "sign" -> sign(x)
        "floor" -> floor(x)
        "ceil" -> ceil(x)
        "round" -> round(x)
        "trunc" -> if (x < 0) ceil(x) else floor(x)
        "frac" -> x - floor(x)
        "gamma" -> gamma(x)
        "erf" -> erf(x)
        "sinc" -> if (x == 0.0) 1.0 else sin(x) / x
        "deg" -> x * 180.0 / PI
        "rad" -> x * PI / 180.0
        "hypot" -> abs(x)
        "min" -> x
        "max" -> x
        else -> call(name, doubleArrayOf(x))
    }

    fun call(name: String, a: DoubleArray): Double = when (name) {
        "sin" -> sin(a[0])
        "cos" -> cos(a[0])
        "tan" -> tan(a[0])
        "cot" -> 1.0 / tan(a[0])
        "sec" -> 1.0 / cos(a[0])
        "csc" -> 1.0 / sin(a[0])
        "asin" -> asin(a[0])
        "acos" -> acos(a[0])
        "atan" -> atan(a[0])
        "atan2" -> atan2(a[0], a[1])
        "sinh" -> sinh(a[0])
        "cosh" -> cosh(a[0])
        "tanh" -> tanh(a[0])
        "coth" -> 1.0 / tanh(a[0])
        "asinh" -> asinh(a[0])
        "acosh" -> acosh(a[0])
        "atanh" -> atanh(a[0])
        "exp" -> exp(a[0])
        "ln" -> ln(a[0])
        "lg" -> log10(a[0])
        "lb" -> log2(a[0])
        "log" -> if (a.size == 1) log10(a[0]) else ln(a[1]) / ln(a[0])
        "sqrt" -> sqrt(a[0])
        "cbrt" -> cbrt(a[0])
        "abs" -> abs(a[0])
        "sign" -> sign(a[0])
        "floor" -> floor(a[0])
        "ceil" -> ceil(a[0])
        "round" -> round(a[0])
        "trunc" -> if (a[0] < 0) ceil(a[0]) else floor(a[0])
        "frac" -> a[0] - floor(a[0])
        "gamma" -> gamma(a[0])
        "erf" -> erf(a[0])
        "sinc" -> if (a[0] == 0.0) 1.0 else sin(a[0]) / a[0]
        "deg" -> a[0] * 180.0 / PI
        "rad" -> a[0] * PI / 180.0
        "pow" -> a[0].pow(a[1])
        "mod" -> mathMod(a[0], a[1])
        "root" -> nthRoot(a[0], a[1])
        "hypot" -> {
            var s = 0.0
            for (v in a) s = hypot(s, v)
            s
        }
        "min" -> a.min()
        "max" -> a.max()
        "clamp" -> if (a[0] < a[1]) a[1] else if (a[0] > a[2]) a[2] else a[0]
        else -> Double.NaN
    }

    /** 数学意义上的取模：结果符号跟随除数（-1 mod 3 = 2），不是 Kotlin 的 %。 */
    fun mathMod(x: Double, y: Double): Double =
        if (y == 0.0) Double.NaN else x - y * floor(x / y)

    /** x 的 n 次根；n 为奇数时允许负数（cbrt(-8) = -2）。 */
    fun nthRoot(x: Double, n: Double): Double = when {
        n == 0.0 -> Double.NaN
        x < 0.0 && abs(n % 2.0) == 1.0 -> -(-x).pow(1.0 / n)
        else -> x.pow(1.0 / n)
    }

    fun log2(x: Double): Double = ln(x) / ln(2.0)

    /**
     * 阶乘。非负整数走精确连乘（顺带保住 170! 以内的精度），
     * 其它情况退回 Γ(n+1)，这样 (1/2)! 之类也能算。
     */
    fun factorial(x: Double): Double {
        if (x < 0.0 && x == floor(x)) return Double.NaN
        if (x == floor(x) && x <= 170.0) {
            var r = 1.0
            var k = 2
            while (k <= x.toInt()) {
                r *= k
                k++
            }
            return r
        }
        return gamma(x + 1.0)
    }

    // ---- Γ 函数：Lanczos 近似，g=7、n=9，双精度下相对误差约 1e-13 ----

    private val LANCZOS_G = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    )

    fun gamma(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x < 0.5) {
            // 反射公式，避开负半轴上的极点
            val s = sin(PI * x)
            if (s == 0.0) return Double.NaN
            return PI / (s * gamma(1.0 - x))
        }
        val z = x - 1.0
        var acc = LANCZOS_G[0]
        for (i in 1 until 9) acc += LANCZOS_G[i] / (z + i)
        val t = z + 7.5
        return sqrt(2.0 * PI) * t.pow(z + 0.5) * exp(-t) * acc
    }

    /** 误差函数，Abramowitz & Stegun 7.1.26，最大绝对误差约 1.5e-7。 */
    fun erf(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x == 0.0) return 0.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t -
            0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return if (x < 0) -y else y
    }
}
