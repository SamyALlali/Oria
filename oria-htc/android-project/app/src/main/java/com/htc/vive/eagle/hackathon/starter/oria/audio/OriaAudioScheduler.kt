package com.htc.vive.eagle.hackathon.starter.oria.audio

import kotlin.math.PI
import kotlin.math.sin

enum class OriaAudioKind { DANGER, NAVIGATION, COMMAND_RESPONSE }
enum class OriaAudioPriority(val rank: Int) { DESCRIPTION(0), NAVIGATION(1), DANGER_LOW(2), DANGER_HIGH(3), DANGER_CRITICAL(4) }
enum class OriaAudioDecisionReason {
    DISPATCHED, QUEUED, PREEMPTED, COMPLETED_NEXT, MUTED, STALE, OLD_GENERATION,
    DUPLICATE, LOWER_PRIORITY, RESET, ROUTE_LOST, SUBMISSION_FAILED, NONE,
    INSTRUCTIONS_INVALIDATED,
}

data class OriaAudioRequest(
    val id: String,
    val generation: Long,
    val kind: OriaAudioKind,
    val priority: OriaAudioPriority,
    val text: String,
    val pan: SpeechPan,
    val observedAtMs: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    init { require(id.isNotBlank() && text.isNotBlank() && observedAtMs >= 0 && createdAtMs >= observedAtMs &&
        expiresAtMs >= createdAtMs) }
}

data class OriaAudioDecision(
    val dispatch: OriaAudioRequest? = null,
    val cancel: OriaAudioRequest? = null,
    val dropped: List<OriaAudioRequest> = emptyList(),
    val reason: OriaAudioDecisionReason = OriaAudioDecisionReason.NONE,
)

data class OriaAudioSnapshot(
    val generation: Long,
    val muted: Boolean,
    val inFlight: OriaAudioRequest?,
    val queued: List<OriaAudioRequest>,
)

/** Pure, single-owner scheduler. It never plays audio or reads a wall clock. */
class OriaAudioScheduler {
    private var generation = 0L
    private var muted = false
    private var inFlight: OriaAudioRequest? = null
    private val queued = linkedMapOf<String, OriaAudioRequest>()
    private val seen = linkedSetOf<String>()

    fun reset(newGeneration: Long, muted: Boolean = this.muted): OriaAudioDecision {
        val cancelled = inFlight
        val dropped = queued.values.toList()
        generation = newGeneration
        this.muted = muted
        inFlight = null
        queued.clear()
        seen.clear()
        return OriaAudioDecision(cancel = cancelled, dropped = dropped, reason = OriaAudioDecisionReason.RESET)
    }

    fun setMuted(value: Boolean): OriaAudioDecision {
        if (muted == value) return OriaAudioDecision(reason = OriaAudioDecisionReason.NONE)
        muted = value
        if (!value) return OriaAudioDecision(reason = OriaAudioDecisionReason.NONE)
        val cancelled = inFlight
        val dropped = queued.values.toList()
        inFlight = null
        queued.clear()
        return OriaAudioDecision(cancel = cancelled, dropped = dropped, reason = OriaAudioDecisionReason.MUTED)
    }

    fun offer(request: OriaAudioRequest, nowMs: Long): OriaAudioDecision {
        if (request.generation != generation) return OriaAudioDecision(dropped = listOf(request),
            reason = OriaAudioDecisionReason.OLD_GENERATION)
        if (nowMs < request.observedAtMs || nowMs > request.expiresAtMs) return OriaAudioDecision(
            dropped = listOf(request), reason = OriaAudioDecisionReason.STALE)
        if (!seen.add(request.id)) return OriaAudioDecision(dropped = listOf(request),
            reason = OriaAudioDecisionReason.DUPLICATE)
        if (seen.size > 512) seen.remove(seen.first())
        if (muted) return OriaAudioDecision(dropped = listOf(request), reason = OriaAudioDecisionReason.MUTED)
        val active = inFlight
        if (active == null) {
            inFlight = request
            return OriaAudioDecision(dispatch = request, reason = OriaAudioDecisionReason.DISPATCHED)
        }
        if (canPreempt(request, active)) {
            inFlight = request
            if (request.kind == OriaAudioKind.DANGER) queued.entries.removeAll { it.value.kind != OriaAudioKind.DANGER }
            return OriaAudioDecision(dispatch = request, cancel = active, reason = OriaAudioDecisionReason.PREEMPTED)
        }
        if (request.kind == OriaAudioKind.DANGER) return OriaAudioDecision(dropped = listOf(request),
            reason = OriaAudioDecisionReason.LOWER_PRIORITY)
        // Only the newest navigation maneuver remains meaningful; command descriptions are bounded too.
        if (request.kind == OriaAudioKind.NAVIGATION) queued.entries.removeAll { it.value.kind == OriaAudioKind.NAVIGATION }
        if (queued.size >= 8) queued.remove(queued.entries.minByOrNull { it.value.priority.rank }?.key)
        queued[request.id] = request
        return OriaAudioDecision(reason = if (request.priority.rank < active.priority.rank)
            OriaAudioDecisionReason.LOWER_PRIORITY else OriaAudioDecisionReason.QUEUED)
    }

