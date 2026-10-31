package com.functiondrawer.watch.engine

/**
 * LaTeX 源码 → 本引擎认识的普通表达式的**翻译层**。
 *
 * ## 为什么要它（2026-10-27）
 *
 * 用户原话："LaTeX语法没有实装。"
 *
 * 之前只在**显示**上做了半个子集（`MathRender` 把 `a/b` 排成分式、`sqrt(x)`
 * 排成根号），但**输入/求值**这一侧一个 LaTeX 命令都不认：写
 * `\frac{1}{2}`、`\sqrt{x}`、`\pi` 只会得到"看不懂这个字符：'\'"，函数文件
 * 里带 LaTeX 的表达式也进不了引擎。这条就是补上输入侧的翻译。
 *
 * ## 翻译规则（只做"能求值"的子集，不做排版）
 *
 * | LaTeX | 翻译成 |
 * |---|---|
 * | `\frac{a}{b}` / `\dfrac` / `\tfrac` | `(a)/(b)` |
 * | `\sqrt{x}` | `sqrt(x)` |
 * | `\sqrt[n]{x}` | `root(x,n)` |
 * | `x^{2}` / `x^{n+1}` | `x^(2)` / `x^(n+1)` |
 * | `\pi` `\tau` `\varphi` | `pi` `tau` `phi` |
 * | `\alpha` `\theta` … | 保留原希腊字母（引擎不认识会给出可读报错） |
 * | `\sin` `\cos` `\ln` `\log` … | `sin` `cos` `ln` `log`（后面照旧可省括号） |
 * | `\log_{a}{b}` / `\log_a b` / `\log_a(b)` | `log(a,b)`（引擎的底数在前，见 `MathFns`） |
 * | `\cdot` `\times` `\div` | `*` `*` `/` |
 * | `\left(` `\right)` `\bigl[` … | 直接丢掉命令，只留括号 |
 * | `\lvert` `\rvert` `\vert` `\Vert` | `\|` |
 * | `\langle` `\rangle` `\lfloor` `\rfloor` `\lceil` `\rceil` | `⟨` `⟩` `⌊` `⌋` `⌈` `⌉` |
 * | `\mathrm{…}` `\text{…}` `\operatorname{…}` | 只留花括号里的内容 |
 * | `\overline{…}` `\vec{…}` `\hat{…}` `\bar{…}` … | 只留内容（引擎没有装饰结构） |
 * | `\,` `\;` `\!` `\quad` | 空格或直接删掉 |
 * | `\{` `\}` `\%` | `(` `)` `%` |
 * | `30^\circ` / `x\degree` | `30` / `x`（角度符号不是上标） |
 *
 * ### 不等式 / 求和 / 积分
 *
 * 引擎是"一条曲线一个表达式"的求值器（见 `PlotMode`），没有比较运算、
 * 也没有求和积分节点。这几类命令**不静默删掉**，而是翻成对应的 Unicode 符号
 * （`≤` `≥` `≠` `∑` `∫` …）—— 排版能画出来，求值时会给出
 * "看不懂这个字符"的可读报错，比删成 `x2` 这种鬼东西诚实。
 *
 * 不认识的命令**静默删掉**：LaTeX 里大量命令（`\displaystyle`、`\limits` …）
 * 只是排版提示，删掉比报"看不懂"更接近用户预期；真正的未知标识符
 * （`\foo`）删掉后也不会有歧义。
 *
 * 没有 `\` 也没有 `{` 的普通表达式**原样返回**，一个字符都不动 ——
 * 所以老表达式的求值和光标下标完全不受影响（见 [expand] 的快速路径）。
 */
internal object Latex {

