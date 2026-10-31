package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.CalcPrecision
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 绘图界面的**尺度换算**（网格步长 + 刻度数字的贴边规则），外加**列表行左滑删除**
 * 的那几个尺度（滑够 1/3 行宽才删、甩出 28%、渐染 85%，见文件末尾那一节）。
 *
 * ## 为什么单独一个文件、而且**纯 Kotlin**（没有任何 Compose / Android import）
 *
 * 这两件事都是"纯算术"，却决定了用户一眼能看到的东西：
 *   * 网格多密（格距算错 → 手机上格线糊成一片、刻度数字叠在一起）；
 *   * 刻度数字在屏幕边缘怎么摆（算错 → 数字被裁掉、或者跑到离刻度很远的地方）。
 *
 * 它们不该只靠"装到表上试一眼"来验证，所以刻意写成**零依赖的纯函数**，
 * 交给 `tools\run-tool-tests.ps1` 那套离线单测钉住（见 `PlotMetricsTest.kt`）：
 * 那个脚本只能编"不 import Compose"的文件。
 *
 * ## 为什么网格步长要在 UI 层重算，而不是直接用引擎的 `Viewport.gridStep`
 *
 * 引擎里的 `Viewport.gridStep` 是按**像素**定的（每格约 60px，见 `Viewport.kt`）。
 * 这在 466×466 / density 2 的圆表上正好（60px = 30dp），但手机屏幕 density 高、
 * 逻辑尺寸却不一定大 —— 同一句"每格 60px"到了 density 3 的手机上只有 20dp，
 * 格线一下子密了三成，数字也就挤到一起了。
 *
 * 而**引擎不能改**（本轮的规定：`engine\` 目录一个字节都不许动），而且从原理上
 * 也不该由引擎管：density 是纯粹的 UI 概念，引擎是"零 Android 依赖的纯数学层"，
 * 它连 `Density` 这个类型都看不见。所以换算放在 UI 层做：**UI 算好"一格多少世界单位"
 * 再交给绘制**，引擎那边只负责它自己需要的 60px（隐函数 marching 的格子精度）。
 *
 * 换算的**基准**是"屏幕短边"而不是 density：本项目的尺寸语言全程是"短边 × 比例"
 * （`WatchMetrics.frac()`：图标按钮、行高、字号都是这么来的），网格走同一条才自洽。
 * 具体是"每格 ≈ 短边的 12.9%，且不少于 60px"：
 *
 * | 设备（短边 px） | 目标格距 | 相对引擎原来的 60px |
 * |---|---|---|
 * | 466（圆表基准） | 60px | **逐位相同**（圆屏观感一个像素都不变） |
 * | 400（方表） | 60px（下限） | **逐位相同** |
 * | 1080（手机） | 139px | 格子宽 2.3 倍，一屏约 7.8 格（和圆表一样） |
 *
 * 为什么不按 density 算：同一块 466×466 的屏，有的设备报 density 2、有的报 1
 * （见 `WatchMetrics` 的说明）。按 density 算出来的 dp 目标在两类设备上会差一倍，
 * 按短边算则**只认像素**：一块屏无论报什么 density，格子像素数都一样。
 * 而"12.9% 短边"这个比例就是圆表上那 60px 的占比 —— 手机因此不会比圆表更密，
 * 而且**刻度数字与格子的相对大小和圆表完全一致**（字号也是按短边算的），
 * 圆表上"数字不重叠"这个已经被用户接受的事实，于是被原样搬到大屏上。
 */

/**
 * 圆表基准短边（px）：466×466 的圆屏。
 *
 * 它是"引擎那 60px 一格"的出处，也是"短边比例"的分母。
 */
const val WATCH_BASE_PX = 466f

/**
 * 网格格距的**像素下限** = 圆表那一格 = 60px。
 *
 * 这就是引擎原来的口径（`Viewport.gridStep` 里那句 `unitsPerPixel * 60.0`）。
 * 它同时兼两个职责：
 *   * **下限** —— 短边不足 466px 的屏（小表、方表）继续按 60px 走，绝不因按比例
 *     缩放而变密；
 *   * **分母** —— [GRID_TARGET_SIZE_FRACTION] 的分子，保证 466px 的圆表算出来正好 60px。
 */
const val MIN_GRID_PX = 60f

/**
 * 目标格距占屏幕短边的比例 = 60 / 466 ≈ 0.1288。
 *
 * 圆表上等于 [MIN_GRID_PX]（恒等，所以圆表逐位不变）；屏越大格子按比例越大。
 */
const val GRID_TARGET_SIZE_FRACTION = MIN_GRID_PX / WATCH_BASE_PX

/**
 * 相邻两个刻度数字之间至少留出的空隙（dp）。
 *
 * 光有格距还不够：数字的宽度随位数变化（`5` 很窄、`-1000` 很宽），
 * 所以绘制侧还要按**实测字宽**判断"这两个数字会不会压在一起"，
 * 这个常量是那份判断里要留的最小缝。
 *
 * 为什么是 5dp（≈ 圆表 10px）而不是更大的数：这条规则一旦放宽过头，
 * 就会把**本来画得下的数字**挤掉 —— 圆表在缩放中有一档格距是 45px、
 * 数字宽约 28px，缝只有 17px（8.5dp），10dp 的缝会让那一档**每隔一个丢一个**
 * （实测平均标签数 8.4 → 5.3），那就是"圆屏观感变差"。5dp 能过那一档，
 * 又足够让两个数一眼分得开（数字高约 20px，缝 10px 是半个字高）。
 */
const val LABEL_MIN_GAP_DP = 5f

/**
 * 刻度数字**贴边预算**的兜底下限（dp）—— 它只是下限，不是真正的判据。
 *
 * ## 真因（用户报"刻度数字贴边没有实装"，这一条是主因）
 *
 * 上一轮的预算就是这个 `22dp × density`。它**永远小于"边缘那一个刻度至少需要挪多远"**，
 * 于是 `snapLabelAnchor` 那个"挪得动就贴边、挪不动就不画"的分支**几乎永远走不到**，
 * 表现和"没写这段代码"完全一样。算式（半宽 = 文字宽度的一半，安全区 = 短边 6%）：
 *
 * ```
 *   需要挪的距离 = 文字盒右沿 − 可用区右界 = (刻度x + 半宽) − (屏宽 − safeInset)
 *                = safeInset + 2 × 半宽      ← 刻度正好落在屏幕右缘时
 * ```
 *
 * | 设备 | safeInset | 半宽（`-10` 这种三字符） | 需要挪 | 旧预算 `22dp×density` |
 * |---|---|---|---|---|
 * | 圆表 466px / density 2 | 28px | ≈14px | **56px** | 44px ❌ 挪不动 |
 * | 圆表 466px / density **1**（有些表就这么报，见 `WatchMetrics`） | 28px | ≈14px | **56px** | 22px ❌ 差一倍半 |
 * | 手机 1080px / density 3 | 65px | ≈22px | **109px** | 66px ❌ 挪不动 |
 *
 * 也就是说：手机上**一个都贴不上**；圆表上只有最窄的数字（`5` 这种，半宽约 5px）
 * 勉强够（39px < 44px）—— 那就是"有时灵、有时不灵"的来历。
 *
 * ## 现在的判据：预算按**几何**算，dp 只当兜底
 *
 * 见 [labelSnapBudgetPx]：`safeInset + 2 × 半宽 + 1% 短边`，和 `22dp × density` 取大者。
 * 几何那一项**与 density 无关**（和网格格距同一条思路：项目里所有"多少像素"
 * 都按屏幕短边算，因为同一块 466px 的屏有的报 density 2、有的报 1）——
 * 这正是"圆表报 density 1 时贴边失效"的解药。
 * 保留下来的 22dp 只在小屏上兜底（万一短边算出来的预算比它还小）。
 *
 * 为什么**不是**无上限：刻度本来就在屏外很远时不该把数字拖进屏幕中间
 * （那会让数字看不出属于哪条刻度线）。几何预算天然有界 —— 它正好等于
 * "刻度贴着自己那侧的屏幕边缘时所需的距离"，再多一点也不给。
 *
 * ## ⚠️ 绘制侧现在**不再**用这个函数（保留它是因为离线单测钉着它）
 *
 * 它有两个洞，见本节开头那张表：① 只按"屏幕边缘"算，没算**可用区比屏幕窄多少**
 * （圆屏弦宽，一算就少几十像素 → 纵轴靠边的数字被丢）；② 判定是连续量、没有量化。
 * 绘制侧改用 [labelFollowBudgetPx]（几何同源、按刻度可见性算）；这个函数留在文件里
 * 当"上一版的参照实现"，`PlotMetricsTest` 里那几条对拍断言仍然钉着它。
 */
const val LABEL_MIN_SNAP_DP = 22f

/**
 * 贴边预算在"安全内缩 + 两倍半宽"之外再给的富余，占**屏幕短边**的比例。
 *
 * 为什么要一点富余：文字盒在纵向上并不以锚点为中心 —— `drawLabel` 的盒子是
 * `baseline − 0.85 字号` 到 `baseline + 0.25 字号`，而纵轴数字的锚点是
 * `刻度y + 0.36 字号` 再换算成中心的，这两处的小数差最多约 `0.06 字号`
 * （圆表上 ≈1.2px）。不留富余的话，"正好落在边缘的那一个"会因为这点零头被判掉，
 * 用户看到的就是"最后一个数字偶尔还是消失"。
 *
 * 为什么是 1% 短边（圆表 4.7px、手机 10.8px）而不是一个固定 dp：它要**跟屏幕一起缩放**
 * —— 大屏上字号大、这点零头也大。1% 比 0.06 字号（≈0.25% 短边）宽裕 4 倍，
 * 又小到不可能把"屏外很远的刻度"拉进来（那种刻度离边缘有几十上百像素）。
 */
const val LABEL_SNAP_SLACK_FRACTION = 0.01f

/**
 * 这一个刻度数字允许挪动的**最大距离**（像素）—— 由几何算出，不是拍一个数。
 *
 * ```
 *   预算 = max( LABEL_MIN_SNAP_DP × density ,  safeInset + 2 × 半宽 + 1% × 短边 )
 * ```
 *
 * 第二项就是"**刻度正好落在屏幕边缘时**为了让文字盒完整可见所需挪动的距离"：
 * 文字盒要落进 `[safeInset, 屏宽 − safeInset]`，盒宽 `2 × 半宽`，
 * 而锚点（文字中心）在刻度上 —— 把刻度推到屏缘，需要挪 `safeInset + 2 × 半宽`。
 * 于是"贴着屏幕边缘那一个刻度"**一定**挪得动（这是用户要的"贴住屏幕边缘"），
 * 而"已经跑到屏外很远"的刻度仍然挪不动、照旧不画。
 *
 * @param safeInsetPx 安全内缩（像素）：圆屏上是"表壳切不到"的余量，
 *   方屏/手机上是四边的留白。**所有**轴向共用同一个值。
 * @param halfExtentPx 这个文字的**半宽或半高**（像素）。横向用 `measureText/2`、
 *   纵向用字体度量的 `(descent − ascent) / 2`（`GraphCanvas.drawCartesianLabels` 里算）。
 * @param sizePx 屏幕短边（像素）—— 富余那一项按它缩放，**不看 density**（理由见
 *   [LABEL_MIN_SNAP_DP] 那张表）。
 * @param densityScale 每 dp 多少像素，只用来算那个兜底下限。
 * @return 允许挪动的距离（像素）。参数非法（NaN / 负数）时返回 0 ——
 *   那等于"不许挪"，调用方仍然按老规矩"放得下就画、放不下不画"，不会崩也不会乱画。
 */
fun labelSnapBudgetPx(
    safeInsetPx: Float,
    halfExtentPx: Float,
    sizePx: Float,
    densityScale: Float
): Float {
    if (!safeInsetPx.isFinite() || safeInsetPx < 0f) return 0f
    if (!halfExtentPx.isFinite() || halfExtentPx < 0f) return 0f
    val floor = if (densityScale.isFinite() && densityScale > 0f) {
        LABEL_MIN_SNAP_DP * densityScale
    } else {
        0f
    }
    val slack = if (sizePx.isFinite() && sizePx > 0f) sizePx * LABEL_SNAP_SLACK_FRACTION else 0f
    val geometric = safeInsetPx + 2f * halfExtentPx + slack
    return if (geometric > floor) geometric else floor
}

/**
 * 把"一格多少世界单位"取整到 **1-2-5** 档。
 *
 * 阈值（1.5 / 3.5 / 7.5）与引擎 `Viewport.gridStep` **逐字一致** ——
 * 这是"缩放时步长跳档要自然"的关键：档位一样，用户在圆表和手机上
 * 看到的跳档节奏就一样，也不会出现"UI 算出来的格线跟引擎算的刻度对不上"。
 *
 * @param target 期望的格距（**世界单位**，已经是"像素 × unitsPerPixel"之后的值）。
 *   非有限值或非正数时原样返回（调用方自己兜底），不抛异常 ——
 *   这个函数每帧都会被绘制代码调到，不能有异常路径。
 */
fun niceStep(target: Double): Double {
    if (!target.isFinite() || target <= 0.0) return target
    val exp = floor(log10(target))
    val base = 10.0.pow(exp)
    val norm = target / base
    val mult = when {
        norm <= 1.5 -> 1.0
        norm <= 3.5 -> 2.0
        norm <= 7.5 -> 5.0
        else -> 10.0
    }
    return mult * base
}

/**
 * 这一帧的**目标格距（像素）**：`max(60px, 短边 × 60/466)`。
 *
 * 短边（[sizePx]，就是 `WatchMetrics.sizePx` = `min(可用宽, 可用高)` 的像素值）不足
 * 466px 时保持 60px（[MIN_GRID_PX]），超过之后按比例放大：
 *
 * | 短边 px | 目标格距 | 一屏多少格 |
 * |---|---|---|
 * | 400（方表） | 60px | 6.7 |
 * | 466（圆表基准） | 60px | 7.8 |
 * | 1080（手机） | 139px | 7.8 |
 *
 * 注意它**不看 density**：一块屏无论报 density 1 还是 2，这个数都一样 ——
 * 这正是"按逻辑长度决定密度"想要的效果（比例语言和 `WatchMetrics.frac()` 同源）。
 *
 * @param sizePx 屏幕短边（像素）。非有限值或非正数时退回 [MIN_GRID_PX]。
 */
fun gridTargetPxFor(sizePx: Float): Float {
    if (!sizePx.isFinite() || sizePx <= 0f) return MIN_GRID_PX
    // ≤ 基准尺寸就直接给下限，**不做乘除** —— 让"小屏与旧口径逐位相同"是构造性的，
    // 不依赖浮点乘除的舍入（万一算出 59.999999 就会掉到下一档去了）。
    if (sizePx <= WATCH_BASE_PX) return MIN_GRID_PX
    return MIN_GRID_PX * (sizePx / WATCH_BASE_PX)
}

/**
 * 按**屏幕短边**算这一帧的网格步长（世界单位）——绘制侧实际调用的就是它。
 *
 * 用法：`gridStepForScreen(viewport.unitsPerPixel, metrics.sizePx)`。
 *
 * @param unitsPerPixel 当前视口的一个像素等于多少世界单位（`Viewport.unitsPerPixel`）。
 * @param sizePx 屏幕短边（像素）。
 *
 * 返回 0.0 表示"这个视口没法用"（`unitsPerPixel` 非法），调用方应当跳过网格绘制。
 */
fun gridStepForScreen(unitsPerPixel: Double, sizePx: Float): Double {
    if (!unitsPerPixel.isFinite() || unitsPerPixel <= 0.0) return 0.0
    return niceStep(unitsPerPixel * gridTargetPxFor(sizePx))
}

/**
 * 刻度数字的**贴边**规则：把文字锚点夹进"完整可见"的范围里。
 *
 * 真因（用户报的"坐标轴上的数字移动到屏幕外之后会消失"）：
 * 绘制侧原来是"文字的包围盒完整落在安全区里才画，否则**整条不画**"，
 * 而视口边缘那一个刻度的锚点**正好落在屏幕边缘上** —— 文字的一半在屏外，
 * 于是它被整条丢掉。用户滑动屏幕时看到的就是"数字到了边上就没了"。
 *
 * 现在的语义：
 *   * 不需要挪（本来就在 [lo] + [hi] 之间放得下）→ **原样返回**，
 *     也就是"只在本来会被裁掉时才吸附"；
 *   * 需要挪、而且挪的距离不超过 [maxShift] → 返回夹过之后的锚点，
 *     数字于是**贴着屏幕边缘停住**，显示的还是当前视口下这个刻度的值
 *     （绘制侧每帧都用最新视口重算 → 滑动时它实时变化）；
 *   * 需要挪的距离超过 [maxShift]（刻度本来就在屏外很远），
 *     或者文字比 [lo]..[hi] 这一段还宽 → 返回 null，调用方**仍然不画**。
 *
 * @param anchor 期望的锚点（沿某一个轴的像素坐标）。
 * @param halfExtent 这个文字的**半宽/半高**（像素）：绘制侧用 `paint.measureText` 量。
 * @param lo 可用区间的起点（含，已经算进圆屏安全内缩）。
 * @param hi 可用区间的终点（含）。
 * @param maxShift 允许挪动的上限（像素）。**不要**再自己拍一个 dp 数传进来 ——
 *   用 [labelSnapBudgetPx] 按"安全内缩 + 两倍半宽"算，否则会重现
 *   "预算永远小于需要挪的距离、于是这条分支永远走不到"那个 bug（见 [LABEL_MIN_SNAP_DP]）。
 *
 * ## ⚠️ 绘制侧现在**不再**用这个函数（保留它是因为离线单测钉着它）
 *
 * 它的"夹"缺少亚像素富余：夹出来的锚点会在 `fitsRoundScreen` 的**严格**比较上
 * 卡 1 个 ULP（圆表 466×466 上实测差 3.1e-5px）。纵轴一格一格夹所以只丢最边上一个，
 * **x 行是整行夹一次 → 整排数字消失**，也就是用户报的"横坐标的数字直接显示不出来"。
 * 绘制侧改用 [pinLabelIntoBand] / [pinLabelNearAxis]；这个函数留作参照实现。
 */
fun snapLabelAnchor(
    anchor: Float,
    halfExtent: Float,
    lo: Float,
    hi: Float,
    maxShift: Float
): Float? {
    if (!anchor.isFinite() || !halfExtent.isFinite() || halfExtent < 0f) return null
    if (!lo.isFinite() || !hi.isFinite() || hi < lo) return null
    if (!maxShift.isFinite() || maxShift < 0f) return null
    val minAnchor = lo + halfExtent
    val maxAnchor = hi - halfExtent
    // 这一段比文字本身还窄：怎么夹都会被裁，宁可不画
    if (minAnchor > maxAnchor) return null
    val clamped = anchor.coerceIn(minAnchor, maxAnchor)
    if (abs(clamped - anchor) > maxShift) return null
    return clamped
}

// ===================================================================================
// 刻度数字的「贴边」判定（本轮新增 —— 绘制侧**唯一**入口）
//
// ## 为什么必须重写（上一版贴边"上线了但不好用"的三条真因）
//
// 上一版把"能挪多少"算成 [labelSnapBudgetPx]（`安全内缩 + 2×半宽 + 1% 短边`），
// 再用 [snapLabelAnchor] 夹一次。方向是对的，但它有三处**结构性**问题，
// 表现正是用户报的"纵坐标的数字在移动的时候会抽搐，横坐标的数字直接显示不出来了"：
//
// 1. **预算只算了"屏幕边缘"，没算"可用区比屏幕窄多少"。**
//    圆屏上一行数字的可用宽度是该高度上的**弦宽**（`bandHalfWidth`），贴底那一行
//    只剩屏宽的 ±0.35。一个刻度即使还在屏幕上、还在弦内看得见，它到可用区边界的距离
//    也**远大于** `安全内缩 + 2×半宽` —— 于是圆表上纵轴靠近上下边缘的那几个数字
//    总是被判成"挪不动"而丢掉，而且随着数字上下滚动，"丢掉哪几个"一直在变。
//    用户看到的"抽搐"主要就是它（实测：圆表上 27% 的纵轴刻度是被横向这一关丢掉的）。
//
// 2. **判定用的是连续量，没有量化（没有迟滞）。**
//    "贴边 / 不贴边"的判据是 `需要挪的距离 ≤ 预算`。两个都是视口的连续函数，
//    于是视口每动一点，判定就可能翻一次；手抖、子像素换算、`measureText` 末位抖动
//    都会让边缘上那个数字在"贴住"和"没了"之间来回翻。见 [quantizeShiftUpPx]。
//
// 3. **"夹"和"闸"是两套算式、两套零头 —— 这一条让**整排**消失。**
//    [snapLabelAnchor] 夹出来的锚点，还要在 `drawLabel → fitsRoundScreen` 里再过一道
//    "文字盒完整可见"的**严格**比较。而文字盒的下沿是 `(边界 − 半高) + 中心偏移 + 0.25 字号`
//    三次浮点加出来的，末位可能比 `边界` 大 1 个 ULP（圆表 466×466 上实测
//    `下沿 = h − 安全内缩 + 3.1e-5`）→ 那一个数字被判掉。
//    **纵轴那一列是"一个刻度夹一次"的，所以最多丢最上/最下那一个；x 轴那一行是
//    "整行一共夹一次"的（`rowCenterY`），一次误判就是整排一起消失** ——
//    而"整行夹一次"恰好只在"轴滑出屏幕（那一行被顶到下面那条安全线上）"时被用到，
//    也就是用户测贴边时的那一帧。这就是"只有横坐标不显示"的来历
//    （离线复现：把当前视口喂进这条判定链，轴滑出屏幕后 x 行画出 0 个数字、
//    纵轴仍然画出 8~9 个）。
//
// 现在这一节把判定拆成三个纯函数，绘制侧只调它们：
//
// | 函数 | 管什么 |
// |---|---|
// | [pinLabelIntoBand] | **夹**：把文字锚点夹进可用区，并**保证夹出来的位置一定过得了完整可见闸**（见 [LABEL_FIT_SLACK_PX]） |
// | [labelFollowBudgetPx] | **预算**：按几何算"这一帧最多允许挪多远"（把可用区比屏幕窄多少也算进去） |
// | [pinLabelNearAxis] | **判定**：夹 + 预算 + 量化，三者合起来回答"这个数字这一帧画在哪、还是干脆不画" |
//
// 它们全是纯算术（只 import `kotlin.math`），所以能交给 `tools\run-tool-tests.ps1`
// 那套离线单测钉住 —— 判定的三条性质（**不抖**、**夹出来的位置一定可见**、
// **x 行在任何"轴在屏内/轴出屏"情形下都至少画出一个刻度**）都在 `PlotMetricsTest` 里。
// ===================================================================================

/**
 * 贴边挪动量的**量化步长**，占屏幕短边的比例（`6 / 466` ≈ 1.29%，圆表上正好 6px）。
 *
 * 写成 `6f / WATCH_BASE_PX` 而不是 0.0129 是刻意的：**圆表（466px）上它必须正好是 6px**
 * —— 这一档的观感是用户已经接受过的基准，和小数点无关的整数让"6px 到底是多少"
 * 在任何一次讨论里都不需要再算一遍（和 [MIN_GRID_PX] / [GRID_TARGET_SIZE_FRACTION]
 * 用 60/466 同一条思路）。
 *
 * | 设备（短边 px） | 步长 | 一个数字的高度（1.1 字号 = 4.6% 短边） |
 * |---|---|---|
 * | 圆表 466 | **6.0px** | 21.5px（步长是它的 28%） |
 * | 方表 400 | 5.2px | 18.5px |
 * | 手机 1080 | 13.9px | 49.9px |
 *
 * ## 为什么要有它（用户报的"纵坐标数字在移动的时候抽搐"）
 *
 * 贴边判定原来是一个**连续**比较：`需要挪的距离 ≤ 预算`。两边都随视口连续变化，
 * 于是"贴边 / 不贴边"的判定可以在阈值上来回翻 —— 手抖、子像素换算、浮点末位、
 * 以及"这一帧到底该不该丢"的微小差别，都能让边缘上那个数字这一帧贴住、下一帧消失。
 *
 * 量化之后判据里出现的是 `向上量化后的挪动量`（见 [quantizeShiftUpPx]）：
 * 它只在跨过格线的那一瞬间变一次，**同一格内的视口差异永远不会给出两个不同的判定**
 * —— 于是"阈值到底在哪"这件事不再取决于浮点末位和亚像素抖动，
 * 而是一条**固定的格线**（圆表上每 6px 一条）。
 *
 * ⚠️ 量化**不是**迟滞：它把阈值钉在格线上，但阈值本身还是一个（跨过去仍然会翻）。
 * 真正的"两个阈值"在 [pinLabelNearAxis] 的 `wasDrawn` / `hysteresisPx` 里 ——
 * 那一对阈值（去：预算 + 一格；回：预算）之间有整整一个格线的死区，
 * 比死区更小的抖动无论怎么抖都翻不过去。两者配合才是这一轮的"不抖"。
 *
 * ## 为什么取 6/466（而不是更大/更小）
 *
 *   * **下界：必须比噪声大。** 要盖住手抖、子像素换算、浮点末位（1~3px），
 *     1% 短边（圆表 4.7px）是下限；
 *   * **上界：必须小到看不见。** 量化的代价是"贴上边之后最多再往里多停一个步长"，
 *     而迟滞的死区也是一个步长（数字离开屏幕后最多再多贴一格的行程），
 *     所以步长要和**文字本身的尺度**比：它等于字高的 28%（圆表 6px / 21.5px），
 *     而一个格距是 34~86px（步长是格距的 7%~18%）—— 一个格子里的小数点级别，
 *     任何一档缩放下都看不出来。
 *   * **为什么按"短边比例"而不是 dp**：字号本身就是"短边 × 0.042"（`WatchMetrics.fontTiny`），
 *     所以"短边的一个固定比例"= **"字高的一个固定比例"**，所有设备上手感一致；
 *     而按 dp 算会在"同一块 466px 的屏报 density 1 还是 2"的两类表上差一倍
 *     （见 [LABEL_MIN_SNAP_DP] 里那张表）。
 */
const val LABEL_SHIFT_QUANTUM_FRACTION = 6f / WATCH_BASE_PX

/**
 * 「夹出来的锚点一定过得了完整可见闸」的**亚像素**富余（像素）。
 *
 * 真因见本节开头第 3 条：夹取和"完整可见"原来是两套三次浮点加法，
 * 末位能差 1 个 ULP（实测 3.1e-5px），严格比较会把**已经夹对了**的数字判掉；
 * 纵轴一格一格夹所以只丢最边上一个，x 行整行夹一次所以**整排消失**。
 *
 * 现在两侧各留 0.5px：[pinLabelIntoBand] 往可用区**里面**多收 0.5px，
 * `fitsRoundScreen` 再放过 0.5px 的零头 —— 夹出来的位置在数学上就不可能被闸掉，
 * 也不再依赖浮点末位那点运气。
 *
 * 为什么是 0.5px：要盖住的是 1e-4 量级的舍入（0.5px 是它的 5000 倍），
 * 同时小到看不见（圆表 466px 上是 0.026 字号、屏宽的 0.1%）。
 * 不跟 density 走：舍入误差的量级由浮点决定，和设备自报的 density 无关。
 */
const val LABEL_FIT_SLACK_PX = 0.5f

/**
 * 这一帧的**量化步长**（像素）：`短边 × [LABEL_SHIFT_QUANTUM_FRACTION]`。
 *
 * 屏幕短边非法或非正时返回 0，语义是"不量化"（[quantizeShiftUpPx] 会原样返回挪动量）
 * —— 绘制侧永远不该因为一个坏尺寸就整排不画。
 */
fun labelFollowQuantumPx(sizePx: Float): Float =
    if (sizePx.isFinite() && sizePx > 0f) sizePx * LABEL_SHIFT_QUANTUM_FRACTION else 0f

/**
 * 把"需要挪动的距离"**向上**量化到一个固定步长（取绝对值，方向由调用方保留）。
 *
 * "向上"是刻意的：量化后的值 **≥** 真实需要挪的距离，于是量化**只会**让文字
 * 多往可用区里多停最多一个步长，绝不会让文字被推到可用区外（那会被完整可见闸判掉，
 * 表现就是"偶尔又没了一个数字"）。
 *
 * 非法输入：`shiftPx` 非有限 → 正无穷（等于"挪不动"，调用方会判成不画 —— 这是安全的一侧）；
 * `quantumPx` 非法或非正 → 不量化，原样返回距离（退化行为仍然可用）。
 */
fun quantizeShiftUpPx(shiftPx: Float, quantumPx: Float): Float {
    if (!shiftPx.isFinite()) return Float.POSITIVE_INFINITY
    val distance = abs(shiftPx)
    if (!quantumPx.isFinite() || quantumPx <= 0f) return distance
    return ceil(distance / quantumPx) * quantumPx
}

/**
 * 这一帧**允许挪动的最大距离**（像素）—— 由几何算，不是拍一个 dp 数。
 *
 * ```
 *   预算 = max( 22dp × density ,
 *               「刻度滑到可见区边缘那一刻所需的挪动量」 + 量化步长 )
 * ```
 *
 * 第二个式子是本轮真正修掉的那个 bug（真因第 1 条）：上一版只算了
 * `安全内缩 + 2×半宽（+1% 短边）`，也就是**假设可用区就是整块屏幕**。
 * 可圆屏的可用区是按高度收窄的（`bandHalfWidth` 给的是弦宽），
 * 贴底那一行只有屏宽的 ±0.35 —— 一个明明还在屏幕上、也还在弦内看得见的刻度，
 * 它到可用区边界的距离轻松超过旧预算，于是被判成"挪不动"而丢掉。
 *
 * 现在换一个**和可用区同源**的算法：把"刻度自己"沿这一轴挪到可见区两端
 * （`visibleLo` / `visibleHi`）时，文字锚点会落在哪、那一刻需要挪多远，两个方向取大者 ——
 * 于是"**刻度还在屏幕上**就一定挪得动（能贴边）"和"**已经出屏的刻度**不许被拖进来"
 * 这两件事同时成立，而且自动把**可用区比屏幕窄多少**算了进去。
 *
 * @param labelAnchorPx 文字**锚点（中心）**不加限制时该在的位置。
 * @param axisAnchorPx 刻度线自己的屏幕坐标（同一个轴）—— 判"还在不在屏幕上"用它。
 *   文字锚点与刻度是 1:1 平移的，所以两者之差是一个常量，本函数因此与"当前挪到哪"无关。
 * @param halfExtentPx 文字在这一轴上的半身位（横向用 `measureText/2`、纵向用 `0.55 字号`）。
 * @param loPx/hiPx 可用区间（已含安全内缩与圆屏弦宽）。
 * @param visibleLoPx/visibleHiPx 刻度自己的可见范围（一般就是画布 `0..宽` / `0..高`）。
 * @param densityScale 每 dp 多少像素，只用来算那个兜底下限。
 * @return 允许挪动的距离（像素）。输入非法时返回兜底下限或 0（等于"不许挪"，
 *   调用方仍然按"放得下就画、放不下不画"走，不会崩也不会乱画）。
 */
fun labelFollowBudgetPx(
    labelAnchorPx: Float,
    axisAnchorPx: Float,
    halfExtentPx: Float,
    loPx: Float,
    hiPx: Float,
    visibleLoPx: Float,
    visibleHiPx: Float,
    quantumPx: Float,
    densityScale: Float
): Float {
    val floor = if (densityScale.isFinite() && densityScale > 0f) {
        LABEL_MIN_SNAP_DP * densityScale
    } else {
        0f
    }
    if (!labelAnchorPx.isFinite() || !axisAnchorPx.isFinite() || !halfExtentPx.isFinite()) return floor
    if (!loPx.isFinite() || !hiPx.isFinite()) return floor
    if (!visibleLoPx.isFinite() || !visibleHiPx.isFinite()) return floor
    val room = if (quantumPx.isFinite() && quantumPx > 0f) quantumPx else 0f
    val minCentre = loPx + halfExtentPx + LABEL_FIT_SLACK_PX
    val maxCentre = hiPx - halfExtentPx - LABEL_FIT_SLACK_PX
    // 可用区连一个文字都放不下：一格都不许挪（调用方也随之不画）
    if (minCentre > maxCentre) return 0f
    // 刻度滑到可见区两端时，文字锚点分别会在哪 → 那一刻需要挪多远
    val labelAtVisibleLo = labelAnchorPx + (visibleLoPx - axisAnchorPx)
    val labelAtVisibleHi = labelAnchorPx + (visibleHiPx - axisAnchorPx)
    val geometric = maxOf(
        minCentre - labelAtVisibleLo,
        labelAtVisibleHi - maxCentre,
        0f
    ) + room
    return if (geometric > floor) geometric else floor
}

/**
 * **夹**：把文字锚点夹进"完整可见"的可用区 `[loPx, hiPx]`，放得下就原样返回。
 *
 * 这是 [snapLabelAnchor] 的**保闸版**，两者语义只差一件事：
 * 这里在可用区两端各留了 [LABEL_FIT_SLACK_PX] 的余量，于是**夹出来的锚点一定
 * 过得了 `fitsRoundScreen` 那道严格比较**（真因第 3 条：圆表上两者差 1 个 ULP，
 * 而 x 行是整行夹一次 → 整排数字消失）。
 *
 * 其余语义与 [snapLabelAnchor] 一致：
 *   * 放得下的**原样返回**（位置严格跟手，一个像素都不动）；
 *   * 放不下的夹到最近的可用位置；
 *   * 可用区连一个文字都放不下（`hi − lo < 2×半身位`）→ null，调用方不画。
 *
 * `coerceIn` 只在越界时生效，所以"本来放得下"的那些位置与改动前**逐位相同**
 * （圆表上数字的位置因此一个像素都没变）。
 */
fun pinLabelIntoBand(
    labelAnchorPx: Float,
    halfExtentPx: Float,
    loPx: Float,
    hiPx: Float,
    slackPx: Float = LABEL_FIT_SLACK_PX
): Float? {
    if (!labelAnchorPx.isFinite() || !halfExtentPx.isFinite() || halfExtentPx < 0f) return null
    if (!loPx.isFinite() || !hiPx.isFinite() || hiPx < loPx) return null
    val slack = if (slackPx.isFinite() && slackPx > 0f) slackPx else 0f
    val minCentre = loPx + halfExtentPx + slack
    val maxCentre = hiPx - halfExtentPx - slack
    if (minCentre > maxCentre) return null
    return labelAnchorPx.coerceIn(minCentre, maxCentre)
}

/**
 * **贴边判定**（绘制侧真正调用的那一个）：夹 + 预算 + 量化 + **迟滞**。
 *
 * 语义（四条）：
 *   1. 放得下的**原样不动** —— 只差一点点才吸附，数字永远跟手；
 *   2. 需要挪、而且**刻度自己还在可见范围里**（[labelFollowBudgetPx] 的几何预算）
 *      → 返回夹过的锚点：数字贴着**可用区**的边缘停住，锚点由当前视口现算，
 *      所以滑动时它显示的就是此刻屏幕边缘处的坐标值（实时变化）；
 *   3. 刻度已经出屏（或可用区连一个文字都放不下）→ null，调用方不画；
 *   4. **迟滞**：这一帧的"丢不丢"取决于 [wasDrawn]（上一次这个刻度画过没有，
 *      由调用方逐刻度记着，见 `GraphCanvas` 的 `LabelSticky`）：
 *      ```
 *      画过  → 允许挪动的上限 = 预算 + hysteresisPx   （宽松阈值：不轻易消失）
 *      没画过 → 允许挪动的上限 = 预算                  （严格阈值：要回来得走近一点）
 *      ```
 *      两个阈值之间差了整整 [hysteresisPx]（调用方传一个量化步长），
 *      也就是一段**死区**：比死区更小的抖动怎么抖都跨不过去（"抽搐"没了），
 *      而数字离开屏幕之后最多再多贴一格的行程才消失（"贴边不消失"仍在）。
 *
 * 与上一版 [snapLabelAnchor] 的三处区别，也就是这一轮修的两条 bug：
 *   * 预算按**可用区几何**算，不按"屏幕边缘"算（圆屏上纵轴靠边的数字不再被丢）；
 *   * 判定用**量化后**的挪动量 + 上面那对**双阈值**（阈值落在固定格线上，噪声跨不过）；
 *   * 夹取自带**亚像素富余**，夹出来的一定过得了完整可见闸（x 行不再整排消失）。
 *
 * ## 为什么迟滞要由调用方传 `wasDrawn` 进来（这个函数本身仍然是纯函数）
 *
 * 绘制层每一帧都是"当前视口 → 像素"的纯计算，没有跨帧状态；而**两个阈值**必须知道
 * "上一帧这个刻度画了没有"。做法是把那一件事（而且只有那一件事）交给调用方：
 * 调用方逐刻度记一个布尔（`GraphCanvas` 里就是一个 `HashSet<Double>`，尺寸被可见刻度数
 * 钉死在几十个），其余全是纯算术 —— 于是这个函数仍然可以离线扫 100 个采样点、
 * 一帧一帧喂进去，把"不出现反复跳变"这条性质钉在单测里（见 `PlotMetricsTest`）。
 *
 * @param quantumPx 量化步长（[labelFollowQuantumPx]）。非正/非法 = 不量化（退化但仍可用）。
 * @param wasDrawn 上一个**绘制帧**里，这个刻度值画过没有（迟滞用）。
 * @param hysteresisPx 迟滞宽度（像素）：画过的那个多留一段行程。非正 = 不做迟滞。
 * @param slackPx 夹取时往可用区里多收的亚像素余量，默认 [LABEL_FIT_SLACK_PX]。
 */
fun pinLabelNearAxis(
    labelAnchorPx: Float,
    axisAnchorPx: Float,
    halfExtentPx: Float,
    loPx: Float,
    hiPx: Float,
    visibleLoPx: Float,
    visibleHiPx: Float,
    quantumPx: Float,
    densityScale: Float,
    wasDrawn: Boolean = false,
    hysteresisPx: Float = 0f,
    slackPx: Float = LABEL_FIT_SLACK_PX
): Float? {
    val pinned = pinLabelIntoBand(labelAnchorPx, halfExtentPx, loPx, hiPx, slackPx) ?: return null
    val need = pinned - labelAnchorPx
    // 本来放得下：原样返回（不量化位置 —— 量化位置会让数字在平移时一跳一跳）
    if (need == 0f) return pinned
    val budget = labelFollowBudgetPx(
        labelAnchorPx = labelAnchorPx,
        axisAnchorPx = axisAnchorPx,
        halfExtentPx = halfExtentPx,
        loPx = loPx,
        hiPx = hiPx,
        visibleLoPx = visibleLoPx,
        visibleHiPx = visibleHiPx,
        quantumPx = quantumPx,
        densityScale = densityScale
    )
    if (!budget.isFinite()) return null
    // 迟滞：画过的那个用宽松阈值（预算 + 一格），没画过的用严格阈值（预算）。
    // 两个阈值之间那段死区就是"不抽"的来源。
    val extra = if (wasDrawn && hysteresisPx.isFinite() && hysteresisPx > 0f) hysteresisPx else 0f
    if (quantizeShiftUpPx(need, quantumPx) > budget + extra) return null
    return pinned
}

// ===================================================================================
// 刻度数字的**显示范围**：两条轴共用的「角锚点」
//
// ## 用户报的现象（"纵坐标显示多，横坐标显示少"）与真因
//
// 上一轮之前，x 行与 y 列各用**自己那条**可用区间，两条区间的**起点不是同一个点**：
//
// | 轴 | 可用区间（屏幕像素） | 起点 | 长度（圆表 466 / 默认视野实测） |
// |---|---|---|---|
// | x 行 | `[max(安全内缩, w/2 − 弦宽), min(w − 安全内缩, w/2 + 弦宽)]` | 取决于**行高**（弦宽随高度变） | 默认 ~407px；行贴底时只剩 **164px** |
// | y 列 | `[安全内缩 + 半高, h − 安全内缩 − 半高]` | 恒为屏幕下缘 | 恒为 **~410px** |
//
// 两条区间的**左/下端**一个在弦宽边界（可能离左缘几十像素）、一个在屏幕下缘 →
// 起点不同、长度也不同：用户看到的就是"纵坐标能显示到屏幕最下面，横坐标却只到中间"。
// 而且 y 列的区间**完全不看弦宽**（纵向上没有弦宽约束），所以它在圆屏上永远拿到满高度。
//
// ## 现在的规则：两条范围**从同一个角锚点出发、量同一段长度**
//
// 1. **角锚点** [labelCornerAnchorPx] = 屏幕**左下角**；圆屏上左下角在可视区之外，
//    于是取"**屏幕中心 → 左下角**这条连线（也就是 45° 左下方向）与**可用圆**边界的交点"。
// 2. **臂长** [labelRangeSpanPx] = `短边 − 2 × 安全内缩` —— 两条范围共用的"口径"。
// 3. x 行 = `[角锚点.x, 角锚点.x + 臂长]`、y 列 = `[角锚点.y − 臂长, 角锚点.y]`，
//    各自再与**这条轴自己的可见边界**取交（`[xRowRangeFromCorner]` /
//    `[yColumnRangeFromCorner]`）—— 取交那一步保证范围里每一个锚点都过得了
//    `fitsRoundScreen` 那道完整可见闸（所以不会出现"范围放宽了、数字却被闸判掉"）。
//
// 两条范围于是**只有一个公共点：角锚点**（这就是用户说的"汇交点"），
// 而且在方屏/圆屏上**长度相等**（圆屏上两边会被圆对称地各截掉一点，仍然相等）：
//
// | 屏 | 角锚点（px） | 角锚点（dp） | 臂长（px / dp） | x 范围 | y 范围 |
// |---|---|---|---|---|---|
// | 圆表 466×466（density 2） | (88.01, 377.99) | (44.0, 189.0) | 410.08 / 205.0 | [88.01, 436.40]（348.4px） | [27.96, 377.99]（350.0px） |
// | 方表 400×400（density 2） | (24.00, 376.00) | (12.0, 188.0) | 352.00 / 176.0 | [24.00, 376.00]（352px） | [24.00, 376.00]（352px） |
// | 手机 1080×2400（density 3） | (64.80, 2335.20) | (21.6, 778.4) | 950.40 / 316.8 | [64.80, 1015.20]（950.4px） | [1384.80, 2335.20]（950.4px） |
//
// 圆表上两条长度差 1.6px（348.4 对 350.0）：x 行那一端被**该行高度上的弦宽**截掉一点点
// —— 这是圆屏的几何事实，不是口径不一致（两条都是"从角锚点量 410.08px 再按自己那条
// 可见边界截断"，规则完全相同）。
//
// ## 与 `safeInset` / `bandHalfWidth` / 弦宽的关系（有一处要不要改的说明）
//
//   * **`safeInset`**：圆屏的角锚点是"**沿半径**内缩 safeInset"后的交点（半径 233 → 205.04），
//     方屏/手机则直接把角锚点放在四边留白的角上 —— 两种屏都保证"锚点自己在可见区里"。
//   * **弦宽 `bandHalfWidth`**：它是"某一高度上横向能放多宽"，是**逐行/逐格**的可见性边界。
//     范围（本节）与它取交，而不是取代它 —— 顺序是"先定范围、再受可见性截断"。
//   * **x 行的左端与 y 列的下端确实都改成了"以角锚点为基准"**（这是本轮唯一的改动点）：
//     x 行的左端从"弦宽边界 / 安全内缩"改成 `max(弦宽边界, 角锚点.x)`，
//     y 列的下端从 `h − 安全内缩` 改成 `角锚点.y`。两条的**另一端**（右端 / 上端）
//     仍然各自是"安全内缩 / 弦宽"，因为屏幕是长方形时两条臂的物理上限本来就不同
//     （手机上屏高是屏宽的 2.2 倍）—— 口径一致靠的是**同一起点 + 同一臂长**，
//     不是"把长的那条硬切到和短的一样"。
//
// ## ⚠️ 为什么"范围"要同时当成"刻度数字的可见范围"
//
// 范围是"数字的**定义域**"：范围内的刻度一律有数字（夹取保证完整可见），范围外的刻度
// **不画**（哪怕它的网格线还在屏幕上）。这样才不会出现"数字被拉进范围、却离自己那条线
// 一格多远"（那会被读成邻居的值）。副作用是圆屏上贴着画布上下缘、但在角锚点之外的那
// 一两个刻度不再有数字 —— 这正是"显示范围到角锚点为止"的意思。
// ===================================================================================

/** 一个**角锚点**（屏幕像素）：两条刻度范围共用的"出发点"，圆屏上是 45° 左下方向的可用圆交点。 */
data class LabelCornerAnchor(
    val xPx: Float,
    val yPx: Float
)

/** 一段刻度数字的**可用区间**（屏幕像素，`loPx ≤ hiPx`）。 */
data class LabelRange(
    val loPx: Float,
    val hiPx: Float
) {
    /** 区间长度（像素）——"两条范围口径一致"就是比它。 */
    val spanPx: Float get() = hiPx - loPx

    /** 区间是否可用（有限且非空）。 */
    fun isUsable(): Boolean = loPx.isFinite() && hiPx.isFinite() && hiPx > loPx
}

/**
 * 两条刻度范围共用的**角锚点**：屏幕左下角；圆屏取"中心 → 左下角连线与可用圆的交点"。
 *
 * 推导（屏幕坐标：x 向右、y 向下）：
 * ```
 *   可用圆：圆心 = (w/2, h/2)、半径 r = min(w, h)/2（圆表上屏幕就是外接正方形）
 *   可用半径 r_u = r − safeInset            // 沿**半径**内缩，和"数字不许被表壳切掉"同一条
 *   左下方向单位向量 u = (−1/√2, +1/√2)      // 圆心 → 物理左下角 (0, h)
 *   角锚点 C = 圆心 + r_u · u
 *            = (w/2 − r_u/√2,  h/2 + r_u/√2)
 * ```
 * 三条数字（圆表 466、safeInset = 0.06×466 = 27.96、r = 233、r_u = 205.04、r_u/√2 = 144.99）：
 * `C = (233 − 144.99, 233 + 144.99) = (88.01, 377.99)` px，即圆上的 225° 方向那一点。
 *
 * 方屏/手机没有表壳，左下角**就是**可用矩形的角：`C = (safeInset, h − safeInset)`。
 *
 * @param wPx/hPx 画布尺寸（像素）。
 * @param safeInsetPx 安全内缩（像素）：圆屏上是沿半径的余量，方屏上是四边留白。
 * @param isRound 这块屏是不是圆的（只有圆屏才需要"45° 求交点"这一步）。
 * @return 角锚点；输入非法时退回可用矩形的左下角（不抛异常 —— 每帧都会被调到）。
 */
fun labelCornerAnchorPx(
    wPx: Float,
    hPx: Float,
    safeInsetPx: Float,
    isRound: Boolean
): LabelCornerAnchor {
    val inset = if (safeInsetPx.isFinite() && safeInsetPx > 0f) safeInsetPx else 0f
    if (!wPx.isFinite() || !hPx.isFinite() || wPx <= 0f || hPx <= 0f) {
        return LabelCornerAnchor(inset, inset)
    }
    if (!isRound) return LabelCornerAnchor(inset, hPx - inset)
    // 可用圆：半径 = 短边的一半（圆表上宽高相等，取 min 对非正方的圆屏也安全）
    val radius = minOf(wPx, hPx) / 2f
    val usable = radius - inset
    // 可用圆退化（安全内缩比半径还大）→ 退回圆心，至少不会算出屏外的怪值
    if (!usable.isFinite() || usable <= 0f) return LabelCornerAnchor(wPx / 2f, hPx / 2f)
    val diagonal = usable / SQRT2
    return LabelCornerAnchor(wPx / 2f - diagonal, hPx / 2f + diagonal)
}

/**
 * 两条范围共用的**臂长**（像素）：`短边 − 2 × 安全内缩`。
 *
 * 为什么用短边：正方形/圆屏上它正好等于"可用区的一边"，于是从角锚点量出臂长之后，
 * 两条范围**长度相等**；长条屏（手机）上短边是屏宽，于是"两条范围盖住的坐标跨度一样"
 * —— 这正是用户要的"口径一致、不会一边多一边少"（手机上原来是 y 列 20 个数字、
 * x 行 9 个，现在是各 9 个）。
 *
 * 非法输入退回 0（调用方随之得到空区间而不画 —— 绝不会因此画出屏外的数字）。
 */
fun labelRangeSpanPx(wPx: Float, hPx: Float, safeInsetPx: Float): Float {
    if (!wPx.isFinite() || !hPx.isFinite() || wPx <= 0f || hPx <= 0f) return 0f
    val inset = if (safeInsetPx.isFinite() && safeInsetPx > 0f) safeInsetPx else 0f
    val span = minOf(wPx, hPx) - 2f * inset
    return if (span.isFinite() && span > 0f) span else 0f
}

/**
 * **x 行**的可用区间：从角锚点**向右**量 [spanPx]，再与这一行自己的可见区间取交。
 *
 * 取交是硬要求：范围内每一个锚点都必须过得了 `fitsRoundScreen`（圆屏的弦宽是逐行变的），
 * 所以"范围"永远不可能比可见区更宽 —— 这也顺手保证了"不会把数字放到表壳外"。
 *
 * @return 区间；退化（空区间 / 非法输入）时返回 null，调用方这一行整排不画。
 */
fun xRowRangeFromCorner(
    loNaturalPx: Float,
    hiNaturalPx: Float,
    cornerXPx: Float,
    spanPx: Float
): LabelRange? {
    if (!loNaturalPx.isFinite() || !hiNaturalPx.isFinite()) return null
    val corner = if (cornerXPx.isFinite()) cornerXPx else loNaturalPx
    val span = if (spanPx.isFinite() && spanPx > 0f) spanPx else 0f
    val lo = maxOf(loNaturalPx, corner)
    val hi = minOf(hiNaturalPx, corner + span)
    if (!lo.isFinite() || !hi.isFinite() || hi <= lo) return null
    return LabelRange(lo, hi)
}

/**
 * **y 列**的可用区间：从角锚点**向上**量 [spanPx]（屏幕 y 减小），
 * 再与这一列自己的可见区间 `[loNaturalPx, hiNaturalPx]` 取交。
 *
 * 注意参数语义和 x 行一致：`loPx` 是屏幕上边（小 y）、`hiPx` 是屏幕下边（大 y），
 * 而角锚点在**下端** —— 所以臂是 `[corner − span, corner]`。
 *
 * @return 区间；退化时返回 null（调用方整列不画）。
 */
fun yColumnRangeFromCorner(
    loNaturalPx: Float,
    hiNaturalPx: Float,
    cornerYPx: Float,
    spanPx: Float
): LabelRange? {
    if (!loNaturalPx.isFinite() || !hiNaturalPx.isFinite()) return null
    val corner = if (cornerYPx.isFinite()) cornerYPx else hiNaturalPx
    val span = if (spanPx.isFinite() && spanPx > 0f) spanPx else 0f
    val lo = maxOf(loNaturalPx, corner - span)
    val hi = minOf(hiNaturalPx, corner)
    if (!lo.isFinite() || !hi.isFinite() || hi <= lo) return null
    return LabelRange(lo, hi)
}

/** `1/√2`：45° 左下方向的分量（角锚点推导里那一步）。 */
private const val SQRT2 = 1.4142135f

// ===================================================================================
// 刻度数字的「行级兜底」：什么时候才允许**整排/整列**一个数字都不标
//
// ## 这一条到底在防什么（先说清它原来防的是不是真问题）
//
// 它原来是一个 dp 常数：`20dp × density`（圆表 density 2 上正好 40px）——
// 相邻刻度的屏幕间距小于它，就**整排（整列）不标**。听上去像"防数字挤成一团"，
// 但那个问题**已经由逐格判定解决了**：`drawCartesianLabels` 里每个数字都拿
// `paint.measureText` 量出真实字宽，与上一个**已经画出去**的数字比 `LABEL_MIN_GAP_DP`
// （5dp 的缝），挤不下就跳过这一个（`5` 很窄、`-1000` 很宽都能正确对待）。
// 也就是说：**"会不会挤"是逐格的几何问题，逐格判定管得住；行级一条粗线管不住**
// —— 它只会把一个"其实还能隔一个画一个"的档位变成**一个都不画**。
//
// 而它杀伤的面积并不小：格距是 `niceStep(upp × max(60px, 短边×60/466))`，1-2-5 取整的
// 固有偏差让**屏幕间距最小能到目标格距的 0.571 倍**（圆表 34.3px），而 `20dp × density`
// 在圆表（density 2）上是 40px —— 于是**屏幕间距落在 [34.3, 40) 的那几档缩放会把
// 横竖两排数字一起清空**（离线扫 61 帧缩放路径实测 5 帧；手机上因为阈值是 60px 而
// 最小间距是 79px，从来不触发）。用户看到的就是"某些缩放档数字全没了"。
//
// 顺带排除另一种可能：**它不是性能兜底**。数字个数本来就由 [MAX_LABEL_TICKS] 之类的
// 循环上界兜着，而且"不标"和"标"的循环次数**完全一样**（循环体里少画一次字而已）——
// 清空整排省不下任何东西。
//
// ## 现在的阈值：一个字高（`0.0462 × 短边`）—— 1-2-5 永远够不到
//
// `spacing ≥ 目标格距 × 0.5714 ≥ 60px × 0.5714 = 34.3px`，而"一个字高" =
// 1.1 字号 = `1.1 × 0.042 × 短边 = 0.0462 × 短边`：圆表 21.5px、方表 18.5px、
// 手机（短边 1080）49.9px —— 三块屏上都**远小于**该屏能达到的最小间距
// （圆表 34.3 > 21.5、手机 79 > 49.9），所以它在正常参数下**不可能触发**，
// 只在"step/upp 算出荒唐值"（坏视口、坏尺寸）时兜住，避免画出读不出来的东西。
//
// 阈值口径也从 dp 换成"短边比例"：字号本身就是短边的 4.2%，于是"短边的一个固定比例"
// = "字高的一个固定比例"，和 [LABEL_SHIFT_QUANTUM_FRACTION]、[MIN_GRID_PX] 同一套语言
// （也顺手修掉"同一块 466px 的屏报 density 1 还是 2 行为差一倍"那个老问题）。
// ===================================================================================

/**
 * 「一个字高」占屏幕短边的比例 = 1.1 字号 = `1.1 × 0.042`（字号 = 短边的 4.2%）。
 *
 * 它是行级兜底的阈值：相邻刻度的屏幕间距比它还小，才允许整排/整列不标。
 */
const val MIN_LABEL_SPACING_FRACTION = 1.1f * 0.042f

/**
 * 行级兜底判据：这一帧的刻度间距是不是密到**根本画不出数字**（真要整排/整列不标）。
 *
 * 用法：`if (labelsTooDenseForRow(spacingPx, metrics.sizePx)) return`（`drawLabels` 里）。
 *
 * 正常参数下它**永远返回 false**：间距的下界是 `目标格距 × 0.5714 ≥ 34.3px`，
 * 而阈值是 `短边 × 0.0462`（圆表 21.5px、手机 49.9px），够不到（见本节开头那张推理）。
 * 它只为"坏值"存在：`spacingPx` 非有限/非正，或者画布尺寸荒唐时。
 *
 * ⚠️ 不要把它当成"防数字挤在一起"的主判据 —— 那一件事由**逐格字宽判定**
 * （`LABEL_MIN_GAP_DP` 那道缝）负责；这里放宽之后，"密"只表现为"隔一个画一个"，
 * 而不是"整排清空"。
 *
 * @param spacingPx 相邻两个刻度在屏幕上的像素间距（`step / unitsPerPixel`）。
 * @param sizePx 屏幕短边（像素）—— 阈值按它缩放，不看 density。
 * @return true 才整排不标。间距非法 → true（宁可不画）；尺寸非法 → false
 *   （把判断让给逐格判定，绝不因为一个坏尺寸把整排清空）。
 */
fun labelsTooDenseForRow(spacingPx: Float, sizePx: Float): Boolean {
    if (!spacingPx.isFinite() || spacingPx <= 0f) return true
    if (!sizePx.isFinite() || sizePx <= 0f) return false
    return spacingPx < sizePx * MIN_LABEL_SPACING_FRACTION
}

// ===================================================================================
// 列表行「左滑删除」的尺度 + 「单向拖动」认不认领这一串手势的判据
//
// ## 为什么这一节也在本文件里（而不是新开一个文件）
//
// 和上面两节**同一条理由**：这几条是纯算术、零 Android/Compose 依赖，却决定用户一眼
// 能看到的行为 —— 滑多少才删、跟不跟手、渐染到什么程度、松手是不是"马上就没"。
// 它们不该只靠"装到设备上滑一下"来验证，所以和网格/刻度一样住在这里、交给
// `tools\run-tool-tests.ps1` 那套离线单测钉住（断言在 `PlotMetricsTest` 末尾那一组）。
// 而"能不能被那个脚本编进去"是这个包里**文件位置**的硬约束：脚本只编固定的七个 .kt
// （它自己是带 BOM 的 .ps1，不在本轮可改范围内），所以纯算术只能住在它们里面 ——
// 而这几条既不属于引擎、也不属于几何模型/文件格式/工具状态机。
//
// ## 为什么必须是**唯一**一份（用户报的那个 bug 的一半真因）
//
// 用户报："除了函数之外的对象在列表都不能滑动删除，应该所有对象都能滑动删除的。"
// 对着代码数一遍：**函数行**（`FunctionList.FunctionRow`）和**对象行**
// （`FunctionsScreen.GraphObjectRow`）当时各写了一套删除手势 ——
// 两边的阈值、时长、甩出距离、缩小比例各有一份常量
// （`SWIPE_DELETE_TRAVEL` 对 `0.3333f` 字面量、`DELETE_OUT_*` 对 `DELETE_OBJECT_*`），
// 手势块是两段互相抄出来的代码，而且**对象行那一段比函数行多了一条起手位置限制**
// （行右侧那条带被让给了"右边缘回列表"）。于是"函数能删、别的都不能删" ——
// 差一处，用户看到的就是完全不同的行为。
//
// 现在：阈值只在这里定义一次，手势与动画只在
// `DragGestures.rememberRowSwipeDelete` 定义一次，两行**调的是同一份**；
// 这一节留下的是"可离线测的那一半"。
// ===================================================================================

/**
 * 左滑删除的**位移阈值**（占行宽）：滑够行宽的 1/3，松手才真的删。
 *
 * 为什么卡住而不是"滑一下就删"：这个手势没有二次确认（手表上弹确认框比误删还烦），
 * 只有 2 秒的撤销后路兜底；1/3 是"确实想扔掉它"和"随手蹭一下"的分界。
 *
 * ⚠️ 它是**一个比例**、不是一个 px 数：函数行与对象行的行宽口径不同
 * （圆屏按弧宽、非圆屏铺满可用宽度，见 `ScreenGeometry.rowWidth`），但阈值永远问
 * 同一个比例 —— 两边都由 [swipeDeleteTravelPx] 现算，谁也不许再写第二个数。
 */
const val SWIPE_DELETE_TRAVEL = 1f / 3f

/**
 * 滑动时整行朝砖红渐染的**最大比例**。
 *
 * 0.85 = 滑够时是 85% 的砖红（`KeyPalette.danger`），不是纯红：整行纯红会把行里的
 * 文字（白字压在满红上）糊掉，而"差一点点"反而更容易读出"松手就要删了"。
 */
const val SWIPE_DELETE_TINT = 0.85f

/**
 * 「被扔掉」那一下的时长（ms）。
 *
 * 为什么要多这一步：`onDelete()` 一调，状态层立刻把这一条摘掉，用户看到的就是
 * "行凭空没了"——没有"我把它扔掉了"的手感。180ms 内走完，不会让人觉得删得慢。
 */
const val DELETE_OUT_MS = 180

/** 「被扔掉」时继续往左甩出的距离（占行宽）：滑够 1/3 再往外推一点就到屏幕边上了。 */
const val DELETE_OUT_SHIFT = 0.28f

/** 「被扔掉」时同时缩小多少（0.12 → 缩到 88%）：配合淡出，看起来是"飞走"而不是"平移"。 */
const val DELETE_OUT_SHRINK = 0.12f

/**
 * 这一行的左滑删除阈值（px）。
 *
 * 行宽非法（0 / NaN —— 行还没被测到、或者约束算出了怪值）时返回 0，
 * 语义是"怎么滑都不算够"：宁可这一次不删，也不能让一行在宽度未知时被判成删掉。
 */
fun swipeDeleteTravelPx(rowWidthPx: Float): Float =
    if (rowWidthPx.isFinite() && rowWidthPx > 0f) rowWidthPx * SWIPE_DELETE_TRAVEL else 0f

/**
 * 跟手位移：只跟**往左**那一截。
 *
 * 往右的部分被夹成 0 —— 行的位移因此永远不会是正的（行不会被拽出屏幕右边），
 * 而"往左滑过头再回搓"时行会跟着回来（用户看到的是它真的跟手）。
 */
fun swipeDeleteFollow(totalPx: Float): Float =
    if (totalPx.isFinite()) minOf(totalPx, 0f) else 0f

/** 渐染 / 触发进度 0..1：0 = 没在滑，1 = 已经滑够（松手就删）。 */
fun swipeDeleteProgress(dragXPx: Float, rowWidthPx: Float): Float {
    val travel = swipeDeleteTravelPx(rowWidthPx)
    if (travel <= 0f || !dragXPx.isFinite()) return 0f
    return (-dragXPx / travel).coerceIn(0f, 1f)
}

/**
 * 抬手时的判定：这一次左滑**够不够删**。
 *
 * 判据只有两条 —— 方向得是往左（[totalPx] 为负）、位移得够 [SWIPE_DELETE_TRAVEL]。
 * 起手在行里的**哪个位置完全不参与**判定：这正是对象行上一版划不动的原因
 * （上一版多了一条"行右侧那条带不算"的起手限制，见
 * `DragGestures.rememberRowSwipeDelete` 上面那段）。
 */
fun swipeDeleteReached(totalPx: Float, rowWidthPx: Float): Boolean {
    val travel = swipeDeleteTravelPx(rowWidthPx)
    return travel > 0f && totalPx.isFinite() && totalPx <= -travel
}

/** 「被扔掉」那一下的横向位移（px，往左为负）= `-行宽 × 0.28 × 进度`。 */
fun swipeDeleteSweepShiftPx(rowWidthPx: Float, sweep: Float): Float {
    if (!rowWidthPx.isFinite() || !sweep.isFinite()) return 0f
    return -rowWidthPx * DELETE_OUT_SHIFT * sweep.coerceIn(0f, 1f)
}

/** 「被扔掉」那一下的缩放系数（1 → 0.88）。 */
fun swipeDeleteSweepScale(sweep: Float): Float =
    if (sweep.isFinite()) 1f - DELETE_OUT_SHRINK * sweep.coerceIn(0f, 1f) else 1f

/** 「被扔掉」那一下的不透明度（1 → 0）。 */
fun swipeDeleteSweepAlpha(sweep: Float): Float =
    if (sweep.isFinite()) 1f - sweep.coerceIn(0f, 1f) else 1f

/**
 * 一行**当前**该画在哪：跟手位移 **加上** 「被扔掉」那一下继续往左甩出的位移（px）。
 *
 * ## 为什么要单独有这一个函数（这里修的是用户报的"删除动画鬼畜"）
 *
 * 用户原话："现在删除动画有点鬼畜了，函数条会从左边移到右边去，正常逻辑应该是
 * 函数条直接从左边移开消失的。"
 *
 * 真因是**符号与基准**，而且两半都错在同一个地方：
 *
 * | 量 | 谁给的 | 符号 |
 * |---|---|---|
 * | 跟手位移 [RowSwipeDelete.dragX] | `swipeDeleteFollow(total)` | **负数**（往左） |
 * | 甩出位移 [swipeDeleteSweepShiftPx] | `-行宽 × 0.28 × 进度` | **负数**（往左） |
 *
 * 两者**都是往左的负数**，所以合成位移必须是 `dragX + 甩出`（两个负数相加 = 更往左）。
 * 而三个调用点上一版写的都是 `dragX - 甩出` —— 减掉一个负数等于**加上一个正数**：
 * 行从"已经滑到左边的 -1/3 行宽"被一路推回 `-1/3 + 0.28 = -0.05 行宽`，
 * 也就是**一边缩小淡出、一边往右回弹**，看起来就是"函数条从左边移到右边去"。
 *
 * 为什么不是别的原因（逐条排除，免得后人再猜一遍）：
 *   * **不是**"动画结束把 `translationX` 归零"：`dragX` 在删除那条路上**故意不清零**
 *     （见 `DragGestures.RowSwipeDelete.gesture` 的 `onEnd`），行就停在滑到的位置上；
 *   * **不是**"行被删掉后 `LazyColumn` 让下一行从右边滑进来"：第一屏那个 `LazyColumn`
 *     的项上**根本没有** `animateItem` / `animateItemPlacement`（全仓库 grep 过，
 *     只有「函数文件」那一屏用了 `animateItem`），所以邻居是**瞬移合拢**、没有位移动画；
 *   * **不是**时长或缓动：另外两个派生值（缩小、淡出）方向都对，用户看到的就是
 *     "缩着往右跑"，那只能是横向位移的符号。
 *
 * 所以这条算式抽成一个函数，三个调用点（函数行 / 几何对象行 / 滑动条行）都调它 ——
 * 上一版是三个 `graphicsLayer` 里各写一遍同样的减法，改一处漏两处是迟早的事。
 * 断言在 `PlotMetricsTest`（"甩出方向"那一组）：合成位移必须**单调往左**、
 * 终点必须比 `dragX` 更左。
 *
 * @param dragXPx 跟手位移（往左为负；往右的部分已经被 [swipeDeleteFollow] 夹成 0）。
 * @param rowWidthPx 这一行当前的宽度（px）—— 甩出距离按它的比例算。
 * @param sweep 「被扔掉」的进度 0..1（`Animatable` 的当前值）。
 * @return 该写进 `graphicsLayer.translationX` 的值（px，往左为负）。
 *   非法输入时退回"只用跟手位移"（绝不把 NaN 塞进图层 —— 那会让整行消失）。
 */
fun swipeDeleteTranslationPx(dragXPx: Float, rowWidthPx: Float, sweep: Float): Float {
    val follow = swipeDeleteFollow(dragXPx)
    val shift = swipeDeleteSweepShiftPx(rowWidthPx, sweep)
    if (!follow.isFinite() || !shift.isFinite()) return 0f
    return follow + shift
}

/**
 * 一行「左滑删除」的**跟手位移状态机**（纯 Kotlin）—— 真机上那一份是
 * `DragGestures.RowSwipeDelete`，它把这个类包起来，界面只读它同步出来的镜像。
 *
 * ## 为什么要有这个类（用户报的第二个动画 bug 的真因就在"什么时候把位移归零"上）
 *
 * 用户原话："函数列表删除的动画有点抽搐，把一个对象从右往左滑删除之后还会从中间
 * 再往左滑动，实际应该是从松手的位置就继续滑动，而不是从中间再滑动。"
 *
 * 上一轮修的是**方向**（合成位移必须是 `跟手 + 甩出`，两个负数相加），这一轮的问题是
 * **起点**：松手那一刻行已经在"跟手位置"上了，可是甩出动画却从**另一个基准**开始 ——
 * 于是用户先看到行弹回它自己的位置（屏幕上就是"中间"），再从那往左滑出去。
 *
 * 那个"另一个基准"就是被清零的跟手位移本身，清零的元凶在 `awaitEachGesture` 的循环形状里：
 *
 * ```
 * awaitEachGesture {          // 循环体 = 一串手势
 *     dragX = 0f              // ← 就是这一句（它写在循环体的第一行）
 *     awaitOneWayDrag(...)    //    里面等 down、跟手、抬手时回调 onEnd
 * }
 * ```
 *
 * 反编译 1.7.0 的 `ForEachGestureKt` 可以看到它的循环体是
 * `while (isActive) { block(); awaitAllPointersUp() }` —— 而 `awaitAllPointersUp()`
 * 在"最后一个事件已经是抬手"时**立即返回**（第一句就是判 `currentEvent`）。
 * 所以顺序是：
 *
 * 1. 抬手那帧：`onEnd` 里 `scope.launch { sweep 动画 }`（只是**排队**，下一帧才跑）
 *    → 然后函数返回，`dragX` 此刻还是**松手位置**；
 * 2. 同一个调度里：`block()` 立刻被**再调一次** → 第一句 `dragX = 0f` 执行；
 * 3. 下一帧动画才真正开始跑，此时 `translationX = 0 + 甩出` —— 行已经在"中间"了。
 *
 * 两条加起来就是用户看到的"先弹回中间、再从中间往左滑"。它不是缓动、不是时长、
 * 也不是方向的问题（那三样上一轮都对齐过了），纯粹是**时序**：
 * 位移在"这一串手势结束"和"甩出动画开始"之间被抹掉了。
 *
 * ## 修法：甩出期间**不许归零**
 *
 * 归零只在"上一串没有进入甩出阶段"时才做（[beginGesture]），而 [release] 一旦判定
 * "够远要删"就把 [sweeping] 立起来 —— 此后这一串的收尾、以及任何提前转回来的循环，
 * 都不会再碰 [followPx]。行因此停在松手的位置上，[translationPx] 从那里继续往左叠加。
 *
 * 为什么"不用管什么时候把它放下来"：`release` 返回 true 之后调用方会调 `onDelete()`，
 * 这一行会被状态层从列表里摘掉、连同本对象一起消失（这是"甩出去"的语义本身）。
 * 万一哪一行没被真的摘掉（理论上不该发生），它也只是停在屏幕外（`sweep` 停在 1、
 * alpha 0）、而不是回弹回来闪一下 —— 这个方向的失败是可接受的。
 *
 * ⚠️ 三行（函数行 / 几何对象行 / 滑动条行）共用 `DragGestures.rememberRowSwipeDelete`，
 * 也就是共用这个类，所以"从松手位置继续甩"在三行上**是同一件事**，不是三份实现。
 */
class SwipeDeleteMotion {

    /** 跟手位移（px，往左为负）。0 = 行还在它自己的位置上。 */
    var followPx: Float = 0f
        private set

    /** true = 这一行已经进入"被扔掉"阶段（甩出动画在播，马上就会被删掉）。 */
    var sweeping: Boolean = false
        private set

    /**
     * 一串手势开始了（`awaitEachGesture` 的循环体转回来时）。
     *
     * **甩出期间不归零** —— 见类注释：正常抬手之后循环体会立刻回来一次，
     * 那一次归零正是"从中途重新开始滑"的真因。
     */
    fun beginGesture() {
        if (!sweeping) followPx = 0f
    }

    /** 手指每动一帧：`totalPx` = 从落点算起的**带符号**累计位移。 */
    fun drag(totalPx: Float) {
        if (!sweeping) followPx = swipeDeleteFollow(totalPx)
    }

    /**
     * 抬手。
     *
     * @return true = 够远、这一行要甩出去（调用方去播甩出动画，**并且不要再碰位移**）；
     *   false = 不够远，行弹回原位（[followPx] 已经归零）。
     */
    fun release(totalPx: Float, rowWidthPx: Float): Boolean {
        if (sweeping) return false
        if (!swipeDeleteReached(totalPx, rowWidthPx)) {
            followPx = 0f
            return false
        }
        sweeping = true
        return true
    }

    /** 这一行此刻该画在哪（px，往左为负）= 跟手位移 + 甩出位移。 */
    fun translationPx(rowWidthPx: Float, sweep: Float): Float =
        swipeDeleteTranslationPx(followPx, rowWidthPx, sweep)
}

/**
 * 「单向拖动」认不认领这一串手势的判据：**越过触摸容差、而且方向对**。
 *
 * 这是 `DragGestures.awaitOneWayDrag` 里唯一的判定。单独抽出来是为了能离线钉住它 ——
 * 三条语义缺一不可（下面的测试逐条钉着）：
 *   * 没过容差 → **两个方向都不认领**（这一条是"点击 / 长按 / 列表滚动还能用"的全部
 *     依据：手指落下的头几像素一定是抖的）；
 *   * 越过容差、方向对 → 认领，此后每帧消费并回调；
 *   * 越过容差、方向不对 → 调用方**立刻退出、一个字节都不消费**，把这一串原样让给
 *     别人（父节点的手势、列表滚动都靠这个）。
 *
 * @param totalPx 从落点算起的**带符号**累计位移（px）。
 * @param positive 认哪个方向：水平 true = 往右、false = 往左；竖直 true = 往下、false = 往上。
 * @param slopPx 触摸容差（px），就是 `ViewConfiguration.touchSlop`。
 */
fun oneWayDragClaims(totalPx: Float, positive: Boolean, slopPx: Float): Boolean =
    totalPx.isFinite() && slopPx.isFinite() && abs(totalPx) >= slopPx &&
        ((totalPx > 0f) == positive)

/**
 * 单向拖动**累加一步**：把这一帧的位移并进"我们自己的累计位移"。
 *
 * ⚠️ 这个函数**只有两个输入**：累计值、这一帧的位移。**没有第三个输入** ——
 * 尤其没有"这一帧有没有被别人消费"这一项。这就是"认领只看向累计位移与方向"在代码上的
 * 形状：父节点的横向手势、列表的竖直滚动、子节点的 clickable 谁消费了这一串事件，
 * 都与我们的判定无关（列表滚动由它自己的轴负责，横向删除不该受它的影响）。
 *
 * 真机上它对应 `PointerInputChange.positionChangeIgnoreConsumed()` —— **绝对不能**
 * 换成 `positionChange()`：后者在"这一帧已被消费"时返回 0（Compose 1.7 的字节码：
 * `positionChangeInternal(change, ignoreConsumed = false)` 里那句
 * `if (isConsumed) Offset.Zero else offset`）。一旦父节点抢先消费，我们的累计位移会被
 * **每帧清零**、永远到不了阈值 —— 用户报的"纯水平左滑删不掉、带一点斜度就能删"
 * 就是这么来的（完整机制见 `DragGestures.kt` 文件头"谁在消费这一串事件"那一节）。
 *
 * 非法输入（NaN / 无穷）时**保留原累计值**：一个坏帧不该把我们滑了半天的位移抹掉。
 */
fun oneWayDragStep(totalPx: Float, deltaPx: Float): Float =
    if (totalPx.isFinite() && deltaPx.isFinite()) totalPx + deltaPx else totalPx

/**
 * 「这一串手势已经被**另一个轴**拿走了」—— 行删除必须**永久放弃**这一串。
 *
 * ## 真因（用户报的误删："上下滑之后再左右滑，会把最开始按住的那一行删掉"）
 *
 * 用户原话："我在就列表这里上下滑动之后，如果再左右滑动，会唤起这个开始按住的
 * 对象的删除，但是实际上我上下滑动之后，我应该唤起不了对象的删除。"
 *
 * 机制：行删除手势是**水平**的（[DragAxis.HORIZONTAL]），它只累计 x；
 * 而列表的竖直滚动是 `LazyColumn` 自己认领的（越过竖直触摸容差就认领并消费）。
 * 上一版里，一旦竖直滚动把这一串拿走了，我们这份"横向累计"**还在继续涨** ——
 * 手指在滚动到一半之后改成横着划，x 累计很快就越过触摸容差，
 * 于是被判成"用户想删这一行"（而用户心里那一下是"我在滚列表"）。
 * 行跟着手指滑出去、松手就删 —— 一次明确的误删。
 *
 * ## 判据：**谁的轴先越过触摸容差，这一串就归谁**
 *
 * 这条规矩不是新发明的，它正是平台自己的约定（`scrollable` / `draggable` 都是
 * "越过容差即认领，认领者独占"）。所以这里只需要回答一个问题：
 * "**交叉轴**是不是已经先一步越过了容差、而且比主轴还大？"
 *
 * ```
 *   交叉轴 |cross| < 容差                → false（还没人能拿走，继续观察）
 *   交叉轴 |cross| ≥ 容差 且 > |主轴|    → true （竖着滑动，不是横着划走）
 *   交叉轴 |cross| ≥ 容差 但 ≤ |主轴|    → false（横着划为主，仍归这一行）
 * ```
 *
 * 第三条是**故意留活口**的：真正的横向划过（含"横为主、带一点斜"）里，手指的
 * 纵向抖动会越过容差，但 x 一直领着 y —— 那时仍然算删除。
 * 反之（y 领着 x，也就是"竖着滚列表"）一律放弃。
 *
 * ## 为什么"一旦为真就永远为真"（调用方必须 `break`，不能只是这一帧不认领）
 *
 * 用户明确要求"**之后的横向移动也不能再复活它**"。如果只是这一帧不认领、
 * 下一帧继续观察，那么"先竖着滚过容差、再横着划够"照样会删 —— 那就是同一类误删。
 * 所以调用方一旦拿到 true 就**彻底退出**这一串手势（同"方向不对"那条路：
 * 一个字节都不再消费），本串手势此后与删除无关。
 *
 * @param primaryTotalPx 主轴（行删除 = 水平）从落点算起的累计位移（px，带符号）。
 * @param crossTotalPx 交叉轴（行删除 = 竖直）从落点算起的累计位移（px，带符号）。
 * @param slopPx 触摸容差（px），就是 `ViewConfiguration.touchSlop`。
 * @return true = 这一串已经被交叉轴拿走，行删除放弃（**永久**）。
 *   非法输入一律 false（宁可让这一串继续按老规矩走，也不要因为一个坏帧把删除吞掉）。
 */
fun oneWayDragLostToOtherAxis(
    primaryTotalPx: Float,
    crossTotalPx: Float,
    slopPx: Float
): Boolean {
    if (!primaryTotalPx.isFinite() || !crossTotalPx.isFinite()) return false
    if (!slopPx.isFinite() || slopPx <= 0f) return false
    val cross = abs(crossTotalPx)
    // 交叉轴连容差都没过：列表滚动根本还没认领这一串，我们没有理由放弃
    if (cross < slopPx) return false
    // 交叉轴过了容差，而且比主轴还大 → 这是"竖着滑动"，不是"横着划走"
    return cross > abs(primaryTotalPx)
}

// ===================================================================================
// 滑动条行（`SliderRow`）的**版面比例** + 「删除起手区」里能用算术回答的那一半
//
// ## 为什么这几个比例搬到本文件（它们原来是 `SliderRow.kt` 里的 private const）
//
// 同一份数字现在有三个使用者：**版面**（行的 padding / 参数字母栏宽 / 子元素间距 /
// 播放键尺寸）、行内**长按**判定（"播放键那一格不算长按区"）和这一节新增的
// "按住的这一点是不是播放键"。抄成两份迟早会漂 —— 本项目为这类漂移付过一次代价：
// 函数行和对象行各写一套删除手势，结果"函数能删、别的对象不能删"。
// 放在本文件还多一个好处：它是**纯 Kotlin**，能被 `tools\run-tool-tests.ps1`
// 那套离线单测钉住（断言在 `PlotMetricsTest` 末尾那一组），
// 于是"删除起手区在三块屏上到底有多宽"这件事不必装到设备上试。
//
// ## 滑动条行的手势分区：**谁在手指底下，谁说了算**（不是按 x 坐标切三块）
//
// 用户要求"滑动条行也要能左滑删除"，而这条行的横向拖动被"拖参数值"占着。
// 划分是这样（三块地方，互不重叠）：
//
// | 手指落在哪 | 横着拖动 = 什么 | 由谁保证 |
// |---|---|---|
// | 滑轨 `SliderTrack` | **改值**（滑轨占满它自己那一格） | 滑轨自己的 `detectTapGestures` 消费掉 down → 行的删除手势用 `skipIfDownConsumed = true`，**根本不开始** |
// | 播放键 `PlayKey` | 什么都不做（松手 = 播放/停止） | 同上（它也消费 down） |
// | 参数字母/当前值徽标、下界/上界文字、行内边距与缝隙 | **左滑删除** | 这些地方没有任何子节点消费 down，删除手势照常起手 |
//
// 为什么**不**用"传一个 x 区间当起手区"（`startsIn`）：
//   * 滑轨的左沿 = `行左内边距 + 参数字母栏 + 缝 + 播放键宽 + 缝 + 下界文字宽 + 缝`，
//     而**下界/上界文字的宽度是量出来的**（滑轨是 `weight(1f)`，吃的是剩下的宽度）——
//     同一个 233dp 圆表上，`-5~5` 和 `-0.5~0.5` 量出来的滑轨并不是同一条；
//   * 字号、行高、内边距全部按**屏幕短边**缩放（见 `WatchMetrics`），"哪一段是滑轨"
//     在三块屏上不是一个 dp 数，写死一个比例一定会在某块屏上偏；
//   * 而**命中测试本来就知道滑轨在哪**：用"谁消费了 down"来分区，等于把边界交给布局，
//     它永远等于真实绘制边界 —— 文字变宽、系统放大字号、换 density 都不会漂。
//   所以"删除起手区"的边界**不是本文件算的**；本文件只算**播放键那一格**
//   （它由纯比例决定、不依赖文字宽度），用来回答"按住的这一点是不是播放键"。
//
// ## 真机上的三条保证（一一对应，改代码时别拆）
//
//   * **在滑轨上拖动永远不会误删**：滑轨的 down 被它自己消费 → 删除手势第一句就返回、
//     **一个字节都不消费**（和"对象行上一版右边缘那条带"用的是同一个机制）；
//   * **在非滑轨区域左滑能稳定删除**：徽标 / 下界上界文字 / 边距里没有任何子节点消费
//     down，删除手势照常起手，判定仍然是"往左滑够 1/3 行宽"（[swipeDeleteReached]）；
//   * **短按 / 长按不变**：没过触摸容差时删除手势两个方向都不认领、不消费任何事件
//     （[oneWayDragClaims] 第一条语义），所以"短按什么都不做""长按进滑动条属性页"
//     "按住 ▶ 只切播放"三条都照旧。
//
// ## 三块屏上的实际 dp（**行内**坐标，行左沿 = 0）
//
// | 设备（短边） | 行高 0.15× | 左内边距 0.022× | 参数字母栏 0.12× | 缝 0.016× | 播放键宽 | **删除起手区左带**（= 播放键左沿） | 播放键那一格 | 滑轨（标称） |
// |---|---|---|---|---|---|---|---|---|
// | 圆表 233dp | 34.95 | 5.13 | 27.96 | 3.73 | 24.92 | **36.8** | 36.8 ~ 61.7（24.9 × 21.7） | ≈117（最宽行 0.90 屏宽）/ ≈29（最窄行 0.52） |
// | 方表 200dp | 30.00 | 4.40 | 24.00 | 3.20 | 21.39 | **31.6** | 31.6 ~ 53.0（21.4 × 18.6） | ≈109 |
// | 手机 411dp | 61.65 | 9.04 | 49.32 | 6.58 | 43.96 | **64.9** | 64.9 ~ 108.9（44.0 × 38.2） | ≈224 |
//
// 把三块地方**加起来**看（行宽取"非圆屏铺满"和"圆屏最宽行 0.90 短边"）：
//
// | 设备（行宽） | 删除起手区合计（左带 + 下界那一截 + 上界那一截） | 播放键那一格 | 滑轨 |
// |---|---|---|---|
// | 圆表 233dp（209.7dp） | **≈67.3dp（32%）** | 24.9dp（12%） | ≈117.5dp（56%） |
// | 圆表 233dp 最窄行（121.2dp） | **≈67.3dp（56%）** | 24.9dp（21%） | ≈28.9dp（24%） |
// | 方表 200dp（188.0dp） | **≈57.8dp（31%）** | 21.4dp（11%） | ≈108.8dp（58%） |
// | 手机 411dp（386.3dp） | **≈118.7dp（31%）** | 44.0dp（11%） | ≈223.6dp（58%） |
//
// "滑轨"和"删除起手区合计"里带 ≈ 的那部分都含**下界/上界文字的实测宽度**
// （按下界 `-5`、上界 `5` 估算，量程写得越长这两块越宽、滑轨越窄）——
// 真实值随文字走，所以代码里根本没有这些数（见上一节）。**不含 ≈ 的那几个数
// （左内边距 / 参数字母栏 / 缝 / 播放键 / 左带）是纯比例，逐位可算**。
//
// 怎么读这张表：三块屏上"删除起手区"都稳定在**行宽的 31%~32%**（最窄的圆表行上甚至
// 占 56%，因为徽标宽度不随弧宽缩），而"滑轨"始终是行里最宽的一块（56%~58%）。
// 所以这不是"把拖值的地盘让出去"，而是把它本来就有的那一块**明确划给它** ——
// 删除起手区完全是原来"点一下没反应"的那些地方（徽标、上下界文字、边距与缝）。
// 真机上手指按得中：圆表 36.8dp × 34.95dp 的左带已经和图标按钮（41.9 × 29.1dp）同量级，
// 手机上的 64.9dp 更宽裕；而滑轨那一侧一点没被吃掉。
// ===================================================================================

/**
 * 滑动条行的左内边距（占**屏幕短边**）—— 版面与手势共用的唯一来源。
 *
 * 三个使用者：`SliderRow` 里 `Row` 的 `padding`、"播放键那一格"的算术、
 * 以及"按住这一点算不算播放键"的判定。改它必须三处同时成立，所以只留这一个数。
 */
const val SLIDER_ROW_PAD = 0.022f

/** 滑动条行里"参数字母 + 当前值"那一栏的宽度（占短边）。 */
const val SLIDER_PARAM_COL_W = 0.12f

/** 滑动条行内各元素之间的缝（占短边）。 */
const val SLIDER_ROW_GAP = 0.016f

/** 播放键高度（占**行高**）：0.62 × 行高，是颗小胶囊而不是正方形。 */
const val SLIDER_PLAY_KEY_H = 0.62f

/** 播放键宽度相对高度的倍数（1.15 → 略宽于正方形）。 */
const val SLIDER_PLAY_KEY_SCALE = 1.15f

/**
 * 播放键那一格的**左沿**（dp，行内坐标）。
 *
 * `行左内边距 + 参数字母栏 + 一条缝` —— `SliderRow` 的 `Row` 是
 * `padding(horizontal = 短边 × [SLIDER_ROW_PAD])` + `spacedBy(短边 × [SLIDER_ROW_GAP])`，
 * 第一个子元素（参数字母/当前值那一栏）固定占 `短边 × [SLIDER_PARAM_COL_W]`，
 * 所以这个式子和**真实排版**逐位一致，不是估的。
 *
 * 它同时是"左带删除起手区"的右沿（那一带 = `[0, 本值]`，见本文件上面那张表）。
 */
fun sliderPlayKeyLeftDp(sizeDp: Float): Float {
    if (!sizeDp.isFinite() || sizeDp <= 0f) return 0f
    return sizeDp * (SLIDER_ROW_PAD + SLIDER_PARAM_COL_W + SLIDER_ROW_GAP)
}

/**
 * 播放键那一格的**右沿**（dp，行内坐标）= [sliderPlayKeyLeftDp] + 播放键宽。
 *
 * 播放键宽 = `行高 × [SLIDER_PLAY_KEY_H] × [SLIDER_PLAY_KEY_SCALE]`
 * （`PlayKey` 的 `size(width = size × SLIDER_PLAY_KEY_SCALE, height = size)`，
 * 而 `size = rowHeight × SLIDER_PLAY_KEY_H`）。
 */
fun sliderPlayKeyRightDp(sizeDp: Float, rowHeightDp: Float): Float {
    if (!rowHeightDp.isFinite() || rowHeightDp <= 0f) return sliderPlayKeyLeftDp(sizeDp)
    return sliderPlayKeyLeftDp(sizeDp) +
        rowHeightDp * SLIDER_PLAY_KEY_H * SLIDER_PLAY_KEY_SCALE
}

/**
 * 按下的那一点（行内 px）算不算**播放键那一格**。
 *
 * 这一格在滑动条行里有两个含义，都靠它：
 *   * **不算长按区** —— 按住 ▶ 半秒不该弹出属性界面（那里是"按住播放键"，
 *     松手还是要切一次播放的，见 `SliderRow` 文件头）；
 *   * **不算删除起手区** —— 在 ▶ 上横着划不该把这一行删掉（和滑轨同一个待遇；
 *     真机上这一点由"播放键自己消费 down"再兜一道，见本文件上面那张表）。
 *
 * 端点算**在内**（`[左沿, 右沿]` 闭区间）：边界上不留一条"擦过去就变成删除"的缝。
 * 注意它的**竖直范围是整行**（不看 y）：播放键只有行高的 0.62，
 * 它上下那两条小空隙按这里也一并归播放键那一格 —— 那两条缝里按住本来什么都不做，
 * 与其让它变成"按住没反应"的死角，不如和它上下对齐（删除手势仍然照常起手）。
 *
 * 非法输入（NaN）返回 false = "不算播放键"，于是长按照常可以成立 ——
 * 宁可长按能用，也不要因为一个坏坐标把长按吞掉（删除那边另有"down 被消费没有"把关）。
 */
fun sliderPressOnPlayKey(xPx: Float, playLeftPx: Float, playRightPx: Float): Boolean {
    if (!xPx.isFinite() || !playLeftPx.isFinite() || !playRightPx.isFinite()) return false
    if (playRightPx < playLeftPx) return false
    return xPx >= playLeftPx && xPx <= playRightPx
}

// ===================================================================================
// 五、顶部工具条上那条「横着滑一下唤出撤销 / 重做菜单」的尺度
//
// ## 用户要的是什么（含本轮那条**关键补充**）
//
// 原话："给这个软件加一个撤销的功能，在函数列表界面，从设置滑到加号。按键可以在设置和
// 加号那一条的地方显示出撤销菜单有左箭头和右箭头。然后在功能列表界面从文件夹滑到删除
// 也是一样的道理。从加号滑到设置和从删除滑到文件夹，也可以调出来撤销按钮。"
//
// 补充（本轮）："**按出撤销菜单的条件是从顶部的一个按钮滑到另一个按钮，请不要给顶部的
// 按钮还没有滑到另一个按钮的时候就加动画。**"
//
// 也就是**两屏各一处**、同一件事，而且判据是**几何**的、不是"拖了多远"：
//   * 第一屏从 ⚙ 滑到 `+`（或反过来）；第二屏从 📁 滑到 🗑（或反过来）；
//   * **必须真的越过两颗键之间那道空当、进入另一颗键的矩形**才算达成；
//   * **达成之前一帧动画都没有**（顶部的键不动、菜单不半开、不预先让位）——
//     所以本节的判定里**没有"跟手进度"这种东西**：只有"到了/没到"两个状态。
//
// ## 为什么这些数住在本文件（而不是 `Common.kt` / 两屏里各写一份）
//
// 和上面四节**同一条理由**：它们是纯算术、零 Android/Compose 依赖，却决定了
// "什么算滑到了另一颗键""两端的键让开多少""菜单怎么展开"这些用户一眼能看到的行为。
// 它们不该只靠"装到设备上滑一下"来验证 —— 而"能不能被那个脚本编进去"是这个包里
// **文件位置**的硬约束（`tools\run-tool-tests.ps1` 只编固定的八个 .kt），所以纯算术
// 只能住在这里，断言在 `PlotMetricsTest` 末尾那一组。
//
// ## 两颗键在工具条上的**真实位置**（判定的全部依据）
//
// 工具条那一行的版面是：`Row(Modifier.fillMaxSize(), Arrangement.SpaceBetween)` 里放
// **两颗** `WatchIconButton`，所以行内坐标（行左沿 = 0，单位 px）恒为：
//
// ```
//   左键矩形 [0, w]                        右键矩形 [行宽 − w, 行宽]
//   中间那道空当 (w, 行宽 − w)             w = metrics.iconButton（= 0.18 × 短边，形状锁定）
//   ↑ 起点落在空当里的横滑**不算**（那是给"顶部下滑回图像"留的活路，见 `Common.kt`）
// ```
//
// 三块屏上的实算（行宽 = 圆屏弧宽比例 / 非圆屏"屏宽 − 左右各 3%"，见 `ScreenGeometry.rowWidth`）：
//
// | 设备 | 行宽 | 单键宽 w | 左键矩形 | 右键矩形 | **中间空当**（最短行程） | **中心距**（典型行程） |
// |---|---|---|---|---|---|---|
// | 圆表 233dp | 121.2dp | 41.9dp | [0, 41.9] | [79.2, 121.2] | **37.3dp** | **79.2dp** |
// | 方表 200dp | 104.0dp | 36.0dp | [0, 36.0] | [68.0, 104.0] | **32.0dp** | **68.0dp** |
// | 手机 411dp | 386.3dp | 74.0dp | [0, 74.0] | [312.4, 386.3] | **238.4dp** | **312.4dp** |
//
// 读这张表要看出两件事：
//   * **"随便横拖一点"不可能达成** —— 最短行程 = 空当宽度，圆表 37.3dp（74.6px @d2）
//     是触摸容差（≈24px）的 3 倍，方表 64px、手机 655px 更不必说。所以本节的判定
//     **不需要**任何额外的距离阈值常量：几何本身就卡住了。
//   * **典型行程 = 中心距**（从 ⚙ 的中心滑到 `+` 的中心）79.2dp（圆表）——
//     这正是用户嘴里"从设置滑到加号"那一下。
// ===================================================================================

