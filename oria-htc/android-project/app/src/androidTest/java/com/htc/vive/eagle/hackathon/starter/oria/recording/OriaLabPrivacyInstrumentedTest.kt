package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriaLabPrivacyInstrumentedTest {
    @Test fun destinationDataIsOmittedRecursivelyWithoutChangingDiagnosticFields() {
        val source = JSONObject().put("type", "navigation_begin")
            .put("destination", "12 rue privée")
            .put("nested", JSONObject().put("query", "domicile").put("routeVersion", 4))
            .put("items", JSONArray().put(JSONObject().put("latitude", 48.0).put("generation", 8)))
        val sanitized = OriaLabPrivacy.sanitize(source)
        assertFalse(sanitized.has("destination"))
        assertFalse(sanitized.getJSONObject("nested").has("query"))
        assertFalse(sanitized.getJSONArray("items").getJSONObject(0).has("latitude"))
        assertEquals(4, sanitized.getJSONObject("nested").getInt("routeVersion"))
        assertEquals(8, sanitized.getJSONArray("items").getJSONObject(0).getInt("generation"))
        assertTrue(sanitized.getBoolean("privateLocationDataOmitted"))
        assertEquals("12 rue privée", source.getString("destination"))
    }
}
