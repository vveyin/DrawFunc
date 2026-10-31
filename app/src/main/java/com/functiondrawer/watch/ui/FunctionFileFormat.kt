package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.LineStyle
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.SnapKind

/**
 * 「函数文件」（`.fdf`）的文本格式：**整个应用状态 ⇄ 紧凑文本**。
 *
 * ## 为什么是"逐行 + `|` 分隔"的纯文本，而不是 JSON / gzip
 *
 * 用户要求"越节省空间且无损越好"。三种方案的**实测**字节数
 * （同一个典型状态：3 个函数 + 1 条滑动条 + 3 个几何对象 + 2 条对称规则 + 设置 + 视口，
 * 见 `FunctionFileFormatTest` 里那条会打印体积的测试）：
 *
 * | 方案 | 字节数 | 说明 |
 * |---|---|---|
 * | 本格式 FDF1 | **386** | 字段之间只有 1 个 `\|`，枚举只有 1 个字符 |
 * | 等价内容的 JSON | **1611**（4.2 倍） | 每个字段名都要重复一遍：`"colorIndex":0` 用 15 个字节表达 1 个字节的信息 |
 * | 本格式再 gzip | 301（省 22%） | 固定头尾就有 18 字节；**而且文件不再可读**（不能 grep、不能手改、坏了没法看） |
 *
 * 结论：JSON 被 4 倍地淘汰；gzip 那 85 个字节在这个量级上买不回可读性和
 * "出问题能人工看"的价值（一份文件几百字节，而一次图像导出是几百 KB）。
 * 另外这个 App 里没有任何 JSON 库（`Store.kt` 也是行格式 + SharedPreferences），
 * 自己写一个 JSON 解析器反而是最脆的一环：手写格式只要一个转义函数就能说清。
 *
 * ## 格式（第 1 行固定是版本号，其余行顺序无关，一条记录一行）
 *
 * | 记录 | 字段 |
 * |---|---|
 * | `FDF1` | 版本行。**必须**是第一行，不是它 → 整体失败 |
 * | `S` | 设置：`coord\|angle\|网格\|坐标轴\|刻度\|示例\|切坐标系确认` |
 * | `X` | 当前选中：`selectedId` |
 * | `V` | 视口：`centerX\|centerY\|unitsPerPixel` |
 * | `F` | 函数：`id\|mode\|颜色\|可见\|tMin\|tMax\|src1\|src2\|度制\|名字\|线型\|线径` |
 * | `L` | 滑动条：`id\|参数\|下界\|上界\|步长\|值\|颜色\|可见` |
 * | `O\|P` | 点对象：`id\|名字\|x\|y\|来源\|颜色\|可见` |
 * | `O\|L` | 线对象：`id\|名字\|px\|py\|qx\|qy\|种类\|说明\|颜色\|可见` |
 * | `O\|M` | 度量：`id\|种类\|数值\|点名表\|点表\|颜色\|可见\|显示数值` |
 * | `T` | 曲线对称规则：`functionId\|轴\|中心` |
 * | `B` | 名字绑定：`functionId\|字母\|对象id`（一条绑定一行；**空表一行都不写**，见下面那条解释） |
 * | `R` | 施工说明：`O 或 F\|id\|正文`（派生对象"跟着点动"的 recipe；**空表一行都不写**，见 [ObjectRecipe]） |
 *
 * ### `B` 记录（本轮新增）：把"这个字母指谁"也存进文件
 *
 * 用户需求："标量和滑动条的名字可以不用严格分出使用的字母……至于 e、x、y 这些
 * 可能冲突的字母，请你给函数编辑界面输入的这些字母单独分组编 id，
 * 这样也不用担心字母冲突的问题。" —— 那张表存在 `GraphState.functionBindings`
 * （`Map<函数 id, Map<字母, 对象 id>>`），这里负责把它落到文件里。
 *
 * 三条设计选择：
 *  1. **空表一行都不写**（而不是写一行 `B|0|NONE|NONE` 之类的占位）：这样
 *     "从来没指认过字母"的文件的**字节数与本轮之前逐字节相同** ——
 *     老用户存过的文件、以及测试里那两个尺寸断言（`FDF1=386` / `FDF1=252` 字节）
 *     都不受影响。读的时候没有 `B` 行就是空表，所以**老文件读进来天然是空表**。
 *  2. **一条绑定一行**（而不是压进某个已有记录里）：`F` 行已经有 12 个字段了，
 *     再加一格会让"第 9 格以后"那种"越往后越容易看错"的问题更严重；
 *     而绑定是**稀疏**的（大多数函数一个都没有），独立成行反而更省字节。
 *  3. **字母用 `%XX` 转义**（和名字/表达式同一套）：字母本身只可能是小写拉丁字母序列
 *     （`isScalarName` 的口径），理论上不需要转义，但**别人手改文件**时什么都能写进来，
 *     转义一下就不会因为一个 `|` 把整行读歪。
 *
 * 为什么**不改 `Store.kt`**（本地存档）：它本来就不存几何对象，而绑定表存的是
 * **对象 id**，存进本地存档只会在下次冷启动时变成空指针（悬空引用）。
 * 要么两边一起存，要么两边都不存 —— 现在是不存，一致。
 *
 * 单字符取值（**不**用枚举序号：序号一改枚举顺序就会把老文件解释错，
 * 而单字符是写死的、可读的）：
 *
 * | 类型 | 取值 |
 * |---|---|
 * | [PlotMode] | `E`=y=f(x)、`P`=r(θ)、`T`=描点、`I`=F(x,y) |
 * | [LineStyle] | `s`=实线、`f`=细虚、`m`=中虚、`c`=粗虚 |
 * | 坐标系 | `c`=笛卡尔、`p`=极坐标、`x`=复平面 |
 * | 角度制 | `r`=弧度、`d`=度 |
 * | [SnapKind] | `v`=顶点、`i`=交点、`z`=零点、`e`=极值点、`p`=描点、`o`=其它 |
 * | [GLineKind] | `l`=直线、`s`=线段、`r`=射线 |
 * | [FileMeasureKind] | `a`=角度、`d`=距离、`r`=面积 |
 *
 * ## 转义规则（自由文本：表达式、名字、线的说明）
 *
 * 只转义 5 个字符，全部写成 `%XX` 形式（Hex 大写）：
 *
 * | 原字符 | 转义后 | 为什么要转 |
 * |---|---|---|
 * | `%` | `%25` | 转义前缀自身，不转就没法反解 |
 * | `\|` | `%7C` | 字段分隔符 |
 * | `\n` | `%0A` | 记录分隔符 |
 * | `\r` | `%0D` | 同上（Windows 工具会加回车） |
 * | `;` | `%3B` | `O\|M` 记录里两个子表的元素分隔符 |
 *
 * **逗号有意不转义**：它只出现在"两个数字之间"（点表 `x,y`）这一个位置，
 * 而自由文本里的逗号永远不在数字对的位置上，所以不转也不会歧义 ——
 * 而表达式里逗号太常见（`max(x,3)`、`(cos t, sin t)`），转义它会让文件白白变大。
 * 反解遇到不合法的 `%XX` 时**原样保留**那一段（宁可显示得怪一点，也不能丢字符）。
 *
 * ## 两条硬规则
 *
 *  1. **版本不认识就整体失败**：[decode] 返回 null，绝不"猜一个版本去读" ——
 *     猜错的后果是用户的功能被悄悄改坏，比明确报错糟糕得多。
 *  2. **反序列化永不抛异常**：任何一条记录坏掉只丢**那一条**（能救多少救多少），
 *     而且版本行不对时也只返回 null，不抛。
 *
 * ## 有意的两点信息取舍（都在报告里写明）
 *
 *  * `Slider.playing` **不进文件**：它是"正在播放"这个瞬时动画状态，
 *    和 `Store.kt` 里"播放状态不落库（重启之后不该自己动起来）"的既有约定一致。
 *  * `role == FRAGMENT_ROLE` 的**对称曲线折线段不进文件**：它们是曲线的**派生物**
 *    （一次采样一条曲线能拆出上百段），真正要存的是"哪条曲线按什么轴/中心对称"
 *    这条**规则** —— 所以存 `T` 记录，打开时由 `GraphState.rebuildTransformedCurves()`
 *    按新视口重新生成，比存一堆死掉的折线既小又正确。
 *
 * ## 为什么这里有一整套"镜像类型"（[FileState] / [FileSlider] / [FileObject]…）
 *
 * [PlotFunction] / [Viewport] / [GPoint] / [GLine] 这些引擎类型可以直接用（零依赖）；
 * 但 `WatchSettings` / `Slider` / `GraphObject` 虽然**本身**是纯 Kotlin，
 * 却住在 `GraphState.kt` / `Sliders.kt` / `GraphObjects.kt` 这三个**带 Compose**
 * 的文件里 —— 离线脚本（`tools\run-tool-tests.ps1`）只编译"纯文件"，
 * 引用它们会让这个格式的编解码**无法离线测试**（那样最该被钉住的转义/版本规则
 * 就没人守着）。所以文件里自带一套**文件格式自己的**数据模型，
 * 由 `GraphState` 做一层机械的一对一转换。
 * 顺带的好处：文件格式不再和界面类耦合，改界面字段时编译器会在转换处报出来。
 */
