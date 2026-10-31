// 手表 UI 会用到少量实验性 Compose 接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 让抽屉里的内容（函数列表 / 功能界面）能在**拖动过程中**驱动抽屉。
 *
 * ## 为什么要这个东西
 *
 * 用户 2026-10-17 的原话：
 *
 * > "函数列表和功能界面与函数界面切换就像一个抽屉拉回和拉出一样，
 * >  函数界面在下面，抽屉在上面，抽屉有吸附。"
 *
 * 关键词是"拉"—— 手指往下拖的时候抽屉就得跟着走，不能等松手才播一段动画。
 * 但手势的起点在**列表内部**（顶部那条下滑热区）和**图像界面底部**（上滑热区），
 * 而抽屉的位置在 [TabDeck] 里。跨层通信有两条路：改三个界面的参数表（要动
 * 十几个调用点），或者走 CompositionLocal（一行都不改调用点）。
 * 这里选后者：拿不到 controller 时各界面**自动退回老行为**（直接切路由），
 * 所以这两个界面单独拿出来用也不会坏。
 */
internal class DrawerController(
    /** 把抽屉拖到 [fraction]（0 = 收起，1 = 完全展开）。拖动中每帧调用。 */
    val dragTo: (Float) -> Unit,
    /** 手指抬起：按当前比例 + 速度决定吸附到哪一边。 */
    val release: () -> Unit,
    /**
     * 「手指碰到了函数界面底部那条提示条」—— 按下那一刻调用一次。
     *
     * 用户要求（3.27）：
     * > "把任何方式的重新绘制都删了，只留在函数界面手碰到下面的提示条的时候
     * >  重新绘制一帧。"
     *
     * 这是**全应用唯一**的毛玻璃重录入口。规则干净到只有一条：
     * 手指碰到提示条 → 重画一帧；除此之外任何路径都不重画。
     *
     * ⚠️ 不要把它挪进 `dragTo`：`dragTo` 是三个入口共用的
     * （列表顶部下滑、功能界面顶部下滑、图像底部上滑），
     * 放进去等于"任何拖动都重录"，又回到每滑一次抽一下的老毛病。
     */
    val freshBackdrop: () -> Unit,
    /**
     * 现在能不能推进「滑动条播放」？
     *
     * 用户要求：
     * > "把滑动条做成完全回到函数图像界面（提示条到底部）才开始播放，
     * >  在函数图像界面按到提示条就暂停播放，松手继续。"
     *
     * 所以判据只有两条，缺一不可：
     *   * 抽屉**完全收起** —— 用户真的站在函数图像界面上（不是列表 / 功能界面，
     *     也不是拖动或吸附途中）；
     *   * 手指没有按在底部那条提示条上。
     */
    val canPlay: () -> Boolean,
    /** 手指按下（true）/ 抬离（false）图像界面底部那条提示条 —— 播放的暂停开关。 */
    val setHintBarPressed: (Boolean) -> Unit
)

/**
 * 抽屉毛玻璃的「一帧」状态（3.27）。
 *
 * 用户原话：
 * > "把任何方式的重新绘制都删了，只留在函数界面手碰到下面的提示条的时候重新绘制一帧。"
 *
 * 所以这里只有一个 [request]：函数界面底部提示条被**手指碰到**的那一刻 +1，
 * 冻结层看到代次变化就重录一帧。别的什么都不重录 —— 抽屉收起、停稳、
 * 横滑、吸附、平移、缩放、改函数，一律复用同一张纹理。
 */
internal class BackdropFrameState {
    var request by mutableIntStateOf(0)
}

internal val LocalBackdropFrame = staticCompositionLocalOf<BackdropFrameState?> { null }

internal val LocalDrawerController = staticCompositionLocalOf<DrawerController?> { null }

/**
 * 当前是不是「液态玻璃」主题。
 *
 * 只用来决定**长相**（底色、描边、透明度），**不参与任何布局与交互** ——
 * 用户明确要求"只写 ui，不要动逻辑"，所以所有读取点都只在 `color =` 这一类
 * 视觉参数上分支，尺寸、顺序、手势一个都不改。
 *
 * 为什么用 CompositionLocal 而不是给每个组件加参数：
 * 列表行、设置行、卡片分散在三个文件里，逐层透传会把签名改得面目全非；
 * 而它是个纯粹的视觉开关，正好是 CompositionLocal 的用途。
 */
internal val LocalGlassTheme = staticCompositionLocalOf { false }

/**
 * 「模糊背景」当前是不是开着的（`WatchSettings.blurBackdrop`）。
 *
 * 3.29 新增。和 [LocalGlassTheme] 一样是个**纯视觉开关**、一样走 CompositionLocal
 * （读取点分散在列表页、功能界面、设置页…，逐层透传会把签名改得面目全非），
 * 但它管的是另一件事：**这一屏要不要自己画不透明底**。
 *
 * 开 = 抽屉背后有一张模糊位图当背板 → 各屏保持透明，让背板透上来；
 * 关 = 抽屉里只有一块纯色 → 各屏必须自己铺不透明底，否则底下那张
 *      清晰的函数图会透上来（表现为"滑动时列表和功能界面消失了"）。
 */
internal val LocalBlurBackdrop = staticCompositionLocalOf { true }

