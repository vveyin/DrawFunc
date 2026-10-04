package com.functiondrawer.watch.engine

import java.util.Locale
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round

/**
 * 复数运算 + 复数表达式求值 —— 「复平面」模式的地基。
 *
 * 用户原话：*"给设置的坐标系选项加一个复平面，在复平面模式下没有函数只有描点和数值运算，
 * 预留的 abcd 就是为这个准备的。"* 也就是说复平面模式下用户输入的是一个含复数的
 * 表达式 / 数值运算，算出来是**一个复数**，然后被当作**一个点**画在复平面上（没有曲线）。
 * 这个文件只提供那份数学地基 + 给 UI 用的标签格式化，**不接任何 UI / 不改任何现有文件**。
 *
 * 这一层完全自包含：不依赖 [Expression] / [Lexer] / [MathFns] 的内部实现，
 * 也零 Android 依赖，所以能直接用 `tools\run-engine-tests.ps1` 离线编译测试。
 * 只**只读**地复用了同一个包里的两样东西：
 *  * [ExpressionException] —— 直接用同一种语法错误类型，UI 那边的报错处理不用分叉；
 *  * [ParameterSet] 的 `a b c d` 名字表与占位默认值 1.0 —— 和现有模式对齐。
 *
 * ## 全局约定
 *
 *  1. **支割线 / 主值**。多值函数一律取主值，辐角落在 `(-π, π]`；
 *     `log` / `pow` / `sqrt` 的支割线是**负实轴**（含原点）。
 *  2. **边界不抛异常**。除零、`log(0)`、`0` 的负数次幂这类边界一律按 IEEE 754 给出
 *     ±inf / NaN，和 `Double` 的 `/` 语义一致 —— 复平面描点是逐帧重算的，
 *     用户随手写下的 `1/z` 不该让绘制线程炸掉。只有**语法/语义错误**
 *     （括号不匹配、不认识的名字、参数个数不对）才抛 [ExpressionException]，带字符下标。
 *  3. **纯函数**。所有类型都是不可变值类型，没有全局可变状态、没有缓存、没有懒初始化，
 *     因此天然线程安全，可以跨线程并发使用（和 [Expression] 同一条约定）。
 *
 * ## 复平面模式下的预期用法（本文件不接 UI，只留口子）
 * ```
 * val z = Complex(1.0, 2.0)
 * val w = evaluate("z^2 + i", z, defaultParameterValues())   // 得到结果复数
 * val label = resultLabel(w)                                  // 画在复平面上的点标签
 * ```
 */
data class Complex(val re: Double, val im: Double) {

    // ---------------------------------------------------------------- 四则运算

    /** 加法：实部虚部分别相加。 */
    operator fun plus(o: Complex): Complex = Complex(re + o.re, im + o.im)

    /** 减法：实部虚部分别相减。 */
    operator fun minus(o: Complex): Complex = Complex(re - o.re, im - o.im)

    /** 乘法 `(a+bi)(c+di) = (ac−bd) + (ad+bc)i`。 */
    operator fun times(o: Complex): Complex =
        Complex(re * o.re - im * o.im, re * o.im + im * o.re)

    /**
     * 除法 `(a+bi)/(c+di)`。
     *
     * 用 **Smith 算法**（按 `|c|`、`|d|` 谁大选主元），而不是教科书里的
     * `((ac+bd) + (bc−ad)i)/(c²+d²)`：后者在 c、d 很大时 `c²+d²` 会**上溢**
     * （c = 1e200 → c² = ∞ → 结果整片变 NaN），很小时又会**下溢**成 0 丢精度。
     * Smith 算法全程只出现 `d/c` 这种比值，两头都不溢出。
     *
     * `c = d = 0`（除零）时**不抛异常**，按 IEEE 754 的分量语义给出 ±∞ / NaN
     * （每个分量各自算 `x/0`）：
     *  * 分子虚部为 0（实数除法）→ `(±∞, 0)`：和实数模式里 `1/0 = ∞` 完全一致，
     *    UI 标签也能显示成 "∞"，而不是信息量为零的 "NaN"；
     *  * 分子虚部非零 → 两个分量各自发散，`(∞, ∞)`（标签 "∞+∞i"）；
     *  * `0/0` → `(NaN, 0)`；`(0+2i)/0` → `(NaN, ∞)`。
     */
    operator fun div(o: Complex): Complex {
        val c = o.re
        val d = o.im
        if (c == 0.0 && d == 0.0) {
            return if (im == 0.0) Complex(re / 0.0, 0.0) else Complex(re / 0.0, im / 0.0)
        }
        return if (kotlin.math.abs(c) >= kotlin.math.abs(d)) {
            val r = d / c
            val s = 1.0 / (c + d * r)
            Complex((re + im * r) * s, (im - re * r) * s)
        } else {
            val r = c / d
            val s = 1.0 / (c * r + d)
            Complex((re * r + im) * s, (im * r - re) * s)
        }
    }

    /** 取负。 */
    operator fun unaryMinus(): Complex = Complex(-re, -im)

    // ---- 和实数混算的语法糖（省一次 `Complex(r, 0.0)`）----

    /** `z + 2`。 */
    operator fun plus(r: Double): Complex = Complex(re + r, im)

    /** `z - 2`。 */
    operator fun minus(r: Double): Complex = Complex(re - r, im)

    /** `z * 2`。 */
    operator fun times(r: Double): Complex = Complex(re * r, im * r)

    /** `z / 2`。走 [Complex.div] 的同一套语义，所以 `z / 0.0` 和 `z / Complex.ZERO` 完全一致。 */
    operator fun div(r: Double): Complex = div(Complex(r, 0.0))

    // ---------------------------------------------------------------- 度量与共轭

    /**
     * 模（绝对值）`|z| = √(x²+y²)`。
     *
     * 用 [hypot] 而不是 `sqrt(x*x + y*y)`：后者在 x、y ~ 1e200 时平方就上溢成 ∞，
     * 在 ~1e-200 时平方又下溢成 0；hypot 内部做了缩放，两头都对。
     */
    val abs: Double get() = hypot(re, im)

