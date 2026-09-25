package dev.idart.mangareader.sources

import dev.idart.mangareader.source.CatalogueSource
import dev.idart.mangareader.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds a `Referer` header to image requests whose host belongs to a known
 * catalogue source, using the source base URL as value. Many sites reject
 * hotlinked images without a same-site referer.
 */
class SourceRefererInterceptor(
    private val catalogueSources: () -> List<CatalogueSource>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Referer") != null) {
            return chain.proceed(request)
        }
        val referer = catalogueSources()
            .mapNotNull { (it as? HttpSource)?.baseUrl }
            .firstOrNull { baseUrl ->
                baseUrl.toHttpUrlOrNull()?.host == request.url.host
            }
            ?.let { "$it/" }
            ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Referer", referer).build(),
        )
    }
}
