// 手表 UI 会用到少量实验性 Compose 接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.functiondrawer.watch.engine.Latex
import kotlin.math.max
import kotlin.math.min

/**
 * 表达式输入的**数学排版引擎**（对标 GeoGebra 的输入行观感）。
 *
 * ## 为什么要自己写
 *
 * 用户 2026-10-17 的原话：
 *
 * > "最后再给输入框加个 LaTeX 数学语法的显示，看一大堆符号太难受了，
 * >  所有的语法显示对标 geogebra。"
 *
 * 现在输入框里是等宽字体的原始源码：`sqrt(x^2+1)/2` 就是这 15 个字符，
 * 分式是一条斜杠、根号是四个字母。手表屏幕本来就小，一串这样的字符根本读不出
 * "这是一个分式、分子里有个根号"。
 *
 * 项目不能引入 KaTeX / MathJax（无网络、无 WebView、包体也不允许），
 * 所以这里做一个**够用的子集**：把项目里实际会出现的写法排成二维公式。
 *
 * ## 支持的语法（覆盖本 App 的输入法能打出来的全部）
 *
 * | 输入 | 排成 | 说明 |
 * |---|---|---|
 * | `a/b` | 分式（横线 + 上下两行） | 分子取左操作数、分母取右操作数 |
 * | `x^2` / `x^(n+1)` | 上标 | 指数整体缩小并抬高 |
 * | `sqrt(x)` / `√(x)` | 根号（带勾和上横线） | 被开方部分整体在横线下 |
 * | `sin(x)` | `sin` 正体 + 括号 | 函数名一律正体，和变量区分 |
 * | `pi` / `theta` / `alpha` … | π / θ / α | 希腊字母 |
 * | `*` | `·` | 中间点，比星号像数学 |
 * | `abs(x)` / `|x|` | 竖线 | |
 * | 单字母变量 | **斜体** | 数学惯例：变量斜、数字和函数名正 |
 * | 数字 | 正体 | |
 *
 * ## 为什么是"排版 + 光标"一起做
 *
 * 光标不能再用"等宽字估算 x 坐标"那一套了：一个 `^2` 只占半个字宽、
 * 一个分式要跨两行。所以布局阶段就为**每一个源字符**记下一个 [Stop]
 * （x + 上下沿），光标直接读它 —— 这也是"可自选光标"能成立的前提。
 *
 * ## 结构
 *
 * 1. [tokenize]：源码 → 带原始下标的 token（下标是光标定位的锚点）；
 * 2. [Parser]：递归下降建树（分式 / 上标 / 根号 / 括号 / 文本）；
 * 3. 测量：每个节点算出 `宽 / 基线上高 / 基线下深`；
 * 4. [MathLayout]：把树摊平成"要画的字形 + 线 + 光标停靠点"三张表。
 *
 * 摊平的好处：绘制阶段没有任何递归，一屏公式就是几十次 `drawText` + 几条线，
 * 手表上也扛得住；而且**布局一次、绘制多次**（重组时不会重排）。
 */

/**
 * 排版用的字体。
 *
 * 这两个 ttf 直接来自 **KaTeX 发行包**（`KaTeX_Main-Regular.ttf` 53KB +
 * `KaTeX_Math-Italic.ttf` 31KB，放在 `assets/fonts/`，合计 88KB）。
 *
 * 为什么是这两个文件、而不是"模仿 KaTeX 自己画矢量"：
 * KaTeX 好看的根本原因不是"矢量绘制"，而是**它对的那套字形**——
 * Computer Modern 的数学字体。数字 `1` 有衬线、`+` 的横竖粗细不等、
 * `x` 的斜体是真正的数学斜体（不是系统字体的伪斜体）。
 * 换成这两个字体，同样的排版代码立刻就像 KaTeX 了；自己拿 Path 画 26 个字母
 * 是不可能画出 CM 那股味道的 —— 光 `a` 的收笔就够画一下午。
 *
 * 传 null 时回退系统字体（`Typeface.SANS_SERIF`），行为与上一版一致。
 */
internal class MathTypefaces(
    val main: Typeface?,
    val italic: Typeface?
) {
    fun of(style: GlyphStyle): Typeface? = when (style) {
        GlyphStyle.UPRIGHT -> main ?: Typeface.SANS_SERIF
        GlyphStyle.ITALIC -> italic ?: ITALIC_FALLBACK
    }

    private companion object {
        /**
         * 系统斜体回退。
         *
         * **允许为 null**：纯 JVM 单测里 `Typeface.SANS_SERIF` 本身就是 null
         * （`unitTests.isReturnDefaultValues = true` 把 Android 的静态字段
         * 全替成默认值），把它写死成非空会在类初始化时抛
         * `ExceptionInInitializerError`，整层排版的单测就都跑不起来了。
         * 真机上它永远不是 null，绘制结果和声明成非空时完全一致。
         */
        val ITALIC_FALLBACK: Typeface? =
            Typeface.SANS_SERIF?.let { Typeface.create(it, Typeface.ITALIC) }
    }
}

// ---------------------------------------------------------------------------
// 1. 词法
// ---------------------------------------------------------------------------

/**
 * 乘号的一族字符。
 *
 * 和 `engine/Lexer.kt` 的 `'*', '×', '·', '⋅' -> "*"` 一一对应 ——
 * 排版层不归一化就会漏掉键盘直接插进来的 `×`（用户报的"乘号没做 LaTeX 语法"）。
 */
private val MUL_OPS = setOf("*", "×", "·", "⋅")

/** 除号的一族字符（对应词法层的 `'/', '÷' -> "/"`）。 */
private val DIV_OPS = setOf("/", "÷")

/**
 * **不能**当一个表达式开头的运算符 —— 上标的解析终止在这一族上（见 `parsePower`）。
 *
 * `x^` 还没填指数时用户按 `+`，那一下加号属于整条式子、不属于指数。
 * `-` 不在这里：`x^-1` 是合法的负指数。
 */
private val NON_EXP_START_OPS = setOf("+", "*", "×", "·", "⋅", "/", "÷", "=", "^")

/** token 的种类。区分它们只为了排版时的正/斜体和函数名识别。 */
private enum class TokKind {
    /** 数字：正体。 */
    NUMBER,

    /** 标识符：可能是变量（斜体）、函数名（正体）、希腊字母（换成符号）。 */
    IDENT,

    /** 运算符 / 括号 / 逗号 / 等号：正体，前后留一点空。 */
    OP,

    /** 源码结束。 */
    END
}

private class Tok(
    val kind: TokKind,
    val text: String,
    /** 这个 token 在**原始源码**里的起始下标 —— 光标定位的锚点。 */
    val start: Int
)

/**
 * 把源码切成 token。
 *
 * 容错优先：认不出的字符一律当成单字符 IDENT 原样画出来，绝不抛异常 ——
 * 这是输入框，用户随时处在"打到一半"的非法状态，排版层必须能画任何东西。
 */
private fun tokenize(src: String): List<Tok> {
    val out = ArrayList<Tok>(src.length + 1)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c.isWhitespace() -> i++

            c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                val s = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                out += Tok(TokKind.NUMBER, src.substring(s, i), s)
            }

            c.isLetter() || c == '_' -> {
                val s = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                out += Tok(TokKind.IDENT, src.substring(s, i), s)
            }

            else -> {
                out += Tok(TokKind.OP, c.toString(), i)
                i++
            }
        }
    }
    // END 的"下标"取**去掉末尾空白之后**的长度，而不是整串长度。
    //
    // 输入框在"点右侧空白、想回到整条式子"时会在源码末尾补一个空格（见
    // `FunctionEditor.escapeToEnd`）。空格本身不排版，但空操作数槽位
    // （`x^` 的指数、`1÷` 的分母、`log(2,` 的真数）的停靠点下标取的就是 END
    // 的位置 —— 如果 END 停在 `src.length`，那个槽位还会和光标（也等于
    // `src.length`）撞在同一个下标上，光标依旧被吸回结构里，空格就白补了。
    // 取"最后一个有效字符之后"就没这个问题：光标下标严格大于所有停靠点。
    out += Tok(TokKind.END, "", src.trimEnd().length)
    return out
}

// ---------------------------------------------------------------------------
// 2. 语法树
// ---------------------------------------------------------------------------

private sealed interface MNode

/** 一个叶子文本片段（可能是多字符的函数名，也可能是单个变量）。 */
private class MLeaf(
    val text: String,
    val style: GlyphStyle,
    /** 这段文本覆盖的源码区间 `[start, start + len)`。 */
    val start: Int,
    /**
     * 它在**源码**里占几个字符。
     *
     * 和 [text] 的长度不是一回事：`pi`（2 字符）画成 `π`（1 字符）、
     * `*`（1 字符）画成 `⋅`（1 字符但码点不同）。光标定位必须按源码长度算，
     * 否则光标会越界或者卡在同一个位置不动。
     */
    val sourceLen: Int = text.length
) : MNode

/** 横排：若干节点并排。 */
private class MRow(val items: List<MNode>) : MNode

/** 一个节点是不是**没有实际内容**（空叶子 / 只由空叶子组成的行）。 */
private fun MNode.isBlankContent(): Boolean = when (this) {
    is MLeaf -> text.isEmpty()
    is MRow -> items.all { it.isBlankContent() }
    else -> false
}

/**
 * 这一行是不是**空的**（只有占位的空叶子）：空参数槽位要画阴影，靠它判断。
 *
 * ⚠️ 判断必须**递归到叶子**。`parseSum()` → `parseProduct()` → `parseAtom()`
 * 三层下来，一个空参数的树长这样：
 *
 * ```
 * MRow([ MRow([ MLeaf("") ]) ])
 * ```
 *
 * 旧写法 `items.all { it is MLeaf && it.text.isEmpty() }` 只看第一层，
 * 里面那层 `MRow` 不是 `MLeaf`，立刻判成"有内容" —— 于是 `log(,)` 的底数、
 * `root(,)` 的根指数、`sin()`/`√()` 里面的空槽位**一个都不会画阴影**，
 * 也不会登记光标停靠点。这正是用户两次反馈"log 底数位还是没有输入位置、
 * 也没有阴影""ⁿ√ 没有输入次方的位置"的真因：不是尺寸问题，是压根没建。
 */
private fun MRow.isBlank(): Boolean = items.all { it.isBlankContent() }

/**
 * 分式。
 *
 * @param numStart 分子在源码里的起始下标（-1 = 分子是结构造出来的空位，没有位置）。
 *   只有**分子为空**时才用得上：空分子要画一块阴影槽位，并登记一个停靠点
 *   （点它有地方落光标、打字进得来）——用户报的"分数线上面和下面没有阴影"。
 * @param denStart 分母的起始下标；空分母同理。
 */
private class MFrac(
    val num: MRow,
    val den: MRow,
    val numStart: Int = -1,
    val denStart: Int = -1
) : MNode

/** 上标。 */
private class MSup(
    val base: MNode,
    val exp: MRow,
    val expStart: Int = -1,
    /**
     * 隐式括号收尾 `)` 之后的下标（`^()` 里那个看不见的右括号，-1 = 不是括号写法）。
     *
     * 它不进语法树（括号不画），但**光标要能停在这里**：用户把 `x^(2+1)` 打到末尾时，
     * 光标还在指数这一小块里，不会掉回整行基线。
     */
    val expEnd: Int = -1
) : MNode

