package com.themoon.y1.controllers.player

import android.app.Activity
import com.themoon.y1.models.SongItem
import com.themoon.y1.views.WheelDialog
import java.io.File

/**
 * Context menu displayed on long pressing the center button in Now Playing screen.
 * Replicates iPod Classic context actions:
 * - Add/Remove from Favorites
 * - Add to On-The-Go playlist
 * - Add to Playlist
 * - Browse Album / Browse Artist
 * Only actions on the current song: the display options (Spectrum, Total Time) are in Settings.
 */
object NowPlayingContextMenu {

    fun show(
        activity: Activity,
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
        WheelDialog.show(activity) {
            title = localize("Now Playing")

            addButton(
                text = if (isFavorite) localize("Remove from Favorites") else localize("Add to Favorites"),
                icon = null
            ) {
                onToggleFavorite()
            }

            addButton(
                text = localize("Add to On-The-Go"),
                icon = null
            ) {
                onAddToOnTheGo(songFile)
            }

            addButton(
                text = localize("Add to Playlist"),
                icon = null
            ) {
                onAddToPlaylist(songFile)
            }

            if (songItem != null) {
                if (songItem.album.isNotEmpty()) {
                    addButton(
                        text = localize("Browse Album"),
                        icon = null
                    ) {
                        onBrowseAlbum(songItem.album)
                    }
                }

                val artist = if (songItem.albumArtist.isNotEmpty()) songItem.albumArtist else songItem.artist
                if (artist.isNotEmpty()) {
                    addButton(
                        text = localize("Browse Artist"),
                        icon = null
                    ) {
                        onBrowseArtist(artist)
                    }
                }
            }

            negativeButton(localize("Cancel"))
        }
    }
}
