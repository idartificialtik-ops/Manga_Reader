package dev.idart.mangareader.ui.reader

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.idart.mangareader.domain.chapter.interactor.GetChaptersByMangaId
import dev.idart.mangareader.domain.chapter.interactor.GetPageList
import dev.idart.mangareader.domain.chapter.interactor.UpdateChapterProgress
import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.history.interactor.UpsertHistory
import dev.idart.mangareader.domain.manga.interactor.GetMangaById
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.source.model.Page
import dev.idart.mangareader.sources.download.DownloadManager
import dev.idart.mangareader.translate.PageTranslation
import dev.idart.mangareader.translate.PageTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

/**
 * One loaded chapter section in the continuous feed.
 */
data class LoadedChapter(
    val chapterId: Long,
    val name: String?,
    val pages: List<Page>,
)

sealed interface ReaderFeedItem {
    data class ChapterHeader(val chapterId: Long, val name: String?) : ReaderFeedItem
    data class Transition(
        val fromChapterId: Long,
        val fromName: String?,
        val toChapterId: Long,
        val toName: String?,
    ) : ReaderFeedItem
    data class PageItem(val chapterId: Long, val page: Page) : ReaderFeedItem
    data object EndMarker : ReaderFeedItem
    data object LoadingMore : ReaderFeedItem
}

