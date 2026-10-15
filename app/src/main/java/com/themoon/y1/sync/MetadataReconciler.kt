package com.themoon.y1.sync

import com.themoon.y1.models.SongItem
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem
import java.io.File
import java.util.Locale

/**
 * Reconciles remote Jellyfin media items with local library and ledger.
 */
object MetadataReconciler {

    class ReconciliationResult {
        val itemsToDownload: MutableList<JellyfinMediaItem> = ArrayList()
        val updatedSongs: MutableList<SongItem> = ArrayList()
        val updatedTrackNumbers: MutableMap<String, Int> = HashMap() // absPath -> trackNum
        var isDirty: Boolean = false
    }
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalizeText(text: String?): String {
        if (text == null) return ""
        return text.trim().replace(WHITESPACE_REGEX, " ").lowercase(Locale.ROOT)
    }
    fun makeSongKey(title: String?, artist: String?, album: String?): String {
        return normalizeText(title) + "|" + normalizeText(artist) + "|" + normalizeText(album)
    }

    /**
     * Reconciles remote music items against the local customLibrary and sync ledger.
     */

    fun reconcileMusic(
        remoteItems: List<JellyfinMediaItem>,
        localSongs: List<SongItem>?,
        trackNumberMap: Map<String, Int>?,
        ledger: SyncLedger
    ): ReconciliationResult {
        val result = ReconciliationResult()

        val localIndex = HashMap<String, SongItem>()
        if (localSongs != null) {
            for (song in localSongs) {
                if (song.file.exists()) {
                    val key = makeSongKey(song.title, song.artist, song.album)
                    localIndex[key] = song
                }
            }
        }

        for (remote in remoteItems) {
            val remoteId = remote.id ?: continue
            val ledgerItem = ledger.getItem(remoteId)
            var matchedSong: SongItem? = null

            val ledgerPath = ledgerItem?.localPath
            if (ledgerPath != null) {
                val localFile = File(ledgerPath)
                if (localFile.exists()) {
                    if (localSongs != null) {
                        for (s in localSongs) {
                            if (s.file.absolutePath == ledgerPath) {
                                matchedSong = s
                                break
                            }
                        }
                    }
                    if (matchedSong == null) {
                        matchedSong = SongItem(
                            localFile,
                            remote.name ?: "",
                            remote.artist,
                            remote.album,
                            remote.year,
                            remote.genre,
                            remote.albumArtist,
                            remote.composer
                        )
                    }
                }
            }

            if (matchedSong == null) {
                val key = makeSongKey(remote.name, remote.artist, remote.album)
                matchedSong = localIndex[key]
            }

            if (matchedSong != null && matchedSong.file.exists()) {
                var metaChanged = false
                val remoteName = remote.name ?: ""
                val remoteArtist = remote.artist ?: "Unknown Artist"
                val remoteAlbum = remote.album ?: "Unknown Album"

                if (normalizeText(matchedSong.title) != normalizeText(remoteName)) {
                    matchedSong.title = remoteName
                    metaChanged = true
                }
                if (normalizeText(matchedSong.artist) != normalizeText(remoteArtist)) {
                    matchedSong.artist = remoteArtist
                    metaChanged = true
                }
                if (normalizeText(matchedSong.album) != normalizeText(remoteAlbum)) {
                    matchedSong.album = remoteAlbum
                    metaChanged = true
                }
                if (remote.year != null && remote.year != matchedSong.year) {
                    matchedSong.year = remote.year ?: "Unknown Year"
                    metaChanged = true
                }
                if (remote.genre != null && remote.genre != matchedSong.genre) {
                    matchedSong.genre = remote.genre ?: "Unknown Genre"
                    metaChanged = true
                }
                if (remote.albumArtist != null && remote.albumArtist != matchedSong.albumArtist) {
                    matchedSong.albumArtist = remote.albumArtist ?: matchedSong.artist
                    metaChanged = true
                }
                if (remote.composer != null && remote.composer != matchedSong.composer) {
                    matchedSong.composer = remote.composer ?: "Unknown Composer"
                    metaChanged = true
                }

                val absPath = matchedSong.file.absolutePath
                if (remote.indexNumber > 0) {
                    val currentNum = trackNumberMap?.get(absPath)
                    if (currentNum == null || currentNum != remote.indexNumber) {
                        result.updatedTrackNumbers[absPath] = remote.indexNumber
                        metaChanged = true
                    }
                }

                if (metaChanged) {
                    result.updatedSongs.add(matchedSong)
                    result.isDirty = true
                }

                ledger.putItem(
                    SyncLedger.LedgerItem(
                        remoteId,
                        remote.libraryId,
                        "music",
                        absPath,
                        matchedSong.file.length(),
                        remote.serverDateModified,
                        false
                    )
                )
            } else {
                result.itemsToDownload.add(remote)
            }
        }

        return result
    }

