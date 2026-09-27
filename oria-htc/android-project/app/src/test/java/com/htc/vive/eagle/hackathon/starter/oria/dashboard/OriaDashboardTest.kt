package com.htc.vive.eagle.hackathon.starter.oria.dashboard

import org.junit.Assert.*
import org.junit.Test

class OriaDashboardTest {
    @Test fun projectsActiveLimitedDisconnectedAndReplayStates() {
        assertEquals(DashboardOverallState.ACTIVE, project(running = true).overall)
        assertEquals(DashboardOverallState.LIMITED, project(running = true, limited = true).overall)
        assertEquals(DashboardOverallState.DISCONNECTED, project(connected = false).overall)
        assertEquals(DashboardOverallState.REPLAY, project(replaying = true).overall)
    }

    @Test fun failureDominatesLiveAndProducesExplicitText() {
        val snapshot = project(running = true, failed = true, status = "Décodeur indisponible")
        assertEquals(DashboardOverallState.FAILED, snapshot.overall)
        assertTrue(snapshot.headline.contains("Erreur"))
        assertTrue(snapshot.accessibleSummary.contains("Navigation"))
    }

    @Test fun navigationAndDangerRemainTextualNotColorOnly() {
        val snapshot = project(running = true, navigationState = "préemptée par danger",
            selected = "Véhicule devant")
        assertEquals("préemptée par danger", snapshot.navigation.state)
        assertEquals("Véhicule devant", snapshot.decision.selected)
        assertTrue(snapshot.headline.contains("Véhicule devant"))
        assertTrue(snapshot.accessibleSummary.contains("Véhicule devant"))
    }

    @Test fun projectionCopiesMutableListsAndBoxes() {
        val box = mutableListOf(.1f, .2f, .3f, .4f)
        val objects = mutableListOf(DashboardObject(8, "Piéton", 92, "centre", true, box))
        val snapshot = OriaDashboardProjector.project(input(objects = objects))
        box[0] = .9f
        objects.clear()
        assertEquals(1, snapshot.objects.size)
        assertEquals(.1f, snapshot.objects.single().box.first())
    }

    @Test fun publicationRateIsBoundedToFourHertzAndClockReversalIsRejected() {
        val limiter = DashboardRateLimiter(250)
        assertTrue(limiter.shouldPublish(1_000))
        assertFalse(limiter.shouldPublish(1_249))
        assertTrue(limiter.shouldPublish(1_250))
        assertFalse(limiter.shouldPublish(1_100, force = true))
        assertTrue(limiter.shouldPublish(1_251, force = true))
    }

    @Test fun publicationRateCanBeReducedUnderLoad() {
        val limiter = DashboardRateLimiter(250)
        assertTrue(limiter.shouldPublish(1_000, minimumIntervalOverrideMs = 1_000))
        assertFalse(limiter.shouldPublish(1_999, minimumIntervalOverrideMs = 1_000))
        assertTrue(limiter.shouldPublish(2_000, minimumIntervalOverrideMs = 1_000))
    }

    @Test fun percentileHandlesEmptyOddAndInterpolatedSamples() {
        assertEquals(0, percentileMillis(emptyList(), .5))
        assertEquals(20, percentileMillis(listOf(10, 20, 30), .5))
        assertEquals(17, percentileMillis(listOf(10, 20), .75))
    }

    @Test fun immutableProjectionCostIsMeasured() {
        val input = input(running = true, objects = List(8) { index ->
            DashboardObject(index.toLong(), "Objet $index", 80 + index, "centre", true,
                listOf(.1f, .2f, .3f, .4f))
        })
        repeat(1_000) { OriaDashboardProjector.project(input) }
        val iterations = 20_000
        val started = System.nanoTime()
        repeat(iterations) { OriaDashboardProjector.project(input.copy(sequence = it.toLong())) }
        val elapsed = System.nanoTime() - started
        val averageMicros = elapsed / iterations / 1_000.0
        println("dashboard_projection iterations=$iterations averageMicros=$averageMicros")
        assertTrue("Dashboard projection averaged $averageMicros µs", averageMicros < 1_000.0)
    }

    private fun project(connected: Boolean = true, running: Boolean = false,
                        limited: Boolean = false, failed: Boolean = false,
                        replaying: Boolean = false, status: String = "Prêt",
                        navigationState: String = "arrêtée", selected: String = "Aucun") =
        OriaDashboardProjector.project(input(connected, running, limited, failed, replaying,
            status, navigationState = navigationState, selected = selected))

    private fun input(connected: Boolean = true, running: Boolean = false,
                      limited: Boolean = false, failed: Boolean = false,
                      replaying: Boolean = false, status: String = "Prêt",
                      objects: List<DashboardObject> = emptyList(),
                      navigationState: String = "arrêtée", selected: String = "Aucun") = DashboardInput(
        1, 1_000, connected, running, limited, failed, replaying, status, "Aucune annonce", objects,
        OriaDashboardSnapshot.initial().decision.copy(selected = selected),
        OriaDashboardSnapshot.initial().navigation.copy(state = navigationState),
        OriaDashboardSnapshot.initial().metrics,
        OriaDashboardSnapshot.initial().health,
        "Aucune capture")
}
