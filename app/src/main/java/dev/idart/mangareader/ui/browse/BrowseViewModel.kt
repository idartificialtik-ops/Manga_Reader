package dev.idart.mangareader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.sources.extension.ExtensionSource
import dev.idart.mangareader.sources.extension.repo.ExtensionRepoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Whether a source belongs to an extension the repo index flags as 18+.
 * Sources carry no such flag at runtime, so the comparison is by extension
 * package. Built-in ports are never 18+.
 */
fun CatalogueSource.isNsfw(nsfwPackages: Set<String>): Boolean {
    val pkg = (this as? ExtensionSource)?.extensionPackage ?: return false
    return pkg in nsfwPackages
}

data class SourceGroup(
    val lang: String,
    val displayName: String,
    val sources: List<CatalogueSource>,
)

data class BrowseUiState(
    val sources: List<CatalogueSource> = emptyList(),
    /** Source ids whose extension is flagged 18+ (shown with a badge). */
    val nsfwSourceIds: Set<Long> = emptySet(),
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
    private val extensionRepoStore: ExtensionRepoStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState(sourceManager.getCatalogueSources()))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    val hiddenLangs: StateFlow<Set<String>> = filterRepository.hiddenLangs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 18+ toggle state (persisted). */
    val hideNsfw: StateFlow<Boolean> = filterRepository.hideNsfw
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * How many sources the 18+ toggle is hiding right now — the UI shows it so
     * the filter is never a silent hole in the list.
     */
    val hiddenNsfwCount: StateFlow<Int> = combine(
        sourceManager.catalogueSources,
        extensionRepoStore.nsfwPackages,
        hideNsfw,
    ) { sources, nsfwPackages, hide ->
        if (hide) sources.count { it.isNsfw(nsfwPackages) } else 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    fun setHideNsfw(hide: Boolean) {
        viewModelScope.launch {
            filterRepository.setHideNsfw(hide)
        }
    }

    init {
        viewModelScope.launch {
            combine(
                sourceManager.catalogueSources,
                extensionRepoStore.nsfwPackages,
                hideNsfw,
            ) { sources, nsfwPackages, hide ->
                val nsfwIds = sources.filter { it.isNsfw(nsfwPackages) }.map { it.id }.toSet()
                BrowseUiState(
                    sources = if (hide) sources.filterNot { it.id in nsfwIds } else sources,
                    nsfwSourceIds = nsfwIds,
                )
            }.collect { _state.value = it }
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
