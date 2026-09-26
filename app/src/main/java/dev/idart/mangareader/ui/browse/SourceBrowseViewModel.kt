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
)

class SourceBrowseViewModel(
    private val browse: BrowseSourceCatalogue,
    private val ensureMangaInDb: EnsureMangaInDb,
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
        reload()
    }

    fun reload() {
        page = 0
        _state.update { it.copy(items = emptyList(), error = null, isLoading = true) }
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
}
