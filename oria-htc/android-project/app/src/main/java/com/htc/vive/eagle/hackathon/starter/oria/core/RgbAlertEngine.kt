package com.htc.vive.eagle.hackathon.starter.oria.core

/** Values inherited from Swift are named separately from provisional RGB adaptations. */
data class RgbAlertConfig(
    val maxObservationAgeMs: Long = 500,
    val trackAssociationIou: Float = 0.25f,
    val trackLostAfterMs: Long = 750,
    val confirmationSamples: Int = 2,
    val confidenceExitMargin: Float = 0.04f,
    val minimumTrackingConfidence: Float = 0.70f,
    val selectionHoldMs: Long = 1_100,
    val replacementScoreMargin: Float = 0.28f,
    val repeatIntervalMs: Long = 6_500,
    val globalAnnouncementGapMs: Long = 1_000,
    val failureRetryGapMs: Long = 1_000,
    val voiceMemoryRetentionMs: Long = 18_000,
    val maximumVoiceMemories: Int = 32,
    val maximumTracks: Int = 300,
    val trackingMode: RgbTrackingMode = RgbTrackingMode.LEGACY_IOU,
) {
    init {
        require(maxObservationAgeMs > 0 && trackLostAfterMs > 0 && confirmationSamples > 0)
        require(trackAssociationIou > 0f && trackAssociationIou <= 1f && confidenceExitMargin in 0f..1f)
        require(minimumTrackingConfidence in 0f..1f)
        require(selectionHoldMs >= 0 && replacementScoreMargin >= 0)
        require(repeatIntervalMs > 0 && globalAnnouncementGapMs > 0 && failureRetryGapMs > 0)
        require(voiceMemoryRetentionMs > 0 && maximumVoiceMemories > 0 && maximumTracks > 0)
    }
}

enum class RgbAudioState { AVAILABLE, IN_FLIGHT, UNKNOWN }
enum class RgbFrameStatus { ACCEPTED, STOPPED, WRONG_SESSION, STALE, FUTURE, OUT_OF_ORDER, CLOCK_REVERSED }
enum class RgbTrackObservationState { VISIBLE, OCCLUDED }
enum class RgbTrackRetirementReason { AMBIGUOUS, EXPIRED, CAPACITY }
enum class RgbSuppressionReason {
    NONE, NO_FRESH_CANDIDATE, NEEDS_CONFIRMATION, SAME_ENTITY_COOLDOWN,
    GLOBAL_PACING, RETRY_BACKOFF, AUDIO_IN_FLIGHT, AUDIO_UNKNOWN, FRAME_REJECTED
}

data class RgbTrack(
    val sessionId: Long,
    val generation: Long,
    val id: Long,
    val detection: Detection,
    val zone: RgbZone,
    val observedAtMs: Long,
    val confirmationSamples: Int,
    val confirmed: Boolean,
    val visibleInLatestFrame: Boolean,
    val associationStatus: RgbAssociationStatus = RgbAssociationStatus.LEGACY_IOU,
    val observationState: RgbTrackObservationState = if (visibleInLatestFrame) {
        RgbTrackObservationState.VISIBLE
    } else {
        RgbTrackObservationState.OCCLUDED
    },
)

/** A bounded diagnostic event. Retirement means lost local geometric continuity, not physical identity. */
data class RgbTrackRetirement(
    val sessionId: Long,
    val generation: Long,
    val trackId: Long,
    val reason: RgbTrackRetirementReason,
    val lastObservedAtMs: Long,
    val retiredAtMs: Long,
)

data class RgbCandidate(
    val sessionId: Long,
    val generation: Long,
    val frameId: Long,
    val trackId: Long,
    val detection: Detection,
    val category: RgbCategory,
    val zone: RgbZone,
    val priority: Float,
    val observedAtMs: Long,
) {
    val text: String get() = "${category.label} ${zone.voiceSuffix}"
}

