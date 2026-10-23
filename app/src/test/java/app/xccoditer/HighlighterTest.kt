package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class HighlighterTest {
    @Test
    fun detectsLanguageByExtension() {
        assertEquals(Lang.PYTHON, Lang.forFileName("main.py"))
        assertEquals(Lang.TYPESCRIPT, Lang.forFileName("a.tsx"))
        assertEquals(Lang.PLAIN, Lang.forFileName("notes"))
    }

    @Test
    fun pythonTokens() {
        val src = "def f(): # note\n    return \"x\" + 42"
        val t = Highlighter.tokenize(src, Lang.PYTHON)
        fun text(k: TokenKind) = t.filter { it.kind == k }.map { src.substring(it.start, it.end) }
        assertEquals(listOf("def", "return"), text(TokenKind.KEYWORD))
        assertEquals(listOf("# note"), text(TokenKind.COMMENT))
        assertEquals(listOf("\"x\""), text(TokenKind.STRING))
        assertEquals(listOf("42"), text(TokenKind.NUMBER))
    }

    @Test
    fun keywordInsideStringIsStringOnly() {
        val src = "x = \"def\""
        val t = Highlighter.tokenize(src, Lang.PYTHON)
        assertEquals(listOf(TokenKind.STRING), t.map { it.kind })
    }

    @Test
    fun blockCommentCStyle() {
        val src = "/* if */ int x;"
        val t = Highlighter.tokenize(src, Lang.C)
        assertEquals(TokenKind.COMMENT, t.first().kind)
        assertEquals(listOf("int"), t.filter { it.kind == TokenKind.KEYWORD }.map { src.substring(it.start, it.end) })
    }

    @Test
    fun sqlKeywordsCaseInsensitive() {
        val t = Highlighter.tokenize("SELECT * FROM t", Lang.SQL)
        assertEquals(2, t.count { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun hugeFilesSkipped() {
        assertTrue(Highlighter.tokenize("a".repeat(Highlighter.MAX_CHARS + 1), Lang.PYTHON).isEmpty())
    }
}
