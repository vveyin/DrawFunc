package com.functiondrawer.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 顶部工具条（返回键那一行）的几何 —— 和设置页 / 文件列表同一组数。 */
private const val TOOLBAR_TOP = 0.10f
private const val TOOLBAR_H = 0.125f

/** 列表上沿（占真实屏高）：工具条下面一点点，和设置页同一个数。 */
private const val LIST_TOP = TOOLBAR_TOP + TOOLBAR_H + 0.02f

/** 行高（占短边）—— 和设置页的行逐字相同。 */
private const val ROW_H = 0.13f

/** 圆屏上这一屏的行宽比例（屏幕中间那一段，弧宽足够宽）。 */
private const val ROUND_ROW_WIDTH = 0.84f

/**
 * 行尾箭头边长（占短边）。
 *
 * 和「长按设置」那一屏的入口行 `EntryRow` 用**同一个数**（SettingsScreen 里的
 * `ENTRY_CHEVRON`，同样 0.050）：那边是 0.050、这里原来是 0.11，同一个箭头
 * 在两屏里粗细差了一倍多 —— 用户说"按钮上面的箭头和原来的箭头不一样"，
 * 统一到长按设置那一屏的口径。
 */
private const val CHEVRON_SIZE = 0.050f

/**
 * 「开发者模式」子页。
 *
 * 入口只在开发者模式开着时出现（「更多设置」末尾）。这一屏三行：
 *   1. **开发者模式**：点一下就关掉，并立刻退回「更多设置」—— 入口随之消失；
 *   2. **回到初始界面**：确认后 `onboarded` 置 false，[AppRoot] 自己切回欢迎页；
 *   3. **恢复默认设置**：确认后把设置整份恢复成 `WatchSettings()` 的默认值
 *      （函数列表保留 —— 那是用户的数据，不是设置）。
 *
 * 确认框沿用项目里已有的那一套：整屏压暗 + [KeyPalette.bubble] 卡片 +
 * 药丸按钮（取消 / 确定）。
 */
