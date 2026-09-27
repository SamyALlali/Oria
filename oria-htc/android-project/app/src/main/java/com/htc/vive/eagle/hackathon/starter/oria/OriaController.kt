package com.htc.vive.eagle.hackathon.starter.oria

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import com.htc.vive.eagle.hackathon.starter.oria.core.*
import com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetector
import com.htc.vive.eagle.hackathon.starter.oria.ml.LetterboxTransform
import com.htc.vive.eagle.hackathon.starter.oria.ml.MidasV21DepthEstimator
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import com.htc.vive.eagle.hackathon.starter.oria.audio.BluetoothSpeechBackend
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechDelivery
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioScheduler
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioRequest
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioKind
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioPriority
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioDecision
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioDecisionReason
import com.htc.vive.eagle.hackathon.starter.oria.audio.DangerSoundLevel
import com.htc.vive.eagle.hackathon.starter.oria.audio.DangerSoundPattern
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabRecorder
import com.htc.vive.eagle.hackathon.starter.oria.lifecycle.*
import com.htc.vive.eagle.hackathon.starter.oria.navigation.*
import com.htc.vive.eagle.hackathon.starter.oria.interaction.*
import com.htc.vive.eagle.hackathon.starter.oria.dashboard.*
import com.htc.vive.eagle.hackathon.starter.oria.robustness.*
import com.htc.vive.eagle.hackathon.starter.OriaTranscriptionEvent
import com.htc.vive.eagle.hackathon.starter.OriaTranscriptionStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

enum class OriaVoiceBackend { BLUETOOTH, HTC }

enum class OriaInteractionPhase { IDLE, LISTENING, EXECUTING, RECOVERING, FAILED }

data class OriaInteractionState(
    val phase: OriaInteractionPhase = OriaInteractionPhase.IDLE,
    val status: String = "Double-appui IA : commande vocale",
    val lastTranscript: String? = null,
    val lastIntent: String? = null,
)

data class OriaUiState(
    val connected: Boolean = false,
    val simulator: Boolean = false,
    val running: Boolean = false,
    val modelReady: Boolean = false,
    val modelLoading: Boolean = true,
    val xnnpack: Boolean = true,
    val trackingMode: RgbTrackingMode = RgbTrackingMode.LEGACY_IOU,
    val depthEnabled: Boolean = false,
    val depthReady: Boolean = false,
    val depthLoading: Boolean = false,
    val depthStatus: String = "Désactivée · fallback RGB",
    val approachEnabled: Boolean = false,
    val resolutionStatus: String = "Résolution Oria · RGB validé",
    val status: String = "Chargement du modèle…",
    val audio: String = "Voix à tester sur les lunettes",
    val audioBusy: Boolean = false,
    val voiceBackend: OriaVoiceBackend = OriaVoiceBackend.BLUETOOTH,
    val localVoiceReady: Boolean = false,
    val localVoiceStatus: String = "Préparation de la voix française locale…",
    val audioAutomaticPaused: Boolean = false,
    val audioUnknown: Boolean = false,
    val audioSilent: Boolean = false,
    val dangerTonesEnabled: Boolean = false,
    val audioQueueStatus: String = "Ordonnanceur prêt",
    val lastAlert: String = "Aucune annonce",
    val suppression: String = "",
    val detections: List<Detection> = emptyList(),
    val preview: Bitmap? = null,
    val received: Long = 0,
    val analyzed: Long = 0,
    val stale: Long = 0,
    val lastLatencyMs: Long = 0,
    val preprocessMs: Double = 0.0,
    val inferenceMs: Double = 0.0,
    val depthInferenceMs: Double = 0.0,
    val fps: Double = 0.0,
    val p95Ms: Long = 0,
    val allInferenceP95Ms: Long = 0,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val orientationVerified: Boolean = false,
    val pocketEnabled: Boolean = false,
    val pocketPreparing: Boolean = false,
    val pocketActive: Boolean = false,
    val pocketStatus: String = "Désactivé · garder l’application ouverte",
    val runtime: OriaRuntimeSnapshot = OriaRuntimeSnapshot.initial(),

)

