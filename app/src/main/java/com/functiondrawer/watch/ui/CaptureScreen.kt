// 手表 UI 大量用到实验性接口，文件级统一 OptIn。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.functiondrawer.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.functiondrawer.watch.engine.CallableSet
import com.functiondrawer.watch.engine.Plotter
import com.functiondrawer.watch.engine.Viewport
import com.functiondrawer.watch.ui.theme.CurvePalette
import com.functiondrawer.watch.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 勾选行 / 名称标题 / 输入框 / ×✓ 之间统一的缝（占短边）。 */
private const val CAPTURE_GAP = 0.028f
/** 输入框 / 勾选行的高度：和函数创建界面的「选择栏」同一档（0.1356 短边）。 */
private const val CAPTURE_ROW_H = 0.1356f
/** 内容左右留白（占屏宽）。 */
private const val CAPTURE_SIDE_PAD = 0.06f
/** 圆屏上图片的边长占屏宽（正方形图片放在圆心附近时，≤0.707 才能整块进圆）。 */
private const val ROUND_PREVIEW_W = 0.70f
/** 非圆屏上图片最大高度占屏高（再高就把下面的表单全挤出去了）。 */
private const val SQUARE_PREVIEW_MAX_H = 0.62f

/**
 * 截图界面（1.3.11 引入，1.3.14 从浮窗改回**整屏路由**，1.3.17 补回勾选项 + 背景）。
 *
 * 背景两种：`blurBackdrop` 开 → 全局模糊图盖在不透明底色上；关 → 纯
 * `scheme.background`（白天白 / 夜间黑）。整屏路由不组合抽屉 / 图像界面。
 *
 * ## 预览 = "按预览框自己的像素尺寸画一遍，再原样录下来"
 *
 * 界面里看到的图片和存下来的 PNG **是同一层**（`capture.modifier` 录的就是这个框），
 * 所以三件事自然一致：
 * 1. 图片自带**不透明背景** —— 用户 1.3.17："因为没有背景，打开看就是黑色的背景"，
 *    之前录的是画布那一层（只有网格/曲线，透明底），现在是框（底 + 内容）；
 * 2. 图片尺寸不会小于画布：`Viewport` 的像素尺寸**就是**这个框的像素尺寸，
 *    曲线按这个视口重新采样（`GraphCanvas` 只在两者相等时才画曲线），
 *    网格 / 坐标轴按整块画布铺满，不会在图片里留白边；
 * 3. 勾选的坐标轴 / 数字 / 网格真实生效（只改本屏的 `previewSettings`，不写回设置）。
 *
 * 图片是静态的：没有拖动 / 缩放，框外上下拖动只滚页面。
 *
 * ## 圆屏的"空白矩形"
 *
 * 圆表四角被表壳切掉，图片放在最上面时上下沿会被裁。按用户要求，圆屏在图片**上面**
 * 垫一块"宽度 = 屏宽、高度 = 半个屏高"的空白，把图片顶到中间更宽的区域；方屏没有。
 */
