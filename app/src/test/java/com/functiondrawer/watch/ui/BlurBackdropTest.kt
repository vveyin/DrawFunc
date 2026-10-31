package com.functiondrawer.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃的**口径**测试（3.36：改用 BlurView 之后剩下的部分）。
 *
 * 这里只钉不用 Android 也能验证的东西：
 *
 *   * 三处背景共用的层次常数（页面遮罩 / 压暗）；
 *   * 半径口径（短边 × 0.075，下限 20dp）；
 *   * BlurView 的半径换算（dp → px → 缩小 6 倍的坐标系 → 卡 25）。
 *
 * `isFlatImage`、σ 换算那一批测试随 `GlassBackdrop` / `BitmapBlur` 一起删掉了 ——
 * 那套"抓帧 → CPU 糊 → 判空图"的管线在 3.36 已整体移除，留着测试只会误导。
 */
class BlurBackdropTest {

    // ---- 渲染口径（3.35：全部回到 3.20；3.36 保留）----
    //
    // 3.34 曾把页面描边降到 0.30、把 tint 换算成跟着描边走、把模糊图放大 1.22 倍。
    // 用户装上后的反馈是"还是一样的" —— 证明这几个数不是病根，
    // 真正的差别在渲染方式。3.35 试过换成 `Modifier.blur`（RenderEffect），
    // 但那是 API 31 才有的能力，**Android 11 手表上完全无效**，所以 3.36
    // 回到"CPU 糊一张位图 + 画图"，这几个数也就只能一直是 3.20 的值。

    @Test
    fun `page mask is gone so every screen shows the same backdrop`() {
        // 1.2.47：用户要求"全局都用函数列表界面的背景"。
        // 函数列表 = `GlobalBackdrop`（糊图 + 10% tint）；非抽屉页再盖 0.62 的底色
        // 会让同一张图变暗变平，看起来就像"糊得不一样"。所以这层归零。
        val mask = GLASS_PAGE_MASK_ALPHA
        assertEquals("非抽屉页不再叠自己的底色", 0f, mask, 1e-6f)
    }

    @Test
    fun `backdrop tint is the 3_20 ten percent`() {
        // 3.20 的同款压暗层。3.34 让它跟着描边换算，结果把模糊压成了色块。
        val tint = GLASS_BACKDROP_TINT
        assertEquals("tint 必须回到 3.20 的 0.10", 0.10f, tint, 1e-6f)
    }

    /**
     * 满档半径：短边 × [GLASS_BLUR_FRACTION]，下限 [GLASS_BLUR_MIN_DP]。
     *
     * ## 3.42：比例从 0.075 提到 0.16
     *
     * 用户报"模糊程度定在 70% 看不出任何改变"。除了映射曲线（见
     * [blurRadiusDpFor]），还有一个原因：0.075 这个满档本身在手表上
     * 只有 17.5dp（被 20dp 下限抬到 20），折进算法坐标只有 6.7 ——
     * 整个滑条的可调范围挤在"几乎看不出糊"的小区间里。
     *
     * 0.16 让满档真正"很糊"（被 25 上限卡住），滑条才有行程可走。
     */
    @Test
    fun `radius uses the full strength fraction with a 20dp floor`() {
        val fraction = GLASS_BLUR_FRACTION
        val floorDp = GLASS_BLUR_MIN_DP
        assertEquals("满档比例 0.22", 0.22f, fraction, 1e-6f)
        assertEquals("下限仍是 20dp", 20f, floorDp, 1e-6f)
        // 手表 233dp：比例项 51.26dp > 下限 → 用比例项
        assertEquals("233dp → 51.26（比例）", 51.26f, maxOf(233f * fraction, floorDp), 1e-1f)
        // 手机 360dp：79.2dp，明显大于下限
        assertEquals("360dp → 79.2（比例）", 79.2f, maxOf(360f * fraction, floorDp), 1e-1f)
        // 极窄屏（80dp 的方块预览）必须被下限兜住，否则糊了个寂寞
        assertTrue("80dp 上比例项小于下限（说明下限真的在起作用）", 80f * fraction < floorDp)
    }

    // ---- BlurView 的半径换算（3.36 / 3.42 修正缩放因子）----

