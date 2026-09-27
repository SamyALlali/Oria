package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlin.math.*

/** Single-owner policy. A proposal is consumed only after the matching playback completes. */
class NavigationEngine(
    private val offRouteMeters: Double = 45.0,
    private val maneuverReachedMeters: Double = 18.0,
    private val arrivalMeters: Double = 15.0,
    private val maximumUsefulAccuracyMeters: Float = 35f,
) {
    private var route: NavigationRoute? = null
    private var snapshot = NavigationSnapshot()
    private var lastEvidenceAt = -1L
    private var invalidatedAt = -1L
    private var offRouteSamples = 0
    private var arrivalSamples = 0
    private val spoken = mutableSetOf<String>()
    private var pending: Pair<String, NavigationSpeech>? = null
    private var proposalSequence = 0L

    fun snapshot(): NavigationSnapshot = snapshot
    fun pendingSpeech(): NavigationSpeech? = pending?.second

    fun start(generation: Long, newRoute: NavigationRoute, atMs: Long,
              mode: NavigationMode = if (newRoute.simulated) NavigationMode.SIMULATED else NavigationMode.REAL): NavigationSnapshot {
        require(generation >= 0 && atMs >= 0)
        route = newRoute; lastEvidenceAt = -1; invalidatedAt = atMs
        offRouteSamples = 0; arrivalSamples = 0; spoken.clear(); pending = null
        snapshot = NavigationSnapshot(generation, newRoute.version, snapshot.instructionVersion + 1,
            NavigationPhase.ACTIVE, mode, newRoute.destination, 0, null, newRoute.distanceMeters, null,
            if (newRoute.simulated) "Trajet simulé prêt · avancez la simulation" else "Itinéraire prêt · attente d’une position fraîche")
        return snapshot
    }

    fun replaceRoute(expectedGeneration: Long, newRoute: NavigationRoute, atMs: Long): Boolean {
        if (snapshot.generation != expectedGeneration || snapshot.phase != NavigationPhase.RECALCULATING ||
            newRoute.version <= snapshot.routeVersion || atMs < invalidatedAt) return false
        start(expectedGeneration, newRoute, atMs, snapshot.mode)
        return true
    }

    fun onLocation(fix: LocationFix): List<NavigationEvent> {
        val activeRoute = route ?: return emptyList()
        if (fix.generation != snapshot.generation || fix.observedAtMs <= lastEvidenceAt ||
            fix.observedAtMs <= invalidatedAt || snapshot.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.LIMITED)) return emptyList()
        lastEvidenceAt = fix.observedAtMs
        if (fix.accuracyMeters > maximumUsefulAccuracyMeters) {
            invalidateInstructions(fix.observedAtMs, NavigationPhase.LIMITED, "Position trop imprécise")
            return emptyList()
        }
        val routeDistance = distanceToPolylineMeters(fix.point, activeRoute.geometry)
        offRouteSamples = if (routeDistance > offRouteMeters + fix.accuracyMeters) offRouteSamples + 1 else 0
        if (offRouteSamples >= 2) {
            invalidateInstructions(fix.observedAtMs, NavigationPhase.RECALCULATING, "Sortie d’itinéraire · recalcul")
            return listOf(NavigationEvent.Recalculate(snapshot.generation, snapshot.routeVersion, fix.point,
                activeRoute.destination, "off_route"))
        }
        var index = snapshot.maneuverIndex.coerceIn(activeRoute.maneuvers.indices)
        var distance = haversineMeters(fix.point, activeRoute.maneuvers[index].point)
        while (index < activeRoute.maneuvers.lastIndex && distance <= maneuverReachedMeters) {
            index++; distance = haversineMeters(fix.point, activeRoute.maneuvers[index].point)
        }
        if (index != snapshot.maneuverIndex) {
            pending = null
            snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1)
        }
        val finalDistance = haversineMeters(fix.point, activeRoute.destination.point)
        arrivalSamples = if (fix.accuracyMeters <= 10f && finalDistance <= arrivalMeters) arrivalSamples + 1 else 0
        if (arrivalSamples >= 2) {
            invalidateInstructions(fix.observedAtMs, NavigationPhase.ARRIVED, "Destination atteinte")
            snapshot = snapshot.copy(lastFixAtMs = fix.observedAtMs, remainingMeters = 0.0,
                currentInstruction = "Vous êtes arrivé à ${activeRoute.destination.label}")
            val speech = proposal("arrived", snapshot.currentInstruction!!, fix.observedAtMs, 10_000, "arrival")
            return listOf(NavigationEvent.Arrived(activeRoute.destination, speech))
        }
        if (arrivalSamples == 1) {
            // A first accurate arrival fix may jump past the tracked maneuver. Do not repeat
            // an old turn or announce arrival until a second independent fix confirms it.
            pending = null
            snapshot = snapshot.copy(phase = NavigationPhase.ACTIVE, instructionVersion = snapshot.instructionVersion + 1,
                currentInstruction = null, remainingMeters = finalDistance, lastFixAtMs = fix.observedAtMs,
                status = "Arrivée à confirmer par une seconde position")
            return emptyList()
        }
        val maneuver = activeRoute.maneuvers[index]
        snapshot = snapshot.copy(phase = NavigationPhase.ACTIVE, maneuverIndex = index,
            currentInstruction = if (maneuver.arrival) "Approchez de la destination" else maneuver.instruction,
            remainingMeters = distance, lastFixAtMs = fix.observedAtMs,
            status = if (maneuver.arrival) "Arrivée à confirmer par la position" else "${distance.roundToInt()} m · ${maneuver.instruction}")
        // Arrival is only spoken after the two explicit accurate samples above.
        if (maneuver.arrival) return emptyList()
        val bucket = when { distance <= 25 -> "now"; distance <= 80 -> "soon"; index == 0 -> "start"; else -> null }
            ?: return emptyList()
        val key = "${activeRoute.version}:${maneuver.id}:$bucket"
        if (key in spoken) return emptyList()
        pending?.let { (_, request) ->
            if (request.instructionVersion == snapshot.instructionVersion && fix.observedAtMs <= request.expiresAtMs) return emptyList()
        }
        val prefix = when (bucket) { "soon" -> "Dans ${distance.roundToInt()} mètres, "; "now" -> "Maintenant, "; else -> "" }
        return listOf(NavigationEvent.Speak(proposal(key, prefix + maneuver.instruction, fix.observedAtMs, 8_000, maneuver.id)))
    }

    private fun proposal(key: String, text: String, atMs: Long, lifetimeMs: Long, maneuverId: String): NavigationSpeech {
        val speech = NavigationSpeech("nav-${snapshot.generation}-${snapshot.routeVersion}-${snapshot.instructionVersion}-${++proposalSequence}",
            snapshot.generation, snapshot.routeVersion, snapshot.instructionVersion, text, atMs, atMs + lifetimeMs, maneuverId)
        pending = key to speech
        return speech
    }

    fun canSpeak(speech: NavigationSpeech, nowMs: Long): Boolean =
        snapshot.phase in setOf(NavigationPhase.ACTIVE, NavigationPhase.ARRIVED) &&
            speech.generation == snapshot.generation && speech.routeVersion == snapshot.routeVersion &&
            speech.instructionVersion == snapshot.instructionVersion && pending?.second?.id == speech.id &&
            nowMs in speech.observedAtMs..speech.expiresAtMs &&
            snapshot.lastFixAtMs?.let { nowMs - it in 0..MAX_FIX_AGE_MS } == true

    fun onSpeechResult(speech: NavigationSpeech, completed: Boolean) {
        val item = pending ?: return
        if (item.second.id != speech.id) return
        if (completed) spoken += item.first
        pending = null
    }

    fun repeatFresh(atMs: Long): NavigationSpeech? {
        val sourceAt = snapshot.lastFixAtMs ?: return null
        val text = snapshot.currentInstruction ?: return null
        if (snapshot.phase != NavigationPhase.ACTIVE || atMs - sourceAt !in 0..MAX_FIX_AGE_MS || pending != null) return null
        val maneuver = route?.maneuvers?.getOrNull(snapshot.maneuverIndex) ?: return null
        if (maneuver.arrival) return null
        return proposal("repeat-${++proposalSequence}", text, sourceAt, 8_000, maneuver.id)
    }

    fun pause(atMs: Long): Boolean {
        if (snapshot.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.LIMITED, NavigationPhase.RECALCULATING)) return false
        invalidateInstructions(atMs, NavigationPhase.PAUSED, "Navigation en pause")
        return true
    }
    fun resume(atMs: Long): Boolean {
        if (snapshot.phase != NavigationPhase.PAUSED) return false
        invalidatedAt = atMs; spoken.clear(); pending = null
        snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1, phase = NavigationPhase.ACTIVE,
            currentInstruction = null, lastFixAtMs = null, status = "Reprise · attente d’une position fraîche")
        return true
    }
    fun gpsLost(atMs: Long, reason: String): Boolean {
        if (snapshot.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.LIMITED)) return false
        invalidateInstructions(atMs, NavigationPhase.LIMITED, reason)
        return true
    }
    fun audioInterrupted(atMs: Long): Boolean {
        if (snapshot.phase != NavigationPhase.ACTIVE) return false
        invalidateInstructions(atMs, NavigationPhase.ACTIVE, "Alerte prioritaire · attente d’une position fraîche")
        return true
    }
    fun cancel(atMs: Long, reason: String = "Navigation arrêtée") {
        invalidatedAt = atMs; route = null; spoken.clear(); pending = null
        snapshot = NavigationSnapshot(generation = snapshot.generation + 1,
            instructionVersion = snapshot.instructionVersion + 1, status = reason)
    }
    private fun invalidateInstructions(atMs: Long, phase: NavigationPhase, status: String) {
        invalidatedAt = max(invalidatedAt, atMs); spoken.clear(); pending = null; arrivalSamples = 0
        snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1, phase = phase,
            currentInstruction = null, remainingMeters = null, status = status, lastFixAtMs = null)
    }
    companion object {
        const val MAX_FIX_AGE_MS = 6_000L
        fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
            val h = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
            return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        fun distanceToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double =
            line.zipWithNext().minOfOrNull { (a, b) -> distanceToSegmentMeters(point, a, b) } ?: Double.POSITIVE_INFINITY
        private fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            val latScale = 111_320.0; val lonScale = latScale * cos(Math.toRadians(p.latitude))
            val ax = (a.longitude - p.longitude) * lonScale; val ay = (a.latitude - p.latitude) * latScale
            val bx = (b.longitude - p.longitude) * lonScale; val by = (b.latitude - p.latitude) * latScale
            val dx = bx - ax; val dy = by - ay; val length2 = dx * dx + dy * dy
            val t = if (length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}
