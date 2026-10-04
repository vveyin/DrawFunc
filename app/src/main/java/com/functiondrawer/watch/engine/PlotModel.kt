package com.functiondrawer.watch.engine

import kotlin.math.PI

/**
 * 线型：实线 + 三档**密度不同的虚线**。
 *
 * 用户要求属性编辑界面里"点击切换实线和密度不一样的虚线（以图案表示，不要以文字表示）"，
 * 所以这里只给参数，界面上画的是用这些参数做出来的一小段示意线。
 *
 * 单位是**屏幕像素**（在像素空间里描边，所以和缩放无关地看起来一致）。
 * 之所以要三档而不是"虚线一种"：光是"实/虚"两态在 1.5 寸屏上很难一眼分辨，
 * 疏密差异才看得出来。
 */
enum class LineStyle(
    /** 虚线里"实"的那一段长度（像素）；0 = 实线。 */
    val dashOn: Float,
    /** 虚线里"空"的那一段长度（像素）。 */
    val dashOff: Float
) {
    SOLID(0f, 0f),
    DASH_FINE(5f, 4f),
    DASH_MEDIUM(11f, 7f),
    DASH_COARSE(20f, 12f);

    val isDashed: Boolean get() = dashOn > 0f

    fun next(): LineStyle = entries[(ordinal + 1) % entries.size]
}

/** 四种绘图模式。 */
enum class PlotMode {
    /** y = f(x)，逐像素列采样 */
    EXPLICIT,
    /** r = f(θ)，极坐标 */
    POLAR,
    /** (x(t), y(t))，参数方程（界面上叫「描点」，见 [label]） */
    PARAMETRIC,
    /** F(x, y) = 0，隐函数，marching squares */
    IMPLICIT;

    /**
     * 模式 chip 上显示的短标签，函数编辑页顶部直接读它。
     *
     * PARAMETRIC 显示「描点」而不是「参数」是**用户指定的叫法**：
     * 用户原话"函数编辑界面的参数改名成描点"，指的是 `(x(t), y(t))`
     * 这种逐点描出来的曲线（另外三种模式的采样变量里也有 t/θ，
     * 叫「参数」容易和"可绑定参数 a b c d"混起来）。
     *
     * 文案只在这里定义一份 —— UI 用 `fn.mode.label`，不要在界面里另起一份字符串，
     * 否则以后改叫法就会出现两处不一致。
     */
    val label: String
        get() = when (this) {
            EXPLICIT -> "y=f(x)"
            POLAR -> "r(θ)"
            PARAMETRIC -> "描点"
            IMPLICIT -> "F(x,y)"
        }

    /** 该模式下第一个输入框的语义提示。 */
    val primaryHint: String
        get() = when (this) {
            EXPLICIT -> "y = x^2"
            POLAR -> "r = 2cos(3θ)"
            PARAMETRIC -> "x = cos t"
            IMPLICIT -> "x^2+y^2 = 1"
        }

    val secondaryHint: String
        get() = "y = sin t"

    /**
     * 该模式允许出现的变量。报错信息会用它提示用户。
     *
     * 这里只列**采样输入**：列表顺序就是 `Expression.eval(...)` 的实参顺序，
     * 采样器按它的长度分配参数数组（极坐标下 θ 和 t 是同一个位置的两个写法）。
     * 可绑定参数 `a b c d` 走另一条通道（见 [ParameterSet]），四种模式下都能用，
     * 刻意**不**列在这里 —— 它们在编译期就折成常量了，不是采样输入。
     */
    val variables: List<String>
        get() = when (this) {
            EXPLICIT -> listOf("x")
            POLAR -> listOf("θ", "t")
            PARAMETRIC -> listOf("t")
            IMPLICIT -> listOf("x", "y")
        }

    /** 参数 t / 极角 θ 的默认取值范围。 */
    val defaultRange: ClosedFloatingPointRange<Double>
        get() = when (this) {
            POLAR -> 0.0..(2.0 * PI)
            PARAMETRIC -> 0.0..(2.0 * PI)
            else -> 0.0..(2.0 * PI)
        }

    val usesRange: Boolean
        get() = this == POLAR || this == PARAMETRIC
}

/**
 * 一条待绘制的函数。
 *
 * @param src1 EXPLICIT/POLAR/IMPLICIT 的主表达式；PARAMETRIC 的 x(t)
 * @param src2 仅 PARAMETRIC 使用：y(t)
 */
