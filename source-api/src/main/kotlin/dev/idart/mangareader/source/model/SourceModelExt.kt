package dev.idart.mangareader.source.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Sets [SManga.url] (or [SChapter.url]) stripping scheme + host, keeping path + query.
 */
fun SManga.setUrlWithoutDomain(url: String) {
    this.url = stripDomain(url)
}

fun SChapter.setUrlWithoutDomain(url: String) {
    this.url = stripDomain(url)
}

private fun stripDomain(url: String): String {
    val httpUrl = url.toHttpUrlOrNull() ?: return url
    val query = httpUrl.encodedQuery?.let { "?$it" } ?: ""
    return httpUrl.encodedPath + query
}
