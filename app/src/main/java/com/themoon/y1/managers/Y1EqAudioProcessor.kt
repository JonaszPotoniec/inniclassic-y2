package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.AudioProcessor.EMPTY_BUFFER
import com.google.android.exoplayer2.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 10-band peaking EQ AudioProcessor for ExoPlayer with Biquad IIR filters.
 */
class Y1EqAudioProcessor : AudioProcessor {

    private var isEnabledFlag = false
    private var isFormatSupported = false

    private var pendingAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private val centerFreqs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val filters = Array(2) { Array(10) { BiquadFilter() } }
    private val currentGains = FloatArray(10)

    // True when a band has a gain other than 0 dB. With all bands at 0 dB the filters do not change the sound.
    @Volatile
    private var hasGain = false
    // True while the last buffer went through the filters.
    private var filtersInUse = false

    fun setBandLevel(bandIndex: Int, dbGain: Float) {
        if (bandIndex < 0 || bandIndex >= 10) return
        currentGains[bandIndex] = dbGain
        hasGain = currentGains.any { it != 0f }

        if (outputAudioFormat.sampleRate > 0) {
            val fs = outputAudioFormat.sampleRate.toFloat()
            var f0 = centerFreqs[bandIndex]

            // Prevent Nyquist foldover breakdown
            if (f0 >= fs / 2.0f) {
                f0 = (fs / 2.0f) * 0.99f
            }

            for (ch in 0 until 2) {
                filters[ch][bandIndex].setPeakingEQ(fs, f0, 1.414f, dbGain)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabledFlag) return
        this.isEnabledFlag = enabled
        flush()
    }

    @Throws(UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingAudioFormat = inputAudioFormat
        isFormatSupported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        if (!isFormatSupported) return false
        return pendingAudioFormat != AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        // Off, or a flat curve: copy the buffer, with no work for each sample.
        if (!isEnabledFlag || !isFormatSupported || !hasGain) {
            filtersInUse = false
            if (inputBuffer.hasRemaining()) {
                buffer.put(inputBuffer)
            }
            buffer.flip()
            outputBuffer = buffer
            return
        }
        if (!filtersInUse) {
            // The filter state is from before the bypass: start again from silence.
            resetFilterStates()
            filtersInUse = true
        }

        val channelCount = outputAudioFormat.channelCount
        val bytesPerFrame = channelCount * 2

        while (inputBuffer.remaining() >= bytesPerFrame) {
            for (ch in 0 until channelCount) {
                val rawSample = inputBuffer.short
                var sample = rawSample / 32768.0f

                val channelIdx = if (ch < 2) ch else 0
                for (band in 0 until 10) {
                    sample = filters[channelIdx][band].process(sample)
                }

                if (sample > 1.0f) sample = 1.0f
                else if (sample < -1.0f) sample = -1.0f

                buffer.putShort((sample * 32767.0f).toInt().toShort())
            }
        }

        if (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer)
        }

        buffer.flip()
        outputBuffer = buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        outputAudioFormat = pendingAudioFormat

        resetFilterStates()

        if (outputAudioFormat.sampleRate > 0) {
            for (band in 0 until 10) {
                setBandLevel(band, currentGains[band])
            }
        }
    }

    private fun resetFilterStates() {
        for (ch in 0 until 2) {
            for (band in 0 until 10) {
                filters[ch][band].reset()
            }
        }
    }

    override fun reset() {
        flush()
        buffer = EMPTY_BUFFER
        pendingAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        isFormatSupported = false
    }

    private class BiquadFilter {
        private var a0: Float = 0f
        private var a1: Float = 0f
        private var a2: Float = 0f
        private var b1: Float = 0f
        private var b2: Float = 0f
        private var x1: Float = 0f
        private var x2: Float = 0f
        private var y1: Float = 0f
        private var y2: Float = 0f

        fun setPeakingEQ(fs: Float, f0: Float, q: Float, dbGain: Float) {
            val a = Math.pow(10.0, dbGain / 40.0).toFloat()
            val w0 = (2.0 * Math.PI * f0 / fs).toFloat()
            val alpha = (Math.sin(w0.toDouble()) / (2.0 * q)).toFloat()

            val b0 = 1f + alpha * a
            b1 = -2f * Math.cos(w0.toDouble()).toFloat()
            b2 = 1f - alpha * a
            a0 = 1f + alpha / a
            a1 = -2f * Math.cos(w0.toDouble()).toFloat()
            a2 = 1f - alpha / a

            a0 /= b0
            a1 /= b0
            a2 /= b0
            b1 /= b0
            b2 /= b0
        }

        fun process(x: Float): Float {
            val y = a0 * x + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }
}
