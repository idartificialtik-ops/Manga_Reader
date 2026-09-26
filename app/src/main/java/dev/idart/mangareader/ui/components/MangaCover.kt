package dev.idart.mangareader.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import coil3.request.crossfade
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.sources.extension.ExtensionSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val TAG = "MangaCover"

/**
 * Cover request with the source's own headers (User-Agent, Referer) attached,
 * so image CDNs that gate on headers keep working.
 *
 * Shared with the cover-palette extraction on the detail screen: the palette
 * used to build its own bare request, so any cover that needs those headers
 * displayed fine but produced no color.
 *
 * [sizePx] asks Coil for a scaled decode instead of the full-resolution image
 * (the palette only needs a few pixels per color) and forces a software
 * bitmap, so callers can read pixels without copying.
 */
fun coverImageRequest(
    context: Context,
    coverUrl: String,
    sourceId: Long?,
    sizePx: Int? = null,
    crossfade: Boolean = false,
): ImageRequest {
    val builder = ImageRequest.Builder(context).data(coverUrl)
    if (crossfade) builder.crossfade(true)
    if (sizePx != null) {
        builder.size(sizePx)
        builder.bitmapConfig(Bitmap.Config.ARGB_8888)
    }
    val headers = sourceId?.let { id ->
        runCatching {
            (Injekt.get<SourceManager>().getSource(id) as? ExtensionSource)?.coverHeaders()
        }.getOrNull()
    }
    if (headers != null) {
        val networkHeaders = NetworkHeaders.Builder()
        for (i in 0 until headers.size) {
            networkHeaders.add(headers.name(i), headers.value(i))
        }
        builder.httpHeaders(networkHeaders.build())
    }
    return builder.build()
}

/**
 * Manga cover with the source's own headers (User-Agent etc.) attached, so
 * image CDNs that gate on headers keep working. Failures are logged once per
 * URL with host + exception for diagnostics (`adb logcat -s MangaCover`).
 */
@Composable
fun MangaCover(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    sourceId: Long? = null,
) {
    val rounded = Modifier.clip(RoundedCornerShape(8.dp))
    if (coverUrl.isNullOrBlank()) {
        CoverPlaceholder(modifier.then(rounded))
        return
    }
    val context = LocalContext.current
    val model = remember(coverUrl, sourceId) {
        coverImageRequest(context, coverUrl, sourceId, crossfade = true)
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.then(rounded),
        contentScale = ContentScale.Crop,
        loading = { CoverPlaceholder(Modifier.matchParentSize()) },
        error = { state ->
            remember(coverUrl) {
                val throwable = state.result.throwable
                Log.e(TAG, "fail host=${runCatching { android.net.Uri.parse(coverUrl).host }} url=$coverUrl err=${throwable::class.java.simpleName}: ${throwable.message}")
                true
            }
            CoverPlaceholder(Modifier.matchParentSize())
        },
    )
}

@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
