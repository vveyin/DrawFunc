package com.functiondrawer.watch.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「把一份函数文件导出到 **下载/DrawFunc**」的**落盘那一半**（Android 侧）。
 *
 * 决策（名字、撞名、失败文案）全在 [FileExport]；这里只负责和系统打交道：
 * **查一下目标文件夹里有什么、插一行、写字节、收尾**。
 * 这么分是因为这个文件 import 了 `android.*`，**编不进** `tools\run-tool-tests.ps1`
 * 的离线脚本（那条 classpath 里没有 android.jar），而上面那几条规则是最该被离线钉住的。
 *
 * ## API 等级：两分支，**只有 ≥ 29 这一支真的实现**
 *
 * 目标设备是 **Android 11（API 30）**，分区存储（scoped storage）生效：
 *
 *   * **API 29+**：走 `MediaStore.Downloads.EXTERNAL_CONTENT_URI` + `ContentValues`。
 *     一条记录里的关键四格：
 *     | 字段 | 值 | 为什么 |
 *     |---|---|---|
 *     | `DISPLAY_NAME` | `名字.fdf` | 用户看到的名字（含扩展名） |
 *     | `MIME_TYPE` | `application/octet-stream` | `.fdf` 是我们的自定义文本格式，报 `text/plain` 会让别的 App 拿文本编辑器打开它；"未知二进制"最老实 |
 *     | `RELATIVE_PATH` | `Download/DrawFunc` | **DrawFunc 这一层由 MediaStore 自动创建**（这就是用户要的"没有就自动创建"），不用自己 `mkdirs` |
 *     | `IS_PENDING` | 插入时 `1`、写完 `0` | 写一半的文件对别的 App / 文件管理器**不可见**。不这么干的话，图上那种"几百字节"的文件也可能被媒体扫描器逮到半个 |
 *     **不需要任何权限**：App 写自己插进 MediaStore 的那一行（`Download/` 下的 Downloads 集合对
 *     分区存储是"App 可自由写"的），所以 `WRITE_EXTERNAL_STORAGE` 一个都不用声明 ——
 *     这条很要紧：这块表的系统权限框一弹就是整屏，而且 API 30 上那个权限本来也已经不生效了。
 *
 *   * **API 24~28（minSdk 24）**：`MediaStore.Downloads` **不存在**（它是 API 29 才加的），
 *     写公共下载目录只能退回 `Environment.getExternalStoragePublicDirectory()` +
 *     `WRITE_EXTERNAL_STORAGE` **运行时权限**。这里**有意不做**这一支，而是给一句明确的
 *     失败提示（[ExportProblem.UNSUPPORTED_VERSION]），理由三条：
 *       1. 那会往 `AndroidManifest.xml` 里加**唯一一个**权限声明，而"这个 App 一个权限都没申请"
 *          是仓库里写死的性质（`ImageExport.kt` 就是为了躲它才存到 `Android/data/...`），
 *          为一个少数机型才走到的分支破掉它不划算；
 *       2. 真要做得配一整套运行时权限流程（申请 → 用户可能拒绝 → 拒绝后给提示 → 去设置页），
 *          而"拒绝"这条路在手表上的体验很差，且**没有任何设备能验**；
 *       3. 主设备是 API 30，这一支只在"老手机/老表"上出现 —— 那种场合用户把文件拷出来
 *          本来就更常用 `adb pull`。
 *     代价写清楚：**API 29 以下的设备上这颗"导出文件"按钮点了只会得到那句提示**，
 *     不会导出。以后真要补这一支，[DownloadExport.export] 里加一个
 *     `else -> legacyExport(...)` 分支即可（`planExport` 那一套名字规则原样能复用）。
 *
 * ## 导出是"复制"，不是"搬走"
 *
 * 源文件（App 私有目录 `filesDir/saves/xxx.fdf`）只**读**：[File.readBytes] 读出来的是
 * **原始字节**（不是 `readText` 再写回 —— 那会经过一次编解码，BOM/行尾有可能被顺手改掉），
 * 目标文件是下载目录里的**新**文件。所以"源文件一个字都不能动"这条是结构上保证的：
 * 这条链路上唯一写盘的地方是 `openOutputStream`，它只碰 MediaStore 给的那一条 Uri。
 *
 * ## 重名：先列一遍，再加序号（不覆盖）
 *
 * 插之前先查一下目标文件夹里已有的 `DISPLAY_NAME`（[existingFileNames]），
 * 交给 [uniqueFileName] 加 ` (2)` 序号。**为什么还要在成功之后回查一次真实名字**：
 * 没有 `READ_EXTERNAL_STORAGE` 时，查询只看得见"本 App 插进去的那些行"，
 * 用户从别处拷进来的同名文件我们看不见 —— 那种情况下 `MediaStore` 自己会给它改个名字，
 * 所以成功提示里的名字以**它回给我们的**为准（[actualFileName]），不能想当然。
 */
object DownloadExport {

    /**
     * MIME：未知二进制。
     *
     * 不用 `text/plain` 是因为 `.fdf` 是**这个 App 自己的格式**：报成纯文本的话，
     * 用户在文件管理器里点它会跳到某个文本编辑器，看到一个 `F|1|E|...` 的行式内容，
     * 那比"没有应用能打开它"更让人困惑。
     */
    private const val MIME_TYPE = "application/octet-stream"

