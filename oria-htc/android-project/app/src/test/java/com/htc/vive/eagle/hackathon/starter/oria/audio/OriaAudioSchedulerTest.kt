package com.htc.vive.eagle.hackathon.starter.oria.audio

import org.junit.Assert.*
import org.junit.Test

class OriaAudioSchedulerTest {
    private fun request(id: String, kind: OriaAudioKind, priority: OriaAudioPriority,
                        generation: Long = 1, at: Long = 100, expires: Long = 1_000) =
        OriaAudioRequest(id, generation, kind, priority, id, SpeechPan.CENTER, at, at, expires)

    @Test fun highDangerPreemptsNavigation() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        assertEquals("nav", scheduler.offer(request("nav", OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION), 100).dispatch?.id)
        val result = scheduler.offer(request("danger", OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_HIGH), 120)
        assertEquals("nav", result.cancel?.id)
        assertEquals("danger", result.dispatch?.id)
        assertEquals(OriaAudioDecisionReason.PREEMPTED, result.reason)
    }

    @Test fun escalationPreemptsLowerDanger() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("low", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_LOW), 100)
        val result = scheduler.offer(request("critical", OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_CRITICAL), 120)
        assertEquals("low", result.cancel?.id)
        assertEquals("critical", result.dispatch?.id)
    }

    @Test fun lowDangerDoesNotInterruptSpeechAndIsNotReplayedLate() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("command", OriaAudioKind.COMMAND_RESPONSE,
            OriaAudioPriority.DESCRIPTION), 100)
        val low = scheduler.offer(request("low", OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_LOW), 120)
        assertNull(low.dispatch)
        assertEquals(listOf("low"), low.dropped.map { it.id })
        assertNull(scheduler.finish("command", 1, 130).dispatch)
    }

    @Test fun staleNavigationIsNeverResumedAfterDanger() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("danger", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_HIGH,
            expires = 500), 100)
        scheduler.offer(request("nav", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION,
            at = 100, expires = 150), 110)
        val done = scheduler.finish("danger", 1, 200)
        assertNull(done.dispatch)
        assertEquals(listOf("nav"), done.dropped.map { it.id })
    }

    @Test fun newestNavigationReplacesOldQueuedManeuver() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("danger", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_LOW), 100)
        scheduler.offer(request("nav-old", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION), 110)
        scheduler.offer(request("nav-new", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION), 120)
        assertEquals(listOf("nav-new"), scheduler.snapshot().queued.map { it.id })
    }

    @Test fun routeChangeCancelsActiveNavigationAndDropsOldManeuvers() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("nav-active", OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION), 100)
        scheduler.offer(request("nav-queued", OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION), 110)
        val invalidated = scheduler.invalidate(OriaAudioKind.NAVIGATION, 120)
        assertEquals("nav-active", invalidated.cancel?.id)
        assertEquals(listOf("nav-queued"), invalidated.dropped.map { it.id })
        assertNull(scheduler.snapshot().inFlight)
        assertTrue(scheduler.snapshot().queued.isEmpty())
    }

    @Test fun resetRejectsOldGenerationAndClearsAllSpeech() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("active", OriaAudioKind.COMMAND_RESPONSE, OriaAudioPriority.DESCRIPTION), 100)
        val reset = scheduler.reset(2)
        assertEquals("active", reset.cancel?.id)
        assertEquals(OriaAudioDecisionReason.OLD_GENERATION,
            scheduler.offer(request("old", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_CRITICAL), 120).reason)
        assertNull(scheduler.snapshot().inFlight)
    }

    @Test fun silentModeCancelsOutputWithoutChangingGeneration() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(3)
        scheduler.offer(request("active", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION,
            generation = 3), 100)
        val muted = scheduler.setMuted(true)
        assertEquals("active", muted.cancel?.id)
        assertTrue(scheduler.snapshot().muted)
        assertEquals(3, scheduler.snapshot().generation)
        assertEquals(OriaAudioDecisionReason.MUTED,
            scheduler.offer(request("danger", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_CRITICAL,
                generation = 3), 120).reason)
    }

    @Test fun routeLossCancelsAndDropsQueue() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("active", OriaAudioKind.COMMAND_RESPONSE, OriaAudioPriority.DESCRIPTION), 100)
        scheduler.offer(request("queued", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION), 110)
        val lost = scheduler.routeLost()
        assertEquals("active", lost.cancel?.id)
        assertEquals(listOf("queued"), lost.dropped.map { it.id })
        assertEquals(OriaAudioDecisionReason.ROUTE_LOST, lost.reason)
    }

    @Test fun schedulerRecoversWithFreshSpeechAfterRouteLoss() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("before-loss", OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION), 100)
        scheduler.routeLost()
        val recovered = scheduler.offer(request("after-loss", OriaAudioKind.COMMAND_RESPONSE,
            OriaAudioPriority.DESCRIPTION), 130)
        assertEquals("after-loss", recovered.dispatch?.id)
    }

    @Test fun lateCompletionFromOldGenerationCannotAdvanceNewQueue() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        scheduler.offer(request("old", OriaAudioKind.NAVIGATION, OriaAudioPriority.NAVIGATION), 100)
        scheduler.reset(2)
        scheduler.offer(request("new", OriaAudioKind.COMMAND_RESPONSE, OriaAudioPriority.DESCRIPTION,
            generation = 2), 120)
        val late = scheduler.finish("old", 1, 130)
        assertEquals(OriaAudioDecisionReason.OLD_GENERATION, late.reason)
        assertEquals("new", scheduler.snapshot().inFlight?.id)
    }

    @Test fun duplicateUuidIsRejected() {
        val scheduler = OriaAudioScheduler(); scheduler.reset(1)
        val item = request("same", OriaAudioKind.DANGER, OriaAudioPriority.DANGER_LOW)
        scheduler.offer(item, 100)
        assertEquals(OriaAudioDecisionReason.DUPLICATE, scheduler.offer(item, 110).reason)
    }

    @Test fun patternsAreIndependentAndMonotonicByUrgency() {
        val levels = listOf(DangerSoundLevel.LOW, DangerSoundLevel.MEDIUM, DangerSoundLevel.HIGH)
            .map { DangerSoundPattern.forLevel(it, SpeechPan.LEFT) }
        assertTrue(levels.zipWithNext().all { (a, b) -> b.frequencyHz > a.frequencyHz && b.cycleMs < a.cycleMs })
        val critical = DangerSoundPattern.forLevel(DangerSoundLevel.CRITICAL, SpeechPan.RIGHT)
        assertEquals(critical.cycleMs, critical.activeMs)
        assertFalse(DangerSoundPattern.silent().audible)
    }

    @Test fun dangerToneUsesCheckedSeventyThirtyDirectionBalance() {
        val left = DangerToneRenderer.render(
            DangerSoundPattern.forLevel(DangerSoundLevel.CRITICAL, SpeechPan.LEFT),
            sampleRate = 8_000, frames = 80, startedAtMs = 0, initialPhase = 0.0).pcm16Stereo
        val right = DangerToneRenderer.render(
            DangerSoundPattern.forLevel(DangerSoundLevel.CRITICAL, SpeechPan.RIGHT),
            sampleRate = 8_000, frames = 80, startedAtMs = 0, initialPhase = 0.0).pcm16Stereo
        fun peak(bytes: ByteArray, channelOffset: Int): Int = (channelOffset until bytes.size step 4)
            .maxOf { index ->
                val value = ((bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xff)).toShort()
                kotlin.math.abs(value.toInt())
            }
        assertTrue(peak(left, 0) > peak(left, 2) * 2)
        assertTrue(peak(right, 2) > peak(right, 0) * 2)
    }

    @Test fun intermittentToneHasSilentWindowAndCriticalDoesNot() {
        val low = DangerToneRenderer.render(
            DangerSoundPattern.forLevel(DangerSoundLevel.LOW, SpeechPan.CENTER),
            sampleRate = 8_000, frames = 80, startedAtMs = 200, initialPhase = 0.0).pcm16Stereo
        val critical = DangerToneRenderer.render(
            DangerSoundPattern.forLevel(DangerSoundLevel.CRITICAL, SpeechPan.CENTER),
            sampleRate = 8_000, frames = 80, startedAtMs = 200, initialPhase = 0.0).pcm16Stereo
        assertTrue(low.all { it == 0.toByte() })
        assertTrue(critical.any { it != 0.toByte() })
    }
}
