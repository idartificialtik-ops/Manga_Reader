package dev.idart.mangareader.data.history

import db.History
import dev.idart.mangareader.domain.history.model.History as HistoryModel

object HistoryMapper {
    fun map(row: History): HistoryModel =
        HistoryModel(
            id = row._id,
            chapterId = row.chapter_id,
            readAt = row.last_read,
            readDuration = row.time_read,
        )
}
