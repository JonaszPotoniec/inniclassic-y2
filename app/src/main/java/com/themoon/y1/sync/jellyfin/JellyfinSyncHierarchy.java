package com.themoon.y1.sync.jellyfin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hierarchical tree representation and tri-state calculation helper for Jellyfin items.
 * Supports:
 * - TV Shows: Series -> Seasons -> Episodes
 * - Music: Artists -> Albums -> Songs
 * - Movies: Flat list of movies
 */
public class JellyfinSyncHierarchy {

    public enum TriState {
        UNCHECKED,
        INDETERMINATE,
        CHECKED
    }

    public static TriState computeTriState(Collection<String> itemIds, Set<String> selectedIds) {
        if (itemIds == null || itemIds.isEmpty() || selectedIds == null || selectedIds.isEmpty()) {
            return TriState.UNCHECKED;
        }
        int selectedCount = 0;
        for (String id : itemIds) {
            if (selectedIds.contains(id)) {
                selectedCount++;
            }
        }
        if (selectedCount == 0) {
            return TriState.UNCHECKED;
        } else if (selectedCount == itemIds.size()) {
            return TriState.CHECKED;
        } else {
            return TriState.INDETERMINATE;
        }
    }

    public static int countSelected(Collection<String> itemIds, Set<String> selectedIds) {
        if (itemIds == null || itemIds.isEmpty() || selectedIds == null || selectedIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String id : itemIds) {
            if (selectedIds.contains(id)) {
                count++;
            }
        }
        return count;
    }

    public static class SeriesNode {
        public final String id;
        public final String name;
        public final List<SeasonNode> seasons = new ArrayList<>();
        public final Set<String> allEpisodeIds = new HashSet<>();
        public long totalSize = 0;

        public SeriesNode(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public TriState getTriState(Set<String> selectedIds) {
            return computeTriState(allEpisodeIds, selectedIds);
        }

        public int getSelectedCount(Set<String> selectedIds) {
            return countSelected(allEpisodeIds, selectedIds);
        }
    }

    public static class SeasonNode {
        public final String id;
        public final String name;
        public final int seasonNumber;
        public final String seriesName;
        public final List<JellyfinMediaItem> episodes = new ArrayList<>();
        public final Set<String> allEpisodeIds = new HashSet<>();
        public long totalSize = 0;

        public SeasonNode(String id, String name, int seasonNumber, String seriesName) {
            this.id = id;
            this.name = name;
            this.seasonNumber = seasonNumber;
            this.seriesName = seriesName;
        }

        public TriState getTriState(Set<String> selectedIds) {
            return computeTriState(allEpisodeIds, selectedIds);
        }

        public int getSelectedCount(Set<String> selectedIds) {
            return countSelected(allEpisodeIds, selectedIds);
        }
    }

    public static class ArtistNode {
        public final String name;
        public final List<AlbumNode> albums = new ArrayList<>();
        public final Set<String> allSongIds = new HashSet<>();
        public long totalSize = 0;

        public ArtistNode(String name) {
            this.name = name;
        }

        public TriState getTriState(Set<String> selectedIds) {
            return computeTriState(allSongIds, selectedIds);
        }

        public int getSelectedCount(Set<String> selectedIds) {
            return countSelected(allSongIds, selectedIds);
        }
    }

    public static class AlbumNode {
        public final String id;
        public final String name;
        public final String artistName;
        public final List<JellyfinMediaItem> songs = new ArrayList<>();
        public final Set<String> allSongIds = new HashSet<>();
        public long totalSize = 0;

        public AlbumNode(String id, String name, String artistName) {
            this.id = id;
            this.name = name;
            this.artistName = artistName;
        }

        public TriState getTriState(Set<String> selectedIds) {
            return computeTriState(allSongIds, selectedIds);
        }

        public int getSelectedCount(Set<String> selectedIds) {
            return countSelected(allSongIds, selectedIds);
        }
    }

