package com.functiondrawer.watch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志的三种"带出去"方式（3.38）。
 *
 * ## 为什么是三种
 *
 * 用户的原话：
 *
 * > "在日志界面底部加一个保存日志图片的按钮、复制日志文字的按钮
 * >  和一个保存日志到应用内部的按钮。"
 *
 * 三条路各有各的用处，缺一条都会卡住某一类反馈：
 *
 * | 按钮 | 用户拿到什么 | 什么时候最好用 |
 * |---|---|---|
 * | 存图 | `Pictures/DrawFunc/crash_....png` | 手表上直接开相册/聊天软件发出去 |
 * | 复制 | 剪贴板里的纯文本 | 在表上粘到某个输入框，或同步到手机再粘 |
 * | 存内部 | `filesDir/crash_....txt` + 一句路径提示 | 全量原文留档，`adb pull` 或下次启动自己带出来 |
 *
 * ## 存图为什么是"自己画一张"
 *
 * 崩溃屏是 Compose 画的，但那套 `GraphicsLayer` 记录在**崩溃这个语境下不可靠**
 * （正是它把上一个版本炸掉的）。这里用最笨也最稳的办法：
 * 拿 [Canvas] + [Paint] 把报告**重排成一张长图**。
 *
 * 好处是这张图**不依赖当前屏幕上有什么** —— 用户滚到哪、屏幕多小都不影响，
 * 整段栈一定完整地在这张图里。代价是要自己算行宽，所以下面按"字符估算宽度"折行，
 * 中文字符按 1 个字宽算（等宽字体下这是够准的）。
 *
 * ## 一个权限都不申请
 *
 * 存图落在**应用私有的外部目录**（`Android/data/<包名>/files/Pictures/DrawFunc`）
 * 以及 `Pictures/DrawFunc`（API 29+ 走 MediaStore，不需要权限）。
 * 仓库里"这个 App 不申请任何权限"是写死的性质（见 `ImageExport.kt` / `DownloadExport.kt`
 * 的注释），这里继续守着它 —— 手表上弹一个整屏权限框的代价比"用户多点一步找文件"大得多。
 */
internal object CrashLogExport {

    /** 存图 / 存文本的子目录名（`Pictures` 与 `filesDir` 底下都用它）。 */
    private const val DIR_NAME = "DrawFunc"

    // -----------------------------------------------------------------------
    // ① 复制文字
    // -----------------------------------------------------------------------

    /** 把 [report] 放进剪贴板。返回是否成功（手表上个别 ROM 会禁剪贴板）。 */
    fun copyText(context: Context, report: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DrawFunc 崩溃日志", report))
        true
    }.getOrElse { false }

    // -----------------------------------------------------------------------
    // ② 存日志到应用内部
    // -----------------------------------------------------------------------

