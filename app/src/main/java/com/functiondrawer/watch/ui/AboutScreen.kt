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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.round
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 「应用信息」页 —— 第一屏那颗 ⚙ 的**长按**进来的一屏。
//
// 用户原话（第一轮，逐条都落在这个文件里）：
//
// > "长按之后打开应用信息页面（不要有标题），最上方是应用图标，然后下一排是应用名字
// > （把应用名字改成 DrawFunc），再下一排是应用版本号，再下一排是 Version Code，
// > 然后再空一排，再下一排写"本应用使用 dsh 开发"，再下一排写"开发者 春羽"。
// > 注意动画和打开文件列表一样，注意放置返回键。"
//
// 第二轮（本轮）：
//
// > "把应用信息界面改成列表，在应用列表底部加一行字：bug反馈：drawfunc@163.com"
//
// ## 版面：**没有标题**，内容是一列可滚动的行
//
// 上一版是"每一行各自一个绝对落点"（`ICON_TOP` / `NAME_TOP` / `VERSION_TOP` /
// `CODE_TOP` / `DSH_TOP` / `DEV_TOP`，每个都是"真实屏高的百分之几"）。
// 用户这一轮要求**改成列表** —— 落点由"一串固定常量"变成"一个 [LazyColumn] 的顺序"，
// 于是三件事一起变好：
//   * **加一行不用再重排后面所有行**（底部那行 `bug反馈` 就是这么加的：
//     往 [`aboutItems`] 末尾追加一项就行，一个落点常量都不用改）；
//   * **装不下可以滚**：八行加起来 ≈ 241dp，而这一屏从列表上沿到屏幕底只剩 ≈150dp
//     （233dp 圆表），绝对落点那一版最后几行会被挤到屏幕外；
//   * 行高、行距、弧宽收窄**全部照抄设置页**（同一套 `ROW_H` / `ROW_GAP` /
//     `rowWidthFraction`），所以"设置页"和"应用信息页"在手表上看起来是同一种东西。
//
// ```
//   ┌ ● 返回键（左上角，和其它屏同一处，形状锁在 Common.kt）   ← TOOLBAR_TOP(0.10) / TOOLBAR_H(0.125)
//   │
//   │   ┌──────────────────────────────┐
//   │   │          [ 应用图标 ]         │   ← 图标那一行（高 = 0.16 × 短边，只有图标、没有胶囊底）
//   │   ├──────────────────────────────┤
//   │   │          DrawFunc            │   ← fontTitle、纯白
//   │   ├──────────────────────────────┤
//   │   │         版本 1.1              │   ← fontSmall、80% 白
//   │   ├──────────────────────────────┤
//   │   │       Version Code 3         │
//   │   ├──────────────────────────────┤
//   │   │        （空一排，无胶囊）      │   ← 用户第一轮要的"再空一排"
//   │   ├──────────────────────────────┤
//   │   │      本应用使用 dsh 开发       │
//   │   ├──────────────────────────────┤
//   │   │         开发者 春羽           │   ← 纯白收尾
//   │   ├──────────────────────────────┤
//   └   │  bug反馈：drawfunc@163.com    │   ← 本轮新增，**列表最后一行**
//       └──────────────────────────────┘
// ```
//
// ⚠️ 最后那一行在 233dp 圆表上**一行装不下**（≈134dp 的字，最窄的行只有 121dp），
// 所以它**自己会长高**折成两行（用户原话："如果显示全就不管，显示不全就自动换行"）：
// ```
//       ┌──────────────────────────────┐
//       │          bug反馈：            │
//       │      drawfunc@163.com        │
//       └──────────────────────────────┘
// ```
// 别的行仍然钉死一行高（它们的文案都是一眼读完的短句，换行只会打乱节奏）。
//
// **"不要有标题"照旧**：这一屏没有"关于"/"应用信息"之类的标题栏，
// 顶部只有那一颗返回键（用户原话里"注意放置返回键"指的就是它）。
//
// ## 宽度 / 高度分别跟谁走（README 那条长期规则的原文口径）
//
//   * **行宽** → 圆屏走 `rowWidthFraction`（按这一行**此刻**在屏幕上的高度取弧宽 ×
//     [ARC_MARGIN]，夹在 [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间），
//     非圆屏走 `geometry.rowWidth(...)`（屏宽 − 左右各 3%）。**一个 dp 宽度都没写死。**
//     ⚠️ 行宽必须按"这一行现在在屏幕上的高度"算，不能按序号算 —— 列表能滚之后
//     两者不是一回事（设置页踩过这个坑，注释留在 `SettingsScreen.rowWidthFraction`）。
//   * **行高 / 字号 / 图标大小** → `metrics.sizeDp`（**短边**）比例，和别的屏一致。
//   * **列表上沿 / 可视高度** → `geometry.sy(...)`（**真实屏高**）与 `geometry.listHeight(...)`，
//     手机上因此铺满整屏，不会挤在顶部一个方格里。
//
// ## 为什么空一排也占一行
//
// 用户第一轮要的"再空一排"在列表里就是**一个没有任何内容的行**（[AboutItem.Kind.GAP]）：
// 它照样占 [ROW_H] + 行距，所以"空一排"仍然是一个整行的视觉间隔。
// 不写成 `Arrangement.spacedBy` 的特例，也不用 `Spacer` 塞进 items 里 ——
// 它是**列表的一项**（有 key、参与交错入场），这样"第几行是什么"在数据里一眼可见。
//
// ## 动画（沿用项目语言，**没有新造浮层语言**）
//
//   * **进出这一屏**由路由那层的 `AnimatedContent` 负责，走的是**和「函数文件」列表
//     完全相同**的那一条分支（用户点名"动画和打开文件列表一样"，见 `GraphScreen`
//     里 `softScreenTransition()` 的注释；参数逐字相同：淡入 200ms + 0.94→1 放大 240ms，
//     出去是淡出 150ms + 1→1.02 放大 150ms，全部 `tween` + `FastOutSlowInEasing`）。
//   * **列表各行依次浮起来**：和设置页同一套 —— 每行一个 `animateFloatAsState`，
//     `delay = index × [ROW_STAGGER_MS]`，只喂给 `graphicsLayer{alpha, translationY}`
//     （位置、字号、命中区在动画期间一个像素都不变）。
//
// ## 版本号 / 名字从哪读（**都不写死、都不碰 `build.gradle.kts`**）
//
// 全部走 `PackageManager`（见 [readAppInfo]）：
//   * **应用名字** = `pm.getApplicationLabel(...)` —— 它就是 manifest 的
//     `android:label="@string/app_name"`，也就是 `strings.xml` 里那个 `app_name`
//     （`DrawFunc`）。所以"启动器上的名字 / 系统设置里的应用名 / 这一行"三处**同源**。
//     ⚠️ 这里**没有**用 `stringResource(R.string.app_name)`：`tools\typecheck-ui.ps1`
//     是"手工拼 classpath + 直接调 K2JVMCompiler"的离线类型检查，**不生成 R 类**，
//     引用 `R.string.*` 会让它报 unresolved reference（要求是 0 error）。
//   * **版本号 / Version Code** = `getPackageInfo(packageName, 0)` 的 `versionName` /
//     `longVersionCode`；**包名用 `context.packageName`，不写死字符串**。
//   * **图标** = `pm.getApplicationIcon(packageName)` —— 和启动器上那颗是同一张图。
//   * **任何一步失败都不崩**：整段包在 `runCatching` 里，读不到就显示 `—`（见 [UNKNOWN]）、
//     图标留一块同尺寸的灰砖占位，版面不会塌。
//
// ## 底部那行"bug反馈"
//
// 文案逐字取自用户原话（含全角冒号）：[FEEDBACK_TEXT]。
// 它是**最后一行**，纯文字、不可点 —— 用户只说"加一行字"，没说点它要做事；
// 手表上也没有能顺利发信的邮件客户端，做成可点反而会让人以为点了会打开什么。
// ⚠️ 它在圆表上一行放不下（见上面 ASCII 图那段），所以**允许自动折行、胶囊跟着长高**：
// 用户第二轮原话："bug反馈邮箱那里的条改成如果显示全就不管，显示不全就自动换行。"
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

