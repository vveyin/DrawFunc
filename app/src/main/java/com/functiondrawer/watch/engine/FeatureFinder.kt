package com.functiondrawer.watch.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 一个极值点。 */
data class Extremum(val x: Double, val y: Double, val isMax: Boolean)

/** 某条曲线在给定 x 范围内的可展示特征。 */
data class CurveFeatures(
    val roots: List<Double>,          // 零点的 x 坐标，已去重、已排序
    val extrema: List<Extremum>,      // 极值点，已排序
    val yIntercept: Double?,          // f(0)，仅当 0 落在范围内且有限
    val hasDiscontinuity: Boolean     // 范围内是否检测到不连续/无定义（用于 UI 提示"仅显示部分特征"）
)

/**
 * 从显函数里挖出可以直接展示给用户的特征：零点、极值点（顶点）、y 截距。
 *
 * 两条硬约束沿用了第一版（它们是对的，别丢）：
 *  * 全程不抛异常：任何非法输入（NaN 范围、xMin >= xMax、处处无定义……）都返回空结果；
 *  * **求值次数有硬上界**：所有 f 求值都必须走 [BudgetedFn]，上界见 [MAX_EVALS] 的算式，
 *    所以再疯的函数最多是漏掉特征，不会把表卡死。
 *
 * 但"怎么找"整个换了。第一版是"均匀采样 4000 段 + 变号处二分"，它有个结构性缺陷：
 * **偶数重根不变号**，`y = x^2` 的零点只能靠"某个采样点的 |f| 恰好落在绝对阈值 1e-9 内"
 * 这种运气兜住 —— 网格一错开（x=0 不是采样点、或视口平移过）整条根就没了，
 * 这正是用户报的"y=x^2 告诉我没有零点"。现在改成按**结构**找（见 [analyze] 的几条路径）：
 * 采样网格只负责"分段/给初值"，精度全部交给 [brentRoot] 与 [brentMin] 压到机器精度。
 */
object FeatureFinder {

    // ---------------------------------------------------------------- 调参常量

    /** 采样区间数（采样点 = SAMPLES + 1）。4000 段在手表上是毫秒级，够分辨范围 1/4000 的特征。 */
    private const val SAMPLES = 4000

    /**
     * 5 点差商的步长 `h = (xMax - xMin) / DERIV_STEP_DIV`，即 h ≈ 可见范围的 1e-6。
     *
     * 为什么还是这个量级（两头都是坑，必须权衡）：
     *  * h 太大 —— 差商是 h 尺度上的平均斜率，靠得太近的两个极值会被"抹平"成一个，
     *    而且截断误差（5 点法是 O(h⁴)）会整体推着驻点偏移；
     *  * h 太小 —— 差分是两个几乎相等的数相减，误差约 `eps·|f| / h`；
     *    h 掉到 1e-15·|x| 附近就纯粹是浮点噪声，会报出一堆假极值点。
     *
     * 取范围的 1e-6：比采样间距（范围/4000）小两三个数量级，能分辨网格分不出的驻点；
     * 又远大于浮点分辨率，噪声量级约 `8·eps·|f| / (12h)`，对常见范围（1~100）落在 1e-12 上下。
     */
    private const val DERIV_STEP_DIV = 1e6

    /** 双精度机器 epsilon（2^-52）。所有"压到机器精度"的判据都以它为单位写，不写魔数。 */
    private const val EPS = 2.220446049250313e-16

    /**
     * Brent 迭代次数上限（求根与求极小共用）。
     *
     * 依据：两条算法的保底步都是"把区间对半砍"（求根退回二分，求极小退回黄金分割 0.381），
     * 于是 60 次迭代最坏也能把区间压到 `0.381^60 ≈ 1e-25` 倍 —— 任何双精度区间都早已贴到
     * ulp（浮点再也分不开）。所以 60 既是"够用"的上界，也是"一定结束"的保险：
     * 每轮至多 1 次新求值，单次求根的最坏代价就是 60 次求值。
     */
    private const val BRENT_MAX_ITERS = 60

    /** 相邻样本 |f| 的直接阈值：超过就认为区间里"炸了"。 */
    private const val HUGE = 1e12

    /** 相邻样本 |f| 的比值阈值：跳变这么夸张只能是渐近线/间断。 */
    private const val RATIO_LIMIT = 1e6

    /**
     * 求根收敛后，|f| 要小于 `该系数 × 函数量的中位数` 才算真根（否则是渐近线）。
     *
     * 这里刻意**不是**原来的绝对阈值 ZERO_EPS = 1e-9：
     * 绝对阈值对 `1e-12·x^2` 这种整体很小的函数会把**整条曲线**都判成 0，
     * 于是每个采样点都成"零点"（实测在 [-10,10] 上报出 5 个散布全区的假零点）。
     * 改成"相对这个函数自己的量级"，同一套判据就能同时应付 1e-12·x² 和 1e12·(x-1)。
     */
    private const val ROOT_ACCEPT_FACTOR = 1e-6

    /** 极值点处 |f| 超过 `该系数 × 参考量级` 就认为是发散（极点），不是极值。 */
    private const val DIVERGE_FACTOR = 1e6