/** 工具条上两颗图标键的左右身份（[NONE] = 都没落在键上，也就是**中间那道空当**）。 */
enum class ToolbarKeySide { NONE, LEFT, RIGHT }

/**
 * 落点 [xPx]（**工具条行内**坐标，行左沿 = 0）落在哪一颗键上。
 *
 * 判据就是上面那张表：左键矩形 `[0, w]`、右键矩形 `[行宽 − w, 行宽]`，两者**都含端点**
 * （边界上不留一条"擦过去不算"的缝），中间那道空当返回 [ToolbarKeySide.NONE]。
 *
 * 非法 / 退化输入一律 [ToolbarKeySide.NONE]（= "这一次横滑与我们无关"）：
 *   * 行宽或键宽非正、NaN；
 *   * **两道键重叠**（`2w ≥ 行宽`，只在极端的坏版面上可能出现）—— 那时"进入另一颗键"
 *     根本没有中间地带可言，宁可不认，也不能把一次点击当成"滑过去"。
 * 负坐标（手指拖到行的左边外面）按同一把尺子判：它会先穿过空当、再进左键矩形，
 * 于是"从右键出发往左拖出去"照样能达成（那是用户要的"从删除滑到文件夹"的同一件事）。
 */
fun toolbarKeyAt(xPx: Float, rowWidthPx: Float, keyWidthPx: Float): ToolbarKeySide {
    if (!xPx.isFinite() || !rowWidthPx.isFinite() || !keyWidthPx.isFinite()) return ToolbarKeySide.NONE
    if (rowWidthPx <= 0f || keyWidthPx <= 0f) return ToolbarKeySide.NONE
    if (keyWidthPx * 2f >= rowWidthPx) return ToolbarKeySide.NONE
    if (xPx <= keyWidthPx) return ToolbarKeySide.LEFT
    if (xPx >= rowWidthPx - keyWidthPx) return ToolbarKeySide.RIGHT
    return ToolbarKeySide.NONE
}

