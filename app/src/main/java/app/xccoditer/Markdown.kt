package app.xccoditer

/** Small Markdown subset to HTML. All input is HTML-escaped first, so raw HTML/script in a .md file is shown as text. */
object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val hr = Regex("^(\\*{3,}|-{3,}|_{3,})\\s*$")
    private val quote = Regex("^>\\s?(.*)$")
    private val ul = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ol = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("\\*(.+?)\\*")
    private val link = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")

    private fun esc(s: String) = SqlHtml.esc(s)

    fun inline(s: String): String {
        val parts = s.split('`')
        if (parts.size % 2 == 0) return styled(esc(s)) // unmatched backtick: no code spans
        return parts.mapIndexed { i, p -> if (i % 2 == 1) "<code>${esc(p)}</code>" else styled(esc(p)) }.joinToString("")
    }

    private fun styled(e: String): String {
        var r = bold.replace(e) { "<strong>${it.groupValues[1]}</strong>" }
        r = italic.replace(r) { "<em>${it.groupValues[1]}</em>" }
        return link.replace(r) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    }

    fun toHtml(src: String): String {
        val out = StringBuilder()
        val para = ArrayList<String>()
        var list: String? = null
        var inCode = false
        fun flush() { if (para.isNotEmpty()) { out.append("<p>").append(inline(para.joinToString(" "))).append("</p>\n"); para.clear() } }
        fun closeList() { list?.let { out.append("</$it>\n") }; list = null }
        for (raw in src.replace("\r\n", "\n").split('\n')) {
            if (inCode) {
                if (raw.trimStart().startsWith("```")) { out.append("</code></pre>\n"); inCode = false } else out.append(esc(raw)).append('\n')
                continue
            }
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> { flush(); closeList(); out.append("<pre><code>"); inCode = true }
                line.isBlank() -> { flush(); closeList() }
                heading.matches(line) -> {
                    flush(); closeList()
                    val m = heading.find(line)!!
                    val n = m.groupValues[1].length
                    out.append("<h$n>").append(inline(m.groupValues[2])).append("</h$n>\n")
                }
                hr.matches(line) -> { flush(); closeList(); out.append("<hr>\n") }
                quote.matches(line) -> { flush(); closeList(); out.append("<blockquote>").append(inline(quote.find(line)!!.groupValues[1])).append("</blockquote>\n") }
                ul.matches(line) -> { flush(); if (list != "ul") { closeList(); out.append("<ul>\n"); list = "ul" }; out.append("<li>").append(inline(ul.find(line)!!.groupValues[1])).append("</li>\n") }
                ol.matches(line) -> { flush(); if (list != "ol") { closeList(); out.append("<ol>\n"); list = "ol" }; out.append("<li>").append(inline(ol.find(line)!!.groupValues[1])).append("</li>\n") }
                else -> { closeList(); para += line.trim() }
            }
        }
        if (inCode) out.append("</code></pre>\n")
        flush(); closeList()
        return out.toString()
    }

    fun page(src: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>body{margin:0;padding:16px;background:#0E1117;color:#E6EDF3;font:16px/1.55 sans-serif}" +
            "h1,h2,h3{color:#38BDF8}a{color:#A78BFA}code{background:#161B22;padding:1px 5px;border-radius:4px;font-family:monospace}" +
            "pre{background:#161B22;padding:10px;border-radius:6px;overflow-x:auto}pre code{padding:0}" +
            "blockquote{border-left:3px solid #38BDF8;margin:8px 0;padding:2px 12px;color:#7D8590}hr{border:0;border-top:1px solid #30363D}</style>" +
            "</head><body>" + toHtml(src) + "</body></html>"
}
