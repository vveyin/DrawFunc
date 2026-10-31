package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GPoint
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 「圆」「圆锥曲线」两栏用到的**代数式生成**（用户需求：参照 GeoGebra 的作图工具）。
 *
 * 这一套工具产出的不是新的对象类型，而是一条**隐函数曲线**（[com.functiondrawer.watch.engine.PlotMode.IMPLICIT]）：
 * 圆、椭圆、抛物线、双曲线、任意的五点圆锥曲线，全都能写成 `F(x, y) = 0`。
 * 这样：
 *   * 采样、绘制、显隐、着色、属性页、列表、删除全部复用既有管线（零改动）；
 *   * 半径可以直接写成变量（用户原话："设置 d 为半径"），隐函数采样会把它折成数值；
 *   * 列表里显示的就是方程本身，和 GeoGebra 一样点开就能看懂。
 *
 * 圆弧是**参数方程**（`x = cx + r cos t`、`y = cy + r sin t`，t 走一段区间），
 * 所以它产出的是 [PlotMode.PARAMETRIC] 的两个分量。
 *
 * ## 数字写法
 *
 * 所有数值都用 [fmtNum] 写成**十进制字面量**（最多 8 位小数、去掉末尾的 0）。
 * 不用 `formatNumber` 那一套科学计数法：那是**给人看**的，而这里生成的是要
 * 喂回表达式解析器的**源码**，`1.0e+05` 那种写法会多绕一层词法解析。
 * 也不用 `Calc.format`（它按用户选的精度截断）：几何作图的结果必须是真实数值，
 * 否则"保留 3 位小数"时一个半径 0.0004 的圆会被写成一个点。
 */
