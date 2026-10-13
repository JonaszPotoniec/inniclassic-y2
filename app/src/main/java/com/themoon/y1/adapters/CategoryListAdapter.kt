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
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.browser.BrowserController
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.views.ClassicMenuStyle
import java.io.File
import java.io.FileInputStream
import java.util.Collections

class CategoryListAdapter(
    private val items: List<String>,
    private val type: String
) : BaseAdapter() {

    private var albumSongCounts: MutableMap<String, Int>? = null
    private var albumFilesMap: MutableMap<String, MutableList<File>>? = null
    private var cachedAllSongsCount = -1

    init {
        if (coverCache == null) {
            coverCache = LruCache(50)
        }

        if (type == "ALBUM" || type == "GENRE_ALBUM") {
            val counts = HashMap<String, Int>()
            val filesMap = HashMap<String, MutableList<File>>()
            val bc = MainActivity.instance?.browserController
            val genreFilter = bc?.categoryGenreFilter ?: ""
            val artistFilter = bc?.categoryArtistFilter ?: ""
            val isTrackArtist = bc?.categoryArtistFilterIsTrackArtist == true

            for (song in MainActivity.customLibrary) {
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
                } else if (artistFilter.isNotEmpty()) {
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
            }
            albumSongCounts = counts
            albumFilesMap = filesMap
        }
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

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
            BrowserController.ALL_SONGS_SENTINEL == name -> getAllSongsRowView(position, convertView)
            type == "ALBUM" || type == "GENRE_ALBUM" -> getAlbumRowView(position, convertView, name)
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

        tvTitle.text = main.t("All Songs")
        ivCover.setImageResource(R.drawable.icon_all_songs)

        if (cachedAllSongsCount < 0) {
            var allCount = 0
            val bc = main.browserController
            if (type == "GENRE_ARTIST") {
                for (song in MainActivity.customLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        allCount++
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in MainActivity.customLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            allCount++
                        }
                    }
                }
            } else {
                val isTrackArtistMode = bc.categoryArtistFilterIsTrackArtist
                for (song in MainActivity.customLibrary) {
                    val matchField = if (isTrackArtistMode) song.artist else song.albumArtist
                    if (bc.categoryArtistFilter == matchField) {
                        allCount++
                    }
                }
            }
            cachedAllSongsCount = allCount
        }

        val allCount = cachedAllSongsCount
        tvSubtitle.text = if (allCount == 1) t1Song() else "$allCount ${tSongs()}"

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
            if (type == "GENRE_ARTIST") {
                for (song in MainActivity.customLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        tracks.add(song.file)
                    }
                }
            } else if (type == "GENRE_ALBUM") {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in MainActivity.customLibrary) {
                    if (song.genre.isNotEmpty() && song.genre.equals(bc.categoryGenreFilter, ignoreCase = true)) {
                        val matchField = if (isTrack) song.artist else song.albumArtist
                        if (bc.categoryArtistFilter.equals(matchField, ignoreCase = true)) {
                            tracks.add(song.file)
                        }
                    }
                }
            } else {
                val isTrack = bc.categoryArtistFilterIsTrackArtist
                for (song in MainActivity.customLibrary) {
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

    private fun getAlbumRowView(position: Int, convertView: View?, name: String): View {
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

        tvArrow.setTextColor(0xFFFFFFFF.toInt())
        tvArrow.visibility = if (row.isFocused) View.VISIBLE else View.GONE

        ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)
        ivCover.tag = name
        row.background = if (row.isFocused) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvTitle.isSelected = row.isFocused

        tvTitle.text = name
        tvTitle.setTextColor(if (row.isFocused) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary())
        val songCount = albumSongCounts?.get(name) ?: 0
        tvSubtitle.text = if (songCount == 1) t1Song() else "$songCount ${tSongs()}"
        tvSubtitle.setTextColor(if (row.isFocused) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorSecondary())

        val cached = coverCache?.get(name)
        if (cached != null) {
            ivCover.setImageDrawable(cached)
        } else {
            val albumFiles = albumFilesMap?.get(name)
            val sampleFile = if (!albumFiles.isNullOrEmpty()) albumFiles[0] else null
            val albumKey = if (sampleFile != null) AlbumCoverManager.getAlbumKey(sampleFile, name) else name

            val directBmp = AlbumCoverManager.getInstance().getThumbnailDirect(albumKey)
            if (directBmp != null) {
                val directDrawable = BitmapDrawable(row.resources, directBmp)
                coverCache?.put(name, directDrawable)
                ivCover.setImageDrawable(directDrawable)
            } else {
                ivCover.setImageResource(R.drawable.default_album)
                ivCover.tag = name
                loadAlbumArtAsync(name, sampleFile, ivCover)
            }
        }

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                row.background = main.createFocusedButtonBackground()
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvSubtitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvArrow.visibility = View.VISIBLE
                main.showFastScrollLetter(name)
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
            } else {
                bc.virtualQueryType = type
            }
            bc.virtualQueryValue = name
            bc.currentBrowserMode = BrowserMode.VirtualSongs
            bc.buildVirtualSongs()
        }

        return row
    }

    private fun loadAlbumArtAsync(name: String, sampleFile: File?, targetView: ImageView) {
        if (sampleFile == null) return
        val stubItem = SongItem(sampleFile, "", "", name)
        AlbumCoverManager.getInstance().loadCoverAsync(stubItem, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(albumKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (cover != null) {
                    val res = MainActivity.instance?.resources ?: targetView.resources
                    val result = BitmapDrawable(res, cover)
                    coverCache?.put(name, result)
                    if (name == targetView.tag) {
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

    companion object {
        private var coverCache: LruCache<String, Drawable>? = null
    }
}
