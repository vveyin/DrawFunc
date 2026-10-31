package com.functiondrawer.watch.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.functiondrawer.watch.engine.Calc
import com.functiondrawer.watch.engine.CalcPrecision
import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.Plotter
import com.functiondrawer.watch.engine.Quality
import com.functiondrawer.watch.engine.Viewport
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.FunctionDrawerTheme
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 启动预编译。
 *
 * ## 为什么要有这一屏
 *
 * 用户原话："给刚打开软件的预编译界面的下面放一个进度条，进度条颜色就用蓝色，
 * 进度条右边显示百分比，在进度条下面显示正在加载的内容"，
 * 以及"手表过了预编译之后刚进软件还是很卡"。
 *
 * 所以这里做的**不是**装样子：每一格进度都对应一件"第一次用到才付钱"的初始化 ——
 * 字体解析、表达式编译、采样管线、数学排版、BigDecimal 格式化、工具代数。
 * 在后台线程先把它们跑一遍，进主界面时这些类已经装载好、JIT 已经热过。
 *
 * 动画预热（1.2.70 起）也在这里：热**计算和资源**之外，[PrecompileScreen] 里还挂着
 * [AnimationWarmup]，把 App 里用到的动画类型在遮罩后面先跑一遍。见那个函数的说明
 * （旧版曾把"列表滚动 + 按钮淡入"各播三遍并回退，那次覆盖不到真正掉帧的几类动画）。
 *
 * ## 进度条为什么不会 0 → 100 直接跳
 *
 * 进度值本身是按步推进的，真正平滑靠界面层的 [animateFloatAsState]
 * （见 [PrecompileScreen] 的注释）；再加上 [MIN_SHOW_MS] 给一个最短展示时间，
 * 冷启动再快也至少能看到一次连续的上涨。
 */
internal object Precompile {

    /** 整个进程只做一次。Activity 重建（比如从欢迎页回来）不该再看一遍进度条。 */
    @Volatile
    var finished: Boolean = false
        private set

    /** 预编译完成时停在哪个百分比，让"跳变"这一步也有动画可走。 */
    private const val DONE_LABEL = "准备就绪"

    /** 进度条最短展示时间（毫秒）。低于这个时间的机器看起来像"闪一下"。 */
    const val MIN_SHOW_MS = 1000L

    /**
     * 一步初始化。
     *
     * @param label 显示在进度条下面的那句话
     * @param weight 这一步占总进度的比例（全部加起来 = 1）
     */
    private class Step(val label: String, val weight: Float, val work: (Context) -> Unit)

