package com.htc.vive.eagle.hackathon.starter.oria.navigation

class SimulatedRouteProvider : RouteProvider {
    override suspend fun route(origin: GeoPoint, destination: GeocodedPlace,
                               version: Long): Result<NavigationRoute> = Result.success(routeFor(origin, destination, version))

    companion object {
        fun routeFor(origin: GeoPoint = GeoPoint(48.85660, 2.35220),
                     destination: GeocodedPlace = GeocodedPlace("sim-destination",
                         "Démonstration SILMO", GeoPoint(48.85720, 2.35300)),
                     version: Long = 1): NavigationRoute {
            val turn = GeoPoint(origin.latitude + .00025, origin.longitude)
            val beforeArrival = GeoPoint(destination.point.latitude, destination.point.longitude - .00020)
            val geometry = listOf(origin, turn, beforeArrival, destination.point)
            val maneuvers = listOf(
                RouteManeuver("sim-$version-depart", version, 0, "Avancez tout droit", turn),
                RouteManeuver("sim-$version-turn", version, 1, "Tournez à droite", beforeArrival),
                RouteManeuver("sim-$version-arrive", version, 2, "Approchez de la destination", destination.point, arrival = true),
            )
            val distance = geometry.zipWithNext().sumOf { (a, b) -> NavigationEngine.haversineMeters(a, b) }
            return NavigationRoute(version, destination, geometry, maneuvers, distance,
                distance / 1.2, true, "Trajet simulé déterministe · aucune donnée GPS")
        }
    }
}

class SimulatedLocationProvider : LocationProvider {
    private var generation = 0L
    private var callback: ((LocationFix) -> Unit)? = null
    override fun start(generation: Long, onFix: (LocationFix) -> Unit,
                       onUnavailable: (String) -> Unit): Result<Unit> {
        this.generation = generation; callback = onFix
        return Result.success(Unit)
    }
    fun emit(point: GeoPoint, observedAtMs: Long, accuracyMeters: Float = 3f) {
        callback?.invoke(LocationFix(generation, observedAtMs, point, accuracyMeters))
    }
    override fun stop() { callback = null }
}
