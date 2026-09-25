package dev.idart.mangareader.sources.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.service.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private val Context.downloadPrefs: DataStore<Preferences> by preferencesDataStore(name = "download_prefs")

/**
 * State of a chapter download, combining WorkManager queue info with
 * on-disk completion markers.
 */
sealed interface ChapterDownloadState {
    data object NotDownloaded : ChapterDownloadState
    data class Queued(val progress: Int = 0, val total: Int = 0) : ChapterDownloadState
    data class Downloading(val progress: Int, val total: Int) : ChapterDownloadState
    data object Paused : ChapterDownloadState
    data object Downloaded : ChapterDownloadState
    data class Failed(val retryable: Boolean = true) : ChapterDownloadState
}

/** A fully downloaded chapter on disk. */
data class DownloadedChapter(
    val chapterId: Long,
    val name: String,
    val sizeBytes: Long,
)

/** Downloaded chapters grouped by manga for the management screen. */
data class DownloadedGroup(
    val mangaId: Long,
    val mangaTitle: String,
    val coverUrl: String?,
    val chapters: List<DownloadedChapter>,
) {
    val totalBytes: Long get() = chapters.sumOf { it.sizeBytes }
}

class DownloadManager(
    private val context: Context,
    private val workManager: WorkManager,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueStates = MutableStateFlow<Map<Long, ChapterDownloadState>>(emptyMap())
    val queueStates: StateFlow<Map<Long, ChapterDownloadState>> = _queueStates.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<Long>>(emptySet())
    val downloadedIds: StateFlow<Set<Long>> = _downloadedIds.asStateFlow()

    private val _pausedChapterIds = MutableStateFlow<Set<Long>>(emptySet())
    val pausedChapterIds: StateFlow<Set<Long>> = _pausedChapterIds.asStateFlow()

    private val _pausedKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Raw pause keys (`"$mangaId:$chapterId"`) for the management screen,
     * which needs the manga id to resume/delete.
     */
    val pausedKeys: StateFlow<Set<String>> = _pausedKeys.asStateFlow()

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _downloadedGroups = MutableStateFlow<List<DownloadedGroup>>(emptyList())
    val downloadedGroups: StateFlow<List<DownloadedGroup>> = _downloadedGroups.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    init {
        scope.launch {
            _wifiOnly.value = context.downloadPrefs.data.map { it[WIFI_ONLY_KEY] ?: false }.first()
            refreshPaused()
        }
        scope.launch {
            workManager.getWorkInfosByTagFlow(DOWNLOADS_TAG).collect { workInfos ->
                _queueStates.update { current ->
                    val updated = current.toMutableMap()
                    // Drop entries whose work finished; disk markers are the source of truth.
                    updated.keys.removeAll { id -> workInfos.none { it.tags.contains(workTag(id)) } }
                    workInfos.forEach { info ->
                        val chapterId = info.tags.mapNotNull { it.removePrefix(WORK_TAG_PREFIX).toLongOrNull() }
                            .firstOrNull() ?: return@forEach
                        if (isDownloadedDir(chapterId)) {
                            updated[chapterId] = ChapterDownloadState.Downloaded
                        } else {
                            updated[chapterId] = when (info.state) {
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ChapterDownloadState.Queued(
                                    progress = info.progress.getInt(PROGRESS_KEY, 0),
                                    total = info.progress.getInt(TOTAL_KEY, 0),
                                )
                                WorkInfo.State.RUNNING -> ChapterDownloadState.Downloading(
                                    progress = info.progress.getInt(PROGRESS_KEY, 0),
                                    total = info.progress.getInt(TOTAL_KEY, 0),
                                )
                                WorkInfo.State.SUCCEEDED -> ChapterDownloadState.Downloaded
                                WorkInfo.State.FAILED -> ChapterDownloadState.Failed()
                                WorkInfo.State.CANCELLED -> ChapterDownloadState.NotDownloaded
                            }
                        }
                    }
                    updated
                }
                refreshDownloadedIds()
            }
        }
        scope.launch { refreshDownloadedIds() }
    }

    fun download(mangaId: Long, chapterIds: List<Long>) {
        val networkType = if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED
        chapterIds.forEach { chapterId ->
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag(DOWNLOADS_TAG)
                .addTag(workTag(chapterId))
                .addTag(mangaTag(mangaId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build(),
                )
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_MANGA_ID to mangaId,
                        DownloadWorker.KEY_CHAPTER_ID to chapterId,
                    ),
                )
                .build()
            workManager.enqueueUniqueWork(
                uniqueWorkName(mangaId, chapterId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        scope.launch { refreshDownloadedIds() }
    }

    fun cancel(mangaId: Long, chapterId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(mangaId, chapterId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(DOWNLOADS_TAG)
    }

    /**
     * Pauses a chapter: stops its work but keeps partial files so [resume]
     * can continue where it stopped.
     */
    fun pause(mangaId: Long, chapterId: Long) {
        cancel(mangaId, chapterId)
        scope.launch {
            context.downloadPrefs.edit { prefs ->
                prefs[PAUSED_KEY] = (prefs[PAUSED_KEY] ?: emptySet()) + pauseKey(mangaId, chapterId)
            }
            refreshPaused()
        }
    }

    /** Resumes a paused chapter, keeping already downloaded pages. */
    fun resume(mangaId: Long, chapterId: Long) {
        scope.launch {
            context.downloadPrefs.edit { prefs ->
                prefs[PAUSED_KEY] = (prefs[PAUSED_KEY] ?: emptySet()) - pauseKey(mangaId, chapterId)
            }
            refreshPaused()
            download(mangaId, listOf(chapterId))
        }
    }

    /** Resumes every paused chapter. */
    fun resumeAll() {
        scope.launch {
            val keys = context.downloadPrefs.data.map { it[PAUSED_KEY] ?: emptySet() }.first()
            context.downloadPrefs.edit { prefs -> prefs[PAUSED_KEY] = emptySet() }
            refreshPaused()
            keys.mapNotNull { parsePauseKey(it) }.forEach { (mangaId, chapterId) ->
                download(mangaId, listOf(chapterId))
            }
        }
    }

    fun delete(mangaId: Long, chapterId: Long) {
        cancel(mangaId, chapterId)
        chapterDir(mangaId, chapterId).deleteRecursively()
        scope.launch {
            context.downloadPrefs.edit { prefs ->
                prefs[PAUSED_KEY] = (prefs[PAUSED_KEY] ?: emptySet()) - pauseKey(mangaId, chapterId)
            }
            refreshPaused()
            refreshDownloadedIds()
        }
    }

    /** Deletes every downloaded file and stops all queued work. */
    fun deleteAll() {
        cancelAll()
        scope.launch {
            context.downloadPrefs.edit { prefs -> prefs[PAUSED_KEY] = emptySet() }
            downloadsDir().deleteRecursively()
            refreshPaused()
            refreshDownloadedIds()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        scope.launch {
            context.downloadPrefs.edit { prefs -> prefs[WIFI_ONLY_KEY] = enabled }
            _wifiOnly.value = enabled
        }
    }

    fun isDownloaded(mangaId: Long, chapterId: Long): Boolean {
        return doneMarker(mangaId, chapterId).exists()
    }

    fun chapterDir(mangaId: Long, chapterId: Long): File {
        return File(downloadsDir(), "$mangaId/$chapterId")
    }

    fun pageFile(mangaId: Long, chapterId: Long, index: Int): File {
        return File(chapterDir(mangaId, chapterId), "$index.img")
    }

    /**
     * Local pages of a downloaded chapter, ordered by index, or null when the
     * chapter is not fully downloaded.
     */
    fun localPageFiles(mangaId: Long, chapterId: Long): List<File>? {
        val dir = chapterDir(mangaId, chapterId)
        if (!doneMarker(mangaId, chapterId).exists() || !dir.isDirectory) return null
        return dir.listFiles { file -> file.name.endsWith(".img") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun doneMarker(mangaId: Long, chapterId: Long): File {
        return File(chapterDir(mangaId, chapterId), DONE_MARKER)
    }

    private fun downloadsDir(): File {
        return File(context.filesDir, DOWNLOADS_DIR).apply { mkdirs() }
    }

    private fun isDownloadedDir(chapterId: Long): Boolean {
        return downloadsDir().walkTopDown()
            .any { it.isFile && it.name == DONE_MARKER && it.parentFile?.name == chapterId.toString() }
    }

    private suspend fun refreshPaused() {
        val keys = context.downloadPrefs.data.map { it[PAUSED_KEY] ?: emptySet() }.first()
        _pausedKeys.value = keys
        _pausedChapterIds.value = keys.mapNotNull { parsePauseKey(it)?.second }.toSet()
    }

    private suspend fun refreshDownloadedIds() {
        val dir = downloadsDir()
        if (!dir.isDirectory) {
            _downloadedIds.value = emptySet()
            _downloadedGroups.value = emptyList()
            _totalBytes.value = 0L
            return
        }
        val doneDirs = dir.walkTopDown().toList()
            .filter { it.isFile && it.name == DONE_MARKER }
            .mapNotNull { it.parentFile }
        _downloadedIds.value = doneDirs.mapNotNull { it.name.toLongOrNull() }.toSet()
        var total = 0L
        val groups = doneDirs.mapNotNull { chapterDir ->
            val chapterId = chapterDir.name.toLongOrNull() ?: return@mapNotNull null
            val mangaId = chapterDir.parentFile?.name?.toLongOrNull() ?: return@mapNotNull null
            val size = chapterDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".img") }
                .sumOf { it.length() }
            total += size
            val chapterName = runCatching { chapterRepository.getChapterById(chapterId)?.name }
                .getOrNull() ?: "Capítulo $chapterId"
            mangaId to DownloadedChapter(chapterId, chapterName, size)
        }.groupBy({ it.first }, { it.second })
            .mapNotNull { (mangaId, chapters) ->
                val manga = runCatching { mangaRepository.getMangaById(mangaId) }.getOrNull()
                DownloadedGroup(
                    mangaId,
                    manga?.title ?: "Mangá $mangaId",
                    manga?.thumbnailUrl,
                    chapters.sortedBy { it.chapterId },
                )
            }
            .sortedBy { it.mangaTitle }
        _downloadedGroups.value = groups
        _totalBytes.value = total
        // A chapter that completed no longer needs its pause mark.
        val downloadedKeys = _pausedKeys.value.filter { key ->
            parsePauseKey(key)?.second in _downloadedIds.value
        }
        if (downloadedKeys.isNotEmpty()) {
            context.downloadPrefs.edit { prefs ->
                prefs[PAUSED_KEY] = (prefs[PAUSED_KEY] ?: emptySet()) - downloadedKeys.toSet()
            }
            refreshPaused()
        }
    }

    fun pruneFinished() {
        workManager.pruneWork()
    }

    companion object {
        const val DOWNLOADS_TAG = "manga_downloads"
        const val WORK_TAG_PREFIX = "chapter_"
        const val MANGA_TAG_PREFIX = "manga_"
        const val PROGRESS_KEY = "progress"
        const val TOTAL_KEY = "total"

        private const val DOWNLOADS_DIR = "downloads"
        private const val DONE_MARKER = "done"

        private val PAUSED_KEY = stringSetPreferencesKey("paused_chapters")
        private val WIFI_ONLY_KEY = booleanPreferencesKey("wifi_only")

        fun workTag(chapterId: Long) = "$WORK_TAG_PREFIX$chapterId"
        fun mangaTag(mangaId: Long) = "$MANGA_TAG_PREFIX$mangaId"
        fun uniqueWorkName(mangaId: Long, chapterId: Long) = "download-$mangaId-$chapterId"

        private fun pauseKey(mangaId: Long, chapterId: Long) = "$mangaId:$chapterId"

        private fun parsePauseKey(key: String): Pair<Long, Long>? {
            val parts = key.split(":")
            if (parts.size != 2) return null
            val mangaId = parts[0].toLongOrNull() ?: return null
            val chapterId = parts[1].toLongOrNull() ?: return null
            return mangaId to chapterId
        }

        /** Human-readable byte count for the management screen header. */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB")
            var value = bytes.toDouble() / 1024
            var unit = units[0]
            for (u in units) {
                unit = u
                if (value < 1024 || u == units.last()) break
                value /= 1024
            }
            return "${if (value >= 100) value.toInt() else String.format("%.1f", value)} $unit"
        }
    }
}