/**
 * 抽屉里那两屏（列表 / 功能界面）根节点的底色修饰符。
 *
 * 两屏自己都**没有**不透明底：模糊背景开着时，抽屉背后那张静态位图就是它们的背板。
 * 关掉模糊背景后，抽屉里只剩一块纯色，于是它们必须自己担起"这一屏的不透明底"——
 * 否则底下那张清晰的函数图会透上来，横滑翻页时看起来就是"页面在滑动时消失了"。
 *
 * ⚠️ 只在**关闭**时才挂实色底：开着的时候挂一层会把背板整个盖掉。
 *
 * 抽成函数而不是在调用点写 `if (...)`：`LocalBlurBackdrop.current` 是
 * `@Composable` 属性读取，写在 `Modifier` 的链式表达式里虽然合法（那一处
 * 仍在组合上下文），但读起来像普通取值，容易被后面的人当成可随意移动的表达式挪到
 * 非组合位置去。收进一个 `@Composable fun`，读点就只有这一个、位置也固定。
 */
@Composable
internal fun backgroundWhenNoBlur(): Modifier =
    if (LocalBlurBackdrop.current) {
        Modifier
    } else {
        Modifier.background(MaterialTheme.colors.background)
    }

/**
 * 模糊背板之上那层 tint 的浓度。
 *
 * **3.20 同款 0.10。** 3.34 曾经让它跟着页面描边一起换算（描边 0.30 时
 * tint 反而变浓），结果是模糊被压成一块色块，用户反馈"还是一样的"。
 * 3.35 渲染方式整个退回 3.20，这里也一并回到原值。
 *
 * 抽屉和非抽屉页（`GraphScreen`）两处读同一个常量，进出设置页时背景色不会跳。
 */
internal const val GLASS_BACKDROP_TINT = 0.10f

/**
 * 抽屉位移与手指位移的比例 —— **1.0 = 手到哪、抽屉就到哪**。
 *
 * 上一版这里是 `0.40`（手指走 40% 屏高、抽屉已经走完 100%），
 * 相当于把手指的位移**放大了 2.5 倍**。用户的反馈很准确：
 * "滑动的路程和实际手动的路程不一样……请你改成手到哪就划到哪。"
 *
 * 现在改成 1:1：手指上移多少像素，抽屉就上移多少像素。
 * 拉到 42% 屏高仍然会自动吸附展开 —— 所以并不需要真的从底滑到顶，
 * 但**在拉到那之前，每一像素都对得上**。
 *
 * 两头（图像界面上滑拉出、列表/功能界面下滑收回）用同一个数，
 * 保证同一个抽屉两个方向的手感对称。
 */
internal const val DRAWER_PULL_FRACTION = 1.0f

/**
 * 「抽屉算**完全收起**」的进度阈值。
 *
 * 和 `showDrawer` 里那个 `progress > 0.005f` 同一条线：0.005 屏高在任何手表上
 * 都不到 1 像素，视觉上早就贴底了。弹簧动画最后一段收敛得很慢，
 * 要求精确等于 0 会让"回到图像界面就开始播放"白等好几帧。
 */
internal const val DRAWER_CLOSED_EPS = 0.005f

/**
 * 松手时的"甩动"阈值（progress / 毫秒）。
 *
 * `progress` 是 0~1 的整屏行程，所以 0.0008 /ms ≈ **0.8 屏/秒** —— 也就是
 * 手指在一秒内能带动抽屉走完八成屏高的那种速度。达到它就按**方向**吸附：
 * 往上甩直接展开、往下甩直接收起，不再看已经拉了多少。
 *
 * 为什么不是更低：手表屏幕小、手指短，正常拖动很容易就到 0.5 屏/秒；
 * 阈值太低会让"轻轻拉一下又松手"也变成整屏切换，反而不受控。
 */
private const val DRAWER_FLING = 0.0008f

/**
 * 抽屉落位后等多久再让背景刷新（毫秒，1.2.42）。
 *
 * 用户的要求是"操作完毕 260ms 就刷新背景"，抽屉本身**不负责刷新**：
 * 拖动全程只写位移，落位后静默等这段时间，再请预糊产线补一张。
 * 手指已经离开、卡片已经铺满，这一次抓帧不会和任何手势抢主线程。
 */
private const val BACKDROP_SETTLE_DELAY_MS = 260L

/**
 * 模糊半径占屏幕短边的比例（**满档 = 100%**）。
 *
 * ## 3.42：0.075 → 0.22
 *
 * 0.075 是 3.20 那一档，当时糊度**是固定的**、没有滑条，所以"刚好"就行。
 * 现在它变成了 100% 的参照点，就必须保证"100% 是真的很糊"，
 * 否则滑条右半段整个没有意义（用户报的"70% 和没拨一样"）。
 *
 * ## 为什么是 0.22 而不是更大
 *
 * `ScriptIntrinsicBlur` 的半径被 [BLUR_MAX_RADIUS] = 25 卡死，换算下来
 * 屏幕半径的上限 ≈ `25 × 4 / density`。按 0.22：
 *
 * | 屏宽 | 50% | 70% | 80% | 90% | 100% |
 * |---|---|---|---|---|---|
 * | 233dp @2x | 6.4 | 12.6 | 16.4 | 20.8 | **25（满）** |
 * | 411dp @2x | 11.3 | 22.2 | 25 | 25 | 25 |
 *
 * 手表那条曲线从头到尾都在可分辨区间；更大的比例（0.26 / 0.30）会让
 * 手表也提前撞满，滑条右半段又变成"拨了没反应"。
 */
internal const val GLASS_BLUR_FRACTION = 0.22f


/**
 * 模糊半径的**下限**（dp）。
 *
 * 3.20 / 3.25 在真机上就是这个值在起作用：手表短边 233dp 时
 * `0.075 × 233 ≈ 17.5dp` 小于 20dp，实际用的是 20dp。
 * 要复现 3.20 的观感，这两个数必须一起照抄（只抄比例会糊得偏轻）。
 */
