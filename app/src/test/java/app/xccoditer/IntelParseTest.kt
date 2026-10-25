package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class IntelParseTest {
    @Test fun lineCol() {
        assertEquals(1 to 0, IntelParse.lineCol("abc", 0))
        assertEquals(1 to 3, IntelParse.lineCol("abc", 3))
        assertEquals(2 to 0, IntelParse.lineCol("abc\nde", 4))
        assertEquals(2 to 2, IntelParse.lineCol("abc\nde", 6))
        assertEquals(3 to 0, IntelParse.lineCol("a\n\nb", 3))
        assertEquals(1 to 1, IntelParse.lineCol("a", 99))
    }
    @Test fun diagnostics() {
        val d = IntelParse.diagnostics("""[{"line":2,"col":0,"severity":"error","message":"bad"}]""")
        assertEquals(IntelDiag(2, 0, "error", "bad"), d.single())
        assertEquals("error  line 2: bad", IntelParse.diagRows(d).single().label)
        assertTrue(IntelParse.diagnostics("garbage").isEmpty())
    }
    @Test fun completions() {
        val c = IntelParse.completions("""[{"name":"path","insert":"path","type":"module","detail":"x"}]""").single()
        assertEquals("path", c.label)
        assertEquals(SuggestionKind.SYMBOL, c.kind)
    }
    @Test fun gotoAndRefs() {
        val g = IntelParse.gotos("""[{"name":"f","path":"/a.py","line":4,"col":4,"same_file":true,"builtin":false}]""").single()
        assertTrue(g.sameFile); assertEquals(4, g.line)
        assertEquals(7, IntelParse.refLines("""[{"line":7,"col":1,"name":"f"}]""").single().line)
    }
    @Test fun renameResult() {
        assertEquals("x", IntelParse.renamed("""{"ok":true,"code":"x"}"""))
        val e = runCatching { IntelParse.renamed("""{"ok":false,"error":"nope"}""") }.exceptionOrNull()
        assertEquals("nope", e?.message)
    }
}
