@file:Suppress("PropertyName", "ktlint:standard:property-naming")

package eu.kanade.tachiyomi.source.model

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
interface SChapter {

    var url: String

    var name: String

    var date_upload: Long

    var chapter_number: Float

    var scanlator: String?

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}
