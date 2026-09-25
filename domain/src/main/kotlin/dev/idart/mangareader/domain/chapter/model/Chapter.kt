package dev.idart.mangareader.domain.chapter.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Chapter(
    val id: Long,
    val mangaId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val chapterNumber: Float,
    val sourceOrder: Long,
    val dateFetch: Long,
    val dateUpload: Long,
    val lastModifiedAt: Long,
    val version: Long,
    val url: String,
    val name: String,
    val scanlator: String?,
    val memo: JsonObject,
) : java.io.Serializable {
    companion object {
        fun create(mangaId: Long = -1L) =
            Chapter(
                id = -1L,
                mangaId = mangaId,
                url = "",
                name = "",
                scanlator = null,
                read = false,
                bookmark = false,
                lastPageRead = 0L,
                chapterNumber = 0f,
                sourceOrder = 0L,
                dateFetch = 0L,
                dateUpload = 0L,
                lastModifiedAt = 0L,
                version = 0L,
                memo = buildJsonObject { },
            )
    }
}
