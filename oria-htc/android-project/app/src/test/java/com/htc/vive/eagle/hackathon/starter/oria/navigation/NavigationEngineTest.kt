package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NavigationEngineTest {
    private val origin = GeoPoint(48.85660, 2.35220)
    private val turn = GeoPoint(48.85680, 2.35220)
    private val destination = GeocodedPlace("dest", "Destination test", GeoPoint(48.85680, 2.35260))

    private fun route(version: Long = 1, simulated: Boolean = false) = NavigationRoute(
        version, destination, listOf(origin, turn, destination.point), listOf(
            RouteManeuver("depart-$version", version, 0, "Avancez tout droit", turn),
            RouteManeuver("turn-$version", version, 1, "Tournez à droite", destination.point),
        ), 60.0, 50.0, simulated, if (simulated) "simulation" else "test",
    )

    private fun fix(point: GeoPoint, at: Long, generation: Long = 7, accuracy: Float = 3f) =
        LocationFix(generation, at, point, accuracy)

    @Test fun twoManeuversProgressAndSpeakWithRouteVersion() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        val first = engine.onLocation(fix(origin, 101)).single() as NavigationEvent.Speak
        assertEquals(1, first.speech.routeVersion)
        assertTrue(first.speech.text.contains("Avancez tout droit"))

        val second = engine.onLocation(fix(turn, 102)).single() as NavigationEvent.Speak
        assertEquals(1, engine.snapshot().maneuverIndex)
        assertTrue(second.speech.text.contains("Tournez à droite"))
        assertTrue(second.speech.expiresAtMs > second.speech.observedAtMs)
    }

    @Test fun gpsLossInvalidatesOldFixAndFreshFixRecovers() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        engine.onLocation(fix(origin, 110))
        val before = engine.snapshot().instructionVersion
        assertTrue(engine.gpsLost(120, "GPS perdu"))
        assertEquals(NavigationPhase.LIMITED, engine.snapshot().phase)
        assertTrue(engine.snapshot().instructionVersion > before)
        assertTrue(engine.onLocation(fix(turn, 115)).isEmpty())
        engine.onLocation(fix(turn, 121))
        assertEquals(NavigationPhase.ACTIVE, engine.snapshot().phase)
    }

    @Test fun pauseRequiresFreshLocationBeforeNewSpeech() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        engine.onLocation(fix(origin, 110))
        assertTrue(engine.pause(120))
        assertTrue(engine.onLocation(fix(turn, 121)).isEmpty())
        assertTrue(engine.resume(130))
        assertTrue(engine.onLocation(fix(turn, 129)).isEmpty())
        val resumed = engine.onLocation(fix(turn, 131))
        assertTrue(resumed.single() is NavigationEvent.Speak)
    }

    @Test fun dangerPreemptionResumesOnlyFromFreshUsefulLocation() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        engine.onLocation(fix(origin, 110))
        assertTrue(engine.audioInterrupted(120))
        assertTrue(engine.onLocation(fix(origin, 119)).isEmpty())
        val resumed = engine.onLocation(fix(origin, 121)).single() as NavigationEvent.Speak
        assertTrue(resumed.speech.text.contains("Avancez tout droit"))
    }

    @Test fun twoOffRouteSamplesRequestRecalculation() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        val far = GeoPoint(48.85850, 2.35500)
        assertTrue(engine.onLocation(fix(far, 110)).none { it is NavigationEvent.Recalculate })
        val event = engine.onLocation(fix(far, 120)).single() as NavigationEvent.Recalculate
        assertEquals(1, event.invalidatedRouteVersion)
        assertEquals("off_route", event.reason)
        assertEquals(NavigationPhase.RECALCULATING, engine.snapshot().phase)
    }

    @Test fun recalculationRejectsOldVersionAndAcceptsNewRoute() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        val far = GeoPoint(48.85850, 2.35500)
        engine.onLocation(fix(far, 110)); engine.onLocation(fix(far, 120))
        assertFalse(engine.replaceRoute(7, route(1), 130))
        assertFalse(engine.replaceRoute(8, route(2), 130))
        assertTrue(engine.replaceRoute(7, route(2), 130))
        assertEquals(2, engine.snapshot().routeVersion)
        assertTrue(engine.onLocation(fix(origin, 121)).isEmpty())
        assertTrue(engine.onLocation(fix(origin, 131)).single() is NavigationEvent.Speak)
    }

    @Test fun arrivalNeedsTwoFreshSamplesAndThenStopsSpeech() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        engine.onLocation(fix(destination.point, 110))
        val arrived = engine.onLocation(fix(destination.point, 120)).single() as NavigationEvent.Arrived
        assertEquals(destination, arrived.destination)
        assertTrue(arrived.speech.text.contains("Vous êtes arrivé"))
        assertEquals(1, arrived.speech.routeVersion)
        assertEquals(NavigationPhase.ARRIVED, engine.snapshot().phase)
        assertTrue(engine.onLocation(fix(origin, 130)).isEmpty())
    }

    @Test fun destinationChangeInvalidatesPreviousInstructionVersion() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        val old = (engine.onLocation(fix(origin, 110)).single() as NavigationEvent.Speak).speech
        val oldInstructionVersion = old.instructionVersion
        engine.start(8, route(2), 120)
        assertNotEquals(old.routeVersion, engine.snapshot().routeVersion)
        assertTrue(engine.snapshot().instructionVersion > oldInstructionVersion)
        assertTrue(engine.onLocation(fix(origin, 130, generation = 7)).isEmpty())
    }

    @Test fun impreciseLocationLimitsWithoutInventingProgress() {
        val engine = NavigationEngine(); engine.start(7, route(), 100)
        assertTrue(engine.onLocation(fix(destination.point, 110, accuracy = 120f)).isEmpty())
        assertEquals(NavigationPhase.LIMITED, engine.snapshot().phase)
        assertEquals(0, engine.snapshot().maneuverIndex)
    }

    @Test fun simulatedProviderCompletesDeterministicThreeManeuverRoute() {
        val simulated = SimulatedRouteProvider.routeFor(version = 4)
        val engine = NavigationEngine(); engine.start(7, simulated, 100, NavigationMode.SIMULATED)
        var at = 101L
        val events = mutableListOf<NavigationEvent>()
        (listOf(simulated.geometry.first()) + simulated.maneuvers.map { it.point } + simulated.destination.point)
            .forEach { point -> events += engine.onLocation(fix(point, at++)) }
        assertEquals(NavigationPhase.ARRIVED, engine.snapshot().phase)
        assertTrue(events.any { it is NavigationEvent.Arrived })
        assertTrue(events.filterIsInstance<NavigationEvent.Speak>().size >= 2)
    }

    @Test fun geocodingFailureRemainsAnExplicitFailure() = runBlocking {
        val unavailable = object : Geocoder {
            override suspend fun search(query: String, limit: Int) =
                Result.failure<List<GeocodedPlace>>(IllegalStateException("hors ligne"))
        }
        val result = unavailable.search("Paris")
        assertTrue(result.isFailure)
        assertEquals("hors ligne", result.exceptionOrNull()?.message)
    }
}