object FunctionFileFormat {

    /** 版本行内容。将来不兼容地改字段时要换成 `FDF2`。 */
    const val VERSION = "FDF1"

    /** 函数文件的扩展名。界面上**一律不显示**它（用户明确要求）。 */
    const val EXTENSION = ".fdf"

    /** 保存目录名。真正的路径是 `<应用内部存储>/saves/`（见 `FunctionFileStore`）。 */
    const val SAVES_DIR = "saves"

    /** 用户名字（不含扩展名）的最大长度。 */
    const val MAX_BASE_NAME = 40

    /** 文件名里不允许出现的字符。 */
    private val ILLEGAL_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private const val SEP = '|'
    private const val LIST_SEP = ';'
    private const val PAIR_SEP = ','

    /** "这一格没有值"（`degrees` 的 null、`T` 记录里缺席的轴/中心）。 */
    private const val NONE = "-"

    // ===========================================================================
    // 1. 数据模型（= 文件格式自己的模型，不是界面类）
    // ===========================================================================

    /** 复平面/坐标系在文件里怎么写的镜像枚举（转换在 `GraphState` 里做）。 */
    enum class FileCoord(val code: Char) {
        CARTESIAN('c'),
        POLAR('p'),
        COMPLEX('x');

        companion object {
            fun of(code: Char): FileCoord? = entries.firstOrNull { it.code == code }
        }
    }

    /** 角度制的镜像枚举。 */
    enum class FileAngle(val code: Char) {
        RAD('r'),
        DEG('d');

        companion object {
            fun of(code: Char): FileAngle? = entries.firstOrNull { it.code == code }
        }
    }

    /** 度量的镜像枚举。`label` 和 `MeasureKind.label` 一致（度量的名字由它拼出来）。 */
    enum class FileMeasureKind(val code: Char, val label: String) {
        ANGLE('a', "角度"),
        DISTANCE('d', "距离"),
        AREA('r', "面积");

        companion object {
            fun of(code: Char): FileMeasureKind? = entries.firstOrNull { it.code == code }
        }
    }

    /** 设置（= `WatchSettings` 的全部 7 个字段）。 */
    data class FileSettings(
        val coord: FileCoord = FileCoord.CARTESIAN,
        val angle: FileAngle = FileAngle.RAD,
        val showGrid: Boolean = true,
        val showAxes: Boolean = true,
        val showNumbers: Boolean = false,
        val showPresets: Boolean = true,
        val confirmCoordSwitch: Boolean = true
    )

    /** 视口。**只存"从哪看、看多大"**：widthPx/heightPx 是设备尺寸，存了反而会错。 */
    data class FileViewport(
        val centerX: Double = 0.0,
        val centerY: Double = 0.0,
        /** <= 0 表示"这个文件没带缩放"，打开时沿用当前缩放。 */
        val unitsPerPixel: Double = 0.0
    )

    /** 一条滑动条（= `Slider` 去掉 `playing`，理由见类注释）。 */
    data class FileSlider(
        val id: Long,
        val param: String,
        val min: Double = -5.0,
        val max: Double = 5.0,
        val step: Double = 0.1,
        val value: Double = 0.0,
        val colorIndex: Int = 0,
        val visible: Boolean = true
    )

    /**
     * 图上的一个几何对象。
     *
     * 做成 sealed 家族、并且**统一放在 [FileState.objects] 一个有序列表里**：
     * 应用中 `objects` 就是一条有序列表，点/线/度量混在一起按画的先后排列；
     * 如果按类型拆成三个列表，那个顺序就丢了（＝有损）。
     */
    sealed interface FileObject {
        val id: Long
        val name: String
        val colorIndex: Int
        val visible: Boolean

        data class Point(
            override val id: Long,
            override val name: String,
            val point: GPoint,
            val kind: SnapKind = SnapKind.POINT,
            override val colorIndex: Int = 0,
            override val visible: Boolean = true,
            /**
             * 吸附关系（跟着哪条函数曲线 / 哪条自绘线 / 哪个交点走）。1.3.10 起存盘：
             * 以前不存，重启后点虽然还在，但曲线一动它就不跟了 ——
             * 和 recipe 不存盘是同一类毛病。null = 自由点；老文件没有这一段。
             */
            val attachment: PointAttachment? = null
        ) : FileObject

        data class Line(
            override val id: Long,
            override val name: String,
            val line: GLine,
            val role: String = "",
            override val colorIndex: Int = 0,
            override val visible: Boolean = true
        ) : FileObject

        data class Measure(
            override val id: Long,
            val kind: FileMeasureKind,
            val value: Double,
            /** 参与度量的点（角度 3 个、距离 2 个、面积 N 个）。 */
            val points: List<GPoint>,
            /** 这些点各自叫什么（`角度（A，B，C）` 里的 A/B/C）。 */
            val pointNames: List<String>,
            override val colorIndex: Int = 0,
            override val visible: Boolean = true,
            val showValue: Boolean = true
        ) : FileObject {
            /**
             * 名字**照 `GraphMeasureObject.name` 的规则现算**，不单独存一个字段：
             * 同一个事实存两份迟早会不一致，而这份规则只有一行。
             */
            override val name: String
                get() = if (pointNames.isEmpty()) {
                    kind.label
                } else {
                    "${kind.label}（${pointNames.joinToString("，")}）"
                }
        }
    }

