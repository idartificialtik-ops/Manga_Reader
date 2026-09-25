package dev.idart.mangareader.ocronnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.roundToInt

private const val REC_HEIGHT = 48
private const val REC_MIN_WIDTH = 16
private const val CTC_BLANK = 0

/**
 * Fase 3: line recognition. Upright vertical crops are rotated to horizontal,
 * resized to height 48, normalized, then CTC greedy-decoded with [vocab].
 */
internal fun recognizeText(
    env: ai.onnxruntime.OrtEnvironment,
    session: OrtSession,
    vocab: List<String>,
    crop: Bitmap,
): String {
    val upright = if (crop.height > crop.width) crop.rotateCcw() else crop
    val targetW = maxOf(REC_MIN_WIDTH, (REC_HEIGHT.toFloat() * upright.width / maxOf(1, upright.height)).roundToInt())
    val resized = upright.scaledTo(targetW, REC_HEIGHT)
    val pixels = resized.argbPixels()
    if (resized !== upright && !resized.isRecycled) resized.recycle()
    if (upright !== crop && !upright.isRecycled) upright.recycle()

    val data = FloatArray(3 * REC_HEIGHT * targetW)
    for (y in 0 until REC_HEIGHT) {
        for (x in 0 until targetW) {
            val argb = pixels[y * targetW + x]
            val i = y * targetW + x
            data[i] = (((argb shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            data[REC_HEIGHT * targetW + i] = (((argb shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            data[2 * REC_HEIGHT * targetW + i] = ((argb and 0xFF) / 255f - 0.5f) / 0.5f
        }
    }

    val inputName = session.inputNames.first()
    val tensor = OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(data),
        longArrayOf(1, 3, REC_HEIGHT.toLong(), targetW.toLong()),
    )
    val shape: LongArray
    val logits: FloatArray = tensor.use {
        session.run(mapOf(inputName to it)).use { result ->
            val out = result[0] as OnnxTensor
            shape = out.info.shape
            out.readFloats()
        }
    }
    // Expected [1, T, V].
    if (shape.size != 3) return ""
    val timeSteps = shape[1].toInt()
    val vocabSize = shape[2].toInt()
    if (logits.size != timeSteps * vocabSize) return ""

    val text = StringBuilder()
    var previous = -1
    for (t in 0 until timeSteps) {
        var best = 0
        var bestScore = logits[t * vocabSize]
        for (v in 1 until vocabSize) {
            val score = logits[t * vocabSize + v]
            if (score > bestScore) {
                bestScore = score
                best = v
            }
        }
        if (best != CTC_BLANK && best != previous && best < vocab.size) {
            text.append(vocab[best])
        }
        previous = best
    }
    return text.toString().trim()
}