    /**
     * 辐角 `arg z = atan2(y, x)`，单位弧度，**值域 (-π, π]**。
     *
     * `atan2` 本身的值域是 [-π, π]，只有"y 是负零且 x < 0"这一种边界会给出 -π
     * （`Complex(-1.0, -0.0)`）。这里把 -π 归一成 +π，让值域严格是 (-π, π] ——
     * 用户看到的 `arg(-1)` 和 `arg(-1 - 0i)` 都是 π，不会出现两个答案。
     * `arg 0 = 0`（未定义，取 0 这个最省事的约定，避免 UI 上冒出 NaN）。
     */
    val arg: Double
        get() {
            if (re == 0.0 && im == 0.0) return 0.0
            val a = atan2(im, re)
            return if (a <= -PI) PI else a
        }

    /** 共轭 `x − yi`。 */
    val conjugate: Complex get() = Complex(re, -im)

    /** 模的平方 `|z|² = x²+y²`（比较模大小时省一次开方，但会溢出，见 [abs]）。 */
    val normSquared: Double get() = re * re + im * im

    /** 两个分量都是有限数（既不是 inf 也不是 NaN）。 */
    val isFinite: Boolean get() = re.isFinite() && im.isFinite()

    // ---------------------------------------------------------------- 幂

    /**
     * 整数幂 `z^n`。
     *
     * 用**平方-乘**（exponentiation by squaring）而不是绕道 `exp(n·log z)`：
     *  * 整数次幂是**代数运算**，连乘能得到精确结果 —— `(1+i)²` 精确等于 `2i`，
     *    而走 exp/log 会得到 2.0000000000000004i；
     *  * 它**不碰支割线**：`(-1)² = 1`、`(-8)^(1/3)` 之类的坑不存在，
     *    因为指数是整数，没有对数分支可言。
     *
     * `n = 0` 恒等于 `1`（含 `0^0 = 1`，与 `Math.pow(0.0, 0.0)` 一致）；
     * `n < 0` 时先算正幂再取倒数，于是 `0^-1 = (∞, NaN)`（除零语义，不抛异常）。
     */
    fun pow(n: Int): Complex {
        if (n == 0) return ONE
        var k = if (n < 0) -(n.toLong()) else n.toLong()
        var base = this
        var acc = ONE
        while (k > 0L) {
            if ((k and 1L) != 0L) acc *= base
            k = k shr 1
            if (k > 0L) base *= base
        }
        return if (n < 0) ONE / acc else acc
    }

    /**
     * 复数幂 `z^w = exp(w·log z)`，主值（支割线负实轴）。
     *
     * `z = 0` 时 `log z = -∞ + 0i`，直接代公式会得到一堆 NaN，所以先按极限特判
     * （这一组约定是**本文件定的**，本意是"能给一个方向的就不给 NaN"）：
     *  * `w = 0` → `1`（`0^0 = 1`，与 `Math.pow(0.0, 0.0)`、C99 `cpow` 一致）；
     *  * `Re(w) > 0` → `0`（模按 `exp(-∞·Re w)` 收敛到 0）；
     *  * `Re(w) < 0` → `(∞, 0)`：模发散到 +∞，而方向 `Im(w)·∞` 不定，
     *    这里只保留 +∞ 实部、虚部写 0（不写 NaN：UI 标签宁可显示 "∞" 也不要 "NaN"）；
     *  * `Re(w) = 0 且 w ≠ 0`（纯虚指数）→ `(NaN, NaN)`：`|0^(iy)|` 是"模 1 × 辐角 -y·∞"
     *    的未定式，数学上没有主值，只能给 NaN。
     */
    fun pow(w: Complex): Complex {
        if (re == 0.0 && im == 0.0) {
            return when {
                w.re > 0.0 -> ZERO
                w.re < 0.0 -> INF
                w.im == 0.0 -> ONE
                else -> NAN
            }
        }
        return (w * log).exp
    }

    /**
     * 实数幂 `z^r = exp(r·log z)`，主值。
     *
     * 注意这类幂**有**支割线：`(-1)^0.5 = i`（而不是 -1 的实数平方根那套），
     * 因为 `log(-1) = iπ` 取的是主值辐角。
     */
    fun pow(r: Double): Complex = pow(Complex(r, 0.0))

    // ---------------------------------------------------------------- 指数 / 对数 / 开方

    /**
     * 指数 `e^z = e^x(cos y + i sin y)`。
     *
     * |x| 很大时结果**真的**会超过 double 上限（|e^z| = e^x），此时按 IEEE 给 ±∞ / NaN，
     * 不做也无从做"防溢出"处理。
     */
    val exp: Complex
        get() {
            val e = kotlin.math.exp(re)
            return Complex(e * kotlin.math.cos(im), e * kotlin.math.sin(im))
        }

    /**
     * 主值自然对数 `log z = ln|z| + i·arg z`。
     *
     * 支割线是负实轴，辐角取 (-π, π]（见 [arg]）：`log(-1) = iπ`，
     * `log(-1 − 0i)` 也返回 +iπ。代价是 `log(conj z)` 在负实轴上不再严格等于
     * `conj(log z)`；换来"辐角恒落在 (-π, π]"这条对 UI 更重要的约定。
     *
     * `z = 0` 时按极限给 `(-∞, 0)`：`ln 0 = -∞`，方向不定取 0（不抛异常）。
     */
    val log: Complex
        get() = Complex(ln(hypot(re, im)), arg)