    /**
     * 一条"曲线对称"规则（= `GraphState.TransformedCurve`）。
     * [axis] 与 [center] 二选一：轴对称给轴、中心对称给中心。
     */
    data class FileTransform(
        val functionId: Long,
        val axis: GLine? = null,
        val center: GPoint? = null
    )

    /**
     * 一个文件里的**全部**内容。
     *
     * [selectedId] 也存：文件是"整个应用状态"的快照，打开之后列表里高亮哪一条
     * 属于同一次快照（少一个字段就得自己编一个规则出来）。
     */
    data class FileState(
        val functions: List<PlotFunction> = emptyList(),
        val sliders: List<FileSlider> = emptyList(),
        val objects: List<FileObject> = emptyList(),
        val transforms: List<FileTransform> = emptyList(),
        val settings: FileSettings = FileSettings(),
        val viewport: FileViewport = FileViewport(),
        val selectedId: Long = -1L,
        /**
         * 名字绑定表：函数 id →（用户在编辑器里敲的字母 → 对象 id）。
         *
         * 和 `GraphState.functionBindings` 同一个形状（那边是权威，这里只是它在文件里的样子）。
         * **默认空表**，而且 [encode] 在空表时**一行都不写** ——
         * 所以"没指认过字母"的文件和本轮之前**逐字节相同**，老文件读进来也是空表。
         *
         * 值可能是**滑动条 id**，也可能是**标量对象 id**（上一轮定的口径：绑定的对象
         * 两种都行，靠 id 现查是哪一个）。装载时如果那个对象已经不在文件里，
         * 绑定会变成"指不到东西" —— 采样时那个字母就原样留给引擎报"不认识的名字"，
         * 而不是悄悄换成同名的别人（见 `PointCoordinates.resolveScalarValues`）。
         */
        val bindings: Map<Long, Map<String, Long>> = emptyMap(),
        /**
         * 几何对象（点 / 线 / 度量）的"施工说明"：对象 id → recipe（1.3.9）。
         *
         * 以前**不存**这一份，于是重开软件后所有派生对象都退化成建出来那一刻的
         * 死快照（用户实测："关闭软件之后再打开，原本描的点派生的直线 / 曲线
         * 就不能跟着点动了"）。空表时 [encode] **一行都不写** ——
         * 没有派生对象的文件与本轮之前逐字节相同。
         */
        val objectRecipes: Map<Long, ObjectRecipe> = emptyMap(),
        /**
         * 函数形态的派生曲线（圆 / 圆弧 / 圆锥曲线 / 切线…）的施工说明：函数 id → recipe。
         *
         * 和 `GraphState.curveRecipes` 对得上（那边是权威，这里只是它在文件里的样子）。
         */
        val functionRecipes: Map<Long, ObjectRecipe> = emptyMap()
    )

    /** 文件列表里那行小字要用的统计（从文本直接数出来，不用完整解析）。 */
    data class Summary(
        val functions: Int = 0,
        val sliders: Int = 0,
        val objects: Int = 0,
        /** 版本行是不是我们能读的那一个。false 时上面三个计数没有意义（恒为 0）。 */
        val versionOk: Boolean = false
    ) {
        /** 列表第二行的小字：`3 函数 · 1 滑动条 · 412 B`（为 0 的栏目不占字）。 */
        fun text(bytes: Long): String {
            val parts = ArrayList<String>(3)
            if (functions > 0) parts += "$functions 函数"
            if (sliders > 0) parts += "$sliders 滑动条"
            if (objects > 0) parts += "$objects 对象"
            if (parts.isEmpty()) parts += "空文件"
            parts += fileSizeText(bytes)
            return parts.joinToString(" · ")
        }
    }

    // ===========================================================================
    // 2. 编码
    // ===========================================================================

    /** 把一份状态编成文件正文（UTF-8 文本，行尾是 `\n`，带结尾换行）。 */
    fun encode(state: FileState): String {
        val sb = StringBuilder(320)
        sb.append(VERSION).append('\n')

        // ---- 设置 ----
        val s = state.settings
        sb.append('S').append(SEP)
            .append(s.coord.code).append(SEP)
            .append(s.angle.code).append(SEP)
            .append(bit(s.showGrid)).append(SEP)
            .append(bit(s.showAxes)).append(SEP)
            .append(bit(s.showNumbers)).append(SEP)
            .append(bit(s.showPresets)).append(SEP)
            .append(bit(s.confirmCoordSwitch)).append('\n')

        // ---- 当前选中 ----
        sb.append('X').append(SEP).append(state.selectedId).append('\n')

        // ---- 视口 ----
        sb.append('V').append(SEP)
            .append(num(state.viewport.centerX)).append(SEP)
            .append(num(state.viewport.centerY)).append(SEP)
            .append(num(state.viewport.unitsPerPixel)).append('\n')

        // ---- 函数 ----
        for (fn in state.functions) {
            sb.append('F').append(SEP)
                .append(fn.id).append(SEP)
                .append(modeCode(fn.mode)).append(SEP)
                .append(fn.colorIndex).append(SEP)
                .append(bit(fn.visible)).append(SEP)
                .append(num(fn.tMin)).append(SEP)
                .append(num(fn.tMax)).append(SEP)
                .append(escape(fn.src1)).append(SEP)
                .append(escape(fn.src2)).append(SEP)
                .append(
                    when (fn.degrees) {
                        true -> "1"
                        false -> "0"
                        null -> NONE
                    }
                ).append(SEP)
                .append(escape(fn.name)).append(SEP)
                .append(styleCode(fn.lineStyle)).append(SEP)
                .append(numF(fn.widthScale)).append('\n')
        }

        // ---- 滑动条 ----
        for (sl in state.sliders) {
            sb.append('L').append(SEP)
                .append(sl.id).append(SEP)
                .append(escape(sl.param)).append(SEP)
                .append(num(sl.min)).append(SEP)
                .append(num(sl.max)).append(SEP)
                .append(num(sl.step)).append(SEP)
                .append(num(sl.value)).append(SEP)
                .append(sl.colorIndex).append(SEP)
                .append(bit(sl.visible)).append('\n')
        }

        // ---- 几何对象（保持原来的先后顺序）----
        for (o in state.objects) {
            when (o) {
                is FileObject.Point -> {
                    sb.append('O').append(SEP).append('P').append(SEP)
                        .append(o.id).append(SEP)
                        .append(escape(o.name)).append(SEP)
                        .append(num(o.point.x)).append(SEP)
                        .append(num(o.point.y)).append(SEP)
                        .append(snapCode(o.kind)).append(SEP)
                        .append(o.colorIndex).append(SEP)
                        .append(bit(o.visible))
                    // 第 10 格（1.3.10）：吸附关系。**只有真的吸住了才写** ——
                    // 普通描点 / 老文件于是逐字节不变（既有那份体积断言不用改）。
                    o.attachment?.let { sb.append(SEP).append(attachmentCode(it)) }
                    sb.append('\n')
                }

                is FileObject.Line -> sb.append('O').append(SEP).append('L').append(SEP)
                    .append(o.id).append(SEP)
                    .append(escape(o.name)).append(SEP)
                    .append(num(o.line.p.x)).append(SEP)
                    .append(num(o.line.p.y)).append(SEP)
                    .append(num(o.line.q.x)).append(SEP)
                    .append(num(o.line.q.y)).append(SEP)
                    .append(lineCode(o.line.kind)).append(SEP)
                    .append(escape(o.role)).append(SEP)
                    .append(o.colorIndex).append(SEP)
                    .append(bit(o.visible)).append('\n')

                is FileObject.Measure -> sb.append('O').append(SEP).append('M').append(SEP)
                    .append(o.id).append(SEP)
                    .append(o.kind.code).append(SEP)
                    .append(num(o.value)).append(SEP)
                    .append(o.pointNames.joinToString(LIST_SEP.toString()) { escape(it) })
                    .append(SEP)
                    .append(
                        o.points.joinToString(LIST_SEP.toString()) {
                            num(it.x) + PAIR_SEP + num(it.y)
                        }
                    )
                    .append(SEP)
                    .append(o.colorIndex).append(SEP)
                    .append(bit(o.visible)).append(SEP)
                    .append(bit(o.showValue)).append('\n')
            }
        }

        // ---- 曲线对称规则 ----
        for (t in state.transforms) {
            sb.append('T').append(SEP).append(t.functionId).append(SEP)
            val axis = t.axis
            sb.append(
                if (axis == null) {
                    NONE
                } else {
                    num(axis.p.x) + PAIR_SEP + num(axis.p.y) + PAIR_SEP +
                        num(axis.q.x) + PAIR_SEP + num(axis.q.y) + PAIR_SEP +
                        lineCode(axis.kind)
                }
            ).append(SEP)
            val c = t.center
            sb.append(if (c == null) NONE else num(c.x) + PAIR_SEP + num(c.y)).append('\n')
        }

        // ---- 名字绑定表（`B|函数id|字母|对象id`，一条绑定一行）----
        //
        // **空表一行都不写**：这是"老文件/没指认过的文件逐字节兼容"的关键
        // （`bindings` 的默认值就是空表，所以绝大多数文件走到这里什么都不发生）。
        // 顺序按"函数 id 升序 + 字母升序"排一下：`Map` 的迭代顺序不保证，
        // 排过之后同一个状态**每次都编码出同样的字节**（存档比对、测试断言都靠这个）。
        for ((fnId, table) in state.bindings.entries.sortedBy { it.key }) {
            for ((letter, objId) in table.entries.sortedBy { it.key }) {
                sb.append('B').append(SEP)
                    .append(fnId).append(SEP)
                    .append(escape(letter)).append(SEP)
                    .append(objId).append('\n')
            }
        }

        // ---- 施工说明（`R|O|对象id|正文` / `R|F|函数id|正文`，一条一行）----
        //
        // 派生对象"跟着点动"靠的就是这一份；以前不存，重启后全退化成死快照。
        // 空表一行都不写 → 老文件、以及"没有派生对象"的文件逐字节不变。
        // 排序的理由和绑定表一样：同一个状态每次编出同样的字节。
        for ((id, r) in state.objectRecipes.entries.sortedBy { it.key }) {
            sb.append('R').append(SEP).append('O').append(SEP).append(id).append(SEP)
                .append(encodeRecipe(r)).append('\n')
        }
        for ((id, r) in state.functionRecipes.entries.sortedBy { it.key }) {
            sb.append('R').append(SEP).append('F').append(SEP).append(id).append(SEP)
                .append(encodeRecipe(r)).append('\n')
        }

        return sb.toString()
    }

