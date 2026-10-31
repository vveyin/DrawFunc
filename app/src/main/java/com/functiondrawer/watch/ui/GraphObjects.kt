package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.SnapKind
import kotlin.math.abs

/**
 * 图上"点出来的东西"（用户需求 #9）。
 *
 * 和 [com.functiondrawer.watch.engine.PlotFunction] 的区别：
 *  * `PlotFunction` 是**靠表达式采样出来的曲线/隐函数**；
 *  * [GraphObject] 是**用户用手在图上点出来的几何对象** ——
 *    描点、度量（角度/距离/面积）、变换与作图的结果（对称点、中垂线、切线…）。
 *
 * 它们都要出现在函数列表里、都要有名字和颜色，所以这里统一成一个 sealed 家族，
 * 每种对象只带自己需要的数据。
 *
 * ## 名字规则（用户原话："除了点之外的所有对象的名字应该都是小写字母。"）
 *
 * 这一版把规则**简化成一条**（原来按"几何 / 标量"分家：点、直线大写，度量小写）：
 *
 * | 对象 | 字母 | 为什么 |
 * |---|---|---|
 * | [GraphPointObject]（点） | **大写** `A`、`B`、`AA`… | `x(A)` / `y(A)` 这种"取坐标"写法靠 [isPointName] **只认大写字母序列**来识别点名；点改名时同步改引用也走同一把尺子 |
 * | [GraphLineObject]（直线/线段/射线） | **小写** `a`、`b`… | 用户这一轮明确要求"除了点之外的所有对象都是小写"，直线也在里面 |
 * | [GraphMeasureObject]（角度/距离/面积） | **小写** `a`、`b`… | 同上，而且它本来就能直接写进表达式当数值用（`a*x`） |
 *
 * 也就是说：**判据从"这个对象是几何还是标量"换成了"这个对象是不是点"**。
 * 判据本体**只有一处** —— [objectNameCase]（按 [ObjectKind] 分派），
 * 发名字（`GraphState.nextObjectName(kind)`）和校验改名（[objectNameError]）
 * 都走它；以前"分配器发大写、校验说只能大写"那种两份实现是同一个规则写两遍，
 * 改一处漏一处就会出现"新建的对象叫 C、改名却被告知只能小写"。
 *
 * 小写名字**能直接写进表达式当数值/标识符**，所以小写这一档另有一条限制：
 * [RESERVED_LOWER_NAMES]（`x` `y` `t` `e` `i` `z`）不许**手打**进名字
 * —— 见那个常量的说明。点仍然是大写，`x(A)` 只是"点的引用通道"，不受这条影响。
 *
 *    ⚠️ **本轮起不再"跳过任何字母"**（原来跳 `a`~`d` / `x` / `y` / `t` / `θ` / `z` / `e` / `i`）。
 *    用户原话："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
 *    标量也可以用 a，滑动条也可以用 f。至于 e、x、y 这些可能冲突的字母，
 *    请你给函数编辑界面输入的这些字母单独分组编 id。" 于是"名字撞上引擎里的含义"
 *    不再是错误，而是变成**用户要指认一次**的状态：在那条函数上把字母绑定到
 *    具体对象（`GraphState.functionBindings` + `PointCoordinates.nameChoicesIn`），
 *    采样前先查绑定表（`PointCoordinates.resolveScalarValues`）。
 *
 *    标量名仍然"能直接写进表达式当数值用"（用户原话"就是这些标量能直接写进表达式
 *    当数值用"），比如量出一段距离叫 `a` 之后就能写 `a*x`、`x^2+a`。换算发生在采样前，
 *    路径和 `x(A)` 取坐标完全一样（见 `PointCoordinates.substitutePointCoordinates`
 *    与 `GraphState.scalarValues()` / `functionsForSampling()`）。
 *
 * ## 角度对象**只有这个角本身**（用户要求那一轮改的）
 *
 * 用户原话："角度只包含这个角，不包含两条线段。" 所以角度现在是**从两条线段量出来的**
 * （或者连点三个点、先建出两条线段再量，见 `FunctionTool.ANGLE` 与
 * `FunctionTools.angleFromLines`），它自己只存：
 *
 *  * 顶点（[GraphMeasureObject.points] 的**中间那个**）；
 *  * 两条边**各自所指方向**上的一个点（第一个和最后一个）—— 到顶点的距离
 *    就是那条边实际伸出去多长，扇形半径按"较短那条边"的比例取（见 `GraphCanvas`）。
 *
 * 那两条线段是**独立对象**：有自己的名字、在函数列表里有自己的一行，删掉/隐藏都跟角度无关。
 * 列表第二行的 `角度（C，D）` 只是"说明它是拿哪两条边量出来的"，不是从属关系。
 *
 * ## 距离 / 面积同一条规矩（用户追加："距离，面积这些都是和角度一样的调整"）
 *
 *  * **距离**的图形 = 两个端点之间的测量线（+ 两端刻度帽），**不含那两个点**；
 *  * **面积**的图形 = 顶点连线围成的多边形（轮廓 + 填充），**不含那些点**。
 *
 * 也就是说整个 `GraphObject` 家族里，**每个对象只画自己**：点画点、线画线、
 * 度量画度量图形 —— 用户要的"度量不把辅助对象据为己有"就是这么落地的。
 * 删除一个点时也不会连带删掉引用它的度量（度量存的是**坐标快照**，不是引用）。
 *
 * ## 标量 = "不能调的变量"（用户定性），所以它是**对象行**、不是滑动条行
 *
 * 用户原话："它和滑动条本质上都是变量，但是滑动条可以调，这个不能调。"
 *
 * | | 谁 | 在哪个列表 | 列表里长什么样 | 能不能改值 |
 * |---|---|---|---|---|
 * | 可调的变量 | 可绑定参数 `a`~`d`（`GraphState.sliders`） | 滑动条列表 | **滑动条行**（`SliderRow`，能拖） | 能 |
 * | 不能调的变量 | 标量（[GraphMeasureObject]，本文件的 `objects`） | 几何对象列表 | **对象行**（`GraphObjectRow`：名字 + 当前数值，点色点显隐、左滑删除） | **不能** |
 *
 * 两件事在数据结构上就是分开的（度量对象只进 `objects`，永远不进 `sliders`），
 * 在界面上也是两个不同的 composable —— 所以用户一眼能区分"这个能拖、这个只能看"。
 *
 * 标量名**不再靠"跳过 a~d"来保证不互相长出滑动条**（本轮改的，见上面那段名字规则）：
 * 命名可以重名，冲突由用户在编辑器里指认。所以"这条表达式里的 `a` 到底是
 * 滑动条还是标量"不再是一个能在取名时解决的问题，而是一个**要在那条函数上说清**的
 * 状态（`GraphState.functionBindings`）。
 *
 * 所有坐标都是**世界坐标**：渲染时由 [com.functiondrawer.watch.engine.Viewport]
 * 换算成屏幕像素（和曲线一样，缩放/平移时跟着动）。
 */
