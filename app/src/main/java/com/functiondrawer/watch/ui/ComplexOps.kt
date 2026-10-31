package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.Complex
import com.functiondrawer.watch.engine.ExpressionException
import com.functiondrawer.watch.engine.compileComplex
import com.functiondrawer.watch.engine.evaluate

/**
 * 复平面上的**一条数值运算**（用户需求 #1）。
 *
 * 用户原话："在复平面模式下**没有函数只有描点和数值运算**，预留的 a b c d 就是为这个准备的。"
 * 所以复平面里"方程/函数"那一格换成了这个：
 *
 * ```
 *   数值运算：  (z^2 + a) / (z - b)         ← 用户输入的表达式
 *   ⇒ 结果：    1.5 - 0.25i                 ← 一个复数
 *   ⇒ 画法：    在复平面的 (1.5, -0.25) 处**画一个点**（不是曲线）
 * ```
 *
 * 为什么单独一个文件、而且**不依赖 Compose**：求值规则（用哪个 z、参数怎么来、
 * 出错怎么办、点叫什么名字）是纯逻辑，用 `tools/run-tool-tests.ps1` 离线钉住最划算 ——
 * 复平面是这一批里最"新"的东西，语义容易改，改动时最需要立刻知道有没有碰坏别处。
 *
 * 求值本身交给引擎（`engine/ComplexMath.kt` 的 `evaluate`，35 个测试覆盖了
 * 四则运算、分支切割、`i^i`、`e^{iπ}` 这些），这里只负责**把 UI 的意图翻译过去**。
 */
data class ComplexOp(
    val id: Long,
    /** 列表里显示的名字。带点的名字用大写（和"点/方程"一套规则，见 `GraphState.nextUpperName`）。 */
    val name: String,
    /** 表达式，例如 `(z^2+a)/(z-b)`。变量 `z`、参数 `a b c d`。 */
    val src: String,
    val colorIndex: Int = 0,
    val visible: Boolean = true,
    /** 是否把结果数值显示在图上（用户对度量提过这个开关，数值运算同理）。 */
    val showValue: Boolean = true
)

/** 求值结果：要么是一个复数，要么是一句能读懂的中文错误。 */
sealed interface ComplexOpResult {
    data class Ok(val value: Complex) : ComplexOpResult

    /**
     * 表达式有问题（语法、未知名字、参数个数不对）。
     *
     * [position] 是**字符下标**（和引擎的 `ExpressionException.position` 同一个口径），
     * 界面可以用它在这一格上做个标记；[message] 是给用户看的中文说明。
     */
    data class Failed(val message: String, val position: Int = -1) : ComplexOpResult
}

/**
 * 求一条数值运算的结果。
 *
 * [z] 是代入的自变量（用户点出来的那个复平面上的点）；[params] 是 `a b c d` 的当前值
 * （来自滑动条，`ui/Sliders.kt` —— 那里的值本来是实数，`parameterValuesOf` 会把它们
 * 装成虚部为 0 的复数）。两个都可以不传：`z` 默认 0、参数默认 1.0。
 */
fun evaluateComplexOp(
    op: ComplexOp,
    z: Complex = Complex.ZERO,
    params: Map<String, Complex> = emptyMap()
): ComplexOpResult {
    if (op.src.isBlank()) return ComplexOpResult.Failed("还没有输入表达式")
    return try {
        ComplexOpResult.Ok(evaluate(op.src, z, params))
    } catch (e: com.functiondrawer.watch.engine.ExpressionException) {
        ComplexOpResult.Failed(e.message ?: "表达式有问题", e.position)
    } catch (e: Throwable) {
        // 兜底：引擎不该抛别的，但绝不能让一条算错的表达式把界面搞崩
        ComplexOpResult.Failed(e.message ?: "这条运算算不出来")
    }
}

/**
 * 结果在图上作为点标签显示的文本（`2+3i`、`-i`、`1.23e7`…）。
 *
 * 失败时返回 null —— 调用方据此决定"不画这个点"，而不是画一个写着错误信息的点。
 */
fun complexOpLabel(result: ComplexOpResult): String? =
    (result as? ComplexOpResult.Ok)?.let { com.functiondrawer.watch.engine.resultLabel(it.value) }

/** 结果作为**世界坐标**的点（复平面的横轴是实部、纵轴是虚部）。 */
fun complexOpPoint(result: ComplexOpResult): com.functiondrawer.watch.engine.GPoint? =
    (result as? ComplexOpResult.Ok)?.let {
        com.functiondrawer.watch.engine.GPoint(it.value.re, it.value.im)
    }

/**
 * 数值运算的名字规则。
 *
 * 用户对点与方程的要求是"从 A 开始按字母表推移"，数值运算的结果画出来就是**一个点**，
 * 所以沿用同一套大写命名：`A(x)` 那种括号写法是函数的，这里就一个大写字母。
 * [taken] 是已经用掉的名字，用来避开重复。
 */
fun nextComplexOpName(taken: Set<String>, index: Int): String {
    var i = index.coerceAtLeast(0)
    while (true) {
        val candidate = lettersOf(i).uppercase()
        if (candidate !in taken) return candidate
        i++
    }
}

/** 和 `GraphState.letters` 同一套双射 26 进制（0→a、25→z、26→aa…）。 */
private fun lettersOf(index: Int): String {
    var n = index.coerceAtLeast(0)
    val sb = StringBuilder()
    while (true) {
        sb.insert(0, ('a' + (n % 26)))
        n = n / 26 - 1
        if (n < 0) break
    }
    return sb.toString()
}

