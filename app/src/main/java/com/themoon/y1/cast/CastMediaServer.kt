package com.themoon.y1.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small HTTP server for the speaker: it serves only the tracks and covers that the cast session registers, each
 * under a random path. Range requests are necessary: a Sonos reads the start of the file, then seeks with
 * "Range: bytes=N-".
 */
class CastMediaServer {
    private var server: ServerSocket? = null
    /** The threads that send the files; [stop] ends them. */
    private var pool: ExecutorService? = null
    /** The open connections; [stop] closes them. */
    private val clients = HashSet<Socket>()
    private val entries = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 8
    }

    private class Entry(val file: File?, val bytes: ByteArray?, val mime: String)

    val port: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0)
        val p = Executors.newFixedThreadPool(4)
        server = s
        pool = p
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Exception) { break }
                synchronized(clients) { clients.add(client) }
                try {
                    p.execute { serve(client) }
                } catch (_: Exception) {
                    synchronized(clients) { clients.remove(client) }
                    closeQuietly(client)
                }
            }
        }, "CastMediaServer").start()
        Log.i(TAG, "listening on port ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool?.shutdown()
        pool = null
        // A write to a speaker that does not read has no timeout: close the connections, then the transfers end
        synchronized(clients) { clients.forEach { closeQuietly(it) }; clients.clear() }
        synchronized(entries) { entries.clear() }
    }

    /** Path (without host) under which the speaker can read [file]. */
    fun register(file: File): String = add(Entry(file, null, CastProtocol.mimeType(file.name)), file.extension)

    fun registerBytes(bytes: ByteArray, mime: String, ext: String): String = add(Entry(null, bytes, mime), ext)

    private fun add(entry: Entry, ext: String): String {
        val path = "/m/" + UUID.randomUUID().toString().replace("-", "") + if (ext.isNotEmpty()) ".$ext" else ""
        synchronized(entries) { entries[path] = entry }
        return path
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val c = line.indexOf(':')
                if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore('?')
            val out = socket.getOutputStream()
            val entry = synchronized(entries) { entries[path] }
            if (entry == null || (method != "GET" && method != "HEAD")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val size = entry.bytes?.size?.toLong() ?: entry.file?.length() ?: 0L
            val range = CastProtocol.parseRange(headers["range"], size)
            val first = range?.first ?: 0L
            val count = if (range != null) range.last - range.first + 1 else size
            val head = StringBuilder()
            if (range != null) {
                head.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes ${range.first}-${range.last}/$size\r\n")
            } else {
                head.append("HTTP/1.1 200 OK\r\n")
            }
            head.append("Content-Type: ${entry.mime}\r\nContent-Length: $count\r\nAccept-Ranges: bytes\r\n")
                .append("transferMode.dlna.org: Streaming\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            Log.d(TAG, "$method $path range=${headers["range"]} -> ${if (range != null) 206 else 200}")
            if (method == "GET") send(entry, first, count, out)
            out.flush()
        } catch (_: Exception) {
            // The speaker closes its probe connections early: normal
        } finally {
            synchronized(clients) { clients.remove(socket) }
            closeQuietly(socket)
        }
    }

    private fun send(entry: Entry, first: Long, count: Long, out: OutputStream) {
        val bytes = entry.bytes
        if (bytes != null) {
            out.write(bytes, first.toInt(), count.toInt())
            return
        }
        RandomAccessFile(entry.file, "r").use { raf ->
            raf.seek(first)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "CastMediaServer"
    }
}
