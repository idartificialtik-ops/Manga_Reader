package dev.idart.mangareader.core.common.network

import keiyoushi.network.UserAgentInterceptor
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * Provides the shared [OkHttpClient] used by all sources.
 *
 * Installs the Conscrypt security provider (same as the reference app) so TLS
 * works consistently across devices, old and new.
 */
class NetworkHelper(
    userAgent: String = DEFAULT_USER_AGENT,
    enableLogging: Boolean = false,
) {
    init {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    private val defaultHeaders =
        okhttp3.Headers.Builder()
            .add("User-Agent", userAgent)
            .build()

    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .apply {
                if (enableLogging) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .addInterceptor(UserAgentInterceptor(userAgent))
            .build()

    fun defaultHeaders(): okhttp3.Headers = defaultHeaders
}
