package dev.idart.mangareader.ui.browse

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.browseFilterDataStore: DataStore<Preferences> by preferencesDataStore(name = "browse_filter")

/**
 * Persists which source languages are hidden from the Browse tab.
 * Empty set = every language visible.
 */
class BrowseFilterRepository(
    private val context: Context,
) {

    val hiddenLangs: Flow<Set<String>> = context.browseFilterDataStore.data.map { preferences ->
        preferences[HIDDEN_LANGS_KEY] ?: emptySet()
    }

    suspend fun setLangVisible(lang: String, visible: Boolean) {
        context.browseFilterDataStore.edit { preferences ->
            val current = preferences[HIDDEN_LANGS_KEY] ?: emptySet()
            preferences[HIDDEN_LANGS_KEY] = if (visible) current - lang else current + lang
        }
    }

    suspend fun setAllVisible() {
        context.browseFilterDataStore.edit { preferences ->
            preferences[HIDDEN_LANGS_KEY] = emptySet()
        }
    }

    suspend fun hideAll(langs: Collection<String>) {
        context.browseFilterDataStore.edit { preferences ->
            preferences[HIDDEN_LANGS_KEY] = langs.toSet()
        }
    }

    companion object {
        private val HIDDEN_LANGS_KEY = stringSetPreferencesKey("hidden_langs")
    }
}
