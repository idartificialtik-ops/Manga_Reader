package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.library.model.LibraryManga
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.flow.Flow

class GetLibraryMangas(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(): List<LibraryManga> {
        return mangaRepository.getLibraryManga()
    }

    fun awaitAsFlow(): Flow<List<LibraryManga>> {
        return mangaRepository.getLibraryMangaAsFlow()
    }
}
