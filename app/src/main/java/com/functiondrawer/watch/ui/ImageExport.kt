// 抓取 / 保存图像要用到 Compose 的实验性图层 API，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「把当前图像存成文件」（用户需求 #8）。
 *
 * 做法是 Compose 1.7 的 [GraphicsLayer]：把图像界面那一层的内容**录制**进一个图层，
 * 需要的时候再 `toImageBitmap()` 取出来存盘。
 *
 * 为什么不自己重画一遍：重画要么把 `GraphCanvas` 内部那些私有绘制函数搬出来
 * （容易和真机渲染的观感分叉），要么复制一份（迟早不一致）。
 * 录制的是**屏幕上真实画出来的那层**，存下来的和看到的一模一样。
 *
 * 用法（调用方三步）：
 * ```
 * val capture = rememberGraphCapture()
 * Box(modifier = capture.modifier) { ...图像界面... }        // ① 给要抓的那层挂上
 * ...
 * scope.launch { val f = capture.save(context, "我的曲线") }   // ② 存盘（挂起）
 * ```
 *
 * 存到**应用自己的外部目录**（`Android/data/…/files/Pictures`）：
 * API 30 起写公共 `Pictures` 目录需要 `WRITE_EXTERNAL_STORAGE`，
 * 而这个 App 一个权限都没申请（手表上弹权限框很打扰），
 * 应用目录不需要任何权限、用文件管理器/ADB 都能取出来。
 */
class GraphCapture internal constructor(private val layer: GraphicsLayer) {

    /**
     * 挂到要抓取的那一层上。
     *
     * `drawWithContent` 把这一层的内容同时画到屏幕和图层里 ——
     * 也就是说它**不影响显示**，只是顺手录一份。
     *
     * ## ⚠️ 3.41：`layer.record` 把「读到的状态」也一起缓存了
     *
     * `record` 记录的是**内容本身在录制那一刻的求值结果**。所以如果被录的那一层
     * 之后没重绘（比如用户在设置页里拨了滑条，底下的画布纹丝不动），
     * 图层里就仍是旧画面 —— 取出来的位图自然也是旧的。
     *
     * 这就是"设置里改模糊程度不重绘"最后剩下的那一环：光让 `BackdropImage` 重新请求
     * **没用**，因为源头（这一层）根本没刷新。修法见 [version]：每次要取图前
     * 把版本号赶一格，让这一层的 `drawWithContent` 在下一帧必然重跑一遍。
     */
    val modifier: Modifier
        get() = Modifier
            // 读一下版本号：它变 → 这个 draw 节点变脏 → 下一帧重绘 → 图层刷新。
            .drawWithContent {
                if (version >= Int.MIN_VALUE) {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
            }

    /**
     * 录制版本号（3.41）。
     *
     * `drawWithContent` 的 lambda 在**绘制阶段**执行，里面读到的任何 state
     * 都会被 Compose 记为"这个绘制节点的依赖"。值一变，节点失效、下一帧重绘。
     *
     * 只增不减，永远不会回到同一个值 —— 这是它能当"手动失效开关"用的前提。
     */
    var version by mutableStateOf(0)
        private set

    /** 让这一层下一帧重绘一次（从而把最新内容录进 [layer]）。 */
    fun invalidate() {
        version++
    }

    /** 取当前内容的位图（挂起：图层是异步资源）。 */
    suspend fun toImageBitmap(): ImageBitmap = layer.toImageBitmap()

    /**
     * 存成 PNG。
     *
     * [rawName] 是用户在提示框里输入的名字 —— 用户要求输入框**隐藏扩展名**，
     * 所以这里负责补 `.png`；名字里的路径分隔符/非法字符一律替换成 `_`，
     * 空白或全非法则退回时间戳（不弹错、不覆盖别的文件）。
     *
     * @return 写好的文件；失败（画布还没内容、磁盘满、IO 错）返回 null。
     */
    suspend fun savePng(context: Context, rawName: String): File? {
        val bitmap = runCatching { toImageBitmap().asAndroidBitmap() }.getOrNull() ?: return null
        if (bitmap.width <= 1 || bitmap.height <= 1) return null
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, sanitizeFileName(rawName))
        return runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // 用户 1.3.11："截图也保存到 download\DrawFunc 里。" —— 除了上面这份
            // 应用私有目录，再往 **Download/DrawFunc/** 写一份（MediaStore，无需权限）。
            // 这一步失败不影响主流程：私有目录那份已经写好了，用户总能从文件管理器拿到。
            exportPngToDownloads(context, bitmap, file.name)
            file
        }.getOrNull()
    }
}

/**
 * 把同一张 PNG 再写一份到 `Download/DrawFunc/`（1.3.11）。
 *
 * 和 [DownloadExport] 写 `.fdf` 用的是同一条路：`MediaStore.Downloads` + `IS_PENDING`，
 * 目录由系统自动创建、不需要任何权限。API 29 以下没有 `MediaStore.Downloads`，
 * 这里**有意**什么都不做（和 `.fdf` 导出同一套取舍，见 `DownloadExport` 的类注释）。
 *
 * @return 写成功时返回文件名，否则 null（调用方可以完全不看它）。
 */
@RequiresApi(Build.VERSION_CODES.Q)
private fun exportPngToDownloads(context: Context, bitmap: Bitmap, fileName: String): String? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        // 子目录 DrawFunc 由 MediaStore 自己建（和 .fdf 导出同一个常量）
        put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = runCatching {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return null

    val written = runCatching {
        val out = resolver.openOutputStream(uri) ?: error("openOutputStream 返回了 null")
        out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.getOrDefault(false)

    if (!written) {
        // 半截文件不留（删掉那一行，文件也会跟着没）
        runCatching { resolver.delete(uri, null, null) }
        return null
    }

    // 收尾：IS_PENDING = 0，这之前别的 App 看不见这个文件
    runCatching {
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
    }
    return fileName
}

@Composable
fun rememberGraphCapture(): GraphCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GraphCapture(layer) }
}

// SAVE_DIR_HINT 与文件名清洗都在 SaveName.kt（纯字符串逻辑、可离线单测）。

// 文件名清洗 / 存盘目录提示 / 时间戳兜底名字都搬到了 `SaveName.kt`：
// 那几件是纯字符串逻辑，拆出去之后就能用 tools/run-tool-tests.ps1 离线单测
// （放在这个文件里会因为 Compose 依赖而编不进离线脚本）。
