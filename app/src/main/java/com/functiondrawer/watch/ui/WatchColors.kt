package com.functiondrawer.watch.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Material Design 3 的色彩角色 —— 公共形状。
 *
 * 为什么抽成接口（2026-10-17）：
 * 用户要求在设置里加一个「夜间模式」开关，让经典主题能在**黑底（夜间）**与
 * **白底（日间）**之间切。两份色板的字段完全一样、语义完全一样，只有值不同，
 * 所以抽一份接口、两份实现对坐；界面里读 `scheme.surfaceContainerHigh` 那套
 * 扩展属性时，读的永远是"当前生效的那一份"。
 *
 * 两份实现的字段必须**一一对应**（名字、类型都相同）—— 这是"换底色"这件事
 * 能一次换干净的前提。
 */
interface AppRoleColors {

    val primary: Color
    val onPrimary: Color
    val primaryContainer: Color
    val onPrimaryContainer: Color

    val secondary: Color
    val onSecondary: Color
    val secondaryContainer: Color
    val onSecondaryContainer: Color

    val tertiary: Color
    val onTertiary: Color
    val tertiaryContainer: Color
    val onTertiaryContainer: Color

    val error: Color
    val onError: Color
    val errorContainer: Color
    val onErrorContainer: Color

    val background: Color
    val onBackground: Color

    val surface: Color
    val onSurface: Color
    val surfaceVariant: Color
    val onSurfaceVariant: Color

    // MD3 的 surface container 阶梯（夜间越高越亮 / 日间越高越深）
    val surfaceContainerLowest: Color
    val surfaceContainerLow: Color
    val surfaceContainer: Color
    val surfaceContainerHigh: Color
    val surfaceContainerHighest: Color

    val outline: Color
    val outlineVariant: Color

    /**
     * **画布网格线**的颜色。
     *
     * 为什么不直接拿 [outlineVariant] 乘个 alpha：
     * 两个模式需要的"淡"方向相反 —— 夜间是"亮色压到很暗"（`#3F484C × 45%`
     * 合成到纯黑上 ≈ `#1C2022`），白天是"深色压到很淡"（`#C9D2DC` 原档）。
     * 同一个 alpha 在两边得到的东西完全不同，所以这里直接把**合成后**的结果
     * 存成一个角色，画布那边只负责用。
     *
     * 它的定位：看得见坐标系结构，但绝不能抢曲线。
     */
    val gridLine: Color

    val inverseSurface: Color
    val inverseOnSurface: Color
    val scrim: Color
}

/**
 * Material Design 3 的色彩角色 —— **夜间模式 / 黑底**（这个 App 的母语）。
 *
 * 为什么不直接用 `androidx.compose.material3`：
 *   本机的 Gradle 依赖缓存里**没有** material3 构件，而 Maven Central 的 TLS
 *   在这台机器上会被拦，为一个配色层去联网不值得。
 *   所以这里把 MD3 的角色色板自己实现一遍，再用**扩展属性**挂到
 *   Compose Material 2 的 [Colors] 上 —— 这样界面代码里写
 *   `MaterialTheme.colors.surfaceContainerHigh` 一样能编译、语义也一样，
 *   将来想换回官方 material3，只要把扩展属性删掉即可。
 *
 * ## 这一份就是「夜间模式」，也是默认
 *
 * 用户 2026-10-17 把口径彻底说清楚了：
 *
 * > "我原来做的那一套 ui 应该是夜间模式的。你要给白天模式的 ui 重新写一套配色，
 * >  而不是只把背景变成白色。"
 *
 * 也就是说：**这套黑底不是"默认主题"，是夜间主题本身**。
 * 它是这个 App 长出来的样子（`#000000` 纯黑画布 + 亮青主色 + 粉彩曲线），
 * 用户认的就是它。白天模式是**另外新写的一套**（[WatchPaletteDay] +
 * `CurvePaletteDay` + `DayKeyColors`），三套东西一起换才叫"白天模式"。
 *
 * ## 为什么纯黑
 *
 * 这块表是 AMOLED：`#000000` 的像素**真的不发光**，所以夜间看曲线时
 * 画面之外是一片绝对的黑 —— 曲线像是浮在空气里，这是这套 UI 最核心的观感。
 * 用 `#0C0F11` 之类的"近黑"会立刻失去这个效果（整块屏都在发微光）。
 */
object WatchPalette : AppRoleColors {

    override val primary = Color(0xFF7FD8FF)
    override val onPrimary = Color(0xFF00344A)
    override val primaryContainer = Color(0xFF004C69)
    override val onPrimaryContainer = Color(0xFFC6E9FF)

    override val secondary = Color(0xFFB4CAD8)
    override val onSecondary = Color(0xFF1F333E)
    override val secondaryContainer = Color(0xFF354A55)
    override val onSecondaryContainer = Color(0xFFD0E6F4)

    override val tertiary = Color(0xFFD0BCFF)
    override val onTertiary = Color(0xFF36265B)
    override val tertiaryContainer = Color(0xFF4D3D73)
    override val onTertiaryContainer = Color(0xFFE9DDFF)