    /**
     * 手表实测值：短边 233dp、密度 2.0（466px）。
     *
     *   ﻿20dp → 40px → 40 / 4 = 10（小图上的半径）
     *
     * ## 3.42：除数 6 → 4
     *
     * 项目依赖的是 BlurView **3.2.0**，反编译 `BlurView.setupWith(BlurTarget)`
     * 看到的默认 `scaleFactor` 是 `4.0f`；旧注释"钉在 2.0.6 核过 6"是错的版本。
     * 按 6 算会让半径偏小 1/3，叠加平方曲线后中间档全都糊得不够。
     */
    @Test
    fun `watch radius is divided by the downscale factor`() {
        val r = blurViewRadius(radiusDp = 20f, density = 2f)
        assertEquals("20dp @2x → 40px → 小图 10", 40f / 4f, r, 1e-4f)
        assertTrue("必须落在库的 25 上限以内", r <= BLUR_MAX_RADIUS)
    }

    /**
     * 手机上短边 411dp、密度 2.75：`0.16 × 411 ≈ 65.8dp` → 181px → 45.2，
     * **被 25 卡住** —— 满档在手机上也应该是"库里能给的最糊"。
     */
    @Test
    fun `phone full strength hits the lib cap`() {
        val radiusDp = blurRadiusDp(411, 411)
        val r = blurViewRadius(radiusDp, 2.75f)
        assertEquals("满档必须顶到 25", BLUR_MAX_RADIUS, r, 1e-4f)
    }

    /**
     * 极端值：密度大到换算后超过 25 时必须被削到 25，而不是把大数传给库。
     *
     * 库自己也会 `Math.min(25, r)`，但"我们这边算清楚"能让参数变化真正有感知
     * （否则调大半径会看起来毫无变化，很容易误判成"BlurView 没生效"）。
     */
    @Test
    fun `huge radius is clamped to the lib cap`() {
        assertEquals(BLUR_MAX_RADIUS, blurViewRadius(radiusDp = 200f, density = 4f), 1e-4f)
    }

    /** 0 或负数不能传进去（RenderScript 对半径 0 是"不糊"，负数会抛）。 */
    @Test
    fun `non-positive radius collapses to zero`() {
        assertEquals(0f, blurViewRadius(radiusDp = 0f, density = 2f), 1e-6f)
        assertEquals(0f, blurViewRadius(radiusDp = -5f, density = 2f), 1e-6f)
    }

    /** [blurRadiusDp] 与 [GLASS_BLUR_MIN_DP] 的关系：极窄屏被下限抬起、常规屏看比例。 */
    @Test
    fun `blurRadiusDp matches the full strength formula`() {
        assertEquals("233dp → 51.26（比例）", 51.26f, blurRadiusDp(233, 233), 1e-1f)
        assertEquals("411dp → 90.42（比例）", 90.42f, blurRadiusDp(411, 411), 1e-1f)
        assertEquals("80dp → 20（下限）", 20f, blurRadiusDp(80, 80), 1e-4f)
    }

    // ---- 3.40：模糊程度滑条 ----

    /**
     * 100% = 3.20 那一档（**逐位相同**）。
     *
     * 用户拖到最右时看到的就是升级前的糊度 —— 这一条是"默认观感没变"的保证。
     */
    @Test
    fun `full strength equals the 3_20 radius`() {
        assertEquals(blurRadiusDp(233, 233), blurRadiusDpFor(233, 233, 1f), 1e-4f)
        assertEquals(blurRadiusDp(411, 411), blurRadiusDpFor(411, 411, 1f), 1e-3f)
    }

    /**
     * **0% 必须是真的不糊**（返回 0），不能被 `GLASS_BLUR_MIN_DP = 20f` 抬起。
     *
     * 这是用户新加滑条的核心语义（"0% 到 100%"），也是唯一一处
     * [blurRadiusDpFor] 与 [blurRadiusDp] 行为不同的地方。
     */
    @Test
    fun `zero strength is really zero not the min floor`() {
        assertEquals(0f, blurRadiusDpFor(233, 233, 0f), 1e-6f)
        assertEquals(0f, blurRadiusDpFor(411, 411, 0f), 1e-6f)
    }

    /**
     * 中段用**平方**曲线：50% 是满档的 1/4（3.42）。
     *
     * ## 为什么不是线性的一半
     *
     * 用户报："模糊程度定在 70%，看不出有任何改变。"根因是旧映射
     * `full × strength` 在手表上把 70% 算成算法坐标 7.2、100% 算成 10.3 ——
     * 那个区间里 `ScriptIntrinsicBlur` 早就糊成一片，两个值看起来一样。
     *
     * 平方把"能分辨"的区间整体挪到滑条左半段：50% 只到 1/4 半径，
     * 数值小、变化就看得见。右半段继续加深直到卡上限，那是"更糊"而非"没变化"。
     */
    @Test
    fun `half strength is a quarter of the radius`() {
        val full = blurRadiusDp(411, 411)
        assertEquals(full / 4f, blurRadiusDpFor(411, 411, 0.5f), 1e-3f)
    }