/** 工具条下沿到列表上沿之间留的空（和设置页同一个数）。 */
private const val LIST_GAP = 0.02f
/** 列表上沿（占**真实屏高**）：工具条那一行下面一点点。 */
private const val LIST_TOP = TOOLBAR_TOP + TOOLBAR_H + LIST_GAP

/**
 * 列表下沿（占**真实屏高**，0.90）—— **和设置页同一个数**。
 *
 * 它不是"屏高的百分之几"这种随手取的数，而是按圆屏拱口推出来的：圆表上越靠下弦越短，
 * 一行的宽度被 [MIN_ROW_WIDTH]（0.52 × 233dp = 121.2dp）夹住之后，圆内至少要有
 * 121.2dp 才装得下 —— `2·√(116.5² − (0.90 × 233 − 116.5)²) = 139.8dp > 121.2dp` 成立，
 * 而再往下延到 0.95 屏高时只剩 103dp，最后一行就会顶到表壳外面。
 * 推导与后果写在 `SettingsScreen.LIST_BOTTOM` 的注释里（那边踩过同一个坑）。
 */
private const val LIST_BOTTOM = 0.90f

/**
 * 行高（占**短边**，0.115 × 233dp ≈ 26.8dp）—— **和设置页逐字相同**。
 *
 * 用户明确说过"行高/字号/胶囊尺寸仍按短边缩放，别改"，所以它是"控件尺寸"那一类。
 */