    /** 差商被当成 0 的相对阈值（相对于差商自身的中位量级）。 */
    private const val DERIV_ZERO_FACTOR = 1e-9

    /**
     * 相切零点判据：`|f(x*)| <= TOUCH_REL × min(|f(x*-w)|, |f(x*+w)|)` 才算"贴到了 0"。
     *
     * 取值依据（分母是 x* 两侧 w 处的函数值，也就是"离开根这么远，函数涨到多少"）：
     *  * 真·偶数重根：`(x-r)²` 在 x* 处的值只剩舍入噪声（~1e-30 甚至精确 0），
     *    而两侧 w 处是 `w²` 量级 → 比值 ~1e-25 以下，离 1e-8 还有十几个数量级余量；
     *  * 假候选：`x² + 1e-9` 的最小值是 1e-9，两侧是 `w² ≈ 1e-4` → 比值 1e-5，
     *    比 1e-8 大 1000 倍，稳稳被拒。`(x-0.5)² + 0.01` 的比值更接近 1，更不用说。
     *  * 为什么不用更宽松的 1e-6：判别力全靠分母。分母取大（w 大）会让 1e-5 这种
     *    假候选也混进来，所以这个系数必须跟 [TOUCH_W_STEPS] 一起看（下面有算式）。
     *  * 为什么不用更严的 1e-12：真根处 |f(x*)| 有时被求值本身的舍入噪声（~eps·|f|）托住，
     *    例如 `x²-2x+1` 在 x=1 附近 `|f(x*)| ≈ 1e-16`，分母 `w²≈4e-4` → 比值 2.5e-13，
     *    要求它还小于 1e-12 就只剩 4 倍余量了；1e-8 给这类"有相消误差的展开式"留了 1e4 倍余量。
     */
    private const val TOUCH_REL = 1e-8

    /**
     * 相切判据里参考点离 x* 多远：`w = TOUCH_W_STEPS × 采样步长`（默认 4 步）。
     *
     * 为什么必须有 w（不能拿固定绝对阈值直接和 0 比）：`x²` 与 `x² + 1e-9`、
     * `1e-12·x²` 与 `1e-12·x² + 1e-13` 在数值上唯一的区别就是"谷底离 0 多远"，
     * 而这只有在拿来跟"这个函数自己在附近的正常量级"比时才有意义。
     *
     * 为什么是几倍采样步长、而不是几十步或任意小：
     *  * 下界：w 必须大到让 |f(x*±w)| 由函数的确定性形状主导，而不是求值舍入噪声。
     *    步长是网格能提供的最小可信尺度（比它更小的 w 已经落在"网格分辨不出"的区域）。
     *  * 上界：分母越大判据越松。取 4 步、范围 20 时 w = 0.02，`x² + 1e-9` 的比值
     *    是 `1e-9 / 4e-4 = 2.5e-6`，还算被 1e-8 拦住；一旦 w 涨到 0.032（≈6.5 步）
     *    比值就掉到 1e-6 以下开始误判。所以是"几倍"，不是"几十倍"。
     */
    private const val TOUCH_W_STEPS = 4.0

    /**
     * 两个特征算不算同一个的判据：`|x1 - x2| <= DEDUPE_REL × max(1, |x|)`。
     *
     * 为什么这个量级：同一个根会被两条路径各算一次（变号走 [brentRoot]、相切走 [brentMin]），
     * 两者都收敛到几个 ulp 以内，差异 ≤ `4·eps·|x| ≈ 1e-15·|x|`；取 1e-9（相对）留了 6 个数量级余量。
     * 而第一版用的"范围的 1e-6"太粗：范围 20 时阈值 2e-5，会把真实相距 1e-5 的两根并成一个
     * （`x² - 9e-10` 的 ±3e-5 就会被吞掉）。改成 1e-9 后，`±3e-5` 这对根相距 6e-5 ≫ 1e-9，
     * 一定保留成两个；代价只是同一根的两份拷贝被合并 —— 这正是想要的方向。
     */
    private const val DEDUPE_REL = 1e-9

    /** 一次分析里最多尝试多少个"变号区间"做 Brent 求根（防病态函数把时间吃光；正常函数远达不到）。 */
    private const val MAX_ROOT_BRACKETS = 512

    /** 一次分析里最多尝试多少个"差商变号区间"做极值定位。 */
    private const val MAX_EXTREMUM_BRACKETS = 512

    /** 一次分析里最多对多少个 |f| 谷底做相切判定（每个谷底最坏 60 次求值）。 */
    private const val MAX_TANGENT_BRACKETS = 128

    /** 差商符号变化允许跨越的最大采样段数，跨得太多说明中间有古怪，宁可放弃。 */
    private const val MAX_DERIV_SPAN = 64

