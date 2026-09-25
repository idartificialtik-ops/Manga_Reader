package dev.idart.mangareader.domain.chapter.repository

import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.chapter.model.ChapterUpdate
import kotlinx.coroutines.flow.Flow

interface ChapterRepository {
    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChaptersByMangaId(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): List<Chapter>

    fun getChaptersByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(
        url: String,
        mangaId: Long,
    ): Chapter?

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun insert(chapter: Chapter): Long

    suspend fun update(update: ChapterUpdate): Boolean

    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>): Boolean

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)
}
