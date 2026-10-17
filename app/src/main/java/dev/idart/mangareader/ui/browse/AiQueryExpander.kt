package dev.idart.mangareader.ui.browse

import android.util.Log
import dev.idart.mangareader.ui.ai.AiClient
import dev.idart.mangareader.ui.ai.AiSettings
import dev.idart.mangareader.ui.ai.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Locale

/**
 * Turns what the user typed into several queries to fan out across sources.
 *
 * A single literal query is weak on manga sites: each one indexes its own way
 * (romaji, accented titles, abbreviations, the original language), so the
 * literal term alone often returns nothing. The model proposes variants in
 * different registers and languages, and every source is searched with all of
 * them in parallel.
 *
 * The literal query is always variant zero — the AI only *adds* searches, it
 * never replaces what the user asked for. If the provider is missing, slow or
 * returns garbage, [fallbackVariants] keeps the feature useful offline.
 */
class AiQueryExpander(
    private val aiSettings: AiSettings,
    private val aiClient: AiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** One query to run, with a short human label for the UI. */
    data class Variant(
        val label: String,
        val query: String,
        val fromAi: Boolean = false,
    )

    private val cache = LinkedHashMap<String, List<Variant>>()

    /**
     * @param extraVariants how many queries to add on top of the literal one.
     */
    suspend fun expand(query: String, extraVariants: Int = DEFAULT_EXTRA): List<Variant> {
        val literal = query.trim()
        if (literal.isEmpty()) return emptyList()
        val key = "$literal|$extraVariants"
        synchronized(cache) { cache[key] }?.let { return it }

        val ai = if (extraVariants <= 0) {
            emptyList()
        } else {
            runCatching { askAi(literal, extraVariants) }
                .onFailure { Log.w(TAG, "expansão falhou: ${it.message}") }
                .getOrDefault(emptyList())
        }
        val result = merge(literal, ai, extraVariants)
        synchronized(cache) {
            // Bounded cache: a long session would otherwise grow it forever.
            if (cache.size >= CACHE_LIMIT) cache.remove(cache.keys.first())
            cache[key] = result
        }
        return result
    }

    private suspend fun askAi(literal: String, extraVariants: Int): List<Variant> {
        aiSettings.refresh()
        val baseUrl = aiSettings.baseUrl.first().ifBlank { AiSettings.DEFAULT_BASE_URL }
        val key = aiSettings.apiKey.first()
        if (key.isBlank()) return emptyList()
        val model = aiSettings.model.first().ifBlank { AiSettings.DEFAULT_MODEL }

        val answer = withTimeoutOrNull(EXPAND_TIMEOUT_MS) {
            aiClient.chat(
                baseUrl = baseUrl,
                apiKey = key,
                model = model,
                jsonMode = true,
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "Você expande buscas de mangás para vários sites. " +
                            "Responda SOMENTE um array JSON com $extraVariants objetos " +
                            "{\"q\":\"termo de busca\",\"lang\":\"código ISO 639-1 do idioma\", " +
                            "\"style\":\"palavras-chave\" | \"título exato\" | \"sinônimo\" | " +
                            "\"sem acento\"}. Varie o IDIOMA (inclua o idioma original do título) " +
                            "e o estilo. Termos curtos, como sites indexam. Não repita o termo original.",
                    ),
                    ChatMessage(role = "user", content = "TERMO DO USUÁRIO:\n$literal"),
                ),
            ).content
        } ?: return emptyList()

        return parseVariants(answer, literal, extraVariants)
    }

    /** Tolerant parse: models wrap JSON in prose, code fences or use PT keys. */
    private fun parseVariants(answer: String?, literal: String, extraVariants: Int): List<Variant> {
        if (answer.isNullOrBlank()) return emptyList()
        val unfenced = answer
            .replace(Regex("```\\s*json", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
        val arrayText = unfenced.substringAfter('[', "")
            .substringBeforeLast(']', "")
            .takeIf { it.isNotEmpty() }?.let { "[$it]" } ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(arrayText) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val query = stringAny(obj, "q", "query", "termo", "busca", "titulo", "título")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val lang = stringAny(obj, "lang", "idioma", "language")
            val style = stringAny(obj, "style", "estilo")
            Variant(
                label = listOfNotNull(lang?.uppercase(Locale.ROOT), style).joinToString(" · ")
                    .ifBlank { query },
                query = query,
                fromAi = true,
            )
        }.filter { !sameAsLiteral(it.query, literal) }.take(extraVariants)
    }

    private fun stringAny(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Literal first, then the AI's, then local heuristics if there is room. */
    private fun merge(literal: String, ai: List<Variant>, extraVariants: Int): List<Variant> {
        val out = ArrayList<Variant>(extraVariants + 1)
        val seen = HashSet<String>()
        fun add(variant: Variant) {
            val norm = normalize(variant.query)
            if (norm.isEmpty() || !seen.add(norm)) return
            out += variant
        }
        add(Variant("original", literal))
        ai.forEach(::add)
        if (out.size <= extraVariants) {
            fallbackVariants(literal).forEach(::add)
        }
        return out
    }

    /**
     * Offline variants: the same term without accents, with punctuation
     * removed, and the significant words only. Sites disagree on exactly these
     * three details.
     */
    private fun fallbackVariants(literal: String): List<Variant> = buildList {
        val noAccents = stripAccents(literal)
        if (noAccents != literal) add(Variant("sem acento", noAccents))
        val noPunct = noAccents
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noPunct.isNotEmpty() && noPunct != noAccents) add(Variant("sem pontuação", noPunct))
        val keywords = noPunct.split(' ')
            .filter { word -> word.length >= 4 }
            .joinToString(" ")
        if (keywords.isNotEmpty() && keywords != noPunct) add(Variant("palavras-chave", keywords))
    }

    private fun sameAsLiteral(candidate: String, literal: String) =
        normalize(candidate) == normalize(literal)

    private fun normalize(value: String): String =
        stripAccents(value).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /** NFD + strip das marcas combinantes: "Açúcar" -> "acucar". */
    private fun stripAccents(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ç', 'c')
            .replace('Ç', 'C')

    private companion object {
        private const val TAG = "QueryExpander"

        /** Extra queries on top of the literal one. */
        const val DEFAULT_EXTRA = 4

        /** Generous for a small model, short enough to not block the search. */
        private val EXPAND_TIMEOUT_MS = 6_000L

        private const val CACHE_LIMIT = 60
    }
}
