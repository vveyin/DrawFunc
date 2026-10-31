package com.functiondrawer.watch.ui

import android.content.Context
import java.io.File

/**
 * 函数文件在应用**内部存储**里的相对位置。
 *
 * 用户明确要求"在应用内部存储空间创建一个 saves 文件夹专门保存文件"，
 * 所以真正的目录是 `context.filesDir/saves/`（`/data/data/com.functiondrawer.watch/files/saves`）。
 *
 * 为什么不用外部存储（`ImageExport.kt` 存 PNG 用的是外部目录）：
 *   * 内部存储**不需要任何权限**、不会被别的 App 或用户随手清掉（清理类工具最爱删
 *     `Android/data` 下的东西），而这里的文件是用户的**全部工作**，比一张导出的图贵得多；
 *   * 缺点是从文件管理器里够不着 —— 所以命名对话框底部写着这行小字指路（用户要"隐藏扩展名"，
 *     但我们没有义务隐藏位置）；真要取出来，`adb pull` 走一次就行。
 */
const val SAVES_DIR_HINT = "files/saves"

/**
 * 「函数文件」的磁盘读写。
 *
 * 这里只做**文件系统**的事（列出 / 写入 / 读出 / 改名 / 删除），
 * 内容 ⇄ 状态的转换在 [FunctionFileFormat]（纯 Kotlin、离线可测），
 * 装进应用状态在 [GraphState.snapshotForFile] / [GraphState.loadFileState]。
 * 这么分是因为"格式对不对"必须能离线钉住，而"文件能不能写进去"只能在设备上验。
 *
 * **所有方法都不抛异常**：出错一律返回 `null` / `false` / 空表，
 * 由界面层翻译成"无法识别的用户提示"（手表上没有崩溃对话框，
 * 一个异常直接就是闪退，而这里全是"磁盘满/被占用"这类必然会遇到的情况）。
 */
object FunctionFileStore {

    /**
     * 教程期间被「名义删除」的文件（绝对路径）。
     *
     * 第 31 步要让用户真的按住垃圾桶把刚存的 `test` 删掉，可教程中途退出时
     * 那个文件还得**原样还回来** —— 真删盘就还不回来了。所以教程期间只把它
     * 从列表里拿掉（[list] 跳过这些路径），盘上的文件一直留着：
     *   * 走完全程 → [dropStash] 这时才真删；
     *   * 中途退出 → [unstash] 直接放回列表。
     *
     * 只在内存里，进程重启自然清空（那时磁盘上的文件本来就是完整的）。
     */
    private val stashedPaths: MutableSet<String> = mutableSetOf()

    /** 列表里的一行。`summary` 是从文件内容里数出来的（列表第二行的小字用它）。 */
    data class Entry(
        val file: File,
        val baseName: String,
        val bytes: Long,
        val modified: Long,
        val summary: FunctionFileFormat.Summary
    )

    /** 把所有文件都真的删掉（教程走完全程时调一次）。 */
    fun dropStash() {
        for (path in stashedPaths) delete(File(path))
        stashedPaths.clear()
    }

    /** 当前有没有「名义删除」还挂着的文件。 */
    fun hasStash(): Boolean = stashedPaths.isNotEmpty()

    /** 名义删除：只从列表里拿掉，不动磁盘。 */
    fun stash(files: Collection<File>) {
        files.forEach { stashedPaths += it.absolutePath }
    }

    /** 撤销名义删除：文件原样回到列表。 */
    fun unstash() {
        stashedPaths.clear()
    }

    /** 保存目录（不保证存在；写入时会创建）。 */
    fun dir(context: Context): File =
        File(context.filesDir, FunctionFileFormat.SAVES_DIR)

    /**
     * 列出所有函数文件，**按修改时间倒序**（用户要求：最近动的排最上面）。
     *
     * 只认 `.fdf`：用户也可能把别的东西丢进这个目录（或者改名对话框写坏了），
     * 列出来点开却打不开，不如一开始就不显示。
     */
    fun list(context: Context): List<Entry> {
        val files = runCatching {
            dir(context).listFiles { f -> f.isFile && f.name.endsWith(FunctionFileFormat.EXTENSION) }
        }.getOrNull() ?: return emptyList()

        return files
            // 教程里"名义删除"的那些不列出来（见 [stashedPaths]）
            .filter { it.absolutePath !in stashedPaths }
            .mapNotNull { f ->
                runCatching {
                    val text = f.readText(Charsets.UTF_8)
                    Entry(
                        file = f,
                        baseName = FunctionFileFormat.displayName(f.name),
                        bytes = f.length(),
                        modified = f.lastModified(),
                        summary = FunctionFileFormat.summarize(text)
                    )
                }.getOrNull()
            }.sortedByDescending { it.modified }
    }

    /** 读出一个文件的正文。读不到（不存在 / 没权限 / 编码坏）返回 null。 */
    fun read(file: File): String? =
        runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()

    /**
     * 写入一个函数文件。
     *
     * 先写 `.tmp` 再改名：手表随时可能被系统掐掉进程，直接覆写有概率留下
     * **半个文件**（原来的好文件也没了）。改名在同一目录内是原子操作，
     * 所以任何时刻磁盘上要么是旧的完整文件、要么是新的完整文件。
     *
     * 名字不合法（空 / 非法字符 / 超长 / 以点开头）**一律不写盘**：界面层在对话框里
     * 已经拦过一次并给了红字提示，这里用**同一条纯规则**再兜一层 ——
     * 规则只有 `FunctionFileFormat.baseNameError` 一份、而且有离线测试，
     * 免得"界面拦的和这里认的"哪一天不一致，悄悄落下一个 `.fdf` 这种畸形文件。
     *
     * @return 写好的文件；失败返回 null。
     */
    fun write(context: Context, baseName: String, text: String): File? {
        if (FunctionFileFormat.baseNameError(baseName, emptySet()) != null) return null
        val name = FunctionFileFormat.fileNameFor(baseName)
        val d = dir(context)
        return runCatching {
            if (!d.isDirectory && !d.mkdirs()) return null
            val target = File(d, name)
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        }.getOrNull()
    }

    /**
     * 改名（只改基名，扩展名不变）。
     *
     * @return 改名后的文件；目标已存在或改名失败返回 null（界面层会提示"已有同名文件"）。
     */
    fun rename(file: File, newBaseName: String): File? {
        val target = File(file.parentFile, FunctionFileFormat.fileNameFor(newBaseName))
        if (target.absolutePath == file.absolutePath) return file
        if (target.exists()) return null
        return runCatching { if (file.renameTo(target)) target else null }.getOrNull()
    }

    /** 删除。删不掉（不存在也算成功）返回 false。 */
    fun delete(file: File): Boolean =
        runCatching { !file.exists() || file.delete() }.getOrDefault(false)

    /**
     * 已占用的名字（不含扩展名），给重名判定用。
     *
     * [except] 是"正在被改名的那个文件"：它自己当然不能算重名，
     * 否则 `sin` → `sin` 这种"原地改个大小写"会被自己的名字拦下来。
     */
    fun takenBaseNames(context: Context, except: File? = null): Set<String> {
        val exceptPath = except?.absolutePath
        return list(context)
            .filter { it.file.absolutePath != exceptPath }
            .map { it.baseName }
            .toSet()
    }
}
