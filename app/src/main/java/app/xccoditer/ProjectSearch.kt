package app.xccoditer

import java.io.File

data class Match(val file: File, val line: Int, val text: String)

object ProjectSearch {
    private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea")
    const val MAX_FILE_BYTES = 1_000_000L
    const val MAX_RESULTS = 500

    fun files(root: File): List<File> =
        root.walkTopDown().onEnter { it == root || it.name !in skipDirs }
            .filter { it.isFile && !it.name.endsWith(".xctmp") }.sortedBy { it.path }.toList()

    private fun isText(f: File): Boolean {
        if (f.length() > MAX_FILE_BYTES) return false
        val buf = ByteArray(4096)
        val n = f.inputStream().use { it.read(buf) }
        return (0 until maxOf(n, 0)).none { buf[it] == 0.toByte() }
    }

    fun search(root: File, query: String, ignoreCase: Boolean = true, cancelled: () -> Boolean = { false }): List<Match> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (f in files(root)) {
            if (cancelled() || out.size >= MAX_RESULTS) break
            if (!isText(f)) continue
            f.useLines { lines ->
                lines.forEachIndexed { i, line ->
                    if (out.size < MAX_RESULTS && line.contains(query, ignoreCase)) out += Match(f, i + 1, line.trim().take(200))
                }
            }
        }
        return out
    }

    fun matchingFiles(root: File, query: String, ignoreCase: Boolean = true): List<File> {
        if (query.isEmpty()) return emptyList()
        return files(root).filter { isText(it) && it.readText().contains(query, ignoreCase) }
    }

    fun countOccurrences(text: String, query: String, ignoreCase: Boolean = true): Int {
        if (query.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(query, 0, ignoreCase)
        while (i >= 0) { n++; i = text.indexOf(query, i + query.length, ignoreCase) }
        return n
    }

    /** Literal replace in each file. Returns replacements per changed file. */
    fun replace(files: Collection<File>, query: String, with: String, ignoreCase: Boolean = true): Map<File, Int> {
        val done = LinkedHashMap<File, Int>()
        if (query.isEmpty()) return done
        for (f in files) {
            val text = f.readText()
            val n = countOccurrences(text, query, ignoreCase)
            if (n > 0) { f.writeText(text.replace(query, with, ignoreCase)); done[f] = n }
        }
        return done
    }
}
