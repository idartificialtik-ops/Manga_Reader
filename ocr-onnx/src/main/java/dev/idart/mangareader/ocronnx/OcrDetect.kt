package dev.idart.mangareader.ocronnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.roundToInt

private const val DET_LONG_SIDE = 960
private const val BINARIZE_THRESH = 0.15f
private const val BOX_SCORE_THRESH = 0.25f

/** Dilation radius (det-scale px): stands in for DB unclip_ratio expansion. */
private const val DILATE_RADIUS = 3
private const val MIN_COMPONENT_PX = 16
private const val NMS_IOU = 0.3f

private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** Test/diagnostics: preprocess + run det, return output statistics. */
internal fun detStats(
    env: ai.onnxruntime.OrtEnvironment,
    session: OrtSession,
    bitmap: Bitmap,
): String {
    val pageW = bitmap.width
    val pageH = bitmap.height
    val ratio = DET_LONG_SIDE.toFloat() / maxOf(pageW, pageH)
    val rw = roundToMultipleOf32((pageW * ratio).roundToInt())
    val rh = roundToMultipleOf32((pageH * ratio).roundToInt())
    val scaled = bitmap.scaledTo(rw, rh)
    val pixels = scaled.argbPixels()
    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
    val data = FloatArray(3 * rh * rw)
    for (y in 0 until rh) {
        for (x in 0 until rw) {
            val argb = pixels[y * rw + x]
            val i = y * rw + x
            data[i] = (((argb shr 16) and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0]
            data[rh * rw + i] = (((argb shr 8) and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1]
            data[2 * rh * rw + i] = ((argb and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2]
        }
    }
    val inputName = session.inputNames.first()
    val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, rh.toLong(), rw.toLong()))
    return tensor.use {
        session.run(mapOf(inputName to it)).use { result ->
            val out = result[0] as OnnxTensor
            val shape = out.info.shape.joinToString("x")
            val arr = out.readFloats()
            var max = Float.NEGATIVE_INFINITY
            var sum = 0.0
            var over = 0
            for (v in arr) {
                if (v > max) max = v
                sum += v
                if (v > BINARIZE_THRESH) over++
            }
            "shape=$shape size=${arr.size} max=$max mean=${sum / arr.size} over=$over rw=$rw rh=$rh"
        }
    }
}

/**
 * Fase 2: text detection. Resize (long side 960, multiple of 32) → normalize →
 * ORT det → binarize → dilate (unclip substitute) → connected components →
 * score filter → NMS → merge colinear fragments → page coordinates.
 *
 * Páginas muito longas (tiras densas tipo Comic Fury) são fatiadas em tiles
 * de ~960px: o resize único some com o texto miúdo.
 */
internal fun detectBoxes(
    env: ai.onnxruntime.OrtEnvironment,
    session: OrtSession,
    bitmap: Bitmap,
): List<RectF> {
    val pageW = bitmap.width
    val pageH = bitmap.height
    if (pageW <= 0 || pageH <= 0) return emptyList()
    if (maxOf(pageW, pageH) > TILE_LONG_SIDE) {
        runCatching { detectTiled(env, session, bitmap) }
            .getOrNull()?.let { return it }
        // OOM ou crop inválido: cai no passe único.
    }
    return detectSingle(env, session, bitmap)
}

/** Tiles de ~960px ao longo do eixo maior, com overlap e zona exclusiva. */
private const val TILE_LONG_SIDE = 1700
private const val TILE_SIZE = 960
private const val TILE_OVERLAP = 128
private const val MAX_TILES = 6

private fun detectTiled(
    env: ai.onnxruntime.OrtEnvironment,
    session: OrtSession,
    bitmap: Bitmap,
): List<RectF> {
    val pageW = bitmap.width
    val pageH = bitmap.height
    val wide = pageW >= pageH
    val long = if (wide) pageW else pageH
    val stride = TILE_SIZE - TILE_OVERLAP
    val starts = mutableListOf<Int>()
    var s = 0
    while (s + TILE_SIZE < long) {
        starts.add(s)
        s += stride
    }
    starts.add((long - TILE_SIZE).coerceAtLeast(0))
    if (starts.size > MAX_TILES) return detectSingle(env, session, bitmap)

    val out = mutableListOf<RectF>()
    for ((i, start) in starts.withIndex()) {
        val last = i == starts.lastIndex
        // Zona exclusiva: cada caixa entra pelo tile onde está seu centro.
        val zoneStart = start.toFloat()
        val zoneEnd = if (last) long.toFloat() else (start + stride).toFloat()
        val tile = if (wide) {
            Bitmap.createBitmap(bitmap, start, 0, TILE_SIZE.coerceAtMost(pageW - start), pageH)
        } else {
            Bitmap.createBitmap(bitmap, 0, start, pageW, TILE_SIZE.coerceAtMost(pageH - start))
        }
        try {
            val boxes = detectSingle(env, session, tile)
            for (box in boxes) {
                val coord = if (wide) box.centerX() + start else box.centerY() + start
                if (coord < zoneStart || coord > zoneEnd) continue
                out.add(
                    if (wide) {
                        RectF(box.left + start, box.top, box.right + start, box.bottom)
                    } else {
                        RectF(box.left, box.top + start, box.right, box.bottom + start)
                    },
                )
            }
        } finally {
            if (!tile.isRecycled) tile.recycle()
        }
    }
    return out.sortedWith(compareBy({ it.top }, { it.left }))
}

private fun detectSingle(
    env: ai.onnxruntime.OrtEnvironment,
    session: OrtSession,
    bitmap: Bitmap,
): List<RectF> {
    val pageW = bitmap.width
    val pageH = bitmap.height
    if (pageW <= 0 || pageH <= 0) return emptyList()
    val ratio = DET_LONG_SIDE.toFloat() / maxOf(pageW, pageH)
    val rw = roundToMultipleOf32((pageW * ratio).roundToInt())
    val rh = roundToMultipleOf32((pageH * ratio).roundToInt())

    val scaled = bitmap.scaledTo(rw, rh)
    val pixels = scaled.argbPixels()
    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()

    val data = FloatArray(3 * rh * rw)
    for (y in 0 until rh) {
        for (x in 0 until rw) {
            val argb = pixels[y * rw + x]
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val i = y * rw + x
            data[i] = (r - DET_MEAN[0]) / DET_STD[0]
            data[rh * rw + i] = (g - DET_MEAN[1]) / DET_STD[1]
            data[2 * rh * rw + i] = (b - DET_MEAN[2]) / DET_STD[2]
        }
    }

    val inputName = session.inputNames.first()
    val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, rh.toLong(), rw.toLong()))
    val prob: FloatArray = tensor.use {
        session.run(mapOf(inputName to it)).use { result ->
            (result[0] as OnnxTensor).readFloats()
        }
    }
    // prob is [1,1,rh,rw] row-major.
    require(prob.size == rh * rw) { "Det output ${prob.size} != ${rh * rw}" }

    val mask = BooleanArray(rh * rw) { i -> prob[i] > BINARIZE_THRESH }
    val dilated = dilate(mask, rw, rh, DILATE_RADIUS)
    val components = connectedComponents(dilated, rw, rh)

    val boxes = mutableListOf<ScoredBox>()
    for (component in components) {
        if (component.size < MIN_COMPONENT_PX) continue
        var minX = rw
        var minY = rh
        var maxX = -1
        var maxY = -1
        var sum = 0.0
        for (i in component) {
            val x = i % rw
            val y = i / rw
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            sum += prob[i]
        }
        val score = (sum / component.size).toFloat()
        if (score < BOX_SCORE_THRESH) continue
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        if (minOf(w, h) < 3) continue
        boxes.add(
            ScoredBox(
                minX.toFloat() / rw * pageW,
                minY.toFloat() / rh * pageH,
                (maxX + 1).toFloat() / rw * pageW,
                (maxY + 1).toFloat() / rh * pageH,
                score,
            ),
        )
    }

    val kept = nonMaxSuppression(boxes)
    // Sem merge aqui: o rec é um reconhecedor de LINHA e lê fragmentos bem;
    // o agrupamento em balões (colinear + proximidade) acontece na Fase 4.
    return kept.map { it.toRectF() }.sortedWith(compareBy({ it.top }, { it.left }))
}

