package dev.idart.mangareader.domain.history.repository

import dev.idart.mangareader.domain.history.model.History
import kotlinx.coroutines.flow.Flow
import java.util.Date

interface HistoryRepository {
    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    fun getHistoryByMangaIdAsFlow(mangaId: Long): Flow<List<History>>

    suspend fun upsertHistory(
        chapterId: Long,
        readAt: Date,
        readDuration: Long,
    )

    suspend fun resetHistoryByMangaIds(mangaIds: List<Long>)

    suspend fun removeAllHistory()

    suspend fun getTotalReadDuration(): Long
}