    /**
     * 把 [source] 这份函数文件导出到 `Download/DrawFunc/`。
     *
     * 挂起函数、**整段跑在 IO 线程**（`withContext(Dispatchers.IO)`）：这里面有查询、
     * 插入、几百字节的写和一次回查，都在主线程上是不能碰的。
     *
     * @param draftName 重命名浮窗输入框里**此刻**的文本（用户可能刚改完还没按 ✓）；
     *                  空的话退回 [originalBaseName]（规则见 [planExport]）。
     * @return [ExportOutcome]：**成功和失败都带一句能给用户看的话**，调用方只管把它丢给 toast。
     */
    suspend fun export(
        context: Context,
        source: File,
        draftName: String,
        originalBaseName: String
    ): ExportOutcome = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // 这一支不再往下走（见类注释：有意不做 <29 的旧路，给一句看得懂的提示）
            return@withContext ExportOutcome.Failure(
                exportProblemText(ExportProblem.UNSUPPORTED_VERSION)
            )
        }
        // 源文件只读；读不出来（被删了/被清理工具清了）就说清楚是"源文件"的问题，
        // 而不是笼统的"导出失败"
        val bytes = runCatching { source.readBytes() }.getOrNull()
            ?: return@withContext ExportOutcome.Failure(
                exportProblemText(ExportProblem.SOURCE_UNREADABLE)
            )

        when (val plan = planExport(draftName, originalBaseName, existingFileNames(context))) {
            is ExportPlan.Rejected -> ExportOutcome.Failure(plan.message)
            is ExportPlan.Ready -> writeToDownloads(context, plan.fileName, bytes)
        }
    }

    // -----------------------------------------------------------------------
    // 内部：查询 / 写入 / 回查
    // -----------------------------------------------------------------------

    /**
     * 目标文件夹里**已有的文件名**（带扩展名），给 [uniqueFileName] 判重名用。
     *
     * `RELATIVE_PATH` 在库里是**带结尾斜杠**的形式（`Download/DrawFunc/`），而插入时两种
     * 写法系统都收 —— 所以这里两种都比一遍，免得漏掉老的行。
     *
     * 整段兜在 `runCatching` 里：**一次查询失败不该把导出整个挡掉**。
     * 查不到就当"文件夹是空的"，插进去之后 `MediaStore` 自己还有一道去重，
     * 而成功提示用的是回查到的真实名字，所以最坏情况也只是序号从 2 开始数而已。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun existingFileNames(context: Context): Set<String> {
        val names = HashSet<String>()
        runCatching {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePath = EXPORT_RELATIVE_PATH
            val selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            val args = arrayOf(relativePath, "$relativePath/")
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (column >= 0) cursor.getString(column)?.let { names += it }
                }
            }
        }
        return names
    }

    /**
     * 真的写：插一行（`IS_PENDING = 1`）→ 写字节 → 把 `IS_PENDING` 放回 `0`。
     *
     * 失败时**把那一行删掉**：留下一个 pending 的行会在库里积垃圾，而用户看到的
     * 是一个"不存在但又占着名字"的文件。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(context: Context, fileName: String, bytes: ByteArray): ExportOutcome {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            // 子目录（DrawFunc）由 MediaStore 自己建 —— 用户要的"没有就自动创建"就是这一格
            put(MediaStore.MediaColumns.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            // 写完之前别的 App 看不见它（见类注释那张表）
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        // 插入失败基本只有两种可能：目标目录建不出来（外部存储没挂载 / 被卸载），
        // 或者 ROM 把 Downloads 集合管得更严（抛 SecurityException）
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return ExportOutcome.Failure(exportProblemText(ExportProblem.IO_ERROR))

        val failure = runCatching {
            val out = resolver.openOutputStream(uri) ?: error("openOutputStream 返回了 null")
            out.use { it.write(bytes) }
        }.exceptionOrNull()

        if (failure != null) {
            // 半截文件不留（删掉那一行，文件也会跟着没）
            runCatching { resolver.delete(uri, null, null) }
            val problem = if (isOutOfSpace(failure)) ExportProblem.NO_SPACE else ExportProblem.IO_ERROR
            return ExportOutcome.Failure(exportProblemText(problem))
        }

        // 收尾：IS_PENDING = 0。这一步之前，用户在文件管理器里是**看不到**这个文件的
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }

        return ExportOutcome.Success(actualFileName(context, uri) ?: fileName)
    }

    /**
     * 回查这一行**真实**的 `DISPLAY_NAME`（`MediaStore` 可能自己去过重，见类注释）。
     *
     * 查不到就返回 null，调用方退回我们本来要的那个名字 —— 宁可提示里的名字差一点，
     * 也不能因为一次回查失败就把"其实写成功了"报成失败。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun actualFileName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * 这个异常是不是"盘满了"。
     *
     * 没有干净的 API 能问出这件事（`StorageManager.getAllocatableBytes` 要另一套权限、
     * 而且算出来的是"可回收多少"），所以按 `IOException` 的 message 认那两个惯用写法
     * （Linux 的 `ENOSPC` 和它的英文描述）。**认不出来就报"写不进下载目录"** ——
     * 少一个精确原因，也绝不能把原因说错。
     */
    private fun isOutOfSpace(failure: Throwable): Boolean {
        val text = failure.message ?: return false
        return text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space left", ignoreCase = true)
    }
}
