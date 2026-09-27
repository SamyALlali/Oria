package com.htc.vive.eagle.hackathon.starter.oria.dashboard

import kotlin.math.ceil
import kotlin.math.floor

enum class DashboardOverallState { DISCONNECTED, READY, ACTIVE, LIMITED, FAILED, REPLAY }

data class DashboardObject(
    val trackId: Long,
    val label: String,
    val confidencePercent: Int,
    val zone: String,
    val confirmed: Boolean,
    val box: List<Float>,
)

data class DashboardDecision(
    val selected: String,
    val dangerLevel: String,
    val source: String,
    val policyReason: String,
    val stabilizationReason: String,
    val suppressionReason: String,
    val estimatedRelativeProximityPercent: Int?,
    val estimatedDepthConfidencePercent: Int?,
)

data class DashboardNavigation(
    val mode: String,
    val state: String,
    val destination: String,
    val nextInstruction: String,
    val estimatedRemainingMeters: Int?,
    val gps: String,
    val routeVersion: Long,
    val preemptions: Long,
    val resumptions: Long,
)

data class DashboardMetrics(
    val usefulFps: Double,
    val receivedFrames: Long,
    val analyzedFrames: Long,
    val abandonedFrames: Long,
    val staleFrames: Long,
    val observationAgeMs: Long,
    val latencyP50Ms: Long,
    val latencyP95Ms: Long,
    val allInferenceP95Ms: Long,
    val preprocessMs: Int,
    val inferenceMs: Int,
    val estimatedDepthInferenceMs: Int,
    val audioDrops: Long,
    val dashboardPublications: Long,
    val dashboardAverageBuildMicros: Long,
    val decisionToAudioP50Ms: Long = 0,
    val decisionToAudioP95Ms: Long = 0,
    val memoryUsedMb: Long = 0,
    val cpuPercent: Double = 0.0,
    val loadProfile: String = "normal",
)

data class DashboardHealth(
    val model: String,
    val audio: String,
    val phoneBatteryPercent: Int?,
    val phoneTemperatureCelsius: Double?,
    val lastError: String?,
    val thermalStatus: Int? = null,
)

data class OriaDashboardSnapshot(
    val sequence: Long,
    val publishedAtMs: Long,
    val overall: DashboardOverallState,
    val headline: String,
    val accessibleSummary: String,
    val objects: List<DashboardObject>,
    val decision: DashboardDecision,
    val navigation: DashboardNavigation,
    val metrics: DashboardMetrics,
    val health: DashboardHealth,
    val replay: String,
) {
    companion object {
        fun initial() = OriaDashboardSnapshot(0, 0, DashboardOverallState.DISCONNECTED,
            "Lunettes déconnectées", "Oria. Lunettes déconnectées.", emptyList(),
            DashboardDecision("Aucun", "aucun", "aucune", "aucune décision",
                "aucune stabilisation", "session arrêtée", null, null),
            DashboardNavigation("aucune", "arrêtée", "Aucune", "Aucune", null,
                "indisponible", 0, 0, 0),
            DashboardMetrics(0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            DashboardHealth("chargement", "non vérifié", null, null, null), "Aucune capture")
    }
}

data class DashboardInput(
    val sequence: Long,
    val nowMs: Long,
    val connected: Boolean,
    val running: Boolean,
    val limited: Boolean,
    val failed: Boolean,
    val replaying: Boolean,
    val status: String,
    val lastAlert: String,
    val objects: List<DashboardObject>,
    val decision: DashboardDecision,
    val navigation: DashboardNavigation,
    val metrics: DashboardMetrics,
    val health: DashboardHealth,
    val replay: String,
)

/** Pure projection. Lists and boxes are copied so Compose never observes mutable pipeline data. */
object OriaDashboardProjector {
    fun project(input: DashboardInput): OriaDashboardSnapshot {
        val overall = when {
            input.replaying -> DashboardOverallState.REPLAY
            !input.connected -> DashboardOverallState.DISCONNECTED
            input.failed -> DashboardOverallState.FAILED
            input.limited -> DashboardOverallState.LIMITED
            input.running -> DashboardOverallState.ACTIVE
            else -> DashboardOverallState.READY
        }
        val headline = when (overall) {
            DashboardOverallState.ACTIVE -> input.decision.selected.takeUnless { it == "Aucun" }
                ?.let { "Priorité : $it" } ?: "Perception active · aucun danger prioritaire"
            DashboardOverallState.LIMITED -> "Mode limité · ${input.status}"
            DashboardOverallState.FAILED -> "Erreur · ${input.status}"
            DashboardOverallState.DISCONNECTED -> "Lunettes déconnectées"
            DashboardOverallState.REPLAY -> "Relecture Oria Lab"
            DashboardOverallState.READY -> "Oria prête"
        }
        val alert = input.lastAlert.ifBlank { "aucune alerte" }
        val accessible = "Oria. ${overall.name.lowercase()}. $headline. " +
            "Navigation ${input.navigation.state}, prochaine instruction ${input.navigation.nextInstruction}. " +
            "Dernière alerte : $alert."
        return OriaDashboardSnapshot(input.sequence, input.nowMs, overall, headline, accessible,
            input.objects.map { it.copy(box = it.box.toList()) }, input.decision.copy(),
            input.navigation.copy(), input.metrics.copy(), input.health.copy(), input.replay)
    }
}

/** Monotonic publication gate used by the controller and independently unit-tested. */
class DashboardRateLimiter(private val minimumIntervalMs: Long = 250L) {
    private var lastPublishedAtMs: Long? = null
    init { require(minimumIntervalMs >= 100L) }

    fun shouldPublish(nowMs: Long, force: Boolean = false,
                      minimumIntervalOverrideMs: Long = minimumIntervalMs): Boolean {
        require(nowMs >= 0)
        require(minimumIntervalOverrideMs >= 100L)
        val last = lastPublishedAtMs
        if (!force && last != null && nowMs - last < minimumIntervalOverrideMs) return false
        if (last != null && nowMs < last) return false
        lastPublishedAtMs = nowMs
        return true
    }
}

fun percentileMillis(values: Collection<Long>, fraction: Double): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val lower = floor(position).toInt()
    val upper = ceil(position).toInt()
    val weight = position - lower
    return (sorted[lower] * (1.0 - weight) + sorted[upper] * weight).toLong()
}
