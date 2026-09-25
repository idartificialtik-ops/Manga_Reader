package dev.idart.mangareader.sources.extension

import dev.idart.mangareader.source.RelatedMangas
import keiyoushi.source.KeiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import dev.idart.mangareader.source.CatalogueSource as DevCatalogueSource
import dev.idart.mangareader.source.model.Filter as DevFilter
import dev.idart.mangareader.source.model.FilterList as DevFilterList
import dev.idart.mangareader.source.model.MangasPage as DevMangasPage
import dev.idart.mangareader.source.model.Page as DevPage
import dev.idart.mangareader.source.model.SChapter as DevSChapter
import dev.idart.mangareader.source.model.SChapterImpl as DevSChapterImpl
import dev.idart.mangareader.source.model.SManga as DevSManga
import dev.idart.mangareader.source.model.SMangaImpl as DevSMangaImpl
import dev.idart.mangareader.source.model.UpdateStrategy as DevUpdateStrategy
import eu.kanade.tachiyomi.source.CatalogueSource as ExtCatalogueSource
import eu.kanade.tachiyomi.source.model.Filter as ExtFilter
import eu.kanade.tachiyomi.source.model.FilterList as ExtFilterList
import eu.kanade.tachiyomi.source.model.MangasPage as ExtMangasPage
import eu.kanade.tachiyomi.source.model.Page as ExtPage
import eu.kanade.tachiyomi.source.model.SChapter as ExtSChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl as ExtSChapterImpl
import eu.kanade.tachiyomi.source.model.SManga as ExtSManga
import eu.kanade.tachiyomi.source.model.SMangaImpl as ExtSMangaImpl
import eu.kanade.tachiyomi.source.model.SMangaUpdate as ExtSMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy as ExtUpdateStrategy

/**
 * Bridges an external extension source (`eu.kanade.tachiyomi` API, loaded from
 * an installed extension APK) to our suspend catalogue API.
 *
 * Blocking Observable calls run on [Dispatchers.IO] via `blockingFirst()`.
 */
