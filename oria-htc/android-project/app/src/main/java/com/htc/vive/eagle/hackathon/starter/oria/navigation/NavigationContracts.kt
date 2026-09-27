package com.htc.vive.eagle.hackathon.starter.oria.navigation

import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
}

data class SavedDestination(val id: String, val name: String, val address: String) {
    init { require(id.isNotBlank() && name.isNotBlank() && address.isNotBlank()) }
}

interface DestinationRepository {
    fun list(): List<SavedDestination>
    fun save(destination: SavedDestination): SavedDestination
    fun delete(id: String): Boolean
}

object DestinationNormalization {
    fun text(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    fun searchKey(value: String): String = text(value).lowercase(Locale.ROOT)
    fun normalized(destination: SavedDestination) = destination.copy(
        name = text(destination.name), address = text(destination.address))
}

data class GeocodedPlace(val id: String, val label: String, val point: GeoPoint) {
    init { require(id.isNotBlank() && label.isNotBlank()) }
}

interface Geocoder {
    suspend fun search(query: String, limit: Int = 5): Result<List<GeocodedPlace>>
}

data class LocationFix(
    val generation: Long,
    val observedAtMs: Long,
    val point: GeoPoint,
    val accuracyMeters: Float,
    val bearingDegrees: Float? = null,
) {
    init { require(generation >= 0 && observedAtMs >= 0 && accuracyMeters.isFinite() && accuracyMeters >= 0f && (bearingDegrees == null || bearingDegrees.isFinite())) }
}

interface LocationProvider {
    fun start(generation: Long, onFix: (LocationFix) -> Unit, onUnavailable: (String) -> Unit): Result<Unit>
    fun stop()
}

data class RouteManeuver(
    val id: String,
    val routeVersion: Long,
    val index: Int,
    val instruction: String,
    val point: GeoPoint,
    val arrival: Boolean = false,
) {
    init { require(id.isNotBlank() && instruction.isNotBlank() && routeVersion > 0 && index >= 0) }
}

data class NavigationRoute(
    val version: Long,
    val destination: GeocodedPlace,
    val geometry: List<GeoPoint>,
    val maneuvers: List<RouteManeuver>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val simulated: Boolean,
    val attribution: String,
) {
    init {
        require(version > 0 && geometry.size >= 2 && maneuvers.isNotEmpty())
        require(maneuvers.all { it.routeVersion == version } && distanceMeters.isFinite() && durationSeconds.isFinite() && distanceMeters >= 0 && durationSeconds >= 0)
    }
}

interface RouteProvider {
    suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long): Result<NavigationRoute>
}

enum class NavigationMode { REAL, SIMULATED }
enum class NavigationPhase { IDLE, SEARCHING, CONFIRMATION, CALCULATING, ACTIVE, PAUSED, LIMITED, RECALCULATING, ARRIVED, FAILED }

data class NavigationSpeech(
    val id: String,
    val generation: Long,
    val routeVersion: Long,
    val instructionVersion: Long,
    val text: String,
    val observedAtMs: Long,
    val expiresAtMs: Long,
    val maneuverId: String = "",
)

interface NavigationSpeechScheduler {
    fun offer(speech: NavigationSpeech)
    fun invalidate(reason: String)
}

sealed interface NavigationEvent {
    data class Speak(val speech: NavigationSpeech) : NavigationEvent
    data class Recalculate(val generation: Long, val invalidatedRouteVersion: Long,
                           val origin: GeoPoint, val destination: GeocodedPlace, val reason: String) : NavigationEvent
    data class Arrived(val destination: GeocodedPlace, val speech: NavigationSpeech) : NavigationEvent
}

data class NavigationSnapshot(
    val generation: Long = 0,
    val routeVersion: Long = 0,
    val instructionVersion: Long = 0,
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val mode: NavigationMode = NavigationMode.REAL,
    val destination: GeocodedPlace? = null,
    val maneuverIndex: Int = 0,
    val currentInstruction: String? = null,
    val remainingMeters: Double? = null,
    val lastFixAtMs: Long? = null,
    val status: String = "Navigation arrêtée",
)
