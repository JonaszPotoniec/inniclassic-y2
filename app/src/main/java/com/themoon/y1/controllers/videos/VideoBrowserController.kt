package com.themoon.y1.controllers.videos

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.lists.ListScreen
import com.themoon.y1.lists.ListSpec
import com.themoon.y1.lists.Pane
import com.themoon.y1.lists.RowLook
import com.themoon.y1.lists.RowSpec
import com.themoon.y1.models.VideoItem
import com.themoon.y1.models.TvSeries
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.VideoMode
import java.io.File
import java.util.Locale

/**
 * Controller for Video library browser:
 * - Root menu (Movies, TV Series, By Directory) with the art pane at the right
 * - Movies list with async cover/frame thumbnails
 * - Hierarchical TV Series browser: Series -> Season -> Episode
 * - Folder tree directory browser for Movies and Tvshows
 * - LRU cached cover and video frame loading
 *
 * Each list is a [ListSpec] on one [ListScreen]; the video rows are [RowLook.ART] rows.
 */
class VideoBrowserController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val movieVideosProvider: () -> List<VideoItem>,
    private val tvSeriesProvider: () -> List<TvSeries>,
    private val allVideosProvider: () -> List<File>,
    private val onVideoSelected: (file: File) -> Unit,
    private val onNavigateToMode: (VideoMode) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String,
    private val updateHeaderTitle: (String) -> Unit
) : ScreenController {

    private val layoutVideos: LinearLayout = rootView.findViewById(R.id.layout_videos_mode)
    private val ivVideoCover: ImageView? = rootView.findViewById(R.id.iv_video_cover)
    private val screen = ListScreen(rootView.findViewById(R.id.container_video_items), clickFeedback = clickFeedback)

    /** The art pane of the root menu. */
    private val pane: Pane? = rootView.findViewById<LinearLayout?>(R.id.layout_videos_left_pane)?.let { left ->
        rootView.findViewById<FrameLayout?>(R.id.frame_video_cover)?.let { frame ->
            Pane(activity, left, frame, ivVideoCover) { refreshCoverPane() }
        }
    }

    private val thumbCache = LruCache<String, Bitmap>(50)
    var currentVideoMode: VideoMode = VideoMode.Root

    fun clearThumbCache() {
        thumbCache.evictAll()
    }

    fun isCoverPaneActive(): Boolean = pane?.isShown == true

    fun getCurrentTitle(): String = screen.title ?: localize("Videos")

    override fun title(): String? = screen.title

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
        screen.clear()
    }

    override fun onThemeChanged() {
        buildCurrentModeUI()
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean = screen.wheel(delta, event.eventTime)

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

    private fun show(spec: ListSpec) {
        screen.show(spec)
        updateHeaderTitle(spec.title.orEmpty())
    }

    /** Movies, TV Series, By Directory, with the art of the last video at the right. */
    private fun buildRootVideoMenu() {
        val initialDir = StoragePaths.getMoviesDir().parentFile ?: StoragePaths.getMoviesDir()
        show(ListSpec(localize("Videos"), listOf(
            RowSpec(localize("Movies"), onClick = { onNavigateToMode(VideoMode.Movies) }),
            RowSpec(localize("TV Series"), onClick = { onNavigateToMode(VideoMode.TvShows) }),
            RowSpec(localize("By Directory"), onClick = { onNavigateToMode(VideoMode.Directory(initialDir)) })
        ), pane = pane))
    }

    private fun buildMoviesListUI() {
        show(ListSpec(localize("Movies"), movieVideosProvider().map { videoRow(it) }, empty = localize("No movies found.")))
    }

    private fun buildTvShowsListUI() {
        val rows = tvSeriesProvider().map { series ->
            val seasonWord = if (series.seasons.size == 1) localize("Season") else localize("Seasons")
            val epWord = if (series.totalEpisodes == 1) localize("Episode") else localize("Episodes")
            RowSpec(series.name, RowLook.ART, value = "${series.seasons.size} $seasonWord • ${series.totalEpisodes} $epWord",
                image = { iv ->
                    iv.setImageResource(R.drawable.video_circle)
                    if (series.posterFile != null && series.posterFile.exists()) {
                        loadArtworkIntoImageView(series.posterFile, iv)
                    } else {
                        val firstEp = series.seasons.firstOrNull()?.episodes?.firstOrNull()
                        if (firstEp != null) loadThumbnailIntoImageView(firstEp.file, firstEp.coverFile, iv)
                    }
                },
                onClick = { onNavigateToMode(VideoMode.TvSeasons(series.name)) })
        }
        show(ListSpec(localize("TV Series"), rows, empty = localize("No TV series found.")))
    }

    private fun buildTvSeasonsListUI(seriesName: String) {
        val series = tvSeriesProvider().firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
        val rows = (series?.seasons ?: emptyList()).map { season ->
            val epWord = if (season.episodes.size == 1) localize("Episode") else localize("Episodes")
            RowSpec(season.seasonTitle, RowLook.ART, value = "${season.episodes.size} $epWord",
                onClick = { onNavigateToMode(VideoMode.TvEpisodes(seriesName, season.seasonNumber)) })
        }
        show(ListSpec(seriesName, rows, empty = localize("No TV series found.")))
    }

    private fun buildTvEpisodesListUI(seriesName: String, seasonNumber: Int) {
        val series = tvSeriesProvider().firstOrNull { it.name.equals(seriesName, ignoreCase = true) }
        val season = series?.seasons?.firstOrNull { it.seasonNumber == seasonNumber }
        show(ListSpec("$seriesName - ${localize("Season")} $seasonNumber", (season?.episodes ?: emptyList()).map { videoRow(it) },
            empty = localize("No TV series found.")))
    }

    /** By Directory: the Movies and TV Shows folders, then in a folder its folders and videos (A to Z). */
    private fun buildDirectoryListUI(dir: File) {
        val title = if (StoragePaths.isAnyStorageRoot(dir) || !dir.exists()) {
            localize("By Directory")
        } else {
            dir.name
        }

        // If at synthetic top-level root, list Movies and Tvshows roots
        if (!dir.exists() || StoragePaths.isAnyStorageRoot(dir)) {
            val roots = ArrayList<File>()
            roots.addAll(StoragePaths.getMoviesDirs())
            roots.addAll(StoragePaths.getTvShowsDirs())

            val hasSec = StoragePaths.hasSecondaryStorage()
            val rows = ArrayList<RowSpec>()
            for (r in roots) {
                if (!r.exists()) continue
                val isSec = r.absolutePath.startsWith(StoragePaths.SECONDARY_PATH)
                val label = if (hasSec) {
                    if (isSec) "${r.name} (SD)" else "${r.name} (${localize("Internal Flash")})"
                } else {
                    r.name
                }
                rows += RowSpec(label, key = r.absolutePath, onClick = { onNavigateToMode(VideoMode.Directory(r)) })
            }
            show(ListSpec(title, rows))
            return
        }

        val subfolders = ArrayList<File>()
        val videoFiles = ArrayList<File>()

        for (f in dir.listFiles() ?: emptyArray()) {
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

        val rows = ArrayList<RowSpec>()
        for (sf in subfolders) {
            rows += RowSpec(sf.name, key = sf.absolutePath, onClick = { onNavigateToMode(VideoMode.Directory(sf)) })
        }
        for (vf in videoFiles) rows += videoRow(VideoItem(file = vf, title = vf.nameWithoutExtension))
        show(ListSpec(title, rows, empty = localize("No videos found in this folder.")))
    }

    /** A movie, an episode or a video file: its cover (or a frame of the video) and its title. */
    private fun videoRow(item: VideoItem) = RowSpec(item.title, RowLook.ART, key = item.file.absolutePath,
        image = { iv ->
            iv.setImageResource(R.drawable.video_circle)
            loadThumbnailIntoImageView(item.file, item.coverFile, iv)
        },
        onClick = { onVideoSelected(item.file) })

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
}
