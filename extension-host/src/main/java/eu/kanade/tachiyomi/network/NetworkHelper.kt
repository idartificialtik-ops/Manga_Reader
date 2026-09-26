package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import keiyoushi.network.UncaughtExceptionInterceptor
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Real host implementation of the network service exposed to extensions.
 *
 * Shape mirrors extensions-lib 1.x (`NetworkHelper(context)` + `client`).
 * Cloudflare challenges are solved headlessly by [CloudflareInterceptor], or
 * by hand in the in-app WebView — the cookies from that are copied into
 * [cookieJar], which is persistent on purpose.
 */
open class NetworkHelper(
    private val context: Context,
    baseClient: OkHttpClient? = null,
) {
    init {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    /**
     * Typed as the concrete jar (not `CookieJar`) so the app can import
     * cookies solved by hand in the WebView: those live in the system
     * [android.webkit.CookieManager], which OkHttp never reads.
     */
    open val cookieJar: PersistentCookieJar = PersistentCookieJar(context)

    open val client: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .cookieJar(cookieJar)
        // Required by keiyoushi-built extensions: they check that the default
        // client contains UncaughtExceptionInterceptor + CloudflareInterceptor.
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(CloudflareInterceptor(context))
        .addInterceptor(
            HttpLoggingInterceptor { Log.d("OkHttp", it) }
                .setLevel(HttpLoggingInterceptor.Level.BASIC),
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    @Deprecated("Same client: challenges are solved by the interceptor on the regular client")
    open val cloudflareClient: OkHttpClient
        get() = client
}
