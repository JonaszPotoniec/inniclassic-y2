package com.themoon.y1.adapters

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.core.TimeFormat
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.podcasts.PodcastDownloadManager
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date

class SongListAdapter(
    private val items: List<SongItem>
) : BaseAdapter() {

    private val showShuffleRow: Boolean = determineShuffleRow(items)

    fun hasShuffleRow(): Boolean = showShuffleRow

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int {
        if (showShuffleRow && position == 0) {
            return VIEW_TYPE_SHUFFLE
        }
        return VIEW_TYPE_SONG
    }

    override fun getCount(): Int = items.size + (if (showShuffleRow) 1 else 0)

    override fun getItem(position: Int): Any? {
        if (showShuffleRow) {
            if (position == 0) return null
            return items[position - 1]
        }
        return items[position]
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        if (hasShuffleRow() && position == 0) {
            return getShuffleRowView(convertView)
        }
        val songPosition = if (hasShuffleRow()) position - 1 else position
        val song = items[songPosition]

        val main = MainActivity.instance
        var iconCode = if (main != null && main.isAudiobookLibraryMode) "\uE310" else "\uE405"
        var displayTitle = song.title
        var customColor = 0

        if (main != null) {
            if (main.currentBrowserMode == 14) {
                val audioUrl = song.genre
                val channelName = song.artist
                val pubDate = song.year
                val datePrefix = if (!pubDate.isNullOrEmpty()) "[${pubDate.trim()}] " else ""

                val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)

                var savedPos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
                if (savedPos == 0) {
                    val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                    savedPos = main.prefs.getInt("book_pos_$streamKey", 0)
                }

                var progressText = ""
                if (savedPos > 0) {
                    progressText = " [⏱ " + TimeFormat.format(savedPos) + "]"
                }

                val pdm = PodcastDownloadManager.getInstance()
                if (pdm.isDownloading(audioUrl)) {
                    val prog = pdm.getProgress(audioUrl)
                    displayTitle = "⏳ [$prog%] $datePrefix${song.title}"
                } else if (localFile.exists() && localFile.length() > 0) {
                    displayTitle = "✔ $datePrefix${song.title}$progressText"
                } else {
                    displayTitle = "$datePrefix${song.title}$progressText"
                }
            } else if ("RECENT" == main.virtualQueryType) {
                val lastMod = song.file.lastModified()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayStart = cal.timeInMillis
                val yesterdayStart = todayStart - (24 * 60 * 60 * 1000)
                val datePrefix = when {
                    lastMod >= todayStart -> "[${main.t("Today")}] "
                    lastMod >= yesterdayStart -> "[${main.t("Yesterday")}] "
                    else -> {
                        val sdf = SimpleDateFormat("yy.MM.dd")
                        "[${sdf.format(Date(lastMod))}] "
                    }
                }
                displayTitle = datePrefix + song.title
            } else if ("SEARCH" == main.virtualQueryType) {
                val q = main.virtualQueryValue?.lowercase() ?: ""
                val titleMatch = song.title.lowercase().contains(q)
                if (!titleMatch && song.artist.lowercase().contains(q)) {
                    displayTitle = "${song.title}  —  ${song.artist}"
                } else if (!titleMatch && song.album.lowercase().contains(q)) {
                    displayTitle = "${song.title}  —  ${song.album}"
                }
            }
        }

        val isSongRowValid = convertView is LinearLayout &&
            "song_row" == convertView.tag &&
            convertView.childCount > 1 &&
            convertView.getChildAt(0) is TextView

        val btn: View = if (!isSongRowValid || main == null) {
            main?.createListButtonWithIcon(iconCode, displayTitle, customColor)?.apply {
                tag = "song_row"
                val rowHeight = if (ThemeManager.isClassicTheme()) (ClassicMenuStyle.ROW_HEIGHT_DP * main.resources.displayMetrics.density).toInt() else AbsListView.LayoutParams.WRAP_CONTENT
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    rowHeight
                )
            } ?: View(parent?.context)
        } else {
            val layout = convertView as LinearLayout
            val tvIcon = layout.getChildAt(0) as TextView
            val tvText = layout.getChildAt(1) as TextView
            tvIcon.text = iconCode
            tvText.text = displayTitle
            val applyColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()
            if (!convertView.hasFocus()) {
                tvIcon.setTextColor(applyColor)
                tvText.setTextColor(applyColor)
            }
            convertView
        }

        var hasProgress = false
        if (main != null && main.isAudiobookLibraryMode) {
            val pos = main.prefs.getInt("book_pos_" + song.file.absolutePath, 0)
            val dur = main.prefs.getInt("book_dur_" + song.file.absolutePath, 0)
            if (pos > 0 && dur > 0) {
                main.setupAudiobookProgress(btn, pos, dur)
                hasProgress = true
            }
        }

        if (main != null && main.currentBrowserMode == 14) {
            val safeChannel = song.artist.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
            val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)
            val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"

            var pos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
            var dur = main.prefs.getInt("book_dur_" + localFile.absolutePath, 0)

            if (pos == 0 || dur == 0) {
                pos = main.prefs.getInt("book_pos_$streamKey", 0)
                dur = main.prefs.getInt("book_dur_$streamKey", 0)
            }

            if (pos > 0 && dur > 0) {
                main.setupAudiobookProgress(btn, pos, dur)
                hasProgress = true
            }
        }

        if (!hasProgress) {
            applyDefaultFocusListener(btn, position, song.title, customColor)
        }

        btn.setOnClickListener {
            if (main == null) return@setOnClickListener
            main.virtualListSelectedIndex = position
            main.clickFeedback()

            if (main.currentBrowserMode == 14) {
                val audioUrl = song.genre
                val imageUrl = song.album
                val channelName = song.artist

                val safeChannel = channelName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val safeTitle = song.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                val localFile = File(StoragePaths.getPodcastChannelDir(safeChannel), safeTitle)

                if (localFile.exists() && localFile.length() > 0) {
                    var savedPos = main.prefs.getInt("book_pos_" + localFile.absolutePath, 0)
                    if (savedPos == 0) {
                        val streamKey = "/PODCAST_STREAM/$safeChannel/$safeTitle"
                        savedPos = main.prefs.getInt("book_pos_$streamKey", 0)
                    }

                    val playList = ArrayList<File>()
                    var targetIdx = 0

                    for (ep in items) {
                        val epTitle = ep.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_") + ".mp3"
                        val epFile = File(StoragePaths.getPodcastChannelDir(safeChannel), epTitle)
                        if (epFile.exists() && epFile.length() > 0) {
                            playList.add(epFile)
                            if (epFile.absolutePath == localFile.absolutePath) {
                                targetIdx = playList.size - 1
                            }
                        }
                    }

                    if (savedPos > 0) {
                        AudioPlayerManager.getInstance().playTrackListWithOffset(playList, targetIdx, savedPos)
                    } else {
                        AudioPlayerManager.getInstance().playTrackList(playList, targetIdx)
                    }
                    main.changeScreen(3)
                } else {
                    main.showPodcastActionDialog(song.title, audioUrl, imageUrl, channelName)
                }
                return@setOnClickListener
            }

            AudioPlayerManager.getInstance().playTrackList(main.virtualSongList, songPosition)
            main.changeScreen(3)
        }

        btn.setOnLongClickListener {
            if (main == null) return@setOnLongClickListener false
            main.clickFeedback()
            main.isLongPressConsumed = true

            when (main.currentBrowserMode) {
                5 -> main.showRemoveFromFavoritesDialog(song.file)
                7 -> main.showRemoveFromPlaylistDialog(song.file)
                else -> main.showAddToPlaylistDialog(song.file)
            }
            true
        }

        return btn
    }

    private fun getShuffleRowView(convertView: View?): View {
        val main = MainActivity.instance ?: return View(convertView?.context)
        val d = main.resources.displayMetrics.density
        val coverSize = (52f * d).toInt()

        val isShuffleRowValid = convertView is LinearLayout &&
            "shuffle_banner_row" == convertView.tag &&
            convertView.childCount > 2 &&
            convertView.getChildAt(0) is ImageView

        val row: LinearLayout
        val ivCover: ImageView
        val textStack: LinearLayout
        val tvTitle: TextView
        val tvSubtitle: TextView
        val tvArrow: TextView

        if (isShuffleRowValid && convertView != null) {
            row = convertView as LinearLayout
            ivCover = row.getChildAt(0) as ImageView
            textStack = row.getChildAt(1) as LinearLayout
            tvTitle = textStack.getChildAt(0) as TextView
            tvSubtitle = textStack.getChildAt(1) as TextView
            tvArrow = row.getChildAt(2) as TextView
        } else {
            row = LinearLayout(main).apply {
                tag = "shuffle_banner_row"
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

        tvTitle.text = main.t("Shuffle")

        if (shuffleTileBitmap == null || shuffleTileBitmap?.width != coverSize) {
            shuffleTileBitmap = TablerIcons.renderTile(
                TablerIcons.SHUFFLE,
                coverSize,
                (28f * d).toInt(),
                0xFFFFFFFF.toInt(),
                0xFF1C1C1E.toInt(),
                8f * d
            )
        }
        ivCover.setImageBitmap(shuffleTileBitmap)

        val count = if (main.virtualSongList != null && main.virtualSongList.isNotEmpty()) {
            main.virtualSongList.size
        } else {
            items.size
        }
        val countStr = if (count == 1) "1 " + main.t("Song") else "$count " + main.t("Songs")
        tvSubtitle.text = countStr

        val focusedNow = row.isFocused
        row.background = if (focusedNow) main.createFocusedButtonBackground() else main.createButtonBackground(ThemeManager.getListButtonNormalBg())
        tvTitle.setTextColor(if (focusedNow) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorPrimary())
        tvSubtitle.setTextColor(if (focusedNow) ThemeManager.getListButtonFocusedTextColor() else ThemeManager.getTextColorSecondary())
        tvTitle.isSelected = focusedNow

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = 0
                row.background = main.createFocusedButtonBackground()
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvSubtitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvArrow.visibility = View.VISIBLE
                tvTitle.isSelected = true
                main.showFastScrollLetter(main.t("Shuffle"))
            } else {
                row.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvSubtitle.setTextColor(ThemeManager.getTextColorSecondary())
                tvArrow.visibility = View.GONE
                tvTitle.isSelected = false
            }
        }

        row.setOnClickListener {
            main.virtualListSelectedIndex = 0
            main.clickFeedback()
            val listToShuffle: List<File> = if (main.virtualSongList != null && main.virtualSongList.isNotEmpty()) {
                main.virtualSongList
            } else {
                val list = ArrayList<File>()
                for (item in items) {
                    list.add(item.file)
                }
                list
            }
            val shuffled = ArrayList(listToShuffle)
            Collections.shuffle(shuffled)
            AudioPlayerManager.getInstance().playTrackList(shuffled, 0)
            main.changeScreen(3)
        }
        row.setOnLongClickListener(null)

        return row
    }

    private fun applyDefaultFocusListener(btn: View, position: Int, title: String, customColor: Int) {
        val main = MainActivity.instance ?: return
        val normalColor = if (customColor != 0) customColor else ThemeManager.getTextColorPrimary()

        val listener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                main.virtualListSelectedIndex = position
                btn.background = main.createFocusedButtonBackground()

                if (v is LinearLayout) {
                    if (v.childCount > 1) {
                        (v.getChildAt(0) as? TextView)?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        val tvText = v.getChildAt(1) as? TextView
                        tvText?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                        if (v.childCount > 2) v.getChildAt(2).visibility = View.VISIBLE
                        tvText?.isSelected = true
                    }
                } else if (v is Button) {
                    v.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    v.isSelected = true
                }

                main.showFastScrollLetter(title)
            } else {
                btn.background = main.createButtonBackground(ThemeManager.getListButtonNormalBg())

                if (v is LinearLayout) {
                    if (v.childCount > 1) {
                        (v.getChildAt(0) as? TextView)?.setTextColor(normalColor)
                        val tvText = v.getChildAt(1) as? TextView
                        tvText?.setTextColor(normalColor)
                        if (v.childCount > 2) v.getChildAt(2).visibility = View.GONE
                        tvText?.isSelected = false
                    }
                } else if (v is Button) {
                    v.setTextColor(normalColor)
                    v.isSelected = false
                }
            }
        }

        btn.onFocusChangeListener = listener
        if (btn.isFocused) {
            listener.onFocusChange(btn, true)
        }
    }

    companion object {
        const val VIEW_TYPE_SHUFFLE: Int = 0
        const val VIEW_TYPE_SONG: Int = 1

        private var shuffleTileBitmap: Bitmap? = null

        private fun determineShuffleRow(items: List<SongItem>?): Boolean {
            if (items.isNullOrEmpty()) return false
            val main = MainActivity.instance ?: return false
            val type = main.virtualQueryType
            return "ALL" == type || "GENRE" == type || "GENRE_ARTIST_SONGS" == type ||
                "ARTIST" == type || "ARTIST_ALL_ALBUMS" == type
        }
    }
}