private data class ScoredBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
) {
    fun toRectF() = RectF(left, top, right, bottom)
}

/** Separable box-max dilation. */
private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
    if (radius <= 0) return mask
    val horizontal = BooleanArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var hit = false
            var dx = -radius
            while (dx <= radius && !hit) {
                val xx = x + dx
                if (xx in 0 until w && mask[y * w + xx]) hit = true
                dx++
            }
            horizontal[y * w + x] = hit
        }
    }
    val out = BooleanArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var hit = false
            var dy = -radius
            while (dy <= radius && !hit) {
                val yy = y + dy
                if (yy in 0 until h && horizontal[yy * w + x]) hit = true
                dy++
            }
            out[y * w + x] = hit
        }
    }
    return out
}

/** Iterative flood-fill labeling; returns pixel-index lists. */
private fun connectedComponents(mask: BooleanArray, w: Int, h: Int): List<IntArray> {
    val labels = IntArray(w * h) { -1 }
    val components = mutableListOf<IntArray>()
    val stack = IntArray(w * h)
    for (seed in mask.indices) {
        if (!mask[seed] || labels[seed] != -1) continue
        val label = components.size
        var top = 0
        stack[top++] = seed
        labels[seed] = label
        val pixels = mutableListOf<Int>()
        while (top > 0) {
            val i = stack[--top]
            pixels.add(i)
            val x = i % w
            val y = i / w
            top = pushNeighbor(mask, labels, label, stack, top, x > 0, i - 1)
            top = pushNeighbor(mask, labels, label, stack, top, x < w - 1, i + 1)
            top = pushNeighbor(mask, labels, label, stack, top, y > 0, i - w)
            top = pushNeighbor(mask, labels, label, stack, top, y < h - 1, i + w)
        }
        components.add(pixels.toIntArray())
    }
    return components
}

private fun pushNeighbor(
    mask: BooleanArray,
    labels: IntArray,
    label: Int,
    stack: IntArray,
    top: Int,
    inBounds: Boolean,
    i: Int,
): Int {
    if (inBounds && mask[i] && labels[i] == -1) {
        labels[i] = label
        stack[top] = i
        return top + 1
    }
    return top
}

private fun nonMaxSuppression(boxes: List<ScoredBox>): List<ScoredBox> {
    val sorted = boxes.sortedByDescending { it.score }
    val kept = mutableListOf<ScoredBox>()
    for (box in sorted) {
        if (kept.all { iou(box, it) < NMS_IOU }) kept.add(box)
    }
    return kept
}

private fun iou(a: ScoredBox, b: ScoredBox): Float {
    val ix1 = maxOf(a.left, b.left)
    val iy1 = maxOf(a.top, b.top)
    val ix2 = minOf(a.right, b.right)
    val iy2 = minOf(a.bottom, b.bottom)
    val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
    if (inter <= 0f) return 0f
    val union = (a.right - a.left) * (a.bottom - a.top) +
        (b.right - b.left) * (b.bottom - b.top) - inter
    return if (union <= 0f) 0f else inter / union
}
