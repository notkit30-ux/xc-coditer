package app.xccoditer

import android.content.Context
import java.io.File

data class IntelDiag(val line: Int, val col: Int, val severity: String, val message: String)
data class IntelGoto(val name: String, val path: String, val line: Int, val col: Int, val sameFile: Boolean, val builtin: Boolean)
data class IntelRow(val label: String, val line: Int)
/** Result panel: either [text] (documentation) or [rows] (tap a row to jump to its line). */
data class IntelPanel(val title: String, val text: String? = null, val rows: List<IntelRow> = emptyList())

/** Pure helpers and JSON parsing for code-intelligence results (no Android APIs). */
object IntelParse {
    /** 1-based line and 0-based column of [offset] in [text]. */
    fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var last = -1
        for (i in 0 until o) if (text[i] == '\n') { line++; last = i }
        return line to (o - last - 1)
    }

    private fun list(json: String): List<Map<*, *>> =
        (runCatching { MiniJson.parse(json) as? List<*> }.getOrNull()).orEmpty().mapNotNull { it as? Map<*, *> }

    private fun int(v: Any?) = (v as? Double)?.toInt() ?: 0

    fun diagnostics(json: String): List<IntelDiag> =
        list(json).map { IntelDiag(int(it["line"]), int(it["col"]), it["severity"]?.toString() ?: "warning", it["message"]?.toString().orEmpty()) }

    fun completions(json: String): List<Suggestion> =
        list(json).mapNotNull {
            val n = it["name"]?.toString() ?: return@mapNotNull null
            Suggestion(n, it["insert"]?.toString() ?: n, SuggestionKind.SYMBOL, it["type"]?.toString().orEmpty())
        }

    fun gotos(json: String): List<IntelGoto> =
        list(json).map {
            IntelGoto(it["name"]?.toString().orEmpty(), it["path"]?.toString().orEmpty(), int(it["line"]), int(it["col"]), it["same_file"] == true, it["builtin"] == true)
        }

    fun refLines(json: String): List<IntelRow> =
        list(json).map { IntelRow("line ${int(it["line"])}, col ${int(it["col"])}", int(it["line"])) }

    /** Returns the new source on success; throws IllegalStateException with the reason otherwise. */
    fun renamed(json: String): String {
        val m = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: error("unreadable rename result")
        if (m["ok"] != true) error(m["error"]?.toString() ?: "rename failed")
        return m["code"]?.toString() ?: error("rename returned no code")
    }

    fun diagRows(d: List<IntelDiag>): List<IntelRow> =
        d.map { IntelRow((if (it.severity == "error") "error" else "warning") + "  line ${it.line}: " + it.message, it.line) }
}

/** Python intelligence through jedi and pyflakes, bundled with the embedded interpreter. Works offline. */
object PythonIntel {
    private fun mod(ctx: Context) = PythonRunner.py(ctx).getModule("xcintel")

    fun availability(ctx: Context): String? = runCatching { mod(ctx).callAttr("available").toString() }.getOrNull()

    fun diagnostics(ctx: Context, code: String, file: File) = IntelParse.diagnostics(mod(ctx).callAttr("diagnostics", code, file.name).toString())
    fun complete(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.completions(mod(ctx).callAttr("complete", code, line, col, file.path, root.path).toString())
    fun goto(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.gotos(mod(ctx).callAttr("goto", code, line, col, file.path, root.path).toString())
    fun hover(ctx: Context, code: String, line: Int, col: Int, file: File, root: File): String =
        mod(ctx).callAttr("hover", code, line, col, file.path, root.path).toString()
    fun references(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.refLines(mod(ctx).callAttr("references", code, line, col, file.path, root.path).toString())
    fun rename(ctx: Context, code: String, line: Int, col: Int, name: String, file: File, root: File): String =
        IntelParse.renamed(mod(ctx).callAttr("rename", code, line, col, name, file.path, root.path).toString())
}
