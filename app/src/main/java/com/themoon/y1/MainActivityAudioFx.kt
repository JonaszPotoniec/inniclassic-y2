package com.themoon.y1

import android.media.audiofx.Visualizer
import com.themoon.y1.managers.AudioPlayerManager

internal fun MainActivity.setupVisualizerInternal() {
    try {
        audioVisualizer?.apply {
            setEnabled(false)
            release()
        }
        audioVisualizer = Visualizer(AudioPlayerManager.getInstance().getAudioSessionId()).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1]
            setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            if (isVisualizerShowing) setEnabled(true)
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.toggleVisualizerInternal() {
    isVisualizerShowing = !isVisualizerShowing
    audioVisualizer?.setEnabled(isVisualizerShowing)
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
