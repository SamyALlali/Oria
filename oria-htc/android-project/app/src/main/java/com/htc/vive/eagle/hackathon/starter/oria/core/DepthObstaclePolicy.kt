package com.htc.vive.eagle.hackathon.starter.oria.core

/** Provisional capture-relative policy; these thresholds are not calibrated distances. */
data class DepthObstacleConfig(
    val minCandidateFraction: Float = .12f,
    val minConsecutive: Int = 3,
    val minHoldMs: Long = 500,
    val maxGapMs: Long = 1_500,
    val repeatIntervalMs: Long = 8_000,
    val maxObservationAgeMs: Long = DEFAULT_MAX_OBSERVATION_AGE_MS,
    val failureRetryGapMs: Long = 1_000,
) {
    init {
        require(minCandidateFraction.isFinite() && minCandidateFraction in 0f..1f)
        require(minConsecutive > 0 && minHoldMs >= 0 && maxGapMs > 0)
        require(repeatIntervalMs >= 0 && maxObservationAgeMs > 0 && failureRetryGapMs > 0)
    }
    companion object { const val DEFAULT_MAX_OBSERVATION_AGE_MS = 1_500L }
}

data class DepthZoneEvidence(val zone: RgbZone, val candidateFraction: Float, val relativeDepthMedian: Float)
enum class DepthAudioState { AVAILABLE, IN_FLIGHT, UNKNOWN }

data class DepthVoiceAlert(
    val id: Long, val sessionId: Long, val generation: Long,
    val observationIndex: Long, val observedAtMs: Long, val zone: RgbZone,
) {
    val text: String get() = when (zone) {
        RgbZone.LEFT -> "Obstacle possible avant-gauche"
        RgbZone.CENTER -> "Obstacle possible devant"
        RgbZone.RIGHT -> "Obstacle possible avant-droite"
    }
}

/** Opaque identity: only the exact returned ticket may complete the reservation. */
class DepthVoiceTicket internal constructor(val alert: DepthVoiceAlert, val submittedAtMs: Long) {
    val id: Long get() = alert.id
    val sessionId: Long get() = alert.sessionId
    val generation: Long get() = alert.generation
    val zone: RgbZone get() = alert.zone
    val text: String get() = alert.text
}

data class DepthEvaluation(
    val status: String,
    val reason: String,
    val eligibleAlert: DepthVoiceAlert? = null,
    val selectedZone: RgbZone? = null,
    val resetReason: String? = null,
    val audioState: DepthAudioState,
)

/** Pure deterministic state machine. Serialize calls on one owner thread.
 *
 * observationIndex counts every attempted depth observation, not camera frames skipped
 * before inference. None means unavailable evidence, while three valid zero fractions
 * mean no candidate in that observation. Neither means that a path is clear.
 *
 * A proposal does not reserve sound or consume a repeat interval. The caller must pass
 * the correlated ticket to onCompleted only after its own playback acknowledgement.
 * This acknowledgement is not human acoustic proof. No anonymous callback is accepted.
 */
class DepthObstaclePolicy(val config: DepthObstacleConfig = DepthObstacleConfig()) {
    private data class Evidence(var samples: Int = 0, var firstMs: Long? = null, var confirmedAudioMs: Long? = null)
    private val evidence = RgbZone.entries.associateWith { Evidence() }
    private var sessionId: Long? = null
    private var generation = 0L
    private var startedAtMs = 0L
    private var lastClockMs: Long? = null
    private var lastIndex: Long? = null
    private var lastObservedMs: Long? = null
    private var nextAlertId = 1L
    private var offered: DepthVoiceAlert? = null
    private var inFlight: DepthVoiceTicket? = null
    private var reservationPlaybackEligible = false
    private var failureAtMs: Long? = null
    var audioState: DepthAudioState = DepthAudioState.AVAILABLE
        private set

    fun start(sessionId: Long, nowMs: Long) {
        require(sessionId >= 0 && nowMs >= 0)
        invalidateSession()
        this.sessionId = sessionId
        startedAtMs = nowMs
        lastClockMs = nowMs
    }

    fun stop() { invalidateSession(); sessionId = null }

