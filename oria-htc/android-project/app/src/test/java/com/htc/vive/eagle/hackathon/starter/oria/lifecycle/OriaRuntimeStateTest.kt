package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

import org.junit.Assert.*
import org.junit.Test

class OriaRuntimeStateTest {
    @Test fun stopRestartRejectsPreviousGeneration() {
        val state = OriaRuntimeState(0)
        assertTrue(state.starting(1, 10, true))
        assertTrue(state.active(1, 20))
        assertTrue(state.acceptEvidence(1, 20, 21, 500))
        assertTrue(state.interrupted(2, 30, "stop"))
        assertTrue(state.starting(3, 40, true))
        assertFalse(state.acceptEvidence(1, 41, 41, 500))
        assertTrue(state.acceptEvidence(3, 41, 41, 500))
    }

    @Test fun disconnectReconnectRequiresFreshGenerationEvidence() {
        val state = OriaRuntimeState(0)
        state.starting(1, 10, true)
        state.active(1, 11)
        state.disconnected(2, 12, "Bluetooth perdu")
        assertEquals(OriaRuntimePhase.DISCONNECTED, state.snapshot().phase)
        state.ready(2, 13, true)
        assertFalse(state.acceptEvidence(1, 11, 14, 500))
        state.starting(3, 15, true)
        assertTrue(state.acceptEvidence(3, 15, 15, 500))
    }

    @Test fun lockInterruptionCutsLiveEvidenceUntilManualRestart() {
        val state = OriaRuntimeState(0)
        state.starting(1, 10, true)
        state.interrupted(2, 20, "Écran verrouillé sans mode poche")
        assertFalse(state.acceptEvidence(1, 19, 21, 500))
        assertFalse(state.acceptEvidence(2, 21, 21, 500))
    }

    @Test fun bluetoothLossCanBeExplicitlyLimitedWithoutInventingDepth() {
        val state = OriaRuntimeState(0)
        state.starting(1, 10, true)
        state.active(1, 11)
        state.limited(1, 12, OriaRuntimeDependency.AUDIO, "Bluetooth perdu")
        val snapshot = state.snapshot()
        assertEquals(OriaRuntimePhase.LIMITED, snapshot.phase)
        assertEquals(OriaDependencyAvailability.LIMITED,
            snapshot.dependencies.getValue(OriaRuntimeDependency.AUDIO).availability)
        assertEquals(OriaDependencyAvailability.UNAVAILABLE,
            snapshot.dependencies.getValue(OriaRuntimeDependency.DEPTH).availability)
        assertTrue(snapshot.dependencies.getValue(OriaRuntimeDependency.DEPTH).optional)
    }

    @Test fun staleFutureAndOutOfOrderEvidenceAreRejected() {
        val state = OriaRuntimeState(0)
        state.starting(1, 1_000, true)
        assertFalse(state.acceptEvidence(1, 400, 1_001, 500))
        assertFalse(state.acceptEvidence(1, 1_002, 1_001, 500))
        assertTrue(state.acceptEvidence(1, 1_000, 1_001, 500))
        assertFalse(state.acceptEvidence(1, 999, 1_002, 500))
    }

    @Test fun lateDetectorResultAndVoiceCallbackCannotReviveOldSession() {
        val state = OriaRuntimeState(0)
        state.starting(7, 100, true)
        state.active(7, 110)
        state.interrupted(8, 120, "restart")
        state.starting(9, 130, true)
        assertFalse(state.acceptEvidence(7, 115, 131, 500))
        assertFalse(state.dependency(7, 131, OriaRuntimeDependency.AUDIO,
            OriaDependencyAvailability.AVAILABLE, "ancien callback"))
        assertEquals(9, state.snapshot().generation)
    }

    @Test fun reversedTransitionClockDoesNotMutateState() {
        val state = OriaRuntimeState(100)
        state.ready(0, 110, true)
        val before = state.snapshot()
        assertFalse(state.starting(1, 109, true))
        assertEquals(before, state.snapshot())
    }

    @Test fun rgbModeCanBeFullyActiveWithOptionalDepthUnavailable() {
        val state = OriaRuntimeState(0)
        state.starting(1, 10, true)
        state.active(1, 11)
        val snapshot = state.snapshot()
        assertEquals(OriaRuntimePhase.ACTIVE, snapshot.phase)
        assertEquals(OriaDependencyAvailability.UNAVAILABLE,
            snapshot.dependencies.getValue(OriaRuntimeDependency.DEPTH).availability)
        assertTrue(snapshot.dependencies.getValue(OriaRuntimeDependency.DEPTH).optional)
    }
}
