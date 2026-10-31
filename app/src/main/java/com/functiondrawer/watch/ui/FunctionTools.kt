package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.angleAt
import com.functiondrawer.watch.engine.distance
import com.functiondrawer.watch.engine.intersect

/**
 * 「功能界面」的工具目录与交互状态机（用户需求 #9）。
 *
 * 这个文件**故意不依赖 Compose / Android**：工具怎么分类、每一步要用户选什么、
 * 什么时候算完成、标量对象怎么取名，都是纯粹的规则，用离线单测覆盖
 * （`tools/run-tool-tests.ps1`），界面只管把点击事件喂进来、把结果画出去。
 *
 * ## 交互语义（这一轮按用户原话整段改过，下面所有代码都照它理解）
 *
 * 用户报的 bug 模式是"选了工具 → 点一个点 → 界面卡住（点不动、移不动）→
 * 切到功能界面发现工具变回了「移动」→ 切回来才正常"。三个根因：
 *
 *  1. **[ToolFlow.acceptedCount] 把"选中的对象 id"也数成一步。**
 *     [ToolSelection.PointSel] 是带对象 id 的（吸附到的点 / 现描的那个点），
 *     于是"距离"（两步的工具）点**一个**点就被判成"选够了"：结果算不出来
 *     （点数不够），界面又顺手退回了「移动」—— 用户看到的就是"点了没反应"。
 *  2. **做完一个对象就自动退回 [FunctionTool.MOVE]。** 用户要的是
 *     "可以选择多个角度之后手动切回移动才移动"，自动退出让他没法连续量。
 *  3. **界面把手势里的"是不是移动模式"捕捉成了快照**（那个改动在 `GraphScreen`，
 *     这里只负责把规则钉死）：退回移动之后那份快照还是旧的，于是
 *     点也点不动、拖也拖不动。现在工具**不会自己退出**，这条路径也就断了。
 *
 * 所以现在的语义是三条：
 *  * **工具一旦选中就一直保持激活**，可以连续做多个对象（角度一个接一个量下去）。
 *    每做完一个对象只是把 [ToolFlow] 清空重来（[ToolFlow.reset]），工具名不变；
 *  * **只有用户手动选「移动」**（功能界面那一栏 / 图像界面提示条上的入口 /
 *    图像界面的返回键）才恢复平移缩放；
 *  * **每一次点击都必须有明确结果**：要么推进（[ToolStepResult.Accepted]），
 *    要么由 [ToolFlow.step] 给一句"为什么没算数、还差什么"（界面直接 toast 它）。
 *    这里以前有"没有匹配分支就什么都不做"的死路，那正是"点了没反应"的来源。
 *
 * 用户原话里"没点到就不退出该状态"仍然建模成 [FunctionTool.staysUntilValid]
 * （轴对称/中心对称/垂线/平行线/切线）—— 它现在只影响提示语的措辞：
 * **所有**工具都不会因为一次无效点击而退出，只是那几个工具是用户点名要求的。
 */

/** 功能界面的七栏（每栏在界面上有个小标题）。 */
enum class ToolSection(val title: String) {
    BASIC("基本工具"),
    MEASURE("度量"),
    TRANSFORM("变换"),
    CONSTRUCT("作图"),
    LINE("直线"),

    /** 圆（用户要求："给功能界面最底下加一个栏，名字叫圆"）。 */
    CIRCLE("圆"),

    /** 圆锥曲线（紧跟在圆后面）。 */
    CONIC("圆锥曲线")
}

/** 选中的东西是什么。 */
enum class SelectionKind {
    /** 图上的一个点（可以是已有的，也可以是刚描/吸附出来的）。 */
    POINT,

    /** 一条曲线（函数或方程）。 */
    CURVE,

    /** 一条直线/线段/射线（几何对象，轴对称的对称轴、平行线的参考线用它）。 */
    LINE
}

/** 用户交给工具的一次选择。 */
sealed interface ToolSelection {
    data class PointSel(val point: GPoint, val objectId: Long? = null) : ToolSelection

    data class CurveSel(val functionId: Long) : ToolSelection

    /**
     * 一条直线。
     *
     * [objectId] 是图上一个**几何直线对象**（[com.functiondrawer.watch.ui.GraphLineObject]）。
     * [inline] 是"不来自任何对象的直线"——目前只有**坐标轴**这一种用法：
     * 用户点「轴对称」时最想做的事往往是"关于 x 轴对称"，而坐标轴并不是
     * 几何对象（没有对象 id），于是界面把那条轴本身塞在这里送进来
     * （见 `GraphState.axisLineAt`）。它默认 null，所以原有的构造点一字不改。
     */
    data class LineSel(val objectId: Long, val inline: com.functiondrawer.watch.engine.GLine? = null) :
        ToolSelection

    val kind: SelectionKind
        get() = when (this) {
            is PointSel -> SelectionKind.POINT
            is CurveSel -> SelectionKind.CURVE
            is LineSel -> SelectionKind.LINE
        }
}

/**
 * 工具的一步：这一步要选什么、提示语写什么。
 *
 * [anyObject] = true 的那一步是"**选一个对象**"（轴对称/中心对称的第一步）：
 * 点、函数/方程曲线、几何直线**都算对象**。
 * 以前这一步只认 [SelectionKind.POINT]，于是用户在图上点一条曲线时会被当成
 * "在这儿描一个新点"，做出来的东西完全不是他要的 —— 而且那一步一"完成"，
 * 界面就退回了「移动」（用户报的"轴对称和中心对称在函数图像页面都点不动"）。
 *
 * [text] 是界面顶部提示条要说的那句话，**写短一点**（表盘只有 233dp 宽，
 * 那一条最多两行，前面还要接工具名）。
 */
