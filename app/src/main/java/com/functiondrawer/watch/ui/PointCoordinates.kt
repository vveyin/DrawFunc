package com.functiondrawer.watch.ui

import com.functiondrawer.watch.engine.GPoint
import com.functiondrawer.watch.engine.ParameterSet

/**
 * 「取坐标」+「标量值」的文本替换 —— 都是把用户写的**名字**换成**数值字面量**。
 *
 * 两件事共用这一遍扫描：
 *
 *  1. `x(A)` / `y(A)` → 点 A 的横/纵坐标。需求原话："点击之后会在输入框显示 x()
 *     （这是一个整体），默认定位到括号中间，括号里输入一个点的名字，
 *     把这个点的 x 坐标作为数值参与运算。" —— `x(A)` 不是"调用函数 x"，
 *     而是一个**数字常量**；
 *  2. **标量名**（角度/距离/面积对象的名字，如 `f`、`g`）→ 那个度量的**当前数值**。
 *     用户原话："就是这些标量能直接写进表达式当数值用" —— 量出一段距离叫 `f` 之后，
 *     就能在别的函数里写 `f*x`、`x^2+f`。
 *
 * 两者的规则细节不同（取坐标要"轴名 + 括号 + 点名"三段，标量是**一个独立的标识符**），
 * 但**必须是同一遍从左到右的扫描**：分开扫两遍的话，第一遍替换出来的数字会被第二遍
 * 再扫一次，边界（括号、隐式乘法）迟早会走散。
 *
 * ## 为什么是文本替换，而不是改引擎
 *
 * 引擎（`engine/` 那一层）只认识四类名字：采样变量、可绑定参数、常量、函数
 * （见 `Expression.parseIdent` 的判定顺序）。**"点"不在里面** ——
 * 点是 UI 层的几何对象（[GraphPointObject]，存在 `GraphState.objects` 里），
 * 引擎一个字节都不知道它的存在。要让 `x(A)` 参与运算只有两条路：
 *
 *   1. 在引擎里加一类"点名"，让它在编译期折成常量；
 *   2. 在**送进引擎之前**把 `x(A)` 换成字面量 `(数值)`。
 *
 * 选了 ②。理由是改动面：① 要动 `Lexer` / `Expression` / `Plotter` 四处，
 * 而那套代码有上百个测试盯着采样、特征点、求交；② 只动 UI 这一侧，
 * 而且"点"本来就是 UI 的状态，什么时候能取、取哪一个点，本来就该由 UI 决定。
 *
 * 代价是**表达式里有几种写法会被"看穿"**（比如 `x(A)` 里 A 被删掉之后，
 * 表达式文本不变、但算出来的是另一条曲线）—— 所以每次采样前都要**重新**替换一遍，
 * 而不是替换一次存起来（见 `GraphState.functionsForSampling`）。
 *
 * ## 这个文件刻意不依赖 Compose
 *
 * 它只 import `engine.GPoint` 和 `engine.ParameterSet`，所以这些规则可以被离线单测钉住
 * （`app/src/test/.../tools/PointCoordinatesTest.kt`，由 `tools/run-tool-tests.ps1` 跑）。
 * 文本扫描最容易错的不是"认不认得"，而是"多吃一个字符 / 少认一种空格"这类边界，
 * 而它们在真机上只表现为"图画错了"，极难查 —— 必须离线钉住。
 *
 * 除了上面两件替换，这个文件还放**第三件事**：判断"哪些名字是可绑定参数、
 * 需要自动补一条滑动条"（[bindableParametersIn]）。它和替换是同一类活儿
 * （扫描表达式文本、判断一个名字是什么），而且**必须放在一起看**：
 * 标量是"不能调的变量"、滑动条是"可以调的变量"，两者判据互斥才不会互相长出来。
 *
 * ## 认得的写法
 *
 * ```
 * x(A)      y(B)        最基本的两种写法
 * x( A )    x (A)       括号内外都容忍空格（用户很自然会多打一个空格）
 * 2x(A)                 x 左边是**数字**照样认：那是隐式乘法，换成 2(1.5) 后语义不变
 * x(A)+y(A)             一条式子里可以出现多次，逐个替换
 * ```
 *
 * ## 不认的写法（一律**原样留下**，交给引擎去报错/去算）
 *
 * ```
 * ax(A)     x 左边是**字母** → 那个 x 其实是标识符 `ax` 的一部分，动它就是改用户的意思
 * x(a)      点名大小写要**精确**匹配（点名叫 A，写 a 就不是它）
 * x(B)      B 不是一个已知的点 → 原样进引擎，报"不认识的名字 'B'"（这个报错是可接受的）
 * x(A1)     点名只认**大写字母序列**，A1 不是
 * x(A       括号没闭上
 * x()       括号里没有名字（键盘刚插完的那种状态）
 * X(A)      轴名只认小写的 x / y（大写 X 不是引擎的变量）
 * ```
 *
 * 名字不认识时**绝不瞎改**：改错了的话用户看到的报错会指向一个他根本没写过的式子，
 * 比原样送进去让引擎报错糟糕得多。
 *
 * ## 替换只做一遍，不做递归
 *
 * 换出来的数字里不可能再出现 `x(` 或一个独立的标量名（`Double.toString()` 只会给出
 * 数字、`E`、`.`、`-`），所以一次从左到右扫描到不动点就够了，不需要 while 循环反复替换。
 *
 * ## 标量名的识别规则（**严格到和引擎词法层逐条对齐**）
 *
 * 引擎的 `Lexer.isIdentStart` / `isIdentPart` 决定了"什么是一段名字"：字母或下划线开头，
 * 后面可以跟字母/数字/下划线。所以标量名的判定就照它来：
 *
 * | 写法 | 结果 | 为什么 |
 * |---|---|---|
 * | `f*x` | `(3.0)*x` | 独立的名字 = 标量 |
 * | `2f` | `2(3.0)` | 左边是**数字**照样认：词法层把 `2` 读成数字字面量、`f` 读成独立的名字，那是隐式乘法 |
 * | `af` | 不动 | 左边是字母 ⇒ 词法层读到的是**一个**名字 `af`，动它就是改用户的意思 |
 * | `_f` | 不动 | 下划线同理（标识符允许下划线） |
 * | `f2` / `fx` | 不动 | 右边是数字/字母 ⇒ 同一个更长的名字 |
 * | `f(x)` / `f (x)` | 不动 | 右边（可以隔空格）是 `(` ⇒ 那是**函数调用**（用户自定义函数 `f(x)`），不是标量。`[` 也一样：词法层把 `[` 当成圆括号 |
 * | `1e5` / `1e+5` / `1.5e-3` | 不动 | **科学计数法让路**：判定与词法层 `readNumber` 一字不差 —— 只有"左边紧挨数字、右边（可隔一个正负号）确实跟着数字"的那个 `e` 才属于数字字面量。替换了会变成 `1(3.0)5` 这种垃圾 |
 * | `2e` | `2(3.0)` | 反过来：后面没跟着数字时，词法层自己就把 `2e` 读成 `2 * e`（见 `Lexer.readNumber` 的注释），所以这时该替换 |
 * | `q*x`（表里没有 q） | 不动 | 名字不认识 ⇒ **原样保留**，交给引擎报"不认识的名字" |
 * | 值不是有限数（NaN/±Inf） | 不动 | 和取坐标同一条理由：塞 `NaN` 进去只会换来一句莫名其妙的词法错误 |
 *
 * 名字合法性另外有一条：只有**纯小写拉丁字母**（`f`、`g`、`aa`…，见 [isScalarName]）
 * 才可能是标量 —— 和 `isPointName` 只认大写字母序列是对称的。
 * 这样复平面里那些用**结果文本**当名字的点（`2`、`2+3i`）也不会被误判。
 *
 * 数值一律**套括号**（和取坐标一样）：负数尤其必须，否则 `f^2` 会算成 `-(3^2)` 而不是 `(-3)^2`。
 *
 * ## 标量是"不能调的变量"（用户定性）
 *
 * 用户原话："它和滑动条本质上都是变量，但是滑动条可以调，这个不能调。" 所以：
 *   * 滑动条（可绑定参数 `a`~`d`）= **可调的变量** —— 拖一下就变；
 *   * 标量（`f`、`g`…）= **量出来的变量** —— 值由那次测量决定，**不能调**。
 *
 * 这条定性有一个必须落地的后果：**标量名绝不能长出滑动条**。
 * 实现落在 [bindableParametersIn] 的"整段匹配"规则上（见那个函数的说明）。
 *
 * ## 一处已知代价：标量是**按名字**写进表达式的（本轮不解决）
 *
 * `f*x` 里的 `f` 只是一个名字，表达式本身不知道它指向哪个对象。所以
 *   * 那个度量对象被**删掉**、或者（将来若支持）被**改名**之后，`f*x` 里的 `f`
 *     就会回到"不认识的名字 'f'"这条报错；
 *   * 这**和 `x(A)` 里点名被删掉之后一模一样**：行为一致、报错明确、不会悄悄算错。
 *
 * 将来若要做"改名时同步更新引用它的表达式"，那是**另一件事**（要给表达式建引用关系、
 * 要处理 `.fdf` 存档里的旧名字、要能撤销），不在本轮范围内。现在不做，是因为
 * "报一个看得懂的错" 比 "猜用户想改哪一个" 安全得多。
 */

