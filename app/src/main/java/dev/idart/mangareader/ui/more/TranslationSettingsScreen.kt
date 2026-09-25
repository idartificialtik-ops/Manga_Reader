package dev.idart.mangareader.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.idart.mangareader.translate.OcrEngine
import dev.idart.mangareader.translate.TranslationMode
import dev.idart.mangareader.translate.TranslatorSettings
import dev.idart.mangareader.ui.ai.AiSettings
import dev.idart.mangareader.ui.injektViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationSettingsViewModel(
    private val translatorSettings: TranslatorSettings,
    private val aiSettings: AiSettings,
) : ViewModel() {
    val ocrEngine: Flow<OcrEngine> = translatorSettings.ocrEngine
    val translationMode: Flow<TranslationMode> = translatorSettings.translationMode
    val aiBaseUrl: Flow<String> = aiSettings.baseUrl
    val aiModel: Flow<String> = aiSettings.model
    val aiHasKey: Flow<Boolean> = aiSettings.apiKey.map { it.isNotBlank() }

    fun setOcrEngine(value: OcrEngine) = translatorSettings.setOcrEngine(value)
    fun setTranslationMode(value: TranslationMode) = translatorSettings.setTranslationMode(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsScreen(
    onBack: () -> Unit,
    onAiClick: () -> Unit,
) {
    val viewModel: TranslationSettingsViewModel = injektViewModel {
        TranslationSettingsViewModel(Injekt.get(), Injekt.get())
    }
    val ocrEngine by viewModel.ocrEngine.collectAsState(initial = OcrEngine.AUTO)
    val translationMode by viewModel.translationMode.collectAsState(initial = TranslationMode.AUTO)
    val aiBaseUrl by viewModel.aiBaseUrl.collectAsState(initial = "")
    val aiModel by viewModel.aiModel.collectAsState(initial = "")
    val aiHasKey by viewModel.aiHasKey.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tradução") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Leitura do texto (OCR)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            OcrEngine.entries.forEach { engine ->
                item {
                    ListItem(
                        headlineContent = { Text(engine.label) },
                        leadingContent = {
                            RadioButton(
                                selected = ocrEngine == engine,
                                onClick = { viewModel.setOcrEngine(engine) },
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setOcrEngine(engine) },
                    )
                }
            }
            item {
                Text(
                    "Tradução",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            TranslationMode.entries.forEach { mode ->
                item {
                    ListItem(
                        headlineContent = { Text(mode.label) },
                        leadingContent = {
                            RadioButton(
                                selected = translationMode == mode,
                                onClick = { viewModel.setTranslationMode(mode) },
                            )
                        },
                        modifier = Modifier.clickable { viewModel.setTranslationMode(mode) },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Provedor de IA") },
                    supportingContent = {
                        Text(
                            if (aiBaseUrl.isBlank()) {
                                "Nenhum configurado"
                            } else {
                                "$aiBaseUrl · $aiModel · chave ${if (aiHasKey) "ok" else "ausente"}"
                            },
                        )
                    },
                    modifier = Modifier.clickable { onAiClick() },
                )
            }
        }
    }
}
