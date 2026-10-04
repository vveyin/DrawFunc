package com.functiondrawer.watch.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.ui.graphics.Color

/**
 * Material Design 3 的色彩角色 —— 手工实现版。
 *
 * 为什么不直接用 `androidx.compose.material3`：
 *   本机的 Gradle 依赖缓存里**没有** material3 构件，而 Maven Central 的 TLS
 *   在这台机器上会被拦，为一个配色层去联网不值得。
 *   所以这里把 MD3 的角色色板自己实现一遍，再用**扩展属性**挂到
 *   Compose Material 2 的 [Colors] 上 —— 这样界面代码里写
 *   `MaterialTheme.colors.surfaceContainerHigh` 一样能编译、语义也一样，
 *   将来想换回官方 material3，只要把扩展属性删掉即可。
 *
 * 只做深色：表是 AMOLED 圆屏，纯黑既省电又不刺眼，
 * 而且 MD3 的深色角色在 1.5 寸屏上对比度最舒服。
 */
object WatchPalette {

    val primary = Color(0xFF7FD8FF)
    val onPrimary = Color(0xFF00344A)
    val primaryContainer = Color(0xFF004C69)
    val onPrimaryContainer = Color(0xFFC6E9FF)

    val secondary = Color(0xFFB4CAD8)
    val onSecondary = Color(0xFF1F333E)
    val secondaryContainer = Color(0xFF354A55)
    val onSecondaryContainer = Color(0xFFD0E6F4)

    val tertiary = Color(0xFFD0BCFF)
    val onTertiary = Color(0xFF36265B)
    val tertiaryContainer = Color(0xFF4D3D73)
    val onTertiaryContainer = Color(0xFFE9DDFF)

    val error = Color(0xFFFFB4AB)
    val onError = Color(0xFF690005)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFFFDAD6)

    val background = Color(0xFF000000)
    val onBackground = Color(0xFFE2E2E6)

    val surface = Color(0xFF0C0F11)
    val onSurface = Color(0xFFE2E2E6)
    val surfaceVariant = Color(0xFF3F484C)
    val onSurfaceVariant = Color(0xFFBFC8CD)

    // MD3 的 surface container 阶梯（越高越亮，用来表达层级/高度）
    val surfaceContainerLowest = Color(0xFF07090B)
    val surfaceContainerLow = Color(0xFF121517)
    val surfaceContainer = Color(0xFF181B1E)
    val surfaceContainerHigh = Color(0xFF222629)
    val surfaceContainerHighest = Color(0xFF2D3134)

    val outline = Color(0xFF899297)
    val outlineVariant = Color(0xFF3F484C)

    val inverseSurface = Color(0xFFE2E2E6)
    val inverseOnSurface = Color(0xFF2F3133)
    val scrim = Color(0xFF000000)
}

// ---------------------------------------------------------------------------
// 键盘按键专用配色 —— 参考 `D:\Culculator`（Java + XML 的手表计算器）的按键风格。
//
// 它那边整套按键风格就四个 drawable，取值直接照搬（见它的 res/drawable/）：
//
//   | 用途 | 取值 | 来源 |
//   |---|---|---|
//   | 普通键（数字、字母、括号） | `#2D2F33` | `bg_btn_dark.xml` |
//   | 强调键（函数名、RAD/DEG）  | `#4B586B` | `bg_btn_accent.xml` |
//   | 危险键（`⌫`）             | `#824C51` | `bg_btn_danger.xml` |
//   | 未选中页签文字             | `#CCFFFFFF` | `colors.xml: log_option_text` |
//   | 气泡本体                   | `#F21F2124` + 1dp `#33FFFFFF` 描边 | `bg_log_bubble.xml` |
//
// 风格要点是**扁平 + 低饱和 + 纯白字 + 一整颗胶囊**：
// 没有渐变、没有阴影、没有 elevation，层级全靠底色区分，按下去只有一层
// 13% 白的涟漪（`#22FFFFFF`）。
//
// 我们原来用的是 MD3 的 container 色（`#004C69` 深青、`#93000A` 深红），
// 饱和度偏高，一整屏按键看下来偏"重"；换成这套之后安静很多，
// 而且纯黑 AMOLED 底上深灰胶囊的边界更清楚。
// ---------------------------------------------------------------------------