data class ReaderUiState(
    val manga: Manga? = null,
    val initialChapterId: Long = -1L,
    val currentChapterId: Long = -1L,
    val currentChapterName: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val feed: List<ReaderFeedItem> = emptyList(),
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val reachedEnd: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class ReaderViewModel(
    private val getMangaById: GetMangaById,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapterProgress: UpdateChapterProgress,
    private val upsertHistory: UpsertHistory,
    private val getPageList: GetPageList,
    private val downloadManager: DownloadManager,
    private val translator: PageTranslator,
    private val mangaId: Long,
    private val chapterId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState(initialChapterId = chapterId))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _translationEnabled = MutableStateFlow(false)
    val translationEnabled: StateFlow<Boolean> = _translationEnabled.asStateFlow()

    private val _translations = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    val translations: StateFlow<Map<String, PageTranslation>> = _translations.asStateFlow()

    private val translateQueue = ArrayDeque<TranslateTask>()
    private var translateWorker: Job? = null

    private data class TranslateTask(val chapterId: Long, val index: Int, val imageUrl: String?)

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionStart = System.currentTimeMillis()
    private var lastSavedPosition = -1

    /** Reading order (oldest-first); sources sync newest-first. */
    private var orderedChapters: List<Chapter> = emptyList()
    private val loadedSections = mutableListOf<LoadedChapter>()

    init {
        viewModelScope.launch {
            combine(
                getMangaById.awaitAsFlow(mangaId),
                getChaptersByMangaId.awaitAsFlow(mangaId),
            ) { manga, chapters ->
                orderedChapters = chapters.asReversed()
                if (loadedSections.isNotEmpty()) {
                    // Late chapter list: backfill names and recompute the end.
                    for (i in loadedSections.indices) {
                        val section = loadedSections[i]
                        val full = orderedChapters.firstOrNull { it.id == section.chapterId }
                        if (full != null && full.name != section.name) {
                            loadedSections[i] = section.copy(name = full.name)
                        }
                    }
                    rebuildFeed()
                }
                _state.value.copy(manga = manga, isLoading = _state.value.feed.isEmpty())
            }
                .catch { e -> _state.value = ReaderUiState(initialChapterId = chapterId, isLoading = false, error = e.message) }
                .collect { _state.value = it }
        }
        viewModelScope.launch {
            try {
                appendChapter(chapterId)
            } catch (e: Throwable) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Erro ao carregar páginas")
                }
            }
        }
    }

    /**
     * Loads a chapter's pages (offline-first) and appends it to the feed.
     * Returns false when there is nothing after it.
     */
    private suspend fun appendChapter(id: Long): Boolean {
        val chapter = orderedChapters.firstOrNull { it.id == id }
        val localFiles = downloadManager.localPageFiles(mangaId, id)
        val pages = if (localFiles != null) {
            localFiles.mapIndexed { index, file ->
                Page(index, file.toUri().toString(), file.toUri().toString())
            }
        } else {
            getPageList.await(mangaId, id)
        }
        loadedSections += LoadedChapter(id, chapter?.name, pages)
        viewModelScope.launch {
            updateChapterProgress.awaitMarkRead(id, read = true)
        }
        rebuildFeed()
        if (_translationEnabled.value) {
            enqueueTranslation(id, pages)
        }
        return true
    }

    fun pageKey(chapterId: Long, index: Int): String = "$chapterId-$index"

    fun setTranslationEnabled(enabled: Boolean) {
        _translationEnabled.value = enabled
        if (enabled) {
            loadedSections.forEach { section ->
                enqueueTranslation(section.chapterId, section.pages)
            }
            startTranslateWorker()
        } else {
            translateWorker?.cancel()
            translateWorker = null
            _translations.update { current ->
                current.mapValues { (_, state) ->
                    if (state is PageTranslation.Working) PageTranslation.Idle else state
                }
            }
        }
    }

    private fun enqueueTranslation(chapterId: Long, pages: List<Page>) {
        pages.forEach { page ->
            val key = pageKey(chapterId, page.index)
            if (!_translations.value.containsKey(key)) {
                translateQueue += TranslateTask(chapterId, page.index, page.imageUrl)
            }
        }
        if (_translationEnabled.value) {
            startTranslateWorker()
        }
    }

    private fun startTranslateWorker() {
        if (translateWorker?.isActive == true) return
        translateWorker = viewModelScope.launch {
            while (true) {
                val task = translateQueue.removeFirstOrNull() ?: break
                val key = pageKey(task.chapterId, task.index)
                val existing = _translations.value[key]
                if (existing is PageTranslation.Done) continue
                _translations.update { it + (key to PageTranslation.Working) }
                val result = translator.translate(mangaId, task.chapterId, task.index, task.imageUrl)
                _translations.update { it + (key to result) }
            }
        }
    }

    private fun rebuildFeed() {
        val feed = mutableListOf<ReaderFeedItem>()
        loadedSections.forEachIndexed { index, section ->
            if (index == 0) {
                feed += ReaderFeedItem.ChapterHeader(section.chapterId, section.name)
            } else {
                feed += ReaderFeedItem.Transition(
                    fromChapterId = loadedSections[index - 1].chapterId,
                    fromName = loadedSections[index - 1].name,
                    toChapterId = section.chapterId,
                    toName = section.name,
                )
            }
            section.pages.forEach { page ->
                feed += ReaderFeedItem.PageItem(section.chapterId, page)
            }
        }
        val totalPages = loadedSections.sumOf { it.pages.size }
        val lastLoadedId = loadedSections.lastOrNull()?.chapterId
        val hasNext = lastLoadedId != null &&
            orderedChapters.indexOfFirst { it.id == lastLoadedId }.let { index ->
                index >= 0 && index + 1 < orderedChapters.size
            }
        _state.update {
            it.copy(
                feed = feed,
                totalPages = totalPages,
                reachedEnd = !hasNext,
                isLoading = false,
            )
        }
    }

    /** Loads the next chapter below the current ones (continuous reading). */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.reachedEnd || current.isLoading) return
        val lastLoadedId = loadedSections.lastOrNull()?.chapterId ?: return
        val index = orderedChapters.indexOfFirst { it.id == lastLoadedId }
        val next = if (index >= 0) orderedChapters.getOrNull(index + 1) else null
        if (next == null) {
            _state.update { it.copy(reachedEnd = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, loadMoreError = null) }
            try {
                appendChapter(next.id)
            } catch (e: Throwable) {
                _state.update { it.copy(loadMoreError = e.message ?: "Erro ao carregar próximo capítulo") }
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    fun retryLoadMore() {
        _state.update { it.copy(loadMoreError = null) }
        loadMore()
    }

    fun onFeedPositionChanged(feedIndex: Int) {
        val feed = _state.value.feed
        if (feed.isEmpty()) return
        val safeIndex = feedIndex.coerceIn(0, feed.size - 1)
        if (safeIndex == lastSavedPosition) return
        lastSavedPosition = safeIndex
        // Current chapter = nearest header/transition at or before this position.
        var currentId = _state.value.currentChapterId
        var currentName = _state.value.currentChapterName
        for (i in safeIndex downTo 0) {
            when (val item = feed[i]) {
                is ReaderFeedItem.ChapterHeader -> {
                    currentId = item.chapterId
                    currentName = item.name
                    break
                }
                is ReaderFeedItem.Transition -> {
                    currentId = item.toChapterId
                    currentName = item.toName
                    break
                }
                else -> {}
            }
        }
        // Global page number = PageItems up to this position.
        var globalPage = 0
        var chapterPage = -1
        for (i in 0..safeIndex) {
            val item = feed[i]
            if (item is ReaderFeedItem.PageItem) {
                globalPage++
                if (item.chapterId == currentId) chapterPage++
            }
        }
        _state.update {
            it.copy(
                currentChapterId = currentId,
                currentChapterName = currentName,
                currentPage = globalPage,
            )
        }
        if (currentId != -1L && chapterPage >= 0) {
            viewModelScope.launch {
                updateChapterProgress.awaitSetLastPageRead(currentId, chapterPage.toLong())
            }
        }
    }

    override fun onCleared() {
        val elapsedSeconds = ((System.currentTimeMillis() - sessionStart) / 1000).coerceAtLeast(0)
        val lastChapterId = loadedSections.lastOrNull()?.chapterId ?: chapterId
        sessionScope.launch {
            runCatching {
                upsertHistory.await(lastChapterId, Date(), elapsedSeconds)
            }
            sessionScope.cancel()
        }
        super.onCleared()
    }
}
