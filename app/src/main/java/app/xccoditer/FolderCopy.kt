package app.xccoditer

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Copy-based access to phone folders through Android's folder picker (no storage permission needed).
 * This is import/export, not live editing: changes in the app reach the phone folder only when you export.
 */
object FolderCopy {
    const val MAX_FILES = 3000
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

    class Stats { var files = 0; var skipped = 0; var bytes = 0L }

    private fun okName(n: String) = n.isNotBlank() && '/' !in n && '\\' !in n && n != "." && n != ".."

    fun importTree(ctx: Context, uri: Uri, ws: Workspace, into: File): Pair<File, Stats> {
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: throw IllegalStateException("Could not open that folder")
        val name = root.name?.takeIf { okName(it) } ?: "imported"
        val dest = ws.createFolder(into, ws.uniqueChildName(into, name))
        val st = Stats()
        fun copy(doc: DocumentFile, dir: File) {
            for (c in doc.listFiles()) {
                val n = c.name ?: continue
                if (!okName(n)) { st.skipped++; continue }
                val target = File(dir, n)
                if (!ws.contains(target)) { st.skipped++; continue }
                if (c.isDirectory) { target.mkdirs(); copy(c, target); continue }
                if (st.files >= MAX_FILES || c.length() > MAX_FILE_BYTES || st.bytes + c.length() > MAX_TOTAL_BYTES) { st.skipped++; continue }
                try {
                    ctx.contentResolver.openInputStream(c.uri)?.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                    st.files++; st.bytes += target.length()
                } catch (e: Exception) { st.skipped++ }
            }
        }
        copy(root, dest)
        return dest to st
    }

    fun exportTree(ctx: Context, src: File, uri: Uri): Pair<String, Stats> {
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: throw IllegalStateException("Could not open that folder")
        var name = src.name
        var i = 2
        while (root.findFile(name) != null) { name = "${src.name}-$i"; i++ }
        val target = root.createDirectory(name) ?: throw IllegalStateException("Could not create a folder there")
        val st = Stats()
        fun copy(dir: File, out: DocumentFile) {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.name.endsWith(".xctmp")) continue
                if (f.isDirectory) { out.createDirectory(f.name)?.let { copy(f, it) } ?: st.skipped++; continue }
                if (st.files >= MAX_FILES || f.length() > MAX_FILE_BYTES || st.bytes + f.length() > MAX_TOTAL_BYTES) { st.skipped++; continue }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
                try {
                    val d = out.createFile(mime, f.name) ?: run { st.skipped++; null } ?: continue
                    ctx.contentResolver.openOutputStream(d.uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                    st.files++; st.bytes += f.length()
                } catch (e: Exception) { st.skipped++ }
            }
        }
        copy(src, target)
        return name to st
    }
}