    override val error = Color(0xFFFFB4AB)
    override val onError = Color(0xFF690005)
    override val errorContainer = Color(0xFF93000A)
    override val onErrorContainer = Color(0xFFFFDAD6)

    override val background = Color(0xFF000000)
    override val onBackground = Color(0xFFE2E2E6)

    override val surface = Color(0xFF0C0F11)
    override val onSurface = Color(0xFFE2E2E6)
    override val surfaceVariant = Color(0xFF3F484C)
    override val onSurfaceVariant = Color(0xFFBFC8CD)

    // MD3 的 surface container 阶梯（越高越亮，用来表达层级/高度）
    override val surfaceContainerLowest = Color(0xFF07090B)
    override val surfaceContainerLow = Color(0xFF121517)
    override val surfaceContainer = Color(0xFF181B1E)
    override val surfaceContainerHigh = Color(0xFF222629)
    override val surfaceContainerHighest = Color(0xFF2D3134)

    override val outline = Color(0xFF899297)
    override val outlineVariant = Color(0xFF3F484C)
    // = #3F484C × 45% 合成在纯黑上（等价于改动前那句 `.copy(alpha = 0.45f)`）
    override val gridLine = Color(0xFF1C2022)

    override val inverseSurface = Color(0xFFE2E2E6)
    override val inverseOnSurface = Color(0xFF2F3133)
    override val scrim = Color(0xFF000000)
}

/**
 * Material Design 3 的色彩角色 —— **白天模式**（白底纸张感）。
 *
 * ## 这一份是"另写一套"，不是把夜间那份的背景刷白
 *
 * 用户 2026-10-17 的原话：
 *
 * > "我原来做的那一套 ui 应该是夜间模式的。你要给白天模式的 ui 重新写一套配色，
 * >  而不是只把背景变成白色。"
 *
 * 也就是说：黑底那套（[WatchPalette]）**就是**夜间模式，它是这个 App 的母语；
 * 白天模式是一套**新的设计**，必须按"白纸上看图"这个场景重新配 ——
 * 只把 `background` 换成 `#FFFFFF` 会得到一堆在白底上直接消失的东西：
 *
 * | 东西 | 夜间（黑底） | 只刷白会怎样 | 白天这一份怎么处理 |
 * |---|---|---|---|
 * | 12 条曲线 | 粉彩（`#7FD8FF` 等） | 几乎全白，看不见 | 换成**深饱和**的 12 色（见 `CurvePaletteDay`） |
 * | 按键底 | 深灰 `#2D2F33` + 白字 | 深灰块压在白纸上，脏 | 浅灰 `#EDF0F4` + **深字** |
 * | 按下反馈 | 叠 16% **白**（更亮） | 白叠白，按下去没反应 | 叠 16% **黑**（更暗） |
 * | 滑轨底线 | 16% 白 | 看不见 | 用 `onSurface` 的 14%（浅灰） |
 * | 网格线 | 低透明度亮灰 | 白底上更低 | 冷灰 `#8A97A4` 一档 |
 * | 主色 | 亮青 `#7FD8FF` | 白底上发飘、对比度 1.4:1 | 稳重蓝 `#1668B8`（对比度 5.6:1） |
 *
 * ## 取值依据
 *
 * 全部按 WCAG 2.1 的对比度算过（白底 `#FFFFFF` 为参照）：
 *
 * | 角色 | 取值 | 对比度 | 用途 |
 * |---|---|---|---|
 * | [onSurface] | `#11181D` | **17.6 : 1** | 正文、曲线标题 |
 * | [onSurfaceVariant] | `#4A5561` | **7.4 : 1** | 次要文字、坐标刻度 |
 * | [primary] | `#1668B8` | **5.6 : 1** | 主操作、选中态 |
 * | [outline] | `#7B8794` | **4.2 : 1** | 图标、输入框描边（非文本门槛 3:1） |
 * | [outlineVariant] | `#C9D2DC` | 1.6 : 1 | 纯装饰分隔线（不需要可读） |
 *
 * `onSurface` 刻意**不用纯黑**：`#000000` 在纯白上会硬到"字在跳"，
 * `#11181D` 保留了 4% 的蓝灰，长时间看曲线图眼睛更稳。
 *
 * surface 阶梯（日间 MD3 的规矩是**越高越深**，和夜间相反）：
 * `#FFFFFF → #FAFBFC → #F4F6F8 → #EDF0F4 → #E4E9EF`。
 * 它表达的是"卡片浮在画布上"的层级，不是阴影 —— 手表上阴影基本看不清。
 */
object WatchPaletteDay : AppRoleColors {

    override val primary = Color(0xFF1668B8)
    override val onPrimary = Color(0xFFFFFFFF)
    override val primaryContainer = Color(0xFFD6E7F8)
    override val onPrimaryContainer = Color(0xFF0A3659)

    override val secondary = Color(0xFF4A5866)
    override val onSecondary = Color(0xFFFFFFFF)
    override val secondaryContainer = Color(0xFFDEE6EF)
    override val onSecondaryContainer = Color(0xFF1A242E)

