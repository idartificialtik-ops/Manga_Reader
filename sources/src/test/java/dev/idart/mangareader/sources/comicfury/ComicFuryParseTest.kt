package dev.idart.mangareader.sources.comicfury

import dev.idart.mangareader.domain.source.model.StoredSource
import dev.idart.mangareader.domain.source.repository.SourceRepository
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.FilterList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComicFuryParseTest {
    private lateinit var source: TestableComicFury

    @Before
    fun setup() {
        source = TestableComicFury(OkHttpClient())
    }

    @Test
    fun `search request builds comicfury url`() {
        val request = source.buildSearch(page = 2, query = "hero", filters = FilterList(TagsFilter().apply { state = "action, drama" }))
        val url = request.url.toString()
        assertTrue(url.startsWith("https://comicfury.com/search.php?"))
        assertTrue(url.contains("query=hero"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("tags=action%2Cdrama") || url.contains("tags=action,drama"))
    }

    @Test
    fun `search parse extracts results and next page`() {
        val html =
            """
            <html><body>
              <div class="webcomic-result">
                <div class="webcomic-result-avatar"><a href="https://comicfury.com/read/hero/"><img src="https://comicfury.com/thumb/hero.png"/></a></div>
                <div class="webcomic-result-title" title="Hero Comic"></div>
              </div>
              <div class="webcomic-result">
                <div class="webcomic-result-avatar"><a href="https://comicfury.com/read/villain/"><img src="https://comicfury.com/thumb/villain.png"/></a></div>
                <div class="webcomic-result-title" title="Villain Comic"></div>
              </div>
              <div class="search-next-page"><a href="?page=2">Next</a></div>
            </body></html>
            """.trimIndent()

        val page = source.parseSearch(htmlResponse("https://comicfury.com/search.php?query=hero", html))
        assertEquals(2, page.mangas.size)
        assertEquals("Hero Comic", page.mangas[0].title)
        assertEquals("https://comicfury.com/read/hero/", page.mangas[0].url)
        assertEquals("https://comicfury.com/thumb/hero.png", page.mangas[0].thumbnail_url)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `search parse without next page marker`() {
        val html =
            """
            <html><body>
              <div class="webcomic-result">
                <div class="webcomic-result-avatar"><a href="https://comicfury.com/read/solo/"><img src="https://comicfury.com/thumb/solo.png"/></a></div>
                <div class="webcomic-result-title" title="Solo"></div>
              </div>
            </body></html>
            """.trimIndent()

        val page = source.parseSearch(htmlResponse("https://comicfury.com/search.php", html))
        assertEquals(1, page.mangas.size)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `details parse extracts description genre author`() {
        val html =
            """
            <html><body>An epic hero story.
              <div class="description-tags"><span>Action</span><span>Adventure</span></div>
              <a class="authorname">Jane Doe</a>
            </body></html>
            """.trimIndent()

        val manga = source.parseDetails(htmlResponse("https://comicfury.com/read/hero/archive", html))
        assertEquals("An epic hero story.", manga.description)
        assertEquals("Action, Adventure", manga.genre)
        assertEquals("Jane Doe", manga.author)
        assertEquals("/read/hero/archive", manga.url)
        assertTrue(manga.initialized)
    }

    @Test
    fun `chapter list parse extracts flat archive`() {
        val html =
            """
            <html><body>
              <a href="https://comicfury.com/read/hero/1/"><div class="archive-comic"><span class="archive-comic-title">Chapter One</span><span class="archive-comic-date">Jan 05 2024</span></div></a>
              <a href="https://comicfury.com/read/hero/2/"><div class="archive-comic"><span class="archive-comic-title">Chapter Two</span><span class="archive-comic-date">4th Feb 2024</span></div></a>
            </body></html>
            """.trimIndent()

        val chapters = source.parseChapters(htmlResponse("https://comicfury.com/read/hero/archive", html))
        assertEquals(2, chapters.size)
        // Newest first after the reversed() call.
        assertEquals("Chapter Two", chapters[0].name)
        assertEquals("Chapter One", chapters[1].name)
        assertEquals("/read/hero/1/", chapters[1].url)
        assertTrue(chapters.all { it.date_upload > 0L })
    }

    @Test
    fun `page list parse extracts infinite scroll images`() {
        val html =
            """
            <html><body>
              <div class="is--comic-page">
                <div class="is--image-segment"><div><img src="https://cdn.example.com/p1.png"/></div></div>
                <div class="is--image-segment"><div><img src="https://cdn.example.com/p2.png"/></div></div>
              </div>
            </body></html>
            """.trimIndent()

        val pages = source.parsePages(htmlResponse("https://comicfury.com/read/hero/1/", html))
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].index)
        assertEquals("https://cdn.example.com/p1.png", pages[0].imageUrl)
        assertEquals("https://cdn.example.com/p2.png", pages[1].imageUrl)
    }

    @Test
    fun `manga url points to archive page`() {
        val manga =
            dev.idart.mangareader.source.model.SManga.create().apply {
                url = "/read/hero/"
            }
        assertEquals(
            "https://comicfury.com/read//read/hero//archive",
            source.mangaUrl(manga),
        )
    }

    @Test
    fun `source manager registers built-in source and stubs unknown`() =
        runBlocking {
            val repository = FakeSourceRepository()
            val manager = SourceManager(listOf(source), repository)

            manager.refresh()

            assertEquals(1, repository.stored.size)
            assertEquals(ComicFury.COMIC_FURY_SOURCE_ID, repository.stored.first().id)
            assertEquals("Comic Fury", repository.stored.first().name)
            assertEquals(source, manager.getSource(ComicFury.COMIC_FURY_SOURCE_ID))
            assertTrue(manager.getOrStub(424242L).name.contains("424242"))
        }

    private fun htmlResponse(
        url: String,
        html: String,
    ): Response {
        val request = Request.Builder().url(url).build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private class TestableComicFury(client: OkHttpClient) : ComicFury(client) {
        fun buildSearch(
            page: Int,
            query: String,
            filters: FilterList,
        ) = searchMangaRequest(page, query, filters)

        fun parseSearch(response: Response) = searchMangaParse(response)

        fun parseDetails(response: Response) = mangaDetailsParse(response)

        fun parseChapters(response: Response) = chapterListParse(response)

        fun parsePages(response: Response) = pageListParse(response)

        fun mangaUrl(manga: dev.idart.mangareader.source.model.SManga) = getMangaUrl(manga)
    }

    private class FakeSourceRepository : SourceRepository {
        val stored = mutableListOf<StoredSource>()

        override suspend fun getAll(): List<StoredSource> = stored.toList()

        override fun getAllAsFlow(): Flow<List<StoredSource>> = flowOf(stored.toList())

        override suspend fun getById(id: Long): StoredSource? = stored.find { it.id == id }

        override suspend fun upsert(source: StoredSource) {
            stored.removeAll { it.id == source.id }
            stored.add(source)
        }
    }
}
