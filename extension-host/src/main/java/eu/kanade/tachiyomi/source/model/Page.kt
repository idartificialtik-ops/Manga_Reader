package eu.kanade.tachiyomi.source.model

import android.net.Uri

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var uri: Uri? = null,
)
