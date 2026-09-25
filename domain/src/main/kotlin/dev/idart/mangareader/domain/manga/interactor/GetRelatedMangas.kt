package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.model.toSManga
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.SManga

/**
 * Returns manga related/similar to the given library entry, for the
 * suggestions row. Delegates to the source (native related support or
 * title-keyword search fallback).
 */
class GetRelatedMangas(
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(mangaId: Long): List<SManga> {
        val manga = mangaRepository.getMangaById(mangaId)
        val source = sourceManager.getSource(manga.source) ?: return emptyList()
        return try {
            source.getRelatedMangas(manga.toSManga())
        } catch (_: Exception) {
            emptyList()
        }
    }
}
