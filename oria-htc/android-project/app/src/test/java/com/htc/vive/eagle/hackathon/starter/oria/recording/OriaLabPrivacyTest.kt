package com.htc.vive.eagle.hackathon.starter.oria.recording

import com.htc.vive.eagle.hackathon.starter.oria.core.RgbCategory
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbZone
import org.junit.Assert.*
import org.junit.Test

class OriaLabPrivacyTest {
    private val secret = "12 rue privée, domicile"
    private val id = "12345678-1234-1234-1234-123456789abc"

    @Test fun sensitiveEventsUseClosedFieldsWithoutStatusErrorOrAliasLeaks() {
        for (type in listOf("navigation_begin", "navigation_error", "voice_command_failed", "speech_local_result", "audio_scheduler")) {
            val source = mapOf("type" to type, "sessionId" to 7L, "atMs" to 120L,
                "requestId" to id, "routeVersion" to 4, "accepted" to true, "mode" to "REAL",
                "query" to secret, "status" to secret, "error" to secret, "reason" to secret, "detail" to secret,
                "message" to secret, "destination" to secret, "LAT_ITUDE" to 48.7, "long-itude" to 2.1,
                "payload" to listOf(mapOf("harmless" to secret)), "count" to secret, "text" to secret)
            val clean = OriaLabPrivacy.sanitizeEvent(source)
            assertFalse(type, clean.toString().contains(secret))
            assertFalse(clean.containsKey("LAT_ITUDE")); assertFalse(clean.containsKey("long-itude"))
            assertEquals(type, clean["type"]); assertEquals(7L, clean["sessionId"])
            assertEquals(id, clean["requestId"]); assertEquals(4, clean["routeVersion"])
            assertEquals(true, clean["accepted"]); assertEquals("REAL", clean["mode"])
            assertEquals(true, clean["privateDataOmitted"])
            assertEquals(secret, source["query"])
            assertEquals(clean, OriaLabPrivacy.sanitizeEvent(clean))
        }
    }

    @Test fun speechKeepsOnlyExactClosedDangerPhrasesAndValidatedIdentifiers() {
        val phrases = RgbCategory.entries.filter { it.alertable }.flatMap { c -> RgbZone.entries.map { "${c.label} ${it.voiceSuffix}" } } +
            RgbZone.entries.map { "Obstacle possible ${it.voiceSuffix}" }
        for (phrase in phrases) {
            val clean = OriaLabPrivacy.sanitizeEvent(mapOf("type" to "speech_submitted", "text" to phrase,
                "requestId" to id, "pan" to "LEFT", "backend" to "BLUETOOTH"))
            assertEquals(phrase, clean["text"]); assertEquals(id, clean["requestId"])
        }
        for (phrase in listOf(secret, "Piéton devant $secret", "piéton devant", "Piéton devant\n")) {
            val clean = OriaLabPrivacy.sanitizeEvent(mapOf("type" to "speech_submitted", "text" to phrase,
                "requestId" to secret, "pan" to secret, "backend" to secret))
            assertFalse(clean.containsKey("text")); assertFalse(clean.containsKey("requestId"))
            assertFalse(clean.containsKey("pan")); assertFalse(clean.containsKey("backend"))
        }
    }

    @Test fun anUnknownSensitiveTypeCannotEncodeTheDestinationInItsName() {
        val clean = OriaLabPrivacy.sanitizeEvent(mapOf("type" to "navigation_$secret", "frameId" to 3, "status" to secret))
        assertEquals("sensitive_event", clean["type"])
        assertFalse(clean.toString().contains(secret))
        assertEquals(3, clean["frameId"])
    }

    @Test fun nestedContextsAndExplicitLocationFieldsAreFilteredRecursively() {
        val source = mapOf("type" to "decision", "frameId" to 4,
            "navigation" to mapOf("routeVersion" to 8, "status" to secret, "error" to secret, "latitude" to 48.1),
            "items" to listOf(mapOf("type" to "voice_command_parsed", "atMs" to 14L, "name" to secret)),
            "nested" to mapOf("coordinates" to listOf(48.1, 2.1), "transcript" to secret, "score" to .8f))
        val clean = OriaLabPrivacy.sanitizeEvent(source)
        assertFalse(clean.toString().contains(secret)); assertFalse(clean.toString().contains("48.1"))
        assertEquals(8, (clean["navigation"] as Map<*, *>)["routeVersion"])
        assertEquals(.8f, (clean["nested"] as Map<*, *>)["score"])
        assertEquals(secret, (source["navigation"] as Map<*, *>)["status"])
        assertFalse(OriaLabPrivacy.sanitizeMetadata(mapOf("navigation" to secret)).toString().contains(secret))
        assertFalse(OriaLabPrivacy.sanitizeEvent(mapOf("interaction" to listOf(secret))).toString().contains(secret))
    }

