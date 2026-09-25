package dev.idart.mangareader.domain.source.model

import dev.idart.mangareader.domain.chapter.model.Chapter
import dev.idart.mangareader.domain.manga.model.Manga
import dev.idart.mangareader.domain.manga.model.MangaUpdate
import dev.idart.mangareader.source.model.SChapter
import dev.idart.mangareader.source.model.SChapterImpl
import dev.idart.mangareader.source.model.SManga
import dev.idart.mangareader.source.model.SMangaImpl
import kotlinx.serialization.json.buildJsonObject

/**
 * Converts a network [SManga] into a not-yet-persisted domain [Manga].
 */
fun SManga.toManga(sourceId: Long): Manga {
    return Manga.create(source = sourceId).copy(
        url = url,
        title = title,
        artist = artist,
        author = author,
        thumbnailUrl = thumbnail_url,
        description = description,
        genre = getGenres(),
        status = status.toLong(),
        updateStrategy = update_strategy,
        initialized = initialized,
        memo = memo,
    )
}

/**
 * Converts a persisted domain [Manga] back into an [SManga] for source requests.
 */
fun Manga.toSManga(): SManga {
    return SMangaImpl().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.thumbnail_url = thumbnailUrl
        it.description = description
        it.genre = genre?.joinToString(", ")
        it.status = status.toInt()
        it.update_strategy = updateStrategy
        it.initialized = initialized
        it.memo = memo
    }
}

/**
 * Converts fetched network details into a partial [MangaUpdate].
 * Blank network values become null so the update keeps existing data.
 */
fun SManga.toMangaUpdate(id: Long): MangaUpdate {
    return MangaUpdate(
        id = id,
        title = title.ifBlank { null },
        artist = artist?.ifBlank { null },
        author = author?.ifBlank { null },
        thumbnailUrl = thumbnail_url,
        description = description?.ifBlank { null },
        genre = getGenres(),
        status = status.toLong(),
        updateStrategy = update_strategy,
        initialized = initialized,
    )
}

/**
 * Converts a persisted domain [Chapter] back into an [SChapter] for source requests.
 */
fun Chapter.toSChapter(): SChapter {
    return SChapterImpl().also {
        it.url = url
        it.name = name
        it.date_upload = dateUpload
        it.chapter_number = chapterNumber
        it.scanlator = scanlator
    }
}

/**
 * Converts a network [SChapter] into a not-yet-persisted domain [Chapter].
 */
fun SChapter.toChapter(mangaId: Long, sourceOrder: Long, dateFetch: Long = System.currentTimeMillis()): Chapter {
    return Chapter.create(mangaId = mangaId).copy(
        url = url,
        name = name,
        scanlator = scanlator,
        chapterNumber = chapter_number,
        sourceOrder = sourceOrder,
        dateFetch = dateFetch,
        dateUpload = date_upload,
        memo = buildJsonObject { },
    )
}
