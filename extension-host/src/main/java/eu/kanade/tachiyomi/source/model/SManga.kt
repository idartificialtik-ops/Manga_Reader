@file:Suppress("PropertyName", "ktlint:standard:property-naming")

package eu.kanade.tachiyomi.source.model

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 */
interface SManga {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga {
            return SMangaImpl()
        }
    }
}
