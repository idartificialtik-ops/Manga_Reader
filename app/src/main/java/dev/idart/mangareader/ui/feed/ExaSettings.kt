package dev.idart.mangareader.ui.feed

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Exa (neural web search) settings. Key stored locally with synchronous
 * [commit] so it sticks immediately.
 */
class ExaSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("exa_settings", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: Flow<String> = _apiKey.asStateFlow()

    val hasKey: Boolean get() = _apiKey.value.isNotBlank()

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).commit()
        _apiKey.update { trimmed }
    }

    /** Re-reads from disk (heals any in-memory staleness). */
    fun refresh() {
        _apiKey.update { prefs.getString(KEY_API_KEY, "") ?: "" }
    }

    companion object {
        private const val KEY_API_KEY = "api_key"

        /** Where the user grabs a key. */
        const val GET_KEY_URL = "https://dashboard.exa.ai/api-keys"
    }
}
