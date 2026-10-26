package com.themoon.y1

import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.models.TvSeason
import com.themoon.y1.models.TvSeries
import com.themoon.y1.models.VideoItem
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import java.io.File
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern

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
                if (isBrowserControllerInitialized() && NavigationManager.getInstance().currentState is ScreenState.Browser) {
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
    var anyMigrated = false
    for (root in StoragePaths.getAllRoots()) {
        val v1 = File(root, "Videos")
        val v2 = File(root, "Video")
        if (v1.exists() || v2.exists()) {
            if (com.themoon.y1.migrations.Migration003MigrateVideoDirectories.migrateVolume(root)) {
                anyMigrated = true
            }
        }
    }
    if (anyMigrated) {
        com.themoon.y1.migrations.Migration003MigrateVideoDirectories.updateSyncLedgerPaths()
    }

    videoFiles.clear()
    movieVideos.clear()
    tvSeriesList.clear()

    val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp", "ts", "wmv")
    val tvRegex1 = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})\\s*(?:e|ep|episode\\s*)(\\d{1,3})")
    val tvRegex2 = Pattern.compile("(?i)(\\d{1,2})x(\\d{2,3})")
    val seasonDirRegex = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d+)")
    val leadingNumRegex = Pattern.compile("^(\\d{1,3})\\s*[-._]?\\s*(.*)")

    fun findCoverForFile(file: File): File? {
        val parent = file.parentFile ?: return null
        val baseName = file.nameWithoutExtension
        val c1 = File(parent, "$baseName.jpg")
        if (c1.exists()) return c1
        val c2 = File(parent, "$baseName.png")
        if (c2.exists()) return c2
        val c3 = File(parent, "$baseName.jpeg")
        if (c3.exists()) return c3
        return null
    }

    fun findPosterForDir(dir: File): File? {
        val f1 = File(dir, "folder.jpg")
        if (f1.exists()) return f1
        val f2 = File(dir, "poster.jpg")
        if (f2.exists()) return f2
        val f3 = File(dir, "cover.jpg")
        if (f3.exists()) return f3
        val f4 = File(dir, "folder.png")
        if (f4.exists()) return f4
        val f5 = File(dir, "poster.png")
        if (f5.exists()) return f5
        return null
    }

    fun collectVideoFiles(dir: File, result: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collectVideoFiles(f, result)
            } else if (f.isFile) {
                val ext = f.extension.lowercase(Locale.US)
                if (videoExtensions.contains(ext)) {
                    result.add(f)
                }
            }
        }
    }

    // 1. Scan Movies
    val foundMovies = ArrayList<VideoItem>()
    for (moviesDir in StoragePaths.getMoviesDirs()) {
        val files = ArrayList<File>()
        collectVideoFiles(moviesDir, files)
        for (f in files) {
            val title = f.nameWithoutExtension
            val cover = findCoverForFile(f) ?: f.parentFile?.let { findPosterForDir(it) }
            foundMovies.add(VideoItem(file = f, title = title, isTvEpisode = false, coverFile = cover))
            videoFiles.add(f)
        }
    }
    foundMovies.sortBy { it.title.lowercase(Locale.US) }
    movieVideos.addAll(foundMovies)

    // 2. Scan TV Shows
    val seriesMap = LinkedHashMap<String, MutableMap<Int, MutableList<VideoItem>>>()
    val seriesPosterMap = HashMap<String, File?>()

    for (tvDir in StoragePaths.getTvShowsDirs()) {
        val files = ArrayList<File>()
        collectVideoFiles(tvDir, files)
        for (f in files) {
            videoFiles.add(f)
            val parent = f.parentFile
            val grandparent = parent?.parentFile

            var seriesName = "Unknown Series"
            var seriesDir: File? = null

            val pName = parent?.name ?: ""
            val gpName = grandparent?.name ?: ""
            val isParentSeason = seasonDirRegex.matcher(pName).find()

            if (gpName.equals("Jellyfin", ignoreCase = true)) {
                if (isParentSeason) {
                    seriesName = grandparent?.parentFile?.name ?: pName
                    seriesDir = grandparent?.parentFile
                } else {
                    seriesName = pName
                    seriesDir = parent
                }
            } else if (pName.equals("Jellyfin", ignoreCase = true)) {
                seriesName = f.nameWithoutExtension
                seriesDir = parent
            } else if (isParentSeason) {
                seriesName = gpName
                seriesDir = grandparent
            } else if (parent != null && !parent.absolutePath.equals(tvDir.absolutePath, ignoreCase = true)) {
                seriesName = pName
                seriesDir = parent
            } else {
                seriesName = "TV Shows"
                seriesDir = tvDir
            }

            if (seriesDir != null && !seriesPosterMap.containsKey(seriesName)) {
                seriesPosterMap[seriesName] = findPosterForDir(seriesDir)
            }

            var seasonNum = 1
            var epNum = 0
            var cleanTitle = f.nameWithoutExtension

            val m1 = tvRegex1.matcher(f.name)
            val m2 = tvRegex2.matcher(f.name)
            if (m1.find()) {
                seasonNum = m1.group(1)?.toIntOrNull() ?: 1
                epNum = m1.group(2)?.toIntOrNull() ?: 0
                val after = f.nameWithoutExtension.substring(m1.end()).trim()
                cleanTitle = after.removePrefix("-").removePrefix(".").removePrefix("_").trim().ifEmpty { f.nameWithoutExtension }
            } else if (m2.find()) {
                seasonNum = m2.group(1)?.toIntOrNull() ?: 1
                epNum = m2.group(2)?.toIntOrNull() ?: 0
                val after = f.nameWithoutExtension.substring(m2.end()).trim()
                cleanTitle = after.removePrefix("-").removePrefix(".").removePrefix("_").trim().ifEmpty { f.nameWithoutExtension }
            } else {
                val sm = seasonDirRegex.matcher(pName)
                if (sm.find()) {
                    seasonNum = sm.group(1)?.toIntOrNull() ?: 1
                }
                val lm = leadingNumRegex.matcher(cleanTitle)
                if (lm.find()) {
                    epNum = lm.group(1)?.toIntOrNull() ?: 0
                    val rest = lm.group(2)?.trim() ?: ""
                    if (rest.isNotEmpty()) cleanTitle = rest
                }
            }

            val epCover = findCoverForFile(f)
            val item = VideoItem(
                file = f,
                title = cleanTitle,
                isTvEpisode = true,
                seriesName = seriesName,
                seasonNumber = seasonNum,
                episodeNumber = epNum,
                coverFile = epCover
            )

            val seasons = seriesMap.getOrPut(seriesName) { LinkedHashMap() }
            val epList = seasons.getOrPut(seasonNum) { ArrayList() }
            epList.add(item)
        }
    }

    val builtSeriesList = ArrayList<TvSeries>()
    for ((sName, seasons) in seriesMap) {
        val builtSeasons = ArrayList<TvSeason>()
        for ((sNum, episodes) in seasons.toSortedMap()) {
            episodes.sortWith { e1, e2 ->
                val ep1 = e1.episodeNumber ?: 0
                val ep2 = e2.episodeNumber ?: 0
                if (ep1 != ep2 && ep1 > 0 && ep2 > 0) ep1.compareTo(ep2)
                else e1.title.compareTo(e2.title, ignoreCase = true)
            }
            val seasonTitle = "${t("Season")} $sNum"
            builtSeasons.add(TvSeason(seriesName = sName, seasonNumber = sNum, seasonTitle = seasonTitle, episodes = episodes))
        }
        builtSeriesList.add(TvSeries(name = sName, seasons = builtSeasons, posterFile = seriesPosterMap[sName]))
    }
    builtSeriesList.sortBy { it.name.lowercase(Locale.US) }
    tvSeriesList.addAll(builtSeriesList)

    if (isVideosControllerInitialized()) {
        runOnUiThread {
            videoBrowserController.clearThumbCache()
            // The rebuild sets the status-bar title and width, so only do it while Videos shows.
            // Videos rebuilds its list in onEnter.
            if (NavigationManager.getInstance().currentState is ScreenState.Videos) {
                videoBrowserController.buildCurrentModeUI()
            }
        }
    }
}