    public final List<SeriesNode> seriesList = new ArrayList<>();
    public final List<ArtistNode> artistsList = new ArrayList<>();
    public final List<JellyfinMediaItem> moviesList = new ArrayList<>();
    public final Set<String> allItemIds = new HashSet<>();
    public long totalLibrarySize = 0;

    public static JellyfinSyncHierarchy buildTvHierarchy(List<JellyfinMediaItem> episodes) {
        JellyfinSyncHierarchy hierarchy = new JellyfinSyncHierarchy();
        if (episodes == null || episodes.isEmpty()) return hierarchy;

        Map<String, SeriesNode> seriesMap = new HashMap<>();
        Map<String, SeasonNode> seasonMap = new HashMap<>();

        for (JellyfinMediaItem ep : episodes) {
            if (ep.id == null || ep.id.isEmpty()) continue;
            hierarchy.allItemIds.add(ep.id);
            hierarchy.totalLibrarySize += ep.fileSize;

            String seriesKey = (ep.seriesId != null && !ep.seriesId.isEmpty()) ? ep.seriesId
                    : (ep.seriesName != null ? ep.seriesName : "Unknown Series");
            String seriesTitle = (ep.seriesName != null && !ep.seriesName.isEmpty()) ? ep.seriesName : "Unknown Series";

            SeriesNode sNode = seriesMap.get(seriesKey);
            if (sNode == null) {
                sNode = new SeriesNode(seriesKey, seriesTitle);
                seriesMap.put(seriesKey, sNode);
                hierarchy.seriesList.add(sNode);
            }
            sNode.allEpisodeIds.add(ep.id);
            sNode.totalSize += ep.fileSize;

            String seasonKey = seriesKey + "_S_" + ep.parentIndexNumber;
            String seasonTitle = (ep.seasonName != null && !ep.seasonName.isEmpty())
                    ? ep.seasonName
                    : (ep.parentIndexNumber > 0 ? "Season " + ep.parentIndexNumber : "Specials");

            SeasonNode seasonNode = seasonMap.get(seasonKey);
            if (seasonNode == null) {
                seasonNode = new SeasonNode(seasonKey, seasonTitle, ep.parentIndexNumber, seriesTitle);
                seasonMap.put(seasonKey, seasonNode);
                sNode.seasons.add(seasonNode);
            }
            seasonNode.episodes.add(ep);
            seasonNode.allEpisodeIds.add(ep.id);
            seasonNode.totalSize += ep.fileSize;
        }

        // Sort series by name
        Collections.sort(hierarchy.seriesList, new Comparator<SeriesNode>() {
            @Override
            public int compare(SeriesNode o1, SeriesNode o2) {
                return o1.name.compareToIgnoreCase(o2.name);
            }
        });

        // Sort seasons by seasonNumber, and episodes by indexNumber
        for (SeriesNode sNode : hierarchy.seriesList) {
            Collections.sort(sNode.seasons, new Comparator<SeasonNode>() {
                @Override
                public int compare(SeasonNode o1, SeasonNode o2) {
                    if (o1.seasonNumber == 0 && o2.seasonNumber > 0) return 1;
                    if (o2.seasonNumber == 0 && o1.seasonNumber > 0) return -1;
                    return Integer.compare(o1.seasonNumber, o2.seasonNumber);
                }
            });
            for (SeasonNode seaNode : sNode.seasons) {
                Collections.sort(seaNode.episodes, new Comparator<JellyfinMediaItem>() {
                    @Override
                    public int compare(JellyfinMediaItem o1, JellyfinMediaItem o2) {
                        return Integer.compare(o1.indexNumber, o2.indexNumber);
                    }
                });
            }
        }

        return hierarchy;
    }

