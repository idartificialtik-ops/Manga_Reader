package dev.idart.mangareader.domain.source.model

import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.MangasPage
import dev.idart.mangareader.source.model.Page
import dev.idart.mangareader.source.model.SChapter
import dev.idart.mangareader.source.model.SManga

/**
 * Placeholder for a source referenced by the database that is not installed
 * (e.g. its extension was uninstalled). Keeps library entries readable instead
 * of crashing lookups.
 */
class StubSource(
    override val id: Long,
    override val name: String = "Unknown source ($id)",
    override val lang: String = "",
) : CatalogueSource {
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
}
