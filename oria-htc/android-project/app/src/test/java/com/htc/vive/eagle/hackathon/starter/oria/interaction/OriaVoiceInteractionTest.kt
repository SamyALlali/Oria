package com.htc.vive.eagle.hackathon.starter.oria.interaction

import org.junit.Assert.*
import org.junit.Test

class OriaVoiceInteractionTest {
    @Test fun parsesCriticalCommandsAndFrenchVariants() {
        assertEquals(OriaVoiceIntent.StartPerception, parse("Démarre ÉchoNav"))
        assertEquals(OriaVoiceIntent.StartPerception, parse("démarre"))
        assertEquals(OriaVoiceIntent.StopPerception, parse("arrête la perception"))
        assertEquals(OriaVoiceIntent.RepeatActive, parse("Répète la dernière information"))
        assertEquals(OriaVoiceIntent.DescribeAhead, parse("Qu’est-ce qu’il y a devant ?"))
        assertEquals(OriaVoiceIntent.PauseNavigation, parse("mets la navigation en pause"))
        assertEquals(OriaVoiceIntent.ResumeNavigation, parse("reprends navigation"))
        assertEquals(OriaVoiceIntent.StopNavigation, parse("annule la navigation"))
        assertEquals(OriaVoiceIntent.MuteAlerts, parse("désactive les alertes"))
        assertEquals(OriaVoiceIntent.MuteAlerts, parse("coupe le son"))
        assertEquals(OriaVoiceIntent.UnmuteAlerts, parse("réactive les alertes"))
    }

    @Test fun guideKeepsARequiredDestinationAndNeedsLaterConfirmation() {
        assertEquals(OriaVoiceIntent.GuideTo("gare de lyon"), parse("Guide-moi vers Gare de Lyon"))
        assertTrue(parse("guide-moi vers") is OriaVoiceIntent.Ambiguous)
        assertEquals(OriaVoiceIntent.ConfirmDestination, parse("oui confirme"))
        assertEquals(OriaVoiceIntent.SelectDestination(1), parse("deuxième résultat"))
    }

    @Test fun ambiguousRiskyWordsNeverMapToAnExecutableAction() {
        listOf("arrête", "stop", "pause", "reprends", "continue").forEach {
            assertTrue("$it must be ambiguous", parse(it) is OriaVoiceIntent.Ambiguous)
        }
    }

    @Test fun silenceAndUnknownSpeechAreNoOps() {
        assertTrue(parse("   ") is OriaVoiceIntent.Unknown)
        assertTrue(parse("commande sans rapport") is OriaVoiceIntent.Unknown)
    }

    @Test fun singleButtonPressIsDeferredThenReleased() {
        val buttons = EagleButtonSequencer(500)
        val (first, token) = buttons.press(1_000)
        assertEquals(EagleButtonAction.WAIT_FOR_SECOND_PRESS, first)
        assertEquals(EagleButtonAction.IGNORE, buttons.timeout(token, 1_499))
        assertEquals(EagleButtonAction.RUN_SINGLE_PRESS, buttons.timeout(token, 1_500))
        assertEquals(EagleButtonAction.IGNORE, buttons.timeout(token, 2_000))
    }

    @Test fun repeatedButtonPressStartsOneVoiceCommand() {
        val buttons = EagleButtonSequencer(500)
        buttons.press(1_000)
        assertEquals(EagleButtonAction.START_VOICE_COMMAND, buttons.press(1_250).first)
        val third = buttons.press(1_260)
        assertEquals(EagleButtonAction.WAIT_FOR_SECOND_PRESS, third.first)
        buttons.reset()
        assertEquals(EagleButtonAction.IGNORE, buttons.timeout(third.second, 2_000))
    }

    @Test fun lateAndDuplicateCallbacksCannotCompleteANewRequest() {
        val gate = TranscriptionRequestGate()
        assertTrue(gate.begin("first"))
        assertFalse(gate.begin("overlap"))
        assertTrue(gate.complete("first"))
        assertFalse(gate.complete("first"))
        assertTrue(gate.begin("second"))
        assertFalse(gate.complete("first"))
        assertEquals("second", gate.activeId)
        assertTrue(gate.complete("second"))
    }

    private fun parse(text: String): OriaVoiceIntent = OriaVoiceIntentParser.parse(text).intent
}
