package dev.idart.mangareader.ocronnx

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Bitmap helpers for the OCR pipeline (Fases 2–3). Everything runs on
 * ARGB_8888 copies; HARDWARE bitmaps are converted first.
 */
internal fun Bitmap.ensureReadable(): Bitmap {
    if (config != Bitmap.Config.HARDWARE && config != null) return this
    return copy(Bitmap.Config.ARGB_8888, false) ?: this
}

internal fun Bitmap.argbPixels(): IntArray {
    val readable = ensureReadable()
    val out = IntArray(readable.width * readable.height)
    readable.getPixels(out, 0, readable.width, 0, 0, readable.width, readable.height)
    if (readable !== this && !readable.isRecycled) readable.recycle()
    return out
}

internal fun Bitmap.scaledTo(width: Int, height: Int): Bitmap {
    if (width == this.width && height == this.height) return this
    return Bitmap.createScaledBitmap(this, width, height, true)
}

/**
 * Rotates 90° counter-clockwise (vertical Japanese text → horizontal line
 * for the line recognizer).
 */
internal fun Bitmap.rotateCcw(): Bitmap {
    val matrix = Matrix().apply { postRotate(-90f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

internal fun roundToMultipleOf32(value: Int): Int {
    return maxOf(32, ((value + 16) / 32) * 32)
}