private const val ROW_H = 0.115f

/** 行距（占短边）—— 和设置页同一个数（0.010 × 233dp ≈ 2.3dp）。 */
private const val ROW_GAP = 0.010f

/** 行内文字左右各留一点内边距（占短边，0.026 × 233dp ≈ 6.1dp，和设置行同一个数）。 */
private const val ROW_SIDE_PAD = 0.026f

/** 图标那一行的高度（占短边）：就是图标自己的边长，行里只放这一张图。 */
private const val ICON_SIZE = 0.16f

/** 表冠每滚一格滚多少行 —— 和设置页同一个系数。 */
private const val ROTARY_SCROLL_FACTOR = 1.0f

// ---- 入场 ----
/** 单行淡入 + 浮起的时长（和设置页各行同一个数）。 */
private const val ROW_IN_MS = 220
/** 相邻两行错开多久（设置页是 35ms，同一档）。 */
private const val ROW_STAGGER_MS = 35
/** 入场时每行从下方浮起多少（占短边）：0.015 × 233dp ≈ 3.5dp，和设置页相同。 */
private const val ROW_IN_SLIDE = 0.015f

/** 读不到某样东西时显示什么（用户提过"显示 `—` 之类"，**绝不崩**）。 */
private const val UNKNOWN = "—"

/** 连应用名都读不出来时的最后兜底（正常情况下读得到，见 [readAppInfo]）。 */
private const val NAME_FALLBACK = "DrawFunc"

/** 列表最后一行：bug 反馈邮箱（**逐字**取自用户原话，含全角冒号）。 */
private const val FEEDBACK_TEXT = "bug反馈：drawfunc@163.com"

/**
 * 列表里的一项。
 *
 * 为什么用"数据 + 类型"而不是把八行硬写在 `LazyColumn` 里：行的**顺序**就是版面，
 * 写成一份数据之后"加一行/换文案"只动一处（底部那行 `bug反馈` 本轮就是这么加的），
 * 而且 `itemsIndexed` 的 key、交错入场的序号都能直接从它推出来。
 */
private class AboutItem(val key: String, val kind: Kind, val text: String = "") {
    /**
     * 这一行画成什么样子：
     *   * [ICON] 图标那一行（只有图标，没有胶囊底）；
     *   * [TITLE] 应用名（`fontTitle`、纯白）；
     *   * [MUTED] 版本 / Version Code / dsh（`fontSmall`、80% 白）；
     *   * [BODY] 开发者那一行（`fontSmall`、纯白 —— 这一屏的"收尾句"）；
     *   * [FEEDBACK] 最后那行 bug 反馈邮箱：**和 [MUTED] 同色同字号，但放不下时自动折成两行**
     *     （用户原话："如果显示全就不管，显示不全就自动换行"）；
     *   * [GAP] 用户要的"空一排"：一个**没有任何内容**的行（照样占一行的高度）。
     */
    enum class Kind { ICON, TITLE, MUTED, BODY, FEEDBACK, GAP }
}

