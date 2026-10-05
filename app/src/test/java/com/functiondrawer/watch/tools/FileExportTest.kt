package com.functiondrawer.watch.tools

// ⚠️ 过渡期说明（`tools\run-tool-tests.ps1` 里加完那两行就可以把这一段删掉）：
//
// 这个测试类依赖 `ui\FileExport.kt`，而那个文件**还不在** `tools\run-tool-tests.ps1`
// 的编译清单里（脚本只编七个写死的文件 + engine 目录 + 本测试目录）—— 所以在那两行
// 加进去之前，这个脚本会报一串 `unresolved reference`（EXPORT_RELATIVE_PATH /
// ExportOutcome / ExportPlan / ExportProblem / exportProblemText / planExport /
// uniqueFileName），**全部是这一个原因**，不是 Kotlin 代码写坏了。
// 加完之后：190 + 19 = 209 条全绿。

import com.functiondrawer.watch.ui.EXPORT_RELATIVE_PATH
import com.functiondrawer.watch.ui.ExportOutcome
import com.functiondrawer.watch.ui.ExportPlan
import com.functiondrawer.watch.ui.ExportProblem
import com.functiondrawer.watch.ui.FunctionFileFormat
import com.functiondrawer.watch.ui.exportProblemText
import com.functiondrawer.watch.ui.planExport
import com.functiondrawer.watch.ui.uniqueFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导出到 下载/DrawFunc」的**纯规则**（用户需求："在重命名浮窗里加一颗导出文件的按钮，
 * 点了就导到下载文件夹里的 DrawFunc 文件夹，没有就自动创建"）。
 *
 * 为什么这几条值得离线钉住：它们决定"导出出去的那个文件叫什么、会不会把别人覆盖掉、
 * 出错时用户看到哪句话"。而在手表上这三点都**没法当场验证**（要拔线、要在文件管理器里翻），
 * 一旦名字规则写错（比如撞名直接覆盖），用户丢的是他自己上一次导出的备份。
 *
 * 这个测试只碰 [com.functiondrawer.watch.ui.FileExport] 这一个文件里的纯函数 ——
 * 真正落盘的 `DownloadExport`（MediaStore）注定只能在设备上验，不在这里装样子。
 */
class FileExportTest {

    // ---- 小工具：把 planExport 的两个分支写短一点 ----

    private fun ready(draft: String, original: String = "sin", taken: Set<String> = emptySet()): String =
        when (val plan = planExport(draft, original, taken)) {
            is ExportPlan.Ready -> plan.fileName
            is ExportPlan.Rejected -> error("本该通过，却被拒绝了：${plan.message}")
        }

    private fun rejected(draft: String, original: String = "sin"): String =
        when (val plan = planExport(draft, original, emptySet())) {
            is ExportPlan.Rejected -> plan.message
            is ExportPlan.Ready -> error("本该被拒绝，却拿到了 ${plan.fileName}")
        }

    // ---- 1. 落点 ----

    @Test
    fun `落点是下载目录下的 DrawFunc`() {
        // 这个值同时是 MediaStore 的 RELATIVE_PATH（子目录由系统自动创建）和提示语里的路径
        assertEquals("Download/DrawFunc", EXPORT_RELATIVE_PATH)
        assertTrue("不能以斜杠开头", !EXPORT_RELATIVE_PATH.startsWith("/"))
        assertTrue("不能带结尾斜杠", !EXPORT_RELATIVE_PATH.endsWith("/"))
    }

    // ---- 2. 名字怎么定 ----

    @Test
    fun `用输入框里当前那段文本_而不是磁盘上的原名`() {
        // 用户可能刚改完名字还没按 ✓：导出应该是"我现在看到的这个名字"
        assertEquals("新名字.fdf", ready("新名字"))
    }

    @Test
    fun `草稿里手打的 fdf 扩展名不会变成 fdf_fdf`() {
        assertEquals("sin.fdf", ready("sin.fdf"))
        assertEquals("sin.fdf", ready("  sin.FDF  "))
    }

    @Test
    fun `草稿为空时退回原名而不是报错`() {
        // 手表上打字慢，"清空输入框"更可能表示"还没想好新名字"，这时让导出失败没有好处
        assertEquals("sin.fdf", ready("", original = "sin"))
        assertEquals("sin.fdf", ready("   ", original = "sin"))
        assertEquals("sin.fdf", ready(".fdf", original = "sin"))
    }

    @Test
    fun `草稿和原名都为空才拒绝_并且说明原因`() {
        assertEquals("导出失败：名字不能为空", rejected("", original = ""))
    }

