package com.themoon.y1.models

import java.io.File

/**
 * Representation of a standalone movie or a television series episode.
 */
data class VideoItem(
    val file: File,
    val title: String,
    val durationMs: Long = 0L,
    val isTvEpisode: Boolean = false,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val coverFile: File? = null
)

/**
 * A television series containing one or more seasons.
 */
data class TvSeries(
    val name: String,
    val seasons: List<TvSeason>,
    val posterFile: File? = null
) {
    val totalEpisodes: Int
        get() = seasons.sumOf { it.episodes.size }
}

/**
 * A television season containing ordered episodes.
 */
data class TvSeason(
    val seriesName: String,
    val seasonNumber: Int,
    val seasonTitle: String,
    val episodes: List<VideoItem>
)

/**
 * Aggregate model for the entire video library.
 */
data class VideoLibrary(
    val movies: List<VideoItem> = emptyList(),
    val tvSeries: List<TvSeries> = emptyList(),
    val allVideos: List<File> = emptyList()
)
