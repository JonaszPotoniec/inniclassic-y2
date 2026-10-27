package com.themoon.y1.managers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The play count and the last play time of each song, keyed by file path. It gives the iPod smart playlists
 * Recently Played ([recentlyPlayed]) and Top 25 Most Played ([mostPlayed]).
 *
 * Not thread-safe: [PlayHistoryManager] uses it on one worker thread. It is a JSON file in the app folder,
 * not a preference file, so the settings backup does not include it.
 */
class PlayHistoryStore(private val maxSongs: Int = MAX_SONGS) {

    class Entry(val path: String, var count: Int, var lastPlayedMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size

    fun get(path: String): Entry? = entries[path]

    /** Adds one play of [path] at [timeMs]. */
    fun record(path: String, timeMs: Long) {
        val entry = entries.getOrPut(path) { Entry(path, 0, 0L) }
        entry.count++
        entry.lastPlayedMs = maxOf(entry.lastPlayedMs, timeMs)
        if (entries.size > maxSongs) {
            entries.values.minByOrNull { it.lastPlayedMs }?.let { entries.remove(it.path) }
        }
    }

    fun remove(path: String): Boolean = entries.remove(path) != null

    /** The last played songs, newest first. */
    fun recentlyPlayed(limit: Int = RECENT_LIMIT): List<Entry> =
        entries.values.sortedWith(compareByDescending<Entry> { it.lastPlayedMs }.thenBy { it.path }).take(limit)

    /** The most played songs, most plays first; with equal counts, the last played first. */
    fun mostPlayed(limit: Int = TOP_LIMIT): List<Entry> =
        entries.values.sortedWith(
            compareByDescending<Entry> { it.count }.thenByDescending { it.lastPlayedMs }.thenBy { it.path }
        ).take(limit)

    /**
     * [list] ([recentlyPlayed] or [mostPlayed]) with no song whose file is gone. Removes those songs from the
     * store (a moved file has a new path and starts again at 0). Returns the list and true if it removed songs.
     */
    fun listWithoutGone(list: () -> List<Entry>, isGone: (String) -> Boolean): Pair<List<Entry>, Boolean> {
        var removed = false
        while (true) {
            val result = list()
            val gone = result.filter { isGone(it.path) }
            if (gone.isEmpty()) return result to removed
            gone.forEach { entries.remove(it.path) }
            removed = true
        }
    }

    fun toJson(): String {
        val songs = JSONArray()
        for (e in entries.values) {
            songs.put(JSONObject().put("path", e.path).put("count", e.count).put("last", e.lastPlayedMs))
        }
        return JSONObject().put("version", VERSION).put("songs", songs).toString()
    }

    /** Writes the store to [file] through a temporary file, so a crash cannot leave half a file. */
    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Cannot write $file")
        }
    }

    companion object {
        const val VERSION = 1
        const val RECENT_LIMIT = 100
        const val TOP_LIMIT = 25
        const val MAX_SONGS = 20000

        /** Reads the JSON of [toJson]. Bad entries are skipped; text that is not JSON gives an empty store. */
        fun fromJson(text: String, maxSongs: Int = MAX_SONGS): PlayHistoryStore {
            val store = PlayHistoryStore(maxSongs)
            val songs = try {
                JSONObject(text).optJSONArray("songs")
            } catch (e: Exception) {
                null
            } ?: return store
            for (i in 0 until songs.length()) {
                val o = songs.optJSONObject(i) ?: continue
                val path = o.optString("path", "")
                val count = o.optInt("count", 0)
                if (path.isEmpty() || count <= 0) continue
                store.entries[path] = Entry(path, count, o.optLong("last", 0L))
            }
            return store
        }

        fun readFrom(file: File, maxSongs: Int = MAX_SONGS): PlayHistoryStore =
            if (file.isFile) fromJson(file.readText(Charsets.UTF_8), maxSongs) else PlayHistoryStore(maxSongs)
    }
}
