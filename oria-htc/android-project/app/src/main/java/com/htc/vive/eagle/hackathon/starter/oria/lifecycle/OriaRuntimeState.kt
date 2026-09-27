package com.htc.vive.eagle.hackathon.starter.oria.lifecycle

enum class OriaRuntimePhase { READY, STARTING, ACTIVE, LIMITED, INTERRUPTED, DISCONNECTED, FAILED }

enum class OriaRuntimeDependency { CAMERA, DECODER, DETECTOR, DEPTH, AUDIO, NAVIGATION }

enum class OriaDependencyAvailability { STARTING, AVAILABLE, LIMITED, UNAVAILABLE, FAILED }

data class OriaDependencyState(
    val availability: OriaDependencyAvailability,
    val generation: Long,
    val changedAtMs: Long,
    val detail: String,
    val optional: Boolean = false,
)

data class OriaRuntimeSnapshot(
    val phase: OriaRuntimePhase,
    val generation: Long,
    val changedAtMs: Long,
    val reason: String,
    val dependencies: Map<OriaRuntimeDependency, OriaDependencyState>,
) {
    companion object {
        fun initial(atMs: Long = 0L): OriaRuntimeSnapshot = OriaRuntimeSnapshot(
            OriaRuntimePhase.DISCONNECTED, 0L, atMs, "Lunettes non connectées",
            defaultDependencies(0L, atMs),
        )

        internal fun defaultDependencies(generation: Long, atMs: Long) = mapOf(
            OriaRuntimeDependency.CAMERA to OriaDependencyState(
                OriaDependencyAvailability.UNAVAILABLE, generation, atMs, "Flux caméra arrêté"),
            OriaRuntimeDependency.DECODER to OriaDependencyState(
                OriaDependencyAvailability.UNAVAILABLE, generation, atMs, "Décodeur arrêté"),
            OriaRuntimeDependency.DETECTOR to OriaDependencyState(
                OriaDependencyAvailability.STARTING, generation, atMs, "Chargement du modèle"),
            OriaRuntimeDependency.DEPTH to OriaDependencyState(
                OriaDependencyAvailability.UNAVAILABLE, generation, atMs,
                "Mode RGB · profondeur optionnelle indisponible", optional = true),
            OriaRuntimeDependency.AUDIO to OriaDependencyState(
                OriaDependencyAvailability.LIMITED, generation, atMs, "Route audio non vérifiée", optional = true),
            OriaRuntimeDependency.NAVIGATION to OriaDependencyState(
                OriaDependencyAvailability.UNAVAILABLE, generation, atMs, "Navigation non démarrée", optional = true),
        )
    }
}
/** Generation and monotonic observation carried by future depth/navigation adapters. */
data class OriaRuntimeEvidence<T>(val generation: Long, val observedAtMs: Long, val value: T)

/**
 * Diagnostic and safety state owned by OriaController. It never allocates a session ID: callers
 * must pass the controller generation. Rejected old/reversed events leave the snapshot untouched.
 */
class OriaRuntimeState(initialAtMs: Long = 0L) {
    private var current = OriaRuntimeSnapshot.initial(initialAtMs)
    private var lastEvidenceAtMs: Long? = null

    @Synchronized fun snapshot(): OriaRuntimeSnapshot = current

    @Synchronized
    fun ready(generation: Long, atMs: Long, detectorReady: Boolean): Boolean = move(
        generation, atMs, OriaRuntimePhase.READY, "Prêt",
        mapOf(
            OriaRuntimeDependency.CAMERA to state(OriaDependencyAvailability.UNAVAILABLE, generation, atMs, "Flux caméra arrêté"),
            OriaRuntimeDependency.DECODER to state(OriaDependencyAvailability.UNAVAILABLE, generation, atMs, "Décodeur arrêté"),
            OriaRuntimeDependency.DETECTOR to state(
                if (detectorReady) OriaDependencyAvailability.AVAILABLE else OriaDependencyAvailability.STARTING,
                generation, atMs, if (detectorReady) "Modèle prêt" else "Chargement du modèle"),
        ),
    )

    @Synchronized
    fun starting(generation: Long, atMs: Long, audioReady: Boolean): Boolean = move(
        generation, atMs, OriaRuntimePhase.STARTING, "Démarrage de la perception",
        mapOf(
            OriaRuntimeDependency.CAMERA to state(OriaDependencyAvailability.STARTING, generation, atMs, "Autorisation et flux caméra"),
            OriaRuntimeDependency.DECODER to state(OriaDependencyAvailability.STARTING, generation, atMs, "En attente H.264"),
            OriaRuntimeDependency.DETECTOR to state(OriaDependencyAvailability.AVAILABLE, generation, atMs, "Modèle prêt"),
            OriaRuntimeDependency.AUDIO to state(
                if (audioReady) OriaDependencyAvailability.AVAILABLE else OriaDependencyAvailability.LIMITED,
                generation, atMs, if (audioReady) "Audio prêt" else "Audio non vérifié", optional = true),
        ),
    )

