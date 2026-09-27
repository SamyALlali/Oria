package com.htc.vive.eagle.hackathon.starter.oria

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.htc.vive.eagle.hackathon.starter.ViveGlassKitManager
import com.htc.vive.eagle.hackathon.starter.oria.core.*
import com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxDepthDetector
import com.htc.vive.eagle.hackathon.starter.oria.ml.DepthInference
import com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxObjectDetector
import com.htc.vive.eagle.hackathon.starter.oria.ml.LetterboxTransform
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import com.htc.vive.eagle.hackathon.starter.oria.audio.BluetoothSpeechBackend
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechDelivery
import com.htc.vive.eagle.hackathon.starter.oria.navigation.*
import com.htc.vive.eagle.hackathon.starter.oria.interaction.*
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioScheduler
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioKind
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaAudioPlan
import com.htc.vive.eagle.hackathon.starter.oria.audio.OriaInstruction
import com.htc.vive.eagle.hackathon.starter.oria.audio.SpeechPan
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPrivacyJson
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabPhase
import com.htc.vive.eagle.hackathon.starter.oria.lifecycle.PocketSessionPolicy
import com.htc.vive.eagle.hackathon.starter.oria.lifecycle.PocketSessionService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

enum class OriaVoiceBackend { BLUETOOTH, HTC }

data class OriaUiState(
    val connected: Boolean = false,
    val simulator: Boolean = false,
    val running: Boolean = false,
    val modelReady: Boolean = false,
    val modelLoading: Boolean = true,
    val xnnpack: Boolean = true,
    val obstacleModelReady: Boolean = false,
    val obstacleModelLoading: Boolean = false,
    val obstacleStatus: String = "Chargement des obstacles possibles…",
    val obstacleLatencyMs: Double = 0.0,
    val trackingMode: RgbTrackingMode = RgbTrackingMode.LEGACY_IOU,
    val status: String = "Chargement du modèle…",
    val audio: String = "Voix automatique dans les lunettes",
    val audioBusy: Boolean = false,
    val voiceBackend: OriaVoiceBackend = OriaVoiceBackend.BLUETOOTH,
    val localVoiceReady: Boolean = false,
    val localVoiceStatus: String = "Préparation de la voix française locale…",
    val audioAutomaticPaused: Boolean = false,
    val audioUnknown: Boolean = false,
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
    val fps: Double = 0.0,
    val p95Ms: Long = 0,
    val allInferenceP95Ms: Long = 0,
    val rotation: Int = 0,
    val mirrored: Boolean = false,
    val pocketPreparing: Boolean = false,
    val pocketActive: Boolean = false,
    val pocketStatus: String = "Écran éteint pris en charge après démarrage",

)

