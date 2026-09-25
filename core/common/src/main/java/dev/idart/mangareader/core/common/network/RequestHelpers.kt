package dev.idart.mangareader.core.common.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

@Suppress("FunctionName")
fun GET(
    url: String,
    headers: Headers = Headers.Builder().build(),
    cache: CacheControl = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build(),
): Request {
    return Request.Builder()
        .url(url)
        .headers(headers)
        .cacheControl(cache)
        .build()
}