// ===================================================================================
// 复平面的「点定义」：`x = 2+i`
//
// 用户要求（1.2.71）："字母键盘里和其他键盘里面的字母键盘输入的字母只能输入
// x＝2+i 这样的形式，x 就对应复平面里的一个点，名字为 x，不能单独定义 y＝x
// 这样的函数，因为复平面不能输入函数。"
//
// 所以复平面没有"函数"，只有"点"：**等号左边是点名（单个字母）、右边是复数表达式**。
// 等号右边的表达式交给复数引擎（`compileComplex`）编译 —— 它只认 `z` 这个变量，
// 所以 `y = x` 里的 `x` 会被判成"不认识的名字"而**自然被拒绝**，
// 不需要额外写一条"不许定义函数"的规则。
// ===================================================================================

/**
 * 一条复平面点定义的解析结果：点名 + 右边的复数表达式。
 *
 * [expression] 是在**求值/校验**时用的那一段（已经去掉 `x =` 前缀）；
 * 界面上的原文仍然完整地留在 `PlotFunction.src1` 里（列表行直接显示 `x = 2+i`）。
 */
data class ComplexPointDefinition(val name: String, val expression: String)

/**
 * 只做**结构**解析，不编译表达式：`x = 2+i` → `(x, "2+i")`。
 *
 * 规则（宽松的一半）：
 *   * 必须有一个 `=`，且左边不是空的；
 *   * 左边 trim 之后必须是**单个英文字母**，且不能是虚数单位 `i`；
 *   * 右边 trim 之后不能是空的。
 *
 * 不在这里编译是为了让 `GraphState.complexResultPoints` 能对**半成品**输入
 * （用户还在打字）也给出"表达式"那一段 —— 编译失败就不画点，而不是整条消失。
 *
 * @return 不符合结构时返回 null（调用方当"这不是点定义"处理）。
 */
fun parseComplexPointDefinition(src: String): ComplexPointDefinition? {
    val raw = src.trim()
    val eq = raw.indexOf('=')
    if (eq <= 0) return null
    val name = raw.substring(0, eq).trim()
    if (name.length != 1) return null
    val c = name[0]
    if (c !in 'a'..'z' && c !in 'A'..'Z') return null
    if (c == 'i' || c == 'I') return null
    val body = raw.substring(eq + 1).trim()
    if (body.isEmpty()) return null
    return ComplexPointDefinition(name, body)
}

/**
 * 结构解析 + **编译校验**：确认时用这一个。
 *
 * `compileComplex` 会把"不认识的名字"（比如 `y = x` 右边的 `x`）、语法错误、
 * 参数个数不对全部抛成 [ExpressionException]，这里一律折算成 null ——
 * 调用方据此弹一句"复平面只能输入 x = 2+i"并**留在编辑器**。
 *
 * `a b c d` 等参数取引擎默认值（1.0）；表达式里写滑动条名字时，真正的求值
 * 由 `GraphState` 那边带上当前参数（见 `complexResultPoints`），校验这一关
 * 只关心"语法和名字能不能编译"。
 */
fun validatedComplexPointDefinition(src: String): ComplexPointDefinition? {
    val def = parseComplexPointDefinition(src) ?: return null
    return try {
        compileComplex(def.expression)
        def
    } catch (e: ExpressionException) {
        null
    } catch (e: Throwable) {
        null
    }
}

/**
 * 复平面点名的自动生成：`a` → `z`，用完接 `aa`、`ab`…（和 [lettersOf] 同一套双射 26 进制）。
 *
 * 用户要求（1.3.1）："允许直接输入复数，比如 1+i，输入之后自动创建名称，
 * 从 a 到 z 递增，用完了就用 aa，ab 这样的名字"。
 *
 * [taken] 是**已经用掉的小写名字**（调用方负责统一大小写）；从 [start] 开始往后找
 * 第一个空位，所以中间有空洞（删掉过某个点）也会先填回去。
 */
fun nextComplexPointName(taken: Set<String>, start: Int = 0): String {
    var i = start.coerceAtLeast(0)
    while (true) {
        val candidate = lettersOf(i)
        if (candidate !in taken) return candidate
        i++
    }
}

/** 一条复平面输入的解析结果：点名（可能是自动生成的）+ 复数表达式。 */
data class ResolvedComplexPoint(val name: String, val expression: String)

/**
 * 复平面确认框的统一入口：**两种写法都认**。
 *
 *   1. `x = 2+i` —— 用户自己点名（老写法，仍然支持）；
 *   2. `2+i`      —— 只给复数，名字由 [nextComplexPointName] 自动生成（1.3.1 新增）。
 *
 * 两种都要过 [compileComplex]：`y = x` 这种拿函数当输入的写法（右边 `x` 不是
 * 复平面主变量 `z`）会被引擎判成"不认识的名字"，这里折算成 null。
 */
fun resolveComplexPointInput(src: String, takenNames: Set<String>): ResolvedComplexPoint? {
    val def = parseComplexPointDefinition(src)
    if (def != null) {
        return try {
            compileComplex(def.expression)
            ResolvedComplexPoint(def.name, def.expression)
        } catch (e: ExpressionException) {
            null
        } catch (e: Throwable) {
            null
        }
    }
    val expr = src.trim()
    if (expr.isEmpty()) return null
    return try {
        compileComplex(expr)
        ResolvedComplexPoint(nextComplexPointName(takenNames), expr)
    } catch (e: ExpressionException) {
        null
    } catch (e: Throwable) {
        null
    }
}
