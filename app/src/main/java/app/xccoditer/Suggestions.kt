package app.xccoditer

enum class SuggestionKind { SNIPPET, KEYWORD, WORD }

/** [insert] may contain one '§' marking where the cursor should land. */
data class Suggestion(val label: String, val insert: String, val kind: SuggestionKind, val detail: String = "")

/**
 * Offline, rule-based completion: language snippets, language keywords and identifiers already in the file.
 * This is NOT language-server intelligence (no types, no imports, no project symbols).
 */
object Suggestions {
    const val MARKER = '§'
    private const val MAX_SCAN = 200_000

    /** Snippets contributed by enabled extensions. Set by the app when extensions change. */
    @Volatile var extras: Map<Lang, List<Suggestion>> = emptyMap()
    private val wordRe = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    fun prefixAt(text: String, cursor: Int): String {
        val c = cursor.coerceIn(0, text.length)
        var s = c
        while (s > 0 && isIdent(text[s - 1])) s--
        return text.substring(s, c)
    }

    fun suggest(text: String, cursor: Int, lang: Lang, limit: Int = 8): List<Suggestion> {
        val prefix = prefixAt(text, cursor)
        if (prefix.isEmpty() || prefix[0].isDigit()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>()
        fun ok(label: String) = label.startsWith(prefix, ignoreCase = true)

        (snippetsFor(lang) + (extras[lang] ?: emptyList())).filter { ok(it.label) }.sortedBy { it.label }.forEach { if (seen.add(it.label)) out += it }

        val upperSql = lang == Lang.SQL && prefix.length > 1 && prefix.all { !it.isLowerCase() }
        lang.keywords.filter { ok(it) && it != prefix }
            .sortedWith(compareBy({ it.length }, { it }))
            .forEach {
                val label = if (upperSql) it.uppercase() else it
                if (seen.add(label)) out += Suggestion(label, label, SuggestionKind.KEYWORD)
            }

        if (text.length <= MAX_SCAN) {
            wordRe.findAll(text).map { it.value }.distinct()
                .filter { ok(it) && it != prefix }
                .sortedWith(compareBy({ it.length }, { it }))
                .forEach { if (seen.add(it)) out += Suggestion(it, it, SuggestionKind.WORD) }
        }
        return out.take(limit)
    }

    /** Returns the new text and new cursor position. Multi-line snippets keep the current line's indentation. */
    fun apply(text: String, cursor: Int, s: Suggestion): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val start = c - prefixAt(text, c).length
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        val ins = s.insert.replace("\n", "\n" + indent)
        val m = ins.indexOf(MARKER)
        val clean = ins.replace(MARKER.toString(), "")
        return (text.substring(0, start) + clean + text.substring(c)) to (start + if (m >= 0) m else clean.length)
    }

    private fun sn(label: String, template: String, detail: String = "snippet") =
        Suggestion(label, template, SuggestionKind.SNIPPET, detail)