class ToolStep(
    val kind: SelectionKind,
    val anyObject: Boolean = false,
    val text: String = "",
    /**
     * 这一步除了 [kind] 之外**还认**哪一种选择；选了它就**换一条岔路**走
     * （见 [FunctionTool.stepsFor]）。null（默认）= 只有一条路。
     *
     * 现在只有「角度」用它，因为用户给角度定的是**两条路**（原话逐字）：
     * "先点击两条线段**或者**调用线段工具点击三个点创建两条线段……再创建角度"。
     *   * 主路：点两条已有的线段/直线（[SelectionKind.LINE]）；
     *   * 岔路：连点三个点（[SelectionKind.POINT]）→ 先按线段工具的规则
     *     建出两条线段（自动命名、进函数列表），再拿它们量角。
     * 所以第一步**同时**认这两种，选了哪种就决定后面还要选什么 —— 这就是
     * [ToolFlow.altBranch] 存在的意义。
     */
    val altKind: SelectionKind? = null
) {
    /** 这一步认不认这种选择。 */
    fun accepts(kind: SelectionKind): Boolean =
        anyObject || this.kind == kind || this.altKind == kind

    /** 给用户看的"这一步要选什么"（toast 和提示条共用一句话）。 */
    val requirement: String
        get() = when {
            anyObject -> "选一个对象（点、曲线或直线）"
            // 两种都认的那一步要把两条路都说出来，否则提示语会把用户赶进死胡同
            altKind == SelectionKind.POINT && kind == SelectionKind.LINE ->
                "点一条线段/直线（或者点角的第一个点，自己建两条边）"

            kind == SelectionKind.POINT -> "选一个点"
            kind == SelectionKind.CURVE -> "选一条函数/方程曲线"
            else -> "选一条直线/线段"
        }
}

/** 一步"选一个点"，只要一行提示语。 */
private fun needsPoint(text: String) = ToolStep(SelectionKind.POINT, text = text)

/** 一步"选一条直线/线段"，只要一行提示语。 */
private fun needsLine(text: String) = ToolStep(SelectionKind.LINE, text = text)

/** 一步"选一个对象"（点 / 曲线 / 直线都算）。 */
private fun needsObject(text: String) = ToolStep(SelectionKind.POINT, anyObject = true, text = text)

/**
 * 一个工具。
 *
 * [staysUntilValid] 是用户点名的工具（"没点击到直线不退出该状态"/"没选择到不退出"）：
 * 它的含义是"**连工具本身都不退出**"。现在所有工具都满足这一点（见文件头），
 * 所以它只用来挑提示语的措辞（"没点到，请再点一次"那种口气）。
 */
