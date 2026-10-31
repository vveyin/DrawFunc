// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.functiondrawer.watch.engine.Calc
import com.functiondrawer.watch.engine.CalcPrecision
import com.functiondrawer.watch.engine.CallableSet
import com.functiondrawer.watch.engine.Complex
import com.functiondrawer.watch.engine.Expression
import com.functiondrawer.watch.engine.ExplicitSource
import com.functiondrawer.watch.engine.FeatureFinder
import com.functiondrawer.watch.engine.GLine
import com.functiondrawer.watch.engine.GLineKind
import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.ParameterSet
import com.functiondrawer.watch.engine.parameterValuesOf
import com.functiondrawer.watch.engine.PlotMode
import com.functiondrawer.watch.engine.PlotResult
import com.functiondrawer.watch.engine.Plotter
import com.functiondrawer.watch.engine.Quality
import com.functiondrawer.watch.engine.SnapCandidate
import com.functiondrawer.watch.engine.SnapKind
import com.functiondrawer.watch.engine.Viewport
import com.functiondrawer.watch.engine.PlotFunction
import com.functiondrawer.watch.engine.angleBisector
import com.functiondrawer.watch.engine.distance
import com.functiondrawer.watch.engine.distanceToShape
import com.functiondrawer.watch.engine.intersectionOfLines
import com.functiondrawer.watch.engine.intersections
import com.functiondrawer.watch.engine.lineOf
import com.functiondrawer.watch.engine.midpoint
import com.functiondrawer.watch.engine.nearestOnCurve
import com.functiondrawer.watch.engine.parallel
import com.functiondrawer.watch.engine.perpendicular
import com.functiondrawer.watch.engine.perpendicularBisector
import com.functiondrawer.watch.engine.polygonArea
import com.functiondrawer.watch.engine.projectionParam
import com.functiondrawer.watch.engine.rayOf
import com.functiondrawer.watch.engine.reflect
import com.functiondrawer.watch.engine.reflectThrough
import com.functiondrawer.watch.engine.screenDistance
import com.functiondrawer.watch.engine.segmentOf
import com.functiondrawer.watch.engine.snap
import com.functiondrawer.watch.engine.snapToAxes
import com.functiondrawer.watch.engine.tangentLine
import com.functiondrawer.watch.engine.worldEpsForPixels
import kotlin.math.PI
import kotlin.math.hypot

/**
 * 全局坐标系。
 *
 * 设置里切换它是**全局强制**的语义（用户明确要这个）：
 * 切换后所有已有函数都会被换算到新坐标系下最接近的模式，
 * 而不是只影响以后新建的函数。换算规则见 [coerce]。
 */
enum class CoordinateSystem(val label: String) {
    CARTESIAN("笛卡尔"),
    POLAR("极坐标"),

    /**
     * 复平面（用户需求 #1）。
     *
     * 用户原话："在复平面模式下**没有函数只有描点和数值运算**，
     * 预留的 a b c d 就是为这个准备的。"
     *
     * 所以这个坐标系下：
     *   * 列表里的每一条**不是曲线**，而是一条**数值运算**——
     *     表达式（`(2+3i)*(a-b)` 这种，变量只有 `i` 和参数 a~d）算出一个**复数**，
     *     然后在复平面上**画一个点**（横轴实部、纵轴虚部），点旁边写结果数值。
     *     求值走 `engine/ComplexMath.kt`（35 个测试），不用实数那套表达式引擎。
     *   * 描点照旧：功能界面选"描点"就能在复平面上点位置。
     *
     * 复用"列表 + 编辑器"这一整套（而不是另开一屏）的理由：用户要的输入方式
     * （字母表键盘、abc 键、滑动条调 a~d、名字 A/B/C…）全都已经在那个流程里了，
     * 复平面只是把"曲线"换成了"点"。
     */
    COMPLEX("复平面");

    fun next(): CoordinateSystem = entries[(ordinal + 1) % entries.size]

    /** 这个坐标系下说得通的绘图模式。 */
    val modes: List<PlotMode>
        get() = when (this) {
            // 参数方程在两种坐标系里都成立：(x(t), y(t)) 本来就是笛卡尔的，
            // 但它也常用来画玫瑰线之类的极坐标风格曲线，所以两边都留着。
            CARTESIAN -> listOf(PlotMode.EXPLICIT, PlotMode.IMPLICIT, PlotMode.PARAMETRIC)
            POLAR -> listOf(PlotMode.POLAR, PlotMode.PARAMETRIC)
            // 复平面没有曲线模式；留一个合法的兜底值，免得编辑器/换算路径拿到空列表崩掉。
            // （正在做的是"数值运算 → 点"，见上面的说明）
            COMPLEX -> listOf(PlotMode.EXPLICIT)
        }

    /** 把一个模式换算成本坐标系下最接近的模式。 */
    fun coerce(mode: PlotMode): PlotMode = when (this) {
        CARTESIAN -> if (mode == PlotMode.POLAR) PlotMode.EXPLICIT else mode
        POLAR -> if (mode == PlotMode.EXPLICIT || mode == PlotMode.IMPLICIT) PlotMode.POLAR else mode
        COMPLEX -> PlotMode.EXPLICIT
    }

    /** 复平面下不做曲线采样，每条列表项算出一个点（见 [COMPLEX] 的说明）。 */
    val plotsCurves: Boolean get() = this != COMPLEX
}

/**
 * 角度制 / 弧度制。
 *
 * 键盘「其他」页有一个切换键，标签显示**当前制**；切换后三角函数的求值
 * 真的会跟着变（DEG 下 `sin(90)=1`）—— 引擎侧由 `Expression.compile(degrees)` 负责。
 */
enum class AngleUnit(val label: String) {
    RAD("RAD"),
    DEG("DEG");

    fun next(): AngleUnit = if (this == RAD) DEG else RAD

    /** 传给引擎的开关：true = 三角函数按"度"理解。 */
    val isDegrees: Boolean get() = this == DEG
}

/**
 * 设置项。
 *
 * 全部做成"点一下切到下一个值"，而不是开关 —— 圆表上开关控件太占地方，
 * 而且每个设置只有两三个取值，循环切换反而更快。
 */
data class WatchSettings(
    val coordinateSystem: CoordinateSystem = CoordinateSystem.CARTESIAN,
    val showGrid: Boolean = true,
    val showAxes: Boolean = true,
    /** 坐标轴/圆环上的数字刻度。默认关：小屏上数字很容易糊成一团。 */
    val showNumbers: Boolean = false,
    /** 函数列表里要不要显示「示例」那一块。 */
    val showPresets: Boolean = true,
    /**
     * 切换坐标系前要不要弹确认框。
     *
     * 首次切换并退出设置时会问一次，勾了「不再提示」就永久关掉。
     * 这个开关本身也要持久化 —— 否则每次重启又会问一遍。
     */
    val confirmCoordSwitch: Boolean = true,
    /** 角度制 / 弧度制。 */
    val angleUnit: AngleUnit = AngleUnit.RAD,
    /**
     * 要不要把这个 App 的窗口**排除在系统手势之外**（用户需求 #6，**首次进入默认开**）。
     *
     * 手表上"从表盘边缘上滑"是系统手势（回表盘 / 换表盘），
     * 它和本 App 的两条边缘手势（图像界面底部上滑回列表、列表左边缘右滑进功能界面）
     * 抢的是同一片区域 —— 不屏蔽掉的话，用户想上滑回列表会被系统直接踢回表盘。
     * 所以默认开：装完就用，不用先去设置里找开关。
     *
     * 实现见 [GraphScreen] 顶层的 `systemGestureExclusionRects` 那段。
     * 默认值同时也是**老存档的缺省**（老存档没有第 8 个字段 → 这里当"开"）。
     */
    val blockSystemGestures: Boolean = true,
    /**
     * 图像界面底部要不要显示"按键 + 滑动条"缩放栏（用户需求 #7，**首次进入默认关**）。
     *
     * 默认关是有意的：很多表**不支持多指触控、也没有表冠**，那些用户需要它；
     * 而支持的表用双指捏合/表冠更顺手，多一条底部栏只会挡内容。
     * 默认关时这一栏**完全不组合**（不占位、不吃手势），老行为逐像素不变。
     */
    val showZoomBar: Boolean = false,
    /**
     * 用工具（点/直线/度量…）时那条"回到移动"的**首次提示**还要不要弹（本轮新增）。
     *
     * 用户原话："改成第 1 次使用任意会在函数图像界面展示点击可以回到移动的 toast 的
     * 功能，显示一个类似于第一次切换坐标系的确定框，勾选不再提示之后就不提示了。"
     *
     * 语义和 [confirmCoordSwitch] **完全对齐**（那边也是"下次还要不要问"）：
     *   * true（默认）= 第一次用到工具、那条提示条刚要出现时，弹一次确认框；
     *   * 用户在框里勾了「不再提示」→ 置 false，**永久不再弹**（这个值会落进存档）；
     *   * 没勾 → 值保持 true，但本次运行里那个 `rememberSaveable` 的闩不会再弹
     *     （见 `GraphScreen.GraphRoute` 的 `returnHintAsked`），下次启动会再提示一次。
     *
     * ⚠️ **默认值是 true**：这是个"新用户第一次用工具时就该看到一次"的说明 ——
     * 老存档没有这个字段（见 `Store.load` 第 10 个字段那一行），也就按 true 处理：
     * 升级上来的老用户**会**看到一次这个框。这是有意的（他的提示条里那句
     * "点这里回到移动"正好被本轮删掉了，缺了这次说明就少了一条信息），
     * 而代价只是一次可以勾掉的框。
     */
    val confirmReturnToMove: Boolean = true,
    /**
     * **白天模式**开关（2026-10-17 新增，本轮修正了方向）。
     *
     * 字段名是 `dayMode`（不是 `nightMode`）—— 这是关键：**名字和方向必须一致**。
     *
     *   * **false（默认）= 夜间** —— 也就是这个 App 原来那套黑底 UI。
     *     用户原话："**我原来做的那一套 ui 应该是夜间模式的**"，
     *     所以纯黑画布 + 粉彩曲线 + 深灰按键**就是**夜间主题，不是"默认主题"。
     *   * **true = 白天模式** —— 白纸画布 + 深饱和曲线 + 浅灰按键，
     *     一整套重新配过的颜色（`WatchPaletteDay` / `CurvePaletteDay` / `DayKeyColors`），
     *     不是"把背景刷白"。
     *
     * 设置页那一行仍然叫「**夜间模式**」（用户就是这么叫的），
     * 显示的是 `!dayMode`：打开 = 夜间 = 黑底。点击翻转 `dayMode`。
     *
     * ## 为什么改字段名而不是给 `nightMode` 取反
     *
     * 上一版字段叫 `nightMode` 但 `true = 白底`，于是"夜间模式：开"显示白屏 ——
     * 用户一句"反了"就是这个。更麻烦的是 `ThemeSettingsScreen` 里那句
     * `copy(nightMode = !nightMode)` 读起来像"关掉夜间"，实际在开白天。
     * 字段名、主题参数、存档段名三处统一成 `dayMode` 之后，
     * 代码里任何一处 `dayMode` 都能一眼读对。
     *
     * ## 存档兼容：零迁移
     *
     * 存档第 11 段存的是这个布尔值本身（`0`/`1`）。旧版 `nightMode=false` 表示黑底，
     * 新版 `dayMode=false` 也表示黑底 —— **存值语义完全一致**，只是换了个名字。
     * 所以老存档读出来该黑的还是黑的，不需要任何迁移代码。
     *
     * 切换**即时生效**（点一下当场换，不等退出设置），并随存档持久化。
     */
    val dayMode: Boolean = false,
    /**
     * **主题风格**（2026-10-17 新增）。
     *
     * 用户原话："设置的主题设置里面的夜间模式前面加一条主题设置，
     * 可以切换 Material（现在这个风格）和液态玻璃。"
     *
     * [ThemeStyle.MATERIAL] = 现在这套（纯色卡片 + 扁平按键）；
     * [ThemeStyle.LIQUID_GLASS] = 抽屉/卡片换成磨砂玻璃：背后的图像被模糊、
     * 盖一层半透明底、顶部一条高光边。
     *
     * 它和「夜间模式」是**正交**的两件事：一个管明暗、一个管质感，
     * 所以是两个独立字段，可以组合（白天 + 玻璃 / 夜间 + 玻璃）。
     */
    val themeStyle: ThemeStyle = ThemeStyle.MATERIAL,
    /**
     * **模糊背景**开关（2026-10-18 新增）。
     *
     * 用户原话："在主题设置里面加一个模糊背景的开关吧，这样让 material 主题也能使用
     * 模糊背景，让毛玻璃主题也能不使用模糊背景。注意分开写模糊背景和不模糊背景的效果，
     * 不模糊背景的效果就是刚开始没做模糊效果的时候的抽屉效果。"
     *
     * 所以它和 [themeStyle] 是**正交**的两件事，四种组合都成立：
     *
     * | themeStyle | blurBackdrop | 抽屉里看到的 |
     * |---|---|---|
     * | MATERIAL | false | 纯不透明底色（最早期那个抽屉，什么都不透） |
     * | MATERIAL | true  | 背后函数图的模糊版（本轮新增的玩法） |
     * | LIQUID_GLASS | true | 同样的模糊版 + 玻璃卡片（玻璃主题一直的观感） |
     * | LIQUID_GLASS | false | 纯不透明底色 + 玻璃卡片（玻璃材质在实色底上） |
     *
     * ⚠️ 它不是"玻璃的一个子开关"：玻璃主题的本体是**卡片材质**
     * （半透明面、更亮的分界线、行内高光），和背景糊不糊没关系 ——
     * 这也是为什么两者必须是两个字段，而不是一个三态枚举。
     *
     * 默认值 `true`：上一版（3.28）所有主题共用一张静态模糊图，升级上来
     * 手上就是开着的样子，零感知。Material 主题的老用户如果觉得太花，
     * 去主题设置里关掉即可。
     */
    val blurBackdrop: Boolean = true,
    /**
     * **模糊程度**（0f~1f，3.40 新增；界面显示 0%~100%）。
     *
     * 用户原话："在模糊背景的设置条下面加个调表冠灵敏度同款的滑动设置条，
     * 内容是模糊程度，调整从 0% 到 100%。"
     *
     * 它只影响**半径**，不影响开关：[blurBackdrop] 关着时这一项不生效。
     * 换算见 `BackdropImage.blurRadiusDpFor(strength)` —— 0% 时半径 0
     * （等于不糊、只剩压暗），100% 时被 `ScriptIntrinsicBlur` 的 25 上限卡住
     * （库里能给的最糊）。
     *
     * ## 默认值
     *
     * 3.42 曾定成 70%，本轮按用户要求改成 **30%**：
     * "把初次打开软件里模糊程度的默认值改成 30%"。
     * 只影响**新装**的默认值；已经存过设置的设备沿用存档里的值
     * （升级用户不会因为这一行突然变糊或变清）。
     */
    val blurStrength: Float = 0.3f,
    /**
     * **按钮下阴影强度**（0~1，默认 10%）。
     *
     * 用户原话："把初次打开软件里模糊程度的默认值改成 30%，按钮下阴影强度默认值改成 10%。"
     *
     * 它乘在液态玻璃按键的投影 alpha 上（见 `Common.kt` 的 `glassElevation`）：
     * 0% = 完全没有投影，100% = 明显浮起。只影响**观感**，不参与任何布局与交互。
     */
    val keyShadowStrength: Float = 0.1f,
    /**
     * **切换坐标系时要不要删掉功能界面画出来的对象**（1.2.71 新增）。
     *
     * 用户原话："你在短按设置里的坐标系设置下加一栏：切换坐标系删除绘制图形（是/否），
     * 如果选择是，那么切换坐标系的时候所有对象都会删除；如果选择否，那么切换坐标系的时候
     * 保留绘制的图形，但是绘制的图形的方程要改变成适配坐标系的方程。"
     *
     * 默认 `false`（= 否）：保持本轮之前的行为 —— 切坐标系只清函数，画出来的点/线/度量
     * 都留着，它们的显示方程按新坐标系换算（见 `ObjectEquations.kt`）。
     * `true` = 是：切坐标系时把 `objects` 一起清空。
     */
    val clearObjectsOnCoordSwitch: Boolean = false,
    /**
     * **表冠工作机制**（2026-10-17 新增）。
     *
     * 不同厂家的手表把"表冠旋转"报成不同的输入事件，一套代码吃不下两种：
     * OPPO 系会在普通的 `MotionEvent` 里带滚轮轴，而华为/三星走
     * `ACTION_SCROLL` + `SOURCE_ROTARY_ENCODER`。用错了就是"表冠完全没反应"。
     * 具体差异见 [CrownMode] 的说明。
     *
     * 默认 [CrownMode.OPPO]（本 App 一直用的那套），华为/三星用户去设置里切一下。
     */
    val crownMode: CrownMode = CrownMode.OPPO,
    /**
     * **表冠灵敏度**（倍速）：1.0 = 原来的手感。
     *
     * 用户原话："在这个设置条下面放灵敏度设置，用滑动条控制，现在默认速度是 1.0，
     * 最低到 0.1，最高到 10.0。"
     *
     * 它乘在表冠给的原始滚动量上（见 `MainActivity` 的 `rotaryEvents`）。
     */
    val crownSpeed: Float = 1.0f,
    /**
     * **计算精度**（3 / 8 / 16 位小数，默认 8 位）。
     *
     * 它决定三件事：小于精度阈值的数按 0 处理、缩放的放大上限、
     * 以及坐标轴/数值显示的小数位数 —— 详见 `engine/CalcPrecision.kt`。
     * 存档里追加在末尾（老存档缺这一段 → 8 位）。
     */
    val precision: CalcPrecision = CalcPrecision.P8
)

/**
 * 表冠的两种事件来源（用户要求"表冠要能选 OPPO / 华为三星"）。
 *
 * 为什么必须让用户自己选、而不是自动探测：
 * 两种事件在不同厂家的表上**都可能出现、也可能都不出现** —— 自动探测要么会
 * 把同一次旋转算两遍（表格滚两次），要么在某个厂家固件上永远探不到（表冠失灵）。
 * 让用户手动选一次、选错再换一个，比猜稳。
 *
 *   * [OPPO]：旋转走普通 `MotionEvent` 的滚轮轴（`AXIS_SCROLL` / `AXIS_VSCROLL`），
 *     事件本身没有 `SOURCE_ROTARY_ENCODER` 标记。这是本 App 一直以来的接法。
 *   * [HUAWEI_SAMSUNG]：旋转走 `ACTION_SCROLL`，且 `source` 带
 *     `SOURCE_ROTARY_ENCODER` 位。这一支要按 "一格 = 固定步进" 理解，
 *     原始值往往只有 ±1，直接乘灵敏度容易"转半天不动"，所以这一支做了放大。
 */
/**
 * 主题风格（见 `WatchSettings.themeStyle`）。
 *
 * 只有两个值，而且**不叫"经典/新版"**：用户说的是"Material（现在这个风格）和液态玻璃"，
 * 那就用这两个词，界面上直接显示，不另起名字。
 */
enum class ThemeStyle(val label: String) {
    /** 现在这套：纯色卡片、扁平按键、无模糊。 */
    MATERIAL("Material"),

    /** 磨砂玻璃：背后图像模糊 + 半透明底 + 顶部高光边。 */
    LIQUID_GLASS("毛玻璃");

    fun next(): ThemeStyle = if (this == MATERIAL) LIQUID_GLASS else MATERIAL
}

enum class CrownMode(val label: String) {
    OPPO("OPPO"),
    HUAWEI_SAMSUNG("华为 / 三星");

    /** 循环到下一个取值（设置行点一下切一个）。 */
    fun next(): CrownMode = entries[(ordinal + 1) % entries.size]
}

// ---------------------------------------------------------------------------
// 「这条设置在本机能不能用」的统一规则（依赖系统版本的设置只有这一套写法）
//
// 规则（用户这一轮明确定下来的）：
//   1. 本机版本不够 → **设置页那一行根本不显示**（不占位，也不排进那几行的几何计算）；
//   2. 本机版本不够 → 这个设置的**有效值强制为安全默认**（见 [WatchSettings.coercedForThisDevice]）；
//   3. **不要**再写"点了弹 toast 拒绝切换"那种交互（用户看不到这一行，也就无从点起）。
//
// 为什么"隐藏"还不够、必须连值一起压掉：设置项一旦被隐藏，用户就**没有任何办法**改它；
// 如果存档或默认值里恰好是 true，内存里的有效值就是 true，而它背后的能力在低版本上
// 根本不存在 —— 界面看不到、行为也做不到，两边对不上，将来排查的人会被这个 true 误导。
// 所以隐藏和 coerce 必须成对出现，"用户看到的（没有这一项）"和"实际生效的（关闭）"才一致。
//
// 以后再加"依赖系统版本"的设置：① 在 [GatedSetting] 里加一项（写清要求的最低版本和理由），
// ② 在 [WatchSettings.coercedForThisDevice] 里加一行 coerce，③ 在设置页那一行外面标上
// `gate = …`（SettingsScreen 会按它过滤）。三处各一行，判定本身不用重写。
// ---------------------------------------------------------------------------

/**
 * 有"最低系统版本"要求的设置项。
 *
 * @param minSdk 这个设置**真正生效**所需的最低 API 级别（不是"能跑起来"的级别）
 */
internal enum class GatedSetting(val minSdk: Int) {
    /**
     * 「屏蔽系统手势」= `View.setSystemGestureExclusionRects`。
     *
     * Android 10（API 29）引入"应用可以申请把某块矩形排除在系统手势之外"。
     * 在那之前**没有对应的公开 API** —— 老的 `setSystemUiVisibility` 那套只能隐藏/让位
     * 系统栏，管不了"屏幕边缘的手势归系统还是归应用"。
     * 所以低版本上的退化行为是**完全不做任何事**（最安全的默认）：
     * 这一行不显示、值被压成 false、`GraphScreen` 里那个调用也不会发生。
     */
    BLOCK_SYSTEM_GESTURES(Build.VERSION_CODES.Q)
}

/**
 * 这条设置要求的最低 API 级别。
 *
 * 单独抽成函数（而不是到处直接读 [GatedSetting.minSdk]）是为了留一个统一入口：
 * 将来某个设置变成"条件式要求"，只改这里一处，调用点不用动。
 */
internal fun minSdkFor(setting: GatedSetting): Int = setting.minSdk

/**
 * 本机能不能用这条设置（设置页据此决定显示/隐藏，模型据此决定要不要 coerce）。
 *
 * [sdkInt] 默认取当前系统，留参数是为了让它成为纯函数、便于离线单测。
 */
