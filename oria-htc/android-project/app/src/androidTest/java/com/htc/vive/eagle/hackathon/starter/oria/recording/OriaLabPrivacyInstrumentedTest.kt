package com.htc.vive.eagle.hackathon.starter.oria.recording

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Android org.json adapter tests; no camera, radio, or acoustic evidence. */
class OriaLabPrivacyInstrumentedTest {
    @Test fun bothBundledModelManifestsKeepEveryTechnicalValueAndTensorName() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val yolo = assets.open("oria/model_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val depth = assets.open("oria/depth/model_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val metadata = JSONObject().put("videoSessionId", 7).put("modelManifest", yolo).put("depthModelManifest", depth)
        val clean = OriaLabPrivacyJson.sanitizeMetadata(metadata)
        assertEquals(yolo.toString(), clean.getJSONObject("modelManifest").toString())
        assertEquals(depth.toString(), clean.getJSONObject("depthModelManifest").toString())
        assertEquals("images", clean.getJSONObject("modelManifest").getJSONObject("input").getString("name"))
        assertEquals("pixel_values", clean.getJSONObject("depthModelManifest").getJSONObject("input").getString("name"))
        assertEquals("Apache-2.0", clean.getJSONObject("depthModelManifest").getJSONObject("license").getString("name"))
    }

    @Test fun nestedArraysNullsAndDiagnosticNumbersRoundTripWithoutMutation() {
        val source = JSONObject().put("type", "decision").put("frameId", 3)
            .put("relativeDepth", JSONObject().put("metric", false).put("reason", JSONObject.NULL)
                .put("values", JSONArray().put(JSONArray().put(.2).put(.8))))
            .put("items", JSONArray().put(JSONObject().put("type", "navigation_error")
                .put("routeVersion", 4).put("status", "12 rue privée")))
        val clean = OriaLabPrivacyJson.sanitizeEvent(source)
        assertFalse(clean.toString().contains("12 rue privée"))
        assertTrue(clean.getJSONObject("relativeDepth").isNull("reason"))
        assertEquals(.8, clean.getJSONObject("relativeDepth").getJSONArray("values").getJSONArray(0).getDouble(1), 0.0)
        assertEquals(4, clean.getJSONArray("items").getJSONObject(0).getInt("routeVersion"))
        assertEquals("12 rue privée", source.getJSONArray("items").getJSONObject(0).getString("status"))
    }

    @Test fun recursiveJsonIsRejectedWithBoundedNonSensitiveError() {
        val cyclic = JSONObject(); cyclic.put("nested", cyclic)
        try { OriaLabPrivacyJson.sanitizeEvent(cyclic); fail("Expected bounded rejection") }
        catch (e: IllegalArgumentException) { assertEquals("privacy_structure_limit", e.message) }
    }
}
