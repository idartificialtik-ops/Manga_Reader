package dev.idart.mangareader.domain.source.interactor

import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.MangasPage

/**
 * Browse operations (popular / latest / search) against a catalogue source.
 */
class BrowseSourceCatalogue(
    private val sourceManager: SourceManager,
) {

    fun getSource(sourceId: Long): CatalogueSource {
        return sourceManager.getSource(sourceId)
            ?: error("Fonte $sourceId não instalada")
    }

    suspend fun popular(sourceId: Long, page: Int): MangasPage {
        return getSource(sourceId).getPopularManga(page)
    }

    suspend fun latest(sourceId: Long, page: Int): MangasPage {
        return getSource(sourceId).getLatestUpdates(page)
    }

    suspend fun search(sourceId: Long, page: Int, query: String, filters: FilterList): MangasPage {
        return getSource(sourceId).getSearchManga(page, query, filters)
    }
}
