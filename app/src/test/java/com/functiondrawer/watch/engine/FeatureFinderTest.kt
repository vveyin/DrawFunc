package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * FeatureFinder 的单元测试。
 *
 * 数值方法天生带误差，所以断言分两类：
 *  * 结构性的（个数、isMax、是否为 null）—— 必须精确；
 *  * 数值性的（x/y 的值）—— 用相对误差 1e-6，二分收敛到 1e-12 后这个容差绰绰有余。
 */
class FeatureFinderTest {

    private fun f(src: String): Expression = Expression.compile(src)

    /** 相对误差 < 1e-6 的近似相等（期望值为 0 时退化成绝对误差 1e-6）。 */
    private fun assertClose(message: String, expected: Double, actual: Double) {
        val tol = 1e-6 * max(1.0, abs(expected))
        assertTrue("$message：期望 $expected，实际 $actual（容差 $tol）", abs(expected - actual) <= tol)
    }

    /** 通用不变量：所有返回的特征都必须是有限的、落在给定范围内的。 */
    private fun assertWellFormed(r: CurveFeatures, xMin: Double, xMax: Double) {
        for (x in r.roots) {
            assertTrue("零点 $x 超出范围", x.isFinite() && x >= xMin && x <= xMax)
        }
        for (e in r.extrema) {
            assertTrue("极值点 ${e.x} 超出范围", e.x.isFinite() && e.x >= xMin && e.x <= xMax)
            assertTrue("极值 y 不是有限值：${e.y}", e.y.isFinite())
        }
    }

    // ------------------------------------------------------------ 零点 + 顶点