class ExtensionSource(
    val extensionPackage: String,
    private val extSource: ExtCatalogueSource,
) : DevCatalogueSource {

    /**
     * Set once a legacy Observable call throws [AbstractMethodError]: the
     * source is 1.6-style (suspend API under renamed bases) and all further
     * calls go through [invokeSuspendMethod] directly.
     */
    private var legacyBroken = false

    private suspend fun <T> legacyOrReflective(
        legacy: suspend () -> T,
        reflective: suspend () -> T,
    ): T {
        if (!legacyBroken) {
            try {
                return legacy()
            } catch (e: AbstractMethodError) {
                legacyBroken = true
            }
        }
        return reflective()
    }

    private suspend fun reflectiveMangasPage(name: String, vararg args: Any?): DevMangasPage {
        val result = extSource.invokeSuspendMethod(name, *args)
            ?: throw IllegalStateException("$name returned null")
        return (result as ExtMangasPage).toDev()
    }

    override val id: Long = extSource.id

    override val name: String = extSource.name

    override val lang: String = extSource.lang

    override val supportsLatest: Boolean = extSource.supportsLatest

    override fun getFilterList(): DevFilterList {
        return extSource.getFilterList().toDev()
    }

    override suspend fun getPopularManga(page: Int): DevMangasPage = withContext(Dispatchers.IO) {
        (extSource as? KeiSource)?.getPopularManga(page)?.toDev()
            ?: legacyOrReflective(
                legacy = { extSource.fetchPopularManga(page).toBlocking().first().toDev() },
                reflective = { reflectiveMangasPage("getPopularManga", page) },
            )
    }

    override suspend fun getLatestUpdates(page: Int): DevMangasPage = withContext(Dispatchers.IO) {
        (extSource as? KeiSource)?.getLatestUpdates(page)?.toDev()
            ?: legacyOrReflective(
                legacy = { extSource.fetchLatestUpdates(page).toBlocking().first().toDev() },
                reflective = { reflectiveMangasPage("getLatestUpdates", page) },
            )
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: DevFilterList): DevMangasPage =
        withContext(Dispatchers.IO) {
            val extFilters = filters.toExt(extSource.getFilterList())
            val kei = extSource as? KeiSource
            if (kei != null) {
                kei.getSearchManga(page, query, extFilters).toDev()
            } else {
                legacyOrReflective(
                    legacy = { extSource.fetchSearchManga(page, query, extFilters).toBlocking().first().toDev() },
                    reflective = { reflectiveSearch(page, query, extFilters) },
                )
            }
        }

    override suspend fun getMangaDetails(manga: DevSManga): DevSManga = withContext(Dispatchers.IO) {
        val kei = extSource as? KeiSource
        if (kei != null) {
            kei.getMangaUpdate(manga.toExt(), emptyList(), fetchDetails = true, fetchChapters = true)
                .manga.toDev()
        } else {
            legacyOrReflective(
                legacy = { extSource.fetchMangaDetails(manga.toExt()).toBlocking().first().toDev() },
                reflective = {
                    reflectiveMangaUpdate(manga.toExt(), fetchDetails = true, fetchChapters = true)
                        .manga.toDev()
                },
            )
        }
    }

    override suspend fun getChapterList(manga: DevSManga): List<DevSChapter> = withContext(Dispatchers.IO) {
        val kei = extSource as? KeiSource
        if (kei != null) {
            kei.getMangaUpdate(manga.toExt(), emptyList(), fetchDetails = false, fetchChapters = true)
                .chapters.map { it.toDev() }
        } else {
            legacyOrReflective(
                legacy = { extSource.fetchChapterList(manga.toExt()).toBlocking().first().map { it.toDev() } },
                reflective = {
                    reflectiveMangaUpdate(manga.toExt(), fetchDetails = false, fetchChapters = true)
                        .chapters.map { it.toDev() }
                },
            )
        }
    }

    override suspend fun getPageList(chapter: DevSChapter): List<DevPage> = withContext(Dispatchers.IO) {
        (extSource as? KeiSource)?.getPageList(chapter.toExt())?.map { it.toDev() }
            ?: legacyOrReflective(
                legacy = { extSource.fetchPageList(chapter.toExt()).toBlocking().first().map { it.toDev() } },
                reflective = {
                    @Suppress("UNCHECKED_CAST")
                    (extSource.invokeSuspendMethod("getPageList", chapter.toExt()) as List<ExtPage>)
                        .map { it.toDev() }
                },
            )
    }

    private suspend fun reflectiveSearch(page: Int, query: String, filters: ExtFilterList): DevMangasPage {
        val result = runCatching {
            extSource.invokeSuspendMethod("getSearchManga", page, query, filters)
        }.recoverCatching { firstError ->
            if (firstError is NoSuchMethodException) {
                extSource.invokeSuspendMethod("getSearchMangaList", page, query, filters)
            } else {
                throw firstError
            }
        }.getOrThrow() ?: throw IllegalStateException("search returned null")
        return (result as ExtMangasPage).toDev()
    }

    private suspend fun reflectiveMangaUpdate(
        manga: ExtSManga,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): ExtSMangaUpdate {
        val result = extSource.invokeSuspendMethod(
            "getMangaUpdate",
            manga,
            emptyList<ExtSChapter>(),
            fetchDetails,
            fetchChapters,
        ) ?: throw IllegalStateException("getMangaUpdate returned null")
        return result as ExtSMangaUpdate
    }

    override fun getMangaUrl(manga: DevSManga): String {
        return try {
            (extSource as? eu.kanade.tachiyomi.source.online.HttpSource)?.getMangaUrl(manga.toExt()) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Exposes the extension's own OkHttp client (with its interceptors) so the
     * downloader uses the same stack as the extension's requests.
     *
     * Resolved lazily and safely: a single exotic extension must never break
     * the catalogue scan (its client check may reject the default stack).
     */
    val client: okhttp3.OkHttpClient?
        get() = runCatching {
            (extSource as? eu.kanade.tachiyomi.source.online.HttpSource)?.client
        }.getOrNull()

    /**
     * Headers the extension itself uses (User-Agent, site defaults). Used for
     * cover loads so image CDNs that gate on headers keep working.
     */
    fun coverHeaders(): okhttp3.Headers? {
        return runCatching {
            (extSource as? eu.kanade.tachiyomi.source.online.HttpSource)?.headers
        }.getOrNull()
    }

    /**
     * Delegates to the extension's `imageRequest` (which adds per-image headers
     * like `Referer`), falling back to a plain GET with the extension headers.
     */
    fun imageRequest(page: DevPage): okhttp3.Request {
        val http = extSource as? eu.kanade.tachiyomi.source.online.HttpSource
        if (http != null) {
            try {
                return http.imageRequest(page.toExt())
            } catch (_: Exception) {
                // Fall through to the plain GET below.
            }
        }
        val url = page.imageUrl ?: throw IllegalStateException("Page ${page.index} has no image url")
        return okhttp3.Request.Builder().url(url).build()
    }

    override suspend fun getRelatedMangas(manga: DevSManga): List<DevSManga> = withContext(Dispatchers.IO) {
        try {
            if (extSource.supportsRelatedMangas) {
                extSource.fetchRelatedMangaList(manga.toExt())
                    .map { it.toDev() }
                    .filter { it.url != manga.url }
            } else {
                RelatedMangas.bySearch(this@ExtensionSource, manga)
            }
        } catch (_: Exception) {
            RelatedMangas.bySearch(this@ExtensionSource, manga)
        }
    }
}

fun ExtSManga.toDev(): DevSManga {
    return DevSMangaImpl().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
        it.artist = artist
        it.author = author
        it.status = status
        it.description = description
        it.genre = genre
        it.update_strategy = DevUpdateStrategy.valueOf(update_strategy.name)
        it.initialized = initialized
        it.memo = buildJsonObject { }
    }
}

fun DevSManga.toExt(): ExtSManga {
    return ExtSMangaImpl().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail_url
        it.artist = artist
        it.author = author
        it.status = status
        it.description = description
        it.genre = genre
        it.update_strategy = ExtUpdateStrategy.valueOf(update_strategy.name)
        it.initialized = initialized
    }
}

