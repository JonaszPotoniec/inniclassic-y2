package com.themoon.y1

import com.themoon.y1.managers.MediaLibraryManager
import java.io.File
import java.util.HashSet

internal fun MainActivity.startMediaLibraryScanInternal() {
    if (isCustomScanning) return
    isCustomScanning = true
    showLoadingPopup(t("Scanning Media Library..."))
    val mlm = MediaLibraryManager.getInstance()
    mlm.startMediaLibraryScan(this, blacklist, object : MediaLibraryManager.ScanProgressListener {
        override fun onProgress(scanned: Int, total: Int) {}
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