    /**
     * 写一份**带时间戳**的副本到 `filesDir/crash_<时间>.txt`。
     *
     * 为什么不直接覆盖 `last_crash.txt`：那是"有没有待看的崩溃"的状态位，
     * 被清掉就该没了。这里存的是**归档**，用户点几次就有几份，互不干扰。
     *
     * 顺带在外部应用目录也留一份（`Android/data/<包名>/files`），那一路
     * 文件管理器能直接翻到 —— 手表上没有"下载"这类入口，这是用户真的能找到的地方。
     */
    suspend fun saveText(context: Context, report: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = "crash_${timestamp()}.txt"
                val file = File(context.filesDir, name)
                file.writeText(report)

                // 外部应用目录：尽力而为，写不进去（没挂载/被清理）不算失败，
                // 内部目录那份才是"一定在"的那一份。
                runCatching {
                    val dir = File(
                        context.getExternalFilesDir(null) ?: context.filesDir,
                        DIR_NAME
                    )
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, name).writeText(report)
                }
                file
            }
        }

    // -----------------------------------------------------------------------
    // ③ 存日志图片
    // -----------------------------------------------------------------------

    /** 把报告重排成一张 PNG 存下来。返回写好的文件，失败返回 null。 */
    suspend fun savePng(context: Context, report: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bmp = renderReport(report)
                val name = "crash_${timestamp()}.png"

                // 优先落公共 Pictures（API 29+ 的 MediaStore 不需要权限），
                // 再退回应用外部目录 —— 两条都失败才算失败。
                writeToPictures(context, name, bmp)?.let { return@runCatching it }

                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        ?: context.filesDir,
                    DIR_NAME
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                file
            }
        }

    /**
     * 报告 → 位图。
     *
     * 版面就是"一屏的截图"该有的样子：深色底、等宽字、左对齐、
     * 顶部一条红字标题。宽度固定 [IMAGE_WIDTH]，高度按行数算出来（长的栈不截断）。
     */
    private fun renderReport(report: String): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xDD, 0xDD, 0xDD)
            textSize = TEXT_SIZE
            typeface = Typeface.MONOSPACE
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xFF, 0x8A, 0x80)
            textSize = TEXT_SIZE
            typeface = Typeface.MONOSPACE
        }

        val lines = buildList {
            add("DrawFunc 崩溃日志" to titlePaint)
            add("设备 ${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}"
                to titlePaint)
            add("" to textPaint)
            wrap(report, charsPerLine()).forEach { add(it to textPaint) }
        }

        val lineHeight = (TEXT_SIZE * LINE_SPACING).toInt()
        val height = (PADDING * 2 + lineHeight * lines.size).coerceAtLeast(lineHeight)
        val bmp = Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0x1A, 0x1A, 0x1A))

        var y = (PADDING + TEXT_SIZE).toFloat()
        lines.forEach { (text, paint) ->
            canvas.drawText(text, PADDING.toFloat(), y, paint)
            y += lineHeight
        }
        return bmp
    }

    /** 图宽固定，能塞多少个等宽字符（粗估，够用：宁可早折一行，也别出界）。 */
    private fun charsPerLine(): Int =
        ((IMAGE_WIDTH - PADDING * 2) / (TEXT_SIZE * CHAR_WIDTH_RATIO)).toInt().coerceAtLeast(16)

    /**
     * 按"显示宽度"折行。
     *
     * 不用 `Paint.breakText` 是因为这里要的是**等宽假设下的稳定版面**：
     * 同一个字符永远占同样的格子，图和屏幕上看到的行号才对得上。
     * 中日韩字符按 2 格算（等宽字体里它们确实是两倍宽）。
     */
    private fun wrap(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            if (raw.isEmpty()) {
                out += ""
                continue
            }
            var start = 0
            var width = 0
            var i = 0
            while (i < raw.length) {
                val w = if (raw[i].code > 0x2E80) 2 else 1
                if (width + w > limit && i > start) {
                    out += raw.substring(start, i)
                    start = i
                    width = 0
                }
                width += w
                i++
            }
            if (start < raw.length) out += raw.substring(start)
        }
        return out
    }

    /**
     * API 29+ 走 `MediaStore.Images` 写进公共 `Pictures/DrawFunc`（无需权限）。
     *
     * 任何一步失败都返回 null，由调用方退回应用目录 —— 这条路上"没有失败提示"，
     * 因为两张落地位置对用户是等价的（都是文件管理器里能翻到的地方）。
     */
    private fun writeToPictures(context: Context, name: String, bmp: Bitmap): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DIR_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } != null
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                return@runCatching null
            }
            if (!ok) return@runCatching null
            runCatching {
                resolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            // MediaStore 的 Uri 不是 File —— 回一个可读路径名，仅用于提示文案
            File("Pictures/$DIR_NAME/$name")
        }.getOrNull()
    }

    /** `20240102_1530` —— 手表上短、排序天然正确。 */
    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 常量集中放在末尾，方便对照着调（数值调过一轮，图上不再挤成一团）。 */
    private const val IMAGE_WIDTH = 1080
    private const val PADDING = 36
    private const val TEXT_SIZE = 26f
    private const val LINE_SPACING = 1.35f
    private const val CHAR_WIDTH_RATIO = 0.6f
}

/** 一句 toast：不管结果，先让用户知道"点了有反应"。 */
internal fun toast(context: Context, text: String) {
    runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
