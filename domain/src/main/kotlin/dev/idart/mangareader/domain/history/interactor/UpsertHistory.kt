package dev.idart.mangareader.domain.history.interactor

import dev.idart.mangareader.domain.history.repository.HistoryRepository
import java.util.Date

class UpsertHistory(
    private val historyRepository: HistoryRepository,
) {
    suspend fun await(
        chapterId: Long,
        readAt: Date = Date(),
        readDuration: Long = 0L,
    ) {
        historyRepository.upsertHistory(chapterId, readAt, readDuration)
    }
}
