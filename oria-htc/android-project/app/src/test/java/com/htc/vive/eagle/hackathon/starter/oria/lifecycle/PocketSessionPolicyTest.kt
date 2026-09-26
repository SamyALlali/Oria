package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

import org.junit.Assert.*
import org.junit.Test

class PocketSessionPolicyTest {
    @Test fun onlyAnActiveRealConnectedSessionCanContinue() {
        assertTrue(PocketSessionPolicy.canContinue(true, true, true, false, false, 1))
        assertFalse(PocketSessionPolicy.canContinue(false, true, true, false, false, 1))
        assertFalse(PocketSessionPolicy.canContinue(true, false, true, false, false, 1))
        assertFalse(PocketSessionPolicy.canContinue(true, true, false, false, false, 1))
        assertFalse(PocketSessionPolicy.canContinue(true, true, true, true, false, 1))
    }
    @Test fun captureAlwaysStopsWhenLeavingTheApp() {
        assertFalse(PocketSessionPolicy.canContinue(true, true, true, false, true, 1))
    }
    @Test fun sessionIsBoundedAndDoesNotAcceptAReversedClock() {
        assertTrue(PocketSessionPolicy.canContinue(true, true, true, false, false, 899999))
        assertFalse(PocketSessionPolicy.canContinue(true, true, true, false, false, 900000))
        assertFalse(PocketSessionPolicy.canContinue(true, true, true, false, false, -1))
    }
}
