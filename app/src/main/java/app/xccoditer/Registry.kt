package app.xccoditer

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class RegistryEntry(
    val id: String, val name: String, val version: String, val publisher: String,
    val description: String, val languages: List<String>, val url: String, val sha256: String,
)

/** Client for an extension registry: an https index.json plus manifests whose SHA-256 is checked before install. */
object Registry {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/notkit30-ux/xc-coditer/main/registry/index.json"
    const val MAX_BYTES = 200_000
    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    fun requireHttps(url: String): String {
        val u = url.trim()
        require(u.startsWith("https://") && u.length < 500) { "Registry URLs must start with https://" }
        return u
    }

    @Suppress("UNCHECKED_CAST")
    fun parseIndex(text: String): List<RegistryEntry> {
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Registry index must be a JSON object")
        require(root["registry"] == 1.0) { "Unsupported registry format" }
        val list = root["extensions"] as? List<*> ?: throw IllegalArgumentException("Registry has no \"extensions\" list")
        require(list.size <= 500) { "Registry is too large" }
        return list.map { e ->
            val m = e as? Map<String, Any?> ?: throw IllegalArgumentException("Bad registry entry")
            fun s(k: String) = (m[k] as? String)?.takeIf { it.isNotBlank() && it.length <= 500 } ?: throw IllegalArgumentException("Registry entry is missing \"$k\"")
            val sha = s("sha256")
            require(hex64.matches(sha)) { "Registry entry has an invalid sha256" }
            RegistryEntry(
                s("id"), s("name"), s("version"), s("publisher"), (m["description"] as? String ?: "").take(300),
                (m["languages"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(), s("url"), sha.lowercase(),
            )
        }
    }

    fun resolve(indexUrl: String, rel: String): String {
        val r = URI(requireHttps(indexUrl)).resolve(rel).toString()
        return requireHttps(r)
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun search(entries: List<RegistryEntry>, query: String): List<RegistryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.contains(q, true) || it.id.contains(q, true) || it.description.contains(q, true) || it.languages.any { l -> l.contains(q, true) }
        }
    }

    /** Plain https GET with size and time limits. */
    fun download(url: String, maxBytes: Int = MAX_BYTES): ByteArray {
        val c = URL(requireHttps(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        try {
            check(c.responseCode == 200) { "Server answered ${c.responseCode}" }
            check(c.url.protocol == "https") { "Redirected away from https" }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    check(out.size() <= maxBytes) { "Download is larger than the allowed ${maxBytes / 1000} KB" }
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    fun fetchIndex(indexUrl: String, fetch: (String) -> ByteArray = ::download): List<RegistryEntry> =
        parseIndex(String(fetch(requireHttps(indexUrl)), Charsets.UTF_8))

    /** Downloads the manifest, checks its hash and that it matches the listing, then installs it. */
    fun install(entry: RegistryEntry, indexUrl: String, store: ExtensionStore, fetch: (String) -> ByteArray = ::download): Extension {
        val bytes = fetch(resolve(indexUrl, entry.url))
        require(sha256Hex(bytes) == entry.sha256) { "Integrity check failed: the file does not match the registry's hash. Nothing was installed." }
        val text = String(bytes, Charsets.UTF_8)
        val ext = ExtensionManifest.parse(text)
        require(ext.id == entry.id && ext.version == entry.version) { "The downloaded extension does not match its registry listing. Nothing was installed." }
        return store.install(text)
    }
}
