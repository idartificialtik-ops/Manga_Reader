package dev.idart.mangareader.domain.source.repository

import dev.idart.mangareader.domain.source.model.StoredSource
import kotlinx.coroutines.flow.Flow

interface SourceRepository {
    suspend fun getAll(): List<StoredSource>

    fun getAllAsFlow(): Flow<List<StoredSource>>

    suspend fun getById(id: Long): StoredSource?

    suspend fun upsert(source: StoredSource)
}
