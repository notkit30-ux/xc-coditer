package app.xccoditer

import java.io.File
import java.io.IOException

/** File operations confined to a single workspace root. Pure JVM so it can be unit tested. */
class Workspace(root: File) {
    val root: File = root.canonicalFile

    init {
        this.root.mkdirs()
    }

    fun list(dir: File): List<File> =
        dir.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()

    /** Resolve [name] inside [parent]; rejects separators, "." / ".." and anything escaping the root. */
    fun child(parent: File, name: String): File {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name cannot be empty" }
        require('/' !in clean && '\\' !in clean && clean != "." && clean != "..") { "Invalid name" }
        val f = File(parent, clean).canonicalFile
        require(contains(f)) { "Path is outside the workspace" }
        return f
    }

    fun contains(f: File): Boolean {
        val p = f.canonicalFile.path
        return p == root.path || p.startsWith(root.path + File.separator)
    }

    fun createFile(parent: File, name: String): File {
        val f = child(parent, name)
        if (f.exists()) throw IOException("\"${f.name}\" already exists")
        if (!f.createNewFile()) throw IOException("Could not create ${f.name}")
        return f
    }

    fun createFolder(parent: File, name: String): File {
        val f = child(parent, name)
        if (f.exists()) throw IOException("\"${f.name}\" already exists")
        if (!f.mkdirs()) throw IOException("Could not create ${f.name}")
        return f
    }

    fun rename(f: File, newName: String): File {
        require(contains(f) && f != root) { "Cannot rename this item" }
        val target = child(f.parentFile!!, newName)
        if (target.exists()) throw IOException("\"${target.name}\" already exists")
        if (!f.renameTo(target)) throw IOException("Rename failed")
        return target
    }

    fun delete(f: File) {
        require(contains(f) && f != root) { "Cannot delete this item" }
        if (!f.deleteRecursively()) throw IOException("Could not delete ${f.name}")
    }

    fun read(f: File): String {
        require(contains(f)) { "Path is outside the workspace" }
        if (f.length() > MAX_OPEN_BYTES) throw IOException("File is larger than ${MAX_OPEN_BYTES / 1024 / 1024} MB")
        return f.readText()
    }

    /** Writes via a temp file then rename so a crash cannot leave a half-written file. */
    fun write(f: File, text: String) {
        require(contains(f)) { "Path is outside the workspace" }
        val tmp = File(f.parentFile, f.name + ".xctmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_OPEN_BYTES = 2L * 1024 * 1024
    }
}
