package com.htc.vive.eagle.hackathon.starter.oria.robustness

enum class OriaFailure {
    GLASSES, H264_STREAM, DECODER, DETECTOR, DEPTH, BLUETOOTH_AUDIO,
    MICROPHONE, GPS, NETWORK, ROUTE_PROVIDER,
}

data class OriaFailureImpact(
    val preserved: Set<String>,
    val cutOutputs: Set<String>,
    val message: String,
    val recovery: String,
    val requiresNewGeneration: Boolean,
)

/** Product contract used by diagnostics and tests; it never attempts an automatic unsafe restart. */
object OriaFailurePolicy {
    fun impact(failure: OriaFailure): OriaFailureImpact = when (failure) {
        OriaFailure.GLASSES -> impact(emptySet(), setOf("perception", "audio", "navigation"),
            "Lunettes déconnectées · sorties arrêtées", "Reconnecter puis redémarrer Oria", true)
        OriaFailure.H264_STREAM -> impact(setOf("interface", "destination"), setOf("perception", "danger_audio"),
            "Flux H.264 interrompu", "Redémarrer une nouvelle session vidéo", true)
        OriaFailure.DECODER -> impact(setOf("interface", "destination"), setOf("perception", "danger_audio"),
            "Décodeur indisponible", "Recréer le décodeur et la session vidéo", true)
        OriaFailure.DETECTOR -> impact(setOf("interface", "navigation"), setOf("perception", "danger_audio"),
            "Détection indisponible", "Recharger le modèle puis redémarrer Oria", true)
        OriaFailure.DEPTH -> impact(setOf("rgb", "tracking", "danger_audio", "navigation"), setOf("depth"),
            "Profondeur estimée indisponible · fallback RGB", "Réactiver la profondeur hors session", false)
        OriaFailure.BLUETOOTH_AUDIO -> impact(setOf("perception", "tracking", "navigation_visual"),
            setOf("speech", "danger_tones"), "Sortie Bluetooth VIVE interrompue",
            "Rétablir la route puis redémarrer la session audio", true)
        OriaFailure.MICROPHONE -> impact(setOf("perception", "danger_audio", "navigation"), setOf("voice_commands"),
            "Microphone indisponible · commandes à l’écran conservées", "Rendre le microphone puis relancer l’écoute", false)
        OriaFailure.GPS -> impact(setOf("perception", "danger_audio", "route_display"), setOf("fresh_navigation_speech"),
            "Position GPS perdue · instructions suspendues", "Attendre une position fraîche", false)
        OriaFailure.NETWORK -> impact(setOf("perception", "local_voice", "current_route"), setOf("new_online_route"),
            "Réseau indisponible · perception et voix locale actives", "Rétablir le réseau ou utiliser la simulation", false)
        OriaFailure.ROUTE_PROVIDER -> impact(setOf("perception", "local_voice", "destination"),
            setOf("navigation_speech"), "Fournisseur d’itinéraire indisponible",
            "Relancer le calcul avec une nouvelle version de route", false)
    }

    private fun impact(preserved: Set<String>, cut: Set<String>, message: String, recovery: String,
                       generation: Boolean) = OriaFailureImpact(preserved, cut, message, recovery, generation)
}

/** Generation fence shared by fault-injection tests. Recovery never accepts a pre-failure output. */
class OriaRecoveryGate(initialGeneration: Long = 0) {
    var generation: Long = initialGeneration
        private set

    fun fail(failure: OriaFailure): Long {
        if (OriaFailurePolicy.impact(failure).requiresNewGeneration) generation++
        return generation
    }

    fun restart(): Long = ++generation
    fun accepts(outputGeneration: Long): Boolean = outputGeneration == generation
}
