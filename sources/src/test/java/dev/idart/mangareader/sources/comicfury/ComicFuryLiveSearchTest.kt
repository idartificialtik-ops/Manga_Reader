package dev.idart.mangareader.sources.comicfury

import dev.idart.mangareader.source.model.FilterList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Test

class ComicFuryLiveSearchTest {

    @Test
    fun liveSearchReturns() {
        val source = ComicFury(OkHttpClient())
        val page = runBlocking {
            withTimeout(60_000) {
                source.getSearchManga(1, "hero", FilterList())
            }
        }
        println("RESULTS count=${page.mangas.size} hasNext=${page.hasNextPage} first=${page.mangas.firstOrNull()?.title}")
    }

    @Test
    fun livePopularReturns() {
        val source = ComicFury(OkHttpClient())
        val page = runBlocking {
            withTimeout(60_000) {
                source.getPopularManga(1)
            }
        }
        println("POPULAR count=${page.mangas.size} hasNext=${page.hasNextPage}")
    }
}
