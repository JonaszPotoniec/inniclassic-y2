package com.themoon.y1

import android.media.audiofx.Visualizer
import com.themoon.y1.controllers.player.PlayerController
import com.themoon.y1.managers.AudioPlayerManager

// The audio session of audioVisualizer: the player keeps its session from track to track
private var visualizerSession = 0

internal fun MainActivity.setupVisualizerInternal() {
    try {
        val session = AudioPlayerManager.getInstance().getAudioSessionId()
        val current = audioVisualizer
        if (current != null && session != 0 && session == visualizerSession) {
            // Same session (a track change): keep the visualizer, as a new one is a slow call to the audio server
            refreshVisualizerCapture()
            return
        }
        current?.apply {
            setEnabled(false)
            release()
        }
        audioVisualizer = null
        visualizerSession = session
        audioVisualizer = Visualizer(session).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1]
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    (getActiveScreenController() as? PlayerController)?.updateVisualizer(fft)
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
        }
        refreshVisualizerCapture()
    } catch (_: Exception) {}
}

internal fun MainActivity.toggleVisualizerInternal() {
    isVisualizerShowing = !isVisualizerShowing
    playerController.refreshVisualizerState()
}

internal fun MainActivity.rebootAudioEngineInternal() {
    val am = AudioPlayerManager.getInstance()
    if (currentPlaylist.isNotEmpty() && currentIndex in currentPlaylist.indices) {
        val wasPlaying = am.isPlaying()
        val currentPos = am.getCurrentPosition()
        am.releasePlayer()
        am.prepareMusicTrack(currentIndex)
        if (currentPos > 0) am.seekRelative(currentPos)
        if (wasPlaying) am.playOrPauseMusic()
    }
}
