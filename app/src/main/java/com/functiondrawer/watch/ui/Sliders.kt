package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.ParameterSet

/**
 * 一条**滑动条**（用户需求 #3 / #4）。
 *
 * 它绑定的不是表达式，而是引擎里的一个**可绑定参数**（`engine/Parameters.kt` 的
 * a / b / c / d）。用户如果在函数表达式里写了一个"什么也不代表的小写字母"
 * （既不是函数名、也不是采样变量 x/t/θ），就说他要的是参数 ——
 * 于是一个 `-5~5`、步长 `0.1` 的滑动条自动生成出来，
 * 拖动它就能实时改变曲线形状（`a*x+b` 这类式子立刻跟着变）。
 *
 * 为什么名字就是参数字母（小写 `a`）而不是 `a(x)`：
 * 用户要求"滑动条强制使用小写"，而列表里函数是 `a(x)`、滑动条是 `a`，
 * **靠有没有括号区分**——这也正是用户给的判定规则（#5）。
 *
 * [value] 是当前值；[playing] 为 true 时，图像界面会用动画让它来回滑动
 * （用户原话："从滑动条当前位置以较缓和的速度递增滑动条的值，
 * 滑动条播放到上界之后返回下界播放"）。
 */
data class Slider(
    val id: Long,
    /** 参数字母（小写，`a`/`b`/`c`/`d`）。 */
    val param: String,
    val min: Double = DEFAULT_MIN,
    val max: Double = DEFAULT_MAX,
    val step: Double = DEFAULT_STEP,
    /** 当前值：初始取 0，正好在 `-5~5` 的正中。 */
    val value: Double = 0.0,
    val colorIndex: Int = 0,
    val visible: Boolean = true,
    /** 是否正在播放（#3 的播放键）。 */
    val playing: Boolean = false
) {
    /** 列表里显示的名字：就是参数字母本身（小写）。 */
    val name: String get() = param

    /** 按步长夹紧到 `[min, max]`，并吸附到步长网格上。 */
    fun snap(v: Double): Double {
        if (step <= 0.0) return v.coerceIn(min, max)
        val steps = Math.round((v - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    /**
     * 播放时每一步的增量（带方向）。
     *
     * [direction] = +1 向上界走、-1 向下界走。走到头就反向 ——
     * 用户要求"播放到上界之后返回下界播放"，所以是**往返**而不是重置。
     * 返回值已经按步长吸附；**到头了返回 null**，调用方据此换方向。
     *
     * ⚠️ 顺序很关键：**先判越界、再吸附**。
     * 真机 bug（"播放只能播放一次，不能自动重播，之后点播放键没反应"）就是这里写反了：
     * 原来先 `snap(value + direction * step)` —— 而 `snap` 会把超出的值**夹回** `[min, max]`，
     * 于是值到 5.0 之后 `snap(5.1)` 又变成 5.0：既没超过 max（`next > max` 不成立 → 不返回 null
     * → 永远不换方向），值也不再变化（卡在顶上）。表现就是"播到顶就死了"，
     * 而 `playing` 仍然是 true，所以再点播放键只把它切到 false、屏幕上什么都没发生。
     *
     * 现在改成：先算未吸附的 `raw`，用一个小容差判它有没有真的越界；越界 → null（换方向），
     * 否则才吸附返回。这样到顶/到底各换一次方向，能一直往返。
     */
    fun advance(direction: Int): Double? {
        if (step <= 0.0) return null
        val raw = value + direction * step
        if (raw > max + STEP_EPS || raw < min - STEP_EPS) return null
        return snap(raw)
    }

    companion object {
        const val DEFAULT_MIN = -5.0
        const val DEFAULT_MAX = 5.0
        const val DEFAULT_STEP = 0.1

        /** 判越界用的容差：浮点加法（0.1 这种）在边界上会有微小误差，不给容差会提前/延后一格。 */
        private const val STEP_EPS = 1e-9
    }
}

/** 把当前所有可见滑动条打包成引擎要的参数集合。 */
fun List<Slider>.toParameterSet(): ParameterSet {
    if (isEmpty()) return ParameterSet.DEFAULT
    val visibleSliders = filter { it.visible }
    if (visibleSliders.isEmpty()) return ParameterSet.DEFAULT
    return ParameterSet.of(*visibleSliders.map { it.param to it.value }.toTypedArray())
}
