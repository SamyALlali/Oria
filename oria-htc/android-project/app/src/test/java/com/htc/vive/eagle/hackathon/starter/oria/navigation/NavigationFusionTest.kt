package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NavigationFusionTest {
    private val origin = GeoPoint(48.8566, 2.3522)
    private val place = GeocodedPlace("test", "Lieu synthétique", GeoPoint(48.8575, 2.3522))
    private fun route(version: Long = 1) = NavigationRoute(version, place, listOf(origin, place.point), listOf(
        RouteManeuver("turn", version, 0, "Continuez", origin),
        RouteManeuver("end", version, 1, "Vous êtes arrivé", place.point, arrival = true)), 100.0, 80.0, false, "fixture")

    @Test fun firstArrivalFixNeverSpeaksArrival() {
        val engine = NavigationEngine(); engine.start(1, route(), 100)
        val first = engine.onLocation(LocationFix(1, 101, place.point, 3f))
        assertTrue(first.isEmpty())
        assertNotEquals(NavigationPhase.ARRIVED, engine.snapshot().phase)
        val second = engine.onLocation(LocationFix(1, 102, place.point, 3f)).single() as NavigationEvent.Arrived
        assertTrue(engine.canSpeak(second.speech, 102))
    }
    @Test fun impreciseArrivalIsNotConfirmed() {
        val engine = NavigationEngine(); engine.start(1, route(), 100)
        repeat(3) { engine.onLocation(LocationFix(1, 101L + it, place.point, 25f)) }
        assertNotEquals(NavigationPhase.ARRIVED, engine.snapshot().phase)
    }
    @Test fun proposalRetriesAfterFailureAndExpiresBeforePcm() {
        val route = route().copy(maneuvers = listOf(RouteManeuver("turn", 1, 0, "Continuez", place.point)))
        val engine = NavigationEngine(); engine.start(1, route, 100)
        val first = (engine.onLocation(LocationFix(1, 101, origin, 3f)).single() as NavigationEvent.Speak).speech
        assertTrue(engine.canSpeak(first, 101))
        assertFalse(engine.canSpeak(first, 9_000))
        engine.onSpeechResult(first, false)
        val retry = (engine.onLocation(LocationFix(1, 102, origin, 3f)).single() as NavigationEvent.Speak).speech
        assertNotEquals(first.id, retry.id)
        engine.onSpeechResult(retry, true)
        assertTrue(engine.onLocation(LocationFix(1, 103, origin, 3f)).isEmpty())
    }
    @Test fun pausedOrLostPositionCannotBeRepeated() {
        val route = route().copy(maneuvers = listOf(RouteManeuver("turn", 1, 0, "Continuez", place.point)))
        val engine = NavigationEngine(); engine.start(1, route, 100)
        val speech = (engine.onLocation(LocationFix(1, 101, origin, 3f)).single() as NavigationEvent.Speak).speech
        engine.onSpeechResult(speech, true)
        engine.pause(102)
        assertNull(engine.repeatFresh(103)); assertFalse(engine.canSpeak(speech, 103))
        engine.resume(104); assertNull(engine.repeatFresh(105))
        engine.onLocation(LocationFix(1, 106, origin, 3f)); engine.gpsLost(107, "GPS indisponible")
        assertNull(engine.repeatFresh(108))
    }
    @Test fun rateGateSpacesRequestsWithoutRealSleeping() = runBlocking {
        var time = 10_000L; val waits = mutableListOf<Long>()
        val gate = NavigationRequestRateGate({ time }, { waits += it; time += it })
        gate.awaitTurn(); gate.awaitTurn(); time += 400; gate.awaitTurn()
        assertEquals(listOf(1_000L, 600L), waits)
    }

    private class MemoryRepository : DestinationRepository {
        private val items = linkedMapOf<String, SavedDestination>()
        override fun list() = items.values.toList()
        override fun save(destination: SavedDestination) = destination.also { items[it.id] = it }
        override fun delete(id: String) = items.remove(id) != null
    }
    private class FakeLocation : LocationProvider {
        var generation = 0L; var stops = 0
        var callback: ((LocationFix) -> Unit)? = null
        var unavailable: ((String) -> Unit)? = null
        override fun start(generation: Long, onFix: (LocationFix) -> Unit, onUnavailable: (String) -> Unit): Result<Unit> {
            this.generation = generation; callback = onFix; unavailable = onUnavailable; return Result.success(Unit)
        }
        override fun stop() { stops++ } // Keep callback deliberately, to model an already posted old fix.
        fun emit(point: GeoPoint, at: Long, accuracy: Float = 3f) = callback?.invoke(LocationFix(generation, at, point, accuracy))
    }
    private class Harness(val place: GeocodedPlace, val routes: RouteProvider) : AutoCloseable {
        val time = AtomicLong(100)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val location = FakeLocation()
        val geocodes = AtomicInteger()
        val speeches = mutableListOf<NavigationSpeech>()
        val nav = OriaNavigationCoordinator(scope, speeches::add, {}, { _, fields ->
            check(fields.keys.none { it in setOf("query", "destination", "transcript", "latitude", "longitude") })
        }, time::get, MemoryRepository(), object : Geocoder {
            override suspend fun search(query: String, limit: Int): Result<List<GeocodedPlace>> {
                geocodes.incrementAndGet(); return Result.success(listOf(place))
            }
        }, location, SimulatedLocationProvider(), routes, SimulatedRouteProvider())
        suspend fun ready() {
            nav.setNetworkConsent(true); nav.search("Lieu synthétique", NavigationMode.REAL)
            await(NavigationPhase.CONFIRMATION); nav.confirmSelected(place.id)
        }
        suspend fun await(phase: NavigationPhase) = withTimeout(3_000) { nav.state.first { it.phase == phase } }
        override fun close() { nav.close(); scope.cancel() }
    }
    @Test fun realSearchRequiresVisibleConsentWhileDemoNeedsNone() = runBlocking {
        Harness(place, SimulatedRouteProvider()).use { h ->
            h.nav.search("Lieu synthétique", NavigationMode.REAL)
            assertEquals(0, h.geocodes.get())
            h.nav.search("Démo", NavigationMode.SIMULATED)
            h.await(NavigationPhase.CONFIRMATION)
            assertEquals(0, h.geocodes.get())
        }
    }
    @Test fun slowRouteKeepsOneRequestAcrossContinuousFixes() = runBlocking {
        val requested = CompletableDeferred<Unit>(); val response = CompletableDeferred<Result<NavigationRoute>>()
        val calls = AtomicInteger()
        Harness(place, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long): Result<NavigationRoute> {
                calls.incrementAndGet(); requested.complete(Unit); return response.await()
            }
        }).use { h ->
            h.ready(); h.location.emit(origin, h.time.incrementAndGet()); requested.await()
            repeat(10) { h.location.emit(origin, h.time.incrementAndGet()) }
            assertEquals(1, calls.get())
            response.complete(Result.success(route()))
            h.await(NavigationPhase.ACTIVE)
            assertEquals(1L, h.nav.state.value.routeVersion)
        }
    }
    @Test fun backgroundCancelsRouteAndLateResultCannotActivateIt() = runBlocking {
        val requested = CompletableDeferred<Unit>(); val response = CompletableDeferred<Result<NavigationRoute>>()
        Harness(place, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long) = withContext(NonCancellable) {
                requested.complete(Unit); response.await()
            }
        }).use { h ->
            h.ready(); h.location.emit(origin, h.time.incrementAndGet()); requested.await()
            h.nav.setForeground(false)
            assertEquals(NavigationPhase.PAUSED, h.nav.state.value.phase)
            response.complete(Result.success(route())); yield()
            assertEquals(NavigationPhase.PAUSED, h.nav.state.value.phase)
            assertTrue(h.location.stops > 0)
        }
    }
    @Test fun stopRejectsPostedOldFixAndInaccurateFixCannotRoute() = runBlocking {
        val calls = AtomicInteger()
        Harness(place, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long): Result<NavigationRoute> {
                calls.incrementAndGet(); return Result.success(route(version))
            }
        }).use { h ->
            h.ready(); h.location.emit(origin, h.time.incrementAndGet(), 100f)
            assertEquals(0, calls.get())
            h.time.addAndGet(10_000); h.location.emit(origin, 101)
            assertEquals(0, calls.get())
            h.nav.stop(); h.location.emit(origin, h.time.incrementAndGet())
            assertEquals(0, calls.get()); assertEquals(NavigationPhase.IDLE, h.nav.state.value.phase)
        }
    }
    @Test fun changingDestinationBeforePermissionReturnCannotConfirmAnotherPlace() = runBlocking {
        Harness(place, SimulatedRouteProvider()).use { h ->
            h.nav.search("Démo", NavigationMode.SIMULATED); h.await(NavigationPhase.CONFIRMATION)
            h.nav.confirmSelected("obsolete-place")
            assertEquals(NavigationPhase.CONFIRMATION, h.nav.state.value.phase)
        }
    }
    @Test fun silenceGpsInvalidatesInstructionsAndWorkerPermit() = runBlocking {
        Harness(place, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long) = Result.success(
                route(version).copy(maneuvers = listOf(RouteManeuver("target", version, 0, "Continuez", place.point))))
        }).use { h ->
            h.ready(); h.location.emit(origin, h.time.incrementAndGet()); h.await(NavigationPhase.ACTIVE)
            h.location.emit(origin, h.time.incrementAndGet())
            val speech = h.speeches.single(); assertTrue(h.nav.canSpeak(speech))
            h.time.addAndGet(6_001); h.nav.checkFreshness()
            assertFalse(h.nav.canSpeak(speech)); assertEquals(NavigationPhase.LIMITED, h.nav.state.value.phase)
            assertNull(h.nav.state.value.currentInstruction)
        }
    }
    @Test fun oldSubscriptionCannotInvalidateResumedRouteOrSpeech() = runBlocking {
        Harness(place, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long) = Result.success(
                route(version).copy(maneuvers = listOf(RouteManeuver("target", version, 0, "Continuez", place.point))))
        }).use { h ->
            h.ready(); h.location.emit(origin, h.time.incrementAndGet()); h.await(NavigationPhase.ACTIVE)
            h.location.emit(origin, h.time.incrementAndGet())
            val oldFix = h.location.callback!!
            val oldUnavailable = h.location.unavailable!!
            val oldGeneration = h.location.generation
            h.time.incrementAndGet(); h.nav.pause()
            h.time.incrementAndGet(); h.nav.resume()
            h.location.emit(origin, h.time.incrementAndGet())
            val speech = h.speeches.last()
            val resumed = h.nav.state.value
            assertTrue(h.nav.canSpeak(speech))
            oldUnavailable("Ancien abonnement indisponible")
            oldFix(LocationFix(oldGeneration, h.time.incrementAndGet(), place.point, 3f))
            assertEquals(resumed, h.nav.state.value)
            assertTrue(h.nav.canSpeak(speech))
        }
    }

}
