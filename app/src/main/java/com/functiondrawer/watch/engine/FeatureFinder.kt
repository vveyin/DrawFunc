package com.functiondrawer.watch.engine

import kotlin.math.abs
import kotlin.math.max

/** 一个极值点。 */
data class Extremum(val x: Double, val y: Double, val isMax: Boolean)

/** 某条曲线在给定 x 范围内的可展示特征。 */
data class CurveFeatures(
    val roots: List<Double>,          // 零点的 x 坐标，已去重、已排序
    val extrema: List<Extremum>,      // 极值点，已排序
    val yIntercept: Double?,          // f(0)，仅当 0 落在范围内且有限
    val hasDiscontinuity: Boolean     // 范围内是否检测到不连续/无定义（用于 UI 提示"仅显示部分特征"）
)

/**
 * 从显函数里挖出可以直接展示给用户的特征：零点、极值点（顶点）、y 截距。
 *
 * 设计取向是"稳"而不是"准到极限"：
 *  * 纯采样 + 二分，没有任何迭代求解器，**每一次求值次数都有硬上界**，
 *    所以再疯的函数也不会卡死（最坏是漏掉特征，而不是算不完）；
 *  * 全程不抛异常：任何非法输入（NaN 范围、xMin >= xMax、处处无定义……）都返回空结果；
 *  * 主动识别"跨不连续处"的假特征 —— 这是这类算法最容易翻车的地方：
 *    `tan(x)` 在渐近线两侧变号、`1/x` 在 0 附近导数变号，都会被误当成零点/极值点。
 *
 * 假特征的判别思路见 [analyze] 里的三段扫描。
 */
object FeatureFinder {

    // ---------------------------------------------------------------- 调参常量

    /** 采样区间数（采样点 = SAMPLES + 1）。4000 段在手表上是毫秒级，够分辨范围 1/4000 的特征。 */
    private const val SAMPLES = 4000

    /**
     * 数值一阶导的步长 `h = (xMax - xMin) / DERIV_STEP_DIV`，即 h ≈ 可见范围的 1e-6。
     *
     * 为什么要在这个量级（两头都是坑，必须权衡）：
     *  * h 太大 —— `(f(x+h) - f(x-h)) / 2h` 是 h 尺度上的平均斜率，极小值被"抹平"，
     *    而且差商误差是 O(h²)，驻点会整体偏移；
     *  * h 太小 —— 两个几乎相等的数相减，相对误差约 `eps * |f| / (2h)`；
     *    h 掉到 1e-15 * |x| 附近时差商就完全是浮点噪声，会报出一堆假的极值点。
     *
     * 取范围的 1e-6，对常见可见范围（1~100）h 落在 1e-6~1e-4：
     * 比采样间距（范围/4000）小两到三个数量级，能把极值点从采样网格里"分"出来；
     * 又远大于浮点分辨率，噪声量级约 `1e-16 * |f| / h ≈ 1e-10 * |f|`，相对函数自身量级可忽略。
     * 注意驻点的**最终精度不靠 h**，而是靠后面在差商上做二分（收敛到 1e-12）。
     */
    private const val DERIV_STEP_DIV = 1e6

    /** 二分迭代次数上限（足够把区间压到 1e-12 以下，同时保证循环一定结束）。 */
    private const val BISECT_ITERS = 60

    /** 二分提前退出条件：区间已经窄到这个宽度就没必要再切了。 */
    private const val BISECT_MIN_WIDTH = 1e-12

    /** 函数值绝对值小于它就当成 0（避免 sin 在零点附近报出一串重复根）。 */
    private const val ZERO_EPS = 1e-9

    /** 相邻样本 |f| 的直接阈值：超过就认为区间里"炸了"。 */
    private const val HUGE = 1e12

    /** 相邻样本 |f| 的比值阈值：跳变这么夸张只能是渐近线/间断。 */
    private const val RATIO_LIMIT = 1e6

    /** 二分收敛后，|f| 要小于 `该系数 × 函数量的中位数` 才算真根（否则是渐近线）。 */
    private const val ROOT_ACCEPT_FACTOR = 1e-6

    /** 极值点处 |f| 超过 `该系数 × 参考量级` 就认为是发散（极点），不是极值。 */
    private const val DIVERGE_FACTOR = 1e6

    /** 差商被当成 0 的相对阈值（相对于差商自身的中位量级）。 */
    private const val DERIV_ZERO_FACTOR = 1e-9

