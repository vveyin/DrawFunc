// Headless PNG preview renderer for the pure-Kotlin math engine.
//
// Purpose: with no watch and no emulator, draw the sampling output of all four
//          plotting modes into 600x600 PNGs so the math can be checked by eye.
//
// Reads the engine (app/src/main/java/.../engine) but never modifies it; writes
// only build/preview/*.png.
//
// Compile/run with tools/render-preview.ps1 -- do not invoke kotlinc directly.

import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.PlotResult
import com.functiondrawer.watch.engine.Plotter
import com.functiondrawer.watch.engine.Quality
import com.functiondrawer.watch.engine.Viewport
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor

private const val W = 600
private const val H = 600

/** One preview case: the functions to plot plus the world-x range of the view. */
private class CaseSpec(
    val file: String,
    val title: String,
    val minX: Double,
    val maxX: Double,
    val fns: List<PlotFunction>
)

/** Bright, high-contrast curve colours (indexed by Curve.colorIndex). */
private val CURVE_COLORS = arrayOf(
    Color(0x39FF6A), // green
    Color(0xFFD23F), // orange
    Color(0x3FE0FF), // cyan
    Color(0x5C8CFF), // blue
    Color(0xFF5CE1), // magenta
    Color(0xFFF75F), // yellow
    Color(0xFF3B30)  // red
)

private val GRID_COLOR = Color(0x2A2A2A)
private val AXIS_COLOR = Color(0xC8C8C8)
private val BG_COLOR = Color.BLACK

private fun cases(): List<CaseSpec> = listOf(
    CaseSpec(
        "01-sin.png", "y = sin(x)  [EXPLICIT, view -10..10]", -10.0, 10.0,
        listOf(PlotFunction(1L, PlotMode.EXPLICIT, "sin(x)", colorIndex = 0))
    ),
    CaseSpec(
        "02-parabola.png", "y = x^2/4  [EXPLICIT, view -10..10]", -10.0, 10.0,
        listOf(PlotFunction(1L, PlotMode.EXPLICIT, "x^2/4", colorIndex = 1))
    ),
    CaseSpec(
        "03-cardioid.png", "r = 1-sin(theta)  [POLAR, t in 0..2pi, view -3..3]", -3.0, 3.0,
        listOf(
            PlotFunction(
                1L, PlotMode.POLAR, "1-sin(\u03b8)",
                tMin = 0.0, tMax = 2.0 * PI, colorIndex = 2
            )
        )
    ),
    CaseSpec(
        "04-lissajous.png", "(sin(3t), sin(4t))  [PARAMETRIC, view -2..2]", -2.0, 2.0,
        listOf(
            PlotFunction(
                1L, PlotMode.PARAMETRIC, "sin(3t)", "sin(4t)",
                tMin = 0.0, tMax = 2.0 * PI, colorIndex = 3
            )
        )
    ),
    CaseSpec(
        "05-unitcircle.png", "x^2+y^2=1  [IMPLICIT, view -2..2]", -2.0, 2.0,
        listOf(PlotFunction(1L, PlotMode.IMPLICIT, "x^2+y^2=1", colorIndex = 4))
    ),
    CaseSpec(
        "06-folium.png", "x^3+y^3=3xy  [IMPLICIT, view -3..3]", -3.0, 3.0,
        listOf(PlotFunction(1L, PlotMode.IMPLICIT, "x^3+y^3=3xy", colorIndex = 5))
    ),
    CaseSpec(
        "07-tan.png", "y = tan(x)  [EXPLICIT, view -pi..pi, asymptote check]", -PI, PI,
        listOf(PlotFunction(1L, PlotMode.EXPLICIT, "tan(x)", colorIndex = 6))
    )
)

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")

    val outDir = if (args.isNotEmpty()) File(args[0]) else File("build/preview")
    outDir.mkdirs()
    if (!outDir.isDirectory) {
        System.err.println("cannot create output dir: ${outDir.absolutePath}")
        kotlin.system.exitProcess(3)
    }

    println("== FunctionDrawer headless preview ==")
    println("output dir : ${outDir.absolutePath}")
    println("java       : ${System.getProperty("java.version")}  headless=${java.awt.GraphicsEnvironment.isHeadless()}")
    println("engine     : Plotter.plot(...) @ Quality.NORMAL")

    var errorCases = 0
    var emptyCases = 0
    for ((i, spec) in cases().withIndex()) {
        val vp = Viewport.forRange(W.toFloat(), H.toFloat(), spec.minX, spec.maxX)
        val result = Plotter.plot(spec.fns, vp, Quality.NORMAL)
        val png = renderOne(i + 1, spec, vp, result, outDir)
        report(i + 1, spec, vp, result, png)
        if (result.errors.isNotEmpty()) errorCases++
        if (result.curves.isEmpty()) emptyCases++
    }

    println("--------------------------------------------------------------")
    println("== done: ${cases().size} cases -> ${outDir.absolutePath} ==")
    if (errorCases > 0) println("!! $errorCases case(s) reported engine errors (see ERROR lines)")
    if (emptyCases > 0) println("!! $emptyCases case(s) produced no curve at all (blank image)")
    if (errorCases == 0 && emptyCases == 0) println("all cases rendered, no engine errors")
    if (errorCases > 0 || emptyCases > 0) kotlin.system.exitProcess(1)
}

