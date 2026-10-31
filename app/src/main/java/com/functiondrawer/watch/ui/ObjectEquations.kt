package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 几何对象在**当前坐标系**下的方程文本。
 *
 * 用户要求（1.2.71）："如果选择否，那么切换坐标系的时候保留绘制的图形，
 * 但是绘制的图形的方程要改变成适配坐标系的方程，比如一条直线在笛卡尔坐标系、
 * 极坐标系和复平面（`|x+a|=|x-b|`，其中 `x` 是变量，`a` 和 `b` 是常数）下
 * 有三种表达方式。"
 *
 * ## 为什么单独一个文件
 *
 * 图形对象的几何是**世界坐标**的（和坐标系无关）：切换坐标系只改"怎么读这些坐标"，
 * 不移动任何点。所以这里做的全部是**显示层换算**：
 *
 * | 坐标系 | 一条直线 `y = kx + b` |
 * |---|---|
 * | 笛卡尔 | `y = 2x + 1` |
 * | 极坐标 | `r = 1 / (sin θ - 2 cos θ)` |
 * | 复平面 | `\|x - (1+i)\| = \|x - (-1-i)\|`（中垂线写法，和用户给的格式同源） |
 *
 * 放在 `ui/` 而不是 `GraphObjects.kt`：那里是**纯 Kotlin**（离线单测钉着，
 * 不能 import `CoordinateSystem` —— 它住在带 Compose 的 `GraphState.kt`）。
 * 这里是 UI 层，可以安全地读枚举。
 */

/** 把一个几何对象的世界坐标方程翻译成 [system] 下的写法（点/度量由调用方自己处理）。 */
internal fun lineEquationFor(line: GLine, system: CoordinateSystem): String? {
    if (line.isDegenerate) return null
    return when (system) {
        CoordinateSystem.CARTESIAN -> lineEquationSummary(line)
        CoordinateSystem.POLAR -> polarLineEquation(line)
        CoordinateSystem.COMPLEX -> complexLineEquation(line)
    }
}

/** 点在世界坐标 [p] 处、在 [system] 下的摘要（列表行与属性页共用同一句话）。 */
internal fun pointSummaryText(p: GPoint, system: CoordinateSystem): String = when (system) {
    CoordinateSystem.CARTESIAN -> "点 (${eqNum(round3(p.x))}, ${eqNum(round3(p.y))})"
    CoordinateSystem.POLAR -> {
        val r = round3(hypot(p.x, p.y))
        val deg = round3(Math.toDegrees(atan2(p.y, p.x)))
        "点 (r = ${eqNum(r)}, θ = ${eqNum(deg)}°)"
    }
    CoordinateSystem.COMPLEX -> "点 (${complexValueText(p.x, p.y)})"
}

// ===================================================================================
// 函数（含圆 / 圆锥曲线这类隐函数）的方程改写
//
// 用户要求（1.3.1）："切换坐标系删除绘制图形选项改成否的选项下改成原来的函数也不清空，
// 只是用 rcosθ 代替 x，rsinθ 代替 y（极坐标系）；复平面下用 Re a 代替 x，Im a 代替 y"。
//
// 只改**显示**：`PlotFunction.src1` 一个字都不动，切回笛卡尔还是原来的式子。
// 覆盖两类："圆 / 圆锥曲线"是 `PlotMode.IMPLICIT` 的隐函数，"普通函数"是 `EXPLICIT`；
// 参数方程（`x(t), y(t)`）本来就是 t 的式子，不做替换。
// ===================================================================================

/** 列表行 / 属性页里要排版的源码（和 `FunctionList.listFormulaSource` 同一份规则）。 */
internal fun plotFunctionSourceText(fn: PlotFunction): String = when (fn.mode) {
    PlotMode.EXPLICIT, PlotMode.POLAR, PlotMode.IMPLICIT -> fn.src1
    PlotMode.PARAMETRIC -> "(${fn.src1},${fn.src2})"
}

/**
 * 同一条函数在 [system] 下的**显示**方程：
 * 笛卡尔原样；极坐标把 `x`/`y` 换成 `rcosθ`/`rsinθ`；复平面换成 `Re a`/`Im a`。
 *
 * 只替换 EXPLICIT / IMPLICIT（笛卡尔式的 `y=f(x)`、`F(x,y)=0`）。
 * `POLAR` / `PARAMETRIC` 模式的式子本来就是 θ / t 的，替换只会写坏它们。
 */
internal fun plotFunctionDisplayText(fn: PlotFunction, system: CoordinateSystem): String {
    val raw = plotFunctionSourceText(fn)
    if (system == CoordinateSystem.CARTESIAN) return raw
    if (fn.mode != PlotMode.EXPLICIT && fn.mode != PlotMode.IMPLICIT) return raw
    val (xText, yText) = when (system) {
        CoordinateSystem.POLAR -> "rcosθ" to "rsinθ"
        CoordinateSystem.COMPLEX -> "Re a" to "Im a"
        CoordinateSystem.CARTESIAN -> return raw
    }
    return substituteVars(raw, xText, yText)
}

/** 独立出现的变量 `x` / `y`（既不能是字母开头的前缀，也不能是函数名里的一份）。 */
private val X_VAR = Regex("(?<![A-Za-z0-9_])x(?![A-Za-z0-9_])")
private val Y_VAR = Regex("(?<![A-Za-z0-9_])y(?![A-Za-z0-9_])")

private fun substituteVars(src: String, xText: String, yText: String): String =
    Y_VAR.replace(X_VAR.replace(src, xText), yText)

// ===================================================================================
// 极坐标
// ===================================================================================