    /** 一次分析里每类特征最多做多少次二分（防止病态函数把时间吃光；正常函数远达不到）。 */
    private const val MAX_BISECTIONS = 512

    /** 差商符号变化允许跨越的最大采样段数，跨得太多说明中间有古怪，宁可放弃。 */
    private const val MAX_DERIV_SPAN = 64

    // ---------------------------------------------------------------- 主入口

    /**
     * 求 [xMin, xMax] 上 [f] 的特征。
     *
     * @param xMin,xMax 可见的 x 范围（世界坐标）
     * @param limit     每类特征最多返回多少个（默认 5，防止 UI 塞不下）
     */
    fun analyze(f: Expression, xMin: Double, xMax: Double, limit: Int = 5): CurveFeatures {
        val empty = CurveFeatures(emptyList(), emptyList(), null, false)

        // 多变量（隐函数上下文）没法按单个 x 描述特征
        if (f.variables.size > 1) return empty
        // 非法范围：安静的返回空结果
        if (!xMin.isFinite() || !xMax.isFinite() || xMin >= xMax) return empty

        val range = xMax - xMin
        val step = range / SAMPLES
        // 范围大到溢出（range = Inf）或小到采样点全部重合（step = 0）时都没法分析
        if (!range.isFinite() || !step.isFinite() || step <= 0.0) return empty

        val arg = DoubleArray(if (f.variables.isEmpty()) 1 else f.variables.size)
        val yInt = yInterceptAt(f, xMin, xMax, arg)

        val n = SAMPLES
        val xs = DoubleArray(n + 1)
        val ys = DoubleArray(n + 1)
        for (i in 0..n) {
            val x = if (i == n) xMax else xMin + step * i
            xs[i] = x
            ys[i] = evalAt(f, x, arg)
        }

        var hasDisc = false

        // ---------- 第 1 段：标记"不可信区间" ----------
        // bad[i] 表示第 i 段（xs[i] ~ xs[i+1]）跨过了不连续/无定义，
        // 后面的零点、极值扫描都必须绕开它，否则 tan(x) 会在渐近线附近报一堆假特征。
        val bad = BooleanArray(n)
        for (i in 0 until n) {
            val a = ys[i]
            val b = ys[i + 1]
            if (!a.isFinite() || !b.isFinite()) {
                // 端点无定义/溢出：这一段跨过了间断
                bad[i] = true
                hasDisc = true
                continue
            }
            val aa = abs(a)
            val bb = abs(b)
            if (aa > HUGE || bb > HUGE) {
                bad[i] = true
                hasDisc = true
                continue
            }
            val lo = if (aa < bb) aa else bb
            val hi = if (aa < bb) bb else aa
            if (lo > 0.0 && hi / lo > RATIO_LIMIT) {
                // 相邻样本跳了 6 个数量级以上：例如 1/x 在 0 附近从 -4e2 跳到 +1e16
                bad[i] = true
                hasDisc = true
            }
        }

        // 函数值的代表性量级（中位数），用来做"这个值算不算接近 0"的相对判断
        val scale = representativeScale(ys)
        // 二分收敛后允许的残差：相对阈值，兼顾 f 整体很大（如 1e9*(x-1)）的情况
        val rootTol = max(ZERO_EPS, ROOT_ACCEPT_FACTOR * scale)
        // 去重阈值：范围内的 1e-6
        val dedupe = range / 1e6

        // ---------- 第 2 段：零点 ----------
        val rootHits = ArrayList<Double>()

        // 2a. 采样点本身就是 0 的（|f| <= ZERO_EPS）
        for (i in 0..n) {
            val y = ys[i]
            if (!y.isFinite() || abs(y) > ZERO_EPS) continue
            // 两侧都不可信的点不要（典型是 sqrt(x) 在 0 处的边界处理已经由 2b 覆盖）
            val leftBad = i > 0 && bad[i - 1]
            val rightBad = i < n && bad[i]
            if (leftBad && rightBad) continue
            rootHits.add(xs[i])
        }

        // 2b. 相邻样本变号 → 二分求根。端点符号含"0"（欠零）时不做，避免重复和噪声。
        var bisections = 0
        for (i in 0 until n) {
            if (bad[i] || bisections >= MAX_BISECTIONS) continue
            val a = ys[i]
            val b = ys[i + 1]
            val sa = sampleSign(a)
            val sb = sampleSign(b)
            if (sa == 0 || sb == 0 || sa == sb) continue
            bisections++
            val x0 = bisectSignChange({ x -> evalAt(f, x, arg) }, xs[i], xs[i + 1], a)
            val y0 = evalAt(f, x0, arg)
            if (y0.isFinite() && abs(y0) <= rootTol) {
                rootHits.add(x0)
            } else {
                // 二分收敛到了"函数值不趋近于 0"的地方 —— 这不是根，是渐近线。
                // 例：tan(x) 在 π/2 两侧变号；二分把区间压到极点后 |f| 反而越来越大。
                hasDisc = true
                markBad(bad, i, n)
            }
        }

        val roots = dedupeSorted(rootHits, dedupe, limit)

        // ---------- 第 3 段：极值点 ----------
        val extHits = ArrayList<Extremum>()
        val h = range / DERIV_STEP_DIV
        if (h > 0.0 && h.isFinite()) {
            val d = DoubleArray(n + 1)
            for (i in 0..n) d[i] = derivAt(f, xs[i], h, arg)
            val dScale = representativeScale(d)
            val dZero = DERIV_ZERO_FACTOR * dScale

            // 只跟踪"确定的符号"：差商为 0（常数函数整条线、x^3 的拐点……）既不算正也不算负，
            // 所以符号变化必须发生在两个 nonzero 样本之间 —— 这样拐点不会被当成极值点。
            var lastIdx = -1
            var lastSign = 0
            var extBisections = 0
            for (j in 0..n) {
                val dj = d[j]
                if (!dj.isFinite()) {
                    lastIdx = -1
                    lastSign = 0
                    continue
                }
                val s = if (dj > dZero) 1 else if (dj < -dZero) -1 else 0
                if (s == 0) continue

                if (lastIdx >= 0 && s != lastSign && extBisections < MAX_BISECTIONS) {
                    val span = j - lastIdx
                    if (span in 1..MAX_DERIV_SPAN && !spansBad(bad, lastIdx, j)) {
                        extBisections++
                        val isMax = lastSign > 0        // 差商由正变负 → 极大
                        val x0 = bisectSignChange(
                            { x -> derivAt(f, x, h, arg) },
                            xs[lastIdx], xs[j], d[lastIdx]
                        )
                        val y0 = evalAt(f, x0, arg)
                        val ref = max(scale, max(abs(ys[lastIdx]), abs(ys[j])))
                        val divergent = !y0.isFinite() || abs(y0) > HUGE ||
                            abs(y0) > DIVERGE_FACTOR * max(ref, ZERO_EPS)
                        if (divergent) {
                            // 差商在这里变号，但 f 本身在发散 —— 是极点（如 1/x^2 的 0 处）
                            hasDisc = true
                            markBad(bad, lastIdx, n)
                            markBad(bad, j - 1, n)
                        } else if (isLocalExtremum(f, x0, y0, h, arg, isMax)) {
                            extHits.add(Extremum(x0, y0, isMax))
                        }
                    }
                }
                lastIdx = j
                lastSign = s
            }
        }

        val extrema = dedupeExtrema(extHits, dedupe, limit)

        return CurveFeatures(roots, extrema, yInt, hasDisc)
    }

