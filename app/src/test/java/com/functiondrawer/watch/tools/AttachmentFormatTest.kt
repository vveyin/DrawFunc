package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.ui.FunctionFileFormat
import com.functiondrawer.watch.ui.PointAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 描点「吸附关系」的编解码（1.3.10）。
 *
 * 背景：recipe 存盘之后还剩同一类漏洞 —— 点吸在哪条曲线 / 哪条线 / 哪个交点上
 * 这一份**没存**，于是重启后点还在、曲线一动它就不跟了。这里钉住新的那一格：
 * 三种吸附都能原样往返，自由点（没有吸附）**一格都不写**，老文件读出来是自由点。
 */
class AttachmentFormatTest {

    private val everyAttachment = listOf(
        PointAttachment.OnCurve(functionId = 7, x = -1.25),
        PointAttachment.OnLine(lineId = 5, t = 0.375),
        PointAttachment.OnIntersection(aFunction = 7, bFunction = 8),
        PointAttachment.OnIntersection(aFunction = 7, bLine = 5),
        PointAttachment.OnIntersection(aLine = 5, bLine = 6)
    )

    @Test
    fun `每一种吸附关系都能原样往返`() {
        for (a in everyAttachment) {
            val back = FunctionFileFormat.parseAttachment(FunctionFileFormat.attachmentCode(a))
            assertEquals("往返失败：$a", a, back)
        }
    }

    @Test
    fun `自由点不写吸附那一格`() {
        val text = FunctionFileFormat.encode(
            FunctionFileFormat.FileState(
                objects = listOf(
                    FunctionFileFormat.FileObject.Point(1, "A", GPoint(1.5, 2.5), visible = true)
                )
            )
        )
        val pointLine = text.split('\n').first { it.startsWith("O|P|") }
        // 老格式 9 格：O P id name x y kind color visible
        assertEquals(9, pointLine.split('|').size)
        assertFalse(pointLine.contains(":"))
    }

    @Test
    fun `吸住的点写一格并且能读回来`() {
        val a = PointAttachment.OnIntersection(aFunction = 7, bLine = 5)
        val state = FunctionFileFormat.FileState(
            objects = listOf(
                FunctionFileFormat.FileObject.Point(
                    id = 1, name = "B", point = GPoint(0.0, 0.0), visible = true, attachment = a
                )
            )
        )
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(state))
        val p = back?.objects?.firstOrNull() as? FunctionFileFormat.FileObject.Point
        assertEquals(a, p?.attachment)
    }

    @Test
    fun `老文件没有那一格时当作自由点`() {
        val legacy = FunctionFileFormat.encode(
            FunctionFileFormat.FileState(
                objects = listOf(
                    FunctionFileFormat.FileObject.Point(1, "A", GPoint(1.5, 2.5), visible = true)
                )
            )
        )
        val back = FunctionFileFormat.decode(legacy)
        val p = back?.objects?.firstOrNull() as? FunctionFileFormat.FileObject.Point
        assertNull(p?.attachment)
    }

    @Test
    fun `坏掉的吸附那一格只丢吸附本身`() {
        assertNull(FunctionFileFormat.parseAttachment("XX"))
        assertNull(FunctionFileFormat.parseAttachment("c:7"))
        assertNull(FunctionFileFormat.parseAttachment("i:-:-:-:-"))
        // 点本身照样读回来
        val legacy = FunctionFileFormat.encode(
            FunctionFileFormat.FileState(
                objects = listOf(FunctionFileFormat.FileObject.Point(1, "A", GPoint(1.5, 2.5)))
            )
        )
        val line = legacy.split('\n').first { it.startsWith("O|P|") }
        val back = FunctionFileFormat.decode(legacy.replace(line, "$line|BROKEN"))
        assertEquals(1, back?.objects?.size)
        assertNull((back?.objects?.first() as? FunctionFileFormat.FileObject.Point)?.attachment)
    }
}