    private fun invalidateSession() {
        generation++
        if (inFlight != null) audioState = DepthAudioState.UNKNOWN
        inFlight = null
        reservationPlaybackEligible = false
        offered = null
        lastIndex = null
        lastObservedMs = null
        lastClockMs = null
        failureAtMs = null
        evidence.values.forEach { it.samples = 0; it.firstMs = null; it.confirmedAudioMs = null }
    }

    private fun breakEvidence() {
        offered = null
        // Evidence recovered later cannot resurrect a request awaiting its first PCM.
        reservationPlaybackEligible = false
        evidence.values.forEach { it.samples = 0; it.firstMs = null }
    }

    private fun result(status: String, reason: String, zone: RgbZone? = null,
                       reset: String? = null, alert: DepthVoiceAlert? = null) =
        DepthEvaluation(status, reason, alert, zone, reset, audioState)

    fun evaluate(sessionId: Long, observationIndex: Long, observedAtMs: Long,
                 zones: List<DepthZoneEvidence>?, nowMs: Long,
                 qualityUsable: Boolean = true, qualityReason: String? = null): DepthEvaluation {
        // A result from an old session cannot cancel a valid current intention.
        if (this.sessionId == null) return result("rejected", "stopped")
        if (this.sessionId != sessionId) return result("rejected", "wrong_session")
        val invalid = when {
            nowMs < 0 || observationIndex < 0 || observedAtMs < 0 -> "invalid_identity"
            lastClockMs?.let { nowMs < it } == true -> "clock_reversed"
            observedAtMs > nowMs -> "future_observation"
            observedAtMs < startedAtMs || nowMs - observedAtMs > config.maxObservationAgeMs -> "stale_observation"
            lastIndex?.let { observationIndex <= it } == true -> "frame_order"
            lastObservedMs?.let { observedAtMs <= it } == true -> "observation_clock_order"
            else -> null
        }
        if (invalid != null) {
            breakEvidence()
            // Valid wall-clock time still advances expiration without rewinding capture marks.
            if (nowMs >= 0 && (lastClockMs == null || nowMs >= lastClockMs!!)) lastClockMs = nowMs
            return result("rejected", invalid, reset = invalid)
        }
        var reset: String? = null
        if (lastIndex?.let { observationIndex != it + 1 } == true) reset = "frame_gap"
        if (lastObservedMs?.let { observedAtMs - it > config.maxGapMs } == true) reset = "observation_gap"
        if (reset != null) breakEvidence()
        offered = null
        lastIndex = observationIndex
        lastObservedMs = observedAtMs
        lastClockMs = nowMs
        val qualityInvalid = (qualityUsable && qualityReason != null) ||
            (!qualityUsable && qualityReason !in setOf("low_light", "low_texture", "low_light,low_texture"))
        if (!qualityUsable || qualityInvalid) {
            breakEvidence()
            return result("uncertain", if (qualityInvalid) "invalid_image_quality" else "image_quality_limited",
                reset = reset ?: "image_quality_limited")
        }
        if (zones == null) {
            breakEvidence()
            return result("missing", "observation_missing", reset = reset ?: "missing_data")
        }
        if (zones.size != 3 || zones.map { it.zone }.toSet() != RgbZone.entries.toSet() || zones.any {
                !it.candidateFraction.isFinite() || it.candidateFraction !in 0f..1f ||
                !it.relativeDepthMedian.isFinite() || it.relativeDepthMedian !in 0f..1f
            }) {
            breakEvidence()
            return result("invalid", "invalid_zone_measurement", reset = reset ?: "invalid_data")
        }
        val candidates = zones.filter { it.candidateFraction > 0 && it.candidateFraction >= config.minCandidateFraction }
        for ((zone, state) in evidence) {
            if (candidates.none { it.zone == zone }) { state.samples = 0; state.firstMs = null }
            else {
                if (state.firstMs == null) state.firstMs = observedAtMs
                if (state.samples < Int.MAX_VALUE) state.samples++
            }
        }
        inFlight?.let { if (evidence.getValue(it.zone).samples < config.minConsecutive) reservationPlaybackEligible = false }
        val confirmed = candidates.filter {
            val state = evidence.getValue(it.zone)
            state.samples >= config.minConsecutive && observedAtMs - state.firstMs!! >= config.minHoldMs
        }
        val selected = confirmed.firstOrNull { it.zone == RgbZone.CENTER } ?: confirmed.sortedWith(
            compareByDescending<DepthZoneEvidence> { it.candidateFraction }.thenBy { it.zone.ordinal }
        ).firstOrNull()
        if (selected == null) return result(if (candidates.isEmpty()) "no_candidate" else "confirming",
            if (candidates.isEmpty()) "no_candidate" else "needs_confirmation", reset = reset)
        val suppressed = when {
            audioState == DepthAudioState.UNKNOWN -> "audio_unknown"
            audioState == DepthAudioState.IN_FLIGHT -> "audio_in_flight"
            evidence.getValue(selected.zone).confirmedAudioMs?.let { nowMs - it < config.repeatIntervalMs } == true -> "zone_cooldown"
            failureAtMs?.let { nowMs - it < config.failureRetryGapMs } == true -> "retry_backoff"
            else -> null
        }
        if (suppressed != null) return result("suppressed", suppressed, selected.zone, reset)
        val alert = DepthVoiceAlert(nextAlertId++, sessionId, generation, observationIndex, observedAtMs, selected.zone)
        offered = alert
        return result("proposal", "confirmed_candidate", selected.zone, reset, alert)
    }

