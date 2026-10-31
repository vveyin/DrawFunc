package com.functiondrawer.watch.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 用户选择的屏幕形状。 */
internal enum class ScreenShape(val label: String) {
    ROUND("圆屏"),
    SQUARE("方屏");

    /** 下一个形状（设置条点一下就切一格，和「表冠模式」那条同一个交互）。 */
    fun next(): ScreenShape = entries[(ordinal + 1) % entries.size]
}

/**
 * 应用级偏好（**不属于某个函数存档**）。
 *
 * 和 [Store] 分开是刻意的：这里的三件事描述的是"这台设备上要怎么用这个 App"
 * （屏幕形状、开发者模式、是否看过欢迎页），和函数列表 / 设置那份存档没有关系。
 * 单独一个 SharedPreferences 文件，换存档、清函数都不会把它们带走。
 *
 * 值用 `mutableStateOf` 暴露，界面直接读就会在改动时自动重组
 * （[isRoundScreen] 就是靠这一点让全 App 的布局跟着形状变）。
 */
internal object AppPrefs {

    private const val PREF = "function_drawer_app"
    private const val KEY_SHAPE = "screen_shape"
    private const val KEY_DEVELOPER = "developer_mode"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_SLIDER_HINT = "slider_play_hint"
    private const val KEY_POINT_MOVE_HINT = "point_move_hint"

    private const val SHAPE_ROUND = "round"
    private const val SHAPE_SQUARE = "square"

    /** 屏幕形状。**设备默认值**在 [load] 里取（`Configuration.isScreenRound`）。 */
    var screenShape by mutableStateOf(ScreenShape.ROUND)
        private set

    /** 开发者模式：只在「应用信息」页连点版本号 7 下之后打开。 */
    var developerMode by mutableStateOf(false)
        private set

    /** 是否已经看过首次启动的欢迎流程。 */
    var onboarded by mutableStateOf(false)
        private set

    /**
     * 「滑动条第一次播放」的提示弹过没有。
     *
     * 用户要求："给第一次播放提示条做个弹窗提示，提示要回到函数图像界面才开始播放"。
     * 这是**装一次只弹一次**的提示（不是每进一次界面弹一次），所以和欢迎页一样
     * 落在本文件的 SharedPreferences 里，而不是函数存档。
     */
    var sliderPlayHintShown by mutableStateOf(false)
        private set

    /**
     * 「描点工具怎么拖点」的提示弹过没有（用户在提示框里勾了「不再提示」）。
     *
     * 和 [sliderPlayHintShown] 同一条规矩：**装一次只弹一次**，
     * 存在 SharedPreferences 里而不是函数存档 —— 它说的是"这台设备上的使用习惯"。
     */
    var pointMoveHintShown by mutableStateOf(false)
        private set

    /** 一次性标记：欢迎页里选了「进入新手教程」时为 true，[consumePendingTutorial] 读一次就清掉。 */
    var pendingTutorial by mutableStateOf(false)
        private set

    /**
     * 读盘。必须在 `setContent` **之前**调用一次（见 `MainActivity.onCreate`）——
     * 首帧就要按用户选过的屏幕形状排版，晚一步会先闪一下默认形状。
     */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val deviceRound = context.resources.configuration.isScreenRound
        screenShape = when (prefs.getString(KEY_SHAPE, null)) {
            SHAPE_ROUND -> ScreenShape.ROUND
            SHAPE_SQUARE -> ScreenShape.SQUARE
            // 没存过 = 全新安装：跟设备实际形状走
            else -> if (deviceRound) ScreenShape.ROUND else ScreenShape.SQUARE
        }
        developerMode = prefs.getBoolean(KEY_DEVELOPER, false)
        onboarded = prefs.getBoolean(KEY_ONBOARDED, false)
        sliderPlayHintShown = prefs.getBoolean(KEY_SLIDER_HINT, false)
        pointMoveHintShown = prefs.getBoolean(KEY_POINT_MOVE_HINT, false)
    }

    fun setScreenShape(context: Context, shape: ScreenShape) {
        screenShape = shape
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_SHAPE, if (shape == ScreenShape.ROUND) SHAPE_ROUND else SHAPE_SQUARE)
            .apply()
    }

    fun setDeveloperMode(context: Context, on: Boolean) {
        developerMode = on
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEVELOPER, on)
            .apply()
    }

    fun setOnboarded(context: Context, v: Boolean) {
        onboarded = v
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDED, v)
            .apply()
    }

    /** 滑动条播放提示已经弹过（用户点了「知道了」）。 */
    fun setSliderPlayHintShown(context: Context) {
        if (sliderPlayHintShown) return
        sliderPlayHintShown = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SLIDER_HINT, true)
            .apply()
    }

    /** 描点拖动的提示已经弹过（用户在框里勾了「不再提示」）。 */
    fun setPointMoveHintShown(context: Context) {
        if (pointMoveHintShown) return
        pointMoveHintShown = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_POINT_MOVE_HINT, true)
            .apply()
    }

    /** 「回到初始界面」：下次组合就是欢迎页（`AppRoot` 读 [onboarded]）。 */
    fun requestOnboarding(context: Context) = setOnboarded(context, false)

    /** 欢迎页里选了「进入新手教程」：下一次进 [GraphScreen] 时自动起教程。 */
    fun requestTutorial() {
        pendingTutorial = true
    }

    /** 取走一次性标记（取完即清）。 */
    fun consumePendingTutorial(): Boolean {
        val v = pendingTutorial
        pendingTutorial = false
        return v
    }
}

/**
 * 当前该按圆屏排版吗 —— 全 App 判断屏幕形状的唯一入口。
 *
 * 读的是 [AppPrefs.screenShape]，所以在「手表适配」里改形状之后，
 * 所有用到它的界面会当场重组（不再直接读 `LocalConfiguration.isScreenRound`）。
 */
@Composable
internal fun isRoundScreen(): Boolean = AppPrefs.screenShape == ScreenShape.ROUND
