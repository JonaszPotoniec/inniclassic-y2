package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.AudioProcessor.EMPTY_BUFFER
import com.google.android.exoplayer2.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ExoPlayer AudioProcessor implementing crossfeed filter for headphone listening fatigue reduction.
 */
class Y1CrossfeedAudioProcessor : AudioProcessor {

    private var isActiveFlag = false
    private var isFormatSupported = false

    private var pendingAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    private var intensity = 0
    private var crossfeedLevel = 0.0f
    private var alpha = 0.0f
    private var lastL = 0.0f
    private var lastR = 0.0f

    fun setIntensity(level: Int) {
        // Same level again (each track start applies the settings): keep the filter state, no flush.
        if (level == intensity) return
        intensity = level
        if (level == 0) {
            isActiveFlag = false
            crossfeedLevel = 0.0f
        } else {
            isActiveFlag = true
            when (level) {
                1 -> crossfeedLevel = 0.15f
                2 -> crossfeedLevel = 0.25f
                else -> crossfeedLevel = 0.35f
            }
        }
        flush()
    }

    @Throws(UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingAudioFormat = inputAudioFormat
        isFormatSupported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2)

        if (isFormatSupported) {
            val fc = 700.0f
            val dt = 1.0f / inputAudioFormat.sampleRate
            val rc = 1.0f / (2.0 * Math.PI * fc).toFloat()
            alpha = dt / (rc + dt)
        }

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

        if (!isActiveFlag || crossfeedLevel == 0.0f || !isFormatSupported) {
            if (inputBuffer.hasRemaining()) {
                buffer.put(inputBuffer)
            }
            buffer.flip()
            outputBuffer = buffer
            return
        }

        while (inputBuffer.remaining() >= 4) {
            val rawL = inputBuffer.short
            val rawR = inputBuffer.short

            val l = rawL / 32768.0f
            val r = rawR / 32768.0f

            lastL += alpha * (l - lastL)
            lastR += alpha * (r - lastR)

            var outL = l + (lastR * crossfeedLevel)
            var outR = r + (lastL * crossfeedLevel)

            outL /= (1.0f + crossfeedLevel)
            outR /= (1.0f + crossfeedLevel)

            if (outL > 1.0f) outL = 1.0f else if (outL < -1.0f) outL = -1.0f
            if (outR > 1.0f) outR = 1.0f else if (outR < -1.0f) outR = -1.0f

            buffer.putShort((outL * 32767.0f).toInt().toShort())
            buffer.putShort((outR * 32767.0f).toInt().toShort())
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
        lastL = 0.0f
        lastR = 0.0f
    }

    override fun reset() {
        flush()
        buffer = EMPTY_BUFFER
        pendingAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        isFormatSupported = false
    }
}
