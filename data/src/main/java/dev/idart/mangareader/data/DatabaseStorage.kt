package dev.idart.mangareader.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import db.Chapters
import db.History
import db.Mangas
import dev.idart.mangareader.data.db.MangaReaderDatabase

object DatabaseStorage {
    const val DATABASE_NAME = "mangareader.db"

    @Volatile
    private var instance: MangaReaderDatabase? = null

    fun instance(context: Context): MangaReaderDatabase {
        return instance ?: synchronized(this) {
            instance ?: create(
                AndroidSqliteDriver(MangaReaderDatabase.Schema, context, DATABASE_NAME),
            ).also { instance = it }
        }
    }

    fun create(driver: SqlDriver): MangaReaderDatabase {
        return MangaReaderDatabase(
            driver = driver,
            chaptersAdapter =
            Chapters.Adapter(
                memoAdapter = MemoColumnAdapter,
            ),
            historyAdapter =
            History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter =
            Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }
}
