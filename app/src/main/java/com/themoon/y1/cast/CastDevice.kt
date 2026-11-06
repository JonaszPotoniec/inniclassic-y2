package com.themoon.y1.cast

/** A player on the network that can play a URL from the Y2. */
data class CastDevice(
    val name: String,
    val kind: Kind,
    val host: String,
    val port: Int,
    /** UPnP only: absolute control URLs of AVTransport and RenderingControl. */
    val avTransportUrl: String? = null,
    val renderingUrl: String? = null,
    /** Sonos only: the RINCON id (the UDN without "uuid:"), for the x-rincon-queue URI. */
    val rinconId: String? = null
) {
    enum class Kind(val label: String) { SONOS("Sonos"), DLNA("DLNA"), CAST("Cast") }

    val id: String get() = "${kind.name}@$host:$port"

    /** Text of the row in the Play On list. */
    val rowLabel: String get() = "$name (${kind.label})"
}

/** What the speaker plays: one track, served by [CastMediaServer]. */
data class CastMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** Cover URL: for a Sonos an HTTPS URL (its app shows no other), for the other speakers a URL of the Y2. */
    val coverUrl: String?
)

/** What the speaker reports. */
data class RemoteStatus(val state: State, val positionMs: Long, val trackUrl: String?) {
    enum class State { PLAYING, PAUSED, BUFFERING, STOPPED, FINISHED, UNKNOWN }
}
