# FunctionDrawer · 手表函数图像绘制器

给 **Android 11「原屏」手表**（跑完整 Android，不是 Wear OS）写的开源DrawFunc App。
Material Design 3 观感，圆形表盘自适应，支持四种绘图模式，手势 + 旋转表冠操作。

```
y = f(x)       显函数
r = f(θ)       极坐标
(x(t), y(t))   参数方程
F(x, y) = 0    隐函数（marching squares）
```

**当前状态：已构建成功，两种包都在。**

| 包 | 大小 | 说明 |
|---|---|---|
| `app\build\outputs\apk\release\app-release.apk` | **1.59 MB** | 已签名（R8 压缩过），**推荐装这个** |
| `app\build\outputs\apk\debug\app-debug.apk` | 19.79 MB | 未混淆，方便调试 |

校验结果：清单 **7/7 通过**（minSdk 30 / targetSdk 34 / 应用名「DrawFunc」/ 无 Wear OS 标记）、
签名有效、zipalign 4 字节对齐、**56 个引擎单元测试全绿**。
数学正确性另有独立验证：四种模式渲染成 PNG 后做像素级反解，
**覆盖率 100%、杂散像素 0**（每个画出的像素都落在真实曲线上）。

---

## 真机验证（OPPO OWW251 / Android 11）

实测：**466×466 圆屏，density 320 → 逻辑宽度正好 233dp**，
与 `WatchMetrics` 的设计基准完全一致；CPU ABI 是 `armeabi-v7a`（32 位），APK 里已包含。

真机上暴露并修掉的两个问题：

| 现象 | 根因 | 修法 |
|---|---|---|
| **拖动时函数图像跟不上坐标系** | 网格按**当前**视口实时重画，而曲线是**上次采样那个视口**下的屏幕像素坐标，两者不同步 | 采样结果记住当时的视口（`PlotResult.viewport`），绘制时用「旧视口 → 当前视口」的**仿射增量**把整条曲线带过去（`Viewport.screenDeltaTo`）。**O(1)，不重采样**，且这段数学放在引擎层、被 `ViewportDeltaTest` 覆盖 |
| **旋转表冠不能缩放** | 表冠设备 `oplus_crown` 的能力里**只有 `REL_WHEEL`**（不是标准 Wear 编码器的 `REL_DIAL`）→ 事件以 `AXIS_VSCROLL` 进来，而代码只读了 `AXIS_SCROLL`，于是永远取到 0 | `onGenericMotionEvent` 里**逐个滚轮轴**尝试（SCROLL / VSCROLL / HSCROLL / WHEEL），取第一个非零值；同时把每次事件打进 logcat |

排查表冠（换机型时照用）：

```powershell
# 看输入设备能力：找有没有 REL_DIAL（标准编码器）还是 REL_WHEEL（滚轮式）
& 'D:\android sdk\platform-tools\adb.exe' shell getevent -pl | Select-String 'add device|name:|REL_'

# 看 App 到底收到了什么（需要先转一下表冠）
& 'D:\android sdk\platform-tools\adb.exe' logcat -s FunctionDrawer
```

按真机使用反馈做的界面调整：

- 顶部那条「当前函数」信息框**去掉了** —— 实际使用不会只有一个函数。
  改成**点击曲线**才弹出信息卡：显示解析式 + **零点** + **顶点**（`engine/FeatureFinder.kt`）。
  极坐标/参数方程/隐函数没有公认的「零点/顶点」，那时只显示解析式，不编数据。
- 左下角的「复位视野」按钮**去掉了**（双击画布依然是复位）。
- 参数范围（`0~2π` / `R` 等）的紫色 chip **只在极坐标模式下出现**：
  `y=f(x)` / `F(x,y)=0` 的 x 范围来自视口（缩放就是改范围），描点模式用户也要求不显示。
  `R` = 全体实数，实现成 ±20π（= ±3600°，正好和度制对上）。
- 四种模式里 `(x(t), y(t))` 那一档的显示名从「参数」改成**「描点」**（用户指定叫法，
  改的是 `engine/PlotModel.kt` 的 `PlotMode.label`，列表和编辑器一起跟着变）。
- 输入键盘是**4 个页签**：`数字` / `初等` / `字母` / `其他`，每页**固定 3 行 × 4 列**
  （`ui/keyboard/KeyboardModel.kt` 的 `KEYBOARD_ROWS`）。
  原来的编辑行（`← → C ⌫`）**撤掉了**：⌫ 在输入框右边、清空是长按 ⌫，
  光标移动交给了"逗号跳槽"（光标正对着模板里的 `,` 时按逗号 = 跳过它，
  这样 `log(,)` 的两个槽位才填得完）。
  每页内容见 `KeyboardModel` 里的 `digitsRows` / `elementaryRows` / `lettersRows` / `otherRows`。

### 按键风格：取自 `D:\Culculator`

整套按键的**形状与配色是照搬** `D:\Culculator`（一个 Java + XML 的手表计算器）的，
它那边就四个 drawable，取值原样搬进 `ui/WatchColors.kt` 的 `KeyPalette`：

| 用途 | 取值 | 来源 |
|---|---|---|
| 普通键（数字、字母、括号） | `#2D2F33` | `bg_btn_dark.xml` |
| 强调键（函数名、RAD/DEG、选中页签） | `#4B586B` | `bg_btn_accent.xml` |
| 危险键（`⌫`、滑删渐染） | `#824C51` | `bg_btn_danger.xml` |
| 文字 | `#FFFFFF`（次要 `#CCFFFFFF`） | `colors.xml` |
| 浮层（气泡/调色板/对话框/信息卡） | `#F21F2124` + 1dp `#33FFFFFF` 描边 | `bg_log_bubble.xml` |
| 形状 | `corners radius="50dp"` → **完整胶囊** | 四个 drawable 都是 |

要点：**扁平 + 低饱和 + 纯白字 + 胶囊**，没有渐变/阴影/elevation，层级只靠底色，
按下只有一层 13% 白的涟漪。Compose 里的等价形状是 `RoundedCornerShape(percent = 50)`
（见 `ui/theme/Theme.kt` 的 `PillShape`）。

