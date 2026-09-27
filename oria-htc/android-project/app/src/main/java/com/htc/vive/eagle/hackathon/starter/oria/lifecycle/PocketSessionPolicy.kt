package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

/** Eligibility for a user-started assistance session. Neither check starts or resumes perception. */
object PocketSessionPolicy {
    /** Rechecked when Android delivers the start request, as visibility and readiness may change. */
    fun canPrepare(visible: Boolean, running: Boolean, connected: Boolean, simulator: Boolean,
                   objectModelReady: Boolean, obstacleModelReady: Boolean, audioReady: Boolean, recording: Boolean,
                   storageBusy: Boolean): Boolean =
        visible && !running && connected && !simulator && objectModelReady && obstacleModelReady && audioReady &&
            !recording && !storageBusy

    fun canContinue(serviceReady: Boolean, running: Boolean, connected: Boolean,
                    simulator: Boolean, recording: Boolean, elapsedMs: Long): Boolean =
        serviceReady && running && connected && !simulator && !recording &&
            elapsedMs >= 0
}