    @Test fun structuredNavigationRequestIdsRemainCorrelatableWithoutFreeTextIds() {
        val clean = OriaLabPrivacy.sanitizeEvent(mapOf("type" to "speech_local_result", "requestId" to "nav-1-2-3-4",
            "result" to "INTERRUPTED", "detail" to secret))
        assertEquals("nav-1-2-3-4", clean["requestId"])
        assertEquals("INTERRUPTED", clean["result"])
        assertFalse(clean.containsKey("detail"))
        assertFalse(OriaLabPrivacy.sanitizeEvent(mapOf("type" to "speech_submitted",
            "requestId" to "nav-1-2-3-$secret")).containsKey("requestId"))
    }

    @Test fun inferenceAndDecisionEvidenceRemainIdenticalAndDetached() {
        val box = linkedMapOf<String, Any?>("left" to .1, "top" to .2, "right" to .8, "bottom" to .9)
        val source = mapOf("type" to "decision", "videoSessionId" to 8L, "frameId" to 19L,
            "evaluatedAtMs" to 1200L, "reason" to "NONE", "selected" to mapOf("zone" to "CENTER", "text" to "Piéton devant"),
            "detections" to listOf(mapOf("classId" to 0, "confidence" to .95, "box" to box)),
            "relativeDepth" to mapOf("metric" to false, "values" to listOf(listOf(.2, .8))),
            "rawModelOutput" to listOf(.1f, .2f, .7f))
        val clean = OriaLabPrivacy.sanitizeEvent(source)
        assertEquals(source, clean)
        box["left"] = .9
        val cleanBox = ((clean["detections"] as List<*>)[0] as Map<*, *>)["box"] as Map<*, *>
        assertEquals(.1, cleanBox["left"])
    }

    @Test fun metadataAdmissionPreservesTechnicalContractsButRejectsUnreviewedFreeData() {
        val source = mapOf("videoSessionId" to 12L, "source" to "instrumented_synthetic", "depthMetric" to false,
            "policyConfig" to mapOf("repeatIntervalMs" to 8000, "categories" to listOf(mapOf("classId" to 0, "label" to "Piéton"))),
            "modelManifest" to mapOf("sha256" to "abcd", "shape" to listOf(1, 3, 252, 252),
                "input" to mapOf("name" to "pixel_values"), "license" to mapOf("name" to "Apache-2.0")),
            "navigation" to mapOf("routeVersion" to 4, "instruction" to secret, "status" to secret),
            "destination" to secret, "operatorNote" to secret, "appProvenanceError" to secret)
        val clean = OriaLabPrivacy.sanitizeMetadata(source)
        assertFalse(clean.toString().contains(secret)); assertEquals(12L, clean["videoSessionId"])
        assertEquals(source["policyConfig"], clean["policyConfig"])
        assertEquals(source["modelManifest"], clean["modelManifest"])
        assertEquals(clean, OriaLabPrivacy.sanitizeMetadata(clean))
    }

    @Test fun actualTensorShapesAndCompactDepthFitBudgetWithoutChangingAnyValue() {
        val source = mapOf("type" to "depth_inference", "frameId" to 17L,
            "relativeDepth" to mapOf("available" to true, "metric" to false, "width" to 128, "height" to 128,
                "values" to List(128) { y -> List(128) { x -> ((y * 128 + x) / 16383f).toDouble() } }),
            "rawModelOutput" to List(1800) { (it / 1800f).toDouble() },
            "modelDetections" to List(300) { mapOf("row" to it, "classId" to 0, "confidence" to .91,
                "box" to mapOf("left" to .1, "top" to .2, "right" to .8, "bottom" to .9)) })
        assertEquals(source, OriaLabPrivacy.sanitizeEvent(source))
    }

    @Test fun malformedOrOverlyDeepStructuresFailWithoutReflectingTheirValues() {
        val cyclic = linkedMapOf<String, Any?>(); cyclic["loop"] = cyclic
        for (source in listOf(mapOf("unknown" to Any()), mapOf("score" to Double.NaN), cyclic)) {
            try { OriaLabPrivacy.sanitizeEvent(source); fail("Expected bounded rejection") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("privacy_")) }
        }
        val oversized = mapOf("values" to List(OriaLabPrivacy.MAX_NODES + 1) { 0 })
        try { OriaLabPrivacy.sanitizeEvent(oversized); fail("Expected node budget") }
        catch (e: IllegalArgumentException) { assertEquals("privacy_structure_limit", e.message) }
    }
}
