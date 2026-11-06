package com.themoon.y1.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class CastCoverTest {
    private val mbid = "f36eb891-6276-452a-8fdf-cde443a6f543"

    private fun frame(version: Int, id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        val n = body.size
        if (version == 4) out.write(byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte()))
        else out.write(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private fun head(version: Int) = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0, 0, 0, 0, 0)

    @Test
    fun readsTheAlbumIdFromId3v24Utf8() {
        val tag = frame(4, "TIT2", byteArrayOf(3) + "Ghosts IV".toByteArray()) +
            frame(4, "TXXX", byteArrayOf(3) + "MusicBrainz Artist Id\u0000abc".toByteArray()) +
            frame(4, "TXXX", byteArrayOf(3) + "MusicBrainz Album Id\u0000$mbid".toByteArray()) + ByteArray(20)
        assertEquals(mbid, CastCover.musicBrainzAlbumIdFromId3(head(4), tag))
    }

    @Test
    fun readsTheAlbumIdFromId3v23Utf16() {
        val text = "MusicBrainz Album Id\u0000$mbid"
        val tag = frame(3, "TXXX", byteArrayOf(1) + text.toByteArray(Charsets.UTF_16))
        assertEquals(mbid, CastCover.musicBrainzAlbumIdFromId3(head(3), tag))
    }

    @Test
    fun noAlbumId() {
        val tag = frame(4, "TIT2", byteArrayOf(3) + "x".toByteArray())
        assertNull(CastCover.musicBrainzAlbumIdFromId3(head(4), tag))
        assertNull(CastCover.id3TagSize(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)))
    }
}
