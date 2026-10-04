// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 「应用信息」页（本轮新增）—— 第一屏那颗 ⚙ 的**长按**进来的一屏。
//
// 用户原话（逐条都落在这个文件里）：
//
// > "长按之后打开应用信息页面（不要有标题），最上方是应用图标，然后下一排是应用名字
// > （把应用名字改成 DrawFunc），再下一排是应用版本号，再下一排是 Version Code，
// > 然后再空一排，再下一排写"本应用使用 dsh 开发"，再下一排写"开发者 春羽"。
// > 注意动画和打开文件列表一样，注意放置返回键。"
//
// ## 版面：**没有标题栏**，自上而下就是用户念的那七行
//
// ```
//   ┌ ● 返回键（左上角，和其它屏同一处，形状锁在 Common.kt）        ← TOOLBAR_TOP / TOOLBAR_H
//   │
//   │            [ 应用图标 ]                                    ← ICON_TOP / ICON_SIZE
//   │            DrawFunc                                        ← NAME_TOP（fontTitle、纯白）
//   │            版本 1.0                                         ← VER_TOP （fontSmall、80% 白）
//   │            Version Code 1                                   ← +LINE_H
//   │            （空一排）                                       ← 再 +LINE_H（不画任何东西）
//   │            本应用使用 dsh 开发                                ← +LINE_H
//   └            开发者 春羽                                       ← +LINE_H
// ```
//
// **"不要有标题"是照做的**：这一屏没有 "关于" / "应用信息" 之类的标题栏，
// 顶部只有那一颗返回键（用户原话里"注意放置返回键"指的就是它）。
//
// ## 位置 / 宽度分别跟谁走（README 那条长期规则的原文口径）
//
//   * **位置**（每一行的 y）→ `geometry.sy(占**真实屏高**的比例)`。
//     圆表上 `screenH == sizeDp`，所以这些数就是"233dp 的百分之几"；
//     手机上 `screenH` 是 850dp 那一整块，整块内容因此均匀铺满全屏，
//     不会像"按短边算"那样挤在顶部一个方格里。
//   * **宽度**（可点控件的行 / 每一行文字的排版宽度）→
//     `bandWidth(...)` / `geometry.rowWidth(...)` —— 圆屏取该段上下沿里更窄的弧宽 × 0.93，
//     非圆屏给"屏宽 − 左右各 3%"。**一个 dp 宽度都没有写死。**
//   * **控件尺寸**（图标大小、字号）→ `metrics.sizeDp`（短边）比例，和别的屏一致。
//
// ## 圆屏上四角不会被表壳切到（依据是算式，不是眼睛）
//
// 233dp / 466px 圆屏上逐行实算（直径 233dp，圆心 (116.5, 116.5)）：
//
// | 元素 | 纵向区间 | 这一段的圆内宽度 | 实际用的宽度 | 结论 |
// |---|---|---|---|---|
// | 返回键所在行 | 23.3 ~ 52.4dp | `min(cwf(0.0725), cwf(0.2525))` = 0.5186 | × `ARC_MARGIN`(0.95) = 0.4927 → 被 `MIN_ROW_WIDTH` 抬到 **0.52** → 行宽 **121.2dp**，行左右端 x = 55.9 / 177.1 | 按键本体只占 41.9dp，整行在圆内；x 和列表屏那颗 ⚙、功能界面那颗 📁 **逐像素相同** |
// | 应用图标 | 54.8 ~ 92.0dp | 上沿 `cwf(0.235)` = 0.848 → 半宽 98.8dp | 图标 37.3dp 正方、居中（x 97.9 ~ 135.1） | 离圆心**最远**的那个角 √(18.64² + 61.75²) = **64.5dp < 116.5dp**（半径） |
// | 四行文字 | 117.7 ~ 213.9dp | 每行取自己上下沿里更窄的那处 × 0.93 —— 最紧的是**最后一行** `min(cwf(0.845), cwf(0.930))` = 0.510 | 四行的可用宽度依次是 **213.2 / 203.0 / 156.8 / 110.6dp** | 最紧那一行是"开发者 春羽"（≈76dp）；最长那句"本应用使用 dsh 开发"（≈112dp）待在它自己的 156.8dp 里 |
//
// 所以"不被切"这件事来自"每行宽度都是按**它自己那一处的弧宽**算的"，
// 而不是靠调位置试出来的 —— 换一台密度不同的表（同一块 466px 屏报 d1.0）
// 这套比例依然成立。
//
// ## 动画（沿用项目语言，**没有新造浮层语言**）
//
//   * **进出这一屏**由路由那层的 `AnimatedContent` 负责，走的是**和「函数文件」列表
//     完全相同**的那一条分支（用户点名"动画和打开文件列表一样"，见 `GraphScreen`
//     里 `softScreenTransition()` 的注释；参数逐字相同：淡入 200ms + 0.94→1 放大 240ms，
//     出去是淡出 150ms + 1→1.02 放大 150ms，全部 `tween` + `FastOutSlowInEasing`）。
//   * **屏内这几行依次浮起来**：一个 0→1 的 `appear`（420ms `tween` + `FastOutSlowInEasing`），
//     每相邻两行错开 40ms；每一行把这个值喂给 [bubbleFade]（alpha + 0.92→1 缩放）。
//     全程**只动 `graphicsLayer`** —— 位置、字号、命中区在动画期间一个像素都不变
//     （和 `PressFeedback` / 画布卡片 / 属性屏那条铁律一致）。
//     为什么要"一个值 + 错开"而不是每行各一个 `animateFloatAsState`：
//     七行就是七个动画状态、七次每帧失效；这里只有一个状态，行数再多也只有一份开销。
//
// ## 版本号 / 名字从哪读（**都不写死、都不碰 `build.gradle.kts`**）
//
// 全部走 `PackageManager`（见 [readAppInfo]）：
//   * **应用名字** = `pm.getApplicationLabel(...)` —— 它就是 manifest 的
//     `android:label="@string/app_name"`，也就是 `strings.xml` 里那个 `app_name`
//     （本轮按用户要求改成了 `DrawFunc`）。所以"启动器上的名字 / 系统设置里的应用名 /
//     这一行"三处**同源**，改一次三处一起变。
//     ⚠️ 这里**没有**用 `stringResource(R.string.app_name)`：`tools\typecheck-ui.ps1`
//     是"手工拼 classpath + 直接调 K2JVMCompiler"的离线类型检查，**不生成 R 类**，
//     引用 `R.string.*` 会让它报 unresolved reference（要求是 0 error）。
//     `getApplicationLabel` 读到的就是同一个字符串，而且顺带不会在
//     "换 applicationId / 改名"时漏改一处。
//   * **版本号 / Version Code** = `getPackageInfo(packageName, 0)` 的
//     `versionName` / `longVersionCode`；**包名用 `context.packageName`，不写死字符串**。
//   * **图标** = `pm.getApplicationIcon(packageName)` —— 和启动器上那颗是同一张图
//     （自适应图标的圆形遮罩也由系统自己处理），不引用 `@mipmap/ic_launcher`
//     （同样是为了不依赖 R 类，而且换图标时这里自动跟着变）。
//   * **任何一步失败都不崩**：整段包在 `runCatching` 里，读不到就显示 `—`（见 [UNKNOWN]）、
//     图标留一块同尺寸的灰砖占位，版面不会塌。
// ---------------------------------------------------------------------------

