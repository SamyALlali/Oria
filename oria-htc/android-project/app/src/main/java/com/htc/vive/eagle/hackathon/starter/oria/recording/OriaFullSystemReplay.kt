package com.htc.vive.eagle.hackathon.starter.oria.recording

import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioDecision
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioKind
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioPriority
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioRequest
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioScheduler
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.oria.core.DangerLevel
import com.htc.vive.eagle.hackathon.starter.oria.core.DangerResolutionEngine
import com.htc.vive.eagle.hackathon.starter.oria.core.DetectionFrame
import com.htc.vive.eagle.hackathon.starter.oria.core.OriaDangerAdapter
import com.htc.vive.eagle.hackathon.starter.oria.core.OriaDistanceEvidence
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbAlertEngine
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbZone
import com.htc.vive.eagle.hackathon.starter.oria.navigation.LocationFix
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationEvent
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationMode
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationRoute
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationSpeech
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationEngine
import java.security.MessageDigest

/** Typed replay input. It deliberately contains no wall-clock access and performs no persistence. */
sealed interface OriaReplayAction {
    val atMs: Long

    data class Frame(
        override val atMs: Long,
        val frame: DetectionFrame,
        val depthByTrack: Map<Long, OriaDistanceEvidence> = emptyMap(),
        val frontalDepth: OriaDistanceEvidence? = null,
    ) : OriaReplayAction

    data class StartNavigation(override val atMs: Long, val route: NavigationRoute) : OriaReplayAction
    data class Location(override val atMs: Long, val fix: LocationFix) : OriaReplayAction
    data class FinishAudio(override val atMs: Long) : OriaReplayAction
    data class Seek(override val atMs: Long) : OriaReplayAction
}

data class OriaReplayResult(val records: List<String>, val behavioralSha256: String)

/**
 * Full-system deterministic harness. Direct and replay paths share the production RGB tracker,
 * danger resolver, audio arbiter and navigation engine. A seek invalidates every state owner.
 */
class OriaFullSystemReplay(private val approachEnabled: Boolean = false) {
    private var clockMs = 0L
    private var generation = 1L
    private var audioSequence = 0L
    private var rgb = RgbAlertEngine()
    private var danger = DangerResolutionEngine()
    private var audio = OriaAudioScheduler()
    private var navigation = NavigationEngine()
    private val records = mutableListOf<String>()

    init { resetOwners(0, record = false) }

    fun replay(actions: List<OriaReplayAction>): OriaReplayResult {
        actions.forEach(::apply)
        val snapshot = records.toList()
        return OriaReplayResult(snapshot, sha256(snapshot.joinToString("\n")))
    }

    private fun apply(action: OriaReplayAction) {
        require(action.atMs >= 0) { "Replay time must be monotonic-origin milliseconds" }
        if (action is OriaReplayAction.Seek) {
            generation++
            resetOwners(action.atMs, record = true)
            return
        }
        require(action.atMs >= clockMs) { "Only an explicit seek may move the replay clock" }
        clockMs = action.atMs
        when (action) {
            is OriaReplayAction.Frame -> frame(action)
            is OriaReplayAction.StartNavigation -> startNavigation(action)
            is OriaReplayAction.Location -> location(action)
            is OriaReplayAction.FinishAudio -> finishAudio()
            is OriaReplayAction.Seek -> Unit
        }
    }

