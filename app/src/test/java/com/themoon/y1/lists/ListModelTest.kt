package com.themoon.y1.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListModelTest {
    private val keys = listOf("Recently Played", "Top 25 Most Played", "Chinese Songs", "Playlist 1")

    @Test
    fun opensAtTheFirstRow() {
        assertEquals(0, ListModel.firstRow(keys, Focus.FIRST, "Chinese Songs"))
        assertEquals(0, ListModel.firstRow(keys, Focus.LAST, null))
    }

    @Test
    fun opensAtTheRememberedRowWhenTheListAsksForIt() {
        assertEquals(2, ListModel.firstRow(keys, Focus.LAST, "Chinese Songs"))
        // The remembered row is gone (playlist deleted): the first row
        assertEquals(0, ListModel.firstRow(keys, Focus.LAST, "Deleted"))
    }

    @Test
    fun aValueListOpensAtTheChosenValue() {
        val values = listOf("Off", "1", "2", "3")
        assertEquals(2, ListModel.firstRow(values, Focus.CHECKED, null, checked = 2))
        // No value is chosen (a value that is not in the list): the first row
        assertEquals(0, ListModel.firstRow(values, Focus.CHECKED, null, checked = -1))
        // The checked row counts only for Focus.CHECKED
        assertEquals(0, ListModel.firstRow(values, Focus.FIRST, null, checked = 2))
    }

    @Test
    fun aRowIsEnabledAndGivesTheFeedbackFirstByDefault() {
        val row = RowSpec("Vibration Strength")
        assertTrue(row.enabled)
        assertFalse(row.feedbackAfter)
    }

    @Test
    fun anEmptyListSelectsNoRow() {
        assertEquals(-1, ListModel.firstRow(emptyList(), Focus.FIRST, null))
        assertEquals(-1, ListModel.keptRow(emptyList(), "a", 0))
    }

    @Test
    fun keepsTheSelectedRowWhenTheRowsChange() {
        // A new network came in above the selected one: the same network stays selected
        assertEquals(3, ListModel.keptRow(listOf("Power", "Scan", "New", "Home"), "Home", 2))
        // The selected network is gone: the row at the same place
        assertEquals(2, ListModel.keptRow(listOf("Power", "Scan", "Other", "Last"), "Home", 2))
        // The list is shorter now: the last row
        assertEquals(1, ListModel.keptRow(listOf("Power", "Scan"), "Home", 5))
        // No row was selected: none
        assertEquals(-1, ListModel.keptRow(listOf("Power", "Scan"), null, -1))
    }

    @Test
    fun anEmptyListShowsOneEmptyRow() {
        val rows = ListModel.shownRows(ListSpec("Songs", empty = "No Songs"))
        assertEquals(1, rows.size)
        assertEquals("No Songs", rows[0].label)
        assertEquals(ListModel.EMPTY_KEY, rows[0].key)
        assertTrue(rows[0].onClick == null)
    }

    @Test
    fun aListWithRowsShowsItsRows() {
        val spec = ListSpec("Playlists", rows = listOf(RowSpec("A"), RowSpec("B")), empty = "None")
        assertEquals(listOf("A", "B"), ListModel.shownRows(spec).map { it.key })
        assertFalse(spec.isLong)
        assertTrue(ListModel.shownRows(ListSpec("Wi-Fi")).isEmpty())
    }

    @Test
    fun theListIdIsTheTitleByDefault() {
        assertEquals("Playlists", ListSpec("Playlists").id)
        assertEquals("", ListSpec(null).id)
    }

    @Test
    fun opensAtTheFirstRowThatCanBeSelected() {
        // An action screen: two info rows, then the actions
        val rows = listOf("Info 1", "Info 2", "Delete", "Keep")
        val notInfo: (Int) -> Boolean = { it >= 2 }
        assertEquals(2, ListModel.firstRow(rows, Focus.FIRST, null, selectable = notInfo))
        // A remembered row that cannot be selected now: the first row that can
        assertEquals(2, ListModel.firstRow(rows, Focus.LAST, "Info 1", selectable = notInfo))
        // Only info rows (a progress screen): no row
        assertEquals(-1, ListModel.firstRow(rows, Focus.FIRST, null, selectable = { false }))
    }

    @Test
    fun focusNoneSelectsNoRow() {
        assertEquals(-1, ListModel.firstRow(keys, Focus.NONE, "Chinese Songs"))
    }

    @Test
    fun theKeptRowIsARowThatCanBeSelected() {
        // Bluetooth: the selected device is gone, and a header is now at its place: the next device
        val rows = listOf("Power", "Scan", "Paired", "Speaker", "Available")
        val notHeader: (Int) -> Boolean = { rows[it] != "Paired" && rows[it] != "Available" }
        assertEquals(3, ListModel.keptRow(rows, "Car", 2, notHeader))
        // At the end only a header: the row before it
        assertEquals(3, ListModel.keptRow(rows, "Car", 4, notHeader))
    }

    @Test
    fun theLetterJumpTakesTheRowLabelsAndNotTheEmptyRow() {
        val rows = ListModel.shownRows(ListSpec("Music", rows = listOf(RowSpec("Albums"), RowSpec("Songs", key = "songs")), letters = true))
        assertEquals(listOf("Albums", "Songs"), ListModel.letterTexts(rows))
        val empty = ListModel.shownRows(ListSpec("Artists", empty = "No Songs", letters = true))
        assertEquals(listOf<String?>(null), ListModel.letterTexts(empty))
    }

    @Test
    fun aListHasNoPaneAndNoLetterJumpByDefault() {
        val spec = ListSpec("Wi-Fi")
        assertTrue(spec.pane == null)
        assertFalse(spec.letters)
    }
}
