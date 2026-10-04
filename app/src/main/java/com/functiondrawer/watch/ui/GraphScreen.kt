// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.functiondrawer.watch.engine.CallableSet
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.Plotter
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.FunctionDrawerTheme
import com.functiondrawer.watch.ui.theme.PillShape
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.min

/**
 * 应用外壳 + 各屏的路由。
 *
 * ## 导航结构（真机反馈后改过）
 *
 * ```
 * 函数列表（首屏，左上角 ⚙ 设置、右上角 + 新建）
 *    ├─ 点某一行      → 函数编辑器（改表达式）
 *    ├─ 长按某一行    → 属性编辑界面（#4：名称 / 线型 / 线径）
 *    ├─ 长按滑动条行  → 滑动条属性界面（#4：名称 / 下界 / 上界 / 步长）
 *    ├─ 从行右侧左滑  → 删除（底部浮出撤销条）
 *    ├─ 点 +          → 新建函数并进编辑器
 *    ├─ 长按 +        → 保存函数文件：弹命名浮窗，**浮窗底下垫的是模糊的函数图像**
 *    │                   （见下面"保存浮窗的底图"那一段 —— 那一条路**不走路由**，
 *    │                    所以关掉浮窗之后用户还在这一屏，不会跑去看图）
 *    └─ 上/下滑       → 图像界面 ⇄ 列表
 * 设置（每行点一下切到下一个取值）
 * ```
 *
 * 图像界面**只有一颗悬浮按钮**：原来顶部是「函数列表」、底部是「编辑」，
 * 用户要求合并成一颗放底部，所以现在底部那颗就是回列表 —— 编辑改从列表行进入，
 * 保证从图像回列表之后仍然能编辑（否则就没有入口了）。
 *
 * ## 表冠为什么接了三套输入
 *
 * 「原屏手表」这个品类的表冠上报方式完全不统一。实测 OPPO 表的表冠设备
 * `oplus_crown` 只有 `REL_WHEEL` 能力，走的是 `AXIS_VSCROLL`；标准 Wear
 * 编码器走 `AXIS_SCROLL`；还有机型把表冠映射成按键。三条路都接上，
 * 全部收敛到同一个 [GraphState.zoomByFactor]。
 */
