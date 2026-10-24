package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.themoon.y1.MainActivity
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * Manages tracking and persistence of recently played albums (up to 50 distinct albums).
 * Albums are tracked with a composite key of (albumName, artistName), or of albumName only (see AlbumKey).
 * Includes a 5-second playback debounce to avoid recording rapid skips.
 */
class RecentlyPlayedManager private constructor(context: Context) {

    data class RecentAlbumEntry(
        val albumName: String,
        val artistName: String,
        val timestamp: Long
    ) {
        val compositeKey: String
            get() = AlbumKey.of(albumName, artistName)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("Y1_RECENTLY_PLAYED", Context.MODE_PRIVATE)

    private val recentAlbums = Collections.synchronizedList(ArrayList<RecentAlbumEntry>())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingRunnable: Runnable? = null
    private var pendingFilePath: String? = null

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val jsonStr = prefs.getString(KEY_RECENT_ALBUMS_JSON, null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<RecentAlbumEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val album = obj.optString("album", "").trim()
                val artist = obj.optString("artist", "").trim()
                val ts = obj.optLong("timestamp", 0L)
                if (album.isNotEmpty()) {
                    list.add(RecentAlbumEntry(album, if (artist.isNotEmpty()) artist else "Unknown Artist", ts))
                }
            }
            synchronized(recentAlbums) {
                recentAlbums.clear()
                recentAlbums.addAll(list)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load recently played albums", e)
        }
    }

    private fun saveToPrefs() {
        val copy = synchronized(recentAlbums) { ArrayList(recentAlbums) }
        try {
            val arr = JSONArray()
            for (entry in copy) {
                val obj = JSONObject().apply {
                    put("album", entry.albumName)
                    put("artist", entry.artistName)
                    put("timestamp", entry.timestamp)
                }
                arr.put(obj)
            }
            prefs.edit().putString(KEY_RECENT_ALBUMS_JSON, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save recently played albums", e)
        }
    }

    /**
     * Called when a track starts playing. Starts a 5-second debounce timer.
     * If the user skips or pauses before 5 seconds, recording is canceled.
     */
    fun onTrackStarted(file: File, fallbackAlbum: String?, fallbackArtist: String?) {
        val path = file.absolutePath
        if (isExcludedPath(path)) {
            cancelPending()
            return
        }

        cancelPending()
        pendingFilePath = path

        val runnable = Runnable {
            val apm = AudioPlayerManager.getInstance()
            val currentPath = apm.currentlyPlayingFilePath
            val isPlaying = apm.isPlaying()
            if (currentPath == path && isPlaying) {
                commitSongPlayed(file, fallbackAlbum, fallbackArtist)
            }
            pendingRunnable = null
            pendingFilePath = null
        }
        pendingRunnable = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    fun onPlaybackPausedOrStopped() {
        cancelPending()
    }

    private fun cancelPending() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
        pendingFilePath = null
    }

    private fun commitSongPlayed(file: File, fallbackAlbum: String?, fallbackArtist: String?) {
        // Look up matching song in active custom library if possible
        val librarySong = MainActivity.customLibrary.find { it.file.absolutePath == file.absolutePath }

        val album = librarySong?.album?.trim()?.ifEmpty { null }
            ?: fallbackAlbum?.trim()?.ifEmpty { null }
            ?: "Unknown Album"

        val artist = librarySong?.let {
            if (it.albumArtist.isNotBlank()) it.albumArtist.trim() else it.artist.trim()
        }?.ifEmpty { null }
            ?: fallbackArtist?.trim()?.ifEmpty { null }
            ?: "Unknown Artist"

        val newEntry = RecentAlbumEntry(album, artist, System.currentTimeMillis())
        val targetKey = newEntry.compositeKey

        synchronized(recentAlbums) {
            // Remove existing entry with identical composite key (case-insensitive)
            val iterator = recentAlbums.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.compositeKey.equals(targetKey, ignoreCase = true)) {
                    iterator.remove()
                    break
                }
            }

            // Insert at the front (most recent)
            recentAlbums.add(0, newEntry)

            // Limit to MAX_RECENT_ALBUMS
            while (recentAlbums.size > MAX_RECENT_ALBUMS) {
                recentAlbums.removeAt(recentAlbums.size - 1)
            }
        }

        saveToPrefs()
    }

    /**
     * Returns the list of recent album entries that currently exist in the active media library.
     */
    fun getRecentAlbums(activeLibrary: List<SongItem>): List<RecentAlbumEntry> {
        val copy = synchronized(recentAlbums) { ArrayList(recentAlbums) }
        if (activeLibrary.isEmpty()) return copy

        val existingAlbums = HashSet<String>()
        for (song in activeLibrary) {
            val a = song.album.trim().lowercase(Locale.US)
            val ar = (if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist).trim().lowercase(Locale.US)
            existingAlbums.add("$a||$ar")
            existingAlbums.add(a) // Also allow fallback by album name alone
        }

        return copy.filter { entry ->
            val key = entry.compositeKey
            val albumOnly = entry.albumName.trim().lowercase(Locale.US)
            existingAlbums.contains(key) || existingAlbums.contains(albumOnly)
        }.distinctBy { it.compositeKey }
    }

    fun clear() {
        cancelPending()
        synchronized(recentAlbums) {
            recentAlbums.clear()
        }
        prefs.edit().remove(KEY_RECENT_ALBUMS_JSON).apply()
    }

    private fun isExcludedPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.contains("/audiobooks/") ||
                lower.contains("/podcasts/") ||
                lower.contains("/sound_effects/") ||
                lower.startsWith("/podcast_stream")
    }

    companion object {
        private const val TAG = "RecentlyPlayedManager"
        private const val KEY_RECENT_ALBUMS_JSON = "recent_albums_json"
        const val MAX_RECENT_ALBUMS = 50
        const val DEBOUNCE_MS = 5000L

        @Volatile
        private var instance: RecentlyPlayedManager? = null

        fun getInstance(context: Context? = null): RecentlyPlayedManager {
            val existing = instance
            if (existing != null) return existing

            val ctx = context ?: MainActivity.instance ?: throw IllegalStateException("Context required to init RecentlyPlayedManager")
            return synchronized(this) {
                instance ?: RecentlyPlayedManager(ctx).also { instance = it }
            }
        }
    }
}
