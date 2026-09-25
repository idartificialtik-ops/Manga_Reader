package dev.idart.mangareader.data

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import dev.idart.mangareader.data.db.MangaReaderDatabase
import kotlinx.coroutines.flow.Flow

interface DatabaseHandler {
    suspend fun <T> await(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> T,
    ): T

    suspend fun <T : Any> awaitList(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> Query<T>,
    ): List<T>

    suspend fun <T : Any> awaitListExecutable(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> ExecutableQuery<T>,
    ): List<T>

    suspend fun <T : Any> awaitOne(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> Query<T>,
    ): T

    suspend fun <T : Any> awaitOneExecutable(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> ExecutableQuery<T>,
    ): T

    suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> Query<T>,
    ): T?

    suspend fun <T : Any> awaitOneOrNullExecutable(
        inTransaction: Boolean = false,
        block: suspend MangaReaderDatabase.() -> ExecutableQuery<T>,
    ): T?

    fun <T : Any> subscribeToList(block: MangaReaderDatabase.() -> Query<T>): Flow<List<T>>

    fun <T : Any> subscribeToOne(block: MangaReaderDatabase.() -> Query<T>): Flow<T>

    fun <T : Any> subscribeToOneOrNull(block: MangaReaderDatabase.() -> Query<T>): Flow<T?>
}