sealed interface GraphObject {
    val id: Long
    val name: String
    val colorIndex: Int
    val visible: Boolean

    /**
     * 是否在图上显示**数值文字**（#9：角度/距离/面积"可以选择数值是否在函数图像界面显示"）。
     * 几何对象本身（点、线）没有数值，恒为 false。
     *
     * 对**度量**来说这是**第二套可见性**：色点（[visible]）管"图形画不画"，
     * 这个开关管"数值文字画不画"，两者互不干扰 —— 关掉色点之后数值仍然画
     * （用户要的就是"左边管图形、右边管数值"两件事各管一件）。
     * 在对象行上，色点 = [visible]（点一下切，带脉冲反馈），右端的实心/空心 `123` = 这个字段。
     */
    val showValue: Boolean get() = false
}

/**
 * 一个点。
 *
 * [kind] 记录它是怎么来的（自由描点 / 吸附到了顶点、交点、零点、极值点），
 * 吸附来源会影响画法：吸附来的点画成**空心**，让用户一眼看出"这个点是曲线上的特征点"，
 * 而不是随手点的。
 */
data class GraphPointObject(
    override val id: Long,
    override val name: String,
    val point: GPoint,
    val kind: SnapKind = SnapKind.POINT,
    override val colorIndex: Int = 0,
    override val visible: Boolean = true,
    /** "我是按什么算出来的"（中点 / 对称点）；自由描的点是 null。 */
    val recipe: ObjectRecipe? = null,
    /** "我吸附在哪条曲线/直线上"（跟着曲线走）；自由点是 null。 */
    val attachment: PointAttachment? = null
) : GraphObject {

    /**
     * 这个点是不是**由别的对象算出来的**（中点 / 对称点）。
     *
     * 用户原话："由点派生出的点要和描点时描的极值点一样是空心，
     * 并且空心的点要做成不可拖动。"
     *
     * 判据只看 [recipe]：它只在两种点上有值（见 [ObjectRecipe.MidpointOf] /
     * [ObjectRecipe.MirrorPointOf]），自由描的点、吸附到曲线上的点都是 null。
     * 存盘不存 recipe，所以读回之后这个点就是普通自由点（实心、可拖）——
     * 这也对：那时它已经不再是"根据别的点点算出来的"了。
     */
    val isDerived: Boolean get() = recipe != null

    /**
     * 画成空心、并且**不许拖**（用户要求"空心的点要做成不可拖动"）。
     *
     * 两档来源：
     *   * 曲线上的**特征点**（交点 / 零点 / 极值点 / 顶点）—— 它们的坐标是曲线算出来的，
     *     拖走就不再是特征点，所以钉住；
     *   * **派生点**（[isDerived]）—— 坐标由别的点算出来，拖走没有意义。
     *
     * [SnapKind.POINT]（自由描点）和 [SnapKind.OTHER]（吸在曲线本身上的描点）是实心、
     * 可拖的 —— 用户点名要的就是这两种能拖（"描出来的实心点可以拖动"）。
     */
    val isHollow: Boolean
        get() = isDerived || (kind != SnapKind.POINT && kind != SnapKind.OTHER)

    /** 能不能被拖动（= 实心点）。 */
    val isDraggable: Boolean get() = !isHollow
}

/**
 * 一条直线 / 线段 / 射线（含作图工具构造出来的那些）。
 *
 * [role] 只是给人看的说明（"垂线"、"中垂线"、"角平分线"、"切线"…），
 * 真正决定怎么画的是 [line] 里的 [GLineKind]。
 */
data class GraphLineObject(
    override val id: Long,
    override val name: String,
    val line: GLine,
    val role: String = "",
    override val colorIndex: Int = 0,
    override val visible: Boolean = true,
    /** "我是怎么由哪些点/线算出来的"；对称曲线那种折线碎片是 null。 */
    val recipe: ObjectRecipe? = null
) : GraphObject

// ===================================================================================
// 直线的方程（用户本轮要求："在使用任何方式生成直线时，请在函数列表里的直线一栏
// 写出直线的 y=kx+b 方程。"）
//
// 这一整块是**纯字符串/算术**（只 import engine 的 GLine），所以放在这个文件里
// 和改名规则一起离线单测钉住（`tools\run-tool-tests.ps1` → `PointCoordinatesTest`）。
//
// ## 为什么是"从两个锚点现算"，而不是生成时存一份
//
// 直线对象只有 [GLine.p]/[GLine.q] 两个锚点，方程是它们的**推论**。存一份的话，
// 变换（轴对称 / 中心对称）把锚点搬走之后方程就过期了 —— 那正是"列表写着
// y = 2x + 1、图上却明显不是这条线"这类最难查的错。现算的成本是每帧几个浮点
// 运算（列表行本来就要重组），换来的是**方程永远等于图上那条线**。
//
// ## 各种退化情形的写法（都是用户点名要求的）
//
// | 情形 | 输出 | 为什么不能写通用式 |
// |---|---|---|
// | 竖直线（`dx == 0`） | `x = 3` | 斜率不存在，`y = kx + b` 根本写不出来 |
// | 水平线（`k` 四舍五入后是 0） | `y = 5` | 写 `y = 0x + 5` 是废话，还占列表宽度 |
// | `k = ±1` | `y = x + 3` / `y = -x + 3` | 写 `1x` 不是数学写法 |
// | `b = 0` | `y = 2x` | 尾巴上的 `+ 0` 没有信息量 |
// | `b < 0` | `y = 2x - 3` | **绝不能**出现 `+ -3`（读起来像两个符号打架） |
//
// ## 线段 / 射线：同一类对象，多一个**定义域**
//
// 用户说的"直线那一栏"指的是列表里这类对象（`GLineKind` 的三种都算）。线段/射线
// 光有方程是不完整的（`y = 2x + 1` 画出来是无限长的一条，而它其实只有一段），
// 所以后面加一个定义域括号 —— 用户给的样例正是这个形状（`y = kx + b (x ≥ x0)`）：
//
//   * 线段 `y = 2x + 1 (-1 ≤ x ≤ 3)`；
//   * 射线 `y = 2x + 1 (x ≥ -1)`（从 p 出发朝 q 那一侧延伸，所以上下界由 `dx` 的正负定）；
//   * 竖直线段/射线同理，只是换成 `y` 的范围（`x = 2 (-1 ≤ y ≤ 3)`）。
//
// 定义域用的是 ASCII 括号（数学表达式里 ASCII 更清楚），界符用 `≤` / `≥`
// （Roboto 自带这两个字形，和其它屏上的 `°`、`∞`、`·` 一样）。
// ===================================================================================

