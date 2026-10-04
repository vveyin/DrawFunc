// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlin.math.pow

/**
 * 「按键 + 滑动条」缩放栏（用户需求 #7）。
 *
 * 用户原话："给设置加一个是否使用按键和滑动条缩放的选项（因为有些手表不支持多指触控
 * 也没有表冠），首次进入软件默认关闭。打开之后在函数显示界面的底部显示按键和滑动条，
 * 左边放 -，右边放 +，中间放滑动条。按键进行大尺度缩放，滑动进行微调（范围在 + 和 -
 * 调节的范围之间）。"
 *
 * ## 为什么需要它
 *
 * 图像界面原有的三套缩放入口（双指捏合、表冠、方向键）都有前提：
 * 捏合要**多指触控屏**、表冠要**有表冠**、方向键要**表冠被映射成按键**。
 * 三者皆无的表（国产"原屏"表里就有）在这一屏上**完全没法缩放**。这条栏只用单指，
 * 是那类设备唯一的缩放入口 —— 所以它默认关、按需打开（支持的设备用捏合更顺手，
 * 多一条底部栏只会挡内容）。
 *
 * ## 两种缩放的分工
 *
 *   * 两端 `−` / `+`：**固定的一大步**，从**当前倍率**起算乘 [ZOOM_STEP]（1.5 倍 ≈ 一眼能看出变化）。
 *     它复用的是表冠/方向键那条路（`GraphState.zoomByFactor`），
 *     焦点**固定为屏幕中心**，和另外三套入口完全一致 —— 同一个动作不该因为入口不同
 *     得到不同的结果。按完之后**滑轨回到原位（中点）**，见下。
 *   * 中间滑轨：**微调**，而且是**相对量**。滑轨只表示"相对当前基准的一小段偏移"：
 *     从中点推到任意一端 = 一个 `−`/`+` 的量（`1.5^位移`，位移夹在 ±1），
 *     所以"范围在 + 和 − 调节的范围之间"是硬约束 —— 滑轨**永远不可能**单独把图形缩到别处去。
 *     做成绝对倍率是错的：那样滑轨会有"绝对视口"含义，用户从任意缩放位置一碰滑轨
 *     就会被拽到某个固定倍率上，和 `−`/`+` 的手感完全割裂。
 *
 * ### 滑轨的两个手感（用户 2026-XX 明确要求）
 *
 * 用户原话："缩放滑动条的逻辑应该是拖动之后就留在那个位置，点击 + 或 - 之后
 * 从当前比例加或减一个固定的程度然后滑动条回到原位，不然滑动条就可以进行所有缩放了。"
 *
 *   * **拖完**停在原地（不弹回中点）—— 滑块位置 = 当前那一小段偏移量，看得见、
 *     也可以再拖回去；松手什么都不发生。
 *   * **按 `−`/`+`** 之后滑块**滑回中点**（走 [FINE_RESET_MS] 的短动画）：
 *     因为那一按已经把当前倍率变成了新的"基准"，中点自然就该回到"没偏移"的位置。
 *     这一步是把"偏移"重新归零，否则偏移会在按键之间越积越多，滑轨就真的能做所有缩放了。
 *
 * ## 和底部"上滑回列表"热区的关系（取舍写在这里）
 *
 * 图像界面底部有一条 0.17 屏高的上滑热区（`SWIPE_UP_ZONE_H`，见 GraphScreen）。
 * 这条栏**放在它之上**，中间留 [ZOOM_BAR_BOTTOM_GAP] 的缝，热区**一点不动**：
 *   * 关掉这个设置时这一栏根本不组合 → 老行为逐像素不变；
 *   * 打开时上滑热区还在原地，用户想回列表仍然在屏幕最底下那一条往上划。
 * 反过来"把热区让开"（例如开启时把热区移上去或改窄）会动到一条**已经调好的**手势，
 * 那是回归风险最高的一种改法，收益只是少一条缝 —— 不值得。
 */

/** 缩放栏总高度（占屏短边的比例）。233dp 屏上约 28dp。 */
internal const val ZOOM_BAR_H = 0.12f

