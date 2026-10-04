package com.functiondrawer.watch.engine

/**
 * 语法树节点。
 *
 * 做成 sealed class + 每次求值直接递归，而不是编译成闭包链：
 * 隐函数采样最坏情况也就几万次求值，JIT 完全吃得下，
 * 换来的是可读性和好调试。
 */
internal sealed class Node {
    abstract fun eval(a: DoubleArray): Double

    class Const(val value: Double) : Node() {
        override fun eval(a: DoubleArray): Double = value
    }

    class Var(val index: Int) : Node() {
        override fun eval(a: DoubleArray): Double = a[index]
    }

    class Neg(val child: Node) : Node() {
        override fun eval(a: DoubleArray): Double = -child.eval(a)
    }

    class Add(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = l.eval(a) + r.eval(a)
    }

    class Sub(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = l.eval(a) - r.eval(a)
    }

    class Mul(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = l.eval(a) * r.eval(a)
    }

    class Div(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = l.eval(a) / r.eval(a)
    }

    class Pow(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = Math.pow(l.eval(a), r.eval(a))
    }

    class Mod(val l: Node, val r: Node) : Node() {
        override fun eval(a: DoubleArray): Double = MathFns.mathMod(l.eval(a), r.eval(a))
    }

    class Fact(val child: Node) : Node() {
        override fun eval(a: DoubleArray): Double = MathFns.factorial(child.eval(a))
    }

    class Call(val name: String, val args: List<Node>) : Node() {
        override fun eval(a: DoubleArray): Double {
            // 注意：Expression 是只读的，可能被后台线程并发求值，
            // 所以这里不能用共享缓冲，每次新建（靠 JIT 逃逸分析优化掉）。
            if (args.size == 1) {
                // 一元函数是绝对大头，单独开一条不分配数组的路径
                return MathFns.call1(name, args[0].eval(a))
            }
            val b = DoubleArray(args.size)
            for (k in args.indices) b[k] = args[k].eval(a)
            return MathFns.call(name, b)
        }
    }

    /**
     * 调用一个**用户定义**的函数：`a(x)`、`2a(x)`、`a(x+1)`、`a(a(x))`。
     *
     * 和内置函数的 [Call] 分开是刻意的：
     *  * [Call] 只带一个名字，求值时去 [MathFns] 里查；
     *  * 用户函数的**函数体**早在建树时就用它自己的 degrees / 参数表编译好了
     *    （见 [Parser.parseUserCall]），求值时只剩"算实参 → 喂进去"，热路径上不做任何名字查找。
     *  * 实参 [arg] 永远在**调用方**的帧里求值：`b(x)` 里写 `a(x+1)`，那个 x 是调用方的 x。
     *
     * [depth] 是"这是第几层用户函数调用"（顶层表达式里的调用 = 1），
     * 它是**兜底**用的：环在编译期就被查掉了，正常路径上永远超不过上限
     * （见 [Expression.MAX_USER_CALL_DEPTH]）。留着是为了手表上绝不允许
     * 出现一次 `StackOverflowError` —— 那等于崩溃，而不是一条能读的报错。
     */
    class UserCall(
        val name: String,
        val callee: Expression,
        val arg: Node,
        val depth: Int
    ) : Node() {
        override fun eval(a: DoubleArray): Double {
            if (depth > Expression.MAX_USER_CALL_DEPTH) {
                throw ExpressionException(
                    "函数调用嵌套太深（超过 ${Expression.MAX_USER_CALL_DEPTH} 层），无法求值",
                    0
                )
            }
            // 和 [Call] 一样不能共享缓冲：Expression 可能被后台线程并发求值。
            return callee.evalArray(doubleArrayOf(arg.eval(a)))
        }
    }
}

/**
 * 编译好的表达式。不可变、可复用、可跨线程调用。
 *
 * 用法：
 * ```
 * val f = Expression.compile("x^2 + 1", listOf("x"))
 * f.eval(3.0)                       // 10.0
 *
 * // a b c d 是可绑定参数，不传就用临时占位的 1.0
 * val g = Expression.compile("a*x+b", listOf("x"), parameters = ParameterSet.of("a" to 2.0))
 * g.eval(3.0)                       // 2*3 + 1 = 7.0
 *
 * // 调用**用户定义过**的函数：b(x) = 2a(x)，其中 a(x) = sin(x)
 * val callables = CallableSet.of(CallableFn("a", "sin(x)"))
 * val b = Expression.compile("2a(x)", listOf("x"), callables = callables)
 * b.eval(3.0)                       // 2*sin(3)，而不是 2*1*3
 * ```
 */
