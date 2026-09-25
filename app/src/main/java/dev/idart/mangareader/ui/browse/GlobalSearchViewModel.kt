package dev.idart.mangareader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

sealed interface SourceSearchResult {
    data object Loading : SourceSearchResult
    data class Content(val items: List<SManga>) : SourceSearchResult
    data class Error(val message: String) : SourceSearchResult
}

data class SourceSearchSection(
    val sourceId: Long,
    val sourceName: String,
    val result: SourceSearchResult = SourceSearchResult.Loading,
)

data class GlobalSearchUiState(
    val query: String = "",
    val sections: List<SourceSearchSection> = emptyList(),
    val searched: Boolean = false,
    /** Client-side filters (search stubs often lack metadata; those are hidden). */
    val statusFilter: Set<Int> = emptySet(),
    val genreQuery: String = "",
) {
    fun matches(manga: SManga): Boolean {
        if (statusFilter.isNotEmpty() && manga.status !in statusFilter) return false
        if (genreQuery.isNotBlank()) {
            val hit = manga.genre?.split(",")
                ?.any { it.trim().contains(genreQuery, ignoreCase = true) } == true
            if (!hit) return false
        }
        return true
    }
}

class GlobalSearchViewModel(
    private val browse: BrowseSourceCatalogue,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun toggleStatus(status: Int) {
        _state.update {
            val current = it.statusFilter
            it.copy(statusFilter = if (status in current) current - status else current + status)
        }
    }

    fun setGenreQuery(genre: String) {
        _state.update { it.copy(genreQuery = genre) }
    }

    fun clearFilters() {
        _state.update { it.copy(statusFilter = emptySet(), genreQuery = "") }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Debounce: typing fast replaces the previous search.
            delay(SEARCH_DEBOUNCE_MS)
            val sources = sourceManager.getCatalogueSources()
            _state.update {
                it.copy(
                    searched = true,
                    sections = sources.map { source ->
                        SourceSearchSection(source.id, source.name)
                    },
                )
            }
            supervisorScope {
                val semaphore = Semaphore(MAX_PARALLEL_SOURCES)
                sources.map { source ->
                    async {
                        semaphore.withPermit {
                            searchOne(source.id, query)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun searchOne(sourceId: Long, query: String) {
        try {
            val page = withTimeout(SOURCE_TIMEOUT_MS) {
                browse.search(sourceId, 1, query, FilterList())
            }
            _state.update { state ->
                state.copy(
                    sections = state.sections.map { section ->
                        if (section.sourceId == sourceId) {
                            section.copy(result = SourceSearchResult.Content(page.mangas))
                        } else {
                            section
                        }
                    },
                )
            }
        } catch (e: TimeoutCancellationException) {
            setError(sourceId, "Tempo esgotado")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            setError(sourceId, e.message ?: "Erro de rede")
        }
    }

    private fun setError(sourceId: Long, message: String) {
        _state.update { state ->
            state.copy(
                sections = state.sections.map { section ->
                    if (section.sourceId == sourceId) {
                        section.copy(result = SourceSearchResult.Error(message))
                    } else {
                        section
                    }
                },
            )
        }
    }

    /**
     * Inserts the network manga as a local stub (not favorite) and returns its id
     * for the detail screen, which refreshes it from the network on open.
     */
    suspend fun openManga(networkManga: SManga, sourceId: Long): Long {
        return ensureMangaInDb.await(networkManga, sourceId)
    }

    companion object {
        const val MAX_VISIBLE_PER_SOURCE = 8
        private const val SEARCH_DEBOUNCE_MS = 500L
        private const val SOURCE_TIMEOUT_MS = 25_000L
        private const val MAX_PARALLEL_SOURCES = 6
    }
}
