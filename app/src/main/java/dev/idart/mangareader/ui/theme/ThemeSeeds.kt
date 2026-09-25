package dev.idart.mangareader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Preset seed colors for the app theme. Index 0 is reserved for the
 * system dynamic color (Android 12+); 1..N map to [SEEDS].
 */
data class ThemeSeed(
    val name: String,
    val color: Color,
)

val THEME_SEEDS = listOf(
    ThemeSeed("Azul", Color(0xFF3B82F6)),
    ThemeSeed("Verde", Color(0xFF22C55E)),
    ThemeSeed("Roxo", Color(0xFFA855F7)),
    ThemeSeed("Vermelho", Color(0xFFEF4444)),
    ThemeSeed("Laranja", Color(0xFFF97316)),
    ThemeSeed("Rosa", Color(0xFFEC4899)),
    ThemeSeed("Ciano", Color(0xFF06B6D4)),
)

fun seedName(index: Int): String {
    return if (index <= 0) "Sistema" else THEME_SEEDS.getOrNull(index - 1)?.name ?: "Sistema"
}

/** Black or white, whichever contrasts more with [background]. */
fun contentColorFor(background: Color): Color {
    return if (background.luminance() > 0.5f) Color.Black else Color.White
}