/**
 * 「应用信息」页。
 *
 * 它**没有标题栏**（用户明确要求），顶部只有左上角那颗返回键；
 * 系统返回键走同一条路（`BackHandler` 在 `GraphScreen` 的 `Route.About` 分支里，
 * 和 `Route.Details` / `Route.Properties` 那两屏的分工完全一致 —— 路由那一侧管"怎么走"，
 * 这一屏只管"长什么样"）。
 *
 * @param onBack 左上角返回键：回函数列表（和系统返回键同一个落点）。
 * @param rotary 表冠 / 滚轮事件流：这一屏现在是**可滚动列表**，表冠要能滚它
 *   （接法和 `FunctionList` / `SavesScreen` / `SettingsScreen` 一字不差）。
 *   默认值让"没补这一行也能编译、也能跑"，只是表冠滚不动。
 */
@Composable
fun AboutScreen(
    metrics: WatchMetrics,
    onBack: () -> Unit,
    rotary: Flow<Float> = emptyFlow()
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

    // 列表内容：**顺序就是版面**（图标 → 名字 → 版本 → Version Code → 空一排 →
    // dsh → 开发者 → bug反馈）。加行/改文案只动这一处。
    val items = remember(info) {
        listOf(
            AboutItem("icon", AboutItem.Kind.ICON),
            AboutItem("name", AboutItem.Kind.TITLE, info.name),
            AboutItem("version", AboutItem.Kind.MUTED, "版本 ${info.versionName}"),
            AboutItem("code", AboutItem.Kind.MUTED, "Version Code ${info.versionCode}"),
            AboutItem("gap", AboutItem.Kind.GAP),
            AboutItem("dsh", AboutItem.Kind.MUTED, "本应用使用 dsh 开发"),
            AboutItem("dev", AboutItem.Kind.BODY, "开发者 春羽"),
            // ---- 本轮新增：用户要的"应用列表底部加一行字" ----
            // ⚠️ 它用的是 [AboutItem.Kind.FEEDBACK] 而不是 MUTED：这一行在圆表上装不下，
            //    要允许它自动折成两行（用户原话："如果显示全就不管，显示不全就自动换行"）。
            AboutItem("feedback", AboutItem.Kind.FEEDBACK, FEEDBACK_TEXT)
        )
    }

    // 入场的总开关：进这一屏后置 true，各行按序号错开淡入（同设置页那套）。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val listState = rememberLazyListState()

    // ---- 表冠：这一屏只用来滚动列表（缩放图像是图像界面的事）----
    // 往下拨 = 往下滚；手指直接拖列表也照常能滚（`LazyColumn` 自带的手势），互不干扰。
    LaunchedEffect(rotary) {
        rotary.collect { amount ->
            listState.scrollBy(-amount * ROTARY_SCROLL_FACTOR)
        }
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
        val listTopPx = with(density) { geometry.sy(LIST_TOP).toPx() }
        val screenHeightPx = with(density) { geometry.screenH.toPx() }

        /**
         * 列表可视区高度 —— **和设置页逐字同一套**（`SettingsScreen.listHeight`）。
         *
         *   * 圆屏：`sy(LIST_BOTTOM) − sy(LIST_TOP)`。这里**刻意不给圆屏"铺到屏幕底部"**：
         *     [LIST_BOTTOM] = 0.90 是按圆屏拱口（越靠下弦越短）反推出来的几何结论 ——
         *     再往下延，滚到底的那一行会被夹到 [MIN_ROW_WIDTH]（0.52）而圆内实际只剩
         *     103dp（0.95 屏高处），行的两个角就伸到表壳外面去了（用户那条宽度规则
         *     里点名的坑）。0.90 处圆内宽度是 139.8dp > 121dp，最后一行仍然整个在圆内。
         *   * 非圆屏：一路铺到屏幕底边、只留 `LIST_BOTTOM_PAD`（`ScreenGeometry.listHeight`）
         *     —— 手机上因此"铺满整屏"，不会挤在顶部一个方格里。
         */
        val listHeight = if (isRound) {
            geometry.sy(LIST_BOTTOM) - geometry.sy(LIST_TOP)
        } else {
            geometry.listHeight(metrics, geometry.sy(LIST_TOP))
        }

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
            contentAlignment = Alignment.CenterStart
        ) {
            WatchIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                metrics = metrics,
                onClick = onBack
            )
        }

        // ---- ② 内容列表（图标 + 七行）----
        //
        // 三件和设置页/函数列表对齐的事：
        //   1. 容器 `fillMaxWidth()`，**行自己**按所在高度的弧宽收窄并居中
        //      （靠 `horizontalAlignment`），行宽按"这一行**此刻**在屏幕上的高度"算；
        //   2. 行距交给 `Arrangement.spacedBy(ROW_GAP)`，不自己塞占位行；
        //   3. `key` 用 [AboutItem.key]：滚动回收重建时行不会认错自己，
        //      也不会把"另一行的入场进度"套到这一行上。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 上沿按**真实屏高**（圆屏上逐像素不变）
                .offset(y = geometry.sy(LIST_TOP))
                .fillMaxWidth()
                // 左右余量只在非圆屏上生效：圆屏上行宽本来就是按弧宽收过的
                // （最宽 0.90 屏宽，离屏幕边还有 0.05 屏宽），再加边距等于把圆表上的行
                // 又缩窄一圈 —— 圆屏这一支一点都不能动（和 `SettingsScreen` /
                // `FunctionsScreen` 里那两处 `.padding(horizontal = if (isRound) 0.dp else ...)`
                // 同一个做法）。
                .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))
                // 高度 = 圆屏取到 [LIST_BOTTOM]（按拱口反推）、非圆屏铺到屏幕底
                // （见上面 `listHeight` 的那段推导）
                .height(listHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * ROW_GAP)
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                val widthFraction = rowWidthFraction(
                    listState = listState,
                    itemKey = item.key,
                    listTopPx = listTopPx,
                    screenPx = screenHeightPx,
                    metrics = metrics,
                    isRound = isRound
                )
                when (item.kind) {
                    // 图标那一行：只有图标，没有胶囊底（它不是"一条信息"，是这一屏的门面）
                    AboutItem.Kind.ICON -> AboutIconRow(
                        bitmap = iconBitmap,
                        iconSize = iconSize,
                        index = index,
                        entered = entered,
                        metrics = metrics,
                        widthFraction = widthFraction,
                        geometry = geometry,
                        isRound = isRound
                    )
                    // "空一排"：占位行，什么都不画（用户第一轮的原话）
                    AboutItem.Kind.GAP -> AboutGapRow(
                        metrics = metrics,
                        widthFraction = widthFraction,
                        geometry = geometry,
                        isRound = isRound
                    )
                    else -> AboutTextRow(
                        text = item.text,
                        kind = item.kind,
                        metrics = metrics,
                        widthFraction = widthFraction,
                        geometry = geometry,
                        isRound = isRound,
                        index = index,
                        entered = entered
                    )
                }
            }
        }
    }
}

