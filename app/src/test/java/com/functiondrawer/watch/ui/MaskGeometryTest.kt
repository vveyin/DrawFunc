package com.functiondrawer.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 蒙版裁剪几何的单测。
 *
 * 起因：这块逻辑连着改了四轮（clipRect / 高度裁剪 / requiredHeight / drawLayer），
 * 每次都是"看起来对、装上去不对"。这里把边界条件用纯函数钉死：
 * 顶边位置只由"屏幕高"和"抽屉可见高"两个数决定，别的都不该影响它。
 */
class MaskGeometryTest {

    @Test
    fun `抽屉完全收起时整块蒙版被裁掉`() {
        // 可见高度 0 → 顶边 = 屏幕底，矩形高度 0
        assertEquals(1000f, maskTopPx(screenH = 1000f, visibleHeightPx = 0f), 0.001f)
    }

    @Test
    fun `抽屉完全展开时蒙版铺满整屏`() {
        assertEquals(0f, maskTopPx(screenH = 1000f, visibleHeightPx = 1000f), 0.001f)
    }

    @Test
    fun `半开时顶边正好在屏幕中线`() {
        assertEquals(500f, maskTopPx(screenH = 1000f, visibleHeightPx = 500f), 0.001f)
    }

    @Test
    fun `可见高度超出屏幕时夹到 0`() {
        assertEquals(0f, maskTopPx(screenH = 1000f, visibleHeightPx = 1500f), 0.001f)
    }

    @Test
    fun `可见高度为负时夹到屏幕底`() {
        assertEquals(1000f, maskTopPx(screenH = 1000f, visibleHeightPx = -80f), 0.001f)
    }

    @Test
    fun `手表真实尺寸`() {
        // 233dp × 2.0 = 466px；抽屉拉到 42% 吸附位
        assertEquals(270.28f, maskTopPx(screenH = 466f, visibleHeightPx = 466f * 0.42f), 0.01f)
    }
}
