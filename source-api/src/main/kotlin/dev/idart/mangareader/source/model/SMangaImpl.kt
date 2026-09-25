package dev.idart.mangareader.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Suppress("ktlint:standard:property-naming")
class SMangaImpl : SManga {
    override var url: String = ""

    override var title: String = ""

    override var thumbnail_url: String? = null

    override var artist: String? = null

    override var author: String? = null

    override var status: Int = SManga.UNKNOWN

    override var description: String? = null

    override var genre: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    override var memo: JsonObject = buildJsonObject { }
}
