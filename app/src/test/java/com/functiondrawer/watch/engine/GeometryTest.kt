package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 几何工具（[Geometry.kt] 里的图元 / 求交 / 变换 / 度量 / 吸附）的单元测试。
 *
 * 全部是纯 JVM 测试，跑 `tools\run-engine-tests.ps1` 即可，不需要表也不需要 Gradle。
 *
 * 断言的取向：**能用解析值就用解析值**（中垂线上到两端等距、切线斜率等于 2、
 * 对称点是 (0,3) 这种），而不是把实现算出来的数抄一遍 —— 抄下来的期望值
 * 只能证明"代码没变"，证明不了"代码是对的"。
 */
class GeometryTest {

    // ---------------------------------------------------------------- 测试脚手架

    /**
     * 默认视口：200×200 像素、世界范围 ±1，于是 `unitsPerPixel = 0.01`，
     * 正好是「1 世界单位 = 100 像素」，手算期望值很方便：
     * `screenX = 100x + 100`、`screenY = 100 - 100y`。
     */
    private fun vp(size: Float = 200f, half: Double = 1.0): Viewport =
        Viewport(size, size, 0.0, 0.0, half * 2.0 / size)

    /** 缩放到「1 世界单位 = 10 像素」的视口（用来验证像素阈值随缩放的变化）。 */
    private fun vpZoomedOut(): Viewport = vp(half = 10.0)

