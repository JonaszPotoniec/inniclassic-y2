package com.themoon.y1.models

import java.util.Locale

/**
 * Identifies an album by its title and album artist (the track artist when the album artist is empty),
 * case-insensitively. Different albums with the same title stay apart, and a compilation stays one album
 * when its tracks share an album artist (for example "Various Artists").
 */
object AlbumKey {

    fun artistOf(song: SongItem): String =
        (if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist).trim()

    fun of(album: String, albumArtist: String): String =
        "${album.trim().lowercase(Locale.US)}||${albumArtist.trim().lowercase(Locale.US)}"

    fun of(song: SongItem): String = of(song.album, artistOf(song))

    /** True if [song] is on [album] by [albumArtist]. An empty [albumArtist] matches any artist. */
    fun matches(song: SongItem, album: String, albumArtist: String): Boolean =
        song.album.trim().equals(album.trim(), ignoreCase = true) &&
            (albumArtist.isBlank() || artistOf(song).equals(albumArtist.trim(), ignoreCase = true))

    /** The first song of each album, in the order of [songs]. */
    fun firstSongPerAlbum(songs: Iterable<SongItem>): List<SongItem> {
        val seen = HashSet<String>()
        return songs.filter { seen.add(of(it)) }
    }
}
