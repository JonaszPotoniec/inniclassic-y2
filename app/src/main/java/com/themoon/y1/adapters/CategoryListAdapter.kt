package com.themoon.y1.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.RecentlyPlayedManager
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.WheelDialog
import java.io.File
import java.io.FileInputStream
import java.util.Collections

class CategoryListAdapter(
    private val items: List<String>,
    private val type: String,
    private val isAudiobook: Boolean = MainActivity.instance?.let {
        it.isAudiobookLibraryMode || (it.isBrowserControllerInitialized() && it.browserController.isAudiobookLibraryMode)
    } ?: false,
    private val recentAlbumEntries: List<RecentlyPlayedManager.RecentAlbumEntry>? = null,
    /** Album artist of each album row in [items], so albums with the same title stay apart. */
    private val albumArtists: List<String>? = null
) : BaseAdapter() {

    private var albumSongCounts: MutableMap<String, Int>? = null
    private var albumFilesMap: MutableMap<String, MutableList<File>>? = null
    private var albumArtistsMap: MutableMap<String, String>? = null
    private var cachedAllSongsCount = -1
    private val showAlbumArtist = type == "ALBUM" && MainActivity.instance?.browserController?.categoryArtistFilter.isNullOrEmpty()

    init {
        if (coverCache == null) {
            coverCache = LruCache(50)
        }

        if (type == "ALBUM" || type == "GENRE_ALBUM" || type == "RECENTLY_PLAYED_ALBUMS") {
            val counts = HashMap<String, Int>()
            val filesMap = HashMap<String, MutableList<File>>()
            val artists = HashMap<String, String>()
            val bc = MainActivity.instance?.browserController
            val genreFilter = bc?.categoryGenreFilter ?: ""
            val artistFilter = bc?.categoryArtistFilter ?: ""
            val isTrackArtist = bc?.categoryArtistFilterIsTrackArtist == true
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary

            for (song in activeLibrary) {
                if (type == "GENRE_ALBUM") {
                    if (song.genre.isEmpty() || !song.genre.equals(genreFilter, ignoreCase = true)) {
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

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 4

    override fun getItemViewType(position: Int): Int {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> 0
            type == "ALBUM" || type == "GENRE_ALBUM" || (type == "RECENTLY_PLAYED_ALBUMS" && name != BrowserController.CLEAR_RECENTLY_PLAYED_SENTINEL) -> 1
            BrowserController.CLEAR_RECENTLY_PLAYED_SENTINEL == name -> 2
            else -> 3
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val name = items[position]
        return when {
            BrowserController.ALL_SONGS_SENTINEL == name -> getAllSongsRowView(position, convertView)
            BrowserController.CLEAR_RECENTLY_PLAYED_SENTINEL == name -> getClearRecentRowView(position, convertView)
            type == "ALBUM" || type == "GENRE_ALBUM" || type == "RECENTLY_PLAYED_ALBUMS" -> getAlbumRowView(position, convertView, name, position)
            else -> getSimpleRowView(position, convertView, name)
        }
    }

    private fun getAllSongsRowView(position: Int, convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val d = main.resources.displayMetrics.density
        val coverSize = (52f * d).toInt()

        val isAllSongsValid = convertView is LinearLayout &&
            "all_songs_row" == convertView.tag &&
            convertView.childCount > 2 &&
            convertView.getChildAt(0) is ImageView

        val row: LinearLayout
        val ivCover: ImageView
        val textStack: LinearLayout
        val tvTitle: TextView
        val tvSubtitle: TextView
        val tvArrow: TextView

        if (isAllSongsValid && convertView != null) {
            row = convertView as LinearLayout
            ivCover = row.getChildAt(0) as ImageView
            textStack = row.getChildAt(1) as LinearLayout
            tvTitle = textStack.getChildAt(0) as TextView
            tvSubtitle = textStack.getChildAt(1) as TextView
            tvArrow = row.getChildAt(2) as TextView
        } else {
            row = LinearLayout(main).apply {
                tag = "all_songs_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setPadding((8f * d).toInt(), (6f * d).toInt(), (10f * d).toInt(), (6f * d).toInt())
                background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }

            ivCover = ImageView(main).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(coverSize, coverSize)
            }
            row.addView(ivCover)

            textStack = LinearLayout(main).apply {
                orientation = LinearLayout.VERTICAL
                val textLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = (10f * d).toInt()
                }
                layoutParams = textLp
            }

            tvTitle = TextView(main).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
                typeface = ThemeManager.getCustomFontBold()
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isHorizontalFadingEdgeEnabled = true
            }
            textStack.addView(tvTitle)

            tvSubtitle = TextView(main).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d)
                setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val subLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (1f * d).toInt()
                }
                layoutParams = subLp
            }
            textStack.addView(tvSubtitle)
            row.addView(textStack)

            tvArrow = TextView(main).apply {
                text = "〉"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * d)
                visibility = View.GONE
                val lpArrow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (6f * d).toInt()
                }
                layoutParams = lpArrow
            }
            row.addView(tvArrow)
        }

        tvArrow.setTextColor(0xFFFFFFFF.toInt())
        tvArrow.visibility = if (row.isFocused) View.VISIBLE else View.GONE
        ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)

        tvTitle.text = if (isAudiobook) main.t("All Audiobooks") else main.t("All Songs")
        ivCover.setImageResource(if (isAudiobook) R.drawable.book else R.drawable.icon_all_songs)

        if (cachedAllSongsCount < 0) {
            var allCount = 0
            val bc = main.browserController
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        allCount++
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            allCount++
                        }
                    }
                }
            } else {
                val isTrackArtistMode = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    val matchField = if (isTrackArtistMode) song.artist else song.albumArtist
                    if (bc.categoryArtistFilter == matchField) {
                        allCount++
                    }
                }
            }
            cachedAllSongsCount = allCount
        }

        val allCount = cachedAllSongsCount
        tvSubtitle.text = if (isAudiobook) {
            if (allCount == 1) t1File() else "$allCount ${tFiles()}"
        } else {
            if (allCount == 1) t1Song() else "$allCount ${tSongs()}"
        }

        val focusedNow = row.isFocused
        row.background = if (focusedNow) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvTitle.setTextColor(if (focusedNow) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary())
        tvSubtitle.setTextColor(if (focusedNow) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorSecondary())
        tvTitle.isSelected = focusedNow

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                row.background = main.createFocusedButtonBackground()
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvSubtitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvArrow.visibility = View.VISIBLE
                tvTitle.isSelected = true
            } else {
                row.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvSubtitle.setTextColor(ThemeManager.getTextColorSecondary())
                tvArrow.visibility = View.GONE
                tvTitle.isSelected = false
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            bc.virtualListSelectedIndex = position
            main.clickFeedback()
            when (type) {
                "GENRE_ARTIST" -> {
                    bc.virtualQueryType = "GENRE"
                    bc.virtualQueryValue = bc.categoryGenreFilter
                }
                "GENRE_ALBUM" -> {
                    bc.virtualQueryType = "GENRE_ARTIST_SONGS"
                    bc.virtualQueryValue = bc.categoryArtistFilter
                }
                else -> {
                    bc.virtualQueryType = if (bc.categoryArtistFilterIsTrackArtist) "ARTIST" else "ARTIST_ALL_ALBUMS"
                    bc.virtualQueryValue = bc.categoryArtistFilter
                }
            }
            bc.currentBrowserMode = BrowserMode.VirtualSongs
            bc.buildVirtualSongs()
        }

        row.setOnLongClickListener {
            main.clickFeedback()
            main.isLongPressConsumed = true
            val bc = main.browserController
            val tracks = ArrayList<File>()
            val activeLibrary = if (isAudiobook) MainActivity.audiobookLibrary else MainActivity.customLibrary
            if (type == "GENRE_ARTIST") {
                for (song in activeLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        tracks.add(song.file)
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            tracks.add(song.file)
                        }
                    }
                }
            } else {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in activeLibrary) {
                    val matchField = if (isTrack) song.artist else song.albumArtist
                    if (bc.categoryArtistFilter == matchField) {
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

    private fun getAlbumRowView(position: Int, convertView: View?, name: String, itemIndex: Int = position): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val d = main.resources.displayMetrics.density
        val coverSize = (52f * d).toInt()

        val isAlbumRowValid = convertView is LinearLayout &&
            "album_row" == convertView.tag &&
            convertView.childCount > 2 &&
            convertView.getChildAt(0) is ImageView

        val row: LinearLayout
        val ivCover: ImageView
        val textStack: LinearLayout
        val tvTitle: TextView
        val tvSubtitle: TextView
        val tvArrow: TextView

        if (isAlbumRowValid && convertView != null) {
            row = convertView as LinearLayout
            ivCover = row.getChildAt(0) as ImageView
            textStack = row.getChildAt(1) as LinearLayout
            tvTitle = textStack.getChildAt(0) as TextView
            tvSubtitle = textStack.getChildAt(1) as TextView
            tvArrow = row.getChildAt(2) as TextView
        } else {
            row = LinearLayout(main).apply {
                tag = "album_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setPadding((8f * d).toInt(), (6f * d).toInt(), (10f * d).toInt(), (6f * d).toInt())
                background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }

            ivCover = ImageView(main).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(coverSize, coverSize)
            }
            row.addView(ivCover)

            textStack = LinearLayout(main).apply {
                orientation = LinearLayout.VERTICAL
                val textLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = (10f * d).toInt()
                }
                layoutParams = textLp
            }

            tvTitle = TextView(main).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
                typeface = ThemeManager.getCustomFontBold()
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isHorizontalFadingEdgeEnabled = true
            }
            textStack.addView(tvTitle)

            tvSubtitle = TextView(main).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * d)
                setTypeface(ThemeManager.getCustomFont(), Typeface.NORMAL)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val subLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (1f * d).toInt()
                }
                layoutParams = subLp
            }
            textStack.addView(tvSubtitle)
            row.addView(textStack)

            tvArrow = TextView(main).apply {
                text = "〉"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * d)
                visibility = View.GONE
                val lpArrow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (6f * d).toInt()
                }
                layoutParams = lpArrow
            }
            row.addView(tvArrow)
        }

        val recentEntry = if (type == "RECENTLY_PLAYED_ALBUMS") recentAlbumEntries?.getOrNull(itemIndex) else null
        val albumArtist = albumArtists?.getOrNull(itemIndex)
        val lookupKey = recentEntry?.compositeKey ?: albumArtist?.let { AlbumKey.of(name, it) } ?: name
        val displayName = recentEntry?.albumName ?: name

        tvArrow.setTextColor(0xFFFFFFFF.toInt())
        tvArrow.visibility = if (row.isFocused) View.VISIBLE else View.GONE

        ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)
        ivCover.tag = lookupKey
        row.background = if (row.isFocused) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvTitle.isSelected = row.isFocused

        tvTitle.text = displayName
        tvTitle.setTextColor(if (row.isFocused) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary())
        val songCount = albumSongCounts?.get(lookupKey) ?: albumSongCounts?.get(name) ?: 0
        val artist = recentEntry?.artistName ?: albumArtist ?: albumArtistsMap?.get(name)
        tvSubtitle.text = if (isAudiobook) {
            if (songCount == 1) t1File() else "$songCount ${tFiles()}"
        } else if ((type == "RECENTLY_PLAYED_ALBUMS" || showAlbumArtist) && !artist.isNullOrBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
            "$artist • " + (if (songCount == 1) t1Song() else "$songCount ${tSongs()}")
        } else {
            if (songCount == 1) t1Song() else "$songCount ${tSongs()}"
        }
        tvSubtitle.setTextColor(if (row.isFocused) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorSecondary())

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

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                row.background = main.createFocusedButtonBackground()
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvSubtitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvArrow.visibility = View.VISIBLE
                if (type != "RECENTLY_PLAYED_ALBUMS") {
                    main.showFastScrollLetter(name)
                }
                tvTitle.isSelected = true
            } else {
                row.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvSubtitle.setTextColor(ThemeManager.getTextColorSecondary())
                tvArrow.visibility = View.GONE
                tvTitle.isSelected = false
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            bc.virtualListSelectedIndex = position
            main.clickFeedback()
            if (type == "GENRE_ALBUM") {
                bc.virtualQueryType = "GENRE_ALBUM_SONGS"
                bc.virtualQueryValue = name
                bc.virtualQueryAlbumArtist = albumArtist ?: ""
            } else if (type == "RECENTLY_PLAYED_ALBUMS") {
                bc.virtualQueryType = "RECENTLY_PLAYED_ALBUM"
                bc.virtualQueryValue = displayName
                bc.categoryArtistFilter = recentEntry?.artistName?.takeIf { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
                bc.categoryArtistFilterIsTrackArtist = false
                bc.virtualQueryAlbumArtist = bc.categoryArtistFilter
            } else {
                bc.virtualQueryType = type
                bc.virtualQueryValue = name
                bc.virtualQueryAlbumArtist = albumArtist ?: ""
            }
            bc.currentBrowserMode = BrowserMode.VirtualSongs
            bc.buildVirtualSongs()
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

    private fun getClearRecentRowView(position: Int, convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val d = main.resources.displayMetrics.density
        val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * d).toInt() else (48f * d).toInt()
        val padX = ((if (ThemeManager.isClassicTheme()) 8 else 12) * d).toInt()

        val isClearRowValid = convertView is LinearLayout &&
            "clear_recent_row" == convertView.tag &&
            convertView.childCount > 2

        val row: LinearLayout
        val ivIcon: ImageView
        val tvText: TextView
        val tvArrow: TextView

        if (isClearRowValid && convertView != null) {
            row = convertView as LinearLayout
            ivIcon = row.getChildAt(0) as ImageView
            tvText = row.getChildAt(1) as TextView
            tvArrow = row.getChildAt(2) as TextView
        } else {
            row = LinearLayout(main).apply {
                tag = "clear_recent_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
                setPadding(padX, 0, padX, 0)
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
            }

            val iconSize = (18f * d).toInt()
            ivIcon = ImageView(main).apply {
                val lp = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    rightMargin = (10f * d).toInt()
                }
                layoutParams = lp
            }
            row.addView(ivIcon)

            tvText = TextView(main).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
                typeface = if (ThemeManager.isClassicTheme()) ThemeManager.getCustomFontBold() else ThemeManager.getCustomFont()
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            row.addView(tvText)

            tvArrow = TextView(main).apply {
                text = "〉"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * d)
                visibility = View.GONE
                val lpArrow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (6f * d).toInt()
                }
                layoutParams = lpArrow
            }
            row.addView(tvArrow)
        }

        val normalColor = 0xFFFF5555.toInt()
        val focusedColor = ThemeManager.getListButtonFocusedTextColor()
        val isFocused = row.isFocused

        row.background = if (isFocused) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvText.text = main.t("Clear Recently Played")
        tvText.setTextColor(if (isFocused) focusedColor else normalColor)
        tvArrow.setTextColor(0xFFFFFFFF.toInt())
        tvArrow.visibility = if (isFocused) View.VISIBLE else View.GONE
        ClassicMenuStyle.apply(row, tvText, tvArrow, ClassicMenuStyle.ROW_HEIGHT_DP)

        val iconColor = if (isFocused) focusedColor else normalColor
        val iconSize = (18f * d).toInt()
        ivIcon.setImageBitmap(TablerIcons.render(TablerIcons.TRASH, iconSize, iconColor))

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            row.background = if (hasFocus) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
            tvText.setTextColor(if (hasFocus) focusedColor else normalColor)
            tvArrow.visibility = if (hasFocus) View.VISIBLE else View.GONE
            ivIcon.setImageBitmap(TablerIcons.render(TablerIcons.TRASH, iconSize, if (hasFocus) focusedColor else normalColor))
            main.virtualListSelectedIndex = position
        }

        row.setOnClickListener {
            main.clickFeedback()
            WheelDialog.show(main) {
                title = main.t("Clear Recently Played")
                message = main.t("Are you sure you want to clear your recently played history?")
                positiveButton(main.t("Clear"), isDestructive = true) {
                    RecentlyPlayedManager.getInstance(main).clear()
                    main.browserController.buildRecentlyPlayedAlbums()
                    Toast.makeText(main, main.t("Recently played cleared."), Toast.LENGTH_SHORT).show()
                }
                negativeButton(main.t("Cancel"))
            }
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

    private fun getSimpleRowView(position: Int, convertView: View?, name: String): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val row: LinearLayout
        val tvMain: TextView
        val tvArrow: TextView

        val isSimpleRowValid = convertView is LinearLayout &&
            "simple_row" == convertView.tag &&
            convertView.childCount > 1 &&
            convertView.getChildAt(0) is TextView

        if (isSimpleRowValid && convertView != null) {
            row = convertView as LinearLayout
            tvMain = row.getChildAt(0) as TextView
            tvArrow = row.getChildAt(1) as TextView
        } else {
            val d = main.resources.displayMetrics.density

            row = LinearLayout(main).apply {
                tag = "simple_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isSoundEffectsEnabled = false
                setPadding((8f * d).toInt(), (6f * d).toInt(), (10f * d).toInt(), (6f * d).toInt())
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }

            tvMain = TextView(main).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isHorizontalFadingEdgeEnabled = true
                typeface = ThemeManager.getCustomFontBold()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ThemeManager.getListTextSize() * d)
                val lpMain = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lpMain
            }
            row.addView(tvMain)

            tvArrow = TextView(main).apply {
                text = "〉"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * d)
                visibility = View.GONE
                val lpArrow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (6f * d).toInt()
                }
                layoutParams = lpArrow
            }
            row.addView(tvArrow)
        }

        ClassicMenuStyle.apply(row, tvMain, tvArrow, ClassicMenuStyle.ROW_HEIGHT_DP)
        tvMain.isSelected = row.isFocused
        tvMain.text = name

        val isFocusedNow = row.isFocused
        row.background = if (isFocusedNow) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvMain.setTextColor(if (isFocusedNow) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary())
        tvArrow.setTextColor(0xFFFFFFFF.toInt())
        tvArrow.visibility = if (isFocusedNow) View.VISIBLE else View.GONE

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                row.background = main.createFocusedButtonBackground()
                tvMain.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvArrow.visibility = View.VISIBLE
                main.showFastScrollLetter(name)
                tvMain.isSelected = true
            } else {
                row.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvMain.setTextColor(ThemeManager.getTextColorPrimary())
                tvArrow.visibility = View.GONE
                tvMain.isSelected = false
            }
        }

        row.setOnClickListener {
            val bc = main.browserController
            bc.virtualListSelectedIndex = position
            main.clickFeedback()
            when (type) {
                "GENRE" -> {
                    bc.categoryGenreFilter = name
                    bc.categoryArtistFilter = ""
                    bc.categoryArtistFilterIsTrackArtist = true
                    bc.currentBrowserMode = BrowserMode.GenreArtists(name)
                    bc.virtualQueryValue = ""
                    bc.buildVirtualCategories("GENRE_ARTIST")
                }
                "GENRE_ARTIST" -> {
                    bc.categoryArtistFilter = name
                    bc.categoryArtistFilterIsTrackArtist = true
                    bc.currentBrowserMode = BrowserMode.GenreAlbums(bc.categoryGenreFilter, name)
                    bc.virtualQueryValue = ""
                    bc.buildVirtualCategories("GENRE_ALBUM")
                }
                "ARTIST", "TRACK_ARTIST" -> {
                    bc.categoryArtistFilter = name
                    bc.categoryArtistFilterIsTrackArtist = (type == "TRACK_ARTIST")
                    bc.virtualQueryValue = ""
                    bc.currentBrowserMode = BrowserMode.Albums(name, type == "TRACK_ARTIST")
                    bc.buildVirtualCategories("ALBUM")
                }
                else -> {
                    bc.virtualQueryType = type
                    bc.virtualQueryValue = name
                    bc.currentBrowserMode = BrowserMode.VirtualSongs
                    bc.buildVirtualSongs()
                }
            }
        }

        return row
    }

    private fun t1Song(): String = "1 " + (MainActivity.instance?.t("Song") ?: "Song")

    private fun tSongs(): String = MainActivity.instance?.t("Songs") ?: "Songs"

    private fun t1File(): String = "1 " + (MainActivity.instance?.t("File") ?: "File")

    private fun tFiles(): String = MainActivity.instance?.t("Files") ?: "Files"

    companion object {
        private var coverCache: LruCache<String, Drawable>? = null
    }
}
