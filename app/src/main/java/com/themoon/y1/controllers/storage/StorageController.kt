package com.themoon.y1.controllers.storage

import android.app.Activity
import android.os.Build
import android.os.StatFs
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import java.io.File
import java.util.HashSet
import java.util.Locale

/**
 * Controller for storage and device info screen ("About"):
 * Song, video, album, artist counts, internal and SD card storage stats,
 * and software version.
 */
class StorageController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val songsProvider: () -> List<SongItem>,
    private val videosProvider: () -> List<File>,
    clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutStorage: LinearLayout = rootView.findViewById(R.id.layout_storage_mode)
    private val tvStorageHeader: TextView? = rootView.findViewById(R.id.tv_storage_header)
    private val screen = ListScreen(rootView.findViewById(R.id.container_storage_items), clickFeedback = clickFeedback)

    override fun onEnter(state: ScreenState) {
        showColours()
        screen.show(spec())
    }

    override fun onExit() {
        layoutStorage.visibility = View.GONE
        screen.clear()
    }

    override fun onLibraryLoaded() {
        screen.update(spec())
    }

    override fun onThemeChanged() {
        showColours()
        screen.update(spec())
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta)

    override fun title(): String? = screen.title

    private fun showColours() {
        layoutStorage.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        tvStorageHeader?.text = localize("About")
        tvStorageHeader?.setTextColor(ThemeManager.getTextColorPrimary())
    }

    /** One row for each value; the centre button only gives the click feedback. */
    private fun spec(): ListSpec {
        val rows = ArrayList<RowSpec>()
        fun row(title: String, value: String) {
            rows += RowSpec(localize(title), RowLook.VALUE, value = value, key = title, onClick = {})
        }

        row("Name", "Classy")
        val songs = songsProvider()
        row("Songs", songs.size.toString())
        row("Videos", videosProvider().size.toString())

        val uniqueAlbums = HashSet<String>()
        val uniqueArtists = HashSet<String>()
        for (song in songs) {
            if (song.album.isNotBlank()) uniqueAlbums.add(AlbumKey.of(song))
            if (song.albumArtist.isNotBlank()) uniqueArtists.add(song.albumArtist)
        }
        row("Albums", uniqueAlbums.size.toString())
        row("Artists", uniqueArtists.size.toString())

        try {
            val statInternal = StatFs(StoragePaths.getPrimaryRoot().absolutePath)
            val blockSize = statInternal.blockSize.toLong()
            row("Internal Capacity", formatSize(statInternal.blockCount.toLong() * blockSize))
            row("Internal Available", formatSize(statInternal.availableBlocks.toLong() * blockSize))
        } catch (e: Exception) {
            row("Internal Capacity", "0 MB")
        }

        val statSd = if (StoragePaths.hasSecondaryStorage()) try { StatFs(StoragePaths.SECONDARY_PATH) } catch (e: Exception) { null } else null
        if (statSd != null) {
            val sdBlockSize = statSd.blockSize.toLong()
            row("SD Card Capacity", formatSize(statSd.blockCount.toLong() * sdBlockSize))
            row("SD Card Available", formatSize(statSd.availableBlocks.toLong() * sdBlockSize))
        } else {
            row("SD Card", localize("Not Inserted"))
        }

        var versionName = ""
        try {
            versionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (ignored: Exception) {}
        row("Software Version", versionName)
        row("Model", Build.MODEL)
        row("Format", "FAT32")
        return ListSpec(localize("About"), rows)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = bytes / (1024 * 1024)
        return "$mb MB"
    }
}
