package com.htc.vive.eagle.hackathon.starter.oria.interaction

import java.text.Normalizer
import java.util.Locale

sealed interface OriaVoiceIntent {
    data object StartPerception : OriaVoiceIntent
    data object StopPerception : OriaVoiceIntent
    data object RepeatActive : OriaVoiceIntent
    data object DescribeAhead : OriaVoiceIntent
    data class GuideTo(val destination: String) : OriaVoiceIntent
    data object PauseNavigation : OriaVoiceIntent
    data object ResumeNavigation : OriaVoiceIntent
    data object StopNavigation : OriaVoiceIntent
    data object MuteAlerts : OriaVoiceIntent
    data object UnmuteAlerts : OriaVoiceIntent
    data object ConfirmDestination : OriaVoiceIntent
    data class SelectDestination(val index: Int) : OriaVoiceIntent
    data object Cancel : OriaVoiceIntent
    data class Ambiguous(val response: String) : OriaVoiceIntent
    data class Unknown(val response: String) : OriaVoiceIntent
}

data class ParsedVoiceIntent(val transcript: String, val normalized: String, val intent: OriaVoiceIntent)

/** Pure French parser. It never executes an action and defaults to no-op on uncertainty. */
object OriaVoiceIntentParser {
    fun parse(transcript: String): ParsedVoiceIntent {
        val spoken = transcript.trim().replace(Regex("\\s+"), " ")
        val text = normalize(spoken)
        if (text.isBlank()) return ParsedVoiceIntent(spoken, text,
            OriaVoiceIntent.Unknown("Je n’ai rien entendu. Réessayez."))

        val exact = when {
            text in setOf("demarre", "lance", "demarre oria", "demarre la perception",
                "lance oria", "lance la perception") -> OriaVoiceIntent.StartPerception
            text in setOf("arrete oria", "arrete la perception",
                "coupe la perception") -> OriaVoiceIntent.StopPerception
            text in setOf("repete", "repete l information", "repete la derniere information",
                "redis", "redis moi") -> OriaVoiceIntent.RepeatActive
            text in setOf("decris", "decris devant", "decris ce qu il y a devant", "qu y a t il devant",
                "qu est ce qu il y a devant") -> OriaVoiceIntent.DescribeAhead
            text in setOf("pause navigation", "mets la navigation en pause", "suspends la navigation") ->
                OriaVoiceIntent.PauseNavigation
            text in setOf("reprends navigation", "reprends la navigation", "continue la navigation") ->
                OriaVoiceIntent.ResumeNavigation
            text in setOf("arrete navigation", "arrete la navigation", "annule la navigation") ->
                OriaVoiceIntent.StopNavigation
            text in setOf("coupe les alertes", "desactive les alertes", "coupe le son", "silence") -> OriaVoiceIntent.MuteAlerts
            text in setOf("remets les alertes", "reactive les alertes", "retablis les alertes", "remets le son") ->
                OriaVoiceIntent.UnmuteAlerts
            text in setOf("confirme", "confirme destination", "confirme la destination", "oui confirme") ->
                OriaVoiceIntent.ConfirmDestination
            text in setOf("premiere destination", "choisis la premiere", "premier resultat") ->
                OriaVoiceIntent.SelectDestination(0)
            text in setOf("deuxieme destination", "choisis la deuxieme", "deuxieme resultat") ->
                OriaVoiceIntent.SelectDestination(1)
            text in setOf("annule", "non annule", "annule la commande") -> OriaVoiceIntent.Cancel
            text in setOf("arrete", "stop", "pause", "reprends", "continue") ->
                OriaVoiceIntent.Ambiguous("Précisez perception ou navigation.")
            else -> null
        }
        if (exact != null) return ParsedVoiceIntent(spoken, text, exact)

        val guidePrefixes = listOf("guide moi vers ", "guide moi a ", "guide vers ", "emmene moi vers ",
            "navigation vers ", "aller a ", "va a ")
        if (text in guidePrefixes.map { it.trim() }) return ParsedVoiceIntent(spoken, text,
            OriaVoiceIntent.Ambiguous("Quelle destination souhaitez-vous ?"))
        val prefix = guidePrefixes.firstOrNull(text::startsWith)
        if (prefix != null) {
            val normalizedDestination = text.removePrefix(prefix).trim()
            val prefixEnd = (1..spoken.length).firstOrNull { normalize(spoken.take(it)) == prefix.trim() }
            val destination = prefixEnd?.let { spoken.drop(it).trim() } ?: normalizedDestination
            val intent = if (destination.length < 3)
                OriaVoiceIntent.Ambiguous("Quelle destination souhaitez-vous ?")
            else OriaVoiceIntent.GuideTo(destination)
            return ParsedVoiceIntent(spoken, text, intent)
        }

        return ParsedVoiceIntent(spoken, text,
            OriaVoiceIntent.Unknown("Commande non reconnue. Dites par exemple : décris devant."))
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.FRENCH),
        Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('’', ' ').replace('\'', ' ').replace('-', ' ')
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}

enum class EagleButtonAction { WAIT_FOR_SECOND_PRESS, RUN_SINGLE_PRESS, START_VOICE_COMMAND, IGNORE }

/** Pure double-press gate: single press is deferred, double press starts hands-free transcription. */
class EagleButtonSequencer(private val doublePressWindowMs: Long = 550L) {
    private var pendingAtMs: Long? = null
    private var sequence = 0L

    init { require(doublePressWindowMs in 250L..1_500L) }

    fun press(atMs: Long): Pair<EagleButtonAction, Long> {
        require(atMs >= 0)
        val pending = pendingAtMs
        sequence++
        return if (pending != null && atMs >= pending && atMs - pending <= doublePressWindowMs) {
            pendingAtMs = null
            EagleButtonAction.START_VOICE_COMMAND to sequence
        } else {
            pendingAtMs = atMs
            EagleButtonAction.WAIT_FOR_SECOND_PRESS to sequence
        }
    }

    fun timeout(token: Long, atMs: Long): EagleButtonAction {
        val pending = pendingAtMs ?: return EagleButtonAction.IGNORE
        if (token != sequence || atMs - pending < doublePressWindowMs) return EagleButtonAction.IGNORE
        pendingAtMs = null
        return EagleButtonAction.RUN_SINGLE_PRESS
    }

    fun reset() { pendingAtMs = null; sequence++ }
}

/** Correlates asynchronous transcription terminals; old or duplicate callbacks are no-ops. */
class TranscriptionRequestGate {
    var activeId: String? = null
        private set

    fun begin(id: String): Boolean {
        require(id.isNotBlank())
        if (activeId != null) return false
        activeId = id
        return true
    }

    fun complete(id: String): Boolean {
        if (id != activeId) return false
        activeId = null
        return true
    }

    fun cancel(): String? = activeId.also { activeId = null }
}
