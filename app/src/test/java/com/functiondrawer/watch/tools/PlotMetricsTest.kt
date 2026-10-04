package com.functiondrawer.watch.tools

import com.functiondrawer.watch.engine.Viewport
import com.functiondrawer.watch.ui.DELETE_OUT_SHIFT
import com.functiondrawer.watch.ui.DELETE_OUT_SHRINK
import com.functiondrawer.watch.ui.LABEL_FIT_SLACK_PX
import com.functiondrawer.watch.ui.LABEL_MIN_GAP_DP
import com.functiondrawer.watch.ui.LABEL_MIN_SNAP_DP
import com.functiondrawer.watch.ui.LABEL_SHIFT_QUANTUM_FRACTION
import com.functiondrawer.watch.ui.LABEL_SNAP_SLACK_FRACTION
import com.functiondrawer.watch.ui.MIN_GRID_PX
import com.functiondrawer.watch.ui.MIN_LABEL_SPACING_FRACTION
import com.functiondrawer.watch.ui.SLIDER_PARAM_COL_W
import com.functiondrawer.watch.ui.SLIDER_ROW_GAP
import com.functiondrawer.watch.ui.SLIDER_ROW_PAD
import com.functiondrawer.watch.ui.SWIPE_DELETE_TINT
import com.functiondrawer.watch.ui.SWIPE_DELETE_TRAVEL
import com.functiondrawer.watch.ui.WATCH_BASE_PX
import com.functiondrawer.watch.ui.gridStepForScreen
import com.functiondrawer.watch.ui.gridTargetPxFor
import com.functiondrawer.watch.ui.labelCornerAnchorPx
import com.functiondrawer.watch.ui.labelFollowBudgetPx
import com.functiondrawer.watch.ui.labelFollowQuantumPx
import com.functiondrawer.watch.ui.labelRangeSpanPx
import com.functiondrawer.watch.ui.labelSnapBudgetPx
import com.functiondrawer.watch.ui.labelsTooDenseForRow
import com.functiondrawer.watch.ui.niceStep
import com.functiondrawer.watch.ui.oneWayDragClaims
import com.functiondrawer.watch.ui.oneWayDragLostToOtherAxis
import com.functiondrawer.watch.ui.oneWayDragStep
import com.functiondrawer.watch.ui.pinLabelIntoBand
import com.functiondrawer.watch.ui.pinLabelNearAxis
import com.functiondrawer.watch.ui.quantizeShiftUpPx
import com.functiondrawer.watch.ui.sliderPlayKeyLeftDp
import com.functiondrawer.watch.ui.sliderPlayKeyRightDp
import com.functiondrawer.watch.ui.sliderPressOnPlayKey
import com.functiondrawer.watch.ui.snapLabelAnchor
import com.functiondrawer.watch.ui.swipeDeleteFollow
import com.functiondrawer.watch.ui.swipeDeleteProgress
import com.functiondrawer.watch.ui.swipeDeleteReached
import com.functiondrawer.watch.ui.swipeDeleteSweepAlpha
import com.functiondrawer.watch.ui.swipeDeleteSweepScale
import com.functiondrawer.watch.ui.xRowRangeFromCorner
import com.functiondrawer.watch.ui.yColumnRangeFromCorner
import com.functiondrawer.watch.ui.swipeDeleteSweepShiftPx
import com.functiondrawer.watch.ui.swipeDeleteTranslationPx
import com.functiondrawer.watch.ui.swipeDeleteTravelPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 绘图界面的尺度换算（`ui/PlotMetrics.kt`）：**网格按屏幕短边定密度**（用户原话
 * "网格应该按照 dp 调节而不是 px"）+ **刻度数字贴边**（"数字移动到屏幕外之后会消失"）。
 *
 * ## 为什么值得离线钉住
 *
 * 这两件事都是"纯算术"，却决定了用户第一眼看到的东西，而且出错时的表现
 * 全都**没法靠肉眼看曲线发现**：
 *   * 格距算错 → 手机上格线密成一片、刻度数字叠在一起（用户报的正是这个）；
 *   * 贴边算错 → 数字在屏幕边缘整条消失（用户报的也是这个），
 *     或者反过来滑过头、跑到离自己那条刻度很远的地方，读数全乱。
 *
 * 最要紧的一条是**圆表不许变差**：圆表 466px 时
 * `gridStepForScreen(upp, 466f)` 必须与引擎原来的 `Viewport.gridStep`
 * **逐位相同**（原来就是"每格约 60px"，而 60px 正是"短边的 60/466"）。
 * 下面第一条测试直接把两个实现拿来对拍 —— 对拍比"我看着差不多"可靠得多。
 *
 * 全部离线可跑：`PlotMetrics.kt` 只 import `kotlin.math`，
 * 没有任何 Android/Compose 依赖（由 `tools/run-tool-tests.ps1` 编译运行）。
 *
 * 本文件末尾那一组（三、列表行左滑删除的尺度）是同一个文件里的第三类纯算术：
 * 它钉的是用户报的"除了函数之外的对象在列表都不能滑动删除"那个 bug 里**可离线测的一半**
 * —— 够不够远、方向对不对、"被扔掉"那一下怎么随进度走。可测的另一半见
 * `PlotMetrics.SWIPE_DELETE_*` 上面那段说明。
 *
 * 最后一组（四、滑动条行的删除起手区）是同一个道理的第四次应用：用户要求
 * "滑动条行也要能左滑删除"，而这条行的横向拖动被"拖参数值"占着。划分本身是
 * **运行期**的事（谁消费了 down，见 `PlotMetrics` 末尾那一节），但"播放键那一格在哪"
 * 和三块屏上"删除起手区到底多宽"是纯算术 —— 太窄点不中、太宽就会吃掉滑轨的地盘，
 * 所以它必须离线钉住。
 */
class PlotMetricsTest {

    /** 造一个和引擎同参数的视口，用来对拍引擎自己算的 `gridStep`。 */
    private fun engineStep(unitsPerPixel: Double) =
        Viewport(widthPx = 466f, heightPx = 466f, unitsPerPixel = unitsPerPixel).gridStep

    /** 一张跨 12 个数量级的 unitsPerPixel 表（缩放能到的范围，见 Viewport 的上下限）。 */
    private val uppSamples: List<Double> = buildList {
        var e = -4.0
        while (e <= 4.0) {
            add(10.0.pow(e))
            add(1.7 * 10.0.pow(e))
            add(6.3 * 10.0.pow(e))
            e += 1.0
        }
    }

    /** 某个屏幕短边、某个缩放档位下，屏幕上有多少格。 */
    private fun cellsAcross(upp: Double, sizePx: Float): Double =
        sizePx / (gridStepForScreen(upp, sizePx) / upp)

    // ---- 一、圆表观感**一个像素都不许变**（这是本轮改动的底线） ----

    @Test
    fun `短边不大于 466px 的屏（含所有手表与方表）步长与引擎原来的 gridStep 逐位相同`() {
        // 目标格距 = max(60px, 短边 × 60/466)，短边 ≤ 466 时恒为 60px，
        // 于是取的还是引擎原来那句 `unitsPerPixel * 60.0` → **逐位相同**。
        // 测试同时覆盖"同一块 466px 的屏报不同 density"这类手表（它们只影响 dp，不影响 px）。
        var checked = 0
        for (sizePx in listOf(233f, 320f, 400f, 466f)) {
            for (upp in uppSamples) {
                assertEquals(
                    "短边=$sizePx upp=$upp 时必须与引擎一致",
                    engineStep(upp),
                    gridStepForScreen(upp, sizePx),
                    0.0
                )
                checked++
            }
        }
        assertTrue("样本要够多才有说服力：$checked", checked >= 100)
    }

    @Test
    fun `圆表 466px 上一格就是 60px`() {
        // "每格约 60px"是引擎原来的口径；60px 也正好是"短边的 60/466"
        val upp = 1.0 / 120.0 // 一个像素 1/120 世界单位 → 60px 正好是 0.5 世界单位
        val step = gridStepForScreen(upp, WATCH_BASE_PX)
        assertEquals(0.5, step, 1e-12)
        assertEquals(60f, (step / upp).toFloat(), 1e-3f)
        assertEquals(60f, MIN_GRID_PX, 0f)
        assertEquals(466f, WATCH_BASE_PX, 0f)
    }

    // ---- 二、目标格距随短边等比放宽（这才是"按逻辑长度而不是 px"的落地） ----

    @Test
    fun `目标格距是短边按 60 比 466 等比放大，且不小于 60px`() {
        assertEquals(60f, gridTargetPxFor(466f), 1e-4f)
        assertEquals(60f, gridTargetPxFor(400f), 1e-4f)  // 小屏保持下限
        assertEquals(60f, gridTargetPxFor(100f), 1e-4f)
        assertEquals(120f, gridTargetPxFor(932f), 1e-3f) // 两倍短边 → 两倍格距
        assertEquals(60f * 1080f / 466f, gridTargetPxFor(1080f), 1e-3f)
        // 非法输入退回下限（每帧都会被调到，不能抛）
        assertEquals(60f, gridTargetPxFor(Float.NaN), 0f)
        assertEquals(60f, gridTargetPxFor(0f), 0f)
        assertEquals(60f, gridTargetPxFor(-10f), 0f)
    }

    @Test
    fun `手机 1080px 上格子比圆表宽一倍以上（不再是死钉的 60px）`() {
        // upp = 1/60：圆表目标 60px → 格距 1.0 世界单位 = 60px（7.8 格）
        //             手机目标 139.06px → 格距 2.0 世界单位 = 120px（9 格）
        assertEquals(60.0, gridStepForScreen(1.0 / 60.0, 466f) / (1.0 / 60.0), 1e-6)
        assertEquals(120.0, gridStepForScreen(1.0 / 60.0, 1080f) / (1.0 / 60.0), 1e-6)
    }

    @Test
    fun `短边不小于 466px 时，一屏的格数都落在和圆表同一档区间里`() {
        // 这条是"任何屏幕疏密一致"的定量说法：格距与目标格距的比值只由 1-2-5 取整决定
        // （0.571~1.43 倍，与屏幕大小无关），而"短边 / 目标格距"恒等于 466/60，
        // 于是"一屏多少格"这个数**在所有大屏上落在同一个区间**：
        // 7.7667 / 1.43 ≈ 5.4，7.7667 / 0.5714 ≈ 13.6。
        // 对照改动前的手机：upp=1/60 时是 18 格（圆表 7.8 格）—— 手机上密了一倍多。
        var worstLow = Double.MAX_VALUE
        var worstHigh = 0.0
        for (sizePx in listOf(466f, 600f, 800f, 932f, 1080f, 1440f, 1600f)) {
            for (upp in uppSamples) {
                val n = cellsAcross(upp, sizePx)
                assertTrue("短边=$sizePx upp=$upp 格数=$n 太密", n >= 5.4)
                assertTrue("短边=$sizePx upp=$upp 格数=$n 太疏", n <= 13.6)
                worstLow = minOf(worstLow, n)
                worstHigh = maxOf(worstHigh, n)
            }
        }
        assertTrue("取整的偏差要真的出现过（否则这条测试没测到东西）", worstHigh / worstLow > 1.4)
    }

    @Test
    fun `手机上绝不会比改动前更密，平均明显更疏`() {
        // 旧口径就是引擎那 60px（圆表那一列），新口径的目标格距恒 ≥ 60px，
        // 而 niceStep 单调不减 → **逐样本**保证"格子不会变小"。
        // 这是改动最硬的一条安全性：手机上只会变疏、绝不会变得更密。
        var ratioSum = 0.0
        var better = 0
        for (upp in uppSamples) {
            val oldCell = engineStep(upp) / upp
            val newCell = gridStepForScreen(upp, 1080f) / upp
            assertTrue("upp=$upp 手机上比改动前更密了：$oldCell → $newCell px", newCell >= oldCell - 1e-9)
            ratioSum += newCell / oldCell
            if (newCell > oldCell + 1e-9) better++
        }
        val mean = ratioSum / uppSamples.size
        assertTrue("手机上平均格子应该明显变大，实际平均倍数 $mean", mean > 1.3)
        assertTrue("至少要有一半样本真的变宽，实际 $better/${uppSamples.size}", better * 2 >= uppSamples.size)
        // ⚠️ 注意**不能**断言"手机上的格数永远 ≤ 圆表"：1-2-5 取整有 ±43% 的抖动，
        // 极端档位（如 upp=1e-4）下圆表把 0.6 向上取成 5 档、手机把 1.39 向下取成 1 档，
        // 手机上反而会多出一格（9.3 → 10.8 格）。所以"疏密一致"只能按**区间**说
        // （见上一条测试的 5.4 ~ 13.6 格），不能按逐点比较说。
    }

