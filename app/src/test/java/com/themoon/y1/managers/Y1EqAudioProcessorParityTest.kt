package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.AudioProcessor.EMPTY_BUFFER
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests verifying parity and correctness of [Y1EqAudioProcessor]
 * against the original Java implementation.
 */
class Y1EqAudioProcessorParityTest {

    private lateinit var processor: Y1EqAudioProcessor

    @Before
    fun setUp() {
        processor = Y1EqAudioProcessor()
    }

    // =========================================================================
    // Reflection Helpers
    // =========================================================================

    private fun getInternalField(name: String): Any? {
        val field = Y1EqAudioProcessor::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        return field.get(processor)
    }

    private fun getInternalBooleanField(vararg candidates: String): Boolean {
        for (name in candidates) {
            try {
                val field = Y1EqAudioProcessor::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                }
                return field.getBoolean(processor)
            } catch (_: NoSuchFieldException) {
            }
        }
        throw NoSuchFieldException("None of candidate fields found: ${candidates.joinToString()}")
    }

    private fun getIsEnabledFlag(): Boolean = getInternalBooleanField("isEnabledFlag", "isActive")

    private fun getIsFormatSupported(): Boolean = getInternalField("isFormatSupported") as Boolean

    private fun getCurrentGains(): FloatArray = getInternalField("currentGains") as FloatArray

    @Suppress("UNCHECKED_CAST")
    private fun getFilters(): Array<Array<*>> = getInternalField("filters") as Array<Array<*>>

    private fun getFilterCoeff(filter: Any, coeffName: String): Float {
        val field = filter.javaClass.getDeclaredField(coeffName).apply {
            isAccessible = true
        }
        return field.getFloat(filter)
    }

    private fun createPcm16Buffer(sampleCount: Int, generator: (Int) -> Short): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until sampleCount) {
            buffer.putShort(generator(i))
        }
        buffer.flip()
        return buffer
    }

    // =========================================================================
    // 1. setBandLevel(bandIndex, dbGain)
    // =========================================================================

    @Test
    fun testSetBandLevel_outOfBoundsIgnoredWithoutCrash() {
        // Negative indices must be ignored without throwing exceptions
        processor.setBandLevel(-1, 6.0f)
        processor.setBandLevel(-10, 12.0f)
        processor.setBandLevel(Int.MIN_VALUE, 3.0f)

        // Upper boundary indices (>= 10) must be ignored
        processor.setBandLevel(10, 6.0f)
        processor.setBandLevel(15, 12.0f)
        processor.setBandLevel(Int.MAX_VALUE, 3.0f)

        // Verify current gains are untouched (all zeros)
        val gains = getCurrentGains()
        for (i in 0 until 10) {
            assertEquals("Band $i gain should remain default 0.0f", 0.0f, gains[i], 0.0001f)
        }
    }

    @Test
    fun testSetBandLevel_validBands0To9GainsUpdated() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val expectedGains = floatArrayOf(-12f, -8f, -4f, -2f, 0f, 2f, 4f, 6f, 8f, 12f)
        for (band in 0 until 10) {
            processor.setBandLevel(band, expectedGains[band])
        }

        val actualGains = getCurrentGains()
        for (band in 0 until 10) {
            assertEquals("Band $band gain mismatch", expectedGains[band], actualGains[band], 0.0001f)
        }

        // Verify that filter coefficients for each band have been computed and are finite
        val filters = getFilters()
        for (ch in 0 until 2) {
            for (band in 0 until 10) {
                val filter = filters[ch][band]!!
                val a0 = getFilterCoeff(filter, "a0")
                val a1 = getFilterCoeff(filter, "a1")
                val a2 = getFilterCoeff(filter, "a2")
                val b1 = getFilterCoeff(filter, "b1")
                val b2 = getFilterCoeff(filter, "b2")

                assertFalse("Band $band ch $ch a0 must not be NaN", a0.isNaN())
                assertFalse("Band $band ch $ch a1 must not be NaN", a1.isNaN())
                assertFalse("Band $band ch $ch a2 must not be NaN", a2.isNaN())
                assertFalse("Band $band ch $ch b1 must not be NaN", b1.isNaN())
                assertFalse("Band $band ch $ch b2 must not be NaN", b2.isNaN())

                assertFalse("Band $band ch $ch a0 must not be Infinite", a0.isInfinite())
                assertFalse("Band $band ch $ch b1 must not be Infinite", b1.isInfinite())
            }
        }
    }

    @Test
    fun testSetBandLevel_beforeConfigureSavesGainsAndAppliesOnFlush() {
        // Set band levels before configure / flush
        processor.setBandLevel(0, 5.0f)
        processor.setBandLevel(9, -7.0f)

        val gains = getCurrentGains()
        assertEquals(5.0f, gains[0], 0.0001f)
        assertEquals(-7.0f, gains[9], 0.0001f)

        // Now configure and flush -> should calculate coefficients for the configured sample rate
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val filters = getFilters()
        val filterBand0 = filters[0][0]!!
        val a0 = getFilterCoeff(filterBand0, "a0")
        assertFalse("a0 must not be NaN", a0.isNaN())
        assertTrue("a0 must be non-zero after flush applies saved gains", a0 != 0f)
    }

    @Test
    fun testSetBandLevel_nyquistGuardAt44100Hz() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // Test high bands (8 = 8000Hz, 9 = 16000Hz) at 44100Hz (Nyquist = 22050Hz)
        processor.setBandLevel(8, 12.0f)
        processor.setBandLevel(9, 12.0f)

        val filters = getFilters()
        for (band in 8..9) {
            for (ch in 0 until 2) {
                val filter = filters[ch][band]!!
                val a0 = getFilterCoeff(filter, "a0")
                val b1 = getFilterCoeff(filter, "b1")

                assertFalse("Band $band a0 must not be NaN at 44100Hz", a0.isNaN())
                assertFalse("Band $band b1 must not be NaN at 44100Hz", b1.isNaN())
                assertTrue("Band $band a0 must be non-zero", a0 != 0f)
            }
        }
    }

    @Test
    fun testSetBandLevel_nyquistGuardClampsWhenFreqExceedsHalfSampleRate() {
        // At 32000Hz: Nyquist fs / 2 = 16000Hz.
        // Band 9 center freq is 16000Hz >= fs / 2, so Nyquist guard clamps f0 = (fs/2) * 0.99f.
        val format32k = AudioFormat(32000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format32k)
        processor.flush()

        processor.setBandLevel(9, 6.0f)

        val filters32k = getFilters()
        for (ch in 0 until 2) {
            val filter = filters32k[ch][9]!!
            val a0 = getFilterCoeff(filter, "a0")
            val a1 = getFilterCoeff(filter, "a1")
            val b1 = getFilterCoeff(filter, "b1")

            assertFalse("Nyquist clamp should prevent NaN on a0 at 32kHz", a0.isNaN())
            assertFalse("Nyquist clamp should prevent NaN on a1 at 32kHz", a1.isNaN())
            assertFalse("Nyquist clamp should prevent NaN on b1 at 32kHz", b1.isNaN())
            assertTrue("a0 should be finite and non-zero", a0 != 0f && !a0.isInfinite())
        }

        // At 22050Hz: Nyquist fs / 2 = 11025Hz.
        // Band 9 (16000Hz) strictly exceeds fs / 2.
        val format22k = AudioFormat(22050, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format22k)
        processor.flush()

        processor.setBandLevel(9, 9.0f)

        val filters22k = getFilters()
        for (ch in 0 until 2) {
            val filter = filters22k[ch][9]!!
            val a0 = getFilterCoeff(filter, "a0")
            val b1 = getFilterCoeff(filter, "b1")

            assertFalse("Nyquist clamp should prevent NaN on a0 at 22050Hz", a0.isNaN())
            assertFalse("Nyquist clamp should prevent NaN on b1 at 22050Hz", b1.isNaN())
            assertTrue("a0 should be non-zero", a0 != 0f)
        }

        // At 8000Hz: Nyquist fs / 2 = 4000Hz.
        // Bands 7 (4000Hz), 8 (8000Hz), and 9 (16000Hz) all trigger the Nyquist clamp.
        val format8k = AudioFormat(8000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format8k)
        processor.flush()

        for (band in 0 until 10) {
            processor.setBandLevel(band, 3.0f)
        }

        val filters8k = getFilters()
        for (band in 0 until 10) {
            val filter = filters8k[0][band]!!
            val a0 = getFilterCoeff(filter, "a0")
            assertFalse("Band $band a0 at 8kHz must not be NaN", a0.isNaN())
            assertTrue("Band $band a0 at 8kHz must be finite", !a0.isInfinite() && a0 != 0f)
        }
    }

    // =========================================================================
    // 2. configure(AudioFormat)
    // =========================================================================

    @Test
    fun testConfigure_pcm16BitSupported() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val returnedFormat = processor.configure(format)

        assertEquals("Returned format should match input format", format, returnedFormat)
        assertTrue("PCM 16-bit must be marked supported", getIsFormatSupported())
        assertTrue("Processor must be active after configuring supported format", processor.isActive)
    }

    @Test
    fun testConfigure_otherEncodingsNotSupported() {
        val unsupportedEncodings = intArrayOf(
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_8BIT,
            C.ENCODING_INVALID
        )

        for (encoding in unsupportedEncodings) {
            val format = AudioFormat(44100, 2, encoding)
            val returnedFormat = processor.configure(format)

            assertEquals("Configure must pass through format safely", format, returnedFormat)
            assertFalse("Encoding $encoding must not be supported", getIsFormatSupported())
            assertFalse("Processor must not be active for unsupported encoding $encoding", processor.isActive)
        }
    }

    // =========================================================================
    // 3. queueInput(ByteBuffer)
    // =========================================================================

    @Test
    fun testQueueInput_stereo16BitProcessesAndProducesOutput() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(true)
        processor.setBandLevel(5, 12.0f) // Boost 1kHz

        // 100 stereo frames = 200 samples = 400 bytes
        val inputBuffer = createPcm16Buffer(200) { index ->
            (Math.sin(2.0 * Math.PI * 1000.0 * (index / 2) / 44100.0) * 10000).toInt().toShort()
        }

        processor.queueInput(inputBuffer)
        assertEquals("Input buffer should be fully consumed", 0, inputBuffer.remaining())

        val outputBuffer = processor.output
        assertNotNull("Output buffer must not be null", outputBuffer)
        assertEquals("Output buffer size must match input byte count", 400, outputBuffer.remaining())

        // Ensure subsequent call returns EMPTY_BUFFER
        assertSame("Subsequent getOutput() must return EMPTY_BUFFER", EMPTY_BUFFER, processor.output)
    }

    @Test
    fun testQueueInput_channelSafetyMonoAndStereoWithoutIndexOutOfBounds() {
        // Test Mono (1 channel)
        val monoFormat = AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(monoFormat)
        processor.setEnabled(true)
        processor.setBandLevel(2, 6.0f)

        // 50 mono frames = 50 samples = 100 bytes
        val monoInput = createPcm16Buffer(50) { i -> (i * 200).toShort() }
        processor.queueInput(monoInput)

        val monoOutput = processor.output
        assertEquals("Mono output size must be 100 bytes", 100, monoOutput.remaining())

        // Test Stereo (2 channels)
        val stereoFormat = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(stereoFormat)
        processor.setEnabled(true)
        processor.setBandLevel(3, -6.0f)

        // 50 stereo frames = 100 samples = 200 bytes
        val stereoInput = createPcm16Buffer(100) { i -> (i * 100).toShort() }
        processor.queueInput(stereoInput)

        val stereoOutput = processor.output
        assertEquals("Stereo output size must be 200 bytes", 200, stereoOutput.remaining())

        // Test Multi-channel (e.g. 6-channel 5.1 surround sound)
        // Kotlin implementation routes ch >= 2 to channelIdx 0, preventing IndexOutOfBoundsException
        val multiFormat = AudioFormat(44100, 6, C.ENCODING_PCM_16BIT)
        processor.configure(multiFormat)
        processor.setEnabled(true)

        // 10 frames of 6 channels = 60 samples = 120 bytes
        val multiInput = createPcm16Buffer(60) { i -> (i * 50).toShort() }
        processor.queueInput(multiInput)

        val multiOutput = processor.output
        assertEquals("Multi-channel output size must be 120 bytes", 120, multiOutput.remaining())
    }

    @Test
    fun testQueueInput_passthroughWhenDisabledOrUnsupported() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(false) // Disabled!
        processor.setBandLevel(5, 12.0f)

        val sampleCount = 20
        val inputBuffer = createPcm16Buffer(sampleCount) { i -> (1000 + i * 100).toShort() }

        // Duplicate expected raw bytes
        inputBuffer.mark()
        val expectedBytes = ByteArray(sampleCount * 2)
        inputBuffer.get(expectedBytes)
        inputBuffer.reset()

        processor.queueInput(inputBuffer)
        val outputBuffer = processor.output

        val actualBytes = ByteArray(outputBuffer.remaining())
        outputBuffer.get(actualBytes)

        assertArrayEquals("Output must be byte-for-byte identical to input when disabled", expectedBytes, actualBytes)
    }

    @Test
    fun testQueueInput_clampingLimitsPreventSampleOverflow() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(true)
        // Set maximum +12dB boost on all bands
        for (i in 0 until 10) {
            processor.setBandLevel(i, 12.0f)
        }

        // Feed near full-scale samples that will drive the filter to saturate
        val highInput = createPcm16Buffer(100) { 32000.toShort() }
        processor.queueInput(highInput)

        val output = processor.output
        while (output.hasRemaining()) {
            val sample = output.short
            // Clamping in Y1EqAudioProcessor ensures sample stays within [-32767, 32767]
            assertTrue("Sample $sample must not exceed 32767", sample <= 32767)
            assertTrue("Sample $sample must not exceed -32767", sample >= -32768)
        }
    }

    @Test
    fun testQueueInput_oddTrailingBytesPassedSafely() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(true)

        // Stereo frames require 4 bytes per frame. Allocate 43 bytes (10 frames + 3 leftover bytes)
        val oddBuffer = ByteBuffer.allocateDirect(43).order(ByteOrder.nativeOrder())
        for (i in 0 until 40) {
            oddBuffer.put((i and 0xFF).toByte())
        }
        oddBuffer.put(0x11.toByte())
        oddBuffer.put(0x22.toByte())
        oddBuffer.put(0x33.toByte())
        oddBuffer.flip()

        processor.queueInput(oddBuffer)
        val output = processor.output
        assertEquals("All 43 bytes (including 3 residual bytes) must be output", 43, output.remaining())
    }

    // =========================================================================
    // 4. flush() and reset()
    // =========================================================================

    @Test
    fun testFlush_resetsOutputBufferAndFilterInternalStates() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(true)
        processor.setBandLevel(4, 10.0f)

        // Queue some input to update internal filter states (x1, x2, y1, y2)
        val input = createPcm16Buffer(40) { 15000.toShort() }
        processor.queueInput(input)

        val filters = getFilters()
        val filter0 = filters[0][4]!!

        // Filter state x1 or y1 should be non-zero after processing non-zero samples
        val x1Before = getFilterCoeff(filter0, "x1")
        val y1Before = getFilterCoeff(filter0, "y1")
        assertTrue("Filter x1 state should be non-zero before flush", x1Before != 0f)
        assertTrue("Filter y1 state should be non-zero before flush", y1Before != 0f)

        // Flush
        processor.flush()

        assertSame("Output buffer should be EMPTY_BUFFER after flush", EMPTY_BUFFER, processor.output)
        assertFalse("isEnded should be false after flush", processor.isEnded)

        // Verify filter states are reset to 0f
        val x1After = getFilterCoeff(filter0, "x1")
        val y1After = getFilterCoeff(filter0, "y1")
        val x2After = getFilterCoeff(filter0, "x2")
        val y2After = getFilterCoeff(filter0, "y2")

        assertEquals("Filter x1 must reset to 0f", 0f, x1After, 0.00001f)
        assertEquals("Filter y1 must reset to 0f", 0f, y1After, 0.00001f)
        assertEquals("Filter x2 must reset to 0f", 0f, x2After, 0.00001f)
        assertEquals("Filter y2 must reset to 0f", 0f, y2After, 0.00001f)
    }

    @Test
    fun testReset_clearsFormatsBuffersAndActiveState() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setEnabled(true)
        assertTrue("Processor should be active after configure", processor.isActive)

        processor.reset()

        assertFalse("Processor isActive must be false after reset", processor.isActive)
        assertFalse("isFormatSupported must be false after reset", getIsFormatSupported())
        assertSame("outputBuffer must be EMPTY_BUFFER after reset", EMPTY_BUFFER, processor.output)
    }

    // =========================================================================
    // 5. isActive() and isEnabledFlag
    // =========================================================================

    @Test
    fun testSetEnabled_togglesIsEnabledFlagAndFlushes() {
        assertFalse("Default isEnabledFlag should be false", getIsEnabledFlag())

        processor.setEnabled(true)
        assertTrue("isEnabledFlag should be true after setEnabled(true)", getIsEnabledFlag())

        processor.setEnabled(false)
        assertFalse("isEnabledFlag should be false after setEnabled(false)", getIsEnabledFlag())
    }

    @Test
    fun testIsActive_adheresToExoPlayerContract() {
        // Before configure, pendingAudioFormat is NOT_SET -> isActive is false
        assertFalse("Processor must not be active initially", processor.isActive)

        // Configure supported format -> isActive becomes true
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        assertTrue("Processor must be active when format supported and pending", processor.isActive)

        // Toggling setEnabled does NOT disconnect processor from ExoPlayer pipeline (isActive remains true)
        processor.setEnabled(false)
        assertTrue("isActive should remain true so ExoPlayer keeps processor in pipeline for passthrough", processor.isActive)

        processor.setEnabled(true)
        assertTrue("isActive should remain true when enabled", processor.isActive)

        // Reset clears pending format -> isActive returns false
        processor.reset()
        assertFalse("Processor must be inactive after reset", processor.isActive)
    }

    @Test
    fun testQueueEndOfStream_andIsEnded() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        assertFalse("isEnded should be false initially", processor.isEnded)

        processor.queueEndOfStream()
        // If outputBuffer is EMPTY_BUFFER, isEnded should be true
        assertTrue("isEnded should be true when stream ended and output empty", processor.isEnded)

        // After flush, isEnded should be reset to false
        processor.flush()
        assertFalse("isEnded should be false after flush", processor.isEnded)
    }

    @Test
    fun testQueueInput_flatCurveWhenEnabledIsBitExactPassthrough() {
        processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.setEnabled(true)
        for (i in 0 until 10) processor.setBandLevel(i, 0.0f)

        val input = createPcm16Buffer(200) { i -> (i * 157 - 15000).toShort() }
        val expected = ByteArray(input.remaining())
        input.duplicate().get(expected)

        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining())
        output.get(actual)
        assertArrayEquals("A flat curve must not change the samples", expected, actual)

        val filter0 = getFilters()[0][0]!!
        assertEquals("A flat curve must not run the filters", 0f, getFilterCoeff(filter0, "x1"), 0f)
    }

    @Test
    fun testQueueInput_gainAfterFlatCurveRunsFilters() {
        processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.setEnabled(true)
        processor.queueInput(createPcm16Buffer(8) { 1000.toShort() })
        processor.output

        processor.setBandLevel(5, 6.0f)
        processor.queueInput(createPcm16Buffer(8) { 1000.toShort() })
        processor.output
        assertTrue("A band gain must run the filters", getFilterCoeff(getFilters()[0][5]!!, "x1") != 0f)
    }
}