@Composable
internal fun DeveloperScreen(
    metrics: WatchMetrics,
    onBack: () -> Unit,
    /** 关闭开发者模式（调用方负责写盘 + 退出这一屏）。 */
    onDisableDeveloper: () -> Unit,
    /** 回到欢迎流程。 */
    onRestartOnboarding: () -> Unit,
    /** 恢复默认设置（调用方负责写回 + 弹提示）。 */
    onResetSettings: () -> Unit,
    rotary: Flow<Float> = emptyFlow(),
    /** 背后有没有模糊背景可透（和设置页同一条规则）。 */
    backdropVisible: Boolean = false
) {
    /** 现在在问哪一件事；null = 没有确认框。 */
    var ask by remember { mutableStateOf<Ask?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                if (backdropVisible || LocalGlassTheme.current) {
                    MaterialTheme.colors.background.copy(alpha = GLASS_PAGE_MASK_ALPHA)
                } else {
                    MaterialTheme.colors.background
                }
            )
    ) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val isRound = isRoundScreen()
        val scroll = rememberScrollState()

        // 表冠：这一屏可能装不下三行，拨表冠能滚（接法和设置页一字不差）。
        LaunchedEffect(rotary) {
            rotary.collect { amount ->
                scroll.scrollBy(-amount)
            }
        }

        val rowWidth = geometry.rowWidth(
            metrics = metrics,
            ringFraction = if (isRound) ROUND_ROW_WIDTH else 1f,
            isRound = isRound
        )

        // ---- 返回键：左上角，和设置页 / 应用信息页同一处 ----
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(TOOLBAR_TOP))
                .width(
                    geometry.rowWidth(
                        metrics = metrics,
                        ringFraction = if (isRound) 0.52f else 1f,
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

        // ---- 三行入口 ----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = geometry.sy(LIST_TOP))
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = if (isRound) 0.dp else geometry.sx(0.03f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.018f)
        ) {
            DevRow(
                title = "开发者模式",
                value = "已开启",
                metrics = metrics,
                width = rowWidth,
                onClick = onDisableDeveloper
            )
            DevRow(
                title = "回到初始界面",
                metrics = metrics,
                width = rowWidth,
                chevron = true,
                onClick = { ask = Ask.RESTART }
            )
            DevRow(
                title = "恢复默认设置",
                metrics = metrics,
                width = rowWidth,
                chevron = true,
                onClick = { ask = Ask.RESET }
            )
        }

        // ---- 确认框 ----
        val question = ask
        if (question != null) {
            // 压暗幕：吃掉点击，避免误触到底下的行
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
                color = KeyPalette.bubble,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(
                        bandWidth(
                            metrics = metrics,
                            geometry = geometry,
                            isRound = isRound,
                            yTop = 0.5f,
                            yBottom = 0.5f,
                            arcMargin = 0.94f
                        )
                    )
                    .border(1.dp, KeyPalette.bubbleBorder, MaterialTheme.shapes.large)
            ) {
                Column(modifier = Modifier.padding(metrics.sizeDp * 0.042f)) {
                    Text(
                        text = when (question) {
                            Ask.RESTART -> "回到初始界面？"
                            Ask.RESET -> "恢复默认设置？"
                        },
                        fontSize = metrics.fontBody,
                        color = KeyPalette.text
                    )
                    Spacer(Modifier.height(metrics.sizeDp * 0.014f))
                    Text(
                        text = when (question) {
                            Ask.RESTART -> "会重新显示欢迎页，现在的函数和设置都还在。"
                            Ask.RESET -> "设置会回到出厂默认值，函数列表保留。"
                        },
                        fontSize = metrics.fontTiny,
                        color = KeyPalette.textMuted
                    )
                    Spacer(Modifier.height(metrics.sizeDp * 0.03f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.022f)
                    ) {
                        Button(
                            onClick = { ask = null },
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
                                ask = null
                                when (question) {
                                    Ask.RESTART -> onRestartOnboarding()
                                    Ask.RESET -> onResetSettings()
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
    }
}

/** 这一屏要问的两件事。 */
private enum class Ask { RESTART, RESET }

/**
 * 一行入口（胶囊）。
 *
 * 和设置页的 `EntryRow` 同一副长相：整行胶囊、左标题、右箭头；
 * [value] 非空时右端改显一颗小 chip（开发者模式那一行用它写"已开启"）。
 */
@Composable
private fun DevRow(
    title: String,
    metrics: WatchMetrics,
    width: Dp,
    onClick: () -> Unit,
    value: String? = null,
    chevron: Boolean = false
) {
    val press = rememberPressFeedback(scaleAmount = 0.98f, glowAmount = 0f)
    Surface(
        // 用户 1.2.67："毛玻璃主题下开发者模式界面的设置条……没有毛玻璃主题的按钮样式。"
        // 照抄设置页 `SettingsRow` 那一套：玻璃主题下底色换成半透明亮面
        // （`GlassTokens.surface`），让底下的模糊透上来；其它主题仍是普通键深灰。
        color = if (LocalGlassTheme.current) GlassTokens.surface else KeyPalette.normal,
        shape = PillShape,
        modifier = Modifier
            .width(width)
            .height(metrics.sizeDp * ROW_H)
            // 和设置页 / 函数列表行同一段玻璃材质：投影 + 1dp 亮边 + 上沿高光。
            // 行数量少（三行），可以带投影。
            .then(if (LocalGlassTheme.current) Modifier.glassSurface(PillShape) else Modifier)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.sizeDp * 0.026f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.02f)
        ) {
            Text(
                text = title,
                fontSize = metrics.fontSmall,
                color = KeyPalette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Surface(
                    color = KeyPalette.accent,
                    contentColor = KeyPalette.text,
                    shape = PillShape
                ) {
                    Box(
                        modifier = Modifier.padding(
                            horizontal = metrics.sizeDp * 0.022f,
                            vertical = metrics.sizeDp * 0.012f
                        )
                    ) {
                        Text(text = value, fontSize = metrics.fontTiny, color = KeyPalette.text)
                    }
                }
            }
            if (chevron) {
                // 和「长按设置」那一屏的入口行完全一致：同一个 Icon（ArrowForward）、
                // 同一个 tint（textMuted）、同一个 size（0.050 短边）。
                // 原来这里写的是 0.11 —— 箭头在开发者模式里明显比长按设置里大一圈，
                // 两屏并排看就不像同一个组件了。
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = KeyPalette.textMuted,
                    modifier = Modifier.size(metrics.sizeDp * CHEVRON_SIZE)
                )
            }
        }
    }
}