class Expression internal constructor(
    private val root: Node,
    /** 变量名，顺序即 [eval] 参数顺序 */
    val variables: List<String>,
    /**
     * 编译时用的是不是角度制（DEG）。
     *
     * 只是给 UI / 测试查询用的记录；[eval] 完全不再看它 ——
     * 换算早在建树时就写进 AST 里了，所以同一个 Expression
     * 无论在哪个线程、什么时候求值，结果都一样。
     */
    val degrees: Boolean = false,
    /**
     * 编译时 `a b c d` 用的那一组取值。
     *
     * 和 [degrees] 一样只是**记录**：取值早在建树时就折成常量了，[eval] 不再看它。
     * 留着是为了让 UI / 测试能查"这条曲线是按哪组参数编出来的"，
     * 以后接参数滑块时也能一眼看出当前绑定。
     */
    val parameters: ParameterSet = ParameterSet.DEFAULT,
    /**
     * 编译时能调用的那一批**用户函数**。
     *
     * 同样只是**记录**（和 [degrees] / [parameters] 一个道理）：被调用函数的函数体
     * 早在建树时就编译进 [Node.UserCall] 里了，[eval] 不再查任何表 ——
     * 这也意味着表达式编译完就是不可变、可并发求值的，跟以前一样。
     */
    val callables: CallableSet = CallableSet.EMPTY
) {

    /** 按 [variables] 顺序传参求值。 */
    fun eval(vararg args: Double): Double = root.eval(args)

    /** 免装箱版本，给采样器热路径用。 */
    fun evalArray(args: DoubleArray): Double = root.eval(args)

    companion object {

        /**
         * 一条表达式里最多允许展开出多少个"用户函数调用点"。
         *
         * 防的是**宽度爆炸**：环和深度都拦不住 `a₁ = a₂+a₂`、`a₂ = a₃+a₃`……
         * 这种指数展开（20 个函数就是上百万个节点），手表上会直接卡死。
         * 正常写法（`2a(x)+a(x+1)`）只用掉两三个，所以这个数给得很宽松。
         */
        const val MAX_USER_CALLS: Int = 256

        /**
         * 用户函数调用的最大嵌套层数（顶层表达式里的直接调用 = 第 1 层）。
         *
         * 编译期就按它拦（`a(b(c(...)))` 深到离谱时立刻报错），
         * 求值期 [Node.UserCall] 再用它兜一次底 —— 手表上绝不允许出现
         * `StackOverflowError`（那是崩溃，不是报错）。
         */
        const val MAX_USER_CALL_DEPTH: Int = 32

        /**
         * 编译一个表达式。
         *
         * 支持省略乘号（`2x`、`3sin(x)`、`(x+1)(x-1)`、`xy`）、
         * 右结合幂（`2^3^2 = 512`）、`-2^2 = -4`、`|x|`、`5!`。
         *
         * @param variables 该上下文里合法的变量名。写别的名字会给出明确报错，
         *                  而不是悄悄求值成 0 —— 这在手表上尤其重要。
         * @param degrees true 表示角度制（DEG）：三角函数按"度"理解
         *                （`sin(90)` = 1），反三角函数返回"度"。换算在
         *                **编译期**折进语法树，所以这个开关不是全局状态，
         *                同一个进程里 DEG / RAD 的表达式可以共存、可以并发求值。
         * @param parameters 可绑定参数 `a b c d` 的取值，默认全部 1.0
         *                （见 [ParameterSet]）。取值同样在**编译期**折成常量，
         *                所以 [eval] 的签名和求值热路径一点没变；
         *                不给这个参数时 `a*x+b` 等价于 `x+1`。
         * @param callables 这一批**用户定义过、可以被调用**的函数（见 [CallableSet]）。
         *                给了它，表达式里写 `a(x)` / `a(x+1)` / `2a(x)` / `a(a(x))`
         *                就会去调用对应的函数；**不给（默认空表）时行为与加这个功能
         *                之前逐位一致** —— `a(x)` 仍然是 `a * (x)`。
         *
         *                注意 `a` **不带括号**时照样是可绑定参数（缺省 1.0），
         *                所以 `a*x+b` 这类老用法一点没变（用户明确要求）。
         *                只有显函数（[PlotMode.EXPLICIT]）能被调用，别的模式会给出
         *                "不是 y=f(x) 的函数，不能被调用"这样的中文报错；
         *                自引用与循环调用同样在**编译期**就变成可读报错，不会栈溢出。
         */
        fun compile(
            source: String,
            variables: List<String> = listOf("x"),
            degrees: Boolean = false,
            parameters: ParameterSet = ParameterSet.DEFAULT,
            callables: CallableSet = CallableSet.EMPTY
        ): Expression {
            if (source.isBlank()) throw ExpressionException("表达式是空的", 0)
            return compileNested(
                source, variables, degrees, parameters, callables,
                emptyList(), CallBudget(MAX_USER_CALLS)
            )
        }

        /**
         * 真正建树的那一层。
         *
         * 和 [compile] 的差别只有两个"内部的"参数：
         *  * [callStack] —— 正在编译其函数体的那些用户函数名（最里面那个在最后），
         *    环检查就靠它；
         *  * [budget] —— 整棵表达式树共享的调用展开预算（见 [MAX_USER_CALLS]）。
         *
         * 做成 internal 是为了让 [Parser] 递归编译被调用函数的函数体时
         * 能把这两样东西带下去，而不是从公开 API 上开一个口子。
         */
        internal fun compileNested(
            source: String,
            variables: List<String>,
            degrees: Boolean,
            parameters: ParameterSet,
            callables: CallableSet,
            callStack: List<String>,
            budget: CallBudget
        ): Expression {
            val tokens = Lexer(source).tokenize()
            val parser = Parser(tokens, variables, degrees, parameters, callables, callStack, budget)
            val root = parser.parse()
            return Expression(root, variables, degrees, parameters, callables)
        }

        /**
         * 编译一个等式。`左边 = 右边` 会被移项成 `左边 - 右边`，
         * 方便用户直接照抄课本上的 `x^2 + y^2 = 1`。
         */
        fun compileEquation(
            source: String,
            variables: List<String> = listOf("x", "y"),
            degrees: Boolean = false,
            parameters: ParameterSet = ParameterSet.DEFAULT,
            callables: CallableSet = CallableSet.EMPTY
        ): Expression {
            val idx = indexOfTopLevelEquals(source)
            return if (idx < 0) {
                compile(source, variables, degrees, parameters, callables)
            } else {
                val lhs = source.substring(0, idx)
                val rhs = source.substring(idx + 1)
                if (rhs.isBlank()) throw ExpressionException("等号右边是空的", idx)
                compile("($lhs)-($rhs)", variables, degrees, parameters, callables)
            }
        }

        /** 找到不在括号内的第一个 '='。找不到返回 -1。 */
        private fun indexOfTopLevelEquals(s: String): Int {
            var depth = 0
            for (i in s.indices) {
                when (s[i]) {
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    '=' -> if (depth == 0) return i
                }
            }
            return -1
        }
    }
}

