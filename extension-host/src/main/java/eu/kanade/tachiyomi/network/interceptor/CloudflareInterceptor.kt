package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Host implementation of the marker interceptor keiyoushi [KeiSource]
 * extensions require in the default client ("CloudflareInterceptor must be
 * present in default client") — and which actually solves the challenge.
 *
 * Cloudflare answers `403 Forbidden` (sometimes `503`) to anything that does
 * not look like a browser. The fix is to run the challenge in a real browser
 * engine: an offscreen [WebView] loads the URL, the JS challenge runs, the
 * clearance cookie lands in the system cookie store, and the request is
 * replayed with those cookies in the `Cookie` header.
 *
 * Why the header is rebuilt by hand: the OkHttp client keeps its cookies in
 * its own in-memory jar, which never sees what the WebView stored — the
 * system [CookieManager] and the client are different stores, so the
 * harvested cookies have to be copied into the request.
 *
 * One challenge at a time ([mutex]): a WebView per in-flight request would be
 * a main-thread and memory hazard.
 *
 * This gets past *bot challenges*, not *IP reputation blocks*: a VPN or a
 * datacenter range Cloudflare refuses outright still gets 403.
 */
class CloudflareInterceptor(
    context: Context,
) : Interceptor {

    private val appContext = context.applicationContext

    /**
     * Lazy on purpose: reading [CookieManager] loads the WebView provider. A
     * device with no usable WebView must fail here — inside the challenge
     * attempt — not at startup, and never on a request that was fine.
     */
    private val cookieManager: CookieManager by lazy { CookieManager.getInstance() }

    private val mutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code !in CHALLENGE_CODES) return response

        val cookies = runCatching {
            runBlocking { mutex.withLock { solveChallenge(request) } }
        }.onFailure { error ->
            Log.w(TAG, "WebView indisponível em ${request.url.host}: ${error::class.java.simpleName}")
        }.getOrNull()
        if (cookies.isNullOrBlank()) {
            Log.w(TAG, "desafio não resolvido em ${request.url.host} (HTTP ${response.code})")
            return response
        }

        val retried = runCatching {
            chain.proceed(
                request.newBuilder()
                    .removeHeader("Cookie")
                    .header("Cookie", cookies)
                    .build(),
            )
        }.getOrNull()
        // The original 403 body stays open when the replay also failed: that
        // body is what the source parser reports back to the user.
        if (retried == null) return response
        if (retried.code in CHALLENGE_CODES) {
            retried.close()
            return response
        }
        response.close()
        Log.i(TAG, "desafio resolvido em ${request.url.host} (HTTP ${retried.code})")
        return retried
    }

    /**
     * Runs the challenge offscreen and returns the cookie header to replay the
     * request with, or null when it could not be solved within the timeout.
     */
    private suspend fun solveChallenge(request: Request): String? {
        val url = request.url.toString()
        val userAgent = request.header("User-Agent") ?: DEFAULT_USER_AGENT
        cookieManager.setAcceptCookie(true)
        val cookies = withTimeoutOrNull(TIMEOUT_MS) { loadInWebView(url, userAgent) }
        return cookies?.takeIf { it.isNotBlank() }?.let { mergeCookies(request.header("Cookie"), it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadInWebView(url: String, userAgent: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var settled = false

                // Main thread only: a WebView may not be touched from the
                // OkHttp thread (including from the cancellation handler, which
                // runs on whichever thread cancelled the continuation).
                fun finish(result: String?) {
                    if (settled) return
                    settled = true
                    handler.removeCallbacksAndMessages(null)
                    runCatching {
                        webView?.let { view ->
                            view.stopLoading()
                            view.destroy()
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "cleanup do WebView: ${error::class.java.simpleName}")
                    }
                    if (cont.isActive) cont.resume(result) { }
                }

                // The challenge can set its cookie without ever finishing a
                // page load (JS redirect), so poll instead of trusting
                // onPageFinished alone.
                val poll = object : Runnable {
                    override fun run() {
                        val cookies = cookieManager.getCookie(url)
                        if (!cookies.isNullOrBlank()) {
                            finish(cookies)
                        } else {
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        }
                    }
                }

                runCatching {
                    webView = WebView(appContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = userAgent
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                val cookies = cookieManager.getCookie(url)
                                // No cookie at all: the challenge is not
                                // solvable here (login wall, geo block…).
                                if (!cookies.isNullOrBlank()) finish(cookies)
                            }
                        }
                    }
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                    webView?.loadUrl(url)
                    handler.postDelayed(poll, POLL_INTERVAL_MS)
                }.onFailure { error ->
                    Log.w(TAG, "WebView indisponível: ${error::class.java.simpleName}: ${error.message}")
                    finish(null)
                }

                cont.invokeOnCancellation {
                    // Hop to the main thread: cancelling happens on the
                    // timeout's coroutine, not here.
                    handler.post { finish(null) }
                }
            }
        }

    /**
     * Joins the cookies the WebView stored with the ones the request already
     * carried — dropping a session cookie would break sources that log in.
     */
    private fun mergeCookies(requestCookie: String?, webViewCookie: String): String {
        val merged = LinkedHashMap<String, String>()
        fun put(header: String?) {
            header?.split(';')?.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) merged[name] = value
            }
        }
        put(requestCookie)
        // WebView last: it is the fresher state.
        put(webViewCookie)
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        private const val TAG = "CloudflareCF"

        private val CHALLENGE_CODES = setOf(403, 503)

        private const val POLL_INTERVAL_MS = 500L

        /** Generous: slow devices plus a multi-step JS challenge. */
        private val TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20)

        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
