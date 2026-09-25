package keiyoushi.utils

/**
 * Real host implementation (mirrors keiyoushi extensions core).
 */
inline fun <reified T> List<*>.firstInstanceOrNull(): T? = firstOrNull { it is T } as T?
