package com.functiondrawer.watch

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.functiondrawer.watch.ui.AppPrefs
import com.functiondrawer.watch.ui.AppRoot
import com.functiondrawer.watch.ui.CrownMode
import com.functiondrawer.watch.ui.Store
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 唯一一个 Activity。
 *
 * 它承担三件和「原屏手表」强相关的事：
 *
 * 1. **沉浸式全屏**：很多原屏表仍然带状态栏/导航栏，会白吃掉本就不多的像素。
 *
 * 2. **屏蔽系统手势**：`decorView` 侧由 Compose 里的设置项负责，
 *    但"从屏幕边缘上滑"这类系统手势的拦截必须在窗口层做，见 [applyGestureBlock]。
 *
 * 3. **表冠**：不同厂商的表把转动事件放在**完全不同的**通道里 ——
 *    这正是「表冠适配」这一项设置存在的理由，见 [crownMode]。
 *
 * ## 两种表冠模式（用户在「更多 → 表冠适配」里切）
 *
 * * [CrownMode.OPPO]（默认）：实测这块表（OPPO OWW251）的表冠设备是
 *   `/dev/input/event3`，名字 `oplus_crown`，能力里**只有** `REL (0002): REL_WHEEL` ——
 *   它既不是标准 Wear 的旋转编码器（那种走 `REL_DIAL` → `AXIS_SCROLL`），
 *   也不带鼠标的按键位，所以 Android 给它的 source 位几乎肯定是空/非标准。
 *   因此这条路上**刻意不做任何 action / source 过滤**：只要任何一个滚轮轴非零，
 *   就当表冠处理。之前按 `action == ACTION_SCROLL` + `isFromSource(...)` 过滤过，
 *   结果表冠完全没反应 —— 那块判断就是问题所在。
 *
 * * [CrownMode.HUAWEI_SAMSUNG]：华为 / 三星那套是**标准**旋转编码器，
 *   事件规规矩矩地挂在 `SOURCE_ROTARY_ENCODER` 上、`action` 是 `ACTION_SCROLL`，
 *   但原始值是 `±1`（一格一下），直接当滚动量用会慢到几乎不动 ——
 *   所以要放大 [ROTARY_RAW_SCALE] 倍才接近 OPPO 那条路的手感。
 *   反过来说，这条路**必须**过滤：编码器之外的活动（比如某些表把触屏也报成 generic motion）
 *   不该被当成表冠。
 *
 * 两条路算出的量最后都乘上灵敏度 `crownSpeed`（0.1×～10×，见设置页的滑轨），
 * 所以灵敏度对两种模式同时有效。
 *
 * ## 入口位置
 *
 * 统一放在 [dispatchGenericMotionEvent]（比 `onGenericMotionEvent` 更早，
 * 还没经过视图树分发），这样即使内容视图把事件吃掉也拦得住。
 */
class MainActivity : ComponentActivity() {

    private val rotaryEvents = MutableSharedFlow<Float>(extraBufferCapacity = 32)

    /**
     * 当前表冠模式与灵敏度。
     *
     * 取值时机：[onCreate] 读一次、**[onResume] 再读一次**。
     * 后者是关键 —— 用户从「更多 → 表冠适配」退回来（Activity 没重建、只是 resume），
     * 设置页写盘后必须靠这一下让新手感当场生效，否则要杀进程重进才认。
     * 读取成本很小（只解设置那一行），而且不在事件热路径上。
     */
    private var crownMode: CrownMode = CrownMode.OPPO
    private var crownSpeed: Float = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏。**这里一行都不能换成平台 API**：
        // 直接调 `Window.setDecorFitsSystemWindows` 是 API 30 起才有的，
        // 而本 App 的系统下限是 Android 7.0（API 24）。
        // 现在用的 `WindowCompat` / `WindowInsetsControllerCompat` 是 AndroidX 的兼容封装：
        //   * API 30+ → 走平台那套（`setDecorFitsSystemWindows` + `InsetsController`）；
        //   * API 30 以下（含 7.0）→ 自动退化成老的 `View.setSystemUiVisibility` +
        //     `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|LAYOUT_HIDE_NAVIGATION|FULLSCREEN|HIDE_NAVIGATION`
        //     那一套（API 16 起就有），观感一致、不需要我们判版本。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 看函数图像时屏幕不该灭
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        reloadCrownPrefs()

        // 应用级偏好（屏幕形状 / 开发者模式 / 是否看过欢迎页）必须在 setContent **之前**读，
        // 否则首帧会先按默认形状排一遍，再跳到用户选过的形状。
        AppPrefs.load(this)

        // 上次是不是崩过？崩过就先把栈**画在屏幕上**，不进门禁式的主界面。
        //
        // 为什么不是弹个 toast：手表屏小、toast 两秒就没了，用户还没来得及看
        // 就消失了，更别说截图。整屏显示 + 可滚动，才能真的把信息带出来。
        // 点一下屏幕就清掉记录、正常进入 App。
        val lastCrash = CrashLog.readLast(this)
        if (lastCrash != null) {
            setContent {
                CrashScreen(
                    report = lastCrash,
                    onDismiss = {
                        CrashLog.clear(this)
                        setContent { AppRoot(rotary = rotaryEvents, onCrownPrefs = ::applyCrownPrefs) }
                    }
                )
            }
            return
        }

