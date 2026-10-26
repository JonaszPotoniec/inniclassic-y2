package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.LetterJump

/**
 * The letter of each Cover Flow album for speed scrolling: the first letter of its artist sort name
 * ([CoverFlowOrder.sortName] of [CoverFlowOrder.artistOf]), without accents. Digits, symbols and blank names
 * are "#", which sorts before "A". Make it one time for each album list.
 */
class CoverFlowLetters private constructor(
    /** The letter of each album, in Cover Flow order. */
    val letters: List<String>
) {

    val size: Int get() = letters.size

    fun letterAt(index: Int): String? = letters.getOrNull(index)

    /**
     * The first album of the next letter ([direction] > 0) or of the previous letter ([direction] < 0)
     * from album [index]; -1 when there is no such letter.
     */
    fun jump(index: Int, direction: Int): Int = LetterJump.target(letters, index, direction)

    companion object {
        /** Null when the albums are not in alphabetical order of artist: then the wheel steps album by album. */
        fun of(albums: List<SongItem>): CoverFlowLetters? {
            val names = albums.map { CoverFlowOrder.sortName(CoverFlowOrder.artistOf(it)) }
            val letters = LetterJump.lettersFor(names) ?: return null
            return CoverFlowLetters(letters.map { it ?: "#" })
        }
    }
}
