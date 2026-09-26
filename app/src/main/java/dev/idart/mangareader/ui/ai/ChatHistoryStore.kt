package dev.idart.mangareader.ui.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.chatHistoryPrefs: DataStore<Preferences> by preferencesDataStore(name = "chat_history")

@Serializable
private data class StoredHit(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val genre: String? = null,
    val status: Int = 0,
)

@Serializable
private data class StoredMessage(
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val hits: List<StoredHit> = emptyList(),
)

/**
 * Persists the assistant conversation so it survives leaving the screen and
 * restarting the app.
 *
 * Only the settled messages are stored: the live "escrevendo…" bubble and
 * tool spinners are dropped, and the tail is capped ([MAX_MESSAGES]) so the
 * file cannot grow without bound. Restored hits keep working as cards — the
 * assistant reuses the cached hit to open the manga without a new search.
 */
class ChatHistoryStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    val messages: Flow<List<UiChatMessage>> = context.chatHistoryPrefs.data.map { prefs ->
        decode(prefs[HISTORY_KEY])
    }

    suspend fun load(): List<UiChatMessage> = messages.first()

    suspend fun save(list: List<UiChatMessage>) {
        val payload = list
            .filter { it.toolStatus == null }
            .takeLast(MAX_MESSAGES)
            .map {
                StoredMessage(
                    fromUser = it.fromUser,
                    text = it.text,
                    isError = it.isError,
                    hits = it.hits.map { hit ->
                        StoredHit(
                            sourceId = hit.sourceId,
                            sourceName = hit.sourceName,
                            url = hit.url,
                            title = hit.title,
                            thumbnailUrl = hit.thumbnailUrl,
                            genre = hit.genre,
                            status = hit.status,
                        )
                    },
                )
            }
        context.chatHistoryPrefs.edit { prefs ->
            prefs[HISTORY_KEY] = json.encodeToString(payload)
        }
    }

    suspend fun clear() {
        context.chatHistoryPrefs.edit { prefs -> prefs.remove(HISTORY_KEY) }
    }

    private fun decode(raw: String?): List<UiChatMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredMessage>>(raw).map { stored ->
                UiChatMessage(
                    fromUser = stored.fromUser,
                    text = stored.text,
                    isError = stored.isError,
                    hits = stored.hits.map {
                        MangaHit(
                            sourceId = it.sourceId,
                            sourceName = it.sourceName,
                            url = it.url,
                            title = it.title,
                            thumbnailUrl = it.thumbnailUrl,
                            genre = it.genre,
                            status = it.status,
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val HISTORY_KEY = stringPreferencesKey("messages")

        /** Roughly the last dozen exchanges. */
        private const val MAX_MESSAGES = 24
    }
}
