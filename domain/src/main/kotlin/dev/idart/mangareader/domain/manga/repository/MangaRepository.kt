package dev.idart.mangareader.domain.manga.repository

import dev.idart.mangareader.domain.library.model.LibraryManga
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import kotlinx.coroutines.flow.Flow

interface MangaRepository {
    suspend fun getMangaById(id: Long): Manga

    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(
        url: String,
        sourceId: Long,
    ): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(
        url: String,
        sourceId: Long,
    ): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    fun getFavoritesAsFlow(): Flow<List<Manga>>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getMangaBySourceId(sourceId: Long): List<Manga>

    suspend fun getAll(): List<Manga>

    suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setMangaCategories(
        mangaId: Long,
        categoryIds: List<Long>,
    )

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    suspend fun insertNetworkManga(
        manga: List<Manga>,
        updateInfo: Boolean = true,
    ): List<Manga>

    suspend fun deleteManga(mangaId: Long)
}
