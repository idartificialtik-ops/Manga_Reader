package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class StoredCookie(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    /** Epoch millis; 0 means session cookie (kept until cleared). */
    val expiresAt: Long,
    val secure: Boolean,
)

/**
 * Cookie jar for extension traffic that survives the process being killed.
 *
 * The old [InMemoryCookieJar] lost everything on restart, which made a
 * Cloudflare clearance earned once useless on the next launch — the 403 came
 * back and the user had to solve the challenge again.
 *
 * It is also the landing spot for [importFromWebView]: the WebView stores
 * cookies in the *system* store, which OkHttp never reads, so a manual solve
 * has to be copied here for the source's requests to carry it.
 */
class PersistentCookieJar(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CookieJar {

    private val file = File(context.filesDir, "cookies/extension_cookies.json")

    private val lock = Any()

    /** Loaded on first use: a bad file must never break the app. */
    private var cookies: MutableList<StoredCookie>? = null

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val store = load()
            cookies.forEach { cookie ->
                val key = Triple(cookie.domain, cookie.path, cookie.name)
                // OkHttp signals removal with an expired cookie.
                if (cookie.expiresAt <= System.currentTimeMillis()) {
                    store.removeAll { Triple(it.domain, it.path, it.name) == key }
                } else {
                    store.removeAll { Triple(it.domain, it.path, it.name) == key }
                    store += StoredCookie(
                        domain = cookie.domain,
                        path = cookie.path,
                        name = cookie.name,
                        value = cookie.value,
                        expiresAt = cookie.expiresAt,
                        secure = cookie.secure,
                    )
                }
            }
            persist(store)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            load()
                .filter { it.expiresAt == 0L || it.expiresAt > now }
                .mapNotNull { it.toCookie() }
                .filter { it.matches(url) }
        }
    }

    /**
     * Copies cookies out of the system cookie store (where a WebView keeps
     * them) into this jar, so a challenge solved by hand starts working for
     * every later request of that host.
     *
     * [cookieHeader] is what `CookieManager.getCookie(url)` returned.
     *
     * @return how many cookies were stored.
     */
    fun importFromWebView(url: HttpUrl, cookieHeader: String?): Int {
        if (cookieHeader.isNullOrBlank()) return 0
        val now = System.currentTimeMillis()
        val stored = cookieHeader.split(';')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
                StoredCookie(
                    domain = url.host,
                    path = "/",
                    name = name,
                    value = value,
                    expiresAt = now + IMPORTED_TTL_MS,
                    secure = url.isHttps,
                )
            }
        if (stored.isEmpty()) return 0
        synchronized(lock) {
            val store = load()
            stored.forEach { cookie ->
                val key = Triple(cookie.domain, cookie.path, cookie.name)
                store.removeAll { Triple(it.domain, it.path, it.name) == key }
            }
            store += stored
            persist(store)
        }
        Log.i(TAG, "importados ${stored.size} cookie(s) de ${url.host}")
        return stored.size
    }

    /** Cookies this jar holds for [url]'s host — for the UI to show progress. */
    fun countFor(url: HttpUrl): Int {
        return synchronized(lock) {
            load().count { it.domain.removePrefix(".").endsWith(url.host) }
        }
    }

    /**
     * The stored cookies of [url]'s host as a `Cookie` header, or null.
     *
     * Used to seed the WebView with what the source already proved, so a
     * site the user cleared before does not show the gate again.
     */
    fun exportFor(url: HttpUrl): String? {
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val header = load()
                .filter { it.domain.removePrefix(".").endsWith(url.host) }
                .filter { it.expiresAt == 0L || it.expiresAt > now }
                .joinToString("; ") { "${it.name}=${it.value}" }
            header.takeIf { it.isNotBlank() }
        }
    }

    /** Drops everything stored for a host (a stale clearance locks you out). */
    fun clearFor(url: HttpUrl): Int {
        return synchronized(lock) {
            val store = load()
            val removed = store.size
            store.removeAll { it.domain.removePrefix(".").endsWith(url.host) }
            persist(store)
            removed
        }
    }

    fun clearAll() {
        synchronized(lock) {
            cookies = mutableListOf()
            runCatching { file.delete() }
        }
    }

    private fun load(): MutableList<StoredCookie> {
        cookies?.let { return it }
        val loaded = runCatching {
            if (file.exists()) {
                json.decodeFromString<List<StoredCookie>>(file.readText()).toMutableList()
            } else {
                mutableListOf()
            }
        }.onFailure { Log.w(TAG, "jar ilegível, recomeçando: ${it.message}") }
            .getOrDefault(mutableListOf())
        cookies = loaded
        return loaded
    }

    private fun persist(store: List<StoredCookie>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(store))
        }.onFailure { Log.w(TAG, "não foi possível salvar cookies: ${it.message}") }
    }

    private fun StoredCookie.toCookie(): Cookie? {
        return runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .apply { if (expiresAt > 0) expiresAt(expiresAt) }
                .apply { if (secure) secure() }
                .build()
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ExtensionCookies"

        /**
         * How long a cookie solved by hand lasts. A Cloudflare clearance is
         * good for months, so this is deliberately generous.
         */
        private val IMPORTED_TTL_MS = TimeUnit.DAYS.toMillis(30)
    }
}