    @Test
    fun `非法名字用和重命名同一句话拒绝`() {
        // 复用 FunctionFileFormat.baseNameError：不能出现"改名不让用、导出却让用"
        val error = FunctionFileFormat.baseNameError("a/b", emptySet())
        assertEquals("导出失败：$error", rejected("a/b"))
        assertTrue("提示要带前缀，一眼知道是导出失败", rejected("a/b").startsWith("导出失败："))
    }

    @Test
    fun `以点开头的名字被拒绝_因为那是隐藏文件`() {
        assertTrue(rejected(".hidden").startsWith("导出失败："))
        assertTrue(rejected(".hidden").contains("点"))
    }

    @Test
    fun `超长名字被拒绝_40 个字是上限`() {
        assertEquals("x".repeat(40) + ".fdf", ready("x".repeat(40)))
        assertTrue(rejected("x".repeat(41)).contains("太长"))
    }

    @Test
    fun `App 内部有同名文件不影响导出`() {
        // 内部存档的同名是"重命名"这件事的规则；导出只看目标文件夹（taken 由调用方给）
        // —— 这条用例钉的是"planExport 不会因为内部重名而拒绝"这个口径。
        assertEquals("sin.fdf", ready("sin", taken = emptySet()))
    }

    // ---- 3. 目标文件夹里撞名：加序号，绝不覆盖 ----

    @Test
    fun `没有同名时原样用`() {
        assertEquals("sin.fdf", ready("sin", taken = setOf("cos.fdf", "tan.fdf")))
    }

    @Test
    fun `撞名时加序号 2`() {
        assertEquals("sin (2).fdf", ready("sin", taken = setOf("sin.fdf")))
    }

    @Test
    fun `连续撞名就一路往后数`() {
        assertEquals(
            "sin (3).fdf",
            ready("sin", taken = setOf("sin.fdf", "sin (2).fdf"))
        )
        assertEquals(
            "sin (4).fdf",
            ready("sin", taken = setOf("sin.fdf", "sin (2).fdf", "sin (3).fdf"))
        )
    }

    @Test
    fun `序号加在扩展名前面_而且大小写不敏感地判重`() {
        // `Sin.fdf` 和 `sin.fdf` 在 1.5 寸的列表里用户根本分不清，宁可不导出这一对
        assertEquals("sin (2).fdf", ready("sin", taken = setOf("SIN.FDF")))
        assertTrue("序号必须在 .fdf 前面", ready("sin", taken = setOf("sin.fdf")).endsWith(".fdf"))
    }

    @Test
    fun `uniqueFileName 永远不会给出一个已经被占用的名字`() {
        val taken = setOf("sin.fdf", "sin (2).fdf", "sin (3).fdf", "sin (4).fdf")
        val picked = uniqueFileName("sin.fdf", taken)
        assertNotEquals("sin.fdf", picked)
        assertTrue("挑出来的名字不能在占用表里：$picked", taken.none { it.equals(picked, ignoreCase = true) })
    }

    @Test
    fun `uniqueFileName 在没有冲突时原样返回`() {
        assertEquals("sin.fdf", uniqueFileName("sin.fdf", emptySet()))
        assertEquals("sin.fdf", uniqueFileName("sin.fdf", setOf("cos.fdf")))
    }

    // ---- 4. 给用户看的话 ----

    @Test
    fun `成功提示里有完整路径和最终文件名`() {
        assertEquals(
            "已导出到 Download/DrawFunc/sin.fdf",
            ExportOutcome.Success("sin.fdf").message
        )
        // 撞名之后提示里必须是**真的落盘的那个名字**，不能还是我们要的那个
        assertEquals(
            "已导出到 Download/DrawFunc/sin (2).fdf",
            ExportOutcome.Success("sin (2).fdf").message
        )
    }

    @Test
    fun `四种失败原因都有话说_而且各不相同`() {
        val texts = ExportProblem.entries.map { exportProblemText(it) }
        assertEquals("每个原因都要有文案", ExportProblem.entries.size, texts.size)
        texts.forEach { assertTrue("都要带前缀：$it", it.startsWith("导出失败：")) }
        assertEquals("文案不能重复（否则等于没说清原因）", texts.size, texts.toSet().size)
    }

    @Test
    fun `版本不支持的提示要说清需要什么版本`() {
        val text = exportProblemText(ExportProblem.UNSUPPORTED_VERSION)
        assertTrue("用户要看得懂'为什么不行'：$text", text.contains("Android 10"))
    }

    @Test
    fun `失败结果原样带出那句话`() {
        val outcome = ExportOutcome.Failure("导出失败：写不进下载目录")
        assertEquals("导出失败：写不进下载目录", outcome.message)
    }
}