配套改了两处几何，都是"胶囊要有可见的缝才读得出是胶囊"：

* 行缝 `GAP_FRACTION` 0.002 → **0.008**（0.47dp → 1.86dp）。它和 Culculator 的
  每键 2dp 边距同源，但取一半，避免把缝做得像"分开的按钮"。
  **改它必须同步改 `TAB_H`**（`TAB_H = pitch − GAP_FRACTION`），否则选择栏和按键不再等高。
* 键盘按键、页签 chip、`⌫` 用 `PillShape`；输入框卡片用 `watchShapeOf(高度)`
  （按高度取比例 0.42），因为"卡片"和"按键"是两种东西。

风格统一的范围是**整个界面**：函数列表行、设置行、值 chip、`+` / `⚙` 图标按钮、
信息卡、对话框与其按钮、toast、调色板。图上的曲线和网格**不动**。

### 键盘/列表之外的这一批新功能（对应需求 #1~#9）

| 需求 | 落点 | 说明 |
|---|---|---|
| #2 自动命名 | `ui/GraphState.kt` 的 `nextCurveName`/`nextUpperName` | 函数 `a(x)`/`b(x)`…（小写）、方程与点 `A`/`B`…（大写）。取**第一个未被占用的名字**而不是单调计数器 —— 删掉再建会复用，不会一路漂到 `z(x)`、`aa(x)`。字母用完按表格列名进位（`z→aa`）。列表第一行显示名字、第二行显示表达式 + 模式 |
| #3 滑动条 | `ui/Sliders.kt`（模型）+ `ui/SliderRow.kt`（行 UI）+ `GraphState.ensureSlidersFor` | 表达式里出现"什么也不代表的小写字母"（不是函数名、不是采样变量）→ 确定时自动生成 `-5~5`、步长 `0.1` 的滑动条。取值通过 `engine/Parameters.kt` 的 `ParameterSet` **在编译期折进表达式**，所以改值要重采样一次（播放时自动降精度）。播放键在滑动条左边，回到图像界面开始往返播放 |
| #5 字母表键盘 | `ui/keyboard/AlphabetModel.kt`（纯 Kotlin，可离线测）+ `ui/AlphabetKeyboard.kt` | 每排 6 格、返回键在第一格、最后一排是大小写三态 + `(` `)` `,`。入口是「其他」页第 3 行空格右边的 `abc` 键（空格 3/4 + abc 1/4，缝与别处相同）。可滚动，**表冠也能滚** |
| #6 改颜色立即生效 | `ui/GraphCanvas.kt` 的 `CurveStyle` | 颜色/线型/线径原来是从采样结果里取的（`Curve.colorIndex`），而采样在后台线程跑、隐函数高精度要几万个采样点 —— 于是改完颜色要等采样跑完才变，看起来就是"没重绘"。现在外观**每帧实时读**，改这些完全不触发采样 |
| #7 选中阴影 | `GraphCanvas` 的 `SHADOW_ALPHA = 0.6f` | 选中对象时在它下面再描一条更粗的同色 60% 透明线（比高斯模糊便宜，手表不掉帧）。交点/极值点用 `highlightPoints` 加同样的光晕 |
| #8 保存图像 | `ui/ImageExport.kt` | 用 Compose 1.7 的 `GraphicsLayer` **录制屏幕上真实画出来的那一层**，不自己重画（重画迟早和真机观感分叉）。存到应用外部目录 `Android/data/com.functiondrawer.watch/files/Pictures`（API 30 写公共 Pictures 要权限，这个 App 一个权限都没申请） |
| #9 的数学内核 | `engine/Geometry.kt` | 点/直线/线段/射线、两直线与**折线交**点、垂直/中垂/平行/角平分/切线、轴对称与中心对称、距离/面积/角度、按屏幕像素的吸附。34 个测试 |
| #9 的对象模型与渲染 | `ui/GraphObjects.kt` + `GraphCanvas` 的 `drawObjects` | 描点/直线/度量统一成 `GraphObject` 家族，都画在曲线**上面**（被曲线压住就等于没画）；吸附来的特征点画成空心，和随手点的实心点区分开 |
| #9 的工具目录与状态机 | `ui/FunctionTools.kt` | 五栏 16 个工具 + "每步要选什么/什么时候算完"。**纯 Kotlin、零 Compose 依赖**，用 `tools/run-tool-tests.ps1` 离线测（15 个）。用户点名的"没选到就不退出"（轴对称/中心对称/垂线/平行线/切线）在这里建模成 `staysUntilValid` |
| #9 的函数/方程变换 | `engine/CurveTransform.kt` | 轴对称/中心对称要能作用于**函数与方程**，而 `Geometry` 只能变换点；这里把采样折线逐点变换（源视口与目标视口**都显式传**，搞混会让整条曲线偏掉）。10 个测试 |
| #8 的文件名规则 | `ui/SaveName.kt` | 非法字符、以点开头、空名字退回时间戳、超长截断、自动补 `.png`。**纯字符串逻辑**，用 `tools/run-tool-tests.ps1` 离线测（10 个） |
| #8 的提示框 | `ui/SaveImageDialog.kt` | "保存这张图像？" + 命名输入框（隐藏扩展名）+ 取消/确定 + 存盘路径小字 + 字母表键盘（不显示大小写与括号） |
| #1 的复数内核 | `engine/ComplexMath.kt` | 复数类型（Smith 除法、pow/exp/log/sqrt/三角/双曲）+ 自包含的复数表达式求值器 + 点标签格式化。35 个测试，全部用解析值（`e^{iπ}=-1`、`i^i=e^{-π/2}`…） |

### 手势与撤销（这一版的硬约定）

