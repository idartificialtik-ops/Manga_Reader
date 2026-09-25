package dev.idart.mangareader

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Persists uncaught exceptions to app-private storage so crashes can be
 * diagnosed via `run-as ... cat files/crash.log`, independent of logcat
 * buffer rotation.
 */
object CrashReporter {

    const val CRASH_FILE = "crash.log"

    fun install(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                file.appendText(
                    "\n\n===== ${Date()} thread=${thread.name} =====\n" +
                        Log.getStackTraceString(throwable) + "\n",
                )
            } catch (_: Exception) {
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    fun log(context: Context, tag: String, throwable: Throwable) {
        try {
            File(context.filesDir, CRASH_FILE).appendText(
                "\n\n----- ${Date()} [$tag] -----\n" +
                    Log.getStackTraceString(throwable) + "\n",
            )
        } catch (_: Exception) {
        }
    }
}
