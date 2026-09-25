package dev.idart.mangareader

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.idart.mangareader.core.common.network.NetworkHelper
import dev.idart.mangareader.di.AppModule
import dev.idart.mangareader.di.DatabaseModule
import dev.idart.mangareader.di.DownloadModule
import dev.idart.mangareader.di.SourceModule
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.sources.SourceRefererInterceptor
import dev.idart.mangareader.sources.extension.ExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaReaderApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashReporter.install(this)

        Injekt.importModule(DatabaseModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(SourceModule(this))
        Injekt.importModule(DownloadModule(this))

        appScope.launch(Dispatchers.IO) {
            runCatching { Injekt.get<SourceManager>().refresh() }
            runCatching { Injekt.get<ExtensionManager>().refresh() }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val network = Injekt.get<NetworkHelper>()
        val sourceManager = Injekt.get<SourceManager>()
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            network.client.newBuilder()
                                .addInterceptor(
                                    SourceRefererInterceptor { sourceManager.getCatalogueSources() },
                                )
                                .build()
                        },
                    ),
                )
            }
            .build()
    }

    companion object {
        lateinit var instance: MangaReaderApp
            private set
    }
}
