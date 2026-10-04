package com.functiondrawer.watch.tools

import com.functiondrawer.watch.ui.SAVE_DIR_HINT
import com.functiondrawer.watch.ui.SAVE_DIR_HINT as DIR_HINT
import com.functiondrawer.watch.ui.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存图像的文件名规则（#8）。
 *
 * 为什么值得单独测：这条链一旦出错（名字带 `/`、以 `.` 开头、空名字）
 * 表现是"存了但找不到"或"直接存失败"，而这种问题在手表上极难排查 ——
 * 用户拿不到文件列表，也不会去看 logcat。
 */
class SaveNameTest {

    @Test
    fun `普通名字补上 png 扩展名`() {
        assertEquals("我的曲线.png", sanitizeFileName("我的曲线"))
        assertEquals("sin-wave.png", sanitizeFileName("sin-wave"))
    }

    @Test
    fun `已经带扩展名就不重复补`() {
        assertEquals("a.png", sanitizeFileName("a.png"))
        assertEquals("a.PNG", sanitizeFileName("a.PNG"))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("abc.png", sanitizeFileName("   abc   "))
    }

    @Test
    fun `非法字符换成下划线`() {
        assertEquals("a_b.png", sanitizeFileName("a/b"))
        assertEquals("a_b_c.png", sanitizeFileName("a\\b:c"))
        // 名字中间的下划线要保留（只有首尾的才会被清掉）
        assertEquals("my_wave.png", sanitizeFileName("my/wave"))
        assertTrue("路径分隔符绝不能留在结果里", '/' !in sanitizeFileName("../../etc/passwd"))
    }

    @Test
    fun `全是非法字符的名字同样退回时间戳`() {
        // 七个非法字符会被替换成七个下划线，而首尾的下划线属于"无意义修饰"要被清掉 ——
        // 结果就是空名字，于是走时间戳兜底。
        // 这条期望值是我写错后被测试纠正的：原来以为会得到 `_______.png`，
        // 但那会生成一个用户完全认不出、也可能撞名的文件，不如直接给时间戳。
        val name = sanitizeFileName("\\:*?\"<>|")
        assertTrue("应该是时间戳形式：$name", Regex("^\\d{8}-\\d{6}\\.png$").matches(name))
    }

    @Test
    fun `控制字符也换掉`() {
        assertEquals("a_b.png", sanitizeFileName("a\u0000b"))
        assertEquals("a_b.png", sanitizeFileName("a\nb"))
    }

    @Test
    fun `空名字退回时间戳而不是报错`() {
        val name = sanitizeFileName("")
        assertTrue("应该是时间戳形式：$name", Regex("^\\d{8}-\\d{6}\\.png$").matches(name))
        // 全是空白 / 只有点的情况同样要兜底
        assertTrue(Regex("^\\d{8}-\\d{6}\\.png$").matches(sanitizeFileName("   ")))
        assertTrue(Regex("^\\d{8}-\\d{6}\\.png$").matches(sanitizeFileName("...")))
    }

    @Test
    fun `以点开头的名字被清掉前缀`() {
        // Linux 上以 . 开头是隐藏文件：用户会"存了但找不到"
        assertEquals("hidden.png", sanitizeFileName(".hidden"))
        assertEquals("hidden.png", sanitizeFileName("..hidden"))
    }

    @Test
    fun `超长名字被截断且仍然合法`() {
        val long = "x".repeat(500)
        val name = sanitizeFileName(long)
        assertTrue("长度要收住：${name.length}", name.length <= 64 + 4)
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `存盘目录提示是相对路径且不含盘符`() {
        assertTrue(SAVE_DIR_HINT.startsWith("Android/"))
        assertTrue("不能出现盘符或开头斜杠", !SAVE_DIR_HINT.startsWith("/"))
        assertEquals(DIR_HINT, SAVE_DIR_HINT)
    }
}
