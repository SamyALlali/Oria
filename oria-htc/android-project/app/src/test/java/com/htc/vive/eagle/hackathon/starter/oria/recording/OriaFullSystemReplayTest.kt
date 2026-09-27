package com.htc.vive.eagle.hackathon.starter.oria.recording

import com.htc.vive.eagle.hackathon.starter.oria.core.Box
import com.htc.vive.eagle.hackathon.starter.oria.core.Detection
import com.htc.vive.eagle.hackathon.starter.oria.core.DetectionFrame
import com.htc.vive.eagle.hackathon.starter.oria.core.OriaDepthRegion
import com.htc.vive.eagle.hackathon.starter.oria.core.OriaDepthTrend
import com.htc.vive.eagle.hackathon.starter.oria.core.OriaDistanceEvidence
import com.htc.vive.eagle.hackathon.starter.oria.navigation.GeoPoint
import com.htc.vive.eagle.hackathon.starter.oria.navigation.GeocodedPlace
import com.htc.vive.eagle.hackathon.starter.oria.navigation.LocationFix
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationRoute
import com.htc.vive.eagle.hackathon.starter.oria.navigation.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriaFullSystemReplayTest {
    private val origin = GeoPoint(48.85660, 2.35220)
    private val turn = GeoPoint(48.85680, 2.35220)
    private val destination = GeocodedPlace("private-destination", "Adresse privée à omettre",
        GeoPoint(48.85680, 2.35260))

    private fun route() = NavigationRoute(1, destination, listOf(origin, turn, destination.point), listOf(
        RouteManeuver("straight", 1, 0, "Avancez tout droit", turn),
        RouteManeuver("right", 1, 1, "Tournez à droite", destination.point),
    ), 60.0, 50.0, true, "fixture")

    private fun frame(id: Long, at: Long, confidence: Float = .95f) = DetectionFrame(99, id, at, listOf(
        Detection(0, confidence, Box(.40f, .25f, .62f, .92f))))

    private fun depth() = OriaDistanceEvidence(2f, .82f, .9f, 0, OriaDepthTrend.APPROACHING,
        OriaDepthRegion.DETECTION_LOWER_CENTER, 24)

    private fun actions() = listOf(
        OriaReplayAction.StartNavigation(0, route()),
        OriaReplayAction.Location(10, LocationFix(77, 10, origin, 3f)),
        OriaReplayAction.Frame(100, frame(1, 100), mapOf(1L to depth())),
        OriaReplayAction.Frame(200, frame(2, 200), mapOf(1L to depth())),
        OriaReplayAction.FinishAudio(250),
        OriaReplayAction.Seek(1_000),
        OriaReplayAction.Frame(1_010, frame(1, 1_010), mapOf(1L to depth())),
        OriaReplayAction.Frame(1_110, frame(2, 1_110), mapOf(1L to depth())),
    )

    @Test fun identicalSessionReplaysTwiceFieldForFieldAndBitForBit() {
        val first = OriaFullSystemReplay(approachEnabled = true).replay(actions())
        val second = OriaFullSystemReplay(approachEnabled = true).replay(actions())
        assertEquals(first.records, second.records)
        assertEquals(first.behavioralSha256, second.behavioralSha256)
        assertEquals(64, first.behavioralSha256.length)
        assertTrue(first.records.any { it.contains("tracks=") && it.contains("candidates=") })
        assertTrue(first.records.any { it.contains("depth=") && it.contains("stabilization=") })
        assertTrue(first.records.any { it.contains("audio=") })
        assertTrue(first.records.any { it.startsWith("navigation-start|") })
    }

    @Test fun seekInvalidatesEveryStateOwnerBeforeContinuing() {
        val result = OriaFullSystemReplay().replay(actions())
        val seek = result.records.single { it.startsWith("seek|") }
        assertTrue(seek.contains("rgb=RESET"))
        assertTrue(seek.contains("memory=RESET"))
        assertTrue(seek.contains("decision=RESET"))
        assertTrue(seek.contains("audio=RESET"))
        assertTrue(seek.contains("navigation=RESET"))
        assertTrue(result.records.last().contains("|2|2|ACCEPTED|"))
    }

    @Test fun aBehavioralInputChangeChangesTheFingerprint() {
        val reference = OriaFullSystemReplay().replay(actions())
        val changed = actions().toMutableList().apply {
            this[3] = OriaReplayAction.Frame(200, frame(2, 200, confidence = .40f), mapOf(1L to depth()))
        }
        val candidate = OriaFullSystemReplay().replay(changed)
        assertNotEquals(reference.records, candidate.records)
        assertNotEquals(reference.behavioralSha256, candidate.behavioralSha256)
    }

    @Test fun threeConsecutiveDemoScenariosStartFromACompleteReset() {
        val runs = (1..3).map { OriaFullSystemReplay(approachEnabled = true).replay(actions()) }
        assertEquals(3, runs.size)
        assertEquals(1, runs.map { it.behavioralSha256 }.distinct().size)
        runs.forEach { run ->
            assertTrue(run.records.first().startsWith("navigation-start|0|1|"))
            assertTrue(run.records.any { it.startsWith("seek|") && it.contains("navigation=RESET") })
            assertTrue(run.records.last().contains("|2|2|ACCEPTED|"))
        }
    }
}