/**
 * 根号 —— 带**可选根指数**（`ⁿ√` / `∛` / `root(被开方数,次数)`）。
 *
 * @param body 被开方的内容
 * @param index 根指数；null = 平方根
 * @param indexStart 指数槽位在源码里的起始下标（-1 = 没有这个槽位）——
 *   指数为空时要靠它在左上角那块留一个**虚线占位框**，并且能被"点这儿输入"命中
 * @param bodyStart 被开方块在源码里的起始下标（同上，空的时候留占位框）
 * @param closeStart 收尾的 `)` 在源码里的下标（-1 = 没有/不是函数式写法）——
 *   根号末尾也要有光标停靠点，否则光标在 `root(,)` 的 `)` 之后时会被画到
 *   左上角的根指数槽里（"看到的位置"和"真正插入的位置"对不上）。
 */
private class MSqrt(
    val body: MRow,
    val index: MRow? = null,
    val indexStart: Int = -1,
    val bodyStart: Int = -1,
    val closeStart: Int = -1
) : MNode

/**
 * 带括号的一组（括号跟随内容缩放，暂时和正文同号）。
 *
 * @param bodyStart 括号里那块内容在源码里的起始下标 —— 括号里是空的时候
 *   （`sin()` 按下去还没输入）靠它画一块**阴影槽位**，并且让"点阴影"能把光标
 *   停到那里；`-1` = 不算槽位（比如 `()` 只是普通括号）
 * @param closeStart 收尾符号（`)` / `|` / `⌉` …）在源码里的下标 —— 光标要能停在
 *   右半边那个符号**之后**，也必须在竖线/括号上能停靠。`-1` = 源码里没有闭合符号
 *   （`|x` 这种没写完的，或 `()` 那种普通括号）。
 */
private class MParen(
    val open: String,
    val body: MRow,
    val close: String,
    val bodyStart: Int = -1,
    val closeStart: Int = -1
) : MNode

/**
 * 对数 `log(底数, 真数)` —— 排成 GeoGebra 那种 **log 右下角带底数** 的样子。
 *
 * 用户原话："其中第二张图 log 底数的位置是光标，到时候你要做阴影。"
 * 也就是说：底数不是一个跟在括号里的普通参数，而是**下标位的一块槽位** ——
 * 按了 `log` 键（插入 `log(,)`）之后，光标先落在底数上，底数槽位先画一块
 * 阴影，点它就把光标移过去。
 *
 * @param arg 真数（逗号后面的那个参数，排在括号里）
 * @param base 底数；null = 源码里根本没有逗号（`log(x)` 这种单参数写法，
 *   这时不画底数槽位）。注意 `log(,)` 里底数是**空 MRow 而不是 null**：
 *   为空也要占一块槽位、画阴影，否则"光标在底数上却什么也看不见"。
 * @param baseStart 底数槽位在源码里的起始下标（-1 = 没有这个槽位）
 * @param argStart 真数槽位在源码里的起始下标（同上，空的时候画阴影）
 * @param closeStart 收尾的 `)` 在源码里的下标（-1 = 没有）——
 *   给它登记光标停靠点，光标停在 `)` 之后时才不会被画回括号里面。
 */
private class MLog(
    val arg: MRow,
    val base: MRow? = null,
    val baseStart: Int = -1,
    val argStart: Int = -1,
    val closeStart: Int = -1
) : MNode

/** 字形风格：正体 / 斜体 / 希腊符号（正体）。 */
internal enum class GlyphStyle { UPRIGHT, ITALIC }

/**
 * 函数名表 —— 这些标识符排成**正体**，并且后面跟括号时不额外加空格。
 *
 * 只收本 App 的键盘/字母表能打出来的（见 `MathKeyboard` 与 `AlphabetModel`）；
 * 多收几个不花钱，但宁可少收也不要误判（用户可能用 `e` 当变量）。
 */
private val FUNCTION_NAMES = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc",
    "asin", "acos", "atan", "acot",
    "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
    "log", "ln", "lg", "exp", "abs", "floor", "ceil",
    "sqrt", "cbrt", "sign", "max", "min", "mod", "gcd", "lcm"
)

/** 希腊字母 / 常量的显示替换。 */
private val SYMBOLS = mapOf(
    "pi" to "π",
    "theta" to "θ",
    "alpha" to "α",
    "beta" to "β",
    "gamma" to "γ",
    "delta" to "δ",
    "epsilon" to "ε",
    "lambda" to "λ",
    "mu" to "μ",
    "sigma" to "σ",
    "phi" to "φ",
    "omega" to "ω",
    "inf" to "∞",
    "infinity" to "∞"
)

/**
 * 递归下降解析器。
 *
 * 优先级（低 → 高）：`+ -` → `* /` → `^` → 原子。
 *
 * `/` 在这一层就被吃成 [MFrac]（而不是先建一棵除法树再"事后转分式"）：
 * 分式的分子/分母各自又是一个 [MRow]，这样 `(a+b)/(c-d)` 天然排成
 * 上下两行带括号的形式，不需要二次变换。
 */
private class Parser(private val toks: List<Tok>, private val srcLen: Int) {

    private var p = 0

    /**
     * 正在解析的绝对值竖线层数（`|...|` 的嵌套深度）。
     *
     * 竖线既是**开始**符号也是**结束**符号，递归下降里没有"终止符"的概念：
     * 旧代码在 `parseAtom` 里见到 `|` 就当成新的一个开始，于是 `|x|` 被解析成
     * "外层 `|` 里包着内层 `|` + x"，两根竖线都是开括号、没有一根是闭括号 ——
     * 布局阶段只登记了一个"竖线里面的空槽"的停靠点，光标能画出来的唯一位置
     * 就在两根竖线**右边**，可源码里的光标下标又还停在两根竖线**中间**。
     * 用户看到的就是"光标只能停在绝对值框后，输入却进了框里"。
     *
     * 用这个计数器区分"开"和"闭"：解析竖线内部时 > 0，此时 `parseAtom` 遇到
     * `|` 就把它当边界（返回空叶子、不消费），由外层竖线吃掉它当收尾。
     */
    private var barDepth = 0

    fun parseAll(): MRow {
        val items = ArrayList<MNode>()
        items += parseSum()
        // 解析器可能停在没吃掉的 token 上（`y = x^2` 的等号、写了一半的括号…）。
        //
        // 上一版这里把剩下的一整截**原样当正体文本**摊平，于是
        // `x^2+y^2=1`、`y=x^2` 这类带等号的式子（尤其是隐式函数）在函数列表里
        // 显示成"原始式子"（用户 1.2.62 的反馈）。
        // 现在只把当前这个分界符当叶子画出来，后面**继续按语法解析**：
        // 等号 -> 叶子 -> 右边还是正常的 parseSum。每轮至少吃掉一个 token，
        // 认不出的东西也不会死循环。
        while (toks[p].kind != TokKind.END) {
            val t = eat()
            items += MLeaf(
                when (t.text) {
                    "*" -> "⋅"
                    "-" -> "−"
                    else -> t.text
                },
                GlyphStyle.UPRIGHT,
                t.start
            )
            items += parseSum()
        }
        return MRow(items)
    }

    private fun peek(): Tok = toks[p]
    private fun atOp(s: String): Boolean = toks[p].kind == TokKind.OP && toks[p].text == s
    private fun eat(): Tok = toks[p].also { if (p < toks.size - 1) p++ }

    /** 乘号：ASCII `*`、键盘上的 `×`、以及 `·` / `⋅`（引擎词法层同样全收）。 */
    private fun atMul(): Boolean = toks[p].kind == TokKind.OP && toks[p].text in MUL_OPS

    /** 除号：`/` 与键盘上的 `÷`。 */
    private fun atDiv(): Boolean = toks[p].kind == TokKind.OP && toks[p].text in DIV_OPS
    private fun atEnd(): Boolean = toks[p].kind == TokKind.END

    /**
     * 下一个 token 能不能**直接并排**在左边那一段后面（隐式乘法）。
     *
     * `2x`、`2π`、`x·sin(x)` 里的点可以省、`(x+1)(x-1)`、`2√x`、`6x`
     * 这些写法本来就没有乘号，但语法上都是乘法。上一版只认 `* × ÷`，
     * 于是 `x^(2x)` 的第二个 `x` 停在指数括号**外面**、`log(6x,9)` 的 `x`
     * 停在底数后面 —— 用户看到的就是"只有 2 在指数位，x 丢到右边；
     * 底数 6x 显示成 log(6)x,9)"。
     *
     * 排除的几类：
     * * `+ -` → 属于 [parseSum]（`2-3` 不能读成 `2 · (−3)`）；
     * * `,` `)` `=` → 边界 / 分隔符，由各自的调用方处理；
     * * `|` 在绝对值**内部**（[barDepth] > 0）→ 那是收尾竖线，不是新开一个绝对值。
     */
    private fun canStartAtom(): Boolean {
        val t = toks[p]
        return when (t.kind) {
            TokKind.NUMBER, TokKind.IDENT -> true
            TokKind.OP -> when (t.text) {
                "(", "√", "∛" -> true
                "|" -> barDepth == 0
                else -> false
            }
            TokKind.END -> false
        }
    }

    /**
     * 逗号分隔的参数 / 坐标列表：`(x,y)`、`clamp(x,0,1)`、`(f(t),g(t))`…
     *
     * [parseSum] 把 `,` 当边界（见 [parseAtom] 开头），只调一次它会把
     * 逗号后面的内容留在源码里、最后掉进 [parseAll] 的兜底当**原始文本**画出来。
     * 参数列表和坐标列表都是同一个形状，这里统一按 `,` 串起来，
     * 逗号本身也当普通正体叶子画（排版层不管前后留白）。
     */
    private fun parseArgList(): MRow {
        val items = ArrayList<MNode>()
        items += parseSum()
        while (atOp(",")) {
            val comma = eat()
            items += MLeaf(",", GlyphStyle.UPRIGHT, comma.start)
            items += parseSum()
        }
        return MRow(items)
    }

    private fun parseSum(): MRow {
        val items = ArrayList<MNode>()
        items += parseProduct()
        while (atOp("+") || atOp("-")) {
            val t = eat()
            items += MLeaf(t.text, GlyphStyle.UPRIGHT, t.start)
            items += parseProduct()
        }
        return MRow(items)
    }

    private fun parseProduct(): MRow {
        // 记下这一串的**第一个 token**：分式的分子起始下标要用它
        // （分子为空时用运算符自己的下标 —— 在它前面打字就是填分子）。
        val startTok = p
        val leadingOp = atMul() || atDiv()
        // 乘号 / 除号打头（用户单独点 `×`、`÷`）：左边就是**空槽位**，
        // 不能先用 parseAtom 把它当普通字符吃掉 —— 否则"单独点乘除号没有 LaTeX 效果"
        // （`÷` 要立刻画成分式，`×` 要立刻排成 ⋅）。
        var left: MNode? = if (leadingOp) null else parsePower()
        val items = ArrayList<MNode>()
        while (true) {
            if (atMul()) {
                val t = eat()
                if (left != null) items += left
                // 键盘上那颗乘号插进来的是 `×`（U+00D7），词法层也接受 `·` / `⋅`。
                // LaTeX 的口径里这些都是 `\cdot`（`Latex.kt` 把 `\times` / `\cdot` 都翻成 `*`），
                // 所以统一排成中点 —— 和 `*` 完全同一条路。
                // 少了这一支，`2×x^2` 里的 `×` 会被 [parseAll] 的兜底当成普通正体文本，
                // 后面的 `^2` 就原样露出来（用户报的"乘号没做 LaTeX 语法"）。
                items += MLeaf("⋅", GlyphStyle.UPRIGHT, t.start)
                left = parsePower()
            } else if (atDiv()) {
                // 分式：左边（目前累积的这一串）当分子，右边一个"幂"当分母。
                // `÷`（U+00F7，键盘上那颗除号）和 `/` 同一条路 ——
                // LaTeX 里 `\div` 和 `/` 都是"除法"，这里统一排成分数线
                // （`Latex.kt` 也是把 `\div` 翻成 `/`），否则 `1÷x^2` 的后半截
                // 会被兜底循环摊平成纯文本，`^2` 就露出来了。
                val op = toks[p]
                val numStart = if (left == null && items.isEmpty()) {
                    // 分子完全是空的（`÷` 单独按下去）：停在运算符位置
                    op.start
                } else {
                    toks[startTok].start
                }
                val num = MRow(if (left == null) items.toList() else (items + left))
                eat()
                // 分母为空时它的起始下标就是"这一串的最后" → 能在那儿落光标
                val denStart = toks[p].start
                val denNode = parsePower()
                left = MFrac(
                    num = num,
                    den = denNode as? MRow ?: MRow(listOf(denNode)),
                    numStart = numStart,
                    denStart = denStart
                )
                items.clear()
            } else if (left != null && canStartAtom()) {
                // 隐式乘法（`2x`、`2π`、`6x`、`(x+1)(x-1)`、`2√x`）：
                // 不插中点，直接并排 —— 手写和 GeoGebra 里本来就写 `2x`。
                items += left
                left = parsePower()
            } else {
                break
            }
        }
        return MRow(if (left == null) items else if (items.isEmpty()) listOf(left) else items + left)
    }

