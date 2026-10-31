package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.LineStyle
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.SnapKind
import com.functiondrawer.watch.ui.FunctionFileFormat
import com.functiondrawer.watch.ui.FunctionFileFormat.FileAngle
import com.functiondrawer.watch.ui.FunctionFileFormat.FileCoord
import com.functiondrawer.watch.ui.FunctionFileFormat.FileMeasureKind
import com.functiondrawer.watch.ui.FunctionFileFormat.FileObject
import com.functiondrawer.watch.ui.FunctionFileFormat.FileSettings
import com.functiondrawer.watch.ui.FunctionFileFormat.FileSlider
import com.functiondrawer.watch.ui.FunctionFileFormat.FileState
import com.functiondrawer.watch.ui.FunctionFileFormat.FileTransform
import com.functiondrawer.watch.ui.FunctionFileFormat.FileViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 「函数文件」（`.fdf`）格式的往返测试。
 *
 * 为什么必须离线钉住这一份：
 *   * 它是**用户的全部工作**唯一的落盘形式 —— 转义写错一个字符（比如名字里带 `|`）
 *     就会让一份文件从此读不回来，而手表上用户既看不到 logcat 也没法手工修文件；
 *   * 版本/坏行的规则是"故意的容错设计"（版本不认识就整体失败、坏行只丢自己），
 *     这种规则最容易被后来者"顺手改成更宽松/更严格"，必须有测试挡着；
 *   * 体积是这个格式存在的理由（用户要求"越节省空间越好"），
 *     所以这里连**字节数**都测 —— 见最后那条会打印三种方案体积的测试。
 */
class FunctionFileFormatTest {

    // ===================================================================================
    // 测试数据：把"一个真实用了一阵的图"该有的东西都放进去
    // ===================================================================================

    private fun f1() = PlotFunction(
        id = 1L,
        mode = PlotMode.EXPLICIT,
        src1 = "sin(x)+a",
        src2 = "",
        tMin = 0.0,
        tMax = 6.283185307179586,
        colorIndex = 0,
        visible = true,
        degrees = null,
        name = "a(x)",
        lineStyle = LineStyle.SOLID,
        widthScale = 1f
    )

    private fun f2() = PlotFunction(
        id = 2L,
        mode = PlotMode.IMPLICIT,
        src1 = "x^2+y^2=1",
        tMin = -3.141592653589793,
        tMax = 3.141592653589793,
        colorIndex = 3,
        visible = false,
        degrees = true,
        name = "单位圆",
        lineStyle = LineStyle.DASH_FINE,
        widthScale = 1.6f
    )

    /** 参数方程 + 度制关闭 + 粗虚线：把"每个字段都不是默认值"这条路走一遍。 */
    private fun f3() = PlotFunction(
        id = 3L,
        mode = PlotMode.PARAMETRIC,
        src1 = "cos(t)",
        src2 = "sin(2t)",
        tMin = -1.25,
        tMax = 6.5,
        colorIndex = 5,
        visible = true,
        degrees = false,
        name = "李萨如",
        lineStyle = LineStyle.DASH_COARSE,
        widthScale = 0.75f
    )

    private fun sample(): FileState = FileState(
        functions = listOf(f1(), f2(), f3()),
        sliders = listOf(
            FileSlider(
                id = 7L,
                param = "a",
                min = -5.0,
                max = 5.0,
                step = 0.1,
                value = 1.3,
                colorIndex = 0,
                visible = true
            )
        ),
        objects = listOf(
            FileObject.Point(
                id = 4L,
                name = "A",
                point = GPoint(1.5, -2.25),
                kind = SnapKind.ZERO,
                colorIndex = 1,
                visible = true
            ),
            FileObject.Line(
                id = 5L,
                name = "B",
                line = GLine(GPoint(0.0, 0.0), GPoint(2.0, 1.0), GLineKind.SEGMENT),
                role = "线段",
                colorIndex = 2,
                visible = false
            ),
            FileObject.Measure(
                id = 6L,
                kind = FileMeasureKind.ANGLE,
                value = 36.86989764584402,
                points = listOf(GPoint(0.0, 0.0), GPoint(1.0, 0.0), GPoint(1.0, 1.0)),
                pointNames = listOf("A", "B", "C"),
                colorIndex = 4,
                visible = true,
                showValue = false
            )
        ),
        transforms = listOf(
            FileTransform(
                functionId = 2L,
                axis = GLine(GPoint(-1.0, 0.0), GPoint(1.0, 0.0), GLineKind.LINE),
                center = null
            ),
            FileTransform(functionId = 3L, axis = null, center = GPoint(0.5, 0.5))
        ),
        settings = FileSettings(
            coord = FileCoord.POLAR,
            angle = FileAngle.DEG,
            showGrid = false,
            showAxes = true,
            showNumbers = true,
            showPresets = false,
            confirmCoordSwitch = true
        ),
        viewport = FileViewport(centerX = -1.25, centerY = 0.75, unitsPerPixel = 0.04285),
        selectedId = 3L
    )

