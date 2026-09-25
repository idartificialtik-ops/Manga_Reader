package dev.idart.mangareader.domain.library.model

import dev.idart.mangareader.domain.manga.model.Manga

data class LibraryManga(
    val manga: Manga,
    val category: Long,
    val totalChapters: Long,
    val readCount: Long,
    val bookmarkCount: Long,
    val latestUpload: Long,
    val chapterFetchedAt: Long,
    val lastRead: Long,
)
