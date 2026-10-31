package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import kotlin.random.Random

/**
 * The album that Cover Flow opens at, as on the iPod: the album of the song in Now Playing (playing or paused).
 * When there is no such album in Cover Flow (nothing in Now Playing, the FM radio, a podcast, a file that is not in
 * the library), a random album, to explore the library.
 */
object CoverFlowStart {

    /**
     * The index in [albums] (the first song of each Cover Flow album) of the album of [song], or -1.
     * Cover Flow groups the songs by album title, or by title and album artist: of the albums with the same title
     * (case ignored), the one with the same album artist (the track artist when the album artist is empty) wins,
     * then the one with the same spelling of the title. So two albums with the same title stay apart.
     */
    fun albumIndex(albums: List<SongItem>, song: SongItem?): Int {
        if (song == null) return -1
        val title = song.album.trim()
        if (title.isEmpty()) return -1
        val artist = CoverFlowOrder.artistOf(song).trim()
        var best = -1
        var bestScore = -1
        for ((i, album) in albums.withIndex()) {
            if (!album.album.trim().equals(title, ignoreCase = true)) continue
            val score = (if (CoverFlowOrder.artistOf(album).trim().equals(artist, ignoreCase = true)) 2 else 0) +
                (if (album.album.trim() == title) 1 else 0)
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        return best
    }

    /** The album of [song] in [albums], or a random album when it has none. 0 for an empty list. */
    fun startIndex(albums: List<SongItem>, song: SongItem?, random: Random = Random.Default): Int {
        if (albums.isEmpty()) return 0
        val index = albumIndex(albums, song)
        return if (index >= 0) index else random.nextInt(albums.size)
    }
}
