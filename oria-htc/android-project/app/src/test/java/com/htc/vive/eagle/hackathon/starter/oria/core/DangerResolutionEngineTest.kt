package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class DangerResolutionEngineTest {
    private fun candidate(
        id: String,
        source: DangerSource = DangerSource.SEMANTIC_VISUAL_PROXY,
        level: DangerLevel = DangerLevel.MEDIUM,
        score: Float = 1f,
        zone: RgbZone = RgbZone.CENTER,
        at: Long = 1_000,
        owner: String = id,
        metres: Float? = null,
        confidence: Float = 1f,
    ) = DangerCandidate(id, source, level, score, zone, at, DangerCandidateProvenance.FIXTURE,
        owner, RgbCategory.PERSON, metricDistanceMeters = metres, confidence = confidence)

    private fun obstruction(depth: Float = 1.5f, occupancy: Float = .15f,
                            zone: RgbZone = RgbZone.CENTER, wide: Boolean = false) =
        DangerObstructionEvidence(zone, 1_000, 1f, depth, occupancyRatio = occupancy, wide = wide)

    private fun input(candidates: List<DangerCandidate>, now: Long = 1_000, generation: Long = 1,
                      context: DangerResolutionContext = DangerResolutionContext(null, false, false),
                      approach: List<DangerApproachEvidence> = emptyList()) =
        DangerResolutionInput(generation, now, now, now, candidates, context, approach)

    @Test fun everySwiftSourceIsExplicit() {
        assertEquals(setOf("semantic_current", "semantic_visual_proxy", "semantic_visual_memory",
            "semantic_memory", "generic_fallback", "wide_fallback", "limited_sensors"),
            DangerSource.entries.map { it.wireName }.toSet())
    }

    @Test fun wallOwnershipRejectsSemanticFromAnotherZone() {
        val semantic = candidate("person", DangerSource.SEMANTIC_CURRENT, score = 2f,
            zone = RgbZone.LEFT, metres = 1.1f)
        val wall = candidate("wall", DangerSource.WIDE_FALLBACK, DangerLevel.HIGH, 1.8f,
            metres = 1f)
        val context = DangerResolutionContext(obstruction(.8f, .65f), true, true)
        assertFalse(DangerResolutionPolicy.shouldPreferSemantic(semantic, wall, context))
        assertTrue(DangerResolutionPolicy.shouldPreferFallback(wall, semantic, context))
        val snapshot = DangerResolutionEngine().resolve(input(listOf(semantic, wall), context = context))
        assertEquals("wall", snapshot.selected?.id)
        assertEquals(DangerRejectionReason.WALL_OWNERSHIP,
            snapshot.rejected.first { it.candidate.id == "person" }.reason)
    }

    @Test fun closeSameZoneMetricSemanticCanOwnWallSurface() {
        val semantic = candidate("person", DangerSource.SEMANTIC_CURRENT, DangerLevel.HIGH, 1.7f,
            metres = 1.1f)
        val wall = candidate("wall", DangerSource.WIDE_FALLBACK, DangerLevel.HIGH, 1.75f,
            metres = 1f)
        val context = DangerResolutionContext(obstruction(1.15f, .62f), true, true)
        assertFalse(DangerResolutionPolicy.shouldPreferFallback(wall, semantic, context))
    }

    @Test fun nonMetricRelativeEvidenceNeverActivatesMetricWallRule() {
        val relative = DangerObstructionEvidence(RgbZone.CENTER, 1_000, .9f,
            relativeProximity = .95f)
        assertFalse(DangerResolutionPolicy.obstructionLooksWallLikeStrong(relative))
        assertFalse(DangerResolutionPolicy.isAmbiguousFallback(
            candidate("fallback", DangerSource.GENERIC_FALLBACK), relative))
    }

    @Test fun holdThenReleaseAndHigherSeverityReplacementAreDeterministic() {
        val engine = DangerResolutionEngine()
        val first = candidate("a", score = 1.3f, owner = "track-a")
        assertEquals("a", engine.resolve(input(listOf(first))).selected?.id)
        val weaker = candidate("b", score = 1.4f, owner = "track-b", at = 1_100)
        val held = engine.resolve(input(listOf(weaker), now = 1_100))
        assertEquals("a", held.selected?.id)
        assertEquals(DangerResolutionReason.HELD, held.reason)
        val high = candidate("c", level = DangerLevel.HIGH, score = 1.2f, owner = "track-c", at = 1_200)
        val replaced = engine.resolve(input(listOf(high), now = 1_200))
        assertEquals("c", replaced.selected?.id)
        assertEquals(DangerResolutionReason.REPLACED_HIGHER_LEVEL, replaced.reason)
        val released = engine.resolve(input(emptyList(), now = 4_100))
        assertNull(released.selected)
        assertEquals(DangerResolutionReason.RELEASED, released.reason)
    }

    @Test fun zoneChangeDoesNotStealOwnerDuringHoldWithoutMargin() {
        val engine = DangerResolutionEngine()
        engine.resolve(input(listOf(candidate("center", score = 1.5f, owner = "track-1"))))
        val changed = candidate("left", score = 1.6f, zone = RgbZone.LEFT, at = 1_100, owner = "track-1")
        assertEquals(RgbZone.CENTER, engine.resolve(input(listOf(changed), now = 1_100)).selected?.zone)
    }

    @Test fun approachIsSeparateDisabledAndJournaled() {
        val base = candidate("a", score = 1f, owner = "track-a")
        val approach = listOf(DangerApproachEvidence("track-a", OriaDepthTrend.APPROACHING, 1f, 0))
        val off = DangerResolutionEngine().resolve(input(listOf(base), approach = approach))
        assertEquals(1f, off.accepted.single().score, 0f)
        assertFalse(off.approachEnabled)
        val enabledEngine = DangerResolutionEngine(DangerResolutionConfig(approachEnabled = true))
        val on = enabledEngine.resolve(input(listOf(base), approach = approach))
        assertEquals(1.12f, on.accepted.single().score, .0001f)
        assertTrue(on.approachEnabled)
        assertNull(on.approach.single().ttcMs)
    }

    @Test fun staleAndLowConfidenceInputsAreRejectedExplicitly() {
        val stale = candidate("stale", at = 0)
        val weak = candidate("weak", at = 1_000, confidence = .2f)
        val snapshot = DangerResolutionEngine().resolve(input(listOf(stale, weak), now = 1_001))
        assertNull(snapshot.selected)
        assertEquals(setOf(DangerRejectionReason.STALE, DangerRejectionReason.LOW_CONFIDENCE),
            snapshot.rejected.map { it.reason }.toSet())
    }

    @Test fun candidateInputOrderDoesNotChangeWinner() {
        val a = candidate("a", score = 1.2f)
        val b = candidate("b", score = 1.5f)
        val forward = DangerResolutionEngine().resolve(input(listOf(a, b))).selected?.id
        val reverse = DangerResolutionEngine().resolve(input(listOf(b, a))).selected?.id
        assertEquals("b", forward)
        assertEquals(forward, reverse)
    }

    @Test fun generationChangeDropsPreviousOwner() {
        val engine = DangerResolutionEngine()
        engine.resolve(input(listOf(candidate("old", score = 2f, owner = "old"))))
        val next = engine.resolve(input(listOf(candidate("new", score = .5f, at = 1_100, owner = "new")),
            now = 1_100, generation = 2))
        assertEquals("new", next.selected?.id)
        assertEquals(DangerResolutionReason.GENERATION_RESET, next.reason)
    }

    @Test fun visualMemoryIsOwnedBoundedAndReleasedOnGenerationChange() {
        val engine = DangerResolutionEngine()
        engine.resolve(input(listOf(candidate("live", owner = "track-4"))))
        val remembered = engine.resolve(input(emptyList(), now = 1_600))
        assertEquals(DangerSource.SEMANTIC_VISUAL_MEMORY, remembered.selectedBeforeStabilization?.source)
        assertEquals("track-4", remembered.ownerKey)
        val expired = engine.resolve(input(emptyList(), now = 3_851))
        assertTrue(expired.inputs.none { it.source == DangerSource.SEMANTIC_VISUAL_MEMORY })
        val reset = engine.resolve(input(emptyList(), now = 3_900, generation = 2))
        assertTrue(reset.inputs.isEmpty())
    }

    @Test fun eagleAdapterNamesRgbEvidenceAndNeverInventsMetres() {
        val rgb = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1))
        rgb.start(7, 1_000)
        val evaluation = rgb.evaluate(DetectionFrame(7, 1, 1_000,
            listOf(Detection(0, .95f, Box(.35f, .25f, .65f, .95f)))), 1_000)
        val evidence = OriaDistanceEvidence(.8f, .9f, .9f, 0, OriaDepthTrend.APPROACHING,
            OriaDepthRegion.DETECTION_LOWER_CENTER, 20)
        val frontal = evidence.copy(region = OriaDepthRegion.FRONTAL_CORRIDOR)
        val adapted = OriaDangerAdapter.input(evaluation, mapOf(1L to evidence), frontal, 1_000, true)
        val semantic = adapted.candidates.first { it.source == DangerSource.SEMANTIC_VISUAL_PROXY }
        assertEquals(DangerCandidateProvenance.RGB_WITH_RELATIVE_DEPTH, semantic.provenance)
        assertNull(semantic.metricDistanceMeters)
        assertEquals(.9f, semantic.relativeProximity ?: -1f, 0f)
        assertEquals(OriaDepthTrend.APPROACHING, adapted.approach.single().trend)
    }
}