    /**
     * 步骤表。
     *
     * 顺序是按"第一次用到的时间点"排的：配色最早（首帧就要）、字体和解窗口第二
     * （建第一条函数就要），采样的权重最大（它是这个 App 的主体）。
     * 每一步都用 `runCatching` 兜住 —— 预编译失败不该让 App 起不来。
     *
     * ## 2026-10-27：为什么又加回来一大截（用户报"进软件卡了很多"）
     *
     * 用户原话："现在刚进软件还是卡，你去看看之前预编译预编译了什么，做出调整。
     * 现在预编译时间比之前短了很多，进软件也卡了很多。"
     *
     * 上一轮为了"预编译更快"把几处预热删薄了，但被删掉的恰好是**首屏立刻要用**
     * 的三条路径 —— 结果时间省在预编译、代价全落到进主界面的前几帧：
     *   1. 曲线采样原来只热 `Quality.FAST`，而 [GraphState.quality] 静止时是
     *      **`Quality.NORMAL`**（手势中才降到 FAST）→ 首屏那条循环从没被 JIT 编译过；
     *   2. **特征点 / 吸附**（[FeatureFinder]、[intersections]、[nearestOnCurve]）
     *      没预热过 —— 进界面点一下画布、或者教程第 1 步描点就会卡一下；
     *   3. **LaTeX 翻译层**（[com.functiondrawer.watch.engine.Latex]）没预热过 ——
     *      编辑器里第一次写 `\frac` / `\sqrt[n]{}` 要多花一帧。
     * 现在三条都补回来，并按"首屏优先"重新分配权重。
     */
    private val STEPS: List<Step> = listOf(
        Step("准备配色与主题", 0.05f) {
            // 触发两个调色板 object 的静态初始化（日夜两套都摸一遍）
            KeyPalette.text
            KeyPalette.accent
            CurvePalette.size
        },
        Step("载入数学字体", 0.16f) { context ->
            // `Typeface.createFromAsset` 是解析 ttf，冷启动最贵的一件小事
            MathFontCache.get(context)
        },
        Step("预热表达式引擎", 0.18f) {
            // 走一遍词法 → 建树 → 求值：覆盖函数调用、可绑定参数、希腊字母、根号
            val e1 = com.functiondrawer.watch.engine.Expression.compile("sin(x)+x^2/3")
            e1.eval(0.5)
            val e2 = com.functiondrawer.watch.engine.Expression.compile(
                "a*x+b+sqrt(x)+root(x,3)+abs(x)+ceil(x)+floor(x)"
            )
            e2.eval(2.0)
            val e3 = com.functiondrawer.watch.engine.Expression.compile(
                "2cos(3θ)",
                 variables = listOf("θ", "t")
            )
            e3.eval(0.3)
            // LaTeX 翻译层（本轮新增）：`\frac` / `\sqrt[n]{}` / `^{}` / `\pi`
            // 都要在第一次按键之前先跑一遍，把翻译 + 词法那条路径热起来。
            val e4 = com.functiondrawer.watch.engine.Expression.compile(
                "\\frac{1}{2}+\\sqrt[3]{x}+x^{2}+\\pi",
                variables = listOf("x")
            )
            e4.eval(1.0)
        },
        Step("预热曲线采样", 0.22f) {
            // 四种模式各来一条：显函数逐列采样、极坐标、参数方程、隐函数 marching squares
            val viewport = Viewport(
                widthPx = 360f,
                heightPx = 360f,
                unitsPerPixel = 1.0 / 40.0
            )
            val functions = listOf(
                PlotFunction(id = 1L, mode = PlotMode.EXPLICIT, src1 = "sin(x)+x^2/3"),
                PlotFunction(id = 2L, mode = PlotMode.POLAR, src1 = "2cos(3θ)"),
                PlotFunction(
                    id = 3L, mode = PlotMode.PARAMETRIC, src1 = "cos(t)", src2 = "sin(t)"
                ),
                PlotFunction(id = 4L, mode = PlotMode.IMPLICIT, src1 = "x^2+y^2=1")
            )
            // ⚠️ 两档精度都要跑：首屏静止时是 NORMAL，手势中才降到 FAST
            // （见 GraphState.quality）。只热 FAST 的话，进界面的第一张图
            // 仍然要走一遍"从没执行过"的采样循环。
            Plotter.plot(functions, viewport, Quality.NORMAL)
            Plotter.plot(functions, viewport, Quality.FAST)
        },
        Step("预热特征点与吸附", 0.10f) {
            // 进界面后点一下画布 / 教程描点，走的就是这几条：
            //   FeatureFinder（零点、极值）→ nearestOnCurve（吸附到曲线）
            //   → intersections（交点）。全都没预热过的话第一次点会卡一帧。
            val vp = Viewport(widthPx = 360f, heightPx = 360f, unitsPerPixel = 1.0 / 40.0)
            val expr = com.functiondrawer.watch.engine.Expression.compile("sin(x)+x^2/3")
            com.functiondrawer.watch.engine.FeatureFinder.analyze(expr, -6.0, 6.0, limit = 4)
            val plotted = Plotter.plot(
                listOf(
                    PlotFunction(id = 1L, mode = PlotMode.EXPLICIT, src1 = "sin(x)+x^2/3"),
                    PlotFunction(id = 2L, mode = PlotMode.EXPLICIT, src1 = "cos(x)")
                ),
                vp,
                Quality.FAST
            )
            val a = plotted.curves.firstOrNull()
            val b = plotted.curves.getOrNull(1)
            if (a != null) {
                com.functiondrawer.watch.engine.nearestOnCurve(a, 180f, 180f, vp)
                if (b != null) com.functiondrawer.watch.engine.intersections(a, b, vp)
                com.functiondrawer.watch.engine.intersections(
                    a,
                    GLine(GPoint(-3.0, -3.0), GPoint(3.0, 3.0)),
                    vp
                )
            }
        },
        Step("预热数学排版", 0.11f) { context ->
            // 让 MathRender 的解析 / 布局 / Canvas 字形测量都跑一遍
            val fonts = MathFontCache.get(context)
            for (src in listOf(
                "(sqrt(x^2+1)+root(8,3))/2",
                "sin(x)+abs(y)",
                "ceil(x)+floor(y)",
                "√(x+1)+∛(x)",
                "x^2+y^2=1",
                // 1.2.22 新增的排版分支：`log` 的底数下标、空槽位阴影、
                // 根号指数虚线框 —— 不预热的话第一次按下这些键要多花一帧
                "log(2,x)+log(x)",
                "sin()+√()+root(x,)",
                // 末位 `^`：指数那一格是空的（阴影 + 停靠点），
                // 也是用户"按了乘方但没输入"的最常见形态
                "x^"
            )) {
                layoutMath(src, baseSizePx = 22f, maxWidthPx = 220f, fonts = fonts)
            }
            // 键帽图标那一版（虚线框）：单独跑一次，DASHED 分支也进 JIT
            layoutMath("root(x,)", 14f, 80f, fonts, SlotStyle.DASHED)
        },
        Step("预热精度计算", 0.08f) {
            // BigDecimal 三条路径（format / formatTick / snap）三档精度都走一遍
            val saved = Calc.precision
            for (p in CalcPrecision.entries) {
                Calc.precision = p
                Calc.format(Math.PI)
                Calc.format(5.0E-5)
                Calc.formatTick(3L, 0.2)
                Calc.snap(1.0E-9)
            }
            Calc.precision = saved
        },
        Step("预热工具代数", 0.06f) {
            // 圆 / 圆锥曲线那两栏的代数（高斯消元、五点定圆锥曲线是这里最重的）
            val o = GPoint(0.0, 0.0)
            val p1 = GPoint(-1.0, 0.0)
            val p2 = GPoint(1.0, 0.0)
            val p3 = GPoint(0.0, 1.6)
            circleEquation(o, 2.0)
            circleThroughThreePoints(p1, p2, p3)
            circleRadiusExpressionEquation(o, "2.5")
            ellipseEquation(p1, p2, p3)
            hyperbolaEquation(p1, p2, p3)
            parabolaEquation(GPoint(0.0, 1.0), GLine(GPoint(-2.0, -1.0), GPoint(2.0, -1.0)))
            arcOf(o, p1, p3)
            conicThroughPoints(
                listOf(
                    GPoint(-1.5, 0.0), GPoint(1.5, 0.0), GPoint(0.0, 1.0),
                    GPoint(0.0, -1.2), GPoint(1.0, 0.8)
                )
            )
        },
        Step("读取设置", 0.04f) { context ->
            // 存档解析（老文本格式逐行解析）也在后台先过一次
            Store.load(context)
            Store.loadCrownPrefs(context)
        }
    )

