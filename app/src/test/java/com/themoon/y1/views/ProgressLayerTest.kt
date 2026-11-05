package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressLayerTest {

    @Test
    fun aNewMessageReplacesTheOldOne() {
        assertEquals("Scanning", ProgressLayer.messageFor("Scanning", "Please wait...", showing = true, fallback = "Please wait..."))
        assertEquals("Scanning", ProgressLayer.messageFor("Scanning", null, showing = false, fallback = "Please wait..."))
    }

    @Test
    fun noMessageKeepsTheMessageOfALayerThatShows() {
        // A Music list opened during a scan shows the scan
        assertEquals("Scanning Media: 40%", ProgressLayer.messageFor(null, "Scanning Media: 40%", showing = true, fallback = "Please wait..."))
    }

    @Test
    fun noMessageOnANewLayerShowsTheFallback() {
        // Before, a Podcasts search showed the old "Preparing to scan" text
        assertEquals("Please wait...", ProgressLayer.messageFor(null, "Preparing to scan...", showing = false, fallback = "Please wait..."))
        assertEquals("Please wait...", ProgressLayer.messageFor(null, "", showing = true, fallback = "Please wait..."))
    }
}