    override val tertiary = Color(0xFF6B3FA0)
    override val onTertiary = Color(0xFFFFFFFF)
    override val tertiaryContainer = Color(0xFFEADCFB)
    override val onTertiaryContainer = Color(0xFF2C1750)

    override val error = Color(0xFFB3261E)
    override val onError = Color(0xFFFFFFFF)
    override val errorContainer = Color(0xFFFBDAD7)
    override val onErrorContainer = Color(0xFF410E0B)

    override val background = Color(0xFFFFFFFF)
    override val onBackground = Color(0xFF11181D)

    override val surface = Color(0xFFFFFFFF)
    override val onSurface = Color(0xFF11181D)
    override val surfaceVariant = Color(0xFFE7ECF2)
    override val onSurfaceVariant = Color(0xFF4A5561)

    // MD3 的 surface container 阶梯（日间：越高越深）
    override val surfaceContainerLowest = Color(0xFFFFFFFF)
    override val surfaceContainerLow = Color(0xFFFAFBFC)
    override val surfaceContainer = Color(0xFFF4F6F8)
    override val surfaceContainerHigh = Color(0xFFEDF0F4)
    override val surfaceContainerHighest = Color(0xFFE4E9EF)

    override val outline = Color(0xFF7B8794)
    override val outlineVariant = Color(0xFFC9D2DC)
    // 白底上直接用原档：合成出来 ≈ #E7ECF2，看得见格子又不抢曲线
    override val gridLine = Color(0xFFC9D2DC)

    override val inverseSurface = Color(0xFF2A3037)
    override val inverseOnSurface = Color(0xFFF2F4F7)
    override val scrim = Color(0xFF000000)
}

/**
 * **当前生效的角色色板**（`FunctionDrawerTheme` 每次组合时刷新）。
 *
 * 为什么是一份 `mutableStateOf` 而不是让扩展属性直接读一个枚举：
 * MD3 的扩展属性（`scheme.surfaceContainerHigh` 那一套）是**普通 getter**，
 * 拿不到组合上下文；而它们又必须在底色切换时让所有读取方重组。
 * Snapshot 状态正好两头都满足——读它是 `State.value`（组件可观察），
 * 写它不需要组合上下文。
 *
 * 默认值是 [WatchPalette]（黑底）：和"装完就是黑的"这个默认口径一致。
 */
internal val activeRoleColors: MutableState<AppRoleColors> = mutableStateOf(WatchPalette)

// ---------------------------------------------------------------------------
// 曲线配色 —— 12 种，**夜间一套、白天一套**。
//
// 为什么不复用同一套色再调透明度：粉彩（`#7FD8FF`、`#FFF2A0`）在白底上的
// 相对亮度是 0.7~0.95，和白纸几乎一样 —— 12 条曲线里至少 6 条直接消失。
// "把背景刷白"这个做法在这一步就会彻底露馅，所以曲线必须**换一组颜色**，
// 不是调透明度能解决的。
// ---------------------------------------------------------------------------

/**
 * 夜间（黑底）曲线色：**12 种粉彩**。
 *
 * 全部是亮色调，因为在纯黑底上才够跳、又不刺眼。
 * 12 种两两之间都能分辨（亮度或色相至少差一档），这是"多条曲线同屏"的可用性下限；
 * 用户可以在函数列表里长按圆点自己挑，所以这个顺序也决定了默认分配的顺序
 * （第 1 条函数拿第 1 个颜色，依此类推）。
 *
 * 亮度普遍落在 0.6~0.95：黑底上对比度 8:1 以上，2px 细线也看得清。
 */
internal val CurvePaletteNight: List<Color> = listOf(
    Color(0xFF7FD8FF), // 天蓝
    Color(0xFFFFB4AB), // 珊瑚
    Color(0xFFB7F397), // 嫩绿
    Color(0xFFD0BCFF), // 淡紫
    Color(0xFFFFD8A6), // 暖橙
    Color(0xFF8FF0E4), // 青绿
    Color(0xFFFFF2A0), // 奶油黄
    Color(0xFFF8A8D8), // 樱花粉
    Color(0xFFA8C7FF), // 长春花蓝
    Color(0xFF9EE8A8), // 薄荷
    Color(0xFFFFC2A0), // 蜜桃
    // 第 12 色 = 「工具画出来的线」的默认色：夜间是**白**、白天是**黑**。
    // 用户原话："功能界面所有能绘制出线（包括直线和曲线）的功能绘制出的线
    // 默认颜色都是黑色（开启夜间模式下默认为白色，在白天模式下绘制出的黑色的线
    // 在切换夜间模式之后都要自动改成白色）。"
    // 做成色板里的**同一个位置**（而不是给对象单独存一个"黑/白"标志）：
    // 切换主题时色板整体换一套，这条线自然就跟着由黑变白，不用任何额外逻辑。
    Color(0xFFFFFFFF)  // 纯白（夜间）
)