enum class FunctionTool(
    val label: String,
    val section: ToolSection,
    /** 该工具第 index 步要选什么；空表 = 这个工具不吃点选（移动 / 描点）。 */
    private val steps: List<ToolStep>,
    /** 是否允许无限追加（面积：一直点，直到点回第一个点）。 */
    val openEnded: Boolean = false,
    val staysUntilValid: Boolean = false,
    /** 工具级别的提示语（没走到某一步时用它兜底）。 */
    val hint: String = "",
    /**
     * **岔路**的后续步骤（选了 `steps[0].altKind` 之后改用它）。
     *
     * 第 0 步是两条路共用的（它 [ToolStep.accepts] 两种都认），所以这里只写
     * "第 1 步往后"要选什么 —— [stepsFor] 会自动把第 0 步接回去。
     * 空表（默认）= 这个工具没有岔路。
     */
    private val altSteps: List<ToolStep> = emptyList()
) {
    /** 移动：默认就是它，什么都不选，直接拖动图像。 */
    MOVE("移动", ToolSection.BASIC, emptyList(), hint = "拖动图像"),

    /** 描点：按住就出点、松手定住（跟手那一半在界面里，见 GraphScreen）。 */
    PLOT_POINT(
        "描点", ToolSection.BASIC, emptyList(), openEnded = true,
        hint = "按住就出现点，松手定住；可以连续描"
    ),

    /**
     * 角度：**先有两条边，再量角**（用户原话："角度的标记请改成先点击两条线段
     * 或者调用线段工具点击三个点创建两条线段（创建的线段自动命名并且放在函数列表里）
     * 再创建角度，角度只包含这个角，不包含两条线段"）。
     *
     * 所以它和别的工具不一样：第 0 步有**两条路**
     *   * 主路：点一条已有的线段/直线 → 第 1 步再点第二条 → 出角度；
     *   * 岔路（[ToolStep.altKind]）：点角的第一个点 → 后面的步骤改看 [altSteps]，
     *     再点两个点。三个点齐了之后**先按线段工具的规则建出两条线段**
     *     （`P1P2` 和 `P2P3`，自动命名、出现在函数列表里），
     *     再拿这两条线段去量角 —— 两条线段是独立对象，角度里**不含**它们。
     *
     * 量出来的角度对象只存"顶点 + 两条边所指的方向上各一个点"（[points]），
     * 换句话说它只有这个角本身；那两条线段该显示/该删除都跟它无关。
     */
    ANGLE(
        "角度", ToolSection.MEASURE,
        listOf(
            ToolStep(
                kind = SelectionKind.LINE,
                altKind = SelectionKind.POINT,
                text = "点第一条边（线段/直线），或者点角的第一个点"
            ),
            needsLine("再点第二条边（线段/直线）")
        ),
        altSteps = listOf(
            needsPoint("点这个角的顶点"),
            needsPoint("再点第三个点（会先建出两条线段，再量角）")
        ),
        hint = "点两条线段，或者连点三个点自己建两条边"
    ),

    /** 距离：两个点。 */
    DISTANCE(
        "距离", ToolSection.MEASURE,
        listOf(needsPoint("点第一个点"), needsPoint("点第二个点")),
        hint = "点两个点"
    ),

    /** 面积：多点，最后点回第一个点结束。 */
    AREA(
        "面积", ToolSection.MEASURE,
        listOf(needsPoint("依次点多边形顶点")),
        openEnded = true,
        hint = "依次点多个点，最后点回第一个点"
    ),

    /** 轴对称：先选对象（点/函数/方程/直线），再选一条直线当对称轴。 */
    AXIS_SYMMETRY(
        "轴对称", ToolSection.TRANSFORM,
        listOf(
            needsObject("先点要对称的对象"),
            needsLine("再点一条直线当对称轴")
        ),
        staysUntilValid = true,
        hint = "先点对象，再点一条直线"
    ),

    /** 中心对称：先选对象（点/函数/方程/直线），再选对称中心。 */
    CENTER_SYMMETRY(
        "中心对称", ToolSection.TRANSFORM,
        listOf(
            needsObject("先点要对称的对象"),
            needsPoint("再点对称中心")
        ),
        staysUntilValid = true,
        hint = "先点对象，再点对称中心"
    ),

    /** 中点：两个点。 */
    MIDPOINT(
        "中点", ToolSection.CONSTRUCT,
        listOf(needsPoint("点第一个点"), needsPoint("点第二个点")),
        hint = "点两个点"
    ),

    /** 垂线：先点一个点，再点一条直线（过点作垂线）。 */
    PERPENDICULAR(
        "垂线", ToolSection.CONSTRUCT,
        listOf(needsPoint("先点直线外的一个点"), needsLine("再点一条直线")),
        staysUntilValid = true,
        hint = "先点一个点，再点一条直线"
    ),

    /** 中垂线：两个点。 */
    PERP_BISECTOR(
        "中垂线", ToolSection.CONSTRUCT,
        listOf(needsPoint("点第一个点"), needsPoint("点第二个点")),
        hint = "点两个点"
    ),

    /** 平行线：先选参考线，再选要经过的点。 */
    PARALLEL(
        "平行线", ToolSection.CONSTRUCT,
        listOf(needsLine("先点一条参考线"), needsPoint("再点要经过的点")),
        staysUntilValid = true,
        hint = "先点一条线，再点要经过的点"
    ),

    /** 角平分线：三个点，第二个是顶点。 */
    BISECTOR(
        "角平分线", ToolSection.CONSTRUCT,
        listOf(
            needsPoint("点角的第一个点"),
            needsPoint("点角的顶点"),
            needsPoint("点角的第三个点")
        ),
        hint = "依次点三个点（第二个是顶点）"
    ),

    /** 切线：在函数/方程上点一个点；点不到就不退出。 */
    TANGENT(
        "切线", ToolSection.CONSTRUCT,
        listOf(needsPoint("点函数或方程上的一点")),
        staysUntilValid = true,
        hint = "点函数或方程上的一点"
    ),

    /** 线段：两个点。 */
    SEGMENT(
        "线段", ToolSection.LINE,
        listOf(needsPoint("点第一个端点"), needsPoint("点第二个端点")),
        hint = "点两个点"
    ),

    /** 直线：两个点。 */
    STRAIGHT(
        "直线", ToolSection.LINE,
        listOf(needsPoint("点第一个点"), needsPoint("点第二个点")),
        hint = "点两个点"
    ),

    /** 射线：两个点（第一个是起点）。 */
    RAY(
        "射线", ToolSection.LINE,
        listOf(needsPoint("点射线的起点"), needsPoint("点射线经过的另一个点")),
        hint = "点两个点（第一个是起点）"
    ),

    // ---- 第六栏：圆 ----
    //
    // 五种作图方式和 GeoGebra 的「圆」工具一一对应（用户点名要参照它的操作）。
    // 产出都是**一条隐函数曲线**（见 `CircleConics.kt`），所以从这一栏做出来的圆
    // 和普通函数一样出现在列表里、能显隐、能改色、能删除。

    /** 圆（圆心与一点）：点圆心，再点圆上的一点。 */
    CIRCLE_CENTER_POINT(
        "圆心·点", ToolSection.CIRCLE,
        listOf(needsPoint("点圆心"), needsPoint("点圆上的一点")),
        hint = "点圆心，再点圆上一点"
    ),

    /**
     * 圆（圆心与半径）：**先选圆心，再选两点或者一条线段**（用户原话）。
     *
     * 半径取"那两点之间的距离"或者"那条线段的长度"。选线段那一步在
     * `GraphState.toolTap` 里特判（状态机本身只认点，见那里的说明）。
     */
    CIRCLE_CENTER_RADIUS(
        "圆心·半径", ToolSection.CIRCLE,
        listOf(
            needsPoint("点圆心"),
            needsPoint("点第一个半径端点（或直接点一条线段）"),
            needsPoint("点第二个半径端点")
        ),
        hint = "先点圆心，再点两点或一条线段"
    ),

    /**
     * 圆（半径与圆心）：点圆心之后**弹出键盘输入半径**。
     *
     * 半径是一个表达式（`2.5`、`d`、`d/2`…），所以能直接引用滑动条 ——
     * 用户原话"这样还可以调用其他变量的值，比如设置 d 为半径"。
     * 输入面板在 `GraphScreen` 里，状态机只负责收到"圆心"这一步。
     */
    CIRCLE_RADIUS_CENTER(
        "半径·圆心", ToolSection.CIRCLE,
        listOf(needsPoint("点圆心，下一步输入半径")),
        hint = "点圆心，再输入半径"
    ),

    /** 圆（过三点）：三点确定一个圆。 */
    CIRCLE_THREE_POINTS(
        "过三点", ToolSection.CIRCLE,
        listOf(
            needsPoint("点圆上第一点"),
            needsPoint("点圆上第二点"),
            needsPoint("点圆上第三点")
        ),
        hint = "依次点三个点"
    ),

    /** 圆弧：圆心 + 起点 + 终点（逆时针从起点转到终点，和 GeoGebra 一致）。 */
    CIRCLE_ARC(
        "圆弧", ToolSection.CIRCLE,
        listOf(
            needsPoint("点圆心"),
            needsPoint("点弧的起点"),
            needsPoint("点弧的终点")
        ),
        hint = "点圆心、起点、终点"
    ),

    // ---- 第七栏：圆锥曲线 ----

    /** 椭圆：两个焦点 + 椭圆上一点。 */
    ELLIPSE(
        "椭圆", ToolSection.CONIC,
        listOf(
            needsPoint("点第一个焦点"),
            needsPoint("点第二个焦点"),
            needsPoint("点椭圆上一点")
        ),
        hint = "点两个焦点，再点椭圆上一点"
    ),

    /** 圆锥曲线：过五点的任意圆锥曲线（和 GeoGebra 的「圆锥曲线」工具一样）。 */
    CONIC_FIVE_POINTS(
        "圆锥曲线", ToolSection.CONIC,
        listOf(
            needsPoint("点第一个点"),
            needsPoint("点第二个点"),
            needsPoint("点第三个点"),
            needsPoint("点第四个点"),
            needsPoint("点第五个点")
        ),
        hint = "依次点五个点"
    ),

    /** 抛物线：焦点 + 准线（先用「直线」工具画一条，再点它）。 */
    PARABOLA(
        "抛物线", ToolSection.CONIC,
        listOf(needsPoint("点焦点"), needsLine("点准线（可以先用直线工具画一条）")),
        hint = "点焦点，再点准线"
    ),

    /** 双曲线：两个焦点 + 曲线上一点。 */
    HYPERBOLA(
        "双曲线", ToolSection.CONIC,
        listOf(
            needsPoint("点第一个焦点"),
            needsPoint("点第二个焦点"),
            needsPoint("点曲线上一点")
        ),
        hint = "点两个焦点，再点曲线上一点"
    );

    /**
     * 第 [index] 步（从 0 开始）本身；返回 null 表示已经选够了。
     *
     * [openEnded] 的工具永远返回第 0 步那张表（"一直点同一种东西"，
     * 结束由"点回第一个点"决定，见 [ToolFlow.step]）。
     *
     * 这是**主路**的取法（不看 [altSteps]）。要看岔路的用 [stepAtFor]。
     */
    fun stepAt(index: Int): ToolStep? =
        if (openEnded) steps.firstOrNull() else steps.getOrNull(index)

    /**
     * 整条步序表：[alt] = true 时用**岔路**（第 0 步仍然是共用的那一步，
     * 因为岔路就是从它身上分出去的）。
     */
    fun stepsFor(alt: Boolean): List<ToolStep> =
        if (alt && altSteps.isNotEmpty()) listOf(steps.first()) + altSteps else steps

    /** 第 [index] 步（按 [alt] 选的那条路）。 */
    fun stepAtFor(index: Int, alt: Boolean): ToolStep? =
        if (openEnded) stepsFor(alt).firstOrNull() else stepsFor(alt).getOrNull(index)

    /** 这条路上第 [index] 步要选什么；返回 null 表示已经够了。 */
    fun kindAtFor(index: Int, alt: Boolean): SelectionKind? = stepAtFor(index, alt)?.kind

    /** 第 index 步要选什么；返回 null 表示已经够了。 */
    fun kindAt(index: Int): SelectionKind? = stepAt(index)?.kind

    /** 第 [index] 步认不认 [kind] 这种选择（"选对象"那一步三种都认）。 */
    fun acceptsAt(index: Int, kind: SelectionKind): Boolean =
        stepAt(index)?.accepts(kind) ?: false

    /** 一共需要几次选择；[openEnded] 的工具返回 null（由"点回第一个点"决定结束）。 */
    val stepCount: Int? get() = if (openEnded) null else steps.size

    /** 这条路上一共需要几次选择（岔路和主路的步数不一定一样）。 */
    fun stepCountFor(alt: Boolean): Int? = if (openEnded) null else stepsFor(alt).size

    companion object {
        /** 按栏分组，界面直接照着画（保持声明顺序）。 */
        fun bySection(section: ToolSection): List<FunctionTool> =
            entries.filter { it.section == section }
    }
}

