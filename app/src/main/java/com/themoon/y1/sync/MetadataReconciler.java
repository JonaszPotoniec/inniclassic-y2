package com.themoon.y1.sync;

import com.themoon.y1.models.SongItem;
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconciles remote Jellyfin media items with local library and ledger.
 */
public class MetadataReconciler {

    public static class ReconciliationResult {
        public final List<JellyfinMediaItem> itemsToDownload = new ArrayList<>();
        public final List<SongItem> updatedSongs = new ArrayList<>();
        public final Map<String, Integer> updatedTrackNumbers = new HashMap<>(); // absPath -> trackNum
        public boolean isDirty = false;
    }

    public static String normalizeText(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase(Locale.ROOT);
    }

    public static String makeSongKey(String title, String artist, String album) {
        return normalizeText(title) + "|" + normalizeText(artist) + "|" + normalizeText(album);
    }

    /**
     * Reconciles remote music items against the local customLibrary and sync ledger.
     */
    public static ReconciliationResult reconcileMusic(
            List<JellyfinMediaItem> remoteItems,
            List<SongItem> localSongs,
            Map<String, Integer> trackNumberMap,
            SyncLedger ledger) {

        ReconciliationResult result = new ReconciliationResult();

        // Build index of local songs by title+artist+album
        Map<String, SongItem> localIndex = new HashMap<>();
        if (localSongs != null) {
            for (SongItem song : localSongs) {
                if (song != null && song.file != null && song.file.exists()) {
                    String key = makeSongKey(song.title, song.artist, song.album);
                    localIndex.put(key, song);
                }
            }
        }

        for (JellyfinMediaItem remote : remoteItems) {
            SyncLedger.LedgerItem ledgerItem = ledger.getItem(remote.id);
            SongItem matchedSong = null;

            if (ledgerItem != null && ledgerItem.localPath != null) {
                File localFile = new File(ledgerItem.localPath);
                if (localFile.exists()) {
                    // Find song by path if possible
                    if (localSongs != null) {
                        for (SongItem s : localSongs) {
                            if (s.file != null && s.file.getAbsolutePath().equals(ledgerItem.localPath)) {
                                matchedSong = s;
                                break;
                            }
                        }
                    }
                    if (matchedSong == null) {
                        // Song exists on disk, create or match
                        matchedSong = new SongItem(localFile, remote.name, remote.artist, remote.album,
                                remote.year, remote.genre, remote.albumArtist, remote.composer);
                    }
                }
            }

            if (matchedSong == null) {
                String key = makeSongKey(remote.name, remote.artist, remote.album);
                matchedSong = localIndex.get(key);
            }

            if (matchedSong != null && matchedSong.file != null && matchedSong.file.exists()) {
                // Match found! Check if metadata differs
                boolean metaChanged = false;
                if (!normalizeText(matchedSong.title).equals(normalizeText(remote.name))) {
                    matchedSong.title = remote.name;
                    metaChanged = true;
                }
                if (!normalizeText(matchedSong.artist).equals(normalizeText(remote.artist))) {
                    matchedSong.artist = remote.artist;
                    metaChanged = true;
                }
                if (!normalizeText(matchedSong.album).equals(normalizeText(remote.album))) {
                    matchedSong.album = remote.album;
                    metaChanged = true;
                }
                if (remote.year != null && !remote.year.equals(matchedSong.year)) {
                    matchedSong.year = remote.year;
                    metaChanged = true;
                }
                if (remote.genre != null && !remote.genre.equals(matchedSong.genre)) {
                    matchedSong.genre = remote.genre;
                    metaChanged = true;
                }
                if (remote.albumArtist != null && !remote.albumArtist.equals(matchedSong.albumArtist)) {
                    matchedSong.albumArtist = remote.albumArtist;
                    metaChanged = true;
                }
                if (remote.composer != null && !remote.composer.equals(matchedSong.composer)) {
                    matchedSong.composer = remote.composer;
                    metaChanged = true;
                }

                String absPath = matchedSong.file.getAbsolutePath();
                if (remote.indexNumber > 0) {
                    Integer currentNum = trackNumberMap != null ? trackNumberMap.get(absPath) : null;
                    if (currentNum == null || currentNum != remote.indexNumber) {
                        result.updatedTrackNumbers.put(absPath, remote.indexNumber);
                        metaChanged = true;
                    }
                }

                if (metaChanged) {
                    result.updatedSongs.add(matchedSong);
                    result.isDirty = true;
                }

                // Register in ledger
                ledger.putItem(new SyncLedger.LedgerItem(
                        remote.id,
                        remote.libraryId,
                        "music",
                        absPath,
                        matchedSong.file.length(),
                        remote.serverDateModified,
                        false
                ));
            } else {
                // Not found locally -> need download
                result.itemsToDownload.add(remote);
            }
        }

        return result;
    }

    /**
     * Reconciles remote video (movie / tv) items against local files and sync ledger.
     */
    public static ReconciliationResult reconcileVideos(
            List<JellyfinMediaItem> remoteItems,
            SyncLedger ledger) {

        ReconciliationResult result = new ReconciliationResult();

        for (JellyfinMediaItem remote : remoteItems) {
            SyncLedger.LedgerItem ledgerItem = ledger.getItem(remote.id);
            if (ledgerItem != null && ledgerItem.localPath != null) {
                File localFile = new File(ledgerItem.localPath);
                if (localFile.exists() && localFile.length() > 0) {
                    ledgerItem.orphaned = false;
                    continue; // Already downloaded
                }
            }
            result.itemsToDownload.add(remote);
        }

        return result;
    }
}
