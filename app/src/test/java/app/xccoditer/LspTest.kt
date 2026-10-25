package app.xccoditer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class LspTest {
    @Test fun codecRoundTripWithUnicode() {
        val msg = """{"a":"héllo ✓"}"""
        val back = LspCodec.read(ByteArrayInputStream(LspCodec.encode(msg) + LspCodec.encode("{}")))
        assertEquals(msg, back)
    }
    @Test fun codecEndAndBadHeader() {
        assertNull(LspCodec.read(ByteArrayInputStream(ByteArray(0))))
        assertThrows(java.io.IOException::class.java) { LspCodec.read(ByteArrayInputStream("Content-Length: x\r\n\r\n".toByteArray())) }
    }
    @Test fun parseCompletionHoverLocations() {
        val c = LspParse.completions(MiniJson.parse("""{"items":[{"label":"printf","detail":"int"},{"label":"for","insertText":"for (${'$'}1;;)"}]}"""))
        assertEquals(listOf("printf", "for"), c.map { it.label })
        assertEquals("for", c[1].insert)
        assertEquals("doc", LspParse.hover(MiniJson.parse("""{"contents":{"kind":"plaintext","value":"doc"}}""")))
        assertEquals("a\n\nb", LspParse.hover(MiniJson.parse("""{"contents":["a",{"value":"b"}]}""")))
        assertEquals(Triple("file:///x", 3, 4), LspParse.locations(MiniJson.parse("""{"uri":"file:///x","range":{"start":{"line":2,"character":4},"end":{"line":2,"character":6}}}""")).single())
        assertEquals(1, LspParse.locations(MiniJson.parse("""[{"targetUri":"file:///y","targetSelectionRange":{"start":{"line":0,"character":0}}}]""")).size)
    }
    @Test fun applyEditsHandlesMultipleAndMultiline() {
        val text = "int foo = 1;\nfoo++;\n"
        val e = listOf(
            Triple((0 to 4), (0 to 7), "bar"),
            Triple((1 to 0), (1 to 3), "bar"),
        )
        assertEquals("int bar = 1;\nbar++;\n", LspParse.applyEdits(text, e))
    }
    @Test fun editsRejectOtherFiles() {
        val r = MiniJson.parse("""{"changes":{"file:///a":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"x"}],"file:///b":[]}}""")
        assertNull(LspParse.editsFor(r, "file:///a"))
        assertNotNull(LspParse.editsFor(MiniJson.parse("""{"documentChanges":[{"textDocument":{"uri":"file:///a"},"edits":[]}]}"""), "file:///a"))
    }

    /** A tiny language server speaking real LSP framing over a real socket. */
    private fun mockServer(): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val t = thread(isDaemon = true) {
            val s = ss.accept()
            val inp = s.getInputStream(); val out = s.getOutputStream()
            fun send(j: String) { out.write(LspCodec.encode(j)); out.flush() }
            while (true) {
                val m = MiniJson.parse(LspCodec.read(inp) ?: break) as Map<*, *>
                val id = (m["id"] as? Double)?.toInt()
                when (m["method"]) {
                    "initialize" -> send("""{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{},"serverInfo":{"name":"mock-ls"}}}""")
                    "textDocument/didOpen" -> send("""{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///p/a.c","diagnostics":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"severity":1,"message":"boom"}]}}""")
                    "textDocument/completion" -> send("""{"jsonrpc":"2.0","id":$id,"result":[{"label":"printf"}]}""")
                    "textDocument/hover" -> send("""{"jsonrpc":"2.0","id":$id,"result":{"contents":"hover!"}}""")
                    "textDocument/definition" -> send("""{"jsonrpc":"2.0","id":$id,"result":[{"uri":"file:///p/a.c","range":{"start":{"line":4,"character":1},"end":{"line":4,"character":2}}}]}""")
                    "textDocument/rename" -> send("""{"jsonrpc":"2.0","id":$id,"result":{"changes":{"file:///p/a.c":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"Z"}]}}}""")
                    "textDocument/references" -> send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no refs"}}""")
                    "workspace/never" -> {} // never answers: exercises the timeout
                }
            }
        }
        return ss to t
    }

    @Test fun clientTalksToMockServer() {
        val (ss, _) = mockServer()
        lateinit var engine: LspEngine
        val client = LspClient.connect("127.0.0.1", ss.localPort) { u, d -> engine.onDiagnostics(u, d) }
        engine = LspEngine(client, "c", "x")
        try {
            assertEquals("mock-ls", client.initialize("file:///p"))
            val uri = "file:///p/a.c"
            val d = engine.diagnostics(uri, "abc\n")
            assertEquals(listOf(IntelDiag(2, 2, "error", "boom")), d)
            assertEquals("printf", engine.complete(uri, "abc\n", 1, 2).single().label)
            assertEquals("hover!", engine.hover(uri, "abc\n", 1, 0))
            assertEquals(Triple(uri, 5, 1), engine.definition(uri, "abc\n", 1, 0).single())
            assertEquals("Zbc\n", engine.rename(uri, "abc\n", 1, 0, "Z"))
            val err = runCatching { engine.references(uri, "abc\n", 1, 0) }.exceptionOrNull()
            assertEquals("no refs", err?.message)
            val t = runCatching { client.request("workspace/never", "{}", 300) }.exceptionOrNull()
            assertTrue(t is java.io.IOException)
        } finally { client.close(); ss.close() }
    }

    @Test fun requestFailsFastWhenServerDisappears() {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { ss.accept().close() }
        val client = LspClient.connect("127.0.0.1", ss.localPort) { _, _ -> }
        try {
            Thread.sleep(300)
            assertThrows(java.io.IOException::class.java) { client.request("initialize", "{}", 2000) }
        } finally { client.close(); ss.close() }
    }
}
