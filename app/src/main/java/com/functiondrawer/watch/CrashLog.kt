package com.functiondrawer.watch

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 启动崩溃的"黑匣子"。
 *
 * ## 为什么需要它
 *
 * 手表上装完点图标直接退回桌面时，**什么都看不到** —— 没有 adb、没有 logcat、
 * 屏幕上一闪而过，用户能反馈的只有"打不开"。这一版就卡在这种反馈上：
 * 从代码里逐行审了两遍都没审出必然崩的点，而每次"改一版再试"要等一轮装机。
 *
 * 所以加这一层：崩溃时把栈写进应用私有目录，**下次启动时把上一次的栈直接画在屏幕上**。
 * 用户截一张图就能把真凶发回来。
 *
 * ## 只做一件事
 *
 * 它**不吞异常**：写完文件照旧交回给系统默认处理器（App 该退还是退）。
 * 吞掉异常会让"崩了"变成"卡住"，比崩更难查。
 *
 * ## 文件位置
 *
 * `filesDir/last_crash.txt` —— 应用私有目录，不需要任何存储权限，
 * `adb shell run-as` 或系统设置里的"清除数据"都能清掉它。
 */
internal object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    /**
     * 装一个默认异常处理器。**在 `Application.onCreate` / `Activity.onCreate` 最早期调用**，
     * 越早越好：连 `setContent` 之前的崩溃也要抓到。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 交回系统：App 该退还是退，我们只负责留下记录。
            previous?.uncaughtException(thread, error)
        }
    }

    /** 上一次崩溃的栈（读不到就返回 null）。读完**不删**，方便反复看。 */
    fun readLast(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (!f.exists()) return@runCatching null
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 用户看过了就清掉，免得下次启动又弹一遍。 */
    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("线程: ${thread.name}")
            pw.println("时间: ${System.currentTimeMillis()}")
            pw.println("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "API ${android.os.Build.VERSION.SDK_INT}")
            error.printStackTrace(pw)
        }
        File(context.filesDir, FILE_NAME).writeText(sw.toString())
    }
}

/**
 * 把上一次的崩溃栈整屏显示出来。
 *
 * 刻意**不用** App 自己的主题/组件：崩溃可能就发生在主题或某个共用组件里，
 * 复用它们有可能一进来又崩 —— 那就白装了这套黑匣子。所以这里只用最原始的
 * `Box` + `Text` + 一个滚动条，颜色写死（不读 `MaterialTheme`）。
 *
 * ## 布局（3.38 起分两段）
 *
 * ```
 * ┌──────────────────────┐
 * │ 上次启动崩了          │  标题（红）
 * │ 栈……（可滚动）        │  正文
 * │                      │
 * ├──────────────────────┤
 * │ [存图] [复制] [存内部] │  ← 固定贴底，不跟着滚
 * │     点空白处继续进入    │
 * └──────────────────────┘
 * ```
 *
 * 按钮**固定在底部**而不是跟在文字后面：栈通常几百行，跟着滚就等于"要翻到底才找得到"。
 * 底部这一条用 `Column(weight(1f))` 撑开正文，所以无论栈多长它都在原位。
 *
 * ## 点屏进入 vs 点按钮
 *
 * 整屏点击仍然是"清记录 + 进 App"，但三个按钮各自的 `clickable` 会把点击吃掉
 * （Compose 里子节点先消费），所以点按钮不会顺手退出这一屏 —— 这正是要的：
 * 用户往往要连点两三个按钮（先存图再复制）。
 */
@Composable
internal fun CrashScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .clickable(
                // 去掉涟漪：这时可能还没有 MaterialTheme 的 indication，
                // 用一个空交互源最稳。
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .padding(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 正文：占满剩余高度，自己滚 ----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "上次启动崩了",
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
                Text(
                    text = report,
                    color = Color(0xFFDDDDDD),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ---- 底部：三个带走日志的按钮 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CrashActionButton("存图片", Modifier.weight(1f)) {
                    scope.launch {
                        val r = CrashLogExport.savePng(context, report)
                        toast(
                            context,
                            r.fold(
                                onSuccess = { "已存图：${it.name}" },
                                onFailure = { "存图失败" }
                            )
                        )
                    }
                }
                CrashActionButton("复制", Modifier.weight(1f)) {
                    val ok = CrashLogExport.copyText(context, report)
                    toast(context, if (ok) "日志已复制" else "复制失败")
                }
                CrashActionButton("存日志", Modifier.weight(1f)) {
                    scope.launch {
                        val r = CrashLogExport.saveText(context, report)
                        toast(
                            context,
                            r.fold(
                                onSuccess = { "已存：${it.name}" },
                                onFailure = { "保存失败" }
                            )
                        )
                    }
                }
            }

            Text(
                text = "点空白处继续进入（并清掉记录）",
                color = Color(0xFF888888),
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

/**
 * 崩溃屏上的按钮。
 *
 * 不用 `Button`：那要 `MaterialTheme`（本屏刻意不依赖它，见 [CrashScreen] 注释），
 * 而手表上圆角小方块本来比一堆 Material 阴影更好看。这里就是"一个能点的框"。
 */
@Composable
private fun CrashActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(Color(0xFF2E2E2E), RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFFE0E0E0),
            fontSize = 9.sp,
            textAlign = TextAlign.Center
        )
    }
}
