package com.functiondrawer.watch.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/**
 * 计算精度：保留几位小数。
 *
 * 三个档位同时决定三件事（用户要求"让缩放上限、坐标轴数字、数值显示都跟着精度走"）：
 *  * [epsilon]：绝对值比它小的数一律按 0 处理（采样、显示都是）；
 *  * [minUnitsPerPixel]：缩放的**上限**（一个像素最少代表多少世界单位），
 *    3 位 → 1e-3、8 位 → 1e-8、16 位 → 1e-16；到了这里就不再继续放大；
 *  * [decimals]：数值显示保留的小数位数（见 [Calc.format]）。
 */
enum class CalcPrecision(val decimals: Int, val label: String) {
    P3(3, "保留 3 位小数"),
    P8(8, "保留 8 位小数"),
    P16(16, "保留 16 位小数");

    /** 小于它的数视为 0（3 位 → 1e-3，8 位 → 1e-8，16 位 → 1e-16）。 */
    val epsilon: Double get() = Math.pow(10.0, -decimals.toDouble())

    /** 当前精度允许的最小 [Viewport.unitsPerPixel]（= 最大放大倍数）。 */
    val minUnitsPerPixel: Double get() = epsilon
}

/**
 * 精度相关的数值工具：snap（按精度归零）+ 数字格式化。
 *
 * [precision] 是**进程级**的当前精度，由 `GraphScreen` 在每次重组时同步
 * （`SideEffect { Calc.precision = settings.precision }`）。采样在后台线程读它，
 * 所以标了 `@Volatile`。
 */
object Calc {

    /** 默认 8 位（老存档没有"精度"这一段时也是 8 位）。 */
    @Volatile
    var precision: CalcPrecision = CalcPrecision.P8

    /** 绝对值小于当前精度 → 0.0，其余原样返回。 */
    fun snap(v: Double): Double =
        if (v.isFinite() && abs(v) < precision.epsilon) 0.0 else v

    /**
     * 显示一个数值。
     *
     *  * NaN → "—"；±Inf → "∞" / "−∞"；
     *  * 绝对值小于当前精度 → "0"（例：3 位精度下 5.0E-5 → 0）；
     *  * 太大 / 太小（科学计数区间）仍用 `%.1e`；
     *  * 其余用 BigDecimal 按十进制四舍五入 —— 所以 16 位下也不会出现
     *    `0.2000000000000001` 这种浮点尾巴。
     */
    fun format(v: Double, decimals: Int = precision.decimals): String {
        if (v.isNaN()) return "—"
        if (!v.isFinite()) return if (v > 0) "∞" else "−∞"
        val a = abs(v)
        if (a == 0.0 || a < epsilonOf(decimals)) return "0"
        if (a >= HUMAN_MAX || a < HUMAN_MIN) {
            return String.format(Locale.US, "%.1e", v)
        }
        return formatDecimal(BigDecimal.valueOf(v), decimals)
    }

    /**
     * 刻度标签：**整数下标 × 步长**的精确十进制结果。
     *
     * 这里不走 `Double` 累加/相乘再 `toString()` —— 那样 16 位精度下会把
     * 0.1+0.2 的尾巴 `0.30000000000000004` 印到坐标轴上。
     * `BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(index))` 得到的是
     * 十进制精确积，最后再按 [decimals] 位四舍五入。
     */
    fun formatTick(index: Long, step: Double, decimals: Int = precision.decimals): String {
        if (!step.isFinite()) return format(index.toDouble() * step, decimals)
        val value = BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(index))
        return formatDecimal(value, decimals)
    }

    /** 精度对应的 0 阈值（按十进制算）。 */
    private fun epsilonOf(decimals: Int): Double = Math.pow(10.0, -decimals.toDouble())

    /** 大到 / 小到这个范围就改用科学计数（和改动前的判据一致）。 */
    private val HUMAN_MAX: Double = 1e5
    private val HUMAN_MIN: Double = 1e-4

    /** 同一个显示规则的 BigDecimal 版本（[formatTick] 必须用它，不能先转 Double）。 */
    private fun formatDecimal(value: BigDecimal, decimals: Int): String {
        if (value.signum() == 0) return "0"
        val a = value.abs()
        if (a < BigDecimal.valueOf(epsilonOf(decimals))) return "0"
        if (a >= BigDecimal.valueOf(HUMAN_MAX) || a < BigDecimal.valueOf(HUMAN_MIN)) {
            // 科学计数只保留一位小数，这里转 Double 不会带出可见的浮点尾巴
            return String.format(Locale.US, "%.1e", value.toDouble())
        }
        return value.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
