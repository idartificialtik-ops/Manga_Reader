package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import keiyoushi.network.UncaughtExceptionInterceptor
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Real host implementation of the network service exposed to extensions.
 *
 * Shape mirrors extensions-lib 1.x (`NetworkHelper(context)` + `client`).
 * Cloudflare bypass (WebView based) is intentionally not supported.
 */
open class NetworkHelper(
    @Suppress("unused") private val context: Context,
    baseClient: OkHttpClient? = null,
) {
    init {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    open val cookieJar: CookieJar = InMemoryCookieJar()

    open val client: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .cookieJar(cookieJar)
        // Required by keiyoushi-built extensions: they check that the default
        // client contains UncaughtExceptionInterceptor + CloudflareInterceptor.
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor(
            HttpLoggingInterceptor { Log.d("OkHttp", it) }
                .setLevel(HttpLoggingInterceptor.Level.BASIC),
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    @Deprecated("The regular client is used (no Cloudflare bypass in this host)")
    open val cloudflareClient: OkHttpClient
        get() = client
}