    /** Check again immediately before reserving playback. A later image invalidates this offer. */
    fun onSubmitted(alert: DepthVoiceAlert, nowMs: Long): DepthVoiceTicket? {
        if (audioState != DepthAudioState.AVAILABLE || offered !== alert ||
            sessionId != alert.sessionId || generation != alert.generation || lastIndex != alert.observationIndex ||
            !isFresh(alert.observedAtMs, nowMs) || !acceptClock(nowMs)) return null
        offered = null
        return DepthVoiceTicket(alert, nowMs).also {
            inFlight = it; reservationPlaybackEligible = true; audioState = DepthAudioState.IN_FLIGHT
        }
    }

    /** The integration should also call this immediately before the first PCM sample. */
    fun canPlay(ticket: DepthVoiceTicket, nowMs: Long): Boolean =
        matches(ticket) && reservationPlaybackEligible && audioState == DepthAudioState.IN_FLIGHT &&
            evidence.getValue(ticket.zone).samples >= config.minConsecutive &&
            evidence.getValue(ticket.zone).firstMs?.let { first ->
                lastObservedMs?.let { it - first >= config.minHoldMs } == true
            } == true &&
            isFresh(ticket.alert.observedAtMs, nowMs) && (lastClockMs == null || nowMs >= lastClockMs!!)

    fun onCompleted(ticket: DepthVoiceTicket, nowMs: Long): Boolean {
        if (!matches(ticket) || !acceptClock(nowMs)) return false
        evidence.getValue(ticket.zone).confirmedAudioMs = nowMs
        inFlight = null
        reservationPlaybackEligible = false
        audioState = DepthAudioState.AVAILABLE
        return true
    }

    fun onFailed(ticket: DepthVoiceTicket, nowMs: Long): Boolean {
        if (!matches(ticket) || !acceptClock(nowMs)) return false
        inFlight = null
        reservationPlaybackEligible = false
        audioState = DepthAudioState.AVAILABLE
        failureAtMs = nowMs
        return true
    }

    fun onAudioUnknown(ticket: DepthVoiceTicket): Boolean {
        if (!matches(ticket)) return false
        reservationPlaybackEligible = false
        audioState = DepthAudioState.UNKNOWN
        return true
    }

    /** Call only after the integration has cancelled/flushed the actual backend. */
    fun onAudioReset() {
        inFlight = null
        reservationPlaybackEligible = false
        offered = null
        audioState = DepthAudioState.AVAILABLE
    }

    private fun matches(ticket: DepthVoiceTicket): Boolean =
        inFlight === ticket && sessionId == ticket.sessionId && generation == ticket.generation
    private fun isFresh(observedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= observedAtMs && nowMs - observedAtMs <= config.maxObservationAgeMs
    private fun acceptClock(nowMs: Long): Boolean {
        if (nowMs < 0 || lastClockMs?.let { nowMs < it } == true) return false
        lastClockMs = nowMs
        return true
    }
}