    fun finish(id: String, requestGeneration: Long, nowMs: Long,
               reason: OriaAudioDecisionReason = OriaAudioDecisionReason.COMPLETED_NEXT): OriaAudioDecision {
        if (requestGeneration != generation || inFlight?.id != id) return OriaAudioDecision(
            reason = OriaAudioDecisionReason.OLD_GENERATION)
        inFlight = null
        val dropped = queued.values.filter { nowMs < it.observedAtMs || nowMs > it.expiresAtMs }
        dropped.forEach { queued.remove(it.id) }
        if (muted) return OriaAudioDecision(dropped = dropped, reason = OriaAudioDecisionReason.MUTED)
        val next = queued.values.sortedWith(compareByDescending<OriaAudioRequest> { it.priority.rank }
            .thenByDescending { it.observedAtMs }.thenBy { it.id }).firstOrNull()
        next?.let { queued.remove(it.id); inFlight = it }
        return OriaAudioDecision(dispatch = next, dropped = dropped, reason = if (next == null) reason
            else OriaAudioDecisionReason.COMPLETED_NEXT)
    }

    fun routeLost(): OriaAudioDecision {
        val cancelled = inFlight
        val dropped = queued.values.toList()
        inFlight = null
        queued.clear()
        return OriaAudioDecision(cancel = cancelled, dropped = dropped, reason = OriaAudioDecisionReason.ROUTE_LOST)
    }

    fun invalidate(kind: OriaAudioKind, nowMs: Long): OriaAudioDecision {
        val dropped = queued.values.filter { it.kind == kind }.toMutableList()
        dropped.forEach { queued.remove(it.id) }
        val cancelled = inFlight?.takeIf { it.kind == kind }
        if (cancelled != null) inFlight = null
        val stale = queued.values.filter { nowMs < it.observedAtMs || nowMs > it.expiresAtMs }
        stale.forEach { queued.remove(it.id) }
        dropped += stale
        val next = if (cancelled != null && !muted) queued.values.sortedWith(
            compareByDescending<OriaAudioRequest> { it.priority.rank }
                .thenByDescending { it.observedAtMs }.thenBy { it.id }).firstOrNull() else null
        next?.let { queued.remove(it.id); inFlight = it }
        return OriaAudioDecision(dispatch = next, cancel = cancelled, dropped = dropped,
            reason = OriaAudioDecisionReason.INSTRUCTIONS_INVALIDATED)
    }

    fun snapshot(): OriaAudioSnapshot = OriaAudioSnapshot(generation, muted, inFlight, queued.values.toList())

    private fun canPreempt(incoming: OriaAudioRequest, active: OriaAudioRequest): Boolean =
        incoming.kind == OriaAudioKind.DANGER && incoming.priority.rank >= OriaAudioPriority.DANGER_HIGH.rank &&
            (active.kind != OriaAudioKind.DANGER || incoming.priority.rank > active.priority.rank)
}

enum class DangerSoundLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

data class DangerSoundPattern(
    val level: DangerSoundLevel,
    val frequencyHz: Double,
    val cycleMs: Long,
    val activeMs: Long,
    val pan: SpeechPan,
) {
    init { require(frequencyHz >= 0 && cycleMs >= 0 && activeMs >= 0 && activeMs <= cycleMs) }
    val audible: Boolean get() = level != DangerSoundLevel.NONE

    companion object {
        fun silent() = DangerSoundPattern(DangerSoundLevel.NONE, 0.0, 0, 0, SpeechPan.CENTER)
        fun forLevel(level: DangerSoundLevel, pan: SpeechPan): DangerSoundPattern = when (level) {
            DangerSoundLevel.NONE -> silent()
            DangerSoundLevel.LOW -> DangerSoundPattern(level, 420.0, 1_000, 80, pan)
            DangerSoundLevel.MEDIUM -> DangerSoundPattern(level, 620.0, 520, 100, pan)
            DangerSoundLevel.HIGH -> DangerSoundPattern(level, 850.0, 260, 120, pan)
            DangerSoundLevel.CRITICAL -> DangerSoundPattern(level, 1_050.0, 1_000, 1_000, pan)
        }
    }
}

internal data class RenderedDangerTone(val pcm16Stereo: ByteArray, val nextPhase: Double)

/** Pure renderer shared by the Android player and unit tests. Stereo balance follows the
 * physically checked speech convention: 70/30 on the indicated side, 100/100 in front. */
internal object DangerToneRenderer {
    fun render(pattern: DangerSoundPattern, sampleRate: Int, frames: Int,
               startedAtMs: Long, initialPhase: Double): RenderedDangerTone {
        require(sampleRate > 0 && frames >= 0 && initialPhase >= 0.0)
        val bytes = ByteArray(frames * 4)
        var phase = initialPhase
        val gains = when (pattern.pan) {
            SpeechPan.LEFT -> .7 to .3
            SpeechPan.RIGHT -> .3 to .7
            SpeechPan.CENTER -> 1.0 to 1.0
        }
        for (frame in 0 until frames) {
            val offsetMs = startedAtMs + frame * 1_000L / sampleRate
            val active = pattern.audible && pattern.cycleMs > 0 &&
                offsetMs % pattern.cycleMs < pattern.activeMs
            val sample = if (active) (sin(phase) * 2_800).toInt() else 0
            phase += 2.0 * PI * pattern.frequencyHz / sampleRate
            while (phase >= 2.0 * PI) phase -= 2.0 * PI
            writeSample(bytes, frame * 4, (sample * gains.first).toInt())
            writeSample(bytes, frame * 4 + 2, (sample * gains.second).toInt())
        }
        return RenderedDangerTone(bytes, phase)
    }

    private fun writeSample(bytes: ByteArray, offset: Int, value: Int) {
        val sample = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[offset] = (sample and 0xff).toByte()
        bytes[offset + 1] = ((sample ushr 8) and 0xff).toByte()
    }
}