/** 取坐标的横轴名（小写）。 */
private const val AXIS_X = 'x'

/** 取坐标的纵轴名（小写）。`x(A)` 取横坐标、`y(A)` 取纵坐标。 */
private const val AXIS_Y = 'y'

/**
 * 一个名字长得像不像**点名**：非空、且全是大写字母（`A`、`B`、…、`Z`、`AA`）。
 *
 * 这条规则**必须**和自动取名的规则一致：几何对象的名字来自
 * `GraphState.nextObjectName()`，它是 `('A' + n)` 拼出来的大写字母序列。
 *
 * 卡死成"大写拉丁字母"还顺手挡掉了两种不该被当点名的东西：
 *   * 复平面模式下，每条数值运算算出来的点用的是**结果文本**当名字
 *     （`2`、`2+3i` 这种，见 `GraphState.complexResultPoints`）——
 *     用户写 `x(2)` 想的是"x 乘 2"，不是"取某个点的坐标"；
 *   * 可绑定参数 `a`/`b`/`c`/`d` 和采样变量 `x`/`y`/`t` 都是小写，
 *     写成 `x(a)` 时引擎会把它当"用参数 a 去调用 x"，我们不该抢这个解释权。
 */
fun isPointName(name: String): Boolean = name.isNotEmpty() && name.all { it in 'A'..'Z' }

