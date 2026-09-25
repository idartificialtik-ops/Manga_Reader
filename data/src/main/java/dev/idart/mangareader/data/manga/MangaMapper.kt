package dev.idart.mangareader.data.manga

import db.Mangas
import dev.idart.mangareader.domain.manga.model.Manga

object MangaMapper {
    fun map(row: Mangas): Manga =
        Manga(
            id = row._id,
            source = row.source,
            favorite = row.favorite,
            lastUpdate = row.last_update ?: 0L,
            nextUpdate = row.next_update ?: 0L,
            fetchInterval = row.calculate_interval.toInt(),
            dateAdded = row.date_added,
            viewerFlags = row.viewer,
            chapterFlags = row.chapter_flags,
            coverLastModified = row.cover_last_modified,
            url = row.url,
            title = row.title,
            artist = row.artist,
            author = row.author,
            thumbnailUrl = row.thumbnail_url,
            description = row.description,
            genre = row.genre,
            status = row.status,
            updateStrategy = row.update_strategy,
            initialized = row.initialized,
            lastModifiedAt = row.last_modified_at,
            favoriteModifiedAt = row.favorite_modified_at,
            version = row.version,
            notes = row.notes,
            memo = row.memo,
        )
}
