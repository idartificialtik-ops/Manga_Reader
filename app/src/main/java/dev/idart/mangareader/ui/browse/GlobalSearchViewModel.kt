package dev.idart.mangareader.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One source that has this work, as an option in the picker.
 *
 * [libraryMangaId] is set when the same (url, source) row already exists in the
 * database — the same book under a different source is a *different* row, so
 * without this the tap would silently create a duplicate of a title the user
 * already has favourited elsewhere.
 */
data class SourceChoice(
    val hit: FederatedSearch.Hit,
    val sourceName: String,
    val libraryMangaId: Long? = null,
    val recommended: Boolean = false,
    /** Came from the on-demand "search elsewhere" pass, not from the results. */
    val discovered: Boolean = false,
)

/** The work being picked, with every source that has it. */
data class PendingWork(
    val title: String,
    val choices: List<SourceChoice>,
    val searchingElsewhere: Boolean = false,
    val searchedElsewhere: Boolean = false,
    val elsewhereMessage: String? = null,
)

data class GlobalSearchUiState(
    val query: String = "",
    val searched: Boolean = false,
    val searching: Boolean = false,
    /** What the assistant is doing right now, for the progress line. */
    val phase: String? = null,
    val variants: List<AiQueryExpander.Variant> = emptyList(),
    val merged: List<FederatedSearch.MergedWork> = emptyList(),
    val sections: List<FederatedSearch.SourceOutcome> = emptyList(),
    /** One line per work (recommended) instead of one block per source. */
    val grouped: Boolean = true,
    /** Hide sources that errored or found nothing. */
    val onlySourcesWithHits: Boolean = true,
    val useAi: Boolean = true,
    val elapsedMs: Long = 0L,
    val stats: String? = null,
    val statusFilter: Set<Int> = emptySet(),
    val genreQuery: String = "",
    /** Source id -> display name, so the picker needs no lookup. */
    val sourceNames: Map<Long, String> = emptyMap(),
    val pendingWork: PendingWork? = null,
) {
    /**
     * Client-side filters. Search results often carry no metadata, so a source
     * with unfiltered stubs can be emptied by these — same rule as before.
     */
    fun matches(manga: SManga): Boolean {
        if (statusFilter.isNotEmpty() && manga.status !in statusFilter) return false
        if (genreQuery.isNotBlank()) {
            val hit = manga.genre?.split(",")
                ?.any { it.trim().contains(genreQuery, ignoreCase = true) } == true
            if (!hit) return false
        }
        return true
    }

    /** Sections worth rendering, honouring [onlySourcesWithHits]. */
    fun visibleSections(): List<FederatedSearch.SourceOutcome> =
        if (onlySourcesWithHits) sections.filter { it.foundSomething } else sections

    fun hiddenSectionCount(): Int = sections.size - visibleSections().size
}

