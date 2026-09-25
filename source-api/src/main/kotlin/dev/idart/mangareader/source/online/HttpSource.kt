package dev.idart.mangareader.source.online

import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.source.RelatedMangas
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.MangasPage
import dev.idart.mangareader.source.model.Page
import dev.idart.mangareader.source.model.SChapter
import dev.idart.mangareader.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Base class for online sources fetched over HTTP and parsed from the response.
 *
 * It keeps the classic request-builder / response-parser split used by existing
 * extensions, but exposes the suspend API defined by [CatalogueSource]:
 * requests are executed on [Dispatchers.IO] and the blocking parse methods run there.
 */
abstract class HttpSource(
    protected val defaultClient: OkHttpClient,
) : CatalogueSource {
    abstract override val name: String

    abstract val baseUrl: String

    open val client: OkHttpClient = defaultClient

    open val headers: Headers =
        Headers.Builder()
            .add("User-Agent", DEFAULT_SOURCE_USER_AGENT)
            .build()

    // ---------- Request builders (override to customize) ----------

    protected open fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException("Popular manga is not supported by this source")

    protected open fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Latest updates are not supported by this source")

    protected open fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Search is not supported by this source")

    protected open fun mangaDetailsRequest(manga: SManga): Request =
        okhttp3.Request.Builder().url(baseUrl + manga.url).headers(headers).build()

    protected open fun chapterListRequest(manga: SManga): Request =
        okhttp3.Request.Builder().url(baseUrl + manga.url).headers(headers).build()

    protected open fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return okhttp3.Request.Builder().url(url).headers(headers).build()
    }

    /**
     * Builds the request for a page image. Extensions override this to add
     * per-image headers (e.g. `Referer` required by hotlink-protected CDNs).
     * Downloaders and readers must use this instead of a raw GET.
     */
    open fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: throw IllegalStateException("Page ${page.index} has no image url")
        return okhttp3.Request.Builder().url(url).headers(headers).build()
    }

    // ---------- Response parsers (must be implemented) ----------

    protected abstract fun popularMangaParse(response: Response): MangasPage

    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    protected abstract fun searchMangaParse(response: Response): MangasPage

    protected abstract fun mangaDetailsParse(response: Response): SManga

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    protected abstract fun pageListParse(response: Response): List<Page>

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    // ---------- Suspend CatalogueSource implementation ----------

    override suspend fun getPopularManga(page: Int): MangasPage = fetch(popularMangaRequest(page), ::popularMangaParse)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetch(latestUpdatesRequest(page), ::latestUpdatesParse)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetch(searchMangaRequest(page, query, filters), ::searchMangaParse)

    override suspend fun getMangaDetails(manga: SManga): SManga = fetch(mangaDetailsRequest(manga), ::mangaDetailsParse)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = fetch(chapterListRequest(manga), ::chapterListParse)

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetch(pageListRequest(chapter), ::pageListParse)

    override suspend fun getRelatedMangas(manga: SManga): List<SManga> =
        RelatedMangas.bySearch(this, manga)

    private suspend fun <T> fetch(
        request: Request,
        parse: (Response) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use(parse)
        }

    // ---------- Helpers for implementations ----------

    protected fun Response.asJsoup(): Document {
        val html = body!!.string()
        return Jsoup.parse(html, request.url.toString())
    }

    companion object {
        const val DEFAULT_SOURCE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
