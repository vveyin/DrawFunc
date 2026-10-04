// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.functiondrawer.watch.engine.Curve
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.LineStyle
import com.functiondrawer.watch.engine.PlotResult
import com.functiondrawer.watch.engine.SnapKind
import com.functiondrawer.watch.engine.Viewport
import com.functiondrawer.watch.ui.theme.CurvePalette
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 一条曲线的**外观**：颜色 / 线型 / 线径倍数。
 *
 * 它和采样结果（[PlotResult]）是**分开**传进画布的 —— 这是"改颜色之后图像不重绘"
 * 那个真机反馈的修法：原来颜色是从 `PlotResult` 里 `Curve.colorIndex` 取的，
 * 而 `PlotResult` 只在**重新采样**后才变（采样在后台线程跑，隐函数高精度下要几万个
 * 采样点），于是改完颜色要等采样跑完才变色，看起来就是"没反应"。
 * 现在颜色/线型/线径每帧实时读，改它们**完全不触发采样**。
 */
data class CurveStyle(
    val color: Color,
    val lineStyle: LineStyle = LineStyle.SOLID,
    /** 线径倍数（1 = 默认）。 */
    val widthScale: Float = 1f
)

/** 每条曲线预编译成一条 Path，避免每帧重建。颜色不在这里 —— 见 [CurveStyle]。 */
private class CurvePath(val functionId: Long, val path: Path, val colorIndex: Int)

/** 曲线"画出来"的揭示动画时长（毫秒）。 */
private const val REVEAL_MS = 520

/**
 * 绘图区。
 *
 * 这里刻意保持"傻"：采样、断线、插值全在 [com.functiondrawer.watch.engine.Plotter] 里做完了，
 * 收到的是**某个视口下的**屏幕像素坐标，所以这个 composable 只负责画线。
 *
 * ## 为什么需要那一段仿射变换
 *
 * 手势拖动时 viewport 每帧都在变，网格是按**当前**视口实时画的；
 * 而 [plot] 里的曲线是**上一次采样时**那个视口的屏幕坐标 ——
 * 直接画就会出现「函数图像跟不上坐标系」的滞后感。
 *
 * 修法不是每次手势都重采样（那太慢），而是把曲线按两个视口之间的
 * 仿射增量整体变换过去。**这是 O(1) 的**：Path 只建一次，
 * 变换交给 Canvas 的矩阵，每帧零额外开销。
 *
 * 换算推导（sx 为屏幕 x，upp 为每像素世界单位）：
 *   sx_old = (wx - cx_old)/upp_old + W/2
 *   sx_new = (wx - cx_new)/upp_new + W/2
 * 消掉 wx 得  sx_new = k·sx_old + W/2·(1-k) + (cx_old-cx_new)/upp_new，其中 k = upp_old/upp_new。
 * y 轴同理但要取负（屏幕 y 向下）。
 */
@Composable
fun GraphCanvas(
    viewport: Viewport,
    plot: PlotResult,
    settings: WatchSettings,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    gridColor: Color,
    axisColor: Color,
    labelColor: Color,
    strokeGrid: Float,
    strokeAxis: Float,
    strokeCurve: Float,
    /**
     * **只对这批函数**播放"从左到右画出来"的揭示动画。
     *
     * 由 `GraphState.consumeReveal` 给出，里面只有**新加进来**的函数 id；
     * 已有的函数直接整条画出来、不参与动画（用户明确要求
     * "添加新函数之后只绘制新函数，原本已经有的函数不绘制"）。
     */
    revealIds: Set<Long> = emptySet(),
    /**
     * 每条曲线的外观（颜色/线型/线径倍数），按函数 id 索引。
     *
     * 缺省（表里没有）时退回用采样结果里的 `colorIndex` —— 这样其它调用方
     * 不传也能正常画。注意它**不进 `remember(plot)` 的缓存**：
     * 外观是每帧读的，所以改颜色/线型/线径立刻可见，不用等重新采样。
     */
    curveStyles: Map<Long, CurveStyle> = emptyMap(),
    /**
     * 需要加阴影的函数 id（点击选中的那些）。
     *
     * 用户要求："点击函数、方程或点之后给曲线或点加一个透明度为 60% 的阴影"。
     * 实现成"在曲线下面再描一条更粗的、同色 60% 透明的线"——
     * 比高斯模糊便宜得多，在手表上不掉帧。
     */
    shadowIds: Set<Long> = emptySet(),
    /**
     * 图上的几何对象（#9）：描点、直线/线段/射线、角度/距离/面积度量。
     *
     * 由 [GraphState] 的对象列表直接传进来（世界坐标），换算和绘制都在这里做 ——
     * 上层只管"有哪些对象"，不用碰像素。
     */
    objects: List<GraphObject> = emptyList(),
    /**
     * 要额外加光晕的**世界坐标点**（#7：交点/极值点也要加阴影）。
     *
     * 和 [shadowIds] 的分工：那个按 id 给整条曲线加光晕，这个给"零散的点"加 ——
     * 交点是从两条曲线算出来的，没有自己的对象 id。
     */
    highlightPoints: List<GPoint> = emptyList(),
    /**
     * **第二类**高亮点：工具正在选/正在拖的点（#9）。
     *
     * 和 [highlightPoints] 分开是因为它们含义不同：那个是"交点/极值点"（曲线自身的特征），
     * 这个是"你刚点的那一下"（交互反馈）。用同一个颜色的话，用户点了三个点做角度时
     * 分不清哪个是刚选的、哪个是曲线交点。
     */
    secondaryHighlightPoints: List<GPoint> = emptyList(),
    /** 交点/极值点光晕的颜色（默认用曲线配色里的奶油黄，压在深色曲线上很跳）。 */
    highlightColor: Color = CurvePalette[6],
    /** 工具选点/拖点用的颜色（用强调蓝，和曲线配色的黄分得开）。 */
    secondaryHighlightColor: Color = CurvePalette[0]
) {
    // 只在采样结果变化时重建 Path。手势期间 viewport 每帧都在变，
    // 但 plot 不变，所以这里不会重复分配。
    val paths = remember(plot) { buildPaths(plot) }

    // 揭示进度：0 = 还没画，1 = 画完。默认 1（不动画时就是完整显示）。
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(revealIds) {
        if (revealIds.isNotEmpty()) {
            reveal.snapTo(0f)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = REVEAL_MS, easing = FastOutSlowInEasing)
            )
        }
    }

    /**
     * **度量图形的"长出来 / 收回去"**（用户要求："距离，面积这些都是和角度一样的调整"，
     * 动画也要按项目语言给出去）。
     *
     * 每个度量对象各自一条进度（[MeasureAnimations.grow]）：
     *   * 新量出来的 → 0 涨到 1（角度是扇形张开、距离是从第一个端点画到第二个、
     *     面积是从顶点平均位置整块放大）；
     *   * 被色点关掉 / 被删掉的 → 1 落到 0（`fade` + `ghosts`，那几帧还画着它）。
     *
     * 为什么按**对象**记而不是像曲线那样一个全局进度：曲线那边"新函数被画出来"
     * 是整条列表一起重新采样、一个进度就够；而度量是**手工一个一个量出来**的，
     * 用户量第二条时，第一条不该跟着重播（那看起来像是图重画了一次）。
     *
     * [primed] 是"第一次进这一屏不播"的闩：从列表切回图像界面时，
     * 屏幕上已有的度量不该集体长一遍 —— 它们不是"刚出现"。
     */
    val measureAnim = remember { MeasureAnimations() }
    val primed = remember { mutableStateOf(false) }
    val measureSig = objects.filterIsInstance<GraphMeasureObject>().map { it.id to it.visible }
    LaunchedEffect(measureSig) {
        val live = objects.filterIsInstance<GraphMeasureObject>()
        val visibleNow = live.filter { it.visible }
        val visibleIds = visibleNow.map { it.id }.toSet()

        // ---- 第一次进这一屏：把已有的都记成"已经画好了"，不播动画 ----
        if (!primed.value) {
            primed.value = true
            for (o in visibleNow) measureAnim.grow[o.id] = 1f
            measureAnim.prev.clear()
            for (o in visibleNow) measureAnim.prev[o.id] = o
            return@LaunchedEffect
        }

        // ---- 收回去：上一次看得见、这次看不见了的（被色点关掉，或者被删掉）----
        // 删掉的那种也从这里走：对象已经不在 `objects` 里了，只有 `prev` 还留着它，
        // 所以"删了"和"关掉"是同一条淡出路径（用户看到的行为也一致）。
        for ((id, o) in measureAnim.prev) {
            if (id in visibleIds) continue
            measureAnim.grow.remove(id)
            measureAnim.ghosts[id] = o
            measureAnim.fade[id] = 1f
            launch {
                animate(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = MEASURE_FADE_MS,
                        easing = FastOutSlowInEasing
                    )
                ) { value, _ -> measureAnim.fade[id] = value }
                // 淡完就把它整条忘掉，下一帧不会再画（表也不会长胖）
                measureAnim.ghosts.remove(id)
                measureAnim.fade.remove(id)
            }
        }

        // ---- 长出来：这次新看得见的 ----
        // 正在淡出的（fade 里还有它）不重播：用户刚关又开时，让那一次淡出收尾更自然。
        for (o in visibleNow) {
            if (measureAnim.grow.containsKey(o.id) || measureAnim.fade.containsKey(o.id)) continue
            measureAnim.grow[o.id] = 0f
            launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = MEASURE_GROW_MS,
                        easing = FastOutSlowInEasing
                    )
                ) { value, _ -> measureAnim.grow[o.id] = value }
            }
        }

        // ---- 记下这一次"看得见的是谁"，下一轮拿它做差集 ----
        measureAnim.prev.clear()
        for (o in visibleNow) measureAnim.prev[o.id] = o
    }

    // 刻度数字走原生画布：Paint 建一次反复用，每帧只改属性，不再分配对象。
    val labelPaint = remember { android.graphics.Paint() }
    // 刻度数字的**迟滞状态**（"上一帧哪些刻度值画过"）：绘制阶段读写，不进 Compose
    // 状态（理由见 LabelSticky）。它的生命周期跟着这一块画布走，所以 remember 一次就够。
    val labelSticky = remember { LabelSticky() }
    // 几何对象上的文字标签单独一个 Paint：刻度那个每帧会被重新配置，
    // 共用的话后面画的会改掉前面的状态（描边/填充/颜色来回切）。
    val objectPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    }
    // TextUnit → 像素必须在 Density 作用域里换算。
    val density = LocalDensity.current
    val labelPx = with(density) { metrics.fontTiny.toPx() }
    // ⚠️ 这个 density 倍数在刻度数字里**只剩相邻字距用得上**（`LABEL_MIN_GAP_DP` 是 dp 口径，
    // 乘上它才是像素）；其余判据都换成了按**屏幕短边**算的几何量 ——
    // 量化步长 `labelFollowQuantumPx`、预算 `labelFollowBudgetPx` 里的几何项、
    // 行级兜底 `labelsTooDenseForRow` —— 它们故意不跟 density 走，
    // 因为同一块 466px 的屏有的设备报 density 2、有的报 1。
    // 网格格距**已经不看 density 了** —— 它按"屏幕短边 × 比例"算（`metrics.sizePx`，
    // 见 chartGridStep），所以同一块屏无论报 density 1 还是 2，格子的像素数都一样。
    // 之所以必须在 composable 这一层取：`Canvas` 的 lambda 是 DrawScope
    // （不是 composable 作用域），在那里读不到 `LocalDensity`。
    // 它是"每 dp 多少像素"这个常量，和视口无关，所以不违反"不许缓存依赖视口的东西"。
    val densityScale = density.density
    val originDotRadius = metrics.sizePx * ORIGIN_DOT_FRACTION
    val safeInset = metrics.sizePx * SAFE_INSET_FRACTION

    /**
     * 这块屏**是不是圆的**（圆表的表壳会把四角切掉，方屏/手机不会）。
     *
     * ## 为什么刻度数字必须知道这件事（用户报的"贴边没实装"的第二条真因）
     *
     * `bandHalfWidth` / `fitsRoundScreen` 用的是 `WatchMetrics.circleWidthFraction()`：
     * 它按"这个高度上的内切圆弦宽"算可用宽度。那个公式对圆表是对的，但它以前
     * **对所有屏都生效** —— 而画布是 `fillMaxSize`，手机屏高（2400px）远大于宽（1080px）：
     *
     * | 屏 | x 轴数字那一行所在高度 | 按圆算出来的可用宽度 |
     * |---|---|---|
     * | 圆表 466×466（行在竖直中央附近） | y ≈ 0.55h | ±205px（≈ 整个可见宽度）✓ 合理 |
     * | 手机 1080×2400（轴滑出屏幕、行贴底） | y ≈ 0.97h | **±110px（屏中央那一条窄柱）** ❌ |
     *
     * 于是手机上"轴一滑出屏幕，最下面那排数字就只剩中间两三个、贴不到边缘" ——
     * 贴边代码没错，是**可用区间**被一个不属于它的公式缩掉了。
     * 现在只在真圆屏上套那套弦宽，方屏/手机只留矩形安全内缩。
     * **圆表分支一个字节都没变**（`isRound == true` 走的是原来那条路）。
     */
    val isRound = LocalConfiguration.current.isScreenRound

    Canvas(modifier = modifier) {
        // 网格与坐标轴永远按当前视口画，所以它们永远是"即时跟随手指"的基准。
        // 顺序：网格 → 坐标轴 → 刻度数字 → 曲线（曲线最后，压在坐标系上面）。
        if (settings.showGrid) {
            // 格距按**屏幕短边**算，所以传的是 metrics.sizePx（根 BoxWithConstraints 的短边像素，
            // 和 metrics.iconButton / 字号同一个基准），不再是 density。
            drawGrid(
                viewport, settings.coordinateSystem, gridColor, axisColor, strokeGrid, metrics.sizePx
            )
        }
        if (settings.showAxes) {
            drawAxes(viewport, settings.coordinateSystem, axisColor, strokeAxis, originDotRadius)
        }
        if (settings.showNumbers) {
            drawLabels(
                vp = viewport,
                cs = settings.coordinateSystem,
                metrics = metrics,
                labelColor = labelColor,
                textPx = labelPx,
                safeInset = safeInset,
                paint = labelPaint,
                densityScale = densityScale,
                // 圆屏才按"弦宽"收窄可用区间（见 isRound 那段：这是手机上贴边失效的真因之一）
                isRound = isRound,
                // 迟滞状态：判定要"两个阈值"就必然要知道上一帧画过没有（见 LabelSticky）
                sticky = labelSticky
            )
        }

        // 曲线最后画、压在坐标系上面。
        // 新增的函数按揭示进度做一次左→右裁剪（"这支笔把它画了出来"），
        // 已有的函数直接整条出现、不参与动画。
        // 注意网格/坐标轴**不参与**这个裁剪 —— 坐标系本来就该一直在。
        val src = plot.viewport
        val canDraw = src != null &&
            paths.isNotEmpty() &&
            src.widthPx == viewport.widthPx &&
            src.heightPx == viewport.heightPx
        if (canDraw) {
            val delta = src.screenDeltaTo(viewport)
            val k = delta.k.toFloat()
            val sw = if (!delta.isIdentity() && k > 1e-6f) strokeCurve / k else strokeCurve
            val progress = reveal.value

            for (cp in paths) {
                val style = curveStyles[cp.functionId] ?: CurveStyle(
                    color = CurvePalette[cp.colorIndex % CurvePalette.size]
                )
                val baseWidth = strokeCurve * style.widthScale
                val sw = if (!delta.isIdentity() && k > 1e-6f) baseWidth / k else baseWidth
                val w = if (delta.isIdentity()) baseWidth else sw
                val shadowed = cp.functionId in shadowIds
                // 只有"新增的"且动画还没跑完，才做裁剪
                if (progress < 0.999f && cp.functionId in revealIds) {
                    clipRect(right = size.width * progress) {
                        if (delta.isIdentity()) {
                            drawCurve(cp, w, style, shadowed)
                        } else {
                            withTransform({
                                translate(delta.tx.toFloat(), delta.ty.toFloat())
                                scale(k, k, pivot = Offset.Zero)
                            }) {
                                drawCurve(cp, w, style, shadowed)
                            }
                        }
                    }
                } else {
                    if (delta.isIdentity()) {
                        drawCurve(cp, w, style, shadowed)
                    } else {
                        withTransform({
                            translate(delta.tx.toFloat(), delta.ty.toFloat())
                            scale(k, k, pivot = Offset.Zero)
                        }) {
                            drawCurve(cp, w, style, shadowed)
                        }
                    }
                }
            }
        }

        // ---- 几何对象（#9）：点 / 直线·线段·射线 / 度量 ----
        // 画在曲线**上面**：这些是用户用手点出来的东西，被曲线压住就等于没画。
        //
        // 这里**故意不做 `objects.isNotEmpty()` 的短路**：正在淡出的度量已经不在
        // `objects` 里了（被删掉的那条只剩 ghost），一短路的话"删掉最后一个对象"
        // 就是瞬间消失、看不到淡出。空表时这个函数什么都不画，代价可以忽略。
        objectPaint.textSize = labelPx
        drawObjects(
            vp = viewport,
            objects = objects,
            shadowIds = shadowIds,
            labelPaint = objectPaint,
            // 度量图形的出现/消失进度（见 measureAnim）。传进去而不是在外面算好：
            // 三个 map 都是 Compose 状态，**在绘制阶段读**才能只让画布重画、
            // 不每帧重组整个 GraphCanvas（和上面曲线的 reveal.value 同一个理由）。
            measureAnim = measureAnim
        )

        // ---- 交点/极值点的高亮光晕（#7 后半）----
        if (highlightPoints.isNotEmpty()) {
            drawPointHalos(viewport, highlightPoints, highlightColor)
        }
        // ---- 工具正在选的点 / 正在拖的描点（#9）----
        if (secondaryHighlightPoints.isNotEmpty()) {
            drawPointHalos(viewport, secondaryHighlightPoints, secondaryHighlightColor)
        }
    }
}

