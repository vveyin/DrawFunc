package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.Complex
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
