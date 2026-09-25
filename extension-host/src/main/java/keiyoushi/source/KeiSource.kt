package keiyoushi.source

import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.CacheControlInterceptor
import keiyoushi.network.RateLimitInterceptor
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.jsonInstance
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.CompressionInterceptor
import okhttp3.Gzip
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.Brotli
import okhttp3.brotli.BrotliInterceptor
import okhttp3.zstd.Zstd
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days

/**
 * Real host implementation (mirrors keiyoushi extensions core `KeiSource`,
 * the suspend-based source API used by lib 1.6 extensions).
 *
 * Adaptations vs upstream: suspend catalogue methods are declared here (the
 * host's legacy `CatalogueSource` is Observable-based); the filter cache uses
 * plain JSON instead of zstd so no extra codec is needed.
 */
abstract class KeiSource : HttpSource() {

    // make `headers` not lazy
    init {
        val delegate = object : Lazy<Headers> {
            override val value: Headers get() = headersBuilder().build()
            override fun isInitialized(): Boolean = true
        }

        HttpSource::class.java.getDeclaredField("headers\$delegate").apply {
            isAccessible = true
            set(this@KeiSource, delegate)
        }
    }

    /**
     * Customizes the OkHttpClient builder. Subclasses can override this to configure timeouts,
     * add application/network interceptors, or configure cookie/cache/dns settings.
     */
    protected open fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this

    final override val client: OkHttpClient by lazy {
        network.client.newBuilder().apply {
            with(interceptors()) {
                check(
                    this.any { it.javaClass.simpleName == "UncaughtExceptionInterceptor" },
                ) {
                    "UncaughtExceptionInterceptor must be present in default client"
                }

                check(
                    this.any { it.javaClass.simpleName == "UserAgentInterceptor" },
                ) {
                    "UserAgentInterceptor must be present in default client"
                }

                check(
                    this.any { it.javaClass.simpleName == "CloudflareInterceptor" },
                ) {
                    "CloudflareInterceptor must be present in default client"
                }
            }

            with(networkInterceptors()) {
                check(
                    this.none { it.javaClass.simpleName == "IgnoreGzipInterceptor" },
                ) {
                    "IgnoreGzipInterceptor must not be present in default client"
                }

                check(
                    this.none { it is BrotliInterceptor },
                ) {
                    "BrotliInterceptor must not be present in default client"
                }
            }

            configureClient()

            // cf interceptor below source specific interceptors
            interceptors().apply {
                // some sources remove the cf interceptor, so do nullable check
                val cloudflareInterceptor = firstOrNull { it.javaClass.simpleName == "CloudflareInterceptor" }
                if (cloudflareInterceptor != null) {
                    remove(cloudflareInterceptor)
                    add(cloudflareInterceptor)
                }
            }

            // last application interceptor
            addInterceptor(CompressionInterceptor(Brotli, Gzip, Zstd))

            // allow caching when server doesn't explicitly say anything
            addNetworkInterceptor(CacheControlInterceptor())

            // keep the source's rate limiter as the last network interceptor
            networkInterceptors().apply {
                val rateLimiter = firstInstanceOrNull<RateLimitInterceptor>()
                if (rateLimiter != null) {
                    remove(rateLimiter)
                    add(rateLimiter)
                }
            }
        }.build()
    }

    /**
     * Customizes the base Headers builder. Subclasses can override this to append source-specific
     * headers.
     *
     * Note: "User-Agent", "Referer", and "Origin" are already added by default.
     */
    protected open fun Headers.Builder.configureHeaders(): Headers.Builder = this

    final override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .configureHeaders()

    /**
     * Fetches a page of popular manga from the source.
     */
    abstract suspend fun getPopularManga(page: Int): MangasPage

    /**
     * Whether the source supports fetching a list of latest manga updates.
     */
    override val supportsLatest get() = true

    /**
     * Fetches a page of latest manga updates from the source.
     */
    abstract suspend fun getLatestUpdates(page: Int): MangasPage

    /**
     * Fetches a page of manga matching the query and filters.
     */
    abstract suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage

    /**
     * Fetches details of a single manga directly using its HTTP URL.
     */
    protected open suspend fun getMangaByUrl(url: HttpUrl): SManga? = throw Exception("getMangaByUrl not implemented")

    /**
     * Fetches a page of manga list from given url.
     */
    protected open suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        val manga = getMangaByUrl(url)

