package com.themoon.y1.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.lists.ArtRow
import com.themoon.y1.lists.RowTexts
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.ListItemViewFactory
import java.io.File
import java.io.FileInputStream
import java.util.Collections

class CategoryListAdapter(
    private val items: List<String>,
    private val type: String,
    private val isAudiobook: Boolean = MainActivity.instance?.let {
        it.isAudiobookLibraryMode || (it.isBrowserControllerInitialized() && it.browserController.isAudiobookLibraryMode)
    } ?: false,
    /** Album artist of each album row in [items], so albums with the same title stay apart. */
    private val albumArtists: List<String>? = null
) : BaseAdapter(), RowTexts {

    private var albumSongCounts: MutableMap<String, Int>? = null
    private var albumFilesMap: MutableMap<String, MutableList<File>>? = null
    private var albumArtistsMap: MutableMap<String, String>? = null
    private var cachedAllSongsCount = -1
    private val showAlbumArtist = AlbumKey.byArtist && type == "ALBUM" && MainActivity.instance?.browserController?.categoryArtistFilter.isNullOrEmpty()

    init {
        if (coverCache == null) {
            coverCache = LruCache(50)
        }

        if (type == "ALBUM" || type == "GENRE_ALBUM") {
            val counts = HashMap<String, Int>()
            val filesMap = HashMap<String, MutableList<File>>()
            val artists = HashMap<String, String>()
            val bc = MainActivity.instance?.browserController
            val genreFilter = bc?.categoryGenreFilter ?: ""
            val artistFilter = bc?.categoryArtistFilter ?: ""
            val isTrackArtist = bc?.categoryArtistFilterIsTrackArtist == true
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            // The same filters as BrowserController.buildVirtualCategories: Compilations, and Artists without them.
            val compilationsOnly = type == "ALBUM" && bc?.currentBrowserMode is BrowserMode.Compilations
            val hideCompilationTracks = type == "ALBUM" && artistFilter.isNotEmpty() && isTrackArtist &&
                bc?.artistsHideCompilations() == true

            for (song in activeLibrary) {
                if (compilationsOnly && !song.isCompilation) continue
                if (com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)) continue
                if (type == "GENRE_ALBUM") {
                    if (!song.matchesGenre(genreFilter)) {
                        continue
                    }
                    if (artistFilter.isNotEmpty()) {
                        val matchField = if (isTrackArtist) song.artist else song.albumArtist
                        if (!artistFilter.equals(matchField, ignoreCase = true)) {
                            continue
                        }
                    }
                } else if (type == "ALBUM" && artistFilter.isNotEmpty()) {
                    val matchField = if (isTrackArtist) song.artist else song.albumArtist
                    if (!artistFilter.equals(matchField, ignoreCase = true)) {
                        continue
                    }
                }

                val c = counts[song.album]
                counts[song.album] = if (c == null) 1 else c + 1

                var files = filesMap[song.album]
                if (files == null) {
                    files = ArrayList()
                    filesMap[song.album] = files
                }
                files.add(song.file)

                val primaryArtist = if (song.albumArtist.isNotBlank()) song.albumArtist else song.artist
                val compKey = AlbumKey.of(song)
                val compC = counts[compKey]
                counts[compKey] = if (compC == null) 1 else compC + 1
                var compFiles = filesMap[compKey]
                if (compFiles == null) {
                    compFiles = ArrayList()
                    filesMap[compKey] = compFiles
                }
                compFiles.add(song.file)

                if (!artists.containsKey(song.album) && primaryArtist.isNotBlank()) {
                    artists[song.album] = primaryArtist
                }
            }
            albumSongCounts = counts
            albumFilesMap = filesMap
            albumArtistsMap = artists
        }
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    /** The name of the row for the letter jump; none for the "All Songs" row. */
    override fun rowText(position: Int): String? = items[position].takeUnless { it == BrowserController.ALL_SONGS_SENTINEL }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> 0
            type == "ALBUM" || type == "GENRE_ALBUM" -> 1
            else -> 2
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> getAllSongsRowView(convertView)
            type == "ALBUM" || type == "GENRE_ALBUM" -> getAlbumRowView(position, convertView, name)
            else -> getSimpleRowView(convertView, name)
        }
    }

    private fun getAllSongsRowView(convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val art = ArtRow.of(convertView, main)
        art.image.setImageResource(if (isAudiobook) R.drawable.book else R.drawable.icon_all_songs)
        if (cachedAllSongsCount < 0) {
            var allCount = 0
            val bc = main.browserController
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        allCount++
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            allCount++
                        }
                    }
                }
            } else {
                val isTrackArtistMode = bc.categoryArtistFilterIsTrackArtist
                val hideCompilationTracks = isTrackArtistMode && bc.artistsHideCompilations()
                for (song in activeLibrary) {
                    val matchField = if (isTrackArtistMode) song.artist else song.albumArtist
                    if (bc.categoryArtistFilter == matchField &&
                        !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                    ) {
                        allCount++
                    }
                }
            }
            cachedAllSongsCount = allCount
        }

        val allCount = cachedAllSongsCount
        art.bind(if (isAudiobook) main.t("All Audiobooks") else main.t("All Songs"), if (isAudiobook) {
            if (allCount == 1) t1File() else "$allCount ${tFiles()}"
        } else {
            if (allCount == 1) t1Song() else "$allCount ${tSongs()}"
        })
        val row = art.view

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            when (type) {
                "GENRE_ARTIST" -> bc.openSongs("GENRE", bc.categoryGenreFilter)
                "GENRE_ALBUM" -> bc.openSongs("GENRE_ARTIST_SONGS", bc.categoryArtistFilter)
                else -> bc.openSongs(if (bc.categoryArtistFilterIsTrackArtist) "ARTIST" else "ARTIST_ALL_ALBUMS", bc.categoryArtistFilter)
            }
        }

        row.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            val bc = main.browserController
            val tracks = ArrayList<File>()
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        tracks.add(song.file)
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.matchesGenre(bc.categoryGenreFilter)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            tracks.add(song.file)
                        }
                    }
                }
            } else {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                val hideCompilationTracks = isTrack && bc.artistsHideCompilations()
                for (song in activeLibrary) {
                    val matchField = if (isTrack) song.artist else song.albumArtist
                    if (bc.categoryArtistFilter == matchField &&
                        !com.themoon.y1.models.Compilations.hiddenFromArtists(song, hideCompilationTracks)
                    ) {
                        tracks.add(song.file)
                    }
                }
            }
            if (tracks.isNotEmpty()) {
                Collections.shuffle(tracks)
                com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(tracks, 0)
                main.changeScreen(3)
            }
            true
        }

        return row
    }

    private fun getAlbumRowView(position: Int, convertView: View?, name: String): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val art = ArtRow.of(convertView, main)
        val row = art.view
        val ivCover = art.image

        val albumArtist = albumArtists?.getOrNull(position)
        val lookupKey = albumArtist?.let { AlbumKey.of(name, it) } ?: name
        val displayName = name
        ivCover.tag = lookupKey
        val songCount = albumSongCounts?.get(lookupKey) ?: albumSongCounts?.get(name) ?: 0
        val artist = albumArtist ?: albumArtistsMap?.get(name)
        art.bind(displayName, if (isAudiobook) {
            if (songCount == 1) t1File() else "$songCount ${tFiles()}"
        } else if (showAlbumArtist && !artist.isNullOrBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
            "$artist • " + (if (songCount == 1) t1Song() else "$songCount ${tSongs()}")
        } else {
            if (songCount == 1) t1Song() else "$songCount ${tSongs()}"
        })

        val cached = coverCache?.get(lookupKey)
        if (cached != null) {
            ivCover.setImageDrawable(cached)
        } else {
            val albumFiles = albumFilesMap?.get(lookupKey) ?: albumFilesMap?.get(name)
            val sampleFile = if (!albumFiles.isNullOrEmpty()) albumFiles[0] else null
            val albumKey = if (sampleFile != null) AlbumCoverManager.getAlbumKey(sampleFile, displayName) else displayName

            val directBmp = AlbumCoverManager.getInstance().getThumbnailDirect(albumKey)
            if (directBmp != null) {
                val directDrawable = BitmapDrawable(row.resources, directBmp)
                coverCache?.put(lookupKey, directDrawable)
                ivCover.setImageDrawable(directDrawable)
            } else {
                ivCover.setImageResource(if (isAudiobook) R.drawable.book else R.drawable.default_album)
                ivCover.tag = lookupKey
                loadAlbumArtAsync(lookupKey, displayName, sampleFile, ivCover)
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            val query = when {
                type == "GENRE_ALBUM" -> "GENRE_ALBUM_SONGS"
                bc.currentBrowserMode is BrowserMode.Compilations -> "COMPILATION_ALBUM"
                else -> type
            }
            bc.openSongs(query, name, albumArtist ?: "")
        }

        row.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            val tracks = albumFilesMap?.get(lookupKey) ?: albumFilesMap?.get(name)
            if (!tracks.isNullOrEmpty()) {
                val shuffled = ArrayList(tracks)
                Collections.shuffle(shuffled)
                AudioPlayerManager.getInstance().playTrackList(shuffled, 0)
                main.changeScreen(MainActivity.STATE_PLAYER)
            }
            true
        }

        return row
    }

    private fun loadAlbumArtAsync(cacheKey: String, name: String, sampleFile: File?, targetView: ImageView) {
        if (sampleFile == null) return
        val stubItem = SongItem(sampleFile, "", "", name)
        AlbumCoverManager.getInstance().loadCoverAsync(stubItem, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (cover != null) {
                    val res = MainActivity.instance?.resources ?: targetView.resources
                    val result = BitmapDrawable(res, cover)
                    coverCache?.put(cacheKey, result)
                    if (cacheKey == targetView.tag) {
                        targetView.setImageDrawable(result)
                    }
                }
            }
        })
    }

    /** A name row: the MENU row of the list factory (the same row as the song rows). */
    private fun getSimpleRowView(convertView: View?, name: String): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val row: View
        if (convertView != null && SIMPLE_ROW == convertView.tag) {
            row = convertView
            ((row as LinearLayout).getChildAt(1) as TextView).text = name
        } else {
            row = ListItemViewFactory.createListButtonWithIcon(main, "", name).apply {
                tag = SIMPLE_ROW
                val d = main.resources.displayMetrics.density
                val height = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * d).toInt() else AbsListView.LayoutParams.WRAP_CONTENT
                layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, height)
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            main.clickFeedback()
            when (type) {
                "GENRE" -> bc.openList(BrowserMode.GenreArtists(name))
                "GENRE_ARTIST" -> bc.openList(BrowserMode.GenreAlbums(bc.categoryGenreFilter, name))
                "ARTIST", "TRACK_ARTIST" -> bc.openList(BrowserMode.Albums(name, type == "TRACK_ARTIST"))
                else -> bc.openSongs(type, name)
            }
        }

        return row
    }

    private fun t1Song(): String = "1 " + (MainActivity.instance?.t("Song") ?: "Song")

    private fun tSongs(): String = MainActivity.instance?.t("Songs") ?: "Songs"

    private fun t1File(): String = "1 " + (MainActivity.instance?.t("File") ?: "File")

    private fun tFiles(): String = MainActivity.instance?.t("Files") ?: "Files"

    companion object {
        private const val SIMPLE_ROW = "simple_row"
        private var coverCache: LruCache<String, Drawable>? = null
    }
}
