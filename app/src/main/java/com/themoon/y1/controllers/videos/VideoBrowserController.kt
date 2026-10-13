package com.themoon.y1.controllers.videos

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.SquareImageView
import java.io.File

/**
 * Controller for Video library browser:
 * Scans video files, displays video rows with async thumbnail loading and LRU caching,
 * and launches playback.
 */
class VideoBrowserController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val videosProvider: () -> List<File>,
    private val onVideoSelected: (file: File) -> Unit,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val localize: (String) -> String,
    private val onFocusItem: ((title: String) -> Unit)? = null
) : ScreenController {

    private val layoutVideos: LinearLayout = rootView.findViewById(R.id.layout_videos_mode)
    private val containerVideoItems: LinearLayout = rootView.findViewById(R.id.container_video_items)
    private val thumbCache = LruCache<String, Bitmap>(30)

    override fun onEnter(state: ScreenState) {
        layoutVideos.visibility = View.VISIBLE
        buildVideoListUI()
    }

    override fun onExit() {
        layoutVideos.visibility = View.GONE
    }

    fun buildVideoListUI() {
        val density = activity.resources.displayMetrics.density
        val classic = ThemeManager.isClassicTheme()

        layoutVideos.setBackgroundColor(if (classic) ThemeManager.getOverlayBackgroundColor() else 0x66000000)
        layoutVideos.setPadding(
            if (classic) 0 else (10 * density).toInt(),
            if (classic) 0 else (36 * density).toInt(),
            if (classic) 0 else (10 * density).toInt(),
            if (classic) 0 else (10 * density).toInt()
        )

        val params = layoutVideos.layoutParams as? FrameLayout.LayoutParams
        if (params != null) {
            params.topMargin = if (classic) (26 * density).toInt() else 0
            layoutVideos.layoutParams = params
        }

        if (layoutVideos.childCount > 0) {
            layoutVideos.getChildAt(0).visibility = if (classic) View.GONE else View.VISIBLE
        }

        containerVideoItems.removeAllViews()

        val videos = videosProvider()
        if (videos.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No videos found.\nCopy videos into the \"Videos\" folder on your SD card.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        for (file in videos) {
            val title = file.nameWithoutExtension
            val row = createVideoRow(file, title)
            row.setOnClickListener {
                clickFeedback()
                onVideoSelected(file)
            }
            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    private fun createVideoRow(videoFile: File, title: String): LinearLayout {
        val d = activity.resources.displayMetrics.density
        val coverSize = (52 * d).toInt()

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundColor(0)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, if (ThemeManager.isClassicTheme()) 0 else 2, 0, if (ThemeManager.isClassicTheme()) 0 else 2)
            }
        }

        val ivCover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.video_circle)
            layoutParams = LinearLayout.LayoutParams(coverSize, coverSize).apply {
                rightMargin = (10 * d).toInt()
            }
        }
        row.addView(ivCover)

        val tvTitle = TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(ThemeManager.getTextColorPrimary())
            typeface = ThemeManager.getCustomFontBold()
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            )
        }
        row.addView(tvTitle)

        val tvArrow = TextView(activity).apply {
            text = "〉"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (10 * d).toInt()
            }
        }
        row.addView(tvArrow)

        com.themoon.y1.views.ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                row.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvTitle.isSelected = true
                tvArrow.visibility = View.VISIBLE
                onFocusItem?.invoke(tvTitle.text.toString())
            } else {
                row.setBackgroundColor(0)
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvTitle.isSelected = false
                tvArrow.visibility = View.GONE
            }
        }

        loadVideoThumbnail(videoFile, ivCover)
        return row
    }

    private fun loadVideoThumbnail(file: File, ivCover: ImageView) {
        val path = file.absolutePath
        val cached = thumbCache.get(path)
        if (cached != null) {
            ivCover.setImageBitmap(cached)
            return
        }

        Thread {
            var bmp: Bitmap? = null
            var mmr: MediaMetadataRetriever? = null
            try {
                mmr = MediaMetadataRetriever()
                mmr.setDataSource(path)
                bmp = mmr.getFrameAtTime(2000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (ignored: Exception) {
            } finally {
                try { mmr?.release() } catch (ignored: Exception) {}
            }

            if (bmp != null) {
                thumbCache.put(path, bmp)
                activity.runOnUiThread {
                    ivCover.setImageBitmap(bmp)
                }
            }
        }.start()
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
}
