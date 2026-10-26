package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.LetterJump

/**
 * iPod speed scrolling in Cover Flow, with the letter mode of the lists ([LetterJump.Mode]). A fast wheel turn
 * starts it. Then each wheel step, slow or fast, moves through the artist letters of the albums, and the letter
 * box shows the letter. The covers do not move. Letter mode ends [LetterJump.IDLE_END_MS] after the last step,
 * or at a centre, Back or Play/Pause press ([onKey]): then the box fades and the covers scroll past fast to the
 * first album of that letter ([Ui.goTo]).
 */
class CoverFlowLetterJump(private val albums: List<SongItem>, private val ui: Ui) {

    interface Ui {
        fun showLetter(letter: String)
        fun hideLetter()
        /** End of letter mode: goes to album [index]. */
        fun goTo(index: Int)
        fun click()
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    private val mode = LetterJump.Mode()
    private var letters: CoverFlowLetters? = null
    /** The album count that [letters] was made for; -1 = not made. */
    private var lettersSize = -1
    private var letterShown = false
    private val idleTask = Runnable { settle() }

    /**
     * One wheel step ([dir] -1 or 1, at [timeMs]) with album [current] in the centre. Returns true when the step
     * is used here (letter mode), false when Cover Flow steps one album.
     */
    fun onStep(dir: Int, timeMs: Long, current: Int): Boolean {
        if (mode.isIdle(timeMs)) settle() // the idle timer did not run yet
        when (mode.onStep(dir, timeMs, current) { letters()?.letters }) {
            LetterJump.Step.ROW -> return false
            LetterJump.Step.LETTER -> ui.click()
            LetterJump.Step.STAY -> {}
        }
        letters?.letterAt(mode.row)?.let {
            ui.showLetter(it)
            letterShown = true
        }
        ui.cancel(idleTask)
        ui.postDelayed(idleTask, LetterJump.IDLE_END_MS)
        return true
    }

    /**
     * A key event ([keyCode], down or up, [repeatCount]). Returns true when the key is used here: in letter mode,
     * a centre press only ends it (no album opens). Back and Play/Pause end it, then work as usual (false).
     */
    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int): Boolean =
        when (mode.onKey(keyCode, isDown, repeatCount)) {
            LetterJump.Press.PASS -> false
            LetterJump.Press.DROP -> true
            LetterJump.Press.END -> { settle(); true }
            LetterJump.Press.END_AND_PASS -> { settle(); false }
        }

    /** Ends letter mode without a move, and forgets the letters (the album list can change). */
    fun reset() {
        ui.cancel(idleTask)
        mode.end()
        hideLetter()
        letters = null
        lettersSize = -1
    }

    private fun letters(): CoverFlowLetters? {
        if (lettersSize != albums.size) {
            letters = CoverFlowLetters.of(albums)
            lettersSize = albums.size
        }
        return letters
    }

    private fun hideLetter() {
        if (!letterShown) return
        letterShown = false
        ui.hideLetter()
    }

    /** The end of letter mode (idle wheel or a key): the only place that calls [Ui.goTo]. */
    private fun settle() {
        ui.cancel(idleTask)
        val album = mode.end()
        hideLetter()
        if (album >= 0) ui.goTo(album)
    }
}
