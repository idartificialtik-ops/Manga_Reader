package dev.idart.mangareader.ui.ai

import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.source.model.SMangaImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Tools exposed to the manga assistant: real search across the user's
 * installed sources, favorites, details and opening manga.
 */
class AgentTools(
    private val browse: BrowseSourceCatalogue,
    private val sourceManager: SourceManager,
    private val ensureMangaInDb: EnsureMangaInDb,
    private val mangaRepository: MangaRepository,
    private val client: AiClient,
) {

    fun definitions(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "search_manga",
                    description = "Busca mangás pelo título em TODAS as fontes instaladas. Use para qualquer pedido de recomendação, busca ou filtragem.",
                    parameters = client.toolJson(
                        mapOf(
                            "query" to ("string" to "Título ou termo buscado"),
                            "lang" to ("string" to "Filtro opcional de idioma, ex: pt-BR, en"),
                            "status" to ("string" to "Status: ongoing, completed, hiatus, cancelled, finished, licensed"),
                            "genre" to ("string" to "Gênero, ex: romance, ação"),
                            "minChapters" to ("integer" to "Mínimo de capítulos"),
                            "maxChapters" to ("integer" to "Máximo de capítulos"),
                        ),
                        requiredExtra = listOf("query"),
                    ),
                ),
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "list_favorites",
                    description = "Lista os mangás favoritados na biblioteca do usuário.",
                    parameters = client.toolJson(emptyMap()),
                ),
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "manga_details",
                    description = "Detalhes de um mangá da biblioteca: sinopse, gêneros e progresso de leitura.",
                    parameters = client.toolJson(
                        mapOf("mangaId" to ("integer" to "Id local do mangá")),
                        requiredExtra = listOf("mangaId"),
                    ),
                ),
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "add_to_library",
                    description = "Adiciona um resultado da última busca à biblioteca (favorita). O usuário diz o número do item na lista.",
                    parameters = client.toolJson(
                        mapOf("index" to ("integer" to "Número do item na última lista de resultados, começando em 1")),
                        requiredExtra = listOf("index"),
                    ),
                ),
            ),
        )
    }

    /** Últimos resultados do search_manga (para add_to_library por número). */
    private var lastHits: List<MangaHit> = emptyList()

    /** sourceId -> instante da última falha (cooldown de rede). */
    private val sourceFailures = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    suspend fun execute(name: String, argsJson: String): Pair<String, List<MangaHit>> {
        return when (name) {
            "search_manga" -> {
                val query = client.argString(argsJson, "query") ?: return "query ausente" to emptyList()
                val lang = client.argString(argsJson, "lang")
                val status = client.argString(argsJson, "status")?.let(::statusCode)
                val genre = client.argString(argsJson, "genre")?.trim()?.takeIf { it.isNotBlank() }
                val minChapters = client.argLong(argsJson, "minChapters")?.toInt()
                val maxChapters = client.argLong(argsJson, "maxChapters")?.toInt()
                val (hits, failedSources) = searchAllWithStats(query, lang, status, genre, minChapters, maxChapters)
                android.util.Log.d(
                    "AgentTools",
                    "search_manga q=\"$query\" hits=${hits.size} failedSources=$failedSources",
                )
                lastHits = hits.map { it.hit }
                val summary = if (hits.isEmpty()) {
                    if (failedSources > 0) {
                        "Nenhum resultado para \"$query\" ($failedSources fonte(s) falharam — rede/VPN?)."
                    } else {
                        "Nenhum resultado para \"$query\"."
                    }
                } else {
                    hits.joinToString("\n") { it.label }
                }
                summary to hits.map { it.hit }
            }
            "list_favorites" -> {
                val favs = runCatching { mangaRepository.getFavorites() }.getOrDefault(emptyList())
                if (favs.isEmpty()) {
                    "Biblioteca vazia." to emptyList()
                } else {
                    favs.take(30).joinToString("\n") { "- ${it.title} (id ${it.id})" } to emptyList()
                }
            }
            "manga_details" -> {
                val id = client.argLong(argsJson, "mangaId")
                    ?: return "mangaId ausente" to emptyList()
                val manga = runCatching { mangaRepository.getMangaById(id) }.getOrNull()
                    ?: return "Mangá $id não encontrado" to emptyList()
                "${manga.title}\n${manga.description?.take(600) ?: "Sem sinopse"}\n" +
                    "Gêneros: ${manga.genre?.joinToString() ?: "-"}" to emptyList()
            }
            "add_to_library" -> {
                val index = client.argLong(argsJson, "index")?.toInt()
                    ?: return "index ausente" to emptyList()
                val hit = lastHits.getOrNull(index - 1)
                    ?: return "Índice inválido: a última busca tem ${lastHits.size} resultado(s)." to emptyList()
                return try {
                    val id = openHit(hit)
                    mangaRepository.update(MangaUpdate(id = id, favorite = true))
                    "“${hit.title}” adicionado à biblioteca." to emptyList()
                } catch (e: Throwable) {
                    "Falha ao adicionar: ${e.message}" to emptyList()
                }
            }
            else -> "Ferramenta desconhecida: $name" to emptyList()
        }
    }

    private suspend fun searchAllWithStats(
        query: String,
        lang: String?,
        status: Int? = null,
        genre: String? = null,
        minChapters: Int? = null,
        maxChapters: Int? = null,
    ): Pair<List<RichHit>, Int> {
        val sources = sourceManager.getCatalogueSources()
            .filter { lang.isNullOrBlank() || it.lang.equals(lang, ignoreCase = true) }
            .filterNot { isSourceCoolingDown(it.id) }
        val results: List<Result<List<MangaHit>>> = supervisorScope {
            val semaphore = Semaphore(8)
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            withTimeout(SEARCH_TIMEOUT_MS) {
                                browse.search(source.id, 1, query, FilterList()).mangas.take(4).map {
                                    MangaHit(
                                        sourceId = source.id,
                                        sourceName = source.name,
                                        url = it.url,
                                        title = it.title,
                                        thumbnailUrl = it.thumbnail_url,
                                        genre = it.genre,
                                        status = it.status,
                                    )
                                }
                            }
                        }.onSuccess {
                            markSourceOk(source.id)
                        }.onFailure {
                            markSourceFailed(source.id)
                        }
                    }
                }
            }.awaitAll()
        }
        val failed = results.count { it.isFailure }
        val base = results.mapNotNull { it.getOrNull() }.flatten().take(30)
        if (status == null && genre == null && minChapters == null && maxChapters == null) {
            return base.map { RichHit(it, "- ${it.title} [${it.sourceName}]") } to failed
        }
        // Details-backed refine on the top candidates (bounded cost).
        val needChapters = minChapters != null || maxChapters != null
        val refined = supervisorScope {
            val semaphore = Semaphore(6)
            base.take(REFINE_LIMIT).map { hit ->
                async {
                    semaphore.withPermit {
                        refineHit(hit, status, genre, minChapters, maxChapters, needChapters)
                    }
                }
            }.awaitAll().filterNotNull().take(10)
        }
        return refined to failed
    }

    /**
     * Fontes que acabaram de falhar (rede/ISP) entram em cooldown: evita
     * que toda tool repita o timeout do mesmo host bloqueado.
     */
    private fun isSourceCoolingDown(sourceId: Long): Boolean {
        val at = sourceFailures[sourceId] ?: return false
        return System.currentTimeMillis() - at < SOURCE_COOLDOWN_MS
    }

    private fun markSourceFailed(sourceId: Long) {
        sourceFailures[sourceId] = System.currentTimeMillis()
    }

    private fun markSourceOk(sourceId: Long) {
        sourceFailures.remove(sourceId)
    }

    private suspend fun refineHit(
        hit: MangaHit,
        status: Int?,
        genre: String?,
        minChapters: Int?,
        maxChapters: Int?,
        needChapters: Boolean,
    ): RichHit? {
        val source = runCatching { browse.getSource(hit.sourceId) }.getOrNull() ?: return null
        val stub = SMangaImpl().also {
            it.url = hit.url
            it.title = hit.title
        }
        val details = runCatching {
            withTimeout(DETAILS_TIMEOUT_MS) { source.getMangaDetails(stub) }
        }.getOrNull() ?: return null
        val realStatus = if (details.status != SManga.UNKNOWN) details.status else hit.status
        if (status != null && realStatus != status) return null
        val genres = (details.genre ?: hit.genre)?.split(",").orEmpty().map { it.trim() }
        if (genre != null && genres.none { it.contains(genre, ignoreCase = true) }) return null
        var chapters: Int? = null
        if (needChapters) {
            chapters = runCatching {
                withTimeout(DETAILS_TIMEOUT_MS) { source.getChapterList(stub).size }
            }.getOrNull() ?: return null
            if (minChapters != null && chapters < minChapters) return null
            if (maxChapters != null && chapters > maxChapters) return null
        }
        val meta = buildList {
            statusLabel(realStatus)?.let(::add)
            chapters?.let { add("$it caps") }
            if (genres.isNotEmpty()) add(genres.take(3).joinToString())
        }
        val label = "- ${hit.title} [${hit.sourceName}]" +
            (if (meta.isEmpty()) "" else " (${meta.joinToString(" · ")})")
        return RichHit(hit, label)
    }

    private data class RichHit(
        val hit: MangaHit,
        val label: String,
    )

    suspend fun openHit(hit: MangaHit): Long {
        val network = SMangaImpl().also {
            it.url = hit.url
            it.title = hit.title
            it.thumbnail_url = hit.thumbnailUrl
        }
        return ensureMangaInDb.await(network, hit.sourceId)
    }

    /**
     * Best network hit for a title across catalogue sources (used by the
     * Feed to resolve recommendations into openable manga). Prefers
     * title-similar candidates; [author] disambiguates the query.
     */
    suspend fun findTopHit(query: String, author: String? = null): MangaHit? {
        val queries = listOfNotNull(
            author?.takeIf { it.isNotBlank() }?.let { "$query $it" },
            query,
        )
        val candidates = queries.flatMap { findHits(it, 3) }
            .distinctBy { it.sourceId to it.url }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { scoreMatch(it.title, query) }
    }

    /**
     * Real title, genres and synopsis of a network hit (grounds Feed
     * reasons on the actual work instead of the model's guess).
     */
    suspend fun hitDetails(hit: MangaHit): Triple<String, String, String>? {
        return runCatching {
            val source = browse.getSource(hit.sourceId)
            val stub = SMangaImpl().also {
                it.url = hit.url
                it.title = hit.title
            }
            val details = withTimeout(DETAILS_TIMEOUT_MS) { source.getMangaDetails(stub) }
            val genres = details.genre?.takeIf { it.isNotBlank() } ?: hit.genre.orEmpty()
            Triple(details.title.takeIf { it.isNotBlank() } ?: hit.title, genres, details.description.orEmpty())
        }.getOrNull()
    }

    private suspend fun findHits(query: String, perSource: Int): List<MangaHit> {
        val sources = sourceManager.getCatalogueSources()
            .filterNot { isSourceCoolingDown(it.id) }
        return supervisorScope {
            val semaphore = Semaphore(8)
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            withTimeout(SEARCH_TIMEOUT_MS) {
                                browse.search(source.id, 1, query, FilterList()).mangas.take(perSource).map {
                                    MangaHit(
                                        sourceId = source.id,
                                        sourceName = source.name,
                                        url = it.url,
                                        title = it.title,
                                        thumbnailUrl = it.thumbnail_url,
                                        genre = it.genre,
                                        status = it.status,
                                    )
                                }
                            }
                        }.onSuccess {
                            markSourceOk(source.id)
                        }.onFailure {
                            markSourceFailed(source.id)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().take(12)
        }
    }

    /**
     * Resolve titles cited in the assistant's text into real catalogue hits,
     * so recommendations are tappable even when the model answers from its
     * own knowledge instead of calling `search_manga`.
     */
    suspend fun resolveTitlesFrom(text: String, limit: Int = 6): List<MangaHit> {
        val candidates = extractCandidateTitles(text).take(limit)
        if (candidates.isEmpty()) return emptyList()
        return supervisorScope {
            val semaphore = Semaphore(3)
            candidates.map { title ->
                async { semaphore.withPermit { findTopHit(title) } }
            }.awaitAll().filterNotNull()
                .distinctBy { it.sourceId to it.url }
                .sortedBy { hit -> candidates.indexOfFirst { sameTitle(it, hit.title) }.takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .take(limit)
        }
    }

    private fun sameTitle(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val na = norm(a)
        val nb = norm(b)
        return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
    }

    /** Títulos de listas numeradas (1. Título) ou em negrito (**Título**). */
    private fun extractCandidateTitles(text: String): List<String> {
        val numbered = Regex("""(?m)^\s*\d+[.)]\s+(.+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        val bold = Regex("""\*\*([^*\n]{3,80})\*\*""")
            .findAll(text).map { it.groupValues[1] }.toList()
        return (numbered + bold)
            .map { raw ->
                raw
                    .replace(Regex("""\(Fonte:.*?\)""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*-\s*(Um|Uma|O|A|E).*$"""), "")
                    .replace(Regex("""\s*[-–—]\s.*$"""), "")
                    .trim().trim('"', '\'', '*', '.', ',', ';')
            }
            .filter { it.length in 3..80 }
            .distinct()
    }

    private fun scoreMatch(candidate: String, want: String): Int {
        fun norm(s: String) = s.lowercase().trim()
        val c = norm(candidate)
        val w = norm(want)
        var score = 0
        if (c == w) score += 10
        if (c.contains(w) || w.contains(c)) score += 5
        val tokens: (String) -> Set<String> = { s ->
            s.split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        }
        score += tokens(c).intersect(tokens(w)).size
        return score
    }

    companion object {
        private const val REFINE_LIMIT = 12
        private const val DETAILS_TIMEOUT_MS = 10_000L
        private const val SEARCH_TIMEOUT_MS = 8_000L
        private const val SOURCE_COOLDOWN_MS = 300_000L

        const val SYSTEM_PROMPT = "Você é o assistente do app de leitura de mangás. " +
            "Responda sempre em português. Use a ferramenta search_manga para qualquer " +
            "pedido de busca, recomendação ou filtragem. Ela aceita status " +
            "(ongoing, completed, hiatus, cancelled, finished, licensed), genre " +
            "(ex.: romance, ação) e minChapters/maxChapters — use-os quando o usuário " +
            "pedir filtrado. " +
            "Para pedidos baseados na biblioteca: chame list_favorites UMA vez, " +
            "depois no máximo 2 search_manga, e responda — nunca repita a mesma " +
            "chamada. " +
            "Numere os resultados a partir de 1 e ao final diga: o usuário pode " +
            "tocar num item para abrir e favoritar, ou pedir \"adiciona o número N\" " +
            "(use add_to_library com o índice). " +
            "Cite de qual fonte veio cada indicação. Seja conciso: liste título + fonte + 1 frase sobre cada um."
    }
}

/** Maps assistant status words to [SManga] codes. Null = unrecognized. */
private fun statusCode(raw: String): Int? {
    return when (raw.trim().lowercase()) {
        "ongoing", "em andamento", "em_andamento", "publicando", "produção", "producao" -> SManga.ONGOING
        "completed", "complete", "concluído", "concluido", "completo", "completa" -> SManga.COMPLETED
        "hiatus", "hiato" -> SManga.ON_HIATUS
        "cancelled", "canceled", "cancelado" -> SManga.CANCELLED
        "finished", "finalizado", "finalizada" -> SManga.PUBLISHING_FINISHED
        "licensed", "licenciado" -> SManga.LICENSED
        else -> null
    }
}

private fun statusLabel(code: Int): String? {
    return when (code) {
        SManga.ONGOING -> "Em andamento"
        SManga.COMPLETED -> "Concluído"
        SManga.ON_HIATUS -> "Hiato"
        SManga.CANCELLED -> "Cancelado"
        SManga.PUBLISHING_FINISHED -> "Finalizado"
        SManga.LICENSED -> "Licenciado"
        else -> null
    }
}
