package app.xccoditer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun headingsListsAndInline() {
        val h = Markdown.toHtml("# Title\n\nHello **bold** and *it* and `code`.\n\n- a\n- b\n\n1. x\n2. y\n")
        assertTrue(h.contains("<h1>Title</h1>"))
        assertTrue(h.contains("<strong>bold</strong>"))
        assertTrue(h.contains("<em>it</em>"))
        assertTrue(h.contains("<code>code</code>"))
        assertTrue(h.contains("<ul>\n<li>a</li>\n<li>b</li>\n</ul>"))
        assertTrue(h.contains("<ol>"))
    }
    @Test fun rawHtmlIsEscaped() {
        val h = Markdown.toHtml("<script>alert(1)</script>\n\n[x](javascript:alert(1))")
        assertFalse(h.contains("<script>"))
        assertFalse(h.contains("<a href"))
    }
    @Test fun httpLinksAndFences() {
        val h = Markdown.toHtml("[ok](https://example.com)\n\n```\n<b>x</b>\n```")
        assertTrue(h.contains("<a href=\"https://example.com\">ok</a>"))
        assertTrue(h.contains("<pre><code>&lt;b&gt;x&lt;/b&gt;"))
    }
    @Test fun playRoutesMarkdown() {
        assertTrue(Play.build("README.md", Lang.MARKDOWN, "# hi") { null } is PlayResult.Page)
    }
}

class JsonAndSettingsTest {
    @Test fun parsesNestedJson() {
        @Suppress("UNCHECKED_CAST")
        val m = MiniJson.parse("""{"a":[1,2.5,"x\nA"],"b":{"c":true,"d":null}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, 2.5, "x\nA"), m["a"])
        assertEquals(true, (m["b"] as Map<*, *>)["c"])
    }
    @Test fun rejectsBadJson() {
        for (bad in listOf("{", "[1,]", "{\"a\":}", "nul", "{} x", "\"abc")) {
            assertThrows(bad, IllegalArgumentException::class.java) { MiniJson.parse(bad) }
        }
    }
    @Test fun settingsRoundTripAndValidation() {
        val s = SettingsData(true, 18, 2, false)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decode("""{"xcSettings":1,"light":true,"fontSize":99,"tabSize":4,"autoSave":true}""") }
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decode("""{"hello":1}""") }
    }
}

class ExtensionTest {
    private fun store() = ExtensionStore(Files.createTempDirectory("xcx").toFile())

    @Test fun officialExtensionsAllValid() {
        for (j in OfficialExtensions.all) ExtensionManifest.parse(j)
        assertEquals(OfficialExtensions.all.size, OfficialExtensions.all.map { ExtensionManifest.parse(it).id }.toSet().size)
    }
    @Test fun lifecycleInstallDisableUninstall() {
        val st = store()
        val e = st.install(OfficialExtensions.all[1])
        assertEquals("xc.python-snippets", e.id)
        assertTrue(st.enabledSnippets()[Lang.PYTHON]!!.any { it.label == "readfile" })
        st.setEnabled(e.id, false)
        assertTrue(st.enabledSnippets().isEmpty())
        assertFalse(st.list().single().enabled)
        st.setEnabled(e.id, true)
        assertTrue(st.list().single().enabled)
        st.uninstall(e.id)
        assertTrue(st.list().isEmpty())
    }
    @Test fun suggestionsUseExtensionSnippets() {
        val st = store(); st.install(OfficialExtensions.all[1])
        Suggestions.extras = st.enabledSnippets()
        try {
            assertTrue(Suggestions.suggest("readf", 5, Lang.PYTHON).any { it.label == "readfile" })
        } finally { Suggestions.extras = emptyMap() }
    }
    @Test fun rejectsPermissionsBadIdsAndUnknownLanguage() {
        fun m(extra: String, id: String = "a.b") = """{"id":"$id","name":"n","version":"1.0.0","publisher":"p",$extra}"""
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest.parse(m("\"permissions\":[\"network\"]")) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest.parse(m("\"permissions\":[]", id = "BAD ID")) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest.parse(m("\"contributes\":{\"snippets\":{\"klingon\":[]}}")) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest.parse(m("\"contributes\":{\"snippets\":{\"python\":[{\"label\":\"has space\",\"insert\":\"x\"}]}}")) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionManifest.parse("not json") }
    }
}

class RuntimeAndShellTest {
    @Test fun staticCatalogNeverClaimsVerified() {
        assertTrue(RuntimeCatalog.notInstalled().none { it.status == RuntimeStatus.VERIFIED || it.status == RuntimeStatus.LIMITED })
    }
    @Test fun cdResolution() {
        val home = Files.createTempDirectory("xch").toFile()
        val sub = File(home, "a/b").apply { mkdirs() }
        assertEquals(home.canonicalFile, ShellUtil.resolveCd(sub, "", home))
        assertEquals(File(home, "a").canonicalFile, ShellUtil.resolveCd(sub, "..", home))
        assertEquals(sub.canonicalFile, ShellUtil.resolveCd(home, "a/b", home))
        assertEquals(sub.canonicalFile, ShellUtil.resolveCd(home, "~/a/b", home))
        assertNull(ShellUtil.resolveCd(home, "nope", home))
    }
}
