package com.themoon.y1.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.themoon.y1.StoragePaths
import java.io.File
import java.util.concurrent.Executors

/**
 * Song play history and play counts, for the iPod smart playlists Music -> Playlists -> Recently Played and
 * Top 25 Most Played. While music plays, a 1 s timer gives the player state to [PlayCountRule]; each counted
 * play goes into [PlayHistoryStore] (file `play_history.json` in the app folder) on a worker thread.
 */
class PlayHistoryManager private constructor(context: Context) {

    enum class Kind { RECENTLY_PLAYED, MOST_PLAYED }

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "y1-play-history").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rule = PlayCountRule()
    private var store = PlayHistoryStore()
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            val apm = AudioPlayerManager.getInstance()
            val playing = apm.isPlaying()
            val counted = rule.sample(
                apm.currentlyPlayingFilePath,
                apm.getCurrentPosition().toLong(),
                apm.getDuration().toLong(),
                playing,
                SystemClock.elapsedRealtime(),
                apm.getCurrentSpeed()
            )
            if (counted != null && PlayCountRule.isMusicPath(counted)) record(counted, System.currentTimeMillis())
            if (playing) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                ticking = false
            }
        }
    }

    init {
        worker.execute {
            store = try {
                PlayHistoryStore.readFrom(file)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot read $file", e)
                PlayHistoryStore()
            }
        }
    }

    /** Playback started or resumed: sample the player each second until it stops. */
    fun onPlaybackStarted() {
        mainHandler.post {
            if (!ticking) {
                ticking = true
                mainHandler.postDelayed(tick, TICK_MS)
            }
        }
    }

    private fun record(path: String, timeMs: Long) {
        worker.execute {
            store.record(path, timeMs)
            save()
        }
    }

    private fun save() {
        try {
            store.writeTo(file)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot write $file", e)
        }
    }

    /**
     * The songs of [kind] as files, on the main thread. Songs whose file is gone are removed from the history,
     * but only when their storage is there (with the SD card out or shared over USB, nothing is removed).
     * Removed songs make room, so the lists stay full.
     */
    fun loadSongs(kind: Kind, onLoaded: (List<File>) -> Unit) {
        worker.execute {
            val roots = HashMap<String, Boolean>()
            val (entries, removed) = store.listWithoutGone(
                {
                    when (kind) {
                        Kind.RECENTLY_PLAYED -> store.recentlyPlayed()
                        Kind.MOST_PLAYED -> store.mostPlayed()
                    }
                },
                { path -> !File(path).exists() && storageReady(path, roots) }
            )
            if (removed) save()
            // A song on storage that is not there now stays in the history, but not in the list
            val files = entries.map { File(it.path) }.filter { it.exists() }
            mainHandler.post { onLoaded(files) }
        }
    }

    /**
     * True if the storage root of [path] is mounted: it has a Music folder (an SD card that is out or shared
     * over USB leaves an empty mount point). No list() call: it can hang on a bad mount.
     */
    private fun storageReady(path: String, cache: HashMap<String, Boolean>): Boolean {
        val root = StoragePaths.getAllRoots().firstOrNull { path.startsWith(it.absolutePath.trimEnd('/') + "/") }
            ?: return false
        return cache.getOrPut(root.absolutePath) { File(root, "Music").isDirectory }
    }

    companion object {
        private const val TAG = "PlayHistoryManager"
        const val FILE_NAME = "play_history.json"
        private const val TICK_MS = 1000L

        @Volatile
        private var instance: PlayHistoryManager? = null

        fun getInstance(context: Context): PlayHistoryManager =
            instance ?: synchronized(this) {
                instance ?: PlayHistoryManager(context).also { instance = it }
            }
    }
}
