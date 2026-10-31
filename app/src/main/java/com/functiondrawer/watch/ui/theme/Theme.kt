// 手表 UI 会用到少量实验性 Compose UI 接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.functiondrawer.watch.ui.AppKeyColors
import com.functiondrawer.watch.ui.AppRoleColors
import com.functiondrawer.watch.ui.CurvePaletteDay
import com.functiondrawer.watch.ui.CurvePaletteNight
import com.functiondrawer.watch.ui.DayKeyColors
import com.functiondrawer.watch.ui.NightKeyColors
import com.functiondrawer.watch.ui.WatchPalette
import com.functiondrawer.watch.ui.WatchPaletteDay
import com.functiondrawer.watch.ui.activeCurveColors
import com.functiondrawer.watch.ui.activeKeyColors
import com.functiondrawer.watch.ui.activeGlassTheme
import com.functiondrawer.watch.ui.activeRoleColors
import com.functiondrawer.watch.ui.watchColors

/**
 * 曲线配色：12 种 —— **日夜各一套**，这里读的是当前生效的那一套。
 *
 * 为什么是 `get()` 而不是一个常量 List：
 * 黑底那套是粉彩（`#7FD8FF` 天蓝、`#FFF2A0` 奶油黄……），它们靠"亮"从黑底里跳出来；
 * 白底上这些颜色的相对亮度是 0.7~0.95，**和白纸几乎一样**，12 条里有 6 条会直接消失。
 * 所以白天必须换一组深饱和色（`CurvePaletteDay`），而不是调透明度 ——
 * "把背景刷白"这个做法在曲线这一步就会彻底露馅。
 *
 * 调用点（9 个文件、20 多处 `CurvePalette[i]`）**一行都没改**：
 * 它现在是个 getter，读 [activeCurveColors] 快照，模式一换自动跟着换。
 * 顺序仍然是"第 1 条函数拿第 1 个颜色"，两套的 12 个位置色相语义一一对应
 * （第 1 个都是蓝、第 2 个都是红、第 3 个都是绿……），所以切换模式时
 * 函数的颜色**不会跳位**，只是同一支颜色换了个明度档。
 */
val CurvePalette: List<Color> get() = activeCurveColors.value

/**
 * 字号整体调小：默认字体是按手机屏定的，在 233dp 的圆表上会显得过于笨重。
 *
 * 注意：真正决定"字有多大"的是 [com.functiondrawer.watch.ui.WatchMetrics]，
 * 它按屏占比动态算字号；这里只是给 MaterialTheme 一个合理基线，
 * 免得万一某处直接用了 MaterialTheme.typography 就爆掉。
 */
private val Base = Typography()

val WatchTypography = Typography(
    h6 = Base.h6.copy(
        fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold
    ),
    subtitle1 = Base.subtitle1.copy(
        fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold
    ),
    subtitle2 = Base.subtitle2.copy(fontSize = 13.sp, lineHeight = 17.sp),
    body1 = Base.body1.copy(fontSize = 13.sp, lineHeight = 17.sp),
    body2 = Base.body2.copy(fontSize = 11.sp, lineHeight = 14.sp),
    button = Base.button.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    caption = Base.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
    overline = Base.overline.copy(fontSize = 9.sp, lineHeight = 12.sp)
)

/**
 * 圆屏 + 小屏：圆角给足一点更好看。
 *
 * 真机反馈"所有按键都变得圆滑、美观"之后整体上调了一档
 * （10/14/20 → 14/18/24）：手表上控件本来就小，同样的 10dp 圆角
 * 在 233dp 屏上占比很小，看起来还是"方盒子"。
 *
 * 注意**键盘那些按键不用这三档**：它们的高度是算出来的（一个格子 35.8dp），
 * 固定 dp 的圆角在高度变化时会比例失调，所以键盘走 [KEY_CORNER_OF_HEIGHT]
 * 那套"按高度取比例"的算法。这三档留给列表行、卡片、对话框、图标按钮。
 */
val WatchShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

/**
 * 按键圆角 = 高度 × 这个比例。
 *
 * 为什么按比例而不是固定 dp：键盘"一个格子高"是纵向分区算出来的（现在 ≈35.8dp），
 * 同一个高度下键宽从 24.8dp（最窄那行）到 51.9dp 不等。固定 dp 的圆角
 * 在行高变化时会比例失调（改一次行数就得重调一次），按高度取比例则观感恒定。
 *
 * 0.42 是"接近胶囊但还没到胶囊"：窄键（24.8dp 宽）会被 Compose 自动夹到
 * 半宽、变成胶囊；宽键保留一点点直边。两者放在一起仍是同一套圆润语言，
 * 而且不会因为太圆而显得像一排药丸。
 */
