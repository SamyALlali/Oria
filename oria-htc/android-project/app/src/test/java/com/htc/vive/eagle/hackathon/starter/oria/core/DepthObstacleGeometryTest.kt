package com.htc.vive.eagle.hackathon.starter.oria.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DepthObstacleGeometryTest {
    private fun plane(a: Float = .1f, b: Float = .7f) = FloatArray(128 * 128) { i ->
        (a * ((i % 128 + .5f) / 128) + b * ((i / 128 + .5f) / 128) + .1f).coerceIn(0f, 1f)
    }

    @Test fun invalidAndDegenerateMapsAreMissingNotEmptyCandidates() {
        for (values in listOf(null, floatArrayOf(0f), FloatArray(128 * 128), plane().also { it[0] = Float.NaN },
            plane().also { it[0] = Float.POSITIVE_INFINITY }, plane().also { it[0] = 1.1f })) {
            assertNull(DepthObstacleGeometry.compute(values).zones)
        }
    }

    @Test fun pureSupportedPlaneHasZeroCandidatesButNeverMeansClearPath() {
        val r = DepthObstacleGeometry.compute(plane())
        assertEquals("supported", r.reference.status)
        assertEquals(true, r.reference.dominantPlanarView)
        assertEquals(0, r.candidatePixels)
        assertTrue(r.rawCandidatePixels!! > 0)
        assertTrue(r.zones!!.all { it.candidateFraction == 0f })
    }

    @Test fun centerReliefAndSmallOrLowObjectsExposeRoiLimits() {
        fun relief(y0: Int, y1: Int, x0: Int, x1: Int): DepthGeometryResult {
            val values = plane()
            for (y in y0 until y1) for (x in x0 until x1) values[y * 128 + x] = (values[y * 128 + x] + .45f).coerceAtMost(1f)
            return DepthObstacleGeometry.compute(values)
        }
        assertEquals(1050, relief(40, 75, 50, 80).candidatePixels)
        assertEquals(0, relief(40, 44, 50, 54).candidatePixels)
        assertEquals(400, relief(82, 100, 45, 85).candidatePixels)
        assertEquals(0, relief(100, 120, 45, 85).candidatePixels)
    }

    @Test fun noReferencePlaneDoesNotDisableRawOccupancy() {
        val r = DepthObstacleGeometry.compute(plane(.6f, .7f))
        assertEquals("unavailable", r.reference.status)
        assertTrue(r.candidatePixels!! > 0)
    }

    @Test fun qualityMetadataPreservesRawDiagnosticsAndInputIsUnmodified() {
        val values = plane(.6f, .7f); val before = values.copyOf()
        val normal = DepthObstacleGeometry.compute(values)
        val limited = DepthObstacleGeometry.compute(values, false, "low_texture")
        assertEquals(normal.zones, limited.zones)
        assertFalse(limited.qualityUsable)
        assertArrayEquals(before, values, 0f)
        assertTrue(normal.candidateMask!!.contentEquals(DepthObstacleGeometry.compute(values).candidateMask!!))
    }

    @Test fun portableSyntheticFixtureCountsAgreeWithPython() {
        val fixtures = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "fixtures/depth_portability/geometry_cases.tsv") }.firstOrNull { it.isFile }
            ?: error("Missing portable depth fixtures")
        for (line in fixtures.readLines().drop(1).filter { it.isNotBlank() }) {
            val f = line.split('\t')
            val values = plane(f[1].toFloat(), f[2].toFloat())
            val y0 = f[3].toInt(); val y1 = f[4].toInt(); val x0 = f[5].toInt(); val x1 = f[6].toInt()
            if (y1 > y0) for (y in y0 until y1) for (x in x0 until x1) {
                values[y * 128 + x] = (values[y * 128 + x] + .45f).coerceAtMost(1f)
            }
            val actual = DepthObstacleGeometry.compute(values)
            assertEquals(f[0], f[7].toInt(), actual.candidatePixels)
            assertEquals(f[0], f[8], actual.reference.status)
        }
    }
}