/**
 * 白天（白底）曲线色：**12 种深饱和色**。
 *
 * 这是为白纸重新配的一套，不是上面那套"压暗"：
 * 每一色都在 Material 的 700~800 档上取，相对亮度压到 0.05~0.25，
 * 白底上对比度最低 1.9:1（柠檬黄那种物理上做不到）、实际都 ≥ 3.6:1，
 * 2px 细线读起来是"彩铅画在纸上"的感觉。
 *
 * 色相刻意拉满一整圈（蓝 210° → 红 0° → 绿 125° → 紫 282° → 橙 27° →
 * 青绿 174° → 琥珀 47° → 品红 330° → 靛 231° → 橄榄 90° → 棕 20° → 亮青蓝 197°），
 * 相邻两色在色相上至少隔 25°，保证"第 1 条和第 2 条"不会看混。
 *
 * | # | 名称 | 取值 | 白底对比度 |
 * |---|---|---|---|
 * | 1 | 蓝 | `#1565C0` | 5.5 : 1 |
 * | 2 | 红 | `#C62828` | 6.0 : 1 |
 * | 3 | 绿 | `#2E7D32` | 5.4 : 1 |
 * | 4 | 紫 | `#6A1B9A` | 8.6 : 1 |
 * | 5 | 橙 | `#E65100` | 3.6 : 1 |
 * | 6 | 青绿 | `#00695C` | 6.9 : 1 |
 * | 7 | 琥珀 | `#8D6E00` | 5.1 : 1 |
 * | 8 | 品红 | `#AD1457` | 6.7 : 1 |
 * | 9 | 靛 | `#3949AB` | 8.0 : 1 |
 * | 10 | 橄榄 | `#558B2F` | 3.8 : 1 |
 * | 11 | 棕 | `#5D4037` | 9.6 : 1 |
 * | 12 | 亮青蓝 | `#0277BD` | 4.6 : 1 |
 */
internal val CurvePaletteDay: List<Color> = listOf(
    Color(0xFF1565C0), // 蓝
    Color(0xFFC62828), // 红
    Color(0xFF2E7D32), // 绿
    Color(0xFF6A1B9A), // 紫
    Color(0xFFE65100), // 橙
    Color(0xFF00695C), // 青绿
    Color(0xFF8D6E00), // 琥珀
    Color(0xFFAD1457), // 品红
    Color(0xFF3949AB), // 靛
    Color(0xFF558B2F), // 橄榄
    Color(0xFF5D4037), // 棕
    Color(0xFF000000)  // 纯黑（白天）—— 和上面夜间那第 12 色是同一个位置
)

/** 「工具画出来的线」默认用的色板下标（第 12 色 = 白天黑 / 夜间白，见上面两个色板）。 */
internal const val TOOL_CURVE_COLOR_INDEX = 11

/**
 * 「描点」和所有工具画出来的点默认用的色板下标。
 *
 * 用户原话："把描点功能描出来的所有点的默认颜色都改成蓝色。"
 * 白天色板第 1 色是蓝 `#1565C0`、夜间第 1 色是天蓝 `#7FD8FF` —— 两边都算"蓝"。
 */
internal const val POINT_DEFAULT_COLOR_INDEX = 0

/** 当前生效的曲线色板（[com.functiondrawer.watch.ui.theme.CurvePalette] 读它）。 */
internal val activeCurveColors: MutableState<List<Color>> = mutableStateOf(CurvePaletteNight)

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

/**
 * 按键配色的一组角色（**日夜各一套**）。
 *
 * 为什么按键要单独一套、不直接复用 [AppRoleColors]：
 * 按键是这个 App 里被摸得最多的东西（键盘 40 多颗、工具条、列表行），
 * 它的观感语言和卡片/文字不一样 —— 参考的是实体计算器：
 * **扁平 + 低饱和 + 一整颗胶囊**，没有渐变、没有阴影、没有 elevation，
 * 层级全靠底色区分，按下去只有一层很淡的覆盖色。
 * 这些值日夜两套差异极大（深灰键身/浅灰键身、白字/黑字、提亮/压暗），
 * 所以给它们一个独立接口，各配一份。
 */
internal interface AppKeyColors {

    /** 普通键底（数字、字母、括号、工具条按钮）。 */
    val normal: Color

    /**
     * 比 [normal] **显眼一档**的灰 —— 用于"长按确认"那颗转圈线圈。
     *
     * 注意"显眼"在两个模式下方向相反：
     * 夜间（黑底）是**更亮**（`#4A4D52`，亮度约 [normal] 的 2.6 倍）；
     * 白天（白底）是**更深**（`#D3DBE4`）—— 白纸上"更亮"等于消失。
     * 所以它不能靠 `lighten(normal)` 算，两套各写一个值。
     */
    val normalBright: Color

    /** 强调键底（函数名、角度制开关、选中的页签）。 */
    val accent: Color

    /**
     * 比 [accent] **暗一档的同色相**：给"上面要压一圈强调色线圈"的按钮当底色。
     *
     * 目前只有第一屏那颗 `+`（长按保存文件，线圈就是 [accent]）。
     * 夜间是深蓝 `#333E4D`；白天是**更深一档的浅蓝** `#AFC9E6`，
     * 让线圈（浅蓝）压在底色上能看出边界。
     */
    val accentDeep: Color