    /**
     * `^`：**右结合**，指数本身又是一次幂。
     *
     * 用户原话："连 x^x^x 都不能显示。" —— 旧写法指数只吃一个 [parseAtom]，
     * 于是 `x^x^x` 被拆成 `(x^x)` + 剩下的 `^x`：第二个 `^x` 没进语法树，
     * 掉到 [parseAll] 的兜底里当普通文本画出来，看着就是"上标坏了"。
     *
     * 现在指数递归 [parsePower]，`x^x^x` 得到 `x^(x^x)`（TeX 也是这个结合方向）。
     * 括号那一支也放进循环里，所以 `x^(a)^b` 同样能一路接下去。
     */
    private fun parsePower(): MNode {
        var base: MNode = parseAtom()
        while (atOp("^")) {
            eat()
            var expStart = -1
            // 隐式括号的收尾 `)` 是**看不见**的，但它仍然是源码里的一个字符：
            // 光标停在它后面（比如 `x^(2+1)` 的 7）时，必须仍然显示在指数这一小块里，
            // 不能因为"树里没有这个字符"就掉到整行末尾的基线上。
            var expEnd = -1
            val exp = if (atOp("(")) {
                eat()
                val bodyStart = toks[p].start
                // 括号是**隐式**的（`^()` 是键盘插的固定骨架）：不画括号，
                // 但空的时候仍要留一块阴影槽位 —— 槽位靠 expStart 登记，
                // 所以这里必须把它记下来。
                expStart = bodyStart
                val body = parseSum()
                if (atOp(")")) {
                    val close = eat()
                    expEnd = close.start + close.text.length
                }
                // 用户 1.2.63 原话："指数位只要把两个单项式用加号或者减号连接，
                // 括号就会显示出来，你把括号做成始终不显示。"
                //
                // 所以这里的圆括号**永远只作为源码里的分界符**，不进语法树：
                // `x^(2+1)` 直接排成 x 的 `2+1` 上标（TeX 的 `x^{2+1}` 也不画括号）。
                // 想在指数里显示括号，用户自己再多打一层 `x^((2+1))` ——
                // 那层是 parseAtom 的普通括号，照常画。
                body
            } else {
                expStart = toks[p].start
                // 指数的第一个 token 如果是"不可能当表达式开头"的运算符
                // （`+` `*` `×` `÷` `=` `^`），就认为指数还是空的、而且**不消耗**它 ——
                // 否则 `x^` 之后按 `+`，那个 `+` 会被 parseAtom 吃成指数里的一个叶子
                // （画成 x^{+}），后面的数字反而甩到基线上。
                // `-` 不在表里：`x^-1` 是合法的负指数（parseAtom 有专门的一元负号分支）。
                val canStartExp = !(toks[p].kind == TokKind.OP && toks[p].text in NON_EXP_START_OPS)
                if (canStartExp) {
                    // 指数继续走 parsePower：右结合 + 嵌套（见上面的说明）
                    MRow(listOf(parsePower()))
                } else {
                    MRow(emptyList())
                }
            }
            base = MSup(base, exp, expStart, expEnd)
        }
        return base
    }

    private fun parseAtom(): MNode {
        if (atEnd()) return MLeaf("", GlyphStyle.UPRIGHT, srcLen)
        val t = toks[p]

        // 右括号 / 逗号是**边界**，不是原子：空参数（`√()`、`root(,)`、`sin()`）
        // 里如果把它们当单字符吃掉，退出时 `atOp(")")` 就再也对不上了。
        // `|` 在"正在解析一对竖线的内部"时同样是**收尾边界**（见 [barDepth]）。
        if (t.kind == TokKind.OP &&
            (t.text == ")" || t.text == "," || (t.text == "|" && barDepth > 0))
        ) {
            return MLeaf("", GlyphStyle.UPRIGHT, t.start)
        }

        // 一元负号：`-x`，符号和变量并排
        if (t.kind == TokKind.OP && t.text == "-") {
            eat()
            return MRow(listOf(MLeaf("−", GlyphStyle.UPRIGHT, t.start), parseAtom()))
        }

        if (t.kind == TokKind.OP && t.text == "(") {
            val open = eat()
            val bodyStart = toks[p].start
            // 括号里可能是一个逗号分隔的列表（`(x,y)` 这种坐标 / 点）——
            // 只调 parseSum 的话逗号之后的内容会掉进 parseAll 的原始文本兜底。
            val body = parseArgList()
            if (atOp(")")) {
                val close = eat()
                return MParen(open.text, body, close.text, bodyStart, close.start)
            }
            return MParen(open.text, body, "", bodyStart)
        }

        if (t.kind == TokKind.OP && t.text == "|") {
            val open = eat()
            val bodyStart = toks[p].start
            // 解析内部时把竖线当收尾边界（见 [barDepth] 的说明）
            barDepth++
            val body = parseSum()
            barDepth--
            if (atOp("|")) {
                val close = eat()
                return MParen(open.text, body, close.text, bodyStart, close.start)
            }
            return MParen(open.text, body, "", bodyStart)
        }

        // √ / ∛：后面跟括号时，这对括号是**隐式**的 —— 不画出来，只画一根开方横线
        //（用户原话："让输入根号之后在解析里面自动带根号……还要加一个看不见的括号"）。
        if (t.kind == TokKind.OP && (t.text == "√" || t.text == "∛")) {
            val root = eat()
            // ∛ 的指数是固定的 3：它**不占源码位置**（sourceLen = 0），
            // 所以退格删不掉它，屏幕上也不出现一个可编辑的 "3"。
            val degree = if (root.text == "∛") {
                MRow(listOf(MLeaf("3", GlyphStyle.UPRIGHT, root.start, sourceLen = 0)))
            } else {
                null
            }
            if (atOp("(")) {
                eat()
                // 指数槽位只对真正的 n 次根号有意义；√ / ∛ 的左上角没有槽位
                val idxStart = if (root.text == "∛") root.start else -1
                val bodyStart = toks[p].start
                val body = parseSum()
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MSqrt(body, degree, idxStart, bodyStart, closeStart)
            }
            return MSqrt(MRow(listOf(parseAtom())), degree)
        }

        if (t.kind == TokKind.NUMBER) {
            eat()
            return MLeaf(t.text, GlyphStyle.UPRIGHT, t.start)
        }

        if (t.kind == TokKind.IDENT) {
            val lower = t.text.lowercase()
            // root(被开方数, 次数) 吃成**带根指数的根号**（ⁿ√ 键插的就是它）。
            // 和 sqrt 一样是结构，不能按普通函数名排。
            if (lower == "root" && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                eat() // root
                eat() // (
                val bodyStart = toks[p].start
                val body = parseSum()
                var index: MRow? = null
                var indexStart = -1
                if (atOp(",")) {
                    eat()
                    indexStart = toks[p].start
                    val idx = parseSum()
                    index = if (idx.isBlank()) null else idx
                }
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MSqrt(body, index, indexStart, bodyStart, closeStart)
            }
            // sqrt(...) 吃成根号：它和 `^` 一样是**结构**，不能按普通函数名排。
            //
            // 这里是**函数式写法**（`sqrt(x)`、以及 `Latex.expand` 把 `\sqrt{x}`
            // 翻出来的结果），和上面键盘插的 `√(...)` 是同一颗根号的两种源码形式。
            // 两种都必须带 `bodyStart`：括号在排版层是**隐式**的、不画出来，
            // 空的时候只能靠这个下标留一块可点的槽位。少了它，`sqrt()` 既画不出
            // 可输入的阴影，也登记不了光标停靠点 —— 用户点哪儿都会被 `indexAt`
            // 兜底成 0（根号**前面**），这就是"根号下不能输入内容"的真因。
            if (lower == "sqrt" && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                eat() // sqrt
                eat() // (
                val bodyStart = toks[p].start
                val body = parseSum()
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MSqrt(body, bodyStart = bodyStart, closeStart = closeStart)
            }
            // cbrt(...) 是立方根的函数式写法（LaTeX 的 `\sqrt[3]{x}` 会翻成
            // `root(x,3)`，不经过这里；但用户自己打 `cbrt(x)` 时也该画成根号）。
            // 指数固定是 3、**不占源码位置**（sourceLen = 0），和键盘上那颗 `∛`
            // 完全一致 —— 所以退格删不到那个 3，屏幕上也不出现可编辑的 "3"。
            // 少了这个分支，输入框里会原样显示 `cbrt(x)` 四个字母，而不是根号。
            if (lower == "cbrt" && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                val head = eat() // cbrt
                eat() // (
                val bodyStart = toks[p].start
                val body = parseSum()
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MSqrt(
                    body = body,
                    index = MRow(
                        listOf(MLeaf("3", GlyphStyle.UPRIGHT, head.start, sourceLen = 0))
                    ),
                    bodyStart = bodyStart,
                    closeStart = closeStart
                )
            }
            // abs(...) 排成竖线，比 `abs` 三个字母更像数学
            if (lower == "abs" && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                eat()
                eat()
                val bodyStart = toks[p].start
                val body = parseSum()
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MParen("|", body, "|", bodyStart, closeStart)
            }
            // 向上/向下取整排成 ⌈x⌉ / ⌊x⌋（LaTeX 的 \lceil…\rceil、\lfloor…\rfloor）。
            // 用户原话："向上取整和向下取整函数没有 LaTeX 语法的符号表达，只有 ceil() 和 floor()"。
            // 和 sqrt/abs 一样是**结构**：丢掉函数名，只留括号，
            // 括号本身会按内容高度放大（见 [Measurer] 的 MParen 分支）。
            if ((lower == "ceil" || lower == "floor") && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                eat()
                eat()
                val bodyStart = toks[p].start
                val body = parseSum()
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return if (lower == "ceil") {
                    MParen("⌈", body, "⌉", bodyStart, closeStart)
                } else {
                    MParen("⌊", body, "⌋", bodyStart, closeStart)
                }
            }
            // log(底数, 真数)：底数排成右下角下标（GeoGebra 同款），
            // 而不是跟在逗号后面的第二个参数 —— 见 [MLog]。
            // 引擎的约定是 `log(b, x)`（第一个参数是底数，见 `MathFns`），
            // 所以逗号前的那个才是底数、逗号后的那个进括号。
            // `log(x)` 也走这里（baseStart = -1，不画底数槽位）。
            if (lower == "log" && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                eat() // log
                eat() // (
                val firstStart = toks[p].start
                var arg = parseSum()
                var argStart = firstStart
                var base: MRow? = null
                var baseStart = -1
                if (atOp(",")) {
                    eat()
                    baseStart = firstStart
                    // ⚠️ 底数即使是**空的**也要保留成空 MRow，不能置 null ——
                    // null 的语义是"源码里没有底数槽位"（`log(x)` 单参数写法），
                    // 而 `log(,)` 里底数槽位是存在的、只是还没填。
                    // 置 null 会让测量/布局都不画槽位，键盘却把光标停在底数上
                    //（`logKey` 的 cursorBack=2），于是"光标在底数上没有框"。
                    base = arg
                    argStart = toks[p].start
                    arg = parseSum()
                }
                var closeStart = -1
                if (atOp(")")) {
                    closeStart = toks[p].start
                    eat()
                }
                return MLog(arg, base, baseStart, argStart, closeStart)
            }
            // 普通函数调用（`sin(x)`、`ln(2x)`、`exp(-x)` ……）：函数名照旧画正体，
            // 但**括号里的内容必须继续按语法解析**。
            //
            // 少了这一支，`sin` 会被当成一个孤立叶子吃下去，紧跟的 `(` `x` `)` `^2`
            // 就再也接不上 parseSum/parseProduct/parsePower，全掉进 [parseAll] 的
            // 兜底循环里被当**正体纯文本**摊平 —— 用户看到的就是"x 单独输入才是
            // 数学斜体，`sin(x)` 里变成印刷体；`x^2` 单独输入是上标，`sin(x^2)`
            // 里 `^2` 原样露出一截"。
            if (lower in FUNCTION_NAMES && p + 1 < toks.size &&
                toks[p + 1].kind == TokKind.OP && toks[p + 1].text == "("
            ) {
                val head = eat() // 函数名
                val open = eat() // (
                val bodyStart = toks[p].start
                // 参数可以是逗号分隔的多个（`clamp(x,0,1)`、`atan2(y,x)`），
                // 和括号列表同一个解析（见 [parseArgList]）。
                val body = parseArgList()
                var closeStart = -1
                var close = ""
                if (atOp(")")) {
                    closeStart = toks[p].start
                    close = eat().text
                }
                return MRow(
                    listOf(
                        MLeaf(head.text, GlyphStyle.UPRIGHT, head.start),
                        MParen(open.text, body, close, bodyStart, closeStart)
                    )
                )
            }
            eat()
            SYMBOLS[lower]?.let { sym ->
                // ⚠️ 希腊字母**必须用斜体字体**：KaTeX_Main-Regular 里没有 α/β/π/θ，
                // 只有 KaTeX_Math-Italic 有。用正体会画成一串豆腐块。
                // `∞` 是唯一的例外 —— 它在 Main 里，Math-Italic 反而没有。
                val style = if (sym == "∞") GlyphStyle.UPRIGHT else GlyphStyle.ITALIC
                return MLeaf(sym, style, t.start, t.text.length)
            }
            return MLeaf(
                t.text,
                if (lower in FUNCTION_NAMES) GlyphStyle.UPRIGHT else GlyphStyle.ITALIC,
                t.start
            )
        }

        // 其它单字符运算符
        eat()
        return MLeaf(
            when (t.text) {
                // KaTeX_Main 里没有 U+00B7（·），但有 U+22C5（⋅，真正的数算子点）
                "*" -> "⋅"
                "-" -> "−"
                else -> t.text
            },
            GlyphStyle.UPRIGHT,
            t.start
        )
    }
}

