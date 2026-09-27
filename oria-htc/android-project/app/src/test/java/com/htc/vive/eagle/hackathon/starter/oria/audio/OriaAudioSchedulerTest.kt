package com.htc.vive.eagle.hackathon.starter.oria.audio

import org.junit.Assert.*
import org.junit.Test

class OriaAudioSchedulerTest {
    private fun instruction(id: String = "nav", generation: Long = 1, expiry: Long = 3000,
                            kind: OriaAudioKind = OriaAudioKind.NAVIGATION) =
        OriaInstruction(id, generation, kind, "Continuez", 100, expiry)

    @Test fun dangerCancelsInstructionButWaitsForActualTerminalAndIdleBackend() {
        val lane = OriaAudioScheduler().apply { reset(1); offer(instruction(), 100) }
        assertTrue(lane.plan(100, false, true) is OriaAudioPlan.Instruction)
        assertEquals(OriaAudioPlan.CancelInstruction("nav"), lane.plan(120, true, false))
        assertEquals(OriaAudioPlan.Wait, lane.plan(121, true, true))
        lane.finished("old")
        assertEquals(OriaAudioPlan.Wait, lane.plan(122, true, true))
        lane.finished("nav")
        assertEquals(OriaAudioPlan.Wait, lane.plan(123, true, false))
        assertEquals(OriaAudioPlan.Danger, lane.plan(124, true, true))
    }

    @Test fun hazardLaneNeverInterruptedByInstructionOrAnotherHazard() {
        val lane = OriaAudioScheduler().apply { reset(1); dangerStarted("danger"); offer(instruction(), 100) }
        assertEquals(OriaAudioPlan.Wait, lane.plan(101, true, false))
        lane.finished("danger")
        assertEquals(OriaAudioPlan.Danger, lane.plan(102, true, true))
        assertTrue(lane.plan(103, false, true) is OriaAudioPlan.Instruction)
    }

    @Test fun latestInstructionsBoundedAndExpiredSpeechNeverResurrected() {
        val lane = OriaAudioScheduler().apply { reset(1) }
        repeat(1000) { lane.offer(instruction("nav-$it"), 100) }
        lane.offer(instruction("command", kind = OriaAudioKind.COMMAND_RESPONSE), 100)
        assertEquals(2, lane.queuedCount())
        assertEquals("nav-999", (lane.plan(101, false, true) as OriaAudioPlan.Instruction).value.id)
        lane.finished("nav-999")
        assertEquals(OriaAudioPlan.Wait, lane.plan(3001, false, true))
    }

    @Test fun resetRejectsOldGenerationButDoesNotBypassBackendDrain() {
        val lane = OriaAudioScheduler().apply { reset(2) }
        assertFalse(lane.offer(instruction(), 100))
        assertTrue(lane.offer(instruction("new", 2), 100))
        assertEquals(OriaAudioPlan.Wait, lane.plan(101, true, false))
        assertFalse(lane.offer(instruction("future", 2), 99))
    }

    @Test fun routeInvalidationWaitsForTerminalAndDropsQueue() {
        val lane = OriaAudioScheduler().apply { reset(1); offer(instruction(), 100) }
        lane.plan(100, false, true)
        lane.offer(instruction("next"), 101)
        assertEquals("nav", lane.invalidate(OriaAudioKind.NAVIGATION))
        assertEquals(0, lane.queuedCount())
        assertEquals(OriaAudioPlan.Wait, lane.plan(102, false, true))
        lane.finished("nav")
        assertEquals(OriaAudioPlan.Wait, lane.plan(103, false, true))
    }

    @Test fun explicitManualRecoveryRemainsPossibleWhileAutomaticInstructionsArePaused() {
        val lane = OriaAudioScheduler().apply { reset(1) }
        lane.offer(instruction("navigation"), 100)
        lane.offer(instruction("manual", kind = OriaAudioKind.COMMAND_RESPONSE), 100)
        val plan = lane.plan(101, false, true, automaticAllowed = false) as OriaAudioPlan.Instruction
        assertEquals("manual", plan.value.id)
        lane.finished("manual")
        assertEquals(OriaAudioPlan.Wait, lane.plan(102, false, true, automaticAllowed = false))
    }
}