const val KEY_CORNER_OF_HEIGHT = 0.42f

/** 按控件高度算圆角形状（用途见 [KEY_CORNER_OF_HEIGHT]）。 */
fun watchShapeOf(height: Dp): RoundedCornerShape =
    RoundedCornerShape(height * KEY_CORNER_OF_HEIGHT)

/**
 * 胶囊形（stadium）—— 键盘按键、页签 chip、长按气泡都用它。
 *
 * 取值来源是 `D:\Culculator` 的按键 drawable：那边四个 `bg_btn_*.xml`
 * 全都写 `corners android:radius="50dp"`，远大于按键高度的一半，
 * Android 会把它夹到半高，画出来就是**一整颗胶囊**。
 * Compose 里等价的写法就是 `percent = 50`（按短边的 50% 取圆角）。
 *
 * 为什么按键不再用 [watchShapeOf] 那套"按高度取比例"：
 * 比例算法得到的是 0.42×高度（近胶囊但留着一点直边），
 * 而参考风格要的是**完整胶囊**，两者在 35dp 高的键上是肉眼可分的。
 */
val PillShape: Shape = RoundedCornerShape(percent = 50)

/**
 * 应用主题外壳。
 *
 * @param dayMode **白天模式**开关。`false`（默认）= **夜间**，也就是这个 App 原来那套
 *   黑底 UI（[WatchPalette] + `CurvePaletteNight` + [NightKeyColors]）；
 *   `true` = 白天模式（[WatchPaletteDay] + `CurvePaletteDay` + [DayKeyColors]）。
 *
 *   ⚠️ **参数名是 `dayMode`，不是 `nightMode`** —— 这是本轮最重要的一处修正。
 *   上一版把它叫 `nightMode` 且 `true = 白底`，于是设置页里"夜间模式：开"
 *   显示的是白屏，用户直接说"反了"。正过来之后语义和字面一致：
 *   **开关打开 = 夜间 = 黑底 = 原来那套**。
 *
 *   设置页那一行仍然叫「夜间模式」（用户就是这么叫的），显示的是 `!dayMode`，
 *   点击翻转 `dayMode` —— 界面文案与内部字段名方向不同，但都写在
 *   `ThemeSettingsScreen` 一处，不会散。
 *
 *   存档层是 `SettingsFields.dayMode`（第 11 段，`false` = 夜间）。
 *   它和旧版的 `nightMode` 段**取值完全一致**（旧版 `false` = 黑底），
 *   所以老存档不需要迁移：装上新包，原来黑的还是黑的。
 *
 * 四条线（M2 的 12 个槽位 / MD3 扩展属性 / 曲线 12 色 / 按键 10 色）
 * 在**同一个 composable** 的 SideEffect 里一起换，所以不存在
 * "背景换了但按键还是旧的"这种中间态 —— 那正是"只把背景变白"的症状。
 */
@Composable
fun FunctionDrawerTheme(
    dayMode: Boolean = false,
    /**
     * 是否「液态玻璃」主题（`ThemeStyle.LIQUID_GLASS`）。
     *
     * 它只切换**材质**（半透明面、高光边、模糊底），和明暗正交。
     * 真正生效的方式是把 [activeGlassTheme] 这个全局状态置位 ——
     * `KeyPalette` 的所有 getter 都会读它，于是几百处调用点一次性换成玻璃材质。
     */
    glass: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette: AppRoleColors = if (dayMode) WatchPaletteDay else WatchPalette
    val curves: List<Color> = if (dayMode) CurvePaletteDay else CurvePaletteNight
    val keys: AppKeyColors = if (dayMode) DayKeyColors else NightKeyColors

    // 扩展属性（`scheme.surfaceContainerHigh` 那一套）读的是这份快照状态：
    // 把它写进 SideEffect（组合成功之后跑），读取方就会在开关拨动时重组。
    // 值没变时 Snapshot 不会通知，所以不会死循环。
    SideEffect {
        activeRoleColors.value = palette
        activeCurveColors.value = curves
        activeKeyColors.value = keys
        activeGlassTheme.value = glass
    }

    MaterialTheme(
        colors = watchColors(palette),
        typography = WatchTypography,
        shapes = WatchShapes,
        content = content
    )
}
