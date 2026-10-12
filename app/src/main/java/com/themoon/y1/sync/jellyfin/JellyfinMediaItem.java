package com.themoon.y1.sync.jellyfin;

/**
 * Unified data transfer object for all Jellyfin media items (music tracks, movies, TV episodes).
 */
public class JellyfinMediaItem {
    public String id;
    public String name;              // track/movie/episode title
    public String album;             // null for movies
    public String artist;            // or director/artist for movies
    public String albumArtist;
    public String composer;
    public String year;
    public String genre;
    public int indexNumber;          // track/episode number
    public int parentIndexNumber;    // disc/season number
    public String container;         // flac, mp3, mp4, mkv...
    public long fileSize;
    public String albumId;
    public String albumPrimaryImageTag;
    public String collectionType;    // music, movies, tvshows
    public String libraryId;
    public String seriesName;        // for TV episodes
    public String serverDateModified;

    public JellyfinMediaItem() {}
}
