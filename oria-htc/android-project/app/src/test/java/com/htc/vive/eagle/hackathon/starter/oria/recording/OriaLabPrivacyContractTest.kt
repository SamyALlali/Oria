package com.htc.vive.eagle.hackathon.starter.oria.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriaLabPrivacyContractTest {
    @Test fun locationAndFreeTextKeysAreOmittedButDiagnosticFieldsRemain() {
        listOf("destination", "query", "address", "label", "name", "origin", "point",
            "latitude", "longitude", "instruction", "currentInstruction", "text")
            .forEach { assertTrue(it, OriaLabPrivacy.mustOmit(it)) }
        listOf("generation", "frameId", "tracks", "candidates", "stabilizationReason",
            "audioState", "routeVersion").forEach { assertFalse(it, OriaLabPrivacy.mustOmit(it)) }
    }
}
