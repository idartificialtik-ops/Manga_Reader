package dev.idart.mangareader.ui.sourcewebview

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.sources.extension.ExtensionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import eu.kanade.tachiyomi.network.NetworkHelper as ExtNetworkHelper

data class SourceWebViewUiState(
    val sourceName: String = "",
    val startUrl: String = "",
    val userAgent: String? = null,
    /** Cookies the jar holds for the current host. */
    val storedCookies: Int = 0,
    val message: String? = null,
    val blocked: Boolean = false,
    /**
     * A page loaded but produced no cookie: the site is showing an
     * interstitial (age gate, "I am not a robot") that needs a tap.
     */
    val awaitingInteraction: Boolean = false,
)

/**
 * Backs the manual Cloudflare screen: a real WebView solves the challenge with
 * the user watching (checkbox, CAPTCHA), and the cookies it produces are
 * copied into the extension's persistent jar.
 *
 * The system [CookieManager] and OkHttp are different stores — without the
 * copy, a clearance earned here would never reach a source request.
 */
class SourceWebViewViewModel(
    private val sourceManager: SourceManager,
    private val networkHelper: ExtNetworkHelper,
    private val sourceId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceWebViewUiState())
    val state: StateFlow<SourceWebViewUiState> = _state.asStateFlow()

    private val cookieManager = CookieManager.getInstance()

    init {
        val source = sourceManager.getSource(sourceId)
        val extension = source as? ExtensionSource
        val baseUrl = extension?.baseUrl.orEmpty()
        val jar = networkHelper.cookieJar
        // Cookie store shared with the WebView: needed for the copy to matter.
        cookieManager.setAcceptCookie(true)
        // Seed the WebView with what the jar already proved for this host, so
        // a site cleared earlier does not greet the user with the gate again.
        val seeded = baseUrl.toHttpUrlOrNull()?.let { url ->
            jar.exportFor(url)?.let { header ->
                cookieManager.setCookie(url.toString(), header)
                cookieManager.flush()
                true
            }
        } == true
        _state.value = SourceWebViewUiState(
            sourceName = source?.name.orEmpty(),
            startUrl = baseUrl,
            userAgent = webViewUserAgent(extension),
            storedCookies = baseUrl.toHttpUrlOrNull()?.let { jar.countFor(it) } ?: 0,
            message = if (seeded) {
                "Cookies anteriores reaplicados neste WebView."
            } else {
                null
            },
        )
    }

    fun onUrlChanged(url: String) {
        _state.value = _state.value.copy(startUrl = url)
    }

    /** Main frame answered 403/503: the challenge is still up. */
    fun onBlocked(blocked: Boolean) {
        _state.value = _state.value.copy(blocked = blocked, awaitingInteraction = false)
    }

    /**
     * Copies the WebView's cookies for [url] into the jar. Called on every page
     * load (solving the challenge is enough — no extra tap) and by the button.
     */
    fun saveCookies(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: run {
            _state.value = _state.value.copy(message = "URL inválida")
            return
        }
        val jar = networkHelper.cookieJar
        val header = cookieManager.getCookie(url)
        val imported = jar.importFromWebView(parsed, header)
        val total = jar.countFor(parsed)
        _state.value = _state.value.copy(
            storedCookies = total,
            awaitingInteraction = imported == 0,
            message = when {
                imported == 0 -> null
                // A gate often sets a session cookie and the clearance comes
                // with the request after it: ask for the reload.
                else -> "$imported cookie(s) salvos. Volte para a fonte e recarregue."
            },
        )
    }

    fun clearCookies(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val jar = networkHelper.cookieJar
        val removed = jar.countFor(parsed)
        jar.clearFor(parsed)
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        _state.value = _state.value.copy(
            storedCookies = 0,
            awaitingInteraction = false,
            message = if (removed > 0) {
                "$removed cookie(s) removidos. Feche e reabra a fonte."
            } else {
                "Não havia cookies salvos."
            },
        )
    }

    /** Called after any navigation so the status reflects reality. */
    fun refreshStored(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val count = networkHelper.cookieJar.countFor(parsed)
        if (count != _state.value.storedCookies) {
            _state.value = _state.value.copy(storedCookies = count)
        }
    }
}

/**
 * User-Agent for the challenge WebView.
 *
 * The Android WebView default ends in `; wv)` and carries `Version/4.0` —
 * Cloudflare reads that as an embedded browser, refuses to initialise the
 * Turnstile widget, and the interstitial stays empty (no checkbox, nothing to
 * scroll). The extension's own UA is preferred (the site is happy with it in
 * OkHttp), with the tell-tale tokens stripped either way.
 */
private fun webViewUserAgent(extension: ExtensionSource?): String {
    val ua = extension?.userAgent()
        ?.takeIf { it.isNotBlank() && "android" in it.lowercase() }
        ?: DEFAULT_CHROME_UA
    val cleaned = ua
        .replace("; wv", "")
        .replace("Version/4.0 ", "")
        .replace("; wv)", ")")
    android.util.Log.i("SourceWebView", "UA do WebView: $cleaned")
    return cleaned
}

private const val DEFAULT_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"
