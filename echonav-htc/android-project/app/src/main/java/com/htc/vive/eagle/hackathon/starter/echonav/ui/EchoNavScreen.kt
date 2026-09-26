package com.htc.vive.eagle.hackathon.starter.echonav.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.echonav.EchoNavController
import com.htc.vive.eagle.hackathon.starter.echonav.EchoNavVoiceBackend
import com.htc.vive.eagle.hackathon.starter.echonav.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.echonav.recording.EchoTestPhase
import java.util.Locale

private val Ink = Color(0xFF101A23)
private val Paper = Color(0xFFF5F6F2)
private val Teal = Color(0xFF086D65)

@Composable
fun EchoNavScreen(controller: EchoNavController, onStart: () -> Unit, onOpenHtcDiagnostics: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val capture by controller.echoTestState.collectAsStateWithLifecycle()
    var diagnostics by rememberSaveable { mutableStateOf(false) }
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
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Paper, surface = Color.White, onSurface = Ink)) {
        Scaffold(
            containerColor = Paper,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Surface(color = Color.White, shadowElevation = 6.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onStart, enabled = state.connected && state.modelReady && !state.running,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Démarrer") }
                            OutlinedButton(onClick = { controller.stop() }, enabled = state.running,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Arrêter") }
                        }
                    }
                }
            },
        ) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Oria", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(20.dp), color = Ink) {
                        Text("SILMO • PROTOTYPE", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp, 8.dp))
                    }
                }
                Text("Les objets autour de vous,\nannoncés dans vos lunettes.", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onOpenHtcDiagnostics) { Text("Diagnostic HTC") }
                if (capture.phase == EchoTestPhase.RECORDING || capture.phase == EchoTestPhase.FINALIZING) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE5DD))) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (capture.phase == EchoTestPhase.RECORDING) "● Oria Lab enregistre · ${capture.elapsedMs / 1000} s" else "Oria Lab finalise la capture", fontWeight = FontWeight.Bold)
                            Text("${capture.frames} images · ${capture.detail}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = controller::stopEchoTest, enabled = capture.phase == EchoTestPhase.RECORDING) { Text("Arrêter et finaliser") }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.simulator) "SIMULATEUR HTC" else "LUNETTES HTC · CAMÉRA RÉELLE", style = MaterialTheme.typography.labelLarge, color = Teal)
                        Text(if (state.connected) "Lunettes connectées" else "Lunettes déconnectées", fontWeight = FontWeight.Bold)
                        if (!state.connected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = simulatorChoice, onCheckedChange = { simulatorChoice = it })
                                Text("Utiliser le simulateur", modifier = Modifier.padding(start = 8.dp))
                            }
                            Button(onClick = { controller.connect(simulatorChoice) }, modifier = Modifier.fillMaxWidth()) { Text("Connecter") }
                        } else {
                            TextButton(onClick = { controller.disconnect() }) { Text("Déconnecter") }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = if (state.running) Color(0xFFE0F0E8) else Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (state.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.modelReady) "Modèle chargé • calcul sur le téléphone" else "Le modèle doit être prêt avant de démarrer.", style = MaterialTheme.typography.bodyMedium)
                        Text("Caméra seule : catégories et directions, sans mesure de distance.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Ink)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DERNIÈRE DEMANDE VOCALE", color = Color(0xFFA9D8CC), style = MaterialTheme.typography.labelMedium)
                        Text(state.lastAlert, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(state.audio, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        if (state.voiceBackend == EchoNavVoiceBackend.BLUETOOTH) {
                            Text(if (state.simulator) "Sortie Bluetooth réservée aux lunettes réelles" else state.localVoiceStatus,
                                color = Color(0xFFA9D8CC), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.audioAutomaticPaused && !state.audioUnknown) {
                            Text("Annonces automatiques suspendues. Tester la voix pour les réactiver.",
                                color = Color(0xFFFFD4AB), style = MaterialTheme.typography.bodySmall)
                        }
                        val voiceTestEnabled = state.connected && !state.audioBusy && !state.audioUnknown &&
                            (state.voiceBackend == EchoNavVoiceBackend.HTC || (state.localVoiceReady && !state.simulator))
                        if (state.voiceBackend == EchoNavVoiceBackend.BLUETOOTH) {
                            Text("Voix orientée selon l’objet. Tester les côtés :", color = Color.White,
                                style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(SpeechPan.LEFT to "Gauche", SpeechPan.CENTER to "Centre", SpeechPan.RIGHT to "Droite").forEach { (pan, label) ->
                                    OutlinedButton(onClick = { controller.testVoice(pan) }, enabled = voiceTestEnabled,
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(label) }
                                }
                            }
                        } else {
                            OutlinedButton(onClick = { controller.testVoice() }, enabled = voiceTestEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Tester la voix") }
                        }
                        if (state.audioUnknown) Text("La voix est suspendue : une réponse HTC n’a pas pu être attribuée. La phrase déjà envoyée peut continuer.", color = Color(0xFFFFD4AB))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.orientationVerified, onCheckedChange = controller::confirmOrientation)
                    Text("J’ai vérifié gauche / droite dans l’aperçu et le test vocal. Autoriser les annonces d’objets.", style = MaterialTheme.typography.bodyMedium)
                }
                if (!state.orientationVerified) Text("Vérifiez l’aperçu avec un objet de chaque côté, puis les boutons vocaux Gauche / Droite avant d’autoriser les annonces.", style = MaterialTheme.typography.bodySmall)
                if (state.running) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(previewAspectRatio).background(Ink),
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
                TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Masquer les réglages" else "Réglages Oria") }
                if (diagnostics) {
                    HorizontalDivider()
                    Text("Sortie vocale", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.voiceBackend == EchoNavVoiceBackend.BLUETOOTH,
                            enabled = !state.running && !state.audioBusy && !state.audioUnknown,
                            onClick = { controller.setVoiceBackend(EchoNavVoiceBackend.BLUETOOTH) },
                            label = { Text("Bluetooth VIVE") })
                        FilterChip(selected = state.voiceBackend == EchoNavVoiceBackend.HTC,
                            enabled = !state.running && !state.audioBusy && !state.audioUnknown,
                            onClick = { controller.setVoiceBackend(EchoNavVoiceBackend.HTC) },
                            label = { Text("HTC · diagnostic") })
                    }
                    Text("La voix HTC est réservée au diagnostic sans vidéo. La voix locale utilise la sortie Bluetooth VIVE détectée.",
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
                        Switch(checked = state.xnnpack, enabled = !state.running && !state.modelLoading, onCheckedChange = controller::loadModel)
                        Text("XNNPACK · accélération locale", Modifier.padding(start = 8.dp))
                    }
                    Text("Images reçues pour analyse : ${state.received}\nRésultats frais : ${state.analyzed} · périmés : ${state.stale}\nCadence utile : ${String.format(Locale.FRANCE, "%.1f", state.fps)} Hz\nRéception → décision : ${state.lastLatencyMs} ms · p95 accepté ${state.p95Ms} ms\nP95 toutes inférences : ${state.allInferenceP95Ms} ms\nPrétraitement : ${state.preprocessMs.toInt()} ms · modèle : ${state.inferenceMs.toInt()} ms\nPolitique : ${state.suppression}", style = MaterialTheme.typography.bodySmall)
                    Text("La réception est mesurée sur le téléphone. Le délai de capture et le son audible restent à mesurer séparément.", style = MaterialTheme.typography.bodySmall)
                    if (!state.xnnpack) Text("CPU expérimental : un échec de parité stricte a été observé sur le téléphone précédent.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { controller.loadModel(state.xnnpack) }, enabled = !state.running && !state.modelLoading) { Text("Recharger le modèle") }
                }
                Text("Oria × VIVE Eagle · Démonstration contrôlée", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
            }
        }
    }
}
