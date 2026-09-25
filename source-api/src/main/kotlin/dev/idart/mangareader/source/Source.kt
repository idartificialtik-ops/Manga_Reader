package dev.idart.mangareader.source

import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.MangasPage
import dev.idart.mangareader.source.model.Page
import dev.idart.mangareader.source.model.SChapter
import dev.idart.mangareader.source.model.SManga

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc...
 */
interface Source {
    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Get a page with a list of manga.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getPopularManga(page: Int): MangasPage

    /**
     * Get a page with a list of latest manga updates.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): MangasPage

    /**
     * Get a page with a list of manga.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage

    /**
     * Fetches updated manga details and chapter list.
     *
     * @param manga the manga to fetch details for. Only [SManga.url] is guaranteed to be set.
     * @return the updated manga details.
     */
    suspend fun getMangaDetails(manga: SManga): SManga = manga

    /**
     * Get the list of chapters of a manga.
     *
     * @param manga the manga to fetch chapters for.
     * @return the chapters for the manga.
     */
    suspend fun getChapterList(manga: SManga): List<SChapter>

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    suspend fun getPageList(chapter: SChapter): List<Page>

    /**
     * Get manga related/similar to the given one (suggestions row).
     *
     * The default implementation searches the catalogue by title keywords.
     * Sources with native related-manga support should override this.
     *
     * @param manga the manga to find suggestions for.
     * @return similar manga (never includes [manga] itself).
     */
    suspend fun getRelatedMangas(manga: SManga): List<SManga> = emptyList()

    /**
     * Returns the browser url of the given manga.
     */
    fun getMangaUrl(manga: SManga): String = ""
}
