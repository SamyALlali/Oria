package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class RelativeDepthEvidenceAdapterTest {
    private fun map(at: Long = 0, transform: (x: Int, y: Int) -> Float): RelativeDepthMap {
        val width = 64
        val height = 48
        return RelativeDepthMap(width, height,
            FloatArray(width * height) { index -> transform(index % width, index / width) },
            at, 1920, 1080)
    }

    @Test fun nearAndFarOrderingIsMonotonicWithoutInventingMetres() {
        val frame = map { x, _ -> x.toFloat() / 63f }
        val adapter = RelativeDepthEvidenceAdapter()
        val far = adapter.evidence(1, Box(.05f, .1f, .30f, .9f), frame, 10)
        val near = adapter.evidence(2, Box(.70f, .1f, .95f, .9f), frame, 10)
        assertTrue(far.available && near.available)
        assertTrue(requireNotNull(near.relativeProximity) > requireNotNull(far.relativeProximity))
        assertNull(near.unavailableReason)
    }

    @Test fun lowerCentralMedianRejectsIsolatedBackgroundPixel() {
        val clean = map { x, _ -> x.toFloat() / 63f }
        val contaminated = clean.inverseDepth.copyOf().also { it[36 * clean.width + 32] = 1000f }
        val adapter = RelativeDepthEvidenceAdapter()
        val a = adapter.evidence(1, Box(.35f, .1f, .65f, .95f), clean, 10)
        adapter.reset()
        val b = adapter.evidence(1, Box(.35f, .1f, .65f, .95f), clean.copy(inverseDepth = contaminated), 10)
        assertEquals(requireNotNull(a.relativeProximity), requireNotNull(b.relativeProximity), .01f)
    }

    @Test fun trendUsesOnlyFreshConfidentRelativeEvidence() {
        val adapter = RelativeDepthEvidenceAdapter()
        val first = map(0) { x, _ -> x.toFloat() / 63f }
        val second = map(100) { x, _ -> (x.toFloat() / 63f).let { if (x in 20..40) it + .4f else it } }
        assertEquals(OriaDepthTrend.UNKNOWN,
            adapter.evidence(7, Box(.30f, .1f, .65f, .95f), first, 0).trend)
        assertEquals(OriaDepthTrend.APPROACHING,
            adapter.evidence(7, Box(.30f, .1f, .65f, .95f), second, 100).trend)
    }

    @Test fun flatLowLightLikeMapFallsBackWithExplicitReason() {
        val evidence = RelativeDepthEvidenceAdapter().evidence(1, Box(.1f, .1f, .9f, .9f),
            map { _, _ -> .5f }, 0)
        assertFalse(evidence.available)
        assertEquals(OriaDepthUnavailableReason.LOW_CONFIDENCE, evidence.unavailableReason)
        assertNull(evidence.relativeProximity)
    }

    @Test fun staleAndInvalidMapsCannotBecomeEvidence() {
        val adapter = RelativeDepthEvidenceAdapter()
        val stale = adapter.frontalObstruction(map { x, _ -> x / 63f }, 501)
        assertEquals(OriaDepthUnavailableReason.STALE, stale.unavailableReason)
        val invalid = map { _, _ -> Float.NaN }
        assertEquals(OriaDepthUnavailableReason.INVALID_MAP,
            adapter.frontalObstruction(invalid, 0).unavailableReason)
    }

    @Test fun frontalCorridorIsIndependentOfDetectorClasses() {
        val evidence = RelativeDepthEvidenceAdapter().frontalObstruction(
            map { x, y -> if (x in 22..42 && y > 25) 2f else x / 63f }, 0)
        assertTrue(evidence.available)
        assertEquals(OriaDepthRegion.FRONTAL_CORRIDOR, evidence.region)
        assertTrue(requireNotNull(evidence.relativeProximity) > .8f)
    }

    @Test fun normalizedAlignmentSurvivesDifferentSourceAspectRatios() {
        val values = map { x, _ -> x / 63f }
        val a = values.copy(sourceWidth = 1920, sourceHeight = 1080)
        val b = values.copy(sourceWidth = 1080, sourceHeight = 1920)
        val box = Box(.65f, .1f, .95f, .9f)
        val adapter = RelativeDepthEvidenceAdapter()
        val first = adapter.evidence(1, box, a, 0)
        adapter.reset()
        val second = adapter.evidence(1, box, b, 0)
        assertEquals(first.relativeProximity, second.relativeProximity)
    }

    @Test fun missingModelStateIsExplicitAndContainsNoDistance() {
        val evidence = RelativeDepthEvidenceAdapter().unavailable(OriaDepthUnavailableReason.MODEL_MISSING, 20)
        assertFalse(evidence.available)
        assertNull(evidence.relativeInverseDepth)
        assertNull(evidence.relativeProximity)
    }
}