    @Test
    fun quadratic_roots_and_vertex() {
        val r = FeatureFinder.analyze(f("x^2-4"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        assertEquals("x^2-4 在 [-10,10] 上恰好两个零点", 2, r.roots.size)
        assertClose("第一个零点", -2.0, r.roots[0])
        assertClose("第二个零点", 2.0, r.roots[1])

        assertEquals("x^2-4 恰好一个极值点", 1, r.extrema.size)
        val e = r.extrema[0]
        assertClose("顶点 x", 0.0, e.x)
        assertClose("顶点 y", -4.0, e.y)
        assertFalse("x^2-4 的顶点是最小值", e.isMax)
        assertFalse("x^2-4 处处连续，不该被标记不连续", r.hasDiscontinuity)
    }

    @Test
    fun sin_roots_cover_three_periods_each_side() {
        // 默认 limit = 5 只会保留 x 最小的 5 个根，所以这里放宽 limit 来验证完整的一组
        val r = FeatureFinder.analyze(f("sin(x)"), -10.0, 10.0, limit = 20)
        assertWellFormed(r, -10.0, 10.0)

        val expected = listOf(-3 * PI, -2 * PI, -PI, 0.0, PI, 2 * PI, 3 * PI)
        for (k in expected) {
            assertTrue(
                "sin(x) 在 [-10,10] 上应有零点 $k，实际 ${r.roots}",
                r.roots.any { abs(it - k) < 1e-6 }
            )
        }
        assertEquals("[-10,10] 上只有这 7 个零点", expected.size, r.roots.size)

        // 默认 limit = 5 时保留 x 最小的 5 个：-3π, -2π, -π, 0, π —— 关键的 ±π 与 0 都在
        val d = FeatureFinder.analyze(f("sin(x)"), -10.0, 10.0)
        assertEquals("默认 limit 下根被截断到 5 个", 5, d.roots.size)
        for (k in listOf(-PI, 0.0, PI)) {
            assertTrue("默认 limit 下也应有零点 $k，实际 ${d.roots}", d.roots.any { abs(it - k) < 1e-6 })
        }
        assertFalse("sin(x) 是处处连续的，不该被标记不连续", d.hasDiscontinuity)
    }

    @Test
    fun sin_extrema() {
        val r = FeatureFinder.analyze(f("sin(x)"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        // 默认 limit=5 保留 x 最小的 5 个极值点：-7.85, -4.71, -1.57, 1.57, 4.71
        val top = r.extrema.firstOrNull { abs(it.x - PI / 2) < 1e-6 }
        assertNotNull("应有极大值点 x = π/2，实际 ${r.extrema}", top)
        assertTrue("π/2 处是极大值", top!!.isMax)
        assertClose("sin(π/2)", 1.0, top.y)

        val bottom = r.extrema.firstOrNull { abs(it.x + PI / 2) < 1e-6 }
        assertNotNull("应有极小值点 x = -π/2，实际 ${r.extrema}", bottom)
        assertFalse("-π/2 处是极小值", bottom!!.isMax)
        assertClose("sin(-π/2)", -1.0, bottom.y)
    }

    @Test
    fun cos_has_maximum_at_zero() {
        val r = FeatureFinder.analyze(f("cos(x)"), -6.0, 6.0)
        assertWellFormed(r, -6.0, 6.0)

        val e = r.extrema.firstOrNull { abs(it.x) < 1e-6 }
        assertNotNull("cos 在 0 处应有极值点，实际 ${r.extrema}", e)
        assertTrue("cos 在 0 处是极大值", e!!.isMax)
        assertClose("cos(0)", 1.0, e.y)
    }

    // ------------------------------------------------------------ 拐点 ≠ 极值点

    @Test
    fun cubic_has_root_but_no_extremum() {
        val r = FeatureFinder.analyze(f("x^3"), -5.0, 5.0)
        assertWellFormed(r, -5.0, 5.0)

        assertTrue("x^3 应有零点 0，实际 ${r.roots}", r.roots.any { abs(it) < 1e-6 })
        // x^3 的导数 3x^2 在 0 处不变号，0 是拐点不是极值点
        assertEquals("x^3 没有极值点，实际 ${r.extrema}", 0, r.extrema.size)
    }

    // ------------------------------------------------------------ 不连续 / 渐近线

    @Test
    fun reciprocal_has_no_fake_roots() {
        val r = FeatureFinder.analyze(f("1/x"), -5.0, 5.0)
        assertWellFormed(r, -5.0, 5.0)

        assertEquals("1/x 在 [-5,5] 上没有零点，实际 ${r.roots}", 0, r.roots.size)
        assertTrue("1/x 在 0 处无定义，应标记不连续", r.hasDiscontinuity)
        assertNull("1/x 的 y 截距应为 null（0 处无定义）", r.yIntercept)
    }

    @Test
    fun tan_has_root_at_zero_and_no_roots_at_poles() {
        val r = FeatureFinder.analyze(f("tan(x)"), -3.0, 3.0)
        assertWellFormed(r, -3.0, 3.0)

        assertTrue("tan 应有零点 0，实际 ${r.roots}", r.roots.any { abs(it) < 1e-6 })

        // 最容易出错的地方：±π/2 是渐近线，两侧变号，绝不能被当成零点
        val nearPole = r.roots.filter { abs(abs(it) - PI / 2) < 0.05 }
        assertTrue("±π/2 附近不应有零点，实际 $nearPole（全部零点：${r.roots}）", nearPole.isEmpty())
        // [-3,3] 内除了 0 没有别的零点（±π 都在范围外）
        assertEquals("[-3,3] 上只有 0 一个零点，实际 ${r.roots}", 1, r.roots.size)
    }

    // ------------------------------------------------------------ 边界情况

    @Test
    fun constant_function_has_no_features() {
        val r = FeatureFinder.analyze(f("3"), -5.0, 5.0)
        assertTrue("常量函数没有零点", r.roots.isEmpty())
        assertTrue("常量函数没有极值点", r.extrema.isEmpty())
        assertNotNull("常量函数有 y 截距", r.yIntercept)
        assertEquals("y 截距等于 3", 3.0, r.yIntercept!!, 0.0)
    }

    @Test
    fun sqrt_does_not_report_roots_on_undefined_half() {
        val r = FeatureFinder.analyze(f("sqrt(x)"), -5.0, 5.0)
        assertWellFormed(r, -5.0, 5.0)

        val negative = r.roots.filter { it < -1e-6 }
        assertTrue("sqrt(x) 在负半轴无定义，不应报零点，实际 $negative", negative.isEmpty())
    }

    @Test
    fun invalid_range_returns_empty_features() {
        val reversed = FeatureFinder.analyze(f("x^2-4"), 5.0, -5.0)
        assertTrue("xMin > xMax 时零点为空", reversed.roots.isEmpty())
        assertTrue("xMin > xMax 时极值为空", reversed.extrema.isEmpty())
        assertNull("xMin > xMax 时 y 截距为 null", reversed.yIntercept)

        val degenerate = FeatureFinder.analyze(f("x^2-4"), 5.0, 5.0)
        assertTrue("xMin == xMax 时零点为空", degenerate.roots.isEmpty())
        assertTrue("xMin == xMax 时极值为空", degenerate.extrema.isEmpty())

        // 顺带的健壮性：NaN / 无穷范围也不能抛异常
        assertTrue(FeatureFinder.analyze(f("sin(x)"), Double.NaN, 1.0).roots.isEmpty())
        assertTrue(
            FeatureFinder.analyze(
                f("sin(x)"), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
            ).roots.isEmpty()
        )
    }

    @Test
    fun limit_is_respected() {
        val r = FeatureFinder.analyze(f("sin(x)"), -100.0, 100.0, limit = 5)
        assertWellFormed(r, -100.0, 100.0)
        assertTrue("roots 应被截断到 limit，实际 ${r.roots.size}", r.roots.size <= 5)
        assertTrue("extrema 应被截断到 limit，实际 ${r.extrema.size}", r.extrema.size <= 5)
        // 截断保留的是 x 最小的一批
        assertEquals("保留的是 x 最小的根", r.roots.sorted(), r.roots)

        val zeroLimit = FeatureFinder.analyze(f("sin(x)"), -100.0, 100.0, limit = 0)
        assertTrue("limit = 0 时为零点为空", zeroLimit.roots.isEmpty())
        assertTrue("limit = 0 时为极值为空", zeroLimit.extrema.isEmpty())
    }

    @Test
    fun y_intercept() {
        val a = FeatureFinder.analyze(f("x^2-4"), -10.0, 10.0)
        assertNotNull("x^2-4 的 y 截距", a.yIntercept)
        assertClose("x^2-4 的 y 截距应为 -4", -4.0, a.yIntercept!!)

        assertNull("1/x 的 y 截距应为 null", FeatureFinder.analyze(f("1/x"), -5.0, 5.0).yIntercept)
        assertNull("0 不在范围内时 y 截距为 null", FeatureFinder.analyze(f("x^2-4"), 1.0, 10.0).yIntercept)
    }

    // ------------------------------------------------------------ 极端输入不崩

    @Test
    fun extreme_ranges_and_wild_oscillation_are_safe() {
        val huge = FeatureFinder.analyze(f("x^2-4"), -1e9, 1e9)
        assertTrue("超大范围下 roots 不超过 limit", huge.roots.size <= 5)
        assertTrue("超大范围下 extrema 不超过 limit", huge.extrema.size <= 5)

        val tiny = FeatureFinder.analyze(f("sin(x)"), -1e-12, 1e-12)
        assertTrue("极小范围下 roots 不超过 limit", tiny.roots.size <= 5)
        assertWellFormed(tiny, -1e-12, 1e-12)

        // 采样网格分辨不了的剧烈振荡：只要求不抛异常、不超限
        val wild = FeatureFinder.analyze(f("sin(1000x)"), -5.0, 5.0)
        assertTrue("剧烈振荡下 roots 不超过 limit", wild.roots.size <= 5)
        assertTrue("剧烈振荡下 extrema 不超过 limit", wild.extrema.size <= 5)

        // 处处 NaN 的函数
        val nan = FeatureFinder.analyze(f("sqrt(0-1) + x"), -5.0, 5.0)
        assertTrue("处处无定义时零点为空", nan.roots.isEmpty())
        assertTrue("处处无定义时极值为空", nan.extrema.isEmpty())
    }

    // ------------------------------------------------------------ 相切零点（偶数重根）

    /**
     * 用户报的 bug：手表上给 `y = x^2` 打开信息卡显示"零点：无"。
     *
     * x^2 在 0 处是二重根（曲线**贴着** x 轴不穿过去），所以"相邻采样变号 + 二分"
     * 这种算法天生看不见它。现在由"|f| 谷底 + 相切判据"这条路负责。
     */
    @Test
    fun x_squared_has_tangent_root_at_zero() {
        val r = FeatureFinder.analyze(f("x^2"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        assertEquals("x^2 去重后只有一个零点，实际 ${r.roots}", 1, r.roots.size)
        assertTrue("x^2 的零点应该在 0 附近，实际 ${r.roots[0]}", abs(r.roots[0]) < 1e-9)

        // 相切零点同时是极值点：两者都要在（这正是 GeoGebra 的行为）
        val e = r.extrema.firstOrNull { abs(it.x) < 1e-9 }
        assertNotNull("x^2 的顶点 (0,0) 应该在，实际 ${r.extrema}", e)
        assertFalse("x^2 的顶点是极小值", e!!.isMax)
        assertTrue("顶点 y 应该是 0，实际 ${e.y}", abs(e.y) < 1e-12)
        assertFalse("x^2 处处连续", r.hasDiscontinuity)
    }

    /**
     * 上面那条 bug 的**回归测试**：把视口平移 0.003，使 x = 0 不再落在采样网格上。
     *
     * 这是第一版真正翻车的场景 —— x=0 不是采样点时，网格上最小的 |f| 是 (step/2)^2 ≈ 6e-6，
     * 远大于当时的绝对阈值 1e-9，于是"零点：无"。
     */
    @Test
    fun x_squared_root_survives_shifted_grid() {
        val r = FeatureFinder.analyze(f("x^2"), -10.003, 9.997)
        assertWellFormed(r, -10.003, 9.997)

        assertTrue("网格错开时 x^2 仍然必须有零点，实际 ${r.roots}", r.roots.isNotEmpty())
        assertEquals("去重后仍然只有一个零点，实际 ${r.roots}", 1, r.roots.size)
        assertTrue(
            "零点应被压到机器精度（要求 < 1e-6），实际 ${r.roots[0]}",
            abs(r.roots[0]) < 1e-6
        )
    }

    @Test
    fun double_root_and_simple_root_of_cubic() {
        // (x-1)^2 (x+2)：x=1 是二重根（相切）、x=-2 是单根（穿过）。
        // 去重后的 roots 是"不同的 x"，所以这里期望两个条目；
        // 按重数算是三个根 —— 关键是 x=1 这个二重根**必须**在。
        val r = FeatureFinder.analyze(f("(x-1)^2(x+2)"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        assertEquals("去重后两个不同的根，实际 ${r.roots}", 2, r.roots.size)
        assertTrue("单根 -2 应在，实际 ${r.roots}", abs(r.roots[0] + 2.0) < 1e-9)
        assertTrue("二重根 1 应在，实际 ${r.roots}", abs(r.roots[1] - 1.0) < 1e-9)

        val e = r.extrema.firstOrNull { abs(it.x - 1.0) < 1e-6 }
        assertNotNull("二重根处同时是极小值，实际 ${r.extrema}", e)
        assertFalse("x=1 处是极小值", e!!.isMax)
    }

    /** sin 的根间距 π ≈ 3.14，是"精度够不够"最直观的硬指标：这里要求 1e-12。 */
    @Test
    fun sin_roots_are_accurate_to_machine_precision() {
        val r = FeatureFinder.analyze(f("sin(x)"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        val expected = listOf(-3 * PI, -2 * PI, -PI, 0.0, PI)
        assertEquals("默认 limit 下保留 x 最小的 5 个根，实际 ${r.roots}", 5, r.roots.size)
        for ((k, want) in expected.withIndex()) {
            assertTrue(
                "第 ${k + 1} 个零点应≈ $want，实际 ${r.roots[k]}（误差 ${abs(r.roots[k] - want)}）",
                abs(r.roots[k] - want) < 1e-12
            )
        }
    }

    /** sin^2 在 [-4,4] 上有三个相切零点（±π 与 0），全都是偶数重根。 */
    @Test
    fun sin_squared_tangent_roots() {
        val r = FeatureFinder.analyze(f("sin(x)^2"), -4.0, 4.0)
        assertWellFormed(r, -4.0, 4.0)

        assertEquals("sin^2 在 [-4,4] 上三个相切零点，实际 ${r.roots}", 3, r.roots.size)
        for ((k, want) in listOf(-PI, 0.0, PI).withIndex()) {
            assertTrue(
                "第 ${k + 1} 个零点应≈ $want，实际 ${r.roots[k]}",
                abs(r.roots[k] - want) < 1e-9
            )
        }
    }

    // ------------------------------------------------------------ 渐近线不许报假特征

    @Test
    fun tan_has_no_fake_roots_at_poles() {
        val r = FeatureFinder.analyze(f("tan(x)"), -4.0, 4.0)
        assertWellFormed(r, -4.0, 4.0)

        val nearPole = r.roots.filter { abs(abs(it) - PI / 2) < 0.05 }
        assertTrue("±π/2 附近绝不能有零点，实际 $nearPole（全部：${r.roots}）", nearPole.isEmpty())
        // 每个报出来的根都真的让 tan 落在 0 上（±π 处 tan 的浮点值是 ~1e-16）
        for (x in r.roots) {
            assertTrue("报出的零点 $x 处 tan = ${tan(x)}，不是根", abs(tan(x)) < 1e-9)
        }
        assertTrue("tan 在 ±π/2 无定义，应标记不连续", r.hasDiscontinuity)
    }

    @Test
    fun reciprocal_pole_is_not_a_root_or_extremum() {
        val r = FeatureFinder.analyze(f("1/x"), -2.0, 2.0)
        assertWellFormed(r, -2.0, 2.0)

        assertTrue("1/x 没有零点，实际 ${r.roots}", r.roots.isEmpty())
        assertTrue("1/x 没有极值点（0 处是极点），实际 ${r.extrema}", r.extrema.isEmpty())
        assertTrue("1/x 在 0 处无定义，应标记不连续", r.hasDiscontinuity)
    }

    // ------------------------------------------------------------ 不许把"贴着但没碰到"报成零点

    @Test
    fun functions_that_never_reach_zero_have_no_roots() {
        // (x-0.5)^2 + 0.01：谷底离 0 有 0.01
        // x^2 + 1e-9：谷底离 0 只有 1e-9，但仍然是正的 —— 相对判据必须挡住它
        // x^2 + 1、常数 2：显而易见
        for (src in listOf("(x-0.5)^2+0.01", "x^2+1e-9", "x^2+1", "2")) {
            val r = FeatureFinder.analyze(f(src), -10.0, 10.0)
            assertWellFormed(r, -10.0, 10.0)
            assertTrue("$src 不应该有任何零点，实际 ${r.roots}", r.roots.isEmpty())
        }
    }

    /**
     * 整体尺度很小的函数：`1e-12·x^2`。
     *
     * 第一版用绝对阈值 1e-9 判零，于是 `|f| <= 1e-9 ⟺ |x| <= 31.6`，
     * 在 [-10,10] 上**整条曲线**都算 0 → 报出 5 个散布全区的假零点。
     * 现在判据全部相对函数自身量级，只有一个真零点。
     */
    @Test
    fun tiny_scaled_function_reports_exactly_one_root() {
        val r = FeatureFinder.analyze(f("1e-12*x^2"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        assertEquals("1e-12·x^2 只有一个零点，实际 ${r.roots}", 1, r.roots.size)
        assertTrue("零点在 0 附近，实际 ${r.roots[0]}", abs(r.roots[0]) < 1e-6)
    }

    // ------------------------------------------------------------ 三次函数的根与极值

    @Test
    fun cubic_roots_and_extrema() {
        val r = FeatureFinder.analyze(f("x^3-3x"), -10.0, 10.0)
        assertWellFormed(r, -10.0, 10.0)

        val s3 = sqrt(3.0)
        assertEquals("x^3-3x 三个零点，实际 ${r.roots}", 3, r.roots.size)
        assertTrue("-√3 应在，实际 ${r.roots[0]}", abs(r.roots[0] + s3) < 1e-9)
        assertTrue("0 应在，实际 ${r.roots[1]}", abs(r.roots[1]) < 1e-9)
        assertTrue("√3 应在，实际 ${r.roots[2]}", abs(r.roots[2] - s3) < 1e-9)

        assertEquals("x^3-3x 两个极值点，实际 ${r.extrema}", 2, r.extrema.size)
        val lo = r.extrema[0]
        assertTrue("极小/极大 x = -1，实际 ${lo.x}", abs(lo.x + 1.0) < 1e-9)
        assertTrue("x=-1 处是极大值（y=2），实际 y=${lo.y}", lo.isMax && abs(lo.y - 2.0) < 1e-9)
        val hi = r.extrema[1]
        assertTrue("极值 x = 1，实际 ${hi.x}", abs(hi.x - 1.0) < 1e-9)
        assertFalse("x=1 处是极小值", hi.isMax)
        assertTrue("x=1 处 y=-2，实际 y=${hi.y}", abs(hi.y + 2.0) < 1e-9)
    }

    // ------------------------------------------------------------ 去重阈值

    /**
     * `x^2 - 9e-10` 的两个根相距只有 6e-5。
     *
     * 第一版的去重阈值是"范围的 1e-6"：范围 60 时等于 6e-5，正好会把这一对真实根
     * 并成一个。现在的阈值与可达精度相称（相对 1e-9），这一对必须原样保留。
     *
     * 注意这里的可分辨性还依赖"采样网格里有点落进 x²-9e-10 的负谷"（本用例里 0 是网格点）：
     * 谷底比采样步长还窄、又没有任何采样点落进去时，只能靠 |f| 谷底路径找到其中一个 —— 见报告里
     * 的"剩余不确定项"。这一条测的是**去重阈值**，不是网格分辨率。
     */
    @Test
    fun two_roots_six_microunits_apart_are_not_merged() {
        val r = FeatureFinder.analyze(f("x^2-9e-10"), -30.0, 30.0)
        assertWellFormed(r, -30.0, 30.0)

        assertEquals("±3e-5 是两个不同的根，实际 ${r.roots}", 2, r.roots.size)
        assertTrue("负根应≈ -3e-5，实际 ${r.roots[0]}", abs(r.roots[0] + 3e-5) < 1e-9)
        assertTrue("正根应≈ 3e-5，实际 ${r.roots[1]}", abs(r.roots[1] - 3e-5) < 1e-9)
    }

    // ------------------------------------------------------------ 退化输入

    @Test
    fun degenerate_inputs_return_empty_without_throwing() {
        val g = f("x^2")

        for (pair in listOf(
            Double.NaN to 1.0,
            -1.0 to Double.NaN,
            Double.NEGATIVE_INFINITY to 1.0,
            -1.0 to Double.POSITIVE_INFINITY,
            5.0 to -5.0,
            1.0 to 1.0
        )) {
            val r = FeatureFinder.analyze(g, pair.first, pair.second)
            assertTrue("范围 (${pair.first}, ${pair.second}) 下零点应为空，实际 ${r.roots}", r.roots.isEmpty())
            assertTrue("范围 (${pair.first}, ${pair.second}) 下极值应为空", r.extrema.isEmpty())
            assertNull("非法范围下 y 截距应为 null", r.yIntercept)
        }

        val zeroLimit = FeatureFinder.analyze(g, -1.0, 1.0, limit = 0)
        assertTrue("limit = 0 时零点为空", zeroLimit.roots.isEmpty())
        assertTrue("limit = 0 时极值为空", zeroLimit.extrema.isEmpty())

        val negativeLimit = FeatureFinder.analyze(g, -1.0, 1.0, limit = -3)
        assertTrue("负 limit 时零点为空", negativeLimit.roots.isEmpty())
        assertTrue("负 limit 时极值为空", negativeLimit.extrema.isEmpty())
    }
}
