package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class DepthObstaclePolicyTest {
    private fun zones(left: Float = 0f, center: Float = .4f, right: Float = 0f) = listOf(
        DepthZoneEvidence(RgbZone.LEFT, left, .9f), DepthZoneEvidence(RgbZone.CENTER, center, .1f),
        DepthZoneEvidence(RgbZone.RIGHT, right, .9f))
    private fun started(config: DepthObstacleConfig = DepthObstacleConfig()) = DepthObstaclePolicy(config).apply { start(1, 0) }
    private fun confirmed(p: DepthObstaclePolicy, values: List<DepthZoneEvidence> = zones()): DepthVoiceAlert {
        assertNull(p.evaluate(1, 0, 0, values, 0).eligibleAlert)
        assertNull(p.evaluate(1, 1, 250, values, 250).eligibleAlert)
        return p.evaluate(1, 2, 500, values, 500).eligibleAlert!!
    }

    @Test fun exactThresholdNeedsThreeFramesAndHalfSecond() {
        val p = started()
        assertEquals("confirming", p.evaluate(1, 0, 0, zones(center = .12f), 0).status)
        assertEquals("confirming", p.evaluate(1, 1, 100, zones(center = .12f), 100).status)
        assertEquals("confirming", p.evaluate(1, 2, 499, zones(center = .12f), 499).status)
        assertEquals(RgbZone.CENTER, p.evaluate(1, 3, 500, zones(center = .12f), 500).eligibleAlert!!.zone)
    }

    @Test fun directionCenterPriorityAndStableSideTieIgnoreMedian() {
        assertEquals(RgbZone.CENTER, confirmed(started(), zones(.9f, .12f, .9f)).zone)
        assertEquals(RgbZone.LEFT, confirmed(started(), zones(.4f, 0f, .4f)).zone)
        assertEquals(RgbZone.RIGHT, confirmed(started(), zones(.4f, 0f, .6f)).zone)
    }

    @Test fun obstaclePhrasesUseTheSameCameraRelativeVocabularyAsYolo() {
        assertEquals("Obstacle possible avant-gauche", confirmed(started(), zones(.4f, 0f, 0f)).text)
        assertEquals("Obstacle possible devant", confirmed(started()).text)
        assertEquals("Obstacle possible avant-droite", confirmed(started(), zones(0f, 0f, .4f)).text)
    }

    @Test fun intentionDoesNotConsumeCooldownAndNewFrameInvalidatesPreviousOffer() {
        val p = started(); val first = confirmed(p)
        val second = p.evaluate(1, 3, 750, zones(), 750).eligibleAlert!!
        assertNull(p.onSubmitted(first, 750))
        assertNotNull(p.onSubmitted(second, 750))
    }

    @Test fun copiedOrForgedOfferCannotReserve() {
        val p = started(); val alert = confirmed(p)
        assertNull(p.onSubmitted(alert.copy(), 500))
        assertNotNull(p.onSubmitted(alert, 500))
    }

    @Test fun submittedReservesAudioButOnlyCompletionStartsRepeatInterval() {
        val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
        assertEquals("audio_in_flight", p.evaluate(1, 3, 750, zones(), 750).reason)
        assertTrue(p.onCompleted(t, 1000))
        assertEquals("zone_cooldown", p.evaluate(1, 4, 1250, zones(.9f, .4f, .9f), 1250).reason)
        // Accepted consecutive observations keep evidence fresh until exactly 8s after completion.
        for (i in 5L..11L) p.evaluate(1, i, 1250 + (i - 4) * 1000, zones(), 1250 + (i - 4) * 1000)
        assertNotNull(p.evaluate(1, 12, 9000, zones(), 9000).eligibleAlert)
    }

    @Test fun failedPlaybackRetriesAfterOneSecondWithoutEightSecondPenalty() {
        val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
        assertTrue(p.onFailed(t, 600))
        assertEquals("retry_backoff", p.evaluate(1, 3, 1599, zones(), 1599).reason)
        assertNotNull(p.evaluate(1, 4, 1600, zones(), 1600).eligibleAlert)
    }

    @Test fun duplicateCompletionAndOldCallbackCannotConfirmNewTicket() {
        val p = started(); val old = p.onSubmitted(confirmed(p), 500)!!
        assertTrue(p.onFailed(old, 600))
        val next = p.onSubmitted(p.evaluate(1, 3, 1600, zones(), 1600).eligibleAlert!!, 1600)!!
        assertFalse(p.onCompleted(old, 1700))
        assertEquals(DepthAudioState.IN_FLIGHT, p.audioState)
        assertTrue(p.onCompleted(next, 1700))
        assertFalse(p.onCompleted(next, 1800))
    }

    @Test fun stopAndSameSessionRestartInvalidateGenerationAndRequireBackendReset() {
        val p = started(); val old = p.onSubmitted(confirmed(p), 500)!!
        p.stop(); p.start(1, 600)
        assertFalse(p.onCompleted(old, 700))
        assertEquals(DepthAudioState.UNKNOWN, p.audioState)
        p.onAudioReset()
        for (i in 0L..1L) p.evaluate(1, i, 600 + i * 250, zones(), 600 + i * 250)
        assertNotNull(p.evaluate(1, 2, 1100, zones(), 1100).eligibleAlert)
    }

    @Test fun wrongSessionResultDoesNotDestroyCurrentOffer() {
        val p = started(); val alert = confirmed(p)
        assertEquals("wrong_session", p.evaluate(2, 3, 550, null, 550).reason)
        assertNotNull(p.onSubmitted(alert, 600))
    }

    @Test fun unknownAudioRequiresCorrelatedResolutionOrExplicitFlush() {
        val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
        assertTrue(p.onAudioUnknown(t))
        assertFalse(p.canPlay(t, 550))
        assertEquals("audio_unknown", p.evaluate(1, 3, 750, zones(), 750).reason)
        p.onAudioReset()
        assertFalse(p.onCompleted(t, 800))
        assertNotNull(p.evaluate(1, 4, 1000, zones(), 1000).eligibleAlert)
    }

    @Test fun staleSubmissionAndFirstPcmAreRefusedAtBoundary() {
        val p = started(); val alert = confirmed(p)
        assertNull(p.onSubmitted(alert, 2001))
        val t = p.onSubmitted(alert, 2000)!!
        assertTrue(p.canPlay(t, 2000))
        assertFalse(p.canPlay(t, 2001))
        assertFalse(p.canPlay(t, 1999))
    }

    @Test fun newerMissingLowQualityZeroOrDifferentSidePreventsFirstPcm() {
        for (kind in listOf("missing", "quality", "zero", "side", "stale")) {
            val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
            when (kind) {
                "missing" -> p.evaluate(1, 3, 750, null, 750)
                "quality" -> p.evaluate(1, 3, 750, zones(), 750, false, "low_texture")
                "zero" -> p.evaluate(1, 3, 750, zones(center = 0f), 750)
                "side" -> p.evaluate(1, 3, 750, zones(1f, 0f, 0f), 750)
                "stale" -> p.evaluate(1, 3, 750, zones(), 2251)
            }
            assertFalse(kind, p.canPlay(t, if (kind == "stale") 2251 else 750))
        }
    }

    @Test fun missingInvalidAndZeroRemainDistinctAndAllBreakConfirmation() {
        for (values in listOf(null, emptyList(), zones(center = Float.NaN), zones(center = 0f))) {
            val p = started()
            p.evaluate(1, 0, 0, zones(), 0); p.evaluate(1, 1, 250, zones(), 250)
            val r = p.evaluate(1, 2, 500, values, 500)
            assertEquals(when { values == null -> "missing"; values.isEmpty() || values.any { !it.candidateFraction.isFinite() } -> "invalid"; else -> "no_candidate" }, r.status)
            assertNull(p.evaluate(1, 3, 750, zones(), 750).eligibleAlert)
        }
    }

    @Test fun invalidQualityHeaderFailsClosed() {
        for ((usable, reason) in listOf(true to "low_light", false to null, false to "nonsense")) {
            val p = started(); confirmed(p)
            assertEquals("invalid_image_quality", p.evaluate(1, 3, 750, zones(), 750, usable, reason).reason)
        }
    }

    @Test fun invalidatedPendingPlaybackCannotResurrectAfterFreshConfirmation() {
        val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
        p.evaluate(1, 3, 750, null, 750)
        assertFalse(p.canPlay(t, 750))
        for (i in 4L..6L) p.evaluate(1, i, i * 250, zones(), i * 250)
        assertFalse("New evidence cannot revive an earlier invalidated PCM request", p.canPlay(t, 1500))
        assertTrue("The exact ticket can still report its cancellation", p.onFailed(t, 1500))
    }

    @Test fun missingFrameGapAndClockReversalCannotReuseConfirmation() {
        val p = started(); confirmed(p)
        assertNull(p.evaluate(1, 4, 750, zones(), 750).eligibleAlert)
        assertEquals("frame_order", p.evaluate(1, 4, 800, zones(), 800).reason)
        assertEquals("observation_clock_order", p.evaluate(1, 5, 750, zones(), 800).reason)
        assertEquals("clock_reversed", p.evaluate(1, 5, 900, zones(), 799).reason)
        assertNull(p.evaluate(1, 5, 1000, zones(), 1000).eligibleAlert)
    }

    @Test fun observationGapBreaksEvenWhenIndexConsecutive() {
        val p = started(); confirmed(p)
        val r = p.evaluate(1, 3, 2001, zones(), 2001)
        assertEquals("observation_gap", r.resetReason)
        assertNull(r.eligibleAlert)
    }

    @Test fun futureAndPreSessionObservationsAreRefused() {
        val p = started()
        assertEquals("future_observation", p.evaluate(1, 0, 10, zones(), 0).reason)
        p.start(1, 100)
        assertEquals("stale_observation", p.evaluate(1, 0, 99, zones(), 100).reason)
    }

    @Test fun callbackClockCannotGoBackwards() {
        val p = started(); val t = p.onSubmitted(confirmed(p), 500)!!
        p.evaluate(1, 3, 750, zones(), 750)
        assertFalse(p.onCompleted(t, 749))
        assertFalse(p.onFailed(t, 749))
        assertTrue(p.onCompleted(t, 750))
    }

    @Test fun zeroFractionNeverQualifiesEvenAtZeroThresholdAndConfigValidates() {
        val p = started(DepthObstacleConfig(minCandidateFraction = 0f))
        for (i in 0L..3L) assertEquals("no_candidate", p.evaluate(1, i, i * 250, zones(center = 0f), i * 250).status)
        assertThrows(IllegalArgumentException::class.java) { DepthObstacleConfig(minCandidateFraction = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { DepthObstacleConfig(maxObservationAgeMs = 0) }
    }
}
