package dev.idart.mangareader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.sources.download.DownloadManager
import dev.idart.mangareader.sources.download.DownloadedGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DownloadRow(
    val mangaId: Long,
    val chapterId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val state: WorkInfo.State,
    val paused: Boolean = false,
    val progress: Int,
    val total: Int,
)

class DownloadsViewModel(
    private val workManager: WorkManager,
    private val downloadManager: DownloadManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) : ViewModel() {

    private val activeRows: StateFlow<List<DownloadRow>> = workManager
        .getWorkInfosByTagFlow(DownloadManager.DOWNLOADS_TAG)
        .map { workInfos ->
            workInfos.mapNotNull { info ->
                if (info.state.isFinished) return@mapNotNull null
                val chapterId = info.tags
                    .mapNotNull { it.removePrefix(DownloadManager.WORK_TAG_PREFIX).toLongOrNull() }
                    .firstOrNull() ?: return@mapNotNull null
                val mangaId = info.tags
                    .mapNotNull { it.removePrefix(DownloadManager.MANGA_TAG_PREFIX).toLongOrNull() }
                    .firstOrNull() ?: return@mapNotNull null
                DownloadRow(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    mangaTitle = mangaTitleOf(mangaId),
                    chapterName = chapterNameOf(chapterId),
                    state = info.state,
                    progress = info.progress.getInt(DownloadManager.PROGRESS_KEY, 0),
                    total = info.progress.getInt(DownloadManager.TOTAL_KEY, 0),
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Active queue plus synthetic rows for paused chapters (whose work was
     * cancelled and therefore no longer appears in WorkManager).
     */
    val rows: StateFlow<List<DownloadRow>> = combine(
        activeRows,
        downloadManager.pausedKeys,
    ) { active, pausedKeys ->
        val activeIds = active.map { it.chapterId }.toSet()
        val pausedRows = pausedKeys.mapNotNull { key ->
            val (mangaId, chapterId) = key.split(":").let {
                if (it.size != 2) return@mapNotNull null
                val m = it[0].toLongOrNull() ?: return@mapNotNull null
                val c = it[1].toLongOrNull() ?: return@mapNotNull null
                m to c
            }
            if (chapterId in activeIds) return@mapNotNull null
            DownloadRow(
                mangaId = mangaId,
                chapterId = chapterId,
                mangaTitle = mangaTitleOf(mangaId),
                chapterName = chapterNameOf(chapterId),
                state = WorkInfo.State.CANCELLED,
                paused = true,
                progress = 0,
                total = 0,
            )
        }
        active + pausedRows
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<DownloadedGroup>> = downloadManager.downloadedGroups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalBytes: StateFlow<Long> = downloadManager.totalBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val wifiOnly: StateFlow<Boolean> = downloadManager.wifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _pruned = MutableStateFlow(0)
    val pruned: StateFlow<Int> = _pruned.asStateFlow()

    private suspend fun mangaTitleOf(mangaId: Long): String {
        return runCatching { mangaRepository.getMangaById(mangaId).title }
            .getOrDefault("Mangá $mangaId")
    }

    private suspend fun chapterNameOf(chapterId: Long): String {
        return runCatching { chapterRepository.getChapterById(chapterId)?.name }
            .getOrNull() ?: "Capítulo $chapterId"
    }

    fun pause(mangaId: Long, chapterId: Long) {
        downloadManager.pause(mangaId, chapterId)
    }

    fun resume(mangaId: Long, chapterId: Long) {
        downloadManager.resume(mangaId, chapterId)
    }

    fun resumeAll() {
        downloadManager.resumeAll()
    }

    fun cancel(mangaId: Long, chapterId: Long) {
        downloadManager.cancel(mangaId, chapterId)
    }

    fun cancelAll() {
        downloadManager.cancelAll()
    }

    fun delete(mangaId: Long, chapterId: Long) {
        downloadManager.delete(mangaId, chapterId)
    }

    fun deleteGroup(mangaId: Long) {
        val group = groups.value.firstOrNull { it.mangaId == mangaId } ?: return
        group.chapters.forEach { downloadManager.delete(mangaId, it.chapterId) }
    }

    fun deleteAll() {
        downloadManager.deleteAll()
    }

    fun retry(mangaId: Long, chapterId: Long) {
        downloadManager.download(mangaId, listOf(chapterId))
    }

    fun setWifiOnly(enabled: Boolean) {
        downloadManager.setWifiOnly(enabled)
    }

    fun pruneFinished() {
        downloadManager.pruneFinished()
    }
}