/** 顶部工具条（返回键那一行）的几何 —— 和 `FunctionList` / `SavesScreen` 同一组数。 */
private const val TOOLBAR_TOP = 0.10f
private const val TOOLBAR_H = 0.125f

/** 图标按钮边长占屏比 —— **必须等于 `WatchMetrics.iconButton` 的 0.18**（宽度公式要用它）。 */
private const val ICON_BTN_H = 0.18f

/** 圆屏的弧宽余量 / 上下限 —— 三个数都和 `FunctionList` 那一套逐字相同。 */
private const val ARC_MARGIN = 0.95f
private const val MIN_ROW_WIDTH = 0.52f
private const val SQUARE_ROW_WIDTH = 0.90f

// ---- 内容各行的落点（占**真实屏高**；圆表上就是 233dp 的百分之几）----
/** 应用图标顶（0.235 × 233dp ≈ 54.8dp）：压在返回键那一行（23.3~52.4dp）**下面**，不重叠。 */
private const val ICON_TOP = 0.235f
/** 图标边长（占**短边**，控件尺寸那一类）：0.16 × 233dp ≈ 37.3dp。 */
private const val ICON_SIZE = 0.16f
/** 应用名（`DrawFunc`）那一行。 */
private const val NAME_TOP = 0.400f
/** 版本号那一行。 */
private const val VERSION_TOP = 0.505f
/** 文字行之间的**行距**（占真实屏高）：0.085 × 233dp ≈ 19.8dp，比一行 `fontSmall`（行高 17sp）略宽。 */
private const val LINE_H = 0.085f
/**
 * 上面这几行是"接着排"的，所以下面三行的落点由 [LINE_H] 推出来 ——
 * 用户那句"再**空一排**"就是 [DSH_TOP] 与 Version Code 之间**整整一个 [LINE_H]**。
 */
