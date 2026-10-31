package com.functiondrawer.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MathRender] 的排版结构单测（纯 JVM，不需要表）。
 *
 * 这里**不断言像素**：JVM 单测里 `android.graphics.Paint.measureText` 只会返回
 * 0（`unitTests.isReturnDefaultValues = true`），宽度一个都没有意义。
 * 断言的是**结构**——空槽位有没有建出来、变量是不是斜体、指数有没有被缩成
 * 上标——这些恰好就是用户在真机上用眼睛判定的那几件事。
 *
 * 覆盖的是 2026-10-29 的四条反馈：
 *  1. `log` 底数位要有一块阴影槽位（`log(,)` 里底数和真数各一块）；
 *  2. `ⁿ√`（`root(,)`）左上角要有一块根指数槽位；
 *  3. `sin(x)` 里的 `x` 必须是**数学斜体**（不能因为包了一层函数就退回正体）；
 *  4. `sin(x^2)` 里的 `2` 必须是**缩小字号的上标**，而不是原样露出来的 `^2`。
 */
class MathRenderTest {

    private fun layout(src: String, size: Float = 32f): MathLayout =
        layoutMath(src, size, Float.MAX_VALUE, MathTypefaces(null, null))

    // ---- 1. log 底数位 ----

    @Test
    fun `log 的空底数和空真数各画一块阴影`() {
        // 键盘 `log` 键插的就是这个串：底数在 `,` 之前、真数在 `,` 之后
        assertEquals(2, layout("log(,)").placeholderCount)
    }

    @Test
    fun `log 填了底数之后只剩真数那块阴影`() {
        // 底数已经输完了：底数槽位的阴影必须消失（用户要求"输入之后不显示阴影"）
        assertEquals(1, layout("log(2,)").placeholderCount)
    }

    @Test
    fun `log 只有一个参数时不画底数槽位`() {
        // `log(x)` 是单参数写法（源码里根本没有逗号），不该凭空长出一块底数阴影
        assertEquals(0, layout("log(x)").placeholderCount)
    }

    // ---- 2. n 次根号的根指数位 ----

    @Test
    fun `n 次根号的根指数和被开方数各画一块阴影`() {
        // `ⁿ√` 键插的是 `root(,)`：第一参是被开方数、第二参是次数
        assertEquals(2, layout("root(,)").placeholderCount)
    }

    @Test
    fun `平方根只在根号下面画一块阴影`() {
        // `√()` 没有根指数，左上角不该有槽位
        assertEquals(1, layout("√()").placeholderCount)
    }

    // ---- 3. sin(x) 里变量的字体 ----

    @Test
    fun `sin 包着的变量仍然是斜体`() {
        val g = layout("sin(x)").glyphSnapshots
        assertTrue("函数名 sin 必须是正体：$g", g.any { it.first == "sin" && it.second == GlyphStyle.UPRIGHT })
        assertTrue("括号必须排成独立字形：$g", g.any { it.first == "(" })
        assertTrue("括号必须排成独立字形：$g", g.any { it.first == ")" })
        assertTrue("sin(x) 里的 x 必须是斜体：$g", g.any { it.first == "x" && it.second == GlyphStyle.ITALIC })
    }

    @Test
    fun `括号里的表达式照常排成分式`() {
        val g = layout("sin(x/2)").glyphSnapshots
        // 分式的分子/分母都排出来了，说明括号里真的走了 parseSum 而不是被摊平成文本
        assertTrue("分子里的 x 要在：$g", g.any { it.first == "x" })
        assertTrue("分母里的 2 要在：$g", g.any { it.first == "2" })
        // `x/2` 若被摊平，会剩一个正体的 `/`
        assertTrue("`/` 已被分式吃掉，不该再出现：$g", g.none { it.first == "/" })
    }

    // ---- 4. 嵌套的指数 ----

