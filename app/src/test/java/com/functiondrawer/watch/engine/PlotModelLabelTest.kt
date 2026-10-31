package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 函数列表那一行文本（[PlotFunction.listLabel]）的取整符号替换。
 *
 * 用户原话："函数列表界面不能显示向上取整函数和向下取整的函数符号。"
 * 列表是纯文本，所以只能在字符串层面把 `ceil()` / `floor()` 换成 ⌈⌉ / ⌊⌋。
 */
class PlotModelLabelTest {

    private fun label(src: String, name: String = ""): String =
        PlotFunction(id = 1L, mode = PlotMode.EXPLICIT, src1 = src, name = name).listLabel()

    @Test
    fun `向上取整换成方括号`() {
        assertEquals("y = ⌈x⌉", label("ceil(x)"))
    }

    @Test
    fun `向下取整换成方括号`() {
        assertEquals("y = ⌊x⌋", label("floor(x)"))
    }

    @Test
    fun `嵌套的取整按配对括号展开`() {
        // 不能停在第一个 `)` —— 否则会变成 `⌈x+floor(y⌉)`
        assertEquals("y = ⌈x+⌊y⌋⌉", label("ceil(x+floor(y))"))
    }

    @Test
    fun `有名字时名字保留`() {
        assertEquals("f(x) : y = ⌈x⌉", label("ceil(x)", name = "f(x)"))
    }

    @Test
    fun `普通表达式原样不动`() {
        assertEquals("y = sin(x)+2", label("sin(x)+2"))
    }

    @Test
    fun `括号不配对时不乱改`() {
        assertEquals("y = ceil(x", label("ceil(x"))
    }
}
