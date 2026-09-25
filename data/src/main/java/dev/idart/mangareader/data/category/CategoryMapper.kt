package dev.idart.mangareader.data.category

import db.GetCategories
import db.GetCategoriesByMangaId
import db.GetCategory
import dev.idart.mangareader.domain.category.model.Category

object CategoryMapper {
    fun map(row: GetCategory): Category =
        Category(
            id = row._id,
            name = row.name,
            order = row.sort,
            flags = row.flags,
            hidden = row.hidden != 0L,
        )

    fun map(row: GetCategories): Category =
        Category(
            id = row.id,
            name = row.name,
            order = row.order,
            flags = row.flags,
            hidden = row.hidden != 0L,
        )

    fun map(row: GetCategoriesByMangaId): Category =
        Category(
            id = row.id,
            name = row.name,
            order = row.order,
            flags = row.flags,
            hidden = row.hidden != 0L,
        )
}
