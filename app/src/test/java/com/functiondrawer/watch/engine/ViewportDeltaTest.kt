package com.functiondrawer.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [Viewport.screenDeltaTo] 的正确性验证。
 *
 * 这段数学是「拖动时函数图像跟不上坐标系」那个 bug 的修复核心：
 * 采样结果里的曲线是**旧视口**下的像素坐标，必须按增量变换到新视口才能与网格对齐。
 * 它被放在引擎层（而不是 UI 层）就是为了能被这里测到 —— UI 里的数学测不了。
 *
 * 用的主要是**性质测试**：随机生成大量视口，直接验证
 * 「用增量变换旧屏幕坐标」和「用新视口直接换算世界点的屏幕位置」结果一致。
 * 这是这段公式的定义本身，也是唯一需要保证的性质。
 *
 * ## 关于容差
 *
 * 引擎的屏幕坐标是 `Float`（要直接喂给 Compose 的 `Path`），不是 `Double`。
 * 在 |坐标| ~ 500、缩放系数 k 最大 ~40 的情况下，一次 `Float` 舍入
 * （相对精度约 1.2e-7）会被 k 放大到 1e-3 量级。所以宽范围随机测试用 0.05 ——
 * 这仍然能抓住任何**真正的公式错误**：符号写反、少乘一项之类的误差
 * 都是几十上百的量级，不可能藏在 0.05 里。
 *
 * 另外单独有一组「真实手势」测试（k ≈ 1、位移几十像素），
 * 那才是这个函数在真机上唯一会被用到的工况，那里用的是**紧容差**，
 * 确保它不是"大概对"。
 */
class ViewportDeltaTest {

    private val w = 466f
    private val h = 466f

    // ------------------------------------------------------------------
    // 真实工况：手势一帧之内的视口变化（这是唯一会在真机上发生的场景）
    // ------------------------------------------------------------------

    @Test
    fun `手势增量：平移加缩放都必须精确到小数点后四位`() {
        val rnd = Random(7L)
        val base = Viewport.forRange(w, h, -10.0, 10.0)
        repeat(300) {
            // 一帧之内的典型变化：平移 ±40px，缩放 0.85~1.18 倍
            val a = base.panBy((rnd.nextDouble() * 80 - 40).toFloat(), (rnd.nextDouble() * 80 - 40).toFloat())
            val b = a.panBy((rnd.nextDouble() * 80 - 40).toFloat(), (rnd.nextDouble() * 80 - 40).toFloat())
                .zoomBy(0.85 + rnd.nextDouble() * 0.33, 233f, 233f)

            val delta = a.screenDeltaTo(b)

            val wx = a.centerX + (rnd.nextDouble() - 0.5) * a.worldWidth
            val wy = a.centerY + (rnd.nextDouble() - 0.5) * a.worldHeight
            val sxA = a.toScreenX(wx).toDouble()
            val syA = a.toScreenY(wy).toDouble()
            val sxB = b.toScreenX(wx).toDouble()
            val syB = b.toScreenY(wy).toDouble()

            assertEquals("x 偏差过大 (a=$a b=$b)", sxB, delta.k * sxA + delta.tx, 1e-4)
            assertEquals("y 偏差过大 (a=$a b=$b)", syB, delta.k * syA + delta.ty, 1e-4)
        }
    }

    // ------------------------------------------------------------------
    // 宽范围性质测试：覆盖很极端的缩放比，验证公式本身没有结构性错误
    // ------------------------------------------------------------------