internal fun supportsSetting(setting: GatedSetting, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= minSdkFor(setting)

/**
 * 把本机不支持的设置压到**安全默认值**（规则 2）。
 *
 * 取值选 false 而不是 true，是因为"屏蔽系统手势"这类开关的语义是"额外接管一段系统手势"：
 * 做不到的时候必须回到**系统默认行为**，而不是假装开着。
 */
internal fun WatchSettings.coercedForThisDevice(
    sdkInt: Int = Build.VERSION.SDK_INT
): WatchSettings {
    var s = this
    // 以后加门槛项：在这里补一行同形状的 coerce
    if (!supportsSetting(GatedSetting.BLOCK_SYSTEM_GESTURES, sdkInt)) {
        s = s.copy(blockSystemGestures = false)
    }
    return s
}

/** 当前在哪一屏。 */
sealed interface Route {
    /** 主界面：图像 + 手势 */
    data object Graph : Route

    /** 函数列表（**首屏**） */
    data object List : Route

    /** 编辑某一个函数 */
    data class Editor(val id: Long) : Route

    /**
     * 「半径·圆心」的半径输入屏（1.3.2）。
     *
     * 用户原话："你不要单独做面板了，你就做成调用函数创建界面的面板，
     * 只是上面的文字是半径并且不可点击。"
     *
     * 所以它不再是盖在图像界面上的浮层，而是一条**整屏路由**（和 [Editor] 同一层级）：
     * 抽屉 / 功能界面在它下面根本不组合，从根上杜绝"面板还在的时候拉起抽屉"。
     * 界面实现在 `RadiusEditorScreen`（复用 `FunctionEditorScreen` 的 `radiusMode`）。
     *
     * 是**临时屏**（`rememberScreen` 里不记来路）：它的返回/完成都回 [Graph]，
     * 而不是列表或功能界面。
     */
    data object Radius : Route

    /**
     * **截图界面**（1.3.14：从浮窗改回整屏路由）。
     *
     * 用户："你还是不要做成浮窗了，还是做成单独的界面吧，模糊背景和函数图像界面叠一起了"。
     * 整屏路由在它下面根本不会组合抽屉 / 图像界面，背景就是这一屏自己的
     * （开模糊背景时用全局模糊图，关掉时用纯白 / 纯黑）。
     *
     * 和 [Radius] 一样是**临时屏**：✗ / 返回键都回 [Graph]。
     */
    data object Capture : Route

    /**
     * 某一个函数/方程/点**或几何对象**的**属性编辑**界面（用户需求 #4）。
     *
     * 入口是函数列表里**长按**某一行；短按仍然是进 [Editor] 改表达式 ——
     * 这是有意的分工：短按是最常用的动作（改式子），长按才是"改外观/改名字"。
     * 退出后回列表（和 [Editor] 一样，本 App 是两层导航，没有更深的栈）。
     *
     * ## 为什么几何对象也复用这一个路由（用户："给所有函数列表里面的对象都加长按菜单，
     * 直接调用函数的长按菜单，但是注意要适配各个对象的特征"）
     *
     * 函数和几何对象取的是**同一个 id 序列**（[nextId] 里那一个 `idSeq`：
     * 函数、滑动条、几何对象全从它拿号），所以一个 `Long` 就能唯一定位到目标，
     * 不需要再加一个"是哪一类"的字段（那反而要维护两份真相、还会漂移）。
     * 界面侧按"先在 [functions] 里找、找不到再去 [objects] 里找"分派
     * （见 `GraphScreen` 里 `is Route.Properties ->` 那一支），两个都查不到才回列表。
     *
     * 和 [SliderProperties] 分开仍然是对的：滑动条的名字是**参数字母**（能直接拖值），
     * 和"改名字会牵动表达式"的对象完全是两件事。
     */
    data class Properties(val id: Long) : Route

    /**
     * 某一个几何对象的**详情页**（用户本轮新增：「只读、显示完全」那一屏）。
     *
     * 用户原话："给除了函数之外点击没有菜单的加一个点击显示详情页面，因为有些点和直线
     * 里面数值太长了，在这个详情页面只显示这个点的颜色，名字和它的坐标，直线就显示
     * 它的解析式，显示完全。"
     *
     * ## 和 [Properties] 的关系（**两个不同的东西，不许合并**）
     *
     * | | [Properties]（长按进） | [Details]（短按进，本轮新增） |
     * |---|---|---|
     * | 性质 | **可改**：名字（+ 类型只读） | **只读**：颜色 / 名字 / 完整数据 |
     * | 入口 | 列表行**长按**文字区 | 列表行**短按**文字区 |
     * | 起因 | 改名字 | **列表行放不下**：点和直线的数值被省略号截掉 |
     *
     * 用户对"短按"这条路径的要求是**只读**（"只显示这个点的颜色，名字和它的坐标"），
     * 所以它不能塞进属性屏那个"可编辑名字 + 键盘"的骨架里 —— 那会变成
     * "点一下进来就能改"（用户明确说过不要这个）。两屏共用的是**版面和浮层语言**
     * （`PropertyEditor.kt` 里同一套常量与色板），不是同一个 composable。
     *
     * ## 为什么仍然是一个只带 id 的路由
     *
     * 和 [Properties] 同一条理由：函数、几何对象、滑动条共用一个 `idSeq`
     * （见 [nextId]），所以一个 `Long` 就能唯一定位；这一屏只服务**几何对象**
     * （函数行的短按是"改表达式"，不是详情页），分派在 `GraphScreen` 那一侧。
     */
    data class Details(val id: Long) : Route

    /**
     * 某一条**滑动条**的属性编辑界面（用户需求 #4）。
     *
     * 和 [Properties] 分成两个变体、而不是合成一个带 nullable 字段的变体：
     * 两者的 id 取自**同一个 id 序列**（函数和滑动条共用一个 idSeq，见
     * [restoreSliders]），一个 `Properties(id)` 根本无法判断该去函数表还是
     * 滑动条表里找。分开之后 `when (route)` 的分支各自持有强类型的目标，
     * 也不需要在界面层写 `sliders.firstOrNull { … } ?: functions.…` 这种兜底。
     */
    data class SliderProperties(val id: Long) : Route

    /**
     * 「函数设置」（**短按 ⚙** 打开）。
     *
     * 用户本轮原话："短按设置只打开函数设置界面。" —— 这一屏只剩函数相关的那几项
     * （坐标系 / 网格 / 坐标轴 / 坐标数字 / 函数示例 / 屏蔽系统手势 / 按键缩放栏）。
     * 其余的（表冠 / 触摸 / 主题 / 应用信息）搬到长按 ⚙ 的 [MoreSettings] 里。
     */
    data object Settings : Route

    /**
     * 「更多菜单」（**长按 ⚙** 打开）：四个入口行，点进去是各自的子页。
     *
     * 用户本轮原话："把那些设置放到长按设置键的更多菜单里，分四个大块，点进去再设置。"
     *
     * 四个子页见 [CrownSettings] / [TouchSettings] / [ThemeSettings] / [About]。
     * 它们都是**临时屏**（进出不记来路，和 [Settings] / [Saves] 同构）。
     */
    data object MoreSettings : Route

    /** 「表冠适配」子页（更多菜单第一个大块）：表冠模式 + 灵敏度滑条。 */
    data object CrownSettings : Route

    /** 「计算设置」子页（更多菜单第一块下面）：调整计算精度（3 / 8 / 16 位小数）。 */
    data object CalcSettings : Route

    /** 「触摸设置」子页（更多菜单第二个大块）：屏蔽系统手势 + 按键缩放栏。 */
    data object TouchSettings : Route

    /** 「主题设置」子页（更多菜单第三个大块）：夜间模式（黑底 / 日间白底）。 */
    data object ThemeSettings : Route

    /**
     * 「功能界面」（用户需求 #9）。
     *
     * 它是函数列表**左边**的一屏：从列表屏幕左边缘往右滑进入。
     * 放进 [Route] 而不是做成列表里的一个抽屉，是因为这里的工具选完要
     * **自动回到图像界面**去取点（`Route.Graph`），是一条真正的平行导航，
     * 用路由表达最直接 —— 也才能被系统的返回键/BackHandler 统一管理。
     */
    data object Tools : Route

    /**
     * 「函数文件」列表（用户需求：第二屏左上角那颗**文件夹**按钮）。
     *
     * 它是**功能界面的下一层**（`Route.Tools` → 这里），不是和列表/功能界面并列的一屏：
     * 入口只有一个（文件夹按钮），返回也只有一条路（顶部下滑 / 返回键都回 [Tools]）。
     * 之所以不做成列表里的一个抽屉：文件浏览有自己的手势（点击打开、长按改名、左滑删除），
     * 和函数列表那三条手势的语义完全不一样，混在一屏里必然互相打架。
     */
    data object Saves : Route

    /**
     * 「应用信息」页（本轮新增：第一屏那颗 ⚙ 的**长按**进来）。
     *
     * 用户原话："长按之后打开应用信息页面（不要有标题），最上方是应用图标，
     * 然后下一排是应用名字（把应用名字改成 DrawFunc），再下一排是应用版本号，
     * 再下一排是 Version Code，然后再空一排，再下一排写"本应用使用 dsh 开发"，
     * 再下一排写"开发者 春羽"。注意动画和打开文件列表一样，注意放置返回键。"
     *
     * ## 它为什么是一个"只有类型、没有参数"的路由
     *
     * 这一屏显示的东西**全是进程级的常量**（应用图标 / 名字 / 版本号 / Version Code
     * 都从 `PackageManager` 现读，见 `AboutScreen.kt`），不带任何"看哪一个"的 id ——
     * 所以它和 [Settings] 同一类：`data object`，进出的路上没有任何要传的状态。
     *
     * ## 它是**临时屏**（不进 `lastNonGraphRoute` 的记忆）
     *
     * 入口只有一条（列表长按 ⚙），出口也只有一条（左上角返回键 / 系统返回键 → 回列表），
     * 和 [Settings] / [Saves] 完全同构。所以它在 [rememberScreen] 那个穷举 `when` 里
     * 必须归到"临时屏"那一支 —— 记成来路就会出现用户报过的那种坏味道：
     * "看过一次应用信息 → 回列表 → 进图像界面 → 上滑"掉回应用信息页。
     */
    data object About : Route

    /**
     * 「开发者模式」子页（更多菜单里**只有开了开发者模式才出现**的入口）。
     *
     * 三项：关掉开发者模式、回到初始界面（重看欢迎流程）、恢复默认设置。
     * 和 [CrownSettings] / [TouchSettings] 一样是临时屏：进来办完事回 [MoreSettings]。
     */
    data object Developer : Route
}

/**
 * 整个 App 的状态。
 *
 * 用普通类 + mutableStateOf 而不是 ViewModel：
 * 这个 App 只有一个界面、没有进程级共享状态，
 * 引入 lifecycle-viewmodel 只会多一层间接。
 */
class GraphState {

    var viewport by mutableStateOf(Viewport(0f, 0f))
        private set

    // 启动时不再自动创建正弦函数（用户要求删掉这条机制）：
    // 全新安装看到的是一张空列表，第一条函数由用户自己点 + 新建。
    var functions by mutableStateOf(listOf<PlotFunction>())
        private set

    /**
     * 滑动条列表（#3）。
     *
     * 每条绑定一个可绑定参数（`a`/`b`/`c`/`d`），同一个参数最多一条 ——
     * 用户写 `a*x+b` 时自动生成 `a`、`b` 两条，拖动它们曲线实时变。
     * 它不参与采样：取值通过 [com.functiondrawer.watch.engine.ParameterSet]
     * 在**编译期**折进表达式（见 `engine/Parameters.kt`），所以改值要重新采样一次。
     *
     * **这个列表里只会有"可以调的变量"**。用户把标量（角度/距离/面积）定性成
     * "和滑动条本质上都是变量，但是滑动条可以调，这个不能调" —— 所以标量**不在这里**：
     * 它是 [objects] 里的一个对象行，值由测量决定、只读（见 [scalarValues]）。
     * 自动生成的入口是 [ensureSlidersFor]（判据整段匹配 `ParameterSet.BINDABLE`，
     * 所以自动补出来的只会是 `a`~`d`）；用户也可以在滑动条的属性界面把名字改成
     * **任何一个小写字母**（用户要求："滑动条也可以用 f"），读文件/存档时由
     * [validSliders] 按同一把尺子收。叫 `a`~`d` 以外的字母的滑动条，值要靠在编辑器里
     * 指认那个字母才折得进表达式（见 [functionBindings]）。
     */
    var sliders by mutableStateOf(listOf<Slider>())
        private set

    var route by mutableStateOf<Route>(Route.List)

    /**
     * 全局设置（坐标系、网格/坐标轴/刻度的显隐）。
     *
     * **所有**写入都必须走 [applySettings]（它负责把"本机不支持"的设置压到安全值），
     * 不要在图里别处直接给 `settings` 赋值 —— 低版本上那样会让"已经隐藏起来的设置"
     * 悄悄留着一个 true 的有效值（规则见文件上方那段"依赖系统版本的设置"）。
     * 初始值也过一遍 coerce：全新安装、没有存档时它是数据类的默认值（`true`），
     * 而低版本上"默认开"是做不到的。
     */
    var settings by mutableStateOf(WatchSettings().coercedForThisDevice())
        private set

    /** 唯一的设置写入口：先按本机能力 coerce，再落地。 */
    private fun applySettings(next: WatchSettings) {
        settings = next.coercedForThisDevice()
    }

    /**
     * 改设置。
     *
     * 坐标系一变就**把所有函数清空**（用户明确要求）：
     * 笛卡尔下输入的 `y=f(x)` 换到极坐标毫无意义，反之亦然，
     * 留着只会变成一堆莫名其妙的曲线 —— 不如清空重来。
     */
    fun updateSettings(next: WatchSettings) {
        val previous = settings
        applySettings(next)
        if (next.coordinateSystem != previous.coordinateSystem) {
            // 1.3.1：用户把这条规则改了一半 ——
            // 选「是」：函数 + 功能界面画出的对象全部删除；
            // 选「否」：**函数也不清空**，只把列表里的方程按新坐标系改写
            //   （极坐标 `x → rcosθ`、`y → rsinθ`；复平面 `x → Re a`、`y → Im a`，
            //     见 `ObjectEquations.plotFunctionDisplayText`）。
            if (next.clearObjectsOnCoordSwitch) {
                clearAllFunctions()
                // 曲线对称规则也一起丢，否则清完还会有一条"自己长回来"的对称曲线。
                objects = emptyList()
                transformedCurves.clear()
                backToMove()
                showToast("已切换到${next.coordinateSystem.label}坐标系，原有函数已清空")
            } else {
                // 保留函数与对象：几何/源码都不动，只换显示方程。
                showToast("已切换到${next.coordinateSystem.label}坐标系，函数已按新坐标系改写")
            }
        }
    }

    /** 从存档恢复设置（不触发清空，存档里的函数和坐标系本来就是配套的）。 */
    fun restoreSettings(saved: WatchSettings) {
        // 老存档里可能是 true（那台设备版本够、或这份存档是从别的设备拷来的）：
        // applySettings 会在本机做不到时把它压成 false，随后的防抖存档就会写回 false。
        applySettings(saved)
    }

    /**
     * 把当前缩放夹回**新精度**允许的上限。
     *
     * 用户从 16 位切到 3 位时，原来的视野可能已经放大到 1e-10 ——
     * 此时不该继续停在"比 3 位精度还细"的尺度上，直接按新上限纠正（放大不再继续）。
     */
    fun clampViewportToPrecision() {
        val cur = viewport
        val min = Calc.precision.minUnitsPerPixel
        if (cur.widthPx <= 1f || !cur.unitsPerPixel.isFinite()) return
        if (cur.unitsPerPixel >= min) return
        viewport = cur.copy(unitsPerPixel = min)
    }

    // ---- 清空全部函数（功能界面长按 🗑 触发）----

    /**
     * 删掉所有函数。
     *
     * 这是一个"一下就全没了"的破坏性操作，所以必须留后路 —— 后路现在只有一条：
     * **撤销栈**（工具条上左箭头，`pushUndo` 已经把这一步记下来了）。
     * 原来那份 `clearedBackup` + 底部浮出的 2 秒撤销条在本轮被用户明确要求删掉了。
     */
    fun clearAllFunctions() {
        if (functions.isEmpty() && sliders.isEmpty()) return
        // 撤销点：在**改动之前**拍一张快照（含函数 / 滑动条 / 绑定表 / 对称规则），
        // 所以撤销那一下会把它们一起还回来 —— 不再需要旁边再留一份"清空备份"。
        pushUndo("清空全部")
        functions = emptyList()
        sliders = emptyList()
        // 清空全部 = 连"哪条函数里哪个字母指谁"也一起清掉（函数都没了，那张表没有意义）。
        // 注意撤销时这张表会**跟着快照一起回来**（本轮起它进了快照），
        // 所以"撤销之后绑定表偷偷变了"这件事不存在 —— 它就是当时的那个样子。
        functionBindings = emptyMap()
        selectedId = -1L
    }

    // ---- 新建但还没真正保存的函数 ----

    /**
     * 刚从「+」新建、还没按下确定的那个函数 id。
     *
     * 用户要求：**保存时如果什么都没输入，就不要添加这个函数**，只给个 toast 提示。
     * 所以这里要记住它是"新来的"，确定时表达式为空就把它丢掉（取消时同理）。
     */
    var justCreatedId by mutableStateOf<Long?>(null)
        private set

    fun markJustCreated(id: Long) {
        justCreatedId = id
        // 把"新建"那一步的步骤号认成这条草稿的：草稿被丢弃时一起作废
        // （见 [draftUndoStepId] 的说明）。
        draftUndoStepId = lastAddUndoStepId
    }

    fun clearJustCreated() {
        justCreatedId = null
    }

    /** 丢弃一个还没真正保存的函数：**不产生撤销记录**（它本来就不该存在）。 */
    fun discardFunction(id: Long) {
        // 这条草稿的"新建 + 之后每一次编辑"那几步撤销点一起作废：
        // 它们记的都是"这条草稿还不存在"的世界，留着就会出现
        // "按一次撤销，被丢掉的空函数又回来了"（详见 [draftUndoStepId]）。
        // 只对**刚新建的那一条**这么做：用户自己建的已有函数从来不在射程内。
        val step = draftUndoStepId.takeIf { justCreatedId == id }
        if (step != null) dropUndoStepsFrom(step)
        functions = functions.filterNot { it.id == id }
        if (selectedId == id) selectedId = functions.firstOrNull()?.id ?: -1L
        if (justCreatedId == id) justCreatedId = null
        if (draftUndoStepId == step) draftUndoStepId = null
    }

    /**
     * **离开编辑器时清掉"新建了但一个字都没写"的那条草稿**（治本）。
     *
     * ## 为什么要有一个"扫一遍"的入口
     *
     * `+` 是**先建一条空函数再进编辑器**的（这样编辑器里能边打边看）。丢掉它有两条
     * 明确的路：编辑器里的 **×** 和"空输入按确定"（两者最后都走到 [discardFunction]）。
     * 但只要**离开编辑器的方式**不在那两条路上（系统返回被别的东西接走、
     * 路由被别处改掉、去保存界面……），这条空函数就会一直挂在列表里，然后被写进文件 ——
     * 用户报的"保存文件的时候会在文件里添加一个没有表达式的函数"就是这么来的。
     *
     * 所以由界面在**每次路由变化**时调它一次：只要"当前不在这个草稿的编辑器里"、
     * 而它又确实一个字都没写（[hasNoExpression]），就丢掉。
     * 判断条件刻意写得很窄，避免误删用户的东西：
     *   * 只动 [justCreatedId] 指的那一条（用户**自己建的**已有函数从来不在射程内）；
     *   * 还在它自己的编辑器里就什么都不做（用户正在写）；
     *   * 写了任何内容就留着 —— 那时它是"还没按确定的新函数"，由用户自己决定去留，
     *     顺手把"新来的"这个标记收掉（见函数体里那一段说明）。
     *
     * [snapshotForFile] 里还有一道 [functionsToSave] 的过滤作为兜底：万一还有别的路径
     * 让空函数活到了保存那一刻，文件里也不会带上它。
     */
    fun discardBlankDraft() {
        val id = justCreatedId ?: return
        // 还在这个草稿自己的编辑器里：用户正在写，别动
        val editing = route as? Route.Editor
        if (editing != null && editing.id == id) return
        val fn = functions.firstOrNull { it.id == id }
        if (fn == null) {
            // 它已经被别处删掉了：只把"新来的"这个标记收干净
            clearJustCreated()
            return
        }
        if (fn.hasNoExpression()) {
            discardFunction(id)
            return
        }
        // 写了内容、而且人已经不在它的编辑器里了：把它**转正**（只是一个标记）。
        // 为什么多这一步：`justCreatedId` 留着的话，用户以后从列表再进这个编辑器、
        // 按 **×** 就会走"新建的那种直接丢掉"那条路，把**已经有内容**的函数删掉。
        // 转正之后 × 的语义是"还原成进编辑器时的样子"—— 那才是他预期的。
        clearJustCreated()
    }

    /** 最近一次采样的结果，以及每个函数的报错。 */
    var plot by mutableStateOf(PlotResult.EMPTY)
        private set

    /** 手势进行中：此时用低精度保证跟手，手指停下来再出高精度图。 */
    var interacting by mutableStateOf(false)

    var highQuality by mutableStateOf(true)

    private var idSeq = 1L

    /** 存档里带了视口信息，等量到真实尺寸后再补 width/height。 */
    private var pendingRestore = false

    val quality: Quality
        get() = when {
            interacting -> Quality.FAST
            highQuality -> Quality.NORMAL
            else -> Quality.FAST
        }

    // ---- 点击曲线：选中 + 信息卡 ----

    /** 最近被点中的曲线 id（null = 没选中，信息卡收起）。 */
    var tappedId by mutableStateOf<Long?>(null)
        private set

    val tappedFunction: PlotFunction?
        get() = functions.firstOrNull { it.id == tappedId }

    /**
     * 最近被点中的**几何对象** id（null = 没有）。
     *
     * 用户本轮要求："在函数图像界面（画布上）直接点画出来的那些对象 —— 点、角度、面积、长度
     * —— 要显示它的属性。"
     *
     * ⚠️ **和 [tappedId] 互斥**（见 [onTapCanvas]）：信息卡只有一张，
     * 同时只可能是"曲线的卡"或"对象的卡"。两个状态各自可空，但**永远不会同时非空** ——
     * 这是"点另一条曲线 / 点空白时卡片正确切换或收起、绝不两张叠着"的保证。
     */
    var tappedObjectId by mutableStateOf<Long?>(null)
        private set

    /** 当前被点中的几何对象（**按 id 现查**：对象被删掉之后它自然变成 null，卡片跟着收起）。 */
    val tappedObject: GraphObject?
        get() = tappedObjectId?.let { id -> objects.firstOrNull { it.id == id } }

    /**
     * 最后一次点画布的屏幕位置（点空白处会把 [tappedId] 清掉，但位置留着）。
     *
     * #7 后半要算"**手指附近**半径 = 屏宽 1/10 内有没有交点/极值点"，
     * 而 `tappedId` 只说"点中了哪条曲线"、没说点在哪 —— 所以要单独记住这个像素位置。
     * 不能拿屏幕中心代替：用户点在曲线最右端时，屏幕中心那个"附近"
     * 和他手指那一处完全不是一回事。
     */
    var lastTapPx by mutableStateOf(0f)
        private set
    var lastTapPy by mutableStateOf(0f)
        private set

    /**
     * 画布被点击（**只在「移动」模式下会被调用**，见 [toolTap] 和 `GraphScreen` 的手势）。
     *
     * ## 命中顺序 = 用户给的优先级表（**只有三类**）
     *
     * 用户原话（更正后）："如果有多个对象重合，优先显示点，再是线段，再是函数曲线，
     * 角度、面积和距离在图像上面能显示，不用管。"
     *
     * ```
     *   ① 点                → 属性卡
     *   ② 线段 / 直线 / 射线 → 属性卡
     *   ③ 函数曲线          → 信息卡（老行为：解析式 + 零点/顶点/交点/极值点）
     *   ④ 都没命中          → 两张卡都收起
     * ```
     *
     * **高优先级的一类只要有命中就不再往下看**（不是三类放一起比距离），
     * 所以"点压在线上"这类重合处的结果是稳定的。
     *
     * ## 角度 / 面积 / 距离：不参与，点击**穿透**过去
     *
     * 它们是用户明确划出去的三类："在图像上面能显示，不用管。"
     * 所以这里**不判**它们的图形 —— 画布上点扇形/多边形/尺寸线**不会弹属性卡**，
     * 也**不会吃掉点击**：那一下会穿透到下面真正在优先级里的那三类
     * （多边形里穿过的线段、尺寸线压着的点……），三类都没有就当作点了空白。
     * 选"穿透"而不是"什么都不做"的理由：**不会让人有"点不动图"的感觉** ——
     * 之前面积是"点进去就选中"，一块大三角形会把里面穿过的曲线和线段全挡住，
     * 想选那条线只能绕到多边形外面点，那才是"点不动"。
     * 它们的开关（图形显示 / 数值显示）照旧走**列表行**和图像界面上的色点 / `123`。
     *
     * 点中的对象 / 曲线**互斥**（[tappedObjectId] ↔ [tappedId]）：任何时刻最多一张卡，
     * 点另一处是"切换"而不是"叠一张新的"；再点**已经选中的那一个**保持选中（卡片不收）。
     */
    fun onTapCanvas(px: Float, py: Float) {
        lastTapPx = px
        lastTapPy = py

        // ①② 用户给的优先级表里的前两类（点 → 线段/直线）；度量不在里面
        val obj = objectAt(px, py)
        if (obj != null) {
            tappedObjectId = obj.id
            tappedId = null // 互斥：曲线卡收起
            return
        }

        // ③ 函数曲线（现成的做法与容差）
        val slop = (viewport.widthPx * TAP_SLOP_FRACTION).coerceAtLeast(MIN_TAP_SLOP_PX)
        val hit = hitTestCurves(plot, px, py, slop)
        if (hit != null) {
            tappedObjectId = null
            tappedId = hit
            selectedId = hit
            return
        }

        // ④ 都没有（含"点在度量图形上"）→ 两张卡都收起。
        // 注意：这不是"点了没反应"——它和点空白是同一条路：先收卡片；本来就没卡片时
        // 确实什么都不发生，但旁边那些真正在优先级里的对象/曲线随时可以点。
        tappedId = null
        tappedObjectId = null
    }

    /** 收起两张卡（切工具、清屏、删对象时用；画布点击不必调它 —— 那边自己会覆盖）。 */
    fun clearTappedCards() {
        tappedId = null
        tappedObjectId = null
    }

    /**
     * 信息卡上的两行文字：零点 / 顶点。
     *
     * 只有**显函数**才谈得上"零点/顶点"；极坐标、参数方程、隐函数没有
     * 公认的对应概念，所以直接返回 null，卡片就只显示解析式 ——
     * 宁可不显示，也不编一个数字出来。
     */
    fun featureLines(fn: PlotFunction): Pair<String?, String?> {
        if (fn.mode != PlotMode.EXPLICIT) return null to null
        val f = runCatching {
            Expression.compile(
                // 取坐标替换：卡片上的零点/顶点必须和**画出来的那条曲线**同源，
                // 所以这条路径和采样路径一样要过一遍 x(A) → 数值。
                withPointCoordinates(stripExplicitPrefix(fn.src1)),
                listOf("x"),
                effectiveAngleUnit(fn).isDegrees,
                // 参数与可调用函数表都要和采样那条路径**完全一致**，
                // 否则 `a*x+b`（或 `2a(x)`）在图上和这张卡片里会是两条不同的曲线。
                parameters(),
                callables()
            )
        }.getOrNull() ?: return null to null

        val xMin = viewport.worldLeft
        val xMax = viewport.worldRight
        if (!(xMax > xMin)) return null to null

        val feats = FeatureFinder.analyze(f, xMin, xMax, limit = 4)

        val roots = if (feats.roots.isEmpty()) {
            "零点：无"
        } else {
            "零点：" + feats.roots.joinToString("  ") { formatNumber(it) }
        }
        val extrema = if (feats.extrema.isEmpty()) {
            "顶点：无"
        } else {
            "顶点：" + feats.extrema.joinToString("  ") {
                "(${formatNumber(it.x)}, ${formatNumber(it.y)})"
            }
        }
        return roots to extrema
    }

    /** 去掉用户可能写的 `y =` 前缀，不然解析会失败、特征算不出来。 */
    private fun stripExplicitPrefix(src: String): String {
        val t = src.trim()
        val m = Regex("^y\\s*=\\s*", RegexOption.IGNORE_CASE).find(t)
        return if (m != null) t.substring(m.range.last + 1).trim() else t
    }

    // -----------------------------------------------------------------------
    // 取坐标：表达式里的 `x(A)` / `y(A)` → 点 A 的坐标数值
    //
    // 用户需求：「点击之后会在输入框显示 x()（这是一个整体），默认定位到括号中间，
    // 括号里输入一个点的名字，把这个点的 x 坐标作为数值参与运算。」
    //
    // 引擎不认识"点"（它只有变量/参数/常量/函数四类名字），所以这一层的工作是
    // **在把表达式送进引擎之前**把 `x(A)` 换成字面量 `(数值)`。替换规则本身在一个
    // 纯 Kotlin 文件里（[substitutePointCoordinates]，能被离线单测钉住），
    // 这里只负责两件事：① 从 `objects` 里挑出**能取坐标的点**并做成名字表；
    // ② 在真正"算值"的那几条路径上用它。
    //
    // 铁律：**用户输入的原文一个字都不改**。编辑器里的文本、列表里的名字/摘要、
    // 存进文件的内容，用的都是 `functions` 里那份原始 src1/src2；
    // 只有"交给引擎算"的那份副本才经过替换（见 [functionsForSampling]）。
    // -----------------------------------------------------------------------

    /**
     * 当前**可以取坐标的点**：点名 → 坐标。
     *
     * 只收 [GraphPointObject]（点），直线、圆、度量这些对象**不算**：
     *   * 用户说的是"括号里输入一个点的名字，把这个点的 x 坐标作为数值参与运算"；
     *   * 一条直线没有"x 坐标"这回事，圆也没有（它有圆心，但那是另一个点）。
     *     真要取圆心，用户自己描一个点就是了 —— 这比在这里替用户猜语义干净。
     *
     * 细节：
     *   * **大小写精确**匹配（`.associate` 用的是 HashMap 的 equals）——
     *     点名叫 `A` 就不能写成 `a`，`a` 是可绑定参数，两者是两回事；
     *   * 名字必须是 [isPointName] 那种大写字母序列，所以复平面模式下
     *     以结果文本（`2`、`2+3i`）当名字的那些点不会混进来；
     *   * **隐藏的点照样能取**：`visible = false` 只是"不画"，
     *     不该让别的函数跟着算不出来（`callables()` 对隐藏函数也是这个口径）；
     *   * 每次采样前现算：点是被工具随时加进来的，缓一份就要再想"什么时候失效"。
     *     表很小（几个点），构造代价可以忽略。
     */
    fun pointCoordinates(): Map<String, GPoint> = objects
        .filterIsInstance<GraphPointObject>()
        .filter { isPointName(it.name) }
        .associate { it.name to it.point }

    /**
     * **标量对象当前的值**：名字 → 数值（角度是度、距离和面积是世界单位）。
     *
     * 用户原话："就是这些标量能直接写进表达式当数值用。" 以及把它定性的一句：
     * "它和滑动条本质上都是变量，但是滑动条可以调，这个不能调。"
     *
     * 所以标量和可绑定参数（`a`~`d`）是**同一个概念的两面**：
     *
     * | | 谁 | 值从哪来 | 用户能不能改 | 在列表里长什么样 |
     * |---|---|---|---|---|
     * | 可调的变量 | `a` `b` `c` `d`（[sliders]） | 用户拖滑动条 | 能 | **滑动条行**（`SliderRow`，可以拖） |
     * | 不能调的变量 | `f` `g` `h`…（[objects] 里的度量） | 这一次测量算出来的 | **不能** | **对象行**（`GraphObjectRow`：名字 + 数值，点色点显隐、左滑删除） |
     *
     * 于是有两条硬规则（都有注释和测试盯着）：
     *   * 标量**不会**长出滑动条：[parametersNeedingSliders] 整段匹配 `ParameterSet.BINDABLE`，
     *     而标量取名时就跳过了 `a`~`d`（见 [SCALAR_NAME_RESERVED]）；
     *   * 标量**不进** [sliders] 那条列表：度量对象只进 [objects]，
     *     而列表里 `SliderRow` 只由 [sliders] 渲染、`GraphObjectRow` 只由 [objects] 渲染
     *     （两个不同的 composable），所以用户一眼能看出"这个能拖、这个只能看"。
     *
     * 实现走的是**和取坐标完全同一条路**：这两张表都由
     * [substitutePointCoordinates] 在采样前折成字面量（见 [withPointCoordinates] /
     * [functionsForSampling]），引擎一个字节都没改，用户写的原文也一个字都没改
     * （编辑器、列表摘要、`.fdf` 存档看到的仍然是 `f*x`）。
     *
     * ## 一处已知代价
     *
     * 标量是**按名字**写进表达式的：`f*x` 里的 `f` 只是一个名字。
     * 所以那个度量对象被**删掉**之后，表达式里的 `f` 会回到
     * "不认识的名字 'f'"这条报错 —— 这**和 `x(A)` 里点名没了之后一模一样**：
     * 行为一致、报错明确、绝不会悄悄算成一个错的数。
     *
     * **改名不会再断**（本轮起）：[renameObject] 会把所有函数表达式里的旧名字
     * 按整体标识符换掉（[renameIdentifierIn]）。原来这里写着"改名同步引用不在本轮范围"，
     * 现在那句话已经不成立了 —— 留着它反而会让后人以为改完还得自己去翻引用。
     *
     * 其它细节：
     *   * 只收**名字合法**的（[isScalarName]：纯小写拉丁字母）—— 名字是从名字表里分配的，
     *     这里再挡一道，防止将来某种坏数据让 `x`/`e` 这种名字混进来变成常数；
     *   * 只收 [GraphMeasureObject]（角度/距离/面积）。点、直线是**几何**对象，
     *     它们的意义是坐标/位置，要取坐标请用 `x(A)` / `y(A)`；
     *   * 隐藏的度量和可见的一样参与（`visible = false` 只是"不画那个数值标签"，
     *     不代表这个量不存在 —— 和 `pointCoordinates()`、"隐藏的函数也能被调用"同一个口径）；
     *   * 表很小（几个度量），每次采样前现算，不用缓存。
     */
    fun scalarValues(): Map<String, Double> = objects
        .filterIsInstance<GraphMeasureObject>()
        .filter { isScalarName(it.name) }
        .associate { it.name to it.value }

    // ---------------------------------------------------------------------------
    // 名字绑定表（用户需求："标量也可以用 a，滑动条也可以用 f……至于 e、x、y 这些可能
    // 冲突的字母，请你给函数编辑界面输入的这些字母单独分组编 id"）
    //
    // ## 它解决的是哪一件事
    //
    // 命名规则不再"跳过 a~d / x / y / e"（见 `FunctionTools.nextScalarName`），
    // 所以**同一个字母可能有两层含义**：一条叫 `a` 的滑动条、一个叫 `a` 的标量对象
    // （用户在编辑器里写 `a` 时，引擎会先认领它当可绑定参数）。用户要的解法是
    // "这个字母指谁由我在编辑器里点一下说清"，那张"说清"的表就是这里。
    //
    // ## 模型
    //
    // ```
    //   functionBindings: Map<函数 id, Map<字母, 对象 id>>
    // ```
    //
    //   * **键 = 用户在编辑器里敲的那个字母**（`a`、`f`、`x`…，就是表达式文本里的写法）；
    //   * **值 = 对象的 id**：滑动条的 id 或者标量对象（角度/距离/面积）的 id。
    //     两种 id 都在同一个 `idSeq` 里分配、全局唯一，所以一个 Long 就够，
    //     不需要再存一个"是哪一类"（那反而要维护两份真相）；
    //   * **每条函数一份**：这是关键 —— 用户说的是"给函数编辑界面输入的这些字母
    //     单独分组编 id"，所以 `a` 在第一条函数里可以指滑动条、在第二条里指那个标量，
    //     互不影响；
    //   * 表**只在 UI 层**，引擎一个字节都不知道它（采样前折成数值，见
    //     [functionsForSampling] / `PointCoordinates.resolveScalarValues`）。
    //
    // ⚠️ **有意没放在 `PlotFunction` 上**（用户建议的写法是"在函数上存一张绑定表"）：
    // `PlotFunction` 在 `engine/PlotModel.kt` 里，而本轮明确不许改 `engine/` 目录
    // （163 个离线测试在那儿）。表放在这里同样是"每条函数一份"，
    // 只是键换成函数 id；迁移到 `PlotFunction.nameBindings` 时把 [bindingsOf]
    // 的所有调用点改成 `fn.nameBindings` 即可。序列化需要配合的地方见报告。
    // ---------------------------------------------------------------------------

    /**
     * 每条函数的名字绑定表；**空表是默认状态**（没指认过任何字母）。
     *
     * 用 `mutableStateOf` 而不是普通字段：它要进采样的 key（见 `GraphScreen` 里那个
     * `LaunchedEffect`），绑定一变必须**立刻重采样** —— 否则用户点完候选条，
     * 图还是按老含义画的，看起来就是"点了没反应"。
     */
    var functionBindings by mutableStateOf<Map<Long, Map<String, Long>>>(emptyMap())
        private set

    /** 这条函数已经指认过的字母（没指认过就是空表）。 */
    fun bindingsOf(functionId: Long): Map<String, Long> = functionBindings[functionId] ?: emptyMap()

    /** 指认一个字母：`a` → 某个滑动条 / 标量对象的 id（用户在候选条上点了那一颗）。 */
    fun bindName(functionId: Long, letter: String, objectId: Long) {
        val cur = bindingsOf(functionId)
        if (cur[letter] == objectId) return
        functionBindings = functionBindings + (functionId to (cur + (letter to objectId)))
    }

    /** 取消指认：这个字母回到"按老规则自动解析"（再点一下已经选中的那颗 chip 就是它）。 */
    fun unbindName(functionId: Long, letter: String) {
        val cur = bindingsOf(functionId)
        if (letter !in cur) return
        val next = cur - letter
        functionBindings =
            if (next.isEmpty()) functionBindings - functionId else functionBindings + (functionId to next)
    }

    /** 点候选条上的一颗 chip：已经选中的那颗 = 取消指认，否则 = 指认成它。 */
    fun toggleNameBinding(functionId: Long, letter: String, objectId: Long) {
        if (bindingsOf(functionId)[letter] == objectId) {
            unbindName(functionId, letter)
        } else {
            bindName(functionId, letter, objectId)
        }
    }

    /** 采样前折绑定表要用的两张表：滑动条 id → 当前值、标量对象 id → 当前值。 */
    fun sliderValuesById(): Map<Long, Double> = sliders.associate { it.id to it.value }

    fun measureValuesById(): Map<Long, Double> = objects
        .filterIsInstance<GraphMeasureObject>()
        .associate { it.id to it.value }

    /**
     * 编辑器要显示的"这个字母指谁"候选（纯逻辑在 `PointCoordinates.nameChoicesIn`）。
     *
     * 参数方程两个框**一起扫**：`t` 是采样变量，两个框里的字母是同一套名字空间，
     * 分开看会出现"x(t) 里那个 f 有候选、y(t) 里那个 f 没有"这种分裂。
     */
    fun nameChoicesOf(fn: PlotFunction): List<NameChoice> = nameChoicesIn(
        src = if (fn.mode == PlotMode.PARAMETRIC) fn.src1 + " " + fn.src2 else fn.src1,
        sliderParams = sliders.associate { it.param to it.id },
        scalarNames = objects.filterIsInstance<GraphMeasureObject>().associate { it.name to it.id },
        scalarLabels = objects.filterIsInstance<GraphMeasureObject>()
            .associate { it.name to it.kind.label },
        bound = bindingsOf(fn.id)
    )
        // 1.2.57：滑动条现在**任何单字母都自动生效**（见 [parameters]），
        // 所以"这个字母只是个滑动条"不再是一条需要用户处理的提示 ——
        // 用户点名"删掉在函数界面显示字母是滑动条的提示"。
        // 只过滤"候选全是滑动条"的字母；滑动条 + 标量的**真冲突**仍然留给候选条。
        .filterNot { choice ->
            choice.candidates.all { it.kind == NameCandidateKind.SLIDER }
        }

    /**
     * 把源码里的取坐标写法（`x(A)`）和**标量名**（`f`）换成数值
     * （[substitutePointCoordinates] 的便捷入口）。
     *
     * 只给"要拿去算值"的字符串用。**不要**拿它的结果回写 `fn.src1` ——
     * 那会把用户写的 `x(A)` / `f` 永久换成 `(1.5)`，点一动、度量一变式子就死了。
     */
    fun withPointCoordinates(src: String): String =
        substitutePointCoordinates(src, pointCoordinates(), scalarValues())

    /**
     * **采样用的函数表**：src1 / src2 里已经做过"取坐标 + 标量值"替换的副本。
     *
     * 为什么必须传副本而不是就地改：用户原文（`x(A)`、`f*x`）要一直留在编辑器、
     * 列表、存档里 —— 那是他自己的式子。替换只是"这一次算的时候，
     * 把 A 当成现在这个坐标、把 f 当成这个度量现在的数值"。
     *
     * 表里既没有点、也没有标量时直接返回原表（不做任何 map 拷贝）：绝大多数用户
     * 根本没画过几何对象，这条路径就是零开销。
     *
     * 副产品：`.fdf` 存档里存的仍然是 `x(A)` / `f*x`，所以在另一台设备上打开、
     * 或者点/度量被删掉之后，表达式会回到"不认识的名字"的报错 —— 而不是
     * 变成一串看不懂的数字。这是有意的（和用户"原文一个字都不改"的要求一致）。
     */
    fun functionsForSampling(): List<PlotFunction> {
        val points = pointCoordinates()
        val scalars = scalarValues()
        // 短路的第三个条件是本轮加的：只有绑定表的时候也要替换
        // （用户把 `a` 指给一个叫 `a` 的滑动条/标量时，自动表里可能什么都没有）。
        if (points.isEmpty() && scalars.isEmpty() && functionBindings.isEmpty()) return functions
        val sliderValues = sliderValuesById()
        val measureValues = measureValuesById()
        return functions.map { fn ->
            // **先查绑定表**（用户在这条函数上指认过的字母说了算），
            // 没指认的字母才落到老规则上（同名标量 / 引擎的可绑定参数）——
            // 规则本体在 `PointCoordinates.resolveScalarValues`，那边有离线单测。
            val table = resolveScalarValues(
                auto = scalars,
                bound = bindingsOf(fn.id),
                sliderValues = sliderValues,
                measureValuesById = measureValues,
                // "可见的滑动条"就是引擎 `ParameterSet` 里真正存在的那些
                // （`toParameterSet()` 只收 visible 的）—— 同名滑动条优先就是按它判的
                sliderParams = sliders.filter { it.visible }.map { it.param }.toSet()
            )
            fn.copy(
                src1 = substitutePointCoordinates(fn.src1, points, table),
                src2 = substitutePointCoordinates(fn.src2, points, table)
            )
        }
    }

    fun nextId(): Long = ++idSeq

    /** 首次拿到真实尺寸时初始化视口，之后只跟随尺寸变化。 */
    fun attachSurface(widthPx: Float, heightPx: Float) {
        val cur = viewport
        viewport = when {
            cur.widthPx <= 1f || cur.heightPx <= 1f -> {
                if (pendingRestore) {
                    // 从存档恢复过中心点和缩放：只补尺寸，别把用户的视野重置掉
                    pendingRestore = false
                    cur.resized(widthPx, heightPx)
                } else {
                    Viewport.forRange(widthPx, heightPx, -DEFAULT_HALF_RANGE, DEFAULT_HALF_RANGE)
                }
            }
            cur.widthPx != widthPx || cur.heightPx != heightPx -> cur.resized(widthPx, heightPx)
            else -> cur
        }
    }

    /** 采样结果由界面在后台线程算好后交回来。 */
    fun applyPlot(result: PlotResult) {
        plot = result
        // 曲线变了（滑动条拖值 / 播放 / 改表达式）→ 吸在曲线上的点重新贴回去，
        // 再让"跟着这些点画出来的线/度量"跟着动（用户明确要求的第二种情形）。
        refreshGeometry()
    }

    /**
     * 从存档恢复。
     *
     * ## 函数列表**可以为空**（本轮「设置没被记住」那个 bug 的收尾）
     *
     * 存档里一条函数都没有不是"这份存档作废"，而是"这位用户确实还没有函数"
     * （切坐标系会清空全部函数、删掉最后一行、清屏都会到这个状态）。
     * 上一版这里写的是 `if (functions.isEmpty()) return`，而调用点外面那句
     * `Store.load(context)?.let { … }` 曾经也把整份存档丢掉了 —— 修完 `Store.load`
     * 之后，这一句仍然是"设置回来了、视野却被跳过"的最后一个缺口。
     *
     * 所以现在：**空表时只恢复视野**，不动函数列表（内存里那份就是空列表，
     * 第一条函数由用户自己新建 —— 启动时不再自动塞一条正弦函数）。
     *
     * 视野的合法性再判一次：`unitsPerPixel` 坏掉时宁可继续用默认视野，
     * 也不能把 NaN 塞进 `Viewport`（那会让整块画布算不出任何像素）。
     */
    fun restore(
        functions: List<PlotFunction>,
        selectedId: Long,
        centerX: Double,
        centerY: Double,
        unitsPerPixel: Double
    ) {
        if (functions.isNotEmpty()) {
            this.functions = functions
            this.selectedId =
                if (functions.any { it.id == selectedId }) selectedId else functions.first().id
            idSeq = functions.maxOf { it.id }
            // 这些函数**不是刚加的**：和打开文件同一条尺子（见 `loadFileState`），
            // 把"已揭示"直接对上新列表，冷启动时 `consumeReveal` 就返回空集。
            //
            // 用户反馈 6：冷启动列表里有函数、模糊背景里却没有曲线 ——
            // 原因之一就是这里没对上新列表，冷启动被当成"全部都是新函数"，
            // 曲线在背景抓帧的那一刻还停在揭示动画的 0 进度上（等于没画）。
            revealedIds = functions.map { it.id }.toSet()
        }
        if (unitsPerPixel.isFinite() && unitsPerPixel > 0.0) {
            // 合法值直接收下（Store.load 已经按存档里的精度夹过一次了；
            // 这里不再用 Calc.precision 夹，避免它此刻还是默认的 8 位而误伤 16 位存档）。
            viewport = Viewport(0f, 0f, centerX, centerY, unitsPerPixel)
            pendingRestore = true
        }
    }

    /**
     * 从存档恢复滑动条（#3）。
     *
     * 注意要把 [idSeq] 一起顶上去：滑动条和函数共用一个 id 序列，
     * 否则新建的滑动条会拿到和已有函数相同的 id，列表的 key 就撞了。
     */
    fun restoreSliders(saved: List<Slider>) {
        // 和打开文件同一条尺子（见 validSliders）：存档里万一带了非法名字（空/多字母/
        // 重名），也不能让它进到 [parameters]，那一层再把非单字母的名字挡在 ParameterSet 外面
        // （那里对不认识的名字是直接抛异常的）
        sliders = validSliders(saved)
        if (sliders.isNotEmpty()) {
            val maxSliderId = sliders.maxOf { it.id }
            if (maxSliderId > idSeq) idSeq = maxSliderId
        }
    }

    /**
     * 从存档恢复**几何对象**（点/线/度量）与名字绑定表（1.2.57 新增）。
     *
     * 用户报的 bug："关闭应用再打开，之前描的点会消失，图形还在" ——
     * 原因是 `Store` 只存函数/滑动条/设置，`objects` 一个字节都没落库。
     *
     * 两件事和打开 `.fdf` 文件保持同一条尺子：
     *   * **度量的名字要重排**（文件里没有名字字段，见 [reNameMeasures]）；
     *   * **[idSeq] 要顶上去**（对象和函数共用一套 id，不顶的话下一次新建会撞 key）。
     *
     * 绑定表（函数 id → 字母 → 对象 id）也一起恢复：它引用的是**对象 id**，
     * 而对象 id 在存档里原样保留，所以能对上。不恢复它的话，
     * 用户指认过的"这个字母指那个量"会在重启后悄悄退回自动规则。
     */
    fun restoreObjects(
        saved: List<FunctionFileFormat.FileObject>,
        bindings: Map<Long, Map<String, Long>> = emptyMap(),
        /** 施工说明：对象 id → recipe（文件 / 本地存档里的 `R|O|…` 段）。 */
        recipes: Map<Long, ObjectRecipe> = emptyMap()
    ) {
        val restored = reNameMeasures(
            saved.map { it.toGraphObject() }
                .map { o -> recipes[o.id]?.let { o.withRecipe(it) } ?: o }
        )
            .filter { it.appearsInList }
            // 存档不存 recipe：把"直线/线段/射线"的施工说明按锚点认回来，
            // 否则重开软件后拖点、线不跟着动（用户实测的 bug）。
            .withInferredLineRecipes()
        objects = restored
        if (restored.isNotEmpty()) {
            val maxId = restored.maxOf { it.id }
            if (maxId > idSeq) idSeq = maxId
        }
        functionBindings = bindings
    }

    /**
     * 恢复**函数形态**派生曲线的施工说明（本地存档和打开文件都走这里）。
     *
     * `CurveRecipeEntry` 还要记"上一次真正写进去的表达式"，用来判断用户有没有手动改过；
     * 从文件恢复时那份表达式就是函数自己的 `src1` / `src2`（文件里存的就是这一份），
     * 所以拿它填进去 —— 于是加载后重建不会把用户改过的表达式覆盖掉。
     */
    fun restoreCurveRecipes(recipes: Map<Long, ObjectRecipe>) {
        curveRecipes.clear()
        for ((fid, recipe) in recipes) {
            val fn = functions.firstOrNull { it.id == fid } ?: continue
            curveRecipes[fid] = CurveRecipeEntry(recipe, fn.src1, fn.src2)
        }
    }

    /**
     * 读盘时补回"两点定线"的 [ObjectRecipe]（见 [inferLineFromPointsRecipe]）。
     *
     * 只处理直线 / 线段 / 射线这一条工具链：它们一定是"从两个点对象画出来"的。
     * 垂线 / 平行线 / 切线 / 角平分线 / 对称直线的锚点不一定是点对象，
     * 硬认容易误绑，宁可保持原来的"快照、不跟随"。
     *
     * 这是**老文件**的补救（1.3.8）：1.3.9 起文件里直接存 `R` 行，
     * 有 recipe 的对象根本走不到这里。
     */
    private fun List<GraphObject>.withInferredLineRecipes(): List<GraphObject> {
        val points = filterIsInstance<GraphPointObject>()
        if (points.isEmpty()) return this
        return map { o ->
            if (o is GraphLineObject &&
                o.recipe == null &&
                o.role.orEmpty() in LINE_ROLES_FROM_POINTS
            ) {
                o.copy(recipe = inferLineFromPointsRecipe(o.line, points))
            } else {
                o
            }
        }
    }

    /** 重新采样。视图或函数有任何变化都要调它。 */
    fun replot() {
        // 三个"编译期输入"都要和 GraphScreen 那条主采样路径一致：
        // 度制（全局默认）、滑动条参数、可调用函数表。少任何一个，
        // 同一个表达式在两条路径上就会画出两条不同的曲线。
        // **取坐标（x(A) → 数值）和标量值（f → 数值）也是"编译期输入"之一**：
        // 走 [functionsForSampling] 拿替换过 src1/src2 的副本，两条路径才画同一条线。
        plot = Plotter.plot(
            functionsForSampling(),
            viewport,
            quality,
            settings.angleUnit.isDegrees,
            parameters(),
            callables()
        )
    }

    fun resetView() {
        val w = viewport.widthPx
        val h = viewport.heightPx
        if (w <= 1f || h <= 1f) return
        viewport = Viewport.forRange(w, h, -DEFAULT_HALF_RANGE, DEFAULT_HALF_RANGE)
    }

    /** 以屏幕中心为焦点缩放。表冠、按键走这里。 */
    fun zoomByFactor(factor: Double) {
        val vp = viewport
        if (vp.widthPx <= 1f) return
        if (!factor.isFinite() || factor <= 0.0) return
        viewport = vp.zoomBy(factor, vp.widthPx / 2f, vp.heightPx / 2f)
    }

    fun panBy(dx: Float, dy: Float) {
        viewport = viewport.panBy(dx, dy)
    }

    fun transformBy(panX: Float, panY: Float, zoom: Float, focusX: Float, focusY: Float) {
        var vp = viewport
        if (panX != 0f || panY != 0f) vp = vp.panBy(panX, panY)
        if (zoom != 1f) vp = vp.zoomBy(zoom.toDouble(), focusX, focusY)
        viewport = vp
    }

    // ---- 函数增删改 ----

    /** 当前编辑/选中的函数。 */
    var selectedId by mutableStateOf(1L)

    val selected: PlotFunction?
        get() = functions.firstOrNull { it.id == selectedId } ?: functions.firstOrNull()

    fun addFunction(mode: PlotMode = PlotMode.EXPLICIT, src1: String = "", src2: String = ""): Long {
        // 撤销点：加之前先拍快照（"撤销：新建函数"撤掉的就是这一条）。
        // 步骤号留给 [markJustCreated]：`+` 那条路建出来的是一条**草稿**，
        // 草稿被丢弃时这一步（连同它之后的编辑步骤）要一起作废。
        lastAddUndoStepId = pushUndo("新建函数")
        val id = nextId()
        val fn = PlotFunction(
            id = id,
            mode = mode,
            src1 = src1,
            src2 = src2,
            colorIndex = functions.size % 6,
            // 自动取名（#2）：函数走小写 a(x)、b(x)…；
            // 隐函数（方程）走大写 A、B…，和"点"共用一套大写序号（见 nextUpperName）
            name = if (mode == PlotMode.IMPLICIT) nextUpperName() else nextCurveName()
        )
        functions = functions + fn
        selectedId = id
        return id
    }

    // ---- 自动命名（用户需求 #2）----
    //
    //   * 函数（曲线）：`a(x)`、`b(x)`… 小写，按字母表推移
    //   * 方程（隐函数）与点：`A`、`B`… 大写，也是按字母表推移
    //
    // ⚠️ **这里说的是"函数"那一族（列表里的曲线/方程），不是几何对象**（用户本轮
    // 那句"除了点之外的所有对象的名字应该都是小写字母"针对的是几何对象）。
    // 隐函数的名字同时是它的**可调用名**（`A(x)=…` 里那个 `A`），而它和点的名字
    // 共用一套大写序号（这样方程 `A` 和点 `A` 不会在列表里撞成两行同名）。
    // 几何对象那一套在文件下方（[nextObjectName]）——**只有点是大写**，
    // 直线/角度/距离/面积全是小写。
    //
    // 取名策略是"**取第一个还没被占用的名字**"，不是单调递增的计数器：
    // 删掉 b(x) 再新建一个，它还是叫 b(x) —— 计数器会让名字一路漂到 z(x)、aa(x)，
    // 用户很难认。字母用完（26 个）之后按表格列名的规则进位：z → aa → ab…
    //
    // 注意滑动条不在这里取名：滑动条的名字就是它绑定的参数字母本身（小写 a/b/c/d），
    // 所以列表里 `a` 是参数、`a(x)` 是函数 —— 靠有没有括号区分，
    // 这正是用户要求的判定规则（见 #5：小写字母右边有括号就是函数）。

    /** 小写字母序列：0→a、25→z、26→aa、27→ab…（双射 26 进制，和表格列名一样）。 */
    private fun letters(index: Int): String {
        var n = index.coerceAtLeast(0)
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('a' + (n % 26)))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    /** 函数名：第一个没被占用的 `X(x)`。 */
    fun nextCurveName(): String {
        val taken = functions.map { it.name }.toHashSet()
        var i = 0
        while (true) {
            val candidate = letters(i) + "(x)"
            if (candidate !in taken) return candidate
            i++
        }
    }

    /** 方程/点名：第一个没被占用的单个大写字母序列（A、B…Z、AA…）。 */
    fun nextUpperName(): String {
        val taken = functions.map { it.name }.toHashSet()
        var i = 0
        while (true) {
            val candidate = letters(i).uppercase()
            if (candidate !in taken) return candidate
            i++
        }
    }

    // ---- 滑动条（#3 / #4）----

    /**
     * 表达式里"什么也不代表的小写名字"——也就是需要滑动条的可绑定参数。
     *
     * 1.2.69 起不再是 `a`~`d`：用户要求"输入 s、r1、r2、opt、rv 这样的字母都能创建滑动条"。
     * 词形/引擎名那一层判定在纯函数 [bindableParametersIn]（`PointCoordinates.kt`）里，
     * 这里补上**只有这层状态才知道**的那一条：
     *
     *   * **已被标量对象占着的名字不长滑动条** —— 标量是"量出来的变量、不能调"
     *     （用户那句"滑动条可以调，这个不能调"），同名再长一条滑动条会让
     *     `sin(f)` 里的 `f` 变成拖动值，悄悄改掉用户量出来的结果。
     *
     * 举例：`x(A)` 里 `x` 是采样变量（[bindableParametersIn] 排除）、`A` 是跨不过
     * 词形判定的大写点名 → 整条**一个参数都扫不出来**，不会凭空多出一行。
     */
    fun parametersNeedingSliders(src: String): List<String> {
        val words = bindableParametersIn(src)
        if (words.isEmpty()) return emptyList()
        val scalars = scalarObjectNames()
        return words.filterNot { it in scalars }
    }

    /** 现有**标量对象**（角度/距离/面积）的名字 —— 它们和滑动条是同一类"变量"，但不长滑动条。 */
    private fun scalarObjectNames(): Set<String> =
        objects.filterIsInstance<GraphMeasureObject>().map { it.name }.toSet()

    /**
     * **判"用户到底要滑动条还是要函数"**：整条表达式去掉首尾空白后
     * **恰好就是一个还不存在的可绑定参数名**、**且没有写 `y=` / `f(x)=` 前缀**时返回那个名字，否则 null。
     *
     * 用户需求 #1 的原话（两次补充后的最终版）：
     *   * 第一次："生成滑动条的时候为什么还会同步生成一条函数啊，滑动条和函数不是一个东西，
     *     但是同样是一个对象。"
     *   * 第二次："我发现输入字母 a 的时候会生成一个滑动条 a，同时生成函数 y=a，
     *     这就是生成滑动条同时生成函数的原因" —— 他同意用**有没有写 `y=`** 来区分意图。
     *   * 1.2.69："做成第一次输入不认识的名字就创建滑动条，第二次再输入就是创建函数，
     *     比如第一次输入 q 创建滑动条，在创建完滑动条之后第二次再输入 q 就创建函数 y=q。"
     *
     * 于是判据就是这一条（**这就是用户意图的判据**，不是语法判据）：
     *   | 输入                       | 意图            | 结果                     |
     *   |----------------------------|-----------------|--------------------------|
     *   | `q`（裸名字，无前缀，还没有同名滑动条/标量） | 我要一条滑动条 | **只生成滑动条，丢掉函数** |
     *   | 同上，但**已经有**同名滑动条/标量 | 我要一条函数 | 保留函数（如 `y=q`）+ 复用那条滑动条 |
     *   | `y=q`、`y = q`（有前缀）   | 我要一条函数    | 保留函数 + 滑动条         |
     *   | `2q`/`q*x+b`/`sin(q)`/`q+0`| 我要一条函数    | 保留函数 + 滑动条         |
     * 两种意图都能表达，所以**不需要**再给用户加一个开关或让他选。
     *
     * ### 判"是不是一个参数名"复用扫描那一份规则
     *
     * 调 [bindableParametersIn]：整条文本扫出来的参数**恰好就是它自己**才算
     * （这样 `x`/`t`/`e`/`sin`、`sin(q)`、`2q` 全都自动落选，不用在这里再抄一份过滤）。
     *
     * ### 前缀判定复用引擎那一份规则（**不在 UI 里另写正则**）
     *
     * 用的是 `engine/Callables.kt` 的 [ExplicitSource.strip] —— `Plotter` 采样 `y=f(x)`、
     * `CallableSet` 装函数体读的都是它（`y = …` / `f(x)= …`，大小写不敏感、`=` 两边可有空格）。
     * 这样"这里认不认前缀"和"引擎画的时候认不认前缀"永远是同一件事：
     * 如果哪天引擎的前缀规则放宽（比如再支持一个写法），这里自动跟着走，不会两边漂移。
     *
     * ### 只认"整条表达式"
     *   * `q q`、`q,`、`(q)` —— 长度/字符不对，照旧保留函数；
     *   * 参数方程（`x(t)`/`y(t)` 两栏都有内容）—— "整条表达式"根本不成立，直接不判。
     *
     * 首尾空白先 trim：编辑器里很容易多打一个空格，不该因为一个空格就改变语义。
     */
    fun bareParameterOf(src1: String, src2: String): String? {
        // 两个源都非空 = 参数方程，没有"整条表达式"可言
        if (src2.isNotBlank()) return null
        val text = src1.trim()
        // 剥掉 `y=` / `f(x)=` 之后还是原样 = 用户没写前缀 → 才是"只想要滑动条"的那一路。
        // （剥得动就说明他在写"y = 某个式子"，那是函数，一律保留。）
        if (ExplicitSource.strip(text) != text) return null
        if (bindableParametersIn(text) != listOf(text)) return null
        // 已经有一条同名滑动条（本次之前建的）→ 这次输入是在"用它"，要建函数（用户那句
        // "第二次再输入 q 就创建函数 y=q"）。
        if (sliders.any { it.param == text }) return null
        // 同名标量对象同理：那是"量出来的变量"，用它建函数，不能再长滑动条。
        if (text in scalarObjectNames()) return null
        return text
    }

    /**
     * 保证表达式里用到的参数都有滑动条（没有就补一条，初值 0）。
     *
     * 用户要求："输入小写字母之后确定，自动生成一个范围为 -5 到 5、步长为 0.1 的滑动条。"
     * 已经存在的参数**不动**（不把用户调好的值重置回 0）。
     *
     * 补出来的参数只可能来自 [parametersNeedingSliders] —— 1.2.69 起可以是任意小写名字
     * （`s`、`r1`、`opt`…），但**标量名（`f`、`g`…）永远不会在这里长出一条滑动条**
     * （理由见那个函数）。
     */
    fun ensureSlidersFor(vararg sources: String): Int {
        var added = 0
        for (src in sources) {
            for (param in parametersNeedingSliders(src)) {
                if (sliders.any { it.param == param }) continue
                sliders = sliders + Slider(
                    id = nextId(),
                    param = param,
                    colorIndex = sliders.size % 6
                )
                added++
            }
        }
        return added
    }

    /**
     * 只留下**合法**的滑动条：名字必须是**一个合法的小写标识符**，而且同一个名字只有一条。
     *
     * ## 为什么必须过滤，而不是信任读进来的文件/存档
     *
     * `ParameterSet` 对不认识的名字是**直接 `require` 抛异常**的
     * （见 `engine/Parameters.kt` 的 init），而滑动条会被 `toParameterSet()` 原样送进去 ——
     * 一份手改过的 `.fdf`（比如写了 `L|…|A|…`）足以让采样那条协程每次都抛异常。
     *
     * ## 尺子：`ParameterSet.isParameterName`（1.2.69 起）
     *
     * 以前这里只认单个小写字母，1.2.69 起和引擎的校验用**同一个函数**：
     * 任意小写标识符（`s`、`r1`、`opt`、`rv`…）都收。两处共用一份判据，
     * 以后放宽/收紧只改 `engine/Parameters.kt` 一处。
     *
     * 仍然挡住的：
     *   * 空名 / 大写 / 数字开头（`A`、`2a`）—— 词法上不是参数名；
     *   * 同名两条 —— 两条 `a` 会各自认为自己才是 `a` 的值，表达式到底用哪一个是
     *     看列表顺序，说不清（`toParameterSet()` 是后写的赢）。
     */
    private fun validSliders(list: List<Slider>): List<Slider> {
        val seen = HashSet<String>()
        return list.filter { sl ->
            val p = sl.param
            ParameterSet.isParameterName(p) && seen.add(p)
        }
    }

    /** 换一条滑动条的内容，**不产生撤销点**（拖值 / 播放每帧都会调它，见 [editSlider]）。 */
    fun updateSlider(s: Slider) {
        sliders = sliders.map { if (it.id == s.id) s else it }
    }

    /**
     * 改滑动条的**属性**（属性屏里改名字 / 下界 / 上界 / 步长）。
     *
     * 和 [updateSlider] 分开的理由只有一条：拖值（[setSliderValue]，手指每动一帧调一次）
     * 绝不能压撤销点 —— 那会把 20 步历史在一秒之内冲干净。属性屏的改动才算一步
     * （tag 相同 = 同一个属性上连打字只算一步，机制见 [pushUndo]）。
     */
    fun editSlider(s: Slider) {
        pushUndo("编辑滑动条 ${s.param}", tag = "slider:${s.id}")
        updateSlider(s)
    }

    /**
     * 拖值（滑轨拖动 / 表冠），1.3.10 起**能撤销**。
     *
     * 撤销点用 tag `sliderdrag:<id>` 合并：一次拖动里手指每动一帧都会调到这里，
     * 合并之后"拖一下" = 一步撤销（不会把二十步历史一秒冲干净）。
     * 和 [editSlider] 的属性改动是两个 tag，互不合并。
     */
    fun setSliderValue(id: Long, value: Double) {
        sliders.firstOrNull { it.id == id }?.let { s ->
            val v = s.snap(value)
            if (v == s.value) return
            pushUndo("调整滑动条 ${s.param}", tag = "sliderdrag:$id")
            updateSlider(s.copy(value = v))
        }
    }

    /**
     * 滑动条播放的**会话号**：每按下一次 ▶ 就 +1。
     *
     * 图像界面那段播放循环拿它当 `LaunchedEffect` 的 key ——
     * 见 [toggleSliderPlaying] 里的说明（用户报的"滑动条只能播放一次"）。
     */
    var sliderPlayToken by mutableStateOf(0)
        private set

    /** 播放键：同一时刻只播放一条（再点别的先把上一条停掉）。 */
    fun toggleSliderPlaying(id: Long) {
        val target = sliders.firstOrNull { it.id == id } ?: return
        val willPlay = !target.playing
        // 每按一次 ▶ 就把会话号 +1：图像界面那一段播放循环用它当 key。
        // 不能只靠 `playing` 这个布尔量 —— 同一个 id 在"停 → 再播"时，
        // 循环协程可能还没来得及被取消完就以同一个 `(id, true)` 重启了，
        // 表现就是用户报的"滑动条只能播放一次"（第二次按下什么都没发生）。
        if (willPlay) sliderPlayToken++
        // 第一次按下播放键：弹一次「回到函数图像界面才开始播放」的提示。
        // 判据用 `AppPrefs.sliderPlayHintShown`（装一次只弹一次），而不是
        // 存档里的设置 —— 它是纯 UI 提示，不属于某份函数文件。
        if (willPlay && !AppPrefs.sliderPlayHintShown) sliderPlayHint = true
        sliders = sliders.map {
            when {
                it.id == id -> it.copy(playing = willPlay)
                willPlay -> it.copy(playing = false)
                else -> it
            }
        }
    }

    fun stopAllSliders() {
        if (sliders.any { it.playing }) sliders = sliders.map { it.copy(playing = false) }
    }

    /**
     * 删掉一条滑动条（列表里左滑）—— 和删函数 / 删对象一样留撤销后路。
     *
     * 后路**只有一条**：撤销栈（工具条上那对箭头）。本轮起这里压的是**整份快照**
     * （滑动条 / 函数 / 几何对象 / 绑定表 / 对称规则全在里面），所以撤销之后那条滑动条
     * 回到**原来的位置、原来的取值、原来的名字**，而不是一条被重置成默认 -5~5 的新滑动条。
     * （原来还要另外存一份 `lastDeletedSlider` 给底部那条 2 秒撤销条用 —— 用户本轮
     * 明确要求把那些撤销提示全部删掉，所以那份备份也一起删了。）
     *
     * 被删的参数值不再参与采样（`sliders` 进的是 `GraphScreen` 那条采样 effect 的 key），
     * 所以删掉/撤销都会重采样，曲线跟着变 —— 和删函数、删对象逐项一致。
     */
    fun removeSlider(id: Long) {
        val index = sliders.indexOfFirst { it.id == id }
        if (index < 0) return
        val name = sliders[index].param
        // 撤销点必须在**改之前**压（快照记的是"这条还在"的那个状态）
        pushUndo("删除滑动条 $name")
        sliders = sliders.filterNot { it.id == id }
        // 删掉了什么要说一句（原来这句话是底部那条 2 秒撤销条承担的；撤销条删掉之后
        // 改成一条**纯提示**的 toast：它不可点、也不再出现"点此撤销"这类字样）。
        showToast("已删除滑动条 $name")
    }

    fun clearSliders() {
        sliders = emptyList()
    }

    /**
     * 当前所有滑动条打包成引擎参数（传给 [com.functiondrawer.watch.engine.Plotter.plot]）。
     *
     * 1.2.57 起**任何单个小写字母**都能当滑动条（用户要求："任何小写字母都可以作滑动条"），
     * 所以这里把**全部可见滑动条**都送进去，不再只挑 `a`~`d`。
     *
     * `ParameterSet` 这一侧相应放宽成了"认识任何单个小写字母的显式值"
     * （见 `engine/Parameters.kt`），所以叫 `f` 的滑动条不会再让采样协程抛异常。
     *
     * 仍然只有 `a`~`d` 有"没给值时取 1.0"的默认语义 —— 那是为了保住
     * `a*x+b` 这种老表达式在**没有滑动条**时也能画出来；别的字母没滑动条时
     * 依旧交回引擎报"不认识的名字"（不会凭空变成 1.0 把曲线画错）。
     */
    fun parameters(): ParameterSet = sliders.toParameterSet()

    /**
     * 当前**可以被别的函数调用**的函数表（用户报的 bug：`a(x)=sin(x)`、`b(x)=2a(x)`，
     * 画出来的却是 `y=2x`）。
     *
     * 表里每一项 = 一条函数的名字 + 它的模式 + 它的表达式 + 它自己的度制：
     *
     *  * **可调用名**由引擎从 [PlotFunction.name] 里取 `(` 之前那一截（`a(x)` → `a`）——
     *    这套取名规则只在引擎里写一份（`CallableFn.callableNameOf`），UI 不自己切字符串；
     *  * **度制**按每条曲线自己的设置解析（`fn.degrees ?: 全局默认值`，和
     *    [effectiveAngleUnit] 同一口径），所以 `a` 按 DEG、`b` 按 RAD 时，
     *    `b(x)=2a(x)` 里的 `a(x)` 仍然按 `a` 自己的度制算；
     *  * **模式原样带过去**：极坐标/描点/隐函数也进表，这样用户写 `p(x)` 时
     *    能得到"不是 y=f(x) 的函数，不能被调用"这句明确报错，
     *    而不是含糊的"不认识的名字"。**过滤只有哪些能真的被调用，这件事在引擎里做**
     *    （`CallableFn.isCallable`），UI 不做模式过滤 —— 否则那句报错就没人发了；
     *  * 隐藏的函数**也在表里**：隐藏只是"不画"，不该让别的函数跟着报错；
     *  * 参数表整张传（所有函数共用滑动条的当前值，和四种模式现在的做法一致）。
     *
     * 每次调用都现算：表很小（几条函数），而 [CallableSet] 本身是不可变快照，
     * 采样线程读它不会碰到状态。
     *
     * **传进去的是 [functionsForSampling]（已经替换过取坐标的副本）**，
     * 因为被调用那条函数的**函数体**也是在"算值"：用户写
     * `a(x)=x(A)`、`b(x)=2a(x)` 时，展开 `a` 的那一步同样要去取点 A 的坐标。
     * 传原文的话，`b` 会报"不认识的名字 'A'"——而图上 `a` 自己是画得出来的，
     * 那种"同一张表里一条能用一条不能用"的错最难解释。
     */
    fun callables(): CallableSet =
        CallableSet.ofFunctions(
            functionsForSampling(),
            settings.angleUnit.isDegrees,
            parameters()
        )

    /**
     * 复平面模式下的"数值运算 → 点"（用户需求 #1）。
     *
     * 复平面里列表中的每一条都不是曲线，而是一条数值运算：
     * 把它的表达式用**复数引擎**算出一个复数，然后在复平面上画一个点
     * （横轴实部、纵轴虚部），点旁边直接写结果数值（`2+3i` 这种）——
     * 用户要的就是"数值运算"，所以图上显示**算出来的数**比显示名字有用。
     *
     * 求值参数 a~d 来自滑动条（`ui/Sliders.kt`），经 `parameterValuesOf`
     * 转成"虚部为 0 的复数"。算不出来（语法错、除零以外的病态）就**不画这个点**，
     * 列表里那一行会显示错误说明 —— 画一个写着错误信息的点是最糟的选择。
     *
     * 其它坐标系返回空表：那里列表是曲线，由采样管线负责。
     */
    fun complexResultPoints(): List<GraphPointObject> {
        if (settings.coordinateSystem != CoordinateSystem.COMPLEX) return emptyList()
        val params = parameterValuesOf(parameters())
        // 名字表只算一次：下面每一条数值运算都要用（见 pointCoordinates / scalarValues 的说明）
        val points = pointCoordinates()
        val scalars = scalarValues()
        return functions.filter { it.visible }.mapNotNull { fn ->
            // 1.2.71：复平面里每条"函数"其实是一个**点定义**（`x = 2+i`）。
            // 右边才是复数表达式，左边是点名 —— 名字由它自己给出（用户要求
            // "x 就对应复平面里的一个点，名字为 x"）。老数据/没写等号的原样当表达式。
            val def = parseComplexPointDefinition(fn.src1)
            val expr = def?.expression ?: stripExplicitPrefix(fn.src1)
            val name = def?.name ?: fn.name
            val op = ComplexOp(
                id = fn.id,
                name = name,
                // 复平面下的"数值运算"也是**算值**，所以同样要过一遍取坐标 + 标量替换
                src = substitutePointCoordinates(expr, points, scalars),
                colorIndex = fn.colorIndex
            )
            val result = evaluateComplexOp(op, Complex.ZERO, params)
            val point = complexOpPoint(result) ?: return@mapNotNull null
            GraphPointObject(
                id = fn.id,
                // 图上写"点名 = 结果值"（`x = 2+3i`）：名字是用户定义的，值才是这个点在哪。
                name = complexOpLabel(result)?.let { label ->
                    if (name.isBlank()) label else "$name = $label"
                } ?: name,
                point = point,
                kind = SnapKind.POINT,
                colorIndex = fn.colorIndex
            )
        }
    }

    /** 复平面下某一条列表项的求值结果（列表里显示数值/错误用）。 */
    fun complexResultOf(fn: PlotFunction): ComplexOpResult = evaluateComplexOp(
        ComplexOp(
            id = fn.id,
            name = fn.name,
            // 同 [complexResultPoints]：先剥掉 `x =` 这个点名前缀，剩下的才是复数表达式。
            src = withPointCoordinates(
                parseComplexPointDefinition(fn.src1)?.expression ?: stripExplicitPrefix(fn.src1)
            )
        ),
        Complex.ZERO,
        parameterValuesOf(parameters())
    )

    // ---- 曲线揭示动画：只画新增的那条 ----

    /** 已经播过揭示动画的函数 id 集合。 */
    private var revealedIds: Set<Long>? = null

    /**
     * 返回这一轮需要播放"被画出来"动画的函数 id。
     *
     * 语义（真机反馈明确的）：**只有新加进来的函数才会在里面**。
     *   * 新建一个函数 → 只有它自己从左到右画出来，原有的函数**不重画** ✓
     *   * 编辑已有函数（id 没变）→ 返回空集合，不播 ✓
     *   * 单纯进出图像界面、拖动缩放 → 空集合 ✓
     *
     * 冷启动时 [restore] 已经把存档里的函数记成"已揭示"了（用户反馈 6：
     * 背景要能立刻画出这些曲线，不能停在揭示动画的 0 进度上），
     * 所以那种情况这里返回空集；只有进程里第一次调用、又没经过 [restore] 的
     * 入口才会把所有函数都当成"新的"。
     */
    fun consumeReveal(current: List<PlotFunction>): Set<Long> {
        val ids = current.map { it.id }.toSet()
        val previous = revealedIds
        revealedIds = ids
        if (previous == null) return ids
        return ids - previous
    }

    // ---- 浮出提示（toast）----

    /** 当前要显示的提示文字；null = 不显示。 */
    var toast by mutableStateOf<String?>(null)
        private set

    /**
     * 等这次重算结束后要把哪个函数的报错弹出来。
     *
     * 编辑器里的红字提示已经按用户要求删掉了，报错改由"点确定后弹 toast"承担。
     * 但报错要等采样跑完才知道（`plot.errors`），所以这里先记下函数 id，
     * 等 [applyPlot] 拿到结果再弹。
     */
    var pendingErrorFor by mutableStateOf<Long?>(null)

    fun showToast(message: String) {
        toast = message
    }

    fun dismissToast() {
        toast = null
    }

    // ---- 滑动条播放提示 ----

    /**
     * 「回到函数图像界面才开始播放」这一句提示要不要显示。
     *
     * 只由 [toggleSliderPlaying] 在**第一次**按播放键时置 true；
     * 关掉它（[dismissSliderPlayHint]）会同时落盘 [AppPrefs.setSliderPlayHintShown]，
     * 之后永不再弹。放在这里而不是 `GraphScreen` 的局部 state，是因为
     * 播放键在抽屉里、对话框在抽屉之上，两边都要读同一个值。
     */
    var sliderPlayHint by mutableStateOf(false)
        private set

    fun dismissSliderPlayHint() {
        sliderPlayHint = false
    }

    // ---- 描点工具"怎么拖点"的首次提示 ----

    /**
     * 「长按点 / 选中点之后都能拖动点」这一句提示要不要显示。
     *
     * 用户要求："给初次使用描点工具加个弹窗提示：长按点或者选中点之后都可以拖动点进行移动，
     * 并附上不再提示的勾选框。"
     *
     * 触发点在 [selectTool]（第一次切到 [FunctionTool.PLOT_POINT] 时）；
     * 关掉时由界面按"有没有勾不再提示"决定要不要落盘 [AppPrefs.pointMoveHintShown]。
     */
    var pointMoveHint by mutableStateOf(false)
        private set

    /** 本次运行里已经问过一次 [pointMoveHint]（没勾「不再提示」时不重复弹）。 */
    private var pointMoveHintAsked = false

    fun dismissPointMoveHint() {
        pointMoveHint = false
    }

    // ===================================================================================
    // 快照式撤销 / 重做（本轮新增 —— 工具条上"横着滑一下"唤出的那一对箭头）
    //
    // ## 用户要的是什么
    //
    // 原话："给这个软件加一个撤销的功能，在函数列表界面，从设置滑到加号。按键可以在设置和
    // 加号那一条的地方显示出撤销菜单有左箭头和右箭头。……从加号滑到设置和从删除滑到文件夹，
    // 也可以调出来撤销按钮。注意添加丰富美观的动画。"
    //
    // 界面那一半在 `Common.kt`（`rememberToolbarUndoMenu` + `ToolbarUndoRedoMenu`，
    // 两屏共用）与两屏的调用点；**历史本身在这里** ——
    // 界面只问四件事：[canUndo] / [canRedo] / [undo] / [redo]。
    //
    // ## 模型：压"改动之前的整份内容"，而不是记"改了什么"
    //
    // 记 delta（"删了第 3 行"）在手表这种小状态上不划算：要对每一种改动各写一份逆操作，
    // 少写一种就是"某一类操作撤不回去"的静默 bug。这里反过来 —— 在改动**之前**拍一张
    // 快照（[UndoStep]），撤销 = 把快照整体装回去。好处是**新增一种改动只要加一句
    // `pushUndo`**，撤销/重做的代码一行都不用动。
    //
    // 代价是内存。这一条要说实话：一份 [UndoStep] 里存的是**几个 list 的引用**
    // （函数 / 滑动条 / 几何对象 / 绑定表 / 对称规则），元素本身是共享的不可变数据类，
    // 所以每一步的额外开销只是"几个 list 头 + 几个标量"——
    // 量级是 **几十~几百字节**（`functions` 那份 list 是 `map/filter` 出来的新数组，
    // 100 条函数时约 100 × 8B = 800B）。20 步上限合计在 **几十 KB** 量级，
    // 对手表上这个规模的 App 完全不构成压力。
    // 上限取 [UNDO_STACK_LIMIT] = 20 的真正理由是**可预期**：用户在手表上按左箭头，
    // 心里记的是"刚才那几步"；栈太深（比如 200）只会让"一直按回去"变成一件失控的事。
    //
    // ## 哪些东西进栈、哪些**不进**（刻意的取舍）
    //
    // | | 进栈吗 | 为什么 |
    // |---|---|---|
    // | 函数 / 方程 | ✅ | 用户画出来的东西 |
    // | 滑动条 | ✅ | 同上（含它的下界/上界/步长/名字） |
    // | 几何对象（点/线/度量） | ✅ | 同上 |
    // | 名字绑定表 `functionBindings` | ✅ | 它是"这个字母指谁"，属于用户点的内容 |
    // | 曲线对称规则 `transformedCurves` | ✅ | 不带上它，撤销"清屏"之后对称曲线不会回来 |
    // | **设置**（坐标系 / 网格 / 坐标轴 / 数字 / 示例 / 手势开关 / 缩放栏） | ❌ | 用户明确不希望"撤销把刚设好的坐标系改回去"这种惊吓；而且切坐标系会清空函数，撤销回去会出现"函数和坐标系对不上"这一对不兼容的状态。设置页本来就有自己的草稿 + 确认框机制 |
    // | **视野**（缩放 / 平移） | ❌ | 它每帧都在变（手势、表冠），进栈会把历史冲干净；而"撤销一次图跳回上一处"不是用户按撤销时想要的东西 |
    // | **选中项 `selectedId`** | ⚠️ 随快照一起还回去 | 它决定列表里哪一条高亮、"编辑哪一条"，跟着内容回到当时的样子最自然；[applyUndoStep] 里还会校验它仍然指向存在的函数 |
    //
    // ## 撤销只有这一条路（本轮起：旧的「2 秒撤销条」整条删掉了）
    //
    // 用户本轮原话："这样子就要把所有的撤销的 toast 提示都给删掉。"
    // 于是原来那套 `lastDeleted` / `lastDeletedObject` / `lastDeletedSlider` /
    // `clearedBackup` / `clearedObjectsBackup`（以及 `GraphScreen` 里那个 2 秒到期 effect、
    // 第一屏底部那条「已删除 · 点此撤销」胶囊）**全部删除**：
    //   * 删除动作（函数 / 滑动条 / 几何对象 / 清屏 / 清空全部）现在只做两件事 ——
    //     在改动**之前** `pushUndo(...)` 记一步（这样撤销菜单能把它撤回来）、
    //     再弹一条**纯提示**的 toast（"已删除 a(x)"，不可点、不含任何"点此撤销"字样）；
    //   * 界面侧只有一个入口：[canUndo] / [canRedo] / [undo] / [redo]
    //     （工具条上那对箭头键）。
    // 好处不只是"少一套 UI"：**只留一个历史源**，"两套各撤各的"这类打架从结构上消失了
    // （原来还要靠 `undoStepId` 对齐、靠"撤一次就清空旧备份"防重复撤销）。
    //
    // ## 进栈 / 不进栈（刻意的取舍，一个字没变）
    //
    // | | 进栈吗 | 为什么 |
    // |---|---|---|
    // | 函数 / 方程 | ✅ | 用户画出来的东西 |
    // | 滑动条 | ✅ | 同上（含它的下界/上界/步长/名字） |
    // | 几何对象（点/线/度量） | ✅ | 同上 |
    // | 名字绑定表 `functionBindings` | ✅ | 它是"这个字母指谁"，属于用户点的内容 |
    // | 曲线对称规则 `transformedCurves` | ✅ | 不带上它，撤销"清屏"之后对称曲线不会回来 |
    // | **设置**（坐标系 / 网格 / 坐标轴 / 数字 / 示例 / 手势开关 / 缩放栏） | ❌ | 用户明确不希望"撤销把刚设好的坐标系改回去"这种惊吓；而且切坐标系会清空函数，撤销回去会出现"函数和坐标系对不上"这一对不兼容的状态。设置页本来就有自己的草稿 + 确认框机制 |
    // | **视野**（缩放 / 平移） | ❌ | 它每帧都在变（手势、表冠），进栈会把历史冲干净；而"撤销一次图跳回上一处"不是用户按撤销时想要的东西 |
    // | **选中项 `selectedId`** | ⚠️ 随快照一起还回去 | 它决定列表里哪一条高亮、"编辑哪一条"，跟着内容回到当时的样子最自然；[applyUndoStep] 里还会校验它仍然指向存在的函数 |
    // ===================================================================================

    /**
     * 一步"可以回去的状态"。
     *
     * `private` 是刻意的：快照的形状只该由状态层自己决定（外面拿不到、也就不会有人
     * 试图在外面拼一个"半个快照"塞进来）。
     */
    private class UndoStep(
        /** 这一步是**什么改动**（toast 文案用，例如"删除 a(x)"）。 */
        val label: String,
        /** 单调递增的步骤号：用来"作废某一步及其之后压进来的全部步骤"（见 [dropUndoStepsFrom]）。 */
        val stepId: Long,
        val functions: List<PlotFunction>,
        val sliders: List<Slider>,
        val objects: List<GraphObject>,
        val selectedId: Long,
        val functionBindings: Map<Long, Map<String, Long>>,
        val transformedCurves: List<TransformedCurve>,
        /**
         * 曲线构造关系（**函数 id** → 记账，1.3.10 加进来）。
         *
         * 之前不在快照里：撤销"删除圆"再重做时，函数回来了、`curveRecipes` 那一行没了，
         * 圆于是退化成死快照，拖圆心不再跟着动。和 [objects] 里对象自己的 recipe
         * 是同一件事的两个位置，两边都得回滚才是完整的对称。
         */
        val curveRecipes: Map<Long, CurveRecipeEntry>
    )

    private val undoStack = ArrayDeque<UndoStep>()
    private val redoStack = ArrayDeque<UndoStep>()
    private var undoStepSeq = 0L

    /**
     * 上一串"同一类连续改动"的标记（[pushUndo] 的 `tag`）。
     *
     * `null` = 下一步一定要压。任何 **tag 不同**的改动、以及任何一次撤/重做
     * （还有 [endUndoGroup]）都会把它清掉 —— 于是"同一类"只可能在**紧挨着的、
     * 没有任何别的动作插进来**的一串调用里成立，这正是"在编辑器里连打十个字算一步"。
     */
    private var lastUndoTag: String? = null

    /**
     * "新建函数"那一步的步骤号（草稿被丢弃时要连同它之后压的步骤一起作废）。
     *
     * 为什么需要它：`+` 是"先建一条空函数再进编辑器"的，用户在编辑器里打的每个字
     * 都会压一步（同一个 tag 只压第一步）。如果最后什么都没输入、这条草稿被丢掉
     * （[discardFunction]），那几步记的都是"这条草稿还不存在"的世界 ——
     * 留着它们，用户按一次撤销就会把**已经被丢掉的空函数**变回来。
     */
    private var draftUndoStepId: Long? = null

    /** `addFunction` 最近压的那一步（`markJustCreated` 会把它认成"草稿的那一步"）。 */
    private var lastAddUndoStepId: Long? = null

    /** 还能撤销吗（工具条上左箭头亮不亮）。 */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** 还能重做吗（右箭头亮不亮）。 */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 当前状态的快照。 */
    private fun snapshot(label: String, stepId: Long): UndoStep = UndoStep(
        label = label,
        stepId = stepId,
        functions = functions,
        sliders = sliders,
        objects = objects,
        selectedId = selectedId,
        functionBindings = functionBindings,
        transformedCurves = transformedCurves.toList(),
        curveRecipes = curveRecipes.toMap()
    )

    /**
     * 在**一次有意义的改动之前**拍一张快照。
     *
     * @param label toast 文案（"删除 a(x)"这种，用户看得懂的词）。
     * @param tag 非空 = "同一类连续改动"的标记（例如 `edit:5` = 第 5 条函数的编辑）。
     *   栈顶那一步的 tag 与它相同就**不再压** —— 那一步记的本来就是"这一串改动之前"
     *   的状态，于是"连打十个字"只算一步撤销。
     * @return 这一步的步骤号（草稿作废时要用，见 [draftUndoStepId]）。
     */
    private fun pushUndo(label: String, tag: String? = null): Long {
        val top = undoStack.lastOrNull()
        if (tag != null && top != null && tag == lastUndoTag) return top.stepId
        val id = ++undoStepSeq
        undoStack.addLast(snapshot(label, id))
        // 上限：超出就丢**最老**的那一步（用户按左箭头是按回"刚才"，不是按回"刚装好"）
        while (undoStack.size > UNDO_STACK_LIMIT) undoStack.removeFirst()
        // 新改动 → 重做栈作废（这是"重做"语义的全部）
        redoStack.clear()
        lastUndoTag = tag
        return id
    }

    /**
     * 丢掉 [stepId] 及其之后压进来的每一步 —— "这一串改动整个作废了"。
     *
     * 唯一的调用点是 [discardFunction]（见 [draftUndoStepId] 的说明）。
     */
    private fun dropUndoStepsFrom(stepId: Long) {
        while (undoStack.isNotEmpty() && undoStack.last().stepId >= stepId) undoStack.removeLast()
        redoStack.clear()
        lastUndoTag = null
        if ((draftUndoStepId ?: -1L) >= stepId) draftUndoStepId = null
    }

    /**
     * 一串"连续改动"到此为止：下一个同类改动算**新的一步**。
     *
     * 由界面在**路由变化**时调一次（`GraphScreen` 里那个 `LaunchedEffect(state.route)`）。
     * 不调会怎样：进编辑器改一下、退出来、再进去改一下 —— 两次编辑会被算成同一步撤销
     * （因为 tag 都是 `edit:<同一个 id>`），撤销一次把两回的改动一起撤掉。
     */
    fun endUndoGroup() {
        lastUndoTag = null
    }

    /**
     * 撤销一步。
     *
     * @return 撤掉的是哪一步（界面拿它拼 toast「已撤销：删除 a(x)」）；
     *   **栈空时返回 null**（界面据此弹「没有可撤销的操作」）。
     */
    fun undo(): String? {
        val step = undoStack.removeLastOrNull() ?: return null
        // 当前状态进重做栈：它记的是"这一步之后的样子"，重做就是把这一步再做一遍。
        // ⚠️ 这里**不做** `redoStack.clear()` —— 那会让重做永远不可能。
        redoStack.addLast(snapshot(step.label, step.stepId))
        while (redoStack.size > UNDO_STACK_LIMIT) redoStack.removeFirst()
        applyUndoStep(step)
        lastUndoTag = null
        draftUndoStepId = null
        return step.label
    }

    /** 重做一步（[undo] 的逆）。@return 重做的是哪一步；栈空返回 null。 */
    fun redo(): String? {
        val step = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(snapshot(step.label, step.stepId))
        while (undoStack.size > UNDO_STACK_LIMIT) undoStack.removeFirst()
        applyUndoStep(step)
        lastUndoTag = null
        draftUndoStepId = null
        return step.label
    }

    /** 把一份快照整体装回去（撤销与重做**共用**这一段，所以两者永远是对称的）。 */
    private fun applyUndoStep(step: UndoStep) {
        functions = step.functions
        // 播放状态**不跟着快照回来**（和 `Store` 那条"播放状态不落库：重启之后不该自己
        // 动起来"是同一条约定）：撤销一步之后某条滑动条自己跑起来会非常莫名其妙。
        sliders = if (step.sliders.any { it.playing }) {
            step.sliders.map { it.copy(playing = false) }
        } else {
            step.sliders
        }
        objects = step.objects
        selectedId = if (step.functions.any { it.id == step.selectedId }) {
            step.selectedId
        } else {
            step.functions.firstOrNull()?.id ?: -1L
        }
        functionBindings = step.functionBindings
        // 对称曲线的**规则**也一起回去（不回去的话，"撤销清屏"之后那些对称曲线不会重画）
        transformedCurves.clear()
        transformedCurves += step.transformedCurves
        // 曲线构造关系跟着回滚（见 [UndoStep.curveRecipes]）
        curveRecipes.clear()
        curveRecipes.putAll(step.curveRecipes)
        // ⚠️ `idSeq` **绝不回退**：函数 / 滑动条 / 几何对象的 id 是全局唯一、永不复用的，
        // 回退会让"撤销之后新建的东西"拿到一个出现过的 id（列表 key 撞车、
        // 名字绑定表串到别人身上）。所以撤销只换内容，号段继续往前走。
        //
        // 顺手收掉"点中的卡片"：它可能正指着刚刚被撤掉的那个对象
        // （不收就会出现一张浮在图上、指向不存在对象的卡片）。
        clearTappedCards()
        // （原来这里还要把五路"2 秒撤销条"的备份清干净 —— 那一条本轮整套删掉了，
        //   撤销现在**只有这一个历史源**，"两套各撤各的"从结构上不可能发生。）
        // 采样 / 列表都不用手动刷新：它们读的就是这几个状态。
        // `GraphScreen` 那条采样 effect 的 key 里有 functions / sliders / objects，
        // 状态一变它自己会重算 —— 和删函数、打开文件走的是同一条管线。
    }

    // ---- 改颜色 ----

    fun setColor(id: Long, colorIndex: Int) {
        functions.firstOrNull { it.id == id }?.let { fn ->
            pushUndo("${fn.title()} 换颜色", tag = "color:$id")
            updateFunctionNoUndo(fn.copy(colorIndex = colorIndex))
        }
    }

    /**
     * 改一条函数。编辑器里**每敲一个字**都会调它（边打边看），所以：
     * 撤销点在这里压，但**同一串编辑只压第一步**（tag = `edit:<id>`，见 [pushUndo]）。
     */
    fun updateFunction(fn: PlotFunction) {
        pushUndo("编辑 ${fn.title()}", tag = "edit:${fn.id}")
        updateFunctionNoUndo(fn)
    }

    /** 换一条函数的**内容**，不产生撤销点（给已经自己压过一步的调用方用）。 */
    private fun updateFunctionNoUndo(fn: PlotFunction) {
        functions = functions.map { if (it.id == fn.id) fn else it }
    }

    /**
     * 切一条函数的显隐（列表里点色点）。
     *
     * 单独开一个方法而不是让界面直接 `updateFunction(fn.copy(visible = …))`：
     * 那样压出来的撤销点 tag 是 `edit:<id>`，会和"正在编辑器里打字"那一串合并；
     * 而"点了色点"和"改了表达式"在用户心里是两件事。
     */
    fun setVisible(id: Long, visible: Boolean) {
        functions.firstOrNull { it.id == id }?.let { fn ->
            pushUndo("${fn.title()} 改显隐", tag = "visible:$id")
            updateFunctionNoUndo(fn.copy(visible = visible))
        }
    }

    // ---- 角度单位（**每个函数各一份**）----

    /**
     * 这个函数实际生效的角度单位。
     *
     * [PlotFunction.degrees] 为 null 表示"没单独设过"，跟随设置里的默认值；
     * 一旦用户在编辑器里按过那个 RAD/DEG 键，它就固定用自己的，不再跟默认走。
     */
    fun effectiveAngleUnit(fn: PlotFunction): AngleUnit =
        if (fn.degrees ?: settings.angleUnit.isDegrees) AngleUnit.DEG else AngleUnit.RAD

    /**
     * 切换某个函数自己的角度单位。
     *
     * 关键的一点是**量程要跟着换算**：极坐标 `r=1-sin(θ)` 的量程本来是
     * 0~2π（弧度），切成 DEG 之后如果还留 0~2π，引擎会把 θ 当成 0~6.28 **度**
     * 来解释 —— 画出来只有一小段弧，看起来就像"切了度制图就坏了"。
     * 所以 2π → 360 一起转过去，用户看到的图形基本不变，只是单位换了。
     *
     * 只对**量程参与采样**的模式（极坐标 / 参数方程）换算：
     * 显函数的 x 范围来自视口而不是 tMin/tMax，换算它反而会把抛物线之类顶出屏幕。
     */
    fun toggleAngleUnit(id: Long) {
        val fn = functions.firstOrNull { it.id == id } ?: return
        val toDegrees = !(fn.degrees ?: settings.angleUnit.isDegrees)
        val converted = if (fn.mode.usesRange) {
            fn.copy(
                tMin = if (toDegrees) Math.toDegrees(fn.tMin) else Math.toRadians(fn.tMin),
                tMax = if (toDegrees) Math.toDegrees(fn.tMax) else Math.toRadians(fn.tMax)
            )
        } else {
            fn
        }
        updateFunction(converted.copy(degrees = toDegrees))
    }

    // ---- 删除（撤销统一走工具条上那对箭头，见文件上方"快照式撤销 / 重做"那一节）----

    /**
     * 删掉一个函数（列表里左滑）。
     *
     * 手势删除是**没有二次确认**的（手表上弹确认框比误删还烦），所以必须留后路 ——
     * 后路现在只有一条：**撤销栈**（`pushUndo` 在改动之前压的那一步，用左箭头撤回来）。
     * 原来那份 `lastDeleted` 备份 + 底部 2 秒撤销条在本轮被用户要求删掉了。
     */
    fun removeFunction(id: Long) {
        val index = functions.indexOfFirst { it.id == id }
        if (index < 0) return
        val name = functions[index].title()
        // 撤销点必须在**改之前**压（快照记的是"这条还在"的那个状态）
        pushUndo("删除 $name")
        functions = functions.filterNot { it.id == id }
        // 名字绑定表跟着函数一起走：这条函数没了，它那份"字母指谁"就没有意义了。
        // （id 是全局递增、永不复用，所以留着也不会串到别的函数上；清掉只是不让它变成垃圾。
        //  撤销时这张表会**跟着快照一起回来** —— 本轮起它进了快照。）
        if (functionBindings.containsKey(id)) functionBindings = functionBindings - id
        if (selectedId == id) {
            selectedId = functions.firstOrNull()?.id ?: -1L
        }
        // 删掉了什么要说一句（原来是底部那条 2 秒撤销条承担的；现在是一条**纯提示**的
        // toast：不可点、不含"点此撤销"这类字样）。
        showToast("已删除 $name")
    }

    fun errorOf(id: Long): String? = plot.errors[id]

    // ===================================================================================
    // 「功能界面」的几何对象（用户需求 #9）
    //
    // 这一整块只做三件事：
    //   1. 存/改**几何对象**（点、线、度量）—— 曲线（函数/方程）仍然在 [functions] 里，
    //      两套东西**分开存**：清屏按钮只清几何对象、长按 + 才清函数（用户明确要求）。
    //   2. 把"手指点屏幕"翻译成一次工具选择（吸附在这里做，因为吸附是**屏幕像素**语义，
    //      需要视口；而 [FunctionTools] 里那套状态机是纯世界坐标、零 Compose 依赖）。
    //   3. 工具选够了就把结果算出来、建成 [GraphObject]、加进 [objects]（于是自动
    //      出现在函数列表里，能改颜色、能删、能量数值显隐）。
    //
    // **所有曲线相关的引擎调用都必须传这条曲线自己的采样视口**（`plot.viewport`），
    // 否则换算出来的世界坐标是错的（`Curve.points` 存的是那次采样的像素坐标）。
    // ===================================================================================

    // ---- 1. 对象与工具状态 ----

    /**
     * 这次用工具画出来的东西（点/线/度量）。渲染在 `functions` 之上，清屏按钮清的就是它。
     *
     * 它是**几何对象**那一套，和 [sliders]（可调变量）刻意分开：
     *   * 对象行（`GraphObjectRow`）只能看/显隐/删除 —— 里面的度量值**不能拖**；
     *   * 滑动条行（`SliderRow`）才能拖。
     * 用户对度量的定性是"和滑动条本质上都是变量，但是滑动条可以调，这个不能调"，
     * 所以度量对象只进这里，并且能直接写进表达式当数值用（见 [scalarValues]）。
     */
    var objects by mutableStateOf(listOf<GraphObject>())
        private set

    /** 当前选中的工具（「移动」是默认，什么都没改时拖动就是平移图像）。 */
    var activeTool by mutableStateOf(FunctionTool.MOVE)
        private set

    /**
     * 当前工具的会话状态机（`null` = 已经回到「移动」）。
     *
     * 它不进 Compose 快照：界面只用到 [toolPicked] / [toolHint] / [toolExpected]
     * 这几个**派生**值，所以每次状态推进就把 [toolFlowVersion] 加一（那几个派生值
     * 都读它一下），比给整个类包一层 Observable 便宜。
     *
     * **工具做完一个对象不会把它置空**：用户要求"可以选择多个角度之后手动切回
     * 移动才移动"，所以 [finishTool] 只是把它 [ToolFlow.reset] 掉，工具本身留着。
     */
    private var toolFlow: ToolFlow? = null
    private var toolFlowVersion by mutableStateOf(0)

    /** 工具已经吃进去的选择（点 = 世界坐标点；对象 = 那是哪条曲线 / 哪条线）。 */
    var toolPicked by mutableStateOf<List<ToolSelection>>(emptyList())
        private set

    /** 手指正在拖、还没定住的临时描点（松手才真正加进 [objects]）。 */
    var pendingPoint by mutableStateOf<GPoint?>(null)
        private set

    /** 描点模式下，当前这一笔吸到了什么（界面上给个"交点/零点"的提示用）。 */
    var pendingKind by mutableStateOf<SnapKind?>(null)
        private set

    /**
     * 「圆（半径·圆心）」已经点好的圆心（`null` = 半径输入面板没开）。
     *
     * 用户要求点完圆心**直接弹键盘输入半径**（"你直接调用函数创建界面的键盘吧"），
     * 所以这里只存"圆心"这一件事；输入框里的文本存在 [radiusInputText]（同样是状态层），
     * 按下 ✓ 时由 [confirmRadiusInput] 校验表达式并建圆。
     */
    var radiusInputCenter by mutableStateOf<GPoint?>(null)
        private set

    /**
     * 半径输入框里的**文本**（`Route.Radius` 这一屏的唯一真源）。
     *
     * 1.3.2 修 bug：原来文本住在 `RadiusEditorScreen` 的 `remember` 里，
     * 每按一个键子树重组、那次 `remember` 被丢掉，下一次按键就在**空串**上插入 —
     * 表现就是"只能输入一位数"（点 5 再点 6 只剩 6，点 0 再点 . 只剩 .）。
     * 放到状态层之后，文本跨重组稳定存续，和函数编辑器
     * （`onChange → updateFunction`）的存法一致。打开 / 确认 / 取消时清空。
     */
    var radiusInputText by mutableStateOf("")
        private set

    /** 半径输入框每次改动：文本写回状态层（见 [radiusInputText]）。 */
    fun updateRadiusInputText(value: String) {
        radiusInputText = value
    }

    /**
     * 面板里那个圆心对应的**点对象 id**（`null` = 圆心不是图上的对象）。
     *
     * 存它的唯一目的是让建出来的圆**跟着圆心点动**：确认半径时把
     * `ObjectRecipe.CircleCenterRadiusExpr(centerId, 半径表达式)` 记进 `curveRecipes`，
     * 之后拖动圆心点就会按新坐标重算圆的方程（见 `rebuildCurveEquations`）。
     * 用户报的 bug 正是"半径·圆心画出的圆在拖动圆心时不会跟着动"。
     */
    private var radiusInputCenterId by mutableStateOf<Long?>(null)

    /**
     * 上一次**常驻屏**是哪一屏 —— 图像界面底部上滑就回那里。
     *
     * 用户要求："从第二屏滑到函数图像界面之后，从函数图像界面底部上滑应该回到第二屏。"
     *
     * ⚠️ **只有"常驻的两屏"会被记进来**（[Route.List] / [Route.Tools]）：
     * 临时屏（编辑器 / 属性编辑 / 设置 / 文件列表）和图像界面自己一律不记 ——
     * 见 [rememberScreen] 里那张表。
     *
     * **为什么不用"各处手动置一个布尔标志"**：早先的实现是 `lastToolsScreen`，
     * 由 `openTools()` / `backToGraph()` / `selectTool()` 各自置位、由别的路径清掉 ——
     * 只要有一条进入图像界面的路径忘了置位（真机上就是这种情况），上滑就回了第一屏，
     * 而且从代码上很难看出漏了哪条路。
     * 现在改成**界面观察路由**：路由一变就交给 [rememberScreen] 判一次（见
     * `GraphScreen` 里那个 `LaunchedEffect(state.route)`），记录点只有一处、
     * 和"用户实际在哪一屏"永远一致。
     */
    var lastNonGraphRoute: Route = Route.List
        private set

    /**
     * 界面在"路由变成另一屏"时调用（**唯一记录点**）。
     *
     * ## 规则：上滑回去的目标必须是**真实存在的那一屏**，临时屏不参与记忆
     *
     * 这里的"真实存在"指的是**用户随时能回去的常驻屏**，只有两屏：
     *  * [Route.List]（函数列表，应用的根）；
     *  * [Route.Tools]（功能界面，从列表左边缘滑进去的那一屏）。
     *
     * 其余**全是临时屏**（进去办一件事就出来），一律不记：
     *
     * | 屏 | 为什么不算"上一屏" |
     * |---|---|
     * | [Route.Editor] | 编辑器是"进去改一条式子然后出来"的表单，按 ✓ 之后它**已经用完了**。用户报的 bug 就是这个："新建函数保存之后会跳转到函数图像界面，但是函数图像界面上滑会回到函数编辑界面而不是函数列表界面。" —— 因为按 ✓ 走的是 `Route.Editor → Route.Graph`，而当时记下来的"上一屏"正是编辑器 |
     * | [Route.Properties] / [Route.SliderProperties] | 同一个道理：属性编辑也是表单（它们自己的 ✓ / 返回落点是**列表**）。记下来现在不会立刻出问题，但只要将来有一条路从它们进图像界面，就会立刻变成同一个 bug |
     * | [Route.Details] | 本轮新增的**详情页**（短按列表行进去只读地看一眼）：和属性屏同类，✓ / 返回键的落点同样是列表 —— 记成来路就会出现"看完详情 → 回列表 → 进图像界面 → 上滑又掉回详情页" |
     * | [Route.Settings] | 设置是"从列表进去改一下"的叶子屏（返回也落列表）。把它记成来路会出现"进过一次设置 → 回列表 → 进图像界面 → 上滑"掉进设置 |
     * | [Route.Saves] | 文件列表同理，而且它是从功能界面点进去的叶子屏：用户心里的"第二屏"一直是功能界面（这条从上一版起就是这么定的） |
     * | [Route.Graph] | 图像界面自己当然不算"上一屏" |
 *
 * 本轮新增的应用信息页（[Route.About]）也在这张表的"临时屏"那一支里，
 * 理由和 [Route.Settings] 逐字相同：它是"长按 ⚙ 进去看一眼"的叶子屏，
 * 返回键的落点是**列表** —— 记成来路就会出现"看过一次应用信息 → 回列表 →
 * 进图像界面 → 上滑"又掉回应用信息页。
     *
     * ## 为什么写成"白名单 + 穷举 `when`"
     *
     * 这是个**表达式**的 `when`，所以将来谁加一个新的 `Route`，编译器会直接报
     * "when 不穷尽"，逼他当场判一次"这是常驻屏还是临时屏"。
     * 这类 bug（把临时屏记成来路）就再也没有生存空间了 ——
     * 上一版是"黑名单"（`!= Graph && != Saves`），每加一屏都要靠人记得回来改，
     * 编辑器那一屏就是漏掉的那一个。
     */
    fun rememberScreen(route: Route) {
        lastNonGraphRoute = when (route) {
            // ---- 常驻的两屏：唯一有资格当"上滑回去"的目标 ----
            Route.List, Route.Tools -> route

            // ---- 临时屏 + 图像界面自己：保持原样（不记） ----
            is Route.Editor,
            // 半径输入屏（1.3.2）也是临时屏：从图像界面弹出、完成/取消都回图像界面，
            // 记成来路会让"再进图像界面后上滑"掉进半径输入。
            Route.Radius,
            // 截图屏（1.3.14）同理：一次性屏，✗ / 返回 / 保存完都回图像界面。
            Route.Capture,
            is Route.Properties,
            // 几何对象的**详情页**（本轮新增）同属临时屏：和 `Properties` 完全同一个道理 ——
            // 它也是"短按一行进去看一眼然后回列表"的一次性屏（✓ / 返回键都落列表），
            // 记成来路会出现"看过一次详情 → 回列表 → 进图像界面 → 上滑"掉进详情页。
            is Route.Details,
            is Route.SliderProperties,
            Route.Settings,
            Route.Saves,
            // 应用信息页（本轮新增）同属临时屏：和 `Settings` 逐字同一个道理 ——
            // 它是"长按列表那颗 ⚙ 进去看一眼"的叶子屏（返回键落**列表**），
            // 记成来路会出现"看过一次应用信息 → 回列表 → 进图像界面 → 上滑"掉回它。
            Route.About,
            // 更多菜单与它的四个子页（本轮新增）同样都是临时屏：进出都不记来路，
            // 和 `Settings` 逐字同一个道理（"看过一次 → 回列表 → 上滑"不该掉回去）。
            Route.MoreSettings,
            Route.CrownSettings,
            Route.CalcSettings,
            Route.TouchSettings,
            Route.ThemeSettings,
            // 开发者模式子页同样不记来路（和上面几屏一个道理）。
            Route.Developer,
            Route.Graph -> lastNonGraphRoute
        }
    }

    /** 进入功能界面。 */
    fun openTools() {
        route = Route.Tools
    }

    /** 打开截图界面（图像界面底部提示条长按 2 秒）。 */
    fun openCapture() {
        route = Route.Capture
    }

    /** 关掉截图界面（✗ / 返回键 / 保存完成），回图像界面。 */
    fun closeCapture() {
        if (route == Route.Capture) route = Route.Graph
    }

    /** 从功能界面回图像界面（返回键 / 点了一下工具就自动回）。 */
    fun backToGraph() {
        route = Route.Graph
    }

    /**
     * 从图像界面往上滑该去哪一屏：**上一次不是图像界面的那一屏**。
     *
     * 不再清空记录：记录由界面观察路由自动维护，清空反而会让"上滑去功能界面、
     * 再进图像界面、再上滑"这种来回切换变得不可预期（上一版就是这么坏的）。
     */
    fun leaveGraphBySwipeUp(): Route = lastNonGraphRoute

    /**
     * 兼容旧调用点：显式要求"下一次上滑回函数列表"。
     *
     * 实际上现在用不到 —— 进入函数列表时上面的观察 effect 已经把它记成 `Route.List` 了。
     * 保留是为了不动那几个调用点的语义（它们表达的是"这是一次明确的离开图像界面"）。
     */
    fun leaveToolsMemory() {
        lastNonGraphRoute = Route.List
    }

    // ===================================================================================
    // 函数文件（用户需求：长按 + 存的是"所有函数信息"的文件；第二屏左上角文件夹按钮浏览）
    //
    // 这里只做**状态 ⇄ 文件模型**的转换，磁盘读写全在 `FunctionFileStore`、
    // 文本格式全在 `FunctionFileFormat`（那个是纯 Kotlin，能被离线脚本单测）。
    // 两边都刻意不放在这个文件里：GraphState 带 Compose 依赖，一进来就没法离线测了。
    // ===================================================================================

    /** 进入函数文件列表（第二屏「功能界面」左上角的文件夹按钮调它）。 */
    fun openSaves() {
        route = Route.Saves
    }

    /**
     * 把当前状态打包成函数文件的模型（长按 `+` → 命名对话框 → 确定时调它）。
     *
     * 覆盖的是**整个应用状态**：函数（全部字段）、滑动条、几何对象、设置、视口、当前选中。
     * 两处有意的取舍（文件格式那边也写了）：
     *   * `Slider.playing` 不进文件（瞬时动画状态，和 `Store.kt` 一样不落库）；
     *   * 变换出来的**曲线折线碎片**不进文件（它们是派生物，一条曲线能拆上百段），
     *     改存"哪条曲线按什么轴对称/中心对称"这条**规则**（[transformedCurves]），
     *     打开时按新视口重新生成 —— 又小又不会在缩放后变成死掉的折线。
     */
    fun snapshotForFile(): FunctionFileFormat.FileState = FunctionFileFormat.FileState(
        // ⚠️ **只写"有表达式"的函数**（用户报的 bug："保存文件的时候会在文件里添加一个
        // 没有表达式的函数"）。判据是共用的 [hasNoExpression]（和编辑器的空输入判定
        // 一字不差，见那边的说明：两处各写一份迟早会出现"编辑器认为它是空的、
        // 存档却把它写进去"）。
        //
        // 这是**兜底**：正常路径上 `+` 建出来的空函数在"取消 / 空输入确定"时就被丢掉了
        // （[discardFunction]），离开编辑器时还有 [discardBlankDraft] 再扫一遍；
        // 但只要有一条路径漏了，这里就必须挡住 —— 文件是用户长期保存的东西，
        // 里面出现一条谁都看不懂的空行，他很难删干净。
        //
        // 过滤**只作用在这一份快照上**：内存里的 [functions] 一个元素都不动，
        // 用户正在编辑的那条仍然留在界面上（"取消"还是"确定"由他自己决定）。
        // 另存/覆盖走的都是这同一个入口（见 `GraphScreen` 里保存对话框的 `encode`）。
        functions = functionsToSave(functions),
        sliders = sliders.map { s ->
            FunctionFileFormat.FileSlider(
                id = s.id,
                param = s.param,
                min = s.min,
                max = s.max,
                step = s.step,
                value = s.value,
                colorIndex = s.colorIndex,
                visible = s.visible
            )
        },
        objects = objects.filter { it.appearsInList }.map { it.toFileObject() },
        // 施工说明：**对象形态**（点 / 线 / 度量）和**函数形态**（圆 / 圆锥曲线）各一张表。
        // 不存这一份的话，重开软件后派生对象全部退化成死快照（用户实测的 bug）。
        objectRecipes = objects.filter { it.appearsInList }
            .mapNotNull { o -> o.recipeOrNull?.let { r -> o.id to r } }
            .toMap(),
        // 只写"还在的函数"：用户可能已经把那条曲线删了，悬空的 recipe 没有意义。
        functionRecipes = curveRecipes
            .filterKeys { id -> functions.any { it.id == id } }
            .mapValues { it.value.recipe },
        transforms = transformedCurves.map {
            FunctionFileFormat.FileTransform(
                functionId = it.functionId,
                axis = it.axis,
                center = it.center
            )
        },
        settings = FunctionFileFormat.FileSettings(
            coord = when (settings.coordinateSystem) {
                CoordinateSystem.CARTESIAN -> FunctionFileFormat.FileCoord.CARTESIAN
                CoordinateSystem.POLAR -> FunctionFileFormat.FileCoord.POLAR
                CoordinateSystem.COMPLEX -> FunctionFileFormat.FileCoord.COMPLEX
            },
            angle = if (settings.angleUnit == AngleUnit.DEG) {
                FunctionFileFormat.FileAngle.DEG
            } else {
                FunctionFileFormat.FileAngle.RAD
            },
            showGrid = settings.showGrid,
            showAxes = settings.showAxes,
            showNumbers = settings.showNumbers,
            showPresets = settings.showPresets,
            confirmCoordSwitch = settings.confirmCoordSwitch
        ),
        viewport = FunctionFileFormat.FileViewport(
            centerX = viewport.centerX,
            centerY = viewport.centerY,
            unitsPerPixel = viewport.unitsPerPixel
        ),
        selectedId = selectedId,
        // 名字绑定表（用户要求："给函数编辑界面输入的这些字母单独分组编 id"）：
        // 直接照搬内存里那张表（`Map<函数 id, Map<字母, 对象 id>>`）。
        // **空表时 `FunctionFileFormat.encode` 一行都不写**，所以没指认过字母的文件
        // 与本轮之前逐字节相同；老文件读进来也是空表（见 loadFileState）。
        // 绑定指向的滑动条/对象 id 都在同一个文件里存着（`L`/`O` 行），所以能对上。
        bindings = functionBindings
    )

    /**
     * 把一份函数文件装回应用状态（打开文件时调它）。
     *
     * 三件容易漏、但漏了就会被用户发现的事：
     *  1. **[idSeq] 要顶上去**：函数/滑动条/几何对象共用一个 id 序列，
     *     不顶的话下一次新建会拿到撞车的 id（`LazyColumn` 的 key 撞了会直接崩）；
     *  2. **不播"新函数被画出来"的揭示动画**：这些函数不是刚加进来的，
     *     装完再让它们一条条从左到右画一遍，看起来像是"打开文件顺便重画了一遍"。
     *     做法是把 [revealedIds] 直接置成新函数的 id 集合（见 `consumeReveal`）；
     *  3. **视口要保留当前画布尺寸**：文件里只存 center/unitsPerPixel，
     *     尺寸是设备的；用 `Viewport(0f, 0f, …)` 会把图像清成 0×0 直到下次重采样
     *     （`attachSurface` 只在布局尺寸变化时跑，路由切换不会触发它）。
     *     只有"画布还没量出尺寸"这一种情况才退回 [pendingRestore] 那条老路。
     */
    fun loadFileState(st: FunctionFileFormat.FileState) {
        functions = st.functions
        // 曲线形态的派生对象（圆 / 圆锥曲线 / 切线…）的施工说明：先清空再装上，
        // 否则上一次打开的文件留下的旧条目会按旧 id 继续生效。
        restoreCurveRecipes(st.functionRecipes)
        // 只收合法滑动条：名字必须是一位小写字母（见 validSliders）
        sliders = validSliders(
            st.sliders.map { s ->
                Slider(
                    id = s.id,
                    param = s.param,
                    min = s.min,
                    max = s.max,
                    step = s.step,
                    value = s.value,
                    colorIndex = s.colorIndex,
                    visible = s.visible,
                    // 播放状态不落库：打开文件不该让某条滑动条自己动起来
                    playing = false
                )
            }
        )
        objects = reNameMeasures(
            st.objects.map { it.toGraphObject() }
                // 施工说明按 id 套回去（文件里存着 `R|O|…` 那一段）。
                // 没有这一段的老文件 → recipe 保持 null，行为和以前一样。
                .map { o -> st.objectRecipes[o.id]?.let { o.withRecipe(it) } ?: o }
        )
        // 走统一的设置写入口：文件里的版本够用的设置，在本机上做不到时同样会被压掉
        applySettings(st.settings.toWatchSettings())

        // 对称规则：只填规则，**不在这里重算**折线 —— 此刻 `plot` 还是旧视口的采样结果，
        // 算出来是错的。`GraphRoute` 里那个以 `plot`/`functions` 为 key 的 effect
        // 会在新的采样结果出来后自动重建（`rebuildTransformedCurves`）。
        transformedCurves.clear()
        transformedCurves += st.transforms.map {
            TransformedCurve(functionId = it.functionId, axis = it.axis, center = it.center)
        }

        selectedId = if (st.functions.any { it.id == st.selectedId }) {
            st.selectedId
        } else {
            st.functions.firstOrNull()?.id ?: -1L
        }

        idSeq = maxOf(
            idSeq,
            st.functions.maxOfOrNull { it.id } ?: 0L,
            st.sliders.maxOfOrNull { it.id } ?: 0L,
            st.objects.maxOfOrNull { it.id } ?: 0L
        )

        // 视口：文件里的缩放必须是合法值，否则沿用当前的（别把用户丢进一个 NaN 视野）；
        // 低于当前精度的下限则按当前精度夹回去（放大上限跟着精度走）。
        val cur = viewport
        val upp = st.viewport.unitsPerPixel
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(Calc.precision.minUnitsPerPixel, Viewport.MAX_UNITS_PER_PIXEL)
            ?: cur.unitsPerPixel
        if (cur.widthPx > 1f) {
            viewport = Viewport(cur.widthPx, cur.heightPx, st.viewport.centerX, st.viewport.centerY, upp)
            pendingRestore = false
        } else {
            viewport = Viewport(0f, 0f, st.viewport.centerX, st.viewport.centerY, upp)
            pendingRestore = true
        }

        // 半截会话一概清掉：它们记的是**上一份内容**的下标/对象，装载之后引用已经对不上了。
        //
        // ⚠️ 这里原来是"五路撤销备份"清一遍（`clearedBackup` / `lastDeleted*` …）——
        // 那些备份本轮**整套删掉**了，所以现在只剩下面这两句"半截会话"。
        // 历史栈（`undoStack`）**故意不清**：打开一个文件本身不是"一次改动"，
        // 撤一下应该回到打开文件**之前**那一屏内容（那是用户按撤销时想要的），
        // 而快照里存的是**内容列表**而不是下标，所以跨文件也不会插错东西。
        justCreatedId = null
        tappedId = null
        // 名字绑定表：**从文件里恢复**（`snapshotForFile` 写的那一份）。
        // 老文件里没有这张表 → `st.bindings` 是空表 → 一切照自动规则走
        // （同名滑动条优先、其次同名标量，见 `PointCoordinates.resolveScalarValues`）。
        //
        // 这里原来是 `emptyMap()`（本轮之前格式里还存不下它）：那时留着的旧表会**串到
        // 新打开的文件上**（函数 id / 对象 id 都是文件里带过来的，两个文件完全可能撞上
        // 同一个 id），所以必须清掉。现在改成"用文件里的" —— 语义反而更准：
        // 打开谁的文件，就是谁的绑定。
        functionBindings = st.bindings
        pendingErrorFor = null
        backToMove()

        // 这些函数**不是新加的**：把"已揭示"集合直接对上新列表，揭示动画就返回空集。
        revealedIds = functions.map { it.id }.toSet()
    }

    /** 一个几何对象 → 文件模型（只在这一处做映射，字段少一个编译器会报）。 */
    private fun GraphObject.toFileObject(): FunctionFileFormat.FileObject = when (this) {
        is GraphPointObject -> FunctionFileFormat.FileObject.Point(
            id = id, name = name, point = point, kind = kind,
            colorIndex = colorIndex, visible = visible,
            // 吸附关系一起存（1.3.10）：不存的话重启后点还在、但曲线一动它就不跟了。
            attachment = attachment
        )

        is GraphLineObject -> FunctionFileFormat.FileObject.Line(
            id = id, name = name, line = line, role = role,
            colorIndex = colorIndex, visible = visible
        )

        is GraphMeasureObject -> FunctionFileFormat.FileObject.Measure(
            id = id,
            kind = when (kind) {
                MeasureKind.ANGLE -> FunctionFileFormat.FileMeasureKind.ANGLE
                MeasureKind.DISTANCE -> FunctionFileFormat.FileMeasureKind.DISTANCE
                MeasureKind.AREA -> FunctionFileFormat.FileMeasureKind.AREA
            },
            value = value,
            points = points,
            pointNames = pointNames,
            colorIndex = colorIndex,
            visible = visible,
            showValue = showValue
        )
    }

    /** 文件模型 → 一个几何对象（[toFileObject] 的逆）。 */
    private fun FunctionFileFormat.FileObject.toGraphObject(): GraphObject = when (this) {
        is FunctionFileFormat.FileObject.Point -> GraphPointObject(
            id = id, name = name, point = point, kind = kind,
            colorIndex = colorIndex, visible = visible,
            attachment = attachment
        )

        is FunctionFileFormat.FileObject.Line -> GraphLineObject(
            id = id, name = name, line = line, role = role,
            colorIndex = colorIndex, visible = visible
        )

        is FunctionFileFormat.FileObject.Measure -> GraphMeasureObject(
            id = id,
            // 文件里没有标量名（只有 pointNames），先给个空串占位，
            // 装载完由 loadFileState 里的 reNameMeasures 统一分配 a/b/c…
            name = "",
            kind = when (kind) {
                FunctionFileFormat.FileMeasureKind.ANGLE -> MeasureKind.ANGLE
                FunctionFileFormat.FileMeasureKind.DISTANCE -> MeasureKind.DISTANCE
                FunctionFileFormat.FileMeasureKind.AREA -> MeasureKind.AREA
            },
            points = points,
            value = value,
            pointNames = pointNames,
            colorIndex = colorIndex,
            visible = visible,
            showValue = showValue
        )
    }

    /** 文件模型里的设置 → 真正的全局设置。 */
    private fun FunctionFileFormat.FileSettings.toWatchSettings(): WatchSettings = WatchSettings(
        coordinateSystem = when (coord) {
            FunctionFileFormat.FileCoord.CARTESIAN -> CoordinateSystem.CARTESIAN
            FunctionFileFormat.FileCoord.POLAR -> CoordinateSystem.POLAR
            FunctionFileFormat.FileCoord.COMPLEX -> CoordinateSystem.COMPLEX
        },
        showGrid = showGrid,
        showAxes = showAxes,
        showNumbers = showNumbers,
        showPresets = showPresets,
        confirmCoordSwitch = confirmCoordSwitch,
        angleUnit = if (angle == FunctionFileFormat.FileAngle.DEG) AngleUnit.DEG else AngleUnit.RAD,
        // ---- 需求 #6 / #7 这两个设置**暂不随文件走**（有意的）----
        //
        // `.fdf` 里的设置段（`FunctionFileFormat.FileSettings`）这一轮不在改动范围内，
        // 它只带坐标系/网格/坐标轴/数字/示例/确认框/度制这七项。
        // 如果这里不给值，构造出来的 WatchSettings 就会用**默认值**顶掉用户当前的选择：
        // 用户明明关掉了"屏蔽系统手势"、开着"缩放栏"，一打开文件就被悄悄改回去 ——
        // 那种"打开文件顺带改我的设置"的行为比"不随文件走"难受得多。
        // 所以这里**保留当前值**（`settings` 是加载前的全局设置；这次写入走
        // `applySettings`，本机做不到时它会把值再压成 false）。
        // 将来 FileSettings 补上这两个字段时，把这两行换成读文件值即可。
        blockSystemGestures = settings.blockSystemGestures,
        showZoomBar = settings.showZoomBar,
        // 本轮新增的那一条（首次用工具时的"回到移动"提示）同属"暂不随文件走"：
        // 它是**本机的一次性说明**（"我在这里已经被告知过"），跟着文件跑到另一台设备上
        // 反而会替那台设备决定"别再提示"，所以同样**保留当前值**。
        // `FunctionFileFormat.FileSettings` 本轮不在可改范围内，所以文件里不存它。
        confirmReturnToMove = settings.confirmReturnToMove,
        // 计算精度同属"暂不随文件走"：它是本机的显示/计算偏好，
        // 文件格式里没有这一段，打开文件时保留用户当前的选择（默认 8 位）。
        precision = settings.precision,
        // ---- 主题相关的设置同样**不随文件走**（1.2.71 修"打开文件会重置主题"）----
        //
        // 用户原话："修复一下打开文件会重置主题的问题。"
        //
        // 真因就在这里没给值：`WatchSettings` 的其余字段会退回**默认值**，
        // 于是打开文件会把当前选择悄悄顶掉 ——
        //   * `themeStyle` 默认 MATERIAL：毛玻璃主题一打开文件就变回 Material；
        //   * `dayMode` 默认 false：白天模式被切回夜间黑底；
        //   * 模糊背景 / 模糊程度 / 按钮阴影强度 / 表冠模式与灵敏度同样被重置。
        // `.fdf` 的 `S` 段只带坐标系/网格/坐标轴/数字/示例/确认框/度制这七项，
        // 主题与观感是本机偏好（不该跟着文件跨设备跑），所以这里一律**保留当前值**，
        // 和上面三条"暂不随文件走"同理。
        dayMode = settings.dayMode,
        themeStyle = settings.themeStyle,
        blurBackdrop = settings.blurBackdrop,
        blurStrength = settings.blurStrength,
        keyShadowStrength = settings.keyShadowStrength,
        crownMode = settings.crownMode,
        crownSpeed = settings.crownSpeed
    )

    /** 真·移动模式（只有它是"拖动 = 平移图像"）。 */
    val isMoveTool: Boolean get() = activeTool == FunctionTool.MOVE

    /** 当前工具下一步要选什么（`null` = 不用再选，或者当前工具不吃点选）。 */
    val toolExpected: SelectionKind?
        get() {
            @Suppress("UNUSED_EXPRESSION") toolFlowVersion
            return toolFlow?.expected()
        }

    /** 当前工具"已经吃进去几个点"（界面用来画已选的点和连线）。 */
    val toolPickedPoints: List<GPoint>
        get() = toolPicked.filterIsInstance<ToolSelection.PointSel>().map { it.point }

    /**
     * 工具提示语："点角的顶点（第 2/3 步）"。
     *
     * 用户要求屏幕上**必须有一句话说明"现在要干什么、还差几步"**，否则他点一下
     * 没反应会以为界面坏了。这句话由状态机自己给（[ToolFlow.hintText]），
     * 因为"第几步"只有它知道 —— 以前这里用 `tool.stepCount - acceptedCount` 现算，
     * 而 acceptedCount 当时是错的（把对象 id 也算一步），于是提示语也跟着错
     * （"还差 1 个"其实一个点都没选对）。
     */
    val toolHint: String
        get() {
            @Suppress("UNUSED_EXPRESSION") toolFlowVersion
            val tool = activeTool
            return toolFlow?.hintText() ?: tool.hint
        }

    /**
     * 选一个工具。
     *
     * 用户原话里"点击之后到函数图像界面"是所有工具共有的行为，所以这里**自动**
     * 回图像界面（[FunctionTool.MOVE] 也一样：选完就回去拖图）。
     *
     * **每换一次工具都把上一次的半截选择丢掉**（否则"上一个工具选了两个点、
     * 新工具接着用"会算出一堆莫名其妙的结果）—— 但要说一句话，因为用户是
     * 在"还差一个点"的中途换的工具，静悄悄清空同样是"没有明确结果"。
     */
    fun selectTool(tool: FunctionTool) {
        val partial = toolPicked.isNotEmpty()
        activeTool = tool
        restartToolFlow(tool)
        // 选工具 = 马上要往画布上点点点，先把信息卡/属性卡收掉：
        // 留着会挡住画布（卡片浮在上三分之一），而且它的内容也不再是"用户正在看的东西"。
        // 工具模式下的点击**不会**再打开卡片（见 [onTapCanvas] 只被移动模式调用），
        // 所以收掉之后不会马上又冒出来。
        clearTappedCards()
        route = Route.Graph
        // 1.3.2：第一次切到描点工具时弹一次"怎么拖点"的说明
        // （用户要求：长按点或者选中点之后都可以拖动点进行移动，并附「不再提示」）。
        // [pointMoveHintAsked] 是本次运行的闩：没勾「不再提示」时本次不再重复弹，
        // 下次启动还会再问一次 —— 和 [WatchSettings.confirmReturnToMove] 同一套语义。
        if (tool == FunctionTool.PLOT_POINT && !pointMoveHintAsked &&
            !AppPrefs.pointMoveHintShown
        ) {
            pointMoveHintAsked = true
            pointMoveHint = true
        }
        if (partial) showToast("已切到${tool.label} · 上一次没选完的选择清掉了")
    }

    /**
     * 把当前工具的**这一轮选择**清空，工具本身**继续留在选中状态**。
     *
     * 这是本轮最重要的一条语义：用户原话"正确逻辑应该是可以选择多个角度之后
     * 手动切回移动才移动"。做完一个对象（[finishTool]）和用户主动"重新选"都走它。
     *
     * 工具没变时**复用同一个状态机**（只调 [ToolFlow.reset]）：少一次分配，
     * 而且"这一轮清了、工具没退"这件事在代码上更直白。
     */
    private fun restartToolFlow(tool: FunctionTool) {
        val current = toolFlow
        if (tool == FunctionTool.MOVE) {
            toolFlow = null
        } else if (current != null && current.tool == tool) {
            current.reset()
        } else {
            toolFlow = ToolFlow(tool)
        }
        toolPicked = emptyList()
        pendingPoint = null
        pendingKind = null
        toolFlowVersion++
    }

    /** 放弃当前工具的半截选择，但留在图像界面、**工具也不退**（继续取点/重新取点）。 */
    fun resetToolFlow() {
        restartToolFlow(activeTool)
    }

    /**
     * 回到「移动」模式。
     *
     * **只有用户手动切**才走这里：功能界面里的「移动」那一栏（[selectTool]）、
     * 图像界面提示条上的"回到移动"入口、图像界面按返回键。
     *
     * 工具算完**不再**自动走这条路（那是用户报的 bug 之一：量完一个角就退出、
     * 想量第二个得回功能界面重新选；更糟的是界面手势里那份"是不是移动模式"
     * 是旧快照，退回移动之后点也点不动、拖也拖不动）。
     */
    fun backToMove() {
        activeTool = FunctionTool.MOVE
        restartToolFlow(FunctionTool.MOVE)
        // 回移动模式时把两张卡收掉：用户按"回到移动"是想拖图/看全图，
        // 卡片（浮在上三分之一）留着会挡视线。想再看点一下对象就是。
        clearTappedCards()
    }

    // ---- 半径输入面板（「圆（半径·圆心）」用）----

    /** 关掉半径输入面板（取消 = 不建圆，工具留在"再点一个圆心"）。 */
    fun cancelRadiusInput() {
        radiusInputCenter = null
        radiusInputCenterId = null
        radiusInputText = ""
        restartToolFlow(activeTool)
        // 取消 = 回图像界面（和函数编辑器的 × 一样，不留在表单页）。
        route = Route.Graph
    }

    /**
     * 半径输入面板按下 ✓：校验表达式、建圆、关面板。
     *
     * 用户原话："如果识别到无法创建就不创建并弹出 toast 提示'请输入正确半径'。"
     * 所以**不合法时面板不关**（用户可以接着改），只弹那一句 —— 静悄悄关掉再什么都不说
     * 是最糟的结果。合法时建一条隐函数曲线（默认工具线的黑/白），关面板回到取点。
     *
     * 校验口径：半径里只能出现数字、滑动条参数（`a`~`d`）、常量和可调用函数；
     * 采样变量（`x`/`y`/`t`）一律不认 —— 那不是"一个半径值"。
     * 同时按**当前参数值**求一次数值，负数/0/无穷大同样算不合法（画出来是一个点或空）。
     */
    fun confirmRadiusInput(source: String) {
        val center = radiusInputCenter ?: return
        val expr = source.trim()
        val value = if (expr.isEmpty()) {
            null
        } else {
            runCatching {
                Expression.compile(
                    expr,
                    variables = emptyList(),
                    parameters = parameters(),
                    callables = callables()
                ).eval()
            }.getOrNull()
        }
        if (value == null || !value.isFinite() || value <= 0.0) {
            showToast("请输入正确半径")
            return
        }
        val eq = circleRadiusExpressionEquation(center, expr)
        // 1.3.1：把"圆心点 id + 半径表达式"记成构造关系 —— 半径表达式原样保留，
        // 拖圆心 / 改滑动条之后按新圆心重算方程，圆就跟着走了。
        val centerId = radiusInputCenterId
        val recipe = if (centerId != null) {
            ObjectRecipe.CircleCenterRadiusExpr(centerId, expr)
        } else {
            null
        }
        val name = addToolCurve(PlotMode.IMPLICIT, eq, recipe = recipe)
        radiusInputCenter = null
        radiusInputCenterId = null
        radiusInputText = ""
        restartToolFlow(activeTool)
        // 建好圆 → 离开整屏的半径输入页，回到图像界面看结果。
        route = Route.Graph
        showToast("已画出圆 $name：$eq")
    }

    /**
     * 工具建出来的一条曲线：**默认颜色是工具线的黑（夜间白）**，其余照常。
     *
     * 为什么不用 `addFunction` 自己给的 `functions.size % 6`：那是"函数"的配色，
     * 而用户明确要求"功能界面所有能绘制出线（包括直线和曲线）的功能绘制出的线
     * 默认颜色都是黑色（夜间白）"—— 曲线和自绘的线同色，才一眼看得出"这些是画出来的"。
     *
     * [tMin] / [tMax] / [degrees] 是给圆弧那条参数方程用的（量程是现算的弧度区间，
     * 而且**必须**按弧度解释 `cos t`，否则全局度制是 DEG 时圆弧会被算成一小段）。
     */
    private fun addToolCurve(
        mode: PlotMode,
        src1: String,
        src2: String = "",
        tMin: Double? = null,
        tMax: Double? = null,
        degrees: Boolean? = null,
        recipe: ObjectRecipe? = null
    ): String {
        val id = addFunction(mode, src1, src2)
        val created = functions.firstOrNull { it.id == id } ?: return ""
        updateFunctionNoUndo(
            created.copy(
                colorIndex = TOOL_CURVE_COLOR_INDEX,
                tMin = tMin ?: created.tMin,
                tMax = tMax ?: created.tMax,
                degrees = degrees ?: created.degrees
            )
        )
        // "这条曲线是按哪些点算出来的"：拖点 / 滑动条改曲线之后重算表达式
        // （见 [rebuildCurveEquations]）。null = 不跟随（比如半径是用户自己写的表达式）。
        trackCurve(id, recipe, src1, src2)
        return created.name
    }

    // ---- 2. 对象的增删改 ----

    fun objectById(id: Long): GraphObject? = objects.firstOrNull { it.id == id }

    fun addObject(o: GraphObject) {
        // 撤销点：一次工具动作 = 一步。
        //
        // ⚠️ **对称曲线的折线碎片不压**（`role == FRAGMENT_ROLE`）：一条曲线的对称结果是
        // 几十上百段 `GraphLineObject`，而且每次重采样都会整批重建
        // （`rebuildTransformedCurves` → `emitFragment` → 这里）。给它们压撤销点，
        // 20 步的上限会被一次变换冲干净，"撤销"从此撤不到任何用户认得的东西。
        // 碎片本身仍然在快照里（`objects` 一起被记下），只是不单独占一步。
        val isFragment = o is GraphLineObject && o.role == FRAGMENT_ROLE
        if (!isFragment) pushUndo("绘制 ${o.name}")
        objects = objects + o
    }

    /** 原地改一个对象，**不产生撤销点**（给已经自己压过一步的调用方用）。 */
    fun updateObject(o: GraphObject) {
        objects = objects.map { if (it.id == o.id) o else it }
    }

    fun setObjectColor(id: Long, colorIndex: Int) {
        objects.firstOrNull { it.id == id }?.let {
            pushUndo("${it.name} 换颜色", tag = "objcolor:$id")
            updateObject(
                when (it) {
                    is GraphPointObject -> it.copy(colorIndex = colorIndex)
                    is GraphLineObject -> it.copy(colorIndex = colorIndex)
                    is GraphMeasureObject -> it.copy(colorIndex = colorIndex)
                }
            )
        }
    }

    fun toggleObjectVisible(id: Long) {
        objects.firstOrNull { it.id == id }?.let {
            pushUndo("${it.name} 改显隐", tag = "objvisible:$id")
            updateObject(
                when (it) {
                    is GraphPointObject -> it.copy(visible = !it.visible)
                    is GraphLineObject -> it.copy(visible = !it.visible)
                    is GraphMeasureObject -> it.copy(visible = !it.visible)
                }
            )
        }
    }

    /**
     * 切换"数值是否在函数图像界面显示"（#9 对角度/距离/面积的要求）。
     *
     * 只对度量有意义；点/线没有数值，调用它是空操作（列表里也不会给它们这一栏）。
     */
    fun toggleObjectShowValue(id: Long) {
        val m = objects.firstOrNull { it.id == id } as? GraphMeasureObject ?: return
        pushUndo("${m.name} 改数值显示", tag = "objvalue:$id")
        updateObject(m.copy(showValue = !m.showValue))
    }

    /**
     * 改名时"**已经被别人占用**"的名字（**不含它自己**）。
     *
     * 三样东西：其它对象名、函数名（含"可调用名"）、**滑动条的参数字母** ——
     * 这正是 `GraphObjects.kt` 里 [objectNameError] 的 `taken` 契约写的那三样
     * （"别的对象名、函数名、滑动条名"），也就是"这个字母现在在这个 App 里
     * 已经有主了"的完整名单。函数名与对象名两头都从 `functions` / `objects`
     * 现算（和自动取名用的 [takenNames] 同一份来源）。
     *
     * ## 为什么这里比**自动取名**严一档（有意的）
     *
     * 自动取名（[nextMeasureName] → [takenNames]）**不把滑动条算作占用**：
     * 用户明确说过"标量也可以用 a，滑动条也可以用 f"，所以新建一个距离时
     * 完全可能分到 `a`（哪怕已经有一条 `a` 滑动条，那种重名靠
     * [functionBindings] / `resolveScalarValues` 的优先级消歧，不会算错）。
     *
     * 手动改名则把滑动条摆到明面上拒绝，理由有两条：
     *   * [objectNameError] 给用户的原因是"$name 已经被别的对象占用了"，
     *     而在用户自己的说法里滑动条**就是**一种对象（"滑动条和函数不是一个东西，
     *     但是同样是一个对象"），这句话不算说错；
     *   * 改名是**用户明确的意图表达**（他一个字一个字打出来的），
     *     这时候当场告诉他"`a` 已经有主了"比让他事后自己在列表里发现两行同名更清楚。
     *
     * 代价（**已知**，写在这里免得后人以为漏了）：一个对象如果在自动取名时
     * 已经分到了 `a`，用户把它改成别的、又想改回 `a` 时会被这句拒绝。
     * 这不是数据损坏 —— 模型从头到尾都还是那个合法的旧名字；
     * 而且刚打开属性屏时 `nameDraft == obj.name`，界面**不会**主动报这句。
     */
    fun takenObjectNames(exceptId: Long): Set<String> =
        takenNames(exceptId) + sliders.map { it.param }

    /**
     * 给一个几何对象**改名**，并把引用它的表达式一起改（用户本轮要求：
     * "给所有函数列表里面的对象都加长按菜单，直接调用函数的长按菜单，
     * 但是注意要适配各个对象的特征"）。
     *
     * ## 为什么必须是**一个原子方法**
     *
     * 对象和表达式之间有**两种**引用方式，性质完全不同：
     *   * **绑定表**（[functionBindings]，`Map<函数 id, Map<字母, 对象 id>>`）
     *     是按**对象 id** 记的 —— 改名**不会**断，一个字都不用改；
     *   * 用户**写在表达式文本里**的引用是按**名字**的：`x(A)`、`y(A)`、`a*x`。
     *     名字一改，这一类引用立刻变成"不认识的名字 'A'"（报错），
     *     或者更糟：撞上另一个同名对象，静默画成另一条曲线。
     *
     * 本轮的选择是**同步改引用**（而不是"禁止改被引用的名字"或"只提示"）：
     * 用户要的是"直接调用函数那套长按菜单"，那里面名字本来就能改，
     * 改完之后"我这条曲线还是指着那个点"才符合直觉。
     *
     * 而这件事**必须一次做完**：采样的钥匙同时包含 `functions` 和 `objects`
     * （见 `GraphScreen` 里那个 `LaunchedEffect`），如果拆成"先改名字、再改表达式"
     * 两次调用，中间那一帧的图上就是"名字已经换了、表达式还没换"——
     * 用户会看到一帧报错（错的曲线 + "不认识的名字"的 toast），
     * 而重采样是异步的，那一帧**真的会被算出来**。
     * 这里三处改动写在同一个函数里、之间**没有挂起点**，而 Compose 的重组是排队
     * 执行的，所以中途状态对外不可见 —— "原子"在这里就是这个意思。
     *
     * ## 一次改哪些地方（全部）
     *
     *   1. 对象自己的 [GraphObject.name]；
     *   2. **所有函数**的表达式文本：`src1` + `src2`（[PlotFunction] 里**仅有**的
     *      两个表达式字段；`tMin`/`tMax` 是数值、`name` 是它自己的名字、
     *      `lineStyle`/`widthScale` 是外观，都不是"引用某个对象的文本"）。
     *      滑动条的 `param` 也**不是**引用文本，它是那条滑动条自己的名字，同样不动；
     *   3. 度量副标题里的点名 [GraphMeasureObject.pointNames]（`角度（C，D）` 里那个 `C`）
     *      —— 它只是个说明文本，但用户要靠着它认出"这个角是拿哪两条线量出来的"，
     *      旧名字留在那里就等于那张说明失效了。
     *
     * ## 两处**已知歧义**（有意保留，不是漏了）
     *
     *   * 项目允许重名（标量可以叫 `a`、函数也可以叫 `a`），靠绑定表消歧。
     *     [renameIdentifierIn] 故意**跳过后面紧跟 `(` 的标识符**（那是**函数调用**的名字，
     *     和对象是两个命名空间），所以把某个标量改成 `a` 不会把 `a(x)` 里那个 `a` 改坏；
     *     代价是表达式里那个 `a` 指谁，仍然由 [functionBindings] / 名字优先规则决定。
     *   * 对象原来如果叫 `x`/`y`/`t`/`e`/`i`/`z`（引擎里有固定含义的小写名），
     *     改名会把这些变量引用**一起改掉**（`x^2` 里的 `x` 也会被换掉）。
     *     所以 [objectNameError] 把 `x/y/t/e/i/z` 列成"不许当标量名"——
     *     那条规则在 `GraphObjects.kt` 里，新建和改名**共用**它，不许在这边再抄一份。
     *
     * @param newName 新名字（已经去过空格）。校验用 [objectNameError]，
     *   不合法就**一个字都不动**（含表达式），返回 false —— 界面那边把原因显示出来。
     * @return true = 改了（新名字和旧名字一样也算成功：那是"点一下不改"的正当操作）；
     *   false = 校验没过（对象不存在 / 名字非法 / 被占用）。
     */
    fun renameObject(id: Long, newName: String): Boolean {
        val o = objects.firstOrNull { it.id == id } ?: return false
        // 名字没变：直接成功返回，**一个字都不动**。
        // 少了这一句，"名字删完再打回来"会把 `objects` 换成内容完全相同的新列表 ——
        // 而那个列表是采样的钥匙，于是白跑一整次重采样（曲线重新画一遍，
        // 在手表上看起来就是"闪了一下"）。
        if (o.name == newName) return true
        // taken 里**不含** o.name 自己：否则"点进去直接退出"也会被报成重名。
        if (objectNameError(o, newName, takenObjectNames(id)) != null) return false
        // 撤销点：校验都过了、确实要改，才压这一步（不然"改了个非法名字"也会占一步撤销，
        // 而用户按撤销时会发现什么都没变）。tag = 同一个对象的改名连打字只算一步。
        pushUndo("${o.name} 改名为 $newName", tag = "rename:$id")
        val old = o.name
        val renamed = when (o) {
            is GraphPointObject -> o.copy(name = newName)
            is GraphLineObject -> o.copy(name = newName)
            is GraphMeasureObject -> o.copy(
                name = newName,
                // 副标题里那份名单：等于旧名字的项换掉（`角度（C，D）` → `角度（E，D）`）。
                // 只换"等于旧名字"的项，所以坐标退化成 `(1.5, 2)` 这种非名字文本的项
                // 不会被动到。
                pointNames = o.pointNames.map { if (it == old) newName else it }
            )
        }
        // 所有函数的两个表达式框都过一遍。`renameIdentifierIn` 是纯函数
        // （`GraphObjects.kt`，离线单测钉着）：整段标识符匹配、`from == to` 原样返回、
        // 后面紧跟 `(` 的不动 —— 规则本体在那边，这里不重写。
        val nextFunctions = functions.map { fn ->
            val s1 = renameIdentifierIn(fn.src1, old, newName)
            val s2 = renameIdentifierIn(fn.src2, old, newName)
            // 没被改到的函数**原样放回**（同一个实例）：`PlotFunction` 进采样钥匙，
            // 无谓地 copy 一遍会让 key 变化、白跑一次重采样。
            if (s1 == fn.src1 && s2 == fn.src2) fn else fn.copy(src1 = s1, src2 = s2)
        }
        functions = nextFunctions
        objects = objects.map { if (it.id == id) renamed else it }
        return true
    }

    /**
     * 删除一个几何对象（列表里左滑）。和删函数一样：后路只有撤销栈那一条。
     */
    fun removeObject(id: Long) {
        val index = objects.indexOfFirst { it.id == id }
        if (index < 0) return
        val name = objects[index].name
        // 撤销点必须在**改之前**压（快照记的是"这个对象还在"的那个状态）
        pushUndo("删除 $name")
        objects = objects.filterNot { it.id == id }
        // 被删的正好是属性卡上那一个 → 把"点中的是谁"也清掉
        // （卡片本身会因为 `tappedObject` 查不到而收起，这里是把状态一起归零，
        //  免得留下一个指向不存在对象的 id）
        if (tappedObjectId == id) tappedObjectId = null
        // 删掉了什么要说一句（原来由底部那条 2 秒撤销条承担）
        showToast("已删除 $name")
    }

    /** 点击命中附近的交点 / 极值点（#7 后半）。 */
    data class FeatureHit(
        /** 所有命中点，直接喂给 `GraphCanvas(highlightPoints = …)` 画光晕。 */
        val points: List<GPoint>,
        /** 这些点都是"谁和谁的交点"（去重后的曲线名）。 */
        val intersectionOf: List<String>,
        /** 命中的极值点。 */
        val extrema: List<GPoint>
    )

    /**
     * 点中一条曲线之后，找出手指附近（半径 [radiusPx] 屏幕像素）的
     * **交点**和**极值点**（用户需求 #7 后半："若附近有两个及以上曲线的交点或极值点，
     * 在信息卡里加'交点 / 极值点'两行坐标"）。
     *
     * 为什么单独一个方法、而不是塞进 [featureLines]：
     *  * [featureLines] 只返回**文本**，而这些点还要交给 `GraphCanvas` 画光晕，
     *    两边必须用同一份数据（否则会出现"卡片里有、图上没圈"）；
     *  * 它需要**视口**参数，而 [featureLines] 只用到了世界范围。
     *
     * 交点的算法是两两折线求交（`Geometry.intersections`），对**每一对**可见曲线都要算一遍；
     * 曲线对数上了 4 条以上就明显变慢，所以这里限制只看前 4 条 —— 手表上
     * 同屏四条曲线已经是极限了。
     */
    fun tapFeatures(fn: PlotFunction, radiusPx: Float): FeatureHit? {
        val plotted = plot.viewport ?: return null
        val vp = viewport
        if (plotted.unitsPerPixel <= 0.0 || !plotted.unitsPerPixel.isFinite()) return null
        if (vp.unitsPerPixel <= 0.0 || !vp.unitsPerPixel.isFinite()) return null
        val base = plot.curves.firstOrNull { it.functionId == fn.id } ?: return null
        val r = radiusPx.toDouble()

        // "附近"是相对**手指按下的那个像素**说的（用户在 #7 里明说"半径 = 屏宽 1/10"）。
        // 手指位置是当前视口下的屏幕像素，而这里的点都是世界坐标，
        // 所以把半径按当前视口折算成世界单位，省掉逐点做屏幕变换。
        val anchor = GPoint(
            vp.toWorldX(lastTapPx.toDouble()),
            vp.toWorldY(lastTapPy.toDouble())
        )
        val rw = r * vp.unitsPerPixel

        val nearby = ArrayList<GPoint>()
        val names = LinkedHashSet<String>()

        // 这条曲线自己与其它曲线的交点
        for (c in plot.curves) {
            if (c.functionId == fn.id) continue
            if (names.size >= MAX_INTERSECTION_CURVES) break
            val n = functions.firstOrNull { it.id == c.functionId }?.name ?: continue
            val pts = runCatching { intersections(base, c, plotted) }.getOrDefault(emptyList())
            for (p in pts) {
                if (hypot(p.x - anchor.x, p.y - anchor.y) > rw) continue
                nearby += p
                names += "${fn.name} ∩ $n"
            }
        }

        // 这条曲线自己的极值点（顶点）和零点
        val extrema = ArrayList<GPoint>()
        compileForFeature(fn)?.let { f ->
            val feats = runCatching {
                FeatureFinder.analyze(f, plotted.worldLeft, plotted.worldRight, limit = MAX_FEATURES)
            }.getOrNull()
            if (feats != null) {
                for (e in feats.extrema) {
                    val p = GPoint(e.x, e.y)
                    if (p.isFinite && hypot(p.x - anchor.x, p.y - anchor.y) <= rw) extrema += p
                }
                // 零点也算特征点，一起高亮（#9 明确要求吸附它们）
                for (x in feats.roots) {
                    val y = runCatching { f.eval(x) }.getOrDefault(Double.NaN)
                    if (!y.isFinite()) continue
                    val p = GPoint(x, y)
                    if (hypot(p.x - anchor.x, p.y - anchor.y) <= rw) extrema += p
                }
            }
        }

        val all = nearby + extrema
        if (all.isEmpty()) return null
        return FeatureHit(
            points = all.take(MAX_FEATURE_HIGHLIGHTS),
            intersectionOf = names.toList(),
            extrema = extrema.take(MAX_FEATURE_HIGHLIGHTS)
        )
    }
    /**
     * **清屏**（功能界面右上角的垃圾桶图标）。
     *
     * 只清"这次用工具画出来的东西"，**函数和方程一个都不动** —— 那是
     * [clearAllFunctions]（长按 +）的职责，两者刻意分开：用户画了半天的曲线
     * 不该被一个清几何对象的按钮清掉。
     *
     * **撤销**：和别的删除一样，`pushUndo` 在改动之前压了一步（快照里有对象、
     * 对称规则、函数、滑动条），用工具条上的左箭头就能整屏撤回来 —— 原来那条
     * "底部浮出小条、点一下撤回"本轮被用户要求删掉了。
     * 顺手把当前工具退回「移动」：清完屏还停在"切线"之类要选点的工具上，
     * 用户下一点又画出一个东西，反而像没清干净。
     */
    fun clearObjects() {
        if (objects.isEmpty()) return
        // 撤销点：清屏是"一下就全没了"的破坏性操作，撤销栈要能撤回去
        pushUndo("清屏")
        objects = emptyList()
        // 曲线对称的规则也一起丢掉：清屏之后不该还剩一条"会自己长回来"的对称曲线。
        // （它进了快照，所以撤销时跟着一起回来。）
        transformedCurves.clear()
        backToMove()
    }



    /**
     * 已登记的"曲线对称"规则（见 [TransformedCurve]）。
     *
     * 曲线对称是**实时**的：一条曲线的对称结果会随采样结果重算，
     * 所以这里存的是"哪个函数、按什么轴/中心变换"，而不是算好的点。
     */
    private val transformedCurves = mutableListOf<TransformedCurve>()

    /**
     * 一条"被对称变换过的曲线"的**规则**。
     *
     * 为什么记规则而不是只记结果：折线段的长度是采样那一刻定下的，
     * 用户放大之后要重算（否则曲线会退化成明显的折线）。而结果对象本身
     * 只是 `GraphLineObject`（没有"我是怎么来的"这个信息），所以规则单独存一份，
     * 由 `GraphRoute` 在每次重采样之后调 [rebuildTransformedCurves] 重放。
     *
     * [axis] 与 [center] 二选一：轴对称给 axis、中心对称给 center。
     */
    private data class TransformedCurve(
        val functionId: Long,
        val axis: GLine?,
        val center: GPoint?
    )

    /**
     * 按当前采样结果重放所有曲线对称。
     *
     * 由图像界面在 `plot` / `functions` 变化后调用（见 `GraphRoute`）。
     * 每次先把上一次生成的折线碎片全部清掉再重建 —— 碎片对象**不进函数列表**
     * （`appearsInList`），所以用户在列表里看不到它们被摘掉又加回来。
     */
    fun rebuildTransformedCurves() {
        if (transformedCurves.isEmpty()) return
        val keep = objects.filterNot { it is GraphLineObject && it.role == FRAGMENT_ROLE }
        objects = keep
        for (t in transformedCurves.toList()) {
            val fn = functions.firstOrNull { it.id == t.functionId } ?: continue
            val xf: (GPoint) -> GPoint = if (t.center != null) {
                { p -> reflectThrough(p, t.center) }
            } else {
                val axis = t.axis ?: continue
                { p -> reflect(p, axis) }
            }
            emitTransformedCurve(fn, xf)
        }
    }

    /** 相邻两个折线点之间的世界距离超过它就算"断开了"（把对称曲线在间断处切开）。 */
    private fun gapWorld(): Double {
        val upp = viewport.unitsPerPixel
        return if (upp.isFinite() && upp > 0.0) upp * 6.0 else 0.0
    }

    // ---- 3. 吸附 ----

    /**
     * 吸附半径（屏幕像素）。
     *
     * 取屏宽的 6%（466px 屏 ≈ 28px），下限 20px。
     *  * 为什么按屏宽比例而不是固定值：这个 App 的 density 报到 1.0 的机型上
     *    屏幕像素数是两倍，固定 24px 在那种屏上只有一半的"手指感"；
     *  * 为什么是 6%：`Geometry.DEFAULT_SNAP_PX = 24` 是引擎给 400~450px 屏的建议值，
     *    466 × 0.06 ≈ 28 比它略宽松一点 —— 描点要吸上零点和顶点，宁可多吸一点；
     *    再大（比如 10% = 47px）就会出现"想点旁边，结果被吸到特征点上"。
     */
    private fun snapRadiusPx(): Double =
        (viewport.widthPx * SNAP_RADIUS_FRACTION).coerceAtLeast(MIN_SNAP_PX).toDouble()

    /**
     * 原点 / 坐标轴的吸附半径。
     *
     * 用户明确要求"吸附范围不要太大"：轴是一条**无限长**的线，如果按曲线那一档
     * （6% 屏宽 ≈ 28px）吸，手指在轴附近稍微一偏就会被拉走，在轴上描点反而更难。
     * 这里取 3.5% 屏宽（466px 屏 ≈ 16px，曲线的六成不到），下限 10px ——
     * 能吸上、但不至于"路过就粘住"。
     */
    private fun axisSnapRadiusPx(): Double =
        (viewport.widthPx * AXIS_SNAP_FRACTION).coerceAtLeast(MIN_AXIS_SNAP_PX).toDouble()

    /**
     * 收集吸附候选点。
     *
     * 顺序**就是优先级**（[snap] 平局时取先出现的那个），按用户要求的
     * "吸附函数顶点、交点、零点和极值点"来排：
     *   1. 已有几何点（用户自己描的点优先级最高 —— 他就是要选它）
     *   2. 零点 / 极值点（顶点）/ 函数与坐标轴的交点
     *   3. 两条曲线的交点
     *
     * [onlyOnCurve] 不为空时只收那条曲线上的候选（切线用：用户原话
     * "选择函数或方程上的一点"，所以吸附不能跑到别的曲线上）。
     */
    fun snapCandidates(onlyOnCurve: Long? = null): List<SnapCandidate> {
        val out = ArrayList<SnapCandidate>()
        val vp = viewport
        val vpOk = vp.widthPx > 1f && vp.unitsPerPixel > 0.0 && vp.unitsPerPixel.isFinite()

        // 1. 已有几何点
        for (o in objects) {
            if (o is GraphPointObject && o.visible) out += SnapCandidate(o.point, o.kind)
        }

        // 2. 每条曲线自己的特征点（零点 / 顶点 / 与 y 轴交点）。
        //    没有采样结果（还没出图）就没有特征点；但**第 3 步不能因此跳过** ——
        //    自绘线之间的交点根本不需要采样（纯解析）。
        val plotted = plot.viewport
        if (plotted != null) {
            val picked = functions.filter { it.visible }
                .filter { onlyOnCurve == null || it.id == onlyOnCurve }
            for (fn in picked) {
                val f = compileForFeature(fn) ?: continue
                val xMin = plotted.worldLeft
                val xMax = plotted.worldRight
                if (!(xMax > xMin)) continue
                val feats = runCatching { FeatureFinder.analyze(f, xMin, xMax, limit = MAX_FEATURES) }
                    .getOrNull() ?: continue
                for (x in feats.roots) {
                    val y = runCatching { f.eval(x) }.getOrDefault(Double.NaN)
                    if (x.isFinite() && y.isFinite()) out += SnapCandidate(GPoint(x, y), SnapKind.ZERO)
                }
                for (e in feats.extrema) {
                    if (e.x.isFinite() && e.y.isFinite()) {
                        out += SnapCandidate(GPoint(e.x, e.y), SnapKind.EXTREMUM)
                    }
                }
                // 与 y 轴的交点（x = 0）：显函数才有意义，也算一种"特征点"。
                if (fn.mode == PlotMode.EXPLICIT) {
                    val y0 = runCatching { f.eval(0.0) }.getOrDefault(Double.NaN)
                    if (y0.isFinite()) out += SnapCandidate(GPoint(0.0, y0), SnapKind.INTERSECTION)
                }
            }
        }

        // 3. 曲线交点（函数 ↔ 函数、函数 ↔ 自绘线、自绘线 ↔ 自绘线）。
        //    **数量上限在 [intersectionCandidates] 里分档控制**：折线求交贵（函数对压到 4 对），
        //    自绘线两两求交是纯算术（不设那么死的上限 —— 之前套用"最多 4 条"，
        //    画到第 5 条线之后它的交点就**整个从候选表里消失**，症状正是
        //    "描点会避开交点、根本描不到"）。
        if (onlyOnCurve == null && vpOk) {
            for ((p, _) in intersectionCandidates()) {
                out += SnapCandidate(p, SnapKind.INTERSECTION)
            }
        }
        return out
    }

    /**
     * 所有**交点**候选，连同"它来自哪两条对象"（[PointAttachment.OnIntersection]）。
     *
     * 两处共用这一份表：
     *   * 描点时的吸附候选（[snapCandidates] 的第 3 组）；
     *   * 落点后要记的"跟着哪两条走"（[attachmentAt]）——
     *     吸附选中的那个点和表里的某一行是**同一个世界坐标**，所以按位置就能认回来。
     *
     * 三种情形：函数 ↔ 函数、函数 ↔ 自绘直线/线段/射线、自绘线 ↔ 自绘线。
     * 用户原话："再给描点加个吸附曲线（包括函数曲线和自绘曲线）交点的功能"。
     *
     * 出错（采样异常 / 某条对象暂时没有曲线）就跳过那一对，不让整次吸附失败。
     */
    private fun intersectionCandidates(): List<Pair<GPoint, PointAttachment.OnIntersection>> {
        val out = ArrayList<Pair<GPoint, PointAttachment.OnIntersection>>()

        // 采样视口只给"折线求交"用；自绘线之间的交点是解析的，**不需要**它。
        // 之前这里写成 `plot.viewport ?: return emptyList()`，于是"一条函数都没有、
        // 只画了两条直线"时整张交点表直接为空 —— 描点就只会吸到曲线本身（沿着某条线
        // 滑走），看起来就是"避开交点、描不到"。
        val vp = plot.viewport?.takeIf {
            it.widthPx > 1f && it.unitsPerPixel > 0.0 && it.unitsPerPixel.isFinite()
        }

        val fns = functions.filter { it.visible }.take(MAX_INTERSECTION_CURVES)
        // 上限是 [MAX_INTERSECTION_LINES]（比函数对宽松得多）：两两解析求交几乎不花时间，
        // 而"第 5 条线就整个消失"对用户来说是不可理解的。真正的规模控制交给
        // 上面的 `snap()` 半径 —— 只在手指 20 多像素内才算命中。
        val lines = objects.filterIsInstance<GraphLineObject>()
            .filter { it.visible && it.role != FRAGMENT_ROLE && !it.line.isDegenerate }
            .take(MAX_INTERSECTION_LINES)

        if (vp != null) {
            // 1) 函数 ↔ 函数
            for (i in fns.indices) {
                val ca = plot.curves.filter { it.functionId == fns[i].id }
                if (ca.isEmpty()) continue
                for (j in i + 1 until fns.size) {
                    val cb = plot.curves.filter { it.functionId == fns[j].id }
                    if (cb.isEmpty()) continue
                    val att = PointAttachment.OnIntersection(
                        aFunction = fns[i].id,
                        bFunction = fns[j].id
                    )
                    for (a in ca) for (b in cb) {
                        val pts = runCatching { intersections(a, b, vp) }.getOrDefault(emptyList())
                        for (p in pts) out += p to att
                    }
                }
            }

            // 2) 函数 ↔ 自绘线（折线求交，比解析贵，所以这一组限制对数）
            var pairs = 0
            outer@ for (fn in fns) {
                for (o in lines) {
                    if (pairs >= MAX_INTERSECTION_LINE_PAIRS) break@outer
                    pairs++
                    val att = PointAttachment.OnIntersection(aFunction = fn.id, bLine = o.id)
                    for (curve in plot.curves) {
                        if (curve.functionId != fn.id) continue
                        val pts = runCatching { intersections(curve, o.line, vp) }
                            .getOrDefault(emptyList())
                        for (p in pts) out += p to att
                    }
                }
            }
        }

        // 3) 自绘线 ↔ 自绘线（解析求解，不需要采样）
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val p = intersectionOfLines(lines[i].line, lines[j].line) ?: continue
                out += p to PointAttachment.OnIntersection(
                    aLine = lines[i].id,
                    bLine = lines[j].id
                )
            }
        }
        return out
    }

    /**
     * 编译一条函数/方程，用来算特征点（[FeatureFinder] 只吃 `Expression`）。
     *
     * 和 `featureLines` 用的是同一条路径：只对显函数谈得上"零点/顶点"，
     * 别的模式（极坐标、参数方程、隐函数）直接放弃 —— 宁可不吸附，
     * 也不编一个数字出来。
     */
    private fun compileForFeature(fn: PlotFunction): Expression? {
        if (fn.mode != PlotMode.EXPLICIT) return null
        return runCatching {
            Expression.compile(
                // 吸附用的零点/极值点也必须来自**画出来的那条曲线**，所以要过取坐标替换
                withPointCoordinates(stripExplicitPrefix(fn.src1)),
                listOf("x"),
                effectiveAngleUnit(fn).isDegrees,
                parameters(),
                callables()
            )
        }.getOrNull()
    }

    /**
     * 把一次屏幕点击吸附成一个世界坐标点。
     *
     * `snap = false` 时原样返回手指位置换算出来的世界坐标（描点第一阶段用：
     * 还没吸附，等手指抬起来才吸）。
     */
    fun snappedPoint(px: Float, py: Float, snap: Boolean = true, onlyOnCurve: Long? = null): GPoint {
        val raw = GPoint(viewport.toWorldX(px.toDouble()), viewport.toWorldY(py.toDouble()))
        if (!snap) return raw
        val hit = snap(snapCandidates(onlyOnCurve), viewport, px, py, snapRadiusPx())
        return hit?.point ?: nearestOnAnyCurve(px, py, onlyOnCurve)?.point ?: raw
    }

    /** 同 [snappedPoint]，但连"吸到了什么"一起给（描点要知道吸的是交点还是零点）。 */
    fun snappedHit(px: Float, py: Float, onlyOnCurve: Long? = null): SnapCandidate? {
        // 0. 原点 / 坐标轴。用户原话："再给描点加个原点和坐标轴的吸附，
        //    原点权重大于坐标轴权重大于函数曲线权重，吸附范围不要太大。"
        //    原点优先级最高，所以放在特征点之前；轴则排在特征点之后、曲线之前
        //    （特征点更精确 —— 零点本来就在 x 轴上，先吸到零点比吸到整条轴更有用）。
        val axisHit = if (onlyOnCurve == null && settings.showAxes) {
            snapToAxes(viewport, px, py, axisSnapRadiusPx())
        } else null
        // 原点（且只可能是原点，因为 [snapToAxes] 里原点优先）直接返回。
        if (axisHit != null && axisHit.point.x == 0.0 && axisHit.point.y == 0.0) return axisHit

        val hit = snap(snapCandidates(onlyOnCurve), viewport, px, py, snapRadiusPx())
        if (hit != null) return SnapCandidate(hit.point, hit.kind)
        // 特征点都没吸上时才轮到坐标轴（此时 [axisHit] 一定不是原点）。
        if (axisHit != null) return axisHit
        // 没吸到特征点，但手指离某条曲线很近 → 吸到曲线上（见 nearestOnAnyCurve）
        val onCurve = nearestOnAnyCurve(px, py, onlyOnCurve)?.point ?: return null
        return SnapCandidate(onCurve, SnapKind.OTHER)
    }

    /**
     * **吸附到曲线本身**。
     *
     * 用户要求："描点功能里，如果手指触控位置的附近有函数曲线或者交点、零点、极值点，
     * 应该自动吸附上去。"
     *
     * 特征点（交点/零点/极值点）走 [snapCandidates]，而"曲线本身"必须**按点击位置**
     * 现算 —— 候选是无限的，所以这一步只能在这里做（先试特征点、都没吸上再吸曲线，
     * 保证特征点的优先级更高：那是用户真正想要的东西）。
     *
     * 用的是引擎的 `nearestOnCurve`（屏幕空间最近点，含像素距离），
     * 所以阈值和特征点同一套（[snapRadiusPx]），手感一致。
     *
     * ## 自绘曲线也要吸（用户要求）
     *
     * 用户原话："描点只能吸附函数曲线不能吸附自绘曲线，请你添加可以吸附自绘曲线。"
     * 自绘曲线就是工具画出来的直线 / 线段 / 射线（[GraphLineObject]）——
     * 它们的世界坐标只有两个锚点，最近点用投影算（和 [lineObjectAt] 同一套
     * `projectionParam` + `distanceToShape`），再按像素距离和函数曲线一起比。
     *
     * [onlyOnCurve] 非空时**不吸自绘曲线**：那条路径是"切线只能选某条函数上的点"，
     * 跑到直线上就不是用户要的了。
     */
    private fun nearestOnAnyCurve(
        px: Float,
        py: Float,
        onlyOnCurve: Long?,
        limitPx: Double = snapRadiusPx()
    ): CurveHit? {
        val vp = plot.viewport ?: return null
        if (vp.widthPx <= 1f) return null
        val limit = limitPx
        val picked = functions.filter { it.visible }
            .filter { onlyOnCurve == null || it.id == onlyOnCurve }
        var best: GPoint? = null
        var bestFunction: Long? = null
        var bestLine: Long? = null
        var bestDist = Double.MAX_VALUE
        if (picked.isNotEmpty()) {
            for (fn in picked) {
                // 一条函数可能被采样成好几段（间断处会切开），每段都要试
                for (curve in plot.curves) {
                    if (curve.functionId != fn.id) continue
                    val (point, dist) = nearestOnCurve(curve, px, py, vp) ?: continue
                    if (dist <= limit && dist < bestDist) {
                        best = point
                        bestFunction = fn.id
                        bestLine = null
                        bestDist = dist
                    }
                }
            }
        }

        // 自绘曲线：投影到直线 / 线段 / 射线上取最近点（线段夹 0~1、射线夹 ≥0）。
        if (onlyOnCurve == null) {
            val finger = GPoint(vp.toWorldX(px.toDouble()), vp.toWorldY(py.toDouble()))
            for (o in objects) {
                if (o !is GraphLineObject || !o.visible || o.role == FRAGMENT_ROLE) continue
                if (o.line.isDegenerate) continue
                val raw = projectionParam(finger, o.line)
                val t = when (o.line.kind) {
                    GLineKind.LINE -> raw
                    GLineKind.SEGMENT -> raw.coerceIn(0.0, 1.0)
                    GLineKind.RAY -> raw.coerceAtLeast(0.0)
                }
                val point = o.line.pointAt(t)
                val dist = screenDistance(vp, px.toDouble(), py.toDouble(), point)
                if (dist <= limit && dist < bestDist) {
                    best = point
                    bestFunction = null
                    bestLine = o.id
                    bestDist = dist
                }
            }
        }
        return best?.let { CurveHit(it, bestFunction, bestLine) }
    }

    /**
     * 一次"吸到曲线上"的结果：点 + **它吸的是谁**（见 [CurveHit.functionId] / [CurveHit.lineId]）。
     *
     * 只给 [commitPendingPoint] / [movePointDrag] 用 —— 它们要把"跟着这条曲线走"
     * 记进 [PointAttachment]，以后曲线/线被拖动时这个点才知道重新贴到哪儿。
     */
    private data class CurveHit(
        val point: GPoint,
        val functionId: Long? = null,
        val lineId: Long? = null
    )

    // ---- 4. 描点（画到一半的临时点） ----

    /**
     * 描点：**手指一按下**就放一个"待定"点并跟着手走（用户原话"正确逻辑应该是
     * 按下就显示点，松开确定点的位置"），松手那一刻才真正落进 [objects]。
     *
     * 实现上是"一个临时的 [pendingPoint] + 松手时才真正建对象"：
     *  * 拖动全程只改这一个快照状态，不往 [objects] 里塞一堆中间点；
     *  * 渲染层把 pendingPoint 画成一枚高亮点（见 `GraphRoute` 里的
     *    `secondaryHighlightPoints = toolPickedPoints + pendingPoint`），
     *    所以它看起来和"已经放下的点"一样，松手时无缝变成真的对象。
     *
     * 上一版"按下没反应、松手才出现点"的原因在**界面**那一侧（`GraphScreen`）：
     * 描点当时只挂在 `detectTapGestures` 上，而只要注册了 `onDoubleTap`，
     * 单击就必须等双击超时才回调（≈300ms，就是用户说的"有一拍延迟"）；
     * 另一条拖动路径又要等手指移过触摸容差才 `onDragStart`，轻轻一按根本不出现点。
     * 现在界面改成自己 `awaitEachGesture`：down 的那一刻就调它。
     */
    fun beginPendingPoint(px: Float, py: Float) {
        lastTouchPx = px
        lastTouchPy = py
        // 按下时**不吸附**：用户还在找位置，吸附留到松手（那时才定位置）
        pendingPoint = snappedPoint(px, py, snap = false)
        pendingKind = null
    }

    /** 手指还没松时跟手（每移动一次重新吸附一次 —— 用户能看到"快吸上了"的过程）。 */
    fun movePendingPoint(px: Float, py: Float) {
        lastTouchPx = px
        lastTouchPy = py
        val hit = snappedHit(px, py)
        // 吸上了就吸住，吸不上才回到手指位置。这样"经过特征点"时会有轻微的黏滞感。
        pendingPoint = hit?.point ?: GPoint(
            viewport.toWorldX(px.toDouble()),
            viewport.toWorldY(py.toDouble())
        )
        pendingKind = hit?.kind
    }

    /** 最后一次触摸的屏幕位置（`onDragEnd` 不带坐标，松手时要靠它再吸一次）。 */
    private var lastTouchPx = 0f
    private var lastTouchPy = 0f

    /**
     * 松手：把"待定"点**定住**（这一刻才真正进 [objects]、才有名字、才进函数列表）。
     *
     * 松手时**再吸附一次**，而且这次带上"附近的已有几何点" —— 描第二个点的时候
     * 手指不可能两次都精确落在同一个像素上，这一步让"描在同一个点上"真的成立。
     *
     * 手指出画布 / 手势被取消时**不要**调它，调 [cancelPendingPoint]：待定点消失、
     * 不产生对象（用户明确要求）。
     */
    fun commitPendingPointAtLastTouch() {
        commitPendingPoint(lastTouchPx, lastTouchPy)
    }

    /** 松手定住（显式给屏幕位置，方便单测/其它入口调用）。 */
    fun commitPendingPoint(px: Float, py: Float) {
        val cur = pendingPoint ?: return
        val hit = snappedHit(px, py)
        val point = hit?.point ?: cur
        val kind = hit?.kind ?: SnapKind.POINT
        pendingPoint = null
        pendingKind = null
        val id = nextId()
        addObject(
            GraphPointObject(
                id = id,
                // 点是**唯一**用大写字母的对象（用户本轮规则），所以这里必须点名 POINT
                name = nextObjectName(ObjectKind.POINT),
                point = point,
                kind = kind,
                // 描点默认**蓝色**（用户要求，见 POINT_DEFAULT_COLOR_INDEX）
                colorIndex = POINT_DEFAULT_COLOR_INDEX,
                // 吸在曲线/直线上 → 记下"跟着谁走"（滑动条改了、曲线动了，这个点跟着动）
                attachment = attachmentAt(px, py, kind, point)
            )
        )
    }

    fun cancelPendingPoint() {
        pendingPoint = null
        pendingKind = null
    }

    // ---- 4b. 拖动已经放下的点（用户要求） ----

    /**
     * 用户原话："实心的点在点击之后可以拖动来改变它的位置，或者长按它直接拖动；
     * 在函数曲线或者自绘曲线上描出来的实心的点也可以拖动，如果函数曲线或者自绘曲线移动，
     * 这个点也跟着移动，确保这个点一定在之前描的曲线上。"
     *
     * 界面那一侧（`GraphScreen` 的手势 1b）负责判定"这一串要不要交给拖点"：
     * 按在点上、而且（已经选中 **或** 按住到长按）才开始，见那边的说明。
     * 这里只管三件事：抓点偏移、约束、写回对象。
     */
    var draggingPointId by mutableStateOf<Long?>(null)
        private set

    /** 手指按下处与点中心的**世界坐标**差：拖动时保持它不变，点不会瞬间跳到指尖。 */
    private var dragGrabDx = 0.0
    private var dragGrabDy = 0.0

    /**
     * 被拖的点是不是"吸附在曲线本身上的描点"（[SnapKind.OTHER]）—— 它拖动时不许离开曲线。
     *
     * 现在能拖的只剩实心点（[GraphPointObject.isDraggable]）：自由描点（[SnapKind.POINT]）
     * 随便拖，吸在曲线上的描点（[SnapKind.OTHER]）沿曲线滑。
     */
    private var dragConstrained = false

    /**
     * 拖动期间**锁死在哪个对象上**（曲线或自绘线）。
     *
     * 用户原话："在一条直线上描点，再拖动这个点在直线上移动，这个点在手拖到另一条曲线上时
     * 会瞬移到另一条曲线上，应该有的逻辑是最开始在一条曲线上描的点只能在这条曲线上运动，
     * 直线也一样。"
     *
     * 所以约束只在这条点**自己吸上的那个对象**里求最近点，不再"哪条近就跳哪条"。
     * 按下的那一刻定下来，整段拖动不变；找不到锁定的对象（曲线被删了、存档里没存
     * 依附关系）就退回自由拖动，免得点在原地卡住。
     */
    private sealed interface DragLock {
        data class OnCurve(val functionId: Long) : DragLock
        data class OnLine(val lineId: Long) : DragLock
    }

    private var dragLock: DragLock? = null

    /**
     * 按下的位置有没有压在一个可拖的点上（有就返回它的 id）。
     *
     * **只有实心点能拖**（用户要求："空心的点要做成不可拖动"）—— 特征点
     * （交点 / 零点 / 极值点 / 顶点）和派生点（中点 / 对称点）返回 null，
     * 手势那一边于是把它当成普通的点选 / 平移。
     */
    fun draggablePointAt(px: Float, py: Float): Long? =
        pointObjectAt(px, py)?.takeIf { it.isDraggable }?.id

    /**
     * 按下的位置有没有压在一个**点对象**上，不管它能不能拖。
     *
     * 描点模式用这个跳过"在已有点上叠着再描一个"（见 `GraphScreen` 手势 1）：
     * 空心点虽然不能拖，但显然也不该在它上面再描一个重合的点。
     */
    fun anyPointAt(px: Float, py: Float): Long? = pointObjectAt(px, py)?.id

    /** 开始拖 [id]（世界坐标偏移按 [px]/[py] 这一点算，见 [dragGrabDx]）。 */
    fun beginPointDrag(id: Long, px: Float, py: Float) {
        val o = objects.firstOrNull { it.id == id } as? GraphPointObject ?: return
        // 空心点不许拖（正常入口在 [draggablePointAt] 已经挡住了，这里再守一道）
        if (!o.isDraggable) return
        // 一次拖动 = 一步撤销：tag 让"同一次拖动的每一帧"不会各压一步
        // （和 [setObjectColor] 同一条规矩）。
        pushUndo("移动 ${o.name}", tag = "pointdrag:$id")
        draggingPointId = id
        // 拖点期间走 FAST 采样档：由这个点算出来的圆/圆锥曲线每动一步都要重采样，
        // 高精度下采样跟不上手指（用户要求"点拖到哪就动到哪"）。
        interacting = true
        dragGrabDx = o.point.x - viewport.toWorldX(px.toDouble())
        dragGrabDy = o.point.y - viewport.toWorldY(py.toDouble())
        // 吸附来的点（曲线上的点 / 交点 / 零点 / 极值点）拖动时**一直留在曲线上**：
        // 用户要求"确保这个点一定在之前描的曲线上"。自由描的点（POINT）随便拖。
        dragConstrained = o.kind != SnapKind.POINT
        dragLock = if (dragConstrained) dragLockOf(o) else null
        if (dragConstrained && dragLock == null) dragConstrained = false
    }

    /**
     * 这个点拖动时该锁在哪条对象上。
     *
     * 先看它的 [GraphPointObject.attachment]（描点落下来时就记好了）；
     * 存档不存依附关系，重开文件之后是 null —— 那就按"点自己现在压在哪条线上"现认一次。
     */
    private fun dragLockOf(o: GraphPointObject): DragLock? {
        val att = o.attachment
        if (att is PointAttachment.OnCurve && functions.any { it.id == att.functionId && it.visible }) {
            return DragLock.OnCurve(att.functionId)
        }
        if (att is PointAttachment.OnLine && lineObjectById(att.lineId) != null) {
            return DragLock.OnLine(att.lineId)
        }
        val vp = plot.viewport ?: return null
        val hit = nearestOnAnyCurve(
            vp.toScreenX(o.point.x),
            vp.toScreenY(o.point.y),
            null,
            snapRadiusPx() * 2.0
        ) ?: return null
        hit.functionId?.let { return DragLock.OnCurve(it) }
        hit.lineId?.let { return DragLock.OnLine(it) }
        return null
    }

    /** 按 id 找一条可见的自绘线（画在图上的是这个对象的 [GraphLineObject.line]）。 */
    private fun lineObjectById(id: Long): GraphLineObject? =
        objects.firstOrNull { it.id == id } as? GraphLineObject

    /**
     * 手指附近、**指定那条自绘线**上的最近点（线段夹 0~1、射线夹 ≥0）。
     *
     * 和 [nearestOnAnyCurve] 里那段投影是同一套算法，区别只在这里不会看到别的线。
     * 用**当前视口**（不是采样视口）：线的坐标是世界坐标，换算只跟屏幕有关。
     */
    private fun nearestOnLine(lineId: Long, px: Float, py: Float, limitPx: Double): CurveHit? {
        val o = lineObjectById(lineId)?.takeIf { it.visible && !it.line.isDegenerate } ?: return null
        val finger = GPoint(viewport.toWorldX(px.toDouble()), viewport.toWorldY(py.toDouble()))
        val raw = projectionParam(finger, o.line)
        val t = when (o.line.kind) {
            GLineKind.LINE -> raw
            GLineKind.SEGMENT -> raw.coerceIn(0.0, 1.0)
            GLineKind.RAY -> raw.coerceAtLeast(0.0)
        }
        val point = o.line.pointAt(t)
        val dist = screenDistance(viewport, px.toDouble(), py.toDouble(), point)
        return if (dist <= limitPx) CurveHit(point, lineId = lineId) else null
    }

    /** 跟手：把正在拖的点挪到手指对应的位置（受约束的点只挪到最近的曲线/直线上）。 */
    fun movePointDrag(px: Float, py: Float) {
        val id = draggingPointId ?: return
        val o = objects.firstOrNull { it.id == id } as? GraphPointObject ?: return
        var next = o.point
        var attachment = o.attachment
        if (dragConstrained) {
            // 约束点：只在这条点**原来吸上的那一个对象**上找最近点（见 [dragLock]）。
            // 半径放宽到 3 倍吸附半径 —— 拖动时手指不会一直精准地压在线上，
            // 用吸附那一档会觉得"点跟不上手"。
            val hit = when (val lock = dragLock) {
                is DragLock.OnCurve -> nearestOnAnyCurve(px, py, lock.functionId, snapRadiusPx() * 3.0)
                is DragLock.OnLine -> nearestOnLine(lock.lineId, px, py, snapRadiusPx() * 3.0)
                null -> null
            }
            if (hit != null) {
                next = hit.point
                // 依附关系保持在同一个对象上（用户要的正是"不许跳到别的曲线"）。
                attachment = attachmentOf(hit)
            }
        } else {
            next = GPoint(
                viewport.toWorldX(px.toDouble()) + dragGrabDx,
                viewport.toWorldY(py.toDouble()) + dragGrabDy
            )
        }
        if (next == o.point && attachment == o.attachment) return
        // 手动拖一个**派生点**（中点 / 对称点）＝ 用户自己接管它：清掉 recipe，
        // 否则 refreshGeometry() 每帧都会把它拉回"算出来的"位置，看起来就是"拖不动"。
        updateObject(o.copy(point = next, attachment = attachment, recipe = null))
        // 这个点被拖走 → 由它算出来的线/度量（以及贴在这些线上的点）全部重算
        refreshGeometry()
    }

    /** 松手（或手势被取消）：拖点结束，对象已经落在最后一次 [movePointDrag] 的位置上。 */
    fun endPointDrag() {
        draggingPointId = null
        dragConstrained = false
        // 松手 → 回到高精度：采样 key 里的 `quality` 变了，会补一张 NORMAL 档的图。
        interacting = false
    }

    // ---- 4c. 跟着动：派生对象 / 吸附点跟着输入走 ----

    /**
     * 一次"吸到曲线上"的结果转成 [PointAttachment]（见 `GraphObjects.kt` 那个类型）。
     *
     * 只有**显函数**记曲线，理由：重新贴回去的做法是"留着横坐标、重新取纵坐标"
     * （`y = f(x)`），而极坐标 / 参数方程 / 隐函数的参数不是 x，拿 x 硬代会贴到
     * 完全不相干的点上 —— 宁可不跟随，也不画一个"看起来跟着、其实是瞎算"的点。
     */
    private fun attachmentOf(hit: CurveHit?): PointAttachment? {
        if (hit == null) return null
        hit.lineId?.let { id ->
            val line = (objects.firstOrNull { it.id == id } as? GraphLineObject)?.line ?: return null
            return PointAttachment.OnLine(id, projectionParam(hit.point, line))
        }
        val fnId = hit.functionId ?: return null
        val fn = functions.firstOrNull { it.id == fnId } ?: return null
        if (fn.mode != PlotMode.EXPLICIT) return null
        return PointAttachment.OnCurve(fnId, hit.point.x)
    }

    /**
     * "这次描出来的点吸在谁身上"。
     *
     *   * 吸到**曲线本身**（[SnapKind.OTHER]）→ 记一条曲线/线的参数；
     *   * 吸到**交点**（[SnapKind.INTERSECTION]）→ 记"哪两条对象"，之后跟着交点走
     *     （用户原话："描出来的点是空心的，随着曲线交点位置的变动而变动"）。
     *
     * 交点这一步按**世界坐标**把落点认回 [intersectionCandidates] 里的那一行：
     * 吸附选中的就是表里的点，所以距离是 0（容差 2 像素只是兜底）。
     */
    private fun attachmentAt(
        px: Float,
        py: Float,
        kind: SnapKind?,
        point: GPoint? = null
    ): PointAttachment? = when (kind) {
        SnapKind.OTHER -> attachmentOf(nearestOnAnyCurve(px, py, null))
        SnapKind.INTERSECTION -> point?.let { intersectionAttachmentAt(it) }
        else -> null
    }

    /** 落点 [point] 是哪一个交点：在候选表里找最近的那一行（同坐标，距离应为 0）。 */
    private fun intersectionAttachmentAt(point: GPoint): PointAttachment.OnIntersection? {
        // 采样视口可能还没有（没有函数曲线），但世界坐标 → 像素的换算用当前视口一样准。
        val vp = plot.viewport ?: viewport
        val eps = worldEpsForPixels(vp, 2.0).let { if (it > 0.0) it else 1e-9 }
        var best: PointAttachment.OnIntersection? = null
        var bestD = Double.MAX_VALUE
        for ((p, att) in intersectionCandidates()) {
            val d = hypot(p.x - point.x, p.y - point.y)
            if (d <= eps && d < bestD) {
                bestD = d
                best = att
            }
        }
        return best
    }

    /** [refreshGeometry] 正在跑：挡住递归（重算过程中不会再来一轮）。 */
    private var refreshingGeometry = false

    /**
     * 把所有"跟着点走"的东西对齐到输入现在的样子（用户要求：
     * "拖动描点时跟着这个点画出的图形要动；滑动条控制的点动了，跟着它的对象也要动"）。
     *
     * 两件事、跑两遍：
     *  1. [refreshAttachedPoints]：吸附在曲线/直线上的点重新贴回曲线上
     *     （滑动条改了曲线、或线被拖动之后，点会跟着移动）；
     *  2. [rebuildDerivedObjects]：由点/线算出来的线段、垂线、度量、中点、对称点
     *     按 [ObjectRecipe] 重算。
     *
     * 为什么是两遍：第 2 步可能刚把"某个点贴着的那条线"挪动（线段 AB 的 A 被拖了），
     * 于是贴在这条线上的点也得再贴一次、引用它的度量再算一次。固定两遍足够收敛 ——
     * 这个依赖图里最长的一条链是"点 → 线 → 线上的点 → 度量"。
     */
    private fun refreshGeometry() {
        if (refreshingGeometry) return
        refreshingGeometry = true
        try {
            refreshAttachedPoints()
            rebuildDerivedObjects()
            // 圆 / 圆弧 / 椭圆 / 双曲线 / 抛物线 / 圆锥曲线是**函数**（隐函数 / 参数方程），
            // 跟随的做法是把表达式按点重算一遍（见 [rebuildCurveEquations]）。
            rebuildCurveEquations()
            refreshAttachedPoints()
            rebuildDerivedObjects()
        } finally {
            refreshingGeometry = false
        }
    }

    /** 吸附在曲线/直线上的点，重新贴到它跟着的那条曲线上（算不出来就留在原地）。 */
    private fun refreshAttachedPoints() {
        var changed = false
        val next = objects.map { o ->
            if (o !is GraphPointObject) return@map o
            val p = attachedPosition(o) ?: return@map o
            if (p == o.point) o else {
                changed = true
                o.copy(point = p)
            }
        }
        if (changed) objects = next
    }

    /**
     * 一个吸附点按它现在跟着的曲线/线**应该**在哪；null = 这条依赖失效了（保持原地）。
     *
     * 传入整个点（而不是只有 attachment）是因为交点要靠**点当前的位置**辨认：
     * 一对曲线可能有好几个交点，重算时取离它最近的那个 —— 连续变形下就是同一个。
     */
    private fun attachedPosition(o: GraphPointObject): GPoint? {
        val attachment = o.attachment ?: return null
        when (attachment) {

            is PointAttachment.OnCurve -> {
                val fn = functions.firstOrNull { it.id == attachment.functionId } ?: return null
                if (fn.mode != PlotMode.EXPLICIT) return null
                val f = compileCurve(fn) ?: return null
                val y = runCatching { f(attachment.x) }.getOrDefault(Double.NaN)
                return if (y.isFinite()) GPoint(attachment.x, y) else null
            }

            is PointAttachment.OnLine -> {
                val line = (objects.firstOrNull { it.id == attachment.lineId } as? GraphLineObject)
                    ?.line ?: return null
                if (line.isDegenerate) return null
                val t = when (line.kind) {
                    GLineKind.LINE -> attachment.t
                    GLineKind.SEGMENT -> attachment.t.coerceIn(0.0, 1.0)
                    GLineKind.RAY -> attachment.t.coerceAtLeast(0.0)
                }
                val p = line.pointAt(t)
                return if (p.isFinite) p else null
            }

            is PointAttachment.OnIntersection -> {
                val pts = intersectionPointsOf(attachment) ?: return null
                // 一对曲线可能有多个交点：取离这个点**现在**最近的那个（连续变形下是同一个）。
                return pts.minByOrNull { hypot(it.x - o.point.x, it.y - o.point.y) }
            }
        }
    }

    /**
     * 一个交点依附关系**现在**的全部交点（世界坐标）；依赖失效（对象被删、采样没了）返回 `null`。
     *
     * 和 [intersectionCandidates] 的三类情形一一对应，区别只在这里不设数量上限的
     * 筛选（上限在候选表那边控制，因为那份表每次手指移动都会算）。
     */
    private fun intersectionPointsOf(att: PointAttachment.OnIntersection): List<GPoint>? {
        val vp = plot.viewport?.takeIf {
            it.widthPx > 1f && it.unitsPerPixel > 0.0 && it.unitsPerPixel.isFinite()
        }
        val out = ArrayList<GPoint>()
        val aFn = att.aFunction
        val bFn = att.bFunction

        // 1) 函数 ↔ 函数
        if (aFn != null && bFn != null) {
            if (vp == null) return null
            for (a in plot.curves) {
                if (a.functionId != aFn) continue
                for (b in plot.curves) {
                    if (b.functionId != bFn) continue
                    out += runCatching { intersections(a, b, vp) }.getOrDefault(emptyList())
                }
            }
            return out
        }

        // 2) 函数 ↔ 自绘线（哪个字段在哪一端不影响结果）
        val fnId = aFn ?: bFn
        val lineId = att.aLine ?: att.bLine
        if (fnId != null && lineId != null) {
            if (vp == null) return null
            val line = (objects.firstOrNull { it.id == lineId } as? GraphLineObject)?.line
                ?: return null
            if (line.isDegenerate) return null
            for (curve in plot.curves) {
                if (curve.functionId != fnId) continue
                out += runCatching { intersections(curve, line, vp) }.getOrDefault(emptyList())
            }
            return out
        }

        // 3) 自绘线 ↔ 自绘线
        val aLine = att.aLine
        val bLine = att.bLine
        if (aLine != null && bLine != null) {
            val la = (objects.firstOrNull { it.id == aLine } as? GraphLineObject)?.line
                ?: return null
            val lb = (objects.firstOrNull { it.id == bLine } as? GraphLineObject)?.line
                ?: return null
            intersectionOfLines(la, lb)?.let { out += it }
            return out
        }
        return null
    }

    /**
     * 按 [ObjectRecipe] 重算所有派生对象。
     *
     * 解析是**递归**的（先解析依赖、再算自己），所以"垂线 → 参考线段 → 线段的两个点"
     * 这种链会从最底下往上一次性算对；不会出现"先算了垂线、后移动了线段"的
     * 一帧错位。成环（用户把对象绕成互相依赖，理论上做得到）时那一圈**原样保留**，
     * 绝不递归死。
     *
     * 只有整张表真的变了才写回 —— 数值稳定之后这一步是幂等的，
     * 不会因为"每帧都调用一次"而白白触发重组/重采样。
     */
    private fun rebuildDerivedObjects() {
        val base = objects
        val byId = base.associateBy { it.id }
        val resolved = HashMap<Long, GraphObject>(base.size)
        val visiting = HashSet<Long>()

        fun resolve(id: Long): GraphObject? {
            resolved[id]?.let { return it }
            val o = byId[id] ?: return null
            if (!visiting.add(id)) return o
            val out = recomputeObject(
                o,
                { pid -> (resolve(pid) as? GraphPointObject)?.point.takeIf { it?.isFinite == true } },
                { ref -> lineRefValue(ref) { lid -> (resolve(lid) as? GraphLineObject)?.line } }
            ) ?: o
            visiting.remove(id)
            resolved[id] = out
            return out
        }

        val next = base.map { o -> resolve(o.id) ?: o }
        if (next != base) objects = next
    }

    /** [ObjectRecipe.LineRef] 取出当前的几何直线（对象引用走 [lookup]，常量直接用）。 */
    private fun lineRefValue(
        ref: ObjectRecipe.LineRef,
        lookup: (Long) -> GLine?
    ): GLine? = when (ref) {
        is ObjectRecipe.LineRef.Obj -> lookup(ref.id)?.takeIf { !it.isDegenerate }
        is ObjectRecipe.LineRef.Fixed -> ref.line.takeIf { !it.isDegenerate }
    }

    // ---- 4d. 曲线（函数）跟着点动：圆 / 圆弧 / 椭圆 / 双曲线 / 抛物线 / 圆锥曲线 ----

    /**
     * 一条"由点算出来的曲线"的记账：
     * [recipe] = 怎么算，[src1]/[src2] = 上一次真正写进去的表达式。
     *
     * 存下后者的理由是**尊重用户的编辑**：用户在编辑器里把这圆的表达式改成了别的，
     * 它的 `src1` 就不再等于这里记的那一份 → 立刻解除跟随（[rebuildCurveEquations]）。
     */
    private data class CurveRecipeEntry(
        val recipe: ObjectRecipe,
        val src1: String,
        val src2: String
    )

    /** 曲线构造关系：**函数 id** → 记账。只在内存里，文件格式不存。 */
    private val curveRecipes = HashMap<Long, CurveRecipeEntry>()

    /** 记下一条曲线的构造关系（[addToolCurve] 建完曲线就调它）。 */
    private fun trackCurve(id: Long, recipe: ObjectRecipe?, src1: String, src2: String) {
        if (recipe == null) return
        curveRecipes[id] = CurveRecipeEntry(recipe, src1, src2)
    }

    /** 按 recipe 现在应该是什么表达式（参数方程那条还要带量程）。 */
    private data class CurveEquation(
        val src1: String,
        val src2: String = "",
        val tMin: Double? = null,
        val tMax: Double? = null
    )

    /**
     * 按 [recipe] 用**当前**的点/线重算表达式；null = 依赖缺了 / 算不出来（保持原样）。
     *
     * 和 [recomputeObject] 同一套哲学：只按 id 现算，绝不存副本；依赖被删了就不动。
     */
    private fun curveEquationOf(recipe: ObjectRecipe): CurveEquation? {
        fun p(id: Long): GPoint? =
            (objects.firstOrNull { it.id == id } as? GraphPointObject)
                ?.point?.takeIf { it.isFinite }

        fun l(ref: ObjectRecipe.LineRef): GLine? =
            lineRefValue(ref) { lid -> (objects.firstOrNull { it.id == lid } as? GraphLineObject)?.line }

        return when (recipe) {
            is ObjectRecipe.CircleCenterPoint -> {
                val c = p(recipe.center) ?: return null
                val t = p(recipe.through) ?: return null
                circleEquation(c, distance(c, t))?.let { CurveEquation(it) }
            }
            is ObjectRecipe.CircleCenterRadius -> {
                val c = p(recipe.center) ?: return null
                val a = p(recipe.a) ?: return null
                val b = p(recipe.b) ?: return null
                circleEquation(c, distance(a, b))?.let { CurveEquation(it) }
            }
            is ObjectRecipe.CircleCenterRadiusLine -> {
                val c = p(recipe.center) ?: return null
                val line = l(recipe.line) ?: return null
                circleEquation(c, line.length)?.let { CurveEquation(it) }
            }
            is ObjectRecipe.CircleCenterRadiusExpr -> {
                val c = p(recipe.center) ?: return null
                CurveEquation(circleRadiusExpressionEquation(c, recipe.radiusSource))
            }
            is ObjectRecipe.CircleThreePoints -> {
                val a = p(recipe.a) ?: return null
                val b = p(recipe.b) ?: return null
                val c = p(recipe.c) ?: return null
                val found = circleThroughThreePoints(a, b, c) ?: return null
                circleEquation(found.first, found.second)?.let { CurveEquation(it) }
            }
            is ObjectRecipe.ArcOfPoints -> {
                val c = p(recipe.center) ?: return null
                val s = p(recipe.start) ?: return null
                val e = p(recipe.end) ?: return null
                val spec = arcOf(c, s, e) ?: return null
                CurveEquation(spec.xs, spec.ys, spec.tMin, spec.tMax)
            }
            is ObjectRecipe.EllipseOf -> {
                val f1 = p(recipe.f1) ?: return null
                val f2 = p(recipe.f2) ?: return null
                val on = p(recipe.on) ?: return null
                ellipseEquation(f1, f2, on)?.let { CurveEquation(it) }
            }
            is ObjectRecipe.HyperbolaOf -> {
                val f1 = p(recipe.f1) ?: return null
                val f2 = p(recipe.f2) ?: return null
                val on = p(recipe.on) ?: return null
                hyperbolaEquation(f1, f2, on)?.let { CurveEquation(it) }
            }
            is ObjectRecipe.ParabolaOf -> {
                val focus = p(recipe.focus) ?: return null
                val directrix = l(recipe.directrix) ?: return null
                parabolaEquation(focus, directrix)?.let { CurveEquation(it) }
            }
            is ObjectRecipe.ConicFivePoints -> {
                val pts = recipe.points.map { p(it) ?: return null }
                conicThroughPoints(pts)?.let { CurveEquation(it) }
            }
            else -> null
        }
    }

    /**
     * 把所有记了账的曲线按当前的点重算一遍（表达式真变了才写回）。
     *
     * 整个刷新链现在是：拖点 / 滑动条 → [refreshGeometry] → [rebuildDerivedObjects]
     * → 这里 → 采样（函数的 `src1` 变了，采样 key 变了）→ [applyPlot] → 再来一遍。
     * 第二遍时表达式已经稳定，这里什么也不写，链就停了。
     */
    private fun rebuildCurveEquations() {
        if (curveRecipes.isEmpty()) return
        val byId = functions.associateBy { it.id }
        val updates = ArrayList<Pair<Long, CurveEquation>>()
        val dropped = ArrayList<Long>()
        for ((fid, entry) in curveRecipes) {
            val fn = byId[fid] ?: continue
            // 用户自己改过这条表达式（不再是上次我们写的那一份）→ 解除跟随
            if (fn.src1 != entry.src1 || fn.src2 != entry.src2) {
                dropped += fid
                continue
            }
            val eq = curveEquationOf(entry.recipe) ?: continue
            val sameRange = (eq.tMin == null || eq.tMin == fn.tMin) &&
                (eq.tMax == null || eq.tMax == fn.tMax)
            if (eq.src1 == fn.src1 && eq.src2 == fn.src2 && sameRange) continue
            updates += fid to eq
        }
        dropped.forEach { curveRecipes.remove(it) }
        if (updates.isEmpty()) return
        for ((fid, eq) in updates) {
            val fn = byId[fid] ?: continue
            updateFunctionNoUndo(
                fn.copy(
                    src1 = eq.src1,
                    src2 = eq.src2,
                    tMin = eq.tMin ?: fn.tMin,
                    tMax = eq.tMax ?: fn.tMax
                )
            )
            curveRecipes[fid]?.let { curveRecipes[fid] = it.copy(src1 = eq.src1, src2 = eq.src2) }
        }
    }

    /**
     * 一个对象按它自己的 recipe **现在应该**是什么样；null = 没有 recipe / 依赖缺了 /
     * 算出来退化（这三种都保持原样，绝不写一个 NaN 进去）。
     */
    private fun recomputeObject(
        o: GraphObject,
        pointOf: (Long) -> GPoint?,
        lineOf: (ObjectRecipe.LineRef) -> GLine?
    ): GraphObject? {
        return when (o) {
        is GraphPointObject -> when (val r = o.recipe) {
            is ObjectRecipe.MidpointOf -> {
                val a = pointOf(r.a)
                val b = pointOf(r.b)
                if (a == null || b == null) null else o.copy(point = midpoint(a, b))
            }

            is ObjectRecipe.MirrorPointOf -> {
                val src = pointOf(r.source) ?: return null
                val p = when (val m = r.mirror) {
                    is ObjectRecipe.MirrorPointOf.Mirror.Axis -> lineOf(m.line)?.let { reflect(src, it) }
                    is ObjectRecipe.MirrorPointOf.Mirror.Center -> pointOf(m.point)?.let {
                        reflectThrough(src, it)
                    }
                }
                if (p != null && p.isFinite) o.copy(point = p) else null
            }

            else -> null
        }

        is GraphLineObject -> when (val r = o.recipe) {
            is ObjectRecipe.LineFromPoints -> {
                val a = pointOf(r.a)
                val b = pointOf(r.b)
                if (a == null || b == null) null
                else o.copy(line = GLine(a, b, r.kind)).takeIf { !it.line.isDegenerate }
            }

            is ObjectRecipe.PerpendicularBisectorOf -> {
                val a = pointOf(r.a)
                val b = pointOf(r.b)
                if (a == null || b == null) null
                else o.copy(line = perpendicularBisector(a, b)).takeIf { !it.line.isDegenerate }
            }

            is ObjectRecipe.AngleBisectorOf -> {
                val a = pointOf(r.a)
                val b = pointOf(r.b)
                val c = pointOf(r.c)
                if (a == null || b == null || c == null) null
                else angleBisector(a, b, c)
                    ?.takeIf { !it.isDegenerate }
                    ?.let { o.copy(line = it) }
            }

            is ObjectRecipe.PerpendicularTo -> {
                val p = pointOf(r.through) ?: return null
                val src = lineOf(r.source) ?: return null
                o.copy(line = perpendicular(src, p)).takeIf { !it.line.isDegenerate }
            }

            is ObjectRecipe.ParallelTo -> {
                val p = pointOf(r.through) ?: return null
                val src = lineOf(r.source) ?: return null
                o.copy(line = parallel(src, p)).takeIf { !it.line.isDegenerate }
            }

            // 切线：切点（可能刚被滑动条挪过）所在的横坐标 + 当前的函数表达式
            is ObjectRecipe.TangentAtPoint -> {
                val p = pointOf(r.point) ?: return null
                val fn = functions.firstOrNull { it.id == r.function } ?: return null
                if (fn.mode != PlotMode.EXPLICIT) return null
                val f = compileCurve(fn) ?: return null
                tangentLine(f, p.x)?.let { o.copy(line = it) }
            }

            else -> null
        }

        is GraphMeasureObject -> when (val r = o.recipe) {
            is ObjectRecipe.DistanceBetween -> {
                val a = pointOf(r.a)
                val b = pointOf(r.b)
                if (a == null || b == null) null
                else o.copy(points = listOf(a, b), value = distance(a, b))
            }

            is ObjectRecipe.AreaOfPoints -> {
                val pts = r.points.map { pointOf(it) }
                if (pts.any { it == null }) null
                else {
                    val ring = pts.filterNotNull()
                    o.copy(points = ring, value = polygonArea(ring))
                }
            }

            is ObjectRecipe.AngleBetweenLines -> {
                val l1 = lineOf(r.first) ?: return null
                val l2 = lineOf(r.second) ?: return null
                angleFromLines(l1, l2)?.let { a ->
                    o.copy(points = listOf(a.arm1, a.vertex, a.arm2), value = a.degrees)
                }
            }

            else -> null
        }
        }
    }

    // ---- 5. 对象命中测试（变换类工具要"点一个对象"） ----

    /** 命中阈值：和点曲线一样取屏宽的 [TAP_SLOP_FRACTION]，至少 [MIN_TAP_SLOP_PX] 像素。 */
    private fun hitSlopPx(): Float =
        (viewport.widthPx * TAP_SLOP_FRACTION).coerceAtLeast(MIN_TAP_SLOP_PX)

    /** 点击处命中的**几何线**对象（直线/线段/射线）。命中的是 id。 */
    fun lineObjectAt(px: Float, py: Float): GraphLineObject? {
        val slop = hitSlopPx()
        var best: GraphLineObject? = null
        var bestD = Double.POSITIVE_INFINITY
        for (o in objects) {
            if (o !is GraphLineObject || !o.visible || o.role == FRAGMENT_ROLE) continue
            if (o.line.isDegenerate) continue
            val d = distanceToShape(
                GPoint(viewport.toWorldX(px.toDouble()), viewport.toWorldY(py.toDouble())),
                o.line
            )
            // 世界距离换成屏幕像素再比阈值（手指精度是像素语义的）
            val dPx = d / viewport.unitsPerPixel
            if (dPx <= slop && dPx < bestD) {
                bestD = dPx
                best = o
            }
        }
        return best
    }

    /** 点击处命中的曲线 id（没吸到几何点、也没点到线时才用它）。 */
    fun curveAt(px: Float, py: Float): Long? =
        hitTestCurves(plot, px, py, hitSlopPx())

    /**
     * 点击处命中的**几何对象**（供画布上的"点对象看属性"用，见 [onTapCanvas]）。
     *
     * ## 顺序 = 用户给的优先级表（**只有三类**）
     *
     * 用户原话（更正后）："如果有多个对象重合，优先显示点，再是线段，再是函数曲线，
     * 角度、面积和距离在图像上面能显示，不用管。"
     *
     * | 优先级 | 谁 | 在哪儿判 |
     * |---|---|---|
     * | 1 | **点**（[GraphPointObject]） | 本函数第一段（[pointObjectAt]） |
     * | 2 | **线段 / 直线 / 射线**（[GraphLineObject]，同一种对象） | 本函数第二段（[lineObjectAt]） |
     * | 3 | **函数曲线** | **不在这里**：[onTapCanvas] 用现成的 [hitTestCurves] 判 |
     *
     * **高优先级的一类只要有命中，就不再往下看**（不是把三类放一起比距离）——
     * 这样"点压在线上"「点落在曲线穿过的地方」的结果才是稳定的。
     *
     * ## 角度 / 面积 / 距离**完全不参与**（用户明确划出去的那三个）
     *
     * 用户说它们"在图像上面能显示，不用管"，所以这里**一个都不判**：
     *  * 画布上点它们的**图形**（扇形 / 多边形 / 尺寸线）**不会弹属性卡**，
     *    也**不会吃掉点击** —— 那一下会**穿透**到下面真正在优先级里的三类去
     *    （比如多边形里穿过的线段、尺寸线压着的点），三样都没有就当作点了空白（收起卡片）。
     *    这是有意的选择，理由：*"穿透"不会让人有"点不动图"的感觉* ——
     *    在这之前面积是"点进去就选中"，一块大三角形会把里面穿过的曲线和线段全挡住，
     *    用户想选那条线只能绕到多边形外面点，那才叫"点不动"。
     *  * 它们的**两个开关（图形显示 / 数值显示）**照旧由**列表行**（短按行 → 属性页）
     *    和图像界面上的 `123` / 色点负责 —— 能力一点没少，只是不在画布上给它们做卡。
     */
    fun objectAt(px: Float, py: Float): GraphObject? {
        // ---- 1) 点（优先级最高的那一类）----
        val point = pointObjectAt(px, py)
        if (point != null) return point

        // ---- 2) 线段 / 直线 / 射线：现成的实现（它自带容差与"跳过碎片"的规则）----
        return lineObjectAt(px, py)
    }

    /**
     * 优先级 1：最近的一个**可见点对象**；没命中返回 null。
     *
     * 阈值用 [TAP_POINT_FRACTION]（≈屏宽 6%）而不是"点的绘制半径"（`OBJECT_POINT_R`
     * 只有屏宽 1.7% ≈ 8px）：8px 在手表上根本点不中 —— 手指落点误差本来就有一二十像素。
     * 这个数**和吸附半径（[SNAP_RADIUS_FRACTION]）数值相同**、但是**两个独立的常量**：
     * "描点时吸到哪个点"和"点在哪个对象上"是两件事，将来调其中一个不该悄悄改掉另一个。
     *
     * "最近"的口径：比较**屏幕像素**距离（点的半径在屏幕上是个定值，用像素最直观），
     * 两个点几乎重合时选离手指更近的那个。
     */
    private fun pointObjectAt(px: Float, py: Float): GraphPointObject? {
        val r = tapPointRadiusPx()
        var best: GraphPointObject? = null
        var bestD = Double.POSITIVE_INFINITY
        for (o in objects) {
            if (o !is GraphPointObject || !o.visible) continue
            val d = screenDistance(viewport, px.toDouble(), py.toDouble(), o.point)
            if (d <= r && d < bestD) {
                bestD = d
                best = o
            }
        }
        return best
    }

    /** 画布上"点中一个点对象"的半径（像素）：[TAP_POINT_FRACTION] 占屏宽，下限 [MIN_TAP_POINT_PX]。 */
    private fun tapPointRadiusPx(): Double =
        (viewport.widthPx * TAP_POINT_FRACTION).coerceAtLeast(MIN_TAP_POINT_PX).toDouble()

    // ---- 6. 一次工具的点击：翻译 → 喂状态机 → 出结果 ----

    /**
     * 图像界面上的一次点击交给当前工具处理。
     *
     * @return 给用户看的提示（null = 这一下推进了状态、而且不用说话）。
     *
     * **这个函数不允许"什么都不做"**：任何一种点击（点到空白、点到已经选过的点、
     * 点到曲线、点到被隐藏的点、工具已经选够了）都必须落到某一条分支上 ——
     * 要么推进状态、要么返回一句说明。用户报的"点第二下没反应、然后什么都
     * 点不动"就是这条规则被破坏的结果：当时第二下走进了
     * `val expected = toolExpected ?: return null` 这条死路（因为距离工具在
     * 第一个点之后就被误判成"选够了"，`expected` 变成 null），静态分支静悄悄地
     * 什么都不做，用户只能看到界面死了。
     *
     * 取点规则（**所有取点类工具统一**，不是逐个定制 —— 用户只在"角度"里
     * 明说了"如果点击的位置附近没有点，就调用描点方法描点并选取"，
     * 但这条规则对距离/面积/作图/直线同样自然，逐个定制只会让行为不一致）：
     *
     * | 当前一步要选 | 手指附近有点 | 附近没点 |
     * |---|---|---|
     * | 点 | 选那个点 | **现描一个点再选它** |
     * | 对象（轴对称/中心对称第一步） | 点 → 直线 → 曲线，逐个试 | 现描一个点当对象 |
     * | 曲线 | 点到曲线才算，点不到就明确说一句（工具不退） | — |
     * | 直线 | 点到几何直线才算，点不到也明确说一句 | — |
     */
    fun toolTap(px: Float, py: Float): String? {
        val tool = activeTool
        // 移动：点击是"看属性 / 选中 / 收起卡片"，**由 onTapCanvas 负责**（那里还会命中原生对象）。
        // ⚠️ 这就是"工具 / 移动"两条语义的分界线：**只有移动模式**会把点击解释成
        // "点对象看属性"；工具模式下每一次点击都必须喂给状态机（要么推进、要么给一句话），
        // 绝不能顺带去弹卡片 —— 否则用户正在取点，屏幕上会突然浮出一张属性卡挡住画布。
        if (tool == FunctionTool.MOVE) return null

        // 描点：按下的那一刻就已经在 pending 里了（见 beginPendingPoint），
        // 这里不用做任何事 —— 真正的"放下"在松手时由 commitPendingPoint 完成。
        if (tool == FunctionTool.PLOT_POINT) return null

        val flow = toolFlow ?: ToolFlow(tool).also {
            toolFlow = it
            toolFlowVersion++
        }

        val spec = flow.stepAt(flow.stepIndex)
            ?: run {
                // 理论上到不了这里（选够的那一刻 acceptSelection 就出结果并重启了）。
                // 万一到了，也必须给用户一条出路：清空重来 + 告诉他可以接着点。
                restartToolFlow(tool)
                return "${tool.label}：这一轮已经出过结果了，接着点就是新的一轮"
            }
        // 「圆心·半径」的第 2 步（stepIndex == 1）还认一条**线段**：用它的长度当半径，
        // 这一轮直接出圆。状态机那三步都只声明了"点"，所以这条捷径在这里特判 ——
        // 和「角度」第 0 步同一个思路（那一步在状态机里就是双选的 altBranch）。
        if (tool == FunctionTool.CIRCLE_CENTER_RADIUS && flow.stepIndex == 1) {
            val center = flow.points.firstOrNull()
            val segment = lineObjectAt(px, py)?.takeIf { it.line.kind == GLineKind.SEGMENT }
            if (center != null && segment != null) {
                val eq = circleEquation(center, segment.line.length)
                // 圆心那个点的 id：这一步的选择里第一个点就是它
                val centerId = pickedPointIds(flow).firstOrNull()
                restartToolFlow(tool)
                return if (eq == null) {
                    "圆心·半径：这条线段长度是 0，圆画不出来（已重新开始）"
                } else {
                    "已画出圆 ${addToolCurve(
                        PlotMode.IMPLICIT, eq,
                        recipe = centerId?.let {
                            ObjectRecipe.CircleCenterRadiusLine(
                                it, ObjectRecipe.LineRef.Obj(segment.id)
                            )
                        }
                    )}：$eq"
                }
            }
        }

        // ⚠️ 取的是 **flow 的** 那一步（不是 `tool.stepAt`）：角度第 0 步同时认
        // "线段"和"点"两种选择，选了哪一种决定后面还要选什么（见 ToolFlow.altBranch）。
        return when {
            spec.anyObject -> tapObject(flow, tool, px, py)
            // 两种都认的那一步（现在只有「角度」）：先看点、再看线、都没有就现描一个点
            spec.altKind == SelectionKind.POINT && spec.kind == SelectionKind.LINE ->
                tapEdgeOrPoint(flow, tool, px, py)

            spec.kind == SelectionKind.POINT -> tapPoint(flow, tool, px, py)
            spec.kind == SelectionKind.CURVE -> {
                val id = curveAt(px, py)
                    ?: return "${tool.label}：这一步要${spec.requirement}（点不到就再点一次）"
                acceptSelection(tool, ToolSelection.CurveSel(id))
            }
            else -> {
                val lo = lineObjectAt(px, py)
                if (lo != null) return acceptSelection(tool, ToolSelection.LineSel(lo.id))
                // 坐标轴也是现成的"直线"（见 axisLineAt）：点不上它的话，用户想
                // "关于 x 轴对称"就得先自己画一条直线，那正是他觉得"点不动"的地方。
                val axis = axisLineAt(px, py)
                    ?: return "${tool.label}：这一步要${spec.requirement}" +
                        "（可以先用「直线/线段」工具画一条，或直接点坐标轴）"
                acceptSelection(tool, ToolSelection.LineSel(AXIS_LINE_ID, inline = axis))
            }
        }
    }

    /**
     * 手指点在**坐标轴**上了吗？点在轴上就返回那条轴（世界坐标下的直线）。
     *
     * 为什么要这条捷径：用户点「轴对称」之后第一件事多半是"关于 x 轴对称"，
     * 而坐标轴**不是**几何对象（没有 [GraphLineObject]、没有对象 id），原先只能
     * 自己先画一条直线来当轴 —— 直接点坐标轴得到的是一句"这一步要选一条直线"，
     * 用户的感觉就是"这个功能点不动"。
     *
     * 只在**设置里画着坐标轴**时才认（`showAxes`）：轴都没画出来却能选中它，
     * 比点不动更让人困惑。两条轴都在容差内时取更近的那一条。
     */
    private fun axisLineAt(px: Float, py: Float): GLine? {
        if (!settings.showAxes) return null
        val slop = hitSlopPx()
        val axisXScreenY = viewport.toScreenY(0.0)   // y = 0 那条横线（x 轴）
        val axisYScreenX = viewport.toScreenX(0.0)   // x = 0 那条竖线（y 轴）
        val dX = kotlin.math.abs(py - axisXScreenY)
        val dY = kotlin.math.abs(px - axisYScreenX)
        return when {
            dX <= slop && dX <= dY ->
                GLine(GPoint(-1.0, 0.0), GPoint(1.0, 0.0), GLineKind.LINE)

            dY <= slop ->
                GLine(GPoint(0.0, -1.0), GPoint(0.0, 1.0), GLineKind.LINE)

            else -> null
        }
    }

    /**
     * 该选一个点的那一步（度量、作图、直线、切线都走这里）。
     *
     * 三条出路：面积收尾 / 选已有（可见）的点 / 现描一个点。
     * 被隐藏的点单独说一句 —— 在那儿再描一个重叠的点，列表里会出现两行几乎
     * 一样的对象，用户根本分不清（而且他的本意多半是"选那个点"）。
     */
    private fun tapPoint(flow: ToolFlow, tool: FunctionTool, px: Float, py: Float): String? {
        // 面积："点回第一个点"收尾。阈值放到吸附半径那么大：用户不用精确压在
        // 第一点上，手指落在它附近就算收尾 —— 而且传的是**第一个点本身**，
        // 状态机那条"同一个点"的判定才成立。
        if (tool.openEnded) {
            val first = flow.points.firstOrNull()
            if (first != null &&
                screenDistance(viewport, px.toDouble(), py.toDouble(), first) <= snapRadiusPx()
            ) {
                return acceptSelection(tool, ToolSelection.PointSel(first, null))
            }
        }

        // 切线：目标必须是某条曲线**上**的点（用户原话"没选择到不退出"）
        val onCurve: Long? = if (tool == FunctionTool.TANGENT) {
            curveAt(px, py)
                ?: return "切线：请点在一条函数或方程曲线上（工具不会退出）"
        } else {
            null
        }

        val hit = snappedHit(px, py, onCurve)
        val point = hit?.point ?: GPoint(
            viewport.toWorldX(px.toDouble()),
            viewport.toWorldY(py.toDouble())
        )

        // 1. 手指附近有**可见的**几何点 → 选它（这是用户真正想选的东西）
        existingPointIdAt(px, py)?.let { id ->
            return acceptSelection(tool, ToolSelection.PointSel(point, onCurve ?: id))
        }

        // 2. 附近只有**被隐藏的**点 → 说清楚，不要悄悄在那儿再描一个
        hiddenPointNear(px, py)?.let { hidden ->
            return "这里有一个被隐藏的点「${hidden.name}」：先点它的色点把它显示出来"
        }

        // 3. 附近什么都没有 → **现描一个点再选它**（这是用户对"角度"明确说过的
        //    规则，其余取点类工具统一照此办理；描出来的点有名字、进函数列表）
        val id = plotPoint(point, hit?.kind ?: SnapKind.POINT, attachmentAt(px, py, hit?.kind, point))
        return acceptSelection(tool, ToolSelection.PointSel(point, onCurve ?: id))
    }

    /**
     * 「角度」的第 0 步：**点线段/直线，或者点角的第一个点**（两条路都从这里分）。
     *
     * 顺序是"**先看点、再看线、最后才现描一个点**"，理由是这三种可能有主次：
     *
     *   1. 手指附近有**可见的点** → 用户多半是在走"连点三个点"那条路（原话里
     *      "调用线段工具点击三个点创建两条线段"），点一个已有的点是最高频的动作；
     *   2. 否则附近有**线段/直线**（含坐标轴）→ 走"点两条线段"那条路；
     *   3. 都没有 → 现描一个点当角的第一个点（和 [tapPoint] 同一条规则：
     *      "没点到就自己造一个"，保证**每一次点击都有明确结果**）。
     *   4. 附近只有**被隐藏**的点时，明确说一句（别在那儿再描一个重合的点）。
     *
     * 为什么线要排在被隐藏的点前面：用户点在一条线段上时，"我要这条边"比
     * "这里有个我看不见的点"更可能是他的本意；而隐藏点那句提示在 [tapPoint] 里
     * （只取点那几步）仍然是第一顺位。
     */
    private fun tapEdgeOrPoint(flow: ToolFlow, tool: FunctionTool, px: Float, py: Float): String? {
        val hit = snappedHit(px, py)
        val point = hit?.point ?: GPoint(
            viewport.toWorldX(px.toDouble()),
            viewport.toWorldY(py.toDouble())
        )

        existingPointIdAt(px, py)?.let { id ->
            return acceptSelection(tool, ToolSelection.PointSel(point, id))
        }
        lineObjectAt(px, py)?.let { lo ->
            return acceptSelection(tool, ToolSelection.LineSel(lo.id))
        }
        // 坐标轴也是一条现成的"直线"（见 axisLineAt）：量"线段和 x 轴的夹角"时
        // 用户不用先自己画一条轴。这一步和第二步（只认直线那一步）口径一致。
        axisLineAt(px, py)?.let { axis ->
            return acceptSelection(tool, ToolSelection.LineSel(AXIS_LINE_ID, inline = axis))
        }
        hiddenPointNear(px, py)?.let { hidden ->
            return "这里有一个被隐藏的点「${hidden.name}」：先点它的色点把它显示出来"
        }

        val id = plotPoint(point, hit?.kind ?: SnapKind.POINT, attachmentAt(px, py, hit?.kind, point))
        return acceptSelection(tool, ToolSelection.PointSel(point, id))
    }

    /**
     * 该"选一个对象"的那一步（轴对称 / 中心对称的第一步）。
     *
     * 用户原话是"先点对象，再点一条直线当对称轴"，而**对象**既可能是点、
     * 也可能是一条函数/方程曲线、还可能是一条已经画出来的直线/线段。
     * 按这个顺序逐个试：可见的点 → 几何直线 → 曲线 → 都没有就现描一个点。
     * 以前这一步只认点，用户点一条曲线会被当成"在这儿描一个新点"，
     * 做出来的对称对象完全不是他要的。
     */
    private fun tapObject(flow: ToolFlow, tool: FunctionTool, px: Float, py: Float): String? {
        val hit = snappedHit(px, py)
        val point = hit?.point ?: GPoint(
            viewport.toWorldX(px.toDouble()),
            viewport.toWorldY(py.toDouble())
        )

        existingPointIdAt(px, py)?.let { id ->
            return acceptSelection(tool, ToolSelection.PointSel(point, id))
        }
        lineObjectAt(px, py)?.let { lo ->
            return acceptSelection(tool, ToolSelection.LineSel(lo.id))
        }
        curveAt(px, py)?.let { id ->
            return acceptSelection(tool, ToolSelection.CurveSel(id))
        }
        hiddenPointNear(px, py)?.let { hidden ->
            return "这里有一个被隐藏的点「${hidden.name}」：先点它的色点把它显示出来"
        }

        // 空白处：现描一个点当对象（明确的结果，绝不"点了没反应"）
        val id = plotPoint(point, hit?.kind ?: SnapKind.POINT, attachmentAt(px, py, hit?.kind, point))
        val msg = acceptSelection(tool, ToolSelection.PointSel(point, id))
        return msg ?: "${tool.label}：空白处描了一个点当对象，接着点${if (tool == FunctionTool.CENTER_SYMMETRY) "对称中心" else "对称轴"}"
    }

    /**
     * 把一次选择喂给状态机，并决定"该出结果了"还是"继续等"。
     *
     * 不合法时**一定**把状态机给的那句话带出去（用户要求"任何一次点击都要有
     * 明确结果"）—— 以前这里 `return null`，界面于是什么都不说，看起来就是死机。
     */
    private fun acceptSelection(tool: FunctionTool, selection: ToolSelection): String? {
        val flow = toolFlow ?: ToolFlow(tool).also { toolFlow = it }
        return when (val result = flow.step(selection)) {
            is ToolStepResult.Rejected -> {
                toolFlowVersion++
                result.message
            }

            is ToolStepResult.Accepted -> {
                toolPicked = toolPicked + selection
                toolFlowVersion++
                if (!flow.isComplete()) null else finishTool(tool)
            }
        }
    }

    /** 附近已有的**可见**几何点（吸附时已经偏好它们，这里只是把 id 找回来挂到选择上）。 */
    private fun existingPointIdAt(px: Float, py: Float): Long? {
        val r = snapRadiusPx()
        var best: Long? = null
        var bestD = Double.POSITIVE_INFINITY
        for (o in objects) {
            if (o !is GraphPointObject || !o.visible) continue
            val d = screenDistance(viewport, px.toDouble(), py.toDouble(), o.point)
            if (d <= r && d < bestD) {
                bestD = d
                best = o.id
            }
        }
        return best
    }

    /** 附近**被隐藏**的几何点（点到它时给一句说明用，见 [tapPoint] 第 2 条出路）。 */
    private fun hiddenPointNear(px: Float, py: Float): GraphPointObject? {
        val r = snapRadiusPx()
        var best: GraphPointObject? = null
        var bestD = Double.POSITIVE_INFINITY
        for (o in objects) {
            if (o !is GraphPointObject || o.visible) continue
            val d = screenDistance(viewport, px.toDouble(), py.toDouble(), o.point)
            if (d <= r && d < bestD) {
                bestD = d
                best = o
            }
        }
        return best
    }

    /** 现描一个点（用户在附近没点，工具需要一个点）：建对象、加进列表、返回它的 id。 */
    private fun plotPoint(point: GPoint, kind: SnapKind, attachment: PointAttachment? = null): Long {
        val id = nextId()
        addObject(
            GraphPointObject(
                id = id,
                name = nextObjectName(ObjectKind.POINT),
                point = point,
                kind = kind,
                colorIndex = POINT_DEFAULT_COLOR_INDEX,
                attachment = attachment
            )
        )
        return id
    }

    /** 对象默认配色：接着已有对象往后排，避免新点/新线和刚画的撞成同一个颜色。 */
    private fun nextObjectColor(): Int = objects.size % CURVE_COLOR_COUNT

    // ---- 7. 算结果 ----

    /**
     * 工具选够了：算出结果、建成 [GraphObject] 加进 [objects]（于是自动出现在
     * 第一屏的函数列表里），然后把**这一轮**清空、**让工具继续保持在选中状态**。
     *
     * ## 为什么算完不再退回「移动」（这里以前就是那么写的）
     *
     * 用户原话："正确逻辑应该是可以选择多个角度之后手动切回移动才移动。"
     * 量完一个角自动退出，用户想再量一个就得回功能界面重新选一次；更糟的是
     * 界面里"是不是移动模式"那一份值是手势闭包里的旧快照（见 `GraphScreen`
     * 那段说明），退回移动之后点也点不动、拖也拖不动 —— 用户看到的就是"卡住"。
     * 现在改成 [restartToolFlow]：工具名不变、已选的点清空，接着点就是下一个对象。
     *
     * ## 返回值一定要有话
     *
     * **成功也要说话**（"e = 36.87°（角度）"）：手表上没有别的反馈通道，
     * 静悄悄加一行对象，用户根本不知道刚才那三下干了什么。每条分支都保证
     * 给出一句话，包括"点数不够/构不成角"这种失败。
     *
     * 「面积」这种开放式工具在"点回第一个点"时进来；「描点」根本不走这条路
     * （它是连续动作，见 `pendingPoint`）。
     */
    private fun finishTool(tool: FunctionTool): String? {
        val flow = toolFlow ?: return null
        val pts = flow.points
        var message: String? = null

        when (tool) {
            FunctionTool.MOVE, FunctionTool.PLOT_POINT -> Unit

            // ---- 第二栏：度量 ----
            //
            // 三种度量的名字都是**小写字母**（a、b、c…，不再跳过任何字母，
            // 见 GraphObjects.kt 文件头那段名字规则和 nextMeasureName）。
            //
            // 角度是**两条路**（用户原话："先点击两条线段或者调用线段工具点击三个点
            // 创建两条线段（创建的线段自动命名并且放在函数列表里）再创建角度，
            // 角度只包含这个角，不包含两条线段"）：
            //   * 两条线段那条路：把用户点的两条边直接量成角；
            //   * 三个点那条路（[ToolFlow.altBranch]）：**先用这三个点建出两条线段**
            //     （走和「线段」工具同一个 addLineObject，自动命名、进函数列表），
            //     再拿这两条线段量角。所以两条线段是**独立对象**，角度里不含它们。
            // 量出来的角度对象只存"顶点 + 两条边所指方向上的两个点"（见 angleFromLines），
            // 换句话说它只有这个角本身：线段该显示/该删除都跟它无关。
            FunctionTool.ANGLE -> {
                if (flow.altBranch) {
                    if (pts.size >= 3) {
                        val ids = pickedPointIds(flow)
                        // 两条边也是"派生对象"：端点一拖，线段跟着走
                        val s1 = addLineObject(
                            segmentOf(pts[0], pts[1]), "线段", GLineKind.SEGMENT,
                            recipe = if (ids.size >= 2) {
                                ObjectRecipe.LineFromPoints(ids[0], ids[1], GLineKind.SEGMENT)
                            } else {
                                null
                            }
                        )
                        val s2 = addLineObject(
                            segmentOf(pts[1], pts[2]), "线段", GLineKind.SEGMENT,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.LineFromPoints(ids[1], ids[2], GLineKind.SEGMENT)
                            } else {
                                null
                            }
                        )
                        if (s1 == null || s2 == null) {
                            message = "角度：这几个点重合了，两条线段画不出来（已重新开始）"
                        } else {
                            message = buildAngleObject(
                                l1Name = s1.name, l1 = s1.line,
                                l2Name = s2.name, l2 = s2.line,
                                prefix = "已建线段 ${s1.name}、${s2.name}；",
                                recipe = ObjectRecipe.AngleBetweenLines(
                                    ObjectRecipe.LineRef.Obj(s1.id),
                                    ObjectRecipe.LineRef.Obj(s2.id)
                                )
                            )
                        }
                    } else {
                        message = "角度：至少要三个不同的点（这次只有 ${pts.size} 个），已重新开始"
                    }
                } else {
                    // 第 0 步和第 1 步都得是"一条线段/直线"（坐标轴以 inline 形式进来）
                    val picked = flow.selections
                        .filterIsInstance<ToolSelection.LineSel>()
                    val data = picked.mapNotNull { lineSelData(it) }
                    if (data.size >= 2) {
                        message = buildAngleObject(
                            l1Name = data[0].first, l1 = data[0].second,
                            l2Name = data[1].first, l2 = data[1].second,
                            recipe = ObjectRecipe.AngleBetweenLines(
                                lineRefOf(picked[0]),
                                lineRefOf(picked[1])
                            )
                        )
                    } else {
                        message = "角度：要两条线段/直线（先用「线段」工具画两条，" +
                            "或者连点三个点自己建）"
                    }
                }
            }

            FunctionTool.DISTANCE -> {
                if (pts.size >= 2) {
                    val ids = pickedPointIds(flow)
                    val v = distance(pts[0], pts[1])
                    val m = GraphMeasureObject(
                        id = nextId(),
                        name = nextMeasureName(),
                        kind = MeasureKind.DISTANCE,
                        points = listOf(pts[0], pts[1]),
                        value = v,
                        pointNames = namesForPoints(pts.take(2)),
                        colorIndex = nextObjectColor(),
                        recipe = if (ids.size >= 2) ObjectRecipe.DistanceBetween(ids[0], ids[1]) else null
                    )
                    addObject(m)
                    message = "${m.name} = ${m.valueText()}（${m.detail}）"
                } else {
                    message = "距离：需要两个不同的点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.AREA -> {
                // 围多边形的顶点就是 flow.points 本身 —— **不要**再 `dropLast(1)`。
                // 这里以前写的是 `pts.dropLast(1)`，而状态机在"点回第一个点"时
                // 并不把那个重合的点记进 points，于是每次都白白丢掉一个真顶点：
                // 三个点围的三角形变成两个点 → `ring.size >= 3` 不成立 →
                // **面积对象一个都没建出来**（用户报的"面积…函数列表不显示对象"）。
                val ring = pts
                val ids = pickedPointIds(flow)
                if (ring.size >= ToolFlow.MIN_AREA_POINTS) {
                    val v = polygonArea(ring)
                    val m = GraphMeasureObject(
                        id = nextId(),
                        name = nextMeasureName(),
                        kind = MeasureKind.AREA,
                        points = ring,
                        value = v,
                        pointNames = namesForPoints(ring),
                        colorIndex = nextObjectColor(),
                        recipe = if (ids.size == ring.size) ObjectRecipe.AreaOfPoints(ids) else null
                    )
                    addObject(m)
                    message = "${m.name} = ${m.valueText()}（${m.detail}）"
                } else {
                    message = "面积：至少要 ${ToolFlow.MIN_AREA_POINTS} 个顶点（现在 ${ring.size} 个），已重新开始"
                }
            }

            // ---- 第三栏：变换 ----
            FunctionTool.AXIS_SYMMETRY -> message = finishAxisOrCenter(flow, center = false)
            FunctionTool.CENTER_SYMMETRY -> message = finishAxisOrCenter(flow, center = true)

            // ---- 第四栏：作图 ----
            FunctionTool.MIDPOINT -> {
                if (pts.size >= 2) {
                    val mid = midpoint(pts[0], pts[1])
                    val ids = pickedPointIds(flow)
                    // 中点本身是个**点**（用户点"中点"要的是那个点），不是线。
                    val name = nextObjectName(ObjectKind.POINT)
                    addObject(
                        GraphPointObject(
                            id = nextId(),
                            name = name,
                            point = mid,
                            kind = SnapKind.POINT,
                            colorIndex = POINT_DEFAULT_COLOR_INDEX,
                            recipe = if (ids.size >= 2) ObjectRecipe.MidpointOf(ids[0], ids[1]) else null
                        )
                    )
                    message = "$name：中点已画出"
                } else {
                    message = "中点：需要两个点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.PERPENDICULAR -> {
                val line = pickedLine(flow)
                val p = pts.lastOrNull()
                if (p != null && line != null) {
                    addLineObject(
                        perpendicular(line, p), "垂线", GLineKind.LINE,
                        recipe = perpendicularRecipe(flow)
                    )
                } else {
                    message = "垂线：需要" +
                        (if (p == null) "一个点" else "一条直线（先用直线/线段工具画一条）")
                }
            }

            FunctionTool.PERP_BISECTOR -> {
                if (pts.size >= 2) {
                    val ids = pickedPointIds(flow)
                    addLineObject(
                        perpendicularBisector(pts[0], pts[1]), "中垂线", GLineKind.LINE,
                        recipe = if (ids.size >= 2) {
                            ObjectRecipe.PerpendicularBisectorOf(ids[0], ids[1])
                        } else {
                            null
                        }
                    )
                } else {
                    message = "中垂线：需要两个点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.PARALLEL -> {
                val line = pickedLine(flow)
                val p = pts.lastOrNull()
                if (p != null && line != null) {
                    addLineObject(
                        parallel(line, p), "平行线", GLineKind.LINE,
                        recipe = parallelRecipe(flow)
                    )
                } else {
                    message = "平行线：需要" +
                        (if (p == null) "一个点" else "一条参考线（先用直线/线段工具画一条）")
                }
            }

            FunctionTool.BISECTOR -> {
                if (pts.size >= 3) {
                    val l = angleBisector(pts[0], pts[1], pts[2])
                    if (l == null) {
                        message = "这三个点构不成角（三点重合或共线），已重新开始"
                    } else {
                        val ids = pickedPointIds(flow)
                        addLineObject(
                            l, "角平分线", GLineKind.LINE,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.AngleBisectorOf(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )
                    }
                } else {
                    message = "角平分线：需要三个点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.TANGENT -> message = finishTangent(flow)

            // ---- 第五栏：直线 ----
            FunctionTool.SEGMENT -> if (pts.size >= 2) {
                val ids = pickedPointIds(flow)
                addLineObject(
                    segmentOf(pts[0], pts[1]), "线段", GLineKind.SEGMENT,
                    recipe = if (ids.size >= 2) {
                        ObjectRecipe.LineFromPoints(ids[0], ids[1], GLineKind.SEGMENT)
                    } else {
                        null
                    }
                )
            } else {
                message = "线段：需要两个点（这次只有 ${pts.size} 个），已重新开始"
            }

            FunctionTool.STRAIGHT -> if (pts.size >= 2) {
                val ids = pickedPointIds(flow)
                addLineObject(
                    lineOf(pts[0], pts[1]), "直线", GLineKind.LINE,
                    recipe = if (ids.size >= 2) {
                        ObjectRecipe.LineFromPoints(ids[0], ids[1], GLineKind.LINE)
                    } else {
                        null
                    }
                )
            } else {
                message = "直线：需要两个点（这次只有 ${pts.size} 个），已重新开始"
            }

            FunctionTool.RAY -> if (pts.size >= 2) {
                val ids = pickedPointIds(flow)
                addLineObject(
                    rayOf(pts[0], pts[1]), "射线", GLineKind.RAY,
                    recipe = if (ids.size >= 2) {
                        ObjectRecipe.LineFromPoints(ids[0], ids[1], GLineKind.RAY)
                    } else {
                        null
                    }
                )
            } else {
                message = "射线：需要两个点（这次只有 ${pts.size} 个），已重新开始"
            }

            // ---- 第六栏：圆 ----
            //
            // 五种方式全部产出一条**隐函数曲线**（见 `CircleConics.kt` 文件头）：
            // 和普通函数一样进列表，显隐 / 着色 / 属性页 / 删除全部复用既有管线，
            // 颜色默认是工具线的黑（夜间白，见 [addToolCurve]）。
            FunctionTool.CIRCLE_CENTER_POINT -> {
                if (pts.size >= 2) {
                    val eq = circleEquation(pts[0], distance(pts[0], pts[1]))
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "圆心·点：圆心和圆上一点重合了，圆画不出来（已重新开始）"
                    } else {
                        "已画出圆 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 2) {
                                ObjectRecipe.CircleCenterPoint(ids[0], ids[1])
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "圆心·点：需要圆心和圆上一点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.CIRCLE_CENTER_RADIUS -> {
                // 点一条线段那条路在 [toolTap] 里特判了（状态机只认点）。
                if (pts.size >= 3) {
                    val r = distance(pts[1], pts[2])
                    val eq = circleEquation(pts[0], r)
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "圆心·半径：那两个半径端点重合了，圆画不出来（已重新开始）"
                    } else {
                        "已画出圆 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.CircleCenterRadius(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "圆心·半径：需要圆心和两个半径端点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.CIRCLE_RADIUS_CENTER -> {
                val center = pts.firstOrNull()
                if (center == null) {
                    message = "半径·圆心：先点一个圆心"
                } else {
                    // 半径输入屏由 [Route.Radius] 打开（整屏复用函数创建界面）；
                    // 圆心对象 id 一起存下来：建圆时才能记"跟着这个点动"（见 [radiusInputCenterId]）。
                    radiusInputCenter = center
                    radiusInputCenterId = pickedPointIds(flow).firstOrNull()
                    // 每次打开都是空的（和"点 + 新建函数"一样从空白开始）。
                    radiusInputText = ""
                    // 1.3.2：半径输入不再是浮层，而是整屏路由（复用函数创建界面）。
                    route = Route.Radius
                    message = "输入半径（可以写滑动条，比如 d）"
                }
            }

            FunctionTool.CIRCLE_THREE_POINTS -> {
                if (pts.size >= 3) {
                    val found = circleThroughThreePoints(pts[0], pts[1], pts[2])
                    val eq = found?.let { circleEquation(it.first, it.second) }
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "过三点：这三点在同一条直线上（或有两个重合），画不出圆（已重新开始）"
                    } else {
                        "已画出圆 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.CircleThreePoints(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "过三点：需要三个不同的点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.CIRCLE_ARC -> {
                if (pts.size >= 3) {
                    val spec = arcOf(pts[0], pts[1], pts[2])
                    val ids = pickedPointIds(flow)
                    message = if (spec == null) {
                        "圆弧：起点和圆心重合了，弧画不出来（已重新开始）"
                    } else {
                        // t 是弧度：这一条**不管全局度制**怎么设都按弧度解释
                        val name = addToolCurve(
                            PlotMode.PARAMETRIC,
                            spec.xs,
                            spec.ys,
                            tMin = spec.tMin,
                            tMax = spec.tMax,
                            degrees = false,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.ArcOfPoints(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )
                        "已画出圆弧 $name"
                    }
                } else {
                    message = "圆弧：需要圆心、起点、终点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            // ---- 第七栏：圆锥曲线 ----
            //
            // 几何部分走**定义式**（`|PF1|+|PF2| = 2a` 这种），这样任意朝向都成立，
            // 也不用解坐标系旋转；采样仍然是隐函数那一套。
            FunctionTool.ELLIPSE -> {
                if (pts.size >= 3) {
                    val eq = ellipseEquation(pts[0], pts[1], pts[2])
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "椭圆：这个点落在两焦点连线上（或焦点重合），画不出椭圆（已重新开始）"
                    } else {
                        "已画出椭圆 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.EllipseOf(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "椭圆：需要两个焦点和椭圆上一点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.CONIC_FIVE_POINTS -> {
                if (pts.size >= 5) {
                    val eq = conicThroughPoints(pts.take(5))
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "圆锥曲线：这五个点画不出圆锥曲线（重合 / 共线），已重新开始"
                    } else {
                        "已画出圆锥曲线 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 5) {
                                ObjectRecipe.ConicFivePoints(ids.take(5))
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "圆锥曲线：需要五个点（这次只有 ${pts.size} 个），已重新开始"
                }
            }

            FunctionTool.PARABOLA -> {
                // 第 0 步焦点（点）、第 1 步准线（直线）。准线可以是已有的直线/线段，
                // 也可以直接点坐标轴（[axisLineAt] 那条内联直线）。
                val focus = pts.firstOrNull()
                val directrix = pickedLine(flow)
                val eq = if (focus != null && directrix != null) {
                    parabolaEquation(focus, directrix)
                } else {
                    null
                }
                val focusId = pickedPointIds(flow).firstOrNull()
                val directrixRef = pickedLineRef(flow)
                message = when {
                    focus == null -> "抛物线：先点焦点"
                    directrix == null -> "抛物线：再点一条准线（可以先用「直线」工具画一条）"
                    eq == null -> "抛物线：这条准线通过了焦点（或退化了），画不出抛物线（已重新开始）"
                    else -> "已画出抛物线 ${addToolCurve(
                        PlotMode.IMPLICIT, eq,
                        recipe = if (focusId != null && directrixRef != null) {
                            ObjectRecipe.ParabolaOf(focusId, directrixRef)
                        } else {
                            null
                        }
                    )}：$eq"
                }
            }

            FunctionTool.HYPERBOLA -> {
                if (pts.size >= 3) {
                    val eq = hyperbolaEquation(pts[0], pts[1], pts[2])
                    val ids = pickedPointIds(flow)
                    message = if (eq == null) {
                        "双曲线：这个点到两焦点的距离相等（或焦点重合），画不出双曲线（已重新开始）"
                    } else {
                        "已画出双曲线 ${addToolCurve(
                            PlotMode.IMPLICIT, eq,
                            recipe = if (ids.size >= 3) {
                                ObjectRecipe.HyperbolaOf(ids[0], ids[1], ids[2])
                            } else {
                                null
                            }
                        )}：$eq"
                    }
                } else {
                    message = "双曲线：需要两个焦点和曲线上一点（这次只有 ${pts.size} 个），已重新开始"
                }
            }
        }

        // **工具不退**：清掉这一轮的选择，用户接着点就是下一个对象。
        restartToolFlow(tool)
        return message
    }

    /**
     * 轴对称 / 中心对称：第一步选"对象"（点 / 曲线 / 直线），
     * 第二步选对称轴（直线）或对称中心（点）。
     *
     * **对象和轴必须按步序分开取**：这里以前两个角色共用 `flow.pickedObjectId`，
     * 而第二步选完轴之后那个值就是轴的 id —— 于是"被变换的对象"变成了那条轴本身，
     * 轴对称一条曲线永远走不到曲线的分支（用户报的"轴对称和中心对称在函数图像
     * 页面都点不动"）。现在第 0 步 = 对象、第 1 步 = 轴/中心，各取各的。
     */
    private fun finishAxisOrCenter(flow: ToolFlow, center: Boolean): String? {
        val what = if (center) "中心对称" else "轴对称"
        val objSel = flow.selectionAt(0) ?: return "$what：还没选到要对称的对象"
        val refSel = flow.selectionAt(1) ?: return "$what：还没选到${if (center) "对称中心" else "对称轴"}"

        // ---- 第二步：对称轴（直线）或对称中心（点）----
        val axisLine: GLine? = (refSel as? ToolSelection.LineSel)?.let { ls ->
            // 坐标轴以"内联直线"的形式进来（见 axisLineAt / ToolSelection.LineSel.inline）
            ls.inline ?: (objects.firstOrNull { it.id == ls.objectId } as? GraphLineObject)
                ?.takeIf { !it.line.isDegenerate }
                ?.line
        }
        val centerPoint: GPoint? = (refSel as? ToolSelection.PointSel)?.point

        val xf: (GPoint) -> GPoint = if (center) {
            val c = centerPoint ?: return "$what：对称中心必须是一个点"
            { p -> reflectThrough(p, c) }
        } else {
            val axis = axisLine
                ?: return "$what：对称轴必须是一条直线/线段（可以先用「直线」工具画一条）"
            { p -> reflect(p, axis) }
        }

        // ---- 第一步：被变换的对象 ----
        val objectId: Long? = when (objSel) {
            is ToolSelection.PointSel -> objSel.objectId
            is ToolSelection.CurveSel -> objSel.functionId
            is ToolSelection.LineSel -> objSel.objectId
        }
        val objPoint = (objSel as? ToolSelection.PointSel)?.point
        val fn = objectId?.let { id -> functions.firstOrNull { it.id == id } }
        val lineObj = objectId?.let { id -> objects.firstOrNull { o -> o.id == id } as? GraphLineObject }

        return when {
            // 曲线：记下**规则**（不是结果），以后每次重采样都按它重画这条对称曲线
            fn != null -> {
                transformedCurves += TransformedCurve(
                    functionId = fn.id,
                    axis = if (center) null else axisLine,
                    center = if (center) centerPoint else null
                )
                val drawn = emitTransformedCurve(fn, xf)
                if (drawn > 0) {
                    "$what：已画出 ${fn.name} 的对称曲线"
                } else {
                    // 一段都没画出来（曲线被隐藏了 / 还没采样结果）：**必须说出来**，
                    // 否则用户以为工具又坏了（这就是本轮反复出现的那类"没反应"）。
                    "$what：${fn.name} 现在没显示出来（可能被隐藏了），对称曲线要等它显示后才有"
                }
            }

            lineObj != null && !lineObj.line.isDegenerate -> {
                editLineObject(lineObj, xf)
                "$what：已把 ${lineObj.name} 变成对称位置的那条线"
            }

            objPoint != null -> {
                val name = nextObjectName(ObjectKind.POINT)
                // 对称点也是"派生对象"：被对称的点或轴/中心一动，它跟着动。
                // 两个 id 都取不到（理论上不会：这一步描点/选点都带 id）就不记，宁可不跟随。
                val srcId = (objSel as? ToolSelection.PointSel)?.objectId
                val mirror = if (center) {
                    (refSel as? ToolSelection.PointSel)?.objectId
                        ?.let { ObjectRecipe.MirrorPointOf.Mirror.Center(it) }
                } else {
                    (refSel as? ToolSelection.LineSel)
                        ?.let { ObjectRecipe.MirrorPointOf.Mirror.Axis(lineRefOf(it)) }
                }
                addObject(
                    GraphPointObject(
                        id = nextId(),
                        name = name,
                        point = xf(objPoint),
                        kind = SnapKind.POINT,
                        colorIndex = POINT_DEFAULT_COLOR_INDEX,
                        recipe = if (srcId != null && mirror != null) {
                            ObjectRecipe.MirrorPointOf(srcId, mirror)
                        } else {
                            null
                        }
                    )
                )
                "$what：$name 已画出"
            }

            else -> "$what：没认出要对称的对象，请重新点一次"
        }
    }

    /**
     * 把一条**曲线**（函数/方程）按 [xf] 逐点变换后画出来。
     *
     * 做法：`plot.curves` 里的折线是**屏幕像素**坐标，先用 `plot.viewport`
     * 换回世界坐标、变换、再换回像素，然后**按段建成一批 [GraphLineObject]**
     * （每段一次直线段）。
     *
     * ### 这里有意的取舍（已在报告里写明）
     *  * 结果是一条**折线**而不是一条新曲线：如果加一个全新的对象类型
     *    （或者塞进 `functions` 里当一条表达式），要么得改禁改文件、
     *    要么得凭空造一个表达式 —— 对 `x^2` 的轴对称结果 `x = -y²` 根本
     *    写不成 `y=f(x)`。折线是唯一不撒谎的画法。
     *  * 折线每帧按**当前**采样结果重算（[GraphRoute] 里以 `plot` 为 key 重跑），
     *    所以放大之后会跟着变细；两个采样点之间的段是真实直线段，
     *    极端放大时看得出来是折线 —— 这是"不做曲线拟合"的代价。
     *  * 这些碎片对象**不进函数列表**（role = [FRAGMENT_ROLE]，见 `appearsInList`）：
     *    一条 `sin(x)` 的对称结果有上百段，逐段列出来列表就没法看了。
     *
     * @return 这次真正画出了几段折线（0 = 一段都没画出来：曲线被隐藏了、
     *   还没有采样结果、或者采样点太少）。调用方拿它决定要不要给用户
     *   "画出来了"还是"暂时画不出来"那句话 —— 静悄悄什么都不画是最糟的结果。
     */
    private fun emitTransformedCurve(fn: PlotFunction, xf: (GPoint) -> GPoint): Int {
        val vp = plot.viewport ?: return 0
        if (vp.unitsPerPixel <= 0.0 || !vp.unitsPerPixel.isFinite()) return 0
        val curve = plot.curves.firstOrNull { it.functionId == fn.id } ?: return 0
        val pts = curve.points
        if (pts.size < 4) return 0

        val gap = gapWorld()
        val rawSegments = pts.size / 2 - 1
        if (rawSegments <= 0) return 0
        // 段数超上限就隔几段抽一段（用户放大之后 rawSegments 会跟着涨）
        val stride = (rawSegments + MAX_FRAGMENTS - 1) / MAX_FRAGMENTS
        // 颜色跟着**被变换的那条曲线**走：折线每帧都会重建（id 每次都变），
        // 颜色只能挂在"函数的 colorIndex"上才不会丢 —— 而 fn.colorIndex 正是
        // 用户在函数列表里改的那个值，所以这里用不着额外的覆盖表。
        val color = fn.colorIndex

        // 先整段变换成世界坐标折线
        val world = ArrayList<GPoint>(pts.size / 2)
        var i = 0
        while (i + 1 < pts.size) {
            val w = GPoint(vp.toWorldX(pts[i].toDouble()), vp.toWorldY(pts[i + 1].toDouble()))
            world += if (w.isFinite) xf(w) else GPoint(Double.NaN, Double.NaN)
            i += 2
        }

        // 再按"连续段"切成若干折线对象：投影不连续的地方（比如 1/x 跨过 x=0、
        // 或者变换之后跑到无穷远处）必须断开，否则会连出一条穿过整屏的假线。
        var emitted = 0
        var start = 0
        var k = 0
        while (k < world.size) {
            val a = world[k]
            if (!a.isFinite) {
                if (k > start && emitFragment(world, start, k, color)) emitted++
                k++
                start = k
                continue
            }
            if (k > start) {
                val b = world[k - 1]
                if (gap > 0 && hypot(a.x - b.x, a.y - b.y) > gap) {
                    if (emitFragment(world, start, k, color)) emitted++
                    start = k
                }
            }
            k++
        }
        if (world.size > start && emitFragment(world, start, world.size, color)) emitted++
        return emitted
    }

    /**
     * 把 `world[from,to)` 这一段建成一个折线对象（点太少的段直接丢掉）。
     *
     * @return 真的建出了对象吗（[emitTransformedCurve] 靠它数"画出来几段"）。
     */
    private fun emitFragment(world: List<GPoint>, from: Int, to: Int, colorIndex: Int): Boolean {
        if (to - from < 2) return false
        val a = world[from]
        val b = world[to - 1]
        if (!a.isFinite || !b.isFinite) return false
        if (a.x == b.x && a.y == b.y) return false
        addObject(
            GraphLineObject(
                id = nextId(),
                name = FRAGMENT_ROLE,
                line = GLine(a, b, GLineKind.SEGMENT),
                role = FRAGMENT_ROLE,
                colorIndex = colorIndex
            )
        )
        return true
    }

    /**
     * 对一条**几何直线**做变换：两个端点各变一次，kind 保持不变。
     *
     * ⚠️ 必须是 [updateObject]（原地改）而不是 `addObject`：这里以前写的是
     * `addObject(o.copy(...))`，而 `copy` **带着同一个 id** —— 列表里会出现两个
     * id 相同的对象，`LazyColumn` 的 key 直接撞车（Compose 会抛
     * "Key ... was already used"）。轴对称一条线段时必崩。
     */
    private fun editLineObject(o: GraphLineObject, xf: (GPoint) -> GPoint) {
        val p = xf(o.line.p)
        val q = xf(o.line.q)
        if (!p.isFinite || !q.isFinite) return
        // recipe 必须清掉：这条线已经被用户"焊"到对称位置上了，
        // 留着原来的 LineFromPoints 会让下一次 refreshGeometry() 把它还原回去
        // （用户看到的就会是"对称完了又自己弹回来"）。
        updateObject(o.copy(line = GLine(p, q, o.line.kind), recipe = null))
    }

    /** 切线：在**这条函数/方程自己**的度制下编译它，再求数值切线。 */
    private fun finishTangent(flow: ToolFlow): String? {
        val pts = flow.points
        if (pts.isEmpty()) return null
        val curveId = flow.pickedCurveId ?: return "切线：需要点在某条函数/方程上"
        val fn = functions.firstOrNull { it.id == curveId } ?: return "切线：找不到那条函数"
        // **只对显函数 y=f(x) 有意义**：Geometry.tangentLine(f, x0) 要的是
        // "给定横坐标、返回纵坐标"的一元函数。极坐标 r(θ) / 参数方程 (x(t),y(t)) /
        // 隐函数 F(x,y)=0 都不是这个形状 —— 硬把 f(x) 代进去算出来的是一条
        // **看起来像切线但其实不是**的直线，那种"悄悄画错"比明说"不支持"糟糕得多。
        if (fn.mode != PlotMode.EXPLICIT) {
            return "切线目前只支持 ${PlotMode.EXPLICIT.label} 的函数"
        }
        val f = compileCurve(fn) ?: return "这条函数的表达式有问题，画不出切线"
        val p = pts.last()
        val line = tangentLine(f, p.x) ?: return "这一点上求不出切线"
        val pointId = pickedPointIds(flow).lastOrNull()
        addLineObject(
            line, "切线", GLineKind.LINE,
            // 切点动了 / 曲线被滑动条改了 → 切线重算（见 recomputeObject）
            recipe = pointId?.let { ObjectRecipe.TangentAtPoint(it, curveId) }
        )
        return null
    }

    /**
     * 编译一条曲线成 `(Double) -> Double`。
     *
     * 度制取**这个函数自己的** [effectiveAngleUnit]（和 `featureLines` 一样）：
     * 同屏一条走弧度一条走度制是这个 App 支持的用法，用错制导出来的切线斜率会完全不对。
     *
     * 参数表和可调用函数表也要跟着传：切线/工具里的点必须落在**画出来的那条曲线**上，
     * 而 `a*x+b`（滑动条）和 `2a(x)`（调用用户函数）都会因为少传而算成另一条线。
     */
    private fun compileCurve(fn: PlotFunction): ((Double) -> Double)? {
        val vars: List<String>
        val body: String
        when (fn.mode) {
            PlotMode.EXPLICIT -> {
                vars = listOf("x")
                body = stripExplicitPrefix(fn.src1)
            }

            PlotMode.POLAR -> {
                vars = PlotMode.POLAR.variables
                body = fn.src1
            }

            PlotMode.PARAMETRIC -> {
                vars = PlotMode.PARAMETRIC.variables
                body = fn.src1
            }

            PlotMode.IMPLICIT -> {
                vars = PlotMode.IMPLICIT.variables
                body = fn.src1
            }
        }
        // 取坐标替换**统一放在最后**（而不是在四个分支里各写一遍）：
        // 这条路径的四个模式都要能和图上那条曲线对上，少替换一个分支就是
        // "切线的切点不在曲线上"这种最难查的错。
        val substituted = withPointCoordinates(body)
        val ex = runCatching {
            Expression.compile(
                substituted,
                vars,
                effectiveAngleUnit(fn).isDegrees,
                parameters(),
                callables()
            )
        }.getOrNull() ?: return null
        val n = vars.size
        val buf = DoubleArray(n)
        return { x ->
            for (k in 0 until n) buf[k] = x
            runCatching { ex.evalArray(buf) }.getOrDefault(Double.NaN)
        }
    }

    // ---- 8. 小工具 ----

    /** 把世界坐标的点反查成"它叫什么名字"（度量名字里的 `角度（A，B，C）` 要用）。 */
    private fun namesForPoints(pts: List<GPoint>): List<String> = pts.map { p ->
        objects.asSequence()
            .filterIsInstance<GraphPointObject>()
            .firstOrNull {
                kotlin.math.abs(it.point.x - p.x) < 1e-9 && kotlin.math.abs(it.point.y - p.y) < 1e-9
            }
            ?.name
            // 找不到名字（比如这个点是"选择的是一条线段上的点"这种情形）：
            // 退回坐标，总比一个空字符串好 —— 名字里空一格用户会以为是坏了。
            ?: "(${formatNumber(p.x)}, ${formatNumber(p.y)})"
    }

    /**
     * 用户选中的那条**几何直线**（垂线/平行线的参考线；坐标轴也行）。
     *
     * 这里必须**挑出 [ToolSelection.LineSel] 那一步**，不能拿"最近一次选择里带的
     * id"：第 0 步选的直线对象 id 在第 1 步选完点之后就被那个**点**的 id 顶掉了，
     * 于是 `objects.firstOrNull { it.id == id } as? GraphLineObject` 永远为 null ——
     * 垂线/平行线点完两个点什么都不会发生（而且当时还会退回「移动」，看起来就是
     * "卡住"）。取第一个 LineSel 对两个工具都成立：垂线是 [点, 线]、平行线是 [线, 点]。
     */
    private fun pickedLine(flow: ToolFlow): GLine? {
        val sel = flow.selections.filterIsInstance<ToolSelection.LineSel>().lastOrNull() ?: return null
        // 坐标轴是"内联直线"（没有对象 id），直接用它的几何数据
        sel.inline?.let { return it }
        val o = objects.firstOrNull { it.id == sel.objectId } as? GraphLineObject ?: return null
        return if (o.line.isDegenerate) null else o.line
    }

    // ---- 5b. 派生对象的"施工说明"（[ObjectRecipe]） ----

    /**
     * 这一步选过的**点对象 id**（按选择的先后顺序）。
     *
     * 面积收尾那次选择故意不带 id（点回第一个点，见 [tapPoint]），
     * 所以这里 `mapNotNull` 会把它丢掉 —— 调用方靠"数量对得上"来判断
     * 该不该记 recipe（对不上就记 null = 不跟随，绝不乱指一个点）。
     */
    private fun pickedPointIds(flow: ToolFlow): List<Long> =
        flow.selections.filterIsInstance<ToolSelection.PointSel>().mapNotNull { it.objectId }

    /** 一次直线选择对应的 [ObjectRecipe.LineRef]（坐标轴这种内联直线记成常量）。 */
    private fun lineRefOf(sel: ToolSelection.LineSel): ObjectRecipe.LineRef =
        sel.inline?.let { ObjectRecipe.LineRef.Fixed(it) } ?: ObjectRecipe.LineRef.Obj(sel.objectId)

    /** 这一步最后选的那条线（坐标轴也认）；null = 没选线。 */
    private fun pickedLineRef(flow: ToolFlow): ObjectRecipe.LineRef? =
        flow.selections.filterIsInstance<ToolSelection.LineSel>().lastOrNull()?.let { lineRefOf(it) }

    /** 垂线：参考线 + 过的那个点。 */
    private fun perpendicularRecipe(flow: ToolFlow): ObjectRecipe? {
        val ref = pickedLineRef(flow) ?: return null
        val pid = pickedPointIds(flow).lastOrNull() ?: return null
        return ObjectRecipe.PerpendicularTo(ref, pid)
    }

    /** 平行线：参考线 + 过的那个点。 */
    private fun parallelRecipe(flow: ToolFlow): ObjectRecipe? {
        val ref = pickedLineRef(flow) ?: return null
        val pid = pickedPointIds(flow).lastOrNull() ?: return null
        return ObjectRecipe.ParallelTo(ref, pid)
    }

    /**
     * 建一条直线/线段/射线对象（自动命名、进函数列表）。
     *
     * **返回建出来的那个对象**（退化时 null）：角度的"三个点"那条路要用它的
     * 名字和几何数据（"已建线段 C、D"那句话、以及紧接着拿这两条线段量角），
     * 所以不能只返回 Unit。
     */
    private fun addLineObject(
        line: GLine,
        role: String,
        kind: GLineKind,
        recipe: ObjectRecipe? = null
    ): GraphLineObject? {
        if (line.isDegenerate) return null
        val o = GraphLineObject(
            id = nextId(),
            // 直线/线段/射线**和其他非点对象一样用小写字母**（用户本轮规则：
            // "除了点之外的所有对象的名字应该都是小写字母"）。原来它和点共用大写那一套
            // （`nextObjectName()` 走 `upperLetters`），所以列表里 `C` 既可能是点、
            // 也可能是线段 —— 现在点的名字独占大写，用户一眼能分清。
            name = nextObjectName(ObjectKind.LINE),
            line = GLine(line.p, line.q, kind),
            role = role,
            // 工具画出来的线默认**黑**（夜间白），见 TOOL_CURVE_COLOR_INDEX
            colorIndex = TOOL_CURVE_COLOR_INDEX,
            // "这条线是按哪两个点算出来的"（本轮新增）：拖点 / 滑动条改曲线之后
            // `refreshGeometry()` 按它把线重算一遍，于是线跟着点动。
            recipe = recipe
        )
        addObject(o)
        return o
    }

    /**
     * 一次 [ToolSelection.LineSel] 指向的那条直线 + 它给用户看的名字
     * （角度要拿它拼 `角度（C，D）`）。
     *
     * 两种来源都要认：几何对象（有 id）和**坐标轴**（内联直线，没有对象 id，
     * 见 [axisLineAt]）。取不到就返回 null —— 调用方据此给一句明确的话，
     * 而不是悄悄用一个不存在的边去量角。
     */
    private fun lineSelData(sel: ToolSelection.LineSel): Pair<String, GLine>? {
        sel.inline?.let { return AXIS_LINE_NAME to it }
        val o = objects.firstOrNull { it.id == sel.objectId } as? GraphLineObject ?: return null
        if (o.line.isDegenerate) return null
        return o.name to o.line
    }

    /**
     * 用两条线段/直线建一个**角度对象**，返回给用户看的那句话。
     *
     * 几何部分全在 `FunctionTools.angleFromLines` 里（纯函数、离线单测钉着）：
     * 它给出顶点、两条边各自所指的方向、以及度数。
     *
     * 存进 [GraphMeasureObject.points] 的顺序是 **`[arm1, vertex, arm2]`**，
     * 和原来"三个点量角"的顺序完全一致 —— 中间那个是顶点。取值
     * （`angleAt(points[1], points[0], points[2])`）、渲染（`drawMeasure` 里角度
     * 特判 `screen[1]` 当顶点）、数值标签的位置都按这个约定读，所以两条路
     * 做出来的角度对象在列表和图上长得一模一样。
     *
     * [pointNames] 放的是**两条线段的名字**：列表第二行会写 `角度（C，D）`，
     * 用户一眼知道这个角是哪两条边量出来的。那只是"说明"，线段仍然是独立对象
     * （用户原话："角度只包含这个角，不包含两条线段"）。
     */
    private fun buildAngleObject(
        l1Name: String,
        l1: GLine,
        l2Name: String,
        l2: GLine,
        prefix: String = "",
        recipe: ObjectRecipe? = null
    ): String {
        val a = angleFromLines(l1, l2)
            ?: return "${prefix}这两条线构不成角（平行、重合或共线），换个交点分明的来"
        val m = GraphMeasureObject(
            id = nextId(),
            name = nextMeasureName(),
            kind = MeasureKind.ANGLE,
            points = listOf(a.arm1, a.vertex, a.arm2),
            value = a.degrees,
            pointNames = listOf(l1Name, l2Name),
            colorIndex = nextObjectColor(),
            // 两条边动了（拖端点 / 滑动条改了线），角度按 `angleFromLines` 重算
            recipe = recipe
        )
        addObject(m)
        return "$prefix${m.name} = ${m.valueText()}（${m.detail}）"
    }

    /**
     * **几何对象**（点 / 直线）的自动名字：**按种类决定大小写，判据只有一处**
     * （`GraphObjects.kt` 的 [objectNameCase]）。
     *
     * 用户这一轮把规则简化成"除了点之外的所有对象的名字应该都是小写字母"：
     *
     * | 种类 | 名字 | 为什么 |
     * |---|---|---|
     * | [ObjectKind.POINT] | 大写序列 `A`、`B`…`AA` | `x(A)` / `y(A)` 这种"取坐标"写法靠 `isPointName`（只认大写字母序列）识别点名 |
     * | [ObjectKind.LINE] | 小写序列 `a`、`b`… | 用户要求"直线也是小写"（原来它和点共用大写那一套） |
     * | [ObjectKind.MEASURE] | 小写序列 `a`、`b`… | 角度/距离/面积本来就是小写 |
     *
     * 大写那一档**必须**连带几何对象一起看占用：它原来只查 `functions`
     * （[nextUpperName]），于是"描了 A，再描一个又是 A"—— 列表里两行同名，
     * 用户根本分不清哪个是哪个（真机上一眼就看出来了）。
     *
     * 小写那一档复用 [nextScalarName]（`FunctionTools.kt` 里的纯函数，离线单测钉着）：
     * 直线和度量落在**同一套小写字母表**上，所以两者的名字天然不会撞
     * （`taken` 里带着已有对象名）。这条规则改变的一个**已知代价**：角度的副标题
     * `角度（c，d）` 里那两个字母现在是线段名，和标量名共用小写字母表 ——
     * 用户这一轮明确接受（列表第一行是对象名、第二行是数值）。
     *
     * @param taken 已经占用的名字（默认现取 [takenNames]；[reNameMeasures]
     *   自己维护一份递增的表，所以留了这个参数）。
     */
    private fun nextObjectName(kind: ObjectKind, taken: Set<String> = takenNames()): String =
        when (objectNameCase(kind)) {
            ObjectNameCase.UPPER -> firstFreeUpperName(taken)
            ObjectNameCase.LOWER -> nextScalarName(taken)
        }

    /** 第一个没被占用的**大写**序列（`A`、`B`…`Z`、`AA`…）。 */
    private fun firstFreeUpperName(taken: Set<String>): String {
        var i = 0
        while (true) {
            val candidate = upperLetters(i)
            if (candidate !in taken) return candidate
            i++
        }
    }

    /**
     * **标量**对象（角度/距离/面积）的自动名字：小写字母序列，第一个可用的是 `a`。
     *
     * 用户原话："角度、距离、面积都是标量，应该用小写字母作为对象名称。"
     * 以及本轮那句："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
     * 标量也可以用 a，滑动条也可以用 f。"
     *
     * 所以它**不再跳过**任何字母（原来跳 `a`~`d` / `x` / `y` / `t` / `θ` / `z` / `e` / `i`）；
     * 规则本体在 `FunctionTools.kt` 的 [nextScalarName] 里（纯 Kotlin、有离线单测钉着），
     * 这里只负责把"已经被占用的名字"（[takenNames]）递进去。
     *
     * 名字撞上引擎里的含义（比如分到 `x`）**不再是错误**：那条函数里必须由用户
     * 在编辑器里指认这个字母指谁（[functionBindings]），采样前先查绑定表
     * （见 [functionsForSampling] 与 `PointCoordinates.resolveScalarValues`）。
     */
    private fun nextMeasureName(): String = nextObjectName(ObjectKind.MEASURE)

    /**
     * 已经**被占用**的名字：函数名、函数的"可调用名"、列表里已有的几何对象名。
     *
     * 函数名要取"括号前那一截"：`f(x)` 这种名字在表达式里写 `f(...)` 就是在调用它，
     * 所以字母 `f` 已经属于那个函数了 —— 用户把某条函数改名叫 `f(x)` 之后，
     * 标量对象就不能再叫 `f`（否则 `f` 在表达式里既有"调用函数"又有"那个度量"两种意思）。
     *
     * ⚠️ **滑动条绑定的参数字母本轮从这里去掉了**。用户原话："标量和滑动条的名字可以
     * 不用严格分出使用的字母……标量也可以用 a，滑动条也可以用 f。" 所以
     * "已经有一条 `a` 滑动条"不再阻止标量被分到名字 `a` —— 那种重名是**允许**的，
     * 由用户在编辑器里指认（[functionBindings]）。仍然必须避开的只剩函数名/可调用名
     * 和已有对象名：那两个是列表里"两行同名"、或者 `f(...)` 到底是调用还是取值的
     * 真冲突，靠指认也说不清。
     *
     * ⚠️ 这里那句"几何对象名是大写、碰不到小写滑动条"**本轮不再成立**：
     * 直线也改成小写之后，直线名和标量名落在同一张表上（`taken` 里带着已有对象名，
     * 所以两者不会互相撞），而滑动条仍然不进 `taken` —— 也就是"一条叫 `a` 的直线
     * 可以和一条 `a` 滑动条并存"，语义冲突由编辑器里的绑定表说清，
     * 和标量那一条**完全同一个口径**。
     *
     * @param exceptId 改名时要排除的**它自己**：`taken` 里放着自己的旧名字，
     *   会把"点进去看一眼、名字一个字没动"也判成重名（[renameObject] 与
     *   [takenObjectNames] 传它；取名分配器不传）。
     */
    private fun takenNames(exceptId: Long? = null): Set<String> = buildSet {
        for (fn in functions) {
            add(fn.name)
            add(fn.name.substringBefore('(').trim())
        }
        for (o in objects) if (o.id != exceptId) add(o.name)
    }

    /**
     * 给列表里所有**标量**对象重新分配名字（打开函数文件之后调）。
     *
     * 为什么需要它：`.fdf` 的 `FileObject.Measure` 里**没有名字字段**
     * （那边只存 `pointNames`，名字是按旧规则现算的描述性文本），
     * 而新规则要求"小写字母"这种名字。装载时按同一套规则重排一遍，
     * 比去改文件格式安全（那个文件不在本次改动范围内，而且加字段也读不出老文件的名字）。
     *
     * 顺序按列表原序，所以 `a`、`b`、`c`… 和用户看到的行顺序一致；同一个文件
     * 打开两次得到同一批名字（幂等）。
     *
     * ⚠️ **只重排度量，不动直线**（本轮）：直线名是**存在文件里的**
     * （`FileObject.Line.name`），而且老文件里可能是大写 —— 按用户要求
     * "兼容读取、不要静默改名"，那些名字照用；只有**新建/改名**才走新的小写规则。
     * 度量这一条不一样：文件里根本没有它的名字字段，不重排就只能是空串。
     */
    private fun reNameMeasures(list: List<GraphObject>): List<GraphObject> {
        if (list.none { it is GraphMeasureObject }) return list
        val taken = takenNames().toMutableSet()
        return list.map { o ->
            if (o !is GraphMeasureObject) {
                o
            } else {
                val name = nextObjectName(ObjectKind.MEASURE, taken)
                taken += name
                o.copy(name = name)
            }
        }
    }

    /** 单个大写字母序列（0→A、25→Z、26→AA…），和 `nextUpperName` 同一套规则。 */
    private fun upperLetters(index: Int): String {
        var n = index.coerceAtLeast(0)
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    companion object {
        /** 初始视野：x ∈ [-10, 10]。 */
        const val DEFAULT_HALF_RANGE = 10.0

        /**
         * 撤销栈的上限（步）。
         *
         * 为什么是 20：用户在手表上按左箭头，心里记的是"刚才那几步" ——
         * 栈太深只会让"一直按回去"变成一件失控的事（而且每按一次都要重采样一遍）。
         * 内存不是这里的约束：一步快照只存**几个 list 的引用**（元素共享），
         * 量级几十~几百字节，20 步合计几十 KB（完整推导见 `undo` 那一节的注释）。
         */
        const val UNDO_STACK_LIMIT = 20

        /**
         * 点击命中**曲线 / 线段 / 直线 / 面积轮廓**的容差，占屏宽的比例。
         *
         * 0.09 × 466px ≈ **42px（约 21dp）**—— 手指比鼠标粗得多，
         * 阈值太小会出现"明明压在线上却选不中"。这条从 #7"点曲线"时代就在用，
         * 现在对象命中判定（线、面积轮廓）也共用它：**同一块屏幕上"够不够近"
         * 只该有一把尺子**，否则会出现"曲线点得中、贴着它的线段点不中"。
         */
        const val TAP_SLOP_FRACTION = 0.09f

        /**
         * 上面那把尺子的**像素下限**。
         *
         * 24px ≈ 12dp：只在"屏宽换算出来比它还小"时才生效（density 报到 1.0 的机型上
         * 0.09 × 屏宽会缩得太小）。低于这个值在真机上基本点不中。
         */
        const val MIN_TAP_SLOP_PX = 24f

        /**
         * 点击命中一个**点对象**的半径，占屏宽的比例。
         *
         * 0.06 × 466px ≈ **28px（约 14dp）**：比"线"那档（0.09）**小一档**，
         * 因为点是**优先级最高**的一类 —— 半径太大就会把旁边线段、曲线的点击全吃掉。
         * 但它也绝不能取"点的绘制半径"（`GraphCanvas.OBJECT_POINT_R` 只有屏宽 1.7% ≈ 8px），
         * 8px 在手表上根本点不中：手指落点的误差本来就有一二十像素。
         *
         * ⚠️ 这个数**和吸附半径（[SNAP_RADIUS_FRACTION]）数值相同**，但刻意是**两个独立的常量**：
         * "描点时吸到哪个点"和"点在哪个对象上"是两件事，将来调其中一个不该悄悄改掉另一个。
         */
        const val TAP_POINT_FRACTION = 0.06f

        /** [TAP_POINT_FRACTION] 的像素下限（20px ≈ 10dp，理由同 [MIN_TAP_SLOP_PX]）。 */
        const val MIN_TAP_POINT_PX = 20f
    }
}

// ===================================================================================
// 「功能界面」用到的常量
// ===================================================================================

/** 曲线配色只有 12 种（见 `theme/CurvePalette`），几何对象按它取模分配颜色。 */
internal const val CURVE_COLOR_COUNT = 12

/**
 * 变换出来的曲线折线的 role 标记。
 *
 * 一条曲线的对称结果会被拆成上百个直线段对象（见 `GraphState.emitTransformedCurve`），
 * 它们**不该出现在函数列表里** —— 列表里一屏全是一模一样的"对称曲线"行，
 * 用户既看不懂也没法用。`GraphObject.appearsInList` 按这个标记把它们过滤掉。
 */
internal const val FRAGMENT_ROLE = "对称曲线"

/** 曲线对称结果最多拆成多少段（段数超了就隔几段抽一段）。 */
private const val MAX_FRAGMENTS = 192

/**
 * "这条直线是坐标轴，不是图上的对象"用的哨兵 id。
 *
 * 坐标轴没有 [GraphLineObject]、也没有 id，但状态机那一步要求"
 * [ToolSelection.LineSel]"（见 [axisLineAt]）。取负数是因为 [GraphState.nextId]
 * 只从 1 开始递增，负数永远不会和真实对象撞车。
 */
private const val AXIS_LINE_ID = -1L

/**
 * 坐标轴在"量角/取边"这类场合**显示出来的名字**。
 *
 * 它不是几何对象、没有自动名字（[AXIS_LINE_ID] 那条注释说了为什么），
 * 但角度的列表第二行会写 `角度（C，D）` 这种"是哪两条边量出来的"，
 * 所以坐标轴也得有个说法 —— 直接用中文，用户一眼知道那是哪条轴。
 */
private const val AXIS_LINE_NAME = "坐标轴"

/** 每条曲线最多取几个零点/极值点当吸附候选（FeatureFinder 的 limit）。 */
private const val MAX_FEATURES = 6

/**
 * 点中曲线时最多高亮几个特征点。
 *
 * 用户要求"附近有两个及以上"就显示，但信息卡只有 1.5 寸屏那么宽，
 * 6 个以上的坐标一定会溢出；取 6 是"两条曲线交 3 个点 + 一条曲线的 3 个顶点"这种常见上限。
 */
private const val MAX_FEATURE_HIGHLIGHTS = 6

/** 求两两交点时最多看几条曲线（这是双重循环，手表上算不起太多对）。 */
private const val MAX_INTERSECTION_CURVES = 4

/**
 * 参与"自绘线 ↔ 自绘线"求交的线条数上限。
 *
 * 和函数对那一档（[MAX_INTERSECTION_CURVES] = 4）不是一个量级：两条线求交是几行算术，
 * 32 条两两算也只有 496 次。**不能再套 4 条** —— 那时第 5 条线之后的交点会从候选表里
 * 整个消失（用户实测："自绘两条直线取交点会避开交点导致根本描不到"）。
 */
private const val MAX_INTERSECTION_LINES = 32

/** "函数 ↔ 自绘线"最多算几对（折线求交，比解析贵得多）。 */
private const val MAX_INTERSECTION_LINE_PAIRS = 8

/**
 * "两点定线"这条工具链的 role。
 *
 * 读盘时只有这三种线才按锚点还原 [ObjectRecipe.LineFromPoints]（见
 * [GraphState.withInferredLineRecipes]）：它们一定是"从两个点对象画出来"的，
 * 锚点坐标和点对象逐位相同；别的派生线（垂线 / 平行线 / 切线 …）不满足这个前提。
 */
private val LINE_ROLES_FROM_POINTS = setOf("直线", "线段", "射线")

/** 吸附半径占屏宽的比例（见 `GraphState.snapRadiusPx`）。 */
private const val SNAP_RADIUS_FRACTION = 0.06f

/** 吸附半径的像素下限（density 报到 1.0 的机型上屏宽比例会缩得太小）。 */
private const val MIN_SNAP_PX = 20f

/**
 * 原点 / 坐标轴的吸附半径占屏宽的比例（见 `GraphState.axisSnapRadiusPx`）。
 *
 * 比 [SNAP_RADIUS_FRACTION]（6%）小一截 —— 用户要求"吸附范围不要太大"，
 * 轴是无限长的线，吸得太宽会"路过就粘住"。
 */
private const val AXIS_SNAP_FRACTION = 0.035f

/** 原点 / 轴吸附半径的像素下限（小屏 / 低密度机型上别缩到吸不上）。 */
private const val MIN_AXIS_SNAP_PX = 10f

/**
 * 这个几何对象要不要出现在**函数列表**里。
 *
 * 只有变换出来的曲线折线碎片被排除（理由见 [FRAGMENT_ROLE]）；
 * 其它对象（点、线、度量）一律出现在列表里 —— 用户要求的"度量同步到函数列表里"
 * 就是靠这一点，而且列表里能改颜色、能删、能切数值显隐。
 */
internal val GraphObject.appearsInList: Boolean
    get() = !(this is GraphLineObject && role == FRAGMENT_ROLE)

/**
 * 「示例库」——手表上打字是最痛的环节，
 * 所以给一批能一键套用的有趣函数。
 *
 * **必须按坐标系分开**：笛卡尔的 `y=x^2/4` 在极坐标下画出来是乱线，
 * 极坐标的 `r=1-sin(θ)` 在笛卡尔下同理。两个坐标系各配 10 个。
 */
data class Preset(
    val label: String,
    val mode: PlotMode,
    val src1: String,
    val src2: String = "",
    val tMin: Double = 0.0,
    val tMax: Double = 2 * PI
)

/** 笛卡尔坐标系下的示例（隐函数、参数方程都在这里）。 */
val CartesianPresets: List<Preset> = listOf(
    Preset("正弦波", PlotMode.EXPLICIT, "sin(x)"),
    Preset("抛物线", PlotMode.EXPLICIT, "x^2/4"),
    Preset("高斯钟形", PlotMode.EXPLICIT, "exp(-x^2/2)"),
    Preset("双曲线一支", PlotMode.EXPLICIT, "1/x"),
    Preset("绝对值", PlotMode.EXPLICIT, "|x|-2"),
    Preset("三次曲线", PlotMode.EXPLICIT, "x^3/3-x"),
    Preset("单位圆", PlotMode.IMPLICIT, "x^2+y^2=1"),
    Preset("双曲线", PlotMode.IMPLICIT, "x^2-y^2=1"),
    Preset("笛卡尔叶", PlotMode.IMPLICIT, "x^3+y^3=3xy"),
    Preset("摆线", PlotMode.PARAMETRIC, "t-sin(t)", "1-cos(t)", tMax = 6 * PI)
)

/** 极坐标系下的示例。 */
val PolarPresets: List<Preset> = listOf(
    Preset("心形线", PlotMode.POLAR, "1-sin(θ)"),
    Preset("三叶玫瑰", PlotMode.POLAR, "cos(3θ)"),
    Preset("四叶玫瑰", PlotMode.POLAR, "cos(2θ)"),
    Preset("八叶玫瑰", PlotMode.POLAR, "cos(4θ)"),
    Preset("圆", PlotMode.POLAR, "2"),
    Preset("利马松", PlotMode.POLAR, "2+cos(θ)"),
    Preset("花瓣", PlotMode.POLAR, "1+2sin(θ)"),
    Preset("阿基米德螺线", PlotMode.POLAR, "θ/3", tMax = 6 * PI),
    Preset("对数螺线", PlotMode.POLAR, "exp(θ/5)", tMax = 4 * PI),
    Preset("利萨如", PlotMode.PARAMETRIC, "sin(3t)", "cos(4t)")
)

/** 取当前坐标系配套的示例。 */
fun presetsFor(cs: CoordinateSystem): List<Preset> =
    if (cs == CoordinateSystem.POLAR) PolarPresets else CartesianPresets