    /**
     * 跑完整个预编译。
     *
     * ## 进度为什么"显示不对"（2026-10-27 修）
     *
     * 用户本轮原话："预编译进度条显示不对。"
     *
     * 旧实现只在**每步开始前**报一次"已完成多少"，于是：
     * 某一步里真正在干活的那几百毫秒，条子**一动不动**，等它结束时
     * 才突然往前跳一格 —— 看起来就是"不是按顺序涨的 / 数字和条子对不上"。
     * 现在每一步都开一条 40ms 的心跳：在干活的同时把条子从这一步的起点
     * 平滑推到 90%，这一步真正结束时再收到 100%。进度因此**只会往前走**，
     * 而且每一拍都在涨。
     *
     * @param onProgress 进度回调：`(已完成的比例 0..1, 正在做的事)`
     */
    suspend fun run(context: Context, onProgress: (Float, String) -> Unit) {
        if (finished) return
        val app = context.applicationContext
        var done = 0f
        for (step in STEPS) {
            onProgress(done, step.label)
            val start = done
            val next = (done + step.weight).coerceIn(0f, 1f)
            // 干活和心跳并行：心跳只负责让条子在长步骤里也持续前进。
            coroutineScope {
                val job = launch(Dispatchers.Default) {
                    runCatching { step.work(app) }
                }
                var creep = 0f
                while (job.isActive) {
                    delay(HEARTBEAT_MS)
                    if (!job.isActive) break
                    // 每拍涨 4%，上限 90%：留出最后 10% 给"这一步真的做完了"。
                    creep = (creep + 0.04f).coerceAtMost(0.9f)
                    onProgress(start + (next - start) * creep, step.label)
                }
                job.join()
            }
            done = next
        }
        onProgress(1f, DONE_LABEL)
        finished = true
    }

