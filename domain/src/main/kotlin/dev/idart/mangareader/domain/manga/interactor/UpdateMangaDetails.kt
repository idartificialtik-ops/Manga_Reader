package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository

class UpdateMangaDetails(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(update: MangaUpdate): Boolean {
        return mangaRepository.update(update)
    }

    suspend fun awaitAll(updates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(updates)
    }
}
