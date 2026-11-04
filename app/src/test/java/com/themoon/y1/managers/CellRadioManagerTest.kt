package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellRadioManagerTest {

    @Test
    fun parseRadios_trimsAndDropsEmptyItems() {
        assertEquals(listOf("cell", "wifi"), CellRadioManager.parseRadios(" cell , ,wifi,"))
    }

    @Test
    fun parseRadios_usesAndroidDefaultWhenNotSet() {
        val expected = listOf("cell", "bluetooth", "wifi", "nfc", "wimax")
        assertEquals(expected, CellRadioManager.parseRadios(null))
        assertEquals(expected, CellRadioManager.parseRadios(""))
    }

    @Test
    fun isCellOnly() {
        assertTrue(CellRadioManager.isCellOnly("cell"))
        assertTrue(CellRadioManager.isCellOnly(" cell,cell "))
        assertFalse(CellRadioManager.isCellOnly("cell,bluetooth,wifi,nfc,wimax"))
        assertFalse(CellRadioManager.isCellOnly("wifi"))
        assertFalse(CellRadioManager.isCellOnly(null))
    }

    @Test
    fun isCellRadioOn() {
        assertTrue(CellRadioManager.isCellRadioOn(false, "cell"))
        assertFalse(CellRadioManager.isCellRadioOn(true, "cell"))
        assertFalse(CellRadioManager.isCellRadioOn(true, null))
        assertTrue(CellRadioManager.isCellRadioOn(true, "bluetooth,wifi"))
    }

    @Test
    fun keepsCellRadioOff_onlyOnTheY2() {
        assertTrue(CellRadioManager.keepsCellRadioOff("Y2"))
        assertFalse(CellRadioManager.keepsCellRadioOff("Y1"))
        assertFalse(CellRadioManager.keepsCellRadioOff(""))
        assertFalse(CellRadioManager.keepsCellRadioOff(null))
    }

    @Test
    fun turnOffCommand_limitsAirplaneModeToCellFirst() {
        val cmd = CellRadioManager.TURN_OFF_COMMAND
        val radios = cmd.indexOf("settings put global airplane_mode_radios cell;")
        val on = cmd.indexOf("settings put global airplane_mode_on 1;")
        val broadcast = cmd.indexOf("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
        assertTrue(radios >= 0 && on > radios && broadcast > on)
    }

    @Test
    fun turnOffCommand_cannotTurnTheRadioOn() {
        assertFalse(CellRadioManager.TURN_OFF_COMMAND.contains("airplane_mode_on 0"))
        assertFalse(CellRadioManager.TURN_OFF_COMMAND.contains("state false"))
    }

    private fun next(cellOn: Boolean = true, busy: Boolean = false, stopped: Boolean = false, msSinceLastTry: Long? = null) =
        CellRadioManager.nextAction(cellOn, busy, stopped, msSinceLastTry)

    @Test
    fun nextAction_turnsOffOnceWhenTheRadioIsOn() {
        assertEquals(CellRadioManager.Action.TURN_OFF, next())
    }

    @Test
    fun nextAction_doesNothingWhenTheRadioIsOff() {
        assertEquals(CellRadioManager.Action.NONE, next(cellOn = false))
        assertEquals(CellRadioManager.Action.NONE, next(cellOn = false, msSinceLastTry = 10))
    }

    @Test
    fun nextAction_ownCommandStartsNoOtherCommand() {
        assertEquals(CellRadioManager.Action.NONE, next(busy = true))
        assertEquals(CellRadioManager.Action.NONE, next(busy = true, msSinceLastTry = 10))
        assertEquals(CellRadioManager.Action.NONE, next(cellOn = false, msSinceLastTry = 500))
    }

    @Test
    fun nextAction_noMoreTriesAfterRootFailed() {
        assertEquals(CellRadioManager.Action.NONE, next(stopped = true))
        assertEquals(CellRadioManager.Action.NONE, next(stopped = true, msSinceLastTry = CellRadioManager.FIGHT_WINDOW_MS * 100))
    }

    @Test
    fun nextAction_stopsWhenAnotherToolTurnsTheRadioOnAgainSoon() {
        assertEquals(CellRadioManager.Action.STOP, next(msSinceLastTry = 0))
        assertEquals(CellRadioManager.Action.STOP, next(msSinceLastTry = CellRadioManager.FIGHT_WINDOW_MS - 1))
    }

    @Test
    fun nextAction_triesAgainAfterALongTime() {
        assertEquals(CellRadioManager.Action.TURN_OFF, next(msSinceLastTry = CellRadioManager.FIGHT_WINDOW_MS))
    }
}