    private fun frame(action: OriaReplayAction.Frame) {
        val source = action.frame.copy(sessionId = generation, observedAtMs = clockMs)
        val rgbResult = rgb.evaluate(source, clockMs)
        val resolution = danger.resolve(OriaDangerAdapter.input(rgbResult, action.depthByTrack,
            action.frontalDepth, clockMs, approachEnabled))
        val audioDecision = rgbResult.eligibleAlert?.let { alert ->
            audio.offer(OriaAudioRequest(
                id = "replay-danger-${generation}-${++audioSequence}", generation = generation,
                kind = OriaAudioKind.DANGER, priority = when (resolution.selected?.level) {
                    DangerLevel.HIGH -> OriaAudioPriority.DANGER_HIGH
                    DangerLevel.MEDIUM, DangerLevel.LOW -> OriaAudioPriority.DANGER_LOW
                    else -> OriaAudioPriority.DANGER_LOW
                }, text = alert.text, pan = alert.zone.pan(), observedAtMs = clockMs,
                createdAtMs = clockMs, expiresAtMs = clockMs + 500,
            ), clockMs)
        }
        if (audioDecision?.cancel?.kind == OriaAudioKind.NAVIGATION) navigation.audioInterrupted(clockMs)
        val tracks = rgbResult.tracks.joinToString(",") { track ->
            "${track.id}:${track.detection.classId}:${track.detection.confidence.toRawBits()}:" +
                "${track.detection.box.left.toRawBits()}:${track.detection.box.top.toRawBits()}:" +
                "${track.detection.box.right.toRawBits()}:${track.detection.box.bottom.toRawBits()}"
        }
        val candidates = rgbResult.candidates.joinToString(",") {
            "${it.trackId}:${it.category.name}:${it.zone.name}:${it.priority.toRawBits()}"
        }
        val depths = action.depthByTrack.toSortedMap().entries.joinToString(",") { (track, depth) ->
            "$track:${depth.relativeProximity?.toRawBits()}:${depth.confidence.toRawBits()}:${depth.trend.name}"
        }
        records += listOf("frame", clockMs, generation, source.frameId, rgbResult.frameStatus.name,
            "tracks=$tracks", "candidates=$candidates", "depth=$depths",
            "selected=${resolution.selected?.ownerKey}", "stabilization=${resolution.stabilizationReason.name}",
            "decision=${resolution.reason.name}", "audio=${audioDecision?.fingerprint() ?: "NONE"}").joinToString("|")
    }

    private fun startNavigation(action: OriaReplayAction.StartNavigation) {
        navigation.start(generation, action.route, clockMs,
            if (action.route.simulated) NavigationMode.SIMULATED else NavigationMode.REAL)
        audio.invalidate(OriaAudioKind.NAVIGATION, clockMs)
        records += "navigation-start|$clockMs|$generation|route=${action.route.version}|maneuvers=${action.route.maneuvers.size}"
    }

    private fun location(action: OriaReplayAction.Location) {
        val events = navigation.onLocation(action.fix.copy(generation = generation, observedAtMs = clockMs))
        val audioResults = events.mapNotNull { event ->
            when (event) {
                is NavigationEvent.Speak -> offerNavigation(event.speech)
                is NavigationEvent.Arrived -> offerNavigation(event.speech)
                is NavigationEvent.Recalculate -> {
                    audio.invalidate(OriaAudioKind.NAVIGATION, clockMs).fingerprint()
                }
            }
        }
        val state = navigation.snapshot()
        records += listOf("location", clockMs, generation, state.phase.name, "route=${state.routeVersion}",
            "instruction=${state.instructionVersion}", "maneuver=${state.maneuverIndex}",
            "remaining=${state.remainingMeters?.toRawBits()}", "events=${events.map { it.javaClass.simpleName }}",
            "audio=$audioResults").joinToString("|")
    }

    private fun offerNavigation(speech: NavigationSpeech): String = audio.offer(OriaAudioRequest(
        id = "replay-navigation-${generation}-${++audioSequence}", generation = generation,
        kind = OriaAudioKind.NAVIGATION, priority = OriaAudioPriority.NAVIGATION,
        text = speech.text, pan = SpeechPan.CENTER, observedAtMs = clockMs,
        createdAtMs = clockMs, expiresAtMs = speech.expiresAtMs.coerceAtLeast(clockMs),
    ), clockMs).fingerprint()

    private fun finishAudio() {
        val current = audio.snapshot().inFlight
        val decision = if (current == null) OriaAudioDecision() else audio.finish(current.id, generation, clockMs)
        records += "audio-finish|$clockMs|$generation|${decision.fingerprint()}"
    }

    private fun resetOwners(atMs: Long, record: Boolean) {
        clockMs = atMs
        audioSequence = 0
        rgb.stop()
        rgb.start(generation, atMs)
        danger.reset(generation)
        val audioReset = audio.reset(generation)
        navigation.cancel(atMs, "replay_seek")
        if (record) records += "seek|$atMs|$generation|rgb=RESET|memory=RESET|decision=RESET|" +
            "audio=${audioReset.reason.name}|navigation=RESET"
    }

    private fun OriaAudioDecision.fingerprint(): String =
        "${reason.name}:${dispatch?.kind?.name}:${cancel?.kind?.name}:${dropped.map { it.kind.name }}"

    private fun RgbZone.pan(): SpeechPan = when (this) {
        RgbZone.LEFT -> SpeechPan.LEFT
        RgbZone.CENTER -> SpeechPan.CENTER
        RgbZone.RIGHT -> SpeechPan.RIGHT
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
