package dev.idart.mangareader.ui.sourceconfig

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceScreen
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.sources.extension.ExtensionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceConfigUiState(
    val sourceName: String = "",
    val screen: PreferenceScreen? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads the settings screen an extension declares through
 * `ConfigurableSource.setupPreferenceScreen`.
 *
 * Building it runs extension code: a source compiled against a different
 * preferences-lib can throw, so every failure is caught and reported instead
 * of taking the screen down.
 */
class SourceConfigViewModel(
    private val application: Application,
    private val sourceManager: SourceManager,
    private val sourceId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceConfigUiState())
    val state: StateFlow<SourceConfigUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val source = sourceManager.getSource(sourceId)
            val name = source?.name.orEmpty()
            val extension = source as? ExtensionSource
            if (extension == null) {
                _state.value = SourceConfigUiState(
                    sourceName = name,
                    isLoading = false,
                    error = if (source == null) {
                        "Fonte não encontrada."
                    } else {
                        "Esta fonte não tem configurações."
                    },
                )
                return@launch
            }
            if (!extension.isConfigurable) {
                _state.value = SourceConfigUiState(
                    sourceName = name,
                    isLoading = false,
                    error = "Esta extensão não expõe configurações.",
                )
                return@launch
            }
            val screen = withContext(Dispatchers.Default) {
                runCatching { extension.buildPreferenceScreen(application) }
                    .onFailure { error ->
                        android.util.Log.w("SourceConfig", "falha ao montar tela: $error")
                    }
                    .getOrNull()
            }
            _state.value = SourceConfigUiState(
                sourceName = name,
                screen = screen,
                isLoading = false,
                error = when {
                    screen != null -> null
                    // Vazio é um caso válido: extensão configurável sem campos.
                    else -> "Não foi possível carregar as configurações desta extensão."
                },
            )
        }
    }
}
