package app.xccoditer

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Global palette switch. Colors below read this state, so composables recompose when it flips. */
object Pal {
    var light by mutableStateOf(false)
}

internal val Bg: Color get() = if (Pal.light) Color(0xFFFFFFFF) else Color(0xFF0E1117)
internal val Panel: Color get() = if (Pal.light) Color(0xFFF3F4F6) else Color(0xFF161B22)
internal val Accent: Color get() = if (Pal.light) Color(0xFF0969DA) else Color(0xFF38BDF8)
internal val Violet: Color get() = if (Pal.light) Color(0xFF8250DF) else Color(0xFFA78BFA)
internal val TextCol: Color get() = if (Pal.light) Color(0xFF1F2328) else Color(0xFFE6EDF3)
internal val Dim: Color get() = if (Pal.light) Color(0xFF656D76) else Color(0xFF7D8590)

fun xcColorScheme(): ColorScheme =
    if (Pal.light) lightColorScheme(
        primary = Accent, secondary = Violet, background = Bg, surface = Panel,
        onPrimary = Color.White, onBackground = TextCol, onSurface = TextCol,
    ) else darkColorScheme(
        primary = Accent, secondary = Violet, background = Bg, surface = Panel,
        onPrimary = Bg, onBackground = TextCol, onSurface = TextCol,
    )

fun tokenColor(kind: TokenKind, light: Boolean): Color = if (light) when (kind) {
    TokenKind.COMMENT -> Color(0xFF6E7781)
    TokenKind.STRING -> Color(0xFF0A3069)
    TokenKind.NUMBER -> Color(0xFFB35900)
    TokenKind.KEYWORD -> Color(0xFFCF222E)
    TokenKind.TAG -> Color(0xFF116329)
} else when (kind) {
    TokenKind.COMMENT -> Color(0xFF6E7681)
    TokenKind.STRING -> Color(0xFFA5D6FF)
    TokenKind.NUMBER -> Color(0xFFF0883E)
    TokenKind.KEYWORD -> Color(0xFFFF7B72)
    TokenKind.TAG -> Color(0xFF7EE787)
}