        setContent {
            AppRoot(rotary = rotaryEvents, onCrownPrefs = ::applyCrownPrefs)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页退回来时 Activity 只走 resume，不重建 —— 必须在这里重新取，
        // 否则「表冠适配」「灵敏度」改了要重进 App 才生效。
        reloadCrownPrefs()
    }

    private fun reloadCrownPrefs() {
        val (mode, speed) = Store.loadCrownPrefs(this)
        crownMode = mode
        crownSpeed = speed
    }

    /**
     * 设置页里改完表冠模式 / 灵敏度**当场**顶进来（用户原话："调整表冠模式和表冠
     * 灵敏度不会即时生效，还要重启软件才能生效"）。
     *
     * [onResume] 那条路走不到：设置页是同一个 Activity 里的整屏路由，退出设置页
     * 不会让 Activity 重新 resume；而存档有 700ms 防抖，等落盘再读也更晚。
     * 所以由设置页直接回调这两个内存字段（见 `GraphScreen.applySettingsChange`）。
     */
    private fun applyCrownPrefs(mode: CrownMode, speed: Float) {
        crownMode = mode
        crownSpeed = speed
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val amount = scrollAmount(event)
        if (amount != 0f) {
            Log.d(
                TAG,
                "表冠: mode=$crownMode action=${event.action} " +
                    "source=0x${event.source.toString(16)} speed=$crownSpeed -> $amount"
            )
            rotaryEvents.tryEmit(amount)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * 从通用运动事件里抽出滚动量，按 [crownMode] 分两条路。
     *
     * 返回值已乘过灵敏度；为 0 表示"这不是表冠事件，交还给系统"。
     */
    private fun scrollAmount(event: MotionEvent): Float {
        val raw = when (crownMode) {
            CrownMode.OPPO -> oppoRaw(event)
            CrownMode.HUAWEI_SAMSUNG -> huaweiSamsungRaw(event)
        }
        if (raw == 0f) return 0f
        val scaled = raw * crownSpeed
        return if (scaled.isFinite()) scaled else 0f
    }

    /**
     * OPPO：逐个滚轮轴试，取第一个非零值，不做 action / source 判断。
     *
     * 理由见类注释：这块表的表冠 source 位不可靠，任何过滤都可能把它挡掉。
     */
    private fun oppoRaw(event: MotionEvent): Float {
        for (axis in SCROLL_AXES) {
            val v = event.getAxisValue(axis)
            if (v != 0f && v.isFinite()) return v
        }
        return 0f
    }

    /**
     * 华为 / 三星：标准旋转编码器。
     *
     * 判据三件套一个都不能少：
     *   * `action == ACTION_SCROLL` —— 编码器转动只会报这个；
     *   * `isFromSource(SOURCE_ROTARY_ENCODER)` —— 把触屏/鼠标的滚动挡在外面；
     *   * 轴值取 [AXIS_SCROLL]（API 26+）或 `AXIS_VSCROLL` 兜底（老设备上编码器
     *     有时候报 VSCROLL）。
     *
     * 原始值是 `±1` 一格，放大 [ROTARY_RAW_SCALE] 倍后才接近 OPPO 那条路一格的手感。
     */
    private fun huaweiSamsungRaw(event: MotionEvent): Float {
        if (event.action != MotionEvent.ACTION_SCROLL) return 0f
        if (!event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return 0f

        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (scroll != 0f) scroll else event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        } else {
            event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        }
        if (v == 0f || !v.isFinite()) return 0f
        return v * ROTARY_RAW_SCALE
    }

    private companion object {
        const val TAG = "FunctionDrawer"

        /**
         * 旋转编码器原始值的放大倍数。
         *
         * 编码器一格 = `1.0`，而 OPPO 那边 `REL_WHEEL` 一格通常是十几到几十。
         * 取 15 让两边"转一格滚多少"差不多 —— 之后再用灵敏度细调，
         * 所以这个数只要落在同一个数量级就够，不需要很准。
         */
        const val ROTARY_RAW_SCALE = 15f

        /**
         * OPPO 模式逐个试的滚轮轴。
         *
         * **版本守卫**：`AXIS_SCROLL` 是 **API 26（Android 8.0）** 才加的常量
         * （见本机 `platforms/android-34/data/api-versions.xml` 的 `since="26"`）。
         * 它是 `static final int`，编译期会被**内联**成字面量，所以低版本上不会
         * `NoSuchFieldError`；但那个值在 24/25 上没有任何含义（框架不认这个轴），
         * 拿它去 `getAxisValue` 只会白读一次。所以 26 以下干脆不列它 ——
         * 另外三个轴（VSCROLL / HSCROLL / WHEEL，都是 API 12 起）在 7.0 上照旧可用，
         * 表冠该走哪条路还是哪条路。
         */
        val SCROLL_AXES: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intArrayOf(
                MotionEvent.AXIS_SCROLL,
                MotionEvent.AXIS_VSCROLL,
                MotionEvent.AXIS_HSCROLL,
                MotionEvent.AXIS_WHEEL
            )
        } else {
            intArrayOf(
                MotionEvent.AXIS_VSCROLL,
                MotionEvent.AXIS_HSCROLL,
                MotionEvent.AXIS_WHEEL
            )
        }
    }
}