private fun DrawScope.drawCurve(cp: CurvePath, width: Float, style: CurveStyle, shadowed: Boolean) {
    val effect = if (style.lineStyle.isDashed) {
        PathEffect.dashPathEffect(floatArrayOf(style.lineStyle.dashOn, style.lineStyle.dashOff))
    } else {
        null
    }
    // 阴影先画（更粗、同色 60% 透明），曲线本体再压在上面
    if (shadowed) {
        drawPath(
            path = cp.path,
            color = style.color.copy(alpha = SHADOW_ALPHA),
            style = Stroke(
                width = width * SHADOW_WIDTH_SCALE,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = effect
            )
        )
    }
    drawPath(
        path = cp.path,
        color = style.color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = effect
        )
    )
}

/** 选中阴影的不透明度（用户要求 60%）。 */
private const val SHADOW_ALPHA = 0.6f

/** 阴影比曲线本体粗多少倍 —— 太粗会糊成一片，2.6 倍在 466px 屏上是"一圈光晕"。 */
private const val SHADOW_WIDTH_SCALE = 2.6f

private fun buildPaths(plot: PlotResult): List<CurvePath> {
    // 同一个函数的所有折线合并进一条 Path：
    // 隐函数会产出成百上千条两点线段，一条 Path 一次 drawPath 才扛得住。
    val grouped = LinkedHashMap<Long, MutableList<Curve>>()
    for (c in plot.curves) {
        grouped.getOrPut(c.functionId) { ArrayList() }.add(c)
    }
    return grouped.map { (_, curves) ->
        val path = Path()
        for (c in curves) {
            val pts = c.points
            if (pts.size < 4) continue
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) {
                path.lineTo(pts[i], pts[i + 1])
                i += 2
            }
        }
        CurvePath(curves.first().functionId, path, curves.first().colorIndex)
    }
}

// ---------------------------------------------------------------------------
// 几何对象（#9）：点 / 直线·线段·射线 / 度量 + 交点高亮（#7）
//
// 都画在曲线**上面**：这些是用户用手点出来的东西，被曲线压住就等于没画。
// 坐标全部是世界坐标，这里统一用 Viewport 换成屏幕像素，
// 所以缩放/平移时它们和曲线严格同步（和坐标系本身一个道理）。
// ---------------------------------------------------------------------------

/** 点的半径（占屏宽比例）。 */
private const val OBJECT_POINT_R = 0.017f

/** 光晕半径（交点/选中点）。 */
private const val OBJECT_HALO_R = 0.034f

/** 对象线宽（占屏宽比例，比曲线细一点：它们是辅助图元）。 */
private const val OBJECT_STROKE = 0.005f

/**
 * 度量**图形**的填充透明度（角度扇形、面积多边形共用这一个数）。
 *
 * 用户要求"弧与两条边围成的扇形**填半透明色**"、"面积……**半透明填充**"，
 * 而且"透明度低到不挡住底下的曲线"。
 * 0.20 是"看得出这块是什么、但压在下头的曲线和网格还读得清"的那一档：
 * 再淡就跟没画一样，再浓就成了盖在图上的一张贴纸（表盘只有 233dp，盖住就看不了图）。
 */
private const val MEASURE_FILL_ALPHA = 0.20f

/** 扇形半径的上下限（占屏幕短边比例），见 [DrawScope.drawAngleFigure]。 */
private const val ANGLE_R_MIN = 0.022f
private const val ANGLE_R_MAX = 0.24f

/**
 * 距离那条**尺寸线**的透明度。
 *
 * 用户说距离图形"可淡一点"：它比扇形/多边形更"细"，太实会显得像一条曲线，
 * 淡一档之后一眼能看出"这是量出来的尺寸，不是一条函数"。
 */
private const val DISTANCE_LINE_ALPHA = 0.85f

/**
 * 尺寸线两端刻度帽的长度：取这段距离的 **12%**（世界长度 → 跟着缩放走）。
 *
 * 再夹到屏幕短边的 `DISTANCE_CAP_MIN ~ DISTANCE_CAP_MAX`：
 *  * 距离极短（两个点几乎重合）时帽子会盖住整条线 → 压到下限；
 *  * 距离极长（横穿全屏）时帽子会变成两道长长的竖线 → 压到上限。
 */
private const val DISTANCE_CAP_RATIO = 0.12f
private const val DISTANCE_CAP_MIN = 0.018f
private const val DISTANCE_CAP_MAX = 0.060f

/**
 * 度量图形"长出来 / 收回去"一遍的时长。
 *
 * 和曲线的揭示（`REVEAL_MS`）是同一套语言：新东西出现时给自己一个交代。
 * 200ms 落在本项目"交互动画 120~260ms"那一档里；淡出短一点（160ms），
 * 因为"消失"不该比"出现"更抢眼。
 */
private const val MEASURE_GROW_MS = 200
private const val MEASURE_FADE_MS = 160

/**
 * 度量图形出现/消失的动画状态（`GraphCanvas` 里 remember 一份，绘制时读）。
 *
 * 为什么不放在 `GraphObject` 上：那是**数据**（要存进文件），而这三个是
 * "这一帧画到哪儿了"的**临时观感**，两者生命周期完全不同（同一份数据在另一台设备上
 * 打开时不该从"正在张开"开始）。和曲线的 `reveal`（`revealIds`）是同一个思路。
 */
private class MeasureAnimations {
    /** 新出现的度量：0 → 1（图形"长出来"）。 */
    val grow = mutableStateMapOf<Long, Float>()

    /** 刚被关掉/删掉的度量：1 → 0（图形"收回去"）。 */
    val fade = mutableStateMapOf<Long, Float>()

    /** 正在淡出的那些对象本身（已经从 `visible` 掉下来了，得留着才能画最后几帧）。 */
    val ghosts = mutableStateMapOf<Long, GraphMeasureObject>()

    /**
     * 上一次"看得见的是谁"（做差集用）。
     *
     * 普通 `HashMap` 而不是 state map：它只在 [LaunchedEffect] 里读写，
     * 没有任何绘制代码看它 —— 用 state map 只会白白多触发无效的失效。
     */
    val prev = HashMap<Long, GraphMeasureObject>()
}