    /** 心跳间隔（毫秒）。40ms ≈ 2.5 帧，条子看起来是连续爬的，又不至于太吵。 */
    private const val HEARTBEAT_MS = 40L
}

/**
 * 数学字体缓存。
 *
 * 原先两个 ttf 是在 `ExpressionField` 里 `remember` 加载的 —— 那意味着
 * **第一次打开函数编辑界面 / 半径输入界面**时才付解析成本（实测一次 ~2ms，
 * 但落在首帧上就会掉帧）。预编译先加载好放在这里，两边共用同一份实例。
 */
internal object MathFontCache {

    @Volatile
    private var cached: MathTypefaces? = null

    fun get(context: Context): MathTypefaces =
        cached ?: synchronized(this) {
            cached ?: MathTypefaces(
                main = runCatching {
                    Typeface.createFromAsset(context.assets, "fonts/KaTeX_Main-Regular.ttf")
                }.getOrNull(),
                italic = runCatching {
                    Typeface.createFromAsset(context.assets, "fonts/KaTeX_Math-Italic.ttf")
                }.getOrNull()
            ).also { cached = it }
        }
}

/**
 * 预编译界面：图标 + 名字 + **蓝色进度条 + 右边的百分比** + 下面那一行"正在加载…"。
 *
 * 进度条的动画在这里做，不在 [Precompile] 里：数据层只报"现在到哪一步"，
 * 界面层用 [animateFloatAsState] 追上去。这样即使某一步瞬间完成，
 * 手指看到的也是**连续上涨**，而不是一格一格地跳。
 */
