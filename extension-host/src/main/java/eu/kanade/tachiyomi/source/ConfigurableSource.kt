package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
interface ConfigurableSource {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