internal const val GLASS_BLUR_MIN_DP = 20f

/**
 * 毛玻璃主题下，非抽屉页（设置 / 编辑器 / 文件列表 / 应用信息）盖在
 * 背景上的那层半透明底的浓度。
 *
 * ## 3.35：回到 3.20 的 0.62
 *
 * 3.34 为了"让模糊透出来"把它降到了 0.30，用户装上后的反馈是
 * "还是一样的" —— 说明**浓度不是那个病根**。
 *
 * 这一版渲染方式整个退回 3.20（`Modifier.blur` 逐帧现糊），
 * 那么这个浓度也该跟着回到 3.20 的 0.62：那一版用户是认可的，
 * 现在没有理由再动它。两个数一起改回来，才是"和 3.20 一样"。
 *
 * ⚠️ 模糊背景**关掉**时这层不生效：关掉后这些页面背后垫的是
 * 实色底板（见 `GraphScreen`），再叠半透明底也只是原地加深，无副作用。
 */
/**
 * 1.2.47：**0**。
 *
 * 用户原话："现在函数列表的背景和文件界面、设置界面、函数创建界面的背景
 * 模糊程度不一样……改成全局都用函数列表界面的背景。"
 *
 * 函数列表那边看到的是 `GlobalBackdrop`（糊图 + 10% 压暗）；而这些整屏页面
 * 又各自盖了一层 62% 的底色 —— 同一张图被压得更暗更平，看起来就像"糊得不一样"。
 * 现在统一为 0：所有页面直接透出**同一张** `BackdropImage.image`，
 * 一个像素都不再叠。
 */
internal const val GLASS_PAGE_MASK_ALPHA = 0f

/**
 * 液态玻璃主题下，底层图像的模糊半径（dp）。
 *
 * 14dp 在 233dp 的表上能把曲线糊成柔和的光斑，但**看得出哪里有线** ——
 * 这正是用户要的"在函数列表界面就能看到模糊的函数"。
 * 调到 20 以上会在这么小的屏上糊成纯色块（什么也看不不出），
 * 调到 8 以下又像没擦干净。
 */





/** 抽屉收起状态下的动画时长感：不用数值，直接给弹簧参数。 */
private val SNAP = spring<Float>(
    dampingRatio = 0.88f,
    stiffness = 520f
)

/**
 * 三屏容器：**底层图像 + 上层抽屉**（用户要的那个交互模型）。
 *
 * ```
 * ┌──────────────────┐
 * │  抽屉：列表 ⇄ 功能界面（横向可滑）│  ← translationY = (1 - drawer) × 屏高
 * ├──────────────────┤
 * │  函数图像（始终在下面）          │
 * └──────────────────┘
 * ```
 *
 * ## 三种手势各管一件事
 *
 * | 手势 | 谁处理 | 效果 |
 * |---|---|---|
 * | 抽屉里**左右滑** | [HorizontalPager] | 列表 ⇄ 功能界面，**跟手**，翻过半页吸附 |
 * | 图像界面**底部上滑** | `GraphRoute` 的底部热区 | 抽屉**跟随手指**升起 |
 * | 抽屉顶部**下滑** | `FunctionListScreen` 的顶部热区 | 抽屉**跟随手指**落回 |
 *
 * 三者的方向互不重叠（横向 / 向上 / 向下），所以不需要仲裁；
 * 也正因为如此，这里**没有**在 `HorizontalPager` 外面再套一层纵向手势 ——
 * 那样会把列表的纵向滚动抢走。
 *
 * ## 吸附规则
 *
 * 松手时看两件事，满足任一就"拉到对面"：
 *   * **位置**：抽屉超过 42% 屏高；
 *   * **速度**：手指往那个方向的瞬时速度超过 900 px/s（快速一甩）。
 *
 * 都不满足就弹回原处。42% 而不是 50%：抽屉本身占满整屏，用户只要拉出小一半
 * 就已经盖住了图像的主体，继续拉是顺理成章的；反倒是"拉到一半又弹回去"更恼人。
 */
