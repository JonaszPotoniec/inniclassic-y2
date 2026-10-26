package com.themoon.y1.models

import java.util.Locale

/**
 * Compilation albums, as the iPod shows them in Music → Compilations.
 *
 * An album (one title in one folder) is a compilation when one of its tracks has the compilation flag
 * (ID3 TCMP, MP4 cpil, Vorbis COMPILATION) or the album artist "Various Artists" (or "VA").
 * When Compilations is in the Music menu, Artists does not show the tracks of these albums, so a
 * soundtrack does not add one artist for each track.
 */
object Compilations {

    /** Album artist names that mark a compilation, in lower case. */
    private val VARIOUS_ARTISTS = setOf("various artists", "va")

    fun isVariousArtists(albumArtist: String?): Boolean =
        albumArtist != null && albumArtist.trim().lowercase(Locale.US) in VARIOUS_ARTISTS

    /** Reads the value of a compilation tag: "1" (or another non-zero number), "true" or "yes". */
    fun parseFlag(value: String?): Boolean {
        val v = value?.trim()?.lowercase(Locale.US) ?: return false
        if (v.isEmpty()) return false
        val n = v.toIntOrNull()
        return if (n != null) n != 0 else v == "true" || v == "yes"
    }

    /** True if [song] itself marks its album as a compilation. */
    fun marksAlbum(song: SongItem): Boolean = song.compilationTag || isVariousArtists(song.albumArtist)

    private fun albumKey(song: SongItem): String =
        "${song.file.parent}|${song.album.trim().lowercase(Locale.US)}"

    /**
     * Sets [SongItem.isCompilation] for each song: true for all tracks of an album when one of its tracks
     * [marksAlbum]. Call it after each library load or scan, so that the browse lists only read a flag.
     */
    fun mark(songs: List<SongItem>) {
        synchronized(songs) {
            val albums = HashSet<String>()
            for (song in songs) {
                if (marksAlbum(song)) albums.add(albumKey(song))
            }
            for (song in songs) {
                song.isCompilation = albums.isNotEmpty() && albumKey(song) in albums
            }
        }
    }

    /**
     * True if the Artists list (and the albums and songs of an artist in it) must not show [song].
     * [compilationsShown] is true when Compilations is in the Music menu.
     */
    fun hiddenFromArtists(song: SongItem, compilationsShown: Boolean): Boolean =
        compilationsShown && song.isCompilation
}