/**
 * 一个名字长得像不像**标量名**（角度/距离/面积这类度量对象的名字）：非空、且全是小写拉丁字母。
 *
 * 和 [isPointName] 是对称的一对：
 *   * 点名 = **大写**字母序列（`GraphState.nextObjectName` 的产物）；
 *   * 标量名 = **小写**字母序列（`GraphState.nextMeasureName` →
 *     `FunctionTools.nextScalarName` 的产物）。
 *
 * 卡死成"小写拉丁字母"顺手挡掉了三种不该被当标量的东西：
 *   * 复平面里用**结果文本**当名字的点（`2`、`2+3i`）—— 它们既不是纯字母，也不该参与替换；
 *   * 引擎自己的常量与变量名（`π`、`θ`、`φ` 这些非 ASCII 字母）；
 *   * 采样变量 / 可绑定参数（`x`、`y`、`t`、`a`…）—— 它们**形状上**合法，
 *     但绝不会出现在标量表里（取名时就跳过了，见 `SCALAR_NAME_RESERVED`）。
 */
fun isScalarName(name: String): Boolean = name.isNotEmpty() && name.all { it in 'a'..'z' }

/**
 * 把 [src] 里所有"认得的"取坐标调用和标量名换成 `(数值)`。
 *
 * [points] 是**点名 → 坐标**表、[scalars] 是**标量名 → 数值**表
 * （都由调用方给出，见 `GraphState.pointCoordinates()` / `GraphState.scalarValues()`）。
 * 两张表都是参数而不是从某个全局状态里读，是为了让这个函数是纯函数：
 * 同样的输入永远得到同样的输出，离线测试可以随便造表。
 *
 * 两张表都为空、或者文本里一个可替换的名字都没有，都原样返回（**连空格都不动**）——
 * 这个函数在采样热路径上，绝大多数表达式里既没有 `x(` 也没有标量名，所以先判空短路。
 *
 * ⚠️ 调用方**不要**把采样变量（`x`/`y`/`t`/`θ`）、可绑定参数（`a`~`d`）或引擎常量
 * （`e`、`i`、`pi`…）放进 [scalars]：放进去就会被替换成数字，`x` 会变成一个常数。
 *
 * 这条约束**本轮改了口径**：取名规则不再跳过这些字母（用户可以允许标量叫 `x`），
 * 所以"能不能替换"改由**绑定表**说了算 —— 只有用户在那条函数上**明确绑定**过的字母
 * 才会进 [scalars] 去抢占引擎的含义（见本文件末尾「名字绑定表」那一节，
 * 以及 `GraphState.functionsForSampling`）。没绑定的名字仍然按老规则来：
 * 只可能是同名标量，绝不会盖住 `x`/`e` 这些引擎自己的名字。
 */