/**
 * 一条直线/线段/射线在列表里显示的**方程**：`y = 2x + 1`、`x = 3`、`y = 5`…
 *
 * @return 退化直线（两个锚点重合 / 坐标非有限）返回 null —— 那种线**画不出来**，
 *   也不该在列表里写一个编出来的方程（调用方据此渲染成"没有方程"）。
 */
fun lineEquationText(line: GLine): String? {
    if (line.isDegenerate) return null
    // 竖直（含"几乎竖直"）：斜率不存在，**必须**是 `x = c`，写成 `y = kx + b` 是不可能的。
    if (isVerticalForDisplay(line)) return "x = " + eqNumber(roundForEquation(line.p.x))

    // k 先按"要显示的位数"取整，之后的判断全用**取整后**的值 ——
    // 否则会出现"显示的 k 是 0、却按真实斜率去写 y = 0.0001x"这种自相矛盾的输出。
    val k = roundForEquation(line.dy / line.dx)
    // k 是 0（`-0.0` 也算）→ 水平线，写成 `y = b`，不写 `0x`
    if (k == 0.0) return "y = " + eqNumber(roundForEquation(line.p.y))

    // b 用**显示用的 k** 反算：把 x = p.x 代回列表里那个方程，得到的正好是 p.y。
    // 用真实斜率算 b 的话，k 被显示成 2（真值 1.99996）时 b 会和显示的 k 对不上，
    // 用户拿计算器一验就会发现"列表里的方程不过这个点"。
    val b = roundForEquation(line.p.y - k * line.p.x)

    val text = StringBuilder("y = ")
    when (k) {
        1.0 -> text.append('x')      // `1x` 不是数学写法
        -1.0 -> text.append("-x")    // `-1x` 同理
        else -> text.append(eqNumber(k)).append('x')
    }
    // 尾巴：b 为 0 就什么都不写；为负写成 `- |b|`（绝不出现 `+ -3`）
    if (b > 0.0) {
        text.append(" + ").append(eqNumber(b))
    } else if (b < 0.0) {
        text.append(" - ").append(eqNumber(-b))
    }
    return text.toString()
}

/**
 * 斜率大到什么程度就**按竖直线写**（`x = c`）。
 *
 * ## 为什么要有这一条（用户列的特殊情形里只有"竖直线"，但真机会撞上"几乎竖直"）
 *
 * 切线的斜率是**数值**求出来的（`Geometry.tangentLine` 在切点附近取差商），所以在
 * `tan(x)` 的渐近线附近切一下，得到的是一条斜率上万、但**并不精确竖直**的线。
 * 那种线的 `k` 写出来是 `1.0e+04` 这种科学计数法（`formatNumber` 在 |v| ≥ 1e5 时就这么写），
 * 在一行小字里既读不懂、又和"这是一条竖直的线"的观感完全对不上。
 *
 * ## 为什么取 1e5 是**安全**的（不是随手拍的数）
 *
 * 斜率 `k` 的直线横跨整块屏幕高度时，横向偏移 = `屏幕高度(px) / k` 像素 ——
 * 这里 `upp` 被约掉了，所以**与缩放无关**：`466 / 1e5 = 0.005px`（圆表）、
 * `2400 / 1e5 = 0.024px`（手机）。也就是说 |k| ≥ 1e5 的线在**任何**屏幕、
 * **任何**缩放档（`Viewport` 允许 upp 从 1e-9 到 1e9）下，看上去都是一条竖直线 ——
 * 写 `x = c` 与写 `y = kx + b` 在屏幕上**一个像素都不差**，而前者能读懂。
 *
 * 阈值以下是老老实实的 `y = kx + b`（`k = 50000` 也照写，那时横向偏移 0.009px…
 * 但那已经不是"读不读得懂"的问题，而是"要不要替用户改一个量"的问题 ——
 * **能读的就不改**，只在这条线的方程不论怎么写都只能用科学计数法时才换成竖直线）。
 */
private const val NEAR_VERTICAL_SLOPE = 1e5

/**
 * 这条线要不要**按竖直线**来写（斜率不存在，或者大到任何缩放下都看不出差别）。
 *
 * 方程与定义域两处必须用**同一个**判据：分开判会出现
 * `x = 3 (-1 ≤ x ≤ 3)` 这种"方程说 x 是常数、定义域却给了 x 的区间"的自相矛盾。
 */
private fun isVerticalForDisplay(line: GLine): Boolean {
    if (line.dx == 0.0) return true
    val k = line.dy / line.dx
    return !k.isFinite() || kotlin.math.abs(k) >= NEAR_VERTICAL_SLOPE
}

/**
 * 线段/射线在方程后面那个**定义域**（直线没有，返回 null）。
 *
 * 判据全部来自 [GLine.kind] 的 `t` 区间（见 `GLine.tRange`）和两个锚点：
 *   * 线段 `t ∈ [0,1]` → p、q 之间，所以取两个端点的**较小值/较大值**；
 *   * 射线 `t ∈ [0,∞)` → 从 p 出发只往 q 那一侧长，所以**下界（或上界）就是 p 自己**，
 *     方向由 `dx`（竖直线则由 `dy`）的正负决定；
 *   * 直线 `t ∈ (-∞,∞)` → 没有定义域。
 *
 * 竖直的图元改用 `y` 的范围：`x = 2 (-1 ≤ y ≤ 3)` —— 那时候 x 是常数，
 * 拿它当区间变量会把定义域说成全空集。
 */
