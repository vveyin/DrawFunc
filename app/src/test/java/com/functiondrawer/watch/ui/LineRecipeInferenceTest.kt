package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.lineOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 读盘补 recipe（1.3.8）：
 * "关闭软件再打开，原本描的点派生的直线就不能跟着点动了"。
 *
 * 存盘不存 [ObjectRecipe]，所以恢复时按锚点认回"两点定线"。
 */
class LineRecipeInferenceTest {

    private fun point(id: Long, x: Double, y: Double) =
        GraphPointObject(id = id, name = "点$id", point = GPoint(x, y))

    @Test
    fun `两个锚点各压在一个点上就还原成两点定线`() {
        val a = point(1, 0.0, 0.0)
        val b = point(2, 3.0, 2.0)
        val line = lineOf(a.point, b.point)
        val recipe = inferLineFromPointsRecipe(line, listOf(a, b))
        assertEquals(ObjectRecipe.LineFromPoints(1, 2, GLineKind.LINE), recipe)
        // 端点顺序不影响 id 的绑定：line.p 对应 a、line.q 对应 b
        assertEquals(GLineKind.LINE, recipe?.kind)
    }

    @Test
    fun `线段和射线按各自的 kind 还原`() {
        val a = point(1, -1.0, 2.0)
        val b = point(2, 4.0, 5.0)
        val segment = com.functiondrawer.watch.engine.segmentOf(a.point, b.point)
        assertEquals(
            ObjectRecipe.LineFromPoints(1, 2, GLineKind.SEGMENT),
            inferLineFromPointsRecipe(segment, listOf(a, b))
        )
        val ray = com.functiondrawer.watch.engine.rayOf(a.point, b.point)
        assertEquals(
            ObjectRecipe.LineFromPoints(1, 2, GLineKind.RAY),
            inferLineFromPointsRecipe(ray, listOf(a, b))
        )
    }

    @Test
    fun `只有一个锚点压着点时不乱认`() {
        val a = point(1, 0.0, 0.0)
        val line = lineOf(a.point, GPoint(5.0, 7.0))
        assertNull(inferLineFromPointsRecipe(line, listOf(a)))
    }

    @Test
    fun `退化线不认`() {
        val a = point(1, 2.0, 3.0)
        val line = lineOf(a.point, a.point)
        assertNull(inferLineFromPointsRecipe(line, listOf(a)))
    }

    @Test
    fun `坐标重合的点不会让两端绑成同一个 id`() {
        val a = point(1, 0.0, 0.0)
        val dup = point(2, 0.0, 0.0)
        val b = point(3, 4.0, 0.0)
        val line = lineOf(a.point, b.point)
        // p 落在 1/2 两个同坐标点上（取先出现的），q 必须换另一个 id（3）
        assertEquals(
            ObjectRecipe.LineFromPoints(1, 3, GLineKind.LINE),
            inferLineFromPointsRecipe(line, listOf(a, dup, b))
        )
    }
}
