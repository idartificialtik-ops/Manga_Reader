package dev.idart.mangareader.sources.extension.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keiyoushi extension repository catalog
 * (`https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json`).
 *
 * The index (~1.4MB) is cached on disk for 24h; each entry carries direct
 * apk/icon URLs, so no further mapping files are needed.
 */
@Serializable
data class RepoIndex(
    val name: String = "",
    @SerialName("extensionList") val extensionList: RepoExtensionList = RepoExtensionList(),
)

@Serializable
data class RepoExtensionList(
    val extensions: List<RepoExtension> = emptyList(),
)

@Serializable
data class RepoExtension(
    val name: String,
    val packageName: String,
    val resources: RepoResources,
    val extensionLib: String? = null,
    val versionCode: String = "0",
    val versionName: String = "",
    val language: List<String> = emptyList(),
    val isNsfw: Boolean = false,
    /** Proto-enum form used by some repos (`CONTENT_WARNING_SAFE/MIXED/NSFW`). */
    val contentWarning: String = "",
    val sources: List<RepoSource> = emptyList(),
) {
    val versionCodeLong: Long get() = versionCode.toLongOrNull() ?: 0L

    val isNsfwEffective: Boolean
        get() = isNsfw || contentWarning == "CONTENT_WARNING_NSFW" || contentWarning == "CONTENT_WARNING_MIXED"

    /** Placeholder notice entries, not real installable extensions. */
    fun isPlaceholder(): Boolean {
        return packageName.endsWith(".keiyoushi") || packageName.endsWith(".mihon") ||
            name.contains("migrate to", ignoreCase = true)
    }
}

@Serializable
data class RepoResources(
    val apkUrl: String,
    val iconUrl: String = "",
    val jarUrl: String? = null,
)

@Serializable
data class RepoSource(
    val name: String,
    val lang: String = "",
    /** Alternate key used by some repos. */
    val language: String = "",
    val id: String = "",
    val baseUrl: String? = null,
) {
    val effectiveLang: String get() = lang.ifBlank { language }
}

class ExtensionRepo(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Volatile
    private var cached: List<RepoExtension>? = null

    /**
     * Fetches the index of any repo ([RepoEntry.indexUrl]), cached on disk
     * per repo for 24h.
     */
    suspend fun fetchIndex(
        indexUrl: String,
        forceRefresh: Boolean = false,
    ): List<RepoExtension> = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(indexUrl)
        if (!forceRefresh && cacheFile.exists() && !isStale(cacheFile, indexUrl)) {
            runCatching { readCache(cacheFile) }.getOrNull()?.let { return@withContext it }
        }
        val fresh = fetch(indexUrl)
        runCatching {
            cacheFile.writeText(json.encodeToString(RepoExtensionList.serializer(), RepoExtensionList(fresh)))
            timestampFileFor(indexUrl).writeText(System.currentTimeMillis().toString())
        }
        fresh
    }

    /**
     * Resolves a repo display name from its sibling `repo.json`
     * (`{meta:{name}}` or `{name}`), falling back to the URL host.
     */
    suspend fun resolveRepoName(indexUrl: String): String = withContext(Dispatchers.IO) {
        val base = indexUrl.substringBeforeLast("/") + "/"
        val fallback = runCatching { java.net.URI(base).host }.getOrDefault(base) ?: base
        runCatching {
            val request = Request.Builder().url(base + "repo.json").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching fallback
                val root = json.parseToJsonElement(response.body?.string() ?: return@runCatching fallback)
                    as? kotlinx.serialization.json.JsonObject ?: return@runCatching fallback
                val meta = root["meta"] as? kotlinx.serialization.json.JsonObject
                (meta?.get("name") ?: root["name"])
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: fallback
            }
        }.getOrDefault(fallback)
    }

    suspend fun catalog(forceRefresh: Boolean = false): List<RepoExtension> {
        cached?.takeUnless { forceRefresh }?.let { return it }
        return fetchIndex(INDEX_URL, forceRefresh).also { cached = it }
    }

    private suspend fun fetch(indexUrl: String): List<RepoExtension> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IllegalStateException("Empty index")
            decodeLenient(body)
        }
    }

    /**
     * Decodes the index entry-by-entry: one malformed entry (unknown shape,
     * variant field types) must not nuke the whole repo.
     */
    private fun decodeLenient(body: String): List<RepoExtension> {
        val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalStateException("Invalid index")
        val extensions = root["extensionList"]
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("extensions") as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalStateException("Invalid index")
        return extensions.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(RepoExtension.serializer(), element) }.getOrNull()
        }.filterNot { it.isPlaceholder() }
    }

    private fun readCache(file: java.io.File): List<RepoExtension> {
        return json.decodeFromString(
            RepoExtensionList.serializer(),
            file.readText(),
        ).extensions.filterNot { it.isPlaceholder() }
    }

    private fun isStale(cacheFile: java.io.File, indexUrl: String): Boolean {
        val last = runCatching { timestampFileFor(indexUrl).readText().toLong() }.getOrNull() ?: 0L
        return System.currentTimeMillis() - last > CACHE_TTL_MS
    }

    private fun cacheKeyFor(indexUrl: String): String {
        return indexUrl.hashCode().toString(16)
    }

    private fun cacheFileFor(indexUrl: String) =
        java.io.File(context.cacheDir, "extension_index_${cacheKeyFor(indexUrl)}.json")

    private fun timestampFileFor(indexUrl: String) =
        java.io.File(context.cacheDir, "extension_index_${cacheKeyFor(indexUrl)}.ts")

    companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