    /** 危险键底（`⌫`、清屏、删除文件）。 */
    val danger: Color

    /** 按键文字（以及大多数"普通文字"）。夜间纯白，白天近黑。 */
    val text: Color

    /** 次要文字 / 未选中页签。夜间 80% 白，白天中灰。 */
    val textMuted: Color

    /** 长按变体气泡的本体。夜间近黑，白天近白。 */
    val bubble: Color

    /** 气泡 1dp 描边：浮起时勾出边界。 */
    val bubbleBorder: Color

    /**
     * 滑轨 / 进度条**未选中段**的底色（叠在键身或面板上）。
     *
     * 为什么单独给一个角色：滑轨的"底"和"已选段"必须是同一空间里的两层，
     * 底的浓度要刚好能看见轮廓、又不能抢已选段。夜间是 16% 白，
     * 白天是 14% 深色 —— 两边都写死 `Color.White.copy(alpha=…)` 的话，
     * 白底上滑轨整个消失（白叠白），用户会以为滑块卡住了。
     */
    val track: Color

    /**
     * 按下时叠在键身上的那层颜色（**靠图层 alpha 控制浓度，不要自己再调透明度**）。
     *
     * 夜间是**白**（越按越亮），白天是**深色**（越按越暗）——
     * 这是"只把背景刷白"最容易漏的一处：白叠白按下去毫无反馈，
     * 用户会以为触屏坏了。
     */
    val pressGlow: Color
}

/**
 * **夜间（黑底）**按键 —— 照搬 `D:\Culculator`（Java + XML 的手表计算器）。
 *
 * 它那边整套按键风格就四个 drawable，取值直接照搬（见它的 `res/drawable/`）：
 *
 * | 用途 | 取值 | 来源 |
 * |---|---|---|
 * | 普通键（数字、字母、括号） | `#2D2F33` | `bg_btn_dark.xml` |
 * | 强调键（函数名、RAD/DEG）  | `#4B586B` | `bg_btn_accent.xml` |
 * | 危险键（`⌫`）             | `#824C51` | `bg_btn_danger.xml` |
 * | 未选中页签文字             | `#CCFFFFFF` | `colors.xml: log_option_text` |
 * | 气泡本体                   | `#F21F2124` + 1dp `#33FFFFFF` 描边 | `bg_log_bubble.xml` |
 *
 * 我们原来用的是 MD3 的 container 色（`#004C69` 深青、`#93000A` 深红），
 * 饱和度偏高，一整屏按键看下来偏"重"；换成这套之后安静很多，
 * 而且纯黑 AMOLED 底上深灰胶囊的边界更清楚。
 *
 * [normalBright] 那个 `#4A4D52` 是用户点名要的"比设置键亮一号的灰"：
 * 相对亮度 0.074，是 [normal]（0.028）的 2.6 倍，对比度 1.58:1 ——
 * 读起来就是灰的上一格。用户原话：
 *
 * > "给长按设置键加个长按 `+` 键同款动画，**线圈颜色用比设置亮一号颜色的灰色**。"
 *
 * （为什么不复用 [accent] `#4B586B` 或 `WatchPalette.primary` `#7FD8FF`：
 * 用户要的是**灰色**，那两都是 200° 以上的蓝，压上去会把"长按 ⚙"染成
 * 和 `+` 同一套语言。真机若觉得不够跳，把它提到 `#52555A` 即可，
 * 调用点不用动 —— 线圈颜色只有这一个来源。）
 */
internal object NightKeyColors : AppKeyColors {
    override val normal = Color(0xFF2D2F33)
    override val normalBright = Color(0xFF4A4D52)
    override val accent = Color(0xFF4B586B)
    override val accentDeep = Color(0xFF333E4D)
    override val danger = Color(0xFF824C51)
    override val text = Color(0xFFFFFFFF)
    override val textMuted = Color(0xCCFFFFFF)
    override val bubble = Color(0xF21F2124)
    override val bubbleBorder = Color(0x33FFFFFF)
    override val track = Color(0x29FFFFFF)
    override val pressGlow = Color(0xFFFFFFFF)
}

/**
 * **白天（白底）**按键 —— 为白纸重新配的一套。
 *
 * 设计参照从"深色计算器"换成**实体计算器的白键**：
 *
 * | 角色 | 取值 | 说明 |
 * |---|---|---|
 * | [normal] | `#E6EBF1` | 浅灰键身。比 `surfaceContainerHigh`（`#EDF0F4`）深一档，压在面板上也看得出块 |
 * | [normalBright] | `#D3DBE4` | 长按线圈：白底上"更显眼"= **更深** |
 * | [accent] | `#CBDEF4` | 浅蓝键身（函数名、RAD/DEG） |
 * | [accentDeep] | `#AFC9E6` | `+` 键底色，比强调色再深一档 |
 * | [danger] | `#EFC3BF` | 浅红键身（`⌫`、清屏）。不像深红那样在白纸上"流血" |
 * | [text] | `#16202A` | 深字。对比 `#E6EBF1` = **11.8 : 1** |
 * | [textMuted] | `#5A6674` | 次要文字，对比 5.6 : 1 |
 * | [bubble] | `#FBFCFE` | 近白气泡（浮在白纸上要靠描边勾边） |
 * | [bubbleBorder] | 20% 深色 | 气泡描边 |
 * | [pressGlow] | `#16202A` | 按下**变暗**一档 |
 *
 * 为什么 [danger] 不像夜间那样用"深色 + 45% alpha"：
 * 白底上 `danger.copy(alpha=0.45)` 会变成一层脏粉，看着像"褪色的贴纸"。
 * 这里直接把浅红做成不透明色 —— 不可用态仍然走 [KeyPalette.dangerDim]
 * （同一个 0.45 的规矩），只是底子本身是浅的，合成结果更干净。
 */