/** All policy/audio mutations live on Main. Pixels/inference run on one owned worker. */
class OriaController(context: Context, private val manager: ViveGlassKitManager) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val traceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine = RgbAlertEngine()
    private val dangerEngine = DangerResolutionEngine()
    private val audioScheduler = OriaAudioScheduler()
    private val navigationCoordinator = OriaNavigationCoordinator(appContext, scope,
        ::onNavigationSpeech, ::invalidateNavigationAudio, ::traceNavigation)
    val navigationState: StateFlow<NavigationUiState> = navigationCoordinator.state
    private val _interactionState = MutableStateFlow(OriaInteractionState())
    val interactionState: StateFlow<OriaInteractionState> = _interactionState.asStateFlow()
    private val _dashboardState = MutableStateFlow(OriaDashboardSnapshot.initial())
    val dashboardState: StateFlow<OriaDashboardSnapshot> = _dashboardState.asStateFlow()
    private val runtime = OriaRuntimeState(SystemClock.elapsedRealtime())
    // Preserve unresolved-delivery state across compatible updates, including the Oria rename.
    private val audioPersistence = appContext.getSharedPreferences("oria_audio_delivery", Context.MODE_PRIVATE)
    private val previousUncertainDelivery = audioPersistence.getBoolean("delivery_pending_or_unknown", false)
    private val _state = MutableStateFlow(OriaUiState(audioUnknown = previousUncertainDelivery,
        audio = if (previousUncertainDelivery) "Livraison vocale précédente incertaine" else "Voix à tester sur les lunettes",
        runtime = runtime.snapshot()))
    val state: StateFlow<OriaUiState> = _state.asStateFlow()
    val oriaLabState = manager.oriaLabRecorder.state
    private var detector: OnnxObjectDetector? = null
    private var depthEstimator: MidasV21DepthEstimator? = null
    private val depthAdapter = RelativeDepthEvidenceAdapter(MAX_AGE_MS)
    @Volatile private var modelManifest: JSONObject? = null
    @Volatile private var appProvenance: JSONObject? = null
    @Volatile private var generation = 0L
    private var startedAt = 0L
    private var streamStartedAt = 0L
    private var lastFrameAt = 0L
    private var lastAcceptedObservationAt = 0L
    private var startJob: Job? = null
    private var modelJob: Job? = null
    private var depthModelJob: Job? = null
    @Volatile private var closed = false
    private val latencies = ArrayDeque<Long>()
    private val allInferenceLatencies = ArrayDeque<Long>()
    private val frames = Channel<OriaVideoFrame>(1, BufferOverflow.DROP_OLDEST) { it.bitmap.recycle() }
    private val traces = Channel<String>(256, BufferOverflow.DROP_OLDEST)
    private data class FrameAnalysis(val generation: Long, val observedAtMs: Long,
        val detections: List<Detection>, val preview: Bitmap,
        val preprocessMs: Double, val inferenceMs: Double, val diagnosticOutput: JSONObject?,
        val depthMap: RelativeDepthMap?, val depthPreprocessMs: Double, val depthInferenceMs: Double,
        val depthFailure: String?)
    private data class PendingSpeech(val ticket: VoiceTicket?, val epoch: Long, val submittedAt: Long,
        val text: String, val id: String, val backend: OriaVoiceBackend, val sessionGeneration: Long,
        val pan: SpeechPan, val scheduled: OriaAudioRequest)
    @Volatile private var pendingSpeech: PendingSpeech? = null
    private var lastLocalVoiceReady = false
    private var lastDangerPattern = DangerSoundPattern.silent()
    private val localSpeech = BluetoothSpeechBackend(appContext, ::onLocalSpeechResult)
    private val buttonSequencer = EagleButtonSequencer()
    private var buttonTimeout: Job? = null
    private var transcriptionTimeout: Job? = null
    private val transcriptionGate = TranscriptionRequestGate()
    private var restorePerceptionAfterVoice = false
    private var voiceDestinationPending = false
    private var lastVoiceDestinationSignature: String? = null
    private var commandOnlyAudioRoute = false
    private var developerDashboardVisible = false
    private val dashboardRateLimiter = DashboardRateLimiter(250)
    private var dashboardSequence = 0L
    private var dashboardObjects = emptyList<DashboardObject>()
    private var dashboardDecision = OriaDashboardSnapshot.initial().decision
    private var dashboardAudioDrops = 0L
    private var dashboardNavigationPreemptions = 0L
    private var dashboardNavigationResumptions = 0L
    private var dashboardNavigationAwaitingResume = false
    private var dashboardLastError: String? = null
    private var dashboardBuilds = 0L
    private var dashboardBuildNanos = 0L
    private var dashboardBatteryAtMs = Long.MIN_VALUE
    private var dashboardBattery: Pair<Int?, Double?> = null to null
    private val performance = OriaPerformanceMonitor()
    private val loadController = OriaLoadController()
    private var loadProfile = loadController.reset()
    private var depthFrameSequence = 0L
    private var lastHealthSampleAt = Long.MIN_VALUE
    private var lastCpuWallMs = 0L
    private var lastCpuElapsedMs = 0L

    init {
        scope.launch { navigationState.collect { navigation ->
            val (availability, detail) = when (navigation.phase) {
                NavigationPhase.ACTIVE, NavigationPhase.ARRIVED ->
                    OriaDependencyAvailability.AVAILABLE to navigation.status
                NavigationPhase.SEARCHING, NavigationPhase.CONFIRMATION, NavigationPhase.CALCULATING,
                NavigationPhase.RECALCULATING -> OriaDependencyAvailability.STARTING to navigation.status
                NavigationPhase.PAUSED, NavigationPhase.LIMITED -> OriaDependencyAvailability.LIMITED to navigation.status
                NavigationPhase.FAILED -> OriaDependencyAvailability.FAILED to navigation.status
                NavigationPhase.IDLE -> OriaDependencyAvailability.UNAVAILABLE to navigation.status
            }
            if (navigation.phase == NavigationPhase.FAILED) dashboardLastError = navigation.status
            updateDependency(OriaRuntimeDependency.NAVIGATION, availability, detail, optional = true)
            announceVoiceDestinationChoice(navigation)
        } }
        scope.launch { manager.oriaTranscriptionEvents.collect(::onOriaTranscription) }
        scope.launch { localSpeech.state.collect { voice ->
            _state.update { it.copy(localVoiceReady = voice.ready, localVoiceStatus = voice.detail) }
            updateDependency(OriaRuntimeDependency.AUDIO,
                if (voice.ready) OriaDependencyAvailability.AVAILABLE else OriaDependencyAvailability.LIMITED,
                voice.detail, optional = true)
            if (lastLocalVoiceReady && !voice.ready && _state.value.voiceBackend == OriaVoiceBackend.BLUETOOTH) {
                val decision = audioScheduler.routeLost()
                silenceDangerPattern()
                if (pendingSpeech?.backend == OriaVoiceBackend.BLUETOOTH) cancelLocalSpeech(
                    "Route Bluetooth interrompue", finishScheduler = false)
                traceAudioDecision(decision, "route_state")
            }
            lastLocalVoiceReady = voice.ready
        } }
        val phrases = RgbCategory.entries.filter { it.alertable }.flatMap { category ->
            RgbZone.entries.map { zone -> "${category.label} ${zone.voiceSuffix}" }
        }.toSet() + TEST_PHRASE
        localSpeech.prepare(phrases)
        traceScope.launch {
            appContext.filesDir.resolve("oria-trace-${System.currentTimeMillis()}.jsonl").bufferedWriter().use { writer ->
                for (line in traces) { writer.appendLine(line); writer.flush() }
            }
            traceScope.cancel()
        }
        scope.launch { manager.connection.collect { connected ->
            _state.update { it.copy(connected = connected) }
            if (!connected && navigationState.value.phase != NavigationPhase.IDLE) {
                navigationCoordinator.stop("Lunettes déconnectées · navigation arrêtée")
            }
            if (!connected && _state.value.running) stop("Lunettes déconnectées", OriaRuntimePhase.DISCONNECTED)
            else if (!connected) updateRuntime { disconnected(generation, now(), "Lunettes déconnectées") }
            else if (!_state.value.running) updateRuntime { ready(generation, now(), _state.value.modelReady) }
        } }
        scope.launch { manager.isSimulator.collect { simulated ->
            _state.update { it.copy(simulator = simulated) }
        } }
        scope.launch { manager.oriaVideoStatus.collect { video ->
            if (video.sessionId == generation && _state.value.running && video.phase in setOf("error", "stopped")) {
                stop("Vidéo indisponible : ${video.detail ?: "erreur de décodage"}",
                    if (video.phase == "error") OriaRuntimePhase.FAILED else OriaRuntimePhase.INTERRUPTED)
            } else if (video.sessionId == generation && _state.value.running) {
                when (video.phase) {
                    "requesting_permissions" -> updateDependency(OriaRuntimeDependency.CAMERA,
                        OriaDependencyAvailability.STARTING, "Autorisation caméra")
                    "waiting_for_config" -> {
                        updateDependency(OriaRuntimeDependency.CAMERA, OriaDependencyAvailability.AVAILABLE, "Flux H.264 reçu")
                        updateDependency(OriaRuntimeDependency.DECODER, OriaDependencyAvailability.STARTING, "En attente SPS/PPS")
                    }
                    "decoding" -> updateDependency(OriaRuntimeDependency.DECODER,
                        OriaDependencyAvailability.AVAILABLE, video.detail ?: "Décodage actif")
                }
            }
        } }
        scope.launch { manager.synthesisTransportChanges.drop(1).collect {
            updateDependency(OriaRuntimeDependency.AUDIO, OriaDependencyAvailability.LIMITED,
                "Transport vocal interrompu", optional = true)
            if (pendingSpeech?.backend == OriaVoiceBackend.HTC) uncertainAudio("Transport vocal interrompu")
        } }
        scope.launch { manager.synthesisEvents.collect { event ->
            val pending = pendingSpeech ?: return@collect
            if (pending.backend != OriaVoiceBackend.HTC) return@collect
            if (_state.value.audioUnknown || event.transportEpoch != pending.epoch || event.receivedAtMs < pending.submittedAt) {
                uncertainAudio("Retour vocal ambigu")
                return@collect
            }
            when (event.event) {
                "SUCCESS" -> {
                    val accepted = pending.ticket?.let { applyVoiceResult(it, "confirmed") } ?: true
                    if (!accepted) {
                        uncertainAudio("Confirmation vocale refusée par le moteur")
                        return@collect
                    }
                    pendingSpeech = null
                    persistUncertainDelivery(false)
                    updateDependency(OriaRuntimeDependency.AUDIO, OriaDependencyAvailability.AVAILABLE,
                        "Retour vocal reçu", optional = true)
                    _state.update { it.copy(audioBusy = false, audioAutomaticPaused = false, audio = "Retour SDK reçu · audibilité à vérifier") }
                    trace("speech_sdk_success", "text" to pending.text, "requestId" to pending.id,
                        "videoSessionId" to pending.sessionGeneration, "delayMs" to (now() - pending.submittedAt))
                    finishScheduled(pending.scheduled, OriaAudioDecisionReason.COMPLETED_NEXT)
                    completeCommandAudio(success = true)
                }
                "ERROR", "ERROR_RESOURCE_CONFLICT", "ERROR_UNSUPPORTED_LOCALE" -> {
                    val accepted = pending.ticket?.let { applyVoiceResult(it, "failed") } ?: true
                    if (!accepted) {
                        uncertainAudio("Retour d’échec vocal non corrélé")
                        return@collect
                    }
                    pendingSpeech = null
                    persistUncertainDelivery(false)
                    _state.update { it.copy(audioBusy = false, audioAutomaticPaused = true, audio = "Voix refusée : ${event.event}") }
                    trace("speech_failed", "event" to event.event, "requestId" to pending.id,
                        "videoSessionId" to pending.sessionGeneration)
                    finishScheduled(pending.scheduled, OriaAudioDecisionReason.SUBMISSION_FAILED)
                    completeCommandAudio(success = false)
                }
                else -> uncertainAudio("Événement vocal inconnu")
            }
        } }
        scope.launch {
            for (frame in frames) {
                try {
                    if (!_state.value.running || frame.sessionId != generation) continue
                    val sourceTime = frame.receivedAtMs
                    val acceptedAt = now()
                    if (!runtime.acceptEvidence(frame.sessionId, sourceTime, acceptedAt, MAX_AGE_MS)) {
                        performance.recordDecision(sourceTime, acceptedAt.coerceAtLeast(sourceTime), false)
                        staleFrame(); continue
                    }
                    val activeDetector = detector ?: continue
                    val result = withContext(worker) {
                        val detections = activeDetector.detect(frame.bitmap,
                            captureRawOutput = oriaLabState.value.phase == OriaLabPhase.RECORDING)
                        val diagnosticOutput = activeDetector.lastRawOutput?.let {
                            rawOutputJson(it, frame.bitmap.width, frame.bitmap.height)
                        }
                        var depthMap: RelativeDepthMap? = null
                        var depthFailure: String? = null
                        val activeDepth = depthEstimator
                        val runDepth = ++depthFrameSequence % loadProfile.depthStride == 0L
                        if (_state.value.depthEnabled && activeDepth != null && runDepth) {
                            try { depthMap = activeDepth.estimate(frame.bitmap, sourceTime) }
                            catch (failure: Exception) { depthFailure = failure.message ?: failure.javaClass.simpleName }
                        } else if (_state.value.depthEnabled && activeDepth != null) {
                            depthFailure = "Échantillonnage réduit · profil ${loadProfile.level.name.lowercase()}"
                        }
                        val scale = minOf(1f, 480f / maxOf(frame.bitmap.width, frame.bitmap.height))
                        val preview = Bitmap.createScaledBitmap(frame.bitmap,
                            (frame.bitmap.width * scale).toInt().coerceAtLeast(1),
                            (frame.bitmap.height * scale).toInt().coerceAtLeast(1), true)
                        val ownedPreview = if (preview === frame.bitmap) preview.copy(Bitmap.Config.ARGB_8888, false) else preview
                        FrameAnalysis(frame.sessionId, sourceTime, detections, ownedPreview, activeDetector.lastPreprocessMs,
                            activeDetector.lastInferenceMs, diagnosticOutput, depthMap,
                            activeDepth?.lastPreprocessMs ?: 0.0, activeDepth?.lastInferenceMs ?: 0.0,
                            depthFailure)
                    }
                    if (!_state.value.running || frame.sessionId != generation || result.generation != generation ||
                        result.observedAtMs != sourceTime) {
                        performance.recordDecision(sourceTime, now().coerceAtLeast(sourceTime), false)
                        result.preview.recycle(); continue
                    }
                    val age = now() - sourceTime
                    allInferenceLatencies.addLast(age)
                    if (allInferenceLatencies.size > 1000) allInferenceLatencies.removeFirst()
                    val allOrdered = allInferenceLatencies.sorted()
                    _state.update { it.copy(allInferenceP95Ms = allOrdered[p95Index(allOrdered.size)]) }
                    trace("inference", "frame" to frame.frameId, "ageMs" to age,
                        "preprocessMs" to result.preprocessMs, "inferenceMs" to result.inferenceMs,
                        "detections" to result.detections.size, "accepted" to (age <= MAX_AGE_MS))
                    recordInference(frame, result, age)
                    if (age > MAX_AGE_MS) {
                        performance.recordDecision(sourceTime, now().coerceAtLeast(sourceTime), false)
                        result.preview.recycle(); staleFrame(); continue
                    }
                    val evaluatedAtMs = now()
                    val evaluation = engine.evaluate(DetectionFrame(generation, frame.frameId, sourceTime, result.detections), evaluatedAtMs)
                    if (evaluation.frameStatus != RgbFrameStatus.ACCEPTED) {
                        performance.recordDecision(sourceTime, evaluatedAtMs, false)
                        recordDecision(frame, evaluation, evaluatedAtMs)
                        result.preview.recycle()
                        trace("policy_frame_rejected", "frameId" to frame.frameId,
                            "reason" to evaluation.frameStatus.name)
                        staleFrame()
                        continue
                    }
                    val depthEvidence = result.depthMap?.let { map -> withContext(worker) {
                        val tracks = evaluation.tracks.filter { it.visibleInLatestFrame }.associate { track ->
                            track.id to depthAdapter.evidence(track.id, track.detection.box, map, evaluatedAtMs)
                        }
                        tracks to depthAdapter.frontalObstruction(map, evaluatedAtMs)
                    } }
                    val resolution = dangerEngine.resolve(OriaDangerAdapter.input(evaluation,
                        depthEvidence?.first ?: emptyMap(), depthEvidence?.second, evaluatedAtMs,
                        _state.value.approachEnabled))
                    performance.recordDecision(sourceTime, evaluatedAtMs, true)
                    dashboardObjects = evaluation.tracks.filter { it.visibleInLatestFrame }.mapNotNull { track ->
                        val category = RgbCategory.fromClassId(track.detection.classId) ?: return@mapNotNull null
                        DashboardObject(track.id, category.label,
                            (track.detection.confidence * 100).toInt().coerceIn(0, 100),
                            track.zone.name.lowercase(), track.confirmed,
                            listOf(track.detection.box.left, track.detection.box.top,
                                track.detection.box.right, track.detection.box.bottom))
                    }.toList()
                    val selectedDanger = resolution.selected
                    dashboardDecision = DashboardDecision(
                        selected = selectedDanger?.let { candidate ->
                            "${candidate.category?.label ?: "Obstacle"} ${candidate.zone.voiceSuffix}"
                        } ?: "Aucun",
                        dangerLevel = selectedDanger?.level?.name?.lowercase() ?: "aucun",
                        source = selectedDanger?.source?.wireName ?: "aucune",
                        policyReason = resolution.policyReason.name.lowercase(),
                        stabilizationReason = resolution.stabilizationReason.name.lowercase(),
                        suppressionReason = evaluation.suppressionReason.name.lowercase(),
                        estimatedRelativeProximityPercent = selectedDanger?.relativeProximity
                            ?.let { (it * 100).toInt().coerceIn(0, 100) },
                        estimatedDepthConfidencePercent = selectedDanger?.takeIf {
                            it.relativeProximity != null
                        }?.let { (it.confidence * 100).toInt().coerceIn(0, 100) })
                    recordDecision(frame, evaluation, evaluatedAtMs, depthEvidence, resolution)
                    updateRuntime { active(frame.sessionId, evaluatedAtMs) }
                    lastAcceptedObservationAt = sourceTime
                    trace("decision", "frame" to frame.frameId, "observedAtMs" to sourceTime,
                        "selectedTrack" to (evaluation.selected?.trackId ?: -1L),
                        "category" to (evaluation.selected?.category?.name ?: "none"),
                        "zone" to (evaluation.selected?.zone?.name ?: "none"),
                        "priority" to (evaluation.selected?.priority ?: 0f),
                        "reason" to evaluation.suppressionReason.name, "tracks" to evaluation.tracks.size)
                    trace("danger_resolution", "frame" to frame.frameId,
                        "source" to (resolution.selected?.source?.wireName ?: "none"),
                        "owner" to (resolution.ownerKey ?: "none"),
                        "level" to (resolution.selected?.level?.name ?: "NONE"),
                        "zone" to (resolution.selected?.zone?.name ?: "none"),
                        "score" to (resolution.selected?.score ?: 0f),
                        "policyReason" to resolution.policyReason.name,
                        "stabilizationReason" to resolution.stabilizationReason.name,
                        "approachEnabled" to resolution.approachEnabled,
                        "matchesRgbSelection" to (resolution.ownerKey == evaluation.selected?.let { "track-${it.trackId}" }))
                    if (_state.value.depthEnabled) {
                        val generic = depthEvidence?.second
                        trace("depth_evidence", "frame" to frame.frameId,
                            "available" to (generic?.available == true),
                            "relativeProximity" to (generic?.relativeProximity ?: -1f),
                            "confidence" to (generic?.confidence ?: 0f),
                            "reason" to (generic?.unavailableReason?.name ?: result.depthFailure ?: "NONE"),
                            "tracks" to (depthEvidence?.first?.size ?: 0),
                            "inferenceMs" to result.depthInferenceMs)
                    }
                    latencies.addLast(age)
                    if (latencies.size > 1000) latencies.removeFirst()
                    val ordered = latencies.sorted()
                    _state.update { old ->
                        val count = old.analyzed + 1
                        val generic = depthEvidence?.second
                        val depthStatus = when {
                            !_state.value.depthEnabled -> "Désactivée · fallback RGB"
                            result.depthFailure != null -> "Indisponible : ${result.depthFailure} · fallback RGB"
                            generic?.available == true -> "Estimation relative active · confiance estimée ${(generic.confidence * 100).toInt()} %"
                            else -> "Estimation relative non fiable : ${generic?.unavailableReason ?: "aucune carte"} · fallback RGB"
                        }
                        old.copy(status = if (generic?.available == true) "Perception active · RGB + profondeur relative"
                            else "Perception active · caméra seule", analyzed = count,
                            preview = result.preview, detections = result.detections, lastLatencyMs = age,
                            inferenceMs = result.inferenceMs, preprocessMs = result.preprocessMs,
                            depthInferenceMs = result.depthInferenceMs, depthStatus = depthStatus,
                            resolutionStatus = resolution.selected?.let {
                                "${it.source.wireName} · ${it.level.name.lowercase()} · ${it.zone.name.lowercase()}"
                            } ?: "Aucun danger résolu",
                            fps = count * 1000.0 / (now() - startedAt).coerceAtLeast(1),
                            p95Ms = ordered[p95Index(ordered.size)],
                            suppression = evaluation.suppressionReason.toString())
                    }
                    updateDangerPattern(resolution)
                    evaluation.eligibleAlert?.let { alert ->
                        if (_state.value.orientationVerified && !_state.value.audioUnknown &&
                            !_state.value.audioAutomaticPaused && !_state.value.audioSilent && voiceBackendReady()) {
                            offerDangerSpeech(alert, resolution)
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    trace("pipeline_error", "error" to error.toString())
                    stop("Analyse interrompue : ${error.message}")
                } finally { frame.bitmap.recycle() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value.running && lastAcceptedObservationAt > 0L && now() - lastAcceptedObservationAt > MAX_AGE_MS) {
                    engine.current(now())
                    silenceDangerPattern()
                    updateRuntime { limited(generation, now(), OriaRuntimeDependency.CAMERA,
                        "Aucune image fraîche") }
                    _state.update { it.copy(status = "Perception en attente d’images fraîches", detections = emptyList(), preview = null, suppression = "observation_expired") }
                }
                if (_state.value.running && streamStartedAt != 0L && now() - maxOf(streamStartedAt, lastFrameAt) > 5_000)
                    stop("Flux vidéo figé ou absent", OriaRuntimePhase.INTERRUPTED)
                pendingSpeech?.let {
                    if (now() - it.submittedAt > (if (it.backend == OriaVoiceBackend.BLUETOOTH) 20_000 else 12_000)) {
                        if (it.backend == OriaVoiceBackend.BLUETOOTH) cancelLocalSpeech("Lecture locale expirée")
                        else uncertainAudio("Retour vocal absent · reprise non vérifiée")
                    }
                }
                sampleHealthAndLoad(now())
                publishDashboard(now())
            }
        }
        // This exact provider passed all six parity fixtures on the supplied HTC U24 pro.
        // CPU remains an explicit diagnostic option while its strict parity failure is investigated.
        loadModel(true)
    }

    private fun updateRuntime(change: OriaRuntimeState.() -> Boolean) {
        if (runtime.change()) _state.update { it.copy(runtime = runtime.snapshot()) }
    }

    private fun updateDependency(kind: OriaRuntimeDependency, availability: OriaDependencyAvailability,
                                 detail: String, optional: Boolean = false) {
        updateRuntime { dependency(generation, now(), kind, availability, detail, optional) }
    }

    fun setDeveloperDashboardVisible(visible: Boolean) {
        if (developerDashboardVisible == visible) return
        developerDashboardVisible = visible
        if (visible) publishDashboard(now(), force = true)
        trace("dashboard_visibility", "visible" to visible,
            "publications" to dashboardBuilds,
            "averageBuildMicros" to if (dashboardBuilds == 0L) 0L
            else dashboardBuildNanos / dashboardBuilds / 1_000L)
    }

    private fun publishDashboard(atMs: Long, force: Boolean = false) {
        if (!developerDashboardVisible || !dashboardRateLimiter.shouldPublish(
                atMs, force, loadProfile.dashboardIntervalMs)) return
        val buildStarted = System.nanoTime()
        val live = _state.value
        val navigation = navigationState.value
        val recording = oriaLabState.value
        val runtimeSnapshot = live.runtime
        if (dashboardBatteryAtMs == Long.MIN_VALUE || atMs - dashboardBatteryAtMs >= 5_000) {
            dashboardBattery = phoneBattery()
            dashboardBatteryAtMs = atMs
        }
        val battery = dashboardBattery
        val navGps = when {
            navigation.mode == NavigationMode.SIMULATED -> "simulation · aucun GPS réel"
            navigation.phase == NavigationPhase.LIMITED -> "GPS limité ou perdu"
            navigation.phase in setOf(NavigationPhase.ACTIVE, NavigationPhase.CALCULATING,
                NavigationPhase.RECALCULATING) -> "GPS réel actif"
            else -> "GPS inactif"
        }
        val navState = when (navigation.phase) {
            NavigationPhase.RECALCULATING -> "recalcul"
            NavigationPhase.PAUSED -> "pause"
            NavigationPhase.ACTIVE -> if (dashboardNavigationAwaitingResume) "préemptée par danger" else "active"
            NavigationPhase.ARRIVED -> "arrivée"
            NavigationPhase.LIMITED -> "limitée"
            NavigationPhase.CALCULATING -> "calcul"
            NavigationPhase.CONFIRMATION -> "confirmation"
            NavigationPhase.SEARCHING -> "recherche"
            NavigationPhase.FAILED -> "erreur"
            NavigationPhase.IDLE -> "arrêtée"
        }
        val navigationDashboard = DashboardNavigation(
            navigation.mode.name.lowercase(), navState, navigation.selected?.label ?: "Aucune",
            navigation.currentInstruction ?: "Aucune", navigation.remainingMeters?.toInt(), navGps,
            navigation.routeVersion, dashboardNavigationPreemptions, dashboardNavigationResumptions)
        val measured = performance.snapshot(atMs)
        val abandoned = (live.received - live.analyzed - live.stale).coerceAtLeast(0)
        val metrics = DashboardMetrics(live.fps, live.received, live.analyzed, abandoned, live.stale,
            if (lastAcceptedObservationAt == 0L) 0L else (atMs - lastAcceptedObservationAt).coerceAtLeast(0),
            measured.cameraToDecisionP50Ms, measured.cameraToDecisionP95Ms, live.allInferenceP95Ms,
            live.preprocessMs.toInt(), live.inferenceMs.toInt(), live.depthInferenceMs.toInt(),
            dashboardAudioDrops, dashboardBuilds + 1,
            if (dashboardBuilds == 0L) 0L else dashboardBuildNanos / dashboardBuilds / 1_000L,
            measured.decisionToAudioP50Ms, measured.decisionToAudioP95Ms,
            (measured.health?.memoryUsedBytes ?: 0L) / (1024L * 1024L),
            measured.health?.cpuPercent ?: 0.0, loadProfile.level.name.lowercase())
        val health = DashboardHealth(
            model = "Oria ONNX · ${if (live.xnnpack) "XNNPACK" else "CPU"} · ${live.trackingMode.name}",
            audio = "${live.voiceBackend.name} · ${live.audioQueueStatus}",
            phoneBatteryPercent = battery.first,
            phoneTemperatureCelsius = battery.second,
            lastError = dashboardLastError ?: runtimeSnapshot.reason.takeIf {
                runtimeSnapshot.phase in setOf(OriaRuntimePhase.FAILED, OriaRuntimePhase.INTERRUPTED)
            }, thermalStatus = measured.health?.thermalStatus)
        val input = DashboardInput(++dashboardSequence, atMs, live.connected, live.running,
            runtimeSnapshot.phase == OriaRuntimePhase.LIMITED,
            runtimeSnapshot.phase == OriaRuntimePhase.FAILED,
            replaying = false, status = live.status, lastAlert = live.lastAlert,
            objects = dashboardObjects, decision = dashboardDecision,
            navigation = navigationDashboard, metrics = metrics, health = health,
            replay = when (recording.phase) {
                OriaLabPhase.RECORDING -> "Capture en cours · ${recording.frames} images"
                OriaLabPhase.FINALIZING -> "Capture en finalisation"
                OriaLabPhase.ERROR -> "Erreur Oria Lab · ${recording.detail}"
                OriaLabPhase.OFF -> "${recording.savedSessions.size} capture(s) disponible(s)"
            })
        _dashboardState.value = OriaDashboardProjector.project(input)
        dashboardBuilds++
        dashboardBuildNanos += System.nanoTime() - buildStarted
    }

    private fun phoneBattery(): Pair<Int?, Double?> {
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null to null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val rawTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = rawTemperature.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
        return percent to temperature
    }

    private fun sampleHealthAndLoad(atMs: Long) {
        if (lastHealthSampleAt != Long.MIN_VALUE && atMs - lastHealthSampleAt < 5_000) return
        val runtimeMemory = Runtime.getRuntime()
        val cpuElapsed = android.os.Process.getElapsedCpuTime()
        val cpuPercent = if (lastCpuWallMs > 0 && atMs > lastCpuWallMs) {
            ((cpuElapsed - lastCpuElapsedMs).coerceAtLeast(0) * 100.0 / (atMs - lastCpuWallMs))
                .coerceIn(0.0, 1_000.0)
        } else 0.0
        lastCpuWallMs = atMs
        lastCpuElapsedMs = cpuElapsed
        lastHealthSampleAt = atMs
        val battery = phoneBattery()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } else null
        val sample = OriaHealthSample(
            memoryUsedBytes = runtimeMemory.totalMemory() - runtimeMemory.freeMemory(),
            memoryLimitBytes = runtimeMemory.maxMemory(), cpuPercent = cpuPercent,
            temperatureCelsius = battery.second, batteryPercent = battery.first,
            thermalStatus = thermal)
        performance.recordHealth(sample)
        val previous = loadProfile
        loadProfile = loadController.evaluate(sample, performance.snapshot(atMs).cameraToDecisionP95Ms)
        if (previous != loadProfile) trace("load_profile", "level" to loadProfile.level.name,
            "reason" to loadProfile.reason, "dashboardIntervalMs" to loadProfile.dashboardIntervalMs,
            "depthStride" to loadProfile.depthStride, "detectionStride" to loadProfile.detectionStride,
            "cpuPercent" to cpuPercent, "memoryUsedBytes" to sample.memoryUsedBytes,
            "temperatureCelsius" to (sample.temperatureCelsius ?: -1.0),
            "batteryPercent" to (sample.batteryPercent ?: -1), "thermalStatus" to (thermal ?: -1))
    }

    fun setTrackingMode(mode: RgbTrackingMode) {
        if (closed || _state.value.running || _state.value.pocketPreparing ||
            _state.value.audioBusy || _state.value.audioUnknown || oriaLabState.value.storageBusy) return
        engine = RgbAlertEngine(engine.config.copy(trackingMode = mode))
        _state.update { it.copy(trackingMode = mode) }
        trace("tracking_mode_changed", "trackingMode" to mode.name)
    }

    fun setDepthEnabled(enabled: Boolean) {
        if (closed || _state.value.running || _state.value.pocketPreparing || depthModelJob?.isActive == true) return
        if (!enabled) {
            dangerEngine.setApproachEnabled(false)
            depthModelJob = scope.launch {
                withContext(worker) { depthEstimator?.close(); depthEstimator = null; depthAdapter.reset() }
                _state.update { it.copy(depthEnabled = false, depthReady = false, depthLoading = false,
                    depthStatus = "Désactivée · fallback RGB", depthInferenceMs = 0.0,
                    approachEnabled = false) }
                trace("depth_mode_changed", "enabled" to false)
            }
            return
        }
        _state.update { it.copy(depthEnabled = true, depthReady = false, depthLoading = true,
            depthStatus = "Chargement de MiDaS v2.1 Small…") }
        depthModelJob = scope.launch {
            try {
                val loaded = withContext(worker) {
                    depthEstimator?.close()
                    depthAdapter.reset()
                    MidasV21DepthEstimator(appContext, useXnnpack = _state.value.xnnpack).also {
                        if (closed) it.close() else depthEstimator = it
                    }
                }
                if (closed) return@launch
                depthEstimator = loaded
                _state.update { it.copy(depthEnabled = true, depthReady = true, depthLoading = false,
                    depthStatus = "Prête · estimation de profondeur inverse relative uniquement") }
                trace("depth_model_loaded", "provider" to loaded.requestedProvider)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                depthEstimator = null
                dashboardLastError = "Profondeur : ${error.message}"
                _state.update { it.copy(depthEnabled = false, depthReady = false, depthLoading = false,
                    depthStatus = "Indisponible : ${error.message} · fallback RGB") }
                trace("depth_model_error", "error" to error.toString())
            }
        }
    }

    fun setApproachEnabled(enabled: Boolean) {
        if (closed || _state.value.running || _state.value.pocketPreparing ||
            (enabled && !_state.value.depthEnabled)) return
        dangerEngine.setApproachEnabled(enabled)
        _state.update { it.copy(approachEnabled = enabled,
            resolutionStatus = if (enabled) "Résolution Oria · approche relative expérimentale"
            else "Résolution Oria · approche désactivée") }
        trace("approach_mode_changed", "enabled" to enabled, "metricTtc" to false)
    }

    fun loadModel(xnnpack: Boolean) {
        if (closed || _state.value.running || _state.value.depthEnabled || _state.value.depthLoading ||
            modelJob?.isActive == true) return
        updateDependency(OriaRuntimeDependency.DETECTOR, OriaDependencyAvailability.STARTING, "Chargement du modèle")
        _state.update { it.copy(modelReady = false, modelLoading = true, xnnpack = xnnpack, status = "Chargement du modèle…") }
        modelJob = scope.launch {
            try {
                val loaded = withContext(worker) {
                    if (appProvenance == null) appProvenance = readAppProvenance()
                    detector?.close()
                    OnnxObjectDetector(appContext, useXnnpack = xnnpack).also { loaded ->
                        modelManifest = appContext.assets.open(OnnxObjectDetector.MANIFEST_ASSET)
                            .bufferedReader().use { JSONObject(it.readText()) }
                        if (closed) loaded.close() else detector = loaded
                    }
                }
                if (closed) return@launch
                detector = loaded
                _state.update { it.copy(modelReady = true, modelLoading = false, status = "Modèle prêt · session arrêtée") }
                updateDependency(OriaRuntimeDependency.DETECTOR, OriaDependencyAvailability.AVAILABLE, "Modèle prêt")
                if (_state.value.connected && !_state.value.running) updateRuntime { ready(generation, now(), true) }
                trace("model_loaded", "provider" to loaded.requestedProvider)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                detector = null
                dashboardLastError = "Modèle : ${error.message}"
                _state.update { it.copy(modelReady = false, modelLoading = false, status = "Modèle indisponible : ${error.message}") }
                if (_state.value.connected) updateRuntime { failed(generation, now(), OriaRuntimeDependency.DETECTOR,
                    "Modèle indisponible : ${error.message}") }
                else updateDependency(OriaRuntimeDependency.DETECTOR, OriaDependencyAvailability.FAILED,
                    "Modèle indisponible : ${error.message}")
                trace("model_error", "error" to error.toString())
            }
        }
    }

    fun connect(simulator: Boolean) {
        if (_state.value.running) stop()
        if (manager.isConnected()) manager.disconnect()
        manager.setSimulator(simulator)
        _state.update { it.copy(status = "Connexion en cours…", orientationVerified = false) }
        manager.connect()
    }

    fun disconnect() {
        navigationCoordinator.stop("Déconnexion · navigation arrêtée")
        stop("Déconnexion"); manager.disconnect()
    }

    fun setPocketMode(enabled: Boolean) {
        if (_state.value.running || _state.value.pocketPreparing) return
        _state.update { it.copy(pocketEnabled = enabled,
            pocketStatus = if (enabled) "Expérimental · prochain démarrage, sans limite de durée" else "Désactivé · garder l’application ouverte") }
    }
    fun pocketServicePreparing() { _state.update { it.copy(pocketPreparing = true, pocketActive = false,
        pocketStatus = "Préparation du mode poche · garder Oria visible") } }
    fun pocketServiceReady() { _state.update { it.copy(pocketPreparing = false, pocketActive = true,
        pocketStatus = "Mode poche actif · arrêt disponible dans la notification") } }
    fun reportPocketError(reason: String) { _state.update { it.copy(pocketPreparing = false, pocketActive = false, pocketStatus = reason) } }
    fun canContinueInBackground(): Boolean {
        val live = _state.value
        return PocketSessionPolicy.canContinue(PocketSessionService.isReadyFor(this), live.running,
            live.connected, live.simulator,
            oriaLabState.value.phase in setOf(OriaLabPhase.RECORDING, OriaLabPhase.FINALIZING),
            PocketSessionService.elapsedMs())
    }


    /** Explicit user action only. Restart the stream so its H.264 headers belong to the capture. */
    fun startOriaLab() {
        if (closed || !manager.isConnected() || !_state.value.modelReady ||
            oriaLabState.value.phase in setOf(OriaLabPhase.RECORDING, OriaLabPhase.FINALIZING)) return
        stop("Préparation d’une nouvelle capture Oria Lab")
        val metadata = recordingMetadata(generation + 1)
        if (!manager.oriaLabRecorder.start(metadata)) return
        start()
        if (!_state.value.running) manager.oriaLabRecorder.stop("Démarrage de la perception refusé")
    }

    fun stopOriaLab() { stop("Oria Lab arrêté · finalisation de la capture") }

    suspend fun exportOriaLab(sessionId: String, destination: Uri) = withContext(Dispatchers.IO) {
        manager.oriaLabRecorder.withExport(sessionId) { archive ->
            val output = appContext.contentResolver.openOutputStream(destination, "w")
                ?: error("Impossible d’ouvrir l’emplacement choisi")
            output.use { sink -> archive.inputStream().use { source -> source.copyTo(sink) } }
        }
    }

    suspend fun renameOriaLab(sessionId: String, name: String) {
        check(!closed && !_state.value.running && !_state.value.pocketPreparing) { "Arrêtez la perception avant de renommer une capture" }
        manager.oriaLabRecorder.renameSession(sessionId, name)
    }

    suspend fun archiveOriaLab(sessionId: String) {
        check(!closed && !_state.value.running && !_state.value.pocketPreparing) { "Arrêtez la perception avant de retirer une capture" }
        manager.oriaLabRecorder.archiveSession(sessionId)
    }

    suspend fun restoreOriaLab(sessionId: String) {
        check(!closed && !_state.value.running && !_state.value.pocketPreparing) { "Arrêtez la perception avant de restaurer une capture" }
        manager.oriaLabRecorder.restoreSession(sessionId)
    }

    fun start() {
        if (closed || _state.value.running || !manager.isConnected() || !_state.value.modelReady ||
            _state.value.depthLoading || (_state.value.depthEnabled && !_state.value.depthReady) ||
            oriaLabState.value.storageBusy) return
        val id = ++generation
        startedAt = now(); streamStartedAt = 0L; lastFrameAt = 0L; lastAcceptedObservationAt = 0L
        performance.reset(startedAt)
        loadProfile = loadController.reset()
        depthFrameSequence = 0L
        lastHealthSampleAt = Long.MIN_VALUE
        lastCpuWallMs = startedAt
        lastCpuElapsedMs = android.os.Process.getElapsedCpuTime()
        latencies.clear(); allInferenceLatencies.clear()
        dashboardObjects = emptyList()
        dashboardDecision = OriaDashboardSnapshot.initial().decision
        dashboardLastError = null
        depthAdapter.reset()
        engine.start(id, startedAt)
        dangerEngine.reset(id)
        audioScheduler.reset(id, _state.value.audioSilent)
        silenceDangerPattern()
        updateRuntime { starting(id, startedAt, voiceBackendReady()) }
        _state.update { it.copy(running = true, status = "Démarrage de la vidéo…", received = 0,
            analyzed = 0, stale = 0, fps = 0.0, lastLatencyMs = 0, p95Ms = 0, allInferenceP95Ms = 0,
            preprocessMs = 0.0, inferenceMs = 0.0, depthInferenceMs = 0.0,
            detections = emptyList(), preview = null) }
        localSpeech.setSessionActive(_state.value.voiceBackend == OriaVoiceBackend.BLUETOOTH && !_state.value.simulator)
        trace("start", "policyAtMs" to startedAt, "rotation" to _state.value.rotation, "mirrored" to _state.value.mirrored,
            "sampleIntervalMs" to ViveGlassKitManager.ORIA_SAMPLE_INTERVAL_MS,
            "trackingMode" to engine.config.trackingMode.name, "pocketMode" to _state.value.pocketActive,
            "depthEnabled" to _state.value.depthEnabled, "approachEnabled" to _state.value.approachEnabled)
        startJob = scope.launch {
            try {
                val started = manager.startOriaVideo(id, _state.value.rotation, _state.value.mirrored) { frame ->
                    if (!_state.value.running || frame.sessionId != generation || closed) false
                    else {
                        frames.trySend(frame).isSuccess.also { accepted -> if (accepted) scope.launch {
                            if (frame.sessionId == generation) {
                                lastFrameAt = now()
                                performance.recordReceived()
                                _state.update { it.copy(received = it.received + 1) }
                            }
                        } }
                    }
                }
                if (started && id == generation) streamStartedAt = now()
                if (!started && id == generation) stop("Démarrage vidéo refusé · vérifier les autorisations HTC",
                    OriaRuntimePhase.FAILED)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (id == generation) stop("Vidéo : ${error.message}", OriaRuntimePhase.FAILED) }
        }
    }

    fun stop(reason: String = "Session arrêtée", terminalPhase: OriaRuntimePhase? = null) {
        restorePerceptionAfterVoice = false
        transcriptionGate.activeId?.let { manager.cancelOriaTranscription("perception_stopped") }
        transcriptionGate.cancel()
        transcriptionTimeout?.cancel(); transcriptionTimeout = null
        PocketSessionService.release(this, appContext)
        _state.update { it.copy(pocketPreparing = false, pocketActive = false,
            pocketStatus = if (it.pocketEnabled) "Mode poche arrêté · redémarrage manuel" else it.pocketStatus) }
        val stoppedSession = generation
        ++generation
        startJob?.cancel(); startJob = null
        if (pendingSpeech?.backend == OriaVoiceBackend.BLUETOOTH) cancelLocalSpeech(
            "Lecture locale arrêtée", finishScheduler = false, pauseAutomatic = false)
        else if (pendingSpeech != null) uncertainAudio("Arrêt demandé · parole déjà soumise possiblement en cours")
        audioScheduler.reset(generation, _state.value.audioSilent)
        silenceDangerPattern()
        localSpeech.setSessionActive(false)
        engine.stop()
        dangerEngine.reset()
        depthAdapter.reset()
        dashboardObjects = emptyList()
        dashboardDecision = OriaDashboardSnapshot.initial().decision
        if (terminalPhase == OriaRuntimePhase.FAILED) dashboardLastError = reason
        // The recorder filters by the old pipeline ID and must see this before the manager closes it.
        trace("stop", "reason" to reason, "sessionId" to stoppedSession)
        manager.stopOriaVideo()
        while (true) { (frames.tryReceive().getOrNull() ?: break).bitmap.recycle() }
        _state.update { it.copy(running = false, status = reason, detections = emptyList(), preview = null, suppression = "session_stopped") }
        updateRuntime {
            when (terminalPhase) {
                OriaRuntimePhase.DISCONNECTED -> disconnected(generation, now(), reason)
                OriaRuntimePhase.FAILED -> failed(generation, now(), OriaRuntimeDependency.CAMERA, reason)
                OriaRuntimePhase.INTERRUPTED -> interrupted(generation, now(), reason)
                else -> if (_state.value.connected && _state.value.modelReady) ready(generation, now(), true)
                    else if (!_state.value.connected) disconnected(generation, now(), reason)
                    else interrupted(generation, now(), reason)
            }
        }
    }

    fun setGeometry(rotation: Int, mirrored: Boolean) {
        if (_state.value.running) stop("Repère modifié · redémarrer la session")
        _state.update { it.copy(rotation = rotation, mirrored = mirrored, orientationVerified = false) }
    }

    fun confirmOrientation(verified: Boolean) {
        _state.update { it.copy(orientationVerified = verified) }
        trace("orientation_confirmed", "verified" to verified,
            "rotation" to _state.value.rotation, "mirrored" to _state.value.mirrored)
    }

    fun testVoice(pan: SpeechPan = SpeechPan.CENTER) {
        speakManualWithPan(TEST_PHRASE, pan)
    }

    /** Manual HTC Chat and Oria voice tests share the same reservation and callback gate. */
    fun speakManual(text: String) = speakManualWithPan(text, SpeechPan.CENTER)

    private fun speakManualWithPan(text: String, pan: SpeechPan) {
        if (closed || text.isBlank()) return
        if (!manager.isConnected() || _state.value.audioUnknown || _state.value.audioSilent) return
        if (!voiceBackendReady()) {
            _state.update { it.copy(audio = it.localVoiceStatus) }
            return
        }
        val created = now()
        val request = OriaAudioRequest(UUID.randomUUID().toString(), generation,
            OriaAudioKind.COMMAND_RESPONSE, OriaAudioPriority.DESCRIPTION, text, pan,
            created, created, created + 10_000)
        handleNonDangerDecision(audioScheduler.offer(request, created), "command")
    }

    private fun offerDangerSpeech(alert: VoiceAlert, resolution: DangerResolutionSnapshot) {
        val created = now()
        val expires = alert.observedAtMs + MAX_AGE_MS
        if (created > expires) return
        val pan = when (alert.zone) {
            RgbZone.LEFT -> SpeechPan.LEFT
            RgbZone.CENTER -> SpeechPan.CENTER
            RgbZone.RIGHT -> SpeechPan.RIGHT
        }
        val priority = dangerPriority(resolution)
        val request = OriaAudioRequest(UUID.randomUUID().toString(), generation, OriaAudioKind.DANGER,
            priority, alert.text, pan, alert.observedAtMs, created, expires)
        val decision = audioScheduler.offer(request, created)
        traceAudioDecision(decision, "danger")
        decision.cancel?.let { cancelled ->
            if (cancelled.kind == OriaAudioKind.NAVIGATION) {
                dashboardNavigationPreemptions++
                dashboardNavigationAwaitingResume = true
                navigationCoordinator.onDangerPreemptedNavigation()
            }
            val pending = pendingSpeech
            if (pending?.id == cancelled.id && pending.backend == OriaVoiceBackend.BLUETOOTH) {
                cancelLocalSpeech("Annonce préemptée par un danger prioritaire",
                    finishScheduler = false, pauseAutomatic = false)
            } else if (pending != null) {
                uncertainAudio("Préemption impossible sur la sortie HTC")
                traceAudioDecision(audioScheduler.routeLost(), "unsafe_preemption")
                return
            }
        }
        if (decision.dispatch?.id != request.id) return
        val submittedAtMs = now()
        val ticket = engine.onSubmitted(alert, submittedAtMs)
        trace("policy_voice", "action" to "submitted", "policyAtMs" to submittedAtMs,
            "accepted" to (ticket != null), "alertId" to alert.id, "ticketId" to (ticket?.id ?: -1L),
            "trackId" to alert.trackId, "frameId" to alert.frameId, "videoSessionId" to alert.sessionId)
        if (ticket == null) finishScheduled(request, OriaAudioDecisionReason.SUBMISSION_FAILED)
        else sendSpeech(request, ticket, alert.observedAtMs)
    }

    private fun dangerPriority(resolution: DangerResolutionSnapshot): OriaAudioPriority {
        val selected = resolution.selected
        val approaching = resolution.approach.any { it.ownerKey == resolution.ownerKey &&
            it.trend == OriaDepthTrend.APPROACHING && it.confidence >= .7f }
        return when {
            selected?.level == DangerLevel.HIGH &&
                (selected.relativeProximity ?: 0f) >= .9f && approaching -> OriaAudioPriority.DANGER_CRITICAL
            selected?.level == DangerLevel.HIGH -> OriaAudioPriority.DANGER_HIGH
            else -> OriaAudioPriority.DANGER_LOW
        }
    }

    private fun handleNonDangerDecision(decision: OriaAudioDecision, origin: String) {
        traceAudioDecision(decision, origin)
        decision.dispatch?.let(::dispatchScheduled)
    }

    private fun dispatchScheduled(request: OriaAudioRequest) {
        if (!_state.value.connected || _state.value.audioSilent || _state.value.audioUnknown ||
            _state.value.audioAutomaticPaused || !voiceBackendReady()) {
            finishScheduled(request, OriaAudioDecisionReason.SUBMISSION_FAILED)
            return
        }
        sendSpeech(request, null)
    }

    private fun finishScheduled(request: OriaAudioRequest, reason: OriaAudioDecisionReason) {
        val decision = audioScheduler.finish(request.id, request.generation, now(), reason)
        traceAudioDecision(decision, "finish_${reason.name.lowercase()}")
        decision.dispatch?.let(::dispatchScheduled)
    }

    private fun traceAudioDecision(decision: OriaAudioDecision, origin: String) {
        dashboardAudioDrops += decision.dropped.size
        _state.update { it.copy(audioQueueStatus = when {
            decision.dispatch != null -> "Lecture ${decision.dispatch.kind.name.lowercase()}"
            audioScheduler.snapshot().queued.isNotEmpty() -> "${audioScheduler.snapshot().queued.size} élément(s) en attente"
            it.audioSilent -> "Silencieux · perception active"
            else -> "Ordonnanceur prêt"
        }) }
        trace("audio_scheduler", "origin" to origin, "reason" to decision.reason.name,
            "dispatchId" to (decision.dispatch?.id ?: "none"),
            "dispatchKind" to (decision.dispatch?.kind?.name ?: "none"),
            "cancelId" to (decision.cancel?.id ?: "none"),
            "dropped" to decision.dropped.joinToString(",") { it.id },
            "generation" to audioScheduler.snapshot().generation,
            "muted" to audioScheduler.snapshot().muted)
    }

    private fun updateDangerPattern(resolution: DangerResolutionSnapshot) {
        val selected = resolution.selected
        val enabled = _state.value.dangerTonesEnabled && !_state.value.audioSilent &&
            _state.value.voiceBackend == OriaVoiceBackend.BLUETOOTH && _state.value.localVoiceReady
        val level = if (!enabled || selected == null) DangerSoundLevel.NONE else {
            val approaching = resolution.approach.any { it.ownerKey == resolution.ownerKey &&
                it.trend == OriaDepthTrend.APPROACHING && it.confidence >= .7f }
            when {
                selected.level == DangerLevel.HIGH && (selected.relativeProximity ?: 0f) >= .9f && approaching ->
                    DangerSoundLevel.CRITICAL
                selected.level == DangerLevel.HIGH -> DangerSoundLevel.HIGH
                selected.level == DangerLevel.MEDIUM -> DangerSoundLevel.MEDIUM
                else -> DangerSoundLevel.LOW
            }
        }
        val pan = when (selected?.zone) {
            RgbZone.LEFT -> SpeechPan.LEFT
            RgbZone.RIGHT -> SpeechPan.RIGHT
            else -> SpeechPan.CENTER
        }
        val pattern = DangerSoundPattern.forLevel(level, pan)
        if (pattern != lastDangerPattern) {
            lastDangerPattern = pattern
            localSpeech.setDangerPattern(pattern)
            trace("danger_pattern", "level" to level.name, "pan" to pan.name,
                "frequencyHz" to pattern.frequencyHz, "cycleMs" to pattern.cycleMs,
                "activeMs" to pattern.activeMs)
        }
    }

    /** Entry point reserved for R06; stale maneuvers are never resumed after danger speech. */
    fun offerNavigationInstruction(id: String, text: String, observedAtMs: Long,
                                   expiresAtMs: Long, pan: SpeechPan = SpeechPan.CENTER) {
        if (closed || text.isBlank() || _state.value.audioUnknown || _state.value.audioSilent) return
        val created = now()
        if (observedAtMs < 0 || observedAtMs > created || expiresAtMs < created) return
        val request = OriaAudioRequest(id, generation, OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION, text, pan, observedAtMs, created, expiresAtMs)
        handleNonDangerDecision(audioScheduler.offer(request, created), "navigation")
    }

    private fun onNavigationSpeech(speech: NavigationSpeech) {
        val navigation = navigationState.value
        if (navigation.routeVersion != speech.routeVersion ||
            navigation.phase !in setOf(NavigationPhase.ACTIVE, NavigationPhase.ARRIVED)) return
        if (dashboardNavigationAwaitingResume) {
            dashboardNavigationAwaitingResume = false
            dashboardNavigationResumptions++
        }
        offerNavigationInstruction(speech.id, speech.text, speech.observedAtMs, speech.expiresAtMs)
    }

    private fun invalidateNavigationAudio(reason: String) {
        val decision = audioScheduler.invalidate(OriaAudioKind.NAVIGATION, now())
        val cancelled = decision.cancel
        if (cancelled != null) {
            val pending = pendingSpeech
            if (pending?.id == cancelled.id && pending.backend == OriaVoiceBackend.BLUETOOTH) {
                cancelLocalSpeech("Instruction de navigation invalidée",
                    finishScheduler = false, pauseAutomatic = false)
            } else if (pending != null) {
                uncertainAudio("Instruction HTC invalidée sans annulation confirmée")
                return
            }
        }
        traceAudioDecision(decision, reason)
        decision.dispatch?.let(::dispatchScheduled)
    }

    private fun traceNavigation(type: String, fields: Map<String, Any>) {
        if (type in setOf("navigation_stop", "navigation_arrived", "navigation_error")) {
            dashboardNavigationAwaitingResume = false
        }
        trace(type, *fields.map { it.key to it.value }.toTypedArray())
    }

    fun hasNavigationLocationPermission(): Boolean = navigationCoordinator.hasLocationPermission(appContext)
    fun updateNavigationQuery(value: String) = navigationCoordinator.updateQuery(value)
    fun searchNavigation(query: String, simulated: Boolean) = navigationCoordinator.search(query,
        if (simulated) NavigationMode.SIMULATED else NavigationMode.REAL)
    fun selectNavigationSuggestion(place: GeocodedPlace) = navigationCoordinator.select(place)
    fun confirmNavigationDestination() = navigationCoordinator.confirmSelected()
    fun startSavedNavigation(destination: SavedDestination, simulated: Boolean) =
        navigationCoordinator.startSaved(destination, if (simulated) NavigationMode.SIMULATED else NavigationMode.REAL)
    fun saveNavigationDestination(name: String, address: String, id: String? = null) =
        navigationCoordinator.saveDestination(name, address, id)
    fun deleteNavigationDestination(id: String) = navigationCoordinator.deleteDestination(id)
    fun pauseNavigation() = navigationCoordinator.pause()
    fun resumeNavigation() = navigationCoordinator.resume()
    fun stopNavigation() = navigationCoordinator.stop()
    fun advanceSimulatedNavigation() = navigationCoordinator.advanceSimulation()

    /** Single press repeats context after a short double-press window; double press listens. */
    fun onEagleAiButton() {
        if (closed) return
        if (transcriptionGate.activeId != null) {
            _interactionState.update { it.copy(status = "Écoute déjà active") }
            return
        }
        val (action, token) = buttonSequencer.press(now())
        buttonTimeout?.cancel()
        when (action) {
            EagleButtonAction.START_VOICE_COMMAND -> beginVoiceCommand()
            EagleButtonAction.WAIT_FOR_SECOND_PRESS -> buttonTimeout = scope.launch {
                delay(560)
                if (buttonSequencer.timeout(token, now()) == EagleButtonAction.RUN_SINGLE_PRESS) {
                    repeatOrDescribe()
                }
            }
            EagleButtonAction.RUN_SINGLE_PRESS -> repeatOrDescribe()
            EagleButtonAction.IGNORE -> Unit
        }
    }

    fun beginVoiceCommand() {
        if (closed || transcriptionGate.activeId != null) {
            _interactionState.update { it.copy(status = "Écoute déjà active") }
            return
        }
        if (!manager.isConnected()) {
            _interactionState.value = OriaInteractionState(OriaInteractionPhase.FAILED,
                "Lunettes déconnectées")
            return
        }
        buttonTimeout?.cancel(); buttonTimeout = null
        buttonSequencer.reset()
        if (pendingSpeech?.backend == OriaVoiceBackend.HTC) {
            _interactionState.value = OriaInteractionState(OriaInteractionPhase.FAILED,
                "Attendez la fin de la réponse vocale")
            return
        }
        pendingSpeech?.let { cancelLocalSpeech("Commande vocale demandée", pauseAutomatic = false) }
        val wasRunning = _state.value.running
        if (wasRunning) stop("Écoute d’une commande vocale", OriaRuntimePhase.INTERRUPTED)
        restorePerceptionAfterVoice = wasRunning
        val requestId = UUID.randomUUID().toString()
        if (!transcriptionGate.begin(requestId)) return
        _interactionState.value = OriaInteractionState(OriaInteractionPhase.LISTENING,
            "Écoute sur les lunettes…")
        trace("voice_command_start", "requestId" to requestId,
            "restorePerception" to restorePerceptionAfterVoice)
        scope.launch {
            val accepted = manager.startOriaTranscription(requestId)
            if (!accepted && transcriptionGate.activeId == requestId) {
                finishVoiceFailure(requestId, "Microphone indisponible")
            }
        }
        transcriptionTimeout?.cancel()
        transcriptionTimeout = scope.launch {
            delay(12_000)
            if (transcriptionGate.activeId == requestId) {
                manager.cancelOriaTranscription("timeout")
                finishVoiceFailure(requestId, "Aucune commande entendue")
            }
        }
    }

    private fun onOriaTranscription(event: OriaTranscriptionEvent) {
        if (!transcriptionGate.complete(event.requestId)) {
            trace("voice_command_late_callback", "requestId" to event.requestId,
                "status" to event.status.name)
            return
        }
        transcriptionTimeout?.cancel(); transcriptionTimeout = null
        if (event.status != OriaTranscriptionStatus.SUCCESS || event.text.isNullOrBlank()) {
            val reason = when (event.status) {
                OriaTranscriptionStatus.RESOURCE_CONFLICT -> "Microphone occupé. Réessayez."
                OriaTranscriptionStatus.CANCELLED -> "Commande annulée"
                OriaTranscriptionStatus.ERROR -> "Transcription indisponible"
                OriaTranscriptionStatus.SUCCESS -> "Je n’ai rien entendu"
            }
            finishVoiceFailure(event.requestId, reason)
            return
        }
        val parsed = OriaVoiceIntentParser.parse(event.text)
        _interactionState.value = OriaInteractionState(OriaInteractionPhase.EXECUTING,
            "Commande reconnue", parsed.transcript, parsed.intent.javaClass.simpleName)
        trace("voice_command_parsed", "requestId" to event.requestId,
            "transcript" to parsed.transcript, "intent" to parsed.intent.javaClass.simpleName)
        val response = executeVoiceIntent(parsed.intent)
        restoreAfterVoiceIfNeeded()
        speakCommandResponse(response)
        _interactionState.update { it.copy(phase = OriaInteractionPhase.IDLE, status = response) }
    }

    private fun executeVoiceIntent(intent: OriaVoiceIntent): String = when (intent) {
        OriaVoiceIntent.StartPerception -> {
            restorePerceptionAfterVoice = true
            "Démarrage de la perception."
        }
        OriaVoiceIntent.StopPerception -> {
            restorePerceptionAfterVoice = false
            "Perception arrêtée."
        }
        OriaVoiceIntent.RepeatActive -> activeInformation()
        OriaVoiceIntent.DescribeAhead -> describeAhead()
        is OriaVoiceIntent.GuideTo -> {
            voiceDestinationPending = true
            lastVoiceDestinationSignature = null
            navigationCoordinator.search(intent.destination, NavigationMode.REAL)
            "Recherche de ${intent.destination}."
        }
        OriaVoiceIntent.PauseNavigation -> {
            if (navigationState.value.phase == NavigationPhase.ACTIVE) {
                navigationCoordinator.pause(); "Navigation en pause."
            } else "Aucune navigation active à mettre en pause."
        }
        OriaVoiceIntent.ResumeNavigation -> {
            if (navigationState.value.phase == NavigationPhase.PAUSED) {
                navigationCoordinator.resume(); "Navigation reprise."
            } else "La navigation n’est pas en pause."
        }
        OriaVoiceIntent.StopNavigation -> {
            voiceDestinationPending = false
            navigationCoordinator.stop(); "Navigation arrêtée."
        }
        OriaVoiceIntent.MuteAlerts -> {
            if (_state.value.audioSilent) "Les alertes vocales sont déjà coupées."
            else {
                muteAfterCommandResponse = true
                "Alertes vocales coupées."
            }
        }
        OriaVoiceIntent.UnmuteAlerts -> {
            setAudioSilent(false); "Alertes vocales réactivées."
        }
        OriaVoiceIntent.ConfirmDestination -> {
            val navigation = navigationState.value
            if (navigation.phase == NavigationPhase.CONFIRMATION && navigation.selected != null) {
                if (navigation.mode == NavigationMode.REAL && !hasNavigationLocationPermission()) {
                    "Autorisez d’abord la localisation sur le téléphone."
                } else {
                    voiceDestinationPending = false
                    navigationCoordinator.confirmSelected()
                    "Destination confirmée. Calcul de l’itinéraire."
                }
            } else "Aucune destination unique à confirmer."
        }
        is OriaVoiceIntent.SelectDestination -> {
            val place = navigationState.value.suggestions.getOrNull(intent.index)
            if (place == null) "Ce résultat n’est pas disponible."
            else {
                navigationCoordinator.select(place)
                lastVoiceDestinationSignature = null
                "${place.label}. Dites : confirme destination."
            }
        }
        OriaVoiceIntent.Cancel -> {
            voiceDestinationPending = false
            if (navigationState.value.phase in setOf(NavigationPhase.SEARCHING, NavigationPhase.CONFIRMATION)) {
                navigationCoordinator.stop("Recherche annulée")
            }
            "Commande annulée."
        }
        is OriaVoiceIntent.Ambiguous -> intent.response
        is OriaVoiceIntent.Unknown -> intent.response
    }

    private var muteAfterCommandResponse = false

    private fun repeatOrDescribe() = speakCommandResponse(activeInformation())

    private fun activeInformation(): String {
        val navigation = navigationState.value
        if (navigation.phase in setOf(NavigationPhase.ACTIVE, NavigationPhase.PAUSED,
                NavigationPhase.RECALCULATING, NavigationPhase.LIMITED)) {
            navigation.currentInstruction?.let { return it }
        }
        return _state.value.lastAlert.takeUnless { it == "Aucune annonce" || it.isBlank() } ?: describeAhead()
    }

    private fun describeAhead(): String {
        val descriptions = _state.value.detections.asSequence()
            .filter(RgbAlertPolicy::qualifies)
            .sortedByDescending { it.box.area }
            .mapNotNull { detection -> RgbCategory.fromClassId(detection.classId)?.let { category ->
                "${category.label} ${RgbZone.fromCenterX(detection.box.centerX).voiceSuffix}"
            } }.distinct().take(3).toList()
        return if (descriptions.isEmpty()) "Aucun danger reconnu devant pour le moment."
        else descriptions.joinToString(prefix = "Devant : ", separator = ", ", postfix = ".")
    }

    private fun restoreAfterVoiceIfNeeded() {
        if (restorePerceptionAfterVoice && !_state.value.running && manager.isConnected()) {
            _interactionState.update { it.copy(phase = OriaInteractionPhase.RECOVERING,
                status = "Restauration de la perception…") }
            start()
        }
        restorePerceptionAfterVoice = false
    }

    private fun finishVoiceFailure(requestId: String, reason: String) {
        if (transcriptionGate.activeId != null && !transcriptionGate.complete(requestId)) return
        transcriptionTimeout?.cancel(); transcriptionTimeout = null
        restoreAfterVoiceIfNeeded()
        _interactionState.value = OriaInteractionState(OriaInteractionPhase.FAILED, reason)
        trace("voice_command_failed", "requestId" to requestId, "reason" to reason)
        speakCommandResponse(reason)
    }

    private fun speakCommandResponse(text: String) {
        if (text.isBlank() || _state.value.audioSilent) return
        if (_state.value.voiceBackend == OriaVoiceBackend.BLUETOOTH && !_state.value.running) {
            commandOnlyAudioRoute = true
            localSpeech.setSessionActive(true)
            scope.launch {
                delay(250)
                if (voiceBackendReady()) speakManual(text)
                else completeCommandAudio(success = false)
            }
        } else speakManual(text)
    }

    private fun announceVoiceDestinationChoice(navigation: NavigationUiState) {
        if (!voiceDestinationPending) return
        if (navigation.phase == NavigationPhase.FAILED) {
            val signature = "failed:${navigation.status}"
            if (signature != lastVoiceDestinationSignature) {
                lastVoiceDestinationSignature = signature
                voiceDestinationPending = false
                speakCommandResponse("Destination indisponible. ${navigation.status}")
            }
            return
        }
        if (navigation.phase != NavigationPhase.CONFIRMATION || navigation.suggestions.isEmpty()) return
        val signature = navigation.suggestions.joinToString("|") { it.id } + ":${navigation.selected?.id}"
        if (signature == lastVoiceDestinationSignature) return
        lastVoiceDestinationSignature = signature
        val response = when {
            navigation.selected != null ->
                "Destination ${navigation.selected.label}. Dites : confirme destination."
            navigation.suggestions.size == 1 ->
                "Destination ${navigation.suggestions.first().label}. Dites : confirme destination."
            else -> "Plusieurs destinations trouvées. Première : ${navigation.suggestions[0].label}. " +
                "Deuxième : ${navigation.suggestions[1].label}. Dites première ou deuxième destination."
        }
        speakCommandResponse(response)
    }

    fun setAudioSilent(silent: Boolean) {
        if (closed) return
        val decision = audioScheduler.setMuted(silent)
        if (decision.cancel != null) {
            if (pendingSpeech?.backend == OriaVoiceBackend.BLUETOOTH) {
                cancelLocalSpeech("Mode silencieux activé · perception toujours active",
                    finishScheduler = false, pauseAutomatic = false)
            } else if (pendingSpeech != null) {
                _state.update { it.copy(audioSilent = silent) }
                uncertainAudio("Mode silencieux demandé, mais l’arrêt de la voix HTC ne peut pas être confirmé")
                return
            }
        }
        silenceDangerPattern()
        _state.update { it.copy(audioSilent = silent, audioBusy = if (silent) false else it.audioBusy,
            audioQueueStatus = if (silent) "Silencieux · perception active" else "Ordonnanceur audio actif",
            audio = if (silent) "Audio coupé · perception toujours active" else "Audio réactivé") }
        traceAudioDecision(decision, "silent_mode")
    }

    fun setDangerTonesEnabled(enabled: Boolean) {
        if (closed) return
        _state.update { it.copy(dangerTonesEnabled = enabled) }
        if (!enabled) silenceDangerPattern()
        trace("danger_tones", "enabled" to enabled)
    }

    private fun voiceBackendReady(): Boolean = _state.value.voiceBackend == OriaVoiceBackend.HTC ||
        (_state.value.localVoiceReady && !_state.value.simulator)

    fun setVoiceBackend(backend: OriaVoiceBackend) {
        if (_state.value.running || pendingSpeech != null || _state.value.audioUnknown) return
        _state.update { it.copy(voiceBackend = backend, audioAutomaticPaused = true,
            audio = "Tester cette sortie vocale avant les annonces automatiques") }
        trace("voice_backend", "backend" to backend.name)
    }

    private fun sendSpeech(request: OriaAudioRequest, ticket: VoiceTicket?, observedAtMs: Long? = null) {
        val text = request.text
        val pan = request.pan
        // Persist before dispatch so an activity/process restart cannot invent a clean transport.
        if (!persistUncertainDelivery(true)) {
            if (!releaseLocalSpeech(ticket)) return
            finishScheduled(request, OriaAudioDecisionReason.SUBMISSION_FAILED)
            _state.update { it.copy(audio = "Impossible de journaliser la demande vocale") }
            return
        }
        val pending = PendingSpeech(ticket, manager.synthesisTransportEpoch, now(), text,
            request.id, _state.value.voiceBackend, generation, pan, request)
        pendingSpeech = pending
        _state.update { it.copy(audioBusy = true,
            audio = if (pending.backend == OriaVoiceBackend.HTC) "Phrase remise au SDK HTC" else "Lecture locale vers Bluetooth VIVE",
            lastAlert = text) }
        try {
            // Disk persistence and UI bookkeeping may have taken time after engine reservation.
            // No blocking work is allowed between this last observation check and SDK dispatch.
            if (ticket != null && (observedAtMs == null || now() - observedAtMs > MAX_AGE_MS ||
                    observedAtMs > now() || !_state.value.running || ticket.sessionId != generation)) {
                if (!releaseLocalSpeech(ticket)) return
                pendingSpeech = null
                persistUncertainDelivery(false)
                finishScheduled(request, OriaAudioDecisionReason.STALE)
                _state.update { it.copy(audioBusy = false, audio = "Annonce expirée avant envoi") }
                trace("speech_expired_before_dispatch")
                return
            }
            val startGuardRecorded = AtomicBoolean(false)
            val accepted = if (pending.backend == OriaVoiceBackend.HTC) manager.speakOriaText(text)
            else localSpeech.submit(pending.id, text, pan = pending.pan,
                canStart = {
                    val allowed = localRequestCurrent(pending) && (ticket == null ||
                        (observedAtMs != null && observedAtMs <= now() && now() - observedAtMs <= MAX_AGE_MS))
                    if (startGuardRecorded.compareAndSet(false, true)) trace("speech_pcm_start_guard",
                        "requestId" to pending.id, "allowed" to allowed, "videoSessionId" to pending.sessionGeneration,
                        "observationAgeMs" to (observedAtMs?.let { now() - it } ?: -1L),
                        "decisionToAndroidStartMs" to (now() - request.createdAtMs),
                        "kind" to request.kind.name, "priority" to request.priority.name)
                    // Trace bookkeeping must not weaken the final freshness boundary.
                    allowed && localRequestCurrent(pending) && (ticket == null ||
                        (observedAtMs != null && observedAtMs <= now() && now() - observedAtMs <= MAX_AGE_MS))
                },
                canContinue = { localRequestCurrent(pending) })
            if (!accepted) {
                if (!releaseLocalSpeech(ticket)) return
                pendingSpeech = null
                persistUncertainDelivery(false)
                finishScheduled(request, OriaAudioDecisionReason.SUBMISSION_FAILED)
                _state.update { it.copy(audioBusy = false, audioAutomaticPaused = true, audio = "Soumission vocale refusée") }
                trace("speech_rejected")
            } else {
                performance.recordAudio(request.createdAtMs, now().coerceAtLeast(request.createdAtMs))
                trace("speech_submitted", "text" to text, "backend" to pending.backend.name,
                "requestId" to pending.id, "observedAtMs" to (observedAtMs ?: -1L),
                "videoSessionId" to pending.sessionGeneration, "ticketId" to (ticket?.id ?: -1L),
                "pan" to if (pending.backend == OriaVoiceBackend.BLUETOOTH) pending.pan.name else "HTC_UNCONTROLLED",
                "kind" to request.kind.name, "priority" to request.priority.name,
                "device" to if (pending.backend == OriaVoiceBackend.BLUETOOTH)
                    _state.value.localVoiceStatus else "HTC_SDK")
            }
        }
        catch (error: Exception) { uncertainAudio("Envoi vocal interrompu : ${error.message}") }
    }

    /** Worker-readable validation; freshness is checked separately only before the first PCM. */
    private fun localRequestCurrent(pending: PendingSpeech): Boolean =
        !closed && pendingSpeech?.id == pending.id && generation == pending.sessionGeneration &&
            !_state.value.audioUnknown && _state.value.connected &&
            (pending.ticket == null || _state.value.running)

    /** Android delivery has a real request ID; unrelated and late results are ignored. */
    private fun onLocalSpeechResult(id: String, result: SpeechDelivery, detail: String) {
        if (closed) return
        val pending = pendingSpeech ?: return
        if (pending.backend != OriaVoiceBackend.BLUETOOTH || pending.id != id) return
        val accepted = pending.ticket?.let {
            applyVoiceResult(it, if (result == SpeechDelivery.COMPLETED) "confirmed" else "failed")
        } ?: true
        if (!accepted) { uncertainAudio("Retour local refusé par le moteur"); return }
        pendingSpeech = null
        persistUncertainDelivery(false)
        updateDependency(OriaRuntimeDependency.AUDIO,
            if (result == SpeechDelivery.COMPLETED) OriaDependencyAvailability.AVAILABLE else OriaDependencyAvailability.LIMITED,
            detail, optional = true)
        _state.update { it.copy(audioBusy = false, audioAutomaticPaused = result != SpeechDelivery.COMPLETED && result != SpeechDelivery.EXPIRED,
            audio = when (result) {
                SpeechDelivery.COMPLETED -> "Lecture Bluetooth terminée · écoute à confirmer"
                SpeechDelivery.EXPIRED -> "Annonce expirée avant lecture · attente d’une observation fraîche"
                SpeechDelivery.NOT_PLAYED -> "Voix non jouée : $detail"
                SpeechDelivery.INTERRUPTED -> "Lecture interrompue : $detail"
            }) }
        trace("speech_local_result", "requestId" to id, "result" to result.name, "detail" to detail,
            "videoSessionId" to pending.sessionGeneration,
            "delayMs" to (now() - pending.submittedAt))
        finishScheduled(pending.scheduled, when (result) {
            SpeechDelivery.COMPLETED -> OriaAudioDecisionReason.COMPLETED_NEXT
            SpeechDelivery.EXPIRED -> OriaAudioDecisionReason.STALE
            SpeechDelivery.NOT_PLAYED, SpeechDelivery.INTERRUPTED -> OriaAudioDecisionReason.SUBMISSION_FAILED
        })
        completeCommandAudio(success = result == SpeechDelivery.COMPLETED)
    }

    private fun completeCommandAudio(success: Boolean) {
        if (commandOnlyAudioRoute) {
            commandOnlyAudioRoute = false
            if (!_state.value.running) localSpeech.setSessionActive(false)
        }
        if (muteAfterCommandResponse) {
            muteAfterCommandResponse = false
            if (success) setAudioSilent(true)
        }
    }

    /** A cancelled local request cannot confirm a later ID. Never resets anonymous HTC uncertainty. */
    private fun cancelLocalSpeech(reason: String, finishScheduler: Boolean = true,
                                  pauseAutomatic: Boolean = true) {
        val pending = pendingSpeech ?: return
        if (pending.backend != OriaVoiceBackend.BLUETOOTH) return
        pendingSpeech = null // The worker's canContinue becomes false before stopping the player.
        localSpeech.stop()
        if (!releaseLocalSpeech(pending.ticket)) return
        persistUncertainDelivery(false)
        _state.update { it.copy(audioBusy = false,
            audioAutomaticPaused = if (pauseAutomatic) true else it.audioAutomaticPaused, audio = reason) }
        trace("speech_local_cancelled", "requestId" to pending.id, "reason" to reason,
            "sessionId" to pending.sessionGeneration, "videoSessionId" to pending.sessionGeneration)
        if (finishScheduler) finishScheduled(pending.scheduled, OriaAudioDecisionReason.SUBMISSION_FAILED)
    }

    /** A known local refusal releases the reservation only if the engine recognizes it. */
    private fun releaseLocalSpeech(ticket: VoiceTicket?): Boolean {
        if (ticket == null || applyVoiceResult(ticket, "failed")) return true
        uncertainAudio("Réservation vocale incohérente · reprise à vérifier")
        return false
    }

    private fun uncertainAudio(reason: String) {
        persistUncertainDelivery(true)
        val pending = pendingSpeech
        pending?.ticket?.let { applyVoiceResult(it, "ambiguous") }
        pendingSpeech = null
        val schedulerDecision = audioScheduler.routeLost()
        silenceDangerPattern()
        updateDependency(OriaRuntimeDependency.AUDIO, OriaDependencyAvailability.LIMITED, reason, optional = true)
        _state.update { it.copy(audioUnknown = true, audioBusy = false, audio = reason) }
        trace("audio_uncertain", "reason" to reason, "requestId" to (pending?.id ?: "none"),
            "videoSessionId" to (pending?.sessionGeneration ?: generation))
        traceAudioDecision(schedulerDecision, "uncertain_audio")
    }

    private fun silenceDangerPattern() {
        val silent = DangerSoundPattern.silent()
        lastDangerPattern = silent
        localSpeech.setDangerPattern(silent)
    }

    /** Record the exact clock supplied to policy, independently from transport/logging latency. */
    private fun applyVoiceResult(ticket: VoiceTicket, action: String): Boolean {
        val policyAtMs = now()
        val accepted = when (action) {
            "confirmed" -> engine.onConfirmed(ticket, policyAtMs)
            "failed" -> engine.onFailure(ticket, policyAtMs)
            "ambiguous" -> engine.onAmbiguous(ticket, policyAtMs)
            else -> error("Unknown policy voice action")
        }
        trace("policy_voice", "action" to action, "policyAtMs" to policyAtMs, "accepted" to accepted,
            "ticketId" to ticket.id, "alertId" to ticket.id, "trackId" to ticket.trackId,
            "sessionId" to ticket.sessionId, "videoSessionId" to ticket.sessionId)
        return accepted
    }

    private fun staleFrame() { _state.update { it.copy(stale = it.stale + 1) } }

    private fun persistUncertainDelivery(uncertain: Boolean): Boolean =
        audioPersistence.edit().putBoolean("delivery_pending_or_unknown", uncertain).commit()

    private fun trace(type: String, vararg fields: Pair<String, Any>) {
        val json = JSONObject().put("type", type).put("atMs", now()).put("sessionId", generation)
            .put("source", if (_state.value.simulator) "htc_simulator" else "htc_live")
        fields.forEach { (key, value) -> json.put(key, value) }
        if (json.has("frame")) json.put("frameId", json.get("frame"))
        val persistent = com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPrivacy.sanitize(json)
        traces.trySend(persistent.toString())
        Log.i("Oria", persistent.toString())
        // These two events have richer, explicitly labelled recordings below; avoid duplicates.
        if (type != "inference" && type != "decision") manager.oriaLabRecorder.recordEvent(persistent)
    }

    private fun recordingMetadata(videoSessionId: Long): JSONObject {
        val config = engine.config
        val policy = JSONObject()
            .put("maxObservationAgeMs", config.maxObservationAgeMs)
            .put("trackAssociationIou", config.trackAssociationIou)
            .put("trackLostAfterMs", config.trackLostAfterMs)
            .put("confirmationSamples", config.confirmationSamples)
            .put("confidenceExitMargin", config.confidenceExitMargin)
            .put("minimumTrackingConfidence", config.minimumTrackingConfidence)
            .put("selectionHoldMs", config.selectionHoldMs)
            .put("replacementScoreMargin", config.replacementScoreMargin)
            .put("repeatIntervalMs", config.repeatIntervalMs)
            .put("globalAnnouncementGapMs", config.globalAnnouncementGapMs)
            .put("failureRetryGapMs", config.failureRetryGapMs)
            .put("voiceMemoryRetentionMs", config.voiceMemoryRetentionMs)
            .put("maximumVoiceMemories", config.maximumVoiceMemories)
            .put("maximumTracks", config.maximumTracks)
            .put("trackingMode", config.trackingMode.name)
            .put("zoneLeftBoundary", 0.39).put("zoneRightBoundary", 0.61)
            .put("categories", JSONArray().apply { RgbCategory.entries.forEach { category ->
                put(JSONObject().put("classId", category.classId).put("label", category.label)
                    .put("alertable", category.alertable).put("confidenceThreshold", category.confidenceThreshold)
                    .put("priorityBias", category.priorityBias))
            } })
        val manifest = modelManifest?.let { JSONObject(it.toString()) } ?: JSONObject()
        return JSONObject().put("videoSessionId", videoSessionId)
            .put("schemaVersion", OriaLabRecorder.SCHEMA_VERSION)
            .put("captureConsent", "explicit_user_action")
            .put("destinationPersistence", "omitted")
            .put("source", if (_state.value.simulator) "htc_simulator" else "htc_live")
            .put("onnxSha256", manifest.optString("onnx_sha256"))
            .put("modelManifest", manifest).put("modelProvider", detector?.requestedProvider ?: "unknown")
            .put("depthEnabled", _state.value.depthEnabled)
            .put("depthModel", if (_state.value.depthEnabled) "MiDaS v2.1 Small relative inverse depth" else "disabled")
            .put("depthProvider", depthEstimator?.requestedProvider ?: "none")
            .put("depthMetricScale", false)
            .put("deviceModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("rotationAppliedDegrees", _state.value.rotation).put("mirrored", _state.value.mirrored)
            .put("orientationVerified", _state.value.orientationVerified)
            .put("voiceBackend", _state.value.voiceBackend.name)
            .put("sampleIntervalMs", ViveGlassKitManager.ORIA_SAMPLE_INTERVAL_MS)
            .put("detectionConfidenceFloor", 0.70).put("rawModelOutputIncluded", true)
            .put("rawModelOutputContract", "float32 flat [1,300,6], xyxy input pixels / score / classId")
            .put("modelDetectionsContract", "300 normalized rows, including empty clipped boxes; no confidence filter")
            .put("boxCoordinates", "normalized_xyxy_on_recorded_png")
            .put("policyConfig", policy).put("clock", "Android elapsedRealtime milliseconds")
            .apply { appProvenance?.let { provenance -> provenance.keys().forEach { key -> put(key, provenance.get(key)) } } }
    }

    /** Once per controller, on its worker; inability to hash the APK does not prevent capture. */
    private fun readAppProvenance(): JSONObject {
        val result = JSONObject().put("packageName", appContext.packageName)
        try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            result.put("versionName", info.versionName ?: "unknown").put("versionCode", info.longVersionCode)
                .put("lastUpdateTime", info.lastUpdateTime)
            val digest = MessageDigest.getInstance("SHA-256")
            File(appContext.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            result.put("apkSha256", digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) { result.put("appProvenanceError", e.message ?: e.javaClass.simpleName) }
        return result
    }

    private fun detectionJson(detection: Detection): JSONObject = JSONObject()
        .put("classId", detection.classId).put("confidence", detection.confidence)
        .put("box", JSONObject().put("left", detection.box.left).put("top", detection.box.top)
            .put("right", detection.box.right).put("bottom", detection.box.bottom))

    private fun frameRecord(type: String, frame: OriaVideoFrame): JSONObject = JSONObject()
        .put("type", type).put("atMs", now()).put("sessionId", frame.sessionId)
        .put("generation", frame.generation)
        .put("videoSessionId", frame.sessionId)
        .put("frameId", frame.frameId).put("receivedAtMs", frame.receivedAtMs)
        .put("observedAtMs", frame.receivedAtMs).put("ptsUs", frame.ptsUs)
        .put("width", frame.bitmap.width).put("height", frame.bitmap.height)
        .put("rotationAppliedDegrees", frame.rotationAppliedDegrees).put("mirrored", frame.mirrorApplied)
        .put("source", if (_state.value.simulator) "htc_simulator" else "htc_live")

    /** Called on the inference worker, so the full 300-row diagnostic transform never blocks Main. */
    private fun rawOutputJson(raw: FloatArray, width: Int, height: Int): JSONObject {
        val transform = LetterboxTransform(width, height)
        val normalized = JSONArray()
        for (row in 0 until raw.size / 6) {
            val offset = row * 6
            val box = transform.toCameraBox(raw[offset], raw[offset + 1], raw[offset + 2], raw[offset + 3])
            normalized.put(detectionJson(Detection(raw[offset + 5].toInt(), raw[offset + 4], box))
                .put("row", row).put("validBox", box.width > 0f && box.height > 0f))
        }
        return JSONObject().put("rawModelOutput", JSONArray().apply { raw.forEach { put(it) } })
            .put("rawModelOutputIncluded", true).put("modelDetections", normalized)
    }

    private fun recordInference(frame: OriaVideoFrame, result: FrameAnalysis, ageMs: Long) {
        if (oriaLabState.value.phase != OriaLabPhase.RECORDING) return
        val event = frameRecord("inference", frame)
            .put("preprocessMs", result.preprocessMs).put("inferenceMs", result.inferenceMs)
            .put("depthEnabled", _state.value.depthEnabled).put("depthMapAvailable", result.depthMap != null)
            .put("depthPreprocessMs", result.depthPreprocessMs).put("depthInferenceMs", result.depthInferenceMs)
            .put("depthFailure", result.depthFailure ?: JSONObject.NULL)
            .put("resultAgeMs", ageMs).put("accepted", ageMs <= MAX_AGE_MS)
            .put("detectionConfidenceFloor", 0.70).put("rawModelOutputIncluded", result.diagnosticOutput != null)
            .put("detections", JSONArray().apply { result.detections.forEach { put(detectionJson(it)) } })
        result.diagnosticOutput?.let { extra -> extra.keys().forEach { key -> event.put(key, extra.get(key)) } }
        manager.oriaLabRecorder.recordEvent(event)
    }

    private fun recordDecision(
        frame: OriaVideoFrame,
        evaluation: RgbEvaluation,
        evaluatedAtMs: Long,
        depthEvidence: Pair<Map<Long, OriaDistanceEvidence>, OriaDistanceEvidence>? = null,
        resolution: DangerResolutionSnapshot? = null,
    ) {
        if (oriaLabState.value.phase != OriaLabPhase.RECORDING) return
        val selected = evaluation.selected
        val eligible = evaluation.eligibleAlert
        manager.oriaLabRecorder.recordEvent(frameRecord("decision", frame)
            .put("evaluatedAtMs", evaluatedAtMs)
            .put("frameStatus", evaluation.frameStatus.name)
            .put("reason", evaluation.suppressionReason.name).put("audioState", evaluation.audioState.name)
            .put("orientationVerified", _state.value.orientationVerified)
            .put("audioAutomaticPaused", _state.value.audioAutomaticPaused)
            .put("voiceBackend", _state.value.voiceBackend.name).put("voiceBackendReady", voiceBackendReady())
            .put("rejectedDetectionCount", evaluation.rejectedDetectionCount)
            .put("selected", selected?.let { candidate ->
                JSONObject().put("generation", candidate.generation).put("frameId", candidate.frameId)
                    .put("trackId", candidate.trackId).put("category", candidate.category.name)
                    .put("zone", candidate.zone.name).put("priority", candidate.priority)
                    .put("observedAtMs", candidate.observedAtMs).put("detection", detectionJson(candidate.detection))
            } ?: JSONObject.NULL)
            .put("eligibleAlert", eligible?.let { alert ->
                JSONObject().put("id", alert.id).put("trackId", alert.trackId).put("frameId", alert.frameId)
                    .put("text", alert.text).put("zone", alert.zone.name).put("observedAtMs", alert.observedAtMs)
            } ?: JSONObject.NULL)
            .put("depthEvidence", depthEvidence?.let { (tracks, frontal) ->
                JSONObject().put("unit", "relative_inverse_depth_not_metres")
                    .put("frontal", distanceEvidenceJson(frontal))
                    .put("tracks", JSONArray().apply { tracks.forEach { (trackId, evidence) ->
                        put(distanceEvidenceJson(evidence).put("trackId", trackId))
                    } })
            } ?: JSONObject.NULL)
            .put("dangerResolution", resolution?.let(::dangerResolutionJson) ?: JSONObject.NULL)
            .put("tracks", JSONArray().apply { evaluation.tracks.forEach { track ->
                put(JSONObject().put("generation", track.generation).put("id", track.id).put("zone", track.zone.name)
                    .put("observedAtMs", track.observedAtMs).put("confirmed", track.confirmed)
                    .put("confirmationSamples", track.confirmationSamples)
                    .put("associationStatus", track.associationStatus.name)
                    .put("visibleInLatestFrame", track.visibleInLatestFrame).put("detection", detectionJson(track.detection)))
            } }))
    }

    private fun distanceEvidenceJson(evidence: OriaDistanceEvidence): JSONObject =
        JSONObject().put("available", evidence.available)
            .put("relativeInverseDepth", evidence.relativeInverseDepth ?: JSONObject.NULL)
            .put("relativeProximity", evidence.relativeProximity ?: JSONObject.NULL)
            .put("confidence", evidence.confidence).put("ageMs", evidence.ageMs)
            .put("trend", evidence.trend.name).put("region", evidence.region.name)
            .put("sampleCount", evidence.sampleCount)
            .put("unavailableReason", evidence.unavailableReason?.name ?: JSONObject.NULL)

    private fun dangerResolutionJson(snapshot: DangerResolutionSnapshot): JSONObject =
        JSONObject().put("generation", snapshot.generation).put("frameId", snapshot.frameId)
            .put("observedAtMs", snapshot.observedAtMs).put("evaluatedAtMs", snapshot.evaluatedAtMs)
            .put("policyReason", snapshot.policyReason.name)
            .put("stabilizationReason", snapshot.stabilizationReason.name)
            .put("reason", snapshot.reason.name).put("ownerKey", snapshot.ownerKey ?: JSONObject.NULL)
            .put("approachEnabled", snapshot.approachEnabled)
            .put("selectedBeforeStabilization", snapshot.selectedBeforeStabilization?.let(::dangerCandidateJson)
                ?: JSONObject.NULL)
            .put("selected", snapshot.selected?.let(::dangerCandidateJson) ?: JSONObject.NULL)
            .put("inputs", JSONArray().apply { snapshot.inputs.forEach { put(dangerCandidateJson(it)) } })
            .put("accepted", JSONArray().apply { snapshot.accepted.forEach { put(dangerCandidateJson(it)) } })
            .put("rejected", JSONArray().apply { snapshot.rejected.forEach {
                put(JSONObject().put("reason", it.reason.name).put("candidate", dangerCandidateJson(it.candidate)))
            } })
            .put("approach", JSONArray().apply { snapshot.approach.forEach {
                put(JSONObject().put("ownerKey", it.ownerKey).put("trend", it.trend.name)
                    .put("confidence", it.confidence).put("ageMs", it.ageMs)
                    .put("relativeDelta", it.relativeDelta ?: JSONObject.NULL)
                    .put("ttcMs", it.ttcMs ?: JSONObject.NULL))
            } })

    private fun dangerCandidateJson(candidate: DangerCandidate): JSONObject =
        JSONObject().put("id", candidate.id).put("source", candidate.source.wireName)
            .put("provenance", candidate.provenance.name).put("ownerKey", candidate.ownerKey)
            .put("level", candidate.level.name).put("zone", candidate.zone.name)
            .put("category", candidate.category?.name ?: JSONObject.NULL)
            .put("score", candidate.score).put("confidence", candidate.confidence)
            .put("observedAtMs", candidate.observedAtMs)
            .put("metricDistanceMeters", candidate.metricDistanceMeters ?: JSONObject.NULL)
            .put("relativeProximity", candidate.relativeProximity ?: JSONObject.NULL)

    override fun close() {
        if (closed) return
        buttonTimeout?.cancel(); transcriptionTimeout?.cancel()
        buttonSequencer.reset()
        transcriptionGate.cancel()
        manager.cancelOriaTranscription("controller_closed")
        navigationCoordinator.close()
        closed = true; stop(); frames.cancel(); traces.close()
        // Closing is serialized behind any ongoing inference, never on the UI thread.
        CoroutineScope(worker).launch { detector?.close(); depthEstimator?.close(); worker.close() }
        localSpeech.close()
        scope.cancel()
    }

    companion object {
        const val MAX_AGE_MS = 500L
        const val TEST_PHRASE = "Oria. Test de la voix dans les lunettes."
        private fun now() = SystemClock.elapsedRealtime()
        private fun p95Index(size: Int) = (kotlin.math.ceil(size * .95).toInt() - 1).coerceAtLeast(0)
    }
}