    /**
     * 整次分析允许的**求值总次数上限**。算式（每一条都对应下面一处循环）：
     *
     * ```
     * 采样            4001
     * 扫描差商           0   ← 复用已采到的 ys[] 做网格差分，一次额外求值都不花
     * 零点 Brent     512 × 60 = 30720
     * 极值 Brent     512 × 4 × 60 = 122880（每次迭代用 5 点差商，4 次求值）
     * 相切极小       128 × 60 =  7680
     * -------------------------- 合计 ≈ 165281
     * ```
     *
     * 取 200000 就是把这个最坏情况向上取整。**实际代价低得多**：Brent 通常 5~15 轮收敛，
     * 而且"512 个零点区间 + 512 个极值区间"这种病态情形在真实函数上不会出现
     * （实测 `sin(x)·e^{-x/5}+cos(3x)` 整次分析 ≈ 3ms，见注释里那份基准）。
     * 预算用尽后 [BudgetedFn.at] 返回 NaN，后面的判据都会把它当成"不可信"而放弃 ——
     * 安静降级，不会抛异常。
     */
    private const val MAX_EVALS = 200000

    // ---------------------------------------------------------------- 主入口

    /**
     * 求 [xMin, xMax] 上 [f] 的特征。
     *
     * 四条独立路径，最后合起来排序去重。采样网格只负责"分区 + 给初值"，
     * 精度全在迭代求解上 —— 这也是 GeoGebra 数值求根的路子：不指望网格命中根，
     * 而是按结构把问题缩小到"一段里最多一个根"，再在段内迭代到机器精度。
     *
     *  1. **采样点恰好为 0**：网格正好落在根上（`x^2` 且 0 是采样点、`sqrt(0)` 这种定义域边界）。
     *  2. **单调段 + 变号**：把采样序列切成单调段（相邻差同号），段内**最多一个**零点；
     *     段两端严格异号时，这一段里**恰好**有一个根，直接交给 [brentRoot]。
     *  3. **|f| 谷底 + 相切判据**：偶数重根不变号，路径 2 永远看不见它。
     *     找 |f| 在网格上的局部极小，用 [brentMin] 压到机器精度，再用 [isTangentZero] 判定。
     *     **这是本次修 bug 的核心路径**，`x^2` 的 (0,0) 就是从这里出来的。
     *  4. **极值点**：差商变号（扫描用**网格差分**、零额外求值）→ [brentRoot] 在差商上精修
     *     （精修用 [deriv5]，O(h⁴)）→ "确实比邻居大/小"校验。
     *
     * @param xMin,xMax 可见的 x 范围（世界坐标）
     * @param limit     每类特征最多返回多少个（默认 5，防止 UI 塞不下）
     */
    fun analyze(f: Expression, xMin: Double, xMax: Double, limit: Int = 5): CurveFeatures {
        val empty = CurveFeatures(emptyList(), emptyList(), null, false)

        // 多变量（隐函数上下文）没法按单个 x 描述特征
        if (f.variables.size > 1) return empty
        // 非法范围：安静的返回空结果
        if (!xMin.isFinite() || !xMax.isFinite() || xMin >= xMax) return empty

        val range = xMax - xMin
        val step = range / SAMPLES
        // 范围大到溢出（range = Inf）或小到采样点全部重合（step = 0）时都没法分析
        if (!range.isFinite() || !step.isFinite() || step <= 0.0) return empty

        val arg = DoubleArray(if (f.variables.isEmpty()) 1 else f.variables.size)
        val fn = BudgetedFn(f, arg, MAX_EVALS)
        val maxKeep = if (limit < 0) 0 else limit

        val yInt = yInterceptAt(f, xMin, xMax, arg)

        val n = SAMPLES
        val xs = DoubleArray(n + 1)
        val ys = DoubleArray(n + 1)
        for (i in 0..n) {
            val x = if (i == n) xMax else xMin + step * i
            xs[i] = x
            ys[i] = fn.at(x)
        }

        var hasDisc = false

        // ---------- 第 0 步：标记"不可信区间" ----------
        // bad[i] 表示第 i 段（xs[i] ~ xs[i+1]）跨过了不连续/无定义，
        // 后面的每一条扫描路径都必须绕开它，否则 tan(x) 会在渐近线附近报一堆假特征。
        val bad = BooleanArray(n)
        for (i in 0 until n) {
            val a = ys[i]
            val b = ys[i + 1]
            if (!a.isFinite() || !b.isFinite()) {
                bad[i] = true
                hasDisc = true
                continue
            }
            val aa = abs(a)
            val bb = abs(b)
            if (aa > HUGE || bb > HUGE) {
                bad[i] = true
                hasDisc = true
                continue
            }
            val lo = if (aa < bb) aa else bb
            val hi = if (aa < bb) bb else aa
            if (lo > 0.0 && hi / lo > RATIO_LIMIT) {
                // 相邻样本跳了 6 个数量级以上：例如 1/x 在 0 附近从 -4e2 跳到 +1e16
                bad[i] = true
                hasDisc = true
            }
        }

        // 函数值的代表性量级（中位数），所有"算不算接近 0"的判断都相对它来做
        val yScale = representativeScale(ys)
        // 求根收敛后允许的残差：相对阈值，兼顾 f 整体很大（1e9·(x-1)）与整体很小（1e-12·x²）的情况
        val rootTol = ROOT_ACCEPT_FACTOR * yScale

        val rootHits = ArrayList<Double>()

        // ---------- 路径 1：采样点恰好是 0 ----------
        // 只认**精确的** 0.0，不再有绝对阈值。第一版这里是 `|f| <= 1e-9`，
        // 对 1e-12·x² 这种函数等于把整条曲线都收成零点（用户报的另一半问题）。
        // 两侧区间都不可信的点不要（典型是 sqrt(x) 在 0 处的定义域边界已经由路径 2/3 覆盖）。
        for (i in 0..n) {
            if (ys[i] != 0.0) continue
            val leftBad = i > 0 && bad[i - 1]
            val rightBad = i < n && bad[i]
            if (leftBad && rightBad) continue
            rootHits.add(xs[i])
        }

        // ---------- 路径 3 的候选：|f| 在网格上的局部极小 ----------
        // 现在就挑出来（此时 bad 还是"原始"的），处理时再复核一次 bad：
        // 路径 2 可能在中间又把某些段标成不可信（识别出渐近线时）。
        val valleys = ArrayList<Int>()
        for (j in 1 until n) {
            if (bad[j - 1] || bad[j]) continue
            val y0 = abs(ys[j])
            val yl = abs(ys[j - 1])
            val yr = abs(ys[j + 1])
            if (!y0.isFinite() || !yl.isFinite() || !yr.isFinite()) continue
            if (y0 < yl && y0 < yr) valleys.add(j)
        }

        // ---------- 路径 2：单调段 + 变号 → Brent 求根 ----------
        // dir[i] = 第 i 段的单调方向；不可信段算 0（"方向未知"）。
        // 平台（差正好为 0）也留作 0：宁可把段切碎，也不要让"段内单调"这条推理失效。
        val dir = IntArray(n)
        for (i in 0 until n) dir[i] = if (bad[i]) 0 else signOf(ys[i + 1] - ys[i])

        var rootBrackets = 0
        var segStart = 0
        var i = 1
        while (i <= n) {
            val boundary = i == n || dir[i - 1] == 0 || dir[i] == 0 || dir[i - 1] != dir[i]
            if (boundary) {
                val s = segStart
                val e = i
                val ysS = ys[s]
                val ysE = ys[e]
                if (e > s && ysS.isFinite() && ysE.isFinite() &&
                    (ysS < 0.0) != (ysE < 0.0) && rootBrackets < MAX_ROOT_BRACKETS
                ) {
                    // 单调段两端严格异号 → 段内恰好一个根，整段作为夹逼区间交给 Brent。
                    // 用**整段**而不是相邻两个采样点，是为了让插值有足够的形状信息，
                    // 收敛更快、也不会因为根贴近采样点而丢精度。
                    rootBrackets++
                    val xr = brentRoot({ x -> fn.at(x) }, xs[s], xs[e], ysS, ysE)
                    val yr = if (xr == null) Double.NaN else fn.at(xr)
                    if (xr != null && isRootResidual(yr, xs[s], xs[e], ysS, ysE, rootTol)) {
                        rootHits.add(xr)
                    } else {
                        // 段内确实变号，但收敛不到 0 —— 这不是根，是渐近线。
                        // 例：tan(x) 在 π/2 两侧从 +1255 跳到 -1585，采样比值只有 1.3 倍，
                        // 逃过了 bad 的比值判据，只能靠"收敛后 |f| 不趋近 0"来识破。
                        hasDisc = true
                        val at = if (xr != null && xr.isFinite()) {
                            ((xr - xMin) / step).toInt().coerceIn(0, n - 1)
                        } else {
                            if (e - 1 < s) s else e - 1
                        }
                        markBad(bad, s, n)
                        markBad(bad, at, n)
                    }
                }
                segStart = i
            }
            i++
        }

        // ---------- 路径 3：|f| 谷底 → 相切零点 ----------
        // 偶数重根本质上是"|f| 贴到 0 的局部极小"，所以要把 |f| 的极小压到机器精度再判。
        // 这里对 |f| 做 Brent 极小化（不需要导数，天然适合 |f| 在根处的尖点形状）：
        // 极小值本身趋近 0，没有"函数值被 eps·|f| 托住"的分辨极限，能一路压到 ulp。
        val w = TOUCH_W_STEPS * step
        var tangentTried = 0
        for (j in valleys) {
            if (tangentTried >= MAX_TANGENT_BRACKETS) break
            if (bad[j - 1] || bad[j]) continue
            tangentTried++
            val res = brentMin({ x -> abs(fn.at(x)) }, xs[j - 1], xs[j], xs[j + 1]) ?: continue
            if (!res.x.isFinite() || !res.f.isFinite()) continue
            if (isTangentZero(fn, res.x, res.f, w)) rootHits.add(res.x)
        }

        val roots = dedupeSorted(rootHits, maxKeep)

        // ---------- 路径 4：极值点 ----------
        val extHits = ArrayList<Extremum>()
        val h = range / DERIV_STEP_DIV
        if (h > 0.0 && h.isFinite()) {
            // ---- 4a. 扫描用的差商：**直接复用已经采到的 ys[]，一次额外求值都不花** ----
            //
            // 为什么不像下面精修那样用 5 点小步长：这一遍只负责"找出差商变号的那一小段"，
            // 判据是**网格分辨率**（range/4000）决定的，把差商算得再准也不会多找到一个
            // 采样点之外的极值。而 5 点差商要对 4001 个采样点各求 4 次值（16004 次），
            // 实测把整个 analyze 拖慢到原来的 1.7~2.7 倍 —— 而 analyze 挂在
            // "描点拖动"的每一次 move 上（GraphState.snapCandidates ← movePendingPoint），
            // 手表上这就是直接掉帧。用网格自身间距做中心差分（内部点）、
            // 单向差分（两端点）**零成本**，而"哪一段里差商变号"和旧实现完全一致。
            //
            // 精度去哪儿了？去 4b：真正定位驻点时用的仍是 [deriv5]（小步长、5 点），
            // 所以 `x³-3x` 的 ±1、`(x-1)²(x+2)` 的 -1 那些精度断言一点没变。
            val d = DoubleArray(n + 1)
            for (i in 0..n) {
                val dl = if (i > 0) (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1]) else Double.NaN
                val dr = if (i < n) (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) else Double.NaN
                d[i] = when {
                    dl.isFinite() && dr.isFinite() -> 0.5 * (dl + dr)   // 内部点 = 中心差分
                    dr.isFinite() -> dr                                // 左端点 = 前向差分
                    dl.isFinite() -> dl                                // 右端点 = 后向差分
                    else -> Double.NaN
                }
            }
            val dScale = representativeScale(d)
            val dZero = DERIV_ZERO_FACTOR * dScale

            // 只跟踪"确定的符号"：差商为 0（常数函数整条线、x^3 在 0 处导数**贴着** 0 但不变号）
            // 既不算正也不算负，所以符号变化必须发生在两个 nonzero 样本之间 ——
            // 这样拐点（x^3）不会被当成极值点。
            var lastIdx = -1
            var lastSign = 0
            var extremaTried = 0
            for (j in 0..n) {
                val dj = d[j]
                if (!dj.isFinite()) {
                    lastIdx = -1
                    lastSign = 0
                    continue
                }
                val s = if (dj > dZero) 1 else if (dj < -dZero) -1 else 0
                if (s == 0) continue

                if (lastIdx >= 0 && s != lastSign && extremaTried < MAX_EXTREMUM_BRACKETS) {
                    val span = j - lastIdx
                    if (span in 1..MAX_DERIV_SPAN && !spansBad(bad, lastIdx, j)) {
                        extremaTried++
                        val isMax = lastSign > 0        // 差商由正变负 → 极大
                        // 在差商上做 Brent：迭代判据是 4 个 ulp，所以**迭代本身**是压到机器精度的；
                        // 剩下的误差来自差商自身的噪声（见 [deriv5] 的注释），不是迭代没收敛。
                        val xd = brentRoot(
                            { x -> deriv5(fn, x, h) }, xs[lastIdx], xs[j], d[lastIdx], d[j]
                        )
                        if (xd != null) {
                            val y0 = fn.at(xd)
                            val ref = max(yScale, max(abs(ys[lastIdx]), abs(ys[j])))
                            val divergent = !y0.isFinite() || abs(y0) > HUGE ||
                                (ref > 0.0 && abs(y0) > DIVERGE_FACTOR * ref)
                            if (divergent) {
                                // 差商在这里变号，但 f 本身在发散 —— 是极点（如 1/x^2 的 0 处）
                                hasDisc = true
                                markBad(bad, lastIdx, n)
                                markBad(bad, j - 1, n)
                            } else if (isLocalExtremum(fn, xd, y0, h, isMax)) {
                                extHits.add(Extremum(xd, y0, isMax))
                            }
                        }
                    }
                }
                lastIdx = j
                lastSign = s
            }
        }