fun lineDomainText(line: GLine): String? {
    if (line.isDegenerate) return null
    // 竖直的判据必须和 [lineEquationText] **共用同一个**（`isVerticalForDisplay`）：
    // 否则会出现"方程写成 x = 3、定义域却写 -1 ≤ x ≤ 3"这种自相矛盾的第二行。
    val vertical = isVerticalForDisplay(line)
    return when (line.kind) {
        GLineKind.LINE -> null

        GLineKind.SEGMENT -> if (vertical) {
            val lo = minOf(line.p.y, line.q.y)
            val hi = maxOf(line.p.y, line.q.y)
            "${eqNumber(roundForEquation(lo))} ≤ y ≤ ${eqNumber(roundForEquation(hi))}"
        } else {
            val lo = minOf(line.p.x, line.q.x)
            val hi = maxOf(line.p.x, line.q.x)
            "${eqNumber(roundForEquation(lo))} ≤ x ≤ ${eqNumber(roundForEquation(hi))}"
        }

        GLineKind.RAY -> if (vertical) {
            if (line.dy > 0) "y ≥ ${eqNumber(roundForEquation(line.p.y))}"
            else "y ≤ ${eqNumber(roundForEquation(line.p.y))}"
        } else {
            if (line.dx > 0) "x ≥ ${eqNumber(roundForEquation(line.p.x))}"
            else "x ≤ ${eqNumber(roundForEquation(line.p.x))}"
        }
    }
}

/**
 * 列表第二行真正要显示的那一串：**方程 +（线段/射线）定义域**。
 *
 * 合成一处是为了让列表行（`FunctionsScreen.objectSummary`）和属性页/画布属性卡
 * （`FunctionsScreen.propertyDataText`）说的**是同一句话** —— 那两处各自拼一遍
 * 迟早会出现"列表里有定义域、属性页里没有"这种对不上的情况。
 *
 * @return 退化直线返回 null（调用方不显示这一栏）。
 */
fun lineEquationSummary(line: GLine): String? {
    val eq = lineEquationText(line) ?: return null
    val domain = lineDomainText(line)
    return if (domain == null) eq else "$eq ($domain)"
}

/**
 * 方程里显示一个数之前先取整到**千分位**（和 `Common.formatNumber` 同一位数）。
 *
 * 为什么不 import `formatNumber`：那个函数在 `Common.kt` 里，而那个文件带
 * Compose 依赖 —— 一旦 import，这个文件就不再是"纯 Kotlin"，`run-tool-tests.ps1`
 * 那套离线单测就编不过（见文件头）。所以这里**逐字复用它的规则**（同一个
 * 千分位取整、同一个科学计数法阈值），两条路径的输出因此在任何输入下都一致。
 */
private fun roundForEquation(v: Double): Double =
    if (!v.isFinite()) v else Math.round(v * 1000.0) / 1000.0

/**
 * 方程里的数字文本。规则与 `Common.formatNumber` **逐字相同**（见 [roundForEquation]）：
 * 取整到千分位、整数不带小数点、极大/极小走科学计数法、0 就是 `0`。
 *
 * 单独写一份的代价（已知）：两边将来若各自改口径会不一致。对冲办法是
 * `PointCoordinatesTest` 里把各种 `k`/`b` 的输出字符串**钉死**，
 * 改这里就会红 —— 而不是等用户在图上看出来。
 */
private fun eqNumber(v: Double): String = when {
    v.isNaN() -> "—"
    !v.isFinite() -> if (v > 0) "∞" else "−∞"
    v == 0.0 -> "0"
    kotlin.math.abs(v) >= 1e5 || kotlin.math.abs(v) < 1e-4 ->
        String.format(java.util.Locale.US, "%.1e", v)
    else -> {
        val rounded = roundForEquation(v)
        if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }
}

/** 度量的种类。**图形**那一栏是本轮追加要求定下来的（三种度量各一种画法）。 */
enum class MeasureKind(val label: String) {
    /**
     * 角度。**由两条线段/直线量出来**（用户要求："先点击两条线段或者调用线段工具
     * 点击三个点创建两条线段……再创建角度，角度只包含这个角，不包含两条线段"）。
     *
     * 所以它的 [GraphMeasureObject.points] 是 `[边1上的点, 顶点, 边2上的点]` ——
     * 顶点永远在**中间**（原来"三点定角、第二个点是顶点"的排布没变，
     * 只是现在这两个"边上的点"是由那两条线段算出来的，见 `FunctionTools.angleFromLines`）。
     *
     * **图形**（色点开关）：顶点 + 两个方向之间的**弧** + 半透明扇形。
     */
    ANGLE("角度"),

    /**
     * 两点定距离。
     *
     * **图形**（色点开关）：两个端点之间的一条**测量线** + 两端各一小段垂直的
     * **刻度帽**（工程图上"尺寸线"的画法）。**不画端点上的点** ——
     * 那两个点是独立对象，由它们自己那一行管（用户要求"距离不把两个端点'收进来'"）。
     */
    DISTANCE("距离"),

    /**
     * 多个点围成的多边形面积；最后一个点 = 起始点（用户说"最后点击开始的第一个点"）。
     *
     * **图形**（色点开关）：顶点依次连线围成的**多边形轮廓 + 半透明填充**。
     * **不画顶点上的点**（用户要求"面积不把那些点据为己有"）；自交多边形按
     * `Path` 的默认非零环绕直接填，不做任何布尔运算（用户明确允许"最简单的方式"）。
     */
    AREA("面积")
}

