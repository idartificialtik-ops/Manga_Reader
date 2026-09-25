package dev.idart.mangareader.domain.chapter.interactor

import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.model.toSChapter
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.Page

/**
 * Fetches the page list of a chapter from its network source.
 */
class GetPageList(
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long, chapterId: Long): List<Page> {
        val manga = mangaRepository.getMangaById(mangaId)
        val source = sourceManager.getSource(manga.source)
            ?: error("Fonte ${manga.source} não instalada")
        val chapter = chapterRepository.getChapterById(chapterId)
            ?: error("Capítulo $chapterId não encontrado")
        return source.getPageList(chapter.toSChapter())
    }
}
