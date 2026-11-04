package com.themoon.y1.managers

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import com.themoon.y1.MainActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.ArrayList

/**
 * LibVLC-based video playback engine with resume positions and master gain routing.
 */
class VideoPlayerManager private constructor() {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var attachedSurfaceView: SurfaceView? = null
    private var currentVideoFile: File? = null

    private var currentMasterVolume = 1.0f

    fun setMasterVolume(volume: Float) {
        this.currentMasterVolume = Math.max(0.0f, Math.min(1.0f, volume))
        applyMasterVolume()
    }

    fun getMasterVolume(): Float {
        return currentMasterVolume
    }

    fun applyMasterVolume() {
        val player = mediaPlayer ?: return
        try {
            // LibVLC volume is in whole percents: keep 1 % (-40 dB) for a quiet step, so only step 0 mutes.
            val percent = if (currentMasterVolume > 0f) Math.round(currentMasterVolume * 100.0f).coerceAtLeast(1) else 0
            player.volume = percent
        } catch (ignored: Throwable) {}
    }

    private fun ensurePlayer(context: Context) {
        if (libVLC == null) {
            val options = ArrayList<String>()
            val vlc = LibVLC(context.applicationContext, options)
            libVLC = vlc
            mediaPlayer = MediaPlayer(vlc)
            currentMasterVolume = VirtualVolumeManager.getInstance().getMasterGain()
            applyMasterVolume()
        }
    }

    fun playVideo(context: Context, videoFile: File, surfaceView: SurfaceView) {
        AudioSources.app.videoStarts()
        ensurePlayer(context)

        val player = mediaPlayer ?: return
        val vlc = libVLC ?: return

        if (currentVideoFile != null && currentVideoFile != videoFile) {
            saveResumePositionIfNeeded()
        }
        currentVideoFile = videoFile

        if (attachedSurfaceView != surfaceView) {
            val oldVout = player.vlcVout
            if (oldVout.areViewsAttached()) {
                oldVout.detachViews()
            }
            attachedSurfaceView = surfaceView
        }

        val vlcVout = player.vlcVout
        if (!vlcVout.areViewsAttached()) {
            vlcVout.setVideoView(surfaceView)
            vlcVout.attachViews()
        }

        surfaceView.post {
            val w = surfaceView.width
            val h = surfaceView.height
            if (w > 0 && h > 0) {
                vlcVout.setWindowSize(w, h)
                val stretchFill = MainActivity.instance?.prefs?.getBoolean("video_stretch_fill", false) == true
                player.aspectRatio = if (stretchFill) "$w:$h" else null
            }
        }

        player.stop()
        val media = Media(vlc, Uri.fromFile(videoFile))
        player.setMedia(media)
        media.release()

        player.scale = 0f
        player.play()
        applyMasterVolume()

        MainActivity.instance?.let { act ->
            act.prefs.edit().putString("last_played_video_path", videoFile.absolutePath).apply()
            val savedPos = act.prefs.getLong("video_pos_" + videoFile.absolutePath, 0)
            if (savedPos > 0) {
                player.time = savedPos
            }
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            saveResumePositionIfNeeded()
            player.pause()
        } else {
            player.play()
        }
    }

    private fun saveResumePositionIfNeeded() {
        val player = mediaPlayer ?: return
        val file = currentVideoFile ?: return
        val act = MainActivity.instance ?: return

        val pos = player.time
        val dur = player.length
        val key = "video_pos_" + file.absolutePath
        if (pos < RESUME_MIN_POS_MS || (dur > 0 && pos > dur - RESUME_END_MARGIN_MS)) {
            act.prefs.edit().remove(key).apply()
        } else {
            act.prefs.edit().putLong(key, pos).apply()
        }
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    fun seekRelative(deltaMs: Long) {
        val player = mediaPlayer ?: return
        var target = player.time + deltaMs
        val duration = player.length
        target = if (duration > 0) {
            Math.max(0, Math.min(target, duration))
        } else {
            Math.max(0, target)
        }
        player.time = target
    }

    fun getCurrentPosition(): Long {
        return mediaPlayer?.time ?: 0L
    }

    fun getDuration(): Long {
        return mediaPlayer?.let { Math.max(0, it.length) } ?: 0L
    }

    fun stopAndRelease() {
        val player = mediaPlayer
        if (player != null) {
            saveResumePositionIfNeeded()
            player.stop()
            val vlcVout = player.vlcVout
            if (vlcVout.areViewsAttached()) {
                vlcVout.detachViews()
            }
            attachedSurfaceView = null
        }
        currentVideoFile = null
    }

    companion object {
        private const val RESUME_MIN_POS_MS = 3000L
        private const val RESUME_END_MARGIN_MS = 5000L

        @Volatile
        private var instance: VideoPlayerManager? = null
        fun getInstance(): VideoPlayerManager {
            var inst = instance
            if (inst == null) {
                inst = VideoPlayerManager()
                instance = inst
            }
            return inst
        }
    }
}
