package dev.idart.mangareader.data.history

import db.History
import dev.idart.mangareader.data.DatabaseHandler
import kotlinx.coroutines.flow.Flow
import java.util.Date

class HistoryDataStore(
    private val handler: DatabaseHandler,
) {
    suspend fun getByMangaId(mangaId: Long): List<History> {
        return handler.awaitList { historyQueries.getHistoryByMangaId(mangaId) }
    }

    fun getByMangaIdAsFlow(mangaId: Long): Flow<List<History>> {
        return handler.subscribeToList { historyQueries.getHistoryByMangaId(mangaId) }
    }

    /**
     * Same semantics as the reference `history.upsert` query (accumulates [timeRead]).
     */
    suspend fun upsert(
        chapterId: Long,
        readAt: Date,
        timeRead: Long,
    ) {
        handler.await(inTransaction = true) {
            val existing = historyQueries.getByChapterId(chapterId).executeAsOneOrNull()
            if (existing == null) {
                historyQueries.insert(chapterId, readAt, timeRead)
            } else {
                historyQueries.updateTimeRead(readAt, existing.time_read + timeRead, chapterId)
            }
        }
    }

    suspend fun resetByMangaIds(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        handler.await { historyQueries.resetHistoryByMangaIds(mangaIds) }
    }

    suspend fun removeAll() {
        handler.await { historyQueries.removeAllHistory() }
    }

    suspend fun getReadDuration(): Long {
        return handler.awaitOne { historyQueries.getReadDuration() }
    }
}
