package com.themoon.y1.sync;

import java.io.File;
import java.util.List;

/**
 * Common interface for media sync providers (Jellyfin, Subsonic, Plex, etc.).
 */
public interface MediaSyncProvider {

    class LibraryInfo {
        public final String id;
        public final String name;
        public final String collectionType; // "music", "movies", "tvshows", etc.

        public LibraryInfo(String id, String name, String collectionType) {
            this.id = id;
            this.name = name;
            this.collectionType = collectionType;
        }
    }

    interface ConnectionCallback {
        void onSuccess(String serverName, String version);
        void onError(String errorMessage);
    }

    interface DownloadProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    class CancellationToken {
        private volatile boolean cancelled = false;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    String getId();
    String getDisplayName();
    boolean isConfigured();
    String getStatusSummary();

    void testConnection(ConnectionCallback callback);
    List<LibraryInfo> fetchLibraries(CancellationToken token) throws Exception;
    void clearConfiguration();
}