    /**
     * 主值平方根 `√z = u + vi`，其中
     * `u = √((|z|+x)/2)`、`v = sgn(y)·√((|z|−x)/2)`，支割线负实轴。
     *
     * 直接套上面两个式子的问题是：x > 0 且 |y| 很小时 `|z| − x` 会**灾难性相消**
     * （两个几乎相等的数相减，有效位全丢）。所以分成两支，各用一次不产生相消的公式：
     *  * `x ≥ 0`：先算 `u = √((|z|+x)/2)`，再由 `|z|² − x² = y²` 推出下半部分等于
     *    `y²/(4u²)`，于是 `v = y/(2u)`；
     *  * `x < 0`：对称地先算 `v = √((|z|−x)/2)`，再 `u = y/(2v)`。
     *
     * 两个细节：
     *  * 写 `|z|*0.5 + x*0.5` 而不是 `(|z|+x)/2`：|z| 接近 double 上限时
     *    `|z| + x` 会上溢成 ∞（除以 2 是精确的，先除不会丢精度）；
     *  * 虚部为零（含 ±0.0）时取 **+1** 符号，所以 `√(−1) = i` 而不是 `−i` ——
     *    这正是"支割线沿负实轴取主值"的通常约定（C99 `csqrt` 对 −0.0 会区分上下岸，
     *    这里刻意不区分，取 +i 一侧，用户更容易预期）。
     *
     * `z = 0` 直接给 `0`（否则 `v = y/(2u)` 会变成 0/0 = NaN）。
     * 实部/虚部出现 ±∞ 时不做特判，`|z| − x` 会是 ∞ − ∞ = NaN —— 这种输入只可能来自
     * 上游已经溢出的表达式，NaN 是合理的传播结果（UI 标签会显示 "NaN"）。
     */
    val sqrt: Complex
        get() {
            if (re == 0.0 && im == 0.0) return ZERO
            val m = hypot(re, im)
            return if (re >= 0.0) {
                val u = kotlin.math.sqrt(m * 0.5 + re * 0.5)
                Complex(u, im / (2.0 * u))
            } else {
                val v = kotlin.math.sqrt(m * 0.5 - re * 0.5) * (if (im < 0.0) -1.0 else 1.0)
                Complex(im / (2.0 * v), v)
            }
        }

    // ---------------------------------------------------------------- 三角 / 双曲
    //
    // 三个恒等式（把复三角化成实三角 × 实双曲）：
    //   sin(x+yi) = sin x·cosh y + i·cos x·sinh y
    //   cos(x+yi) = cos x·cosh y − i·sin x·sinh y
    //   tan(x+yi) = sin(2x)/(cos 2x + cosh 2y) + i·sinh(2y)/(cos 2x + cosh 2y)

    /**
     * 正弦 `sin(x+yi) = sin x·cosh y + i·cos x·sinh y`。
     *
     * 这里**不**做"防溢出"改写：`|sin z|` 的真实量级就是 `e^|y|/2`，
     * 虚部超过约 710 时这个值本身就超出 double 上限，溢出成 ±∞ 是 IEEE 下唯一正确的答案，
     * 用 hypot 之类也变不出不存在的量程。|y| ≤ 709 的范围内 `sinh`/`cosh` 各自
     * 没有相消、没有额外误差，所以不需要改写。真正会"假溢出"的是 [tan]
     * （分子分母同阶爆炸），那里才必须改写 —— 见 [tan] 的说明。
     */
    val sin: Complex
        get() = Complex(
            kotlin.math.sin(re) * kotlin.math.cosh(im),
            kotlin.math.cos(re) * kotlin.math.sinh(im)
        )

    /** 余弦 `cos(x+yi) = cos x·cosh y − i·sin x·sinh y`（溢出说明同 [sin]）。 */
    val cos: Complex
        get() = Complex(
            kotlin.math.cos(re) * kotlin.math.cosh(im),
            -kotlin.math.sin(re) * kotlin.math.sinh(im)
        )

    /**
     * 正切 `tan z`，**改写过的稳定形式**（这是本文件里唯一必须改写的三角公式）。
     *
     * 直接用 `sin z / cos z` 的问题：大 |y| 时 sin 与 cos 都 ~ `e^|y|/2`，
     * 两个 ∞ 相除得到 NaN，而真实极限是好端端的 ±i。
     *
     * 推法：把 `tan(x+yi)` 的分子分母同乘 `m = e^(−2|y|)`（0 < m ≤ 1，绝不溢出）：
     * ```
     *   tan(x+yi) = [ sin 2x·m + i·s·(1−m²)/2 ] / [ cos 2x·m + (1+m²)/2 ]
     * ```
     * 其中 `s = sgn(y)`（用了 `cosh 2y·m = (1+m²)/2`、`|sinh 2y|·m = (1−m²)/2`）。
     * 分母 `= (1−m)²/2 + m(1+cos 2x)` 恒 ≥ 0，等于 0 当且仅当 `y = 0 且 cos 2x = −1`，
     * 也就是 tan 真正的极点 `x = π/2 + kπ` —— 那里给 ∞/NaN 正是 IEEE 想要的。
     *
     * 于是 `|y|` 再大也**不会假溢出**：m 下溢成 0 时公式自动退化成极限 `i·sgn(y)`。
     */
    val tan: Complex
        get() {
            val x2 = 2.0 * re
            val m = kotlin.math.exp(-2.0 * kotlin.math.abs(im))
            val s = if (im < 0.0) -1.0 else 1.0
            val dr = kotlin.math.cos(x2) * m + (1.0 + m * m) * 0.5
            val nr = kotlin.math.sin(x2) * m
            val ni = s * (1.0 - m * m) * 0.5
            return Complex(nr, ni) / Complex(dr, 0.0)
        }

    /** 双曲正弦 `sinh(x+yi) = sinh x·cos y + i·cosh x·sin y`（溢出同 [sin]：量级就是 e^|x|/2）。 */
    val sinh: Complex
        get() = Complex(
            kotlin.math.sinh(re) * kotlin.math.cos(im),
            kotlin.math.cosh(re) * kotlin.math.sin(im)
        )

    /** 双曲余弦 `cosh(x+yi) = cosh x·cos y + i·sinh x·sin y`。 */
    val cosh: Complex
        get() = Complex(
            kotlin.math.cosh(re) * kotlin.math.cos(im),
            kotlin.math.sinh(re) * kotlin.math.sin(im)
        )

    companion object {

        /** `0 + 0i`。 */
        val ZERO: Complex = Complex(0.0, 0.0)

        /** `1 + 0i`。 */
        val ONE: Complex = Complex(1.0, 0.0)

        /** 虚数单位 `i`，`i² = −1`。表达式里也写作 `i`。 */
        val I: Complex = Complex(0.0, 1.0)

        /** `+∞ + 0i`：发给 UI 的"发散"结果（虚部方向不定的场合统一取 0）。 */
        val INF: Complex = Complex(Double.POSITIVE_INFINITY, 0.0)

        /** `NaN + NaN i`：数学上无定义的结果（如 `0^i`、未定式）。 */
        val NAN: Complex = Complex(Double.NaN, Double.NaN)
    }
}

// ---------------------------------------------------------------- 与实数互操作

/** 实数 → 复数（虚部 0）。 */
fun Double.toComplex(): Complex = Complex(this, 0.0)

/** `2 * z`，省一次 `Complex(2.0, 0.0)`。 */
operator fun Double.times(c: Complex): Complex = Complex(this * c.re, this * c.im)

