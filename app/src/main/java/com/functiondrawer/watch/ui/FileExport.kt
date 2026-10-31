package com.functiondrawer.watch.ui

/**
 * 「把一份函数文件（`.fdf`）导出到**下载目录下的 DrawFunc 文件夹**」的**纯逻辑**。
 *
 * 用户原话："再给文件列表里面的重命名浮窗里面再加个导出文件的按钮，点击之后会在下载文件夹
 * 里面的 DrawFunc 文件夹里面导出这个文件，如果没有 DrawFunc 文件夹自动创建。"
 *
 * ## 为什么"决策"和"落盘"分成两个文件
 *
 * 这个文件里**一个 Android 类都不 import**（只用到同包的 [FunctionFileFormat]，那个文件
 * 本身也是纯 Kotlin）：
 *
 *   * 真正的落盘（`MediaStore` / `ContentResolver`）在 [DownloadExport]；
 *   * 于是"导出叫什么名字 / 撞名怎么办 / 失败给用户看哪句话"这几条**能被离线钉住**
 *     （`app\src\test\java\com\functiondrawer\watch\tools\FileExportTest.kt`）。
 *
 * 这条界线不是洁癖：`tools\run-tool-tests.ps1` 拼的 classpath 里**只有
 * kotlin-stdlib + junit**，没有 `android.jar` —— 一个文件只要 import 了
 * `android.*` 就再也编不进那个脚本。同名文件在别处也踩过同一个坑
 * （`SaveName.kt` / `PlotMetrics.kt` 都是这么拆出来的）。
 *
 * ## 落点：`Download/DrawFunc`
 *
 * [EXPORT_RELATIVE_PATH] 就是它。`DrawFunc` 这一层**不用我们自己 mkdir**：
 * API 29+ 走 `MediaStore` 时，`RELATIVE_PATH` 里带着子目录，系统会把缺的目录建出来 ——
 * 这正是用户要的"没有这个文件夹就自动创建"。
 *
 * ## 名字怎么定（三条规则）
 *
 *  1. **优先用输入框里"当前"那段文本**（用户可能刚改完名字还没按 ✓）：导出是"我现在看到的
 *     这个名字"的备份/分享，用磁盘上的原名反而会让人以为导出的是旧的；
 *  2. **输入框是空的就退回原名**（不是报错）："清空输入框"在手表上更可能表示"我还没想好新名字"
 *     （系统输入法打字慢），这时让导出失败没有任何好处；
 *  3. 名字**非法就明确拒绝**（空/带 `/ \ : * ? " < > |`/太长/以点开头），提示语直接复用
 *     [FunctionFileFormat.baseNameError] —— 和重命名对话框里那一行红字**是同一句话**，
 *     不会出现"改名不让用、导出却让用"这种自相矛盾。
 *
 * 扩展名统一补 `.fdf`（和 App 内部一致）：输入框是**隐藏扩展名**的，用户也可能自己打
 * `我的曲线.fdf`，两种都由 [FunctionFileFormat.fileNameFor] / `normalizeBaseName` 处理。
 *
 * ## 目标文件夹里撞名：**自动加序号，不覆盖**
 *
 * `名字.fdf` 已存在 → 写 `名字 (2).fdf`，再撞就 `(3)`…
 * 理由是导出的语义（备份 / 拿去分享）：**覆盖是破坏性的**，而用户按这颗键时脑子里想的
 * 绝不是"把上次那份替换掉"；相反，多出一个 ` (2)` 一眼就看得懂，还能自己删。
 * （项目里"删除"那条路之所以敢直接删，是因为它有一条 4 秒的撤销条兜着；导出没有撤销，
 * 所以宁可多一个文件。）
 */
// ---------------------------------------------------------------------------
// 1. 落点
// ---------------------------------------------------------------------------

/**
 * 下载目录的名字。
 *
 * 字面量就是 `android.os.Environment.DIRECTORY_DOWNLOADS` 的值（`"Download"`）——
 * 这里写死是为了让这个文件零 Android 依赖（见类注释）。它在 AOSP 里从 API 1 就是这个值。
 */
const val DOWNLOAD_DIR_NAME = "Download"

/** 下载目录下专门放导出文件的文件夹名（**用户点名**要 `DrawFunc`）。 */
const val EXPORT_DIR_NAME = "DrawFunc"

/**
 * MediaStore 的 `RELATIVE_PATH`，也是提示里给用户看的路径：`Download/DrawFunc`。
 *
 * **不带结尾斜杠**：`MediaStore` 插入时两种写法都收（它自己会在库里存成带斜杠的形式），
 * 而提示语里不带斜杠更像用户要说的话。
 */
const val EXPORT_RELATIVE_PATH: String = DOWNLOAD_DIR_NAME + "/" + EXPORT_DIR_NAME

// ---------------------------------------------------------------------------
// 2. 结果（成功 / 失败都带一句"直接能给用户看的话"）
// ---------------------------------------------------------------------------

/**
 * 导出失败的原因。
 *
 * 为什么是**枚举**而不是让每处各写一句字符串：文案只有一份、离线能钉住，
 * 也不会出现"同一个原因在 toast 和对话框里说法不一样"。
 */
enum class ExportProblem {
    /** 系统 < Android 10（API 29）：没有 `MediaStore.Downloads`，见 [DownloadExport] 的说明。 */
    UNSUPPORTED_VERSION,

    /** 盘满了（`ENOSPC`）。 */
    NO_SPACE,

    /** 其它写盘失败（没权限 / 被占用 / 目标目录建不出来）。 */
    IO_ERROR,

