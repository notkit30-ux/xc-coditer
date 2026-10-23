package app.xccoditer

enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, TAG }

data class Span(val start: Int, val end: Int, val kind: TokenKind)

enum class Lang(
    val label: String,
    val lineComment: String?,
    val blockComment: Boolean,
    val keywords: Set<String>,
    val markup: Boolean = false,
) {
    PYTHON("Python", "#", false, setOf("def", "class", "import", "from", "as", "if", "elif", "else", "for", "while", "return", "try", "except", "finally", "with", "lambda", "pass", "break", "continue", "in", "is", "not", "and", "or", "None", "True", "False", "yield", "raise", "global", "async", "await")),
    JAVASCRIPT("JavaScript", "//", true, setOf("function", "const", "let", "var", "if", "else", "for", "while", "return", "class", "new", "import", "export", "from", "async", "await", "try", "catch", "finally", "throw", "switch", "case", "break", "continue", "null", "undefined", "true", "false", "this", "typeof", "of", "in", "default")),
    TYPESCRIPT("TypeScript", "//", true, JAVASCRIPT.keywords + setOf("interface", "type", "enum", "implements", "extends", "public", "private", "protected", "readonly", "number", "string", "boolean", "any", "void", "namespace")),
    KOTLIN("Kotlin", "//", true, setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "null", "true", "false", "is", "in", "as", "try", "catch", "finally", "throw", "override", "private", "public", "internal", "data", "sealed", "companion", "suspend", "this", "super")),
    JAVA("Java", "//", true, setOf("public", "private", "protected", "class", "interface", "enum", "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while", "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "throws", "import", "package", "extends", "implements", "null", "true", "false", "this", "super")),
    C("C", "//", true, setOf("int", "char", "float", "double", "void", "long", "short", "unsigned", "signed", "struct", "union", "enum", "typedef", "static", "const", "extern", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "sizeof", "goto", "default")),
    CPP("C++", "//", true, C.keywords + setOf("class", "namespace", "template", "typename", "public", "private", "protected", "virtual", "new", "delete", "using", "bool", "true", "false", "nullptr", "auto", "try", "catch", "throw", "this")),
    GO("Go", "//", true, setOf("package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "if", "else", "for", "range", "switch", "case", "default", "return", "break", "continue", "nil", "true", "false", "select")),
    RUST("Rust", "//", true, setOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "pub", "use", "mod", "if", "else", "match", "for", "while", "loop", "return", "break", "continue", "self", "Self", "true", "false", "as", "in", "ref", "move", "async", "await", "unsafe", "where")),
    PHP("PHP", "//", true, setOf("function", "class", "public", "private", "protected", "static", "if", "else", "elseif", "foreach", "for", "while", "return", "echo", "new", "namespace", "use", "try", "catch", "finally", "throw", "null", "true", "false", "array")),
    RUBY("Ruby", "#", false, setOf("def", "class", "module", "end", "if", "elsif", "else", "unless", "while", "until", "for", "do", "return", "begin", "rescue", "ensure", "yield", "nil", "true", "false", "self", "require", "puts", "case", "when", "then")),
    LUA("Lua", "--", false, setOf("function", "local", "end", "if", "then", "else", "elseif", "for", "while", "do", "repeat", "until", "return", "nil", "true", "false", "and", "or", "not", "in", "break")),
    SHELL("Shell", "#", false, setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "echo", "export", "local", "return", "in")),
    SQL("SQL", "--", true, setOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop", "alter", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "and", "or", "not", "null", "primary", "key", "as", "distinct", "union", "index", "view")),
    HTML("HTML", null, false, emptySet(), markup = true),
    CSS("CSS", null, true, setOf("important", "media", "import", "keyframes", "font-face")),
    JSON("JSON", null, false, setOf("true", "false", "null")),
    YAML("YAML", "#", false, setOf("true", "false", "null")),
    MARKDOWN("Markdown", null, false, emptySet()),
    PLAIN("Plain text", null, false, emptySet());

    companion object {
        fun forFileName(name: String): Lang = when (name.substringAfterLast('.', "").lowercase()) {
            "py" -> PYTHON
            "js", "mjs", "cjs", "jsx" -> JAVASCRIPT
            "ts", "tsx" -> TYPESCRIPT
            "kt", "kts" -> KOTLIN
            "java" -> JAVA
            "c", "h" -> C
            "cpp", "cc", "cxx", "hpp" -> CPP
            "go" -> GO
            "rs" -> RUST
            "php" -> PHP
            "rb" -> RUBY
            "lua" -> LUA
            "sh", "bash" -> SHELL
            "sql" -> SQL
            "html", "htm", "xml", "svg" -> HTML
            "css" -> CSS
            "json" -> JSON
            "yml", "yaml" -> YAML
            "md", "markdown" -> MARKDOWN
            else -> PLAIN
        }
    }
}

/** Regex-based tokenizer. Highlighting only: it does NOT parse, run or validate code. */
object Highlighter {
    const val MAX_CHARS = 200_000
    private val word = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val patterns = HashMap<Lang, Regex>()

    private fun patternFor(lang: Lang): Regex = patterns.getOrPut(lang) {
        val parts = ArrayList<String>()
        // group order matters: 1 comment, 2 string, 3 tag, 4 number, 5 word
        val comment = buildList {
            lang.lineComment?.let { add(Regex.escape(it) + "[^\\n]*") }
            if (lang.blockComment) add("/\\*[\\s\\S]*?\\*/")
            if (lang.markup) add("<!--[\\s\\S]*?-->")
        }.ifEmpty { listOf("(?!)") }.joinToString("|")
        parts += "($comment)"
        parts += "(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')"
        parts += if (lang.markup) "(</?[A-Za-z][^>]*>)" else "((?!))"
        parts += "(\\b\\d+(?:\\.\\d+)?\\b)"
        parts += "([A-Za-z_][A-Za-z0-9_]*)"
        Regex(parts.joinToString("|"))
    }

    fun tokenize(text: String, lang: Lang): List<Span> {
        if (lang == Lang.PLAIN || lang == Lang.MARKDOWN || text.length > MAX_CHARS) return emptyList()
        val sql = lang == Lang.SQL
        val out = ArrayList<Span>()
        for (m in patternFor(lang).findAll(text)) {
            val g = m.groups
            val kind = when {
                g[1] != null -> TokenKind.COMMENT
                g[2] != null -> TokenKind.STRING
                g[3] != null -> TokenKind.TAG
                g[4] != null -> TokenKind.NUMBER
                g[5] != null -> {
                    val w = m.value
                    if (w in lang.keywords || (sql && w.lowercase() in lang.keywords)) TokenKind.KEYWORD else null
                }
                else -> null
            } ?: continue
            out += Span(m.range.first, m.range.last + 1, kind)
        }
        return out
    }
}
