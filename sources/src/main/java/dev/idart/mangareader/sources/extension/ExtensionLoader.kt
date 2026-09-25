package dev.idart.mangareader.sources.extension

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

/**
 * Loads manga sources from installed extension APKs.
 *
 * Each extension is loaded in its own [PathClassLoader] whose parent is the
 * app classloader, so the shared `eu.kanade.tachiyomi` API resolves to our
 * host implementations while extension classes stay isolated per APK.
 */
class ExtensionLoader(
    private val context: Context,
) {

    data class LoadedExtension(
        val packageName: String,
        val label: String,
        val versionName: String,
        val extensionLibVersion: String?,
        val sources: List<CatalogueSource>,
        val error: String? = null,
    )

    fun scanInstalled(): List<LoadedExtension> {
        val pm = context.packageManager
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (t: Throwable) {
            return emptyList()
        }
        return packages.mapNotNull { pkg ->
            try {
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                val metaData = appInfo.metaData ?: return@mapNotNull null
                if (!metaData.containsKey(METADATA_CLASS)) return@mapNotNull null
                load(pkg.packageName, appInfo.sourceDir, metaData.getString(METADATA_CLASS))
            } catch (t: Throwable) {
                // A single broken package must never kill the scan.
                null
            }
        }
    }

    private fun load(packageName: String, apkPath: String?, className: String?): LoadedExtension {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val versionName = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("")
        if (apkPath == null || className == null) {
            return LoadedExtension(packageName, label, versionName ?: "", null, emptyList(), "Sem APK ou classe")
        }
        return try {
            val loader = PathClassLoader(apkPath, javaClass.classLoader)
            val qualifiedName = if (className.startsWith(".")) packageName + className else className
            val clazz = Class.forName(qualifiedName, true, loader)
            val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
            val instance = constructor.newInstance()
            val sources: List<Source> = when (instance) {
                is SourceFactory -> instance.createSources()
                is Source -> listOf(instance)
                else -> error("Classe desconhecida: ${instance.javaClass.name}")
            }
            LoadedExtension(
                packageName = packageName,
                label = label,
                versionName = versionName ?: "",
                extensionLibVersion = null,
                sources = sources.filterIsInstance<CatalogueSource>(),
            )
        } catch (t: Throwable) {
            // Errors (NoSuchMethodError, NoClassDefFoundError, VerifyError, …)
            // must be isolated per extension, never crash the host.
            LoadedExtension(packageName, label, versionName ?: "", null, emptyList(), t.chainString())
        }
    }

    companion object {
        const val METADATA_CLASS = "tachiyomi.extension.class"
        const val EXTENSION_FEATURE = "tachiyomi.extension"
    }
}

/**
 * Full cause chain (unwraps InvocationTargetException etc.) for diagnostics.
 */
fun Throwable.chainString(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 5) {
        parts += "${current.javaClass.name}: ${current.message}"
        if (current.cause === current) break
        current = current.cause
    }
    return parts.joinToString("\nCausado por: ")
}
