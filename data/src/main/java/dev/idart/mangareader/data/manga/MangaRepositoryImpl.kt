package dev.idart.mangareader.data.manga

import dev.idart.mangareader.data.category.CategoryDataStore
import dev.idart.mangareader.data.chapter.ChapterDataStore
import dev.idart.mangareader.data.history.HistoryDataStore
import dev.idart.mangareader.domain.library.model.LibraryManga
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar

class MangaRepositoryImpl(
    private val mangaDataStore: MangaDataStore,
    private val chapterDataStore: ChapterDataStore,
    private val historyDataStore: HistoryDataStore,
    private val categoryDataStore: CategoryDataStore,
) : MangaRepository {
    override suspend fun getMangaById(id: Long): Manga {
        return MangaMapper.map(mangaDataStore.getById(id))
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return mangaDataStore.getByIdAsFlow(id).map(MangaMapper::map)
    }

    override suspend fun getMangaByUrlAndSourceId(
        url: String,
        sourceId: Long,
    ): Manga? {
        return mangaDataStore.getByUrlAndSource(url, sourceId)?.let(MangaMapper::map)
    }

    override fun getMangaByUrlAndSourceIdAsFlow(
        url: String,
        sourceId: Long,
    ): Flow<Manga?> {
        return mangaDataStore.getByUrlAndSourceAsFlow(url, sourceId).map { it?.let(MangaMapper::map) }
    }

    override suspend fun getFavorites(): List<Manga> {
        return mangaDataStore.getFavorites().map(MangaMapper::map)
    }

    override fun getFavoritesAsFlow(): Flow<List<Manga>> {
        return mangaDataStore.getFavoritesAsFlow().map { list -> list.map(MangaMapper::map) }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return mangaDataStore.getReadMangaNotInLibrary().map(MangaMapper::map)
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return mangaDataStore.getFavorites().map { row -> toLibraryManga(row._id, MangaMapper.map(row)) }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return mangaDataStore.getFavoritesAsFlow().map { favorites ->
            favorites.map { row -> toLibraryManga(row._id, MangaMapper.map(row)) }
        }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return mangaDataStore.getFavoritesBySourceIdAsFlow(sourceId)
            .map { list -> list.map(MangaMapper::map) }
    }

    override suspend fun getMangaBySourceId(sourceId: Long): List<Manga> {
        return mangaDataStore.getBySource(sourceId).map(MangaMapper::map)
    }

    override suspend fun getAll(): List<Manga> {
        return mangaDataStore.getAll().map(MangaMapper::map)
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val startOfDay =
            Calendar.getInstance().let {
                it.set(Calendar.HOUR_OF_DAY, 0)
                it.set(Calendar.MINUTE, 0)
                it.set(Calendar.SECOND, 0)
                it.set(Calendar.MILLISECOND, 0)
                it.timeInMillis
            }
        return mangaDataStore.getUpcomingMangaAsFlow(startOfDay, statuses.toList())
            .map { list -> list.map(MangaMapper::map) }
    }

    override suspend fun resetViewerFlags(): Boolean {
        mangaDataStore.resetViewerFlags()
        return true
    }

    override suspend fun setMangaCategories(
        mangaId: Long,
        categoryIds: List<Long>,
    ) {
        mangaDataStore.setMangaCategories(mangaId, categoryIds)
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        mangaDataStore.update(update)
        return true
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        mangaDataStore.updateAll(mangaUpdates)
        return true
    }

    override suspend fun insertNetworkManga(
        manga: List<Manga>,
        updateInfo: Boolean,
    ): List<Manga> {
        return mangaDataStore.insertNetworkManga(manga, updateInfo).map(MangaMapper::map)
    }

    override suspend fun deleteManga(mangaId: Long) {
        mangaDataStore.deleteById(mangaId)
    }

    private suspend fun toLibraryManga(
        mangaId: Long,
        manga: Manga,
    ): LibraryManga {
        val chapters = chapterDataStore.getByMangaId(mangaId)
        val history = historyDataStore.getByMangaId(mangaId)
        val categories = categoryDataStore.getByMangaId(mangaId)
        return LibraryManga(
            manga = manga,
            category = categories.firstOrNull()?.id ?: 0L,
            totalChapters = chapters.size.toLong(),
            readCount = chapters.count { it.read }.toLong(),
            bookmarkCount = chapters.count { it.bookmark }.toLong(),
            latestUpload = chapters.maxOfOrNull { it.date_upload } ?: 0L,
            chapterFetchedAt = chapters.maxOfOrNull { it.date_fetch } ?: 0L,
            lastRead = history.maxOfOrNull { it.last_read?.time ?: 0L } ?: 0L,
        )
    }
}
