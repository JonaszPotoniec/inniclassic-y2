package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class PlaybackStatePersistenceTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testPlaylistSerialization_roundTrip() {
        val f1 = tempFolder.newFile("song1.mp3")
        val f2 = tempFolder.newFile("song2.flac")
        val f3 = tempFolder.newFile("song3.m4a")

        val playlist = listOf(f1, f2, f3)
        val cacheFile = File(tempFolder.root, "last_playback_playlist.txt")

        // Serialize
        val sb = StringBuilder()
        for (f in playlist) {
            sb.append(f.absolutePath).append('\n')
        }
        cacheFile.writeText(sb.toString())

        // Deserialize
        val restored = ArrayList<File>()
        cacheFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val f = File(trimmed)
                if (f.exists()) {
                    restored.add(f)
                }
            }
        }

        assertEquals(3, restored.size)
        assertEquals(f1.absolutePath, restored[0].absolutePath)
        assertEquals(f2.absolutePath, restored[1].absolutePath)
        assertEquals(f3.absolutePath, restored[2].absolutePath)
    }

    @Test
    fun testPlaylistSerialization_ignoresDeletedFiles() {
        val f1 = tempFolder.newFile("existing.mp3")
        val nonExistentPath = File(tempFolder.root, "deleted.mp3").absolutePath

        val cacheFile = File(tempFolder.root, "last_playback_playlist.txt")
        cacheFile.writeText("${f1.absolutePath}\n$nonExistentPath\n")

        val restored = ArrayList<File>()
        cacheFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val f = File(trimmed)
                if (f.exists()) {
                    restored.add(f)
                }
            }
        }

        assertEquals(1, restored.size)
        assertEquals(f1.absolutePath, restored[0].absolutePath)
    }

    @Test
    fun testIndexResolution_clampsOutOfBounds() {
        val f1 = tempFolder.newFile("song1.mp3")
        val f2 = tempFolder.newFile("song2.mp3")
        val playlist = listOf(f1, f2)

        val targetPath = f2.absolutePath
        var savedIndex = 99 // out of bounds

        if (savedIndex !in playlist.indices || playlist[savedIndex].absolutePath != targetPath) {
            savedIndex = playlist.indexOfFirst { it.absolutePath == targetPath }
            if (savedIndex == -1) savedIndex = 0
        }

        assertEquals(1, savedIndex)
    }

    @Test
    fun testIndexResolution_recoversWhenTargetNotPresent() {
        val f1 = tempFolder.newFile("song1.mp3")
        val playlist = listOf(f1)

        val targetPath = "/some/deleted/track.mp3"
        var savedIndex = 5

        if (savedIndex !in playlist.indices || playlist[savedIndex].absolutePath != targetPath) {
            savedIndex = playlist.indexOfFirst { it.absolutePath == targetPath }
            if (savedIndex == -1) savedIndex = 0
        }

        assertEquals(0, savedIndex)
    }

    @Test
    fun testTimeFormatting_initialPositionDisplay() {
        val initialSeekMs = 75000L // 1m 15s
        val initialDurationMs = 215000L // 3m 35s

        val curS = (initialSeekMs / 1000) % 60
        val curM = initialSeekMs / (1000 * 60)
        val curFormatted = String.format(Locale.US, "%02d:%02d", curM, curS)

        val remaining = (initialDurationMs - initialSeekMs).coerceAtLeast(0)
        val remS = (remaining / 1000) % 60
        val remM = remaining / (1000 * 60)
        val remFormatted = String.format(Locale.US, "-%02d:%02d", remM, remS)
        val progress = ((initialSeekMs * 100) / initialDurationMs).toInt()

        assertEquals("01:15", curFormatted)
        assertEquals("-02:20", remFormatted)
        assertEquals(34, progress)
    }
}
