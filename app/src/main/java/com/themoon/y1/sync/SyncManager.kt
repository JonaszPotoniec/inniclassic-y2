package com.themoon.y1.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.themoon.y1.StoragePaths
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.sync.jellyfin.JellyfinClient
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Singleton coordinator for media synchronization with WakeLocks, thread safety,
 * progress notifications, and batch library updates.
 */
class SyncManager internal constructor(context: Context? = null) {

    enum class SyncState {
        IDLE,
        AUTHENTICATING,
        DISCOVERING_LIBRARIES,
        SCANNING_REMOTE,
        RECONCILING,
        DOWNLOADING,
        UPDATING_INDEX,
        COMPLETE,
        ERROR,
        CANCELLED
    }

    interface SyncListener {
        fun onStateChanged(state: SyncState, message: String?)
        fun onProgress(
            libraryName: String,
            currentItem: Int,
            totalItems: Int,
            currentTitle: String,
            bytesRead: Long,
            bytesTotal: Long
        )
    }

    interface LibraryBatchUpdateCallback {
        fun onMusicBatchReady(
            newSongs: List<SongItem>,
            updatedSongs: List<SongItem>,
            trackNumbers: Map<String, Int>,
            isDirty: Boolean
        )
        fun onVideosBatchReady()
    }

    interface PlaybackCheckCallback {
        fun isFileCurrentlyPlaying(file: File): Boolean
    }

    private val context: Context? = context?.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val ledger: SyncLedger = SyncLedger()

    @Volatile
    var currentState: SyncState = SyncState.IDLE
        internal set

    @Volatile
    var isSyncing: Boolean = false
        internal set

    private var currentCancellationToken: MediaSyncProvider.CancellationToken? = null
    var syncListener: SyncListener? = null
    var batchUpdateCallback: LibraryBatchUpdateCallback? = null
    var playbackCheckCallback: PlaybackCheckCallback? = null

    init {
        try {
            ledger.load()
        } catch (_: Exception) {}
    }

    private fun updateState(state: SyncState, message: String?) {
        this.currentState = state
        val posted = try {
            mainHandler.post {
                syncListener?.onStateChanged(state, message)
            }
        } catch (_: Exception) {
            false
        }
        if (!posted) {
            syncListener?.onStateChanged(state, message)
        }
    }

    private fun updateProgress(
        libraryName: String,
        currentItem: Int,
        totalItems: Int,
        currentTitle: String,
        bytesRead: Long,
        bytesTotal: Long
    ) {
        val posted = try {
            mainHandler.post {
                syncListener?.onProgress(libraryName, currentItem, totalItems, currentTitle, bytesRead, bytesTotal)
            }
        } catch (_: Exception) {
            false
        }
        if (!posted) {
            syncListener?.onProgress(libraryName, currentItem, totalItems, currentTitle, bytesRead, bytesTotal)
        }
    }

    fun cancelSync() {
        currentCancellationToken?.cancel()
        isSyncing = false
        updateState(SyncState.CANCELLED, "Cancelled by user")
    }

