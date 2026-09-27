package com.htc.vive.eagle.hackathon.starter.oria.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.core.Detection
import com.htc.vive.eagle.hackathon.starter.oria.core.RgbCategory
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabGallery
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabGalleryIndex
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabGalleryAnalysis
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TestInk = Color(0xFF101A23)

@Composable
fun OriaLabScreen(controller: OriaController, onRecord: () -> Unit, onOpenHtcDiagnostics: () -> Unit) {
    val live by controller.state.collectAsStateWithLifecycle()
    val recording by controller.oriaLabState.collectAsStateWithLifecycle()
    val selectedModelReady = if (live.obstacleMode) live.obstacleModelReady else live.modelReady
    val scope = rememberCoroutineScope()
    var simulatorChoice by rememberSaveable { mutableStateOf(false) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    var managementStatus by remember { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var frameIndex by rememberSaveable { mutableIntStateOf(0) }
    var showCaptureHelp by rememberSaveable { mutableStateOf(false) }
    val recordingActive = recording.phase == OriaLabPhase.RECORDING
    val finalizing = recording.phase == OriaLabPhase.FINALIZING
    val storageBusy = recording.storageBusy || exporting || managing || pendingExport != null
    val canManage = !live.running && !recordingActive && !finalizing && !storageBusy
    fun mutate(success: String, action: suspend () -> Unit) {
        managing = true
        managementStatus = "Mise à jour de la capture…"
        scope.launch {
            try { action(); managementStatus = success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { managementStatus = "Opération interrompue : ${e.message}" }
            finally { managing = false }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExport
        pendingExport = null
        if (uri != null && id != null) scope.launch {
            exporting = true
            exportStatus = "Création et copie du ZIP…"
            try {
                controller.exportOriaLab(id, uri)
                exportStatus = "ZIP exporté vers l’emplacement choisi."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportStatus = "Export interrompu : ${e.message}" }
            finally { exporting = false }
        }
    }
    val selectedSession = recording.savedSessions.firstOrNull { it.id == selectedSessionId }
    val loadedGallery by produceState(GalleryLoad(), selectedSession?.id) {
        value = GalleryLoad(selectedSession?.id, OriaLabGalleryIndex(loading = selectedSession != null))
        value = selectedSession?.let { session ->
            val index = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                OriaLabGallery.load(session.directory) { context.ensureActive() }
            }
            GalleryLoad(session.id, index)
        } ?: GalleryLoad()
    }
    // produceState retains its previous value until the new coroutine begins. Gate it by identity.
    val gallery = loadedGallery.takeIf { it.sessionId == selectedSession?.id }?.index
        ?: OriaLabGalleryIndex(loading = selectedSession != null)
    val safeIndex = frameIndex.coerceIn(0, (gallery.frames.size - 1).coerceAtLeast(0))
    val selectedFrame = gallery.frames.getOrNull(safeIndex)
    val entryKey = selectedFrame?.let { GalleryEntryKey(selectedSession!!.id, it.lineNumber) }
    val loadedReview by produceState(GalleryReview(), entryKey) {
        value = GalleryReview(entryKey, loading = entryKey != null)
        if (selectedFrame != null && selectedSession != null) {
            value = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val analysis = OriaLabGallery.readFrame(selectedSession.directory, selectedFrame) { context.ensureActive() }
                val preview = selectedFrame.image?.let(::decodeGalleryPreview)
                    ?: GalleryPreview(error = "PNG indisponible · entrée d’origine conservée")
                context.ensureActive()
                GalleryReview(entryKey, analysis, preview)
            }
        }
    }
    val review = loadedReview.takeIf { it.key == entryKey } ?: GalleryReview(entryKey, loading = entryKey != null)
    val reviewImage = review.preview.bitmap
    var lastAspectRatio by remember(live.running, live.rotation, live.simulator) {
        mutableFloatStateOf(if (live.rotation % 180 == 0) 480f / 856f else 856f / 480f)
    }
    val liveRatio = live.preview?.let { it.width.toFloat() / it.height } ?: lastAspectRatio
    SideEffect { if (live.preview != null) lastAspectRatio = liveRatio }

    OriaUiTheme {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!live.running && !recordingActive && !finalizing) {
                            Button(onClick = onRecord,
                                enabled = live.connected && selectedModelReady && !recordingActive && !finalizing && !storageBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Enregistrer une scène") }
                        }
                        if (recordingActive || live.running) {
                            OutlinedButton(onClick = controller::stopOriaLab,
                                enabled = recordingActive || live.running,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                Text(if (recordingActive) "Arrêter et finaliser" else "Arrêter la vidéo")
                            }
                        }
                        if (finalizing) Text("Finalisation de la capture…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Oria Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text("Enregistrez une scène. Retrouvez chaque image ici ou sur Mac.", style = MaterialTheme.typography.bodyLarge)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OriaSectionTitle("Source de la capture")
                        Text(if (live.simulator) "Simulateur HTC" else "Caméra des lunettes", style = MaterialTheme.typography.titleMedium)
                        Text(if (live.connected) "Lunettes connectées" else "Lunettes déconnectées", fontWeight = FontWeight.SemiBold)
                        if (!live.connected) {
                            OriaSettingToggle(title = "Utiliser le simulateur", checked = simulatorChoice,
                                onCheckedChange = { simulatorChoice = it })
                            Button(onClick = { controller.connect(simulatorChoice) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Connecter la source") }
                        }
                        Text(if (live.obstacleMode) live.obstacleStatus else if (selectedModelReady) "Modèle prêt" else "Chargement du modèle…")
                        if (live.running && !recordingActive && !finalizing) {
                            Button(onClick = onRecord,
                                enabled = live.connected && selectedModelReady && !recordingActive && !finalizing && !storageBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Enregistrer cette scène") }
                        }
                        OutlinedButton(onClick = onOpenHtcDiagnostics,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Ouvrir le diagnostic HTC") }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = if (recordingActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OriaSectionTitle(when (recording.phase) {
                            OriaLabPhase.RECORDING -> "Capture en cours"
                            OriaLabPhase.FINALIZING -> "Finalisation en cours"
                            OriaLabPhase.ERROR -> "Capture à vérifier"
                            OriaLabPhase.OFF -> "Capture arrêtée"
                        })
                        Text(recording.detail.ifBlank { "Aucun enregistrement en cours" }, style = MaterialTheme.typography.bodyLarge)
                        Text("Sans limite de durée. Arrêtez et finalisez quand vous le souhaitez.", style = MaterialTheme.typography.bodyMedium)
                        Text("${recording.elapsedMs / 1000} secondes · ${recording.frames} images", style = MaterialTheme.typography.titleMedium)
                        Text("${recording.packets} paquets vidéo", style = MaterialTheme.typography.bodySmall)
                        Text("Écrits : ${mib(recording.bytesWritten)} Mio · en attente : ${mib(recording.queuedBytes)} Mio", style = MaterialTheme.typography.bodySmall)
                        if (recording.incomplete) Text("Capture incomplète : consulter le motif et le manifeste exporté.", color = MaterialTheme.colorScheme.error)
                        if (finalizing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(live.status, style = MaterialTheme.typography.bodySmall)
                        Text("Images analysées fraîches : ${live.analyzed} · périmées : ${live.stale}", style = MaterialTheme.typography.bodySmall)
                        if (!live.orientationVerified) Text("Le repère n’est pas confirmé : les annonces automatiques restent désactivées. Vérifiez image et audio dans Oria.", style = MaterialTheme.typography.bodySmall)
                        Text("Dernière annonce : ${live.lastAlert}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (live.running) {
                    OriaSectionTitle("Vidéo en direct")
                    ReviewImage(live.preview, live.detections, liveRatio, "En attente d’image fraîche",
                        "Aperçu vidéo en direct")
                }
                HorizontalDivider()
                OriaSectionTitle("Captures enregistrées")
                if (live.running || recordingActive || finalizing) Text("Arrêtez la vidéo et attendez la finalisation avant de revoir ou exporter une capture.", style = MaterialTheme.typography.bodyMedium)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OriaSectionTitle("Stockage du téléphone")
                        Text("${mib(recording.availableStorageBytes)} Mio libres estimés", style = MaterialTheme.typography.titleMedium)
                        Text("Captures et ZIP : ${mib(recording.totalStorageBytes)} Mio\nCorbeille : ${mib(recording.trashBytes)} Mio")
                        Text("La capture s’arrête pour préserver 512 Mio libres, ou si la file d’écriture déborde. Aucune suppression automatique.", style = MaterialTheme.typography.bodyMedium)
                        Text("La corbeille conserve les données et ne libère pas d’espace.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (storageBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (managementStatus.isNotBlank()) Text(managementStatus, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (exportStatus.isNotBlank()) Text(exportStatus, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (recording.savedSessions.isEmpty()) Text("Aucune capture finalisée pour le moment.")
                recording.savedSessions.sortedByDescending { it.startedAtEpochMs }.forEach { session ->
                    val sessionName = session.displayName ?: sessionDate(session.startedAtEpochMs)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(sessionName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            if (session.displayName != null) Text(sessionDate(session.startedAtEpochMs), style = MaterialTheme.typography.bodySmall)
                            Text("${session.durationMs / 1000} s · ${session.frames} images · ${mib(session.bytes)} Mio")
                            Text(if (session.complete) "Finalisée · ${session.reason}" else "Incomplète · ${session.reason}", style = MaterialTheme.typography.bodySmall)
                            if (!session.usableForReplay) Text("Sans image exploitable pour le rejeu", style = MaterialTheme.typography.bodySmall)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { selectedSessionId = session.id; frameIndex = 0 },
                                    enabled = canManage, modifier = Modifier.captureAction("Revoir les images", sessionName)) { Text("Revoir les images") }
                                OutlinedButton(onClick = {
                                    pendingExport = session.id
                                    exportLauncher.launch("OriaLab-${session.id}.zip")
                                }, enabled = canManage, modifier = Modifier.captureAction("Exporter le ZIP", sessionName)) { Text("Exporter le ZIP") }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = {
                                    renameId = session.id
                                    renameText = session.displayName ?: sessionDate(session.startedAtEpochMs)
                                    renameError = null
                                }, enabled = canManage, modifier = Modifier.captureAction("Renommer", sessionName)) { Text("Renommer") }
                                TextButton(onClick = { archiveId = session.id }, enabled = canManage,
                                    modifier = Modifier.captureAction("Mettre à la corbeille", sessionName)) { Text("Mettre à la corbeille") }
                            }
                        }
                    }
                }
                if (recording.trashedSessions.isNotEmpty()) {
                    HorizontalDivider()
                    OriaSectionTitle("Corbeille locale · ${recording.trashedSessions.size}")
                    Text("Les captures restent sur ce téléphone jusqu’à leur restauration. Aucune suppression automatique.", style = MaterialTheme.typography.bodySmall)
                    recording.trashedSessions.forEach { session ->
                        val sessionName = session.displayName ?: sessionDate(session.startedAtEpochMs)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(sessionName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${session.durationMs / 1000} s · ${session.frames} images · ${mib(session.bytes)} Mio")
                                OutlinedButton(onClick = {
                                    mutate("Capture restaurée.") { controller.restoreOriaLab(session.id) }
                                }, enabled = canManage, modifier = Modifier.captureAction("Restaurer", sessionName)) { Text("Restaurer") }
                            }
                        }
                    }
                }
                if (selectedSession != null) {
                    HorizontalDivider()
                    OriaSectionTitle("Relecture de la capture")
                    Text(selectedSession.displayName ?: selectedSession.id, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { selectedSessionId = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Fermer la relecture") }
                    if (gallery.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (gallery.error != null) Text(gallery.error!!, color = MaterialTheme.colorScheme.error)
                    if (!gallery.loading) {
                        Text("PNG et détections sont vérifiées à la sélection, sans modifier les fichiers.", style = MaterialTheme.typography.bodySmall)
                        Text("${gallery.expectedFrames?.toString() ?: "?"} images annoncées · ${gallery.frames.size} entrées indexées\n" +
                            "${gallery.availableImageFiles} références PNG disponibles · ${gallery.missingImageFiles} références PNG absentes\n" +
                            "${gallery.invalidEntries} entrées d’index invalides · ${gallery.invalidEventLines} en-têtes d’événement invalides",
                            style = MaterialTheme.typography.bodySmall)
                        if (gallery.issueCount > 0) {
                            var showIssues by remember(selectedSession.id) { mutableStateOf(false) }
                            TextButton(onClick = { showIssues = !showIssues },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                                    stateDescription = if (showIssues) "Causes affichées" else "Causes masquées"
                                }) { Text("${gallery.issueCount} problèmes · ${if (showIssues) "masquer" else "afficher les causes"}") }
                            if (showIssues) Text(gallery.issues.joinToString("\n") +
                                if (gallery.issueCount > gallery.issues.size) "\nListe limitée aux ${gallery.issues.size} premières causes ; aucune entrée d’image n’est retirée." else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (!gallery.loading && gallery.frames.isEmpty()) Text("Aucune entrée dans l’index des images de cette capture.")
                    if (selectedFrame != null) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Image ${safeIndex + 1} sur ${gallery.frames.size}", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { frameIndex = (safeIndex - 1).coerceAtLeast(0) }, enabled = safeIndex > 0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Image précédente") }
                            OutlinedButton(onClick = { frameIndex = safeIndex + 1 }, enabled = safeIndex + 1 < gallery.frames.size,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Image suivante") }
                        }
                        Text("Ligne source ${selectedFrame.lineNumber} · vidéo ${selectedFrame.videoSessionId ?: "absente"} · image ${selectedFrame.frameId ?: "absente"}\n" +
                            "Réception ${selectedFrame.receivedAtMs?.let { "$it ms" } ?: "absente"}\n" +
                            when {
                                review.loading -> "Chargement de cette entrée…"
                                review.analysis.error != null -> review.analysis.error
                                review.analysis.analyzed -> "${review.analysis.detections.size} détections enregistrées · ${selectedFrame.decisionReason ?: "Sans décision fraîche"}"
                                else -> "Aucune analyse associée sans ambiguïté à cette entrée"
                            }, style = MaterialTheme.typography.bodySmall)
                        if (selectedFrame.issues.isNotEmpty()) Text(selectedFrame.issues.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (!review.loading && reviewImage == null && review.analysis.analyzed) {
                            Text(if (selectedFrame.image == null) "PNG indisponible, boîtes non superposées"
                                else "Image non décodée, boîtes non superposées", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Text("Détections enregistrées · coordonnées normalisées dans le repère caméra",
                                style = MaterialTheme.typography.bodySmall)
                            if (review.analysis.detections.isEmpty()) Text("Aucune détection dans l’inférence enregistrée.",
                                style = MaterialTheme.typography.bodySmall)
                            review.analysis.detections.forEachIndexed { index, detection ->
                                Text(recordedDetectionText(index, detection), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ReviewImage(reviewImage, if (reviewImage != null) review.analysis.detections else emptyList(),
                            reviewImage?.let { it.width.toFloat() / it.height } ?: 480f / 856f,
                            if (review.loading) "Chargement de l’image enregistrée…" else review.preview.error ?: "Image indisponible",
                            "Image enregistrée ${safeIndex + 1} sur ${gallery.frames.size}")
                    }
                }
                OutlinedCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showCaptureHelp = !showCaptureHelp },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                                stateDescription = if (showCaptureHelp) "Informations affichées" else "Informations masquées"
                            }) { Text(if (showCaptureHelp) "Masquer les informations" else "À propos des captures") }
                        if (showCaptureHelp) {
                            OriaSectionTitle("À propos des captures")
                            Text("L’enregistrement commence uniquement avec le bouton d’enregistrement. Il contient la vidéo, les images exactes analysées et la télémétrie, sans piste microphone.")
                            Text("Une nouvelle capture redémarre le flux pour conserver ses en-têtes vidéo. Le retour sur cet écran ne déclenche rien.")
                            Text("Le ZIP contient les fichiers de la session. Choisissez son emplacement avec le sélecteur Android ; aucun serveur n’est utilisé par Oria Lab.")
                            Text("La relecture conserve les positions et les diagnostics des données d’origine. Une image absente ne sera pas remplacée par une autre image.")
                        }
                    }
                }
            }
        }
        renameId?.let { id ->
            AlertDialog(onDismissRequest = { if (!managing) renameId = null },
                title = { Text("Renommer la capture") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = renameText, onValueChange = { renameText = it; renameError = null },
                            singleLine = true, label = { Text("Nom de la scène") }, enabled = !managing,
                            supportingText = { Text(renameError ?: "De 1 à 80 caractères. Les images et données restent intactes.") },
                            isError = renameError != null)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    managing = true
                    scope.launch {
                        try {
                            controller.renameOriaLab(id, renameText)
                            renameId = null
                            managementStatus = "Nom de la capture enregistré."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { renameError = e.message ?: "Nom invalide" }
                        finally { managing = false }
                    }
                }, enabled = canManage && renameText.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("Enregistrer") } },
                dismissButton = { TextButton(onClick = { renameId = null }, enabled = !managing,
                    modifier = Modifier.heightIn(min = 56.dp)) { Text("Annuler") } })
        }
        archiveId?.let { id ->
            val session = recording.savedSessions.firstOrNull { it.id == id }
            AlertDialog(onDismissRequest = { archiveId = null },
                title = { Text("Mettre à la corbeille ?") },
                text = { Text("${session?.displayName ?: session?.let { sessionDate(it.startedAtEpochMs) } ?: "Cette capture"} sera retirée de la liste. Elle restera dans la corbeille locale et pourra être restaurée, avec toutes ses données.") },
                confirmButton = { TextButton(onClick = {
                    archiveId = null
                    selectedSessionId = selectedSessionId.takeUnless { it == id }
                    mutate("Capture conservée dans la corbeille locale.") { controller.archiveOriaLab(id) }
                }, enabled = canManage, modifier = Modifier.heightIn(min = 56.dp)) { Text("Mettre à la corbeille") } },
                dismissButton = { TextButton(onClick = { archiveId = null },
                    modifier = Modifier.heightIn(min = 56.dp)) { Text("Annuler") } })
        }
    }
}

