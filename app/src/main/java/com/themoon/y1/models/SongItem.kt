package com.themoon.y1.models

import java.io.File

class SongItem {
    var file: File
    var title: String
    var artist: String
    var album: String
    var year: String
    var genre: String
    var albumArtist: String
    var composer: String

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
        private fun safe(value: String?, fallback: String): String {
            return if (!value.isNullOrBlank()) value else fallback
        }
    }
}
