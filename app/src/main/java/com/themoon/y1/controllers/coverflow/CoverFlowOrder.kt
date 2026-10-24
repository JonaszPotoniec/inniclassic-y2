package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import java.text.Collator
import java.util.Locale

/**
 * Cover Flow order of the iPod classic: albums sorted by album artist (the track artist when the album
 * artist is empty), then by album title. Case and a leading "The " are ignored, and accented letters
 * sort next to their base letters.
 */
object CoverFlowOrder {

    fun sortName(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.length > 4 && trimmed.startsWith("The ", ignoreCase = true)) trimmed.substring(4).trimStart() else trimmed
    }

    fun sort(albums: MutableList<SongItem>) {
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        albums.sortWith { a, b ->
            val byArtist = collator.compare(sortName(artistOf(a)), sortName(artistOf(b)))
            if (byArtist != 0) byArtist else collator.compare(sortName(a.album), sortName(b.album))
        }
    }

    private fun artistOf(song: SongItem): String =
        if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist
}
