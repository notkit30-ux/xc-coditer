package app.xccoditer

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** LSP base-protocol framing: "Content-Length: N\r\n\r\n" + N bytes of UTF-8 JSON. */
object LspCodec {
    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return ("Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
    }

    /** Reads one message, or null at end of stream. Throws on a malformed header. */
    fun read(input: InputStream): String? {
        var length = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: -1
        }
        if (length < 0 || length > 64 * 1024 * 1024) throw java.io.IOException("bad Content-Length")
        val buf = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(buf, n, length - n)
            if (r < 0) return null
            n += r
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 4096) throw java.io.IOException("header too long")
        }
    }
}

/** Parsing of LSP results into the editor's own types, plus applying text edits. Pure. */
object LspParse {
    private fun obj(v: Any?) = v as? Map<*, *>
    private fun int(v: Any?) = (v as? Double)?.toInt() ?: 0

    fun completions(result: Any?): List<Suggestion> {
        val items = (result as? List<*>) ?: (obj(result)?.get("items") as? List<*>) ?: return emptyList()
        return items.mapNotNull { obj(it) }.mapNotNull {
            val label = it["label"]?.toString() ?: return@mapNotNull null
            val insert = (it["insertText"] ?: obj(it["textEdit"])?.get("newText") ?: label).toString()
            // Snippet syntax ($1, ${2:x}) is not expanded here: fall back to the plain label.
            Suggestion(label.trim(), if (insert.contains('$')) label.trim() else insert, SuggestionKind.SYMBOL, it["detail"]?.toString().orEmpty())
        }
    }

    fun hover(result: Any?): String {
        fun text(c: Any?): String = when (c) {
            is String -> c
            is Map<*, *> -> c["value"]?.toString().orEmpty()
            is List<*> -> c.joinToString("\n\n") { text(it) }
            else -> ""
        }
        return text(obj(result)?.get("contents")).trim()
    }

    /** Location, Location[] or LocationLink[] -> (uri, line1, col0). */
    fun locations(result: Any?): List<Triple<String, Int, Int>> {
        val list = when (result) { is List<*> -> result; is Map<*, *> -> listOf(result); else -> emptyList() }
        return list.mapNotNull { obj(it) }.mapNotNull {
            val uri = (it["uri"] ?: it["targetUri"])?.toString() ?: return@mapNotNull null
            val start = obj(obj(it["range"] ?: it["targetSelectionRange"])?.get("start"))
            Triple(uri, int(start?.get("line")) + 1, int(start?.get("character")))
        }
    }

    fun diagnostics(params: Any?): Pair<String, List<IntelDiag>>? {
        val p = obj(params) ?: return null
        val uri = p["uri"]?.toString() ?: return null
        val d = (p["diagnostics"] as? List<*>).orEmpty().mapNotNull { obj(it) }.map {
            val s = obj(obj(it["range"])?.get("start"))
            IntelDiag(int(s?.get("line")) + 1, int(s?.get("character")), if (int(it["severity"]) == 1) "error" else "warning", it["message"]?.toString().orEmpty())
        }
        return uri to d
    }

    /** Edits for [uri] from a WorkspaceEdit; null if the edit touches other documents too. */
    fun editsFor(result: Any?, uri: String): List<Triple<Pair<Int, Int>, Pair<Int, Int>, String>>? {
        val we = obj(result) ?: return null
        val perDoc = HashMap<String, List<*>>()
        obj(we["changes"])?.forEach { (k, v) -> (v as? List<*>)?.let { perDoc[k.toString()] = it } }
        (we["documentChanges"] as? List<*>)?.forEach { dc ->
            val m = obj(dc) ?: return null
            val u = obj(m["textDocument"])?.get("uri")?.toString() ?: return null  // file create/rename ops are unsupported
            perDoc[u] = (m["edits"] as? List<*>) ?: return null
        }
        if (perDoc.keys.any { it != uri }) return null
        return (perDoc[uri] ?: return null).mapNotNull { obj(it) }.map {
            val r = obj(it["range"]); val s = obj(r?.get("start")); val e = obj(r?.get("end"))
            Triple((int(s?.get("line")) to int(s?.get("character"))), (int(e?.get("line")) to int(e?.get("character"))), it["newText"]?.toString().orEmpty())
        }
    }

    /** Applies LSP edits (0-based line/character) to [text], last edit first so earlier offsets stay valid. */
    fun applyEdits(text: String, edits: List<Triple<Pair<Int, Int>, Pair<Int, Int>, String>>): String {
        val starts = ArrayList<Int>().apply { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun off(p: Pair<Int, Int>): Int = ((starts.getOrNull(p.first) ?: text.length) + p.second).coerceAtMost(text.length)
        var out = text
        for ((s, e, t) in edits.sortedWith(compareByDescending<Triple<Pair<Int, Int>, Pair<Int, Int>, String>> { it.first.first }.thenByDescending { it.first.second })) {
            out = out.substring(0, off(s)) + t + out.substring(off(e).coerceAtLeast(off(s)))
        }
        return out
    }
}

/** Minimal JSON-RPC client for one language server over TCP. Full-document sync only. */
class LspClient(private val socket: Socket, private val onDiagnostics: (String, List<IntelDiag>) -> Unit) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Any?>>()
    @Volatile var closed = false
        private set

    private val reader = Thread({
        try {
            while (true) dispatch(LspCodec.read(input) ?: break)
        } catch (_: Exception) {
        } finally {
            closed = true
            pending.values.forEach { it.completeExceptionally(java.io.IOException("language server disconnected")) }
        }
    }, "lsp-reader").apply { isDaemon = true; start() }