| 手势 | 判据落在哪 | 说明 |
|---|---|---|
| 工具条上一颗键滑到另一颗键（第一屏 `⚙`↔`+`、第二屏 `📁`↔`🗑`） | `DragGestures.awaitKeyToKeySwipe`：手指**进入另一颗键的矩形**才算达成 | 达成**之前零消费、零动画** —— 所以"在 `⚙` 上拖了十几像素又没滑到 `+`"仍然是一次干净的短按（短按 / 长按 / 按住线圈全不受影响）。达成后两颗端键同时让位（alpha 0.18、缩放 0.82、位移 4% 行宽），中间那对 `←` / `→` 箭头在 220ms 里缩放淡入，松手 4 秒或滑回去就收起 |
| 撤销 / 重做 | `GraphState` 的快照栈（`pushUndo`，上限 20 步，`label` + `tag` 分组） | 撤销**只有这一条路**：所有"点此撤销"的 toast / 胶囊都删掉了，删除之类的操作只弹一句纯提示（点了没反应）。`tag` 让"同一个属性上连续打字"合成一步；一次动作分两段改的（如"删除并选中下一条"）用 `endUndoGroup()` 并成一步。**设置与视口刻意不进快照**（撤销不该把用户的显示设置也拽回去） |
| 切屏：第一屏左边缘往右滑 | 左 26% 屏宽 × 12%~92% 屏高，**再排掉工具条那一块矩形**（`PlotMetrics.toolbarStripRect`） | 工具条那一块是"唤出撤销菜单"的地盘，从 `⚙` 左侧那一小条起手不该切屏 |
| 切屏：第二屏右边缘往左滑 | 右 26% 屏宽 × **排掉工具条那一行本身**（上沿 `geometry.sy(TOOLBAR_TOP)`，下沿 = 上沿 + `0.125 × 短边` —— 和工具条 Box 自己的两个式子逐字相同） | 同一条理由：从 `🗑` 最外侧起手往左滑只该展开撤销菜单。这一行**之外**（上面 0~10% 屏高、下面行底~屏底）逐字照旧。注意下沿按**短边**算行高、而不是"真实屏高的 22.5%" —— 手机竖屏上前者只让出那一行（136dp），后者会多让 55dp |
| 行左滑删除（函数行 / 对象行 / 滑动条行） | 起手不消费 down，只有**水平往左**越过触摸容差才认领；松手越过 1/3 行宽即删 | 三处共用 `RowSwipeDelete`。用 `positionChangeIgnoreConsumed()` 读位移，所以祖先那条切屏手势每帧消费也不会把删除冻住；松手后的扫出**从松手位置**继续（`SwipeDeleteMotion`），不会跳回屏幕中间 |

三条通用规矩（机制与字节码依据写在 `ui/DragGestures.kt` 文件头）：

* **认领前一个字节都不消费**：方向不对（竖直 / 反方向）立刻退出，把整串事件原样让给子节点（按钮的 `clickable`、列表的滚动）；
* **长按只在按钮自己的矩形内判定**（用户要求）：手指飘出矩形就取消；
* 按键 / 可点控件的**宽度**按上面那条硬约定自适应，**高度与字号**仍按短边 `metrics.sizeDp`。

### 离线验证脚本一览

改这个项目**不需要**装到表上就能验一大半 —— 四个脚本都不联网、不用 Gradle：

| 脚本 | 覆盖 | 当前 |
|---|---|---|
| `tools/run-engine-tests.ps1` | 引擎：表达式/视口/采样/几何/复数/曲线变换 | 163 |
| `tools/run-keyboard-tests.ps1` | 数学键盘模型 + 字母表键盘模型 | 127 |
| `tools/run-tool-tests.ps1` | 功能工具状态机 + 保存文件名规则 + 文件格式 + **版面几何**（网格步长/标签吸附/圆弧宽度/工具条矩形/删除扫出/设置字段）+ 导出取名规则 | 217 |
| `tools/typecheck-ui.ps1` | 全部 UI 源码的 Compose 类型检查（跳过 MainActivity） | 0 error |

**注意**：`typecheck-ui.ps1` 只在真的出现 `error:` 时判失败；编译器把 warning 写到 stderr，
而脚本开头是 `$ErrorActionPreference='Stop'` —— 早先会把一条无害警告当成终止错误、
报成"类型检查失败"（真被这个骗过一次，已修）。另外这几个 `.ps1` **必须是带 BOM 的 UTF-8**
（`edit` 工具会抹掉 BOM，改完要写回），否则 PowerShell 5.1 按 GBK 解码会报一堆语法错。


### ⚠️ 坑：Compose 里"行高对了"≠"控件高对了"


这个 bug 咬过**两次**（第一次反馈"按键变矮了、缝变大了"，第二次"选择栏按钮更高、
下面的按键没变"），根因都是同一个：

```kotlin
Row(modifier = Modifier.height(rowHeight)) {          // 行高正确
    KeyButton(modifier = Modifier.weight(1f))          // ✗ weight 只管宽度
}
```

`weight(1f)` 只分配**宽度**；子项高度仍是 wrap-content，于是 `Surface` 按文字高度
收缩到 ≈16dp，而外层 `Row` 预留了整个 35.8dp，视觉上就是"按键很矮、行距很大"。
选择栏 chip 写的是 `weight(1f).fillMaxHeight()`，所以它反而比按键高。

**结论**：凡是"外面给了固定高度、里面要铺满"的控件，`weight` 后面必须再跟一个
`fillMaxHeight()`。只用算术核对 `Row` 的高度是查不出这类问题的 ——
算出来的 35.82dp 一直是对的，错的是按键没撑满它。加/改布局后请顺着
modifier 链一路看到**最内层的 Surface**。

交互总览：

| 手势 | 效果 |
|---|---|
| 单指拖动 | 平移（曲线与网格严格同步） |
| 双指捏合 | 以焦点为中心缩放 |
| 双击画布 | 复位视野到 `x ∈ [-10, 10]` |
| **单击曲线** | 选中并弹出信息卡（解析式 / 零点 / 顶点）；单击空白收起 |
| 旋转表冠 | 缩放（三条上报路径都接了） |
| 方向键 / 音量键 | 上下缩放、左右平移（部分机型表冠被映射成按键） |

---

## ⚠️ 硬约定：按键 / 可点控件的宽度一律"按**真实可用宽度**"自适应