fun substitutePointCoordinates(
    src: String,
    points: Map<String, GPoint>,
    scalars: Map<String, Double> = emptyMap()
): String {
    if (src.isEmpty() || (points.isEmpty() && scalars.isEmpty())) return src
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]

        // ---- 1. 取坐标：`x(A)` / `y(A)` ----
        if ((c == AXIS_X || c == AXIS_Y) && !isIdentLetterBefore(src, i)) {
            val hit = matchCoordinateCall(src, i, points)
            if (hit != null) {
                out.append(hit.text)
                i = hit.end
                continue
            }
        }

        // ---- 2. 标量名：一个**完整**的标识符 ----
        //
        // 关键：把整段名字一次读出来再判断，而不是一个字符一个字符地试。
        // 这样"左边/右边紧挨着字母或数字"的情况就自然落空（`af`、`fx`、`f2`
        // 读出来都是一整个更长的名字，它不在表里），不需要额外的左右边界判断 ——
        // 逐字符试的写法会把 `af` 里面的 `f` 揪出来替换掉，那是在改用户的意思。
        if (isIdentStart(c)) {
            val end = readIdentEnd(src, i)
            val word = src.substring(i, end)
            val replacement = matchScalar(src, i, end, word, scalars)
            out.append(replacement ?: word)
            i = end
            continue
        }

        out.append(c)
        i++
    }
    return out.toString()
}

/** 标量的便捷入口：只替换标量值（没有几何点时用）。 */
fun substituteScalarValues(src: String, scalars: Map<String, Double>): String =
    substitutePointCoordinates(src, emptyMap(), scalars)

// ===================================================================================
// 「哪些名字需要滑动条」—— 可绑定参数扫描
// ===================================================================================

/**
 * 表达式里**需要滑动条的可绑定参数**（`a` / `b` / `c` / `d`），按出现顺序、去重。
 *
 * 判定规则来自用户（需求 #5）：
 *   * 小写字母**右边有括号** = 函数（`sin(x)`、用户自定义的 `a(x)`）→ 不是参数；
 *   * `x` / `y` / `t` / `θ` = 采样变量 → 不是参数；
 *   * 剩下的可绑定字母（`a`/`b`/`c`/`d`）→ **是参数**，要自动补一条滑动条。
 *
 * 判据落在 [ParameterSet.BINDABLE] 上，而且要求**整段名字恰好等于**其中一个：
 * 引擎词法层把 `ax` / `ab` / `a2` 读成**一个**标识符（`Lexer.isIdentPart` = 字母/数字/下划线），
 * 那个标识符既不是参数、也不是采样变量、也不是函数，引擎会直接报"不认识的名字 'ax'" ——
 * 所以这里也不该替它生出一条滑动条（那只会多出一行拖了也没用的东西）。
 *
 * ### 为什么必须"整段匹配"（和标量对象直接相关）
 *
 * 用户把标量（角度/距离/面积的取名）定性为："它和滑动条本质上都是变量，
 * 但是滑动条可以调，这个不能调。" 也就是说
 *   * 可绑定参数（`a`~`d`）= **可调的变量**（拖滑动条）；
 *   * 标量（`f`、`g`…）= **量出来的变量**（不能调）。
 *
 * 两者是同一个概念的两面，所以**标量名绝不能长出滑动条**，靠两道保证：
 *   1. 取名时 `SCALAR_NAME_RESERVED` 跳过了 `a`~`d`（以及 `e`/`i`/`z`/`x`/`y`/`t`/`θ`
 *      这些引擎里有含义的名字），所以标量名不会等于 [ParameterSet.BINDABLE] 里的任何一个；
 *   2. 这里是**整段匹配**，不是"逐个字符看这个字母在不在 BINDABLE 里"。
 *      标量名是双射 26 进制序列：`f`、`g`…`w` 这些单字母用完就进位成 `aa`、`ab`…
 *      （`z` 被跳过），**`aa` 里就含有字母 `a`** —— 逐字符扫会把它拆出 `a`、`b`
 *      来凭空生成两条滑动条。整段匹配之后，`aa` 只是"一个不是参数的名字"。
 *
 * （`x(A)` 里的 `A` 也不会生成滑动条：大写字母不在 BINDABLE 里 ——
 * 这也是 `isPointName` 只认大写字母的另一个理由。）
 */
fun bindableParametersIn(src: String): List<String> {
    if (src.isBlank()) return emptyList()
    val found = LinkedHashSet<String>()
    var i = 0
    while (i < src.length) {
        if (!isIdentStart(src[i])) {
            i++
            continue
        }
        // 和替换那一边一样：**整段**读出一个标识符再判断（字符集照抄 Lexer.isIdentPart）
        val end = readIdentEnd(src, i)
        val word = src.substring(i, end)
        i = end
        // 后面紧跟 `(`（中间可以有空白）就是函数调用，这个名字本身不是参数
        val after = skipSpaces(src, end)
        val isCall = after < src.length && src[after] == '('
        if (!isCall && word in ParameterSet.BINDABLE) found += word
    }
    return found.toList()
}