/**
 * 这一次横滑**达成**了吗 —— 用户那条补充的判据本体。
 *
 * `startedAt` = 落点在哪颗键上（[toolbarKeyAt] 在 down 那一刻算的），
 * [nowXPx] = 手指**此刻**的行内 x（= 落点 + 我们自己累计的横向位移）。
 *
 * 达成的定义（三个条件，缺一不可）：
 *   1. 起手落在**某一颗键**上（[startedAt] 不是 [ToolbarKeySide.NONE]）——
 *      "从顶部的一个按钮滑到另一个按钮"里那个"从"；
 *   2. 手指此刻进了**另一颗键**的矩形 —— 那个"到"；
 *   3. 于是"越过两颗键之间那道空当"是它蕴含的（从一颗键的矩形到另一颗键的矩形，
 *      中间必须走完整条空当）。
 *
 * 调用方**每帧**拿手指当前 x 问它一次，第一次为真就是"滑到了"的那一刻
 * （`Common.kt` 因此可以**在手指还按着的时候就展开菜单**，用户要的就是这个直接反馈）。
 */
fun toolbarMenuCrossedToOtherKey(
    startedAt: ToolbarKeySide,
    nowXPx: Float,
    rowWidthPx: Float,
    keyWidthPx: Float
): Boolean {
    if (startedAt == ToolbarKeySide.NONE) return false
    val now = toolbarKeyAt(nowXPx, rowWidthPx, keyWidthPx)
    return now != ToolbarKeySide.NONE && now != startedAt
}

