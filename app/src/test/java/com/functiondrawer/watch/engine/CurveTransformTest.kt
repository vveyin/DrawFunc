package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线整体变换（#9 变换栏：函数/方程的轴对称与中心对称）。
 *
 * 断言都用**解析值**：反射后的点必须落在它该在的位置，
 * 而不是"和实现输出一致"。
 */
class CurveTransformTest {

    /** 世界坐标 -10~10 映射到 400px，方便手算：世界 0 → 200px、世界 1 → 220px。 */
    private val vp = Viewport.forRange(400f, 400f, -10.0, 10.0)

    /** 造一条"屏幕像素"折线：世界坐标 (x, x) 上的若干点。 */
    private fun diagonalWorldPoints(): FloatArray {
        val world = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
        val out = FloatArray(world.size * 2)
        world.forEachIndexed { i, x ->
            out[i * 2] = vp.toScreenX(x)
            out[i * 2 + 1] = vp.toScreenY(x)
        }
        return out
    }

    private fun worldOf(points: FloatArray, index: Int): GPoint = GPoint(
        vp.toWorldX(points[index * 2].toDouble()),
        vp.toWorldY(points[index * 2 + 1].toDouble())
    )

    @Test
    fun `关于 x 轴反射：y 变号`() {
        val src = diagonalWorldPoints()
        val axis = lineOf(GPoint(-5.0, 0.0), GPoint(5.0, 0.0))
        val out = CurveTransform.reflectOverLine(src, vp, vp, axis)
        for (i in 0..4) {
            val w = worldOf(out, i)
            assertEquals("第 $i 个点的 x 不该变", src[i * 2].toDouble(), vp.toScreenX(w.x).toDouble(), 1e-3)
            assertEquals("第 $i 个点的 y 要变号", -worldOf(src, i).y, w.y, 1e-6)
        }
    }

    @Test
    fun `关于 y 轴反射：x 变号`() {
        val src = diagonalWorldPoints()
        val axis = lineOf(GPoint(0.0, -5.0), GPoint(0.0, 5.0))
        val out = CurveTransform.reflectOverLine(src, vp, vp, axis)
        for (i in 0..4) {
            val w = worldOf(out, i)
            assertEquals(-worldOf(src, i).x, w.x, 1e-6)
            assertEquals(worldOf(src, i).y, w.y, 1e-6)
        }
    }

    @Test
    fun `关于 y 等于 x 反射：坐标互换`() {
        val src = diagonalWorldPoints()
        val axis = lineOf(GPoint(0.0, 0.0), GPoint(1.0, 1.0))
        val out = CurveTransform.reflectOverLine(src, vp, vp, axis)
        for (i in 0..4) {
            val w = worldOf(out, i)
            val s = worldOf(src, i)
            assertEquals("关于 y=x 对称就是交换坐标", s.y, w.x, 1e-6)
            assertEquals(s.x, w.y, 1e-6)
        }
    }

    @Test
    fun `中心对称：关于原点就是两个坐标都变号`() {
        val src = diagonalWorldPoints()
        val out = CurveTransform.reflectThrough(src, vp, vp, GPoint(0.0, 0.0))
        for (i in 0..4) {
            val w = worldOf(out, i)
            val s = worldOf(src, i)
            assertEquals(-s.x, w.x, 1e-6)
            assertEquals(-s.y, w.y, 1e-6)
        }
    }

    @Test
    fun `中心对称：关于非原点中心`() {
        val src = diagonalWorldPoints()
        val out = CurveTransform.reflectThrough(src, vp, vp, GPoint(1.0, 2.0))
        for (i in 0..4) {
            val w = worldOf(out, i)
            val s = worldOf(src, i)
            assertEquals(2 * 1.0 - s.x, w.x, 1e-6)
            assertEquals(2 * 2.0 - s.y, w.y, 1e-6)
        }
    }

    @Test
    fun `平移按世界坐标走`() {
        val src = diagonalWorldPoints()
        val out = CurveTransform.translate(src, vp, vp, 3.0, -4.0)
        for (i in 0..4) {
            val w = worldOf(out, i)
            val s = worldOf(src, i)
            assertEquals(s.x + 3.0, w.x, 1e-6)
            assertEquals(s.y - 4.0, w.y, 1e-6)
        }
    }

    /**
     * 源视口与目标视口不同时，结果要落在**目标**视口该在的位置。
     *
     * 这是最容易写错的地方（把两个视口搞混会让整条曲线偏掉），所以专门钉一条：
     * 同样一个世界点，在放大两倍的视口里应该落在离中心两倍远的像素上。
     */
    @Test
    fun `源视口与目标视口不同：结果按目标视口落位`() {
        val src = diagonalWorldPoints()
        val zoomed = Viewport.forRange(400f, 400f, -5.0, 5.0)
        val out = CurveTransform.mapPoints(src, vp, zoomed) { it }  // 恒等变换，只换视口
        for (i in 0..4) {
            val w = worldOf(src, i)
            // 恒等变换之后，世界坐标不该变
            val back = GPoint(
                zoomed.toWorldX(out[i * 2].toDouble()),
                zoomed.toWorldY(out[i * 2 + 1].toDouble())
            )
            assertEquals(w.x, back.x, 1e-6)
            assertEquals(w.y, back.y, 1e-6)
        }
    }

    @Test
    fun `非有限的结果写成 NaN 而不是画到原点`() {
        val src = floatArrayOf(200f, 200f, 210f, 210f)
        val out = CurveTransform.mapPoints(src, vp, vp) { GPoint(Double.NaN, 0.0) }
        assertTrue("变换出 NaN 时该点要标成 NaN", out[0].isNaN())
        assertTrue(out[1].isNaN())
    }

    @Test
    fun `视野判定：完全在屏幕外的镜像不算可见`() {
        // 世界 1000 处的点远离 -10~10 的视野
        val far = floatArrayOf(vp.toScreenX(1000.0), vp.toScreenY(1000.0))
        assertFalse(CurveTransform.isVisible(far, vp))
        val near = floatArrayOf(vp.toScreenX(0.0), vp.toScreenY(0.0))
        assertTrue(CurveTransform.isVisible(near, vp))
    }

    @Test
    fun `空数组与奇数长度不崩`() {
        assertEquals(0, CurveTransform.mapPoints(FloatArray(0), vp, vp) { it }.size)
        // 奇数长度：最后一个落单的分量保持 0，不允许越界
        val odd = floatArrayOf(200f, 200f, 210f)
        val out = CurveTransform.mapPoints(odd, vp, vp) { it }
        assertEquals(3, out.size)
    }
}
