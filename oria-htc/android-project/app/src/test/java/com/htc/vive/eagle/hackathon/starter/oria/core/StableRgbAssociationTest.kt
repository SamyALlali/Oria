package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class StableRgbAssociationTest {
    private fun person(left: Float, width: Float = .15f, confidence: Float = .95f) =
        Detection(0, confidence, Box(left, .3f, left + width, .85f))

    private fun engine(mode: RgbTrackingMode = RgbTrackingMode.STABLE_RGB_V2) =
        RgbAlertEngine(RgbAlertConfig(trackingMode = mode)).also { it.start(1, 0) }

    private fun step(e: RgbAlertEngine, id: Long, at: Long, vararg detections: Detection) =
        e.evaluate(DetectionFrame(1, id, at, detections.toList()), at)

    @Test fun boundedMotionRecoveryPreservesConfirmedVoiceMemoryWithoutLoweringIou() {
        val stable = engine()
        val legacy = engine(RgbTrackingMode.LEGACY_IOU)
        for (e in listOf(stable, legacy)) {
            step(e, 1, 0, person(.1f))
            val alert = requireNotNull(step(e, 2, 250, person(.15f)).eligibleAlert)
            assertTrue(e.onConfirmed(requireNotNull(e.onSubmitted(alert, 250)), 251))
        }
        assertTrue(RgbAlertPolicy.intersectionOverUnion(person(.15f).box, person(.25f).box) < .25f)
        val recovered = step(stable, 3, 500, person(.25f))
        assertEquals(1L, recovered.tracks.single().id)
        assertEquals(RgbAssociationStatus.MOTION_RECOVERY, recovered.tracks.single().associationStatus)
        assertEquals(RgbSuppressionReason.GLOBAL_PACING, recovered.suppressionReason)
        assertNull(recovered.eligibleAlert)
        assertNotEquals(1L, step(legacy, 3, 500, person(.25f)).tracks.single { it.visibleInLatestFrame }.id)
        step(stable, 4, 750, person(.25f))
        step(stable, 5, 1000, person(.25f))
        val stillRemembered = step(stable, 6, 1251, person(.25f))
        assertEquals(RgbSuppressionReason.SAME_ENTITY_COOLDOWN, stillRemembered.suppressionReason)
        assertNull(stillRemembered.eligibleAlert)
    }

    @Test fun lowConfidenceRecoveryCannotLendOldConfirmationToNewObservations() {
        val e = engine()
        step(e, 1, 0, person(.1f))
        step(e, 2, 250, person(.15f))
        val low = step(e, 3, 500, person(.25f, confidence = .74f))
        assertEquals(1L, low.tracks.single().id)
        assertEquals(0, low.tracks.single().confirmationSamples)
        assertNull(low.selected)
        assertNull(step(e, 4, 750, person(.28f)).eligibleAlert)
        assertNotNull(step(e, 5, 1000, person(.30f)).eligibleAlert)
    }

    @Test fun competingContinuationsOfNewTrackResetBothConfirmations() {
        val e = engine()
        val old = step(e, 1, 0, person(.2f, .4f)).tracks.single().id
        val ambiguous = step(e, 2, 250, person(.15f, .25f), person(.45f, .25f))
        assertEquals(2, ambiguous.tracks.size)
        assertTrue(ambiguous.tracks.none { it.id == old || it.confirmed })
        assertTrue(ambiguous.tracks.all { it.associationStatus == RgbAssociationStatus.AMBIGUOUS_NEW && it.confirmationSamples == 1 })
        assertEquals(listOf(old), ambiguous.retiredTracks.map { it.trackId })
        assertTrue(ambiguous.retiredTracks.all { it.reason == RgbTrackRetirementReason.AMBIGUOUS })
        assertNull(ambiguous.eligibleAlert)
        val resolved = step(e, 3, 500, person(.15f, .25f), person(.45f, .25f))
        assertTrue(resolved.tracks.all { it.confirmed })
        assertEquals(ambiguous.tracks.map { it.id }, resolved.tracks.map { it.id })
    }

    @Test fun crossingRejectsDisagreementBetweenOverlapAndMotionWithoutMergingObjects() {
        val e = engine()
        step(e, 1, 0, person(.1f, .2f), person(.65f, .2f))
        step(e, 2, 250, person(.2f, .2f), person(.55f, .2f))
        step(e, 3, 500, person(.3f, .2f), person(.45f, .2f))
        val crossing = step(e, 4, 750, person(.4f, .2f), person(.35f, .2f))
        val visible = crossing.tracks.filter { it.visibleInLatestFrame }
        assertEquals(2, visible.size)
        assertEquals(2, visible.map { it.id }.toSet().size)
        assertTrue(visible.all { it.associationStatus == RgbAssociationStatus.AMBIGUOUS_NEW })
        assertNull(crossing.eligibleAlert)
    }

    @Test fun shortOcclusionRetainsMemoryButRequiresFreshConfirmationAndNoPredictedAlert() {
        val e = engine()
        step(e, 1, 0, person(.4f))
        val alert = requireNotNull(step(e, 2, 250, person(.42f)).eligibleAlert)
        assertTrue(e.onConfirmed(requireNotNull(e.onSubmitted(alert, 250)), 251))
        val occluded = step(e, 3, 500)
        assertNull(occluded.selected)
        assertEquals(RgbTrackObservationState.OCCLUDED, occluded.tracks.single().observationState)
        assertTrue(occluded.retiredTracks.isEmpty())
        val back = step(e, 4, 750, person(.43f))
        assertEquals(alert.trackId, back.tracks.single().id)
        assertEquals(RgbTrackObservationState.VISIBLE, back.tracks.single().observationState)
        assertEquals(1, back.tracks.single().confirmationSamples)
        assertNull(back.eligibleAlert)
        step(e, 5, 1000, person(.43f))
        val confirmed = step(e, 6, 1251, person(.43f))
        assertEquals(RgbSuppressionReason.SAME_ENTITY_COOLDOWN, confirmed.suppressionReason)
        assertNull(confirmed.eligibleAlert)
    }

    @Test fun expirationStartsNewTrackAndUnexpectedJumpIsNotReidentified() {
        val e = engine()
        val first = step(e, 1, 0, person(.1f)).tracks.single().id
        step(e, 2, 250, person(.15f))
        val jumped = step(e, 3, 500, person(.7f)).tracks.single { it.visibleInLatestFrame }
        assertNotEquals(first, jumped.id)
        assertEquals(RgbAssociationStatus.NEW, jumped.associationStatus)
        assertFalse(jumped.confirmed)
        val expired = step(e, 4, 1501, person(.7f))
        assertEquals(setOf(first, jumped.id), expired.retiredTracks.map { it.trackId }.toSet())
        assertTrue(expired.retiredTracks.all { it.reason == RgbTrackRetirementReason.EXPIRED })
        val later = expired.tracks.single()
        assertNotEquals(jumped.id, later.id)
        assertFalse(later.confirmed)
    }

    @Test fun predictionCannotJumpAcrossLongGapsOrExtrapolateScale() {
        val input = RgbAssociationInput(1, person(.2f), 250, person(.1f).box, 0)
        val noPrediction = StableRgbAssociation.associate(listOf(input), listOf(person(.45f)), 1000, .25f)
        assertTrue(noPrediction.matches.isEmpty())
        val rapidScale = input.copy(detection = person(.1f, .4f))
        val noScalePrediction = StableRgbAssociation.associate(listOf(rapidScale), listOf(person(.55f, .4f)), 500, .25f)
        assertTrue(noScalePrediction.matches.isEmpty())
    }

    @Test fun selectionAndAssociationAreDeterministicUnderReversedInputOrder() {
        val a = engine()
        val b = engine()
        for (step in 0..8) {
            val detections = listOf(person(.05f + .025f * step), person(.72f - .018f * step))
            val left = step(a, step + 1L, step * 250L, *detections.toTypedArray())
            val right = step(b, step + 1L, step * 250L, *detections.reversed().toTypedArray())
            assertEquals(left, right)
        }
    }

    @Test fun candidateModeKeepsSessionFreshnessAndAmbiguousAudioGuards() {
        val e = engine()
        step(e, 1, 0, person(.4f))
        val alert = requireNotNull(step(e, 2, 250, person(.4f)).eligibleAlert)
        val ticket = requireNotNull(e.onSubmitted(alert, 250))
        assertTrue(e.onAmbiguous(ticket, 300))
        assertEquals(RgbSuppressionReason.AUDIO_UNKNOWN, step(e, 3, 500, person(.4f)).suppressionReason)
        e.stop()
        e.start(2, 1000)
        assertFalse(e.onConfirmed(ticket, 1001))
        assertEquals(RgbFrameStatus.WRONG_SESSION, step(e, 4, 1100, person(.4f)).frameStatus)
        val stale = e.evaluate(DetectionFrame(2, 1, 1000, listOf(person(.4f))), 1501)
        assertEquals(RgbFrameStatus.STALE, stale.frameStatus)
        assertTrue(stale.tracks.isEmpty())
        assertNull(stale.eligibleAlert)
    }

    @Test fun denseAmbiguityAndRegistriesRemainBounded() {
        val e = RgbAlertEngine(RgbAlertConfig(trackingMode = RgbTrackingMode.STABLE_RGB_V2, maximumTracks = 16))
        e.start(1, 0)
        repeat(30) { iteration ->
            val crowd = (0 until 40).map { person(.02f * (it % 30) + .001f * (iteration % 2)) }
            val frame = step(e, iteration + 1L, iteration * 250L, *crowd.toTypedArray())
            assertTrue(frame.tracks.size <= 16)
            assertEquals(16, frame.tracks.count { it.visibleInLatestFrame })
            assertEquals(24, frame.rejectedDetectionCount)
        }
    }

    @Test fun capacityRetirementIsExplicitAndDoesNotTransferConfirmation() {
        val e = RgbAlertEngine(RgbAlertConfig(trackingMode = RgbTrackingMode.STABLE_RGB_V2,
            maximumTracks = 1))
        e.start(1, 0)
        val first = step(e, 1, 0, person(.1f)).tracks.single().id
        val replacement = step(e, 2, 250, person(.7f))
        assertEquals(listOf(first), replacement.retiredTracks.map { it.trackId })
        assertEquals(RgbTrackRetirementReason.CAPACITY, replacement.retiredTracks.single().reason)
        assertFalse(replacement.tracks.single().confirmed)
        assertEquals(RgbAssociationStatus.NEW, replacement.tracks.single().associationStatus)
    }
}
