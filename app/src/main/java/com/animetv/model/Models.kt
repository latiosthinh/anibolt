package com.animetv.model

data class Anime(
    val title: String,
    val slug: String,
    val url: String,
    val posterUrl: String,
    val rating: String = "",
    val originalTitle: String = "",
    /** Provider's episode progress, e.g. "Tập 219/219 [4K]" (qfilm's corner badge). */
    val badge: String = "",
    /** Provider's quality tag, e.g. "Full HD 4K". */
    val quality: String = "",
)

data class AnimeDetail(
    val anime: Anime,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val status: String = "",
    val views: String = "",
    val latestEpisode: String = "",
)

data class Episode(
    val number: Int,
    val title: String,
    val url: String,
    val type: String = "vietsub"
)
