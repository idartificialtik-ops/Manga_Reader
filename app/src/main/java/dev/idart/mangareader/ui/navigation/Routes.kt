package dev.idart.mangareader.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Library

@Serializable
data object Browse

@Serializable
data class GlobalSearch(val initialQuery: String = "")

@Serializable
data object Feed

@Serializable
data object More

@Serializable
data object Downloads

@Serializable
data object Extensions

@Serializable
data object Theme

@Serializable
data object AiAssistant

@Serializable
data object TranslationSettings

@Serializable
data class MangaDetail(val mangaId: Long)

@Serializable
data class SourceBrowse(val sourceId: Long, val initialQuery: String = "")

@Serializable
data class Reader(val mangaId: Long, val chapterId: Long)
