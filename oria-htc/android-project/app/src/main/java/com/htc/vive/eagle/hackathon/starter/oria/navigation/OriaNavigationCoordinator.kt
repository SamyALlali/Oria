package com.htc.vive.eagle.hackathon.starter.oria.navigation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable

data class NavigationUiState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val mode: NavigationMode = NavigationMode.SIMULATED,
    val query: String = "",
    val suggestions: List<GeocodedPlace> = emptyList(),
    val selected: GeocodedPlace? = null,
    val savedDestinations: List<SavedDestination> = emptyList(),
    val currentInstruction: String? = null,
    val remainingMeters: Double? = null,
    val routeVersion: Long = 0,
    val status: String = "Choisissez une destination ou essayez la démo sans réseau",
    val attribution: String? = null,
    val simulatedStepAvailable: Boolean = false,
    val networkConsent: Boolean = false,
    val foreground: Boolean = true,
)

/** Main-owned coordinator. Worker audio reads only the immutable volatile permit in canSpeak. */
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
                onInstructionsInvalidated: (String) -> Unit, onTrace: (String, Map<String, Any>) -> Unit,
                clock: () -> Long = SystemClock::elapsedRealtime) : this(
        scope, onSpeech, onInstructionsInvalidated, onTrace, clock,
        SharedPreferencesDestinationRepository(context), AndroidPlatformGeocoder(context),
        AndroidLocationProvider(context), SimulatedLocationProvider(), OsrmFootRouteProvider(), SimulatedRouteProvider())

    private val engine = NavigationEngine()
    private val _state = MutableStateFlow(NavigationUiState(savedDestinations = repository.list()))
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()
    private var navigationGeneration = 0L
    private var routeVersion = 0L
    private var operationToken = 0L
    private var operation: Job? = null
    private var watchdog: Job? = null
    private var routeInFlight = false
    private var pendingPlace: GeocodedPlace? = null
    private var currentProvider: LocationProvider? = null
    private var providerVersion = 0L
    private var simulatedPoints = emptyList<GeoPoint>()
    private var simulatedIndex = 0
    private var lastFixReceivedAt: Long? = null
    @Volatile private var closed = false
    private data class AudioPermit(val speech: NavigationSpeech, val lastFixAtMs: Long)
    @Volatile private var audioPermit: AudioPermit? = null

    fun hasLocationPermission(context: Context): Boolean = AndroidLocationProvider.hasPermission(context)
    fun updateQuery(value: String) { _state.update { it.copy(query = value.take(500)) } }
    fun setNetworkConsent(consented: Boolean) {
        if (!consented && _state.value.mode == NavigationMode.REAL) stop("Navigation réelle arrêtée")
        _state.update { it.copy(networkConsent = consented) }
    }
    fun setForeground(foreground: Boolean) {
        if (_state.value.foreground == foreground) return
        _state.update { it.copy(foreground = foreground) }
        if (!foreground) {
            invalidateSpeech("navigation_background")
            pause("Navigation en pause · gardez Oria visible pour le GPS")
        }
    }

    fun search(query: String, mode: NavigationMode) {
        if (closed) return
        if (!_state.value.foreground) { _state.update { it.copy(status = "Ouvrez Oria pour préparer un trajet") }; return }
        if (mode == NavigationMode.REAL && !_state.value.networkConsent) {
            _state.update { it.copy(status = "Autorisez la recherche réseau avant un trajet réel") }; return
        }
        val normalized = DestinationNormalization.text(query).take(500)
        if (normalized.length < 3) { _state.update { it.copy(status = "Saisissez au moins trois caractères") }; return }
        if (_state.value.phase == NavigationPhase.SEARCHING && _state.value.query == normalized && _state.value.mode == mode) return
        stop("Nouvelle recherche")
        val token = operationToken
        _state.update { it.copy(phase = NavigationPhase.SEARCHING, mode = mode, query = normalized,
            status = if (mode == NavigationMode.SIMULATED) "Préparation de la démo" else "Recherche de l’adresse…") }
        operation = scope.launch {
            val result = try {
                if (mode == NavigationMode.SIMULATED) Result.success(listOf(GeocodedPlace("simulation", normalized, GeoPoint(48.85720, 2.35300))))
                else withTimeout(10_000) { withContext(Dispatchers.IO) { geocoder.search(normalized) } }
            } catch (_: TimeoutCancellationException) { Result.failure(IllegalStateException("geocode_timeout")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            if (!current(token)) return@launch
            result.fold(onSuccess = { places ->
                _state.update { it.copy(phase = if (places.isEmpty()) NavigationPhase.FAILED else NavigationPhase.CONFIRMATION,
                    suggestions = places, selected = places.singleOrNull(),
                    status = if (places.isEmpty()) "Aucune adresse trouvée" else "Confirmez la destination") }
                trace("navigation_geocode", mapOf("mode" to mode.name, "count" to places.size))
            }, onFailure = { fail("Recherche indisponible · réessayez", token) })
        }
    }
    fun select(place: GeocodedPlace) {
        if (_state.value.phase != NavigationPhase.CONFIRMATION || place !in _state.value.suggestions) return
        _state.update { it.copy(selected = place, status = "Destination sélectionnée : ${place.label}") }
    }
    fun confirmSelected(expectedId: String? = null) {
        val state = _state.value
        val place = state.selected ?: return
        if (state.phase != NavigationPhase.CONFIRMATION || (expectedId != null && place.id != expectedId)) return
        if (!state.foreground || (state.mode == NavigationMode.REAL && !state.networkConsent)) return
        begin(place, state.mode)
    }
    fun startSaved(destination: SavedDestination, mode: NavigationMode) = search(destination.address, mode)
    fun saveDestination(name: String, address: String, id: String? = null): Result<SavedDestination> = runCatching {
        val saved = repository.save(SavedDestination(id ?: java.util.UUID.randomUUID().toString(),
            DestinationNormalization.text(name), DestinationNormalization.text(address)))
        _state.update { it.copy(savedDestinations = repository.list(), status = "Destination enregistrée") }
        saved
    }.onFailure { _state.update { it.copy(status = "Destination non enregistrée · vérifiez le nom et l’adresse") } }
    fun deleteDestination(id: String): Boolean = runCatching {
        repository.delete(id).also { if (it) _state.update { state -> state.copy(savedDestinations = repository.list(), status = "Destination supprimée") } }
    }.getOrElse { _state.update { it.copy(status = "Suppression impossible") }; false }

    fun pause() = pause("Navigation en pause")
    private fun pause(reason: String) {
        if (closed || _state.value.phase in setOf(NavigationPhase.IDLE, NavigationPhase.ARRIVED, NavigationPhase.FAILED)) return
        invalidateOperation(); stopProvider(); invalidateSpeech("navigation_paused")
        engine.pause(clock())
        _state.update { it.copy(phase = NavigationPhase.PAUSED, currentInstruction = null, remainingMeters = null,
            simulatedStepAvailable = false, status = reason) }
        trace("navigation_pause")
    }
    fun resume() {
        val state = _state.value
        if (closed || state.phase != NavigationPhase.PAUSED || !state.foreground) return
        val place = state.selected ?: run { stop("Relancez la recherche de destination"); return }
        if (state.mode == NavigationMode.REAL && !state.networkConsent) return
        if (engine.resume(clock())) {
            publishEngine(); startProvider(state.mode)
        } else begin(place, state.mode)
    }
    fun stop(reason: String = "Navigation arrêtée") {
        invalidateOperation(); navigationGeneration++
        stopProvider(); engine.cancel(clock(), reason); pendingPlace = null
        simulatedPoints = emptyList(); simulatedIndex = 0
        invalidateSpeech("navigation_stopped")
        _state.update { NavigationUiState(savedDestinations = it.savedDestinations, networkConsent = it.networkConsent,
            foreground = it.foreground, status = reason) }
        trace("navigation_stop")
    }
    fun advanceSimulation() {
        if (_state.value.mode != NavigationMode.SIMULATED || _state.value.phase != NavigationPhase.ACTIVE || simulatedIndex >= simulatedPoints.size) return
        simulatedLocation.emit(simulatedPoints[simulatedIndex++], clock())
        _state.update { it.copy(simulatedStepAvailable = simulatedIndex < simulatedPoints.size) }
    }
    fun onDangerPreemptedNavigation() {
        if (engine.audioInterrupted(clock())) { invalidateSpeech("navigation_preempted"); publishEngine() }
    }
    fun repeatFreshInstruction(): Boolean {
        val speech = engine.repeatFresh(clock()) ?: return false
        refreshPermit(); onSpeech(speech); return true
    }
    /** Read-only, worker-safe final PCM guard. No policy state is read or mutated on the audio worker. */
    fun canSpeak(speech: NavigationSpeech): Boolean {
        val permit = audioPermit ?: return false
        val at = clock()
        return !closed && _state.value.foreground && permit.speech == speech && at in speech.observedAtMs..speech.expiresAtMs &&
            at - permit.lastFixAtMs in 0..NavigationEngine.MAX_FIX_AGE_MS
    }
    fun onSpeechResult(speech: NavigationSpeech, completed: Boolean) {
        engine.onSpeechResult(speech, completed); refreshPermit()
    }

    private fun begin(place: GeocodedPlace, mode: NavigationMode) {
        invalidateOperation(); stopProvider(); invalidateSpeech("destination_changed")
        engine.cancel(clock()); navigationGeneration++; pendingPlace = place
        _state.update { it.copy(phase = NavigationPhase.CALCULATING, mode = mode, selected = place, suggestions = emptyList(),
            currentInstruction = null, remainingMeters = null,
            status = if (mode == NavigationMode.SIMULATED) "Calcul de la démo" else "Attente d’une position GPS fraîche…") }
        if (!startProvider(mode)) return
        if (mode == NavigationMode.SIMULATED) requestRoute(LocationFix(navigationGeneration, clock(), GeoPoint(48.85660, 2.35220), 3f), place, false)
    }
    private fun startProvider(mode: NavigationMode): Boolean {
        val expectedGeneration = navigationGeneration
        val subscription = ++providerVersion
        val provider = if (mode == NavigationMode.SIMULATED) simulatedLocation else realLocation
        currentProvider = provider; lastFixReceivedAt = clock()
        val result = provider.start(expectedGeneration, { fix -> onLocation(fix, subscription) }) { _ -> scope.launch {
            if (expectedGeneration == navigationGeneration && subscription == providerVersion && _state.value.foreground) onLocationUnavailable()
        } }
        if (result.isFailure) { fail("Position indisponible · vérifiez la localisation et son autorisation", operationToken); return false }
        watchdog?.cancel()
        if (mode == NavigationMode.REAL) watchdog = scope.launch { while (isActive) { delay(1_000); checkFreshness() } }
        return true
    }
    private fun onLocation(fix: LocationFix, subscription: Long) { scope.launch {
        if (closed || subscription != providerVersion || fix.generation != navigationGeneration || !_state.value.foreground ||
            _state.value.phase in setOf(NavigationPhase.IDLE, NavigationPhase.PAUSED, NavigationPhase.ARRIVED, NavigationPhase.FAILED)) return@launch
        val at = clock()
        if (at - fix.observedAtMs !in 0..NavigationEngine.MAX_FIX_AGE_MS || fix.accuracyMeters > 35f) {
            onLocationUnavailable(); return@launch
        }
        lastFixReceivedAt = at
        val place = pendingPlace
        if (place != null && engine.snapshot().phase in setOf(NavigationPhase.IDLE, NavigationPhase.RECALCULATING)) {
            if (!routeInFlight) requestRoute(fix, place, engine.snapshot().phase == NavigationPhase.RECALCULATING)
            return@launch
        }
        val previousVersion = engine.snapshot().instructionVersion
        val events = engine.onLocation(fix)
        if (previousVersion != engine.snapshot().instructionVersion) invalidateSpeech("instruction_changed")
        publishEngine()
        events.forEach { event -> when (event) {
            is NavigationEvent.Speak -> { refreshPermit(); onSpeech(event.speech) }
            is NavigationEvent.Arrived -> { stopProvider(); refreshPermit(); onSpeech(event.speech); trace("navigation_arrived") }
            is NavigationEvent.Recalculate -> {
                invalidateSpeech("route_invalidated"); requestRoute(fix, event.destination, true)
            }
        } }
    } }
    private fun requestRoute(origin: LocationFix, place: GeocodedPlace, recalculation: Boolean) {
        if (routeInFlight || !_state.value.foreground) return
        routeInFlight = true
        val token = ++operationToken; val expectedGeneration = navigationGeneration; val version = ++routeVersion
        val mode = _state.value.mode
        val provider = if (mode == NavigationMode.SIMULATED) simulatedRoutes else realRoutes
        _state.update { it.copy(phase = if (recalculation) NavigationPhase.RECALCULATING else NavigationPhase.CALCULATING,
            status = if (recalculation) "Recalcul de l’itinéraire…" else "Calcul de l’itinéraire…") }
        operation = scope.launch {
            val result = try { withTimeout(15_000) { withContext(Dispatchers.IO) { provider.route(origin.point, place, version) } } }
            catch (_: TimeoutCancellationException) { Result.failure(IllegalStateException("route_timeout")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
            if (!current(token) || expectedGeneration != navigationGeneration || pendingPlace?.id != place.id) return@launch
            routeInFlight = false
            result.fold(onSuccess = { route ->
                if (route.version != version || route.destination.id != place.id || route.simulated != (mode == NavigationMode.SIMULATED)) {
                    fail("Réponse d’itinéraire incohérente", token); return@fold
                }
                val accepted = if (recalculation) engine.replaceRoute(expectedGeneration, route, clock())
                    else { engine.start(expectedGeneration, route, clock(), mode); true }
                if (!accepted) return@fold
                invalidateSpeech("route_started")
                if (mode == NavigationMode.SIMULATED) {
                    simulatedPoints = listOf(route.geometry.first()) + route.maneuvers.map { it.point } + route.destination.point
                    simulatedIndex = 0
                }
                publishEngine(route.attribution)
                trace("navigation_route", mapOf("version" to version, "mode" to mode.name, "maneuvers" to route.maneuvers.size))
            }, onFailure = { fail("Itinéraire indisponible · vérifiez le réseau puis réessayez", token) })
        }
    }
    internal fun checkFreshness() {
        if (closed || _state.value.mode != NavigationMode.REAL || !_state.value.foreground) return
        if (lastFixReceivedAt?.let { clock() - it > NavigationEngine.MAX_FIX_AGE_MS } == true) onLocationUnavailable()
    }
    private fun onLocationUnavailable() {
        invalidateSpeech("gps_unavailable")
        if (engine.gpsLost(clock(), "Position indisponible · instructions suspendues")) publishEngine()
        else _state.update { it.copy(currentInstruction = null, status = "Attente d’une position GPS précise et fraîche…") }
    }
    private fun publishEngine(attribution: String? = _state.value.attribution) {
        val snapshot = engine.snapshot()
        _state.update { it.copy(phase = snapshot.phase, mode = snapshot.mode, selected = snapshot.destination ?: it.selected,
            currentInstruction = snapshot.currentInstruction, remainingMeters = snapshot.remainingMeters,
            routeVersion = snapshot.routeVersion, status = snapshot.status, attribution = attribution,
            simulatedStepAvailable = snapshot.mode == NavigationMode.SIMULATED && snapshot.phase == NavigationPhase.ACTIVE && simulatedIndex < simulatedPoints.size) }
        refreshPermit()
    }
    private fun refreshPermit() {
        val snapshot = engine.snapshot()
        val speech = engine.pendingSpeech()
        audioPermit = if (_state.value.foreground && speech != null && snapshot.lastFixAtMs != null && engine.canSpeak(speech, clock()))
            AudioPermit(speech, snapshot.lastFixAtMs) else null
    }
    private fun invalidateSpeech(reason: String) { audioPermit = null; onInstructionsInvalidated(reason) }
    private fun fail(message: String, token: Long) {
        if (!current(token)) return
        stopProvider(); pendingPlace = null; routeInFlight = false; engine.cancel(clock())
        invalidateSpeech("navigation_failed")
        _state.update { it.copy(phase = NavigationPhase.FAILED, currentInstruction = null, status = message) }
        trace("navigation_error")
    }
    private fun stopProvider() { providerVersion++; watchdog?.cancel(); watchdog = null; currentProvider?.stop(); currentProvider = null; lastFixReceivedAt = null }
    private fun invalidateOperation() { operationToken++; operation?.cancel(); operation = null; routeInFlight = false }
    private fun current(token: Long) = !closed && token == operationToken && _state.value.foreground
    private fun trace(type: String, fields: Map<String, Any> = emptyMap()) = onTrace(type, fields)
    override fun close() { if (!closed) { stop("Navigation fermée"); closed = true } }
}