class GlobalSearchViewModel(
    private val federated: FederatedSearch,
    private val expander: AiQueryExpander,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val health: SourceHealthStore,
    private val mangaRepository: MangaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setUseAi(value: Boolean) {
        _state.update { it.copy(useAi = value) }
    }

    fun setGrouped(value: Boolean) {
        _state.update { it.copy(grouped = value) }
    }

    fun setOnlySourcesWithHits(value: Boolean) {
        _state.update { it.copy(onlySourcesWithHits = value) }
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

    /** Never search this source again until the user unblocks it. */
    fun toggleBlocked(sourceId: Long) {
        val block = !health.isBlocked(sourceId)
        health.setBlocked(sourceId, block)
        _state.update { current ->
            current.copy(
                sections = current.sections.map { section ->
                    if (section.sourceId == sourceId) {
                        section.copy(
                            hits = if (block) emptyList() else section.hits,
                            skipReason = health.skipReason(sourceId),
                        )
                    } else {
                        section
                    }
                },
            )
        }
    }

    /**
     * Opens the picker for [work]. With a single source there is nothing to
     * choose, so the caller skips this and opens the manga directly.
     */
    fun chooseSource(work: FederatedSearch.MergedWork) {
        viewModelScope.launch {
            val choices = choicesOf(work, recommendedSourceId = work.hits.first().sourceId)
            _state.update { it.copy(pendingWork = PendingWork(work.title, choices)) }
        }
    }

    fun dismissPendingWork() {
        _state.update { it.copy(pendingWork = null) }
    }

    /**
     * Opens the work in the chosen source, reusing the database row when the
     * same (url, source) entry already exists.
     */
    suspend fun openChoice(choice: SourceChoice): Long {
        _state.update { it.copy(pendingWork = null) }
        choice.libraryMangaId?.let { return it }
        return ensureMangaInDb.await(choice.hit.manga, choice.hit.sourceId)
    }

    /**
     * On-demand pass over every other source for this exact title, so a source
     * that did not match the original query can still be picked. Results are
     * merged into the open picker instead of replacing the search results.
     */
    fun searchOtherSources() {
        val pending = _state.value.pendingWork ?: return
        if (pending.searchingElsewhere || pending.searchedElsewhere) return
        _state.update { it.copy(pendingWork = pending.copy(searchingElsewhere = true)) }
        viewModelScope.launch {
            val variants = expander.expand(pending.title, extraVariants = AI_EXTRA_VARIANTS)
            val known = pending.choices.map { it.hit.sourceId }.toSet()
            val result = federated.search(variants = variants, filters = FilterList())
            val fresh = result.sections
                .filter { it.foundSomething && it.sourceId !in known }
                .flatMap { it.hits }
            _state.update { state ->
                val current = state.pendingWork ?: return@update state
                val added = fresh.map { hit ->
                    SourceChoice(
                        hit = hit,
                        sourceName = state.sourceNames[hit.sourceId] ?: "Fonte ${hit.sourceId}",
                        discovered = true,
                    )
                }
                // Library check for the new ones, so an existing row is reused.
                val checked = added.map { choice ->
                    choice.copy(
                        libraryMangaId = runCatching {
                            mangaRepository
                                .getMangaByUrlAndSourceId(choice.hit.manga.url, choice.hit.sourceId)?.id
                        }.getOrNull(),
                    )
                }
                state.copy(
                    sourceNames = state.sourceNames + result.sections
                        .associate { it.sourceId to it.sourceName },
                    pendingWork = current.copy(
                        choices = current.choices + checked.sortedByDescending { it.hit.manga.thumbnail_url != null },
                        searchingElsewhere = false,
                        searchedElsewhere = true,
                        elsewhereMessage = if (checked.isEmpty()) {
                            "Nenhuma outra fonte tem este título."
                        } else {
                            "${checked.size} fonte(s) a mais encontrada(s)."
                        },
                    ),
                )
            }
        }
    }

    private suspend fun choicesOf(
        work: FederatedSearch.MergedWork,
        recommendedSourceId: Long,
        discovered: Boolean = false,
    ): List<SourceChoice> {
        val names = _state.value.sourceNames
        return work.hits.map { hit ->
            SourceChoice(
                hit = hit,
                sourceName = names[hit.sourceId] ?: "Fonte ${hit.sourceId}",
                libraryMangaId = runCatching {
                    mangaRepository
                        .getMangaByUrlAndSourceId(hit.manga.url, hit.sourceId)?.id
                }.getOrNull(),
                recommended = hit.sourceId == recommendedSourceId,
                discovered = discovered,
            )
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Debounce: typing fast replaces the previous search.
            delay(SEARCH_DEBOUNCE_MS)
            _state.update {
                it.copy(
                    searched = true,
                    searching = true,
                    phase = if (it.useAi) "IA preparando as buscas…" else null,
                    merged = emptyList(),
                    sections = emptyList(),
                    stats = null,
                )
            }

            val variants = if (_state.value.useAi) {
                expander.expand(query, extraVariants = AI_EXTRA_VARIANTS)
            } else {
                listOf(AiQueryExpander.Variant("original", query))
            }
            _state.update {
                it.copy(
                    phase = "procurando em todas as fontes…",
                    variants = variants,
                )
            }

            val result = federated.search(
                variants = variants,
                filters = FilterList(),
                deadlineMs = FederatedSearch.DEFAULT_DEADLINE_MS,
            )

            _state.update {
                it.copy(
                    searching = false,
                    phase = null,
                    merged = result.merged,
                    sections = result.sections,
                    sourceNames = result.sections.associate { section ->
                        section.sourceId to section.sourceName
                    },
                    elapsedMs = result.elapsedMs,
                    stats = statsOf(result, variants),
                )
            }
        }
    }

    private fun statsOf(
        result: FederatedSearch.Result,
        variants: List<AiQueryExpander.Variant>,
    ): String = buildString {
        append("${result.merged.size} obra(s)")
        append(" · ${variants.size} termo(s) de busca")
        append(" · ${result.sourcesWithHits}/${result.sourcesSearched} fontes acharam")
        if (result.sourcesSkipped > 0) append(" · ${result.sourcesSkipped} pulada(s)")
        if (result.sourcesFailed > 0) append(" · ${result.sourcesFailed} com erro")
        if (result.budgetExhausted) append(" · limite de requisições atingido")
        append(" · ${result.elapsedMs / 1000}s")
    }

    /**
     * Inserts the network manga as a local stub (not favorite) and returns its
     * id for the detail screen, which refreshes it from the network on open.
     */
    suspend fun openManga(networkManga: SManga, sourceId: Long): Long {
        return ensureMangaInDb.await(networkManga, sourceId)
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 500L

        /** Extra queries the model adds on top of the literal one. */
        private const val AI_EXTRA_VARIANTS = 4
    }
}