data class PlotFunction(
    val id: Long,
    val mode: PlotMode,
    val src1: String,
    val src2: String = "",
    val tMin: Double = 0.0,
    val tMax: Double = 2.0 * PI,
    val colorIndex: Int = 0,
    val visible: Boolean = true,
    /**
     * 这一条曲线**自己**的角度单位：true = 三角函数按「度」理解。
     *
     * null 表示"跟随 [Plotter.plot] 传进来的全局默认值"。
     *
     * 之所以做成每条曲线一份，是用户的明确要求：
     * 同一张图里一条走弧度、另一条走度制是合理需求，
     * 所以切换开关放在函数编辑界面，而不是全局设置里。
     */
    val degrees: Boolean? = null,
    /**
     * 用户给这条函数/方程/点起的名字 —— 函数列表里显示的就是它。
     *
     * 新建时由状态层按字母表自动分配（函数 `a(x)`、`b(x)`…，方程与点 `A`、`B`…），
     * 之后可以在属性编辑界面里改。空串表示"还没命名"，UI 退回显示表达式摘要。
     */
    val name: String = "",
    /** 线型（实线 / 三档虚线）。只影响画法，**不影响采样**。 */
    val lineStyle: LineStyle = LineStyle.SOLID,
    /**
     * 线径倍数：1.0 = 默认粗细。
     *
     * 属性编辑界面里"线径"那个滑动条调的就是它。做成**倍数**而不是绝对像素，
     * 是为了让屏幕尺寸/缩放变化时相对粗细保持一致
     * （实际描边宽度 = 基础线宽 × 它）。
     */
    val widthScale: Float = 1f
) {
    /** 列表里显示用的一行摘要。 */
    fun summary(): String = when (mode) {
        PlotMode.EXPLICIT -> "y = $src1"
        PlotMode.POLAR -> "r = $src1"
        PlotMode.PARAMETRIC -> "($src1, $src2)"
        PlotMode.IMPLICIT -> src1
    }

    /** 列表里显示的主标题：起过名字就用名字，否则退回摘要。 */
    fun title(): String = name.ifBlank { summary() }
}

/**
 * 采样精度。
 *
 * 手势进行中用 FAST 保证跟手，手指抬起后回到 NORMAL/HIGH 出图质量。
 */
enum class Quality(
    /** 隐函数网格单元边长（像素） */
    val cellPx: Double,
    /** 隐函数自适应细分层数 */
    val implicitDepth: Int,
    /** 显函数沿 x 轴的采样步长（像素） */
    val explicitStepPx: Double,
    /** 参数/极坐标曲线的平坦度阈值（像素） */
    val flatnessEps: Double
) {
    FAST(9.0, 0, 2.0, 0.7),
    NORMAL(5.0, 1, 1.0, 0.25),
    HIGH(3.0, 2, 0.5, 0.12)
}

/**
 * 采样结果。
 *
 * [points] 是**屏幕像素**坐标、按 x0,y0,x1,y1... 交错存放的折线，
 * 直接就能喂给 Compose 的 Path —— 让 UI 层保持"傻"一点，
 * 也让引擎的单元测试可以直接对像素坐标断言。
 */
class Curve(
    val functionId: Long,
    val colorIndex: Int,
    val points: FloatArray
) {
    val pointCount: Int get() = points.size / 2
}

class PlotResult(
    val curves: List<Curve>,
    /** 函数 id → 出错信息（解析失败或参数不合法），UI 用来在编辑器上飘红 */
    val errors: Map<Long, String>,
    /**
     * 这批曲线是按哪个视口算出来的屏幕坐标。
     *
     * 必须记住它，否则手势里会出现「函数图像跟不上坐标系」：
     * 网格是按**当前**视口实时画的，而曲线是**旧**视口的像素坐标，
     * 于是拖动时曲线看起来在滞后。
     * 绘图时用「这个视口 → 当前视口」的仿射增量把曲线变换过去就行，
     * 完全不需要重新采样（O(1)，每帧零成本）。
     */
    val viewport: Viewport? = null
) {
    val totalPoints: Int get() = curves.sumOf { it.pointCount }

    companion object {
        val EMPTY = PlotResult(emptyList(), emptyMap(), null)
    }
}