/**
 * 一个度量结果（#9 第二栏）。
 *
 * [points] 是参与度量的点（角度 3 个、距离 2 个、面积 N 个），
 * [value] 是算好的数值（角度是**度**、距离和面积是世界单位）。
 *
 * 角度那 3 个点的排布是 `[边1, 顶点, 边2]`（顶点在中间，和"三点定角"时代一样），
 * 但**它们是从两条线段量出来的**：顶点 = 两条线段的交点、另外两个点 = 两条边
 * 各自所指方向上的一个点。角度对象本身**不含**那两条线段（用户原话），
 * 它们有自己的名字、在列表里有自己的一行。
 *
 * [name] 是**小写字母**（`a`、`b`、`c`…，见文件头那段名字规则）——
 * 用户原话"角度、距离、面积都是标量，应该用小写字母作为对象名称"。
 * 它由 `GraphState.nextMeasureName()` 分配、**存在对象里**（不是现算的），
 * 因为"哪些名字已经被占用"要看函数名/已有对象名。
 *
 * 这个名字还能**直接写进别的表达式当数值**（`a*x`、`x^2+a`）：采样前由
 * `PointCoordinates.substitutePointCoordinates` 把 `a` 折成 [value] 的字面量
 * （表来自 `GraphState.scalarValues()`）。名字撞上引擎里的含义（比如分到 `x`）
 * 时，由用户在**那条函数**上指认它指谁（`GraphState.functionBindings`），
 * 采样前先查绑定表 —— 见 `PointCoordinates.resolveScalarValues`。
 *
 * ⚠️ 它是"**不能调的**变量"：值由那一次测量决定，界面上**没有**任何拖动/编辑它的入口
 * （它出现在对象行里，不是滑动条行）。已知代价：表达式是**按名字**引用它的，
 * 所以这个对象被删掉之后，`a*x` 里的 `a` 会回到"不认识的名字 'a'"报错 ——
 * 和 `x(A)` 里点名被删掉的行为完全一致（报错明确，不会悄悄算错）。
 *
 * ## 两套独立的可见性（**三种度量都一样**）
 *
 * 用户原话（先对角度说、本轮追加"距离，面积这些都是和角度一样的调整"）：
 * "角度左边的显示开关控制角度图形在函数图像上的显示……右边的按钮控制角度数值在图像上的显示"。
 *
 * | 开关 | 在对象行上的位置 | 管什么 |
 * |---|---|---|
 * | [visible] | 左边的色点（点一下切换，有色点脉冲反馈） | **图形**：角度是带阴影的扇形（弧 + 半透明填充）、距离是两端带刻度帽的测量线、面积是多边形轮廓 + 半透明填充 |
 * | [showValue] | 右端的**实心/空心 `123`** 按钮 | **数值文字**：要不要把 `36.87°` / `5` / `12.5²` 画在图上 |
 *
 * **两者互不干扰**：关掉色点只是"图形不画了"，数值照样画（`GraphCanvas.drawMeasure`
 * 的两个参数 `figureVisible` / `drawValue` 就是这两件事）；两个都关才是彻底不显示。
 * 图形**各自只含自己那点东西**，不含参与度量的辅助对象（点、线段都是独立对象）。
 *
 * 图形在出现/消失时各播一次动画（长出来 / 收回去，见 `GraphCanvas.MeasureAnimations`）。
 *
 * 描述性的那部分（`角度（C，D）`，C/D 是两条线段的名字）搬到 [detail]：
 * 列表里的第一行放名字、第二行放数值与种类（见 `FunctionsScreen.objectSummary`）。
 */
data class GraphMeasureObject(
    override val id: Long,
    override val name: String,
    val kind: MeasureKind,
    val points: List<GPoint>,
    val value: Double,
    val pointNames: List<String> = emptyList(),
    override val colorIndex: Int = 0,
    override val visible: Boolean = true,
    override val showValue: Boolean = true,
    /** "我是量哪两个点/哪两条线算出来的"；点动了按它重算。 */
    val recipe: ObjectRecipe? = null
) : GraphObject {

    /**
     * 描述性副标题：`角度（C，D）`（C/D 是量出这个角的两条**线段**的名字）；
     * 点名缺失时退回 `角度`。
     *
     * 它**不再是对象名**（名字必须是标量字母），所以单独留一个字段给渲染侧用：
     * 用户要在列表里一眼看出"这个 `a` 是拿哪两条边量出来的角"。
     * （距离是两点名 `A，B`、面积是围成它的那几个点名 —— 同一个字段，两种内容。）
     */
    val detail: String
        get() = if (pointNames.isEmpty()) kind.label else "${kind.label}（${pointNames.joinToString("，")}）"

    /** 显示用的数值文本：角度带 `°`、面积带平方、距离不带。 */
    fun valueText(): String {
        val rounded = Math.round(value * 100.0) / 100.0
        val text = if (Math.abs(rounded - Math.floor(rounded)) < 1e-9) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
        return when (kind) {
            MeasureKind.ANGLE -> "$text°"
            MeasureKind.AREA -> "$text²"
            MeasureKind.DISTANCE -> text
        }
    }
}

/**
 * 派生对象的"施工说明"（用户要求：拖动描出来的点时，跟着这个点画出来的图形也要跟着动）。
 *
 * ## 为什么必须记 id、而不是记坐标
 *
 * 这些对象在**建出来的那一刻**只存了算好的几何（`GLine` 的两个锚点、度量的
 * `points` 坐标、算好的 `value`），那是**快照**。快照本身没错（删掉一个点时
 * 度量不该跟着消失，用户明确要过这个行为），但它有个必然的副作用：
 * 点被拖走之后，图还停在原地。
 *
 * 所以每个派生对象额外记一句"我是什么算出来的"——里面只有**输入对象的 id**
 * 和一个操作名，真正的几何每次刷新时现算（见 `GraphState` 的
 * `rebuildDerivedObjects`）。这样：
 *   * 拖动点 → 重算 → 线/度量跟着动；
 *   * 输入点被删 → 查不到 → 这一轮跳过，对象保持最后的样子（不消失、不变形）；
 *   * **1.3.9 起 recipe 会随文件和本地存档一起存**（`FunctionFileFormat` 的 `R` 段），
 *     所以重启之后照样跟着点动。1.3.8 之前的老存档没有这一段：读进来 recipe 是 null，
 *     那时只有"两点定线"能按锚点认回来（见 [inferLineFromPointsRecipe]）。
 *
 * ## 曲线形态的派生对象（圆 / 圆弧 / 椭圆 / 双曲线 / 抛物线 / 圆锥曲线）
 *
 * 它们在列表里是**函数**（隐函数 / 参数方程的表达式是建出来那一刻的文字），
 * 所以施工说明不挂在对象上，而是挂在 `GraphState.curveRecipes`（函数 id → recipe）；
 * 跟随的做法是"按点重算表达式，表达式变了就换掉那一条函数"（`rebuildCurveEquations`）。
 * 用户一旦手改过那条表达式（`src1`/`src2` 与重算结果对不上），就**自动解除跟随**，
 * 绝不覆盖用户的输入。这一份 recipe 同样会随文件与本地存档存取（`R|F` 段）。
 *
 * ## 仍然不跟随的
 *
 *   * **对称曲线**（折线碎片）：每次重采样就会整批重建，不需要 recipe。
 */
sealed interface ObjectRecipe {
    /**
     * 一个"直线输入"：要么是图上的一条几何线对象（[Obj.id]），
     * 要么是一条**没有对象**的常量直线（[Fixed]，目前只有坐标轴这种内联直线）。
     */
    sealed interface LineRef {
        data class Obj(val id: Long) : LineRef
        data class Fixed(val line: GLine) : LineRef
    }