private const val CODE_TOP = VERSION_TOP + LINE_H
private const val DSH_TOP = CODE_TOP + LINE_H * 2f
private const val DEV_TOP = DSH_TOP + LINE_H

// ---- 入场 ----
/** 单行淡入 + 浮起的时长（和其它屏的 220ms 同一个量级）。 */
private const val IN_MS = 220
/** 相邻两行错开多久（`FunctionList` / `SavesScreen` 的行入场也是 45ms 这一档）。 */
private const val STAGGER_MS = 40
/** 一共几行参与入场（图标 + 名字 + 版本号 + Version Code + dsh + 开发者）。 */
private const val STAGGER_COUNT = 6
/** 整段动画的时长：最后一行也走满 [IN_MS]。 */
private const val APPEAR_MS = IN_MS + (STAGGER_COUNT - 1) * STAGGER_MS

/** 读不到某样东西时显示什么（用户提过"显示 `—` 之类"，**绝不崩**）。 */
private const val UNKNOWN = "—"

/** 连应用名都读不出来时的最后兜底（正常情况下读得到，见 [readAppInfo]）。 */
private const val NAME_FALLBACK = "DrawFunc"

/**
 * 「应用信息」页。
 *
 * 它**没有标题栏**（用户明确要求），顶部只有左上角那颗返回键；
 * 系统返回键走同一条路（`BackHandler` 在 `GraphScreen` 的 `Route.About` 分支里，
 * 和 `Route.Details` / `Route.Properties` 那两屏的分工完全一致 —— 路由那一侧管"怎么走"，
 * 这一屏只管"长什么样"）。
 *
 * @param onBack 左上角返回键：回函数列表（和系统返回键同一个落点）。
 */
