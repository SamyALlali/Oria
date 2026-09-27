package com.htc.vive.eagle.hackathon.starter.oria.ml

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class DepthImageProcessingTest {
    private val names = listOf("synthetic_portrait", "synthetic_landscape")
    private data class Pixels(val width: Int, val height: Int, val argb: IntArray)
    private fun image(name: String): Pixels {
        val bytes = javaClass.getResourceAsStream("/depth/$name.argb32")!!.use { it.readBytes() }
        val ints = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val width = ints.get(); val height = ints.get()
        return Pixels(width, height, IntArray(width * height).also { ints.get(it) })
    }
    private fun floats(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/depth/$name.f32")!!.use { it.readBytes() }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
    @Test fun rgbAntialiasResizeMatchesPillowForPortraitAndLandscape() {
        for (name in names) {
            val source = image(name); val reference = image("$name.input")
            val pixels = source.argb
            val before = pixels.copyOf()
            val actual = DepthImageProcessing.resizedRgb(pixels, source.width, source.height)
            assertArrayEquals(name, reference.argb, actual)
            assertArrayEquals(before, pixels)
            val tensor = DepthImageProcessing.preprocess(pixels, source.width, source.height)
            assertEquals(3 * 252 * 252, tensor.size)
            assertTrue(tensor.all { it.isFinite() && it in -3f..3f })
        }
    }
    @Test fun floatingBilinearCompactMatchesPillow() {
        for (name in names) {
            val expected = floats("$name.compact")
            val actual = DepthImageProcessing.compact(floats("$name.raw"))
            assertTrue("$name compact", expected.indices.maxOf { abs(expected[it] - actual[it]) } <= 1e-5f)
        }
    }
    @Test fun percentileNormalizedMapsMatchMacReference() {
        for (name in names) {
            val expected = floats("$name.normalized")
            val actual = DepthImageProcessing.normalize(DepthImageProcessing.compact(floats("$name.raw")))
            assertTrue(actual.available)
            assertTrue("$name normalized", expected.indices.maxOf { abs(expected[it] - actual.values[it]) } <= 1e-6f)
        }
    }
    @Test fun normalizationRejectsFlatMapAndNonFiniteValues() {
        val flat = DepthImageProcessing.normalize(FloatArray(128 * 128) { 8f })
        assertFalse(flat.available); assertEquals("degenerate_relative_depth", flat.reason); assertTrue(flat.values.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { DepthImageProcessing.normalize(FloatArray(16384) { Float.NaN }) }
        assertThrows(IllegalArgumentException::class.java) { DepthImageProcessing.compact(FloatArray(252 * 252) { Float.POSITIVE_INFINITY }) }
        assertThrows(IllegalArgumentException::class.java) { DepthImageProcessing.preprocess(IntArray(1), -1, 1) }
    }
    @Test fun qualitySeparatesDarkFlatAndTexturedSignals() {
        val dark = DepthImageProcessing.quality(IntArray(100) { 0xff000000.toInt() }, 10, 10)
        assertFalse(dark.usable); assertEquals("low_light", dark.reason)
        val flat = DepthImageProcessing.quality(IntArray(100) { 0xff808080.toInt() }, 10, 10)
        assertFalse(flat.usable); assertEquals("low_texture", flat.reason)
        for (name in names) {
            val source = image(name)
            val pixels = source.argb
            val quality = DepthImageProcessing.quality(pixels, source.width, source.height)
            assertTrue(name, quality.usable)
        }
    }
    @Test fun positiveAffineDepthPreservesRelativeNormalization() {
        val original = FloatArray(16384) { (it % 400) / 100f }
        val a = DepthImageProcessing.normalize(original).values
        val b = DepthImageProcessing.normalize(FloatArray(original.size) { original[it] * 3.25f + 17f }).values
        assertTrue(a.indices.maxOf { abs(a[it] - b[it]) } < 1e-6f)
    }
    @Test fun histogramQualityPercentileMatchesSorted8BitReference() {
        val random = java.util.Random(20260927)
        val signals = listOf(
            IntArray(16384), IntArray(16384) { 255 },
            IntArray(16384) { if (it < 15564) 23 else 255 },
            IntArray(16384) { if (it < 15564) 24 else 255 },
            IntArray(16384) { it % 256 }, IntArray(16384) { random.nextInt(256) },
        )
        for (luma in signals) {
            val argb = IntArray(luma.size) { 0xff000000.toInt() or (luma[it] shl 16) or (luma[it] shl 8) or luma[it] }
            val actual = DepthImageProcessing.quality(argb, 128, 128)
            val expected = DepthImageProcessing.percentile(FloatArray(luma.size) { luma[it].toFloat() }.sortedArray(), 95.0)
            assertEquals(expected, actual.lumaP95, 0.0)
            assertEquals(luma.count { it <= 8 }.toDouble() / luma.size, actual.darkFraction, 0.0)
        }
    }

    @Test fun filterCacheDoesNotMixKernelsDimensionsOrConcurrentImages() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks = (0 until 8).map { index -> executor.submit<Boolean> {
                val name = names[index % names.size]
                val source = image(name); val reference = image("$name.input")
                val actual = DepthImageProcessing.resizedRgb(source.argb, source.width, source.height)
                assertArrayEquals(reference.argb, actual)
                assertTrue(DepthImageProcessing.quality(source.argb, source.width, source.height).usable)
                val compact = DepthImageProcessing.compact(floats("$name.raw"))
                val expected = floats("$name.compact")
                assertTrue(expected.indices.maxOf { abs(expected[it] - compact[it]) } <= 1e-5f)
                true
            } }
            tasks.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { executor.shutdownNow() }
    }

}
