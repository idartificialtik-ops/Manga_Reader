package dev.idart.mangareader.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.idart.mangareader.ui.injektViewModel
import dev.idart.mangareader.ui.theme.ThemeMode
import dev.idart.mangareader.ui.theme.ThemeRepository
import dev.idart.mangareader.ui.theme.seedName
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onExtensionsClick: () -> Unit,
    onThemeClick: () -> Unit,
    onAiClick: () -> Unit,
    onTranslationClick: () -> Unit,
) {
    val viewModel: MoreViewModel = injektViewModel { MoreViewModel(Injekt.get(), Injekt.get(), Injekt.get()) }
    val state by viewModel.state.collectAsState()
    val themeRepository: ThemeRepository = Injekt.get()
    val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val seedIndex by themeRepository.seedIndex.collectAsState(initial = 0)
    val modeLabel = when (themeMode) {
        ThemeMode.LIGHT -> "Claro"
        ThemeMode.DARK -> "Escuro"
        ThemeMode.SYSTEM -> "Sistema"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mais") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Favoritos") },
                supportingContent = { Text("${state.favoriteCount}") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Tempo de leitura") },
                supportingContent = { Text("${state.totalReadMinutes} min") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Extensões") },
                supportingContent = { Text("APKs de fontes instalados no aparelho") },
                modifier = Modifier.clickable { onExtensionsClick() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Aparência") },
                supportingContent = { Text("$modeLabel · ${seedName(seedIndex)}") },
                modifier = Modifier.clickable { onThemeClick() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Assistente IA") },
                supportingContent = { Text("Busque e filtre mangás com sua chave") },
                modifier = Modifier.clickable { onAiClick() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Tradução") },
                supportingContent = { Text("OCR e tradutor das páginas") },
                modifier = Modifier.clickable { onTranslationClick() },
            )
        }
    }
}
