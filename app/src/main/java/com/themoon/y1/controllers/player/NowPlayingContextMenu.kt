package com.themoon.y1.controllers.player

import com.themoon.y1.MainActivity
import com.themoon.y1.cast.PlayOnMenu
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.models.SongItem
import java.io.File

/**
 * The menu of a held centre button on Now Playing: an action screen (a list that slides in, Back comes back to
 * Now Playing). Replicates iPod Classic context actions:
 * - Add/Remove from Favorites
 * - Add to On-The-Go playlist
 * - Add to Playlist
 * - Browse Album / Browse Artist
 * - Play On (the speaker list; with the speaker name while a speaker plays)
 * Only actions on the current song and Play On: the display options (Spectrum, Total Time) are in Settings.
 * A row closes the menu, then does its action (ActionScreens.choose), so Browse Album goes from Now Playing.
 */
object NowPlayingContextMenu {

    fun show(
        songFile: File,
        songItem: SongItem?,
        isFavorite: Boolean,
        onToggleFavorite: () -> Unit,
        onAddToOnTheGo: (file: File) -> Unit,
        onAddToPlaylist: (file: File) -> Unit,
        onBrowseAlbum: (albumName: String) -> Unit,
        onBrowseArtist: (artistName: String) -> Unit,
        localize: (String) -> String
    ) {
        val rows = ArrayList<ActionScreens.Action>()
        rows += ActionScreens.Action(if (isFavorite) localize("Remove from Favorites") else localize("Add to Favorites")) {
            onToggleFavorite()
        }
        rows += ActionScreens.Action(localize("Add to On-The-Go")) { onAddToOnTheGo(songFile) }
        rows += ActionScreens.Action(localize("Add to Playlist")) { onAddToPlaylist(songFile) }
        if (songItem != null) {
            if (songItem.album.isNotEmpty()) {
                rows += ActionScreens.Action(localize("Browse Album")) { onBrowseAlbum(songItem.album) }
            }
            val artist = if (songItem.albumArtist.isNotEmpty()) songItem.albumArtist else songItem.artist
            if (artist.isNotEmpty()) {
                rows += ActionScreens.Action(localize("Browse Artist")) { onBrowseArtist(artist) }
            }
        }
        // Play On: the speaker list of Main menu → Play On opens over this menu (Back comes back to it)
        val main = MainActivity.instance
        if (main != null) {
            rows += ActionScreens.Action(PlayOnMenu.rowLabel(main), opensList = true) { PlayOnMenu.open(main, fromNowPlaying = true) }
        }
        ActionScreens.open(ActionScreens.Spec(localize("Now Playing"), actions = rows))
    }
}
