@file:Suppress("PropertyName", "ktlint:standard:property-naming")

package eu.kanade.tachiyomi.source.model

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
class SChapterImpl : SChapter {

    override var url: String = ""

    override var name: String = ""

    override var date_upload: Long = 0

    override var chapter_number: Float = 0f

    override var scanlator: String? = null
}
