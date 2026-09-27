package com.htc.vive.eagle.hackathon.starter.oria.recording

import com.htc.vive.eagle.hackathon.starter.oria.core.RgbCategory
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbZone
import java.util.Locale

/** Pure persistence projection, not anonymization of camera images or arbitrary user documents.
 * Sensitive traces have a closed structured vocabulary: never preserve their free-form status,
 * errors or text. Other diagnostic events keep their shape, except explicit private fields and
 * free speech. Call before EVERY persistence sink, including Logcat, not only Lab recordings.
 */
object OriaLabPrivacy {
    const val MAX_DEPTH = 32
    const val MAX_NODES = 200_000
    private const val OMITTED = "privateDataOmitted"
    private val phrases = (RgbCategory.entries.filter { it.alertable }.flatMap { category ->
        RgbZone.entries.map { "${category.label} ${it.voiceSuffix}" }
    } + RgbZone.entries.map { "Obstacle possible ${it.voiceSuffix}" }).toSet()
    private val privateKeys = setOf("destination", "query", "address", "latitude", "longitude", "lat", "lon", "lng",
        "coordinate", "coordinates", "geopoint", "point", "origin", "location", "routegeometry", "polyline",
        "transcript", "transcription", "utterance", "recognizedtext", "recognitionresult", "instruction", "currentinstruction")
    private val contexts = setOf("navigation", "voicecommand", "voiceinteraction", "interaction", "recognition", "asr", "route")
    private val sensitiveTypes = setOf("sensitive_event", "navigation_geocode", "navigation_pause", "navigation_resume",
        "navigation_stop", "navigation_audio_preempted", "navigation_begin", "navigation_route", "navigation_arrived",
        "navigation_gps_lost", "navigation_error", "navigation_async_invalidated", "voice_command_start",
        "voice_command_late_callback", "voice_command_parsed", "voice_command_failed", "speech_sdk_success",
        "speech_failed", "speech_expired_before_dispatch", "speech_pcm_start_guard", "speech_rejected", "speech_submitted",
        "speech_local_result", "speech_local_cancelled", "audio_uncertain", "audio_scheduler", "audio_instruction_preempted",
        "policy_voice", "depth_voice")
    private val numberFields = setOf("atMs", "policyAtMs", "sessionId", "videoSessionId", "generation", "frame", "frameId",
        "receivedAtMs", "observedAtMs", "evaluatedAtMs", "recordedAtMs", "submittedAtMs", "delayMs", "observationAgeMs",
        "ticketId", "alertId", "trackId", "routeVersion", "instructionVersion", "version", "count", "token", "attempt",
        "durationMs", "elapsedMs", "queueSize", "schemaVersion")
    private val booleanFields = setOf("accepted", "allowed", "simulated", "enabled", "available", "cancelled", OMITTED)
    private val uuidFields = setOf("requestId", "recordingSessionId")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val navigationId = Regex("nav-[0-9]{1,19}-[0-9]{1,19}-[0-9]{1,19}-[0-9]{1,19}")
    private val enums = mapOf(
        "source" to setOf("htc_live", "htc_simulator", "instrumented_synthetic"),
        "mode" to setOf("REAL", "SIMULATED"), "backend" to setOf("BLUETOOTH", "HTC"),
        "pan" to setOf("LEFT", "CENTER", "RIGHT", "HTC_UNCONTROLLED"), "zone" to setOf("LEFT", "CENTER", "RIGHT"),
        "action" to setOf("submitted", "confirmed", "failed", "ambiguous"),
        "result" to setOf("COMPLETED", "EXPIRED", "NOT_PLAYED", "INTERRUPTED"),
        "event" to setOf("SUCCESS", "ERROR", "ERROR_RESOURCE_CONFLICT", "ERROR_UNSUPPORTED_LOCALE"),
        "perceptionMode" to setOf("objects_and_depth", "depth_only_experimental", "yolo_or_manual"),
        "kind" to setOf("DANGER", "NAVIGATION", "INTERACTION", "COMMAND_RESPONSE"),
    )
    // New metadata fields must be reviewed instead of silently persisting a destination or transcript.
    private val metadataFields = setOf("videoSessionId", "source", "schemaVersion", "captureConsent", "destinationPersistence",
        "perceptionMode", "depthModelSha256", "depthThreads", "depthSampleIntervalMs", "depthModelManifest",
        "depthGeometryVersion", "depthEvidenceIncluded", "depthMetric", "rgbInferenceEnabled", "depthPolicyConfig",
        "orientationProfile", "fusionVoiceArbitration", "onnxSha256", "modelManifest", "modelProvider", "deviceModel",
        "androidApi", "rotationAppliedDegrees", "mirrored", "orientationVerified", "voiceBackend", "sampleIntervalMs",
        "samplingMode", "detectionConfidenceFloor", "rawModelOutputIncluded", "rawModelOutputContract", "modelDetectionsContract",
        "boxCoordinates", "policyConfig", "clock", "packageName", "versionName", "versionCode", "lastUpdateTime", "apkSha256",
        "navigation", "voiceInteraction", OMITTED)

