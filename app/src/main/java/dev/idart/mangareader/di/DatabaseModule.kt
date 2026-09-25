package dev.idart.mangareader.di

import android.app.Application
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.idart.mangareader.data.DatabaseHandler
import dev.idart.mangareader.data.DatabaseHandlerImpl
import dev.idart.mangareader.data.DatabaseStorage
import dev.idart.mangareader.data.category.CategoryDataStore
import dev.idart.mangareader.data.chapter.ChapterDataStore
import dev.idart.mangareader.data.db.MangaReaderDatabase
import dev.idart.mangareader.data.history.HistoryDataStore
import dev.idart.mangareader.data.manga.MangaDataStore
import dev.idart.mangareader.data.source.SourceDataStore
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DatabaseModule(val app: Application) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<SqlDriver> {
            AndroidSqliteDriver(MangaReaderDatabase.Schema, app, DatabaseStorage.DATABASE_NAME)
        }

        addSingletonFactory {
            DatabaseStorage.create(get())
        }

        addSingletonFactory<DatabaseHandler> {
            DatabaseHandlerImpl(get(), get())
        }

        addSingletonFactory { MangaDataStore(get()) }
        addSingletonFactory { ChapterDataStore(get()) }
        addSingletonFactory { HistoryDataStore(get()) }
        addSingletonFactory { SourceDataStore(get()) }
        addSingletonFactory { CategoryDataStore(get()) }
    }
}
