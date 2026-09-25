package dev.idart.mangareader.domain.chapter.interactor

import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import kotlinx.coroutines.flow.Flow

class GetChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): List<Chapter> {
        return chapterRepository.getChaptersByMangaId(mangaId, applyScanlatorFilter)
    }

    fun awaitAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): Flow<List<Chapter>> {
        return chapterRepository.getChaptersByMangaIdAsFlow(mangaId, applyScanlatorFilter)
    }
}