    @Test
    fun `sin(x^2) 里的 2 是缩字号的上标`() {
        val g = layout("sin(x^2)").glyphSnapshots
        val x = g.first { it.first == "x" }
        val two = g.first { it.first == "2" }
        assertTrue("`^` 应该被吃掉，不该当文本画出来：$g", g.none { it.first == "^" })
        assertTrue("上标 2 必须比正文小：x=${x.third} 2=${two.third}", two.third < x.third)
    }

    @Test
    fun `单独输入的 x^2 也是上标`() {
        val g = layout("x^2").glyphSnapshots
        val x = g.first { it.first == "x" }
        val two = g.first { it.first == "2" }
        assertTrue("x^2 的上标必须比正文小：$g", two.third < x.third)
    }

    // ---- 5. 键盘插进来的乘号 / 除号（2026-10-29 用户反馈）----
    //
    // 用户原话："函数创建界面的乘号和除号没做 LaTeX 语法。"
    // 键盘那颗键插的是 `×` / `÷`（不是 `*` / `/`），排版层不认就等于把
    // 后面的整截源码摊平成正体文本 —— `^2`、分式全都不再排。

    @Test
    fun `乘号后面的指数仍然排成上标`() {
        val g = layout("2×x^2").glyphSnapshots
        val x = g.first { it.first == "x" }
        // 行首那个 `2` 是正文大小，指数是后面那一个 → 取最后一个
        val two = g.last { it.first == "2" }
        assertTrue("`^` 应该被吃掉：$g", g.none { it.first == "^" })
        assertTrue("乘号后的 2 必须比 x 小：x=${x.third} 2=${two.third}", two.third < x.third)
        // 乘号按 LaTeX 的 `\cdot` 口径排成中点
        assertTrue("乘号应该排成 ⋅：$g", g.any { it.first == "⋅" })
        assertTrue("不该再残留一个 × 叶子：$g", g.none { it.first == "×" })
    }

    @Test
    fun `除号排成分式`() {
        val g = layout("1÷x^2").glyphSnapshots
        // `÷` 被分式吃掉 → 不再作为字形出现
        assertTrue("`÷` 应该被分式吃掉：$g", g.none { it.first == "÷" })
        assertTrue("`^` 应该被吃掉：$g", g.none { it.first == "^" })
        val x = g.first { it.first == "x" }
        val two = g.last { it.first == "2" }
        assertTrue("分母里的上标 2 必须比 x 小：$g", two.third < x.third)
    }

    // ---- 6. 单独按乘号 / 除号（2026-10-29 用户反馈）----

    @Test
    fun `单独按除号立刻画出带两块槽位的分式`() {
        // 用户原话："乘和除单独点没有 LaTeX 效果……分数线的上面和下面没有阴影"
        val l = layout("÷")
        assertEquals("空分子 + 空分母 = 两块阴影", 2, l.placeholderCount)
        assertTrue("分式必须有宽度（否则既看不见也点不中）：${l.width}", l.width > 0f)
    }

    @Test
    fun `按除号后只补了分母槽位`() {
        assertEquals(1, layout("1÷").placeholderCount)
    }

    @Test
    fun `单独按乘号排成中点`() {
        val g = layout("×").glyphSnapshots
        assertTrue("乘号应该排成 ⋅：$g", g.any { it.first == "⋅" })
        assertTrue("不该出现裸的 ×：$g", g.none { it.first == "×" })
    }

    // ---- 7. 光标在结构内部还是外面（2026-10-29 用户两次反馈）----
    //
    // 第一次：点/拖到右边空白处没反应 → 上一版把同下标的停靠点一律取"最外层"，
    // 于是 `x^x` 的光标确实到了右边空白，但代价是指数只能打一个字符。
    // 第二次用户否决："指数位置输入一个数之后就跳到指数外面，那我要是指数位置想输入
    // 一个式子怎么办呢？……我想要的是点击指数按钮之后指数位置就可以输入，
    // 右边空白处也可以输入，指数位也要能输入式子啊。"