    @Test
    fun `任意两个视口之间，增量变换都要和新视口直接换算一致`() {
        val rnd = Random(20251003L)
        var checked = 0
        repeat(500) {
            val a = Viewport(
                widthPx = w,
                heightPx = h,
                centerX = (rnd.nextDouble() - 0.5) * 40.0,
                centerY = (rnd.nextDouble() - 0.5) * 40.0,
                unitsPerPixel = Math.pow(0.4, rnd.nextDouble() * 6 - 3) // 1e-3 ~ 1e3 量级
            )
            val b = Viewport(
                widthPx = w,
                heightPx = h,
                centerX = (rnd.nextDouble() - 0.5) * 40.0,
                centerY = (rnd.nextDouble() - 0.5) * 40.0,
                unitsPerPixel = Math.pow(0.4, rnd.nextDouble() * 6 - 3)
            )
            val delta = a.screenDeltaTo(b)

            // 取一个落在旧视口附近的世界点，别跑到屏幕外太远的地方
            val wx = a.centerX + (rnd.nextDouble() - 0.5) * a.worldWidth
            val wy = a.centerY + (rnd.nextDouble() - 0.5) * a.worldHeight

            val sxA = a.toScreenX(wx).toDouble()
            val syA = a.toScreenY(wy).toDouble()
            val sxB = b.toScreenX(wx).toDouble()
            val syB = b.toScreenY(wy).toDouble()

            assertEquals("x 不匹配 (a=$a b=$b)", sxB, delta.k * sxA + delta.tx, 0.05)
            assertEquals("y 不匹配 (a=$a b=$b)", syB, delta.k * syA + delta.ty, 0.05)
            checked++
        }
        assertEquals(500, checked)
    }

    // ------------------------------------------------------------------
    // 几个能一眼看懂对错的具体场景
    // ------------------------------------------------------------------

    @Test
    fun `纯平移时只产生位移，不带缩放`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        val b = a.panBy(37f, -21f)
        val d = a.screenDeltaTo(b)

        assertEquals(1.0, d.k, 1e-12)
        // 手指右移 37px：旧坐标要 +37 才能落到新屏幕上
        assertEquals(37.0, d.tx, 1e-6)
        // 手指上移 21px：旧坐标要 -21
        assertEquals(-21.0, d.ty, 1e-6)
    }

    @Test
    fun `以某点为焦点缩放时，该点的屏幕坐标必须钉住不动`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        val fx = 120f
        val fy = 380f
        val b = a.zoomBy(2.5, fx, fy)
        val d = a.screenDeltaTo(b)

        // 焦点处的世界坐标在缩放前后不变 → 它的屏幕坐标也应不变
        assertEquals(fx.toDouble(), d.k * fx + d.tx, 1e-6)
        assertEquals(fy.toDouble(), d.k * fy + d.ty, 1e-6)
    }

    @Test
    fun `缩放系数等于单位每像素之比的倒数关系`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        val b = a.zoomBy(4.0, 0f, 0f)
        val d = a.screenDeltaTo(b)

        assertEquals(a.unitsPerPixel / b.unitsPerPixel, d.k, 1e-12)
        // zoomBy(4) 是**放大** 4 倍 → 旧屏幕上的 1 个单位要变成 4 个 → k = 4
        assertEquals(4.0, d.k, 1e-12)
        assertTrue("放大时 unitsPerPixel 应该变小", b.unitsPerPixel < a.unitsPerPixel)
    }

    @Test
    fun `同一个视口的增量是恒等的`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        assertTrue(a.screenDeltaTo(a).isIdentity())
        assertTrue(a.screenDeltaTo(a.copy()).isIdentity())
    }

    @Test
    fun `视口变了就不是恒等变换`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        assertFalse(a.screenDeltaTo(a.panBy(1f, 0f)).isIdentity())
        assertFalse(a.screenDeltaTo(a.zoomBy(1.1, 233f, 233f)).isIdentity())
    }

    @Test
    fun `非法视口不会产生 NaN 增量`() {
        val a = Viewport.forRange(w, h, -10.0, 10.0)
        val bad = Viewport(w, h, 0.0, 0.0, 0.0)
        val d = a.screenDeltaTo(bad)
        assertTrue(d.k.isFinite() && d.tx.isFinite() && d.ty.isFinite())
    }
}