internal fun fmtNum(v: Double): String = when {
    !v.isFinite() -> "0"
    v == 0.0 -> "0"
    else -> BigDecimal.valueOf(v)
        .setScale(8, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

/** `(x - 2)` / `(x + 3)` / `x` —— 把"平移量"写进括号里。 */
private fun shifted(name: String, c: Double): String = when {
    abs(c) < 1e-12 -> name
    c > 0.0 -> "$name-${fmtNum(c)}"
    else -> "$name+${fmtNum(-c)}"
}

/** `(x-a)^2 + (y-b)^2` 这段（圆的左边，也是椭圆/双曲线里每个焦点的距离平方）。 */
private fun squaredDistance(name1: String, c1: Double, name2: String, c2: Double): String =
    "(${shifted(name1, c1)})^2+(${shifted(name2, c2)})^2"

/** 到点 [p] 的距离：`sqrt((x-px)^2+(y-py)^2)`。 */
private fun distanceExpr(p: GPoint): String = "sqrt(${squaredDistance("x", p.x, "y", p.y)})"

/**
 * 圆：`(x-cx)^2 + (y-cy)^2 = r^2`。
 *
 * @return 半径不是正数 / 坐标非有限时返回 null（调用方给一句话，不建曲线）。
 */
internal fun circleEquation(center: GPoint, radius: Double): String? {
    if (!center.x.isFinite() || !center.y.isFinite()) return null
    if (!radius.isFinite() || radius <= 0.0) return null
    return "${squaredDistance("x", center.x, "y", center.y)}=${fmtNum(radius * radius)}"
}

/**
 * 过三点的圆：返回圆心和半径（圆心由两条中垂线的交点解出来）。
 *
 * @return 三点共线 / 有重合时 null —— 那是"画不出圆"的唯一情形。
 */
internal fun circleThroughThreePoints(p1: GPoint, p2: GPoint, p3: GPoint): Pair<GPoint, Double>? {
    val ax = p1.x
    val ay = p1.y
    val bx = p2.x
    val by = p2.y
    val cx = p3.x
    val cy = p3.y
    // 2 × 三角形有向面积；为 0 说明三点共线
    val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if (!d.isFinite() || abs(d) < 1e-12) return null
    val a2 = ax * ax + ay * ay
    val b2 = bx * bx + by * by
    val c2 = cx * cx + cy * cy
    val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
    val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
    val r = hypot(ux - ax, uy - ay)
    if (!ux.isFinite() || !uy.isFinite() || !r.isFinite() || r < 1e-12) return null
    return GPoint(ux, uy) to r
}

/**
 * 圆，但半径是一个**用户输入的表达式**（可以引用滑动条 `d`、常量、四则运算）。
 *
 * 写成 `... = (表达式)^2` 而不是先求值：这样采样时每次都会重新读参数，
 * 拖动滑动条 `d` 圆会跟着变大变小 —— 比"建的时候算一次、之后定格"更符合
 * GeoGebra 的直觉，也正好是用户要的"可以调用其他变量的值"。
 */
internal fun circleRadiusExpressionEquation(center: GPoint, radiusSource: String): String =
    "${squaredDistance("x", center.x, "y", center.y)}=(${radiusSource.trim()})^2"

/**
 * 椭圆：两个焦点 + 椭圆上一点。
 *
 * 用"到两焦点距离之和 = 2a"的**定义式**（而不是先算长轴短轴再拼标准方程）：
 * 后者在椭圆被旋转过的时候要解一个坐标系旋转，前者对任意朝向都成立，
 * 而且隐函数采样本来就是按 `F(x,y)=0` 画的。
 *
 * @return 三点共线 / 重合（画不出椭圆）时 null。
 */
internal fun ellipseEquation(f1: GPoint, f2: GPoint, on: GPoint): String? {
    if (!f1.x.isFinite() || !f2.x.isFinite() || !on.x.isFinite()) return null
    val d1 = hypot(on.x - f1.x, on.y - f1.y)
    val d2 = hypot(on.x - f2.x, on.y - f2.y)
    val twoA = d1 + d2
    // on 落在两焦点连线上、又在两个焦点之间时，2a 恰好等于两焦点距离 —— 退化
    if (!twoA.isFinite() || twoA <= hypot(f2.x - f1.x, f2.y - f1.y) + 1e-9) return null
    return "${distanceExpr(f1)}+${distanceExpr(f2)}=${fmtNum(twoA)}"
}

/**
 * 双曲线：两个焦点 + 曲线上一点。
 *
 * 定义式：`| |PF1| - |PF2| | = 2a`（两个分支都在同一张隐函数图上，
 * 和 GeoGebra 画出来的一样）。
 */
internal fun hyperbolaEquation(f1: GPoint, f2: GPoint, on: GPoint): String? {
    if (!f1.x.isFinite() || !f2.x.isFinite() || !on.x.isFinite()) return null
    val d1 = hypot(on.x - f1.x, on.y - f1.y)
    val d2 = hypot(on.x - f2.x, on.y - f2.y)
    val twoA = abs(d1 - d2)
    // 点在两焦点之间（两距离相等）时退化成一条直线，画不出曲线
    if (!twoA.isFinite() || twoA <= 1e-9) return null
    return "abs(${distanceExpr(f1)}-${distanceExpr(f2)})=${fmtNum(twoA)}"
}

/**
 * 抛物线：焦点 + 准线。
 *
 * 定义式：到焦点的距离 = 到准线的距离。准线用一般式 `a*x + b*y + c = 0`
 * 先归一化（`a²+b²=1`），右边就是 `abs(a*x+b*y+c)`，不用再除长度。
 */
internal fun parabolaEquation(focus: GPoint, directrix: GLine): String? {
    if (!focus.x.isFinite() || !focus.y.isFinite() || directrix.isDegenerate) return null
    val dx = directrix.q.x - directrix.p.x
    val dy = directrix.q.y - directrix.p.y
    val len = hypot(dx, dy)
    if (!len.isFinite() || len < 1e-12) return null
    val a = dy / len
    val b = -dx / len
    val c = -(a * directrix.p.x + b * directrix.p.y)
    val rhs = "abs(${affineExpr(a, b, c)})"
    return "${distanceExpr(focus)}=$rhs"
}

/** `a*x + b*y + c`（符号连写，永远不出现 `+ -3`）。 */
private fun affineExpr(a: Double, b: Double, c: Double): String {
    val sb = StringBuilder()
    sb.append(fmtNum(a)).append("*x")
    sb.append(if (b >= 0.0) "+" else "-").append(fmtNum(abs(b))).append("*y")
    sb.append(if (c >= 0.0) "+" else "-").append(fmtNum(abs(c)))
    return sb.toString()
}

// -----------------------------------------------------------------------------------
// 圆弧（参数方程）
// -----------------------------------------------------------------------------------

/**
 * 一段圆弧的三个参数方程数值：`x = cx + r cos t`、`y = cy + r sin t`，`t ∈ [tMin, tMax]`。
 *
 * 从圆心、弧的起点、弧的终点算出来。方向按 GeoGebra 的「圆弧」：
 * **从起点逆时针转到终点**（所以终点的角度小于起点时加一圈）。
 */
internal data class ArcSpec(
    val xs: String,
    val ys: String,
    val tMin: Double,
    val tMax: Double,
    val radius: Double
)

internal fun arcOf(center: GPoint, start: GPoint, end: GPoint): ArcSpec? {
    if (!center.x.isFinite() || !center.y.isFinite()) return null
    val r = hypot(start.x - center.x, start.y - center.y)
    if (!r.isFinite() || r < 1e-9) return null
    val a1 = atan2(start.y - center.y, start.x - center.x)
    var a2 = atan2(end.y - center.y, end.x - center.x)
    if (a2 <= a1 + 1e-9) a2 += 2.0 * PI
    return ArcSpec(
        xs = "${fmtNum(center.x)}+${fmtNum(r)}*cos(t)",
        ys = "${fmtNum(center.y)}+${fmtNum(r)}*sin(t)",
        tMin = a1,
        tMax = a2,
        radius = r
    )
}

// -----------------------------------------------------------------------------------
// 过五点的圆锥曲线
// -----------------------------------------------------------------------------------

/**
 * 过五个点的圆锥曲线，返回一般式 `A*x^2+B*x*y+C*y^2+D*x+E*y+F=0` 的源码。
 *
 * 做法：把五个点代进一般方程，得到一个 5×5 的齐次线性方程组。齐次方程
 * 总可以**归一化一个系数为 1**（只要它非零），于是变成 5 个未知数的非齐次方程。
 * 依次尝试把 `F / A / B / C` 归一化 —— 曲线不过原点时 `F` 就能解；
 * 过原点时会退到后面几种（这是 GeoGebra 也走同一条思路的地方）。
 *
 * 返回的系数会按"最大绝对值 = 1"缩放，避免源码里出现 `1.0E-17*x^2` 这种字面量。
 *
 * @return 五点退化（重合、共线）或方程无解时 null。
 */
internal fun conicThroughPoints(points: List<GPoint>): String? {
    if (points.size < 5) return null
    val p = points.take(5)
    if (p.any { !it.x.isFinite() || !it.y.isFinite() }) return null

    // 每种归一化：把哪一个系数钉成 1，其余五个当未知数
    // index: 0=A 1=B 2=C 3=D 4=E 5=F
    for (pinned in intArrayOf(5, 0, 1, 2)) {
        val sol = solveConicNormalized(p, pinned) ?: continue
        val text = conicText(sol)
        if (text != null) return text
    }
    return null
}

/** 归一化一个系数之后解 5×5；无解（奇异）返回全 6 个系数，失败返回 null。 */
private fun solveConicNormalized(points: List<GPoint>, pinned: Int): DoubleArray? {
    val rowFor: (GPoint) -> DoubleArray = { pt ->
        val x = pt.x
        val y = pt.y
        doubleArrayOf(x * x, x * y, y * y, x, y, 1.0)
    }
    // 未知数 = 除 pinned 之外的五个系数；方程：sum(unknown * col) = -pinnedCoef
    val unknowns = (0..5).filter { it != pinned }
    val a = Array(5) { i ->
        val row = rowFor(points[i])
        DoubleArray(6) { c -> if (c < 5) row[unknowns[c]] else -row[pinned] }
    }
    val solved = solveLinearSystem(a) ?: return null
    val out = DoubleArray(6)
    out[pinned] = 1.0
    for (c in 0 until 5) out[unknowns[c]] = solved[c]
    return out
}

/**
 * 高斯消元（带部分主元）。`m` 是 5 行 × 6 列（最后一列是右端项）。
 * 奇异 / 数值上退化时返回 null —— 五个点共线时就会走到这里。
 */
private fun solveLinearSystem(m: Array<DoubleArray>): DoubleArray? {
    val n = m.size
    val a = Array(n) { m[it].copyOf() }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) {
            if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
        }
        if (abs(a[pivot][col]) < 1e-12) return null
        if (pivot != col) {
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
        }
        val d = a[col][col]
        for (c in col..n) a[col][c] /= d
        for (r in 0 until n) {
            if (r == col) continue
            val f = a[r][col]
            if (abs(f) < 1e-18) continue
            for (c in col..n) a[r][c] -= f * a[col][c]
        }
    }
    return DoubleArray(n) { a[it][n] }
}

/** 六个系数 → `A*x^2+B*x*y+...=0`（系数按最大值缩放；全是 0 时返回 null）。 */
private fun conicText(coef: DoubleArray): String? {
    if (coef.any { !it.isFinite() }) return null
    val scale = coef.maxOf { abs(it) }
    if (!scale.isFinite() || scale < 1e-12) return null
    val c = DoubleArray(6) { coef[it] / scale }
    val terms = ArrayList<String>(6)
    fun term(v: Double, body: String) {
        if (abs(v) < 1e-9) return
        if (terms.isEmpty()) {
            terms += if (v < 0) "-${fmtNum(abs(v))}*$body" else "${fmtNum(v)}*$body"
        } else {
            terms += if (v < 0) "-${fmtNum(abs(v))}*$body" else "+${fmtNum(v)}*$body"
        }
    }
    term(c[0], "x^2")
    term(c[1], "x*y")
    term(c[2], "y^2")
    term(c[3], "x")
    term(c[4], "y")
    term(c[5], "1")
    if (terms.isEmpty()) return null
    return terms.joinToString("") + "=0"
}
