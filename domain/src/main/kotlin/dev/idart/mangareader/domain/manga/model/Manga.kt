package dev.idart.mangareader.domain.manga.model

import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Manga(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val fetchInterval: Int,
    val dateAdded: Long,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val url: String,
    val title: String,
    val artist: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val updateStrategy: UpdateStrategy,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val version: Long,
    val notes: String,
    val memo: JsonObject,
) : java.io.Serializable {
    val sorting: Long
        get() = chapterFlags and CHAPTER_SORTING_MASK

    val displayMode: Long
        get() = chapterFlags and CHAPTER_DISPLAY_MASK

    fun sortDescending(): Boolean {
        return chapterFlags and CHAPTER_SORT_DIR_MASK == CHAPTER_SORT_DESC
    }

    companion object {
        // Generic filter that does not filter anything
        const val SHOW_ALL = 0x00000000L

        const val CHAPTER_SORT_DESC = 0x00000000L
        const val CHAPTER_SORT_ASC = 0x00000001L
        const val CHAPTER_SORT_DIR_MASK = 0x00000001L

        const val CHAPTER_SHOW_UNREAD = 0x00000002L
        const val CHAPTER_SHOW_READ = 0x00000004L
        const val CHAPTER_UNREAD_MASK = 0x00000006L

        const val CHAPTER_SHOW_DOWNLOADED = 0x00000008L
        const val CHAPTER_SHOW_NOT_DOWNLOADED = 0x00000010L
        const val CHAPTER_DOWNLOADED_MASK = 0x00000018L

        const val CHAPTER_SHOW_BOOKMARKED = 0x00000020L
        const val CHAPTER_SHOW_NOT_BOOKMARKED = 0x00000040L
        const val CHAPTER_BOOKMARKED_MASK = 0x00000060L

        const val CHAPTER_SORTING_SOURCE = 0x00000000L
        const val CHAPTER_SORTING_NUMBER = 0x00000100L
        const val CHAPTER_SORTING_UPLOAD_DATE = 0x00000200L
        const val CHAPTER_SORTING_ALPHABET = 0x00000300L
        const val CHAPTER_SORTING_MASK = 0x00000300L

        const val CHAPTER_DISPLAY_NAME = 0x00000000L
        const val CHAPTER_DISPLAY_NUMBER = 0x00100000L
        const val CHAPTER_DISPLAY_MASK = 0x00100000L

        fun create(source: Long = -1L) =
            Manga(
                id = -1L,
                url = "",
                title = "",
                source = source,
                favorite = false,
                lastUpdate = 0L,
                nextUpdate = 0L,
                fetchInterval = 0,
                dateAdded = 0L,
                viewerFlags = 0L,
                chapterFlags = 0L,
                coverLastModified = 0L,
                artist = null,
                author = null,
                thumbnailUrl = null,
                description = null,
                genre = null,
                status = SManga.UNKNOWN.toLong(),
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                initialized = false,
                lastModifiedAt = 0L,
                favoriteModifiedAt = null,
                version = 0L,
                notes = "",
                memo = buildJsonObject { },
            )
    }
}
