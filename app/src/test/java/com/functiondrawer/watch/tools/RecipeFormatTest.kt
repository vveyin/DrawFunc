package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.ui.FunctionFileFormat
import com.functiondrawer.watch.ui.ObjectRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 施工说明（`R` 记录）的编解码（1.3.9）。
 *
 * 用户实测："关闭软件之后再打开，原本描的点派生的直线 / 曲线就不能跟着点动了"。
 * 根因是文件里**不存** `ObjectRecipe`，这里钉住新的那一份 `R` 段：
 * 每一种 recipe 都要能原样往返，没有 recipe 的文件必须逐字节不变（不写任何 `R` 行）。
 */
class RecipeFormatTest {

    private val everyRecipe = listOf(
        ObjectRecipe.LineFromPoints(2, 3, GLineKind.LINE),
        ObjectRecipe.LineFromPoints(2, 3, GLineKind.SEGMENT),
        ObjectRecipe.LineFromPoints(2, 3, GLineKind.RAY),
        ObjectRecipe.PerpendicularBisectorOf(2, 3),
        ObjectRecipe.AngleBisectorOf(2, 3, 4),
        ObjectRecipe.PerpendicularTo(ObjectRecipe.LineRef.Obj(5), 2),
        ObjectRecipe.ParallelTo(ObjectRecipe.LineRef.Obj(5), 2),
        ObjectRecipe.MidpointOf(2, 3),
        ObjectRecipe.DistanceBetween(2, 3),
        ObjectRecipe.AreaOfPoints(listOf(2, 3, 4)),
        ObjectRecipe.AngleBetweenLines(ObjectRecipe.LineRef.Obj(5), ObjectRecipe.LineRef.Obj(6)),
        ObjectRecipe.MirrorPointOf(2, ObjectRecipe.MirrorPointOf.Mirror.Axis(ObjectRecipe.LineRef.Obj(5))),
        ObjectRecipe.MirrorPointOf(2, ObjectRecipe.MirrorPointOf.Mirror.Center(7)),
        ObjectRecipe.CircleCenterPoint(2, 3),
        ObjectRecipe.CircleCenterRadius(2, 3, 4),
        ObjectRecipe.CircleCenterRadiusLine(2, ObjectRecipe.LineRef.Obj(5)),
        ObjectRecipe.CircleCenterRadiusExpr(2, "d/2"),
        ObjectRecipe.CircleThreePoints(2, 3, 4),
        ObjectRecipe.ArcOfPoints(2, 3, 4),
        ObjectRecipe.EllipseOf(2, 3, 4),
        ObjectRecipe.HyperbolaOf(2, 3, 4),
        ObjectRecipe.ParabolaOf(2, ObjectRecipe.LineRef.Obj(5)),
        ObjectRecipe.ConicFivePoints(listOf(2, 3, 4, 5, 6)),
        ObjectRecipe.TangentAtPoint(2, 7)
    )

    @Test
    fun `每一种施工说明都能原样往返`() {
        for (r in everyRecipe) {
            val back = FunctionFileFormat.parseRecipe(FunctionFileFormat.encodeRecipe(r))
            assertEquals("往返失败：$r", r, back)
        }
    }

    @Test
    fun `常量直线的引用也能往返`() {
        val fixed = ObjectRecipe.LineRef.Fixed(
            GLine(GPoint(-2.5, 0.0), GPoint(3.0, 9.5), GLineKind.RAY)
        )
        val recipe = ObjectRecipe.ParallelTo(fixed, 2)
        assertEquals(recipe, FunctionFileFormat.parseRecipe(FunctionFileFormat.encodeRecipe(recipe)))
    }

    @Test
    fun `半径表达式里的逗号和竖线都不会把记录拆坏`() {
        val r = ObjectRecipe.CircleCenterRadiusExpr(2, "max(d,2)|3")
        assertEquals(r, FunctionFileFormat.parseRecipe(FunctionFileFormat.encodeRecipe(r)))
    }

    @Test
    fun `文件里带着施工说明读写一遍`() {
        val state = FunctionFileFormat.FileState(
            objectRecipes = mapOf(
                1L to ObjectRecipe.LineFromPoints(2, 3, GLineKind.LINE),
                4L to ObjectRecipe.MidpointOf(2, 3)
            ),
            functionRecipes = mapOf(
                9L to ObjectRecipe.CircleCenterRadiusExpr(2, "d/2")
            )
        )
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(state))
        assertEquals(state.objectRecipes, back?.objectRecipes)
        assertEquals(state.functionRecipes, back?.functionRecipes)
    }

    @Test
    fun `没有施工说明的文件一行 R 都不写`() {
        val text = FunctionFileFormat.encode(FunctionFileFormat.FileState())
        assertFalse(text.split('\n').any { it.startsWith("R|") })
    }

    @Test
    fun `坏掉的记录只丢自己`() {
        // 不认识的正文 / 缺字段 → null，不抛异常
        assertNull(FunctionFileFormat.parseRecipe("XX,1,2"))
        assertNull(FunctionFileFormat.parseRecipe("LP,2"))
        assertNull(FunctionFileFormat.parseRecipe(""))
        // 文件里塞一行坏的 R：其余内容照样能读
        val state = FunctionFileFormat.FileState(
            objectRecipes = mapOf(1L to ObjectRecipe.MidpointOf(2, 3))
        )
        val text = FunctionFileFormat.encode(state) + "R|O|2|BROKEN\n"
        val back = FunctionFileFormat.decode(text)
        assertEquals(state.objectRecipes, back?.objectRecipes)
    }
}
