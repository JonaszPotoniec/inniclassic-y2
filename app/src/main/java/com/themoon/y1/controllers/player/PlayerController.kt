package com.themoon.y1.controllers.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.TimeFormat
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import androidx.palette.graphics.Palette
import com.themoon.y1.views.AudioVisualizerView
import com.themoon.y1.views.SpectrumColors
import java.io.File

/**
 * Controller for Now Playing view:
 * - Album art rendering with 3D tilt and reflection effect
 * - Track metadata, playback timing, and progress bar updates
 * - Bottom widget state cycling: Progress -> Lyrics (if any) -> Scrub -> Shuffle/Repeat -> Rating
 * - Inline volume pill overlay and wheel navigation routing
 * - Integration with LyricsOverlayController and NowPlayingContextMenu
 */
class PlayerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onAdjustVolume: (up: Boolean) -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onBrowseAlbum: (albumName: String) -> Unit,
    private val onBrowseArtist: (artistName: String) -> Unit,
    private val onToggleFavorite: () -> Unit,
    private val onAddToOnTheGo: (file: File) -> Unit,
    private val onAddToPlaylist: (file: File) -> Unit,
    private val onToggleVisualizer: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutPlayerMode: View = rootView.findViewById(R.id.layout_player_mode)
    private val tvPlayerTitle: TextView = rootView.findViewById(R.id.tv_player_title)
    private val tvPlayerArtist: TextView = rootView.findViewById(R.id.tv_player_artist)
    private val tvPlayerAlbum: TextView = rootView.findViewById(R.id.tv_player_album)
    private val tvPlayerRating: TextView? = rootView.findViewById(R.id.tv_player_rating)
    private val tvPlayerTrackCount: TextView? = rootView.findViewById(R.id.tv_player_track_count)
    private val tvPlayerTimeCurrent: TextView = rootView.findViewById(R.id.tv_player_time_current)
    private val tvPlayerTimeTotal: TextView = rootView.findViewById(R.id.tv_player_time_total)
    private val playerProgress: ProgressBar = rootView.findViewById(R.id.player_progress)

    private val ivAlbumArt: ImageView = rootView.findViewById(R.id.iv_album_art)
    private val ivAlbumReflection: ImageView? = rootView.findViewById(R.id.iv_album_reflection)
    private val frameAlbumArt: FrameLayout? = rootView.findViewById(R.id.frame_album_art)
    private val frameAlbumReflection: FrameLayout? = rootView.findViewById(R.id.frame_album_reflection)
    private val ivPauseOverlay: ImageView? = rootView.findViewById(R.id.iv_pause_overlay)
    private val visualizerView = AudioVisualizerView(activity).apply { visibility = View.GONE }
    private var visualizerApplied = false
    // Spectrum colour: main colour of the cover (Palette), cached per track path
    private var spectrumArt: Bitmap? = null
    private var spectrumCoverColor: Int? = null
    private val spectrumColorCache = LruCache<String, Int>(64)
    private val noCoverColor = 0 // cache value for "cover has no usable colour" (swatch colours are opaque)

    private val ivPlayerShuffleStatus: ImageView? = rootView.findViewById(R.id.iv_player_shuffle_status)
    private val ivPlayerRepeatStatus: ImageView? = rootView.findViewById(R.id.iv_player_repeat_status)

    private val rowPlayerProgress: LinearLayout? = rootView.findViewById(R.id.row_player_progress)
    private val rowPlayerVolume: LinearLayout? = rootView.findViewById(R.id.row_player_volume)
    private val playerVolumeProgress: ProgressBar? = rootView.findViewById(R.id.player_volume_progress)

    private val rowPlayerSeek: LinearLayout? = rootView.findViewById(R.id.row_player_seek)
    private val tvPlayerSeekCurrent: TextView? = rootView.findViewById(R.id.tv_player_seek_current)
    private val tvPlayerSeekTotal: TextView? = rootView.findViewById(R.id.tv_player_seek_total)
    private val seekPlayerScrub: SeekBar? = rootView.findViewById(R.id.seek_player_scrub)

    private val rowPlayerShuffleRepeat: LinearLayout? = rootView.findViewById(R.id.row_player_shuffle_repeat)
    private val tvScrubShuffleValue: TextView? = rootView.findViewById(R.id.tv_scrub_shuffle_value)
    private val tvScrubRepeatValue: TextView? = rootView.findViewById(R.id.tv_scrub_repeat_value)

    private val rowPlayerRatingScrub: LinearLayout? = rootView.findViewById(R.id.row_player_rating_scrub)
    private val tvScrubStars = arrayOfNulls<TextView>(5)

    val lyricsOverlayController: LyricsOverlayController by lazy {
        LyricsOverlayController(
            activity,
            layoutPlayerMode as ViewGroup,
            // The art and the song text (the block that holds both) hide while the lyrics show, and also the
            // shuffle/repeat icons: the lyrics start directly under the status bar
            listOfNotNull(
                frameAlbumArt?.parent?.parent?.parent as? View ?: frameAlbumArt,
                ivPlayerShuffleStatus?.parent?.parent as? View
            ),
            region = { lyricsRegion() }
        )
    }

    /**
     * Top and bottom of the full-width lyrics, in layout_player_mode coordinates: directly under the status bar (the
     * top fade starts there), and a small gap above the progress bar. Null before the first layout.
     */
    private fun lyricsRegion(): Pair<Int, Int>? {
        val player = layoutPlayerMode
        val bar = playerProgress
        if (player.height == 0 || bar.height == 0) return null
        val playerPos = IntArray(2).also { player.getLocationInWindow(it) }
        var top = 0
        activity.findViewById<View>(R.id.layout_status_bar)?.takeIf { it.isShown }?.let { status ->
            val pos = IntArray(2).also { status.getLocationInWindow(it) }
            top = Math.max(top, pos[1] + status.height - playerPos[1])
        }
        val gap = (LyricsScrollView.BOTTOM_GAP_DP * activity.resources.displayMetrics.density).toInt()
        val barPos = IntArray(2).also { bar.getLocationInWindow(it) }
        return Pair(top, barPos[1] - playerPos[1] - gap)
    }

    var nowPlayingBottomState: Int = 0
    var pendingSeekChanged: Boolean = false
    var pendingSeekPosition: Int = 0

    private var displayedPlayerPath: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private var lastCenterUpTime: Long = 0
    private val doubleClickHandler = Handler(Looper.getMainLooper())
    private val singleClickRunnable = Runnable { cycleBottomState() }

    private val updateProgressTask = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            if (AudioPlayerManager.getInstance().isPlaying()) {
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    init {
        tvPlayerTitle.isSelected = true
        val density = activity.resources.displayMetrics.density
        frameAlbumArt?.setCameraDistance(density * 3500f)
        frameAlbumReflection?.let {
            it.setCameraDistance(density * 3500f)
            it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            // Use the cover's centre as the tilt pivot, so both share one perspective and the
            // reflection mirrors the tilted cover (with its own centre, a wedge-shaped gap shows)
            val coverLp = frameAlbumArt?.layoutParams
            val reflectionLp = it.layoutParams as? ViewGroup.MarginLayoutParams
            if (coverLp != null && reflectionLp != null && coverLp.height > 0 && reflectionLp.width > 0) {
                it.pivotX = reflectionLp.width / 2f
                it.pivotY = -(coverLp.height / 2f + reflectionLp.topMargin)
            }
        }

        // Spectrum takes the album art slot; same width as the art, so the column keeps its width
        (frameAlbumArt?.parent as? LinearLayout)?.let { column ->
            val visWidth = frameAlbumArt.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT
            val visLp = LinearLayout.LayoutParams(visWidth, (190 * density).toInt())
            visLp.bottomMargin = (8 * density).toInt()
            column.addView(visualizerView, 0, visLp)
        }

        tvScrubStars[0] = rootView.findViewById(R.id.tv_scrub_star_1)
        tvScrubStars[1] = rootView.findViewById(R.id.tv_scrub_star_2)
        tvScrubStars[2] = rootView.findViewById(R.id.tv_scrub_star_3)
        tvScrubStars[3] = rootView.findViewById(R.id.tv_scrub_star_4)
        tvScrubStars[4] = rootView.findViewById(R.id.tv_scrub_star_5)

        // Smooth tilted edges: the reflection is cut and faded in its bitmap, not by a software layer
        (ivAlbumReflection as? com.themoon.y1.views.SmoothEdgeImageView)?.useAsReflection()
        applyAlbumTiltSetting()
    }

    override fun onEnter(state: ScreenState) {
        layoutPlayerMode.visibility = View.VISIBLE
        resetBottomState()
        applyThemeColors()
        updatePlayerUI()
        refreshVisualizerState()
        startProgressUpdates()
    }

    override fun onExit() {
        progressHandler.removeCallbacks(updateProgressTask)
        layoutPlayerMode.visibility = View.GONE
        resetBottomState()
        refreshVisualizerCapture()
    }

    /** Now Playing always opens in the default state (Progress); a pending seek is discarded. */
    private fun resetBottomState() {
        pendingSeekChanged = false
        nowPlayingBottomState = 0
        lyricsOverlayController.hide()
        updateNowPlayingBottomBarState()
    }

    override fun onRefreshUI() {
        updatePlayerUI()
    }

    fun applyAlbumTiltSetting() {
        val tilt = AlbumTiltSetting.rotationY(AlbumTiltSetting.isEnabled(activity))
        frameAlbumArt?.rotationY = tilt
        frameAlbumReflection?.rotationY = tilt
    }

    fun applyThemeColors() {
        // Classic Light keeps the iPod colours; Classic Dark and the other themes use the theme colours
        val classic = ThemeManager.isClassicLightTheme()
        if (classic) {
            layoutPlayerMode.setBackgroundColor(0xFFFFFFFF.toInt())
            tvPlayerTitle.setTextColor(0xFF000000.toInt())
            tvPlayerArtist.setTextColor(0xFF3C3C43.toInt())
            tvPlayerAlbum.setTextColor(0xFF3C3C43.toInt())
            tvPlayerTimeCurrent.setTextColor(0xFF000000.toInt())
            tvPlayerTimeTotal.setTextColor(0xFF000000.toInt())
        } else {
            layoutPlayerMode.setBackgroundColor(ThemeManager.getOverlayBackgroundColor())
            tvPlayerTitle.setTextColor(ThemeManager.getTextColorPrimary())
            tvPlayerArtist.setTextColor(ThemeManager.getTextColorSecondary())
            tvPlayerAlbum.setTextColor(ThemeManager.getTextColorSecondary())
            tvPlayerTimeCurrent.setTextColor(ThemeManager.getTextColorPrimary())
            tvPlayerTimeTotal.setTextColor(ThemeManager.getTextColorPrimary())
        }
        NowPlayingTheme.apply(rootView)
        applyAlbumTiltSetting()
        lyricsOverlayController.onThemeChanged()
    }

    fun updatePlayerUI() {
        val main = MainActivity.instance ?: return
        val playlist = main.currentPlaylist
        val index = main.currentIndex

        if (playlist.isNotEmpty() && index in playlist.indices) {
            val file = playlist[index]
            val path = file.absolutePath

            if (path != displayedPlayerPath) {
                displayedPlayerPath = path
                val wasShowingLyrics = nowPlayingBottomState == LYRICS_STATE
                // The lyrics load on their own thread: an open Lyrics view stays open (empty) until they are in place
                lyricsOverlayController.loadLyrics(file) { onLyricsLoaded() }
                updateRatingDisplay()
                pendingSeekChanged = false
                nowPlayingBottomState = if (wasShowingLyrics) LYRICS_STATE else 0
                updateNowPlayingBottomBarState()
            }

            val totalCount = playlist.size
            tvPlayerTrackCount?.text = "${index + 1} of $totalCount"
        }

        val am = AudioPlayerManager.getInstance()
        val pos = am.getCurrentPosition()
        val dur = am.getDuration()

        tvPlayerTimeCurrent.text = formatTime(pos)
        tvPlayerTimeTotal.text = rightTimeText(pos, dur)

        if (dur > 0) {
            playerProgress.progress = (pos.toLong() * 1000 / dur).toInt()
        } else {
            playerProgress.progress = 0
        }

        if (am.isPlaying()) {
            ivAlbumArt.alpha = 1.0f
            ivPauseOverlay?.visibility = View.GONE
            // The progress loop stops while paused, so restart it on resume or a delayed start
            if (layoutPlayerMode.visibility == View.VISIBLE) startProgressUpdates()
        } else {
            ivAlbumArt.alpha = if (ThemeManager.isClassicTheme()) 1f else 0.4f
            ivPauseOverlay?.visibility = if (ThemeManager.isClassicTheme()) View.GONE else View.VISIBLE
        }

        if (ivAlbumReflection != null && ivAlbumArt.drawable != null) {
            ivAlbumReflection.setImageDrawable(ivAlbumArt.drawable)
        }

        updateShuffleRepeatStatusIcons()
    }

    /** The lyrics of the new track are in place: an open Lyrics view shows them, or closes if the track has none. */
    private fun onLyricsLoaded() {
        if (nowPlayingBottomState != LYRICS_STATE) return
        if (lyricsOverlayController.hasLyrics) {
            setLyricsShowing(true)
        } else {
            nowPlayingBottomState = 0
            setLyricsShowing(false)
            updateNowPlayingBottomBarState()
        }
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(updateProgressTask)
        progressHandler.post(updateProgressTask)
    }

    fun updatePlaybackProgress() {
        val am = AudioPlayerManager.getInstance()
        val pos = am.getCurrentPosition()
        val dur = am.getDuration()

        if (nowPlayingBottomState == 1 && !pendingSeekChanged) {
            tvPlayerSeekCurrent?.text = formatTime(pos)
            tvPlayerSeekTotal?.text = rightTimeText(pos, dur)
            seekPlayerScrub?.progress = pos
        } else if (nowPlayingBottomState == 0 || nowPlayingBottomState == LYRICS_STATE) {
            tvPlayerTimeCurrent.text = formatTime(pos)
            tvPlayerTimeTotal.text = rightTimeText(pos, dur)
            if (dur > 0) {
                playerProgress.progress = (pos.toLong() * 1000 / dur).toInt()
            }
        }
        lyricsOverlayController.updateProgress(pos, dur, am.isPlaying())
    }

    /**
     * The volume row replaces the bottom row, as on the iPod classic (VolumeBar hides it again).
     * [leftIcon] is the small speaker, or the Play On icon for a speaker. False if the row is not in the layout.
     */
    fun showVolume(level: Int, max: Int, leftIcon: Int): Boolean {
        val bar = playerVolumeProgress ?: return false
        bar.max = max
        bar.progress = level
        rootView.findViewById<ImageView>(R.id.tv_player_volume_down)?.let {
            if (it.getTag(R.id.player_volume_progress) != leftIcon) {
                it.setImageResource(leftIcon)
                it.setTag(R.id.player_volume_progress, leftIcon)
            }
        }
        if (!volumeShowing) {
            volumeShowing = true
            updateNowPlayingBottomBarState()
        }
        return true
    }

    fun hideVolume() {
        if (!volumeShowing) return
        volumeShowing = false
        updateNowPlayingBottomBarState()
    }

    private var volumeShowing = false

    fun updateNowPlayingBottomBarState() {
        val state = if (volumeShowing) VOLUME_STATE else nowPlayingBottomState
        rowPlayerVolume?.visibility = if (state == VOLUME_STATE) View.VISIBLE else View.GONE
        rowPlayerProgress?.visibility = if (state == 0 || state == LYRICS_STATE) View.VISIBLE else View.GONE
        rowPlayerSeek?.visibility = if (state == 1) View.VISIBLE else View.GONE
        rowPlayerShuffleRepeat?.visibility = if (state == 2) View.VISIBLE else View.GONE
        rowPlayerRatingScrub?.visibility = if (state == 3) View.VISIBLE else View.GONE

        val am = AudioPlayerManager.getInstance()
        when (nowPlayingBottomState) {
            1 -> {
                val current = if (pendingSeekChanged) pendingSeekPosition else am.getCurrentPosition()
                val duration = am.getDuration()
                tvPlayerSeekCurrent?.text = formatTime(current)
                tvPlayerSeekTotal?.text = rightTimeText(current, duration)
                seekPlayerScrub?.max = if (duration > 0) duration else 1000
                seekPlayerScrub?.progress = current
            }
            2 -> updateShuffleRepeatScrubLabels()
            3 -> updateRatingScrubStars()
        }
        // A bottom state may have shown or hidden the lyrics overlay in the album art slot
        refreshVisualizerState()
    }

    fun handleNowPlayingWheelInput(up: Boolean): Boolean {
        when (nowPlayingBottomState) {
            1 -> {
                val am = AudioPlayerManager.getInstance()
                if (!pendingSeekChanged) pendingSeekPosition = am.getCurrentPosition()
                val delta = if (up) 5000 else -5000
                pendingSeekPosition = Math.max(0, Math.min(am.getDuration(), pendingSeekPosition + delta))
                pendingSeekChanged = true
                updateNowPlayingBottomBarState()
            }
            2 -> {
                if (up) {
                    cycleRepeatMode()
                } else {
                    toggleShuffleMode()
                }
                updateShuffleRepeatScrubLabels()
            }
            3 -> {
                val main = MainActivity.instance ?: return true
                val playlist = main.currentPlaylist
                val index = main.currentIndex
                if (playlist.isNotEmpty() && index in playlist.indices) {
                    val path = playlist[index].absolutePath
                    var rating = main.getSongRating(path)
                    rating = if (up) Math.min(5, rating + 1) else Math.max(0, rating - 1)
                    main.setSongRating(path, rating)
                    updateRatingScrubStars()
                    // The stars under the song details show the rating too; before, they changed only with the song.
                    updateRatingDisplay()
                }
            }
            LYRICS_STATE -> lyricsOverlayController.scrollLines(if (up) 1 else -1)
            else -> onAdjustVolume(up)
        }
        clickFeedback()
        return true
    }

    fun cycleBottomState() {
        val am = AudioPlayerManager.getInstance()
        if (nowPlayingBottomState == 1 && pendingSeekChanged) {
            am.seekRelative(pendingSeekPosition - am.getCurrentPosition())
            pendingSeekChanged = false
        }
        val previousState = nowPlayingBottomState
        nowPlayingBottomState = nextBottomState(nowPlayingBottomState, lyricsOverlayController.hasLyrics)
        if (nowPlayingBottomState == 1) {
            pendingSeekPosition = am.getCurrentPosition()
            pendingSeekChanged = false
        }
        if (nowPlayingBottomState == LYRICS_STATE) {
            setLyricsShowing(true)
        } else if (previousState == LYRICS_STATE) {
            setLyricsShowing(false)
        }
        updateNowPlayingBottomBarState()
        clickFeedback()
    }

    private fun setLyricsShowing(showing: Boolean) {
        if (showing) {
            lyricsOverlayController.show()
            val am = AudioPlayerManager.getInstance()
            lyricsOverlayController.updateProgress(am.getCurrentPosition(), am.getDuration(), am.isPlaying())
        } else {
            lyricsOverlayController.hide()
        }
        refreshVisualizerState()
    }

    private fun updateShuffleRepeatScrubLabels() {
        val prefs = playbackPrefs()
        val isShuffle = prefs.getBoolean("shuffle", false)
        val repeatMode = prefs.getInt("repeat_mode", 0)

        val repeatText = when (repeatMode) {
            1 -> localize("One")
            2 -> localize("All")
            else -> localize("Off")
        }

        tvScrubShuffleValue?.text = "${localize("Shuffle")}: ${if (isShuffle) localize("Songs") else localize("Off")}"
        tvScrubRepeatValue?.text = "${localize("Repeat")}: $repeatText"
    }

    private fun updateRatingScrubStars() {
        val main = MainActivity.instance ?: return
        val playlist = main.currentPlaylist
        val index = main.currentIndex
        if (playlist.isEmpty() || index !in playlist.indices) return

        val path = playlist[index].absolutePath
        val rating = main.getSongRating(path)
        for (i in tvScrubStars.indices) {
            val star = tvScrubStars[i] ?: continue
            star.text = if (i < rating) "★" else "•"
            star.setTextColor(ThemeManager.getTextColorPrimary())
        }
    }

    private fun updateRatingDisplay() {
        val main = MainActivity.instance ?: return
        val playlist = main.currentPlaylist
        val index = main.currentIndex
        if (playlist.isEmpty() || index !in playlist.indices) {
            tvPlayerRating?.visibility = View.GONE
            return
        }

        val rating = main.getSongRating(playlist[index].absolutePath)
        if (rating <= 0) {
            tvPlayerRating?.visibility = View.GONE
            return
        }

        val sb = java.lang.StringBuilder()
        for (i in 0 until 5) {
            sb.append(if (i < rating) "★" else "☆")
        }
        tvPlayerRating?.text = sb.toString()
        tvPlayerRating?.visibility = View.VISIBLE
    }

    private fun toggleShuffleMode() {
        val prefs = playbackPrefs()
        val isShuffle = !prefs.getBoolean("shuffle", false)
        prefs.edit().putBoolean("shuffle", isShuffle).commit()
        MainActivity.instance?.isShuffleMode = isShuffle
        AudioPlayerManager.getInstance().setShuffleMode(isShuffle)
        updateShuffleRepeatStatusIcons()
    }

    private fun cycleRepeatMode() {
        val prefs = playbackPrefs()
        val repeatMode = (prefs.getInt("repeat_mode", 0) + 1) % 3
        prefs.edit().putInt("repeat_mode", repeatMode).commit()
        MainActivity.instance?.repeatMode = repeatMode
        AudioPlayerManager.getInstance().setRepeatMode(repeatMode)
        updateShuffleRepeatStatusIcons()
    }

    // Same store as Settings and AudioPlayerManager, so all screens share one shuffle/repeat state.
    private fun playbackPrefs() = activity.getSharedPreferences("AppPrefs", Activity.MODE_PRIVATE)

    private fun updateShuffleRepeatStatusIcons() {
        val prefs = playbackPrefs()
        val isShuffle = prefs.getBoolean("shuffle", false)
        val repeatMode = prefs.getInt("repeat_mode", 0)

        ivPlayerShuffleStatus?.visibility = if (isShuffle) View.VISIBLE else View.GONE
        ivPlayerRepeatStatus?.visibility = if (repeatMode > 0) View.VISIBLE else View.GONE
    }

    fun showContextMenu() {
        val main = MainActivity.instance ?: return
        val playlist = main.currentPlaylist
        val index = main.currentIndex
        if (playlist.isEmpty() || index !in playlist.indices) return

        val file = playlist[index]
        val isFav = main.favoritePaths.contains(file.absolutePath)
        val songItem = MainActivity.customLibrary.firstOrNull { it.file.absolutePath == file.absolutePath }

        NowPlayingContextMenu.show(
            songFile = file,
            songItem = songItem,
            isFavorite = isFav,
            onToggleFavorite = onToggleFavorite,
            onAddToOnTheGo = onAddToOnTheGo,
            onAddToPlaylist = onAddToPlaylist,
            onBrowseAlbum = onBrowseAlbum,
            onBrowseArtist = onBrowseArtist,
            localize = localize
        )
    }

    /**
     * Spectrum on: the spectrum replaces the album art and its reflection. Spectrum off: album art.
     * While the lyrics overlay is showing it owns the album art slot, so the spectrum hides
     * and comes back when the lyrics are hidden. Runs on toggle, on entering Now Playing and with
     * every bottom state update (which also covers track changes and showing or hiding lyrics).
     */
    fun refreshVisualizerState() {
        val main = MainActivity.instance ?: return
        val isOn = main.isVisualizerShowing
        val lyricsShowing = lyricsOverlayController.isShowing()
        val showSpectrum = isOn && !lyricsShowing

        // Only undo what this mode changed; the lyrics overlay manages the album art while it shows
        if (!lyricsShowing) {
            val albumVisibility = if (isOn) View.GONE else if (visualizerApplied) View.VISIBLE else null
            if (albumVisibility != null) {
                frameAlbumArt?.visibility = albumVisibility
                frameAlbumReflection?.visibility = albumVisibility
            }
        }
        if (showSpectrum) {
            // The width of the art now (a layout can change it after init), so the text column stays in place
            val artWidth = frameAlbumArt?.layoutParams?.width
            val lp = visualizerView.layoutParams
            if (artWidth != null && artWidth > 0 && lp != null && lp.width != artWidth) {
                lp.width = artWidth
                visualizerView.layoutParams = lp
            }
        }
        visualizerView.visibility = if (showSpectrum) View.VISIBLE else View.GONE
        visualizerApplied = isOn
        refreshVisualizerCapture()
    }

    /**
     * Starts or stops the audio capture of the visualizer, and so its thread. Runs at each change of a condition
     * of [NowPlayingSpectrum.shouldCapture], and when the player gets a visualizer. Keeps the Visualizer object:
     * only a new audio session makes a new one.
     */
    fun refreshVisualizerCapture() {
        val main = MainActivity.instance ?: return
        val visualizer = main.audioVisualizer ?: return
        val capture = NowPlayingSpectrum.shouldCapture(
            spectrumOn = main.isVisualizerShowing,
            nowPlayingShown = layoutPlayerMode.visibility == View.VISIBLE,
            lyricsShowing = lyricsOverlayController.isShowing(),
            onScreen = main.isOnScreen
        )
        try {
            if (visualizer.enabled != capture) visualizer.enabled = capture
        } catch (_: Exception) {}
    }

    fun updateVisualizer(fft: ByteArray?) {
        if (visualizerView.visibility == View.VISIBLE) {
            val gain = AudioPlayerManager.getInstance().getMasterVolume()
            visualizerView.updateVisualizer(fft, spectrumColor(), com.themoon.y1.views.SpectrumLevel.gainCompensation(gain))
        }
    }

    /** Cover colour with enough contrast for the Now Playing background, or the theme accent. */
    private fun spectrumColor(): Int {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val art = (ivAlbumArt.drawable as? BitmapDrawable)?.bitmap
            // While the cover of a new track loads, the default cover shows: keep the last colour until the cover shows
            if (art !== spectrumArt && !AudioPlayerManager.getInstance().isArtLoading()) {
                spectrumArt = art
                if (art != null && !art.isRecycled) pickCoverColor(art)
            }
        }
        // The colour that Now Playing really shows (applyThemeColors), also after a theme change without a restart
        val background = (layoutPlayerMode.background as? android.graphics.drawable.ColorDrawable)?.color
            ?: if (ThemeManager.isClassicTheme()) 0xFFFFFFFF.toInt() else ThemeManager.getOverlayBackgroundColor()
        val accent = ThemeManager.getListButtonFocusedBg() or 0xFF000000.toInt()
        return SpectrumColors.spectrumColor(spectrumCoverColor, background, accent)
    }

    private fun pickCoverColor(art: Bitmap) {
        val main = MainActivity.instance ?: return
        val path = main.currentPlaylist.getOrNull(main.currentIndex)?.absolutePath ?: return
        val cached = spectrumColorCache.get(path)
        if (cached != null) {
            spectrumCoverColor = if (cached == noCoverColor) null else cached
            return
        }
        // Palette works on a small copy, off the main thread
        val small = try {
            Bitmap.createScaledBitmap(art, 48, 48, true)
        } catch (_: Exception) {
            return
        }
        Palette.from(small).maximumColorCount(16).generate { palette ->
            val swatch = palette?.vibrantSwatch ?: palette?.darkVibrantSwatch
                ?: palette?.lightVibrantSwatch ?: palette?.dominantSwatch
            val color = swatch?.rgb
            spectrumColorCache.put(path, color ?: noCoverColor)
            if (spectrumArt === art) spectrumCoverColor = color
        }
    }

    private fun isTotalTimeShown(): Boolean =
        activity.getSharedPreferences("AppPrefs", Activity.MODE_PRIVATE).getBoolean(NowPlayingTime.PREF_SHOW_TOTAL_TIME, false)

    /** Right-hand time of the progress and scrub rows; AudioPlayerManager uses it too. */
    fun rightTimeText(positionMs: Int, durationMs: Int): String =
        NowPlayingTime.rightText(positionMs, durationMs, isTotalTimeShown(), ::formatTime)

    private fun formatTime(ms: Int): String {
        return TimeFormat.format(ms)
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        handleNowPlayingWheelInput(delta > 0)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, 23, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    MainActivity.instance?.isLongPressConsumed = false
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 85 -> {
                if (event.repeatCount == 0) {
                    MainActivity.instance?.isMediaLongPressConsumed = false
                    event.startTracking()
                }
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = MainActivity.instance
            if (main != null && main.isLongPressConsumed) {
                // The hold opened the Now Playing menu, so skip the short-press action
                main.isLongPressConsumed = false
                return true
            }
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                val prefs = activity.getSharedPreferences("AppPrefs", Activity.MODE_PRIVATE)
                if (!prefs.getBoolean("double_click_favorite", true)) {
                    // No double-click to wait for, so act on the single press at once.
                    doubleClickHandler.removeCallbacks(singleClickRunnable)
                    lastCenterUpTime = 0
                    cycleBottomState()
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastCenterUpTime < 300) {
                    doubleClickHandler.removeCallbacks(singleClickRunnable)
                    lastCenterUpTime = 0
                    clickFeedback()
                    onToggleFavorite()
                } else {
                    lastCenterUpTime = now
                    doubleClickHandler.postDelayed(singleClickRunnable, 300)
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85) {
            val main = MainActivity.instance
            if (main != null && main.isMediaLongPressConsumed) {
                main.isMediaLongPressConsumed = false
                return true
            }
            clickFeedback()
            onTogglePlayback()
            updatePlayerUI()
            return true
        }
        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = MainActivity.instance
            if (main != null && main.currentPlaylist.isNotEmpty() && main.currentIndex in main.currentPlaylist.indices) {
                clickFeedback()
                main.isLongPressConsumed = true
                doubleClickHandler.removeCallbacks(singleClickRunnable)
                lastCenterUpTime = 0
                showContextMenu()
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86 || keyCode == 126 || keyCode == 127) {
            val main = MainActivity.instance
            if (main != null && main.currentPlaylist.isNotEmpty() && main.currentIndex in main.currentPlaylist.indices) {
                clickFeedback()
                main.isMediaLongPressConsumed = true
                // Hold Play/Pause = Stop (the button is printed play/pause/stop)
                AudioPlayerManager.getInstance().stopMusic()
                return true
            }
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        // Back from Lyrics, Scrub, Shuffle/Repeat or Rating returns to Progress; a pending seek is discarded
        if (nowPlayingBottomState != 0) {
            if (nowPlayingBottomState == LYRICS_STATE) setLyricsShowing(false)
            pendingSeekChanged = false
            nowPlayingBottomState = 0
            updateNowPlayingBottomBarState()
            return true
        }
        if (lyricsOverlayController.hasLyrics && lyricsOverlayController.lyricScrollView.visibility == View.VISIBLE) {
            lyricsOverlayController.hide()
            refreshVisualizerState()
            return true
        }
        onNavigateBack()
        return true
    }

    companion object {
        const val LYRICS_STATE = 4

        /** The volume row, for a moment over any bottom state ([showVolume]); never a value of nowPlayingBottomState. */
        const val VOLUME_STATE = -1

        /** Centre press order: Progress -> Lyrics (only if the track has lyrics) -> Scrub -> Shuffle/Repeat -> Rating. */
        fun nextBottomState(current: Int, hasLyrics: Boolean): Int = when (current) {
            0 -> if (hasLyrics) LYRICS_STATE else 1
            LYRICS_STATE -> 1
            3 -> 0
            else -> current + 1
        }
    }
}
