package dev.idart.mangareader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.idart.mangareader.ocronnx.OnnxMangaOcr
import dev.idart.mangareader.sources.download.DownloadManager
import dev.idart.mangareader.ui.ai.AiClient
import dev.idart.mangareader.ui.ai.AiSettings
import dev.idart.mangareader.ui.ai.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A translated text block in image-pixel coordinates.
 */
@Serializable
data class TranslatedBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val original: String,
    val translated: String,
)

@Serializable
private data class TranslatedPage(
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<TranslatedBlock>,
    val version: Int = 0,
)

sealed interface PageTranslation {
    data object Idle : PageTranslation
    data object Working : PageTranslation
    data class Done(
        val imageWidth: Int,
        val imageHeight: Int,
        val blocks: List<TranslatedBlock>,
    ) : PageTranslation
    data class Error(val message: String) : PageTranslation
}

/**
 * On-device manga page translator: ML Kit OCR (JA → KO → ZH → Latin) +
 * language ID + on-device translation to Portuguese, cached per page on disk.
 */
class PageTranslator(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val onnx: OnnxMangaOcr,
    private val aiSettings: AiSettings,
    private val aiClient: AiClient,
    private val translatorSettings: TranslatorSettings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val ocrClients by lazy {
        listOf(
            "ja" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            "ko" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            "zh" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            "lat" to TextRecognition.getClient(TextRecognizerOptions.Builder().build()),
        )
    }

    suspend fun cached(mangaId: Long, chapterId: Long, index: Int): PageTranslation.Done? {
        return withContext(Dispatchers.IO) {
            val file = cacheFile(mangaId, chapterId, index)
            if (!file.exists()) {
                Log.d(TAG, "cache miss $mangaId/$chapterId/$index")
                return@withContext null
            }
            runCatching {
                val page = json.decodeFromString(TranslatedPage.serializer(), file.readText())
                if (page.version != CACHE_VERSION) {
                    Log.d(TAG, "cache stale $mangaId/$chapterId/$index")
                    return@runCatching null
                }
                Log.d(TAG, "cache hit $mangaId/$chapterId/$index (${page.blocks.size} blocos)")
                PageTranslation.Done(page.imageWidth, page.imageHeight, page.blocks)
            }.getOrNull()
        }
    }

    suspend fun translate(
        mangaId: Long,
        chapterId: Long,
        index: Int,
        imageUrl: String?,
    ): PageTranslation {
        cached(mangaId, chapterId, index)?.let { return it }
        return try {
            val bitmap = loadBitmap(mangaId, chapterId, index, imageUrl)
                ?: return PageTranslation.Error("Imagem indisponível")
            val (blocks, hint) = ocr(bitmap)
            if (blocks.isEmpty()) {
                return PageTranslation.Error("Nenhum texto detectado")
            }
            val sourceLang = identifySource(blocks.joinToString("\n") { it.second }, hint)
            val translated = translateBlocks(blocks, sourceLang)
            val done = PageTranslation.Done(bitmap.width, bitmap.height, translated)
            save(mangaId, chapterId, index, done)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            done
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PageTranslation.Error(e.message ?: "Falha na tradução")
        }
    }

    private suspend fun loadBitmap(
        mangaId: Long,
        chapterId: Long,
        index: Int,
        imageUrl: String?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val local = downloadManager.pageFile(mangaId, chapterId, index)
            .takeIf { it.exists() && it.length() > 0 }
        if (local != null) {
            BitmapFactory.decodeFile(local.absolutePath)?.let { return@withContext it }
        }
        if (imageUrl.isNullOrBlank()) return@withContext null
        runCatching {
            val loader = coil3.SingletonImageLoader.get(context)
            val request = coil3.request.ImageRequest.Builder(context)
                .data(imageUrl)
                .build()
            val result = loader.execute(request) as? coil3.request.SuccessResult ?: return@runCatching null
            var bitmap = (result.image as? coil3.BitmapImage)?.bitmap ?: return@runCatching null
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
            }
            // Detach from Coil's pool: work on our own copy.
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }

    private suspend fun ocr(bitmap: Bitmap): Pair<List<Pair<Rect, String>>, String> {
        val engine = translatorSettings.ocrEngine.first()
        if (engine != OcrEngine.MLKIT) {
            onnxOcr(bitmap)?.let { return it }
            if (engine == OcrEngine.ONNX) {
                Log.w(TAG, "onnx sem resultado e ML Kit desativado nas configs")
                return emptyList<Pair<Rect, String>>() to "und"
            }
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        for ((hint, recognizer) in ocrClients) {
            val text = processImage(recognizer, image)
            val blocks = text.textBlocks.mapNotNull { block ->
                val box = block.boundingBox
                val content = block.text.trim()
                if (box == null || content.isBlank()) {
                    null
                } else {
                    box to content
                }
            }
            if (blocks.isNotEmpty()) return refineBlocks(bitmap, blocks, recognizer) to hint
        }
        return emptyList<Pair<Rect, String>>() to "und"
    }

    /**
     * Engine preferencial: PP-OCRv6_manga on-device. Baixa os modelos na
     * primeira vez; qualquer falha ou resultado vazio cai no ML Kit.
     * Retorna null quando o ML Kit deve assumir.
     */
    private suspend fun onnxOcr(bitmap: Bitmap): Pair<List<Pair<Rect, String>>, String>? {
        return try {
            if (!onnx.isReady()) {
                Log.i(TAG, "onnx: baixando modelos")
                if (!onnx.ensureModels()) return null
            }
            val boxes = onnx.detectBoxes(bitmap)
            Log.i(TAG, "onnx: ${boxes.size} caixas em ${bitmap.width}x${bitmap.height}")
            if (boxes.isEmpty()) return null
            val groups = groupLines(boxes)
            val out = mutableListOf<Pair<Rect, String>>()
            var lineCount = 0
            var blankCount = 0
            for (group in groups) {
                val lines = orderLines(group)
                val texts = lines.mapNotNull { box ->
                    lineCount++
                    // Mesmo enhance da via ML Kit: amplia + contraste ajuda o rec.
                    val crop = cropAndEnhance(
                        bitmap,
                        Rect(
                            box.left.toInt(),
                            box.top.toInt(),
                            box.right.toInt(),
                            box.bottom.toInt(),
                        ),
                    ) ?: return@mapNotNull null
                    try {
                        onnx.recognize(crop).trim().takeIf { it.isNotBlank() }
                    } finally {
                        if (!crop.isRecycled) crop.recycle()
                    }
                }
                blankCount += lines.size - texts.size
                if (texts.isEmpty()) continue
                val union = Rect(
                    group.minOf { it.left }.toInt(),
                    group.minOf { it.top }.toInt(),
                    group.maxOf { it.right }.toInt(),
                    group.maxOf { it.bottom }.toInt(),
                )
                out += union to texts.joinToString("\n")
            }
            if (out.isEmpty()) {
                Log.i(TAG, "onnx: nenhum texto, caindo no ML Kit")
                null
            } else {
                Log.i(TAG, "onnx: ${out.size} blocos (${groups.size} grupos, $lineCount linhas, $blankCount em branco)")
                out to "onnx"
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "onnx falhou, caindo no ML Kit: ${e.message}")
            null
        }
    }

    /**
     * Agrupa caixas de linha em balões. Conservador de propósito: une só o
     * que se sobrepõe de verdade (palavra partida) ou fragmentos lado a lado
     * na mesma linha. Nunca une por proximidade vertical — isso encadeava o
     * capítulo inteiro num bloco só.
     */
    private fun groupLines(boxes: List<RectF>): List<List<RectF>> {
        if (boxes.isEmpty()) return emptyList()
        val parent = IntArray(boxes.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val a = boxes[i]
                val b = boxes[j]
                val overlap = RectF(
                    maxOf(a.left, b.left),
                    maxOf(a.top, b.top),
                    minOf(a.right, b.right),
                    minOf(a.bottom, b.bottom),
                )
                val overlaps = overlap.width() > 0 && overlap.height() > 0
                // Fragmentos lado a lado na mesma linha.
                val verticalOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                val minH = minOf(a.height(), b.height())
                val horizontalGap = if (a.left <= b.left) b.left - a.right else a.left - b.right
                val sideBySide = minH > 0 && verticalOverlap > minH * 0.5f &&
                    horizontalGap < minH * 0.3f
                if (overlaps || sideBySide) union(i, j)
            }
        }
        return boxes.indices.groupBy { find(it) }.values.map { idx -> idx.map { boxes[it] } }
    }

    private fun orderLines(group: List<RectF>): List<RectF> {
        val left = group.minOf { it.left }
        val top = group.minOf { it.top }
        val right = group.maxOf { it.right }
        val bottom = group.maxOf { it.bottom }
        return if ((bottom - top) > (right - left) * 1.2f) {
            group.sortedByDescending { (it.left + it.right) / 2f }
        } else {
            group.sortedBy { (it.top + it.bottom) / 2f }
        }
    }

    private suspend fun processImage(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
    ): com.google.mlkit.vision.text.Text {
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /**
     * Segunda passada: recorta cada balão, amplia 2–4x com filtro + grayscale
     * e contraste, e roda o OCR de novo só no crop. Texto pequeno/pixelado
     * melhora muito; fica com o melhor dos dois.
     */
    private suspend fun refineBlocks(
        bitmap: Bitmap,
        blocks: List<Pair<Rect, String>>,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): List<Pair<Rect, String>> {
        return blocks.map { (box, oldText) ->
            val refined = runCatching {
                val crop = cropAndEnhance(bitmap, box) ?: return@runCatching null
                try {
                    val result = processImage(recognizer, InputImage.fromBitmap(crop, 0))
                    result.text.trim().takeIf { it.isNotBlank() }
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            }.getOrNull()
            if (refined != null && refined.length >= (oldText.length * 0.7f).toInt()) {
                box to refined
            } else {
                box to oldText
            }
        }
    }

    private fun cropAndEnhance(bitmap: Bitmap, box: Rect): Bitmap? {
        val padX = maxOf(4, ((box.right - box.left) * 0.08f).toInt())
        val padY = maxOf(4, ((box.bottom - box.top) * 0.08f).toInt())
        val left = (box.left - padX).coerceAtLeast(0)
        val top = (box.top - padY).coerceAtLeast(0)
        val right = (box.right + padX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + padY).coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        // Amplia até ~128px de altura (máx. 4x) e aplica grayscale + contraste.
        val factor = (128f / crop.height).coerceIn(1f, 4f)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * factor).toInt().coerceAtLeast(1),
            (crop.height * factor).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== crop && !crop.isRecycled) crop.recycle()
        val contrast = 1.5f
        val shift = 128f * (1f - contrast)
        val matrix = android.graphics.ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, shift,
                0f, contrast, 0f, 0f, shift,
                0f, 0f, contrast, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        matrix.preConcat(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (!scaled.isRecycled) scaled.recycle()
        return out
    }

    private suspend fun identifySource(text: String, hint: String): String {
        val sample = text.take(1000)
        val code = suspendCancellableCoroutine { cont ->
            LanguageIdentification.getClient().identifyLanguage(sample)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return when (code) {
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh" -> TranslateLanguage.CHINESE
            "en" -> TranslateLanguage.ENGLISH
            "pt" -> TranslateLanguage.PORTUGUESE
            else -> when (hint) {
                "ko" -> TranslateLanguage.KOREAN
                "zh" -> TranslateLanguage.CHINESE
                "lat" -> TranslateLanguage.ENGLISH
                else -> TranslateLanguage.JAPANESE
            }
        }
    }

    private suspend fun translateBlocks(
        blocks: List<Pair<Rect, String>>,
        sourceLang: String,
    ): List<TranslatedBlock> {
        if (sourceLang == TranslateLanguage.PORTUGUESE) {
            return blocks.map { (box, text) ->
                TranslatedBlock(box.left, box.top, box.right, box.bottom, text, text)
            }
        }
        val sources = blocks.map { it.second }
        val mode = translatorSettings.translationMode.first()
        if (mode == TranslationMode.MLKIT_ONLY) {
            val translated = mlKitTranslate(blocks, sources, sourceLang)
            return blocks.mapIndexed { index, (box, text) ->
                val out = translated.getOrNull(index).takeUnless { it.isNullOrBlank() } ?: text
                TranslatedBlock(box.left, box.top, box.right, box.bottom, text, out)
            }
        }
        // 1) IA configurada: traduz a página inteira com contexto + corrige OCR.
        val ai = runCatching { aiTranslate(sources, sourceLang) }.getOrNull()
        if (ai == null && mode == TranslationMode.AI_ONLY) {
            throw IllegalStateException(
                "Provedor de IA sem resposta. Confira em Mais → Assistente IA.",
            )
        }
        val translated: List<String> = if (ai != null && ai.size == sources.size) {
            Log.i(TAG, "tradução via IA (${ai.size} falas)")
            ai
        } else {
            // 2) ML Kit: uma chamada com a página toda (mais contexto que por
            // linha); se as quebras não baterem, cai no por-bloco.
            mlKitTranslate(blocks, sources, sourceLang)
        }
        return blocks.mapIndexed { index, (box, text) ->
            val out = translated.getOrNull(index).takeUnless { it.isNullOrBlank() } ?: text
            TranslatedBlock(box.left, box.top, box.right, box.bottom, text, out)
        }
    }

    /**
     * Traduz via provedor de IA configurado (OpenAI/DeepSeek/Groq/Ollama…).
     * Retorna null se não configurado ou se a resposta não vier no formato.
     */
    private suspend fun aiTranslate(texts: List<String>, sourceLang: String): List<String>? =
        withContext(Dispatchers.IO) {
            // Garante os valores do disco (cura qualquer leitura antiga).
            aiSettings.refresh()
            val baseUrl = aiSettings.baseUrl.first()
            val apiKey = aiSettings.apiKey.first()
            val model = aiSettings.model.first()
            if (apiKey.isBlank() && baseUrl == AiSettings.DEFAULT_BASE_URL) {
                Log.d(TAG, "IA pulada: provedor não configurado")
                return@withContext null
            }
            val langName = when (sourceLang) {
                TranslateLanguage.JAPANESE -> "japonês"
                TranslateLanguage.KOREAN -> "coreano"
                TranslateLanguage.CHINESE -> "chinês"
                else -> "inglês"
            }
            val numbered = texts.mapIndexed { i, text -> "${i + 1}. $text" }.joinToString("\n")
            val answer = try {
                aiClient.chat(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    messages = listOf(
                        ChatMessage(
                            role = "system",
                            content = "Você traduz falas de quadrinhos do $langName para português " +
                                "brasileiro natural e fluente. Mantenha nomes próprios como estão. " +
                                "Corrija pequenos erros de OCR quando óbvio pelo contexto. " +
                                "Responda SOMENTE com um array JSON de strings, na mesma ordem, " +
                                "uma tradução por fala.",
                        ),
                        ChatMessage(role = "user", content = numbered),
                    ),
                ).content?.trim()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "IA falhou, caindo no ML Kit: ${e.message}")
                return@withContext null
            }
            if (answer.isNullOrBlank()) {
                Log.w(TAG, "IA: resposta vazia, caindo no ML Kit")
                return@withContext null
            }
            // Tolerante: extrai o [...] mesmo com texto ao redor.
            val arrayText = answer.substringAfter('[', missingDelimiterValue = "")
                .substringBeforeLast(']', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() }?.let { "[$it]" }
            if (arrayText == null) {
                Log.w(TAG, "IA sem array JSON, caindo no ML Kit: ${answer.take(120)}")
                return@withContext null
            }
            val parsed = runCatching {
                (json.parseToJsonElement(arrayText) as? JsonArray)
                    ?.map { it.jsonPrimitive.content }
            }.getOrNull()
            if (parsed == null || parsed.size != texts.size) {
                Log.w(TAG, "IA retornou ${parsed?.size} falas para ${texts.size}, caindo no ML Kit")
                return@withContext null
            }
            parsed
        }

    /**
     * Recorta a tradução numerada "1) …\n2) …" de volta nas [count] falas.
     * Null se os marcadores não sobreviveram à tradução.
     */
    private fun splitNumbered(translated: String, count: Int): List<String>? {
        if (count == 0) return emptyList()
        val pattern = Regex("""(?m)^\s*(\d+)\)\s?""")
        val matches = pattern.findAll(translated).toList()
        if (matches.size != count) return null
        for (i in matches.indices) {
            if (matches[i].groupValues[1].toIntOrNull() != i + 1) return null
        }
        return matches.mapIndexed { i, match ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else translated.length
            translated.substring(match.range.last + 1, end).trim()
        }
    }

    private suspend fun mlKitTranslate(
        blocks: List<Pair<Rect, String>>,
        sources: List<String>,
        sourceLang: String,
    ): List<String> {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
        val translator = Translation.getClient(options)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            // Página inteira de uma vez, com marcadores numerados (o ML Kit
            // come as quebras de linha puras, mas costuma preservar "1) …").
            val numbered = sources.mapIndexed { index, text -> "${index + 1}) $text" }
                .joinToString("\n")
            val whole = suspendCancellableCoroutine<String> { cont ->
                translator.translate(numbered)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            splitNumbered(whole, sources.size)?.let { return it }
            Log.i(TAG, "ML Kit não preservou as falas, por-bloco")
            return blocks.map { (_, text) ->
                suspendCancellableCoroutine<String> { cont ->
                    translator.translate(text)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun save(mangaId: Long, chapterId: Long, index: Int, done: PageTranslation.Done) {
        withContext(Dispatchers.IO) {
            val file = cacheFile(mangaId, chapterId, index)
            file.parentFile?.mkdirs()
            runCatching {
                file.writeText(
                    json.encodeToString(
                        TranslatedPage.serializer(),
                        TranslatedPage(done.imageWidth, done.imageHeight, done.blocks, CACHE_VERSION),
                    ),
                )
            }
        }
    }

    private fun cacheFile(mangaId: Long, chapterId: Long, index: Int): File {
        return File(File(File(context.filesDir, "translations"), "$mangaId/$chapterId"), "$index.json")
    }

    companion object {
        private const val TAG = "PageTranslator"

        /** Bump to invalidate old cached translations. */
        private const val CACHE_VERSION = 4
    }
}