data class VoiceAlert(
    val id: Long,
    val sessionId: Long,
    val generation: Long,
    val trackId: Long,
    val text: String,
    val observedAtMs: Long,
    val frameId: Long,
    val zone: RgbZone,
)

/** Only the integration layer can establish correlation with an external anonymous callback. */
class VoiceTicket internal constructor(
    val id: Long,
    val sessionId: Long,
    val generation: Long,
    val trackId: Long,
    val submittedAtMs: Long,
    val text: String,
)

data class RgbEvaluation(
    val sessionId: Long?,
    val generation: Long,
    val frameId: Long?,
    val frameStatus: RgbFrameStatus,
    val tracks: List<RgbTrack>,
    val selected: RgbCandidate?,
    val eligibleAlert: VoiceAlert?,
    val suppressionReason: RgbSuppressionReason,
    val audioState: RgbAudioState,
    val rejectedDetectionCount: Int = 0,
    val retiredTracks: List<RgbTrackRetirement> = emptyList(),
    /** Every fresh confirmed RGB candidate, before voice cooldown/pacing. */
    val candidates: List<RgbCandidate> = emptyList(),
)

/** Deterministic RGB-only policy. Serialize calls on one owner thread (or externally lock).
 * No Android types, clocks, world anchors, depth or hidden background work are used here.
 */
class RgbAlertEngine(val config: RgbAlertConfig = RgbAlertConfig()) {
    private data class TrackState(
        val id: Long,
        var detection: Detection,
        var observedAtMs: Long,
        var frameId: Long,
        var confirmationSamples: Int,
        var confirmed: Boolean,
        var previousBox: Box? = null,
        var previousObservedAtMs: Long? = null,
        var associationStatus: RgbAssociationStatus = RgbAssociationStatus.NEW,
    )
    private data class VoiceMemory(var lastSeenAtMs: Long, var confirmedAtMs: Long)
    private data class Pairing(val trackId: Long, val detectionIndex: Int, val iou: Float)

    private var sessionId: Long? = null
    private var generation = 0L
    private var sessionStartedAtMs = 0L
    private var nextTrackId = 1L
    private var nextAlertId = 1L
    private var lastClockMs: Long? = null
    private var latestFrameId: Long? = null
    private var latestObservedAtMs: Long? = null
    private val tracks = linkedMapOf<Long, TrackState>()
    private val voiceMemories = linkedMapOf<Long, VoiceMemory>()
    private var selectedTrackId: Long? = null
    private var selectedSinceMs = 0L
    private var offeredAlert: VoiceAlert? = null
    private var inFlight: VoiceTicket? = null
    private var lastAttemptAtMs: Long? = null
    private var lastConfirmedAtMs: Long? = null
    private var lastFailureAtMs: Long? = null
    var audioState: RgbAudioState = RgbAudioState.AVAILABLE
        private set

    fun start(sessionId: Long, nowMs: Long) {
        require(nowMs >= 0)
        clearPerception()
        generation++
        if (inFlight != null) audioState = RgbAudioState.UNKNOWN
        inFlight = null
        this.sessionId = sessionId
        sessionStartedAtMs = nowMs
        lastClockMs = nowMs
    }

    fun stop() {
        generation++
        sessionId = null
        clearPerception()
        if (inFlight != null) audioState = RgbAudioState.UNKNOWN
        inFlight = null
    }

    private fun clearPerception() {
        tracks.clear()
        voiceMemories.clear()
        latestFrameId = null
        latestObservedAtMs = null
        selectedTrackId = null
        offeredAlert = null
        lastAttemptAtMs = null
        lastConfirmedAtMs = null
        lastFailureAtMs = null
    }

