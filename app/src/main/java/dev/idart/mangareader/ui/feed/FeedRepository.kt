package dev.idart.mangareader.ui.feed

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@Serializable
data class FeedItem(
    val id: String,
    val title: String,
    val reason: String,
    val mangaId: Long? = null,
    val sourceId: Long? = null,
    val coverUrl: String? = null,
    val query: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private data class FeedStore(
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
)

/**
 * Feed store (JSON file) + refresh settings. Writes are synchronous so the
 * interval/enabled choice survives process death.
 */
class FeedRepository(app: Application) {

    private val context: Context = app.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("feed_settings", Context.MODE_PRIVATE)
    private val file = java.io.File(context.filesDir, "feed/feed.json")
    private val json = Json { ignoreUnknownKeys = true }

    private fun readStore(): FeedStore {
        return runCatching {
            if (!file.exists()) return FeedStore()
            json.decodeFromString(FeedStore.serializer(), file.readText())
        }.getOrDefault(FeedStore())
    }

    private fun writeStore(store: FeedStore) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(FeedStore.serializer(), store))
        }
    }

    private val _items = MutableStateFlow(readStore().items)
    val items: Flow<List<FeedItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow(readStore().error)
    val error: Flow<String?> = _error.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: Flow<Boolean> = _enabled.asStateFlow()

    private val _intervalHours = MutableStateFlow(prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS))
    val intervalHours: Flow<Long> = _intervalHours.asStateFlow()

    private val _lastRefresh = MutableStateFlow(prefs.getLong(KEY_LAST, 0L))
    val lastRefresh: Flow<Long> = _lastRefresh.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: Flow<Boolean> = _refreshing.asStateFlow()

    /**
     * Etapa atual da geração, exibida junto do spinner. Um spinner sem texto
     * parece travamento, e o pipeline tem etapas lentas de verdade (IA +
     * resolução em cada fonte).
     */
    private val _stage = MutableStateFlow<String?>(null)
    val stage: Flow<String?> = _stage.asStateFlow()

    fun setStage(value: String?) {
        _stage.update { value }
    }

    fun setRefreshing(value: Boolean) {
        _refreshing.update { value }
        if (!value) _stage.update { null }
    }

    /**
     * Appends one item while a refresh is running, so cards appear as they are
     * resolved instead of all at once at the end of the pipeline.
     *
     * Only used when starting from an empty feed: with a cached feed already on
     * screen, appending would grow the list and then replace it.
     */
    fun publishPartial(item: FeedItem) {
        val current = _items.value.toMutableList()
        current.removeAll { it.id == item.id }
        current += item
        writeStore(FeedStore(current, _error.value))
        _items.update { current }
    }

    fun isEmpty(): Boolean = _items.value.isEmpty()

    fun saveItems(items: List<FeedItem>, error: String? = null) {
        writeStore(FeedStore(items, error))
        _items.update { items }
        _error.update { error }
    }

    fun saveError(error: String) {
        writeStore(FeedStore(_items.value, error))
        _error.update { error }
    }

    fun markRefreshed() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST, now).commit()
        _lastRefresh.update { now }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        _enabled.update { value }
        schedule()
    }

    fun setIntervalHours(hours: Long) {
        prefs.edit().putLong(KEY_INTERVAL, hours).commit()
        _intervalHours.update { hours }
        schedule()
    }

    fun needsRefresh(): Boolean {
        if (!_enabled.value) return false
        val last = _lastRefresh.value
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= _intervalHours.value * 3_600_000L
    }

    /** (Re)schedules the periodic background refresh. */
    fun schedule() {
        val manager = WorkManager.getInstance(context)
        if (!_enabled.value) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
            _intervalHours.value.coerceAtLeast(1L),
            TimeUnit.HOURS,
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val WORK_NAME = "feed-refresh"
        const val DEFAULT_INTERVAL_HOURS = 24L
        val INTERVAL_OPTIONS = listOf(6L, 12L, 24L, 72L)

        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_LAST = "last_refresh"
    }
}
