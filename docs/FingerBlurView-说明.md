# FingerBlurView —— 手指揭示清晰图

## 交付物

| 文件 | 作用 |
|---|---|
| `ui/FingerBlurView.kt` | 自定义 View：模糊底 + 圆形清晰区 + 羽化边缘 |
| `ui/BitmapBlur.kt` | 纯 Kotlin 高斯近似模糊（降采样 + 3 次 box blur） |
| `ui/FingerBlurDemoActivity.kt` | 使用示例：生成清晰图 → 算模糊图 → `setContentView` |
| `AndroidManifest.xml` | 注册了一个独立的「模糊演示」启动图标 |

装好后桌面会多一个 **模糊演示** 图标，点开即可验收；它和主 App 的抽屉/设置流程完全隔离。

---

## 核心结构

```
onDraw:
  ① 画整屏模糊图（静态，永不移动）
  ② saveLayer(圆的外接矩形 ∩ View)
      ③ 在 layer 里画整屏清晰图 —— 与 ① 共用同一个 dstRect
      ④ 用 DST_IN 乘上径向渐变遮罩（圆内保留、圆外抹掉、边缘羽化）
  ⑤ restore
```

**为什么不用 RenderEffect 跟着手指跑**：RenderEffect 模糊的是「这个 View 自己的内容」。
View 一动，模糊边界跟着动，而背景不动 —— 屏幕上会出现一块飘着的糊图，或者整屏发糊。
这里的模糊图是**预先算好、固定铺满**的，每帧只多做一次圆形裁剪。

---

## 容易出错的地方

### 1. 两张图必须同尺寸

`clearBitmap` 与 `blurBitmap` 尺寸不同时，`CENTER_CROP` 的缩放比例就不一样，
圆内圆外会在接缝处错位。`BitmapBlur.blur()` 最后一步 `createScaledBitmap`
就是保证「返回值与原图同尺寸」，不要省。

### 2. 清晰图和模糊图必须用同一个 dstRect

```kotlin
computeCenterCropDst(blur, dstRect)
canvas.drawBitmap(blur, null, dstRect, bitmapPaint)     // 背景
...
canvas.drawBitmap(clear, null, dstRect, bitmapPaint)    // 圆内
```

只要两处用的是同一个 `dstRect`，像素级对齐是自动的。
如果圆内那块用手写坐标（比如 `drawBitmap(clear, x, y, ...)`），一定会错位。

### 3. 遮罩要贴「未裁剪的圆」，不能贴 layerRect

`layerRect` 会被 `intersect(0, 0, width, height)` 裁小（贴边时）。
如果拿它当遮罩的目标矩形，遮罩会被拉伸，圆的中心就偏了。
代码里 `maskDst` 专门保存未裁剪的圆外接矩形。

### 4. 圆跑出屏幕

- `layerRect` 与 View 求交，完全在外时直接 `return`（不绘制、不崩）；
- 遮罩仍按完整圆贴，超出部分被 layer 自动裁掉 —— `saveLayer` 不会因为负坐标崩溃。

### 5. dp 转 px

`revealRadiusPx` 的单位是 **px**。传 dp 时记得乘 density：

```kotlin
revealRadiusPx = 150f * resources.displayMetrics.density
```

### 6. 不要在 onDraw 里 new 对象

`onDraw` 一秒跑 60~120 次。代码里 `Paint` / `RectF` / `Path` 都是字段复用，
羽化遮罩是一张**预生成位图**（只在半径或羽化比例变化时重建），
拖动过程中零分配 —— 这是「跟手不卡」的关键。

### 7. 模糊要放后台线程

`BitmapBlur.blur()` 在 1080×2400、降采样 4 时约 5~15ms（中端机）。
示例里在 `onCreate` 同步跑没问题，因为只有一次；如果换成从相册/网络来的大图，
放到 `Dispatchers.Default` 里算完再 `setBitmap`。

---

## 可配置项

```kotlin
view.revealRadiusPx = 150f * density   // 半径（px）
view.featherFraction = 0.22f           // 羽化占半径比例，0 = 硬边
view.keepRevealOnRelease = false       // 抬手后：false 消失 / true 留在原地
```

## 接入到现有界面

```kotlin
val clear = BitmapFactory.decodeResource(resources, R.drawable.graph_hd)
val blur = BitmapBlur.blur(clear, radiusPx = (18f * density).toInt(), downSample = 4)

val reveal = FingerBlurView(context).apply {
    clearBitmap = clear
    blurBitmap = blur
    revealRadiusPx = 120f * density
}
// 用 AndroidView 塞进 Compose，或者直接 setContentView
```

在 Compose 里：

```kotlin
AndroidView(
    factory = { revealView },
    modifier = Modifier.fillMaxSize()
)
```

---

## 性能建议

| 项 | 做法 |
|---|---|
| 模糊图 | 只在图片变化时重算，不要每帧算 |
| 遮罩 | 预生成位图，半径变化才重建 |
| `saveLayer` | 只开圆的外接矩形，不要全屏 |
| 绘制对象 | `Paint`/`RectF` 复用，onDraw 里零 `new` |
| 触摸 | 直接用 `event.getX()/getY()`，不做平滑插值（平滑 = 延迟） |
| 降采样 | 4 是默认；内存紧张的老设备可到 8（半径会同比缩小，代码已兜住下限 1） |