/** `2 + z`。 */
operator fun Double.plus(c: Complex): Complex = Complex(this + c.re, c.im)

// =====================================================================================
// 复数表达式求值器
//
// 刻意**自包含**：不复用 [Lexer] / [Parser] / [Node]（那些是给实数采样热路径服务的，
// 逻辑和 `Expression.kt` 一起演进，这里插进去会互相牵制），而是在本文件里另写一份
// 面向复数的小词法器 + 递归下降解析器。语法刻意贴近项目已有的数学语法（见 [compileComplex]），
// 用户从 `x^2+1` 迁到 `z^2+i` 不用重新学。
//
// 类型名统一加 Complex 前缀（ComplexToken / ComplexLexer / ComplexParser / ComplexNode），
// 免得和同包里 Expression.kt / Lexer.kt 的 internal 名字撞车。
// =====================================================================================

internal enum class ComplexTokenType { NUMBER, IDENT, OP, LPAREN, RPAREN, COMMA, BAR, EOF }

internal class ComplexToken(
    val type: ComplexTokenType,
    val text: String,
    val pos: Int,
    val value: Double = 0.0
) {
    override fun toString(): String = "$type('$text')@$pos"
}

/**
 * 复数表达式的词法分析。
 *
 * 和 [Lexer] 保持同样的宽容度，因为手表上的自绘键盘会往输入框里插好看的符号：
 *   `× · ⋅` → 乘   `÷` → 除   `−`(U+2212) → 减   `**` → 幂
 *   `√` → sqrt   `∞` → inf   `[ ]` → 括号   `;` → 逗号
 *
 * 词法层不做名字分类：`z`、`i`、`π`、`a`、`sin` 在这里都只是 IDENT，
 * 到底算主变量、常量、参数还是函数，由 [ComplexParser] 按固定顺序判定。
 *
 * 不认识的符号（`!`、`%`、`=`、`∛`……）直接报"看不懂这个字符"：复数的阶乘、取模、
 * 等式（复平面模式下没有曲线，等式没有意义）这一版都不做，宁可明确报错也不要静默算错。
 */
internal class ComplexLexer(private val src: String) {

    private var i = 0
    private val out = ArrayList<ComplexToken>()

    fun tokenize(): List<ComplexToken> {
        while (true) {
            skipWhitespace()
            if (i >= src.length) break
            val start = i
            val c = src[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) ->
                    out.add(readNumber())
                c.isLetter() || c == '_' -> out.add(readIdent())
                else -> readSymbol(start)
            }
        }
        out.add(ComplexToken(ComplexTokenType.EOF, "", src.length))
        return out
    }

    private fun skipWhitespace() {
        while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++
    }

    private fun readIdent(): ComplexToken {
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        return ComplexToken(ComplexTokenType.IDENT, src.substring(start, i), start)
    }

    private fun readNumber(): ComplexToken {
        val start = i
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        // 指数部分只在后面确实跟着数字时才吞掉，否则 "2e" 应该理解成 2*e（欧拉数），
        // 而不是一个残缺的科学计数法。这条规则和 [Lexer] 逐字一致。
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            var j = i + 1
            if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
            if (j < src.length && src[j].isDigit()) {
                i = j
                while (i < src.length && src[i].isDigit()) i++
            }
        }
        val text = src.substring(start, i)
        val v = text.toDoubleOrNull()
            ?: throw ExpressionException("数字格式不正确：$text", start)
        return ComplexToken(ComplexTokenType.NUMBER, text, start, v)
    }

    private fun readSymbol(start: Int) {
        val c = src[i]

        // 双字符运算符 ** 视同 ^
        if (c == '*' && i + 1 < src.length && src[i + 1] == '*') {
            i += 2
            out.add(ComplexToken(ComplexTokenType.OP, "^", start))
            return
        }

        i++
        val token = when (c) {
            '+', '-' -> ComplexToken(ComplexTokenType.OP, c.toString(), start)
            '−' -> ComplexToken(ComplexTokenType.OP, "-", start)          // U+2212 真减号
            '*', '×', '·', '⋅' -> ComplexToken(ComplexTokenType.OP, "*", start)
            '/', '÷' -> ComplexToken(ComplexTokenType.OP, "/", start)
            '^' -> ComplexToken(ComplexTokenType.OP, "^", start)
            '(', '[' -> ComplexToken(ComplexTokenType.LPAREN, "(", start)
            ')', ']' -> ComplexToken(ComplexTokenType.RPAREN, ")", start)
            ',', ';' -> ComplexToken(ComplexTokenType.COMMA, ",", start)
            '|' -> ComplexToken(ComplexTokenType.BAR, "|", start)
            '√' -> ComplexToken(ComplexTokenType.IDENT, "sqrt", start)
            '∞' -> ComplexToken(ComplexTokenType.IDENT, "inf", start)
            else -> throw ExpressionException("看不懂这个字符：'$c'", start)
        }
        out.add(token)
    }
}

/**
 * 复数语法树。
 *
 * 和 [Node] 一样做成 sealed class + 每次求值直接递归：复平面模式一个表达式只算**一个点**
 * （不是几万点采样），完全不需要闭包链那种优化，可读性和可调试性更重要。
 *
 * 参数 `a b c d` 在这里**看不见** —— 它们在解析期就被折成了 [Const]（和 [Expression] 把
 * [ParameterSet] 折成常量是同一个手法），所以 [eval] 只依赖主变量 z，是纯函数。
 */
internal sealed class ComplexNode {

    abstract fun eval(z: Complex): Complex

    class Const(val value: Complex) : ComplexNode() {
        override fun eval(z: Complex): Complex = value
    }

    /** 主变量 `z`（复平面上被描的那个点）。 */
    object Var : ComplexNode() {
        override fun eval(z: Complex): Complex = z
    }

    class Neg(val child: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex = -child.eval(z)
    }

    class Add(val l: ComplexNode, val r: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex = l.eval(z) + r.eval(z)
    }

    class Sub(val l: ComplexNode, val r: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex = l.eval(z) - r.eval(z)
    }

    class Mul(val l: ComplexNode, val r: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex = l.eval(z) * r.eval(z)
    }

    class Div(val l: ComplexNode, val r: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex = l.eval(z) / r.eval(z)
    }

