package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class RgbAlertEngineTest {
    private fun person(left: Float = .4f, confidence: Float = .9f) =
        Detection(0, confidence, Box(left, .3f, left + .15f, .85f))

    private fun frame(id: Long, at: Long, vararg detections: Detection, session: Long = 1) =
        DetectionFrame(session, id, at, detections.toList())

    private fun engine(config: RgbAlertConfig = RgbAlertConfig()) = RgbAlertEngine(config).also { it.start(1, 0) }

    private fun confirmedAlert(engine: RgbAlertEngine, detections: List<Detection> = listOf(person())): VoiceAlert {
        assertNull(engine.evaluate(DetectionFrame(1, 1, 0, detections), 0).eligibleAlert)
        return requireNotNull(engine.evaluate(DetectionFrame(1, 2, 250, detections), 250).eligibleAlert)
    }

    @Test fun exactZoneBoundariesAndVocabularyMatchSwift() {
        assertEquals(RgbZone.LEFT, RgbZone.fromCenterX(.38999f))
        assertEquals(RgbZone.CENTER, RgbZone.fromCenterX(.39f))
        assertEquals(RgbZone.CENTER, RgbZone.fromCenterX(.61f))
        assertEquals(RgbZone.RIGHT, RgbZone.fromCenterX(.61001f))
        assertEquals(listOf("avant-gauche", "devant", "avant-droite"), RgbZone.entries.map { it.voiceSuffix })
    }

    @Test fun sixClassesDoNotInventTrafficMeaningOrSeventhObstacleClass() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val events = listOf(4, 5, 6).map { Detection(it, 1f, Box(.1f, .1f, .9f, .95f)) }
        val result = e.evaluate(DetectionFrame(1, 1, 0, events), 0)
        assertNull(result.eligibleAlert)
        assertNull(result.selected)
        assertEquals(1, result.rejectedDetectionCount)
    }

    @Test fun qualificationKeepsSwiftConfidenceAreaAndBottomGates() {
        assertFalse(RgbAlertPolicy.qualifies(person(confidence = .839f)))
        assertTrue(RgbAlertPolicy.qualifies(person(confidence = .84f)))
        assertFalse(RgbAlertPolicy.qualifies(Detection(1, .95f, Box(.4f, .1f, .6f, .49f))))
        assertFalse(RgbAlertPolicy.qualifies(Detection(3, .99f, Box(.4f, .5f, .405f, .9f))))
        assertTrue(RgbAlertPolicy.qualifies(Detection(3, .86f, Box(.4f, .4f, .45f, .8f))))
    }

    @Test fun invalidGeometryAndNonFiniteValuesNeverProduceAnnouncements() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val detections = listOf(person(confidence = Float.NaN), person().copy(box = Box(-.1f, 0f, .5f, .9f)),
            person().copy(box = Box(.5f, .3f, .4f, .9f)), person().copy(box = Box(.1f, Float.POSITIVE_INFINITY, .4f, .9f)))
        val r = e.evaluate(DetectionFrame(1, 1, 0, detections), 0)
        assertNull(r.eligibleAlert)
        assertEquals(4, r.rejectedDetectionCount)
    }

    @Test fun moreThanEightCandidatesDoNotDiscardNinthCentralAlert() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val context = (0 until 9).map { Detection(5, .999f, Box(.01f * it, .1f, .05f + .01f * it, .2f)) }
        val r = e.evaluate(DetectionFrame(1, 1, 0, context + person()), 0)
        assertEquals(10, r.tracks.size)
        assertEquals("Piéton devant", r.eligibleAlert?.text)
    }

    @Test fun overlappingClassesRemainDistinctWithoutAgnosticNms() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val r = e.evaluate(frame(1, 0, person(), person().copy(classId = 1)), 0)
        assertEquals(2, r.tracks.size)
        assertEquals(setOf(0, 1), r.tracks.map { it.detection.classId }.toSet())
    }

    @Test fun acceptedDecisionTracksAndCandidatesCarrySessionGenerationAndObservationTime() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val result = e.evaluate(frame(4, 250, person()), 250)
        val track = result.tracks.single()
        val candidate = requireNotNull(result.selected)
        assertEquals(1L, result.sessionId)
        assertEquals(result.generation, track.generation)
        assertEquals(result.generation, candidate.generation)
        assertEquals(1L, track.sessionId)
        assertEquals(1L, candidate.sessionId)
        assertEquals(4L, candidate.frameId)
        assertEquals(250L, track.observedAtMs)
        assertEquals(250L, candidate.observedAtMs)
    }

    @Test fun twoPeopleUseOneToOneAssociationAndKeepIdsWhenInputOrderChanges() {
        val e = engine()
        val first = e.evaluate(frame(1, 0, person(.1f), person(.72f)), 0)
        val next = e.evaluate(frame(2, 250, person(.73f), person(.11f)), 250)
        assertEquals(2, next.tracks.size)
        assertEquals(2, next.tracks.map { it.id }.toSet().size)
        assertEquals(first.tracks.first { it.zone == RgbZone.LEFT }.id,
            next.tracks.first { it.zone == RgbZone.LEFT }.id)
        assertEquals(first.tracks.first { it.zone == RgbZone.RIGHT }.id,
            next.tracks.first { it.zone == RgbZone.RIGHT }.id)
    }

    @Test fun announcingOnePersonDoesNotConsumeTheOthersCooldown() {
        val e = engine()
        val objects = listOf(person(.1f), person(.72f))
        val first = confirmedAlert(e, objects)
        val ticket = requireNotNull(e.onSubmitted(first, 250))
        assertTrue(e.onConfirmed(ticket, 251))
        var result: RgbEvaluation? = null
        for (step in 2..6) result = e.evaluate(DetectionFrame(1, step + 1L, step * 250L, objects), step * 250L)
        val next = requireNotNull(result?.eligibleAlert)
        assertNotEquals(first.trackId, next.trackId)
        assertNotEquals(first.zone, next.zone)
        assertEquals(result?.tracks?.first { it.id == next.trackId }?.zone, next.zone)
        assertEquals("Piéton ${next.zone.voiceSuffix}", next.text)
        assertEquals(2, result?.tracks?.size)
    }

    @Test fun entranceNeedsConfirmationAndSmallScoreOscillationsUseExplicitExitMargin() {
        val e = engine()
        assertNull(e.evaluate(frame(1, 0, person(confidence = .85f)), 0).eligibleAlert)
        assertNull(e.evaluate(frame(2, 250, person(confidence = .83f)), 250).eligibleAlert)
        assertNull(e.evaluate(frame(3, 500, person(confidence = .86f)), 500).eligibleAlert)
        assertNotNull(e.evaluate(frame(4, 750, person(confidence = .86f)), 750).eligibleAlert)
        assertNotNull(e.evaluate(frame(5, 1_000, person(confidence = .82f)), 1_000).selected)
        assertNull(e.evaluate(frame(6, 1_250, person(confidence = .79f)), 1_250).selected)
    }

    @Test fun lostObservationMustConfirmAgainAndCannotUseOldDirection() {
        val e = engine()
        confirmedAlert(e)
        assertNull(e.evaluate(frame(3, 500), 500).selected)
        assertNull(e.evaluate(frame(4, 750, person()), 750).eligibleAlert)
        assertNotNull(e.evaluate(frame(5, 1_000, person()), 1_000).eligibleAlert)
    }

    @Test fun voiceMemoryDoesNotRefreshAnObservationAfterFiveHundredMilliseconds() {
        val e = engine()
        val alert = confirmedAlert(e)
        val ticket = requireNotNull(e.onSubmitted(alert, 250))
        assertTrue(e.onConfirmed(ticket, 251))
        assertNull(e.current(751).selected)
        assertEquals(250L, e.current(751).tracks.single().observedAtMs)
        assertNull(e.current(6_751).eligibleAlert)
        assertNull(e.onSubmitted(alert, 6_751))
        assertTrue(e.current(18_250).tracks.isEmpty())
    }

    @Test fun ageBoundaryIsInclusiveButOldDispatchAndFutureFramesAreRejected() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val alert = requireNotNull(e.evaluate(frame(1, 0, person()), 500).eligibleAlert)
        assertNull(e.onSubmitted(alert, 501))
        assertEquals(RgbFrameStatus.FUTURE, e.evaluate(frame(2, 800, person()), 700).frameStatus)
        assertEquals(RgbFrameStatus.STALE, e.evaluate(frame(2, 0, person()), 800).frameStatus)
        assertTrue(RgbAlertPolicy.isFresh(0, 500, 500))
        assertFalse(RgbAlertPolicy.isFresh(0, 501, 500))
        assertFalse(RgbAlertPolicy.hasExpired(0, 18_000, 18_000))
        assertTrue(RgbAlertPolicy.hasExpired(0, 18_001, 18_000))
    }

    @Test fun submissionCanBeReservedOnceAndOnlyForLatestOfferedFrame() {
        val e = engine()
        val old = confirmedAlert(e)
        val fresh = requireNotNull(e.evaluate(frame(3, 500, person()), 500).eligibleAlert)
        assertNull(e.onSubmitted(old, 500))
        val ticket = requireNotNull(e.onSubmitted(fresh, 500))
        assertNull(e.onSubmitted(fresh, 500))
        assertEquals(RgbAudioState.IN_FLIGHT, e.audioState)
        assertTrue(e.onConfirmed(ticket, 501))
        assertFalse(e.onConfirmed(ticket, 502))
    }

    @Test fun failureDoesNotConsumeSixAndAHalfSecondConfirmedCooldown() {
        val e = engine()
        val alert = confirmedAlert(e)
        val ticket = requireNotNull(e.onSubmitted(alert, 250))
        assertTrue(e.onFailure(ticket, 251))
        assertFalse(e.onConfirmed(ticket, 252))
        assertEquals(RgbSuppressionReason.RETRY_BACKOFF, e.evaluate(frame(3, 500, person()), 500).suppressionReason)
        e.evaluate(frame(4, 750, person()), 750)
        e.evaluate(frame(5, 1_000, person()), 1_000)
        val retry = e.evaluate(frame(6, 1_251, person()), 1_251).eligibleAlert
        assertNotNull(retry)
        assertEquals(alert.trackId, retry?.trackId)
    }

    @Test fun actualConfirmationSuppressesRepeatedEntityUntilItsDeadline() {
        val e = engine()
        val ticket = requireNotNull(e.onSubmitted(confirmedAlert(e), 250))
        assertTrue(e.onConfirmed(ticket, 250))
        for (step in 2..26) {
            val now = step * 250L
            assertNull("No repeated announcement at $now", e.evaluate(frame(step + 1L, now, person()), now).eligibleAlert)
        }
        assertNotNull(e.evaluate(frame(28, 6_750, person()), 6_750).eligibleAlert)
    }

    @Test fun stopAndRestartRejectOldInferenceAndLateCallbackEvenIfSessionIdIsReused() {
        val e = engine()
        val alert = confirmedAlert(e)
        val ticket = requireNotNull(e.onSubmitted(alert, 250))
        e.stop()
        assertEquals(RgbAudioState.UNKNOWN, e.audioState)
        assertNull(e.onSubmitted(alert, 251))
        assertFalse(e.onConfirmed(ticket, 252))
        e.start(1, 500)
        assertFalse(e.onConfirmed(ticket, 501))
        assertEquals(RgbFrameStatus.STALE, e.evaluate(frame(3, 250, person()), 501).frameStatus)
        assertNull(e.evaluate(frame(1, 501, person()), 501).eligibleAlert)
        assertEquals(RgbSuppressionReason.AUDIO_UNKNOWN, e.evaluate(frame(2, 750, person()), 750).suppressionReason)
        assertEquals(RgbFrameStatus.WRONG_SESSION, e.evaluate(frame(3, 800, person(), session = 2), 800).frameStatus)
    }

    @Test fun ambiguousTimeoutBlocksNewSubmissionAndDoesNotBecomeSuccessForAnotherTicket() {
        val e = engine()
        val ticket = requireNotNull(e.onSubmitted(confirmedAlert(e), 250))
        assertTrue(e.onAmbiguous(ticket, 500))
        assertFalse(e.onConfirmed(ticket, 501))
        val r = e.evaluate(frame(3, 750, person()), 750)
        assertNull(r.eligibleAlert)
        assertEquals(RgbAudioState.UNKNOWN, r.audioState)
    }

    @Test fun stopWithoutSubmissionIsIdempotentAndDoesNotCreateAudioUncertainty() {
        val e = engine()
        val old = confirmedAlert(e)
        e.stop()
        e.stop()
        assertEquals(RgbAudioState.AVAILABLE, e.audioState)
        assertNull(e.onSubmitted(old, 500))
        assertEquals(RgbFrameStatus.STOPPED, e.evaluate(frame(3, 500, person()), 500).frameStatus)
        e.start(2, 750)
        assertEquals(RgbFrameStatus.WRONG_SESSION, e.evaluate(frame(4, 750, person()), 750).frameStatus)
        assertTrue(e.evaluate(frame(1, 750, person(), session = 2), 750).tracks.single().confirmationSamples == 1)
    }

    @Test fun duplicateFramesAndReverseTimeDoNotIncreaseConfirmation() {
        val e = engine()
        e.evaluate(frame(1, 100, person()), 100)
        assertEquals(RgbFrameStatus.OUT_OF_ORDER, e.evaluate(frame(1, 100, person()), 100).frameStatus)
        assertEquals(RgbFrameStatus.CLOCK_REVERSED, e.evaluate(frame(2, 50, person()), 50).frameStatus)
        assertEquals(1, e.current(100).tracks.single().confirmationSamples)
    }

    @Test fun associationAndVoiceRegistriesAreBoundedUnderManyObjects() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1, maximumTracks = 16))
        val crowd = (0 until 40).map { person((it % 8) * .10f, .9f) }
        val r = e.evaluate(DetectionFrame(1, 1, 0, crowd), 0)
        assertEquals(16, r.tracks.size)
        assertEquals(24, r.rejectedDetectionCount)
    }

    @Test fun crossingRetainsTwoLocalTracksWithoutClaimingPhysicalReidentification() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val positions = listOf(.15f to .70f, .25f to .60f, .35f to .50f, .44f to .41f, .54f to .31f)
        for ((index, pair) in positions.withIndex()) {
            val r = e.evaluate(frame(index + 1L, index * 250L, person(pair.first), person(pair.second)), index * 250L)
            val visible = r.tracks.filter { it.visibleInLatestFrame }
            assertEquals(2, visible.size)
            assertEquals(2, visible.map { it.id }.toSet().size)
        }
    }

    @Test fun headTurnAfterLongGapCreatesNewLocalIdentityInsteadOfFakeWorldAnchor() {
        val e = engine(RgbAlertConfig(confirmationSamples = 1))
        val first = e.evaluate(frame(1, 0, person(.1f)), 0).tracks.single().id
        e.evaluate(frame(2, 250), 250)
        e.evaluate(frame(3, 500), 500)
        e.evaluate(frame(4, 750), 750)
        val returned = e.evaluate(frame(5, 1_000, person(.1f)), 1_000)
        assertNotEquals(first, returned.tracks.single().id)
        assertEquals(1_000L, returned.selected?.observedAtMs)
    }
}
