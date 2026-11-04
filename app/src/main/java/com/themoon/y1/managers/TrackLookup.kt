package com.themoon.y1.managers

import com.themoon.y1.MainActivity
import com.themoon.y1.models.SongItem

/**
 * Finds a library item by its file path in O(1). The map is made again when a list changed: another
 * size, or another first or last item (a cache load or a scan puts new items into the lists).
 */
internal class LibraryPathIndex<T : Any>(private val pathOf: (T) -> String) {
    private val map = HashMap<String, T>()
    private var stamp: Array<Any?> = emptyArray()

    fun find(path: String, lists: List<List<T>>): T? {
        val now = stampOf(lists)
        if (!sameStamp(now)) {
            rebuild(lists)
            stamp = now
        }
        return map[path]
    }

    private fun stampOf(lists: List<List<T>>): Array<Any?> {
        val s = arrayOfNulls<Any?>(lists.size * 3)
        for ((i, list) in lists.withIndex()) {
            synchronized(list) {
                s[i * 3] = list.size
                s[i * 3 + 1] = list.firstOrNull()
                s[i * 3 + 2] = list.lastOrNull()
            }
        }
        return s
    }

    private fun sameStamp(now: Array<Any?>): Boolean {
        if (now.size != stamp.size) return false
        for (i in now.indices) {
            // Sizes by value, items by identity
            if (i % 3 == 0) {
                if (now[i] != stamp[i]) return false
            } else if (now[i] !== stamp[i]) {
                return false
            }
        }
        return true
    }

    private fun rebuild(lists: List<List<T>>) {
        map.clear()
        for (list in lists) {
            synchronized(list) {
                // The first item of a path wins, as with a search from the start of the lists
                for (item in list) {
                    val key = pathOf(item)
                    if (!map.containsKey(key)) map[key] = item
                }
            }
        }
    }
}

/** The songs and audiobooks of the library (Scan Media Library) by file path. Main thread. */
object LibrarySongs {
    private val index = LibraryPathIndex<SongItem> { it.file.absolutePath }

    fun byPath(path: String): SongItem? =
        index.find(path, listOf(MainActivity.customLibrary, MainActivity.audiobookLibrary))

    private val musicIndex = LibraryPathIndex<SongItem> { it.file.absolutePath }

    /** Only the songs (not the audiobooks), as the Bluetooth track data searched them before. */
    fun songByPath(path: String): SongItem? = musicIndex.find(path, listOf(MainActivity.customLibrary))
}

/**
 * Gives each track change a ticket, so that a cover which a worker thread reads late shows only
 * for the track that is still loaded (a fast run of skips does not show an old cover).
 */
internal class TrackArtRequests {
    class Ticket(val path: String)

    @Volatile
    private var current: Ticket? = null

    /** A new track: the tickets of older tracks are stale. */
    fun start(path: String): Ticket = Ticket(path).also { current = it }

    /** A track change with no cover to read: all tickets are stale. */
    fun cancel() {
        current = null
    }

    /** True while [ticket] is the newest one and its cover did not show yet. Any thread. */
    fun isCurrent(ticket: Ticket): Boolean = current === ticket

    /** True while the cover of the newest track is still loading. */
    val isLoading: Boolean get() = current != null

    /** The cover of [ticket] is ready: true if it must show. Then no cover is loading. */
    fun finish(ticket: Ticket): Boolean {
        if (current !== ticket) return false
        current = null
        return true
    }
}

/**
 * The covers of the tracks around the current one in the play order, by file path, so that a skip shows
 * the cover with the title. At most [maxEntries]; the oldest goes first. Any thread.
 */
internal class NeighbourCovers<V : Any>(private val maxEntries: Int) {
    private val map = LinkedHashMap<String, V>()

    @Synchronized
    fun get(path: String): V? = map[path]

    @Synchronized
    fun contains(path: String): Boolean = map.containsKey(path)

    @Synchronized
    fun put(path: String, value: V) {
        map.remove(path)
        map[path] = value
        while (map.size > maxEntries) {
            val oldest = map.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** Removes the covers of the tracks that are not in [paths]. */
    @Synchronized
    fun keepOnly(paths: Collection<String>) {
        val keep = HashSet(paths)
        map.keys.retainAll(keep)
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        /** The tracks after and before the current one whose covers the cache keeps. */
        const val RADIUS = 2

        /** The window (current track and 2 × [RADIUS]) and one more, for the track that a skip leaves. */
        const val SIZE = 2 * RADIUS + 2

        /** The prefetch starts this long after the last track change, so it does not slow down the new track. */
        const val PREFETCH_DELAY_MS = 1000L

        /**
         * The queue positions of the track at [index] and of the [radius] tracks after and before it, nearest
         * first (next, previous, ...). The queue wraps at both ends, as Next and Previous do.
         */
        fun window(index: Int, size: Int, radius: Int): List<Int> {
            if (size <= 0 || index !in 0 until size) return emptyList()
            val out = ArrayList<Int>()
            out.add(index)
            for (d in 1..radius) {
                for (i in intArrayOf(index + d, index - d)) {
                    val wrapped = ((i % size) + size) % size
                    if (wrapped !in out) out.add(wrapped)
                }
            }
            return out
        }
    }
}