    class Pow(val l: ComplexNode, val r: ComplexNode) : ComplexNode() {
        override fun eval(z: Complex): Complex {
            val base = l.eval(z)
            val e = r.eval(z)
            // 指数是"实整数"时改走 [Complex.pow] 的平方-乘：
            //  * `z^2`、`i^2`、`2^3` 这类整数幂在复平面表达式里极常见，按定义就是代数运算，
            //    绕道 exp(w·log z) 只会平白引入 1e-16 级误差（`i^2` 会得到 -1+1.22e-16i），
            //    还会被支割线带着走（`(-1)^2` 要绕负实轴一圈）；
            //  * 走平方-乘后结果精确、也不碰对数分支，和实数模式里 `Math.pow(x, 2.0)` 一致。
            if (e.im == 0.0 && e.re == floor(e.re) &&
                e.re >= INT_MIN_AS_DOUBLE && e.re <= INT_MAX_AS_DOUBLE
            ) {
                return base.pow(e.re.toInt())
            }
            return base.pow(e)
        }
    }

    class Call(val name: String, val args: List<ComplexNode>) : ComplexNode() {
        override fun eval(z: Complex): Complex {
            // 一元函数占绝对多数，单独走一条不分配 List 的路径
            // （和 [Node.Call] 同样的考虑；这里的求值次数少得多，纯粹是顺手）。
            if (args.size == 1) return callComplex1(name, args[0].eval(z))
            return callComplex2(name, args[0].eval(z), args[1].eval(z))
        }
    }
}

// ---------------------------------------------------------------- 函数表

/** 函数名（**全小写**，查表时把用户写的一律 lowercased）→ 允许的参数个数。 */
private val COMPLEX_ARITY: Map<String, IntRange> = mapOf(
    "abs" to 1..1,
    "arg" to 1..1,
    "conj" to 1..1,
    "conjugate" to 1..1,
    "re" to 1..1,
    "im" to 1..1,
    "exp" to 1..1,
    "ln" to 1..1,
    // log 与项目其它地方一致：1 个参数 = 常用对数（以 10 为底），2 个参数 log(b, x) = 以 b 为底。
    // 复数的"常用对数"就是把主值自然对数除以 ln10（和实数那边 log10 的定义一致）。
    "log" to 1..2,
    "sqrt" to 1..1,
    "sin" to 1..1,
    "cos" to 1..1,
    "tan" to 1..1,
    "sinh" to 1..1,
    "cosh" to 1..1,
    "pow" to 2..2
)

private val COMPLEX_CONSTANTS: Map<String, Complex> = mapOf(
    "i" to Complex.I,
    "pi" to Complex(PI, 0.0),
    "π" to Complex(PI, 0.0),
    "tau" to Complex(2.0 * PI, 0.0),
    "τ" to Complex(2.0 * PI, 0.0),
    "e" to Complex(E, 0.0),
    "inf" to Complex(Double.POSITIVE_INFINITY, 0.0),
    "infinity" to Complex(Double.POSITIVE_INFINITY, 0.0),
    "nan" to Complex.NAN
)

private val LN10: Double = ln(10.0)

/** 复平面模式的主变量名。 */
private const val COMPLEX_VARIABLE = "z"

/** `Int` 值域的两端（写成 Double），用来判断一个实指数能不能安全地转成 Int。 */
private const val INT_MIN_AS_DOUBLE: Double = -2147483648.0
private const val INT_MAX_AS_DOUBLE: Double = 2147483647.0

private fun complexConstantOf(name: String): Complex? =
    COMPLEX_CONSTANTS[name] ?: COMPLEX_CONSTANTS[name.lowercase()]

/**
 * 一元复数函数分派。名字一律小写（[ComplexParser] 负责规范化）。
 *
 * 不认识的函数名给 `NaN` 而不是抛异常：走到这里意味着解析期已经查过 arity 表，
 * 真出现就是本文件自己的 bug，返回 NaN 不会把绘制线程炸掉。
 */
private fun callComplex1(name: String, v: Complex): Complex = when (name) {
    "abs" -> Complex(hypot(v.re, v.im), 0.0)
    "arg" -> Complex(v.arg, 0.0)
    "conj", "conjugate" -> v.conjugate
    "re" -> Complex(v.re, 0.0)
    "im" -> Complex(v.im, 0.0)
    "exp" -> v.exp
    "ln" -> v.log
    "log" -> v.log / Complex(LN10, 0.0)
    "sqrt" -> v.sqrt
    "sin" -> v.sin
    "cos" -> v.cos
    "tan" -> v.tan
    "sinh" -> v.sinh
    "cosh" -> v.cosh
    else -> Complex.NAN
}

/** 二元复数函数分派（`pow(x, y)` 与两参数的 `log(b, x)`）。 */
private fun callComplex2(name: String, x: Complex, y: Complex): Complex = when (name) {
    "pow" -> x.pow(y)
    "log" -> y.log / x.log          // log(b, x) = ln x / ln b，与 MathFns.call 的约定一致
    else -> Complex.NAN
}

// ---------------------------------------------------------------- 解析器

/**
 * 递归下降 + 优先级爬升。
 *
 * 优先级（从低到高）：加减 → 乘除/隐式乘 → 一元正负 → 幂(右结合) → 基本项。
 * 和 [Parser] 完全一致，包括 `-2^2 = -4`、`2^3^2 = 512` 这两个容易记错的点。
 *
 * @param params 参数名 → 取值。**编译期**折成 [ComplexNode.Const]，不进语法树，
 *               于是 [ComplexNode.eval] 的签名和求值路径都不用理会参数。
 */