// ---------------------------------------------------------------------------
// 3. 测量
// ---------------------------------------------------------------------------

/** 测量结果：宽 + 基线上方高 + 基线下方深。 */
internal class Size(val w: Float, val asc: Float, val desc: Float) {
    val h: Float get() = asc + desc
}

/** 一个待绘制的字形。 */
internal class Glyph(
    val text: String,
    /** 相对整个公式左上角的 x。 */
    val x: Float,
    /** 基线 y（相对整个公式左上角）。 */
    val baseline: Float,
    val sizePx: Float,
    val style: GlyphStyle
)

/** 一条待绘制的直线（分数线 / 根号横线）。 */
internal class Rule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float)

/**
 * 一条待描边的路径 —— 根号那个勾。
 *
 * 为什么不是几条 `drawLine`：勾的拐角必须是**圆角**，两段直线接起来
 * 会在折点处露出一个尖角，放大后很刺眼（上一版就是两条直线，看起来像"对勾"）。
 * 这里用 `Path` + `StrokeJoin.Round`，拐点自然收圆。
 */
internal class PathRule(val path: Path, val width: Float)

/**
 * 空槽位的画法。
 *
 * 用户原话分得很清楚：
 * > "我是说图标用虚线框，实际运算用阴影框，点击阴影位置输入之后不显示阴影。"
 *
 * 所以同一个"空参数槽位"有两种画法，由调用方选：
 *   * [SHADOW] —— **实际运算**（输入框里的表达式）：一块半透明阴影；
 *   * [DASHED] —— **图标**（键盘上 `ⁿ√` 那种键帽）：一圈虚线框。
 */
internal enum class SlotStyle { SHADOW, DASHED, NONE }

/**
 * 一个**阴影占位块**（实际运算用，见 [SlotStyle.SHADOW]）。
 *
 * 它**有占位体积**：测量阶段就按它的宽高预留位置，所以阴影左右不会多出
 * "可选光标的位置"（用户原话："阴影没有占位体积，请不要让阴影左边和右边可以选择光标"）。
 */
