package app.xccoditer

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = xcColorScheme()) { XcApp(vm) } }
    }

    override fun onStop() {
        super.onStop()
        vm.onAppStopped() // auto-save when backgrounded (if enabled in settings)
    }
}

private class Highlight(val lang: Lang, val light: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = Highlighter.tokenize(text.text, lang)
        val styled = buildAnnotatedString {
            append(text.text)
            for (s in spans) {
                val c = tokenColor(s.kind, light)
                addStyle(SpanStyle(color = c), s.start, s.end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XcApp(vm: EditorViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var palette by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var showRuntimes by remember { mutableStateOf(false) }
    var showExt by remember { mutableStateOf(false) }
    var showGit by remember { mutableStateOf(false) }
    var exportSrc by remember { mutableStateOf<java.io.File?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFolder(uri) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val s = exportSrc
        if (uri != null && s != null) vm.exportFolder(s, uri)
        exportSrc = null
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    val explorerContent: @Composable () -> Unit = {
        Explorer(vm, onTemplates = { showTemplates = true }, onExport = { exportSrc = it; exportLauncher.launch(null) }) { scope.launch { drawer.close() } }
    }
    val mainContent: @Composable () -> Unit = {
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel, titleContentColor = TextCol),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, "Explorer", tint = TextCol)
                        }
                    },
                    title = {
                        Column {
                            Text("XC CODITER", fontSize = 13.sp, color = Accent)
                            Text(vm.active?.file?.name ?: "No file open", fontSize = 12.sp, color = Dim, maxLines = 1)
                        }
                    },
                    actions = {
                        // reading tab.value makes this recompose on every edit, keeping the stack checks fresh
                        val canUndo = vm.active?.let { it.value; it.undoStack.isNotEmpty() } == true
                        val canRedo = vm.active?.let { it.value; it.redoStack.isNotEmpty() } == true
                        TextButton(onClick = { vm.undo() }, enabled = canUndo) { Text("Undo") }
                        TextButton(onClick = { vm.redo() }, enabled = canRedo) { Text("Redo") }
                        IconButton(onClick = { vm.save() }, enabled = vm.active != null) {
                            Icon(Icons.Default.Check, "Save", tint = TextCol)
                        }
                        IconButton(onClick = { vm.play() }, enabled = vm.active != null) {
                            Icon(Icons.Default.PlayArrow, "Play", tint = Accent)
                        }
                        IconButton(onClick = { findOpen = !findOpen }, enabled = vm.active != null) {
                            Icon(Icons.Default.Search, "Find", tint = TextCol)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextCol) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Command palette") }, onClick = { menu = false; palette = true })
                                DropdownMenuItem(text = { Text("Save all") }, onClick = { menu = false; vm.saveAll() })
                                DropdownMenuItem(text = { Text("Go to line") }, onClick = { menu = false; goTo = true })
                                DropdownMenuItem(text = { Text("Search in files") }, onClick = { menu = false; showSearch = true })
                                DropdownMenuItem(text = { Text("Quick open file") }, onClick = { menu = false; showQuick = true })
                                DropdownMenuItem(text = { Text("New from template") }, onClick = { menu = false; showTemplates = true })
                                DropdownMenuItem(text = { Text("Git") }, onClick = { menu = false; showGit = true })
                                DropdownMenuItem(text = { Text("Import folder from phone") }, onClick = { menu = false; importLauncher.launch(null) })
                                DropdownMenuItem(text = { Text("Export workspace to phone folder") }, onClick = { menu = false; exportSrc = vm.workspace.root; exportLauncher.launch(null) })
                                DropdownMenuItem(text = { Text("Terminal") }, onClick = { menu = false; showTerminal = true })
                                DropdownMenuItem(text = { Text("Runtime Manager") }, onClick = { menu = false; showRuntimes = true })
                                DropdownMenuItem(text = { Text("Extensions") }, onClick = { menu = false; showExt = true })
                                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; showSettings = true })
                            }
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                TabStrip(vm)
                if (findOpen && vm.active != null) FindBar(vm) { findOpen = false }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val tab = vm.active
                    if (tab == null) EmptyState() else CodeEditor(vm, tab)
                }
                if (vm.active != null) SuggestionBar(vm)
                if (vm.active != null) AccessoryBar(vm)
            }
        }
    }
    // Phones: slide-out explorer. Tablets / landscape (>= 840dp wide): explorer stays open beside the editor.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(300.dp), drawerContainerColor = Panel) { explorerContent() } }) { mainContent() }
        } else {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet(drawerContainerColor = Panel) { explorerContent() } }) { mainContent() }
        }
    }

    if (palette) {
        val cmds = listOf<Pair<String, () -> Unit>>(
            "Play (run instantly)" to { vm.play() },
            "Save" to { vm.save() },
            "Save all" to { vm.saveAll() },
            "Undo" to { vm.undo() },
            "Redo" to { vm.redo() },
            "Find" to { findOpen = true },
            "Go to line" to { goTo = true },
            "Open explorer" to { scope.launch { drawer.open() } },
            "Search in files" to { showSearch = true },
            "Quick open file" to { showQuick = true },
            "New from template" to { showTemplates = true },
            "Settings" to { showSettings = true },
            "Terminal" to { showTerminal = true },
            "Git" to { showGit = true },
            "Import folder from phone" to { importLauncher.launch(null) },
            "Runtime Manager" to { showRuntimes = true },
            "Extensions" to { showExt = true },
            "Toggle light/dark theme" to { vm.updateLight(!vm.lightTheme) },
            "Close tab" to { vm.closeTab(vm.activeIndex) },
        )
        CommandPalette(cmds) { palette = false }
    }
    vm.playPage?.let { PlayDialog(it) { vm.playPage = null } }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showSearch) SearchDialog(vm) { showSearch = false }
    if (showQuick) QuickOpenDialog(vm) { showQuick = false }
    if (showTemplates) TemplateDialog(vm) { showTemplates = false }
    if (showTerminal) TerminalDialog(vm) { showTerminal = false }
    if (showRuntimes) RuntimeManagerDialog { showRuntimes = false }
    if (showExt) ExtensionsDialog(vm) { showExt = false }
    if (showGit) GitDialog(vm) { showGit = false }
    LaunchedEffect(vm.terminalRequested) {
        if (vm.terminalRequested) { showTerminal = true; vm.terminalRequested = false }
    }
    vm.pendingPython?.let { t ->
        AlertDialog(
            onDismissRequest = { vm.pendingPython = null },
            title = { Text("Run ${t.file.name}?") },
            text = { Text("Python code runs inside this app with the app's permissions: it can read and change files in your workspace and use the network if the code asks. Only run code you trust.") },
            confirmButton = { TextButton(onClick = { vm.confirmPython(t) }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { vm.pendingPython = null }) { Text("Cancel") } },
        )
    }
    if (goTo) {
        TextPrompt("Go to line", "", "Line number", { goTo = false }) { s ->
            s.trim().toIntOrNull()?.let { vm.goToLine(it) }
            goTo = false
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Open the explorer (☰) and pick a file", color = Dim)
    }
}

