package dev.idart.mangareader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.Filter
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CatalogueTab { POPULAR, LATEST }

data class SourceBrowseUiState(
    val sourceName: String = "",
    val supportsLatest: Boolean = true,
    val tab: CatalogueTab = CatalogueTab.POPULAR,
    val query: String = "",
    val items: List<SManga> = emptyList(),
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = true,
    val isAppending: Boolean = false,
    val error: String? = null,
    /** Expand the typed query into several terms with the model. */
    val useAi: Boolean = true,
    val searchingWithAi: Boolean = false,
    val aiPhase: String? = null,
    val aiVariants: List<AiQueryExpander.Variant> = emptyList(),
    val aiStats: String? = null,
)

class SourceBrowseViewModel(
    private val browse: BrowseSourceCatalogue,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val federated: FederatedSearch,
    private val expander: AiQueryExpander,
    sourceManager: SourceManager,
    private val sourceId: Long,
    initialQuery: String = "",
) : ViewModel() {

    private val _state = MutableStateFlow(
        SourceBrowseUiState(
            sourceName = sourceManager.getOrStub(sourceId).name,
            supportsLatest = sourceManager.getOrStub(sourceId).supportsLatest,
            query = initialQuery,
        ),
    )
    val state: StateFlow<SourceBrowseUiState> = _state.asStateFlow()

    private var page = 0
    private var filters: FilterList = FilterList()

    /** Native filters exposed by the source (status, genres, …). */
    private val _availableFilters = MutableStateFlow<FilterList>(FilterList())
    val availableFilters: StateFlow<FilterList> = _availableFilters.asStateFlow()

    private val _filtersVersion = MutableStateFlow(0)
    val filtersVersion: StateFlow<Int> = _filtersVersion.asStateFlow()

    /** Snapshot of the default states: active = anything different. */
    private var initialSignature: List<String> = emptyList()

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { browse.getSource(sourceId).getFilterList() }
                    .getOrDefault(FilterList())
            }
            filters = list
            initialSignature = signatureOf(list)
            _availableFilters.value = list
        }
        reload()
    }

    /** True when any filter differs from the source's own defaults. */
    fun hasActiveFilters(): Boolean {
        return signatureOf(filters) != initialSignature
    }

    private fun signatureOf(list: FilterList): List<String> {
        return list.map { signatureOfFilter(it) }
    }

    private fun signatureOfFilter(filter: Filter<*>): String {
        return when (filter) {
            is Filter.Group<*> ->
                "G:${filter.name}:" + filter.state
                    .map { (it as? Filter<*>)?.let(::signatureOfFilter) }
                    .joinToString("|")
            else -> "${filter.javaClass.name}:${filter.name}=${filter.state}"
        }
    }

    fun applyFilters() {
        _filtersVersion.update { it + 1 }
        reload()
    }

    fun resetFilters() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { browse.getSource(sourceId).getFilterList() }
                    .getOrDefault(FilterList())
            }
            filters = list
            initialSignature = signatureOf(list)
            _availableFilters.value = list
            _filtersVersion.update { it + 1 }
            reload()
        }
    }

    fun setTab(tab: CatalogueTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, query = "") }
        reload()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search() {
        if (_state.value.useAi && _state.value.query.isNotBlank()) {
            federatedSearch()
        } else {
            reload()
        }
    }

    fun setUseAi(value: Boolean) {
        _state.update { it.copy(useAi = value) }
    }

    /**
     * Fans the typed query out into several terms (languages, synonyms) and
     * searches this one source with all of them, merging into a single list.
     *
     * Pagination is intentionally left out: page 2 of five different queries
     * has no sensible order, so the AI mode stops at the first page of each
     * term and says so in the UI.
     */
    private fun federatedSearch() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        page = 0
        _state.update {
            it.copy(
                items = emptyList(),
                error = null,
                isLoading = true,
                searchingWithAi = true,
                aiPhase = "IA preparando as buscas…",
                aiVariants = emptyList(),
            )
        }
        viewModelScope.launch {
            val variants = expander.expand(query, extraVariants = AI_EXTRA_VARIANTS)
            val sourceName = _state.value.sourceName
            _state.update { it.copy(aiPhase = "procurando em $sourceName…", aiVariants = variants) }
            val result = federated.search(
                variants = variants,
                onlySourceId = sourceId,
                filters = filters,
                deadlineMs = FederatedSearch.DEFAULT_DEADLINE_MS,
            )
            val outcome = result.sections.firstOrNull { it.sourceId == sourceId }
            if (outcome?.searched == false) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        searchingWithAi = false,
                        aiPhase = null,
                        error = outcome?.skipReason ?: "Fonte indisponível",
                    )
                }
                return@launch
            }
            val merged = result.merged.flatMap { work -> work.hits.map { it.manga } }
            _state.update {
                it.copy(
                    items = merged,
                    // No further pages: the list is already a union of results.
                    hasNextPage = false,
                    isLoading = false,
                    searchingWithAi = false,
                    aiPhase = null,
                    error = outcome?.error,
                    aiStats = "${merged.size} resultado(s) com ${variants.size} termo(s) de busca",
                )
            }
        }
    }

    fun reload() {
        page = 0
        _state.update {
            it.copy(
                items = emptyList(),
                error = null,
                isLoading = true,
                searchingWithAi = false,
                aiPhase = null,
                aiVariants = emptyList(),
                aiStats = null,
            )
        }
        loadPage(append = false)
    }

    fun loadMore() {
        val state = _state.value
        if (state.isLoading || state.isAppending || !state.hasNextPage) return
        _state.update { it.copy(isAppending = true, error = null) }
        loadPage(append = true)
    }

    private fun loadPage(append: Boolean) {
        val state = _state.value
        val nextPage = page + 1
        viewModelScope.launch {
            try {
                // Filters set → filtered search even with a blank query
                // (most sources treat it as filtered browse).
                val result = if (state.query.isBlank() && !hasActiveFilters()) {
                    when (state.tab) {
                        CatalogueTab.POPULAR -> browse.popular(sourceId, nextPage)
                        CatalogueTab.LATEST -> browse.latest(sourceId, nextPage)
                    }
                } else {
                    browse.search(sourceId, nextPage, state.query, filters)
                }
                page = nextPage
                _state.update {
                    it.copy(
                        items = if (append) it.items + result.mangas else result.mangas,
                        hasNextPage = result.hasNextPage,
                        isLoading = false,
                        isAppending = false,
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update {
                    it.copy(isLoading = false, isAppending = false, error = friendlyError(state.tab, e))
                }
            }
        }
    }

    /**
     * Inserts the network manga as a local stub (not favorite) and returns its id
     * for the detail screen, which refreshes it from the network on open.
     */
    suspend fun openManga(networkManga: SManga): Long {
        return ensureMangaInDb.await(networkManga, sourceId)
    }

    private fun friendlyError(tab: CatalogueTab, e: Throwable): String {
        if (e is UnsupportedOperationException) {
            return if (tab == CatalogueTab.LATEST) {
                "Esta fonte não oferece a lista de recentes."
            } else {
                "Operação não suportada por esta fonte."
            }
        }
        return friendlySourceError(e, sourceId)
    }

    private companion object {
        /** Extra queries the model adds on top of the literal one. */
        const val AI_EXTRA_VARIANTS = 4
    }
}