    @Synchronized
    fun startSync(localSongs: List<SongItem>, trackNumberMap: Map<String, Int>) {
        if (isSyncing) {
            Log.w(TAG, "Sync already in progress")
            return
        }

        isSyncing = true
        val token = MediaSyncProvider.CancellationToken()
        currentCancellationToken = token

        executor.execute {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var wakeLock: PowerManager.WakeLock? = null
            var wifiLock: WifiManager.WifiLock? = null

            try {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InniClassic:JellyfinSync")
                wakeLock?.acquire()

                val wm = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InniClassic:JellyfinSync")
                    wifiLock.acquire()
                }

                executeSync(localSongs, trackNumberMap, token)

            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: " + e.message, e)
                if (token.isCancelled()) {
                    updateState(SyncState.CANCELLED, "Sync cancelled")
                } else {
                    updateState(SyncState.ERROR, e.message ?: "Sync failed")
                }
            } finally {
                isSyncing = false
                if (wifiLock != null && wifiLock.isHeld) {
                    try { wifiLock.release() } catch (ignored: Exception) {}
                }
                if (wakeLock != null && wakeLock.isHeld) {
                    try { wakeLock.release() } catch (ignored: Exception) {}
                }
            }
        }
    }

    private fun executeSync(
        localSongs: List<SongItem>,
        trackNumberMap: Map<String, Int>,
        token: MediaSyncProvider.CancellationToken
    ) {
        val ctx = context ?: throw IllegalStateException("Context required to execute sync")
        val provider = JellyfinSyncProvider.getInstance(ctx)
        if (!provider.isConfigured()) {
            throw Exception("Jellyfin is not configured")
        }

        try {
            com.themoon.y1.migrations.Migration003MigrateVideoDirectories.updateSyncLedgerPaths()
            ledger.load()
        } catch (_: Exception) {}

        updateState(SyncState.AUTHENTICATING, "Authenticating with server...")
        var accessToken = provider.ensureValidAccessToken()
        var userId = provider.userId
        val client = provider.createClient()

        if (accessToken.isEmpty() || userId.isEmpty()) {
            if ("token".equals(provider.authMode, ignoreCase = true)) {
                val auth = client.verifyToken(provider.accessToken)
                userId = auth.userId
                provider.userId = userId
            } else {
                val auth = client.authenticateByName(provider.username, provider.password)
                accessToken = auth.accessToken
                userId = auth.userId
                provider.accessToken = accessToken
                provider.userId = userId
            }
        }

        if (token.isCancelled()) return

        updateState(SyncState.DISCOVERING_LIBRARIES, "Discovering libraries...")
        val libraries = client.fetchLibraries(accessToken, userId, token)

        val syncRoot = getSyncRoot()
        val removeDeleted = provider.isRemoveDeletedEnabled
        val downloadCovers = provider.isDownloadCoversEnabled
        val transcodeVideo = provider.isTranscodeVideoEnabled

        val allNewSongs = ArrayList<SongItem>()
        val allUpdatedSongs = ArrayList<SongItem>()
        val allNewTrackNumbers = HashMap<String, Int>()
        var anyDirty = false
        var hasVideoUpdates = false

        val seenServerItemIds = HashSet<String>()

        for (lib in libraries) {
            if (token.isCancelled()) return

            val defaultMode = if ("music".equals(lib.collectionType, ignoreCase = true)) {
                "all"
            } else if ("movies".equals(lib.collectionType, ignoreCase = true) || "tvshows".equals(lib.collectionType, ignoreCase = true) ||
                "books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)
            ) {
                "manual"
            } else {
                "off"
            }
            val syncMode = ledger.getLibrarySyncMode(lib.id, defaultMode)

            if ("off".equals(syncMode, ignoreCase = true)) {
                continue
            }
            if (com.themoon.y1.FeatureVisibility.skipsJellyfinLibrary(ctx, lib.collectionType)) {
                for (item in ledger.getItemsByLibrary(lib.id)) seenServerItemIds.add(item.jellyfinId) // keep the local files
                continue
            }

            updateState(SyncState.SCANNING_REMOTE, "Scanning " + lib.name + "...")

            var selectedIds: List<String>? = null
            if ("manual".equals(syncMode, ignoreCase = true)) {
                val sel = ledger.getLibrarySelections(lib.id)
                if (sel.isEmpty()) {
                    continue
                }
                selectedIds = ArrayList(sel)
            }

            val remoteItems = ArrayList<JellyfinMediaItem>()
            var startIndex = 0
            val pageSize = 200
            var totalRecords = Int.MAX_VALUE

            while (startIndex < totalRecords) {
                if (token.isCancelled()) return
                val page = client.fetchItemsPage(
                    accessToken, userId, lib.id, lib.collectionType, startIndex, pageSize, selectedIds, token
                )
                totalRecords = page.totalRecordCount
                remoteItems.addAll(page.items)
                startIndex += pageSize
                if (page.items.isEmpty()) break
            }

            for (item in remoteItems) {
                seenServerItemIds.add(item.id)
            }

            if (token.isCancelled()) return

            updateState(SyncState.RECONCILING, "Reconciling " + lib.name + "...")

            if ("music".equals(lib.collectionType, ignoreCase = true)) {
                val recResult = MetadataReconciler.reconcileMusic(
                    remoteItems, localSongs, trackNumberMap, ledger
                )

                allUpdatedSongs.addAll(recResult.updatedSongs)
                allNewTrackNumbers.putAll(recResult.updatedTrackNumbers)
                if (recResult.isDirty) anyDirty = true

                val toDownload = recResult.itemsToDownload
                val totalToDownload = toDownload.size

                for (i in 0 until totalToDownload) {
                    if (token.isCancelled()) return
                    val item = toDownload[i]
                    val itemIndex = i + 1

                    updateState(SyncState.DOWNLOADING, "Downloading " + item.name)

                    val effectiveArtist = if (!item.albumArtist.isNullOrBlank()) {
                        item.albumArtist
                    } else {
                        item.artist
                    }
                    val artistDir = FileUtils.sanitizeFilename(effectiveArtist)
                    val albumDir = FileUtils.sanitizeFilename(if (!item.album.isNullOrEmpty()) item.album else "Unknown Album")
                    val trackPrefix = if (item.indexNumber > 0) String.format("%02d - ", item.indexNumber) else ""
                    val filename = trackPrefix + FileUtils.sanitizeFilename(item.name) + "." + item.container

                    val targetDir = File(syncRoot, "Music/Jellyfin/$artistDir/$albumDir")
                    val targetFile = File(targetDir, filename)

                    if (playbackCheckCallback?.isFileCurrentlyPlaying(targetFile) == true) {
                        Log.w(TAG, "Skipping playing track: " + targetFile.name)
                        continue
                    }

                    val existingAudio = if (targetFile.exists() && targetFile.length() > 0) {
                        targetFile
                    } else {
                        FileUtils.findExistingMatchingFile(targetDir, targetFile.nameWithoutExtension, FileUtils.AUDIO_EXTENSIONS)
                    }

                    if (existingAudio != null && existingAudio.length() > 0) {
                        Log.i(TAG, "Audio file already exists on disk: ${existingAudio.name}, skipping download.")
                        ledger.putItem(
                            SyncLedger.LedgerItem(
                                item.id,
                                lib.id,
                                "music",
                                existingAudio.absolutePath,
                                existingAudio.length(),
                                item.serverDateModified,
                                false
                            )
                        )
                        val newSong = SongItem(
                            existingAudio, item.name, item.artist, item.album,
                            item.year, item.genre, item.albumArtist, item.composer
                        )
                        allNewSongs.add(newSong)
                        if (item.indexNumber > 0) {
                            allNewTrackNumbers[existingAudio.absolutePath] = item.indexNumber
                        }
                        anyDirty = true
                        continue
                    }

                    if (!FileUtils.hasAvailableSpace(targetDir, item.fileSize, 50 * 1024 * 1024L)) {
                        throw Exception("Storage Full. Minimum 50 MB headroom required.")
                    }

                    client.downloadItem(accessToken, item, false, targetFile, object : MediaSyncProvider.DownloadProgressListener {
                        override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                            updateProgress(lib.name, itemIndex, totalToDownload, item.name, bytesDownloaded, totalBytes)
                        }
                    }, token)

                    if (downloadCovers) {
                        val coverFile = File(targetDir, "cover.jpg")
                        client.downloadCover(accessToken, listOfNotNull(item.albumId, item.parentId, item.id), coverFile, token)
                    }

                    val newSong = SongItem(
                        targetFile, item.name, item.artist, item.album,
                        item.year, item.genre, item.albumArtist, item.composer
                    )
                    allNewSongs.add(newSong)
                    if (item.indexNumber > 0) {
                        allNewTrackNumbers[targetFile.absolutePath] = item.indexNumber
                    }
                    anyDirty = true

                    ledger.putItem(
                        SyncLedger.LedgerItem(
                            item.id,
                            lib.id,
                            "music",
                            targetFile.absolutePath,
                            targetFile.length(),
                            item.serverDateModified,
                            false
                        )
                    )
                }

                if (downloadCovers) {
                    val checkedDirs = HashSet<String>()
                    for (item in remoteItems) {
                        if (token.isCancelled()) return
                        val remoteId = item.id ?: continue
                        var targetDir: File? = null

                        val ledgerItem = ledger.getItem(remoteId)
                        if (ledgerItem?.localPath != null) {
                            val f = File(ledgerItem.localPath)
                            if (f.exists()) {
                                targetDir = f.parentFile
                            }
                        }

                        if (targetDir == null || !targetDir.exists()) {
                            val effectiveArtist = if (!item.albumArtist.isNullOrBlank()) {
                                item.albumArtist
                            } else {
                                item.artist
                            }
                            val artistDir = FileUtils.sanitizeFilename(effectiveArtist)
                            val albumDir = FileUtils.sanitizeFilename(if (!item.album.isNullOrEmpty()) item.album else "Unknown Album")

                            val dJellyfin = File(syncRoot, "Music/Jellyfin/$artistDir/$albumDir")
                            val dStandard = File(syncRoot, "Music/$artistDir/$albumDir")
                            targetDir = when {
                                dJellyfin.exists() -> dJellyfin
                                dStandard.exists() -> dStandard
                                else -> {
                                    var found: File? = null
                                    for (root in StoragePaths.getAllRoots()) {
                                        val c1 = File(root, "Music/Jellyfin/$artistDir/$albumDir")
                                        if (c1.exists()) { found = c1; break }
                                        val c2 = File(root, "Music/$artistDir/$albumDir")
                                        if (c2.exists()) { found = c2; break }
                                    }
                                    found ?: dJellyfin
                                }
                            }
                        }

                        if (targetDir != null && checkedDirs.add(targetDir.absolutePath)) {
                            if (targetDir.exists() && MediaLibraryManager.getInstance().findFolderCover(targetDir) == null) {
                                val coverFile = File(targetDir, "cover.jpg")
                                client.downloadCover(accessToken, listOfNotNull(item.albumId, item.parentId, item.id), coverFile, token)
                                if (coverFile.exists() || File(targetDir, "folder.jpg").exists()) {
                                    anyDirty = true
                                }
                            }
                        }
                    }
                }

            } else if ("books".equals(lib.collectionType, ignoreCase = true) || "audiobooks".equals(lib.collectionType, ignoreCase = true)) {
                val recResult = MetadataReconciler.reconcileMusic(
                    remoteItems, localSongs, trackNumberMap, ledger
                )

                allUpdatedSongs.addAll(recResult.updatedSongs)
                allNewTrackNumbers.putAll(recResult.updatedTrackNumbers)
                if (recResult.isDirty) anyDirty = true

                val toDownload = recResult.itemsToDownload
                val totalToDownload = toDownload.size

                for (i in 0 until totalToDownload) {
                    if (token.isCancelled()) return
                    val item = toDownload[i]
                    val itemIndex = i + 1

                    updateState(SyncState.DOWNLOADING, "Downloading " + item.name)

                    val effectiveAuthor = if (!item.albumArtist.isNullOrBlank()) {
                        item.albumArtist
                    } else if (!item.artist.isNullOrBlank()) {
                        item.artist
                    } else {
                        "Unknown Author"
                    }
                    val authorDir = FileUtils.sanitizeFilename(effectiveAuthor)
                    val bookTitle = if (!item.album.isNullOrBlank()) {
                        item.album
                    } else if (!item.seriesName.isNullOrBlank()) {
                        item.seriesName
                    } else {
                        item.name
                    }
                    val bookDir = FileUtils.sanitizeFilename(if (!bookTitle.isNullOrEmpty()) bookTitle else "Unknown Book")
                    val trackPrefix = if (item.indexNumber > 0) String.format("%02d - ", item.indexNumber) else ""
                    val filename = trackPrefix + FileUtils.sanitizeFilename(item.name) + "." + item.container

                    val targetDir = File(syncRoot, "Audiobooks/Jellyfin/$authorDir/$bookDir")
                    val targetFile = File(targetDir, filename)

                    if (playbackCheckCallback?.isFileCurrentlyPlaying(targetFile) == true) {
                        Log.w(TAG, "Skipping playing track: " + targetFile.name)
                        continue
                    }

                    val existingAudio = if (targetFile.exists() && targetFile.length() > 0) {
                        targetFile
                    } else {
                        FileUtils.findExistingMatchingFile(targetDir, targetFile.nameWithoutExtension, FileUtils.AUDIO_EXTENSIONS)
                    }

                    if (existingAudio != null && existingAudio.length() > 0) {
                        Log.i(TAG, "Audiobook file already exists on disk: ${existingAudio.name}, skipping download.")
                        ledger.putItem(
                            SyncLedger.LedgerItem(
                                item.id,
                                lib.id,
                                "books",
                                existingAudio.absolutePath,
                                existingAudio.length(),
                                item.serverDateModified,
                                false
                            )
                        )
                        val newSong = SongItem(
                            existingAudio, item.name, effectiveAuthor, bookTitle,
                            item.year, item.genre, item.albumArtist, item.composer
                        )
                        allNewSongs.add(newSong)
                        if (item.indexNumber > 0) {
                            allNewTrackNumbers[existingAudio.absolutePath] = item.indexNumber
                        }
                        anyDirty = true
                        continue
                    }

                    if (!FileUtils.hasAvailableSpace(targetDir, item.fileSize, 50 * 1024 * 1024L)) {
                        throw Exception("Storage Full. Minimum 50 MB headroom required.")
                    }

                    client.downloadItem(accessToken, item, false, targetFile, object : MediaSyncProvider.DownloadProgressListener {
                        override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                            updateProgress(lib.name, itemIndex, totalToDownload, item.name, bytesDownloaded, totalBytes)
                        }
                    }, token)

                    if (downloadCovers) {
                        val coverFile = File(targetDir, "cover.jpg")
                        client.downloadCover(accessToken, listOfNotNull(item.albumId, item.parentId, item.seriesId, item.id), coverFile, token)
                    }

                    val newSong = SongItem(
                        targetFile, item.name, effectiveAuthor, bookTitle,
                        item.year, item.genre, item.albumArtist, item.composer
                    )
                    allNewSongs.add(newSong)
                    if (item.indexNumber > 0) {
                        allNewTrackNumbers[targetFile.absolutePath] = item.indexNumber
                    }
                    anyDirty = true

                    ledger.putItem(
                        SyncLedger.LedgerItem(
                            item.id,
                            lib.id,
                            "books",
                            targetFile.absolutePath,
                            targetFile.length(),
                            item.serverDateModified,
                            false
                        )
                    )
                }

                if (downloadCovers) {
                    val checkedDirs = HashSet<String>()
                    for (item in remoteItems) {
                        if (token.isCancelled()) return
                        val remoteId = item.id ?: continue
                        var targetDir: File? = null

                        val ledgerItem = ledger.getItem(remoteId)
                        if (ledgerItem?.localPath != null) {
                            val f = File(ledgerItem.localPath)
                            if (f.exists()) {
                                targetDir = f.parentFile
                            }
                        }

                        if (targetDir == null || !targetDir.exists()) {
                            val effectiveAuthor = if (!item.albumArtist.isNullOrBlank()) {
                                item.albumArtist
                            } else if (!item.artist.isNullOrBlank()) {
                                item.artist
                            } else {
                                "Unknown Author"
                            }
                            val authorDir = FileUtils.sanitizeFilename(effectiveAuthor)
                            val bookTitle = if (!item.album.isNullOrBlank()) {
                                item.album
                            } else if (!item.seriesName.isNullOrBlank()) {
                                item.seriesName
                            } else {
                                item.name
                            }
                            val bookDir = FileUtils.sanitizeFilename(if (!bookTitle.isNullOrEmpty()) bookTitle else "Unknown Book")

                            val dJellyfin = File(syncRoot, "Audiobooks/Jellyfin/$authorDir/$bookDir")
                            val dStandard = File(syncRoot, "Audiobooks/$authorDir/$bookDir")
                            targetDir = when {
                                dJellyfin.exists() -> dJellyfin
                                dStandard.exists() -> dStandard
                                else -> {
                                    var found: File? = null
                                    for (root in StoragePaths.getAllRoots()) {
                                        val c1 = File(root, "Audiobooks/Jellyfin/$authorDir/$bookDir")
                                        if (c1.exists()) { found = c1; break }
                                        val c2 = File(root, "Audiobooks/$authorDir/$bookDir")
                                        if (c2.exists()) { found = c2; break }
                                    }
                                    found ?: dJellyfin
                                }
                            }
                        }

                        if (targetDir != null && checkedDirs.add(targetDir.absolutePath)) {
                            if (targetDir.exists() && MediaLibraryManager.getInstance().findFolderCover(targetDir) == null) {
                                val coverFile = File(targetDir, "cover.jpg")
                                client.downloadCover(accessToken, listOfNotNull(item.albumId, item.parentId, item.seriesId, item.id), coverFile, token)
                                if (coverFile.exists() || File(targetDir, "folder.jpg").exists()) {
                                    anyDirty = true
                                }
                            }
                        }
                    }
                }

            } else if ("movies".equals(lib.collectionType, ignoreCase = true) || "tvshows".equals(lib.collectionType, ignoreCase = true)) {
                val recResult = MetadataReconciler.reconcileVideos(remoteItems, ledger)
                val toDownload = recResult.itemsToDownload
                val totalToDownload = toDownload.size

                for (i in 0 until totalToDownload) {
                    if (token.isCancelled()) return
                    val item = toDownload[i]
                    val itemIndex = i + 1

                    updateState(SyncState.DOWNLOADING, "Downloading " + item.name)

                    val ext = if (transcodeVideo) "mp4" else item.container
                    val targetFile = if ("tvshows".equals(lib.collectionType, ignoreCase = true)) {
                        val series = FileUtils.sanitizeFilename(if (!item.seriesName.isNullOrEmpty()) item.seriesName else "Unknown Series")
                        val epName = String.format(
                            "S%02dE%02d - %s.%s",
                            item.parentIndexNumber, item.indexNumber, FileUtils.sanitizeFilename(item.name), ext
                        )
                        File(syncRoot, "Tvshows/Jellyfin/$series/$epName")
                    } else {
                        val y = item.year
                        val yearSuffix = if (!y.isNullOrEmpty() && !y.contains("Unknown")) " (" + y + ")" else ""
                        val movName = FileUtils.sanitizeFilename(item.name) + yearSuffix + "." + ext
                        File(syncRoot, "Movies/Jellyfin/$movName")
                    }

                    val targetDir = targetFile.parentFile ?: syncRoot

                    val existingVideo = if (targetFile.exists() && targetFile.length() > 0) {
                        targetFile
                    } else {
                        FileUtils.findExistingMatchingFile(targetDir, targetFile.nameWithoutExtension, FileUtils.VIDEO_EXTENSIONS)
                    }

                    if (existingVideo != null && existingVideo.length() > 0) {
                        Log.i(TAG, "Video file already exists on disk: ${existingVideo.name}, skipping download.")
                        ledger.putItem(
                            SyncLedger.LedgerItem(
                                item.id,
                                lib.id,
                                lib.collectionType,
                                existingVideo.absolutePath,
                                existingVideo.length(),
                                item.serverDateModified,
                                false
                            )
                        )
                        if (downloadCovers) {
                            ensureVideoCovers(client, accessToken, lib.collectionType, item, existingVideo, targetDir, token)
                        }
                        hasVideoUpdates = true
                        continue
                    }

                    if (!FileUtils.hasAvailableSpace(targetDir, item.fileSize, 50 * 1024 * 1024L)) {
                        throw Exception("Storage Full. Minimum 50 MB headroom required.")
                    }

                    client.downloadItem(accessToken, item, transcodeVideo, targetFile, object : MediaSyncProvider.DownloadProgressListener {
                        override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                            updateProgress(lib.name, itemIndex, totalToDownload, item.name, bytesDownloaded, totalBytes)
                        }
                    }, token)

                    if (downloadCovers) {
                        ensureVideoCovers(client, accessToken, lib.collectionType, item, targetFile, targetDir, token)
                    }

                    hasVideoUpdates = true

                    ledger.putItem(
                        SyncLedger.LedgerItem(
                            item.id,
                            lib.id,
                            lib.collectionType,
                            targetFile.absolutePath,
                            targetFile.length(),
                            item.serverDateModified,
                            false
                        )
                    )
                }

                if (downloadCovers) {
                    for (item in remoteItems) {
                        if (token.isCancelled()) return
                        val remoteId = item.id ?: continue
                        val ledgerItem = ledger.getItem(remoteId)
                        val videoFile = if (ledgerItem?.localPath != null) File(ledgerItem.localPath) else null
                        if (videoFile != null && videoFile.exists()) {
                            val targetDir = videoFile.parentFile ?: continue
                            if (ensureVideoCovers(client, accessToken, lib.collectionType, item, videoFile, targetDir, token)) {
                                hasVideoUpdates = true
                                anyDirty = true
                            }
                        }
                    }
                }
            }
        }

        if (removeDeleted) {
            val allLedgerItems = ledger.getAllItems()
            var deletedCount = 0
            for ((id, item) in allLedgerItems) {
                if (token.isCancelled()) return
                if (!seenServerItemIds.contains(id)) {
                    val p = item.localPath
                    val f = if (p != null) File(p) else null
                    if (f != null && f.exists() && (playbackCheckCallback == null || !playbackCheckCallback!!.isFileCurrentlyPlaying(f))) {
                        f.delete()
                        deletedCount++
                    }
                    ledger.removeItem(id)
                    anyDirty = true
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Removed $deletedCount deleted items locally")
            }
        }

        if (token.isCancelled()) return

        updateState(SyncState.UPDATING_INDEX, "Updating library index...")
        ledger.setLastSyncTime(System.currentTimeMillis())
        ledger.save()

        val finalDirty = anyDirty
        val finalVideoUpdates = hasVideoUpdates

        mainHandler.post {
            if (finalDirty) {
                AlbumCoverManager.getInstance().clearCache(true)
            }
            batchUpdateCallback?.let { cb ->
                cb.onMusicBatchReady(allNewSongs, allUpdatedSongs, allNewTrackNumbers, finalDirty)
                if (finalVideoUpdates) {
                    cb.onVideosBatchReady()
                }
            }
        }

        updateState(SyncState.COMPLETE, "Sync Complete")
    }

    private fun ensureVideoCovers(
        client: JellyfinClient,
        accessToken: String,
        collectionType: String?,
        item: JellyfinMediaItem,
        targetFile: File,
        targetDir: File,
        token: MediaSyncProvider.CancellationToken
    ): Boolean {
        var downloadedAny = false
        try {
            if ("tvshows".equals(collectionType, ignoreCase = true)) {
                var seriesDir = targetFile.parentFile
                if (seriesDir != null && seriesDir.name.matches(Regex("(?i)(?:season|series|s)\\s*\\d+|specials"))) {
                    seriesDir = seriesDir.parentFile ?: seriesDir
                }
                if (seriesDir != null && seriesDir.exists()) {
                    val seriesCover = File(seriesDir, "folder.jpg")
                    if (!seriesCover.exists()) {
                        client.downloadCover(accessToken, listOfNotNull(item.seriesId, item.parentId, item.id), seriesCover, token)
                        if (seriesCover.exists()) downloadedAny = true
                    }
                }
                val epCover = File(targetDir, "${targetFile.nameWithoutExtension}.jpg")
                if (!epCover.exists()) {
                    client.downloadCover(accessToken, listOfNotNull(item.id), epCover, token)
                    if (epCover.exists()) downloadedAny = true
                }
            } else {
                val movieCover = File(targetDir, "${targetFile.nameWithoutExtension}.jpg")
                if (!movieCover.exists()) {
                    client.downloadCover(accessToken, listOfNotNull(item.id), movieCover, token)
                    if (movieCover.exists()) downloadedAny = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading video cover for ${item.name}: ${e.message}")
        }
        return downloadedAny
    }

    companion object {
        private const val TAG = "SyncManager"

        @Volatile
        private var instance: SyncManager? = null
        @Synchronized
        fun getInstance(context: Context): SyncManager {
            var inst = instance
            if (inst == null) {
                inst = SyncManager(context)
                instance = inst
            }
            return inst
        }

        /**
         * Resolves sync root according to Y2 SD-first priority.
         */

        fun getSyncRoot(): File {
            return if (StoragePaths.hasSecondaryStorage()) {
                File(StoragePaths.SECONDARY_PATH)
            } else {
                File(StoragePaths.PRIMARY_PATH)
            }
        }
    }
}