/**
 * 两颗键的**中心距**（px）—— "从设置滑到加号"那一下的典型行程。
 *
 * = `行宽 − 键宽`（左键中心 w/2 → 右键中心 行宽 − w/2）。
 * 它不在判定里（判定用的是"进另一颗键的矩形"，见 [toolbarMenuCrossedToOtherKey]），
 * 存在的意义有两个：
 *   * **离线测试拿它对拍"典型行程"**（圆表 79.2dp、方表 68.0dp、手机 312.4dp）；
 *   * 报告与注释里引用同一个数，不必各自算一遍。
 */
fun toolbarKeyCenterDistancePx(rowWidthPx: Float, keyWidthPx: Float): Float {
    if (!rowWidthPx.isFinite() || !keyWidthPx.isFinite()) return 0f
    return (rowWidthPx - keyWidthPx).coerceAtLeast(0f)
}

/**
 * 两颗键之间那道**空当**的宽度（px）= `行宽 − 2 × 键宽`。
 *
 * 它同时是达成条件的**最短行程**（从一颗键的内沿起手、走到另一颗键的内沿）——
 * 所以它证明了一件事：**不需要任何额外的距离阈值**，几何本身就保证"随便拖一点"不成立
 * （圆表 74.6px、是触摸容差 ≈24px 的 3 倍）。退化版面（两道键重叠）返回 0。
 */
