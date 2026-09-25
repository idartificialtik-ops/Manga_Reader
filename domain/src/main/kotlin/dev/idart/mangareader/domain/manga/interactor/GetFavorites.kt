package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow

class GetFavorites(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(): List<Manga> {
        return mangaRepository.getFavorites()
    }

    fun awaitAsFlow(): Flow<List<Manga>> {
        return mangaRepository.getFavoritesAsFlow()
    }
}
