package orialab

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.htc.vive.eagle.hackathon.starter.oria.core.*

/** JSONL adapter only: the policy itself is compiled from the Android source files. */
private fun JsonObject.long(name: String): Long = get(name).asLong
private fun JsonObject.config(): RgbAlertConfig {
    val d = RgbAlertConfig()
    val names = setOf("maxObservationAgeMs", "trackAssociationIou", "trackLostAfterMs", "confirmationSamples",
        "confidenceExitMargin", "minimumTrackingConfidence", "selectionHoldMs", "replacementScoreMargin",
        "repeatIntervalMs", "globalAnnouncementGapMs", "failureRetryGapMs", "voiceMemoryRetentionMs",
        "maximumVoiceMemories", "maximumTracks", "trackingMode")
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
        i("maximumTracks", d.maximumTracks),
        get("trackingMode")?.asString?.let { RgbTrackingMode.valueOf(it) } ?: d.trackingMode)
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
            when (input.get("type").asString) {
                "start" -> {
                    // One playback per process; a later start is a real session boundary.
                    require(!started || !input.has("config")) { "Config changes require a new playback process" }
                    if (!started) engine = RgbAlertEngine(input.getAsJsonObject("config")?.config() ?: RgbAlertConfig())
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
                    val result = engine.evaluate(DetectionFrame(input.long("sessionId"), input.long("frameId"),
                        input.long("observedAtMs"), detections), input.long("nowMs"))
                    lastAlert = result.eligibleAlert
                    response["frameId"] = input.long("frameId")
                    response["evaluation"] = result
                    response["eligibleAlert"] = result.eligibleAlert
                    response["alertText"] = result.eligibleAlert?.text
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
            println(gson.toJson(response))
        } catch (error: Exception) {
            System.err.println("Oria Lab policy: ${error.message}")
            println(gson.toJson(mapOf("version" to 1, "type" to "error", "error" to error.message)))
            kotlin.system.exitProcess(2)
        }
    }
}
