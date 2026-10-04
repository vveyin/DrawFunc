package com.functiondrawer.watch

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.functiondrawer.watch.ui.GraphScreen
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 唯一一个 Activity。
 *
 * 它承担两件和「原屏手表」强相关的事：
 *
 * 1. **沉浸式全屏**：很多原屏表仍然带状态栏/导航栏，会白吃掉本就不多的像素。
 *
 * 2. **表冠**：实测这块表（OPPO OWW251）的表冠设备是 `/dev/input/event3`，
 *    名字 `oplus_crown`，能力里**只有** `REL (0002): REL_WHEEL` ——
 *    它既不是标准 Wear 的旋转编码器（那种走 `REL_DIAL` → `AXIS_SCROLL`），
 *    也不带鼠标的按键位，所以 Android 给它的 source 位几乎肯定是空/非标准。
 *
 *    因此这里**刻意不做任何 action / source 过滤**：
 *    只要事件里任何一个滚轮轴非零，就当表冠处理。
 *    之前按 `action == ACTION_SCROLL` + `isFromSource(SOURCE_ROTARY_ENCODER)`
 *    过滤过一次，结果表冠完全没反应 —— 那块判断就是问题所在。
 *
 *    入口放在 [dispatchGenericMotionEvent]（比 `onGenericMotionEvent` 更早，
 *    还没经过视图树分发），这样即使内容视图把事件吃掉也拦得住。
 */
class MainActivity : ComponentActivity() {

    private val rotaryEvents = MutableSharedFlow<Float>(extraBufferCapacity = 32)

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

        setContent {
            GraphScreen(rotary = rotaryEvents)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val amount = scrollAmount(event)
        if (amount != 0f) {
            Log.d(TAG, "表冠: action=${event.action} source=0x${event.source.toString(16)} -> $amount")
            rotaryEvents.tryEmit(amount)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * 从任意通用运动事件里抽出滚动量。
     *
     * 不做 action / source 判断，逐个滚轮轴试，取第一个非零值 ——
     * 理由见类注释：这块表的表冠 source 位不可靠，任何过滤都可能把它挡掉。
     */
    private fun scrollAmount(event: MotionEvent): Float {
        for (axis in SCROLL_AXES) {
            val v = event.getAxisValue(axis)
            if (v != 0f && v.isFinite()) return v
        }
        return 0f
    }

    private companion object {
        const val TAG = "FunctionDrawer"

        /**
         * 逐个试的滚轮轴。
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
