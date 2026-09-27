package com.htc.vive.eagle.hackathon.starter.oria.robustness

import org.junit.Assert.*
import org.junit.Test

class OriaFailurePolicyTest {
    @Test fun everyFailureHasExplicitImpactMessageAndRecovery() {
        OriaFailure.entries.forEach { failure ->
            val impact = OriaFailurePolicy.impact(failure)
            assertTrue("$failure must cut at least one output", impact.cutOutputs.isNotEmpty())
            assertTrue("$failure needs a user-facing message", impact.message.isNotBlank())
            assertTrue("$failure needs a recovery condition", impact.recovery.isNotBlank())
        }
    }

    @Test fun optionalFailuresPreserveSafeCoreFunctions() {
        assertTrue(OriaFailurePolicy.impact(OriaFailure.DEPTH).preserved.contains("rgb"))
        assertTrue(OriaFailurePolicy.impact(OriaFailure.NETWORK).preserved.contains("perception"))
        assertTrue(OriaFailurePolicy.impact(OriaFailure.NETWORK).preserved.contains("local_voice"))
        assertTrue(OriaFailurePolicy.impact(OriaFailure.MICROPHONE).preserved.contains("danger_audio"))
        assertTrue(OriaFailurePolicy.impact(OriaFailure.GPS).cutOutputs.contains("fresh_navigation_speech"))
    }

    @Test fun terminalFaultAndEveryRestartFenceAllOldOutputs() {
        OriaFailure.entries.filter { OriaFailurePolicy.impact(it).requiresNewGeneration }.forEach { failure ->
            val gate = OriaRecoveryGate(40)
            val staleGeneration = gate.generation
            gate.fail(failure)
            assertFalse("$failure accepted stale output", gate.accepts(staleGeneration))
            assertTrue(gate.accepts(41))
        }
        val optional = OriaRecoveryGate(7)
        optional.fail(OriaFailure.DEPTH)
        assertTrue(optional.accepts(7))
        optional.restart()
        assertFalse(optional.accepts(7))
        assertTrue(optional.accepts(8))
    }
}
