package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.Complex
import com.functiondrawer.watch.engine.ExpressionException
import com.functiondrawer.watch.ui.ComplexOp
import com.functiondrawer.watch.ui.ComplexOpResult
import com.functiondrawer.watch.ui.complexOpLabel
import com.functiondrawer.watch.ui.complexOpPoint
import com.functiondrawer.watch.ui.evaluateComplexOp
import com.functiondrawer.watch.ui.nextComplexOpName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复平面的"数值运算"层（#1）。
 *
 * 求值本身在引擎里有 35 个测试，这里测的是**这一层的规则**：
 * 空表达式怎么处理、错误怎么变成给用户看的文字、结果怎么变成"图上的一个点"、
 * 名字怎么分配。这些规则改起来很随意，所以必须钉住。
 */
class ComplexOpsTest {

    private fun ok(result: ComplexOpResult): Complex {
        assertTrue("本应是成功结果，实际是 $result", result is ComplexOpResult.Ok)
        return (result as ComplexOpResult.Ok).value
    }

    @Test
    fun `把 z 代进去算出一个复数`() {
        val op = ComplexOp(id = 1L, name = "A", src = "z^2+1")
        // (1+i)^2 + 1 = 2i + 1 = 1 + 2i
        val v = ok(evaluateComplexOp(op, Complex(1.0, 1.0)))
        assertEquals(1.0, v.re, 1e-9)
        assertEquals(2.0, v.im, 1e-9)
    }

    @Test
    fun `参数 a b 从滑动条那边传进来`() {
        val op = ComplexOp(id = 1L, name = "A", src = "a*z+b")
        val params = mapOf("a" to Complex(2.0, 0.0), "b" to Complex(0.0, 3.0))
        val v = ok(evaluateComplexOp(op, Complex(1.0, 0.0), params))
        assertEquals(2.0, v.re, 1e-9)
        assertEquals(3.0, v.im, 1e-9)
    }

    @Test
    fun `不给参数时按引擎的默认 1 算`() {
        val op = ComplexOp(id = 1L, name = "A", src = "a*z+b")
        val v = ok(evaluateComplexOp(op, Complex(1.0, 0.0)))
        assertEquals("z+1 在 z=1 处应是 2", 2.0, v.re, 1e-9)
        assertEquals(0.0, v.im, 1e-9)
    }

    @Test
    fun `空表达式给一句能读懂的话而不是崩`() {
        val r = evaluateComplexOp(ComplexOp(1L, "A", "   "))
        assertTrue(r is ComplexOpResult.Failed)
        assertTrue((r as ComplexOpResult.Failed).message.contains("还没有输入"))
    }

    @Test
    fun `语法错误带位置并且不抛异常`() {
        val r = evaluateComplexOp(ComplexOp(1L, "A", "1+i+*2"))
        assertTrue("应该是失败结果：$r", r is ComplexOpResult.Failed)
        // 引擎的报错是 ExpressionException，这一层要把它翻译成"能显示的文字"
        assertTrue((r as ComplexOpResult.Failed).message.isNotBlank())
        assertEquals("位置要原样传出来，界面靠它标红", 4, r.position)
    }

    /** 兜底分支也要有：引擎理论上只抛 ExpressionException，但界面绝不能被别的异常搞崩。 */
    @Test
    fun `ExpressionException 之外的情况也被接住`() {
        val r = evaluateComplexOp(ComplexOp(1L, "A", "sin("))
        assertTrue(r is ComplexOpResult.Failed)
    }

    @Test
    fun `结果变成图上的一个点：横轴实部、纵轴虚部`() {
        val r = evaluateComplexOp(ComplexOp(1L, "A", "2+3i"))
        val p = complexOpPoint(r)
        assertEquals(2.0, p!!.x, 1e-9)
        assertEquals(3.0, p.y, 1e-9)
        assertEquals("2+3i", complexOpLabel(r))
    }

    @Test
    fun `失败时不画点也不算标签`() {
        val r = evaluateComplexOp(ComplexOp(1L, "A", "1/"))
        assertNull("算不出来就不该有坐标", complexOpPoint(r))
        assertNull("也不该有标签", complexOpLabel(r))
    }

    @Test
    fun `标签的常见形态`() {
        assertEquals("3", complexOpLabel(evaluateComplexOp(ComplexOp(1L, "A", "3"))))
        assertEquals("-i", complexOpLabel(evaluateComplexOp(ComplexOp(1L, "A", "-i"))))
        assertEquals("2i", complexOpLabel(evaluateComplexOp(ComplexOp(1L, "A", "2i"))))
    }

    @Test
    fun `名字按大写字母推移并避开已占用的`() {
        assertEquals("A", nextComplexOpName(emptySet(), 0))
        assertEquals("B", nextComplexOpName(setOf("A"), 0))
        assertEquals("C", nextComplexOpName(setOf("A", "B"), 0))
        // 26 个之后进位，和 GraphState 的规则一致
        val all = ('A'..'Z').map { it.toString() }.toSet()
        assertEquals("AA", nextComplexOpName(all, 0))
    }

    /** 引擎的异常类型必须是可捕获的（这一层靠它翻译错误信息）。 */
    @Test
    fun `引擎的异常类型是公开的`() {
        val e = ExpressionException("测试", 3)
        assertEquals(3, e.position)
    }
}
