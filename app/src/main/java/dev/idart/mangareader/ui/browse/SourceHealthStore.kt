package dev.idart.mangareader.ui.browse

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Health of catalogue sources for searching: failure counter with escalating
 * cooldown, plus a manual blocklist.
 *
 * Today this knowledge only lived inside the assistant
 * ([dev.idart.mangareader.ui.ai.AgentTools]) as a private in-memory map, so
 * the search screens kept hammering a source that was already known dead —
 * every attempt costing a timeout slot in the parallel budget.
 *
 * Synchronous by design: it is read while building a search request, and a
 * DataStore round trip per source would defeat the point.
 */
class SourceHealthStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("source_health", Context.MODE_PRIVATE)

    /** Sources the user asked never to search again. */
    val blockedIds: Set<Long>
        get() = (prefs.getStringSet(BLOCKS, emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun isBlocked(sourceId: Long): Boolean = blockedIds.contains(sourceId)

    fun setBlocked(sourceId: Long, blocked: Boolean) {
        val current = blockedIds.toMutableSet()
        if (blocked) current += sourceId else current -= sourceId
        prefs.edit().putStringSet(BLOCKS, current.map { it.toString() }.toSet()).commit()
    }

    fun isCoolingDown(sourceId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val since = lastFailure(sourceId)
        if (since <= 0L) return false
        val elapsed = now - since
        if (elapsed >= cooldownFor(failCount(sourceId))) {
            // Cooldown expired: stop counting it against the source.
            forget(sourceId)
            return false
        }
        return true
    }

    fun recordFailure(sourceId: Long, message: String? = null) {
        val next = failCount(sourceId) + 1
        prefs.edit()
            .putInt(failuresKey(sourceId), next)
            .putLong(failedAtKey(sourceId), System.currentTimeMillis())
            .putString(failedMsgKey(sourceId), message.orEmpty().take(120))
            .commit()
    }

    fun recordSuccess(sourceId: Long) {
        // A single success wipes the whole history: the source is healthy.
        if (failCount(sourceId) == 0 && lastFailure(sourceId) == 0L) return
        forget(sourceId)
    }

    fun forget(sourceId: Long) {
        prefs.edit()
            .remove(failuresKey(sourceId))
            .remove(failedAtKey(sourceId))
            .remove(failedMsgKey(sourceId))
            .commit()
    }

    fun failCount(sourceId: Long): Int = prefs.getInt(failuresKey(sourceId), 0)

    fun lastFailure(sourceId: Long): Long = prefs.getLong(failedAtKey(sourceId), 0L)

    fun lastError(sourceId: Long): String? =
        prefs.getString(failedMsgKey(sourceId), null)?.takeIf { it.isNotBlank() }

    /**
     * Why this source is being skipped, or null when it should be searched.
     * Wording is ready for the UI so the skip never happens silently.
     */
    fun skipReason(sourceId: Long, now: Long = System.currentTimeMillis()): String? {
        if (isBlocked(sourceId)) return "não usada (você bloqueou)"
        if (!isCoolingDown(sourceId, now)) return null
        val elapsed = now - lastFailure(sourceId)
        val total = cooldownFor(failCount(sourceId))
        val remaining = formatDuration(total - elapsed)
        val error = lastError(sourceId)
        val times = failCount(sourceId)
        return buildString {
            append("falhou ")
            append(formatDuration(elapsed))
            append(if (times > 1) " ($times×)" else "")
            append(" · nova tentativa em $remaining")
            if (!error.isNullOrBlank()) append(" · $error")
        }
    }

    private fun cooldownFor(failures: Int): Long = when {
        failures <= 0 -> 0L
        failures == 1 -> TimeUnit.MINUTES.toMillis(3)
        failures == 2 -> TimeUnit.MINUTES.toMillis(10)
        else -> TimeUnit.MINUTES.toMillis(30)
    }

    private fun failuresKey(id: Long) = "fails_$id"
    private fun failedAtKey(id: Long) = "failed_at_$id"
    private fun failedMsgKey(id: Long) = "failed_msg_$id"

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "agora"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    private companion object {
        const val BLOCKS = "blocks"
    }
}
