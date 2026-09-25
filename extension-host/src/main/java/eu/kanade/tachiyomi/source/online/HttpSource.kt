package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/**
 * Real host implementation of the classic extensions API.
 *
 * Same binary shape as extensions-lib 1.x `HttpSource` (so installed
 * extension APKs link against it), with working request execution.
 */
abstract class HttpSource : CatalogueSource {

    /**
     * Network service.
     */
    protected val network: NetworkHelper by lazy { Injekt.get() }

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    abstract val baseUrl: String

    /**
     * Version id used to generate the source id. If the site completely changes and urls are
     * incompatible, you may increase this value and it'll be considered as a new source.
     */
    open val versionId: Int = 1

    /**
     * Id of the source. By default it uses a generated id using the first 16 characters (64 bits)
     * of the MD5 of the string `"${name.lowercase()}/$lang/$versionId"`.
     *
     * Note: the generated ID sets the sign bit to `0`.
     */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    /**
     * Headers used for requests.
     */
    open val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for doing requests.
     */
    open val client: OkHttpClient
        get() = network.client

    /**
     * Generates a unique ID for the source (MD5 of `"name/lang/versionId"`, sign bit cleared).
     */
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /**
     * Headers builder for requests. Implementations can override this method for custom headers.
     */
    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", HOST_USER_AGENT)
    }

    /**
     * Visible name of the source.
     */
    override fun toString(): String = "$name (${lang.uppercase()})"

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return client.newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    protected abstract fun popularMangaRequest(page: Int): Request

    protected abstract fun popularMangaParse(response: Response): MangasPage

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
            }
    }

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request

    protected abstract fun searchMangaParse(response: Response): MangasPage

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response ->
                latestUpdatesParse(response)
            }
    }

    protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    open fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(chapterListRequest(manga))
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response)
            }
    }

    protected open fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservableSuccess()
            .map { response ->
                pageListParse(response)
            }
    }

    protected open fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    open fun fetchImageUrl(page: Page): Observable<String> {
        return client.newCall(imageUrlRequest(page))
            .asObservableSuccess()
            .map { response ->
                imageUrlParse(response)
            }
    }

    protected open fun imageUrlRequest(page: Page): Request {
        return GET(page.url, headers)
    }

    protected abstract fun imageUrlParse(response: Response): String

    fun fetchImage(page: Page): Observable<Response> {
        return client.newCall(imageRequest(page)).asObservableSuccess()
    }

    open fun imageRequest(page: Page): Request {
        return GET(page.imageUrl ?: page.url, headers)
    }

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val httpUrl = orig.toHttpUrlOrNull() ?: return orig
            val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
            httpUrl.encodedPath + query
        } catch (_: Exception) {
            orig
        }
    }

    /**
     * Returns the url of the provided manga
     *
     * @since extensions-lib 1.4
     * @param manga the manga
     * @return url of the manga
     */
    open fun getMangaUrl(manga: SManga): String {
        return baseUrl + manga.url
    }

    /**
     * Returns the url of the provided chapter
     *
     * @since extensions-lib 1.4
     * @param chapter the chapter
     * @return url of the chapter
     */
    open fun getChapterUrl(chapter: SChapter): String {
        return baseUrl + chapter.url
    }

    /**
     * Called before inserting a new chapter into database. Use it if you need to override chapter
     * fields, like the title or the chapter number. Do not change anything to [manga].
     *
     * @param chapter the chapter to be added.
     * @param manga the manga of the chapter.
     */
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}

    /**
     * Returns the list of filters for the source.
     */
    override fun getFilterList(): FilterList {
        return FilterList()
    }

    companion object {
        const val HOST_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
