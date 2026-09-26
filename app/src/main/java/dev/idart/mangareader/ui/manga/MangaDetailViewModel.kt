package dev.idart.mangareader.ui.manga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.chapter.interactor.GetChaptersByMangaId
import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.interactor.FetchMangaDetails
import dev.idart.mangareader.domain.manga.interactor.GetMangaById
import dev.idart.mangareader.domain.manga.interactor.GetRelatedMangas
import dev.idart.mangareader.domain.manga.interactor.UpdateMangaDetails
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.source.model.toSManga
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.sources.download.ChapterDownloadState
import dev.idart.mangareader.sources.download.DownloadManager
import dev.idart.mangareader.ui.browse.friendlySourceError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MangaDetailUiState(
    val manga: Manga? = null,
    val chapters: List<Chapter> = emptyList(),
    val sourceName: String? = null,
    val mangaUrl: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

sealed interface SuggestionsUiState {
    data object Loading : SuggestionsUiState
    data class Content(val items: List<SManga>) : SuggestionsUiState
    data object Hidden : SuggestionsUiState
}

class MangaDetailViewModel(
    private val getMangaById: GetMangaById,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateMangaDetails: UpdateMangaDetails,
    private val fetchMangaDetails: FetchMangaDetails,
    private val downloadManager: DownloadManager,
    private val getRelatedMangas: GetRelatedMangas,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val sourceManager: SourceManager,
    private val mangaId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(MangaDetailUiState())
    val state: StateFlow<MangaDetailUiState> = _state.asStateFlow()

    private val _suggestions = MutableStateFlow<SuggestionsUiState>(SuggestionsUiState.Loading)
    val suggestions: StateFlow<SuggestionsUiState> = _suggestions.asStateFlow()

    val downloads: StateFlow<Map<Long, ChapterDownloadState>> = combine(
        downloadManager.queueStates,
        downloadManager.downloadedIds,
        downloadManager.pausedChapterIds,
    ) { queue, downloaded, paused ->
        val merged = downloaded.associateWith { ChapterDownloadState.Downloaded }
            .toMutableMap<Long, ChapterDownloadState>()
        queue.forEach { (id, downloadState) ->
            merged[id] = downloadState
        }
        paused.forEach { id ->
            if (merged[id] !is ChapterDownloadState.Downloaded) {
                merged[id] = ChapterDownloadState.Paused
            }
        }
        merged.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            combine(
                getMangaById.awaitAsFlow(mangaId),
                getChaptersByMangaId.awaitAsFlow(mangaId),
            ) { manga, chapters ->
                val source = sourceManager.getSource(manga.source)
                _state.value.copy(
                    manga = manga,
                    chapters = chapters,
                    sourceName = source?.name,
                    mangaUrl = source?.getMangaUrl(manga.toSManga())?.takeIf { it.isNotBlank() },
                    isLoading = false,
                )
            }
                .catch { e ->
                    _state.value = MangaDetailUiState(isLoading = false, error = friendlySourceError(e, mangaId))
                }
                .collect { _state.value = it }
        }
        viewModelScope.launch {
            try {
                val manga = getMangaById.await(mangaId)
                // Fetch when never initialized OR when the chapter list is empty
                // (stubs inserted as initialized, interrupted syncs, etc.).
                val chapters = getChaptersByMangaId.await(mangaId)
                if (!manga.initialized || chapters.isEmpty()) {
                    _state.update { it.copy(isRefreshing = true) }
                    fetchMangaDetails.await(mangaId)
                }
            } catch (e: Throwable) {
                _state.update { it.copy(error = friendlySourceError(e, mangaId)) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
            loadSuggestions()
        }
    }

    private suspend fun loadSuggestions() {
        try {
            val items = getRelatedMangas.await(mangaId)
            _suggestions.value = if (items.isEmpty()) {
                SuggestionsUiState.Hidden
            } else {
                SuggestionsUiState.Content(items)
            }
        } catch (_: Exception) {
            _suggestions.value = SuggestionsUiState.Hidden
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            try {
                fetchMangaDetails.await(mangaId)
            } catch (e: Throwable) {
                _state.update { it.copy(error = friendlySourceError(e, mangaId)) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
            _suggestions.value = SuggestionsUiState.Loading
            loadSuggestions()
        }
    }

    fun toggleFavorite() {
        val manga = _state.value.manga ?: return
        viewModelScope.launch {
            updateMangaDetails.await(MangaUpdate(id = manga.id, favorite = !manga.favorite))
        }
    }

    fun downloadChapter(chapterId: Long) {
        downloadManager.download(mangaId, listOf(chapterId))
    }

    fun pauseChapter(chapterId: Long) {
        downloadManager.pause(mangaId, chapterId)
    }

    fun resumeChapter(chapterId: Long) {
        downloadManager.resume(mangaId, chapterId)
    }

    fun deleteDownload(chapterId: Long) {
        downloadManager.delete(mangaId, chapterId)
    }

    fun downloadAll() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                _state.value.chapters
                    .filter { !downloadManager.isDownloaded(mangaId, it.id) }
                    .map { it.id }
            }
            if (pending.isNotEmpty()) {
                downloadManager.download(mangaId, pending)
            }
        }
    }

    /**
     * Inserts a suggestion as a local stub and returns its id for navigation.
     */
    suspend fun openSuggestion(suggestion: SManga): Long? {
        val sourceId = _state.value.manga?.source ?: return null
        return ensureMangaInDb.await(suggestion, sourceId)
    }
}
