package dev.idart.mangareader.data.source

import dev.idart.mangareader.domain.source.model.StoredSource
import dev.idart.mangareader.domain.source.repository.SourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SourceRepositoryImpl(
    private val sourceDataStore: SourceDataStore,
) : SourceRepository {
    override suspend fun getAll(): List<StoredSource> {
        return sourceDataStore.getAll().map(StoredSourceMapper::map)
    }

    override fun getAllAsFlow(): Flow<List<StoredSource>> {
        return sourceDataStore.getAllAsFlow().map { list -> list.map(StoredSourceMapper::map) }
    }

    override suspend fun getById(id: Long): StoredSource? {
        return sourceDataStore.getById(id)?.let(StoredSourceMapper::map)
    }

    override suspend fun upsert(source: StoredSource) {
        sourceDataStore.upsert(source)
    }
}
