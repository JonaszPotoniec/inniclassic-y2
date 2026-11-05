package com.themoon.y1.navigation

import com.themoon.y1.managers.PlayHistoryManager
import java.io.File

/**
 * Top-level screen states representing currently active application views.
 *
 * A state is also the route key of a back-stack entry ([NavigationManager]): it says which list shows and has
 * all that its controller needs to build that list again when Back comes back to it.
 */
sealed interface ScreenState {
    data object MainMenu : ScreenState
    /** A Music list ([mode]); [audiobooks] = the same lists in the audiobook library. */
    data class Browser(val mode: BrowserMode, val audiobooks: Boolean = false) : ScreenState
    data object Player : ScreenState
    data class Settings(val subMode: SettingsSubMode = SettingsSubMode.Root) : ScreenState
    data object Bluetooth : ScreenState
    data object Wifi : ScreenState
    data class WifiKeyboard(val target: KeyboardTarget) : ScreenState
    data object Brightness : ScreenState
    data object Storage : ScreenState
    data object WebServer : ScreenState
    data object MusicQuiz : ScreenState
    data class Videos(val mode: VideoMode = VideoMode.Root) : ScreenState
    data class VideoPlayer(val videoFile: File) : ScreenState
    data object CoverFlow : ScreenState
    data object Playlists : ScreenState
    data class M3uPlaylistSongs(val m3uFile: File) : ScreenState
    /** iPod smart playlist in Music -> Playlists: Recently Played or Top 25 Most Played. */
    data class SmartPlaylistSongs(val kind: PlayHistoryManager.Kind) : ScreenState
    data object Podcasts : ScreenState
    data class PodcastEpisodes(val channelTitle: String, val rssUrl: String) : ScreenState
    data object PodcastManage : ScreenState
    data object Equalizer : ScreenState
    /** The FM tuner. It opens directly from the main menu; Back (Menu) goes to [FmRadioMenu], as on the iPod nano. */
    data object FmRadio : ScreenState
    /** The Radio menu: tuner, power, saved channels, scan. */
    data object FmRadioMenu : ScreenState
    /** The list of the saved channels. */
    data object FmRadioSaved : ScreenState
    /** Jellyfin sync: the settings ([JellyfinLevel.Root]) and the lists of a library. Each level is a back-stack entry. */
    data class Jellyfin(val level: JellyfinLevel = JellyfinLevel.Root) : ScreenState
}

/**
 * The levels of Jellyfin sync. A library list is found again by its place in the list above it (the library
 * items stay in memory while its levels are on the back stack).
 */
sealed interface JellyfinLevel {
    /** Server, libraries and sync settings. */
    data object Root : JellyfinLevel
    /** The sync mode of a library (Sync All, Manual, Off). */
    data class Library(val libraryId: String) : JellyfinLevel
    /** The items of a library to select: series, artists, authors or movies. */
    data class Browse(val libraryId: String) : JellyfinLevel
    /** A series, artist or author ([group]: its place in [Browse]): seasons, albums or books. */
    data class Group(val libraryId: String, val group: Int) : JellyfinLevel
    /** A season, album or book ([item]: its place in [Group]): episodes, songs or chapters. */
    data class Items(val libraryId: String, val group: Int, val item: Int) : JellyfinLevel
}

/** A level of the folder browser: the file tree of Folders, or of the background picker of Settings. */
val ScreenState.isFolderLevel: Boolean
    get() = this is ScreenState.Browser && mode is BrowserMode.Folder

/**
 * Browser navigation modes for music library, file tree, playlists, and podcasts.
 */
sealed interface BrowserMode {
    data object Root : BrowserMode
    /** A folder; [pickImage] = the background picker of Settings (images, no audio). */
    data class Folder(val dir: File, val pickImage: Boolean = false) : BrowserMode
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
    /**
     * A song list: [type] and [value] are the query of BrowserController.buildVirtualSongs (ALL, ALBUM, ARTIST, GENRE,
     * SEARCH...), [albumArtist] tells two albums with the same title apart, and [artist], [byTrackArtist] and [genre]
     * are the filters of the list that opened it.
     */
    data class Songs(
        val type: String,
        val value: String = "",
        val albumArtist: String = "",
        val artist: String = "",
        val byTrackArtist: Boolean = false,
        val genre: String = ""
    ) : BrowserMode
    data object Favorites : BrowserMode
    data object RecentlyAdded : BrowserMode
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
    /** A group of the top level, by its English title (SettingsController.groups). */
    data class Group(val title: String) : SettingsSubMode
    /** The screen of a Settings row, by its id (for example "Theme"); [arg] for a screen of one value ("DateTime", "Year"). */
    data class Page(val id: String, val arg: String = "") : SettingsSubMode
    data object DateTime : SettingsSubMode
    data object Equalizer5Band : SettingsSubMode
    data object Equalizer10Band : SettingsSubMode
    data object MenuReorder : SettingsSubMode
    data class Jellyfin(val depth: Int = 0) : SettingsSubMode
    data object Radio : SettingsSubMode
    data object RadioSavedChannels : SettingsSubMode
    /** Restart and Power Off (Settings → General → Power, or a long press of the Power button) */
    data object Power : SettingsSubMode
    /** A screen in place of an Android dialog (controllers/settings/ActionScreen.kt), by the key of its content */
    data class Action(val key: Int) : SettingsSubMode
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
