package com.themoon.y1.sync;

import com.themoon.y1.models.SongItem;
import com.themoon.y1.sync.jellyfin.JellyfinMediaItem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class MetadataReconcilerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testReconcileMusicMatchingAndDiff() throws IOException {
        File dummyFile = folder.newFile("song.mp3");

        SongItem localSong = new SongItem(dummyFile, "Comfortably Numb", "Pink Floyd", "The Wall",
                "1979", "Rock", "Pink Floyd", "Roger Waters");
        List<SongItem> localSongs = new ArrayList<>();
        localSongs.add(localSong);

        Map<String, Integer> trackNumberMap = new HashMap<>();
        trackNumberMap.put(dummyFile.getAbsolutePath(), 1);

        SyncLedger ledger = new SyncLedger();

        // 1. Remote item matches local song by metadata, but has updated genre and track number
        JellyfinMediaItem remoteItem = new JellyfinMediaItem();
        remoteItem.id = "jf-100";
        remoteItem.name = "Comfortably Numb";
        remoteItem.artist = "Pink Floyd";
        remoteItem.album = "The Wall";
        remoteItem.year = "1979";
        remoteItem.genre = "Progressive Rock"; // changed
        remoteItem.albumArtist = "Pink Floyd";
        remoteItem.composer = "Roger Waters";
        remoteItem.indexNumber = 6; // changed from 1 to 6
        remoteItem.libraryId = "lib-music";
        remoteItem.collectionType = "music";
        remoteItem.fileSize = dummyFile.length();

        List<JellyfinMediaItem> remoteList = new ArrayList<>();
        remoteList.add(remoteItem);

        MetadataReconciler.ReconciliationResult res = MetadataReconciler.reconcileMusic(
                remoteList, localSongs, trackNumberMap, ledger);

        // Should NOT download the file because local file matched
        assertEquals(0, res.itemsToDownload.size());
        assertTrue(res.isDirty);
        assertEquals(1, res.updatedSongs.size());
        assertEquals("Progressive Rock", localSong.genre);
        assertEquals(Integer.valueOf(6), res.updatedTrackNumbers.get(dummyFile.getAbsolutePath()));

        // Ledger should have the entry registered
        assertNotNull(ledger.getItem("jf-100"));
        assertEquals(dummyFile.getAbsolutePath(), ledger.getItem("jf-100").localPath);
    }

    @Test
    public void testReconcileMusicMissingNeedsDownload() {
        List<SongItem> localSongs = new ArrayList<>();
        Map<String, Integer> trackNumberMap = new HashMap<>();
        SyncLedger ledger = new SyncLedger();

        JellyfinMediaItem remoteItem = new JellyfinMediaItem();
        remoteItem.id = "jf-200";
        remoteItem.name = "Time";
        remoteItem.artist = "Pink Floyd";
        remoteItem.album = "The Dark Side of the Moon";
        remoteItem.libraryId = "lib-music";
        remoteItem.collectionType = "music";

        List<JellyfinMediaItem> remoteList = new ArrayList<>();
        remoteList.add(remoteItem);

        MetadataReconciler.ReconciliationResult res = MetadataReconciler.reconcileMusic(
                remoteList, localSongs, trackNumberMap, ledger);

        assertEquals(1, res.itemsToDownload.size());
        assertEquals("jf-200", res.itemsToDownload.get(0).id);
    }

    @Test
    public void testReconcileVideos() throws IOException {
        File dummyVideo = folder.newFile("movie.mp4");
        java.nio.file.Files.write(dummyVideo.toPath(), new byte[]{1, 2, 3});
        SyncLedger ledger = new SyncLedger();
        ledger.putItem(new SyncLedger.LedgerItem(
                "jf-movie-1",
                "lib-movies",
                "movies",
                dummyVideo.getAbsolutePath(),
                dummyVideo.length(),
                "2024-01-01",
                false
        ));

        JellyfinMediaItem remoteMovie1 = new JellyfinMediaItem();
        remoteMovie1.id = "jf-movie-1";
        remoteMovie1.name = "Inception";
        remoteMovie1.collectionType = "movies";

        JellyfinMediaItem remoteMovie2 = new JellyfinMediaItem();
        remoteMovie2.id = "jf-movie-2";
        remoteMovie2.name = "The Matrix";
        remoteMovie2.collectionType = "movies";

        List<JellyfinMediaItem> remoteList = new ArrayList<>();
        remoteList.add(remoteMovie1);
        remoteList.add(remoteMovie2);

        MetadataReconciler.ReconciliationResult res = MetadataReconciler.reconcileVideos(remoteList, ledger);

        // Movie 1 already exists on disk and is in ledger; Movie 2 needs download
        assertEquals(1, res.itemsToDownload.size());
        assertEquals("jf-movie-2", res.itemsToDownload.get(0).id);
    }
}
