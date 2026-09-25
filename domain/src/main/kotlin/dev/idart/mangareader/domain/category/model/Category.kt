package dev.idart.mangareader.domain.category.model

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    val hidden: Boolean,
) {
    companion object {
        fun create(
            name: String,
            order: Long = 0L,
        ) = Category(
            id = -1L,
            name = name,
            order = order,
            flags = 0L,
            hidden = false,
        )
    }
}