    fun evaluate(frame: DetectionFrame, nowMs: Long): RgbEvaluation {
        val status = when {
            sessionId == null -> RgbFrameStatus.STOPPED
            frame.sessionId != sessionId -> RgbFrameStatus.WRONG_SESSION
            lastClockMs?.let { nowMs < it } == true -> RgbFrameStatus.CLOCK_REVERSED
            frame.observedAtMs > nowMs || frame.observedAtMs < 0 -> RgbFrameStatus.FUTURE
            frame.observedAtMs < sessionStartedAtMs -> RgbFrameStatus.STALE
            !RgbAlertPolicy.isFresh(frame.observedAtMs, nowMs, config.maxObservationAgeMs) -> RgbFrameStatus.STALE
            latestFrameId?.let { frame.frameId <= it } == true ||
                latestObservedAtMs?.let { frame.observedAtMs < it } == true -> RgbFrameStatus.OUT_OF_ORDER
            else -> RgbFrameStatus.ACCEPTED
        }
        if (status != RgbFrameStatus.ACCEPTED) return snapshot(nowMs, status, null, RgbSuppressionReason.FRAME_REJECTED)
        // An old session/result must not cancel the current session's valid dispatch intention.
        offeredAlert = null
        lastClockMs = nowMs
        val retirements = prune(frame.observedAtMs).toMutableList()
        val previousFrameId = latestFrameId
        val valid = frame.detections.filter {
            RgbAlertPolicy.validDetection(it) && it.confidence >= config.minimumTrackingConfidence
        }.sortedWith(compareByDescending<Detection> { RgbAlertPolicy.visualPriority(it) }
            .thenBy { it.classId }.thenBy { it.box.left }.thenBy { it.box.top })
            .take(config.maximumTracks)
        retirements += associate(valid, frame, previousFrameId)
        latestFrameId = frame.frameId
        latestObservedAtMs = frame.observedAtMs
        for (track in tracks.values) {
            if (track.frameId == frame.frameId) voiceMemories[track.id]?.lastSeenAtMs = track.observedAtMs
        }
        val candidates = freshCandidates(nowMs)
        updateSelection(candidates, nowMs)
        val (candidate, reason) = eligibleCandidate(candidates, nowMs)
        val alert = candidate?.let {
            VoiceAlert(nextAlertId++, frame.sessionId, generation, it.trackId, it.text, it.observedAtMs, frame.frameId, it.zone)
        }
        offeredAlert = alert
        return snapshot(nowMs, status, alert, reason, frame.detections.size - valid.size, retirements)
    }

    /** Observe expiration without inventing a new frame or permitting an old alert to be resubmitted. */
    fun current(nowMs: Long): RgbEvaluation {
        val reversed = lastClockMs?.let { nowMs < it } == true
        val retirements = mutableListOf<RgbTrackRetirement>()
        if (!reversed) {
            lastClockMs = nowMs
            retirements += prune(nowMs)
        }
        val status = when {
            reversed -> RgbFrameStatus.CLOCK_REVERSED
            sessionId == null -> RgbFrameStatus.STOPPED
            else -> RgbFrameStatus.ACCEPTED
        }
        return snapshot(nowMs, status, null, RgbSuppressionReason.NO_FRESH_CANDIDATE,
            retirements = retirements)
    }

    /** Call before dispatching to the SDK. Null means that nothing may be submitted. */
    fun onSubmitted(alert: VoiceAlert, nowMs: Long): VoiceTicket? {
        if (audioState != RgbAudioState.AVAILABLE ||
            offeredAlert != alert || sessionId != alert.sessionId || generation != alert.generation ||
            latestFrameId != alert.frameId ||
            !RgbAlertPolicy.isFresh(alert.observedAtMs, nowMs, config.maxObservationAgeMs)) return null
        val candidate = freshCandidates(nowMs).firstOrNull { it.trackId == alert.trackId } ?: return null
        if (eligibleCandidate(listOf(candidate), nowMs).first?.trackId != alert.trackId) return null
        if (!acceptClock(nowMs)) return null
        val ticket = VoiceTicket(alert.id, alert.sessionId, alert.generation, alert.trackId, nowMs, alert.text)
        offeredAlert = null
        inFlight = ticket
        audioState = RgbAudioState.IN_FLIGHT
        lastAttemptAtMs = nowMs
        return ticket
    }