/** 一次命中：换成什么文本、以及原文本里吃到哪一位（不含）。 */
private class Replacement(val text: String, val end: Int)

/**
 * 这一处 [start, end) 的标识符是不是一个标量名；是就返回 `(数值)`，否则 null。
 *
 * 三道关卡，任何一道不过都**原样保留**（宁可少换，绝不多换）：
 *   1. [isScalarName]：只有纯小写拉丁字母才可能是标量名；
 *   2. 右边（允许隔空格）是 `(` ⇒ 那是**函数调用** `f(x)`，不是标量；
 *   3. 属于数字字面量的科学计数法（`1e5` / `1e+5`）⇒ 让路（见 [isExponentPart]）。
 */
private fun matchScalar(
    src: String,
    start: Int,
    end: Int,
    word: String,
    scalars: Map<String, Double>
): String? {
    if (scalars.isEmpty() || !isScalarName(word)) return null
    val value = scalars[word] ?: return null

    // 右边是 `(`（可以隔空格）→ 函数调用。空格也要看，因为引擎词法层会在 `(` 前
    // 跳过空白，`f (x)` 在它眼里仍然是"调用 f"。
    //
    // `[` 也算：词法层把 `[` 和 `]` 直接当成圆括号（`Lexer.readSymbol` 里
    // `'('[` → LPAREN），所以 `f[2]` 在引擎眼里同样是"调用 f"。
    val after = skipSpaces(src, end)
    if (after < src.length && (src[after] == '(' || src[after] == '[')) return null

    if (isExponentPart(src, start, end)) return null

    // NaN / ±Infinity 一律**不替换**（理由和取坐标那边一样：塞 "NaN" 进表达式，
    // 引擎只会报"数字格式不正确"，原样留着反而能让用户看见这里有个东西算不出来）
    if (!value.isFinite()) return null

    // 和取坐标一样**套一层括号**：负数是 `(-2.5)`，`f^2` 才不会算成 `-(2.5^2)`。
    return "($value)"
}

/**
 * 引擎词法层的标识符首字符判定（`Lexer.isIdentStart`）：字母或下划线。
 *
 * **数字不算**：`2f` 里的 `2` 在词法层是独立的数字字面量，所以从 `2` 出发不会读出一个
 * 标识符，接下来在 `f` 那里读到的就是独立的标量名 —— "左边是数字照样替换"这条
 * 就是从这里自然得出的，不需要额外判断。
 */
private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

/**
 * 从 [from] 起读出一个完整标识符的结束位置（不含）。
 *
 * 字符集和 `Lexer.isIdentPart` 一致：字母 / 数字 / 下划线。多读进来的数字是有意的 ——
 * `f2` 因此在词法层是**一个**名字，我们就不该把它拆成 `f` 和 `2`。
 */
private fun readIdentEnd(src: String, from: Int): Int {
    var i = from
    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
    return i
}

/**
 * 这一处标识符是不是"数字字面量的指数部分"（`1e5`、`1e+5`、`1.5E-3`）。
 *
 * 判定**逐条照抄** `Lexer.readNumber`：先看 `e`/`E` 左边紧不紧挨数字或小数点，
 * 再看右边（允许隔一个正负号）是不是数字 —— 三条都满足才算，缺一条就不是
 * （`2e` 后面没有数字，词法层自己就把那个 `e` 当常数，所以我们照常替换）。
 *
 * 为什么不让路就是错的：把 `1e5` 里的 `e` 换掉会得到 `1(3.0)5` —— 一个语法错误，
 * 而用户写的是完全合法的一个数。这一类"看穿了数字"的错误在真机上只表现为
 * "这条函数打不开了"，几乎不可能靠肉眼定位，所以专门为它写了测试。
 */
private fun isExponentPart(src: String, start: Int, end: Int): Boolean {
    // 只可能是**单个** e / E（`e5` 那种在 readIdentEnd 里就已经并成一个更长的名字了）
    if (end - start != 1) return false
    val c = src[start]
    if (c != 'e' && c != 'E') return false
    if (start <= 0) return false
    val before = src[start - 1]
    if (!before.isDigit() && before != '.') return false
    var j = start + 1
    if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
    return j < src.length && src[j].isDigit()
}

