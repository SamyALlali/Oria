package com.htc.vive.eagle.hackathon.starter.oria.core

import org.junit.Assert.*
import org.junit.Test

/** Virtual clocks only: these are deterministic invariants, not a phone endurance measurement. */
class RgbLongSessionTest {
    private val day = 24 * 60 * 60 * 1000L
    private fun person(left: Float = .4f) = Detection(0, .95f, Box(left, .3f, left + .15f, .9f))
    private fun frame(id: Long, at: Long, vararg detections: Detection, session: Long = 1) =
        DetectionFrame(session, id, at, detections.toList())

    private fun engine(mode: RgbTrackingMode, start: Long = 0, config: RgbAlertConfig = RgbAlertConfig()) =
        RgbAlertEngine(config.copy(trackingMode = mode)).also { it.start(1, start) }

    private fun alert(e: RgbAlertEngine, at: Long = 0): VoiceAlert {
        e.evaluate(frame(1, at, person()), at)
        return requireNotNull(e.evaluate(frame(2, at + 250, person()), at + 250).eligibleAlert)
    }

    private fun registrySize(e: RgbAlertEngine, field: String): Int {
        val declared = e.javaClass.getDeclaredField(field).also { it.isAccessible = true }
        return (declared.get(e) as Map<*, *>).size
    }

    @Test fun twentyFourHoursOfContinuousObservationsKeepClocksCooldownAndMemoryBounded() {
        for (mode in RgbTrackingMode.entries) {
            // Start beyond signed Int milliseconds and use frame IDs beyond signed Int too.
            val origin = Int.MAX_VALUE.toLong() + day
            val firstFrameId = Int.MAX_VALUE.toLong() + 10
            val e = engine(mode, origin)
            var lastConfirmed: Long? = null
            var lastAlertId = 0L
            var result: RgbEvaluation? = null
            val samples = day / 333
            for (index in 0..samples) {
                val now = origin + index * 333
                result = e.evaluate(frame(firstFrameId + index, now, person()), now)
                assertEquals(RgbFrameStatus.ACCEPTED, result.frameStatus)
                assertEquals(1, result.tracks.size)
                assertEquals(1L, result.tracks.single().id)
                assertEquals(now, result.tracks.single().observedAtMs)
                result.eligibleAlert?.let { next ->
                    assertTrue(next.id > lastAlertId)
                    lastConfirmed?.let { assertTrue(now - it >= e.config.repeatIntervalMs) }
                    val ticket = requireNotNull(e.onSubmitted(next, now))
                    assertTrue(e.onConfirmed(ticket, now + 1))
                    lastConfirmed = now + 1
                    lastAlertId = next.id
                }
                if (index % 10_000L == 0L) {
                    assertTrue(registrySize(e, "voiceMemories") <= 1)
                    assertTrue(registrySize(e, "tracks") <= 1)
                }
            }
            assertTrue(lastAlertId > 10_000)
            assertEquals(samples + 1, result!!.tracks.single().confirmationSamples.toLong())
            assertNull(e.current(origin + day + e.config.maxObservationAgeMs + 1).selected)
            assertTrue(e.current(origin + day + e.config.voiceMemoryRetentionMs + 1).tracks.isEmpty())
            assertEquals(0, registrySize(e, "voiceMemories"))
        }
    }

