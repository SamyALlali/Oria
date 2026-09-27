package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.OriaVoiceBackend
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbTrackingMode
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import java.util.Locale

@Composable
fun OriaScreen(controller: OriaController, onStart: () -> Unit, onOpenHtcDiagnostics: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val capture by controller.oriaLabState.collectAsStateWithLifecycle()
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var preparationExpanded by rememberSaveable { mutableStateOf(true) }
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
    val selectedModelReady = if (state.obstacleMode) state.obstacleModelReady else state.modelReady
    val selectedModelLoading = if (state.obstacleMode) state.obstacleModelLoading else state.modelLoading
    val canSelectMode = !state.running && !state.pocketPreparing && !state.modelLoading &&
        !state.obstacleModelLoading && !state.audioBusy && !state.audioUnknown && !capture.storageBusy
    val canStart = state.connected && selectedModelReady && !selectedModelLoading && !state.running &&
        !state.pocketPreparing && !capture.storageBusy
    val voiceTestEnabled = state.connected && !state.audioBusy && !state.audioUnknown &&
        (state.voiceBackend == OriaVoiceBackend.HTC || (state.localVoiceReady && !state.simulator))
    val voiceBlock = when {
        state.audioUnknown -> "Voix suspendue · réponse HTC incertaine"
        state.audioAutomaticPaused -> "Annonces suspendues · testez la voix"
        state.voiceBackend == OriaVoiceBackend.BLUETOOTH && state.simulator -> "Voix Bluetooth indisponible avec le simulateur"
        state.voiceBackend == OriaVoiceBackend.BLUETOOTH && !state.localVoiceReady -> "Voix indisponible · consultez les réglages"
        else -> null
    }
    val startHelp = when {
        state.running -> when {
            voiceBlock != null -> "Vidéo active · $voiceBlock"
            !state.orientationVerified -> "Vidéo active · annonces à autoriser"
            else -> "Oria est en marche"
        }
        state.pocketPreparing -> "Préparation du mode poche…"
        capture.storageBusy -> "Attendez la fin de l’enregistrement"
        !state.connected -> "Connectez vos lunettes pour commencer"
        selectedModelLoading -> if (state.obstacleMode) "Préparation du mode obstacles…" else "Préparation d’Oria…"
        !selectedModelReady -> if (state.obstacleMode) "Mode obstacles indisponible · consultez son état ci-dessous" else "Oria n’est pas prête · voir les réglages"
        voiceBlock != null -> voiceBlock
        !state.orientationVerified -> "Démarrez pour vérifier les directions"
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
                    Text(if (state.obstacleMode) "Les obstacles possibles et leurs directions, annoncés dans vos lunettes."
                        else "Les objets et leurs directions, annoncés dans vos lunettes.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OriaHomeSection {
                    Text(if (state.simulator) "Simulation" else "Vos lunettes",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    OriaSectionTitle(if (state.connected) {
                        if (state.simulator) "Simulateur connecté" else "Lunettes connectées"
                    } else "Lunettes déconnectées")
                    Text(startHelp, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (state.obstacleMode) state.obstacleStatus else state.status,
                        style = MaterialTheme.typography.bodyMedium)
                    if (selectedModelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    OriaSectionTitle("Choisir ce qu’Oria annonce")
                    OriaPerceptionSelector(
                        obstacleMode = state.obstacleMode,
                        enabled = canSelectMode,
                        onObstacleModeChange = controller::setObstacleMode,
                    )
                    Text("Un seul mode fonctionne à la fois.", style = MaterialTheme.typography.bodyMedium)
                    if (state.obstacleMode) {
                        Text("Obstacle possible · sans distance mesurée", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(state.obstacleStatus, style = MaterialTheme.typography.bodyMedium)
                        if (state.running && state.suppression.isNotBlank()) {
                            Text("Décision : ${state.suppression}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.obstacleLatencyMs > 0.0) {
                            Text("Âge de l’image analysée : ${String.format(Locale.FRANCE, "%.0f", state.obstacleLatencyMs)} ms",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("La caméra estime le relief. Une image sombre ou peu structurée peut rendre l’analyse incertaine. Le silence ne garantit pas un passage libre.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!canSelectMode) Text(
                        when {
                            state.running || state.pocketPreparing -> "Arrêtez Oria pour changer de mode."
                            state.audioUnknown -> "Le changement de mode est suspendu : une demande vocale reste incertaine."
                            state.audioBusy -> "Attendez la fin de la phrase pour changer de mode."
                            state.modelLoading || state.obstacleModelLoading -> "Attendez la fin du chargement pour changer de mode."
                            else -> "Attendez la fin de la capture pour changer de mode."
                        }, style = MaterialTheme.typography.bodyMedium)
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

                OriaHomeSection {
                    OriaHomeDisclosure(
                        label = "Préparer les annonces",
                        expanded = preparationExpanded,
                        onClick = { preparationExpanded = !preparationExpanded },
                    )
                    Text(if (state.orientationVerified) "Directions autorisées" else "Une vérification est nécessaire",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (preparationExpanded) {
                        Text("1. Écouter la voix", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text("Écoutez les tests dans les lunettes. Le son favorise le côté annoncé et reste présent dans les deux oreilles.",
                            style = MaterialTheme.typography.bodyLarge)
                        if (state.voiceBackend == OriaVoiceBackend.BLUETOOTH) {
                            listOf(SpeechPan.LEFT to "Tester la voix à gauche", SpeechPan.CENTER to "Tester la voix au centre",
                                SpeechPan.RIGHT to "Tester la voix à droite").forEach { (pan, label) ->
                                OutlinedButton(onClick = { controller.testVoice(pan) }, enabled = voiceTestEnabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
                                    Text(label)
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { controller.testVoice() }, enabled = voiceTestEnabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(16.dp)) {
                                Text("Tester la voix")
                            }
                        }
                        if (!voiceTestEnabled) {
                            Text(when {
                                !state.connected -> "Connectez les lunettes pour écouter le test."
                                state.audioUnknown -> "Le test est indisponible : une réponse vocale HTC reste incertaine."
                                state.audioBusy -> "Attendez la fin de la phrase en cours."
                                state.simulator -> "Le test Bluetooth nécessite les lunettes réelles."
                                else -> state.localVoiceStatus
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("2. Vérifier les directions", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text("Démarrez Oria, puis ouvrez l’aperçu ci-dessous. Faites vérifier, avec un accompagnant si besoin, qu’un objet placé à gauche puis à droite apparaît du bon côté. Vérifiez aussi les tests vocaux.",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("Cochez seulement après ces vérifications. Sans validation, les annonces automatiques restent désactivées.",
                            style = MaterialTheme.typography.bodyMedium)
                        OriaHomeCheck(
                            checked = state.orientationVerified,
                            onCheckedChange = controller::confirmOrientation,
                            label = "Les directions de l’image et de la voix ont été vérifiées. J’autorise les annonces.",
                        )
                    }
                }

                OriaHomeSection(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    OriaSectionTitle("Dernière annonce demandée")
                    Text(state.lastAlert, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(state.audio, style = MaterialTheme.typography.bodyMedium)
                    if (state.audioAutomaticPaused && !state.audioUnknown) {
                        Text("Annonces automatiques suspendues. Testez la voix pour les réactiver.",
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (state.audioUnknown) {
                        Text("La voix est suspendue : une réponse HTC n’a pas pu être attribuée. La phrase déjà envoyée peut continuer.",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }

                OriaHomeSection {
                    OriaSectionTitle("Utiliser Oria en poche")
                    OriaSettingToggle(
                        checked = state.pocketEnabled,
                        onCheckedChange = controller::setPocketMode,
                        enabled = !state.running && !state.pocketPreparing,
                        title = "Continuer écran verrouillé",
                    )
                    Text(state.pocketStatus, style = MaterialTheme.typography.bodyLarge)
                    Text("Mode expérimental, sans limite de durée. Une coupure de connexion peut arrêter Oria. Oria Lab s’arrête toujours en arrière-plan.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (state.running || state.pocketPreparing) Text("Arrêtez Oria pour modifier ce réglage.",
                        style = MaterialTheme.typography.bodyMedium)
                }

                OriaHomeSection {
                    OriaHomeDisclosure("Aperçu de la caméra", previewExpanded, { previewExpanded = !previewExpanded })
                    if (previewExpanded) {
                        Text("Pour vérifier le sens de l’image, avec un accompagnant si besoin. Aucune distance n’est mesurée.",
                            style = MaterialTheme.typography.bodyLarge)
                        if (state.running) {
                            Box(Modifier.fillMaxWidth().aspectRatio(previewAspectRatio).background(Color(0xFF112A2D)),
                                contentAlignment = Alignment.Center) {
                                val bitmap = state.preview
                                if (bitmap != null) {
                                    Image(bitmap.asImageBitmap(), "Aperçu de la caméra des lunettes", Modifier.fillMaxSize(),
                                        contentScale = ContentScale.FillBounds)
                                    Canvas(Modifier.fillMaxSize()) {
                                        if (!state.obstacleMode) state.detections.forEach { detection ->
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
                        OriaSectionTitle("Sortie vocale")
                        listOf(OriaVoiceBackend.BLUETOOTH to "Bluetooth VIVE", OriaVoiceBackend.HTC to "HTC · diagnostic").forEach { (backend, label) ->
                            FilterChip(selected = state.voiceBackend == backend,
                                enabled = !state.running && !state.audioBusy && !state.audioUnknown,
                                onClick = { controller.setVoiceBackend(backend) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), label = { Text(label) })
                        }
                        Text("La voix HTC est réservée au diagnostic sans vidéo. La voix locale utilise la sortie Bluetooth VIVE détectée.",
                            style = MaterialTheme.typography.bodyMedium)
                        if (state.voiceBackend == OriaVoiceBackend.BLUETOOTH) Text(state.localVoiceStatus,
                            style = MaterialTheme.typography.bodyMedium)
                        OriaSectionTitle("Suivi des objets")
                        OriaSettingToggle(
                            checked = state.trackingMode == RgbTrackingMode.STABLE_RGB_V2,
                            onCheckedChange = { controller.setTrackingMode(if (it) RgbTrackingMode.STABLE_RGB_V2 else RgbTrackingMode.LEGACY_IOU) },
                            title = "Suivi stable V2 · expérimental",
                            enabled = !state.obstacleMode && !state.running && !state.pocketPreparing && !state.audioBusy && !state.audioUnknown,
                        )
                        Text("Le suivi habituel reste activé par défaut. Comparez les deux modes dans Oria Lab avant un essai terrain.",
                            style = MaterialTheme.typography.bodyMedium)
                        OriaSectionTitle("Repère de la caméra")
                        listOf(0, 90, 180, 270).forEach { rotation ->
                            FilterChip(selected = state.rotation == rotation,
                                onClick = { controller.setGeometry(rotation, state.mirrored) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                label = { Text("Rotation : $rotation degrés") })
                        }
                        OriaSettingToggle("Corriger une image miroir", state.mirrored, { controller.setGeometry(state.rotation, it) })
                        OriaSettingToggle("XNNPACK · accélération des objets", state.xnnpack, controller::loadModel,
                            enabled = !state.obstacleMode && !state.running && !state.modelLoading)
                        Text("Images reçues pour analyse : ${state.received}\nRésultats frais : ${state.analyzed} · périmés : ${state.stale}\nCadence utile : ${String.format(Locale.FRANCE, "%.1f", state.fps)} Hz\nRéception → décision : ${state.lastLatencyMs} ms · p95 accepté ${state.p95Ms} ms\nP95 toutes inférences : ${state.allInferenceP95Ms} ms\nPrétraitement : ${state.preprocessMs.toInt()} ms · modèle : ${state.inferenceMs.toInt()} ms\nPolitique : ${state.suppression}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("La réception est mesurée sur le téléphone. Le délai de capture et le son audible restent à mesurer séparément.",
                            style = MaterialTheme.typography.bodyMedium)
                        if (!state.obstacleMode && !state.xnnpack) Text("CPU expérimental : un échec de parité stricte a été observé sur le téléphone précédent.",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { controller.loadModel(state.xnnpack) }, enabled = !state.obstacleMode && !state.running && !state.modelLoading,
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

@Composable
private fun OriaHomeCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}


/** Exclusive, named radio choices; each complete row remains one accessible touch target. */
@Composable
internal fun OriaPerceptionSelector(
    obstacleMode: Boolean,
    enabled: Boolean,
    onObstacleModeChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple(false, "Objets", "Personnes, véhicules, deux-roues et poteaux."),
            Triple(true, "Obstacles caméra · expérimental", "Relief seul, indépendant des catégories d’objets."),
        ).forEach { (obstacles, label, description) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .selectable(selected = obstacleMode == obstacles, enabled = enabled,
                        role = Role.RadioButton, onClick = { if (obstacleMode != obstacles) onObstacleModeChange(obstacles) })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = obstacleMode == obstacles, enabled = enabled, onClick = null,
                    modifier = Modifier.clearAndSetSemantics { })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