    /** 两点定线：线段 / 直线 / 射线（[kind] 区分三种）。 */
    data class LineFromPoints(val a: Long, val b: Long, val kind: GLineKind) : ObjectRecipe

    /** 两点的中垂线。 */
    data class PerpendicularBisectorOf(val a: Long, val b: Long) : ObjectRecipe

    /** 三点定角的角平分线（第二个点是顶点）。 */
    data class AngleBisectorOf(val a: Long, val b: Long, val c: Long) : ObjectRecipe

    /** 过一点作一条直线的垂线。 */
    data class PerpendicularTo(val source: LineRef, val through: Long) : ObjectRecipe

    /** 过一点作一条直线的平行线。 */
    data class ParallelTo(val source: LineRef, val through: Long) : ObjectRecipe

    /** 两点的中点。 */
    data class MidpointOf(val a: Long, val b: Long) : ObjectRecipe

    /** 两点之间的距离。 */
    data class DistanceBetween(val a: Long, val b: Long) : ObjectRecipe

    /** 若干个点围成的多边形面积。 */
    data class AreaOfPoints(val points: List<Long>) : ObjectRecipe

    /** 两条线量出来的角度。 */
    data class AngleBetweenLines(val first: LineRef, val second: LineRef) : ObjectRecipe

    /** 一个点关于某条轴 / 某个中心的对称点。 */
    data class MirrorPointOf(val source: Long, val mirror: Mirror) : ObjectRecipe {
        sealed interface Mirror {
            data class Axis(val line: LineRef) : Mirror
            data class Center(val point: Long) : Mirror
        }
    }

    // ---- 曲线（函数列表里的隐函数 / 参数方程） ----
    //
    // 圆 / 圆弧 / 椭圆 / 双曲线 / 抛物线 / 过五点的圆锥曲线在列表里是**函数**
    // （隐函数或参数方程的表达式是建出来那一刻的字符串），所以它们没法像几何对象
    // 那样直接改坐标 —— 跟随的做法是"按点重算表达式，表达式变了就换掉那一条函数"。
    // 表达式一旦被用户自己改过（`src1`/`src2` 不再是这里算出来的那份），
    // 就**自动解除**跟随（见 `GraphState.rebuildCurveEquations`），绝不覆盖用户的输入。

    /** 圆（圆心 + 圆上一点）。 */
    data class CircleCenterPoint(val center: Long, val through: Long) : ObjectRecipe

    /** 圆（圆心 + 两个半径端点）：半径取那两点的距离。 */
    data class CircleCenterRadius(val center: Long, val a: Long, val b: Long) : ObjectRecipe

    /** 圆（圆心 + 一条线段当半径）：半径取线段的长度。 */
    data class CircleCenterRadiusLine(val center: Long, val line: LineRef) : ObjectRecipe

    /**
     * 圆（圆心 + 用户输入的**半径表达式**，如 `2.5` / `d` / `d/2`）。
     *
     * 和上面两种"半径由几何量算出来"的不同：[radiusSource] 是用户原样的表达式，
     * 重算时仍然交给 [circleRadiusExpressionEquation]，所以拖滑动条 / 拖圆心都生效。
     */
    data class CircleCenterRadiusExpr(val center: Long, val radiusSource: String) : ObjectRecipe

    /** 圆（过三点）。 */
    data class CircleThreePoints(val a: Long, val b: Long, val c: Long) : ObjectRecipe

    /** 圆弧（圆心、起点、终点）：参数方程，量程跟着三点重算。 */
    data class ArcOfPoints(val center: Long, val start: Long, val end: Long) : ObjectRecipe

    /** 椭圆（两焦点 + 椭圆上一点）。 */
    data class EllipseOf(val f1: Long, val f2: Long, val on: Long) : ObjectRecipe

    /** 双曲线（两焦点 + 曲线上一点）。 */
    data class HyperbolaOf(val f1: Long, val f2: Long, val on: Long) : ObjectRecipe

    /** 抛物线（焦点 + 准线）。 */
    data class ParabolaOf(val focus: Long, val directrix: LineRef) : ObjectRecipe

    /** 过五点的圆锥曲线。 */
    data class ConicFivePoints(val points: List<Long>) : ObjectRecipe

    /** 切线：函数 [function] 在点 [point] 处的那一条。 */
    data class TangentAtPoint(val point: Long, val function: Long) : ObjectRecipe
}

/**
 * 存档里**不存** [ObjectRecipe]（见上面的说明），所以重开软件之后"两点定线"的
 * 直线（直线 / 线段 / 射线工具画出来的）会退化成一张死快照：再拖描出来的点，
 * 线不动了（用户实测："关闭软件之后再打开，原本描的点派生的直线就不能跟着
 * 原本描的点的拖动而动了"）。
 *
 * 修法不做文件格式迁移，而是**从几何本身把施工说明认回来**：这类线的两个锚点
 * 坐标就是建线时那两个点的坐标（`lineOf(a, b)` 的 `p` / `q`），存盘时点和线
 * 一起写、读盘时一起按原值恢复，所以逐位相等。只要 `p`、`q` 各自压在一个点对象上，
 * 就把它还原成 [ObjectRecipe.LineFromPoints]。
 *
 * 只对"直线 / 线段 / 射线"三种 role 调用（见 [LineFromPoints] 的创建处）：
 * 垂线、平行线、切线等的锚点不一定压着点对象，硬认会把它们误绑成另一条线。
 *
 * @return 认不出来时返回 null（保持快照行为，和以前一样）。
 */
fun inferLineFromPointsRecipe(
    line: GLine,
    points: List<GraphPointObject>
): ObjectRecipe.LineFromPoints? {
    if (line.isDegenerate) return null
    val a = points.firstOrNull { sameAnchor(it.point, line.p) } ?: return null
    val b = points.firstOrNull { it.id != a.id && sameAnchor(it.point, line.q) } ?: return null
    return ObjectRecipe.LineFromPoints(a.id, b.id, line.kind)
}

/** 两个锚点是否"就是同一个点"：存盘/读盘是同一个 double 的往返，容差只为二进制误差。 */
private fun sameAnchor(p: GPoint, q: GPoint): Boolean =
    abs(p.x - q.x) <= 1e-9 && abs(p.y - q.y) <= 1e-9

/**
 * 换掉一个对象的"施工说明"（读盘时按 id 套上文件里存的那一份）。
 *
 * 三种对象都有 `recipe` 字段，这里只是把三个 `copy` 收成一处，
 * 免得装载代码里写三遍 `when`。
 */
