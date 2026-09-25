package dev.idart.mangareader.source.model

import java.io.Serializable

/**
 * The list of filters a source can implement.
 */
sealed class Filter<T>(val name: String, var state: T) : Serializable {
    class Header(name: String) : Filter<Any>(name, 0)

    class Separator(name: String = "") : Filter<Any>(name, 0)

    open class Select<V>(name: String, val values: Array<V>, state: Int = 0) :
        Filter<Int>(name, state)

    open class Text(name: String, state: String = "") : Filter<String>(name, state)

    open class CheckBox(name: String, state: Boolean = false) : Filter<Boolean>(name, state)

    open class TriState(name: String, state: Int = STATE_IGNORE) : Filter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE

        fun isIncluded() = state == STATE_INCLUDE

        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    open class Group<V>(name: String, state: List<V>) : Filter<List<V>>(name, state)

    open class Sort(name: String, val values: Array<String>, state: Sort.Selection? = null) :
        Filter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean) : Serializable
    }
}

class FilterList(filters: List<Filter<*>>) : ArrayList<Filter<*>>(filters) {
    constructor(vararg fs: Filter<*>) : this(fs.toList())

    @Suppress("UNCHECKED_CAST")
    inline fun <reified R> firstOrNull(): R? = firstOrNull { it is R } as R?

    @Suppress("UNCHECKED_CAST")
    inline fun <reified R> listOf(): List<R> = filterIsInstance<R>()
}
