package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Minimal in-memory cookie jar (session cookies only).
 * Real host implementation for extensions.
 */
class InMemoryCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies.toMutableList()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host].orEmpty().filter { it.matches(url) }
    }
}
