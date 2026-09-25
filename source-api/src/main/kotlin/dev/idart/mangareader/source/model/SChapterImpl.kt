package dev.idart.mangareader.source.model

@Suppress("ktlint:standard:property-naming")
class SChapterImpl : SChapter {
    override var url: String = ""

    override var name: String = ""

    override var date_upload: Long = 0

    override var chapter_number: Float = 0f

    override var scanlator: String? = null
}