    /**
     * **70% 与 100% 必须落进不同的算法半径**（3.42 用户报的那条）。
     *
     * 用**手表**参数算（233dp / density 2.0）：旧映射下两者分别 ≈7.2 和 10.3，
     * 那个区间里早就糊成一片、眼睛分不出；新映射下 70% ≈ 12.6、100% = 25，
     * 差 12 格，拨一格就看得出。
     *
     * ⚠️ 不要用 411dp 大表来断言：那边 80% 就撞满 25 了（屏大、可糊的余地小），
     * 真实的"看不出区别"发生在手表那条曲线上。
     */
    @Test
    fun `seventy percent is visibly lighter than full on a watch`() {
        val density = 2.0f
        val r70 = blurViewRadius(blurRadiusDpFor(233, 233, 0.7f), density)
        val r100 = blurViewRadius(blurRadiusDpFor(233, 233, 1f), density)
        assertTrue(
            "手表上 70% 的算法半径（$r70）必须明显小于 100%（$r100）",
            r100 - r70 >= 8f
        )
        // 而且 70% 本身也得"真的糊"（不能是小到看不出）
        assertTrue("70% 本身也必须看得出糊（$r70）", r70 >= 8f)
    }

    /** 0% 时算法半径也必须是 0（不能被"最小可见半径"抬起）。 */
    @Test
    fun `zero radius stays zero in the algorithm space`() {
        assertEquals(0f, blurViewRadius(0f, 2f), 1e-6f)
    }

    /** 非零强度要有可分辨的下限，否则 1% 和 20% 一样。 */
    @Test
    fun `tiny non zero strength still looks blurred`() {
        assertTrue(blurViewRadius(0.5f, 2f) >= BLUR_MIN_VISIBLE_RADIUS)
    }

    /**
     * **每一档程度都要落在可分辨的整数半径上**（3.44 用户第 N 次报"还是一样糊"）。
     *
     * 这一轮才查清楚：3.43 的映射其实算得对（70% → 12.6、100% → 25），
     * 但 `RenderScriptBlur.blur()` 拿到的是**原分辨率**位图 —— 25px 半径
     * 落在 466px 的画面上只是"轻微柔化"，12.6 和 25 肉眼分不出。
     *
     * 现在上游会先缩 4 倍再卷积（[BLUR_DOWNSCALE]），半径在**小图坐标系**里生效。
     * 注意小档位会被 [BLUR_MIN_VISIBLE_RADIUS] 兜住（0.5%~10% 都落在 1.2 附近，
     * 这是有意的 —— 1% 和 10% 在屏幕上本来就该差不多），所以这里不断言"严格单调"，
     * 只断言**能被眼睛分出来**的那几个关键档位。
     */
    @Test
    fun `key strength steps map to visibly different radii`() {
        val density = 2.0f
        fun radiusAt(percent: Int) =
            blurViewRadius(blurRadiusDpFor(233, 233, percent / 100f), density)

        val r30 = radiusAt(30)
        val r50 = radiusAt(50)
        val r70 = radiusAt(70)
        val r100 = radiusAt(100)

        // 单调（不小于上一档）—— 拨到右边不会反而变浅
        assertTrue("50% 不小于 30%", r50 >= r30)
        assertTrue("70% 不小于 50%", r70 >= r50)
        assertTrue("100% 不小于 70%", r100 >= r70)

        // 可分辨：相邻关键档至少差 2 格（1 格在小图上就已经能看出深浅）
        assertTrue("30%→50% 要能看出来（$r30 → $r50）", r50 - r30 >= 2f)
        assertTrue("70%→100% 要能看出来（$r70 → $r100）", r100 - r70 >= 2f)
        // 满档必须顶到库上限：那才是"最糊"
        assertEquals("满档要顶到 25", BLUR_MAX_RADIUS, r100, 1e-4f)
    }

    /**
     * 缩放后撑回原尺寸时，半径的含义必须一致（3.44 的"缩图"契约）。
     *
     * 这里只验证数值关系：`blurViewRadius` 除的那个因子，和
     * `scaleBitmap` 缩的因子必须是同一个 —— 否则"缩 4 倍但按 6 折算"
     * 会让满档永远差一截（3.42 之前就是 6 / 4 混用的老毛病）。
     */
    @Test
    fun `radius divisor matches the downscale constant`() {
        // 20dp @2x = 40px；缩 4 倍后应恰好是 10
        assertEquals(10f, blurViewRadius(20f, 2f), 1e-4f)
        // 同一个因子必须能从常量推出来
        assertEquals(40f / BLUR_DOWNSCALE, blurViewRadius(20f, 2f), 1e-4f)
    }

