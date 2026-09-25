package dev.idart.mangareader.domain.category.repository

import dev.idart.mangareader.domain.category.model.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    suspend fun get(id: Long): Category?

    suspend fun getAll(): List<Category>

    fun getAllAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    suspend fun insert(category: Category): Long

    suspend fun updatePartial(
        categoryId: Long,
        name: String? = null,
        order: Long? = null,
        flags: Long? = null,
        hidden: Boolean? = null,
    )

    suspend fun delete(categoryId: Long)
}
