package com.themoon.y1.controllers.player

import android.app.Activity
import com.themoon.y1.models.SongItem
import com.themoon.y1.views.WheelDialog
import java.io.File

/**
 * Context menu displayed on long pressing center or play/pause button in Now Playing screen.
 * Replicates iPod Classic context actions:
 * - Add/Remove from Favorites
 * - Add to On-The-Go playlist
 * - Browse Album / Browse Artist
 * - Toggle Audio Visualizer
 */
object NowPlayingContextMenu {

    fun show(
        activity: Activity,
        songFile: File,
        songItem: SongItem?,
        isFavorite: Boolean,
        onToggleFavorite: () -> Unit,
        onAddToOnTheGo: (file: File) -> Unit,
        onBrowseAlbum: (albumName: String) -> Unit,
        onBrowseArtist: (artistName: String) -> Unit,
        onToggleVisualizer: () -> Unit,
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

            addButton(
                text = localize("Toggle Visualizer"),
                icon = null
            ) {
                onToggleVisualizer()
            }

            negativeButton(localize("Cancel"))
        }
    }
}