object KeyPalette {
    /** 普通键底：和 Culculator 完全同一个值。 */
    val normal = Color(0xFF2D2F33)

    /**
     * **比 [normal] 亮一档的灰**：`#4A4D52` —— 用户点名要的那个"比设置键亮一号的灰"。
     *
     * 用在"上面要压一圈**灰色**线圈"的按钮上，目前只有**第一屏那颗 ⚙ 设置键**
     * （长按打开应用信息页，线圈就是它）。用户原话：
     *
     * > "给长按设置键加个长按 `+` 键同款动画，**线圈颜色用比设置亮一号颜色的灰色**。"
     *
     * 也就是：底色**一点不动**（设置键仍然是 [normal] `#2D2F33`），只把**线圈**换成
     * "比它亮一号的灰" —— 和 `+` 键那一处的做法完全同构
     * （`+` 键是"底色 [accentDeep]、线圈 [accent]"）。
     *
     * ## 把"亮一号"落到数上
     *
     * 相对亮度按 WCAG 那条 sRGB 公式算（本仓库里 [accentDeep] 的注释用的也是这一套，
     * 只是那边把"亮度比"写成"对比度"；这里两个都列出来，免得再被读混）：
     *
     * | | 取值 | 相对亮度 | 亮度比 | WCAG 对比度 |
     * |---|---|---|---|---|
     * | 设置键底色（[normal]） | `#2D2F33` | 0.028 | —— | —— |
     * | **本值（⚙ 的线圈）** | `#4A4D52` | **0.074** | **2.6 ×** | **1.58 : 1** |
     * | 参照：`+` 键底色（[accentDeep]） | `#333E4D` | 0.047 | —— | —— |
     * | 参照：`+` 键线圈（[accent]） | `#4B586B` | 0.095 | 2.0 × | 1.50 : 1 |
     *
     * 所以它是"同一支中性灰、亮度抬到约 **2.6 倍**"的那一档：读起来就是灰的上一格。
     * 和 `+` 那一对（2.0 倍 / 1.50:1）是**同一个量级、还略强一点点** ——
     * 两颗键的长按确认因此看起来是同一档强度，只是颜色一个灰、一个蓝。
     * （略强是有意的：`+` 那颗键本身是强调底色、线圈只是"再加一圈"，
     * 而 ⚙ 是普通深灰底，线圈是它**唯一**的"正在确认"提示。）
     * 同时它在**绝对亮度**上比 [accent] 暗一档（0.074 vs 0.095），
     * 所以不会让人以为"⚙ 长按 = `+` 那个蓝色动作"。
     *
     * ## 为什么不用别的现成颜色（都试过、都不对）
     *
     *   * [accent]（`#4B586B`）／`WatchPalette.primary`（`#7FD8FF`）：用户要的是**灰色**，
     *     这两个都是 200° 以上的蓝，压在设置键上会把"长按 ⚙"染成和 `+` 同一套语言；
     *   * `WatchPalette.surfaceContainerHighest`（`#2D3134`）：和 [normal] 几乎同亮度
     *     （差 0.001），线圈转起来**看不见**；
     *   * `WatchPalette.surfaceVariant` / `outlineVariant`（`#3F484C`）：偏蓝的灰
     *     （B 比 R 高 13），在纯黑表上会读出一点青色，正是用户没要的那种；
     *   * `WatchPalette.outline`（`#899297`）：太亮，线圈会读成"白色描边"，
     *     亮度比按钮高 4 倍以上，和"亮一号"不是一回事。
     *
     * ## 已知取舍（真机上如果要调，只改这一个常量）
     *
     * 线圈是**满档**画在 [normal] 底上的（`HoldToConfirm` 的 `ringColor` 不做任何
     * alpha 处理），1.58 : 1 在纯黑环境的手表上足够看出"在转圈"。
     * 它唯一"弱"的地方是**绝对亮度**（0.074）比 `+` 那颗线圈（0.095）低一档 ——
     * 中性灰能到的亮度天然比同感知强度的蓝低一点。真机上若觉得不够跳，
     * 把它提到 `#52555A`（亮度 ≈ 0.090，和 `+` 线圈持平；对比度 1.79 : 1）即可 ——
     * **三处调用点（本文件、`FunctionList` 的 ⚙、`HoldRing` 的默认参数）都不用动**，
     * 因为线圈颜色只有一个来源：这个常量。
     */
    val normalBright = Color(0xFF4A4D52)

