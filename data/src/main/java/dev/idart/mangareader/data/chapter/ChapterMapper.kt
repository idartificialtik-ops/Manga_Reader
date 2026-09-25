package dev.idart.mangareader.data.chapter

import db.Chapters
import dev.idart.mangareader.domain.chapter.model.Chapter

object ChapterMapper {
    fun map(row: Chapters): Chapter =
        Chapter(
            id = row._id,
            mangaId = row.manga_id,
            read = row.read,
            bookmark = row.bookmark,
            lastPageRead = row.last_page_read,
            chapterNumber = row.chapter_number.toFloat(),
            sourceOrder = row.source_order,
            dateFetch = row.date_fetch,
            dateUpload = row.date_upload,
            lastModifiedAt = row.last_modified_at,
            version = row.version,
            url = row.url,
            name = row.name,
            scanlator = row.scanlator,
            memo = row.memo,
        )
}
