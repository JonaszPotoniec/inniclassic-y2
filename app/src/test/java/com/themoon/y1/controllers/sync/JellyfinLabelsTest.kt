package com.themoon.y1.controllers.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinLabelsTest {

    @Test
    fun libraryKinds() {
        assertEquals(JellyfinLabels.Kind.TV, JellyfinLabels.kind("tvshows"))
        assertEquals(JellyfinLabels.Kind.MUSIC, JellyfinLabels.kind("Music"))
        assertEquals(JellyfinLabels.Kind.BOOKS, JellyfinLabels.kind("audiobooks"))
        assertEquals(JellyfinLabels.Kind.MOVIES, JellyfinLabels.kind("movies"))
        assertEquals(JellyfinLabels.Kind.MOVIES, JellyfinLabels.kind(null))
    }

    @Test
    fun defaultSyncModes() {
        assertEquals("all", JellyfinLabels.defaultMode("music"))
        assertEquals("manual", JellyfinLabels.defaultMode("tvshows"))
        assertEquals("manual", JellyfinLabels.defaultMode("books"))
        assertEquals("off", JellyfinLabels.defaultMode("homevideos"))
        assertEquals(listOf("Sync All", "Manual", "Off"), JellyfinLabels.MODES.map { JellyfinLabels.modeLabel(it) })
    }

    @Test
    fun authModeGoesRound() {
        assertEquals("credentials", JellyfinLabels.nextAuthMode("quickconnect"))
        assertEquals("token", JellyfinLabels.nextAuthMode("Credentials"))
        assertEquals("quickconnect", JellyfinLabels.nextAuthMode("token"))
        assertEquals("QuickConnect", JellyfinLabels.authModeLabel("unknown"))
    }

    @Test
    fun itemLabels() {
        assertEquals("E05 - Pilot", JellyfinLabels.episode(5, "Pilot"))
        assertEquals("E12 - Finale", JellyfinLabels.episode(12, "Finale"))
        assertEquals("Special", JellyfinLabels.episode(0, "Special"))
        assertEquals("03 - Song", JellyfinLabels.track(3, "Song"))
        assertEquals("Film (2010)", JellyfinLabels.movie("Film", "2010"))
        assertEquals("Film", JellyfinLabels.movie("Film", "Unknown Year"))
        assertEquals("Film", JellyfinLabels.movie("Film", null))
    }

    @Test
    fun sizesAndTheStorageLine() {
        assertEquals("0 MB", JellyfinLabels.formatSize(0))
        assertEquals("5 MB", JellyfinLabels.formatSize(5L * 1024 * 1024))
        assertEquals("1.5 GB", JellyfinLabels.formatSize(1536L * 1024 * 1024))
        assertEquals("Selected: 2/10 (5 MB) | Free: 1.5 GB",
            JellyfinLabels.storageLine("Selected", "Free", 2, 10, 5L * 1024 * 1024, 1536L * 1024 * 1024))
        assertEquals("Selected: 0/10 | Free: 0 MB", JellyfinLabels.storageLine("Selected", "Free", 0, 10, 0, 0))
    }
}
