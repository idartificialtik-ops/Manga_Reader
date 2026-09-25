package dev.idart.mangareader.data.category

import dev.idart.mangareader.domain.category.model.Category
import dev.idart.mangareader.domain.category.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepositoryImpl(
    private val categoryDataStore: CategoryDataStore,
) : CategoryRepository {
    override suspend fun get(id: Long): Category? {
        return categoryDataStore.get(id)?.let(::toModel)
    }

    override suspend fun getAll(): List<Category> {
        return categoryDataStore.getAll().map(::toModel)
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return categoryDataStore.getAllAsFlow().map { list -> list.map(::toModel) }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return categoryDataStore.getByMangaId(mangaId).map(::toModel)
    }

    override suspend fun insert(category: Category): Long {
        return categoryDataStore.insert(category)
    }

    override suspend fun updatePartial(
        categoryId: Long,
        name: String?,
        order: Long?,
        flags: Long?,
        hidden: Boolean?,
    ) {
        categoryDataStore.updatePartial(categoryId, name, order, flags, hidden)
    }

    override suspend fun delete(categoryId: Long) {
        categoryDataStore.delete(categoryId)
    }

    private fun toModel(row: CategoryRow): Category =
        Category(
            id = row.id,
            name = row.name,
            order = row.order,
            flags = row.flags,
            hidden = row.hidden,
        )
}
