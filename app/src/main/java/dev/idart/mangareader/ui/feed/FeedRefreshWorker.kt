package dev.idart.mangareader.ui.feed

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Periodic background Feed refresh. Errors are stored in the
 * [FeedRepository] for the UI; the work itself always succeeds (retrying on
 * schedule) unless really cancelled.
 */
class FeedRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            Injekt.get<FeedUpdater>().refresh()
            Result.success()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}
