package dev.idart.mangareader.di

import android.app.Application
import dev.idart.mangareader.data.category.CategoryRepositoryImpl
import dev.idart.mangareader.data.chapter.ChapterRepositoryImpl
import dev.idart.mangareader.data.history.HistoryRepositoryImpl
import dev.idart.mangareader.data.manga.MangaRepositoryImpl
import dev.idart.mangareader.data.source.SourceRepositoryImpl
import dev.idart.mangareader.domain.category.repository.CategoryRepository
import dev.idart.mangareader.domain.chapter.interactor.GetChaptersByMangaId
import dev.idart.mangareader.domain.chapter.interactor.GetPageList
import dev.idart.mangareader.domain.chapter.interactor.UpdateChapterProgress
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository
import dev.idart.mangareader.domain.history.interactor.UpsertHistory
import dev.idart.mangareader.domain.history.repository.HistoryRepository
import dev.idart.mangareader.domain.manga.interactor.DeleteMangaById
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.interactor.FetchMangaDetails
import dev.idart.mangareader.domain.manga.interactor.GetFavorites
import dev.idart.mangareader.domain.manga.interactor.GetLibraryMangas
import dev.idart.mangareader.domain.manga.interactor.GetMangaById
import dev.idart.mangareader.domain.manga.interactor.GetMangaByUrlAndSource
import dev.idart.mangareader.domain.manga.interactor.GetRelatedMangas
import dev.idart.mangareader.domain.manga.interactor.InsertFavoriteEntries
import dev.idart.mangareader.domain.manga.interactor.UpdateMangaDetails
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.repository.SourceRepository
import dev.idart.mangareader.ocronnx.OcrModelStore
import dev.idart.mangareader.ocronnx.OnnxMangaOcr
import dev.idart.mangareader.translate.PageTranslator
import dev.idart.mangareader.translate.TranslatorSettings
import dev.idart.mangareader.ui.ai.AgentTools
import dev.idart.mangareader.ui.ai.AiClient
import dev.idart.mangareader.ui.ai.AiSettings
import dev.idart.mangareader.ui.ai.ChatHistoryStore
import dev.idart.mangareader.ui.browse.BrowseFilterRepository
import dev.idart.mangareader.ui.feed.ExaClient
import dev.idart.mangareader.ui.feed.ExaSettings
import dev.idart.mangareader.ui.feed.FeedRepository
import dev.idart.mangareader.ui.feed.FeedUpdater
import dev.idart.mangareader.ui.theme.ThemeRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AppModule(val app: Application) : InjektModule {
    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<MangaRepository> { MangaRepositoryImpl(get(), get(), get(), get()) }
        addSingletonFactory<ChapterRepository> { ChapterRepositoryImpl(get()) }
        addSingletonFactory<HistoryRepository> { HistoryRepositoryImpl(get()) }
        addSingletonFactory<SourceRepository> { SourceRepositoryImpl(get()) }
        addSingletonFactory<CategoryRepository> { CategoryRepositoryImpl(get()) }

        addSingletonFactory { GetMangaById(get()) }
        addSingletonFactory { GetMangaByUrlAndSource(get()) }
        addSingletonFactory { GetFavorites(get()) }
        addSingletonFactory { GetLibraryMangas(get()) }
        addSingletonFactory { InsertFavoriteEntries(get()) }
        addSingletonFactory { UpdateMangaDetails(get()) }
        addSingletonFactory { DeleteMangaById(get()) }
        addSingletonFactory { GetChaptersByMangaId(get()) }
        addSingletonFactory { GetPageList(get(), get(), get()) }
        addSingletonFactory { UpdateChapterProgress(get()) }
        addSingletonFactory { UpsertHistory(get()) }
        addSingletonFactory { BrowseSourceCatalogue(get()) }
        addSingletonFactory { FetchMangaDetails(get(), get(), get()) }
        addSingletonFactory { EnsureMangaInDb(get()) }
        addSingletonFactory { GetRelatedMangas(get(), get()) }
        addSingletonFactory { ThemeRepository(app) }
        addSingletonFactory { BrowseFilterRepository(app) }
        addSingletonFactory { OcrModelStore(app) }
        addSingletonFactory { OnnxMangaOcr(get()) }
        addSingletonFactory { AiSettings(app) }
        addSingletonFactory { AiClient() }
        addSingletonFactory { ChatHistoryStore(app) }
        addSingletonFactory { TranslatorSettings(app) }
        addSingletonFactory { ExaSettings(app) }
        addSingletonFactory { ExaClient() }
        addSingletonFactory { FeedRepository(app) }
        addSingletonFactory {
            FeedUpdater(get(), get(), get(), get(), get(), get(), get(), get())
        }
        addSingletonFactory { AgentTools(get(), get(), get(), get(), get()) }
        addSingletonFactory { PageTranslator(app, get(), get(), get(), get(), get()) }
    }
}
