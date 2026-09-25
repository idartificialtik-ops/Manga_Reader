package dev.idart.mangareader.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

class ThemeRepository(
    private val context: Context,
) {

    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { preferences ->
        runCatching { ThemeMode.valueOf(preferences[THEME_KEY] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[THEME_KEY] = mode.name
        }
    }

    /**
     * Seed color index (0 = system dynamic color, 1..N = presets in [ThemeSeeds]).
     */
    val seedIndex: Flow<Int> = context.themeDataStore.data.map { preferences ->
        preferences[SEED_KEY] ?: 0
    }

    suspend fun setSeedIndex(index: Int) {
        context.themeDataStore.edit { preferences ->
            preferences[SEED_KEY] = index
        }
    }

    /** Pure-black surfaces in dark mode (saves battery on AMOLED panels). */
    val amoled: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[AMOLED_KEY] ?: false
    }

    suspend fun setAmoled(value: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[AMOLED_KEY] = value
        }
    }

    companion object {
        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val SEED_KEY = intPreferencesKey("theme_seed")
        private val AMOLED_KEY = booleanPreferencesKey("amoled")
    }
}