    fun sanitizeEvent(source: Map<String, Any?>): Map<String, Any?> = Projection().map(source, 0, false)
    fun sanitizeMetadata(source: Map<String, Any?>): Map<String, Any?> = Projection().map(source, 0, false, metadata = true)

    private fun normalized(key: String) = key.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun sensitive(type: Any?): Boolean = type is String && (type in sensitiveTypes ||
        listOf("navigation", "voice_command", "voice_interaction", "speech", "audio", "recognition", "asr", "tts", "interaction").any {
            type.lowercase(Locale.ROOT).startsWith(it)
        })
    private fun scalarNumber(value: Any?) = value is Byte || value is Short || value is Int || value is Long ||
        value is Float && value.isFinite() || value is Double && value.isFinite()

    private class Projection {
        private var nodes = 0
        private fun visit(depth: Int) { require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "privacy_structure_limit" } }

        fun map(source: Map<*, *>, depth: Int, inheritedSensitive: Boolean, metadata: Boolean = false): Map<String, Any?> {
            visit(depth)
            val guarded = inheritedSensitive || sensitive(source["type"])
            val out = linkedMapOf<String, Any?>()
            var omitted = false
            source.forEach { (rawKey, value) ->
                visit(depth + 1)
                require(rawKey is String && rawKey.length <= 128) { "privacy_invalid_key" }
                val key = rawKey
                val norm = normalized(key)
                when {
                    metadata && key !in metadataFields -> omitted = true
                    norm in privateKeys -> omitted = true
                    key == "type" && guarded -> {
                        out[key] = if (value is String && value in sensitiveTypes) value else "sensitive_event"
                        if (out[key] != value) omitted = true
                    }
                    norm == "text" -> if (value is String && value in phrases) out[key] = value else omitted = true
                    norm in contexts -> if (value is Map<*, *>) out[key] = map(value, depth + 1, true) else omitted = true
                    guarded -> when {
                        key in numberFields && scalarNumber(value) -> out[key] = value
                        key in booleanFields && value is Boolean -> out[key] = value
                        key in uuidFields && value is String && (uuid.matches(value) ||
                            key == "requestId" && navigationId.matches(value)) -> out[key] = value
                        value is String && enums[key]?.contains(value) == true -> out[key] = value
                        else -> omitted = true
                    }
                    else -> out[key] = when (value) {
                        is Map<*, *> -> map(value, depth + 1, false)
                        else -> copy(value, depth + 1)
                    }
                }
            }
            if (omitted) out[OMITTED] = true
            return out
        }

        private fun copy(value: Any?, depth: Int): Any? {
            visit(depth)
            return when (value) {
                null, is String, is Boolean -> value
                is Map<*, *> -> map(value, depth + 1, false)
                is List<*> -> value.map { copy(it, depth + 1) }
                else -> { require(scalarNumber(value)) { "privacy_invalid_scalar" }; value }
            }
        }
    }
}