@Composable
fun GraphScreen(
    rotary: Flow<Float>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val state = remember {
        GraphState().also { s ->
            Store.load(context)?.let { saved ->
                s.restore(
                    functions = saved.functions,
                    selectedId = saved.selectedId,
                    centerX = saved.centerX,
                    centerY = saved.centerY,
                    unitsPerPixel = saved.unitsPerPixel
                )
                s.restoreSettings(saved.settings)
                s.restoreSliders(saved.sliders)
            }
        }
    }

    FunctionDrawerTheme {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            val sizePx = min(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val sizeDp = with(density) { sizePx.toDp() }
            val metrics = remember(sizePx, sizeDp.value, density) {
                WatchMetrics(sizeDp = sizeDp, sizePx = sizePx, density = density)
            }
            // 真实屏幕几何（**整个屏幕**的宽高，不是那个"短边"）。
            //
            // 为什么这一层需要它：`metrics.sizeDp` 是**短边**，圆表上它就是屏宽，
            // 一切按"屏占比"算的位置/宽度都直接用它；但手机是**竖长**的 ——
            // 短边是宽度，于是"0.10 屏高"算出来只有 41dp（顶到状态栏上），
            // 那些浮层在手机上就变成了屏中央的一根窄柱 + 贴顶的位置。
            // 和「函数列表」「功能界面」两屏共用同一个 `ScreenGeometry`
            // （它在 `FunctionList.kt` 里，非圆屏那一支会铺满屏宽减左右余量）。
            val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
            /**
             * 这一屏是圆屏还是非圆屏（手机 / 方表）。
             *
             * 用户本轮定了一条**长效规矩**："以后你写所有按键都要加上对屏幕宽度的自适应。"
             * 也就是"宽度按真实可用宽度自适应、不许再写短边比例"（`metrics.sizeDp` 是
             * **短边**：圆表上短边 == 屏高所以一直看不出来，手机上位宽 411dp 而短边也是
             * 411dp，于是按弧宽公式算出来的浮层就成了屏中央一根窄柱 + 两边空一片）。
             *
             * 两个宽度换算的**唯一去处**是 `MathKeyboard.bandWidth(...)`
             * （圆屏弧宽 / 非圆屏满宽那一支，和数学键盘、属性屏各栏共用同一份），
             * 而它要的 `isRound` 只能从 `LocalConfiguration` 读 —— 所以在这里读一次，
             * 下面两处浮层（toast / 两类对话框）都复用它。
             */
            val isRound = LocalConfiguration.current.isScreenRound

            LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
                state.attachSurface(
                    constraints.maxWidth.toFloat(),
                    constraints.maxHeight.toFloat()
                )
            }

            val functions = state.functions
            val viewport = state.viewport
            val quality = state.quality
            val settings = state.settings
            val sliders = state.sliders

            /**
             * **撤销条现在会不会显示**（本轮从下面 `FunctionListScreen(showUndo = …)` 那一行
             * 提上来，变成两处共用的一个答案）：
             *
             *   ① 第一屏那条「已删除 · 点此撤销」自己（`showUndo`）；
             *   ② **toast 的落点** —— 撤销条在时，toast 必须落在它**上方**
             *      （用户本轮明确要求"移动后别和撤销条叠在一起"）。
             *
             * 两处读同一个值，"会不会撞在一起"就不再靠两处各自猜：只要这一份是真的，
             * 下面 `toastBottomPad(...)` 就会把 toast 抬到撤销条之上。
             */
            val undoVisible = state.lastDeleted != null || state.canUndoClear ||
                state.showObjectsUndo || state.lastDeletedSlider != null

            // ---- 把它自己从系统手势里排除出去（需求 #6，设置里那个开关）----
            //
            // 机制：Android 10（API 29）起，`View.setSystemGestureExclusionRects`
            // 可以告诉系统"这块矩形里的手势别自己吃掉、交给应用"。我们传一个
            // **覆盖整个窗口**的矩形，等于"这个 App 里所有边缘手势都归自己管"。
            // 关掉这个开关就传空表，恢复系统默认行为。
            //
            // **版本守卫（本 App 下限已降到 Android 7.0 / API 24）**：
            // `get/setSystemGestureExclusionRects` 都是 API 29 才有的（本地
            // `platforms/android-34/data/api-versions.xml` 里写着 `since="29"`），
            // 在 24~28 上调它会直接抛 `NoSuchMethodError` 崩掉 —— 所以这里必须判版本。
            // 低版本**没有任何等价的公开 API**（那是 Android 10 才引入的能力），
            // 所以退化行为就是**完全不做任何事**（最安全的默认：回到系统原本的手势行为）：
            //   * 这里直接返回，一次都不调用；
            //   * 设置页里那一行**不显示**；
            //   * 设置的值在模型层已经被 `WatchSettings.coercedForThisDevice` 压成 false。
            // 三件事读的是同一张表（`GatedSetting` / `supportsSetting`），不会走散。
            //
            // 手表上它挡的是哪一类手势：系统把手表**屏幕边缘的上滑 / 侧滑**
            // 留给了"回表盘 / 换表盘 / 通知抽屉"这类全局动作，而本 App 恰好
            // 也有两条边缘手势 —— 图像界面底部上滑回函数列表、函数列表左边缘
            // 右滑进功能界面。不排除掉的话，用户想上滑回列表，手指一过底边
            // 就被系统直接踢回表盘，看起来就是"这个 App 的返回手势时灵时不灵"。
            //
            // 用 `LocalView.current` 拿根 View（Compose 没有"窗口"对象，
            // 根 View 就是窗口），尺寸变化时重设一次 —— 旋转/表盘裁切都会改宽高。
            val rootView = LocalView.current
            LaunchedEffect(
                settings.blockSystemGestures,
                constraints.maxWidth,
                constraints.maxHeight
            ) {
                if (!supportsSetting(GatedSetting.BLOCK_SYSTEM_GESTURES)) {
                    // Android 10 以下：这个能力不存在，什么都不做（不是"关掉"，是"没有"）。
                    // 门槛值取自 GraphState 里那张统一的表（`supportsSetting`）——
                    // 设置页显不显示、模型压不压值、这里做不做，读的是**同一个数**。
                    return@LaunchedEffect
                }
                rootView.systemGestureExclusionRects = if (settings.blockSystemGestures) {
                    // 优先用根 View 自己的宽高（那才是"窗口"）；
                    // 万一拿到的是 0（还没量过），退回 BoxWithConstraints 给的约束 ——
                    // 传一个 0×0 的矩形等于没排除，用户会以为"这个开关坏了"。
                    val w = if (rootView.width > 0) rootView.width else constraints.maxWidth
                    val h = if (rootView.height > 0) rootView.height else constraints.maxHeight
                    listOf(Rect(0, 0, w, h))
                } else {
                    emptyList()
                }
            }

            // ---- 保存**函数文件**（长按 `+`）----
            //
            // 上一版这里是"把当前图像存成 PNG"，这一轮用户把它改成了
            // "保存着所有函数信息的文件"（`.fdf`，见 `FunctionFileFormat`）。
            //
            // 和存图相比少一个坑：**不用真的跳到图像界面**。存图必须跳过去，
            // 因为要在画布上抓图层（那时画布还没组合）；而函数文件存的是
            // "表达式/颜色/线型/视口/几何对象"这些状态，和画布有没有画出来无关 ——
            // 所以这里**不切路由**，用户不会被"先看一眼图"打断。
            // （`ImageExport.kt` 与 `rememberGraphCapture` 都留着没删：
            //  将来要恢复"存图"就是把这个对话框再接一条路上去。）
            //
            // ⚠️ 注意区分两件事（用户这一轮专门纠偏过）：
            //   * **不切路由** = 用户没被带到图像界面去，关掉浮窗还在原来那一屏；
            //   * 但浮窗**底下垫的那张画**是**模糊的函数图像**（见下面
            //     "保存浮窗的底图"那一段）—— 那是用户明确要求的观感，
            //     和"跳到图像界面"是两回事，别把它当成多余的底图删掉。
            var saveDialogVisible by remember { mutableStateOf(false) }
            var savingFile by remember { mutableStateOf(false) }
            /** 命名对话框里那行红字（"名字不能为空"/"已有同名文件"），null = 没问题。 */
            var saveError by remember { mutableStateOf<String?>(null) }
            val graphCapture = rememberGraphCapture()
            val saveScope = rememberCoroutineScope()

            // ---- 设置的"草稿"机制 ----
            //
            // 用户要求：坐标系的切换要**退出设置之后才生效**，而且要考虑到
            // "进去又切回原坐标系"的情况（那种情况什么都不该发生）。
            // 所以设置页改的是一份草稿，退出时再决定：直接生效 / 弹确认框。
            var settingsDraft by remember { mutableStateOf<WatchSettings?>(null) }
            var pendingCoordSwitch by remember { mutableStateOf<WatchSettings?>(null) }

            fun commitSettings() {
                val draft = settingsDraft
                settingsDraft = null
                when {
                    draft == null || draft == settings -> state.route = Route.List

                    // 只动了显示开关，或者坐标系切回了原来那个 → 直接生效，不用问
                    draft.coordinateSystem == settings.coordinateSystem -> {
                        state.updateSettings(draft)
                        state.route = Route.List
                    }

                    settings.confirmCoordSwitch -> pendingCoordSwitch = draft

                    else -> {
                        state.updateSettings(draft)
                        state.route = Route.List
                    }
                }
            }

            // 采样放到后台线程：隐函数在高精度下要做几万次求值，
            // 在主线程做会直接卡住手势。
            //
            // **key 只取"采样相关字段"**：id / 模式 / 表达式 / 量程 / 可见 / 度制 / **名字**。
            // 颜色、线型、线径是"外观"，由 GraphCanvas 每帧实时读
            // （见那边的 CurveStyle），改它们**不该**重新采样。
            // 原来这里的 key 是整个 functions 列表，于是拖"线径"滑动条时每一小步
            // 都会重采样一次 —— 隐函数高精度要几万个采样点，手表上会明显掉帧。
            // （这个坑是属性编辑界面那位同事拖线径时发现的：曲线跟着每一步在重算。）
            //
            // **名字（`it.name`）现在是采样相关字段**（用户报的 bug 那一轮加的）：
            // 表达式里可以调用别的用户函数（`b(x)=2a(x)`），而"能不能调用"取决于
            // **别人的名字与模式** —— 把 `a(x)` 改名成 `c(x)`、或者把一条极坐标
            // 曲线切成显函数，都会让本函数的采样结果变化。
            // 这张表是 map 出来的，所以任何一个函数改表达式/量程/度制也都会变 key；
            // 只有颜色/线型/线径（纯外观）被刻意排除在外。
            val samplingKey = functions.map {
                listOf<Any?>(
                    it.id, it.name, it.mode, it.src1, it.src2,
                    it.tMin, it.tMax, it.visible, it.degrees
                )
            }

            LaunchedEffect(
                samplingKey,
                viewport,
                quality,
                settings.angleUnit,
                sliders,
                // **几何对象也是采样输入**（本轮加）：表达式里写 `x(A)` / `y(A)` 时，
                // 算出来的曲线取决于点 A 现在的坐标（见 GraphState.pointCoordinates）。
                // 不把它列进 key，删掉/新描一个点、或者点被改动之后，图不会重画 ——
                // 那正是"看起来好了、其实还是旧曲线"最容易被放过的那种 bug。
                //
                // **标量值（`f*x` 里的 `f`）也走同一把钥匙**（"标量能直接写进表达式"
                // 这一条需求）：度量对象就住在 `objects` 里，它的 `value` 是采样前
                // 由 `GraphState.scalarValues()` 折成数值的。所以链路是
                //     点/几何对象变化 → state.objects 变 → 这里重采样 → 标量取新值
                // —— **不需要**再为标量单独加一个 key（比如把某个 value 也列进来），
                // 加了反而是重复条件，容易让人以为"标量不走 objects"。
                state.objects,
                // **名字绑定表也是采样输入**（用户需求："给函数编辑界面输入的这些字母
                // 单独分组编 id"）：用户在编辑器里点一下"`a` 指的是那个距离"，
                // 采样时 `a` 折成的数就换了人 —— 不把这张表列进 key，点完候选条
                // 图不会重画，看起来就是"点了没反应"。
                // 表本身是一个 `Map<函数 id, Map<字母, 对象 id>>`，`mutableStateOf` 里
                // 每次指认都会换成新对象，所以它当 key 是"值变了才重采样"。
                state.functionBindings
            ) {
                // 滑动条的当前值就是可绑定参数 a/b/c/d 的取值（#3）。
                // 走 `state.parameters()` 而不是就地 `sliders.toParameterSet()`：
                // 那个方法会把"名字不是 a~d 的滑动条"挡在外面（用户允许滑动条叫 f，
                // 而 `ParameterSet` 对不认识的名字是 require 抛异常的，见那边的注释）。
                val parameters = state.parameters()
                // **送给引擎的是替换过取坐标的副本**（`x(A)` → `(1.5)`）：
                // 用户原文一直留在 `functions` 里（编辑器、列表、存档看到的都是它），
                // 只有交给引擎算的这一份副本才替换，见 GraphState.functionsForSampling。
                val samplingFunctions = state.functionsForSampling()
                // 可以被**别的函数调用**的函数表：表达式里写 `a(x)` 时就真的去调 `a`
                // （用户报的 bug：`a(x)=sin(x)`、`b(x)=2a(x)` 画成了 `2x`）。
                // 可调用名怎么取（`a(x)` → `a`）、哪些模式能被调用，规则都在引擎里
                // （`CallableSet.ofFunctions` / `CallableFn.isCallable`），这里只把
                // 当前函数列表 + 全局度制 + 参数表递进去；表里也包含非显函数，
                // 这样用户写 `p(x)`（p 是极坐标）能拿到"不是 y=f(x) 的函数"那句明确报错。
                //
                // 递进去的也是**替换过的副本**：被调用那条函数的函数体同样是"算值"
                // （`a(x)=x(A)`、`b(x)=2a(x)`），用原文会报"不认识的名字 'A'"。
                val callables = CallableSet.ofFunctions(
                    samplingFunctions,
                    settings.angleUnit.isDegrees,
                    parameters
                )
                state.applyPlot(
                    withContext(Dispatchers.Default) {
                        Plotter.plot(
                            // 复平面（#1）下列表里是「数值运算」而不是曲线：
                            // 交给 complexResultPoints() 算成一个个点，采样管线拿空表
                            // （那时 callables 也自然用不上）。
                            if (settings.coordinateSystem.plotsCurves) samplingFunctions else emptyList(),
                            viewport,
                            quality,
                            settings.angleUnit.isDegrees,
                            parameters = parameters,
                            callables = callables
                        )
                    }
                )
            }

            // "手指还在动"的判定：视口 140ms 没变化就当作手势结束，
            // 此时才切回高精度重算一遍。
            LaunchedEffect(viewport) {
                state.interacting = true
                delay(140)
                state.interacting = false
            }

            // 存档防抖，避免手势期间每帧都写一次磁盘。
            //
            // `sliders` **必须也是 key**（#4）：滑动条的属性界面能改的只有名字和
            // 下界/上界/步长，这些改动全都落在 `sliders` 上、`functions` 一点都不动 ——
            // 不把它列进来的话，"改完上下界、重启 App 又变回去"。
            // 防抖本身扛得住高频改动：播放（90ms 一步）期间这个 delay 一直被重启，
            // 所以播放中不会写盘，停下来 700ms 之后才写一次。
            LaunchedEffect(functions, state.selectedId, viewport, settings, sliders) {
                delay(700)
                // 本地存档也**不写"一个字都没写"的函数**（`functionsToSave`，和
                // 存 `.fdf` 用的是同一条判据）：否则用户"建了空函数 → 直接杀掉 App"
                // 之后，那条空行会从存档里回来，怎么都说不清它是哪来的。
                // 内存里的 `functions` 不受影响（这只是写给磁盘的那一份）。
                Store.save(
                    context,
                    functionsToSave(functions),
                    state.selectedId,
                    viewport,
                    settings,
                    sliders
                )
            }

            // 撤销条 / toast 都只留 2 秒（用户要求，见 UNDO_VISIBLE_MS）
            //
            // ⚠️ **每一路**删除/清空的备份字段**全都**要进 key、也全都要忘掉：
            // 这个 App 有**五路**删除/清空，各自在自己的字段里留备份（`GraphState`）：
            //   * 删一个函数 → `lastDeleted`；清空所有函数 → `clearedBackup`（`canUndoClear`）；
            //   * 删一个几何对象 → `lastDeletedObject`；清屏（清对象）→ `clearedObjectsBackup`
            //     （`canUndoObjectsClear`）；
            //   * 删一条**滑动条** → `lastDeletedSlider`（#3 的"滑动条行也能左滑删除"）。
            // 上一版只盯了**前两条** —— 于是：
            //   1. 删掉一个几何对象之后，撤销条**永远不会自己消失**（"2 秒撤销条"只在函数那
            //      条路上成立），一直压在列表底部；
            //   2. 更糟的是那笔过期的备份会一直被**后面每一次**撤销条显示出来：用户删完一个
            //      函数、等 2 秒后点撤销，撤掉的其实是很久以前那个对象 —— 两笔删除混在一起了。
            // （用户本轮的要求原话：对象行的删除"行为与函数行一致 …… + 2 秒撤销条"，
            //   而且"撤销条出现时不要把两个对象的撤销混在一起"。）
            //
            // ⚠️ 加滑动条那一路时**必须同时**做三件事，少一件就复现上面那个坑：
            //   ① 这里的 key 里加 `state.lastDeletedSlider`；
            //   ② 下面的条件里加它（不加：**别的路**在计时、滑动条这一路的撤销条不消失）；
            //   ③ 超时后调 `state.forgetDeletedSlider()`（不加：撤销条永不消失、
            //      而且下次删别的东西时点撤销会撤到那条很久以前的滑动条）。
            // 另外每一路自己动手时也会把其余几路清掉（`GraphState.removeSlider` 等），
            // 保证"一次只撤最近一次操作"。
            LaunchedEffect(
                state.lastDeleted,
                state.canUndoClear,
                state.lastDeletedObject,
                state.canUndoObjectsClear,
                state.lastDeletedSlider
            ) {
                if (state.lastDeleted != null || state.canUndoClear ||
                    state.lastDeletedObject != null || state.canUndoObjectsClear ||
                    state.lastDeletedSlider != null
                ) {
                    delay(UNDO_VISIBLE_MS)
                    state.forgetDeleted()
                    state.forgetCleared()
                    state.forgetDeletedObject()
                    state.forgetObjectsClear()
                    state.forgetDeletedSlider()
                }
            }

            // 采样跑完之后，把待提示的报错弹成 toast
            LaunchedEffect(state.plot, state.pendingErrorFor) {
                val id = state.pendingErrorFor ?: return@LaunchedEffect
                state.plot.errors[id]?.let { state.showToast(it) }
                state.pendingErrorFor = null
            }

            // toast 自动消失
            LaunchedEffect(state.toast) {
                if (state.toast != null) {
                    delay(UNDO_VISIBLE_MS)
                    state.dismissToast()
                }
            }

            // 表冠路径 1：Activity 转发的滚轮事件（OPPO 这类表走这条）。
            //
            // 按界面分工：**只有图像界面才用它缩放**。函数列表界面自己收表冠
            // 去滚动列表（见 FunctionListScreen 的 rotary 参数）—— 否则在列表里
            // 转表冠会去缩放一个根本看不见的图像，用户只会以为没反应。
            LaunchedEffect(state.route) {
                if (state.route is Route.Graph) {
                    rotary.collect { state.zoomByFactor(rotaryFactor(it)) }
                }
            }

            /**
             * 记录"上一次**常驻屏**是哪一屏"（#9 的上滑去向）。
             *
             * **唯一记录点**，而且是由界面观察出来的，不是各处手动置标志 ——
             * 早先那版就是某个进入图像界面的路径忘了置标志，真机上表现为
             * "从图像页上滑回不到功能界面"。
             *
             * ⚠️ 判"该不该记"的规则**不在这里**，在 [GraphState.rememberScreen] 里：
             * 只有**常驻的两屏**（函数列表 / 功能界面）有资格当"上滑回去"的目标，
             * 临时屏（编辑器 / 属性编辑 / 设置 / 文件列表）一律不记 ——
             * 用户报的"新建函数保存后上滑回到编辑器"就是临时屏被记成来路造成的。
             */
            LaunchedEffect(state.route) {
                state.rememberScreen(state.route)
            }

            /**
             * 离开编辑器时清掉"新建了但一个字都没写"的草稿（用户报的 bug 的治本那一半：
             * "保存文件的时候会在文件里添加一个没有表达式的函数"）。
             *
             * 为什么挂在**路由观察**上而不是只写在编辑器的 ×／确定里：`+` 建出来的空函数
             * 只有那两条路会丢，而"离开编辑器"还有别的走法（系统返回被别人接走、
             * 路由被别处改掉、去别的屏）。挂在路由上就只有一处、覆盖所有走法。
             * 规则本身在 [GraphState.discardBlankDraft]（很窄：只动 `justCreatedId`
             * 指的那条、还在编辑器里就不动、写了内容就留着）。
             */
            LaunchedEffect(state.route) {
                state.discardBlankDraft()
            }

            // 注意：**滑动条播放的那段循环不在这里**，它在下面 `GraphRoute` 里
            // （只在图像界面组合）。这一条本轮**有意没有改动**：真机上播放是好的
            // （在列表里点 ▶、滑回图像界面就开始跑，▶/■ 也能正常停），
            // 所以播放路径（`Slider.advance`、`GraphState.toggleSliderPlaying`、
            // `SLIDER_PLAY_TICK_MS`、这段循环）一律保持原样，避免无谓的回归风险。

            // 各屏之间做一次转场：新屏淡入 + 从 0.94 涨到 1（有一点"弹出来"的感觉），
            // 旧屏更快地淡出。表盘就这么小，两屏长时间叠加会糊成一团，所以旧屏的
            // 退出动画刻意比入场短。
            AnimatedContent(
                targetState = state.route,
                transitionSpec = {
                    when {
                        // ---- 功能界面 ⇄ 函数列表：**左右**切换 ----
                        //
                        // 用户反馈："第一屏切换到第二屏的动画应该是左右切换而不是上下切换。"
                        // 功能界面在列表**左边**，所以进它 = 从左边推进来、列表被推向右边；
                        // 回列表 = 反过来（列表从右边滑回来、功能界面往左走）。
                        //
                        // 注意必须**同时**看 initialState：`targetState is Route.Tools`
                        // 单独一个条件会把"从图像界面进来"也吃掉，而那一类要的是
                        // 和函数列表一样的"抽屉"（见下面那两条）。
                        targetState is Route.Tools && initialState is Route.List ->
                            (
                                slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> -full } +
                                    fadeIn(animationSpec = tween(durationMillis = 200))
                                ).togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> full } +
                                    fadeOut(animationSpec = tween(durationMillis = 180))
                            )

                        initialState is Route.Tools && targetState is Route.List ->
                            (
                                slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> full } +
                                    fadeIn(animationSpec = tween(durationMillis = 200))
                                ).togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> -full } +
                                    fadeOut(animationSpec = tween(durationMillis = 180))
                            )

                        // ---- 功能界面 ⇄ 图像界面：和函数列表**完全一样**的"抽屉" ----
                        //
                        // 用户反馈（这一轮 #8）："从功能界面（第二屏）返回函数图像界面的
                        // 动画要和从函数列表（第一屏）返回函数图像界面一样，类似于抽屉，
                        // 而不是上下翻滚。"
                        //
                        // 所以这里不再给 `Route.Tools` 单开两条纵向翻滚动画，
                        // 而是让它和 `Route.List` **共用**下面同两条分支：
                        //   * 进功能界面（从图像界面出发）：功能界面从屏幕最底下滑上来、
                        //     图像界面淡出（和"进函数列表"同一个手势隐喻：从底下往上滑出抽屉）；
                        //   * 出功能界面（回图像界面）：功能界面往下滑走、图像界面淡入
                        //     并轻微放大（和"离开函数列表"一模一样）。
                        //
                        // 功能界面 ⇄ 函数列表仍然是**左右**切换 —— 那两个分支在上面，
                        // 条件更具体（同时看 target 和 initial），会先被匹配到。

                        // 进函数列表 / 功能界面：从屏幕最底下滑上来 —— 和"从底下往上滑划出列表"
                        // 这个手势是同一套空间隐喻，用户一看就懂。
                        targetState is Route.List || targetState is Route.Tools ->
                            (
                                slideInVertically(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> full } +
                                    fadeIn(animationSpec = tween(durationMillis = 200))
                                ).togetherWith(
                                fadeOut(animationSpec = tween(durationMillis = 160))
                            )

                        // 从列表 / 功能界面离开：那一屏往下滑走（对应"上部往下滑返回"），
                        // 新屏（图像界面）淡入 + 从 0.94 轻微放大 —— 就是"抽屉收回去"。
                        initialState is Route.List || initialState is Route.Tools ->
                            (
                                fadeIn(animationSpec = tween(durationMillis = 220)) +
                                    scaleIn(
                                        initialScale = 0.94f,
                                        animationSpec = tween(
                                            durationMillis = 260,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                ).togetherWith(
                                slideOutVertically(
                                    animationSpec = tween(
                                        durationMillis = 280,
                                        easing = FastOutSlowInEasing
                                    )
                                ) { full -> full } +
                                    fadeOut(animationSpec = tween(durationMillis = 200))
                            )

                        // ---- 「函数文件」列表 / 「应用信息」页：**同一套**软转场 ----
                        //
                        // 用户原话（本轮新增应用信息页时点名的）："注意动画和打开文件列表一样。"
                        // 也就是 `Route.About` 的进出必须和 `Route.Saves` **一模一样**。
                        //
                        // 证据（这是"一样"的落点，不是感觉）：
                        //   * `Route.Saves` 在这条 `when` 里**从来没有自己的分支** ——
                        //     它一路落到最下面那个 `else`，参数是
                        //     淡入 200ms + 0.94→1 放大 240ms（`FastOutSlowInEasing`）
                        //     ／ 淡出 150ms + 1→1.02 放大 150ms（`FastOutSlowInEasing`）；
                        //   * `Route.About` 本来也会落到同一个 `else`（它既不是 List/Tools，
                        //     也不是从 List/Tools 出发的），所以"一样"其实已经天然成立 ——
                        //     但"靠 else 兜着"意味着将来谁往中间插一条更宽的条件，
                        //     两屏就会**悄悄分开**，而用户点名的正是这一对屏要一致。
                        // 所以这里把两条名字**显式**写进同一个条件、共用同一个
                        // [softScreenTransition]（参数与 `else` 逐字相同，只写了一份），
                        // 谁先把这一对拆开，谁就会看到这一段注释。
                        targetState is Route.Saves || initialState is Route.Saves ||
                            targetState is Route.About || initialState is Route.About ->
                            softScreenTransition()

                        // 其余（图像 ⇄ 编辑 ⇄ 设置）：淡入 + 轻微放大
                        // ⚠️ 走的是**同一个** [softScreenTransition]，别在这里另写一套参数
                        // （那是"两处各留一份 0.94"的老毛病，漂了以后"设置页的动画和文件列表
                        // 不一样"这类问题就会回来）。
                        else -> softScreenTransition()
                    }
                },
                label = "route"
            ) { route ->
                when (route) {
                // ---- 首屏：函数列表 ----
                // 这里是导航的根，所以**不拦返回键** —— 让系统正常退出应用。
                //
                // 外面这层 `CompositionLocalProvider` 是给**对象行右端那颗 `123`**
                // 提供动作的：它要调 `GraphState.toggleObjectShowValue`，而对象行
                // 自己的**调用点**在 `FunctionList.kt`（本轮不归我改的文件）里 ——
                // 给 `GraphObjectRow` 加一个必填参数会让那一处编译不过，
                // 所以走环境值（见 `LocalObjectShowValueToggle` 的注释）。
                // 只包这一支：别处用不到它。
                Route.List -> CompositionLocalProvider(
                    LocalObjectShowValueToggle provides { id: Long ->
                        state.toggleObjectShowValue(id)
                    }
                ) {
                FunctionListScreen(
                    functions = functions,
                    errors = state.plot.errors,
                    selectedId = state.selectedId,
                    metrics = metrics,
                    // 点一行就是编辑（用户要求去掉长按、也去掉"点一下看图"）
                    onEdit = { id ->
                        state.selectedId = id
                        state.route = Route.Editor(id)
                    },
                    // 长按一行 = 属性编辑界面（#4：名称 / 线型 / 线径）。
                    // 顺手把 selectedId 指过去：列表里的高亮和"正在改哪一条"保持一致，
                    // 从属性界面回来时看到的仍然是同一条被选中。
                    onProperties = { id ->
                        state.selectedId = id
                        state.route = Route.Properties(id)
                    },
                    // **短按一个几何对象行** = 详情页（本轮新增：只读、显示完全）。
                    //
                    // 只有对象行走这条路（函数行的短按仍然是 onEdit 改表达式）——
                    // 用户说的是"给**除了函数之外**点击没有菜单的加一个点击显示详情页面"。
                    // 同样顺手把 selectedId 指过去：从详情页回来时列表高亮还在这一条上。
                    //
                    // 为什么它和上面那条长按**不是**同一个去处：用户要的详情页是**只读**的
                    // （"只显示这个点的颜色，名字和它的坐标……显示完全"），
                    // 而长按那一屏是**可改名字**的编辑器。两屏的版面和浮层语言共用，
                    // 但输入语义完全不同，合并会变成"点一下进来就能改"（他要的不是这个）。
                    onObjectDetails = { id ->
                        state.selectedId = id
                        state.route = Route.Details(id)
                    },
                    onToggleVisible = { id ->
                        state.functions.firstOrNull { it.id == id }?.let { fn ->
                            state.updateFunction(fn.copy(visible = !fn.visible))
                        }
                    },
                    onDelete = { id -> state.removeFunction(id) },
                    onAdd = {
                        // 用户要求：新建时**不预填**表达式；如果保存时还是空的，
                        // 就干脆不添加这个函数（见下面的 onDone），只弹个 toast。
                        val id = state.addFunction(starterMode(state))
                        state.markJustCreated(id)
                        state.route = Route.Editor(id)
                    },
                    onAddPreset = { preset ->
                        // 示例本身就是按坐标系配好的，这里不必再过一遍 coerce。
                        // 量程用示例自带的 tMin/tMax（不要再把 tMin 硬写成 0，
                        // 那样以后加一个"从 -π 开始"的示例就会被悄悄改坏）。
                        val id = state.addFunction(preset.mode, preset.src1, preset.src2)
                        state.updateFunction(
                            state.functions.first { it.id == id }
                                .copy(tMin = preset.tMin, tMax = preset.tMax)
                        )
                        state.selectedId = id
                        state.route = Route.Graph
                    },
                    onSettings = { state.leaveToolsMemory(); state.route = Route.Settings },
                    // **长按 ⚙** = 应用信息页（本轮新增）。走和上面短按同一套收尾：
                    // `leaveToolsMemory()` 把"上滑回哪一屏"定成函数列表（这一屏就是从
                    // 列表长按进来的），然后落到 `Route.About`。
                    onLongPressSettings = {
                        state.leaveToolsMemory()
                        state.route = Route.About
                    },
                    onSwipeDown = { state.leaveToolsMemory(); state.route = Route.Graph },
                    // 撤销条：函数 / 滑动条 / 几何对象这三路都走这一个入口
                    // （#9 的清屏也留了后路，见 GraphState.clearObjects）。
                    //
                    // ⚠️ 每一路都要在这里有一条分支：少一条，"撤销"就会落到 `else`
                    // 去撤别的东西（而用户看到的是"刚删的那一行没回来"）。
                    // 五路各自动手时也会把别的几路清掉（见 `GraphState.removeSlider` 等），
                    // 所以这里的分支顺序只影响"同时有好几笔备份"这种理论上不该出现的状态。
                    showUndo = undoVisible,
                    onUndo = {
                        when {
                            state.canUndoClear -> state.undoClearAll()
                            state.lastDeleted != null -> state.undoDelete()
                            state.lastDeletedSlider != null -> state.undoDeleteSlider()
                            else -> state.undoObjectsAny()
                        }
                    },
                    // 长按 `+` = 把当前**全部函数信息**存成一个 `.fdf` 文件。
                    // 不再切到图像界面（理由见上面 saveDialogVisible 那段注释）。
                    // "清空全部"的能力仍然在功能界面右上角那个垃圾桶的长按上。
                    onLongPressAdd = {
                        saveError = null
                        saveDialogVisible = true
                    },
                    onColor = { id, index -> state.setColor(id, index) },
                    showPresets = settings.showPresets,
                    presets = presetsFor(settings.coordinateSystem),
                    sliders = state.sliders,
                    onSliderValue = { id, v -> state.setSliderValue(id, v) },
                    onSliderPlayToggle = { id -> state.toggleSliderPlaying(id) },
                    onSliderEdit = { id ->
                        // 长按滑动条行 → 滑动条的属性编辑界面（#4）：
                        // 名称（参数字母）、下界、上界、步长。
                        // 短按仍然什么都不做 —— 这条行天生就是用来拖的。
                        state.route = Route.SliderProperties(id)
                    },
                    // 左滑滑动条行（滑轨和播放键以外的地方）→ 删掉它。
                    // 和删函数/删对象**同一条**撤销路：`removeSlider` 把整条 Slider
                    // 备份进 `lastDeletedSlider`，于是底部浮出 2 秒的
                    // 「已删除 · 点此撤销」（见上面那个 effect 与 showUndo）。
                    onSliderDelete = { id -> state.removeSlider(id) },
                    // ---- 几何对象（#9）：度量/描点等结果也要在函数列表里看得见 ----
                    // 注意**不要**把 `complexResultPoints()` 也加进来：复平面下
                    // 那些"数值运算"本来就是列表里的行（functions），再加一遍会重复显示。
                    objects = state.objects,
                    onObjectColor = { id, index -> state.setObjectColor(id, index) },
                    onObjectToggleVisible = { id -> state.toggleObjectVisible(id) },
                    onObjectToggleValue = { id -> state.toggleObjectShowValue(id) },
                    onObjectDelete = { id -> state.removeObject(id) },
                    // 左边缘往右滑 → 进「功能界面」（#9 的入口手势）
                    onSwipeToTools = { state.openTools() },
                    rotary = rotary
                )
                // 上面那层 `CompositionLocalProvider`（对象行 `123` 的动作）到此结束
                }

                // ---- 「功能界面」（#9）----
                //
                // 它**没有设置键、没有 + 键**（用户明确要求），只能回图像界面。
                // 返回键在这里也回图像界面（和列表不同：列表是首屏、不拦返回键）。
                Route.Tools -> {
                    BackHandler { state.backToGraph() }
                    FunctionsScreen(
                        activeTool = state.activeTool,
                        objectCount = state.objects.size,
                        metrics = metrics,
                        onSelectTool = { tool -> state.selectTool(tool) },
                        onClearObjects = {
                            // 清屏：只清几何对象。给了撤销机会（底部浮出小条），
                            // 理由和删函数一样 —— 一下全没了，必须留后路。
                            if (state.objects.isNotEmpty()) {
                                state.clearObjects()
                                state.showToast("已清屏 · 可在列表底部撤销")
                            } else {
                                state.showToast("还没有用工具画过东西")
                            }
                        },
                        onClearAll = {
                            // 垃圾桶长按 = 连函数/方程一起清。
                            // 这一路接的是需求 #8 之前的"长按 + 清空全部函数"：
                            // 那个手势改成保存图像之后，清空能力搬到这里。
                            // 撤销条本身有三路优先级（清函数 > 删函数 > 清对象/删对象），
                            // 所以先撤销回函数、再点一次撤销回几何对象 —— 一次动作能全撤回。
                            if (state.functions.isEmpty() && state.objects.isEmpty()) {
                                state.showToast("屏幕上已经什么都没有了")
                            } else {
                                state.clearObjects()
                                state.clearAllFunctions()
                                state.showToast("已清掉全部内容 · 可在列表底部撤销")
                            }
                        },
                        onBackToGraph = { state.backToGraph() },
                        // 功能界面在列表**左边**，上下滑动都不是它的方向，
                        // 所以借"下滑"当返回函数列表（用户没规定这个手势）。
                        onSwipeDown = {
                            state.leaveToolsMemory()
                            state.route = Route.List
                        },
                        // 左上角文件夹 → 「函数文件」列表（这一轮新增的入口）
                        onOpenSaves = { state.openSaves() },
                        rotary = rotary
                    )
                }

                // ---- 「函数文件」列表（第二屏左上角文件夹按钮进来）----
                //
                // **这一屏是普通一屏：自己的黑底，没有任何底图、也没有模糊。**
                //
                // 上一轮这里被做成了"浮在模糊的函数图像界面上的一层"
                // （底下是一个 `GraphRoute(backdrop = true)` + 30% 的模糊），
                // 但用户要的底图是**保存浮窗**那一处，不是这里。所以整套摘掉了：
                //   * 不再组合 `GraphRoute`：连"多画一遍图"的代价都没有了，
                //     也不再需要那两个本来只为它存在的"垫在黑底上的遮罩"分支；
                //   * `SavesScreen` 的根节点**自己**补了一层不透明纯黑 ——
                //     它原来"没有背景"正是为了让底图透出来；底图一走，那个
                //     "没有背景"就变成了漏底（转场时会把上一屏透出来），
                //     所以黑底这件事现在由 `SavesScreen` 自己保证（见那边的注释）。
                //
                // 「文件列表 = 黑底普通一屏」和「保存浮窗 = 模糊的函数图像底图」
                // 现在是**两条完全不同**的路径，正是用户要的行为差异：
                //   * 这条（功能界面左上角文件夹 → `state.openSaves()`）→ 本分支；
                //   * 那条（第一屏长按 `+` → `saveDialogVisible`）→ 见本文件里
                //     "保存浮窗的底图"那一段，它**不经过路由**。
                //
                // 打开文件的那一路（onOpen）仍然是"整体装回应用状态 → 回函数列表"，
                // 细节见 GraphState.loadFileState。
                //
                // 这一屏**自己拦返回键**（见 `SavesScreen` 里的 BackHandler），
                // 所以这里不再套一层 —— 两层的语义完全一样（都回功能界面），
                // 多一层只会让"哪个先拿到返回"变得不好读。
                Route.Saves -> SavesScreen(
                    metrics = metrics,
                    onOpen = { entry ->
                        val text = FunctionFileStore.read(entry.file)
                        // 版本不符 / 文件损坏 → 明确说一句，并且**保持当前状态不变**
                        // （什么都不装载，用户看到的东西一个像素都不变）
                        val loaded = text?.let { FunctionFileFormat.decode(it) }
                        if (loaded == null) {
                            state.showToast("打开失败：文件损坏或版本不支持")
                        } else {
                            state.loadFileState(loaded)
                            state.route = Route.List
                            state.showToast("已打开 ${entry.baseName}")
                        }
                    },
                    onMessage = { state.showToast(it) },
                    onBack = { state.route = Route.Tools },
                    rotary = rotary
                )

                Route.Graph -> {
                    // 工具取到一半时按返回：**先退出工具**（回到「移动」），
                    // 再按一次才离开图像界面。理由和"先取消再退出"一样 ——
                    // 直接跳走会把半截选择留在状态里，用户下次进来看到
                    // 莫名其妙的残留高亮。没有工具在跑时行为和以前一样，回函数列表。
                    BackHandler {
                        if (state.isMoveTool) {
                            state.leaveToolsMemory()
                            state.route = Route.List
                        } else {
                            state.backToMove()
                            state.showToast("已退出${state.activeTool.label}")
                        }
                    }
                    // 保存**图像**那条路（SaveImageDialog + GraphCapture）这一轮不再接在
                    // 长按 `+` 上（用户改成了保存函数文件），所以这里也不再弹提示框。
                    // `capture` 仍然一路传到画布：`ImageExport.kt` 是明确要求保留的，
                    // 将来恢复"存图"只要在这里再挂一个对话框、调 `capture.savePng`。
                    GraphRoute(state, metrics, geometry, viewport, settings, graphCapture)
                }

                // ---- 「应用信息」页（本轮新增：第一屏**长按 ⚙** 进来）----
                //
                // 用户原话："长按之后打开应用信息页面（不要有标题）……注意动画和打开文件列表
                // 一样，注意放置返回键。"
                //
                // 它和 `Route.Settings` **完全同构**（都是"从函数列表进去、办完事回列表"的
                // 叶子屏），所以这里的写法也照抄那一支：
                //   * **返回键**：左上角那颗（在 `AboutScreen` 里）+ 系统返回键（这里）；
                //     两条路都回 `Route.List`，落点和设置页一致；
                //   * 这一屏**不拦**别的返回语义（没有编辑器那种"先撤销再退出"的分层）。
                // 它不进 `lastNonGraphRoute`（`GraphState.rememberScreen` 把它归到"临时屏"），
                // 所以"看过一次应用信息 → 回列表 → 进图像界面 → 上滑"仍然回函数列表。
                //
                // 转场：**和「函数文件」列表同一条分支**（`transitionSpec` 里那段
                // `targetState is Route.Saves || targetState is Route.About … -> softScreenTransition()`）。
                Route.About -> {
                    BackHandler { state.route = Route.List }
                    AboutScreen(
                        metrics = metrics,
                        onBack = { state.route = Route.List }
                    )
                }

                Route.Settings -> {
                    BackHandler { commitSettings() }
                    SettingsScreen(
                        // 设置页编辑的是**草稿**：坐标系的切换要等退出设置才生效
                        settings = settingsDraft ?: settings,
                        metrics = metrics,
                        onChange = { settingsDraft = it },
                        // 表冠：设置页现在是**可滚动列表**（7 行 × 48dp 装不下 233dp 的圆屏），
                        // 表冠要能滚它 —— 接法和其他可滚屏一字不差（FunctionList / SavesScreen /
                        // AlphabetKeyboard 都是这么传的），不接的话这一屏就只能用手指划。
                        rotary = rotary,
                        // 注意：这里**不再**有 onMessage —— 依赖系统版本的设置在本机不支持时
                        // 是"那一行不显示 + 值被压成安全默认"，不需要给用户弹任何提示
                        // （规则见 SettingsScreen 顶部与 GraphState 里的 GatedSetting）。
                        onBack = { commitSettings() }
                    )
                }

                is Route.Editor -> {
                    val fn: PlotFunction? = functions.firstOrNull { it.id == route.id }
                    // ---- 打开编辑器那一刻的**原值**（× = 真正的取消）----
                    //
                    // 用户报："函数编辑页面，点击×也会保存编辑结果。"
                    // 原因是编辑器是**边打边写**的（`onChange` 直接 `updateFunction`，
                    // 这样图像界面能实时跟着变），所以"取消"不能只是"退回去" ——
                    // 必须把这条函数**还原**成进来时的样子。
                    //
                    // `remember(route.id)`：只在这个路由第一次组合时抓一次快照，
                    // 之后的重组（每次按键都会重组）不会覆盖它。
                    // 键用 route.id 而不是 fn.id：退出再进来（同一个 id）要重新抓一次。
                    val original = remember(route.id) { fn }
                    // 取消 = 还原 + 回列表。刚新建的那种直接丢掉（本来就不该存在，
                    // 也不产生撤销记录）。
                    val cancelEdit: () -> Unit = {
                        if (fn != null && state.justCreatedId == fn.id) {
                            state.discardFunction(fn.id)
                        } else if (original != null) {
                            state.updateFunction(original)
                        }
                        state.route = Route.List
                    }
                    // 系统返回键和 × 是同一件事：都是"放弃这次编辑"，不是"确定"
                    // （否则同一个界面两条退路行为不一致，用户没法预期）。
                    BackHandler { cancelEdit() }
                    if (fn == null) {
                        state.route = Route.List
                    } else {
                        FunctionEditorScreen(
                            fn = fn,
                            error = state.plot.errors[fn.id],
                            metrics = metrics,
                            coordinateSystem = settings.coordinateSystem,
                            // 度制是**每个函数各一份**：编辑器上那个键显示的就是
                            // 这个函数当前生效的单位，按下去只改它自己
                            angleUnit = state.effectiveAngleUnit(fn),
                            onToggleAngleUnit = { state.toggleAngleUnit(fn.id) },
                            // ---- 「这个字母指谁」（用户需求 #4 后半）----
                            // 候选由状态层算（纯逻辑在 `PointCoordinates.nameChoicesIn`，
                            // 有离线单测）；编辑器只负责画出来 + 把点击递回去。
                            // `nameChoicesOf` 读的是 `objects` / `sliders` / 绑定表，
                            // 三者都是 Compose 状态，所以拖动条、量出新角之后
                            // 候选条会自己跟着更新。
                            nameChoices = state.nameChoicesOf(fn),
                            onPickName = { letter, objectId ->
                                state.toggleNameBinding(fn.id, letter, objectId)
                            },
                            // 表冠：字母表键盘（#5）是可滚动列表，要能转
                            rotary = rotary,
                            onChange = { state.updateFunction(it) },
                            // 完成后直接看图：手表上"改完立刻看结果"比回列表更有用。
                            // 报错不再在编辑器里显示，改成回到图像界面后弹一个 toast
                            // （要等采样跑完才知道有没有错，所以先记下 id）。
                            onDone = {
                                // 用户要求：什么都没输入就不添加这个函数，只给 toast
                                val bare = state.bareParameterOf(fn.src1, fn.src2)
                                // "空"的判据用**共用那一条**（`hasNoExpression`）：
                                // 这里判空、保存时过滤（`functionsToSave`）读的是同一个事实，
                                // 两处各写一份迟早会出现"编辑器认为它是空的、存档却写进去"
                                if (fn.hasNoExpression()) {
                                    if (state.justCreatedId == fn.id) {
                                        state.discardFunction(fn.id)
                                    }
                                    state.showToast("没有输入表达式，已放弃添加")
                                    state.route = Route.List
                                } else if (bare != null) {
                                    // ---- 需求 #1：整条表达式就是一个裸参数字母（`a`）----
                                    //
                                    // 用户原话："生成滑动条的时候为什么还会同步生成一条函数啊，
                                    // 滑动条和函数不是一个东西，但是同样是一个对象。"
                                    // 他要的是**只有一条滑动条**，不是"一条画不出东西的函数 + 一条滑动条"。
                                    //
                                    // 所以走和"空输入"同一条路：丢掉这条函数（`discardFunction`
                                    // 不产生撤销记录 —— 它本来就不该存在），然后**只为它生成滑动条**。
                                    // 这里**无条件**丢，不限于"刚新建的"：用户把一条已有函数的
                                    // 表达式改成 `a` 时，他要的同样是"换成一条滑动条"，
                                    // 留着旧函数就又变成他自己抱怨的那种重复。
                                    //
                                    // 写了 `y=` / `f(x)=` 前缀的（`y=a`、`y = a`）**不会走到这里**：
                                    // `bareParameterOf` 用引擎那份剥离规则（`ExplicitSource.strip`，
                                    // 和 `Plotter` 采样用的是同一份）判过 —— 剥得动就说明用户在写"y = 某个式子"，
                                    // 那种情况照旧保留函数：它是一条高度受参数控制的**水平线**，
                                    // 正是用户拍板的"用有没有写前缀区分意图"里明确要保住的那种。
                                    state.discardFunction(fn.id)
                                    state.ensureSlidersFor(fn.src1, fn.src2)
                                    // 不弹 toast（需求 #2）：列表里当场多出一条滑动条，
                                    // 看得见就不必再说一遍。
                                    //
                                    // 去列表而不是图像界面：新东西（滑动条）在列表里，
                                    // 而且此时图上并没有任何新曲线可看。
                                    state.route = Route.List
                                } else {
                                    state.clearJustCreated()
                                    state.pendingErrorFor = fn.id
                                    // #3 / #5：确定时检测表达式里的"什么也不代表的小写字母"
                                    // （既不是函数名、也不是采样变量 x/t/θ）——
                                    // 那就是可绑定参数，自动给每个补一条 -5~5、步长 0.1 的滑动条。
                                    // 已经存在的参数不动，不会把用户调好的值重置。
                                    // `2a`、`a*x+b`、`sin(a)` 这类**照旧保留函数**：
                                    // 它们的字母是式子的一部分，不是"整条表达式"。
                                    state.ensureSlidersFor(fn.src1, fn.src2)
                                    // ⚠️ 去图像界面之前**显式**把"上滑回去"的目标定成函数列表。
                                    //
                                    // 用户报的 bug 就出在这一步："新建函数保存之后会跳转到函数
                                    // 图像界面，但是函数图像界面上滑会回到函数编辑界面而不是
                                    // 函数列表界面。" —— 根因是通用规则那时把**编辑器**这种临时屏
                                    // 也记成了"上一屏"（已在 `GraphState.rememberScreen` 里
                                    // 改成白名单：只有函数列表 / 功能界面参与记忆）。
                                    // 这里再写一次是**双保险 + 表明意图**：这条路径是
                                    // "编辑器用完即走 → 图像界面"，来路永远是列表。
                                    state.rememberScreen(Route.List)
                                    state.route = Route.Graph
                                }
                            },
                            onCancel = {
                                // × = 取消：还原成进来时的值（用户报"点×也会保存"）
                                cancelEdit()
                            }
                        )
                    }
                }

                // ---- 函数/方程/点**以及几何对象**的属性编辑（#4 + 本轮）----
                //
                // 从列表长按进来、退出就回列表（和 Route.Editor 一样，本 App 是两层
                // 导航，没有更深的栈）。改名字/线型/线径**不需要重新采样**：
                // 渲染层每帧实时读外观（见 GraphRoute 里的 curveStyles），
                // 所以 updateFunction 一下就立刻生效 —— 这里一个 replot 都不调。
                //
                // ## 为什么一个路由能装两种东西（本轮）
                //
                // 用户要求"给所有函数列表里面的对象都加长按菜单，**直接调用函数的长按菜单**"，
                // 也就是对象行和函数行走同一个 `Route.Properties(id)`。能这么做的依据是
                // **id 全局唯一**：函数、滑动条、几何对象都由 `GraphState.nextId()` 从
                // 同一个 `idSeq` 取号，所以一个 `Long` 就足以定位目标。
                // 分派就是"先查函数、再查对象"（两条列表都很小，查找代价可以忽略），
                // 都查不到才按原样回列表 —— 那种情况是"它刚在别处被删掉了"。
                is Route.Properties -> {
                    val fnTarget = functions.firstOrNull { it.id == route.id }
                    // 命中函数时**不再去对象表里找**：一个 id 只可能属于一个东西，
                    // 多查一次只会让"万一两份数据打架"变成一件说不清的事。
                    val objTarget = if (fnTarget == null) {
                        state.objects.firstOrNull { it.id == route.id }
                    } else {
                        null
                    }
                    BackHandler { state.route = Route.List }
                    when {
                        fnTarget != null -> FunctionPropertyEditorScreen(
                            fn = fnTarget,
                            metrics = metrics,
                            // 表冠：字母表键盘是可滚动列表，必须能转（#5）
                            rotary = rotary,
                            onChange = { state.updateFunction(it) },
                            onDone = { state.route = Route.List }
                        )

                        objTarget != null -> ObjectPropertyEditorScreen(
                            obj = objTarget,
                            metrics = metrics,
                            rotary = rotary,
                            // "已经被别人占用"的名字（其它对象名 + 函数名 + 滑动条的参数字母，
                            // **不含它自己**：否则点进去什么都不改也会被报成重名）。
                            // 现取而不是缓存：这一屏开着的时候用户可能在别处新建/改名，
                            // 尺子必须跟着变。
                            taken = state.takenObjectNames(objTarget.id),
                            // 改名是**一次原子操作**（名字 + 所有表达式里的引用 +
                            // 度量副标题里的点名一起改，理由见 GraphState.renameObject）。
                            // 界面已经在写回之前用 objectNameError 校验过，所以这里不会失败。
                            onRename = { state.renameObject(objTarget.id, it) },
                            onPickColor = { index -> state.setObjectColor(objTarget.id, index) },
                            onToggleVisible = { state.toggleObjectVisible(objTarget.id) },
                            onToggleValue = { state.toggleObjectShowValue(objTarget.id) },
                            onDone = { state.route = Route.List }
                        )

                        else -> {
                            // 这个东西在别处被删掉了（比如在列表里滑删之后又退回来）：
                            // 直接回列表。用 LaunchedEffect 而不是在组合里直接赋值 ——
                            // 组合期间写状态会白白多触发一次重组。
                            LaunchedEffect(route) { state.route = Route.List }
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }

                // ---- 几何对象的**详情页**（本轮新增：短按列表行进来）----
                //
                // 用户原话："给除了函数之外点击没有菜单的加一个点击显示详情页面，
                // 因为有些点和直线里面数值太长了，在这个详情页面只显示这个点的颜色，
                // 名字和它的坐标，直线就显示它的解析式，显示完全。"
                //
                // 与 `Route.Properties` 的三点不同（**别把两者合并**）：
                //   1. **只读**：这一屏没有任何输入（名字也不能改）；
                //   2. **显示完全**：数据允许换行、字号按长度缩，绝不省略号；
                //   3. 入口是列表行**短按**（长按仍然是属性屏）。
                //
                // 只查 `objects`，**不查 `functions`**：函数行的短按是"改表达式"，
                // 永远不会把用户送到这一屏来 —— 万一真出现一个"查不到的 id"
                // （比如对象刚在别处被删掉），就按下面 `null` 那一支回列表。
                is Route.Details -> {
                    val target = state.objects.firstOrNull { it.id == route.id }
                    BackHandler { state.route = Route.List }
                    if (target == null) {
                        // 这个东西在别处被删掉了（比如在详情页开着时列表里把它滑删了）：
                        // 直接回列表。用 LaunchedEffect 而不是在组合里直接赋值 ——
                        // 组合期间写状态会白白多触发一次重组（和 Properties 那一支同一条规矩）。
                        LaunchedEffect(route) { state.route = Route.List }
                        Box(Modifier.fillMaxSize())
                    } else {
                        ObjectDetailsScreen(
                            obj = target,
                            metrics = metrics,
                            onDone = { state.route = Route.List }
                        )
                    }
                }

                // ---- 滑动条的属性编辑（#4）----
                is Route.SliderProperties -> {
                    val target = sliders.firstOrNull { it.id == route.id }
                    BackHandler { state.route = Route.List }
                    if (target == null) {
                        LaunchedEffect(route) { state.route = Route.List }
                        Box(Modifier.fillMaxSize())
                    } else {
                        SliderPropertyEditorScreen(
                            slider = target,
                            metrics = metrics,
                            rotary = rotary,
                            // 别的滑动条已经占用的参数字母：重名会被属性界面拒绝
                            // （同一个字母两条滑动条，表达式到底用谁就变成看列表顺序了）
                            takenParams = sliders.filter { it.id != target.id }
                                .map { it.param }
                                .toSet(),
                            // 三个数值栏都是"立即生效"：取值在**编译期**折进表达式，
                            // 而 GraphScreen 顶部的采样 LaunchedEffect 以 sliders 为
                            // key，所以它会自己重算一遍 —— 同样不需要在这里调 replot。
                            onChange = { state.updateSlider(it) },
                            onDone = { state.route = Route.List }
                        )
                    }
                }
                }
            }

            // ---- 保存浮窗的底图：**一张加了高斯模糊的函数图像** ----
            //
            // 用户原话（这一轮纠偏的那句）："保存文件的页面是以函数图像为背景的，
            // 背景要有高斯模糊。" —— "保存文件的页面"指的是**长按 `+` 弹出来的那个
            // 命名浮窗**，**不是**「函数文件」列表那一屏（那一屏这一轮改回了纯黑底，
            // 见 `Route.Saves`）。
            //
            // ## 做法：在浮窗底下多铺一层"只画不接输入"的 `GraphRoute`，**完全不碰路由**
            //
            // 为什么不把路由切到 `Route.Graph`（那样"底下就是图像界面"看起来更直接）：
            //   1. **"关掉浮窗回到原来那一屏"这一条要绝对可靠**。路由一个字都不动，
            //      浮窗摘掉时底下那一屏天然还是原来那一屏（这里是第一屏函数列表），
            //      不需要"记住来路、关掉再跳回去"那套机制 —— 也就没有"哪条路忘了跳回去"
            //      这种 bug 的生存空间（`rememberScreen` 的注释里记着上一版正是这么坏的）；
            //   2. 走路由会触发 `AnimatedContent` 的转场：用户会看到"弹出浮窗顺带换了一屏"
            //      （列表 → 图像那套抽屉动画），而用户要的只是"浮窗底下换成图像界面"；
            //   3. 走路由的话图像界面那一份 `GraphRoute` 会**真的**组合起来：它会开始
            //      驱动滑动条播放、消费"新函数揭示"动画 —— 那些状态只该在
            //      "用户真的站在图像界面上"时才动（见 `backdrop` 参数的分支）。
            // 所以这一层是**纯视觉的一层**：底下的状态、焦点、表冠、播放全都留在原来那一屏。
            //
            // ## 输入：`backdrop = true` + 浮窗遮罩，两道保险
            //
            // `backdrop = true` 复用的就是上一轮给 `Route.Saves` 写的那套机制
            // （同一个参数，只是触发条件从"路由是 Saves"换成"保存浮窗可见"）：
            // 它让这一层不接手势、不要焦点、不吃表冠、连底部上滑热区和缩放栏都不组合
            // （逐处见 `GraphRoute` 里 `interactive` 的分支）。
            // 它上面还压着浮窗自己那层**可点击的 75% 黑遮罩**（`NameDialog`），
            // 所以就算哪一处漏了，点击也到不了底图。**手势/焦点/表冠/热区/缩放栏全屏蔽**。
            //
            // ## 不透明黑底是必需的
            //
            // `GraphCanvas` 自己只画网格/曲线，**不画底**（图像界面的黑底是外壳
            // `BoxWithConstraints` 那一层 `.background(MaterialTheme.colors.background)`
            // 给的）。底图这一层不是外壳的子节点、拿不到那层背景，所以必须自己垫一层纯黑 ——
            // 否则浮窗底下会直接透出函数列表（那就成了"函数列表当底图"，正是要做掉的东西）。
            AnimatedVisibility(
                visible = saveDialogVisible,
                // 淡入 180ms / 淡出 140ms：和浮窗自己那层遮罩的淡入（`DIALOG_IN_MS`）
                // 与退场（`DIALOG_OUT_MS`）**对齐**。浮窗的退场是它自己先播完再回调
                // 调用方摘掉的（见 `NameDialog` 的"已知限制"），两边同时起、同时完 ——
                // 于是"模糊图像 ⇄ 函数列表"是一次干净的交叉淡出，不会"啪"地换掉。
                enter = fadeIn(animationSpec = tween(durationMillis = SAVE_BACKDROP_IN_MS)),
                exit = fadeOut(animationSpec = tween(durationMillis = SAVE_BACKDROP_OUT_MS))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                ) {
                    GraphRoute(
                        state = state,
                        metrics = metrics,
                        geometry = geometry,
                        viewport = viewport,
                        settings = settings,
                        capture = graphCapture,
                        backdrop = true,
                        // Unbounded：底图是满屏的，被模糊的曲线不该在边缘被切一刀。
                        // 半径是[SAVE_BACKDROP_BLUR_FRACTION]（233dp 屏上 ≈ 15.1dp）。
                        modifier = Modifier.blur(
                            radius = metrics.sizeDp * SAVE_BACKDROP_BLUR_FRACTION,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded
                        )
                    )
                    // `Modifier.blur` 在 **Android 12（API 31）以下是个空操作**，
                    // 本项目 minSdk 是 24 —— 那些机器上底图会是一张**清清楚楚**的
                    // 函数图像，浮窗压在上面就少了"底图退到后面去"这一层。
                    // 低版本没有等价的公开 API（`RenderEffect` 也是 API 31 才有，
                    // 而且它对"整个 composable 层"没有 Compose 侧的入口），
                    // 所以退化成一个半透明黑遮罩：它不是模糊，但同样把底图压后一档，
                    // 并且让"这一层不可操作"一眼可辨 —— 和这个 App 其它"按版本降级"
                    // 的做法一致（能做就做，做不到就给一个明显更安全的默认，并写清楚）。
                    //
                    // 浓度取 0.45 而不是别处那种 0.7 上下，理由写在这个常量自己的
                    // 注释里（浮窗那张 75% 的遮罩会和它是**叠乘**的）。
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = SAVE_BACKDROP_MASK_ALPHA))
                        )
                    }
                }
            }

            // ---- 保存函数文件：命名对话框 ----
            //
            // 挂在 AnimatedContent **外面**（和 toast 一样）：长按 `+` 发生在函数列表上，
            // 存的内容又和画布无关，所以这里不需要像"存图像"那一版那样
            // 先切到图像界面去抓图层（`ImageExport.kt` 与 `rememberGraphCapture`
            // 都留着没删，将来要恢复"存图"就把 `capture.savePng` 接回来即可）。
            //
            // **它底下那一层是上面那段"保存浮窗的底图"**（模糊的函数图像），
            // 而不是它弹出时用户所在的那一屏 —— 这是用户这一轮明确要求的。
            //
            // 注意本文件里 `saveDialogVisible` 的**开启点只有一处**：
            // `Route.List` 分支里的 `onLongPressAdd`（关闭点三处：浮窗里的 ×、
            // 点遮罩、确定且写盘成功 —— 都在下面这个 `NameDialog` 的参数里）。
            // 所以"浮窗只从第一屏长按 `+` 打开"这件事是封闭的 ——
            // 这也是"关掉就回到第一屏"能成立的前提（**没有任何一条路径碰过路由**）。
            //
            // 输入走**系统输入法**（用户真机反馈明确要求的），所以对话框**不再要 `rotary`**：
            // 表冠当初是给"可滚动的字母表键盘"用的，系统 IME 不吃表冠事件。
            //
            // 校验分两步：**名字合法**走纯函数 `FunctionFileFormat.baseNameError`
            // （空名字/非法字符/太长/重名都会给出一句话），**写盘**走
            // `FunctionFileStore.write`（先写 `.tmp` 再改名，避免留下半个文件）。
            // 两者失败都**不关对话框**、只在标题下面显示原因，不静默失败。
            if (saveDialogVisible) {
                NameDialog(
                    title = "保存函数文件？",
                    metrics = metrics,
                    pathHint = "存到 $SAVE_DIR_HINT",
                    // 默认名字留空：用户想自己起名的话点一下输入框就能打
                    // （**刻意不自动弹输入法**：这块表的系统输入法可能占满整屏，
                    //  一进来就弹的话取消/确定又被挡住 —— 见 `NameDialog` 的注释）
                    initialName = "",
                    hint = "输入文件名",
                    errorText = saveError,
                    saving = savingFile,
                    onCancel = {
                        saveDialogVisible = false
                        saveError = null
                    },
                    onConfirm = { rawName ->
                        if (savingFile) return@NameDialog
                        val error = FunctionFileFormat.baseNameError(
                            rawName,
                            FunctionFileStore.takenBaseNames(context)
                        )
                        if (error != null) {
                            saveError = error
                        } else {
                            savingFile = true
                            saveScope.launch {
                                val text = FunctionFileFormat.encode(state.snapshotForFile())
                                val file = withContext(Dispatchers.IO) {
                                    FunctionFileStore.write(context, rawName, text)
                                }
                                savingFile = false
                                if (file == null) {
                                    saveError = "保存失败：磁盘写不进去"
                                } else {
                                    saveDialogVisible = false
                                    saveError = null
                                    // 提示里带"文件名 + 大小"（用户要求）；
                                    // 名字**不带扩展名** —— 界面上到处都不显示它。
                                    state.showToast(
                                        "已保存 ${FunctionFileFormat.displayName(file.name)}" +
                                            "（${FunctionFileFormat.fileSizeText(file.length())}）"
                                    )
                                }
                            }
                        }
                    }
                )
            }

            // ---- 浮出提示条（toast）----
            // 放在 AnimatedContent **外面**：这样它在任何界面之上都能显示
            // （"点确定后报错"的提示是回到图像界面之后才弹的）。
            // 它没有点击修饰符，所以不会挡住下面那条上滑手势。
            //
            // ⚠️ 位置 = **屏幕底部**，而且是和工具提示条**同一个锚点**
            // （[bottomPillPadFraction]，其中已经避开底部上滑热区与缩放栏）。
            // 用户上一轮报的"提示都在屏幕中间，非常影响操作"说的就是这一族浮层 ——
            // 两处共用一个锚点，"提示一律在屏幕底下"才是结构性的，而不是靠两处各写一遍。
            //
            // ⚠️⚠️ **和撤销条的关系（本轮新增的那条要求）**：第一屏那条
            // 「已删除 · 点此撤销」也在底部。两者可能同时出现（删掉一行之后两秒内
            // 又弹了一条 toast），所以落点由 [toastBottomPad] 现算：
            // **撤销条在时，toast 抬到它上方**（读的是 `FLOAT_PILL_*` 那两个共享常量，
            // 见那个函数的推导）；撤销条不在时，就落在"热区 + 缩放栏"之上那一档。
            // 这样两条胶囊永远不重叠，而且不需要它们互相知道对方的内部状态。
            //
            // ## 外形：**照搬撤销条**（用户本轮："toast 提示的样式统一成撤销删除的样式，
            // 颜色用原来的颜色。现在圆角太大了不好看。"）
            //
            // 外形（形状/圆角/高度/内边距/字号/对齐）全部由 `Common.FloatPill` 提供 ——
            // 和撤销条**同一个实现、同一批常量**，所以两者的圆角必然是同一档；
            // 底色仍然用 toast 自己的**强调色**（用户明确说颜色不要跟着变），
            // 只把"长得什么形状"统一过来。
            // 圆角"太大"的真因：原来这条胶囊是**内容撑高**的（`maxLines = 3` + 上下内边距），
            // 而 `PillShape` 是 `percent = 50`（半径 = 半个高度）—— 文字一长，胶囊一高，
            // 半径就跟着变成"半个高度"那么大。现在改成**定高**（[FLOAT_PILL_H]，
            // 和撤销条同一个数），半径永远等于半个行高，量出来的就是撤销条那一档。
            AnimatedVisibility(
                visible = state.toast != null,
                enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                    ) { full -> full / 2 },
                exit = fadeOut(animationSpec = tween(durationMillis = 150)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = toastBottomPad(
                            metrics = metrics,
                            geometry = geometry,
                            zoomBarVisible = settings.showZoomBar,
                            undoVisible = undoVisible
                        )
                    )
                    // ---- 宽度：圆屏弧宽 / 非圆屏满宽（用户的长效规矩：按键与浮层都要自适应屏宽）----
                    //
                    // 圆屏那一支**逐像素不变**（就是原来那句 `sizeDp × cwf(0.84) × 0.92`：
                    // `bandWidth` 在圆屏上正是 `min(cwf(yTop), cwf(yBottom)) × arcMargin`，
                    // 这里上下沿取同一个 0.84，算出来完全相等）；
                    // 非圆屏则由 `ScreenGeometry.rowWidth` 给"屏宽 − 左右各 3%" ——
                    // 竖屏手机上原来这条 toast 只有 `0.674 × 短边 ≈ 277dp`
                    // （屏宽 411dp，两侧共空掉 ≈134dp）；现在 ≈386dp。
                    // 横屏 / 长条屏上短边是**屏高**，差得更远。
                    .width(bandWidth(metrics, geometry, isRound, 0.84f, 0.84f, arcMargin = 0.92f))
                    // 定高 = 撤销条那一档：圆角（PillShape 的 50%）因此不再是"跟着文字变"的
                    .height(metrics.sizeDp * FLOAT_PILL_H)
            ) {
                FloatPill(
                    text = state.toast.orEmpty(),
                    // toast 是"提示"，**颜色保持强调色**（原来的深红 errorContainer
                    // 太像报错条 —— 用户本轮也明说"颜色用原来的颜色"）
                    container = KeyPalette.accent,
                    metrics = metrics,
                    modifier = Modifier.fillMaxSize()
                    // onClick 不传：toast 只是提示，点了不该有任何后果
                )
            }

            // ---- 切换坐标系的确认框 ----
            //
            // 只在"退出设置时坐标系确实变了"且用户没勾过「不再提示」时出现。
            // 取消 = 整个草稿丢掉（设置保持原样）。
            val pendingSwitch = pendingCoordSwitch
            if (pendingSwitch != null) {
                var dontAskAgain by remember(pendingSwitch) { mutableStateOf(false) }

                // 遮罩：吃掉点击，避免误触到底下的界面
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                )
                Surface(
                    // 浮层统一：近黑底 + 1dp 20% 白描边 + 大圆角（和长按气泡、调色板一致）
                    color = KeyPalette.bubble,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.Center)
                        // 宽度：圆屏弧宽 / 非圆屏满宽（**逐像素不变**的改写，理由见 toast 那一处
                        // 与 `bottomPillPadFraction` 上面那段：`cwf(0.5) == 1`，圆屏算出来还是
                        // `sizeDp × 0.94`；非圆屏不再是一根窄柱）。
                        // 这个框和下面那个"第一次用工具"的框是**同一种对话框**，
                        // 两处必须同一条算式 —— 否则手机上会出现"一个新框很宽、一个旧框很窄"。
                        .width(bandWidth(metrics, geometry, isRound, 0.5f, 0.5f, arcMargin = 0.94f))
                        .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
                ) {
                    Column(modifier = Modifier.padding(metrics.sizeDp * 0.042f)) {
                        Text(
                            text = "切换坐标系？",
                            fontSize = metrics.fontBody,
                            color = KeyPalette.text
                        )
                        Spacer(Modifier.height(metrics.sizeDp * 0.014f))
                        Text(
                            text = "切到${pendingSwitch.coordinateSystem.label}后，" +
                                "原有函数会全部清空（两种坐标系下的函数互不适用）。",
                            fontSize = metrics.fontTiny,
                            color = KeyPalette.textMuted
                        )
                        Spacer(Modifier.height(metrics.sizeDp * 0.018f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dontAskAgain = !dontAskAgain }
                        ) {
                            Checkbox(
                                checked = dontAskAgain,
                                onCheckedChange = { dontAskAgain = it },
                                modifier = Modifier.size(metrics.sizeDp * 0.11f)
                            )
                            Spacer(Modifier.width(metrics.sizeDp * 0.02f))
                            Text(
                                text = "不再提示",
                                fontSize = metrics.fontTiny,
                                color = KeyPalette.textMuted
                            )
                        }
                        Spacer(Modifier.height(metrics.sizeDp * 0.03f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.022f)
                        ) {
                            Button(
                                onClick = {
                                    pendingCoordSwitch = null
                                    state.route = Route.List
                                },
                                modifier = Modifier.weight(1f),
                                shape = PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = KeyPalette.normal,
                                    contentColor = KeyPalette.text
                                )
                            ) {
                                Text("取消", fontSize = metrics.fontSmall)
                            }
                            Button(
                                onClick = {
                                    state.updateSettings(
                                        pendingSwitch.copy(
                                            confirmCoordSwitch =
                                                !dontAskAgain && settings.confirmCoordSwitch
                                        )
                                    )
                                    pendingCoordSwitch = null
                                    state.route = Route.List
                                },
                                modifier = Modifier.weight(1f),
                                shape = PillShape,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = KeyPalette.accent,
                                    contentColor = KeyPalette.text
                                )
                            ) {
                                Text("确定", fontSize = metrics.fontSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 新建函数时的起始模式：受全局坐标系约束。 */
private fun starterMode(state: GraphState): PlotMode =
    state.settings.coordinateSystem.coerce(PlotMode.EXPLICIT)

/**
 * 各屏之间那套「**淡入 + 轻微放大**」的软转场 —— `AnimatedContent` 的 `transitionSpec`
 * 里**唯一**的来源（本轮从原来的 `else` 分支里原样抽出来，参数一个都没改）。
 *
 * ```
 *   进屏：fadeIn(200ms) + scaleIn(0.94 → 1, 240ms, FastOutSlowInEasing)
 *   出屏：fadeOut(150ms) + scaleOut(1 → 1.02, 150ms, FastOutSlowInEasing)
 * ```
 *
 * 为什么抽成一个函数：用户本轮点名要求"应用信息页（`Route.About`）的动画**和打开文件列表
 * 一样**"，而"打开文件列表"= `Route.Saves` 走的那一支就是这一套参数。
 * 抽出来之后，`Route.Saves` / `Route.About` 有一条**显式**分支（见 `transitionSpec` 里
 * 那一段注释），`else`（图像 ⇄ 编辑 ⇄ 设置）也调它 ——
 * **两个调用点共享同一份参数**，不可能再出现"两处各写一个 0.94、改了一处漏另一处"
 * 这种漂移（旧注释里记着好几起同类问题，都是"两处各留一份数"造成的）。
 *
 * 旧屏的退出动画刻意比入场短（150ms < 200ms）：表盘就这么小，两屏长时间叠加会糊成一团。
 */
private fun softScreenTransition(): ContentTransform = (
    fadeIn(animationSpec = tween(durationMillis = 200)) +
        scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(
                durationMillis = 240,
                easing = FastOutSlowInEasing
            )
        )
    ).togetherWith(
    fadeOut(animationSpec = tween(durationMillis = 150)) +
        scaleOut(
            targetScale = 1.02f,
            animationSpec = tween(
                durationMillis = 150,
                easing = FastOutSlowInEasing
            )
        )
)

@Composable
private fun GraphRoute(
    state: GraphState,
    metrics: WatchMetrics,
    /**
     * 真实屏幕几何（见 `GraphScreen` 里那一处的说明）。
     *
     * 只给"贴在上沿的浮层"用（那张唯一的卡片）：圆屏上它和 `metrics.sizeDp`
     * 是同一个东西，非圆屏上才有区别 —— 宽度铺满、位置按真实屏高算。
     */
    geometry: ScreenGeometry,
    viewport: com.functiondrawer.watch.engine.Viewport,
    settings: WatchSettings,
    /**
     * 抓图句柄（#8）。
     *
     * 只挂在**画布那一个节点**上：存下来的图就是"网格 + 曲线 + 几何对象"，
     * 不含信息卡、提示条、toast 这些浮层 —— 用户要的是"函数图像"，
     * 不是一张带 UI 的截图。
     */
    capture: GraphCapture,
    /**
     * 底图模式（**只画，不接任何输入**）。
     *
     * 唯一的使用者是「保存函数文件」浮窗底下那一层：用户要求这个浮窗
     * "以函数图像为背景 + 高斯模糊"，于是浮窗底下铺一份 `GraphRoute(backdrop = true)`
     * 再加 `Modifier.blur(...)`（见 `GraphScreen` 里"保存浮窗的底图"那一段）。
     *
     * 它和"真的在图像界面上"的差别就是 `interactive`：不接手势、不要焦点、
     * 不吃表冠、连底部上滑热区和缩放栏都不组合 —— 上面那个浮窗才是能操作的东西。
     * 另外它也**不驱动任何状态**（不消费"新函数揭示"动画、不重算对称曲线、
     * 不跑滑动条播放），理由见下面每一处的注释：这一层只是画面，不是"用户在这里"。
     */
    backdrop: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colors
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val swipeUpPx = with(density) { (metrics.sizeDp * SWIPE_UP_THRESHOLD).toPx() }

    /** 底图不接输入：下面每一处手势/焦点/表冠/热区都按它分支。 */
    val interactive = !backdrop

    /**
     * 「第一次用工具时的『回到移动』提示」**这一串界面里问过没有**。
     *
     * 设置那一头是 `settings.confirmReturnToMove`（"以后还要不要问"，落进存档）；
     * 这个闩只管"本次运行里已经弹过了"，所以它**必须**用 `rememberSaveable`：
     * `GraphRoute` 离开组合（切到列表/设置）时普通 `remember` 会被丢掉，
     * 那样用户来回切几次列表就会被反复弹同一个框（用户要的是"第 1 次使用"那一次）。
     */
    var returnHintAsked by rememberSaveable { mutableStateOf(false) }

    // 只对**新增**的函数播"被画出来"的动画（见 GraphState.consumeReveal）。
    // 底图不消费它：消费掉之后用户真正走回图像界面时，新函数就不会被"画出来"了
    // （用户看到的是"新函数直接出现"，而这一步本该有动画）。
    var revealIds by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(state.functions, backdrop) {
        if (!backdrop) revealIds = state.consumeReveal(state.functions)
    }

    /**
     * 滑动条播放（#3）。
     *
     * 用户要求"点击播放键…播放从**返回函数图像页面**之后开始"，
     * 所以这段动画挂在图像界面的路由里：在列表里点了播放键，回到图像页才开始跑。
     * 到达上界后反向（往返播放），直到用户再点一次播放键停下。
     *
     * 本轮**未改动**（真机验证播放是好的）。
     */
    val playingSlider = state.sliders.firstOrNull { it.playing }
    LaunchedEffect(playingSlider?.id, playingSlider?.playing) {
        if (playingSlider == null) return@LaunchedEffect
        // **底图不驱动播放**：它是"只画"的一层，用户此刻所在的地方是浮窗下面
        // 原来那一屏（函数列表）。播放键是"等回到图像界面之后才开始跑"的
        // （见上面 KDoc：在列表里点 ▶、回图像页才跑），底图把它提前跑起来
        // 等于"用户还在列表里给文件起名字、滑动条已经在变了"。
        // 不写这一句的话，浮窗一弹（底图开始组合）这段循环就会多跑一份：
        // 它还会把 `state.interacting` 一直置成 true，采样被永久压在 FAST 档上。
        if (backdrop) return@LaunchedEffect
        var direction = 1
        while (true) {
            delay(SLIDER_PLAY_TICK_MS)
            val cur = state.sliders.firstOrNull { it.id == playingSlider.id } ?: break
            if (!cur.playing) break
            val next = cur.advance(direction)
            if (next == null) direction = -direction else state.setSliderValue(cur.id, next)
            // 播放期间当作"手指还在动"：采样降到 FAST。
            // 否则每 90ms 就跑一次高精度隐函数（几万次求值），手表扛不住。
            state.interacting = true
        }
        state.interacting = false
    }

    /**
     * 这一屏是圆屏还是非圆屏（手机 / 方表）。
     *
     * ⚠️ 提到**函数顶部**来声明（原来它是在下面那张卡旁边就地声明的）：浮层/按键的
     * **宽度**也要用它（见 [rowWidth] 与"第一次用工具"那个确认框），两处都要同一个值，
     * 就地声明会变成两块地方各自读一次 `LocalConfiguration`（而且底下那一处会和这里
     * 重名、被 Kotlin 报 name shadowing）。
     */
    val isRound = LocalConfiguration.current.isScreenRound

    /**
     * 浮层 / 按键的宽度：**圆屏走弧宽、非圆屏走真实屏宽**。
     *
     * 用户本轮定的长效规矩（原话）："以后你写所有按键都要加上对屏幕宽度的自适应。"
     * 所以这里不再直接乘"短边比例" —— `WatchMetrics.sizeDp` 是**短边**，
     * 圆表上短边 == 屏高所以看不出问题，手机上它会让浮层变成屏中央一根窄柱 + 两边空一片
     * （用户已经报过多次）。
     *
     * 换算本体在 `MathKeyboard.bandWidth(...)`：全应用"圆屏弧宽 / 非圆屏满宽"的
     * **唯一一处**（数学键盘、数字小键盘、字母表键盘、属性屏各栏共用同一份），
     * 于是这一屏的浮层和键盘/列表行是**同一套宽度语言**：
     *   * **圆屏**：`sizeDp × min(cwf(yTop), cwf(yBottom)) × factor` ——
     *     和这些调用点改动前**逐像素相同**（原来就是这么写的，只是散在各处）；
     *   * **非圆屏**：`ScreenGeometry.rowWidth(...)` = 屏宽 − 左右各 3%。
     *
     * 取上下沿里**更窄**的那一处（不是只看中心高度）：圆的宽度随高度递减，
     * 行两端比中心更靠外，只看中心会让控件四角越过圆弧被表壳切掉。
     *
     * @param yTop / yBottom 这一段在屏幕高度上的上下沿（0~1）。非圆屏上不参与计算
     *   （只影响圆屏弧宽），圆屏那一支正是靠它才逐像素不变。
     * @param factor 弧宽之外再留的余量（原来叫"factor"，和 `bandWidth` 的 `arcMargin` 同义）。
     */
    fun rowWidth(yTop: Float, yBottom: Float, factor: Float = 0.95f) =
        bandWidth(
            metrics = metrics,
            geometry = geometry,
            isRound = isRound,
            yTop = yTop,
            yBottom = yBottom,
            arcMargin = factor
        )

    val tapped = state.tappedFunction
    // 画布上点中的**几何对象**（点/角度/面积/距离/直线…）——它和 `tapped` 互斥，
    // 见 `GraphState.onTapCanvas`；这里只是取出来给下面那张"唯一的卡片"用。
    val tappedObject = state.tappedObject

    // ---- #7 后半：点击曲线附近两个及以上"交点 / 极值点" ----
    //
    // 命中半径 = 屏宽 1/10（用户原话"半径 = 屏宽 1/10"），比点曲线的容差
    // （TAP_SLOP_FRACTION = 0.09）略宽一点：点中曲线之后，手指附近那两个
    // 特征点都应该被标出来，宁可比选曲线宽松一丁点。
    //
    // 这些点同时喂给两处：
    //   1. 信息卡里新增的"交点 / 极值点"两行坐标；
    //   2. GraphCanvas 的 highlightPoints（60% 光晕）。
    // 两处必须用同一份数据，不然会出现"卡片里有、图上没圈"的割裂感。
    val featureHit = remember(tapped?.id, state.plot, viewport) {
        tapped?.let { fn -> state.tapFeatures(fn, viewport.widthPx * 0.1f) }
    }
    val highlightPoints = featureHit?.points.orEmpty()

    // 外观（颜色/线型/线径）**每帧实时读**，不进采样结果的缓存 ——
    // 这样在属性界面里改线径、或在列表里换颜色，立刻就能看到，
    // 不用等后台重新采样（真机反馈过"换颜色之后图没重绘"）。
    val curveStyles = state.functions.associate { fn ->
        fn.id to CurveStyle(
            color = CurvePalette[fn.colorIndex.mod(CurvePalette.size)],
            lineStyle = fn.lineStyle,
            widthScale = fn.widthScale
        )
    }

    // 函数/方程的对称结果要跟着采样结果重算：底图**不做**这件事 ——
    // 折线碎片每次重建都会换一批 id，而 id 进采样 key（`state.objects` 是采样输入，
    // 因为表达式里可以写 `x(A)`），于是"弹出保存浮窗"会顺带多算一遍图。
    LaunchedEffect(state.plot, state.functions, backdrop) {
        if (!backdrop) state.rebuildTransformedCurves()
    }

    Box(modifier.fillMaxSize()) {

        // 当前工具是不是"移动"：**在组合里读一次**，界面按它分支（提示条 / 手势）。
        //
        // ⚠️ 这个值**只能**用来决定"画什么"，绝不能直接捕进 `pointerInput(Unit) { … }`
        // 的闭包里当"当前的模式"用。原因见下面三个 pointerInput 的 key 那段注释：
        // 手势闭包是**创建它的那一次组合**的快照，key 不变就永远不会刷新 ——
        // 用户报的"点了没反应、然后什么都点不动、切到功能界面再切回来才正常"
        // 就是这么来的（工具做完一个对象自动退回「移动」，而闭包里的 moveMode
        // 还是 false：点击走 toolTap（此时工具是移动 → 什么都不做），
        // 拖动的那个 pointerInput 又在启动时因为 `!moveMode` 直接 return 掉了，
        // 于是画布既不响应点击也不响应拖动）。
        val moveMode = state.isMoveTool
        // 吸附/相切那些工具要给手指下面留一点"看得见"的反馈：
        // 已经吃进去的点和正在拖的临时点都画成一枚高亮点。
        val toolPoints = state.toolPickedPoints + listOfNotNull(state.pendingPoint)

        GraphCanvas(
            viewport = viewport,
            plot = state.plot,
            settings = settings,
            metrics = metrics,
            // 只有新加进来的函数会被画出来，原有的直接显示
            revealIds = revealIds,
            curveStyles = curveStyles,
            // 点击选中的对象加 60% 阴影（用户要求 #7）。
            // 两个都放进去：曲线的信息卡和几何对象的属性卡是**同一个槽位**（互斥），
            // 但阴影各自表达"我选的是这一个"，所以在画布上一起给没问题
            // （同一时刻至多只有一个是非 null，见 GraphState.onTapCanvas）。
            shadowIds = setOfNotNull(state.tappedId, state.tappedObjectId),
            // ---- #9：工具画出来的几何对象 ----
            objects = state.objects + state.complexResultPoints(),
            // 交点 / 极值点的 60% 光晕（#7 后半）—— 奶油黄
            highlightPoints = highlightPoints,
            // 工具已经选中的点 + 正在拖的临时描点（#9）—— 强调蓝
            // 两类分开传：否则用户点三个点做角度时，分不清哪个是"刚选的"、
            // 哪个是曲线自己的交点/极值点。
            secondaryHighlightPoints = toolPoints,
            modifier = Modifier
                .fillMaxSize()
                // 抓图（#8）：录到的就是这一层（网格/曲线/几何对象），不含任何浮层
                .then(capture.modifier)
                // ================= 手势 1：描点（按下就出点、松手才定住）=================
                //
                // 用户原话："描点还是有点延迟，正确逻辑应该是按下就显示点，松开确定点的位置。"
                //
                // 为什么以前有延迟 / 轻轻一按不出点（两条路都堵着）：
                //   * `detectTapGestures` 只要注册了 `onDoubleTap`，单击就必须等
                //     双击超时（约 300ms）才会回调 `onTap` —— 那一拍就是"延迟"；
                //   * `detectDragGestures` 的 `onDragStart` 要等手指**移过触摸容差**
                //     才回调，按下不动根本不会出点。
                // 所以这里自己 `awaitEachGesture`：`awaitFirstDown` 拿到的那一刻
                // 就调 [GraphState.beginPendingPoint]（点立刻出现在手指下、跟着手走），
                // 抬手时用**抬手位置**调 [GraphState.commitPendingPoint]（这才定位置）。
                // 中途手指移出画布 / 手势被别的手势吃掉 → cancel：点消失、不产生对象。
                .then(
                    if (!interactive) Modifier else Modifier.pointerInput(state.activeTool) {
                        if (state.activeTool != FunctionTool.PLOT_POINT) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            state.beginPendingPoint(down.position.x, down.position.y)
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    val x = change.position.x
                                    val y = change.position.y
                                    val inside = x >= 0f && y >= 0f &&
                                        x <= size.width.toFloat() && y <= size.height.toFloat()
                                    if (inside) {
                                        state.commitPendingPoint(x, y)
                                    } else {
                                        // 手指移出画布才松手：点消失，不生成对象
                                        cancelled = true
                                    }
                                    break
                                }
                                if (change.isConsumed) {
                                    // 别的手势（比如底部上滑热区）把它吃掉了：当成取消
                                    cancelled = true
                                    break
                                }
                                state.movePendingPoint(change.position.x, change.position.y)
                                // 吃掉移动事件：描点期间画布不许跟着平移/缩放
                                change.consume()
                            }
                            if (cancelled) state.cancelPendingPoint()
                        }
                    }
                )
                // ================= 手势 2：点一下（选中曲线 / 喂给工具状态机）=================
                //
                // ⚠️ **key 必须是 `state.activeTool`**，不能是 `Unit`。
                // `pointerInput` 的这个 key 决定"闭包什么时候重建"：用 `Unit` 的话
                // 闭包在第一次组合时就定死了，里面读到的 `moveMode`/`activeTool`
                // 全是那一刻的快照，之后工具怎么换都不会更新（Compose 只在 key 变化时
                // 取消并重启这段手势）。用户报的"卡住"就是它：工具一变，
                // 点击/拖动都还按旧模式判断。key 取 activeTool 之后，工具一换
                // 这段手势就重建一次，闭包里的值永远是最新的。
                .then(
                    if (!interactive) Modifier else Modifier
                        .pointerInput(state.activeTool, state.isMoveTool) {
                            // 描点有自己那条手势（按下就出点），这里不重复处理它的点击
                            if (state.activeTool == FunctionTool.PLOT_POINT) return@pointerInput

                            if (state.isMoveTool) {
                                detectTapGestures(
                                    // ---- 移动模式：点击 = "看属性 / 选中"（不喂工具）----
                                    //
                                    // 命中顺序**由用户给定**（`GraphState.onTapCanvas`），只有三类：
                                    //   ① 点 → ② 线段/直线/射线 → ③ 函数曲线
                                    // 即"高优先级那一类只要有命中就不再往下看"，重合处的结果是稳定的。
                                    // ①② 弹**属性卡**（名字/类型/颜色/图形显示/数值显示），
                                    // ③ 弹老的**信息卡**（解析式 + 零点/顶点/交点/极值点）。
                                    //
                                    // **角度/面积/距离不参与**（用户："在图像上面能显示，不用管"）：
                                    // 点它们的图形（扇形/多边形/尺寸线）不会弹卡、也不会吃掉点击 ——
                                    // 那一下**穿透**到上面三类去，都没有就当作点空白（收起卡片）。
                                    // 它们的两个开关走列表行 / 图像界面上的色点与 `123`。
                                    //
                                    // 点空白 → 两张卡都收起；再点同一个 → 保持选中（卡片不收）。
                                    // 双击仍然是"重置视野"（和原来一致）。
                                    onTap = { pos -> state.onTapCanvas(pos.x, pos.y) },
                                    onDoubleTap = { state.resetView() }
                                )
                                return@pointerInput
                            }

                            // 工具模式：**自己的"宽容一点"的点击**。
                            //
                            // 为什么不用 `detectTapGestures`：只要手指挪动超过触摸容差，
                            // 它就**一个回调都不给**（不算 tap）。在工具模式下拖动又
                            // 什么都不做（平移被刻意关掉了），于是"手指稍微滑了一下"
                            // 看起来就是"点了没反应" —— 表盘只有 233dp，手指滑动是常态。
                            // 所以这里自己收手势：抬手时**按抬手位置**交给工具状态机，
                            // 中间怎么滑都不影响（选点时吸附本来就是在抬手位置做的）。
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var last = down.position
                                var released = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        last = change.position
                                        released = true
                                        break
                                    }
                                    // 别的手势吃掉了（比如底部上滑回列表）→ 这次不算点选
                                    if (change.isConsumed) break
                                    last = change.position
                                }
                                if (released) {
                                    state.toolTap(last.x, last.y)?.let { state.showToast(it) }
                                }
                            }
                        }
                )
                // ================= 手势 3：双指/单指拖动 = 平移缩放图像 =================
                //
                // key 取 `state.isMoveTool`：模式一变就重建这段手势。
                // 以前是 `pointerInput(Unit)` + 开头 `if (!moveMode) return@pointerInput`：
                // 那个 return 是**协程级的**（整段手势永远不会再启动），
                // 如果它启动时正好在工具模式，那么用户之后切回「移动」也拖不动
                // （"切回去才能正常移动"的那半个现象就是这么来的）。
                .then(
                    if (!interactive) Modifier else Modifier.pointerInput(state.isMoveTool) {
                        if (!state.isMoveTool) return@pointerInput
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            // 工具模式下这段手势根本不存在（key 已经把它停掉了）：
                            // 拖动留给"描点跟手"，量角度时图像不会跟着手指乱跑。
                            state.transformBy(pan.x, pan.y, zoom, centroid.x, centroid.y)
                        }
                    }
                )
                // 表冠路径 2：Compose 的旋转事件（标准 Wear 编码器）。
                // 注意必须让这个节点可获得焦点，否则事件根本不会派发过来。
                // 底图模式（保存浮窗底下那张模糊图像）不接焦点/表冠 —— 否则会把焦点
                // 从浮窗抢走（浮窗上那个输入框要拿焦点才能用系统输入法打字，
                // 表冠也该归底下原来那一屏，比如函数列表）。
                .then(
                    if (!interactive) Modifier else Modifier
                        .onRotaryScrollEvent { event ->
                            state.zoomByFactor(rotaryFactor(event.verticalScrollPixels))
                            true
                        }
                        .focusRequester(focusRequester)
                        .focusable()
                        // 表冠路径 3：被映射成方向键 / 音量键的机型
                        .onKeyEvent { event -> handleViewKey(event, state) }
                ),
            gridColor = scheme.outlineVariant.copy(alpha = 0.45f),
            axisColor = scheme.onSurfaceVariant,
            labelColor = scheme.onSurfaceVariant,
            strokeGrid = metrics.strokeGrid,
            strokeAxis = metrics.strokeAxis,
            strokeCurve = metrics.strokeCurve
        )

        // 焦点：底图模式不抢焦点（浮窗上的输入框要用系统输入法打字）
        LaunchedEffect(interactive) {
            if (!interactive) return@LaunchedEffect
            runCatching { focusRequester.requestFocus() }
        }

        // ---- 画布上点出来的卡片：**只有一张**，内容按"点中的是谁"切换 ----
        //
        // 用户需求两条：
        //   1. 点曲线 → 解析式/特征卡（#7 的老行为，`CurveInfoCard`）；
        //   2. **点几何对象（点/角度/面积/距离）→ 属性卡**（"点击点、角度、面积、长度这些
        //      也显示属性"，而且是"在函数图像界面"点 —— 不是列表行）。
        //
        // 为什么是**一个槽位**而不是两个 `AnimatedVisibility`：
        // 各写一个的话，切换的那 100~240ms 里两张卡会**同时在场**（一个在退、一个在进），
        // 位置还都在屏幕上方同一处 —— 那就是用户明确不要的"两张卡片叠着"。
        // 所以这里只留一张：`card` 决定当前该显示哪一种，`lastCard` 记住最后一个非空的，
        // 让退场动画期间卡片不会先变空白（和原来 `lastTapped` 那个写法一个道理）。
        //
        // 进出动画**沿用原来那一套**（淡入 180 / 上滑 240 / 0.92 放大，
        // 退场淡出 120 + 缩到 0.96），没有第二套浮层语言。
        val card: CardTarget? = when {
            tappedObject != null -> CardTarget.OfObject(tappedObject)
            tapped != null -> CardTarget.OfCurve(tapped)
            else -> null
        }
        var lastCard by remember { mutableStateOf<CardTarget?>(null) }
        LaunchedEffect(card) {
            if (card != null) lastCard = card
        }

        // ---- 卡片的位置与宽度（本轮按"删掉两栏之后的卡片"重算过）----
        //
        // 宽度按**它自己上下沿里更窄**的那一处圆内宽度算（`circleWidthFraction` 在
        // 0.10 和 0.10+高 两处取小者）：卡片是浮在圆表上沿的，用一条固定宽度会让
        // 四角越过圆弧被表壳切掉。
        //
        // ⚠️ 圆屏上**实际起决定作用的是上沿**（0.10 屏高那一行永远比下沿窄），
        // 所以两个高度上界常量改了也不会让卡片变宽或变窄 —— 它们的作用是
        // "别让常量撒谎"：它写的是这张卡最多长到哪，将来谁把卡片加高了，
        // 只要改那个常量就自动跟着收窄，不会再出现"估小了切角"。
        //
        // 非圆屏（手机 / 方表）：没有圆弧要躲，**铺满屏宽减左右余量**
        // （`ScreenGeometry.rowWidth` 那一支，和列表行 / 工具条同一个口径）——
        // 不这么写的话手机上这张卡是屏中央一根 ≈0.44 屏宽的窄柱，因为
        // `circleWidthFraction` 是给圆表推的公式。位置同理：圆屏仍是
        // 0.10 × 短边（**逐像素不变**），非圆屏按**真实屏高**取同一个比例
        // （和「函数列表」那两屏的 `geometry.sy(...)` 一个做法）。
        // ⚠️ `isRound` 不在这里就地声明了：它已经提到 `GraphRoute` 顶部
        // （浮层宽度那一处 `rowWidth(...)` 也要用，两处必须是同一个值）。
        val cardTop = if (isRound) metrics.sizeDp * CARD_TOP else geometry.sy(CARD_TOP)
        fun cardWidth(cardMaxH: Float) = geometry.rowWidth(
            metrics,
            min(
                metrics.circleWidthFraction(CARD_TOP),
                metrics.circleWidthFraction(CARD_TOP + cardMaxH)
            ) * CARD_ARC_MARGIN,
            isRound
        )

        AnimatedVisibility(
            visible = card != null,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) { height -> -height / 3 } +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(durationMillis = 120)
                ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = cardTop)
                .width(
                    when (lastCard) {
                        // 属性卡比曲线的信息卡矮（本轮删掉了颜色条和"图形"开关）：
                        // 高度上界跟着降到 [OBJECT_CARD_MAX_H]，不再是原来按 5 行估的那个数。
                        is CardTarget.OfObject -> cardWidth(OBJECT_CARD_MAX_H)
                        else -> cardWidth(CURVE_CARD_MAX_H)
                    }
                )
        ) {
            when (val target = lastCard) {
                is CardTarget.OfCurve -> {
                    val fn = target.fn
                    val lines = state.featureLines(fn)
                    CurveInfoCard(
                        fn = fn,
                        rootsLine = lines.first,
                        extremaLine = lines.second,
                        // #7 后半：手指附近的交点 / 极值点坐标（没有就是 null，卡片不加这两行）
                        intersectionLine = featureLine("交点", featureHit?.points.orEmpty(), featureHit?.intersectionOf),
                        extraExtremaLine = featureLine("极值点", featureHit?.extrema.orEmpty(), null),
                        metrics = metrics
                    )
                }

                is CardTarget.OfObject -> ObjectPropertyCard(
                    obj = target.obj,
                    metrics = metrics,
                    // 剩下的那一颗开关（数值显示）**直接落状态层**：画布每帧读
                    // `state.objects`，所以点一下立刻反映到图上（列表也跟着变），
                    // 不需要任何刷新调用。
                    //
                    // "图形显示/隐藏"和"颜色"两栏本轮从这张卡上**删掉**了（用户："这和函数
                    // 列表里面的功能重合了"），所以那两个回调也不再需要 —— 它们现在只在
                    // 列表那条长按菜单（`Route.Properties` → 对象属性屏）里。
                    onToggleValue = { state.toggleObjectShowValue(target.obj.id) }
                )

                null -> Unit
            }
        }

        // ---- 工具提示条（#9）----
        //
        // 用户选完工具会自动回图像界面开始取点，但屏幕上**必须有一句话说明
        // "现在要干什么、还差几步"**，否则他点了一下没反应会以为界面坏了。
        //
        // ## 位置：**屏幕底部**（本轮改的，用户报的就是它）
        //
        // 用户原话："功能界面里面所有功能点击之后到函数图像页面之后的 toast 的提示
        // 都在屏幕中间，非常影响操作，移到屏幕底下。"
        //
        // 它原来落在 `TopCenter + 0.42 屏高` —— 圆表上正好是屏幕中间、也是手指最常停的
        // 那一带。现在和 `state.toast` 共用**同一个底边锚点**
        // （[bottomPillPadFraction]：避开底部上滑热区，缩放栏开着时再往上让一档），
        // 所以这一族提示一律在屏幕底部。
        //
        // ## 它同时是"回到移动"的手动入口（本轮**删掉了那行说明文字**）
        //
        // 用户原话："正确逻辑应该是可以选择多个角度之后手动切回移动才移动" ——
        // 也就是工具**不会**自己退出，那么就必须有一个看得见的地方能切回去。
        // 整条提示语就是那个按钮（点一下回移动）。
        //
        // ⚠️ 本轮把第二行小字"点这里回到移动"**删掉了**（用户："把这个 toasts 的提示
        // 里面的'点这里回到移动'给删掉"）。那件事改由**首次使用时的确认框**承担
        // （见下面 `Route.Graph` 之外那段"第一次用工具时的提示"）——
        // 于是这条胶囊现在只有一行字：`工具名 · 现在要干什么`。
        // **点击行为一个字没改**：点它照样回移动，只是不再在界面上天天写着这句话。
        // 提示条自己也算"当前在干什么"的读数：工具做完一个对象仍然留在原地，
        // 条上的字会从"第 3/3 步"变回"第 1/3 步"，用户看得出来又开了一轮。
        AnimatedVisibility(
            // 底图模式（保存浮窗底下那张模糊图像）不显示这条：那一层只是画面
            visible = interactive && !moveMode,
            enter = fadeIn(animationSpec = tween(durationMillis = 160)) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                ) { height -> height / 3 } +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(durationMillis = 120)
                ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = metrics.sizeDp * bottomPillPadFraction(settings.showZoomBar))
        ) {
            val toolHint = state.toolHint
            // 按压反馈和列表行/对象行同一套（`rememberPressFeedback`）：
            // 这条提示语现在是个按钮，按下去要有"被按住了"的样子。
            // 幅度取 0.04（比整行的 0.03 略大一点）—— 它更小，缩放要更明显才看得出来。
            val pillPress = rememberPressFeedback(scaleAmount = 0.04f, glowAmount = 0f)
            Surface(
                color = KeyPalette.bubble,
                shape = PillShape,
                modifier = Modifier
                    // ---- 宽度：圆屏弧宽 / 非圆屏真实屏宽（用户本轮的长效规矩）----
                    //
                    // 这条胶囊**是个按钮**（整条可点），所以它的宽度必须自适应：
                    // `rowWidth(...)` 现在直通 `MathKeyboard.bandWidth(...)` ——
                    //   * 圆屏：`sizeDp × min(cwf(0.40), cwf(0.52)) × 0.92`，**逐像素不变**；
                    //   * 非圆屏：屏宽 − 左右各 3%（竖屏手机上原来这里是
                    //     `0.843 × 短边 ≈ 346dp`、两边共空 ≈65dp，现在 ≈386dp；
                    //     横屏 / 长条屏上短边是**屏高**，旧写法会窄成一根柱）。
                    .width(rowWidth(0.40f, 0.52f, 0.92f))
                    // 缩放只在 graphicsLayer 里读（在那里读不会每帧重组，见 PressFeedback）
                    //
                    // ⚠️ 描边**必须写在这一句的右边**：`graphicsLayer` 只影响它**内层**
                    // 画的东西，写在左边的描边不跟着缩小 —— 按下去时底色缩到 0.96、
                    // 白描边却纹丝不动，看上去就是"描边从胶囊上浮起来了"。
                    // 这里没有用 `bubbleFade`（它那两个值是普通 Float，要传就得在组合里读
                    // `scale`，那正好破坏了上面这条"每帧只失效图层、不重组"的约定）；
                    // 卡片那边有真正的淡出，所以那边用的是 `bubbleFade`。
                    .graphicsLayer {
                        scaleX = pillPress.scale
                        scaleY = pillPress.scale
                    }
                    .border(1.dp, KeyPalette.bubbleBorder, PillShape)
                    .clickable(
                        interactionSource = pillPress.source,
                        // 不加水波纹：整条表盘泛一圈波纹太吵（和对象行同一套做法）
                        indication = null
                    ) {
                        val label = state.activeTool.label
                        state.backToMove()
                        state.showToast("已退出$label · 现在可以拖动/缩放图像")
                    }
            ) {
                // 只有一行（"点这里回到移动"那一行本轮删掉了，理由见上面那段）：
                // 所以这里不再需要 Column —— 一行的胶囊比两行矮一档，
                // 落在底部时也不会往屏幕中间长。
                Text(
                    text = "${state.activeTool.label} · $toolHint",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = metrics.sizeDp * 0.035f,
                        vertical = metrics.sizeDp * 0.02f
                    )
                )
            }
        }

        // ---- 第一次用工具时的「回到移动」提示（本轮新增）----
        //
        // 用户原话："改成第 1 次使用任意会在函数图像界面展示点击可以回到移动的 toast 的
        // 功能，显示一个类似于第一次切换坐标系的确定框，勾选不再提示之后就不提示了。"
        //
        // 三个要点（写法全部照抄坐标系那个确认框，连"不再提示"这一行的排布都一样）：
        //   1. **只弹一次**：只要用户用过**任何一个**会显示那条提示条的工具，就弹这一个框；
        //      它不是"每个工具弹一次"，也不是"每进一次图像界面弹一次"——
        //      `rememberSaveable` 的闩（[returnHintAsked]）记着"这一串界面里已经弹过了"，
        //      它扛得住路由切换（否则来回切几次列表就会反复弹）；
        //   2. **勾了"不再提示"就永久不再提示**：落进 `WatchSettings.confirmReturnToMove`
        //      → `Store` 存档（老存档没这个字段 → 默认"要问一次"，见 `Store.load`）；
        //   3. **没勾**：本次运行不再打扰（闩已置），下次启动会再问一次 ——
        //      这正是"第一次使用时的提示"这条语义，也和坐标系那个框"勾了才永久关掉"一致。
        //
        // 触发条件 = "那条提示条**本来就会显示**"（`interactive && !moveMode`）：
        // 用户在移动模式下（没有正在用的工具）永远看不到这个框。
        if (interactive && !moveMode && settings.confirmReturnToMove && !returnHintAsked) {
            var dontAskAgain by rememberSaveable { mutableStateOf(false) }

            // 遮罩：吃掉点击，避免误触到底下的界面（和坐标系那个框同一套）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            )
            Surface(
                // 浮层统一：近黑底 + 1dp 20% 白描边 + 大圆角
                color = KeyPalette.bubble,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.Center)
                    // ---- 宽度：按键/浮层自适应屏宽（用户本轮的长效规矩）----
                    //
                    // 走 `rowWidth(...)`（= `MathKeyboard.bandWidth`：圆屏弧宽 / 非圆屏满宽）：
                    //   * **圆屏**：`sizeDp × cwf(0.5) × 0.94` —— `cwf(0.5)` 正好是 1，
                    //     所以算出来还是 `sizeDp × 0.94`，和上一版**逐像素相同**；
                    //   * **非圆屏**：屏宽 − 左右各 3%（`ScreenGeometry.rowWidth`），
                    //     不再看短边。竖屏手机上短边 == 屏宽，两者数值接近（386dp vs 386dp）；
                    //     差别在**横屏 / 长条屏**上 —— 那里短边是**屏高**，
                    //     旧写法会让框窄成一根柱，这正是这条长效规矩要根治的形态。
                    //
                    // 框里那两颗键（知道了 / 确定）用 `Modifier.weight(1f)` 平分这一行，
                    // 所以它们的宽度**跟着这个框一起自适应**（见下面 Row 里那两处注释）。
                    .width(rowWidth(0.5f, 0.5f, 0.94f))
                    .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
            ) {
                Column(modifier = Modifier.padding(metrics.sizeDp * 0.042f)) {
                    Text(
                        text = "怎么回到移动？",
                        fontSize = metrics.fontBody,
                        color = KeyPalette.text
                    )
                    Spacer(Modifier.height(metrics.sizeDp * 0.014f))
                    Text(
                        // 文案照用户给的意思写："提示：点这里可以回到移动/平移模式"。
                        // 位置说清楚（屏幕底部那条提示）—— 因为这个框以后不再出现，
                        // 它是用户唯一一次被告知这件事的机会（"点这里回到移动"那行小字
                        // 本轮已经从提示条上删掉了）。
                        // ⚠️ 引号用中文的「」，不用 ASCII 的 `"`（那会直接截断 Kotlin 字符串）
                        text = "用工具时，屏幕底部那条提示就是「回到移动」：" +
                            "点它一下，就能切回移动模式（拖动 / 缩放图像）。",
                        fontSize = metrics.fontTiny,
                        color = KeyPalette.textMuted
                    )
                    Spacer(Modifier.height(metrics.sizeDp * 0.018f))
                    // ---- 「不再提示」这一行：整行可点，宽度铺满对话框 ----
                    //
                    // 自适应：`fillMaxWidth()` 吃的是**对话框**的宽度，而对话框宽度已经走
                    // `rowWidth(...)`（圆屏弧宽 / 非圆屏满宽），所以这一行跟着一起自适应。
                    // 里面那颗 `Checkbox` 是**指示器**（和列表行的 `metrics.dot`、
                    // 图标按钮同一个性质）：它按短边缩放的是"图标多大"，
                    // 不是"按键多宽" —— 用户那条规矩管的是按键/可点容器的**宽度**。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontAskAgain = !dontAskAgain }
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it },
                            modifier = Modifier.size(metrics.sizeDp * 0.11f)
                        )
                        Spacer(Modifier.width(metrics.sizeDp * 0.02f))
                        Text(
                            text = "不再提示",
                            fontSize = metrics.fontTiny,
                            color = KeyPalette.textMuted
                        )
                    }
                    Spacer(Modifier.height(metrics.sizeDp * 0.03f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.022f)
                    ) {
                        // 左边那颗只是"知道了，关掉"：什么都不改，用户下次启动会再看到一次。
                        //
                        // ⚠️ 两颗键都用 `Modifier.weight(1f)`：宽度 = **对话框宽度的一半**
                        // （减去中间那条缝），而对话框宽度走的是 `rowWidth(...)`
                        // （圆屏弧宽 / 非圆屏真实屏宽）—— 也就是说**按键宽度是自适应的**，
                        // 没有写死任何一个 dp / 短边比例。这正是用户本轮那条长效规矩
                        // （"以后你写所有按键都要加上对屏幕宽度的自适应"）的落点：
                        // 圆屏上两键宽度**逐像素不变**，非圆屏上它们随真实屏宽一起变宽。
                        Button(
                            onClick = { returnHintAsked = true },
                            modifier = Modifier.weight(1f),
                            shape = PillShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = KeyPalette.normal,
                                contentColor = KeyPalette.text
                            )
                        ) {
                            Text("知道了", fontSize = metrics.fontSmall)
                        }
                        Button(
                            onClick = {
                                returnHintAsked = true
                                // 勾了就永久关掉（写进存档）；没勾则保持"下次还问"。
                                // 只在勾选时写设置：不勾也写的话，"不再提示"这个勾就白设了。
                                if (dontAskAgain) {
                                    state.updateSettings(
                                        settings.copy(confirmReturnToMove = false)
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = PillShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = KeyPalette.accent,
                                contentColor = KeyPalette.text
                            )
                        ) {
                            Text("确定", fontSize = metrics.fontSmall)
                        }
                    }
                }
            }
        }

        // ---- 底部「按键 + 滑动条」缩放栏（#7，设置里默认关）----
        //
        // 只在设置打开时**组合**：关掉时这一块连布局都不参与，老行为逐像素不变
        // （不是"画一条透明的"，那样仍会吃掉画布手势）。
        //
        // 位置取舍：放在底部上滑热区**之上**，中间留 ZOOM_BAR_BOTTOM_GAP 的缝。
        // 热区（0.17 屏高，屏幕最底下那一条）一点不动 —— 用户想上滑回列表，
        // 手势起手位置和最底下那条"抓手"完全没变；缩放栏占的是它上面一条 0.12 屏高的带。
        // 反过来把热区让开（上移/变窄）要动一条已经调好的手势，风险高、收益只是少一条缝。
        //
        // 栏宽按它**所在高度**的圆弧宽度算（底部圆弧收得厉害，写死宽度两端会被表壳切掉）。
        // 底图模式下不组合：那是"保存浮窗"底下的一张画，浮窗才是能操作的。
        if (interactive && settings.showZoomBar) {
            val barBottom = 1f - (SWIPE_UP_ZONE_H + ZOOM_BAR_BOTTOM_GAP)
            ZoomBar(
                metrics = metrics,
                widthFraction = min(
                    metrics.circleWidthFraction(barBottom - ZOOM_BAR_H),
                    metrics.circleWidthFraction(barBottom)
                ) * 0.95f,
                // 和表冠/方向键走同一个入口：焦点固定为屏幕中心
                onZoom = { factor -> state.zoomByFactor(factor) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = metrics.sizeDp * (SWIPE_UP_ZONE_H + ZOOM_BAR_BOTTOM_GAP))
            )
        }

        // ---- 从屏幕最底下往上滑 → 划出函数列表 ----
        //
        // 底部原本那颗悬浮按钮按用户要求删掉了，改成"抓手条 + 上滑热区"。
        // 热区是画布之上的一个独立 composable，所以从这一条起手的拖动不会再
        // 传给画布去平移图像 —— 这也是它和画布手势不打架的原因。
        //
        // 它在缩放栏**之后**组合（= z 序更高），所以缩放栏的下边缘即使被手指蹭到，
        // 也只会在它上面那一层判定 —— 两块的矩形本身不重叠（见上面那条缝）。
        //
        // 底图模式下它必须**完全不组合**：不然浮窗开着的时候，用户在浮窗以外的
        // 区域滑一下，事件会漏到这一层来、把路由改回函数列表 ——
        // 用户要的是"关掉浮窗回到原来那一屏"，路由不该被底图那种画面改掉。
        if (interactive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(metrics.sizeDp * SWIPE_UP_ZONE_H)
                    .pointerInput(Unit) {
                        var accum = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accum = 0f },
                            onDragEnd = { accum = 0f },
                            onDragCancel = { accum = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                accum = if (dragAmount < 0f) accum + dragAmount else 0f
                                if (accum < -swipeUpPx) {
                                    accum = 0f
                                    // 去向由状态层决定（#9）：刚从「功能界面」过来就回功能界面，
                                    // 否则回函数列表（以前的行为）。见 GraphState.leaveGraphBySwipeUp。
                                    state.route = state.leaveGraphBySwipeUp()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = metrics.sizeDp * 0.035f)
                        .width(metrics.sizeDp * 0.16f)
                        .height(metrics.sizeDp * 0.011f)
                        .clip(CircleShape)
                        .background(scheme.outlineVariant)
                )
            }
        }
    }
}

