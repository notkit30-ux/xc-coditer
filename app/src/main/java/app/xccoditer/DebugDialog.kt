package app.xccoditer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Python debugger panel: breakpoints, step controls, call stack, variables, evaluate, output. */
@Composable
internal fun DebugDialog(vm: EditorViewModel, d: DebugUi, onClose: () -> Unit) {
    var bpText by remember { mutableStateOf(d.breakpoints.sorted().joinToString(", ")) }
    var expr by remember { mutableStateOf("") }
    val mono = FontFamily.Monospace
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Debug: ${d.tab.file.name}", color = TextCol, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(if (d.running) "Hide" else "Close") }
                }
                Text(
                    "Python only. Runs this file with this app's permissions. Breakpoints are line numbers. Library code is stepped over.",
                    color = Dim, fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = bpText,
                    onValueChange = { bpText = it; DebugParse.lines(it)?.let { s -> vm.debugSetBreakpoints(s) } },
                    label = { Text("Breakpoint lines (e.g. 3, 10)") },
                    isError = DebugParse.lines(bpText) == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!d.running) {
                        Button(onClick = { vm.debugStart(false) }) { Text("Run to breakpoint") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.debugStart(true) }) { Text("Stop on first line") }
                    } else {
                        Text(if (d.paused != null) "Paused" else "Running…", color = Accent)
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { vm.debugCommand("stop") }) { Text("Stop") }
                    }
                }
                val p = d.paused
                if (p != null) {
                    Row(Modifier.fillMaxWidth()) {
                        if (!p.final) {
                            TextButton(onClick = { vm.debugCommand("continue") }) { Text("Continue") }
                            TextButton(onClick = { vm.debugCommand("next") }) { Text("Step over") }
                            TextButton(onClick = { vm.debugCommand("step") }) { Text("Step into") }
                            TextButton(onClick = { vm.debugCommand("out") }) { Text("Step out") }
                        } else {
                            TextButton(onClick = { vm.debugCommand("continue") }) { Text("Finish") }
                        }
                    }
                    Text(p.reason + " (line ${p.line})", color = Violet, fontSize = 13.sp)
                    val src = d.tab.value.text.lines()
                    val sel = d.frame.coerceIn(0, (p.stack.size - 1).coerceAtLeast(0))
                    val at = p.stack.getOrNull(sel)?.line ?: p.line
                    Column(Modifier.fillMaxWidth().background(Panel).padding(6.dp)) {
                        for (n in (at - 3).coerceAtLeast(1)..(at + 3).coerceAtMost(src.size)) {
                            Text(
                                (if (n == at) "▶ " else "  ") + n.toString().padStart(4) + "  " + src[n - 1],
                                fontFamily = mono, fontSize = 12.sp, color = if (n == at) Accent else TextCol,
                            )
                        }
                    }
                    Text("Call stack (tap to inspect)", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    p.stack.forEachIndexed { i, f ->
                        Text(
                            (if (i == sel) "● " else "○ ") + f.function + "  line " + f.line,
                            fontFamily = mono, fontSize = 12.sp, color = if (i == sel) Accent else TextCol,
                            modifier = Modifier.fillMaxWidth().clickable { vm.debugSelectFrame(i) }.padding(vertical = 3.dp),
                        )
                    }
                    Text("Variables", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    if (d.variables.isEmpty()) Text("(none)", color = Dim, fontSize = 12.sp)
                    d.variables.forEach { v -> Text("${v.name}: ${v.type} = ${v.value}", fontFamily = mono, fontSize = 12.sp, color = TextCol) }
                    OutlinedTextField(
                        value = expr, onValueChange = { expr = it }, singleLine = true,
                        label = { Text("Evaluate expression") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton(onClick = { vm.debugEval(expr) }) { Text("Evaluate") }
                    if (d.evalResult.isNotEmpty()) Text(d.evalResult, fontFamily = mono, fontSize = 12.sp, color = TextCol)
                }
                Text("Output", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                if (d.output.isEmpty()) Text("(nothing yet)", color = Dim, fontSize = 12.sp)
                d.output.forEach { Text(it, fontFamily = mono, fontSize = 12.sp, color = TextCol) }
            }
        }
    }
}