    @Test
    fun `x^x 之后的光标还留在指数里`() {
        val l = layout("x^x")
        val s = l.stopSnapshot(3)
        // 指数那一行的行末只有半截高、而且贴在上半部；
        // "整行行末"的停靠点才贯穿整个公式高度。
        assertTrue("光标应该还在指数里：top=${s[1]} bottom=${s[2]} h=${l.height}",
            s[2] - s[1] < l.height * 0.9f)
        assertTrue("光标应该在基线上方：top=${s[1]}", s[1] < l.height * 0.5f)
    }

    @Test
    fun `末尾补一个空格之后光标回到基线`() {
        // "右边空白处也可以输入"的实现：点右侧空白 → 源码末尾补一个空格、光标置末
        // （见 FunctionEditor.escapeToEnd）。空格被 tokenize 跳过，排版一个像素都不变，
        // 但下标 4 已经大于所有结构内部的停靠点 → stopFor 落到最外层行末。
        val l = layout("x^2 ")
        val s = l.stopSnapshot(4)
        assertTrue("光标应该贯穿整行：top=${s[1]} bottom=${s[2]} h=${l.height}",
            s[2] - s[1] >= l.height * 0.9f)
        assertEquals("空格不该被排成字形", 2, l.glyphSnapshots.size)
    }

    @Test
    fun `x^ 之后的光标仍然落在指数槽里`() {
        // 空指数必须"看得见该往哪填" —— 不能因为上面那条规则被推到右边空白处
        val s = layout("x^").stopSnapshot(2)
        assertEquals("下标记 1 = 空槽位", 1f, s[3], 0f)
    }

    @Test
    fun `指数里的括号式子可以继续输入且末尾回到基线`() {
        // 用户 1.2.65："指数是输入能定位到指数位外但是光标还是在指数上面。"
        //   * 6 = 指数内容（那个 `1`）的末尾 —— 还在指数里；
        //   * 7 = 那个看不见的 `)` 之后 —— 已经出了指数，光标必须贯穿整行。
        val l = layout("x^(2+1)")
        val inside = l.stopSnapshot(6)
        assertTrue("光标应该还在指数里：top=${inside[1]} bottom=${inside[2]} h=${l.height}",
            inside[2] - inside[1] < l.height * 0.9f)
        assertTrue("光标应该在基线上方：top=${inside[1]}", inside[1] < l.height * 0.5f)
        val outside = l.stopSnapshot(7)
        assertTrue(
            "出了指数之后光标应该贯穿整行：top=${outside[1]} bottom=${outside[2]} h=${l.height}",
            outside[2] - outside[1] >= l.height * 0.9f
        )
    }

    @Test
    fun `分数末尾的光标回到整行基线`() {
        // 用户 1.2.65："分数线是不能定位到分数外也不能输入到分数外。"
        // 点右侧空白时输入框在末尾补一个空格（光标 = 4 > 所有停靠点）：
        // 这一个下标上同时有"分母那一小条"和"整行行末"，必须取后者，
        // 否则光标画在分母里，看着像没定位出来。
        val l = layout("1÷2 ")
        val s = l.stopSnapshot(4)
        assertTrue("光标应该贯穿整行：top=${s[1]} bottom=${s[2]} h=${l.height}",
            s[2] - s[1] >= l.height * 0.9f)
    }

    @Test
    fun `分数右边补空格之后继续打的字母在整条式子这一层`() {
        // "输入到分数外"的另一半：末尾补过空格之后打一个字母，它必须排成**正文大小**、
        // 并在分式右边（整条式子这一层），而不是被分母吃掉（分母是 0.66 倍字号）。
        val g = layout("1÷2 x").glyphSnapshots
        val x = g.first { it.first == "x" }
        assertEquals("x 应该是正文大小（分式外面）", 32f, x.third, 0.01f)
    }

