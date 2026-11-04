package com.themoon.y1.controllers.videos

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.themoon.y1.R
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.VideoPlayerManager
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.navigation.ScreenState
import java.io.File
import java.util.Locale

/**
 * Controller for fullscreen video playback using LibVLC (VideoPlayerManager),
 * progress updates, time formatting, and Click Wheel seek/volume controls.
 */
class VideoPlayerController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val onExitRequested: () -> Unit,
    private val onAdjustVolume: (up: Boolean) -> Unit,
    private val clickFeedback: () -> Unit,
    private val localize: (String) -> String
) : ScreenController {

    private val layoutVideoPlayer: FrameLayout = rootView.findViewById(R.id.layout_video_player_mode)
    private val surfaceVideo: SurfaceView = rootView.findViewById(R.id.surface_video)
    private val tvVideoTitle: TextView? = rootView.findViewById(R.id.tv_video_title)
    private val tvVideoTimeCurrent: TextView? = rootView.findViewById(R.id.tv_video_time_current)
    private val tvVideoTimeTotal: TextView? = rootView.findViewById(R.id.tv_video_time_total)
    private val videoProgressBar: ProgressBar? = rootView.findViewById(R.id.video_progress)
    private val tvVideoModeHint: TextView? = rootView.findViewById(R.id.tv_video_mode_hint)

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isSeekModeActive = false
    private var currentVideoFile: File? = null

    private val updateProgressTask = object : Runnable {
        override fun run() {
            updateProgressUI()
            progressHandler.postDelayed(this, 1000)
        }
    }

    override fun onEnter(state: ScreenState) {
        layoutVideoPlayer.visibility = View.VISIBLE
        surfaceVideo.visibility = View.VISIBLE
        if (state is ScreenState.VideoPlayer) {
            play(state.videoFile)
        }
    }

    override fun onExit() {
        stop()
        surfaceVideo.visibility = View.GONE
        layoutVideoPlayer.visibility = View.GONE
    }

    fun play(file: File) {
        currentVideoFile = file
        val title = file.nameWithoutExtension
        tvVideoTitle?.text = title

        isSeekModeActive = false
        tvVideoModeHint?.visibility = View.GONE

        VideoPlayerManager.getInstance().playVideo(activity, file, surfaceVideo)
        progressHandler.removeCallbacks(updateProgressTask)
        progressHandler.post(updateProgressTask)
    }

    fun stop() {
        progressHandler.removeCallbacks(updateProgressTask)
        VideoPlayerManager.getInstance().stopAndRelease()
    }

    private fun updateProgressUI() {
        val vm = VideoPlayerManager.getInstance()
        val pos = vm.getCurrentPosition().toInt()
        val dur = vm.getDuration().toInt()

        tvVideoTimeCurrent?.text = formatTime(pos)
        tvVideoTimeTotal?.text = "-" + formatTime(Math.max(0, dur - pos))
        if (videoProgressBar != null && dur > 0) {
            videoProgressBar.progress = ((pos.toLong() * 100) / dur).toInt()
        }
    }

    fun toggleSeekMode() {
        isSeekModeActive = !isSeekModeActive
        tvVideoModeHint?.let { hint ->
            hint.text = if (isSeekModeActive) localize("Seek Mode") else localize("Volume Mode")
            hint.visibility = View.VISIBLE
            hint.postDelayed({
                hint.visibility = View.GONE
            }, 1500)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        ) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val vm = VideoPlayerManager.getInstance()
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && !vm.isPlaying())
                    || (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && vm.isPlaying())
                ) {
                    vm.togglePlayPause()
                }
                clickFeedback()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            if (action == KeyEvent.ACTION_DOWN) {
                VideoPlayerManager.getInstance().seekRelative(
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 10000L else -10000L
                )
            }
            return true
        }

        if (keyCode == 21 || keyCode == 22) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (isSeekModeActive) {
                    VideoPlayerManager.getInstance().seekRelative(if (keyCode == 22) 10000L else -10000L)
                } else {
                    onAdjustVolume(keyCode == 22)
                }
                clickFeedback()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled) {
                clickFeedback()
                stop()
                onExitRequested()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_UP) {
                clickFeedback()
                VideoPlayerManager.getInstance().togglePlayPause()
            }
            return true
        }

        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) { // Wheel Left/Right
            clickFeedback()
            if (isSeekModeActive) {
                val delta = if (keyCode == 22) 10000L else -10000L
                VideoPlayerManager.getInstance().seekRelative(delta)
                updateProgressUI()
            } else {
                onAdjustVolume(keyCode == 22)
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            val delta = if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 10000L else -10000L
            VideoPlayerManager.getInstance().seekRelative(delta)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            clickFeedback()
            VideoPlayerManager.getInstance().togglePlayPause()
            return true
        }


        return false
    }

    override fun onBackPressed(): Boolean {
        stop()
        onExitRequested()
        return true
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }
}
