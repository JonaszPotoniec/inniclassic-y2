package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.LetterJump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverFlowLetterJumpTest {

    private class FakeUi : CoverFlowLetterJump.Ui {
        val shown = mutableListOf<String>()
        var hidden = 0
        var wentTo: Int? = null
        var clicks = 0
        var pending: Runnable? = null
        override fun showLetter(letter: String) { shown += letter }
        override fun hideLetter() { hidden++ }
        override fun goTo(index: Int) { wentTo = index }
        override fun click() { clicks++ }
        override fun postDelayed(task: Runnable, delayMs: Long) { pending = task }
        override fun cancel(task: Runnable) { if (pending === task) pending = null }
    }

    // A A B B C C D D E E: the first album of each letter is at an even index.
    private val albums = "ABCDE".flatMap { l ->
        (1..2).map { SongItem(File("/Music/$l$it.mp3"), "01", "$l artist", "$l album $it", null, null, null) }
    }

    /** Four steps 50 ms apart from [start]: the fourth is fast. Returns the result of each step. */
    private fun fastTurn(jump: CoverFlowLetterJump, dir: Int, start: Long, current: Int, steps: Int = 4) =
        (0 until steps).map { jump.onStep(dir, start + 50L * it, current) }

    @Test
    fun slowStepsMoveTheCovers() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        for (t in 0L..1000L step 120) assertFalse(jump.onStep(1, t, 0))
        assertTrue(ui.shown.isEmpty())
    }

    @Test
    fun fastTurnMovesThroughTheLettersThenSettlesOnTheFirstAlbum() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        assertEquals(listOf(false, false, false, true), fastTurn(jump, 1, 0, current = 1))
        assertEquals(listOf("B"), ui.shown)
        assertTrue(jump.onStep(1, 200, 1))
        assertTrue(jump.onStep(1, 250, 1))
        assertEquals(listOf("B", "C", "D"), ui.shown)
        assertEquals(3, ui.clicks)
        assertNull(ui.wentTo) // the covers wait for the end of letter mode
        ui.pending!!.run() // no wheel step for LetterJump.IDLE_END_MS
        assertEquals(6, ui.wentTo)
        assertEquals(1, ui.hidden) // the box fades at the end
    }

    @Test
    fun slowStepsMoveOneLetterEachAndTheBoxStays() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, -1, 0, current = 9) // from E back to D
        assertTrue(jump.onStep(-1, 500, 9)) // slow: one more letter, to C
        assertTrue(jump.onStep(-1, 1100, 9)) // slower: to B
        assertEquals(listOf("D", "C", "B"), ui.shown)
        assertEquals(0, ui.hidden)
        assertNull(ui.wentTo)
        assertTrue(jump.onStep(1, 1300, 9)) // the other way: back to C
        assertEquals("C", ui.shown.last())
        ui.pending!!.run()
        assertEquals(4, ui.wentTo)
        assertFalse(jump.onStep(-1, 2500, 4)) // then album by album again
    }

    @Test
    fun aLateStepAfterTheIdleTimeEndsLetterModeFirst() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, 1, 0, current = 1) // last step at 150 ms
        // The idle timer did not run (busy main thread), and a step comes later than the idle time.
        assertFalse(jump.onStep(1, 150 + LetterJump.IDLE_END_MS, 1))
        assertEquals(2, ui.wentTo)
        assertEquals(1, ui.hidden)
    }

    @Test
    fun centrePressEndsLetterModeAndOpensNothing() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        assertFalse(jump.onKey(CENTRE, isDown = true, repeatCount = 0)) // not in letter mode: opens the album
        assertFalse(jump.onKey(CENTRE, isDown = false, repeatCount = 0))
        fastTurn(jump, 1, 0, current = 1)
        assertTrue(jump.onKey(CENTRE, isDown = true, repeatCount = 0))
        assertEquals(2, ui.wentTo)
        assertEquals(1, ui.hidden)
        assertNull(ui.pending)
        assertTrue(jump.onKey(CENTRE, isDown = false, repeatCount = 0)) // the release opens nothing
        assertFalse(jump.onKey(CENTRE, isDown = true, repeatCount = 0)) // the next press opens the album
    }

    @Test
    fun backAndPlayEndLetterModeAndWorkAsUsual() {
        for (key in listOf(BACK, PLAY_PAUSE)) {
            val ui = FakeUi()
            val jump = CoverFlowLetterJump(albums, ui)
            fastTurn(jump, 1, 0, current = 3)
            assertFalse(jump.onKey(key, isDown = true, repeatCount = 0))
            assertEquals(4, ui.wentTo)
            assertEquals(1, ui.hidden)
        }
    }

    @Test
    fun noLetterAfterTheLastOneStays() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, 1, 0, current = 9)
        assertEquals(listOf("E"), ui.shown)
        ui.pending!!.run()
        assertEquals(9, ui.wentTo) // the album in the centre: Cover Flow does not move
    }

    @Test
    fun resetEndsTheJumpWithoutAMove() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, 1, 0, current = 0)
        jump.reset()
        assertNull(ui.pending)
        assertEquals(1, ui.hidden)
        assertFalse(jump.onStep(1, 1000, 0))
        assertNull(ui.wentTo)
    }

    private companion object {
        // android.view.KeyEvent codes
        const val BACK = 4
        const val CENTRE = 23
        const val PLAY_PAUSE = 85
    }
}
