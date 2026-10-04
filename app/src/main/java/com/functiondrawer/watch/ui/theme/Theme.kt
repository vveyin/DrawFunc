// 手表 UI 会用到少量实验性 Compose UI 接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.functiondrawer.watch.ui.watchColors

/**
 * 曲线配色：12 种。
 *
 * 全部是**亮色调 / 粉彩**，因为在纯黑底上才够跳、又不刺眼。
 * 12 种两两之间都能分辨（亮度或色相至少差一档），这是"多条曲线同屏"的可用性下限；
 * 用户可以在函数列表里长按圆点自己挑，所以这个顺序也决定了默认分配的顺序
 * （第 1 条函数拿第 1 个颜色，依此类推）。
 */
val CurvePalette: List<Color> = listOf(
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
    Color(0xFFC8E6A0)  // 抹茶
)

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

@Composable
fun FunctionDrawerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = watchColors(),
        typography = WatchTypography,
        shapes = WatchShapes,
        content = content
    )
}