/**
 * 一次 [ToolFlow.step] 的结果。
 *
 * 做成 sealed 而不是继续用 Boolean：用户要求"**任何一次点击都要有明确结果**——
 * 要么推进、要么给一句 toast 说明还差什么"。Boolean 只能表达"算不算数"，
 * 说不出"为什么"和"下一步该点什么"，于是界面只能什么都不做（那正是 bug）。
 */
sealed interface ToolStepResult {
    /** 这一步被接受了（可能已经可以出结果，见 [ToolFlow.isComplete]）。 */
    data object Accepted : ToolStepResult

    /** 这一步不算数；[message] 是界面直接 toast 的那句话（一定要说清还差什么）。 */
    data class Rejected(val message: String) : ToolStepResult
}

/**
 * 一次工具会话的状态机。
 *
 * 用法（界面）：用户选了工具 → `ToolFlow(tool)`；每次点击 → [step]：
 *   * [ToolStepResult.Accepted]：这一步被接受（把点/对象记下来，步数 +1）；
 *   * [ToolStepResult.Rejected]：这一步**不合法**（比如该选直线却点到了函数），
 *     把 [ToolStepResult.Rejected.message] 原样 toast 给用户，状态**留在原地**。
 * 之后用 [isComplete] 判断是不是该出结果了；出完结果由界面调 [reset] 清空重来
 * （**工具本身不退**，用户可以连续做下一个对象）。
 *
 * 面积这种开放式的工具：[isComplete] 在"点回第一个点"时变 true —— 这是用户
 * 明确要求的结束方式（"最后点击开始的第一个点，显示面积"）。
 *
 * ### 为什么把"步数"和"对象 id"分开
 *
 * [acceptedCount] **只能**来自"接受了几次选择"（[selections] 的大小）。
 * 以前它写的是 `pickedPoints.size + (选中的对象 id ?: 0)`，而
 * [ToolSelection.PointSel] 自己就带对象 id（吸附到的点 / 刚现描的点），
 * 一个点被数成两步 —— "距离"点一个点就 `isComplete()`，结果必然算不出来，
 * 界面又顺手退回了「移动」，用户看到的就是"点了没反应、然后什么都点不动"。
 */
class ToolFlow(val tool: FunctionTool) {

    /** 按顺序接受下来的选择：**一步一个**，"还差几步"只看它的大小。 */
    private val picks = mutableListOf<ToolSelection>()

    /** 已经接受的、按顺序的图元点（度量/直线/作图都只需要点的坐标）。 */
    val points: List<GPoint>
        get() = picks.filterIsInstance<ToolSelection.PointSel>().map { it.point }

    /**
     * 全部选择（按顺序）。
     *
     * 轴对称/中心对称靠它把"第 0 步 = 对象、第 1 步 = 轴/中心"分开取 ——
     * 以前两个角色共用 [pickedObjectId] 一个槽，第二步选完轴之后
     * "被变换的对象"就变成了那条轴本身，于是轴对称一条曲线永远走不到
     * 曲线的分支（用户报的"轴对称点不动"）。
     */
    val selections: List<ToolSelection> get() = picks

    /** 第 [index] 步选中的东西；没选到就是 null。 */
    fun selectionAt(index: Int): ToolSelection? = picks.getOrNull(index)

    /** 选择里带的 id（点/曲线/直线各自那一个）。 */
    private fun idOf(selection: ToolSelection): Long? = when (selection) {
        is ToolSelection.PointSel -> selection.objectId
        is ToolSelection.CurveSel -> selection.functionId
        is ToolSelection.LineSel -> selection.objectId
    }

