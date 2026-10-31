package com.functiondrawer.watch.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

/**
 * 把函数采样成**屏幕坐标折线**。
 *
 * 四种模式用的是三套不同的算法，因为它们的数学性质完全不同：
 *
 *  * EXPLICIT  y=f(x)  —— 沿屏幕每一列取一个样本。竖直渐近线靠"相邻样本
 *                         跳变过大就断线"来处理，否则 1/x 会被画成一条假的竖线。
 *  * PARAMETRIC/POLAR  —— 在参数 t / 极角 θ 上先均匀取样，再对"屏幕上看起来
 *                         不够直"的区间递归二分。曲线是旋转/自交的，只能在
 *                         屏幕空间判断该不该细分。
 *  * IMPLICIT  F(x,y)=0 —— 网格 + marching squares，并对产生线段的单元递归
 *                         细分（细分时重新求值，而不是线性插值），这样
 *                         x²+y²=1 这种细线不会被网格粒度切碎。
 *
 * 所有输出都已经换算到屏幕像素并做了范围钳制，UI 只要画线就行。
 */
object Plotter {

    fun plot(
        functions: List<PlotFunction>,
        viewport: Viewport,
        quality: Quality = Quality.NORMAL,
        /**
         * 全局默认角度单位，true = 三角函数按「度」理解。
         *
         * 只作为**默认值**：某条曲线自己带了 [PlotFunction.degrees] 就以它为准，
         * 所以同一次 plot 里可以一条走弧度、另一条走度制。
         *
         * 这里只是"转发"：真正的换算发生在 Expression 建树阶段，
         * 采样线程不需要读任何共享状态。默认 false = 弧度，与改动前完全一致。
         */
        degrees: Boolean = false,
        /**
         * 可绑定参数 `a b c d` 的取值。
         *
         * 默认 [ParameterSet.DEFAULT]（四个都是临时占位的 1.0）——
         * 所以 UI 现在一行都不用改：`a*x+b` 直接就是 `x+1`。
         * 以后接了参数滑块，只要把它传进来，采样算法完全不用动
         * （和 [degrees] 一样，是"转发"，真正的取值在建树时折成常量）。
         */
        parameters: ParameterSet = ParameterSet.DEFAULT,
        /**
         * 可以**被别的函数调用**的那些用户函数（见 [CallableSet]）。
         *
         * 这一条是用户报的 bug 的正解：他在 `b(x)` 里写了 `2a(x)`（`a(x)=sin(x)`），
         * 却画出了 `y=2x` —— 因为 `a` 当时只是可绑定参数（缺省 1.0）。
         * 把界面上的函数列表转成一张表传进来（[CallableSet.ofFunctions]），
         * `a(x)` 就会真的去调用 `a`。
         *
         * 默认是**空表**：等于"这个功能没启用"，行为与加它之前逐位一致
         * （既有测试全走这条默认路径，见 `PlotterTest.没传 callables 时 a(x) 还是老行为`）。
         * 所以 UI 必须显式把表传进来，否则不传 = `2a(x)` 还是老的 `2x`。
         *
         * 和 [degrees] / [parameters] 一样只是"转发"：真正的解析/编译发生在
         * [Expression.compile]，采样线程不需要读任何共享状态。
         */
        callables: CallableSet = CallableSet.EMPTY
    ): PlotResult {
        if (viewport.widthPx <= 1f || viewport.heightPx <= 1f) return PlotResult.EMPTY
        val curves = ArrayList<Curve>()
        val errors = HashMap<Long, String>()
        for (fn in functions) {
            if (!fn.visible) continue
            val deg = fn.degrees ?: degrees
            try {
                when (fn.mode) {
                    PlotMode.EXPLICIT -> sampleExplicit(fn, viewport, quality, deg, parameters, callables, curves)
                    PlotMode.POLAR -> samplePolar(fn, viewport, quality, deg, parameters, callables, curves)
                    PlotMode.PARAMETRIC -> sampleParametric(fn, viewport, quality, deg, parameters, callables, curves)
                    PlotMode.IMPLICIT -> sampleImplicit(fn, viewport, quality, deg, parameters, callables, curves)
                }
            } catch (e: ExpressionException) {
                errors[fn.id] = buildString {
                    append(e.message ?: "表达式错误")
                    if (e.position in 1..fn.src1.length) append("（第 ${e.position + 1} 个字符附近）")
                }
            } catch (e: Throwable) {
                errors[fn.id] = e.message ?: "这个函数没画出来"
            }
        }
        // 把算这批曲线时用的视口一并带出去：
        // 手势中网格按新视口实时画，曲线要靠这个增量做仿射变换才不会滞后。
        return PlotResult(curves, errors, viewport)
    }

