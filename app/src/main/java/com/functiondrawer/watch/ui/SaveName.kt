package com.functiondrawer.watch.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存图像时的文件名规则（用户需求 #8）。
 *
 * 故意放在一个**不依赖 Compose/Android** 的文件里：它是纯字符串逻辑，
 * 但边界情况比看上去多（空名字、只有点、重复的 `.png`、路径分隔符、
 * 控制字符、超长名字…），用 `tools/run-tool-tests.ps1` 离线钉住最划算 ——
 * 这条链一旦出问题（比如名字里带 `/`），存盘会直接失败或者写到别的目录去。
 */

/** 存盘目录里给用户看的相对说明（提示框上会显示，便于用户找文件）。 */
const val SAVE_DIR_HINT = "Android/data/com.functiondrawer.watch/files/Pictures"

/** 文件名里不能出现的字符：Android/Linux 只禁 `/` 和 NUL，但这些在别的系统/工具里都会出问题。 */
private val ILLEGAL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

/** 单个文件名（不含目录）的最大长度：留出 `.png` 与去重后缀的余量。 */
private const val MAX_BASE_LENGTH = 64

/**
 * 文件名清洗 + 补扩展名。
 *
 * 规则：
 *   * 去掉首尾空白；
 *   * 非法字符与控制字符换成 `_`；
 *   * 首尾的 `.`、空格、`_` 去掉 —— 以 `.` 开头的文件在 Linux 上是隐藏文件，
 *     用户会"存了但找不到"；以 `.` 结尾在 Windows 上会被系统吃掉；
 *   * 空名字（或全是非法字符）→ 用时间戳 `20261003-231500`，**不报错**：
 *     长按 + 就是想存图，不该因为没起名字就失败；
 *   * 超过 [MAX_BASE_LENGTH] 截断（保留开头，用户通常把有意义的词写在前面）；
 *   * 不以 `.png` 结尾就补上 —— 用户界面是**隐藏扩展名**的，
 *     所以用户可能输入 `我的曲线.png` 也可能只输 `我的曲线`，两种都要对。
 */
fun sanitizeFileName(rawName: String): String {
    val cleaned = rawName.trim()
        .map { if (it in ILLEGAL_CHARS || it.isISOControl()) '_' else it }
        .joinToString("")
        .trim('.', ' ', '_')
        .take(MAX_BASE_LENGTH)
        // 截断之后可能又留下首尾的点/空格（比如刚好截在 `.` 前面），再清一次
        .trim('.', ' ', '_')
    val base = cleaned.ifBlank { timestampName() }
    return if (base.endsWith(".png", ignoreCase = true)) base else "$base.png"
}

/** 没起名字时的兜底名字：本地时间的 `yyyyMMdd-HHmmss`。 */
internal fun timestampName(now: Date = Date()): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
