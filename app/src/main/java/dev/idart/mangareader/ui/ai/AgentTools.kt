package dev.idart.mangareader.ui.ai

import dev.idart.mangareader.domain.manga.interactor.EnsureMangaInDb
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.domain.manga.repository.MangaRepository
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.source.model.SMangaImpl
import dev.idart.mangareader.sources.extension.ExtensionManager
import dev.idart.mangareader.sources.extension.repo.ExtensionInstaller
import dev.idart.mangareader.sources.extension.repo.ExtensionRepo
import dev.idart.mangareader.sources.extension.repo.ExtensionRepoStore
import dev.idart.mangareader.ui.browse.SourceHealthStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    private val extensionRepo: ExtensionRepo,
    private val extensionRepoStore: ExtensionRepoStore,
    private val extensionManager: ExtensionManager,
    private val extensionInstaller: ExtensionInstaller,
    /** Shared with the search screens: what one learns, the other uses. */
    private val health: SourceHealthStore,
) {

    /**
     * Destructive action waiting for the user's tap. The model cannot skip it:
     * [awaitDecision] suspends until the UI answers, and silence means "no".
     */
    data class PendingAction(
        val id: Long,
        val packageName: String,
        val label: String,
        val sourceCount: Int,
    )

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    /** Answered by the confirmation dialog. */
    fun resolvePendingAction(allow: Boolean) {
        val pending = _pendingAction.value ?: return
        decision?.complete(allow)
        decision = null
        _pendingAction.value = null
    }

    private var decision: CompletableDeferred<Boolean>? = null

    /** Suspends the tool until the user answers; silence counts as "no". */
    private suspend fun awaitDecision(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        return withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { deferred.await() } ?: false
    }

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
            ToolDefinition(
                function = FunctionDefinition(
                    name = "search_extensions",
                    description = "Procura extensões de fontes no catálogo configurado pelo usuário (ex.: Keiyoushi), " +
                        "por nome, pacote ou idioma. Use antes de instalar qualquer coisa.",
                    parameters = client.toolJson(
                        mapOf(
                            "query" to ("string" to "Nome da fonte, da extensão ou idioma (ex.: 'Scanlator BR', 'pt-BR')"),
                        ),
                        requiredExtra = listOf("query"),
                    ),
                ),
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "install_extension",
                    description = "Instala uma extensão pelo packageName EXATO devolvido por search_extensions. " +
                        "O Android pede a confirmação do usuário na tela de instalação. " +
                        "Nunca invente um packageName: se não veio da busca, não instale.",
                    parameters = client.toolJson(
                        mapOf("packageName" to ("string" to "Package exato da busca, ex.: eu.kanade.tachiyomi.extension.all.mangafire")),
                        requiredExtra = listOf("packageName"),
                    ),
                ),
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "uninstall_extension",
                    description = "Remove uma extensão INSTALADA. DESTRUTIVO: o app pede confirmação ao usuário " +
                        "antes de remover, e as configurações das fontes dela são apagadas. " +
                        "Só chame quando o usuário pedir explicitamente para desinstalar.",
                    parameters = client.toolJson(
                        mapOf("packageName" to ("string" to "Package da extensão instalada")),
                        requiredExtra = listOf("packageName"),
                    ),
                ),
            ),
        )
    }

    /** Últimos resultados do search_manga (para add_to_library por número). */
    private var lastHits: List<MangaHit> = emptyList()

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
            "search_extensions" -> {
                val query = client.argString(argsJson, "query") ?: return "query ausente" to emptyList()
                runCatching { searchExtensions(query) }
                    .getOrElse { "Falha ao ler o catálogo: ${it.message}" } to emptyList()
            }
            "install_extension" -> {
                val pkg = client.argString(argsJson, "packageName")?.trim()
                    ?: return "packageName ausente" to emptyList()
                runCatching { installExtension(pkg) }
                    .getOrElse { "Falha ao instalar: ${it.message}" } to emptyList()
            }
            "uninstall_extension" -> {
                val pkg = client.argString(argsJson, "packageName")?.trim()
                    ?: return "packageName ausente" to emptyList()
                runCatching { uninstallExtension(pkg) }
                    .getOrElse { "Falha ao remover: ${it.message}" } to emptyList()
            }
            else -> "Ferramenta desconhecida: $name" to emptyList()
        }
    }

    /**
     * Extensions in the user's repos matching [query] (name, package, language
     * or one of its source names). Cached indexes, so no network when the
     * catalog was already fetched.
     */
    private suspend fun searchExtensions(query: String): String {
        val repos = extensionRepoStore.currentRepos()
        if (repos.isEmpty()) return "Nenhum repositório de extensões configurado."
        val installed = extensionManager.installedBrief().toMap()
        val needle = query.trim().lowercase()
        val matches = repos.flatMap { repo ->
            runCatching { extensionRepo.fetchIndex(repo.indexUrl) }.getOrDefault(emptyList())
        }.filter { ext ->
            ext.langs.any { it.contains(needle) } ||
                ext.name.lowercase().contains(needle) ||
                ext.packageName.lowercase().contains(needle) ||
                ext.sources.any { it.name.lowercase().contains(needle) }
        }.take(15)
        if (matches.isEmpty()) return "Nenhuma extensão encontrada para \"$query\"."
        return matches.joinToString("\n") { ext ->
            val state = if (installed.containsKey(ext.packageName)) "instalada" else "não instalada"
            "- ${ext.name} [${ext.langs.joinToString("/")}] v${ext.versionName} " +
                "($state) package=${ext.packageName}"
        }
    }

    /**
     * Installs an extension **from the configured catalog only**.
     *
     * The APK URL is resolved from the index by package name — never taken
     * from the model — so the assistant cannot point the installer at an
     * arbitrary host. Android still shows its own install confirmation.
     */
    private suspend fun installExtension(packageName: String): String {
        val entry = findInCatalog(packageName)
            ?: return "Nenhuma extensão com o pacote \"$packageName\" existe no catálogo. " +
                "Use search_extensions para achar o pacote exato."
        if (extensionManager.isInstalled(packageName)) {
            return "${entry.name} já está instalada (v${entry.versionName} no catálogo)."
        }
        val apkUrl = entry.resources.apkUrl
        if (apkUrl.isBlank()) return "${entry.name} não tem APK disponível no repositório."
        return extensionInstaller.downloadAndInstall(
            packageName = packageName,
            apkUrl = apkUrl,
            fileName = apkUrl.substringAfterLast('/'),
            onProgress = { _, _ -> },
        ).fold(
            onSuccess = {
                "APK de ${entry.name} baixado. O Android vai pedir a confirmação da instalação; " +
                    "depois a fonte aparece em Navegar."
            },
            onFailure = { "Falha ao baixar ${entry.name}: ${it.message}" },
        )
    }

    /** Uninstall, but only with the user's explicit yes. */
    private suspend fun uninstallExtension(packageName: String): String {
        if (!extensionManager.isInstalled(packageName)) {
            return "A extensão \"$packageName\" não está instalada."
        }
        val label = extensionManager.installedBrief()
            .firstOrNull { it.second == packageName }
            ?.first
            ?: packageName
        _pendingAction.value = PendingAction(
            id = System.currentTimeMillis(),
            packageName = packageName,
            label = label,
            sourceCount = 0,
        )
        val allowed = awaitDecision()
        if (!allowed) {
            _pendingAction.value = null
            return "O usuário não confirmou a remoção de $label. Nada foi desinstalado."
        }
        return when (val outcome = extensionManager.uninstall(packageName)) {
            is ExtensionManager.UninstallOutcome.Done ->
                "$label removida" +
                    if (outcome.cleanedSources > 0) {
                        " junto com as configurações de ${outcome.cleanedSources} fonte(s)."
                    } else {
                        "."
                    }
            is ExtensionManager.UninstallOutcome.Cancelled ->
                "O usuário não confirmou a remoção de $label. Nada foi desinstalado."
            is ExtensionManager.UninstallOutcome.Failed ->
                "Não foi possível remover $label: ${outcome.message}"
        }.also { _pendingAction.value = null }
    }

    private suspend fun findInCatalog(packageName: String) = withContext(Dispatchers.IO) {
        // `for`, não `mapNotNull`: as lambras de Sequence não são inline, e
        // fetchIndex é suspend.
        for (repo in extensionRepoStore.currentRepos()) {
            val index = runCatching { extensionRepo.fetchIndex(repo.indexUrl) }.getOrNull() ?: continue
            val match = index.firstOrNull { it.packageName == packageName && !it.isPlaceholder() }
            if (match != null) return@withContext match
        }
        null
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
     * Fontes que falharam (rede/ISP) entram em cooldown, e a contagem é
     * compartilhada com as telas de busca: uma fonte que o assistente aprendeu
     * a evitar não precisa ser redescoberta pelo usuário numa busca.
     */
    private fun isSourceCoolingDown(sourceId: Long): Boolean = health.isCoolingDown(sourceId)

    private fun markSourceFailed(sourceId: Long, message: String? = null) {
        health.recordFailure(sourceId, message)
    }

    private fun markSourceOk(sourceId: Long) {
        health.recordSuccess(sourceId)
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
        // Sem piso de relevância, o "melhor" entre resultados que não têm
        // nada a ver com a busca vira um card no chat — foi assim que um
        // mangá aleatório apareceu ao lado de uma lista de extensões.
        val best = candidates.maxByOrNull { scoreMatch(it.title, query) } ?: return null
        return best.takeIf { scoreMatch(it.title, query) >= MIN_MATCH_SCORE }
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
    /**
     * Titles worth looking up in the sources. Empty when the answer is not a
     * manga list (an extension answer, for instance), so the caller can skip
     * the lookup instead of announcing it and finding nothing.
     */
    fun hasMangaCandidates(text: String): Boolean = extractCandidateTitles(text).isNotEmpty()

    suspend fun resolveTitlesFrom(text: String, limit: Int = 6): List<MangaHit> {
        val candidates = extractCandidateTitles(text).take(limit)
        if (candidates.isEmpty()) return emptyList()
        // Teto total: isto roda DEPOIS da resposta já estar na tela, então um
        // travamento aqui deixa o chat girando sem nenhuma pista do motivo.
        return withTimeoutOrNull(RESOLVE_TITLES_MS) {
            supervisorScope {
                val semaphore = Semaphore(3)
                candidates.map { title ->
                    async { semaphore.withPermit { findTopHit(title) } }
                }.awaitAll().filterNotNull()
                    .distinctBy { it.sourceId to it.url }
                    .sortedBy { hit ->
                        candidates.indexOfFirst { sameTitle(it, hit.title) }.takeIf { i -> i >= 0 }
                            ?: Int.MAX_VALUE
                    }
                    .take(limit)
            }
        }.orEmpty()
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
            // Entradas de extensão não são títulos de mangá: procurar "Comikey"
            // nas fontes só traz card irrelevante para o chat.
            .filterNot { looksLikeExtensionEntry(it) }
            .distinct()
    }

    /** `1. Comikey [en/es] (v1.6.11)`, `package=eu.kanade…`, `…, não instalada`. */
    private fun looksLikeExtensionEntry(candidate: String): Boolean {
        if (Regex("""\(?v\d+\.\d+""").containsMatchIn(candidate)) return true
        if (candidate.contains("package=", ignoreCase = true)) return true
        if (Regex("""\[[a-z]{2}([/-][a-z]{2,4})*]""").containsMatchIn(candidate)) return true
        val words = candidate.lowercase()
        return listOf("extensão", "extensao", "instalad", "desinstalad", "apk")
            .any { it in words }
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

        /** Sem resposta do usuário = desinstalação negada. */
        private const val CONFIRM_TIMEOUT_MS = 120_000L

        /** Teto da resolução de títulos citados (roda com a resposta na tela). */
        private const val RESOLVE_TITLES_MS = 12_000L

        /**
         * Piso de relevância para virar card: exige nome igual, OU um contendo
         * o outro (5 pontos), OU palavras em comum. Sem isso, qualquer busca
         * devolvia "o melhor entre irrelevantes".
         */
        private const val MIN_MATCH_SCORE = 4

        const val SYSTEM_PROMPT = "Você é o assistente do app de leitura de mangás. " +
            "Responda sempre em português. Use a ferramenta search_manga para qualquer " +
            "pedido de busca, recomendação ou filtragem. Ela aceita status " +
            "(ongoing, completed, hiatus, cancelled, finished, licensed), genre " +
            "(ex.: romance, ação) e minChapters/maxChapters — use-os quando o usuário " +
            "pedir filtrado. " +
            "Para pedidos baseados na biblioteca: chame list_favorites UMA vez, " +
            "depois no máximo 2 search_manga, e responda — nunca repita a mesma " +
            "chamada. " +
            "Extensões (fontes): para instalar, SEMPRE chame search_extensions antes e " +
            "depois install_extension com o packageName EXATO que veio da busca — " +
            "nunca invente o pacote, e nunca peça URL de APK. Só instale quando o " +
            "pedido for explícito (\"instala a fonte X\"). Para desinstalar, use " +
            "uninstall_extension e avise que o usuário vai receber uma confirmação: " +
            "se ele recusar, diga que nada foi removido e não tente de novo. " +
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