    /** Means SDK confirmation only, never acoustic proof. Caller must establish callback correlation. */
    fun onConfirmed(ticket: VoiceTicket, nowMs: Long): Boolean {
        if (!matches(ticket) || !acceptClock(nowMs)) return false
        inFlight = null
        audioState = RgbAudioState.AVAILABLE
        lastConfirmedAtMs = nowMs
        val lastSeen = tracks[ticket.trackId]?.observedAtMs ?: ticket.submittedAtMs
        voiceMemories[ticket.trackId] = VoiceMemory(lastSeen, nowMs)
        while (voiceMemories.size > config.maximumVoiceMemories) {
            val oldest = voiceMemories.minByOrNull { it.value.lastSeenAtMs } ?: break
            voiceMemories.remove(oldest.key)
        }
        return true
    }

    fun onFailure(ticket: VoiceTicket, nowMs: Long): Boolean {
        if (!matches(ticket) || !acceptClock(nowMs)) return false
        inFlight = null
        audioState = RgbAudioState.AVAILABLE
        lastFailureAtMs = nowMs
        return true
    }

    /** Timeout or uncorrelatable callback: no new dispatch until an external reset is proven. */
    fun onAmbiguous(ticket: VoiceTicket, nowMs: Long): Boolean {
        if (!matches(ticket) || !acceptClock(nowMs)) return false
        inFlight = null
        offeredAlert = null
        audioState = RgbAudioState.UNKNOWN
        return true
    }

    /** Do not wire to a UI retry button without a proven SDK reset/cancellation procedure. */
    fun resetAudioAfterVerifiedReset() {
        inFlight = null
        offeredAlert = null
        audioState = RgbAudioState.AVAILABLE
    }

    private fun matches(ticket: VoiceTicket): Boolean =
        audioState == RgbAudioState.IN_FLIGHT && inFlight == ticket &&
            ticket.generation == generation && ticket.sessionId == sessionId

    private fun acceptClock(nowMs: Long): Boolean {
        if (nowMs < 0 || lastClockMs?.let { nowMs < it } == true) return false
        lastClockMs = nowMs
        return true
    }

