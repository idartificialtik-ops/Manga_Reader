package dev.idart.mangareader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.idart.mangareader.ui.navigation.MangaReaderNavGraph
import dev.idart.mangareader.ui.theme.MangaReaderTheme
import dev.idart.mangareader.ui.theme.ThemeMode
import dev.idart.mangareader.ui.theme.ThemeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeRepository = Injekt.get<ThemeRepository>()
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val seedIndex by themeRepository.seedIndex.collectAsState(initial = 0)
            val amoled by themeRepository.amoled.collectAsState(initial = false)
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MangaReaderTheme(
                darkTheme = darkTheme,
                seedIndex = seedIndex,
                amoled = amoled,
            ) {
                val view = LocalView.current
                SideEffect {
                    // Light status/nav icons in dark theme and vice versa.
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
                }
                MangaReaderNavGraph()
            }
        }
    }
}
