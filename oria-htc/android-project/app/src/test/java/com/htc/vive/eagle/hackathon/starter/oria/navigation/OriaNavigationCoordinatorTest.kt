package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class OriaNavigationCoordinatorTest {
    private class MemoryRepository : DestinationRepository {
        private val entries = linkedMapOf<String, SavedDestination>()
        override fun list() = entries.values.toList()
        override fun save(destination: SavedDestination) = DestinationNormalization.normalized(destination)
            .also { entries[it.id] = it }
        override fun delete(id: String) = entries.remove(id) != null
    }

    private class FakeLocation : LocationProvider {
        private var generation = 0L
        private var fix: ((LocationFix) -> Unit)? = null
        private var unavailable: ((String) -> Unit)? = null
        override fun start(generation: Long, onFix: (LocationFix) -> Unit,
                           onUnavailable: (String) -> Unit): Result<Unit> {
            this.generation = generation; fix = onFix; unavailable = onUnavailable
            return Result.success(Unit)
        }
        fun emit(point: GeoPoint, at: Long) = fix?.invoke(LocationFix(generation, at, point, 3f))
        fun lose(reason: String) = unavailable?.invoke(reason)
        override fun stop() { fix = null; unavailable = null }
    }

    private suspend fun NavigationUiState.awaitPhase(coordinator: OriaNavigationCoordinator,
                                                       phase: NavigationPhase): NavigationUiState =
        withTimeout(2_000) { coordinator.state.first { it.phase == phase } }

    private fun coordinator(scope: CoroutineScope, clock: () -> Long, geocoder: Geocoder,
                            location: LocationProvider, routes: RouteProvider,
                            speeches: MutableList<NavigationSpeech> = mutableListOf()) =
        OriaNavigationCoordinator(scope, speeches::add, {}, { _, _ -> }, clock,
            MemoryRepository(), geocoder, location, SimulatedLocationProvider(), routes, SimulatedRouteProvider())

    @Test fun geocodingErrorIsVisibleAndDoesNotStartLocation() = runBlocking {
        var at = 100L
        val location = FakeLocation()
        val navigation = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { ++at },
            object : Geocoder { override suspend fun search(query: String, limit: Int) =
                Result.failure<List<GeocodedPlace>>(IllegalStateException("service indisponible")) },
            location, SimulatedRouteProvider())
        navigation.search("adresse test", NavigationMode.REAL)
        val failed = navigation.state.value.awaitPhase(navigation, NavigationPhase.FAILED)
        assertTrue(failed.status.contains("service indisponible"))
        navigation.close()
    }

    @Test fun routingErrorAfterConfirmationIsVisible() = runBlocking {
        var at = 100L
        val point = GeoPoint(48.8566, 2.3522)
        val place = GeocodedPlace("p", "Paris", GeoPoint(48.857, 2.353))
        val location = FakeLocation()
        val navigation = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { ++at },
            object : Geocoder { override suspend fun search(query: String, limit: Int) = Result.success(listOf(place)) },
            location, object : RouteProvider { override suspend fun route(origin: GeoPoint,
                destination: GeocodedPlace, version: Long) = Result.failure<NavigationRoute>(
                    IllegalStateException("réseau absent")) })
        navigation.search("Paris", NavigationMode.REAL)
        navigation.state.value.awaitPhase(navigation, NavigationPhase.CONFIRMATION)
        navigation.confirmSelected(); location.emit(point, ++at)
        val failed = navigation.state.value.awaitPhase(navigation, NavigationPhase.FAILED)
        assertTrue(failed.status.contains("réseau absent"))
        navigation.close()
    }

    @Test fun deterministicSimulationRunsToArrivalAndEmitsInstructions() = runBlocking {
        var at = 100L
        val speeches = mutableListOf<NavigationSpeech>()
        val location = FakeLocation()
        val navigation = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { ++at },
            object : Geocoder { override suspend fun search(query: String, limit: Int) =
                Result.success(emptyList<GeocodedPlace>()) },
            location, SimulatedRouteProvider(), speeches)
        navigation.search("Démonstration", NavigationMode.SIMULATED)
        navigation.state.value.awaitPhase(navigation, NavigationPhase.CONFIRMATION)
        navigation.confirmSelected()
        navigation.state.value.awaitPhase(navigation, NavigationPhase.ACTIVE)
        repeat(8) {
            if (navigation.state.value.phase != NavigationPhase.ARRIVED) navigation.advanceSimulation()
            yield()
        }
        navigation.state.value.awaitPhase(navigation, NavigationPhase.ARRIVED)
        assertTrue(speeches.size >= 2)
        assertTrue(speeches.all { it.routeVersion == navigation.state.value.routeVersion })
        navigation.close()
    }

    @Test fun savedDestinationCanBeCreatedUpdatedAndDeleted() {
        var at = 100L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val navigation = coordinator(scope, { ++at }, object : Geocoder {
            override suspend fun search(query: String, limit: Int) = Result.success(emptyList<GeocodedPlace>())
        }, FakeLocation(), SimulatedRouteProvider())
        val saved = navigation.saveDestination("  Maison  ", "  1   rue Test ").getOrThrow()
        assertEquals("Maison", saved.name); assertEquals("1 rue Test", saved.address)
        navigation.saveDestination("Maison", "2 rue Test", saved.id).getOrThrow()
        assertEquals("2 rue Test", navigation.state.value.savedDestinations.single().address)
        assertTrue(navigation.deleteDestination(saved.id))
        assertTrue(navigation.state.value.savedDestinations.isEmpty())
        navigation.close(); scope.cancel()
    }

    @Test fun lateRouteFromPreviousDestinationCannotReplaceCurrentRoute() = runBlocking {
        var at = 100L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val location = FakeLocation()
        val placeA = GeocodedPlace("a", "Adresse A", GeoPoint(48.8570, 2.3530))
        val placeB = GeocodedPlace("b", "Adresse B", GeoPoint(48.8580, 2.3540))
        val firstResult = CompletableDeferred<Result<NavigationRoute>>()
        val firstStarted = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        fun routeFor(place: GeocodedPlace, version: Long) = NavigationRoute(version, place,
            listOf(GeoPoint(48.8566, 2.3522), place.point),
            listOf(RouteManeuver("m-$version", version, 0, "Continuez", place.point)),
            100.0, 80.0, false, "test")
        val navigation = coordinator(scope, { ++at }, object : Geocoder {
            override suspend fun search(query: String, limit: Int) = Result.success(
                listOf(if (query.endsWith("A")) placeA else placeB))
        }, location, object : RouteProvider {
            override suspend fun route(origin: GeoPoint, destination: GeocodedPlace,
                                       version: Long): Result<NavigationRoute> {
                val call = calls.incrementAndGet()
                return if (call == 1) withContext(NonCancellable) {
                    firstStarted.complete(Unit); firstResult.await()
                }
                    else Result.success(routeFor(destination, version))
            }
        })

        navigation.search("Adresse A", NavigationMode.REAL)
        navigation.state.value.awaitPhase(navigation, NavigationPhase.CONFIRMATION)
        navigation.confirmSelected(); location.emit(GeoPoint(48.8566, 2.3522), ++at)
        navigation.state.value.awaitPhase(navigation, NavigationPhase.CALCULATING)
        withTimeout(2_000) { firstStarted.await() }

        navigation.search("Adresse B", NavigationMode.REAL)
        navigation.state.value.awaitPhase(navigation, NavigationPhase.CONFIRMATION)
        navigation.confirmSelected(); location.emit(GeoPoint(48.8566, 2.3522), ++at)
        val active = navigation.state.value.awaitPhase(navigation, NavigationPhase.ACTIVE)
        assertEquals("b", active.selected?.id)
        val acceptedVersion = active.routeVersion

        firstResult.complete(Result.success(routeFor(placeA, 1)))
        delay(30)
        assertEquals("b", navigation.state.value.selected?.id)
        assertEquals(acceptedVersion, navigation.state.value.routeVersion)
        navigation.close(); scope.cancel()
    }
}
