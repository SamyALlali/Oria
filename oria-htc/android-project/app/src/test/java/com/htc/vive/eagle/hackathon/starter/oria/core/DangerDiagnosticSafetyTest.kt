package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class DangerDiagnosticSafetyTest {
    private fun candidate(id: String = "rgb", at: Long = 1_000, confidence: Float = 1f) = DangerCandidate(
        id, DangerSource.SEMANTIC_VISUAL_PROXY, DangerLevel.MEDIUM, 2f, RgbZone.CENTER, at,
        DangerCandidateProvenance.RGB_CONFIRMED_CURRENT, id, RgbCategory.PERSON, confidence = confidence)
    private fun depth(at: Long) = DangerCandidate("depth", DangerSource.GENERIC_FALLBACK,
        DangerLevel.MEDIUM, .9f, RgbZone.RIGHT, at, DangerCandidateProvenance.MONOCULAR_OBSTACLE_RELATIVE,
        "depth-right", relativeProximity = .9f)
    private fun input(now: Long, vararg candidates: DangerCandidate, generation: Long = 9) =
        DangerResolutionInput(generation, now, candidates.maxOfOrNull { it.observedAtMs } ?: now, now,
            candidates.toList(), DangerResolutionContext(null, candidates.any { it.source.semantic }, false))
    private fun engine() = DangerResolutionEngine().apply { reset(9) }

    @Test fun onlyExplicitResetCanStartOrChangeTheSession() {
        val engine = DangerResolutionEngine()
        assertEquals(DangerInputStatus.STOPPED, engine.resolve(input(1_000, candidate())).inputStatus)
        engine.reset(9)
        assertNotNull(engine.resolve(input(1_000, candidate())).freshSelected)
        for (id in listOf(8L, 10L)) {
            val result = engine.resolve(input(1_001, candidate("foreign", 1_001), generation = id))
            assertEquals(DangerInputStatus.FOREIGN_SESSION, result.inputStatus)
            assertFalse(result.stateChanged)
            assertEquals(9L, result.generation)
            assertEquals("rgb", result.selected?.ownerKey)
            assertNull(result.freshSelected)
        }
        engine.stop()
        assertEquals(DangerInputStatus.STOPPED, engine.resolve(input(1_002, candidate())).inputStatus)
    }

    @Test fun reversedClockAndFutureInputDoNotMutateTheCurrentOwner() {
        val engine = engine()
        engine.resolve(input(1_000, candidate()))
        assertEquals(DangerInputStatus.CLOCK_REVERSED, engine.resolve(input(999, candidate(at = 999))).inputStatus)
        assertEquals(DangerInputStatus.FUTURE, engine.resolve(input(1_001, candidate(at = 1_002))).inputStatus)
        val resumed = engine.resolve(input(1_001, candidate(at = 1_001)))
        assertEquals(DangerInputStatus.ACCEPTED, resumed.inputStatus)
        assertEquals("rgb", resumed.freshSelected?.id)
    }

    @Test fun asynchronousDepthDoesNotShareTheRgbObservationClock() {
        val engine = engine()
        engine.resolve(input(1_050, candidate(at = 1_050)))
        val slowerDepth = engine.resolve(input(1_100, depth(900)))
        assertTrue(slowerDepth.accepted.any { it.id == "depth" })
        val oldRgb = engine.resolve(input(1_101, candidate("old-rgb", 1_040)))
        assertTrue(oldRgb.rejected.any { it.candidate.id == "old-rgb" && it.reason == DangerRejectionReason.OUT_OF_ORDER })
    }

    @Test fun sourceFreshnessKeepsSeparateFiveHundredAndFifteenHundredMillisecondBounds() {
        val result = engine().resolve(input(1_501, candidate(at = 1_000), depth(1_000)))
        assertTrue(result.rejected.any { it.candidate.id == "rgb" && it.reason == DangerRejectionReason.STALE })
        assertTrue(result.accepted.any { it.id == "depth" })
        assertFalse(engine().resolve(input(2_501, depth(1_000))).accepted.any { it.id == "depth" })
    }

    @Test fun visualMemoryIsNeverCurrentEvidenceEvenWhenItWinsTheDiagnosticPolicy() {
        val engine = engine()
        engine.resolve(input(1_000, candidate()))
        val remembered = engine.resolve(input(1_600))
        assertEquals(DangerSource.SEMANTIC_VISUAL_MEMORY, remembered.selected?.source)
        assertNull(remembered.freshSelected)
        assertNull(engine.resolve(input(3_851)).selected)
    }

    @Test fun heldOwnerMustAppearInCurrentEvidenceToBeFreshSelected() {
        val engine = engine()
        engine.resolve(input(1_000, candidate()))
        val held = engine.resolve(input(1_100, candidate("competitor", 1_100).copy(score = 2.1f)))
        assertEquals("rgb", held.selected?.ownerKey)
        assertNull(held.freshSelected)
    }

    @Test fun rejectedObservationCannotBeLaunderedIntoMemory() {
        val engine = engine()
        engine.resolve(input(1_001, candidate("stale", 0), candidate("weak", 1_001, .1f)))
        val empty = engine.resolve(input(1_002))
        assertTrue(empty.inputs.isEmpty())
        assertNull(empty.selected)
    }

    @Test fun churnDoesNotCreateAnUnboundedDiagnosticMemory() {
        val engine = DangerResolutionEngine(DangerResolutionConfig(maximumVisualMemories = 8)).apply { reset(9) }
        repeat(100) { engine.resolve(input(1_000, candidate("track-$it"))) }
        val remembered = engine.resolve(input(1_100))
        assertEquals(8, remembered.inputs.size)
        assertNull(remembered.freshSelected)
    }

    @Test fun resetDropsBothSourceClocksAndMemory() {
        val engine = engine()
        engine.resolve(input(1_000, candidate(), depth(1_000)))
        engine.reset(10)
        val next = engine.resolve(input(1_100, depth(900), generation = 10))
        assertEquals(listOf("depth"), next.inputs.map { it.id })
        assertNotNull(next.freshSelected)
    }

    @Test fun eagleAdapterUsesVideoSessionRatherThanInternalRgbEpoch() {
        val rgb = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1)).apply { start(73, 1_000) }
        val evaluation = rgb.evaluate(DetectionFrame(73, 1, 1_000,
            listOf(Detection(0, .99f, Box(.3f, .2f, .7f, .95f)))), 1_000)
        assertNotEquals(evaluation.sessionId, evaluation.generation)
        val input = OriaDangerAdapter.input(evaluation, emptyMap(), null, 1_000, false)
        assertEquals(73L, input.generation)
        assertTrue(input.candidates.all { it.metricDistanceMeters == null })
    }
}