fun toolbarKeyGapPx(rowWidthPx: Float, keyWidthPx: Float): Float {
    if (!rowWidthPx.isFinite() || !keyWidthPx.isFinite()) return 0f
    return (rowWidthPx - keyWidthPx * 2f).coerceAtLeast(0f)
}

// -----------------------------------------------------------------------------------
// 「工具条那一行」= 顶部那排按键所在的**整条全宽高度带** —— 这一块让给撤销菜单，切屏手势不认它
//
// ## 用户要求（原话，三轮，**以最后一句为准**）
//
//   1. "在能够唤出撤销菜单的按键的位置滑到另一个屏幕的判定应该取消掉。"
//      —— 落在这一条里的横滑**只服务于撤销菜单**，不再切屏；
//   2. "在右边缘手势的起手判据里把工具条那一段（**屏高 10%~22.5%**）排掉。"
//   3. "**只排掉那一行本身。**"
//      —— 上一句的 22.5% 是"按真实屏高"算出来的下沿，在手机上比那一行本身高 55dp；
//         用户点名收掉，于是下沿改成**行顶 + 行高（行高按短边）**，也就是工具条 Box
//         自己那两个式子，禁区**逐像素等于那一行**。
//
// 所以本函数的矩形 = **x ∈ [0, 整屏宽] × y ∈ [行顶, 行顶 + 行高]**：
// 上沿 = `geometry.sy(TOOLBAR_TOP)`（占真实屏高 0.10）、下沿 = 上沿 + `0.125 × 短边`
// （`TOOLBAR_H × metrics.sizeDp`），**左右沿就是整屏宽**（用户第 1、2 句说的都是"那一段"）。
//
// ⚠️ 这里要说清一个取舍（免得后人以为写错了）：**比行矩形更宽的那两部分其实并不冲突** ——
// 撤销菜单的起手区是"某颗键的矩形"（见 [toolbarKeyAt]），两颗键都在行内，
// 圆表上行两侧各空 55.9dp，那两块地方本来谁也唤不出菜单；把它们一并封掉，
// "左边缘右滑"在这一带里就少了 55.9dp 的可用起手面。**但用户要求按高度一刀切**
// （他只说"那一段/那一条"），所以就按他说的来：**左右全宽、上下就是那一行**。
//
// ## 两条切屏手势因此变成这样
//
// | 屏 | 原起手区 | 现在 |
// |---|---|---|
// | 第一屏「左边缘右滑进功能界面」 | x < 26% 屏宽、y ∈ 12%~92% | 同上，**但要排掉这一块**（圆表 y 23.3~52.4dp） |
// | 第二屏「右边缘左滑回函数列表」 | x ≥ 74% 屏宽、全高 | 同上，**但要排掉这一块** |
//
// 实测（三块屏，**左右全宽、上下 = 那一行**；行顶 = 0.10 真实屏高、行高 = 0.125 短边）：
//
// | 设备 | 这一块（屏幕坐标） |
// |---|---|
// | 圆表 233dp / d2（短边 = 屏高） | **x ∈ [0, 233]dp（0~466px）、y ∈ [23.30, 52.43]dp**（46.60~104.85px） |
// | 方表 200dp / d2（短边 = 屏高） | x ∈ [0, 200]dp（0~400px）、y ∈ [20.00, 45.00]dp（40.0~90.0px） |
// | 手机 411 × 850dp / d2.75 | x ∈ [0, 411]dp（0~1130.25px）、y ∈ [85.00, 136.38]dp（233.75~375.03px） |
//
// 圆表与方表上短边 = 屏高，所以新旧口径**逐像素相同**（用户要求的"圆表不变"自动满足）；
// 手机上新的下沿是 **136.38dp（375.03px）**，比旧口径的 191.25dp 少了 55dp ——
// 那 55dp 就是"按真实屏高的 22.5%"多让出去的部分。
// -----------------------------------------------------------------------------------

