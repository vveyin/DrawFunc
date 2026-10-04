package com.functiondrawer.watch.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * 几何工具（度量 / 变换 / 作图 / 直线）的数学核心。
 *
 * 服务的是「工具」这一类交互：点一下曲线看它跟别的函数在哪儿相交、
 * 拉一条直线量距离、画中垂线 / 角平分线 / 切线、拖动时吸附到顶点和交点。
 * 这里只有数学：不碰 Compose、不碰 Android，所以能在纯 JVM 上离线测试
 * （见 `tools\run-engine-tests.ps1`）。
 *
 * ## 两套尺度，别混用
 *
 *  * **世界坐标**（[GPoint] / [GLine]）里算数学：交点在哪儿、角度多少、面积多大。
 *  * **屏幕像素**（[Curve.points]、手势落点）里判断「点得准不准」：
 *    手指偏 3 个像素算不算点在交点上，**只跟像素有关，跟当前缩放无关**。
 *
 * 所以凡是容差都写明单位：
 *  * `eps` 之类是**世界单位**，调用方可以用 [worldEpsForPixels] 把像素换算过来；
 *  * 名字带 `Px` 的参数一律是**屏幕像素**。
 *
 * ## 退化输入的统一约定（不抛异常、不放 NaN 出去）
 *
 *  * **求交 / 判定**（[intersect] / [contains] / [angleBisector] / [tangentLine]）：
 *    没有唯一解就返回 `null` —— 平行、重合、零长度线段、非有限坐标。
 *  * **构造类**（[perpendicular] / [parallel] / [perpendicularBisector]）：**永不返回 null**，
 *    但输入退化时返回**退化直线**（`p == q`，见 [GLine.isDegenerate]）。
 *    方向根本不存在，与其编一个方向骗调用方，不如让它一眼看出来。
 *  * **度量类**（[angleAt] / [polygonArea]）：算不出来返回 0.0，而不是 NaN。
 *  * **距离类**：非有限输入返回 `Double.POSITIVE_INFINITY`（"无穷远"），不会返回 NaN。
 *
 * 全部函数都是**纯函数**、无副作用、线程安全：采样线程和 UI 线程可以同时读同一份数据。
 */

// ---------------------------------------------------------------------------
// 1. 基本图元（世界坐标）
// ---------------------------------------------------------------------------

/**
 * 世界坐标里的一个点。
 *
 * 刻意用 `Double`：屏幕像素才用 `Float`（[Curve.points]），
 * 世界坐标要参与求交、求导、累加，精度不能丢。
 */
data class GPoint(val x: Double, val y: Double) {

    /** 两个坐标都有限（不是 NaN / ±∞）。几何运算前的通用体检。 */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/** 直线 / 线段 / 射线。三者只差在参数 `t` 的定义域上（见 [GLine.tRange]）。 */
enum class GLineKind { LINE, SEGMENT, RAY }

/**
 * 参数 `t` 的合法区间。线段是 `[0,1]`、射线是 `[0,∞)`、直线是 `(-∞,∞)`。
 *
 * 交点裁剪就是「解出来的 t 落不落在这个区间里」，所以把它单独拎出来，
 * 三种图元共用同一套判定逻辑，不会出现"线段忘了夹 0~1"这种漏。
 */
data class TRange(val lo: Double, val hi: Double) {

    /** t 是否落在区间内。[slack] 是端点容差（相对参数，不是长度）。 */
    fun contains(t: Double, slack: Double = DEFAULT_SLACK): Boolean =
        t >= lo - slack && t <= hi + slack

