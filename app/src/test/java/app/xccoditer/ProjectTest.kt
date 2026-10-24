package app.xccoditer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SqlTest {
    @Test fun splitsOnSemicolonsOutsideQuotes() {
        val s = SqlSplit.split("INSERT INTO t VALUES ('a;b'); -- note; here\nSELECT 1; /* x; y */ SELECT \"q;\";")
        assertEquals(listOf("INSERT INTO t VALUES ('a;b')", "SELECT 1", "SELECT \"q;\""), s)
    }
    @Test fun escapedQuoteInsideString() {
        assertEquals(1, SqlSplit.split("SELECT 'it''s; ok'").size)
    }
    @Test fun triggerBodyNotSplit() {
        val sql = "CREATE TRIGGER tr AFTER INSERT ON t BEGIN UPDATE t SET a=1; DELETE FROM t WHERE a=2; END; SELECT 1;"
        val s = SqlSplit.split(sql)
        assertEquals(2, s.size)
        assertTrue(s[0].endsWith("END"))
    }
    @Test fun detectsRowReturningStatements() {
        assertTrue(SqlSplit.returnsRows("SELECT 1"))
        assertTrue(SqlSplit.returnsRows("  with x as (select 1) select * from x"))
        assertTrue(SqlSplit.returnsRows("PRAGMA table_info(t)"))
        assertFalse(SqlSplit.returnsRows("INSERT INTO t VALUES (1)"))
    }
    @Test fun htmlEscapesEverything() {
        val html = SqlHtml.render("3.x", listOf(
            SqlResult.Table("SELECT '<b>'", listOf("<c>"), listOf(listOf("<script>alert(1)</script>")), false),
            SqlResult.Error("bad", "no such table: <x>"),
        ))
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("no such table: &lt;x&gt;"))
    }
}

class ProjectSearchTest {
    private fun tmp(): File = Files.createTempDirectory("xcs").toFile()

    @Test fun findsLinesSkipsBinaryAndSkippedDirs() {
        val r = tmp()
        File(r, "a.txt").writeText("hello\nWorld hello\n")
        File(r, "sub").mkdirs(); File(r, "sub/b.py").writeText("print('HELLO')")
        File(r, "node_modules").mkdirs(); File(r, "node_modules/x.js").writeText("hello")
        File(r, "bin.dat").writeBytes(byteArrayOf(104, 101, 0, 108, 108, 111))
        val m = ProjectSearch.search(r, "hello")
        assertEquals(3, m.size)
        assertEquals(listOf(1, 2), m.filter { it.file.name == "a.txt" }.map { it.line })
    }
    @Test fun replaceAcrossFiles() {
        val r = tmp()
        val a = File(r, "a.txt").apply { writeText("foo foo") }
        val b = File(r, "b.txt").apply { writeText("none") }
        val res = ProjectSearch.replace(ProjectSearch.matchingFiles(r, "foo"), "foo", "bar")
        assertEquals(mapOf(a to 2), res)
        assertEquals("bar bar", a.readText())
        assertEquals("none", b.readText())
    }
    @Test fun emptyQueryDoesNothing() {
        assertTrue(ProjectSearch.search(tmp(), "").isEmpty())
        assertEquals(0, ProjectSearch.countOccurrences("abc", ""))
    }
}

class TemplatesTest {
    @Test fun templatesAreWellFormed() {
        assertEquals(Templates.all.size, Templates.all.map { it.id }.toSet().size)
        for (t in Templates.all) {
            assertTrue(t.id, t.files.isNotEmpty())
            for (p in t.files.keys) assertTrue("$p", !p.startsWith("/") && p.split('/').none { it.isEmpty() || it == ".." })
            assertTrue(t.id, t.howToRun.isNotBlank())
        }
    }
    @Test fun writeTreeAndUniqueNames() {
        val ws = Workspace(Files.createTempDirectory("xct").toFile())
        val t = Templates.all.first { it.id == "typescript" }
        val dir = ws.createFolder(ws.root, ws.uniqueChildName(ws.root, t.id))
        ws.writeTree(dir, t.files)
        assertTrue(File(dir, "src/index.ts").isFile)
        assertEquals("typescript-2", ws.uniqueChildName(ws.root, "typescript"))
        assertThrows(IllegalArgumentException::class.java) { ws.writeTree(dir, mapOf("../evil.txt" to "x")) }
    }
    @Test fun websiteTemplateReallyInlinesInPlay() {
        val t = Templates.all.first { it.id == "website" }
        val r = Play.build("index.html", Lang.HTML, t.files["index.html"]!!) { t.files[it] } as PlayResult.Page
        assertTrue(r.html.contains("<style>body {"))
        assertTrue(r.html.contains("addEventListener"))
    }
}
