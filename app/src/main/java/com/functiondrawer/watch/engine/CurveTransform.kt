package com.functiondrawer.watch.engine

/**
 * 把**已经采样好的曲线**整体搬过去（用户需求 #9 的"变换"栏）。
 *
 * 为什么需要它：`Geometry.kt` 里的 `reflect` / `reflectThrough`（同包的包级函数）
 * 只能变换**点**。而"轴对称"要能作用于**函数和方程** —— 那是一条采样出来的折线
 * （[Curve.points] 是屏幕像素），所以需要"逐点变换后再换回屏幕坐标"这一步。
 *
 * 三个关键约定：
 *
 *  1. **源视口和目标视口可以不同**。曲线是用某个视口采样出来的（[PlotResult.viewport]），
 *     而结果要在**当前**视口里画。传错就会出现"对称后的曲线整体偏移/缩放"
 *     这种很难查的 bug，所以这里两个视口都是显式参数。
 *  2. **一段一段变换，不跨段**：`Plotter` 把不连续的地方拆成了不同的 [Curve]，
 *     每一段内部都是连续的。调用方对每个 [Curve] 各调一次，段与段之间不会连错线。
 *  3. **变换后跑出视野的点照样保留**（不做裁剪）：裁掉会让曲线在屏幕边缘断掉，
 *     而 `GraphCanvas` 本来就会把画到画布外的部分裁掉，交给它更简单也更准。
 *
 * 所有函数都是纯函数：不修改入参，返回新数组。
 */
object CurveTransform {

    /**
     * 逐点变换：屏幕像素 → 世界坐标（用 [srcViewport]）→ 变换 → 屏幕像素（用 [dstViewport]）。
     *
     * [transform] 返回的点如果是非有限值（NaN/∞），对应的点会被写成 NaN，
     * 画出来就是断开 —— 比静默画到 (0,0) 强（那会出现一条莫名其妙的线）。
     */
    fun mapPoints(
        src: FloatArray,
        srcViewport: Viewport,
        dstViewport: Viewport,
        transform: (GPoint) -> GPoint
    ): FloatArray {
        val out = FloatArray(src.size)
        var i = 0
        while (i + 1 < src.size) {
            val world = GPoint(
                srcViewport.toWorldX(src[i].toDouble()),
                srcViewport.toWorldY(src[i + 1].toDouble())
            )
            val mapped = transform(world)
            if (mapped.isFinite) {
                out[i] = dstViewport.toScreenX(mapped.x)
                out[i + 1] = dstViewport.toScreenY(mapped.y)
            } else {
                out[i] = Float.NaN
                out[i + 1] = Float.NaN
            }
            i += 2
        }
        return out
    }

    /** 关于一条直线做轴对称（函数/方程的整体镜像）。 */
    fun reflectOverLine(
        src: FloatArray,
        srcViewport: Viewport,
        dstViewport: Viewport,
        axis: GLine
    ): FloatArray = mapPoints(src, srcViewport, dstViewport) { p -> reflect(p, axis) }

    /** 关于一个点做中心对称（180° 旋转）。 */
    fun reflectThrough(
        src: FloatArray,
        srcViewport: Viewport,
        dstViewport: Viewport,
        center: GPoint
    ): FloatArray = mapPoints(src, srcViewport, dstViewport) { p ->
        reflectThrough(p, center)
    }

    /** 平移（用户拖动、或"整体挪一下"时用；单位是世界坐标）。 */
    fun translate(
        src: FloatArray,
        srcViewport: Viewport,
        dstViewport: Viewport,
        dx: Double,
        dy: Double
    ): FloatArray = mapPoints(src, srcViewport, dstViewport) { p -> GPoint(p.x + dx, p.y + dy) }

    /**
     * 一条曲线变换之后，还有没有可能落在视野里。
     *
     * 用来决定"要不要把这个对象加进列表"：完全在视野外的镜像（比如对称轴在很远的地方）
     * 加进去只会让列表变长、用户却看不见任何东西。
     * 判定留了 [marginPx] 的余量（手一拖就可能进来了）。
     */
    fun isVisible(
        mapped: FloatArray,
        dstViewport: Viewport,
        marginPx: Float = 24f
    ): Boolean {
        var i = 0
        while (i + 1 < mapped.size) {
            val x = mapped[i]
            val y = mapped[i + 1]
            if (x.isFinite() && y.isFinite()) {
                if (x >= -marginPx && x <= dstViewport.widthPx + marginPx &&
                    y >= -marginPx && y <= dstViewport.heightPx + marginPx
                ) {
                    return true
                }
            }
            i += 2
        }
        return false
    }
}