    /** 源文件（App 私有目录里那份 `.fdf`）读不出来。 */
    SOURCE_UNREADABLE
}

/** [ExportProblem] → 给用户看的一句话（都带"导出失败："前缀，一眼知道是哪件事失败了）。 */
fun exportProblemText(problem: ExportProblem): String = when (problem) {
    ExportProblem.UNSUPPORTED_VERSION ->
        "导出失败：当前安卓版本不支持导出到下载目录（需要 Android 10 及以上）"

    ExportProblem.NO_SPACE -> "导出失败：存储空间不足"
    ExportProblem.IO_ERROR -> "导出失败：写不进下载目录"
    ExportProblem.SOURCE_UNREADABLE -> "导出失败：源文件读不出来"
}

/**
 * 一次导出的结果。
 *
 * [message] 是**直接丢给 toast 的那句话**（成功失败都有）：用户点一下按钮立刻有反馈，
 * 这也是"手表上没有别的反馈渠道"这件事实要求的 —— 浮窗不关，提示只有这一句话。
 */
sealed interface ExportOutcome {

    /** 给用户看的一句话。 */
    val message: String

    /**
     * 成功。
     *
     * [fileName] 是**最终真的落盘的那个名字**，不一定等于我们要的那个：
     * `MediaStore` 在没有读外部存储权限时只看得见"本 App 导出的行"，别的来源的同名文件
     * 它可能自己改成 ` (1)`；所以成功那一侧要以它回给我们的名字为准（见 [DownloadExport]）。
     */
    data class Success(val fileName: String) : ExportOutcome {
        override val message: String get() = "已导出到 $EXPORT_RELATIVE_PATH/$fileName"
    }

    /** 失败。[message] 见 [exportProblemText] / [planExport]。 */
    data class Failure(override val message: String) : ExportOutcome
}

// ---------------------------------------------------------------------------
// 3. 纯决策：要导出成什么名字
// ---------------------------------------------------------------------------

/** [planExport] 的结果。 */
sealed interface ExportPlan {
    /** 名字已经定好了（含重名序号）。 */
    data class Ready(val fileName: String) : ExportPlan

    /** 名字非法 → 明确拒绝，[message] 直接给用户看。 */
    data class Rejected(val message: String) : ExportPlan
}

/**
 * 从"输入框草稿 + 磁盘上的原名 + 目标文件夹里已有的文件名"推出**最终文件名**。
 *
 * @param draft 重命名浮窗输入框里**此刻**的文本（可能还没按 ✓）。
 * @param originalBaseName 这个文件在 App 内部存档里的基名（不带扩展名），兜底用。
 * @param takenFileNames 目标文件夹（`Download/DrawFunc`）里**已有的文件名**（带扩展名）。
 *        允许为空集：拿不到也不会写坏东西 —— `MediaStore` 自己还有一道去重，
 *        而且成功提示用的是它回给我们的真实名字。
 */
fun planExport(
    draft: String,
    originalBaseName: String,
    takenFileNames: Set<String>
): ExportPlan {
    val typed = FunctionFileFormat.normalizeBaseName(draft)
    // 草稿为空 → 用原名（见类注释第 2 条：这不是错误，是"还没想好新名字"）
    val base = typed.ifEmpty { FunctionFileFormat.normalizeBaseName(originalBaseName) }

    // **复用**项目既有的那一条规则（空 / 非法字符 / 太长 / 以点开头），不自己再写一套。
    // `taken` 传空集：App **内部**的同名文件不该拦导出（那是"重命名"这件事的规则），
    // 导出要处理的是**目标文件夹里**的同名 —— 那个不拒绝，改成加序号（见 uniqueFileName）。
    val error = FunctionFileFormat.baseNameError(base, emptySet())
    if (error != null) return ExportPlan.Rejected("导出失败：$error")

    return ExportPlan.Ready(uniqueFileName(FunctionFileFormat.fileNameFor(base), takenFileNames))
}

/** 撞名最多往后数到几号（再多的同名文件属于"目标目录坏掉了"，不值得让导出卡住）。 */
private const val DEDUPE_LIMIT = 999

/**
 * 目标文件夹里已经有 [wantedFileName] 时，往后找一个没被占用的名字：
 * `名字 (2).fdf`、`名字 (3).fdf`…
 *
 * **大小写不敏感**地比（和设备内部存档 `FunctionFileStore.takenBaseNames` 同一个口径）：
 * `Sin.fdf` 和 `sin.fdf` 在 1.5 寸的列表里用户根本分不清，宁可不导出这一对。
 *
 * @return 可以用的文件名（已经带 `.fdf`）。
 */
fun uniqueFileName(wantedFileName: String, takenFileNames: Set<String>): String {
    if (takenFileNames.none { it.equals(wantedFileName, ignoreCase = true) }) return wantedFileName

    // 去掉扩展名再加序号：`sin (2).fdf`，而不是 `sin.fdf (2)`
    val base = FunctionFileFormat.displayName(wantedFileName)
    for (n in 2..DEDUPE_LIMIT) {
        val candidate = FunctionFileFormat.fileNameFor("$base ($n)")
        if (takenFileNames.none { it.equals(candidate, ignoreCase = true) }) return candidate
    }
    // 数到上限还不罢休：把最后一个交出去（`MediaStore` 会给它 ` (1000)` 之类），
    // 总比在这里死循环、或者让用户点了没反应好。
    return FunctionFileFormat.fileNameFor("$base ($DEDUPE_LIMIT)")
}