/**
 * 图标那一行（列表第一项）。
 *
 * 行高就是图标自己的边长（[ICON_SIZE] × 短边），**没有胶囊底** ——
 * 它是这一屏的门面，不是"一条信息"。宽度仍然按这一行的弧宽收窄：
 * 图标本身很小（233dp 表上 37.3dp），收窄只是保证它在圆内居中，
 * 不会出现"图标跑到表壳切掉的那两块里"。
 */
@Composable
private fun AboutIconRow(
    bitmap: ImageBitmap?,
    iconSize: Dp,
    index: Int,
    entered: Boolean,
    metrics: WatchMetrics,
    widthFraction: Float,
    geometry: ScreenGeometry,
    isRound: Boolean
) {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )

    Box(
        modifier = Modifier
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            .height(iconSize)
            // 入场只动图层：位置 / 尺寸 / 命中区一个像素都不变
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
            },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(iconSize)) {
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
    }
}

/**
 * "空一排"（用户第一轮原话要的那个整行间隔）。
 *
 * 它**有行高、没有内容、没有胶囊底**：占位靠的是 `Modifier.height(ROW_H)`，
 * 于是它和别的行一样被 `spacedBy` 隔开，"空一排"就是**一个整行的视觉间隔**。
 * 没有用 `Spacer` 塞进 items 里：那样它就没有 key、不参与交错入场，
 * 而这里它是**列表的一项**（"第几行是什么"在数据里一眼可见）。
 */
