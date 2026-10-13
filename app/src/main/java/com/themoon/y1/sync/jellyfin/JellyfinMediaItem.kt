package com.themoon.y1.sync.jellyfin

/**
 * Unified data transfer object for all Jellyfin media items (music tracks, movies, TV episodes).
 */
class JellyfinMediaItem {
    var id: String = ""
    var name: String = ""
    var album: String? = null
    var artist: String = ""
    var albumArtist: String = ""
    var composer: String = ""
    var year: String = ""
    var genre: String = ""
    var indexNumber: Int = 0
    var parentIndexNumber: Int = 0
    var container: String = ""
    var fileSize: Long = 0L
    var albumId: String? = null
    var albumPrimaryImageTag: String? = null
    var collectionType: String = ""
    var libraryId: String = ""
    var seriesName: String? = null
    var seriesId: String = ""
    var seasonId: String = ""
    var seasonName: String? = null
    var serverDateModified: String = ""
}
