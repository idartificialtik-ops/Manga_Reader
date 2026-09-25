package dev.idart.mangareader.sources.extension.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.extensionRepoPrefs: DataStore<Preferences> by preferencesDataStore(name = "extension_repos")

/**
 * A user-registered extension repository (Mihon index format).
 *
 * @param name display name (from the repo's `repo.json`).
 * @param indexUrl full URL of the repo index (`.../index.json`).
 */
@Serializable
data class RepoEntry(
    val name: String,
    val indexUrl: String,
)

class ExtensionRepoStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    val repos: Flow<List<RepoEntry>> = context.extensionRepoPrefs.data.map { prefs ->
        val raw = prefs[REPOS_KEY] ?: defaultRepos().map { encode(it) }.toSet()
        raw.mapNotNull { runCatching { json.decodeFromString<RepoEntry>(it) }.getOrNull() }
    }

    val hideNsfw: Flow<Boolean> = context.extensionRepoPrefs.data.map { prefs ->
        prefs[HIDE_NSFW_KEY] ?: false
    }

    suspend fun setHideNsfw(hide: Boolean) {
        context.extensionRepoPrefs.edit { prefs -> prefs[HIDE_NSFW_KEY] = hide }
    }

    suspend fun addRepo(entry: RepoEntry) {
        val encoded = json.encodeToString(RepoEntry.serializer(), entry)
        context.extensionRepoPrefs.edit { prefs ->
            val current = (prefs[REPOS_KEY] ?: emptySet()).toMutableSet()
            // Replace same-URL entries (e.g. renamed repo).
            current.removeAll { raw ->
                runCatching { json.decodeFromString<RepoEntry>(raw).indexUrl }.getOrNull() == entry.indexUrl
            }
            current += encoded
            prefs[REPOS_KEY] = current
        }
    }

    suspend fun removeRepo(entry: RepoEntry) {
        context.extensionRepoPrefs.edit { prefs ->
            val current = (prefs[REPOS_KEY] ?: emptySet()).toMutableSet()
            current.removeAll { raw ->
                runCatching { json.decodeFromString<RepoEntry>(raw) }.getOrNull() == entry
            }
            prefs[REPOS_KEY] = current
        }
    }

    suspend fun currentRepos(): List<RepoEntry> = repos.first()

    private fun encode(entry: RepoEntry): String {
        return json.encodeToString(RepoEntry.serializer(), entry)
    }

    companion object {
        private val REPOS_KEY = stringSetPreferencesKey("repos")
        private val HIDE_NSFW_KEY = booleanPreferencesKey("hide_nsfw")

        fun defaultRepos(): List<RepoEntry> {
            return listOf(
                RepoEntry(
                    name = "Keiyoushi",
                    indexUrl = ExtensionRepo.INDEX_URL,
                ),
            )
        }

        /**
         * Normalizes a user-pasted repo URL (the documented `index.min.json`
         * form) into the full `index.json` URL this client reads.
         */
        fun normalizeIndexUrl(rawUrl: String): String? {
            val url = rawUrl.trim().trimEnd('/')
            if (url.isBlank()) return null
            val base = when {
                url.endsWith("index.min.json") -> url.removeSuffix("index.min.json")
                url.endsWith("index.json") -> url.removeSuffix("index.json")
                url.endsWith("index.pb") -> url.removeSuffix("index.pb")
                else -> return null
            }
            if (!base.startsWith("http://") && !base.startsWith("https://")) return null
            return base + "index.json"
        }
    }
}
