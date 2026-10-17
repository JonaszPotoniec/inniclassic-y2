package com.themoon.y1.sync.jellyfin

/**
 * Unified data transfer object for all Jellyfin media items (music tracks, movies, TV episodes).
 */
class JellyfinMediaItem(
    var id: String = "",
    var name: String = "",
    var artist: String = "",
    var album: String? = null,
    var albumArtist: String = "",
    var composer: String = "",
    var year: String? = null,
    var genre: String? = null,
    var trackNumber: Int = 0,
    var discNumber: Int = 0,
    var container: String = "",
    var runTimeTicks: Long = 0L,
    var size: Long = 0L,
    var albumId: String? = null,
    var albumPrimaryImageTag: String? = null,
    var collectionType: String = "",
    var libraryId: String = "",
    var seriesName: String? = null,
    var seriesId: String = "",
    var seasonId: String = "",
    var seasonName: String? = null,
    var serverDateModified: String = "",
    var parentId: String? = null
) {
    var indexNumber: Int
        get() = trackNumber
        set(value) { trackNumber = value }

    var parentIndexNumber: Int
        get() = discNumber
        set(value) { discNumber = value }

    var fileSize: Long
        get() = size
        set(value) { size = value }
}