@Composable
internal fun PrecompileScreen(
    progress: Float,
    label: String,
    dayMode: Boolean = false,
    /**
     * 是否毛玻璃主题。**必须和下面那份真实界面一致**（[AppRoot] 从存档里读出来传进来）。
     *
     * 原因：`FunctionDrawerTheme` 是把 `activeGlassTheme` 这个**全局状态**写进去的
     * （`KeyPalette` 的几百处 getter 都读它）。1.2.57 起遮罩和真实界面是**同时组合**的，
     * 如果这里固定写 `false`，它的 `SideEffect` 会排在真实界面之后执行，
     * 把全局的玻璃开关盖回 material —— 用户看到的就是"毛玻璃主题的按钮多了
     * material 的底色，去设置里重新切一下又好了"。
     */
    glass: Boolean = false,
    modifier: Modifier = Modifier
) {
    FunctionDrawerTheme(dayMode = dayMode, glass = glass) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            val density = LocalDensity.current
            val sizePx = minOf(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val sizeDp = with(density) { sizePx.toDp() }
            val metrics = WatchMetrics(sizeDp = sizeDp, sizePx = sizePx, density = density)
            val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
            val isRound = isRoundScreen()

            // 追进度：线性缓动 + 稍短的时长，既不会一格一格跳，也不会明显滞后
            val shown by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 280, easing = LinearEasing)
            )

            val contentWidth = geometry.rowWidth(metrics, 0.78f, isRound)
                .coerceAtMost(metrics.sizeDp * 0.78f)
            // ---- 2026-10-27：整体放大 ----
            //
            // 用户原话："预编译阶段的图标放应用图标，进度条做大点，
            // 下面显示正在进行的项目，字都做大点。"
            //
            //   * 图标：[AppMark] 画的就是应用图标本身（圆角方块 + 坐标轴 + 正弦曲线），
            //     0.22 → 0.30；
            //   * 进度条：0.028 → 0.055（翻倍），百分比字号 fontTiny → fontSmall；
            //   * 名字：fontBody → fontTitle；
            //   * 下面"正在进行的项目"：fontTiny → fontSmall，并允许折两行。
            //
            // 2026-10-27 追加：用户说"图标改成圆形，进度条改细一点，粗度变成原来的一半"。
            //   * 图标：调用 [AppMark] 时传 `circle = true`（欢迎页仍保持圆角方形）；
            //   * 进度条：0.055 → 0.0275（正好一半）。圆角由 `h / 2` 推出来，
            //     高度减半后两端自然还是胶囊，颜色与动画一律不动。
            val barHeight = metrics.sizeDp * 0.0275f
            val percentWidth = metrics.sizeDp * 0.20f

            // 1.2.70：动画管线预热。这一层画在最底下、alpha 只有 5%，肉眼几乎看不见，
            // 但它把弹簧 / 无限循环 / 转场 / 颜色 / 尺寸 / 图层变换 / Canvas 图元 /
            // 玻璃材质 / 文字光栅先各跑一遍（见 [AnimationWarmup]）。
            AnimationWarmup(metrics)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = metrics.sizeDp * 0.08f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 预编译这一屏要求圆形图标（和欢迎页的圆角方形区分开）
                AppMark(size = metrics.sizeDp * 0.30f, circle = true)
                Spacer(Modifier.height(metrics.sizeDp * 0.030f))
                Text(
                    text = "f(x)",
                    fontSize = metrics.fontTitle,
                    color = KeyPalette.text,
                    maxLines = 1
                )
                Spacer(Modifier.height(metrics.sizeDp * 0.070f))
                Row(
                    modifier = Modifier.width(contentWidth),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PrecompileBar(
                        progress = shown,
                        heightDp = barHeight,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(metrics.sizeDp * 0.022f))
                    Text(
                        // 四舍五入到整百分比：条形自己会平滑，数字不用带小数点
                        text = "${(shown * 100f).roundToInt()}%",
                        fontSize = metrics.fontSmall,
                        color = KeyPalette.textMuted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(percentWidth)
                    )
                }
                Spacer(Modifier.height(metrics.sizeDp * 0.035f))
                Text(
                    // 正在进行的项目：字号提到 fontSmall，允许折两行（长标题不截断）
                    text = label,
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(contentWidth)
                )
            }
        }
    }
}

/** 预热层透明度：0.05 肉眼几乎看不见，但**照常参与绘制**（0f 会被硬件渲染整层剪掉）。 */
private const val WARMUP_ALPHA = 0.05f