    private fun associate(detections: List<Detection>, frame: DetectionFrame,
                          previousFrameId: Long?): List<RgbTrackRetirement> {
        val retirements = mutableListOf<RgbTrackRetirement>()
        val stable = if (config.trackingMode == RgbTrackingMode.STABLE_RGB_V2) {
            StableRgbAssociation.associate(tracks.values.map {
                RgbAssociationInput(it.id, it.detection, it.observedAtMs, it.previousBox, it.previousObservedAtMs)
            }, detections, frame.observedAtMs, config.trackAssociationIou)
        } else null
        stable?.retiredAmbiguousTracks?.forEach { id ->
            tracks.remove(id)?.let { retirements += retirement(it, RgbTrackRetirementReason.AMBIGUOUS, frame.observedAtMs) }
        }
        val pairings = stable?.matches?.map { Pairing(it.trackId, it.detectionIndex, 0f) } ?: tracks.values.flatMap { track ->
            detections.mapIndexedNotNull { index, detection ->
                if (track.detection.classId != detection.classId) null else {
                    val iou = RgbAlertPolicy.intersectionOverUnion(track.detection.box, detection.box)
                    if (iou >= config.trackAssociationIou) Pairing(track.id, index, iou) else null
                }
            }
        }.sortedWith(compareByDescending<Pairing> { it.iou }.thenBy { it.trackId }.thenBy { it.detectionIndex })
        val matchedTracks = mutableSetOf<Long>()
        val matchedDetections = mutableSetOf<Int>()
        for (pairing in pairings) {
            if (pairing.trackId in matchedTracks || pairing.detectionIndex in matchedDetections) continue
            val track = tracks.getValue(pairing.trackId)
            val detection = detections[pairing.detectionIndex]
            val consecutive = track.frameId == previousFrameId
            val margin = if (track.confirmed && consecutive) config.confidenceExitMargin else 0f
            val qualifies = RgbAlertPolicy.qualifies(detection, margin)
            track.confirmationSamples = if (!qualifies) 0 else if (consecutive) track.confirmationSamples + 1 else 1
            track.confirmed = qualifies && track.confirmationSamples >= config.confirmationSamples
            track.previousBox = if (consecutive) track.detection.box else null
            track.previousObservedAtMs = if (consecutive) track.observedAtMs else null
            track.associationStatus = stable?.matches?.firstOrNull { it.trackId == track.id }?.status
                ?: RgbAssociationStatus.LEGACY_IOU
            track.detection = detection
            track.observedAtMs = frame.observedAtMs
            track.frameId = frame.frameId
            matchedTracks += track.id
            matchedDetections += pairing.detectionIndex
        }
        for (track in tracks.values) {
            if (track.id !in matchedTracks) {
                track.confirmed = false
                track.confirmationSamples = 0
            }
        }
        for ((index, detection) in detections.withIndex()) {
            if (index in matchedDetections) continue
            if (tracks.size >= config.maximumTracks) {
                val oldestLost = tracks.values.filter { it.frameId != frame.frameId }.minByOrNull { it.observedAtMs }
                if (oldestLost != null) {
                    tracks.remove(oldestLost.id)
                    retirements += retirement(oldestLost, RgbTrackRetirementReason.CAPACITY, frame.observedAtMs)
                } else break
            }
            val qualifies = RgbAlertPolicy.qualifies(detection)
            val id = nextTrackId++
            tracks[id] = TrackState(id, detection, frame.observedAtMs, frame.frameId,
                if (qualifies) 1 else 0, qualifies && config.confirmationSamples == 1,
                associationStatus = if (index in (stable?.ambiguousNewDetections ?: emptySet()))
                    RgbAssociationStatus.AMBIGUOUS_NEW else RgbAssociationStatus.NEW)
        }
        return retirements
    }

    private fun prune(observedAtMs: Long): List<RgbTrackRetirement> {
        val expired = tracks.values.filter {
            RgbAlertPolicy.hasExpired(it.observedAtMs, observedAtMs, config.trackLostAfterMs)
        }
        expired.forEach { tracks.remove(it.id) }
        voiceMemories.entries.removeAll { RgbAlertPolicy.hasExpired(it.value.lastSeenAtMs, observedAtMs, config.voiceMemoryRetentionMs) }
        return expired.map { retirement(it, RgbTrackRetirementReason.EXPIRED, observedAtMs) }
    }

    private fun retirement(track: TrackState, reason: RgbTrackRetirementReason,
                           retiredAtMs: Long): RgbTrackRetirement =
        RgbTrackRetirement(sessionId ?: -1L, generation, track.id, reason,
            track.observedAtMs, retiredAtMs)

    private fun freshCandidates(nowMs: Long): List<RgbCandidate> = tracks.values.mapNotNull { track ->
        val activeSession = sessionId ?: return@mapNotNull null
        val activeFrame = latestFrameId ?: return@mapNotNull null
        if (!track.confirmed || track.frameId != latestFrameId ||
            !RgbAlertPolicy.isFresh(track.observedAtMs, nowMs, config.maxObservationAgeMs)) return@mapNotNull null
        val category = RgbCategory.fromClassId(track.detection.classId) ?: return@mapNotNull null
        if (!category.alertable) return@mapNotNull null
        RgbCandidate(activeSession, generation, activeFrame, track.id, track.detection, category,
            RgbZone.fromCenterX(track.detection.box.centerX),
            RgbAlertPolicy.visualPriority(track.detection), track.observedAtMs)
    }.sortedWith(compareByDescending<RgbCandidate> { it.priority }.thenBy { it.trackId })