    public static JellyfinSyncHierarchy buildMusicHierarchy(List<JellyfinMediaItem> songs) {
        JellyfinSyncHierarchy hierarchy = new JellyfinSyncHierarchy();
        if (songs == null || songs.isEmpty()) return hierarchy;

        Map<String, ArtistNode> artistMap = new HashMap<>();
        Map<String, AlbumNode> albumMap = new HashMap<>();

        for (JellyfinMediaItem song : songs) {
            if (song.id == null || song.id.isEmpty()) continue;
            hierarchy.allItemIds.add(song.id);
            hierarchy.totalLibrarySize += song.fileSize;

            String artistName = (song.albumArtist != null && !song.albumArtist.trim().isEmpty())
                    ? song.albumArtist
                    : (song.artist != null && !song.artist.trim().isEmpty() ? song.artist : "Unknown Artist");

            ArtistNode artNode = artistMap.get(artistName.toLowerCase());
            if (artNode == null) {
                artNode = new ArtistNode(artistName);
                artistMap.put(artistName.toLowerCase(), artNode);
                hierarchy.artistsList.add(artNode);
            }
            artNode.allSongIds.add(song.id);
            artNode.totalSize += song.fileSize;

            String albumTitle = (song.album != null && !song.album.trim().isEmpty()) ? song.album : "Unknown Album";
            String albumKey = artistName.toLowerCase() + "_" + albumTitle.toLowerCase();

            AlbumNode albNode = albumMap.get(albumKey);
            if (albNode == null) {
                String albumId = song.albumId != null ? song.albumId : albumKey;
                albNode = new AlbumNode(albumId, albumTitle, artistName);
                albumMap.put(albumKey, albNode);
                artNode.albums.add(albNode);
            }
            albNode.songs.add(song);
            albNode.allSongIds.add(song.id);
            albNode.totalSize += song.fileSize;
        }

        // Sort artists by name
        Collections.sort(hierarchy.artistsList, new Comparator<ArtistNode>() {
            @Override
            public int compare(ArtistNode o1, ArtistNode o2) {
                return o1.name.compareToIgnoreCase(o2.name);
            }
        });

        // Sort albums by name, and songs by indexNumber
        for (ArtistNode artNode : hierarchy.artistsList) {
            Collections.sort(artNode.albums, new Comparator<AlbumNode>() {
                @Override
                public int compare(AlbumNode o1, AlbumNode o2) {
                    return o1.name.compareToIgnoreCase(o2.name);
                }
            });
            for (AlbumNode albNode : artNode.albums) {
                Collections.sort(albNode.songs, new Comparator<JellyfinMediaItem>() {
                    @Override
                    public int compare(JellyfinMediaItem o1, JellyfinMediaItem o2) {
                        if (o1.parentIndexNumber != o2.parentIndexNumber) {
                            return Integer.compare(o1.parentIndexNumber, o2.parentIndexNumber);
                        }
                        return Integer.compare(o1.indexNumber, o2.indexNumber);
                    }
                });
            }
        }

        return hierarchy;
    }

    public static JellyfinSyncHierarchy buildMoviesHierarchy(List<JellyfinMediaItem> movies) {
        JellyfinSyncHierarchy hierarchy = new JellyfinSyncHierarchy();
        if (movies == null || movies.isEmpty()) return hierarchy;

        for (JellyfinMediaItem m : movies) {
            if (m.id == null || m.id.isEmpty()) continue;
            hierarchy.allItemIds.add(m.id);
            hierarchy.totalLibrarySize += m.fileSize;
            hierarchy.moviesList.add(m);
        }

        Collections.sort(hierarchy.moviesList, new Comparator<JellyfinMediaItem>() {
            @Override
            public int compare(JellyfinMediaItem o1, JellyfinMediaItem o2) {
                String n1 = o1.name != null ? o1.name : "";
                String n2 = o2.name != null ? o2.name : "";
                return n1.compareToIgnoreCase(n2);
            }
        });

        return hierarchy;
    }
}
