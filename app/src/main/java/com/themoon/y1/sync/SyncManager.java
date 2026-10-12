package com.themoon.y1.sync;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.themoon.y1.StoragePaths;
import com.themoon.y1.models.SongItem;
import com.themoon.y1.sync.jellyfin.JellyfinClient;
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem;
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton coordinator for media synchronization with WakeLocks, thread safety,
 * progress notifications, and batch library updates.
 */
public class SyncManager {

    private static final String TAG = "SyncManager";
    private static SyncManager instance;

    public enum SyncState {
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

    public interface SyncListener {
        void onStateChanged(SyncState state, String message);
        void onProgress(String libraryName, int currentItem, int totalItems, String currentTitle,
                        long bytesRead, long bytesTotal);
    }

    public interface LibraryBatchUpdateCallback {
        void onMusicBatchReady(List<SongItem> newSongs, List<SongItem> updatedSongs,
                               Map<String, Integer> trackNumbers, boolean isDirty);
        void onVideosBatchReady();
    }

    public interface PlaybackCheckCallback {
        boolean isFileCurrentlyPlaying(File file);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SyncLedger ledger = new SyncLedger();

    private volatile SyncState currentState = SyncState.IDLE;
    private volatile boolean isSyncing = false;
    private MediaSyncProvider.CancellationToken currentCancellationToken;
    private SyncListener syncListener;
    private LibraryBatchUpdateCallback batchUpdateCallback;
    private PlaybackCheckCallback playbackCheckCallback;

    private SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.ledger.load();
    }