fun GraphObject.withRecipe(recipe: ObjectRecipe?): GraphObject = when (this) {
    is GraphPointObject -> copy(recipe = recipe)
    is GraphLineObject -> copy(recipe = recipe)
    is GraphMeasureObject -> copy(recipe = recipe)
}

/** 读一个对象的"施工说明"（三种实现都有这个字段，但接口上不暴露，所以在这里摊平）。 */
val GraphObject.recipeOrNull: ObjectRecipe?
    get() = when (this) {
        is GraphPointObject -> recipe
        is GraphLineObject -> recipe
        is GraphMeasureObject -> recipe
    }

/**
 * 一个**吸附在曲线上**的点（用户要求："滑动条控制的描点动了之后，
 * 跟着这个点创建的对象也要跟着动"）。
 *
 * 只有"吸到曲线本身"（[SnapKind.OTHER]）的点才有它：特征点（交点/零点/极值点）
 * 是**多曲线共同**算出来的，跟随需要记录"哪两条曲线、算哪一个交点"，
 * 本轮不做（记 null = 不跟随，行为和以前一样）。
 *
 * 参数记的是**位置**而不是值：
 *   * [OnCurve.x] = 显函数的横坐标（滑动条变了就按新的 f 重新取 y，
 *     所以曲线上下平移/伸缩时点**垂直**跟着走，这正是"描在曲线上"的含义）；
 *   * [OnLine.t] = 自绘直线上的**投影参数**（线段夹 0~1、射线 ≥0，
 *     直线不夹），线被拖动后按同一个 t 重新取点。
 */
sealed interface PointAttachment {
    /** 吸在一条**显函数**曲线上：跟着 `y = f(x)` 走。 */
    data class OnCurve(val functionId: Long, val x: Double) : PointAttachment

    /** 吸在一条自绘直线/线段/射线上：跟着它的参数 [t] 走。 */
    data class OnLine(val lineId: Long, val t: Double) : PointAttachment

    /**
     * 吸在**两条对象的交点**上：两条曲线一起动，这个点就跟着交点走。
     *
     * 用户原话："再给描点加个吸附曲线（包括函数曲线和自绘曲线）交点的功能，
     * 描出来的点是空心的，随着曲线交点位置的变动而变动。"
     *
     * 两端各记**一个**引用，函数曲线和自绘线都可能是端点，所以每端有两个字段、
     * 恰好一个非空 —— 于是三种情形都由这一个类型覆盖：
     *   * 函数 ↔ 函数（`aFunction` + `bFunction`）；
     *   * 函数 ↔ 自绘线（`aFunction` + `bLine` / `aLine` + `bFunction`）；
     *   * 自绘线 ↔ 自绘线（`aLine` + `bLine`）。
     *
     * 不记"第几个交点"：曲线一动交点的数量/顺序都会变，序号立刻失效。
     * 重算时取**离这个点当前位置最近**的那个交点 —— 这在连续变形下就是同一个交点，
     * 也正好是"跟着交点走"的定义（见 `GraphState.attachedPosition`）。
     */
    data class OnIntersection(
        val aFunction: Long? = null,
        val aLine: Long? = null,
        val bFunction: Long? = null,
        val bLine: Long? = null
    ) : PointAttachment
}

// ===================================================================================
// 改名规则（用户本轮要求："给所有函数列表里面的对象都加长按菜单，直接调用函数的长按菜单，
// 但是注意要适配各个对象的特征，比如点的名字只能是大写，诸如此类。"）
//
// 这一整块刻意写成**纯 Kotlin**（不 import 任何 Compose/Android 类型），
// 因为它是"改了名字之后，用户写的表达式还能不能对上"的唯一判据 ——
// 这种规则最该被离线单测钉住（`tools\run-tool-tests.ps1` + `PointCoordinatesTest`）。
// 界面侧只负责"把校验结果画出来"和"把改名落到状态上"。
// ===================================================================================

/**
 * 对象名字该用哪一套字母。
 *
 * 用户这一轮把规则简化成一句话："除了点之外的所有对象的名字应该都是小写字母。"
 *
 *  * [UPPER]：**只有点**（[GraphPointObject]）—— `x(A)` / `y(A)` 这种**取坐标**
 *    写法靠 `isPointName`（只认大写字母序列）识别点名，所以点必须留在大写这一档；
 *  * [LOWER]：**其余全部** —— 直线/线段/射线、角度/距离/面积，以及以后任何新类型。
 *
 * 为什么以前直线是大写、现在要改：以前的分法是"几何对象（点+线）大写、标量小写"，
 * 用户这一轮明确否掉了它。已知代价（写在这里免得后人以为是漏了）：
 * 角度的副标题 `角度（c，d）` 里那两个字母现在是**线段名**，和标量名共用一套小写
 * 字母表 —— 用户接受这个代价，因为列表第一行同时写着对象名、第二行写着数值。
 */
enum class ObjectNameCase { UPPER, LOWER }

/**
 * 对象的**种类**（不是实例）。
 *
 * 为什么要有它：[ObjectNameCase] 的判据必须能被**发名字**那一侧调用，
 * 而发名字的时候对象**还不存在**（名字是先算出来、再拿去构造对象的）。
 * 所以判据的入口是"种类"，对象实例那个重载只是转一手。
 */
enum class ObjectKind {
    /** 点：唯一用大写的一类。 */
    POINT,

    /** 直线 / 线段 / 射线（含垂线、中垂线、角平分线、切线、对称结果）。 */
    LINE,

    /** 角度 / 距离 / 面积。 */
    MEASURE
}

/** 对象名字最多几个字母。3 个够用（`A`、`AA`、`ABC`），列表行也放得下。 */
const val MAX_OBJECT_NAME_LEN: Int = 3

/**
 * 小写名字里**不许自己打进去**的那几个。
 *
 * 它们不是"分配器不许发"（自动取名只避开真正被占用的名字，见
 * `FunctionTools.nextScalarName`），而是"**改名时不许手打**"：用户手打一个字
 * 改的是**所有**表达式里那个字母的含义 —— `x`、`y` 是自变量，`t` 是极坐标参数，
 * `e`/`i`/`z` 是引擎里的常量与复数单位。小写名字是**能直接写进表达式**的
 * （`a*x`、`x^2+a`），所以把一个对象改名成 `x` 会让已有曲线**静默**改变形状
 * （比报错更难发现）。分配器那条路有绑定表兜底（`GraphState.functionBindings`），
 * 手打这条路没有。
 *
 * 这条规则现在管**所有小写对象**（直线、角度、距离、面积），不再只管标量 ——
 * 名字从这个常量改叫 `RESERVED_LOWER_NAMES` 就是为了这件事。
 */
