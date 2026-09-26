package keiyoushi.utils

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Name prefix of the [SharedPreferences] file a source stores its settings
 * in. The host must use the same prefix when it builds a source's preference
 * screen, or the user would type into a file the source never reads.
 */
const val SOURCE_PREFS_PREFIX = "source_"

/**
 * Returns the [SharedPreferences] associated with current source id
 */
inline fun HttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

/**
 * Lazily returns the [SharedPreferences] associated with current source id
 */
inline fun HttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { },
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
fun getPreferences(sourceId: Long): SharedPreferences =
    applicationContext.getSharedPreferences(SOURCE_PREFS_PREFIX + sourceId, 0x0000)