/**
 * 按 `−` / `+` 之后滑轨滑回中点的动画时长（毫秒）。
 *
 * 140ms：短到不会让人等，又能看出"它自己回去了"，而不是"啪"地跳一下。
 * 拖动时**不使用**这个动画（见 [FineZoomTrack]），否则滑块会跟不上手指。
 */
internal const val FINE_RESET_MS = 140

/**
 * 缩放栏与底部上滑热区之间留的缝（占屏短边的比例）。
 *
 * 不留缝的话两个手势区会贴在一起：手指落在栏的下边缘时，
 * 稍微往下一点点就变成"上滑回列表"，用户会觉得栏在抖。
 */
internal const val ZOOM_BAR_BOTTOM_GAP = 0.012f

/**
 * 按一次 `+` / `-` 的缩放倍率。
 *
 * 1.5 是"一次按下去变化明显，又不至于一下子找不到图形"的量：
 * 连按 5 次 = 7.6 倍，连按 5 次反向 ≈ 1/7.6 —— 够快了。
 * 和表冠一格的量级也接近（`rotaryFactor` 一格最大 1.28 倍）。
 */
internal const val ZOOM_STEP = 1.5

/**
 * 缩放栏本体：左 `−`、中滑轨、右 `+`。
 *
 * 三个部件放在一个近黑胶囊里（[KeyPalette.bubble] + 描边）：图像界面上的浮层
 * （信息卡、工具提示条）都是这套，缩放栏也浮在曲线上，用同一套才不会"两种浮层"。
 *
 * @param widthFraction 栏宽占屏宽的比例。由调用方按它**所在高度的圆弧宽度**算
 *                      （见 GraphScreen 里的 `rowWidth`）—— 底部圆弧收得厉害，
 *                      写死宽度在圆表上会被表壳切掉两端。
 * @param onZoom 缩放回调，参数是**倍率**（>1 放大、<1 缩小）。
 *               直接接 `GraphState.zoomByFactor`（焦点 = 屏幕中心）。
 */
@Composable
fun ZoomBar(
    metrics: WatchMetrics,
    widthFraction: Float,
    onZoom: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val barHeight = metrics.sizeDp * ZOOM_BAR_H
    // 按键做成长胶囊（不是正圆）：形状和键盘按键、列表行的 `PillShape` 一致。
    // 高度只占栏高的 0.82，上下各留一点，栏的胶囊边才不会和键的胶囊边贴在一起。
    val keyWidth = barHeight * 0.95f
    val keyHeight = barHeight * 0.82f

    // ---- 滑轨的"归零信号" ----
    //
    // 滑轨的偏移量由滑轨自己持有（拖动时直接写它，零滞后）。`−`/`+` 要做的是
    // **让它回到中点**，所以这里只发一个"归零"信号：每按一次加一，
    // 滑轨那边看到信号变化就走一小段动画回中点。
    //
    // 为什么不让这里直接持有那个偏移量：那样"拖动"和"归零动画"就是两个来源，
    // 松手时画的东西会在两者之间切换 —— 那正是用户报的"抽搐"（见 [FineZoomTrack]）。
    var fineResetTick by remember { mutableStateOf(0) }

    Surface(
        color = KeyPalette.bubble,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = modifier
            .width(metrics.sizeDp * widthFraction)
            .height(barHeight)
            .border(1.dp, KeyPalette.bubbleBorder, PillShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * 0.014f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.012f)
        ) {
            // ---- 左：大尺度缩小（按完滑轨回中点）----
            ZoomKey(
                label = "−",
                metrics = metrics,
                width = keyWidth,
                height = keyHeight,
                description = "缩小",
                onClick = {
                    // 从**当前**倍率起算，缩固定的一步
                    onZoom(1.0 / ZOOM_STEP)
                    // 再让滑轨滑回中点（偏移归零，倍率不动）
                    fineResetTick++
                }
            )

            // ---- 中：微调滑轨（相对量；拖完停在原地，按 ± 才回中点）----
            FineZoomTrack(
                resetTick = fineResetTick,
                onZoom = onZoom,
                metrics = metrics,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // ---- 右：大尺度放大（按完滑轨回中点）----
            ZoomKey(
                label = "+",
                metrics = metrics,
                width = keyWidth,
                height = keyHeight,
                description = "放大",
                onClick = {
                    onZoom(ZOOM_STEP)
                    fineResetTick++
                }
            )
        }
    }
}