    /** 强调键底（函数名、角度制开关、选中的页签）。 */
    val accent = Color(0xFF4B586B)

    /**
     * **暗一档的强调蓝**（同一色相、亮度压到约 1/2）。
     *
     * 用在"上面要压一圈强调色线圈"的按钮上 —— 目前只有第一屏那颗 `+`
     * （长按保存文件，线圈就是 [accent]）。
     *
     * 用户原话："长按 + 键的线圈和 + 键自己的颜色没区别，把 + 键像删除键一样改成
     * 暗一点的色。" → 后来进一步定了颜色："+ 键用暗一点的蓝色，风格和软件整体一致。"
     *
     * 为什么是这样一个值（而不是深灰或砖红）：
     *   * **同色相**：#4B586B 的色相约 216°，这里保持 216° 只把亮度压下来
     *     （HSL 亮度 36% → 25%），所以它读起来是"同一个蓝色家族的暗调"，
     *     和整屏的蓝灰主色（[accent]、`WatchPalette.primary` #7FD8FF）是一套语言 ——
     *     这正是用户要的"风格和软件整体一致"；
     *   * **和线圈分得开**：相对亮度 ≈0.045 vs 线圈的 ≈0.095（对比度约 2.1:1），
     *     加上线圈本身是**亮一档的同色**，压在按钮上时边界清楚；
     *   * **比深灰更有主次**：`+` 是这一屏的主操作，用暗蓝而不是 `normal`（#2D2F33）
     *     的深灰，能和左边的 ⚙ 区分开，同时又不至于亮到和线圈糊在一起。
     */
    val accentDeep = Color(0xFF333E4D)

    /** 危险键底（`⌫`）。比原来那个 `#93000A` 柔和得多。 */
    val danger = Color(0xFF824C51)

    /**
     * 危险键**压暗一档**的透明度（0.45）—— **两屏共用这一个数**。
     *
     * 为什么要有它：同一个"暗红"档出现在两个屏幕上，各写一个 `0.45f` 迟早会漂
     * （"文件列表的删除键颜色和功能界面不一样"这类问题就是这么来的）。两处的语义各有
     * 各的，但"暗到哪一档"必须是同一个数：
     *   * `FunctionsScreen`（功能界面右上角「清屏」）：`objectCount > 0` 时满档 1f、
     *     否则这一档，中间 180ms 渐变（画完第一个对象 / 清完屏时那颗键会亮/暗过去）；
     *   * `SavesScreen`（「函数文件」多选模式右上角「删除」）：**常驻这一档**
     *     （用户原话："文件列表的删除键的颜色没有和功能界面的一样变成暗红色"）。
     */
    const val DANGER_DIM_ALPHA = 0.45f

    /**
     * 危险键的**暗档底色** = [danger] 乘 [DANGER_DIM_ALPHA]。
     *
     * 压在纯黑底（`WatchPalette.background`）上合成出来约 **#3A2224** ——
     * 就是用户嘴里"暗红色"的那一档（满档 [danger] 在纯黑上就是 #824C51 本身）。
     *
     * 为什么用"同一个色相的透明度"而不是再写一个十六进制（对比 [accentDeep]）：
     * accent 那边要的是**不透明**的暗调（线圈压在底色上、两块都是不透明色），
     * 危险键这边只需要"压暗"，用 alpha 表达天然只有一个来源（[danger]），
     * 不会再多出第二个红色来跟它漂。
     *
     * ⚠️ 顺带也是**长按线圈能看清**的前提：`HoldToConfirm` 的 `ringColor` 用的是满档
     * [danger]，底色只要亮到满档，线圈就和底色同色、按住时看不出在转圈
     * （`+` 键那一轮踩过同一个坑，那边的解法是 [accentDeep]）。
     */
    val dangerDim: Color get() = danger.copy(alpha = DANGER_DIM_ALPHA)

