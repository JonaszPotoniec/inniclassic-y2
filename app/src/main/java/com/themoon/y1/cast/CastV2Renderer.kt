package com.themoon.y1.cast

import android.annotation.SuppressLint
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Google Cast (Cast v2) control without Google Play Services: a TLS connection to port 8009, then Google's
 * Default Media Receiver plays the URL. A Cast device has a self-signed certificate, so the connection does not
 * check it. Android 4.4 has TLS 1.2 but does not turn it on by default: the socket turns it on.
 */
class CastV2Renderer(private val device: CastDevice) : CastRenderer {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val lock = Object()
    private var requestId = 0
    @Volatile private var closed = false

    // State from the device (guarded by lock)
    private var transportId: String? = null
    private var appSessionId: String? = null
    private var mediaSessionId: Int? = null
    private var media: JSONObject? = null
    private var mediaStatusCount = 0
    private var receiverStatusCount = 0
    private var volumeLevel: Double? = null
    private var loadError: String? = null
    private var contentId: String? = null

    override fun load(media: CastMedia, startMs: Long, play: Boolean) {
        val tid = ensureApp()
        val metadata = JSONObject().put("metadataType", 3).put("title", media.title)
        if (media.artist.isNotEmpty()) metadata.put("artist", media.artist)
        if (media.album.isNotEmpty()) metadata.put("albumName", media.album)
        media.coverUrl?.let { metadata.put("images", JSONArray().put(JSONObject().put("url", it))) }
        val m = JSONObject().put("contentId", media.url).put("contentType", media.mimeType)
            .put("streamType", "BUFFERED").put("metadata", metadata)
        if (media.durationMs > 0) m.put("duration", media.durationMs / 1000.0)
        val before = synchronized(lock) {
            loadError = null
            contentId = media.url
            mediaStatusCount
        }
        send(tid, NS_MEDIA, JSONObject().put("type", "LOAD").put("media", m).put("autoplay", play)
            .put("currentTime", startMs / 1000.0))
        // Wait for the first status of the new media
        waitFor(10000) { loadError != null || (mediaStatusCount > before && mediaSessionId != null && this.media?.optString("contentId") == media.url) }
        synchronized(lock) {
            loadError?.let { throw IOException("LOAD: $it") }
            if (this.media?.optString("contentId") != media.url) throw IOException("LOAD: no answer")
        }
    }

    override fun play() = mediaCommand("PLAY")
    override fun pause() = mediaCommand("PAUSE")
    override fun seek(ms: Long) = mediaCommand("SEEK", JSONObject().put("currentTime", ms / 1000.0))

    override fun stop() {
        val (tid, sid) = synchronized(lock) { transportId to appSessionId }
        try { mediaCommand("STOP") } catch (_: IOException) {}
        // Close the receiver app, so the TV goes back to its home screen
        if (tid != null && sid != null) send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", sid))
    }

    override fun status(): RemoteStatus {
        if (synchronized(lock) { transportId } == null) reattach()
        val tid = synchronized(lock) { transportId } ?: return RemoteStatus(RemoteStatus.State.STOPPED, 0, null)
        val before = synchronized(lock) { mediaStatusCount }
        send(tid, NS_MEDIA, JSONObject().put("type", "GET_STATUS"))
        waitFor(2000) { mediaStatusCount > before || transportId == null }
        synchronized(lock) {
            if (transportId == null) return RemoteStatus(RemoteStatus.State.STOPPED, 0, null)
            val st = media ?: return RemoteStatus(RemoteStatus.State.STOPPED, 0, null)
            val state = when (st.optString("playerState")) {
                "PLAYING" -> RemoteStatus.State.PLAYING
                "PAUSED" -> RemoteStatus.State.PAUSED
                "BUFFERING", "LOADING" -> RemoteStatus.State.BUFFERING
                "IDLE" -> if (st.optString("idleReason") == "FINISHED") RemoteStatus.State.FINISHED else RemoteStatus.State.STOPPED
                else -> RemoteStatus.State.UNKNOWN
            }
            val pos = (st.optDouble("currentTime", 0.0) * 1000).toLong()
            return RemoteStatus(state, pos, st.optJSONObject("media")?.optString("contentId") ?: contentId)
        }
    }

    override fun volume(): Int? {
        receiverStatus(2000)
        return synchronized(lock) { volumeLevel?.let { Math.round(it * 100).toInt() } }
    }

