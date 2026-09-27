package com.htc.vive.eagle.hackathon.starter.oria.navigation

import kotlin.math.*

/** Deterministic navigation policy. It owns no Android API, network call, wall clock or audio player. */
class NavigationEngine(
    private val offRouteMeters: Double = 45.0,
    private val maneuverReachedMeters: Double = 18.0,
    private val arrivalMeters: Double = 15.0,
    private val maximumUsefulAccuracyMeters: Float = 80f,
) {
    private var route: NavigationRoute? = null
    private var snapshot = NavigationSnapshot()
    private var lastEvidenceAt = -1L
    private var invalidatedAt = -1L
    private var offRouteSamples = 0
    private var arrivalSamples = 0
    private val spoken = mutableSetOf<String>()

    fun snapshot(): NavigationSnapshot = snapshot

    fun start(generation: Long, newRoute: NavigationRoute, atMs: Long,
              mode: NavigationMode = if (newRoute.simulated) NavigationMode.SIMULATED else NavigationMode.REAL): NavigationSnapshot {
        require(generation >= 0 && atMs >= 0)
        route = newRoute
        lastEvidenceAt = -1
        invalidatedAt = atMs
        offRouteSamples = 0
        arrivalSamples = 0
        spoken.clear()
        snapshot = NavigationSnapshot(generation, newRoute.version, snapshot.instructionVersion + 1,
            NavigationPhase.ACTIVE, mode, newRoute.destination, 0,
            newRoute.maneuvers.first().instruction, newRoute.distanceMeters, null,
            if (newRoute.simulated) "Trajet simulé prêt" else "Itinéraire prêt")
        return snapshot
    }

    fun replaceRoute(expectedGeneration: Long, newRoute: NavigationRoute, atMs: Long): Boolean {
        if (snapshot.generation != expectedGeneration || snapshot.phase == NavigationPhase.IDLE ||
            newRoute.version <= snapshot.routeVersion || atMs < invalidatedAt) return false
        start(expectedGeneration, newRoute, atMs, snapshot.mode)
        return true
    }

    fun onLocation(fix: LocationFix): List<NavigationEvent> {
        val activeRoute = route ?: return emptyList()
        if (fix.generation != snapshot.generation || fix.observedAtMs <= lastEvidenceAt ||
            fix.observedAtMs <= invalidatedAt || snapshot.phase in setOf(NavigationPhase.IDLE,
                NavigationPhase.PAUSED, NavigationPhase.ARRIVED, NavigationPhase.FAILED,
                NavigationPhase.RECALCULATING)) return emptyList()
        lastEvidenceAt = fix.observedAtMs
        if (fix.accuracyMeters > maximumUsefulAccuracyMeters) {
            invalidateInstructions(fix.observedAtMs, NavigationPhase.LIMITED,
                "Position trop imprécise (${fix.accuracyMeters.toInt()} m)")
            return emptyList()
        }
        if (snapshot.phase == NavigationPhase.LIMITED) {
            snapshot = snapshot.copy(phase = NavigationPhase.ACTIVE, status = "Position GPS retrouvée")
        }

        val routeDistance = distanceToPolylineMeters(fix.point, activeRoute.geometry)
        offRouteSamples = if (routeDistance > offRouteMeters + fix.accuracyMeters) offRouteSamples + 1 else 0
        if (offRouteSamples >= 2) {
            val oldVersion = snapshot.routeVersion
            invalidateInstructions(fix.observedAtMs, NavigationPhase.RECALCULATING, "Sortie d’itinéraire · recalcul")
            return listOf(NavigationEvent.Recalculate(snapshot.generation, oldVersion, fix.point,
                activeRoute.destination, "off_route"))
        }

        var index = snapshot.maneuverIndex.coerceIn(activeRoute.maneuvers.indices)
        var distance = haversineMeters(fix.point, activeRoute.maneuvers[index].point)
        while (index < activeRoute.maneuvers.lastIndex && distance <= maneuverReachedMeters) {
            index++
            distance = haversineMeters(fix.point, activeRoute.maneuvers[index].point)
        }
        val finalDistance = haversineMeters(fix.point, activeRoute.destination.point)
        arrivalSamples = if (finalDistance <= arrivalMeters + fix.accuracyMeters.coerceAtMost(10f)) arrivalSamples + 1 else 0
        if (arrivalSamples >= 2) {
            invalidateInstructions(fix.observedAtMs, NavigationPhase.ARRIVED, "Destination atteinte")
            return listOf(NavigationEvent.Arrived(activeRoute.destination, NavigationSpeech(
                id = "nav-${activeRoute.version}-arrived",
                generation = snapshot.generation,
                routeVersion = activeRoute.version,
                instructionVersion = snapshot.instructionVersion,
                text = "Vous êtes arrivé à ${activeRoute.destination.label}",
                observedAtMs = fix.observedAtMs,
                expiresAtMs = fix.observedAtMs + 10_000,
            )))
        }

        val maneuver = activeRoute.maneuvers[index]
        snapshot = snapshot.copy(phase = NavigationPhase.ACTIVE, maneuverIndex = index,
            currentInstruction = maneuver.instruction, remainingMeters = distance,
            lastFixAtMs = fix.observedAtMs, status = "${distance.roundToInt()} m · ${maneuver.instruction}")
        val bucket = when {
            distance <= 25 -> "now"
            distance <= 80 -> "soon"
            index == 0 && snapshot.lastFixAtMs == fix.observedAtMs -> "start"
            else -> null
        }
        val speechKey = bucket?.let { "${activeRoute.version}:${maneuver.id}:$it" }
        if (speechKey == null || !spoken.add(speechKey)) return emptyList()
        val prefix = when (bucket) {
            "soon" -> "Dans ${distance.roundToInt()} mètres, "
            "now" -> "Maintenant, "
            else -> ""
        }
        return listOf(NavigationEvent.Speak(NavigationSpeech(
            id = "nav-${activeRoute.version}-${maneuver.id}-$bucket",
            generation = snapshot.generation,
            routeVersion = activeRoute.version,
            instructionVersion = snapshot.instructionVersion,
            text = prefix + maneuver.instruction,
            observedAtMs = fix.observedAtMs,
            expiresAtMs = fix.observedAtMs + 8_000,
        )))
    }

    fun pause(atMs: Long): Boolean {
        if (snapshot.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.LIMITED)) return false
        invalidateInstructions(atMs, NavigationPhase.PAUSED, "Navigation en pause")
        return true
    }

    fun resume(atMs: Long): Boolean {
        if (snapshot.phase != NavigationPhase.PAUSED) return false
        invalidatedAt = atMs
        spoken.clear()
        snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1,
            phase = NavigationPhase.ACTIVE, status = "Reprise · attente d’une position fraîche")
        return true
    }

    fun gpsLost(atMs: Long, reason: String): Boolean {
        if (snapshot.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.LIMITED)) return false
        invalidateInstructions(atMs, NavigationPhase.LIMITED, reason)
        return true
    }

    /** A danger cancelled the audible maneuver. Only a later fresh GPS fix may make it useful again. */
    fun audioInterrupted(atMs: Long): Boolean {
        if (snapshot.phase != NavigationPhase.ACTIVE) return false
        invalidatedAt = max(invalidatedAt, atMs)
        spoken.clear()
        snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1,
            status = "Alerte prioritaire · navigation en attente d’une position fraîche")
        return true
    }

    fun cancel(atMs: Long, reason: String = "Navigation arrêtée") {
        invalidatedAt = atMs
        route = null
        spoken.clear()
        snapshot = NavigationSnapshot(generation = snapshot.generation + 1,
            instructionVersion = snapshot.instructionVersion + 1, status = reason)
    }

    private fun invalidateInstructions(atMs: Long, phase: NavigationPhase, status: String) {
        invalidatedAt = max(invalidatedAt, atMs)
        spoken.clear()
        snapshot = snapshot.copy(instructionVersion = snapshot.instructionVersion + 1,
            phase = phase, status = status, lastFixAtMs = null)
    }

    companion object {
        fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
            val earth = 6_371_000.0
            val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1; val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 2 * earth * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        fun distanceToPolylineMeters(point: GeoPoint, line: List<GeoPoint>): Double =
            line.zipWithNext().minOfOrNull { (a, b) -> distanceToSegmentMeters(point, a, b) }
                ?: Double.POSITIVE_INFINITY

        private fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            val latScale = 111_320.0
            val lonScale = latScale * cos(Math.toRadians(p.latitude))
            val ax = (a.longitude - p.longitude) * lonScale; val ay = (a.latitude - p.latitude) * latScale
            val bx = (b.longitude - p.longitude) * lonScale; val by = (b.latitude - p.latitude) * latScale
            val dx = bx - ax; val dy = by - ay
            val length2 = dx * dx + dy * dy
            val t = if (length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}