    /**
     * 最近一次"能提供 id 的选择"里的那个 id。
     *
     * 切线用它知道"这个点落在哪条函数/方程上"（要用那条曲线的表达式求导）。
     *
     * ⚠️ 它**不参与**"还差几步"的计算（见文件头那条根因 1）。
     */
    val pickedObjectId: Long? get() = picks.asReversed().firstNotNullOfOrNull { idOf(it) }

    /** 选中的曲线 id（切线要知道是哪条函数）。 */
    var pickedCurveId: Long? = null
        private set

    /** 已经接受的**步数**（= 选择了几次）。 */
    val acceptedCount: Int get() = picks.size

    /** 现在是第几步（0 开始）。 */
    val stepIndex: Int get() = picks.size

    /** 还差几步；开放式的（面积）返回 null。 */
    val stepsLeft: Int? get() = tool.stepCount?.let { (it - picks.size).coerceAtLeast(0) }

    /** 面积那种开放式工具的收尾标志（"点回第一个点"）。 */
    var closed: Boolean = false
        private set

    /**
     * 走的是不是**岔路**（第一步选的那一种选择决定的，见 [ToolStep.altKind]）。
     *
     * 现在只有「角度」会走到 true：第一步点了**一个点**（而不是一条线段），
     * 后面就按"再点顶点、再点第三个点"走，三个点齐了先建两条线段再量角。
     * 一旦定下来就**这一轮不再改** —— 想换一条路，[reset] 之后重新点第一步
     * （这正是"任何一次点击都有明确结果"：不会中途悄悄换路，把已经选的东西
     * 解释成另一种东西）。
     */
    var altBranch: Boolean = false
        private set

    /** 当前这一步（按已经定下来的那条路取）；null = 不用再选了。 */
    fun stepAt(index: Int): ToolStep? = tool.stepAtFor(index, altBranch)

    /** 下一步要选什么；null = 不用再选了（该出结果或已经完成）。 */
    fun expected(): SelectionKind? {
        if (tool == FunctionTool.MOVE) return null
        if (tool == FunctionTool.PLOT_POINT) return null
        return stepAt(picks.size)?.kind
    }

    /** 界面提示条上那句话（"点角的顶点（第 2/3 步）"）。 */
    fun hintText(): String {
        if (tool == FunctionTool.MOVE || tool == FunctionTool.PLOT_POINT) return tool.hint
        if (closed) return "点回第一个点，正在出结果…"
        val spec = stepAt(picks.size) ?: return tool.hint
        val text = spec.text.ifEmpty { tool.hint }
        val total = tool.stepCountFor(altBranch)
        return if (total != null && total > 1) "$text（第 ${picks.size + 1}/$total 步）" else text
    }

    /**
     * 接受一次选择。
     *
     * 返回 false = 这一步不合法（等价于 [step] 给了 [ToolStepResult.Rejected]）。
     * 想知道"为什么"，用 [step] 拿那句话。
     */
    fun accept(selection: ToolSelection): Boolean = step(selection) is ToolStepResult.Accepted

    /**
     * 接受一次选择，并把"为什么没算数"一起给出来。
     *
     * **每一个状态 × 每一种选择都有明确的返回值**：要么推进，要么给一句说明。
     * 这条规则是用户点名的（"任何一次点击、任何顺序的点选都必须有明确结果"）——
     * 以前这里会走到"没有匹配的分支就 return false 且界面也不说话"，于是
     * 用户点了第二下什么都没发生，还以为界面死了。
     */
    fun step(selection: ToolSelection): ToolStepResult {
        if (tool == FunctionTool.MOVE) {
            return ToolStepResult.Rejected("当前是「移动」工具：拖动就是平移图像")
        }
        if (tool == FunctionTool.PLOT_POINT) {
            return ToolStepResult.Rejected("描点不用选目标：按住就出点，松手定住")
        }

        val spec = stepAt(picks.size)
            ?: return ToolStepResult.Rejected(
                "${tool.label}：这一轮已经选够了，正在出结果（再点一下就重新开始）"
            )

        // ---- 面积这类开放式的：点回第一个点才算完 ----
        if (tool.openEnded && selection is ToolSelection.PointSel) {
            return acceptOpenEnded(selection)
        }

        if (!spec.accepts(selection.kind)) {
            return ToolStepResult.Rejected("${tool.label}：这一步要${spec.requirement}")
        }

        when (selection) {
            is ToolSelection.PointSel -> {
                // 同一个点选两次：给一句明确的话，而且**不让它成为一步** ——
                // 否则会量出 0 距离、退化的角（三个点里两个重合）。
                val dup = points.indexOfFirst { samePoint(it, selection.point) }
                if (dup >= 0) {
                    return ToolStepResult.Rejected(
                        "这个点已经选过了（第 ${dup + 1} 个），请换一个点"
                    )
                }
                picks += selection
                // 岔路就在这里定下来：这一步**同时认**线段和点（[ToolStep.altKind]），
                // 选了点就走"连点三个点、自己建两条边"那条路（角度专用）。
                if (spec.altKind == SelectionKind.POINT && spec.kind != SelectionKind.POINT) {
                    altBranch = true
                }
                // 切线要知道"这个点落在哪条函数/方程上"（单测抓到过这里漏了）
                if (tool == FunctionTool.TANGENT && selection.objectId != null) {
                    pickedCurveId = selection.objectId
                }
            }

            is ToolSelection.CurveSel -> {
                picks += selection
                pickedCurveId = selection.functionId
            }

            is ToolSelection.LineSel -> {
                picks += selection
            }
        }
        return ToolStepResult.Accepted
    }

