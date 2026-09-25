package dev.idart.mangareader.sources.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.idart.mangareader.core.common.network.GET
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.model.toSChapter
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.online.HttpSource
import dev.idart.mangareader.sources.extension.ExtensionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * Downloads every page image of a single chapter into the app-private
 * downloads directory and writes a `done` marker on success.
 *
 * Dependencies are resolved from Injekt (the default [WorkerFactory] only
 * supports the `(Context, WorkerParameters)` constructor).
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1L)
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1L)
        if (mangaId == -1L || chapterId == -1L) return@withContext Result.failure()

        try {
            val mangaRepository = Injekt.get<MangaRepository>()
            val chapterRepository = Injekt.get<ChapterRepository>()
            val sourceManager = Injekt.get<SourceManager>()
            val downloadManager = Injekt.get<DownloadManager>()

            val manga = mangaRepository.getMangaById(mangaId)
            val chapter = chapterRepository.getChapterById(chapterId)
                ?: return@withContext Result.failure()
            val source = sourceManager.getSource(manga.source)
                ?: return@withContext Result.failure()

            val pages = source.getPageList(chapter.toSChapter())
            if (pages.isEmpty()) return@withContext Result.failure()

            // External extension sources are bridged (ExtensionSource), not
            // HttpSource: resolve the client and per-image request accordingly.
            // Images must go through `imageRequest` (per-image headers like
            // Referer), never a raw GET, or hotlink-protected CDNs return 403.
            val extSource = source as? ExtensionSource
            val httpSource = source as? HttpSource
            val client = extSource?.client ?: httpSource?.client
                ?: return@withContext Result.failure()

            val dir = downloadManager.chapterDir(mangaId, chapterId).apply { mkdirs() }
            // Resume support: pages already on disk are skipped, except the
            // highest existing index, which may be truncated and is re-fetched.
            val existingIndices = (0 until Int.MAX_VALUE).takeWhile { index ->
                downloadManager.pageFile(mangaId, chapterId, index).let { it.exists() && it.length() > 0 }
            }.toSet()
            val refetchIndex = existingIndices.maxOrNull()
            pages.forEachIndexed { index, page ->
                if (index in existingIndices && index != refetchIndex) {
                    setProgress(workDataOf(DownloadManager.PROGRESS_KEY to index + 1, DownloadManager.TOTAL_KEY to pages.size))
                    return@forEachIndexed
                }
                val request = when {
                    extSource != null -> extSource.imageRequest(page)
                    httpSource != null -> httpSource.imageRequest(page)
                    else -> {
                        val imageUrl = page.imageUrl
                            ?: throw IOException("Page $index has no image url")
                        GET(imageUrl, okhttp3.Headers.Builder().build())
                    }
                }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
                    val body = response.body ?: throw IOException("Empty body for ${request.url}")
                    downloadManager.pageFile(mangaId, chapterId, index).outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
                setProgress(workDataOf(DownloadManager.PROGRESS_KEY to index + 1, DownloadManager.TOTAL_KEY to pages.size))
            }
            downloadManager.doneMarker(mangaId, chapterId).createNewFile()
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_MANGA_ID = "manga_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        private const val MAX_ATTEMPTS = 3
    }
}