> 用户原话（定为**长期规则**）：**"以后你写所有按键都要加上对屏幕宽度的自适应。"**
> 补充澄清（同一轮）：**"我说的宽度适配是像函数列表的示例的那些按钮一样。"**

### 唯一参照实现：`ui\FunctionList.kt` 里「示例」那一对 chip（用户点名的样板）

```kotlin
itemsIndexed(presets.chunked(2), key = { i, _ -> "preset-$i" }) { i, pair ->
    val presetKey = "preset-$i"
    val wf = rowWidthFraction(                       // ← 圆屏：这一项**此刻**所在高度的弧宽比例
        listState = listState, itemKey = presetKey, listTopPx = listTopPx,
        screenPx = with(density) { geometry.screenH.toPx() },   // 分母是**真实屏高**
        metrics = metrics, isRound = isRound
    )
    Row(
        modifier = Modifier
            .width(geometry.rowWidth(metrics = metrics, ringFraction = wf, isRound = isRound))
            .height(metrics.sizeDp * 0.085f)                        // 行高：短边（控件尺寸）
            .graphicsLayer { alpha = tailAlpha },
        horizontalArrangement = Arrangement.spacedBy(metrics.sizeDp * 0.018f)   // 缝：短边常量
    ) {
        pair.forEach { preset ->
            MiniChip(label = preset.label, selected = false, metrics = metrics,
                     modifier = Modifier.weight(1f).fillMaxHeight(),   // ← 列宽 = 行宽的 1/2
                     onClick = { onAddPreset(preset) })
        }
        if (pair.size == 1) Box(Modifier.weight(1f))               // 奇数个补占位，两列永远等宽
    }
}
```
```kotlin
// ui\FunctionList.kt 的 rowWidthFraction(...)：圆屏弧宽比例
if (!isRound) return 1f                                  // 非圆屏：比例交给 rowWidth 处理
val centerY = (listTopPx + info.offset + info.size / 2f) / screenPx   // 该项**此刻**的中心高度
val raw = metrics.circleWidthFraction(centerY) * ARC_MARGIN           // ARC_MARGIN = 0.95f
(round(raw * 100f) / 100f).coerceIn(MIN_ROW_WIDTH, SQUARE_ROW_WIDTH)  // 量化 + 夹 [0.52, 0.90]

// ui\FunctionList.kt 的 ScreenGeometry.rowWidth(...)：唯一那个宽度函数
fun rowWidth(metrics: WatchMetrics, ringFraction: Float, isRound: Boolean): Dp =
    if (isRound) metrics.sizeDp * ringFraction else screenW - sx(ROW_SIDE_INSET) * 2f
//                                                        ROW_SIDE_INSET = 0.03f（左右各 3%）
```

读出来的四条：**① 宽度只由 `geometry.rowWidth(metrics, 比例, isRound)` 出**；
**② 圆屏的比例来自"这一项此刻所在的高度"（中心高度）× 0.95，量化到百分位、夹 [0.52, 0.90]**；
**③ 非圆屏一律 `屏宽 − 左右各 3%`（`ROW_SIDE_INSET`）**；**④ 两列用 `weight(1f)` 分行宽**，
行高与缝都是**短边常量**（`0.085` / `0.018`），不随屏形变。

### 规则

**凡是按键、按钮、行、以及任何可点控件的宽度，都要从"这块屏真实能用的宽度"算出来**，
一律走上面那个 `geometry.rowWidth(...)`，不许再出现"按短边写死的宽度"：

| 屏形 | **宽度**基准 | **位置 / 分段高度**基准 | 备注 |
|---|---|---|---|
| 圆屏（466×466 / 233dp，**主设备**） | `geometry.rowWidth(metrics, 弧宽比例, true)` = `metrics.sizeDp × 比例`（**原算法一个字不动**） | `geometry.sy(比例)`（圆屏上 `screenH == sizeDp`，等价于旧的 `sizeDp × 比例`） | 圆屏必须**逐像素不变** |
| 非圆屏（手机 / 方表 / 横屏） | `geometry.rowWidth(..., false)` = **真实屏宽 − 左右各 3%**（`ROW_SIDE_INSET`，与示例按钮**共用同一个常量**） | `geometry.sy(比例)` = **真实屏高** × 比例 | 4 列按键因此自然铺满整宽 |

**控件尺寸（行高、字号、圆点、内边距、胶囊高度）继续按短边 `metrics.sizeDp` 缩放** ——
它们描述的是"手指多大、字多高"，不该跟着屏高/屏宽走（示例按钮的行高/缝也是这么干的）。
**只有"宽度"和"位置"换基准。**

### 为什么（真因：`WatchMetrics.sizeDp` 是**短边**）

`WatchMetrics` 的一切都建立在**短边** `sizeDp = min(可用宽, 可用高)` 上（那是圆表的安全区基准）。
圆表上**短边 == 屏高**、方表上也相等，所以"按短边算"在手表上一直看不出问题；
**手机上短边是"屏宽"**，于是所有"写成屏高百分比"的位置被压进顶部一个方格里：

| 屏 | 症状（都曾经真实存在，用户报过多次） |
|---|---|
| 手机 411×850dp | 函数列表 / 设置页 / 编辑器 / 属性屏的**内容只落在 y ≤ 388dp**（屏高的 46%），底下 462dp 全空 |
| 手机 411×850dp | 数学键盘三行行宽是 **370 / 316 / 185.7dp**（第 3 行只占屏宽 **45%**，两边各空 113dp、每颗键 44dp）→ 用户原话"**按键不是自动适应屏幕宽度，这样子很难看**" |
| 手机 | 设置页行宽 **349~370dp**、列表带下沿只到 370dp；属性屏各栏 313~382dp |
| 手机 | 键盘**行高**也按短边算，整块键盘只占屏幕上半截 →"**函数编辑页面也没有全屏显示**" |

一句话：**短边当宽度的基准，在长条屏上一定退化成"窄柱 + 中间空一片 + 内容挤在上半截"。**

### 怎么做（只有三个入口，别自己发明）

几何层在 `ui\FunctionList.kt` 的 `internal class ScreenGeometry`（`BoxWithConstraints` 量**真实** `maxWidth/maxHeight`；
`internal fun rememberScreenGeometry(maxWidth, maxHeight, metrics)` 造它）：