/**
 * 画布上那张卡片**现在在显示谁**。
 *
 * 做成 sealed 而不是"两个独立的 `Long?` 状态 + 两个 AnimatedVisibility"：
 * 后者在切换的那 100~240ms 里会让两张卡同时在场（一张在退、一张在进，
 * 位置还都在屏幕上方同一处）—— 那就是"两张卡片叠着"。
 * 只有一个槽位，才谈得上"切换或收起"。
 *
 * 状态的**真相**仍在 `GraphState`（`tappedId` / `tappedObjectId`，两者互斥），
 * 这里只是把它们**翻译成"该画哪一种卡"**；`lastCard` 额外记住最后一个非空的，
 * 让退场动画期间卡片不会先变成空白（原来 `lastTapped` 那个写法同一个道理）。
 */
private sealed interface CardTarget {
    data class OfCurve(val fn: PlotFunction) : CardTarget

    data class OfObject(val obj: GraphObject) : CardTarget
}

/**
 * **几何对象属性卡**（画布上点中一个对象时弹出）。
 *
 * 用户原话（更正后）："如果有多个对象重合，优先显示点，再是线段，再是函数曲线，
 * 角度、面积和距离在图像上面能显示，不用管。"
 *
 * ## 现在谁会用到它（画布这一侧）
 *
 * **只有点（[GraphPointObject]）和线段/直线/射线（[GraphLineObject]）**：
 * 画布命中判定只有三类（点 → 线段/直线 → 函数曲线，见 `GraphState.onTapCanvas`），
 * 而第三类是曲线、走的是 [CurveInfoCard]。**角度/面积/距离不参与命中** ——
 * 点它们的图形不会弹这张卡，那一下会穿透到点/线段/曲线上去。
 * 它们的两个开关照旧由**列表行**（长按行 → `Route.Properties` → 对象属性屏）
 * 和图像界面上的色点 / `123` 负责。
 *
 * 卡片本身仍然按 `GraphObject` 通用地写（`obj is GraphMeasureObject` 那一支留着）：
 * 类型上它必须能接住任何对象，而且"哪天又放开度量命中"时不用再改这里。
 *
 * ## 内容（本轮**删掉两栏**之后）
 *
 *   * **名字** + **颜色点**；
 *   * **类型**（点 / 线段 · 垂线 / 直线 / 射线 / 角度 / 距离 / 面积 —— 复用
 *     `propertyKindText`，和列表那侧属性页同一份说法）；
 *   * **数据**（点的坐标(+来源)、度量的 `36.87° · 角度（C，D）`，线不占位）；
 *   * **一颗开关**：`数值`（[GraphMeasureObject.showValue]，只有度量有）。
 *
 * ### 本轮删掉的是"颜色更改（12 色条）"和"图形：显示/隐藏"
 *
 * 用户原话："函数图像界面，点击点之后显示的小菜单不要显示颜色的更改和是否显示，
 * 这和函数列表里面的功能重合了。" —— 那两件事现在只在**函数列表那条长按菜单**里
 * （`Route.Properties` → 对象属性屏：12 色 + 图形/数值两颗胶囊）。
 *
 * 只删这两项，`123` 那一颗（**数值显示**）留着：用户点名的是"颜色的更改"和
 * "是否显示"两件事，而"这个度量要不要在图上写数值"是**看图像时**才会想到的事 ——
 * 为它专门回一趟列表太远。它和列表里那颗 `123` 是同一个字段，谁改都立刻生效。
 *
 * ### 卡片因此矮了一大截（几何跟着重算）
 *
 * 内容从"5 行估高"变成"3 行"（名字/数据/一颗开关），高度上界从原来的 0.42 降到
 * [OBJECT_CARD_MAX_H]（见调用方那一段：它进宽度公式，也进"这张卡最多长到哪"的说明）。
 * 卡片本身是**按内容撑开**的，所以删栏之后不留空档；上沿位置一个字没动
 * （和曲线信息卡共用同一个位置——两张卡是同一个槽位，位置不一致就会"跳一下"）。
 *
 * ## 和曲线信息卡的关系
 *
 * 它和 [CurveInfoCard] 是**同一张卡的两个内容**：同一个位置（屏幕上方、`TopCenter` +
 * [CARD_TOP] 屏高）、同一套浮层样式（`KeyPalette.bubble` 近黑 + 1dp 描边 + `shapes.large`）、
 * 同一套进出动画（调用方那一处 `AnimatedVisibility` 只有一份）。
 * 也就是说画布上**没有第二种浮层语言**：卡片就是卡片，只是里面画的东西不同。
 *
 * ## 描边为什么必须写在 `bubbleFade` **右边**（用户报的"描边瞬切"）
 *
 * `Modifier` 链里 `graphicsLayer` 只影响它**内层**画的东西。原来这条链是
 * `.border(...)` 打头、没有任何透明度图层，卡片自己的淡入靠的是外面那层
 * `AnimatedVisibility`；一旦有人把 alpha/缩放挪进卡片自己的图层，写在左边的
 * 描边就会**不跟着淡出** —— 底已经透明了、白描边还亮着，直到整块消失，
 * 看起来就是"描边瞬间不见"。现在描边一律写在 [bubbleFade] 右边（见下面那句），
 * 卡片的 `alpha` 和 `0.88 → 1` 缩放都盖得住它。
 */
