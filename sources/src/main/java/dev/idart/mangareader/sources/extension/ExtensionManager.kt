package dev.idart.mangareader.sources.extension

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import dev.idart.mangareader.domain.source.service.SourceManager
import keiyoushi.utils.SOURCE_PREFS_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    sealed interface UninstallOutcome {
        data class Done(val cleanedSources: Int) : UninstallOutcome

        /** User backed out, or the system dialog never came. */
        data object Cancelled : UninstallOutcome

        data class Failed(val message: String) : UninstallOutcome
    }

    /**
     * Removes an extension through the system uninstall dialog and waits for
     * the result.
     *
     * Why the dialog: on modern SDKs the callback-based
     * `PackageInstaller.uninstall` overloads are gone — only the
     * `IntentSender` variants remain, and those hand the consent UI back to
     * the caller. Asking the system is both the reliable path and the one the
     * user expects for a destructive action.
     */
    suspend fun uninstall(packageName: String): UninstallOutcome {
        val sourceIds = _installed.value
            .firstOrNull { it.packageName == packageName }
            ?.sources
            ?.map { it.id }
            .orEmpty()
        if (!isInstalled(packageName)) {
            return UninstallOutcome.Failed("extensão não encontrada")
        }
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse { return UninstallOutcome.Failed(it.message ?: "não consegui abrir a tela do sistema") }

        if (!launched) return UninstallOutcome.Cancelled
        val removed = awaitRemoval(packageName)
        if (!removed) return UninstallOutcome.Cancelled
        clearPreferences(sourceIds)
        refresh()
        return UninstallOutcome.Done(sourceIds.size)
    }

    /** Waits for the package to disappear from the system. */
    private suspend fun awaitRemoval(packageName: String): Boolean {
        return withTimeoutOrNull(UNINSTALL_WAIT_MS) {
            while (true) {
                val stillThere = runCatching {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                }.getOrDefault(false)
                if (!stillThere) return@withTimeoutOrNull true
                delay(400)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Label + package of an installed extension, for the assistant. */
    fun installedBrief(): List<Pair<String, String>> =
        _installed.value.map { it.label to it.packageName }

    fun isInstalled(packageName: String): Boolean =
        _installed.value.any { it.packageName == packageName }

    /**
     * Deletes the `source_<id>` preference files of the given sources.
     *
     * Uninstalling goes through the system dialog (`ACTION_DELETE`): the
     * `PackageInstaller.uninstall` overload without user consent is hidden on
     * API 30+, and asking is the expected UX anyway. The leftovers would
     * otherwise keep logins and cookies of a source that no longer exists.
     */
    fun clearPreferences(sourceIds: List<Long>) {
        sourceIds.forEach { id ->
            runCatching {
                context.deleteSharedPreferences(SOURCE_PREFS_PREFIX + id)
            }.onFailure { Log.w(TAG, "não limpou prefs de $id: ${it.message}") }
        }
    }

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

    private companion object {
        private const val TAG = "ExtensionManager"

        /** Tempo que o usuário pode levar no diálogo do sistema. */
        private const val UNINSTALL_WAIT_MS = 180_000L
    }
}