```kotlin
BoxWithConstraints(Modifier.fillMaxSize()) {
    val geometry = rememberScreenGeometry(maxWidth, maxHeight, metrics)
    val isRound = LocalConfiguration.current.isScreenRound

    // 位置 / 分段高度：真实屏高 / 屏宽
    .offset(y = geometry.sy(LIST_TOP))          // 圆屏：sizeDp × LIST_TOP（逐像素不变）
    .padding(horizontal = if (isRound) 0.dp else geometry.sx(ROW_SIDE_INSET))

    // 宽度：圆屏弧宽 / 非圆屏"屏宽 − 左右边距"
    .width(bandWidth(metrics, geometry, isRound, yTop, yBottom))            // 一段高度上的可用宽度
    .width(geometry.rowWidth(metrics, ringFraction, isRound))               // 一行 / 浮层
    .width(keyWidthBase(metrics, geometry, isRound) * DEL_W_FRACTION)        // 单颗键（宽度 = 基准 × 比例）
    .height(geometry.listHeight(metrics, geometry.sy(LIST_TOP)))            // "一路铺到屏幕底部"
}
```

| 想干什么 | 用哪个 | 在哪 |
|---|---|---|
| 得到真实几何 | `rememberScreenGeometry(maxWidth, maxHeight, metrics)` | `ui\FunctionList.kt` |
| **位置**（工具条、列表上沿、各段落点） | `geometry.sy(f)`（dp）/ `geometry.syPx(f)`（px）/ `geometry.sx(f)` | 同上 |
| **一段高度上的可用宽度**（键盘行、选择栏、工具条行） | `bandWidth(metrics, geometry, isRound, yTop, yBottom, arcMargin = 0.93f)` | `ui\MathKeyboard.kt` |
| **按键行高 + 行缝** | `keyGridMetrics(...)`（圆屏原样；非圆屏按真实屏高铺满、行高最多 1.25 倍键宽） | `ui\MathKeyboard.kt` |
| **单颗键的宽度** | `keyWidthBase(metrics, geometry, isRound) * 比例`，或干脆 `Modifier.weight(...)` 挂在已自适应的行里 | `ui\MathKeyboard.kt` |
| 铺到底的列表高度 | `geometry.listHeight(metrics, geometry.sy(TOP))` | `ui\FunctionList.kt` |

**硬性写法要求**：所有新逻辑一律写成 `if (isRound) 原来那一句 else 新算法`
（或"用 `sy()` 算、而圆屏上 `screenH == sizeDp` 时数值相同"），**比例常数不许改**。
于是圆表上"逐像素不变"是可以**用等式证明**的，而不是靠肉眼比。已经落地的五处：
`ui\SettingsScreen.kt`、`ui\FunctionEditor.kt`、`ui\MathKeyboard.kt`、
`ui\AlphabetKeyboard.kt`、`ui\PropertyEditor.kt`（数字小键盘 + 三个属性屏 + 详情屏）。

**唯一的例外：`WatchIconButton` 那一类"形状锁定"的图标键**（`⚙ + 📁 🗑 ✓ ✕ ⌫`）。
它们的宽高由 `ui\Common.kt` 按 `0.18 × 短边` 与 `ICON_BTN_ASPECT`（= 1.44:1 胶囊）**统一锁死**，
依据是用户更早的一条明确要求："请你让按键强制保持在一个形状" —— 把一个 1.44:1 的图标键
拉成满屏宽，它在手机上就不再是同一颗键了。所以**调用点只写比例、不写基准**（`0.16 × 短边`）：
`ui\FunctionEditor.kt` 的取消/完成、`ui\PropertyEditor.kt` 的属性屏 ✓ 与详情屏 ✓。

这不是"窄柱"，也不会留空隙：手机上短边 == 屏宽，它仍是**真实屏宽的 16%**；
真正自适应的是它所在的**那一行**（行宽走 `bandWidth`）。哪天真要连图标键本身也跟着
"屏宽 − 边距"走，改 `Common.kt` 的 `WatchIconButton` **一处**即可（各调用点不用动），
那属于"改全应用图标键"，不该由某一屏单独拉长一颗。

> ⚠️ 剩下的 `⌫`（`ui\MathKeyboard.kt` 的 `BackspaceKey`）**不属于**这个例外：
> 它没有锁形状（胶囊形，宽高由调用方给），所以它是自适应的 ——
> 宽度 = `keyWidthBase(...) × 0.155`（手机 59.9dp）。

### 与参照实现**有意**不同的地方（只有两处，且圆屏上都等价）

| | 参照实现（示例按钮行） | 键盘 / 编辑器分段 | 为什么不能照搬 |
|---|---|---|---|
| **圆屏的弧宽比例** | 列表行：取该项**此刻的中心高度** `cwf(centerY) × 0.95`，量化到百分位，夹 `[0.52, 0.90]` | 固定分段：取这一段**上下沿里更窄的那处** `min(cwf(yTop), cwf(yBottom)) × 0.93`（**改动前就是这一支**） | 分段是一整块矩形，最容易被表壳切的是**下沿的角**，所以取更窄的下沿、余量留得更多。换成参照那一支，圆表键盘三行会从 **209.8 / 179.2 / 105.3dp 变成 209.7 / 200.4 / 151.5dp**（第 3 行 +44%）——"圆屏逐像素不变"当场作废。两边都是"按所在行高度取弧宽"，只是保守程度不同 |
| **键与键的缝** | 短边常量：`Arrangement.spacedBy(metrics.sizeDp * 0.018f)`，不随屏形变 | 非圆屏上跟着**行高**等比放大：`sizeDp × gapFraction × (行高 / 短边行高)`（手机 3.3 → 6.4dp） | 参照是列表（行高恒定，非圆屏只是"一屏多露几行"）；键盘只有 3 行、又要铺到屏幕下半截（"键盘区到底部"），行高会长到 ≈1.94 倍 —— 缝不跟着放大就只剩键高的 2.8%（手表 5.4%），一颗颗胶囊会挤成一条。**圆屏上两者完全等价**（比值 = 1） |