    // ===================================================================================
    // 1. 往返无损
    // ===================================================================================

    @Test
    fun `完整状态编码再解码是完全一样的`() {
        val original = sample()
        val text = FunctionFileFormat.encode(original)
        val back = FunctionFileFormat.decode(text)
        assertNotNull("自己的版本一定要能读回来", back)
        assertEquals(original, back)
    }

    /** 每个字段都单独变一次：防止"某个字段压根没写进文件"被上面那条大断言掩盖。 */
    @Test
    fun `函数的每个字段都真的进了文件`() {
        val original = sample()
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(original))!!
        assertEquals(original.functions, back.functions)

        val fn = back.functions[1]
        assertEquals(2L, fn.id)
        assertEquals(PlotMode.IMPLICIT, fn.mode)
        assertEquals("x^2+y^2=1", fn.src1)
        assertEquals("", fn.src2)
        assertEquals(-3.141592653589793, fn.tMin, 0.0)
        assertEquals(3.141592653589793, fn.tMax, 0.0)
        assertEquals(3, fn.colorIndex)
        assertEquals(false, fn.visible)
        assertEquals(true, fn.degrees)
        assertEquals("单位圆", fn.name)
        assertEquals(LineStyle.DASH_FINE, fn.lineStyle)
        assertEquals(1.6f, fn.widthScale, 0.0f)
        // `null` 的度制（跟随全局设置）不能变成 false —— 那会让"跟随设置"失效
        assertNull(back.functions[0].degrees)
    }

    @Test
    fun `几何对象的顺序和三个子类都保住`() {
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(sample()))!!
        assertEquals(3, back.objects.size)
        assertTrue(back.objects[0] is FileObject.Point)
        assertTrue(back.objects[1] is FileObject.Line)
        assertTrue(back.objects[2] is FileObject.Measure)
        // 度量的名字是**现算**的（不单独存字段），要保证算出来和界面上一样
        assertEquals("角度（A，B，C）", back.objects[2].name)
    }

    @Test
    fun `滑动条与设置与视口都保住`() {
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(sample()))!!
        assertEquals(sample().sliders, back.sliders)
        assertEquals(sample().settings, back.settings)
        assertEquals(sample().viewport, back.viewport)
        assertEquals(3L, back.selectedId)
    }

    @Test
    fun `曲线对称规则往返（轴与中心两种）`() {
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(sample()))!!
        assertEquals(2, back.transforms.size)
        val axis = back.transforms[0].axis
        assertNotNull(axis)
        assertEquals(GLineKind.LINE, axis!!.kind)
        assertEquals(-1.0, axis.p.x, 0.0)
        assertNull(back.transforms[0].center)
        assertNull(back.transforms[1].axis)
        assertEquals(GPoint(0.5, 0.5), back.transforms[1].center)
    }

    @Test
    fun `空状态也能编码解码（不会变成 null）`() {
        val text = FunctionFileFormat.encode(FileState())
        assertEquals("FDF1", text.lines()[0])
        assertEquals(4, text.trimEnd('\n').lines().size)
        val back = FunctionFileFormat.decode(text)
        assertNotNull(back)
        assertEquals(FileState(), back)
    }

    // ===================================================================================
    // 1b. 名字绑定表（`B|函数id|字母|对象id`）
    //
    // 用户需求："标量和滑动条的名字可以不用严格分出使用的字母……至于 e、x、y 这些可能
    // 冲突的字母，请你给函数编辑界面输入的这些字母单独分组编 id。" —— 那张表要能存进文件。
    // 这一节钉住三件事：**空表不写一行**（老文件逐字节兼容）、非空表往返无损、
    // 以及坏记录只丢它自己。
    // ===================================================================================

    @Test
    fun `空绑定表一行都不写（老文件逐字节兼容）`() {
        // sample() 的 bindings 是默认空表 → 编码结果里**一条 B 记录都不该有**
        val text = FunctionFileFormat.encode(sample())
        assertFalse(
            "空表不该写出任何 B 记录",
            text.lines().any { it.startsWith("B|") }
        )
        // 反过来说：这份文本读回来还是空表（老文件正是这个形状）
        assertEquals(emptyMap<Long, Map<String, Long>>(), FunctionFileFormat.decode(text)!!.bindings)
    }

    @Test
    fun `名字绑定表往返（字母与对象 id 都保住）`() {
        val original = sample().copy(
            bindings = mapOf(
                1L to mapOf("a" to 7L, "aa" to 4L),
                2L to mapOf("f" to 6L)
            )
        )
        val text = FunctionFileFormat.encode(original)
        // 一条绑定一行，写的时候排过序（同一个状态每次编码出同样的字节）
        assertTrue(text.contains("B|1|a|7\n"))
        assertTrue(text.contains("B|1|aa|4\n"))
        assertTrue(text.contains("B|2|f|6\n"))
        val back = FunctionFileFormat.decode(text)!!
        assertEquals(original.bindings, back.bindings)
        assertEquals(original, back)
    }

    @Test
    fun `坏掉的 B 记录只丢它自己`() {
        // 三种坏法都要被挡掉，而且**不能影响其它记录**（函数照样读得回来）
        val text = buildString {
            append("FDF1\n")
            append("F|9|E|0|1|0|6.283185307179586|sin(x)||-|||1.0\n")
            append("B|9|a|4\n")          // 好的一条
            append("B|9|A|5\n")          // 字母大写 → 不是标量名的形状，丢掉
            append("B|9|x\n")            // 字段不够，丢掉
            append("B|abc|a|5\n")        // 函数 id 不是整数，丢掉
            append("B|9|a|notanumber\n") // 对象 id 不是整数，丢掉
        }
        val back = FunctionFileFormat.decode(text)!!
        assertEquals(mapOf(9L to mapOf("a" to 4L)), back.bindings)
        assertEquals("函数那条记录不受影响", 1, back.functions.size)
    }

    @Test
    fun `同一个函数同一个字母出现两次时后写的赢`() {
        val text = "FDF1\nB|1|a|7\nB|1|a|8\n"
        assertEquals(mapOf(1L to mapOf("a" to 8L)), FunctionFileFormat.decode(text)!!.bindings)
    }

    // ===================================================================================
    // 2. 转义：只动那 5 个字符，而且真能救回记录结构
    // ===================================================================================

    @Test
    fun `转义表就是那五个字符`() {
        assertEquals("%7C", FunctionFileFormat.escape("|"))
        assertEquals("%0A", FunctionFileFormat.escape("\n"))
        assertEquals("%0D", FunctionFileFormat.escape("\r"))
        assertEquals("%25", FunctionFileFormat.escape("%"))
        assertEquals("%3B", FunctionFileFormat.escape(";"))
        // 逗号**不**转义：它只出现在"两个数字之间"，转义它只会让 max(x,3) 这种式子白白变大
        assertEquals("max(x,3)", FunctionFileFormat.escape("max(x,3)"))
        assertEquals("x^2+y^2=1", FunctionFileFormat.escape("x^2+y^2=1"))
    }

    @Test
    fun `不合法的百分号序列原样保留而不是丢字符`() {
        assertEquals("100%", FunctionFileFormat.unescape("100%"))
        assertEquals("%ZZ", FunctionFileFormat.unescape("%ZZ"))
        assertEquals("%2", FunctionFileFormat.unescape("%2"))
        assertEquals("a|b", FunctionFileFormat.unescape("a%7Cb"))
        assertEquals("%", FunctionFileFormat.unescape("%25"))
        // 转义再反解要回到原样（含多字节汉字：%XX 是**字节**级还是**字符**级都行，
        // 只要 encode/decode 自洽 —— 这里钉的是自洽性）
        val text = "角度|x%\n;A中"
        assertEquals(text, FunctionFileFormat.unescape(FunctionFileFormat.escape(text)))
    }

    @Test
    fun `名字里的竖线和换行不会撑开记录`() {
        val nasty = "a|x\ny;z%w\r"
        val state = FileState(
            functions = listOf(f1().copy(name = nasty, src1 = "1|2\n3")),
            selectedId = 1L
        )
        val text = FunctionFileFormat.encode(state)
        // 版本 + S + X + V + F 正好 5 行：名字里的换行**不能**多出一行来
        assertEquals(5, text.trimEnd('\n').split('\n').size)
        val back = FunctionFileFormat.decode(text)!!
        assertEquals(nasty, back.functions[0].name)
        assertEquals("1|2\n3", back.functions[0].src1)
    }

    @Test
    fun `度量点名表里的分号被转义（不会多出几个点）`() {
        val m = FileObject.Measure(
            id = 1L,
            kind = FileMeasureKind.DISTANCE,
            value = 2.0,
            points = listOf(GPoint(0.0, 0.0), GPoint(2.0, 0.0)),
            pointNames = listOf("A;B", "C")
        )
        val back = FunctionFileFormat.decode(
            FunctionFileFormat.encode(FileState(objects = listOf(m)))
        )!!
        assertEquals(listOf("A;B", "C"), (back.objects[0] as FileObject.Measure).pointNames)
        assertEquals(2, (back.objects[0] as FileObject.Measure).points.size)
    }

    // ===================================================================================
    // 3. 版本与坏行
    // ===================================================================================

    @Test
    fun `版本不认识就整体失败（不猜）`() {
        assertNull(FunctionFileFormat.decode("FDF2\nF|1|E|0|1|0|0|1|sin(x)"))
        assertNull(FunctionFileFormat.decode("FDF10\n"))
        assertNull(FunctionFileFormat.decode("fdf1\n"))
        assertNull(FunctionFileFormat.decode("F|1|E|0|1|0|0|1|sin(x)\n"))
        assertNull(FunctionFileFormat.decode(""))
        assertNull(FunctionFileFormat.decode("\n\n"))
    }

    @Test
    fun `版本对就算内容全坏也给一份空状态（能救多少救多少）`() {
        val back = FunctionFileFormat.decode("FDF1\nF|坏|坏|坏\nO|Q|bad\n")
        assertNotNull(back)
        assertEquals(0, back!!.functions.size)
        assertEquals(0, back.objects.size)
    }

    @Test
    fun `单条记录坏掉只丢它自己`() {
        val good = FunctionFileFormat.encode(sample())
        val text = good +
            "O|Q|坏记录\n" +                              // 不认识的对象子类型
            "F|不是数字|E|0|1|0|0|1|sin(x)\n" +            // id 坏了
            "F|1|E|0\n" +                                  // 字段不够
            "ZZZ|将来才有的记录\n" +                        // 不认识的记录类型：忽略，不算错
            "F|9|E|0|1|0|6.28|cos(x)\n"                    // 一条好的
        val back = FunctionFileFormat.decode(text)!!
        assertEquals("三条原有 + 一条好的", 4, back.functions.size)
        assertEquals(9L, back.functions.last().id)
        assertEquals("cos(x)", back.functions.last().src1)
        assertEquals(3, back.objects.size)
    }

    @Test
    fun `设置记录坏掉时退回默认值而不是丢掉整个文件`() {
        val text = "FDF1\nS|q|q|1|1|0|1|1\nF|1|E|0|1|0|6.28|sin(x)\n"
        val back = FunctionFileFormat.decode(text)!!
        assertEquals(FileSettings(), back.settings)
        assertEquals(1, back.functions.size)
    }

    @Test
    fun `乱七八糟的输入从不抛异常`() {
        val nasty = listOf(
            "", "\n", "FDF1", "|", "FDF1\n|", "FDF1\n|||||||||",
            "FDF1\n" + "|".repeat(5000),
            "FDF1\n" + "F".repeat(5000),
            "\u0000\u0001\u0002",
            "FDF1\nT|1|1,2,3|4,5\n",
            "FDF1\nV|NaN|Infinity|-Infinity\n",
            "FDF1\nO|M|1|a|2|A;B|1,2;3,4|0|1|1\n"
        )
        for (bad in nasty) {
            val r = runCatching { FunctionFileFormat.decode(bad) }
            assertTrue("decode 不该抛异常，输入=${bad.take(20)}，结果=$r", r.isSuccess)
        }
    }

    @Test
    fun `Windows 行尾也能读`() {
        val back = FunctionFileFormat.decode("FDF1\r\nF|1|E|0|1|0|6.28|sin(x)\r\n")!!
        assertEquals(1, back.functions.size)
        assertEquals("sin(x)", back.functions[0].src1)
    }

    // ===================================================================================
    // 4. 数字写法
    // ===================================================================================

    @Test
    fun `数字的短写法和科学计数法都能原样读回`() {
        val values = listOf(
            0.0, 1.0, -1.0, 0.1, -5.0, 6.283185307179586, 1e-9, 1e9, 1e10, 12345.6789,
            -0.000123456, 999999.9999, 1.0E-7, 2.5E20
        )
        for (v in values) {
            val text = FunctionFileFormat.encode(FileState(viewport = FileViewport(v, 0.0, 0.0)))
            val back = FunctionFileFormat.decode(text)!!
            assertEquals("值 $v 的写法应该是往返无损的", v, back.viewport.centerX, 0.0)
        }
    }

    @Test
    fun `整数不写成 1_0 这种带尾巴的形式（省字节）`() {
        val text = FunctionFileFormat.encode(FileState(viewport = FileViewport(1.0, -2.0, 0.5)))
        assertTrue("视口那一行应该是 V|1|-2|0.5，实际=${text.lines()[3]}", text.contains("V|1|-2|0.5"))
    }

    @Test
    fun `NaN 和负零也往返（不特殊处理、也不抛）`() {
        val state = FileState(viewport = FileViewport(Double.NaN, -0.0, 0.0))
        val back = FunctionFileFormat.decode(FunctionFileFormat.encode(state))!!
        assertTrue(back.viewport.centerX.isNaN())
        // 负零要保住符号：`(-0.0).toLong()` 会丢掉它，那样"无损"就有一个例外了
        assertTrue(
            "负零丢了符号",
            1.0 / back.viewport.centerY == Double.NEGATIVE_INFINITY
        )
    }

    // ===================================================================================
    // 5. 文件列表要用的统计
    // ===================================================================================

    @Test
    fun `统计个数只数记录前缀`() {
        val s = FunctionFileFormat.summarize(FunctionFileFormat.encode(sample()))
        assertEquals(3, s.functions)
        assertEquals(1, s.sliders)
        assertEquals(3, s.objects)
        assertTrue(s.versionOk)
    }

    @Test
    fun `版本不对时统计不给数`() {
        val s = FunctionFileFormat.summarize("JUNK\nF|1|E|0|1|0|0|1|sin(x)\n")
        assertEquals(0, s.functions)
        assertEquals(false, s.versionOk)
    }

    @Test
    fun `列表第二行的小字`() {
        assertEquals("3 函数 · 1 滑动条 · 3 对象 · 412 B",
            FunctionFileFormat.Summary(3, 1, 3, true).text(412L))
        assertEquals("1 函数 · 2 KB", FunctionFileFormat.Summary(1, 0, 0, true).text(2048L))
        assertEquals("空文件 · 0 B", FunctionFileFormat.Summary(0, 0, 0, true).text(0L))
        assertEquals("412 B", FunctionFileFormat.fileSizeText(412L))
        assertEquals("1 KB", FunctionFileFormat.fileSizeText(1024L))
        assertEquals("2.5 KB", FunctionFileFormat.fileSizeText(2560L))
        assertEquals("—", FunctionFileFormat.fileSizeText(-1L))
    }

    // ===================================================================================
    // 6. 文件名的规则
    // ===================================================================================

    @Test
    fun `补扩展名与剥扩展名`() {
        assertEquals("我的曲线.fdf", FunctionFileFormat.fileNameFor("我的曲线"))
        assertEquals("我的曲线.fdf", FunctionFileFormat.fileNameFor("  我的曲线  "))
        // 用户手打（或粘）了扩展名也不能变成 a.fdf.fdf
        assertEquals("a.fdf", FunctionFileFormat.fileNameFor("a.fdf"))
        assertEquals("我的曲线", FunctionFileFormat.displayName("我的曲线.fdf"))
        // 不是 .fdf 的文件名原样返回（列表理论上不会出现，但别崩）
        assertEquals("readme.txt", FunctionFileFormat.displayName("readme.txt"))
        assertEquals("fdf", FunctionFileFormat.displayName("fdf"))
        assertEquals("我的曲线", FunctionFileFormat.normalizeBaseName(" 我的曲线.FDF "))
    }

    @Test
    fun `空名字明确拒绝并说明原因`() {
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError("", emptySet()))
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError("     ", emptySet()))
        // 只打了扩展名：剥掉之后是空名字
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError(".fdf", emptySet()))
        // 以点开头 = 隐藏文件，用户"存了但找不到"
        assertTrue(FunctionFileFormat.baseNameError(".hidden", emptySet())!!.contains("点"))
    }

    @Test
    fun `非法字符与超长名字都被拒绝`() {
        assertTrue(FunctionFileFormat.baseNameError("a/b", emptySet())!!.contains("不能有"))
        assertTrue(FunctionFileFormat.baseNameError("a\\b", emptySet())!!.contains("不能有"))
        assertTrue(FunctionFileFormat.baseNameError("a|b", emptySet())!!.contains("不能有"))
        assertTrue(FunctionFileFormat.baseNameError("a\nb", emptySet())!!.contains("不能有"))
        assertTrue(FunctionFileFormat.baseNameError("x".repeat(41), emptySet())!!.contains("太长"))
    }

    @Test
    fun `重名不区分大小写地拒绝`() {
        assertEquals("已有同名文件", FunctionFileFormat.baseNameError("sin", setOf("sin")))
        assertEquals("已有同名文件", FunctionFileFormat.baseNameError("Sin", setOf("sin")))
        // 重命名时调用方会把"被改的那个自己"从 taken 里排除掉，所以改大小写是允许的
        assertNull(FunctionFileFormat.baseNameError("Sin", setOf("其他")))
    }

    @Test
    fun `正常名字通过`() {
        assertNull(FunctionFileFormat.baseNameError("我的曲线", emptySet()))
        assertNull(FunctionFileFormat.baseNameError("  sin(x) 图像  ", emptySet()))
        assertNull(FunctionFileFormat.baseNameError("a".repeat(40), emptySet()))
    }

    /**
     * 「确定」按下去时名字为空到底怎么处理 —— 这一条是**定死的产品行为**：
     *
     *   对话框**不关闭**、标题下面显示"名字不能为空"，**不**像
     *   `SaveName.kt` 的 `sanitizeFileName`（存图那条路）那样静默退回时间戳名字。
     *   理由：存函数文件是用户主动起的名字，悄悄改掉之后他在列表里认不出哪个是自己的；
     *   而"退不出来"这种更糟的情况由对话框自己兜（按钮永远可见 + 遮罩 + 返回键三条路）。
     *
     * 顺带钉住写盘层的兜底：`fileNameFor("")` 会得到畸形名字 `.fdf`，
     * 所以 `FunctionFileStore.write` 用**同一条** `baseNameError` 规则先拦一道
     * （这里只能测规则本身，写盘那层要 Context、进不了离线脚本）。
     */
    @Test
    fun `名字为空一律明确拒绝，不静默退回时间戳`() {
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError("", emptySet()))
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError("　　　", emptySet()))
        assertEquals("名字不能为空", FunctionFileFormat.baseNameError(".fdf", emptySet()))
        // 畸形名字的前提：空基名会拼出 `.fdf`（以点开头 = 隐藏文件），所以必须拦住
        assertEquals(FunctionFileFormat.EXTENSION, FunctionFileFormat.fileNameFor(""))
        assertNotNull("空名字在任何情况下都不能被判成可用", FunctionFileFormat.baseNameError("", setOf("已有的")))
    }

    // ===================================================================================
    // 7. 体积：这个格式存在的理由
    // ===================================================================================

    /**
     * 同一个典型状态（3 个函数 + 1 条滑动条 + 3 个几何对象 + 2 条对称规则）
     * 用三种方式存下来的**实测字节数**：
     *
     * ```
     * [体积] FDF1=386 字节 / JSON=1611 字节 / FDF1+gzip=301 字节
     * ```
     *
     * JSON 那一列是这里自己拼的（classpath 上没有任何 JSON 库，本 App 也没打算引），
     * 字段名按"一个字段一个 key"的常规写法给，数组/对象嵌套也照写 ——
     * 也就是**偏袒 JSON** 的比法：真实库还会再加空格、转义和可选字段。
     *
     * gzip 那一列是对本格式**再压一层**的结果：能省 22%，但固定头尾就占了 18 字节，
     * 而且压完就是二进制、用户和我们都看不了里面的内容 ——
     * 在"几百字节"这个量级上，这份可读性比 85 个字节值钱。
     */
    @Test
    fun `FDF 比 JSON 小得多，再 gzip 的收益也换不回可读性`() {
        val state = sample()
        val fdf = FunctionFileFormat.encode(state).toByteArray(Charsets.UTF_8)
        val json = jsonLike(state).toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(fdf) }
        }.toByteArray()

        println("[体积] FDF1=${fdf.size} 字节 / JSON=${json.size} 字节 / FDF1+gzip=${gz.size} 字节")

        // 用户问的是"典型 3 个函数 + 1 个滑动条 = 多少字节"，所以这一档也量出来
        val small = FileState(
            functions = listOf(f1(), f2(), f3()),
            sliders = sample().sliders,
            settings = sample().settings,
            viewport = sample().viewport,
            selectedId = 1L
        )
        val smallFdf = FunctionFileFormat.encode(small).toByteArray(Charsets.UTF_8)
        val smallJson = jsonLike(small).toByteArray(Charsets.UTF_8)
        println("[体积] 3 函数 + 1 滑动条：FDF1=${smallFdf.size} 字节 / JSON=${smallJson.size} 字节")

        assertTrue(
            "3 函数 + 1 滑动条应该落在几百字节以内：${smallFdf.size}",
            smallFdf.size < 300
        )

        assertTrue(
            "JSON 应该至少是 FDF 的 3 倍：fdf=${fdf.size} json=${json.size}",
            json.size >= fdf.size * 3
        )
        // 测试数据是写死的，所以这个比值是确定的（实测 301/386 = 0.78）。
        // 钉住"gzip 省不到三分之一"这个事实：省的那点字节不值得放弃纯文本。
        assertTrue(
            "gzip 在这个量级上的收益应该很有限：fdf=${fdf.size} gz=${gz.size}",
            gz.size > fdf.size * 2 / 3
        )
    }

    /**
     * 一份"常规写法"的 JSON（每个字段一个键名）。
     *
     * 刻意写成手工拼接：本机 classpath 上没有 JSON 库，而为了量一个体积去装一个库
     * 显然不值得。字段名、嵌套结构都按常规写法来，不作弊（不缩写键名）。
     */
    private fun jsonLike(s: FileState): String {
        fun q(v: String): String = "\"" + v
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

        val sb = StringBuilder()
        sb.append("{\"version\":\"FDF1\",\"selectedId\":").append(s.selectedId)
        sb.append(",\"settings\":{\"coordinateSystem\":\"").append(s.settings.coord.name)
            .append("\",\"angleUnit\":\"").append(s.settings.angle.name)
            .append("\",\"showGrid\":").append(s.settings.showGrid)
            .append(",\"showAxes\":").append(s.settings.showAxes)
            .append(",\"showNumbers\":").append(s.settings.showNumbers)
            .append(",\"showPresets\":").append(s.settings.showPresets)
            .append(",\"confirmCoordSwitch\":").append(s.settings.confirmCoordSwitch)
            .append("}")
        sb.append(",\"viewport\":{\"centerX\":").append(s.viewport.centerX)
            .append(",\"centerY\":").append(s.viewport.centerY)
            .append(",\"unitsPerPixel\":").append(s.viewport.unitsPerPixel).append("}")

        sb.append(",\"functions\":[")
        s.functions.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(f.id)
                .append(",\"mode\":\"").append(f.mode.name)
                .append("\",\"src1\":").append(q(f.src1))
                .append(",\"src2\":").append(q(f.src2))
                .append(",\"tMin\":").append(f.tMin)
                .append(",\"tMax\":").append(f.tMax)
                .append(",\"colorIndex\":").append(f.colorIndex)
                .append(",\"visible\":").append(f.visible)
                .append(",\"degrees\":").append(f.degrees)
                .append(",\"name\":").append(q(f.name))
                .append(",\"lineStyle\":\"").append(f.lineStyle.name)
                .append("\",\"widthScale\":").append(f.widthScale).append("}")
        }
        sb.append("]")

        sb.append(",\"sliders\":[")
        s.sliders.forEachIndexed { i, sl ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(sl.id)
                .append(",\"param\":").append(q(sl.param))
                .append(",\"min\":").append(sl.min)
                .append(",\"max\":").append(sl.max)
                .append(",\"step\":").append(sl.step)
                .append(",\"value\":").append(sl.value)
                .append(",\"colorIndex\":").append(sl.colorIndex)
                .append(",\"visible\":").append(sl.visible).append("}")
        }
        sb.append("]")

        sb.append(",\"objects\":[")
        s.objects.forEachIndexed { i, o ->
            if (i > 0) sb.append(',')
            when (o) {
                is FileObject.Point ->
                    sb.append("{\"type\":\"point\",\"id\":").append(o.id)
                        .append(",\"name\":").append(q(o.name))
                        .append(",\"point\":{\"x\":").append(o.point.x)
                        .append(",\"y\":").append(o.point.y).append("}")
                        .append(",\"kind\":\"").append(o.kind.name)
                        .append("\",\"colorIndex\":").append(o.colorIndex)
                        .append(",\"visible\":").append(o.visible).append("}")

                is FileObject.Line ->
                    sb.append("{\"type\":\"line\",\"id\":").append(o.id)
                        .append(",\"name\":").append(q(o.name))
                        .append(",\"p\":{\"x\":").append(o.line.p.x)
                        .append(",\"y\":").append(o.line.p.y).append("}")
                        .append(",\"q\":{\"x\":").append(o.line.q.x)
                        .append(",\"y\":").append(o.line.q.y).append("}")
                        .append(",\"kind\":\"").append(o.line.kind.name)
                        .append("\",\"role\":").append(q(o.role))
                        .append(",\"colorIndex\":").append(o.colorIndex)
                        .append(",\"visible\":").append(o.visible).append("}")

                is FileObject.Measure ->
                    sb.append("{\"type\":\"measure\",\"id\":").append(o.id)
                        .append(",\"kind\":\"").append(o.kind.name)
                        .append("\",\"value\":").append(o.value)
                        .append(",\"points\":[")
                        .append(o.points.joinToString(",") { "{\"x\":${it.x},\"y\":${it.y}}" })
                        .append("],\"pointNames\":[")
                        .append(o.pointNames.joinToString(",") { q(it) })
                        .append("],\"colorIndex\":").append(o.colorIndex)
                        .append(",\"visible\":").append(o.visible)
                        .append(",\"showValue\":").append(o.showValue).append("}")
            }
        }
        sb.append("]")

        sb.append(",\"transforms\":[")
        s.transforms.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"functionId\":").append(t.functionId)
                .append(",\"axis\":").append(
                    t.axis?.let {
                        "{\"p\":{\"x\":${it.p.x},\"y\":${it.p.y}}," +
                            "\"q\":{\"x\":${it.q.x},\"y\":${it.q.y}},\"kind\":\"${it.kind.name}\"}"
                    } ?: "null"
                )
                .append(",\"center\":").append(
                    t.center?.let { "{\"x\":${it.x},\"y\":${it.y}}" } ?: "null"
                )
                .append("}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
