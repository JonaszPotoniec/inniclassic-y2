package com.themoon.y1.managers

import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvrcpMetadataLogicTest {

    private val track = AvrcpMetadataLogic.Track("Title", "Artist", "Album", 215_000L)

    @Test
    fun stringsHaveTitleArtistAlbumAndArtistAsAlbumArtist() {
        val s = AvrcpMetadataLogic.strings(track)
        assertEquals("Title", s[MediaMetadataRetriever.METADATA_KEY_TITLE])
        assertEquals("Artist", s[MediaMetadataRetriever.METADATA_KEY_ARTIST])
        assertEquals("Artist", s[MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST])
        assertEquals("Album", s[MediaMetadataRetriever.METADATA_KEY_ALBUM])
        // Key values of Android 4.4 (the MediaTek AVRCP reads key 13 as the artist).
        assertEquals(7, MediaMetadataRetriever.METADATA_KEY_TITLE)
        assertEquals(2, MediaMetadataRetriever.METADATA_KEY_ARTIST)
        assertEquals(13, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        assertEquals(1, MediaMetadataRetriever.METADATA_KEY_ALBUM)
    }

    @Test
    fun durationOnlyWhenKnown() {
        assertEquals(215_000L, AvrcpMetadataLogic.duration(track))
        assertNull(AvrcpMetadataLogic.duration(track.copy(durationMs = 0)))
        assertNull(AvrcpMetadataLogic.duration(track.copy(durationMs = -1)))
    }

    @Test
    fun newTrackOnlyWhenTitleArtistOrAlbumChanges() {
        assertTrue(AvrcpMetadataLogic.isNewTrack(null, track))
        assertFalse(AvrcpMetadataLogic.isNewTrack(track, track.copy(durationMs = 0)))
        assertTrue(AvrcpMetadataLogic.isNewTrack(track, track.copy(title = "Other")))
        assertTrue(AvrcpMetadataLogic.isNewTrack(track, track.copy(artist = "Other")))
        assertTrue(AvrcpMetadataLogic.isNewTrack(track, track.copy(album = "Other")))
    }

    @Test
    fun newTrackWaitsForItsDuration() {
        val next = track.copy(title = "Next", durationMs = 0)
        assertEquals(AvrcpMetadataLogic.Action.WAIT, AvrcpMetadataLogic.action(track, next, artChanged = true, waitedMs = 0))
        assertEquals(AvrcpMetadataLogic.Action.WAIT, AvrcpMetadataLogic.action(null, next, artChanged = false, waitedMs = 1_499))
        // The duration is known: send now.
        assertEquals(AvrcpMetadataLogic.Action.SEND, AvrcpMetadataLogic.action(track, next.copy(durationMs = 180_000), artChanged = false, waitedMs = 0))
        // The duration does not come: send the title, artist and album after the wait.
        assertEquals(AvrcpMetadataLogic.Action.SEND, AvrcpMetadataLogic.action(track, next, artChanged = false, waitedMs = AvrcpMetadataLogic.MAX_DURATION_WAIT_MS))
    }

    @Test
    fun sameTrackSendsOnlyChanges() {
        assertEquals(AvrcpMetadataLogic.Action.SKIP, AvrcpMetadataLogic.action(track, track, artChanged = false, waitedMs = 0))
        assertEquals(AvrcpMetadataLogic.Action.SEND, AvrcpMetadataLogic.action(track, track, artChanged = true, waitedMs = 0))
        // A later duration of a track that was sent without one: send it (no wait, it is not a new track).
        val noDuration = track.copy(durationMs = 0)
        assertEquals(AvrcpMetadataLogic.Action.SEND, AvrcpMetadataLogic.action(noDuration, track, artChanged = false, waitedMs = 0))
        assertEquals(AvrcpMetadataLogic.Action.SEND, AvrcpMetadataLogic.action(track, noDuration, artChanged = false, waitedMs = 0))
    }

    @Test
    fun logLineHasTheDuration() {
        assertEquals("AVRCP track data sent: \"Title\" duration 215000 ms", AvrcpMetadataLogic.logLine(track))
        assertEquals("AVRCP track data sent: \"Title\" duration -", AvrcpMetadataLogic.logLine(track.copy(durationMs = 0)))
    }

    @Test
    fun playState() {
        assertEquals(RemoteControlClient.PLAYSTATE_STOPPED, AvrcpMetadataLogic.playState(false, true))
        assertEquals(RemoteControlClient.PLAYSTATE_PLAYING, AvrcpMetadataLogic.playState(true, true))
        assertEquals(RemoteControlClient.PLAYSTATE_PAUSED, AvrcpMetadataLogic.playState(true, false))
    }

    @Test
    fun artSizeKeepsAspectAndLimitsLargestSide() {
        assertEquals(256 to 256, AvrcpMetadataLogic.artSize(600, 600))
        assertEquals(256 to 128, AvrcpMetadataLogic.artSize(800, 400))
        assertEquals(128 to 256, AvrcpMetadataLogic.artSize(400, 800))
        assertEquals(200 to 100, AvrcpMetadataLogic.artSize(200, 100))
        assertNull(AvrcpMetadataLogic.artSize(0, 100))
    }

    @Test
    fun shortTitleIsCutForTheLog() {
        assertEquals("Title", AvrcpMetadataLogic.shortTitle("Title"))
        val cut = AvrcpMetadataLogic.shortTitle("x".repeat(100))
        assertEquals(40, cut.length)
        assertEquals('…', cut.last())
    }
}