**非圆屏那一支没有任何差异**：两者都是 `geometry.rowWidth(metrics, 1f, false)`
= `screenW − 2 × sx(ROW_SIDE_INSET)` = 手机 **386.3dp**，用的是**同一个共享常量** `ROW_SIDE_INSET`。
换句话说：**"宽度自适应"这件事全仓只有一份实现**（`ScreenGeometry.rowWidth`），
`bandWidth` / `keyWidthBase` / 各屏的 `rowWidthFraction` 只是给它准备参数的薄壳。

### 反例（这三个坑本仓库都踩过）

```kotlin
// ✗ 1) 写死 dp：同一块 466px 的屏，density 报 2.0 / 1.0 时物理大小差一倍（见"二、6"）
Modifier.width(48.dp)

// ✗ 2) 用"短边比例"当宽度：圆表上等价于弧宽、手机上退化成窄柱
Modifier.width(metrics.sizeDp * widthFraction)      // SQUARE_ROW_WIDTH = 0.90f 那一类
Modifier.width(metrics.sizeDp * 0.155f)              // 应写成 keyWidthBase(...) * 0.155f

// ✗ 3) 拿短边当"屏高"用：手机上"屏高的 90%"其实只是屏高的 43%，内容全挤在上半截
Modifier.offset(y = metrics.sizeDp * LIST_TOP)       // 应写成 geometry.sy(LIST_TOP)
Modifier.height(metrics.sizeDp * (LIST_BOTTOM - LIST_TOP))
```

**另外两个"看起来像新东西、其实是同一个坑"的信号**：

* 出现 `metrics.sizePx` 当**分母**（算"屏幕高度的百分之几"）→ 应该是 `geometry.screenH.toPx()`；
* 出现 `SQUARE_ROW_WIDTH` / `0.90f` 这种"方屏专用比例"→ 非圆屏应当**铺满**（`geometry.rowWidth`），
  那个常数只配当"还没测到这一项"时的兜底。

### 新加按键 / 新加一屏时的自查（照着做，两分钟）

1. **把屏设成"高 ≈ 宽的 2 倍"**（手机比例，例如 411×850dp）看一眼：
   按键有没有被"居中成一根窄柱"、行与行之间/屏幕左右有没有**莫名其妙的空隙**、
   内容是不是只占上半截。这三样任何一个出现，就是又把短边当宽度的基准用了。
2. **圆屏（466×466 / 233dp）上比对数值**：`geometry.screenH` 必须等于 `metrics.sizeDp`
   （两处都打一行 log 或对着上面的表算一遍）；改完的每个位置/宽度都应当和改动前**一模一样**。
3. **算术自查**：按键宽度 == `基准 × 比例`（没有裸的 `sizeDp`）；行宽 ==
   `bandWidth(...)` 或 `geometry.rowWidth(...)`；位置 == `geometry.sy(...)`；
   高度/字号/内边距 == `metrics.sizeDp × 比例`（这些**该**按短边）。
4. 跑 `powershell -ExecutionPolicy Bypass -File tools\typecheck-ui.ps1`（必须 **0 error**）；
   改过键盘/列表几何的，再顺手跑 `tools\run-keyboard-tests.ps1`。
5. 别用**绝对定位**给可点控件凑宽度：优先"父行自适应 + 子项 `weight(...)`"，
   这样宽度天然跟着真实可用宽度走，也不会出现"加起来的宽度越过父行"的破绽。

> 备注：本节的结论覆盖了「五、已知限制」里那句"方形屏能用，但没有专门优化" ——
> 非圆屏（手机 / 方表）现在和圆屏走**同一套**几何，只是两个分支的基准不同。

---

## 一、构建（已实测跑通，照抄即可）

### 前置

| 组件 | 本机位置 | 说明 |
|---|---|---|
| JDK 17 | `D:\jdk17` | AGP 8.5 要求 17 |
| Android SDK | `D:\android sdk` | 需要 `platforms/android-34`、`build-tools/34.0.0` |
| Gradle 8.7 | `D:\gradle-home\wrapper\dists\gradle-8.7-bin\af3un6e4ivqgjcdo5lfa5efog\gradle-8.7` | **已解压好的发行版**，直接调用 |

### 命令

```powershell
$env:JAVA_HOME = 'D:\jdk17'
$env:GRADLE_USER_HOME = 'D:\gradle-home'
$g = 'D:\gradle-home\wrapper\dists\gradle-8.7-bin\af3un6e4ivqgjcdo5lfa5efog\gradle-8.7\bin\gradle.bat'

& $g -p 'D:\FunctionDrawer' --no-daemon --console=plain :app:assembleDebug :app:testDebugUnitTest
```

> **不要**用 `gradlew.bat`。它的 distributionUrl 哈希目录与本地已解压的那份对不上，
> 会去重新下载 130 MB 的 `gradle-8.7-bin.zip`（这台机器上下得极慢）。
> 直接用上面那条已解压的 `gradle.bat`，完全跳过下载。

### 装到表上

优先装 **1.59 MB 的 release 包**（手表存储紧张）：

```powershell
# 一步到位（含设备排查、信息打印、启动、崩溃日志）
powershell -ExecutionPolicy Bypass -File tools\install-to-watch.ps1

# 或者手动
& 'D:\android sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\release\app-release.apk
& 'D:\android sdk\platform-tools\adb.exe' shell am start -n com.functiondrawer.watch/.MainActivity
```

### 签名

release 包用 `release.keystore` 自签名，口令在 `keystore.properties` 里
（两者都已被 `.gitignore` 忽略）。**如果要正式发布，请换一套自己的密钥和口令** ——
现在这套只是为了侧载方便，口令就是默认值。

#### ⚠️ release 包必须再跑一次三签（v1 + v2 + v3）

```powershell
powershell -ExecutionPolicy Bypass -File tools\sign-release.ps1
```

为什么构建完还要这一步（三条都是实测过的）：