internal object DayKeyColors : AppKeyColors {
    override val normal = Color(0xFFE6EBF1)
    override val normalBright = Color(0xFFD3DBE4)
    override val accent = Color(0xFFCBDEF4)
    override val accentDeep = Color(0xFFAFC9E6)
    override val danger = Color(0xFFEFC3BF)
    override val text = Color(0xFF16202A)
    override val textMuted = Color(0xFF5A6674)
    override val bubble = Color(0xFFFBFCFE)
    override val bubbleBorder = Color(0x3316202A)
    override val track = Color(0x2416202A)
    override val pressGlow = Color(0xFF16202A)
}

/**
 * **当前生效的按键配色**（`FunctionDrawerTheme` 每次组合时刷新）。
 *
 * 读 State 的组件会在模式切换时自动重组，所以「夜间模式」一拨，
 * 键盘、工具条、列表行、设置页所有按键同时换色，不存在半新半旧的中间态。
 */
internal val activeKeyColors: MutableState<AppKeyColors> = mutableStateOf(NightKeyColors)

/**
 * **当前是不是液态玻璃主题**（`FunctionDrawerTheme` 每次组合时刷新）。
 *
 * 为什么做成全局状态而不是 CompositionLocal：这个项目里 `KeyPalette` 是个
 * **object**，它的属性在几百处被直接读（按键、行、chip、卡片、滑块轨道……），
 * 那些读取点大多不在 composable 的参数位置上，没法逐层透传。
 * 把它做成 Snapshot 状态，`KeyPalette.normal` 的 getter 里读一下，
 * **所有调用点一行都不用改**，就能一次性换成玻璃材质 —— 这正是
 * "所有元素都做液态玻璃" 最省事、也最不容易漏的做法。
 *
 * 纹理细节（描边、圆角）仍然是逐个组件单独加的，因为颜色表达不了"边"。
 */
internal val activeGlassTheme: MutableState<Boolean> = mutableStateOf(false)

/**
 * **按钮下阴影强度**（0f~1f，默认 10%）。
 *
 * 和 [activeGlassTheme] 同一套做法：液态玻璃按键的投影是 `Modifier.shadow` 画的，
 * 调用点在 [glassElevation]（`Common.kt`）—— 那里读这个全局状态，
 * 设置页拖动滑条的每一帧就都能直接反映到所有按键上，不用逐层透传参数。
 *
 * 用户原话："按钮下阴影强度默认值改成 10%。"
 */
internal val activeKeyShadow: MutableState<Float> = mutableStateOf(0.1f)

/** 玻璃材质的几个基础量（只在 [activeGlassTheme] 为真时生效）。 */
internal object GlassTokens {

    /**
     * 当前是不是**白天（浅色）**主题。
     *
     * 毛玻璃在昼夜两套底色上不是"同一块玻璃换个色"，参数要分开：
     *   * 夜间：面 = 白色 10%（在深色底上提亮）；描边 = 白色 16%；
     *   * 白天：面 = 白色 55%（浅色底上要"更白"才像玻璃）；描边 = 白色 70% 高光 +
     *     淡淡的深色下边（浅色玻璃的边缘靠对比，不靠提亮）。
     *
     * 判断方式不依赖 `MaterialTheme`（这里是 object，不是 @Composable），
     * 直接用当前角色色板里背景色的亮度。
     */
    val isLight: Boolean
        get() = activeRoleColors.value.background.luminance() > 0.5f

    /**
     * 普通面。
     *
     * **数值直接取自旧版 1.2.20 APK**（`Y.K0()`）：白天 `White 72%`、
     * 夜间 `onSurface 14%`。旧版只有"一层半透明面 + 阴影"，没有任何描边/
     * 渐变高光 —— 这也是白天看起来干净、不发白的根本原因。
     *
     * 白天**必须**用白色而不是 `onSurface`（那是深色）；
     * 浅灰底上叠深色只会变成"更灰的一块"，完全没有玻璃感。
     */
    val surface: Color
        get() = if (isLight) {
            Color.White.copy(alpha = 0.72f)
        } else {
            activeRoleColors.value.onSurface.copy(alpha = 0.14f)
        }