private val RESERVED_LOWER_NAMES = setOf("x", "y", "t", "e", "i", "z")

/**
 * 这个**种类**的对象用哪一套字母 —— **唯一的判据**。
 *
 * 发名字（`GraphState.nextObjectName(kind)`）和校验改名（[objectNameError]）
 * 都调它，所以"新建的对象叫什么"和"改名时允许打什么"永远是同一条规则。
 *
 * `when` 是穷举的：将来加一种对象，编译器会在这里报"when 不穷尽"，
 * 逼人当场判一次"它该用大写还是小写"（默认答案几乎一定是小写 —— 只有点是大写）。
 */
fun objectNameCase(kind: ObjectKind): ObjectNameCase = when (kind) {
    ObjectKind.POINT -> ObjectNameCase.UPPER
    ObjectKind.LINE, ObjectKind.MEASURE -> ObjectNameCase.LOWER
}

/** 这个对象是哪个种类（[objectNameCase] 的实例入口）。 */
fun objectKindOf(o: GraphObject): ObjectKind = when (o) {
    is GraphPointObject -> ObjectKind.POINT
    is GraphLineObject -> ObjectKind.LINE
    is GraphMeasureObject -> ObjectKind.MEASURE
}

/** 这个对象的名字用大写还是小写字母（见 [ObjectNameCase]）。 */
fun objectNameCase(o: GraphObject): ObjectNameCase = objectNameCase(objectKindOf(o))

/**
 * 名字**合法吗**（只看字符集与长度，不看唯一性）。
 *
 * 拆出一个"只看字符集"的版本，是因为属性页要**一边打字一边**决定
 * "这个字符能不能进去"（和大写/小写锁定的键盘同一套判据），
 * 而 [objectNameError] 那一步还要查"有没有重名"，两者时机不同。
 */
fun isValidObjectName(o: GraphObject, name: String): Boolean = when (objectNameCase(o)) {
    ObjectNameCase.UPPER -> isPointName(name) && name.length <= MAX_OBJECT_NAME_LEN
    ObjectNameCase.LOWER -> isScalarName(name) && name.length <= MAX_OBJECT_NAME_LEN
}

/**
 * 改名时的完整校验。
 *
 * @param name 用户想改成的新名字（已经去过空格）。
 * @param taken **其它**占用者：别的对象名、函数名、滑动条名（调用方给，
 *   因为它比这个文件更清楚"现在的名字表长什么样"）。**不要**把对象自己的旧名字放进来 ——
 *   否则"点一下不改"也会被报成重名。
 * @return null = 可以改；否则是**给用户看的中文原因**（属性页直接显示这一句）。
 *
 * 错误文案都是完整句子：这一屏没有多余的提示位，字符串本身就是提示。
 * 文案里**不再写"标量"**：同一条规则现在管着直线和度量，说"标量"会让用户在
 * 改一条直线的名字时对不上号（那句话是说给他看的，不是给代码看的）。
 */
fun objectNameError(o: GraphObject, name: String, taken: Set<String>): String? {
    if (name.isEmpty()) return "名字不能为空"
    if (name.length > MAX_OBJECT_NAME_LEN) return "名字最多 $MAX_OBJECT_NAME_LEN 个字母"
    when (objectNameCase(o)) {
        ObjectNameCase.UPPER -> if (!isPointName(name)) {
            return "只有点能用大写字母（A~Z）"
        }

        ObjectNameCase.LOWER -> {
            if (!isScalarName(name)) return "点以外的对象只能用小写字母（a~z）"
            if (name in RESERVED_LOWER_NAMES) {
                return "$name 在表达式里有固定含义，不能当对象名"
            }
        }
    }
    if (name in taken) return "$name 已经被别的对象占用了"
    return null
}

/** 标识符字符：字母、数字、下划线（和 `PointCoordinates` 里那套识别口径一致）。 */
private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * 把表达式里**按名字引用**的标识符 `from` 换成 `to`（改名时同步引用）。
 *
 * ## 为什么需要它
 *
 * 对象和表达式之间有两种引用方式，性质完全不同：
 *   * **绑定表**（`GraphState.functionBindings`）是**按对象 id** 记的 —— 改名不会断；
 *   * 但用户**写在表达式文本里**的引用是**按名字**的：`x(A)`、`y(A)`、`a*x`。
 *     改了名之后这些引用就会变成"不认识的名字 A"（报错）或者更糟：
 *     撞上另一个同名对象，静默画成另一条线。
 *
 * 本轮的选择是**同步改引用**（而不是"禁止改被引用的名字"或"只提示"）：
 * 用户要的是"直接调用函数的那套长按菜单"，那里面名字是能改的，
 * 改完之后"我这条曲线还是指着那个点"才是符合直觉的结果。
 *
 * ## 两条必须守住的边界
 *
 * 1. **整体标识符匹配**：`a` 不能改掉 `ab` 里的那个 `a`（所以前后都要检查字符边界）；
 * 2. **后面紧跟 `(` 的不动**：那是**函数调用**的名字（`a(x)` 是函数 `a`，
 *    不是标量 `a`），它和对象是两个命名空间 —— 用户完全可能既有函数 `a`
 *    又有一个标量对象 `a`（项目就是这么设计的：重名由绑定表消歧）。
 *
 * @param src 表达式原文（可能是空串）。
 * @return 换过之后的表达式；`from` 为空或等于 `to` 时原样返回。
 */
fun renameIdentifierIn(src: String, from: String, to: String): String {
    if (from.isEmpty() || to.isEmpty() || from == to) return src
    if (src.isEmpty() || !src.contains(from)) return src
    val sb = StringBuilder(src.length + 8)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (!isIdentifierChar(c) || (i > 0 && isIdentifierChar(src[i - 1]))) {
            // 不在标识符的开头（要么不是标识符字符，要么是标识符中间）→ 原样搬过去
            sb.append(c)
            i++
            continue
        }
        var j = i
        while (j < src.length && isIdentifierChar(src[j])) j++
        val word = src.substring(i, j)
        val isCall = j < src.length && src[j] == '('
        sb.append(if (word == from && !isCall) to else word)
        i = j
    }
    return sb.toString()
}
