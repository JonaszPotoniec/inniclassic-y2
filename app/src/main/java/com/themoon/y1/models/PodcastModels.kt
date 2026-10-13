package com.themoon.y1.models

import java.io.File

/**
 * Represents a subscribed or discovered podcast channel.
 */
data class PodcastChannel(
    val title: String,
    val rssUrl: String,
    val imageUrl: String? = null,
    val localDirectory: File? = null
)

/**
 * Represents a single podcast episode parsed from an RSS feed.
 */
data class PodcastEpisode(
    val title: String,
    val channelTitle: String,
    val audioUrl: String,
    val pubDate: String? = null,
    val imageUrl: String? = null,
    val durationMs: Long = 0L,
    val localFile: File? = null
)
