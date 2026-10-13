package com.themoon.y1.sync

/**
 * Common interface for media sync providers (Jellyfin, Subsonic, Plex, etc.).
 */
interface MediaSyncProvider {

    class LibraryInfo(
        val id: String,
        val name: String,
        val collectionType: String // "music", "movies", "tvshows", etc.
    )

    interface ConnectionCallback {
        fun onSuccess(serverName: String, version: String)
        fun onError(errorMessage: String)
    }

    interface DownloadProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    class CancellationToken {
        @Volatile
        private var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
        }

        fun isCancelled(): Boolean {
            return cancelled
        }
    }

    fun getId(): String
    fun getDisplayName(): String
    fun isConfigured(): Boolean
    fun getStatusSummary(): String

    fun testConnection(callback: ConnectionCallback)

    @Throws(Exception::class)
    fun fetchLibraries(token: CancellationToken?): List<LibraryInfo>

    fun clearConfiguration()
}
