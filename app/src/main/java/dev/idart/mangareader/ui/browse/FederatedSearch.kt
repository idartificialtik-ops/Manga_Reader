package dev.idart.mangareader.ui.browse

import android.util.Log
import dev.idart.mangareader.domain.source.interactor.BrowseSourceCatalogue
import dev.idart.mangareader.domain.source.service.SourceManager
import dev.idart.mangareader.source.model.FilterList
import dev.idart.mangareader.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs several queries across several sources at once and folds everything
 * into a single result.
 *
 * Naive fan-out (5 queries x 30 sources) is 150 requests against sites that
 * already rate-limit, so the search is bounded on three axes:
 *
 * - a global deadline — whatever has not answered by then is simply dropped;
 * - a request ceiling — the tail of the list of sources is not even started;
 * - per-source failure cutoff — a source that already failed twice in this
 *   same run stops receiving the remaining queries, instead of burning the
 *   budget on a source that is down.
 *
 * The health store is updated on the way out, so a source that is dead stops
 * being retried on the next search (see [SourceHealthStore]).
 */
class FederatedSearch(
    private val browse: BrowseSourceCatalogue,
    private val sourceManager: SourceManager,
    private val health: SourceHealthStore,
) {

    /** One result of one source, with which queries found it. */
    data class Hit(
        val manga: SManga,
        val sourceId: Long,
        val queries: List<String> = emptyList(),
        val literal: Boolean = false,
    )

    /** Per-source outcome, whatever happened to it. */
    data class SourceOutcome(
        val sourceId: Long,
        val sourceName: String,
        val hits: List<Hit> = emptyList(),
        val queriesRun: Int = 0,
        val error: String? = null,
        /** Set when the source was not searched at all (blocked/cooldown). */
        val skipReason: String? = null,
        val elapsedMs: Long = 0L,
    ) {
        val foundSomething: Boolean get() = hits.isNotEmpty()
        val searched: Boolean get() = skipReason == null
    }

    /**
     * The same work across sources, collapsed. One entry per distinct title,
     * ordered by how many queries found it.
     */
    data class MergedWork(
        val title: String,
        val hits: List<Hit>,
    ) {
        val best: Hit get() = hits.first()
        val sourceCount: Int get() = hits.distinctBy { it.sourceId }.size
        val queryCount: Int get() = hits.flatMap { it.queries }.distinct().size
        val literal: Boolean get() = hits.any { it.literal }
    }

    data class Result(
        val sections: List<SourceOutcome> = emptyList(),
        val merged: List<MergedWork> = emptyList(),
        val variants: List<AiQueryExpander.Variant> = emptyList(),
        val elapsedMs: Long = 0L,
        /** Sources that answered and found something. */
        val sourcesWithHits: Int = 0,
        val sourcesSearched: Int = 0,
        val sourcesSkipped: Int = 0,
        val sourcesFailed: Int = 0,
        val budgetExhausted: Boolean = false,
    )

    private class Accumulator(val sourceId: Long, val sourceName: String) {
        val lock = Any()
        val byUrl = LinkedHashMap<String, Hit>()
        val failures = AtomicInteger(0)
        var queriesRun = 0
        var error: String? = null
        var skipReason: String? = null
        val startedAt = System.currentTimeMillis()

        fun add(manga: SManga, query: String, literal: Boolean) {
            synchronized(lock) {
                val existing = byUrl[manga.url]
                if (existing == null) {
                    byUrl[manga.url] = Hit(manga, sourceId, listOf(query), literal)
                } else {
                    byUrl[manga.url] = existing.copy(
                        queries = (existing.queries + query).distinct(),
                        literal = existing.literal || literal,
                    )
                }
            }
        }

        fun toOutcome(): SourceOutcome = synchronized(lock) {
            SourceOutcome(
                sourceId = sourceId,
                sourceName = sourceName,
                hits = byUrl.values.toList(),
                queriesRun = queriesRun,
                error = error,
                skipReason = skipReason,
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    /**
     * @param variants queries to run against every source, the first being the
     *   literal one.
     * @param onlySourceId restrict to a single source (search inside one
     *   extension) instead of every catalogue source.
     */
    suspend fun search(
        variants: List<AiQueryExpander.Variant>,
        onlySourceId: Long? = null,
        filters: FilterList = FilterList(),
        deadlineMs: Long = DEFAULT_DEADLINE_MS,
        maxRequests: Int = DEFAULT_MAX_REQUESTS,
        parallel: Int = DEFAULT_PARALLEL,
    ): Result {
        val started = System.currentTimeMillis()
        if (variants.isEmpty()) return Result()

        val literal = variants.first().query
        val sources = if (onlySourceId != null) {
            listOfNotNull(sourceManager.getSource(onlySourceId))
        } else {
            sourceManager.getCatalogueSources()
        }

        val accs = sources.map { source ->
            Accumulator(source.id, source.name).apply {
                // Skipped before spending a single request on it.
                skipReason = health.skipReason(source.id)
            }
        }
        val runnable = accs.filter { it.skipReason == null }
        val skipped = accs.size - runnable.size

        val requests = AtomicInteger(0)
        val hits = mutableListOf<Hit>()
        var budgetExhausted = false

        // One deadline for everything: a source that is still waiting when it
        // closes simply contributes nothing.
        withTimeoutOrNull(deadlineMs) {
            coroutineScope {
                val semaphore = Semaphore(parallel)
                val jobs = mutableListOf<kotlinx.coroutines.Job>()
                outer@ for (acc in runnable) {
                    for (variant in variants) {
                        // A source that is already failing takes no more.
                        if (acc.failures.get() >= MAX_FAILURES_PER_SOURCE) break
                        if (requests.incrementAndGet() > maxRequests) {
                            budgetExhausted = true
                            break@outer
                        }
                        jobs += launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                runQuery(acc, variant, literal, filters, hits)
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        val sections = accs.map { it.toOutcome() }
        sections.forEach { section ->
            if (section.searched) {
                if (section.error != null) {
                    health.recordFailure(section.sourceId, section.error)
                } else {
                    health.recordSuccess(section.sourceId)
                }
            }
        }

        val failed = sections.count { it.searched && it.error != null }
        val withHits = sections.count { it.foundSomething }
        val merged = mergeWorks(hits)
        Log.i(
            TAG,
            "busca: ${variants.size} termos x ${runnable.size} fontes em ${System.currentTimeMillis() - started}ms " +
                "→ ${merged.size} obras, $withHits fontes com resultado, $skipped puladas, $failed falharam" +
                if (budgetExhausted) " (orçamento estourado)" else "",
        )
        return Result(
            sections = sections,
            merged = merged,
            variants = variants,
            elapsedMs = System.currentTimeMillis() - started,
            sourcesWithHits = withHits,
            sourcesSearched = runnable.size,
            sourcesSkipped = skipped,
            sourcesFailed = failed,
            budgetExhausted = budgetExhausted,
        )
    }

    private suspend fun runQuery(
        acc: Accumulator,
        variant: AiQueryExpander.Variant,
        literal: String,
        filters: FilterList,
        hits: MutableList<Hit>,
    ) {
        // runCatching engoliria CancellationException e transformaria um
        // cancelamento (o usuário mudou a busca) em "fonte sem resposta".
        var errorMessage: String? = null
        val page = try {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                browse.search(acc.sourceId, 1, variant.query, filters)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "erro de rede"
            null
        }
        if (page == null) {
            synchronized(acc.lock) { acc.queriesRun++ }
            acc.failures.incrementAndGet()
            synchronized(acc.lock) { acc.error = acc.error ?: (errorMessage ?: "sem resposta") }
            return
        }
        val isLiteral = sameQuery(variant.query, literal)
        val kept = page.mangas.take(MAX_PER_QUERY)
        synchronized(acc.lock) { acc.queriesRun++ }
        kept.forEach { manga ->
            acc.add(manga, variant.query, isLiteral)
            synchronized(hits) { hits += Hit(manga, acc.sourceId, listOf(variant.query), isLiteral) }
        }
    }

    /**
     * One line per distinct title, ordered by how strongly the searches agreed:
     * a title that several queries found is very likely the one being looked
     * for, and a title found by the literal query beats a lucky synonym hit.
     */
    private fun mergeWorks(hits: List<Hit>): List<MergedWork> {
        val groups = LinkedHashMap<String, MutableList<Hit>>()
        hits.forEach { hit ->
            val key = normalizeTitle(hit.manga.title)
            if (key.isEmpty()) return@forEach
            groups.getOrPut(key) { mutableListOf() } += hit
        }
        return groups.entries
            .map { (_, group) -> dedupeSources(group) }
            .sortedWith(
                compareByDescending<MergedWork> { work ->
                    // Acertos da busca literal pesam mais que sinônimos.
                    (if (work.literal) 2 else 0) + work.queryCount * 2 + work.sourceCount
                }.thenByDescending { it.hits.size },
            )
    }

    /** Keeps the best hit per source (most queries, then has cover). */
    private fun dedupeSources(hits: List<Hit>): MergedWork {
        val perSource = LinkedHashMap<Long, Hit>()
        hits.forEach { hit ->
            val current = perSource[hit.sourceId]
            perSource[hit.sourceId] = when {
                current == null -> hit
                hit.queries.size > current.queries.size -> hit
                hit.queries.size == current.queries.size &&
                    current.manga.thumbnail_url.isNullOrBlank() &&
                    !hit.manga.thumbnail_url.isNullOrBlank() -> hit
                else -> current.copy(
                    queries = (current.queries + hit.queries).distinct(),
                    literal = current.literal || hit.literal,
                )
            }
        }
        val ordered = perSource.values.sortedWith(
            compareByDescending<Hit> { it.literal }
                .thenByDescending { it.queries.size }
                .thenByDescending { !it.manga.thumbnail_url.isNullOrBlank() },
        )
        return MergedWork(
            title = ordered.first().manga.title,
            hits = ordered,
        )
    }

    private fun sameQuery(a: String, b: String) = normalizeTitle(a) == normalizeTitle(b)

    /** Lowercase, accent-free, no punctuation: "Sakamoto Days" == "sakamoto-days". */
    private fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    companion object {
        private const val TAG = "FederatedSearch"

        /** Everything must answer within this, or the tail is dropped. */
        const val DEFAULT_DEADLINE_MS = 35_000L

        /** Ceiling on requests: 5 queries x 30 sources would be 150. */
        const val DEFAULT_MAX_REQUESTS = 60

        const val DEFAULT_PARALLEL = 8

        private val SOURCE_TIMEOUT_MS = 10_000L

        /** A source that failed this many times gets no further queries. */
        private const val MAX_FAILURES_PER_SOURCE = 2

        private const val MAX_PER_QUERY = 10
    }
}