    // ===========================================================================
    // 3. 解码
    // ===========================================================================

    /**
     * 把文件正文读回一份状态。
     *
     * @return 版本行不是 [VERSION] → **null**（整体失败）；否则一定是一份状态
     *         （坏记录被丢掉，剩下的照样能用，**从不抛异常**）。
     */
    fun decode(text: String): FileState? {
        val lines = splitLines(text)
        if (lines.isEmpty()) return null
        if (lines[0].trim() != VERSION) return null

        var settings = FileSettings()
        var selectedId = -1L
        var viewport = FileViewport()
        val functions = ArrayList<PlotFunction>()
        val sliders = ArrayList<FileSlider>()
        val objects = ArrayList<FileObject>()
        val transforms = ArrayList<FileTransform>()
        // 名字绑定：函数 id →（字母 → 对象 id）。**老文件里没有 `B` 行 → 它保持空表**。
        // 用 LinkedHashMap 保序（写入按id/字母排过序，读出来也稳定），
        // 同一个函数 + 同一个字母出现两次时**后写的赢**（和滑动条那边的口径一致）。
        val bindings = LinkedHashMap<Long, LinkedHashMap<String, Long>>()
        // 施工说明：`R` 行。老文件里没有 → 两张表保持空（行为和以前一样）。
        val objectRecipes = LinkedHashMap<Long, ObjectRecipe>()
        val functionRecipes = LinkedHashMap<Long, ObjectRecipe>()

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            // 一条记录坏掉只丢它自己（**能救多少救多少**），所以逐条兜异常。
            runCatching {
                val f = line.split(SEP)
                when (f.getOrNull(0)) {
                    "S" -> parseSettings(f)?.let { settings = it }
                    "X" -> f.getOrNull(1)?.toLongOrNull()?.let { selectedId = it }
                    "V" -> parseViewport(f)?.let { viewport = it }
                    "F" -> parseFunction(f)?.let { functions += it }
                    "L" -> parseSlider(f)?.let { sliders += it }
                    "O" -> parseObject(f)?.let { objects += it }
                    "T" -> parseTransform(f)?.let { transforms += it }
                    "B" -> parseBinding(f)?.let { (fnId, letter, objId) ->
                        bindings.getOrPut(fnId) { LinkedHashMap() }[letter] = objId
                    }
                    // R|O|对象id|正文 / R|F|函数id|正文：派生对象的施工说明
                    "R" -> {
                        val id = f.getOrNull(2)?.toLongOrNull()
                        val recipe = f.getOrNull(3)?.let { parseRecipe(it) }
                        if (id != null && recipe != null) {
                            when (f.getOrNull(1)) {
                                "O" -> objectRecipes[id] = recipe
                                "F" -> functionRecipes[id] = recipe
                            }
                        }
                    }
                    // 不认识的记录类型（将来的版本加的）→ 直接忽略，不算错
                    else -> Unit
                }
            }
        }

