package com.themoon.y1.managers

import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient

/**
 * Pure rules for the track data that the Y2 gives to Bluetooth AVRCP (car displays) through
 * RemoteControlClient on Android 4.4.
 */
object AvrcpMetadataLogic {

    /** Largest side of the artwork bitmap given to RemoteControlClient. */
    const val MAX_ART_SIDE = 256

    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long)

    /** Text values for MetadataEditor.putString, by METADATA_KEY_*. */
    fun strings(track: Track): Map<Int, String> = linkedMapOf(
        MediaMetadataRetriever.METADATA_KEY_TITLE to track.title,
        MediaMetadataRetriever.METADATA_KEY_ARTIST to track.artist,
        // The MediaTek AVRCP of the Y2 ROM sends key 13 (album artist) as the artist; set both.
        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to track.artist,
        MediaMetadataRetriever.METADATA_KEY_ALBUM to track.album
    )

    /** The title for a log line: at most [max] characters. */
    fun shortTitle(title: String, max: Int = 40): String =
        if (title.length <= max) title else title.substring(0, max - 1) + "…"

    /** Duration for MetadataEditor.putLong(METADATA_KEY_DURATION), or null when not known yet. */
    fun duration(track: Track): Long? = track.durationMs.takeIf { it > 0 }

    /**
     * Longest time that the track data of a new track waits for its duration. After this time the
     * title, artist and album go to the car without the duration.
     */
    const val MAX_DURATION_WAIT_MS = 1500L

    enum class Action { SEND, WAIT, SKIP }

    /**
     * The AVRCP service of the Y2 ROM (AOSP 4.4 `Avrcp.updateMetadata`) tells the car "track changed"
     * only when the title, artist (key 13) or album changes. The car then reads the track data one time.
     * A duration that comes in a later update does not get to the car.
     */
    fun isNewTrack(last: Track?, next: Track): Boolean =
        last == null || last.title != next.title || last.artist != next.artist || last.album != next.album

    /**
     * What to do with [next]: SKIP when nothing changed since [last]; WAIT when it is a new track and its
     * duration is not known yet, until [waitedMs] gets to [MAX_DURATION_WAIT_MS]; else SEND.
     */
    fun action(last: Track?, next: Track, artChanged: Boolean, waitedMs: Long): Action = when {
        next == last && !artChanged -> Action.SKIP
        isNewTrack(last, next) && duration(next) == null && waitedMs < MAX_DURATION_WAIT_MS -> Action.WAIT
        else -> Action.SEND
    }

    /** The log line of one send: title and duration ("-" when not known). */
    fun logLine(track: Track): String =
        "AVRCP track data sent: \"" + shortTitle(track.title) + "\" duration " + (duration(track)?.let { "$it ms" } ?: "-")

    fun playState(hasTrack: Boolean, isPlaying: Boolean): Int = when {
        !hasTrack -> RemoteControlClient.PLAYSTATE_STOPPED
        isPlaying -> RemoteControlClient.PLAYSTATE_PLAYING
        else -> RemoteControlClient.PLAYSTATE_PAUSED
    }

    /** Size of the artwork copy: same aspect ratio, largest side at most [max]. */
    fun artSize(width: Int, height: Int, max: Int = MAX_ART_SIDE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        if (width <= max && height <= max) return width to height
        return if (width >= height) {
            max to maxOf(1, Math.round(height.toFloat() * max / width))
        } else {
            maxOf(1, Math.round(width.toFloat() * max / height)) to max
        }
    }
}
