package dev.idart.mangareader.data.history

import dev.idart.mangareader.domain.history.model.History
import dev.idart.mangareader.domain.history.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date

class HistoryRepositoryImpl(
    private val historyDataStore: HistoryDataStore,
) : HistoryRepository {
    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return historyDataStore.getByMangaId(mangaId).map(HistoryMapper::map)
    }

    override fun getHistoryByMangaIdAsFlow(mangaId: Long): Flow<List<History>> {
        return historyDataStore.getByMangaIdAsFlow(mangaId)
            .map { list -> list.map(HistoryMapper::map) }
    }

    override suspend fun upsertHistory(
        chapterId: Long,
        readAt: Date,
        readDuration: Long,
    ) {
        historyDataStore.upsert(chapterId, readAt, readDuration)
    }

    override suspend fun resetHistoryByMangaIds(mangaIds: List<Long>) {
        historyDataStore.resetByMangaIds(mangaIds)
    }

    override suspend fun removeAllHistory() {
        historyDataStore.removeAll()
    }

    override suspend fun getTotalReadDuration(): Long {
        return historyDataStore.getReadDuration()
    }
}