@Composable
private fun AboutGapRow(
    metrics: WatchMetrics,
    widthFraction: Float,
    geometry: ScreenGeometry,
    isRound: Boolean
) {
    Box(
        modifier = Modifier
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            .height(metrics.sizeDp * ROW_H)
    )
}

/**
 * 一行文字（应用名 / 版本号 / Version Code / dsh / 开发者 / bug反馈）。
 *
 * 装在一颗**深灰胶囊**里，配色与设置行、函数列表行、键盘按键同一套（`KeyPalette.normal`）——
 * 用户要的是"改成列表"，而这个 App 里"列表"的视觉语言就是这种整行胶囊。
 * 文字**居中**（这一屏是"信息展示"，不是"设置项"那种标题在左、取值在右的排版）。
 *
 * ## 高度：默认钉死一行，**只有 bug反馈那一行按内容长**
 *
 * 用户原话（本轮）："bug反馈邮箱那里的条改成**如果显示全就不管，显示不全就自动换行**。"
 *
 * 这句话落到实现上就是 [AboutItem.Kind.FEEDBACK] 那一行（[wrap] = true）：
 *   * **高度用 `heightIn(min = ROW_H)` 而不是 `height(ROW_H)`** ——
 *     一行装得下时它就停在最小高度（和别的行**逐像素相同**，用户说的"显示全就不管"）；
 *     装不下时 [Text] 自己折成两行，胶囊跟着内容变高（`LazyColumn` 的 item 高度本来就按内容算，
 *     所以不会和邻居打架）；
 *   * 其余各行仍然 `height(ROW_H)` 钉死 + `maxLines = 1` + 省略号：
 *     它们都是一眼能读完的短句，让它们随便换行只会把这一屏的节奏打乱。
 *
 * ## 为什么最多两行、为什么这样一定放得下
 *
 * 233dp 圆表上这一行最窄是 `0.52 × 233 ≈ 121dp`，减去左右各 6.1dp 内边距只剩 ≈109dp；
 * 而 `bug反馈：drawfunc@163.com` 在 12.1sp 下约 134dp —— **一行确实放不下**（用户看到的截断）。
 * 但它的折行点正好在全角冒号之后（CJK 标点后面允许断行）：
 *   * 第一行 `bug反馈：` ≈ 30dp；
 *   * 第二行 `drawfunc@163.com` ≈ 104dp ≤ 109dp；
 * 所以 **2 行一定够**，`maxLines = 2` 不会是"换个地方继续截断"。
 * 万一将来文案更长，第 3 行会以省略号收尾（宁可省略号，也不让某一行把整屏撑成一面墙）。
 */
