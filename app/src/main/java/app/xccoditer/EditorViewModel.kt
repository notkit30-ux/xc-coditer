package app.xccoditer

import android.app.Application
import android.content.Context
import android.net.Uri
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
    val workspace = Workspace(File(app.filesDir, "workspace"))
    val tabs = mutableStateListOf<EditorTab>()
    var activeIndex by mutableStateOf(0)
    val expanded = mutableStateListOf<String>()
    var treeVersion by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)
    var playPage by mutableStateOf<PlayPage?>(null)
    var scrollToLine by mutableStateOf<Int?>(null)

    // ---- settings (persisted in app-private SharedPreferences) ----
    private val prefs = app.getSharedPreferences("xc_settings", Context.MODE_PRIVATE)
    var lightTheme by mutableStateOf(prefs.getBoolean("light", false))
        private set
    var fontSize by mutableStateOf(prefs.getInt("font", 14))
        private set
    var tabSize by mutableStateOf(prefs.getInt("tab", 4))
        private set
    var autoSave by mutableStateOf(prefs.getBoolean("autosave", true))
        private set

    fun updateLight(v: Boolean) { lightTheme = v; Pal.light = v; prefs.edit().putBoolean("light", v).apply() }
    fun updateFontSize(v: Int) { fontSize = v.coerceIn(10, 24); prefs.edit().putInt("font", fontSize).apply() }
    fun updateTabSize(v: Int) { tabSize = v; prefs.edit().putInt("tab", v).apply() }
    fun updateAutoSave(v: Boolean) { autoSave = v; prefs.edit().putBoolean("autosave", v).apply() }
    fun resetSettings() { updateLight(false); updateFontSize(14); updateTabSize(4); updateAutoSave(true) }

    fun exportSettings(): String = SettingsCodec.encode(SettingsData(lightTheme, fontSize, tabSize, autoSave))

    fun importSettings(text: String) {
        runCatching { SettingsCodec.decode(text.trim()) }
            .onSuccess { updateLight(it.light); updateFontSize(it.fontSize); updateTabSize(it.tabSize); updateAutoSave(it.autoSave); message = "Settings imported" }
            .onFailure { message = "Import failed: ${it.message}" }
    }

    // ---- extensions (data-only; see docs/EXTENSIONS.md) ----
    private val extensionStore = ExtensionStore(File(app.filesDir, "extensions"))
    private var extVersion by mutableStateOf(0)

    fun installedExtensions(): List<InstalledExtension> {
        @Suppress("UNUSED_VARIABLE") val v = extVersion
        return extensionStore.list()
    }

    private fun reloadExtensions() {
        Suggestions.extras = extensionStore.enabledSnippets()
        extVersion++
    }

    fun installExtension(json: String) {
        runCatching { extensionStore.install(json) }
            .onSuccess { reloadExtensions(); message = "Installed ${it.name} ${it.version}" }
            .onFailure { message = "Install failed: ${it.message}" }
    }

    var registryUrl by mutableStateOf(prefs.getString("registry_url", Registry.DEFAULT_URL) ?: Registry.DEFAULT_URL)
        private set
    var registryEntries by mutableStateOf<List<RegistryEntry>?>(null)
        private set
    var registryStatus by mutableStateOf("")
        private set

    fun setRegistry(url: String) {
        runCatching { Registry.requireHttps(url) }
            .onSuccess { registryUrl = it; prefs.edit().putString("registry_url", it).apply(); registryEntries = null; registryStatus = "" }
            .onFailure { registryStatus = it.message ?: "Invalid URL" }
    }

    fun browseRegistry() {
        viewModelScope.launch {
            registryStatus = "Contacting ${runCatching { java.net.URI(registryUrl).host }.getOrNull() ?: "registry"}…"
            runCatching { withContext(Dispatchers.IO) { Registry.fetchIndex(registryUrl) } }
                .onSuccess { registryEntries = it; registryStatus = "${it.size} extension(s) listed" }
                .onFailure { registryEntries = null; registryStatus = GitOps.friendly(it).let { m -> if (m.startsWith("Server") || m.startsWith("Registry") || m.startsWith("No internet")) m else (it.message ?: m) } }
        }
    }

    fun installFromRegistry(e: RegistryEntry) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Registry.install(e, registryUrl, extensionStore) } }
                .onSuccess { reloadExtensions(); message = "Installed ${it.name} ${it.version} (hash verified)" }
                .onFailure { message = "Install failed: ${it.message}" }
        }
    }

    fun setExtensionEnabled(id: String, enabled: Boolean) { extensionStore.setEnabled(id, enabled); reloadExtensions() }
    fun uninstallExtension(id: String) { extensionStore.uninstall(id); reloadExtensions() }

    // ---- phone folders (copy-based) ----
    private fun under(f: File, dir: File) = f == dir || f.path.startsWith(dir.path + File.separator)

    fun importFolder(uri: Uri) {
        viewModelScope.launch {
            message = "Importing…"
            runCatching { withContext(Dispatchers.IO) { FolderCopy.importTree(getApplication(), uri, workspace, workspace.root) } }
                .onSuccess { (dir, st) ->
                    expanded.add(dir.path)
                    refreshTree()
                    message = "Imported ${st.files} file(s) into ${dir.name}" + if (st.skipped > 0) "; skipped ${st.skipped} (too large, over limits, or unreadable)" else ""
                }
                .onFailure(::fail)
        }
    }

    fun exportFolder(src: File, uri: Uri) {
        val pending = tabs.filter { it.dirty && under(it.file, src) }.map { it to it.value.text }
        viewModelScope.launch {
            message = "Exporting…"
            runCatching {
                withContext(Dispatchers.IO) {
                    pending.forEach { (t, text) -> workspace.write(t.file, text) }
                    FolderCopy.exportTree(getApplication(), src, uri)
                }
            }.onSuccess { (name, st) ->
                pending.forEach { (t, text) -> t.saved = text }
                message = "Exported ${st.files} file(s) to \"$name\"" + if (st.skipped > 0) "; skipped ${st.skipped}" else ""
            }.onFailure(::fail)
        }
    }

    // ---- git ----
    private val secrets by lazy { SecretStore(app) }
    var gitName by mutableStateOf(prefs.getString("git_name", "") ?: "")
        private set
    var gitEmail by mutableStateOf(prefs.getString("git_email", "") ?: "")
        private set
    var gitUser by mutableStateOf(prefs.getString("git_user", "") ?: "")
        private set
    var tokenSaved by mutableStateOf(false)
        private set
    var secretsAvailable by mutableStateOf(true)
        private set
    var gitRepo by mutableStateOf<File?>(null)
    var gitText by mutableStateOf("")
        private set
    var gitBusy by mutableStateOf(false)
        private set

    fun loadGitState() {
        viewModelScope.launch {
            val (saved, avail) = withContext(Dispatchers.IO) { !secrets.get("git_token").isNullOrBlank() to secrets.available }
            tokenSaved = saved
            secretsAvailable = avail
        }
    }

    /** An empty [token] keeps the saved one. */
    fun saveGitSettings(name: String, email: String, user: String, token: String) {
        gitName = name.trim(); gitEmail = email.trim(); gitUser = user.trim()
        prefs.edit().putString("git_name", gitName).putString("git_email", gitEmail).putString("git_user", gitUser).apply()
        viewModelScope.launch {
            if (token.isNotBlank()) {
                val ok = withContext(Dispatchers.IO) { secrets.put("git_token", token.trim()) }
                tokenSaved = ok
                message = if (ok) "Git settings saved (token stored encrypted)" else "Saved name and email, but the token could not be stored securely on this device"
            } else message = "Git settings saved"
        }
    }

    fun forgetGitToken() { secrets.remove("git_token"); tokenSaved = false; message = "Token removed" }

    fun gitCandidates(): List<File> {
        @Suppress("UNUSED_VARIABLE") val v = treeVersion
        return listOf(workspace.root) + workspace.list(workspace.root).filter { it.isDirectory }
    }

    private fun gitAuth(): GitAuth? = secrets.get("git_token")?.takeIf { it.isNotBlank() }?.let { GitAuth(gitUser, it) }

    private fun closeTabsUnder(dir: File) {
        tabs.removeAll { under(it.file, dir) }
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
    }

    private fun gitRun(closesEditors: Boolean = false, block: (File, GitAuth?) -> String) {
        val repo = gitRepo ?: run { gitText = "Choose a repository first"; return }
        if (gitBusy) return
        if (closesEditors && hasUnsavedUnder(repo)) { gitText = "Save or close edited files in this repository first."; return }
        viewModelScope.launch {
            gitBusy = true
            gitText = "Working…"
            gitText = try { withContext(Dispatchers.IO) { block(repo, gitAuth()) } } catch (e: Exception) { GitOps.friendly(e) }
            if (closesEditors) closeTabsUnder(repo)
            refreshTree()
            gitBusy = false
        }
    }

    fun gitStatus() = gitRun { d, _ -> GitOps.status(d) }
    fun gitDiff() = gitRun { d, _ -> GitOps.diff(d) }
    fun gitLog() = gitRun { d, _ -> GitOps.log(d) }
    fun gitBranches() = gitRun { d, _ -> GitOps.branches(d) }
    fun gitCommit(msg: String) { saveAll(); gitRun { d, _ -> GitOps.commitAll(d, msg, GitIdentity(gitName, gitEmail)) } }
    fun gitPush() = gitRun { d, a -> GitOps.push(d, a) }
    fun gitPull() = gitRun(true) { d, a -> GitOps.pull(d, a) }
    fun gitCheckout(n: String) = gitRun(true) { d, _ -> GitOps.checkout(d, n) }
    fun gitNewBranch(n: String) = gitRun(true) { d, _ -> GitOps.createBranch(d, n) }
    fun gitInit(remote: String) = gitRun { d, _ ->
        GitOps.init(d)
        "Initialized empty repository in ${d.name}" + if (remote.isNotBlank()) "\n" + GitOps.setRemote(d, remote) else ""
    }

    fun gitClone(url: String) {
        if (gitBusy) return
        val base = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git").filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "repo" }
        viewModelScope.launch {
            gitBusy = true
            gitText = "Cloning… (this can take a while)"
            val dest = workspace.child(workspace.root, workspace.uniqueChildName(workspace.root, base))
            try {
                val msg = withContext(Dispatchers.IO) { GitOps.clone(url, dest, gitAuth()) }
                gitRepo = dest
                gitText = msg
            } catch (e: Exception) {
                gitText = GitOps.friendly(e)
                if (dest.exists() && !GitOps.isRepo(dest)) dest.deleteRecursively()
            }
            refreshTree()
            gitBusy = false
        }
    }

    // ---- terminal ----
    val terminals = mutableStateListOf<TerminalTab>()
    var activeTerminal by mutableStateOf(0)

    fun newTerminal() {
        terminals.add(TerminalTab("sh ${terminals.size + 1}", workspace.root))
        activeTerminal = terminals.lastIndex
    }

    fun ensureTerminal() { if (terminals.isEmpty()) newTerminal() }

    fun closeTerminal(i: Int) {
        terminals.getOrNull(i)?.stop()
        if (i in terminals.indices) terminals.removeAt(i)
        activeTerminal = activeTerminal.coerceAtMost(terminals.lastIndex).coerceAtLeast(0)
    }

    fun runCommand(tab: TerminalTab, raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || tab.running) return
        tab.history.add(cmd)
        tab.historyPos = -1
        tab.lines.add("$ $cmd")
        when {
            cmd == "clear" -> tab.lines.clear()
            cmd == "pwd" -> tab.lines.add(tab.cwd.path)
            cmd == "help" -> TERMINAL_HELP.lines().forEach { tab.lines.add(it) }
            cmd == "cd" || cmd.startsWith("cd ") -> {
                val t = ShellUtil.resolveCd(tab.cwd, cmd.removePrefix("cd").trim(), workspace.root)
                if (t == null) tab.lines.add("cd: no such directory") else tab.cwd = t
            }
            else -> exec(tab, cmd)
        }
    }

    private fun exec(tab: TerminalTab, cmd: String) {
        tab.running = true
        tab.stopped = false
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val exit = withContext(Dispatchers.IO) {
                try {
                    val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(tab.cwd).redirectErrorStream(true)
                    pb.environment()["HOME"] = workspace.root.path
                    pb.environment()["TERM"] = "dumb"
                    val p = pb.start()
                    tab.process = p
                    p.outputStream.close() // no stdin
                    var count = 0
                    val r = p.inputStream.bufferedReader()
                    while (true) {
                        val l = r.readLine() ?: break
                        count++
                        if (count <= 2000) withContext(Dispatchers.Main) { tab.lines.add(l.take(2000)) }
                        else if (count == 2001) withContext(Dispatchers.Main) { tab.lines.add("[output truncated at 2000 lines]") }
                    }
                    p.waitFor()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { tab.lines.add("error: ${e.message}") }
                    -1
                }
            }
            tab.process = null
            tab.lines.add((if (tab.stopped) "[stopped] " else "") + "[exit $exit, ${System.currentTimeMillis() - t0} ms]")
            tab.running = false
        }
    }

    val active: EditorTab? get() = tabs.getOrNull(activeIndex)

    init {
        Pal.light = lightTheme
        reloadExtensions()
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

    fun open(file: File, line: Int? = null) {
        val existing = tabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            activeIndex = existing
            line?.let { goToLine(it) }
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { workspace.read(file) } }
                .onSuccess {
                    tabs.add(EditorTab(file, it))
                    activeIndex = tabs.lastIndex
                    line?.let { l -> goToLine(l) }
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

    /** Called when the app is backgrounded. */
    fun onAppStopped() {
        if (autoSave) saveAll()
    }

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

    fun createFromTemplate(t: Template) = runCatching {
        val dir = workspace.createFolder(workspace.root, workspace.uniqueChildName(workspace.root, t.id))
        workspace.writeTree(dir, t.files)
        expanded.add(dir.path)
        t.files.keys.filter { it.contains('/') }.map { it.substringBeforeLast('/') }.distinct().forEach { sub ->
            expanded.add(File(dir, sub).path)
        }
        refreshTree()
        t.files.keys.firstOrNull { it.endsWith(".html") || it.endsWith(".sql") || it.endsWith(".js") || it.endsWith(".py") }
            ?.let { open(File(dir, it)) } ?: open(File(dir, t.files.keys.first()))
        message = t.howToRun
    }.onFailure(::fail)

    fun rename(f: File, newName: String) = runCatching {
        val target = workspace.rename(f, newName)
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

    // ---- Termux (Java, Kotlin, C, C++, Go, Rust, PHP, Ruby, shell) ----
    var pendingTermux by mutableStateOf<EditorTab?>(null)
    private val termuxJobs = HashMap<Int, TerminalTab>()
    var termuxSummary by mutableStateOf("")
        private set

    private fun termuxPayload(tab: EditorTab): Triple<String, List<Pair<String, ByteArray>>, String>? {
        val folder = tab.file.parentFile ?: return null
        val all = TermuxScript.collect(folder)
        val rel = tab.file.relativeTo(folder).invariantSeparatorsPath
        val files = (all ?: listOf(rel to tab.value.text.toByteArray())).toMutableList()
        // use the editor's current text for the file being run
        val idx = files.indexOfFirst { it.first == rel }
        if (idx >= 0) files[idx] = rel to tab.value.text.toByteArray() else files += rel to tab.value.text.toByteArray()
        val cmd = TermuxScript.commandFor(rel) ?: return null
        val note = if (all == null) "folder too large, sending only this file" else "${files.size} file(s) from ${folder.name}"
        return Triple(cmd, files, note)
    }

    fun requestTermux(tab: EditorTab) {
        if (TermuxScript.commandFor(tab.file.name) == null) { message = "No Termux command for this file type"; return }
        val ctx = getApplication<Application>()
        if (!TermuxBridge.isInstalled(ctx)) { message = "Termux is not installed. " + TermuxScript.SETUP; return }
        termuxSummary = termuxPayload(tab)?.let { "Send ${it.third} to Termux and run: ${it.first.take(120)}" } ?: ""
        pendingTermux = tab
    }

    fun confirmTermux(tab: EditorTab) {
        pendingTermux = null
        val payload = termuxPayload(tab) ?: return
        val ctx = getApplication<Application>()
        val term = terminals.firstOrNull { it.title == "Termux" } ?: TerminalTab("Termux", workspace.root).also { terminals.add(it) }
        if (term.running) { message = "A Termux job is already running"; return }
        activeTerminal = terminals.indexOf(term)
        terminalRequested = true
        term.lines.add("$ [Termux] " + payload.first)
        term.lines.add("[sent ${payload.third}; waiting for Termux…]")
        term.running = true
        term.stopped = false
        try {
            TermuxBridge.listener = { id, res -> viewModelScope.launch(Dispatchers.Main) { onTermuxResult(id, res) } }
            val script = TermuxScript.build(tab.file.parentFile!!.name, payload.second, payload.first)
            val id = TermuxBridge.run(ctx, script)
            termuxJobs[id] = term
            term.stopHook = { termuxJobs.remove(id); term.lines.add("[stopped waiting; the job may still be running in Termux]"); term.running = false }
        } catch (e: SecurityException) {
            term.running = false
            term.lines.add("Termux refused the request: ${e.message}")
            TermuxScript.SETUP.lines().forEach { term.lines.add(it) }
        } catch (e: Exception) {
            term.running = false
            term.lines.add("Could not reach Termux: ${e.message}")
        }
    }

    private fun onTermuxResult(id: Int, r: TermuxResult) {
        val term = termuxJobs.remove(id) ?: return
        term.stopHook = null
        if (r.error != null) term.lines.add("Termux error: ${r.error}")
        r.stdout.lines().dropLastWhile { it.isEmpty() }.take(2000).forEach { term.lines.add(it) }
        r.stderr.lines().dropLastWhile { it.isEmpty() }.take(2000).forEach { term.lines.add(it) }
        TermuxScript.hint(r.stderr + "\n" + (r.error ?: ""))?.let { term.lines.add(it) }
        term.lines.add("[exit ${r.exitCode}]")
        term.running = false
    }

    // ---- Python ----
    var terminalRequested by mutableStateOf(false)
    var pendingPython by mutableStateOf<EditorTab?>(null)
    private val trustedPython = HashSet<File>()

    fun confirmPython(tab: EditorTab) { trustedPython.add(tab.file); pendingPython = null; runPython(tab) }

    private fun runPython(tab: EditorTab) {
        val text = tab.value.text
        val term = terminals.firstOrNull { it.title == "Python" } ?: TerminalTab("Python", workspace.root).also { terminals.add(it) }
        if (term.running) { message = "Python is already running. Stop it first."; return }
        activeTerminal = terminals.indexOf(term)
        terminalRequested = true
        term.lines.add("$ python ${tab.file.name}")
        term.running = true
        term.stopped = false
        val ctx = getApplication<Application>()
        term.stopHook = { PythonRunner.stop(ctx) }
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            var lines = 0
            val code = withContext(Dispatchers.IO) {
                try {
                    workspace.write(tab.file, text)
                    withContext(Dispatchers.Main) { tab.saved = text }
                    PythonRunner.run(ctx, tab.file, emptyList()) { l ->
                        lines++
                        if (lines <= 5000) viewModelScope.launch(Dispatchers.Main) { term.lines.add(l.take(2000)) }
                        else if (lines == 5001) viewModelScope.launch(Dispatchers.Main) { term.lines.add("[output truncated at 5000 lines]") }
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { term.lines.add("Python could not start: ${e.message ?: e.javaClass.simpleName}") }
                    -1
                }
            }
            term.stopHook = null
            term.lines.add((if (term.stopped) "[stopped] " else "") + "[exit $code, ${System.currentTimeMillis() - t0} ms]")
            term.running = false
        }
    }

    fun play() {
        val tab = active ?: return
        if (TermuxScript.commandFor(tab.file.name) != null) { requestTermux(tab); return }
        if (tab.file.extension.equals("py", ignoreCase = true)) {
            if (tab.file in trustedPython) runPython(tab) else pendingPython = tab
            return
        }
        if (tab.file.extension.equals("sql", ignoreCase = true)) {
            val script = tab.value.text
            viewModelScope.launch {
                val html = withContext(Dispatchers.Default) { SqlRunner.runToHtml(script) }
                playPage = PlayPage(tab.file.name, html)
            }
            return
        }
        when (val r = Play.build(tab.file.name, tab.lang, tab.value.text) { readSibling(tab.file, it) }) {
            is PlayResult.Page -> playPage = PlayPage(tab.file.name, r.html)
            is PlayResult.Unsupported -> message = r.reason
        }
    }

    // ---- search across files ----

    var searchResults by mutableStateOf<List<Match>>(emptyList())
        private set
    var searchInfo by mutableStateOf("")
        private set

    fun searchProject(query: String) {
        if (query.isEmpty()) return
        viewModelScope.launch {
            searchInfo = "Searching…"
            val r = withContext(Dispatchers.IO) { ProjectSearch.search(workspace.root, query) }
            searchResults = r
            searchInfo = "${r.size} match(es)" + if (r.size >= ProjectSearch.MAX_RESULTS) " (showing the first ${ProjectSearch.MAX_RESULTS})" else ""
        }
    }

    /** Replaces in files without unsaved editor changes; reports skipped files. */
    fun replaceProject(query: String, with: String) {
        if (query.isEmpty()) return
        viewModelScope.launch {
            val dirty = tabs.filter { it.dirty }.map { it.file }.toSet()
            val (done, skipped) = withContext(Dispatchers.IO) {
                val files = ProjectSearch.matchingFiles(workspace.root, query)
                ProjectSearch.replace(files.filter { it !in dirty }, query, with) to files.count { it in dirty }
            }
            tabs.removeAll { it.file in done.keys }
            activeIndex = activeIndex.coerceAtMost(tabs.lastIndex).coerceAtLeast(0)
            searchResults = emptyList()
            searchInfo = ""
            refreshTree()
            message = "Replaced ${done.values.sum()} occurrence(s) in ${done.size} file(s)" +
                if (skipped > 0) "; skipped $skipped file(s) with unsaved changes" else ""
        }
    }

    // ---- find / replace in the current file ----

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

    fun countMatches(query: String): Int = ProjectSearch.countOccurrences(active?.value?.text ?: "", query)

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
        scrollToLine = l
    }
}
