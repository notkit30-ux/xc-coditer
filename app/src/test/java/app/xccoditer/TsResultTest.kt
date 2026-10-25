package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class TsResultTest {
    @Test fun parsesOk() {
        val r = TsResult.parse("""{"ok":true,"js":"const x = 1;\n","diagnostics":[{"line":2,"col":3,"message":"Expression expected."}],"version":"6.0.3"}""") as TsOutcome.Ok
        assertEquals("const x = 1;\n", r.js)
        assertEquals(TsDiagnostic(2, 3, "Expression expected."), r.diagnostics.single())
        assertEquals("6.0.3", r.version)
    }
    @Test fun parsesFailureAndGarbage() {
        assertTrue(TsResult.parse("""{"ok":false,"error":"boom"}""") is TsOutcome.Failed)
        assertTrue(TsResult.parse("not json") is TsOutcome.Failed)
        assertTrue(TsResult.parse("[]") is TsOutcome.Failed)
    }
    @Test fun diagnosticsPageEscapesHtml() {
        val h = TsResult.diagnosticsPage("a<b>.ts", listOf(TsDiagnostic(1, 1, "'>' expected & <x>")))
        assertTrue(h.contains("a&lt;b&gt;.ts"))
        assertTrue(h.contains("&lt;x&gt;"))
        assertFalse(h.contains("<x>"))
    }
    @Test fun moduleWrapsAsModuleWithConsole() {
        val h = Play.moduleFromJs("console.log('</script>')")
        assertTrue(h.contains("<script type=\"module\">"))
        assertTrue(h.contains("<\\/script>"))
        assertTrue(h.indexOf("__xcFull") < h.indexOf("type=\"module\""))
    }
}