/** Draw one case and write its PNG. */
private fun renderOne(
    index: Int,
    spec: CaseSpec,
    vp: Viewport,
    result: PlotResult,
    outDir: File
): File {
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = BG_COLOR
    g.fillRect(0, 0, W, H)
    drawGrid(g, vp)
    drawAxes(g, vp)
    drawCurves(g, result)
    g.dispose()

    val file = File(outDir, spec.file)
    ImageIO.write(img, "png", file)
    return file
}

/** Per-case stdout statistics. */
private fun report(
    index: Int,
    spec: CaseSpec,
    vp: Viewport,
    result: PlotResult,
    png: File
) {
    println("--------------------------------------------------------------")
    println("case $index : ${spec.title}")
    println("  file        : ${spec.file}  (${png.length()} bytes)")
    println(
        "  viewport    : ${W}x$H  x=[${fmt(vp.worldLeft)}, ${fmt(vp.worldRight)}]" +
            "  y=[${fmt(vp.worldBottom)}, ${fmt(vp.worldTop)}]  gridStep=${fmt(vp.gridStep)}"
    )
    println("  curves.size : ${result.curves.size}")
    println("  totalPoints : ${result.totalPoints}")
    if (result.curves.isNotEmpty()) {
        val counts = result.curves.map { it.pointCount }
        val detail = if (counts.size <= 10) {
            counts.joinToString(", ")
        } else {
            "min=${counts.min()} max=${counts.max()} avg=${"%.1f".format(counts.average())}"
        }
        println("  per curve   : $detail")
        println("  drawn bbox  : ${bbox(result)}")
    }
    if (result.errors.isEmpty()) {
        println("  errors      : none")
    } else {
        for ((id, msg) in result.errors) println("  ERROR       : functionId=$id -> $msg")
    }
    if (result.curves.isEmpty()) println("  WARN        : no curve segments, the PNG is empty background")
}

private fun fmt(v: Double): String = String.format("%.4f", v)

/** Pixel bounding box of every sampled point (diagnostic for "did it land on canvas"). */
private fun bbox(result: PlotResult): String {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (curve in result.curves) {
        val p = curve.points
        var k = 0
        while (k + 1 < p.size) {
            val x = p[k].toDouble()
            val y = p[k + 1].toDouble()
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            k += 2
        }
    }
    if (minX > maxX) return "n/a"
    return String.format("x=[%.1f, %.1f] y=[%.1f, %.1f]  (canvas 0..%d)", minX, maxX, minY, maxY, W)
}

// ----------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------

/** Dark grey grid, one line per multiple of Viewport.gridStep. */
private fun drawGrid(g: Graphics2D, vp: Viewport) {
    val step = vp.gridStep
    if (!step.isFinite() || step <= 0.0) return
    g.color = GRID_COLOR
    g.stroke = BasicStroke(1f)

    val i0 = ceil(vp.worldLeft / step).toLong()
    val i1 = floor(vp.worldRight / step).toLong()
    if (i1 - i0 <= 2000) {
        var i = i0
        while (i <= i1) {
            val x = vp.toScreenX(i * step).toInt()
            g.drawLine(x, 0, x, H - 1)
            i++
        }
    }
    val j0 = ceil(vp.worldBottom / step).toLong()
    val j1 = floor(vp.worldTop / step).toLong()
    if (j1 - j0 <= 2000) {
        var j = j0
        while (j <= j1) {
            val y = vp.toScreenY(j * step).toInt()
            g.drawLine(0, y, W - 1, y)
            j++
        }
    }
}

/** Grey-white x and y axes. */
private fun drawAxes(g: Graphics2D, vp: Viewport) {
    g.color = AXIS_COLOR
    g.stroke = BasicStroke(1.5f)
    val x0 = vp.toScreenX(0.0).toInt()
    val y0 = vp.toScreenY(0.0).toInt()
    if (y0 in 0 until H) g.drawLine(0, y0, W - 1, y0)
    if (x0 in 0 until W) g.drawLine(x0, 0, x0, H - 1)
}

/** Stroke every curve as a polyline over its interleaved screen-pixel points. */
private fun drawCurves(g: Graphics2D, result: PlotResult) {
    g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    for (curve in result.curves) {
        val p = curve.points
        if (p.size < 4) continue
        g.color = CURVE_COLORS[Math.floorMod(curve.colorIndex, CURVE_COLORS.size)]
        val path = Path2D.Float()
        path.moveTo(p[0], p[1])
        var k = 2
        while (k + 1 < p.size) {
            path.lineTo(p[k], p[k + 1])
            k += 2
        }
        g.draw(path)
    }
}
