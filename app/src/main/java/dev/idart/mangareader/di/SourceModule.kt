package dev.idart.mangareader.di

import android.app.Application
import dev.idart.mangareader.core.common.network.NetworkHelper
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.sources.comicfury.ComicFury
import dev.idart.mangareader.sources.extension.ExtensionManager
import dev.idart.mangareader.sources.extension.repo.ExtensionInstaller
import dev.idart.mangareader.sources.extension.repo.ExtensionRepo
import dev.idart.mangareader.sources.extension.repo.ExtensionRepoStore
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.network.NetworkHelper as ExtNetworkHelper

class SourceModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)

        // TODO: gate HTTP logging on BuildConfig.DEBUG before release builds.
        addSingletonFactory { NetworkHelper(enableLogging = true) }

        addSingletonFactory { ComicFury(get<NetworkHelper>().client) }

        addSingletonFactory {
            SourceManager(
                builtInSources = listOf<CatalogueSource>(get<ComicFury>()),
                sourceRepository = get(),
            )
        }

        // Host-side network service for external extensions (eu.kanade API).
        addSingletonFactory<ExtNetworkHelper> {
            ExtNetworkHelper(app, get<NetworkHelper>().client)
        }

        // Shared kotlinx.serialization Json (extensions resolve it via Injekt).
        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }

        addSingletonFactory { ExtensionManager(app, get()) }

        // Keiyoushi extension repository catalog + APK installer.
        addSingletonFactory { ExtensionRepo(app, get<NetworkHelper>().client) }
        addSingletonFactory { ExtensionRepoStore(app) }
        addSingletonFactory { ExtensionInstaller(app, get<NetworkHelper>().client) }
    }
}
