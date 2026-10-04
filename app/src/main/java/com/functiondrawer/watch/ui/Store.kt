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
        // **新字段一律追加在末尾**：中间插一个会让老存档的字段整体错位，
        // 读出来就是"网格开关跑到坐标系上"这种灾难（见 load 里按 size 判缺省的做法）。
        val settingsText = listOf(
            settings.coordinateSystem.ordinal.toString(),
            if (settings.showGrid) "1" else "0",
            if (settings.showAxes) "1" else "0",
            if (settings.showNumbers) "1" else "0",
            if (settings.showPresets) "1" else "0",
            if (settings.confirmCoordSwitch) "1" else "0",
            if (settings.angleUnit == AngleUnit.DEG) "1" else "0",
            // 第 8 个字段（后加，需求 #6）：屏蔽系统手势
            if (settings.blockSystemGestures) "1" else "0",
            // 第 9 个字段（后加，需求 #7）：底部按键/滑动条缩放栏
            if (settings.showZoomBar) "1" else "0",
            // 第 10 个字段（后加，本轮）：首次使用工具时那条"回到移动"提示还要不要弹。
            // **追加在末尾**（见上面那段规矩）：中间插一个会让老存档的字段整体错位。
            if (settings.confirmReturnToMove) "1" else "0"
        ).joinToString(",")
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
        val raw = prefs.getString(KEY_FUNCTIONS, null) ?: return null
        if (raw.isBlank()) return null

        val functions = raw.split("\n").mapNotNull { line -> parseLine(line) }
        if (functions.isEmpty()) return null

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

        // 设置：任何一个字段不合法就整组退回默认值，别让存档把界面弄坏
        val settings = run {
            val parts = prefs.getString(KEY_SETTINGS, null)?.split(",")
            val default = WatchSettings()
            if (parts == null || parts.size < 4) return@run default
            val csOrdinal = parts[0].toIntOrNull() ?: return@run default
            val systems = CoordinateSystem.entries
            if (csOrdinal !in systems.indices) return@run default
            WatchSettings(
                coordinateSystem = systems[csOrdinal],
                showGrid = parts[1] == "1",
                showAxes = parts[2] == "1",
                showNumbers = parts[3] == "1",
                // 老存档没有第 5 个字段：缺省当作"显示示例"
                showPresets = if (parts.size > 4) parts[4] == "1" else true,
                // 第 6 个字段也是后加的（切换坐标系的确认框），缺省当作"要问"
                confirmCoordSwitch = if (parts.size > 5) parts[5] == "1" else true,
                // 第 7 个字段：角度制，缺省弧度制
                angleUnit = if (parts.size > 6 && parts[6] == "1") AngleUnit.DEG else AngleUnit.RAD,
                // 第 8 个字段（需求 #6）：老存档没有这一段 → **当作"开"**。
                // 它和别的布尔项的缺省方向相反是有原因的：这个开关的默认值就是
                // "开"，老存档的用户升级上来必须继续是开着的（否则升级一次
                // 边缘上滑就被系统抢走，用户会以为"更新把它改坏了"）。
                blockSystemGestures = if (parts.size > 7) parts[7] == "1" else true,
                // 第 9 个字段（需求 #7）：老存档没有 → false（= 不显示缩放栏）
                showZoomBar = if (parts.size > 8) parts[8] == "1" else false,
                // 第 10 个字段（本轮，追加在末尾）：首次用工具时那条"回到移动"提示
                // 还要不要弹。老存档没有这一段 → **当作"要弹一次"**（默认值 true）——
                // 方向是刻意的：这一条提示的内容（"点这里回到移动"）本轮刚从提示条上删掉，
                // 升级上来的老用户缺了这次说明就少知道一件事；而多问一次的代价
                // 只是一个可以勾掉"不再提示"的框。勾过之后这个字段就是 0，永久不再弹。
                confirmReturnToMove = if (parts.size > 9) parts[9] == "1" else true
            )
        }

        return Saved(
            functions = functions,
            selectedId = prefs.getLong(KEY_SELECTED, functions.first().id),
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
