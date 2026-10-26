package com.themoon.y1.navigation

import java.io.File

/**
 * Top-level screen states representing currently active application views.
 */
sealed interface ScreenState {
    data object MainMenu : ScreenState
    data class Browser(val mode: BrowserMode) : ScreenState
    data object Player : ScreenState
    data class Settings(val subMode: SettingsSubMode = SettingsSubMode.Root) : ScreenState
    data object Bluetooth : ScreenState
    data object Wifi : ScreenState
    data class WifiKeyboard(val ssid: String) : ScreenState
    data object Brightness : ScreenState
    data object Storage : ScreenState
    data object WebServer : ScreenState
    data object MusicQuiz : ScreenState
    data class Videos(val mode: VideoMode = VideoMode.Root) : ScreenState
    data class VideoPlayer(val videoFile: File) : ScreenState
    data object CoverFlow : ScreenState
    data object Playlists : ScreenState
    data class M3uPlaylistSongs(val m3uFile: File) : ScreenState
    data object Podcasts : ScreenState
    data class PodcastEpisodes(val channelTitle: String, val rssUrl: String) : ScreenState
    data object PodcastManage : ScreenState
    data object Equalizer : ScreenState
    data object FmRadio : ScreenState
    data object Jellyfin : ScreenState
}

/**
 * Browser navigation modes for music library, file tree, playlists, and podcasts.
 */
sealed interface BrowserMode {
    data object Root : BrowserMode
    data class Folder(val dir: File) : BrowserMode
    data object Artists : BrowserMode
    data object TrackArtists : BrowserMode
    data class Albums(val artistFilter: String? = null, val byTrackArtist: Boolean = false) : BrowserMode
    data object Compilations : BrowserMode
    data object Genres : BrowserMode
    data class GenreArtists(val genre: String) : BrowserMode
    data class GenreAlbums(val genre: String, val artist: String? = null) : BrowserMode
    data object Composers : BrowserMode
    data object Years : BrowserMode
    data object VirtualSongs : BrowserMode
    data object Favorites : BrowserMode
    data object RecentlyAdded : BrowserMode
    data object RecentlyPlayed : BrowserMode
    data object Audiobooks : BrowserMode
    data object Playlists : BrowserMode
    data class M3uSongs(val m3uFile: File) : BrowserMode
    data object CoverFlow : BrowserMode
    data object PodcastChannels : BrowserMode
    data class PodcastEpisodes(val channelTitle: String, val rssUrl: String) : BrowserMode
    data object PodcastManage : BrowserMode
}

/**
 * Sub-modes within settings navigation (EQ studio, Jellyfin, reordering, date/time).
 */
sealed interface SettingsSubMode {
    data object Root : SettingsSubMode
    data object DateTime : SettingsSubMode
    data object Equalizer5Band : SettingsSubMode
    data object Equalizer10Band : SettingsSubMode
    data object MenuReorder : SettingsSubMode
    data class Jellyfin(val depth: Int = 0) : SettingsSubMode
    data object Radio : SettingsSubMode
    data object RadioSavedChannels : SettingsSubMode
}

/**
 * Sub-modes within Video library navigation.
 */
sealed interface VideoMode {
    data object Root : VideoMode
    data object Movies : VideoMode
    data object TvShows : VideoMode
    data class TvSeasons(val seriesName: String) : VideoMode
    data class TvEpisodes(val seriesName: String, val seasonNumber: Int) : VideoMode
    data class Directory(val directory: File) : VideoMode
}
