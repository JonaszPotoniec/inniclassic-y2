package com.themoon.y1.models

import java.io.File

class SongItem {
    var file: File
    var title: String
    var artist: String
    var album: String
    var year: String
    var genre: String = "Unknown Genre"
        set(value) {
            field = safe(value, "Unknown Genre")
            cachedGenres = null
        }

    private var cachedGenres: List<String>? = null

    fun getGenres(): List<String> {
        var g = cachedGenres
        if (g == null) {
            g = GenreHelper.parseGenres(genre)
            cachedGenres = g
        }
        return g
    }

    fun matchesGenre(targetGenre: String?): Boolean {
        if (targetGenre.isNullOrBlank()) return false
        val target = targetGenre.trim()
        return getGenres().any { it.equals(target, ignoreCase = true) }
    }

    var albumArtist: String
    var composer: String

    /** The compilation flag of the tags (ID3 TCMP, MP4 cpil, Vorbis COMPILATION). The library cache keeps it. */
    var compilationTag: Boolean = false

    /** True if the song is on a compilation album. [Compilations.mark] sets it for all tracks of the album. */
    var isCompilation: Boolean = false

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
        internal fun safe(value: String?, fallback: String): String {
            return if (!value.isNullOrBlank()) value else fallback
        }
    }
}
