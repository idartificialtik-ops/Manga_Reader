package dev.idart.mangareader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun MangaReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedIndex: Int = 0,
    amoled: Boolean = false,
    coverSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Tema por capa (estilo Komikku): a seed da capa regenera o
        // ColorScheme inteiro, em vez de só manchar o fundo. Tinta forte
        // para o tom da capa aparecer em toda a tela.
        coverSeed != null -> seedColorScheme(coverSeed, darkTheme, tint = 0.26f)
        seedIndex <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedIndex > 0 -> {
            val seed = THEME_SEEDS.getOrNull(seedIndex - 1)?.color ?: Color(0xFF3B82F6)
            // Seeds de preset: tinta discreta, só para não ficar neutro puro.
            seedColorScheme(seed, darkTheme, tint = 0.10f)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Preto puro: só faz sentido no escuro, derruba todas as superfícies.
    val finalScheme = if (darkTheme && amoled) colorScheme.toAmoled() else colorScheme

    MaterialTheme(
        colorScheme = finalScheme,
        content = content,
    )
}

private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF101010),
    surfaceContainerHighest = Color(0xFF161616),
    inverseSurface = Color(0xFFE6E6E6),
    outline = Color(0xFF3A3A3A),
)

/**
 * Builds tonal light/dark schemes from a seed color without extra
 * dependencies: accent roles AND the neutral surfaces take the seed's hue
 * (that's what makes the whole screen read as "colored by the cover",
 * like Komikku's cover-based theme). The amount of seed mixed into the
 * neutrals is [tint] — the base neutrals are Material's defaults.
 */
private fun seedColorScheme(seed: Color, darkTheme: Boolean, tint: Float) = if (darkTheme) {
    val primary = blendArgb(seed, Color.White, 0.25f)
    val primaryContainer = blendArgb(seed, Color.Black, 0.45f)
    // Neutros do Material 3 escurecidos + matiz da seed.
    val bg = mix(Color(0xFF141218), seed, tint)
    val sLowest = mix(Color(0xFF0F0E13), seed, tint * 0.8f)
    val sLow = mix(Color(0xFF1D1B20), seed, tint * 0.9f)
    val sContainer = mix(Color(0xFF211F26), seed, tint)
    val sHigh = mix(Color(0xFF2B2930), seed, tint * 1.1f)
    val sHighest = mix(Color(0xFF36343B), seed, tint * 1.2f)
    val sVariant = mix(Color(0xFF49454F), seed, tint * 1.3f)
    // Textos também recebem o matiz (creme/oliva na img de referência), mas
    // com mistura leve para não perder contraste.
    val onSurface = mix(Color(0xFFE6E0E9), seed, tint * 0.6f)
    val onSurfaceVariant = mix(Color(0xFFCAC4D0), seed, tint * 0.7f)
    darkColorScheme(
        primary = primary,
        onPrimary = contentColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = blendArgb(seed, Color.White, 0.75f),
        secondary = blendArgb(seed, Color.White, 0.45f),
        secondaryContainer = blendArgb(seed, Color.Black, 0.55f),
        tertiary = blendArgb(seed, Color.White, 0.6f),
        background = bg,
        onBackground = onSurface,
        surface = bg,
        onSurface = onSurface,
        surfaceVariant = sVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = sLowest,
        surfaceContainerLow = sLow,
        surfaceContainer = sContainer,
        surfaceContainerHigh = sHigh,
        surfaceContainerHighest = sHighest,
        outline = mix(Color(0xFF938F99), seed, tint),
    )
} else {
    val primaryContainer = blendArgb(seed, Color.White, 0.85f)
    val bg = mix(Color(0xFFFFFBFF), seed, tint * 0.35f)
    lightColorScheme(
        primary = seed,
        onPrimary = contentColorFor(seed),
        primaryContainer = primaryContainer,
        onPrimaryContainer = blendArgb(seed, Color.Black, 0.55f),
        secondary = blendArgb(seed, Color.Black, 0.25f),
        secondaryContainer = blendArgb(seed, Color.White, 0.75f),
        tertiary = blendArgb(seed, Color.Black, 0.35f),
        background = bg,
        onBackground = Color(0xFF1C1B1F),
        surface = bg,
        onSurface = Color(0xFF1C1B1F),
        surfaceVariant = mix(Color(0xFFE7E0EC), seed, tint * 0.5f),
        onSurfaceVariant = mix(Color(0xFF49454F), seed, tint * 0.5f),
        surfaceContainerLowest = mix(Color(0xFFFFFFFF), seed, tint * 0.3f),
        surfaceContainerLow = mix(Color(0xFFF7F2FA), seed, tint * 0.4f),
        surfaceContainer = mix(Color(0xFFF3EDF7), seed, tint * 0.5f),
        surfaceContainerHigh = mix(Color(0xFFECE6F0), seed, tint * 0.6f),
        surfaceContainerHighest = mix(Color(0xFFE6E0E9), seed, tint * 0.7f),
    )
}

/** Mistura [seed] sobre [base] na proporção [ratio]. */
private fun mix(base: Color, seed: Color, ratio: Float): Color {
    return Color(
        red = base.red + (seed.red - base.red) * ratio,
        green = base.green + (seed.green - base.green) * ratio,
        blue = base.blue + (seed.blue - base.blue) * ratio,
        alpha = 1f,
    )
}

private fun blendArgb(from: Color, to: Color, ratio: Float): Color {
    return Color(
        red = from.red + (to.red - from.red) * ratio,
        green = from.green + (to.green - from.green) * ratio,
        blue = from.blue + (to.blue - from.blue) * ratio,
        alpha = 1f,
    )
}