    // ---------------------------------------------------------------- 求值辅助

    private fun evalAt(f: Expression, x: Double, arg: DoubleArray): Double {
        arg[0] = x
        return f.evalArray(arg)
    }

    /** 中心差商近似 f'(x)；h 相对 x 太小（浮点分辨不出来）时返回 NaN。 */
    private fun derivAt(f: Expression, x: Double, h: Double, arg: DoubleArray): Double {
        val xr = x + h
        val xl = x - h
        val dx = xr - xl
        if (dx <= 0.0 || !dx.isFinite()) return Double.NaN
        val yr = evalAt(f, xr, arg)
        val yl = evalAt(f, xl, arg)
        if (!yr.isFinite() || !yl.isFinite()) return Double.NaN
        return (yr - yl) / dx
    }

    /** 采样点的符号；|y| <= ZERO_EPS 视为 0。 */
    private fun sampleSign(y: Double): Int = when {
        !y.isFinite() -> 0
        y > ZERO_EPS -> 1
        y < -ZERO_EPS -> -1
        else -> 0
    }

    /** 有根区间上的二分。要求两端严格异号（由调用方保证）。 */
    private fun bisectSignChange(g: (Double) -> Double, aIn: Double, bIn: Double, yaIn: Double): Double {
        var a = aIn
        var b = bIn
        var ya = yaIn
        for (k in 0 until BISECT_ITERS) {
            if (b - a < BISECT_MIN_WIDTH) break
            val m = 0.5 * (a + b)
            if (m <= a || m >= b) break          // 浮点已经贴到一起了
            val ym = g(m)
            if (!ym.isFinite()) break
            if (ym == 0.0) return m
            if ((ym > 0.0) == (ya > 0.0)) {
                a = m
                ya = ym
            } else {
                b = m
            }
        }
        return 0.5 * (a + b)
    }

