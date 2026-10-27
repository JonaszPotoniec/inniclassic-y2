package com.themoon.y1.controllers.videos

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.models.TvSeason
import com.themoon.y1.models.TvSeries
import com.themoon.y1.models.VideoItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.VideoMode
import com.themoon.y1.views.ClassicMenuStyle
import com.themoon.y1.views.ListItemViewFactory
import com.themoon.y1.views.TablerIcons
import java.io.File
import java.util.Locale

/**
 * Controller for Video library browser:
 * - Dual-pane root menu (Movies, TV Series, By Directory) with right artwork preview pane
 * - Standalone Movies full-width browser with async cover/frame thumbnails
 * - Hierarchical TV Series browser: Series -> Season -> Episode
 * - Folder tree directory browser for Movies and Tvshows
 * - LRU cached cover and video frame loading
 */
class VideoBrowserController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val movieVideosProvider: () -> List<VideoItem>,
    private val tvSeriesProvider: () -> List<TvSeries>,
    private val allVideosProvider: () -> List<File>,
    private val onVideoSelected: (file: File) -> Unit,
    private val onNavigateToMode: (VideoMode) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String,
    private val updateHeaderTitle: (String) -> Unit
) : ScreenController {

    private val layoutVideos: LinearLayout = rootView.findViewById(R.id.layout_videos_mode)
    private val layoutVideosLeftPane: LinearLayout? = rootView.findViewById(R.id.layout_videos_left_pane)
    private val containerVideoItems: LinearLayout = rootView.findViewById(R.id.container_video_items)
    private val scrollVideos: ScrollView? = rootView.findViewById(R.id.scroll_videos)
    private val frameVideoCover: FrameLayout? = rootView.findViewById(R.id.frame_video_cover)
    private val ivVideoCover: ImageView? = rootView.findViewById(R.id.iv_video_cover)

    private val thumbCache = LruCache<String, Bitmap>(50)
    var currentVideoMode: VideoMode = VideoMode.Root
    private var coverPaneSetUp: Boolean = false

    fun clearThumbCache() {
        thumbCache.evictAll()
    }

    fun isCoverPaneActive(): Boolean = frameVideoCover?.visibility == View.VISIBLE

    fun getCurrentTitle(): String {
        return when (val mode = currentVideoMode) {
            is VideoMode.Root -> localize("Videos")
            is VideoMode.Movies -> localize("Movies")
            is VideoMode.TvShows -> localize("TV Series")
            is VideoMode.TvSeasons -> mode.seriesName
            is VideoMode.TvEpisodes -> "${mode.seriesName} - ${localize("Season")} ${mode.seasonNumber}"
            is VideoMode.Directory -> if (StoragePaths.isAnyStorageRoot(mode.directory) || !mode.directory.exists()) localize("By Directory") else mode.directory.name
        }
    }

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp", "ts", "wmv")

    override fun onEnter(state: ScreenState) {
        layoutVideos.visibility = View.VISIBLE
        if (state is ScreenState.Videos) {
            currentVideoMode = state.mode
        }
        buildCurrentModeUI()
    }

    override fun onExit() {
        layoutVideos.visibility = View.GONE
        hideCoverPane()
    }

    override fun onThemeChanged() {
        buildCurrentModeUI()
    }

    fun buildCurrentModeUI() {
        val classic = ThemeManager.isClassicTheme()

        layoutVideos.setBackgroundColor(if (classic) ThemeManager.getOverlayBackgroundColor() else 0x66000000)

        val params = layoutVideos.layoutParams as? FrameLayout.LayoutParams
        if (params != null && params.topMargin != 0) {
            params.topMargin = 0
            layoutVideos.layoutParams = params
        }

        when (val mode = currentVideoMode) {
            is VideoMode.Root -> buildRootVideoMenu()
            is VideoMode.Movies -> buildMoviesListUI()
            is VideoMode.TvShows -> buildTvShowsListUI()
            is VideoMode.TvSeasons -> buildTvSeasonsListUI(mode.seriesName)
            is VideoMode.TvEpisodes -> buildTvEpisodesListUI(mode.seriesName, mode.seasonNumber)
            is VideoMode.Directory -> buildDirectoryListUI(mode.directory)
        }
    }

    // ------------------------------------------------------------------------
    // Root Menu (Dual-Pane View)
    // ------------------------------------------------------------------------

    private fun buildRootVideoMenu() {
        showCoverPane()
        updateHeaderTitle(localize("Videos"))
        containerVideoItems.removeAllViews()

        // 1. Movies button
        val btnMovies = ListItemViewFactory.createTablerListButtonWithRightIcon(
            context = activity,
            iconPaths = TablerIcons.MOVIE,
            text = localize("Movies"),
            rightIconPaths = TablerIcons.CHEVRON_RIGHT,
            iconColor = ThemeManager.getTextColorPrimary(),
            textColor = ThemeManager.getTextColorPrimary()
        )
        btnMovies.setOnClickListener {
            clickFeedback()
            onNavigateToMode(VideoMode.Movies)
        }
        btnMovies.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnMovies.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                val tv = findFirstTextView(btnMovies)
                tv?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tv?.isSelected = true
            } else {
                btnMovies.setBackgroundColor(0)
                val tv = findFirstTextView(btnMovies)
                tv?.setTextColor(ThemeManager.getTextColorPrimary())
                tv?.isSelected = false
            }
        }
        containerVideoItems.addView(btnMovies)

        // 2. TV Series button
        val btnTv = ListItemViewFactory.createTablerListButtonWithRightIcon(
            context = activity,
            iconPaths = TablerIcons.DEVICE_TV,
            text = localize("TV Series"),
            rightIconPaths = TablerIcons.CHEVRON_RIGHT,
            iconColor = ThemeManager.getTextColorPrimary(),
            textColor = ThemeManager.getTextColorPrimary()
        )
        btnTv.setOnClickListener {
            clickFeedback()
            onNavigateToMode(VideoMode.TvShows)
        }
        btnTv.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnTv.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                val tv = findFirstTextView(btnTv)
                tv?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tv?.isSelected = true
            } else {
                btnTv.setBackgroundColor(0)
                val tv = findFirstTextView(btnTv)
                tv?.setTextColor(ThemeManager.getTextColorPrimary())
                tv?.isSelected = false
            }
        }
        containerVideoItems.addView(btnTv)

        // 3. By Directory button
        val btnDir = ListItemViewFactory.createTablerListButtonWithRightIcon(
            context = activity,
            iconPaths = TablerIcons.FOLDER,
            text = localize("By Directory"),
            rightIconPaths = TablerIcons.CHEVRON_RIGHT,
            iconColor = ThemeManager.getTextColorPrimary(),
            textColor = ThemeManager.getTextColorPrimary()
        )
        btnDir.setOnClickListener {
            clickFeedback()
            val initialDir = StoragePaths.getMoviesDir().parentFile ?: StoragePaths.getMoviesDir()
            onNavigateToMode(VideoMode.Directory(initialDir))
        }
        btnDir.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnDir.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                val tv = findFirstTextView(btnDir)
                tv?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tv?.isSelected = true
            } else {
                btnDir.setBackgroundColor(0)
                val tv = findFirstTextView(btnDir)
                tv?.setTextColor(ThemeManager.getTextColorPrimary())
                tv?.isSelected = false
            }
        }
        containerVideoItems.addView(btnDir)

        refreshCoverPane()

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    private fun findFirstTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView) return child
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Movies List View
    // ------------------------------------------------------------------------

    private fun buildMoviesListUI() {
        hideCoverPane()
        updateHeaderTitle(localize("Movies"))
        containerVideoItems.removeAllViews()

        val movies = movieVideosProvider()
        if (movies.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No movies found.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        for (movie in movies) {
            val row = createVideoItemRow(movie) {
                clickFeedback()
                onVideoSelected(movie.file)
            }
            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------------
    // TV Series List View
    // ------------------------------------------------------------------------

    private fun buildTvShowsListUI() {
        hideCoverPane()
        updateHeaderTitle(localize("TV Series"))
        containerVideoItems.removeAllViews()

        val seriesList = tvSeriesProvider()
        if (seriesList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No TV series found.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        val d = activity.resources.displayMetrics.density
        val coverSize = (52 * d).toInt()

        for (series in seriesList) {
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

            val textContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvTitle = TextView(activity).apply {
                text = series.name
                textSize = 16f
                setTextColor(ThemeManager.getTextColorPrimary())
                typeface = ThemeManager.getCustomFontBold()
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
            }
            textContainer.addView(tvTitle)

            val seasonWord = if (series.seasons.size == 1) localize("Season") else localize("Seasons")
            val epWord = if (series.totalEpisodes == 1) localize("Episode") else localize("Episodes")
            val tvSub = TextView(activity).apply {
                text = "${series.seasons.size} $seasonWord • ${series.totalEpisodes} $epWord"
                textSize = 12f
                setTextColor(ThemeManager.getTextColorSecondary())
                setSingleLine(true)
            }
            textContainer.addView(tvSub)
            row.addView(textContainer)

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

            ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)

            row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    row.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                    tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    tvSub.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    tvTitle.isSelected = true
                    tvArrow.visibility = View.VISIBLE
                } else {
                    row.setBackgroundColor(0)
                    tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                    tvSub.setTextColor(ThemeManager.getTextColorSecondary())
                    tvTitle.isSelected = false
                    tvArrow.visibility = View.GONE
                }
            }

            row.setOnClickListener {
                clickFeedback()
                onNavigateToMode(VideoMode.TvSeasons(series.name))
            }

            if (series.posterFile != null && series.posterFile.exists()) {
                loadArtworkIntoImageView(series.posterFile, ivCover)
            } else {
                val firstEp = series.seasons.firstOrNull()?.episodes?.firstOrNull()
                if (firstEp != null) {
                    loadThumbnailIntoImageView(firstEp.file, firstEp.coverFile, ivCover)
                }
            }

            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------------
    // TV Seasons List View
    // ------------------------------------------------------------------------

    private fun buildTvSeasonsListUI(seriesName: String) {
        hideCoverPane()
        updateHeaderTitle(seriesName)
        containerVideoItems.removeAllViews()

        val series = tvSeriesProvider().firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
        val seasons = series?.seasons ?: emptyList()

        if (seasons.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No TV series found.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        val d = activity.resources.displayMetrics.density

        for (season in seasons) {
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

            val textContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvTitle = TextView(activity).apply {
                text = season.seasonTitle
                textSize = 16f
                setTextColor(ThemeManager.getTextColorPrimary())
                typeface = ThemeManager.getCustomFontBold()
                setSingleLine(true)
            }
            textContainer.addView(tvTitle)

            val epWord = if (season.episodes.size == 1) localize("Episode") else localize("Episodes")
            val tvSub = TextView(activity).apply {
                text = "${season.episodes.size} $epWord"
                textSize = 12f
                setTextColor(ThemeManager.getTextColorSecondary())
                setSingleLine(true)
            }
            textContainer.addView(tvSub)
            row.addView(textContainer)

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

            ClassicMenuStyle.apply(row, tvTitle, tvArrow, 48)

            row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    row.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                    tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    tvSub.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                    tvTitle.isSelected = true
                    tvArrow.visibility = View.VISIBLE
                } else {
                    row.setBackgroundColor(0)
                    tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                    tvSub.setTextColor(ThemeManager.getTextColorSecondary())
                    tvTitle.isSelected = false
                    tvArrow.visibility = View.GONE
                }
            }

            row.setOnClickListener {
                clickFeedback()
                onNavigateToMode(VideoMode.TvEpisodes(seriesName, season.seasonNumber))
            }

            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------------
    // TV Episodes List View
    // ------------------------------------------------------------------------

    private fun buildTvEpisodesListUI(seriesName: String, seasonNumber: Int) {
        hideCoverPane()
        val seasonTitle = "${localize("Season")} $seasonNumber"
        updateHeaderTitle("$seriesName - $seasonTitle")
        containerVideoItems.removeAllViews()

        val series = tvSeriesProvider().firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
        val season = series?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }
        val episodes = season?.episodes ?: emptyList()

        if (episodes.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No TV series found.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        for (ep in episodes) {
            val row = createVideoItemRow(ep) {
                clickFeedback()
                onVideoSelected(ep.file)
            }
            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------------
    // By Directory Browser View
    // ------------------------------------------------------------------------

    private fun buildDirectoryListUI(dir: File) {
        hideCoverPane()
        val title = if (StoragePaths.isAnyStorageRoot(dir) || !dir.exists()) {
            localize("By Directory")
        } else {
            dir.name
        }
        updateHeaderTitle(title)
        containerVideoItems.removeAllViews()

        // If at synthetic top-level root, list Movies and Tvshows roots
        if (!dir.exists() || StoragePaths.isAnyStorageRoot(dir)) {
            val roots = ArrayList<File>()
            roots.addAll(StoragePaths.getMoviesDirs())
            roots.addAll(StoragePaths.getTvShowsDirs())

            val hasSec = StoragePaths.hasSecondaryStorage()
            for (r in roots) {
                if (!r.exists()) continue
                val isSec = r.absolutePath.startsWith(StoragePaths.SECONDARY_PATH)
                val label = if (hasSec) {
                    if (isSec) "${r.name} (SD)" else "${r.name} (${localize("Internal Flash")})"
                } else {
                    r.name
                }
                val row = createFolderRow(label) {
                    clickFeedback()
                    onNavigateToMode(VideoMode.Directory(r))
                }
                containerVideoItems.addView(row)
            }

            containerVideoItems.post {
                if (containerVideoItems.childCount > 0) {
                    containerVideoItems.getChildAt(0).requestFocus()
                }
            }
            return
        }

        val files = dir.listFiles()
        if (files == null || files.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No videos found in this folder.")
                textSize = 16f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 0)
            }
            containerVideoItems.addView(tvEmpty)
            return
        }

        val subfolders = ArrayList<File>()
        val videoFiles = ArrayList<File>()

        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                subfolders.add(f)
            } else if (f.isFile) {
                val ext = f.extension.lowercase(Locale.US)
                if (videoExtensions.contains(ext)) {
                    videoFiles.add(f)
                }
            }
        }

        subfolders.sortBy { it.name.lowercase(Locale.US) }
        videoFiles.sortBy { it.name.lowercase(Locale.US) }

        for (sf in subfolders) {
            val row = createFolderRow(sf.name) {
                clickFeedback()
                onNavigateToMode(VideoMode.Directory(sf))
            }
            containerVideoItems.addView(row)
        }

        for (vf in videoFiles) {
            val item = VideoItem(file = vf, title = vf.nameWithoutExtension)
            val row = createVideoItemRow(item) {
                clickFeedback()
                onVideoSelected(vf)
            }
            containerVideoItems.addView(row)
        }

        containerVideoItems.post {
            if (containerVideoItems.childCount > 0) {
                containerVideoItems.getChildAt(0).requestFocus()
            }
        }
    }

    private fun createFolderRow(name: String, onClick: () -> Unit): LinearLayout {
        val d = activity.resources.displayMetrics.density
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

        val iconSize = (22 * d).toInt()
        val ivIcon = ImageView(activity).apply {
            setImageBitmap(TablerIcons.render(TablerIcons.FOLDER, iconSize, ThemeManager.getTextColorPrimary()))
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                rightMargin = (10 * d).toInt()
            }
        }
        row.addView(ivIcon)

        val tvTitle = TextView(activity).apply {
            text = name
            textSize = 16f
            setTextColor(ThemeManager.getTextColorPrimary())
            typeface = ThemeManager.getCustomFontBold()
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
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

        ClassicMenuStyle.apply(row, tvTitle, tvArrow, 48)

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                row.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvTitle.isSelected = true
                tvArrow.visibility = View.VISIBLE
                ivIcon.setImageBitmap(TablerIcons.render(TablerIcons.FOLDER, iconSize, ThemeManager.getListButtonFocusedTextColor()))
            } else {
                row.setBackgroundColor(0)
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvTitle.isSelected = false
                tvArrow.visibility = View.GONE
                ivIcon.setImageBitmap(TablerIcons.render(TablerIcons.FOLDER, iconSize, ThemeManager.getTextColorPrimary()))
            }
        }

        row.setOnClickListener { onClick() }
        return row
    }

    // ------------------------------------------------------------------------
    // Shared Video Row Factory
    // ------------------------------------------------------------------------

    private fun createVideoItemRow(item: VideoItem, onClick: () -> Unit): LinearLayout {
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
            text = item.title
            textSize = 16f
            setTextColor(ThemeManager.getTextColorPrimary())
            typeface = ThemeManager.getCustomFontBold()
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
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

        ClassicMenuStyle.apply(row, tvTitle, tvArrow, 64)

        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                row.setBackgroundColor(ThemeManager.getListButtonFocusedBg())
                tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                tvTitle.isSelected = true
                tvArrow.visibility = View.VISIBLE
            } else {
                row.setBackgroundColor(0)
                tvTitle.setTextColor(ThemeManager.getTextColorPrimary())
                tvTitle.isSelected = false
                tvArrow.visibility = View.GONE
            }
        }

        row.setOnClickListener { onClick() }

        loadThumbnailIntoImageView(item.file, item.coverFile, ivCover)
        return row
    }

    // ------------------------------------------------------------------------
    // Cover Pane & Artwork Helpers
    // ------------------------------------------------------------------------

    private fun showCoverPane() {
        val frame = frameVideoCover ?: return
        frame.visibility = View.VISIBLE
        val density = activity.resources.displayMetrics.density

        layoutVideosLeftPane?.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
        if (ThemeManager.isClassicTheme() && layoutVideosLeftPane != null) {
            val left = layoutVideosLeftPane.layoutParams as? LinearLayout.LayoutParams
            if (left != null) {
                left.width = (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
                left.weight = 0f
                layoutVideosLeftPane.layoutParams = left
            }
        }

        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        if (statusBar != null) {
            val slp = statusBar.layoutParams
            slp.width = (ClassicMenuStyle.PANE_WIDTH_DP * density).toInt()
            statusBar.layoutParams = slp
            statusBar.background = ThemeManager.createStatusBarBackground()
        }


        if (!coverPaneSetUp && ivVideoCover != null) {
            coverPaneSetUp = true
            com.themoon.y1.views.CoverPane.attach(ivVideoCover)
        }
    }

    private fun hideCoverPane() {
        val density = activity.resources.displayMetrics.density
        layoutVideosLeftPane?.setPadding(0, (ClassicMenuStyle.STATUS_HEIGHT_DP * density).toInt(), 0, 0)
        if (ThemeManager.isClassicTheme() && layoutVideosLeftPane != null) {
            val left = layoutVideosLeftPane.layoutParams as? LinearLayout.LayoutParams
            if (left != null) {
                left.width = 0
                left.weight = 1f
                layoutVideosLeftPane.layoutParams = left
            }
        }

        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        if (statusBar != null) {
            val slp = statusBar.layoutParams
            slp.width = ViewGroup.LayoutParams.MATCH_PARENT
            statusBar.layoutParams = slp
            statusBar.background = ThemeManager.createStatusBarBackground()
        }

        frameVideoCover?.visibility = View.GONE
    }

    fun refreshCoverPane() {
        val iv = ivVideoCover ?: return
        val act = activity as? MainActivity ?: return

        // 1. Resolve poster for last played video
        val lastPath = act.prefs.getString("last_played_video_path", null)
        if (!lastPath.isNullOrEmpty()) {
            val f = File(lastPath)
            if (f.exists()) {
                val companion = findCompanionImage(f)
                val seriesPoster = f.parentFile?.let { findFolderPoster(it) }
                val targetArtwork = companion ?: seriesPoster
                if (targetArtwork != null && targetArtwork.exists()) {
                    loadArtworkIntoImageView(targetArtwork, iv)
                    return
                }
                loadThumbnailIntoImageView(f, null, iv)
                return
            }
        }

        // 2. Fallback to first movie poster
        val firstMovie = movieVideosProvider().firstOrNull()
        if (firstMovie != null) {
            if (firstMovie.coverFile != null && firstMovie.coverFile.exists()) {
                loadArtworkIntoImageView(firstMovie.coverFile, iv)
                return
            }
            loadThumbnailIntoImageView(firstMovie.file, null, iv)
            return
        }

        // 3. Fallback to first series poster
        val firstSeries = tvSeriesProvider().firstOrNull()
        if (firstSeries != null && firstSeries.posterFile != null && firstSeries.posterFile.exists()) {
            loadArtworkIntoImageView(firstSeries.posterFile, iv)
            return
        }

        // 4. Default placeholder
        iv.setImageResource(R.drawable.video_circle)
    }

    private fun findCompanionImage(file: File): File? {
        val p = file.parentFile ?: return null
        val base = file.nameWithoutExtension
        val c1 = File(p, "$base.jpg")
        if (c1.exists()) return c1
        val c2 = File(p, "$base.png")
        if (c2.exists()) return c2
        val c3 = File(p, "$base.jpeg")
        if (c3.exists()) return c3
        return null
    }

    private fun findFolderPoster(dir: File): File? {
        val f1 = File(dir, "folder.jpg")
        if (f1.exists()) return f1
        val f2 = File(dir, "poster.jpg")
        if (f2.exists()) return f2
        val f3 = File(dir, "cover.jpg")
        if (f3.exists()) return f3
        val parent = dir.parentFile
        if (parent != null) {
            val pf1 = File(parent, "folder.jpg")
            if (pf1.exists()) return pf1
            val pf2 = File(parent, "poster.jpg")
            if (pf2.exists()) return pf2
        }
        return null
    }

    private fun loadArtworkIntoImageView(imageFile: File, iv: ImageView) {
        val path = imageFile.absolutePath
        val cached = thumbCache.get(path)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }

        Thread {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 128 && opts.outHeight / (sample * 2) >= 128) {
                    sample *= 2
                }
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                val bmp = BitmapFactory.decodeFile(path, opts)
                if (bmp != null) {
                    thumbCache.put(path, bmp)
                    activity.runOnUiThread {
                        iv.setImageBitmap(bmp)
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun loadThumbnailIntoImageView(videoFile: File, companionCover: File?, ivCover: ImageView) {
        val path = videoFile.absolutePath
        val cached = thumbCache.get(path)
        if (cached != null) {
            ivCover.setImageBitmap(cached)
            return
        }

        Thread {
            var bmp: Bitmap? = null

            // 1. Check companion cover
            val cover = companionCover ?: findCompanionImage(videoFile)
            if (cover != null && cover.exists()) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(cover.absolutePath, opts)
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= 96 && opts.outHeight / (sample * 2) >= 96) {
                        sample *= 2
                    }
                    opts.inJustDecodeBounds = false
                    opts.inSampleSize = sample
                    bmp = BitmapFactory.decodeFile(cover.absolutePath, opts)
                } catch (_: Throwable) {
                }
            }

            // 2. Fall back to frame grab
            if (bmp == null) {
                var mmr: MediaMetadataRetriever? = null
                try {
                    mmr = MediaMetadataRetriever()
                    mmr.setDataSource(path)
                    bmp = mmr.getFrameAtTime(2000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Throwable) {
                } finally {
                    try { mmr?.release() } catch (_: Throwable) {}
                }
            }

            if (bmp != null) {
                thumbCache.put(path, bmp)
                activity.runOnUiThread {
                    ivCover.setImageBitmap(bmp)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------------
    // Navigation / Key Handling
    // ------------------------------------------------------------------------

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