    override fun setVolume(percent: Int) {
        connect()
        send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "SET_VOLUME")
            .put("volume", JSONObject().put("level", percent.coerceIn(0, 100) / 100.0)))
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // --- connection ---

    private fun mediaCommand(type: String, extra: JSONObject? = null) {
        val (tid, msid) = synchronized(lock) { transportId to mediaSessionId }
        if (tid == null || msid == null) throw IOException("$type: no media session")
        val p = extra ?: JSONObject()
        send(tid, NS_MEDIA, p.put("type", type).put("mediaSessionId", msid))
    }

    /** After a lost connection: connects again to the receiver app if it still runs (it does not start it). */
    private fun reattach() {
        receiverStatus(3000)
        synchronized(lock) { transportId }?.let { send(it, NS_CONNECTION, JSONObject().put("type", "CONNECT")) }
    }

    /** Starts the Default Media Receiver if it does not run, connects to it and returns its transport id. */
    private fun ensureApp(): String {
        receiverStatus(3000)
        if (synchronized(lock) { transportId } == null) {
            send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP_ID))
            waitFor(15000) { transportId != null }
        }
        val tid = synchronized(lock) { transportId } ?: throw IOException("LAUNCH: no answer")
        send(tid, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        return tid
    }

    /** Connects if necessary, asks for the receiver status and waits for it (at most [timeoutMs]). */
    private fun receiverStatus(timeoutMs: Long) {
        connect()
        val before = synchronized(lock) { receiverStatusCount }
        send(RECEIVER, NS_RECEIVER, JSONObject().put("type", "GET_STATUS"))
        waitFor(timeoutMs) { receiverStatusCount > before }
    }

    @Synchronized
    private fun connect() {
        if (socket?.isClosed == false) return
        if (closed) throw IOException("closed")
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustAll), null)
        val plain = Socket()
        plain.connect(InetSocketAddress(device.host, device.port), 4000)
        // A receiver that does not finish the handshake must not block the cast thread: a timeout until the read loop
        plain.soTimeout = 5000
        val raw = ctx.socketFactory.createSocket(plain, device.host, device.port, true) as SSLSocket
        raw.enabledProtocols = raw.supportedProtocols
        raw.soTimeout = 5000
        try {
            raw.startHandshake()
        } catch (e: IOException) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
        raw.soTimeout = 0
        socket = raw
        out = raw.outputStream
        synchronized(lock) { transportId = null; mediaSessionId = null; media = null }
        Log.i(TAG, "connected to ${device.host}:${device.port} (${raw.session.protocol})")
        Thread({ readLoop(raw) }, "CastV2Read").start()
        Thread({ heartbeat(raw) }, "CastV2Ping").start()
        send(RECEIVER, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
    }

    private fun send(destination: String, namespace: String, payload: JSONObject) {
        val type = payload.optString("type")
        if (type != "CONNECT" && type != "PING" && type != "PONG") {
            synchronized(lock) { payload.put("requestId", ++requestId) }
        }
        val frame = CastProtocol.castFrame(SENDER, destination, namespace, payload.toString())
        val o = out ?: throw IOException("not connected")
        synchronized(o) { o.write(frame); o.flush() }
    }

    private fun heartbeat(s: SSLSocket) {
        while (!s.isClosed && socket === s) {
            try {
                Thread.sleep(5000)
                send(RECEIVER, NS_HEARTBEAT, JSONObject().put("type", "PING"))
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun readLoop(s: SSLSocket) {
        try {
            val input = DataInputStream(s.inputStream)
            while (true) {
                val n = input.readInt()
                if (n <= 0 || n > 1 shl 20) throw IOException("bad frame length $n")
                val b = ByteArray(n)
                input.readFully(b)
                handle(CastProtocol.parseCastMessage(b))
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "connection ended: $e")
        } finally {
            try { s.close() } catch (_: Exception) {}
            synchronized(lock) { transportId = null; (lock as Object).notifyAll() }
        }
    }

    private fun handle(m: CastProtocol.CastMessage) {
        val p = try { JSONObject(m.payload) } catch (_: Exception) { return }
        val type = p.optString("type")
        if (type == "PING") {
            try { send(m.source, NS_HEARTBEAT, JSONObject().put("type", "PONG")) } catch (_: Exception) {}
            return
        }
        synchronized(lock) {
            when (type) {
                "RECEIVER_STATUS" -> {
                    val status = p.optJSONObject("status")
                    status?.optJSONObject("volume")?.let { v -> if (v.has("level")) volumeLevel = v.optDouble("level") }
                    val apps = status?.optJSONArray("applications")
                    var app: JSONObject? = null
                    for (i in 0 until (apps?.length() ?: 0)) {
                        val a = apps!!.optJSONObject(i)
                        if (a?.optString("appId") == APP_ID) app = a
                    }
                    transportId = app?.optString("transportId")?.takeIf { it.isNotEmpty() }
                    appSessionId = app?.optString("sessionId")
                    if (app == null) { mediaSessionId = null; media = null }
                    receiverStatusCount++
                }
                "MEDIA_STATUS" -> {
                    val list = p.optJSONArray("status")
                    val st = if (list != null && list.length() > 0) list.optJSONObject(0) else null
                    if (st != null) {
                        mediaSessionId = st.optInt("mediaSessionId")
                        // A status can leave out "media": keep the last one
                        val prevMedia = media?.optJSONObject("media")
                        if (!st.has("media") && prevMedia != null) st.put("media", prevMedia)
                        media = st.put("contentId", st.optJSONObject("media")?.optString("contentId") ?: contentId)
                    } else {
                        media = null
                    }
                    mediaStatusCount++
                }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> loadError = type + " " + p.optString("reason")
                "CLOSE" -> if (m.source == transportId) { transportId = null; mediaSessionId = null; media = null }
            }
            (lock as Object).notifyAll()
        }
    }

    private fun waitFor(timeoutMs: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!done()) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return
                (lock as Object).wait(left)
            }
        }
    }

    /** Accepts each certificate: a Cast device has a self-signed one ([CastCover] also uses it). */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    internal object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        private const val TAG = "CastV2"
        private const val APP_ID = "CC1AD845" // Default Media Receiver
        private const val SENDER = "sender-y2"
        private const val RECEIVER = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}