    @Test
    fun `屏越大格子只会更大、绝不变小（像素单调不减）`() {
        for (upp in uppSamples) {
            var prev = 0.0
            var size = 200f
            while (size <= 2400f) {
                val px = gridStepForScreen(upp, size) / upp
                assertTrue("upp=$upp 短边=$size 时格子反而变小了（$prev → $px）", px >= prev - 1e-9)
                prev = px
                size += 25f
            }
        }
    }

    // ---- 三、缩放时"跳档自然"（1-2-5） ----

    @Test
    fun `取整只会给出 1 2 5 三个档位`() {
        assertEquals(1.0, niceStep(1.0), 1e-12)
        assertEquals(1.0, niceStep(1.5), 1e-12)
        assertEquals(2.0, niceStep(1.6), 1e-12)
        assertEquals(2.0, niceStep(3.5), 1e-12)
        assertEquals(5.0, niceStep(3.6), 1e-12)
        assertEquals(5.0, niceStep(7.5), 1e-12)
        assertEquals(10.0, niceStep(7.6), 1e-12)
        assertEquals(0.2, niceStep(0.16), 1e-12)
        assertEquals(0.5, niceStep(0.4), 1e-12)
    }

    @Test
    fun `连续缩放时步长单调不减而且在跳档处只乘 2 或 2_5 或 5`() {
        // 用户缩放时步长是"跳档"的（1 → 2 → 5 → 10）。跳档要么是这几个干净的倍数，
        // 要么是"档位没变"，绝不能出现 1.37 这种拖泥带水的比例 —— 那看起来像抖动。
        var prev = niceStep(1.0)
        var t = 1.0
        while (t < 1.0e6) {
            val step = niceStep(t)
            assertTrue("步长必须单调不减：$prev → $step (target=$t)", step >= prev - 1e-12)
            if (step > prev) {
                val ratio = step / prev
                val ok = listOf(2.0, 2.5, 5.0).any { kotlin.math.abs(ratio - it) < 1e-9 }
                assertTrue("跳档比例只能是 2 / 2.5 / 5，实际 $ratio (target=$t)", ok)
            }
            prev = step
            t *= 1.01
        }
    }

    // ---- 四、刻度数字贴边（用户："数字移动到屏幕外之后会消失"） ----

