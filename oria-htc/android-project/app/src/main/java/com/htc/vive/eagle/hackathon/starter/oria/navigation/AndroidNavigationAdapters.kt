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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
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
    private val lock = Mutex()
    private val requestGate = NavigationRequestRateGate()
    private val cache = linkedMapOf<String, Pair<Long, List<GeocodedPlace>>>()

    override suspend fun search(query: String, limit: Int): Result<List<GeocodedPlace>> = try {
        lock.withLock {
            val normalized = DestinationNormalization.text(query)
            require(normalized.length in 3..500) { "Adresse invalide" }
            check(AndroidGeocoder.isPresent()) { "Géocodeur indisponible" }
            val key = "${DestinationNormalization.searchKey(normalized)}:${limit.coerceIn(1, 8)}"
            val at = android.os.SystemClock.elapsedRealtime()
            cache[key]?.takeIf { at - it.first < 300_000 }?.let { return@withLock Result.success(it.second) }
            requestGate.awaitTurn()
            val addresses: List<Address> = withTimeout(10_000) {
                if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(normalized, limit.coerceIn(1, 8), object : AndroidGeocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(results)
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Géocodage indisponible")))
                        }
                    })
                } else withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION") geocoder.getFromLocationName(normalized, limit.coerceIn(1, 8)).orEmpty()
                }
            }
            currentCoroutineContext().ensureActive()
            val places = addresses.mapIndexedNotNull { index, address ->
                if (!address.hasLatitude() || !address.hasLongitude()) return@mapIndexedNotNull null
                val label = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(address.featureName, address.locality, address.countryName).joinToString(", ").ifBlank { normalized }
                GeocodedPlace("android-${address.latitude}-${address.longitude}-$index", label.take(500), GeoPoint(address.latitude, address.longitude))
            }.distinctBy { "%.6f,%.6f".format(Locale.ROOT, it.point.latitude, it.point.longitude) }
            cache[key] = at to places
            while (cache.size > 16) cache.remove(cache.keys.first())
            Result.success(places)
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }
}

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // Guarded immediately below; SecurityException is captured by Result.
    override fun start(generation: Long, onFix: (LocationFix) -> Unit,
                       onUnavailable: (String) -> Unit): Result<Unit> = runCatching {
        stop()
        check(hasPermission(context)) { "Autorisation de localisation requise" }
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val activeProviders = (if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        check(activeProviders.isNotEmpty()) { "Localisation désactivée" }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val atMs = if (location.elapsedRealtimeNanos > 0) location.elapsedRealtimeNanos / 1_000_000
                    else android.os.SystemClock.elapsedRealtime()
                if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0f ||
                    !location.latitude.isFinite() || !location.longitude.isFinite()) {
                    onUnavailable("Position imprécise"); return
                }
                onFix(LocationFix(generation, atMs, GeoPoint(location.latitude, location.longitude),
                    location.accuracy, location.bearing.takeIf { location.hasBearing() && it.isFinite() }))
            }
            override fun onProviderDisabled(provider: String) {
                if (activeProviders.none { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) })
                    onUnavailable("Localisation désactivée")
            }
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = callback
        try {
            activeProviders.forEach { provider -> manager.requestLocationUpdates(provider, 1_000L, 1f, callback, Looper.getMainLooper()) }
        } catch (e: Exception) { stop(); throw e }
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

/** Dedicated FOSSGIS foot graph. Network consent is enforced by the coordinator before entry. */
class OsrmFootRouteProvider : RouteProvider {
    override suspend fun route(origin: GeoPoint, destination: GeocodedPlace, version: Long): Result<NavigationRoute> = try {
        REQUEST_GATE.awaitTurn()
        currentCoroutineContext().ensureActive()
        val body: String = suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val future = NETWORK.submit {
                try {
                    if (!continuation.isActive) return@submit
                    val coordinates = "${origin.longitude},${origin.latitude};${destination.point.longitude},${destination.point.latitude}"
                    val conn = (URL("$ENDPOINT/$coordinates?overview=full&geometries=geojson&steps=true").openConnection() as HttpURLConnection)
                    connection.set(conn)
                    conn.requestMethod = "GET"; conn.instanceFollowRedirects = false; conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                    conn.setRequestProperty("User-Agent", "Oria-SILMO/1.7 (hackathon prototype)")
                    conn.setRequestProperty("Accept", "application/json")
                    if (!continuation.isActive) return@submit
                    check(conn.responseCode == 200) { "Service d’itinéraire indisponible" }
                    val bytes = conn.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= MAX_RESPONSE_BYTES) { "Réponse trop volumineuse" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Service d’itinéraire indisponible")))
                } finally { connection.getAndSet(null)?.disconnect() }
            }
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.cancel(true) }
        }
        Result.success(parseRoute(body, destination, version))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    companion object {
        const val ENDPOINT = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
        const val ATTRIBUTION = "© OpenStreetMap contributors · itinéraire piéton OSRM/FOSSGIS"
        const val FIX_MAP_URL = "https://www.openstreetmap.org/fixthemap"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val NETWORK = Executors.newFixedThreadPool(2) { task -> Thread(task, "oria-route-http").apply { isDaemon = true } }
        private val REQUEST_GATE = NavigationRequestRateGate()

        internal fun parseRoute(body: String, destination: GeocodedPlace, version: Long): NavigationRoute {
            val root = JSONObject(body)
            check(root.optString("code") == "Ok") { "Itinéraire introuvable" }
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val geometry = (0 until coords.length()).map { coords.getJSONArray(it).point() }
            val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val maneuvers = buildList {
                for (index in 0 until steps.length()) {
                    val step = steps.getJSONObject(index)
                    // Reject an unexpected configured graph instead of silently accepting a vehicle route.
                    check(step.optString("mode") in setOf("walking", "pushing bike", "ferry")) { "Profil piéton non confirmé" }
                    val maneuver = step.getJSONObject("maneuver")
                    val type = maneuver.optString("type")
                    add(RouteManeuver("osrm-$version-$index", version, index,
                        frenchInstruction(type, maneuver.optString("modifier"), step.optString("name"), maneuver.optInt("exit", 0)),
                        maneuver.getJSONArray("location").point(), arrival = type == "arrive"))
                }
                if (isEmpty() || !last().arrival) add(RouteManeuver("osrm-$version-arrive", version, size,
                    "Approchez de la destination", destination.point, arrival = true))
            }
            return NavigationRoute(version, destination, geometry, maneuvers, route.getDouble("distance"),
                route.getDouble("duration"), false, ATTRIBUTION)
        }
        private fun JSONArray.point() = GeoPoint(getDouble(1), getDouble(0))
        private fun frenchInstruction(type: String, modifier: String, name: String, exit: Int): String {
            val road = name.take(100).takeIf { it.isNotBlank() }?.let { " sur $it" }.orEmpty()
            return when (type) {
                "depart" -> "Partez$road"
                "arrive" -> "Approchez de la destination"
                "roundabout", "rotary" -> if (exit > 0) "Au rond-point, prenez la sortie $exit$road" else "Entrez dans le rond-point$road"
                else -> when (modifier) {
                    "left", "sharp left", "slight left" -> "Tournez à gauche$road"
                    "right", "sharp right", "slight right" -> "Tournez à droite$road"
                    "uturn" -> "Faites demi-tour$road"
                    else -> "Continuez tout droit$road"
                }
            }
        }
    }
}

fun newDestination(name: String, address: String) = SavedDestination(
    UUID.randomUUID().toString(), DestinationNormalization.text(name), DestinationNormalization.text(address))
