package app.xccoditer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows documentation text or a tappable list of lines (problems, references). */
@Composable
internal fun IntelDialog(p: IntelPanel, onGoto: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(p.title, fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                p.text?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                p.rows.forEach { r ->
                    Text(
                        r.label, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onGoto(r.line); onClose() }.padding(vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