@Composable
fun AboutScreen(
    metrics: WatchMetrics,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isRound = LocalConfiguration.current.isScreenRound

    // 名字 / 版本号 / Version Code / 图标都只读一次：它们是**进程级常量**，
    // 不会因为用户点了什么而变，所以不值得每次都问一遍 PackageManager。
    val info = remember(context) { readAppInfo(context) }

    val iconSize = metrics.sizeDp * ICON_SIZE
    // 图标先画成一张位图（Compose 的 `Image` 吃 `ImageBitmap`）；
    // 读不到图标（或转换失败）就是 null —— 下面留一块同尺寸的灰砖占位。
    val iconBitmap: ImageBitmap? = remember(info.icon, iconSize, density) {
        val drawable = info.icon ?: return@remember null
        val px = with(density) { iconSize.toPx() }.roundToInt().coerceAtLeast(1)
        runCatching { drawableToBitmap(drawable, px) }.getOrNull()
    }

    // 入场的总进度：0 → 1 一次性播一遍（`LaunchedEffect(Unit)`，回到这一屏时重播一次）。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = APPEAR_MS, easing = FastOutSlowInEasing)
    )

    /**
     * 第 [index] 行的进场进度（0~1）：在整段 [APPEAR_MS] 里错开 `index × [STAGGER_MS]`。
     *
     * 算式说明：整段时长已经预留了"错开完还够最后一行走满 [IN_MS]"，
     * 所以第 index 行的起点比例 `d = index × STAGGER / APPEAR`，
     * 它自己的进度就是把 `[d, 1]` 这一段重新映射回 `[0, 1]` —— 于是每一行都是
     * **同样 220ms、同样缓动**的一次浮入，只是起跑时间不同。
     */
    fun stage(index: Int): Float {
        val d = (index * STAGGER_MS).toFloat() / APPEAR_MS
        return ((appear - d) / (1f - d)).coerceIn(0f, 1f)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // ---- 这一屏的底：不透明纯黑 ----
            // 和 `SavesScreen` 同一条理由：`AnimatedContent` 转场的那两百多毫秒里
            // 上一屏还在淡出，根节点没有背景就会透出来（"漏底"）。
            // 取 `MaterialTheme.colors.background`（本主题里就是 #000000）而不是写死一个黑，
            // 将来换主题时它和最外层外壳一起变。
            .background(MaterialTheme.colors.background)
    ) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)

        // ---- ① 返回键：左上角，和「函数文件」列表、属性屏那颗在**同一处** ----
        //
        // 宽度自适应（用户那条长期规则）：这一行情景和 `FunctionList` 顶部那颗 ⚙ 完全一样，
        // 所以比例用的是**同一套公式**（取这一行上下沿里更窄的弧宽 × [ARC_MARGIN]，
        // 再夹在 [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间）——
        // 233dp 圆屏上算出来是 0.52 → 行宽 121.2dp，和列表屏那颗 ⚙、功能界面那颗 📁
        // **严丝合缝地对齐**（返回键因此出现在用户手指记住的那个位置上）。
        // 非圆屏那一支返回 1f，真实宽度由 `geometry.rowWidth` 给"屏宽 − 左右各 3%"。
        //
        // 按键**本体**的尺寸不在这里定：它是 `WatchIconButton` 那一类
        // **形状锁定**的图标键（宽 0.18 短边、高 × `ICON_BTN_ASPECT`），
        // 这是 README「硬约定」里唯一的例外（用户更早的要求："让按键强制保持在一个形状"）。
        // 真正自适应的是它所在的这一行 —— 所以手机上按钮旁边不会留出莫名其妙的空隙。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(TOOLBAR_TOP))
                .width(
                    geometry.rowWidth(
                        metrics = metrics,
                        ringFraction = toolbarWidthFraction(metrics, isRound),
                        isRound = isRound
                    )
                )
                .height(metrics.sizeDp * TOOLBAR_H),
            // 左端顶左：和列表屏的 ⚙ 同一个 x。
            contentAlignment = Alignment.CenterStart
        ) {
            WatchIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                metrics = metrics,
                onClick = onBack
            )
        }

        // ---- ② 应用图标（用户念的第一行）----
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(ICON_TOP))
                .size(iconSize)
                // 入场：alpha + 0.92→1 的缩放，**只动图层**
                .bubbleFade(alpha = stage(0), scale = 0.92f + 0.08f * stage(0))
        ) {
            val bitmap = iconBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    // 装饰性图片：应用名就在它下面一行，读屏软件不需要再念一遍
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 兜底：读不到启动器图标时留一块同尺寸的灰砖，版面不塌。
                // 用 `KeyPalette.normal`（普通键底）+ 大圆角，和这一屏之外的语言一致。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.large)
                        .background(KeyPalette.normal)
                )
            }
        }

        // ---- ③④⑤⑥⑦ 五行文字（名字 / 版本号 / Version Code / 空一排 / dsh / 开发者）----
        //
        // 名字用 `fontTitle` + 纯白（这一屏最重要的信息），其余用 `fontSmall`；
        // 版本号那两行压到 80% 白（`KeyPalette.textMuted`）做出层次，
        // 最后那句"开发者 春羽"再回到纯白收尾。
        // 颜色全部来自 `KeyPalette`（和整屏的按键文字同一套），没有新造色。
        AboutLine(
            text = info.name,
            top = NAME_TOP,
            fontSize = metrics.fontTitle,
            color = KeyPalette.text,
            appear = stage(1),
            metrics = metrics,
            geometry = geometry,
            isRound = isRound
        )
        AboutLine(
            text = "版本 ${info.versionName}",
            top = VERSION_TOP,
            fontSize = metrics.fontSmall,
            color = KeyPalette.textMuted,
            appear = stage(2),
            metrics = metrics,
            geometry = geometry,
            isRound = isRound
        )
        AboutLine(
            text = "Version Code ${info.versionCode}",
            top = CODE_TOP,
            fontSize = metrics.fontSmall,
            color = KeyPalette.textMuted,
            appear = stage(3),
            metrics = metrics,
            geometry = geometry,
            isRound = isRound
        )
        // ⚠️ 这里**故意什么都不画**：用户要的"再空一排"就是 [CODE_TOP] 和 [DSH_TOP]
        // 之间那整整一个 [LINE_H]（圆表上 ≈19.8dp）。用一个 Spacer 也能表达，
        // 但"空一排"是两个落点之差，写成常量比多一个不可见的组件更清楚。
        AboutLine(
            text = "本应用使用 dsh 开发",
            top = DSH_TOP,
            fontSize = metrics.fontSmall,
            color = KeyPalette.textMuted,
            appear = stage(4),
            metrics = metrics,
            geometry = geometry,
            isRound = isRound
        )
        AboutLine(
            text = "开发者 春羽",
            top = DEV_TOP,
            fontSize = metrics.fontSmall,
            color = KeyPalette.text,
            appear = stage(5),
            metrics = metrics,
            geometry = geometry,
            isRound = isRound
        )
    }
}

