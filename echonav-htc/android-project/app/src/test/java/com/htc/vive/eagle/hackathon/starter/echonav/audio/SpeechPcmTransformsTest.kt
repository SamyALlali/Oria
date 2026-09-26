package com.htc.vive.eagle.hackathon.starter.echonav.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechPcmTransformsTest {
    @Test fun monoSignedExtremesBecomeExactLittleEndianStereoFrames() {
        val mono = byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, 0x34, 0x12, -1, 0x7f)
        val centered = SpeechPcmTransforms.normalizeStereo(SpeechPcm(22_050, 1, mono))
        assertEquals(2, centered.channels)
        assertEquals(22_050, centered.sampleRate)
        assertEquals(6, centered.bytes.size / centered.frameBytes)
        assertArrayEquals(byteArrayOf(
            0, -128, 0, -128,
            -1, -1, -1, -1,
            0, 0, 0, 0,
            1, 0, 1, 0,
            0x34, 0x12, 0x34, 0x12,
            -1, 0x7f, -1, 0x7f), centered.bytes)
    }

    @Test fun stereoInputIsCenteredWithoutOverflowOrAmplification() {
        val source = SpeechPcm(48_000, 2, samples(
            -32768, -32768, 32767, 32767, 32767, -32768,
            100, -50, -100, 50, 1, 0, -1, 0))
        val centered = SpeechPcmTransforms.normalizeStereo(source)
        assertArrayEquals(samples(-32768, -32768, 32767, 32767, 0, 0, 25, 25, -25, -25, 0, 0, 0, 0), centered.bytes)
        assertEquals(source.bytes.size, centered.bytes.size)
        assertEquals(source.sampleRate, centered.sampleRate)
    }

    @Test fun leftRightUseExactSeventyThirtyAmplitudeAndCenterKeepsHistoricalVolume() {
        val pcm = SpeechPcmTransforms.normalizeStereo(SpeechPcm(16_000, 1, samples(-32768, -1234, 0, 4321, 32767)))
        assertArrayEquals(samples(-22938, -9830, -864, -370, 0, 0, 3025, 1296, 22937, 9830), SpeechPcmTransforms.panned(pcm, SpeechPan.LEFT).bytes)
        assertArrayEquals(samples(-9830, -22938, -370, -864, 0, 0, 1296, 3025, 9830, 22937), SpeechPcmTransforms.panned(pcm, SpeechPan.RIGHT).bytes)
        assertArrayEquals(samples(-32768, -32768, -1234, -1234, 0, 0, 4321, 4321, 32767, 32767), SpeechPcmTransforms.panned(pcm, SpeechPan.CENTER).bytes)
    }

    @Test fun halfStepsRoundSymmetricallyAwayFromZeroWithoutSilenceBias() {
        val pcm = SpeechPcmTransforms.normalizeStereo(SpeechPcm(16_000, 1, samples(-25, -15, -5, -1, 0, 1, 5, 15, 25)))
        assertArrayEquals(samples(-18, -8, -11, -5, -4, -2, -1, 0, 0, 0, 1, 0, 4, 2, 11, 5, 18, 8),
            SpeechPcmTransforms.panned(pcm, SpeechPan.LEFT).bytes)
    }

    @Test fun everySignedSampleHasBoundedQuantizationNoSaturationAndMirroredChannels() {
        val raw = ByteBuffer.allocate(65_536 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in -32768..32767) raw.putShort(sample.toShort())
        val centered = SpeechPcmTransforms.normalizeStereo(SpeechPcm(48_000, 1, raw.array()))
        val left = ByteBuffer.wrap(SpeechPcmTransforms.panned(centered, SpeechPan.LEFT).bytes).order(ByteOrder.LITTLE_ENDIAN)
        val right = ByteBuffer.wrap(SpeechPcmTransforms.panned(centered, SpeechPan.RIGHT).bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in -32768..32767) {
            val dominant = left.short.toInt()
            val residual = left.short.toInt()
            assertEquals(residual, right.short.toInt())
            assertEquals(dominant, right.short.toInt())
            // Error <= half of one quantization step, independently of the gain implementation.
            assertTrue(kotlin.math.abs(dominant * 10 - sample * 7) <= 5)
            assertTrue(kotlin.math.abs(residual * 10 - sample * 3) <= 5)
            assertTrue(kotlin.math.abs(dominant) <= kotlin.math.abs(sample))
            assertTrue(kotlin.math.abs(residual) <= kotlin.math.abs(dominant))
            assertTrue(dominant * sample >= 0 && residual * sample >= 0)
        }
        assertArrayEquals(centered.bytes, SpeechPcmTransforms.panned(centered, SpeechPan.CENTER).bytes)
    }

    @Test fun panningOwnsItsBuffersAndCannotCorruptCachedOrLaterRequests() {
        val input = samples(-101, 202, 303)
        val original = input.copyOf()
        val cache = SpeechPcmTransforms.normalizeStereo(SpeechPcm(24_000, 1, input))
        val cachedBytes = cache.bytes.copyOf()
        val left = SpeechPcmTransforms.panned(cache, SpeechPan.LEFT)
        val center = SpeechPcmTransforms.panned(cache, SpeechPan.CENTER)
        val right = SpeechPcmTransforms.panned(cache, SpeechPan.RIGHT)
        assertNotSame(input, cache.bytes)
        assertNotSame(cache.bytes, center.bytes)
        assertNotSame(left.bytes, right.bytes)
        left.bytes.fill(99)
        center.bytes.fill(-99)
        assertArrayEquals(original, input)
        assertArrayEquals(cachedBytes, cache.bytes)
        assertArrayEquals(samples(-30, -71, 61, 141, 91, 212), right.bytes)
        assertArrayEquals(cachedBytes, SpeechPcmTransforms.panned(cache, SpeechPan.CENTER).bytes)
    }

    @Test fun normalizationAndPanPreserveEightSecondBoundaryAndFrameCount() {
        val pcm = SpeechPcm(8_000, 1, ByteArray(8_000 * 8 * 2))
        val result = SpeechPcmTransforms.panned(SpeechPcmTransforms.normalizeStereo(pcm), SpeechPan.RIGHT)
        assertEquals(pcm.bytes.size / pcm.frameBytes, result.bytes.size / result.frameBytes)
        assertEquals(pcm.sampleRate, result.sampleRate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialStereoFrameIsRejectedInsteadOfShiftingChannels() {
        SpeechPcmTransforms.normalizeStereo(SpeechPcm(16_000, 2, byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun uncenteredStereoCannotBeUsedAsPreparedCache() {
        SpeechPcmTransforms.panned(SpeechPcm(16_000, 2, samples(123, -456)), SpeechPan.CENTER)
    }

    @Test(expected = IllegalArgumentException::class)
    fun phraseOverEightSecondsIsRejectedBeforeAllocationOfStereoCopy() {
        SpeechPcmTransforms.normalizeStereo(SpeechPcm(8_000, 1, ByteArray((8_000 * 8 + 1) * 2)))
    }

    private fun samples(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
        }.array()
}