        return MangasPage(listOfNotNull(manga), hasNextPage = false)
    }

    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        query.toHttpUrlOrNull()?.also { url ->
            return getMangasByUrl(url, page)
        }

        return getSearchMangaList(page, query, filters)
    }

    private val filterFetchInFlight = AtomicBoolean(false)
    private val filterFetchAttemptCount = AtomicInteger(0)
    private val maxFilterFetchAttempts = 3
    private val filterCacheDir: File get() =
        applicationContext.cacheDir.resolve("source_$id")
            .apply { mkdirs() }
    private val filterCacheFile: File get() =
        filterCacheDir.resolve("filters.json")

    /**
     * Whether this source fetches its filters from the network
     * implement [fetchFilterData] when true
     */
    protected open val supportsFilterFetching: Boolean get() = false

    /**
     * Text shown as a filter header hint while filters are unavailable
     * Do not override by hand
     */
    protected open val filterFetchHint: String get() = "Tap 'Reset' to load filters"

    /**
     * Fetches filter data from the network.
     */
    protected open suspend fun fetchFilterData(): JsonElement = JsonNull

    /**
     * Builds a [FilterList] from previously cached/fetched filter data.
     */
    protected open fun getFilterList(data: JsonElement?): FilterList = FilterList()

    final override fun getFilterList(): FilterList {
        if (!supportsFilterFetching) return getFilterList(data = null)

        val (cached, isFresh) = readFilterCacheState()
        val parsed = cached?.let {
            try {
                getFilterList(it)
            } catch (e: Exception) {
                Log.e(name, "Failed to parse filter data", e)
                null
            }
        }

        if (parsed == null || !isFresh) {
            triggerBackgroundFilterFetch()
        }

        if (parsed != null) return parsed

        return FilterList(
            buildList {
                addAll(getFilterList(data = null))
                if (isNotEmpty()) {
                    add(Filter.Separator())
                }
                add(Filter.Header(filterFetchHint))
            },
        )
    }

    private data class FilterCacheState(val data: JsonElement?, val isFresh: Boolean)

    private fun readFilterCacheState(): FilterCacheState {
        val file = filterCacheFile
        if (!file.exists()) return FilterCacheState(data = null, isFresh = false)

        val age = System.currentTimeMillis() - file.lastModified()
        val isFresh = age <= 3.days.inWholeMilliseconds

        val data = try {
            jsonInstance.parseToJsonElement(file.readText())
        } catch (_: Exception) {
            null
        }

        return FilterCacheState(data, isFresh)
    }

    private fun writeFilterCache(data: JsonElement) {
        val tmpFile = File.createTempFile("filters", ".tmp", filterCacheDir)

        try {
            tmpFile.writeText(jsonInstance.encodeToString(JsonElement.serializer(), data))

            if (!tmpFile.renameTo(filterCacheFile)) {
                filterCacheFile.delete()
                if (!tmpFile.renameTo(filterCacheFile)) {
                    throw IOException("Failed to move $tmpFile to $filterCacheFile")
                }
            }
        } finally {
            tmpFile.delete()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun triggerBackgroundFilterFetch() {
        if (filterFetchAttemptCount.get() >= maxFilterFetchAttempts) return
        if (!filterFetchInFlight.compareAndSet(false, true)) return

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val data = fetchFilterData()
                writeFilterCache(data)
                filterFetchAttemptCount.set(0)
            } catch (e: Exception) {
                Log.e(name, "Failed to fetch filter data", e)
                filterFetchAttemptCount.incrementAndGet()
            } finally {
                filterFetchInFlight.set(false)
            }
        }
    }

    /**
     * Fetches updated information for a manga (details and/or chapters).
     */
    abstract suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate

    private val updatesInFlight = ConcurrentHashMap<String, Boolean>()

    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        check(fetchDetails || fetchChapters) { "getMangaUpdate called with nothing to fetch (fetchDetails=false, fetchChapters=false)" }

        check(updatesInFlight.putIfAbsent(manga.url, true) == null) { "getMangaUpdate must not be called concurrently for same manga" }

        try {
            val update = fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters)

            return SMangaUpdate(
                manga = update.manga.apply { initialized = true },
                chapters = update.chapters,
            )
        } finally {
            updatesInFlight.remove(manga.url)
        }
    }

    /**
     * Whether the source supports retrieving related manga.
     *
     * Only works on Komikku
     */
    override val supportsRelatedMangas get() = false

    /**
     * Fetch related mangas for a manga from source/site.
     *
     * Only works on Komikku
     */
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    /**
     * Returns the absolute web URL for the provided manga.
     */
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    /**
     * Returns the absolute web URL for the provided chapter.
     */
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    /**
     * Get the list of pages a chapter has.
     */
    abstract suspend fun getPageList(chapter: SChapter): List<Page>

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .get()
        .build()
}
