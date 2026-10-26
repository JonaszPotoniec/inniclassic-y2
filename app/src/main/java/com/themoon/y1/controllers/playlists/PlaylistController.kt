package com.themoon.y1.controllers.playlists

import android.app.Activity
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.AlphabetIndexBar
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.WheelDialog
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayList
import java.util.Collections

/**
 * Controller for M3U playlist browsing, song listing, creation, and editing:
 * - Lists M3U / M3U8 playlists in `Playlists/` folder
 * - Parses playlist track sequences without losing custom ordering
 * - Displays smart popup dialogs for "Add to Playlist" and "Delete Playlist"
 * - Handles track deletion from individual M3U playlists
 */
class PlaylistController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: ListView?,
    private val hzIndexScroll: android.widget.HorizontalScrollView?,
    private val layoutIndexContainer: LinearLayout?,
    private val musicRootFolder: File,
    private val isAudioFile: (File) -> Boolean,
    private val createListButtonWithIcon: (icon: String, text: String, color: Int?) -> View,
    private val clickFeedback: () -> Unit,
    private val onPlaylistSelected: (m3uFile: File) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val containerBrowserItems: LinearLayout = rootView.findViewById(R.id.container_browser_items)
    private val scrollViewBrowser: View? = containerBrowserItems.parent as? View
    private val tvBrowserPath: TextView = rootView.findViewById(R.id.tv_browser_path)

    private val alphabetIndexBarHelper = if (hzIndexScroll != null && layoutIndexContainer != null) {
        AlphabetIndexBar(
            activity,
            hzIndexScroll,
            layoutIndexContainer,
            listVirtualSongs,
            clickFeedback
        )
    } else null

    var currentM3uFile: File? = null
    var lastPlaylistFocusName: String = ""

    override fun onEnter(state: ScreenState) {
        when (state) {
            is ScreenState.Playlists -> buildPlaylistsUI()
            is ScreenState.M3uPlaylistSongs -> {
                currentM3uFile = state.m3uFile
                buildM3uSongsUI(state.m3uFile)
            }
            is ScreenState.Browser -> {
                when (state.mode) {
                    is BrowserMode.Playlists -> buildPlaylistsUI()
                    is BrowserMode.M3uSongs -> {
                        currentM3uFile = state.mode.m3uFile
                        buildM3uSongsUI(state.mode.m3uFile)
                    }
                    else -> buildPlaylistsUI()
                }
            }
            else -> buildPlaylistsUI()
        }
    }

    override fun onExit() {
        containerBrowserItems.removeAllViews()
    }

    override fun onThemeChanged() {
        com.themoon.y1.navigation.NavigationManager.getInstance().currentState?.let { onEnter(it) }
    }

    fun buildPlaylistsUI() {
        scrollViewBrowser?.visibility = View.VISIBLE
        listVirtualSongs?.visibility = View.GONE
        containerBrowserItems.removeAllViews()
        tvBrowserPath.text = localize("Playlists")

        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }

        val files = playlistDir.listFiles()
        val m3uFiles = ArrayList<File>()
        if (files != null) {
            for (f in files) {
                val name = f.name.lowercase()
                if (f.isFile && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) {
                    m3uFiles.add(f)
                }
            }
        }

        Collections.sort(m3uFiles) { f1: File, f2: File ->
            f1.name.compareTo(f2.name, ignoreCase = true)
        }

        if (m3uFiles.isEmpty()) {
            val btnEmpty = createListButtonWithIcon(
                "\uE05F",
                localize("No .m3u files found in Y1_Playlists"),
                ThemeManager.getTextColorSecondary()
            )
            containerBrowserItems.addView(btnEmpty)
        } else {
            for (m3u in m3uFiles) {
                val dotIdx = m3u.name.lastIndexOf(".")
                val cleanName = if (dotIdx > 0) m3u.name.substring(0, dotIdx) else m3u.name
                val btn = createListButtonWithIcon("\uE05F", cleanName, null)

                btn.setOnClickListener {
                    clickFeedback()
                    currentM3uFile = m3u
                    lastPlaylistFocusName = cleanName
                    onPlaylistSelected(m3u)
                }

                btn.isLongClickable = true
                btn.setOnLongClickListener {
                    clickFeedback()
                    showDeletePlaylistDialog(m3u)
                    true
                }

                containerBrowserItems.addView(btn)
            }
        }

        val sv = containerBrowserItems.parent as? ScrollView
        sv?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= 16) {
                    sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } else {
                    sv.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }
                if (containerBrowserItems.childCount > 0) {
                    var targetIndex = 0
                    if (lastPlaylistFocusName.isNotEmpty()) {
                        for (i in 0 until containerBrowserItems.childCount) {
                            val child = containerBrowserItems.getChildAt(i)
                            var itemText = ""
                            if (child is Button) {
                                itemText = child.text.toString()
                            } else if (child is LinearLayout && child.childCount > 1 && child.getChildAt(1) is TextView) {
                                itemText = (child.getChildAt(1) as TextView).text.toString()
                            }
                            if (itemText == lastPlaylistFocusName) {
                                targetIndex = i
                                break
                            }
                        }
                    }
                    val target = containerBrowserItems.getChildAt(targetIndex)
                    target?.requestFocus()
                    if (sv != null && target != null) {
                        val offset = (sv.height / 2) - (target.height / 2)
                        val targetY = (target.top - offset).coerceAtLeast(0)
                        sv.scrollTo(0, targetY)
                    }
                }
            }
        })
    }

    fun buildM3uSongsUI(m3uFile: File) {
        scrollViewBrowser?.visibility = View.GONE
        listVirtualSongs?.visibility = View.VISIBLE

        val dotIdx = m3uFile.name.lastIndexOf(".")
        val playlistName = if (dotIdx > 0) m3uFile.name.substring(0, dotIdx) else m3uFile.name
        tvBrowserPath.text = localize("Playlist") + ": " + playlistName

        val songs = parseM3uFile(m3uFile)
        val main = activity as? com.themoon.y1.MainActivity
        main?.virtualSongList?.clear()
        for (song in songs) {
            main?.virtualSongList?.add(song.file)
        }
        val titles = ArrayList<String>()
        for (song in songs) {
            titles.add(song.title)
        }

        if (songs.isEmpty()) {
            Toast.makeText(activity, localize("No valid tracks found in this playlist."), Toast.LENGTH_SHORT).show()
        }

        val adapter = SongListAdapter(songs)
        listVirtualSongs?.adapter = adapter

        listVirtualSongs?.post {
            if (listVirtualSongs.childCount > 0) {
                listVirtualSongs.getChildAt(0).requestFocus()
            }
        }

        alphabetIndexBarHelper?.build(titles)
    }

    fun parseM3uFile(m3uFile: File): List<SongItem> {
        val songs = ArrayList<SongItem>()
        if (!m3uFile.exists()) return songs

        try {
            val br = BufferedReader(InputStreamReader(FileInputStream(m3uFile), "UTF-8"))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val raw = line?.trim() ?: continue
                if (raw.isEmpty() || raw.startsWith("#")) continue

                val normalized = raw.replace("\\", "/")
                var audioFile = File(normalized)
                if (!audioFile.isAbsolute) {
                    audioFile = File(musicRootFolder, normalized)
                }

                if (audioFile.exists() && isAudioFile(audioFile)) {
                    var title = audioFile.name
                    val dotIdx = title.lastIndexOf(".")
                    if (dotIdx > 0) {
                        title = title.substring(0, dotIdx)
                    }
                    songs.add(SongItem(audioFile, title, "M3U Playlist", ""))
                }
            }
            br.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songs
    }

    fun showDeletePlaylistDialog(m3uFile: File) {
        WheelDialog.show(activity) {
            title = localize("Delete Playlist")
            message = localize("Are you sure you want to delete this playlist?")
            positiveButton(localize("Delete"), isDestructive = true) {
                if (m3uFile.exists() && m3uFile.delete()) {
                    Toast.makeText(activity, localize("Playlist deleted."), Toast.LENGTH_SHORT).show()
                    buildPlaylistsUI()
                } else {
                    Toast.makeText(activity, localize("Failed to delete playlist."), Toast.LENGTH_SHORT).show()
                }
            }
            negativeButton(localize("Cancel"))
        }
    }

    fun showRemoveFromPlaylistDialog(audioFile: File) {
        val currentM3u = currentM3uFile ?: return
        WheelDialog.show(activity) {
            title = localize("Remove from Playlist")
            message = localize("Do you want to remove this track from the playlist?")
            positiveButton(localize("Remove"), isDestructive = true) {
                removeSongFromM3uFile(currentM3u, audioFile)
                buildM3uSongsUI(currentM3u)
                Toast.makeText(activity, localize("Track removed."), Toast.LENGTH_SHORT).show()
            }
            negativeButton(localize("Cancel"))
        }
    }

    fun showAddToPlaylistDialog(songFile: File) {
        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }

        val onTheGoFile = File(playlistDir, "On-The-Go.m3u8")
        if (!onTheGoFile.exists()) {
            try {
                val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(onTheGoFile), "UTF-8"))
                bw.write("#EXTM3U\n")
                bw.close()
            } catch (ignored: Exception) {}
        }

        val files = playlistDir.listFiles()
        val playlistFiles = ArrayList<File>()
        if (files != null) {
            for (f in files) {
                val name = f.name.lowercase()
                if (f.isFile && (name.endsWith(".m3u") || name.endsWith(".m3u8")) && f.name != "On-The-Go.m3u8") {
                    playlistFiles.add(f)
                }
            }
        }

        WheelDialog.show(activity) {
            title = localize("ADD TO PLAYLIST")
            addButton(localize("On-The-Go"), icon = null) {
                addSongToOnTheGo(songFile)
            }
            addButton(localize("Create New Playlist"), icon = "\uE145") {
                var count = 1
                var newPlaylistFile: File
                do {
                    newPlaylistFile = File(playlistDir, localize("Playlist ") + count + ".m3u8")
                    count++
                } while (newPlaylistFile.exists())

                writeSongToM3uFile(newPlaylistFile, songFile, false)
                Toast.makeText(
                    activity,
                    localize("Created Playlist ") + (count - 1) + " " + localize("successfully!"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            for (targetM3u in playlistFiles) {
                val dotIdx = targetM3u.name.lastIndexOf(".")
                val cleanName = if (dotIdx > 0) targetM3u.name.substring(0, dotIdx) else targetM3u.name
                addButton(cleanName, icon = "\uE05F") {
                    writeSongToM3uFile(targetM3u, songFile, true)
                    Toast.makeText(activity, localize("Added to playlist successfully!"), Toast.LENGTH_SHORT).show()
                }
            }
            negativeButton(localize("Cancel"))
        }
    }

    fun addSongToOnTheGo(songFile: File) {
        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }
        val onTheGoFile = File(playlistDir, "On-The-Go.m3u8")
        if (!onTheGoFile.exists()) {
            try {
                val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(onTheGoFile), "UTF-8"))
                bw.write("#EXTM3U\n")
                bw.close()
            } catch (ignored: Exception) {}
        }
        writeSongToM3uFile(onTheGoFile, songFile, true)
        Toast.makeText(activity, localize("Added to On-The-Go."), Toast.LENGTH_SHORT).show()
    }

    fun writeSongToM3uFile(m3uFile: File, songFile: File, append: Boolean) {
        try {
            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(m3uFile, append), "UTF-8"))
            if (!append) {
                bw.write("#EXTM3U\n")
            }
            bw.write(songFile.absolutePath + "\n")
            bw.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeSongFromM3uFile(m3uFile: File?, songFile: File) {
        if (m3uFile == null || !m3uFile.exists()) return
        try {
            val lines = ArrayList<String>()
            val br = BufferedReader(InputStreamReader(FileInputStream(m3uFile), "UTF-8"))
            var line: String?
            var isRemoved = false

            while (br.readLine().also { line = it } != null) {
                val cur = line ?: continue
                val cleanLine = cur.replace("\\", "/").trim()

                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) {
                    lines.add(cur)
                    continue
                }

                if (!isRemoved && cleanLine.endsWith(songFile.name)) {
                    isRemoved = true
                    continue
                }

                lines.add(cur)
            }
            br.close()

            val bw = BufferedWriter(OutputStreamWriter(FileOutputStream(m3uFile, false), "UTF-8"))
            for (l in lines) {
                bw.write(l + "\n")
            }
            bw.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        val list = listVirtualSongs
        if (list != null && list.visibility == View.VISIBLE) {
            val totalCount = list.count
            if (totalCount <= 0) return true
            val curFocused = list.findFocus()
            val currentIdx = if (curFocused != null) list.getPositionForView(curFocused) else 0
            val targetPos = currentIdx + (if (delta < 0) -1 else 1)
            if (targetPos in 0 until totalCount) {
                if (com.themoon.y1.views.ListEdges.focusRow(list, targetPos)) {
                    clickFeedback()
                    return true
                }
                list.setSelection(targetPos)
                val visiblePos = targetPos - list.firstVisiblePosition
                if (visiblePos in 0 until list.childCount) {
                    list.getChildAt(visiblePos)?.requestFocus()
                }
                clickFeedback()
            }
            return true
        }
        return false
    }
}