internal class Placeholder(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

/**
 * 一个**占位框**（虚线或细实线）。
 *
 * 用在"按了键、但槽位还空着"的地方：n 次根号的根指数位置、根号下面那块。
 * 图标用虚线框（见 [SlotStyle.DASHED]），实际运算用 [Placeholder] 画阴影。
 */
internal class Outline(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val dashed: Boolean,
    val width: Float
)

/**
 * 一个光标停靠点。
 *
 * @param index 源码下标（0..len，插入位置）
 * @param x 竖线的 x
 * @param top 竖线上沿
 * @param bottom 竖线下沿
 */
internal class Stop(
    val index: Int,
    val x: Float,
    val top: Float,
    val bottom: Float,
    /**
     * 这个停靠点是不是**空槽位**（`x^` 的指数框、`sin()` 的括号里、分式的空分子……）。
     *
     * 同一个源码下标可能登记两个停靠点：一个是"结构内部那一行的行末"，
     * 另一个是"整个公式的行末"（都在源码末尾时下标相同、x 不同）。
     * 画光标时必须能区分它们，见 [MathLayout.stopFor]。
     */
    val slot: Boolean = false
)

/**
 * 排版结果。**不可变**：`remember(src, size)` 缓存住，绘制阶段只读。
 *
 * 坐标系统一为"相对公式左上角"，基线的绝对 y 由调用方决定 ——
 * 这样同一个公式可以画在输入框里、也可以画在别处。
 */
internal class MathLayout(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    private val glyphs: List<Glyph>,
    private val rules: List<Rule>,
    private val paths: List<PathRule>,
    private val outlines: List<Outline>,
    private val placeholders: List<Placeholder>,
    private val stops: List<Stop>,
    /** 字形来源（KaTeX 的 ttf；null = 系统字体）。 */
    private val fonts: MathTypefaces
) {

    val height: Float get() = ascent + descent

    /**
     * 测试用的只读视图：当前画出来的**空槽位（阴影）**有几个。
     *
     * 排版结果的大部分字段都是 private（绘制阶段才用），单测没法从外面看见
     * "log 底数那块阴影到底建没建"。这个属性只给单测断言用，生产代码不读。
     */
    internal val placeholderCount: Int get() = placeholders.size

    /**
     * 测试用的只读视图：某个源码下标上光标停靠点的 `(x, top, bottom, 槽位?)`。
     *
     * "光标画在结构里面还是外头"是这一轮的核心（`x^x` 的末尾要落在基线上，
     * `x^` 的末尾要落在指数槽里），单测只能靠这个看见。
     */
    internal fun stopSnapshot(index: Int): FloatArray {
        val s = stopFor(index)
        return floatArrayOf(s.x, s.top, s.bottom, if (s.slot) 1f else 0f)
    }

    /** 测试用的只读视图：`(文字, 风格, 字号)`。生产代码不读。 */
    internal val glyphSnapshots: List<Triple<String, GlyphStyle, Float>>
        get() = glyphs.map { Triple(it.text, it.style, it.sizePx) }

    /**
     * 绘制用的 Paint —— **跟着 layout 一起缓存**。
     *
     * 不能放在 `draw()` 里 new：Canvas 的绘制 lambda 每帧都会跑，
     * 一帧一个 Paint 在手表上就是持续的 GC 压力（这个 App 已经在
     * `GraphCanvas` 里为了同一个理由复用过 Paint）。
     * 布局对象本身是 `remember` 住的，所以它活多久、Paint 就活多久。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 源码长度对应的"末尾光标"x（没有 stop 时的兜底）。 */
    private val endX: Float get() = stops.lastOrNull()?.x ?: width

    /**
     * 画出来。
     *
     * @param origin 公式左上角在画布上的位置
     * @param color 文字和线共用的颜色（跟随主题）
     * @param caretIndex 光标在源码里的下标；传 null 不画光标
     * @param caretColor 光标颜色
     * @param caretAlpha 闪烁透明度
     */
    fun draw(
        scope: DrawScope,
        origin: Offset,
        color: Color,
        caretIndex: Int? = null,
        caretColor: Color = Color.White,
        caretAlpha: Float = 1f,
        caretWidth: Float = 1.5f
    ) {
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // ---- 空槽位阴影：画在**最底下**（文字盖在上面）----
            //
            // 用户要求"点击阴影位置输入之后不显示阴影"：槽位非空时
            // [layoutMath] 根本不会往 [placeholders] 里放东西，所以这里不需要任何判断。
            if (placeholders.isNotEmpty()) {
                paint.style = Paint.Style.FILL
                paint.pathEffect = null
                paint.color = color.copy(alpha = 0.26f).toArgbCompat()
                for (ph in placeholders) {
                    native.drawRoundRect(
                        origin.x + ph.x,
                        origin.y + ph.y,
                        origin.x + ph.x + ph.w,
                        origin.y + ph.y + ph.h,
                        ph.h * 0.34f,
                        ph.h * 0.34f,
                        paint
                    )
                }
                // 同色细描边：纯 26% 填充在深色卡片上几乎看不出来 ——
                // log 底数 / 根指数这两块脚本号槽位只有 12~20px 宽，光靠填充
                // 就是一团看不清的雾（用户两次反馈"没有阴影"）。
                // 描一圈边之后"这里能输入"才一眼可辨；键帽图标用的虚线框
                // （[SlotStyle.DASHED]）不受影响。
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = color.copy(alpha = 0.55f).toArgbCompat()
                for (ph in placeholders) {
                    native.drawRoundRect(
                        origin.x + ph.x,
                        origin.y + ph.y,
                        origin.x + ph.x + ph.w,
                        origin.y + ph.y + ph.h,
                        ph.h * 0.34f,
                        ph.h * 0.34f,
                        paint
                    )
                }
                // 描边用完之后必须切回填充 —— 下面的字形是 drawText，留着 STROKE
                // 会把整行公式画成空心字。
                paint.style = Paint.Style.FILL
            }
            for (g in glyphs) {
                paint.textSize = g.sizePx
                paint.typeface = typefaceOf(g.style)
                paint.color = color.toArgbCompat()
                native.drawText(g.text, origin.x + g.x, origin.y + g.baseline, paint)
            }
            paint.color = color.toArgbCompat()
            paint.style = Paint.Style.FILL
            for (r in rules) {
                paint.strokeWidth = r.width
                native.drawLine(
                    origin.x + r.x1, origin.y + r.y1,
                    origin.x + r.x2, origin.y + r.y2,
                    paint
                )
            }
            if (paths.isNotEmpty()) {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                canvas.save()
                canvas.translate(origin.x, origin.y)
                for (pr in paths) {
                    paint.strokeWidth = pr.width
                    native.drawPath(pr.path, paint)
                }
                canvas.restore()
                paint.style = Paint.Style.FILL
            }
            if (outlines.isNotEmpty()) {
                paint.style = Paint.Style.STROKE
                for (o in outlines) {
                    paint.strokeWidth = o.width
                    paint.pathEffect = if (o.dashed) {
                        DashPathEffect(floatArrayOf(o.width * 2.6f, o.width * 1.9f), 0f)
                    } else {
                        null
                    }
                    native.drawRect(
                        origin.x + o.x, origin.y + o.y,
                        origin.x + o.x + o.w, origin.y + o.y + o.h,
                        paint
                    )
                }
                paint.pathEffect = null
                paint.style = Paint.Style.FILL
            }
            if (caretIndex != null) {
                val s = stopFor(caretIndex)
                paint.color = caretColor.copy(alpha = caretColor.alpha * caretAlpha).toArgbCompat()
                paint.strokeWidth = caretWidth
                native.drawLine(
                    origin.x + s.x, origin.y + s.top,
                    origin.x + s.x, origin.y + s.bottom,
                    paint
                )
            }
        }
    }

    /**
     * 离某个源码下标最近的光标停靠点。
     *
     * 停靠点是**按源码顺序**登记的（分子在前、分母在后；内层行末先于外层行末）。
     *
     * 同一个下标可能登记两个停靠点：一个是**结构内部的**（指数 / 分母 / log 底数
     * 那一行的行末），另一个是**整行行末**（基线上）。分支在于：
     *
     *   * 光标是**源码里的一个 Int**，这两种位置的下标**完全一样** ——
     *     光凭下标分不出"还在指数里"和"已经出了整条式子"；
     *   * 用户要的是"按完 `^` 之后能一直往指数里打（`x^2`、`x^(a+b)`），
     *     而不是打一个字符就被弹到外头"，所以同下标一律**取最内层**；
     *   * "出了整条式子"则由输入框显式表达：点右侧空白会在源码末尾补一个空格、
     *     光标下标因此大于所有停靠点，落到 [stops] 的最后一条（最外层行末）。
     *   * 空槽位仍然优先（`x^` / `sin(` 要看得见"该往哪填"）。
     */
    private fun stopFor(index: Int): Stop {
        val exact = stops.filter { it.index == index }
        if (exact.isNotEmpty()) return exact.firstOrNull { it.slot } ?: exact.first()
        val after = stops.firstOrNull { it.index > index }
        if (after != null) return after
        // 光标在源码末尾补过一个空格（index > 所有停靠点）= **已经出了所有结构**。
        // 这时必须取最后下标里**纵向最高**的那条停靠点（整行行末），不能图省事拿
        // `stops.last()`：同一个下标往往同时有"分母 / 指数那一小条"和"整行行末"两条，
        // 取错那条光标就画在分数线下 / 指数里（用户 1.2.65 原话："分数线……不能定位到
        // 分数外""指数……光标还是在指数上面"）。
        val maxIndex = stops.maxOfOrNull { it.index }
            ?: return Stop(0, endX, 0f, height)
        return stops.filter { it.index == maxIndex }.maxByOrNull { it.bottom - it.top }
            ?: stops.last()
    }

    /**
     * 公式内容的右边缘（相对公式左边）。
     *
     * 输入框用它判断"点在空白处"：落点在这条线右边 = 用户想回到**整条式子**
     * 这一层继续输入（见 [MathKeyboard.ExpressionField] 的 `onCursorEnd`）。
     * 取所有停靠点的最大 x —— 停靠点已经覆盖了字形的左右边界和空槽位框。
     */
    internal val contentRight: Float get() = stops.maxOfOrNull { it.x } ?: width

    /**
     * 屏幕坐标（相对公式左边）→ 最近的源码下标。
     *
     * **必须同时看 x 和 y**：分式的分子和分母、`log` 的底数和真数、上标和基字，
     * 这几对停靠点的 **x 几乎完全重叠**（分式上下两行居中对齐、宽度相同）。
     * 上一版只按 x 找最近点、而且"同样近取后面的"，于是点分子永远命中分母
     * （用户报的"除号输入之后分数线左边不能输入"）。纵向距离一进来，
     * 点上半部分就落分子、点下半部分就落分母。
     *
     * @param x 相对公式左边的横向位置
     * @param y 相对公式顶边的纵向位置（不传 = 只按横向找，保持老调用方行为）
     */
    fun indexAt(x: Float, y: Float = Float.NaN): Int {
        if (stops.isEmpty()) return 0
        var best = stops[0]
        var bestD = Float.MAX_VALUE
        for (s in stops) {
            // 纵向：落点在这一行的上下沿之内就算 0（行内点击按横向比即可）
            val dy = when {
                y.isNaN() -> 0f
                y < s.top -> s.top - y
                y > s.bottom -> y - s.bottom
                else -> 0f
            }
            // 纵向权重 3：分式上下两行的 x 完全一样，靠纵向才能分开它们；
            // 单行公式里所有停靠点的 top/bottom 相同，权重不影响原来的手感。
            val d = kotlin.math.abs(x - s.x) + dy * 3f
            if (d < bestD) {
                best = s
                bestD = d
            }
        }
        return best.index
    }

    /** 光标 x（相对公式左边），用于横向滚动。 */
    fun caretX(index: Int): Float = stopFor(index).x

    private fun typefaceOf(style: GlyphStyle): Typeface? = fonts.of(style)
}

/** `Color` → `Int`（避免为了一个 toArgb 引入 graphics 包的重载歧义）。 */
private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255),
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255)
)

// ---------------------------------------------------------------------------
// 4. 布局
// ---------------------------------------------------------------------------

/**
 * 排版入口。
 *
 * @param source 源码（`fn.src1` 原文）
 * @param baseSizePx 正文字号（px）
 * @param maxWidthPx 可用宽度；超出时**整体等比缩小**（下限 [MIN_SCALE]），
 *   仍超出则交给调用方裁剪/滚动 —— 排版层不裁，因为它不知道滚动偏移。
 */
internal fun layoutMath(
    source: String,
    baseSizePx: Float,
    maxWidthPx: Float,
    fonts: MathTypefaces = MathTypefaces(null, null),
    /** 空槽位画法：输入框用阴影（[SlotStyle.SHADOW]），键帽图标用虚线（[SlotStyle.DASHED]）。 */
    slot: SlotStyle = SlotStyle.SHADOW
): MathLayout {
    // 先翻 LaTeX 再排版：`\frac{a}{b}` → `(a)/(b)`、`\sqrt[n]{x}` → `root(x,n)`。
    // 没有 `\` / `{` 时 [Latex.expand] 原样返回，所以普通输入（以及键盘图标
    // 那种 `root(x,)` 的固定串）的排版和光标下标完全不变。
    val cooked = Latex.expand(source)
    // 光标停靠点按"去掉末尾空白"的长度算（见 [tokenize] 的 END 说明）
    val srcEnd = cooked.trimEnd().length
    val root = Parser(tokenize(cooked), srcEnd).parseAll()

    val measurer = Measurer(baseSizePx, fonts, slot)
    var size = measurer.measure(root)
    var scale = 1f
    if (maxWidthPx > 1f && size.w > maxWidthPx) {
        scale = (maxWidthPx / size.w).coerceIn(MIN_SCALE, 1f)
    }

    val s = MathLayoutBuilder(baseSizePx * scale, srcEnd, fonts, slot)
    // 基线 = 整行的 ascent：`MathLayout` 的坐标系约定是**左上角为原点**，
    // 所以基线必须落在 y = ascent 处（而不是 0 —— 0 会让整行字形往上游走半个行高，
    // 视觉重心因此不在输入框中心，用户报的"字符高度没有居中"就是这一条）。
    val laid = s.layoutRoot(root, baseSizePx * scale)
    // 停靠点按源码下标排序：根号的**指数**画在左边、源码下标却在后面，
    // 不排序会让 [MathLayout.stopFor] 的"第一个不小于目标的停靠点"提前命中。
    s.stops.sortBy { it.index }
    return MathLayout(
        width = laid.size.w,
        ascent = laid.size.asc,
        descent = laid.size.desc,
        glyphs = s.glyphs,
        rules = s.rules,
        paths = s.paths,
        outlines = s.outlines,
        placeholders = s.placeholders,
        stops = s.stops,
        fonts = fonts
    )
}

/**
 * 括号的字号：内容越高、括号越大。
 *
 * 正常一行（`sin(x)`）时 = 原字号；含分式/根号时最多放大到 1.9 倍。
 * 上限是必要的 —— 一个三层嵌套的分式会让括号大到抢戏。
 */
private fun parenSizeFor(body: Size, sizePx: Float): Float {
    val natural = sizePx * 0.94f
    val scale = (body.h / natural).coerceIn(1f, 1.9f)
    return sizePx * scale
}

/** 自动缩小的下限：再小就谁也看不清了，宁可由调用方裁剪。 */
private const val MIN_SCALE = 0.55f

/** 上下标的相对字号。 */
private const val SCRIPT_SCALE = 0.66f

/**
 * 嵌套上下标的**字号下限**（相对公式正文）。
 *
 * `x^x^x` 现在是真的三层上标，每层都乘 [SCRIPT_SCALE] 的话
 * 0.66³ ≈ 0.29 —— 在手表输入框里就是四个像素的灰点，等于没显示。
 * TeX 也只有 script / scriptscript 两档（≈0.7 / 0.5），第三层起不再缩小；
 * 这里取 0.5 作为下限，三层以上全部停在"半号字"。
 */
private const val MIN_SCRIPT_SCALE = 0.5f

/**
 * 算下一层上下标的字号：先按 [SCRIPT_SCALE] 缩，但不低于正文的 [MIN_SCRIPT_SCALE]。
 *
 * @param sizePx 当前这一层的字号
 * @param rootBase 公式正文的字号（整棵树的基准，不是当前层）
 */
private fun scriptSize(sizePx: Float, rootBase: Float): Float =
    (sizePx * SCRIPT_SCALE).coerceAtLeast(rootBase * MIN_SCRIPT_SCALE)

/** 分式内部的相对字号（GeoGebra 也会把分式里的字缩一点）。 */
private const val FRAC_SCALE = 0.92f

/**
 * **数学轴高度**（TeX 的 `axis_height`）：分数线就画在这条线上。
 *
 * 这是分式位置的唯一参照 —— 不是"贴着分子顶部"，而是**固定在基线上方 0.25em**。
 * 上一版用 `baseline - num.h - pad`，导致分子里的东西越多、整条分数线越往上跑，
 * 一个 `(a+b)/c` 会飘到半空中，这是"看起来丑"的主要原因之一。
 */
private const val AXIS_HEIGHT = 0.25f

/** 分子/分母与分数线之间的间隙（相对当前字号）。 */
private const val FRAC_GAP = 0.12f

/** 上标相对基线的抬升（相对当前字号）。 */
private const val SUP_RISE = 0.42f