@Composable
private fun TabStrip(vm: EditorViewModel) {
    if (vm.tabs.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())) {
        vm.tabs.forEachIndexed { i, t ->
            val sel = i == vm.activeIndex
            Row(
                Modifier.background(if (sel) Bg else Panel).clickable { vm.activeIndex = i }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (t.dirty) "● " else "") + t.file.name, color = if (sel) TextCol else Dim, fontSize = 13.sp, maxLines = 1)
                IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Close tab", tint = Dim, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(vm: EditorViewModel, tab: EditorTab) {
    val text = tab.value.text
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = vm.fontSize.sp, lineHeight = (vm.fontSize * 1.45f).sp, color = TextCol)
    val vScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(vm.scrollToLine, tab) {
        vm.scrollToLine?.let { l ->
            delay(80) // let the freshly opened tab lay out first
            val px = with(density) { (vm.fontSize * 1.45f).sp.toPx() }
            vScroll.animateScrollTo(((l - 1) * px).toInt().coerceAtLeast(0))
            vm.scrollToLine = null
        }
    }
    Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
        Text(
            (1..lines).joinToString("\n"),
            style = mono.copy(color = Dim),
            modifier = Modifier.background(Panel).padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            key(tab) {
                BasicTextField(
                    value = tab.value,
                    onValueChange = { vm.onEdit(tab, it) },
                    textStyle = mono,
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = Highlight(tab.lang, Pal.light),
                    modifier = Modifier.padding(8.dp).defaultMinSize(minWidth = 300.dp, minHeight = 400.dp),
                )
            }
        }
    }
}