    // ------------------------------------------------------------------
    // 输入预处理：让用户可以直接照抄课本写法
    // ------------------------------------------------------------------

    private val POLAR_PREFIX = Regex("^\\s*(r|ρ)\\s*=\\s*", RegexOption.IGNORE_CASE)
    private val PARAM_X_PREFIX =
        Regex("^\\s*x\\s*(\\(\\s*t\\s*\\))?\\s*=\\s*", RegexOption.IGNORE_CASE)
    private val PARAM_Y_PREFIX =
        Regex("^\\s*y\\s*(\\(\\s*t\\s*\\))?\\s*=\\s*", RegexOption.IGNORE_CASE)

    private fun hasTopLevelEquals(s: String): Boolean {
        var depth = 0
        for (c in s) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '=' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun explicitBody(src: String): String {
        // 剥 `y=` / `f(x)=` 前缀的那份正则挪到了 [ExplicitSource]：
        // 现在 [CallableSet.ofFunctions] 装函数体时也要剥同一个前缀，只留一份实现。
        val body = ExplicitSource.strip(src)
        if (body.isEmpty()) throw ExpressionException("还没写表达式", 0)
        if (hasTopLevelEquals(body)) {
            throw ExpressionException("y=f(x) 模式里不能有等号，要画隐函数请切到 F(x,y) 模式", 0)
        }
        return body
    }

    // ------------------------------------------------------------------
    // 1) 显函数 y = f(x)
    // ------------------------------------------------------------------

    private fun sampleExplicit(
        fn: PlotFunction,
        vp: Viewport,
        q: Quality,
        degrees: Boolean,
        parameters: ParameterSet,
        callables: CallableSet,
        out: MutableList<Curve>
    ) {
        val f = Expression.compile(
            explicitBody(fn.src1), PlotMode.EXPLICIT.variables, degrees, parameters, callables
        )
        val w = vp.widthPx
        val h = vp.heightPx

        // 屏幕外的 y 一律钳到 [-4h, 5h]：
        // 既不丢陡峭线段的斜率，又不会让坐标飞到 float 精度之外。
        val lo = -4.0 * h
        val hi = 5.0 * h
        // 相邻两列跳变超过 2 倍屏高，就认定中间有竖直渐近线，断线。
        val breakJump = 2.0 * h

        val b = PolyBuilder()
        val args = DoubleArray(1)
        var prevY = Double.NaN
        var px = 0.0
        while (px <= w) {
            args[0] = vp.toWorldX(px)
            // 按当前计算精度归零：绝对值小于精度阈值的 y 一律当作 0
            val y = Calc.snap(f.evalArray(args))
            if (y.isFinite()) {
                var sy = vp.toScreenY(y).toDouble()
                if (sy < lo) sy = lo
                if (sy > hi) sy = hi
                if (prevY.isFinite() && abs(sy - prevY) > breakJump) b.endLine()
                b.add(px.toFloat(), sy.toFloat())
                prevY = sy
            } else {
                // NaN / ±Inf：函数在这里没定义，断开
                b.endLine()
                prevY = Double.NaN
            }
            px += q.explicitStepPx
        }
        b.finish()
        for (pts in b.take()) out.add(Curve(fn.id, fn.colorIndex, pts))
    }

    // ------------------------------------------------------------------
    // 2) 极坐标 r = f(θ)
    // ------------------------------------------------------------------

    private fun samplePolar(
        fn: PlotFunction,
        vp: Viewport,
        q: Quality,
        degrees: Boolean,
        parameters: ParameterSet,
        callables: CallableSet,
        out: MutableList<Curve>
    ) {
        val body = POLAR_PREFIX.replace(fn.src1, "").trim()
        if (body.isEmpty()) throw ExpressionException("还没写表达式", 0)
        val f = Expression.compile(body, PlotMode.POLAR.variables, degrees, parameters, callables)

        val limit = 64.0 * max(vp.widthPx, vp.heightPx)
        // θ 和 t 都绑定到同一个值，两种写法都能用
        val args = DoubleArray(2)
        val pf = PointFn { t, p ->
            args[0] = t
            args[1] = t
            val r = f.evalArray(args)
            if (!r.isFinite()) {
                p.valid = false
            } else {
                // DEG 模式下 θ 是按"度"理解的（表达式那边就是按度算的），
                // 所以这里必须先换成弧度再喂给 cos/sin ——
                // 否则会出现"表达式按度、几何按弧度"，画出来的图是错的。
                val theta = if (degrees) t * MathFns.DEG_TO_RAD else t
                // 两个分量都按精度归零（和显函数的 y 同一条规则）
                val wx = Calc.snap(r * cos(theta))
                val wy = Calc.snap(r * sin(theta))
                fillScreen(vp, wx, wy, limit, p)
            }
        }
        val b = PolyBuilder()
        // 1.3.1：`R` / `R⁺∪{0}` / `R⁻∪{0}` 这三个预设的量程是**非有限**的（±∞）。
        // 真·无限没法采样，但"手划到哪就渲染到哪"可以做到：屏幕能看见的最远半径
        // 决定了 θ 要走到哪。于是这里按**当前视口**反推一个有限区间 ——
        // 缩放 / 拖动之后重新算，曲线就会继续往外长。
        val (renderMin, renderMax) = polarRenderRange(fn.tMin, fn.tMax, vp, f, degrees)
        adaptiveCurve(renderMin, renderMax, q, pf, b)
        b.finish()
        for (pts in b.take()) out.add(Curve(fn.id, fn.colorIndex, pts))
    }

    /**
     * 极坐标的**可见**量程：把非有限的 [tMin] / [tMax] 换成当前视口下"够用"的有限区间。
     *
     * 做法不扫描整条曲线（那太贵），只用 2π / 4π 两个探针估一下增长速度：
     *   * 半径有界（`1+sin θ` 这种周期曲线）→ 两圈足够，再多是同一个形状；
     *   * 半径线性 / 超线性增长（螺线）→ 按 `r(4π)` 反推出"半径到视口最远点"的 θ，
     *     再乘一点余量（宁多勿少，跑出屏幕的点会被 `fillScreen` 断线，不会画坏）。
     *
     * 负半轴用同样的探针（`θ < 0` 那一支）。
     */
    private fun polarRenderRange(
        tMin: Double,
        tMax: Double,
        vp: Viewport,
        f: Expression,
        degrees: Boolean
    ): Pair<Double, Double> {
        // 视口四角到世界原点的最远距离 = 屏幕上能看见的最大半径
        val w = vp.widthPx.toDouble()
        val h = vp.heightPx.toDouble()
        var rMax = 0.0
        for (x in doubleArrayOf(0.0, w)) {
            for (y in doubleArrayOf(0.0, h)) {
                val r = hypot(vp.toWorldX(x), vp.toWorldY(y))
                if (r.isFinite() && r > rMax) rMax = r
            }
        }
        // 视口退化时退回两圈（至少不会什么都不画）
        if (!rMax.isFinite() || rMax <= 0.0) {
            val period = if (degrees) 360.0 else PI
            return (if (tMin.isFinite()) tMin else -2 * period) to
                (if (tMax.isFinite()) tMax else 2 * period)
        }

        val period = if (degrees) 360.0 else 2 * PI
        val maxExtent = if (degrees) 360.0 * 2048.0 else 4096.0 * PI
        val args = DoubleArray(2)

        fun radiusAt(theta: Double): Double {
            args[0] = theta
            args[1] = theta
            val r = f.evalArray(args)
            return if (r.isFinite()) abs(r) else Double.POSITIVE_INFINITY
        }

        /** 某一支（正 / 负）要走到多远。 */
        fun extent(sign: Double): Double {
            val r1 = radiusAt(sign * period)
            val r2 = radiusAt(sign * 2 * period)
            val grow = if (r1.isFinite() && r1 > 1e-9) r2 / r1 else 1.0
            // 有界 / 周期：两圈就够（`r(2π)` 到 `r(4π)` 没怎么长大）
            if (!r2.isFinite() || grow <= 1.2) return sign * 2 * period
            // 螺线：按增长率反推"半径到 rMax"的位置，余量 1.2
            val needed = (2 * period) * (rMax / max(r2, 1e-12)) * 1.2
            val capped = needed.coerceIn(2 * period, maxExtent)
            return sign * capped
        }

        val lo = if (tMin.isFinite()) tMin else extent(-1.0)
        val hi = if (tMax.isFinite()) tMax else extent(1.0)
        return lo to hi
    }

    // ------------------------------------------------------------------
    // 3) 参数方程 (x(t), y(t))
    // ------------------------------------------------------------------

    private fun sampleParametric(
        fn: PlotFunction,
        vp: Viewport,
        q: Quality,
        degrees: Boolean,
        parameters: ParameterSet,
        callables: CallableSet,
        out: MutableList<Curve>
    ) {
        val xs = PARAM_X_PREFIX.replace(fn.src1, "").trim()
        val ys = PARAM_Y_PREFIX.replace(fn.src2, "").trim()
        if (xs.isEmpty()) throw ExpressionException("缺少 x(t)", 0)
        if (ys.isEmpty()) throw ExpressionException("缺少 y(t)", 0)

        val fx = Expression.compile(xs, PlotMode.PARAMETRIC.variables, degrees, parameters, callables)
        val fy = Expression.compile(ys, PlotMode.PARAMETRIC.variables, degrees, parameters, callables)

        val limit = 64.0 * max(vp.widthPx, vp.heightPx)
        val args = DoubleArray(1)
        val pf = PointFn { t, p ->
            args[0] = t
            // 参数方程的两个分量同样按精度归零
            val wx = Calc.snap(fx.evalArray(args))
            val wy = Calc.snap(fy.evalArray(args))
            if (!wx.isFinite() || !wy.isFinite()) {
                p.valid = false
            } else {
                fillScreen(vp, wx, wy, limit, p)
            }
        }
        val b = PolyBuilder()
        adaptiveCurve(fn.tMin, fn.tMax, q, pf, b)
        b.finish()
        for (pts in b.take()) out.add(Curve(fn.id, fn.colorIndex, pts))
    }

    private fun fillScreen(vp: Viewport, wx: Double, wy: Double, limit: Double, p: Pt) {
        val sx = vp.toScreenX(wx).toDouble()
        val sy = vp.toScreenY(wy).toDouble()
        if (abs(sx) > limit || abs(sy) > limit) {
            // 远远跑出屏幕：当作无效点断线。反正这一段的可见部分已经画完了。
            p.valid = false
        } else {
            p.x = sx
            p.y = sy
            p.valid = true
        }
    }

    // ------------------------------------------------------------------
    // 4) 隐函数 F(x, y) = 0
    // ------------------------------------------------------------------

    /** 在屏幕像素坐标系里求值的小包装，避免热路径里出现装箱。 */
    private class ImplicitField(private val f: Expression, private val vp: Viewport) {
        private val args = DoubleArray(2)

        fun at(px: Double, py: Double): Double {
            args[0] = vp.toWorldX(px)
            args[1] = vp.toWorldY(py)
            return f.evalArray(args)
        }
    }

    private fun sampleImplicit(
        fn: PlotFunction,
        vp: Viewport,
        q: Quality,
        degrees: Boolean,
        parameters: ParameterSet,
        callables: CallableSet,
        out: MutableList<Curve>
    ) {
        val src = fn.src1.trim()
        if (src.isEmpty()) throw ExpressionException("还没写表达式", 0)
        // 支持 "x^2+y^2 = 1" 这种直接照抄的写法
        val f = Expression.compileEquation(src, PlotMode.IMPLICIT.variables, degrees, parameters, callables)
        val field = ImplicitField(f, vp)

        val nx = ceil(vp.widthPx / q.cellPx).toInt().coerceIn(4, 400)
        val ny = ceil(vp.heightPx / q.cellPx).toInt().coerceIn(4, 400)
        // 网格步长用 Double：march() 全程在 Double 下做插值，
        // 免得 Float 精度在细分到第 2、3 层时把交点算歪。
        val sx = vp.widthPx.toDouble() / nx
        val sy = vp.heightPx.toDouble() / ny

        val b = PolyBuilder()
        for (j in 0 until ny) {
            val y0 = j * sy
            val y1 = y0 + sy
            for (i in 0 until nx) {
                val x0 = i * sx
                val x1 = x0 + sx
                march(
                    field, x0, y0, x1, y1,
                    field.at(x0, y0), field.at(x1, y0),
                    field.at(x1, y1), field.at(x0, y1),
                    0, q.implicitDepth, b
                )
            }
        }
        b.finish()
        for (pts in b.take()) out.add(Curve(fn.id, fn.colorIndex, pts))
    }

    /**
     * 对一个单元做 marching squares；如果这一格确实有轮廓线、且还有细分额度，
     * 就切成 2x2 重新求值再递归 —— 这是把曲线画平滑的关键。
     */
    private fun march(
        field: ImplicitField,
        x0: Double, y0: Double, x1: Double, y1: Double,
        v00: Double, v10: Double, v11: Double, v01: Double,
        depth: Int, maxDepth: Int, b: PolyBuilder
    ) {
        // 有任何一个角求值失败（1/x 那种定义域空洞），这一格直接放弃
        if (!v00.isFinite() || !v10.isFinite() || !v11.isFinite() || !v01.isFinite()) return

        val case = (if (v00 > 0.0) 1 else 0) or
            (if (v10 > 0.0) 2 else 0) or
            (if (v11 > 0.0) 4 else 0) or
            (if (v01 > 0.0) 8 else 0)
        if (case == 0 || case == 15) return

        if (depth < maxDepth) {
            val xm = 0.5 * (x0 + x1)
            val ym = 0.5 * (y0 + y1)
            val vM = field.at(xm, ym)
            val vB = field.at(xm, y0)
            val vR = field.at(x1, ym)
            val vT = field.at(xm, y1)
            val vL = field.at(x0, ym)
            march(field, x0, y0, xm, ym, v00, vB, vM, vL, depth + 1, maxDepth, b)
            march(field, xm, y0, x1, ym, vB, v10, vR, vM, depth + 1, maxDepth, b)
            march(field, xm, ym, x1, y1, vM, vR, v11, vT, depth + 1, maxDepth, b)
            march(field, x0, ym, xm, y1, vL, vM, vT, v01, depth + 1, maxDepth, b)
            return
        }

        // 四条边上的零点位置（线性插值）
        // e0: (x0,y0)-(x1,y0)   e1: (x1,y0)-(x1,y1)   e2: (x1,y1)-(x0,y1)   e3: (x0,y1)-(x0,y0)
        val t0 = frac(v00, v10)
        val t1 = frac(v10, v11)
        val t2 = frac(v11, v01)
        val t3 = frac(v01, v00)
        val e0x = x0 + (x1 - x0) * t0; val e0y = y0
        val e1x = x1; val e1y = y0 + (y1 - y0) * t1
        val e2x = x1 + (x0 - x1) * t2; val e2y = y1
        val e3x = x0; val e3y = y1 + (y0 - y1) * t3

        fun seg(ax: Double, ay: Double, bx: Double, by: Double) {
            b.add(ax.toFloat(), ay.toFloat())
            b.add(bx.toFloat(), by.toFloat())
            b.endLine()
        }

        when (case) {
            1, 14 -> seg(e3x, e3y, e0x, e0y)
            2, 13 -> seg(e0x, e0y, e1x, e1y)
            3, 12 -> seg(e1x, e1y, e3x, e3y)
            4, 11 -> seg(e1x, e1y, e2x, e2y)
            6, 9 -> seg(e0x, e0y, e2x, e2y)
            7, 8 -> seg(e2x, e2y, e3x, e3y)
            5 -> {
                // 鞍点：用中心值的符号决定两条线怎么连
                val center = (v00 + v10 + v11 + v01) / 4.0
                if (center > 0.0) {
                    seg(e0x, e0y, e1x, e1y); seg(e2x, e2y, e3x, e3y)
                } else {
                    seg(e0x, e0y, e3x, e3y); seg(e1x, e1y, e2x, e2y)
                }
            }
            10 -> {
                val center = (v00 + v10 + v11 + v01) / 4.0
                if (center > 0.0) {
                    seg(e0x, e0y, e3x, e3y); seg(e1x, e1y, e2x, e2y)
                } else {
                    seg(e0x, e0y, e1x, e1y); seg(e2x, e2y, e3x, e3y)
                }
            }
        }
    }

    /** 线性插值参数：a→b 上值为 0 的位置（0 表示在 a 端，1 表示在 b 端）。 */
    private fun frac(a: Double, b: Double): Double {
        val d = a - b
        if (d == 0.0 || !d.isFinite()) return 0.5
        val t = a / d
        return if (t.isFinite()) t.coerceIn(0.0, 1.0) else 0.5
    }

    // ------------------------------------------------------------------
    // 自适应细分（参数方程 / 极坐标共用）
    // ------------------------------------------------------------------

    /** 一个屏幕上的点；valid=false 表示该参数处无定义或跑得太远。 */
    private class Pt {
        var x = 0.0
        var y = 0.0
        var valid = false

        fun copyFrom(o: Pt) {
            x = o.x
            y = o.y
            valid = o.valid
        }
    }

    private fun interface PointFn {
        fun at(t: Double, out: Pt)
    }

    private fun adaptiveCurve(
        tMin: Double,
        tMax: Double,
        q: Quality,
        f: PointFn,
        b: PolyBuilder
    ) {
        if (!tMax.isFinite() || !tMin.isFinite() || tMax <= tMin) return
        val span = tMax - tMin
        // 初始密度给足：宁可多算几百个点，也不能一开始就把花瓣/自交漏掉。
        val n0 = (span * 24.0).toInt().coerceIn(96, 2048)
        val eps = q.flatnessEps
        val maxDepth = when (q) {
            Quality.FAST -> 5
            Quality.NORMAL -> 8
            Quality.HIGH -> 10
        }

        val prev = Pt()
        val cur = Pt()
        f.at(tMin, prev)
        emit(prev, b)

        var tPrev = tMin
        for (i in 1..n0) {
            val t = tMin + span * i / n0
            f.at(t, cur)
            refine(tPrev, prev, t, cur, 0, eps, maxDepth, f, b)
            tPrev = t
            prev.copyFrom(cur)
        }
    }

    private fun refine(
        t0: Double, p0: Pt,
        t1: Double, p1: Pt,
        depth: Int, eps: Double, maxDepth: Int,
        f: PointFn, b: PolyBuilder
    ) {
        val tm = 0.5 * (t0 + t1)
        if (tm <= t0 || tm >= t1) {
            // 浮点已经分不动了
            emit(p1, b)
            return
        }
        val pm = Pt()
        f.at(tm, pm)
        if (!pm.valid || depth >= maxDepth || deviation(p0, pm, p1) <= eps) {
            if (!pm.valid) b.endLine()
            emit(p1, b)
        } else {
            refine(t0, p0, tm, pm, depth + 1, eps, maxDepth, f, b)
            refine(tm, pm, t1, p1, depth + 1, eps, maxDepth, f, b)
        }
    }

    private fun emit(p: Pt, b: PolyBuilder) {
        if (p.valid) b.add(p.x.toFloat(), p.y.toFloat()) else b.endLine()
    }

    /** 中点离弦的距离（像素）。点无效时返回最大值，逼着它继续细分直到找到边界。 */
    private fun deviation(p0: Pt, pm: Pt, p1: Pt): Double {
        if (!p0.valid || !p1.valid) return Double.MAX_VALUE
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return hypot(pm.x - p0.x, pm.y - p0.y)
        var t = ((pm.x - p0.x) * dx + (pm.y - p0.y) * dy) / len2
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val cx = p0.x + t * dx
        val cy = p0.y + t * dy
        return hypot(pm.x - cx, pm.y - cy)
    }

    /**
     * 折线收集器。
     *
     * [add] 自动开新段，[endLine] 断线，[finish] 收尾。
     * 另外设了折线总数上限：病态表达式（比如 sin(1/x)）不至于把内存吃爆。
     */
    private class PolyBuilder {
        private var buf = FloatArray(256)
        private var n = 0
        private var open = false
        private val result = ArrayList<FloatArray>()

        fun add(x: Float, y: Float) {
            if (result.size >= MAX_CURVES) return
            if (!open) {
                flush()
                open = true
            }
            if (n + 2 > buf.size) {
                if (buf.size >= MAX_FLOATS_PER_CURVE) {
                    // 单条折线过长，强制断成下一段，避免无限增长
                    flush()
                    open = true
                } else {
                    buf = buf.copyOf(buf.size * 2)
                }
            }
            buf[n++] = x
            buf[n++] = y
        }

        fun endLine() {
            if (open) {
                flush()
                open = false
            }
        }

        fun finish() = endLine()

        fun take(): List<FloatArray> = result

        private fun flush() {
            // 少于两个点画不出线
            if (n >= 4) result.add(buf.copyOf(n))
            n = 0
        }

        private companion object {
            const val MAX_CURVES = 20000
            const val MAX_FLOATS_PER_CURVE = 1 shl 16
        }
    }
}
