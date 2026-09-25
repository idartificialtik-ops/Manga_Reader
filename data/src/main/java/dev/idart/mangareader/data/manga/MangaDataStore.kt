package dev.idart.mangareader.data.manga

import db.Mangas
import dev.idart.mangareader.data.DatabaseHandler
import dev.idart.mangareader.data.StringListColumnAdapter
import dev.idart.mangareader.data.UpdateStrategyColumnAdapter
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import kotlinx.coroutines.flow.Flow

class MangaDataStore(
    private val handler: DatabaseHandler,
) {
    suspend fun getById(id: Long): Mangas {
        return handler.awaitOne { mangasQueries.getMangaById(id) }
    }

    fun getByIdAsFlow(id: Long): Flow<Mangas> {
        return handler.subscribeToOne { mangasQueries.getMangaById(id) }
    }

    suspend fun getByUrlAndSource(
        url: String,
        source: Long,
    ): Mangas? {
        return handler.awaitOneOrNull { mangasQueries.getMangaByUrlAndSource(url, source) }
    }

    fun getByUrlAndSourceAsFlow(
        url: String,
        source: Long,
    ): Flow<Mangas?> {
        return handler.subscribeToOneOrNull { mangasQueries.getMangaByUrlAndSource(url, source) }
    }

    suspend fun getFavorites(): List<Mangas> {
        return handler.awaitList { mangasQueries.getFavorites() }
    }

    fun getFavoritesAsFlow(): Flow<List<Mangas>> {
        return handler.subscribeToList { mangasQueries.getFavorites() }
    }

    suspend fun getReadMangaNotInLibrary(): List<Mangas> {
        return handler.awaitList { mangasQueries.getReadMangaNotInLibrary() }
    }

    suspend fun getAll(): List<Mangas> {
        return handler.awaitList { mangasQueries.getAll() }
    }

    suspend fun getBySource(sourceId: Long): List<Mangas> {
        return handler.awaitList { mangasQueries.getBySource(sourceId) }
    }

    fun getFavoritesBySourceIdAsFlow(sourceId: Long): Flow<List<Mangas>> {
        return handler.subscribeToList { mangasQueries.getFavoriteBySourceId(sourceId) }
    }

    fun getUpcomingMangaAsFlow(
        startOfDay: Long,
        statuses: Collection<Long>,
    ): Flow<List<Mangas>> {
        return handler.subscribeToList { mangasQueries.getUpcomingManga(startOfDay, statuses) }
    }

    suspend fun insert(manga: Manga): Long {
        return handler.await(inTransaction = true) {
            mangasQueries.insert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = manga.nextUpdate,
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                updateStrategy = manga.updateStrategy,
                calculateInterval = manga.fetchInterval.toLong(),
                version = manga.version,
                notes = manga.notes,
                memo = manga.memo,
            )
            mangasQueries.selectLastInsertedRowId().executeAsOne()
        }
    }

    suspend fun insertNetworkManga(
        manga: List<Manga>,
        updateInfo: Boolean = true,
    ): List<Mangas> {
        return handler.await(inTransaction = true) {
            manga.map { networkManga ->
                mangasQueries.insertNetworkManga(
                    source = networkManga.source,
                    url = networkManga.url,
                    artist = networkManga.artist,
                    author = networkManga.author,
                    description = networkManga.description,
                    genre = networkManga.genre,
                    title = networkManga.title,
                    status = networkManga.status,
                    thumbnailUrl = networkManga.thumbnailUrl,
                    favorite = networkManga.favorite,
                    lastUpdate = networkManga.lastUpdate,
                    nextUpdate = networkManga.nextUpdate,
                    initialized = networkManga.initialized,
                    viewerFlags = networkManga.viewerFlags,
                    chapterFlags = networkManga.chapterFlags,
                    coverLastModified = networkManga.coverLastModified,
                    dateAdded = networkManga.dateAdded,
                    updateStrategy = networkManga.updateStrategy,
                    calculateInterval = networkManga.fetchInterval.toLong(),
                    version = networkManga.version,
                    memo = networkManga.memo,
                    updateTitle = networkManga.title.isNotBlank(),
                    updateCover = networkManga.thumbnailUrl != null,
                    updateDetails = true,
                    updateInfo = updateInfo,
                ).executeAsOne()
            }
        }
    }

    suspend fun update(update: MangaUpdate) {
        handler.await {
            mangasQueries.update(
                source = update.source,
                url = update.url,
                artist = update.artist,
                author = update.author,
                description = update.description,
                genre = update.genre?.let(StringListColumnAdapter::encode),
                title = update.title,
                status = update.status,
                thumbnailUrl = update.thumbnailUrl,
                favorite = update.favorite,
                lastUpdate = update.lastUpdate,
                nextUpdate = update.nextUpdate,
                initialized = update.initialized,
                viewer = update.viewerFlags,
                chapterFlags = update.chapterFlags,
                coverLastModified = update.coverLastModified,
                dateAdded = update.dateAdded,
                updateStrategy = update.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                calculateInterval = update.calculateInterval,
                version = update.version,
                isSyncing = update.isSyncing,
                notes = update.notes,
                memo = null,
                mangaId = update.id,
            )
        }
    }

    suspend fun updateAll(updates: List<MangaUpdate>) {
        handler.await(inTransaction = true) {
            updates.forEach { update ->
                mangasQueries.update(
                    source = update.source,
                    url = update.url,
                    artist = update.artist,
                    author = update.author,
                    description = update.description,
                    genre = update.genre?.let(StringListColumnAdapter::encode),
                    title = update.title,
                    status = update.status,
                    thumbnailUrl = update.thumbnailUrl,
                    favorite = update.favorite,
                    lastUpdate = update.lastUpdate,
                    nextUpdate = update.nextUpdate,
                    initialized = update.initialized,
                    viewer = update.viewerFlags,
                    chapterFlags = update.chapterFlags,
                    coverLastModified = update.coverLastModified,
                    dateAdded = update.dateAdded,
                    updateStrategy = update.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    calculateInterval = update.calculateInterval,
                    version = update.version,
                    isSyncing = update.isSyncing,
                    notes = update.notes,
                    memo = null,
                    mangaId = update.id,
                )
            }
        }
    }

    suspend fun setMangaCategories(
        mangaId: Long,
        categoryIds: List<Long>,
    ) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.forEach { categoryId ->
                mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    suspend fun resetViewerFlags() {
        handler.await { mangasQueries.resetViewerFlags() }
    }

    suspend fun deleteById(id: Long) {
        handler.await { mangasQueries.deleteById(id) }
    }
}