/**
 * 顶部工具条那一行的**整条全宽矩形**（px，屏幕坐标）—— 切屏手势的"禁区"。
 *
 * @param screenWidthPx 整屏宽（px）：**左右沿就是 0 与它**（用户点名的口径是"那一段"）。
 * @param bandTopPx 这一块的上沿（px）= `geometry.syPx(TOOLBAR_TOP)`（行顶，0.10 真实屏高）。
 * @param bandBottomPx 这一块的下沿（px）= 上沿 + `TOOLBAR_H × metrics.sizeDp`（行高，按**短边**）
 *   —— 和工具条 Box 自己的 `.offset(y = geometry.sy(TOOLBAR_TOP))` / `.height(...)` 逐字同源，
 *   所以禁区永远等于那一行。⚠️ 别再用 `geometry.syPx(TOOLBAR_TOP + TOOLBAR_H)`：
 *   那是"真实屏高的 22.5%"，在手机上会比那一行高 55dp（用户明确要求"只排掉那一行本身"）。
 *
 * 非法输入（NaN / 非正的宽高）返回 `null` = "没有禁区"：
 * 宁可让切屏手势照旧，也不能因为一个坏尺寸把边缘手势整块封死。
 *
 * ⚠️ 它是 **data class**（值相等）：调用点把它当 `pointerInput` 的 key 用 ——
 * 值没变（同一块屏、同一个版面）就不会重启手势协程，屏形一变（旋转 / 分屏）才重启，
 * 于是"切屏手势读到的禁区"永远是**当前**那一块，不会拿着上一次组合的旧矩形。
 */
