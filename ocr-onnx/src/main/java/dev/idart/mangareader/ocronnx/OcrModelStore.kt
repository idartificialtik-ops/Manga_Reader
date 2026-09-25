package dev.idart.mangareader.ocronnx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface OcrModelState {
    data object Idle : OcrModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : OcrModelState
    data object Ready : OcrModelState
    data class Error(val message: String) : OcrModelState
}

/**
 * Downloads the PP-OCRv6_manga ONNX models (FP32, ~22MB) + dict on demand
 * into `files/ocr/`. Fase 1: storage + download only.
 *
 * FP32 on purpose: the FP16 graphs return NaN on ORT-Android (1.22).
 */
class OcrModelStore(
    context: Context,
) {

    private val dir = File(context.filesDir, "ocr").also { it.mkdirs() }

    val detFile = File(dir, "manga_det_v0.1.onnx")
    val recFile = File(dir, "manga_rec_v0.1.onnx")
    val dictFile = File(dir, "ppocrv6_dict.txt")

    private val _state = MutableStateFlow<OcrModelState>(OcrModelState.Idle)
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    fun isReady(): Boolean {
        return detFile.exists() && detFile.length() > 0 &&
            recFile.exists() && recFile.length() > 0 &&
            dictFile.exists() && dictFile.length() > 0
    }

    suspend fun ensureModels(): Boolean = withContext(Dispatchers.IO) {
        if (isReady()) {
            _state.value = OcrModelState.Ready
            return@withContext true
        }
        _state.value = OcrModelState.Downloading(0, TOTAL_BYTES)
        var done = 0L
        try {
            download(DET_URL, detFile) {
                done += it
                _state.value = OcrModelState.Downloading(done, TOTAL_BYTES)
            }
            download(REC_URL, recFile) {
                done += it
                _state.value = OcrModelState.Downloading(done, TOTAL_BYTES)
            }
            download(DICT_URL, dictFile) {
                done += it
                _state.value = OcrModelState.Downloading(done, TOTAL_BYTES)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = OcrModelState.Error(e.message ?: "Falha no download")
            return@withContext false
        }
        _state.value = OcrModelState.Ready
        true
    }

    private fun download(url: String, dest: File, onChunk: (Long) -> Unit) {
        val tmp = File(dest.parent, dest.name + ".tmp")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code em $url")
            }
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onChunk(read.toLong())
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            connection.disconnect()
            if (!dest.exists()) tmp.delete()
        }
    }

    companion object {
        private const val BASE_URL = "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/main"
        const val DET_URL = "$BASE_URL/det/manga_det_v0.1.onnx"
        const val REC_URL = "$BASE_URL/rec/manga_rec_v0.1.onnx"
        const val DICT_URL = "$BASE_URL/ppocrv6_dict.txt"

        /** Approximate total for progress display (det ~1.7MB + rec ~20.2MB + dict). */
        const val TOTAL_BYTES = 23_000_000L
    }
}
