package com.themoon.y1.migrations

import org.junit.Assert.assertEquals
import org.junit.Test

class Migration008PlayOnMenuItemTest {
    private fun with(order: String) = Migration008PlayOnMenuItem.withPlayOn(order)

    @Test
    fun goesAfterWifi() {
        assertEquals("btn_now,btn_music,btn_bt,btn_wifi,btn_play_on,btn_set", with("btn_now,btn_music,btn_bt,btn_wifi,btn_set"))
    }

    @Test
    fun goesAtTheEndWithoutWifi() {
        assertEquals("btn_now,btn_set,btn_play_on", with("btn_now,btn_set"))
    }

    @Test
    fun keepsAnOrderThatHasIt() {
        assertEquals("btn_play_on,btn_now,btn_wifi", with("btn_play_on,btn_now,btn_wifi"))
        assertEquals("", with(""))
    }
}
