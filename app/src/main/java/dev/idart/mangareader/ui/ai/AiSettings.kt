package dev.idart.mangareader.ui.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AI provider settings. The API key lives in [EncryptedSharedPreferences];
 * the rest are plain prefs in the same file.
 */
class AiSettings(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ai_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
    val baseUrl: Flow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: Flow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val model: Flow<String> = _model.asStateFlow()

    private val _streamEnabled = MutableStateFlow(prefs.getBoolean(KEY_STREAM, true))
    val streamEnabled: Flow<Boolean> = _streamEnabled.asStateFlow()

    val hasKey: Boolean get() = _apiKey.value.isNotBlank()

    fun setBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        prefs.edit().putString(KEY_BASE_URL, normalized).commit()
        _baseUrl.update { normalized }
    }

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        prefs.edit().putString(KEY_API_KEY, trimmed).commit()
        _apiKey.update { trimmed }
    }

    fun setModel(value: String) {
        val trimmed = value.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString(KEY_MODEL, trimmed).commit()
        _model.update { trimmed }
    }

    fun setStreamEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_STREAM, value).commit()
        _streamEnabled.update { value }
    }

    /** Re-reads everything from disk (heals any in-memory staleness). */
    fun refresh() {
        _baseUrl.update { prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL }
        _apiKey.update { prefs.getString(KEY_API_KEY, "") ?: "" }
        _model.update { prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL }
        _streamEnabled.update { prefs.getBoolean(KEY_STREAM, true) }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        data class ProviderPreset(val name: String, val baseUrl: String, val model: String)

        val PRESETS = listOf(
            ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            ProviderPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
            ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
            ProviderPreset("Ollama local", "http://10.0.2.2:11434/v1", "llama3.1"),
        )

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_STREAM = "stream_enabled"
        private const val KEY_MODEL = "model"
    }
}