/**
 * 一颗 `−` / `+` 键。
 *
 * 和键盘按键、设置行同一套：深灰胶囊 [KeyPalette.normal] + 纯白字，
 * 按下只缩图层（[rememberPressFeedback]），不放大块高亮 ——
 * 它浮在曲线上，一按就闪一片白会很吵。
 */
@Composable
private fun ZoomKey(
    label: String,
    metrics: WatchMetrics,
    width: Dp,
    height: Dp,
    description: String,
    onClick: () -> Unit
) {
    val press = rememberPressFeedback(scaleAmount = 0.10f, glowAmount = 0f)

    Surface(
        color = KeyPalette.normal,
        contentColor = KeyPalette.text,
        shape = PillShape,
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClickLabel = description,
                onClick = onClick
            )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = metrics.fontBody,
                color = KeyPalette.text,
                maxLines = 1
            )
        }
    }
}

/**
 * 微调滑轨。
 *
 * 画法照抄 `ui/SliderRow.kt` 里的 `SliderTrack`（那个文件这一轮不改动，
 * 所以是**复制画法**而不是复用函数）：16% 白的轨道底 + 强调色的已选段 +
 * 纯白滑块，尺寸全部按同一个基准（栏高）推。
 *
 * 和 SliderRow 唯一的差别是**已选段的画法**：那边是"从左端到当前值"的进度条，
 * 这边没有"进度"这个概念（它是相对量），所以已选段从**中点**向滑块方向长 ——
 * 一眼能看出"往右 = 放大、往左 = 缩小、中点 = 没动"。
 * 拖完停住时那一段仍然亮着，就是当前这一小段偏移的可视化。
 *
 * ## 手感（用户明确要求）
 *
 *   * **拖动时就停在拖动的位置**（`offset` 就是滑块位置，拖动只改它，松手不改）——
 *     用户原话："拖动之后就留在那个位置"。
 *   * **只有一个位置来源**：滑块画的就是 `offset` 本身。拖动时立刻写它（零滞后），
 *     归零时才用 `animate()` 一小段一小段地写它。
 *     ⚠️ 用户报："缩放滑动条在确定位置的时候有点抽搐。" 上一版就是这么错的：
 *     拖动期间画 `offset`、非拖动期间画 `animateFloatAsState(offset)` ——
 *     那个动画值一直在**追**手指（140ms 滞后），松手那一刻画的东西从"手指位置"
 *     切回"还在追的动画值"，滑块先往回跳一下再爬回来 = 抽搐。
 *     现在两个阶段写的是**同一个** `offset`，切换时不可能有跳变。
 *   * 只有 `−`/`+` 发[resetTick]信号时才走 [FINE_RESET_MS] 的动画回中点，
 *     看起来是"滑轨自己滑回原位"，而不是"啪"地跳一下。
 *
 * @param resetTick 归零信号（**每按一次 `−`/`+` 加一**）：值一变就把偏移动画回 0。
 *                  用信号而不是"外部持有偏移量"，是为了让滑块位置只有一个来源。
 * @param onZoom 缩放回调，参数是倍率（>1 放大、<1 缩小）。微调就是在拖动时把
 *               **位移增量**换成倍率（`[ZOOM_STEP]^增量`），连续小位移 = 连续小倍率。
 */
