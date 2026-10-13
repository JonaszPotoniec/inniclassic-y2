package com.themoon.y1.sync;

import com.themoon.y1.sync.jellyfin.JellyfinMediaItem;
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy;
import com.themoon.y1.sync.jellyfin.JellyfinSyncHierarchy.TriState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class JellyfinSyncHierarchyTest {

    @Test
    public void testTvHierarchyAndTriState() {
        List<JellyfinMediaItem> items = new ArrayList<>();

        JellyfinMediaItem ep1 = new JellyfinMediaItem();
        ep1.id = "ep1";
        ep1.name = "Pilot";
        ep1.seriesName = "Breaking Bad";
        ep1.seriesId = "s1";
        ep1.parentIndexNumber = 1;
        ep1.seasonName = "Season 1";
        ep1.indexNumber = 1;
        ep1.fileSize = 1000;
        items.add(ep1);

        JellyfinMediaItem ep2 = new JellyfinMediaItem();
        ep2.id = "ep2";
        ep2.name = "Cat's in the Bag...";
        ep2.seriesName = "Breaking Bad";
        ep2.seriesId = "s1";
        ep2.parentIndexNumber = 1;
        ep2.seasonName = "Season 1";
        ep2.indexNumber = 2;
        ep2.fileSize = 1000;
        items.add(ep2);

        JellyfinMediaItem ep3 = new JellyfinMediaItem();
        ep3.id = "ep3";
        ep3.name = "Seven Thirty-Seven";
        ep3.seriesName = "Breaking Bad";
        ep3.seriesId = "s1";
        ep3.parentIndexNumber = 2;
        ep3.seasonName = "Season 2";
        ep3.indexNumber = 1;
        ep3.fileSize = 1000;
        items.add(ep3);

        JellyfinSyncHierarchy hierarchy = JellyfinSyncHierarchy.buildTvHierarchy(items);

        assertEquals(1, hierarchy.seriesList.size());
        assertEquals(3, hierarchy.allItemIds.size());
        assertEquals(3000, hierarchy.totalLibrarySize);

        JellyfinSyncHierarchy.SeriesNode series = hierarchy.seriesList.get(0);
        assertEquals("Breaking Bad", series.name);
        assertEquals(2, series.seasons.size());

        JellyfinSyncHierarchy.SeasonNode season1 = series.seasons.get(0);
        assertEquals("Season 1", season1.name);
        assertEquals(2, season1.episodes.size());

        JellyfinSyncHierarchy.SeasonNode season2 = series.seasons.get(1);
        assertEquals("Season 2", season2.name);
        assertEquals(1, season2.episodes.size());

        // TriState checks
        Set<String> selected = new HashSet<>();
        assertEquals(TriState.UNCHECKED, series.getTriState(selected));
        assertEquals(TriState.UNCHECKED, season1.getTriState(selected));

        // Select 1 of Season 1
        selected.add("ep1");
        assertEquals(TriState.INDETERMINATE, series.getTriState(selected));
        assertEquals(TriState.INDETERMINATE, season1.getTriState(selected));
        assertEquals(TriState.UNCHECKED, season2.getTriState(selected));
        assertEquals(1, series.getSelectedCount(selected));
        assertEquals(1, season1.getSelectedCount(selected));

        // Select both of Season 1
        selected.add("ep2");
        assertEquals(TriState.INDETERMINATE, series.getTriState(selected));
        assertEquals(TriState.CHECKED, season1.getTriState(selected));
        assertEquals(2, series.getSelectedCount(selected));

        // Select Season 2 as well -> All selected
        selected.add("ep3");
        assertEquals(TriState.CHECKED, series.getTriState(selected));
        assertEquals(TriState.CHECKED, season2.getTriState(selected));
        assertEquals(3, series.getSelectedCount(selected));
    }

    @Test
    public void testMusicHierarchy() {
        List<JellyfinMediaItem> items = new ArrayList<>();

        JellyfinMediaItem s1 = new JellyfinMediaItem();
        s1.id = "song1";
        s1.name = "Come Together";
        s1.artist = "The Beatles";
        s1.album = "Abbey Road";
        s1.indexNumber = 1;
        s1.fileSize = 500;
        items.add(s1);

        JellyfinMediaItem s2 = new JellyfinMediaItem();
        s2.id = "song2";
        s2.name = "Something";
        s2.artist = "The Beatles";
        s2.album = "Abbey Road";
        s2.indexNumber = 2;
        s2.fileSize = 500;
        items.add(s2);

        JellyfinMediaItem s3 = new JellyfinMediaItem();
        s3.id = "song3";
        s3.name = "Taxman";
        s3.artist = "The Beatles";
        s3.album = "Revolver";
        s3.indexNumber = 1;
        s3.fileSize = 500;
        items.add(s3);

        JellyfinSyncHierarchy hierarchy = JellyfinSyncHierarchy.buildMusicHierarchy(items);

        assertEquals(1, hierarchy.artistsList.size());
        JellyfinSyncHierarchy.ArtistNode artist = hierarchy.artistsList.get(0);
        assertEquals("The Beatles", artist.name);
        assertEquals(2, artist.albums.size());

        Set<String> selected = new HashSet<>(Arrays.asList("song1", "song2"));
        assertEquals(TriState.INDETERMINATE, artist.getTriState(selected));

        JellyfinSyncHierarchy.AlbumNode abbeyRoad = null;
        for (JellyfinSyncHierarchy.AlbumNode a : artist.albums) {
            if ("Abbey Road".equals(a.name)) abbeyRoad = a;
        }
        assertNotNull(abbeyRoad);
        assertEquals(TriState.CHECKED, abbeyRoad.getTriState(selected));
    }

    @Test
    public void testMoviesHierarchy() {
        List<JellyfinMediaItem> items = new ArrayList<>();

        JellyfinMediaItem m1 = new JellyfinMediaItem();
        m1.id = "m1";
        m1.name = "Inception";
        m1.year = "2010";
        m1.fileSize = 2000000;
        items.add(m1);

        JellyfinMediaItem m2 = new JellyfinMediaItem();
        m2.id = "m2";
        m2.name = "Interstellar";
        m2.year = "2014";
        m2.fileSize = 2500000;
        items.add(m2);

        JellyfinSyncHierarchy hierarchy = JellyfinSyncHierarchy.buildMoviesHierarchy(items);

        assertEquals(2, hierarchy.moviesList.size());
        assertEquals("Inception", hierarchy.moviesList.get(0).name);
        assertEquals("Interstellar", hierarchy.moviesList.get(1).name);
        assertEquals(2, hierarchy.allItemIds.size());
    }
}
