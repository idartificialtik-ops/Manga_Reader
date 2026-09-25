package dev.idart.mangareader.sources.extension

import android.content.Context
import android.graphics.drawable.Drawable
import dev.idart.mangareader.domain.source.service.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Discovers installed extension APKs, wraps their sources in [ExtensionSource]
 * bridges and registers them in the [SourceManager].
 *
 * A failing extension never breaks the scan: its error is recorded per package.
 */
class ExtensionManager(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val loader: ExtensionLoader = ExtensionLoader(context),
) {

    data class ExtensionInfo(
        val packageName: String,
        val label: String,
        val versionName: String,
        val icon: Drawable?,
        val sources: List<ExtensionSource>,
        val error: String?,
    )

    private val _installed = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installed: StateFlow<List<ExtensionInfo>> = _installed.asStateFlow()

    suspend fun refresh(): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val loaded = loader.scanInstalled()
        // Wrap each source in isolation: one exotic source must never kill the
        // whole scan (honors this class' contract).
        val wrappedByPackage = loaded.associate { ext ->
            ext.packageName to ext.sources.mapNotNull { src ->
                runCatching { ExtensionSource(ext.packageName, src) }.getOrNull()
            }
        }
        val wrapped = wrappedByPackage.values.flatten()
        sourceManager.setExternalSources(wrapped)
        sourceManager.refresh()
        _installed.value = loaded.map { ext ->
            ExtensionInfo(
                packageName = ext.packageName,
                label = ext.label,
                versionName = ext.versionName,
                icon = runCatching {
                    pm.getApplicationIcon(ext.packageName)
                }.getOrNull(),
                sources = wrappedByPackage[ext.packageName] ?: emptyList(),
                error = ext.error,
            )
        }
        _installed.value
    }
}
