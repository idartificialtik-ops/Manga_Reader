package dev.idart.mangareader.translate

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** OCR engine used to read text from the page. */
enum class OcrEngine(val label: String) {
    AUTO("Automático (ONNX → ML Kit)"),
    ONNX("ONNX (mangá)"),
    MLKIT("ML Kit (Google)"),
}

/** Translation backend. */
enum class TranslationMode(val label: String) {
    AUTO("Automática (IA → ML Kit)"),
    AI_ONLY("Só IA (provedor)"),
    MLKIT_ONLY("Só ML Kit (offline)"),
}

/**
 * Translator preferences. Plain prefs with synchronous [commit] so the
 * choice sticks even if the process dies right after saving.
 */
class TranslatorSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

    private val _ocrEngine = MutableStateFlow(readOcr())
    val ocrEngine: Flow<OcrEngine> = _ocrEngine.asStateFlow()

    private val _translationMode = MutableStateFlow(readMode())
    val translationMode: Flow<TranslationMode> = _translationMode.asStateFlow()

    fun setOcrEngine(value: OcrEngine) {
        prefs.edit().putString(KEY_OCR, value.name).commit()
        _ocrEngine.update { value }
    }

    fun setTranslationMode(value: TranslationMode) {
        prefs.edit().putString(KEY_MODE, value.name).commit()
        _translationMode.update { value }
    }

    private fun readOcr(): OcrEngine {
        return runCatching { OcrEngine.valueOf(prefs.getString(KEY_OCR, null) ?: "") }
            .getOrDefault(OcrEngine.AUTO)
    }

    private fun readMode(): TranslationMode {
        return runCatching { TranslationMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(TranslationMode.AUTO)
    }

    companion object {
        private const val KEY_OCR = "ocr_engine"
        private const val KEY_MODE = "translation_mode"
    }
}
