package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.htc.vive.eagle.hackathon.starter.oria.navigation.*
import kotlin.math.roundToInt

/** Additive navigation actions. The host owns permission and correlated phone dictation requests. */
data class OriaNavigationActions(
    val updateQuery: (String) -> Unit,
    val search: (String, Boolean) -> Unit,
    val select: (GeocodedPlace) -> Unit,
    val confirm: (String) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val stop: () -> Unit,
    val advanceSimulation: () -> Unit,
    val networkConsent: (Boolean) -> Unit,
    val dictateQuery: () -> Unit,
    val save: (String, String, String?) -> Unit,
    val delete: (String) -> Unit,
    val startSaved: (SavedDestination, Boolean) -> Unit,
)

@Composable
fun OriaNavigationPanel(state: NavigationUiState, actions: OriaNavigationActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var simulated by rememberSaveable { mutableStateOf(state.mode == NavigationMode.SIMULATED) }
    var destinationName by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.mode) { simulated = state.mode == NavigationMode.SIMULATED }
    val busy = state.phase in setOf(NavigationPhase.SEARCHING, NavigationPhase.CALCULATING, NavigationPhase.RECALCULATING)
    val routeVisible = state.phase in setOf(NavigationPhase.ACTIVE, NavigationPhase.PAUSED, NavigationPhase.LIMITED, NavigationPhase.RECALCULATING, NavigationPhase.ARRIVED)
    val canSearch = state.foreground && (simulated || state.networkConsent) && !busy

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Navigation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            Text("Le GPS fonctionne avec Oria visible. Écran verrouillé, le trajet se met en pause ; les alertes d’objets et d’obstacles continuent si Oria est démarré.",
                style = MaterialTheme.typography.bodyMedium)
            if (routeVisible) {
                Text(if (state.mode == NavigationMode.SIMULATED) "Démo simulée · aucun GPS" else "Trajet réel · GPS du téléphone",
                    style = MaterialTheme.typography.labelLarge)
                state.selected?.let { Text(it.label, style = MaterialTheme.typography.bodyLarge) }
                state.currentInstruction?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                state.remainingMeters?.let { Text("Environ ${it.roundToInt()} m à vol d’oiseau de la prochaine étape", style = MaterialTheme.typography.bodyMedium) }
                if (state.phase == NavigationPhase.PAUSED) {
                    OutlinedButton(onClick = actions.resume, enabled = state.foreground,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Reprendre la navigation") }
                } else if (state.phase != NavigationPhase.ARRIVED) {
                    OutlinedButton(onClick = actions.pause, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Mettre la navigation en pause") }
                }
                if (state.mode == NavigationMode.SIMULATED && state.simulatedStepAvailable) {
                    Button(onClick = actions.advanceSimulation, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Avancer d’une étape dans la démo") }
                }
            }
            if (state.phase != NavigationPhase.IDLE) {
                OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (busy) "Annuler la préparation du trajet" else "Arrêter la navigation")
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (expanded) "Masquer les destinations" else "Choisir une destination")
            }
            if (expanded || state.phase == NavigationPhase.CONFIRMATION) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = simulated, enabled = !busy,
                        onClick = { simulated = true }, label = { Text("Démo sans réseau") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                    FilterChip(selected = !simulated, enabled = !busy,
                        onClick = { simulated = false }, label = { Text("Trajet piéton réel") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                }
                if (!simulated) {
                    Text("La recherche transmet l’adresse au géocodeur Android. Le calcul transmet votre position et la destination au service public FOSSGIS/OSRM, qui journalise les demandes.",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(state.networkConsent, role = Role.Checkbox, onValueChange = actions.networkConsent),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(state.networkConsent, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
                        Text("Autoriser ces recherches réseau pour cette session", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(value = state.query, onValueChange = actions.updateQuery,
                    label = { Text("Adresse ou lieu") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedButton(onClick = actions.dictateQuery, enabled = state.foreground && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Dicter avec le téléphone") }
                Text("La dictée utilise le service vocal installé sur le téléphone et peut nécessiter Internet. Vérifiez le texte avant de rechercher.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { actions.search(state.query, simulated) }, enabled = canSearch && state.query.trim().length >= 3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (simulated) "Préparer la démo" else "Rechercher cette destination")
                }
                state.suggestions.forEach { place ->
                    FilterChip(selected = state.selected?.id == place.id, onClick = { actions.select(place) },
                        label = { Text(place.label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
                }
                if (state.phase == NavigationPhase.CONFIRMATION) state.selected?.let { place ->
                    Button(onClick = { actions.confirm(place.id) }, enabled = state.foreground,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Confirmer cette destination") }
                }
                HorizontalDivider()
                Text("Destinations enregistrées sur ce téléphone", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                state.savedDestinations.forEach { destination ->
                    Text(destination.name, style = MaterialTheme.typography.titleMedium)
                    Text(destination.address, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { actions.startSaved(destination, simulated) }, enabled = canSearch,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Rechercher ${destination.name}") }
                    TextButton(onClick = { editingId = destination.id; destinationName = destination.name; actions.updateQuery(destination.address) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Modifier ${destination.name}") }
                    TextButton(onClick = { actions.delete(destination.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Supprimer ${destination.name}") }
                }
                OutlinedTextField(destinationName, { destinationName = it.take(100) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom à enregistrer, par exemple Maison") })
                OutlinedButton(onClick = { actions.save(destinationName, state.query, editingId) },
                    enabled = destinationName.isNotBlank() && state.query.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (editingId == null) "Enregistrer la destination" else "Enregistrer la modification")
                }
            }
            state.attribution?.let { attribution ->
                Text(attribution, style = MaterialTheme.typography.bodySmall)
                if (state.mode == NavigationMode.REAL) {
                    TextButton(onClick = { uriHandler.openUri(OsrmFootRouteProvider.FIX_MAP_URL) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Signaler une erreur de carte OpenStreetMap") }
                }
            }
        }
    }
}
