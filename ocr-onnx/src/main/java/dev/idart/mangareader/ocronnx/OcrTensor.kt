package dev.idart.mangareader.ocronnx

import ai.onnxruntime.OnnxTensor
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * Reads an output tensor as floats. ORT Java returns nested arrays
 * (`float[][][]…`); FP16 graphs may come back as shorts/bytes, whose half
 * bits are converted manually.
 */
internal fun OnnxTensor.readFloats(): FloatArray {
    return flatten(value).toFloatArray()
}

private fun flatten(raw: Any?): List<Float> {
    return when (raw) {
        null -> emptyList()
        is FloatArray -> raw.toList()
        is Array<*> -> raw.flatMap { flatten(it) }
        is Float -> listOf(raw)
        is Double -> listOf(raw.toFloat())
        is ShortArray -> raw.map { halfToFloat(it.toInt()) }
        is ShortBuffer -> {
            val dup = raw.duplicate()
            List(dup.remaining()) { halfToFloat(dup.get().toInt()) }
        }
        is ByteBuffer -> {
            val dup = raw.duplicate()
            List(dup.remaining() / 2) {
                val lo = dup.get().toInt() and 0xFF
                val hi = dup.get().toInt() and 0xFF
                halfToFloat((hi shl 8) or lo)
            }
        }
        else -> error("Tipo de saída não suportado: ${raw.javaClass.name}")
    }
}

/** IEEE 754 binary16 → float. */
private fun halfToFloat(h: Int): Float {
    val bits = h and 0xFFFF
    val sign = (bits shr 15) and 0x1
    val exp = (bits shr 10) and 0x1F
    val mant = bits and 0x3FF
    val fbits = when {
        exp == 0 -> {
            if (mant == 0) {
                sign shl 31
            } else {
                var e = -14
                var m = mant
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                m = m and 0x3FF
                (sign shl 31) or ((e + 127) shl 23) or (m shl 13)
            }
        }
        exp == 31 -> (sign shl 31) or (0xFF shl 23) or (mant shl 13)
        else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
    }
    return Float.fromBits(fbits)
}