@Composable
private fun FindBar(vm: EditorViewModel, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var r by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it; info = "${vm.countMatches(it)} matches" }, Modifier.weight(1f), singleLine = true, label = { Text("Find") })
            TextButton(onClick = { if (!vm.findNext(q)) info = "No matches" }) { Text("Next") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close find", tint = TextCol) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(r, { r = it }, Modifier.weight(1f), singleLine = true, label = { Text("Replace with") })
            TextButton(onClick = { info = "Replaced ${vm.replaceAll(q, r)}" }) { Text("Replace all") }
        }
        if (info.isNotEmpty()) Text(info, color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun AccessoryBar(vm: EditorViewModel) {
    val keys = listOf("⇥", "(", ")", "{", "}", "[", "]", "\"", "'", ";", ":", "/", "\\", "|", "<", ">", "=", "_", "#")
    Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { vm.moveCursor(-1) }) { Text("←") }
        TextButton(onClick = { vm.moveCursor(1) }) { Text("→") }
        keys.forEach { k ->
            TextButton(onClick = { vm.insert(if (k == "⇥") " ".repeat(vm.tabSize) else k) }) { Text(k, color = TextCol) }
        }
    }
}

@Composable
private fun CommandPalette(cmds: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Command palette") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Type a command") })
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(cmds.filter { it.first.contains(q, ignoreCase = true) }) { (name, run) ->
                        Text(name, Modifier.fillMaxWidth().clickable { onDismiss(); run() }.padding(12.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun TextPrompt(title: String, initial: String, label: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface Dlg {
    data class NewFile(val parent: File) : Dlg
    data class NewFolder(val parent: File) : Dlg
    data class Rename(val f: File) : Dlg
    data class Delete(val f: File) : Dlg
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Explorer(vm: EditorViewModel, onTemplates: () -> Unit, onExport: (File) -> Unit, closeDrawer: () -> Unit) {
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    val rows = vm.visibleTree()

    Column(Modifier.fillMaxHeight().widthIn(max = 320.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EXPLORER", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { dlg = Dlg.NewFile(vm.workspace.root) }) { Text("+ File") }
            TextButton(onClick = { dlg = Dlg.NewFolder(vm.workspace.root) }) { Text("+ Folder") }
            TextButton(onClick = { closeDrawer(); onTemplates() }) { Text("Template") }
        }
        if (rows.isEmpty()) Text("Empty workspace", Modifier.padding(16.dp), color = Dim)
        LazyColumn {
            items(rows, key = { it.first.path }) { (f, depth) ->
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (f.isDirectory) vm.toggleExpanded(f).also { vm.refreshTree() }
                                    else { vm.open(f); closeDrawer() }
                                },
                                onLongClick = { menuFor = f },
                            )
                            .padding(start = (12 + depth * 16).dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
                    ) {
                        val open = f.path in vm.expanded
                        Text(if (f.isDirectory) (if (open) "▾ " else "▸ ") else "   ", color = Dim)
                        Text(f.name, color = if (f.isDirectory) Accent else TextCol, maxLines = 1)
                    }
                    DropdownMenu(expanded = menuFor == f, onDismissRequest = { menuFor = null }) {
                        if (f.isDirectory) {
                            DropdownMenuItem(text = { Text("New file here") }, onClick = { menuFor = null; dlg = Dlg.NewFile(f) })
                            DropdownMenuItem(text = { Text("New folder here") }, onClick = { menuFor = null; dlg = Dlg.NewFolder(f) })
                        }
                        if (f.isDirectory) {
                            DropdownMenuItem(text = { Text("Export to phone folder") }, onClick = { menuFor = null; onExport(f) })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; dlg = Dlg.Rename(f) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; dlg = Dlg.Delete(f) })
                    }
                }
            }
        }
    }

    when (val d = dlg) {
        is Dlg.NewFile -> TextPrompt("New file", "", "File name", { dlg = null }) { vm.createFile(d.parent, it); dlg = null }
        is Dlg.NewFolder -> TextPrompt("New folder", "", "Folder name", { dlg = null }) { vm.createFolder(d.parent, it); dlg = null }
        is Dlg.Rename -> {
            if (vm.hasUnsavedUnder(d.f)) {
                dlg = null
                vm.message = "Save your changes before renaming"
            } else TextPrompt("Rename", d.f.name, "New name", { dlg = null }) { vm.rename(d.f, it); dlg = null }
        }
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("Delete ${d.f.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.f); dlg = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

@Composable
private fun SuggestionBar(vm: EditorViewModel) {
    val tab = vm.active ?: return
    val v = tab.value
    val list = remember(v.text, v.selection, tab.lang) {
        if (v.selection.collapsed) Suggestions.suggest(v.text, v.selection.start, tab.lang) else emptyList()
    }
    if (list.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(Bg).horizontalScroll(rememberScrollState())) {
        list.forEach { s ->
            val c = when (s.kind) {
                SuggestionKind.SNIPPET -> Violet
                SuggestionKind.KEYWORD -> Accent
                SuggestionKind.WORD -> TextCol
            }
            Text(
                s.label,
                color = c,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.clickable { vm.applySuggestion(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Offline, sandboxed preview: JS on, network blocked, no file/content access, no native bridge, no navigation. */
@Composable
private fun PlayDialog(page: PlayPage, onClose: () -> Unit) {
    var reload by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶ " + page.title + "  (offline sandbox)", color = TextCol, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { reload++ }) { Text("Reload") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                key(reload) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                settings.blockNetworkLoads = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                                }
                                loadDataWithBaseURL(null, page.html, "text/html", "utf-8", null)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: EditorViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { vm.resetSettings() }) { Text("Reset") } },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Light theme", Modifier.weight(1f))
                    Switch(checked = vm.lightTheme, onCheckedChange = { vm.updateLight(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Font size: ${vm.fontSize}", Modifier.weight(1f))
                    TextButton(onClick = { vm.updateFontSize(vm.fontSize - 1) }) { Text("−") }
                    TextButton(onClick = { vm.updateFontSize(vm.fontSize + 1) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tab size", Modifier.weight(1f))
                    listOf(2, 4, 8).forEach { n ->
                        TextButton(onClick = { vm.updateTabSize(n) }) {
                            Text(if (vm.tabSize == n) "[$n]" else "$n")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-save when leaving the app", Modifier.weight(1f))
                    Switch(checked = vm.autoSave, onCheckedChange = { vm.updateAutoSave(it) })
                }
                val clip = LocalClipboardManager.current
                Row {
                    TextButton(onClick = { clip.setText(AnnotatedString(vm.exportSettings())); vm.message = "Settings copied to clipboard" }) { Text("Copy settings") }
                    TextButton(onClick = { vm.importSettings(clip.getText()?.text ?: "") }) { Text("Paste settings") }
                }
                Text("Settings are stored privately on this device. Nothing is sent anywhere.", color = Dim, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun SearchDialog(vm: EditorViewModel, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var r by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Search in files") },
        confirmButton = { TextButton(onClick = { vm.searchProject(q) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Find") })
                OutlinedTextField(r, { r = it }, singleLine = true, label = { Text("Replace with") })
                TextButton(onClick = { if (q.isNotEmpty()) confirm = true }) { Text("Replace in all files") }
                Text(vm.searchInfo, color = Dim, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(vm.searchResults) { m ->
                        Text(
                            m.file.relativeTo(vm.workspace.root).path + ":" + m.line + "  " + m.text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().clickable { vm.open(m.file, m.line); onClose() }.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        },
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Replace in all files?") },
            text = { Text("Replace \"$q\" with \"$r\" in every text file of this workspace. Files with unsaved changes are skipped. This writes to disk and cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.replaceProject(q, r); confirm = false; onClose() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QuickOpenDialog(vm: EditorViewModel, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { ProjectSearch.files(vm.workspace.root).take(5000).map { it to it.relativeTo(vm.workspace.root).path } }
    val shown = all.filter { it.second.contains(q, ignoreCase = true) }.take(50)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Quick open") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("File name") })
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(shown) { (f, rel) ->
                        Text(rel, Modifier.fillMaxWidth().clickable { vm.open(f); onClose() }.padding(12.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun TemplateDialog(vm: EditorViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New project from template") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(Templates.all) { t ->
                    Column(Modifier.fillMaxWidth().clickable { vm.createFromTemplate(t); onClose() }.padding(vertical = 10.dp)) {
                        Text(t.name)
                        Text(t.howToRun, color = Dim, fontSize = 12.sp)
                    }
                }
            }
        },
    )
}

@Composable
private fun TerminalDialog(vm: EditorViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.ensureTerminal() }
    val clip = LocalClipboardManager.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    vm.terminals.forEachIndexed { i, t ->
                        TextButton(onClick = { vm.activeTerminal = i }) {
                            Text(t.title + if (t.running) " ●" else "", color = if (i == vm.activeTerminal) Accent else Dim)
                        }
                    }
                    TextButton(onClick = { vm.newTerminal() }) { Text("+") }
                    TextButton(onClick = { vm.closeTerminal(vm.activeTerminal) }) { Text("Close tab") }
                    TextButton(onClick = {
                        vm.terminals.getOrNull(vm.activeTerminal)?.let { clip.setText(AnnotatedString(it.lines.joinToString("\n"))) }
                        vm.message = "Output copied"
                    }) { Text("Copy") }
                    TextButton(onClick = onClose) { Text("Exit") }
                }
                val tab = vm.terminals.getOrNull(vm.activeTerminal)
                if (tab == null) {
                    Text("No terminal open", Modifier.padding(16.dp), color = Dim)
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(tab.lines.size) { if (tab.lines.isNotEmpty()) listState.scrollToItem(tab.lines.lastIndex) }
                    Text("Android shell (not full Linux). Type help.", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Dim, fontSize = 11.sp)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = listState) {
                        items(count = tab.lines.size) { i ->
                            Text(tab.lines[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextCol)
                        }
                    }
                    Text(tab.cwd.path, Modifier.padding(horizontal = 8.dp), color = Dim, fontSize = 11.sp, maxLines = 1)
                    var input by remember(tab) { mutableStateOf("") }
                    val send = { vm.runCommand(tab, input); input = "" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            input, { input = it }, Modifier.weight(1f).padding(start = 8.dp), singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextCol),
                            placeholder = { Text("command") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        TextButton(onClick = {
                            if (tab.history.isNotEmpty()) {
                                tab.historyPos = if (tab.historyPos < 0) tab.history.lastIndex else (tab.historyPos - 1).coerceAtLeast(0)
                                input = tab.history[tab.historyPos]
                            }
                        }) { Text("↑") }
                        if (tab.running) TextButton(onClick = { tab.stop() }) { Text("Stop") }
                        else TextButton(onClick = { send() }) { Text("Run") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeManagerDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list by remember { mutableStateOf<List<RuntimeInfo>?>(null) }
    LaunchedEffect(Unit) { list = RuntimeCheck.collect(ctx) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Runtime Manager") },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        text = {
            val l = list
            if (l == null) Text("Running self-tests on this device…", color = Dim)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(l) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(r.name, color = TextCol)
                        Text(
                            r.status.label + (if (r.version != "-") "  ·  " + r.version else ""),
                            color = if (r.status == RuntimeStatus.VERIFIED) Accent else Violet, fontSize = 12.sp,
                        )
                        Text("Offline: " + (if (r.offline) "yes" else "no") + "  ·  Size: " + r.size, color = Dim, fontSize = 12.sp)
                        Text(r.note, color = Dim, fontSize = 12.sp)
                    }
                }
            }
        },
    )
}

@Composable
private fun ExtensionsDialog(vm: EditorViewModel, onClose: () -> Unit) {
    val installed = vm.installedExtensions()
    val bundled = remember { OfficialExtensions.all.map { ExtensionManifest.parse(it) to it } }
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Extensions") },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("INSTALLED", color = Dim, fontSize = 12.sp) }
                if (installed.isEmpty()) item { Text("None yet", Modifier.padding(vertical = 6.dp), color = Dim) }
                items(installed) { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.ext.name + "  " + i.ext.version)
                            Text(i.ext.publisher + " · permissions: none · data-only", color = Dim, fontSize = 11.sp)
                        }
                        Switch(checked = i.enabled, onCheckedChange = { vm.setExtensionEnabled(i.ext.id, it) })
                        TextButton(onClick = { vm.uninstallExtension(i.ext.id) }) { Text("Remove") }
                    }
                }
                item { Text("AVAILABLE (bundled with the app; no online registry yet)", Modifier.padding(top = 10.dp), color = Dim, fontSize = 12.sp) }
                items(bundled) { (e, raw) ->
                    val cur = installed.firstOrNull { it.ext.id == e.id }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name + "  " + e.version)
                            Text(e.description, color = Dim, fontSize = 11.sp)
                        }
                        TextButton(onClick = { vm.installExtension(raw) }, enabled = cur == null || cur.ext.version != e.version) {
                            Text(if (cur == null) "Install" else if (cur.ext.version == e.version) "Installed" else "Update")
                        }
                    }
                }
                item {
                    Text("INSTALL FROM JSON", Modifier.padding(top = 10.dp), color = Dim, fontSize = 12.sp)
                    OutlinedTextField(json, { json = it }, Modifier.fillMaxWidth().heightIn(max = 140.dp), label = { Text("Paste a manifest") })
                    TextButton(onClick = { vm.installExtension(json) }, enabled = json.isNotBlank()) { Text("Install") }
                    Text("Extensions are data-only (snippets). They cannot run code, read your files or use the network. Format: docs/EXTENSIONS.md", color = Dim, fontSize = 11.sp)
                }
            }
        },
    )
}

@Composable
private fun GitDialog(vm: EditorViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGitState() }
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(vm.gitName) }
    var email by remember { mutableStateOf(vm.gitEmail) }
    var user by remember { mutableStateOf(vm.gitUser) }
    var token by remember { mutableStateOf("") }
    val repo = vm.gitRepo
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Git", color = Accent, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("Identity & token") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                if (vm.gitBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (showSettings) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your name (for commits)") })
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your email (for commits)") })
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("GitHub username") })
                    OutlinedTextField(
                        token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (vm.tokenSaved) "Access token (saved; leave empty to keep)" else "Personal access token") },
                    )
                    Row {
                        TextButton(onClick = { vm.saveGitSettings(name, email, user, token); token = "" }) { Text("Save") }
                        if (vm.tokenSaved) TextButton(onClick = { vm.forgetGitToken() }) { Text("Remove token") }
                    }
                    Text(
                        (if (vm.secretsAvailable) "The token is stored encrypted with the Android Keystore and is only sent to the server you push to or pull from. "
                        else "Secure storage is unavailable on this device, so the token will NOT be saved. ") +
                            "Use a GitHub fine-grained token limited to the repos you need (Contents: read and write).",
                        color = Dim, fontSize = 11.sp,
                    )
                }
                Text("REPOSITORY FOLDER", Modifier.padding(top = 8.dp), color = Dim, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    vm.gitCandidates().forEach { d ->
                        val isRepo = GitOps.isRepo(d)
                        TextButton(onClick = { vm.gitRepo = d }) {
                            Text((if (d == vm.workspace.root) "(workspace)" else d.name) + if (isRepo) " ✓" else "", color = if (d == repo) Accent else TextCol)
                        }
                    }
                }
                if (repo != null && !GitOps.isRepo(repo)) {
                    OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Remote URL (optional, https://…)") })
                    Button(onClick = { vm.gitInit(remote) }, enabled = !vm.gitBusy) { Text("Start a Git repository in ${repo.name}") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("Clone: https://github.com/user/repo") })
                    TextButton(onClick = { vm.gitClone(url) }, enabled = url.isNotBlank() && !vm.gitBusy) { Text("Clone") }
                }
                if (repo != null && GitOps.isRepo(repo)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        TextButton(onClick = { vm.gitStatus() }) { Text("Status") }
                        TextButton(onClick = { vm.gitDiff() }) { Text("Diff") }
                        TextButton(onClick = { vm.gitLog() }) { Text("History") }
                        TextButton(onClick = { vm.gitBranches() }) { Text("Branches") }
                        TextButton(onClick = { vm.gitPull() }) { Text("Pull") }
                        TextButton(onClick = { vm.gitPush() }) { Text("Push") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(msg, { msg = it }, Modifier.weight(1f), singleLine = true, label = { Text("Commit message") })
                        TextButton(onClick = { vm.gitCommit(msg); msg = "" }, enabled = msg.isNotBlank() && !vm.gitBusy) { Text("Commit all") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(branch, { branch = it }, Modifier.weight(1f), singleLine = true, label = { Text("Branch name") })
                        TextButton(onClick = { vm.gitCheckout(branch) }, enabled = branch.isNotBlank() && !vm.gitBusy) { Text("Switch") }
                        TextButton(onClick = { vm.gitNewBranch(branch) }, enabled = branch.isNotBlank() && !vm.gitBusy) { Text("New") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        vm.gitText.ifEmpty { "Pick a folder above, or clone a repository. Unsaved edits are saved before a commit." },
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextCol,
                        modifier = Modifier.fillMaxWidth().background(Panel).padding(8.dp),
                    )
                }
            }
        }
    }
}
