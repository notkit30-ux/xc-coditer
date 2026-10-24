package app.xccoditer

import java.io.File

/**
 * XC extension manifest, version 1 (data-only). See docs/EXTENSIONS.md.
 * Extensions contribute snippets. They cannot run code, read files, or use the network.
 */
data class Extension(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val snippets: Map<Lang, List<Suggestion>>,
    val raw: String,
)

object ExtensionManifest {
    private val idRe = Regex("^[a-z0-9][a-z0-9-]{0,38}\\.[a-z0-9][a-z0-9-]{0,38}$")
    private val verRe = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")
    const val MAX_BYTES = 100_000
    const val MAX_SNIPPETS = 200

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Extension {
        require(text.length <= MAX_BYTES) { "Manifest is larger than ${MAX_BYTES / 1000} KB" }
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Manifest must be a JSON object")
        fun str(k: String, max: Int, required: Boolean = true): String {
            val v = root[k]
            if (v == null && !required) return ""
            require(v is String && v.isNotBlank() && v.length <= max) { "\"$k\" must be a non-empty string (max $max characters)" }
            return v
        }
        val id = str("id", 80)
        require(idRe.matches(id)) { "\"id\" must look like publisher.name (lowercase letters, digits, dashes)" }
        val version = str("version", 20)
        require(verRe.matches(version)) { "\"version\" must look like 1.0.0" }
        val perms = root["permissions"] ?: emptyList<Any?>()
        require(perms is List<*>) { "\"permissions\" must be a list" }
        require(perms.isEmpty()) { "Permission \"${perms.first()}\" is not supported. XC extensions are data-only for now and must request none." }
        val name = str("name", 60)
        val snippets = LinkedHashMap<Lang, List<Suggestion>>()
        var total = 0
        val contributes = root["contributes"] ?: emptyMap<String, Any?>()
        require(contributes is Map<*, *>) { "\"contributes\" must be an object" }
        val sn = contributes["snippets"] ?: emptyMap<String, Any?>()
        require(sn is Map<*, *>) { "\"contributes.snippets\" must be an object" }
        for ((k, v) in sn) {
            val lang = Lang.values().firstOrNull { it.name.equals(k as String, ignoreCase = true) && it != Lang.PLAIN }
                ?: throw IllegalArgumentException("Unknown language \"$k\" in snippets")
            require(v is List<*>) { "Snippets for $k must be a list" }
            snippets[lang] = v.map { e ->
                val m = e as? Map<*, *> ?: throw IllegalArgumentException("Each snippet must be an object")
                val label = m["label"] as? String
                val insert = m["insert"] as? String
                require(!label.isNullOrBlank() && label.length <= 60 && label.none { it.isWhitespace() }) { "Snippet \"label\" must be 1-60 characters with no spaces" }
                require(insert != null && insert.isNotEmpty() && insert.length <= 2000) { "Snippet \"insert\" must be 1-2000 characters" }
                total++
                Suggestion(label, insert, SuggestionKind.SNIPPET, name)
            }
        }
        require(total <= MAX_SNIPPETS) { "Too many snippets (max $MAX_SNIPPETS)" }
        return Extension(id, name, version, str("publisher", 40), str("description", 300, required = false), snippets, text)
    }
}

data class InstalledExtension(val ext: Extension, val enabled: Boolean)

class ExtensionStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(id: String) = File(dir, "$id.json")
    private fun marker(id: String) = File(dir, "$id.disabled")

    /** Validates then stores. Installing an id that exists is an update (enabled state is kept). */
    fun install(text: String): Extension {
        val ext = ExtensionManifest.parse(text)
        val tmp = File(dir, "${ext.id}.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file(ext.id))) { file(ext.id).writeText(text); tmp.delete() }
        return ext
    }

    fun list(): List<InstalledExtension> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { ExtensionManifest.parse(f.readText()) }.getOrNull()?.let { InstalledExtension(it, !marker(it.id).exists()) }
        }

    fun setEnabled(id: String, enabled: Boolean) {
        if (!file(id).exists()) return
        if (enabled) marker(id).delete() else marker(id).createNewFile()
    }

    fun uninstall(id: String) { file(id).delete(); marker(id).delete() }

    fun enabledSnippets(): Map<Lang, List<Suggestion>> {
        val out = HashMap<Lang, MutableList<Suggestion>>()
        for (i in list()) if (i.enabled) for ((l, s) in i.ext.snippets) out.getOrPut(l) { ArrayList() }.addAll(s)
        return out
    }
}