/** 根号横线与被开方内容之间的留白（相对当前字号）。 */
private const val SQRT_PAD = 0.10f

/** 根指数（`ⁿ√` 的 n）的字号，相对正文 —— 和 TeX 的 script size 同一档。 */
private const val ROOT_INDEX_SCALE = 0.58f

/**
 * 空根指数槽位的宽度（相对**指数字号**）：左上角那块阴影框。
 *
 * 必须和普通空槽位（[SLOT_W]）同一个量级 —— 0.42 / 0.62 在手表上只有
 * 8~13px 宽，拇指点不中、眼睛也看不出那里能输入（用户两次反馈"ⁿ√ 没有开几次根
 * 的位置"）。0.85 大约是"一个指数字号字符 + 两边留白"，和 [LOG_BASE_SLOT_W]
 * 同一个口径，两块脚本槽位看起来才是一套。
 */
private const val ROOT_SLOT_W = 0.85f

/**
 * 空 log 底数槽位的宽度（相对**底数字号**，也就是 [SCRIPT_SCALE] 缩过的那一档）。
 *
 * 和 [ROOT_SLOT_W] 一样，用 [SLOT_W]（0.52）在手表上只有 ≈12px，
 * 用户两次反馈"log 的底数位还是没有输入位置、也没有阴影"。
 * 单独给个更大的比例，不去动 [SLOT_W] 是因为那个常数还被空括号（`sin()`）、
 * 空上标、空分母用着，那些位置按正文字号算，bump 会把它们的阴影撑得很宽。
 */
private const val LOG_BASE_SLOT_W = 0.85f

/** 根指数与根号勾之间的水平间距（相对正文字号）。 */
private const val ROOT_INDEX_GAP = 0.05f

/**
 * 空槽位的宽度（相对当前字号）。
 *
 * 它同时是**测量宽度**和**绘制宽度** —— 一个来源，所以"阴影没有占位体积"
 * 不会发生：阴影占多宽，光标就只能在它里面/两侧的合法位置停。
 */
private const val SLOT_W = 0.52f

/** 空槽位的高度（相对当前字号）—— 用户要求"确保它有一个半字符的高度"。 */
private const val SLOT_H = 0.74f

/** 测量器：只算尺寸，不产生绘制指令。 */
private class Measurer(
    private val base: Float,
    private val fonts: MathTypefaces,
    /** 空槽位画不画 —— 画的话测量宽度里要给它留位置（见 [SLOT_W]）。 */
    private val slot: SlotStyle
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun measure(n: MNode, sizePx: Float = base): Size = when (n) {
        is MLeaf -> measureLeaf(n, sizePx)
        is MRow -> measureRow(n, sizePx)
        is MFrac -> {
            val numSize = sizePx * FRAC_SCALE
            val den = measureRow(n.den, numSize)
            val num = measureRow(n.num, numSize)
            val axis = sizePx * AXIS_HEIGHT
            val gap = sizePx * FRAC_GAP
            // 空分子 / 空分母各留一块槽位（宽度和普通空槽位同一个口径），
            // 否则 `÷` 单独按下去是一个 0×0 的分式 —— 屏幕上只看见一条横线，
            // 上面和下面都没有可以点的阴影（用户报的正是这一条）。
            val numW = if (slot != SlotStyle.NONE && n.num.isBlank() && n.numStart >= 0) {
                max(num.w, numSize * SLOT_W)
            } else {
                num.w
            }
            val denW = if (slot != SlotStyle.NONE && n.den.isBlank() && n.denStart >= 0) {
                max(den.w, numSize * SLOT_W)
            } else {
                den.w
            }
            val numH = if (slot != SlotStyle.NONE && n.num.isBlank() && n.numStart >= 0) {
                max(num.h, numSize * SLOT_H)
            } else {
                num.h
            }
            val denH = if (slot != SlotStyle.NONE && n.den.isBlank() && n.denStart >= 0) {
                max(den.h, numSize * SLOT_H)
            } else {
                den.h
            }
            Size(
                w = max(numW, denW),
                asc = axis + gap + numH,
                desc = (denH + gap - axis).coerceAtLeast(sizePx * 0.05f)
            )
        }
        is MSup -> {
            val b = measure(n.base, sizePx)
            // 嵌套上标逐层缩小，但停在 [MIN_SCRIPT_SCALE]（`x^x^x` 三层仍看得清）
            val eSize = scriptSize(sizePx, base)
            val e = measureRow(n.exp, eSize)
            // 空指数：给它一块槽位的宽度（否则 `^` 按下去什么都看不到）
            val eW = if (slot != SlotStyle.NONE && n.exp.isBlank()) {
                max(e.w, eSize * SLOT_W)
            } else {
                e.w
            }
            Size(
                w = b.w + eW,
                asc = max(b.asc, e.h + sizePx * SUP_RISE),
                desc = b.desc
            )
        }
        is MSqrt -> {
            val body = measureRow(n.body, sizePx)
            val pad = sizePx * SQRT_PAD
            val hook = sizePx * 0.62f
            // 根指数：坐在左上角，所以整棵根号的宽度要加上它、高度也要往上顶
            val idxSize = sizePx * ROOT_INDEX_SCALE
            val idxW = when {
                n.index != null -> measureRow(n.index, idxSize).w
                n.indexStart >= 0 -> idxSize * ROOT_SLOT_W
                else -> 0f
            }
            val idxGap = if (idxW > 0f) sizePx * ROOT_INDEX_GAP else 0f
            // 被开方数是空的时候（`sqrt()` / `√()` 刚按下去）要按槽位宽度**预留体积**：
            // 绘制阶段会画一块 `SLOT_W` 宽的阴影，测量时不给宽度的话那块阴影会
            // 伸出根号横线之外、压到后面的字符上（"阴影没有占位体积"的老问题）。
            val bodyW = if (slot != SlotStyle.NONE && n.body.isBlank() && n.bodyStart >= 0) {
                max(body.w, sizePx * SLOT_W)
            } else {
                body.w
            }
            Size(
                w = idxW + idxGap + hook + bodyW + pad,
                asc = body.h + pad * 2 + (if (idxW > 0f) idxSize * 0.78f else 0f),
                desc = sizePx * 0.12f
            )
        }
        is MParen -> {
            val body = measureRow(n.body, sizePx)
            // 空括号（`sin()`）：括号里那块阴影也要占宽度 ——
            // 这就是"阴影有占位体积、左右不会多出光标位"的实现点。
            val bodyW = if (slot != SlotStyle.NONE && n.body.isBlank() && n.bodyStart >= 0) {
                max(body.w, sizePx * SLOT_W)
            } else {
                body.w
            }
            // 括号按内容高度放大（`(a/b)` 里的括号必须比单个字母高）。
            // TeX 是靠 Size1~4 四套字形做的，我们只有一套 ttf，所以按比例放大字号 ——
            // 笔画会略粗一点，但比"括号明显比内容矮"好得多。
            val pSize = parenSizeFor(body, sizePx)
            val ow = textWidth(n.open, pSize, GlyphStyle.UPRIGHT)
            val cw = if (n.close.isEmpty()) 0f else textWidth(n.close, pSize, GlyphStyle.UPRIGHT)
            Size(w = ow + bodyW + cw, asc = body.asc, desc = body.desc)
        }
        is MLog -> {
            val body = measureRow(n.arg, sizePx)
            // 真数是空的时候给槽位留宽度（否则括号里的阴影没有体积）
            val bodyW = if (slot != SlotStyle.NONE && n.arg.isBlank() && n.argStart >= 0) {
                max(body.w, sizePx * SLOT_W)
            } else {
                body.w
            }
            val pSize = parenSizeFor(body, sizePx)
            val ow = textWidth("(", pSize, GlyphStyle.UPRIGHT)
            val cw = textWidth(")", pSize, GlyphStyle.UPRIGHT)
            val nameW = textWidth("log", sizePx, GlyphStyle.UPRIGHT)
            // 底数是右下角的下标：占一行下方的高度，宽度同样要给槽位留位置
            val baseSize = scriptSize(sizePx, base)
            val baseW = when {
                // 底数为空（`log(,)` 还没填）也要按槽位宽度留体积 ——
                // 空 MRow 测出来是 0 宽，直接 `measureRow` 会让槽位没有位置。
                n.base != null && !n.base.isBlank() -> measureRow(n.base, baseSize).w
                n.baseStart >= 0 && slot != SlotStyle.NONE -> baseSize * LOG_BASE_SLOT_W
                else -> 0f
            }
            val baseBottom = if (baseW > 0f) sizePx * 0.45f + baseSize * 0.22f else 0f
            Size(
                w = nameW + baseW + ow + bodyW + cw,
                asc = body.asc,
                desc = max(body.desc, baseBottom)
            )
        }
    }

    private fun measureLeaf(n: MLeaf, sizePx: Float): Size {
        if (n.text.isEmpty()) return Size(0f, sizePx * 0.78f, sizePx * 0.22f)
        val w = textWidth(n.text, sizePx, n.style)
        return Size(w, sizePx * 0.78f, sizePx * 0.22f)
    }

    fun measureRow(row: MRow, sizePx: Float): Size {
        var w = 0f
        var asc = 0f
        var desc = 0f
        for (item in row.items) {
            val s = measure(item, sizePx)
            w += s.w
            asc = max(asc, s.asc)
            desc = max(desc, s.desc)
        }
        if (row.items.isEmpty()) return Size(0f, sizePx * 0.78f, sizePx * 0.22f)
        return Size(w, asc, desc)
    }

    fun textWidth(text: String, sizePx: Float, style: GlyphStyle): Float {
        paint.textSize = sizePx
        paint.typeface = fonts.of(style)
        return paint.measureText(text)
    }
}