    /**
     * 强调面：主色的半透明版本（选中的 chip、强调键）。
     *
     * 旧版 `Y.I0()`：白天 primary **24%**、夜间 primary **18%**
     * （注意昼夜和普通面是**反着**的 —— 浅色底上要多给一点主色才看得出选中）。
     */
    val accent: Color
        get() = if (isLight) {
            activeRoleColors.value.primary.copy(alpha = 0.24f)
        } else {
            activeRoleColors.value.primary.copy(alpha = 0.18f)
        }

    /** 更暗一档的强调面（`+` 键那种"底色比线圈暗一档"的关系）。 */
    val accentDeep: Color
        get() = if (isLight) {
            activeRoleColors.value.primary.copy(alpha = 0.12f)
        } else {
            activeRoleColors.value.primary.copy(alpha = 0.15f)
        }

    /** 危险面：错误色的半透明版本（`⌫`、清屏、删除）。 */
    val danger: Color
        get() = if (isLight) {
            activeRoleColors.value.error.copy(alpha = 0.18f)
        } else {
            activeRoleColors.value.error.copy(alpha = 0.24f)
        }

    /** 高光边：夜间是"提亮"，白天是"更白的一条亮线"。 */
    val edge: Color
        get() = if (isLight) {
            Color.White.copy(alpha = 0.80f)
        } else {
            activeRoleColors.value.onSurface.copy(alpha = 0.22f)
        }

    /**
     * 最高一档：浮层、对话框、卡片这类"离眼睛最近"的面。
     *
     * 旧版 `Y.O0()`：白天 `White 55%`、夜间 `onSurface 10%`。
     * 之前写成 78% 是白天整屏发白的另一个来源 —— 卡片比按键还白，
     * 一层层叠上去就成了"白纸贴白纸"。
     */
    val raised: Color
        get() = if (isLight) {
            Color.White.copy(alpha = 0.55f)
        } else {
            activeRoleColors.value.onSurface.copy(alpha = 0.10f)
        }
}

/**
 * **当前是不是液态玻璃主题**（`FunctionDrawerTheme` 每次组合时刷新）。
 *
 * 为什么做成全局状态而不是 CompositionLocal：这个项目里 `KeyPalette` 是个
 * **object**，它的属性在几百处被直接读（按键、行、chip、卡片、滑块轨道……），
 * 那些读取点大多不在 composable 的参数位置上，没法逐层透传。
 * 把它做成 Snapshot 状态，`KeyPalette.normal` 的 getter 里读一下，
 * **所有调用点一行都不用改**，就能一次性换成玻璃材质 —— 这正是
 * "所有元素都做液态玻璃" 最省事、也最不容易漏的做法。
 *
 * 纹理细节（描边、圆角）仍然是逐个组件单独加的，因为颜色表达不了"边"。
 */
/**
 * 按键配色 —— 全 App 唯一的取色入口（**对外 API 不变**）。
 *
 * 每一个成员都是 `get()` 委托到 [activeKeyColors]，所以：
 *   * 17 个文件、240 多处调用点**一行都不用改**；
 *   * 但值会跟着当前模式走 —— 这正是"整套换色"和"只刷背景"的区别。
 */
object KeyPalette {

    /** 普通键底。玻璃主题下换成半透明亮面（见 [GlassTokens.surface]）。 */
    val normal: Color
        get() = if (activeGlassTheme.value) GlassTokens.surface else activeKeyColors.value.normal

    /** 比 [normal] 显眼一档的灰（长按线圈用，日夜方向相反，见 [AppKeyColors.normalBright]）。 */
    val normalBright: Color
        get() = if (activeGlassTheme.value) GlassTokens.edge else activeKeyColors.value.normalBright

    /** 强调键底（函数名、角度制开关、选中的页签）。 */
    val accent: Color
        get() = if (activeGlassTheme.value) GlassTokens.accent else activeKeyColors.value.accent

    /** 比 [accent] 暗一档的同色相（`+` 键底色）。 */
    val accentDeep: Color
        get() = if (activeGlassTheme.value) GlassTokens.accentDeep else activeKeyColors.value.accentDeep

    /** 危险键底（`⌫`）。 */
    val danger: Color
        get() = if (activeGlassTheme.value) GlassTokens.danger else activeKeyColors.value.danger

    /**
     * 危险键**压暗一档**的透明度（0.45）。
     *
     * 现在只有「函数文件」列表多选模式右上角那颗「删除」用它（[dangerDim]）。
     * 功能界面那颗「清屏」已经改成**常亮** [danger]（用户本轮要求
     * "删除键在函数图像界面有描点的时候会变色，删掉"），不再走两档动画。
     */
    const val DANGER_DIM_ALPHA = 0.45f

    /**
     * 危险键的**暗档底色** = [danger] 乘 [DANGER_DIM_ALPHA]。
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

    /** 按键文字：夜间纯白，白天近黑。 */
    val text: Color get() = activeKeyColors.value.text

    /** 未选中页签的文字（做出层级，但仍是同色系）。 */
    val textMuted: Color get() = activeKeyColors.value.textMuted

    /** 长按变体气泡的本体。玻璃主题下是一块更厚的高光面。 */
    val bubble: Color
        get() = if (activeGlassTheme.value) GlassTokens.raised else activeKeyColors.value.bubble