    @Synchronized
    fun active(generation: Long, atMs: Long): Boolean = move(
        generation, atMs, OriaRuntimePhase.ACTIVE, "Perception RGB active",
        mapOf(
            OriaRuntimeDependency.CAMERA to state(OriaDependencyAvailability.AVAILABLE, generation, atMs, "Image fraîche"),
            OriaRuntimeDependency.DECODER to state(OriaDependencyAvailability.AVAILABLE, generation, atMs, "Décodage actif"),
            OriaRuntimeDependency.DETECTOR to state(OriaDependencyAvailability.AVAILABLE, generation, atMs, "Détection active"),
        ),
    )

    @Synchronized
    fun limited(generation: Long, atMs: Long, dependency: OriaRuntimeDependency, reason: String): Boolean = move(
        generation, atMs, OriaRuntimePhase.LIMITED, reason,
        mapOf(dependency to state(OriaDependencyAvailability.LIMITED, generation, atMs, reason,
            optional = dependency in setOf(OriaRuntimeDependency.DEPTH, OriaRuntimeDependency.AUDIO, OriaRuntimeDependency.NAVIGATION))),
    )

    @Synchronized
    fun interrupted(generation: Long, atMs: Long, reason: String): Boolean = move(
        generation, atMs, OriaRuntimePhase.INTERRUPTED, reason,
        stoppedDependencies(generation, atMs, reason),
    )

    @Synchronized
    fun disconnected(generation: Long, atMs: Long, reason: String): Boolean = move(
        generation, atMs, OriaRuntimePhase.DISCONNECTED, reason,
        stoppedDependencies(generation, atMs, reason),
    )

    @Synchronized
    fun failed(generation: Long, atMs: Long, dependency: OriaRuntimeDependency, reason: String): Boolean = move(
        generation, atMs, OriaRuntimePhase.FAILED, reason,
        stoppedDependencies(generation, atMs, reason) +
            (dependency to state(OriaDependencyAvailability.FAILED, generation, atMs, reason)),
    )

    @Synchronized
    fun dependency(generation: Long, atMs: Long, dependency: OriaRuntimeDependency,
                   availability: OriaDependencyAvailability, detail: String, optional: Boolean = false): Boolean {
        if (!validTransition(generation, atMs)) return false
        current = current.copy(changedAtMs = atMs, dependencies = current.dependencies +
            (dependency to state(availability, generation, atMs, detail, optional)))
        return true
    }

    /** Accepts a fresh proof only for the current live generation and in monotonic order. */
    @Synchronized
    fun acceptEvidence(generation: Long, observedAtMs: Long, nowMs: Long, maximumAgeMs: Long): Boolean {
        if (generation != current.generation || current.phase !in LIVE_PHASES || observedAtMs < 0 ||
            observedAtMs > nowMs || nowMs - observedAtMs > maximumAgeMs ||
            lastEvidenceAtMs?.let { observedAtMs < it } == true) return false
        lastEvidenceAtMs = observedAtMs
        return true
    }

    private fun move(generation: Long, atMs: Long, phase: OriaRuntimePhase, reason: String,
                     updates: Map<OriaRuntimeDependency, OriaDependencyState>): Boolean {
        if (!validTransition(generation, atMs)) return false
        if (generation > current.generation) lastEvidenceAtMs = null
        val base = if (generation > current.generation)
            OriaRuntimeSnapshot.defaultDependencies(generation, atMs) else current.dependencies
        current = OriaRuntimeSnapshot(phase, generation, atMs, reason, base + updates)
        if (phase !in LIVE_PHASES) lastEvidenceAtMs = null
        return true
    }

    private fun validTransition(generation: Long, atMs: Long): Boolean =
        generation >= current.generation && atMs >= current.changedAtMs && atMs >= 0

    private fun stoppedDependencies(generation: Long, atMs: Long, reason: String) = mapOf(
        OriaRuntimeDependency.CAMERA to state(OriaDependencyAvailability.UNAVAILABLE, generation, atMs, reason),
        OriaRuntimeDependency.DECODER to state(OriaDependencyAvailability.UNAVAILABLE, generation, atMs, reason),
    )

    private fun state(availability: OriaDependencyAvailability, generation: Long, atMs: Long,
                      detail: String, optional: Boolean = false) =
        OriaDependencyState(availability, generation, atMs, detail, optional)

    companion object {
        private val LIVE_PHASES = setOf(OriaRuntimePhase.STARTING, OriaRuntimePhase.ACTIVE, OriaRuntimePhase.LIMITED)
    }
}