    private val table: Map<Lang, List<Suggestion>> by lazy {
        val js = listOf(
            sn("console.log", "console.log(§)"),
            sn("function", "function §() {\n  \n}"),
            sn("for", "for (let i = 0; i < §; i++) {\n  \n}"),
            sn("if", "if (§) {\n  \n}"),
            sn("document.querySelector", "document.querySelector('§')"),
            sn("document.getElementById", "document.getElementById('§')"),
            sn("addEventListener", "addEventListener('§', () => {\n  \n})"),
            sn("setTimeout", "setTimeout(() => {\n  §\n}, 1000)"),
            sn("try", "try {\n  §\n} catch (e) {\n  console.error(e)\n}"),
        )
        val c = listOf(
            sn("main", "int main() {\n    §\n    return 0;\n}"),
            sn("printf", "printf(\"§\\n\");"),
            sn("include", "#include <§>"),
            sn("for", "for (int i = 0; i < §; i++) {\n    \n}"),
            sn("if", "if (§) {\n    \n}"),
        )
        mapOf(
            Lang.PYTHON to listOf(
                sn("print", "print(§)"),
                sn("def", "def §():\n    "),
                sn("for", "for i in range(§):\n    "),
                sn("if", "if §:\n    "),
                sn("while", "while §:\n    "),
                sn("class", "class §:\n    "),
                sn("try", "try:\n    §\nexcept Exception as e:\n    print(e)"),
                sn("main", "if __name__ == \"__main__\":\n    §"),
            ),
            Lang.JAVASCRIPT to js,
            Lang.TYPESCRIPT to js + listOf(sn("interface", "interface § {\n  \n}")),
            Lang.HTML to listOf(
                sn("html5", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>§</title>\n</head>\n<body>\n  \n</body>\n</html>", "page skeleton"),
                sn("div", "<div>§</div>"),
                sn("p", "<p>§</p>"),
                sn("a", "<a href=\"§\"></a>"),
                sn("ul", "<ul>\n  <li>§</li>\n</ul>"),
                sn("img", "<img src=\"§\" alt=\"\">"),
                sn("button", "<button>§</button>"),
                sn("input", "<input type=\"text\" name=\"§\">"),
                sn("script", "<script>\n  §\n</script>"),
                sn("style", "<style>\n  §\n</style>"),
                sn("link", "<link rel=\"stylesheet\" href=\"§\">"),
            ),
            Lang.CSS to listOf(
                sn("flex", "display: flex;\njustify-content: center;\nalign-items: center;§"),
                sn("grid", "display: grid;\ngrid-template-columns: repeat(§, 1fr);"),
                sn("media", "@media (max-width: 600px) {\n  §\n}"),
                sn("transition", "transition: all 0.3s ease;§"),
            ),
            Lang.KOTLIN to listOf(
                sn("fun", "fun §() {\n    \n}"),
                sn("main", "fun main() {\n    §\n}"),
                sn("println", "println(§)"),
                sn("for", "for (i in 0 until §) {\n    \n}"),
                sn("if", "if (§) {\n    \n}"),
                sn("when", "when (§) {\n    else -> \n}"),
                sn("class", "class § {\n    \n}"),
            ),
            Lang.JAVA to listOf(
                sn("main", "public static void main(String[] args) {\n    §\n}"),
                sn("sout", "System.out.println(§);"),
                sn("for", "for (int i = 0; i < §; i++) {\n    \n}"),
                sn("if", "if (§) {\n    \n}"),
                sn("class", "public class § {\n    \n}"),
            ),
            Lang.C to c,
            Lang.CPP to c + listOf(sn("cout", "std::cout << § << std::endl;")),
            Lang.GO to listOf(
                sn("main", "func main() {\n\t§\n}"),
                sn("fmt.Println", "fmt.Println(§)"),
                sn("for", "for i := 0; i < §; i++ {\n\t\n}"),
                sn("iferr", "if err != nil {\n\t§\n}"),
            ),
            Lang.RUST to listOf(
                sn("main", "fn main() {\n    §\n}"),
                sn("println!", "println!(\"§\");"),
                sn("for", "for i in 0..§ {\n    \n}"),
                sn("match", "match § {\n    _ => {}\n}"),
            ),
            Lang.PHP to listOf(
                sn("php", "<?php\n§"),
                sn("echo", "echo §;"),
                sn("function", "function §() {\n    \n}"),
                sn("foreach", "foreach (§ as \$item) {\n    \n}"),
            ),
            Lang.RUBY to listOf(sn("puts", "puts §"), sn("def", "def §\n  \nend"), sn("each", "each do |x|\n  §\nend")),
            Lang.LUA to listOf(sn("print", "print(§)"), sn("function", "function §()\n  \nend"), sn("for", "for i = 1, § do\n  \nend")),
            Lang.SHELL to listOf(sn("shebang", "#!/bin/bash\n§"), sn("if", "if [ § ]; then\n  \nfi"), sn("for", "for i in §; do\n  \ndone")),
            Lang.SQL to listOf(
                sn("select", "SELECT § FROM "),
                sn("create", "CREATE TABLE § (\n  id INTEGER PRIMARY KEY,\n  name TEXT\n);"),
                sn("insert", "INSERT INTO § (name) VALUES ('');"),
            ),
        )
    }

    fun snippetsFor(lang: Lang): List<Suggestion> = table[lang] ?: emptyList()
}
