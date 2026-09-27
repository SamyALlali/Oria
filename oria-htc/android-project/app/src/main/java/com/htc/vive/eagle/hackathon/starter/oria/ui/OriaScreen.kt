package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.OriaVoiceBackend
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbTrackingMode
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import java.util.Locale

@Composable
fun OriaScreen(controller: OriaController, onStart: () -> Unit,
               onDictateQuery: () -> Unit = {}, onVoiceCommand: () -> Unit = {},
               onLocationPermission: () -> Unit = {}, onOpenHtcDiagnostics: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val capture by controller.oriaLabState.collectAsStateWithLifecycle()
    val navigation by controller.navigation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var previewExpanded by rememberSaveable { mutableStateOf(false) }
    var simulatorChoice by rememberSaveable { mutableStateOf(false) }
    // Preserve layout when freshness removes the bitmap; never retain old pixels or boxes.
    var lastPreviewAspectRatio by remember(state.running, state.rotation, state.simulator) {
        mutableFloatStateOf(if (state.rotation % 180 == 0) 480f / 856f else 856f / 480f)
    }
    val currentPreviewAspectRatio = state.preview?.let { it.width.toFloat() / it.height }
    val previewAspectRatio = currentPreviewAspectRatio ?: lastPreviewAspectRatio
    SideEffect {
        if (state.running && currentPreviewAspectRatio != null) {
            lastPreviewAspectRatio = currentPreviewAspectRatio
        }
    }
    val modelsReady = state.modelReady && state.obstacleModelReady
    val modelsLoading = state.modelLoading || state.obstacleModelLoading
    val canStart = state.connected && modelsReady && !modelsLoading && !state.running &&
        !state.pocketPreparing && !capture.storageBusy &&
        (state.simulator || (state.localVoiceReady && !state.audioUnknown && !state.audioBusy))
    val voiceBlock = when {
        state.audioUnknown -> "Voix suspendue · réponse HTC incertaine"
        state.audioAutomaticPaused -> "Annonces suspendues · reprise disponible ci-dessous"
        state.voiceBackend == OriaVoiceBackend.BLUETOOTH && state.simulator -> "Voix Bluetooth indisponible avec le simulateur"
        state.voiceBackend == OriaVoiceBackend.BLUETOOTH && !state.localVoiceReady -> state.localVoiceStatus
        else -> null
    }
    val startHelp = when {
        state.running -> when {
            voiceBlock != null -> "Vidéo active · $voiceBlock"
            else -> "Oria est en marche"
        }
        state.pocketPreparing -> "Préparation du fonctionnement écran verrouillé…"
        capture.storageBusy -> "Attendez la fin de l’enregistrement"
        !state.connected -> "Connectez vos lunettes pour commencer"
        modelsLoading -> "Préparation d’Oria…"
        !modelsReady -> "Analyse indisponible · consultez l’état ci-dessous"
        voiceBlock != null -> voiceBlock
        else -> "Prête à démarrer"
    }

    OriaUiTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { if (state.running || state.pocketPreparing) controller.stop() else onStart() },
                            enabled = state.running || state.pocketPreparing || canStart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.running || state.pocketPreparing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (state.running || state.pocketPreparing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text(if (state.running || state.pocketPreparing) "Arrêter Oria" else "Démarrer Oria",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
        ) { contentPadding ->
            Column(
                Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Votre environnement, à l’écoute", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("Les objets et les obstacles possibles, annoncés ensemble dans vos lunettes.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OriaHomeSection {
                    Text(if (state.simulator) "Simulation" else "Vos lunettes",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    OriaSectionTitle(if (state.connected) {
                        if (state.simulator) "Simulateur connecté" else "Lunettes connectées"
                    } else "Lunettes déconnectées")
                    Text(startHelp, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (!state.obstacleModelReady) Text(state.obstacleStatus, style = MaterialTheme.typography.bodyMedium)
                    if (modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!state.connected) {
                        Text("Allumez vos lunettes et connectez-les dans VIVE Connect, puis revenez ici.",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { controller.connect(simulatorChoice) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(16.dp)) {
                            Text(if (simulatorChoice) "Connecter le simulateur" else "Connecter les lunettes")
                        }
                    } else {
                        OutlinedButton(onClick = controller::disconnect,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(16.dp)) {
                            Text(if (state.simulator) "Déconnecter le simulateur" else "Déconnecter les lunettes")
                        }
                    }
                    if (state.simulator) {
                        Text("Les images proviennent du simulateur. La voix Bluetooth exige les lunettes réelles.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OriaHomeSection {
                    OriaExperienceSummary()
                    if (state.pocketPreparing || state.pocketActive || state.running) {
                        Text(state.pocketStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OriaNavigationPanel(navigation, OriaNavigationActions(
                    updateQuery = controller.navigation::updateQuery,
                    search = { query, simulated -> controller.navigation.search(query,
                        if (simulated) NavigationMode.SIMULATED else NavigationMode.REAL) },
                    select = controller.navigation::select,
                    confirm = { id ->
                        if (navigation.mode == NavigationMode.REAL && !controller.navigation.hasLocationPermission(context))
                            onLocationPermission()
                        else controller.navigation.confirmSelected(id)
                    },
                    pause = controller.navigation::pause, resume = controller.navigation::resume,
                    stop = { controller.navigation.stop() }, advanceSimulation = controller.navigation::advanceSimulation,
                    networkConsent = controller.navigation::setNetworkConsent, dictateQuery = onDictateQuery,
                    save = { name, address, id -> controller.navigation.saveDestination(name, address, id) },
                    delete = { controller.navigation.deleteDestination(it) },
                    startSaved = { destination, simulated -> controller.navigation.startSaved(destination,
                        if (simulated) NavigationMode.SIMULATED else NavigationMode.REAL) },
                ))
                OriaHomeSection {
                    OriaSectionTitle("Commande vocale")
                    Text("Dites « pause navigation », « reprends la navigation » ou « guide-moi vers… ». La dictée utilise le téléphone et peut nécessiter Internet.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onVoiceCommand, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Dicter une commande avec le téléphone")
                    }
                    Text("Bouton IA des lunettes : un appui annonce l’état d’Oria ; deux appuis ouvrent la dictée quand l’application est visible.",
                        style = MaterialTheme.typography.bodyMedium)
                }

                if (capture.phase == OriaLabPhase.RECORDING || capture.phase == OriaLabPhase.FINALIZING) {
                    OriaHomeSection(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        OriaSectionTitle(if (capture.phase == OriaLabPhase.RECORDING)
                            "Oria Lab enregistre" else "Finalisation de la capture")
                        Text(if (capture.phase == OriaLabPhase.RECORDING) "${capture.elapsedMs / 1000} secondes enregistrées" else capture.detail,
                            style = MaterialTheme.typography.bodyLarge)
                        Text("${capture.frames} images · ${capture.detail}", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = controller::stopOriaLab, enabled = capture.phase == OriaLabPhase.RECORDING,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
                            Text("Arrêter et finaliser la capture")
                        }
                    }
                }

                OriaHomeSection(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    OriaSectionTitle("Dernière annonce demandée")
                    Text(state.lastAlert, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(state.audio, style = MaterialTheme.typography.bodyMedium)
                    OriaAudioRecovery(
                        paused = state.audioAutomaticPaused,
                        unknown = state.audioUnknown,
                        connected = state.connected,
                        busy = state.audioBusy,
                        onResume = controller::resumeAutomaticVoice,
                    )
                }

                OriaHomeSection {
                    OriaHomeDisclosure("Aperçu de la caméra", previewExpanded, { previewExpanded = !previewExpanded })
                    if (previewExpanded) {
                        Text("Images reçues des lunettes et objets reconnus. Aucune distance n’est mesurée.",
                            style = MaterialTheme.typography.bodyLarge)
                        if (state.running) {
                            Box(Modifier.fillMaxWidth().aspectRatio(previewAspectRatio).background(Color(0xFF112A2D)),
                                contentAlignment = Alignment.Center) {
                                val bitmap = state.preview
                                if (bitmap != null) {
                                    Image(bitmap.asImageBitmap(), "Aperçu de la caméra des lunettes", Modifier.fillMaxSize(),
                                        contentScale = ContentScale.FillBounds)
                                    Canvas(Modifier.fillMaxSize()) {
                                        state.detections.forEach { detection ->
                                            val b = detection.box
                                            drawRect(Color(0xFF63FFC3), Offset(b.left * size.width, b.top * size.height),
                                                Size(b.width * size.width, b.height * size.height), style = Stroke(2.dp.toPx()))
                                        }
                                        listOf(.39f, .61f).forEach { x ->
                                            drawLine(Color.White.copy(alpha = .6f), Offset(x * size.width, 0f),
                                                Offset(x * size.width, size.height), 1.dp.toPx())
                                        }
                                    }
                                } else {
                                    Text("En attente d’une image fraîche", color = Color.White, modifier = Modifier.padding(24.dp))
                                }
                            }
                        } else Text("Démarrez Oria pour afficher la caméra.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                OriaHomeSection {
                    OriaHomeDisclosure("Réglages avancés et diagnostic", diagnostics, { diagnostics = !diagnostics })
                    if (diagnostics) {
                        if (!state.connected) {
                            OriaSettingToggle("Utiliser le simulateur HTC", simulatorChoice, { simulatorChoice = it })
                            Text("Activez ce réglage avant de connecter le simulateur.", style = MaterialTheme.typography.bodyMedium)
                        }
                        OriaSectionTitle("Voix dans les lunettes")
                        Text(state.localVoiceStatus, style = MaterialTheme.typography.bodyMedium)
                        OriaSectionTitle("Suivi des objets")
                        OriaSettingToggle(
                            checked = state.trackingMode == RgbTrackingMode.STABLE_RGB_V2,
                            onCheckedChange = { controller.setTrackingMode(if (it) RgbTrackingMode.STABLE_RGB_V2 else RgbTrackingMode.LEGACY_IOU) },
                            title = "Suivi stable V2 · expérimental",
                            enabled = !state.running && !state.pocketPreparing && !state.audioBusy && !state.audioUnknown,
                        )
                        Text("Le suivi habituel reste activé par défaut. Comparez les deux modes dans Oria Lab avant un essai terrain.",
                            style = MaterialTheme.typography.bodyMedium)
                        OriaSettingToggle("XNNPACK · accélération des objets", state.xnnpack, controller::loadModel,
                            enabled = !state.running && !state.modelLoading)
                        Text("Images reçues pour analyse : ${state.received}\nRésultats frais : ${state.analyzed} · périmés : ${state.stale}\nCadence utile : ${String.format(Locale.FRANCE, "%.1f", state.fps)} Hz\nRéception → décision : ${state.lastLatencyMs} ms · p95 accepté ${state.p95Ms} ms\nP95 toutes inférences : ${state.allInferenceP95Ms} ms\nPrétraitement : ${state.preprocessMs.toInt()} ms · modèle : ${state.inferenceMs.toInt()} ms\nPolitique : ${state.suppression}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Obstacles possibles : ${state.obstacleStatus}\nÂge de l’image analysée : ${String.format(Locale.FRANCE, "%.0f", state.obstacleLatencyMs)} ms",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("La réception est mesurée sur le téléphone. Le délai de capture et le son audible restent à mesurer séparément.",
                            style = MaterialTheme.typography.bodyMedium)
                        if (!state.xnnpack) Text("CPU expérimental : un échec de parité stricte a été observé sur le téléphone précédent.",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { controller.loadModel(state.xnnpack) }, enabled = !state.running && !state.modelLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
                            Text("Recharger le modèle d’objets")
                        }
                        OutlinedButton(onClick = onOpenHtcDiagnostics, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(16.dp)) { Text("Ouvrir le diagnostic HTC") }
                    }
                }
                Text("Oria × VIVE Eagle · Prototype", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OriaHomeSection(
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = containerColor) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun OriaHomeDisclosure(label: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .semantics { heading(); stateDescription = if (expanded) "Développé" else "Replié" },
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (expanded) "Masquer" else "Afficher", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Read-only guidance: detection and background operation require no user mode selection. */
@Composable
internal fun OriaExperienceSummary() {
    OriaSectionTitle("Annonces automatiques")
    Text("Piétons, véhicules et obstacles possibles", style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold)
    Text("Les annonces sont automatiques. Le son favorise la direction annoncée : avant-gauche, devant ou avant-droite.",
        style = MaterialTheme.typography.bodyLarge)
    Text("Vous pouvez verrouiller le téléphone : Oria continue tant que la connexion aux lunettes reste active. Arrêtez Oria avec le bouton ci-dessous ou sa notification.",
        style = MaterialTheme.typography.bodyMedium)
    Text("L’estimation des obstacles par caméra reste expérimentale. Le silence ne garantit pas un passage libre.",
        style = MaterialTheme.typography.bodyMedium)
}

/** A failed local playback can be retried; an ambiguous HTC delivery cannot be cleared here. */
@Composable
internal fun OriaAudioRecovery(
    paused: Boolean,
    unknown: Boolean,
    connected: Boolean,
    busy: Boolean,
    onResume: () -> Unit,
) {
    if (unknown) {
        Text("La voix est suspendue : une réponse HTC n’a pas pu être attribuée. La phrase déjà envoyée peut continuer. Consultez le diagnostic HTC.",
            style = MaterialTheme.typography.bodyLarge)
    } else if (paused) {
        Text("Les annonces sont suspendues après une erreur audio.",
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onResume, enabled = connected && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
            Text("Reprendre les annonces")
        }
    }
}
