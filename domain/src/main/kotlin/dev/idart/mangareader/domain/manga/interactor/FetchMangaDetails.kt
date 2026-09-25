package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.model.toChapter
import dev.idart.mangareader.domain.source.model.toMangaUpdate
import dev.idart.mangareader.domain.source.model.toSManga
import dev.idart.mangareader.domain.source.service.SourceManager

/**
 * Fetches manga details + chapter list from the network source and syncs them
 * into the database. New chapters are inserted, existing ones are kept
 * (progress is never overwritten).
 */
class FetchMangaDetails(
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long) {
        val manga = mangaRepository.getMangaById(mangaId)
        val source = sourceManager.getSource(manga.source) ?: return
        val networkManga = manga.toSManga()

        val updated = source.getMangaDetails(networkManga)
        mangaRepository.update(updated.toMangaUpdate(mangaId))

        val networkChapters = source.getChapterList(networkManga)
        val existingUrls = chapterRepository.getChaptersByMangaId(mangaId).map { it.url }.toSet()
        networkChapters.forEachIndexed { index, networkChapter ->
            if (networkChapter.url !in existingUrls) {
                chapterRepository.insert(networkChapter.toChapter(mangaId, index.toLong()))
            }
        }

        mangaRepository.update(MangaUpdate(id = mangaId, initialized = true))
    }
}