/** Extensions bundled inside the app. There is no online registry yet; these install through the same real flow. */
object OfficialExtensions {
    val all: List<String> = listOf(
        """{"id":"xc.web-snippets","name":"Web snippets","version":"1.0.0","publisher":"XC CODITER","description":"Handy HTML, CSS and JavaScript snippets.","permissions":[],"contributes":{"snippets":{"html":[{"label":"card","insert":"<div class=\"card\">\n  <h2>§</h2>\n  <p></p>\n</div>"},{"label":"navbar","insert":"<nav>\n  <a href=\"#\">§</a>\n</nav>"}],"css":[{"label":"center","insert":"margin: 0 auto;\ntext-align: center;§"},{"label":"shadow","insert":"box-shadow: 0 2px 8px rgba(0,0,0,0.3);§"}],"javascript":[{"label":"fetchjson","insert":"fetch('§').then(r => r.json()).then(data => console.log(data))"},{"label":"listener","insert":"document.querySelector('§').addEventListener('click', () => {\n  \n})"}]}}}""",
        """{"id":"xc.python-snippets","name":"Python snippets","version":"1.0.0","publisher":"XC CODITER","description":"Common Python patterns.","permissions":[],"contributes":{"snippets":{"python":[{"label":"readfile","insert":"with open('§') as f:\n    data = f.read()"},{"label":"listcomp","insert":"[x for x in § if x]"},{"label":"initclass","insert":"class §:\n    def __init__(self):\n        pass"},{"label":"argparse","insert":"import argparse\n\nparser = argparse.ArgumentParser()\nparser.add_argument('§')\nargs = parser.parse_args()"}]}}}""",
        """{"id":"xc.c-snippets","name":"C snippets","version":"1.0.0","publisher":"XC CODITER","description":"Common C patterns.","permissions":[],"contributes":{"snippets":{"c":[{"label":"mallocarr","insert":"int *arr = malloc(§ * sizeof(int));\nif (!arr) return 1;\nfree(arr);"},{"label":"readline","insert":"char buf[256];\nfgets(buf, sizeof buf, stdin);§"}]}}}""",
    )
}

data class SettingsData(val light: Boolean, val fontSize: Int, val tabSize: Int, val autoSave: Boolean)

object SettingsCodec {
    fun encode(s: SettingsData) =
        "{\"xcSettings\":1,\"light\":${s.light},\"fontSize\":${s.fontSize},\"tabSize\":${s.tabSize},\"autoSave\":${s.autoSave}}"

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): SettingsData {
        val m = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Not an XC CODITER settings export")
        require(m["xcSettings"] == 1.0) { "Not an XC CODITER settings export" }
        val light = m["light"] as? Boolean ?: throw IllegalArgumentException("\"light\" missing")
        val font = (m["fontSize"] as? Double)?.toInt() ?: throw IllegalArgumentException("\"fontSize\" missing")
        val tab = (m["tabSize"] as? Double)?.toInt() ?: throw IllegalArgumentException("\"tabSize\" missing")
        val auto = m["autoSave"] as? Boolean ?: throw IllegalArgumentException("\"autoSave\" missing")
        require(font in 10..24) { "fontSize must be 10-24" }
        require(tab in setOf(2, 4, 8)) { "tabSize must be 2, 4 or 8" }
        return SettingsData(light, font, tab, auto)
    }
}
