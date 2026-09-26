package echotest.audit

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.htc.vive.eagle.hackathon.starter.echonav.core.*

/** JSONL adapter only: the policy itself is compiled from the Android source files. */
private fun JsonObject.long(name: String): Long = get(name).asLong
private fun JsonObject.config(): RgbAlertConfig {
    val d = RgbAlertConfig()
    val names = setOf("maxObservationAgeMs", "trackAssociationIou", "trackLostAfterMs", "confirmationSamples",
        "confidenceExitMargin", "minimumTrackingConfidence", "selectionHoldMs", "replacementScoreMargin",
        "repeatIntervalMs", "globalAnnouncementGapMs", "failureRetryGapMs", "voiceMemoryRetentionMs",
        "maximumVoiceMemories", "maximumTracks")
    require(keySet().all { it in names }) { "Unknown policy parameter" }
    fun l(n: String, v: Long) = get(n)?.asLong ?: v
    fun i(n: String, v: Int) = get(n)?.asInt ?: v
    fun f(n: String, v: Float) = get(n)?.asFloat ?: v
    return RgbAlertConfig(l("maxObservationAgeMs", d.maxObservationAgeMs),
        f("trackAssociationIou", d.trackAssociationIou), l("trackLostAfterMs", d.trackLostAfterMs),
        i("confirmationSamples", d.confirmationSamples), f("confidenceExitMargin", d.confidenceExitMargin),
        f("minimumTrackingConfidence", d.minimumTrackingConfidence), l("selectionHoldMs", d.selectionHoldMs),
        f("replacementScoreMargin", d.replacementScoreMargin), l("repeatIntervalMs", d.repeatIntervalMs),
        l("globalAnnouncementGapMs", d.globalAnnouncementGapMs), l("failureRetryGapMs", d.failureRetryGapMs),
        l("voiceMemoryRetentionMs", d.voiceMemoryRetentionMs), i("maximumVoiceMemories", d.maximumVoiceMemories),
        i("maximumTracks", d.maximumTracks))
}

// Audit-only reflection reads the unchanged engine; only lifetime ID counters are seeded.
private fun field(engine: RgbAlertEngine, name: String): Any? =
    engine.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(engine)
private fun seedCounter(engine: RgbAlertEngine, name: String, value: Long) =
    engine.javaClass.getDeclaredField(name).apply { isAccessible = true }.setLong(engine, value)
private fun audit(engine: RgbAlertEngine, nowMs: Long): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val candidates = engine.javaClass.getDeclaredMethod("freshCandidates", java.lang.Long.TYPE)
        .apply { isAccessible = true }.invoke(engine, nowMs) as List<RgbCandidate>
    val selected = field(engine, "selectedTrackId") as Long?
    val ordered = candidates.sortedWith(compareByDescending<RgbCandidate> { it.trackId == selected }
        .thenByDescending { it.priority }.thenBy { it.trackId })
    return linkedMapOf("candidateRanking" to candidates, "voiceOrder" to ordered,
        "selectedTrackId" to selected, "selectedSinceMs" to field(engine, "selectedSinceMs"),
        "voiceMemories" to field(engine, "voiceMemories"),
        "lastAttemptAtMs" to field(engine, "lastAttemptAtMs"),
        "lastConfirmedAtMs" to field(engine, "lastConfirmedAtMs"),
        "lastFailureAtMs" to field(engine, "lastFailureAtMs"),
        "audioState" to engine.audioState,
        "nextTrackId" to field(engine, "nextTrackId"), "nextAlertId" to field(engine, "nextAlertId"))
}

private fun readValue(owner: Any, name: String): Any? =
    owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
private fun associationAudit(engine: RgbAlertEngine, detections: List<Detection>, observedAtMs: Long): Map<String, Any?> {
    val tracks = (field(engine, "tracks") as Map<*, *>).values.filterNotNull().filter {
        !RgbAlertPolicy.hasExpired(readValue(it, "observedAtMs") as Long, observedAtMs, engine.config.trackLostAfterMs)
    }
    val ordered = detections.filter { RgbAlertPolicy.validDetection(it) && it.confidence >= engine.config.minimumTrackingConfidence }
        .sortedWith(compareByDescending<Detection> { RgbAlertPolicy.visualPriority(it) }
            .thenBy { it.classId }.thenBy { it.box.left }.thenBy { it.box.top }).take(engine.config.maximumTracks)
    data class Pairing(val trackId: Long, val detectionIndex: Int, val iou: Float)
    val pairs = tracks.flatMap { track ->
        val prior = readValue(track, "detection") as Detection
        ordered.mapIndexedNotNull { index, detection ->
            val iou = RgbAlertPolicy.intersectionOverUnion(prior.box, detection.box)
            if (prior.classId == detection.classId)
                Pairing(readValue(track, "id") as Long, index, iou) else null
        }
    }.sortedWith(compareByDescending<Pairing> { it.iou }.thenBy { it.trackId }.thenBy { it.detectionIndex })
    val usedTracks = mutableSetOf<Long>(); val usedDetections = mutableSetOf<Int>()
    val choices = pairs.filter { it.iou >= engine.config.trackAssociationIou }.map { pairing ->
        val accepted = pairing.trackId !in usedTracks && pairing.detectionIndex !in usedDetections
        if (accepted) { usedTracks += pairing.trackId; usedDetections += pairing.detectionIndex }
        mapOf("trackId" to pairing.trackId, "detectionIndex" to pairing.detectionIndex,
            "iou" to pairing.iou, "greedyAccepted" to accepted)
    }
    return mapOf("priorTracks" to tracks.map { track -> mapOf("id" to readValue(track, "id"),
        "frameId" to readValue(track, "frameId"), "observedAtMs" to readValue(track, "observedAtMs"),
        "detection" to readValue(track, "detection"), "confirmed" to readValue(track, "confirmed"),
        "confirmationSamples" to readValue(track, "confirmationSamples")) },
        "detectionsSortedForTracking" to ordered, "allSameClassIoU" to pairs, "pairingsAboveThreshold" to choices)
}