    /** 驻点处再做一次"确实比邻居大/小"的校验，容忍浮点噪声（1e-6 相对）。 */
    private fun isLocalExtremum(
        f: Expression, x0: Double, y0: Double, h: Double, arg: DoubleArray, isMax: Boolean
    ): Boolean {
        val yl = evalAt(f, x0 - h, arg)
        val yr = evalAt(f, x0 + h, arg)
        if (!yl.isFinite() || !yr.isFinite()) return false
        val eps = 1e-6 * max(max(abs(y0), max(abs(yl), abs(yr))), 1.0)
        return if (isMax) y0 >= yl - eps && y0 >= yr - eps else y0 <= yl + eps && y0 <= yr + eps
    }

    // ---------------------------------------------------------------- 小工具

    /** 有限值的 |y| 中位数；没有可用值或中位数为 0 时退回最大值，再不行给 1。 */
    private fun representativeScale(values: DoubleArray): Double {
        var count = 0
        var mx = 0.0
        for (v in values) {
            if (!v.isFinite()) continue
            val a = abs(v)
            if (a > mx) mx = a
            count++
        }
        if (count == 0) return 1.0
        val sorted = DoubleArray(count)
        var k = 0
        for (v in values) if (v.isFinite()) sorted[k++] = abs(v)
        sorted.sort()
        val med = sorted[count / 2]
        return when {
            med.isFinite() && med > 0.0 -> med
            mx.isFinite() && mx > 0.0 -> mx
            else -> 1.0
        }
    }

    /** 把第 i 段及其左右各一段标记为不可信（间断处两侧的差商同样是垃圾）。 */
    private fun markBad(bad: BooleanArray, i: Int, n: Int) {
        for (k in (i - 1)..(i + 1)) {
            if (k in 0 until n) bad[k] = true
        }
    }

    /** 第 [from, to) 段里有没有不可信的。 */
    private fun spansBad(bad: BooleanArray, from: Int, to: Int): Boolean {
        for (k in from until to) {
            if (k < 0 || k >= bad.size) return true
            if (bad[k]) return true
        }
        return false
    }

    /** 排序 + 按 |Δx| < tol 去重 + 截断。 */
    private fun dedupeSorted(values: List<Double>, tol: Double, limit: Int): List<Double> {
        val maxKeep = if (limit < 0) 0 else limit
        if (maxKeep == 0 || values.isEmpty()) return emptyList()
        val sorted = values.filter { it.isFinite() }.sorted()
        val out = ArrayList<Double>(minOf(sorted.size, maxKeep))
        for (v in sorted) {
            if (out.isEmpty() || v - out[out.size - 1] >= tol) {
                out.add(v)
                if (out.size >= maxKeep) break
            }
        }
        return out
    }

    private fun dedupeExtrema(values: List<Extremum>, tol: Double, limit: Int): List<Extremum> {
        val maxKeep = if (limit < 0) 0 else limit
        if (maxKeep == 0 || values.isEmpty()) return emptyList()
        val sorted = values.filter { it.x.isFinite() && it.y.isFinite() }.sortedBy { it.x }
        val out = ArrayList<Extremum>(minOf(sorted.size, maxKeep))
        for (e in sorted) {
            if (out.isEmpty() || e.x - out[out.size - 1].x >= tol) {
                out.add(e)
                if (out.size >= maxKeep) break
            }
        }
        return out
    }

    /** f(0)，仅当 0 落在 [xMin, xMax] 内且值有限。 */
    private fun yInterceptAt(f: Expression, xMin: Double, xMax: Double, arg: DoubleArray): Double? {
        if (xMin > 0.0 || xMax < 0.0) return null
        val v = evalAt(f, 0.0, arg)
        return if (v.isFinite()) v else null
    }
}
