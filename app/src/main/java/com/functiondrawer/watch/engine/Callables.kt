package com.functiondrawer.watch.engine

/**
 * 一个「可以被别的表达式调用的用户函数」。
 *
 * 背景（用户报的真 bug）：
 * 函数编辑页面上，用户先建了 `a(x) = sin(x)`，又在 `b(x)` 里写 `2a(x)`，
 * 期望的是 `2sin(x)`，实际画出来的是 `y = 2x`。
 * 原因是引擎里 `a` 是**可绑定参数**（[ParameterSet]，缺省 1.0），
 * 于是 `2a(x)` 被解析成 `2 * a * x = 2 * 1 * x = 2x`。
 *
 * 这个类型就是为了让"用户定义过的函数"变成真的能被调用的东西：
 * 只要在编译时把当前这一批函数打包成 [CallableSet] 传进
 * [Expression.compile] / [Plotter.plot]，`a(x)` 就会去调用它，
 * 而**不带括号的 `a` 仍然老老实实是可绑定参数**（老行为一点不变）。
 *
 * ### 可调用的名字怎么取
 *
 * 取的是显示名（[PlotFunction.name]）里 `(` **之前**那一截：
 *
 *  * `a(x)`（自动命名规则产出的一律长这样，见 `GraphState.nextCurveName`） → 可调用名 `a`
 *  * `g`（用户没写括号）→ 可调用名 `g`，写 `g(x)` 也能调到
 *  * 空名字 / 名字里带奇怪字符 → 没有可调用名，这条函数调不到（[callableNameOf] 返回 null）
 *
 * 于是界面上"`a` 是参数、`a(x)` 是函数"这条用户自己定的规则（有没有括号）
 * 在引擎里成了同一件事：**带括号才是调用**。
 *
 * ### 只有 y=f(x) 能被调用
 *
 * 极坐标 `r(θ)`、描点 `(x(t),y(t))`、隐函数 `F(x,y)=0` 都不是"给 x 返回一个 y"的形状，
 * 所以它们的名字进表也**只能进表**：真被写成 `p(x)` 时会得到一条明确的中文报错
 * （见 [CallableFn.mode] 与 Parser），而不是悄悄算出一个错的数。
 *
 * ### 求值时用谁的设置
 *
 *  * **函数体**用它自己的 [degrees]（度制）和 [parameters] 编译 —— 用户在 `a(x)`
 *    上按过 DEG 键，那 `a` 就按度算，跟调用方是 RAD 还是 DEG 无关；
 *  * **实参**永远在**调用方**的帧里求值：`b(x)` 里写 `a(x+1)`，那个 `x` 是调用方的 x。
 *
 * @param name 可调用名（不含括号）。一般用 [callableNameOf] 从显示名推出来。
 * @param expression 函数体，也就是 `y=f(x)` 编辑框里 `y=` 右边那一串。
 *                   带 `y=` / `f(x)=` 前缀也能吃（[ExplicitSource] 会剥掉）。
 * @param degrees 这条函数自己的度制。
 *                **null = 跟随调用方**（内置默认下就是全局的弧度开关）。
 *                接入方最省事的做法是传 `fn.degrees ?: 全局默认值`，
 *                引擎这边留 null 兜底只是为了"忘了传也不会得出离谱结果"。
 * @param parameters 这条函数自己的可绑定参数取值。
 *                **null = 用调用方当前那张参数表**（滑动条的当前值）。
 * @param mode 这条函数自己的绘图模式。只有 [PlotMode.EXPLICIT] 是可调用的；
 *             别的模式会带着这个 mode 进 [CallableSet]，好在被调用时报一句明确的话。
 */
class CallableFn(
    val name: String,
    val expression: String,
    val degrees: Boolean? = null,
    val parameters: ParameterSet? = null,
    val mode: PlotMode = PlotMode.EXPLICIT
) {

    init {
        // 名字空是**调用方**的 bug（比如 UI 传进来一条还没命名的函数）：
        // 立刻炸，别让它变成一张"看不出为什么调不到"的表。
        require(name.isNotBlank()) { "可调用名不能是空的" }
    }

    /** 是不是"给 x 返回 y"的形状 —— 只有它为 true 才真的能 `a(x)`。 */
    val isCallable: Boolean get() = mode == PlotMode.EXPLICIT

    /** 报错信息里用的显示名，例如 `a(x)`。 */
    val displayName: String get() = "$name(x)"

    override fun toString(): String =
        "$displayName = $expression" + if (isCallable) "" else "（${mode.label}模式，不能被调用）"

    companion object {

        /**
         * 从显示名推出可调用名：`(` 之前那一截。
         *
         *  * `a(x)` → `a`；`aa(x)` → `aa`；带空格也认（`a (x)` → `a`）；
         *  * 没有括号时整个名字就是可调用名（`g` → `g`）；
         *  * 取不出一个像名字的东西（空串、`(x)`、`3(x)`、带 `+ - * /` 之类）时返回 null ——
         *    这种函数就是"调不到"，而不是勉强编一个名字出来。
         */
        fun callableNameOf(displayName: String): String? {
            val head = displayName.substringBefore('(').trim()
            if (head.isEmpty()) return null
            if (!head[0].isLetter() && head[0] != '_') return null
            if (head.any { !it.isLetterOrDigit() && it != '_' }) return null
            return head
        }
    }
}

