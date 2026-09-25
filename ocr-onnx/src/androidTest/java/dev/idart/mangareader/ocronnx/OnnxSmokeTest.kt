package dev.idart.mangareader.ocronnx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test: downloads the FP16 models, inits ORT sessions and
 * runs det + rec on a real Japanese crop. Proves the Kotlin port + FP32 feed
 * into the FP16 graph work on ARM64.
 */
@RunWith(AndroidJUnit4::class)
class OnnxSmokeTest {

    @Test
    fun detectAndRecognizeJapaneseCrop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = OcrModelStore(context)
        assertTrue("download dos modelos", store.ensureModels())
        val ocr = OnnxMangaOcr(store)
        try {
            assertTrue("sessões ORT", ocr.initSessions())
            val bitmap = context.assets.open("page_jp.jpg").use { input ->
                BitmapFactory.decodeStream(input)
            } ?: error("asset ilegível")
            val stats = ocr.debugDetStats(bitmap)
            val boxes = ocr.detectBoxes(bitmap)
            assertTrue("nenhuma caixa detectada [$stats]", boxes.isNotEmpty())
            val results = boxes.sortedBy { it.top }.take(12).map { box ->
                val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
                val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
                val width = box.width().toInt().coerceIn(1, bitmap.width - left)
                val height = box.height().toInt().coerceIn(1, bitmap.height - top)
                val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
                val text = ocr.recognize(crop)
                "($left,$top ${width}x$height)='$text'"
            }
            assertTrue(
                "tudo vazio:\n${results.joinToString("\n")}",
                results.any { it.contains("='") && !it.endsWith("=''") },
            )
        } finally {
            ocr.close()
        }
    }
}
