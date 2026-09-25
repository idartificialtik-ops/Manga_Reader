package eu.kanade.tachiyomi.source.model

/**
 * Real host implementation (mirrors extensions-lib 1.6 API shape).
 */
data class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)
