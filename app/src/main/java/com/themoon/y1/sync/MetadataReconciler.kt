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
        @JvmField val itemsToDownload: MutableList<JellyfinMediaItem> = ArrayList()
        @JvmField val updatedSongs: MutableList<SongItem> = ArrayList()
        @JvmField val updatedTrackNumbers: MutableMap<String, Int> = HashMap() // absPath -> trackNum
        @JvmField var isDirty: Boolean = false
    }

    @JvmStatic
    fun normalizeText(text: String?): String {
        if (text == null) return ""
        return text.trim().lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun makeSongKey(title: String?, artist: String?, album: String?): String {
        return normalizeText(title) + "|" + normalizeText(artist) + "|" + normalizeText(album)
    }

    /**
     * Reconciles remote music items against the local customLibrary and sync ledger.
     */
    @JvmStatic
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

            if (ledgerItem != null && ledgerItem.localPath != null) {
                val localFile = File(ledgerItem.localPath)
                if (localFile.exists()) {
                    if (localSongs != null) {
                        for (s in localSongs) {
                            if (s.file.absolutePath == ledgerItem.localPath) {
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
    @JvmStatic
    fun reconcileVideos(
        remoteItems: List<JellyfinMediaItem>,
        ledger: SyncLedger
    ): ReconciliationResult {
        val result = ReconciliationResult()

        for (remote in remoteItems) {
            val remoteId = remote.id ?: continue
            val ledgerItem = ledger.getItem(remoteId)
            if (ledgerItem != null && ledgerItem.localPath != null) {
                val localFile = File(ledgerItem.localPath)
                if (localFile.exists() && localFile.length() > 0) {
                    ledgerItem.orphaned = false
                    continue
                }
            }
            result.itemsToDownload.add(remote)
        }

        return result
    }
}
