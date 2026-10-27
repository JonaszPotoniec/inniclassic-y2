package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCountRuleTest {

    private val song = "/storage/sdcard1/Music/A/a.mp3"
    private val other = "/storage/sdcard1/Music/A/b.mp3"

    /** Plays [path] from [fromMs] for [seconds], one sample each second. Returns the samples that counted. */
    private fun PlayCountRule.play(path: String, fromMs: Long, seconds: Int, durationMs: Long, clock: LongArray): List<String> {
        val counted = ArrayList<String>()
        for (i in 0..seconds) {
            sample(path, fromMs + i * 1000L, durationMs, true, clock[0])?.let { counted.add(it) }
            clock[0] += 1000L
        }
        clock[0] -= 1000L
        return counted
    }

    @Test
    fun thresholdIsHalfTheSongButNotMoreThanFourMinutes() {
        assertEquals(90_000L, PlayCountRule.thresholdMs(180_000L))
        assertEquals(240_000L, PlayCountRule.thresholdMs(600_000L))
        assertEquals(10_000L, PlayCountRule.thresholdMs(20_000L))
        assertEquals(240_000L, PlayCountRule.thresholdMs(0L))
    }

    @Test
    fun countsOnceAtHalfOfTheSong() {
        val rule = PlayCountRule()
        val clock = longArrayOf(1_000_000L)
        assertTrue(rule.play(song, 0L, 89, 180_000L, clock).isEmpty())
        assertEquals(listOf(song), rule.play(song, 89_000L, 91, 180_000L, clock))
    }

    @Test
    fun longSongCountsAfterFourMinutes() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        val counted = rule.play(song, 0L, 300, 900_000L, clock)
        assertEquals(listOf(song), counted)
        assertTrue(rule.listened in 240_000L..300_000L)
    }

    @Test
    fun skipDoesNotCount() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        assertTrue(rule.play(song, 0L, 20, 180_000L, clock).isEmpty())
        // Next song: the first song stays not counted
        assertTrue(rule.play(other, 0L, 20, 180_000L, clock).isEmpty())
        assertTrue(rule.listened <= 20_000L)
    }

    @Test
    fun seekToTheEndDoesNotCount() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        assertTrue(rule.play(song, 0L, 10, 180_000L, clock).isEmpty())
        clock[0] += 1000L
        // A jump of 160 s in 1 s is a seek, not play
        assertTrue(rule.play(song, 170_000L, 10, 180_000L, clock).isEmpty())
        assertTrue(rule.listened < 30_000L)
    }

    @Test
    fun seekWhilePausedDoesNotCount() {
        val rule = PlayCountRule()
        assertNull(rule.sample(song, 10_000L, 180_000L, true, 0L))
        assertNull(rule.sample(song, 11_000L, 180_000L, true, 1_000L))
        assertNull(rule.sample(song, 11_000L, 180_000L, false, 2_000L))
        // Long pause, then the player is at 150 s: the first sample after the pause only sets the start
        assertNull(rule.sample(song, 150_000L, 180_000L, true, 600_000L))
        assertNull(rule.sample(song, 151_000L, 180_000L, true, 601_000L))
        assertEquals(2_000L, rule.listened)
    }

    @Test
    fun pauseKeepsTheTimeListened() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        assertTrue(rule.play(song, 0L, 50, 180_000L, clock).isEmpty())
        assertNull(rule.sample(song, 50_000L, 180_000L, false, clock[0] + 1000L))
        clock[0] += 60_000L
        assertEquals(listOf(song), rule.play(song, 50_000L, 45, 180_000L, clock))
    }

    @Test
    fun repeatOneCountsEachPlay() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        assertEquals(listOf(song), rule.play(song, 0L, 60, 60_000L, clock))
        clock[0] += 1000L
        // The same song from the start again
        assertEquals(listOf(song), rule.play(song, 500L, 59, 60_000L, clock))
    }

    @Test
    fun jumpBackBeforeTheCountDoesNotStartAgain() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        assertTrue(rule.play(song, 0L, 40, 180_000L, clock).isEmpty())
        clock[0] += 1000L
        // Back to the start before half: the time listened stays, so 50 s more makes 90 s
        assertEquals(listOf(song), rule.play(song, 0L, 50, 180_000L, clock))
    }

    @Test
    fun playbackSpeedIsNotASeek() {
        val rule = PlayCountRule()
        var listened = 0L
        for (i in 0..60) {
            rule.sample(song, i * 2000L, 180_000L, true, i * 1000L, speed = 2f)
            listened = rule.listened
        }
        assertEquals(120_000L, listened)
    }

    @Test
    fun noPathResets() {
        val rule = PlayCountRule()
        val clock = longArrayOf(0L)
        rule.play(song, 0L, 30, 180_000L, clock)
        assertNull(rule.sample(null, 0L, 0L, false, clock[0]))
        assertEquals(0L, rule.listened)
    }

    @Test
    fun audiobooksAndPodcastsAreNotMusic() {
        assertTrue(PlayCountRule.isMusicPath("/storage/sdcard1/Music/A/a.mp3"))
        assertFalse(PlayCountRule.isMusicPath("/storage/sdcard1/Audiobooks/B/1.m4b"))
        assertFalse(PlayCountRule.isMusicPath("/storage/emulated/0/Podcasts/C/ep.mp3"))
        assertFalse(PlayCountRule.isMusicPath("/PODCAST_STREAM/C/ep.mp3"))
    }
}
