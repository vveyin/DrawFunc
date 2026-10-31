package com.functiondrawer.watch.engine

/**
 * 表达式无法解析时抛出。带字符下标，UI 可以据此在自绘键盘上把出错位置标红。
 */
class ExpressionException(
    message: String,
    val position: Int
) : Exception(message)

internal enum class TokenType {
    NUMBER, IDENT, OP, LPAREN, RPAREN, COMMA, BAR, EQUALS, EOF
}

internal class Token(
    val type: TokenType,
    val text: String,
    val pos: Int,
    val value: Double = 0.0
) {
    override fun toString(): String = "$type('$text')@$pos"
}

/**
 * 词法分析。
 *
 * 除了 ASCII，还刻意接受数学符号和手写习惯：
 *   × · ⋅ → 乘   ÷ → 除   − (U+2212) → 减
 *   √ → sqrt   ∛ → cbrt   ∞ → inf
 * 这样自绘键盘可以直接插入好看的符号，而不用去管用户怎么输入。
 *
 * 单位函数（如 π、θ）在 Unicode 里属于字母类，天然会被识别成标识符。
 *
 * 词法层**不做名字分类**：`a b c d`（可绑定参数，见 [ParameterSet]）、`e`（自然常数）、
 * `x`、`sin` 在这里都只是 IDENT，到底算采样变量、可绑定参数、常量还是函数，
 * 由 [Parser.parseIdent] 按"采样变量 → 可绑定参数 → 常量 → 函数"的顺序判定。
 * 所以加 a b c d 不需要动这个词法器。
 */
internal class Lexer(private val src: String) {

    private var i = 0
    private val out = ArrayList<Token>()

    fun tokenize(): List<Token> {
        while (true) {
            skipWhitespace()
            if (i >= src.length) break
            val start = i
            val c = src[i]
            when {
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) ->
                    out.add(readNumber())
                isIdentStart(c) -> out.add(readIdent())
                else -> readSymbol(start)
            }
        }
        out.add(Token(TokenType.EOF, "", src.length))
        return out
    }

    private fun skipWhitespace() {
        while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun readIdent(): Token {
        val start = i
        while (i < src.length && isIdentPart(src[i])) i++
        return Token(TokenType.IDENT, src.substring(start, i), start)
    }

    private fun readNumber(): Token {
        val start = i
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        // 指数部分只有在后面确实跟着数字时才吞掉。
        // 否则 "2e" 应该被理解成 2*e（欧拉数），而不是一个残缺的科学计数法。
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            var j = i + 1
            if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
            if (j < src.length && src[j].isDigit()) {
                i = j
                while (i < src.length && src[i].isDigit()) i++
            }
        }
        val text = src.substring(start, i)
        val v = text.toDoubleOrNull()
            ?: throw ExpressionException("数字格式不正确：$text", start)
        return Token(TokenType.NUMBER, text, start, v)
    }

    private fun readSymbol(start: Int) {
        val c = src[i]

        // 双字符运算符 ** 视同 ^
        if (c == '*' && i + 1 < src.length && src[i + 1] == '*') {
            i += 2
            out.add(Token(TokenType.OP, "^", start))
            return
        }

        i++
        val token = when (c) {
            '+', '-' -> Token(TokenType.OP, c.toString(), start)
            '−' -> Token(TokenType.OP, "-", start)          // U+2212 真减号
            '*', '×', '·', '⋅' -> Token(TokenType.OP, "*", start)
            '/', '÷' -> Token(TokenType.OP, "/", start)
            '^' -> Token(TokenType.OP, "^", start)
            '%' -> Token(TokenType.OP, "%", start)
            '(', '[' -> Token(TokenType.LPAREN, "(", start)
            ')', ']' -> Token(TokenType.RPAREN, ")", start)
            ',', ';' -> Token(TokenType.COMMA, ",", start)
            '|' -> Token(TokenType.BAR, "|", start)
            '=' -> Token(TokenType.EQUALS, "=", start)
            '√' -> Token(TokenType.IDENT, "sqrt", start)
            '∛' -> Token(TokenType.IDENT, "cbrt", start)
            '∞' -> Token(TokenType.IDENT, "inf", start)
            '!' -> Token(TokenType.OP, "!", start)
            else -> throw ExpressionException("看不懂这个字符：'$c'", start)
        }
        out.add(token)
    }
}
