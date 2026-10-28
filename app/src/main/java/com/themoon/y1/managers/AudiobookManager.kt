package com.themoon.y1.managers

import android.content.Context
import android.content.SharedPreferences
import com.themoon.y1.AppPrefs
import com.themoon.y1.StoragePaths
import java.io.File
import java.util.ArrayList
import java.util.Collections

/**
 * Manages audiobook folder scanning, chapter playlists, and bookmark persistence.
 */
class AudiobookManager private constructor(context: Context) {

    /** Positions are in AppPrefs as book_pos_<path> (AudioPlayerManager writes them). */
    private val appPrefs: SharedPreferences = AppPrefs.get(context)
    private val audiobookRoot: File = StoragePaths.getAudiobooksDir()

    private val currentBookChapters: MutableList<File> = ArrayList()
    private var currentChapterIndex: Int = -1

    init {
        if (!audiobookRoot.exists()) {
            audiobookRoot.mkdirs()
        }
    }

    fun getRootFolder(): File = audiobookRoot

    /**
     * Returns saved bookmark position in milliseconds.
     */
    fun getSavedPosition(filePath: String?): Int {
        if (filePath.isNullOrEmpty()) return 0
        return appPrefs.getInt("book_pos_$filePath", 0)
    }

    /**
     * Configures audiobook chapter playlist and resumes playback at saved offset.
     */
    fun setupBookPlaylist(context: Context?, clickedFile: File, parentFolder: File) {
        currentBookChapters.clear()

        val files = parentFolder.listFiles()
        if (files != null) {
            for (f in files) {
                if (isAudioFile(f)) {
                    currentBookChapters.add(f)
                }
            }
            Collections.sort(currentBookChapters) { f1, f2 ->
                f1.name.compareTo(f2.name, ignoreCase = true)
            }
        }

        currentChapterIndex = currentBookChapters.indexOf(clickedFile)
        if (currentChapterIndex == -1) {
            currentChapterIndex = 0
        }

        val savedOffset = getSavedPosition(clickedFile.absolutePath)
        AudioPlayerManager.getInstance().playTrackListWithOffset(currentBookChapters, currentChapterIndex, savedOffset)
    }

    companion object {
        @Volatile
        private var instance: AudiobookManager? = null
        @Synchronized
        fun getInstance(context: Context): AudiobookManager {
            var inst = instance
            if (inst == null) {
                inst = AudiobookManager(context.applicationContext)
                instance = inst
            }
            return inst
        }

        fun isAudioFile(file: File?): Boolean {
            if (file == null || file.isDirectory) return false
            val name = file.name.lowercase(java.util.Locale.US)
            return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".m4b") ||
                    name.endsWith(".aac") || name.endsWith(".flac") || name.endsWith(".ogg") ||
                    name.endsWith(".wav") || name.endsWith(".ape") || name.endsWith(".wma") ||
                    name.endsWith(".opus") || name.endsWith(".alac")
        }
    }
}