@Composable
internal fun TabDeck(
    route: Route,
    metrics: WatchMetrics,
    /**
     * 是否「液态玻璃」主题（`ThemeStyle.LIQUID_GLASS`）。
     *
     * **只管卡片材质**：半透明底色、更亮一档的分界线、行内高光。
     * 背后糊不糊由下面的 [blurBackdrop] 单独决定（3.29 起拆开）。
     */
    glass: Boolean,
    /**
     * 抽屉背后要不要透出**函数图的模糊版**（`WatchSettings.blurBackdrop`）。
     *
     * 和 [glass] **正交**：`glass` 管卡片材质（半透明面 / 高光边 / 行内高光），
     * 这个管背景糊不糊。四种组合都要成立，见 `WatchSettings.blurBackdrop` 的注释。
     *
     * 为 false 时抽屉里就是一块**不透明底色** —— 也就是"最开始没做模糊效果时"
     * 那个抽屉的观感（抽屉是实心的一张卡片，底下什么都看不见）。
     */
    blurBackdrop: Boolean,
    onRoute: (Route) -> Unit,
    graph: @Composable () -> Unit,
    tools: @Composable () -> Unit,
    list: @Composable () -> Unit
) {
    val expanded = route is Route.Tools || route is Route.List

    val scheme = MaterialTheme.colors



    /**
     * 抽屉进度（0 = 收起，1 = 展开）。
     *
     * ## 为什么不用 `animateFloatAsState`
     *
     * `animateFloatAsState` 内部是"`LaunchedEffect(targetValue, spec)` + Animatable"：
     * **target 每变一次就重启一次动画协程**。拖动时 target 每帧都在变，
     * 于是每帧都在取消/重启一个协程 —— 这和上一版手写 `scope.launch { snapTo }`
     * 是同一个毛病，只是换了个位置，所以"鬼畜"没消失。
     *
     * 现在改成：
     *   * **拖动**只写 [progress]（一个 Float state，零协程、零调度）；
     *   * **松手后**才用一个 `animate(...)` 逐帧把 progress 从当前值推到目标值，
     *     全程只有一个协程、不重建任何动画对象。
     *
     * 这就是"贴着手指走、松手才归位"的实现，中间没有任何一跳。
     */
    val progress = remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    /** 吸附目标（0 或 1）。只在松手 / 路由变化时被写。 */
    var target by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }

    /**
     * 手指**要求**的进度（1.2.47）。
     *
     * 背景还在刷新（[BackdropImage.refreshing]）时，抽屉不真的动，但要把
     * "手指现在到哪了"记下来；刷新一完成，下面的 `LaunchedEffect` 把这一格补上 ——
     * 于是"手快在刷新前就拉"的效果是：手先走、抽屉在刷新完成的那一拍追上来。
     */
    var gatedFraction by remember { mutableFloatStateOf(0f) }

    // ---- 1.2.47：背景刷新完，抽屉才补上手指要求的进度 ----
    //
    // 用户原话："如果手快在背景刷新之前就拉抽屉，抽屉就在背景刷新完才跟着手动；
    // 如果背景刷新完才拖动抽屉，没有影响。"
    //
    // [BackdropImage.refreshing] 由产线自己维护（请求 → true，产线空了 → false）。
    // 拖动中一直是 `dragTo` 在读它；这里只负责"刷新完成那一拍"把存下的进度写进去，
    // 所以手指停下不再产生事件时，抽屉也会自己追上来。
    LaunchedEffect(BackdropImage.refreshing, dragging) {
        if (!BackdropImage.refreshing && dragging) {
            progress.floatValue = gatedFraction
        }
    }

    // 吸附动画：逐帧写 progress，全程一个协程
    //
    // ⚠️ 1.2.43：这里**不再请求背景**。
    //
    // 用户的分工是"抽屉只负责切换界面，操作完毕 260ms 由预糊刷新背景"。
    // 之前在这里落位后要一张，等于每次开关抽屉都多付一次主线程抓帧，
    // 而且那一张还总是赶不上拉开的那一瞬间 —— 既卡又晚。
    // 现在抽屉一个字都不提背景（`GraphScreen` 的预糊负责）。
    LaunchedEffect(target, dragging) {
        if (dragging) return@LaunchedEffect        // 拖动中由手指直接写，不要动画
        if (progress.floatValue == target) return@LaunchedEffect
        animate(
            initialValue = progress.floatValue,
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 480f)
        ) { value, _ -> progress.floatValue = value }
    }

    // ---- 横向：列表 ⇄ 功能界面 ----
    // 页序沿用现有的空间关系：功能界面在**左**（page 0）、列表在右（page 1）。
    val pager = rememberPagerState(
        initialPage = if (route is Route.Tools) 0 else 1,
        pageCount = { 2 }
    )

    /**
     * 路由变化 → 抽屉 + 页码。
     *
     * ⚠️ 这里**只在抽屉展开那一瞬间**对齐一次页码，之后不再干预。
     * 上一版写的是 `LaunchedEffect(route)` 持续驱动 `animateScrollToPage`，
     * 它和"用户横滑"是同一条回路的两个方向 —— 一旦 `currentPage` 越中点、
     * 回调把 route 改掉，这个 effect 就会被重启并**抢走 Pager 的惯性**；
     * 更糟的是"功能界面 → 图像 → 再回功能界面"这种来回走法会把两边的状态
     * 拧在一起（用户报的"回不到函数列表"就是它）。
     *
     * 现在：`pager → route` 是**单向**的（下面那个 effect），
     * 这条只在展开时做一次对齐，且不打断正在进行的滚动。
     */
    LaunchedEffect(expanded) {
        if (!dragging) target = if (expanded) 1f else 0f
        if (expanded) {
            val page = if (route is Route.Tools) 0 else 1
            if (pager.currentPage != page && !pager.isScrollInProgress) {
                pager.scrollToPage(page)
            }
        }
    }

    /**
     * 页 → 路由（**唯一**同步方向）。
     *
     * 用 `settledPage` 而不是 `currentPage`：后者在滑动过半就变，
     * 会让 route 在手指还没松开时先跳一次（用户看到的"界面先换、动画后到"）。
     * settledPage 只在页面真正停稳后变，和视觉完全同步。
     */
    LaunchedEffect(pager.settledPage) {
        if (!expanded) return@LaunchedEffect
        val r = if (pager.settledPage == 0) Route.Tools else Route.List
        if (r != route) onRoute(r)
    }

    // ------------------------------------------------------------------
    // 松手速度（progress / 毫秒）
    //
    // 用户要求："抽屉切换再加个速度判定，如果松手的时候速度大于某个值就吸附过去，
    // 而不是吸附回来。" —— 也就是快速一甩应该**按方向**去对面，而不是看拉了多少。
    //
    // 为什么不用 velocityTracker：三个入口（图像底部、列表顶部、功能界面顶部）
    // 各自的手势闭包拿不到同一份指针历史，而 `dragTo` 本来就每帧被调用一次 ——
    // 在它里面用"progress 的变化量 / 时间差"估速度，三个入口自动统一，
    // 也不用改任何一处的函数签名。
    // 指数平滑是为了滤掉最后一两帧的抖动（真机上松手前常有一下小回弹）。
    // ------------------------------------------------------------------
    var lastDragAt by remember { mutableLongStateOf(0L) }
    var lastDragProgress by remember { mutableFloatStateOf(0f) }
    var dragVelocity by remember { mutableFloatStateOf(0f) }

    /**
     * 拖动 → 抽屉，松手 → 吸附。
     *
     * 拖动只写 progress（零协程）；松手把 dragging 翻回 false 并定 target，
     * 上面那个 `LaunchedEffect(target, dragging)` 会把弹簧跑完。
     *
     * 重录时机（3.27）见 [BackdropFrameState] 的 KDoc：全应用只有
     * "手指碰到函数界面底部提示条"这一处会重画一帧。
     */
    val frameHolder = remember { BackdropFrameState() }

    /**
     * 手指是否正按在图像界面底部那条提示条上。
     *
     * 只服务滑动条播放的暂停：按下 → 暂停，松手 → 继续（见 [DrawerController.canPlay]）。
     * 单独一份 state 而不是塞进 `dragging`：`dragging` 要等纵向拖动真的被认领
     * 才翻 true，而"按到提示条就暂停"发生在按下的那一刻。
     */
    val hintBarPressed = remember { mutableStateOf(false) }

    val controller = remember {
        DrawerController(
            canPlay = {
                !dragging &&
                    target == 0f &&
                    progress.floatValue <= DRAWER_CLOSED_EPS &&
                    !hintBarPressed.value
            },
            setHintBarPressed = { hintBarPressed.value = it },
            freshBackdrop = {
                // ---- 1.2.48：手指碰到提示条 = **最后一道保险** ----
                //
                // 用户原话："我是说背景没刷新抽屉就不动，等到背景刷新完背景就快速
                // 跟上手。一定要确保背景刷新。"
                //
                // 1.2.47 只拿 `refreshing` 当门是不够的：那段"变动后 100ms"的请求
                // 往往在手指碰到之前就已经做完（或从未发出，因为手势期间 `hold`
                // 把请求丢掉了），于是抽屉照旧立刻跟手、底下还是旧图。
                //
                // 现在这里当场补一次：
                //   * `hold` 先放开（调用方是在这之后才置位的，这里再兜一遍）；
                //   * 只要画面是"脏"的（`stale`），这一刻就发请求 ——
                //     请求一被接受 `refreshing` 立刻为真，`dragTo` 的门落下，
                //     抽屉原地不动，等 `publish` 完成那一拍再补上手指的进度。
                BackdropImage.hold = false
                if (BackdropImage.stale) {
                    BackdropImage.requestFor?.invoke(BackdropReason.OTHER)
                }
                frameHolder.request++
            },
            dragTo = { fraction ->                // 横向翻页正在进行时，忽略抽屉拖动。
                //
                // 斜向拖动时，纵向手势有可能先越过自己的容差而认领这一串
                // （Pager 在子层，抢不过）。那一下会把抽屉从 1 直接写到某个值，
                // 表现为"滑得好好的突然跳到另一屏、没有动画"。
                // 这里再兜一层：只要 Pager 在滚，就完全不响应抽屉。
                // 1.2.44：抽屉**收着**的时候完全不看 Pager。
                //
                // 用户原话："手拉抽屉要过零点几秒抽屉才能跟上手。"
                //
                // 这就是那"零点几秒"：以前只要 `pager.isScrollInProgress` 为真
                // （上一次翻页的惯性/吸附动画还没彻底停），`dragTo` 就把手指的
                // 位移**全部丢掉**，等它停下来的那一拍抽屉才突然跳到当前位置。
                // 抽屉收起时手里根本没有翻页手势要保，没理由让位。
                if (target < 0.5f ||
                    !pager.isScrollInProgress ||
                    abs(pager.currentPageOffsetFraction) < 0.02f
                ) {
                    dragging = true
                    val v = fraction.coerceIn(0f, 1f)
                    // 手指"要求"的进度总是记下来（哪怕这一拍不写）
                    gatedFraction = v

                    // ---- 1.2.47：背景还没刷新完 → 这一拍不动 ----
                    //
                    // 用户原话："如果手快，在背景还没刷新的时候就拉动抽屉，
                    // 背景就还是松手才刷新，你做成在背景刷新完的时候抽屉才会跟手动。"
                    //
                    // 只跳过写入：`dragging` 已经为真，上面的
                    // `LaunchedEffect(refreshing, dragging)` 会在刷新完成那一拍
                    // 把 `gatedFraction` 补进 `progress`，抽屉于是"追上来"。
                    if (!BackdropImage.refreshing) {
                        val now = System.nanoTime() / 1_000_000L
                        val dt = now - lastDragAt
                        // 只采信 1~60ms 的间隔：太小是噪声、太大是停顿（不该算速度）
                        if (dt in 1..60) {
                            val instant = (v - lastDragProgress) / dt.toFloat()
                            dragVelocity = dragVelocity * 0.55f + instant * 0.45f
                        } else if (dt > 60) {
                            dragVelocity = 0f
                        }
                        lastDragAt = now
                        lastDragProgress = v

                        progress.floatValue = v
                        // ⚠️ 3.25：不再在拖动中写 `target`。
                        // `target` 是 LaunchedEffect(target, dragging) 的 key，拖动中
                        // 每帧写一次 = 每帧取消并重启一条协程；真机上表现为拖动
                        // 一顿一顿（用户录屏里的"抽搐"）。吸附动画只由 release()
                        // 设 target，起点用 progress 当前值，本就不需要中途写。
                    }
                }
            },
            release = {
                dragging = false
                // 1.2.47：手离开时背景可能还在刷新 —— 那时 `progress` 没被写过，
                // 位置要用手指**要求**的那一格，否则会被当成"没拉动"而弹回关闭。
                if (BackdropImage.refreshing) progress.floatValue = gatedFraction
                // 先看速度，再看位置（快速一甩时位置往往还没过半）
                val open = when {
                    dragVelocity > DRAWER_FLING -> true      // 快速往上甩 → 展开
                    dragVelocity < -DRAWER_FLING -> false    // 快速往下甩 → 收起
                    else -> progress.floatValue > 0.42f      // 慢速 → 按位置吸附
                }
                dragVelocity = 0f
                target = if (open) 1f else 0f
                onRoute(
                    if (open) {
                        if (pager.settledPage == 0) Route.Tools else Route.List
                    } else {
                        Route.Graph
                    }
                )
                // ---- 背景：这里也不再补图（1.2.41）----
                //
                // 上一版在这里给"按下没取成"兜底。可松手那一刻补的图，
                // 发布时抽屉已经铺满 —— 用户看到的就是"松手才刷新"，
                // 而且新图上场要先垫一帧旧图。预糊（`GraphScreen`）已经保证
                // 抽屉关着的时候图就是最新的，这里不再动它。
            }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // 离散可见性：只在这两个布尔翻转时重组（不是每帧）
        // ⚠️ key 必须带上 glass 和 expanded：`derivedStateOf` 放在无 key 的
        // `remember {}` 里会**捕获首次组合时的值**，之后切换主题 / 开关抽屉
        // 都不会重算 —— 表现为"切到液态玻璃后列表一片空白"。
        val showGraph by remember(glass, blurBackdrop, expanded) {
            derivedStateOf {
                // **模糊背景开着时，底层图像永远保留。**
                //
                // 关掉时抽屉一铺满就把画布撤掉（省电）—— 这和最初那个抽屉的行为一致。
                // 开着时不能撤：抽屉的全部观感来自"透过半透明底看到背后被模糊的东西"，
                // 底下什么都没有，抽屉就是一块纯色板（用户说过的"在函数列表界面
                // 看不到模糊的函数"就是它）。
                //
                // 而"开着"时也不做"铺满就撤"：撤掉的那一刻屏幕内容会跳一下
                // （哪怕只露出 0.5%，也会被眼睛抓到"底下那块没了"）。
                // 用户明确不要这类过渡上的小动作 —— 那就让它稳定地一直都在。
                // 代价是多绘制一份被遮住的画布，这是模糊背板既定要付的账。
                //
                // ---- 3.41：不管开关怎么变，画布都留着 ----
                //
                // 3.29 这里写的是 `if (blurBackdrop) true else (未铺满)` ——
                // 也就是"关掉模糊背景、抽屉一铺满就把画布撤掉"（当时为了省电）。
                //
                // 那条优化正好造成用户这轮报的现象：**设置里关掉模糊背景 →
                // 画布被移出组合树 → 再打开时取图，图层是空的**，
                // 于是"打开模糊背景 / 调模糊程度之后不重绘"，
                // 而且新打开时第一张背景永远出不来，要等下次碰提示条。
                //
                // 现在画布常驻：它被抽屉盖住时不消耗卷积（背景是静态图），
                // 省下的那点绘制远不如"开关随点随生效"重要。
                true
            }
        }
        // ---- 1.2.46：抽屉内容**常驻组合**（这是"跟手"的关键） ----
        //
        // 用户原话："保证手一碰到提示条，抽屉就跟着动。"
        //
        // 以前这里是 `expanded || progress > 0.005f`：抽屉收起时内容不在组合树里。
        // 手指刚动一像素，Pager + 两整页（功能界面 / 函数列表）要**现场组合一遍**，
        // 那一帧在手表上就是几百毫秒 —— 手指已经在走、抽屉还钉在原地，
        // 等它组合完才"跳"上来。这就是用户反复说的"要过零点几秒才跟上手"。
        //
        // 现在内容一直在树里（只是被 `translationY` 推到屏幕外），
        // 第一个移动事件只改一个浮点数 → 下一帧就跟着走，中间没有任何组合开销。
        val showDrawer = true

        // 抽屉/玻璃这两个 CompositionLocal 要一直提供（3.24 起底图层常驻，
        // 不能跟 `showDrawer` 分支一起进出）。
        // 重录时机（3.27）：全应用只有"手指碰到函数界面底部提示条"一处会重录，
        // 常驻的毛玻璃层与手势入口共用同一份 [frameHolder]。
        val frameState = frameHolder

        CompositionLocalProvider(
            LocalDrawerController provides controller,
            LocalGlassTheme provides glass,
            LocalBlurBackdrop provides blurBackdrop,
            LocalBackdropFrame provides frameState
        ) {

            if (showGraph) {
                // 底层就是图像界面本身：不模糊、不改透明度、不做任何过渡。
                // 抽屉收起时它是主角，升起后被盖住。
                //
                // ---- 3.39：这一屏要有**自己的不透明底** ----
                // 用户："这个背景是要当做全局背景，但是不要当函数图像界面的背景。"
                // `GraphCanvas` 只画网格/曲线/对象、没有底色 —— 以前根层那张模糊图
                // 会从它底下透出来，等于"模糊图当了图像界面的背景"。垫一块主题
                // 底色后，模糊背景只服务抽屉、玻璃页和保存浮窗。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                ) {
                    graph()
                }
            }

            // ---- 抽屉底下那层：两种观感，二选一 ----
            //
            // `blurBackdrop = true`  → 那张静态模糊图（3.28 的观感，现在是可选项）。
            // `blurBackdrop = false` → 一块**不透明底色**（最开始那个抽屉：
            //                          实心卡片，拉开时看不见底下任何东西）。
            //
            // ⚠️ 这一层**无条件组合**（不看 glass、不看 blur）。它是抽屉的"背板"，
            // 少了它抽屉就是透明的，底下那张函数图会直接透上来 ——
            // 横滑翻页时页面在动、底图不动，看起来就是"列表和功能界面在滑动的时候
            // 消失了"（用户报的 bug）。Material 主题以前不会这样，是因为它那时
            // 压根没有"抽屉"这层结构；改成抽屉模型之后，两屏的根节点都没有不透明底，
            // 于是这个洞就露出来了。
            //
            // 两种模式画的东西不同，但**都必须铺满整屏、都必须挡住底层图像**。
            GlassBackdropLayer(
                progress = { progress.floatValue },
                screenH = screenH,
                blurred = blurBackdrop
            )

            if (showDrawer) {
                // 抽屉的"卡片感"：顶部圆角 + 一条浅色分界线（用户要求）
                val corner = RoundedCornerShape(
                    topStart = metrics.sizeDp * 0.075f,
                    topEnd = metrics.sizeDp * 0.075f
                )
                Box(modifier = Modifier.fillMaxSize()) {

                // ---- 抽屉内容：跟手移动 + 圆角 + 两页 ----
                //
                // 页面**只负责内容**：不透明底和模糊图都搬到了上面那个固定层，
                // 页面自己是透明的。于是：
                //   * 横滑时内容在动、背景不动（用户要的"蒙版不跟着页面动"）；
                //   * 内容行的半透明玻璃面直接透出固定层的模糊图。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 位移走 `graphicsLayer`：只失效图层、不触发重排。
                        .graphicsLayer {
                            translationY = (1f - progress.floatValue) * screenH
                        }
                        // 圆角裁切放在位移之后：抽屉升起时顶部两个角是圆的，
                        // 和底下那张图像之间因此有一条清楚的边界。
                        .clip(corner)
                ) {
                    HorizontalPager(
                        state = pager,
                        // ---- 撤销菜单展开时锁住横滑 ----
                        //
                        // 用户原话："设置键划向加号键不能移动界面" —— 两键之间横划
                        // 是呼出撤销菜单的手势，这一串必须**不能**同时把 Pager 翻页。
                        // `activeToolbarMenuOpen` 由 `rememberToolbarUndoMenu` 维护
                        // （展开即真、收起即假），这里读它即可。
                        userScrollEnabled = expanded && !activeToolbarMenuOpen.value,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        // ⚠️ 页面**铺满整屏**：不内缩、不切圆角。
                        //
                        // 之前为了"看得出两张卡片在交错"加了 8dp 内缩 + 18dp 圆角，
                        // 但用户要的是整屏界面 —— 卡片边缘会让列表/功能界面看起来
                        // 没有铺满（用户原话："怎么做成铺不满屏幕的卡片了？请改回铺满整屏"）。
                        // 玻璃的位移感由模糊内容本身承担，不需要靠卡片的边来提示。
                        Box(Modifier.fillMaxSize()) {
                            if (page == 0) tools() else list()
                        }
                    }

                    // ---- 顶部分界线：抽屉与图像之间那条浅色边 ----
                    // 1dp 的实线，颜色用 outlineVariant（日夜两套各自成立），
                    // 两端被圆角自然裁短，看起来就是一张浮起来的卡片。
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                if (glass) {
                                    // 玻璃的"高光边"：比普通分界线亮一档，
                                    // 模拟光在玻璃上沿的反射
                                    MaterialTheme.colors.onSurface.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colors.outlineVariant
                                }
                            )
                    )

                    // ---- 底部指示点（Material 风格 + 系统桌面的"吸附"）----
                    //
                    // 用户要求："把函数列表界面和功能界面两个界面左边和右边的提示条删了，
                    // 改成在屏幕底部显示提示点。"
                    //
                    // 1.2.63 新增（用户原话："你给它们做个平滑的切换，就是界面滑到一半的时候
                    // 两个点会有一点点吸附的效果，很多手机系统的桌面下面的小点都有这个效果"）：
                    //
                    // 原来两颗点只看 `pager.currentPage`，翻页**过了一半才啪一下**跳变，
                    // 和手指完全脱节。现在按 Pager 的**连续进度**
                    // `pagePos = currentPage + currentPageOffsetFraction` 给每颗点算一个
                    // 激活度 `t`：
                    //   * 离目标页 0.35 页以内 → `t = 1`（已经完全吸住）；
                    //   * 0.35 ~ 0.65 页 → smoothstep 过渡（"吸过去"的那一下）；
                    //   * 0.65 页以外 → `t = 0`。
                    // 中间那段**平台区**就是"一点点吸附"的来源：滑到一半时点已经提前
                    // 吸到目标的位置/大小，而不是线性地跟手指走。
                    if (expanded) {
                        val pagePos = pager.currentPage + pager.currentPageOffsetFraction
                        val deadZone = 0.35f
                        val tail = 0.65f
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = metrics.sizeDp * 0.028f),
                            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.024f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(2) { index ->
                                val d = kotlin.math.abs(pagePos - index)
                                val raw = 1f - ((d - deadZone) / (tail - deadZone))
                                    .coerceIn(0f, 1f)
                                // smoothstep：两端缓、中间快，看起来是"吸"而不是"滑"
                                val t = raw * raw * (3f - 2f * raw)
                                val diameter = metrics.sizeDp * (0.020f + 0.010f * t)
                                val idle = MaterialTheme.colors.onSurfaceVariant
                                    .copy(alpha = 0.38f)
                                Box(
                                    Modifier
                                        .size(diameter)
                                        .clip(CircleShape)
                                        .background(lerp(idle, MaterialTheme.colors.primary, t))
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 常驻的毛玻璃底图层（3.36：BlurView）
// ---------------------------------------------------------------------------

/**
 * 抽屉背后那片毛玻璃。
 *
 * ## 为什么不是 `Modifier.blur`（3.36 的关键决定）
 *
 * 用户的设备是 **Android 11 的手表**。`Modifier.blur` 背后是 RenderEffect，
 * 从 **API 31（Android 12）** 才有 —— 在 Android 11 上它**是空操作**，
 * 一个像素都不会糊。
 *
 * ## 3.37 起：贴一张**全局静态图**（见 [GlobalBackdrop] / [BackdropImage]）
 *
 * 3.36 用的是 BlurView 的"每帧自动更新"，观感对了但**抽搐**：拖动、翻页、
 * 吸附动画期间它每帧都重新抓整屏 + 重新卷积，帧率直接被卷积吃掉。
 *
 * 现在这一层只做三件事：
 *
 * | 事项 | 做法 |
 * |---|---|
 * | 观感 | 图里就是**糊好的当前画面**（函数图、网格、标注都在） |
 * | 静止 | 拖动/翻页/转场期间背景一动不动 —— 这是不抽搐的根本原因 |
 * | 什么时候换 | 只有"手指碰到提示条"那一刻重做一帧（`freshBackdrop` → `BackdropImage`）|
 *
 * 以前那套"抓帧 → 降采样 → CPU 卷积 → 存单例 → 画位图"整条链路（3.20 前）已删，
 * 3.36 的"每帧实时糊"也已删。
 *
 * ## 两条分支
 *
 *   * [blurred] = true  → 静态模糊图 + 10% 压暗；
 *   * [blurred] = false → **只有一块不透明底色**（"最开始那个抽屉"）。
 *
 * 两条都必须挡住底下的函数图 —— 这是抽屉的背板，漏了就会"滑着滑着页面没了"。
 *
 * @param progress 读抽屉进度（**只能读**，不写），用来算裁剪顶边。
 * @param screenH 整屏高度（px），裁剪几何用。
 * @param blurred 背后要不要模糊。false = 实心底色。
 */
@Composable
private fun GlassBackdropLayer(
    progress: () -> Float,
    screenH: Float,
    blurred: Boolean
) {
    val scheme = MaterialTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 裁剪在 draw 阶段读 progress：只失效绘制、不触发重组。
            // top = H - p·H —— 抽屉从底部往上长，它占的是下半段（3.22 踩过的坑）。
            .drawWithContent {
                val visible = progress() * screenH
                val top = maskTopPx(screenH, visible)
                // ⚠️ 抽屉完全收起时（progress = 0）top == 屏幕高度，这一层**一点都
                // 不该露出来**。3.29 起这一层改成无条件组合（material 主题下也在），
                // 于是"图像界面"这种 progress 恒为 0 的情形变成常态 ——
                // 零高度（甚至 top > bottom）的 clipRect 在部分设备的 RenderNode 上
                // 会被判成非法矩形，是"点图标立刻退回桌面"那种启动崩溃的典型来源。
                if (visible <= 0f || top >= size.height) return@drawWithContent
                clipRect(0f, top, size.width, size.height) {
                    this@drawWithContent.drawContent()
                }
            }
    ) {
        // ① 不透明底：挡住更底层的清晰函数图（用户："层次放在蒙版下面"）。
        Box(
            Modifier
                .fillMaxSize()
                .background(scheme.background)
        )

        if (blurred) {
            // ② 全局静态背景图（3.37）。
            //
            // 3.36 这里挂的是 BlurView 本身：它每帧都重新抓整屏、重新卷积一遍，
            // 于是拖动/翻页/吸附全程都在算 —— 用户看到的"抽搐"就是它。
            //
            // 现在改成：只**贴**一张已经糊好的图（`BackdropImage`），
            // 这张图只在"手指碰到提示条"那一刻重做一帧（见 `freshBackdrop`）。
            // 拖动、翻页、转场期间背景是静止的，零计算。
            //
            // ⚠️ 这一层必须**在内容之前**画（当前就是），否则它会盖住抽屉本身。
            // 抽屉升起时它被压缩在可见区域里（见上面的 clipRect），
            // 所以"只糊下半屏"这件事仍然成立。
            GlobalBackdrop(
                modifier = Modifier.fillMaxSize(),
                overlayAlpha = GLASS_BACKDROP_TINT
            )
        }
    }
}

/**
 * 蒙版顶边的 y（px）：屏幕上沿到"抽屉顶边"的距离。
 *
 * - [visibleHeightPx] = 0   → 返回 screenH（整块都被裁掉）
 * - [visibleHeightPx] = screenH → 返回 0（整屏都显示）
 *
 * 单独的纯函数是为了能写 JVM 单测（[MaskGeometryTest]）把边界钉死，
 * 避免"除以 0 / 溢出 / 反向"这类低级错误再回到界面上。
 */
internal fun maskTopPx(screenH: Float, visibleHeightPx: Float): Float =
    (screenH - visibleHeightPx).coerceIn(0f, screenH)

/**
 * 只保留屏幕底部 [visibleHeightPx] 高的区域，其余裁掉。
 *
 * 用矩形 Outline（不是 Path）：RenderNode 对矩形裁剪有直接支持，
 * 不需要每帧分配 Path 对象。
 */
internal class BottomRevealShape(private val visibleHeightPx: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val top = maskTopPx(size.height, visibleHeightPx)
        return Outline.Rectangle(Rect(0f, top, size.width, size.height))
    }
}