/**
 * 动画管线预热（1.2.70）。
 *
 * ## 为什么加（用户原话）
 *
 * > "再给预编译阶段加个播放所有类型的动画，手表进入软件之后刚开始动画会很卡，
 * >  播放一遍之后就流畅了，所以在预编译阶段就播放就不会卡了。"
 *
 * 手表上"第一次播某种动画"要付几笔一次性的钱：动画类装载 / JIT、Shader 与
 * RenderEffect 的首次编译、`graphicsLayer` 的硬件层第一次建纹理、字体首次光栅化。
 * 播过一遍之后这些都在缓存里，于是就"流畅了"。
 *
 * 旧版这里写着"动画预热试过、回退"——那次只播了"列表滚动 + 按钮淡入"两种，
 * 覆盖不到真正掉帧的那几类。这一版**按类型铺齐**：
 *
 * | 类型 | 这里怎么播 |
 * |---|---|
 * | tween（线性 / 缓动） | [rememberInfiniteTransition] 里四条（含 [RepeatMode.Reverse]） |
 * | keyframes（分段插值） | 第三条的 `0→1→0.25→1` |
 * | spring（按压回弹） | [Animatable] 以 [spring] 0.45/900 往返（和 `PressFeedback` 同参数） |
 * | AnimatedVisibility 转场 | 每 380ms 切一次：`fadeIn/fadeOut + slideIn/OutVertically` |
 * | 颜色 / 尺寸补间 | [animateColorAsState] + [animateDpAsState] |
 * | 内容尺寸动画 | [animateContentSize] |
 * | 图层变换 | `graphicsLayer` 的位移 / 旋转 / 缩放（每帧只失效图层） |
 * | Canvas 图元 | 线、圆、圆角矩形、圆弧、宽 Path（正弦）、虚线 `PathEffect`、渐变 `Brush` |
 * | 玻璃材质 | [glassSurface]（投影 6 层 + 1dp 描边 + 上沿高光） |
 * | 文字光栅 | 两处 `Text`（`f(x)` / 键盘标签） |
 *
 * 注意它**只在遮罩还在时存在**：`overlayVisible` 一变 false，这一层就连同
 * [PrecompileScreen] 一起离开组合树，动画协程随之取消，不会在正式界面里空转。
 *
 * @param metrics 屏尺寸（和 `PrecompileScreen` 里那份同一实例，字号/尺寸口径一致）
 */