private fun DrawScope.drawObjects(
    vp: Viewport,
    objects: List<GraphObject>,
    shadowIds: Set<Long>,
    labelPaint: android.graphics.Paint,
    /**
     * 度量图形的出现/消失进度。
     *
     * ⚠️ 在**绘制阶段**读它（三个 map 都是 Compose 状态）：动画每帧只让画布重画，
     * 不会每一帧重组整个 `GraphCanvas` —— 和曲线的 `reveal.value` 同一个理由。
     */
    measureAnim: MeasureAnimations? = null
) {
    val pointR = size.minDimension * OBJECT_POINT_R
    val haloR = size.minDimension * OBJECT_HALO_R
    val stroke = size.minDimension * OBJECT_STROKE

    // 先画线（被点压住没关系），再画度量，最后画点 —— 点永远在最上面
    for (o in objects) {
        if (!o.visible || o !is GraphLineObject) continue
        drawObjectLine(vp, o, stroke, shadowIds.contains(o.id))
    }
    for (o in objects) {
        if (o !is GraphMeasureObject) continue
        // ---- 度量的**两套可见性**（用户要求，本轮推广到全部度量）----
        //
        // 用户原话："距离，面积这些都是和角度一样的调整"：
        //   * 左边的色点（[GraphObject.visible]）管**图形** ——
        //     角度是"顶点 + 两段边之间的弧 + 扇形阴影"、距离是"两端的尺寸线"、
        //     面积是"多边形轮廓 + 填充"；
        //   * 右边的 `123`（[GraphMeasureObject.showValue]）管**数值文字**。
        //
        // 两者**互相独立**，所以这里**不再**用 `if (!o.visible) continue` 跳过整条度量：
        // 关掉色点只表示"图形不画了"，数值该画还得画（用户要的就是两个开关各管一件事）。
        drawMeasure(
            vp = vp,
            o = o,
            stroke = stroke,
            pointR = pointR,
            labelPaint = labelPaint,
            shadowed = shadowIds.contains(o.id),
            figureVisible = o.visible,
            drawValue = o.showValue,
            grow = measureAnim?.grow?.get(o.id) ?: 1f,
            alpha = 1f
        )
    }
    // 正在淡出的度量：只画**图形**（数值跟着图形一起收，不然会出现
    // "图形没了、数字还飘在半空"），画完那几帧就整条不再出现
    measureAnim?.let { anim ->
        if (anim.ghosts.isNotEmpty()) {
            for (g in anim.ghosts.values) {
                val a = anim.fade[g.id] ?: 0f
                if (a <= 0.01f) continue
                drawMeasure(
                    vp = vp,
                    o = g,
                    stroke = stroke,
                    pointR = pointR,
                    labelPaint = labelPaint,
                    shadowed = false,
                    figureVisible = true,
                    drawValue = false,
                    grow = 1f,
                    alpha = a
                )
            }
        }
    }
    for (o in objects) {
        if (!o.visible || o !is GraphPointObject) continue
        val cx = vp.toScreenX(o.point.x)
        val cy = vp.toScreenY(o.point.y)
        val color = CurvePalette[o.colorIndex.mod(CurvePalette.size)]
        if (shadowIds.contains(o.id)) {
            drawCircle(color.copy(alpha = SHADOW_ALPHA), radius = haloR, center = Offset(cx, cy))
        }
        // 吸附来的特征点画成空心圆：一眼区分"曲线上的特征点"和"随手点的点"
        if (o.kind == SnapKind.POINT || o.kind == SnapKind.OTHER) {
            drawCircle(color, radius = pointR, center = Offset(cx, cy))
        } else {
            drawCircle(color, radius = pointR, center = Offset(cx, cy), style = Stroke(width = stroke))
        }
        drawLabel(labelPaint, o.name, cx + pointR * 1.6f, cy - pointR * 1.6f, color)
    }
}

/** 一条直线/线段/射线：按 [GLineKind] 决定往两头延伸多少。 */
private fun DrawScope.drawObjectLine(
    vp: Viewport,
    o: GraphLineObject,
    stroke: Float,
    shadowed: Boolean
) {
    val line = o.line
    if (line.isDegenerate) return
    val color = CurvePalette[o.colorIndex.mod(CurvePalette.size)]
    // 直线/射线要延伸到画布之外：取一个足够长的参数量（屏幕对角线的量级），
    // 超出部分由 Canvas 自己裁掉，不需要手写裁剪算法。
    val extent = (size.width + size.height) / kotlin.math.max(1e-6, line.length).toFloat()
    val t0 = when (line.kind) {
        GLineKind.LINE -> -extent
        GLineKind.RAY -> 0f
        GLineKind.SEGMENT -> 0f
    }
    val t1 = when (line.kind) {
        GLineKind.LINE -> 1f + extent
        GLineKind.RAY -> 1f + extent
        GLineKind.SEGMENT -> 1f
    }
    val a = line.pointAt(t0.toDouble())
    val b = line.pointAt(t1.toDouble())
    val x0 = vp.toScreenX(a.x)
    val y0 = vp.toScreenY(a.y)
    val x1 = vp.toScreenX(b.x)
    val y1 = vp.toScreenY(b.y)
    if (shadowed) {
        drawLine(
            color = color.copy(alpha = SHADOW_ALPHA),
            start = Offset(x0, y0),
            end = Offset(x1, y1),
            strokeWidth = stroke * SHADOW_WIDTH_SCALE,
            cap = StrokeCap.Round
        )
    }
    drawLine(
        color = color,
        start = Offset(x0, y0),
        end = Offset(x1, y1),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    // 端点小圆点：线段/射线才有明确的"起点"，直线没有。
    // **变换出来的折线碎片不画**：一条函数做轴对称会拆成上百段首尾相接的线段，
    // 每段都点一个端点上，接缝处会连成一串珠子（那位同事做函数对称时发现的）。
    if (line.kind != GLineKind.LINE && o.role != FRAGMENT_ROLE) {
        val r = stroke * 1.6f
        drawCircle(color, radius = r, center = Offset(x0, y0))
    }
}

/**
 * 一个度量：**图形**（各度量自己那一种）+ **数值文字**，两者各自独立开关。
 *
 * 用户原话（本轮追加的那句）："距离，面积这些都是和角度一样的调整" ——
 * 所以三种度量现在是同一套模型：
 *
 * | 度量 | 图形（[figureVisible] = `visible`，对象行的色点） | 数值（[drawValue] = `showValue`，对象行的 `123`） |
 * |---|---|---|
 * | 角度 | 顶点 + 两段边之间的**弧** + 半透明扇形（[drawAngleFigure]） | `36.87°` |
 * | 距离 | 两个端点之间的**尺寸线**（测量线 + 两端刻度帽，[drawDistanceFigure]） | `5` |
 * | 面积 | **多边形轮廓 + 半透明填充**（[drawAreaFigure]） | `12.5²` |
 *
 * ## 两条硬规则（用户点名）
 *
 *  1. **图形不包含辅助对象**："距离不把两个端点'收进来'，面积不把那些点据为己有" ——
 *     所以这里**不再画参与点上的小圆点**了（以前 `screen.forEach { drawCircle(...) }`
 *     等于把那些点又画了一遍）。点仍然是独立对象，由上面那段 `GraphPointObject`
 *     循环负责画（连同它们自己的名字）。
 *  2. **两套可见性互相独立**：`visible` 只管图形、`showValue` 只管数字 ——
 *     所以关掉色点时数值**照样画**（用户要的就是两个开关各管一件事；
 *     两个都关才是"这条度量彻底不显示"）。
 *
 * [alpha] 只给"正在淡出"的那几帧用（见 [MeasureAnimations]）：活着的度量恒为 1。
 */
private fun DrawScope.drawMeasure(
    vp: Viewport,
    o: GraphMeasureObject,
    stroke: Float,
    pointR: Float,
    labelPaint: android.graphics.Paint,
    shadowed: Boolean,
    figureVisible: Boolean,
    drawValue: Boolean,
    /** 图形"长出来"的进度（0 = 刚出现、1 = 完整）。 */
    grow: Float = 1f,
    /** 整条度量的透明度（淡出用；活着的恒为 1）。 */
    alpha: Float = 1f
) {
    if (o.points.size < 2) return
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.001f) return
    val g = grow.coerceIn(0f, 1f)
    val color = CurvePalette[o.colorIndex.mod(CurvePalette.size)]
    val screen = o.points.map {
        Offset(vp.toScreenX(it.x), vp.toScreenY(it.y))
    }

    // ---- 图形（色点开关）----
    if (figureVisible) {
        when (o.kind) {
            // 角度：只有这个角本身（顶点 + 弧 + 扇形阴影），不含那两条线段
            // （用户原话："角度只包含这个角，不包含两条线段……图形就是一段有阴影的扇形，边缘有弧"）
            MeasureKind.ANGLE -> if (screen.size >= 3) {
                drawAngleFigure(screen[0], screen[1], screen[2], color, stroke, pointR, shadowed, g, a)
            }

            // 距离：两个端点之间的尺寸线（不画端点上的点 —— 那是独立对象）
            MeasureKind.DISTANCE -> drawDistanceFigure(screen[0], screen[1], color, stroke, shadowed, g, a)

            // 面积：顶点依次连线围成的多边形（轮廓 + 半透明填充，不画顶点上的点）
            MeasureKind.AREA -> drawAreaFigure(screen, color, stroke, shadowed, g, a)
        }
    }

    // ---- 数值（`123` 开关）----
    if (!drawValue) return
    // 数值写在"图形自己的中心"旁边，而不是压在某个参与点上：
    // 那些点上本来就有它们自己的名字（`A`、`B`…），两个字叠在一起谁都读不清。
    val anchor = when (o.kind) {
        // 角度写在顶点旁（角的张开处，一眼对应得上）
        MeasureKind.ANGLE -> screen[1]

        // 距离写在测量线的中点
        MeasureKind.DISTANCE -> Offset((screen[0].x + screen[1].x) / 2f, (screen[0].y + screen[1].y) / 2f)

        // 面积写在各个顶点的平均位置（凹多边形时可能落在图形外，但那是**标签**，
        // 只要不压住顶点名字就行；真要"一定在内部"就得做三角剖分，不值得）
        MeasureKind.AREA -> Offset(
            screen.map { it.x }.sum() / screen.size,
            screen.map { it.y }.sum() / screen.size
        )
    }
    drawLabel(labelPaint, o.valueText(), anchor.x + pointR, anchor.y - pointR * 2f, color)
}

/**
 * 角度图形：顶点 + 两个方向之间的**弧** + 半透明扇形阴影。
 *
 * ## 半径怎么取（用户要求："半径取'到两条边较短者的某个比例'……最好跟着视图缩放走"）
 *
 * 半径 = **两条边里较短那条** × [com.functiondrawer.watch.ui.ANGLE_ARM_RATIO]（0.35）。
 *
 * 为什么是"世界坐标下的长度"而不是固定像素：三个点（顶点和两个边上的点）都是
 * **世界坐标**存下来的，换到屏幕上时长度已经按当前缩放比例换算过了 —— 所以
 * 放大图像时扇形跟着变大、缩小跟着变小，和曲线、点、网格**严格同步**
 * （固定像素的话，缩放时只有扇形不动，看起来就像贴死在屏幕上的一块色斑）。
 *
 * 两头再夹一下（[ANGLE_R_MIN] ~ [ANGLE_R_MAX]，占屏幕短边）：
 *   * 两条边极短时弧线会缩成一个点，用户看不出量的是哪个角 → 抬到下限；
 *   * 两条边极长（比如量了一条横穿全屏的直线和一个线段）时扇形的弧会伸到屏幕外、
 *     把半张图盖住 → 压到上限。
 * 夹的是**极端情况**，正常视野下半径完全由世界长度决定。
 *
 * **线宽**没有跟着缩放：它取的是屏幕像素（和曲线、直线、点同一个约定）。
 * 世界单位的线宽在缩到很小时会细到看不见、放到很大时会粗成一坨 —— 那才是"糊成一坨"。
 * 跟着缩放走的是**几何尺寸**（半径、弧长、刻度帽长度）。
 */
private fun DrawScope.drawAngleFigure(
    a0: Offset,
    v: Offset,
    a1: Offset,
    color: Color,
    stroke: Float,
    pointR: Float,
    shadowed: Boolean,
    grow: Float,
    alpha: Float
) {
    // 顶点：用户要求角度图形里包含它（"顶点 + 两段边之间的圆弧 + 半透明扇形阴影"）
    drawCircle(color.copy(alpha = alpha), radius = pointR * 0.7f, center = v)

    val arm0 = hypot(a0.x - v.x, a0.y - v.y)
    val arm1 = hypot(a1.x - v.x, a1.y - v.y)
    val shortest = min(arm0, arm1)
    if (!shortest.isFinite() || shortest <= 0f) return
    // 刚量出来的角度：扇形从第一条边**张开**到最后一条边（0 → 完整，200ms）
    if (grow <= 0.001f) return

    val r = (shortest * ANGLE_ARM_RATIO)
        .coerceIn(size.minDimension * ANGLE_R_MIN, size.minDimension * ANGLE_R_MAX)

    // 屏幕坐标下 0° = 正右、角度顺时针增大（y 轴朝下），和 atan2(dy, dx) 一致
    val startDeg = atan2(a0.y - v.y, a0.x - v.x) * 180f / PI.toFloat()
    val endDeg = atan2(a1.y - v.y, a1.x - v.x) * 180f / PI.toFloat()
    // 取**短的那一侧**：两条边之间有两个角（α 和 360°−α），要的是量出来的那个
    // ——`angleAt` 给的是 0~180°，所以差角规范化到 (−180, 180] 就是它。
    var sweep = endDeg - startDeg
    while (sweep > 180f) sweep -= 360f
    while (sweep <= -180f) sweep += 360f
    if (grow < 1f) sweep *= grow

    val topLeft = Offset(v.x - r, v.y - r)
    val diameter = Size(r * 2f, r * 2f)

    // 选中/高亮时先垫一层更淡更大的扇形（和曲线、直线那套"影子"同一个语言）
    if (shadowed) {
        val rs = r * SHADOW_WIDTH_SCALE
        drawArc(
            color = color.copy(alpha = SHADOW_ALPHA * MEASURE_FILL_ALPHA * alpha),
            startAngle = startDeg,
            sweepAngle = sweep,
            useCenter = true,
            topLeft = Offset(v.x - rs, v.y - rs),
            size = Size(rs * 2f, rs * 2f)
        )
    }

    // 扇形本体：用 drawArc(useCenter = true) 画（它自带"扇心 + 弧"围成的闭合区域）
    drawArc(
        color = color.copy(alpha = MEASURE_FILL_ALPHA * alpha),
        startAngle = startDeg,
        sweepAngle = sweep,
        useCenter = true,
        topLeft = topLeft,
        size = diameter
    )
    // 弧线描一下（用户要求："弧线描一下"）：不描的话半透明填充的边缘很虚，
    // 在深色底上几乎看不出弧在哪
    drawArc(
        color = color.copy(alpha = alpha),
        startAngle = startDeg,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = diameter,
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )
}

