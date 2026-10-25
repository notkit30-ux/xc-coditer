package app.xccoditer

data class TsDiagnostic(val line: Int, val col: Int, val message: String)

sealed interface TsOutcome {
    data class Ok(val js: String, val diagnostics: List<TsDiagnostic>, val version: String) : TsOutcome
    data class Failed(val reason: String) : TsOutcome
}

/** Pure parsing and page building for the TypeScript runner (no Android APIs, unit-testable). */
object TsResult {
    /** [raw] is what the runner page's xcTranspile returned: a JSON document. */
    fun parse(raw: String): TsOutcome {
        val root = runCatching { MiniJson.parse(raw) as? Map<*, *> }.getOrNull()
            ?: return TsOutcome.Failed("The TypeScript compiler returned something unreadable.")
        if (root["ok"] != true) return TsOutcome.Failed("TypeScript compiler error: " + (root["error"] ?: "unknown"))
        val diags = (root["diagnostics"] as? List<*>).orEmpty().mapNotNull { d ->
            val m = d as? Map<*, *> ?: return@mapNotNull null
            TsDiagnostic(
                (m["line"] as? Double)?.toInt() ?: 0,
                (m["col"] as? Double)?.toInt() ?: 0,
                m["message"]?.toString().orEmpty(),
            )
        }
        return TsOutcome.Ok(root["js"]?.toString().orEmpty(), diags, root["version"]?.toString().orEmpty())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun diagnosticsPage(fileName: String, diags: List<TsDiagnostic>): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>body{background:#0E1117;color:#E6EDF3;font:14px monospace;padding:12px}h3{color:#FF7B72}li{margin:6px 0}small{color:#8B949E}</style></head><body>")
        append("<h3>").append(esc(fileName)).append(": ").append(diags.size).append(" syntax error(s)</h3><ul>")
        for (d in diags) append("<li>line ${d.line}, col ${d.col}: ").append(esc(d.message)).append("</li>")
        append("</ul><small>Not run. Only syntax is checked; types are not checked.</small></body></html>")
    }
}