@Composable
fun CaptureScreen(
    state: GraphState,
    metrics: WatchMetrics,
    onClose: () -> Unit,
    /** 存好之后回调：参数是最终文件名（带 `.png`）。 */
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val capture = rememberGraphCapture()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val isRound = isRoundScreen()

    // 三个开关的初值 = 当前设置；**只影响这张图**，不写回 state.settings
    // （用户："在截图界面调整的坐标轴、坐标数字和网格在返回函数界面之后不生效"）。
    var keepAxes by remember { mutableStateOf(state.settings.showAxes) }
    var keepNumbers by remember { mutableStateOf(state.settings.showNumbers) }
    var keepGrid by remember { mutableStateOf(state.settings.showGrid) }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colors
    val previewSettings = state.settings.copy(
        showAxes = keepAxes,
        showNumbers = keepNumbers,
        showGrid = keepGrid
    )
    val curveStyles = state.functions.associate { fn ->
        fn.id to CurveStyle(
            color = CurvePalette[fn.colorIndex.mod(CurvePalette.size)],
            lineStyle = fn.lineStyle,
            widthScale = fn.widthScale
        )
    }
    val rowHeight = metrics.sizeDp * CAPTURE_ROW_H
    val gap = metrics.sizeDp * CAPTURE_GAP

    // 返回键 = ✗（和过去整屏版本同一个语义）
    BackHandler(enabled = true) { onClose() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
        val screenW = geometry.screenW
        val screenH = geometry.screenH

        // ---- 背景：先铺一层**不透明**底色（必须挡住底下的图像界面），
        //      开模糊背景时再在上面盖当前画面的模糊图；关掉就是纯白 / 纯黑。----
        Box(
            Modifier
                .fillMaxSize()
                .background(scheme.background)
        )
        if (state.settings.blurBackdrop) {
            GlobalBackdrop(
                modifier = Modifier.fillMaxSize(),
                overlayAlpha = GLASS_BACKDROP_TINT
            )
        }

        // ---- 图片尺寸：形状与大小按屏幕形状定（圆屏正方形、方屏按画布宽高比）----
        val graphWpx = state.viewport.widthPx
        val graphHpx = state.viewport.heightPx
        val aspect = if (graphWpx > 0f) (graphHpx / graphWpx).coerceAtLeast(0.2f) else 1f
        val previewW: Dp
        val previewH: Dp
        if (isRound) {
            previewW = metrics.sizeDp * ROUND_PREVIEW_W
            previewH = previewW
        } else {
            val fullW = geometry.rowWidth(metrics, 1f, false)
            val rawH = fullW * aspect
            val maxH = screenH * SQUARE_PREVIEW_MAX_H
            val shrink = if (rawH.value > 0f) {
                (maxH.value / rawH.value).coerceAtMost(1f)
            } else {
                1f
            }
            previewW = fullW * shrink
            previewH = rawH * shrink
        }
        val previewWpx = with(density) { previewW.toPx() }
        val previewHpx = with(density) { previewH.toPx() }

        // 预览视口：像素尺寸**就是**图片框的像素尺寸；unitsPerPixel 取
        // `屏幕 / 图片` 的像素比（**取大**），把整块屏幕的世界范围完整放进来。
        //
        // ⚠️ 方向必须是"变大"（1.3.19 修正）：图片像素比屏幕少，每像素要代表**更多**
        // 世界单位，才能看到和图像界面**同一片范围**。之前写反了（乘 preview/graph），
        // 等于把范围缩小到原来的 (预览/屏幕)² —— 用户看到的"截图画幅和屏幕不一样"。
        // 取 max 而不是 min：圆屏的图片是正方形、屏幕不是，取 min 会在某一轴裁掉屏幕内容。
        val fitUpp = state.viewport.unitsPerPixel * maxOf(
            if (previewWpx > 0f) graphWpx / previewWpx else 1f,
            if (previewHpx > 0f) graphHpx / previewHpx else 1f
        )
        val previewViewport = remember(previewWpx, previewHpx) {
            Viewport(
                widthPx = previewWpx,
                heightPx = previewHpx,
                centerX = state.viewport.centerX,
                centerY = state.viewport.centerY,
                unitsPerPixel = fitUpp
            )
        }

        // 曲线按预览视口重新采样：`GraphCanvas` 只在"画布像素 = 视口像素"时才画曲线。
        var previewPlot by remember { mutableStateOf(state.plot) }
        LaunchedEffect(Unit) {
            snapshotFlow {
                listOf<Any?>(
                    state.functions.map {
                        listOf<Any?>(
                            it.id, it.name, it.mode, it.src1, it.src2,
                            it.tMin, it.tMax, it.visible, it.degrees
                        )
                    },
                    state.quality,
                    state.settings.angleUnit,
                    state.settings.precision,
                    state.sliders,
                    state.objects,
                    state.functionBindings
                )
            }.conflate().collect {
                val settings = state.settings
                val parameters = state.parameters()
                val samplingFunctions = state.functionsForSampling()
                val callables = CallableSet.ofFunctions(
                    samplingFunctions,
                    settings.angleUnit.isDegrees,
                    parameters
                )
                previewPlot = withContext(Dispatchers.Default) {
                    Plotter.plot(
                        if (settings.coordinateSystem.plotsCurves) {
                            samplingFunctions
                        } else {
                            emptyList()
                        },
                        previewViewport,
                        state.quality,
                        settings.angleUnit.isDegrees,
                        parameters = parameters,
                        callables = callables
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- 圆屏专用：图片上面的空白矩形（宽 = 屏宽，高 = 半个屏高）----
            if (isRound) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(screenH * 0.5f)
                )
            }

            // ---- 图片：底色 + 画布；`capture` 挂在最外层，PNG 自带背景 ----
            Box(
                modifier = Modifier
                    .size(previewW, previewH)
                    // 先背景再内容：录下来的图 = 不透明底 + 网格/坐标轴/曲线
                    // （不加任何描边 —— 用户 1.3.18："不要给截图边上加一层灰色的边框"）
                    .background(scheme.background)
                    .then(capture.modifier)
            ) {
                GraphCanvas(
                    viewport = previewViewport,
                    plot = previewPlot,
                    settings = previewSettings,
                    metrics = metrics,
                    gridColor = scheme.gridLine,
                    axisColor = scheme.onSurfaceVariant,
                    labelColor = scheme.onSurfaceVariant,
                    strokeGrid = metrics.strokeGrid,
                    strokeAxis = metrics.strokeAxis,
                    strokeCurve = metrics.strokeCurve,
                    curveStyles = curveStyles,
                    objects = state.objects + state.complexResultPoints(),
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(Modifier.height(gap))

            // ---- 表单（宽度按圆屏/方屏自适应）----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = screenW * CAPTURE_SIDE_PAD),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CaptureOptionRow("坐标轴", keepAxes, metrics, rowHeight) { keepAxes = it }
                CaptureOptionRow("坐标数字", keepNumbers, metrics, rowHeight) { keepNumbers = it }
                CaptureOptionRow("网格", keepGrid, metrics, rowHeight) { keepGrid = it }

                Spacer(Modifier.height(gap))

                Text(
                    text = "图片名称",
                    fontSize = metrics.fontSmall,
                    color = KeyPalette.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(gap))

                // ---- 输入框 + 右边的 `.png`（夜间白 / 白天黑）----
                Surface(
                    color = KeyPalette.normal,
                    shape = PillShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .border(width = 1.dp, color = KeyPalette.bubbleBorder, shape = PillShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { keyboard?.show() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = metrics.sizeDp * 0.038f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (name.isEmpty()) {
                                Text(
                                    text = "输入名称",
                                    fontSize = metrics.fontSmall,
                                    color = KeyPalette.textMuted.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = KeyPalette.text,
                                    fontSize = metrics.fontSmall
                                ),
                                cursorBrush = SolidColor(KeyPalette.text),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(metrics.sizeDp * 0.012f))
                        Text(
                            text = ".png",
                            fontSize = metrics.fontSmall,
                            color = KeyPalette.text,
                            maxLines = 1
                        )
                    }
                }

                if (failed) {
                    Spacer(Modifier.height(metrics.sizeDp * 0.012f))
                    Text(
                        text = "保存失败 请检查存储空间",
                        fontSize = metrics.fontTiny,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(gap))

                // ---- ✗ / ✓：和函数列表的 ⚙ / + 同款（WatchIconButton）----
                //
                // 方屏：可用宽度二等分、每颗键在各半区居中；
                // 圆屏：整行收窄到 0.62 屏宽，两颗键向中间靠。
                val buttonRowWidth: Dp =
                    if (isRound) metrics.sizeDp * 0.62f
                    else geometry.rowWidth(metrics, 1f, false)
                Row(
                    modifier = Modifier
                        .width(buttonRowWidth)
                        .height(metrics.iconButton)
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        WatchIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = "取消截图",
                            metrics = metrics,
                            containerColor = KeyPalette.normal,
                            modifier = Modifier
                                .graphicsLayer { alpha = if (saving) 0.45f else 1f }
                                .tutorialAnchor(TutorialAnchors.CAPTURE_CANCEL),
                            onClick = { if (!saving) onClose() }
                        )
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        WatchIconButton(
                            icon = Icons.Filled.Done,
                            contentDescription = "保存图片",
                            metrics = metrics,
                            containerColor = KeyPalette.accentDeep,
                            modifier = Modifier
                                .graphicsLayer { alpha = if (saving) 0.45f else 1f }
                                .tutorialAnchor(TutorialAnchors.CAPTURE_CONFIRM),
                            onClick = {
                                if (saving) return@WatchIconButton
                                saving = true
                                failed = false
                                scope.launch {
                                    val file = runCatching {
                                        capture.savePng(context, name)
                                    }.getOrNull()
                                    saving = false
                                    if (file == null) failed = true else onSaved(file.name)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(screenH * 0.05f))
            }
        }
    }
}

/**
 * 一行「勾选项」：左边说明文字、右边 Material 勾选框。
 *
 * 整行可点（用户不必正好点在 20dp 的小方框上）；勾选框本身只显示状态、
 * 不再接自己的点击（否则一次点击会触发两次 toggle）。
 */
@Composable
private fun CaptureOptionRow(
    label: String,
    checked: Boolean,
    metrics: WatchMetrics,
    rowHeight: Dp,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(PillShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onChange(!checked) }
            .padding(horizontal = metrics.sizeDp * 0.03f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = metrics.fontSmall,
            color = KeyPalette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = KeyPalette.accent,
                uncheckedColor = KeyPalette.textMuted,
                checkmarkColor = Color.White
            )
        )
    }
}
