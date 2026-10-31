package com.functiondrawer.watch.engine

import kotlin.math.abs

/**
 * 屏幕空间的仿射增量：把「视口 A 下的屏幕坐标」映射成「视口 B 下的屏幕坐标」。
 *
 * 关系是 `p_B = k · p_A + (tx, ty)`。
 *
 * 为什么需要它：手势中网格按当前视口实时重画，而采样结果里的曲线是
 * **上一次采样那个视口**下的像素坐标。不做处理就会出现
 * 「拖动时函数图像跟不上坐标系」的滞后感。有了这个增量，
 * 只要给画布套一个矩阵就能把曲线整体带过去 —— **O(1)，不用重采样**。
 */
data class ScreenDelta(val k: Double, val tx: Double, val ty: Double) {

    /** 是否近似恒等（可以走不做变换的快路径）。 */
    fun isIdentity(): Boolean =
        abs(k - 1.0) < 1e-9 && abs(tx) < 1e-6 && abs(ty) < 1e-6
}

/**
 * 世界坐标 ↔ 屏幕像素 的映射。
 *
 * 设计要点：
 *  * 不可变（data class + copy），这样能直接当 Compose state 用，
 *    手势里每次改动都产生新实例，重组判断天然正确。
 *  * 单一 [unitsPerPixel] 同时管两个轴 → 等比例，圆永远是圆、
 *    正方体的对角线是 45°。手表屏幕是正方形/圆形，等比例是唯一合理的选择。
 */
data class Viewport(
    val widthPx: Float,
    val heightPx: Float,
    /** 屏幕中心对应的世界坐标 */
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    /** 一个屏幕像素代表多少世界单位。越大越"缩小"。 */
    val unitsPerPixel: Double = 1.0
) {

    val worldLeft: Double get() = centerX - widthPx / 2.0 * unitsPerPixel
    val worldRight: Double get() = centerX + widthPx / 2.0 * unitsPerPixel
    val worldBottom: Double get() = centerY - heightPx / 2.0 * unitsPerPixel
    val worldTop: Double get() = centerY + heightPx / 2.0 * unitsPerPixel

    fun toScreenX(worldX: Double): Float =
        ((worldX - centerX) / unitsPerPixel).toFloat() + widthPx / 2f

    fun toScreenY(worldY: Double): Float =
        heightPx / 2f - ((worldY - centerY) / unitsPerPixel).toFloat()

    fun toWorldX(screenX: Double): Double =
        (screenX - widthPx / 2.0) * unitsPerPixel + centerX

    fun toWorldY(screenY: Double): Double =
        (heightPx / 2.0 - screenY) * unitsPerPixel + centerY

    /** 视口宽度（世界单位）。 */
    val worldWidth: Double get() = widthPx * unitsPerPixel

    /** 视口高度（世界单位）。 */
    val worldHeight: Double get() = heightPx * unitsPerPixel

    /**
     * 以某个屏幕点为焦点缩放。
     *
     * factor > 1 表示放大（同样世界里的一格占更多像素，unitsPerPixel 变小）。
     * 焦点下的世界坐标在缩放前后保持不动，这才是手感对的做法。
     */
    fun zoomBy(factor: Double, focusPx: Float, focusPy: Float): Viewport {
        if (factor <= 0.0 || !factor.isFinite()) return this
        val wx = toWorldX(focusPx.toDouble())
        val wy = toWorldY(focusPy.toDouble())
        // 放大的上限跟着计算精度走：3 位最多到 1e-3、8 位到 1e-8、16 位到 1e-16。
        val next = (unitsPerPixel / factor)
            .coerceIn(Calc.precision.minUnitsPerPixel, MAX_UNITS_PER_PIXEL)
        if (next == unitsPerPixel) return this
        return copy(
            centerX = wx - (focusPx - widthPx / 2.0) * next,
            centerY = wy + (focusPy - heightPx / 2.0) * next,
            unitsPerPixel = next
        )
    }

    /** 拖动平移：手指移动 (dx, dy) 像素。 */
    fun panBy(dxPx: Float, dyPx: Float): Viewport = copy(
        centerX = centerX - dxPx * unitsPerPixel,
        centerY = centerY + dyPx * unitsPerPixel
    )

    /** 保留当前缩放，只换尺寸（设备旋转/窗口变化时用）。 */
    fun resized(width: Float, height: Float): Viewport =
        copy(widthPx = width, heightPx = height)

    /**
     * 算出「本视口下的屏幕坐标 → [target] 视口下的屏幕坐标」所需的仿射增量。
     *
     * 推导（sx 为屏幕 x，upp 为每像素世界单位）：
     *   sx_A = (wx - cx_A)/upp_A + W/2
     *   sx_B = (wx - cx_B)/upp_B + W/2
     * 消掉 wx：
     *   sx_B = k·sx_A + W/2·(1-k) + (cx_A-cx_B)/upp_B,   k = upp_A/upp_B
     * y 轴同理，但因为屏幕 y 向下，符号要反过来。
     *
     * 注意：要求两者尺寸相同（画布尺寸没变）。尺寸变了说明视口已被替换，
     * 应该等下一次采样，而不是拿错的增量硬撑 —— 调用方负责判断。
     */
    fun screenDeltaTo(target: Viewport): ScreenDelta {
        if (unitsPerPixel <= 0.0 || target.unitsPerPixel <= 0.0) {
            return ScreenDelta(1.0, 0.0, 0.0)
        }
        val k = unitsPerPixel / target.unitsPerPixel
        val w = target.widthPx.toDouble()
        val h = target.heightPx.toDouble()
        val tx = w / 2.0 * (1.0 - k) + (centerX - target.centerX) / target.unitsPerPixel
        val ty = h / 2.0 * (1.0 - k) - (centerY - target.centerY) / target.unitsPerPixel
        return ScreenDelta(k, tx, ty)
    }

    /** 当前的数值刻度步长，给网格线用。 */
    val gridStep: Double
        get() {
            val target = unitsPerPixel * 60.0 // 大约每 60 像素一条线
            if (target <= 0.0 || !target.isFinite()) return 1.0
            val exp = kotlin.math.floor(kotlin.math.log10(target))
            val base = Math.pow(10.0, exp)
            val norm = target / base
            val mult = when {
                norm <= 1.5 -> 1.0
                norm <= 3.5 -> 2.0
                norm <= 7.5 -> 5.0
                else -> 10.0
            }
            return mult * base
        }

    companion object {
        /**
         * 理论上的最小缩放（保留给调用方做"值是否合法"的粗判）。
         *
         * 真正生效的**放大上限**随计算精度变，一律读 [Calc.precision.minUnitsPerPixel]。
         */
        const val MIN_UNITS_PER_PIXEL = 1e-9
        const val MAX_UNITS_PER_PIXEL = 1e9

        /** 让 x 轴正好覆盖 [minX, maxX] 的初始视口。 */
        fun forRange(width: Float, height: Float, minX: Double, maxX: Double): Viewport {
            val span = abs(maxX - minX)
            val upp = if (width > 0f && span > 0) span / width else 1.0
            return Viewport(
                widthPx = width,
                heightPx = height,
                centerX = (minX + maxX) / 2.0,
                centerY = 0.0,
                unitsPerPixel = upp.coerceIn(Calc.precision.minUnitsPerPixel, MAX_UNITS_PER_PIXEL)
            )
        }
    }
}