fun ExtSChapter.toDev(): DevSChapter {
    return DevSChapterImpl().also {
        it.url = url
        it.name = name
        it.date_upload = date_upload
        it.chapter_number = chapter_number
        it.scanlator = scanlator
    }
}

fun DevSChapter.toExt(): ExtSChapter {
    return ExtSChapterImpl().also {
        it.url = url
        it.name = name
        it.date_upload = date_upload
        it.chapter_number = chapter_number
        it.scanlator = scanlator
    }
}

fun ExtPage.toDev(): DevPage {
    return DevPage(index, url, imageUrl)
}

fun DevPage.toExt(): ExtPage {
    return ExtPage(index, url, imageUrl)
}

fun ExtMangasPage.toDev(): DevMangasPage {
    return DevMangasPage(mangas.map { it.toDev() }, hasNextPage)
}

fun ExtFilterList.toDev(): DevFilterList {
    return DevFilterList(list.map { it.toDev() })
}

@Suppress("UNCHECKED_CAST")
fun ExtFilter<*>.toDev(): DevFilter<*> {
    return when (this) {
        is ExtFilter.Header -> DevFilter.Header(name)
        is ExtFilter.Separator -> DevFilter.Separator(name)
        is ExtFilter.Select<*> -> DevFilter.Select(
            name,
            (values as Array<*>).map { it }.toTypedArray(),
            state,
        )
        is ExtFilter.Text -> DevFilter.Text(name, state)
        is ExtFilter.CheckBox -> DevFilter.CheckBox(name, state)
        is ExtFilter.TriState -> DevFilter.TriState(name, state)
        is ExtFilter.Group<*> -> DevFilter.Group(name, (state as List<*>).toList())
        is ExtFilter.Sort -> DevFilter.Sort(
            name,
            values.map { it }.toTypedArray(),
            state?.let { DevFilter.Sort.Selection(it.index, it.ascending) },
        )
        else -> DevFilter.Header(name)
    }
}

/**
 * Converts our (possibly user-modified) filters back to the extension's own
 * filter instances, matched by name and kind against a fresh template list.
 */
fun DevFilterList.toExt(template: ExtFilterList): ExtFilterList {
    val matched = template.map { ext ->
        val ours = find { it.name == ext.name && it::class == ext.toDev()::class } as DevFilter<*>?
        if (ours != null) {
            applyState(ext, ours)
        }
        ext
    }
    return ExtFilterList(matched)
}

@Suppress("UNCHECKED_CAST")
private fun applyState(ext: ExtFilter<*>, ours: DevFilter<*>) {
    try {
        when {
            ext is ExtFilter.Select<*> && ours is DevFilter.Select<*> -> ext.state = ours.state
            ext is ExtFilter.Text && ours is DevFilter.Text -> ext.state = ours.state
            ext is ExtFilter.CheckBox && ours is DevFilter.CheckBox -> ext.state = ours.state
            ext is ExtFilter.TriState && ours is DevFilter.TriState -> ext.state = ours.state
            ext is ExtFilter.Group<*> && ours is DevFilter.Group<*> ->
                (ext as ExtFilter.Group<Any?>).state = (ours as DevFilter.Group<Any?>).state
            ext is ExtFilter.Sort && ours is DevFilter.Sort ->
                ext.state = ours.state?.let { ExtFilter.Sort.Selection(it.index, it.ascending) }
        }
    } catch (_: Exception) {
        // Best effort: keep the extension default on type mismatches.
    }
}