        val extrema = dedupeExtrema(extHits, maxKeep)

        return CurveFeatures(roots, extrema, yInt, hasDisc)
    }

    // ---------------------------------------------------------------- 求值

    /**
     * 带**求值预算**的求值器：analyze 里每一次 f 求值都必须走它。
     *
     * 预算耗尽后一律返回 NaN，于是所有调用方（Brent、差商、相切判据）都会看到
     * "不可信"并按已有的安静失败路径放弃 —— 不需要在每个循环里塞一个 if。
     * 这就是"再疯的函数也不会卡死"这句承诺在实现上的落点。
     */
    private class BudgetedFn(
        private val f: Expression,
        private val arg: DoubleArray,
        private val maxEvals: Int
    ) {
        private var evals = 0

        fun at(x: Double): Double {
            if (evals >= maxEvals) return Double.NaN
            evals++
            arg[0] = x
            return f.evalArray(arg)
        }
    }

    /**
     * 5 点中心差商 `f'(x) ≈ (-f(x+2h) + 8f(x+h) - 8f(x-h) + f(x-2h)) / (12h)`。
     *
     * 为什么比原来的 3 点差商值：驻点定位的误差有两块 ——
     *  * 截断误差：3 点是 `h²·f'''/6`，5 点是 `h⁴·f⁵/30`。h = 范围×1e-6 时前者约 1e-10·|f'''|
     *    （对 x³-3x 这种 |f'''|=6 的函数就是 1e-10 量级的 x 偏移），后者小到可以忽略；
     *  * 舍入噪声：约 `8·eps·|f| / (12h)`，比 3 点法大 2~3 倍。
     * 两条曲线的交叉点在 h ≈ eps^(1/5)·|x| ≈ 1e-3·|x| 附近；我们取 h 只有它的千分之一，
     * 属于"截断误差已可忽略、舍入噪声主导"的区间，所以换成高阶公式是净赚。
     *
     * 注意 x±h 在浮点下未必严格对称，所以分母用实际算出来的步长 `hEff`，
     * 并且要求两侧步长都 > 0（x 附近已经密到分辨不出来时宁可返回 NaN 不报特征）。
     */
    private fun deriv5(fn: BudgetedFn, x: Double, h: Double): Double {
        val hp = (x + h) - x
        val hm = x - (x - h)
        if (hp <= 0.0 || hm <= 0.0 || !hp.isFinite() || !hm.isFinite()) return Double.NaN
        val f2 = fn.at(x + 2.0 * h)
        val f1 = fn.at(x + h)
        val fm1 = fn.at(x - h)
        val fm2 = fn.at(x - 2.0 * h)
        if (!f2.isFinite() || !f1.isFinite() || !fm1.isFinite() || !fm2.isFinite()) return Double.NaN
        val hEff = 0.5 * (hp + hm)
        val v = (fm2 - 8.0 * fm1 + 8.0 * f1 - f2) / (12.0 * hEff)
        return if (v.isFinite()) v else Double.NaN
    }

    // ---------------------------------------------------------------- 迭代求解器

    /**
     * Brent 求根（反二次插值 / 割线 + 中点兜底），要求 `g(a)` 与 `g(b)` 严格异号。
     *
     * 收敛判据是 `4·eps·|b|`（即几个 ulp），所以只要函数本身允许，结果就是机器精度；
     * 迭代次数硬上限 [BRENT_MAX_ITERS]。任何异常（求值非有限、预算耗尽）都返回 null，
     * 由调用方按"这不是根"处理 —— 不抛异常。
     *
     * @param fa,fb 调用方已经算过的 g(a)、g(b)，省两次求值（它们就在预算里）
     */
    private fun brentRoot(
        g: (Double) -> Double,
        aIn: Double,
        bIn: Double,
        faIn: Double,
        fbIn: Double
    ): Double? {
        var a = aIn
        var b = bIn
        var fa = faIn
        var fb = fbIn
        if (fa == 0.0) return a
        if (fb == 0.0) return b
        if ((fa < 0.0) == (fb < 0.0)) return null
        // 让 b 始终是"函数值更小"的那一端（返回它，精度更好）
        if (abs(fa) < abs(fb)) {
            val tx = a; a = b; b = tx
            val tf = fa; fa = fb; fb = tf
        }
        var c = a
        var fc = fa
        var d = b - a
        var mflag = true

        var k = 0
        while (k < BRENT_MAX_ITERS) {
            k++
            val tol = 4.0 * EPS * abs(b) + Double.MIN_VALUE
            if (abs(b - a) <= tol || fb == 0.0) return b

            var s: Double
            if (fa != fc && fb != fc) {
                // 反二次插值（三点都不同值时才用，除零自然被这个条件挡住）
                s = a * fb * fc / ((fa - fb) * (fa - fc)) +
                    b * fa * fc / ((fb - fa) * (fb - fc)) +
                    c * fa * fb / ((fc - fa) * (fc - fb))
            } else {
                s = b - fb * (b - a) / (fb - fa)      // 割线
            }
            if (!s.isFinite()) s = 0.5 * (a + b)

            // 插值点必须落在 [ (3a+b)/4, b ] 里（b 是更好的那端），且步长要比上一步明显缩小，
            // 否则退回中点 —— 这条兜底保证最坏情况下每轮至少把区间砍一半。
            val quarter = 0.25 * (3.0 * a + b)
            val inside = if (a < b) (s > quarter && s < b) else (s < quarter && s > b)
            val slow = if (mflag) abs(s - b) >= 0.5 * abs(b - c) else abs(s - b) >= 0.5 * abs(c - d)
            if (!inside || slow) {
                s = 0.5 * (a + b)
                mflag = true
            } else {
                mflag = false
            }
            if (s == a || s == b) return b          // 浮点已经贴死，再算也没意义

            val fs = g(s)
            if (!fs.isFinite()) return null         // 预算耗尽 / 撞上 NaN / 撞上极点
            d = c
            c = b
            fc = fb
            if ((fa < 0.0) != (fs < 0.0)) {
                b = s
                fb = fs
            } else {
                a = s
                fa = fs
            }
            if (abs(fa) < abs(fb)) {
                val tx = a; a = b; b = tx
                val tf = fa; fa = fb; fb = tf
            }
        }
        return b
    }

    /** 一次极小化找到的点。 */
    private class MinResult(val x: Double, val f: Double)

    /**
     * Brent 极小化（抛物线插值 + 黄金分割兜底），在 [ax, bx, cx] 三点给出的区间里找 g 的极小。
     *
     * 为什么相切零点用**极小化**而不是求 `g'=0` 的根：
     *  * |f| 在根处是尖点（simple 根）或平的谷（偶数重根），导数根本不存在/不可靠；
     *  * 更关键的是"分辨极限"：在 x² 这种谷底为 0 的地方，函数值会一路趋近 0，
     *    不存在 `eps·|f|` 那种把相邻点数值抹平的托底效应，所以 x 能压到 ulp。
     *    （对"谷底不为 0"的普通极小值就不行了，那里 x 只能分辨到 sqrt(eps)·尺度 ——
     *     所以普通极值点走的是路径 4 的差商求根，不是这里。）
     *
     * 迭代上限同样是 [BRENT_MAX_ITERS]，而且额外记录"目前见过的最小点"，
     * 万一函数有平台/浮点贴死，返回的也是见过的最好结果而不是垃圾。
     */
    private fun brentMin(g: (Double) -> Double, ax: Double, bx: Double, cx: Double): MinResult? {
        val cgold = 0.3819660112501051
        var a = min(ax, cx)
        var b = max(ax, cx)
        var x = bx
        var w = bx
        var v = bx
        var fx = g(x)
        if (!fx.isFinite()) return null
        var fw = fx
        var fv = fx
        var bestX = x
        var bestF = fx
        var d = 0.0
        var e = 0.0

        var k = 0
        while (k < BRENT_MAX_ITERS) {
            k++
            val xm = 0.5 * (a + b)
            val tol1 = 4.0 * EPS * abs(x) + Double.MIN_VALUE
            val tol2 = 2.0 * tol1
            if (abs(x - xm) <= tol2 - 0.5 * (b - a)) break

            if (abs(e) > tol1) {
                // 三点抛物线插值：p/q 是极值点相对 x 的位移
                val r = (x - w) * (fx - fv)
                val q = (x - v) * (fx - fw)
                var p = (x - v) * q - (x - w) * r
                var qq = 2.0 * (q - r)
                if (qq > 0.0) p = -p
                qq = abs(qq)
                val etemp = e
                e = d
                if (abs(p) >= abs(0.5 * qq * etemp) || p <= qq * (a - x) || p >= qq * (b - x)) {
                    // 插值点跑出区间或步子没缩短 → 黄金分割
                    e = if (x >= xm) a - x else b - x
                    d = cgold * e
                } else {
                    d = p / qq
                    val u = x + d
                    if (u - a < tol2 || b - u < tol2) d = if (xm > x) tol1 else -tol1
                }
            } else {
                e = if (x >= xm) a - x else b - x
                d = cgold * e
            }

            val u = if (abs(d) >= tol1) x + d else x + (if (d > 0.0) tol1 else -tol1)
            if (u == x || u == a || u == b) break     // 浮点已经贴死
            val fu = g(u)
            if (!fu.isFinite()) break

            if (fu <= fx) {
                if (u >= x) a = x else b = x
                v = w; fv = fw
                w = x; fw = fx
                x = u; fx = fu
            } else {
                if (u < x) a = u else b = u
                if (fu <= fw || w == x) {
                    v = w; fv = fw
                    w = u; fw = fu
                } else if (fu <= fv || v == x || v == w) {
                    v = u; fv = fu
                }
            }
            if (fx < bestF) {
                bestF = fx
                bestX = x
            }
        }
        return MinResult(bestX, bestF)
    }

    // ---------------------------------------------------------------- 判据

    /**
     * 求根收敛后的验收：|f(x*)| 要小到什么程度才算根。
     *
     * 两个相对判据取较宽的那个（用 max 是为了**宁可收下真根**）：
     *  * `rootTol = 1e-6 × 函数量级中位数`：挡住渐近线。tan 在 π/2 处收敛过去时
     *    |f(x*)| 能到 1e15 以上，而中位数量级只有 1 上下 → 差 20 个数量级，必拒；
     *  * `1e-6 × 夹逼区间两端中较小的 |f|`：挡住"求值相消噪声"造成的假拒绝。
     *    例：`1e-12·(x²-4)` 在 x=2 附近，`x²-4` 本身有 ~1e-16 的相消误差，
     *    乘上 1e-12 后残差 ~1e-28，比中位数判据（2.5e-17）还大一点，
     *    而它相对区间端点（~1e-3×1e-12）却小得离谱 —— 真根不能被这种噪声判死。
     */
    private fun isRootResidual(
        y: Double, xa: Double, xb: Double, ya: Double, yb: Double, rootTol: Double
    ): Boolean {
        if (!y.isFinite()) return false
        val r = abs(y)
        if (r <= rootTol) return true
        val local = min(abs(ya), abs(yb))
        return local > 0.0 && r <= ROOT_ACCEPT_FACTOR * local
    }

    /**
     * 相切零点判据：把 |f| 的极小值 `|y*|` 与"离开 x* 各 w 处的 |f|"比。
     *
     * 为什么必须相对（不能用固定绝对阈值）：见 [TOUCH_REL] 与 [TOUCH_W_STEPS] 的注释 ——
     * 绝对值判据（原来的 ZERO_EPS = 1e-9）对 1e-12·x² 会把整条曲线判成 0，
     * 对 1e-12·x² + 1e-13 又完全看不出区别；只有"跟函数自己在附近的量级比"才有判别力。
     *
     * `|y*| == 0.0` 直接算根：Brent 把 x* 压到 0（或函数值下溢成 0）时就是这种情况，x² 常走到这。
     */
    private fun isTangentZero(fn: BudgetedFn, xStar: Double, gStar: Double, w: Double): Boolean {
        if (gStar == 0.0) return true
        if (w <= 0.0 || !w.isFinite()) return false
        val yl = abs(fn.at(xStar - w))
        val yr = abs(fn.at(xStar + w))
        if (!yl.isFinite() || !yr.isFinite()) return false
        val ref = min(yl, yr)
        if (ref <= 0.0) return false
        return gStar <= TOUCH_REL * ref
    }

    /** 驻点处再做一次"确实比邻居大/小"的校验，容忍浮点噪声（1e-6 相对）。 */
    private fun isLocalExtremum(fn: BudgetedFn, x0: Double, y0: Double, h: Double, isMax: Boolean): Boolean {
        val yl = fn.at(x0 - h)
        val yr = fn.at(x0 + h)
        if (!yl.isFinite() || !yr.isFinite()) return false
        // 容差只相对这三个值本身，不再像第一版那样兜一个绝对的 1.0 ——
        // 那个绝对底会让 1e-12 量级的函数统统"通过校验"（同一类绝对阈值 bug）。
        val eps = 1e-6 * max(abs(y0), max(abs(yl), abs(yr)))
        return if (isMax) y0 >= yl - eps && y0 >= yr - eps else y0 <= yl + eps && y0 <= yr + eps
    }

    // ---------------------------------------------------------------- 小工具

    private fun signOf(v: Double): Int = when {
        v > 0.0 -> 1
        v < 0.0 -> -1
        else -> 0
    }

    /** 有限值的 |y| 中位数；没有可用值或中位数为 0 时退回最大值，再不行给 1。 */
    private fun representativeScale(values: DoubleArray): Double {
        var count = 0
        var mx = 0.0
        for (v in values) {
            if (!v.isFinite()) continue
            val a = abs(v)
            if (a > mx) mx = a
            count++
        }
        if (count == 0) return 1.0
        val sorted = DoubleArray(count)
        var k = 0
        for (v in values) if (v.isFinite()) sorted[k++] = abs(v)
        sorted.sort()
        val med = sorted[count / 2]
        return when {
            med.isFinite() && med > 0.0 -> med
            mx.isFinite() && mx > 0.0 -> mx
            else -> 1.0
        }
    }

    /** 把第 i 段及其左右各一段标记为不可信（间断处两侧的差商同样是垃圾）。 */
    private fun markBad(bad: BooleanArray, i: Int, n: Int) {
        for (k in (i - 1)..(i + 1)) {
            if (k in 0 until n) bad[k] = true
        }
    }

    /** 第 [from, to) 段里有没有不可信的。 */
    private fun spansBad(bad: BooleanArray, from: Int, to: Int): Boolean {
        for (k in from until to) {
            if (k < 0 || k >= bad.size) return true
            if (bad[k]) return true
        }
        return false
    }

    /**
     * 排序 + 去重 + 截断。
     *
     * 去重阈值是**相对**当前坐标的（见 [DEDUPE_REL]），不是第一版那种"范围的 1e-6"：
     * 范围 20 时后者等于 2e-5，会把 ±3e-5 这种真实的一对根并成一个。
     */
    private fun dedupeSorted(values: List<Double>, limit: Int): List<Double> {
        if (limit == 0 || values.isEmpty()) return emptyList()
        val sorted = values.filter { it.isFinite() }.sorted()
        val out = ArrayList<Double>(minOf(sorted.size, limit))
        for (v in sorted) {
            val last = if (out.isEmpty()) Double.NaN else out[out.size - 1]
            if (out.isEmpty() || v - last > DEDUPE_REL * max(1.0, max(abs(v), abs(last)))) {
                out.add(v)
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun dedupeExtrema(values: List<Extremum>, limit: Int): List<Extremum> {
        if (limit == 0 || values.isEmpty()) return emptyList()
        val sorted = values.filter { it.x.isFinite() && it.y.isFinite() }.sortedBy { it.x }
        val out = ArrayList<Extremum>(minOf(sorted.size, limit))
        for (e in sorted) {
            val last = if (out.isEmpty()) null else out[out.size - 1]
            if (last == null || e.x - last.x > DEDUPE_REL * max(1.0, max(abs(e.x), abs(last.x)))) {
                out.add(e)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** f(0)，仅当 0 落在 [xMin, xMax] 内且值有限。 */
    private fun yInterceptAt(f: Expression, xMin: Double, xMax: Double, arg: DoubleArray): Double? {
        if (xMin > 0.0 || xMax < 0.0) return null
        arg[0] = 0.0
        val v = f.evalArray(arg)
        return if (v.isFinite()) v else null
    }
}