data class ToolbarStripRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** 这一点（屏幕 px）在这一条里吗（四条边**都算在内**）。 */
    fun contains(xPx: Float, yPx: Float): Boolean {
        if (!xPx.isFinite() || !yPx.isFinite()) return false
        return xPx >= left && xPx <= right && yPx >= top && yPx <= bottom
    }
}

/** 造出"工具条那一条"的整条全宽矩形（见 [ToolbarStripRect] 的说明；退化输入返回 null）。 */
fun toolbarStripRect(
    screenWidthPx: Float,
    bandTopPx: Float,
    bandBottomPx: Float
): ToolbarStripRect? {
    if (!screenWidthPx.isFinite() || !bandTopPx.isFinite() || !bandBottomPx.isFinite()) return null
    if (screenWidthPx <= 0f || bandBottomPx <= bandTopPx) return null
    return ToolbarStripRect(
        left = 0f,
        top = bandTopPx,
        right = screenWidthPx,
        bottom = bandBottomPx
    )
}

/**
 * 菜单展开时，**两端的键**（⚙/`+`、📁/🗑）各自朝屏幕外让开的距离（占工具条行宽）。
 *
 * 为什么是"朝外"而不是"朝中间"：菜单是从**中间**长出来的，两端的键必须把中间让出来；
 * 朝中间收只会和箭头键撞在一起。0.04 × 行宽在圆表上是 4.8dp ——
 * 只是"让了一下"的暗示，不会把键推出屏幕（行两端离屏幕边还有 55.9dp）。
 *
 * ⚠️ 它**只在达成之后**才动（进度由 `openState` 驱动，见 `Common.kt`）：
 * 没滑到另一颗键的那一串手势里，这个位移恒为 0 —— 用户明确要求过
 * "还没滑到另一个按钮的时候不要加动画"。
 */
const val TOOLBAR_MENU_YIELD = 0.04f

/** 两颗箭头键之间的缝（占**短边**），和工具条那两颗按钮的缝同一个量级。 */
const val TOOLBAR_MENU_GAP = 0.014f

/**
 * 两端的键让开的横向位移（px，**朝屏幕外**为正负号见 [toRight]）。
 *
 * @param toRight true = 右边那颗（`+` / 🗑，往右让）；false = 左边那颗（⚙ / 📁，往左让）。
 * @param progress 菜单展开进度 0..1。
 */
fun toolbarMenuEndKeyShiftPx(rowWidthPx: Float, progress: Float, toRight: Boolean): Float {
    if (!rowWidthPx.isFinite() || !progress.isFinite()) return 0f
    val d = rowWidthPx * TOOLBAR_MENU_YIELD * progress.coerceIn(0f, 1f)
    return if (toRight) d else -d
}

/**
 * 菜单展开时两端那两颗键的不透明度：1 → [TOOLBAR_MENU_END_KEY_ALPHA]。
 *
 * 为什么是**变暗**而不是缩小消失：它们的位置和点击区在动画期间**一个像素都不动**
 * （只动图层），所以"暗下去但还在原地"才是诚实的 —— 用户这时候点那颗位置，
 * 点到的仍然是 ⚙（设置）/ 📁（函数文件），不会点空。
 */
fun toolbarMenuEndKeyAlpha(progress: Float): Float =
    if (!progress.isFinite()) 1f
    else 1f - (1f - TOOLBAR_MENU_END_KEY_ALPHA) * progress.coerceIn(0f, 1f)

/** 两端那两颗键"让位"时缩到多少。0.82：看得出退了一步，又不至于让图标糊掉。 */
const val TOOLBAR_MENU_END_KEY_SCALE = 0.82f

/** 两端那两颗键最终暗到多少（= 0.18）。 */
const val TOOLBAR_MENU_END_KEY_ALPHA = 0.18f

/** [toolbarMenuEndKeyAlpha] 的缩放同伴：1 → [TOOLBAR_MENU_END_KEY_SCALE]。 */
fun toolbarMenuEndKeyScale(progress: Float): Float =
    if (!progress.isFinite()) 1f
    else 1f - (1f - TOOLBAR_MENU_END_KEY_SCALE) * progress.coerceIn(0f, 1f)

/**
 * 撤销菜单本体（那两颗箭头键所在的那一行）的展开比例。
 *
 * ## 为什么是"宽度先长、高度随后、透明度从头跟到尾"
 *
 * 用户要求"从中间**展开**（宽度/透明度/缩放一起动）"。三个量分开取不同区间，
 * 合起来才是"展开"而不是"放大"：
 *   * **横向** 0.30 → 1.00：它主要表达"这东西是从中间往两边长出来的"；
 *   * **纵向** 0.60 → 1.00：只收一点，因为两颗键的高度本来就是固定的（0.125 短边），
 *     纵向压太多会看起来像"从一个小点里蹦出来"，反而像故障；
 *   * **透明度** 0 → 1：线性跟进度走，收起时反向 —— 于是"淡出"和"缩回去"同时发生。
 *
 * 只动 `graphicsLayer`：菜单的占位尺寸在动画期间不变，所以工具条那一行、
 * 下面列表的上沿**一帧都不会抖**（这是用户明确要求过的"不要让布局抖动"）。
 */
fun toolbarMenuPanelScaleX(progress: Float): Float =
    if (!progress.isFinite()) 1f
    else TOOLBAR_MENU_PANEL_SCALE_X_FROM +
        (1f - TOOLBAR_MENU_PANEL_SCALE_X_FROM) * progress.coerceIn(0f, 1f)

/** 见 [toolbarMenuPanelScaleX]：纵向 0.60 → 1.00。 */
fun toolbarMenuPanelScaleY(progress: Float): Float =
    if (!progress.isFinite()) 1f
    else TOOLBAR_MENU_PANEL_SCALE_Y_FROM +
        (1f - TOOLBAR_MENU_PANEL_SCALE_Y_FROM) * progress.coerceIn(0f, 1f)

/** 菜单本体的不透明度：0 → 1（线性）。 */
fun toolbarMenuPanelAlpha(progress: Float): Float =
    if (!progress.isFinite()) 0f else progress.coerceIn(0f, 1f)

/** 菜单展开时横向从 0.30 起（见 [toolbarMenuPanelScaleX]）。 */
const val TOOLBAR_MENU_PANEL_SCALE_X_FROM = 0.30f

/** 菜单展开时纵向从 0.60 起（见 [toolbarMenuPanelScaleX]）。 */
const val TOOLBAR_MENU_PANEL_SCALE_Y_FROM = 0.60f

/**
 * 工具条上方那道**细横线**（顶部抓手）在菜单展开时的不透明度：1 → 0。
 *
 * 为什么要动它：那道小胶囊是"这里能滑"的语言，而撤销菜单展开之后，
 * 中间要表达的是"这里是两个键" —— 两个"中间"的东西叠在一起会互相干扰。
 * 淡出它（只 160ms）比留着更干净，也让"展开"这件事多一个看得见的层次。
 */
fun toolbarMenuHandleAlpha(progress: Float): Float =
    if (!progress.isFinite()) 1f else 1f - progress.coerceIn(0f, 1f)

// ===================================================================================
// 六、本地存档里那一行「设置」的文本编解码（`Store` 的 `KEY_SETTINGS`）
//
// ## 为什么它住在本文件（这一条是"修 bug 的一半"，不是洁癖）
//
// 用户报的 bug："软件的设置，你没有添加记忆功能。每次我打开软件之前设置过的东西
// 都会回归默认。"
//
// 存档那一层（`ui/Store.kt`）**读过**设置、也**写过**设置，但那个文件 `import
// android.content.Context`，**编不进** `tools\run-tool-tests.ps1` 那套离线单测
// （脚本只编固定的八个 .kt 文件）。而"写的顺序 == 读的顺序"这件事恰恰是最需要
// 离线钉住的一类：这个项目最近往设置行末尾**追加过三段**（`blockSystemGestures`、
// `showZoomBar`、`confirmReturnToMove`），每一次追加都要同时改"写"和"读"两处 ——
// 只要有一处 `parts.size > N` 的判据写错，就会出现"某个设置永远读回默认值"
// 这种**看起来像没存、其实是没读对**的 bug，而它在设备上极难定位。
//
// 所以：**字段的个数与顺序只有这一份**（就是下面 [SettingsFields] 的主构造参数顺序），
// 编码与解码都从它生成。`Store` 只负责"`WatchSettings` ⇄ `SettingsFields`"这一层映射，
// 它自己不再知道字段顺序。
//
// ## 兼容规矩（老存档必须照读）
//
//   * 行本身缺失 / 字段数 < 4 → **整组退回默认值**（这是改动前就有的规矩：
//     连最老的四项都凑不齐，说明这一行不是我们写的）；
//   * 缺的那几段 → **各自按自己的默认值补**（`parts.size > N` 那一套）——
//     最老的四段存档到现在仍然读得出来，行为与改动前逐项一致；
//   * 任何一个字段坏掉（非数字、序号越界）→ 整组退回默认（不拿半截存档去改界面）。
//
// ⚠️ **新字段一律追加在末尾**。中间插一个会让老存档的字段整体错位 ——
// 读出来的就是"网格开关跑到坐标轴上"这种灾难。这条规矩现在由代码结构保证：
// 追加 = 在主构造参数末尾加一个（并给它一个"老存档缺这一段时"的默认值）。
// ===================================================================================

/**
 * 存档里那一行设置的**纯文本编解码**（字段顺序的唯一真相）。
 *
 * 字段顺序（就是下面主构造参数的顺序，**不许改**，只能往后追加）：
 * ```
 * 0 坐标系序号 | 1 网格 | 2 坐标轴 | 3 坐标数字 | 4 函数示例 | 5 切坐标系要确认 |
 * 6 度制(DEG) | 7 屏蔽系统手势 | 8 按键缩放栏 | 9 首次用工具"回到移动"提示 |
 * 10 白天模式(1=白底那套 / 0=夜间黑底) | 11 表冠模式 | 12 表冠灵敏度(十分之一为单位的整数) |
 * 13 主题风格(0=Material / 1=液态玻璃) | 14 模糊背景(1=开 / 0=关)
 * ```
 *
 * 默认值必须和 `GraphState.WatchSettings` 的默认值**同值**（那边是数据类的默认值，
 * 这边是"老存档缺这一段"时补的值）。离线单测钉的是**本文件这一份**（写的顺序、
 * 读的顺序、缺段补齐、坏行退回），两边若漂了，表现是"某项设置读回来是错的默认值"。
 *
 * @param coordinateSystem 坐标系的**序号**（`CoordinateSystem.entries` 的下标）。
 *   这里存序号而不是名字：老存档里就是数字，换成名字会让所有老存档读不出来。
 * @param degrees 全局度制（true = DEG）。每个函数自己的 `degrees` 在函数行里，不走这里。
 */
