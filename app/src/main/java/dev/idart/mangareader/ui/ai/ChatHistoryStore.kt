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

private val THREADS_KEY = stringPreferencesKey("threads")

@Serializable
data class StoredHit(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val genre: String? = null,
    val status: Int = 0,
)

@Serializable
data class StoredMessage(
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val hits: List<StoredHit> = emptyList(),
)

/** One saved conversation. [messages] is the newest tail of the chat. */
@Serializable
data class ChatThread(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
)

/**
 * Saved assistant conversations.
 *
 * Only settled messages are stored: the live "escrevendo…" bubble and the tool
 * spinners are transient. Both the message tail and the thread list are capped,
 * so the file cannot grow without bound.
 *
 * Restored hits keep working as cards — the assistant reuses the cached hit to
 * open the manga without searching again.
 */
class ChatHistoryStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Stored threads, newest activity first. */
    val threads: Flow<List<ChatThread>> = context.chatHistoryPrefs.data.map { prefs ->
        decode(prefs[THREADS_KEY]).sortedByDescending { it.updatedAt }
    }

    suspend fun loadThreads(): List<ChatThread> =
        decode(rawThreads()).sortedByDescending { it.updatedAt }

    suspend fun loadMessages(threadId: Long): List<UiChatMessage> =
        decode(rawThreads()).firstOrNull { it.id == threadId }
            ?.messages
            ?.map { it.toUi() }
            .orEmpty()

    /**
     * Inserts or replaces [threadId] with [messages]. Transient bubbles are
     * filtered out, so this is safe to call on every message change.
     */
    suspend fun saveThread(threadId: Long, messages: List<UiChatMessage>) {
        val stored = messages
            .filter { it.toolStatus == null && it.text.isNotBlank() }
            .takeLast(MAX_MESSAGES)
            .map { it.toStored() }
        if (stored.isEmpty()) return
        val title = stored.firstOrNull { it.fromUser }?.text?.trim()?.take(60) ?: "Conversa"
        val others = decode(rawThreads()).filterNot { it.id == threadId }
        val kept = (others + ChatThread(threadId, title, System.currentTimeMillis(), stored))
            .sortedByDescending { it.updatedAt }
            .take(MAX_THREADS)
        context.chatHistoryPrefs.edit { prefs -> prefs[THREADS_KEY] = json.encodeToString(kept) }
    }

    suspend fun deleteThread(threadId: Long) {
        val kept = decode(rawThreads()).filterNot { it.id == threadId }
        context.chatHistoryPrefs.edit { prefs -> prefs[THREADS_KEY] = json.encodeToString(kept) }
    }

    /** Drops every conversation. */
    suspend fun clearAll() {
        context.chatHistoryPrefs.edit { prefs -> prefs.remove(THREADS_KEY) }
    }

    private suspend fun rawThreads(): String? = context.chatHistoryPrefs.data.first()[THREADS_KEY]

    private fun decode(raw: String?): List<ChatThread> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ChatThread>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        /** Messages kept per conversation (~30 exchanges). */
        private const val MAX_MESSAGES = 60

        /** Conversations kept before the oldest is dropped. */
        private const val MAX_THREADS = 20
    }
}

private fun UiChatMessage.toStored() = StoredMessage(
    fromUser = fromUser,
    text = text,
    isError = isError,
    hits = hits.map {
        StoredHit(
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

private fun StoredMessage.toUi() = UiChatMessage(
    fromUser = fromUser,
    text = text,
    isError = isError,
    hits = hits.map {
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
