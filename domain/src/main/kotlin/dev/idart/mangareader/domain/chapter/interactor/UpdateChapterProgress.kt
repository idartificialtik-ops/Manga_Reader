package dev.idart.mangareader.domain.chapter.interactor

import dev.idart.mangareader.domain.chapter.model.ChapterUpdate
import dev.idart.mangareader.domain.chapter.repository.ChapterRepository

class UpdateChapterProgress(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun awaitMarkRead(
        chapterId: Long,
        read: Boolean,
    ): Boolean {
        return chapterRepository.update(ChapterUpdate(id = chapterId, read = read))
    }

    suspend fun awaitSetLastPageRead(
        chapterId: Long,
        lastPageRead: Long,
    ): Boolean {
        return chapterRepository.update(ChapterUpdate(id = chapterId, lastPageRead = lastPageRead))
    }

    suspend fun awaitSetBookmark(
        chapterId: Long,
        bookmark: Boolean,
    ): Boolean {
        return chapterRepository.update(ChapterUpdate(id = chapterId, bookmark = bookmark))
    }
}
