package com.htc.vive.eagle.hackathon.starter.oria.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder as AndroidGeocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class SharedPreferencesDestinationRepository(context: Context) : DestinationRepository {
    private val preferences = context.applicationContext
        .getSharedPreferences("oria_navigation_destinations", Context.MODE_PRIVATE)

    @Synchronized override fun list(): List<SavedDestination> = decode(preferences.getString(KEY, null))

    @Synchronized override fun save(destination: SavedDestination): SavedDestination {
        val normalized = DestinationNormalization.normalized(destination)
        val entries = list().toMutableList()
        val existing = entries.indexOfFirst { it.id == normalized.id }
        if (existing >= 0) entries[existing] = normalized else entries += normalized
        check(preferences.edit().putString(KEY, encode(entries)).commit()) { "Sauvegarde des destinations refusée" }
        return normalized
    }

    @Synchronized override fun delete(id: String): Boolean {
        val entries = list().toMutableList()
        val removed = entries.removeAll { it.id == id }
        if (removed) check(preferences.edit().putString(KEY, encode(entries)).commit()) {
            "Suppression de destination refusée"
        }
        return removed
    }

    private fun encode(entries: List<SavedDestination>) = JSONArray().apply { entries.forEach { destination ->
        put(JSONObject().put("id", destination.id).put("name", destination.name).put("address", destination.address))
    } }.toString()

    private fun decode(raw: String?): List<SavedDestination> = runCatching {
        if (raw.isNullOrBlank()) emptyList() else buildList {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { item ->
                val id = item.optString("id"); val name = DestinationNormalization.text(item.optString("name"))
                val address = DestinationNormalization.text(item.optString("address"))
                if (id.isNotBlank() && name.isNotBlank() && address.isNotBlank()) add(SavedDestination(id, name, address))
            }
        }
    }.getOrDefault(emptyList())

    companion object { private const val KEY = "saved_destinations_v1" }
}

class AndroidPlatformGeocoder(context: Context) : Geocoder {
    private val geocoder = AndroidGeocoder(context.applicationContext, Locale.FRANCE)

    override suspend fun search(query: String, limit: Int): Result<List<GeocodedPlace>> = runCatching {
        val normalized = DestinationNormalization.text(query)
        require(normalized.length >= 3) { "Adresse trop courte" }
        val addresses = if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(normalized, limit.coerceIn(1, 8), object : AndroidGeocoder.GeocodeListener {
                override fun onGeocode(results: MutableList<Address>) { if (continuation.isActive) continuation.resume(results) }
                override fun onError(errorMessage: String?) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(
                        IllegalStateException(errorMessage ?: "Géocodage indisponible")))
                }
            })
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION") geocoder.getFromLocationName(normalized, limit.coerceIn(1, 8)).orEmpty()
        }
        addresses.mapIndexed { index, address ->
            val label = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(address.featureName, address.locality, address.countryName)
                    .joinToString(", ").ifBlank { normalized }
            GeocodedPlace("android-${address.latitude}-${address.longitude}-$index", label,
                GeoPoint(address.latitude, address.longitude))
        }.distinctBy { "%.6f,%.6f".format(Locale.ROOT, it.point.latitude, it.point.longitude) }
    }
}

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // Guarded immediately below; SecurityException is captured by Result.
    override fun start(generation: Long, onFix: (LocationFix) -> Unit,
                       onUnavailable: (String) -> Unit): Result<Unit> = runCatching {
        stop()
        check(hasPermission(context)) { "Autorisation de localisation requise" }
        val activeProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        check(activeProviders.isNotEmpty()) { "Localisation désactivée" }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val atMs = if (location.elapsedRealtimeNanos > 0) location.elapsedRealtimeNanos / 1_000_000
                    else android.os.SystemClock.elapsedRealtime()
                onFix(LocationFix(generation, atMs, GeoPoint(location.latitude, location.longitude),
                    location.accuracy.coerceAtLeast(0f), location.bearing.takeIf { location.hasBearing() }))
            }
            override fun onProviderDisabled(provider: String) {
                if (activeProviders.none { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) })
                    onUnavailable("Localisation désactivée")
            }
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        activeProviders.forEach { provider -> manager.requestLocationUpdates(provider, 1_000L, 1f, callback, Looper.getMainLooper()) }
    }

    override fun stop() {
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** Walking route through the public OSRM demo endpoint. No key is embedded; callers must retain attribution. */
class OsrmFootRouteProvider(
    private val endpoint: String = "https://routing.openstreetmap.de/routed-foot/route/v1/driving",
) : RouteProvider {
    init { require(endpoint.startsWith("https://")) { "Le fournisseur d’itinéraire doit utiliser HTTPS" } }
    override suspend fun route(origin: GeoPoint, destination: GeocodedPlace,
                               version: Long): Result<NavigationRoute> = withContext(Dispatchers.IO) { runCatching {
        val coordinates = "${origin.longitude},${origin.latitude};${destination.point.longitude},${destination.point.latitude}"
        val url = URL("$endpoint/$coordinates?overview=full&geometries=geojson&steps=true&language=fr")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "Oria-SILMO/1.4-night (hackathon prototype)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            check(status == 200) { "Service d’itinéraire indisponible (HTTP $status)" }
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(root.optString("code") == "Ok") { "Itinéraire introuvable : ${root.optString("code")}" }
            val route = root.getJSONArray("routes").getJSONObject(0)
            val geometry = route.getJSONObject("geometry").getJSONArray("coordinates").toPoints()
            val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val maneuvers = buildList {
                for (index in 0 until steps.length()) {
                    val step = steps.getJSONObject(index)
                    val maneuver = step.getJSONObject("maneuver")
                    val point = maneuver.getJSONArray("location").toPoint()
                    add(RouteManeuver("osrm-$version-$index", version, index,
                        frenchInstruction(maneuver.optString("type"), maneuver.optString("modifier"),
                            step.optString("name")), point))
                }
                if (isEmpty() || last().point != destination.point) add(RouteManeuver(
                    "osrm-$version-arrive", version, size, "Vous êtes arrivé à ${destination.label}", destination.point))
            }
            NavigationRoute(version, destination, geometry, maneuvers, route.getDouble("distance"),
                route.getDouble("duration"), false, "© OpenStreetMap contributors · itinéraire OSRM")
        } finally { connection.disconnect() }
    } }

    private fun JSONArray.toPoints(): List<GeoPoint> = buildList {
        for (index in 0 until length()) add(getJSONArray(index).toPoint())
    }
    private fun JSONArray.toPoint() = GeoPoint(getDouble(1), getDouble(0))

    private fun frenchInstruction(type: String, modifier: String, name: String): String {
        val road = name.takeIf { it.isNotBlank() }?.let { " sur $it" }.orEmpty()
        return when (type) {
            "depart" -> "Partez$road"
            "arrive" -> "Vous êtes arrivé"
            "roundabout", "rotary" -> "Entrez dans le rond-point$road"
            else -> when (modifier) {
                "left", "sharp left", "slight left" -> "Tournez à gauche$road"
                "right", "sharp right", "slight right" -> "Tournez à droite$road"
                "uturn" -> "Faites demi-tour$road"
                else -> "Continuez tout droit$road"
            }
        }
    }
}

fun newDestination(name: String, address: String) = SavedDestination(
    UUID.randomUUID().toString(), DestinationNormalization.text(name), DestinationNormalization.text(address))