/**
 * 这一屏的一行文字。
 *
 * ## 它为什么是 `BoxScope` 的扩展
 *
 * 因为它要 `Modifier.align(Alignment.TopCenter)`（这一屏所有元素都是"水平居中 + 各自
 * 一个纵向落点"，没有用 `Column` 串起来 —— 每行的落点是**独立常量**，
 * 串成 Column 之后"空一排"就变成"往 Column 里塞一个 Spacer"，多一层不可见的组件，
 * 也让"某一行挪动"要连带改后面所有行）。`align` 只在 `BoxScope` 里可用，
 * 所以这个私有 composable 也声明成 `BoxScope` 的扩展 ——
 * `BoxWithConstraints` 的内容就是一个 `BoxScope`，因此调用点不用做任何转换。
 *
 * ## 宽度（用户那条长期规则）
 *
 * 文字**不是可点控件**，所以这里只做两件事：**宽度按真实可用宽度自适应 + 居中**，
 * 不写死任何 dp：
 *   * 圆屏：`bandWidth(...)` 取这一行上下沿里更窄的那处弧宽 × 0.93 ——
 *     文字因此永远待在圆内（表壳切不到）；
 *   * 非圆屏（手机 / 方表）：`ScreenGeometry.rowWidth` 给"屏宽 − 左右各 3%"，
 *     一行字铺满可用宽度、居中排版（手机上不会缩成一根窄柱）。
 * 行高**不设**（`Text` 自己按字号撑高），落点由 `offset(y = geometry.sy(top))` 决定 ——
 * 这样任何系统字体缩放下都不会出现"字被盒子的高度切掉"。
 *
 * @param top 这一行的落点（占**真实屏高**）——调用方给的都是上面那批常量。
 */
@Composable
private fun BoxScope.AboutLine(
    text: String,
    top: Float,
    fontSize: TextUnit,
    color: Color,
    appear: Float,
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    isRound: Boolean
) {
    Text(
        text = text,
        fontSize = fontSize,
        color = color,
        textAlign = TextAlign.Center,
        // 一行就够（最长那句"本应用使用 dsh 开发"在圆表上 ≈112dp，那一行的可用宽度
        // 是 156.8dp —— 逐行实算见文件头那张表）；万一将来文案变长，
        // 省略号比"换行把版面撑破"安全。
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = geometry.sy(top))
            .width(
                bandWidth(
                    metrics = metrics,
                    geometry = geometry,
                    isRound = isRound,
                    yTop = top,
                    // 下沿多算一个行距：弧宽是"越往下越窄"，取更窄的那一处更保守
                    // （分段控件最容易被切的是下沿的角，见 `MathKeyboard.bandWidth`）。
                    yBottom = top + LINE_H
                )
            )
            // 入场：只动图层（alpha），位置/字号/命中区一个像素都不动。
            // ⚠️ 必须写在 `.width(...)` **右边**（内层）—— `graphicsLayer` 只影响它
            // 内层画的东西，写在左边会让这一段高度里的内容在淡出时露出来
            // （`Common.bubbleFade` 的 KDoc 记着这个坑）。
            .bubbleFade(alpha = appear)
    )
}

