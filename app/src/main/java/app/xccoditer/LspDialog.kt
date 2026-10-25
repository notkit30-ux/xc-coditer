package app.xccoditer

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

private fun hint(l: Lang): String = when (l) {
    Lang.C, Lang.CPP -> "Termux: pkg install clang socat, then: socat TCP-LISTEN:2087,reuseaddr,fork EXEC:clangd"
    Lang.GO -> "Termux: go install golang.org/x/tools/gopls@latest, then: gopls serve -listen=127.0.0.1:2088"
    Lang.RUST -> "Termux: pkg install rust-analyzer socat, then: socat TCP-LISTEN:2089,reuseaddr,fork EXEC:rust-analyzer"
    Lang.TYPESCRIPT, Lang.JAVASCRIPT -> "Termux: npm i -g typescript typescript-language-server; pkg install socat; then: socat TCP-LISTEN:2090,reuseaddr,fork EXEC:'typescript-language-server --stdio'"
    else -> "Run a language server that speaks LSP and expose it on a TCP port (stdio servers can be wrapped with socat)."
}

private fun defaultAddr(l: Lang) = when (l) {
    Lang.C, Lang.CPP -> "127.0.0.1:2087"; Lang.GO -> "127.0.0.1:2088"; Lang.RUST -> "127.0.0.1:2089"
    Lang.TYPESCRIPT, Lang.JAVASCRIPT -> "127.0.0.1:2090"; else -> "127.0.0.1:2091"
}

@Composable
internal fun LspDialog(vm: EditorViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Language servers", color = TextCol, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text(
                    "Python intelligence is built in and needs nothing here. For other languages, run a language server (for example in Termux) " +
                        "and connect to it over localhost. The server sees your open file's text, but not your project's files on disk, " +
                        "so single-file features work best. The Termux commands below are suggestions and have not been verified on a device.",
                    color = Dim, fontSize = 12.sp,
                )
                vm.lspLanguages.forEach { l ->
                    var addr by remember(l) { mutableStateOf(vm.lspSavedAddress(l).ifEmpty { defaultAddr(l) }) }
                    Text(l.label, color = Accent, modifier = Modifier.padding(top = 12.dp))
                    Text(hint(l), color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(value = addr, onValueChange = { addr = it }, singleLine = true, label = { Text("host:port") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { vm.lspConnect(l, addr) }) { Text("Connect") }
                        TextButton(onClick = { vm.lspDisconnect(l) }) { Text("Disconnect") }
                    }
                    vm.lspStatus[l]?.let { Text(it, color = TextCol, fontSize = 12.sp) }
                }
            }
        }
    }
}
