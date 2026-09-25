package dev.idart.mangareader.data.chapter

import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.chapter.model.ChapterUpdate
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChapterRepositoryImpl(
    private val chapterDataStore: ChapterDataStore,
) : ChapterRepository {
    override suspend fun getChapterById(id: Long): Chapter? {
        return chapterDataStore.getById(id)?.let(ChapterMapper::map)
    }

    override suspend fun getChaptersByMangaId(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): List<Chapter> {
        return chapterDataStore.getByMangaId(mangaId, applyScanlatorFilter).map(ChapterMapper::map)
    }

    override fun getChaptersByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> {
        return chapterDataStore.getByMangaIdAsFlow(mangaId, applyScanlatorFilter)
            .map { list -> list.map(ChapterMapper::map) }
    }

    override suspend fun getChapterByUrlAndMangaId(
        url: String,
        mangaId: Long,
    ): Chapter? {
        return chapterDataStore.getByUrlAndMangaId(url, mangaId)?.let(ChapterMapper::map)
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return chapterDataStore.getBookmarkedByMangaId(mangaId).map(ChapterMapper::map)
    }

    override suspend fun insert(chapter: Chapter): Long {
        return chapterDataStore.insert(chapter)
    }

    override suspend fun update(update: ChapterUpdate): Boolean {
        chapterDataStore.update(update)
        return true
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>): Boolean {
        chapterDataStore.updateAll(chapterUpdates)
        return true
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        chapterDataStore.removeWithIds(chapterIds)
    }
}
