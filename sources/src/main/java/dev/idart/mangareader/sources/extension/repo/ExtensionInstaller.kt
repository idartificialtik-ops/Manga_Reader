package dev.idart.mangareader.sources.extension.repo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads an extension APK and installs it through [PackageInstaller].
 *
 * The system shows its own install/update confirmation dialog; the outcome
 * is delivered to [InstallResultReceiver] and re-emitted on [InstallEvents].
 */
data class InstallResult(
    val packageName: String?,
    val success: Boolean,
    val message: String?,
)

object InstallEvents {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    val results = _results.asSharedFlow()

    internal fun emit(result: InstallResult) {
        _results.tryEmit(result)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system asks the user to confirm: launch its dialog. The
            // final SUCCESS/FAILURE result arrives as a second broadcast.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            } else {
                InstallEvents.emit(
                    InstallResult(null, false, "Confirmação do sistema indisponível"),
                )
            }
            return
        }
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        InstallEvents.emit(
            InstallResult(
                packageName = pkg,
                success = status == PackageInstaller.STATUS_SUCCESS,
                message = msg,
            ),
        )
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "dev.idart.mangareader.INSTALL_RESULT"
    }
}

class ExtensionInstaller(
    private val context: Context,
    private val client: OkHttpClient,
) {

    /**
     * Downloads [apkUrl] to the app cache (reporting `downloaded/total`
     * bytes) and then commits a [PackageInstaller] session, which asks the
     * user to confirm. Returns after commit; the final outcome arrives via
     * [InstallEvents.results].
     */
    suspend fun downloadAndInstall(
        packageName: String,
        apkUrl: String,
        fileName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apkFile = downloadApk(apkUrl, fileName, onProgress)
            commitInstall(packageName, apkFile)
        }
    }

    private fun downloadApk(
        apkUrl: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "extension_apks").apply { mkdirs() }
        val out = File(dir, fileName)
        val request = Request.Builder().url(apkUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty body")
            val total = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        return out
    }

    private fun commitInstall(packageName: String, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apkFile.inputStream().use { input ->
                session.openWrite("$packageName-base", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_RESULT
            }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        } finally {
            session.close()
        }
    }
}
