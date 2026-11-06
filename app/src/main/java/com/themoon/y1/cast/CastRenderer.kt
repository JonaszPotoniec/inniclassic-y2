package com.themoon.y1.cast

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Control of one speaker. All calls block (network): the cast thread calls them. They throw IOException on errors. */
interface CastRenderer {
    fun load(media: CastMedia, startMs: Long, play: Boolean)
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun stop()
    fun status(): RemoteStatus
    /** 0..100, or null if the speaker does not tell. */
    fun volume(): Int?
    fun setVolume(percent: Int)
    fun close()

    companion object {
        fun forDevice(device: CastDevice): CastRenderer =
            if (device.kind == CastDevice.Kind.CAST) CastV2Renderer(device) else UpnpRenderer(device)
    }
}

/**
 * UPnP AVTransport control. A Sonos gets the track through its queue (AddURIToQueue, then x-rincon-queue): with a
 * plain SetAVTransportURI the Sonos app shows the title only, with no artist and no progress bar (test 2026-10-29).
 * The queue of the Sonos then holds only this track.
 */
class UpnpRenderer(private val device: CastDevice) : CastRenderer {
    private val isSonos = device.kind == CastDevice.Kind.SONOS && device.rinconId != null

    override fun load(media: CastMedia, startMs: Long, play: Boolean) {
        val meta = CastProtocol.escapeXml(CastProtocol.didl(media))
        val uri = CastProtocol.escapeXml(media.url)
        if (isSonos) {
            av("RemoveAllTracksFromQueue", "")
            av("AddURIToQueue", "<EnqueuedURI>$uri</EnqueuedURI><EnqueuedURIMetaData>$meta</EnqueuedURIMetaData>" +
                "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued><EnqueueAsNext>0</EnqueueAsNext>")
            av("SetAVTransportURI", "<CurrentURI>x-rincon-queue:${device.rinconId}#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
            av("Seek", "<Unit>TRACK_NR</Unit><Target>1</Target>")
        } else {
            av("SetAVTransportURI", "<CurrentURI>$uri</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>")
        }
        if (startMs > 1000 && isSonos) seek(startMs)
        if (play) play()
        // Other players often refuse a seek before they play: CastManager seeks again when the track plays
    }

    override fun play() { av("Play", "<Speed>1</Speed>") }
    override fun pause() { av("Pause", "") }
    override fun stop() { av("Stop", "") }

    override fun seek(ms: Long) {
        av("Seek", "<Unit>REL_TIME</Unit><Target>${CastProtocol.formatTime(ms)}</Target>")
    }

    override fun status(): RemoteStatus {
        val info = av("GetTransportInfo", "")
        val pos = av("GetPositionInfo", "")
        val state = when (CastProtocol.tag(info, "CurrentTransportState")) {
            "PLAYING" -> RemoteStatus.State.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteStatus.State.PAUSED
            "TRANSITIONING" -> RemoteStatus.State.BUFFERING
            "STOPPED", "NO_MEDIA_PRESENT" -> RemoteStatus.State.STOPPED
            else -> RemoteStatus.State.UNKNOWN
        }
        val uri = CastProtocol.tag(pos, "TrackURI")?.let { CastProtocol.unescapeXml(it) }
        val relTime = CastProtocol.tag(pos, "RelTime")
        var ms = CastProtocol.parseTime(relTime) ?: 0L
        // Whole seconds (a Sonos): on average the speaker is half a second further
        if (relTime != null && !relTime.contains('.') && state == RemoteStatus.State.PLAYING) ms += 500
        return RemoteStatus(state, ms, uri)
    }

    override fun volume(): Int? =
        CastProtocol.tag(rc("GetVolume", "<Channel>Master</Channel>"), "CurrentVolume")?.toIntOrNull()

    override fun setVolume(percent: Int) {
        rc("SetVolume", "<Channel>Master</Channel><DesiredVolume>${percent.coerceIn(0, 100)}</DesiredVolume>")
    }

    override fun close() {}

    private fun av(action: String, args: String) = soap(device.avTransportUrl, "AVTransport", action, args)

    private fun rc(action: String, args: String) = soap(device.renderingUrl, "RenderingControl", action, args)

    private fun soap(url: String?, service: String, action: String, args: String): String {
        if (url == null) throw IOException("$service is not available")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            c.outputStream.use { it.write(CastProtocol.soapEnvelope(service, action, args).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                throw IOException("$action: HTTP $code ${CastProtocol.tag(body, "errorCode").orEmpty()}")
            }
            return body
        } finally {
            c.disconnect()
        }
    }
}