* `apksigner` **默认只签"这个包的 minSdkVersion 需要的那几档"** —— 本项目
  `minSdk = 24`（Android 7）已经支持 v2，于是 AGP 产出的 release 包**只有 v2+v3，v1 是关的**；
* `app/build.gradle.kts` 里那句 `signingConfigs { enableV1Signing = true }` 在 AGP 8.5.2 上
  **被忽略**（写进去也没用，产物仍然 `v1 = false`），所以只能构建后补签；
* 缺 v1 的后果是**只认 JAR 签名的老设备装不上**（用户就是在 Android 7 那台机器上撞到的）。

`tools\sign-release.ps1` 干三件事，顺序不能反：`zipalign -p -f 4`（**签名之前**对齐）→
`apksigner sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true`
→ 校验三档 + `zipalign -c`。产物在 `build\apk-signed\DrawFunc-v1v2v3.apk`。
**不需要装任何额外工具**：`java`（`D:\jdk17`）+ SDK 里的 `zipalign` / `apksigner` 就够，
连 `jarsigner` 都不用（apksigner 自己会做 JAR 签名）。

> ⚠️ 判断"v1 到底签上没有"**不能**看 `apksigner verify` 的默认输出：本包 minSdk = 24，
> 默认那条命令只检 v2/v3，**即使 v1 完好也会打印 `Verified using v1 scheme: false`**。
> 必须显式加 `--min-sdk-version 21`。`tools\verify-apk.ps1` 现在会自动用这个口径
> 把 v1/v2/v3 三行都打出来。

要重新生成密钥：

```powershell
& 'D:\jdk17\bin\keytool.exe' -genkeypair -v -keystore release.keystore -storetype PKCS12 `
    -alias functiondrawer -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass <你的口令> -keypass <你的口令> -dname "CN=..., O=..., C=CN"
```

### 校验产物

```powershell
powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1     # 默认校验 release 产物（没有才退回 debug）
powershell -ExecutionPolicy Bypass -File tools\verify-apk.ps1 -ApkPath build\apk-signed\DrawFunc-v1v2v3.apk
```

它会打印包名 / minSdk / targetSdk / label / 启动项 / 图标 / 无 Wear 标记这七项，
外加**签名档位（v1 / v2 / v3）、zipalign 对齐、SHA256**。

### 只想验证数学引擎（不需要 Gradle、不需要网络）

引擎是纯 Kotlin、零 Android 依赖，能单独编译并跑测试，十几秒出结果：

```powershell
powershell -ExecutionPolicy Bypass -File tools\run-engine-tests.ps1
```

> 注意：`tools\run-engine-tests.ps1` 必须以**带 BOM 的 UTF-8** 保存。
> Windows PowerShell 5.1 在无 BOM 时会按 GBK 解码，中文注释会把脚本语法搞坏。

---

## 二、这台机器的环境坑（都踩过，已解决）

这一节是给未来的自己/协作者看的 —— 不知道这些会浪费几个小时。

### 1. 网络：Maven Central 的一个域名不通

| 地址 | 结果 |
|---|---|
| `repo.maven.apache.org`（`mavenCentral()` 指向它） | ❌ TLS 被重置（curl exit 35） |
| `repo1.maven.org`（同一个 Maven Central） | ✅ 200 |
| `dl.google.com`（Google Maven） | ✅ 200 |
| `maven.aliyun.com`（public / google / gradle-plugin） | ✅ 200 |
| `mirrors.cloud.tencent.com` | ✅ 200 |
| `repo.huaweicloud.com` | ❌ TLS 失败 |

所以 `settings.gradle.kts` 里**阿里云镜像放最前面**，并用 `repo1.maven.org` 取代 `mavenCentral()`。

另外：沙箱限制下所有 HTTPS 都会失败（裸 TCP 能连、TLS 一律被重置），
需要放开网络才下得动依赖。

### 2. `--offline` 不可用

`D:\gradle-home\caches` 里**没有 `metadata-*` 目录**，Gradle 的离线模式无法工作；
而且 AGP 8.5.2 / Kotlin 2.0.20 插件自身的依赖（guava、asm、protobuf、sdklib…）
本机缓存里是缺的，必须联网解析。别浪费时间试 `--offline`。

### 3. 版本组合是被缓存锁死的

| 组件 | 版本 | 为什么是这个 |
|---|---|---|
| Gradle | 8.7 | 本地只有这一份已解压 |
| AGP | 8.5.2 | 缓存里可用的是 7.2.2 / 8.5.2 / 9.3.0；9.x 需要 Gradle 9（下不到） |
| Kotlin | 2.0.20 | 配套的 `compose-compiler-gradle-plugin` 2.0.20 在缓存里 |
| Compose BOM | 2024.09.00 | 对应 ui 1.7.3，缓存里齐 |
| compileSdk | 34 | SDK 管理器不能联网补装，只有 android-34 |

### 4. 为什么用 Compose Material 2 而不是 material3

依赖缓存里**没有** `androidx.compose.material3` 构件。为一个配色层去联网拉一堆
新依赖不划算，所以**自己实现 MD3 的令牌层**，挂在 M2 的 `Colors` 上：

- `ui/WatchColors.kt` —— MD3 全套色彩角色（`primaryContainer`、`surfaceContainerHigh`、
  `outlineVariant`…），用**扩展属性**补到 `androidx.compose.material.Colors` 上。
- `ui/theme/Theme.kt` —— MD3 的字号阶梯与圆角（`WatchShapes`）。

于是界面代码里写的仍然是 `MaterialTheme.colors.surfaceContainerHigh` 这类 MD3 语义。
**将来想换回官方 material3，删掉这些扩展属性和对应 import 即可。**

### 5. APK 体积：`material-icons-extended` 是重量级陷阱

第一版 debug APK 有 **49.96 MB**，其中 `classes.dex` 一个文件就 42 MB ——
元凶是 `material-icons-extended`（几千个图标定义）。

改成只用 `material-icons-core`（`material` 自身会传递依赖它）之后：
**49.96 MB → 19.79 MB（debug）；开了 R8 的 release 只有 1.59 MB**。
做法：图标全部换成 core 里确定存在的 `Icons.Filled.*`（含 `AutoMirrored` 变体），
并把列表里的「显示/隐藏」改成**点圆点切换**（实心=显示、空心=隐藏），省掉一个图标。

### 6. 国产原屏表的 density 极不统一

同一块 466×466 的屏，有的机型报 density 2.0（=233dp 逻辑宽），有的报 1.0（=466dp）。
按固定 dp 做的界面在其中一种上会小到点不中。

所以所有尺寸都按**屏占比**算 —— 见 `ui/WatchMetrics.kt`。
它连「圆屏在某个高度上的可用宽度」都算好了，键盘两端的行会自动收窄，
否则底部两角的按键会被表壳物理切掉。

---

## 三、代码结构

```
app/src/main/java/com/functiondrawer/watch/
├── MainActivity.kt              沉浸式全屏 + 表冠兜底（onGenericMotionEvent）
├── engine/                      ★ 纯 Kotlin，零 Android 依赖，被 35 个单元测试覆盖
│   ├── Lexer.kt                 词法：数字/标识符/数学符号（× ÷ − √ ∞ π）
│   ├── Expression.kt            Pratt 解析 + 求值 + 报错定位（含隐式乘法）
│   ├── MathFns.kt               函数与常量表，Γ/erf/阶乘
│   ├── Viewport.kt              世界↔屏幕映射、缩放焦点保持、网格步长
│   ├── PlotModel.kt             PlotFunction / PlotMode / Quality / PlotResult
│   └── Plotter.kt               ★ 四种采样算法
└── ui/
    ├── WatchMetrics.kt          圆屏自适应：尺寸全按屏占比，含圆弧宽度计算
    ├── WatchColors.kt           MD3 色彩角色（手工实现，挂在 M2 Colors 上）
    ├── GraphState.kt            应用状态 + 示例库
    ├── GraphCanvas.kt           只负责画线（一次 drawPath 一条曲线）
    ├── MathKeyboard.kt          自绘数学键盘 + 表达式输入框（不用系统输入法）
    ├── FunctionEditor.kt        编辑器：模式切换 / 表达式 / 参数范围 / 键盘
    ├── FunctionList.kt          函数列表 + 示例库
    ├── GraphScreen.kt           主界面：手势 + 表冠三路兼容
    ├── Store.kt                 SharedPreferences 持久化
    ├── Common.kt                MiniChip / WatchIconButton / 数字格式化
    └── theme/Theme.kt           MD3 字号与圆角、曲线色板
