package com.themoon.y1.managers

import android.content.Context
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.models.SongItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.Writer
import java.util.Collections

/**
 * Manages media library scanning, persistence in .y1_library_cache.json,
 * tag extraction, category indexing, and unique album cache rebuilding.
 */
class MediaLibraryManager private constructor() {

    private val tag = "MediaLibraryManager"

    val musicLibrary = Collections.synchronizedList(ArrayList<SongItem>())
    val audiobookLibrary = Collections.synchronizedList(ArrayList<SongItem>())
    val trackNumberMap = Collections.synchronizedMap(HashMap<String, Int>())
    val uniqueAlbumList = Collections.synchronizedList(ArrayList<SongItem>())

    @Volatile
    var isScanning: Boolean = false
        private set

    interface ScanProgressListener {
        fun onProgress(scanned: Int, total: Int)
        fun onComplete(musicCount: Int, booksCount: Int)
    }

    fun saveLibraryCache() {
        val cacheFile = StoragePaths.primaryFile(".y1_library_cache.json")
        val tempFile = StoragePaths.primaryFile(".y1_library_cache.json.tmp")
        var bw: BufferedWriter? = null
        try {
            val parent = cacheFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            bw = BufferedWriter(FileWriter(tempFile), 32768)
            bw.write("{\"music\":[")
            writeSongListJson(bw, musicLibrary)
            bw.write("],\"books\":[")
            writeSongListJson(bw, audiobookLibrary)
            bw.write("]}")
            bw.flush()
            bw.close()
            bw = null

            if (tempFile.exists() && tempFile.length() > 0) {
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                tempFile.renameTo(cacheFile)
            }
        } catch (t: Throwable) {
            Log.e(tag, "Failed to save library cache: ${t.message}")
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (ignored: Throwable) {}
        } finally {
            try {
                bw?.close()
            } catch (ignored: Throwable) {}
        }
    }

    private fun writeSongListJson(w: Writer, list: List<SongItem>) {
        var first = true
        synchronized(list) {
            for (song in list) {
                if (song.file == null) continue
                if (!first) w.write(",")
                first = false
                w.write("{\"path\":")
                w.write(JSONObject.quote(song.file.absolutePath))
                w.write(",\"title\":")
                w.write(JSONObject.quote(song.title ?: ""))
                w.write(",\"artist\":")
                w.write(JSONObject.quote(song.artist ?: ""))
                w.write(",\"album\":")
                w.write(JSONObject.quote(song.album ?: ""))
                w.write(",\"year\":")
                w.write(JSONObject.quote(song.year ?: ""))
                w.write(",\"genre\":")
                w.write(JSONObject.quote(song.genre ?: ""))
                w.write(",\"albumArtist\":")
                w.write(JSONObject.quote(song.albumArtist ?: ""))
                w.write(",\"composer\":")
                w.write(JSONObject.quote(song.composer ?: ""))
                val trackNum = trackNumberMap[song.file.absolutePath] ?: 0
                w.write(",\"trackNum\":")
                w.write(trackNum.toString())
                w.write("}")
            }
        }
    }

    fun loadLibraryCache(): Boolean {
        var cacheFile = StoragePaths.primaryFile(".y1_library_cache.json")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val secFile = File(StoragePaths.SECONDARY_PATH, ".y1_library_cache.json")
            if (secFile.exists() && secFile.length() > 0L) {
                cacheFile = secFile
            } else {
                return false
            }
        }
        return try {
            val sb = StringBuilder()
            BufferedReader(FileReader(cacheFile), 32768).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
            }
            val root = JSONObject(sb.toString())
            jsonToSongList(root.optJSONArray("music"), musicLibrary, isAudiobook = false)
            jsonToSongList(root.optJSONArray("books"), audiobookLibrary, isAudiobook = true)
            rebuildUniqueAlbumList()
            true
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load library cache: ${t.message}")
            false
        }
    }

    private fun jsonToSongList(arr: JSONArray?, target: MutableList<SongItem>, isAudiobook: Boolean) {
        if (arr == null) return
        target.clear()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val f = File(o.getString("path"))
                if (!f.exists()) continue

                if (isAudiobook) {
                    if (!StoragePaths.isUnderAudiobooks(f)) continue
                } else {
                    if (!StoragePaths.isUnderMusic(f)) continue
                }

                val song = SongItem(
                    f,
                    o.optString("title", f.name),
                    o.optString("artist", "Unknown Artist"),
                    o.optString("album", "Unknown Album"),
                    o.optString("year", "Unknown Year"),
                    o.optString("genre", "Unknown Genre"),
                    o.optString("albumArtist", ""),
                    o.optString("composer", "")
                )
                target.add(song)
                trackNumberMap[f.absolutePath] = o.optInt("trackNum", 0)
            } catch (ignored: Throwable) {}
        }
    }

    fun rebuildUniqueAlbumList() {
        val combinedLibrary = ArrayList<SongItem>()
        synchronized(musicLibrary) {
            combinedLibrary.addAll(musicLibrary)
        }
        synchronized(audiobookLibrary) {
            combinedLibrary.addAll(audiobookLibrary)
        }

        val albumMap = LinkedHashMap<String, ArrayList<SongItem>>()
        for (song in combinedLibrary) {
            if (song.file == null) continue
            val album = if (!song.album.isNullOrBlank()) song.album.trim() else "Unknown Album"
            val key = album.lowercase(java.util.Locale.US)
            val list = albumMap.getOrPut(key) { ArrayList() }
            list.add(song)
        }

        val result = ArrayList<SongItem>()
        for (tracks in albumMap.values) {
            if (tracks.isEmpty()) continue
            val artistCounts = HashMap<String, Int>()
            var bestArtist: String? = null
            var maxCount = -1
            for (t in tracks) {
                val a = if (!t.albumArtist.isNullOrBlank()) t.albumArtist.trim()
                else if (!t.artist.isNullOrBlank()) t.artist.trim()
                else "Unknown Artist"
                val cnt = (artistCounts[a] ?: 0) + 1
                artistCounts[a] = cnt
                if (cnt > maxCount) {
                    maxCount = cnt
                    bestArtist = a
                }
            }
            val resolvedArtist = bestArtist ?: "Unknown Artist"
            val first = tracks[0]
            val rep = SongItem(
                first.file, first.title, resolvedArtist, first.album, first.year, first.genre, resolvedArtist, first.composer
            )
            result.add(rep)
        }

        result.sortWith { s1, s2 ->
            val byArtist = (s1.albumArtist ?: "").compareTo(s2.albumArtist ?: "", ignoreCase = true)
            if (byArtist != 0) byArtist
            else (s1.album ?: "").compareTo(s2.album ?: "", ignoreCase = true)
        }

        synchronized(uniqueAlbumList) {
            uniqueAlbumList.clear()
            uniqueAlbumList.addAll(result)
        }
    }

    companion object {
        @Volatile
        private var instance: MediaLibraryManager? = null

        @JvmStatic
        fun getInstance(): MediaLibraryManager {
            return instance ?: synchronized(this) {
                instance ?: MediaLibraryManager().also { instance = it }
            }
        }
    }
}
