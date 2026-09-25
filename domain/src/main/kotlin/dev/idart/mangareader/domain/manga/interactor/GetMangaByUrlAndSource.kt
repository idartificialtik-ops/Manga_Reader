package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow

class GetMangaByUrlAndSource(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(
        url: String,
        sourceId: Long,
    ): Manga? {
        return mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
    }

    fun awaitAsFlow(
        url: String,
        sourceId: Long,
    ): Flow<Manga?> {
        return mangaRepository.getMangaByUrlAndSourceIdAsFlow(url, sourceId)
    }
}
