// 抓取 / 保存图像要用到 Compose 的实验性图层 API，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
     */
    val modifier: Modifier
        get() = Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
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
            file
        }.getOrNull()
    }
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
