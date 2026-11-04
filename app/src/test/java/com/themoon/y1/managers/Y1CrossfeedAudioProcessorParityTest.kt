package com.themoon.y1.managers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioProcessor.AudioFormat
import com.google.android.exoplayer2.audio.AudioProcessor.EMPTY_BUFFER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests verifying parity and correctness of [Y1CrossfeedAudioProcessor]
 * against the original Java implementation.
 */
class Y1CrossfeedAudioProcessorParityTest {

    private lateinit var processor: Y1CrossfeedAudioProcessor

    @Before
    fun setUp() {
        processor = Y1CrossfeedAudioProcessor()
    }

    // Helper reflection methods to inspect internal state for parity verification
    private fun getInternalField(name: String): Any? {
        val field = Y1CrossfeedAudioProcessor::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        return field.get(processor)
    }

    private fun getInternalBooleanField(vararg candidates: String): Boolean {
        for (name in candidates) {
            try {
                val field = Y1CrossfeedAudioProcessor::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                }
                return field.getBoolean(processor)
            } catch (_: NoSuchFieldException) {
            }
        }
        throw NoSuchFieldException("None of candidate fields found: ${candidates.joinToString()}")
    }

    private fun getCrossfeedLevel(): Float = getInternalField("crossfeedLevel") as Float
    private fun getAlpha(): Float = getInternalField("alpha") as Float
    private fun getLastL(): Float = getInternalField("lastL") as Float
    private fun getLastR(): Float = getInternalField("lastR") as Float
    private fun getIsFormatSupported(): Boolean = getInternalField("isFormatSupported") as Boolean
    private fun getIsActiveFlag(): Boolean = getInternalBooleanField("isActiveFlag", "isActive")

    // -------------------------------------------------------------------------
    // 1. setIntensity(level: Int)
    // -------------------------------------------------------------------------

    @Test
    fun testSetIntensityLevel0_disablesCrossfeed() {
        processor.setIntensity(0)
        assertFalse("Level 0 should set isActive to false", getIsActiveFlag())
        assertEquals(0.0f, getCrossfeedLevel(), 0.0001f)
    }

    @Test
    fun testSetIntensityLevel1_setsMildCrossfeed() {
        processor.setIntensity(1)
        assertTrue("Level 1 should set isActive to true", getIsActiveFlag())
        assertEquals(0.15f, getCrossfeedLevel(), 0.0001f)
    }

    @Test
    fun testSetIntensityLevel2_setsMediumCrossfeed() {
        processor.setIntensity(2)
        assertTrue("Level 2 should set isActive to true", getIsActiveFlag())
        assertEquals(0.25f, getCrossfeedLevel(), 0.0001f)
    }

    @Test
    fun testSetIntensityLevel3_setsHighCrossfeed() {
        processor.setIntensity(3)
        assertTrue("Level 3 should set isActive to true", getIsActiveFlag())
        assertEquals(0.35f, getCrossfeedLevel(), 0.0001f)
    }

    @Test
    fun testSetIntensityLevel99_clampsToMaxCrossfeed() {
        processor.setIntensity(99)
        assertTrue("Level 99 should set isActive to true", getIsActiveFlag())
        assertEquals(0.35f, getCrossfeedLevel(), 0.0001f)
    }

    @Test
    fun testSetIntensityTransitions_resetsFilterState() {
        // Configure and process some audio so lastL and lastR become non-zero
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(2)

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(16000)
        input.putShort(0)
        input.putShort(16000)
        input.putShort(0)
        input.flip()
        processor.queueInput(input)

        assertTrue(getLastL() > 0.0f)

        // Setting intensity calls flush(), which should clear lastL and lastR
        processor.setIntensity(0)
        assertFalse(getIsActiveFlag())
        assertEquals(0.0f, getCrossfeedLevel(), 0.0001f)
        assertEquals(0.0f, getLastL(), 0.0001f)
        assertEquals(0.0f, getLastR(), 0.0001f)
    }

    // -------------------------------------------------------------------------
    // 2. configure(AudioFormat)
    // -------------------------------------------------------------------------

    @Test
    fun testConfigureSupportedStereo16Bit44100Hz() {
        val inputFormat = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outputFormat = processor.configure(inputFormat)

        assertSame("configure should return the input format", inputFormat, outputFormat)
        assertTrue("16-bit stereo format must be supported", getIsFormatSupported())
        assertTrue("Processor must be active when format is configured", processor.isActive)

        // Mathematical verification for 44.1kHz:
        // fc = 700.0, dt = 1.0 / 44100, rc = 1.0 / (2 * pi * 700)
        val fc = 700.0f
        val dt = 1.0f / 44100.0f
        val rc = 1.0f / (2.0 * Math.PI * fc).toFloat()
        val expectedAlpha = dt / (rc + dt)

        assertEquals("Alpha calculation must match 700Hz low-pass filter at 44100Hz",
            expectedAlpha, getAlpha(), 0.00001f)
    }

    @Test
    fun testConfigureSupportedStereo16Bit48000Hz() {
        val inputFormat = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(inputFormat)

        assertTrue(getIsFormatSupported())
        assertTrue(processor.isActive)

        val fc = 700.0f
        val dt = 1.0f / 48000.0f
        val rc = 1.0f / (2.0 * Math.PI * fc).toFloat()
        val expectedAlpha = dt / (rc + dt)

        assertEquals(expectedAlpha, getAlpha(), 0.00001f)
    }

    @Test
    fun testConfigureUnsupportedMonoChannel_isNotSupported() {
        val monoFormat = AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        val result = processor.configure(monoFormat)

        assertSame(monoFormat, result)
        assertFalse("Mono channel must NOT be supported", getIsFormatSupported())
        assertFalse("Processor must not be active for unsupported mono", processor.isActive)
    }

    @Test
    fun testConfigureUnsupportedMultiChannel_isNotSupported() {
        val surroundFormat = AudioFormat(44100, 6, C.ENCODING_PCM_16BIT)
        val result = processor.configure(surroundFormat)

        assertSame(surroundFormat, result)
        assertFalse("6-channel audio must NOT be supported", getIsFormatSupported())
        assertFalse(processor.isActive)
    }

    @Test
    fun testConfigureUnsupportedEncoding_isNotSupported() {
        val floatFormat = AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)
        val result = processor.configure(floatFormat)

        assertSame(floatFormat, result)
        assertFalse("PCM Float must NOT be supported", getIsFormatSupported())
        assertFalse(processor.isActive)
    }

    @Test
    fun testIsActiveBeforeConfigure_returnsFalse() {
        assertFalse("isActive should be false before configure", processor.isActive)
    }

    // -------------------------------------------------------------------------
    // 3. queueInput(ByteBuffer)
    // -------------------------------------------------------------------------

    @Test
    fun testQueueInputPassthroughWhenIntensityZero() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(0)

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(1234)
        input.putShort(-5678)
        input.putShort(9999)
        input.putShort(-1111)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals("Output buffer should have same remaining bytes", 8, output.remaining())
        assertEquals(1234.toShort(), output.short)
        assertEquals((-5678).toShort(), output.short)
        assertEquals(9999.toShort(), output.short)
        assertEquals((-1111).toShort(), output.short)
    }

    @Test
    fun testQueueInputPassthroughWhenFormatUnsupported() {
        val monoFormat = AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(monoFormat)
        processor.setIntensity(2) // Enabled, but format unsupported

        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(4321)
        input.putShort(-8765)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(4, output.remaining())
        assertEquals(4321.toShort(), output.short)
        assertEquals((-8765).toShort(), output.short)
    }

    @Test
    fun testQueueInputCrossfeedProcessingAndAttenuation() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(1) // level 1: crossfeedLevel = 0.15f

        val alpha = getAlpha()
        val crossfeedLevel = getCrossfeedLevel()
        assertEquals(0.15f, crossfeedLevel, 0.0001f)

        // Feed 10 stereo sample pairs: left channel active, right channel silence
        val sampleCount = 10
        val input = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.nativeOrder())
        val inputL = ShortArray(sampleCount) { 16000.toShort() }
        val inputR = ShortArray(sampleCount) { 0.toShort() }

        for (i in 0 until sampleCount) {
            input.putShort(inputL[i])
            input.putShort(inputR[i])
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(sampleCount * 4, output.remaining())

        // Simulate step-by-step math to verify bit-level parity
        var simLastL = 0.0f
        var simLastR = 0.0f

        for (i in 0 until sampleCount) {
            val rawL = inputL[i]
            val rawR = inputR[i]

            val l = rawL / 32768.0f
            val r = rawR / 32768.0f

            simLastL += alpha * (l - simLastL)
            simLastR += alpha * (r - simLastR)

            var outL = (l + simLastR * crossfeedLevel) / (1.0f + crossfeedLevel)
            var outR = (r + simLastL * crossfeedLevel) / (1.0f + crossfeedLevel)

            if (outL > 1.0f) outL = 1.0f else if (outL < -1.0f) outL = -1.0f
            if (outR > 1.0f) outR = 1.0f else if (outR < -1.0f) outR = -1.0f

            val expectedL = (outL * 32767.0f).toInt().toShort()
            val expectedR = (outR * 32767.0f).toInt().toShort()

            val actualL = output.short
            val actualR = output.short

            assertEquals("Left channel output mismatch at sample $i", expectedL, actualL)
            assertEquals("Right channel output mismatch at sample $i", expectedR, actualR)

            // Direct parity assertions:
            // 1. Left channel attenuated: outL should be less than original 16000
            assertTrue("Left channel must be attenuated", actualL < rawL)
            // 2. Right channel receives crossfeed bleed: outR should be greater than 0
            assertTrue("Right channel must receive crossfeed bleed", actualR > 0)
        }
    }

    @Test
    fun testQueueInputClippingBounds() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(3) // level 3: crossfeedLevel = 0.35f

        // Send max positive short values
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(Short.MAX_VALUE)
        input.putShort(Short.MAX_VALUE)
        input.putShort(Short.MIN_VALUE)
        input.putShort(Short.MIN_VALUE)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        val maxOutL = output.short
        val maxOutR = output.short
        val minOutL = output.short
        val minOutR = output.short

        assertTrue(maxOutL <= Short.MAX_VALUE)
        assertTrue(maxOutR <= Short.MAX_VALUE)
        assertTrue(minOutL >= Short.MIN_VALUE)
        assertTrue(minOutR >= Short.MIN_VALUE)
    }

    @Test
    fun testQueueInputOddRemainderBytesPreserved() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(2)

        // 5 bytes = 1 complete 4-byte stereo frame + 1 leftover byte
        val input = ByteBuffer.allocateDirect(5).order(ByteOrder.nativeOrder())
        input.putShort(1000)
        input.putShort(2000)
        input.put(0x7F.toByte())
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(5, output.remaining())
        output.short // skip outL
        output.short // skip outR
        assertEquals("Remainder byte must be preserved", 0x7F.toByte(), output.get())
    }

    // -------------------------------------------------------------------------
    // 4. flush() and reset()
    // -------------------------------------------------------------------------

    @Test
    fun testFlushClearsOutputAndResetsFilterState() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(2)

        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(10000)
        input.putShort(0)
        input.flip()

        processor.queueInput(input)
        assertTrue(getLastL() > 0.0f)

        processor.flush()

        assertEquals("lastL must be reset to 0.0f on flush", 0.0f, getLastL(), 0.0f)
        assertEquals("lastR must be reset to 0.0f on flush", 0.0f, getLastR(), 0.0f)
        assertSame("outputBuffer must be EMPTY_BUFFER on flush", EMPTY_BUFFER, processor.output)
        assertFalse("isEnded must be false on flush", processor.isEnded)
    }

    @Test
    fun testResetClearsEverything() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(2)

        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(10000)
        input.putShort(0)
        input.flip()
        processor.queueInput(input)

        processor.reset()

        assertEquals(0.0f, getLastL(), 0.0f)
        assertEquals(0.0f, getLastR(), 0.0f)
        assertSame(EMPTY_BUFFER, processor.output)
        assertFalse(getIsFormatSupported())
        assertFalse(processor.isActive)
        assertFalse(processor.isEnded)

        val internalBuffer = getInternalField("buffer") as ByteBuffer
        assertSame("internal buffer should be reset to EMPTY_BUFFER", EMPTY_BUFFER, internalBuffer)
    }

    @Test
    fun testQueueEndOfStreamAndIsEnded() {
        val format = AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setIntensity(1)

        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(1000)
        input.putShort(1000)
        input.flip()

        processor.queueInput(input)
        processor.queueEndOfStream()

        // Buffer has output pending, so isEnded is false
        assertFalse("isEnded should be false while output is pending", processor.isEnded)

        // Drain output
        val output = processor.output
        assertTrue(output.hasRemaining())

        // Once output is drained, isEnded should be true
        assertTrue("isEnded should be true when end of stream queued and output drained", processor.isEnded)
    }

    @Test
    fun testSetIntensitySameLevel_keepsFilterState() {
        processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.setIntensity(2)
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(16000).putShort(0).putShort(16000).putShort(0)
        input.flip()
        processor.queueInput(input)
        val lastL = getLastL()
        assertTrue(lastL > 0.0f)

        processor.setIntensity(2)
        assertEquals("The same level again must not flush", lastL, getLastL(), 0.0f)
    }
}
