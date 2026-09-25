package dev.idart.mangareader.domain.source.service

import dev.idart.mangareader.domain.source.model.StoredSource
import dev.idart.mangareader.domain.source.model.StubSource
import dev.idart.mangareader.domain.source.repository.SourceRepository
import dev.idart.mangareader.source.CatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registry of available catalogue sources: built-in ports plus sources loaded
 * from external extension APKs.
 *
 * When an external source has the same (name, lang) as a built-in port, the
 * external one wins and the built-in is hidden to avoid duplicates.
 */
class SourceManager(
    private val builtInSources: List<CatalogueSource>,
    private val sourceRepository: SourceRepository,
) {

    private val _externalSources = MutableStateFlow<List<CatalogueSource>>(emptyList())
    val externalSources: StateFlow<List<CatalogueSource>> = _externalSources.asStateFlow()

    private val _catalogueSources = MutableStateFlow<List<CatalogueSource>>(builtInSources)
    val catalogueSources: StateFlow<List<CatalogueSource>> = _catalogueSources.asStateFlow()

    fun getCatalogueSources(): List<CatalogueSource> = _catalogueSources.value

    fun getSource(id: Long): CatalogueSource? = _catalogueSources.value.find { it.id == id }

    fun getOrStub(id: Long): CatalogueSource = getSource(id) ?: StubSource(id)

    fun setExternalSources(sources: List<CatalogueSource>) {
        _externalSources.value = sources
        _catalogueSources.value = merge(builtInSources, sources)
    }

    /**
     * Upserts every known source into the `sources` table.
     * Call at startup and whenever the set of sources changes.
     */
    suspend fun refresh() {
        getCatalogueSources().forEach { source ->
            sourceRepository.upsert(StoredSource(id = source.id, lang = source.lang, name = source.name))
        }
    }

    private fun merge(
        builtIn: List<CatalogueSource>,
        external: List<CatalogueSource>,
    ): List<CatalogueSource> {
        val externalKeys = external.map { it.name.lowercase() to it.lang.lowercase() }.toSet()
        return external + builtIn.filter { (it.name.lowercase() to it.lang.lowercase()) !in externalKeys }
    }
}