    public static synchronized SyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new SyncManager(context);
        }
        return instance;
    }

    public SyncLedger getLedger() {
        return ledger;
    }

    public synchronized boolean isSyncing() {
        return isSyncing;
    }

    public synchronized SyncState getCurrentState() {
        return currentState;
    }

    public void setSyncListener(SyncListener listener) {
        this.syncListener = listener;
    }

    public void setBatchUpdateCallback(LibraryBatchUpdateCallback callback) {
        this.batchUpdateCallback = callback;
    }

    public void setPlaybackCheckCallback(PlaybackCheckCallback callback) {
        this.playbackCheckCallback = callback;
    }

    private void updateState(final SyncState state, final String message) {
        this.currentState = state;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (syncListener != null) {
                    syncListener.onStateChanged(state, message);
                }
            }
        });
    }

    private void updateProgress(final String libraryName, final int currentItem, final int totalItems,
                                final String currentTitle, final long bytesRead, final long bytesTotal) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (syncListener != null) {
                    syncListener.onProgress(libraryName, currentItem, totalItems, currentTitle, bytesRead, bytesTotal);
                }
            }
        });
    }

    public void cancelSync() {
        if (currentCancellationToken != null) {
            currentCancellationToken.cancel();
        }
        updateState(SyncState.CANCELLED, "Cancelled by user");
    }

    /**
     * Resolves sync root according to Y2 SD-first priority.
     */
    public static File getSyncRoot() {
        if (StoragePaths.hasSecondaryStorage()) {
            return new File(StoragePaths.SECONDARY_PATH);
        }
        return new File(StoragePaths.PRIMARY_PATH);
    }

    public synchronized void startSync(final List<SongItem> localSongs,
                                      final Map<String, Integer> trackNumberMap) {
        if (isSyncing) {
            Log.w(TAG, "Sync already in progress");
            return;
        }

        isSyncing = true;
        currentCancellationToken = new MediaSyncProvider.CancellationToken();
        final MediaSyncProvider.CancellationToken token = currentCancellationToken;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = null;
                WifiManager.WifiLock wifiLock = null;

                try {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InniClassic:JellyfinSync");
                    wakeLock.acquire();

                    WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InniClassic:JellyfinSync");
                        wifiLock.acquire();
                    }

                    executeSync(localSongs, trackNumberMap, token);

                } catch (Exception e) {
                    Log.e(TAG, "Sync failed: " + e.getMessage(), e);
                    if (token.isCancelled()) {
                        updateState(SyncState.CANCELLED, "Sync cancelled");
                    } else {
                        updateState(SyncState.ERROR, e.getMessage() != null ? e.getMessage() : "Sync failed");
                    }
                } finally {
                    isSyncing = false;
                    if (wifiLock != null && wifiLock.isHeld()) {
                        try { wifiLock.release(); } catch (Exception ignored) {}
                    }
                    if (wakeLock != null && wakeLock.isHeld()) {
                        try { wakeLock.release(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private void executeSync(List<SongItem> localSongs,
                             Map<String, Integer> trackNumberMap,
                             MediaSyncProvider.CancellationToken token) throws Exception {

        JellyfinSyncProvider provider = JellyfinSyncProvider.getInstance(context);
        if (!provider.isConfigured()) {
            throw new Exception("Jellyfin is not configured");
        }

        updateState(SyncState.AUTHENTICATING, "Authenticating with server...");
        String accessToken = provider.ensureValidAccessToken();
        String userId = provider.getUserId();
        JellyfinClient client = provider.createClient();

        if (accessToken.isEmpty() || userId.isEmpty()) {
            if ("token".equalsIgnoreCase(provider.getAuthMode())) {
                JellyfinClient.AuthResult auth = client.verifyToken(provider.getAccessToken());
                userId = auth.userId;
                provider.setUserId(userId);
            } else {
                JellyfinClient.AuthResult auth = client.authenticateByName(provider.getUsername(), provider.getPassword());
                accessToken = auth.accessToken;
                userId = auth.userId;
                provider.setAccessToken(accessToken);
                provider.setUserId(userId);
            }
        }

        if (token.isCancelled()) return;

        updateState(SyncState.DISCOVERING_LIBRARIES, "Discovering libraries...");
        List<MediaSyncProvider.LibraryInfo> libraries = client.fetchLibraries(accessToken, userId, token);

        File syncRoot = getSyncRoot();
        boolean removeDeleted = provider.isRemoveDeletedEnabled();
        boolean downloadCovers = provider.isDownloadCoversEnabled();
        boolean transcodeVideo = provider.isTranscodeVideoEnabled();

        final List<SongItem> allNewSongs = new ArrayList<>();
        final List<SongItem> allUpdatedSongs = new ArrayList<>();
        final Map<String, Integer> allNewTrackNumbers = new HashMap<>();
        boolean anyDirty = false;
        boolean hasVideoUpdates = false;

        Set<String> seenServerItemIds = new HashSet<>();

        for (MediaSyncProvider.LibraryInfo lib : libraries) {
            if (token.isCancelled()) return;

            String defaultMode = "music".equalsIgnoreCase(lib.collectionType) ? "all" :
                    ("movies".equalsIgnoreCase(lib.collectionType) || "tvshows".equalsIgnoreCase(lib.collectionType) ? "manual" : "off");
            String syncMode = ledger.getLibrarySyncMode(lib.id, defaultMode);

            if ("off".equalsIgnoreCase(syncMode)) {
                continue; // Skip library
            }

            updateState(SyncState.SCANNING_REMOTE, "Scanning " + lib.name + "...");

            List<String> selectedIds = null;
            if ("manual".equalsIgnoreCase(syncMode)) {
                Set<String> sel = ledger.getLibrarySelections(lib.id);
                if (sel.isEmpty()) {
                    continue; // Nothing selected
                }
                selectedIds = new ArrayList<>(sel);
            }

            // Paginated fetch of all items in this library
            List<JellyfinMediaItem> remoteItems = new ArrayList<>();
            int startIndex = 0;
            int pageSize = 200;
            int totalRecords = Integer.MAX_VALUE;

            while (startIndex < totalRecords) {
                if (token.isCancelled()) return;
                JellyfinClient.ItemsQueryResult page = client.fetchItemsPage(
                        accessToken, userId, lib.id, lib.collectionType, startIndex, pageSize, selectedIds, token);
                totalRecords = page.totalRecordCount;
                remoteItems.addAll(page.items);
                startIndex += pageSize;
                if (page.items.isEmpty()) break;
            }

            for (JellyfinMediaItem item : remoteItems) {
                seenServerItemIds.add(item.id);
            }

            if (token.isCancelled()) return;

            updateState(SyncState.RECONCILING, "Reconciling " + lib.name + "...");

            if ("music".equalsIgnoreCase(lib.collectionType)) {
                MetadataReconciler.ReconciliationResult recResult = MetadataReconciler.reconcileMusic(
                        remoteItems, localSongs, trackNumberMap, ledger);

                allUpdatedSongs.addAll(recResult.updatedSongs);
                allNewTrackNumbers.putAll(recResult.updatedTrackNumbers);
                if (recResult.isDirty) anyDirty = true;

                // Download missing items
                List<JellyfinMediaItem> toDownload = recResult.itemsToDownload;
                int totalToDownload = toDownload.size();

                for (int i = 0; i < totalToDownload; i++) {
                    if (token.isCancelled()) return;
                    final JellyfinMediaItem item = toDownload.get(i);
                    final int itemIndex = i + 1;
                    final int finalTotal = totalToDownload;

                    updateState(SyncState.DOWNLOADING, "Downloading " + item.name);

                    // Destination file path: {syncRoot}/Music/Jellyfin/{AlbumArtist}/{Album}/{TrackNum} - {Title}.{ext}
                    String effectiveArtist = (item.albumArtist != null && !item.albumArtist.trim().isEmpty())
                            ? item.albumArtist : item.artist;
                    String artistDir = FileUtils.sanitizeFilename(effectiveArtist);
                    String albumDir = FileUtils.sanitizeFilename(item.album != null ? item.album : "Unknown Album");
                    String trackPrefix = item.indexNumber > 0 ? String.format("%02d - ", item.indexNumber) : "";
                    String filename = trackPrefix + FileUtils.sanitizeFilename(item.name) + "." + item.container;

                    File targetDir = new File(syncRoot, "Music/Jellyfin/" + artistDir + "/" + albumDir);
                    File targetFile = new File(targetDir, filename);

                    // Playback guard
                    if (playbackCheckCallback != null && playbackCheckCallback.isFileCurrentlyPlaying(targetFile)) {
                        Log.w(TAG, "Skipping playing track: " + targetFile.getName());
                        continue;
                    }

                    // Free space check
                    if (!FileUtils.hasAvailableSpace(targetDir, item.fileSize, 50 * 1024 * 1024L)) {
                        throw new Exception("Storage Full. Minimum 50 MB headroom required.");
                    }

                    // Download audio file
                    client.downloadItem(accessToken, item, false, targetFile, new MediaSyncProvider.DownloadProgressListener() {
                        @Override
                        public void onProgress(long bytesDownloaded, long totalBytes) {
                            updateProgress(lib.name, itemIndex, finalTotal, item.name, bytesDownloaded, totalBytes);
                        }
                    }, token);

                    // Download folder.jpg if enabled
                    if (downloadCovers) {
                        File coverFile = new File(targetDir, "folder.jpg");
                        client.downloadCover(accessToken, item.albumId, item.id, coverFile, token);
                    }

                    // Create SongItem and register
                    SongItem newSong = new SongItem(targetFile, item.name, item.artist, item.album,
                            item.year, item.genre, item.albumArtist, item.composer);
                    allNewSongs.add(newSong);
                    if (item.indexNumber > 0) {
                        allNewTrackNumbers.put(targetFile.getAbsolutePath(), item.indexNumber);
                    }
                    anyDirty = true;

                    ledger.putItem(new SyncLedger.LedgerItem(
                            item.id,
                            lib.id,
                            "music",
                            targetFile.getAbsolutePath(),
                            targetFile.length(),
                            item.serverDateModified,
                            false
                    ));
                }

            } else if ("movies".equalsIgnoreCase(lib.collectionType) || "tvshows".equalsIgnoreCase(lib.collectionType)) {
                MetadataReconciler.ReconciliationResult recResult = MetadataReconciler.reconcileVideos(remoteItems, ledger);
                List<JellyfinMediaItem> toDownload = recResult.itemsToDownload;
                int totalToDownload = toDownload.size();

                for (int i = 0; i < totalToDownload; i++) {
                    if (token.isCancelled()) return;
                    final JellyfinMediaItem item = toDownload.get(i);
                    final int itemIndex = i + 1;
                    final int finalTotal = totalToDownload;

                    updateState(SyncState.DOWNLOADING, "Downloading " + item.name);

                    File targetFile;
                    String ext = transcodeVideo ? "mp4" : item.container;
                    if ("tvshows".equalsIgnoreCase(lib.collectionType)) {
                        String series = FileUtils.sanitizeFilename(item.seriesName != null ? item.seriesName : "Unknown Series");
                        String epName = String.format("S%02dE%02d - %s.%s",
                                item.parentIndexNumber, item.indexNumber, FileUtils.sanitizeFilename(item.name), ext);
                        targetFile = new File(syncRoot, "Videos/Jellyfin/" + series + "/" + epName);
                    } else {
                        String yearSuffix = (item.year != null && !item.year.contains("Unknown")) ? " (" + item.year + ")" : "";
                        String movName = FileUtils.sanitizeFilename(item.name) + yearSuffix + "." + ext;
                        targetFile = new File(syncRoot, "Videos/Jellyfin/" + movName);
                    }

                    File targetDir = targetFile.getParentFile();
                    if (!FileUtils.hasAvailableSpace(targetDir, item.fileSize, 50 * 1024 * 1024L)) {
                        throw new Exception("Storage Full. Minimum 50 MB headroom required.");
                    }

                    client.downloadItem(accessToken, item, transcodeVideo, targetFile, new MediaSyncProvider.DownloadProgressListener() {
                        @Override
                        public void onProgress(long bytesDownloaded, long totalBytes) {
                            updateProgress(lib.name, itemIndex, finalTotal, item.name, bytesDownloaded, totalBytes);
                        }
                    }, token);

                    hasVideoUpdates = true;

                    ledger.putItem(new SyncLedger.LedgerItem(
                            item.id,
                            lib.id,
                            lib.collectionType,
                            targetFile.getAbsolutePath(),
                            targetFile.length(),
                            item.serverDateModified,
                            false
                    ));
                }
            }
        }

        // Handle deletion of removed items if requested
        if (removeDeleted) {
            Map<String, SyncLedger.LedgerItem> allLedgerItems = ledger.getAllItems();
            int deletedCount = 0;
            for (Map.Entry<String, SyncLedger.LedgerItem> entry : allLedgerItems.entrySet()) {
                if (token.isCancelled()) return;
                String id = entry.getKey();
                SyncLedger.LedgerItem item = entry.getValue();
                if (!seenServerItemIds.contains(id)) {
                    File f = new File(item.localPath);
                    if (f.exists() && (playbackCheckCallback == null || !playbackCheckCallback.isFileCurrentlyPlaying(f))) {
                        f.delete();
                        deletedCount++;
                    }
                    ledger.removeItem(id);
                    anyDirty = true;
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Removed " + deletedCount + " deleted items locally");
            }
        }

        if (token.isCancelled()) return;

        updateState(SyncState.UPDATING_INDEX, "Updating library index...");
        ledger.setLastSyncTime(System.currentTimeMillis());
        ledger.save();

        final boolean finalDirty = anyDirty;
        final boolean finalVideoUpdates = hasVideoUpdates;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (batchUpdateCallback != null) {
                    batchUpdateCallback.onMusicBatchReady(allNewSongs, allUpdatedSongs, allNewTrackNumbers, finalDirty);
                    if (finalVideoUpdates) {
                        batchUpdateCallback.onVideosBatchReady();
                    }
                }
            }
        });

        updateState(SyncState.COMPLETE, "Sync Complete");
    }
}
