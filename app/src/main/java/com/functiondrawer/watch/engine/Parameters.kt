package com.functiondrawer.watch.engine

/**
 * 四个「可绑定参数」`a b c d` 的取值集合。
 *
 * 背景：手表键盘的「字母」页要加 `a b c d` 四个键，用户说"预留四个变量，
 * 等下讲干什么"。所以这一轮只保证它们**能被解析、能算出一个数**，
 * 具体用途（大概率是可调滑块 / 参数探索）等用户说明之后再定。
 *
 * 为什么做成单独一个类型、而不是把它们塞进 [PlotMode.variables]：
 *
 *  * `variables` 里的名字是**采样输入**。它的顺序直接决定 `Expression.eval(...)`
 *    的实参顺序，采样器还按它的长度分配参数数组
 *    （极坐标下 `θ` 和 `t` 共用同一个位置，见 [Plotter]）。
 *  * `a b c d` 是「这条曲线的一个参数在编译期取什么值」，跟采样位置毫无关系。
 *
 * 两者混在一个列表里，`a` 就会被当成第三个采样输入，采样器的参数数组、
 * UI 的输入框语义全都要跟着改；分开之后它们在建树阶段就折成 **常量**，
 * 于是 `Expression.eval` 的签名、求值热路径、UI 全都一行不用动。
 *
 * 取值来源**已经接好了**：`GraphState.sliders` 里每个可绑定参数各有一条滑动条，
 * 拖动时实时重建 [ParameterSet] 再重采样曲线（见 `GraphState.parameters()`）。
 * 没被任何滑动条覆盖的参数取 [DEFAULT_VALUE] = 1.0（乘法与加法的单位元），
 * 这样用户写下的 `a*x+b` 在没调过滑块时也能直接画成 `x+1`，而不是一写就报错。
 */
class ParameterSet(values: Map<String, Double> = emptyMap()) {

    /**
     * 存一份拷贝：`Expression` 是不可变、可被后台线程并发求值的，
     * 不能让调用方事后改掉传进来的那个 map，把已编译表达式的含义偷换掉。
     */
    private val values: Map<String, Double> = values.toMap()

    init {
        // 名字写错是**调用方**的 bug（比如 UI 传了 "2x"），要立刻炸；
        // 这里绝不能悄悄忽略，否则界面上会出现"拖了滑块但图没变"的幽灵 bug。
        //
        // 1.2.69 起接受**任何小写标识符**（`s`、`r1`、`opt`、`rv`…）：用户要求
        // "输入了不认识的字母就创建滑动条"，名字不再限制成 a~d 或单个字母。
        // 形状照抄引擎词法层的标识符：小写字母开头，后面可以跟字母/数字/下划线。
        // 大写、空名、数字开头仍然立刻报错（大写留给几何点名，数字开头是字面量）。
        val bad = this.values.keys.filterNot { isParameterName(it) }.sorted()
        require(bad.isEmpty()) {
            "不认识的参数名：${bad.joinToString("、")}；" +
                "参数名必须是小写字母开头的标识符（如 a、s、r1、opt）"
        }
    }

    /**
     * 取某个名字的绑定值。
     *
     *   * **显式给过值**（`values` 里有它）→ 那个值，任意合法小写标识符都行
     *     （滑动条可以叫 `f`、`z`、`r1`、`opt`…）；
     *   * 没给值、但是 `a`~`d` → [DEFAULT_VALUE]（保住 `a*x+b` 没有滑动条时也能画）；
     *   * 其它没给值的名字 → **null**，让 Parser 继续走"未知名字"那条报错路径
     *     （不会凭空把 `f` 当成 1.0 把曲线画错）。
     */
    fun value(name: String): Double? = when {
        name in values -> values[name]
        name in BINDABLE -> DEFAULT_VALUE
        else -> null
    }

    /** 显式给过值的那些参数（没给的不在这里）。以后做滑块时 UI 可以读它。 */
    val explicit: Map<String, Double> get() = values

    override fun toString(): String =
        BINDABLE.joinToString(separator = ", ", prefix = "ParameterSet(", postfix = ")") {
            "$it=${value(it)}"
        }

    companion object {

        /** 保留的四个可绑定参数。四种绘图模式下都可用。 */
        val BINDABLE: List<String> = listOf("a", "b", "c", "d")

        /** 临时占位默认值：还没有任何界面能改参数值。 */
        const val DEFAULT_VALUE: Double = 1.0

        /** 四个参数全取默认值 —— `compile` / `plot` 的默认实参。 */
        val DEFAULT: ParameterSet = ParameterSet()

        /** 写法糖：`ParameterSet.of("a" to 2.0, "b" to -3.0)`。 */
        fun of(vararg bindings: Pair<String, Double>): ParameterSet =
            ParameterSet(bindings.toMap())

        /**
         * 一个名字能不能当**可绑定参数**（= 能不能自动长出一条滑动条）。
         *
         * 形状照抄引擎词法层的标识符：**小写字母开头**，后面可以跟小写字母 / 数字 / 下划线。
         * 这样 `s`、`r1`、`r2`、`opt`、`rv` 都合法，而
         *   * 大写（`A`、`Alpha`）留给几何点名，不是参数；
         *   * 数字开头（`2a`）是字面量，不是名字；
         *   * 空名没有任何意义。
         *
         * **注意它只管"词形"**：常量（`pi`/`e`…）、内置函数名（`sin`…）、
         * 采样变量（`x`/`y`/`t`/`θ`/`z`）虽然词形也合法，但它们不该长出滑动条 ——
         * 那一层过滤在 UI 侧的 `bindableParametersIn` 里做（那里能拿到绘图模式的变量表）。
         */
        fun isParameterName(name: String): Boolean {
            if (name.isEmpty()) return false
            if (name[0] !in 'a'..'z') return false
            return name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }
    }
}
