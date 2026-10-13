package com.themoon.y1.sync.jellyfin

/**
 * Unified data transfer object for all Jellyfin media items (music tracks, movies, TV episodes).
 */
class JellyfinMediaItem {
    @JvmField var id: String = ""
    @JvmField var name: String = ""
    @JvmField var album: String? = null
    @JvmField var artist: String = ""
    @JvmField var albumArtist: String = ""
    @JvmField var composer: String = ""
    @JvmField var year: String = ""
    @JvmField var genre: String = ""
    @JvmField var indexNumber: Int = 0
    @JvmField var parentIndexNumber: Int = 0
    @JvmField var container: String = ""
    @JvmField var fileSize: Long = 0L
    @JvmField var albumId: String? = null
    @JvmField var albumPrimaryImageTag: String? = null
    @JvmField var collectionType: String = ""
    @JvmField var libraryId: String = ""
    @JvmField var seriesName: String? = null
    @JvmField var seriesId: String = ""
    @JvmField var seasonId: String = ""
    @JvmField var seasonName: String? = null
    @JvmField var serverDateModified: String = ""
}