    /** 气泡描边：1dp。 */
    val bubbleBorder: Color get() = activeKeyColors.value.bubbleBorder

    /** 滑轨 / 进度条未选中段的底色（夜间 16% 白，白天 14% 深色）。 */
    val track: Color get() = activeKeyColors.value.track

    /**
     * 按下时叠在键身上的那层颜色（浓度由 `PressFeedback.glowAlpha` 控制，最大 0.16）。
     *
     * 夜间白（提亮）、白天深色（压暗）。用法：
     * ```
     * Box(Modifier.graphicsLayer { alpha = press.glowAlpha }.background(KeyPalette.pressGlow))
     * ```
     */
    val pressGlow: Color get() = activeKeyColors.value.pressGlow
}

// ---------------------------------------------------------------------------
// M2 的 Colors 只有 12 个槽位，MD3 多出来的角色用扩展属性补上。
// 注意：primary / secondary / background / surface / error / onXxx 这些
// M2 本来就有的不要重复定义，否则会遮挡原有成员。
//
// 这些 getter 全部读 [activeRoleColors]（当前生效的那一份），**不是**直接读
// [WatchPalette] —— 这是"夜间模式开关一拨，整屏控件跟着换色"的关键：
// 读 State 的组件会自动重组，而写死某个 object 就不会。
// ---------------------------------------------------------------------------

val Colors.onSurfaceVariant: Color get() = activeRoleColors.value.onSurfaceVariant
val Colors.surfaceVariant: Color get() = activeRoleColors.value.surfaceVariant

val Colors.primaryContainer: Color get() = activeRoleColors.value.primaryContainer
val Colors.onPrimaryContainer: Color get() = activeRoleColors.value.onPrimaryContainer
val Colors.secondaryContainer: Color get() = activeRoleColors.value.secondaryContainer
val Colors.onSecondaryContainer: Color get() = activeRoleColors.value.onSecondaryContainer
val Colors.tertiary: Color get() = activeRoleColors.value.tertiary
val Colors.onTertiary: Color get() = activeRoleColors.value.onTertiary
val Colors.tertiaryContainer: Color get() = activeRoleColors.value.tertiaryContainer
val Colors.onTertiaryContainer: Color get() = activeRoleColors.value.onTertiaryContainer
val Colors.errorContainer: Color get() = activeRoleColors.value.errorContainer
val Colors.onErrorContainer: Color get() = activeRoleColors.value.onErrorContainer

val Colors.surfaceContainerLowest: Color get() = activeRoleColors.value.surfaceContainerLowest
val Colors.surfaceContainerLow: Color get() = activeRoleColors.value.surfaceContainerLow
val Colors.surfaceContainer: Color get() = activeRoleColors.value.surfaceContainer
val Colors.surfaceContainerHigh: Color get() = activeRoleColors.value.surfaceContainerHigh
val Colors.surfaceContainerHighest: Color get() = activeRoleColors.value.surfaceContainerHighest

val Colors.outline: Color get() = activeRoleColors.value.outline
val Colors.outlineVariant: Color get() = activeRoleColors.value.outlineVariant
val Colors.gridLine: Color get() = activeRoleColors.value.gridLine
val Colors.inverseSurface: Color get() = activeRoleColors.value.inverseSurface
val Colors.inverseOnSurface: Color get() = activeRoleColors.value.inverseOnSurface
val Colors.scrim: Color get() = activeRoleColors.value.scrim

/**
 * 构造 M2 的 [Colors]。M2 自带组件（如果有用到）会读这些槽位，
 * 所以要把 MD3 角色映射到 M2 的语义槽上，避免出现"半黑半灰"的违和感。
 *
 * ## 深浅必须跟着底色走（2026-10-17）
 *
 * M2 的 `darkColors` / `lightColors` 不只是"把 12 个值填进去"：它们还决定
 * `isLight`，而 `isLight` 会影响一批组件默认值（涟漪透明度、elevation 叠色、
 * 禁用态的叠色比例）。夜里用黑底却给 `isLight = true`，按下的涟漪会变成
 * "白上加白"、浮层的叠色也会发灰。所以：
 *   * 黑底（[WatchPalette]，默认）→ `darkColors`；
 *   * 白底（[WatchPaletteDay]）→ `lightColors`。
 *
 * @param palette 当前生效的角色色板（由 `FunctionDrawerTheme` 传入）。
 */
fun watchColors(palette: AppRoleColors = WatchPalette): Colors {
    // ⚠️ 用**位置参数**调用：`::darkColors` / `::lightColors` 一旦赋给变量，
    // 函数引用就丢了默认参数信息，具名参数调用会报 "no value passed for parameter …"。
    // 所以这里固定按 12 个槽位的声明顺序传。
    val slots = if (palette === WatchPaletteDay) ::lightColors else ::darkColors
    return slots(
        palette.primary,
        palette.primaryContainer,   // primaryVariant
        palette.secondary,
        palette.secondaryContainer, // secondaryVariant
        palette.background,
        palette.surface,
        palette.error,
        palette.onPrimary,
        palette.onSecondary,
        palette.onBackground,
        palette.onSurface,
        palette.onError
    )
}
