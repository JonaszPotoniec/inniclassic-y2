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
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutStorage: LinearLayout = rootView.findViewById(R.id.layout_storage_mode)
    private val containerStorageItems: LinearLayout = rootView.findViewById(R.id.container_storage_items)
    private val tvStorageHeader: TextView? = rootView.findViewById(R.id.tv_storage_header)

    override fun onEnter(state: ScreenState) {
        buildStorageUI()
    }

    override fun onExit() {
        layoutStorage.visibility = View.GONE
    }

    override fun onLibraryLoaded() {
        buildStorageUI()
    }

    override fun onThemeChanged() {
        buildStorageUI()
    }

    fun buildStorageUI() {
        containerStorageItems.removeAllViews()
        layoutStorage.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
        tvStorageHeader?.text = localize("About")
        tvStorageHeader?.setTextColor(ThemeManager.getTextColorPrimary())

        val rowName = createSettingRow(localize("Name"), "iPod")
        rowName.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowName)

        val songs = songsProvider()
        val rowSongs = createSettingRow(localize("Songs"), songs.size.toString())
        rowSongs.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowSongs)

        val videos = videosProvider()
        val rowVideos = createSettingRow(localize("Videos"), videos.size.toString())
        rowVideos.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowVideos)

        val uniqueAlbums = HashSet<String>()
        val uniqueArtists = HashSet<String>()
        for (song in songs) {
            if (song.album.isNotBlank()) uniqueAlbums.add(AlbumKey.of(song))
            if (song.albumArtist.isNotBlank()) uniqueArtists.add(song.albumArtist)
        }

        val rowAlbums = createSettingRow(localize("Albums"), uniqueAlbums.size.toString())
        rowAlbums.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowAlbums)

        val rowArtists = createSettingRow(localize("Artists"), uniqueArtists.size.toString())
        rowArtists.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowArtists)

        try {
            val statInternal = StatFs(StoragePaths.getPrimaryRoot().absolutePath)
            val blockSize = statInternal.blockSize.toLong()
            val totalInternal = statInternal.blockCount.toLong() * blockSize
            val freeInternal = statInternal.availableBlocks.toLong() * blockSize

            val rowIntCap = createSettingRow(localize("Internal Capacity"), formatSize(totalInternal))
            rowIntCap.setOnClickListener { clickFeedback() }
            containerStorageItems.addView(rowIntCap)

            val rowIntAvail = createSettingRow(localize("Internal Available"), formatSize(freeInternal))
            rowIntAvail.setOnClickListener { clickFeedback() }
            containerStorageItems.addView(rowIntAvail)
        } catch (e: Exception) {
            val rowIntCap = createSettingRow(localize("Internal Capacity"), "0 MB")
            rowIntCap.setOnClickListener { clickFeedback() }
            containerStorageItems.addView(rowIntCap)
        }

        if (StoragePaths.hasSecondaryStorage()) {
            try {
                val statSd = StatFs(StoragePaths.SECONDARY_PATH)
                val sdBlockSize = statSd.blockSize.toLong()
                val totalSd = statSd.blockCount.toLong() * sdBlockSize
                val freeSd = statSd.availableBlocks.toLong() * sdBlockSize

                val rowSdCap = createSettingRow(localize("SD Card Capacity"), formatSize(totalSd))
                rowSdCap.setOnClickListener { clickFeedback() }
                containerStorageItems.addView(rowSdCap)

                val rowSdAvail = createSettingRow(localize("SD Card Available"), formatSize(freeSd))
                rowSdAvail.setOnClickListener { clickFeedback() }
                containerStorageItems.addView(rowSdAvail)
            } catch (e: Exception) {
                val rowSd = createSettingRow(localize("SD Card"), localize("Not Inserted"))
                rowSd.setOnClickListener { clickFeedback() }
                containerStorageItems.addView(rowSd)
            }
        } else {
            val rowSd = createSettingRow(localize("SD Card"), localize("Not Inserted"))
            rowSd.setOnClickListener { clickFeedback() }
            containerStorageItems.addView(rowSd)
        }

        var versionName = ""
        try {
            versionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (ignored: Exception) {}

        val rowVer = createSettingRow(localize("Software Version"), versionName)
        rowVer.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowVer)

        val rowModel = createSettingRow(localize("Model"), Build.MODEL)
        rowModel.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowModel)

        val rowFormat = createSettingRow(localize("Format"), "FAT32")
        rowFormat.setOnClickListener { clickFeedback() }
        containerStorageItems.addView(rowFormat)

        if (containerStorageItems.childCount > 0) {
            containerStorageItems.post {
                containerStorageItems.getChildAt(0).requestFocus()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onNavigateBack()
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
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