    /**
     * 开放式工具（面积）的一次点选。
     *
     * 三条规则都要有话说：
     *   * 点回第一个点且已经有 [MIN_AREA_POINTS] 个顶点 → 收尾；
     *   * 顶点还不够就点第一个点 → "还差几个点"（以前这里会静悄悄把第一个点
     *     再加一遍，围出来的多边形是退化的）；
     *   * 点到任何一个已经选过的顶点 → "这个点已经选过了"。
     */
    private fun acceptOpenEnded(selection: ToolSelection.PointSel): ToolStepResult {
        val first = points.firstOrNull()
        if (first != null && points.size >= MIN_AREA_POINTS && samePoint(first, selection.point)) {
            closed = true
            return ToolStepResult.Accepted
        }
        val dup = points.indexOfFirst { samePoint(it, selection.point) }
        if (dup >= 0) {
            val left = MIN_AREA_POINTS - points.size
            return ToolStepResult.Rejected(
                if (left > 0) {
                    "面积至少要 $MIN_AREA_POINTS 个点，还差 $left 个（点回第一个点才算完）"
                } else {
                    "这个点已经选过了（第 ${dup + 1} 个）；要收尾就再点一次第一个点"
                }
            )
        }
        picks += selection
        return ToolStepResult.Accepted
    }

    /** 选够了没有（面积要看收尾标志 [closed]）。 */
    fun isComplete(): Boolean {
        if (closed) return true
        if (tool == FunctionTool.MOVE || tool == FunctionTool.PLOT_POINT) return false
        val need = tool.stepCountFor(altBranch) ?: return false
        return picks.size >= need
    }

    /**
     * 重来：清掉这一轮的选择，**但工具本身仍然保持选中**。
     *
     * 用户原话："正确逻辑应该是可以选择多个角度之后手动切回移动才移动" ——
     * 所以做完一个对象之后由界面调它，用户接着点下一个就行，不用回功能界面。
     *
     * 岔路（[altBranch]）也一起清：下一轮从"点线段还是点第一个点"重新分。
     */
    fun reset() {
        picks.clear()
        pickedCurveId = null
        closed = false
        altBranch = false
    }

    /** 两个点是不是"同一个点"（界面已经吸附过了，所以只需要一个极小容差）。 */
    private fun samePoint(a: GPoint, b: GPoint): Boolean =
        kotlin.math.hypot(a.x - b.x, a.y - b.y) < SAME_POINT_TOLERANCE

    companion object {
        /** 面积至少要有几个顶点才能围成多边形。 */
        const val MIN_AREA_POINTS = 3

        /** 判定"同一个点"的容差（世界坐标）。吸附之后坐标通常**完全相同**。 */
        private const val SAME_POINT_TOLERANCE = 1e-6
    }
}

// ===================================================================================
// 「这条函数有没有内容」—— 编辑器判空、保存过滤**共用同一条判据**
// ===================================================================================

/**
 * 这条公式/方程**是不是一个字都没写**（两栏表达式 trim 之后都是空）。
 *
 * ## 为什么必须是一条共用判据
 *
 * 同一件事有两个地方要问："编辑器里它算不算空"和"存文件时要不要写它"。
 * 两处各写一份判断，迟早会出现**编辑器认为它是空的、存档却把它写进去**这种
 * 自相矛盾的状态 —— 用户看到的就是"我明明没输入，文件里却多了一条没有表达式的函数"
 * （真实报上来的 bug）。所以判据只留这一份，两处都调它。
 *
 * ## 什么时候会出现"没有表达式的函数"
 *
 * `+` 是**先建一条空函数再进编辑器**的（这样编辑器里能边打边看），
 * 正常情况下"取消 / 空输入确定"会把它丢掉。但只要有**任何一条**别路径让它活下来
 * （历史上的例子：长按 `+` 存文件时顺带触发了一次点击，既开了编辑器又留下了空函数），
 * 它就会跟着存档一起被写进文件。所以除了"离开编辑器时清掉草稿"（`GraphState.discardBlankDraft`）
 * 之外，**保存这一侧也必须过滤**（[functionsToSave]）—— 两层都要。
 *
 * ## 判据的边界
 *
 * * `""` / `"   "` / 换行 → **空**（用 `isBlank`，不是 `isEmpty`：用户敲了一串空格
 *   和什么都没敲是一样的意思）；
 * * 参数方程只写了一栏（`src1` 空、`src2` 有）→ **不算空**：那是写了一半的式子，
 *   引擎会报"另一栏没填"，而**报错**是用户能看见、能改的；把它悄悄丢掉反而糟糕；
 * * 前缀（`y=`）不算内容：`y=` 单独一条在引擎里也是空表达式，但这里不替它剥前缀 ——
 *   判断"空"只看这两栏，和编辑器那条 `src1.isBlank() && src2.isBlank()` 一字不差。
 */
fun PlotFunction.hasNoExpression(): Boolean = src1.isBlank() && src2.isBlank()

/**
 * 存进 `.fdf` 函数文件时该写哪些函数：**跳过"一个字都没写"的那种**。
 *
 * 这是用户报的那个 bug 的兜底（"保存文件的时候会在文件里添加一个没有表达式的函数"）：
 * 就算某条空函数还活着（前面几道清理都没赶上），写文件这一份也不会带上它 ——
 * 而且**内存里的列表一个元素都不动**（这是个 filter，返回新表），
 * 用户正在编辑的那条仍然留在界面上。
 *
 * 边界：**空文件是允许的**。用户可能只画了几何对象（角度/距离）或者只有滑动条，
 * 那照样存得下来（"清空函数"之后长按 `+` 存一份只有对象的文件是合理操作）——
 * 这里过滤的是"没有表达式的函数"，不是"内容少"。
 */
fun functionsToSave(list: List<PlotFunction>): List<PlotFunction> = list.filterNot { it.hasNoExpression() }

// ===================================================================================
// 标量对象（角度/距离/面积）的取名规则 —— 纯逻辑，离线单测覆盖
// ===================================================================================