/**
 * 轴名左边是不是还连着一个标识符字母。
 *
 * ⚠️ 现在这条判断**基本已经是双保险**了：[substitutePointCoordinates] 会把一整段标识符
 * 一次吃掉（见那里的说明），所以扫描根本走不到"某个字母的中间"去；这里留着它，
 * 是为了万一将来有人改了扫描顺序，`ax(A)` 那种写法也不会被拆成 `a` + `x(A)`。
 *
 * 判定和词法层的 `isIdentPart` 同一套（字母或下划线）——
 * 只差**数字**：`2x(A)` 里的 `x` 左边是数字，而词法层会把 `2` 读成一个数字字面量、
 * 把 `x` 读成一个独立的名字，所以那种写法该认（隐式乘法）。
 * 反过来 `ax(A)` 里的 `x` 前面是字母，词法层读到的是**一个**名字 `ax` ——
 * 这时候替换就是在改用户的意思，必须放过。
 *
 * 光标在文本开头（`index < 0`）不算有左边，所以 `x(A)` 开头的写法照认。
 */
private fun isIdentLetterBefore(src: String, index: Int): Boolean {
    if (index <= 0) return false
    val before = src[index - 1]
    return before.isLetter() || before == '_'
}

/**
 * 这个字符算不算"空白"。
 *
 * 字符集和引擎词法层的 `skipWhitespace` **一模一样**（空格 / 制表 / 换行 / 回车），
 * 不敢用 `Char.isWhitespace`：它连不换行空格（U+00A0）都算，而词法层不认识那个字符，
 * 两边不一致就会出现"我们替换成功了、但用户看不见的那一格其实是个非法字符"。
 */
private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

/** 从 [from] 起跳过空白，返回第一个非空白字符的位置（可能越界）。 */
private fun skipSpaces(src: String, from: Int): Int {
    var i = from
    while (i < src.length && isSpace(src[i])) i++
    return i
}

/**
 * 从 [axisAt] 那个轴名字母开始，试着匹配一次取坐标调用 `轴名( 点名 )`。
 *
 * 顺序就是用户写字面的顺序：轴名 →（空格）→ `(` →（空格）→ 点名 →（空格）→ `)`，
 * 任何一步对不上就返回 null（**整个调用原样留着**，不做半截替换）。
 */
private fun matchCoordinateCall(
    src: String,
    axisAt: Int,
    points: Map<String, GPoint>
): Replacement? {
    val open = skipSpaces(src, axisAt + 1)
    if (open >= src.length || src[open] != '(') return null

    val nameStart = skipSpaces(src, open + 1)
    var nameEnd = nameStart
    // 点名只吃大写字母：`x(A1)` / `x(A.B)` 这类都在这里自然落空（见 isPointName）
    while (nameEnd < src.length && src[nameEnd] in 'A'..'Z') nameEnd++
    val name = src.substring(nameStart, nameEnd)
    if (name.isEmpty()) return null

    val close = skipSpaces(src, nameEnd)
    if (close >= src.length || src[close] != ')') return null

    val point = points[name] ?: return null
    val value = if (src[axisAt] == AXIS_X) point.x else point.y
    // NaN / ±Infinity 一律**不替换**：那说明这个点自己就是个坏值，
    // 硬塞一个 "NaN" 进表达式，引擎的词法层只会报"数字格式不正确"这种莫名其妙的错；
    // 原样留下 `x(A)` 反而能让用户看见"这里有东西算不出来"。
    if (!value.isFinite()) return null

    // 数字用 Double.toString()（引擎词法层吃得下的写法）并**套一层括号**：
    //   * 负数是 `(-2.5)`，不会和前后的 `-` / `^` 粘成别的意思（`2x(A)-1` 尤其明显）；
    //   * 顺带把 `2x(A)` → `2(1.5)` 这种隐式乘法写成引擎确定认得的形状。
    return Replacement("($value)", close + 1)
}

// ===================================================================================
// 「这个字母到底指谁」—— 名字绑定表（用户需求：字母冲突由用户在编辑器里指认）
// ===================================================================================
//
// 用户原话："标量和滑动条的名字可以不用严格分出使用的字母，只要后台能识别就行，
// 标量也可以用 a，滑动条也可以用 f。至于 e、x、y 这些可能冲突的字母，
// 请你给函数编辑界面输入的这些字母单独分组编 id，这样也不用担心字母冲突的问题。"
//
// 于是命名规则不再"跳过 a~d / x / y / e"（`FunctionTools.nextScalarName`），
// 撞名变成一种**正常状态**，要靠用户在**那条函数**上说清"这个字母指谁"：
//
//   `PlotFunction`（这里是它的绑定表，存在 `GraphState.functionBindings` 里，
//   为什么不在 PlotFunction 上见报告）`nameBindings: Map<字母, 对象 id>`
//
// ## 解析顺序（[resolveScalarValues] 就是这条规则的唯一实现）
//
//   1. **绑定表**：用户在编辑器里点过的那一项说了算（可以绑定到滑动条、也可以绑定到标量）；
//      绑定过的字母**一定会被替换成那个对象的当前值**，哪怕它叫 `x`、叫 `e`
//      （这是用户要的能力：字母不再和引擎内置含义绑死）；
//   2. 没绑定 → 老规则：可绑定参数 `a`~`d` 由引擎的 `ParameterSet` 管（滑动条的值），
//      其余同名标量由 [GraphState.scalarValues] 折成数值（走 [substitutePointCoordinates]）；
//   3. 都没有 → **原样留着**，交给引擎报"不认识的名字"（绝不猜）。
//
// 绑定指向的对象被删掉之后**不做任何回退**（那个字母就不替换了）：宁可让引擎报一句
// "不认识的名字 'f'"，也不要在用户以为 `f` 是"那段距离"的时候悄悄换成另一个同名的东西。
//
// 这一节是**纯函数**（只 import engine/ParameterSet），离线测试直接钉住它：
// 候选怎么列、绑定怎么折值，都在 `tools/run-tool-tests.ps1` 里跑。