    companion object {
        /** 端点容差：1e-9 的参数偏移，避免「正好落在端点上」被浮点误差判掉。 */
        const val DEFAULT_SLACK = 1e-9

        val FULL = TRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val UNIT = TRange(0.0, 1.0)
        val HALF_LINE = TRange(0.0, Double.POSITIVE_INFINITY)
    }
}

/**
 * 直线类图元：由两个锚点 `p`、`q` 和种类决定，参数化是 `p + t·(q-p)`。
 *
 * `q` 只用来定**方向**，它的模长不影响图元本身（所以 [lineOf] 的两个点
 * 放在哪儿结果都一样）。但 `q == p` 时方向不存在，此时 [isDegenerate] 为真，
 * 求交 / 判定一律返回 null。
 *
 * 为什么不做成 `sealed interface`：三种图元的数学完全相同（同一个参数方程、
 * 同一套求交公式），差别只在 `t` 的区间。用 kind 字段能让所有函数只有一份实现，
 * 不会出现三份几乎一样的求交代码各自带一个 bug。
 */
data class GLine(
    val p: GPoint,
    val q: GPoint,
    val kind: GLineKind = GLineKind.LINE
) {

    val dx: Double get() = q.x - p.x
    val dy: Double get() = q.y - p.y

    /** 锚点间距（世界单位）。 */
    val length: Double get() = hypot(dx, dy)

    /** 方向不存在（两点重合）或坐标非有限 → 退化，后面所有运算都要先看这个。 */
    val isDegenerate: Boolean get() = !p.isFinite || !q.isFinite || (dx == 0.0 && dy == 0.0)

    /** 该图元允许的 t 区间。 */
    val tRange: TRange
        get() = when (kind) {
            GLineKind.LINE -> TRange.FULL
            GLineKind.SEGMENT -> TRange.UNIT
            GLineKind.RAY -> TRange.HALF_LINE
        }

    /** 取参数 t 处的点。t 在区间外就落到延长线上了（不做裁剪，裁剪是调用方的事）。 */
    fun pointAt(t: Double): GPoint = GPoint(p.x + t * dx, p.y + t * dy)
}

/** 过 a、b 两点的**直线**（两端无限延伸）。 */
fun lineOf(a: GPoint, b: GPoint): GLine = GLine(a, b, GLineKind.LINE)

/** 以 a、b 为端点的**线段**。 */
fun segmentOf(a: GPoint, b: GPoint): GLine = GLine(a, b, GLineKind.SEGMENT)

/** 从 a 出发、经过 b 的**射线**（只往 b 那一侧延伸）。 */
fun rayOf(a: GPoint, b: GPoint): GLine = GLine(a, b, GLineKind.RAY)

/**
 * 把「屏幕上多少个像素」换算成「世界坐标里多少单位」。
 *
 * 存在意义：几何判定在世界坐标里做（[contains] 的 eps 是世界单位），
 * 而"手指点准了没有"只跟像素有关。中间这一步换算少了，就会出现
 * 「放大以后明明点得很准，却判不中」这类随缩放变化的怪毛病。
 *
 * 视口非法（unitsPerPixel ≤ 0）时返回 0.0 —— **宁严不宽**：
 * 退化成"只有精确命中才算"，不会误判成"哪儿都算命中"。
 */
fun worldEpsForPixels(vp: Viewport, pixels: Double): Double {
    val upp = vp.unitsPerPixel
    if (upp <= 0.0 || !upp.isFinite() || !pixels.isFinite()) return 0.0
    return pixels * upp
}

// ---------------------------------------------------------------------------
// 2. 交点与判定（世界坐标）
// ---------------------------------------------------------------------------

/**
 * 两个图元的交点。平行、重合、任一方退化（零长度）一律返回 `null`。
 *
 * 推导：设交点为 `p1 + t·d1 = p2 + u·d2`，两边分别叉乘 `d2`、`d1`：
 * ```
 *   t = cross(p2-p1, d2) / cross(d1, d2)
 *   u = cross(p2-p1, d1) / cross(d1, d2)
 * ```
 * 分母为 0 就是平行（含重合 —— 重合有**无穷多个**交点，返回 null 比返回
 * 其中一个诚实）。解出 t、u 后各自用自己的 [GLine.tRange] 裁剪，
 * 这样线段只管 `[0,1]`、射线只管 `[0,∞)`，一份代码三种图元都对。
 *
 * @param parallelEps 判平行的**相对**容差：`|cross(d1,d2)| ≤ eps·|d1|·|d2|`
 *                    即夹角正弦小于它就算平行。用相对量是为了跟坐标量级无关。
 */
fun intersect(a: GLine, b: GLine, parallelEps: Double = 1e-12): GPoint? {
    if (a.isDegenerate || b.isDegenerate) return null
    val den = a.dx * b.dy - a.dy * b.dx
    val scale = a.length * b.length
    if (scale <= 0.0 || !scale.isFinite()) return null
    if (abs(den) <= parallelEps * scale) return null

    val wx = b.p.x - a.p.x
    val wy = b.p.y - a.p.y
    val t = (wx * b.dy - wy * b.dx) / den
    val u = (wx * a.dy - wy * a.dx) / den
    if (!t.isFinite() || !u.isFinite()) return null
    if (!a.tRange.contains(t) || !b.tRange.contains(u)) return null

    val r = a.pointAt(t)
    return if (r.isFinite) r else null
}

/**
 * 点是否落在图元上。[eps] 是**世界单位**的距离容差。
 *
 * 判定分两步：先看到**支撑直线**的距离在不在 eps 内，再看参数 t 落不落
 * 在图元的定义域里（线段 `[0,1]`、射线 `[0,∞)`）。
 * 退化图元（零长度）不包含任何点，直接 false。
 */
fun contains(line: GLine, p: GPoint, eps: Double = 1e-9): Boolean {
    if (line.isDegenerate || !p.isFinite) return false
    if (!eps.isFinite() || eps < 0.0) return false
    if (distanceToLine(p, line) > eps) return false
    return line.tRange.contains(projectionParam(p, line))
}

/** 点 p 在直线上的投影参数 t（`t = (p-a)·d / |d|²`）。退化直线返回 0.0。 */
fun projectionParam(p: GPoint, l: GLine): Double {
    val len2 = l.dx * l.dx + l.dy * l.dy
    if (len2 <= 0.0 || !len2.isFinite()) return 0.0
    return ((p.x - l.p.x) * l.dx + (p.y - l.p.y) * l.dy) / len2
}

/**
 * 点到**支撑直线**的距离（不看 kind：线段也按它所在的整条直线算）。
 *
 * 公式：`|cross(p-a, d)| / |d|`。
 * 零长度图元按「一个点」处理，返回点到锚点的距离 —— 这比返回 ∞ / NaN
 * 更有用（UI 上等价于吸附到那个点），也让退化的线段和点行为一致。
 */
fun distanceToLine(p: GPoint, l: GLine): Double {
    if (!p.isFinite) return Double.POSITIVE_INFINITY
    if (l.isDegenerate) return distance(p, l.p)
    val cross = (p.x - l.p.x) * l.dy - (p.y - l.p.y) * l.dx
    val d = abs(cross) / l.length
    return if (d.isFinite()) d else Double.POSITIVE_INFINITY
}

/** 点到**线段**的最短距离：把投影参数夹到 `[0,1]` 再量距离。 */
fun distanceToSegment(p: GPoint, a: GPoint, b: GPoint): Double {
    if (!p.isFinite || !a.isFinite || !b.isFinite) return Double.POSITIVE_INFINITY
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 <= 0.0) return distance(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/**
 * 点到**图元**的最短距离（尊重 kind）：直线→垂距，线段→夹 `[0,1]`，射线→夹 `[0,∞)`。
 *
 * 工具里量"点到这条射线多远"应该走这个，而不是 [distanceToLine]。
 */
fun distanceToShape(p: GPoint, l: GLine): Double = when (l.kind) {
    GLineKind.LINE -> distanceToLine(p, l)
    GLineKind.SEGMENT -> distanceToSegment(p, l.p, l.q)
    GLineKind.RAY -> {
        if (l.isDegenerate || !p.isFinite) {
            if (!p.isFinite) Double.POSITIVE_INFINITY else distance(p, l.p)
        } else {
            val t = projectionParam(p, l).coerceAtLeast(0.0)
            distance(p, l.pointAt(t))
        }
    }
}

// ---------------------------------------------------------------------------
// 3. 折线（Curve）求交 —— 屏幕像素空间
// ---------------------------------------------------------------------------

/**
 * 两条折线的交点，**返回屏幕像素坐标**（[GPoint] 在这里装的是像素，不是世界坐标）。
 *
 * 为什么在屏幕空间做：折线的顶点是像素坐标（[Curve.points]），
 * 逐段求交在像素里做既不用反复换算，容差也天然是"多少个像素"，
 * 正好对上"手指点准了没有"这个判断。需要世界坐标的调用方用
 * [intersections]（带 [Viewport] 的那个重载）。
 *
 * 三条容易踩的坑，这里都处理了：
 *  * **去重**：交点落在折线顶点上时，相邻两条线段会各算出一次，
 *    不去重就会出现"一个交点画了两个标记"。距离小于 [mergeEpsPx] 的只留先算出来的那个。
 *  * **共线重叠**：两条线段重叠时有**无穷多个**交点，一律不算（返回空）——
 *    否则去重也救不了。
 *  * **非有限坐标**：跳过含 NaN / ∞ 的线段（理论上采样器不会产出，但别让一个坏点毁掉全部结果）。
 *
 * 结果顺序是"沿 a 的线段顺序"，不是沿 x 排序，需要排序的话由调用方决定。
 */
fun intersectionsInScreen(a: Curve, b: Curve, mergeEpsPx: Double = 1.0): List<GPoint> {
    val pa = a.points
    val pb = b.points
    if (pa.size < 4 || pb.size < 4) return emptyList()

    val raw = ArrayList<GPoint>()
    var i = 0
    while (i + 3 < pa.size) {
        val ax = pa[i].toDouble()
        val ay = pa[i + 1].toDouble()
        val bx = pa[i + 2].toDouble()
        val by = pa[i + 3].toDouble()
        i += 2
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue

        var j = 0
        while (j + 3 < pb.size) {
            val cx = pb[j].toDouble()
            val cy = pb[j + 1].toDouble()
            val ex = pb[j + 2].toDouble()
            val ey = pb[j + 3].toDouble()
            j += 2
            if (!cx.isFinite() || !cy.isFinite() || !ex.isFinite() || !ey.isFinite()) continue

            val hit = segmentIntersection(ax, ay, bx, by, cx, cy, ex, ey) ?: continue
            raw.add(GPoint(hit.first, hit.second))
        }
    }
    return dedupScreen(raw, mergeEpsPx)
}

/**
 * 两条折线的交点，返回**世界坐标**。
 *
 * 数学在像素里做（见 [intersectionsInScreen]），最后一次性换回世界坐标。
 *
 * @param vp **必须是这两条曲线的采样视口**（也就是 `PlotResult.viewport`）。
 *           曲线里存的是那次采样的像素坐标，拿别的视口去换会得到错的世界坐标。
 * @param mergeEpsPx 去重容差（屏幕像素）：距离小于它的交点只留一个。
 */
fun intersections(
    a: Curve,
    b: Curve,
    vp: Viewport,
    mergeEpsPx: Double = 1.0
): List<GPoint> =
    intersectionsInScreen(a, b, mergeEpsPx).map { GPoint(vp.toWorldX(it.x), vp.toWorldY(it.y)) }

/**
 * 一条折线与一条直线 / 线段 / 射线的交点，返回**世界坐标**。
 *
 * 做法：把直线在屏幕空间里**裁到曲线包围盒**（外扩 [marginPx]）当成一条折线，
 * 于是问题退化成"两条折线求交"，跟 [intersectionsInScreen] 共用同一套代码。
 *
 * 几点说明：
 *  * 直线无限长，但曲线是有限的，所以交点只可能落在曲线包围盒附近；
 *    裁到包围盒既省时间，也避免远处坐标把浮点精度吃光（视口外的世界坐标
 *    换成像素可能是 1e12 量级，Float 只有 7 位有效数字，方向会歪）。
 *  * 定义域照样生效：线段只在 `t∈[0,1]`、射线只在 `t≥0` 上找交点，
 *    裁不出区间（比如线段整条在包围盒外）就返回空表。
 *  * 只返回**可见范围内**的交点。曲线跑出屏幕的那些段本来也点不到，
 *    少几个点不影响工具交互，反而省算。
 *
 * @param vp 必须是该曲线的采样视口（同 [intersections] 的说明）。
 */
fun intersections(
    curve: Curve,
    line: GLine,
    vp: Viewport,
    mergeEpsPx: Double = 1.0,
    marginPx: Double = 8.0
): List<GPoint> {
    val upp = vp.unitsPerPixel
    if (upp <= 0.0 || !upp.isFinite()) return emptyList()
    if (line.isDegenerate) return emptyList()

    val pts = curve.points
    if (pts.size < 4) return emptyList()

    // 曲线的屏幕包围盒（跳过非有限点）
    var xmin = Double.POSITIVE_INFINITY
    var ymin = Double.POSITIVE_INFINITY
    var xmax = Double.NEGATIVE_INFINITY
    var ymax = Double.NEGATIVE_INFINITY
    var k = 0
    while (k + 1 < pts.size) {   // 写成 k+1 而不是 k：数组长度是奇数时别越界
        val x = pts[k].toDouble()
        val y = pts[k + 1].toDouble()
        if (x.isFinite() && y.isFinite()) {
            if (x < xmin) xmin = x
            if (x > xmax) xmax = x
            if (y < ymin) ymin = y
            if (y > ymax) ymax = y
        }
        k += 2
    }
    if (!xmin.isFinite()) return emptyList()
    val pad = if (marginPx.isFinite() && marginPx > 0.0) marginPx else 0.0
    xmin -= pad
    ymin -= pad
    xmax += pad
    ymax += pad

    // 直线换算到屏幕。这里自己按 Double 算，不用 Viewport.toScreenX/Y（那两个返回 Float，
    // 远离视口的坐标会掉精度）。
    val ax = screenX(vp, line.p.x)
    val ay = screenY(vp, line.p.y)
    val bx = screenX(vp, line.q.x)
    val by = screenY(vp, line.q.y)
    if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) return emptyList()
    val dx = bx - ax
    val dy = by - ay
    if (hypot(dx, dy) <= MIN_SCREEN_DIR) return emptyList()

    // 种类 → t 的初始区间（屏幕映射是仿射的，t 的含义不变）
    val tLo: Double
    val tHi: Double
    when (line.kind) {
        GLineKind.SEGMENT -> {
            tLo = 0.0
            tHi = 1.0
        }
        GLineKind.RAY -> {
            tLo = 0.0
            tHi = MAX_T
        }
        GLineKind.LINE -> {
            tLo = -MAX_T
            tHi = MAX_T
        }
    }
    val span = clipToRect(ax, ay, dx, dy, xmin, ymin, xmax, ymax, tLo, tHi) ?: return emptyList()
    val sx = ax + span.lo * dx
    val sy = ay + span.lo * dy
    val ex = ax + span.hi * dx
    val ey = ay + span.hi * dy

    val raw = ArrayList<GPoint>()
    var i = 0
    while (i + 3 < pts.size) {
        val cx = pts[i].toDouble()
        val cy = pts[i + 1].toDouble()
        val fx = pts[i + 2].toDouble()
        val fy = pts[i + 3].toDouble()
        i += 2
        if (!cx.isFinite() || !cy.isFinite() || !fx.isFinite() || !fy.isFinite()) continue
        val hit = segmentIntersection(sx, sy, ex, ey, cx, cy, fx, fy) ?: continue
        raw.add(GPoint(hit.first, hit.second))
    }
    return dedupScreen(raw, mergeEpsPx).map { GPoint(vp.toWorldX(it.x), vp.toWorldY(it.y)) }
}

// ---------------------------------------------------------------------------
// 4. 变换 / 作图（都由已有对象构造新对象，纯函数）
// ---------------------------------------------------------------------------

/** 线段 ab 的中点 `((a+b)/2)`。 */
fun midpoint(a: GPoint, b: GPoint): GPoint =
    GPoint((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

/** 点 p 在直线 l 上的**垂足**（正投影）。退化直线返回它的锚点。 */
fun projectOnto(p: GPoint, l: GLine): GPoint =
    if (l.isDegenerate) l.p else l.pointAt(projectionParam(p, l))

/**
 * 过点 p、垂直于 l 的直线。
 *
 * 方向取 `(-dy, dx)`（把 l 的方向逆时针转 90°），点积必然是 0，所以垂直是**解析成立**的，
 * 不靠浮点近似判断。
 *
 * l 退化（零长度）时方向不存在，返回**退化直线**（`p == q`）——
 * 调用方可查 [GLine.isDegenerate]，本文件里其它函数遇到退化直线也都会给出明确结果。
 */
fun perpendicular(l: GLine, p: GPoint): GLine =
    GLine(p, GPoint(p.x - l.dy, p.y + l.dx), GLineKind.LINE)

/**
 * 线段 ab 的**垂直平分线**（中垂线）：过中点、方向垂直于 ab。
 *
 * 中垂线上的点到 a、b 等距，这是它唯一的定义性质，测试直接按这条性质断言。
 * a 与 b 重合时中点仍在，但方向不存在 → 返回退化直线。
 */
fun perpendicularBisector(a: GPoint, b: GPoint): GLine {
    val m = midpoint(a, b)
    return GLine(m, GPoint(m.x - (b.y - a.y), m.y + (b.x - a.x)), GLineKind.LINE)
}

/**
 * 过点 p、平行于 l 的直线（方向原样搬过来）。
 *
 * l 退化时返回退化直线。平行性同样解析成立（方向相同 ⇒ 叉积恒为 0）。
 */
fun parallel(l: GLine, p: GPoint): GLine =
    GLine(p, GPoint(p.x + l.dx, p.y + l.dy), GLineKind.LINE)

/**
 * ∠a-vertex-c 的**角平分线**（vertex 是顶点，即第二个参数）。返回整条直线，不是射线。
 *
 * 做法：两条边各取**单位向量** u、v，则 `u + v` 是内角平分线的方向
 * （菱形对角线平分顶角）。取单位向量是关键 —— 直接拿 a-vertex 和 c-vertex
 * 相加，边长不等时平分线会歪。
 *
 * 退化情形都有明确结果：
 *  * 顶点与 a 或 c 重合（边长 0，没有角）→ `null`。
 *  * 三点共线且 a、c 在顶点同侧（0° 角）→ 返回那条直线本身
 *    （`u+v = 2u`，方向仍是 u，公式自然给出正确结果）。
 *  * 三点共线且 a、c 在顶点两侧（180° 角）→ `u+v ≈ 0`，内角平分线方向退化，
 *    返回**垂直于该方向**的直线（两条候选垂线里取 u 逆时针转 90° 的那条，
 *    这样它随角度连续变化，不会在 179°→180° 时突然翻转）。
 *
 * @param eps 判定"重合 / 反向"的长度阈值（世界单位）。
 */
fun angleBisector(a: GPoint, vertex: GPoint, c: GPoint, eps: Double = 1e-12): GLine? {
    val ux = a.x - vertex.x
    val uy = a.y - vertex.y
    val vx = c.x - vertex.x
    val vy = c.y - vertex.y
    val lu = hypot(ux, uy)
    val lv = hypot(vx, vy)
    if (!lu.isFinite() || !lv.isFinite()) return null
    if (lu <= eps || lv <= eps) return null

    val sx = ux / lu + vx / lv
    val sy = uy / lu + vy / lv
    if (hypot(sx, sy) <= eps) {
        // 180°：u 与 v 反向，取垂直于 u 的单位向量作为平分线方向
        return GLine(vertex, GPoint(vertex.x - uy / lu, vertex.y + ux / lu), GLineKind.LINE)
    }
    // 归一化后当第二点：这样 GLine.length 是稳定的 1，调用方按长度取样也不会受边长影响
    val d = hypot(sx, sy)
    return GLine(vertex, GPoint(vertex.x + sx / d, vertex.y + sy / d), GLineKind.LINE)
}

/**
 * 函数 `f` 在 `x0` 处的**数值导数**（中心差分）：
 * ```
 *   f'(x0) ≈ (f(x0+h) - f(x0-h)) / (2h)      误差 O(h²)
 * ```
 * 用中心差分而不是前向差分，是因为同样步长下精度高一个量级（`O(h)` → `O(h²)`），
 * 代价只是多算一次函数值 —— 手表上一次表达式求值是微秒级，完全付得起。
 *
 * 求不出来就返回 `null`（**不返回 0，也不返回 NaN**）：
 *  * `h` 非有限或 ≤ 0（`h = 0` 会直接除出 NaN）；
 *  * `f(x0)` 非有限（切点本身不在定义域里）；
 *  * `f(x0±h)` 非有限（例如 `√x` 在 `x=0` 处左邻域无定义）；
 *  * `x0±h == x0`（h 小到被浮点精度吃掉，差分恒为 0，结果会骗人）。
 */
fun numericDerivative(f: (Double) -> Double, x0: Double, h: Double = 1e-5): Double? {
    if (!x0.isFinite()) return null
    if (!h.isFinite() || h <= 0.0) return null
    if (x0 + h == x0 || x0 - h == x0) return null
    val y1 = f(x0 + h)
    val y2 = f(x0 - h)
    if (!y1.isFinite() || !y2.isFinite()) return null
    val slope = (y1 - y2) / (2.0 * h)
    return if (slope.isFinite()) slope else null
}

/**
 * 函数 `f` 在 `x0` 处的**切线**（用 [numericDerivative] 求斜率），
 * 切点为 `(x0, f(x0))`。
 *
 * 这里只吃 `(Double) -> Double`：DEG / RAD、参数 `a b c d` 全都在**调用方编译表达式时**
 * 就折进函数里了（见 `Expression.compile`），几何这边不关心、也不该关心。
 *
 * @param h 差分步长。默认 1e-5 对普通量级的函数足够；`|x0|` 很大时
 *          （比如 1e6）可以适当放大 h，否则 `f(x0+h) - f(x0-h)` 会被抵消误差毁掉。
 * @return 导数求不出来时返回 `null`（原因见 [numericDerivative]）。
 */
fun tangentLine(f: (Double) -> Double, x0: Double, h: Double = 1e-5): GLine? {
    if (!x0.isFinite()) return null
    val y0 = f(x0)
    if (!y0.isFinite()) return null
    val slope = numericDerivative(f, x0, h) ?: return null
    // 第二点取 x0+1：斜率就是 dy/dx，调用方直接读方向向量即可
    return GLine(GPoint(x0, y0), GPoint(x0 + 1.0, y0 + slope), GLineKind.LINE)
}

/**
 * 点 p 关于直线 l 的**轴对称**点。
 *
 * 做法：先求垂足 `foot`（p 在 l 上的正投影），对称点就是 `2·foot - p` ——
 * 这正是"l 是 pp' 的中垂线"的定义。用垂足而不是自己推公式，
 * 是为了让 [projectOnto] 成为唯一一处投影实现。
 *
 * l 退化（零长度）时按**点**处理，返回关于锚点的中心对称点 ——
 * 与 [distanceToLine] / [reflectThrough] 的退化约定一致。
 */
fun reflect(p: GPoint, l: GLine): GPoint {
    if (l.isDegenerate) return reflectThrough(p, l.p)
    val foot = projectOnto(p, l)
    return GPoint(2.0 * foot.x - p.x, 2.0 * foot.y - p.y)
}

/** 点 p 关于中心 [center] 的**中心对称**点（`2·center - p`）。 */
fun reflectThrough(p: GPoint, center: GPoint): GPoint =
    GPoint(2.0 * center.x - p.x, 2.0 * center.y - p.y)

// ---------------------------------------------------------------------------
// 5. 度量
// ---------------------------------------------------------------------------

/** 两点距离。任一坐标非有限时返回 `Double.POSITIVE_INFINITY`（而不是 NaN）。 */
fun distance(a: GPoint, b: GPoint): Double {
    if (!a.isFinite || !b.isFinite) return Double.POSITIVE_INFINITY
    return hypot(a.x - b.x, a.y - b.y)
}

/**
 * ∠a-vertex-c 的**度数**（顶点是第一个参数，范围 0~180）。
 *
 * 用 `acos((u·v)/(|u||v|))`，并把余弦**夹到 [-1,1]**：浮点误差可能让
 * 两个同向单位向量的点积算出 1.0000000000000002，不夹的话 `acos` 直接返回 NaN。
 *
 * 退化（顶点与 a 或 c 重合，没有角）返回 `0.0`，不返回 NaN。
 */
fun angleAt(vertex: GPoint, a: GPoint, c: GPoint): Double {
    val ux = a.x - vertex.x
    val uy = a.y - vertex.y
    val vx = c.x - vertex.x
    val vy = c.y - vertex.y
    val lu = hypot(ux, uy)
    val lv = hypot(vx, vy)
    if (!lu.isFinite() || !lv.isFinite() || lu <= 0.0 || lv <= 0.0) return 0.0
    val cos = ((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0)
    val deg = acos(cos) * 180.0 / PI
    return if (deg.isFinite()) deg else 0.0
}

/**
 * 多边形（按给定顺序连成闭合圈）的**面积**，鞋带公式取绝对值：
 * ```
 *   2S = Σ (xⱼ·yᵢ - xᵢ·yⱼ)      j = i-1（即把最后一点和第一点接起来）
 * ```
 * 取绝对值是因为顶点顺序（顺时针/逆时针）由用户随手画的顺序决定，
 * 度量工具不该因为方向不同给出负数。
 *
 * 少于 3 个点返回 `0.0`（两个点围不出面积，不是错误）；
 * 三点共线、自交图形按公式原样算（自交会正负抵消，这是鞋带公式的正常语义）。
 * 结果非有限（坐标里有 NaN/∞）时返回 0.0。
 */
fun polygonArea(points: List<GPoint>): Double {
    if (points.size < 3) return 0.0
    var s = 0.0
    var j = points.size - 1
    for (i in points.indices) {
        s += points[j].x * points[i].y - points[i].x * points[j].y
        j = i
    }
    val area = abs(s) / 2.0
    return if (area.isFinite()) area else 0.0
}

// ---------------------------------------------------------------------------
// 6. 吸附（屏幕空间）
// ---------------------------------------------------------------------------

/** 吸附点的来源。UI 拿它决定高亮样式和提示文案（"交点" / "顶点" / "零点" / "极值点"）。 */
enum class SnapKind { VERTEX, INTERSECTION, ZERO, EXTREMUM, POINT, OTHER }

/** 一个候选吸附点及其来源。 */
data class SnapCandidate(val point: GPoint, val kind: SnapKind = SnapKind.POINT)

/** 命中结果：吸到哪个点、它是什么、离手指多少**屏幕像素**。 */
data class SnapHit(val point: GPoint, val kind: SnapKind, val screenDistance: Double)

/**
 * 一个**屏幕像素**点与一个**世界坐标**点的距离。
 *
 * 内部按 Double 算，不用 `Viewport.toScreenX/Y`（那两个返回 Float，
 * 视口外的点会掉精度）；判断"点得准不准"用的就是这个量。
 */
fun screenDistance(vp: Viewport, px: Double, py: Double, p: GPoint): Double {
    if (!p.isFinite || !px.isFinite() || !py.isFinite()) return Double.POSITIVE_INFINITY
    if (vp.unitsPerPixel <= 0.0 || !vp.unitsPerPixel.isFinite()) return Double.POSITIVE_INFINITY
    return hypot(screenX(vp, p.x) - px, screenY(vp, p.y) - py)
}

/**
 * 折线上离屏幕点 `(px, py)` 最近的点。
 *
 * 返回 `Pair(世界坐标的点, 屏幕像素距离)`；曲线没有可用线段时返回 `null`
 * （空曲线、只有一个点的曲线，或者所有点坐标都非有限）。
 *
 * 做法是把折线的每一段投影一遍：把查询点投到线段上、参数夹到 `[0,1]`，
 * 取最近的那一段。**精度就是折线本身的精度** —— 采样点之间的曲线是直的，
 * 所以得到的最近点可能比真实曲线上的最近点略偏，偏差量级是采样步长
 * （显函数是 1 像素量级，见 `Quality.explicitStepPx`）。这个量级对"吸附到函数上"
 * 完全够用，不值得为它再做一次曲线拟合。
 *
 * 注意它**不设阈值**：多远都会给出最近点。要不要采纳由 [snap] 按像素阈值决定。
 *
 * @param vp 必须是该曲线的采样视口，否则换算出来的世界坐标是错的。
 */
fun nearestOnCurve(curve: Curve, px: Float, py: Float, vp: Viewport): Pair<GPoint, Double>? {
    val pts = curve.points
    if (pts.size < 4) return null
    if (vp.unitsPerPixel <= 0.0 || !vp.unitsPerPixel.isFinite()) return null
    val qx = px.toDouble()
    val qy = py.toDouble()
    if (!qx.isFinite() || !qy.isFinite()) return null

    var bestD = Double.POSITIVE_INFINITY
    var bestX = 0.0
    var bestY = 0.0
    var i = 0
    while (i + 3 < pts.size) {
        val ax = pts[i].toDouble()
        val ay = pts[i + 1].toDouble()
        val bx = pts[i + 2].toDouble()
        val by = pts[i + 3].toDouble()
        i += 2
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue

        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((qx - ax) * dx + (qy - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val d = hypot(qx - cx, qy - cy)
        if (d < bestD) {
            bestD = d
            bestX = cx
            bestY = cy
        }
    }
    if (!bestD.isFinite()) return null
    return Pair(GPoint(vp.toWorldX(bestX), vp.toWorldY(bestY)), bestD)
}

/**
 * 默认吸附半径（屏幕像素）。
 *
 * 手表屏幕大约 400~450 像素见方，指尖触控的"有效精准区"约 20~30 像素，
 * 取 24 是"能吸上但不会吸到隔壁点"的折中。UI 通常应该自己按屏幕尺寸传一个值。
 */
const val DEFAULT_SNAP_PX = 24.0

/**
 * 从候选点里挑**最近的、且在阈值内**的那一个；没有就返回 `null`。
 *
 * 阈值 [maxScreenDistance] 的单位是**屏幕像素**，这是刻意的：
 * 手指能戳多准跟缩放无关，用世界单位当阈值就会出现
 * 「放大之后同样的手指精度反而吸不上」这种怪现象。
 *
 * 几个约定：
 *  * 恰好等于阈值算命中（`<=`）；平局时取**先出现**的那个，结果稳定、不抖。
 *  * 跳过坐标非有限的候选点（采样异常不该让吸附整体失灵）。
 *  * [kinds] 为空 = 不筛；非空时只看这些来源（比如"只吸零点和极值点，不吸交点"）。
 *
 * @param px,py 手指的屏幕坐标（和 [Viewport] 同一套像素坐标系）。
 */
fun snap(
    candidates: List<SnapCandidate>,
    vp: Viewport,
    px: Float,
    py: Float,
    maxScreenDistance: Double = DEFAULT_SNAP_PX,
    kinds: Set<SnapKind> = emptySet()
): SnapHit? {
    if (candidates.isEmpty()) return null
    if (!maxScreenDistance.isFinite() || maxScreenDistance < 0.0) return null
    val qx = px.toDouble()
    val qy = py.toDouble()
    var best = Double.POSITIVE_INFINITY
    var bestPoint: GPoint? = null
    var bestKind = SnapKind.OTHER
    for (c in candidates) {
        if (kinds.isNotEmpty() && c.kind !in kinds) continue
        val d = screenDistance(vp, qx, qy, c.point)
        if (d < best) {
            best = d
            bestPoint = c.point
            bestKind = c.kind
        }
    }
    val hit = bestPoint ?: return null
    if (best > maxScreenDistance) return null
    return SnapHit(hit, bestKind, best)
}

/** 只关心"吸到哪个点"时的简写版本（候选点默认当成 [SnapKind.POINT]）。 */
fun snap(
    points: List<GPoint>,
    vp: Viewport,
    px: Float,
    py: Float,
    maxScreenDistance: Double = DEFAULT_SNAP_PX
): GPoint? =
    snap(points.map { SnapCandidate(it) }, vp, px, py, maxScreenDistance)?.point

// ---------------------------------------------------------------------------
// 内部工具
// ---------------------------------------------------------------------------

/** 屏幕 x（Double 版，避免 Float 在远离视口时的精度损失）。 */
private fun screenX(vp: Viewport, worldX: Double): Double =
    (worldX - vp.centerX) / vp.unitsPerPixel + vp.widthPx / 2.0

/** 屏幕 y（Double 版；屏幕 y 向下，所以是减）。 */
private fun screenY(vp: Viewport, worldY: Double): Double =
    vp.heightPx / 2.0 - (worldY - vp.centerY) / vp.unitsPerPixel

/** 判两条屏幕线段平行的**相对**容差（`|cross| / (|r||s|)`，无量纲）。 */
private const val SEGMENT_PARALLEL_EPS = 1e-12

/** 端点容差：交点在端点附近时允许的 t 越界量。 */
private const val SEGMENT_TOUCH_EPS = 1e-9

/** 把参数铺到无穷远的取值（真正的 ∞ 会在裁剪里算出 NaN，用一个够大的有限数代替）。 */
private const val MAX_T = 1e12

/** 屏幕方向上短于这个长度就没有方向可言（像素）。 */
private const val MIN_SCREEN_DIR = 1e-12

/**
 * 两条线段求交（屏幕空间，Double 精度）。平行 / 共线返回 `null`。
 *
 * `p + t·r = q + u·s` 解出
 * ```
 *   t = cross(q-p, s) / cross(r, s)
 *   u = cross(q-p, r) / cross(r, s)
 * ```
 * 要求 t、u 都落在 `[0,1]`（带浮点端点容差）。
 * 共线重叠时 `cross(r,s) = 0` → 返回 null：重叠有**无穷多个**交点，
 * 返回哪一个都是错的，不如不算（见 [intersectionsInScreen] 的说明）。
 */
private fun segmentIntersection(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double
): Pair<Double, Double>? {
    val rx = bx - ax
    val ry = by - ay
    val sx = dx - cx
    val sy = dy - cy
    val den = rx * sy - ry * sx
    val scale = hypot(rx, ry) * hypot(sx, sy)
    if (scale <= 0.0 || !scale.isFinite()) return null
    if (abs(den) <= SEGMENT_PARALLEL_EPS * scale) return null

    val qpx = cx - ax
    val qpy = cy - ay
    val t = (qpx * sy - qpy * sx) / den
    val u = (qpx * ry - qpy * rx) / den
    if (!t.isFinite() || !u.isFinite()) return null
    if (t < -SEGMENT_TOUCH_EPS || t > 1.0 + SEGMENT_TOUCH_EPS) return null
    if (u < -SEGMENT_TOUCH_EPS || u > 1.0 + SEGMENT_TOUCH_EPS) return null
    return Pair(ax + t * rx, ay + t * ry)
}

/**
 * 屏幕空间去重：与已保留的点距离 ≤ [epsPx] 的一律丢掉。
 *
 * 只保留**先算出来的**那个，不做平均：差值本来就在容差内，
 * 平均反而会让交点在相邻线段之间"漂"，同一处点两次还会给出不同坐标。
 * 候选点数量是"线段数平方"里筛出来的少量交点，两重循环足够。
 */
private fun dedupScreen(raw: List<GPoint>, epsPx: Double): List<GPoint> {
    if (raw.isEmpty()) return emptyList()
    val eps = if (epsPx.isFinite() && epsPx > 0.0) epsPx else 0.0
    val kept = ArrayList<GPoint>(raw.size)
    for (p in raw) {
        var dup = false
        for (k in kept) {
            if (hypot(p.x - k.x, p.y - k.y) <= eps) {
                dup = true
                break
            }
        }
        if (!dup) kept.add(p)
    }
    return kept
}

/** 裁剪结果（参数 t 的可用区间）。 */
private class TSpan(var lo: Double, var hi: Double)

/**
 * Liang–Barsky：把直线（参数区间 `[lo, hi]`）裁到矩形 `[xmin,xmax]×[ymin,ymax]`。
 *
 * 四条边各夹一次，`p` 是边界法向在参数方向上的投影、`q` 是起点到该边的带符号距离：
 *  * `p == 0`：平行于这条边 —— 在外侧（`q < 0`）就整条丢掉，否则这条边管不着；
 *  * `p < 0`：从外侧往里进，`r = q/p` 是入口，抬高 lo；
 *  * `p > 0`：从里往外走，`r` 是出口，压低 hi。
 * 区间被夹空就返回 `null`（整条线段在矩形外）。
 */
private fun clipToRect(
    ax: Double, ay: Double, dx: Double, dy: Double,
    xmin: Double, ymin: Double, xmax: Double, ymax: Double,
    lo: Double, hi: Double
): TSpan? {
    val span = TSpan(lo, hi)
    if (!clipEdge(-dx, ax - xmin, span)) return null
    if (!clipEdge(dx, xmax - ax, span)) return null
    if (!clipEdge(-dy, ay - ymin, span)) return null
    if (!clipEdge(dy, ymax - ay, span)) return null
    if (span.lo > span.hi) return null
    return span
}

/** 单条边界的裁剪，见 [clipToRect]。返回 false 表示整段被拒。 */
private fun clipEdge(p: Double, q: Double, span: TSpan): Boolean {
    if (p == 0.0) return q >= 0.0
    val r = q / p
    if (!r.isFinite()) return false
    if (p < 0.0) {
        if (r > span.hi) return false
        if (r > span.lo) span.lo = r
    } else {
        if (r < span.lo) return false
        if (r < span.hi) span.hi = r
    }
    return true
}
