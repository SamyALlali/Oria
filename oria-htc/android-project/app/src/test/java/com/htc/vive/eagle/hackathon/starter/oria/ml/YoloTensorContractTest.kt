package com.htc.vive.eagle.hackathon.starter.oria.ml

import org.junit.Assert.*
import org.junit.Test

class YoloTensorContractTest {
    private fun output(vararg rows: FloatArray): FloatArray = FloatArray(300 * 6).also { out ->
        rows.forEachIndexed { index, row -> row.copyInto(out, index * 6) }
    }

    @Test fun portraitPaddingAndInversePreserveCameraDirections() {
        val t = LetterboxTransform(480, 856)
        assertEquals(233, t.resizedWidth)
        assertEquals(91, t.left)
        assertEquals(0, t.top)
        val box = t.toCameraBox(t.left.toFloat(), 0f, (t.left + t.resizedWidth).toFloat(), 416f)
        assertEquals(0f, box.left, 0.00001f)
        assertEquals(1f, box.right, 0.00001f)
        assertEquals(1f, box.bottom, 0.00001f)
    }

    @Test fun exactRasterInverseKeepsBothSidesOfDirectionBoundaries() {
        // Portrait and landscape; dimensions rounded both up and down by the real resize.
        for ((width, height) in listOf(467 to 832, 480 to 856, 832 to 467, 856 to 480)) {
            val t = LetterboxTransform(width, height)
            for (center in listOf(.3898f, .3902f, .6098f, .6102f)) {
                val box = t.toCameraBox(
                    t.left + (center - .02f) * t.resizedWidth,
                    t.top + (center - .03f) * t.resizedHeight,
                    t.left + (center + .02f) * t.resizedWidth,
                    t.top + (center + .03f) * t.resizedHeight,
                )
                assertEquals("X center $width x $height", center, box.centerX, .000001f)
                assertEquals("Y center $width x $height", center, (box.top + box.bottom) / 2f, .000001f)
                assertEquals("Left zone boundary", center < .39f, box.centerX < .39f)
                assertEquals("Right zone boundary", center > .61f, box.centerX > .61f)
            }
        }
    }

    @Test fun paddedCoordinatesAreClippedInBothOrientations() {
        for ((width, height) in listOf(467 to 832, 832 to 467)) {
            val t = LetterboxTransform(width, height)
            val covering = t.toCameraBox(-10f, -10f, 426f, 426f)
            assertEquals(0f, covering.left, 0f)
            assertEquals(0f, covering.top, 0f)
            assertEquals(1f, covering.right, 0f)
            assertEquals(1f, covering.bottom, 0f)
            val paddingOnly = if (t.left > 0) t.toCameraBox(1f, 10f, (t.left - 1).toFloat(), 50f)
                else t.toCameraBox(10f, 1f, 50f, (t.top - 1).toFloat())
            assertEquals(0f, paddingOnly.area, 0f)
        }
    }

    @Test fun endToEndRowsKeepDifferentClassesAndMoreThanEightObjects() {
        val rows = (0 until 12).map { i -> floatArrayOf(10f + i, 10f, 40f + i, 50f, .9f, (i % 6).toFloat()) }
        val detections = YoloTensorContract.decode(output(*rows.toTypedArray()), LetterboxTransform(416, 416))
        assertEquals(12, detections.size)
        assertEquals(6, detections.map { it.classId }.distinct().size)
    }

    @Test fun confidenceBoundaryAndPaddingOnlyBoxes() {
        val out = output(floatArrayOf(0f, 0f, 50f, 100f, .99f, 0f),
            floatArrayOf(100f, 10f, 200f, 200f, .7f, 3f),
            floatArrayOf(100f, 10f, 200f, 200f, .6999f, 2f))
        val detected = YoloTensorContract.decode(out, LetterboxTransform(480, 856))
        assertEquals(1, detected.size)
        assertEquals(3, detected.single().classId)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOldRawCoreMlLayout() {
        YoloTensorContract.decode(FloatArray(10 * 3549), LetterboxTransform(416, 416))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteModelOutput() {
        YoloTensorContract.decode(output(floatArrayOf(Float.NaN, 0f, 20f, 20f, .9f, 0f)), LetterboxTransform(416, 416))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsFractionalClass() {
        YoloTensorContract.decode(output(floatArrayOf(0f, 0f, 20f, 20f, .9f, 1.5f)), LetterboxTransform(416, 416))
    }
}
