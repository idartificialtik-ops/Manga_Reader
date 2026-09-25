package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Host stub of the marker interceptor keiyoushi [KeiSource] extensions
 * require in the default client ("CloudflareInterceptor must be present in
 * default client").
 *
 * WebView-based Cloudflare challenge solving is not supported by this host:
 * this is a pass-through, so Cloudflare-protected sites fail with a visible
 * HTTP error instead of crashing source init.
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