/**
 * 递归下降 + 优先级爬升。
 *
 * 优先级（从低到高）：加减 → 乘除/取模/隐式乘 → 一元正负 → 幂(右结合) → 阶乘 → 基本项
 *
 * @param degrees 角度制开关。它只影响**建树**（见 [makeCall]），
 *                建完树就是一份普通的、不可变的 AST。
 * @param parameters 可绑定参数 `a b c d` 的取值。只在建树时用来把
 *                   `a` 这类名字折成 [Node.Const]，不进 AST 的变量表，
 *                   所以采样器那边的参数数组布局完全不受影响。
 * @param callables 可以被调用的用户函数表（见 [CallableFn]）。只有**带括号**时才算调用，
 *                  所以表里有 `a` 也不影响不带括号的 `a` 继续当可绑定参数。
 * @param callStack 正在编译其函数体的那些用户函数名（最里面那个在最后）。
 *                  它就是环检查（自引用 / 相互调用）的依据；顶层表达式是空表。
 * @param budget 整棵表达式树共享的调用展开预算，防宽度爆炸（见 [CallBudget]）。
 */
internal class Parser(
    private val tokens: List<Token>,
    private val variables: List<String>,
    private val degrees: Boolean = false,
    private val parameters: ParameterSet = ParameterSet.DEFAULT,
    private val callables: CallableSet = CallableSet.EMPTY,
    private val callStack: List<String> = emptyList(),
    private val budget: CallBudget = CallBudget(Expression.MAX_USER_CALLS)
) {

    private var p = 0

    /**
     * 当前嵌套在几层 |...| 里面。
     *
     * 这个计数是为了解决一个真实的歧义：
     *   `2|y|` 里的第一个 `|` 应该开启一个新操作数（隐式乘法），
     *   而 `|x|` 里结尾的那个 `|` 是闭合符号，绝不能再开启操作数。
     * 光看 token 类型分不出来，所以靠"是否已经在绝对值内部"来判定。
     */
    private var insideBars = 0

    private fun peek(): Token = tokens[p]

    private fun advance(): Token = tokens[p++]

    private fun matchOp(text: String): Boolean {
        val t = peek()
        if (t.type == TokenType.OP && t.text == text) {
            p++
            return true
        }
        return false
    }

    fun parse(): Node {
        val n = parseSum()
        val t = peek()
        if (t.type != TokenType.EOF) {
            throw ExpressionException(unexpectedMessage(t), t.pos)
        }
        return n
    }

    private fun unexpectedMessage(t: Token): String = when (t.type) {
        TokenType.RPAREN -> "多余的右括号"
        TokenType.OP -> "运算符 '${t.text}' 的位置不对"
        TokenType.EQUALS -> "一个表达式里只能有一个等号"
        TokenType.COMMA -> "逗号只能出现在函数参数里"
        TokenType.NUMBER -> "数字 '${t.text}' 前面缺少运算符"
        TokenType.IDENT -> "名字 '${t.text}' 前面缺少运算符"
        else -> "表达式在这里断掉了"
    }

    private fun parseSum(): Node {
        var left = parseProduct()
        while (true) {
            when {
                matchOp("+") -> left = Node.Add(left, parseProduct())
                matchOp("-") -> left = Node.Sub(left, parseProduct())
                else -> return left
            }
        }
    }

    private fun parseProduct(): Node {
        var left = parseUnary()
        while (true) {
            when {
                matchOp("*") -> left = Node.Mul(left, parseUnary())
                matchOp("/") -> left = Node.Div(left, parseUnary())
                matchOp("%") -> left = Node.Mod(left, parseUnary())
                // 隐式乘法：紧跟一个能开启操作数的 token
                startsOperand(peek()) -> left = Node.Mul(left, parseUnary())
                else -> return left
            }
        }
    }

    /**
     * 判断下一个 token 能不能开启一个操作数 —— 用于隐式乘法。
     *
     * 刻意**不**把 NUMBER 算进来：`1.2.3` 这类输入应该在最后报错，
     * 而不是被静默解释成 1.2*3。
     */
    private fun startsOperand(t: Token): Boolean = when (t.type) {
        TokenType.IDENT, TokenType.LPAREN -> true
        // 已经在 |...| 内部时，BAR 只能是闭合符
        TokenType.BAR -> insideBars == 0
        else -> false
    }

    private fun parseUnary(): Node = when {
        matchOp("-") -> Node.Neg(parseUnary())
        matchOp("+") -> parseUnary()
        else -> parsePower()
    }

    private fun parsePower(): Node {
        val base = parsePostfix()
        // 幂右结合，且指数允许一元负号：2^-1、2^3^2
        return if (matchOp("^")) Node.Pow(base, parseUnary()) else base
    }

    private fun parsePostfix(): Node {
        var n = parsePrimary()
        while (matchOp("!")) n = Node.Fact(n)
        return n
    }

    private fun parsePrimary(): Node {
        val t = peek()
        return when (t.type) {
            TokenType.NUMBER -> {
                p++
                Node.Const(t.value)
            }

            TokenType.LPAREN -> {
                p++
                val inner = parseSum()
                val close = peek()
                if (close.type != TokenType.RPAREN) {
                    throw ExpressionException("括号没有闭合", close.pos)
                }
                p++
                inner
            }

            TokenType.BAR -> {
                p++
                insideBars++
                val inner = try {
                    parseSum()
                } finally {
                    insideBars--
                }
                val close = peek()
                if (close.type != TokenType.BAR) {
                    throw ExpressionException("绝对值符号 | 没有成对出现", close.pos)
                }
                p++
                Node.Call("abs", listOf(inner))
            }

            TokenType.IDENT -> parseIdent(t)

            TokenType.EOF -> throw ExpressionException("表达式不完整", t.pos)
            else -> throw ExpressionException(unexpectedMessage(t), t.pos)
        }
    }

    private fun parseIdent(t: Token): Node {
        val name = t.text
        p++
        val followedByParen = peek().type == TokenType.LPAREN

        // 1) 用户定义过的函数调用（用户报的 bug：b(x)=2a(x) 被算成了 2x）。
        //
        //    判定规则和用户在界面上定的一致：**名字右边有括号就是函数**
        //    （`GraphState.parametersNeedingSliders` 用的是同一条规则）。
        //    所以它必须排在"采样变量 / 可绑定参数"前面 —— `a(x)` 是调用，
        //    而**不带括号**的 `a` 仍然原样走下面那条可绑定参数的老路（`a*x+b` 不受影响）。
        //
        //    唯一的例外是内置函数名：用户给函数起名 `sin(x)` 盖不掉内置的 sin。
        //    这里不是"静默偏向谁"，而是内置名在用户心里本来就有确定含义。
        if (followedByParen && !isBuiltinName(name)) {
            callables[name]?.let { return parseUserCall(it, t.pos) }
        }

        // 2) 采样变量优先。
        //    顺序很关键：万一某个调用方自己把 "a" 声明进了 variables，
        //    以前它是采样变量（按位置传参），现在必须还是 —— 老行为不变。
        val varIndex = variables.indexOf(name)
        if (varIndex >= 0) return Node.Var(varIndex)

        // 3) 可绑定参数 a b c d：取值在**编译期**折成常量。
        //    和采样变量分开之后，eval 的实参个数不用变，
        //    四种绘图模式下也都能用（它们不属于任何模式的采样输入）。
        //    注意 value() 对不认识的名字返回 null，所以 e、π、θ、t、x、y
        //    以及真正的未知名字（z、w……）都原样走后面的老逻辑。
        parameters.value(name)?.let { return Node.Const(it) }

        // 4) 常量
        MathFns.constantOf(name)?.let { return Node.Const(it) }
        // 带 π 的组合常量（如 2π 由隐式乘法处理，这里只管单独出现的情况）
        MathFns.constantOf(name.lowercase())?.let { return Node.Const(it) }

        // 5) 函数
        val arity = MathFns.arityOf(name) ?: MathFns.arityOf(name.lowercase())
        if (arity != null) {
            val fnName = if (MathFns.isFunction(name)) name else name.lowercase()
            return if (peek().type == TokenType.LPAREN) {
                parseCallArgs(fnName, arity, t.pos)
            } else {
                // 允许省括号的写法：sin x、√x、ln x
                if (!arity.contains(1)) {
                    throw ExpressionException("函数 $fnName 需要 ${arity.first} 个参数，要写括号", t.pos)
                }
                makeCall(fnName, listOf(parseUnary()))
            }
        }

        // 6) 兜底：把未知名字拆成单个变量的乘积。
        //    隐函数里用户很自然会写 xy 表示 x*y、2πx 之类。
        trySplitIntoProduct(name)?.let { return it }

        throw ExpressionException(
            "不认识的名字 '$name'。" + hintFor(name),
            t.pos
        )
    }

    /**
     * 这个名字是不是内置函数名（含小写写法）。
     *
     * 单独抽出来是因为 [parseIdent] 要在**自己函数表之前**先把内置名认掉：
     * 用户给函数起名 `sin(x)` 不应该把内置的 sin 盖掉。
     */
    private fun isBuiltinName(name: String): Boolean =
        MathFns.isFunction(name) || MathFns.isFunction(name.lowercase())

    /**
     * 用户函数调用：`a(x)`、`2a(x)`、`a(x+1)`、`a(a(x))`。
     *
     * 这里一次干完四件事，顺序有讲究：
     *
     *  1. **能调用吗**：只有 [PlotMode.EXPLICIT] 是"给 x 返回 y"的形状。
     *     极坐标 / 描点 / 隐函数被写成 `p(x)` 时给一条明确的中文报错，
     *     而不是按"未知名字"含糊过去，更不是悄悄算成一个错的数。
     *  2. **环检查**：这个名字已经在 [callStack] 上，说明它的函数体（间接地）又调回了它。
     *     编译期就报错 —— 用户报的"不能崩"在这里兑现：不进求值、不递归、不栈溢出。
     *  3. **实参**：用**调用方**的语法（[variables] / [degrees]）解析，
     *     所以 [Node.UserCall.arg] 求值时吃的就是调用方的采样变量。
     *  4. **函数体**：按被调用函数**自己的** degrees / 参数表编译，
     *     变量表固定成 [PlotMode.EXPLICIT] 的 `x`（它就是一元函数）。
     *     注意这里**不能**走 [makeCall]：DEG 换算要按被调用函数的设置来，
     *     而不是调用方的设置。
     */
    private fun parseUserCall(fn: CallableFn, namePos: Int): Node {
        val display = fn.displayName

        if (!fn.isCallable) {
            throw ExpressionException(
                "$display 不是 ${PlotMode.EXPLICIT.label} 的函数（它是${fn.mode.label}模式），不能被调用",
                namePos
            )
        }

        val at = callStack.indexOf(fn.name)
        if (at >= 0) {
            val path = callStack.subList(at, callStack.size) + fn.name
            val msg = if (path.size == 2) {
                "函数 ${fn.name} 调用了自己，无法求值"
            } else {
                "函数 ${path.joinToString(" → ")} 相互调用形成了循环，无法求值"
            }
            throw ExpressionException(msg, namePos)
        }

        val depth = callStack.size + 1
        if (depth > Expression.MAX_USER_CALL_DEPTH) {
            throw ExpressionException(
                "函数调用嵌套太深（超过 ${Expression.MAX_USER_CALL_DEPTH} 层）：" +
                    (callStack + fn.name).joinToString(" → "),
                namePos
            )
        }
        if (budget.remaining <= 0) {
            throw ExpressionException(
                "这条表达式展开出的函数调用太多（超过 ${Expression.MAX_USER_CALLS} 个），请写简单一点",
                namePos
            )
        }
        budget.remaining--

        p++ // 吃掉 '('
        if (peek().type == TokenType.RPAREN) {
            throw ExpressionException("函数 $display 缺少参数", namePos)
        }
        val arg = parseSum()
        when (peek().type) {
            TokenType.RPAREN -> p++
            TokenType.COMMA -> throw ExpressionException(
                "函数 $display 只需要一个参数（它是 ${PlotMode.EXPLICIT.label} 的形状）",
                peek().pos
            )
            else -> throw ExpressionException(
                "函数 $display 的参数列表少了一个右括号",
                peek().pos
            )
        }

        // 用户很可能把 `y = sin(x)` 整串抄进编辑框，这里统一剥掉前缀再编译函数体
        val body = ExplicitSource.strip(fn.expression)
        if (body.isEmpty()) {
            throw ExpressionException("函数 $display 还没写表达式，不能被调用", namePos)
        }

        val callee = Expression.compileNested(
            body,
            PlotMode.EXPLICIT.variables,
            // 它自己的度制；实在没给（null）就跟随调用方 —— 后者在正常接入下
            // 与"全局默认值"是同一个东西（见 CallableFn 的注释）
            fn.degrees ?: degrees,
            fn.parameters ?: parameters,
            callables,
            callStack + fn.name,
            budget
        )
        return Node.UserCall(fn.name, callee, arg, depth)
    }

    private fun hintFor(name: String): String = when {
        name.length > 1 && MathFns.isFunction(name.dropLast(1)) ->
            "函数要加括号，比如 ${name.dropLast(1)}(${name.last()})"
        // 表里有这个名字（但不带括号）时，最可能的意图是"想调用它忘了写括号"
        callables.isNotEmpty() && callables[name]?.isCallable == true ->
            "想调用函数 ${callables[name]!!.displayName} 的话要带括号：$name(x)"
        else -> buildString {
            append("可用的变量：${variables.joinToString("、")}")
            append("；可绑定参数：${ParameterSet.BINDABLE.joinToString("、")}")
            if (callables.isNotEmpty()) {
                append("；可调用的函数：${callables.functions.filter { it.isCallable }
                    .joinToString("、") { it.displayName }}")
            }
        }
    }

    private fun parseCallArgs(name: String, arity: IntRange, namePos: Int): Node {
        p++ // 吃掉 '('
        val args = ArrayList<Node>(2)
        if (peek().type == TokenType.RPAREN) {
            throw ExpressionException("函数 $name 缺少参数", namePos)
        }
        while (true) {
            args.add(parseSum())
            when (peek().type) {
                TokenType.COMMA -> p++
                TokenType.RPAREN -> {
                    p++
                    break
                }
                else -> throw ExpressionException(
                    "函数 $name 的参数列表少了一个逗号或右括号",
                    peek().pos
                )
            }
        }
        if (args.size !in arity) {
            throw ExpressionException(
                "函数 $name 需要 ${arityDescription(arity)} 个参数，现在给了 ${args.size} 个",
                namePos
            )
        }
        return makeCall(name, args)
    }

    /**
     * 建函数调用节点 —— DEG/RAD 换算唯一的落点。
     *
     * 为什么放在这一层：`Expression` 是不可变、可被后台线程并发求值的，
     * 所以"现在是度还是弧度"必须在**编译期**变成树上的一部分。
     * 这里做的就是在 AST 上插一层乘法：
     *
     *  * 正向三角 `sin cos tan cot sec csc` —— 包参数：`sin(a)` → `sin(a * π/180)`
     *  * 反向三角 `asin acos atan atan2` —— 包结果：`asin(a)` → `asin(a) * 180/π`
     *    （`atan2(y, x)` 的两个参数是坐标，一律当普通数，只有结果换）
     *  * 其它函数（双曲、对数、`deg`/`rad`……）原样不动
     *
     * 因为包的是节点而不是就地改写数值，`sin(x)` 这种带变量的参数同样正确，
     * 而且弧度模式下建的树与改动前**逐字节等价**（degrees=false 直接返回原节点）。
     */
    private fun makeCall(name: String, args: List<Node>): Node = when {
        !degrees -> Node.Call(name, args)

        MathFns.isForwardTrig(name) ->
            Node.Call(name, args.map { Node.Mul(it, Node.Const(MathFns.DEG_TO_RAD)) })

        MathFns.isInverseTrig(name) ->
            Node.Mul(Node.Call(name, args), Node.Const(MathFns.RAD_TO_DEG))

        else -> Node.Call(name, args)
    }

    private fun arityDescription(arity: IntRange): String =
        if (arity.first == arity.last) "${arity.first}" else "${arity.first}~${arity.last}"

    /**
     * 把 `xy` 拆成 x*y；只要有一个字符既不是采样变量、也不是可绑定参数或常量，
     * 就返回 null（外层会给"不认识的名字"报错）。
     *
     * 可绑定参数也算进来，是为了让 `ax`（= a*x）、`ab` 这类写法能用 ——
     * 和已有的 `xy`、`2πx` 保持一致，用户在字母页刚打完 a 就能接着打 x。
     */
    private fun trySplitIntoProduct(name: String): Node? {
        if (name.length < 2) return null
        var node: Node? = null
        for (ch in name) {
            val one = ch.toString()
            val child: Node = when {
                variables.contains(one) -> Node.Var(variables.indexOf(one))
                else -> parameters.value(one)?.let { Node.Const(it) }
                    ?: MathFns.constantOf(one)?.let { Node.Const(it) }
                    ?: return null
            }
            node = if (node == null) child else Node.Mul(node, child)
        }
        return node
    }
}
