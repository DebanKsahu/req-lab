package com.reqlab.editor.core

import kotlin.test.*

class JavaScriptModeTest {

    @BeforeTest
    fun setup() {
        LanguageRegistry.registerBuiltins()
    }

    @Test
    fun validJsNoErrors() {
        val js = "function hello() { return 42; }"
        val errors = JavaScriptMode.validate(js)
        assertEquals(0, errors.size)
    }

    @Test
    fun unmatchedBraceReportsError() {
        val js = "function hello() { return 42; "
        val errors = JavaScriptMode.validate(js)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun nestedBracesValid() {
        val js = """
function outer() {
    if (true) {
        for (let i = 0; i < 10; i++) {
            console.log(i);
        }
    }
}
        """.trimIndent()
        val errors = JavaScriptMode.validate(js)
        assertEquals(0, errors.size)
    }

    @Test
    fun tokenizeKeyword() {
        val (tokens, _) = JavaScriptMode.tokenizeLine("const x = 42;", 1, null)
        assertTrue(tokens.any { it.type == TokenType.KEYWORD })
    }

    @Test
    fun tokenizeString() {
        val (tokens, _) = JavaScriptMode.tokenizeLine("""const s = "hello";""", 1, null)
        assertTrue(tokens.any { it.type == TokenType.STRING })
    }

    @Test
    fun tokenizeNumber() {
        val (tokens, _) = JavaScriptMode.tokenizeLine("const n = 3.14;", 1, null)
        assertTrue(tokens.any { it.type == TokenType.NUMBER })
    }

    @Test
    fun tokenizeComment() {
        val (tokens, _) = JavaScriptMode.tokenizeLine("// this is a comment", 1, null)
        assertTrue(tokens.any { it.type == TokenType.COMMENT })
    }

    @Test
    fun foldingRegionsForJs() {
        val js = "function test() {\n  if (true) {\n    return 1;\n  }\n}"
        val doc = EditorDocument.create(js)
        val regions = JavaScriptMode.foldingRegions(doc)
        assertTrue(regions.isNotEmpty(), "Should detect fold regions for JS blocks")
    }

    @Test
    fun bracketMatchingWithStrings() {
        // Braces inside strings should not be counted
        val js = """const s = "{ not a brace }";"""
        val errors = JavaScriptMode.validate(js)
        assertEquals(0, errors.size)
    }

    @Test
    fun bracketMatchingWithComments() {
        // Braces inside comments should not be counted
        val js = """
// { not a brace }
function f() {
    return 1;
}
        """.trimIndent()
        val errors = JavaScriptMode.validate(js)
        assertEquals(0, errors.size)
    }

    @Test
    fun format_compact_function_multiline() {
        val input = "function foo(){const x=1;return x;}"
        val formatted = JavaScriptMode.format(input)
        assertNotEquals(input, formatted)
        assertTrue(formatted.contains("\n"), "formatted output should be multiline")
        assertTrue(formatted.contains("return x;"))
    }

    @Test
    fun format_does_not_break_for_header_semicolons() {
        val input = "for(let i=0;i<10;i++){console.log(i);}"
        val formatted = JavaScriptMode.format(input)
        assertTrue(formatted.contains("for(let i=0;i<10;i++)"), "for header must remain on one line")
    }

    @Test
    fun format_preserves_string_with_semicolon() {
        val input = "const s=\"a;b\";console.log(s);"
        val formatted = JavaScriptMode.format(input)
        assertTrue(formatted.contains("\"a;b\""), "semicolon inside string must be preserved")
    }

    @Test
    fun format_preserves_comments() {
        val input = "const x=1; // keep this comment"
        val formatted = JavaScriptMode.format(input)
        assertTrue(formatted.contains("// keep this comment"))
    }
}