@Composable
private fun AboutTextRow(
    text: String,
    kind: AboutItem.Kind,
    metrics: WatchMetrics,
    widthFraction: Float,
    geometry: ScreenGeometry,
    isRound: Boolean,
    index: Int,
    entered: Boolean
) {
    // 应用名是这一屏最重要的信息（`fontTitle` + 纯白），其余按"是不是收尾句"分两档：
    // 开发者那行纯白收尾，版本号那几行压到 80% 白（`KeyPalette.textMuted`）做出层次。
    // 颜色全部来自 `KeyPalette`（和整屏的按键文字同一套），没有新造色。
    val fontSize: TextUnit = if (kind == AboutItem.Kind.TITLE) metrics.fontTitle else metrics.fontSmall
    // 应用名和"开发者 春羽"（这一屏的收尾句）用纯白；版本号 / Version Code / dsh /
    // bug反馈 压到 80% 白（`KeyPalette.textMuted`）做出层次。
    val color: Color =
        if (kind == AboutItem.Kind.TITLE || kind == AboutItem.Kind.BODY) KeyPalette.text
        else KeyPalette.textMuted
    // 只有 bug反馈那一行允许换行/长高（见上面那段"高度"）
    val wrap = kind == AboutItem.Kind.FEEDBACK

    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = ROW_IN_MS,
            delayMillis = index * ROW_STAGGER_MS,
            easing = FastOutSlowInEasing
        )
    )

    Surface(
        color = KeyPalette.normal,
        shape = PillShape,
        modifier = Modifier
            .width(geometry.rowWidth(metrics, widthFraction, isRound))
            // 换行那一行：`heightIn(min = 行高)`，装得下就是一行的高度、装不下跟着内容长
            .then(
                if (wrap) Modifier.heightIn(min = metrics.sizeDp * ROW_H)
                else Modifier.height(metrics.sizeDp * ROW_H)
            )
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * metrics.sizeDp.toPx() * ROW_IN_SLIDE
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = fontSize,
                color = color,
                textAlign = TextAlign.Center,
                // ⚠️ 这里**不再**写死 `maxLines = 1`：bug反馈那一行放不下时要折成两行
                //（用户本轮的要求）。其余各行仍然是"一行 + 省略号"，
                // 因为它们的文案都是一眼能读完的短句。
                maxLines = if (wrap) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = metrics.sizeDp * ROW_SIDE_PAD,
                    // 只有会长高的那一行需要纵向内边距（两行文字贴着胶囊边不好看）；
                    // 别的行高度是钉死的，加了也只是把居中的文字原地不动地留点余量。
                    vertical = if (wrap) metrics.sizeDp * ROW_SIDE_PAD else 0.dp
                )
            )
        }
    }
}

/**
 * 圆屏上这一行的宽度比例（占屏宽）。
 *
 * **和 `SettingsScreen.rowWidthFraction` / `FunctionList.rowWidthFraction` /
 * `SavesScreen.rowWidthFraction` 逐字同一套**（这是第四份副本 —— 那三份都是 `private`；
 * 收敛成一处是"改那三屏"的活，不该由这一屏顺手做掉，理由和 [toolbarWidthFraction] 一样）：
 *   * 按这一行**此刻**在屏幕上的高度取圆内宽度 × [ARC_MARGIN]，夹在
 *     [MIN_ROW_WIDTH] ~ [SQUARE_ROW_WIDTH] 之间；
 *   * **量化到百分位**（`round(raw * 100) / 100`）再包 `derivedStateOf`：
 *     `LazyListState.layoutInfo` 滚动时每帧都在变，量化之后只有"宽度真的变了"才重组；
 *   * 查不到这一项（还没测到、或已滚出屏幕）退回 [SQUARE_ROW_WIDTH]；
 *   * 非圆屏返回 1f 当占位，真实宽度由 `geometry.rowWidth` 给"屏宽 − 左右各 3%"。
 */
@Composable
private fun rowWidthFraction(
    listState: LazyListState,
    itemKey: Any,
    listTopPx: Float,
    screenPx: Float,
    metrics: WatchMetrics,
    isRound: Boolean
): Float {
    if (!isRound) return 1f
    val fraction by remember(itemKey) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == itemKey }
                ?: return@derivedStateOf SQUARE_ROW_WIDTH
            // 这一行的中心落在屏幕高度的百分之几处
            val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx
            val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN
            (round(raw * 100f) / 100f)
                .coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)
        }
    }
    return fraction
}

/**
 * 顶部那一行的宽度比例（占屏宽）。
 *
 * **逐字照抄 `FunctionList.toolbarWidthFraction`**（`SavesScreen` / 本文件里各有一份副本）：
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
    /** `versionName`（`app/build.gradle.kts` 里现在写的是 `1.1`）。读不到就是 [UNKNOWN]。 */
    val versionName: String,
    /** `versionCode`（现在是 `3`）。读不到就是 [UNKNOWN]。 */
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
