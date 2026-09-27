package com.htc.vive.eagle.hackathon.starter.oria.core

enum class FusionVoiceSource { YOLO, DEPTH }

/** Arbitration only; one serialized caller owns freshness, tickets and the audio transport.
 * Pass only current, confirmed, fresh offers to choose. Fairness applies while both
 * sources remain eligible, never by reviving an expired offer. An actual reservation
 * advances the turn; merely inspecting or declining an offer does not.
 */
class FusionVoiceArbiter {
    private var lastSubmitted: FusionVoiceSource? = null

    fun choose(yoloReady: Boolean, depthReady: Boolean, audioAvailable: Boolean): FusionVoiceSource? = when {
        !audioAvailable -> null
        yoloReady && depthReady -> if (lastSubmitted == FusionVoiceSource.YOLO) FusionVoiceSource.DEPTH else FusionVoiceSource.YOLO
        yoloReady -> FusionVoiceSource.YOLO
        depthReady -> FusionVoiceSource.DEPTH
        else -> null
    }

    /** Call only after the selected policy returned a valid submitted ticket. */
    fun onSubmitted(source: FusionVoiceSource) { lastSubmitted = source }
    fun reset() { lastSubmitted = null }
}