internal class ComplexParser(
    private val tokens: List<ComplexToken>,
    private val params: Map<String, Complex>
) {

    private var p = 0

    /**
     * 当前嵌套在几层 `|...|` 里。
     *
     * 解决和 [Parser] 里一模一样的歧义：`2|z|` 的第一个 `|` 应该开启一个新操作数
     * （隐式乘法），而 `|z|` 结尾那个 `|` 是闭合符号，绝不能再开启操作数。
     * 光看 token 类型分不出来，靠"是否已经在绝对值内部"判定。
     */
    private var insideBars = 0

    private fun peek(): ComplexToken = tokens[p]

    private fun matchOp(text: String): Boolean {
        val t = peek()
        if (t.type == ComplexTokenType.OP && t.text == text) {
            p++
            return true
        }
        return false
    }

    fun parse(): ComplexNode {
        val n = parseSum()
        val t = peek()
        if (t.type != ComplexTokenType.EOF) {
            throw ExpressionException(unexpectedMessage(t), t.pos)
        }
        return n
    }

    private fun unexpectedMessage(t: ComplexToken): String = when (t.type) {
        ComplexTokenType.RPAREN -> "多余的右括号"
        ComplexTokenType.OP -> "运算符 '${t.text}' 的位置不对"
        ComplexTokenType.COMMA -> "逗号只能出现在函数参数里"
        ComplexTokenType.NUMBER -> "数字 '${t.text}' 前面缺少运算符"
        ComplexTokenType.IDENT -> "名字 '${t.text}' 前面缺少运算符"
        ComplexTokenType.BAR -> "绝对值符号 | 的位置不对"
        else -> "表达式在这里断掉了"
    }

    private fun parseSum(): ComplexNode {
        var left = parseProduct()
        while (true) {
            when {
                matchOp("+") -> left = ComplexNode.Add(left, parseProduct())
                matchOp("-") -> left = ComplexNode.Sub(left, parseProduct())
                else -> return left
            }
        }
    }

    private fun parseProduct(): ComplexNode {
        var left = parseUnary()
        while (true) {
            when {
                matchOp("*") -> left = ComplexNode.Mul(left, parseUnary())
                matchOp("/") -> left = ComplexNode.Div(left, parseUnary())
                // 隐式乘法：紧跟一个能开启操作数的 token（2z、3i、zi、2(z+1)……）
                startsOperand(peek()) -> left = ComplexNode.Mul(left, parseUnary())
                else -> return left
            }
        }
    }

    /**
     * 下一个 token 能不能开启一个操作数（决定是否插入隐式乘法）。
     *
     * 刻意**不**把 NUMBER 算进来（和 [Parser] 一致）：`1.2.3`、`2 3` 这类输入应该报错，
     * 而不是被静默理解成 `1.2*3`、`2*3`。
     */
    private fun startsOperand(t: ComplexToken): Boolean = when (t.type) {
        ComplexTokenType.IDENT, ComplexTokenType.LPAREN -> true
        ComplexTokenType.BAR -> insideBars == 0
        else -> false
    }

    private fun parseUnary(): ComplexNode = when {
        matchOp("-") -> ComplexNode.Neg(parseUnary())
        matchOp("+") -> parseUnary()
        else -> parsePower()
    }

    private fun parsePower(): ComplexNode {
        val base = parsePrimary()
        // 幂右结合，且指数允许一元负号：2^3^2 = 512、2^-1 = 0.5
        return if (matchOp("^")) ComplexNode.Pow(base, parseUnary()) else base
    }

    private fun parsePrimary(): ComplexNode {
        val t = peek()
        return when (t.type) {
            ComplexTokenType.NUMBER -> {
                p++
                ComplexNode.Const(Complex(t.value, 0.0))
            }

            ComplexTokenType.LPAREN -> {
                p++
                val inner = parseSum()
                val close = peek()
                if (close.type != ComplexTokenType.RPAREN) {
                    throw ExpressionException("括号没有闭合", close.pos)
                }
                p++
                inner
            }

            ComplexTokenType.BAR -> {
                p++
                insideBars++
                val inner = try {
                    parseSum()
                } finally {
                    insideBars--
                }
                val close = peek()
                if (close.type != ComplexTokenType.BAR) {
                    throw ExpressionException("绝对值符号 | 没有成对出现", close.pos)
                }
                p++
                ComplexNode.Call("abs", listOf(inner))
            }

            ComplexTokenType.IDENT -> parseIdent(t)

            ComplexTokenType.EOF -> throw ExpressionException("表达式不完整", t.pos)
            else -> throw ExpressionException(unexpectedMessage(t), t.pos)
        }
    }

    /**
     * 名字判定顺序（和 [Parser.parseIdent] 同一个骨架）：
     * 主变量 `z` → 调用方给的参数 → `a b c d` 占位 → 常量 → 函数 → 拆成单字母乘积 → 报错。
     *
     * 顺序要点：
     *  * `z` 最优先，所以哪怕调用方往 params 里塞了 "z" 也不会把主变量顶掉；
     *  * `a b c d` 就算没出现在 params 里也按 [ParameterSet.DEFAULT_VALUE]（1.0）算，
     *    和实数模式"不给参数时 `a*x+b` 等价于 `x+1`"的行为一致 —— 不然用户刚打完
     *    `a` 就报"不认识的名字"，体验会和其它模式对不上；
     *  * 常量/函数查表时把名字 lowercased 再查一次，于是 `PI`、`Re`、`Im`、`SIN` 都能用。
     */
    private fun parseIdent(t: ComplexToken): ComplexNode {
        val name = t.text
        p++

        if (name == COMPLEX_VARIABLE) return ComplexNode.Var

        singleValue(name)?.let { return ComplexNode.Const(it) }

        val arity = COMPLEX_ARITY[name] ?: COMPLEX_ARITY[name.lowercase()]
        if (arity != null) {
            val fn = name.lowercase()
            return if (peek().type == ComplexTokenType.LPAREN) {
                parseCallArgs(fn, arity, t.pos)
            } else {
                // 允许省括号的写法（手表键盘上少打两个字符）：`sin z`、`√z`
                if (1 !in arity) {
                    throw ExpressionException("函数 $fn 需要 ${arity.first} 个参数，要写括号", t.pos)
                }
                ComplexNode.Call(fn, listOf(parseUnary()))
            }
        }

        splitIntoProduct(name)?.let { return it }

        throw ExpressionException("不认识的名字 '$name'。" + hintFor(name), t.pos)
    }

    /**
     * 单个名字在**非函数**意义下的取值：参数 → `a b c d` 占位 → 常量。
     * 找不到给 null，让调用方继续走后面的分支/报错。
     */
    private fun singleValue(name: String): Complex? {
        params[name]?.let { return it }
        if (name in ParameterSet.BINDABLE) {
            return Complex(ParameterSet.DEFAULT_VALUE, 0.0)
        }
        return complexConstantOf(name)
    }

    /**
     * 把 `az`、`zi`、`pi` 之外的未知多字母名字拆成单字符的乘积 ——
     * 和 [Parser.trySplitIntoProduct] 支持 `xy`、`ax`、`2πx` 是同一个理由：
     * 手表键盘一次一个字母，用户很自然会写 `az` 表示 `a*z`。
     *
     * 只在其它解释全部失败后才尝试，所以 `sin`、`inf`、`nan` 这些真名字不会被拆坏；
     * 只要有一个字符不认识就返回 null，外层照常报"不认识的名字 'xxx'"。
     */
    private fun splitIntoProduct(name: String): ComplexNode? {
        if (name.length < 2) return null
        var node: ComplexNode? = null
        for (ch in name) {
            val one = ch.toString()
            val child: ComplexNode = if (one == COMPLEX_VARIABLE) {
                ComplexNode.Var
            } else {
                ComplexNode.Const(singleValue(one) ?: return null)
            }
            node = if (node == null) child else ComplexNode.Mul(node, child)
        }
        return node
    }

    private fun parseCallArgs(name: String, arity: IntRange, namePos: Int): ComplexNode {
        p++ // 吃掉 '('
        val args = ArrayList<ComplexNode>(2)
        if (peek().type == ComplexTokenType.RPAREN) {
            throw ExpressionException("函数 $name 缺少参数", namePos)
        }
        while (true) {
            args.add(parseSum())
            when (peek().type) {
                ComplexTokenType.COMMA -> p++
                ComplexTokenType.RPAREN -> {
                    p++
                    break
                }
                else -> throw ExpressionException(
                    "函数 $name 的参数列表少了一个逗号或右括号",
                    peek().pos
                )
            }
        }
        if (args.size !in arity) {
            throw ExpressionException(
                "函数 $name 需要 ${arityDescription(arity)} 个参数，现在给了 ${args.size} 个",
                namePos
            )
        }
        return ComplexNode.Call(name, args)
    }

    private fun arityDescription(arity: IntRange): String =
        if (arity.first == arity.last) "${arity.first}" else "${arity.first}~${arity.last}"

    /** 报错时的补救提示。错误信息里一定要**点名**，用户才知道该改哪个字。 */
    private fun hintFor(name: String): String {
        // "sinx" 这种：去掉最后一个字母正好是个函数名 —— 那用户想要的显然是 sin(x)
        val head = name.dropLast(1).lowercase()
        return if (name.length > 1 && COMPLEX_ARITY.containsKey(head)) {
            "函数要加括号，比如 ${name.dropLast(1)}(${name.last()})"
        } else {
            "可用的名字：变量 $COMPLEX_VARIABLE；可绑定参数 " +
                ParameterSet.BINDABLE.joinToString("、") +
                "；常量 i、π、e；函数 " + COMPLEX_ARITY.keys.joinToString("、")
        }
    }
}

