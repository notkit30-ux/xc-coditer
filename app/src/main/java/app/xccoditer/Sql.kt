package app.xccoditer

sealed interface SqlResult {
    val sql: String
    data class Table(override val sql: String, val columns: List<String>, val rows: List<List<String>>, val truncated: Boolean) : SqlResult
    data class Ok(override val sql: String, val note: String) : SqlResult
    data class Error(override val sql: String, val message: String) : SqlResult
}

/** Splits a SQL script into statements. Respects quotes, comments (dropped) and CREATE TRIGGER ... END blocks. */
object SqlSplit {
    fun split(script: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        val n = script.length
        fun flush() {
            val s = cur.toString().trim()
            if (s.isNotEmpty()) out += s
            cur.setLength(0)
        }
        while (i < n) {
            val c = script[i]
            when {
                c == '\'' || c == '"' || c == '`' -> {
                    cur.append(c); i++
                    while (i < n) {
                        cur.append(script[i])
                        if (script[i] == c) {
                            if (i + 1 < n && script[i + 1] == c) { cur.append(script[i + 1]); i += 2; continue }
                            break
                        }
                        i++
                    }
                    i++
                }
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    while (i < n && script[i] != '\n') i++
                }
                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    val end = script.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                    cur.append(' ')
                }
                c == ';' -> {
                    val s = cur.toString().trim().lowercase()
                    val trigger = Regex("^create\\s+(temp\\s+|temporary\\s+)?trigger\\b").containsMatchIn(s)
                    if (trigger && !Regex("\\bend$").containsMatchIn(s)) cur.append(c) else flush()
                    i++
                }
                else -> { cur.append(c); i++ }
            }
        }
        flush()
        return out
    }

    fun returnsRows(stmt: String): Boolean =
        stmt.trimStart().substringBefore(' ').substringBefore('\n').substringBefore('(').lowercase() in
            setOf("select", "with", "pragma", "explain", "values")
}

object SqlHtml {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun render(version: String, results: List<SqlResult>): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>body{margin:0;padding:12px;background:#0E1117;color:#E6EDF3;font:14px sans-serif}")
        append("h3{color:#38BDF8;margin:0 0 4px}p.m{color:#7D8590;margin:0 0 12px;font-size:12px}")
        append("pre.q{background:#161B22;padding:8px;border-radius:6px;white-space:pre-wrap;color:#A78BFA;margin:14px 0 6px;font-size:12px}")
        append("table{border-collapse:collapse;min-width:100%}th,td{border:1px solid #30363D;padding:4px 8px;text-align:left;font-size:13px}")
        append("th{background:#161B22;color:#38BDF8}.e{color:#FF7B72}.ok{color:#7EE787}.t{overflow-x:auto}</style></head><body>")
        append("<h3>SQL result</h3><p class=\"m\">SQLite ${esc(version)} (Android's built-in engine). In-memory database: it starts empty and is discarded after each run.</p>")
        if (results.isEmpty()) append("<p class=\"m\">No statements found.</p>")
        for (r in results) {
            append("<pre class=\"q\">${esc(r.sql.take(300))}</pre>")
            when (r) {
                is SqlResult.Error -> append("<div class=\"e\">Error: ${esc(r.message)}</div>")
                is SqlResult.Ok -> append("<div class=\"ok\">${esc(r.note)}</div>")
                is SqlResult.Table -> {
                    append("<div class=\"t\"><table><tr>")
                    r.columns.forEach { append("<th>${esc(it)}</th>") }
                    append("</tr>")
                    r.rows.forEach { row -> append("<tr>"); row.forEach { append("<td>${esc(it)}</td>") }; append("</tr>") }
                    append("</table></div><p class=\"m\">${r.rows.size} row(s)${if (r.truncated) " (truncated)" else ""}</p>")
                }
            }
        }
        append("</body></html>")
    }
}