    @Test
    fun `放得下的锚点原样不动`() {
        assertEquals(100f, snapLabelAnchor(100f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        assertEquals(20f, snapLabelAnchor(20f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
    }

    @Test
    fun `会被裁掉时贴到屏幕边缘停住`() {
        // 左边那一头：锚点在屏外一点点 → 数字被推到"刚好完整可见"的位置
        assertEquals(20f, snapLabelAnchor(5f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        assertEquals(20f, snapLabelAnchor(0f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        // 右边那一头同理
        assertEquals(280f, snapLabelAnchor(299f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        assertEquals(280f, snapLabelAnchor(300f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
    }

    @Test
    fun `挪动太多就宁可不画（刻度本来就在屏外很远）`() {
        assertNull(snapLabelAnchor(-400f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        assertNull(snapLabelAnchor(700f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        // 边界：正好挪允许的距离 → 画；超一点点 → 不画
        assertEquals(20f, snapLabelAnchor(-2f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
        assertNull(snapLabelAnchor(-3f, halfExtent = 20f, lo = 0f, hi = 300f, maxShift = 22f))
    }

    @Test
    fun `文字比可用区间还宽就不画`() {
        assertNull(snapLabelAnchor(150f, halfExtent = 200f, lo = 0f, hi = 300f, maxShift = 22f))
    }

    @Test
    fun `视口滑动时贴边位置实时跟着变`() {
        // 模拟"滑动屏幕"：刻度在屏幕上的位置一路往左走，数字应当先贴住左边缘、
        // 超过允许的跟随距离之后才不再画（不是突然整排消失）。
        val lo = 12f
        val hi = 454f
        val half = 18f
        val budget = labelSnapBudgetPx(safeInsetPx = 12f, halfExtentPx = half, sizePx = 466f, densityScale = 2f)
        val snapped = listOf(200f, 30f, 12f, 0f, -6f, -12f)
            .map { snapLabelAnchor(it, half, lo, hi, maxShift = budget) }
        assertEquals(200f, snapped[0]!!, 0f)
        assertEquals(30f, snapped[1]!!, 0f)
        // lo + half = 30 → 从 30 开始就一直贴在同一条边上（不再随刻度往左跑）
        assertEquals(30f, snapped[2]!!, 0f)
        assertEquals(30f, snapped[3]!!, 0f)
        assertEquals(30f, snapped[4]!!, 0f)
        assertEquals(30f, snapped[5]!!, 0f)
        assertNotNull(snapped[5])
    }

    // ---- 五、贴边**预算**（真因：旧的 22dp 预算永远不够，等于没实装） ----

    /**
     * 复刻 `drawCartesianLabels` 里那几行判定：一个屏幕坐标的刻度能不能画出数字、画在哪。
     *
     * @param bandHalf 可用半宽（相对屏幕水平中心）。**方屏/手机**给 `w / 2f`
     *   （`bandHalfWidth` 在 `isRound = false` 时就是这么返回的）；**圆屏**给该高度上的
     *   `circleHalfWidthAt(…) - safeInset`。
     */
    private fun tickLabelCentre(
        tickX: Float,
        halfW: Float,
        w: Float,
        safeInset: Float,
        sizePx: Float,
        densityScale: Float,
        bandHalf: Float
    ): Float? {
        val xLo = maxOf(safeInset, w / 2f - bandHalf)
        val xHi = minOf(w - safeInset, w / 2f + bandHalf)
        val budget = labelSnapBudgetPx(safeInset, halfW, sizePx, densityScale)
        return snapLabelAnchor(tickX, halfW, xLo, xHi, budget)
    }

    /** 旧口径的预算：`22dp × density`（本轮之前就是这么算的）。 */
    private fun oldBudget(densityScale: Float): Float = LABEL_MIN_SNAP_DP * densityScale

    @Test
    fun `预算按几何算：永远够把屏幕边缘那一个刻度挪进来`() {
        // 不变式：预算 ≥ safeInset + 2 × 半宽（刻度正好落在屏幕边缘时所需的距离）
        val cases = listOf(
            // 圆表 466px / density 2
            floatArrayOf(466f, 466f * 0.06f, 2f),
            // 圆表 466px / density **1**（有些表就这么报，见 WatchMetrics）
            floatArrayOf(466f, 466f * 0.06f, 1f),
            // 方表 400px / density 2
            floatArrayOf(400f, 400f * 0.06f, 2f),
            // 手机 1080px / density 3
            floatArrayOf(1080f, 1080f * 0.06f, 3f)
        )
        for (c in cases) {
            val sizePx = c[0]
            val safeInset = c[1]
            val density = c[2]
            for (halfW in listOf(5f, 11f, 14f, 22f, 40f)) {
                val budget = labelSnapBudgetPx(safeInset, halfW, sizePx, density)
                assertTrue(
                    "短边=$sizePx density=$density 半宽=$halfW：预算 $budget 不够 " +
                        "(需要 ${safeInset + 2 * halfW})",
                    budget >= safeInset + 2f * halfW
                )
                // 而且真的能画出来：刻度正好落在屏幕右缘
                val centre = tickLabelCentre(
                    tickX = sizePx, halfW = halfW, w = sizePx,
                    safeInset = safeInset, sizePx = sizePx, densityScale = density,
                    bandHalf = sizePx / 2f
                )
                assertNotNull("短边=$sizePx density=$density 半宽=$halfW 的右缘刻度必须贴住边缘", centre)
                // 贴住之后文字盒的右沿正好落在"屏宽 − safeInset"上（这就是"贴住屏幕边缘"）
                assertEquals(sizePx - safeInset, centre!! + halfW, 0.01f)
            }
        }
    }

    @Test
    fun `手机 1080px 上旧预算（22dp×3=66px）根本挪不动边缘那个数字`() {
        // 这一条是用户报"贴边没实装"的**主因**的定量复现：手机上需要挪 109px，
        // 旧预算只有 66px，于是 `snapLabelAnchor` 返回 null → 数字照样消失。
        val w = 1080f
        val safeInset = w * 0.06f      // 64.8px
        val halfW = 22f                // `-100` 这种三字符数字的一半
        val xLo = safeInset
        val xHi = w - safeInset
        // 旧口径：挪不动
        assertNull(snapLabelAnchor(w, halfW, xLo, xHi, oldBudget(3f)))
        // 新口径：贴住右缘（中心落在 xHi − halfW 上）
        val centre = tickLabelCentre(w, halfW, w, safeInset, w, 3f, bandHalf = w / 2f)
        assertNotNull(centre)
        assertEquals(xHi - halfW, centre!!, 0.01f)
        // 需要挪的距离确实是 safeInset + 2×半宽
        assertEquals(64.8f + 44f, (w + halfW) - centre, 0.05f)
    }

    @Test
    fun `圆表报 density 1 时贴边也必须生效（旧预算只有 22px）`() {
        // `WatchMetrics` 的注释里写着：同一块 466×466 的屏，有的设备报 density 2、有的报 1。
        // 预算以前是 `22dp × density`，在报 1 的表上只剩 22px —— 比"最少需要挪 39px"还小，
        // 所以那类表上贴边**一个都贴不上**（用户看到的就是"根本没做"）。
        val w = 466f
        val safeInset = w * 0.06f      // 28px
        val halfW = 5.5f               // `5` 这种窄数字
        assertNull(snapLabelAnchor(w, halfW, safeInset, w - safeInset, oldBudget(1f)))
        val centre = tickLabelCentre(w, halfW, w, safeInset, w, 1f, bandHalf = w / 2f)
        assertNotNull(centre)
        assertEquals(w - safeInset, centre!! + halfW, 0.01f)
    }

    @Test
    fun `贴边是有界的：已经跑到屏外很远的刻度照样不画`() {
        // 用户要的是"最多跟随移动到屏幕边缘"，不是"把屏外的刻度拖到屏幕中间"。
        val w = 1080f
        val safeInset = w * 0.06f
        val halfW = 22f
        val band = w / 2f
        // 贴着自己的右缘 → 画
        assertNotNull(tickLabelCentre(w, halfW, w, safeInset, w, 3f, band))
        // 再往右跑 200px（早就出屏了）→ 不画
        assertNull(tickLabelCentre(w + 200f, halfW, w, safeInset, w, 3f, band))
        // 边界：正好用掉整个预算 → 画；再多一个像素 → 不画
        val budget = labelSnapBudgetPx(safeInset, halfW, w, 3f)
        val edgeX = (w - safeInset - halfW) + budget - halfW + 0.5f
        assertNotNull(tickLabelCentre(edgeX, halfW, w, safeInset, w, 3f, band))
        assertNull(tickLabelCentre(edgeX + 60f, halfW, w, safeInset, w, 3f, band))
    }

    @Test
    fun `圆表底部那一行：圆内可见的刻度贴住可用区边缘，圆外的不画`() {
        // 圆表底部的可用宽度很窄（弦宽），一条刻度只要还落在**表盘可见范围**里，
        // 它的数字就该贴住可用区边缘；而在表盘外（画布矩形里有、圆上没有）的不画 ——
        // 那正是"x 轴滑出屏幕时下面那排数字不该整排消失"的分寸。
        val w = 466f
        val safeInset = w * 0.06f
        // 行高 ~430px（贴底那一行）：圆在该高度上的半宽 ≈ 110px
        val bandHalf = 110f - safeInset   // ≈ 82px（bandHalfWidth 的算法）
        val halfW = 5.5f
        // 表盘可见范围内的一个刻度（x ≈ 340，圆右界 ≈ 233 + 110 = 343）
        val inside = tickLabelCentre(340f, halfW, w, safeInset, w, 2f, bandHalf)
        assertNotNull("表盘内可见的刻度必须能贴边画出", inside)
        assertEquals(w / 2f + bandHalf - halfW, inside!!, 0.01f)
        // 同样的高度，x = 460 已经在表盘外面（那儿根本没有刻度线）
        assertNull(tickLabelCentre(460f, halfW, w, safeInset, w, 2f, bandHalf))
        // 行高在竖直中央时可用宽度接近整个屏宽（圆表中间最宽）：
        // 这时同一条刻度就能贴到"屏宽 − safeInset"上了
        val midBand = w / 2f - safeInset
        val mid = tickLabelCentre(460f, halfW, w, safeInset, w, 2f, midBand)
        assertNotNull(mid)
        assertEquals(w - safeInset, mid!! + halfW, 0.01f)
    }

    @Test
    fun `非圆屏的可用区间是整个屏宽（不许套圆屏弦宽公式）`() {
        // 真因之二：`bandHalfWidth` 以前对所有屏都按圆屏弦宽算，而画布是 fillMaxSize ——
        // 手机 1080×2400 上"轴滑出屏幕、数字那一行贴底"时，按圆算出来的可用宽度只有
        // 屏中央的 ±110px，数字于是永远贴不到屏幕侧边。
        // 现在方屏/手机走 `w / 2f`（调用方再收一次 safeInset），也就是"整个屏宽"。
        val w = 1080f
        val safeInset = w * 0.06f
        val bandHalf = w / 2f
        val xLo = maxOf(safeInset, w / 2f - bandHalf)
        val xHi = minOf(w - safeInset, w / 2f + bandHalf)
        assertEquals("可用区间左界就是安全内缩", safeInset, xLo, 0f)
        assertEquals("可用区间右界就是屏宽减去安全内缩", w - safeInset, xHi, 0f)
        // 反面对照：如果还按"圆屏贴底"的弦宽算，可用区间只剩中间那一小段
        val circleBand = 110f - safeInset
        val circleLo = maxOf(safeInset, w / 2f - circleBand)
        val circleHi = minOf(w - safeInset, w / 2f + circleBand)
        assertTrue("圆屏公式下可用宽度只有 ${circleHi - circleLo}px", circleHi - circleLo < 300f)
        assertTrue("矩形口径下可用宽度是 ${xHi - xLo}px", xHi - xLo > 900f)
    }

    @Test
    fun `预算的兜底下限与富余都用得上`() {
        // 富余那一项按**屏幕短边**缩放（1%）：和网格格距同一条思路 —— 不看 density，
        // 因为同一块 466px 的屏有的设备报 density 2、有的报 1（见 WatchMetrics）。
        assertEquals(4.66f, 466f * LABEL_SNAP_SLACK_FRACTION, 0.01f)
        // 短边很小、文字很窄时，几何那一项会小于 22dp 的下限 → 用下限
        assertEquals(
            LABEL_MIN_SNAP_DP * 2f,
            labelSnapBudgetPx(safeInsetPx = 1f, halfExtentPx = 1f, sizePx = 10f, densityScale = 2f),
            0.01f
        )
        // 非法输入 → 0（等于"不许挪"，调用方退回"放得下就画"的老行为，绝不崩）
        assertEquals(0f, labelSnapBudgetPx(Float.NaN, 10f, 466f, 2f), 0f)
        assertEquals(0f, labelSnapBudgetPx(-1f, 10f, 466f, 2f), 0f)
        assertEquals(0f, labelSnapBudgetPx(28f, Float.NaN, 466f, 2f), 0f)
        assertEquals(0f, labelSnapBudgetPx(28f, -1f, 466f, 2f), 0f)
        // density 非法时下限不生效，但几何那一项仍然给得出预算（不能因此一条都不画）
        assertTrue(labelSnapBudgetPx(28f, 14f, 466f, Float.NaN) > 0f)
    }

    @Test
    fun `非法输入不抛异常（这个函数每帧都会被调到）`() {
        assertEquals(0.0, gridStepForScreen(0.0, 1080f), 0.0)
        assertEquals(0.0, gridStepForScreen(-1.0, 1080f), 0.0)
        assertEquals(0.0, gridStepForScreen(Double.NaN, 1080f), 0.0)
        assertEquals(0.0, gridStepForScreen(Double.POSITIVE_INFINITY, 1080f), 0.0)
        // 屏幕尺寸非法时退回 60px 下限，仍然给出一个可用的步长
        assertTrue(gridStepForScreen(1.0 / 60.0, 0f) > 0.0)
        assertTrue(gridStepForScreen(1.0 / 60.0, Float.NaN) > 0.0)
        // 非法 target 原样返回（调用方自己判断），不抛
        assertEquals(0.0, niceStep(0.0), 0.0)
        assertTrue(niceStep(Double.NaN).isNaN())
        assertNull(snapLabelAnchor(Float.NaN, 20f, 0f, 300f, 22f))
        assertNull(snapLabelAnchor(10f, 20f, 300f, 0f, 22f))
        assertNull(snapLabelAnchor(10f, -1f, 0f, 300f, 22f))
    }

    // ---- 二·补、贴边判定的**稳定性** + x 行的**防回归**（本轮新增） ----
    //
    // 用户报："刻度数字贴边效果里，纵坐标的数字在移动的时候会抽搐，横坐标的数字直接显示不出来了。"
    // 离线把这两条复现出来之后，这里钉三件事：
    //   ① 连续/抖动的视口下，判定不许出现"贴边 ↔ 不贴边"的**反复跳变**（纵轴抽搐的根）；
    //   ② x 行在"轴在屏内 / 轴滑出屏幕下方 / 轴移出屏幕上方"三种情形下都**至少画出一个刻度**
    //      （整排消失的根：整行只夹一次 + 夹与闸差 1 个 ULP）；
    //   ③ 夹出来的位置**一定**过得了"完整可见"那道严格闸（含浮点零头）。

    /**
     * `WatchMetrics.circleWidthFraction` 的**离线镜像**（测试包 import 不了 Compose，
     * 所以按同一条公式重写：半径为 `w/2`、圆心在屏幕中心的内切圆在高度 y 处的弦半宽）。
     */
    private fun chordHalfAt(y: Float, w: Float, h: Float): Float {
        val f = (y / h).coerceIn(0f, 1f)
        val d = ((f - 0.5f) / 0.5f).coerceIn(-1f, 1f)
        val inside = 1f - d * d
        return if (inside <= 0f) 0f else w / 2f * sqrt(inside)
    }

    /** 离线近似"实测字宽"：等宽数字 0.556em、负号 0.333em（原生 Paint 量出来的量级一致）。 */
    private fun textWidthPx(text: String, textPx: Float): Float {
        var em = 0f
        for (c in text) em += if (c == '-') 0.333f else 0.556f
        return em * textPx
    }

    /** `Common.formatNumber` 在整数刻度上的结果（离线镜像，只覆盖测试里用到的那几种值）。 */
    private fun tickText(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    /**
     * 镜像 `drawCartesianLabels` 的 **x 行**（网格步长之后的每一步），返回这一帧画出去的锚点。
     *
     * 链上的四段都是**真函数/真算式**：粗保护（`MIN_LABEL_SPACING_DP`）、行级纵向夹
     * [pinLabelIntoBand]、每格横向判定 [pinLabelNearAxis]、以及"完整可见"闸
     * （按 `fitsRoundScreen` 逐字重写，**故意不留它那 0.5px 余量**：这里用最严格的判法，
     * 能过就说明夹取自己那 0.5px 富余起了作用）。只有"逐个刻度"的循环是这里重写的。
     *
     * @param axisY x 轴在画布上的高度：`< 0` = 轴移出屏幕上方，`> h` = 轴滑出屏幕下方
     *   （这两种情况绘制侧都走"把这一行顶到下面那条安全线上"那一支）。
     * @return 画出去的锚点（空 = 这一帧一个刻度都没画出来）。
     */
    private fun xRowDrawn(
        w: Float,
        h: Float,
        sizePx: Float,
        densityScale: Float,
        axisY: Float,
        upp: Double,
        centerX: Double,
        isRound: Boolean
    ): List<Float> {
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val step = gridStepForScreen(upp, sizePx)
        if (!step.isFinite() || step <= 0.0) return emptyList()
        // 行级兜底（**真函数**：一个字高，正常参数下不可能触发）——绘制侧在判定链之前调它
        val spacingPx = (step / upp).toFloat()
        if (labelsTooDenseForRow(spacingPx, sizePx)) return emptyList()

        val halfHeight = textPx * 0.55f
        val centerOffset = textPx * 0.30f
        val quantum = labelFollowQuantumPx(sizePx)
        val gapPx = LABEL_MIN_GAP_DP * densityScale
        val lo = safeInset + textPx
        val hi = h - safeInset
        val base = if (axisY >= 0f && axisY <= h) axisY + textPx * 1.15f else h - safeInset
        val wanted = if (lo <= hi) base.coerceIn(lo, hi) else h / 2f
        val rowCentre = pinLabelIntoBand(wanted - centerOffset, halfHeight, safeInset, hi)
            ?: return emptyList()
        val baseline = rowCentre + centerOffset
        val boxTop = baseline - textPx * 0.85f
        val boxBottom = baseline + textPx * 0.25f
        val band = if (!isRound) {
            w / 2f
        } else {
            minOf(chordHalfAt(boxTop, w, h), chordHalfAt(boxBottom, w, h)) - safeInset
        }
        val xLoNatural = maxOf(safeInset, w / 2f - band)
        val xHiNatural = minOf(w - safeInset, w / 2f + band)
        // 角锚点范围（和绘制侧同一对真函数）：x 行从角锚点向右量一段臂长，再与可见边界取交
        val corner = labelCornerAnchorPx(w, h, safeInset, isRound)
        val span = labelRangeSpanPx(w, h, safeInset)
        val xRange = xRowRangeFromCorner(xLoNatural, xHiNatural, corner.xPx, span)
            ?: return emptyList()
        val xLo = xRange.loPx
        val xHi = xRange.hiPx

        val out = ArrayList<Float>()
        var lastRight = Float.NEGATIVE_INFINITY
        var v = ceil((centerX - w / 2.0 * upp) / step) * step
        val worldRight = centerX + w / 2.0 * upp
        var guard = 0
        while (v <= worldRight && guard++ < 512) {
            if (abs(v) > step * 1e-6) {
                val text = tickText(v)
                val halfW = textWidthPx(text, textPx) / 2f
                val tickX = ((v - centerX) / upp).toFloat() + w / 2f
                val anchor = pinLabelNearAxis(
                    labelAnchorPx = tickX,
                    axisAnchorPx = tickX,
                    halfExtentPx = halfW,
                    loPx = xLo,
                    hiPx = xHi,
                    visibleLoPx = xLo,
                    visibleHiPx = xHi,
                    quantumPx = quantum,
                    densityScale = densityScale,
                    wasDrawn = false,
                    hysteresisPx = quantum
                )
                if (anchor != null && anchor - halfW - lastRight >= gapPx) {
                    // 完整可见闸（严格版：`fitsRoundScreen` 不留那 0.5px，这里用最狠的判法）
                    val roundOk = !isRound || (
                        (w / 2f - (anchor - halfW)) <= band &&
                            ((anchor + halfW) - w / 2f) <= band
                        )
                    val ok = anchor - halfW >= safeInset && anchor + halfW <= w - safeInset &&
                        boxTop >= safeInset && boxBottom <= hi && roundOk
                    if (ok) {
                        lastRight = anchor + halfW
                        out.add(anchor)
                    }
                }
            }
            v += step
        }
        return out
    }

    /**
     * 镜像 `drawCartesianLabels` 的 **y 列**（纵向那一半），返回这一帧画出去的**文字中心 y**。
     *
     * 与 [xRowDrawn] 同一套口径：行级兜底 / 角锚点范围 / 逐格判定 / 完整可见闸全是真函数
     * 或逐字重写，只有"逐个刻度"的循环是重写的。横向那一夹也用真函数 `pinLabelIntoBand`
     * （纵轴数字骑在轴左边；`centerX` 决定轴在哪，从而决定要不要贴到屏幕侧边）。
     *
     * @param centerY 视口中心的世界 y（默认视野传 0）
     * @param centerX 视口中心的世界 x（默认视野传 0 = 轴在屏幕正中）
     */
    private fun yColumnDrawn(
        w: Float,
        h: Float,
        sizePx: Float,
        densityScale: Float,
        upp: Double,
        centerX: Double,
        centerY: Double,
        isRound: Boolean
    ): List<Float> {
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val step = gridStepForScreen(upp, sizePx)
        if (!step.isFinite() || step <= 0.0) return emptyList()
        if (labelsTooDenseForRow((step / upp).toFloat(), sizePx)) return emptyList()

        val halfHeight = textPx * 0.55f
        val centerOffset = textPx * 0.30f
        val quantum = labelFollowQuantumPx(sizePx)
        val gapPx = LABEL_MIN_GAP_DP * densityScale
        val corner = labelCornerAnchorPx(w, h, safeInset, isRound)
        val span = labelRangeSpanPx(w, h, safeInset)
        val yRange = yColumnRangeFromCorner(safeInset, h - safeInset, corner.yPx, span)
            ?: return emptyList()
        val axisX = ((0.0 - centerX) / upp).toFloat() + w / 2f
        val labelX = axisX - textPx * 0.5f

        val out = ArrayList<Float>()
        var lastTop = Float.POSITIVE_INFINITY
        var v = ceil((centerY - h / 2.0 * upp) / step) * step
        val worldTop = centerY + h / 2.0 * upp
        var guard = 0
        while (v <= worldTop && guard++ < 512) {
            if (abs(v) > step * 1e-6) {
                val text = tickText(v)
                val tw = textWidthPx(text, textPx)
                val tickY = h / 2f - ((v - centerY) / upp).toFloat()
                val cy = pinLabelNearAxis(
                    labelAnchorPx = tickY + textPx * 0.36f - centerOffset,
                    axisAnchorPx = tickY,
                    halfExtentPx = halfHeight,
                    loPx = yRange.loPx,
                    hiPx = yRange.hiPx,
                    visibleLoPx = yRange.loPx,
                    visibleHiPx = yRange.hiPx,
                    quantumPx = quantum,
                    densityScale = densityScale,
                    wasDrawn = false,
                    hysteresisPx = quantum
                )
                if (cy != null) {
                    val top = cy - halfHeight
                    val bottom = cy + halfHeight
                    val band = if (!isRound) w / 2f else roundBandHalf(w, h, top, bottom, safeInset)
                    val yLoB = maxOf(safeInset, w / 2f - band)
                    val yHiB = minOf(w - safeInset, w / 2f + band)
                    val cx = pinLabelIntoBand(labelX - tw / 2f, tw / 2f, yLoB, yHiB)
                    if (cx != null && lastTop - bottom >= gapPx) {
                        val boxL = (cx + tw / 2f) - tw
                        val boxR = cx + tw / 2f
                        val boxT = (cy + centerOffset) - textPx * 0.85f
                        val boxB = (cy + centerOffset) + textPx * 0.25f
                        val roundOk = !isRound || ((w / 2f - boxL) <= band && (boxR - w / 2f) <= band)
                        val ok = boxL >= safeInset && boxR <= w - safeInset &&
                            boxT >= safeInset && boxB <= h - safeInset && roundOk
                        if (ok) {
                            lastTop = top
                            out.add(cy + centerOffset)
                        }
                    }
                }
            }
            v += step
        }
        return out
    }

    /** 三种屏幕几何：圆表（466 圆屏 / density 2）、方表（400 / density 2）、手机（1080×2400 / density 3）。 */
    private val screens = listOf(
        Triple(466f, 466f, 2f to true),
        Triple(400f, 400f, 2f to false),
        Triple(1080f, 2400f, 3f to false)
    )

    @Test
    fun `x 行在轴在屏内、轴滑出屏幕、轴移出屏幕三种情形下都至少画出一个刻度`() {
        // 这一条是**防回归的关键断言**：上一版在"轴滑出屏幕"时整排一个都不画 ——
        // 那一行是**整行夹一次**的，夹出来的下沿恰好落在 `h − 安全内缩` 上，而文字盒下沿
        // 是三次浮点加算出来的，末位比边界大 1 个 ULP（圆表实测 3.1e-5px），
        // `fitsRoundScreen` 的严格比较于是把整排判掉（纵轴一格一格夹，所以只丢最边上一个
        // —— 这就是"只有横坐标不显示"的来历）。
        for ((w, h, rest) in screens) {
            val (density, isRound) = rest
            val sizePx = minOf(w, h)
            val upp = 20.0 / w   // 默认视野（DEFAULT_HALF_RANGE = 10）
            // 三种轴位置：屏内（正中间）／滑出屏幕下方／移出屏幕上方
            val states = listOf(
                "轴在屏内" to h / 2f,
                "轴滑出屏幕下方" to h + 60f,
                "轴移出屏幕上方" to -60f
            )
            for ((name, axisY) in states) {
                val drawn = xRowDrawn(w, h, sizePx, density, axisY, upp, 0.0, isRound)
                assertTrue(
                    "${name}（${w}x$h density=$density isRound=$isRound）：一个刻度都没画出来 —— " +
                        "这正是用户报的\"横坐标的数字直接显示不出来\"",
                    drawn.isNotEmpty()
                )
                // 而且画出来的每一个都真的完整可见（在可用区里、在圆内）
                for (a in drawn) {
                    assertTrue("锚点 $a 落在可用区外", a >= 0f && a <= w)
                }
            }
        }
    }

    @Test
    fun `行级夹取出来的位置一定过得了完整可见闸（整排消失的真因）`() {
        // 真因复算：文字盒下沿 = `(夹出来的中心 + 中心偏移) + 0.25 字号`（三次浮点加），
        // 而夹取用的是 `边界 − 半高` —— 两边的末位能差 1 个 ULP。圆表 466×466 / density 2 上
        // 实测差 **3.1e-5px**：严格比较把那一排整排判掉。现在 `pinLabelIntoBand` 自己
        // 就往可用区里多收 0.5px，于是"夹出来的位置"在数学上不可能被闸掉。
        for ((w, h, rest) in screens) {
            val (_, _unusedDensity) = rest
            val sizePx = minOf(w, h)
            val safeInset = sizePx * 0.06f
            val textPx = sizePx * 0.042f
            val half = textPx * 0.55f
            val offset = textPx * 0.30f
            val hi = h - safeInset
            // 行被顶到下面那条安全线上（轴滑出屏幕时就是这个状态）
            val centre = pinLabelIntoBand(hi - half, half, safeInset, hi)!!
            val baseline = centre + offset
            val bottom = baseline + textPx * 0.25f
            val top = baseline - textPx * 0.85f
            assertTrue("短边=$sizePx：下沿 $bottom 越过了闸的边界 $hi", bottom <= hi)
            assertTrue("短边=$sizePx：上沿 $top 越过了闸的下界 $safeInset", top >= safeInset)
            // 并且这份"过得了闸"是靠**留出来的富余**保证的，不是靠浮点末位那点运气
            assertTrue(
                "夹取要把富余留够：centre=$centre 应当 ≤ ${hi - half - LABEL_FIT_SLACK_PX}",
                centre <= hi - half - LABEL_FIT_SLACK_PX + 1e-4f
            )
            assertTrue(LABEL_FIT_SLACK_PX > 0f)
        }
    }

    @Test
    fun `圆屏贴底那一行：屏幕边缘的刻度也挪得动（预算按可用区算，不按屏幕边缘算）`() {
        // 抽搐的真因之一：上一版预算是 `安全内缩 + 2×半宽（+1% 短边）`，等于假设
        // "可用区就是整块屏幕"。而圆屏一行数字的可用宽度是该高度上的**弦宽**
        // （贴底那一行只剩屏宽的 ±0.35），于是明明还在屏幕上、还在弦内看得见的刻度，
        // 到可用区边界的距离远大于旧预算 → 被判成"挪不动"丢掉，而且随滚动一直变。
        val sizePx = 466f
        val density = 2f
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val half = textWidthPx("-10", textPx) / 2f     // ≈16px：三字符数字的半宽
        val bandHalf = 110f - safeInset                // `bandHalfWidth` 在贴底那一行的结果
        val xLo = maxOf(safeInset, sizePx / 2f - bandHalf)
        val xHi = minOf(sizePx - safeInset, sizePx / 2f + bandHalf)
        val quantum = labelFollowQuantumPx(sizePx)

        // 刻度正好落在屏幕左缘（网格线就在那儿，肉眼看得见）：数字必须能贴到可用区左界
        val left = pinLabelNearAxis(
            0f, 0f, half, xLo, xHi, 0f, sizePx, quantum, density
        )
        assertNotNull("屏幕左缘的刻度必须能贴边画出", left)
        assertEquals(xLo + half + LABEL_FIT_SLACK_PX, left!!, 0.02f)
        // 右缘同理
        val right = pinLabelNearAxis(
            sizePx, sizePx, half, xLo, xHi, 0f, sizePx, quantum, density
        )
        assertNotNull("屏幕右缘的刻度必须能贴边画出", right)
        assertEquals(xHi - half - LABEL_FIT_SLACK_PX, right!!, 0.02f)

        // 对照：旧口径（预算只按"屏幕边缘"算）在这一行盖不住 —— 边缘那个数字被丢
        val need = (xLo + half) - 0f
        val oldBudget = labelSnapBudgetPx(safeInset, half, sizePx, density)
        assertTrue("旧预算 $oldBudget 应当盖不住 $need", oldBudget < need)
        // 新预算按几何给出，一定盖得住（"刻度还在屏幕上就一定挪得动"）
        val newBudget = labelFollowBudgetPx(0f, 0f, half, xLo, xHi, 0f, sizePx, quantum, density)
        assertTrue("新预算 $newBudget 必须盖得住 $need", newBudget >= need)
    }

    @Test
    fun `纵轴数字贴边时不再被按屏幕算的预算丢掉（抽搐的真因之一）`() {
        // 轴滑出屏幕左边时，纵轴数字要贴到**可用区**左界上；而圆屏靠上下边缘处那条弦
        // 比屏幕窄几十像素。上一版这里套了 `labelSnapBudgetPx`（只按屏幕边缘算）：
        // 需要挪 150+px、预算只有 62px → 判成"挪不动"丢掉。圆表上 27% 的纵轴刻度
        // 就是这么没的，而且随着数字上下滚动"丢掉哪几个"一直在变 —— 用户看到的"抽搐"。
        val sizePx = 466f
        val density = 2f
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val half = textWidthPx("-10", textPx) / 2f
        val bandHalf = 110f - safeInset
        val yLo = maxOf(safeInset, sizePx / 2f - bandHalf)
        val yHi = minOf(sizePx - safeInset, sizePx / 2f + bandHalf)
        // 轴滑出屏幕左边时的锚点（右对齐、骑在轴左边）：中心 = 安全内缩 − 半宽
        val centreWhenAxisOffLeft = safeInset - half
        val cx = pinLabelIntoBand(centreWhenAxisOffLeft, half, yLo, yHi)
        assertNotNull("刻度还在屏幕上 → 数字必须画得出来（贴到可用区左界）", cx)
        assertEquals(yLo + half + LABEL_FIT_SLACK_PX, cx!!, 0.02f)
        // 对照：旧口径下这个位置过不去（收入"挪不动"那一类）
        val oldBudget = labelSnapBudgetPx(safeInset, half, sizePx, density)
        assertNull(
            snapLabelAnchor(centreWhenAxisOffLeft, half, yLo, yHi, oldBudget)
        )
        // 轴滑出屏幕**右边**时贴的是**右边**那条边（不是像上一版那样被贴到左缘瞬移过去）
        val centreWhenAxisOffRight = sizePx + half
        val cx2 = pinLabelIntoBand(centreWhenAxisOffRight, half, yLo, yHi)
        assertEquals(yHi - half - LABEL_FIT_SLACK_PX, cx2!!, 0.02f)
    }

    @Test
    fun `贴边判定在单调平移下最多翻一次，而且位置单调`() {
        // ① 的正面：视口**单调**地把一个刻度推出屏幕 —— 判定只允许翻一次
        // （"贴边 ↔ 不贴边"反复跳变就是用户看到的那种抽）。100 个采样点。
        val sizePx = 466f
        val density = 2f
        val safeInset = sizePx * 0.06f
        val half = 16f
        val quantum = labelFollowQuantumPx(sizePx)
        var drawn = false
        var flips = 0
        var previous: Boolean? = null
        var lastAnchor = Float.NaN
        val positions = ArrayList<Float>()
        for (i in 0 until 100) {
            val tickX = -i * 1.5f            // 从屏内一路推到屏外 150px
            val anchor = pinLabelNearAxis(
                labelAnchorPx = tickX,
                axisAnchorPx = tickX,
                halfExtentPx = half,
                loPx = safeInset,
                hiPx = sizePx - safeInset,
                visibleLoPx = 0f,
                visibleHiPx = sizePx,
                quantumPx = quantum,
                densityScale = density,
                wasDrawn = drawn,
                hysteresisPx = quantum
            )
            drawn = anchor != null
            if (previous != null && previous != drawn) flips++
            previous = drawn
            if (anchor != null) {
                // 位置必须单调（屏幕左向：锚点只会越来越小、贴住之后不再动）
                if (!lastAnchor.isNaN()) {
                    assertTrue("锚点回跳了：$lastAnchor → $anchor（第 $i 个采样点）", anchor <= lastAnchor + 1e-3f)
                }
                lastAnchor = anchor
                positions.add(anchor)
            }
        }
        assertEquals("100 个采样点里判定只允许翻一次", 1, flips)
        assertTrue("被推出屏幕前应当真的画出来过（否则这条测试没测到东西）：${positions.size} 个点", positions.size >= 5)
    }

    @Test
    fun `贴边判定在抖动的视口下不出现反复跳变（旧判定会来回翻）`() {
        // ② 迟滞的价值：视口一边往外走、一边叠一个**小于一个量化步长**的手抖
        // （6px 步长 vs ±2px 抖动）。新判定（两个阈值）只翻一次；旧判定（单阈值、无量化、
        // 无迟滞）在同一串采样点上来回翻 —— 那就是"纵坐标数字抽搐"。
        val sizePx = 466f
        val density = 2f
        val safeInset = sizePx * 0.06f
        val half = 16f
        val quantum = labelFollowQuantumPx(sizePx)
        var drawnNew = false
        var drawnOld = false
        var flipsNew = 0
        var flipsOld = 0
        var prevNew: Boolean? = null
        var prevOld: Boolean? = null
        for (i in 0 until 100) {
            // 0.4px/帧的单调外移：100 个采样点足以同时跨过**新判定的阈值**
            // （需要挪 48px 才丢）和**旧判定的阈值**（旧预算比几何预算大 20 多像素，
            // 所以旧判定丢得更晚 —— 两条阈值不重合正是要对比的地方）
            val sweep = -i * 0.4f
            val dither = if (i % 2 == 0) 2f else -2f     // 手抖：±2px（< 一个步长 6px）
            val tickX = sweep + dither
            // 新判定：带迟滞（`wasDrawn` 就是"上一个绘制帧里它画过没有"）
            drawnNew = pinLabelNearAxis(
                labelAnchorPx = tickX,
                axisAnchorPx = tickX,
                halfExtentPx = half,
                loPx = safeInset,
                hiPx = sizePx - safeInset,
                visibleLoPx = 0f,
                visibleHiPx = sizePx,
                quantumPx = quantum,
                densityScale = density,
                wasDrawn = drawnNew,
                hysteresisPx = quantum
            ) != null
            if (prevNew != null && prevNew != drawnNew) flipsNew++
            prevNew = drawnNew
            // 旧判定 = 上一版绘制侧那一对（单阈值、无量化、无迟滞）
            drawnOld = snapLabelAnchor(
                tickX, half, safeInset, sizePx - safeInset,
                labelSnapBudgetPx(safeInset, half, sizePx, density)
            ) != null
            if (prevOld != null && prevOld != drawnOld) flipsOld++
            prevOld = drawnOld
        }
        assertEquals("新判定在「抖动 + 单调外移」下只允许翻一次", 1, flipsNew)
        assertTrue("旧判定在同一串采样点上来回翻了 $flipsOld 次（这就是抽搐）", flipsOld >= 3)
        assertTrue("量化步长要真的比手抖大（否则迟滞死区盖不住噪声）", quantum > 4f)
    }

    @Test
    fun `量化的三条硬性质：向上取整、非法输入不炸、步长按短边缩放`() {
        // 向上取整：量化后的挪动量**永不小于**真实需要的距离
        // （否则文字会被推到可用区外，再被完整可见闸判掉 = "偶尔又没了一个数字"）
        for (need in listOf(0f, 0.1f, 5.9f, 6f, 6.1f, 41.9f, 42f, 1e4f)) {
            val q = quantizeShiftUpPx(need, 6f)
            assertTrue("量化不许把距离改小：need=$need → $q", q >= need - 1e-4f)
            assertTrue("量化不许超出一步：need=$need → $q", q <= need + 6f + 1e-4f)
            assertEquals("量化后的值必须落在格线上", 0f, q % 6f, 1e-3f)
        }
        // 非法输入：距离非有限 → 正无穷（等于"挪不动"，安全的一侧）；步长非法 → 不量化
        assertEquals(Float.POSITIVE_INFINITY, quantizeShiftUpPx(Float.NaN, 6f), 0f)
        assertEquals(12f, quantizeShiftUpPx(12f, 0f), 0f)
        assertEquals(12f, quantizeShiftUpPx(12f, Float.NaN), 0f)
        assertEquals(0f, quantizeShiftUpPx(0f, Float.NaN), 0f)
        // 步长按**短边**缩放（不是 dp）：466 → 6px、400 → 5.2px、1080 → 13.9px，
        // 而且它始终是"字高的 28%"（字号也是短边的 4.2%），所以各设备手感一致
        assertEquals(466f * LABEL_SHIFT_QUANTUM_FRACTION, labelFollowQuantumPx(466f), 1e-4f)
        assertEquals(6.0f, labelFollowQuantumPx(466f), 0.01f)
        assertEquals(0.28f, labelFollowQuantumPx(466f) / (466f * 0.042f * 1.1f), 0.01f)
        assertEquals(0f, labelFollowQuantumPx(0f), 0f)
        assertEquals(0f, labelFollowQuantumPx(Float.NaN), 0f)
        // 新判定对非法输入不抛异常（每帧都会被调到）
        assertNull(pinLabelIntoBand(Float.NaN, 10f, 0f, 300f))
        assertNull(pinLabelIntoBand(10f, Float.NaN, 0f, 300f))
        assertNull(pinLabelIntoBand(10f, -1f, 0f, 300f))
        assertNull(pinLabelIntoBand(10f, 10f, 300f, 0f))
        assertNull(pinLabelIntoBand(150f, 200f, 0f, 300f))
        assertNull(pinLabelNearAxis(Float.NaN, 0f, 10f, 0f, 300f, 0f, 300f, 6f, 2f))
        assertNull(pinLabelNearAxis(150f, 150f, 200f, 0f, 300f, 0f, 300f, 6f, 2f))
        assertTrue(labelFollowBudgetPx(Float.NaN, 0f, 10f, 0f, 300f, 0f, 300f, 6f, 2f) >= 0f)
        // 可用区连一个文字都放不下 → 预算 0（不许挪），调用方随之不画
        assertEquals(0f, labelFollowBudgetPx(10f, 10f, 200f, 0f, 300f, 0f, 300f, 6f, 2f), 0f)
    }

    // ---- 二·补二、两条轴的**显示范围**（本轮：共用的角锚点） ----
    //
    // 用户报："刻度数字贴边纵坐标和横坐标的显示范围不一样，这就导致纵坐标显示多，
    //          横坐标显示少，让纵坐标和横坐标的显示范围的汇交点在屏幕左下角
    //          （屏幕左下角在显示范围之外时，取中心→左下角连线与屏幕的交点）。"
    //
    // 真因：两条轴各用**自己那条**区间、起点互不相同 ——
    //   * x 行左端 = 该行高度上的**弦宽**边界（圆屏上随行高变，贴底时收到屏幕中间）；
    //   * y 列下端 = `h − 安全内缩`（**恒在屏幕下缘**，纵向上没有弦宽约束）。
    // 于是 y 列永远拿到满高度、x 行在圆屏边缘只剩中间一小段 → "一边多一边少"。
    // 现在两条都从**同一个角锚点**出发、量**同一段臂长**，各自再被自己的可见边界截断。

    /** 圆屏在高度 y 处的弦半宽（`WatchMetrics.circleWidthFraction` 的离线镜像）。 */
    private fun roundBandHalf(w: Float, h: Float, top: Float, bottom: Float, safeInset: Float): Float =
        minOf(chordHalfAt(top, w, h), chordHalfAt(bottom, w, h)) - safeInset

    @Test
    fun `角锚点：圆屏取中心到左下角连线与可用圆的交点，方屏手机取可用矩形的左下角`() {
        // 推导（屏幕坐标 x 向右、y 向下；圆心 = 屏幕中心，半径 = 短边/2）：
        //   左下方向 = (−1/√2, +1/√2)；可用半径 r_u = r − 安全内缩
        //   C = 圆心 + r_u·(−1/√2, +1/√2) = (w/2 − r_u/√2, h/2 + r_u/√2)
        // 圆表 466：安全内缩 27.96、r = 233、r_u = 205.04、r_u/√2 = 144.99
        //           → C = (88.01, 377.99)px = (44.0, 189.0)dp（density 2）
        val watch = labelCornerAnchorPx(466f, 466f, 466f * 0.06f, isRound = true)
        assertEquals(88.01f, watch.xPx, 0.05f)
        assertEquals(377.99f, watch.yPx, 0.05f)
        // 它必须落在"可用圆"上（到圆心距离 = r_u），方向正好是 45°
        val ru = 233f - 466f * 0.06f
        assertEquals(ru, kotlin.math.hypot(watch.xPx - 233f, watch.yPx - 233f), 0.05f)
        assertEquals(watch.yPx - 233f, 233f - watch.xPx, 0.05f)
        // 而且它在**屏幕之内**（圆表上物理左下角 (0,466) 在圆外：到圆心距离 329 > 233）
        assertTrue("角锚点必须在屏幕里", watch.xPx > 0f && watch.yPx < 466f)
        assertTrue("物理左下角确实在圆外", kotlin.math.hypot(0f - 233f, 466f - 233f) > 233f)

        // 方表 400（非圆屏）：可用矩形四边留白 24px → 左下角 (24, 376)，density 2 → (12, 188)dp
        val square = labelCornerAnchorPx(400f, 400f, 400f * 0.06f, isRound = false)
        assertEquals(24f, square.xPx, 0.01f)
        assertEquals(376f, square.yPx, 0.01f)

        // 手机 1080×2400（非圆屏）：安全内缩 = 短边 1080 的 6% = 64.8
        // → C = (64.8, 2335.2)px，density 3 → (21.6, 778.4)dp
        val phone = labelCornerAnchorPx(1080f, 2400f, 1080f * 0.06f, isRound = false)
        assertEquals(64.8f, phone.xPx, 0.01f)
        assertEquals(2335.2f, phone.yPx, 0.02f)

        // 臂长 = 短边 − 2×安全内缩：圆表 410.08px、方表 352px、手机 950.4px
        assertEquals(410.08f, labelRangeSpanPx(466f, 466f, 466f * 0.06f), 0.05f)
        assertEquals(352f, labelRangeSpanPx(400f, 400f, 400f * 0.06f), 0.01f)
        assertEquals(950.4f, labelRangeSpanPx(1080f, 2400f, 1080f * 0.06f), 0.05f)
        // 非法输入不炸、也不给出无意义的范围
        val fallback = labelCornerAnchorPx(Float.NaN, 0f, Float.NaN, isRound = true)
        assertTrue(fallback.xPx.isFinite() && fallback.yPx.isFinite())
        assertEquals(0f, labelRangeSpanPx(0f, 400f, 24f), 0f)
        assertNull(xRowRangeFromCorner(Float.NaN, 400f, 0f, 100f))
        assertNull(yColumnRangeFromCorner(0f, Float.NaN, 400f, 100f))
        assertNull(xRowRangeFromCorner(100f, 100f, 0f, 100f))
    }

    /**
     * 两条轴的范围（**绘制侧同一对真函数**）：x 行从角锚点向右、y 列从角锚点向上，各量一段臂长。
     *
     * @param axisY x 轴在画布上的高度（决定这一行的弦宽 → 决定 x 行右端被截到哪）
     * @return `x 范围 / y 范围 / 两个范围里各有多少个刻度`
     */
    private fun rangesFor(
        w: Float,
        h: Float,
        densityScale: Float,
        isRound: Boolean,
        axisY: Float,
        upp: Double,
        centerX: Double
    ): Triple<com.functiondrawer.watch.ui.LabelRange, com.functiondrawer.watch.ui.LabelRange, Pair<Int, Int>>? {
        val sizePx = minOf(w, h)
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val step = gridStepForScreen(upp, sizePx)
        if (!step.isFinite() || step <= 0.0) return null
        val corner = labelCornerAnchorPx(w, h, safeInset, isRound)
        val span = labelRangeSpanPx(w, h, safeInset)

        // x 行：行高 → 弦宽 → 可见边界 → 与角锚点范围取交
        val halfHeight = textPx * 0.55f
        val centerOffset = textPx * 0.30f
        val lo = safeInset + textPx
        val hi = h - safeInset
        val base = if (axisY >= 0f && axisY <= h) axisY + textPx * 1.15f else h - safeInset
        val wanted = if (lo <= hi) base.coerceIn(lo, hi) else h / 2f
        val rowCentre = pinLabelIntoBand(wanted - centerOffset, halfHeight, safeInset, hi) ?: return null
        val baseline = rowCentre + centerOffset
        val boxTop = baseline - textPx * 0.85f
        val boxBottom = baseline + textPx * 0.25f
        val band = if (!isRound) {
            w / 2f
        } else {
            roundBandHalf(w, h, boxTop, boxBottom, safeInset)
        }
        val xRange = xRowRangeFromCorner(
            maxOf(safeInset, w / 2f - band),
            minOf(w - safeInset, w / 2f + band),
            corner.xPx,
            span
        ) ?: return null
        // y 列：纵向没有弦宽，只有安全内缩 + 角锚点范围
        val yRange = yColumnRangeFromCorner(safeInset, h - safeInset, corner.yPx, span) ?: return null

        // 两个范围里各有多少个**刻度**（值 = 最近的格点；范围端点按"刻度自己的锚点落进去"算）
        fun ticksIn(loPx: Float, hiPx: Float, toScreen: (Double) -> Float, worldLo: Double, worldHi: Double): Int {
            var n = 0
            var v = ceil(worldLo / step) * step
            var guard = 0
            while (v <= worldHi && guard++ < 512) {
                if (abs(v) > step * 1e-6) {
                    val s = toScreen(v)
                    if (s >= loPx && s <= hiPx) n++
                }
                v += step
            }
            return n
        }
        val xToScreen: (Double) -> Float = { ((it - centerX) / upp).toFloat() + w / 2f }
        val yToScreen: (Double) -> Float = { h / 2f - (it / upp).toFloat() }
        val countX = ticksIn(xRange.loPx, xRange.hiPx, xToScreen, centerX - w / 2.0 * upp, centerX + w / 2.0 * upp)
        val countY = ticksIn(yRange.loPx, yRange.hiPx, yToScreen, -h / 2.0 * upp, h / 2.0 * upp)
        return Triple(xRange, yRange, countX to countY)
    }

    @Test
    fun `两条范围从同一个角锚点出发、长度相等（口径一致，不再一边多一边少）`() {
        val table = ArrayList<String>()
        for ((w, h, rest) in screens) {
            val (density, isRound) = rest
            // 默认视野 + 四个缩放档（覆盖 1-2-5 取整的四个区间）
            val upps = listOf(20.0 / w, 20.0 / w * 0.35, 20.0 / w * 1.6, 20.0 / w * 4.0, 20.0 / w * 9.0)
            for (upp in upps) {
                val r = rangesFor(w, h, density, isRound, axisY = h / 2f, upp = upp, centerX = 0.0)
                    ?: return   // 粗保护那一档（密到整排不标）不算口径不一致
                val (xRange, yRange, counts) = r
                val corner = labelCornerAnchorPx(w, h, minOf(w, h) * 0.06f, isRound)
                val tag = "${w.toInt()}x${h.toInt()}(d=$density,round=$isRound,upp=${"%.4f".format(upp)})"
                table.add(
                    "$tag x=[${"%.1f".format(xRange.loPx)}, ${"%.1f".format(xRange.hiPx)}]" +
                        "(${counts.first}个) y=[${"%.1f".format(yRange.loPx)}, ${"%.1f".format(yRange.hiPx)}]" +
                        "(${counts.second}个)"
                )
                // ① 两条范围**从同一点出发**：x 的左端 = 角锚点 x、y 的下端 = 角锚点 y
                assertEquals("$tag：x 行左端必须落在角锚点上", corner.xPx, xRange.loPx, 0.6f)
                assertEquals("$tag：y 列下端必须落在角锚点上", corner.yPx, yRange.hiPx, 0.6f)
                // ② 两条范围**长度相等**（圆屏上各被圆对称地截掉一点，误差 ≤ 2px）
                assertEquals("$tag：两条范围长度必须相等", xRange.spanPx, yRange.spanPx, 2f)
                // ③ 刻度条数：口径一样 ⟹ 条数最多差 2 条，差的那两条来自**格点相位**
                //    （范围端头正好落在格点外侧）+ **0 不标**（圆点不标，落进哪一条范围就少一个），
                //    不是两条范围长度不同 —— 长度相等在上面已经单独钉住。
                assertTrue(
                    "$tag：刻度数差太多 x=${counts.first} y=${counts.second}",
                    abs(counts.first - counts.second) <= 2
                )
                assertTrue("$tag：两条范围都必须至少有一个刻度", counts.first >= 1 && counts.second >= 1)
            }
        }
        // 打印对照表（跑测试时能一眼看见两边这些档位是不是同一个口径）
        println("== 两条范围对照（同一角锚点出发、同一臂长）==")
        table.forEach { println("  $it") }
    }

    @Test
    fun `角锚点范围下 x 行与 y 列在三种轴位置、三种屏上都至少画出一个刻度`() {
        // 与上一条 x 行测试互补：这里同时盯**两条轴**，而且范围换成本轮的角锚点范围。
        // "至少一个"是硬要求：范围是数字的定义域，范围非空就必须有数字落进去。
        for ((w, h, rest) in screens) {
            val (density, isRound) = rest
            val sizePx = minOf(w, h)
            val upp = 20.0 / w
            for (axisY in listOf(h / 2f, h + 60f, -60f)) {
                val xDrawn = xRowDrawn(w, h, sizePx, density, axisY, upp, 0.0, isRound)
                assertTrue(
                    "轴位=$axisY（${w.toInt()}x${h.toInt()}）：x 行一个刻度都没画出来",
                    xDrawn.isNotEmpty()
                )
                // y 列：同一套真函数镜像（含防重叠与完整可见闸），至少也要有一个
                val yDrawn = yColumnDrawn(w, h, sizePx, density, upp, 0.0, 0.0, isRound)
                assertTrue(
                    "轴位=$axisY（${w.toInt()}x${h.toInt()}）：y 列一个刻度都没画出来",
                    yDrawn.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun `行级兜底在任何缩放档都不可能触发（旧的 20dp 会误清整排）`() {
        // 用户报的"某些缩放档两排数字一起空"就是旧的 `20dp × density` 那条粗保护。
        // 新阈值 = 一个字高（短边 × 0.0462 = 1.1 字号），而 1-2-5 取整能给到的最小
        // 屏幕间距是目标格距的 0.5714 倍（圆表 34.3px、手机 79px）—— 三块屏上都够不到，
        // 所以它在正常参数下不可能触发，只为"step/upp 算出荒唐值"兜底。
        var oldFired = 0
        var samples = 0
        // 细扫 8 个数量级（×1.02 一档，约 930 档 × 3 块屏）：旧判据那条带**很窄**
        // （屏幕间距 [34.3, 40)px，只占一个数量级的约 5%），粗扫会整条跳过去
        // —— 第一次用 9×7 个粗样本扫出来 oldFired = 0，正是这个原因。
        var upp = 1e-4
        while (upp <= 1e4) {
            for ((w, h, rest) in screens) {
                val (density, _) = rest
                val sizePx = minOf(w, h)
                val step = gridStepForScreen(upp, sizePx)
                val spacing = (step / upp).toFloat()
                samples++
                assertFalse(
                    "短边=$sizePx upp=$upp 间距=${spacing}px 被行级兜底判成'整排不标'",
                    labelsTooDenseForRow(spacing, sizePx)
                )
                // 对照：旧判据（20dp × density）在这一批样本里确实误伤过
                if (spacing < 20f * density) oldFired++
            }
            upp *= 1.02
        }
        assertTrue("样本要够多才有说服力：$samples（×1.02 细扫 8 个数量级 × 3 块屏）", samples >= 500)
        assertTrue(
            "旧判据应当真的误伤过（实测 $oldFired / $samples 个样本）—— 否则这条测试没测到东西",
            oldFired > 0
        )
        // 阈值本身：一个字高 = 1.1 × 0.042 = 0.0462 短边；圆表 21.5px < 该屏最小间距 34.3px
        assertEquals(0.0462f, MIN_LABEL_SPACING_FRACTION, 1e-6f)
        assertEquals(21.5f, 466f * MIN_LABEL_SPACING_FRACTION, 0.1f)
        assertTrue("阈值必须小于圆表能出现的最小间距 34.3px", 466f * MIN_LABEL_SPACING_FRACTION < 34.3f)
        // 只有坏值才拦：间距非法 → 兜底拦下；**尺寸**非法 → 不拦
        // （把判断让给逐格判定，绝不因为一个坏尺寸把整排清空）
        assertTrue(labelsTooDenseForRow(Float.NaN, 466f))
        assertTrue(labelsTooDenseForRow(0f, 466f))
        assertTrue(labelsTooDenseForRow(-5f, 466f))
        assertFalse(labelsTooDenseForRow(60f, Float.NaN))
        assertFalse(labelsTooDenseForRow(60f, 0f))
        assertFalse(labelsTooDenseForRow(5f, Float.NaN))
    }

    @Test
    fun `缩放与平移采样里 x 行与 y 列都至少有一个刻度（不许整排或整列空）`() {
        // 用户的要求：**任何缩放档、任何平移位置**都不该出现"整排/整列一个数字都没有"。
        // 采样：12 个数量级的缩放 × 5 个轴位置（正中／轴偏上／轴偏下／轴滑出上边／滑出下边）× 三块屏，
        // x 行与 y 列都断言 ≥ 1（不是"≥ 某个数"）。
        var checks = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var e = -4.0
        while (e <= 4.0) {
            for (mult in listOf(1.0, 2.2, 5.5)) {
                val upp = mult * 10.0.pow(e)
                for ((w, h, rest) in screens) {
                    val (density, isRound) = rest
                    val sizePx = minOf(w, h)
                    for (cy in listOf(0.0, 0.3 * h * upp, -0.3 * h * upp, 0.9 * h * upp, -0.9 * h * upp)) {
                        // x 轴在画布上的高度（世界中心 cy → 屏幕位置）
                        val axisY = h / 2f - ((0.0 - cy) / upp).toFloat()
                        val x = xRowDrawn(w, h, sizePx, density, axisY, upp, 0.0, isRound)
                        val y = yColumnDrawn(w, h, sizePx, density, upp, 0.0, cy, isRound)
                        minX = minOf(minX, x.size)
                        minY = minOf(minY, y.size)
                        checks++
                        assertTrue(
                            "upp=$upp 短边=$sizePx cy=$cy：x 行整排空了（轴高 ${"%.1f".format(axisY)}）",
                            x.isNotEmpty()
                        )
                        assertTrue("upp=$upp 短边=$sizePx cy=$cy：y 列整列空了", y.isNotEmpty())
                    }
                }
            }
            e += 1.0
        }
        assertTrue("样本要够多才有说服力：$checks", checks >= 100)
        println("== 缩放/平移采样（${checks} 个）：两排最少刻度数 x=$minX y=$minY ==")
    }

    @Test
    fun `角锚点范围不破坏上一轮两条修复：夹出来的位置仍过闸、判定仍不抖`() {
        // ① 范围里的每一个锚点都必须过得了"完整可见"那道**严格**闸
        //    （范围是与可见边界取交得到的，所以这是构造性的 —— 这里把它钉住）
        for ((w, h, rest) in screens) {
            val (_, isRound) = rest
            val sizePx = minOf(w, h)
            val safeInset = sizePx * 0.06f
            val corner = labelCornerAnchorPx(w, h, safeInset, isRound)
            val span = labelRangeSpanPx(w, h, safeInset)
            val yRange = yColumnRangeFromCorner(safeInset, h - safeInset, corner.yPx, span)!!
            // 沿范围扫 100 个采样点：夹出来的锚点一定在范围内，而且离端头至少半个身位
            val half = 9f
            for (i in 0 until 100) {
                val t = i / 99f
                val anchor = yRange.loPx + t * yRange.spanPx
                val cy = pinLabelIntoBand(anchor, half, yRange.loPx, yRange.hiPx)
                assertNotNull("范围里的锚点必须夹得出来（t=$t）", cy)
                assertTrue(cy!! >= yRange.loPx + half - 0.01f)
                assertTrue(cy <= yRange.hiPx - half + 0.01f)
            }
        }
        // ② 判定在角锚点范围上重扫 100 个采样点：仍然最多翻一次、位置仍然单调
        //    （上一轮"不抽"的结论不许因为换范围而回退）
        val w = 466f
        val h = 466f
        val sizePx = 466f
        val density = 2f
        val safeInset = sizePx * 0.06f
        val textPx = sizePx * 0.042f
        val corner = labelCornerAnchorPx(w, h, safeInset, isRound = true)
        val span = labelRangeSpanPx(w, h, safeInset)
        val yRange = yColumnRangeFromCorner(safeInset, h - safeInset, corner.yPx, span)!!
        val quantum = labelFollowQuantumPx(sizePx)
        var drawn = false
        var flips = 0
        var previous: Boolean? = null
        var lastAnchor = Float.NaN
        // 从**范围之外**（屏幕更下方）一路往上扫进范围：起点要真的超出预算
        // （预算里有 22dp 的兜底下限，所以在范围端头外一点点仍然画得出来 —— 那是故意的）
        for (i in 0 until 100) {
            val tickY = 470f - i * 1.7f
            val cy = pinLabelNearAxis(
                labelAnchorPx = tickY + textPx * 0.06f,
                axisAnchorPx = tickY,
                halfExtentPx = textPx * 0.55f,
                loPx = yRange.loPx,
                hiPx = yRange.hiPx,
                visibleLoPx = yRange.loPx,
                visibleHiPx = yRange.hiPx,
                quantumPx = quantum,
                densityScale = density,
                wasDrawn = drawn,
                hysteresisPx = quantum
            )
            drawn = cy != null
            if (previous != null && previous != drawn) flips++
            previous = drawn
            if (cy != null) {
                if (!lastAnchor.isNaN()) {
                    assertTrue("锚点回跳：$lastAnchor → $cy（第 $i 个采样点）", cy <= lastAnchor + 1e-3f)
                }
                lastAnchor = cy
            }
        }
        assertEquals("角锚点范围上判定仍然只允许翻一次", 1, flips)
    }

    // ---- 三、列表行左滑删除的尺度（函数行与对象行**同一份**） ----
    //
    // 用户报的那条 bug 是："除了函数之外的对象在列表都不能滑动删除，应该所有对象都能
    // 滑动删除的。" 它的主因是**起手位置**（对象行上一版把行右侧那条带让给了"右边缘
    // 回列表"手势，而从那里起手的左滑会被 `awaitOneWayDrag` 静默丢弃 ——
    // 见 `FunctionsScreen.GraphObjectRow` 里那段说明），那属于"谁挂在哪个节点、谁拿到
    // down"的事，离线测不了。
    //
    // 但**判定用的算术**（够不够远、方向对不对、动画怎么随进度走）现在只有一份、
    // 就在 `PlotMetrics` 末尾那一节里 —— 两行（`FunctionList.FunctionRow` 和
    // `FunctionsScreen.GraphObjectRow`）调的是同一个共用件，所以下面这三条测试
    // 对**两种行**同时成立："函数行过得了 ⟹ 对象行也过得了"。
    //
    // （上一版的隐患正是这一条：两行各有一份常量和一段抄出来的判定，
    //   函数行 1/3、对象行 0.3333f，谁也保证不了它们一直一样。）

    /** 三种真实行宽（px）：圆表最宽行（弧宽 0.90×466）、圆表最窄行（0.52×466）、手机满宽（411dp@2.75）。 */
    private val rowWidthsPx = listOf(419f, 242f, 1062f)

    @Test
    fun `左滑删除的阈值永远是行宽的 1_3，方向和距离缺一不可`() {
        for (w in rowWidthsPx) {
            val travel = swipeDeleteTravelPx(w)
            // 阈值是一个**比例**，不是各屏各自的 px 数
            assertEquals(w * SWIPE_DELETE_TRAVEL, travel, 1e-3f)
            // 正好滑够 → 删；差 1px → 不删（"够不够"是硬边界）
            assertTrue("行宽 ${w}px：滑够 1/3 必须判删", swipeDeleteReached(-travel, w))
            assertFalse("行宽 ${w}px：差 1px 不许删", swipeDeleteReached(-travel + 1f, w))
            // 方向不对（往右）永远不删 —— 往右是第一屏"左边缘往右滑进功能界面"的方向，
            // 两个手势靠**方向**分开，谁也不许越界
            assertFalse("往右不是删除手势", swipeDeleteReached(travel, w))
            assertFalse("没位移不算", swipeDeleteReached(0f, w))
            // 渐染进度和阈值同一个基准：滑够到 1，再滑也不超过 1
            assertEquals(0f, swipeDeleteProgress(0f, w), 1e-4f)
            assertEquals(1f, swipeDeleteProgress(-travel, w), 1e-4f)
            assertEquals(1f, swipeDeleteProgress(-travel * 3f, w), 1e-4f)
        }
        // 渐染深度：滑够时是 85% 的砖红（够醒目、又不至于把行里的白字糊掉）
        assertTrue(SWIPE_DELETE_TINT > 0.5f && SWIPE_DELETE_TINT < 1f)
        // 跟手：只跟往左那一截（往右的回搓被夹成 0，行不会被拽出屏幕右边）
        assertEquals(0f, swipeDeleteFollow(120f), 0f)
        assertEquals(-120f, swipeDeleteFollow(-120f), 0f)
        // 行宽非法时阈值是 0 = "怎么滑都不算够"（宁可这一次不删，也不能拿 NaN 去比大小）
        assertEquals(0f, swipeDeleteTravelPx(0f), 0f)
        assertEquals(0f, swipeDeleteTravelPx(Float.NaN), 0f)
        assertFalse(swipeDeleteReached(-9999f, 0f))
        assertFalse(swipeDeleteReached(Float.NEGATIVE_INFINITY, 419f))
        assertEquals(0f, swipeDeleteProgress(0f, Float.NaN), 1e-4f)
    }

    @Test
    fun `单向拖动：没过触摸容差谁都不认领，越过后方向对才认领`() {
        val slop = 24f
        // ① 没过容差 → 两个方向都不认领。
        //    这一条是"色点单击切显隐、长按调色、长按文字区进属性屏、列表竖直滚动"
        //    全都还能用的**全部依据**（手指落下的头几像素一定是抖的）
        assertFalse(oneWayDragClaims(-5f, positive = false, slopPx = slop))
        assertFalse(oneWayDragClaims(5f, positive = true, slopPx = slop))
        // ② 越过容差 + 方向对 → 认领（左滑删除：positive = false）
        assertTrue(oneWayDragClaims(-slop, positive = false, slopPx = slop))
        assertTrue(oneWayDragClaims(-100f, positive = false, slopPx = slop))
        // ③ 越过容差但方向不对 → 不认领；调用方据此立刻退出、**一个字节都不消费**，
        //    把这一串原样让给别人（第一屏"左边缘往右滑进功能界面"靠的就是这一条）
        assertFalse(oneWayDragClaims(100f, positive = false, slopPx = slop))
        assertFalse(oneWayDragClaims(-100f, positive = true, slopPx = slop))
        //    竖直方向同理（第二屏顶部下滑 positive = true）
        assertTrue(oneWayDragClaims(30f, positive = true, slopPx = slop))
        assertFalse(oneWayDragClaims(30f, positive = false, slopPx = slop))
        // 非法输入不认领：宁可不开始，也不能拿 NaN 去比大小
        assertFalse(oneWayDragClaims(Float.NaN, positive = false, slopPx = slop))
        assertFalse(oneWayDragClaims(-100f, positive = false, slopPx = Float.NaN))
        // ④ 累计**只由"我们自己的位移"决定**：`oneWayDragStep` 只有两个输入
        //    （累计值 + 这一帧的位移），**没有"这一帧被别人消费了吗"这一项** ——
        //    真机上它对应 `positionChangeIgnoreConsumed()`。用户报的
        //    "纯水平左滑删不掉、带一点斜度就能删"的真因就是当时用了消费敏感的
        //    `positionChange()`：父节点（第一屏根节点那条库版横向手势）越界后
        //    每帧消费这一串，于是我们的累计每帧被清零、永远够不到 1/3 行宽。
        //    下面这几条把"消费不该参与判定"这条规矩钉在算术上：
        assertEquals(-24f, oneWayDragStep(0f, -24f), 1e-4f)
        assertEquals(-48f, oneWayDragStep(-24f, -24f), 1e-4f)
        //    回搓是**扣减**而不是清零（比旧版"反向就清零"更跟手，也更不容易误触发）
        assertEquals(0f, oneWayDragStep(-24f, 24f), 1e-4f)
        //    非法增量不污染已经滑出来的累计（一个坏帧不该把位移抹掉）
        assertEquals(-24f, oneWayDragStep(-24f, Float.NaN), 1e-4f)
        assertEquals(-24f, oneWayDragStep(-24f, Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun `被扔掉那一下：甩出 28% 加缩小 12% 加淡出，非法输入不炸`() {
        val w = 419f
        // 起点：还没开始甩 → 一步都不动、尺寸和透明度都是原样
        assertEquals(0f, swipeDeleteSweepShiftPx(w, 0f), 0f)
        assertEquals(1f, swipeDeleteSweepScale(0f), 1e-4f)
        assertEquals(1f, swipeDeleteSweepAlpha(0f), 1e-4f)
        // 终点：甩出 28% 行宽（往左为负）、缩到 88%、完全淡出
        assertEquals(-w * DELETE_OUT_SHIFT, swipeDeleteSweepShiftPx(w, 1f), 1e-3f)
        assertEquals(1f - DELETE_OUT_SHRINK, swipeDeleteSweepScale(1f), 1e-4f)
        assertEquals(0f, swipeDeleteSweepAlpha(1f), 1e-4f)
        // 进度越界也只当 0..1（动画被打断/重启时不会把行甩到屏幕外）
        assertEquals(swipeDeleteSweepShiftPx(w, 1f), swipeDeleteSweepShiftPx(w, 9f), 1e-4f)
        assertEquals(1f, swipeDeleteSweepAlpha(-3f), 1e-4f)
        // 非法输入：退回"什么都没发生"（绝不能算出 NaN 塞进 graphicsLayer）
        assertEquals(0f, swipeDeleteSweepShiftPx(Float.NaN, 1f), 0f)
        assertEquals(0f, swipeDeleteSweepShiftPx(w, Float.NaN), 0f)
        assertEquals(1f, swipeDeleteSweepScale(Float.NaN), 1e-4f)
        assertEquals(1f, swipeDeleteSweepAlpha(Float.NaN), 1e-4f)
    }

    @Test
    fun `甩出方向必须一路往左：合成位移 = 跟手 + 甩出（上一版相减，于是往右回弹）`() {
        // 用户报的"删除动画鬼畜、函数条从左边移到右边去"就是这条算式：
        // 跟手位移和甩出位移**都是往左的负数**，上一版三个调用点写的是
        // `dragX - sweepShiftPx(...)` —— 减一个负数等于往右推，行从"滑到左边"
        // 一路回弹回屏幕中间。下面这几条把"只许更往左"钉死。
        val w = 419f
        // 抬手那一刻的位置：正好滑够 1/3 行宽（往左，负数）
        val dragX = -swipeDeleteTravelPx(w)
        assertTrue("前置：滑够 1/3 时跟手位移必须是负的", dragX < 0f)

        // ① 还没开始甩：行就停在手指滑到的位置上（一个像素都不多动）
        assertEquals(dragX, swipeDeleteTranslationPx(dragX, w, 0f), 1e-3f)

        // ② 甩出过程**单调往左**（这是"继续往左甩出并淡出"这句要求的算术形式）
        var previous = swipeDeleteTranslationPx(dragX, w, 0f)
        for (i in 1..10) {
            val now = swipeDeleteTranslationPx(dragX, w, i / 10f)
            assertTrue("甩出必须一路往左，不许回弹：$previous → $now", now < previous + 1e-4f)
            previous = now
        }
        // ③ 终点 = 跟手位移 + （−0.28 行宽）= 两个负数相加
        assertEquals(dragX - w * DELETE_OUT_SHIFT, previous, 1e-3f)

        // ④ 反面：上一版那句"相减"的终点比起点**更靠右** 0.28 行宽 —— 那就是鬼畜
        val oldVersionEnd = dragX - swipeDeleteSweepShiftPx(w, 1f)
        assertTrue(
            "上一版写法的终点应当比起点更靠右（这正是用户看到的往右跑）",
            oldVersionEnd > dragX + w * 0.2f
        )

        // ⑤ 往右拖过（跟手位移被夹成 0）时，甩出仍然只往左：行不会被甩到屏幕右边去
        assertEquals(-w * DELETE_OUT_SHIFT, swipeDeleteTranslationPx(50f, w, 1f), 1e-3f)

        // ⑥ 非法输入绝不把 NaN 塞进图层：宽/进度坏掉时退回"只用跟手位移"
        assertEquals(dragX, swipeDeleteTranslationPx(dragX, Float.NaN, 1f), 1e-3f)
        assertEquals(dragX, swipeDeleteTranslationPx(dragX, w, Float.NaN), 1e-3f)
        //    跟手位移本身坏掉时等于"没在跟手"（0），甩出那一段照常往左
        //    （`swipeDeleteFollow` 把非有限值夹成 0，所以这里仍然是一个有限数）
        assertEquals(
            -w * DELETE_OUT_SHIFT,
            swipeDeleteTranslationPx(Float.NaN, w, 1f),
            1e-3f
        )
    }

    @Test
    fun `上下滑之后再左右滑不许删：谁的轴先过容差这一串就归谁`() {
        // 用户报的误删原话："我在列表这里上下滑动之后，如果再左右滑动，会唤起这个
        // 开始按住的对象的删除，但是实际上我上下滑动之后，我应该唤起不了对象的删除。"
        //
        // 真因：行的删除手势只累计**横向**位移，而列表的竖直滚动是 `LazyColumn`
        // 自己认领的 —— 上一版里竖直滚动把这一串拿走之后，我们那份横向累计还在涨，
        // 于是"滚到一半改成横滑"被判成"要删这一行"。
        // 判据（`oneWayDragLostToOtherAxis`）：**交叉轴先过容差、而且比主轴大 → 永久放弃**。
        val w = 419f                       // 圆表最宽行（和上面几组同一个口径）
        val slop = 24f                     // `ViewConfiguration.touchSlop`（圆表 d2 上 ≈24px）

        // ① 先上下滑过容差（列表滚动认领），再横滑很远 → **不认领、不删**（用户的场景）
        assertFalse(
            "先竖着滚过容差之后再横滑，绝不能删",
            simulateRowSwipe(
                moves = listOf(0f to -20f, 0f to -20f, 0f to -20f, -400f to 0f),
                rowWidthPx = w,
                slopPx = slop
            )
        )
        // ①' 同一串里"竖为主、带一点横"的斜滑也归列表滚动（竖 60、横 30）
        assertFalse(
            "竖为主的斜滑归列表滚动",
            simulateRowSwipe(
                moves = List(3) { -10f to -20f },
                rowWidthPx = w,
                slopPx = slop
            )
        )

        // ② **纯水平左滑仍然能删**（上一轮刚修好的那条，不许被这一条弄坏）
        assertTrue(
            "纯水平左滑必须照旧能删",
            simulateRowSwipe(
                moves = List(14) { -12f to 1f },   // 横向一直领着纵向（纵向只有抖动）
                rowWidthPx = w,
                slopPx = slop
            )
        )
        // ②' 横为主的斜滑也仍然算删除（"斜着滑能删"这条老行为只在竖为主时才让位）
        assertTrue(
            "横为主的斜滑仍然能删",
            simulateRowSwipe(
                moves = List(14) { -12f to -5f },
                rowWidthPx = w,
                slopPx = slop
            )
        )

        // ③ **纯竖直上下滑**：主轴（横）几乎不动 → 不认领、更不会删
        assertFalse(
            "纯竖直滑动绝不能被当成删除",
            simulateRowSwipe(
                moves = List(20) { 1f to -12f },
                rowWidthPx = w,
                slopPx = slop
            )
        )

        // ④ 判据本身的三条边界（纯函数，逐条钉住）
        //    交叉轴连容差都没过 → 不放弃（"横向抖动"那一档：横向抖动 10px 不该让删除失效）
        assertFalse(oneWayDragLostToOtherAxis(-100f, 10f, slop))
        assertFalse(oneWayDragLostToOtherAxis(-100f, -10f, slop))
        //    交叉轴过了容差、但主轴更大 → 仍然归这一行（横为主的斜滑）
        assertFalse(oneWayDragLostToOtherAxis(-100f, 30f, slop))
        //    交叉轴过了容差、而且比主轴大 → 放弃
        assertTrue(oneWayDragLostToOtherAxis(-10f, 60f, slop))
        assertTrue(oneWayDragLostToOtherAxis(-10f, -60f, slop))
        assertTrue(oneWayDragLostToOtherAxis(0f, -60f, slop))
        //    非法输入（每帧都会被调到）：一律不放弃 —— 宁可让这一串按老规矩走，
        //    也不要因为一个坏帧把用户的横向滑动吞掉
        assertFalse(oneWayDragLostToOtherAxis(Float.NaN, 60f, slop))
        assertFalse(oneWayDragLostToOtherAxis(-10f, Float.NaN, slop))
        assertFalse(oneWayDragLostToOtherAxis(-10f, 60f, Float.NaN))
        assertFalse(oneWayDragLostToOtherAxis(-10f, 60f, 0f))
    }

    /**
     * `DragGestures.awaitOneWayDrag` 每帧判定的**离线镜像**（顺序逐条对齐）：
     *
     * ```
     * 每帧：累计主轴 + 累计交叉轴
     *      → 还没认领时：先问"交叉轴赢了吗"（赢了 = 永久放弃，直接退出）
     *                  → 再问"越过容差且方向对吗"（对 = 认领；不对 = claimOpposite 也认领）
     * 抬手：认领过 且 位移够 1/3 行宽 → 删
     * ```
     *
     * 真机上那两个位移来自 `positionChangeIgnoreConsumed()`（所以"别人消费了这一帧"
     * 不影响判定 —— 那是"纯水平左滑删不掉"那个 bug 的解药，**不能**跟着这条新规矩一起改）；
     * 这里直接喂每帧的 (横向, 纵向) 位移。行删除的方向是**往左**（`positive = false`），
     * `claimOpposite = true`（这一整条水平轴都归这一行）。
     */
    private fun simulateRowSwipe(
        moves: List<Pair<Float, Float>>,
        rowWidthPx: Float,
        slopPx: Float
    ): Boolean {
        var total = 0f
        var cross = 0f
        var claimed = false
        for ((dx, dy) in moves) {
            total = oneWayDragStep(total, dx)
            cross = oneWayDragStep(cross, dy)
            if (!claimed) {
                // ① 交叉轴已经拿走这一串 → **永久**放弃（`break`），之后的横向移动复活不了它
                if (oneWayDragLostToOtherAxis(total, cross, slopPx)) return false
                // ② 认领：越过容差 + 方向对；`claimOpposite = true` 时方向不对也认领
                if (oneWayDragClaims(total, positive = false, slopPx = slopPx)) {
                    claimed = true
                } else if (kotlin.math.abs(total) >= slopPx) {
                    claimed = true
                }
            }
        }
        // 抬手：够不够远（往左为负）
        return claimed && swipeDeleteReached(total, rowWidthPx)
    }

    // ---- 四、滑动条行（`SliderRow`）的「删除起手区」（#3 的补充：滑动条行也要能左滑删除） ----
    //
    // 划分是"**谁在手指底下，谁说了算**"（完整那一节在 `PlotMetrics` 末尾）：
    //   * 滑轨 = 改参数值、播放键 = 它自己、其余（徽标 / 下界上界文字 / 行内边距与缝隙）
    //     = 左滑删除；
    //   * 真机上这一点由 `skipIfDownConsumed` 保证 —— 滑轨和播放键自己会消费掉那次 down，
    //     删除手势因此**根本不开始**（不消费、不跟手、松手也不删）。那是"谁拿到 down"的
    //     事，离线测不了，所以它写在注释与共用件里、由 `skipIfDownConsumed` 的默认值
    //     保证另外两行不受影响；
    //   * 能离线钉住的是**播放键那一格的算术**（`PlotMetrics` 算的）和三块屏上的
    //     **实际 dp 数** —— 它们决定了"删除起手区"在真机上到底有多宽：
    //     太窄就点不中，太宽就会把滑轨的地盘吃掉（用户明确要求滑轨上拖动绝不误删、
    //     非滑轨区域左滑要稳）。

    /** 三块屏：(短边 dp, 行高 dp = 0.15 × 短边)。和 `PlotMetrics` 末尾那张表同一个口径。 */
    private val sliderScreens = listOf(233f to 34.95f, 200f to 30f, 411f to 61.65f)

    @Test
    fun `滑动条行：播放键那一格的左右沿按版面比例算，删除起手区左带不小于 30dp`() {
        // 三块屏上的实算值 —— 就是报告里那张 dp 表（改版面比例时这里会立刻变红）
        val expectedLeft = mapOf(233f to 36.81f, 200f to 31.60f, 411f to 64.94f)
        val expectedRight = mapOf(233f to 61.73f, 200f to 52.99f, 411f to 108.90f)
        for ((size, rowH) in sliderScreens) {
            val left = sliderPlayKeyLeftDp(size)
            val right = sliderPlayKeyRightDp(size, rowH)
            // 左沿 = 行左内边距 + 参数字母/当前值那一栏 + 一条缝 —— **不是估的**：
            // `SliderRow` 的 `Row` 用的就是这三个比例，所以版面和手势不会各说各话
            assertEquals(
                size * (SLIDER_ROW_PAD + SLIDER_PARAM_COL_W + SLIDER_ROW_GAP),
                left,
                1e-3f
            )
            assertEquals(expectedLeft.getValue(size), left, 0.05f)
            assertEquals(expectedRight.getValue(size), right, 0.05f)
            // "删除起手区左带" = `[0, 播放键左沿]`：真机上手指要按得中
            // （圆表上 36.8dp 已经和图标按钮 41.9dp 同一个量级，而且它是**整行高**）
            assertTrue("短边 ${size}dp：删除起手区左带只有 $left dp，太窄了", left >= 30f)
            // 播放键那一格是有大小的：宽 = 行高 × 0.62 × 1.15、高 = 行高 × 0.62
            assertEquals(rowH * 0.62f * 1.15f, right - left, 1e-3f)
        }
        // 播放键那一格的地盘不该大到把行吃掉：三块屏上它都只占行宽的一小截
        // （手机行宽 386.3dp → 44.0dp ≈ 11%；圆表最宽行 209.7dp → 24.9dp ≈ 12%）
        assertTrue(sliderPlayKeyRightDp(411f, 61.65f) - sliderPlayKeyLeftDp(411f) < 411f * 0.15f)
    }

    @Test
    fun `滑动条行：按住的这一点算不算播放键（含端点，非法输入不炸）`() {
        val size = 233f
        val rowH = 34.95f
        val left = sliderPlayKeyLeftDp(size)
        val right = sliderPlayKeyRightDp(size, rowH)
        // 端点**算在内**：边界上不留一条"擦过去就变成删除"的缝
        assertTrue(sliderPressOnPlayKey(left, left, right))
        assertTrue(sliderPressOnPlayKey(right, left, right))
        assertTrue(sliderPressOnPlayKey((left + right) / 2f, left, right))
        // 左边那一带是"删除起手区"（参数字母/当前值徽标），右边是下界文字那一小段 ——
        // 两处都**不算**播放键，于是那里按住能长按进属性界面、左滑能删
        assertFalse(sliderPressOnPlayKey(0f, left, right))
        assertFalse(sliderPressOnPlayKey(left - 0.5f, left, right))
        assertFalse(sliderPressOnPlayKey(right + 0.5f, left, right))
        // 非法输入不炸、也**不吞掉长按**：返回 false = "不算播放键"，长按照常成立
        // （宁可长按能用，也不要因为一个坏坐标把长按吃掉）
        assertFalse(sliderPressOnPlayKey(Float.NaN, left, right))
        assertFalse(sliderPressOnPlayKey(left, Float.NaN, right))
        assertFalse(sliderPressOnPlayKey(left, left, Float.NaN))
        // 区间反了（坏参数）：不认
        assertFalse(sliderPressOnPlayKey(left, right, left))
        // 短边/行高非法：左沿退回 0、右沿退回左沿（都是"退化但可用"，绝不抛异常 ——
        // 这个函数每一帧都会被排版与长按判定调到）
        assertEquals(0f, sliderPlayKeyLeftDp(Float.NaN), 0f)
        assertEquals(0f, sliderPlayKeyLeftDp(0f), 0f)
        assertEquals(sliderPlayKeyLeftDp(size), sliderPlayKeyRightDp(size, Float.NaN), 1e-4f)
        assertEquals(sliderPlayKeyLeftDp(size), sliderPlayKeyRightDp(size, 0f), 1e-4f)
    }
}