/**
 * 顶部那一行的宽度比例（占屏宽）。
 *
 * **逐字照抄 `FunctionList.toolbarWidthFraction`**（`SavesScreen` 里也有一份同样的副本）：
 * 取这一行**上下两端里更窄**的那处圆内宽度 × [ARC_MARGIN]，再夹在
 * [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间。
 *
 * 为什么取上下两端而不是行中心：按钮是长方块，它的上边两个角比行中心更靠外 ——
 * 按中心算会让角落到表壳外面（那一小块在圆表上**真的看不见**）。
 * 为什么高度取 `max(TOOLBAR_H, ICON_BTN_H) / 2`：图标按钮是 `Modifier.size(...)` 那一类，
 * 它的真实上下沿只会落在"行高"和"按钮高"这两个范围之内，按更大的那个算，
 * 两种情况下都在圆内。233dp 圆屏的实算：范围 y ∈ [0.0725, 0.2525]，
 * f(0.0725) = 0.5186 × 0.95 = 0.4927 → 被 [MIN_ROW_WIDTH] 抬到 **0.52** → 行宽 **121.2dp**。
 *
 * 非圆屏那一支返回 1f 当占位，真实宽度由 `ScreenGeometry.rowWidth` 给"屏宽 − 左右各 3%"
 * （和列表行、其它屏的工具条同一个口径：**圆屏走弧宽、非圆屏走满宽**）。
 *
 * ⚠️ 这里是**第三份副本**（`FunctionList` / `SavesScreen` / 本文件）。没有抽成共用函数，
 * 是因为那两个文件里的同名函数是 `private`，而它们各自的注释里都记着同一句取舍；
 * 真要把三处收敛成一处，是"改那两屏"的活，不该由这一屏顺手做掉。
 */
private fun toolbarWidthFraction(metrics: WatchMetrics, isRound: Boolean): Float {
    if (!isRound) return 1f
    val center = TOOLBAR_TOP + TOOLBAR_H * 0.5f
    val halfSpan = maxOf(TOOLBAR_H, ICON_BTN_H) * 0.5f
    val raw = minOf(
        metrics.circleWidthFraction(center - halfSpan),
        metrics.circleWidthFraction(center + halfSpan)
    ) * ARC_MARGIN
    return raw.coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
}

// ---------------------------------------------------------------------------
// 从 `PackageManager` 读应用信息（**不引 R 类、不写死包名、失败不崩**）
// ---------------------------------------------------------------------------

/** 这一屏要显示的四样东西（都是进程级常量，读一次就够）。 */
private class AppInfo(
    /** 应用名：manifest 的 `android:label` → `strings.xml` 的 `app_name`（现在是 `DrawFunc`）。 */
    val name: String,
    /** `versionName`（`app/build.gradle.kts` 里现在写的是 `1.0`）。读不到就是 [UNKNOWN]。 */
    val versionName: String,
    /** `versionCode`（现在是 `1`）。读不到就是 [UNKNOWN]。 */
    val versionCode: String,
    /** 启动器图标；读不到就是 null（界面上留一块同尺寸的灰砖占位）。 */
    val icon: Drawable?
)

