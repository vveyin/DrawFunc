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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import com.functiondrawer.watch.engine.Calc
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
internal const val REVEAL_MS = 520

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
     * 教程第 8 步的"先藏起来"（1.2.53）：为 true 时 [revealIds] 里的函数
     * **一直按 0 进度画**（等于没画），动画不启动；等上层松开（`replayReveal`）
     * 才真正播。正常逻辑不会传它 —— 这是新手教程那条"先减弱阴影、再播放
     * 绘制动画"的特例（见 `Tutorial.revealArmed`）。
     */
    revealHold: Boolean = false,
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
     * 正在被手指拖动的点对象 id（用户需求 13："拖动点的效果没有实装"）。
     *
     * 拖动本身在 [com.functiondrawer.watch.ui.GraphState.movePointDrag] 里已经写得好了，
     * 缺的是"看得出它在被拖"的反馈：这里给那个点画一圈强调色的光环 + 一道呼吸描边，
     * 手指底下就有一个明确的抓取感（否则拖动时只看到点跟着走，分不清是点被拖还是
     * 整个图像被平移了）。
     */
    draggingPointId: Long? = null,
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
    secondaryHighlightColor: Color = CurvePalette[0],
    /**
     * 揭示动画**播完**时回调一次。
     *
     * 上层（`GraphRoute`）用它做两件事：解锁抽屉拖动（用户反馈 3）、
     * 以及补一张背景（动画播完时画布上才是完整曲线，见那边的注释）。
     */
    onRevealFinished: () -> Unit = {}
) {
    // 只在采样结果变化时重建 Path。手势期间 viewport 每帧都在变，
    // 但 plot 不变，所以这里不会重复分配。
    val paths = remember(plot) { buildPaths(plot) }

    // 揭示进度：0 = 还没画，1 = 画完。默认 1（不动画时就是完整显示）。
    val reveal = remember { Animatable(1f) }

    /**
     * 这一批 id 的揭示动画**已经开始**的记号（用户反馈 2 / 5）。
     *
     * ⚠️ 不能只靠 `reveal.snapTo(0f)`：`LaunchedEffect` 在**这一帧画完之后**才跑，
     * 于是新函数会先按完整路径闪一帧，然后才从头画出来。
     * 所以只要 [revealIds] 和它不一致，绘制阶段就一律按 0 进度算；
     * 等下面的 effect 真的启动动画（先 snapTo(0)）之后才交回给 [reveal]。
     */
    val activeRevealIds = remember { mutableStateOf(emptySet<Long>()) }
    val currentOnRevealFinished by rememberUpdatedState(onRevealFinished)

    /**
     * 要揭示的那批函数**已经有采样结果**了没有。
     *
     * 没有就等待（绘制阶段一直按 0 进度画，不会先闪出来）：
     * 否则动画会在"一条都画不出来"的画布上空跑 520ms，
     * 等采样结果到了动画已经播完，用户看到的仍然是"整条直接出现"。
     */
    val revealPathsReady = paths.any { cp -> cp.functionId in revealIds }
    LaunchedEffect(revealIds, revealPathsReady, revealHold) {
        // 教程第 8 步：先藏起来（进度钉在 0、不启动动画），等暗幕降完才放行。
        // 判断必须排在 `revealIds.isEmpty()` 前面 —— 藏的时候动画一帧都不许跑。
        if (revealHold) {
            reveal.snapTo(0f)
            activeRevealIds.value = emptySet()
            return@LaunchedEffect
        }
        if (revealIds.isEmpty()) {
            activeRevealIds.value = emptySet()
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        if (!revealPathsReady) return@LaunchedEffect
        reveal.snapTo(0f)
        activeRevealIds.value = revealIds
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = REVEAL_MS, easing = FastOutSlowInEasing)
        )
        // 播完再回调：上层这时才解锁抽屉、补背景（画布此刻才是完整曲线）。
        currentOnRevealFinished()
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
    val isRound = isRoundScreen()

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
            // 动画还没启动的那一帧强制按 0 画（见 `activeRevealIds`）：
            // 不能拿 `reveal.value` 的初值 1 去画，否则就是"先完整闪一下"。
            val progress = when {
                revealIds.isEmpty() -> 1f
                revealIds != activeRevealIds.value -> 0f
                else -> reveal.value
            }

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
            measureAnim = measureAnim,
            draggingPointId = draggingPointId
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
    measureAnim: MeasureAnimations? = null,
    /** 正在被手指拖动的点 id（用户需求 13），画一圈"抓住了"的强调光环。 */
    draggingPointId: Long? = null
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
        if (o.id == draggingPointId) {
            // 拖动中：先垫一圈强调色光环 + 一道描边，手指底下就是"抓住了"的样子
            val grab = CurvePalette[0]
            drawCircle(grab.copy(alpha = 0.20f), radius = pointR * 2.6f, center = Offset(cx, cy))
            drawCircle(
                color = grab.copy(alpha = 0.80f),
                radius = pointR * 1.7f,
                center = Offset(cx, cy),
                style = Stroke(width = stroke)
            )
        }
        if (shadowIds.contains(o.id)) {
            drawCircle(color.copy(alpha = SHADOW_ALPHA), radius = haloR, center = Offset(cx, cy))
        }
        // 空心圆：区分"曲线算出来的 / 由点派生出来的点"与"用户自己点的点"
        // （用户要求：特征点和派生点都画空心，而且空心点不许拖，见
        // `GraphPointObject.isHollow`）。
        if (o.isHollow) {
            drawCircle(color, radius = pointR, center = Offset(cx, cy), style = Stroke(width = stroke))
        } else {
            drawCircle(color, radius = pointR, center = Offset(cx, cy))
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
 * 刻度数字与刻度线之间的**固定缝**，占字号的比例。
 *
 * 横轴数字在轴下方、纵轴数字在轴左侧，两条轴用**同一个**缝：以前横轴按
 * `1.15 字号` 的 baseline 偏移、纵轴按 `0.36 字号` 再减 `0.30 字号`，两套口径
 * 各偏各的，视觉上就是"数字离刻度线忽远忽近"（用户报的"数字偏移"之一）。
 * 现在统一成"文字盒边缘离刻度线 tickGap"，字号变、屏幕变都按比例走。
 */
private const val TICK_LABEL_GAP_FRACTION = 0.35f

/**
 * 纵轴数字那一列**宽度的粗估**（em），只用于"横轴这个数字会不会和纵轴撞上"的判定。
 *
 * 纵轴数字是右对齐的，占住的是"轴的左侧一条窄带"；判横向冲突时并不需要每一格的真实
 * 字宽（那个随位数变），只要一个不会低估的宽度就够了 —— 估宽了最多多交给对角线裁决
 * 一两个数字，估窄了才会真叠字。2.8em 盖得住 `-100` 这种四字符标签。
 */
private const val Y_COLUMN_WIDTH_EM = 2.8f

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
 * 笛卡尔刻度数字：横轴数字画在轴的**下方**，纵轴数字画在轴的**左侧**。
 *
 * ## 四条用户反馈分别落在这一段代码的哪里
 *
 * 1. **横轴数字不再"翻面"**（"轴划到屏幕外，数字从上面跳到下面"）。
 *    旧写法是 `if (轴在屏内) 轴下方 else 贴屏底` —— 那个 `else` 就是跳变源：
 *    轴刚滑出屏幕上缘，整排数字立刻瞬移到屏底（轴的另一侧）。
 *    现在这一行的**高度只由轴的位置决定**（轴下方一个缝 + 半字高），再夹进安全区。
 *    夹取是连续函数：轴滑出去时这一行就停在**同一侧**的安全线上，顺着轴的延伸方向
 *    自然出屏，**绝不会翻到对面**。
 *
 * 2. **两组数字以屏幕对角线分界**（防止在角落叠在一起）。
 *    取「左下 → 右上」那条对角线（`x/w + y/h = 1`）：横轴数字只在**右下侧**，
 *    纵轴数字只在**左上侧**；落在错误一侧的那个数字**直接不画** —— 注意是"不画"，
 *    不是"挪位置"（挪了正好就是用户说的"偏移"）。
 *    ⚠️ 分界只在**两条数字带的交汇区**生效：带外的数字离另一组很远，硬按半平面切
 *    会把默认视野（原点在屏幕正中）里各一半的数字白丢掉，和第 3 条"整屏显示"打架。
 *    所以判据是"横向/纵向和另一组撞上了"才交给对角线裁决 —— 那正是"防叠字"要管的事。
 *
 * 3. **纵轴数字整屏都有**。纵向可用区就是**整块画布**（旧代码从"角锚点"量一段臂长，
 *    只覆盖上面约 80% 的高度，所以用户看到"数字只在中间一段"）。边缘上的刻度由
 *    `pinLabelNearAxis` 夹住、不消失；横向仍然"右对齐在轴左侧"，轴滑出屏幕就贴到
 *    对应那一侧（左/右）的边上继续显示。横轴那一行同理不会因为轴出屏而整排空白。
 *
 * 4. **偏移**。横轴数字 `Align.CENTER` 正对刻度线；纵轴数字右对齐、与轴之间是一个
 *    固定缝；纵向 baseline 用字体度量的 ascent/descent 把**视觉中心**对准刻度线，
 *    不再用 `tickY + 0.36 字号` 这种硬编码（换字体就系统性偏上/偏下）。
 *
 * ## 保留不变的部分
 * 步长与网格同源（`step` 由 [drawLabels] 按 `chartGridStep` 算好）；逐格用实测字宽判
 * "不许互相压住"；贴边判定 `pinLabelNearAxis`（夹 + 预算 + 量化 + 双阈值迟滞）原样复用；
 * 圆屏的"超出圆形不画"仍由 [drawLabel] → [fitsRoundScreen] 兜底。
 */
private fun DrawScope.drawCartesianLabels(
    vp: Viewport,
    metrics: WatchMetrics,
    step: Double,
    textPx: Float,
    safeInset: Float,
    paint: android.graphics.Paint,
    densityScale: Float,
    /** 这块屏是不是圆的（圆屏才按弦宽收窄可用区间）。 */
    isRound: Boolean,
    /** 迟滞状态（"上一帧哪些刻度值画过"）—— 见 [LabelSticky]。 */
    sticky: LabelSticky
) {
    val w = size.width
    val h = size.height
    val canvas = drawContext.canvas.nativeCanvas

    // 半字高与"视觉中心 → baseline"的偏移都取自字体度量（理由见函数头第 4 条）。
    // 这两个量一致地用在"夹取、避让、完整可见闸"三处，数字才会正对刻度线。
    val fm = paint.fontMetrics
    val halfHeight = (fm.descent - fm.ascent) * 0.5f
    val baselineShift = -(fm.ascent + fm.descent) * 0.5f
    if (!halfHeight.isFinite() || halfHeight <= 0f) return

    val gapPx = LABEL_MIN_GAP_DP * densityScale
    // 数字与刻度线之间的固定缝：横轴数字在轴下、纵轴数字在轴左，用同一个口径
    val tickGap = textPx * TICK_LABEL_GAP_FRACTION

    val axisX = vp.toScreenX(0.0)
    val axisY = vp.toScreenY(0.0)

    // ------------------------------------------------------------------ 横轴数字
    // 行中心 = 轴下方一个缝 + 半个字高。**只看轴的位置**，不看它是否还在屏内：
    // 夹取连续，轴滑出上/下缘时这一行停在同侧的安全线上 —— 旧代码那个 `else 贴屏底`
    // 就是"从上面跳到下面"的来源，这里彻底没有了。
    val xCentre = pinLabelIntoBand(
        axisY + tickGap + halfHeight, halfHeight, safeInset, h - safeInset
    )

    if (xCentre != null) {
        val xTop = xCentre - halfHeight
        val xBottom = xCentre + halfHeight
        // 这一行横向的可用区间：安全内缩 ∩ 该高度上的圆屏弦宽
        val xBand = bandHalfWidth(w, h, xTop, xBottom, safeInset, metrics, isRound)
        val xLo = maxOf(safeInset, w / 2f - xBand)
        val xHi = minOf(w - safeInset, w / 2f + xBand)

        if (xHi > xLo) {
            val xBaseline = xCentre + baselineShift
            // lastRight 是本帧局部变量（依赖当前视口，缓存了滑动时就会拿上一帧的占位）
            var lastRight = Float.NEGATIVE_INFINITY
            // 值从小到大 = 屏幕上从左到右；贴边会把边缘那个数字顶到和邻居同一条竖线上，
            // "谁先占位"直接决定谁留下。
            var tickIndex = ceil(vp.worldLeft / step).toLong()
            var guard = 0
            while (guard++ < MAX_LABEL_TICKS) {
                val v = tickIndex * step
                if (v > vp.worldRight) break
                // 0 不标：原点是两条轴的交点，标上去只会和轴线打架
                if (abs(v) > step * 1e-6) {
                    val text = Calc.formatTick(tickIndex, step)
                    // 实测字宽：`1` 和 `-1000` 差三倍，按位数估会估出"都放不下"的假象
                    val halfW = paint.measureText(text) / 2f
                    val tickX = vp.toScreenX(v)
                    // ---- 1.2.49：数字**就钉在自己那条刻度线正上方** ----
                    //
                    // 用户原话："我要你做横轴数字老老实实待在所占的那一条纵轴上面……
                    // 现在是数字会跟着屏幕偏移一小段，14 可能会偏移到 13 甚至 12 的位置去。"
                    //
                    // 以前这里走 `pinLabelNearAxis`：贴边夹取 + 量化 + 迟滞。放不下的
                    // 那个数字会被"顶"进可用区，横向就挪了一两格 —— 于是 14 画在了
                    // 13（甚至 12）的位置上，看起来像数字在跟着屏幕滑。
                    //
                    // 现在只有两条规矩：
                    //   * 整个字盒必须完整落在这一行的可用区间里 —— 放不下就**不画**，
                    //     绝不挪半个像素（宁可少一个数字，也不许标错位置）；
                    //   * 和左边刚画出去的那个不压住。
                    // 另外去掉了"和纵轴数字撞上 → 交给对角线裁决"那一段：正是它把
                    // 原点左边第一个数字（用户说的"永远不显示"）判掉的。
                    val anchor = if (tickX - halfW >= xLo && tickX + halfW <= xHi) tickX else null
                    if (anchor != null) {
                        val left = anchor - halfW
                        val right = anchor + halfW
                        // 和上一个画出去的数字压住了就跳过这一个：不画，也就不占位。
                        if (left - lastRight >= gapPx) {
                            val drawn = drawLabel(
                                canvas, text, anchor, xBaseline,
                                android.graphics.Paint.Align.CENTER,
                                paint, metrics, safeInset, isRound, fm
                            )
                            if (drawn) lastRight = right
                            // 迟滞按"真的画出去了没有"记：没画出去就等于这一帧没有它
                            if (drawn) sticky.xRow.add(v) else sticky.xRow.remove(v)
                        } else {
                            sticky.xRow.remove(v)
                        }
                    } else {
                        // 判定说"这一帧不画"：从迟滞状态里摘掉，否则它会一直靠宽松阈值赖着
                        sticky.xRow.remove(v)
                    }
                }
                tickIndex++
            }
            // 清掉已经不在可见范围内的刻度值（换档/平移会不断产生新值）
            sticky.prune(sticky.xRow, vp.worldLeft, vp.worldRight, step)
        }
        // 夹不出来（画布小到连一行字都放不下）时横轴这一排不画，
        // 但纵轴那一列照常走 —— 两条轴互不牵连。
    }

    // ------------------------------------------------------------------ 纵轴数字
    // 纵向可用区 = **整块画布**（第 3 条"整屏显示"）：不再从角锚点量一段臂长。
    // 屏内的刻度原样、边缘的刻度夹住贴边、已经出屏的刻度不拖进来 —— 全交给同一个判定。
    var lastTop = Float.POSITIVE_INFINITY
    var yTickIndex = ceil(vp.worldBottom / step).toLong()
    var guard = 0
    while (guard++ < MAX_LABEL_TICKS) {
        val yv = yTickIndex * step
        if (yv > vp.worldTop) break
        if (abs(yv) > step * 1e-6) {
            val text = Calc.formatTick(yTickIndex, step)
            val tw = paint.measureText(text)
            val tickY = vp.toScreenY(yv)
            // 文字中心直接对准刻度线（旧代码是 `tickY + 0.36 字号` 再减去 0.30 字号，
            // 两处约剩下 0.06 字号的系统性错位 —— 这就是"坐标轴数字偏移"）
            //
            // ---- 1.2.49：和横轴同一条规矩 ----
            // 放不下就不画，绝不纵向挪位 —— 否则数字会"跟着屏幕"跑到上下邻格去。
            val cy = if (tickY - halfHeight >= safeInset && tickY + halfHeight <= h - safeInset) {
                tickY
            } else {
                null
            }
            if (cy != null) {
                val top = cy - halfHeight
                val bottom = cy + halfHeight
                // 按**夹完**的高度算圆屏弦宽（弦宽随高度变，顺序反了就会落在更窄的一行上）
                val band = bandHalfWidth(w, h, top, bottom, safeInset, metrics, isRound)
                val lo = maxOf(safeInset, w / 2f - band)
                val hi = minOf(w - safeInset, w / 2f + band)
                // 右对齐在轴的左侧：锚点就是文字的**右边缘**，与轴之间留固定缝（第 4 条）
                val centre = pinLabelIntoBand(axisX - tickGap - tw / 2f, tw / 2f, lo, hi)
                if (centre != null) {
                    val rightX = centre + tw / 2f
                    // 纵向不许互相压住：和上一个画出去的数字比它占到的最高一行
                    if (lastTop - bottom >= gapPx) {
                        val drawn = drawLabel(
                            canvas, text, rightX, cy + baselineShift,
                            android.graphics.Paint.Align.RIGHT,
                            paint, metrics, safeInset, isRound, fm
                        )
                        if (drawn) lastTop = top
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
        yTickIndex++
    }
    sticky.prune(sticky.yColumn, vp.worldBottom, vp.worldTop, step)
}

/**
 * 点到「左下 → 右上」对角线的**有符号距离**（像素）：> 0 = 右下侧、< 0 = 左上侧。
 *
 * 分界线方程是 `x/w + y/h = 1`。乘上 `w·h` 再除以对角线长度就得到真实距离 ——
 * 判"整个字盒有没有跨过线"时，拿一个像素阈值当缝（[gapPx]）比拿坐标比值直观，
 * 而且和字号无关。
 */
private fun diagonalDistance(
    xPx: Float,
    yPx: Float,
    wPx: Float,
    hPx: Float,
    invDiag: Float
): Float = (xPx * hPx + yPx * wPx - wPx * hPx) * invDiag

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
    // 文字盒与"视觉中心 → baseline"的偏移都取自字体度量（和笛卡尔那两排同一套口径：
    // 旧的 0.55/0.30 字号估计换字体就会偏，见 drawCartesianLabels 第 4 条）
    val fm = paint.fontMetrics
    val halfHeight = (fm.descent - fm.ascent) * 0.5f
    val baselineShift = -(fm.ascent + fm.descent) * 0.5f
    if (!halfHeight.isFinite() || halfHeight <= 0f) return
    // 贴边挪动量的量化步长（同 drawCartesianLabels，见那里的说明）
    val quantumPx = labelFollowQuantumPx(metrics.sizePx)

    // 这一行数字都骑在极轴上方（同一个高度），所以和笛卡尔 x 那一行同理：
    // 纵向只夹一次（**保闸版** `pinLabelIntoBand`，理由见 drawCartesianLabels 里同一处：
    // 整行夹一次时，夹取与完整可见闸之间那 1 个 ULP 的差额会让**整排**数字一起消失），
    // 再按夹完的高度取圆屏可用半宽。圆心滑到屏幕上边时，
    // 原来这一整行会整排被裁掉（和 x 轴那排是同一个毛病），现在会停在安全区里。
    val wantedBaseline = center.y - textPx * 0.35f
    val rowCenterY = pinLabelIntoBand(
        wantedBaseline - baselineShift, halfHeight, safeInset, h - safeInset
    ) ?: return
    val baseline = rowCenterY + baselineShift
    val band = bandHalfWidth(
        w, h,
        baseline + fm.ascent, baseline + fm.descent,
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
        val text = Calc.formatTick(k.toLong(), step)
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
                    android.graphics.Paint.Align.CENTER, paint, metrics, safeInset, isRound, fm
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
 *
 * 文字盒的上下沿用**字体度量**的 `ascent` / `descent`（[fm]）算，不再用 `0.85 / 0.25 字号`
 * 的硬编码估计：不同字体、不同字号的上下伸出量并不一样，估出来的盒子和实际画出来的
 * 字错开，就会出现"数字明明在安全区里却被判掉"或"贴边贴得忽高忽低"。
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
    isRound: Boolean,
    /** 和调用方同一份字体度量（`paint.fontMetrics`）—— 盒子必须和放置算法同源。 */
    fm: android.graphics.Paint.FontMetrics
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
    val top = baselineY + fm.ascent
    val bottom = baselineY + fm.descent
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
