package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

class FusionVoiceArbiterTest {
    @Test fun initialTieIsYoloAndContinuousReadinessAlternatesWithoutStarvation() {
        val arbiter = FusionVoiceArbiter()
        val choices = List(20) {
            arbiter.choose(true, true, true)!!.also(arbiter::onSubmitted)
        }
        assertEquals(List(20) { if (it % 2 == 0) FusionVoiceSource.YOLO else FusionVoiceSource.DEPTH }, choices)
    }

    @Test fun BusyAudioNeverOffersAndDoesNotConsumeTheTurn() {
        val arbiter = FusionVoiceArbiter()
        arbiter.onSubmitted(FusionVoiceSource.YOLO)
        repeat(5) { assertNull(arbiter.choose(true, true, false)) }
        assertEquals(FusionVoiceSource.DEPTH, arbiter.choose(true, true, true))
    }

    @Test fun declinedOrExpiredOfferDoesNotAdvanceArbitration() {
        val arbiter = FusionVoiceArbiter()
        assertEquals(FusionVoiceSource.YOLO, arbiter.choose(true, true, true))
        assertNull(arbiter.choose(false, false, true))
        assertEquals(FusionVoiceSource.YOLO, arbiter.choose(true, true, true))
    }

    @Test fun absentOtherSourceDoesNotArtificiallySilenceAnEligibleSource() {
        val arbiter = FusionVoiceArbiter()
        repeat(3) {
            assertEquals(FusionVoiceSource.DEPTH, arbiter.choose(false, true, true))
            arbiter.onSubmitted(FusionVoiceSource.DEPTH)
        }
        assertEquals(FusionVoiceSource.YOLO, arbiter.choose(true, true, true))
    }

    @Test fun newSessionResetRestoresInitialOrder() {
        val arbiter = FusionVoiceArbiter()
        arbiter.onSubmitted(FusionVoiceSource.YOLO)
        arbiter.reset()
        assertEquals(FusionVoiceSource.YOLO, arbiter.choose(true, true, true))
    }
}