    /** 花括号包参数时用的展开函数（递归）。 */
    private fun expandRange(src: String, from: Int, to: Int): String {
        val out = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = src[i]
            when {
                c == '\\' -> i = command(src, i, to, out)

                // 裸的组：LaTeX 的 `{...}` 只表示分组，本身不产生字符
                c == '{' -> {
                    val end = matchingBrace(src, i, to)
                    out.append(expandRange(src, i + 1, minOf(end, to)))
                    i = end + 1
                }

                // 多余的右括号：丢掉，避免把后面的整段都吞掉
                c == '}' -> i++

                // 上标写成 `^{...}`：引擎只认 `^(...)`
                c == '^' -> {
                    // `^\circ` / `\degree` 是**角度符号**，不是上标：整段丢掉
                    //（否则会留下一个悬空的 `^`，词法器直接报错）
                    var j = i + 1
                    while (j < to && src[j].isWhitespace()) j++
                    var degreeName = ""
                    if (j < to && src[j] == '\\') {
                        var k = j + 1
                        val s = k
                        while (k < to && src[k].isLetter()) k++
                        degreeName = src.substring(s, k)
                    }
                    if (degreeName == "circ" || degreeName == "degree") {
                        i = j + 1 + degreeName.length
                    } else {
                        out.append('^')
                        i++
                        if (i < to && src[i] == '{') {
                            val end = matchingBrace(src, i, to)
                            out.append('(')
                            out.append(expandRange(src, i + 1, minOf(end, to)))
                            out.append(')')
                            i = end + 1
                        }
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 读一个参数：`{...}` 取括号里的内容；否则取**一个原子**
     * （单个字符，或一个完整的 `\命令`，比如 `\frac12`、`\sqrt\pi`）。
     *
     * @return 展开后的文本 + 下一个待处理下标
     */
    private fun argument(src: String, at: Int, to: Int): Pair<String, Int> {
        var i = at
        while (i < to && src[i].isWhitespace()) i++
        if (i >= to) return "" to i
        if (src[i] == '{') {
            val end = matchingBrace(src, i, to)
            return expandRange(src, i + 1, minOf(end, to)) to (end + 1)
        }
        if (src[i] == '\\') {
            val sb = StringBuilder()
            val next = command(src, i, to, sb)
            return sb.toString() to next
        }
        return src[i].toString() to (i + 1)
    }

    /** 找到与 [open] 处的 `{` 配对的 `}`（跳过 `\{` / `\}`）；找不到就返回 [to]。 */
    private fun matchingBrace(src: String, open: Int, to: Int): Int {
        var depth = 0
        var i = open
        while (i < to) {
            when (src[i]) {
                '\\' -> i += 2
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return to
    }

    /**
     * 和 [matchingBrace] 一样，找与 [open] 处的 `(` 配对的 `)`。
     *
     * 给 `\log_a(b)` 这种"下标 + 括号实参"的写法用：只取括里的内容，
     * 括号本身丢掉（外面会再包一层 `log(…)`）。
     */
    private fun matchingParen(src: String, open: Int, to: Int): Int {
        var depth = 0
        var i = open
        while (i < to) {
            when (src[i]) {
                '\\' -> i += 2
                '(' -> {
                    depth++
                    i++
                }
                ')' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return to
    }

    /**
     * 读一个实参：`{...}` / `(...)` / 单个原子都算。
     *
     * 和 [argument] 的区别只在括号：`\log_2(8)` 的真实参是 `8`，
     * 不是 `(`（[argument] 会把单字符 `(` 当原子）。
     */
    private fun argumentOrGroup(src: String, at: Int, to: Int): Pair<String, Int> {
        var i = at
        while (i < to && src[i].isWhitespace()) i++
        if (i < to && src[i] == '(') {
            val end = matchingParen(src, i, to)
            return expandRange(src, i + 1, minOf(end, to)) to (end + 1)
        }
        return argument(src, i, to)
    }

    /** 处理一个 `\命令`，把结果写进 [out]，返回命令之后的下标。 */
    private fun command(src: String, at: Int, to: Int, out: StringBuilder): Int {
        var i = at + 1
        if (i >= to) return i

        // 单字符命令：`\{` `\}` `\%` `\ ` `\,`
        if (!src[i].isLetter()) {
            when (val c = src[i]) {
                '{' -> out.append('(')
                '}' -> out.append(')')
                '%' -> out.append('%')
                ' ', ',', ';', ':', '!', '\\' -> out.append(' ')
                else -> out.append(c)
            }
            return i + 1
        }

        val start = i
        while (i < to && src[i].isLetter()) i++
        val name = src.substring(start, i)

        when (name) {
            // ---- 结构 ----
            "frac", "dfrac", "tfrac" -> {
                val (a, i1) = argument(src, i, to)
                val (b, i2) = argument(src, i1, to)
                out.append('(').append(a).append(")/(").append(b).append(')')
                return i2
            }

            "sqrt" -> {
                // 可选的根指数：[n]
                var j = i
                while (j < to && src[j].isWhitespace()) j++
                var index: String? = null
                if (j < to && src[j] == '[') {
                    val end = src.indexOf(']', j + 1).let { if (it < 0) to else it }
                    index = expandRange(src, j + 1, minOf(end, to))
                    j = end + 1
                }
                val (body, next) = argument(src, j, to)
                if (index.isNullOrBlank()) {
                    out.append("sqrt(").append(body).append(')')
                } else {
                    out.append("root(").append(body).append(',').append(index).append(')')
                }
                return next
            }

            // ---- 只留括号 / 只留内容 ----
            "left", "right", "middle", "big", "Big", "bigg", "Bigg",
            "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr" -> {
                // `\left.` / `\right.` 是"这一侧不画括号"，连点一起吞掉
                var j = i
                while (j < to && src[j].isWhitespace()) j++
                if (j < to && src[j] == '.') return j + 1
                return i
            }

            "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "text",
            "textbf", "textit", "textrm", "operatorname", "displaystyle",
            "textstyle", "scriptstyle" -> {
                val (body, next) = argument(src, i, to)
                out.append(body)
                return next
            }

            // ---- 运算符号 ----
            "cdot", "times", "ast" -> {
                out.append('*')
                return i
            }
            "div" -> {
                out.append('/')
                return i
            }
            "bmod", "mod" -> {
                out.append(" mod ")
                return i
            }

            // ---- 装饰 / 定界符：只留结构里能求值的那部分 ----
            "overline", "underline", "bar", "vec", "hat", "widehat",
            "tilde", "widetilde", "dot", "ddot", "breve", "check",
            "acute", "grave", "overbrace", "underbrace", "boxed",
            "colorbox", "cancel", "bcancel", "xcancel" -> {
                val (body, next) = argument(src, i, to)
                out.append(body)
                return next
            }
            "lvert", "rvert", "vert", "lVert", "rVert", "Vert" -> {
                out.append('|')
                return i
            }
            "langle" -> {
                out.append('⟨')
                return i
            }
            "rangle" -> {
                out.append('⟩')
                return i
            }
            "lfloor" -> {
                out.append('⌊')
                return i
            }
            "rfloor" -> {
                out.append('⌋')
                return i
            }
            "lceil" -> {
                out.append('⌈')
                return i
            }
            "rceil" -> {
                out.append('⌉')
                return i
            }
            // 角度符号：单独出现时直接吞掉（`30\degree` → `30`）
            "circ", "degree" -> {
                return i
            }

            // ---- 排版能画、但引擎求不了值的符号（见文件头）----
            "le", "leq" -> {
                out.append('≤')
                return i
            }
            "ge", "geq" -> {
                out.append('≥')
                return i
            }
            "ne", "neq" -> {
                out.append('≠')
                return i
            }
            "approx" -> {
                out.append('≈')
                return i
            }
            "equiv" -> {
                out.append('≡')
                return i
            }
            "lt" -> {
                out.append('<')
                return i
            }
            "gt" -> {
                out.append('>')
                return i
            }
            "pm" -> {
                out.append('±')
                return i
            }
            "mp" -> {
                out.append('∓')
                return i
            }
            "sim" -> {
                out.append('∼')
                return i
            }
            "propto" -> {
                out.append('∝')
                return i
            }
            "sum" -> {
                out.append('∑')
                return i
            }
            "prod" -> {
                out.append('∏')
                return i
            }
            "int" -> {
                out.append('∫')
                return i
            }
            "oint" -> {
                out.append('∮')
                return i
            }
            "in" -> {
                out.append('∈')
                return i
            }
            "notin" -> {
                out.append('∉')
                return i
            }
            "to", "rightarrow" -> {
                out.append('→')
                return i
            }
            "leftarrow" -> {
                out.append('←')
                return i
            }
            "infty" -> {
                out.append("inf")
                return i
            }
            "angle" -> {
                out.append('∠')
                return i
            }
            "triangle" -> {
                out.append('△')
                return i
            }
            "perp" -> {
                out.append('⊥')
                return i
            }
            "parallel" -> {
                out.append('∥')
                return i
            }

            // ---- 空白 ----
            "quad", "qquad", "enspace", "thinspace" -> {
                out.append(' ')
                return i
            }

            // ---- 希腊字母 / 常量 ----
            in GREEK -> {
                out.append(GREEK.getValue(name))
                return i
            }
        }

        // ---- 函数名（照旧可以省括号：`\sin x` → `sin x`）----
        FUNCTIONS[name]?.let { fn ->
            // 带下标的函数：`\log_{a}{b}` / `\log_a(b)` / `\sin_{n}(x)`
            var j = i
            while (j < to && src[j].isWhitespace()) j++
            if (j < to && src[j] == '_') {
                val (base, afterBase) = argument(src, j + 1, to)
                if (fn == "log" || fn == "lg" || fn == "lb") {
                    // 引擎的约定是 `log(底数, 真数)`（见 MathFns：log(b,x) = ln x / ln b），
                    // 而 LaTeX 里底数写在**下标** —— 顺序正好一致，直接拼就行。
                    // 后面没写实参时就留成 `log(底数,)` —— 和键盘插的模板同形，
                    // 用户接着输入真数即可。
                    val (value, next) = argumentOrGroup(src, afterBase, to)
                    out.append("log(").append(base).append(',').append(value).append(')')
                    return next
                }
                // 其它函数的下标只是标签（\|sin_{n}），丢掉
                out.append(fn)
                return afterBase
            }
            out.append(fn)
            return i
        }

        // 不认识的命令：删掉（见文件头说明）
        return i
    }

    /** 希腊字母 / 常量：能直接进引擎的转成名字，其余保留原字符。 */
    private val GREEK: Map<String, String> = mapOf(
        "pi" to "pi",
        "tau" to "tau",
        "phi" to "phi",
        "varphi" to "phi",
        "epsilon" to "epsilon",
        "varepsilon" to "epsilon",
        "theta" to "theta",
        "alpha" to "alpha",
        "beta" to "beta",
        "gamma" to "gamma",
        "delta" to "delta",
        "lambda" to "lambda",
        "mu" to "mu",
        "sigma" to "sigma",
        "omega" to "omega",
        "infty" to "inf",
        "infinity" to "inf"
    )

    /**
     * LaTeX 函数名 → 引擎函数名。
     *
     * 只收 [MathFns] 真的有的（`\gcd` 这种引擎没有的就别假装支持）。
     */
    private val FUNCTIONS: Map<String, String> = mapOf(
        "sin" to "sin",
        "cos" to "cos",
        "tan" to "tan",
        "cot" to "cot",
        "sec" to "sec",
        "csc" to "csc",
        "arcsin" to "asin",
        "arccos" to "acos",
        "arctan" to "atan",
        "sinh" to "sinh",
        "cosh" to "cosh",
        "tanh" to "tanh",
        "coth" to "coth",
        "ln" to "ln",
        "log" to "log",
        "lg" to "lg",
        "exp" to "exp",
        "abs" to "abs",
        "min" to "min",
        "max" to "max",
        "floor" to "floor",
        "ceil" to "ceil",
        "round" to "round",
        "deg" to "deg",
        "rad" to "rad",
        "gamma" to "gamma",
        "erf" to "erf",
        "sinc" to "sinc"
    )

    /**
     * 把 LaTeX 源码翻译成普通表达式。
     *
     * 不含 `\` 也不含 `{` 时**原样返回**：这是热路径上的快速判断，
     * 也保证老表达式的每个字符下标（光标、报错位置）一点没变。
     */
    fun expand(source: String): String {
        val hasCommand = source.indexOf('\\') >= 0
        val hasGroup = source.indexOf('{') >= 0
        if (!hasCommand && !hasGroup) return source
        return expandRange(source, 0, source.length)
    }
}