    @Test fun manyDistinctConfirmedObjectsCannotGrowVoiceOrTrackRegistriesBeyondTheirBounds() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode, config = RgbAlertConfig(confirmationSamples = 1,
                maximumVoiceMemories = 3, maximumTracks = 4, voiceMemoryRetentionMs = day))
            repeat(500) { index ->
                val now = index * 1500L
                val result = e.evaluate(frame(index + 1L, now, person()), now)
                val next = requireNotNull(result.eligibleAlert)
                assertTrue(e.onConfirmed(requireNotNull(e.onSubmitted(next, now)), now + 1))
                assertTrue(registrySize(e, "tracks") <= 4)
                assertTrue(registrySize(e, "voiceMemories") <= 3)
            }
            assertEquals(3, registrySize(e, "voiceMemories"))
        }
    }

    @Test fun oldSessionCallbackCannotAdvanceNewSessionClockOrConfirmItsSpeech() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode)
            val previous = alert(e)
            val oldTicket = requireNotNull(e.onSubmitted(previous, 250))
            e.stop()
            e.resetAudioAfterVerifiedReset()
            e.start(2, day)
            e.evaluate(frame(1, day, person(), session = 2), day)
            assertFalse(e.onConfirmed(oldTicket, day + 2000))
            val fresh = e.evaluate(frame(2, day + 250, person(), session = 2), day + 250)
            assertEquals(RgbFrameStatus.ACCEPTED, fresh.frameStatus)
            assertNotNull(fresh.eligibleAlert)
        }
    }

    @Test fun rejectedOldAlertCannotAdvanceNewSessionClock() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode)
            val previous = alert(e)
            e.stop()
            e.start(2, day)
            e.evaluate(frame(1, day, person(), session = 2), day)
            assertNull(e.onSubmitted(previous, day + 2000))
            assertEquals(RgbFrameStatus.ACCEPTED,
                e.evaluate(frame(2, day + 250, person(), session = 2), day + 250).frameStatus)
        }
    }

    @Test fun reversedClockCannotMakeExpiredObservationSelectedAgain() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode)
            alert(e)
            assertNull(e.current(800).selected) // Still within track retention, outside freshness.
            val reversedSnapshot = e.current(500)
            assertEquals(RgbFrameStatus.CLOCK_REVERSED, reversedSnapshot.frameStatus)
            assertNull(reversedSnapshot.selected)
            assertNull(reversedSnapshot.eligibleAlert)
            val reversedFrame = e.evaluate(frame(3, 500, person()), 500)
            assertEquals(RgbFrameStatus.CLOCK_REVERSED, reversedFrame.frameStatus)
            assertNull(reversedFrame.selected)
            assertNull(reversedFrame.eligibleAlert)
            val wrongSessionAndClock = e.evaluate(frame(3, 500, person(), session = 99), 500)
            assertEquals(RgbFrameStatus.WRONG_SESSION, wrongSessionAndClock.frameStatus)
            assertNull(wrongSessionAndClock.selected)
            assertNull(wrongSessionAndClock.eligibleAlert)
        }
    }

    @Test fun lateRejectedFrameDoesNotCancelLatestValidAnnouncementIntent() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode)
            val current = alert(e)
            val wrongSession = e.evaluate(frame(90, 250, person(.1f), session = 99), 300)
            assertEquals(RgbFrameStatus.WRONG_SESSION, wrongSession.frameStatus)
            assertNull(wrongSession.eligibleAlert)
            val duplicate = e.evaluate(frame(1, 0, person(.1f)), 300)
            assertEquals(RgbFrameStatus.OUT_OF_ORDER, duplicate.frameStatus)
            assertNull(duplicate.eligibleAlert)
            val ticket = requireNotNull(e.onSubmitted(current, 300))
            assertTrue(e.onConfirmed(ticket, 301))
            assertNull(e.onSubmitted(current, 302))
        }
    }

    @Test fun delayedInferenceAndUnknownAudioStayInvalidThroughReconnectionAfterOneDay() {
        for (mode in RgbTrackingMode.entries) {
            val e = engine(mode)
            val previous = alert(e)
            val ticket = requireNotNull(e.onSubmitted(previous, 250))
            assertTrue(e.onAmbiguous(ticket, 500))
            e.stop()
            e.start(2, day)
            assertFalse(e.onConfirmed(ticket, day))
            assertEquals(RgbFrameStatus.WRONG_SESSION, e.evaluate(frame(9, 500, person()), day).frameStatus)
            assertEquals(RgbFrameStatus.STALE,
                e.evaluate(frame(1, day - 1, person(), session = 2), day).frameStatus)
            e.evaluate(frame(1, day, person(), session = 2), day)
            val fresh = e.evaluate(frame(2, day + 250, person(), session = 2), day + 250)
            assertNull(fresh.eligibleAlert)
            assertEquals(RgbSuppressionReason.AUDIO_UNKNOWN, fresh.suppressionReason)
            e.resetAudioAfterVerifiedReset()
            val restored = e.evaluate(frame(3, day + 500, person(), session = 2), day + 500)
            assertNotNull(restored.eligibleAlert)
        }
    }

    @Test fun crossingAndOcclusionAtLargeClockDoNotReuseOldConfirmation() {
        val origin = Int.MAX_VALUE.toLong() + day
        val e = engine(RgbTrackingMode.STABLE_RGB_V2, origin)
        val wide = person(.2f).copy(box = Box(.2f, .3f, .6f, .9f))
        e.evaluate(frame(1, origin, wide), origin)
        val ambiguous = e.evaluate(frame(2, origin + 250,
            person(.15f).copy(box = Box(.15f, .3f, .4f, .9f)),
            person(.45f).copy(box = Box(.45f, .3f, .7f, .9f))), origin + 250)
        assertTrue(ambiguous.tracks.all { it.associationStatus == RgbAssociationStatus.AMBIGUOUS_NEW })
        assertTrue(ambiguous.tracks.none { it.confirmed })
        assertNull(ambiguous.eligibleAlert)
        e.evaluate(frame(3, origin + 500), origin + 500)
        val back = e.evaluate(frame(4, origin + 750, person(.15f)), origin + 750)
        assertTrue(back.tracks.filter { it.visibleInLatestFrame }.all { it.confirmationSamples == 1 })
        assertNull(back.eligibleAlert)
    }
}
