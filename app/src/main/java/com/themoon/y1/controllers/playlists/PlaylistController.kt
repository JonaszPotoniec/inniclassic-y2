package com.themoon.y1.controllers.playlists

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.adapters.SongListAdapter
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.lists.Focus
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.managers.PlayHistoryManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.Notice
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayList

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
    private val musicRootFolder: File,
    private val isAudioFile: (File) -> Boolean,
    private val clickFeedback: () -> Unit,
    private val onPlaylistSelected: (m3uFile: File) -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val screen = ListScreen(rootView.findViewById(R.id.container_browser_items), listVirtualSongs, clickFeedback)

    var currentM3uFile: File? = null

    override fun onEnter(state: ScreenState) {
        when (state) {
            is ScreenState.Playlists -> buildPlaylistsUI()
            is ScreenState.M3uPlaylistSongs -> {
                currentM3uFile = state.m3uFile
                buildM3uSongsUI(state.m3uFile)
            }
            is ScreenState.SmartPlaylistSongs -> buildSmartPlaylistUI(state)
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
        screen.clear()
    }

    override fun onThemeChanged() {
        com.themoon.y1.navigation.NavigationManager.getInstance().currentState?.let { onEnter(it) }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta)

    override fun title(): String? = screen.title

    /** The smart playlists, then the M3U files of the Playlists folder (A to Z). Opens at the last opened playlist. */
    fun buildPlaylistsUI() {
        val playlistDir = StoragePaths.getPlaylistsDir()
        if (!playlistDir.exists()) {
            playlistDir.mkdirs()
        }
        val m3uFiles = (playlistDir.listFiles() ?: emptyArray()).filter { f ->
            val name = f.name.lowercase()
            f.isFile && (name.endsWith(".m3u") || name.endsWith(".m3u8"))
        }.sortedWith { f1, f2 -> f1.name.compareTo(f2.name, ignoreCase = true) }

        val rows = ArrayList<RowSpec>()
        // iPod smart playlists first
        for (kind in PlayHistoryManager.Kind.values()) {
            rows += RowSpec(smartPlaylistTitle(kind), onClick = {
                (activity as? com.themoon.y1.MainActivity)?.changeScreen(ScreenState.SmartPlaylistSongs(kind))
            })
        }
        for (m3u in m3uFiles) {
            rows += RowSpec(m3u.nameWithoutExtension, onClick = {
                currentM3uFile = m3u
                onPlaylistSelected(m3u)
            }, onHold = { showDeletePlaylistDialog(m3u) })
        }
        if (m3uFiles.isEmpty()) rows += RowSpec(localize("No .m3u files found in Y1_Playlists"), color = ThemeManager.getTextColorSecondary())
        screen.show(ListSpec(localize("Playlists"), rows, focus = Focus.LAST))
    }

    fun buildM3uSongsUI(m3uFile: File) {
        val songs = parseM3uFile(m3uFile)
        val main = activity as? com.themoon.y1.MainActivity
        main?.virtualSongList?.clear()
        for (song in songs) {
            main?.virtualSongList?.add(song.file)
        }
        screen.show(ListSpec(localize("Playlist") + ": " + m3uFile.nameWithoutExtension, adapter = SongListAdapter(songs),
            empty = localize("No Songs")))
    }

    private fun smartPlaylistTitle(kind: PlayHistoryManager.Kind): String = when (kind) {
        PlayHistoryManager.Kind.RECENTLY_PLAYED -> localize("Recently Played")
        PlayHistoryManager.Kind.MOST_PLAYED -> localize("Top 25 Most Played")
    }

    /**
     * Recently Played (newest first) or Top 25 Most Played (most plays first), from [PlayHistoryManager].
     * Rows are "title — artist". Not alphabetical, so no index bar. A song plays the list from that song.
     */
    fun buildSmartPlaylistUI(state: ScreenState.SmartPlaylistSongs) {
        // Empty until the list comes from the worker thread (a few ms)
        screen.show(ListSpec(smartPlaylistTitle(state.kind)))
        PlayHistoryManager.getInstance(activity).loadSongs(state.kind) { files ->
            val nav = com.themoon.y1.navigation.NavigationManager.getInstance()
            if (nav.currentState == state) showSmartPlaylistSongs(state, files)
        }
    }

    private fun showSmartPlaylistSongs(state: ScreenState.SmartPlaylistSongs, files: List<File>) {
        val main = activity as? com.themoon.y1.MainActivity
        val byPath = HashMap<String, SongItem>()
        for (song in com.themoon.y1.MainActivity.customLibrary) byPath[song.file.absolutePath] = song
        val rows = ArrayList<SongItem>()
        for (file in files) {
            val song = byPath[file.absolutePath]
            val title = song?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = song?.artist?.takeIf { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            rows.add(SongItem(file, if (artist != null) "$title — $artist" else title, song?.artist, song?.album))
        }
        main?.virtualSongList?.clear()
        main?.virtualSongList?.addAll(files)
        main?.virtualListSelectedIndex = 0
        screen.show(ListSpec(smartPlaylistTitle(state.kind), adapter = SongListAdapter(rows), empty = localize("No Songs")))
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
        ActionScreens.open(ActionScreens.Spec(
            localize("Delete Playlist"),
            listOf(m3uFile.nameWithoutExtension, localize("Are you sure you want to delete this playlist?")),
            listOf(ActionScreens.Action(localize("Delete")) {
                if (m3uFile.exists() && m3uFile.delete()) {
                    Notice.show(activity, localize("Playlist deleted."))
                    buildPlaylistsUI()
                } else {
                    Notice.error(activity, localize("Failed to delete playlist."))
                }
            })
        ))
    }

    fun showRemoveFromPlaylistDialog(audioFile: File) {
        val currentM3u = currentM3uFile ?: return
        ActionScreens.open(ActionScreens.Spec(
            localize("Remove from Playlist"),
            listOf(audioFile.nameWithoutExtension, localize("Do you want to remove this track from the playlist?")),
            listOf(ActionScreens.Action(localize("Remove")) {
                removeSongFromM3uFile(currentM3u, audioFile)
                buildM3uSongsUI(currentM3u)
                Notice.show(activity, localize("Track removed."))
            })
        ))
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

        // A list of targets: On-The-Go, a new playlist, then each playlist. Back cancels.
        val targets = ArrayList<ActionScreens.Action>()
        targets += ActionScreens.Action(localize("On-The-Go")) {
            addSongToOnTheGo(songFile)
        }
        targets += ActionScreens.Action(localize("Create New Playlist")) {
            var count = 1
            var newPlaylistFile: File
            do {
                newPlaylistFile = File(playlistDir, localize("Playlist ") + count + ".m3u8")
                count++
            } while (newPlaylistFile.exists())

            writeSongToM3uFile(newPlaylistFile, songFile, false)
            Notice.show(
                activity,
                localize("Created Playlist ") + (count - 1) + " " + localize("successfully!")
            )
        }
        for (targetM3u in playlistFiles) {
            val dotIdx = targetM3u.name.lastIndexOf(".")
            val cleanName = if (dotIdx > 0) targetM3u.name.substring(0, dotIdx) else targetM3u.name
            targets += ActionScreens.Action(cleanName) {
                writeSongToM3uFile(targetM3u, songFile, true)
                Notice.show(activity, localize("Added to playlist successfully!"))
            }
        }
        ActionScreens.open(ActionScreens.Spec(localize("Add to Playlist"), actions = targets))
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
        Notice.show(activity, localize("Added to On-The-Go."))
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
}