    /**
     * Reconciles remote video (movie / tv) items against local files and sync ledger.
     */
    fun reconcileVideos(
        remoteItems: List<JellyfinMediaItem>,
        ledger: SyncLedger
    ): ReconciliationResult {
        val result = ReconciliationResult()

        for (remote in remoteItems) {
            val remoteId = remote.id ?: continue
            val ledgerItem = ledger.getItem(remoteId)
            var foundFile: File? = null

            val ledgerPath = ledgerItem?.localPath
            if (ledgerPath != null) {
                val directFile = File(ledgerPath)
                if (directFile.exists() && directFile.length() > 0) {
                    foundFile = directFile
                } else {
                    // Check if a file with same base name but different extension exists in parent
                    val p = directFile.parentFile
                    if (p != null) {
                        foundFile = FileUtils.findExistingMatchingFile(p, directFile.nameWithoutExtension, FileUtils.VIDEO_EXTENSIONS)
                    }
                }
            }

            // Fallback: check across all storage roots if file already exists in standard location
            if (foundFile == null) {
                foundFile = findVideoOnDisk(remote)
            }

            val existing = foundFile
            if (existing != null && existing.exists() && existing.length() > 0) {
                if (ledgerItem != null) {
                    ledgerItem.localPath = existing.absolutePath
                    ledgerItem.fileSize = existing.length()
                    ledgerItem.orphaned = false
                } else {
                    ledger.putItem(
                        SyncLedger.LedgerItem(
                            remoteId,
                            remote.libraryId,
                            remote.collectionType,
                            existing.absolutePath,
                            existing.length(),
                            remote.serverDateModified,
                            false
                        )
                    )
                }
                continue
            }

            result.itemsToDownload.add(remote)
        }

        return result
    }

    private fun findVideoOnDisk(item: JellyfinMediaItem): File? {
        val isTv = "tvshows".equals(item.collectionType, ignoreCase = true)
        val roots = com.themoon.y1.StoragePaths.getAllRoots()
        for (root in roots) {
            if (isTv) {
                val series = FileUtils.sanitizeFilename(if (!item.seriesName.isNullOrEmpty()) item.seriesName else "Unknown Series")
                val epBase = String.format(Locale.US, "S%02dE%02d - %s", item.parentIndexNumber, item.indexNumber, FileUtils.sanitizeFilename(item.name))
                val seriesDir = File(root, "Tvshows/Jellyfin/$series")
                val match = FileUtils.findExistingMatchingFile(seriesDir, epBase, FileUtils.VIDEO_EXTENSIONS)
                if (match != null) return match
            } else {
                val y = item.year
                val yearSuffix = if (!y.isNullOrEmpty() && !y.contains("Unknown")) " ($y)" else ""
                val movBase = FileUtils.sanitizeFilename(item.name) + yearSuffix
                val moviesDir = File(root, "Movies/Jellyfin")
                val match = FileUtils.findExistingMatchingFile(moviesDir, movBase, FileUtils.VIDEO_EXTENSIONS)
                if (match != null) return match
                val matchNoYear = FileUtils.findExistingMatchingFile(moviesDir, FileUtils.sanitizeFilename(item.name), FileUtils.VIDEO_EXTENSIONS)
                if (matchNoYear != null) return matchNoYear
            }
        }
        return null
    }
}