    private fun send(json: String) = synchronized(socket) { socket.getOutputStream().apply { write(LspCodec.encode(json)); flush() } }

    private fun dispatch(raw: String) {
        val m = MiniJson.parse(raw) as? Map<*, *> ?: return
        val method = m["method"]?.toString()
        val id = (m["id"] as? Double)?.toInt()
        when {
            method == null && id != null -> {
                val f = pending.remove(id) ?: return
                val err = m["error"] as? Map<*, *>
                if (err != null) f.completeExceptionally(IllegalStateException(err["message"]?.toString() ?: "server error")) else f.complete(m["result"])
            }
            method == "textDocument/publishDiagnostics" -> LspParse.diagnostics(m["params"])?.let { onDiagnostics(it.first, it.second) }
            method != null && id != null -> send("""{"jsonrpc":"2.0","id":$id,"result":null}""") // server-to-client requests: acknowledge
        }
    }

    fun request(method: String, paramsJson: String, timeoutMs: Long = 8000): Any? {
        if (closed) throw java.io.IOException("language server disconnected")
        val id = nextId.getAndIncrement()
        val f = CompletableFuture<Any?>()
        pending[id] = f
        send("""{"jsonrpc":"2.0","id":$id,"method":${MiniJson.quote(method)},"params":$paramsJson}""")
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.remove(id)
            throw java.io.IOException("language server did not answer $method in ${timeoutMs / 1000}s")
        }
    }

    fun notify(method: String, paramsJson: String) =
        send("""{"jsonrpc":"2.0","method":${MiniJson.quote(method)},"params":$paramsJson}""")

    fun initialize(rootUri: String): String {
        val caps = """{"textDocument":{"synchronization":{},"completion":{"completionItem":{"snippetSupport":false}},"hover":{"contentFormat":["plaintext"]},"publishDiagnostics":{},"rename":{}}}"""
        val r = request("initialize", """{"processId":null,"rootUri":${MiniJson.quote(rootUri)},"capabilities":$caps}""", 20000) as? Map<*, *>
        notify("initialized", "{}")
        return (r?.get("serverInfo") as? Map<*, *>)?.get("name")?.toString() ?: "language server"
    }

    override fun close() {
        closed = true
        runCatching { notify("exit", "null") }
        runCatching { socket.close() }
    }

    companion object {
        fun connect(host: String, port: Int, onDiagnostics: (String, List<IntelDiag>) -> Unit): LspClient {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 3000)
            return LspClient(s, onDiagnostics)
        }
    }
}

/** Talks to one connected language server for one language. */
class LspEngine(val client: LspClient, val languageId: String, val serverName: String) {
    private val versions = HashMap<String, Int>()
    private val texts = HashMap<String, String>()
    private val diags = ConcurrentHashMap<String, List<IntelDiag>>()
    private val seen = ConcurrentHashMap<String, Long>()

    fun onDiagnostics(uri: String, d: List<IntelDiag>) { diags[uri] = d; seen[uri] = System.nanoTime() }

    @Synchronized
    fun sync(uri: String, text: String) {
        val old = texts[uri]
        if (old == null) {
            versions[uri] = 1
            client.notify("textDocument/didOpen", """{"textDocument":{"uri":${MiniJson.quote(uri)},"languageId":${MiniJson.quote(languageId)},"version":1,"text":${MiniJson.quote(text)}}}""")
        } else if (old != text) {
            val v = (versions[uri] ?: 1) + 1
            versions[uri] = v
            client.notify("textDocument/didChange", """{"textDocument":{"uri":${MiniJson.quote(uri)},"version":$v},"contentChanges":[{"text":${MiniJson.quote(text)}}]}""")
        }
        texts[uri] = text
    }

    private fun pos(uri: String, line1: Int, col: Int) = """{"textDocument":{"uri":${MiniJson.quote(uri)}},"position":{"line":${line1 - 1},"character":$col}}"""

    fun complete(uri: String, text: String, line: Int, col: Int) = run { sync(uri, text); LspParse.completions(client.request("textDocument/completion", pos(uri, line, col))) }
    fun hover(uri: String, text: String, line: Int, col: Int) = run { sync(uri, text); LspParse.hover(client.request("textDocument/hover", pos(uri, line, col))) }
    fun definition(uri: String, text: String, line: Int, col: Int) = run { sync(uri, text); LspParse.locations(client.request("textDocument/definition", pos(uri, line, col))) }
    fun references(uri: String, text: String, line: Int, col: Int) = run {
        sync(uri, text)
        val p = pos(uri, line, col).dropLast(1) + ""","context":{"includeDeclaration":true}}"""
        LspParse.locations(client.request("textDocument/references", p))
    }

    fun rename(uri: String, text: String, line: Int, col: Int, name: String): String {
        sync(uri, text)
        val p = pos(uri, line, col).dropLast(1) + ""","newName":${MiniJson.quote(name)}}"""
        val edits = LspParse.editsFor(client.request("textDocument/rename", p), uri) ?: error("the rename touches other files; only single-file renames are supported")
        return LspParse.applyEdits(text, edits)
    }

    /** Diagnostics are pushed by the server; wait briefly for the first batch after syncing. */
    fun diagnostics(uri: String, text: String, waitMs: Long = 3000): List<IntelDiag> {
        val before = seen[uri]
        sync(uri, text)
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end && seen[uri] == before && !client.closed) Thread.sleep(50)
        return diags[uri].orEmpty()
    }

    fun uriFor(path: String) = "file://$path"
}
