package com.themoon.y1.models

import java.util.Locale

/**
 * Identifies an album, case-insensitively, as the Settings → Group Albums setting tells ([byArtist]):
 * - By Title (the default): one album per title, so an album stays one album when its tracks have
 *   different album artists.
 * - By Title and Artist: one album per title and album artist (the track artist when the album artist is
 *   empty). Different albums with the same title stay apart, and a compilation stays one album when its
 *   tracks share an album artist (for example "Various Artists").
 */
object AlbumKey {

    /** AppPrefs key of the Group Albums setting: true = By Title and Artist. */
    const val PREF_BY_ARTIST = "group_albums_by_artist"

    @Volatile
    var byArtist = false

    fun artistOf(song: SongItem): String =
        (if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist).trim()

    fun of(album: String, albumArtist: String): String =
        "${album.trim().lowercase(Locale.US)}||${if (byArtist) albumArtist.trim().lowercase(Locale.US) else ""}"

    fun of(song: SongItem): String = of(song.album, artistOf(song))

    /** True if [song] is on [album] by [albumArtist]. An empty [albumArtist] matches any artist, as does By Title. */
    fun matches(song: SongItem, album: String, albumArtist: String): Boolean =
        song.album.trim().equals(album.trim(), ignoreCase = true) &&
            (!byArtist || albumArtist.isBlank() || artistOf(song).equals(albumArtist.trim(), ignoreCase = true))

    /** The first song of each album, in the order of [songs]. */
    fun firstSongPerAlbum(songs: Iterable<SongItem>): List<SongItem> {
        val seen = HashSet<String>()
        return songs.filter { seen.add(of(it)) }
    }
}
