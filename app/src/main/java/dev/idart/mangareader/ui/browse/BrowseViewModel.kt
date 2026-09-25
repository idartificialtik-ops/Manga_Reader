package dev.idart.mangareader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.CatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SourceGroup(
    val lang: String,
    val displayName: String,
    val sources: List<CatalogueSource>,
)

data class BrowseUiState(
    val sources: List<CatalogueSource> = emptyList(),
) {
    val groups: List<SourceGroup>
        get() {
            val order = listOf("pt-BR", "en", "es", "all", "other")
            return sources.groupBy { it.lang.ifBlank { "other" } }
                .map { (lang, list) ->
                    SourceGroup(lang, langDisplayName(lang), list.sortedBy { it.name })
                }
                .sortedWith(
                    compareBy(
                        { order.indexOf(it.lang).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE },
                        { it.displayName },
                    ),
                )
        }
}

fun langDisplayName(lang: String): String {
    return when (lang.lowercase()) {
        "pt-br" -> "Português (BR)"
        "pt" -> "Português"
        "en" -> "English"
        "es" -> "Español"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "it" -> "Italiano"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "zh", "zh-hans", "zh-hant" -> "中文"
        "ru" -> "Русский"
        "ar" -> "العربية"
        "id" -> "Bahasa Indonesia"
        "tr" -> "Türkçe"
        "uk" -> "Українська"
        "all", "other" -> "Outros"
        else -> lang
    }
}

class BrowseViewModel(
    sourceManager: SourceManager,
    private val filterRepository: BrowseFilterRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState(sourceManager.getCatalogueSources()))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    val hiddenLangs: StateFlow<Set<String>> = filterRepository.hiddenLangs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Expanded source groups. Empty by default (all collapsed). Kept here
     * (not in `remember`) so scrolling a group off-screen doesn't reset it.
     */
    private val _expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val expandedGroups: StateFlow<Set<String>> = _expandedGroups.asStateFlow()

    fun toggleGroup(lang: String) {
        val current = _expandedGroups.value
        _expandedGroups.value = if (lang in current) current - lang else current + lang
    }

    init {
        viewModelScope.launch {
            sourceManager.catalogueSources.collect { sources ->
                _state.value = BrowseUiState(sources)
            }
        }
    }

    fun setLangVisible(lang: String, visible: Boolean) {
        viewModelScope.launch {
            filterRepository.setLangVisible(lang, visible)
        }
    }

    fun selectAllLangs() {
        viewModelScope.launch {
            filterRepository.setAllVisible()
        }
    }

    fun deselectAllLangs(langs: Collection<String>) {
        viewModelScope.launch {
            filterRepository.hideAll(langs)
        }
    }
}
