package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources at runtime.
 *
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
interface SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<Source>
}
