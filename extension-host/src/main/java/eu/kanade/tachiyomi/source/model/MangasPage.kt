package eu.kanade.tachiyomi.source.model

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
data class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean)
