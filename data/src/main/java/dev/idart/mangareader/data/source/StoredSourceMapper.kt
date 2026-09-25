package dev.idart.mangareader.data.source

import db.Sources
import dev.idart.mangareader.domain.source.model.StoredSource

object StoredSourceMapper {
    fun map(row: Sources): StoredSource =
        StoredSource(
            id = row._id,
            lang = row.lang,
            name = row.name,
        )
}
