package dev.idart.mangareader.ui.feed

import android.util.Log
import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.source.model.SMangaImpl
import dev.idart.mangareader.ui.ai.AgentTools
import dev.idart.mangareader.ui.ai.AiClient
import dev.idart.mangareader.ui.ai.AiSettings
import dev.idart.mangareader.ui.ai.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the recommendation Feed from the user's read library.
 *
 * The model decides: first it answers whether it wants a web search
 * (`{"action":"search","query":"..."}`) or straight knowledge
 * (`{"action":"recommend"}`). With an Exa key configured the search runs
 * and its results feed the final recommendation call.
 */
class FeedUpdater(
    private val feedRepository: FeedRepository,
    private val aiSettings: AiSettings,
    private val aiClient: AiClient,
    private val exaSettings: ExaSettings,
    private val exaClient: ExaClient,
    private val mangaRepository: MangaRepository,
    private val tools: AgentTools,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun refresh() = withContext(Dispatchers.IO) {
        feedRepository.setRefreshing(true)
        try {
            val items = withTimeout(300_000L) { buildFeed() }
            feedRepository.saveItems(items)
            feedRepository.markRefreshed()
            Log.i(TAG, "feed atualizado: ${items.size} itens")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.w(TAG, "feed falhou: ${e.message}")
            val message = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                "Tempo esgotado (5 min). Tente de novo ou verifique a rede do aparelho."
            } else {
                e.message ?: "Falha ao atualizar o feed"
            }
            feedRepository.saveError(message)
        } finally {
            feedRepository.setRefreshing(false)
        }
    }

    private suspend fun buildFeed(): List<FeedItem> {
        aiSettings.refresh()
        val baseUrl = aiSettings.baseUrl.first()
        val apiKey = aiSettings.apiKey.first()
        val model = aiSettings.model.first()
        if (apiKey.isBlank() && baseUrl == AiSettings.DEFAULT_BASE_URL) {
            error("Configure o provedor de IA para gerar o feed.")
        }
        val favorites = runCatching { mangaRepository.getFavorites() }.getOrDefault(emptyList())
        if (favorites.isEmpty()) {
            error("Favorite mangás na biblioteca para gerar recomendações.")
        }
        val profile = favorites.take(20).joinToString("\n") { manga ->
            val genres = manga.genre?.takeIf { it.isNotEmpty() }?.joinToString() ?: "gênero desconhecido"
            "- ${manga.title} ($genres)"
        }

        // Passo 1: o modelo decide pesquisar na web ou recomendar direto.
        Log.i(TAG, "feed: decidindo (perfil ${favorites.size} favs)")
        val decision = decide(baseUrl, apiKey, model, profile)
        val webContext = if (decision != null && exaSettings.apiKey.first().isNotBlank()) {
            runCatching {
                val results = withContext(Dispatchers.IO) {
                    exaSettings.refresh()
                    exaClient.search(exaSettings.apiKey.first(), decision, 8)
                }
                if (results.isEmpty()) {
                    ""
                } else {
                    "RESULTADOS DA WEB:\n" + results.joinToString("\n") { result ->
                        "- ${result.title}: ${result.text.take(300)}"
                    }
                }
            }.getOrDefault("")
        } else {
            ""
        }

        // Passo 2: recomendações finais em JSON.
        Log.i(TAG, "feed: recomendando (exa=${webContext.isNotBlank()})")
        val recs = recommend(baseUrl, apiKey, model, profile, webContext)
        if (recs.isEmpty()) error("O provedor não retornou recomendações.")
        Log.i(TAG, "feed: resolvendo ${recs.size} nas fontes (paralelo)")
        val resolved = supervisorScope {
            recs.take(MAX_ITEMS).map { rec ->
                async { resolve(rec) }
            }.awaitAll()
        }
        // Passo 3: ancora cada motivo na sinopse REAL da obra encontrada
        // (sem isso o motivo descreve outro mangá).
        val topGenres = favorites.mapNotNull { it.genre }.flatten()
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).map { it.key }
        Log.i(TAG, "feed: ancorando motivos")
        val reasons = groundReasons(baseUrl, apiKey, model, resolved, topGenres)
        return resolved.mapIndexed { index, item ->
            FeedItem(
                id = "${System.currentTimeMillis()}-$index-${item.rec.title.hashCode()}",
                title = item.realTitle,
                reason = reasons.getOrNull(index).takeUnless { it.isNullOrBlank() } ?: item.rec.reason,
                mangaId = item.mangaId,
                sourceId = item.hit?.sourceId,
                coverUrl = item.hit?.thumbnailUrl,
                query = item.rec.title,
            )
        }
    }

    /** Returns the Exa query, or null to recommend from knowledge. */
    private suspend fun decide(baseUrl: String, apiKey: String, model: String, profile: String): String? {
        val answer = runCatching {
            aiClient.chat(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "Você atualiza um feed de recomendações de mangás. " +
                            "Responda SOMENTE JSON: {\"action\":\"search\",\"query\":\"...\"} para pesquisar " +
                            "lançamentos/tendências na web, ou {\"action\":\"recommend\"} para recomendar " +
                            "direto do seu conhecimento. Prefira search quando quiser novidades.",
                    ),
                    ChatMessage(role = "user", content = "BIBLIOTECA DO USUÁRIO:\n$profile"),
                ),
                jsonMode = true,
            ).content?.trim()
        }.getOrNull().orEmpty()
        val obj = runCatching {
            val text = answer.substringAfter('{', "").substringBeforeLast('}', "")
                .takeIf { it.isNotEmpty() }?.let { "{$it}" } ?: return null
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null
        if (obj["action"]?.jsonPrimitive?.content != "search") return null
        val query = obj["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        // Amarra a pesquisa a quadrinhos asiáticos.
        return if (query.contains("manga", ignoreCase = true) ||
            query.contains("manhwa", ignoreCase = true) ||
            query.contains("manhua", ignoreCase = true)
        ) {
            query
        } else {
            "$query manga manhwa"
        }
    }

    private data class Rec(val title: String, val author: String, val reason: String)

    private data class Resolved(
        val rec: Rec,
        val hit: dev.idart.mangareader.ui.ai.MangaHit?,
        val mangaId: Long?,
        val realTitle: String,
        val genres: String,
        val synopsis: String,
    )

    private suspend fun recommend(
        baseUrl: String,
        apiKey: String,
        model: String,
        profile: String,
        webContext: String,
    ): List<Rec> {
        val contextBlock = if (webContext.isNotBlank()) {
            "\n$webContext\n"
        } else {
            "\nSem pesquisa web: use seu conhecimento.\n"
        }
        val system = "Você recomenda quadrinhos asiáticos em português brasileiro. " +
            "Regras duras: SOMENTE mangás, manhwas ou manhuas reais e publicados " +
            "(nada de novels, anime, filmes ou quadrinhos ocidentais). " +
            "Títulos exatos com autor. Responda SOMENTE um array JSON com 6 itens: " +
            "[{\"title\":\"...\",\"author\":\"...\",\"reason\":\"1 frase do porquê combina\"}]. " +
            "Varie gêneros próximos ao gosto. Evite repetir a biblioteca."
        val user = "BIBLIOTECA DO USUÁRIO:\n$profile$contextBlock"
        parseRecs(answer(baseUrl, apiKey, model, system, user))?.let { return it }
        // Retry: modelos pequenos às vezes vêm com prosa junto; nudge estrito.
        Log.i(TAG, "feed: retry com nudge estrito")
        return parseRecs(
            answer(
                baseUrl,
                apiKey,
                model,
                system,
                "$user\nResponda SOMENTE o array JSON, nada além disso.",
            ),
        ) ?: error("O provedor não retornou a lista no formato esperado.")
    }

    private suspend fun answer(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
    ): String {
        return aiClient.chat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user),
            ),
        ).content?.trim() ?: error("Resposta vazia do provedor.")
    }

    /**
     * Extrai o array JSON tolerando cercas ```json e prosa ao redor.
     * Null quando não há array parseável.
     */
    private fun parseRecs(answer: String): List<Rec>? {
        val items = extractJsonItems(answer)
        if (items.isEmpty()) {
            Log.w(TAG, "feed sem itens: ${answer.take(500)}")
            return null
        }
        val recs = items.mapNotNull { element ->
            runCatching {
                // Objeto {title, author, reason} — com tolerância a chaves em PT.
                val obj = element as? JsonObject
                if (obj != null) {
                    val title = obj.stringAny("title", "titulo", "título", "nome", "name")
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    return@mapNotNull Rec(
                        title,
                        obj.stringAny("author", "autor").orEmpty(),
                        obj.stringAny("reason", "motivo", "motivo", "descricao", "descrição").orEmpty(),
                    )
                }
                // String solta: só o título (motivo vem da sinopse depois).
                val title = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Rec(title, "", "")
            }.getOrNull()
        }
        if (recs.isEmpty()) {
            Log.w(TAG, "feed itens vazios: ${answer.take(500)}")
            return null
        }
        return recs
    }

    /**
     * Localiza os itens tolerando cercas ```json, prosa ao redor,
     * embrulhos {"recommendations": [...]} e até objetos com aspas
     * não-escapadas (parse por objeto: os bons passam, o quebrado cai).
     */
    private fun extractJsonItems(answer: String): List<JsonElement> {
        val unfenced = answer
            .replace(Regex("```\\s*json", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
        val arrayText = unfenced.substringAfter('[', missingDelimiterValue = "")
            .substringBeforeLast(']', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }?.let { "[$it]" } ?: return emptyList()
        // 1) Array íntegro.
        runCatching { json.parseToJsonElement(arrayText) as? JsonArray }.getOrNull()?.let { array ->
            if (array.size == 1) {
                val single = array[0] as? JsonObject
                if (single != null) {
                    for (key in listOf("recommendations", "obras", "items", "mangas", "results")) {
                        (single[key] as? JsonArray)?.let { return it.toList() }
                    }
                }
            }
            return array.toList()
        }
        // 2) Caiu por 1 objeto quebrado: extrai cada {...} e parseia separado.
        val items = mutableListOf<JsonElement>()
        var depth = 0
        var start = -1
        for (i in arrayText.indices) {
            when (arrayText[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching {
                            json.parseToJsonElement(arrayText.substring(start, i + 1))
                        }.getOrNull()?.let { items.add(it) }
                        start = -1
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Log.w(TAG, "feed array inválido: ${arrayText.take(500)}")
        } else {
            Log.i(TAG, "feed: ${items.size} itens aproveitados (1+ quebrado ignorado)")
        }
        return items
    }

    private fun JsonObject.stringAny(vararg keys: String): String? {
        for (key in keys) {
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private suspend fun resolve(rec: Rec): Resolved {
        val hit = runCatching { tools.findTopHit(rec.title, rec.author) }.getOrNull()
        val mangaId = hit?.let {
            runCatching {
                ensureMangaInDb.await(
                    SMangaImpl().also { stub ->
                        stub.url = it.url
                        stub.title = it.title
                        stub.thumbnail_url = it.thumbnailUrl
                    },
                    it.sourceId,
                )
            }.getOrNull()
        }
        val details = hit?.let { runCatching { tools.hitDetails(it) }.getOrNull() }
        return Resolved(
            rec = rec,
            hit = hit,
            mangaId = mangaId,
            realTitle = details?.first ?: hit?.title ?: rec.title,
            genres = details?.second.orEmpty(),
            synopsis = details?.third.orEmpty(),
        )
    }

    /**
     * Reescreve os motivos com base nas sinopses reais. Devolve na mesma
     * ordem; em qualquer falha mantém os motivos originais.
     */
    private suspend fun groundReasons(
        baseUrl: String,
        apiKey: String,
        model: String,
        resolved: List<Resolved>,
        topGenres: List<String>,
    ): List<String> {
        val withSynopsis = resolved.filter { it.synopsis.isNotBlank() }
        if (withSynopsis.isEmpty()) return resolved.map { it.rec.reason }
        val taste = topGenres.takeIf { it.isNotEmpty() }?.joinToString() ?: "os gêneros da biblioteca"
        val numbered = withSynopsis.mapIndexed { i, item ->
            "${i + 1}. ${item.realTitle} (${item.genres}): ${item.synopsis.take(500)}"
        }.joinToString("\n")
        val answer = runCatching {
            answer(
                baseUrl,
                apiKey,
                model,
                "Você escreve motivos curtos em português brasileiro. " +
                    "Responda SOMENTE um array JSON de strings, na mesma ordem, " +
                    "1 frase por obra explicando por que combina com quem gosta de " +
                    "$taste. Baseie-se SOMENTE na sinopse dada.",
                "OBRAS:\n$numbered",
            )
        }.getOrNull() ?: return resolved.map { it.rec.reason }
        val rewritten = parseStringArray(answer)
        if (rewritten.size != withSynopsis.size) return resolved.map { it.rec.reason }
        var cursor = 0
        return resolved.map { item ->
            if (item.synopsis.isBlank()) {
                item.rec.reason
            } else {
                rewritten[cursor++]
            }
        }
    }

    private fun parseStringArray(answer: String): List<String> {
        return extractJsonItems(answer).mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    companion object {
        private const val TAG = "FeedUpdater"
        private const val MAX_ITEMS = 6
    }
}
