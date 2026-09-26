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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htc.vive.eagle.hackathon.starter.oria.OriaController
import com.htc.vive.eagle.hackathon.starter.oria.core.Box as DetectionBox
import com.htc.vive.eagle.hackathon.starter.oria.core.Detection
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TestInk = Color(0xFF101A23)
private val TestPaper = Color(0xFFF5F6F2)
private val TestTeal = Color(0xFF086D65)

@Composable
fun OriaLabScreen(controller: OriaController, onRecord: () -> Unit, onOpenHtcDiagnostics: () -> Unit) {
    val live by controller.state.collectAsStateWithLifecycle()
    val recording by controller.oriaLabState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var simulatorChoice by rememberSaveable { mutableStateOf(false) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var frameIndex by rememberSaveable { mutableIntStateOf(0) }
    val recordingActive = recording.phase == OriaLabPhase.RECORDING
    val finalizing = recording.phase == OriaLabPhase.FINALIZING
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
    val gallery by produceState(GalleryData(), selectedSession?.id) {
        value = GalleryData(loading = selectedSession != null)
        value = selectedSession?.let { session -> withContext(Dispatchers.IO) { loadGallery(session.directory) } } ?: GalleryData()
    }
    val safeIndex = frameIndex.coerceIn(0, (gallery.frames.size - 1).coerceAtLeast(0))
    val selectedFrame = gallery.frames.getOrNull(safeIndex)
    val reviewImage by produceState<Bitmap?>(null, selectedFrame?.image?.absolutePath) {
        value = null
        value = selectedFrame?.let { frame -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(frame.image.absolutePath) } }
    }
    var lastAspectRatio by remember(live.running, live.rotation, live.simulator) {
        mutableFloatStateOf(if (live.rotation % 180 == 0) 480f / 856f else 856f / 480f)
    }
    val liveRatio = live.preview?.let { it.width.toFloat() / it.height } ?: lastAspectRatio
    SideEffect { if (live.preview != null) lastAspectRatio = liveRatio }

    MaterialTheme(colorScheme = lightColorScheme(primary = TestTeal, background = TestPaper, surface = Color.White, onSurface = TestInk)) {
        Scaffold(containerColor = TestPaper, contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Surface(color = Color.White, shadowElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(recording.detail.ifBlank { "Aucun enregistrement en cours" }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onRecord,
                                enabled = live.connected && live.modelReady && !recordingActive && !finalizing && !exporting,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Enregistrer une scène") }
                            OutlinedButton(onClick = controller::stopOriaLab,
                                enabled = recordingActive || live.running,
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                                Text(if (recordingActive) "Arrêter et finaliser" else "Arrêter la vidéo")
                            }
                        }
                    }
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Oria Lab", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Capturer une scène pour l’analyser et la rejouer sur Mac.", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenHtcDiagnostics) { Text("Diagnostic HTC") }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (live.simulator) "SOURCE : SIMULATEUR HTC" else "SOURCE : CAMÉRA DES LUNETTES", fontWeight = FontWeight.Bold)
                        Text(if (live.connected) "Lunettes connectées" else "Lunettes déconnectées")
                        if (!live.connected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = simulatorChoice, onCheckedChange = { simulatorChoice = it })
                                Text("Utiliser le simulateur", Modifier.padding(start = 8.dp))
                            }
                            Button(onClick = { controller.connect(simulatorChoice) }) { Text("Connecter") }
                        }
                        Text(if (live.modelReady) "Modèle prêt" else "Chargement du modèle…")
                        Text("L’enregistrement commence uniquement avec « Enregistrer une scène ». Il contient la vidéo, les images exactes analysées et la télémétrie, sans piste microphone.")
                        Text("Limite par capture : 60 secondes ou 250 Mio. Un arrêt à la limite finalise la capture ; la vidéo peut continuer jusqu’à « Arrêter la vidéo ».", style = MaterialTheme.typography.bodySmall)
                        Text("Une nouvelle capture redémarre le flux pour conserver ses en-têtes vidéo. Le retour sur cet écran ne déclenche rien.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = if (recordingActive) Color(0xFFFFE5DD) else Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (recording.phase) {
                            OriaLabPhase.RECORDING -> "● ENREGISTREMENT"
                            OriaLabPhase.FINALIZING -> "FINALISATION EN COURS"
                            OriaLabPhase.ERROR -> "CAPTURE À VÉRIFIER"
                            OriaLabPhase.OFF -> "ENREGISTREMENT ARRÊTÉ"
                        }, fontWeight = FontWeight.Bold)
                        Text("Durée : ${recording.elapsedMs / 1000} s · images : ${recording.frames} · paquets : ${recording.packets}")
                        Text("Écrits : ${mib(recording.bytesWritten)} Mio · en attente : ${mib(recording.queuedBytes)} Mio", style = MaterialTheme.typography.bodySmall)
                        if (recording.incomplete) Text("Capture incomplète : consulter le motif et le manifeste exporté.", color = Color(0xFF9E2F1A))
                        if (finalizing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(live.status, style = MaterialTheme.typography.bodySmall)
                        Text("Images analysées fraîches : ${live.analyzed} · périmées : ${live.stale}", style = MaterialTheme.typography.bodySmall)
                        if (!live.orientationVerified) Text("Le repère n’est pas confirmé : les annonces automatiques restent désactivées. Vérifiez image et audio dans Oria.", style = MaterialTheme.typography.bodySmall)
                        Text("Dernière annonce : ${live.lastAlert}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (live.running) {
                    Text("Aperçu live", fontWeight = FontWeight.Bold)
                    ReviewImage(live.preview, live.detections, liveRatio, "En attente d’image fraîche")
                }
                HorizontalDivider()
                Text("Captures enregistrées", style = MaterialTheme.typography.titleLarge)
                Text("Le ZIP contient les fichiers de la session. Choisissez son emplacement avec le sélecteur Android ; aucun serveur n’est utilisé par Oria Lab.", style = MaterialTheme.typography.bodySmall)
                if (live.running || recordingActive || finalizing) Text("Arrêtez la vidéo et attendez la finalisation avant de revoir ou exporter une capture.", style = MaterialTheme.typography.bodySmall)
                if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (exportStatus.isNotBlank()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                if (recording.savedSessions.isEmpty()) Text("Aucune capture finalisée pour le moment.")
                recording.savedSessions.sortedByDescending { it.startedAtEpochMs }.forEach { session ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date(session.startedAtEpochMs)), fontWeight = FontWeight.Bold)
                            Text("${session.durationMs / 1000} s · ${session.frames} images · ${mib(session.bytes)} Mio")
                            Text(if (session.complete) "Finalisée · ${session.reason}" else "Incomplète · ${session.reason}", style = MaterialTheme.typography.bodySmall)
                            if (!session.usableForReplay) Text("Sans image exploitable pour le rejeu", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { selectedSessionId = session.id; frameIndex = 0 },
                                    enabled = !live.running && !recordingActive && !finalizing && !exporting) { Text("Revoir les images") }
                                OutlinedButton(onClick = {
                                    pendingExport = session.id
                                    exportLauncher.launch("OriaLab-${session.id}.zip")
                                }, enabled = !live.running && !recordingActive && !finalizing && !exporting) { Text("Exporter ZIP") }
                            }
                        }
                    }
                }
                if (selectedSession != null) {
                    HorizontalDivider()
                    Text("Relecture · capture enregistrée", style = MaterialTheme.typography.titleLarge)
                    Text(selectedSession.id, style = MaterialTheme.typography.bodySmall)
                    if (gallery.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (gallery.error != null) Text(gallery.error!!, color = Color(0xFF9E2F1A))
                    if (!gallery.loading && gallery.frames.isEmpty()) Text("Aucune image PNG disponible dans cette capture.")
                    if (selectedFrame != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { frameIndex = (safeIndex - 1).coerceAtLeast(0) }, enabled = safeIndex > 0) { Text("Précédente") }
                            Text("${safeIndex + 1} / ${gallery.frames.size}")
                            OutlinedButton(onClick = { frameIndex = safeIndex + 1 }, enabled = safeIndex + 1 < gallery.frames.size) { Text("Suivante") }
                        }
                        Text("Image ${selectedFrame.frameId} · réception ${selectedFrame.receivedAtMs} ms\n" +
                            if (selectedFrame.analyzed) "${selectedFrame.detections.size} détections retenues · ${selectedFrame.reason}" else "Image enregistrée sans résultat d’inférence associé",
                            style = MaterialTheme.typography.bodySmall)
                        ReviewImage(reviewImage, selectedFrame.detections,
                            reviewImage?.let { it.width.toFloat() / it.height } ?: 480f / 856f, "Chargement de l’image enregistrée…")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewImage(bitmap: Bitmap?, detections: List<Detection>, aspect: Float, placeholder: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(TestInk), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(placeholder, color = Color.White, modifier = Modifier.padding(20.dp))
        else {
            Image(bitmap.asImageBitmap(), "Image de la caméra", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
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

private data class ReviewFrame(val frameId: Long, val receivedAtMs: Long, val image: File,
    val detections: List<Detection>, val analyzed: Boolean, val reason: String)
private data class GalleryData(val frames: List<ReviewFrame> = emptyList(), val loading: Boolean = false, val error: String? = null)

/** Only finalized app-owned sessions are opened; full raw tensors are not kept in UI memory. */
private fun loadGallery(directory: File): GalleryData = try {
    val detections = mutableMapOf<Pair<Long, Long>, List<Detection>>()
    val reasons = mutableMapOf<Pair<Long, Long>, String>()
    val events = directory.resolve("events.jsonl")
    if (events.isFile) events.useLines { lines -> lines.forEach { line ->
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
        val key = event.optLong("videoSessionId", event.optLong("sessionId")) to event.optLong("frameId", event.optLong("frame", -1))
        when (event.optString("type")) {
            "inference" -> {
                val list = event.optJSONArray("detections")
                detections[key] = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    runCatching {
                        val item = list!!.getJSONObject(i)
                        val box = item.getJSONObject("box")
                        Detection(item.getInt("classId"), item.getDouble("confidence").toFloat(),
                            DetectionBox(box.getDouble("left").toFloat(), box.getDouble("top").toFloat(),
                                box.getDouble("right").toFloat(), box.getDouble("bottom").toFloat()))
                    }.getOrNull()
                }
            }
            "decision" -> reasons[key] = event.optString("reason", "")
        }
    } }
    val root = directory.canonicalFile
    val index = directory.resolve("frames.jsonl")
    val frames = if (!index.isFile) emptyList() else index.useLines { lines -> lines.mapNotNull { line ->
        runCatching {
            val frame = JSONObject(line)
            val id = frame.getLong("frameId")
            val key = frame.optLong("videoSessionId", frame.optLong("sessionId")) to id
            val image = directory.resolve(frame.getString("imagePath")).canonicalFile
            require(image.path.startsWith(root.path + File.separator) && image.isFile)
            ReviewFrame(id, frame.optLong("receivedAtMs"), image, detections[key].orEmpty(),
                detections.containsKey(key), reasons[key] ?: "Sans décision fraîche")
        }.getOrNull()
    }.toList() }
    GalleryData(frames = frames)
} catch (e: Exception) { GalleryData(error = "Relecture indisponible : ${e.message}") }

private fun mib(bytes: Long): String = String.format(Locale.FRANCE, "%.1f", bytes / (1024.0 * 1024.0))