    /** 按当前视口把世界坐标折线打包成引擎里的 [Curve]（像素、x0,y0,x1,y1…）。 */
    private fun curveOf(v: Viewport, vararg pts: GPoint): Curve {
        val a = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            a[i * 2] = v.toScreenX(pts[i].x)
            a[i * 2 + 1] = v.toScreenY(pts[i].y)
        }
        return Curve(1L, 0, a)
    }

    /** 直连折线版的 `y = x^2`（[from, to] 上等分 [steps] 段），用来代表"画出来的函数"。 */
    private fun parabola(v: Viewport, from: Double = -1.0, to: Double = 1.0, steps: Int = 20): Curve {
        val a = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val x = from + (to - from) * i / steps
            a[i * 2] = v.toScreenX(x)
            a[i * 2 + 1] = v.toScreenY(x * x)
        }
        return Curve(1L, 0, a)
    }

    private fun assertPoint(expX: Double, expY: Double, act: GPoint, delta: Double, msg: String = "") {
        assertEquals("$msg x 坐标不对：$act", expX, act.x, delta)
        assertEquals("$msg y 坐标不对：$act", expY, act.y, delta)
    }

    // ---------------------------------------------------------------- 1. 基本图元

    @Test
    fun `图元构造与参数化`() {
        val a = GPoint(1.0, 2.0)
        val b = GPoint(4.0, 6.0)

        assertEquals(GLineKind.LINE, lineOf(a, b).kind)
        assertEquals(GLineKind.SEGMENT, segmentOf(a, b).kind)
        assertEquals(GLineKind.RAY, rayOf(a, b).kind)

        val s = segmentOf(a, b)
        assertEquals(5.0, s.length, 1e-12)              // 3-4-5
        assertEquals(3.0, s.dx, 1e-12)
        assertEquals(4.0, s.dy, 1e-12)
        assertPoint(1.0, 2.0, s.pointAt(0.0), 1e-12)
        assertPoint(4.0, 6.0, s.pointAt(1.0), 1e-12)
        assertPoint(2.5, 4.0, s.pointAt(0.5), 1e-12)
        assertPoint(7.0, 10.0, s.pointAt(2.0), 1e-12)   // 延长线上，不裁剪

        // 定义域：线段 [0,1]、射线 [0,∞)、直线 (-∞,∞)
        assertTrue(TRange.UNIT.contains(0.0))
        assertTrue(TRange.UNIT.contains(1.0))
        assertFalse(TRange.UNIT.contains(1.5))
        assertFalse(TRange.UNIT.contains(-0.5))
        assertTrue(TRange.HALF_LINE.contains(1000.0))
        assertFalse(TRange.HALF_LINE.contains(-1.0))
        assertTrue(TRange.FULL.contains(1e300))
        assertTrue(TRange.FULL.contains(-1e300))

        assertEquals(TRange.UNIT, s.tRange)
        assertEquals(TRange.HALF_LINE, rayOf(a, b).tRange)
        assertEquals(TRange.FULL, lineOf(a, b).tRange)
    }

    @Test
    fun `零长度图元的退化标记`() {
        val p = GPoint(1.0, 1.0)
        assertFalse(lineOf(p, GPoint(2.0, 2.0)).isDegenerate)
        assertTrue(lineOf(p, p).isDegenerate)
        assertTrue(segmentOf(p, p).isDegenerate)
        // 非有限坐标也算退化（否则后面会一路算出 NaN）
        assertTrue(lineOf(GPoint(Double.NaN, 0.0), p).isDegenerate)
        assertTrue(lineOf(GPoint(Double.POSITIVE_INFINITY, 0.0), p).isDegenerate)
    }

    @Test
    fun `像素容差换算成世界容差`() {
        val v = vp()                       // 1 像素 = 0.01 世界单位
        assertEquals(0.03, worldEpsForPixels(v, 3.0), 1e-12)
        assertEquals(0.3, worldEpsForPixels(vpZoomedOut(), 3.0), 1e-12)
        // 视口非法时宁严不宽
        assertEquals(0.0, worldEpsForPixels(Viewport(200f, 200f, 0.0, 0.0, 0.0), 3.0), 1e-12)
    }

    // ---------------------------------------------------------------- 2. 交点与判定

    @Test
    fun `两直线相交于一点`() {
        val yx = lineOf(GPoint(-1.0, -1.0), GPoint(1.0, 1.0))          // y = x
        val yNegXPlus2 = lineOf(GPoint(-1.0, 3.0), GPoint(3.0, -1.0))  // y = -x + 2

        val p = intersect(yx, yNegXPlus2)
        assertNotNull("y=x 与 y=-x+2 应该交于 (1,1)", p)
        assertPoint(1.0, 1.0, p!!, 1e-12)

        // 交点与锚点选取无关（y=-x+2 换一组点）
        val q = intersect(yx, lineOf(GPoint(0.0, 2.0), GPoint(4.0, -2.0)))
        assertNotNull(q)
        assertPoint(1.0, 1.0, q!!, 1e-12)

        // 垂直线 × 水平线
        val cross = intersect(
            lineOf(GPoint(2.0, -5.0), GPoint(2.0, 5.0)),
            lineOf(GPoint(-5.0, 3.0), GPoint(5.0, 3.0))
        )
        assertNotNull(cross)
        assertPoint(2.0, 3.0, cross!!, 1e-12)
    }

    @Test
    fun `平行与重合的直线没有交点`() {
        val yx = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        assertNull("平行：y=x 与 y=x+1", intersect(yx, lineOf(GPoint(0.0, 1.0), GPoint(1.0, 2.0))))
        assertNull("重合有无数交点，返回 null", intersect(yx, lineOf(GPoint(2.0, 2.0), GPoint(3.0, 3.0))))
        assertNull("自身与自身重合", intersect(yx, yx))
        // 大坐标下的近似平行：用**相对**容差才判得住，绝对容差会漏
        assertNull(
            intersect(
                lineOf(GPoint(0.0, 0.0), GPoint(1e6, 1e6)),
                lineOf(GPoint(0.0, 1.0), GPoint(1e6, 1e6 + 1.0))
            )
        )
        // 零长度线段：方向不存在
        val dot = lineOf(GPoint(1.0, 1.0), GPoint(1.0, 1.0))
        assertNull("退化图元不参与求交", intersect(dot, yx))
        assertNull(intersect(yx, dot))
    }

    @Test
    fun `线段交点的定义域裁剪`() {
        val segA = segmentOf(GPoint(0.0, 0.0), GPoint(3.0, 0.0))
        val vertical = lineOf(GPoint(1.0, -1.0), GPoint(1.0, 1.0))

        val hit = intersect(segA, vertical)
        assertNotNull("线段 0~3 与 x=1 应该相交", hit)
        assertPoint(1.0, 0.0, hit!!, 1e-12)

        // 同一条支撑直线，交点在 x=4 —— 超出线段定义域
        assertNull(
            "交点在 x=4，超出线段 0~3",
            intersect(segA, lineOf(GPoint(4.0, -1.0), GPoint(4.0, 1.0)))
        )
        // 两条线段各裁各的
        assertNotNull(
            intersect(segA, segmentOf(GPoint(1.0, -1.0), GPoint(1.0, 1.0)))
        )
        assertNull(
            "两条线段不相交（各自的支撑直线交于 x=4）",
            intersect(segA, segmentOf(GPoint(4.0, -1.0), GPoint(4.0, 1.0)))
        )
        // 端点命中：t=1 与 u=0，浮点误差不该把它判掉
        val corner = intersect(
            segmentOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0)),
            segmentOf(GPoint(1.0, 1.0), GPoint(2.0, 0.0))
        )
        assertNotNull("两条线段共用端点 (1,1)，应该算交点", corner)
        assertPoint(1.0, 1.0, corner!!, 1e-9)
    }

    @Test
    fun `射线交点的定义域裁剪`() {
        val verticalX2 = lineOf(GPoint(2.0, -1.0), GPoint(2.0, 1.0))

        // 朝 +x 的射线能打到 x=2
        val forward = intersect(rayOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0)), verticalX2)
        assertNotNull(forward)
        assertPoint(2.0, 0.0, forward!!, 1e-12)

        // 同一条支撑直线、方向反过来：t = -2，打不到
        assertNull(
            "朝 -x 的射线打不到 x=2",
            intersect(rayOf(GPoint(0.0, 0.0), GPoint(-1.0, 0.0)), verticalX2)
        )
        // 反过来用"直线"就没有方向限制
        val asLine = intersect(lineOf(GPoint(0.0, 0.0), GPoint(-1.0, 0.0)), verticalX2)
        assertNotNull(asLine)
        assertPoint(2.0, 0.0, asLine!!, 1e-12)

        // 射线起点恰好就是交点（t = 0）
        val atOrigin = intersect(
            rayOf(GPoint(1.0, 0.0), GPoint(3.0, 0.0)),
            lineOf(GPoint(1.0, -2.0), GPoint(1.0, 2.0))
        )
        assertNotNull("射线起点算在定义域内", atOrigin)
        assertPoint(1.0, 0.0, atOrigin!!, 1e-12)

        // 射线 × 线段
        val rayDown = intersect(
            rayOf(GPoint(0.0, 5.0), GPoint(0.0, 1.0)),
            segmentOf(GPoint(-1.0, 0.0), GPoint(1.0, 0.0))
        )
        assertNotNull(rayDown)
        assertPoint(0.0, 0.0, rayDown!!, 1e-12)
    }

    @Test
    fun `contains 判定尊重图元定义域`() {
        val a = GPoint(0.0, 0.0)
        val b = GPoint(2.0, 2.0)

        assertTrue("直线延伸到 (5,5)", contains(lineOf(a, b), GPoint(5.0, 5.0)))
        assertFalse("线段只到 (2,2)", contains(segmentOf(a, b), GPoint(5.0, 5.0)))
        assertTrue(contains(segmentOf(a, b), GPoint(1.0, 1.0)))
        assertTrue(contains(segmentOf(a, b), GPoint(0.0, 0.0)))
        assertTrue(contains(segmentOf(a, b), GPoint(2.0, 2.0)))
        assertTrue("射线朝 (2,2) 一侧延伸", contains(rayOf(a, b), GPoint(5.0, 5.0)))
        assertFalse("射线的反侧不算", contains(rayOf(a, b), GPoint(-1.0, -1.0)))

        // 容差是世界单位：偏 1e-6 的点，eps 小就判否、eps 大就判是
        val near = GPoint(1.0, 1.0 + 1e-6)
        assertFalse(contains(lineOf(a, b), near, 1e-9))
        assertTrue(contains(lineOf(a, b), near, 1e-5))
        // 用像素换算过来的容差（3 像素 = 0.03 世界单位）
        assertTrue(contains(lineOf(a, b), near, worldEpsForPixels(vp(), 3.0)))

        assertFalse("退化图元不包含任何点", contains(lineOf(a, a), a))
        assertFalse("非有限坐标直接判否", contains(lineOf(a, b), GPoint(Double.NaN, 0.0)))
    }

    @Test
    fun `点到直线的距离`() {
        val yx = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        // (1,-1) 到 y=x 的距离 = |1·1 - (-1)·1| / √2 = 2/√2
        assertEquals(2.0 / sqrt(2.0), distanceToLine(GPoint(1.0, -1.0), yx), 1e-12)
        assertEquals(0.0, distanceToLine(GPoint(3.0, 3.0), yx), 1e-12)
        assertEquals(5.0 / sqrt(2.0), distanceToLine(GPoint(0.0, 5.0), yx), 1e-12)

        // 垂直线 × 水平线
        assertEquals(3.0, distanceToLine(GPoint(-3.0, 7.0), lineOf(GPoint(0.0, 0.0), GPoint(0.0, 1.0))), 1e-12)
        assertEquals(2.0, distanceToLine(GPoint(9.0, 2.0), lineOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0))), 1e-12)

        // 退化直线按"一个点"处理：返回点到锚点的距离，不是 NaN/∞
        val dot = segmentOf(GPoint(1.0, 1.0), GPoint(1.0, 1.0))
        assertEquals(1.0, distanceToLine(GPoint(1.0, 2.0), dot), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, distanceToLine(GPoint(Double.NaN, 0.0), yx), 0.0)
    }

    @Test
    fun `点到线段的最短距离`() {
        val a = GPoint(0.0, 0.0)
        val b = GPoint(1.0, 0.0)

        assertEquals("投影落在段内", 3.0, distanceToSegment(GPoint(0.5, 3.0), a, b), 1e-12)
        assertEquals("投影落在左端外 → 量到端点 a", 1.0, distanceToSegment(GPoint(-1.0, 0.0), a, b), 1e-12)
        assertEquals("投影落在右端外 → 量到端点 b", 1.0, distanceToSegment(GPoint(2.0, 0.0), a, b), 1e-12)
        assertEquals("斜着量到端点 a", hypot(1.0, 1.0), distanceToSegment(GPoint(-1.0, 1.0), a, b), 1e-12)
        assertEquals("端点上", 0.0, distanceToSegment(GPoint(1.0, 0.0), a, b), 1e-12)

        // 零长度线段 = 一个点
        assertEquals(1.0, distanceToSegment(GPoint(1.0, 2.0), GPoint(1.0, 1.0), GPoint(1.0, 1.0)), 1e-12)
        assertEquals(
            Double.POSITIVE_INFINITY,
            distanceToSegment(GPoint(Double.NaN, 0.0), a, b),
            0.0
        )
    }

    @Test
    fun `distanceToShape 尊重线段与射线的定义域`() {
        val a = GPoint(0.0, 0.0)
        val b = GPoint(1.0, 0.0)
        val probe = GPoint(5.0, 0.0)

        assertEquals("直线：probe 就在支撑直线上", 0.0, distanceToShape(probe, lineOf(a, b)), 1e-12)
        assertEquals("线段：夹到端点 b", 4.0, distanceToShape(probe, segmentOf(a, b)), 1e-12)
        assertEquals("射线朝 +x：probe 还在射线上", 0.0, distanceToShape(probe, rayOf(a, b)), 1e-12)
        assertEquals(
            "射线朝 -x：probe 在反侧，量到起点",
            5.0,
            distanceToShape(probe, rayOf(GPoint(0.0, 0.0), GPoint(-1.0, 0.0))),
            1e-12
        )
        // 退化图元
        assertEquals(5.0, distanceToShape(probe, rayOf(a, a)), 1e-12)
        assertEquals(5.0, distanceToShape(probe, segmentOf(a, a)), 1e-12)
    }

    // ---------------------------------------------------------------- 3. 折线求交

    @Test
    fun `折线交点落在公用顶点上只算一次`() {
        val v = vp()
        // A：水平折线，顶点正好在原点；B：斜折线，顶点也在原点
        val a = curveOf(v, GPoint(-1.0, 0.0), GPoint(0.0, 0.0), GPoint(1.0, 0.0))
        val b = curveOf(v, GPoint(-1.0, -1.0), GPoint(0.0, 0.0), GPoint(1.0, 1.0))

        val hits = intersections(a, b, v)
        assertEquals("相邻线段各算一次，去重后只该剩 1 个：$hits", 1, hits.size)
        assertPoint(0.0, 0.0, hits[0], 1e-9)
    }

    @Test
    fun `折线交点去重容差是屏幕像素`() {
        val v = vp()   // 1 世界单位 = 100 像素
        val horizontal = curveOf(v, GPoint(-1.0, 0.0), GPoint(1.0, 0.0))
        // V 形折线：两段各自穿过 y=0，两个交点相距 0.01 世界单位 = 1 像素
        val vee = curveOf(v, GPoint(-0.03, -1.0), GPoint(-0.02, 1.0), GPoint(-0.01, -1.0))

        val loose = intersectionsInScreen(horizontal, vee, mergeEpsPx = 0.5)
        assertEquals("相距 1 像素 > 0.5 像素，两个交点都要留下：$loose", 2, loose.size)
        assertEquals(97.5, loose[0].x, 1e-9)
        assertEquals(98.5, loose[1].x, 1e-9)

        val merged = intersectionsInScreen(horizontal, vee, mergeEpsPx = 2.0)
        assertEquals("容差 2 像素 > 1 像素间距，合并成一个：$merged", 1, merged.size)
        assertEquals("只留先算出来的那个", 97.5, merged[0].x, 1e-9)
    }

    @Test
    fun `折线的两个交点都要给出`() {
        val v = vp()
        val invertedV = curveOf(v, GPoint(-1.0, 0.0), GPoint(0.0, 0.5), GPoint(1.0, 0.0))
        val horizontal = curveOf(v, GPoint(-1.0, 0.25), GPoint(1.0, 0.25))

        val hits = intersections(invertedV, horizontal, v).sortedBy { it.x }
        assertEquals("应该交出两个点：$hits", 2, hits.size)
        assertPoint(-0.5, 0.25, hits[0], 1e-9)
        assertPoint(0.5, 0.25, hits[1], 1e-9)
    }

    @Test
    fun `点数不足的折线求交返回空表`() {
        val v = vp()
        val line = curveOf(v, GPoint(-1.0, 0.0), GPoint(1.0, 0.0))
        val single = Curve(2L, 0, floatArrayOf(100f, 100f))
        val empty = Curve(3L, 0, FloatArray(0))

        assertTrue(intersections(single, line, v).isEmpty())
        assertTrue(intersections(empty, line, v).isEmpty())
        assertTrue(intersections(line, single, v).isEmpty())
        assertTrue(intersectionsInScreen(empty, empty).isEmpty())

        // 含 NaN 的折线：坏点跳过，好段照常算
        val withNaN = Curve(4L, 0, floatArrayOf(0f, 100f, Float.NaN, Float.NaN, 200f, 100f))
        val vertical = curveOf(v, GPoint(0.0, -1.0), GPoint(0.0, 1.0))
        assertTrue("NaN 段不参与计算，也不抛异常", intersections(withNaN, vertical, v).isEmpty())
    }

    @Test
    fun `曲线与直线的交点`() {
        val v = vp()
        val para = parabola(v)   // y = x^2，步长 0.1
        val yHalf = lineOf(GPoint(-1.0, 0.5), GPoint(1.0, 0.5))

        val hits = intersections(para, yHalf, v).sortedBy { it.x }
        assertEquals("y=x^2 与 y=0.5 应该交两次：$hits", 2, hits.size)
        // 解析解是 ±√0.5 = ±0.7071；折线是弦逼近，允许 0.01 的误差
        assertEquals(-sqrt(0.5), hits[0].x, 0.01)
        assertEquals(0.5, hits[0].y, 1e-9)
        assertEquals(sqrt(0.5), hits[1].x, 0.01)
        assertEquals("交点必须在曲线上", hits[1].x * hits[1].x, hits[1].y, 0.01)
    }

    @Test
    fun `曲线与线段射线的交点受定义域限制`() {
        val v = vp()
        val para = parabola(v)   // 值域 [0,1]，定义域 [-1,1]

        val fullLine = lineOf(GPoint(-1.0, 0.5), GPoint(1.0, 0.5))
        assertEquals(2, intersections(para, fullLine, v).size)

        // 同样在 y=0.5 上，但只在 x∈[-0.2,0.2] 里 —— 抛物线那段还不到 0.5 高
        val shortSeg = segmentOf(GPoint(-0.2, 0.5), GPoint(0.2, 0.5))
        assertTrue(
            "线段没伸到交点处，应该一个都不给：${intersections(para, shortSeg, v)}",
            intersections(para, shortSeg, v).isEmpty()
        )

        // x=0 这条竖直线：抛物线只在原点碰到它
        val downRay = rayOf(GPoint(0.0, 1.0), GPoint(0.0, 0.0))
        val upRay = rayOf(GPoint(0.0, 1.0), GPoint(0.0, 2.0))
        val hitDown = intersections(para, downRay, v)
        assertEquals("向下射的射线打到原点：$hitDown", 1, hitDown.size)
        assertPoint(0.0, 0.0, hitDown[0], 1e-9)
        assertTrue("向上射的射线从 y=1 起，抛物线够不着", intersections(para, upRay, v).isEmpty())
    }

    @Test
    fun `退化直线与曲线求交返回空表`() {
        val v = vp()
        val para = parabola(v)
        val dot = segmentOf(GPoint(0.0, 0.0), GPoint(0.0, 0.0))

        assertTrue(intersections(para, dot, v).isEmpty())
        assertTrue(intersections(para, lineOf(GPoint(Double.NaN, 0.0), GPoint(1.0, 1.0)), v).isEmpty())
        assertTrue(intersections(para, lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0)), v).isNotEmpty())
    }

    // ---------------------------------------------------------------- 4. 变换与作图

    @Test
    fun `中点`() {
        assertPoint(2.0, 1.0, midpoint(GPoint(0.0, 0.0), GPoint(4.0, 2.0)), 1e-12)
        assertPoint(0.0, 0.0, midpoint(GPoint(-1.0, -1.0), GPoint(1.0, 1.0)), 1e-12)
        // 重合点：中点就是它自己，不是 NaN
        assertPoint(1.0, 1.0, midpoint(GPoint(1.0, 1.0), GPoint(1.0, 1.0)), 1e-12)
    }

    @Test
    fun `过一点作垂线`() {
        val base = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0))   // x 轴
        val perp = perpendicular(base, GPoint(2.0, 3.0))

        // 方向点积恒为 0 —— 垂直是解析成立的，不靠容差
        assertEquals(0.0, perp.dx * base.dx + perp.dy * base.dy, 1e-12)
        assertTrue("垂线必须过给定点", contains(perp, GPoint(2.0, 3.0), 1e-12))
        assertTrue(contains(perp, GPoint(2.0, -9.0), 1e-12))
        assertFalse(contains(perp, GPoint(2.5, 3.0), 1e-9))

        // 垂线与原直线的交点就是垂足
        val foot = intersect(perp, base)
        assertNotNull(foot)
        assertPoint(2.0, 0.0, foot!!, 1e-12)
        assertPoint(2.0, 0.0, projectOnto(GPoint(2.0, 3.0), base), 1e-12)

        // 斜线也要对：过 (1,1) 垂直于 y=x 的垂线就是 y = -x + 2
        val perp2 = perpendicular(lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0)), GPoint(1.0, 1.0))
        assertTrue(contains(perp2, GPoint(3.0, -1.0), 1e-12))

        // 退化输入：返回退化直线，坐标里没有 NaN
        val degenerate = perpendicular(lineOf(GPoint(5.0, 5.0), GPoint(5.0, 5.0)), GPoint(1.0, 2.0))
        assertTrue(degenerate.isDegenerate)
        assertPoint(1.0, 2.0, degenerate.p, 1e-12)
    }

    @Test
    fun `中垂线上的点到两端等距`() {
        val a = GPoint(0.0, 0.0)
        val b = GPoint(4.0, 0.0)
        val bisector = perpendicularBisector(a, b)

        // a-b 是水平线段 → 中垂线是 x = 2
        assertTrue("过中点", contains(bisector, midpoint(a, b), 1e-12))
        assertTrue("x = 2", contains(bisector, GPoint(2.0, 123.0), 1e-12))
        assertEquals(0.0, bisector.dx, 1e-12)

        for (t in doubleArrayOf(0.0, 1.0, -7.5, 3.25)) {
            val p = bisector.pointAt(t)
            assertEquals("中垂线上任意点到两端等距 (t=$t)", distance(p, a), distance(p, b), 1e-12)
        }
        // 具体数值：t=2 时点是 (2,8)，到两端的距离都是 √68
        val p = bisector.pointAt(2.0)
        assertPoint(2.0, 8.0, p, 1e-12)
        assertEquals(sqrt(68.0), distance(p, a), 1e-12)

        // 斜线段
        val sk = perpendicularBisector(GPoint(0.0, 0.0), GPoint(2.0, 2.0))
        val q = sk.pointAt(1.0)
        assertPoint(-1.0, 3.0, q, 1e-12)
        assertEquals(distance(q, GPoint(0.0, 0.0)), distance(q, GPoint(2.0, 2.0)), 1e-12)

        // 重合点：方向不存在 → 退化直线，但中点是它自己
        val deg = perpendicularBisector(GPoint(1.0, 1.0), GPoint(1.0, 1.0))
        assertTrue(deg.isDegenerate)
        assertPoint(1.0, 1.0, deg.p, 1e-12)
    }

    @Test
    fun `过一点作平行线`() {
        val base = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        val p = GPoint(0.0, 5.0)
        val par = parallel(base, p)

        assertTrue("平行线过给定点", contains(par, p, 1e-12))
        assertTrue(contains(par, GPoint(3.0, 8.0), 1e-12))
        assertTrue(contains(par, GPoint(-2.0, 3.0), 1e-12))
        assertNull("平行线没有交点", intersect(par, base))

        // 方向相同 ⇒ 叉积为 0
        assertEquals(0.0, par.dx * base.dy - par.dy * base.dx, 1e-12)
        // 两线间距 = p 到 base 的垂距 = 5/√2
        assertEquals(5.0 / sqrt(2.0), distanceToLine(p, base), 1e-12)

        // 垂直方向的直线照样能平移
        val vline = lineOf(GPoint(1.0, 0.0), GPoint(1.0, 1.0))
        assertTrue(contains(parallel(vline, GPoint(7.0, 0.0)), GPoint(7.0, -3.0), 1e-12))

        // 退化输入
        val deg = parallel(lineOf(GPoint(2.0, 2.0), GPoint(2.0, 2.0)), GPoint(0.0, 0.0))
        assertTrue(deg.isDegenerate)
        assertPoint(0.0, 0.0, deg.p, 1e-12)
    }

    @Test
    fun `角平分线`() {
        val o = GPoint(0.0, 0.0)

        // 90°：∠(1,0)-(0,0)-(0,1) 的平分线方向是 (1,1)/√2
        val b90 = angleBisector(GPoint(1.0, 0.0), o, GPoint(0.0, 1.0))
        assertNotNull(b90)
        assertPoint(1.0 / sqrt(2.0), 1.0 / sqrt(2.0), b90!!.pointAt(1.0), 1e-12)
        assertEquals(45.0, angleAt(o, GPoint(1.0, 0.0), b90.pointAt(1.0)), 1e-12)
        assertEquals(45.0, angleAt(o, b90.pointAt(1.0), GPoint(0.0, 1.0)), 1e-12)

        // 60°：平分线方向正好是 30°
        val c60 = GPoint(cos(PI / 3.0), sin(PI / 3.0))
        val b60 = angleBisector(GPoint(1.0, 0.0), o, c60)
        assertNotNull(b60)
        assertPoint(cos(PI / 6.0), sin(PI / 6.0), b60!!.pointAt(1.0), 1e-12)
        assertEquals(30.0, angleAt(o, GPoint(1.0, 0.0), b60.pointAt(1.0)), 1e-12)

        // 边长不等也要平分：一条边拉到 7、另一条缩到 0.25，平分线仍然是 45°
        // （这就是"先把方向归一化再相加"的理由 —— 直接拿两条边相加会歪）
        val bScaled = angleBisector(GPoint(7.0, 0.0), o, GPoint(0.0, 0.25))
        assertNotNull(bScaled)
        assertPoint(1.0 / sqrt(2.0), 1.0 / sqrt(2.0), bScaled!!.pointAt(1.0), 1e-12)
        assertEquals(
            "平分线两侧的角必须相等",
            angleAt(o, GPoint(7.0, 0.0), bScaled.pointAt(1.0)),
            angleAt(o, bScaled.pointAt(1.0), GPoint(0.0, 0.25)),
            1e-12
        )

        // 0° 角（三点共线、a 与 c 同侧）：平分线就是那条直线
        val b0 = angleBisector(GPoint(1.0, 0.0), o, GPoint(2.0, 0.0))
        assertNotNull(b0)
        assertTrue(contains(b0!!, GPoint(5.0, 0.0), 1e-12))

        // 180° 角：内角平分线方向退化，返回垂直于该方向的直线
        val b180 = angleBisector(GPoint(-1.0, 0.0), o, GPoint(1.0, 0.0))
        assertNotNull(b180)
        assertEquals("180° 时取垂线", 0.0, b180!!.dx, 1e-12)
        assertTrue(contains(b180, GPoint(0.0, 9.0), 1e-12))

        // 顶点与某一个点重合：没有角 → null
        assertNull(angleBisector(o, o, GPoint(1.0, 1.0)))
        assertNull(angleBisector(GPoint(1.0, 1.0), o, o))
        assertNull("非有限坐标 → null", angleBisector(GPoint(Double.NaN, 0.0), o, GPoint(1.0, 1.0)))
    }

    @Test
    fun `数值导数`() {
        val cube = { x: Double -> x * x * x }
        val d = numericDerivative(cube, 2.0)
        assertNotNull(d)
        assertEquals("3x² 在 x=2 处是 12", 12.0, d!!, 1e-6)

        // 步长非法 / 函数值非法 → null，不是 0 也不是 NaN
        assertNull("h = 0 会除出 NaN", numericDerivative(cube, 2.0, 0.0))
        assertNull(numericDerivative(cube, 2.0, -1e-5))
        assertNull(numericDerivative(cube, 2.0, Double.NaN))
        assertNull("处处无定义", numericDerivative({ Double.NaN }, 1.0))
        assertNull("√x 在 0 处左邻域无定义", numericDerivative({ sqrt(it) }, 0.0))
        assertNull(numericDerivative(cube, Double.NaN))
    }

    @Test
    fun `切线 y=x^2 在 x=1 处的斜率是 2`() {
        val f = { x: Double -> x * x }
        val t = tangentLine(f, 1.0)
        assertNotNull(t)
        assertTrue("切点坐标必须是有限的：$t", t!!.p.isFinite && t.q.isFinite)

        // 斜率 = 2（中心差分对 x² 精确）
        val slope = (t.q.y - t.p.y) / (t.q.x - t.p.x)
        assertEquals(2.0, slope, 1e-9)
        // 切线过切点 (1,1)，且 y = 2x - 1
        assertPoint(1.0, 1.0, t.p, 1e-12)
        assertTrue("切线过 (2,3)", contains(t, GPoint(2.0, 3.0), 1e-9))
        assertTrue("切线在 x=0 处是 -1", contains(t, GPoint(0.0, -1.0), 1e-9))
        assertFalse("(0,0) 不在切线上", contains(t, GPoint(0.0, 0.0), 1e-9))

        // 和引擎的表达式编译串起来用（DEG/RAD、参数 a b c d 都在编译期折进去了）
        val expr = Expression.compile("x^2", listOf("x"))
        val t2 = tangentLine({ x -> expr.eval(x) }, 1.0)
        assertNotNull(t2)
        assertEquals(2.0, (t2!!.q.y - t2.p.y) / (t2.q.x - t2.p.x), 1e-9)

        // 极值处切线水平
        val flat = tangentLine(f, 0.0)
        assertNotNull(flat)
        assertEquals(0.0, (flat!!.q.y - flat.p.y) / (flat.q.x - flat.p.x), 1e-9)
        assertTrue(contains(flat, GPoint(9.0, 0.0), 1e-9))

        // 常函数
        val const = tangentLine({ 2.0 }, 0.0)
        assertNotNull(const)
        assertTrue(contains(const!!, GPoint(5.0, 2.0), 1e-12))

        // 导数求不出来的情形一律 null（不许放 NaN 出去）
        assertNull("h = 0", tangentLine(f, 1.0, 0.0))
        assertNull("h < 0", tangentLine(f, 1.0, -1e-5))
        assertNull("√x 在 0 处左邻域无定义", tangentLine({ sqrt(it) }, 0.0))
        assertNull("切点不在定义域", tangentLine({ 1.0 / it }, 0.0))
        assertNull("x0 非有限", tangentLine(f, Double.NaN))
        assertNull("h 小到被浮点吃掉（x0+h == x0）", tangentLine(f, 1e9, 1e-12))
    }

    @Test
    fun `轴对称`() {
        // 关于 x 轴
        val xAxis = lineOf(GPoint(-1.0, 0.0), GPoint(1.0, 0.0))
        assertPoint(2.0, -3.0, reflect(GPoint(2.0, 3.0), xAxis), 1e-12)
        // 关于 y 轴
        val yAxis = lineOf(GPoint(0.0, -1.0), GPoint(0.0, 1.0))
        assertPoint(-2.0, 3.0, reflect(GPoint(2.0, 3.0), yAxis), 1e-12)
        // 关于 y = x
        val yx = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        assertPoint(0.0, 2.0, reflect(GPoint(2.0, 0.0), yx), 1e-12)
        // 关于一般直线 y = x + 1：垂足是 (1,2)，对称点是 (0,3)
        val general = lineOf(GPoint(0.0, 1.0), GPoint(1.0, 2.0))
        assertPoint(0.0, 3.0, reflect(GPoint(2.0, 1.0), general), 1e-12)
        // 直线上的点不动
        assertPoint(3.0, 4.0, reflect(GPoint(3.0, 4.0), general), 1e-12)
        // 对称两次回到原地
        val p = GPoint(1.7, -2.3)
        assertPoint(p.x, p.y, reflect(reflect(p, general), general), 1e-9)
        // 对称点与原点的连线垂直于轴，且中点在轴上
        val r = reflect(p, general)
        assertEquals(0.0, (r.x - p.x) * general.dx + (r.y - p.y) * general.dy, 1e-12)
        assertTrue(contains(general, midpoint(p, r), 1e-12))
    }

    @Test
    fun `中心对称与退化直线的行为`() {
        assertPoint(0.0, -1.0, reflectThrough(GPoint(2.0, 3.0), GPoint(1.0, 1.0)), 1e-12)
        assertPoint(-1.0, -2.0, reflectThrough(GPoint(1.0, 2.0), GPoint(0.0, 0.0)), 1e-12)
        assertPoint(1.0, 1.0, reflectThrough(GPoint(1.0, 1.0), GPoint(1.0, 1.0)), 1e-12)

        // 退化直线（零长度）按"点"处理：等价于关于锚点的中心对称，有明确结果而不是 NaN
        val dot = segmentOf(GPoint(1.0, 1.0), GPoint(1.0, 1.0))
        assertPoint(0.0, -1.0, reflect(GPoint(2.0, 3.0), dot), 1e-12)
    }

    // ---------------------------------------------------------------- 5. 度量

    @Test
    fun `两点距离`() {
        assertEquals(5.0, distance(GPoint(0.0, 0.0), GPoint(3.0, 4.0)), 1e-12)
        assertEquals(0.0, distance(GPoint(1.0, 1.0), GPoint(1.0, 1.0)), 1e-12)
        assertEquals(
            Double.POSITIVE_INFINITY,
            distance(GPoint(Double.NaN, 0.0), GPoint(0.0, 0.0)),
            0.0
        )
    }

    @Test
    fun `角度`() {
        val o = GPoint(0.0, 0.0)

        assertEquals("直角", 90.0, angleAt(o, GPoint(1.0, 0.0), GPoint(0.0, 1.0)), 1e-12)
        assertEquals("3-4-5 三角形的直角", 90.0, angleAt(GPoint(4.0, 0.0), o, GPoint(4.0, 3.0)), 1e-12)
        assertEquals("等边三角形内角", 60.0, angleAt(o, GPoint(1.0, 0.0), GPoint(0.5, sqrt(3.0) / 2.0)), 1e-9)
        assertEquals("45°", 45.0, angleAt(o, GPoint(1.0, 0.0), GPoint(1.0, 1.0)), 1e-12)
        assertEquals("平角", 180.0, angleAt(o, GPoint(-1.0, 0.0), GPoint(1.0, 0.0)), 1e-12)
        assertEquals("零角", 0.0, angleAt(o, GPoint(2.0, 0.0), GPoint(5.0, 0.0)), 1e-12)
        // 与顶点顺序无关：∠a-vertex-c == ∠c-vertex-a
        assertEquals(
            angleAt(o, GPoint(1.0, 0.0), GPoint(1.0, 2.0)),
            angleAt(o, GPoint(1.0, 2.0), GPoint(1.0, 0.0)),
            1e-12
        )
        // 角度不受边长影响
        assertEquals(
            angleAt(o, GPoint(1.0, 0.0), GPoint(1.0, 1.0)),
            angleAt(o, GPoint(1000.0, 0.0), GPoint(3.0, 3.0)),
            1e-12
        )
        // 退化：顶点与端点重合 → 0.0，不是 NaN
        assertEquals(0.0, angleAt(o, o, GPoint(1.0, 1.0)), 0.0)
        assertEquals(0.0, angleAt(o, GPoint(1.0, 0.0), o), 0.0)
        assertEquals(0.0, angleAt(o, GPoint(Double.NaN, 0.0), GPoint(1.0, 0.0)), 0.0)
    }

    @Test
    fun `多边形面积`() {
        val square = listOf(
            GPoint(0.0, 0.0), GPoint(1.0, 0.0), GPoint(1.0, 1.0), GPoint(0.0, 1.0)
        )
        assertEquals("单位正方形", 1.0, polygonArea(square), 1e-12)
        assertEquals("顶点顺序反过来取绝对值", 1.0, polygonArea(square.reversed()), 1e-12)
        assertEquals(
            "平移不改变面积",
            1.0,
            polygonArea(square.map { GPoint(it.x + 7.0, it.y - 3.0) }),
            1e-12
        )

        assertEquals(
            "直角三角形 4×3/2",
            6.0,
            polygonArea(listOf(GPoint(0.0, 0.0), GPoint(4.0, 0.0), GPoint(0.0, 3.0))),
            1e-12
        )
        // L 形：2×2 的方块挖掉 1×1
        assertEquals(
            3.0,
            polygonArea(
                listOf(
                    GPoint(0.0, 0.0), GPoint(2.0, 0.0), GPoint(2.0, 1.0),
                    GPoint(1.0, 1.0), GPoint(1.0, 2.0), GPoint(0.0, 2.0)
                )
            ),
            1e-12
        )
        // 点太少 → 0.0（不是异常）
        assertEquals(0.0, polygonArea(emptyList()), 0.0)
        assertEquals(0.0, polygonArea(listOf(GPoint(0.0, 0.0))), 0.0)
        assertEquals(0.0, polygonArea(listOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))), 0.0)
        assertEquals("三点共线没有面积", 0.0, polygonArea(listOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0), GPoint(2.0, 2.0))), 1e-12)
        assertEquals("坐标非有限 → 0.0，不是 NaN", 0.0, polygonArea(listOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0), GPoint(Double.NaN, 1.0))), 0.0)
    }

    // ---------------------------------------------------------------- 6. 吸附

    @Test
    fun `曲线上离屏幕点最近的点`() {
        val v = vp()
        val para = parabola(v)

        // 正好点在顶点上（屏幕坐标 (100,100)）→ 距离 0，世界坐标 (0,0)
        val onVertex = nearestOnCurve(para, 100f, 100f, v)
        assertNotNull(onVertex)
        assertPoint(0.0, 0.0, onVertex!!.first, 1e-6)
        assertEquals(0.0, onVertex.second, 1e-6)

        // 在 (1,1) 那个采样点上
        val onEnd = nearestOnCurve(para, v.toScreenX(1.0), v.toScreenY(1.0), v)
        assertNotNull(onEnd)
        assertPoint(1.0, 1.0, onEnd!!.first, 1e-6)
        assertTrue("落在折线顶点上，距离该是 0：${onEnd.second}", onEnd.second < 1e-6)

        // 顶点正上方 10 像素：最近点是折线上的某点，必然比"到顶点的 10 像素"更近
        val above = nearestOnCurve(para, 100f, 90f, v)
        assertNotNull(above)
        assertEquals("最近点必须在曲线上", above!!.first.x * above.first.x, above.first.y, 0.02)
        assertTrue("最近点比顶点更近：${above.second}", above.second < 10.0)
        assertEquals("折线弦逼近，误差在 0.1 像素量级", 9.95, above.second, 0.1)

        // 多远都给最近点（阈值是 snap 的事，这里不设限）
        val far = nearestOnCurve(para, 100f, 5000f, v)
        assertNotNull(far)
        assertTrue(far!!.second > 100.0)

        // 没有可用线段 → null
        assertNull(nearestOnCurve(Curve(2L, 0, FloatArray(0)), 100f, 100f, v))
        assertNull(nearestOnCurve(Curve(3L, 0, floatArrayOf(100f, 100f)), 100f, 100f, v))
        assertNull(
            "全是 NaN 的曲线",
            nearestOnCurve(Curve(4L, 0, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)), 100f, 100f, v)
        )
        assertNull("视口非法", nearestOnCurve(para, 100f, 100f, Viewport(200f, 200f, 0.0, 0.0, 0.0)))
    }

    @Test
    fun `吸附在阈值内命中阈值外不命中`() {
        val v = vp()   // 1 像素 = 0.01 世界单位
        val candidates = listOf(
            SnapCandidate(GPoint(0.0, 0.0), SnapKind.INTERSECTION),
            SnapCandidate(GPoint(1.0, 1.0), SnapKind.VERTEX)
        )

        // 精确命中（屏幕 (100,100) 就是原点）
        val exact = snap(candidates, v, 100f, 100f, 24.0)
        assertNotNull(exact)
        assertEquals(SnapKind.INTERSECTION, exact!!.kind)
        assertEquals(0.0, exact.screenDistance, 1e-9)
        assertPoint(0.0, 0.0, exact.point, 1e-12)

        // 偏 3 像素：阈值 24 → 命中，距离就是 3 像素
        val near = snap(candidates, v, 103f, 100f, 24.0)
        assertNotNull(near)
        assertEquals(3.0, near!!.screenDistance, 1e-9)
        assertPoint(0.0, 0.0, near.point, 1e-12)

        // 同一处手指位置，阈值收到 2 像素 → 不命中
        assertNull("阈值内才吸：3 像素 > 2 像素", snap(candidates, v, 103f, 100f, 2.0))

        // 恰好等于阈值算命中
        assertNotNull(snap(candidates, v, 103f, 100f, 3.0))

        // 阈值是屏幕像素，不是世界单位：**同一个世界偏移**，缩放不同 → 像素距离不同
        val offset = listOf(SnapCandidate(GPoint(0.03, 0.0), SnapKind.POINT))
        assertEquals("放大时 0.03 世界单位 = 3 像素", 3.0, screenDistance(v, 100.0, 100.0, GPoint(0.03, 0.0)), 1e-9)
        assertEquals("缩小时同一个偏移只占 0.3 像素", 0.3, screenDistance(vpZoomedOut(), 100.0, 100.0, GPoint(0.03, 0.0)), 1e-9)
        assertNull(
            "3 像素 > 2 像素阈值 → 不吸",
            snap(offset, v, 100f, 100f, 2.0)
        )
        assertNotNull(
            "同样的世界偏移在缩小后只占 0.3 像素 → 照样吸得上（这正是「用像素当阈值」的意义）",
            snap(offset, vpZoomedOut(), 100f, 100f, 2.0)
        )

        // 空候选 / 非法阈值 / 非有限候选点
        assertNull(snap(emptyList<SnapCandidate>(), v, 100f, 100f, 24.0))
        assertNull(snap(candidates, v, 100f, 100f, -1.0))
        assertNull(snap(candidates, v, 100f, 100f, Double.NaN))
        assertNull(
            snap(
                listOf(SnapCandidate(GPoint(Double.NaN, 0.0), SnapKind.ZERO), SnapCandidate(GPoint(50.0, 50.0), SnapKind.ZERO)),
                v, 100f, 100f, 24.0
            )
        )
    }

    @Test
    fun `吸附可以按来源筛选`() {
        val v = vp()
        val candidates = listOf(
            SnapCandidate(GPoint(0.0, 0.0), SnapKind.ZERO),
            SnapCandidate(GPoint(1.0, 1.0), SnapKind.VERTEX),
            SnapCandidate(GPoint(-1.0, -1.0), SnapKind.EXTREMUM)
        )

        // 只吸顶点：手指在 (1,1) 附近 → 命中；只吸零点则不命中
        val nearVertex = snap(candidates, v, 198f, 2f, 24.0)
        assertNotNull(nearVertex)
        assertEquals(SnapKind.VERTEX, nearVertex!!.kind)
        assertTrue(nearVertex.screenDistance < 3.0)
        assertNull(
            "kinds 不含 VERTEX → 这个点不参与",
            snap(candidates, v, 198f, 2f, 24.0, kinds = setOf(SnapKind.ZERO))
        )
        assertNull(
            "候选里没有零点和极值点靠近手指",
            snap(candidates, v, 100f, 100f, 1.0, kinds = setOf(SnapKind.EXTREMUM))
        )

        // 多选来源
        val multi = snap(
            candidates, v, 100f, 100f, 24.0,
            kinds = setOf(SnapKind.ZERO, SnapKind.EXTREMUM)
        )
        assertNotNull(multi)
        assertEquals(SnapKind.ZERO, multi!!.kind)

        // 简写版本：只关心吸到哪个点
        val plain = snap(listOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0)), v, 101f, 100f, 24.0)
        assertNotNull(plain)
        assertPoint(0.0, 0.0, plain!!, 1e-12)
        assertNull(snap(emptyList<GPoint>(), v, 101f, 100f, 24.0))
    }

    @Test
    fun `吸附与交点联动`() {
        // 完整链路：两条曲线 → 交点（世界坐标）→ 变成候选点 → 手指点一下吸上
        val v = vp()
        val para = parabola(v)
        val yZero = lineOf(GPoint(-1.0, 0.0), GPoint(1.0, 0.0))

        val hits = intersections(para, yZero, v)
        assertEquals("y=x^2 与 x 轴相切于原点：$hits", 1, hits.size)
        assertPoint(0.0, 0.0, hits[0], 1e-9)

        val candidates = hits.map { SnapCandidate(it, SnapKind.INTERSECTION) }
        // 手指点在交点旁边 5 像素
        val hit = snap(candidates, v, 105f, 96f, 12.0)
        assertNotNull(hit)
        assertPoint(0.0, 0.0, hit!!.point, 1e-9)
        assertEquals(hypot(5.0, 4.0), hit.screenDistance, 1e-9)
        assertNull("离得太远就不吸", snap(candidates, v, 105f, 96f, 3.0))
    }
}
