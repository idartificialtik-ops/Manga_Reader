package dev.idart.mangareader.data.chapter

import db.Chapters
import dev.idart.mangareader.data.DatabaseHandler
import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.chapter.model.ChapterUpdate
import dev.idart.mangareader.domain.manga.model.Manga
import kotlinx.coroutines.flow.Flow

class ChapterDataStore(
    private val handler: DatabaseHandler,
) {
    suspend fun getById(id: Long): Chapters? {
        return handler.awaitOneOrNull { chaptersQueries.getChapterById(id) }
    }

    suspend fun getByMangaId(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): List<Chapters> {
        // Same default masks as the reference app: only meaningful when filtering.
        return handler.awaitList {
            chaptersQueries.getChaptersByMangaId(
                mangaId = mangaId,
                applyFilter = if (applyScanlatorFilter) 1 else 0,
                bookmarkUnmask = Manga.CHAPTER_BOOKMARKED_MASK,
                bookmarkMask = Manga.CHAPTER_BOOKMARKED_MASK,
            )
        }
    }

    fun getByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean = false,
    ): Flow<List<Chapters>> {
        return handler.subscribeToList {
            chaptersQueries.getChaptersByMangaId(
                mangaId = mangaId,
                applyFilter = if (applyScanlatorFilter) 1 else 0,
                bookmarkUnmask = Manga.CHAPTER_BOOKMARKED_MASK,
                bookmarkMask = Manga.CHAPTER_BOOKMARKED_MASK,
            )
        }
    }

    suspend fun getByUrlAndMangaId(
        url: String,
        mangaId: Long,
    ): Chapters? {
        return handler.awaitOneOrNull { chaptersQueries.getChapterByUrlAndMangaId(url, mangaId) }
    }

    suspend fun getBookmarkedByMangaId(mangaId: Long): List<Chapters> {
        return handler.awaitList { chaptersQueries.getBookmarkedChaptersByMangaId(mangaId) }
    }

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return handler.awaitList { chaptersQueries.getScanlatorsByMangaId(mangaId) }
            .mapNotNull { it.scanlator }
            .distinct()
    }

    suspend fun insert(chapter: Chapter): Long {
        return handler.await(inTransaction = true) {
            chaptersQueries.insert(
                mangaId = chapter.mangaId,
                url = chapter.url,
                name = chapter.name,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.lastPageRead,
                chapterNumber = chapter.chapterNumber.toDouble(),
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                version = chapter.version,
                memo = chapter.memo,
            )
            chaptersQueries.selectLastInsertedRowId().executeAsOne()
        }
    }

    suspend fun update(update: ChapterUpdate) {
        handler.await {
            chaptersQueries.update(
                mangaId = update.mangaId,
                url = update.url,
                name = update.name,
                scanlator = update.scanlator,
                read = update.read,
                bookmark = update.bookmark,
                lastPageRead = update.lastPageRead,
                chapterNumber = update.chapterNumber?.toDouble(),
                sourceOrder = update.sourceOrder,
                dateFetch = update.dateFetch,
                dateUpload = update.dateUpload,
                version = update.version,
                isSyncing = update.isSyncing,
                memo = null,
                chapterId = update.id,
            )
        }
    }

    suspend fun updateAll(updates: List<ChapterUpdate>) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                chaptersQueries.update(
                    mangaId = update.mangaId,
                    url = update.url,
                    name = update.name,
                    scanlator = update.scanlator,
                    read = update.read,
                    bookmark = update.bookmark,
                    lastPageRead = update.lastPageRead,
                    chapterNumber = update.chapterNumber?.toDouble(),
                    sourceOrder = update.sourceOrder,
                    dateFetch = update.dateFetch,
                    dateUpload = update.dateUpload,
                    version = update.version,
                    isSyncing = update.isSyncing,
                    memo = null,
                    chapterId = update.id,
                )
            }
        }
    }

    suspend fun removeWithIds(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        handler.await { chaptersQueries.removeChaptersWithIds(chapterIds) }
    }
}
