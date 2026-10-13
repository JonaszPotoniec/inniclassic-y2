package com.themoon.y1.models

import java.io.File

class SongItem {
    @JvmField var file: File
    @JvmField var title: String
    @JvmField var artist: String
    @JvmField var album: String
    @JvmField var year: String
    @JvmField var genre: String
    @JvmField var albumArtist: String
    @JvmField var composer: String

    constructor(file: File, title: String, artist: String?, album: String?) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = "Unknown Year"
        this.genre = "Unknown Genre"
        this.albumArtist = this.artist
        this.composer = "Unknown Composer"
    }

    constructor(file: File, title: String, artist: String?, album: String?, year: String?, genre: String?) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = this.artist
        this.composer = "Unknown Composer"
    }

    constructor(
        file: File,
        title: String,
        artist: String?,
        album: String?,
        year: String?,
        genre: String?,
        albumArtist: String?
    ) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = safe(albumArtist, this.artist)
        this.composer = "Unknown Composer"
    }

    constructor(
        file: File,
        title: String,
        artist: String?,
        album: String?,
        year: String?,
        genre: String?,
        albumArtist: String?,
        composer: String?
    ) {
        this.file = file
        this.title = title
        this.artist = safe(artist, "Unknown Artist")
        this.album = safe(album, "Unknown Album")
        this.year = safe(year, "Unknown Year")
        this.genre = safe(genre, "Unknown Genre")
        this.albumArtist = safe(albumArtist, this.artist)
        this.composer = safe(composer, "Unknown Composer")
    }

    companion object {
        @JvmStatic
        private fun safe(value: String?, fallback: String): String {
            return if (!value.isNullOrBlank()) value else fallback
        }
    }
}