@Composable
private fun AnimationWarmup(metrics: WatchMetrics) {
    val infinite = rememberInfiniteTransition(label = "warmup")

    // ① 线性 tween（进度条 / 光标那一类）
    val linear by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "linear"
    )
    // ② 缓动 tween（转场 / 淡入那一类）
    val eased by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700, easing = FastOutSlowInEasing)),
        label = "eased"
    )
    // ③ keyframes（分段插值）
    val keyed by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 800
                0f at 0
                1f at 220 using LinearEasing
                0.25f at 520 using FastOutSlowInEasing
                1f at 800
            }
        ),
        label = "keyed"
    )
    // ④ 往返无限循环（光标闪烁 / 教程脉冲那一类）
    val pingPong by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pingPong"
    )

    // ⑤ 弹簧（和 PressFeedback 完全同参数：dampingRatio 0.45 / stiffness 900）
    val springy = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var on = false
        while (true) {
            on = !on
            springy.animateTo(
                targetValue = if (on) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f)
            )
        }
    }

    // ⑥ 进出场（AnimatedVisibility + 淡入淡出 + 竖直滑动）
    var cardVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(380)
            cardVisible = !cardVisible
        }
    }

    // ⑦ 颜色 / 尺寸补间（目标值只在半个周期翻一次，重组很轻）
    val flipped = linear > 0.5f
    val warmColor by animateColorAsState(
        targetValue = if (flipped) CurvePalette[1] else KeyPalette.normal,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    )
    val warmSize by animateDpAsState(
        targetValue = metrics.sizeDp * if (flipped) 0.17f else 0.11f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = WARMUP_ALPHA }
    ) {
        // ⑧ Canvas 图元：线 / 圆 / 圆角矩形 / 圆弧 / 正弦 Path / 虚线 / 渐变
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val ink = KeyPalette.textMuted

            drawLine(
                color = ink,
                start = Offset(w * 0.08f, h * 0.14f),
                end = Offset(w * 0.08f + w * 0.6f * eased, h * 0.32f),
                strokeWidth = 3f
            )
            drawCircle(
                color = CurvePalette[0],
                radius = w * 0.06f,
                center = Offset(w * 0.30f, h * 0.52f)
            )
            drawRoundRect(
                color = CurvePalette[1].copy(alpha = 0.4f),
                topLeft = Offset(w * 0.60f, h * 0.10f),
                size = Size(w * 0.26f, h * 0.10f),
                cornerRadius = CornerRadius(h * 0.05f, h * 0.05f)
            )
            drawArc(
                color = ink.copy(alpha = 0.5f),
                startAngle = 0f,
                sweepAngle = 90f + 180f * keyed,
                useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.70f),
                size = Size(w * 0.20f, h * 0.20f),
                style = Stroke(width = 3f)
            )

            // 宽 Path（正弦）+ 圆头 Stroke：曲线绘制那条路
            val sine = Path()
            for (i in 0..64) {
                val t = i / 64f
                val x = w * t
                val y = h * 0.86f - h * 0.07f * kotlin.math.sin(t * 6.2831853f)
                if (i == 0) sine.moveTo(x, y) else sine.lineTo(x, y)
            }
            drawPath(
                sine,
                color = CurvePalette[0],
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // 虚线（PathEffect）
            val dashed = Path().apply {
                moveTo(w * 0.55f, h * 0.76f)
                lineTo(w * 0.95f, h * 0.76f)
            }
            drawPath(
                dashed,
                color = ink.copy(alpha = 0.6f),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            )

            // 线性渐变 / 径向渐变（Brush 首次使用要编译 shader）
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(CurvePalette[0].copy(alpha = 0.5f), Color.Transparent),
                    start = Offset(0f, h * 0.40f),
                    end = Offset(w * 0.60f, h * 0.58f)
                ),
                topLeft = Offset(w * 0.05f, h * 0.40f),
                size = Size(w * 0.30f, h * 0.16f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CurvePalette[1].copy(alpha = 0.5f), Color.Transparent)
                ),
                radius = w * 0.12f,
                center = Offset(w * 0.80f, h * 0.52f)
            )
        }

        // ⑨ 图层变换 + 玻璃材质 + 文字光栅（每帧只失效图层，不重组）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(metrics.sizeDp * 0.18f)
                .graphicsLayer {
                    translationX = (linear - 0.5f) * size.width * 0.20f
                    translationY = (pingPong - 0.5f) * size.height * 0.20f
                    rotationZ = eased * 360f
                    scaleX = 0.85f + 0.30f * springy.value
                    scaleY = 0.85f + 0.30f * springy.value
                }
                .glassSurface(PillShape)
                .background(warmColor, PillShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "f(x)",
                fontSize = metrics.fontSmall,
                color = KeyPalette.text,
                maxLines = 1
            )
        }

        // ⑩ 进场 / 出场 + 内容尺寸动画
        AnimatedVisibility(
            visible = cardVisible,
            enter = fadeIn(tween(durationMillis = 220)) +
                slideInVertically(tween(durationMillis = 260, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(durationMillis = 180)) +
                slideOutVertically(tween(durationMillis = 220, easing = FastOutSlowInEasing)) { it / 3 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = metrics.sizeDp * 0.04f)
        ) {
            Box(
                modifier = Modifier
                    .animateContentSize()
                    .size(warmSize)
                    .glassSurface(PillShape)
                    .background(KeyPalette.bubble, PillShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (flipped) "abc" else "ab",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.text,
                    maxLines = 1
                )
            }
        }
    }
}

/** 一条胶囊进度条：暗色轨道 + 蓝色已完成部分。 */
@Composable
private fun PrecompileBar(
    progress: Float,
    heightDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    // 蓝色取曲线色板第 1 色：夜间是天蓝、白天是深蓝，和"蓝色进度条"的要求一致，
    // 又天然跟随主题（不会在白底上留一条看不清的浅蓝）。
    val blue: Color = CurvePalette[0]
    val track: Color = KeyPalette.text.copy(alpha = 0.14f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
    ) {
        val h = size.height
        val r = h / 2f
        drawRoundRect(color = track, cornerRadius = CornerRadius(r, r))
        val w = size.width * progress.coerceIn(0f, 1f)
        if (w > 0.5f) {
            // 宽度小于一个圆角时直接按圆角画，否则 roundRect 会画出尖角
            if (w >= h) {
                drawRoundRect(
                    color = blue,
                    cornerRadius = CornerRadius(r, r),
                    size = Size(w, h),
                    topLeft = Offset.Zero
                )
            } else {
                drawRoundRect(
                    color = blue,
                    cornerRadius = CornerRadius(w / 2f, w / 2f),
                    size = Size(w, h),
                    topLeft = Offset.Zero
                )
            }
        }
    }
}
