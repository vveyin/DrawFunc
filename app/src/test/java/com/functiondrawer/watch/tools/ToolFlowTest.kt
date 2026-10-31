// 功能界面（#9）的工具目录与状态机测试。
//
// 这个测试文件之所以能离线跑：ui/FunctionTools.kt **不依赖 Compose/Android**，
// 只 import engine.GPoint。工具分类、每步要选什么、什么时候算完成，
// 都是纯规则 —— 用 tools/run-tool-tests.ps1 十几秒就能验完。
package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.segmentOf
import com.functiondrawer.watch.ui.ANGLE_DEFAULT_ARM
import com.functiondrawer.watch.ui.FunctionFileFormat
import com.functiondrawer.watch.ui.FunctionTool
import com.functiondrawer.watch.ui.SCALAR_NAME_RESERVED
import com.functiondrawer.watch.ui.SelectionKind
import com.functiondrawer.watch.ui.ToolFlow
import com.functiondrawer.watch.ui.ToolSection
import com.functiondrawer.watch.ui.ToolSelection
import com.functiondrawer.watch.ui.ToolStepResult
import com.functiondrawer.watch.ui.angleFromLines
import com.functiondrawer.watch.ui.functionsToSave
import com.functiondrawer.watch.ui.hasNoExpression
import com.functiondrawer.watch.ui.lowerLetters
import com.functiondrawer.watch.ui.nextScalarName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFlowTest {

    private fun p(x: Double, y: Double) = ToolSelection.PointSel(GPoint(x, y))

    // ---- 目录本身 ----

    @Test
    fun `七栏都有小标题且工具不重复`() {
        // 原本是 5 栏；后面按用户要求往下追加了「圆」「圆锥曲线」两栏。
        assertEquals(7, ToolSection.entries.size)
        assertEquals(
            listOf("基本工具", "度量", "变换", "作图", "直线", "圆", "圆锥曲线"),
            ToolSection.entries.map { it.title }
        )
        val all = FunctionTool.entries.map { it.label }
        assertEquals("工具不能重名", all.size, all.toSet().size)
        // 每一栏至少有一个工具（界面上标题下面不能是空的）
        ToolSection.entries.forEach { s ->
            assertTrue("${s.title} 栏是空的", FunctionTool.bySection(s).isNotEmpty())
        }
    }

    @Test
    fun `用户点名的工具都在目录里`() {
        val labels = FunctionTool.entries.map { it.label }.toSet()
        listOf(
            "移动", "描点",
            "角度", "距离", "面积",
            "轴对称", "中心对称",
            "中点", "垂线", "中垂线", "平行线", "角平分线", "切线",
            "线段", "直线", "射线"
        ).forEach { assertTrue("缺工具：$it", it in labels) }
    }

    @Test
    fun `分栏与用户描述一致`() {
        assertEquals(
            listOf("移动", "描点"),
            FunctionTool.bySection(ToolSection.BASIC).map { it.label }
        )
        assertEquals(
            listOf("角度", "距离", "面积"),
            FunctionTool.bySection(ToolSection.MEASURE).map { it.label }
        )
        assertEquals(
            listOf("轴对称", "中心对称"),
            FunctionTool.bySection(ToolSection.TRANSFORM).map { it.label }
        )
        assertEquals(
            listOf("中点", "垂线", "中垂线", "平行线", "角平分线", "切线"),
            FunctionTool.bySection(ToolSection.CONSTRUCT).map { it.label }
        )
        assertEquals(
            listOf("线段", "直线", "射线"),
            FunctionTool.bySection(ToolSection.LINE).map { it.label }
        )
    }

    /**
     * "没点击到直线不退出该状态，直到点击到直线为止" / "没选择到不退出" ——
     * 用户明确点名的这几个工具必须带上这个标志。
     */
    @Test
    fun `不选到合法目标就不退出的工具集合正确`() {
        val staying = FunctionTool.entries.filter { it.staysUntilValid }.map { it.label }.toSet()
        assertEquals(setOf("轴对称", "中心对称", "垂线", "平行线", "切线"), staying)
    }

    // ---- 状态机 ----

    @Test
    fun `移动与描点不需要选目标`() {
        for (t in listOf(FunctionTool.MOVE, FunctionTool.PLOT_POINT)) {
            val flow = ToolFlow(t)
            assertNull("${t.label} 不该要求选东西", flow.expected())
            assertFalse(flow.isComplete())
        }
    }

    @Test
    fun `距离要两个点`() {
        val flow = ToolFlow(FunctionTool.DISTANCE)
        assertEquals(SelectionKind.POINT, flow.expected())
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertEquals(SelectionKind.POINT, flow.expected())
        assertFalse("只给一个点不算完", flow.isComplete())
        assertTrue(flow.accept(p(3.0, 4.0)))
        assertTrue(flow.isComplete())
        assertEquals(2, flow.points.size)
    }

    @Test
    fun `该选点时给了曲线不算数`() {
        val flow = ToolFlow(FunctionTool.DISTANCE)
        assertFalse(flow.accept(ToolSelection.CurveSel(7L)))
        assertFalse(flow.accept(ToolSelection.LineSel(9L)))
        assertEquals("不合法的选择不该被记下来", 0, flow.points.size)
        assertEquals(SelectionKind.POINT, flow.expected())
    }

    // ---- 角度：两条路（用户："先点击两条线段或者调用线段工具点击三个点创建两条线段……再创建角度"）----

    @Test
    fun `角度走主路_点两条线段`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        // 第 0 步两种都认（[ToolStep.altKind]），但**给了线段就走主路**
        assertEquals(SelectionKind.LINE, flow.expected())
        assertTrue(flow.accept(ToolSelection.LineSel(11L)))
        assertFalse("还没选够，一条边量不出角", flow.isComplete())
        assertFalse("走的是线段那条路", flow.altBranch)
        assertEquals(SelectionKind.LINE, flow.expected())
        assertTrue(flow.accept(ToolSelection.LineSel(12L)))
        assertTrue(flow.isComplete())
        assertEquals("两条边两步，一步都不能多", 2, flow.acceptedCount)
        assertEquals(0, flow.points.size)
    }

    @Test
    fun `角度走岔路_点三个点会先建两条线段`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        assertTrue("第一步给点 = 走「连点三个点」那条路", flow.accept(p(0.0, 0.0)))
        assertTrue("选了点就要记下走了岔路", flow.altBranch)
        assertEquals("岔路第 2 步是顶点", SelectionKind.POINT, flow.expected())
        assertFalse("两个点还量不出角", flow.isComplete())
        assertTrue(flow.accept(p(3.0, 0.0)))
        assertEquals(SelectionKind.POINT, flow.expected())
        assertTrue(flow.accept(p(3.0, 4.0)))
        assertTrue(flow.isComplete())
        assertEquals("三个点就是三步", 3, flow.acceptedCount)
        assertEquals(3, flow.points.size)
    }

    @Test
    fun `角度的岔路一旦定下来就不会中途改`() {
        // 选了第一个点之后再给一条线段：不算数（这一轮已经是"三个点"那条路了）。
        // 这是"任何一次点击都有明确结果"的另一面：不许悄悄把已经选的东西
        // 解释成另一种东西 —— 想换路就 reset 重来。
        val flow = ToolFlow(FunctionTool.ANGLE)
        assertTrue(flow.accept(p(0.0, 0.0)))
        val rejected = flow.step(ToolSelection.LineSel(3L))
        assertTrue(rejected is ToolStepResult.Rejected)
        assertTrue((rejected as ToolStepResult.Rejected).message.isNotBlank())
        assertEquals("拒绝的那一下不该推进", 1, flow.acceptedCount)
    }

    @Test
    fun `reset 之后岔路也清掉_可以重新选线段`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.altBranch)
        flow.reset()
        assertFalse("重来之后从第一步重新分路", flow.altBranch)
        assertEquals(SelectionKind.LINE, flow.expected())
        assertTrue(flow.accept(ToolSelection.LineSel(1L)))
        assertTrue(flow.accept(ToolSelection.LineSel(2L)))
        assertTrue(flow.isComplete())
    }

    @Test
    fun `提示语把两条路都说出来`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        val hint = flow.hintText()
        assertTrue("提示语要提到线段：$hint", hint.contains("线段"))
        assertTrue("提示语要提到点：$hint", hint.contains("点"))
    }

    // ---- 从两条线段量角（纯几何，界面那两路共用它）----

    @Test
    fun `两条共顶点的线段量出直角`() {
        // 两条线段共用顶点 (0,0)：一条沿 +x 走 3，一条沿 +y 走 4
        val l1 = segmentOf(GPoint(0.0, 0.0), GPoint(3.0, 0.0))
        val l2 = segmentOf(GPoint(0.0, 0.0), GPoint(0.0, 4.0))
        val a = angleFromLines(l1, l2)
        requireNotNull(a)
        assertEquals(0.0, a.vertex.x, 1e-9)
        assertEquals(0.0, a.vertex.y, 1e-9)
        assertEquals("两条边也是直角", 90.0, a.degrees, 1e-9)
        // 边的长度用**线段自己的真长度**（决定扇形半径，见 ANGLE_ARM_RATIO）
        assertEquals(3.0, com.functiondrawer.watch.engine.distance(a.vertex, a.arm1), 1e-9)
        assertEquals(4.0, com.functiondrawer.watch.engine.distance(a.vertex, a.arm2), 1e-9)
    }

    @Test
    fun `顶点在端点时边朝着另一个端点走`() {
        // 两条线段在 (1,1) 相交，但**顶点不是任何一条的端点**（延长后相交）：
        // 这时边朝"线段整体所在的哪一侧"，也就是中点方向
        val l1 = segmentOf(GPoint(0.0, 1.0), GPoint(4.0, 1.0))
        val l2 = segmentOf(GPoint(2.0, 0.0), GPoint(2.0, 4.0))
        val a = angleFromLines(l1, l2)
        requireNotNull(a)
        assertEquals(2.0, a.vertex.x, 1e-9)
        assertEquals(1.0, a.vertex.y, 1e-9)
        assertEquals(90.0, a.degrees, 1e-9)
    }

    @Test
    fun `平行或共线的两条线构不成角`() {
        assertNull(
            "平行线没有交点",
            angleFromLines(
                segmentOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0)),
                segmentOf(GPoint(0.0, 2.0), GPoint(1.0, 2.0))
            )
        )
        assertNull(
            "共线（180°/0°）也画不出角",
            angleFromLines(
                segmentOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0)),
                segmentOf(GPoint(0.0, 0.0), GPoint(2.0, 0.0))
            )
        )
        assertNull(
            "退化线段（两点重合）不能当边",
            angleFromLines(
                segmentOf(GPoint(0.0, 0.0), GPoint(0.0, 0.0)),
                segmentOf(GPoint(0.0, 0.0), GPoint(2.0, 0.0))
            )
        )
    }

    @Test
    fun `无限直线用默认边长_并按锐角约定取向`() {
        // 两条直线：一条 x 轴、一条 y 轴。都是无限长 → 边长用 ANGLE_DEFAULT_ARM
        val l1 = GLine(GPoint(0.0, 0.0), GPoint(1.0, 0.0), GLineKind.LINE)
        val l2 = GLine(GPoint(0.0, 0.0), GPoint(0.0, 1.0), GLineKind.LINE)
        val a = angleFromLines(l1, l2)
        requireNotNull(a)
        assertEquals(90.0, a.degrees, 1e-9)
        assertEquals(
            "无限直线没有真长度，用默认边长",
            ANGLE_DEFAULT_ARM,
            com.functiondrawer.watch.engine.distance(a.vertex, a.arm1),
            1e-9
        )
    }

    // ---- 画布命中判定 ----
    //
    // ⚠️ 这里原来还有 3 条测试（多边形内外/自交、角张开区域），测的是
    // `pointInPolygon` / `angleWedgeContains`。用户更正了口径之后
    // "角度、面积和距离不参与画布命中判定"，那两个函数没有任何调用点，
    // 于是函数与它们的测试一起删掉了（见 `FunctionTools.kt` 末尾那段说明）。
    // 现在画布上参与命中的只有三类：点、线段/直线、函数曲线 ——
    // 它们的判定都在 `GraphState`（Compose 文件，离线脚本编不到），
    // 其中"函数曲线"用的是 `GraphCanvas.hitTestCurves`（也是 Compose 文件）。

    @Test
    fun `面积要点回第一个点才算完`() {
        val flow = ToolFlow(FunctionTool.AREA)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.accept(p(1.0, 0.0)))
        assertFalse("两个点还不够围成面", flow.isComplete())
        assertTrue(flow.accept(p(1.0, 1.0)))
        assertFalse("还没点回起点", flow.isComplete())
        assertTrue("点回第一个点 = 结束", flow.accept(p(0.0, 0.0)))
        assertTrue(flow.isComplete())
        assertEquals("结束那一下不该再算一个顶点", 3, flow.points.size)
    }

    @Test
    fun `轴对称先对象后直线`() {
        // ---- 第一步是"选对象"：点 / 曲线 / 直线**都算对象** ----
        //
        // 这一条本轮改过（以前断言"第一步就给直线不合法"）。用户报的 bug 是
        // "轴对称和中心对称在函数图像页面都点不动"，查出来的根因之一就是
        // 这一步只认点：用户在图上点一条曲线，界面会当成"在这儿描一个新点"，
        // 做出来的东西完全不是他要的。现在按用户原话实现：
        // "先点对象（点、函数/方程、直线都算），再点一条直线当对称轴"。
        val onCurve = ToolFlow(FunctionTool.AXIS_SYMMETRY)
        assertTrue("第一步给曲线 = 选中了对象", onCurve.accept(ToolSelection.CurveSel(3L)))
        assertEquals("对象选完就该选轴了", SelectionKind.LINE, onCurve.expected())

        val onLine = ToolFlow(FunctionTool.AXIS_SYMMETRY)
        assertTrue("一条线段也是一个对象", onLine.accept(ToolSelection.LineSel(1L)))

        val flow = ToolFlow(FunctionTool.AXIS_SYMMETRY)
        assertEquals(SelectionKind.POINT, flow.expected())
        assertTrue(flow.accept(p(1.0, 1.0)))
        assertEquals(SelectionKind.LINE, flow.expected())
        assertFalse("该给对称轴时给点不合法", flow.accept(p(2.0, 2.0)))
        assertTrue(flow.accept(ToolSelection.LineSel(5L)))
        assertTrue(flow.isComplete())
        // 对象和轴是**两步两个槽**：轴选完之后 pickedObjectId 是轴，但对象要靠
        // selectionAt(0) 取（以前两者共用一个槽，"被变换的对象"会变成那条轴本身）
        assertEquals(5L, flow.pickedObjectId)
        assertEquals(ToolSelection.PointSel(GPoint(1.0, 1.0)), flow.selectionAt(0))
        assertEquals(ToolSelection.LineSel(5L), flow.selectionAt(1))
    }

    @Test
    fun `选中的对象 id 不再被算成一步`() {
        // 用户报的 bug 根因 1：以前 acceptedCount = 点数 + (对象 id ?: 0)，
        // 而 PointSel 自己就带对象 id，于是"距离"点**一个**点就被判成选够了。
        val flow = ToolFlow(FunctionTool.DISTANCE)
        assertTrue(flow.accept(ToolSelection.PointSel(GPoint(0.0, 0.0), objectId = 42L)))
        assertEquals("一次选择就是一步", 1, flow.acceptedCount)
        assertFalse("只给一个点不算完", flow.isComplete())
        assertEquals(SelectionKind.POINT, flow.expected())
        assertEquals("还差一步", 1, flow.stepsLeft)
        assertTrue(flow.accept(ToolSelection.PointSel(GPoint(3.0, 4.0), objectId = 43L)))
        assertTrue(flow.isComplete())
    }

    @Test
    fun `同一个点选两次会被明确拒绝`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        assertTrue(flow.accept(p(0.0, 0.0)))
        val again = flow.step(p(0.0, 0.0))
        assertTrue("重复的点不该算一步", again is ToolStepResult.Rejected)
        assertEquals(1, flow.acceptedCount)
        assertTrue(
            "拒绝时必须说明白",
            (again as ToolStepResult.Rejected).message.isNotBlank()
        )
    }

    @Test
    fun `面积收尾之后 points 就是全部顶点`() {
        // 收尾那一下**不**往 points 里塞点（那是第一个点的坐标），所以
        // 多边形的顶点就是 points 本身 —— 界面侧以前写 `pts.dropLast(1)`，
        // 白白丢掉一个真顶点，于是面积对象一个都建不出来。
        val flow = ToolFlow(FunctionTool.AREA)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.accept(p(1.0, 0.0)))
        assertTrue(flow.accept(p(1.0, 1.0)))
        assertTrue("点回第一个点 = 结束", flow.accept(p(0.0, 0.0)))
        assertTrue(flow.isComplete())
        assertEquals("顶点一个都不能少", 3, flow.points.size)
    }

    @Test
    fun `面积顶点不够就点回起点会被拒绝并说明还差几个`() {
        val flow = ToolFlow(FunctionTool.AREA)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.accept(p(1.0, 0.0)))
        val early = flow.step(p(0.0, 0.0))
        assertTrue("两个点围不成面", early is ToolStepResult.Rejected)
        assertFalse(flow.isComplete())
        assertTrue((early as ToolStepResult.Rejected).message.contains("还差"))
    }

    /**
     * 用户点名的规则："任何一次点击、任何顺序的点选都必须有明确结果 —— 要么推进、
     * 要么给一句 toast 说明还差什么。" 这条测试把"每种工具 × 每一步 × 每种选择"
     * 全试一遍（含反复选同一个点、选够之后继续点），要求：
     *   * 不许抛异常；
     *   * 拒绝时**必须**带一句非空的说明；
     *   * 接受时步数只能 +1 或不加（不能倒退、不能跳步）。
     */
    @Test
    fun `没有死路_任何工具任何一步的任何选择都有明确结果`() {
        val selections = listOf(
            p(0.5, 0.5), p(1.5, 1.5), p(0.5, 0.5),
            ToolSelection.CurveSel(1L), ToolSelection.LineSel(2L)
        )
        for (tool in FunctionTool.entries) {
            val flow = ToolFlow(tool)
            var before = flow.acceptedCount
            for (sel in selections) {
                val result = flow.step(sel)
                val after = flow.acceptedCount
                when (result) {
                    is ToolStepResult.Rejected -> assertTrue(
                        "${tool.label} 拒绝时必须给出说明",
                        result.message.isNotBlank()
                    )

                    is ToolStepResult.Accepted -> assertTrue(
                        "${tool.label} 一次只能推进（或收尾）",
                        after == before || after == before + 1
                    )
                }
                before = after
            }
            // 走完这一串之后，状态机必须仍然能回答"下一步要选什么"
            flow.expected()
            flow.isComplete()
        }
    }

    @Test
    fun `提示语说清这一步要选什么`() {
        val flow = ToolFlow(FunctionTool.ANGLE)
        // 第 0 步两条路都认 → 步数按主路（两条线段）算：两条边 = 2 步
        assertEquals(
            "点第一条边（线段/直线），或者点角的第一个点（第 1/2 步）",
            flow.hintText()
        )
        // 选了第一个点之后走岔路：后两步是"顶点、第三个点"，一共 3 步
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertEquals("点这个角的顶点（第 2/3 步）", flow.hintText())
        // 轴对称/中心对称的第一步是"选对象"，提示语必须让人知道点/曲线/直线都行
        val axis = ToolFlow(FunctionTool.AXIS_SYMMETRY)
        assertTrue("提示语要提到对象", axis.hintText().contains("对象"))
        // 移动/描点没有步骤，退回工具自己的提示语
        assertEquals(FunctionTool.MOVE.hint, ToolFlow(FunctionTool.MOVE).hintText())
    }

    @Test
    fun `reset 之后工具本身不变，可以连续做下一个对象`() {
        val flow = ToolFlow(FunctionTool.DISTANCE)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.accept(p(3.0, 4.0)))
        assertTrue(flow.isComplete())
        // 出完结果由界面调 reset：工具还是「距离」，但选择清空了
        flow.reset()
        assertEquals(FunctionTool.DISTANCE, flow.tool)
        assertEquals(0, flow.acceptedCount)
        assertFalse(flow.isComplete())
        assertEquals(SelectionKind.POINT, flow.expected())
        assertEquals("重来之后第一步的提示语", "点第一个点（第 1/2 步）", flow.hintText())
    }

    // ---- 标量对象的命名规则 ----
    //
    // 用户原话（本轮改的口径）："标量和滑动条的名字可以不用严格分出使用的字母，
    // 只要后台能识别就行，标量也可以用 a，滑动条也可以用 f。至于 e、x、y 这些
    // 可能冲突的字母，请你给函数编辑界面输入的这些字母单独分组编 id，
    // 这样也不用担心字母冲突的问题。"

    @Test
    fun `标量名从小写字母序列的第一个开始_不再跳字母`() {
        val taken = mutableSetOf<String>()
        val got = (1..60).map { nextScalarName(taken).also { n -> taken += n } }
        // 以前第一个可用的是 f（跳过 a~d / e），现在**从 a 开始**：
        // "撞上引擎里有含义的字母"改由绑定表解决（见 PointCoordinatesTest 那边）
        assertEquals("第一个可用的标量名是 a", "a", got.first())
        got.forEach { n ->
            assertTrue("$n 必须是小写字母", n.all { it in 'a'..'z' })
        }
        assertEquals("名字不能重复", got.size, got.toSet().size)
        // 引擎里有含义的名字**也会**被分配出去（这是有意的）：标量可以叫 a
        listOf("a", "b", "c", "d", "e", "x", "y", "t").forEach {
            assertTrue("$it 现在是合法标量名", it in got)
        }
        // 已被占用的名字仍然要跳过（用户把某条函数改名成 f(x)，标量就不能再叫 f）
        assertEquals("b", nextScalarName(setOf("a")))
        assertEquals("c", nextScalarName(setOf("a", "b")))
        // 双射 26 进制仍然成立：第 26 个之后进位成 aa
        assertEquals("aa", lowerLetters(26))
    }

    @Test
    fun `引擎里有含义的名字仍然列在那张表里`() {
        // 这张表**不再是取名时的跳过表**（标量可以叫 a、x、e），它现在的作用是
        // "告诉编辑器：这些字母引擎自己会先认领，必须让用户选定它指谁"。
        // 所以表的内容本身还要钉住 —— 少了谁，编辑器就不会提示那个字母。
        listOf("a", "b", "c", "d").forEach {
            assertTrue("$it 是可绑定参数", it in SCALAR_NAME_RESERVED)
        }
        listOf("x", "y", "t", "θ", "z").forEach {
            assertTrue("$it 是采样变量/复平面变量", it in SCALAR_NAME_RESERVED)
        }
        listOf("e", "i", "pi", "π", "tau", "τ", "phi", "φ", "inf", "infinity", "nan").forEach {
            assertTrue("$it 是引擎常量", it in SCALAR_NAME_RESERVED)
        }
        // 反过来：引擎里没有含义的普通字母**不该**在表里（在里面会让编辑器白提示一遍）
        listOf("f", "g", "h", "w", "aa").forEach {
            assertFalse("$it 引擎里没有含义", it in SCALAR_NAME_RESERVED)
        }
    }

    @Test
    fun `平行线先线后点`() {
        val flow = ToolFlow(FunctionTool.PARALLEL)
        assertEquals(SelectionKind.LINE, flow.expected())
        assertTrue(flow.accept(ToolSelection.LineSel(3L)))
        assertEquals(SelectionKind.POINT, flow.expected())
        assertTrue(flow.accept(p(-2.0, 5.0)))
        assertTrue(flow.isComplete())
    }

    @Test
    fun `切线只认点且要不到了就一直等`() {
        val flow = ToolFlow(FunctionTool.TANGENT)
        assertEquals(SelectionKind.POINT, flow.expected())
        assertFalse(flow.accept(ToolSelection.LineSel(2L)))
        assertTrue(FunctionTool.TANGENT.staysUntilValid)
        assertTrue(flow.accept(p(0.0, 0.0)))
        assertTrue(flow.isComplete())
    }

    @Test
    fun `每两个点的工具都要两个点`() {
        for (t in listOf(
            FunctionTool.MIDPOINT, FunctionTool.PERP_BISECTOR,
            FunctionTool.SEGMENT, FunctionTool.STRAIGHT, FunctionTool.RAY
        )) {
            val flow = ToolFlow(t)
            assertEquals("${t.label} 第一步", SelectionKind.POINT, flow.expected())
            assertTrue("${t.label} 第一个点", flow.accept(p(0.0, 0.0)))
            assertFalse("${t.label} 一个点不该算完", flow.isComplete())
            assertTrue("${t.label} 第二个点", flow.accept(p(1.0, 1.0)))
            assertTrue("${t.label} 两点之后应该完成", flow.isComplete())
        }
    }

    @Test
    fun `reset 清掉所有选择`() {
        val flow = ToolFlow(FunctionTool.SEGMENT)
        assertTrue(flow.accept(p(1.0, 2.0)))
        flow.reset()
        assertEquals(0, flow.points.size)
        assertNull(flow.pickedObjectId)
        assertNull(flow.pickedCurveId)
        assertEquals(SelectionKind.POINT, flow.expected())
    }

    @Test
    fun `切线记下选中的曲线`() {
        val flow = ToolFlow(FunctionTool.TANGENT)
        assertTrue(flow.accept(ToolSelection.PointSel(GPoint(1.0, 1.0), objectId = 42L)))
        assertEquals(42L, flow.pickedCurveId)
        assertEquals(42L, flow.pickedObjectId)
    }

    // =============================================================================
    // 「没有表达式的函数」不进文件
    // =============================================================================
    //
    // 用户报的 bug："保存文件的时候会在文件里添加一个没有表达式的函数。"
    // 起因是 `+` 会**先建一条空函数再进编辑器**，只要有别路径让它活到保存那一刻，
    // 它就会被写进 `.fdf`（列表里看着是空的、打开文件后也是一条空行）。
    // 判据只有一条（`hasNoExpression`），编辑器判空和保存过滤共用它 ——
    // 两处各写一份迟早会出现"编辑器认为它是空的、存档却把它写进去"。

    @Test
    fun `一个字都没写的函数才算空`() {
        val blank = PlotFunction(id = 1L, mode = PlotMode.EXPLICIT, src1 = "")
        assertTrue("没有表达式 = 空", blank.hasNoExpression())
        // 只敲了空格/换行和什么都没敲是一个意思（用 isBlank 而不是 isEmpty）
        assertTrue(PlotFunction(id = 2L, mode = PlotMode.EXPLICIT, src1 = "   ").hasNoExpression())
        assertTrue(PlotFunction(id = 3L, mode = PlotMode.EXPLICIT, src1 = "\n").hasNoExpression())
        // 有内容就不是空
        assertFalse(PlotFunction(id = 4L, mode = PlotMode.EXPLICIT, src1 = "sin(x)").hasNoExpression())
        // 参数方程只写了一栏**不算空**：那是写了一半的式子，引擎会明确报"另一栏没填"，
        // 在界面上报错用户能改；把它悄悄丢掉反而更糟
        assertFalse(
            PlotFunction(id = 5L, mode = PlotMode.PARAMETRIC, src1 = "t", src2 = "")
                .hasNoExpression()
        )
        assertFalse(
            PlotFunction(id = 6L, mode = PlotMode.PARAMETRIC, src1 = "", src2 = "1-cos(t)")
                .hasNoExpression()
        )
    }

    @Test
    fun `空函数不会被写进 FDF1 文本`() {
        val draft = PlotFunction(id = 7L, mode = PlotMode.EXPLICIT, src1 = "  ", name = "b(x)")
        val real = PlotFunction(id = 2L, mode = PlotMode.EXPLICIT, src1 = "sin(x)", name = "a(x)")
        // ---- 过滤本身 ----
        assertEquals(
            "空函数要被跳过、真函数要留着",
            listOf(2L),
            functionsToSave(listOf(draft, real)).map { it.id }
        )
        // ---- 端到端：走真编码器编一遍，文件里不能有那条空函数 ----
        val text = FunctionFileFormat.encode(
            FunctionFileFormat.FileState(
                functions = functionsToSave(listOf(draft, real)),
                selectedId = 2L
            )
        )
        // 一条函数 = 一条 `F|` 开头的记录行
        assertEquals(
            "文件里的函数记录只能有一条",
            1,
            text.lineSequence().count { it.startsWith("F|") }
        )
        // 再解回来确认：读出来就是那条真函数（不是"被跳过"= 数据丢了）
        val back = FunctionFileFormat.decode(text)
        assertEquals(1, back?.functions?.size)
        assertEquals("sin(x)", back?.functions?.first()?.src1)
        // ---- 允许"一个函数都没有"的文件（只画了几何对象/只有滑动条）：不是报错，是正常 ----
        val objectsOnly = FunctionFileFormat.encode(FunctionFileFormat.FileState(functions = emptyList()))
        assertEquals(0, objectsOnly.lineSequence().count { it.startsWith("F|") })
        assertTrue("空函数表也要能编解码", FunctionFileFormat.decode(objectsOnly) != null)
    }
}