/**
 * **引擎里有含义的名字**：这些字母/词在表达式里已经有别的意思。
 *
 * | 名字 | 引擎里是什么 |
 * |---|---|
 * | `a` `b` `c` `d` | 可绑定参数（`ParameterSet.BINDABLE`）：拖滑动条改的就是它们 |
 * | `x` `y` `t` `θ` | **采样变量**：显函数用 `x`、极坐标用 `θ`、参数方程用 `t`、隐函数用 `x`/`y` |
 * | `z` | **复平面模式的主变量**（`ComplexMath.COMPLEX_VARIABLE = "z"`） |
 * | `e` | 自然常数（`MathFns.CONSTANTS["e"]`）；词法层还专门处理 `2e` 要读成 `2*e` |
 * | `i` | 虚数单位（`ComplexMath.COMPLEX_CONSTANTS["i"]`） |
 * | `pi` `π` `tau` `τ` `phi` `φ` `inf` `infinity` `nan` | 引擎的其它常量 |
 *
 * ## 这张表**已经不是取名时的跳过表**了（用户要求那一轮改的）
 *
 * 用户原话："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
 * 标量也可以用 a，滑动条也可以用 f。至于 e、x、y 这些可能冲突的字母，
 * 请你给函数编辑界面输入的这些字母单独分组编 id，这样也不用担心字母冲突的问题。"
 *
 * 所以 [nextScalarName] **不再跳过任何字母**（它只避开真正被占用的名字），
 * 标量可以叫 `a`、滑动条可以叫 `f`。撞上这张表里的名字也**不再是错误** ——
 * 它变成一个"需要用户指认"的情形：在函数编辑器里给那个字母**绑定**到具体对象
 * （见 [NameCandidate] / `GraphState.functionBindings`），采样前先查绑定表。
 *
 * 留下来的用途只有两个：
 *   1. 告诉**编辑器**"这个字母引擎自己会先认领"，所以必须提示用户选定它指谁；
 *   2. 让后人一眼看到"哪些名字在引擎里有别的含义"这张清单（比翻四个文件快）。
 */
val SCALAR_NAME_RESERVED: Set<String> = setOf(
    // 可绑定参数（滑动条抓的就是它们）
    "a", "b", "c", "d",
    // 采样变量：显函数 / 极坐标 / 参数方程 / 隐函数
    "x", "y", "t", "θ",
    // 复平面模式的主变量
    "z",
    // 引擎常量（含虚数单位）
    "e", "i",
    "pi", "π", "tau", "τ", "phi", "φ", "inf", "infinity", "nan"
)

/**
 * 第一个没被占用的标量对象名：`a`、`b`、`c`…（**不再跳过任何字母**）。
 *
 * 用户原话："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
 * 标量也可以用 a，滑动条也可以用 f。" 于是这里**连 [SCALAR_NAME_RESERVED] 都不跳**
 * —— 那种"名字撞上了引擎里有含义的字母"的情况改由**绑定表**解决
 * （编辑器里让用户选定这个字母指谁，采样前先查表，见 `PointCoordinates.kt`）。
 *
 * 仍然要避开的只有 [taken]：函数名/可调用名（`a(x)` 这种要取括号前那一截）、
 * 以及列表里已有的对象名 —— 那两个是真·冲突（列表里两行同名、或者 `f(...)`
 * 到底是调用函数还是取标量），不是"引擎内置"那种可以靠绑定表说清的事。
 *
 * ⚠️ **滑动条绑定的参数字母不再进 [taken]**（这是本轮有意改的）：用户明确允许
 * 两类名字重名，所以标量可以被分配成 `a` 而同时存在一条 `a` 滑动条 —— 那时
 * 表达式里的 `a` 就有了两种含义，必须由用户在编辑器里选定（[NameCandidate]）。
 * 代价写在报告里：自动分配有可能一上来就制造一个"需要指认"的字母。
 *
 * 序列按双射 26 进制进位（`z` → `aa` → `ab`…），和函数名那套规则一致
 * （见 `GraphState.nextCurveName`），所以对象多了也不会撞名字。
 */
fun nextScalarName(taken: Set<String>): String {
    var i = 0
    while (true) {
        val candidate = lowerLetters(i)
        if (candidate !in taken) return candidate
        i++
    }
}

/** 小写字母序列：0→a、25→z、26→aa、27→ab…（双射 26 进制，和表格列名一样）。 */
fun lowerLetters(index: Int): String {
    var n = index.coerceAtLeast(0)
    val sb = StringBuilder()
    while (true) {
        sb.insert(0, ('a' + (n % 26)))
        n = n / 26 - 1
        if (n < 0) break
    }
    return sb.toString()
}

// ===================================================================================
// 角度：从**两条线段/直线**量出一个角 —— 纯几何，离线单测覆盖
// ===================================================================================

/**
 * 「两条边」量出来的一个角：顶点 + 两条边**各自所指方向**上的一个点 + 度数。
 *
 * 用户原话："角度只包含这个角，不包含两条线段。"
 * 所以这里存下来的就是**角本身**：[vertex] 和两个方向（用 [arm1] / [arm2] 两个点表示），
 * 那两条线段是**独立的对象**，有自己的名字、在函数列表里，和这个角度没有从属关系
 * （删掉它们角度照样在，把角度关掉线段照样在）。
 *
 * [arm1] / [arm2] 到 [vertex] 的距离**就是那两条边实际伸出去多长**
 * （线段的真长度；无限直线/射线用一个默认长度，见 [ANGLE_DEFAULT_ARM]）——
 * 渲染时按"较短那条边"的比例取扇形半径（`GraphCanvas.drawMeasure`），
 * 所以这个距离不是随便取的，它决定了扇形画多大。
 */
data class AngleFromLines(
    val vertex: GPoint,
    val arm1: GPoint,
    val arm2: GPoint,
    /** ∠arm1-vertex-arm2 的度数（0~180，和 [com.functiondrawer.watch.engine.angleAt] 一致）。 */
    val degrees: Double
)

/**
 * 两条都是**无限直线**时，一条边默认伸出去多长（世界单位）。
 *
 * 3.0 世界单位是照着默认视野取的：初始 x ∈ [-10, 10] 占满 466px，
 * 约 23.3px/单位，所以 3 单位 ≈ 70px；扇形半径再取它的 35%（[ANGLE_ARM_RATIO]）
 * ≈ 24.5px —— 在 233dp 的表上看得清，又不会盖住旁边的曲线。
 * 线段/射线**不用这个值**：它们自己就有真实长度，用它们才符合"这个角有多大"的直觉。
 */
const val ANGLE_DEFAULT_ARM = 3.0

/**
 * 扇形半径 = 两条边里**较短者**的百分之多少。
 *
 * 取 0.35 是"看得清 + 不盖图"之间的取舍：角的两条边通常有几格长，
 * 35% 大约是一格半的半径，弧线离顶点不远、一眼能看出是哪个角；
 * 再大就会把边上的点/曲线压住，再小弧线就缩成一个点看不出来了。
 *
 * 类型是 `Float`（不是 `Double`）：它唯一的用处是渲染时乘屏幕像素长度
 * （`GraphCanvas.drawAngleFigure`），那边整条链都是 Float。
 */
