package dev.idart.mangareader.source.model

import java.io.Serializable

data class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
) : Serializable