    /**
     * 表冠灵敏度**只乘一次**（3.42）。
     *
     * `MainActivity` 那条路已经乘过了；[applyCrownSpeed] 只服务
     * Compose 的 `onRotaryScrollEvent` 分支。这条钉的是"缩放倍率符合预期"。
     */
    @Test
    fun `crown speed scales the raw amount`() {
        assertEquals(40f, applyCrownSpeed(20f, 2f), 1e-4f)
        assertEquals(2f, applyCrownSpeed(20f, 0.1f), 1e-4f)
        // 越界夹住，不能出现 0 或负数（会让表冠彻底失灵 / 反向）
        assertEquals(400f, applyCrownSpeed(40f, 999f), 1e-3f)
        assertEquals(4f, applyCrownSpeed(40f, 0f), 1e-3f)
    }

    /** 越界的强度不能算出越界的半径（负数 / 大于 1 都要夹住）。 */
    @Test
    fun `strength is clamped to 0 and 1`() {
        assertEquals(0f, blurRadiusDpFor(411, 411, -0.3f), 1e-6f)
        assertEquals(
            blurRadiusDp(411, 411),
            blurRadiusDpFor(411, 411, 1.7f),
            1e-3f
        )
    }

    /**
     * 晕染只在**三处"第一次"**播（3.44 用户把语义说到底了）。
     *
     * 用户原话：
     *
     * > "我不是说只有开屏第一次和在设置里打开模糊背景开关之后的第一次和
     * >  调整模糊程度之后的第一次才有绘制动画吗，你怎么写得每次都有。"
     */
    @Test
    fun `reveal plays for startup toggle and strength only`() {
        val nothingYet = emptySet<BackdropReason>()

        // ① 开屏第一次：播。之后再也不播。
        assertTrue("开屏第一张要播", shouldAnimateFor(BackdropReason.STARTUP, nothingYet))
        assertFalse(
            "开屏已经播过就不再播",
            shouldAnimateFor(BackdropReason.STARTUP, setOf(BackdropReason.STARTUP))
        )

        // ② 打开模糊背景开关后的第一次：播。
        assertTrue(
            "开开关第一次要播",
            shouldAnimateFor(BackdropReason.TOGGLED_ON, nothingYet)
        )
        assertTrue(
            "关掉再打开还要播（调用方清闩）",
            shouldAnimateFor(BackdropReason.TOGGLED_ON, emptySet())
        )

        // ③ 调整模糊程度后的第一次：播。
        assertTrue(
            "调程度第一次要播",
            shouldAnimateFor(BackdropReason.STRENGTH, nothingYet)
        )

        // ④ 其余一切：**永远不播**。
        assertFalse(
            "碰提示条不播",
            shouldAnimateFor(BackdropReason.OTHER, nothingYet)
        )
        assertFalse(
            "退出设置/创建函数界面不播",
            shouldAnimateFor(BackdropReason.OTHER, setOf(BackdropReason.STARTUP))
        )
    }

    /** 单调：强度越大半径越大（滑条往右拨一定更糊）。 */
    @Test
    fun `strength is monotonic`() {
        var previous = -1f
        for (i in 0..10) {
            val r = blurRadiusDpFor(411, 411, i / 10f)
            assertTrue("强度 ${i * 10}% 应该不小于上一档", r >= previous)
            previous = r
        }
    }

    /**
     * 等一帧的上限必须**短到用户察觉不出**（3.41 / 3.45）。
     *
     * 这个常量是"取图前最多等多久"。它一旦被调大（比如为了稳妥改成 500ms），
     * 表现就是"按下提示条之后要愣一下才换背景" —— 和"松手才重绘"只差一点。
     *
     * 3.45 从 120 → 200：改成轮询之后，多等不影响正确性（拿到帧立刻返回），
     * 而 200 能兜住慢机型上 RenderScript 初始化那一下的抖动。
     * 上限仍是 250 —— 超过那个数人就真的觉得卡了。
     */
    @Test
    fun `frame wait timeout stays under one glance`() {
        assertTrue("等一帧的上限必须 <= 250ms，当前 ${FRAME_WAIT_TIMEOUT_MS}ms",
            FRAME_WAIT_TIMEOUT_MS <= 250L)
        assertTrue("也不能小到一帧都等不到", FRAME_WAIT_TIMEOUT_MS >= 48L)
    }
}
