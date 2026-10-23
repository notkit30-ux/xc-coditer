package app.xccoditer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorTab(val file: File, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var saved by mutableStateOf(initial)
    val undoStack = ArrayDeque<String>()
    val redoStack = ArrayDeque<String>()
    val dirty: Boolean get() = value.text != saved
    val lang: Lang = Lang.forFileName(file.name)
}

data class PlayPage(val title: String, val html: String)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    val workspace = Workspace(java.io.File(app.filesDir, "workspace"))
    val tabs = mutableStateListOf<EditorTab>()
    var activeIndex by mutableStateOf(0)
    val expanded = mutableStateListOf<String>()
    var treeVersion by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)
    var playPage by mutableStateOf<PlayPage?>(null)
    val tabSize = 4

    val active: EditorTab? get() = tabs.getOrNull(activeIndex)

    init {
        seedIfEmpty()
    }

    private fun seedIfEmpty() {
        if (workspace.list(workspace.root).isNotEmpty()) return
        runCatching {
            workspace.write(
                workspace.createFile(workspace.root, "hello.py"),
                "# Welcome to XC CODITER\nprint(\"Hello, world\")\n",
            )
            workspace.write(
                workspace.createFile(workspace.root, "README.md"),
                "# My workspace\n\nFiles here are stored privately inside the app.\n",
            )
        }
    }

    private fun fail(e: Throwable) {
        message = e.message ?: e.javaClass.simpleName
    }

    fun refreshTree() {
        treeVersion++
    }

    fun toggleExpanded(dir: File) {
        val p = dir.path
        if (!expanded.remove(p)) expanded.add(p)
    }

    /** Flattened visible tree: (file, depth). */
    fun visibleTree(): List<Pair<File, Int>> {
        @Suppress("UNUSED_VARIABLE") val v = treeVersion
        val out = ArrayList<Pair<File, Int>>()
        fun walk(dir: File, depth: Int) {
            for (f in workspace.list(dir)) {
                if (f.name.endsWith(".xctmp")) continue
                out += f to depth
                if (f.isDirectory && f.path in expanded) walk(f, depth + 1)
            }
        }
        walk(workspace.root, 0)
        return out
    }

    // ---- file operations (disk work off the main thread) ----

    fun open(file: File) {
        val existing = tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            activeIndex = existing
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { workspace.read(file) } }
                .onSuccess {
                    tabs.add(EditorTab(file, it))
                    activeIndex = tabs.lastIndex
                }
                .onFailure(::fail)
        }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
    }

    fun save(tab: EditorTab? = active) {
        tab ?: return
        val text = tab.value.text
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { workspace.write(tab.file, text) } }
                .onSuccess { tab.saved = text }
                .onFailure(::fail)
        }
    }

    fun saveAll() = tabs.filter { it.dirty }.forEach { save(it) }

    fun createFile(parent: File, name: String) = runCatching {
        val f = workspace.createFile(parent, name)
        if (parent != workspace.root && parent.path !in expanded) expanded.add(parent.path)
        refreshTree()
        open(f)
    }.onFailure(::fail)

    fun createFolder(parent: File, name: String) = runCatching {
        workspace.createFolder(parent, name)
        if (parent != workspace.root && parent.path !in expanded) expanded.add(parent.path)
        refreshTree()
    }.onFailure(::fail)

    fun rename(f: File, newName: String) = runCatching {
        val target = workspace.rename(f, newName)
        // Re-point open tabs: simplest correct behaviour is to close affected tabs (unsaved edits would be lost,
        // so only allow rename when none of them are dirty).
        refreshTree()
        tabs.removeAll { it.file == f || it.file.path.startsWith(f.path + File.separator) }
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
        if (target.isFile) open(target)
    }.onFailure(::fail)

    fun hasUnsavedUnder(f: File): Boolean =
        tabs.any { it.dirty && (it.file == f || it.file.path.startsWith(f.path + File.separator)) }

    fun delete(f: File) = runCatching {
        workspace.delete(f)
        tabs.removeAll { it.file == f || it.file.path.startsWith(f.path + File.separator) }
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
        refreshTree()
    }.onFailure(::fail)

    // ---- editing ----

    fun onEdit(tab: EditorTab, new: TextFieldValue) {
        val old = tab.value
        var next = new
        if (new.text != old.text) {
            if (new.text.length == old.text.length + 1 && new.selection.collapsed &&
                new.selection.start > 0 && new.text[new.selection.start - 1] == '\n'
            ) {
                next = autoIndent(new)
            }
            tab.undoStack.addLast(old.text)
            if (tab.undoStack.size > 200) tab.undoStack.removeFirst()
            tab.redoStack.clear()
        }
        tab.value = next
    }

    private fun autoIndent(v: TextFieldValue): TextFieldValue {
        val nl = v.selection.start - 1
        val prevStart = v.text.lastIndexOf('\n', nl - 1) + 1
        val prev = v.text.substring(prevStart, nl)
        var indent = prev.takeWhile { it == ' ' || it == '\t' }
        val t = prev.trimEnd()
        if (t.endsWith("{") || t.endsWith(":") || t.endsWith("(") || t.endsWith("[")) indent += " ".repeat(tabSize)
        if (indent.isEmpty()) return v
        val pos = v.selection.start
        return TextFieldValue(v.text.substring(0, pos) + indent + v.text.substring(pos), TextRange(pos + indent.length))
    }

    fun undo(tab: EditorTab? = active) {
        tab ?: return
        val prev = tab.undoStack.removeLastOrNull() ?: return
        tab.redoStack.addLast(tab.value.text)
        tab.value = TextFieldValue(prev, TextRange(prev.length.coerceAtMost(tab.value.selection.end)))
    }

    fun redo(tab: EditorTab? = active) {
        tab ?: return
        val next = tab.redoStack.removeLastOrNull() ?: return
        tab.undoStack.addLast(tab.value.text)
        tab.value = TextFieldValue(next, TextRange(next.length.coerceAtMost(tab.value.selection.end)))
    }

    fun insert(text: String) {
        val tab = active ?: return
        val v = tab.value
        val s = v.selection.min
        val e = v.selection.max
        val nt = v.text.substring(0, s) + text + v.text.substring(e)
        onEdit(tab, TextFieldValue(nt, TextRange(s + text.length)))
    }

    fun moveCursor(delta: Int) {
        val tab = active ?: return
        val v = tab.value
        val p = (v.selection.end + delta).coerceIn(0, v.text.length)
        tab.value = v.copy(selection = TextRange(p))
    }

    // ---- suggestions & instant play ----

    fun applySuggestion(s: Suggestion) {
        val tab = active ?: return
        val v = tab.value
        if (!v.selection.collapsed) return
        val (text, pos) = Suggestions.apply(v.text, v.selection.start, s)
        onEdit(tab, TextFieldValue(text, TextRange(pos)))
    }

    /** Reads a same-workspace file for HTML previews (prefers unsaved editor text). Small files only. */
    private fun readSibling(from: File, rel: String): String? = runCatching {
        val f = File(from.parentFile, rel).canonicalFile
        if (!workspace.contains(f) || !f.isFile || f.length() > 1_000_000) return@runCatching null
        tabs.firstOrNull { it.file.canonicalFile == f }?.value?.text ?: f.readText()
    }.getOrNull()

    fun play() {
        val tab = active ?: return
        when (val r = Play.build(tab.file.name, tab.lang, tab.value.text) { readSibling(tab.file, it) }) {
            is PlayResult.Page -> playPage = PlayPage(tab.file.name, r.html)
            is PlayResult.Unsupported -> message = r.reason
        }
    }

    // ---- find / replace ----

    fun findNext(query: String): Boolean {
        val tab = active ?: return false
        if (query.isEmpty()) return false
        val v = tab.value
        var i = v.text.indexOf(query, v.selection.end, ignoreCase = true)
        if (i < 0) i = v.text.indexOf(query, 0, ignoreCase = true)
        if (i < 0) return false
        tab.value = v.copy(selection = TextRange(i, i + query.length))
        return true
    }

    fun countMatches(query: String): Int {
        val tab = active ?: return 0
        if (query.isEmpty()) return 0
        var n = 0
        var i = tab.value.text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            n++
            i = tab.value.text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return n
    }

    fun replaceAll(query: String, with: String): Int {
        val tab = active ?: return 0
        if (query.isEmpty()) return 0
        val n = countMatches(query)
        if (n == 0) return 0
        val nt = tab.value.text.replace(query, with, ignoreCase = true)
        onEdit(tab, TextFieldValue(nt, TextRange(0)))
        return n
    }

    fun goToLine(line: Int) {
        val tab = active ?: return
        val text = tab.value.text
        var idx = 0
        var l = 1
        while (l < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) break
            idx = nl + 1
            l++
        }
        tab.value = tab.value.copy(selection = TextRange(idx))
    }
}
