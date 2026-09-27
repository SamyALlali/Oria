package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.htc.vive.eagle.hackathon.starter.oria.dashboard.*
import java.util.Locale

enum class OriaDashboardMode { PRODUCT, DEVELOPER }
private val DashboardTeal = Color(0xFF086D65)

@Composable
fun ProductDashboardCard(
    connected: Boolean,
    running: Boolean,
    status: String,
    destination: String,
    nextInstruction: String,
    lastAlert: String,
) {
    val summary = "Oria. ${if (connected) "Lunettes connectées" else "Lunettes déconnectées"}. " +
        "${if (running) "Perception active" else "Perception arrêtée"}. $status. " +
        "Destination $destination. Prochaine instruction $nextInstruction. Dernière alerte $lastAlert."
    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = summary
            liveRegion = LiveRegionMode.Polite
        },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape,
                    color = if (running) Color(0xFF1F8A70) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(14.dp)) {}
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (running) "Oria vous accompagne" else "Oria est en pause",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (connected) "Lunettes connectées" else "Connexion aux lunettes requise",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
                }
            }

            Text(status, style = MaterialTheme.typography.bodyLarge)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProductMetric("Destination", destination, Modifier.weight(1f))
                ProductMetric("Prochaine étape", nextInstruction, Modifier.weight(1f))
            }

            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dernière information", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(lastAlert, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProductMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.heightIn(min = 96.dp), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2)
        }
    }
}

@Composable
fun DeveloperDashboardCard(snapshot: OriaDashboardSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VUE DÉVELOPPEUR · SNAPSHOT ${snapshot.sequence}",
                style = MaterialTheme.typography.labelLarge, color = DashboardTeal)
            Text("État : ${snapshot.overall.name.lowercase()} · ${snapshot.headline}",
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics {
                    contentDescription = snapshot.accessibleSummary
                    liveRegion = LiveRegionMode.Polite
                })
            Text("Décision : ${snapshot.decision.selected} · niveau ${snapshot.decision.dangerLevel}")
            Text("Source : ${snapshot.decision.source}\nRaison politique : ${snapshot.decision.policyReason}\n" +
                "Stabilisation : ${snapshot.decision.stabilizationReason}\nSuppression : ${snapshot.decision.suppressionReason}",
                style = MaterialTheme.typography.bodySmall)
            snapshot.decision.estimatedRelativeProximityPercent?.let {
                Text("Estimation de proximité relative : $it % · estimation de confiance profondeur : " +
                    "${snapshot.decision.estimatedDepthConfidencePercent ?: 0} %",
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Objets et pistes (${snapshot.objects.size})", fontWeight = FontWeight.Bold)
            if (snapshot.objects.isEmpty()) Text("Aucune piste visible", style = MaterialTheme.typography.bodySmall)
            snapshot.objects.take(8).forEach { item ->
                Text("Piste ${item.trackId} · ${item.label} · confiance ${item.confidencePercent} % · " +
                    "zone ${item.zone} · ${if (item.confirmed) "confirmée" else "en confirmation"} · " +
                    "boîte ${item.box.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.2f", it) }}",
                    style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Navigation ${snapshot.navigation.mode} · ${snapshot.navigation.state}", fontWeight = FontWeight.Bold)
            Text("Destination : ${snapshot.navigation.destination}\nGPS : ${snapshot.navigation.gps}\n" +
                "Instruction : ${snapshot.navigation.nextInstruction}\nVersion route : ${snapshot.navigation.routeVersion}\n" +
                "Préemptions danger : ${snapshot.navigation.preemptions} · reprises fraîches : ${snapshot.navigation.resumptions}",
                style = MaterialTheme.typography.bodySmall)
            snapshot.navigation.estimatedRemainingMeters?.let {
                Text("Distance de navigation estimée : $it m", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            val m = snapshot.metrics
            Text("Pipeline", fontWeight = FontWeight.Bold)
            Text("Cadence utile ${String.format(Locale.FRANCE, "%.1f", m.usefulFps)} Hz · reçues ${m.receivedFrames} · " +
                "analysées ${m.analyzedFrames} · abandonnées ${m.abandonedFrames} · périmées ${m.staleFrames}\n" +
                "Âge observation ${m.observationAgeMs} ms · latence p50 ${m.latencyP50Ms} ms · p95 ${m.latencyP95Ms} ms · " +
                "p95 toutes inférences ${m.allInferenceP95Ms} ms\nPrétraitement ${m.preprocessMs} ms · modèle ${m.inferenceMs} ms · " +
                "profondeur estimée ${m.estimatedDepthInferenceMs} ms · décision→audio p50 ${m.decisionToAudioP50Ms} ms · " +
                "p95 ${m.decisionToAudioP95Ms} ms · abandons audio ${m.audioDrops}",
                style = MaterialTheme.typography.bodySmall)
            Text("Charge : profil ${m.loadProfile} · CPU ${String.format(Locale.FRANCE, "%.1f", m.cpuPercent)} % · " +
                "mémoire ${m.memoryUsedMb} Mo\nDashboard : ${m.dashboardPublications} publications · " +
                "coût moyen ${m.dashboardAverageBuildMicros} µs",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Modèle : ${snapshot.health.model}\nAudio : ${snapshot.health.audio}\n" +
                "Batterie téléphone : ${snapshot.health.phoneBatteryPercent?.let { "$it %" } ?: "indisponible"}\n" +
                "Température téléphone : ${snapshot.health.phoneTemperatureCelsius?.let { String.format(Locale.FRANCE, "%.1f °C", it) } ?: "indisponible"}\n" +
                "État thermique Android : ${snapshot.health.thermalStatus ?: "indisponible"}\n" +
                "Dernière erreur : ${snapshot.health.lastError ?: "aucune"}\nOria Lab : ${snapshot.replay}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun previewSnapshot(state: DashboardOverallState, headline: String,
                            navigation: String = "arrêtée", danger: String = "Aucun") =
    OriaDashboardSnapshot.initial().copy(sequence = 7, overall = state, headline = headline,
        accessibleSummary = "Oria. ${state.name}. $headline.",
        decision = OriaDashboardSnapshot.initial().decision.copy(selected = danger,
            dangerLevel = if (danger == "Aucun") "aucun" else "élevé"),
        navigation = OriaDashboardSnapshot.initial().navigation.copy(state = navigation,
            destination = if (navigation == "arrêtée") "Aucune" else "Gare de Lyon",
            nextInstruction = if (navigation == "arrêtée") "Aucune" else "Tournez à droite"),
        health = OriaDashboardSnapshot.initial().health.copy(phoneBatteryPercent = 72,
            phoneTemperatureCelsius = 34.2))

@Preview(name = "Actif") @Composable private fun ActivePreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.ACTIVE, "Perception active"))
@Preview(name = "Limité") @Composable private fun LimitedPreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.LIMITED, "Images non fraîches"))
@Preview(name = "Déconnecté") @Composable private fun DisconnectedPreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.DISCONNECTED, "Lunettes déconnectées"))
@Preview(name = "Navigation") @Composable private fun NavigationPreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.ACTIVE, "Navigation active", "recalcul"))
@Preview(name = "Danger prioritaire") @Composable private fun DangerPreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.ACTIVE, "Priorité : véhicule devant", "préemptée par danger", "Véhicule devant"))
@Preview(name = "Replay") @Composable private fun ReplayPreview() = DeveloperDashboardCard(
    previewSnapshot(DashboardOverallState.REPLAY, "Relecture Oria Lab"))