/** 布局产物：摊平的字形表 / 线表 / 光标停靠点表。 */
private class MathLayoutBuilder(
    private val base: Float,
    private val srcLen: Int,
    private val fonts: MathTypefaces,
    /** 空槽位画法（阴影 / 虚线 / 不画），见 [SlotStyle]。 */
    private val slot: SlotStyle
) {

    val glyphs = ArrayList<Glyph>()
    val rules = ArrayList<Rule>()
    val paths = ArrayList<PathRule>()
    val outlines = ArrayList<Outline>()
    val placeholders = ArrayList<Placeholder>()
    val stops = ArrayList<Stop>()

    private val measurer = Measurer(base, fonts, slot)

    /** 画一块空槽位：阴影 / 虚线两选一（[SlotStyle.NONE] 时什么都不做）。 */
    private fun addSlot(x: Float, y: Float, w: Float, h: Float, stroke: Float) {
        when (slot) {
            SlotStyle.NONE -> Unit
            SlotStyle.SHADOW -> placeholders += Placeholder(x, y, w, h)
            SlotStyle.DASHED -> outlines += Outline(
                x = x,
                y = y,
                w = w,
                h = h,
                dashed = true,
                width = max(1f, stroke)
            )
        }
    }

    internal class Laid(val size: Size)

    /**
     * 排一行：逐项往右摆。
     *
     * @param x 这一行的左端
     * @param baseline 这一行的基线
     */
    fun layoutRow(row: MRow, x: Float, baseline: Float, sizePx: Float): Laid =
        layoutItems(row.items, x, baseline, sizePx)

    /**
     * 排**整个公式**（根节点）。
     *
     * 和其它行不同，根节点的基线不能由调用方给 —— 它必须等于整行的 ascent，
     * 这样所有字形 / 分数线 / 根号 / 槽位的 y 都落在 `[0, height]` 里，
     * 调用方才能直接用 `(可用高度 - height) / 2` 做垂直居中。
     */
    fun layoutRoot(root: MRow, sizePx: Float): Laid {
        var asc = 0f
        for (item in root.items) asc = max(asc, measurer.measure(item, sizePx).asc)
        return layoutItems(root.items, 0f, asc, sizePx)
    }

    /**
     * 排一行。
     *
     * ⚠️ [sizePx] **必须显式传**：分式里的字是缩小的（[FRAC_SCALE]）、
     * 上标更小（[SCRIPT_SCALE]）。测量阶段用的是缩小后的字号，
     * 绘制阶段如果还按整体字号画，两边就对不上 —— 分式会顶出横线、
     * 上标会盖住基字。这个参数就是让两边用同一个数。
     */
    fun layoutItems(items: List<MNode>, x: Float, baseline: Float, sizePx: Float): Laid {
        var cx = x
        var asc = 0f
        var desc = 0f
        for (item in items) {
            val s = measurer.measure(item, sizePx)
            layoutNode(item, cx, baseline, sizePx)
            cx += s.w
            asc = max(asc, s.asc)
            desc = max(desc, s.desc)
        }
        // 行末也登记一个停靠点：源码里"这一行结束"的位置
        registerEndStop(items, cx, baseline - asc, baseline + desc)
        return Laid(Size(cx - x, asc, desc))
    }

    /**
     * 行末停靠点：找这一行里**最后一段源码**的结束下标。
     *
     * 不这么做的话，`sin(` 这种"最后一个 token 是括号"的输入，光标会停在
     * 括号的_左边_（因为括号的叶子只登记了起点），用户会以为光标不动。
     */
    private fun registerEndStop(items: List<MNode>, x: Float, top: Float, bottom: Float) {
        val lastEnd = lastSourceIndex(items) ?: return
        // 完全重复（下标、x、竖线上下沿都一样）才跳过 —— 只比下标和 x 不够：
        // `x^x` 的指数行末和整行行末 x 完全重合，但一个在上标区、一个贯穿全高。
        // 旧写法把后者的"整行行末"当重复丢掉，光标就永远卡在指数里
        // （用户报："输入 x^x 之后光标不能定位到右边空白处"）。
        if (stops.any {
                it.index == lastEnd &&
                    kotlin.math.abs(it.x - x) < 0.5f &&
                    kotlin.math.abs(it.top - top) < 0.5f &&
                    kotlin.math.abs(it.bottom - bottom) < 0.5f
            }
        ) return
        stops += Stop(lastEnd, x, top, bottom)
    }

    /** 一行里最靠后的源码下标（含 token 长度）。 */
    private fun lastSourceIndex(items: List<MNode>): Int? {
        var best: Int? = null
        for (item in items) {
            val e = endIndexOf(item)
            if (e != null && (best == null || e > best)) best = e
        }
        return best
    }

    private fun endIndexOf(n: MNode): Int? = when (n) {
        // `sourceLen == 0` 的字形是**结构造出来的**（`∛` / `cbrt(...)` 左上角那个固定的 3），
        // 它在源码里不占位置。要是把它算进"行末下标"，排版层就会在整棵根号的右边缘
        // 登记一个假停靠点（下标 1）—— 点根号左边会得到"插进函数名中间"的非法光标。
        is MLeaf -> if (n.text.isEmpty() || n.sourceLen == 0) null
                    else n.start + sourceLenOf(n)
        is MRow -> lastSourceIndex(n.items)
        is MFrac -> maxOfNotNull(
            // 分子 / 分母为空时，槽位那一位也要算进"这一结构的末尾"
            maxOfNotNull(endIndexOf(n.num), n.numStart.takeIf { it >= 0 }),
            maxOfNotNull(endIndexOf(n.den), n.denStart.takeIf { it >= 0 })
        )
        is MSup -> maxOfNotNull(
            maxOfNotNull(endIndexOf(n.base), endIndexOf(n.exp)),
            // 空指数（`x^` 还没填）：源码下标得算上槽位那一位
            maxOfNotNull(
                n.expStart.takeIf { it >= 0 },
                // `^(...)` 那个看不见的 `)` 也是源码里的真实字符：
                // 行末停靠点必须落在它**之后**（`x^()` 的行末 = 4）。
                // 否则整行行末会停在 index 3（槽位那一位）上，和槽位共用同一个下标、
                // 又在同一条 x 上，点右边空白时 `indexAt` 会先命中它 —— 光标被吸回指数。
                n.expEnd.takeIf { it >= 0 }
            )
        )
        is MSqrt -> maxOfNotNull(
            maxOfNotNull(endIndexOf(n.body), n.index?.let { endIndexOf(it) }),
            // 函数式写法（`sqrt(x)` / `root(x,n)`）的收尾 `)` 也要算一位。
            // 隐式括号画不出来，但它在源码里是**真实存在**的字符 ——
            // 漏掉它，行末停靠点会落在 `)` 之前，光标看起来"卡在根号里面"。
            n.closeStart.takeIf { it >= 0 }?.plus(1)
        )
        is MParen -> maxOfNotNull(
            endIndexOf(n.body),
            // 闭合符号（`)` / `|` / `⌉`）**自己**占的那一位。
            // 上一版写的是 `endIndexOf(body) + 1`：只要内容一变长就会多算/少算，
            // 而且 `||` 这种空内容会被算到末尾之后。
            n.closeStart.takeIf { it >= 0 }?.plus(1)
        )
        is MLog -> maxOfNotNull(
            maxOfNotNull(
                // `log(底数, 真数)` 的真数（空的时候算槽位那一位）
                if (n.arg.isBlank()) n.argStart.takeIf { it >= 0 } else endIndexOf(n.arg),
                // 底数写在逗号后面，所以它一定比真数靠后
                n.base?.let { endIndexOf(it) } ?: n.baseStart.takeIf { it >= 0 }
            ),
            // 右括号也是一位（和 MParen 同一个口径）
            n.closeStart.takeIf { it >= 0 }?.plus(1)
        )
    }

    private fun maxOfNotNull(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> max(a, b)
    }

    /**
     * 叶子在源码里占几个字符。
     *
     * 注意**符号替换**：`pi`（2 字符）画成 `π`（1 字符），但光标位置仍按源码算，
     * 所以这里必须回源码长度，不能用显示文本的长度。
     */
    private fun sourceLenOf(n: MLeaf): Int = when (n.text) {
        "π", "θ", "α", "β", "γ", "δ", "ε", "λ", "μ", "σ", "φ", "ω", "∞" -> 2
        "−", "·" -> 1
        else -> n.text.length
    }

    private fun layoutNode(n: MNode, x: Float, baseline: Float, sizePx: Float) {
        when (n) {
            is MLeaf -> {
                if (n.text.isEmpty()) return
                glyphs += Glyph(n.text, x, baseline, sizePx, n.style)
                val w = measurer.textWidth(n.text, sizePx, n.style)
                val top = baseline - sizePx * 0.78f
                val bottom = baseline + sizePx * 0.22f
                // 起点和终点各一个停靠点：拖到字的左半边/右半边都能对上。
                // `sourceLen == 0` 的人造字形（`∛`/`cbrt` 的 3）跳过：它没有源码位置，
                // 登记了就会在根号左上角多出两个假停靠点（点那儿会把光标插进函数名里）。
                if (n.sourceLen > 0) {
                    stops += Stop(n.start, x, top, bottom)
                    stops += Stop(min(n.start + sourceLenOf(n), srcLen), x + w, top, bottom)
                }
            }

            is MRow -> {
                var cx = x
                for (item in n.items) {
                    layoutNode(item, cx, baseline, sizePx)
                    cx += measurer.measure(item, sizePx).w
                }
            }

            is MFrac -> {
                val numSize = sizePx * FRAC_SCALE
                val denSize = sizePx * FRAC_SCALE
                val num = measurer.measure(n.num, numSize)
                val den = measurer.measure(n.den, denSize)
                val axis = sizePx * AXIS_HEIGHT
                val gap = sizePx * FRAC_GAP
                // ---- 空分子 / 空分母的槽位（和 Measurer 完全同一个口径）----
                // 上一版这里什么都不画：`÷` 按下去只出一条 0 宽的横线，
                // 分数线上下是空白，用户既看不出"该往哪填"，也点不中
                // （用户原话："分数线的上面和下面没有阴影，输入很容易错位"）。
                val numBlank = slot != SlotStyle.NONE && n.num.isBlank() && n.numStart >= 0
                val denBlank = slot != SlotStyle.NONE && n.den.isBlank() && n.denStart >= 0
                val numW = if (numBlank) max(num.w, numSize * SLOT_W) else num.w
                val numH = if (numBlank) max(num.h, numSize * SLOT_H) else num.h
                val denW = if (denBlank) max(den.w, numSize * SLOT_W) else den.w
                val denH = if (denBlank) max(den.h, numSize * SLOT_H) else den.h
                val w = max(numW, denW)
                // 分数线固定在数学轴上（基线往上 axis），分式整体因此是"围绕基线"的
                val barY = baseline - axis
                // 分子：**底边**贴在横线上方 gap 处
                layoutRow(n.num, x + (w - num.w) / 2f, barY - gap - num.desc, numSize)
                // 分母：**顶边**贴在横线下方 gap 处（基线 = 顶边 + ascent）
                layoutRow(n.den, x + (w - den.w) / 2f, barY + gap + den.asc, denSize)
                // 分数线粗细取 TeX 的 0.04em（上一版 0.055 偏粗，像一条黑杠）
                rules += Rule(x, barY, x + w, barY, max(1.1f, sizePx * 0.04f))
                if (numBlank) {
                    val sx = x + (w - numW) / 2f
                    val sy = barY - gap - numH
                    addSlot(sx, sy, numW, numH, max(1f, sizePx * 0.035f))
                    // 空槽位标记：同下标时会优先停在这里（见 [stopFor]）
                    stops += Stop(n.numStart, sx + numW / 2f, sy, sy + numH, slot = true)
                }
                if (denBlank) {
                    val sx = x + (w - denW) / 2f
                    val sy = barY + gap
                    addSlot(sx, sy, denW, denH, max(1f, sizePx * 0.035f))
                    stops += Stop(n.denStart, sx + denW / 2f, sy, sy + denH, slot = true)
                }
            }

            is MSup -> {
                val b = measurer.measure(n.base, sizePx)
                layoutNode(n.base, x, baseline, sizePx)
                // 和 Measurer 同一个口径（含嵌套下限），否则测出来和画出来对不上
                val eSize = scriptSize(sizePx, base)
                // 指数基线抬高：抬到"基字顶边往下一点"的位置，视觉上贴着右上角
                val eBaseline = baseline - b.asc - sizePx * SUP_RISE + sizePx * 0.62f
                layoutRow(n.exp, x + b.w, eBaseline, eSize)
                // 隐式括号的 `)` 不画进语法树，但它的位置仍是源码里的一个字符：
                // 光标停在它后面（`x^(2+1)` 的 7）时**已经在指数外面**（源码里越过了 `)`），
                // 光标要落回基线；而 `6`（指数内容的末尾）仍然留在指数那一小块里。
                if (n.expEnd >= 0) {
                    // 这条停靠点 = 指数位外面：贯穿整行（基线），不能画成指数那一小条 ——
                    // 用户 1.2.65 原话："指数是输入能定位到指数位外但是光标还是在指数上面。"
                    //
                    // 空指数时这条停靠点还要挪到**阴影框的右边缘**（见下面那段）：
                    // 框本身的左半边仍然命中槽位（expStart），右半边 = 出了指数。
                    val em = measurer.measureRow(n.exp, eSize)
                    val ms = measurer.measure(n, sizePx)
                    val expRight = if (slot != SlotStyle.NONE && n.exp.isBlank() && n.expStart >= 0) {
                        // 空指数：`measure` 出来的指数宽是 0，框却有 SLOT_W 的宽度
                        max(em.w, eSize * SLOT_W)
                    } else {
                        em.w
                    }
                    val top = baseline - ms.asc
                    stops += Stop(n.expEnd, x + b.w + expRight, top, top + ms.h)
                }
                if (slot != SlotStyle.NONE && n.exp.isBlank() && n.expStart >= 0) {
                    // 空指数槽位：按了 `^` 还没输入时要有一块阴影，点了能落到这里
                    val sw = eSize * SLOT_W
                    val sh = eSize * SLOT_H
                    val sy = eBaseline - eSize * 0.78f
                    addSlot(x + b.w, sy, sw, sh, max(1f, sizePx * 0.045f))
                    stops += Stop(n.expStart, x + b.w + sw * 0.4f, sy, sy + sh, slot = true)
                }
            }

            is MSqrt -> {
                val body = measurer.measure(n.body, sizePx)
                val pad = sizePx * SQRT_PAD
                val hook = sizePx * 0.58f
                val stroke = max(1.2f, sizePx * 0.045f)

                // ---- 根指数：坐在左上角（GeoGebra 同款）----
                val idxSize = sizePx * ROOT_INDEX_SCALE
                val idxW = when {
                    n.index != null -> measurer.measureRow(n.index, idxSize).w
                    n.indexStart >= 0 -> idxSize * ROOT_SLOT_W
                    else -> 0f
                }
                val idxGap = if (idxW > 0f) sizePx * ROOT_INDEX_GAP else 0f
                // 根号勾从指数右边开始：指数占了左上角，勾不能压着它
                val left = x + idxW + idxGap

                val top = baseline - body.asc - pad
                val botY = top + body.h + pad * 0.55f
                // 勾的上沿（横线起点）和"肩"的位置，参照 TeX 的 radical 比例
                val shoulderY = top + body.h * 0.52f
                // 空被开方数时横线要盖住那块槽位（和 Measurer 的 SLOT_W 同一个口径），
                // 否则阴影会露在根号横线外面。
                val bodyW = if (slot != SlotStyle.NONE && n.body.isBlank() && n.bodyStart >= 0) {
                    max(body.w, sizePx * SLOT_W)
                } else {
                    body.w
                }

                // 一道连续路径（不是三段直线）：拐角靠 StrokeJoin.Round 收圆，
                // 观感是"一支笔写下来的 √"，而不是"对勾 + 横线"
                val path = Path().apply {
                    moveTo(left + hook * 0.02f, shoulderY)
                    lineTo(left + hook * 0.26f, shoulderY - sizePx * 0.045f)
                    lineTo(left + hook * 0.55f, botY)
                    lineTo(left + hook * 0.98f, top)
                }
                paths += PathRule(path, stroke)

                // 横线：从勾顶一直拉到被开方内容末尾（略向右探出一点，像 TeX）
                rules += Rule(
                    left + hook * 0.96f, top,
                    left + hook + bodyW + pad * 1.2f, top,
                    stroke
                )

                // 指数基线：底边贴着横线（TeX 的 \sqrt[3] 就是这个位置）
                val idxBaseline = top - idxSize * 0.08f
                if (n.index != null) {
                    layoutRow(n.index, x, idxBaseline, idxSize)
                } else if (n.indexStart >= 0) {
                    // 空根指数槽位：和普通空槽位**同一个样式**（阴影 / 虚线），
                    // 尺寸跟着指数字号缩。上一版是 0.42×0.92 的窄竖条 ——
                    // 手表上不到 9px 宽，看起来和没有一样，用户以为"ⁿ√ 根本没有
                    // 开几次根的位置、样式和二次根号一样"。
                    val sw = idxSize * ROOT_SLOT_W
                    val sh = idxSize * SLOT_H
                    val sy = idxBaseline - sh
                    addSlot(x, sy, sw, sh, max(1f, stroke * 0.75f))
                    stops += Stop(n.indexStart, x + sw / 2f, sy, sy + sh, slot = true)
                }

                // ---- 被开方内容 ----
                if (n.body.isBlank()) {
                    // 空被开方数：留一块槽位，免得"按了根号什么都没出现"。
                    // 只有带 bodyStart 的根号（`√()` / `sqrt()` / `root(,)`）才有槽位 ——
                    // 没有下标的空根号是无处输入的，画了槽位也点不中。
                    if (n.bodyStart >= 0) {
                        val bx = left + hook + pad * 0.3f
                        addSlot(
                            x = bx,
                            y = top + pad * 0.6f,
                            w = bodyW,
                            h = body.h.coerceAtLeast(sizePx * 0.8f),
                            stroke = max(1f, stroke * 0.75f)
                        )
                        val cy = top + body.h * 0.5f
                        stops += Stop(
                            n.bodyStart,
                            bx + bodyW / 2f,
                            cy - body.h * 0.4f,
                            cy + body.h * 0.4f,
                            slot = true
                        )
                    }
                } else {
                    layoutRow(n.body, left + hook, baseline, sizePx)
                }

                // 闭合 `)`（`sqrt(x)` / `cbrt(x)` / `root(x,n)` 的函数式写法）的停靠点。
                // 这对括号在排版上是**隐式**的（不画出来），所以两个停靠点都落在
                // 根号横线的**右端**；没有它们，光标停在 `)` 之后时会被
                // [stopFor] 吸回左上角的根指数槽位（源码下标更小、可位置在左边）。
                if (n.closeStart >= 0) {
                    val right = left + hook + bodyW + pad
                    val caretTop = baseline - sizePx * 0.78f
                    val caretBottom = baseline + sizePx * 0.22f
                    stops += Stop(n.closeStart, right, caretTop, caretBottom)
                    stops += Stop(n.closeStart + 1, right, caretTop, caretBottom)
                }
            }

            is MParen -> {
                val body = measurer.measure(n.body, sizePx)
                val pSize = parenSizeFor(body, sizePx)
                val ow = measurer.textWidth(n.open, pSize, GlyphStyle.UPRIGHT)
                // 空括号（`sin()`）里的槽位要占宽度，否则右括号会压到阴影上
                val bodyW = if (slot != SlotStyle.NONE && n.body.isBlank() && n.bodyStart >= 0) {
                    max(body.w, sizePx * SLOT_W)
                } else {
                    body.w
                }
                glyphs += Glyph(n.open, x, baseline, pSize, GlyphStyle.UPRIGHT)
                layoutRow(n.body, x + ow, baseline, sizePx)
                if (slot != SlotStyle.NONE && n.body.isBlank() && n.bodyStart >= 0) {
                    // 括号里的空槽位：半字符高，点了才能落光标
                    val sh = sizePx * SLOT_H
                    val sy = baseline - sizePx * 0.74f
                    addSlot(x + ow, sy, bodyW, sh, max(1f, sizePx * 0.045f))
                    stops += Stop(
                        n.bodyStart,
                        x + ow + bodyW * 0.4f,
                        sy,
                        sy + sh,
                        slot = true
                    )
                }
                if (n.close.isNotEmpty()) {
                    val closeX = x + ow + bodyW
                    glyphs += Glyph(
                        n.close, closeX, baseline, pSize, GlyphStyle.UPRIGHT
                    )
                    // 闭合符号（`)` / `|` / `⌉` …）的停靠点：光标要能停在它**之前**
                    // 和**之后**。少了这两个停靠点，`||` 这类空内容的结构只剩槽位
                    // 一个停靠点，光标会被吸到槽位里 —— 用户看到的就是
                    // "光标只能定位到绝对值框后，输入却进了框里"。
                    if (n.closeStart >= 0) {
                        val cw = measurer.textWidth(n.close, pSize, GlyphStyle.UPRIGHT)
                        val top = baseline - sizePx * 0.78f
                        val bottom = baseline + sizePx * 0.22f
                        stops += Stop(n.closeStart, closeX, top, bottom)
                        stops += Stop(n.closeStart + 1, closeX + cw, top, bottom)
                    }
                }
            }
            is MLog -> {
                val body = measurer.measure(n.arg, sizePx)
                val pSize = parenSizeFor(body, sizePx)
                val nameW = measurer.textWidth("log", sizePx, GlyphStyle.UPRIGHT)
                val baseSize = scriptSize(sizePx, base)
                val baseW = when {
                    // 和 Measurer 同一个口径：空底数按槽位宽度占位（见那边的说明）
                    n.base != null && !n.base.isBlank() -> measurer.measureRow(n.base, baseSize).w
                    n.baseStart >= 0 && slot != SlotStyle.NONE -> baseSize * LOG_BASE_SLOT_W
                    else -> 0f
                }
                val ow = measurer.textWidth("(", pSize, GlyphStyle.UPRIGHT)
                val bodyW = if (slot != SlotStyle.NONE && n.arg.isBlank() && n.argStart >= 0) {
                    max(body.w, sizePx * SLOT_W)
                } else {
                    body.w
                }

                glyphs += Glyph("log", x, baseline, sizePx, GlyphStyle.UPRIGHT)

                // ---- 底数：右下角下标（GeoGebra 的 log 就是这个位置）----
                val baseBaseline = baseline + sizePx * 0.45f
                if (n.base != null && !n.base.isBlank()) {
                    layoutRow(n.base, x + nameW, baseBaseline, baseSize)
                } else if (n.baseStart >= 0 && slot != SlotStyle.NONE) {
                    // 底数槽位：**空**的时候画阴影 + 登记停靠点。
                    // 上一版底数为空被解析成 null（不画），而键盘 `logKey` 的
                    // cursorBack=2 又把光标停在底数上 —— 用户看到的就是
                    // "log 右下角没有输入框、但打字从底数开始"。
                    val sw = baseSize * LOG_BASE_SLOT_W
                    val sh = baseSize * SLOT_H
                    val sy = baseBaseline - baseSize * 0.78f
                    addSlot(x + nameW, sy, sw, sh, max(1f, sizePx * 0.045f))
                    stops += Stop(n.baseStart, x + nameW + sw * 0.4f, sy, sy + sh, slot = true)
                }

                val bodyX = x + nameW + baseW
                glyphs += Glyph("(", bodyX, baseline, pSize, GlyphStyle.UPRIGHT)
                layoutRow(n.arg, bodyX + ow, baseline, sizePx)
                if (slot != SlotStyle.NONE && n.arg.isBlank() && n.argStart >= 0) {
                    val sh = sizePx * SLOT_H
                    val sy = baseline - sizePx * 0.74f
                    addSlot(bodyX + ow, sy, bodyW, sh, max(1f, sizePx * 0.045f))
                    stops += Stop(n.argStart, bodyX + ow + bodyW * 0.4f, sy, sy + sh, slot = true)
                }
                glyphs += Glyph(")", bodyX + ow + bodyW, baseline, pSize, GlyphStyle.UPRIGHT)
                // 闭合 `)` 的停靠点：没有它，光标停在 `)` 之后时会被 [stopFor]
                // "吸"回底数/真数槽位（源码下标更大，可位置在左边）。
                if (n.closeStart >= 0) {
                    val closeX = bodyX + ow + bodyW
                    val cw = measurer.textWidth(")", pSize, GlyphStyle.UPRIGHT)
                    val top = baseline - sizePx * 0.78f
                    val bottom = baseline + sizePx * 0.22f
                    stops += Stop(n.closeStart, closeX, top, bottom)
                    stops += Stop(n.closeStart + 1, closeX + cw, top, bottom)
                }
            }
        }
    }
}