/** 一个字母能指到的东西有两种。 */
enum class NameCandidateKind {
    /** 滑动条（= 可绑定参数，能拖）。 */
    SLIDER,

    /** 标量对象（角度/距离/面积，量出来的、不能拖）。 */
    SCALAR
}

/**
 * 表达式里某个字母的一个可能含义。
 *
 * [needsDecision] = 不指认就会出问题：
 *   * 这个字母同时有滑动条和标量（两个候选 —— 引擎只会认其中一个，用户得说清是哪个）；
 *   * 或者它落在引擎自己认识的名字里（`x`/`y`/`e`/`a`~`d`…，见 [SCALAR_NAME_RESERVED]）；
 *   * 或者唯一候选是"引擎不认识的可绑定参数"（比如滑动条叫 `f`：引擎只认 `a`~`d`，
 *     不绑定的话 `f` 会被报成"不认识的名字"）。
 */
data class NameCandidate(
    val letter: String,
    val kind: NameCandidateKind,
    val objectId: Long,
    /** 给人看的说明：滑动条 = `滑动条`，标量 = 它的种类（`距离`/`角度`/`面积`）。 */
    val label: String,
    val needsDecision: Boolean
) {
    /** 候选条上那颗 chip 的字：`a：滑动条`、`a：距离`（用户原话里的写法）。 */
    val chipLabel: String get() = "$letter：$label"
}

/** 表达式里用到的一个名字（字母）以及它能指到的**全部**候选。 */
data class NameChoice(
    val letter: String,
    val candidates: List<NameCandidate>,
    /** 用户已经指认过的对象 id（没指认过就是 null）。 */
    val boundId: Long? = null
) {
    /** 一个字母有不止一个候选 —— 这就是"字母冲突"。 */
    val ambiguous: Boolean get() = candidates.size > 1

    /** 需要用户点一下才能确定：要么有冲突，要么有哪个候选标了 [NameCandidate.needsDecision]。 */
    val needsDecision: Boolean get() = ambiguous || candidates.any { it.needsDecision }
}

/**
 * 扫出表达式 [src] 里**每个有意义的名字字母**以及它的候选。
 *
 * 扫描规则和 [bindableParametersIn] **一模一样**（同一段"整段读标识符、后面跟 `(` 就算
 * 函数调用"的判定），因为两件事必须对同一个文本得出同一批名字，否则会出现
 * "替换认了这个字母、候选条却不认识它"这种说不清的状态。
 *
 * [sliderParams] / [scalarNames] 是"名字 → 对象 id"；[sliderValues] 只用来判断
 * 候选是不是 `needsDecision`（滑动条叫 `a`~`d` 时引擎自己认识，叫别的就得指认）。
 * [bound] 是这条函数**已经指认过**的表（只为把 [NameChoice.boundId] 填出来，
 * 不过滤 —— 界面要显示"现在指到谁"，还可能让用户改主意）。
 *
 * 返回顺序 = 字母在表达式里出现的先后（用户按自己写字的顺序点最省事）。
 */
