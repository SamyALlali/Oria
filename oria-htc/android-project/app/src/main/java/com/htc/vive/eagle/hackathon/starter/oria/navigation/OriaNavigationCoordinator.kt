package com.htc.vive.eagle.hackathon.starter.oria.navigation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.Closeable

data class NavigationUiState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val mode: NavigationMode = NavigationMode.REAL,
    val query: String = "",
    val suggestions: List<GeocodedPlace> = emptyList(),
    val selected: GeocodedPlace? = null,
    val savedDestinations: List<SavedDestination> = emptyList(),
    val currentInstruction: String? = null,
    val remainingMeters: Double? = null,
    val routeVersion: Long = 0,
    val status: String = "Navigation arrêtée",
    val attribution: String? = null,
    val simulatedStepAvailable: Boolean = false,
)

/** Android orchestration around the pure engine. Every async result is guarded by an operation token. */
class OriaNavigationCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val onSpeech: (NavigationSpeech) -> Unit,
    private val onInstructionsInvalidated: (String) -> Unit,
    private val onTrace: (String, Map<String, Any>) -> Unit,
    private val clock: () -> Long,
    private val repository: DestinationRepository,
    private val geocoder: Geocoder,
    private val realLocation: LocationProvider,
    private val simulatedLocation: SimulatedLocationProvider,
    private val realRoutes: RouteProvider,
    private val simulatedRoutes: RouteProvider,
) : Closeable {
    constructor(context: Context, scope: CoroutineScope, onSpeech: (NavigationSpeech) -> Unit,
                onInstructionsInvalidated: (String) -> Unit,
                onTrace: (String, Map<String, Any>) -> Unit,
                clock: () -> Long = SystemClock::elapsedRealtime) : this(
        scope, onSpeech, onInstructionsInvalidated, onTrace, clock,
        SharedPreferencesDestinationRepository(context), AndroidPlatformGeocoder(context),
        AndroidLocationProvider(context), SimulatedLocationProvider(),
        OsrmFootRouteProvider(), SimulatedRouteProvider())

    private val speechScheduler = object : NavigationSpeechScheduler {
        override fun offer(speech: NavigationSpeech) = onSpeech(speech)
        override fun invalidate(reason: String) = onInstructionsInvalidated(reason)
    }

    private val engine = NavigationEngine()
    private val _state = MutableStateFlow(NavigationUiState(savedDestinations = repository.list()))
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()
    private var navigationGeneration = 0L
    private var routeVersion = 0L
    private var operationToken = 0L
    private var operation: Job? = null
    private var pendingPlace: GeocodedPlace? = null
    private var currentProvider: LocationProvider? = null
    private var simulatedPoints = emptyList<GeoPoint>()
    private var simulatedIndex = 0
    private var closed = false

    fun hasLocationPermission(context: Context): Boolean = AndroidLocationProvider.hasPermission(context)

    fun updateQuery(value: String) { _state.update { it.copy(query = value) } }

    fun search(query: String, mode: NavigationMode) {
        if (closed) return
        if (engine.snapshot().phase != NavigationPhase.IDLE) stop("Nouvelle recherche de destination")
        invalidateOperation("Nouvelle recherche")
        val normalized = DestinationNormalization.text(query)
        if (normalized.length < 3) {
            _state.update { it.copy(phase = NavigationPhase.FAILED, status = "Saisissez au moins trois caractères") }
            return
        }
        val token = operationToken
        _state.update { it.copy(phase = NavigationPhase.SEARCHING, mode = mode, query = normalized,
            suggestions = emptyList(), selected = null, status = if (mode == NavigationMode.SIMULATED)
                "Préparation du trajet simulé" else "Recherche de l’adresse…") }
        operation = scope.launch(Dispatchers.IO) {
            val result = if (mode == NavigationMode.SIMULATED) Result.success(listOf(
                GeocodedPlace("sim-${DestinationNormalization.searchKey(normalized)}", normalized,
                    GeoPoint(48.85720, 2.35300)))) else geocoder.search(normalized)
            scope.launch {
                if (closed || token != operationToken) return@launch
                result.fold(onSuccess = { places ->
                    _state.update { it.copy(phase = if (places.isEmpty()) NavigationPhase.FAILED else NavigationPhase.CONFIRMATION,
                        suggestions = places, selected = places.singleOrNull(),
                        status = if (places.isEmpty()) "Aucune adresse trouvée" else "Confirmez la destination") }
                    trace("navigation_geocode", mapOf("mode" to mode.name, "count" to places.size, "query" to normalized))
                }, onFailure = { error -> fail("Géocodage : ${error.message}", token) })
            }
        }
    }

    fun select(place: GeocodedPlace) {
        if (place !in _state.value.suggestions) return
        _state.update { it.copy(selected = place, phase = NavigationPhase.CONFIRMATION,
            status = "Destination sélectionnée : ${place.label}") }
    }

    fun confirmSelected() {
        val place = _state.value.selected ?: return
        begin(place, _state.value.mode)
    }

    fun startSaved(destination: SavedDestination, mode: NavigationMode) {
        updateQuery(destination.address)
        search(destination.address, mode)
    }

    fun saveDestination(name: String, address: String, id: String? = null): Result<SavedDestination> = runCatching {
        val normalizedName = DestinationNormalization.text(name)
        val normalizedAddress = DestinationNormalization.text(address)
        require(normalizedName.isNotBlank() && normalizedAddress.isNotBlank()) { "Nom et adresse requis" }
        val saved = repository.save(SavedDestination(id ?: java.util.UUID.randomUUID().toString(),
            normalizedName, normalizedAddress))
        _state.update { it.copy(savedDestinations = repository.list(), status = "Destination ${saved.name} enregistrée") }
        saved
    }

    fun deleteDestination(id: String): Boolean = repository.delete(id).also { removed ->
        if (removed) _state.update { it.copy(savedDestinations = repository.list(), status = "Destination supprimée") }
    }

    fun pause() {
        if (engine.pause(clock())) {
            speechScheduler.invalidate("navigation_paused")
            publishEngine(); trace("navigation_pause")
        }
    }

    fun resume() {
        if (engine.resume(clock())) {
            publishEngine(); trace("navigation_resume")
        }
    }

    fun stop(reason: String = "Navigation arrêtée") {
        if (closed && _state.value.phase == NavigationPhase.IDLE) return
        invalidateOperation(reason)
        currentProvider?.stop(); currentProvider = null
        engine.cancel(clock(), reason)
        pendingPlace = null; simulatedPoints = emptyList(); simulatedIndex = 0
        speechScheduler.invalidate("navigation_stopped")
        _state.update { NavigationUiState(savedDestinations = repository.list(), status = reason) }
        trace("navigation_stop", mapOf("reason" to reason))
    }

    fun advanceSimulation() {
        if (_state.value.mode != NavigationMode.SIMULATED || simulatedIndex >= simulatedPoints.size) return
        simulatedLocation.emit(simulatedPoints[simulatedIndex++], clock())
        _state.update { it.copy(simulatedStepAvailable = simulatedIndex < simulatedPoints.size) }
    }

    fun onDangerPreemptedNavigation() {
        if (engine.audioInterrupted(clock())) {
            publishEngine()
            trace("navigation_audio_preempted", mapOf("routeVersion" to engine.snapshot().routeVersion))
        }
    }

    private fun begin(place: GeocodedPlace, mode: NavigationMode) {
        invalidateOperation("Destination confirmée")
        currentProvider?.stop()
        engine.cancel(clock(), "Changement de destination")
        speechScheduler.invalidate("destination_changed")
        navigationGeneration++
        pendingPlace = place
        val provider = if (mode == NavigationMode.SIMULATED) simulatedLocation else realLocation
        currentProvider = provider
        _state.update { it.copy(phase = NavigationPhase.CALCULATING, mode = mode, selected = place,
            suggestions = emptyList(), status = if (mode == NavigationMode.SIMULATED)
                "Calcul du trajet simulé" else "Attente d’une position GPS fraîche…") }
        val expectedGeneration = navigationGeneration
        val start = provider.start(expectedGeneration, ::onLocation) { reason ->
            scope.launch { if (expectedGeneration == navigationGeneration) onLocationUnavailable(reason) }
        }
        if (start.isFailure) {
            fail(start.exceptionOrNull()?.message ?: "Localisation indisponible", operationToken)
            return
        }
        trace("navigation_begin", mapOf("mode" to mode.name, "destination" to place.label,
            "navigationGeneration" to navigationGeneration))
        if (mode == NavigationMode.SIMULATED) requestRoute(
            LocationFix(navigationGeneration, clock(), GeoPoint(48.85660, 2.35220), 3f), place, recalculation = false)
    }

    private fun onLocation(fix: LocationFix) {
        scope.launch {
            if (closed || fix.generation != navigationGeneration) return@launch
            val place = pendingPlace
            if (place != null && engine.snapshot().phase in setOf(NavigationPhase.IDLE, NavigationPhase.RECALCULATING)) {
                requestRoute(fix, place, recalculation = engine.snapshot().phase == NavigationPhase.RECALCULATING)
                return@launch
            }
            handleEvents(engine.onLocation(fix))
            publishEngine()
        }
    }

    private fun requestRoute(origin: LocationFix, place: GeocodedPlace, recalculation: Boolean) {
        val token = ++operationToken
        val requestedGeneration = navigationGeneration
        val requestedVersion = ++routeVersion
        val mode = _state.value.mode
        val provider = if (mode == NavigationMode.SIMULATED) simulatedRoutes else realRoutes
        operation?.cancel()
        _state.update { it.copy(phase = if (recalculation) NavigationPhase.RECALCULATING else NavigationPhase.CALCULATING,
            status = if (recalculation) "Recalcul de l’itinéraire…" else "Calcul de l’itinéraire…") }
        operation = scope.launch(Dispatchers.IO) {
            val result = provider.route(origin.point, place, requestedVersion)
            scope.launch {
                if (closed || token != operationToken || requestedGeneration != navigationGeneration || pendingPlace?.id != place.id)
                    return@launch
                result.fold(onSuccess = { route ->
                    val accepted = if (recalculation) engine.replaceRoute(requestedGeneration, route, clock())
                        else { engine.start(requestedGeneration, route, clock(), mode); true }
                    if (!accepted) return@fold
                    speechScheduler.invalidate(if (recalculation) "route_recalculated" else "route_started")
                    if (mode == NavigationMode.SIMULATED) {
                        simulatedPoints = buildList {
                            add(route.geometry.first())
                            route.maneuvers.forEach { maneuver -> add(maneuver.point) }
                            add(route.destination.point) // second fresh arrival sample.
                        }
                        simulatedIndex = 0
                    }
                    publishEngine(route.attribution)
                    trace("navigation_route", mapOf("version" to route.version, "simulated" to route.simulated,
                        "distanceMeters" to route.distanceMeters, "maneuvers" to route.maneuvers.size,
                        "attribution" to route.attribution))
                    if (mode == NavigationMode.SIMULATED) advanceSimulation()
                }, onFailure = { error -> fail("Itinéraire : ${error.message}", token) })
            }
        }
    }

    private fun handleEvents(events: List<NavigationEvent>) {
        events.forEach { event -> when (event) {
            is NavigationEvent.Speak -> speechScheduler.offer(event.speech)
            is NavigationEvent.Arrived -> {
                currentProvider?.stop(); currentProvider = null
                speechScheduler.invalidate("navigation_arrived")
                speechScheduler.offer(event.speech)
                trace("navigation_arrived", mapOf("destination" to event.destination.label))
            }
            is NavigationEvent.Recalculate -> {
                speechScheduler.invalidate("route_invalidated_${event.reason}")
                pendingPlace = event.destination
                requestRoute(LocationFix(event.generation, clock(), event.origin, 3f),
                    event.destination, recalculation = true)
            }
        } }
    }

    private fun onLocationUnavailable(reason: String) {
        if (engine.gpsLost(clock(), reason)) speechScheduler.invalidate("gps_lost")
        publishEngine(); trace("navigation_gps_lost", mapOf("reason" to reason))
    }

    private fun publishEngine(attribution: String? = _state.value.attribution) {
        val snap = engine.snapshot()
        _state.update { current -> current.copy(phase = snap.phase, mode = snap.mode,
            selected = snap.destination ?: current.selected, currentInstruction = snap.currentInstruction,
            remainingMeters = snap.remainingMeters, routeVersion = snap.routeVersion, status = snap.status,
            attribution = attribution, simulatedStepAvailable = snap.mode == NavigationMode.SIMULATED &&
                snap.phase !in setOf(NavigationPhase.ARRIVED, NavigationPhase.IDLE) && simulatedIndex < simulatedPoints.size) }
    }

    private fun fail(message: String, token: Long) {
        if (closed || token != operationToken) return
        currentProvider?.stop(); currentProvider = null
        pendingPlace = null
        engine.cancel(clock(), message)
        speechScheduler.invalidate("navigation_failed")
        _state.update { it.copy(phase = NavigationPhase.FAILED, status = message) }
        trace("navigation_error", mapOf("reason" to message))
    }

    private fun invalidateOperation(reason: String) {
        operationToken++
        operation?.cancel(); operation = null
        trace("navigation_async_invalidated", mapOf("reason" to reason, "token" to operationToken))
    }

    private fun trace(type: String, fields: Map<String, Any> = emptyMap()) = onTrace(type, fields)

    override fun close() { if (!closed) { stop("Navigation fermée"); closed = true } }
}
