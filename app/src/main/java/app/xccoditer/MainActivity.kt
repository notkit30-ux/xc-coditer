package app.xccoditer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0E1117)
private val Panel = Color(0xFF161B22)
private val Accent = Color(0xFF38BDF8)
private val Violet = Color(0xFFA78BFA)
private val TextCol = Color(0xFFE6EDF3)
private val Dim = Color(0xFF7D8590)

private val XcColors = darkColorScheme(
    primary = Accent, secondary = Violet, background = Bg, surface = Panel,
    onPrimary = Bg, onBackground = TextCol, onSurface = TextCol,
)

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = XcColors) { XcApp(vm) } }
    }

    override fun onStop() {
        super.onStop()
        vm.saveAll() // do not lose work when the app is backgrounded
    }
}

private class Highlight(val lang: Lang) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = Highlighter.tokenize(text.text, lang)
        val styled = buildAnnotatedString {
            append(text.text)
            for (s in spans) {
                val c = when (s.kind) {
                    TokenKind.COMMENT -> Color(0xFF6E7681)
                    TokenKind.STRING -> Color(0xFFA5D6FF)
                    TokenKind.NUMBER -> Color(0xFFF0883E)
                    TokenKind.KEYWORD -> Color(0xFFFF7B72)
                    TokenKind.TAG -> Color(0xFF7EE787)
                }
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

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Panel) {
                Explorer(vm) { scope.launch { drawer.close() } }
            }
        },
    ) {
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
                        IconButton(onClick = { findOpen = !findOpen }, enabled = vm.active != null) {
                            Icon(Icons.Default.Search, "Find", tint = TextCol)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = TextCol) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Command palette") }, onClick = { menu = false; palette = true })
                                DropdownMenuItem(text = { Text("Save all") }, onClick = { menu = false; vm.saveAll() })
                                DropdownMenuItem(text = { Text("Go to line") }, onClick = { menu = false; goTo = true })
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
                if (vm.active != null) AccessoryBar(vm)
            }
        }
    }

    if (palette) {
        val cmds = listOf<Pair<String, () -> Unit>>(
            "Save" to { vm.save() },
            "Save all" to { vm.saveAll() },
            "Undo" to { vm.undo() },
            "Redo" to { vm.redo() },
            "Find" to { findOpen = true },
            "Go to line" to { goTo = true },
            "Open explorer" to { scope.launch { drawer.open() } },
            "Close tab" to { vm.closeTab(vm.activeIndex) },
        )
        CommandPalette(cmds) { palette = false }
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
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = TextCol)
    val vScroll = rememberScrollState()
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
                    visualTransformation = Highlight(tab.lang),
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
private fun Explorer(vm: EditorViewModel, closeDrawer: () -> Unit) {
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    val rows = vm.visibleTree()

    Column(Modifier.fillMaxHeight().widthIn(max = 320.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EXPLORER", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { dlg = Dlg.NewFile(vm.workspace.root) }) { Text("+ File") }
            TextButton(onClick = { dlg = Dlg.NewFolder(vm.workspace.root) }) { Text("+ Folder") }
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
