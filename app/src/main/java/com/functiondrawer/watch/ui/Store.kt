// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.content.Context
import android.net.Uri
import com.functiondrawer.watch.engine.LineStyle
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.Viewport

/**
 * 极简持久化。
 *
 * 用手写行格式存 SharedPreferences，而不是引入 JSON / Room：
 * 数据量就那么几行，多一个依赖在手表上只意味着更大的包和更慢的启动。
 *
 * 格式（一行一个函数，字段用 | 分隔，表达式做 URL 编码以免和分隔符打架）：
 *   id|mode序号|颜色|可见|tMin|tMax|src1|src2|度制|名字|线型|线径
 *
 * 第 9 个字段「度制」、第 10~12 个「名字/线型/线径」都是后加的：
 * 老存档没有这些段，读出来就是各自的默认值（跟随设置 / 空名字 / 实线 / 默认粗细），
 * 行为与改动前一致。
 *
 * 其它三个键（都各自独立，**谁也不挂在函数列表上**）：
 *   * `KEY_SETTINGS` —— 设置，一行逗号分隔；**字段顺序住在 `PlotMetrics.SettingsFields`**
 *     （纯 Kotlin，离线单测钉着"写的顺序 == 读的顺序"，本文件只做映射）；
 *   * `KEY_VIEW`     —— 视野：`centerX,centerY,unitsPerPixel`；
 *   * `KEY_SLIDERS`  —— 滑动条，一行一条。
 *
 * ⚠️ **"函数列表为空"不等于"这份存档作废"**（本轮修的 bug）：只要上面任何一个键
 * 有值，[load] 就会把它们读回来。详见 [load] 里那一段注释。
 */
object Store {

    private const val PREF = "function_drawer"
    private const val KEY_FUNCTIONS = "functions"
    private const val KEY_VIEW = "viewport"
    private const val KEY_SELECTED = "selected"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_SLIDERS = "sliders"
    private const val SEP = "|"

    data class Saved(
        val functions: List<PlotFunction>,
        val selectedId: Long,
        val centerX: Double,
        val centerY: Double,
        val unitsPerPixel: Double,
        val settings: WatchSettings,
        /** 滑动条（#3）。老存档没有这一段 → 空表，行为与改动前一致。 */
        val sliders: List<Slider> = emptyList()
    )