    @Test
    fun `指数里用加减连接也不画括号`() {
        // 用户 1.2.63 原话："指数位只要把两个单项式用加号或者减号连接，括号就会显示出来，
        // 你把括号做成始终不显示。" —— `^(` 只是源码里的分界符，不进语法树。
        val g = layout("x^(2+1)").glyphSnapshots
        assertTrue("指数里的隐式括号不该画：$g", g.none { it.first == "(" || it.first == ")" })
        assertEquals(listOf("x", "2", "+", "1"), g.map { it.first })
    }

    @Test
    fun `log 的底数位可以继续打式子`() {
        // 用户原话："还有对数函数的底数位也是，只能输入一个数，我要是想让一个式子
        // 当底数怎么办呢" —— 底数走的是 parseSum，`2+1` 这种式子必须能打下去，
        // 而且光标每一步都停在下标区里（不在整行行末、也不在真数槽位上）。
        val l = layout("log(2+1,)")
        val baseEnd = l.stopSnapshot(7)
        val argSlot = l.stopSnapshot(8)
        assertTrue("光标应该还停在底数这一小行里：h=${baseEnd[2] - baseEnd[1]} total=${l.height}",
            baseEnd[2] - baseEnd[1] < l.height * 0.9f)
        // 底数画在右下角（基线下方），真数槽位在基线上 —— 底数停靠点的上沿更低
        assertTrue("底数区应该在真数槽位下方：base=${baseEnd[1]} arg=${argSlot[1]}",
            baseEnd[1] > argSlot[1])
        assertEquals("真数槽位仍然是一块空槽", 1f, argSlot[3], 0f)
    }

    @Test
    fun `指数还没填时按加号，加号属于整条式子而不是指数`() {
        // 否则 `x^` 之后按 `+`，parseAtom 会把 `+` 吃成指数里的一个叶子（x^{+}），
        // 用户"先不输入指数、先输入其他东西"就完全走不通。
        val g = layout("x^+1").glyphSnapshots
        val plus = g.first { it.first == "+" }
        val one = g.first { it.first == "1" }
        assertEquals("加号必须是正文大小，不是缩小的上标", one.third, plus.third, 0.01f)
    }

    @Test
    fun `指数还没填时点右边空白也能回到基线`() {
        // `x^` 的空指数槽位下标就是源码末尾 —— 补空格之后光标必须仍然落回基线上，
        // 不能被槽位吸回去。
        val l = layout("x^ ")
        val s = l.stopSnapshot(3)
        assertTrue("光标应该贯穿整行：top=${s[1]} bottom=${s[2]} h=${l.height}",
            s[2] - s[1] >= l.height * 0.9f)
        assertEquals("空指数槽位本身仍然在（点它还能回去填）", 1f, l.stopSnapshot(2)[3], 0f)
    }

    @Test
    fun `点分式上半部分落在分子、下半部分落在分母`() {
        // 用户原话："除号输入之后分数线左边不能输入" —— 分子槽和分母槽**居中对齐**，
        // x 完全重叠，只按 x 找最近点永远命中分母（上一版还"同样近取后面的"）。
        val l = layout("÷")
        val numY = l.stopSnapshot(0)[1] + 1f
        val denY = l.stopSnapshot(1)[2] - 1f
        assertEquals("点上半部分 = 分子", 0, l.indexAt(0f, numY))
        assertEquals("点下半部分 = 分母", 1, l.indexAt(0f, denY))
    }

    @Test
    fun `指数里只有一个原子时不画括号`() {
        // `^` 键插的是 `^()`，用户打一个数本该看到 x²，而不是 x^(2)
        val g = layout("x^(2)").glyphSnapshots.map { it.first }
        assertTrue("不该出现圆括号：$g", "(" !in g && ")" !in g)
        assertTrue("指数里应该只有 2", "2" in g)
    }

