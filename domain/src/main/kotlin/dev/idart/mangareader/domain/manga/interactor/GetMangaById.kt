package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow

class GetMangaById(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(id: Long): Manga {
        return mangaRepository.getMangaById(id)
    }

    suspend fun awaitAsFlow(id: Long): Flow<Manga> {
        return mangaRepository.getMangaByIdAsFlow(id)
    }
}
