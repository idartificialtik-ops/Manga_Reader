package dev.idart.mangareader.data.category

import dev.idart.mangareader.data.DatabaseHandler
import dev.idart.mangareader.domain.category.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryDataStore(
    private val handler: DatabaseHandler,
) {
    suspend fun get(id: Long): CategoryRow? {
        val row = handler.awaitOneOrNull { categoriesQueries.getCategory(id) } ?: return null
        return CategoryRow(
            id = row._id,
            name = row.name,
            order = row.sort,
            flags = row.flags,
            hidden = row.hidden != 0L,
        )
    }

    suspend fun getAll(): List<CategoryRow> {
        return handler.awaitList { categoriesQueries.getCategories() }.map {
            CategoryRow(id = it.id, name = it.name, order = it.order, flags = it.flags, hidden = it.hidden != 0L)
        }
    }

    fun getAllAsFlow(): Flow<List<CategoryRow>> {
        return handler.subscribeToList { categoriesQueries.getCategories() }.map { list ->
            list.map {
                CategoryRow(id = it.id, name = it.name, order = it.order, flags = it.flags, hidden = it.hidden != 0L)
            }
        }
    }

    suspend fun getByMangaId(mangaId: Long): List<CategoryRow> {
        return handler.awaitList { categoriesQueries.getCategoriesByMangaId(mangaId) }.map {
            CategoryRow(id = it.id, name = it.name, order = it.order, flags = it.flags, hidden = it.hidden != 0L)
        }
    }

    suspend fun insert(category: Category): Long {
        return handler.await(inTransaction = true) {
            categoriesQueries.insert(
                name = category.name,
                order = category.order,
                flags = category.flags,
                hidden = if (category.hidden) 1 else 0,
            )
            categoriesQueries.selectLastInsertedRowId().executeAsOne()
        }
    }

    suspend fun updatePartial(
        categoryId: Long,
        name: String?,
        order: Long?,
        flags: Long?,
        hidden: Boolean?,
    ) {
        handler.await {
            categoriesQueries.update(
                name = name,
                order = order,
                flags = flags,
                hidden = hidden?.let { if (it) 1 else 0 },
                categoryId = categoryId,
            )
        }
    }

    suspend fun delete(categoryId: Long) {
        handler.await { categoriesQueries.delete(categoryId) }
    }
}

data class CategoryRow(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    val hidden: Boolean,
)