    // ---- 8. 隐式乘法：`2x`、`x^(2x)`、`log(6x,9)`（2026-10-29 第三次反馈）----
    //
    // 用户原话："x^(2x)可以解析，但是显示的时候只有2在指数位，x就被丢到右边去了；
    // log的底数输入也是这样，甚至底数是6x的时候就是显示log(6)x,9)，解析完就变成
    // log(6x,9)了，而且直接不显示底数位置了，直接显示原始式子。"
    //
    // 根因：`parseProduct` 只认显式乘号，`2x` 的 `x` 停在乘积外面，
    // 再被 upper 结构当"没吃掉的内容"甩到兜底里画成原始文本。

    @Test
    fun `隐式乘法让指数能放下多因子式子`() {
        val g = layout("x^(2x)").glyphSnapshots
        val xs = g.filter { it.first == "x" }
        assertEquals("底数一个 x、指数一个 x：$g", 2, xs.size)
        assertTrue(
            "指数里的 x 必须是缩小号：${xs.map { it.third }}",
            xs.last().third < xs.first().third
        )
        assertEquals("`^(2x)` 不需要画括号", 0, g.count { it.first == ")" })
    }

    @Test
    fun `log 的底数可以是多因子式子`() {
        // 以前 `6x` 只解析出 `6`，后面的 `x,9)` 掉进兜底当原始文本 ——
        // 屏幕上就是 `log(6)x,9)`：多出来的逗号、多余的右括号、并且没有底数位。
        val g = layout("log(6x,9)").glyphSnapshots.map { it.first }
        assertEquals("逗号不该被当原始文本画出来：$g", 0, g.count { it == "," })
        assertEquals("只该有一个参数右括号：$g", 1, g.count { it == ")" })
        assertTrue("底数里的 6 要画出来：$g", "6" in g)
        assertTrue("底数里的 x 要画出来：$g", "x" in g)
    }

    @Test
    fun `隐式乘法不把减号读成负号`() {
        // `2-3` 是减法，不能读成 `2 · (−3)`：三个字形、`3` 与 `2` 同号
        val g = layout("2-3").glyphSnapshots
        assertEquals("2-3 应该正好三个字形：$g", 3, g.size)
        val two = g.first { it.first == "2" }
        val three = g.first { it.first == "3" }
        assertEquals("3 必须是正文大小，不是被缩小的因子", two.third, three.third, 0.01f)
    }

    @Test
    fun `等号右边继续按语法解析而不是原始文本`() {
        // 函数列表改成排版显示之后，隐式函数的 `x^2+y^2=1` 也必须整条排出来
        val g = layout("x^2+y^2=1").glyphSnapshots
        assertTrue("等号要画出来：$g", g.any { it.first == "=" })
        assertEquals("等号右边的 1 是一个数字叶子", 1, g.count { it.first == "1" })
    }

    @Test
    fun `键盘插的 x^() 也有一块空指数阴影`() {
        // `^` 键插的是 `^()`（隐式括号）：按下去还没输入时，
        // 指数位置必须有阴影可点 —— 靠 parsePower 的 expStart 登记。
        val s = layout("x^()").stopSnapshot(3)
        assertEquals("下标记 1 = 空槽位", 1f, s[3], 0f)
    }

    @Test
    fun `x^() 右边指数位外面有一个可点输入位`() {
        // 用户 1.2.64 原话："x^() 的右边空白处还是不能单点输入，
        // 你就给它的右边指数位外面的位置加个可选输入。"
        //
        // 源码下标 4 = 那个看不见的 `)` 之后：点阴影框的右半边应该落到这里
        // （打字接在整条式子后面、画在基线上），不能被吸回槽位（3）。
        val l = layout("x^()")
        val outside = l.stopSnapshot(4)
        assertEquals("指数外面不是空槽位", 0f, outside[3], 0f)
        assertTrue(
            "这个输入位应该在阴影框右边：out=${outside[0]} slot=${l.stopSnapshot(3)[0]}",
            outside[0] > l.stopSnapshot(3)[0]
        )
        assertEquals("点框右半边落在指数外面", 4, l.indexAt(l.contentRight))
        assertEquals("框本身仍然能点回指数", 3, l.indexAt(l.stopSnapshot(3)[0]))
    }
}
