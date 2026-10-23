package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class PlayTest {
    private val files = mapOf("style.css" to "body{color:red}", "app.js" to "console.log('hi')")
    private fun read(p: String) = files[p]

    @Test
    fun htmlInlinesLocalCssAndJs() {
        val html = "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"style.css\"></head>" +
            "<body><script src=\"app.js\"></script></body></html>"
        val r = Play.build("index.html", Lang.HTML, html, ::read) as PlayResult.Page
        assertTrue(r.html.contains("<style>body{color:red}</style>"))
        assertTrue(r.html.contains("console.log('hi')"))
        assertFalse(r.html.contains("href=\"style.css\""))
        assertFalse(r.html.contains("src=\"app.js\""))
    }

    @Test
    fun remoteAndMissingAssetsLeftAlone() {
        val html = "<head><link rel=\"stylesheet\" href=\"https://x.com/a.css\"><script src=\"missing.js\"></script></head>"
        val r = Play.build("a.html", Lang.HTML, html, ::read) as PlayResult.Page
        assertTrue(r.html.contains("https://x.com/a.css"))
        assertTrue(r.html.contains("missing.js"))
    }

    @Test
    fun consoleHookAfterDoctypeAndHead() {
        val r = Play.build("a.html", Lang.HTML, "<!DOCTYPE html><html><head></head><body></body></html>", ::read) as PlayResult.Page
        assertTrue(r.html.indexOf("<!DOCTYPE html>") == 0)
        assertTrue(r.html.indexOf("<head>") < r.html.indexOf("__xcFull"))
    }

    @Test
    fun javascriptIsWrappedWithConsole() {
        val r = Play.build("a.js", Lang.JAVASCRIPT, "console.log(1+1)", ::read) as PlayResult.Page
        assertTrue(r.html.contains("console.log(1+1)"))
        assertTrue(r.html.contains("__xcFull=true"))
    }

    @Test
    fun closingScriptTagInJsIsEscaped() {
        val r = Play.build("a.js", Lang.JAVASCRIPT, "var s='</script>';", ::read) as PlayResult.Page
        assertTrue(r.html.contains("<\\/script>';"))
    }

    @Test
    fun otherLanguagesAreHonestlyUnsupported() {
        for ((name, lang) in listOf("a.py" to Lang.PYTHON, "a.ts" to Lang.TYPESCRIPT, "a.css" to Lang.CSS, "a.rs" to Lang.RUST, "a.xml" to Lang.HTML)) {
            assertTrue(name, Play.build(name, lang, "x", ::read) is PlayResult.Unsupported)
        }
        assertTrue(Play.canPlay("x.html"))
        assertFalse(Play.canPlay("x.py"))
    }
}