private fun Modifier.captureAction(action: String, captureName: String): Modifier =
    fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "$action : $captureName" }

@Composable
private fun ReviewImage(bitmap: Bitmap?, detections: List<Detection>, aspect: Float, placeholder: String, imageDescription: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(TestInk), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(placeholder, color = Color.White, modifier = Modifier.padding(20.dp))
        else {
            Image(bitmap.asImageBitmap(), imageDescription, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                detections.forEach { detection ->
                    val box = detection.box
                    drawRect(Color(0xFF63FFC3), Offset(box.left * size.width, box.top * size.height),
                        Size(box.width * size.width, box.height * size.height), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

private data class GalleryLoad(val sessionId: String? = null, val index: OriaLabGalleryIndex = OriaLabGalleryIndex())
private data class GalleryEntryKey(val sessionId: String, val sourceLine: Int)
private data class GalleryReview(val key: GalleryEntryKey? = null,
    val analysis: OriaLabGalleryAnalysis = OriaLabGalleryAnalysis(), val preview: GalleryPreview = GalleryPreview(), val loading: Boolean = false)
internal data class GalleryPreview(val bitmap: Bitmap? = null, val error: String? = null)

/** Decode only the selected PNG, at most about 1,280² pixels; originals remain untouched. */
internal fun decodeGalleryPreview(image: File): GalleryPreview = try {
    require(image.isFile) { "PNG absente au moment de la lecture" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(image.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "PNG présente mais indécodable" }
    var sample = 1
    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
    val bitmap = BitmapFactory.decodeFile(image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    if (bitmap == null) GalleryPreview(error = "PNG présente mais indécodable") else GalleryPreview(bitmap)
} catch (e: Exception) { GalleryPreview(error = "Image non affichée : ${e.message}") }

/** Formats validated recorded values only; no policy evaluation or image substitution. */
private fun recordedDetectionText(index: Int, detection: Detection): String {
    val label = RgbCategory.fromClassId(detection.classId)?.label ?: "Classe ${detection.classId}"
    val box = detection.box
    return String.format(Locale.FRANCE,
        "%d. %s · confiance %.1f %%\nBoîte : gauche %.3f · haut %.3f · droite %.3f · bas %.3f",
        index + 1, label, detection.confidence * 100f, box.left, box.top, box.right, box.bottom)
}

private fun mib(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024.0))

private fun sessionDate(epochMs: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date(epochMs))
