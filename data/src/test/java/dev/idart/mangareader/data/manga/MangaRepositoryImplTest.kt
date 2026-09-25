package dev.idart.mangareader.data.manga

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.idart.mangareader.data.DatabaseHandlerImpl
import dev.idart.mangareader.data.DatabaseStorage
import dev.idart.mangareader.data.category.CategoryDataStore
import dev.idart.mangareader.data.chapter.ChapterDataStore
import dev.idart.mangareader.data.db.MangaReaderDatabase
import dev.idart.mangareader.data.history.HistoryDataStore
import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class MangaRepositoryImplTest {
    private lateinit var driver: SqlDriver
    private lateinit var mangaRepository: MangaRepository
    private lateinit var chapterDataStore: ChapterDataStore
    private lateinit var historyDataStore: HistoryDataStore

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaReaderDatabase.Schema.create(driver)

        val database = DatabaseStorage.create(driver)
        val handler = DatabaseHandlerImpl(database, driver)
        val mangaDataStore = MangaDataStore(handler)
        chapterDataStore = ChapterDataStore(handler)
        historyDataStore = HistoryDataStore(handler)
        val categoryDataStore = CategoryDataStore(handler)
        mangaRepository =
            MangaRepositoryImpl(
                mangaDataStore,
                chapterDataStore,
                historyDataStore,
                categoryDataStore,
            )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `insert network manga and get by id`() =
        runBlocking {
            val manga =
                Manga.create(source = 1L).copy(
                    url = "/manga/test-1",
                    title = "Test Manga",
                    author = "Author",
                )

            val inserted = mangaRepository.insertNetworkManga(listOf(manga))
            assertEquals(1, inserted.size)

            val fetched = mangaRepository.getMangaById(inserted.first().id)
            assertEquals("Test Manga", fetched.title)
            assertEquals("/manga/test-1", fetched.url)
            assertEquals(1L, fetched.source)
            assertEquals("Author", fetched.author)
        }

    @Test
    fun `update favorite flag and list favorites`() =
        runBlocking {
            val inserted =
                mangaRepository.insertNetworkManga(
                    listOf(Manga.create(source = 1L).copy(url = "/manga/fav", title = "Fav")),
                )
            val id = inserted.first().id

            assertTrue(mangaRepository.update(MangaUpdate(id = id, favorite = true)))

            val favorites = mangaRepository.getFavorites()
            assertEquals(1, favorites.size)
            assertEquals(id, favorites.first().id)
            assertNotNull(favorites.first().favoriteModifiedAt)
        }

    @Test
    fun `chapter insert and history upsert accumulate time`() =
        runBlocking {
            val mangaId =
                mangaRepository.insertNetworkManga(
                    listOf(Manga.create(source = 1L).copy(url = "/manga/ch", title = "Ch")),
                ).first().id

            val chapterId =
                chapterDataStore.insert(
                    Chapter.create(mangaId = mangaId).copy(url = "/ch/1", name = "Chapter 1"),
                )

            historyDataStore.upsert(chapterId, Date(1000L), 60L)
            historyDataStore.upsert(chapterId, Date(2000L), 30L)

            val history = historyDataStore.getByMangaId(mangaId)
            assertEquals(1, history.size)
            assertEquals(90L, history.first().time_read)
            assertEquals(Date(2000L), history.first().last_read)
        }
}