// ---------------------------------------------------------------- 对外入口

/**
 * 一个编译好的复数表达式。不可变、可复用、可跨线程求值。
 *
 * `a b c d`（以及调用方通过 [compileComplex] 的 `params` 传进来的任何名字）在**编译期**
 * 就被折成了常量 —— 和 [Expression.compile] 处理 [ParameterSet] 的做法完全一致：
 * 求值路径不做 map 查找，表达式编译完就与参数表脱钩。
 * 将来接复平面参数滑块时，滑块一变重新 [compileComplex] 一次即可（短表达式只要几微秒）。
 */
class ComplexExpression internal constructor(private val root: ComplexNode) {

    /** 代入主变量 z 求值；默认 `z = 0`（表达式里不出现 z 时就是一个常量复数）。 */
    fun eval(z: Complex = Complex.ZERO): Complex = root.eval(z)
}

/**
 * 编译一个复数表达式（只解析一次、可反复求值）。
 *
 * ## 语法
 * ```
 * 字面量   2、3.5、.5、1e-3；虚数单位写作 i（2i、3i 靠隐式乘法得到）
 * 常量     i、π/pi、e、τ/tau、∞/inf、nan      （大小写不敏感）
 * 变量     z                                   （复平面主变量）
 * 参数     a b c d（缺省 1.0）以及 params 里的任意名字
 * 运算     + - * / ^（^ 右结合、优先级高于乘除，-2^2 = -4、2^3^2 = 512）、一元 +/-
 * 隐式乘   2z、3i、2π、zi、az、2(z+1)、(z+1)(z-1)
 * 绝对值   |z|
 * 函数     abs arg conj/conjugate re/Re im/Im exp ln log(1~2 个参数)
 *          sqrt sin cos tan sinh cosh pow(x, y)
 * ```
 * 说明：
 *  * `log` 与项目其它地方一致 —— 1 个参数是常用对数（以 10 为底），
 *    `log(b, x)` 是以 b 为底；自然对数用 `ln`。
 *  * 一元函数可以省括号：`sin z`、`√z`（只吃一个一元操作数）。
 *  * 大小写不敏感：`Re`/`IM`/`Sin` 都能用。
 *  * 不做的事（会明确报错，不会静默算错）：阶乘 `!`、取模 `%`、等式 `=`、`∛`。
 *
 * ## 错误
 * 语法错误 / 不认识的名字 / 参数个数不对一律抛 [ExpressionException]，消息中文、
 * 带字符下标 `position`，UI 可以直接拿去标红出错的位置（和实数表达式同一套处理）。
 * 相反的，**数值**上的病态（`1/0`、`log(0)`、`0^i`）不抛异常，结果是 inf/NaN。
 *
 * @param params 参数名 → 取值；`a b c d` 没给就是 1.0（[defaultParameterValues]）。
 *               这就是将来接"参数滑块"的口子：见 [parameterValuesOf]。
 */
fun compileComplex(src: String, params: Map<String, Complex> = emptyMap()): ComplexExpression {
    if (src.isBlank()) throw ExpressionException("表达式是空的", 0)
    val tokens = ComplexLexer(src).tokenize()
    return ComplexExpression(ComplexParser(tokens, params).parse())
}

/**
 * 一次性求值：`evaluate("z^2+i", z)`。
 *
 * 内部就是 `compileComplex(src, params).eval(z)` —— 只在"用户按了一次计算"这种
 * 单次场景下用；要逐帧/逐点反复算同一个表达式，自己 [compileComplex] 一次再循环 [ComplexExpression.eval]。
 *
 * @param z 主变量的取值；不传就是 0（表达式里没有 z 时相当于算一个常数）。
 * @param params 参数取值，见 [compileComplex]；`a b c d` 缺省 1.0。
 */
