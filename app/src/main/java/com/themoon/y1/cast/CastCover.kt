package com.themoon.y1.cast

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * HTTPS cover for the Sonos app (it does not load the HTTP cover of the Y2, test 2026-10-29): the Cover Art
 * Archive image of the MusicBrainz release in the file tags (beets writes "MusicBrainz Album Id").
 */
object CastCover {
    private const val TAG = "CastCover"
    private val cache = HashMap<String, String?>()
    @Volatile private var offlineUntil = 0L

    /** Blocks (network, at most ~3 s once for each album): call it from the cast thread. */
    fun httpsCoverUrl(file: File): String? {
        val mbid = try { musicBrainzAlbumId(file) } catch (_: Exception) { null } ?: return null
        synchronized(cache) { if (cache.containsKey(mbid)) return cache[mbid] }
        if (System.currentTimeMillis() < offlineUntil) return null
        val url = try {
            resolve("https://coverartarchive.org/release/$mbid/front-500")
        } catch (e: Exception) {
            Log.w(TAG, "cover of $mbid: $e")
            offlineUntil = System.currentTimeMillis() + 5 * 60_000L
            return null
        }
        synchronized(cache) { cache[mbid] = url }
        return url
    }

    /** The final image URL after the redirects (the Sonos app may not follow them), or null for no cover (404). */
    private fun resolve(start: String): String? {
        var url = start
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                if (c is HttpsURLConnection) tls12?.let { c.sslSocketFactory = it }
                c.instanceFollowRedirects = false
                c.requestMethod = "HEAD"
                c.connectTimeout = 2500
                c.readTimeout = 2500
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val next = c.getHeaderField("Location") ?: return null
                        url = URL(URL(url), next).toString()
                    }
                    code in 200..299 -> return if (url.startsWith("https://")) url else null
                    else -> return null
                }
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /**
     * Android 4.4 HTTPS uses TLS 1.0, and the Cover Art Archive needs TLS 1.2: use Conscrypt (installed at app
     * start), as the other HTTPS clients of the app do. Only the image URL is read, so the certificate is not checked
     * (the 4.4 root certificates are old).
     */
    private val tls12: SSLSocketFactory? by lazy {
        try {
            SSLContext.getInstance("TLS", "Conscrypt")
                .apply { init(null, arrayOf<TrustManager>(CastV2Renderer.TrustAll), SecureRandom()) }.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt: $e")
            null
        }
    }

    fun musicBrainzAlbumId(file: File): String? {
        if (!file.name.endsWith(".mp3", ignoreCase = true)) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(10)
            if (raf.read(head) < 10) return null
            val size = id3TagSize(head) ?: return null
            val tag = ByteArray(minOf(size, 512 * 1024))
            raf.readFully(tag)
            return musicBrainzAlbumIdFromId3(head, tag)
        }
    }

    /** Size of the ID3v2 tag after its 10-byte header, or null if the file has no ID3v2 tag. */
    fun id3TagSize(head: ByteArray): Int? {
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        return syncsafe(head, 6)
    }

    /** The "MusicBrainz Album Id" TXXX frame of an ID3v2.3 or v2.4 tag ([head] = the 10-byte header). */
    fun musicBrainzAlbumIdFromId3(head: ByteArray, tag: ByteArray): String? {
        val version = head[3].toInt()
        if (version != 3 && version != 4) return null
        var pos = 0
        if (head[5].toInt() and 0x40 != 0 && tag.size >= 4) {
            // Extended header
            pos = if (version == 4) syncsafe(tag, 0) else 4 + be32(tag, 0)
        }
        while (pos + 10 <= tag.size) {
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (version == 4) syncsafe(tag, pos + 4) else be32(tag, pos + 4)
            val start = pos + 10
            if (size <= 0 || start + size > tag.size) break
            if (id == "TXXX") {
                val fields = decodeText(tag, start, size).split('\u0000').filter { it.isNotEmpty() }
                if (fields.size >= 2 && fields[0].equals("MusicBrainz Album Id", ignoreCase = true)) {
                    val v = fields[1].trim()
                    if (Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(v)) return v
                }
            }
            pos = start + size
        }
        return null
    }

    private fun decodeText(b: ByteArray, start: Int, size: Int): String {
        val charset = when (b[start].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, start + 1, size - 1, charset)
    }

    private fun syncsafe(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0x7f) shl 21) or ((b[i + 1].toInt() and 0x7f) shl 14) or
            ((b[i + 2].toInt() and 0x7f) shl 7) or (b[i + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
}
