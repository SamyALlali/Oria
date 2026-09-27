package com.htc.vive.eagle.hackathon.starter.oria.ui

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.OriaInteractionPhase
import com.htc.vive.eagle.hackathon.starter.oria.OriaVoiceBackend
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationMode
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationPhase
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun OriaScreen(controller: OriaController, onStart: () -> Unit, onOpenHtcDiagnostics: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val capture by controller.oriaLabState.collectAsStateWithLifecycle()
    val navigation by controller.navigationState.collectAsStateWithLifecycle()
    val interaction by controller.interactionState.collectAsStateWithLifecycle()
    val dashboard by controller.dashboardState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var simulatorChoice by rememberSaveable { mutableStateOf(false) }
    var navigationSimulated by rememberSaveable { mutableStateOf(true) }
    var destinationName by rememberSaveable { mutableStateOf("") }
    var editingDestinationId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAfterPermission by rememberSaveable { mutableStateOf(false) }
    var navigationExpanded by rememberSaveable { mutableStateOf(false) }
    var dashboardMode by rememberSaveable { mutableStateOf(OriaDashboardMode.PRODUCT) }
    LaunchedEffect(dashboardMode) {
        controller.setDeveloperDashboardVisible(dashboardMode == OriaDashboardMode.DEVELOPER)
    }
    DisposableEffect(controller) {
        onDispose { controller.setDeveloperDashboardVisible(false) }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && confirmAfterPermission) controller.confirmNavigationDestination()
        confirmAfterPermission = false
    }
    val voiceDestination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) controller.updateNavigationQuery(text)
    }
    // Preserve layout when freshness removes the bitmap; never retain old pixels or boxes.
    var lastPreviewAspectRatio by remember(state.running, state.rotation, state.simulator) {
        mutableFloatStateOf(if (state.rotation % 180 == 0) 480f / 856f else 856f / 480f)
    }
    val currentPreviewAspectRatio = state.preview?.let { it.width.toFloat() / it.height }
    val previewAspectRatio = currentPreviewAspectRatio ?: lastPreviewAspectRatio
    val canStart = state.connected && state.modelReady && !state.running &&
        !state.pocketPreparing && !state.depthLoading &&
        (!state.depthEnabled || state.depthReady) && !capture.storageBusy
    SideEffect {
        if (state.running && currentPreviewAspectRatio != null) {
            lastPreviewAspectRatio = currentPreviewAspectRatio
        }
    }
    OriaUiTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (state.running) "Protection active" else state.status,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = { if (state.running || state.pocketPreparing) controller.stop() else onStart() },
                            enabled = state.running || state.pocketPreparing || canStart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.running || state.pocketPreparing)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (state.running || state.pocketPreparing) "Arrêter Oria" else "Démarrer Oria",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
        ) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OriaHero(
                    connected = state.connected,
                    running = state.running,
                    developer = dashboardMode == OriaDashboardMode.DEVELOPER,
                    onToggleMode = {
                        dashboardMode = if (dashboardMode == OriaDashboardMode.PRODUCT)
                            OriaDashboardMode.DEVELOPER else OriaDashboardMode.PRODUCT
                    },
                )
                if (dashboardMode == OriaDashboardMode.PRODUCT) {
                    ProductDashboardCard(state.connected, state.running, state.status,
                        navigation.selected?.label ?: "Aucune",
                        navigation.currentInstruction ?: "Aucune", state.lastAlert)
                } else {
                    DeveloperDashboardCard(dashboard)
                }
                if (capture.phase == OriaLabPhase.RECORDING || capture.phase == OriaLabPhase.FINALIZING) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (capture.phase == OriaLabPhase.RECORDING) "● Oria Lab enregistre · ${capture.elapsedMs / 1000} s" else "Oria Lab finalise la capture", fontWeight = FontWeight.Bold)
                            Text("${capture.frames} images · ${capture.detail}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = controller::stopOriaLab, enabled = capture.phase == OriaLabPhase.RECORDING) { Text("Arrêter et finaliser") }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.simulator) "Simulateur" else "Vos lunettes",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (state.connected) "Connectées et prêtes" else "Connexion nécessaire",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!state.connected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = simulatorChoice, onCheckedChange = { simulatorChoice = it })
                                Text("Utiliser le simulateur", modifier = Modifier.padding(start = 8.dp))
                            }
                            Button(onClick = { controller.connect(simulatorChoice) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Connecter les lunettes") }
                        } else {
                            TextButton(onClick = { controller.disconnect() }) { Text("Déconnecter") }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Commandes vocales", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(interaction.status, fontWeight = FontWeight.Bold)
                        Text("Bouton IA : un appui répète l’information active ou décrit devant ; deux appuis lancent une commande vocale.",
                            style = MaterialTheme.typography.bodySmall)
                        interaction.lastTranscript?.let { Text("Entendu : $it", style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = controller::beginVoiceCommand,
                            enabled = state.connected && interaction.phase !in setOf(
                                OriaInteractionPhase.LISTENING, OriaInteractionPhase.EXECUTING),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Parler à Oria") }
                    }
                }
                if (dashboardMode == OriaDashboardMode.DEVELOPER) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (state.running)
                        MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (state.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (state.modelReady) "Modèle chargé • calcul sur le téléphone" else "Le modèle doit être prêt avant de démarrer.", style = MaterialTheme.typography.bodyMedium)
                            Text(if (state.depthEnabled) "Profondeur relative expérimentale : aucun nombre de mètres."
                                else "Caméra seule : catégories et directions, sans mesure de distance.",
                                style = MaterialTheme.typography.bodySmall)
                            if (state.depthLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.depthStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Navigation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(navigation.status, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (navigation.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.PAUSED,
                                    NavigationPhase.LIMITED, NavigationPhase.RECALCULATING)) {
                                TextButton(onClick = { navigationExpanded = !navigationExpanded }) {
                                    Text(if (navigationExpanded) "Réduire" else "Choisir un trajet")
                                }
                            }
                        }
                        if (navigationExpanded || navigation.phase in setOf(NavigationPhase.SEARCHING,
                                NavigationPhase.CONFIRMATION, NavigationPhase.CALCULATING, NavigationPhase.FAILED)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !navigationSimulated, onClick = { navigationSimulated = false },
                                label = { Text("Trajet réel") })
                            FilterChip(selected = navigationSimulated, onClick = { navigationSimulated = true },
                                label = { Text("Trajet simulé") })
                            }
                            if (navigationSimulated) Text("Simulation déterministe clairement séparée du GPS réel.",
                                style = MaterialTheme.typography.bodySmall)
                            else Text("Le trajet réel utilise la position Android, le géocodage du téléphone et envoie origine/destination au service public OSRM. Usage de démonstration, sans garantie de disponibilité.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = navigation.query, onValueChange = controller::updateNavigationQuery,
                                label = { Text("Où souhaitez-vous aller ?") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                shape = RoundedCornerShape(18.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.searchNavigation(navigation.query, navigationSimulated) },
                                enabled = navigation.query.trim().length >= 3,
                                modifier = Modifier.weight(1f)) { Text("Rechercher") }
                            OutlinedButton(onClick = {
                                voiceDestination.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictez une destination")
                                })
                            }, modifier = Modifier.weight(1f)) { Text("Dicter") }
                            }
                            navigation.suggestions.forEach { place ->
                                FilterChip(selected = navigation.selected?.id == place.id,
                                    onClick = { controller.selectNavigationSuggestion(place) },
                                    label = { Text(place.label) }, modifier = Modifier.fillMaxWidth())
                            }
                            if (navigation.phase == NavigationPhase.CONFIRMATION && navigation.selected != null) {
                                Button(onClick = {
                                if (navigation.mode == NavigationMode.SIMULATED || controller.hasNavigationLocationPermission()) {
                                    controller.confirmNavigationDestination()
                                } else {
                                    confirmAfterPermission = true
                                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Confirmer et calculer") }
                            }
                        }
                        if (navigation.phase in setOf(NavigationPhase.ACTIVE, NavigationPhase.PAUSED,
                                NavigationPhase.LIMITED, NavigationPhase.RECALCULATING, NavigationPhase.ARRIVED)) {
                            navigation.currentInstruction?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                            navigation.remainingMeters?.let { Text("Environ ${it.roundToInt()} m avant la prochaine instruction") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (navigation.phase == NavigationPhase.PAUSED) {
                                    Button(onClick = controller::resumeNavigation, modifier = Modifier.weight(1f)) { Text("Reprendre") }
                                } else {
                                    OutlinedButton(onClick = controller::pauseNavigation,
                                        enabled = navigation.phase == NavigationPhase.ACTIVE,
                                        modifier = Modifier.weight(1f)) { Text("Pause") }
                                }
                                OutlinedButton(onClick = controller::stopNavigation,
                                    modifier = Modifier.weight(1f)) { Text("Arrêter") }
                            }
                            if (navigation.mode == NavigationMode.SIMULATED && navigation.simulatedStepAvailable) {
                                Button(onClick = controller::advanceSimulatedNavigation,
                                    modifier = Modifier.fillMaxWidth()) { Text("Étape simulée suivante") }
                            }
                        }
                        if (navigationExpanded) {
                            navigation.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            HorizontalDivider()
                            Text("Destinations enregistrées", fontWeight = FontWeight.Bold)
                            navigation.savedDestinations.forEach { destination ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(destination.name, fontWeight = FontWeight.SemiBold)
                                Text(destination.address, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { controller.startSavedNavigation(destination, navigationSimulated) }) {
                                        Text("Guider")
                                    }
                                    TextButton(onClick = {
                                        editingDestinationId = destination.id; destinationName = destination.name
                                        controller.updateNavigationQuery(destination.address)
                                    }) { Text("Modifier") }
                                    TextButton(onClick = { controller.deleteNavigationDestination(destination.id) }) {
                                        Text("Supprimer")
                                    }
                                }
                                }
                            }
                            OutlinedTextField(value = destinationName, onValueChange = { destinationName = it },
                                label = { Text("Nom de la destination") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                shape = RoundedCornerShape(18.dp))
                            TextButton(onClick = {
                            if (controller.saveNavigationDestination(destinationName, navigation.query, editingDestinationId).isSuccess) {
                                destinationName = ""; editingDestinationId = null
                            }
                            }, enabled = destinationName.isNotBlank() && navigation.query.isNotBlank()) {
                                Text(if (editingDestinationId == null) "Enregistrer cette destination" else "Enregistrer la modification")
                            }
                        }
                    }
                }
                if (dashboardMode == OriaDashboardMode.DEVELOPER) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
                    Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DERNIÈRE DEMANDE VOCALE", color = Color(0xFFA9D8CC), style = MaterialTheme.typography.labelMedium)
                        Text(state.lastAlert, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
                        Text(state.audio, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
                        if (state.voiceBackend == OriaVoiceBackend.BLUETOOTH) {
                            Text(if (state.simulator) "Sortie Bluetooth réservée aux lunettes réelles" else state.localVoiceStatus,
                                color = Color(0xFFA9D8CC), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.audioAutomaticPaused && !state.audioUnknown) {
                            Text("Annonces automatiques suspendues. Tester la voix pour les réactiver.",
                                color = Color(0xFFFFD4AB), style = MaterialTheme.typography.bodySmall)
                        }
                        val voiceTestEnabled = state.connected && !state.audioBusy && !state.audioUnknown &&
                            (state.voiceBackend == OriaVoiceBackend.HTC || (state.localVoiceReady && !state.simulator))
                        if (state.voiceBackend == OriaVoiceBackend.BLUETOOTH) {
                            Text("Voix orientée selon l’objet. Tester les côtés :", color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(SpeechPan.LEFT to "Gauche", SpeechPan.CENTER to "Centre", SpeechPan.RIGHT to "Droite").forEach { (pan, label) ->
                                    OutlinedButton(onClick = { controller.testVoice(pan) }, enabled = voiceTestEnabled,
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) { Text(label) }
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { controller.testVoice() }, enabled = voiceTestEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) { Text("Tester la voix") }
                        }
                        if (state.audioUnknown) Text("La voix est suspendue : une réponse HTC n’a pas pu être attribuée. La phrase déjà envoyée peut continuer.", color = Color(0xFFFFD4AB))
                    }
                }
                }
                if (dashboardMode == OriaDashboardMode.DEVELOPER) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = state.pocketEnabled, onCheckedChange = controller::setPocketMode,
                                enabled = !state.running && !state.pocketPreparing)
                            Text("Mode poche · expérimental", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                        }
                        Text(state.pocketStatus, style = MaterialTheme.typography.bodySmall)
                        Text("Continuer écran verrouillé jusqu’à votre arrêt ou une interruption de connexion. À valider sur HTC. Oria Lab s’arrête toujours en arrière-plan.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                }
                if (dashboardMode == OriaDashboardMode.DEVELOPER || !state.orientationVerified) {
                    Surface(shape = RoundedCornerShape(22.dp), color = if (state.orientationVerified)
                        MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = state.orientationVerified, onCheckedChange = controller::confirmOrientation)
                                Text("Autoriser les annonces d’objets", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                            }
                            if (!state.orientationVerified) Text("Vérifiez gauche et droite dans l’aperçu et avec le test vocal avant d’activer les annonces.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.running && dashboardMode == OriaDashboardMode.DEVELOPER) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(previewAspectRatio).background(Color(0xFF112A2D)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = state.preview
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), "Aperçu de la caméra des lunettes", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            Canvas(Modifier.fillMaxSize()) {
                                state.detections.forEach { detection ->
                                    val b = detection.box
                                    drawRect(Color(0xFF63FFC3), Offset(b.left * size.width, b.top * size.height),
                                        Size(b.width * size.width, b.height * size.height), style = Stroke(2.dp.toPx()))
                                }
                                listOf(.39f, .61f).forEach { x -> drawLine(Color.White.copy(alpha = .6f), Offset(x * size.width, 0f), Offset(x * size.width, size.height), 1.dp.toPx()) }
                            }
                        } else {
                            Text("En attente d’image fraîche", color = Color.White, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
                if (dashboardMode == OriaDashboardMode.DEVELOPER) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { diagnostics = !diagnostics }, modifier = Modifier.weight(1f)) {
                            Text(if (diagnostics) "Masquer les réglages" else "Réglages avancés")
                        }
                        OutlinedButton(onClick = onOpenHtcDiagnostics, modifier = Modifier.weight(1f)) {
                            Text("Diagnostic HTC")
                        }
                    }
                }
                if (diagnostics && dashboardMode == OriaDashboardMode.DEVELOPER) {
                    HorizontalDivider()
                    Text("Sortie vocale", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.voiceBackend == OriaVoiceBackend.BLUETOOTH,
                            enabled = !state.running && !state.audioBusy && !state.audioUnknown,
                            onClick = { controller.setVoiceBackend(OriaVoiceBackend.BLUETOOTH) },
                            label = { Text("Bluetooth VIVE") })
                        FilterChip(selected = state.voiceBackend == OriaVoiceBackend.HTC,
                            enabled = !state.running && !state.audioBusy && !state.audioUnknown,
                            onClick = { controller.setVoiceBackend(OriaVoiceBackend.HTC) },
                            label = { Text("HTC · diagnostic") })
                    }
                    Text("La voix HTC est réservée au diagnostic sans vidéo. La voix locale utilise la sortie Bluetooth VIVE détectée.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.audioSilent, onCheckedChange = controller::setAudioSilent)
                        Text("Mode silencieux · perception active", Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.dangerTonesEnabled,
                            enabled = state.voiceBackend == OriaVoiceBackend.BLUETOOTH && !state.audioSilent,
                            onCheckedChange = controller::setDangerTonesEnabled)
                        Text("Bips de danger · expérimental", Modifier.padding(start = 8.dp))
                    }
                    Text("Les bips restent désactivés par défaut jusqu’à validation de confort. ${state.audioQueueStatus}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Suivi des objets", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.trackingMode == com.htc.vive.eagle.hackathon.starter.oria.core.RgbTrackingMode.STABLE_RGB_V2,
                            enabled = !state.running && !state.pocketPreparing && !state.audioBusy && !state.audioUnknown,
                            onCheckedChange = { controller.setTrackingMode(if (it)
                                com.htc.vive.eagle.hackathon.starter.oria.core.RgbTrackingMode.STABLE_RGB_V2
                                else com.htc.vive.eagle.hackathon.starter.oria.core.RgbTrackingMode.LEGACY_IOU) })
                        Text("Suivi stable V2 · expérimental", Modifier.padding(start = 8.dp))
                    }
                    Text("Le suivi habituel reste activé par défaut. Comparez les deux modes dans Oria Lab avant un essai terrain.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Profondeur monoculaire", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.depthEnabled, enabled = !state.running && !state.pocketPreparing &&
                            !state.depthLoading, onCheckedChange = controller::setDepthEnabled)
                        Text("MiDaS relatif · expérimental", Modifier.padding(start = 8.dp))
                    }
                    Text("Option désactivée par défaut. Elle estime seulement un ordre proche/loin et revient au RGB si la carte est absente, périmée ou peu fiable.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.approachEnabled, enabled = !state.running && state.depthEnabled &&
                            state.depthReady && !state.depthLoading, onCheckedChange = controller::setApproachEnabled)
                        Text("Approche relative · expérimental", Modifier.padding(start = 8.dp))
                    }
                    Text("Désactivée par défaut. Aucun TTC ni distance métrique n’est déduit de la caméra.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Repère de la caméra", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0, 90, 180, 270).forEach { rotation ->
                            FilterChip(selected = state.rotation == rotation, onClick = { controller.setGeometry(rotation, state.mirrored) }, label = { Text("$rotation°") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.mirrored, onCheckedChange = { controller.setGeometry(state.rotation, it) })
                        Text("Corriger une image miroir", Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = state.xnnpack, enabled = !state.running && !state.modelLoading &&
                            !state.depthEnabled && !state.depthLoading, onCheckedChange = controller::loadModel)
                        Text("XNNPACK · accélération locale", Modifier.padding(start = 8.dp))
                    }
                    Text("Images reçues pour analyse : ${state.received}\nRésultats frais : ${state.analyzed} · périmés : ${state.stale}\nCadence utile : ${String.format(Locale.FRANCE, "%.1f", state.fps)} Hz\nRéception → décision : ${state.lastLatencyMs} ms · p95 accepté ${state.p95Ms} ms\nP95 toutes inférences : ${state.allInferenceP95Ms} ms\nPrétraitement : ${state.preprocessMs.toInt()} ms · modèle : ${state.inferenceMs.toInt()} ms\nTemps du modèle de profondeur estimée : ${state.depthInferenceMs.toInt()} ms\nPolitique : ${state.suppression}", style = MaterialTheme.typography.bodySmall)
                    Text(state.resolutionStatus, style = MaterialTheme.typography.bodySmall)
                    Text("La réception est mesurée sur le téléphone. Le délai de capture et le son audible restent à mesurer séparément.", style = MaterialTheme.typography.bodySmall)
                    if (!state.xnnpack) Text("CPU expérimental : un échec de parité stricte a été observé sur le téléphone précédent.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { controller.loadModel(state.xnnpack) }, enabled = !state.running &&
                        !state.modelLoading && !state.depthEnabled && !state.depthLoading) { Text("Recharger le modèle") }
                }
                Text("Oria × VIVE Eagle · Démonstration contrôlée", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OriaHero(
    connected: Boolean,
    running: Boolean,
    developer: Boolean,
    onToggleMode: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .10f),
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .18f),
                        RoundedCornerShape(50)),
                ) {
                    Text("ECHONAV  ·  ORIA", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50),
                    color = if (connected) Color(0xFFB9F6D2).copy(alpha = .18f)
                    else MaterialTheme.colorScheme.error.copy(alpha = .16f)) {
                    Text(if (connected) "● Connecté" else "○ Hors ligne",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Le monde autour,\nenfin audible.",
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text("Oria reconnaît, priorise et annonce l’information utile dans vos lunettes.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .78f))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp),
                    color = if (running) Color(0xFF42D3A2).copy(alpha = .18f)
                    else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = .08f),
                    modifier = Modifier.weight(1f)) {
                    Text(if (running) "● Perception active" else "Perception en pause",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(14.dp))
                }
                TextButton(onClick = onToggleMode,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface)) {
                    Text(if (developer) "Accueil" else "Mode expert")
                }
            }
        }
    }
}