fun evaluate(
    src: String,
    z: Complex = Complex.ZERO,
    params: Map<String, Complex> = emptyMap()
): Complex = compileComplex(src, params).eval(z)

/**
 * `a b c d` 四个可绑定参数的**占位默认值**：全部 `1 + 0i`。
 *
 * 名字表和默认值都取自 [ParameterSet]（[ParameterSet.BINDABLE] / [ParameterSet.DEFAULT_VALUE]），
 * 理由也一样：现在**没有任何界面**能改这些值，取乘法与加法的单位元 1.0 才能让用户写下的
 * `a*z+b` 直接算成 `z+1`，而不是一写就报错。
 *
 * 将来接用户滑动条 / 输入框的真实取值时，把这个函数的返回值换成"当前值"即可 ——
 * 或者更直接地把 [ParameterSet] 适配过来（见 [parameterValuesOf]），
 * 再传给 [evaluate] / [compileComplex] 的 `params`。这个函数只是占位，不持有任何状态。
 */
fun defaultParameterValues(): Map<String, Complex> =
    ParameterSet.BINDABLE.associateWith { Complex(ParameterSet.DEFAULT_VALUE, 0.0) }

/**
 * 把现有的**实数**参数表 [ParameterSet] 适配成复数参数表（虚部 0）。
 *
 * 这样复平面模式可以直接复用 UI 上已有的 `a b c d` 取值来源，不用等参数本身复数化。
 * 只读地调用 [ParameterSet.value]，不改也不依赖它的内部结构。
 */
fun parameterValuesOf(parameters: ParameterSet): Map<String, Complex> =
    ParameterSet.BINDABLE.associateWith {
        Complex(parameters.value(it) ?: ParameterSet.DEFAULT_VALUE, 0.0)
    }

/**
 * 把复数写成复平面上一个点的**紧凑标签**（UI 直接拿去画文字）。规则固定、有测试盯着：
 *
 *  * 每个数字最多两位小数、去掉多余的 `.0`：`2.00 → "2"`、`-1.50 → "-1.5"`、
 *    `1/3 → "0.33"`、`-0.25 → "-0.25"`；
 *  * `|v| ≥ 1e7`，或者非零但四舍五入到两位小数会变成 0 时，改用科学计数法：
 *    `12345678 → "1.23e7"`、`0.0001 → "1e-4"`（宁可写 e 形式，也不要显示成 "0"）；
 *  * 虚部为 `±1`（含四舍五入后为 ±1）时写 `i` / `-i`，不写 `1i`；
 *  * 虚部为 0 → 只写实部（`3`）；实部为 0 → 只写虚部（`-i`、`2i`）；
 *  * 两部分都有 → 虚部自带符号：`2+3i`、`-2-i`（不是 `2+-3i`）；
 *  * 任一分量是 NaN → `"NaN"`（这种点本来也画不出来，不需要区分是哪一半坏了）；
 *  * `±∞` → `"∞"` / `"-∞"`，所以 `(1, ∞) → "1+∞i"`、`(0, ∞) → "∞i"`、`(-∞, 0) → "-∞"`。
 *
 * 纯函数，线程安全；`-0.0` 一律当作 0 处理（不会出现 `"-0"`、`"-0+3i"`）。
 */
fun resultLabel(value: Complex): String {
    val re = value.re
    val im = value.im
    if (re.isNaN() || im.isNaN()) return "NaN"
    if (im == 0.0) return formatComplexComponent(re)   // 纯实数（含 -0.0 的虚部）
    val magnitude = formatComplexComponent(kotlin.math.abs(im))
    val body = if (magnitude == "1") "i" else magnitude + "i"
    if (re == 0.0) return if (im < 0.0) "-$body" else body
    return formatComplexComponent(re) + (if (im < 0.0) "-" else "+") + body
}

/**
 * 标签里的单个数字分量。
 *
 * 两条路径：常规量级走 `%.2f` 再削掉多余的 0；超大 / 会显示成 0 的小量级走 [scientific]。
 * 单独拎出来是因为实部虚部共用同一套规则，`2+3i` 的两半必须长得一样。
 */
private fun formatComplexComponent(v: Double): String {
    if (v.isNaN()) return "NaN"
    if (v == Double.POSITIVE_INFINITY) return "∞"
    if (v == Double.NEGATIVE_INFINITY) return "-∞"
    if (v == 0.0) return "0"                      // -0.0 也在这里被归成 "0"
    if (kotlin.math.abs(v) >= 1.0e7) return scientific(v)
    // 先按两位小数舍入：一来定下"显示几位"，二来能判断"这个非零数是不是小到看不见了"。
    // 除法用 100.0 是精确的，round 只引入半个 ulp 级别的误差。
    val rounded = round(v * 100.0) / 100.0
    if (rounded == 0.0) return scientific(v)      // 例：0.0001 不显示成 "0"
    return trimTrailingZeros(String.format(Locale.ROOT, "%.2f", rounded))
}

/**
 * 科学计数法，形如 `1.23e7` / `-4e-3` / `1e-300`。
 *
 * 手工整理 `%.2e` 的输出（`"1.23e+07"` → `"1.23e7"`）：去掉指数里的前导零和 `+`，
 * 尾数同样削掉多余的 0。用 `Locale.ROOT` 是为了不受系统区域影响 ——
 * 有些区域的小数点是逗号，那会让标签变成 `2,5`，和表达式语法对不上。
 */
private fun scientific(v: Double): String {
    val s = String.format(Locale.ROOT, "%.2e", v)
    val e = s.indexOf('e')
    if (e < 0) return s                              // 理论上不会发生，兜底
    val mantissa = trimTrailingZeros(s.substring(0, e))
    val rawExp = s.substring(e + 1)
    val negative = rawExp.startsWith("-")
    val digits = rawExp.trimStart('+', '-', '0').ifEmpty { "0" }
    return mantissa + "e" + (if (negative) "-" else "") + digits
}

private fun trimTrailingZeros(s: String): String {
    if (!s.contains('.')) return s
    var end = s.length
    while (end > 0 && s[end - 1] == '0') end--
    if (end > 0 && s[end - 1] == '.') end--
    return s.substring(0, end)
}