fun nameChoicesIn(
    src: String,
    sliderParams: Map<String, Long>,
    scalarNames: Map<String, Long>,
    scalarLabels: Map<String, String> = emptyMap(),
    bound: Map<String, Long> = emptyMap(),
    engineNames: Set<String> = SCALAR_NAME_RESERVED
): List<NameChoice> {
    if (src.isBlank()) return emptyList()
    val out = ArrayList<NameChoice>()
    val seen = HashSet<String>()
    var i = 0
    while (i < src.length) {
        if (!isIdentStart(src[i])) {
            i++
            continue
        }
        val end = readIdentEnd(src, i)
        val word = src.substring(i, end)
        i = end
        // 后面紧跟 `(`（中间可以有空白）就是函数调用，这个名字不是"变量"
        val after = skipSpaces(src, end)
        if (after < src.length && (src[after] == '(' || src[after] == '[')) continue
        // 只认小写字母序列（标量/参数都是这个形状）；点名是大写，复平面的结果文本更不是
        if (!isScalarName(word) || !seen.add(word)) continue

        val candidates = ArrayList<NameCandidate>(2)
        sliderParams[word]?.let { id ->
            candidates += NameCandidate(
                letter = word,
                kind = NameCandidateKind.SLIDER,
                objectId = id,
                label = "滑动条",
                // 引擎只把 a~d 当可绑定参数：滑动条叫别的字母时，不指认引擎就不认识它
                needsDecision = word !in ParameterSet.BINDABLE
            )
        }
        scalarNames[word]?.let { id ->
            candidates += NameCandidate(
                letter = word,
                kind = NameCandidateKind.SCALAR,
                objectId = id,
                label = scalarLabels[word] ?: "标量",
                // 标量叫了引擎里已有的名字（x/e/i/…）时必须指认，否则替换会看穿引擎的含义
                needsDecision = word in engineNames
            )
        }
        if (candidates.isEmpty()) continue
        // 一个字母有两个候选 = 真·冲突：两个都标成"必须选"
        val list = if (candidates.size > 1) {
            candidates.map { it.copy(needsDecision = true) }
        } else {
            candidates
        }
        out += NameChoice(letter = word, candidates = list, boundId = bound[word])
    }
    return out
}

/**
 * 这条函数**这一次采样**要用的名字表：自动规则的结果 + 用户绑定表（绑定优先）。
 *
 * @param auto 老规则算出来的表（`GraphState.scalarValues()`：同名标量的值）
 * @param bound 绑定表（用户在这条函数上指认过的：字母 → 滑动条 id 或标量对象 id）
 * @param sliderValues 滑动条 id → 当前值
 * @param measureValuesById 标量对象 id → 当前值
 * @param sliderParams **当前真正在用的**滑动条参数字母（只有可见的那些，因为
 *        `toParameterSet()` 也是只收可见的 —— 隐藏的滑动条在引擎眼里根本不存在）
 *
 * ## 解析顺序（用户要求那一句话的落地）
 *
 * 用户原话："没绑定就按现在的规则（`ParameterSet.BINDABLE` 优先，其次是同名标量）解析。"
 *
 *   1. **绑定表**：用户点过的那一项说了算 —— 可以绑定滑动条、也可以绑定标量，
 *      绑定的字母**一定**按它折值（哪怕它叫 `x`、叫 `e`）；
 *   2. 没绑定、但**有一条同名的可见滑动条** → 让给引擎（`ParameterSet` 里的 `a`~`d`），
 *      这里**不替换**：否则一条叫 `a` 的滑动条会被一个叫 `a` 的标量悄悄顶掉，
 *      用户拖滑动条时图不动（那正是"冲突"的坏结果）；
 *   3. 没绑定、也没有同名滑动条、但有一个同名标量 → 用它的值（**老规则**，
 *      和 `substitutePointCoordinates` 原来那一版行为一致）；
 *   4. 都没有 → 原样留着，交给引擎报"不认识的名字"（绝不猜）。
 *
 * 绑定指向的对象**已经不在了**（被删掉）时，那个字母会被**从表里拿掉**：
 * 于是表达式里的它不会被替换，引擎会报"不认识的名字 'f'"。
 * 这是有意的 —— 报一个看得懂的错，好过在用户以为 `f` 是"那段距离"的时候
 * 悄悄换成另一个同名的东西。
 */
fun resolveScalarValues(
    auto: Map<String, Double>,
    bound: Map<String, Long>,
    sliderValues: Map<Long, Double>,
    measureValuesById: Map<Long, Double>,
    sliderParams: Set<String> = emptySet()
): Map<String, Double> {
    // 老规则那一侧先按"可见滑动条优先"过一遍（没有绑定的时候就是它说了算）
    val out = LinkedHashMap<String, Double>(auto.size + bound.size)
    for ((letter, v) in auto) {
        if (letter in sliderParams) continue
        out[letter] = v
    }
    if (bound.isEmpty()) return out
    // 绑定表盖在上面：绑了就一定按它算（见上面第 1 条）
    for ((letter, id) in bound) {
        val v = sliderValues[id] ?: measureValuesById[id]
        if (v != null && v.isFinite()) {
            out[letter] = v
        } else {
            out.remove(letter)
        }
    }
    return out
}
