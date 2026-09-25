package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository

class InsertFavoriteEntries(
    private val mangaRepository: MangaRepository,
) {
    /**
     * Inserts network manga into the database and marks them as favorite,
     * assigning them to the given categories.
     */
    suspend fun await(
        manga: List<Manga>,
        categoryIds: List<Long>,
    ): List<Manga> {
        val inserted = mangaRepository.insertNetworkManga(manga, updateInfo = true)
        inserted.forEach { entry ->
            mangaRepository.update(MangaUpdate(id = entry.id, favorite = true))
            mangaRepository.setMangaCategories(entry.id, categoryIds)
        }
        return inserted
    }
}