fun main() {
    val gson = Gson()
    var engine = RgbAlertEngine()
    var lastAlert: VoiceAlert? = null
    val tickets = mutableMapOf<Long, VoiceTicket>()
    var started = false
    generateSequence(::readLine).filter { it.isNotBlank() }.forEach { line ->
        try {
            val input = JsonParser.parseString(line).asJsonObject
            val response = linkedMapOf<String, Any?>("version" to 1, "type" to input.get("type").asString,
                "requestId" to input.get("requestId")?.asString)
            if (input.has("nowMs")) response["beforeAudit"] = gson.toJsonTree(audit(engine, input.long("nowMs")))
            when (input.get("type").asString) {
                "start" -> {
                    // One playback per process; a later start is a real session boundary.
                    require(!started || !input.has("config")) { "Config changes require a new playback process" }
                    if (!started) {
                        engine = RgbAlertEngine(input.getAsJsonObject("config")?.config() ?: RgbAlertConfig())
                        seedCounter(engine, "nextTrackId", input.long("nextTrackId"))
                        seedCounter(engine, "nextAlertId", input.long("nextAlertId"))
                    }
                    engine.start(input.long("sessionId"), input.long("atMs"))
                    started = true
                    lastAlert = null
                    tickets.clear()
                    response["config"] = engine.config
                }
                "frame" -> {
                    val detections = input.getAsJsonArray("detections").map { value ->
                        val d = value.asJsonObject
                        val b = d.getAsJsonObject("box")
                        Detection(d.get("classId").asInt, d.get("confidence").asFloat,
                            Box(b.get("left").asFloat, b.get("top").asFloat,
                                b.get("right").asFloat, b.get("bottom").asFloat))
                    }
                    response["associationAudit"] = gson.toJsonTree(associationAudit(engine, detections, input.long("observedAtMs")))
                    val result = engine.evaluate(DetectionFrame(input.long("sessionId"), input.long("frameId"),
                        input.long("observedAtMs"), detections), input.long("nowMs"))
                    lastAlert = result.eligibleAlert
                    response["frameId"] = input.long("frameId")
                    response["evaluation"] = result
                    response["eligibleAlert"] = result.eligibleAlert
                    response["alertText"] = result.eligibleAlert?.text
                    response["trackQualification"] = result.tracks.map { track ->
                        mapOf("trackId" to track.id,
                            "classId" to track.detection.classId,
                            "confidenceThreshold" to RgbCategory.fromClassId(track.detection.classId)?.confidenceThreshold,
                            "area" to track.detection.box.area,
                            "qualifiesGeometryAtConfidence1" to RgbAlertPolicy.qualifies(track.detection.copy(confidence = 1f)),
                            "qualifiesInitial" to RgbAlertPolicy.qualifies(track.detection),
                            "qualifiesWithExitMargin" to RgbAlertPolicy.qualifies(track.detection, engine.config.confidenceExitMargin),
                            "priority" to RgbAlertPolicy.visualPriority(track.detection))
                    }
                }
                "submitted" -> {
                    val alert = lastAlert?.takeIf { it.id == input.long("alertId") }
                    val ticket = alert?.let { engine.onSubmitted(it, input.long("nowMs")) }
                    if (ticket != null) tickets[ticket.id] = ticket
                    response["accepted"] = ticket != null
                    response["ticketId"] = ticket?.id
                }
                "confirmed", "failed", "ambiguous" -> {
                    val ticket = tickets[input.long("ticketId")]
                    val accepted = ticket != null && when (input.get("type").asString) {
                        "confirmed" -> engine.onConfirmed(ticket, input.long("nowMs"))
                        "failed" -> engine.onFailure(ticket, input.long("nowMs"))
                        else -> engine.onAmbiguous(ticket, input.long("nowMs"))
                    }
                    if (accepted) tickets.remove(ticket!!.id)
                    response["accepted"] = accepted
                }
                "current" -> response["evaluation"] = engine.current(input.long("nowMs"))
                "stop" -> { engine.stop(); lastAlert = null; response["audioState"] = engine.audioState }
                else -> error("Unknown message type")
            }
            if (input.has("nowMs")) response["audit"] = audit(engine, input.long("nowMs"))
            println(gson.toJson(response))
        } catch (error: Exception) {
            System.err.println("EchoTest policy: ${error.message}")
            println(gson.toJson(mapOf("version" to 1, "type" to "error", "error" to error.message)))
            kotlin.system.exitProcess(2)
        }
    }
}
