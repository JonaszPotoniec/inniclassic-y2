package com.themoon.y1.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowFocusTest {
    private val all = { _: Int -> true }

    private fun next(count: Int, current: Int, delta: Int, loop: Boolean, selectable: (Int) -> Boolean = all) =
        RowFocus.next(count, current, delta, loop, selectable)

    @Test
    fun stepsOneRowInTheWheelDirection() {
        assertEquals(3, next(5, 2, 1, loop = false))
        assertEquals(1, next(5, 2, -1, loop = false))
    }

    @Test
    fun stopsAtTheEndsWhenLoopIsOff() {
        assertNull(next(5, 4, 1, loop = false))
        assertNull(next(5, 0, -1, loop = false))
    }

    @Test
    fun goesRoundAtTheEndsWhenLoopIsOn() {
        assertEquals(0, next(5, 4, 1, loop = true))
        assertEquals(4, next(5, 0, -1, loop = true))
    }

    @Test
    fun skipsRowsThatCannotBeSelected() {
        // Rows 0 and 3 are group headers, row 5 is an info row
        val rows = { i: Int -> i != 0 && i != 3 && i != 5 }
        assertEquals(4, next(6, 2, 1, loop = false, selectable = rows))
        assertEquals(2, next(6, 4, -1, loop = false, selectable = rows))
        assertNull(next(6, 4, 1, loop = false, selectable = rows))
        assertNull(next(6, 1, -1, loop = false, selectable = rows))
        assertEquals(1, next(6, 4, 1, loop = true, selectable = rows))
        assertEquals(4, next(6, 1, -1, loop = true, selectable = rows))
    }

    @Test
    fun selectsTheFirstSelectableRowWhenNoRowIsSelected() {
        val rows = { i: Int -> i >= 2 }
        assertEquals(2, next(5, -1, 1, loop = false, selectable = rows))
        assertEquals(2, next(5, -1, -1, loop = true, selectable = rows))
    }

    @Test
    fun staysWhenNoOtherRowCanBeSelected() {
        assertNull(next(1, 0, 1, loop = true))
        assertNull(next(3, 1, 1, loop = true) { it == 1 })
        assertNull(next(0, -1, 1, loop = true))
        assertNull(next(3, -1, 1, loop = true) { false })
    }
}
