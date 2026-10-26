package com.themoon.y1.controllers.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import java.io.File
import java.util.Locale

/**
 * Controller for Now Playing view:
 * - Album art rendering with 3D tilt and reflection effect
 * - Track metadata, playback timing, and progress bar updates
 * - Bottom widget 4-state cycling: Progress -> Scrub -> Shuffle/Repeat -> Rating
 * - Inline volume pill overlay and wheel navigation routing
 * - Integration with LyricsOverlayController and NowPlayingContextMenu
 */
class PlayerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val clickFeedback: () -> Unit,
    private val onAdjustVolume: (up: Boolean) -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val onNextTrack: () -> Unit,
    private val onPreviousTrack: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onBrowseAlbum: (albumName: String) -> Unit,
    private val onBrowseArtist: (artistName: String) -> Unit,
    private val onToggleFavorite: () -> Unit,
    private val onAddToOnTheGo: (file: File) -> Unit,
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
    private val ivPlayerBgBlur: ImageView? = rootView.findViewById(R.id.iv_player_bg_blur)

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
        LyricsOverlayController(activity, layoutPlayerMode as ViewGroup)
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
        }

        tvScrubStars[0] = rootView.findViewById(R.id.tv_scrub_star_1)
        tvScrubStars[1] = rootView.findViewById(R.id.tv_scrub_star_2)
        tvScrubStars[2] = rootView.findViewById(R.id.tv_scrub_star_3)
        tvScrubStars[3] = rootView.findViewById(R.id.tv_scrub_star_4)
        tvScrubStars[4] = rootView.findViewById(R.id.tv_scrub_star_5)

        applyAlbumTiltSetting()
    }

    override fun onEnter(state: ScreenState) {
        layoutPlayerMode.visibility = View.VISIBLE
        applyThemeColors()
        updatePlayerUI()
        progressHandler.post(updateProgressTask)
    }

    override fun onExit() {
        progressHandler.removeCallbacks(updateProgressTask)
        layoutPlayerMode.visibility = View.GONE
        lyricsOverlayController.hide()
    }

    override fun onRefreshUI() {
        updatePlayerUI()
    }

    fun applyAlbumTiltSetting() {
        val isTiltEnabled = activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
            .getBoolean("album_tilt_enabled", true)
        val tilt = if (isTiltEnabled) 18f else 0f
        frameAlbumArt?.rotationY = tilt
        frameAlbumReflection?.rotationY = tilt
    }

    fun applyThemeColors() {
        val classic = ThemeManager.isClassicTheme()
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
        applyAlbumTiltSetting()
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
                lyricsOverlayController.loadLyrics(file)
                updateRatingDisplay()
                pendingSeekChanged = false
                nowPlayingBottomState = 0
                updateNowPlayingBottomBarState()
            }

            val totalCount = playlist.size
            tvPlayerTrackCount?.text = "${index + 1} of $totalCount"
        }

        val am = AudioPlayerManager.getInstance()
        val pos = am.getCurrentPosition()
        val dur = am.getDuration()

        tvPlayerTimeCurrent.text = formatTime(pos)
        tvPlayerTimeTotal.text = "-${formatTime(Math.max(0, dur - pos))}"

        if (dur > 0) {
            playerProgress.progress = (pos * 100) / dur
        } else {
            playerProgress.progress = 0
        }

        if (am.isPlaying()) {
            ivAlbumArt.alpha = 1.0f
            ivPauseOverlay?.visibility = View.GONE
        } else {
            ivAlbumArt.alpha = if (ThemeManager.isClassicTheme()) 1f else 0.4f
            ivPauseOverlay?.visibility = if (ThemeManager.isClassicTheme()) View.GONE else View.VISIBLE
        }

        if (ivAlbumReflection != null && ivAlbumArt.drawable != null) {
            ivAlbumReflection.setImageDrawable(ivAlbumArt.drawable)
        }

        updateShuffleRepeatStatusIcons()
    }

    private fun updatePlaybackProgress() {
        val am = AudioPlayerManager.getInstance()
        val pos = am.getCurrentPosition()
        val dur = am.getDuration()

        if (nowPlayingBottomState == 1 && !pendingSeekChanged) {
            tvPlayerSeekCurrent?.text = formatTime(pos)
            tvPlayerSeekTotal?.text = "-${formatTime(Math.max(0, dur - pos))}"
            seekPlayerScrub?.progress = pos
        } else if (nowPlayingBottomState == 0) {
            tvPlayerTimeCurrent.text = formatTime(pos)
            tvPlayerTimeTotal.text = "-${formatTime(Math.max(0, dur - pos))}"
            if (dur > 0) {
                playerProgress.progress = (pos * 100) / dur
            }
        }
        lyricsOverlayController.updateProgress(pos, dur)
    }

    fun updateNowPlayingBottomBarState() {
        rowPlayerVolume?.visibility = View.GONE
        rowPlayerProgress?.visibility = if (nowPlayingBottomState == 0) View.VISIBLE else View.GONE
        rowPlayerSeek?.visibility = if (nowPlayingBottomState == 1) View.VISIBLE else View.GONE
        rowPlayerShuffleRepeat?.visibility = if (nowPlayingBottomState == 2) View.VISIBLE else View.GONE
        rowPlayerRatingScrub?.visibility = if (nowPlayingBottomState == 3) View.VISIBLE else View.GONE

        val am = AudioPlayerManager.getInstance()
        when (nowPlayingBottomState) {
            1 -> {
                val current = if (pendingSeekChanged) pendingSeekPosition else am.getCurrentPosition()
                val duration = am.getDuration()
                tvPlayerSeekCurrent?.text = formatTime(current)
                tvPlayerSeekTotal?.text = "-${formatTime(Math.max(0, duration - current))}"
                seekPlayerScrub?.max = if (duration > 0) duration else 1000
                seekPlayerScrub?.progress = current
            }
            2 -> updateShuffleRepeatScrubLabels()
            3 -> updateRatingScrubStars()
        }
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
                }
            }
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
        nowPlayingBottomState = (nowPlayingBottomState + 1) % 4
        if (nowPlayingBottomState == 1) {
            pendingSeekPosition = am.getCurrentPosition()
            pendingSeekChanged = false
        }
        updateNowPlayingBottomBarState()
        clickFeedback()
    }

    private fun updateShuffleRepeatScrubLabels() {
        val prefs = activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
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
        val prefs = activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
        val isShuffle = !prefs.getBoolean("shuffle", false)
        prefs.edit().putBoolean("shuffle", isShuffle).commit()
        AudioPlayerManager.getInstance().setShuffleMode(isShuffle)
        updateShuffleRepeatStatusIcons()
    }

    private fun cycleRepeatMode() {
        val prefs = activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
        val repeatMode = (prefs.getInt("repeat_mode", 0) + 1) % 3
        prefs.edit().putInt("repeat_mode", repeatMode).commit()
        AudioPlayerManager.getInstance().setRepeatMode(repeatMode)
        updateShuffleRepeatStatusIcons()
    }

    private fun updateShuffleRepeatStatusIcons() {
        val prefs = activity.getSharedPreferences("y1_settings", Activity.MODE_PRIVATE)
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
            activity = activity,
            songFile = file,
            songItem = songItem,
            isFavorite = isFav,
            onToggleFavorite = onToggleFavorite,
            onAddToOnTheGo = onAddToOnTheGo,
            onBrowseAlbum = onBrowseAlbum,
            onBrowseArtist = onBrowseArtist,
            onToggleVisualizer = onToggleVisualizer,
            localize = localize
        )
    }

    private fun formatTime(ms: Int): String {
        val s = (ms / 1000) % 60
        val m = (ms / (1000 * 60)) % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        handleNowPlayingWheelInput(delta > 0)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, 23, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 85 -> {
                if (event.repeatCount == 0) {
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, 87 -> {
                clickFeedback()
                onNextTrack()
                updatePlayerUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, 88 -> {
                clickFeedback()
                onPreviousTrack()
                updatePlayerUI()
                return true
            }
            KeyEvent.KEYCODE_BACK, 4 -> {
                onBackPressed()
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
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
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86 || keyCode == 126 || keyCode == 127) {
            val main = MainActivity.instance
            if (main != null && main.currentPlaylist.isNotEmpty() && main.currentIndex in main.currentPlaylist.indices) {
                clickFeedback()
                main.isMediaLongPressConsumed = true
                val currentSong = main.currentPlaylist[main.currentIndex]
                main.showAddToPlaylistDialog(currentSong)
                return true
            }
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        clickFeedback()
        if (lyricsOverlayController.hasLyrics && lyricsOverlayController.lyricScrollView.visibility == View.VISIBLE) {
            lyricsOverlayController.hide()
            return true
        }
        onNavigateBack()
        return true
    }
}
