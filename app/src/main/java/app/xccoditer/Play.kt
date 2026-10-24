package app.xccoditer

sealed interface PlayResult {
    data class Page(val html: String) : PlayResult
    data class Unsupported(val reason: String) : PlayResult
}

/**
 * Builds a self-contained page for "instant play". Only HTML and JavaScript can genuinely run today
 * (inside Android's WebView, offline). Everything else returns an honest [PlayResult.Unsupported].
 */
object Play {
    private val styleLink = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val relStylesheet = Regex("""rel\s*=\s*["']?stylesheet""", RegexOption.IGNORE_CASE)
    private val hrefAttr = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val scriptSrc = Regex(
        """<script\b([^>]*?)\bsrc\s*=\s*(["'])(.*?)\2([^>]*)>\s*</script>""",
        RegexOption.IGNORE_CASE,
    )
    private val headTag = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlTag = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun canPlay(fileName: String): Boolean = ext(fileName) in setOf("html", "htm", "svg", "js", "mjs", "md", "markdown")

    fun build(fileName: String, lang: Lang, text: String, readLocal: (String) -> String?): PlayResult {
        return when (ext(fileName)) {
            "html", "htm", "svg" -> PlayResult.Page(injectConsole(inlineAssets(text, readLocal), full = false))
            "js", "mjs" -> PlayResult.Page(injectConsole(jsPage(text), full = true))
            "md", "markdown" -> PlayResult.Page(Markdown.page(text))
            "ts", "tsx" -> PlayResult.Unsupported("TypeScript needs a transpiler, which isn't built yet. Write plain JavaScript (.js) to play it now.")
            "css" -> PlayResult.Unsupported("CSS plays inside an HTML file that links it. Open your .html file and press Play.")
            else -> PlayResult.Unsupported(
                "${lang.label} can't run yet: no runtime for it is built in. HTML, JavaScript, Markdown, SQL and Python run in the app.",
            )
        }
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun isLocal(ref: String) =
        ref.isNotBlank() && !ref.contains(':') && !ref.startsWith("/") && !ref.startsWith("#")

    /** Inlines same-folder stylesheets and scripts so the offline preview needs no file access. */
    internal fun inlineAssets(html: String, readLocal: (String) -> String?): String {
        var out = styleLink.replace(html) { m ->
            val tag = m.value
            val href = hrefAttr.find(tag)?.groupValues?.get(1)
            if (href != null && relStylesheet.containsMatchIn(tag) && isLocal(href)) {
                readLocal(href)?.let { "<style>" + it.replace("</style", "<\\/style") + "</style>" } ?: tag
            } else tag
        }
        out = scriptSrc.replace(out) { m ->
            val src = m.groupValues[3]
            if (isLocal(src)) {
                readLocal(src)?.let {
                    "<script${m.groupValues[1]}${m.groupValues[4]}>" + it.replace("</script", "<\\/script") + "</script>"
                } ?: m.value
            } else m.value
        }
        return out
    }

    private fun jsPage(js: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Play</title></head>" +
            "<body style=\"margin:0;background:#0E1117\"><script>" +
            js.replace("</script", "<\\/script") + "\n</script></body></html>"

    private const val CONSOLE_JS = """
(function(){var box;
function el(){if(!box){box=document.createElement('pre');box.id='xc-console';
box.style.cssText='position:fixed;left:0;right:0;bottom:0;max-height:'+(window.__xcFull?'100%':'35%')+';overflow:auto;margin:0;padding:6px 8px;background:#161B22;color:#E6EDF3;font:12px monospace;border-top:1px solid #38BDF8;z-index:2147483647;white-space:pre-wrap;display:none';
(document.body||document.documentElement).appendChild(box);}return box;}
function out(kind,args){var b=el();b.style.display='block';var d=document.createElement('div');
if(kind==='error')d.style.color='#FF7B72';
d.textContent=Array.prototype.map.call(args,function(a){try{return typeof a==='object'?JSON.stringify(a):String(a)}catch(e){return String(a)}}).join(' ');
b.appendChild(d);}
['log','info','warn','error'].forEach(function(k){var o=console[k];
console[k]=function(){out(k==='error'?'error':'log',arguments);if(o)o.apply(console,arguments)}});
window.addEventListener('error',function(e){out('error',[e.message+(e.lineno?' (line '+e.lineno+')':'')])});
window.addEventListener('unhandledrejection',function(e){out('error',['Unhandled promise rejection: '+e.reason])});
})();
"""

    internal fun injectConsole(html: String, full: Boolean): String {
        val tag = "<script>window.__xcFull=$full;$CONSOLE_JS</script>"
        val at = (headTag.find(html) ?: htmlTag.find(html))?.range?.last?.plus(1)
        return if (at != null) html.substring(0, at) + tag + html.substring(at) else tag + html
    }
}