/**
 * 距离图形：两个端点之间的一条**测量线**，两端各一小段垂直的**刻度帽**
 * （工程图上"尺寸线"的画法）。
 *
 * 用户要求："两个端点之间的**测量线**（建议：两端各一小段垂直线段/刻度帽的'尺寸线'画法，
 * 或者直接在两点之间画一条带短竖帽的线段），用对象自己的颜色、可淡一点"。
 *
 * 三个细节都是刻意的：
 *   * **不画端点上的点**（"距离不把两个端点'收进来'"）—— 那两个点是独立对象，
 *     由 `GraphPointObject` 那一段自己画、自己带名字；
 *   * **线淡一档**（[DISTANCE_LINE_ALPHA]）：太实会像一条曲线，淡一档才像"量出来的尺寸"；
 *   * **刻度帽世界长度**（[DISTANCE_CAP_RATIO] 取这段距离的 12%，再夹到屏幕短边
 *     的上下限）：跟着缩放走，两端也永远看得出"这是一段有头有尾的尺寸"。
 */
private fun DrawScope.drawDistanceFigure(
    a0: Offset,
    a1: Offset,
    color: Color,
    stroke: Float,
    shadowed: Boolean,
    grow: Float,
    alpha: Float
) {
    val dx = a1.x - a0.x
    val dy = a1.y - a0.y
    val len = hypot(dx, dy)
    if (!len.isFinite() || len <= 0.001f) return
    val ux = dx / len
    val uy = dy / len
    // "长出来"：从第一个端点朝第二个端点画过去
    val end = Offset(a0.x + dx * grow, a0.y + dy * grow)
    val line = color.copy(alpha = DISTANCE_LINE_ALPHA * alpha)

    if (shadowed) {
        drawLine(
            color = color.copy(alpha = SHADOW_ALPHA),
            start = a0,
            end = end,
            strokeWidth = stroke * SHADOW_WIDTH_SCALE,
            cap = StrokeCap.Round
        )
    }
    drawLine(color = line, start = a0, end = end, strokeWidth = stroke, cap = StrokeCap.Round)

    // 刻度帽：长度按**这段距离**的比例（世界长度 → 跟着缩放），再夹到屏幕短边的上下限。
    // 快画完时才浮出来（`capIn`）：一开始就画的话，线还在长、帽子却已经站在终点了。
    val cap = (len * DISTANCE_CAP_RATIO)
        .coerceIn(size.minDimension * DISTANCE_CAP_MIN, size.minDimension * DISTANCE_CAP_MAX)
    val capIn = ((grow - 0.4f) / 0.6f).coerceIn(0f, 1f)
    if (capIn <= 0.02f) return
    val capColor = line.copy(alpha = line.alpha * capIn)
    val hx = -uy * cap / 2f
    val hy = ux * cap / 2f
    for (p in listOf(a0, a1)) {
        drawLine(
            color = capColor,
            start = Offset(p.x - hx, p.y - hy),
            end = Offset(p.x + hx, p.y + hy),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 面积图形：顶点依次连线围成的**多边形** —— 半透明填充 + 轮廓描边。
 *
 * 用户要求："顶点依次连线组成**多边形轮廓 + 半透明填充**"，以及
 * "面积用偶数-奇数/非零环绕哪种都行，但要能在自交多边形下不崩（自交时按最简单的方式画就行，
 * 别做布尔运算）"。
 *
 * 所以这里就是**一条 `Path` + 两种画法**（先填后描），环绕规则用 `Path` 的默认
 * 非零环绕（NonZero）：自交时中间那块会被填两次色（视觉上更深一点），
 * 但**绝不会崩、也不需要任何布尔运算** —— 这正是用户允许的"最简单的方式"。
 *
 * 和距离一样：**不画顶点上的点**（那些点是独立对象）。
 * "长出来"是把整块多边形以**顶点平均位置**为中心从 0 放大到 1：
 * 只做缩放、不做任何几何运算，所以凹多边形、自交多边形都安全。
 */
private fun DrawScope.drawAreaFigure(
    screen: List<Offset>,
    color: Color,
    stroke: Float,
    shadowed: Boolean,
    grow: Float,
    alpha: Float
) {
    if (screen.size < ToolFlow.MIN_AREA_POINTS) return
    if (grow <= 0.001f) return
    // 坐标里有坏值（NaN/∞）时整个不画：`Path` 里塞 NaN 不会崩，但会画出一团
    // 没法定住的东西；"什么都没画"比"画一片乱码"好（和取坐标那边"不替换坏值"同一个口径）
    if (screen.any { !it.x.isFinite() || !it.y.isFinite() }) return
    val cx = screen.map { it.x }.sum() / screen.size
    val cy = screen.map { it.y }.sum() / screen.size
    val path = Path()
    screen.forEachIndexed { i, p ->
        val x = cx + (p.x - cx) * grow
        val y = cy + (p.y - cy) * grow
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    // 选中/高亮：先描一圈更粗更淡的轮廓（和曲线、直线的"影子"同一个语言）。
    // 面积是**面**，没法像扇形那样"放大一圈"（那要偏移多边形顶点），
    // 所以这里用"加粗的轮廓"表达选中 —— 60% 透明度、1.6 倍线宽，和直线那条影子一致。
    if (shadowed) {
        drawPath(
            path = path,
            color = color.copy(alpha = SHADOW_ALPHA * alpha),
            style = Stroke(
                width = stroke * SHADOW_WIDTH_SCALE,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
    drawPath(path, color.copy(alpha = MEASURE_FILL_ALPHA * alpha))
    drawPath(
        path = path,
        color = color.copy(alpha = alpha),
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** 把一段小字画到原生画布上（刻度数字用的同一套 Paint，避免每帧分配）。 */
private fun DrawScope.drawLabel(
    paint: android.graphics.Paint,
    text: String,
    x: Float,
    y: Float,
    color: Color
) {
    paint.color = color.toArgb()
    // 描边一圈深色底，压在亮曲线上也读得清（和刻度数字同样的做法）
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = size.minDimension * 0.006f
    paint.color = android.graphics.Color.argb(160, 0, 0, 0)
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = color.toArgb()
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** 交点/极值点的高亮光晕（#7）。 */
private fun DrawScope.drawPointHalos(vp: Viewport, points: List<GPoint>, color: Color) {
    val haloR = size.minDimension * OBJECT_HALO_R
    for (p in points) {
        drawCircle(
            color = color.copy(alpha = SHADOW_ALPHA),
            radius = haloR,
            center = Offset(vp.toScreenX(p.x), vp.toScreenY(p.y))
        )
    }
}

// ---------------------------------------------------------------------------
// 坐标系：网格 / 坐标轴 / 刻度数字
//
// 这三层都只依赖**当前**视口，所以永远和手指同步；
// 曲线那一层才需要那段仿射增量（见 GraphCanvas 的注释）。
// ---------------------------------------------------------------------------

/** 网格线（含极坐标同心圆）的迭代上界，防止极端缩放时循环停不下来。 */
private const val MAX_GRID_LINES = 512

/** 极坐标同心圆 / 半径刻度的 k 上界。 */
private const val MAX_POLAR_RINGS = 512

/** 放射线条数（360° / 30°）。 */
private const val MAX_POLAR_RAYS = 12

/** 每多少度一条放射线。 */
private const val RAY_STEP_DEG = 30

/**
 * 数字刻度最多标多少个，同样只是保险用的上界。
 *
 * ⚠️ 这里原本还有一个 `MIN_LABEL_SPACING_DP = 20f`（相邻刻度间距小于 `20dp × density`
 * 就**整排不标**）。本轮删掉了它 —— 它挡的"数字挤在一起"由逐格字宽判定
 * （[LABEL_MIN_GAP_DP]）负责，而它自己会在"屏幕间距 34.3~40px"那几档缩放把
 * **横竖两排一起清空**（用户报的"某些缩放档数字全没了"）。现在只剩
 * [labelsTooDenseForRow]（一个字高，正常参数够不到），理由见它的说明。
 */
private const val MAX_LABEL_TICKS = 512

/**
 * 文字包围盒的上下半径，占字号的比例。
 *
 * `drawLabel` 里的包围盒是 `baseline − 0.85·字号` 到 `baseline + 0.25·字号`，
 * 所以"半高" = (0.85 + 0.25) / 2 = 0.55 个字号。
 * 纵向的半身位只能这么**估**：原生 `Paint` 能量出可靠的字宽（`measureText`），
 * 却没有可靠的字高 —— 而贴边和"不许互相压住"这两处判断都要用到它。
 */
private const val TEXT_HALF_HEIGHT_FRACTION = 0.55f

/** baseline 到文字包围盒**中心**的偏移，占字号的比例（= 0.85 − 0.55，与上面配套）。 */
private const val TEXT_CENTER_OFFSET_FRACTION = 0.30f

/** 极坐标圆心点半径，占屏短边比例。 */
private const val ORIGIN_DOT_FRACTION = 0.008f

/** 圆屏安全余量：文字离屏幕边缘至少留这么多像素，否则会被表壳切掉。 */
private const val SAFE_INSET_FRACTION = 0.06f

/**
 * 这一帧该用的网格 / 刻度步长（世界单位）——目标格距 =
 * **`max(60px, 屏幕短边 × 60/466)`**，也就是"每格 ≈ 短边的 12.9%，且不少于 60px"。
 * **完全不看 density**（`PlotMetrics.gridStepForScreen` 就是这条公式）。
 *
 * 真因：引擎 `Viewport.gridStep` 的口径是"每格约 60 **像素**"（`Viewport.kt`）。
 * 这在 466×466 的圆表上正好，可手机的物理像素多得多 —— 同一句"每格 60px"
 * 到了 1080px 的屏上就是"一屏 18 格"，格线糊成一片、刻度数字叠在一起
 * （用户报的就是这个）。
 *
 * ## 为什么基准是"屏幕短边"，而不是 density
 * 1. **和项目的尺寸语言同源**：`WatchMetrics.frac()` 那一套（图标按钮、行高、字号）
 *    全是"短边 × 比例"，网格走同一条才自洽 —— 刻度数字与格子的**相对大小**
 *    于是在圆表和手机上完全一致；圆表上"数字不重叠"这个用户已经接受的事实，
 *    被原样搬到了大屏上（字号同样是按短边算出来的）。
 * 2. **只认像素、不认 density 自报值**：同一块 466×466 的屏，有的设备报 density 2
 *    （=233dp 逻辑宽）、有的报 1（=466dp，见 `WatchMetrics` 的说明）。按 dp 算的话
 *    这两台机器的格子会差一倍；按短边算，一格多少像素**完全一样**。
 * 3. **466px 处恒等**：`60/466 × 466 = 60px`，而且 `gridTargetPxFor` 是
 *    "`sizePx ≤ 466` 就直接返回 60f、**不做乘除**"的构造性保证（万一算出 59.999999
 *    就掉到下一档去了），所以圆表（含报 density 1 的手表）与引擎原来的
 *    `Viewport.gridStep` **逐位相同**，圆屏观感一个像素都不变。
 * 4. **每一档缩放都变宽**：短边 400 → 60px（下限）、466 → 60px、932 → 120px、
 *    1080 → **139px**。手机 1080 上 `upp = 1/60` 的格距从 60px 变成 **120px**、
 *    一屏 **9 格**（圆表 7.8 格）—— 不再有"某些缩放档位没修到"的问题
 *    （上一版按 `30dp × density` 算时，`1/60`、`1/120` 这些档位正好压在 1-2-5
 *    的阈值上，格距仍是 60px，那恰好是用户抱怨的那一档）。
 *
 * ## 这一步只管"平均疏密"，管不了最坏情况
 * 1-2-5 取整是**固有偏差**：**实际格距 = 目标格距 × (0.571 ~ 1.43)**。
 * 换算成 dp 只跟设备自报的 density 有关，不再是这条公式能管的量 ——
 * 所以"数字不重叠"必须另有一道按**实测字宽**的判定，见 [drawCartesianLabels]。
 *
 * ## 为什么不去改引擎
 * 1. 原理上不该由引擎管：屏幕多大是纯 UI 的事，引擎是"零 Android 依赖的纯数学层"，
 *    它连自己画布多大都得靠传进去的 `Viewport` 才知道。
 * 2. 引擎那份 60px 另有用途：`Plotter` 隐函数 marching 的格子精度 `q.cellPx`
 *    是按自己的视口算的，和"看起来疏密"无关，不能跟着屏幕尺寸动。
 *
 * ## 为什么在这里现算、不进 `remember`
 * 步长依赖**当前**视口（外加画布短边），缓存了缩放时就会慢半拍。
 * 这里每帧只多一次乘法和一次取整；而 `sizePx` 是布局算出来的不变量。
 */
private fun chartGridStep(vp: Viewport, sizePx: Float): Double =
    gridStepForScreen(vp.unitsPerPixel, sizePx)

private fun DrawScope.drawGrid(
    vp: Viewport,
    cs: CoordinateSystem,
    gridColor: Color,
    axisColor: Color,
    strokeGrid: Float,
    /** 屏幕短边（像素）：`metrics.sizePx`。格距按它算，和 density 无关。 */
    sizePx: Float
) {
    if (vp.widthPx <= 1f || vp.heightPx <= 1f) return
    // 按 max(60px, 短边 × 60/466) 算出来的步长（为什么不直接用 vp.gridStep
    // → 见 chartGridStep 的注释）
    val step = chartGridStep(vp, sizePx)
    if (!step.isFinite() || step <= 0.0) return

    when (cs) {
        CoordinateSystem.CARTESIAN -> drawCartesianGrid(vp, gridColor, strokeGrid, step)
        CoordinateSystem.POLAR -> drawPolarGrid(vp, gridColor, axisColor, strokeGrid, step)
        // 复平面就是笛卡尔网格（横轴实部、纵轴虚部）：刻度数字仍然是有意义的数值。
        CoordinateSystem.COMPLEX -> drawCartesianGrid(vp, gridColor, strokeGrid, step)
    }
}

/**
 * 笛卡尔网格：按 [step] 画横竖直线（笔触与改动前完全一致，只是尺子从"死钉 60px"
 * 换成了 `max(60px, 屏幕短边 × 60/466)`）。
 *
 * 步长由调用方算好传进来（[drawGrid] 已经校验过它有限且为正），
 * 这样"网格线"和"刻度数字"一定用的是**同一把尺子** —— 分开各算一次迟早在
 * 取整边界上错开半格，那看起来就是"数字不在线上"。
 */
private fun DrawScope.drawCartesianGrid(
    vp: Viewport,
    gridColor: Color,
    strokeGrid: Float,
    step: Double
) {
    val w = size.width
    val h = size.height

    // 竖线
    var x = ceil(vp.worldLeft / step) * step
    var guard = 0
    while (x <= vp.worldRight && guard++ < MAX_GRID_LINES) {
        val sx = vp.toScreenX(x)
        drawLine(gridColor, Offset(sx, 0f), Offset(sx, h), strokeGrid)
        x += step
    }

    // 横线
    var y = ceil(vp.worldBottom / step) * step
    guard = 0
    while (y <= vp.worldTop && guard++ < MAX_GRID_LINES) {
        val sy = vp.toScreenY(y)
        drawLine(gridColor, Offset(0f, sy), Offset(w, sy), strokeGrid)
        y += step
    }
}

/**
 * 极坐标网格：同心圆 + 放射线。
 *
 * 同心圆只画世界坐标里 `r = k·step` 这些圈 —— 也就是**网格步长的整数倍**，
 * 和笛卡尔网格用的是同一把尺子（同一个 [step] 参数，由 [drawGrid] 按 dp 算好），
 * 切坐标系时观感一致。半径换算：世界半径 / unitsPerPixel = 像素半径。
 */
private fun DrawScope.drawPolarGrid(
    vp: Viewport,
    gridColor: Color,
    axisColor: Color,
    strokeGrid: Float,
    step: Double
) {
    if (vp.unitsPerPixel <= 0.0) return
    val w = size.width
    val h = size.height
    val center = Offset(vp.toScreenX(0.0), vp.toScreenY(0.0))

    // 画到"圆心到最远屏幕角"的距离就够覆盖整个屏幕了；k 另有硬上界。
    val farX = maxOf(abs(center.x), abs(center.x - w))
    val farY = maxOf(abs(center.y), abs(center.y - h))
    val maxRadius = hypot(farX, farY)

    var k = 1
    while (k <= MAX_POLAR_RINGS) {
        val radiusPx = (k * step / vp.unitsPerPixel).toFloat()
        if (!radiusPx.isFinite()) break
        if (radiusPx > maxRadius) break
        if (radiusPx > 0.5f) {
            drawCircle(
                color = gridColor,
                radius = radiusPx,
                center = center,
                style = Stroke(width = strokeGrid)
            )
        }
        k++
    }

    // 放射线：每 30° 一条，共 12 条。0/90/180/270 是主轴，用更亮的 axisColor。
    val rayLength = w + h
    var deg = 0
    var drawn = 0
    while (deg < 360 && drawn < MAX_POLAR_RAYS) {
        val rad = deg * PI / 180.0
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        drawLine(
            color = if (deg % 90 == 0) axisColor else gridColor,
            start = center,
            // 屏幕 y 向下、世界角度逆时针 → 屏幕方向是 (cos, -sin)
            end = Offset(center.x + dirX * rayLength, center.y - dirY * rayLength),
            strokeWidth = strokeGrid
        )
        deg += RAY_STEP_DEG
        drawn++
    }
}

/** 坐标轴：笛卡尔两条直线，极坐标一条极轴 + 圆心点。 */
private fun DrawScope.drawAxes(
    vp: Viewport,
    cs: CoordinateSystem,
    axisColor: Color,
    strokeAxis: Float,
    originDotRadius: Float
) {
    if (vp.widthPx <= 1f || vp.heightPx <= 1f) return
    val w = size.width
    val h = size.height

    when (cs) {
        // 复平面和笛卡尔共用十字轴（横轴实部、纵轴虚部）
        CoordinateSystem.CARTESIAN, CoordinateSystem.COMPLEX -> {
            // 坐标轴：比网格亮一档，但不喧宾夺主。只在轴落在可见区域内时才画。
            val axisY = vp.toScreenY(0.0)
            if (axisY >= 0f && axisY <= h) {
                drawLine(axisColor, Offset(0f, axisY), Offset(w, axisY), strokeAxis)
            }
            val axisX = vp.toScreenX(0.0)
            if (axisX >= 0f && axisX <= w) {
                drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, h), strokeAxis)
            }
        }

        CoordinateSystem.POLAR -> {
            val center = Offset(vp.toScreenX(0.0), vp.toScreenY(0.0))
            // 极轴：θ = 0，从圆心指向右方，长度足够伸出屏幕。
            drawLine(axisColor, center, Offset(w + h, center.y), strokeAxis)
            // 圆心点：极坐标里原点是真正的"中心"，值得一个点
            drawCircle(axisColor, originDotRadius, center)
        }
    }
}

/**
 * 刻度数字的**迟滞状态**：上一个绘制帧里"哪些刻度值真的画出去了"。
 *
 * ## 为什么需要它（用户报的"纵坐标的数字在移动的时候抽搐"）
 *
 * 贴边判定里"这个数字该不该消失"是一个**二值**决定，而它原来只有一个阈值：
 * 需要挪的距离一超过预算就消失、一掉回来就出现。阈值上的任何抖动（手指、子像素换算、
 * 浮点末位）都会让边缘上那个数字在"贴住"和"没了"之间来回翻 —— 看起来就是抽搐。
 *
 * 修法是**两个阈值**（迟滞）：画过的那个用宽松阈值（预算 + 一格量化步长），
 * 没画过的用严格阈值（预算）。两个阈值之间隔着一整格，比这一格更小的抖动怎么抖都
 * 跨不过去。而"上一帧画没画过"是这里唯一的跨帧状态 —— 就记在这里：
 * 三个 `HashSet<Double>`（刻度值），尺寸被可见刻度数钉死在几十个。
 *
 * ## 为什么不是 Compose 状态（不用 `mutableStateMapOf`）
 *
 * 和 [MeasureAnimations] 里那个 `prev` 同一个理由：它只在**绘制阶段**读写，
 * 没有任何组合代码看它。用 Compose 状态只会白白多触发失效与重组，
 * 而且"在绘制里写 state"本身就会引起重组回环。
 *
 * ## 为什么三个集合分开
 *
 * x 行、y 列、极坐标半径行是三条独立的判定链，同一个数值在三条链上的"上一帧状态"
 * 互不相干（比如 `2` 在 x 行画过、在 y 列没画过）。共用一个集合会让迟滞串味。
 * 每个集合每帧都会把"已经不在可见范围里的刻度值"清掉（见 [prune]），
 * 所以长会话也不会长胖。
 */
private class LabelSticky {
    val xRow = HashSet<Double>()
    val yColumn = HashSet<Double>()
    val polarRow = HashSet<Double>()

    /**
     * 扔掉留在集合里的**过期刻度值**：只保留 `[lo − step, hi + step]` 这一段。
     *
     * 为什么要清：缩放换档会不断产生新的刻度值，不清的话集合会随会话一直长。
     * 留一格余量是给"刚好滑出去的邻居"用的 —— 它下一帧可能又被迟滞拉回来。
     */
    fun prune(set: MutableSet<Double>, lo: Double, hi: Double, step: Double) {
        if (!step.isFinite() || step <= 0.0) {
            set.clear()
            return
        }
        set.removeAll { it < lo - step || it > hi + step }
    }
}

/**
 * 刻度数字。
 *
 * 走**原生画布**（`drawContext.canvas.nativeCanvas`）而不是 Compose 的
 * 文字测量 API：手表上字体缩放五花八门，原生 Paint 的行为最可预测，
 * 也不用碰任何实验性接口。
 */
private fun DrawScope.drawLabels(
    vp: Viewport,
    cs: CoordinateSystem,
    metrics: WatchMetrics,
    labelColor: Color,
    textPx: Float,
    safeInset: Float,
    paint: android.graphics.Paint,
    densityScale: Float,
    /** 这块屏是不是圆的（圆屏才按弦宽收窄可用区间，见 composable 里 `isRound` 那段）。 */
    isRound: Boolean,
    /** 迟滞状态（"上一帧哪些刻度值画过"）—— 见 [LabelSticky]。 */
    sticky: LabelSticky
) {
    if (vp.widthPx <= 1f || vp.heightPx <= 1f) return
    if (!textPx.isFinite() || textPx <= 0f) return
    if (vp.unitsPerPixel <= 0.0) return
    // 和网格同一个函数算出来的步长：数字必须落在网格线上，两处分别算迟早在
    // 1-2-5 的取整边界上错开半格（见 chartGridStep）。
    // 它按屏幕短边算（metrics.sizePx），**不经过 density** —— 只有下面那些 dp 口径的
    // 判据（整排不标 / 字宽缝隙 / 贴边距离）才需要 densityScale。
    val step = chartGridStep(vp, metrics.sizePx)
    if (!step.isFinite() || step <= 0.0) return

    // ---- 行级兜底：什么情况下才允许**整排/整列**一个数字都不标 ----
    //
    // 这一条原来是一个 dp 常数（`20dp × density`，圆表 density 2 上 = 40px），
    // 用户报的是"某些缩放档两排数字一起空"。复盘下来它挡的不是真问题：
    //   * **"数字会不会挤在一起"已经由逐格判定管住了** —— 下面每个数字都拿
    //     `paint.measureText` 量真实字宽，和上一个画出去的数字比 `LABEL_MIN_GAP_DP`
    //     （5dp 的缝），挤不下只跳过**那一个**。行级粗线管不住这个几何问题，
    //     只会把"其实还能隔一个画一个"的档位变成"一个都不画"。
    //   * **它也不是性能兜底**：循环次数由 `MAX_LABEL_TICKS` 之类的上界兜着，
    //     "标"与"不标"循环次数完全相同，清空整排省不下任何东西。
    //   * 杀伤面积不小：格距的最小值是目标格距 × 0.5714（1-2-5 的固有偏差）= 圆表 34.3px，
    //     而 40px 的阈值正好压在它上面 → 屏幕间距落在 [34.3, 40) 的那几档缩放整排清空。
    //
    // 现在阈值换成**一个字高**（`短边 × 0.0462`：圆表 21.5px、手机 49.9px），
    // 三块屏上都小于该屏**能出现的最小间距**（34.3 / 79），所以正常参数下不可能触发 ——
    // 它退回真正的兜底（step/upp 算出荒唐值时才拦），"密"只表现为"隔一个画一个"。
    // 判据本身是纯函数：`PlotMetrics.labelsTooDenseForRow`（可离线测，见 PlotMetricsTest）。
    val spacingPx = (step / vp.unitsPerPixel).toFloat()
    if (labelsTooDenseForRow(spacingPx, metrics.sizePx)) return

    paint.color = labelColor.toArgb()
    paint.textSize = textPx
    paint.isAntiAlias = true

    // ⚠️ 这里必须先设好 `paint.textSize`：下面两个函数用 `paint.measureText` 实测字宽，
    // 字号晚一步设就会拿上一次的字号去量（错得还不明显，最难查）。
    when (cs) {
        CoordinateSystem.CARTESIAN ->
            drawCartesianLabels(
                vp, metrics, step, textPx, safeInset, paint, densityScale, isRound, sticky
            )
        CoordinateSystem.POLAR ->
            drawPolarLabels(
                vp, metrics, step, textPx, safeInset, paint, densityScale, isRound, sticky
            )
        // 复平面：刻度数字就是实部/虚部的值，沿用笛卡尔那套
        CoordinateSystem.COMPLEX ->
            drawCartesianLabels(
                vp, metrics, step, textPx, safeInset, paint, densityScale, isRound, sticky
            )
    }
}

/**
 * 笛卡尔刻度：x 轴下方标 x 值，y 轴左侧标 y 值。
 *
 * ## 贴边判定（本轮第三次改；前两版各自栽在一处）
 *
 * **判定只有一处**：`PlotMetrics.pinLabelNearAxis`（夹 + 预算 + 量化三合一，纯函数、离线可测）。
 * 这一版修的是用户报的两条回归 —— "纵坐标的数字在移动的时候会抽搐、横坐标的数字直接显示不出来"：
 *
 * 1. **预算按"刻度可见性"算，不按"屏幕边缘"算。** 上一版的预算是
 *    `safeInset + 2 × 半宽 + 1% 短边`，等于假设"可用区就是整块屏幕"；
 *    可圆屏的可用宽度是按高度收窄的**弦宽**（[bandHalfWidth]），贴底那一行只有屏宽的 ±0.35
 *    —— 一个明明还在屏幕上、也还在弦内看得见的刻度，到可用区边界的距离轻松超过旧预算，
 *    于是被判成"挪不动"丢掉。纵轴靠上下边缘的那几个数字因此一直在丢，而且随滚动
 *    "丢掉哪几个"一直变 —— **用户看到的"抽搐"主要就是它**（实测圆表上 27% 的纵轴刻度
 *    是被横向那一关丢掉的）。现在预算 = "刻度滑到画布边缘那一刻所需的挪动量"，
 *    与可用区**同源**，于是"刻度还在屏幕上就一定挪得动、出屏就不许拖进来"同时成立。
 * 2. **判定要量化。** 判据是"需要挪的距离 ≤ 预算"，两边都随视口连续变化，
 *    于是手抖/子像素换算/浮点末位（1~3px）都能让边缘那个数字在"贴住"和"没了"之间来回翻。
 *    现在比的是**向上量化后**的挪动量（步长 = 短边 × 1.29% ≈ 圆表 6px）：阈值落在一条
 *    固定格线上，比一个步长更小的变化跨不过它 —— 这是不引入跨帧状态的"迟滞"。
 * 3. **夹与闸必须同源。** 夹出来的锚点还要过 `drawLabel → fitsRoundScreen` 的
 *    "完整可见"比较；上一版两处是两套浮点算式，末位差 1 个 ULP（圆表实测 3.1e-5px）就能
 *    把数字判掉。**纵轴是一格一格夹的（只丢最边上一个），x 行是整行夹一次（整排消失）**
 *    —— 这就是"只有横坐标不显示"的来历。现在夹取自带亚像素富余、闸也放过同样的富余。
 *
 * 另外（历史遗留，本轮保留）：[bandHalfWidth] 只在**真圆屏**上走弦宽 ——
 * 手机屏高是屏宽的 2.2 倍，把圆屏公式套上去会让"轴滑出屏幕、数字那一行贴底"时的
 * 可用宽度只剩屏中央那 ±110px（见 composable 里 `isRound` 那段）。
 *
 * ## 两个判据的分工（都没变）
 *
 *   * 放得下的**原样不动**；只差一点点的**贴着边缘停住**；挪不过去的仍然不画 ——
 *     锚点每帧由**当前**视口（`vp.toScreenX/toScreenY`）现算，所以滑动时贴边那个
 *     数字显示的就是此刻屏幕边缘处的坐标值（实时变化）；
 *   * **按实测字宽的不重叠判定**（防重叠的第二道，管最坏情况）：贴边天然会把好几个
 *     数字挤到同一条边上，所以按刻度值从小到大依次判，和上一个**已经画出去**的数字压住
 *     （两盒之间不满 `LABEL_MIN_GAP_DP`）就跳过这一个。
 *     ⚠️ 它是"谁留下"的唯一判据，所以**任何一整排都不画的情况**都必须来自
 *     `pinLabelNearAxis`（而那一条已经在离线单测里钉着"至少画出一个"）。
 *
 * ## 为什么"格距"和这道字宽判定两道都要
 * [chartGridStep] 那道管**平均疏密**：它按 `max(60px, 短边 × 60/466)` 定目标，
 * 让大小屏上"数字 / 格子"的相对大小与圆表一致（字号也是按短边算的）——
 * 但它**保证不了**相邻两个数字放得下，因为 1-2-5 取整是固有偏差
 * （**实际格距 = 目标 × 0.571 ~ 1.43**），而且数字宽度还随位数变化
 * （`5` 很窄、`-1000` 很宽）。所以两道分工：**格距管平均、字宽判定管最坏**。
 * 行级那条粗保护（`labelsTooDenseForRow`）本轮已经降到"一个字高"——
 * 正常参数下够不到，只为坏值兜底；"密"只表现为**隔一个画一个**，不再整排清空
 * （理由见那个函数与 `MAX_LABEL_TICKS` 上面的说明）。
 */
private fun DrawScope.drawCartesianLabels(
    vp: Viewport,
    metrics: WatchMetrics,
    step: Double,
    textPx: Float,
    safeInset: Float,
    paint: android.graphics.Paint,
    densityScale: Float,
    /** 这块屏是不是圆的（方屏/手机不套弦宽，见 `isRound` 那段）。 */
    isRound: Boolean,
    /** 迟滞状态（"上一帧哪些刻度值画过"）—— 见 [LabelSticky]。 */
    sticky: LabelSticky
) {
    val w = size.width
    val h = size.height
    val canvas = drawContext.canvas.nativeCanvas

    // 相邻两个数字之间至少留多宽（dp 口径，乘 density 变像素）。
    // ⚠️ "最多挪多远"**不再**是一个 dp 常数，也**不再**由这里统一算：它按几何逐字算
    // （`labelFollowBudgetPx`：刻度还在屏幕上就一定挪得动、出屏就不许拖进来），
    // 因为写死 dp 的那一版永远小于"边缘那个刻度需要挪的距离"，等于没实装
    // （真因与算式见 `PlotMetrics.LABEL_MIN_SNAP_DP`），而"按屏幕边缘算"的那一版
    // 又漏掉了**可用区比屏幕窄多少**（圆屏弦宽 → 纵轴靠边的数字被丢）。
    val gapPx = LABEL_MIN_GAP_DP * densityScale
    // 纵向半高与"中心到 baseline"的偏移：原生 Paint 量不出可靠字高，按字号估。
    val halfHeight = textPx * TEXT_HALF_HEIGHT_FRACTION
    val centerOffset = textPx * TEXT_CENTER_OFFSET_FRACTION
    val rightAlign = android.graphics.Paint.Align.RIGHT
    // 贴边挪动量的**量化步长**（像素，= 短边 × 1.29% ≈ 圆表 6px）：判定的阈值因此落在
    // 一条固定的格线上，比一个步长更小的视口变化（手抖/子像素/浮点末位）跨不过它，
    // 于是"贴边 ↔ 不贴边"不会在阈值上来回翻 —— 这是用户报的"纵坐标数字抽搐"的对症药。
    // 为什么是短边比例、为什么是 1.29%，见 `PlotMetrics.LABEL_SHIFT_QUANTUM_FRACTION`。
    val quantumPx = labelFollowQuantumPx(metrics.sizePx)

    // ---- x 轴刻度：画在 x 轴下方一点；轴不在屏内就贴着底部（仍留安全余量）----
    // 这一支是改动前就有的、行为也对（"轴在屏内 / 轴出屏"两种情况），原样保留：
    // 它只决定"这一行**想**画在哪个高度"，会不会被裁由下面那次贴边兜底。
    val axisY = vp.toScreenY(0.0)
    val lo = safeInset + textPx
    val hi = h - safeInset
    val base = if (axisY >= 0f && axisY <= h) axisY + textPx * 1.15f else h - safeInset
    val wantedBaseline = if (lo <= hi) base.coerceIn(lo, hi) else h / 2f

    // 整行同一个高度 → 纵向只夹一次（"同一个数字只夹一次"），夹完再拿它算圆屏可用宽度。
    // 这一步顺带修掉一支老毛病：`h - safeInset` 那个"贴下边缘"的高度只算了 baseline，
    // 可文字的包围盒还要再往下探 0.25 个字号 → 整行都压出安全区，
    // `drawLabel` 会把它们**一条不剩**地丢掉（x 轴一滑出屏幕，那排数字就整排消失）。
    //
    // ⚠️ 用的是**保闸版** `pinLabelIntoBand`（往可用区里多收 0.5px 亚像素余量）：
    // 上一版这里用 `snapLabelAnchor`，夹出来的下沿恰好落在 `h - safeInset` 上，
    // 而"下沿"是三次浮点加出来的、末位可能比边界大 1 个 ULP（圆表实测 3.1e-5px）——
    // `drawLabel` 里那道**严格**比较于是把**整排**判掉。纵轴那一列是一格一格夹的，
    // 所以只丢最上/最下那一个数字（用户看到的是"抽"）；x 行是整行夹一次，
    // 一次误判就是**整排一起消失**（用户报的"横坐标的数字直接显示不出来"就是它，
    // 而"整行夹一次"只在轴滑出屏幕、这一行被顶到底部安全线上时才被用到）。
    // 夹不出来（null）只剩"画布小到连半个字高都放不下"这一种情况：那时纵轴那一列用的是
    // 同一段区间、同一个半高，同样一条也放不下，直接收工是对的。
    val rowCenterY = pinLabelIntoBand(
        wantedBaseline - centerOffset, halfHeight, safeInset, h - safeInset
    ) ?: return
    val xBaseline = rowCenterY + centerOffset

    // 这一行文字的横向可用区间：安全内缩 +（**圆屏**）该高度上的内切圆弦宽。
    // 表盘是圆的、越靠上/下越窄，所以"能往哪挪"必须按行高算 ——
    // 用的就是 [fitsRoundScreen] 里同一套 [circleHalfWidthAt]（提前算出来，
    // 因为贴边得先知道"往圆心方向最多能挪到哪"）。
    // ⚠️ 方屏/手机**不套这个公式**（`isRound` 为 false 时 [bandHalfWidth] 直接返回
    // 整个半宽）：那不是"安全区小一点"的问题 —— 手机屏高 2.2 倍屏宽，套上去
    // 底部那一行只剩中间 ±110px，贴边就永远贴不到屏幕侧边（见 `isRound` 那段）。
    val xBand = bandHalfWidth(
        w, h,
        xBaseline - textPx * 0.85f, xBaseline + textPx * 0.25f,
        safeInset, metrics, isRound
    )
    // 这一行自己的可见边界（弦宽 + 安全内缩）—— 范围必须落在它里面，否则数字会被闸判掉
    val xLoNatural = maxOf(safeInset, w / 2f - xBand)
    val xHiNatural = minOf(w - safeInset, w / 2f + xBand)

    // 两条轴共用的**角锚点 + 臂长**（见 `PlotMetrics.labelCornerAnchorPx`）：
    // x 行从角锚点**向右**量一段臂长，y 列从角锚点**向上**量同一段臂长 ——
    // 于是两条范围"从同一点出发、量同一段长度"，不再出现"纵坐标显示多、横坐标显示少"
    // （上一版 x 行的左端跟着弦宽走、y 列的下端恒在屏幕下缘，起点和长度都不一样）。
    // 与可见边界取交是硬要求：范围内每个锚点都过得了完整可见闸。
    val corner = labelCornerAnchorPx(w, h, safeInset, isRound)
    val span = labelRangeSpanPx(w, h, safeInset)
    val xRange = xRowRangeFromCorner(xLoNatural, xHiNatural, corner.xPx, span) ?: return
    val xLo = xRange.loPx
    val xHi = xRange.hiPx

    // 逐个刻度：值从小到大 = 屏幕上从左到右。顺序很要紧 —— 贴边会把边缘那个数字顶到
    // 和邻居同一条竖线上，"谁先占位"直接决定谁留下。
    // lastRight 是**当前帧**的局部变量，不进 `remember`：它依赖当前视口，
    // 缓存了滑动时就会拿上一帧的占位去判这一帧。
    var lastRight = Float.NEGATIVE_INFINITY
    var v = ceil(vp.worldLeft / step) * step
    var guard = 0
    while (v <= vp.worldRight && guard++ < MAX_LABEL_TICKS) {
        // 0 不标：原点是坐标轴的交点，标上去只会和轴线打架
        if (abs(v) > step * 1e-6) {
            val text = formatNumber(v)
            // **实测**字宽：`1` 和 `-1000` 差三倍，按位数估会估出两个都放不下的假象
            val halfW = paint.measureText(text) / 2f
            // 迟滞：上一次这个刻度值画过没有（见 LabelSticky）。画过的用宽松阈值，
            // 没画过的用严格阈值 —— 两个阈值之间那一格就是"噪声跨不过去"的死区。
            val wasDrawn = sticky.xRow.contains(v)
            // 贴边判定（`PlotMetrics.pinLabelNearAxis`）一次答完四件事：画在哪、贴没贴边、
            // 有没有迟滞、还是干脆不画。
            //   * 预算按**几何**算：把"刻度自己还在屏幕上"翻译成"最多允许挪多远"，
            //     于是可用区比屏幕窄多少（圆屏底部那一行的弦宽）自动被算进去 ——
            //     上一版只按"屏幕边缘"算，圆屏上靠边的数字因此被丢掉；
            //   * 判定用**量化后**的挪动量 + **双阈值迟滞**：阈值落在固定格线上、
            //     而且"去"和"回"用的不是同一个数 → 噪声跨不过 → 不抽；
            //   * 夹取自带亚像素富余：夹出来的位置一定过得了 `drawLabel` 那道完整可见闸。
            // 锚点每帧由**当前**视口现算（不缓存），所以贴住边缘时显示的就是此刻的坐标值。
            //
            // ⚠️ "刻度还在不在可见范围里"用的是**这一行自己的角锚点范围** `[xLo, xHi]`
            // （不是整块画布）：范围就是数字的**定义域** —— 范围内的刻度都有数字，
            // 范围外的不画（哪怕网格线还在屏幕上）。这样"显示范围"才和 y 列一致，
            // 而且不会出现"数字被拉进范围却离自己那条线一格多远"（那会被读成邻居的值）。
            val tickX = vp.toScreenX(v)
            val anchor = pinLabelNearAxis(
                labelAnchorPx = tickX,
                axisAnchorPx = tickX,
                halfExtentPx = halfW,
                loPx = xLo,
                hiPx = xHi,
                visibleLoPx = xLo,
                visibleHiPx = xHi,
                quantumPx = quantumPx,
                densityScale = densityScale,
                wasDrawn = wasDrawn,
                hysteresisPx = quantumPx
            )
            if (anchor != null) {
                val left = anchor - halfW
                // 和上一个画出去的数字压住了（缝不到 LABEL_MIN_GAP_DP）就跳过这一个：
                // 不画，也就不占位。宁可少一个数字，也不让两个数字叠成一团。
                if (left - lastRight >= gapPx) {
                    val drawn = drawLabel(
                        canvas, text, anchor, xBaseline,
                        android.graphics.Paint.Align.CENTER, paint, metrics, safeInset, isRound
                    )
                    if (drawn) lastRight = anchor + halfW
                    // 迟滞状态按"真的画出去了没有"更新：没画出去（被闸掉/被别的数字压住）
                    // 就等于"这一帧没有它"，下一帧想回来得按严格阈值走近一点。
                    if (drawn) sticky.xRow.add(v) else sticky.xRow.remove(v)
                } else {
                    sticky.xRow.remove(v)
                }
            } else {
                // 判定说"这一帧不画"：把它从迟滞状态里摘掉 —— 否则它会一直靠宽松阈值赖着
                sticky.xRow.remove(v)
            }
        }
        v += step
    }
    // 清掉已经不在可见范围内的刻度值：换档/平移会不断产生新值，不清的话集合会一直长。
    sticky.prune(sticky.xRow, vp.worldLeft, vp.worldRight, step)

    // ---- y 轴刻度：骑在 y 轴**左边**（右对齐）----
    //
    // ⚠️ 本轮删掉了"轴不在屏内就改贴左缘（左对齐）"那条分支，它是**抽搐**的真因之一：
    // 轴一跨过屏幕左缘，整列数字就被同时换了**对齐方式**和**位置**；而在轴正好压着
    // 左缘的那几像素里，"把右对齐的文字拉回安全区"所需的距离比上一版预算大一点点
    // （差 ≈ 半个字号减去富余项），于是那一小段里 `snapLabelAnchor` 对**每一个**刻度
    // 都返回 null —— 整列数字先"闪一下没了"、再平移一大截出现在别处。
    // 轴从右缘出去时更糟：那一支也会把它贴到**左缘**，等于整列瞬移半个屏宽。
    //
    // 现在只有一条规则：数字**永远**骑在轴左边（右对齐），"要不要挪、挪到哪"交给
    // 横向的 `pinLabelIntoBand` 按可用区算 —— 轴从哪边出去，数字就贴哪一边的边，
    // 而且跨过边缘时位置是**连续**的（右对齐的限制正好等于夹取在那一侧的极限值）。
    val axisX = vp.toScreenX(0.0)
    val labelX = axisX - textPx * 0.5f
    val align = rightAlign
    // 这个对齐方式下锚点是不是文字**右边界**（下面把边界换算成中心要用它）
    val anchorIsRight = true

    // 纵轴这一列：值从小到大 = 屏幕上从下到上，判定顺序同样按它。
    // lastTop 是上一个画出去的数字占到的**最高一行**（屏幕 y 向下，越小越高）。
    //
    // 纵向的可用区间 = **从角锚点向上量同一段臂长**（和 x 行共用 corner / span）：
    // 上一版这里是 `[safeInset, h − safeInset]`（一路顶到屏幕下缘），而 x 行那边
    // 被弦宽截在中间 —— 两条范围起点不同、长度也不同，用户看到"纵坐标显示多、
    // 横坐标显示少"。现在两条都是从 corner 出发、量 span，端头只受各自的可见边界截断。
    val yRange = yColumnRangeFromCorner(safeInset, h - safeInset, corner.yPx, span)
    val yLoColumn = yRange?.loPx ?: return
    val yHiColumn = yRange.hiPx
    var lastTop = Float.POSITIVE_INFINITY
    var yv = ceil(vp.worldBottom / step) * step
    guard = 0
    while (yv <= vp.worldTop && guard++ < MAX_LABEL_TICKS) {
        if (abs(yv) > step * 1e-6) {
            val text = formatNumber(yv)
            val tw = paint.measureText(text)
            // 刻度线自己的屏幕高度（判"还在不在**范围**里"要用它，所以只算一次）
            val tickY = vp.toScreenY(yv)
            // 竖直方向大致居中：baseline 往下挪约半个字高（沿用改动前的 0.36 个字号）
            val wantedY = tickY + textPx * 0.36f
            // 纵向贴边判定（`PlotMetrics.pinLabelNearAxis`）：范围内、但文字盒会探出
            // 范围端头的刻度，数字夹回来贴住那一端；**范围之外**的刻度不画。
            // 注意夹的是**文字中心**，baseline 只是画的时候才要。
            // 判据按**半高**算（纵向上文字的"半身位"就是半高）：上一版这里是
            // `22dp×3 = 66px` 的固定预算，而"刻度贴到屏幕上下缘"需要 65 + 2×25 = 115px，
            // 于是纵轴数字到了边上照样整列消失（用户"纵轴同理"那句的由来）。
            // 现在预算由几何给出 = "刻度滑到范围端头那一刻所需的挪动量"，
            // 同时带**量化 + 双阈值迟滞**（阈值落格线、去与回不同阈值 → 噪声跨不过）。
            // 迟滞的"上一帧画过没有"按**刻度值**记（见 LabelSticky.yColumn）。
            val wasDrawn = sticky.yColumn.contains(yv)
            val cy = pinLabelNearAxis(
                labelAnchorPx = wantedY - centerOffset,
                axisAnchorPx = tickY,
                halfExtentPx = halfHeight,
                loPx = yLoColumn,
                hiPx = yHiColumn,
                visibleLoPx = yLoColumn,
                visibleHiPx = yHiColumn,
                quantumPx = quantumPx,
                densityScale = densityScale,
                wasDrawn = wasDrawn,
                hysteresisPx = quantumPx
            )
            if (cy != null) {
                val top = cy - halfHeight
                val bottom = cy + halfHeight
                // 再按**夹完**的高度算圆屏可用宽度 —— 顺序不能反：圆屏的弦宽随高度变，
                // 按夹之前的高度算出来的区间，夹完可能已经落在更窄的一行上了。
                val yBand = bandHalfWidth(w, h, top, bottom, safeInset, metrics, isRound)
                val yLo = maxOf(safeInset, w / 2f - yBand)
                val yHi = minOf(w - safeInset, w / 2f + yBand)
                // 横向：把文字的**中心**夹进这一行的可用区（`pinLabelIntoBand` 的锚点语义
                // 就是"文字中心"，夹完的 drawX 再按对齐方式换回去）。
                //
                // ⚠️ 横向这里**故意不做"跟随预算"判定**：纵轴刻度的"刻度位置"是纵向的，
                // 横向上它没有"自己被挪出去多远"可言 —— 该不该画由上面那一步（纵向可见性）
                // 决定；只要刻度还在屏幕上，它的数字就该出现在这一行**可见区**的边缘上。
                // 上一版这里套了按屏幕算的预算，圆屏上靠上下边缘的那几个数字因此
                // 因为"弦宽让可用区窄了几十像素"而被判成挪不动、丢掉 ——
                // 那就是用户看到的"纵坐标数字在移动的时候抽搐"（丢掉哪几个随滚动一直变）。
                val centerX = labelX - tw / 2f
                val cx = pinLabelIntoBand(centerX, tw / 2f, yLo, yHi)
                if (cx != null) {
                    val drawX = if (anchorIsRight) cx + tw / 2f else cx - tw / 2f
                    // 纵向不重叠：和上一个**画出去**的数字比它占到的最高一行。
                    // 纵轴方向的两个数字之间只有高度差，"两者半宽之和"就等于两个纵向半高之和，
                    // 所以直接比包围盒的上下沿即可（横向还有实测字宽，纵向只有估出来的半高）。
                    if (lastTop - bottom >= gapPx) {
                        val drawn = drawLabel(
                            canvas, text, drawX, cy + centerOffset,
                            align, paint, metrics, safeInset, isRound
                        )
                        if (drawn) lastTop = top
                        // 迟滞状态按"真的画出去了没有"更新（理由同 x 行那一处）
                        if (drawn) sticky.yColumn.add(yv) else sticky.yColumn.remove(yv)
                    } else {
                        sticky.yColumn.remove(yv)
                    }
                } else {
                    sticky.yColumn.remove(yv)
                }
            } else {
                sticky.yColumn.remove(yv)
            }
        }
        yv += step
    }
    sticky.prune(sticky.yColumn, vp.worldBottom, vp.worldTop, step)
}

/** 极坐标刻度：沿 θ = 0 的极轴标半径值（从 k = 1 开始，不标 0）。 */
private fun DrawScope.drawPolarLabels(
    vp: Viewport,
    metrics: WatchMetrics,
    step: Double,
    textPx: Float,
    safeInset: Float,
    paint: android.graphics.Paint,
    densityScale: Float,
    /** 这块屏是不是圆的（方屏/手机不套弦宽，见 `isRound` 那段）。 */
    isRound: Boolean,
    /** 迟滞状态（"上一帧哪些刻度值画过"）—— 见 [LabelSticky]。 */
    sticky: LabelSticky
) {
    val w = size.width
    val h = size.height
    val canvas = drawContext.canvas.nativeCanvas
    val center = Offset(vp.toScreenX(0.0), vp.toScreenY(0.0))

    val farX = maxOf(abs(center.x), abs(center.x - w))
    val farY = maxOf(abs(center.y), abs(center.y - h))
    val maxRadius = hypot(farX, farY)

    val gapPx = LABEL_MIN_GAP_DP * densityScale
    val halfHeight = textPx * TEXT_HALF_HEIGHT_FRACTION
    val centerOffset = textPx * TEXT_CENTER_OFFSET_FRACTION
    // 贴边挪动量的量化步长（同 drawCartesianLabels，见那里的说明）
    val quantumPx = labelFollowQuantumPx(metrics.sizePx)

    // 这一行数字都骑在极轴上方（同一个高度），所以和笛卡尔 x 那一行同理：
    // 纵向只夹一次（**保闸版** `pinLabelIntoBand`，理由见 drawCartesianLabels 里同一处：
    // 整行夹一次时，夹取与完整可见闸之间那 1 个 ULP 的差额会让**整排**数字一起消失），
    // 再按夹完的高度取圆屏可用半宽。圆心滑到屏幕上边时，
    // 原来这一整行会整排被裁掉（和 x 轴那排是同一个毛病），现在会停在安全区里。
    val wantedBaseline = center.y - textPx * 0.35f
    val rowCenterY = pinLabelIntoBand(
        wantedBaseline - centerOffset, halfHeight, safeInset, h - safeInset
    ) ?: return
    val baseline = rowCenterY + centerOffset
    val band = bandHalfWidth(
        w, h,
        baseline - textPx * 0.85f, baseline + textPx * 0.25f,
        safeInset, metrics, isRound
    )
    val xLo = maxOf(safeInset, w / 2f - band)
    val xHi = minOf(w - safeInset, w / 2f + band)

    // 沿极轴由内往外（值从小到大）逐个判：贴边后挤在右边缘上的只留最靠边的那个。
    // 预算与判定和笛卡尔 x 那一排是**同一个函数**（`pinLabelNearAxis`）：环标签的锚点
    // 可以落在屏外（环的右端点出了屏），判据是"环自己还在不在屏幕上"——
    // 还在屏幕上就一定挪得动（能贴边），已经出屏的不画（"别把数字拖到屏幕中间"）。
    var lastRight = Float.NEGATIVE_INFINITY
    var k = 1
    while (k <= MAX_POLAR_RINGS) {
        val radiusPx = (k * step / vp.unitsPerPixel).toFloat()
        if (!radiusPx.isFinite()) break
        if (radiusPx > maxRadius) break
        val text = formatNumber(k * step)
        val halfW = paint.measureText(text) / 2f
        val ringX = center.x + radiusPx
        // 迟滞按"环半径值"记（极坐标这一行和笛卡尔 x 行是两条独立的判定链）
        val ringValue = k * step
        val wasDrawn = sticky.polarRow.contains(ringValue)
        val anchor = pinLabelNearAxis(
            labelAnchorPx = ringX,
            axisAnchorPx = ringX,
            halfExtentPx = halfW,
            loPx = xLo,
            hiPx = xHi,
            visibleLoPx = 0f,
            visibleHiPx = w,
            quantumPx = quantumPx,
            densityScale = densityScale,
            wasDrawn = wasDrawn,
            hysteresisPx = quantumPx
        )
        if (anchor != null) {
            val left = anchor - halfW
            if (left - lastRight >= gapPx) {
                val drawn = drawLabel(
                    canvas, text, anchor, baseline,
                    android.graphics.Paint.Align.CENTER, paint, metrics, safeInset, isRound
                )
                if (drawn) lastRight = anchor + halfW
                if (drawn) sticky.polarRow.add(ringValue) else sticky.polarRow.remove(ringValue)
            } else {
                sticky.polarRow.remove(ringValue)
            }
        } else {
            sticky.polarRow.remove(ringValue)
        }
        k++
    }
    // 极坐标没有"世界坐标区间"可用，直接按当前看到的半径上界清一次（见 LabelSticky.prune）
    sticky.prune(sticky.polarRow, 0.0, maxRadius * vp.unitsPerPixel, step)
}

/**
 * 画一个刻度数字，但先做安全裁剪；返回**是否真的画了**。
 *
 * 圆屏：表盘是圆的，越靠上/下，可用宽度越窄 —— 文字只要有一角探出安全区就直接不画
 * （被表壳切掉半个数字比没有数字更难看）。安全区用 [WatchMetrics.circleWidthFraction]
 * 在文字的上下边缘各算一次，取更窄的那个。
 * **方屏/手机**（[isRound] = false）：没有表壳，只留矩形安全内缩（见 [fitsRoundScreen]）。
 *
 * 调用方（[drawCartesianLabels] / [drawPolarLabels]）已经先用 `pinLabelIntoBand` /
 * `pinLabelNearAxis` 把锚点夹进"完整可见"的范围（**并向内留了亚像素富余**，
 * 见 [fitsRoundScreen]：不这样的话，夹出来的位置会因为 1 个 ULP 被判掉，
 * 而 x 轴那一行是整行夹一次 → 整排数字消失），所以这里**正常情况下一定放得下**。
 * 仍然保留这道闸，是为了两件事：
 *   1. "绝不把数字画到表壳外"是圆屏的硬要求（贴边也不行）——它是最后一道保险；
 *   2. 返回值告诉调用方这一次到底画没画，贴边之后的"不许互相压住"要靠它记账
 *      （没画出去的数字不该占位）。
 */
private fun DrawScope.drawLabel(
    canvas: android.graphics.Canvas,
    text: String,
    anchorX: Float,
    baselineY: Float,
    align: android.graphics.Paint.Align,
    paint: android.graphics.Paint,
    metrics: WatchMetrics,
    safeInset: Float,
    isRound: Boolean
): Boolean {
    val tw = paint.measureText(text)
    val left: Float
    val right: Float
    when (align) {
        android.graphics.Paint.Align.CENTER -> {
            left = anchorX - tw / 2f
            right = anchorX + tw / 2f
        }
        android.graphics.Paint.Align.RIGHT -> {
            left = anchorX - tw
            right = anchorX
        }
        else -> {
            left = anchorX
            right = anchorX + tw
        }
    }
    val top = baselineY - paint.textSize * 0.85f
    val bottom = baselineY + paint.textSize * 0.25f
    if (!fitsRoundScreen(size.width, size.height, left, right, top, bottom, safeInset, metrics, isRound)) {
        return false
    }

    paint.textAlign = align
    canvas.drawText(text, anchorX, baselineY, paint)
    return true
}

/**
 * 文字的包围盒是否完整落在"屏幕内缩 safeInset"的可用区里。
 *
 * [isRound] = false（方屏、手机）时**只做矩形检查** —— 圆屏那套弦宽公式套到
 * 一块 1080×2400 的屏上会把底部那一行的可用宽度算成屏中央的一条窄柱，
 * 数字于是永远贴不到屏幕侧边（这是用户报"贴边没实装"的真因之一，详见
 * composable 里 `isRound` 那段与 `drawCartesianLabels` 的说明）。
 *
 * ## 为什么这里允许**亚像素**溢出（[LABEL_FIT_SLACK_PX]，0.5px）
 *
 * 这道闸是"绝不把数字画到表壳外"的最后一道保险，而"夹"在 `pinLabelIntoBand` 里。
 * 两处原本是**两套算式**：夹的位置是 `边界 − 半身位` 算出来的，而这里的包围盒是
 * `(夹出来的中心 + 中心偏移) ± 0.85/0.25 字号` 三次浮点加出来的 —— 末位能差 1 个 ULP
 * （圆表 466×466 上实测 `下沿 = h − safeInset + 3.1e-5`）。
 * 严格比较于是会把**已经夹对了**的数字判掉；而 x 轴那一行是**整行夹一次**的，
 * 一次误判就是整排数字一起消失（用户报的"横坐标的数字直接显示不出来"）。
 *
 * 现在两侧都留富余（夹取往内收 0.5px + 这里放过 0.5px），"夹出来的位置"在数学上
 * 就不可能被这道闸拒掉。0.5px 盖得住 1e-4 量级的舍入，又小于任何肉眼能分辨的位移
 * （圆表上是 0.026 个字号、屏宽的 0.1%），所以"绝不画出表壳"这条硬要求一秒都没松。
 */
private fun fitsRoundScreen(
    w: Float,
    h: Float,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    safeInset: Float,
    metrics: WatchMetrics,
    isRound: Boolean
): Boolean {
    if (w <= 0f || h <= 0f) return false
    val fit = LABEL_FIT_SLACK_PX
    if (left < safeInset - fit || right > w - safeInset + fit) return false
    if (top < safeInset - fit || bottom > h - safeInset + fit) return false
    // 方屏/手机到这儿就够了：四角是真的看得见，没有"表壳切掉"这回事
    if (!isRound) return true

    val half = minOf(
        circleHalfWidthAt(top, w, h, metrics),
        circleHalfWidthAt(bottom, w, h, metrics)
    ) - safeInset
    if (half <= 0f) return false

    val mid = w / 2f
    return (mid - left) <= half + fit && (right - mid) <= half + fit
}

/** 圆屏在高度 y 处的可用半宽（像素）。 */
private fun circleHalfWidthAt(y: Float, w: Float, h: Float, metrics: WatchMetrics): Float {
    if (h <= 0f) return w / 2f
    val yFraction = (y / h).coerceIn(0f, 1f)
    return w / 2f * metrics.circleWidthFraction(yFraction)
}

/**
 * "文字所占的那条高度带"里能用的**半宽**（像素，相对屏幕水平中心）。
 *
 * **圆屏**：越靠上/下弦宽越窄，所以一条刻度数字能不能放下、**往圆心方向最多能挪到哪**，
 * 都取决于它落在哪个高度。这里对文字的上下边缘各算一次内切圆半宽、取更窄的那个 ——
 * 和 [fitsRoundScreen] 里那句是**同一套算法**（用的也是同一个 [circleHalfWidthAt]），
 * 只是提前算出来：贴边必须先知道"挪到哪才算完整可见"，不能等画的时候才发现放不下。
 *
 * **方屏/手机**（[isRound] = false）：直接给 `w / 2f`，也就是"整个屏宽" ——
 * 调用方接着会用 `min(w - safeInset, …)` 收一次，结果就是"屏宽减左右安全内缩"。
 * 这一步是本轮修的那个 bug：圆屏公式对 1080×2400 的手机会算出 ±110px 的窄柱
 * （屏高是屏宽的 2.2 倍，贴底那一行按圆算几乎没宽度），数字于是永远贴不到屏幕侧边。
 *
 * 返回值 ≤ 0 表示这段高度上根本没有可用宽度，调用方应当整条不画。
 */
private fun bandHalfWidth(
    w: Float,
    h: Float,
    top: Float,
    bottom: Float,
    safeInset: Float,
    metrics: WatchMetrics,
    isRound: Boolean
): Float {
    if (!isRound) return w / 2f
    return minOf(
        circleHalfWidthAt(top, w, h, metrics),
        circleHalfWidthAt(bottom, w, h, metrics)
    ) - safeInset
}


// ---------------------------------------------------------------------------
// 点击命中测试：找离手指最近的那条曲线
// ---------------------------------------------------------------------------

/**
 * 在屏幕像素坐标里找离 (px, py) 最近的曲线。
 *
 * 用「点到线段的距离」而不是「点到顶点的距离」：曲线是折线，
 * 稀疏处两个顶点可能隔很远，只比顶点会让手指明明压在线上却选不中。
 *
 * @param maxDistPx 超过这个距离就当作没点中
 * @return 命中函数的 id，没命中返回 null
 */
fun hitTestCurves(plot: PlotResult, px: Float, py: Float, maxDistPx: Float): Long? {
    var bestId: Long? = null
    var bestDist = maxDistPx
    for (c in plot.curves) {
        val pts = c.points
        var i = 0
        while (i + 3 < pts.size) {
            val d = pointSegmentDistance(px, py, pts[i], pts[i + 1], pts[i + 2], pts[i + 3])
            if (d < bestDist) {
                bestDist = d
                bestId = c.functionId
            }
            i += 2
        }
    }
    return bestId
}

private fun pointSegmentDistance(
    px: Float, py: Float,
    ax: Float, ay: Float,
    bx: Float, by: Float
): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-6f) return kotlin.math.hypot(px - ax, py - ay)
    var t = ((px - ax) * dx + (py - ay) * dy) / len2
    if (t < 0f) t = 0f else if (t > 1f) t = 1f
    return kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}