/**
 * 一批「可以被调用的用户函数」。
 *
 * 做法刻意和 [ParameterSet] 对齐（见那边的注释）：只是编译/采样时传进来的一个**只读快照**，
 * 不动 [Expression.eval] 的签名，也不进采样变量数组，所以老的调用点一行都不用改
 * （末尾参数默认 [EMPTY]，行为与加这个功能之前逐位一致）。
 *
 * 名字重复时**先出现的赢**（确定性优先）。正常流程里撞不了名：
 * `GraphState.nextCurveName()` 只会发没被占用的名字；只有用户手工把两条函数改成同名才会撞。
 *
 * 看不到的函数（[PlotFunction.visible] = false）**照样在表里**：
 * 隐藏是"画不画"的事，不该让别的函数跟着报错。
 * 真正删掉一条函数时它才从表里消失，那时调用它的表达式会得到"不认识的名字"。
 */
class CallableSet(functions: List<CallableFn> = emptyList()) {

    /**
     * 存一份拷贝：和 [Expression] 一样，这张表可能被后台采样线程在读，
     * 不能让调用方事后改掉传进来的那个 list。
     */
    private val byName: Map<String, CallableFn>

    /** 去重后的列表（顺序 = 先到先得）。缓存下来，免得每次查都新建。 */
    private val list: List<CallableFn>

    init {
        val m = LinkedHashMap<String, CallableFn>(functions.size * 2)
        for (f in functions) m.putIfAbsent(f.name, f)
        byName = m
        list = m.values.toList()
    }

    val functions: List<CallableFn> get() = list

    val names: Set<String> get() = byName.keys

    val size: Int get() = byName.size

    fun isEmpty(): Boolean = byName.isEmpty()

    fun isNotEmpty(): Boolean = byName.isNotEmpty()

    /** 查一条可调用函数。名字不认识返回 null（Parser 继续走"未知名字"那条报错路径）。 */
    operator fun get(name: String): CallableFn? = byName[name]

    fun contains(name: String): Boolean = byName.containsKey(name)

    override fun toString(): String =
        if (list.isEmpty()) "CallableSet()" else "CallableSet(${list.joinToString("、")})"

    companion object {

        /** 空表 —— `Expression.compile` / `Plotter.plot` 的默认实参，等于"这个功能没启用"。 */
        val EMPTY: CallableSet = CallableSet()

        /** 写法糖：`CallableSet.of(CallableFn("a", "sin(x)"))`。 */
        fun of(vararg functions: CallableFn): CallableSet = CallableSet(functions.toList())

        fun ofList(functions: List<CallableFn>): CallableSet = CallableSet(functions)

        /**
         * 把界面上那串 [PlotFunction] 直接转成一张表 —— 接入方唯一需要调的东西。
         *
         * 做的三件事：
         *  1. 用 [CallableFn.callableNameOf] 取可调用名（`a(x)` → `a`），取不出来的跳过；
         *  2. 剥掉 `y=` / `f(x)=` 前缀（用户很可能在编辑框里就写着 `y = sin(x)`）；
         *  3. 把每条曲线**自己的**度制解析成具体值：`fn.degrees ?: degrees`
         *     （null 表示"跟随全局默认值"，就是 [Plotter.plot] 那个 degrees 参数）。
         *
         * 模式**原样带过去**：极坐标/描点/隐函数的名字也进表，
         * 这样用户写 `p(x)` 时能得到"不是 y=f(x) 的函数，不能被调用"，
         * 而不是一句含糊的"不认识的名字 'p'"。
         *
         * 参数表整张传下去（所有函数共用滑动条的当前值，和现在四种模式的做法一致）。
         *
         * @param degrees 全局默认度制，和 [Plotter.plot] 的 degrees 传同一个值。
         */
        fun ofFunctions(
            functions: List<PlotFunction>,
            degrees: Boolean = false,
            parameters: ParameterSet = ParameterSet.DEFAULT
        ): CallableSet {
            val out = ArrayList<CallableFn>(functions.size)
            for (fn in functions) {
                val callableName = CallableFn.callableNameOf(fn.name) ?: continue
                out += CallableFn(
                    name = callableName,
                    expression = ExplicitSource.strip(fn.src1),
                    degrees = fn.degrees ?: degrees,
                    parameters = parameters,
                    mode = fn.mode
                )
            }
            return CallableSet(out)
        }
    }
}

/**
 * 显函数源码的公共预处理：剥掉 `y=` / `f(x)=` 前缀。
 *
 * 抽出来是因为现在有两个地方要剥同一个前缀：
 *  * [Plotter] 采样 `y=f(x)` 曲线时（老逻辑）；
 *  * [CallableSet.ofFunctions] 把函数体装进 [CallableFn] 时。
 * 两处各写一遍正则迟早会漂移，所以只留一份。
 */
internal object ExplicitSource {

    /** `y = ...`、`f(x) = ...`（大小写不敏感，`=` 两边可以有空格）。 */
    private val PREFIX =
        Regex("^\\s*(y|f\\s*\\(\\s*x\\s*\\))\\s*=\\s*", RegexOption.IGNORE_CASE)

    fun strip(src: String): String = PREFIX.replace(src, "").trim()
}

/**
 * 整棵表达式树共享的**调用展开预算**。
 *
 * 环检查 + 深度上限能挡住"无限递归"，但挡不住宽度爆炸：
 * 若用户写了 `a₁ = a₂+a₂`、`a₂ = a₃+a₃`…… 这种"每个函数调用下一个两次"的链，
 * 展开出来的调用点是指数级的（20 个函数就是上百万个节点），
 * 编译期就能把表卡死 —— 手表上卡死和崩了一样难受。
 *
 * 所以每编译一个顶层表达式给一份预算（见 [Expression.MAX_USER_CALLS]），
 * 每展开一个调用点扣 1，扣完就报错。正常写法（`2a(x)+a(x+1)`）只会用掉两三个。
 */
internal class CallBudget(var remaining: Int)
