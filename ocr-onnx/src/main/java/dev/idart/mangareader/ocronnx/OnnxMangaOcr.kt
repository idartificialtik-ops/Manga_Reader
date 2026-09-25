package dev.idart.mangareader.ocronnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Collections

/**
 * On-device manga OCR via PP-OCRv6_manga ONNX (det + rec, FP16).
 *
 * Fase 1: runtime wiring only — lazy ORT sessions + vocabulary loading.
 * Detection ([detectBoxes]) and recognition ([recognize]) land in Fases 2–3.
 */
class OnnxMangaOcr(
    private val store: OcrModelStore,
) {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var vocab: List<String>? = null

    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        store.isReady()
    }

    /**
     * Downloads the model files on first use. Returns false when the
     * download fails — callers must fall back to another engine.
     */
    suspend fun ensureModels(): Boolean {
        return store.ensureModels()
    }

    /**
     * Creates the ORT sessions (idempotent, thread-safe). Returns false when
     * the model files are missing — call [OcrModelStore.ensureModels] first.
     */
    suspend fun initSessions(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (detSession != null && recSession != null) return@withContext true
            if (!store.isReady()) return@withContext false
            val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1)
            }
            detSession = environment.createSession(store.detFile.absolutePath, options)
            recSession = environment.createSession(store.recFile.absolutePath, options)
            vocab = Collections.unmodifiableList(
                mutableListOf("blank").also { list ->
                    store.dictFile.forEachLine(Charsets.UTF_8) { line ->
                        list.add(line.trimEnd('\r', '\n'))
                    }
                    list.add(" ")
                },
            )
            true
        }
    }

    suspend fun vocabulary(): List<String> {
        mutex.withLock {
            vocab?.let { return it }
        }
        if (!initSessions()) error("Modelos OCR ausentes")
        return mutex.withLock { vocab ?: error("Vocabulário não carregado") }
    }

    /**
     * Test/diagnostics: preprocess + run det, return output statistics.
     */
    internal suspend fun debugDetStats(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        initSessions()
        val (det, _) = sessionsOrThrow()
        detStats(env ?: error("ORT ausente"), det, bitmap)
    }

    /**
     * Fase 2: detect text boxes, in page-pixel coordinates.
     */
    suspend fun detectBoxes(bitmap: Bitmap): List<android.graphics.RectF> = withContext(Dispatchers.IO) {
        initSessions()
        val (det, _) = sessionsOrThrow()
        detectBoxes(env ?: error("ORT ausente"), det, bitmap)
    }

    /**
     * Fase 3: recognize a single crop (vertical crops are rotated upright).
     */
    suspend fun recognize(crop: Bitmap): String = withContext(Dispatchers.IO) {
        initSessions()
        val (_, rec) = sessionsOrThrow()
        val words = vocabulary()
        recognizeText(env ?: error("ORT ausente"), rec, words, crop)
    }

    protected fun newFloatTensor(buffer: FloatBuffer, shape: LongArray): OnnxTensor {
        val environment = env ?: error("Sessões não inicializadas")
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    protected fun sessionsOrThrow(): Pair<OrtSession, OrtSession> {
        return (detSession ?: error("Sessão det ausente")) to (recSession ?: error("Sessão rec ausente"))
    }

    fun close() {
        detSession?.close()
        recSession?.close()
        detSession = null
        recSession = null
        vocab = null
    }
}