/**
 * 读应用名 / 版本号 / Version Code / 图标。
 *
 * ## 三处刻意的写法
 *
 *   1. **包名用 `context.packageName`**（用户明确要求"不要写死字符串"）——
 *      换 `applicationId`（例如出个测试包）时这里自动跟着变，不会读出别的应用的信息。
 *   2. **不引用 `R.string.app_name` / `@mipmap/ic_launcher`**：
 *      `tools\typecheck-ui.ps1` 用"手工拼 classpath + `K2JVMCompiler`"做离线类型检查，
 *      **不生成 R 类**，引用它会让类型检查报 unresolved reference（那一步要求 0 error）。
 *      而 `PackageManager` 这两条路读到的**就是**同一份东西：应用名来自 manifest 的
 *      `android:label`（= `app_name`），图标来自 manifest 的 `android:icon`
 *      （= `@mipmap/ic_launcher`）—— 语义完全等价，还顺带解决"改名/换图标时漏改一处"。
 *   3. **整段 `runCatching` 兜底**：任何一步失败（包信息读不到、label 是空的、
 *      图标抛异常）都退化成 [UNKNOWN] / null，**绝不崩**（用户明确要求）。
 *
 * ⚠️ `@Suppress("DEPRECATION")` 是有意的：`getPackageInfo(String, Int)` 在 API 33 起、
 * `PackageInfo.versionCode` 在 API 28 起都被标了 deprecation（分别推荐
 * `PackageInfoFlags` 与 `longVersionCode`）。这里故意用老写法，因为
 * **本 App 的 minSdk 是 24**：`longVersionCode` 在 24~27 上不存在，
 * 所以必须两条路都写（下面按 `Build.VERSION.SDK_INT` 分支），
 * 而 API 33 的 `PackageInfoFlags` 那条路在 24~32 上同样用不了 ——
 * 用被标记为 deprecated 的这个重载是"一套代码覆盖全部支持的版本"的唯一办法。
 */
@Suppress("DEPRECATION")
private fun readAppInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val pkg = context.packageName
    return runCatching {
        val info = pm.getPackageInfo(pkg, 0)
        val label = runCatching { pm.getApplicationLabel(info.applicationInfo).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: NAME_FALLBACK
        // versionCode：API 28 起用 longVersionCode（能装下 32 位以上的值），
        // 24~27 上退回那个 int（本机 minSdk 24，所以这一支是真会走到的）。
        val code: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        AppInfo(
            name = label,
            versionName = info.versionName?.takeIf { it.isNotBlank() } ?: UNKNOWN,
            versionCode = code.toString(),
            icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
        )
    }.getOrElse {
        // 兜底：读不到就显示占位符（用户提过"显示 — 之类"），界面照旧，绝不崩。
        AppInfo(name = NAME_FALLBACK, versionName = UNKNOWN, versionCode = UNKNOWN, icon = null)
    }
}

/**
 * 把一个 `Drawable`（启动器图标）画成方形的 [ImageBitmap]。
 *
 * 为什么手写 `Canvas` 而不是用 `androidx.core.graphics.drawable.toBitmap()`：
 * 这里要**自己控制 bounds**，才能做到两件 `toBitmap` 不保证的事 ——
 *   1. **保持长宽比**：普通 png 图标不一定是正方形，直接拉满会压变形；
 *      这里按小的那一维等比缩放、居中放进正方形画布；
 *   2. **自适应图标（`AdaptiveIconDrawable`）的 `intrinsicWidth/Height` 是 -1**
 *      （它本来就没有"固定像素尺寸"，形状由系统的圆形/圆角遮罩决定）——
 *      这一支直接把整块画布给它，让系统遮罩自己生效。
 *
 * ⚠️ 调用点已经用 `runCatching` 包住：这里万一抛异常（某些机型的图标实现会），
 * 结果就是 null → 界面上那块灰砖占位，**不会把这一屏弄崩**。
 */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) {
        drawable.setBounds(0, 0, sizePx, sizePx)
    } else {
        val scale = minOf(sizePx.toFloat() / w, sizePx.toFloat() / h)
        val dw = (w * scale).roundToInt().coerceAtLeast(1)
        val dh = (h * scale).roundToInt().coerceAtLeast(1)
        val left = (sizePx - dw) / 2
        val top = (sizePx - dh) / 2
        drawable.setBounds(left, top, left + dw, top + dh)
    }
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
