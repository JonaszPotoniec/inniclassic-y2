package com.themoon.y1

import com.themoon.y1.managers.MediaLibraryManager
import java.io.File
import java.util.HashSet

internal fun MainActivity.loadLibraryCacheInternal(): Boolean {
    val mlm = MediaLibraryManager.getInstance()
    if (mlm.loadLibraryCache()) {
        synchronized(MainActivity.customLibrary) {
            MainActivity.customLibrary.clear()
            MainActivity.customLibrary.addAll(mlm.musicLibrary)
        }
        synchronized(MainActivity.audiobookLibrary) {
            MainActivity.audiobookLibrary.clear()
            MainActivity.audiobookLibrary.addAll(mlm.audiobookLibrary)
        }
        synchronized(MainActivity.trackNumberMap) {
            MainActivity.trackNumberMap.clear()
            MainActivity.trackNumberMap.putAll(mlm.trackNumberMap)
        }
        rebuildUniqueAlbumListInternal()
        scanVideosInternal()
        refreshNowPlayingPreview()
        return true
    }
    return false
}

internal fun MainActivity.startMediaLibraryScanInternal() {
    if (isCustomScanning) return
    isCustomScanning = true
    pbLoadingProgress?.progress = 0
    showLoadingPopup(t("Preparing to scan...\nPlease wait."))
    val mlm = MediaLibraryManager.getInstance()
    mlm.startMediaLibraryScan(this, blacklist, object : MediaLibraryManager.ScanProgressListener {
        override fun onProgress(scanned: Int, total: Int) {
            if (total > 0) {
                val progress = ((scanned.toFloat() / total) * 100).toInt()
                runOnUiThread {
                    pbLoadingProgress?.progress = progress
                    val template = t("Scanning Media: %d%%\n(%d / %d)\nDo not turn off the screen.")
                    tvLoadingProgress?.text = String.format(java.util.Locale.US, template, progress, scanned, total)
                }
            }
        }
        override fun onComplete(musicCount: Int, booksCount: Int) {
            runOnUiThread {
                MainActivity.customLibrary.clear()
                MainActivity.customLibrary.addAll(mlm.musicLibrary)
                MainActivity.audiobookLibrary.clear()
                MainActivity.audiobookLibrary.addAll(mlm.audiobookLibrary)
                MainActivity.trackNumberMap.clear()
                MainActivity.trackNumberMap.putAll(mlm.trackNumberMap)
                rebuildUniqueAlbumListInternal()
                scanVideosInternal()
                isCustomScanning = false
                dismissLoadingOverlay()
                refreshNowPlayingPreview()
                if (isBrowserControllerInitialized()) {
                    browserController.buildFileBrowserUI()
                }
            }
        }
    })
}

internal fun MainActivity.rebuildUniqueAlbumListInternal() {
    uniqueAlbumList.clear()
    val seenAlbums = HashSet<String>()
    for (song in MainActivity.customLibrary) {
        val album = song.album
        if (!album.isNullOrBlank() && seenAlbums.add(album)) {
            uniqueAlbumList.add(song)
        }
    }
}

internal fun MainActivity.scanVideosInternal() {
    videoFiles.clear()
    for (root in StoragePaths.getAllRoots()) {
        val moviesDir = File(root, "Movies")
        if (moviesDir.exists() && moviesDir.isDirectory) {
            moviesDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".mkv") || f.name.endsWith(".avi"))) {
                    videoFiles.add(f)
                }
            }
        }
    }
}