    private fun updateSelection(candidates: List<RgbCandidate>, nowMs: Long) {
        val strongest = candidates.firstOrNull()
        val active = candidates.firstOrNull { it.trackId == selectedTrackId }
        val next = when {
            strongest == null -> null
            active == null -> strongest
            strongest.trackId == active.trackId -> active
            strongest.priority >= active.priority + config.replacementScoreMargin -> strongest
            nowMs - selectedSinceMs >= config.selectionHoldMs -> strongest
            else -> active
        }
        if (next?.trackId != selectedTrackId) selectedSinceMs = nowMs
        selectedTrackId = next?.trackId
    }

    private fun eligibleCandidate(candidates: List<RgbCandidate>, nowMs: Long): Pair<RgbCandidate?, RgbSuppressionReason> {
        if (audioState == RgbAudioState.UNKNOWN) return null to RgbSuppressionReason.AUDIO_UNKNOWN
        if (audioState == RgbAudioState.IN_FLIGHT) return null to RgbSuppressionReason.AUDIO_IN_FLIGHT
        if (candidates.isEmpty()) {
            val waiting = tracks.values.any { it.frameId == latestFrameId && it.confirmationSamples > 0 &&
                RgbAlertPolicy.isFresh(it.observedAtMs, nowMs, config.maxObservationAgeMs) }
            return null to if (waiting) RgbSuppressionReason.NEEDS_CONFIRMATION else RgbSuppressionReason.NO_FRESH_CANDIDATE
        }
        if (lastFailureAtMs?.let { nowMs - it < config.failureRetryGapMs } == true) {
            return null to RgbSuppressionReason.RETRY_BACKOFF
        }
        if (lastAttemptAtMs?.let { nowMs - it < config.globalAnnouncementGapMs } == true ||
            lastConfirmedAtMs?.let { nowMs - it < config.globalAnnouncementGapMs } == true) {
            return null to RgbSuppressionReason.GLOBAL_PACING
        }
        val ordered = candidates.sortedWith(compareByDescending<RgbCandidate> { it.trackId == selectedTrackId }
            .thenByDescending { it.priority }.thenBy { it.trackId })
        val eligible = ordered.firstOrNull {
            voiceMemories[it.trackId]?.let { memory -> nowMs - memory.confirmedAtMs >= config.repeatIntervalMs } != false
        }
        return eligible to if (eligible == null) RgbSuppressionReason.SAME_ENTITY_COOLDOWN else RgbSuppressionReason.NONE
    }

    private fun snapshot(nowMs: Long, status: RgbFrameStatus, alert: VoiceAlert?, reason: RgbSuppressionReason,
                         rejectedCount: Int = 0,
                         retirements: List<RgbTrackRetirement> = emptyList()): RgbEvaluation {
        // WRONG_SESSION can precede CLOCK_REVERSED in validation. No rejected request may
        // make an expired observation look fresh again by supplying an earlier clock.
        val snapshotClock = maxOf(nowMs, lastClockMs ?: nowMs)
        val candidates = if (sessionId != null && status != RgbFrameStatus.CLOCK_REVERSED)
            freshCandidates(snapshotClock) else emptyList()
        return RgbEvaluation(sessionId, generation, latestFrameId, status, tracks.values.map {
            RgbTrack(sessionId ?: -1L, generation, it.id, it.detection,
                RgbZone.fromCenterX(it.detection.box.centerX), it.observedAtMs,
                it.confirmationSamples, it.confirmed, it.frameId == latestFrameId, it.associationStatus)
        }, candidates.firstOrNull { it.trackId == selectedTrackId } ?: candidates.firstOrNull(),
            alert, reason, audioState, rejectedCount, retirements, candidates)
    }
}