/**
 * 直线在极坐标下的写法（`x = r cos θ`、`y = r sin θ` 代进 `y = kx + b`）：
 *
 *   * 竖直 `x = c` → `r = c / cos θ`（`c = 0` 就是 y 轴本身 → `θ = 90°`）；
 *   * 水平 `y = b` → `r = b / sin θ`（`b = 0` 就是极轴本身 → `θ = 0°`）；
 *   * 过原点 `y = kx` → `θ = arctan(k)`（写成角度，比 `0.785` 好读）；
 *   * 一般 → `r = b / (sin θ - k cos θ)`。
 *
 * 线段/射线**只给方程、不带定义域**：定义域在极坐标下不是"x 的区间"
 * （那是笛卡尔那一套），硬抄过来只会自相矛盾。切到笛卡尔时会自动带上。
 */
private fun polarLineEquation(line: GLine): String? {
    if (line.isDegenerate) return null
    if (isVerticalForEquation(line)) {
        val c = round3(line.p.x)
        return if (c == 0.0) "θ = 90°" else "r = ${eqNum(c)} / cos θ"
    }
    val k = round3(line.dy / line.dx)
    if (k == 0.0) {
        val b = round3(line.p.y)
        return if (b == 0.0) "θ = 0°" else "r = ${eqNum(b)} / sin θ"
    }
    // b 用**显示用的 k** 反算（和 `lineEquationText` 同一条理由：列表里的方程要过锚点）
    val b = round3(line.p.y - k * line.p.x)
    if (b == 0.0) {
        val deg = round3(Math.toDegrees(atan(k)))
        return "θ = ${eqNum(deg)}°"
    }
    val denom = when {
        k == 1.0 -> "sin θ - cos θ"
        k == -1.0 -> "sin θ + cos θ"
        k > 0.0 -> "sin θ - ${eqNum(k)} cos θ"
        else -> "sin θ + ${eqNum(-k)} cos θ"
    }
    return "r = ${eqNum(b)} / ($denom)"
}

/** 斜率大到任何缩放下都看不出倾斜 → 按竖直线写（阈值与 `GraphObjects.kt` 一致）。 */
private fun isVerticalForEquation(line: GLine): Boolean {
    if (line.dx == 0.0) return true
    val k = line.dy / line.dx
    return !k.isFinite() || abs(k) >= 1e5
}

// ===================================================================================
// 复平面
// ===================================================================================

/**
 * 直线在复平面下的写法：**中垂线形式** `|x - p| = |x - q|`
 * （`p`、`q` 关于这条直线对称，`x` 在这里是复变量）。
 *
 * 用户给的格式是 `|x+a| = |x-b|` —— 同一个意思（把 `-b` 移到左边就是 `x - b`）。
 * 取法：直线上的锚点 `F` 沿单位法向往两边各挪 1 个单位得到 `p`、`q`，
 * 于是这条直线正好是 `p q` 的中垂线。所有坐标系下几何都不动，所以这个写法
 * 与原来那条线**逐点重合**。
 *
 * 线段/射线同样只给方程（不带定义域），理由同 [polarLineEquation]。
 */
private fun complexLineEquation(line: GLine): String? {
    if (line.isDegenerate) return null
    val len = hypot(line.dx, line.dy)
    if (!len.isFinite() || len == 0.0) return null
    // 单位方向 → 单位法向
    val nx = -line.dy / len
    val ny = line.dx / len
    val f = line.p
    val p = GPoint(f.x + nx, f.y + ny)
    val q = GPoint(f.x - nx, f.y - ny)
    return "|${complexShiftText("x", p)}| = |${complexShiftText("x", q)}|"
}

/**
 * `x - p` 的文本：`x`、`x - 1`、`x - i`、`x + 1 - 2i`…
 *
 * 不写 `x - (-2+i)` 这种"负负得负"的样子：实部/虚部的符号都折进运算符。
 */
private fun complexShiftText(base: String, p: GPoint): String {
    val a = round3(p.x)
    val b = round3(p.y)
    val sb = StringBuilder(base)
    if (a != 0.0) {
        sb.append(if (a > 0.0) " - " else " + ").append(eqNum(abs(a)))
    }
    if (b != 0.0) {
        sb.append(if (b > 0.0) " - " else " + ").append(imagText(abs(b)))
    }
    return sb.toString()
}

/** 一个复数点的文本：`2+3i`、`-i`、`2`、`-0.5 + 1.5i`。 */
private fun complexValueText(x: Double, y: Double): String {
    val a = round3(x)
    val b = round3(y)
    if (b == 0.0) return eqNum(a)
    val im = imagText(abs(b))
    if (a == 0.0) return if (b < 0.0) "-$im" else im
    return eqNum(a) + (if (b > 0.0) " + " else " - ") + im
}

/** 虚部的文字：`1` 写在系数位置时省略（`i` 而不是 `1i`）。 */
private fun imagText(v: Double): String = if (v == 1.0) "i" else eqNum(v) + "i"

// ===================================================================================
// 数字格式（与 `GraphObjects.eqNumber` 同一套规则：千分位、科学计数法、0 就是 0）
// ===================================================================================

private fun round3(v: Double): Double =
    if (!v.isFinite()) v else Math.round(v * 1000.0) / 1000.0

private fun eqNum(v: Double): String = when {
    v.isNaN() -> "—"
    !v.isFinite() -> if (v > 0) "∞" else "-∞"
    v == 0.0 -> "0"
    abs(v) >= 1e5 || abs(v) < 1e-4 ->
        String.format(java.util.Locale.US, "%.1e", v)
    else -> {
        val rounded = round3(v)
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }
}
