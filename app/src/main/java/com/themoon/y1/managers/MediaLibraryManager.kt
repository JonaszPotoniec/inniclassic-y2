package com.themoon.y1.managers

import android.content.Context
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.models.AlbumKey
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

    fun isAudioFile(file: File): Boolean {
        val name = file.name.lowercase(java.util.Locale.US)
        return name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") ||
                name.endsWith(".alac") || name.endsWith(".ogg") || name.endsWith(".opus") ||
                name.endsWith(".wav") || name.endsWith(".aac") || name.endsWith(".m4b")
    }

    fun findFolderCover(folder: File?): File? {
        if (folder == null || !folder.exists()) return null
        val files = folder.listFiles() ?: return null
        for (f in files) {
            val name = f.name.lowercase(java.util.Locale.US)
            if ((name.startsWith("cover.") || name.startsWith("folder.")) &&
                (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
            ) {
                return f
            }
        }
        return null
    }

    private fun countAudioFiles(folder: File): Int {
        if (!folder.exists()) return 0
        var count = 0
        val files = folder.listFiles() ?: return 0
        for (f in files) {
            if (f.isDirectory) {
                count += countAudioFiles(f)
            } else if (isAudioFile(f)) {
                if (f.length() >= 1024L) {
                    count++
                }
            }
        }
        return count
    }

    fun startMediaLibraryScan(
        context: Context,
        blacklist: Set<String> = emptySet(),
        listener: ScanProgressListener? = null
    ) {
        if (isScanning) return
        isScanning = true

        Thread({
            try {
                // 1. Remove missing files
                synchronized(musicLibrary) {
                    val it = musicLibrary.iterator()
                    while (it.hasNext()) {
                        val item = it.next()
                        if (!item.file.exists() || !StoragePaths.isUnderMusic(item.file)) {
                            trackNumberMap.remove(item.file.absolutePath)
                            it.remove()
                        }
                    }
                }

                synchronized(audiobookLibrary) {
                    val it = audiobookLibrary.iterator()
                    while (it.hasNext()) {
                        val item = it.next()
                        if (!item.file.exists() || !StoragePaths.isUnderAudiobooks(item.file)) {
                            trackNumberMap.remove(item.file.absolutePath)
                            it.remove()
                        }
                    }
                }

                deduplicateLibrary(musicLibrary)
                deduplicateLibrary(audiobookLibrary)

                val existingMusic = HashSet<String>()
                synchronized(musicLibrary) {
                    for (item in musicLibrary) existingMusic.add(normalizePathKey(item.file))
                }

                val existingBooks = HashSet<String>()
                synchronized(audiobookLibrary) {
                    for (item in audiobookLibrary) existingBooks.add(normalizePathKey(item.file))
                }

                StoragePaths.invalidate()

                var total = 0
                for (musicDir in StoragePaths.getMusicDirs()) total += countAudioFiles(musicDir)
                val bookDirs = if (com.themoon.y1.FeatureVisibility.isHidden(context, com.themoon.y1.FeatureVisibility.AUDIOBOOKS)) emptyList() else StoragePaths.getAudiobooksDirs()
                for (bookDir in bookDirs) total += countAudioFiles(bookDir)

                var scanned = 0
                for (musicDir in StoragePaths.getMusicDirs()) {
                    scanned = scanDirectory(musicDir, musicLibrary, existingMusic, blacklist, scanned, total, isAudiobook = false, listener)
                }
                for (bookDir in bookDirs) {
                    scanned = scanDirectory(bookDir, audiobookLibrary, existingBooks, blacklist, scanned, total, isAudiobook = true, listener)
                }

                deduplicateLibrary(musicLibrary)
                deduplicateLibrary(audiobookLibrary)
                saveLibraryCache()
                rebuildUniqueAlbumList()
                AlbumCoverManager.getInstance().pregenerateThumbnails(uniqueAlbumList)

                listener?.onComplete(musicLibrary.size, audiobookLibrary.size)
            } catch (t: Throwable) {
                Log.e(tag, "Scan error: ${t.message}", t)
            } finally {
                isScanning = false
            }
        }, "y1-media-scanner").start()
    }

    private fun normalizePathKey(file: File): String {
        return try {
            file.canonicalPath.lowercase(java.util.Locale.US)
        } catch (_: Exception) {
            file.absolutePath.lowercase(java.util.Locale.US)
        }
    }

    fun deduplicateLibrary(list: MutableList<SongItem>) {
        synchronized(list) {
            val seen = HashSet<String>()
            val it = list.iterator()
            while (it.hasNext()) {
                val item = it.next()
                val key = normalizePathKey(item.file)
                if (!seen.add(key)) {
                    trackNumberMap.remove(item.file.absolutePath)
                    it.remove()
                }
            }
        }
    }

    private fun scanDirectory(
        folder: File,
        targetList: MutableList<SongItem>,
        existingPaths: MutableSet<String>,
        blacklist: Set<String>,
        startScanned: Int,
        total: Int,
        isAudiobook: Boolean,
        listener: ScanProgressListener?
    ): Int {
        if (!folder.exists()) return startScanned
        var scanned = startScanned
        val files = folder.listFiles() ?: return scanned

        for (f in files) {
            if (f.isDirectory) {
                scanned = scanDirectory(f, targetList, existingPaths, blacklist, scanned, total, isAudiobook, listener)
            } else if (isAudioFile(f)) {
                if (f.length() < 1024L) {
                    android.util.Log.w("MediaLibraryManager", "Skipping empty/corrupted file (< 1024 bytes): ${f.absolutePath}")
                    continue
                }
                if (blacklist.contains(f.absolutePath)) continue

                val fileKey = normalizePathKey(f)
                if (existingPaths.contains(fileKey) || existingPaths.contains(f.absolutePath)) {
                    scanned++
                    if (total > 0 && scanned % 10 == 0) {
                        listener?.onProgress(scanned, total)
                    }
                    continue
                }

                var title = f.name
                val dot = title.lastIndexOf(".")
                if (dot > 0) title = title.substring(0, dot)

                var artist = if (isAudiobook) "Unknown Author" else "Unknown Artist"
                var album = if (isAudiobook) "Unknown Book" else "Unknown Album"
                var year = "Unknown Year"
                var genre = "Unknown Genre"
                var albumArtist: String? = null
                var composer: String? = null
                var compilation = false
                var trackNum = 0

                try {
                    val lower = f.name.lowercase(java.util.Locale.US)
                    var t: String? = null
                    var a: String? = null
                    var al: String? = null
                    var y: String? = null
                    var g: String? = null
                    var aa: String? = null
                    var cp: String? = null
                    var cpl: String? = null
                    var trackStr: String? = null

                    val apm = AudioPlayerManager.getInstance()
                    if (lower.endsWith(".opus")) {
                        val tags = apm.extractOpusMetadata(f, false)
                        if (tags[0] != null) t = tags[0] as? String
                        if (tags[1] != null) a = tags[1] as? String
                        if (tags[2] != null) al = tags[2] as? String
                        if (tags[3] != null) y = tags[3] as? String
                        if (tags[4] != null) g = tags[4] as? String
                        if (tags.size > 6 && tags[6] != null) trackStr = tags[6] as? String
                        if (tags.size > 8 && tags[8] != null) aa = tags[8] as? String
                        if (tags.size > 9 && tags[9] != null) cp = tags[9] as? String
                        if (tags.size > 10 && tags[10] != null) cpl = tags[10] as? String
                    } else if (lower.endsWith(".flac")) {
                        val tags = apm.extractFlacMetadata(f, false)
                        if (tags[0] != null) t = tags[0] as? String
                        if (tags[1] != null) a = tags[1] as? String
                        if (tags[2] != null) al = tags[2] as? String
                        if (tags[3] != null) y = tags[3] as? String
                        if (tags[4] != null) g = tags[4] as? String
                        if (tags.size > 6 && tags[6] != null) trackStr = tags[6] as? String
                        if (tags.size > 8 && tags[8] != null) aa = tags[8] as? String
                        if (tags.size > 9 && tags[9] != null) cp = tags[9] as? String
                        if (tags.size > 10 && tags[10] != null) cpl = tags[10] as? String
                    } else if (lower.endsWith(".m4a") || lower.endsWith(".m4b")) {
                        val tags = apm.extractAlacMetadata(f, false)
                        if (tags[0] != null) t = tags[0] as? String
                        if (tags[1] != null) a = tags[1] as? String
                        if (tags[2] != null) al = tags[2] as? String
                        if (tags[3] != null) y = tags[3] as? String
                        if (tags[4] != null) g = tags[4] as? String
                        if (tags.size > 6 && tags[6] != null) trackStr = tags[6] as? String
                        if (tags.size > 8 && tags[8] != null) aa = tags[8] as? String
                        if (tags.size > 9 && tags[9] != null) cp = tags[9] as? String
                        if (tags.size > 10 && tags[10] != null) cpl = tags[10] as? String
                    } else if (lower.endsWith(".ogg")) {
                        val tags = apm.extractOggVorbisMetadata(f, false)
                        if (tags[0] != null) t = tags[0] as? String
                        if (tags[1] != null) a = tags[1] as? String
                        if (tags[2] != null) al = tags[2] as? String
                        if (tags[3] != null) y = tags[3] as? String
                        if (tags[4] != null) g = tags[4] as? String
                        if (tags.size > 6 && tags[6] != null) trackStr = tags[6] as? String
                        if (tags.size > 8 && tags[8] != null) aa = tags[8] as? String
                        if (tags.size > 9 && tags[9] != null) cp = tags[9] as? String
                        if (tags.size > 10 && tags[10] != null) cpl = tags[10] as? String
                    } else {
                        var mmr: android.media.MediaMetadataRetriever? = null
                        var fis: java.io.FileInputStream? = null
                        try {
                            mmr = android.media.MediaMetadataRetriever()
                            fis = java.io.FileInputStream(f)
                            mmr.setDataSource(fis.fd)
                            t = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            a = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            if (a.isNullOrEmpty()) a = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                            al = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            trackStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                            y = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                            g = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                            aa = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            cp = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                            cpl = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPILATION)
                        } catch (_: Throwable) {
                        } finally {
                            try { fis?.close() } catch (_: Throwable) {}
                            try { mmr?.release() } catch (_: Throwable) {}
                        }
                    }

                    if (!t.isNullOrBlank()) title = t
                    if (!a.isNullOrBlank()) {
                        artist = a
                    } else {
                        try {
                            val parentName = f.parentFile?.parentFile?.name
                            val folderName = f.parentFile?.name
                            if (!parentName.isNullOrEmpty() && !parentName.equals("Music", ignoreCase = true) && !parentName.equals("Audiobooks", ignoreCase = true) && !StoragePaths.isStorageVolumeName(parentName)) {
                                artist = parentName
                            } else if (!folderName.isNullOrEmpty() && !folderName.equals("Music", ignoreCase = true) && !folderName.equals("Audiobooks", ignoreCase = true) && !StoragePaths.isStorageVolumeName(folderName)) {
                                artist = folderName
                            }
                        } catch (_: Exception) {}
                    }

                    if (!al.isNullOrBlank()) {
                        album = al
                    } else {
                        val folderName = f.parentFile?.name
                        if (!folderName.isNullOrEmpty() && !folderName.equals("Music", ignoreCase = true) && !folderName.equals("Audiobooks", ignoreCase = true) && !StoragePaths.isStorageVolumeName(folderName)) {
                            album = folderName
                        }
                    }

                    if (!y.isNullOrBlank()) year = y
                    if (!g.isNullOrBlank()) genre = g
                    albumArtist = if (!aa.isNullOrBlank()) aa else artist
                    if (!cp.isNullOrBlank()) composer = cp
                    compilation = com.themoon.y1.models.Compilations.parseFlag(cpl)

                    if (!trackStr.isNullOrEmpty()) {
                        trackNum = try {
                            if (trackStr.contains("/")) trackStr.split("/")[0].trim().toInt()
                            else trackStr.trim().toInt()
                        } catch (_: Exception) { 0 }
                    }
                } catch (_: Throwable) {}

                val song = SongItem(f, title, artist, album, year, genre, albumArtist, composer)
                song.compilationTag = compilation
                targetList.add(song)
                existingPaths.add(fileKey)
                existingPaths.add(f.absolutePath)
                trackNumberMap[f.absolutePath] = trackNum

                scanned++
                if (scanned % 500 == 0) {
                    saveLibraryCache()
                }
                if (total > 0) {
                    listener?.onProgress(scanned, total)
                }
            }
        }
        return scanned
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
                // Only for compilation tracks: a missing field reads as false (also in caches from before).
                if (song.compilationTag) w.write(",\"compilation\":true")
                w.write("}")
            }
        }
    }

    /** Song lists read from .y1_library_cache.json, not yet applied to the shared library lists. */
    class LibraryCache(
        val music: List<SongItem>,
        val books: List<SongItem>,
        val trackNumbers: Map<String, Int>
    )

    /**
     * Reads the cache file and checks that each file still exists. This does file I/O for each
     * track, so call it on a worker thread. It does not change the shared library lists.
     */
    fun readLibraryCache(): LibraryCache? {
        var cacheFile = StoragePaths.primaryFile(".y1_library_cache.json")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val secFile = File(StoragePaths.SECONDARY_PATH, ".y1_library_cache.json")
            if (secFile.exists() && secFile.length() > 0L) {
                cacheFile = secFile
            } else {
                return null
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
            parseLibraryCache(JSONObject(sb.toString()))
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load library cache: ${t.message}")
            null
        }
    }

    internal fun parseLibraryCache(
        root: JSONObject,
        isUsable: (File, Boolean) -> Boolean = ::isUsableCachedFile
    ): LibraryCache {
        val trackNumbers = HashMap<String, Int>()
        val music = jsonToSongList(root.optJSONArray("music"), trackNumbers, isAudiobook = false, isUsable = isUsable)
        val books = jsonToSongList(root.optJSONArray("books"), trackNumbers, isAudiobook = true, isUsable = isUsable)
        return LibraryCache(music, books, trackNumbers)
    }

    /** Puts [cache] into the shared library lists. Call it on the main thread when no scan is running. */
    fun applyLibraryCache(cache: LibraryCache) {
        synchronized(musicLibrary) {
            musicLibrary.clear()
            musicLibrary.addAll(cache.music)
        }
        synchronized(audiobookLibrary) {
            audiobookLibrary.clear()
            audiobookLibrary.addAll(cache.books)
        }
        trackNumberMap.putAll(cache.trackNumbers)
        rebuildUniqueAlbumList()
    }

    private fun isUsableCachedFile(f: File, isAudiobook: Boolean): Boolean {
        if (!f.exists() || f.length() < 1024L) return false
        return if (isAudiobook) StoragePaths.isUnderAudiobooks(f) else StoragePaths.isUnderMusic(f)
    }

    private fun jsonToSongList(
        arr: JSONArray?,
        trackNumbers: MutableMap<String, Int>,
        isAudiobook: Boolean,
        isUsable: (File, Boolean) -> Boolean
    ): List<SongItem> {
        val target = ArrayList<SongItem>()
        if (arr == null) return target
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val f = File(o.getString("path"))
                if (!isUsable(f, isAudiobook)) continue

                val key = normalizePathKey(f)
                if (!seen.add(key)) continue

                val song = SongItem(
                    f,
                    o.optString("title", f.name),
                    o.optString("artist", if (isAudiobook) "Unknown Author" else "Unknown Artist"),
                    o.optString("album", if (isAudiobook) "Unknown Book" else "Unknown Album"),
                    o.optString("year", "Unknown Year"),
                    o.optString("genre", "Unknown Genre"),
                    o.optString("albumArtist", ""),
                    o.optString("composer", "")
                )
                song.compilationTag = o.optBoolean("compilation", false)
                target.add(song)
                trackNumbers[f.absolutePath] = o.optInt("trackNum", 0)
            } catch (ignored: Throwable) {}
        }
        return target
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
            val key = AlbumKey.of(song)
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
        fun getInstance(): MediaLibraryManager {
            return instance ?: synchronized(this) {
                instance ?: MediaLibraryManager().also { instance = it }
            }
        }
    }
}
