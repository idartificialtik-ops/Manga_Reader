package dev.idart.mangareader.data.source

import db.Sources
import dev.idart.mangareader.data.DatabaseHandler
import dev.idart.mangareader.domain.source.model.StoredSource
import kotlinx.coroutines.flow.Flow

class SourceDataStore(
    private val handler: DatabaseHandler,
) {
    suspend fun getAll(): List<Sources> {
        return handler.awaitList { sourcesQueries.findAll() }
    }

    fun getAllAsFlow(): Flow<List<Sources>> {
        return handler.subscribeToList { sourcesQueries.findAll() }
    }

    suspend fun getById(id: Long): Sources? {
        return handler.awaitOneOrNull { sourcesQueries.findOne(id) }
    }

    suspend fun upsert(source: StoredSource) {
        handler.await { sourcesQueries.upsert(id = source.id, lang = source.lang, name = source.name) }
    }
}