    /**
     * 危险键"**可操作时亮起 / 不可操作时压暗**"两档的**目标透明度**。
     *
     * 目前只有功能界面那颗「清屏」用它（当 `animateFloatAsState` 的 `targetValue`）；
     * 「函数文件」那颗是常驻 [dangerDim]、不走这条两档动画（见 `SavesScreen` 里的说明）。
     */
    fun dangerAlpha(active: Boolean): Float = if (active) 1f else DANGER_DIM_ALPHA

    /** 按键文字：纯白（原来是 `#E2E2E6` 那种偏灰的白）。 */
    val text = Color(0xFFFFFFFF)

    /** 未选中页签的文字：80% 白，做出层级但仍然是"白字"。 */
    val textMuted = Color(0xCCFFFFFF)

    /** 长按变体气泡的本体：近黑（95% 不透明），和 Culculator 的日志气泡一致。 */
    val bubble = Color(0xF21F2124)

    /** 气泡描边：1dp、20% 白 —— 浮在深色界面上时勾出边界。 */
    val bubbleBorder = Color(0x33FFFFFF)
}

// ---------------------------------------------------------------------------
// M2 的 Colors 只有 12 个槽位，MD3 多出来的角色用扩展属性补上。
// 注意：primary / secondary / background / surface / error / onXxx 这些
// M2 本来就有的不要重复定义，否则会遮挡原有成员。
// ---------------------------------------------------------------------------

val Colors.onSurfaceVariant: Color get() = WatchPalette.onSurfaceVariant
val Colors.surfaceVariant: Color get() = WatchPalette.surfaceVariant

val Colors.primaryContainer: Color get() = WatchPalette.primaryContainer
val Colors.onPrimaryContainer: Color get() = WatchPalette.onPrimaryContainer
val Colors.secondaryContainer: Color get() = WatchPalette.secondaryContainer
val Colors.onSecondaryContainer: Color get() = WatchPalette.onSecondaryContainer
val Colors.tertiary: Color get() = WatchPalette.tertiary
val Colors.onTertiary: Color get() = WatchPalette.onTertiary
val Colors.tertiaryContainer: Color get() = WatchPalette.tertiaryContainer
val Colors.onTertiaryContainer: Color get() = WatchPalette.onTertiaryContainer
val Colors.errorContainer: Color get() = WatchPalette.errorContainer
val Colors.onErrorContainer: Color get() = WatchPalette.onErrorContainer

val Colors.surfaceContainerLowest: Color get() = WatchPalette.surfaceContainerLowest
val Colors.surfaceContainerLow: Color get() = WatchPalette.surfaceContainerLow
val Colors.surfaceContainer: Color get() = WatchPalette.surfaceContainer
val Colors.surfaceContainerHigh: Color get() = WatchPalette.surfaceContainerHigh
val Colors.surfaceContainerHighest: Color get() = WatchPalette.surfaceContainerHighest

val Colors.outline: Color get() = WatchPalette.outline
val Colors.outlineVariant: Color get() = WatchPalette.outlineVariant
val Colors.inverseSurface: Color get() = WatchPalette.inverseSurface
val Colors.inverseOnSurface: Color get() = WatchPalette.inverseOnSurface
val Colors.scrim: Color get() = WatchPalette.scrim

/**
 * 构造 M2 的 [Colors]。M2 自带组件（如果有用到）会读这些槽位，
 * 所以要把 MD3 角色映射到 M2 的语义槽上，避免出现"半黑半灰"的违和感。
 */
fun watchColors(): Colors = darkColors(
    primary = WatchPalette.primary,
    primaryVariant = WatchPalette.primaryContainer,
    secondary = WatchPalette.secondary,
    secondaryVariant = WatchPalette.secondaryContainer,
    background = WatchPalette.background,
    surface = WatchPalette.surface,
    error = WatchPalette.error,
    onPrimary = WatchPalette.onPrimary,
    onSecondary = WatchPalette.onSecondary,
    onBackground = WatchPalette.onBackground,
    onSurface = WatchPalette.onSurface,
    onError = WatchPalette.onError
)