class SettingsFields(
    val coordinateSystem: Int = 0,
    val showGrid: Boolean = true,
    val showAxes: Boolean = true,
    val showNumbers: Boolean = false,
    val showPresets: Boolean = true,
    val confirmCoordSwitch: Boolean = true,
    val degrees: Boolean = false,
    // ---- 以上七段是最老的那一批；以下每一段都是后追加的，缺省值各有各的理由 ----
    /**
     * 屏蔽系统手势。**老存档缺这一段时当作"开"**（= 默认值 true）——
     * 方向和别的布尔项相反是刻意的：这个开关的默认值本来就是"开"，
     * 老用户升级上来必须继续开着（否则边缘上滑被系统抢走，他会以为"更新把它改坏了"）。
     */
    val blockSystemGestures: Boolean = true,
    /** 按键/滑动条缩放栏。老存档缺这一段 → false（= 不显示，和"默认关"一致）。 */
    val showZoomBar: Boolean = false,
    /**
     * 首次用工具时那条"回到移动"提示还要不要弹。老存档缺这一段 → **true**
     * （有意的：这一条说明本轮才补上，缺了它老用户会少知道一件事，
     * 代价只是一次可以勾掉的框）。勾过之后写下去就是 0，永久不再弹。
     */
    val confirmReturnToMove: Boolean = true,
    /**
     * **白天模式**（`true` = 白底那套新配色）。老存档缺这一段 → **false**，也就是**夜间黑底**。
     *
     * ⚠️ 字段名是 `dayMode`，语义是"是否白天"，**方向不要反过来**。
     * 上一版这一段叫 `nightMode` 但 `true` 表示白底 —— 设置页里"夜间模式：开"
     * 显示出来是白屏，用户一句"反了"就是它。
     *
     * 老存档兼容：旧版 `nightMode=false` 表示黑底，新版 `dayMode=false` 也表示黑底，
     * **存值语义完全一致**（只是换名），所以老存档零迁移。
     * 详见 `WatchSettings.dayMode` 的说明。
     */
    val dayMode: Boolean = false,
    /**
     * 表冠模式的**序号**（`CrownMode.entries` 下标：0 = OPPO、1 = 华为/三星）。
     * 老存档缺这一段 → 0（OPPO，本 App 一直用的那套，老用户零感知）。
     */
    val crownMode: Int = 0,
    /**
     * 表冠灵敏度的**十分之一**（10 = 1.0 倍，1 = 0.1 倍，100 = 10.0 倍）。
     * 老存档缺这一段 → 10（1.0 倍，改动前的手感）。
     *
     * 不直接存 Float：CSV 里的浮点数会带区域设置的小数点/科学计数法
     * （`toString()` 结果随 Locale 变），绕一圈可能变味；整数干净。
     */
    val crownSpeedTenths: Int = 10,
    /**
     * **主题风格**的序号（`ThemeStyle.entries` 下标：0 = Material、1 = 液态玻璃）。
     *
     * 老存档缺这一段 → 0（Material，也就是改动前的外观，零感知）。
     *
     * 为什么单独一段而不是塞进 `dayMode`：明暗和质感是两个正交维度，
     * 挤进一个布尔以后就没法表达"白天 + 玻璃"这种组合。
     */
    val themeStyle: Int = 0,
    /**
     * **模糊背景**（`true` = 抽屉背后透出函数图的模糊版）。
     *
     * 老存档缺这一段 → **true**：上一版（3.28）所有主题都共用一张静态模糊图，
     * 缺省成 `true` 才是"升级上来和之前一模一样"。
     *
     * 它和 [themeStyle] 是两个正交维度（见 `WatchSettings.blurBackdrop` 那张表），
     * 所以**不能**塞进同一段里编码。
     */
    val blurBackdrop: Boolean = true,
    /**
     * **模糊程度**（0~100，`WatchSettings.blurStrength × 100`，3.40 新增）。
     *
     * 老存档缺这一段 → **100**：升级上来糊度和之前一模一样。
     */
    val blurStrengthPercent: Int = 100,
    /**
     * **计算精度**（`CalcPrecision.entries` 下标：0 = 3 位、1 = 8 位、2 = 16 位）。
     *
     * 老存档缺这一段 → **1（8 位小数）**：这是本 App 改动前的显示习惯，
     * 升级上来的用户不该因为多了一段存档就换来一个陌生的小数位数。
     */
    val precision: Int = 1,
    /**
     * **按钮下阴影强度**（0~100，`WatchSettings.keyShadowStrength × 100`）。
     *
     * 老存档缺这一段 → **10**：这是用户点名的默认值（"按钮下阴影强度默认值改成 10%"）。
     * 和上面那条"升级上来零感知"不同 —— 这一项在旧存档里根本不存在，
     * 也就没有"旧观感"要保住，直接按 10% 走。
     */
    val keyShadowPercent: Int = 10,
    /**
     * **切换坐标系删除绘制图形**（1.2.71）。
     *
     * `true` = 切坐标系时连功能界面画出来的点/线/度量一起清掉；
     * `false`（默认）= 保留对象，只把它们的方程换成新坐标系的写法。
     *
     * 老存档缺这一段 → `false`：保持改动前的行为（只清函数、对象留着），
     * 升级上来的用户不会因为多了这一段就突然丢掉画好的图形。
     */
    val clearObjectsOnCoordSwitch: Boolean = false
) {

    /** 打包成存档里那一行（字段之间用逗号）。 */
    fun encode(): String = listOf(
        coordinateSystem.toString(),
        flag(showGrid),
        flag(showAxes),
        flag(showNumbers),
        flag(showPresets),
        flag(confirmCoordSwitch),
        flag(degrees),
        flag(blockSystemGestures),
        flag(showZoomBar),
        flag(confirmReturnToMove),
        flag(dayMode),
        crownMode.toString(),
        crownSpeedTenths.toString(),
        themeStyle.toString(),
        flag(blurBackdrop),
        // 第 16 段：**占位**。这一格原来是「存帧调试开关」，功能已按用户要求整条删除。
        // 位置必须留着并恒定写 "0"，否则老存档第 17~19 段会整体错位。
        "0",
        blurStrengthPercent.toString(),
        // 第 18 段：计算精度（0=3 位 / 1=8 位 / 2=16 位）
        precision.toString(),
        // 第 19 段：按钮下阴影强度（0~100 整数）
        keyShadowPercent.toString(),
        // 第 20 段：切换坐标系删除绘制图形（是/否，1.2.71）
        flag(clearObjectsOnCoordSwitch)
    ).joinToString(SEPARATOR)

    companion object {

        /** 存档里的字段分隔符（和函数行的 `|` 不同：设置行里没有一个字段是自由文本）。 */
        const val SEPARATOR = ","

        /** 字段个数。加一段就 +1（离线单测拿它对拍编码结果）。 */
        const val FIELD_COUNT = 20

        /** 最老的那一批有几段：连它都凑不齐就整组退回默认。 */
        private const val OLDEST_FIELD_COUNT = 4

        private fun flag(on: Boolean): String = if (on) "1" else "0"

        /**
         * 一行存档 → 设置。**绝不抛异常**（坏存档只该退回默认值，不该让 App 起不来）。
         *
         * @param line `Store` 里 `KEY_SETTINGS` 那一行；null = 老存档/全新安装。
         * @param coordinateSystemCount 本版本的 `CoordinateSystem.entries.size`。
         *   传进来而不是写死：枚举住在 `GraphState.kt`（带 Compose，编不进离线脚本），
         *   而"序号越界就退回默认"这条规矩必须能离线测到。
         */
        fun decode(line: String?, coordinateSystemCount: Int): SettingsFields {
            val fallback = SettingsFields()
            val parts = line?.split(SEPARATOR) ?: return fallback
            if (parts.size < OLDEST_FIELD_COUNT) return fallback
            val cs = parts[0].toIntOrNull() ?: return fallback
            if (coordinateSystemCount <= 0 || cs !in 0 until coordinateSystemCount) return fallback
            return SettingsFields(
                coordinateSystem = cs,
                // 前三项在最老的那一批里，一定存在
                showGrid = parts[1] == "1",
                showAxes = parts[2] == "1",
                showNumbers = parts[3] == "1",
                // ---- 以下每一项都"缺了就用自己的默认值"（老存档照读）----
                showPresets = boolAt(parts, 4, default = true),
                confirmCoordSwitch = boolAt(parts, 5, default = true),
                degrees = boolAt(parts, 6, default = false),
                blockSystemGestures = boolAt(parts, 7, default = true),
                showZoomBar = boolAt(parts, 8, default = false),
                confirmReturnToMove = boolAt(parts, 9, default = true),
                dayMode = boolAt(parts, 10, default = false),
                crownMode = intAt(parts, 11, default = 0),
                crownSpeedTenths = intAt(parts, 12, default = 10),
                themeStyle = intAt(parts, 13, default = 0),
                blurBackdrop = boolAt(parts, 14, default = true),
                // 第 16 段（原「存帧调试」）已删除，读到什么值都忽略。
                // 第 17 段：模糊程度（0~100）。缺 → 100（和之前一样糊）。
                blurStrengthPercent = intAt(parts, 16, default = 100).coerceIn(0, 100),
                // 第 18 段：计算精度（0=3 位 / 1=8 位 / 2=16 位）。缺 → 1（8 位）；
                // 越界（未来版本写了更大的数）按枚举上限夹住。
                precision = intAt(parts, 17, default = 1)
                    .coerceIn(0, CalcPrecision.entries.size - 1),
                // 第 19 段：按钮下阴影强度（0~100）。缺 → 10（用户点名的默认值）。
                keyShadowPercent = intAt(parts, 18, default = 10).coerceIn(0, 100),
                // 第 20 段：切换坐标系删除绘制图形（是/否）。缺 → false（保持旧行为：保留对象）。
                clearObjectsOnCoordSwitch = boolAt(parts, 19, default = false)
            )
        }

        /** 第 [index] 段是不是 "1"；这一行没有这一段就用 [default]。 */
        private fun boolAt(parts: List<String>, index: Int, default: Boolean): Boolean =
            if (parts.size > index) parts[index] == "1" else default

        /**
         * 第 [index] 段当整数读；这一段缺失/不是数字就用 [default]。
         *
         * 不 coerce 范围：序号越界由读取侧（`Store` → `WatchSettings` 的映射）
         * 用 `entries.getOrNull` 兜底，和 [coordinateSystem] 越界的处理分层一致。
         */
        private fun intAt(parts: List<String>, index: Int, default: Int): Int =
            if (parts.size > index) parts[index].toIntOrNull() ?: default else default
    }
}

/**
 * 存档里**还有没有东西可读** —— 这一条判据本身就是本轮那个 bug 的修复点。
 *
 * ## 真因回顾（一句话）
 *
 * `Store.load` 改动前是"函数列表为空 → 整份存档作废"（返回 null），而调用点写的是
 * `Store.load(context)?.let { … 恢复设置 … }` —— 于是**只要用户一条函数都没有，
 * 设置就永远读不回来**（切坐标系会清空全部函数、删掉最后一行、清屏都会到这个状态）。
 * 用户报的"每次打开软件之前设置过的东西都会回归默认"逐字对得上。
 *
 * ## 现在的规矩
 *
 * **四个键互不依赖**：只要任何一个有值，这份存档就该被读回来。
 * 函数列表为空只说明"这位用户还没有函数"，不是"这份存档作废"。
 *
 * 参数就是四个键"有没有值"（`SharedPreferences.contains` / 字符串非空白），
 * 抽成纯函数是为了让这条规矩能离线钉住 —— `Store.kt` 里 `import android.content.Context`，
 * 编不进 `tools\run-tool-tests.ps1` 那一套，而这条规矩恰恰是那一轮 bug 的正中央。
 */
fun archiveHasContent(
    hasFunctions: Boolean,
    hasSettings: Boolean,
    hasView: Boolean,
    hasSliders: Boolean
): Boolean = hasFunctions || hasSettings || hasView || hasSliders

// ===================================================================================
// 七、「长按必须留在控件自己的矩形里」—— 出界判定的纯算术
//
// ## 用户要求（本轮，全局性）
//
// 原话："**还有把所有按钮的长按判定都改成只有在按钮的矩形框内才判定。**"
// 也就是：手指一旦滑出这个控件**自己的布局矩形**（哪怕只是飘出去），长按就**作废** ——
// 进度/线圈/闪光**当场复位**、抬手也不再触发那一次长按。
//
// ## 为什么这条判据要有名字、要能离线测
//
// 它被**四种**长按实现共用（`HoldToConfirm` 的手写手势、`SliderRow` 整行的手写长按、
// 四行里的 `combinedClickable(onLongClick)`、以及 `WatchIconButton` 的 `onLongClick`），
// 而"出界"的边界只要在某一处写歪一点（写死一个 dp、或者把容差放大一档），
// 表现就是"长按有时候算数、有时候不算"—— 真机上几乎没法判断是哪一处。
// 所以边界本体只有一个纯函数 [pressInsideRect]，调用方一律传**自己节点的实测尺寸**。
//
// ## 容差：**有，但极小**（用户："我倾向留一个很小的像素容差…绝不能大到让飘到隔壁键上还能算数"）
//
// 完全零容差是**不能**用的：长按要按住 500~900ms，手表上手指又粗，指腹在这段时间里
// 偏出边界一两个像素是**必然**的（尤其按压时指尖的接触点在滑动）—— 零容差会让
// "按住 ⚙ 不放"变成一件需要运气的事，而这条功能本身是"按住才生效"的。
//
// 所以留 [PRESS_INSIDE_SLACK_FRACTION] = **控件自己短边的 2%**，实算（d2 圆表）：
//
// | 控件 | 实测尺寸 | 容差 | 折合 px |
// |---|---|---|---|
// | 图标键（⚙ / `+` / 📁 / 🗑） | 41.9 × 29.1dp | 0.58dp | **≈1.2px** |
// | 函数行色点区（手势区） | 30.3 × 35.0dp | 0.61dp | **≈1.2px** |
// | 函数行文字区 | ≈157 × 35.0dp | 0.70dp | **≈1.4px** |
// | 滑动条行 / 对象行 | ≈121~210 × 35.0dp | 0.70dp | **≈1.4px** |
//
// 落在用户说的"1~3px"那一档，而且**永远够不到隔壁**：相邻控件的间距最小也有 4dp 以上
// （工具条两颗键之间 37.3dp、键盘按键之间 ≈4.2dp、色点区与文字区紧挨着 ——
// 所以**颜色点飘到文字区**这种情形同样会被判成出界 ✔）。
//
// 为什么是"占控件自己短边的比例"而不是一个固定 px：固定 px 在 density 1.0 的机型上
// 会变成"3dp"、在 d3 上又变成"1dp"，同一个控件在不同表上严格程度差三倍；
// 而"短边的 2%"在任何 density、任何屏形上都折合**同样的物理长度**。
// ===================================================================================

/**
 * 长按"出界"判定的**容差**（占控件自己**短边**的比例）。
 *
 * 0.02 → 见上面那张表（圆表上折合 ≈1.2~1.4px）。改大它之前请先想清楚
 * "会不会让手指飘到隔壁键上还算数" —— 那是用户明确禁止的。
 */
const val PRESS_INSIDE_SLACK_FRACTION = 0.02f

/**
 * 手指（控件**本地坐标**）还在这个控件自己的矩形里吗 —— 长按成立的前提。
 *
 * 判据就是"点在 `[0, 宽] × [0, 高]` 里（含一个 [PRESS_INSIDE_SLACK_FRACTION] 的小容差）"。
 *
 * 非法 / 退化输入一律返回 **true**（= "还算在里"）：**宁可让长按照常成立，
 * 也不要把用户的长按吞掉** —— 尺寸还没量出来（0×0）的那一帧就是这样，
 * 判定只是"别误取消"，而真正的边界永远由实测尺寸给。
 * （这一条和 [sliderPressOnPlayKey] 的取舍方向一致：那边坏坐标也返回"长按能用"。）
 *
 * @param xPx / [yPx] 手指位置（控件本地坐标，左沿/上沿 = 0）。
 * @param widthPx / [heightPx] 这个控件**自己的实测尺寸**（`PointerInputScope.size`）。
 */
fun pressInsideRect(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean {
    if (!xPx.isFinite() || !yPx.isFinite()) return true
    if (!widthPx.isFinite() || !heightPx.isFinite()) return true
    if (widthPx <= 0f || heightPx <= 0f) return true
    val slack = minOf(widthPx, heightPx) * PRESS_INSIDE_SLACK_FRACTION
    return xPx >= -slack && xPx <= widthPx + slack && yPx >= -slack && yPx <= heightPx + slack
}