/** All policy/audio mutations live on Main. Pixels/inference run on one owned worker. */
class OriaController(context: Context, private val manager: ViveGlassKitManager) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val traceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val depthWorker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine = RgbAlertEngine()
    private val depthPolicy = DepthObstaclePolicy()
    private val dangerDiagnostics = DangerResolutionEngine()
    private var depthObservationIndex = 0L
    private val voiceArbiter = FusionVoiceArbiter()
    private var latestObjectAlert: VoiceAlert? = null
    private var latestDepthAlert: DepthVoiceAlert? = null
    private var nextAutomaticVoiceAt = 0L
    private var lastDepthObservationAt = 0L
    @Volatile private var depthOfferCount = 0L
    private var depthDetector: OnnxDepthDetector? = null
    private var depthModelJob: Job? = null
    // Preserve unresolved-delivery state across compatible updates, including the Oria rename.
    private val audioPersistence = appContext.getSharedPreferences("oria_audio_delivery", Context.MODE_PRIVATE)
    private val previousUncertainDelivery = audioPersistence.getBoolean("delivery_pending_or_unknown", false)
    private val _state = MutableStateFlow(OriaUiState(audioUnknown = previousUncertainDelivery,
        audio = if (previousUncertainDelivery) "Livraison vocale précédente incertaine" else "Voix automatique dans les lunettes"))
    val state: StateFlow<OriaUiState> = _state.asStateFlow()
    val oriaLabState = manager.oriaLabRecorder.state
    private var detector: OnnxObjectDetector? = null
    @Volatile private var modelManifest: JSONObject? = null
    @Volatile private var appProvenance: JSONObject? = null
    @Volatile private var generation = 0L
    private var startedAt = 0L
    private var streamStartedAt = 0L
    private var lastFrameAt = 0L
    private var lastAcceptedObservationAt = 0L
    private var startJob: Job? = null
    private var modelJob: Job? = null
    @Volatile private var closed = false
    private val latencies = ArrayDeque<Long>()
    private val allInferenceLatencies = ArrayDeque<Long>()
    private val frames = Channel<OriaVideoFrame>(1, BufferOverflow.DROP_OLDEST) { it.bitmap.recycle() }
    private val depthFrames = Channel<OriaVideoFrame>(1, BufferOverflow.DROP_OLDEST) { it.bitmap.recycle() }
    private val traces = Channel<String>(256, BufferOverflow.DROP_OLDEST)
    private data class FrameAnalysis(val detections: List<Detection>, val preview: Bitmap,
        val preprocessMs: Double, val inferenceMs: Double, val diagnosticOutput: JSONObject?)
    private data class PendingSpeech(val ticket: VoiceTicket?, val epoch: Long, val submittedAt: Long,
        val text: String, val id: String, val backend: OriaVoiceBackend, val sessionGeneration: Long,
        val pan: SpeechPan, val depthTicket: DepthVoiceTicket? = null,
        val instruction: OriaInstruction? = null, val navigationSpeech: NavigationSpeech? = null,
        val cancelled: AtomicBoolean = AtomicBoolean(false))
    @Volatile private var pendingSpeech: PendingSpeech? = null
    @Volatile private var playableDepthTicketId: Long? = null
    private val localSpeech = BluetoothSpeechBackend(appContext, ::onLocalSpeechResult)

    private val audioScheduler = OriaAudioScheduler()
    private var queuedNavigationSpeech: NavigationSpeech? = null
    val navigation = OriaNavigationCoordinator(appContext, scope,
        onSpeech = ::offerNavigationSpeech,
        onInstructionsInvalidated = { invalidateNavigationAudio() },
        onTrace = { type, fields -> trace(type, *fields.toList().toTypedArray()) })
    @Volatile private var foreground = true
    private val _voiceCommandRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val voiceCommandRequests = _voiceCommandRequests.asSharedFlow()
    private val eagleButton = EagleButtonSequencer()

    init {
        scope.launch { manager.keyEvent.collect { event ->
            if (event == com.htc.viveglass.sdk.KeyEvent.AIBUTTON) onEagleButton()
        } }
        scope.launch { localSpeech.state.collect { voice ->
            _state.update { it.copy(localVoiceReady = voice.ready, localVoiceStatus = voice.detail) }
        } }
        prepareModeSpeech()
        traceScope.launch {
            appContext.filesDir.resolve("oria-trace-${System.currentTimeMillis()}.jsonl").bufferedWriter().use { writer ->
                for (line in traces) { writer.appendLine(line); writer.flush() }
            }
            traceScope.cancel()
        }
        scope.launch { manager.connection.collect { connected ->
            _state.update { it.copy(connected = connected) }
            if (!connected && _state.value.running) stop("Lunettes déconnectées")
        } }
        scope.launch { manager.isSimulator.collect { simulated ->
            _state.update { it.copy(simulator = simulated) }
        } }
        scope.launch { manager.oriaVideoStatus.collect { video ->
            if (video.sessionId == generation && _state.value.running && video.phase in setOf("error", "stopped")) {
                stop("Vidéo indisponible : ${video.detail ?: "erreur de décodage"}")
            }
        } }
        scope.launch { manager.synthesisTransportChanges.drop(1).collect {
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
                    val accepted = applySpeechResult(pending, "confirmed")
                    if (!accepted) {
                        uncertainAudio("Confirmation vocale refusée par le moteur")
                        return@collect
                    }
                    pendingSpeech = null
                    audioScheduler.finished(pending.id)
                    pending.navigationSpeech?.let { navigation.onSpeechResult(it, true) }
                    nextAutomaticVoiceAt = now() + engine.config.globalAnnouncementGapMs
                    persistUncertainDelivery(false)
                    _state.update { it.copy(audioBusy = false, audioAutomaticPaused = false, audio = "Retour SDK reçu · audibilité à vérifier") }
                    trace("speech_sdk_success", "text" to pending.text, "requestId" to pending.id,
                        "videoSessionId" to pending.sessionGeneration, "delayMs" to (now() - pending.submittedAt))
                }
                "ERROR", "ERROR_RESOURCE_CONFLICT", "ERROR_UNSUPPORTED_LOCALE" -> {
                    val accepted = applySpeechResult(pending, "failed")
                    if (!accepted) {
                        uncertainAudio("Retour d’échec vocal non corrélé")
                        return@collect
                    }
                    pendingSpeech = null
                    audioScheduler.finished(pending.id)
                    pending.navigationSpeech?.let { navigation.onSpeechResult(it, false) }
                    persistUncertainDelivery(false)
                    _state.update { it.copy(audioBusy = false, audioAutomaticPaused = true, audio = "Voix refusée : ${event.event}") }
                    trace("speech_failed", "event" to event.event, "requestId" to pending.id,
                        "videoSessionId" to pending.sessionGeneration)
                }
                else -> uncertainAudio("Événement vocal inconnu")
            }
        } }
        scope.launch {
            for (frame in frames) {
                try {
                    if (!_state.value.running || frame.sessionId != generation) continue
                    val sourceTime = frame.receivedAtMs
                    // Each branch owns a recent source bitmap and its own post-inference budget.
                    if (now() - sourceTime > MAX_AGE_MS || sourceTime > now()) {
                        latestObjectAlert = null
                        staleFrame(); continue
                    }
                    val activeDetector = detector ?: continue
                    val result = withContext(worker) {
                        val detections = activeDetector.detect(frame.bitmap,
                            captureRawOutput = oriaLabState.value.phase == OriaLabPhase.RECORDING)
                        val diagnosticOutput = activeDetector.lastRawOutput?.let {
                            rawOutputJson(it, frame.bitmap.width, frame.bitmap.height)
                        }
                        val scale = minOf(1f, 480f / maxOf(frame.bitmap.width, frame.bitmap.height))
                        val preview = Bitmap.createScaledBitmap(frame.bitmap,
                            (frame.bitmap.width * scale).toInt().coerceAtLeast(1),
                            (frame.bitmap.height * scale).toInt().coerceAtLeast(1), true)
                        val ownedPreview = if (preview === frame.bitmap) preview.copy(Bitmap.Config.ARGB_8888, false) else preview
                        FrameAnalysis(detections, ownedPreview, activeDetector.lastPreprocessMs,
                            activeDetector.lastInferenceMs, diagnosticOutput)
                    }
                    if (!_state.value.running || frame.sessionId != generation) { result.preview.recycle(); continue }
                    val age = now() - sourceTime
                    allInferenceLatencies.addLast(age)
                    if (allInferenceLatencies.size > 1000) allInferenceLatencies.removeFirst()
                    val allOrdered = allInferenceLatencies.sorted()
                    _state.update { it.copy(allInferenceP95Ms = allOrdered[p95Index(allOrdered.size)]) }
                    trace("inference", "frame" to frame.frameId, "ageMs" to age,
                        "preprocessMs" to result.preprocessMs, "inferenceMs" to result.inferenceMs,
                        "detections" to result.detections.size, "accepted" to (age <= MAX_AGE_MS))
                    recordInference(frame, result, age)
                    if (age > MAX_AGE_MS) { latestObjectAlert = null; result.preview.recycle(); staleFrame(); continue }
                    val evaluatedAtMs = now()
                    val evaluation = engine.evaluate(DetectionFrame(generation, frame.frameId, sourceTime, result.detections), evaluatedAtMs)
                    recordDecision(frame, evaluation, evaluatedAtMs)
                    latestObjectAlert = evaluation.eligibleAlert
                    if (evaluation.frameStatus != RgbFrameStatus.ACCEPTED) {
                        result.preview.recycle()
                        trace("policy_frame_rejected", "frameId" to frame.frameId,
                            "reason" to evaluation.frameStatus.name)
                        staleFrame()
                        continue
                    }
                    val resolution = dangerDiagnostics.resolve(OriaDangerAdapter.input(
                        evaluation, emptyMap(), null, evaluatedAtMs, approachEnabled = false))
                    trace("danger_diagnostic", "diagnosticOnly" to true,
                        "inputStatus" to resolution.inputStatus.name, "reason" to resolution.reason.name,
                        "candidates" to resolution.inputs.size, "accepted" to resolution.accepted.size,
                        "freshSelected" to (resolution.freshSelected?.id ?: "none"))
                    lastAcceptedObservationAt = sourceTime
                    trace("decision", "frame" to frame.frameId, "observedAtMs" to sourceTime,
                        "selectedTrack" to (evaluation.selected?.trackId ?: -1L),
                        "category" to (evaluation.selected?.category?.name ?: "none"),
                        "zone" to (evaluation.selected?.zone?.name ?: "none"),
                        "priority" to (evaluation.selected?.priority ?: 0f),
                        "reason" to evaluation.suppressionReason.name, "tracks" to evaluation.tracks.size)
                    latencies.addLast(age)
                    if (latencies.size > 1000) latencies.removeFirst()
                    val ordered = latencies.sorted()
                    _state.update { old ->
                        val count = old.analyzed + 1
                        old.copy(status = "Perception active · caméra seule", analyzed = count,
                            preview = result.preview, detections = result.detections, lastLatencyMs = age,
                            inferenceMs = result.inferenceMs, preprocessMs = result.preprocessMs,
                            fps = count * 1000.0 / (now() - startedAt).coerceAtLeast(1),
                            p95Ms = ordered[p95Index(ordered.size)],
                            suppression = evaluation.suppressionReason.toString())
                    }
                    dispatchAutomaticVoice()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    trace("pipeline_error", "error" to error.toString())
                    stop("Analyse interrompue : ${error.message}")
                } finally { frame.bitmap.recycle() }
            }
        }
        scope.launch {
            for (frame in depthFrames) {
                try {
                    if (!_state.value.running || frame.sessionId != generation) continue
                    val at = now()
                    if (at - frame.receivedAtMs !in 0..MAX_AGE_MS) {
                        depthPolicy.evaluate(generation, depthObservationIndex++, frame.receivedAtMs, null, at)
                        latestDepthAlert = null; playableDepthTicketId = null
                        trace("depth_input_rejected", "frameId" to frame.frameId, "ageMs" to (at - frame.receivedAtMs),
                            "videoSessionId" to frame.sessionId, "observedAtMs" to frame.receivedAtMs)
                        continue
                    }
                    analyzeDepthFrame(frame)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { trace("depth_pipeline_error", "error" to e.toString()); stop("Analyse des obstacles interrompue : ${e.message}") }
                finally { frame.bitmap.recycle() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value.running && lastAcceptedObservationAt > 0L && now() - lastAcceptedObservationAt > activeObservationMaxAgeMs()) {
                    _state.update { it.copy(detections = emptyList(), preview = null, suppression = "observation_expired") }
                }
                if (_state.value.running && lastDepthObservationAt > 0 && now() - lastDepthObservationAt > DEPTH_MAX_AGE_MS) {
                    latestDepthAlert = null
                    _state.update { it.copy(obstacleStatus = "En attente d’images fraîches · aucune nouvelle alerte obstacle") }
                }
                dispatchAutomaticVoice()
                if (_state.value.running && streamStartedAt != 0L && now() - maxOf(streamStartedAt, lastFrameAt) > 5_000) stop("Flux vidéo figé ou absent")
                pendingSpeech?.let {
                    if (now() - it.submittedAt > (if (it.backend == OriaVoiceBackend.BLUETOOTH) 20_000 else 12_000)) {
                        if (it.backend == OriaVoiceBackend.BLUETOOTH) cancelLocalSpeech("Lecture locale expirée")
                        else uncertainAudio("Retour vocal absent · reprise non vérifiée")
                    }
                }
            }
        }
        // This exact provider passed all six parity fixtures on the supplied HTC U24 pro.
        // CPU remains an explicit diagnostic option while its strict parity failure is investigated.
        loadModel(true)
        loadDepthModel()
    }

    private data class DepthFrameAnalysis(val inference: DepthInference,
        val zones: List<DepthZoneEvidence>?, val geometryMs: Double,
        val diagnostic: JSONObject?)

    /** Own the full source bitmap until the worker returns; never append depth results to YOLO events. */
    private suspend fun analyzeDepthFrame(frame: OriaVideoFrame) {
        val activeDetector = depthDetector ?: return
        val result = withContext(depthWorker) {
            val inference = activeDetector.detect(frame.bitmap)
            val geometryStarted = SystemClock.elapsedRealtimeNanos()
            val geometry = DepthObstacleGeometry.compute(if (inference.available) inference.values else null)
            val geometryMs = (SystemClock.elapsedRealtimeNanos() - geometryStarted) / 1_000_000.0
            val diagnostic = if (oriaLabState.value.phase == OriaLabPhase.RECORDING) JSONObject()
                .put("analysisMode", "depth_only_experimental")
                .put("modelSha256", OnnxDepthDetector.MODEL_SHA256)
                .put("modelProvider", activeDetector.requestedProvider)
                .put("preprocessMs", inference.preprocessMs).put("inferenceMs", inference.inferenceMs)
                .put("postprocessMs", inference.postprocessMs).put("geometryMs", geometryMs)
                .put("qualityUsable", inference.qualityUsable).put("qualityReason", inference.qualityReason ?: JSONObject.NULL)
                .put("relativeDepth", JSONObject().put("available", inference.available)
                    .put("reason", inference.unavailableReason ?: JSONObject.NULL)
                    .put("width", inference.width).put("height", inference.height)
                    .put("metric", false).put("temporallyComparable", false)
                    .put("normalization", "per_frame_percentile").put("convention", "higher_is_nearer")
                    .put("p02", inference.p02).put("p98", inference.p98)
                    .put("values", if (inference.available) JSONArray().apply {
                        for (y in 0 until inference.height) put(JSONArray().apply {
                            for (x in 0 until inference.width) put(inference.values[y * inference.width + x].toDouble())
                        })
                    } else JSONObject.NULL))
                .put("zones", depthZonesJson(geometry.zones))
                .put("geometry", JSONObject().put("version", geometry.version)
                    .put("reason", geometry.reason ?: JSONObject.NULL)
                    .put("candidatePixels", geometry.candidatePixels ?: JSONObject.NULL)
                    .put("rawCandidatePixels", geometry.rawCandidatePixels ?: JSONObject.NULL)
                    .put("referenceRemovedPixels", geometry.referenceRemovedPixels ?: JSONObject.NULL)
                    .put("referenceStatus", geometry.reference.status).put("referenceReason", geometry.reference.reason)) else null
            DepthFrameAnalysis(inference, geometry.zones, geometryMs, diagnostic)
        }
        if (closed || !_state.value.running || frame.sessionId != generation) return
        val evaluatedAt = now()
        val age = evaluatedAt - frame.receivedAtMs
        val evaluation = depthPolicy.evaluate(generation, depthObservationIndex++, frame.receivedAtMs,
            result.zones, evaluatedAt, result.inference.qualityUsable, result.inference.qualityReason)
        latestDepthAlert = evaluation.eligibleAlert
        playableDepthTicketId = pendingSpeech?.depthTicket?.takeIf { depthPolicy.canPlay(it, evaluatedAt) }?.id
        if (oriaLabState.value.phase == OriaLabPhase.RECORDING) {
            result.diagnostic?.let { diagnostic ->
                val event = frameRecord("depth_inference", frame).put("resultAgeMs", age)
                    .put("observationIndex", depthObservationIndex - 1)
                diagnostic.keys().forEach { event.put(it, diagnostic.get(it)) }
                manager.oriaLabRecorder.recordEvent(event)
            }
            manager.oriaLabRecorder.recordEvent(frameRecord("depth_decision", frame)
                .put("evaluatedAtMs", evaluatedAt).put("observationIndex", depthObservationIndex - 1)
                .put("status", evaluation.status).put("reason", evaluation.reason)
                .put("audioState", evaluation.audioState.name)
                .put("orientationVerified", true)
                .put("observationMaxAgeMs", DEPTH_MAX_AGE_MS)
                .put("eligibleAlert", evaluation.eligibleAlert?.let {
                    JSONObject().put("id", it.id).put("zone", it.zone.name).put("text", it.text)
                        .put("observedAtMs", it.observedAtMs)
                } ?: JSONObject.NULL))
        }
        trace("depth_timing", "frameId" to frame.frameId, "ageMs" to age,
            "inferenceMs" to result.inference.inferenceMs, "preprocessMs" to result.inference.preprocessMs,
            "postprocessMs" to result.inference.postprocessMs, "geometryMs" to result.geometryMs,
            "qualityUsable" to result.inference.qualityUsable, "status" to evaluation.status,
            "reason" to evaluation.reason, "zones" to depthZonesJson(result.zones))
        if (age < 0 || age > DEPTH_MAX_AGE_MS) {
            latestDepthAlert = null; staleFrame()
            _state.update { it.copy(obstacleLatencyMs = age.toDouble(),
                obstacleStatus = "Image trop ancienne · aucune alerte obstacle", suppression = evaluation.reason) }
            return
        }
        if (evaluation.status !in setOf("rejected", "invalid", "missing", "uncertain")) lastDepthObservationAt = frame.receivedAtMs
        val explanation = when (evaluation.status) {
            "uncertain" -> "Image trop sombre ou sans structure · analyse incertaine"
            "missing", "invalid", "rejected" -> "Relief inexploitable · aucune alerte obstacle"
            "no_candidate" -> "Aucun obstacle confirmé · passage libre non garanti"
            "confirming" -> "Obstacle possible · confirmation en cours"
            "proposal" -> evaluation.eligibleAlert?.text ?: "Obstacle possible"
            "suppressed" -> when (evaluation.reason) {
                "audio_in_flight" -> "Annonce obstacle en cours"
                "audio_unknown" -> "Voix à vérifier avant reprise"
                "zone_cooldown" -> "Obstacle encore présent · annonce espacée"
                else -> "Obstacle possible · attente avant annonce"
            }
            else -> "Relief expérimental actif"
        }
        _state.update { it.copy(obstacleStatus = explanation, obstacleLatencyMs = age.toDouble()) }
        dispatchAutomaticVoice()
    }

    /** Both policies retain separate evidence; only this owner may reserve the shared speaker. */
    private fun dispatchAutomaticVoice() {
        if (closed) return
        val at = now()
        latestObjectAlert = latestObjectAlert?.takeIf { it.sessionId == generation && at - it.observedAtMs in 0..MAX_AGE_MS }
        latestDepthAlert = latestDepthAlert?.takeIf { it.sessionId == generation && at - it.observedAtMs in 0..DEPTH_MAX_AGE_MS }
        val canSpeak = !_state.value.audioUnknown &&
            voiceBackendReady() && _state.value.connected
        if (!canSpeak) return
        val dangerReady = _state.value.running && !_state.value.audioAutomaticPaused && at >= nextAutomaticVoiceAt &&
            (latestObjectAlert != null || latestDepthAlert != null)
        val backendIdle = pendingSpeech == null && localSpeech.isIdle() && at >= nextAutomaticVoiceAt
        when (val plan = audioScheduler.plan(at, dangerReady, backendIdle, automaticAllowed = !_state.value.audioAutomaticPaused)) {
            is OriaAudioPlan.CancelInstruction -> {
                pendingSpeech?.takeIf { it.id == plan.id && it.backend == OriaVoiceBackend.BLUETOOTH }?.let {
                    it.cancelled.set(true)
                    if (it.navigationSpeech != null) navigation.onDangerPreemptedNavigation()
                    localSpeech.interrupt()
                    trace("audio_instruction_preempted", "requestId" to it.id)
                }
                return
            }
            is OriaAudioPlan.Instruction -> {
                val request = plan.value
                val nav = queuedNavigationSpeech?.takeIf { it.id == request.id }
                if (request.kind == OriaAudioKind.NAVIGATION && (nav == null || !navigation.canSpeak(nav))) {
                    audioScheduler.finished(request.id)
                    return
                }
                sendSpeech(request.text, null, pan = request.pan, instruction = request, navigationSpeech = nav)
                return
            }
            OriaAudioPlan.Wait -> return
            OriaAudioPlan.Danger -> Unit
        }
        val selected = voiceArbiter.choose(latestObjectAlert != null, latestDepthAlert != null, true) ?: return
        when (selected) {
            FusionVoiceSource.YOLO -> {
                val alert = latestObjectAlert ?: return
                latestObjectAlert = null
                val ticket = engine.onSubmitted(alert, at) ?: return
                voiceArbiter.onSubmitted(selected)
                trace("policy_voice", "action" to "submitted", "policyAtMs" to at, "accepted" to true,
                    "alertId" to alert.id, "ticketId" to ticket.id, "trackId" to alert.trackId,
                    "frameId" to alert.frameId, "videoSessionId" to alert.sessionId)
                sendSpeech(alert.text, ticket, alert.observedAtMs, panFor(alert.zone))
            }
            FusionVoiceSource.DEPTH -> {
                val alert = latestDepthAlert ?: return
                latestDepthAlert = null
                val ticket = depthPolicy.onSubmitted(alert, at) ?: return
                voiceArbiter.onSubmitted(selected)
                trace("depth_voice", "action" to "submitted", "ticketId" to ticket.id,
                    "videoSessionId" to ticket.sessionId, "text" to alert.text)
                sendSpeech(alert.text, null, alert.observedAtMs, panFor(alert.zone), ticket)
            }
        }
    }

    private fun panFor(zone: RgbZone) = when (zone) {
        RgbZone.LEFT -> SpeechPan.LEFT
        RgbZone.CENTER -> SpeechPan.CENTER
        RgbZone.RIGHT -> SpeechPan.RIGHT
    }

    private fun depthZonesJson(zones: List<DepthZoneEvidence>?) = zones?.let { values ->
        JSONArray().apply { values.forEach { put(JSONObject().put("zone", it.zone.name)
            .put("candidateFraction", it.candidateFraction.toDouble()).put("relativeDepthMedian", it.relativeDepthMedian.toDouble())) } }
    } ?: JSONObject.NULL

    fun setTrackingMode(mode: RgbTrackingMode) {
        if (closed || _state.value.running || _state.value.pocketPreparing ||
            _state.value.audioBusy || _state.value.audioUnknown || oriaLabState.value.storageBusy) return
        engine = RgbAlertEngine(engine.config.copy(trackingMode = mode))
        _state.update { it.copy(trackingMode = mode) }
        trace("tracking_mode_changed", "trackingMode" to mode.name)
    }

    private fun prepareModeSpeech() {
        val objects = RgbCategory.entries.filter { it.alertable }.flatMap { category ->
            RgbZone.entries.map { zone -> "${category.label} ${zone.voiceSuffix}" }
        }
        localSpeech.prepare((objects + RgbZone.entries.map { "Obstacle possible ${it.voiceSuffix}" }).toSet())
    }

    fun resumeAutomaticVoice() {
        if (closed || pendingSpeech != null || _state.value.audioUnknown || !_state.value.connected) return
        localSpeech.stop()
        localSpeech.setSessionActive(false)
        prepareModeSpeech()
        localSpeech.setSessionActive(_state.value.running && !_state.value.simulator)
        _state.update { it.copy(audioAutomaticPaused = false, audio = "Reprise de la voix automatique") }
    }

    private fun loadDepthModel() {
        if (closed || depthModelJob?.isActive == true) return
        _state.update { it.copy(obstacleModelLoading = true, obstacleModelReady = false,
            obstacleStatus = "Chargement du relief expérimental…") }
        depthModelJob = scope.launch {
            try {
                val loaded = withContext(depthWorker) {
                    OnnxDepthDetector(appContext, useXnnpack = false, numThreads = DEPTH_THREADS).also {
                        if (closed) it.close() else depthDetector = it
                    }
                }
                if (closed) return@launch
                depthDetector = loaded
                _state.update { it.copy(obstacleModelLoading = false, obstacleModelReady = true,
                    obstacleStatus = "Relief prêt · obstacles possibles, sans distance mesurée") }
                trace("depth_model_loaded")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(obstacleModelLoading = false, obstacleModelReady = false,
                    obstacleStatus = "Relief indisponible : ${e.message}") }
                trace("depth_model_error", "error" to e.toString())
            }
        }
    }

    private fun activeModelReady() = _state.value.modelReady && _state.value.obstacleModelReady
    private fun activeObservationMaxAgeMs() = MAX_AGE_MS

    fun loadModel(xnnpack: Boolean) {
        if (closed || _state.value.running || modelJob?.isActive == true) return
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
                trace("model_loaded", "provider" to loaded.requestedProvider)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                detector = null
                _state.update { it.copy(modelReady = false, modelLoading = false, status = "Modèle indisponible : ${error.message}") }
                trace("model_error", "error" to error.toString())
            }
        }
    }

    fun connect(simulator: Boolean) {
        if (_state.value.running) stop()
        if (manager.isConnected()) manager.disconnect()
        manager.setSimulator(simulator)
        _state.update { it.copy(status = "Connexion en cours…") }
        manager.connect()
    }

    fun disconnect() { stop("Déconnexion"); manager.disconnect() }

    fun pocketServicePreparing() { _state.update { it.copy(pocketPreparing = true, pocketActive = false,
        pocketStatus = "Préparation de l’assistance · garder Oria visible") } }
    fun pocketServiceReady() { _state.update { it.copy(pocketPreparing = false, pocketActive = true,
        pocketStatus = "Assistance active écran éteint · arrêt dans la notification") } }
    fun reportPocketError(reason: String) { _state.update { it.copy(pocketPreparing = false, pocketActive = false, pocketStatus = reason, status = reason) } }
    fun canContinueInBackground(): Boolean {
        val live = _state.value
        return PocketSessionPolicy.canContinue(PocketSessionService.isReadyFor(this), live.running,
            live.connected, live.simulator,
            oriaLabState.value.phase in setOf(OriaLabPhase.RECORDING, OriaLabPhase.FINALIZING),
            PocketSessionService.elapsedMs())
    }


    /** Explicit user action only. Restart the stream so its H.264 headers belong to the capture. */
    fun startOriaLab() {
        if (closed || !manager.isConnected() || !activeModelReady() ||
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
        if (closed || _state.value.running || !manager.isConnected() || !activeModelReady() || oriaLabState.value.storageBusy) return
        val id = ++generation
        audioScheduler.reset(id)
        queuedNavigationSpeech = null
        // Navigation owns its route generation independently. Refresh its audio evidence only.
        navigation.onDangerPreemptedNavigation()
        startedAt = now(); streamStartedAt = 0L; lastFrameAt = 0L; lastAcceptedObservationAt = 0L
        latencies.clear(); allInferenceLatencies.clear()
        playableDepthTicketId = null
        engine.start(id, startedAt)
        dangerDiagnostics.reset(id)
        depthPolicy.start(id, startedAt); depthObservationIndex = 0L
        latestObjectAlert = null; latestDepthAlert = null; nextAutomaticVoiceAt = 0L
        voiceArbiter.reset(); lastDepthObservationAt = 0L; depthOfferCount = 0L
        _state.update { it.copy(running = true, status = "Démarrage de la vidéo…",
            obstacleStatus = "Démarrage de la vidéo…", audioAutomaticPaused = false, received = 0,
            analyzed = 0, stale = 0, fps = 0.0, lastLatencyMs = 0, p95Ms = 0, allInferenceP95Ms = 0,
            preprocessMs = 0.0, inferenceMs = 0.0, detections = emptyList(), preview = null) }
        localSpeech.setSessionActive(_state.value.voiceBackend == OriaVoiceBackend.BLUETOOTH && !_state.value.simulator)
        trace("start", "policyAtMs" to startedAt, "rotation" to _state.value.rotation, "mirrored" to _state.value.mirrored,
            "sampleIntervalMs" to ViveGlassKitManager.ORIA_SAMPLE_INTERVAL_MS, "samplingMode" to "fixed_interval_after_decode",
            "trackingMode" to engine.config.trackingMode.name, "pocketMode" to _state.value.pocketActive,
            "perceptionMode" to "objects_and_depth", "depthThreads" to DEPTH_THREADS, "depthSampleIntervalMs" to DEPTH_SAMPLE_INTERVAL_MS,
            "observationMaxAgeMs" to activeObservationMaxAgeMs())
        startJob = scope.launch {
            try {
                val started = manager.startOriaVideo(id, _state.value.rotation, _state.value.mirrored) { frame ->
                    if (!_state.value.running || frame.sessionId != generation || closed) false
                    else {
                        // Copy before handing the original to YOLO: either worker may recycle immediately.
                        if (depthOfferCount++ % DEPTH_FRAME_STRIDE == 0L) {
                            val depthCopy = frame.copy(bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                            if (depthFrames.trySend(depthCopy).isFailure) depthCopy.bitmap.recycle()
                        }
                        frames.trySend(frame).isSuccess.also { accepted ->
                            if (accepted) scope.launch {
                                if (frame.sessionId == generation) {
                                    lastFrameAt = now()
                                    _state.update { it.copy(received = it.received + 1) }
                                }
                            }
                        }
                    }
                }
                if (started && id == generation) streamStartedAt = now()
                if (!started && id == generation) stop("Démarrage vidéo refusé · vérifier les autorisations HTC")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (id == generation) stop("Vidéo : ${error.message}") }
        }
    }

    fun stop(reason: String = "Session arrêtée", stopNavigation: Boolean = true) {
        PocketSessionService.release(this, appContext)
        _state.update { it.copy(pocketPreparing = false, pocketActive = false,
            pocketStatus = "Assistance arrêtée") }
        val stoppedSession = generation
        ++generation
        if (stopNavigation) navigation.stop("Session Oria arrêtée")
        queuedNavigationSpeech = null
        audioScheduler.reset(generation)
        eagleButton.reset()
        startJob?.cancel(); startJob = null
        if (pendingSpeech?.backend == OriaVoiceBackend.BLUETOOTH) cancelLocalSpeech("Lecture locale arrêtée")
        else if (pendingSpeech != null) uncertainAudio("Arrêt demandé · parole déjà soumise possiblement en cours")
        localSpeech.setSessionActive(false)
        engine.stop()
        dangerDiagnostics.stop()
        depthPolicy.stop(); playableDepthTicketId = null
        latestObjectAlert = null; latestDepthAlert = null; voiceArbiter.reset()
        // The recorder filters by the old pipeline ID and must see this before the manager closes it.
        trace("stop", "reason" to reason, "sessionId" to stoppedSession)
        manager.stopOriaVideo()
        while (true) { (frames.tryReceive().getOrNull() ?: break).bitmap.recycle() }
        while (true) { (depthFrames.tryReceive().getOrNull() ?: break).bitmap.recycle() }
        _state.update { it.copy(running = false, status = reason, obstacleStatus = reason, detections = emptyList(), preview = null, suppression = "session_stopped") }
    }

    fun testVoice(pan: SpeechPan = SpeechPan.CENTER) {
        speakManualWithPan(TEST_PHRASE, pan)
    }

    /** Manual HTC Chat and Oria voice tests share the same reservation and callback gate. */
    fun speakManual(text: String) = speakManualWithPan(text, SpeechPan.CENTER)

    private fun speakManualWithPan(text: String, pan: SpeechPan) {
        if (closed || text.isBlank() || text.length > 240 || !manager.isConnected()) return
        val at = now()
        audioScheduler.offer(OriaInstruction(UUID.randomUUID().toString(), generation,
            OriaAudioKind.COMMAND_RESPONSE, text, at, at + 5_000, pan), at)
        dispatchAutomaticVoice()
    }

    private fun offerNavigationSpeech(speech: NavigationSpeech) {
        if (closed || !navigation.canSpeak(speech)) return
        queuedNavigationSpeech = speech
        val accepted = audioScheduler.offer(OriaInstruction(speech.id, generation,
            OriaAudioKind.NAVIGATION, speech.text.take(240), speech.observedAtMs, speech.expiresAtMs), now())
        if (accepted) dispatchAutomaticVoice()
    }

    private fun invalidateNavigationAudio() {
        queuedNavigationSpeech = null
        val id = audioScheduler.invalidate(OriaAudioKind.NAVIGATION)
        pendingSpeech?.takeIf { it.id == id && it.backend == OriaVoiceBackend.BLUETOOTH }?.let {
            it.cancelled.set(true)
            localSpeech.interrupt()
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        navigation.setForeground(visible)
        if (!visible) eagleButton.reset()
    }

    private fun onEagleButton() {
        if (!_state.value.connected || closed) return
        val (action, token) = eagleButton.press(now())
        when (action) {
            EagleButtonAction.START_VOICE_COMMAND -> {
                if (foreground) _voiceCommandRequests.tryEmit(Unit)
                else speakManual("Ouvrez Oria sur le téléphone pour dicter une commande.")
            }
            EagleButtonAction.WAIT_FOR_SECOND_PRESS -> scope.launch {
                delay(560)
                if (eagleButton.timeout(token, now()) == EagleButtonAction.RUN_SINGLE_PRESS) {
                    speakManual(if (_state.value.running) "Oria est en marche." else "Oria est arrêtée.")
                }
            }
            else -> Unit
        }
    }

    /** Only correlated Android activity results reach this method; never anonymous HTC callbacks. */
    fun acceptVoiceCommand(transcript: String) {
        if (!foreground || closed) return
        when (val intent = OriaVoiceIntentParser.parse(transcript).intent) {
            is OriaVoiceIntent.GuideTo -> navigation.search(intent.destination, NavigationMode.REAL)
            OriaVoiceIntent.PauseNavigation -> navigation.pause()
            OriaVoiceIntent.ResumeNavigation -> navigation.resume()
            OriaVoiceIntent.StopNavigation -> navigation.stop()
            OriaVoiceIntent.ConfirmDestination -> navigation.confirmSelected()
            is OriaVoiceIntent.SelectDestination -> navigation.state.value.suggestions.getOrNull(intent.index)?.let(navigation::select)
            OriaVoiceIntent.StopPerception -> stop()
            OriaVoiceIntent.Cancel -> navigation.stop()
            OriaVoiceIntent.StartPerception -> speakManual("Utilisez Démarrer Oria sur le téléphone.")
            OriaVoiceIntent.RepeatActive -> if (!navigation.repeatFreshInstruction())
                speakManual("Aucune instruction récente à répéter.")
            OriaVoiceIntent.DescribeAhead -> speakManual("Les objets et obstacles sont annoncés automatiquement avec des images fraîches.")
            is OriaVoiceIntent.Ambiguous -> speakManual(intent.response)
            is OriaVoiceIntent.Unknown -> speakManual(intent.response)
            else -> speakManual("Cette commande n’est pas disponible.")
        }
    }

    private fun voiceBackendReady(): Boolean = _state.value.voiceBackend == OriaVoiceBackend.HTC ||
        (_state.value.localVoiceReady && !_state.value.simulator)

    fun setVoiceBackend(backend: OriaVoiceBackend) {
        if (_state.value.running || pendingSpeech != null || _state.value.audioUnknown) return
        _state.update { it.copy(voiceBackend = backend, audioAutomaticPaused = true,
            audio = "Tester cette sortie vocale avant les annonces automatiques") }
        trace("voice_backend", "backend" to backend.name)
    }

    private fun sendSpeech(text: String, ticket: VoiceTicket?, observedAtMs: Long? = null,
                           pan: SpeechPan = SpeechPan.CENTER, depthTicket: DepthVoiceTicket? = null,
                           instruction: OriaInstruction? = null, navigationSpeech: NavigationSpeech? = null) {
        val automatic = ticket != null || depthTicket != null
        val maximumAge = if (depthTicket != null) DEPTH_MAX_AGE_MS else MAX_AGE_MS
        // Persist before dispatch so an activity/process restart cannot invent a clean transport.
        if (!persistUncertainDelivery(true)) {
            instruction?.let { audioScheduler.finished(it.id) }
            if (!releaseLocalSpeech(ticket, depthTicket)) return
            _state.update { it.copy(audio = "Impossible de journaliser la demande vocale") }
            return
        }
        val pending = PendingSpeech(ticket, manager.synthesisTransportEpoch, now(), text,
            instruction?.id ?: UUID.randomUUID().toString(), _state.value.voiceBackend, generation, pan, depthTicket,
            instruction, navigationSpeech)
        if (automatic) audioScheduler.dangerStarted(pending.id)
        pendingSpeech = pending
        playableDepthTicketId = depthTicket?.takeIf { depthPolicy.canPlay(it, now()) }?.id
        _state.update { it.copy(audioBusy = true,
            audio = if (pending.backend == OriaVoiceBackend.HTC) "Phrase remise au SDK HTC" else "Lecture locale vers Bluetooth VIVE",
            lastAlert = text) }
        try {
            // Disk persistence and UI bookkeeping may have taken time after engine reservation.
            // No blocking work is allowed between this last observation check and SDK dispatch.
            if (automatic && (observedAtMs == null || now() - observedAtMs > maximumAge ||
                    observedAtMs > now() || !_state.value.running ||
                    (ticket?.sessionId ?: depthTicket?.sessionId) != generation) || !instructionCurrent(pending)) {
                if (!releaseLocalSpeech(ticket, depthTicket)) return
                pendingSpeech = null
                audioScheduler.finished(pending.id)
                pending.navigationSpeech?.let { navigation.onSpeechResult(it, false) }
                persistUncertainDelivery(false)
                _state.update { it.copy(audioBusy = false, audio = "Annonce expirée avant envoi") }
                trace("speech_expired_before_dispatch")
                return
            }
            val startGuardRecorded = AtomicBoolean(false)
            val accepted = if (pending.backend == OriaVoiceBackend.HTC) manager.speakOriaText(text)
            else localSpeech.submit(pending.id, text, pan = pending.pan,
                canStart = {
                    val allowed = localRequestCurrent(pending) && instructionCurrent(pending) &&
                        (depthTicket == null || playableDepthTicketId == depthTicket.id) && (!automatic ||
                        (observedAtMs != null && observedAtMs <= now() && now() - observedAtMs <= maximumAge))
                    if (startGuardRecorded.compareAndSet(false, true)) trace("speech_pcm_start_guard",
                        "requestId" to pending.id, "allowed" to allowed, "videoSessionId" to pending.sessionGeneration,
                        "observationAgeMs" to (observedAtMs?.let { now() - it } ?: -1L))
                    // Trace bookkeeping must not weaken the final freshness boundary.
                    allowed && localRequestCurrent(pending) && instructionCurrent(pending) &&
                        (depthTicket == null || playableDepthTicketId == depthTicket.id) && (!automatic ||
                        (observedAtMs != null && observedAtMs <= now() && now() - observedAtMs <= maximumAge))
                },
                canContinue = { localRequestCurrent(pending) })
            if (!accepted) {
                if (!releaseLocalSpeech(ticket, depthTicket)) return
                pendingSpeech = null
                audioScheduler.finished(pending.id)
                pending.navigationSpeech?.let { navigation.onSpeechResult(it, false) }
                persistUncertainDelivery(false)
                _state.update { it.copy(audioBusy = false, audioAutomaticPaused = true, audio = "Soumission vocale refusée") }
                trace("speech_rejected")
            } else trace("speech_submitted", "text" to text, "backend" to pending.backend.name,
                "requestId" to pending.id, "observedAtMs" to (observedAtMs ?: -1L),
                "videoSessionId" to pending.sessionGeneration, "ticketId" to (ticket?.id ?: depthTicket?.id ?: -1L),
                "perceptionMode" to if (depthTicket != null) "depth_only_experimental" else "yolo_or_manual",
                "pan" to if (pending.backend == OriaVoiceBackend.BLUETOOTH) pending.pan.name else "HTC_UNCONTROLLED")
        }
        catch (error: Exception) { uncertainAudio("Envoi vocal interrompu : ${error.message}") }
    }

    /** Worker-readable validation; freshness is checked separately only before the first PCM. */
    private fun localRequestCurrent(pending: PendingSpeech): Boolean =
        !closed && !pending.cancelled.get() && pendingSpeech?.id == pending.id && generation == pending.sessionGeneration &&
            !_state.value.audioUnknown && _state.value.connected &&
            ((pending.ticket == null && pending.depthTicket == null) || _state.value.running)

    private fun instructionCurrent(pending: PendingSpeech): Boolean = pending.instruction?.let {
        now() in it.observedAtMs..it.expiresAtMs &&
            (pending.navigationSpeech?.let(navigation::canSpeak) ?: true)
    } ?: true

    /** Android delivery has a real request ID; unrelated and late results are ignored. */
    private fun onLocalSpeechResult(id: String, result: SpeechDelivery, detail: String) {
        if (closed) return
        val pending = pendingSpeech ?: return
        if (pending.backend != OriaVoiceBackend.BLUETOOTH || pending.id != id) return
        val accepted = applySpeechResult(pending, if (result == SpeechDelivery.COMPLETED) "confirmed" else "failed")
        if (!accepted) { uncertainAudio("Retour local refusé par le moteur"); return }
        pendingSpeech = null
        audioScheduler.finished(id)
        pending.navigationSpeech?.let { navigation.onSpeechResult(it, result == SpeechDelivery.COMPLETED && !pending.cancelled.get()) }
        val expectedCancellation = pending.instruction != null && pending.cancelled.get()
        nextAutomaticVoiceAt = if (expectedCancellation) now() else now() + engine.config.globalAnnouncementGapMs
        persistUncertainDelivery(false)
        _state.update { it.copy(audioBusy = false, audioAutomaticPaused = !expectedCancellation && result != SpeechDelivery.COMPLETED && result != SpeechDelivery.EXPIRED,
            audio = when (result) {
                SpeechDelivery.COMPLETED -> "Annonce terminée"
                SpeechDelivery.EXPIRED -> "Annonce expirée avant lecture · attente d’une observation fraîche"
                SpeechDelivery.NOT_PLAYED -> "Voix non jouée : $detail"
                SpeechDelivery.INTERRUPTED -> "Lecture interrompue : $detail"
            }) }
        trace("speech_local_result", "requestId" to id, "result" to result.name, "detail" to detail,
            "videoSessionId" to pending.sessionGeneration,
            "delayMs" to (now() - pending.submittedAt))
    }

    /** A cancelled local request cannot confirm a later ID. Never resets anonymous HTC uncertainty. */
    private fun cancelLocalSpeech(reason: String) {
        val pending = pendingSpeech ?: return
        if (pending.backend != OriaVoiceBackend.BLUETOOTH) return
        audioScheduler.finished(pending.id)
        pending.navigationSpeech?.let { navigation.onSpeechResult(it, false) }
        pendingSpeech = null // The worker's canContinue becomes false before stopping the player.
        localSpeech.stop()
        if (!releaseLocalSpeech(pending.ticket, pending.depthTicket)) return
        persistUncertainDelivery(false)
        _state.update { it.copy(audioBusy = false, audioAutomaticPaused = true, audio = reason) }
        trace("speech_local_cancelled", "requestId" to pending.id, "reason" to reason,
            "sessionId" to pending.sessionGeneration, "videoSessionId" to pending.sessionGeneration)
    }

    /** A known local refusal releases the reservation only if the engine recognizes it. */
    private fun releaseLocalSpeech(ticket: VoiceTicket?, depthTicket: DepthVoiceTicket? = null): Boolean {
        val accepted = when {
            depthTicket != null -> applyDepthVoiceResult(depthTicket, "failed")
            ticket != null -> applyVoiceResult(ticket, "failed")
            else -> true
        }
        if (accepted) return true
        uncertainAudio("Réservation vocale incohérente · reprise à vérifier")
        return false
    }

    private fun uncertainAudio(reason: String) {
        persistUncertainDelivery(true)
        val pending = pendingSpeech
        pending?.let { applySpeechResult(it, "ambiguous") }
        pendingSpeech = null
        audioScheduler.reset(generation)
        _state.update { it.copy(audioUnknown = true, audioBusy = false, audio = reason) }
        trace("audio_uncertain", "reason" to reason, "requestId" to (pending?.id ?: "none"),
            "videoSessionId" to (pending?.sessionGeneration ?: generation))
    }

    private fun applySpeechResult(pending: PendingSpeech, action: String): Boolean = when {
        pending.depthTicket != null -> applyDepthVoiceResult(pending.depthTicket, action)
        pending.ticket != null -> applyVoiceResult(pending.ticket, action)
        else -> true
    }

    private fun applyDepthVoiceResult(ticket: DepthVoiceTicket, action: String): Boolean {
        val at = now()
        val accepted = when (action) {
            "confirmed" -> depthPolicy.onCompleted(ticket, at)
            "failed" -> depthPolicy.onFailed(ticket, at)
            "ambiguous" -> depthPolicy.onAudioUnknown(ticket)
            else -> false
        }
        trace("depth_voice", "action" to action, "accepted" to accepted,
            "ticketId" to ticket.id, "videoSessionId" to ticket.sessionId, "policyAtMs" to at)
        return accepted
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
        val safe = runCatching { OriaLabPrivacyJson.sanitizeEvent(json) }.getOrElse {
            JSONObject().put("type", "privacy_redaction_failed").put("atMs", now())
        }
        traces.trySend(safe.toString())
        Log.i("Oria", safe.toString())
        // These two events have richer, explicitly labelled recordings below; avoid duplicates.
        if (type != "inference" && type != "decision") manager.oriaLabRecorder.recordEvent(safe)
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
            .put("perceptionMode", "objects_and_depth")
            .put("depthModelSha256", OnnxDepthDetector.MODEL_SHA256).put("depthThreads", DEPTH_THREADS)
            .put("depthSampleIntervalMs", DEPTH_SAMPLE_INTERVAL_MS).put("depthSamplingStrategy", "every_second_decoded_sample")
            .put("depthModelManifest", appContext.assets.open(OnnxDepthDetector.MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) })
            .put("depthGeometryVersion", DepthObstacleGeometry.VERSION)
            .put("depthEvidenceIncluded", true).put("depthMetric", false).put("rgbInferenceEnabled", true)
            .put("depthPolicyConfig", JSONObject().put("maxObservationAgeMs", DEPTH_MAX_AGE_MS)
                .put("minimumObservations", 3).put("minimumHoldMs", 500).put("maxGapMs", 1500)
                .put("minCandidateFraction", 0.12).put("repeatAfterPlaybackMs", 8000).put("failureRetryMs", 1000))
            .put("orientationProfile", "vive_eagle_0deg_no_mirror_previously_user_validated")
            .put("fusionVoiceArbitration", "alternate_fresh_ready_sources_v1")
            .put("source", if (_state.value.simulator) "htc_simulator" else "htc_live")
            .put("onnxSha256", manifest.optString("onnx_sha256"))
            .put("modelManifest", manifest).put("modelProvider", detector?.requestedProvider ?: "unknown")
            .put("deviceModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("rotationAppliedDegrees", _state.value.rotation).put("mirrored", _state.value.mirrored)
            .put("orientationVerified", true)
            .put("voiceBackend", _state.value.voiceBackend.name)
            .put("sampleIntervalMs", ViveGlassKitManager.ORIA_SAMPLE_INTERVAL_MS).put("samplingMode", "fixed_interval_after_decode")
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
            .put("resultAgeMs", ageMs).put("accepted", ageMs <= MAX_AGE_MS)
            .put("detectionConfidenceFloor", 0.70).put("rawModelOutputIncluded", result.diagnosticOutput != null)
            .put("detections", JSONArray().apply { result.detections.forEach { put(detectionJson(it)) } })
        result.diagnosticOutput?.let { extra -> extra.keys().forEach { key -> event.put(key, extra.get(key)) } }
        manager.oriaLabRecorder.recordEvent(event)
    }

    private fun recordDecision(frame: OriaVideoFrame, evaluation: RgbEvaluation, evaluatedAtMs: Long) {
        if (oriaLabState.value.phase != OriaLabPhase.RECORDING) return
        val selected = evaluation.selected
        val eligible = evaluation.eligibleAlert
        manager.oriaLabRecorder.recordEvent(frameRecord("decision", frame)
            .put("evaluatedAtMs", evaluatedAtMs)
            .put("frameStatus", evaluation.frameStatus.name)
            .put("reason", evaluation.suppressionReason.name).put("audioState", evaluation.audioState.name)
            .put("orientationVerified", true)
            .put("audioAutomaticPaused", _state.value.audioAutomaticPaused)
            .put("voiceBackend", _state.value.voiceBackend.name).put("voiceBackendReady", voiceBackendReady())
            .put("rejectedDetectionCount", evaluation.rejectedDetectionCount)
            .put("selected", selected?.let { candidate ->
                JSONObject().put("trackId", candidate.trackId).put("category", candidate.category.name)
                    .put("zone", candidate.zone.name).put("priority", candidate.priority)
                    .put("observedAtMs", candidate.observedAtMs).put("detection", detectionJson(candidate.detection))
            } ?: JSONObject.NULL)
            .put("eligibleAlert", eligible?.let { alert ->
                JSONObject().put("id", alert.id).put("trackId", alert.trackId).put("frameId", alert.frameId)
                    .put("text", alert.text).put("zone", alert.zone.name).put("observedAtMs", alert.observedAtMs)
            } ?: JSONObject.NULL)
            .put("tracks", JSONArray().apply { evaluation.tracks.forEach { track ->
                put(JSONObject().put("id", track.id).put("zone", track.zone.name)
                    .put("observedAtMs", track.observedAtMs).put("confirmed", track.confirmed)
                    .put("confirmationSamples", track.confirmationSamples)
                    .put("associationStatus", track.associationStatus.name)
                    .put("visibleInLatestFrame", track.visibleInLatestFrame).put("detection", detectionJson(track.detection)))
            } }))
    }

    override fun close() {
        if (closed) return
        closed = true; stop(); frames.cancel(); depthFrames.cancel(); traces.close()
        // Closing is serialized behind any ongoing inference, never on the UI thread.
        CoroutineScope(worker).launch { detector?.close(); worker.close() }
        CoroutineScope(depthWorker).launch { depthDetector?.close(); depthWorker.close() }
        navigation.close()
        localSpeech.close()
        scope.cancel()
    }

    companion object {
        const val DEPTH_THREADS = 4
        const val DEPTH_FRAME_STRIDE = 2L
        const val DEPTH_SAMPLE_INTERVAL_MS = 666L // Nominal only; every second offered decoded sample, not a timer.
        const val DEPTH_MAX_AGE_MS = DepthObstacleConfig.DEFAULT_MAX_OBSERVATION_AGE_MS // Experimental depth only; never changes YOLO freshness.
        const val MAX_AGE_MS = 500L
        const val TEST_PHRASE = "Oria. Test de la voix dans les lunettes."
        private fun now() = SystemClock.elapsedRealtime()
        private fun p95Index(size: Int) = (kotlin.math.ceil(size * .95).toInt() - 1).coerceAtLeast(0)
    }
}