const val ANGLE_ARM_RATIO = 0.35f

/**
 * 从两条线段/直线量出一个角；构不成角时返回 null（平行、重合、共线、退化）。
 *
 * ## 两条边的"方向"怎么定（这是这个函数唯一需要解释的地方）
 *
 * 两条线交出来的是**四条射线**、四个角。用户点的是"两条线段"，所以按线段自己的
 * 走向去读最符合直觉：**从顶点朝着这条线段伸出去的那一头**。
 *
 * | 被点中的对象 | 边的方向 |
 * |---|---|
 * | 线段（[com.functiondrawer.watch.engine.GLineKind.SEGMENT]） | 顶点落在某个端点上 → 朝**另一个端点**（这是最常见的"两条线段共用一个顶点"）；顶点不在端点上（两条线段交叉/延长后相交）→ 朝**线段中点**，即"这条线段整体在顶点哪一侧" |
 * | 射线（[com.functiondrawer.watch.engine.GLineKind.RAY]） | 就是它自己的方向（起点 → 经过点）；顶点正好在起点上时反过来取，保证朝着射线伸出去的那一侧 |
 * | 直线（[com.functiondrawer.watch.engine.GLineKind.LINE]） | 无限长、没有"哪一头"可言：第一条边用存储方向，第二条边取"和第一条边成**锐角**"的那个方向（数学上"两条直线的夹角"就是这个约定） |
 *
 * 两条边共线（方向相同或相反）时返回 null —— 那是 0° 或 180°，画出来是一条线，
 * 用户量不出任何东西，界面会明确告诉他"这条线构不成角"。
 *
 * [defaultReach] 只对无限直线/极短线段生效（见 [ANGLE_DEFAULT_ARM]）。
 */
fun angleFromLines(
    l1: GLine,
    l2: GLine,
    defaultReach: Double = ANGLE_DEFAULT_ARM
): AngleFromLines? {
    if (l1.isDegenerate || l2.isDegenerate) return null
    val v = intersect(l1, l2) ?: return null
    if (!v.isFinite) return null
    val d1 = armDirection(l1, v, null) ?: return null
    val d2 = armDirection(l2, v, d1) ?: return null
    // 共线（同向或反向）：cos 的绝对值是 1，角度退化成 0°/180°
    val cos = (d1.x * d2.x + d1.y * d2.y).coerceIn(-1.0, 1.0)
    if (kotlin.math.abs(kotlin.math.abs(cos) - 1.0) < 1e-9) return null

    val r1 = armReach(l1, v, defaultReach)
    val r2 = armReach(l2, v, defaultReach)
    val a1 = GPoint(v.x + d1.x * r1, v.y + d1.y * r1)
    val a2 = GPoint(v.x + d2.x * r2, v.y + d2.y * r2)
    val deg = angleAt(v, a1, a2)
    if (!deg.isFinite() || deg <= 0.0) return null
    return AngleFromLines(v, a1, a2, deg)
}

/** 顶点是不是（几乎）就落在这个点上。世界坐标下的固定容差：吸附过的点是**精确**相等的。 */
private fun sameVertex(a: GPoint, b: GPoint): Boolean =
    kotlin.math.abs(a.x - b.x) < 1e-9 && kotlin.math.abs(a.y - b.y) < 1e-9

/** 一条边从顶点朝哪边伸出去（单位向量）。[other] 是第一条边的方向，只有无限直线会用到。 */
private fun armDirection(l: GLine, v: GPoint, other: GPoint?): GPoint? {
    val len = l.length
    if (!len.isFinite() || len < 1e-12) return null
    val ux = l.dx / len
    val uy = l.dy / len
    return when (l.kind) {
        GLineKind.SEGMENT -> {
            val far = when {
                sameVertex(v, l.p) -> l.q
                sameVertex(v, l.q) -> l.p
                else -> GPoint((l.p.x + l.q.x) / 2.0, (l.p.y + l.q.y) / 2.0)
            }
            val fx = far.x - v.x
            val fy = far.y - v.y
            val fl = kotlin.math.hypot(fx, fy)
            // 顶点正好是线段中点（两条线段在中点交叉）：中点方向退化，退回存储方向
            if (!fl.isFinite() || fl < 1e-12) GPoint(ux, uy) else GPoint(fx / fl, fy / fl)
        }

        GLineKind.RAY -> if (sameVertex(v, l.q)) GPoint(-ux, -uy) else GPoint(ux, uy)

        GLineKind.LINE -> when {
            other == null -> GPoint(ux, uy)
            ux * other.x + uy * other.y >= 0.0 -> GPoint(ux, uy)
            else -> GPoint(-ux, -uy)
        }
    }
}

/** 这条边从顶点实际伸出去多远（决定扇形半径，见 [ANGLE_ARM_RATIO]）。 */
private fun armReach(l: GLine, v: GPoint, defaultReach: Double): Double {
    if (l.kind == GLineKind.LINE) return defaultReach
    val d = maxOf(distance(v, l.p), distance(v, l.q))
    return if (d.isFinite() && d > 1e-9) d else defaultReach
}

// -----------------------------------------------------------------------------------
// 关于"画布上点对象的命中判定"，这里**没有**纯几何工具了（本轮删掉过两个）
//
// 上一版曾经在这里放过 `pointInPolygon`（面积）与 `angleWedgeContains`（角度），
// 供"点在度量图形上就选中它"用。用户后来更正了口径：
//
//   "如果有多个对象重合，优先显示点，再是线段，再是函数曲线，
//    角度、面积和距离在图像上面能显示，不用管。"
//
// 也就是说**角度/面积/距离根本不参与命中判定**（点它们的图形会穿透到下面那三类去，
// 见 `GraphState.onTapCanvas`）。那两个函数因此没有任何调用点，连同它们的离线测试
// 一起删掉了 —— 留着就是死代码，而这个文件的原则是"每条规则都要有调用方和测试钉着"。
//
// 如果将来又要"点度量图形选中它"，需要恢复的就是这两件事：
//   1. 多边形内外判定（射线法 / 奇偶规则，自交与坏值不崩）；
//   2. 角度张开区域的判定（夹角之和 ≤ 总角 + eps，不用管绕序）。
// -----------------------------------------------------------------------------------
