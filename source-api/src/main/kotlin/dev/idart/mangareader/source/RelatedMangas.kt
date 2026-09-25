package dev.idart.mangareader.source

import dev.idart.mangareader.source.model.SManga

/**
 * Related-manga lookup based on title keywords.
 *
 * Splits the manga title into searchable keywords (plus the full title),
 * runs a page-1 search for each and merges the results, excluding the
 * manga itself. Works with any [CatalogueSource].
 */
object RelatedMangas {

    suspend fun bySearch(
        source: CatalogueSource,
        manga: SManga,
        maxResults: Int = 20,
    ): List<SManga> {
        val keywords = extractKeywords(manga.title)
        if (keywords.isEmpty()) return emptyList()
        val filterList = source.getFilterList()
        val results = linkedMapOf<String, SManga>()
        for (keyword in keywords) {
            val page = try {
                source.getSearchManga(1, keyword, filterList)
            } catch (_: Exception) {
                continue
            }
            for (entry in page.mangas) {
                if (entry.url != manga.url) {
                    results.putIfAbsent(entry.url, entry)
                }
            }
            if (results.size >= maxResults) break
        }
        return results.values.take(maxResults)
    }

    fun extractKeywords(title: String): List<String> {
        val keywords = LinkedHashSet<String>()
        val trimmed = title.trim()
        if (trimmed.length > 1) {
            keywords.add(trimmed)
        }
        trimmed
            .replace(Regex("([!~#\$%^&*+_|/\\\\,?:;'\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)"), " ")
            .split(Regex("\\s+"))
            .map { it.replace(Regex("^\\d+$"), "").lowercase() }
            .filter { it.length > 1 }
            .forEach { keywords.add(it) }
        return keywords.toList()
    }
}
