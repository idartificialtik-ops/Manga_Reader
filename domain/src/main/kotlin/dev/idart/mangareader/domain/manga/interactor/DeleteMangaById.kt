package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.repository.MangaRepository

class DeleteMangaById(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(mangaId: Long) {
        mangaRepository.deleteManga(mangaId)
    }
}