```

### 关键设计取舍

**1. 引擎与 UI 彻底分离。**
采样、插值、断线全在 `Plotter` 里做完，输出**屏幕像素坐标**。UI 层只画线。
好处：最需要正确性的部分能被纯 JVM 单元测试覆盖，UI 层没有可出错的数学。

**2. 四种模式用三套不同算法。**
- 显函数：沿屏幕每一列取一个样本；相邻样本跳变超过 2 倍屏高就断线 ——
  否则 `1/x` 会被画成一条假的竖线，`tan(x)` 同理。
- 参数/极坐标：先在 t/θ 上均匀取样，再对"屏幕上看起来不够直"的区间递归二分
  （按中点离弦的距离判断）。曲线会自交、会旋转，只能在屏幕空间判断该不该细分。
- 隐函数：网格 + marching squares，并对产生线段的单元递归细分，
  **细分时重新求值**而不是线性插值，所以 `x²+y²=1` 这种细线不会被网格粒度切碎。

**3. 手写键盘，不用系统输入法。**
手表上系统输入法弹出来能占满整块屏，完全不可用。所以自己画光标、自己做增删
（含左右移动光标），键盘行宽按圆弧收缩。

**4. 表冠接三套输入。**
"原屏手表"这个品类的表冠上报方式不统一，三条路径都收敛到同一个缩放函数：
`onRotaryScrollEvent`（标准编码器）、`Activity.onGenericMotionEvent` 的 `ACTION_SCROLL`、
以及被映射成方向键/音量键的机型。

**5. 手势用低精度、停下再出高精度。**
视口变化时先用 `Quality.FAST` 保证跟手，视口 140ms 没变化再切回 `NORMAL` 重算。
采样一律在 `Dispatchers.Default` 上跑，不阻塞手势。

---

## 四、表达式语法

支持省略乘号、右结合幂、绝对值、阶乘、Unicode 数学符号：

```
2x            隐式乘法
3sin(x)       = 3·sin(x)
(x+1)(x-1)    = x² − 1
2^3^2         512      （幂右结合）
-2^2          −4       （一元负号优先级低于幂）
|x|           绝对值
5!            阶乘（非整数走 Γ(n+1)）
2×3  6÷2      手写符号也能吃
```

函数：`sin cos tan cot sec csc`、`asin acos atan atan2`、`sinh cosh tanh coth`、
`asinh acosh atanh`、`exp ln lg lb log`、`sqrt cbrt root`、`abs sign`、
`floor ceil round trunc frac`、`gamma erf sinc`、`pow hypot mod min max clamp`、`deg rad`

常量：`pi π e tau τ phi φ inf ∞ nan`

约定：`log(x)` 是以 10 为底（跟 Desmos 一致），`log(b,x)` 是以 b 为底，`ln` 是自然对数。

写错变量名会明确报错并列出可用变量，而不是悄悄当成 0 —— 手表上这一点尤其重要。

---

## 五、已知限制

- 隐函数靠网格发现零点，**比网格还细的特征会被漏掉**（例如振荡极快的曲线）。
  提高精度档位（`Quality.HIGH`）可以缓解，代价是求值次数上升。
- 极坐标/参数方程默认范围 `0 ~ 2π`，编辑器里可换 `0~4π`、`−π~π`、`−10~10`。
- 没做函数值表格、交点求解、导数/积分 —— 不在当前范围内。
- 布局只在 466×466 圆屏的尺寸模型上推演过；方形屏能用，但没有专门优化。
- 参数方程的 t 范围只能选预设值，不能任意输入。

---

## 六、许可证

MIT（见 `LICENSE`）。如需改为 GPL-3.0 / Apache-2.0 请告知。