@Composable
private fun ObjectPropertyCard(
    obj: GraphObject,
    metrics: WatchMetrics,
    onToggleValue: () -> Unit
) {
    val color = CurvePalette[obj.colorIndex.mod(CurvePalette.size)]
    val scheme = MaterialTheme.colors

    // 卡片自己的那一次淡入 + 0.88 → 1 缩放（190ms，和调色板面板/长按气泡同一套）。
    // `remember` 不带 key：点**另一个**对象时卡片只是换内容，不会每点一下重播一遍
    // （那会像闪烁）；收起再弹出时这个 composable 会离开组合，下次进来自然重播。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = CARD_IN_MS, easing = FastOutSlowInEasing)
        )
    }

    Surface(
        // 和曲线信息卡同一个浮层样式（近黑 + 描边）
        color = KeyPalette.bubble,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            // 描边**必须**在 bubbleFade 右边（理由见上面那段 KDoc）：
            // graphicsLayer 只作用于它内层的绘制，写在左边的描边不会跟着淡出/缩放。
            .bubbleFade(alpha = appear.value, scale = 0.88f + 0.12f * appear.value)
            .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = metrics.sizeDp * 0.035f,
                vertical = metrics.sizeDp * 0.025f
            )
        ) {
            // ---- 名字 + 颜色点 + 类型 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(metrics.dot)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(metrics.sizeDp * 0.022f))
                Text(
                    text = obj.name,
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(metrics.sizeDp * 0.022f))
                Text(
                    text = propertyKindText(obj),
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // ---- 数据（点：坐标；度量：数值 + 拿什么量的）----
            propertyDataText(obj)?.let { data ->
                Spacer(Modifier.height(metrics.sizeDp * 0.008f))
                Text(
                    text = data,
                    fontSize = metrics.fontTiny,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // ---- 唯一那一颗开关：数值显示/隐藏（**只有度量有**）----
            // 点/线没有数值，所以对它们连这一栏都不画（一栏点了没反应比不画更糟）。
            if (obj is GraphMeasureObject) {
                Spacer(Modifier.height(metrics.sizeDp * 0.018f))
                CardToggleChip(
                    label = if (obj.showValue) "数值：显示" else "数值：隐藏",
                    active = obj.showValue,
                    metrics = metrics,
                    // 以前是两颗并排（各占一半宽），现在只剩一颗：整幅宽，
                    // 点起来和列表里那颗 `123` 一样的份量。
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onToggleValue
                )
            }
        }
    }
}

/**
 * 属性卡上的一颗开关胶囊（选中 = 强调色，160ms 渐染）。
 *
 * 和列表那侧属性页里的 `PropertyActionChip` 是同一套配色/时长。卡片上的用法本轮变过：
 * 原来是"横向并排两颗（各占一半宽）"，删掉"图形"那一颗之后只剩一颗、**整幅宽**
 * （调用方给 `fillMaxWidth()`）—— 尺寸仍然由调用方决定，这个件只做
 * "颜色跟着 `active` 渐染"这一件事，和对象属性屏里那颗 `PropertyToggleChip` 的分工一样。
 */
@Composable
private fun CardToggleChip(
    label: String,
    active: Boolean,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
    )
    Box(
        modifier = modifier
            .height(metrics.sizeDp * 0.10f)
            .clip(PillShape)
            .background(lerp(KeyPalette.normal, KeyPalette.accent, t))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = metrics.fontTiny,
            color = lerp(KeyPalette.textMuted, KeyPalette.text, t),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 曲线信息卡。
 *
 * 只显示"选中了这一条"、它的解析式，以及能算出来的特征。
 * 特征由 [GraphState.featureLines] 提供 —— 非显函数模式算不出零点/顶点，
 * 那时这两行就是 null，卡片只显示解析式（诚实，不编数据）。
 *
 * [#intersectionLine] / [#extraExtremaLine] 是 #7 后半（点击曲线附近有交点/极值点时
 * 多加两行坐标），它们是 [GraphState.tapFeatures] 算出来的，同一份数据也拿去画光晕。
 */
@Composable
private fun CurveInfoCard(
    fn: PlotFunction,
    rootsLine: String?,
    extremaLine: String?,
    intersectionLine: String?,
    extraExtremaLine: String?,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colors
    val color = CurvePalette[fn.colorIndex % CurvePalette.size]

    Surface(
        // 信息卡也是浮层：和气泡/调色板/对话框同一套（近黑 + 描边）
        color = KeyPalette.bubble,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = metrics.sizeDp * 0.035f,
                vertical = metrics.sizeDp * 0.025f
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(metrics.dot)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(metrics.sizeDp * 0.025f))
                Text(
                    text = fn.summary(),
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.text,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (rootsLine != null) {
                Spacer(Modifier.height(metrics.sizeDp * 0.012f))
                Text(
                    text = rootsLine,
                    fontSize = metrics.fontTiny,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (extremaLine != null) {
                Spacer(Modifier.height(metrics.sizeDp * 0.008f))
                Text(
                    text = extremaLine,
                    fontSize = metrics.fontTiny,
                    color = scheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // ---- #7 后半：手指附近的交点 / 极值点 ----
            if (intersectionLine != null) {
                Spacer(Modifier.height(metrics.sizeDp * 0.008f))
                Text(
                    text = intersectionLine,
                    fontSize = metrics.fontTiny,
                    color = CurvePalette[6],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (extraExtremaLine != null) {
                Spacer(Modifier.height(metrics.sizeDp * 0.008f))
                Text(
                    text = extraExtremaLine,
                    fontSize = metrics.fontTiny,
                    color = CurvePalette[6],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (rootsLine == null && extremaLine == null && fn.mode.usesRange) {
                Spacer(Modifier.height(metrics.sizeDp * 0.012f))
                Text(
                    text = "θ/t ∈ [${formatNumber(fn.tMin)}, ${formatNumber(fn.tMax)}]",
                    fontSize = metrics.fontTiny,
                    color = KeyPalette.textMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/** 底部上滑区的高度与判定阈值（占屏高）。 */
private const val SWIPE_UP_ZONE_H = 0.17f

/**
 * 浮在图像界面上的**提示胶囊**离屏幕底边的那条缝（占屏高 / 短边）。
 *
 * ## 为什么要一个具名常量（用户报的"提示在屏幕中间，非常影响操作"）
 *
 * 用户原话："功能界面里面所有功能点击之后到函数图像页面之后的 toast 的提示都在屏幕中间，
 * 非常影响操作，移到屏幕底下。"
 *
 * 他说的是**工具提示条**（选完工具回到图像界面时浮出来的那条胶囊，本轮之前它落在
 * `TopCenter + 0.42 屏高` —— 圆表上正好压在手最容易停的地方）。现在它和
 * `state.toast` 那条浮出提示**共用同一个底边锚点**（[bottomPillPadFraction]），
 * 于是"所有走这条路的提示都在屏幕底部"是结构性的，不靠两处各写一遍。
 *
 * ## 为什么不是"贴着屏幕最底下"
 *
 * 屏幕最底下那 17%（[SWIPE_UP_ZONE_H]）是**上滑回列表的手势热区**：提示条压在那上面
 * 会有两个问题 —— ① 它是可点的（点一下回移动），会抢走那段上滑的落点；
 * ② 圆屏最底下那条弧只有半宽，胶囊会被表壳切掉。
 * 缩放栏（[ZOOM_BAR_H] + [ZOOM_BAR_BOTTOM_GAP]，设置里默认关）也占着底下一条，
 * 所以开着的时候要**再往上让一档** —— 让多少由 [bottomPillPadFraction] 算，
 * 这里只负责"离最近那条东西再留一条缝"。
 *
 * 0.014 ≈ 233dp 屏上 3.3dp：够把两块东西分开（比行间距 0.016 略小），
 * 又不会把提示推得离底边太远。它和缩放栏那三个数一样是**占屏高**的比例，
 * 所以手机上也是同一条相对位置（手机上热区高度同样是屏高的 17%）。
 */
private const val BOTTOM_PILL_GAP = 0.014f

/**
 * 底部提示胶囊该离底边多高（占屏高的分数）。
 *
 * = 上滑热区 +（缩放栏开着时）缩放栏 + 一条缝。两个使用者共用它：
 * `state.toast` 那条浮出提示、以及工具提示条 —— 见 [BOTTOM_PILL_GAP]。
 *
 * @param zoomBarVisible 图像界面底部那条"按键 + 滑动条"缩放栏当前是否显示
 *   （`settings.showZoomBar`；关着的时候那一块**完全不组合**，
 *   所以提示条可以直接落得更低 —— 这也是"两块地方不许重叠"这条约束的最小实现）。
 */
private fun bottomPillPadFraction(zoomBarVisible: Boolean): Float =
    if (zoomBarVisible) {
        SWIPE_UP_ZONE_H + ZOOM_BAR_BOTTOM_GAP + ZOOM_BAR_H + BOTTOM_PILL_GAP
    } else {
        SWIPE_UP_ZONE_H + BOTTOM_PILL_GAP
    }

/**
 * **toast 离屏幕底边多高**（dp）—— 两个约束取更靠上的那个。
 *
 * 用户本轮的要求："移动后**别和撤销条叠在一起**（两者都可能出现在底部）"。
 *
 * 约束 ①：底部上滑热区 +（缩放栏开着时）缩放栏 + 一条缝 —— 见 [bottomPillPadFraction]。
 * 约束 ②：**撤销条在时，抬到它上方**。撤销条那一条的几何是
 * （`FunctionList` 的撤销条 + `Common.FLOAT_PILL_*`，两处读**同一批常量**）：
 *
 * ```
 *   撤销条底边离屏幕底边 = geometry.sx(FLOAT_PILL_BOTTOM_PAD)   // 占屏宽 0.06
 *   撤销条高             = metrics.sizeDp × FLOAT_PILL_H          // 占短边 0.105
 *   ⇒ 撤销条顶边         = 上面两个之和
 *   ⇒ toast 底边 ≥ 撤销条顶边 + 一条缝
 * ```
 *
 * 为什么"占屏宽"那一项要用 `geometry.sx(...)` 而不是短边比例：撤销条自己就是用
 * `geometry.sx(FLOAT_PILL_BOTTOM_PAD)` 定位的（`FunctionList` 那一处），
 * 这里必须用**同一把尺子**，否则在"屏宽 ≠ 短边"的屏上算出来的顶边是错的 ——
 * 横屏尤其明显（短边是屏高，0.06 × 屏宽会比 0.06 × 短边大一倍多，那里正是两条胶囊会撞上的情形）。
 *
 * 为什么不是"永远给撤销条留位"：撤销条大部分时间不在（它是 2 秒的浮层），
 * 常驻地把它那一档留空只会让 toast 白白抬高一条 —— 只在它真的在时才让位。
 *
 * 实测（圆表 233dp）：撤销条占 y ∈ [194.5, 219]（从顶算），toast 占 [165.6, 190.1] →
 * 本来就差 4.5dp 不重叠；这一条算式的作用是**把它变成结构性的**（横屏那类比例下
 * 0.06 × 屏宽会涨到 51dp，不抬就会真的叠在一起）。
 */
private fun toastBottomPad(
    metrics: WatchMetrics,
    geometry: ScreenGeometry,
    zoomBarVisible: Boolean,
    undoVisible: Boolean
): Dp {
    val base = metrics.sizeDp * bottomPillPadFraction(zoomBarVisible)
    if (!undoVisible) return base
    val undoTop = geometry.sx(FLOAT_PILL_BOTTOM_PAD) + metrics.sizeDp * FLOAT_PILL_H
    val above = undoTop + metrics.sizeDp * BOTTOM_PILL_GAP
    return maxOf(base, above)
}


// ---------------------------------------------------------------------------
// 画布上那张卡片（曲线信息卡 / 对象属性卡）的几何与动画
// ---------------------------------------------------------------------------

/**
 * 卡片的上沿（占屏高；非圆屏上按**真实屏高**取同一个比例）。
 *
 * 0.10 从"画布上点出来的卡片"第一版就是这个数，本轮**没有动**：曲线信息卡和对象属性卡
 * 是**同一个槽位**（同一处 `AnimatedVisibility`），位置只要差一点，
 * 在两张卡之间切换时就会"跳一下"。而且它同时是宽度公式里的上沿 ——
 * 圆屏越靠上越窄，往上挪会让卡片更窄。
 */
private const val CARD_TOP = 0.10f

/**
 * 卡片宽度在"圆内弦宽"之外再留的余量（和 `FunctionList` 的 `ARC_MARGIN` 同一档）。
 *
 * 它乘的是 `circleWidthFraction(...)`：那个函数给的是**整条弦**，
 * 顶到弦宽会让卡片的四角正好压在圆弧上（描边会被表壳切掉一点），
 * 所以留 3%。非圆屏那一支不用它（宽度铺满屏宽减左右余量，见 `ScreenGeometry.rowWidth`）。
 */
private const val CARD_ARC_MARGIN = 0.97f

/**
 * **对象属性卡**的高度上界（占屏高）。
 *
 * 本轮删掉"颜色条"和"图形开关"两栏之后重新估的：剩下的内容最多 3 行 ——
 * 名字+类型（高 ≈ `metrics.dot` 0.05）、数据一行（≈ 0.055）、
 * 以及只有度量才有的那颗开关胶囊（0.10），加上下内边距 0.05 与两道缝 0.008+0.018，
 * 合计 ≈ **0.28**。取 0.30 是**故意估大一点**：估大了只是卡片窄一点，
 * 估小了才会四角被切掉（和 `FunctionsScreen.PROPERTY_MAX_H` 同一个取舍）。
 *
 * ⚠️ 圆屏上它对宽度**其实不起作用**（上沿 0.10 那一行永远比下沿窄，取小者永远是上沿）——
 * 它的价值是"别让常量撒谎"：这个数描述的是这张卡最多长到哪，将来谁给卡片加了内容、
 * 只要改它，宽度公式就自动跟着收窄。原来这里是 0.42（按 5 行估的），
 * 那个数在删栏之后已经和内容对不上了。
 */
private const val OBJECT_CARD_MAX_H = 0.30f

/**
 * **曲线信息卡**的高度上界（占屏高）。本轮**未改动**：它的内容一行都没少
 * （解析式 + 零点 + 顶点 + 交点 + 极值点，最多 5 行），0.34 仍然是它的上界。
 */
private const val CURVE_CARD_MAX_H = 0.34f

/**
 * 画布上那张卡片**自己**的淡入 + `0.88 → 1` 缩放的时长（毫秒）。
 *
 * 190 就是 `FunctionsScreen` 里那个 `BUBBLE_IN_MS`（调色板面板和长按气泡同一套浮层语言，
 * 见那里"和调色板面板同一套"的说明）；它是那个文件里的 `private`，引用不到，
 * 所以这里照抄一份并注明来源。也落在项目约定的 120~260ms 区间里。
 *
 * 和调用方那层 `AnimatedVisibility`（淡入 180 / 上滑 240 / 0.92 放大）是**并存**的：
 * 那层负责"进出"（含退场，卡片自己没有播退场动画的余地 —— 它是被状态摘掉的），
 * 这一层负责让卡片的**描边和底色待在同一个图层里**（见 [ObjectPropertyCard] 的 KDoc）。
 */
private const val CARD_IN_MS = 190

/**
 * 「保存函数文件」**浮窗**底下那张模糊函数图像的模糊半径（占屏宽的比例）。
 *
 * 用户原话是"以函数图像为背景的，背景要有**高斯模糊**"，而且明确要"看得出来"。
 * `Modifier.blur` 要的是**半径**（一个长度），不是百分比，所以按屏宽折算：
 *
 *     233dp × 0.065 ≈ **15.1dp**（466px 屏上 ≈ 30px）
 *
 * 为什么取屏宽 5%~8% 这一档（12~19dp）：
 *   * 再小（比如上一轮那个 3% ≈ 7dp）：只有曲线边缘轻微化开，
 *     网格还是清清楚楚的 —— "有高斯模糊"这件事**看不出来**，等于没做；
 *   * 再大（屏宽 15% 以上）：网格和曲线糊成一整块色斑，
 *     用户就认不出"底下是函数图像界面"了 —— 而"知道自己还站在图像界面上"
 *     正是他要在浮窗下面垫这张图的原因（上一轮的注释里也是这个意思）；
 *   * 15.1dp 这一档：网格线明显化开、曲线变成一条柔和的光带，
 *     但整体形状和配色仍然认得出是那张图 —— 也就是"看得出模糊、又认得出是图"。
 *
 * **这是一个纯视觉参数：想要更糊/更清楚只改这一个数**（`Modifier.blur` 那句只用它）。
 * 用 `BlurredEdgeTreatment.Unbounded`：底图满屏，边缘不会被切一刀。
 */
private const val SAVE_BACKDROP_BLUR_FRACTION = 0.065f

/**
 * 低版本（`Modifier.blur` 是空操作的 Android 12 以下）兜底那层半透明黑遮罩的浓度。
 *
 * 为什么是 **0.45** 而不是别处降级用的 0.7 上下：这一层**上面还压着浮窗自己那张
 * 75% 黑的遮罩**（`SaveImageDialog.kt` 的 `DIALOG_MASK_ALPHA`），两者是**叠乘**的 ——
 *
 *     底图最终亮度 ≈ (1 - 0.45) × (1 - 0.75) ≈ **14%**
 *
 * 取 0.7 的话只剩约 7.5%，函数图像基本等同全黑，"底下是图像界面"这件事
 * 反而看不出来了（那正是这一层存在的意义）。0.45 是"明显退到后面一档、
 * 但仍然认得出轮廓"的那个值。
 *
 * 再说一遍：**它不是模糊**，只是低版本上"把底图压后一档"的替代物；
 * 能真的模糊的机器（API 31+）根本不会组合到这一层。
 */
private const val SAVE_BACKDROP_MASK_ALPHA = 0.45f

/**
 * 浮窗底图淡入 / 淡出时长（毫秒）。
 *
 * 180 / 140 是为了**和浮窗自己那层遮罩对齐**（`SaveImageDialog.kt` 里的
 * `DIALOG_IN_MS = 180` / `DIALOG_OUT_MS = 140`，那两个是 private，引用不到，
 * 所以这里各写一份并说明来源）。浮窗退场是它自己先播完再回调调用方摘掉的，
 * 于是这两条动画同时起、同时完 —— 看起来就是"模糊图像 ⇄ 函数列表"的一次交叉淡出。
 */
private const val SAVE_BACKDROP_IN_MS = 180
private const val SAVE_BACKDROP_OUT_MS = 140

/**
 * 撤销条 / toast 的停留时长（毫秒）。
 *
 * 用户要求：**都只留 2 秒**。原来是撤销条 4 秒、toast 3.6 秒 —— 手表上"信息早就看完了它还赖在屏幕上"，
 * 而这两个东西都浮在图像之上、会一直挡着内容。统一成一个常量，也免得以后两处再走散。
 */
private const val UNDO_VISIBLE_MS = 2000L
private const val SWIPE_UP_THRESHOLD = 0.09f

/**
 * 把一组特征点拼成信息卡里的一行（`交点：(1, 0)  (-1, 0)  a(x) ∩ b(x)`）。
 *
 * 没有任何点就返回 **null**：卡片那一行会整行消失，而不是显示"交点：无" ——
 * 用户要求的是"若附近有两个及以上…则加两行"，没有就不该占地方。
 */
private fun featureLine(label: String, points: List<GPoint>, sources: List<String>?): String? {
    if (points.isEmpty()) return null
    val coords = points.joinToString("  ") {
        "(${formatNumber(it.x)}, ${formatNumber(it.y)})"
    }
    val who = if (sources.isNullOrEmpty()) "" else "  " + sources.joinToString(" ")
    return "$label：$coords$who"
}

/**
 * 滑动条播放时每一小步的间隔（毫秒）。
 *
 * 步长固定 0.1（见 [Slider.DEFAULT_STEP]），90ms 一步 ≈ 1.1 单位/秒：
 * 走完 -5~5 大约 9 秒 —— 用户要的是"较缓和的速度"，
 * 太快看不清曲线怎么变，太慢又等不到头。
 */
private const val SLIDER_PLAY_TICK_MS = 90L

/**
 * 表冠一格大约几像素，转成缩放倍率。
 *
 * 钳制在 ±0.25 是有必要的：不同 ROM 一次事件给的像素量差很多，
 * 不钳制会出现"转一格直接放大十倍"。
 */
private fun rotaryFactor(scrollPixels: Float): Double =
    exp((scrollPixels * 0.0016f).toDouble().coerceIn(-0.25, 0.25))

private fun handleViewKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: GraphState
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionUp, Key.VolumeUp -> {
            state.zoomByFactor(1.18); true
        }

        Key.DirectionDown, Key.VolumeDown -> {
            state.zoomByFactor(1.0 / 1.18); true
        }

        Key.DirectionLeft -> {
            state.panBy(24f, 0f); true
        }

        Key.DirectionRight -> {
            state.panBy(-24f, 0f); true
        }

        Key.Enter -> {
            state.resetView(); true
        }

        else -> false
    }
}
