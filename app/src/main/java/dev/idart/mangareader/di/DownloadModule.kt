package dev.idart.mangareader.di

import android.app.Application
import androidx.work.WorkManager
import dev.idart.mangareader.sources.download.DownloadManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DownloadModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { WorkManager.getInstance(app) }

        addSingletonFactory {
            DownloadManager(
                context = app,
                workManager = get(),
                sourceManager = get(),
                mangaRepository = get(),
                chapterRepository = get(),
            )
        }
    }
}