    fun save(
        context: Context,
        functions: List<PlotFunction>,
        selectedId: Long,
        viewport: Viewport,
        settings: WatchSettings,
        sliders: List<Slider> = emptyList()
    ) {
        if (viewport.widthPx <= 1f) return
        val text = functions.joinToString("\n") { fn ->
            listOf(
                fn.id.toString(),
                fn.mode.ordinal.toString(),
                fn.colorIndex.toString(),
                if (fn.visible) "1" else "0",
                fn.tMin.toString(),
                fn.tMax.toString(),
                Uri.encode(fn.src1),
                Uri.encode(fn.src2),
                when (fn.degrees) {
                    true -> "1"
                    false -> "0"
                    null -> "-"
                },
                // 第 10~12 个字段（后加）：名字、线型、线径倍数。
                // 名字要 URL 编码：用户可以在属性界面里改名，可能打出 `|` 或换行。
                Uri.encode(fn.name),
                fn.lineStyle.ordinal.toString(),
                fn.widthScale.toString()
            ).joinToString(SEP)
        }
        // 设置存成一行若干字段，取值都很小，没必要上 JSON。
        //
        // ⚠️ **字段的个数与顺序不在本文件**：它们住在 `PlotMetrics.SettingsFields`
        // （纯 Kotlin，离线单测钉着"写的顺序 == 读的顺序"）。本文件只负责
        // "`WatchSettings` ⇄ `SettingsFields`"这一层映射。
        //
        // 为什么值得为此搬一次家：上一版这里是一串手写的 `listOf(...)`，而 load 里
        // 是另一串手写的 `parts[N]` —— 两串靠**人对齐**。这个项目往这一行末尾追加过
        // 三段设置（屏蔽系统手势 / 按键缩放栏 / 回到移动提示），每追加一次都要在
        // 两处同时数对下标；错一处就是"某个设置永远读回默认值"（用户报的正是这个症状，
        // 只不过这一轮查出来的真因在下面 load 那一处，见那边的注释）。
        // 现在追加一段 = 在 `SettingsFields` 的主构造参数末尾加一个（并给出"老存档
        // 缺这一段时"的默认值），编码与解码都从同一个顺序生成，错位不可能再发生。
        val settingsText = SettingsFields(
            coordinateSystem = settings.coordinateSystem.ordinal,
            showGrid = settings.showGrid,
            showAxes = settings.showAxes,
            showNumbers = settings.showNumbers,
            showPresets = settings.showPresets,
            confirmCoordSwitch = settings.confirmCoordSwitch,
            degrees = settings.angleUnit == AngleUnit.DEG,
            blockSystemGestures = settings.blockSystemGestures,
            showZoomBar = settings.showZoomBar,
            confirmReturnToMove = settings.confirmReturnToMove
        ).encode()
        // 滑动条：一行一条，字段 id|参数|下界|上界|步长|当前值|颜色|可见
        val slidersText = sliders.joinToString("\n") { s ->
            listOf(
                s.id.toString(),
                s.param,
                s.min.toString(),
                s.max.toString(),
                s.step.toString(),
                s.value.toString(),
                s.colorIndex.toString(),
                if (s.visible) "1" else "0"
            ).joinToString(SEP)
        }
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_FUNCTIONS, text)
                .putLong(KEY_SELECTED, selectedId)
                .putString(
                    KEY_VIEW,
                    "${viewport.centerX},${viewport.centerY},${viewport.unitsPerPixel}"
                )
                .putString(KEY_SETTINGS, settingsText)
                .putString(KEY_SLIDERS, slidersText)
                .apply()
        }
    }

    fun load(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // ==================================================================
        // ⚠️ 本轮修的真因：「设置没有被记住」不是没写进去，而是**读的时候被整份丢掉**
        //
        // 改动前这里是三句连着的提前返回：
        // ```
        // val raw = prefs.getString(KEY_FUNCTIONS, null) ?: return null
        // if (raw.isBlank()) return null
        // val functions = raw.split("\n").mapNotNull { parseLine(it) }
        // if (functions.isEmpty()) return null     // ← 元凶
        // ```
        // 也就是**整份存档（设置、滑动条、视野）都挂在那份函数列表上**：只要存档里
        // 一条函数都没有，`load` 就返回 null，而调用点（`GraphScreen`）写的是
        // `Store.load(context)?.let { saved -> … }` —— 整个恢复块被跳过，设置自然是默认值。
        //
        // 而"存档里一条函数都没有"**一点都不罕见**：
        //   * `GraphState.updateSettings` 在**切换坐标系**时会清空全部函数
        //     （那是既定行为："笛卡尔下输入的 y=f(x) 换到极坐标毫无意义"）——
        //     而"坐标系"恰好就是设置页的第一行，用户很容易点一下；
        //   * 删掉最后一行、功能界面长按垃圾桶清空全部，都会走到同一个状态；
        //   * 刚装好的新用户如果先去设置里调了一圈（还没建函数），也读不回来。
        // 于是 `save` 那一侧明明把设置写进了 `KEY_SETTINGS`（它一直是对的），
        // 重启之后却永远读不到 —— 用户看到的正是"每次打开都回归默认"。
        //
        // 现在：设置 / 视野 / 滑动条**各自独立地读**，函数列表允许为空
        // （空只表示"这位用户还没有函数"，不是"这份存档作废"）。
        // 只有"这一行键一个都没写过"（真·全新安装）才返回 null。
        //
        // 兼容性：老存档（函数非空、设置行只有四段 / 七段 / 九段）走的是**同一条路**，
        // 缺的段由 `SettingsFields.decode` 按各自的默认值补齐 —— 与改动前逐项一致。
        // ==================================================================
        val rawFunctions = prefs.getString(KEY_FUNCTIONS, null)
        val functions = rawFunctions
            ?.split("\n")
            ?.mapNotNull { line -> parseLine(line) }
            ?: emptyList()
        // "还有没有东西可读"这条判据本身抽在 `PlotMetrics.archiveHasContent`（纯函数，
        // 离线单测钉着）—— 它是本轮那个 bug 的正中央，不该只靠"装到表上试一次"。
        if (!archiveHasContent(
                hasFunctions = functions.isNotEmpty(),
                hasSettings = prefs.contains(KEY_SETTINGS),
                hasView = prefs.contains(KEY_VIEW),
                hasSliders = prefs.contains(KEY_SLIDERS)
            )
        ) {
            return null
        }

        var centerX = 0.0
        var centerY = 0.0
        var upp = 0.0
        prefs.getString(KEY_VIEW, null)?.split(",")?.let { parts ->
            if (parts.size == 3) {
                centerX = parts[0].toDoubleOrNull() ?: 0.0
                centerY = parts[1].toDoubleOrNull() ?: 0.0
                upp = parts[2].toDoubleOrNull() ?: 0.0
            }
        }
        // 缩放值必须合法，否则宁可用默认视野
        if (!upp.isFinite() || upp <= Viewport.MIN_UNITS_PER_PIXEL) upp = 0.0

        // 设置：字段顺序与"缺段补默认"的规矩全在纯 Kotlin 那一份里
        // （`PlotMetrics.SettingsFields`，离线单测钉着）。
        // 这里只把序号映射回枚举，并再做一次"越界就退回默认"的把关。
        val settings = run {
            val f = SettingsFields.decode(
                prefs.getString(KEY_SETTINGS, null),
                CoordinateSystem.entries.size
            )
            WatchSettings(
                coordinateSystem = CoordinateSystem.entries[
                    f.coordinateSystem.coerceIn(0, CoordinateSystem.entries.size - 1)
                ],
                showGrid = f.showGrid,
                showAxes = f.showAxes,
                showNumbers = f.showNumbers,
                showPresets = f.showPresets,
                confirmCoordSwitch = f.confirmCoordSwitch,
                angleUnit = if (f.degrees) AngleUnit.DEG else AngleUnit.RAD,
                blockSystemGestures = f.blockSystemGestures,
                showZoomBar = f.showZoomBar,
                confirmReturnToMove = f.confirmReturnToMove
            )
        }

        return Saved(
            functions = functions,
            // ⚠️ 函数列表可以为空，所以**不能**再写 `functions.first().id`
            // （那是一句会崩的写法：老代码靠上面那个提前返回躲过去了）。
            // -1 = "没有可选的"，`GraphState.restore` 那边对空表是直接不动的。
            selectedId = prefs.getLong(KEY_SELECTED, functions.firstOrNull()?.id ?: -1L),
            centerX = centerX,
            centerY = centerY,
            unitsPerPixel = if (upp > 0.0) upp else Viewport.forRange(466f, 466f, -10.0, 10.0).unitsPerPixel,
            settings = settings,
            sliders = prefs.getString(KEY_SLIDERS, null)
                ?.split("\n")
                ?.mapNotNull { parseSlider(it) }
                ?: emptyList()
        )
    }

    /** 滑动条一行：`id|参数|下界|上界|步长|当前值|颜色|可见`。坏行直接丢掉。 */
    private fun parseSlider(line: String): Slider? {
        if (line.isBlank()) return null
        val f = line.split(SEP)
        if (f.size < 8) return null
        return runCatching {
            val min = f[2].toDouble()
            val max = f[3].toDouble()
            val step = f[4].toDouble()
            if (!min.isFinite() || !max.isFinite() || max <= min || step <= 0.0) return null
            Slider(
                id = f[0].toLong(),
                param = f[1],
                min = min,
                max = max,
                step = step,
                value = f[5].toDouble().coerceIn(min, max),
                colorIndex = f[6].toInt().coerceAtLeast(0),
                visible = f[7] == "1",
                // 播放状态**不落库**：重启之后不该自己动起来
                playing = false
            )
        }.getOrNull()
    }

    private fun parseLine(line: String): PlotFunction? {
        if (line.isBlank()) return null
        val f = line.split(SEP)
        if (f.size < 8) return null
        return runCatching {
            PlotFunction(
                id = f[0].toLong(),
                mode = PlotMode.entries[f[1].toInt().coerceIn(0, PlotMode.entries.size - 1)],
                colorIndex = f[2].toInt().coerceAtLeast(0),
                visible = f[3] == "1",
                tMin = f[4].toDouble(),
                tMax = f[5].toDouble(),
                src1 = Uri.decode(f[6]),
                src2 = Uri.decode(f[7]),
                // 第 9 个字段：老存档没有 → null（跟随设置默认值）
                degrees = when (if (f.size > 8) f[8] else "-") {
                    "1" -> true
                    "0" -> false
                    else -> null
                },
                // 第 10~12 个字段：老存档没有 → 名字空串（UI 退回显示表达式）、实线、默认粗细
                name = if (f.size > 9) Uri.decode(f[9]) else "",
                lineStyle = if (f.size > 10) {
                    LineStyle.entries[f[10].toInt().coerceIn(0, LineStyle.entries.size - 1)]
                } else {
                    LineStyle.SOLID
                },
                widthScale = if (f.size > 11) {
                    f[11].toFloatOrNull()?.coerceIn(0.4f, 3f) ?: 1f
                } else {
                    1f
                }
            )
        }.getOrNull()
    }
}
