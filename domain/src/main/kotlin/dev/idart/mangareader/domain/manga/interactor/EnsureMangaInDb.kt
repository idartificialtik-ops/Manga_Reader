package dev.idart.mangareader.domain.manga.interactor

import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.model.toManga
import dev.idart.mangareader.source.model.SManga

/**
 * Returns the local id of a network manga, inserting it as a non-favorite
 * stub first when it is not in the database yet.
 */
class EnsureMangaInDb(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(networkManga: SManga, sourceId: Long): Long {
        val existing = mangaRepository.getMangaByUrlAndSourceId(networkManga.url, sourceId)
        if (existing != null) return existing.id
        val inserted = mangaRepository.insertNetworkManga(listOf(networkManga.toManga(sourceId)))
        return inserted.first().id
    }
}
