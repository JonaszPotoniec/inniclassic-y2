package com.themoon.y1.managers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scrobble check after [PlayCountRule] counted a play (the rule itself: [PlayCountRuleTest]). */
class LastFmScrobblerTest {

    @Test
    fun countedPlayIsAScrobbleOnce() {
        assertTrue(LastFmScrobbler.isScrobble(submitted = false, durationMs = 180_000L))
        assertFalse(LastFmScrobbler.isScrobble(submitted = true, durationMs = 180_000L))
    }

    @Test
    fun trackShorterThan30SecondsIsNotAScrobble() {
        assertFalse(LastFmScrobbler.isScrobble(submitted = false, durationMs = 29_999L))
        assertTrue(LastFmScrobbler.isScrobble(submitted = false, durationMs = 30_000L))
    }

    @Test
    fun longTrackIsAScrobble() {
        assertTrue(LastFmScrobbler.isScrobble(submitted = false, durationMs = 900_000L))
    }
}
