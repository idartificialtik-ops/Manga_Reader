package dev.idart.mangareader.domain.manga.model

import dev.idart.mangareader.source.model.UpdateStrategy

data class MangaUpdate(
    val id: Long,
    val source: Long? = null,
    val url: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val title: String? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
    val favorite: Boolean? = null,
    val lastUpdate: Long? = null,
    val nextUpdate: Long? = null,
    val initialized: Boolean? = null,
    val viewerFlags: Long? = null,
    val chapterFlags: Long? = null,
    val coverLastModified: Long? = null,
    val dateAdded: Long? = null,
    val updateStrategy: UpdateStrategy? = null,
    val calculateInterval: Long? = null,
    val version: Long? = null,
    val isSyncing: Long? = null,
    val notes: String? = null,
)
