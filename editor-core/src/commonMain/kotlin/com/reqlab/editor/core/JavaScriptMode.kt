package com.reqlab.editor.core

object JavaScriptMode : LanguageModeProvider {
    override val mode = LanguageMode.JAVASCRIPT
    override val displayName = "JavaScript"
    override val fileExtensions = listOf("js", "mjs", "cjs", "jsx")
    override val mimeTypes = listOf("application/javascript", "text/javascript")
    override val foldingStyle = FoldingStyle.BRACE

    private val KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false",
        "finally", "for", "function", "if", "import", "in", "instanceof",
        "let", "new", "null", "return", "super", "switch", "this", "throw",
        "true", "try", "typeof", "undefined", "var", "void", "while", "with",
        "yield", "async", "await", "of", "static", "get", "set",
    )

    data class JsTokenState(val inBlockComment: Boolean = false, val inTemplateLiteral: Boolean = false, val inString: Char? = null)

    override fun tokenizeLine(line: String, lineNumber: Int, state: Any?): Pair<List<Token>, Any?> {
        val tokens = mutableListOf<Token>()
        val prev = state as? JsTokenState ?: JsTokenState()
        var i = 0
        var inBlock = prev.inBlockComment
        var inTmpl = prev.inTemplateLiteral
        var inString = prev.inString

        // Handle continuation from previous line
        if (inBlock) {
            val endIdx = line.indexOf("*/")
            if (endIdx >= 0) {
                tokens.add(Token(0, endIdx + 2, TokenType.COMMENT))
                i = endIdx + 2
                inBlock = false
            } else {
                tokens.add(Token(0, line.length, TokenType.COMMENT))
                return tokens to JsTokenState(true, inTmpl, inString)
            }
        }

        if (inTmpl) {
            val endIdx = findUnescaped(line, '`', 0)
            if (endIdx >= 0) {
                tokens.add(Token(0, endIdx + 1, TokenType.STRING))
                i = endIdx + 1
                inTmpl = false
            } else {
                tokens.add(Token(0, line.length, TokenType.STRING))
                return tokens to JsTokenState(false, true, inString)
            }
        }

        if (inString != null) {
            val endIdx = findUnescaped(line, inString, 0)
            if (endIdx >= 0) {
                tokens.add(Token(0, endIdx + 1, TokenType.STRING))
                i = endIdx + 1
                inString = null
            } else {
                tokens.add(Token(0, line.length, TokenType.STRING))
                return tokens to JsTokenState(false, inTmpl, inString)
            }
        }

        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> {
                    i++
                }
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    tokens.add(Token(i, line.length, TokenType.COMMENT))
                    i = line.length
                }
                c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                    val endIdx = line.indexOf("*/", i + 2)
                    if (endIdx >= 0) {
                        tokens.add(Token(i, endIdx + 2, TokenType.COMMENT))
                        i = endIdx + 2
                    } else {
                        tokens.add(Token(i, line.length, TokenType.COMMENT))
                        inBlock = true
                        i = line.length
                    }
                }
                c == '"' || c == '\'' -> {
                    val end = findUnescaped(line, c, i + 1)
                    if (end >= 0) {
                        tokens.add(Token(i, end + 1, TokenType.STRING))
                        i = end + 1
                    } else {
                        tokens.add(Token(i, line.length, TokenType.STRING))
                        inString = c
                        i = line.length
                    }
                }
                c == '`' -> {
                    val endIdx = findUnescaped(line, '`', i + 1)
                    if (endIdx >= 0) {
                        tokens.add(Token(i, endIdx + 1, TokenType.STRING))
                        i = endIdx + 1
                    } else {
                        tokens.add(Token(i, line.length, TokenType.STRING))
                        inTmpl = true
                        i = line.length
                    }
                }
                c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
                    val end = scanNumber(line, i)
                    tokens.add(Token(i, end, TokenType.NUMBER))
                    i = end
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val end = scanIdent(line, i)
                    val word = line.substring(i, end)
                    tokens.add(Token(i, end, if (word in KEYWORDS) TokenType.KEYWORD else TokenType.PLAIN))
                    i = end
                }
                c in "{}[]()" -> {
                    tokens.add(Token(i, i + 1, TokenType.PUNCTUATION))
                    i++
                }
                c in "=+-*%!&|^~<>?:" -> {
                    tokens.add(Token(i, i + 1, TokenType.OPERATOR))
                    i++
                }
                c in ";,." -> {
                    tokens.add(Token(i, i + 1, TokenType.PUNCTUATION))
                    i++
                }
                else -> {
                    tokens.add(Token(i, i + 1, TokenType.PLAIN))
                    i++
                }
            }
        }
        return tokens to JsTokenState(inBlock, inTmpl, inString)
    }

    private fun findUnescaped(line: String, target: Char, start: Int): Int {
        var i = start
        while (i < line.length) {
            if (line[i] == '\\') {
                i += 2
                continue
            }
            if (line[i] == target) return i
            i++
        }
        return -1
    }

    private fun scanNumber(line: String, start: Int): Int {
        var i = start
        if (i + 1 < line.length && line[i] == '0' && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
            i += 2
            while (i < line.length && line[i].isLetterOrDigit()) i++
            return i
        }
        while (i < line.length && line[i].isDigit()) i++
        if (i < line.length && line[i] == '.') {
            i++
            while (i < line.length && line[i].isDigit()) i++
        }
        if (i < line.length && (line[i] == 'e' || line[i] == 'E')) {
            i++
            if (i < line.length && (line[i] == '+' || line[i] == '-')) i++
            while (i < line.length && line[i].isDigit()) i++
        }
        return i
    }

    private fun scanIdent(line: String, start: Int): Int {
        var i = start
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
        return i
    }

    override fun foldingRegions(document: EditorDocument): List<FoldRegion> {
        val regions = mutableListOf<FoldRegion>()
        val stack = ArrayDeque<Int>()
        val text = document.text
        var line = 1
        var inStr: Char? = null
        var inLC = false
        var inBC = false
        var i = 0
        var escaped = false
        
        while (i < text.length) {
            val c = text[i]
            
            if (c == '\n') {
                line++
                inLC = false
                escaped = false
                i++
                continue
            }
            
            // Handle escape sequences
            if (escaped) {
                escaped = false
                i++
                continue
            }
            
            if (!inLC && !inBC && c == '\\') {
                escaped = true
                i++
                continue
            }
            
            if (inStr == null && !inLC && !inBC && c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                inBC = true
                i += 2
                continue
            }
            
            if (inBC && c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                inBC = false
                i += 2
                continue
            }
            
            if (inBC) {
                i++
                continue
            }
            
            if (inStr == null && !inLC && c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLC = true
                i++
                continue
            }
            
            if (inLC) {
                i++
                continue
            }
            
            if (inStr == null && (c == '"' || c == '\'' || c == '`')) {
                inStr = c
            } else if (inStr != null && c == inStr && !escaped) {
                inStr = null
            }
            
            if (inStr != null) {
                i++
                continue
            }
            
            when (c) {
                '{' -> stack.addLast(line)
                '}' -> {
                    if (stack.isNotEmpty()) {
                        val s = stack.removeLast()
                        if (line > s) regions.add(FoldRegion(s, line))
                    }
                }
            }
            i++
        }
        return regions.sortedBy { it.startLine }
    }

    override fun validate(text: String): List<InlineEditorError> {
        if (text.isBlank()) return emptyList()
        val errors = mutableListOf<InlineEditorError>()
        val stack = ArrayDeque<Triple<Char, Int, Int>>()
        var line = 1
        var col = 0
        var inLC = false
        var inBC = false
        var inStr: Char? = null
        var i = 0
        var escaped = false
        
        while (i < text.length) {
            val c = text[i]
            
            if (c == '\n') {
                line++
                col = 0
                inLC = false
                escaped = false
            } else {
                col++
            }
            
            // Handle escape sequences
            if (escaped) {
                escaped = false
                i++
                continue
            }
            
            if (!inLC && !inBC && c == '\\') {
                escaped = true
                i++
                continue
            }
            
            if (inStr == null && !inLC && !inBC && c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                inBC = true
                i += 2
                continue
            }
            
            if (inBC && c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                inBC = false
                i += 2
                continue
            }
            
            if (inBC) {
                i++
                continue
            }
            
            if (inStr == null && !inLC && c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLC = true
                i++
                continue
            }
            
            if (inLC) {
                i++
                continue
            }
            
            if (inStr == null && (c == '"' || c == '\'' || c == '`')) {
                inStr = c
            } else if (inStr != null && c == inStr && !escaped) {
                inStr = null
            }
            
            if (inStr != null) {
                i++
                continue
            }
            
            when (c) {
                '{', '[', '(' -> stack.addLast(Triple(c, line, col))
                '}', ']', ')' -> {
                    val expectedMatch = when (c) {
                        '}' -> '{'
                        ']' -> '['
                        else -> '('
                    }
                    if (stack.isEmpty()) {
                        errors += InlineEditorError(line, col, "Unexpected '$c'")
                    } else if (stack.last().first != expectedMatch) {
                        val expectedClose = when (stack.last().first) {
                            '{' -> '}'
                            '[' -> ']'
                            else -> ')'
                        }
                        errors += InlineEditorError(line, col, "Expected '$expectedClose' but got '$c' (opened at line ${stack.last().second})")
                        stack.removeLast()
                    } else {
                        stack.removeLast()
                    }
                }
            }
            i++
        }
        
        for ((open, l, c2) in stack.asReversed()) {
            val expectedClose = when (open) {
                '{' -> '}'
                '[' -> ']'
                else -> ')'
            }
            errors += InlineEditorError(l, c2, "Unclosed '$open' — expected '$expectedClose'")
        }
        return errors
    }

    override fun format(text: String): String {
        if (text.isBlank()) return text

        val input = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(input.length + 64)

        var i = 0
        var indent = 0
        var atLineStart = true
        var pendingSpace = false

        var inSingle = false
        var inDouble = false
        var inTemplate = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false

        var inForHeader = false
        var forParenDepth = 0

        fun appendIndentIfNeeded() {
            if (!atLineStart) return
            repeat(indent.coerceAtLeast(0)) { out.append("  ") }
            atLineStart = false
        }

        fun appendNewLine() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            atLineStart = true
            pendingSpace = false
        }

        fun appendChar(c: Char) {
            appendIndentIfNeeded()
            out.append(c)
            if (c == '\n') {
                atLineStart = true
                pendingSpace = false
            }
        }

        fun flushPendingSpace() {
            if (pendingSpace && out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') {
                out.append(' ')
            }
            pendingSpace = false
        }

        fun appendToken(token: String) {
            appendIndentIfNeeded()
            flushPendingSpace()
            out.append(token)
        }

        fun peek(offset: Int = 1): Char? = input.getOrNull(i + offset)

        while (i < input.length) {
            val c = input[i]
            val n = peek()

            // Handle escape sequences in strings
            if (escaped && (inSingle || inDouble || inTemplate)) {
                out.append(c)
                escaped = false
                i++
                continue
            }

            // Line comment
            if (inLineComment) {
                out.append(c)
                if (c == '\n') {
                    inLineComment = false
                    atLineStart = true
                    pendingSpace = false
                }
                i++
                continue
            }

            // Block comment
            if (inBlockComment) {
                out.append(c)
                if (c == '\n') {
                    atLineStart = true
                    pendingSpace = false
                }
                if (c == '*' && n == '/') {
                    out.append('/')
                    i += 2
                    inBlockComment = false
                    pendingSpace = true
                } else {
                    i++
                }
                continue
            }

            // Inside string / template literal
            if (inSingle || inDouble || inTemplate) {
                out.append(c)
                if (c == '\\') {
                    escaped = true
                } else if ((inSingle && c == '\'') || (inDouble && c == '"') || (inTemplate && c == '`')) {
                    when {
                        inSingle -> inSingle = false
                        inDouble -> inDouble = false
                        inTemplate -> inTemplate = false
                    }
                }
                i++
                continue
            }

            // Whitespace
            if (c.isWhitespace()) {
                if (c == '\n') appendNewLine() else pendingSpace = true
                i++
                continue
            }

            // // line comment
            if (c == '/' && n == '/') {
                appendIndentIfNeeded()
                flushPendingSpace()
                out.append("//")
                inLineComment = true
                i += 2
                continue
            }

            // /* block comment
            if (c == '/' && n == '*') {
                appendIndentIfNeeded()
                flushPendingSpace()
                out.append("/*")
                inBlockComment = true
                i += 2
                continue
            }

            // String / template literals
            if (c == '\'' || c == '"' || c == '`') {
                appendIndentIfNeeded()
                flushPendingSpace()
                out.append(c)
                when (c) {
                    '\'' -> inSingle = true
                    '"' -> inDouble = true
                    '`' -> inTemplate = true
                }
                i++
                continue
            }

            // Identifiers & keywords
            if (c.isLetter() || c == '_' || c == '$') {
                var end = i + 1
                while (end < input.length) {
                    val ch = input[end]
                    if (!ch.isLetterOrDigit() && ch != '_' && ch != '$') break
                    end++
                }
                val token = input.substring(i, end)

                // Handle else/catch/finally after closing brace
                if ((token == "else" || token == "catch" || token == "finally") &&
                    out.isNotEmpty() && out.toString().trimEnd().last() == '}'
                ) {
                    while (out.isNotEmpty() && out.last() == '\n') out.deleteCharAt(out.length - 1)
                    atLineStart = false
                    out.append(' ')
                    out.append(token)
                } else {
                    appendToken(token)
                }

                if (token == "for") {
                    inForHeader = true
                    forParenDepth = 0
                    pendingSpace = true
                }

                i = end
                continue
            }

            // Numbers
            if (c.isDigit() || (c == '.' && n != null && n.isDigit())) {
                var end = i
                if (c == '0' && (n == 'x' || n == 'X')) {
                    end += 2
                    while (end < input.length && (input[end].isDigit() ||
                                input[end] in 'a'..'f' || input[end] in 'A'..'F')) end++
                } else {
                    while (end < input.length && input[end].isDigit()) end++
                    if (end < input.length && input[end] == '.') {
                        end++
                        while (end < input.length && input[end].isDigit()) end++
                    }
                    if (end < input.length && (input[end] == 'e' || input[end] == 'E')) {
                        end++
                        if (end < input.length && (input[end] == '+' || input[end] == '-')) end++
                        while (end < input.length && input[end].isDigit()) end++
                    }
                }
                appendToken(input.substring(i, end))
                i = end
                continue
            }

            // Structural & punctuation
            when (c) {
                '(' -> {
                    val lastTrimmed = out.toString().trimEnd()
                    val spaceBeforeParen = lastTrimmed.endsWith("if") ||
                            lastTrimmed.endsWith("for") ||
                            lastTrimmed.endsWith("while") ||
                            lastTrimmed.endsWith("switch") ||
                            lastTrimmed.endsWith("catch")
                    appendIndentIfNeeded()
                    if (spaceBeforeParen && out.isNotEmpty() && out.last() != ' ') {
                        out.append(' ')
                    }
                    pendingSpace = false
                    out.append('(')
                    if (inForHeader) {
                        forParenDepth++
                    }
                }
                ')' -> {
                    appendChar(')')
                    if (inForHeader) {
                        forParenDepth--
                        if (forParenDepth <= 0) {
                            inForHeader = false
                            forParenDepth = 0
                        }
                    }
                    pendingSpace = false
                }
                '[' -> {
                    appendIndentIfNeeded()
                    pendingSpace = false
                    out.append('[')
                }
                ']' -> {
                    appendChar(']')
                    pendingSpace = false
                }
                '{' -> {
                    appendIndentIfNeeded()
                    if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                    out.append('{')
                    pendingSpace = false
                    indent++
                    appendNewLine()
                }
                '}' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    if (!atLineStart) appendNewLine()
                    appendIndentIfNeeded()
                    out.append('}')
                    pendingSpace = false

                    // Check if we need to suppress newline after }
                    val followedByContinuation = run {
                        var j = i + 1
                        while (j < input.length && input[j].isWhitespace()) j++
                        if (j >= input.length) return@run false
                        val nxt = input[j]
                        if (nxt == ')' || nxt == ']' || nxt == ',' || nxt == ';') return@run true
                        val rest = input.substring(j)
                        rest.startsWith("else") || rest.startsWith("catch") || rest.startsWith("finally")
                    }
                    if (!followedByContinuation) appendNewLine()
                }
                ';' -> {
                    while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    out.append(';')
                    pendingSpace = false
                    if (!inForHeader) appendNewLine()
                }
                ',' -> {
                    while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    out.append(',')
                    pendingSpace = true
                }
                ':' -> {
                    out.append(':')
                    pendingSpace = true
                }
                // Operators
                '=', '+', '-', '*', '%', '!', '&', '|', '^', '~', '<', '>', '?', '/' -> {
                    val twoChar = if (n != null) "$c$n" else ""
                    val threeChar = if (n != null && peek(2) != null) "$c$n${peek(2)}" else ""

                    val (opToken, advance) = when {
                        threeChar in setOf("===", "!==", ">>>", "<<=", ">>=") -> threeChar to 3
                        twoChar in setOf(
                            "==", "!=", "<=", ">=", "=>", "+=", "-=", "*=", "/=",
                            "%=", "&&", "||", "??", "++", "--", "**", ">>", "<<",
                            "&=", "|=", "^="
                        ) -> twoChar to 2
                        else -> c.toString() to 1
                    }

                    val isUnary = opToken in setOf("!", "~")
                    val isPostfix = opToken in setOf("++", "--") &&
                            out.isNotEmpty() &&
                            (out.last().isLetterOrDigit() || out.last() == ')' || out.last() == ']')

                    when {
                        isUnary || isPostfix -> {
                            pendingSpace = false
                            out.append(opToken)
                        }
                        else -> {
                            appendIndentIfNeeded()
                            if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
                            out.append(opToken)
                            pendingSpace = true
                        }
                    }
                    i += advance
                    continue
                }
                '.' -> {
                    pendingSpace = false
                    appendChar('.')
                }
                else -> appendToken(c.toString())
            }

            i++
        }

        return out.toString()
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trimEnd()
    }
}