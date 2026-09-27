package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class RgbDiagnosticContractTest {
    private fun detection(id: Int = 0, left: Float = .35f) = Detection(id, .96f, Box(left, .25f, left + .25f, .95f))

    @Test fun diagnosticCandidatesRemainAvailableDuringVoiceCooldownWithoutReofferingSpeech() {
        val engine = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1)).apply { start(73, 0) }
        val objects = listOf(detection(), detection(1))
        val first = engine.evaluate(DetectionFrame(73, 1, 0, objects), 0)
        assertEquals("Véhicule devant", first.eligibleAlert?.text)
        assertEquals(2, first.candidates.size)
        assertTrue(first.candidates.all { it.sessionId == 73L && it.frameId == 1L && it.generation == first.generation })
        assertNotEquals(first.sessionId, first.generation)
        val ticket = requireNotNull(engine.onSubmitted(requireNotNull(first.eligibleAlert), 0))
        assertTrue(engine.onConfirmed(ticket, 10))
        val second = engine.evaluate(DetectionFrame(73, 2, 100, objects), 100)
        assertEquals(2, second.candidates.size)
        assertNull(second.eligibleAlert)
        assertEquals(RgbSuppressionReason.GLOBAL_PACING, second.suppressionReason)
    }

    @Test fun occlusionIsNotAConfirmedCurrentObservationAndExpiryIsExplicit() {
        val engine = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1)).apply { start(73, 0) }
        val visible = engine.evaluate(DetectionFrame(73, 1, 0, listOf(detection())), 0)
        assertEquals(RgbTrackObservationState.VISIBLE, visible.tracks.single().observationState)
        val missing = engine.evaluate(DetectionFrame(73, 2, 200, emptyList()), 200)
        assertEquals(RgbTrackObservationState.OCCLUDED, missing.tracks.single().observationState)
        assertTrue(missing.candidates.isEmpty())
        assertNull(missing.eligibleAlert)
        val expired = engine.current(751)
        assertTrue(expired.tracks.isEmpty())
        assertEquals(RgbTrackRetirementReason.EXPIRED, expired.retiredTracks.single().reason)
        assertEquals(73L, expired.retiredTracks.single().sessionId)
        assertEquals(0L, expired.retiredTracks.single().lastObservedAtMs)
        assertTrue(engine.current(752).retiredTracks.isEmpty())
    }

    @Test fun capacityRetirementDoesNotInventWorldIdentity() {
        val engine = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1, maximumTracks = 1)).apply { start(73, 0) }
        engine.evaluate(DetectionFrame(73, 1, 0, listOf(detection(left = .02f))), 0)
        val replacement = engine.evaluate(DetectionFrame(73, 2, 100, listOf(detection(left = .7f))), 100)
        assertEquals(RgbTrackRetirementReason.CAPACITY, replacement.retiredTracks.single().reason)
        assertEquals(1L, replacement.retiredTracks.single().trackId)
        assertEquals(2L, replacement.tracks.single().id)
        assertEquals(RgbTrackingMode.LEGACY_IOU, engine.config.trackingMode)
    }

    @Test fun newSessionDropsAllExposedCandidatesAndRetirementHistory() {
        val engine = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1)).apply { start(73, 0) }
        engine.evaluate(DetectionFrame(73, 1, 0, listOf(detection())), 0)
        engine.stop()
        engine.start(80, 100)
        val next = engine.current(100)
        assertEquals(80L, next.sessionId)
        assertTrue(next.candidates.isEmpty())
        assertTrue(next.retiredTracks.isEmpty())
        assertNull(next.eligibleAlert)
    }
}