        return FileState(
            functions = functions,
            sliders = sliders,
            objects = objects,
            transforms = transforms,
            settings = settings,
            viewport = viewport,
            selectedId = selectedId,
            // 空表时显式给 `emptyMap()`：让"没绑定"这条最常见的路径不分配多余的对象
            bindings = if (bindings.isEmpty()) emptyMap() else bindings,
            objectRecipes = if (objectRecipes.isEmpty()) emptyMap() else objectRecipes,
            functionRecipes = if (functionRecipes.isEmpty()) emptyMap() else functionRecipes
        )
    }

    /**
     * 只数一数"里面有多少东西"，给文件列表那行小字用。
     *
     * 为什么不直接 [decode] 再数：`decode` 要建一堆对象，而列表每滚一屏都会用到它。
     * 反正记录都是自描述的（第一段就是类型），数前缀就够了。
     */
    fun summarize(text: String): Summary {
        val lines = splitLines(text)
        if (lines.isEmpty() || lines[0].trim() != VERSION) return Summary()
        var f = 0
        var l = 0
        var o = 0
        for (i in 1 until lines.size) {
            when (lines[i].substringBefore(SEP)) {
                "F" -> f++
                "L" -> l++
                "O" -> o++
            }
        }
        return Summary(functions = f, sliders = l, objects = o, versionOk = true)
    }

    // ===========================================================================
    // 4. 文件名的规则（纯字符串逻辑，所以放在这个能离线测的文件里）
    // ===========================================================================

    /** 人名/文件名里不允许的字符，给错误提示用（`/` 会被当成目录分隔符）。 */
    fun illegalNameChars(): String = ILLEGAL_NAME_CHARS.joinToString(" ")

    /**
     * 去掉首尾空白、顺手剥掉用户手打的扩展名。
     *
     * 界面上**不显示扩展名**，但用户完全可能把 `我的曲线.fdf` 整个打进来
     * （或者从别处粘过来），所以这里要认。**光一个 `.fdf` 也剥**（剥完是空名字，
     * 于是被"名字不能为空"拦下）—— 不剥的话它会变成 `.fdf.fdf`，
     * 以点开头在 Linux 上是隐藏文件，用户会"存了但找不到"。
     */
    fun normalizeBaseName(raw: String): String {
        var n = raw.trim()
        if (n.length >= EXTENSION.length && n.endsWith(EXTENSION, ignoreCase = true)) {
            n = n.dropLast(EXTENSION.length).trim()
        }
        return n
    }

    /**
     * 校验用户输入的名字。
     *
     * @param taken 已经存在的**其它**文件的基名（重命名时要先把被改的那个自己排除掉）。
     * @return null = 可以用；否则是给用户看的一句话（对话框标题下面那行小字）。
     *
     * 为什么不学 `sanitizeFileName`（那个是"静默改成合法名 + 空名字退时间戳"）：
     * 存函数文件是用户主动按"确定"的动作，名字是他自己起的，
     * 悄悄改掉或者悄悄起个时间戳名字，用户回头在列表里根本认不出哪个是自己的。
     * 所以这里**一律明确拒绝并说明**（用户原话："要明确拒绝并说明"）。
     */
    fun baseNameError(raw: String, taken: Set<String>): String? {
        val name = normalizeBaseName(raw)
        if (name.isEmpty()) return "名字不能为空"
        if (name.length > MAX_BASE_NAME) return "名字太长（最多 $MAX_BASE_NAME 个字）"
        // 以点开头在 Linux/Android 上是**隐藏文件**：列表里我们自己看得见，
        // 但用户从文件管理器或 ADB 去找就"存了却找不到"（`SaveName.kt` 踩过同一个坑）。
        if (name.startsWith(".")) return "名字不能以点开头"
        val bad = name.filter { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        if (bad.isNotEmpty()) return "名字里不能有 ${illegalNameChars()}"
        // 大小写不敏感地判重：Linux 上 `Sin` 和 `sin` 是两个文件，
        // 但在 1.5 寸的列表里用户根本分不清，宁可不让他建出这一对。
        if (taken.any { it.equals(name, ignoreCase = true) }) return "已有同名文件"
        return null
    }

    /** 基名 → 真正的文件名（补 `.fdf`）。 */
    fun fileNameFor(baseName: String): String = normalizeBaseName(baseName) + EXTENSION

    /** 文件名 → 界面上显示的名字（**去掉扩展名**；不是 `.fdf` 就原样返回）。 */
    fun displayName(fileName: String): String =
        if (fileName.length > EXTENSION.length && fileName.endsWith(EXTENSION, ignoreCase = true)) {
            fileName.dropLast(EXTENSION.length)
        } else {
            fileName
        }

    /** 字节数 → 列表里那行小字（1024 进位的 `B` / `KB`，整数值不写 `.0`）。 */
    fun fileSizeText(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        val kb = Math.round(bytes * 10.0 / 1024.0) / 10.0
        val text = if (kb == Math.floor(kb)) kb.toLong().toString() else kb.toString()
        return "$text KB"
    }

    // ===========================================================================
    // 5. 内部工具
    // ===========================================================================

    /** 统一按 `\n` 切行，并把 Windows 风格的行尾 `\r` 去掉。 */
    private fun splitLines(text: String): List<String> =
        text.split('\n').map { it.trimEnd('\r') }

    private fun bit(v: Boolean): String = if (v) "1" else "0"

    private fun boolOf(s: String?): Boolean = s == "1"

    /**
     * 数字写成最短的、又能 **原样解析回来** 的形式。
     *
     * `Double.toString()` 本身是往返无损的（Java 的算法保证），但整数会写成
     * `1.0`、`0.0` 这种多一个尾巴的形式 —— 而视口中心、量程、颜色这些字段
     * 绝大多数是整数或一位小数。短的写法只在**确实更短**时才用（比如
     * `1.0E10` 反而比 `10000000000` 短，那就保留科学计数法）。
     * `NaN` / `Infinity` 也照原样写出去（`toDouble()` 能读回来），不特殊处理。
     */
    private fun num(v: Double): String {
        if (!v.isFinite()) return v.toString()
        // 零单独写：`0.0` 有两处浪费（小数点、尾巴），而 `-0` 还要保住符号 ——
        // 否则"无损"就有一处例外（`(-0.0).toLong()` 会丢掉负号）。
        if (v == 0.0) return if (1.0 / v < 0.0) "-0" else "0"
        val plain = v.toString()
        val asLong = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else null
        return if (asLong != null && asLong.length <= plain.length) asLong else plain
    }

    /**
     * `Float` 的写法和 `Double` 分开：`1.6f.toString()` 就是 `"1.6"`，
     * 而先转成 Double 会写成 `1.600000023841858`（多 12 个字节，而且不好看）。
     * `Float.toString` 同样是往返无损的（Java 保证）。
     */
    private fun numF(v: Float): String = v.toString()

    /** 自由文本 → 一格字段（规则见类注释；`%XX` 是 Hex 大写）。 */
    fun escape(text: String): String {
        if (text.isEmpty()) return text
        // 绝大多数文本一个字符都不用转：先扫一遍，没命中就原样返回（省掉一次拼接）。
        var need = false
        for (c in text) {
            if (c == '%' || c == SEP || c == '\n' || c == '\r' || c == LIST_SEP) {
                need = true
                break
            }
        }
        if (!need) return text

        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            // 这里刻意写字符字面量而不是用 SEP/LIST_SEP：转义表是"格式的一部分"，
            // 读代码的人应该一眼看到`|`和`;`本身，而不是去猜常量是什么。
            when (c) {
                '%' -> sb.append("%25")
                '|' -> sb.append("%7C")
                '\n' -> sb.append("%0A")
                '\r' -> sb.append("%0D")
                ';' -> sb.append("%3B")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 一格字段 → 自由文本。不合法的 `%XX` **原样保留**（不丢字符、不抛异常）。 */
    fun unescape(text: String): String {
        if (text.indexOf('%') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && i + 3 <= text.length) {
                val code = hexToChar(text.substring(i + 1, i + 3))
                if (code != null) {
                    sb.append(code)
                    i += 3
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private fun hexToChar(hex: String): Char? {
        if (hex.length != 2) return null
        var v = 0
        for (c in hex) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            v = v * 16 + d
        }
        return v.toChar()
    }

    private fun modeCode(m: PlotMode): Char = when (m) {
        PlotMode.EXPLICIT -> 'E'
        PlotMode.POLAR -> 'P'
        PlotMode.PARAMETRIC -> 'T'
        PlotMode.IMPLICIT -> 'I'
    }

    private fun modeOf(c: Char): PlotMode? = when (c) {
        'E' -> PlotMode.EXPLICIT
        'P' -> PlotMode.POLAR
        'T' -> PlotMode.PARAMETRIC
        'I' -> PlotMode.IMPLICIT
        else -> null
    }

    private fun styleCode(s: LineStyle): Char = when (s) {
        LineStyle.SOLID -> 's'
        LineStyle.DASH_FINE -> 'f'
        LineStyle.DASH_MEDIUM -> 'm'
        LineStyle.DASH_COARSE -> 'c'
    }

    private fun styleOf(c: Char): LineStyle? = when (c) {
        's' -> LineStyle.SOLID
        'f' -> LineStyle.DASH_FINE
        'm' -> LineStyle.DASH_MEDIUM
        'c' -> LineStyle.DASH_COARSE
        else -> null
    }

    private fun snapCode(k: SnapKind): Char = when (k) {
        SnapKind.VERTEX -> 'v'
        SnapKind.INTERSECTION -> 'i'
        SnapKind.ZERO -> 'z'
        SnapKind.EXTREMUM -> 'e'
        SnapKind.POINT -> 'p'
        SnapKind.OTHER -> 'o'
    }

    private fun snapOf(c: Char): SnapKind? = when (c) {
        'v' -> SnapKind.VERTEX
        'i' -> SnapKind.INTERSECTION
        'z' -> SnapKind.ZERO
        'e' -> SnapKind.EXTREMUM
        'p' -> SnapKind.POINT
        'o' -> SnapKind.OTHER
        else -> null
    }

    private fun lineCode(k: GLineKind): Char = when (k) {
        GLineKind.LINE -> 'l'
        GLineKind.SEGMENT -> 's'
        GLineKind.RAY -> 'r'
    }

    private fun lineOf(c: Char): GLineKind? = when (c) {
        'l' -> GLineKind.LINE
        's' -> GLineKind.SEGMENT
        'r' -> GLineKind.RAY
        else -> null
    }

    // ---- 施工说明（`R` 记录正文）：`ObjectRecipe` ⇄ 一小段文本 ----
    //
    // 参数用**逗号**分隔（逗号在文件里从来不是分隔符，见类注释那节），所以 recipe 里
    // 的自由文本（半径表达式 `d/2`）必须把逗号也转义掉，否则 `max(x,3)` 会被拆成多格。
    // 复用 [escape] 之后补一步逗号（`escape` 已经先把 `%` 转掉了，所以不会双重转义）。
    //
    // 整条记录的字段顺序：`R|O 或 F|id|正文`。写的时候按 id 排序，保证同一状态
    // 每次编出同样的字节。

    /** 一个线段引用：`o<id>`（对象）或 `x<px>/<py>/<qx>/<qy>/<kind>`（常量直线）。 */
    fun encodeLineRef(ref: ObjectRecipe.LineRef): String = when (ref) {
        is ObjectRecipe.LineRef.Obj -> "o" + ref.id
        is ObjectRecipe.LineRef.Fixed ->
            "x" + num(ref.line.p.x) + "/" + num(ref.line.p.y) + "/" +
                num(ref.line.q.x) + "/" + num(ref.line.q.y) + "/" + lineCode(ref.line.kind)
    }

    /** 反向：见上面的 [encodeLineRef]。 */
    private fun decodeLineRef(s: String): ObjectRecipe.LineRef? = when {
        s.startsWith("o") -> s.drop(1).toLongOrNull()?.let { ObjectRecipe.LineRef.Obj(it) }
        s.startsWith("x") -> {
            val p = s.drop(1).split('/')
            val px = p.getOrNull(0)?.toDoubleOrNull()
            val py = p.getOrNull(1)?.toDoubleOrNull()
            val qx = p.getOrNull(2)?.toDoubleOrNull()
            val qy = p.getOrNull(3)?.toDoubleOrNull()
            val kind = p.getOrNull(4)?.firstOrNull()?.let { lineOf(it) }
            if (px != null && py != null && qx != null && qy != null && kind != null) {
                ObjectRecipe.LineRef.Fixed(GLine(GPoint(px, py), GPoint(qx, qy), kind))
            } else {
                null
            }
        }
        else -> null
    }

    /** 施工说明正文：[ObjectRecipe] → 文本（格式见上一节的注释）。 */
    fun encodeRecipe(r: ObjectRecipe): String = when (r) {
        is ObjectRecipe.LineFromPoints -> "LP,${r.a},${r.b},${lineCode(r.kind)}"
        is ObjectRecipe.PerpendicularBisectorOf -> "PB,${r.a},${r.b}"
        is ObjectRecipe.AngleBisectorOf -> "AB,${r.a},${r.b},${r.c}"
        is ObjectRecipe.PerpendicularTo -> "PE,${encodeLineRef(r.source)},${r.through}"
        is ObjectRecipe.ParallelTo -> "PA,${encodeLineRef(r.source)},${r.through}"
        is ObjectRecipe.MidpointOf -> "MP,${r.a},${r.b}"
        is ObjectRecipe.DistanceBetween -> "DI,${r.a},${r.b}"
        is ObjectRecipe.AreaOfPoints -> "AR," + r.points.joinToString(",")
        is ObjectRecipe.AngleBetweenLines ->
            "AL,${encodeLineRef(r.first)},${encodeLineRef(r.second)}"
        is ObjectRecipe.MirrorPointOf -> "MR,${r.source}," + when (val m = r.mirror) {
            is ObjectRecipe.MirrorPointOf.Mirror.Axis -> "a" + encodeLineRef(m.line)
            is ObjectRecipe.MirrorPointOf.Mirror.Center -> "c" + m.point
        }
        is ObjectRecipe.CircleCenterPoint -> "CP,${r.center},${r.through}"
        is ObjectRecipe.CircleCenterRadius -> "CR,${r.center},${r.a},${r.b}"
        is ObjectRecipe.CircleCenterRadiusLine -> "CRL,${r.center},${encodeLineRef(r.line)}"
        is ObjectRecipe.CircleCenterRadiusExpr ->
            "CE,${r.center}," + escape(r.radiusSource).replace(",", "%2C")
        is ObjectRecipe.CircleThreePoints -> "C3,${r.a},${r.b},${r.c}"
        is ObjectRecipe.ArcOfPoints -> "ARC,${r.center},${r.start},${r.end}"
        is ObjectRecipe.EllipseOf -> "EL,${r.f1},${r.f2},${r.on}"
        is ObjectRecipe.HyperbolaOf -> "HY,${r.f1},${r.f2},${r.on}"
        is ObjectRecipe.ParabolaOf -> "PR,${r.focus},${encodeLineRef(r.directrix)}"
        is ObjectRecipe.ConicFivePoints -> "C5," + r.points.joinToString(",")
        is ObjectRecipe.TangentAtPoint -> "TG,${r.point},${r.function}"
    }

    /** 反向：见上面那个 [encodeRecipe]。坏掉 / 认不出的返回 null（丢这一条）。 */
    fun parseRecipe(text: String): ObjectRecipe? {
        val f = text.split(',')
        if (f.size < 2) return null
        fun id(i: Int): Long? = f.getOrNull(i)?.toLongOrNull()
        fun ref(i: Int): ObjectRecipe.LineRef? = f.getOrNull(i)?.let { decodeLineRef(it) }
        fun ids(from: Int): List<Long> = f.drop(from).mapNotNull { it.toLongOrNull() }
        return when (f[0]) {
            // 两点定线 / 中垂线 / 角平分线
            "LP" -> {
                val a = id(1)
                val b = id(2)
                val k = f.getOrNull(3)?.firstOrNull()?.let { lineOf(it) }
                if (a != null && b != null && k != null) {
                    ObjectRecipe.LineFromPoints(a, b, k)
                } else {
                    null
                }
            }
            "PB" -> id(1)?.let { a -> id(2)?.let { b -> ObjectRecipe.PerpendicularBisectorOf(a, b) } }
            "AB" -> id(1)?.let { a ->
                id(2)?.let { b -> id(3)?.let { c -> ObjectRecipe.AngleBisectorOf(a, b, c) } }
            }
            "PE" -> ref(1)?.let { r -> id(2)?.let { ObjectRecipe.PerpendicularTo(r, it) } }
            "PA" -> ref(1)?.let { r -> id(2)?.let { ObjectRecipe.ParallelTo(r, it) } }
            "MP" -> id(1)?.let { a -> id(2)?.let { b -> ObjectRecipe.MidpointOf(a, b) } }
            "DI" -> id(1)?.let { a -> id(2)?.let { b -> ObjectRecipe.DistanceBetween(a, b) } }
            "AR" -> ids(1).takeIf { it.isNotEmpty() }?.let { ObjectRecipe.AreaOfPoints(it) }
            "AL" -> ref(1)?.let { a -> ref(2)?.let { b -> ObjectRecipe.AngleBetweenLines(a, b) } }
            "MR" -> {
                val source = id(1)
                val token = f.getOrNull(2).orEmpty()
                val mirror = when {
                    token.startsWith("a") ->
                        decodeLineRef(token.drop(1))
                            ?.let { ObjectRecipe.MirrorPointOf.Mirror.Axis(it) }
                    token.startsWith("c") ->
                        token.drop(1).toLongOrNull()
                            ?.let { ObjectRecipe.MirrorPointOf.Mirror.Center(it) }
                    else -> null
                }
                if (source != null && mirror != null) ObjectRecipe.MirrorPointOf(source, mirror) else null
            }
            // 曲线
            "CP" -> id(1)?.let { c -> id(2)?.let { ObjectRecipe.CircleCenterPoint(c, it) } }
            "CR" -> id(1)?.let { c ->
                id(2)?.let { a -> id(3)?.let { b -> ObjectRecipe.CircleCenterRadius(c, a, b) } }
            }
            "CRL" -> id(1)?.let { c -> ref(2)?.let { ObjectRecipe.CircleCenterRadiusLine(c, it) } }
            "CE" -> id(1)?.let { c ->
                ObjectRecipe.CircleCenterRadiusExpr(c, unescape(f.getOrNull(2).orEmpty()))
            }
            "C3" -> id(1)?.let { a ->
                id(2)?.let { b -> id(3)?.let { c -> ObjectRecipe.CircleThreePoints(a, b, c) } }
            }
            "ARC" -> id(1)?.let { c ->
                id(2)?.let { s -> id(3)?.let { e -> ObjectRecipe.ArcOfPoints(c, s, e) } }
            }
            "EL" -> id(1)?.let { f1 ->
                id(2)?.let { f2 -> id(3)?.let { on -> ObjectRecipe.EllipseOf(f1, f2, on) } }
            }
            "HY" -> id(1)?.let { f1 ->
                id(2)?.let { f2 -> id(3)?.let { on -> ObjectRecipe.HyperbolaOf(f1, f2, on) } }
            }
            "PR" -> id(1)?.let { focus ->
                ref(2)?.let { ObjectRecipe.ParabolaOf(focus, it) }
            }
            "C5" -> ids(1).takeIf { it.isNotEmpty() }?.let { ObjectRecipe.ConicFivePoints(it) }
            "TG" -> id(1)?.let { p -> id(2)?.let { ObjectRecipe.TangentAtPoint(p, it) } }
            else -> null
        }
    }

    // ---- 各记录的解析：字段不够 / 数字坏掉 / 取值不认识 → 一律返回 null（丢这条）----

    /**
     * 取"单字符取值"那一格。
     *
     * 字段可能整个是空的（比如用户手改坏了文件），直接 `s[0]` 会抛
     * `StringIndexOutOfBoundsException` —— 虽然 [decode] 逐条兜着异常，
     * 但"靠异常兜底"会把真正的 bug 藏起来，所以这里显式返回一个不可能匹配的字符，
     * 让 `modeOf`/`styleOf` 这些查表函数自然失败。
     */
    private fun codeAt(s: String?): Char = if (s != null && s.length == 1) s[0] else ' '

    private fun parseSettings(f: List<String>): FileSettings? {
        if (f.size < 8) return null
        val coord = FileCoord.of(codeAt(f.getOrNull(1))) ?: return null
        val angle = FileAngle.of(codeAt(f.getOrNull(2))) ?: return null
        return FileSettings(
            coord = coord,
            angle = angle,
            showGrid = boolOf(f[3]),
            showAxes = boolOf(f[4]),
            showNumbers = boolOf(f[5]),
            showPresets = boolOf(f[6]),
            confirmCoordSwitch = boolOf(f[7])
        )
    }

    private fun parseViewport(f: List<String>): FileViewport? {
        if (f.size < 4) return null
        val cx = f[1].toDoubleOrNull() ?: return null
        val cy = f[2].toDoubleOrNull() ?: return null
        val upp = f[3].toDoubleOrNull() ?: return null
        return FileViewport(cx, cy, upp)
    }

    private fun parseFunction(f: List<String>): PlotFunction? {
        if (f.size < 8) return null
        val id = f[1].toLongOrNull() ?: return null
        val mode = modeOf(codeAt(f.getOrNull(2))) ?: return null
        val color = f[3].toIntOrNull() ?: return null
        val tMin = f[5].toDoubleOrNull() ?: return null
        val tMax = f[6].toDoubleOrNull() ?: return null
        return PlotFunction(
            id = id,
            mode = mode,
            src1 = unescape(f[7]),
            src2 = if (f.size > 8) unescape(f[8]) else "",
            tMin = tMin,
            tMax = tMax,
            colorIndex = color,
            visible = boolOf(f[4]),
            // 第 9 格：`-` = 跟随全局设置（null）
            degrees = when (if (f.size > 9) f[9] else NONE) {
                "1" -> true
                "0" -> false
                else -> null
            },
            name = if (f.size > 10) unescape(f[10]) else "",
            // 线型认不出来就退回实线（这一个字段不值得丢掉整条函数）
            lineStyle = if (f.size > 11) {
                styleOf(codeAt(f.getOrNull(11))) ?: LineStyle.SOLID
            } else {
                LineStyle.SOLID
            },
            widthScale = if (f.size > 12) f[12].toFloatOrNull() ?: 1f else 1f
        )
    }

    private fun parseSlider(f: List<String>): FileSlider? {
        if (f.size < 8) return null
        val id = f[1].toLongOrNull() ?: return null
        val min = f[3].toDoubleOrNull() ?: return null
        val max = f[4].toDoubleOrNull() ?: return null
        val step = f[5].toDoubleOrNull() ?: return null
        val value = f[6].toDoubleOrNull() ?: return null
        val color = f[7].toIntOrNull() ?: return null
        return FileSlider(
            id = id,
            param = unescape(f[2]),
            min = min,
            max = max,
            step = step,
            value = value,
            colorIndex = color,
            // 老版本/手改的文件没有第 8 格：缺省当作"显示"
            visible = if (f.size > 8) boolOf(f[8]) else true
        )
    }

    private fun parseObject(f: List<String>): FileObject? {
        return when (f.getOrNull(1)) {
            "P" -> {
                if (f.size < 9) return null
                val id = f[2].toLongOrNull() ?: return null
                val x = f[4].toDoubleOrNull() ?: return null
                val y = f[5].toDoubleOrNull() ?: return null
                val color = f[7].toIntOrNull() ?: return null
                FileObject.Point(
                    id = id,
                    name = unescape(f[3]),
                    point = GPoint(x, y),
                    // 来源认不出来就退回"普通描点"（这一个字段不值得丢掉整条记录）
                    kind = snapOf(codeAt(f.getOrNull(6))) ?: SnapKind.POINT,
                    colorIndex = color,
                    visible = boolOf(f[8]),
                    // 第 10 格（1.3.10）：吸附关系。老文件没有这一格 → null（自由点）。
                    attachment = if (f.size > 9) parseAttachment(f[9]) else null
                )
            }

            "L" -> {
                if (f.size < 12) return null
                val id = f[2].toLongOrNull() ?: return null
                val px = f[4].toDoubleOrNull() ?: return null
                val py = f[5].toDoubleOrNull() ?: return null
                val qx = f[6].toDoubleOrNull() ?: return null
                val qy = f[7].toDoubleOrNull() ?: return null
                val kind = lineOf(codeAt(f.getOrNull(8))) ?: return null
                val color = f[10].toIntOrNull() ?: return null
                FileObject.Line(
                    id = id,
                    name = unescape(f[3]),
                    line = GLine(GPoint(px, py), GPoint(qx, qy), kind),
                    role = unescape(f[9]),
                    colorIndex = color,
                    visible = boolOf(f[11])
                )
            }

            "M" -> {
                if (f.size < 10) return null
                val id = f[2].toLongOrNull() ?: return null
                val kind = FileMeasureKind.of(codeAt(f.getOrNull(3))) ?: return null
                val value = f[4].toDoubleOrNull() ?: return null
                val names = if (f[5].isEmpty()) {
                    emptyList()
                } else {
                    f[5].split(LIST_SEP).map { unescape(it) }
                }
                val pts = if (f[6].isEmpty()) {
                    emptyList()
                } else {
                    f[6].split(LIST_SEP).mapNotNull { pair ->
                        val xy = pair.split(PAIR_SEP)
                        if (xy.size != 2) return@mapNotNull null
                        val x = xy[0].toDoubleOrNull() ?: return@mapNotNull null
                        val y = xy[1].toDoubleOrNull() ?: return@mapNotNull null
                        GPoint(x, y)
                    }
                }
                val color = f[7].toIntOrNull() ?: return null
                FileObject.Measure(
                    id = id,
                    kind = kind,
                    value = value,
                    points = pts,
                    pointNames = names,
                    colorIndex = color,
                    visible = boolOf(f[8]),
                    showValue = boolOf(f[9])
                )
            }

            else -> null
        }
    }

    // ===========================================================================
    // 吸附关系（`O|P` 记录可选的最后一格，1.3.10）
    // ===========================================================================
    //
    // 编码用**冒号**分隔、整段只占一格：这一格本来就是一个字段，冒号让
    // "四个端点全空"（`i:-:-:-:-`，理论上不该出现）也能读回来（当作自由点）。
    // 数值一律走 [num]（和坐标同一套写法）。
    //
    // 只在**真的吸住了**才写这一格（见 encode 里的 `o.attachment?.let`），
    // 所以普通描点和本轮之前的文件逐字节相同。

    fun attachmentCode(a: PointAttachment): String = when (a) {
        is PointAttachment.OnCurve -> "c:${a.functionId}:${num(a.x)}"
        is PointAttachment.OnLine -> "l:${a.lineId}:${num(a.t)}"
        is PointAttachment.OnIntersection -> "i:" + listOf(
            a.aFunction, a.aLine, a.bFunction, a.bLine
        ).joinToString(":") { it?.toString() ?: NONE }
    }

    /** [attachmentCode] 的逆。认不出来 / 数值不是有限值 → null（当作自由点，不丢整条记录）。 */
    fun parseAttachment(text: String): PointAttachment? {
        val f = text.split(':')
        fun id(i: Int): Long? = f.getOrNull(i)?.toLongOrNull()
        fun d(i: Int): Double? = f.getOrNull(i)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        return when (f.firstOrNull()) {
            "c" -> id(1)?.let { fn -> d(2)?.let { PointAttachment.OnCurve(fn, it) } }
            "l" -> id(1)?.let { line -> d(2)?.let { PointAttachment.OnLine(line, it) } }
            "i" -> {
                val any = id(1) ?: id(2) ?: id(3) ?: id(4)
                if (any == null) {
                    null
                } else {
                    PointAttachment.OnIntersection(
                        aFunction = id(1), aLine = id(2),
                        bFunction = id(3), bLine = id(4)
                    )
                }
            }
            else -> null
        }
    }

    /**
     * 一条名字绑定记录 `B|函数id|字母|对象id`；认不出来返回 null（只丢这一条）。
     *
     * 校验三道（都是"宁可少收，不可收错"）：
     *  1. 字段数够 4；
     *  2. 函数 id / 对象 id 都解得成长整数（`Long`）；
     *  3. 字母必须长得像**标量名**（纯小写拉丁字母序列，见 `isScalarName`）——
     *     绑定表的键就是"用户在编辑器里敲的那个字母"，只可能是这个形状；
     *     手改文件写进来的怪东西（大写、带括号、空串）在这里被挡掉，
     *     免得它进到采样路径上去参与替换。
     *
     * 注意这里**不检查那个对象 id 是否真的存在**：文件里函数/对象的装载顺序无关，
     * 而且 `GraphState` 那边是按 id 现查的（查不到就是"这个字母不替换"，行为明确）。
     */
    private fun parseBinding(f: List<String>): Triple<Long, String, Long>? {
        if (f.size < 4) return null
        val fnId = f[1].toLongOrNull() ?: return null
        val letter = unescape(f[2])
        if (!isScalarName(letter)) return null
        val objId = f[3].toLongOrNull() ?: return null
        return Triple(fnId, letter, objId)
    }

    private fun parseTransform(f: List<String>): FileTransform? {
        if (f.size < 4) return null
        val id = f[1].toLongOrNull() ?: return null
        val axis = if (f[2] == NONE) {
            null
        } else {
            val a = f[2].split(PAIR_SEP)
            if (a.size != 5) return null
            val px = a[0].toDoubleOrNull() ?: return null
            val py = a[1].toDoubleOrNull() ?: return null
            val qx = a[2].toDoubleOrNull() ?: return null
            val qy = a[3].toDoubleOrNull() ?: return null
            val kind = lineOf(codeAt(a.getOrNull(4))) ?: return null
            GLine(GPoint(px, py), GPoint(qx, qy), kind)
        }
        val center = if (f[3] == NONE) {
            null
        } else {
            val c = f[3].split(PAIR_SEP)
            if (c.size != 2) return null
            val x = c[0].toDoubleOrNull() ?: return null
            val y = c[1].toDoubleOrNull() ?: return null
            GPoint(x, y)
        }
        if (axis == null && center == null) return null
        return FileTransform(functionId = id, axis = axis, center = center)
    }
}
