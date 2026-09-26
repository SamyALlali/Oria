package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

/** This only authorizes retaining an already running session; it never starts or resumes one. */
object PocketSessionPolicy {
    fun canContinue(serviceReady: Boolean, running: Boolean, connected: Boolean,
                    simulator: Boolean, recording: Boolean, elapsedMs: Long): Boolean =
        serviceReady && running && connected && !simulator && !recording &&
            elapsedMs >= 0
}