@Composable
private fun FineZoomTrack(
    resetTick: Int,
    onZoom: (Double) -> Unit,
    metrics: WatchMetrics,
    modifier: Modifier = Modifier
) {
    val trackH = metrics.sizeDp * ZOOM_BAR_H * 0.16f
    val thumb = metrics.sizeDp * ZOOM_BAR_H * 0.42f
    val tickW = metrics.sizeDp * 0.006f

    // 偏移：-1（≈ 一次 `−`）… 0（中点）… +1（≈ 一次 `+`）。**滑块画的就是它**。
    var offset by remember { mutableStateOf(0f) }

    // 归零：按了 `−`/`+`（信号 +1）就从当前位置滑回中点。
    // 只改偏移、**不碰倍率** —— 倍率那一步已经在按键里做完了，
    // 归零只是"把偏移重新定义成 0"，所以画面上图形不会动，只有滑块回家。
    // `resetTick == 0`（首次组合）时什么都不做，免得刚进这一屏滑块自己动一下。
    LaunchedEffect(resetTick) {
        if (resetTick == 0) return@LaunchedEffect
        val from = offset
        if (from == 0f) return@LaunchedEffect
        animate(
            initialValue = from,
            targetValue = 0f,
            animationSpec = tween(durationMillis = FINE_RESET_MS, easing = FastOutSlowInEasing)
        ) { value, _ -> offset = value }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.CenterStart
    ) {
        // 滑轨宽度同一帧就在约束里（不用 onSizeChanged 那样慢一帧，慢一帧在拖动里就是"手感黏"）
        val trackDp = with(LocalDensity.current) { constraints.maxWidth.toDp() }
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        val fraction = ((offset + 1f) / 2f).coerceIn(0f, 1f)
        // 滑块**左边缘**的位置：中点时正好居中（(轨道 - 滑块) / 2）
        val thumbX = (trackDp - thumb) * fraction
        // 滑块中心走过的位置：中点 = 轨道正中
        val centerDp = trackDp / 2f
        val thumbCenter = thumbX + thumb / 2f
        // 已选段：中点 → 滑块中心（左移时为负方向，所以要单独算起点和长度）。
        // 长度/起点都在 Dp 上算：`kotlin.math.abs`/`min` 没有 Dp 重载，
        // 这里用 Dp 自己的比较运算（Dp 是 Comparable）。
        val fillW = if (thumbCenter > centerDp) thumbCenter - centerDp else centerDp - thumbCenter
        val fillStart = minOf(centerDp, thumbCenter)

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // 拖动整条轨道的**一半** = 位移 1 = 一次 `+`/`−` 的量。
                // 这样"范围在 ± 一次按键之间"是硬约束（下面的 coerceIn），
                // 而不是"滑到底差不多刚好"。
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        // 用系统自带的这套（和滑轨、图上平移同一套）而不是手写 slop 判定：
                        // 项目里手写 slop 的那几个手势在真机上全都不触发（见 DragGestures.kt）。
                        // ⚠️ 松手**不归零**（原来这里是 `offset = 0f`）：用户要"拖完停在原地"。
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offset + dragAmount / (widthPx * 0.5f)).coerceIn(-1f, 1f)
                            val applied = next - offset
                            if (applied != 0f) {
                                offset = next
                                onZoom(ZOOM_STEP.pow(applied.toDouble()))
                            }
                        }
                    )
                }
        ) {
            // ---- 轨道底 ----
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackH)
                    .align(Alignment.CenterStart)
                    .clip(PillShape)
                    .background(Color.White.copy(alpha = 0.16f))
            )
            // ---- 已选段：从中点往滑块方向长（相对量的可视化）----
            if (fillW > 0.dp) {
                Box(
                    Modifier
                        .offset(x = fillStart)
                        .width(fillW)
                        .height(trackH)
                        .align(Alignment.CenterStart)
                        .clip(PillShape)
                        .background(KeyPalette.accent)
                )
            }
            // ---- 中点刻度：告诉用户"这里是没动" ----
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = centerDp - tickW / 2f)
                    .width(tickW)
                    .height(trackH)
                    .background(KeyPalette.text.copy(alpha = 0.5f))
            )
            // ---- 滑块 ----
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbX)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(KeyPalette.text)
            )
        }
    }
}
