package com.htc.vive.eagle.hackathon.starter

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ExifInterface.ORIENTATION_NORMAL
import android.media.ExifInterface.ORIENTATION_ROTATE_180
import android.media.ExifInterface.ORIENTATION_ROTATE_270
import android.media.ExifInterface.ORIENTATION_ROTATE_90
import android.media.MediaCodec
import android.os.SystemClock
import android.media.MediaPlayer
import android.view.Surface
import android.widget.Toast
import com.htc.vive.eagle.hackathon.starter.util.AudioDecoder
import com.htc.vive.eagle.hackathon.starter.util.H264Decoder
import com.htc.vive.eagle.hackathon.starter.util.StreamingPlayer
import com.htc.viveglass.sdk.simulator.ViveGlassSimulator
import com.htc.viveglass.sdk.AudioChannel
import com.htc.viveglass.sdk.AudioStreamingFormat
import com.htc.viveglass.sdk.CaptureEvent
import com.htc.viveglass.sdk.ConnectionState
import com.htc.viveglass.sdk.ImagePayload
import com.htc.viveglass.sdk.ImageQuality
import com.htc.viveglass.sdk.KeyEvent
import com.htc.viveglass.sdk.Microphone
import com.htc.viveglass.sdk.Permission
import com.htc.viveglass.sdk.PermissionResult
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.SynthesisEvent
import com.htc.viveglass.sdk.TranscribedEvent
import com.htc.viveglass.sdk.ViveGlass
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.StreamingEvent.*
import com.htc.viveglass.sdk.VideoStreamingFormat
import com.htc.viveglass.sdk.client.StreamingBufferCallback
import com.htc.viveglass.sdk.client.StreamingEventCallback
import com.htc.viveglass.sdk.client.ViveGlassClientCallback
import com.htc.vive.eagle.hackathon.starter.ui.tab.AppDestination
import com.htc.vive.eagle.hackathon.starter.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import com.htc.vive.eagle.hackathon.starter.oria.recording.OriaLabRecorder
import org.json.JSONObject
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoDecoder
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoFrame
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaVideoStatus
import com.htc.vive.eagle.hackathon.starter.oria.video.OriaSynthesisEvent
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Locale

interface ViveGlassKitInterface{

    val connection: StateFlow<Boolean>
    val isSampleAudioPlaying : StateFlow<Boolean>
    val isAudioRecording : StateFlow<Boolean>
    val isVideoStreaming : StateFlow<Boolean>
    val isImageCapturing : StateFlow<Boolean>
    val previewRatio : StateFlow<Float>
    val isSimulator : StateFlow<Boolean>
    val isStartTranscribe : StateFlow<Boolean>
    val imageReceived : SharedFlow<Bitmap>
    val keyEvent : SharedFlow<KeyEvent>
    val textTranscribed : StateFlow<String>
    fun setSimulator(isUseSimulator: Boolean)
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean
    fun speakText(text:String)
    suspend fun startTranscription()
    fun stopTranscription()
    fun playSampleAudio()
    fun stopSampleAudio()
    suspend fun startAudioStreaming()
    fun stopAudioStreaming()
    suspend fun captureImage()
    suspend fun startVideoStreaming()
    fun stopVideoStreaming()
    fun getPreferredLocale() : Locale
    fun cleanup()
}

const val TAG:String = "ViveGlassKit"
class ViveGlassKitManager(
    private val glass : ViveGlass,
    private val kit: ViveGlassKit,
    private val simulator: ViveGlassSimulator,
    private val activity: Activity,
    appContext : Context,
    audioManager :AudioManager
): ViveGlassKitInterface
{
    companion object {
        const val ORIA_SAMPLE_INTERVAL_MS = 333L
    }

    private val log = Logger.instance
    private val _isSimulator =  MutableStateFlow(false)
    override val isSimulator : StateFlow<Boolean> = _isSimulator.asStateFlow()

    private val _connection = MutableStateFlow(false)
    override val connection: StateFlow<Boolean> = _connection.asStateFlow()

    private val _isSampleAudioPlaying = MutableStateFlow(false)
    override val  isSampleAudioPlaying : StateFlow<Boolean> = _isSampleAudioPlaying.asStateFlow()

    private val _isAudioRecording = MutableStateFlow(false)
    override val  isAudioRecording : StateFlow<Boolean> = _isAudioRecording.asStateFlow()

    private val _isVideoStreaming = MutableStateFlow(false)
    override val isVideoStreaming : StateFlow<Boolean> = _isVideoStreaming.asStateFlow()

    private val _isImageCapturing = MutableStateFlow(false)
    override val isImageCapturing: StateFlow<Boolean> = _isImageCapturing.asStateFlow()

    private val _previewRatio = MutableStateFlow(1.8f)
    override val  previewRatio : StateFlow<Float> = _previewRatio.asStateFlow()

    private val _isStartTranscribe = MutableStateFlow(false)
    override val isStartTranscribe: StateFlow<Boolean> = _isStartTranscribe.asStateFlow()
    private val _textTranscribed = MutableStateFlow("")
    override val textTranscribed: StateFlow<String> = _textTranscribed.asStateFlow()

    private val _imageReceived =MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val imageReceived: SharedFlow<Bitmap> = _imageReceived

    private val _keyEvent = MutableSharedFlow<KeyEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val keyEvent: SharedFlow<KeyEvent> = _keyEvent

    private var mediaPlayer: MediaPlayer =  MediaPlayer.create(appContext, R.raw.default_audio_256kbps)

    private var streamPlayer : StreamingPlayer? = null
    @Volatile private var renderSurface: Surface? = null
    private var videoDecoder: H264Decoder? = null
    private var audioDecoder = AudioDecoder(audioManager)

    @Volatile
    private var cachedPreferredLocale: Locale = Locale.getDefault()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingPermissionRequests = mutableMapOf<Permission, CompletableDeferred<Boolean>>()
    private val permissionMutex = Mutex()

    private val echoRequestVersion = AtomicLong()
    private val legacyRequestVersion = AtomicLong()
    private val legacyAudioVersion = AtomicLong()
    private val legacyVideoVersion = AtomicLong()
    private val legacyTranscriptionVersion = AtomicLong()
    // SDK buffers are consumed synchronously while valid. Serialize their handoff with
    // local stop/start, so a buffer checked before stop cannot enter the next decoder run.
    private val legacyMediaLock = Any()
    private var legacyAudioRequested = false
    private var legacyVideoRequested = false
    @Volatile private var oriaMode = false
    @Volatile private var echoSessionId: Long? = null
    @Volatile private var echoDecoder: OriaVideoDecoder? = null
    private val _oriaVideoStatus = MutableStateFlow(OriaVideoStatus())
    val oriaVideoStatus: StateFlow<OriaVideoStatus> = _oriaVideoStatus.asStateFlow()
    val oriaLabRecorder = OriaLabRecorder(appContext)
    private val synthesisEpochCounter = AtomicLong()
    private val _synthesisTransportChanges = MutableStateFlow(0L)
    val synthesisTransportChanges: StateFlow<Long> = _synthesisTransportChanges.asStateFlow()
    val synthesisTransportEpoch: Long get() = _synthesisTransportChanges.value
    private val _synthesisEvents = MutableSharedFlow<OriaSynthesisEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val synthesisEvents: SharedFlow<OriaSynthesisEvent> = _synthesisEvents
    private var manualSpeechHandler: ((String) -> Unit)? = null

    /** Share one in-flight voice owner across the HTC Chat and Oria tabs. Called on Main. */
    fun setManualSpeechHandler(handler: (String) -> Unit) {
        manualSpeechHandler = handler
    }

    /** Navigation owns the media mode. Pending permission requests cannot outlive it. */
    fun setOriaMode(active: Boolean) {
        if (oriaMode == active) return
        legacyRequestVersion.incrementAndGet()
        oriaMode = active
        if (active) {
            // STARTED may still be queued, so UI flags cannot tell us what the SDK owns.
            stopLegacyTranscription()
            stopSampleAudio()
            stopLegacyAudio()
            stopLegacyVideo()
            _isImageCapturing.value = false
        } else {
            stopOriaVideo()
        }
    }

    /** Called on Main after the controller stops. Preserve connection and selected mode;
     * permissions may finish later, but they cannot restart media in the background.
     */
    fun stopMediaForBackground() {
        legacyRequestVersion.incrementAndGet()
        stopOriaVideo()
        stopLegacyTranscription()
        stopSampleAudio()
        stopLegacyAudio()
        stopLegacyVideo()
        _isImageCapturing.value = false
    }

    private fun legacyStartAllowed(token: Long): Boolean =
        token == legacyRequestVersion.get() && !oriaMode && echoSessionId == null && glass.isConnected

    private fun legacyAudioAllowed(token: Long, request: Long): Boolean =
        legacyStartAllowed(token) && request == legacyAudioVersion.get()

    private fun legacyVideoAllowed(token: Long, request: Long): Boolean =
        legacyStartAllowed(token) && request == legacyVideoVersion.get()

    private fun invalidateSynthesisTransport() {
        _synthesisTransportChanges.value = synthesisEpochCounter.incrementAndGet()
    }

    /** A successful return is submission only, never a statement that sound was heard. */
    fun speakOriaText(text: String): Boolean {
        if (!glass.isConnected) return false
        // A thrown SDK/IPC call has unknown delivery, so let the controller quarantine audio.
        glass.speakText(text, Locale.FRENCH)
        return true
    }

    /** Called on Main. Dedicated ownership: no preview, microphone playback or audio-derived clock. */
    suspend fun startOriaVideo(
        sessionId: Long,
        rotationDegrees: Int = 0,
        mirrored: Boolean = false,
        onFrame: (OriaVideoFrame) -> Boolean,
    ): Boolean {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        if (!oriaMode) { oriaLabRecorder.stop("echo_mode_inactive"); return false }
        // OriaLab is armed before this start, so retain the matching new capture while
        // releasing any old decoder. The UI always stops the old pipeline before arming it.
        oriaLabRecorder.onVideoStarting(sessionId)
        stopOriaVideoInternal(stopRecording = false)
        if (_isVideoStreaming.value) {
            glass.stopVideoStreaming()
            streamPlayer?.onStreamEvent(STOPPED)
            _isVideoStreaming.value = false
        }
        val token = echoRequestVersion.incrementAndGet()
        echoSessionId = sessionId
        _oriaVideoStatus.value = OriaVideoStatus(sessionId, "requesting_permissions")
        if (!glass.isConnected) {
            echoSessionId = null
            oriaLabRecorder.stop("glasses_not_connected")
            _oriaVideoStatus.value = OriaVideoStatus(sessionId, "error", detail = "Glasses are not connected")
            return false
        }
        val permitted = withTimeoutOrNull(30_000) {
            ensurePermissions(Permission.CAMERA,
                requestIsCurrent = { token == echoRequestVersion.get() && oriaMode && glass.isConnected })
        } == true
        if (token != echoRequestVersion.get() || !oriaMode) return false
        if (!permitted || !glass.isConnected) {
            echoSessionId = null
            oriaLabRecorder.stop("camera_permission_denied_or_connection_lost")
            _oriaVideoStatus.value = OriaVideoStatus(sessionId, "error", detail = "Camera permission denied, timed out, or connection lost")
            return false
        }
        val decoder = OriaVideoDecoder(sessionId, rotationDegrees, mirrored,
            // CPU4 depth replay favored 333 ms over 250/167 ms and demand-only sampling.
            // Decode every H.264 dependency; the consumer keeps only the latest pending bitmap.
            sampleIntervalMs = ORIA_SAMPLE_INTERVAL_MS,
            onFrame = { frame ->
                if (token != echoRequestVersion.get() || echoSessionId != sessionId) false
                else {
                    // Copy while decoder still owns the Bitmap: the controller may recycle it
                    // immediately after ownership transfer. PNGs include offered/rejected frames.
                    oriaLabRecorder.recordFrame(frame)
                    val accepted = token == echoRequestVersion.get() && echoSessionId == sessionId && onFrame(frame)
                    oriaLabRecorder.recordEvent(JSONObject().put("type", "frame_delivery")
                        .put("sessionId", sessionId).put("frameId", frame.frameId)
                        .put("receivedAtMs", frame.receivedAtMs).put("atMs", SystemClock.elapsedRealtime())
                        .put("accepted", accepted))
                    accepted
                }
            },
            onStatus = { status ->
                managerScope.launch(Dispatchers.Main) {
                    if (token == echoRequestVersion.get()) {
                        _oriaVideoStatus.value = status
                        oriaLabRecorder.recordEvent(JSONObject().put("type", "decoder_status").put("sessionId", sessionId)
                            .put("atMs", SystemClock.elapsedRealtime()).put("phase", status.phase)
                            .put("receivedPackets", status.receivedPackets).put("decodedFrames", status.decodedFrames)
                            .put("deliveredFrames", status.deliveredFrames).put("staleBeforeConversion", status.staleBeforeConversion)
                            .put("staleAfterConversion", status.staleAfterConversion).put("lastConversionMs", status.lastConversionMs)
                            .put("codecName", status.codecName ?: JSONObject.NULL).put("detail", status.detail ?: JSONObject.NULL))
                    }
                }
            },
            onFatalError = { reason ->
                managerScope.launch(Dispatchers.Main) {
                    if (token == echoRequestVersion.get()) {
                        stopOriaVideo("decoder_error: $reason")
                        _oriaVideoStatus.value = OriaVideoStatus(sessionId, "error", detail = reason)
                    }
                }
            })
        echoDecoder = decoder
        val videoCallback = object : StreamingBufferCallback {
            override fun onReceiveBuffer(byteBuffer: ByteBuffer?, bufferInfo: MediaCodec.BufferInfo?) {
                if (token != echoRequestVersion.get() || byteBuffer == null || bufferInfo == null) return
                val receivedAtMs = SystemClock.elapsedRealtime()
                oriaLabRecorder.recordPacket(byteBuffer, bufferInfo, receivedAtMs, sessionId)
                decoder.submit(byteBuffer, bufferInfo, receivedAtMs)
            }
        }
        val events = StreamingEventCallback { event ->
            // SDK callback threads are unspecified. Check ownership only after dispatch,
            // so stop/restart on Main cannot interleave between the check and mutations.
            managerScope.launch(Dispatchers.Main) {
                if (token != echoRequestVersion.get()) return@launch
                oriaLabRecorder.recordEvent(JSONObject().put("type", "sdk_video_event").put("sessionId", sessionId)
                    .put("atMs", SystemClock.elapsedRealtime()).put("event", event.toString()))
                when (event) {
                    STARTED -> _isVideoStreaming.value = true
                    STOPPED -> {
                        oriaLabRecorder.stop("sdk_video_stopped")
                        _isVideoStreaming.value = false
                        echoDecoder?.close()
                        echoDecoder = null
                        echoSessionId = null
                        echoRequestVersion.incrementAndGet()
                        _oriaVideoStatus.value = OriaVideoStatus(sessionId, "stopped", detail = "SDK stopped the video stream")
                    }
                    else -> {
                        oriaLabRecorder.stop("sdk_video_event: $event")
                        _isVideoStreaming.value = false
                        echoDecoder?.close()
                        echoDecoder = null
                        echoSessionId = null
                        echoRequestVersion.incrementAndGet()
                        _oriaVideoStatus.value = OriaVideoStatus(sessionId, "error", detail = "SDK video event: $event")
                    }
                }
            }
        }
        return try {
            withContext(Dispatchers.Main) {
                if (token != echoRequestVersion.get() || !oriaMode) return@withContext false
                glass.startVideoStreaming(
                    VideoStreamingFormat(VideoStreamingFormat.DEFAULT_BITRATE, VideoStreamingFormat.VideoQuality.DEFAULT, VideoStreamingFormat.FrameRate.FPS_30, false),
                    videoCallback, events)
                true
            }
        } catch (error: Exception) {
            if (token == echoRequestVersion.get()) {
                stopOriaVideo("video_start_failed: ${error.message}")
                _oriaVideoStatus.value = OriaVideoStatus(sessionId, "error", detail = "Start failed: ${error.message}")
            }
            false
        }
    }

    /** Called on Main, including from all SDK callbacks that release this session. */
    fun stopOriaVideo(reason: String = "video_stopped") {
        stopOriaVideoInternal(stopRecording = true, reason = reason)
    }

    private fun stopOriaVideoInternal(stopRecording: Boolean, reason: String = "video_replaced") {
        if (stopRecording) {
            oriaLabRecorder.recordEvent(JSONObject().put("type", "video_stop").put("reason", reason)
                .put("sessionId", echoSessionId ?: _oriaVideoStatus.value.sessionId).put("atMs", SystemClock.elapsedRealtime()))
            oriaLabRecorder.stop(reason)
        }
        echoRequestVersion.incrementAndGet()
        val owned = echoSessionId != null || echoDecoder != null
        val session = echoSessionId ?: _oriaVideoStatus.value.sessionId
        echoSessionId = null
        echoDecoder?.close()
        echoDecoder = null
        if (owned) {
            runCatching { glass.stopVideoStreaming() }
            _isVideoStreaming.value = false
            _oriaVideoStatus.value = OriaVideoStatus(session, "stopped")
        }
    }

    // ======================
    // Callbacks
    // ======================

    private val viveGlassClientCallback = object : ViveGlassClientCallback{
        override fun onConnectionStateChanged(state: ConnectionState?) {
            log.d(TAG, "onConnectionStateChanged() state: [$state]")
            // onDisconnected releases the Oria decoder and must share the same
            // serial executor as start/stop and per-session streaming events.
            managerScope.launch(Dispatchers.Main) {
                when(state)
                {
                    ConnectionState.CONNECTING->
                        log.d(TAG, "onConnectionStateChanged() connecting...")
                    ConnectionState.CONNECTED ->
                        onConnected()
                    ConnectionState.DISCONNECTED ->
                        onDisconnected()
                    ConnectionState.ERROR, null -> {
                        log.e(TAG, "onConnectionStateChanged() error state: [$state]")
                        onDisconnected()
                    }
                    ConnectionState.ERROR_UNREGISTER_APP ->
                    {
                        log.e(TAG, "onConnectionStateChanged() error state: [$state]")
                        onDisconnected()
                    }
                    ConnectionState.ERROR_UNSUPPORTED_ROM_VERSION -> {
                        log.e(TAG, "onConnectionStateChanged() error state: [$state]")
                        onDisconnected()
                    }
                }
            }
        }

        override fun onImageCaptured(
            event: CaptureEvent?,
            payload: ImagePayload?
        ) {
            log.d(TAG, "onImageCaptured() event: [$event], payload null: ${payload == null}")

            try {
                when (event) {
                    CaptureEvent.SUCCESS -> {
                        if (payload == null) {
                            log.e(TAG, "onImageCaptured() success but payload is null")
                            return
                        }

                        val bitmap = byteToBitmap(payload.byteArray)
                        _previewRatio.value = bitmap.width.toFloat() / bitmap.height.toFloat()
                        _imageReceived.tryEmit(bitmap)
                    }

                    CaptureEvent.ERROR,
                    CaptureEvent.ERROR_RESOURCE_CONFLICT,
                    null -> {
                        log.e(TAG, "onImageCaptured() event: [$event]")
                    }
                }
            } finally {
                _isImageCapturing.value = false
            }
        }
        override fun onSpeechTranscribed(
            event: TranscribedEvent?,
            text: String?
        ) {
            log.d(TAG, "onSpeechTranscribed() event: [$event], text: [$text]")
            when(event)
            {
                TranscribedEvent.SUCCESS -> {
                    val transcribed = text ?: return
                    _textTranscribed.value = transcribed
                }
                TranscribedEvent.ERROR,
                TranscribedEvent.ERROR_RESOURCE_CONFLICT ->
                {
                    log.e(TAG, "onSpeechTranscribed() event: [$event]")
                }
                null -> log.e(TAG, "onSpeechTranscribed() event: [null]")
            }
            _isStartTranscribe.value = false
        }
        override fun onTextSpoken(event: SynthesisEvent?) {
            _synthesisEvents.tryEmit(OriaSynthesisEvent(event?.name ?: "UNKNOWN", synthesisTransportEpoch, SystemClock.elapsedRealtime()))
            when(event)
            {
                SynthesisEvent.SUCCESS ->
                    log.d(TAG, "onTextSpoken() event: [$event]")
                SynthesisEvent.ERROR,
                SynthesisEvent.ERROR_RESOURCE_CONFLICT,
                null ->
                    log.e(TAG, "onTextSpoken() event: [$event]")
                SynthesisEvent.ERROR_UNSUPPORTED_LOCALE ->
                    log.e(TAG, "onTextSpoken() event: [$event]")
            }
        }

        override fun onKeyEvent(event: KeyEvent?) {
            if(event == null) return
            log.d(TAG, "onKeyEvent() event: [$event]")
            _keyEvent.tryEmit(event)
        }

        override fun onPermissionResult(
            permission: Permission?,
            result: PermissionResult?
        ) {
            if (permission == null) return
            log.d(TAG, "onPermissionResult() permission: [$permission], result : [$result]")

            val granted = result == PermissionResult.GRANT
            if (!granted) {
                managerScope.launch(Dispatchers.Main) {
                    Toast.makeText(appContext, "Permission: $result", Toast.LENGTH_SHORT).show()
                }
            }
            managerScope.launch {
                permissionMutex.withLock {
                    pendingPermissionRequests.remove(permission)?.complete(granted)
                }
            }
        }
    }

    // Each SDK registration captures its navigation and stream request identities.
    private fun audioRecordCallback(token: Long, request: Long) = object : StreamingBufferCallback {
        override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
            if (buffer == null || info == null) return
            synchronized(legacyMediaLock) {
                if (legacyAudioAllowed(token, request)) {
                    audioDecoder.onReceivedAudioBuffer(buffer, info)
                }
            }
        }
    }

    private fun audioRecordEventCallback(token: Long, request: Long) = StreamingEventCallback { event ->
        managerScope.launch(Dispatchers.Main) {
            synchronized(legacyMediaLock) {
                if (!legacyAudioAllowed(token, request)) return@synchronized
                when (event) {
                    STARTED -> {
                        _isAudioRecording.value = true
                    }
                    STOPPED -> {
                        legacyAudioVersion.incrementAndGet()
                        resetLegacyAudioPlayback()
                    }
                    else -> {
                        legacyAudioVersion.incrementAndGet()
                        resetLegacyAudioPlayback()
                        log.e(TAG, "Legacy audio event: $event")
                    }
                }
            }
        }
    }

    private fun streamEventCallback(token: Long, request: Long) =
        StreamingEventCallback { event ->
            managerScope.launch(Dispatchers.Main) {
                synchronized(legacyMediaLock) {
                    if (!legacyVideoAllowed(token, request)) return@synchronized
                    when (event) {
                        STARTED -> {
                            _isVideoStreaming.value = true
                        }
                        else -> {
                            legacyVideoVersion.incrementAndGet()
                            resetLegacyVideoPlayback()
                            if (event != STOPPED) log.e(TAG, "Legacy video event: $event")
                        }
                    }
                }
            }
        }

    private fun audioStreamCallback(token: Long, request: Long, player: StreamingPlayer) =
        object : StreamingBufferCallback {
            override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
                if (buffer == null || info == null) return
                synchronized(legacyMediaLock) {
                    if (legacyVideoAllowed(token, request)) player.onReceivedAudioBuffer(buffer, info)
                }
            }
        }

    private fun videoStreamCallback(token: Long, request: Long, player: StreamingPlayer) =
        object : StreamingBufferCallback {
            override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
                if (buffer == null || info == null) return
                synchronized(legacyMediaLock) {
                    if (legacyVideoAllowed(token, request)) player.onReceivedVideoBuffer(buffer, info)
                }
            }
        }

    private fun playerCallback(token: Long, request: Long) = object : StreamingPlayer.StreamingPlayerCallback {
        override fun onPlaybackEvent(event: StreamingPlayer.PlaybackEvent) {
            managerScope.launch(Dispatchers.Main) {
                if (!legacyVideoAllowed(token, request)) return@launch
                when (event) {
                    StreamingPlayer.PlaybackEvent.EndOfStream,
                    StreamingPlayer.PlaybackEvent.Stopped -> _isVideoStreaming.value = false
                    is StreamingPlayer.PlaybackEvent.Error -> {
                        _isVideoStreaming.value = false
                        log.e(TAG, "Legacy player error", event.reason)
                    }
                    StreamingPlayer.PlaybackEvent.Started -> {
                        val player = streamPlayer ?: return@launch
                        _previewRatio.value = player.getFrameRatio()
                    }
                }
            }
        }
    }

    // Called under legacyMediaLock. No SDK terminal callback is needed to release local playback.
    private fun resetLegacyAudioPlayback() {
        legacyAudioRequested = false
        _isAudioRecording.value = false
        audioDecoder.setPlaybackEnabled(false)
        audioDecoder.stop()
    }

    private fun resetLegacyVideoPlayback() {
        legacyVideoRequested = false
        _isVideoStreaming.value = false
        streamPlayer?.onStreamEvent(STOPPED)
        streamPlayer = null
        audioDecoder.setPlaybackEnabled(false)
    }

    private fun stopLegacyAudio() {
        synchronized(legacyMediaLock) {
            legacyAudioVersion.incrementAndGet()
            resetLegacyAudioPlayback()
        }
        runCatching { glass.stopAudioStreaming() }.onFailure { log.e(TAG, "Legacy audio stop failed", it) }
    }

    private fun stopLegacyVideo() {
        synchronized(legacyMediaLock) {
            legacyVideoVersion.incrementAndGet()
            resetLegacyVideoPlayback()
        }
        runCatching { glass.stopVideoStreaming() }.onFailure { log.e(TAG, "Legacy video stop failed", it) }
    }

    private fun stopLegacyTranscription() {
        legacyTranscriptionVersion.incrementAndGet()
        _isStartTranscribe.value = false
        runCatching { glass.stopTranscription() }.onFailure { log.e(TAG, "Legacy transcription stop failed", it) }
    }

    // ==============================
    // Camera / Microphone permission
    // ==============================

    fun checkPermission(permit:Permission) : PermissionResult{
        return glass.checkPermission(permit)
    }

    fun requestPermission(permit: Permission)
    {
        glass.requestPermission(activity,permit)
    }
    private data class PermissionCheckResult(
        val granted: Boolean,
        val requested: Boolean
    )

    private suspend fun ensurePermission(permission: Permission): PermissionCheckResult {
        val currentResult = checkPermission(permission)
        if (currentResult == PermissionResult.GRANT) {
            return PermissionCheckResult(
                granted = true,
                requested = false
            )
        }

        var shouldRequest = false

        val deferred = permissionMutex.withLock {
            val existing = pendingPermissionRequests[permission]
            if (existing != null) {
                existing
            } else {
                shouldRequest = true
                CompletableDeferred<Boolean>().also {
                    pendingPermissionRequests[permission] = it
                }
            }
        }

        if (shouldRequest) {
            withContext(Dispatchers.Main) {
                requestPermission(permission)
            }
        }

        val granted = deferred.await()

        return PermissionCheckResult(
            granted = granted,
            requested = shouldRequest
        )
    }

    private suspend fun ensurePermissions(
        vararg permissions: Permission,
        requestIsCurrent: () -> Boolean,
    ): Boolean {
        for (permission in permissions) {
            // A CAMERA result can arrive after leaving its page. Do not let that old
            // request open a fresh MICROPHONE prompt on the new owner's screen.
            if (!requestIsCurrent()) return false
            val permissionResult = ensurePermission(permission)
            if (!requestIsCurrent()) return false
            if (!permissionResult.granted) {
                log.e(TAG, "Permission denied: $permission")
                return false
            }
        }
        return true
    }


    // ==============================
    // Decoder / StreamingPlayer
    // ==============================


    fun attachPreviewSurface(surface: Surface)
    {
        if (renderSurface === surface && videoDecoder != null) return
        // A new diagnostic view must not orphan the previous decoder's HandlerThread.
        stopVideoStreaming()
        synchronized(legacyMediaLock) {
            videoDecoder?.releaseForever()
            videoDecoder = null
        }
        renderSurface = surface

        log.d(TAG, "attachPreviewSurface() Surface attached.")
        createVideoDecoder()
    }

    fun detachAndStopPreview() {
        stopVideoStreaming()
        synchronized(legacyMediaLock) {
            renderSurface = null
            videoDecoder?.releaseForever()
            videoDecoder = null
        }
        log.d(TAG, "detachAndStopPreview() Surface detached.")
    }

    private fun createVideoDecoder()
    {
        val surf = renderSurface ?: return

        videoDecoder = H264Decoder(surf)
    }

    // =======================
    // ViveGlassKit
    // =======================

    override fun connect() {
        log.d(TAG, "connect()")
        glass.connect(viveGlassClientCallback)
    }

    override fun disconnect() {
        invalidateSynthesisTransport()
        stopOriaVideo()
        log.d(TAG, "disconnect()")
        glass.disconnect()
    }

    private fun onConnected(){
        _connection.value = true

        managerScope.launch {
            cachedPreferredLocale = getPreferredLocale()
            log.d(TAG, "Preferred locale cached: $cachedPreferredLocale")
        }
    }

    private fun onDisconnected(){
        invalidateSynthesisTransport()
        stopOriaVideo()
        _connection.value = false
        releasePreviousPage(AppDestination.Chat.route)
        releasePreviousPage(AppDestination.Audio.route)
        releasePreviousPage(AppDestination.Camera.route)
    }

    override fun isConnected(): Boolean {
        return  glass.isConnected
    }

    override fun setSimulator(isUseSimulator: Boolean) {
        if(_isSimulator.value == isUseSimulator && ViveGlass.adapter != null) return
        invalidateSynthesisTransport()
        stopOriaVideo()
        if(glass.isConnected) glass.disconnect()

        _isSimulator.value = isUseSimulator
        if(isUseSimulator)
            ViveGlass.adapter = simulator
        else
            ViveGlass.adapter = kit
    }

    override fun speakText(text: String) {
        // A legacy Chat request must not bypass Oria's gate: anonymous callbacks
        // from that request could otherwise confirm a later Oria announcement.
        manualSpeechHandler?.invoke(text)
    }

    override suspend fun startTranscription() {
        val token = legacyRequestVersion.get()
        if (!legacyStartAllowed(token)) return
        val request = legacyTranscriptionVersion.incrementAndGet()
        val permissionResult = ensurePermission(Permission.MICROPHONE)
        if (!permissionResult.granted) {
            log.e(TAG, "startTranscription() failed: microphone permission denied")
            return
        }

        if(permissionResult.requested) delay(1000) // Wait a moment for the permission state to update

        if (!legacyStartAllowed(token) || request != legacyTranscriptionVersion.get()) return
        glass.startTranscription(false)
        _isStartTranscribe.value = true
    }

    override fun stopTranscription() {
        if (!oriaMode) stopLegacyTranscription()
    }

    override fun playSampleAudio() {
        if (oriaMode) return

        mediaPlayer.setOnCompletionListener {
            _isSampleAudioPlaying.value = false
        }

        if(!mediaPlayer.isPlaying)
        {
            mediaPlayer.start()
            _isSampleAudioPlaying.value = true
        }
    }

    override fun stopSampleAudio() {
        if(mediaPlayer.isPlaying)
        {
            mediaPlayer.pause()
            mediaPlayer.seekTo(0)
        }
        _isSampleAudioPlaying.value = false
    }

    /** startAudioStreaming()
     * Replacing Simulator Audio Samples
     * Path: Transfer your file to the app files directory. Ex:
     * /data/data/com.htc.vive.eagle.hackathon.starter/files/audio_sample.aac
     * Specs: AAC format, 44.1 kHz, 32 kbps, Stereo.
     */
    override suspend fun startAudioStreaming() {
        val token = legacyRequestVersion.get()
        if (!legacyStartAllowed(token)) return
        if (legacyAudioRequested) stopLegacyAudio()
        val request = legacyAudioVersion.incrementAndGet()
        legacyAudioRequested = true
        val permissionResult = ensurePermission(Permission.MICROPHONE)
        if (!permissionResult.granted) {
            if (request == legacyAudioVersion.get()) legacyAudioRequested = false
            log.e(TAG, "startAudioStreaming() failed: microphone permission denied")
            return
        }

        if(permissionResult.requested) delay(1000) // Wait a moment for the permission state to update

        val audioFormat = AudioStreamingFormat(
            Microphone.MIC_DIRECTION_TOWARD_USER,
            AudioStreamingFormat.DEFAULT_BITRATE,
            AudioStreamingFormat.DEFAULT_SAMPLE_RATE_AUDIO,
            AudioChannel.MONO
        )

        if (!legacyAudioAllowed(token, request)) return
        synchronized(legacyMediaLock) {
            // Prepare before SDK dispatch: config buffers may arrive before the STARTED
            // event reaches Main. The event only acknowledges the active request.
            audioDecoder.start()
            audioDecoder.setPlaybackEnabled(true)
        }
        try {
            glass.startAudioStreaming(
                audioFormat,
                audioRecordCallback(token, request),
                audioRecordEventCallback(token, request)
            )
        } catch (error: Exception) {
            if (legacyAudioAllowed(token, request)) stopLegacyAudio()
            throw error
        }
    }

    override fun stopAudioStreaming() {
        if (!oriaMode && legacyAudioRequested) stopLegacyAudio()
    }

    /** captureImage()
     * Replacing Simulator Image Samples
     * Path: Transfer your file to the app files directory. Ex:
     * /data/data/com.htc.vive.eagle.hackathon.starter/files/image_sample.heic
     * Specs: HEIC format, 1440x1920 resolution.
     */
    override suspend fun captureImage() {
        val token = legacyRequestVersion.get()
        if (!legacyStartAllowed(token)) return
        if (_isImageCapturing.value) return

        _isImageCapturing.value = true

        val permissionResult = ensurePermission(Permission.CAMERA)
        if (!permissionResult.granted) {
            log.e(TAG, "captureImage() failed: camera permission denied")
            _isImageCapturing.value = false
            return
        }

        if (permissionResult.requested) {
            delay(1000)
        }

        if (!legacyStartAllowed(token)) { _isImageCapturing.value = false; return }
        val result = glass.captureImage(ImageQuality.DEFAULT)
        log.d(TAG, "takePhoto result : $result")

    }
    /** startVideoStreaming()
     * Replacing Simulator Video Samples
     * Path: Transfer your file to the app file directory. Ex:
     * /data/data/com.htc.vive.eagle.hackathon.starter/files/video_sample.mp4
     * Specs: H.264 video (480x856, 30 FPS) with AAC audio (44.1 kHz, 32 kbps, Stereo).
     */
    override suspend fun startVideoStreaming() {
        val token = legacyRequestVersion.get()
        if (!legacyStartAllowed(token)) return
        if (legacyVideoRequested) stopLegacyVideo()
        val request = legacyVideoVersion.incrementAndGet()
        legacyVideoRequested = true
        val granted = ensurePermissions(
            Permission.CAMERA,
            Permission.MICROPHONE,
            requestIsCurrent = { legacyVideoAllowed(token, request) }
        )
        if (!granted) {
            if (request == legacyVideoVersion.get()) legacyVideoRequested = false
            log.e(TAG, "startVideoStreaming() failed: camera or microphone permission denied")
            return
        }

        var audioFormat = AudioStreamingFormat(
            Microphone.MIC_DIRECTION_TOWARD_USER,
            AudioStreamingFormat.DEFAULT_BITRATE,
            AudioStreamingFormat.DEFAULT_SAMPLE_RATE_VIDEO,
            AudioChannel.STEREO
        )

        var videoFormat = VideoStreamingFormat(
            VideoStreamingFormat.DEFAULT_BITRATE,
            VideoStreamingFormat.VideoQuality.DEFAULT,
            VideoStreamingFormat.FrameRate.FPS_30,
            false
        )

        if (!legacyVideoAllowed(token, request)) return
        val decoder = videoDecoder
        if (decoder == null) {
            legacyVideoRequested = false
            log.e(TAG, "Legacy video start requires a preview Surface")
            return
        }
        val player = StreamingPlayer(decoder, audioDecoder, playerCallback(token, request))
        synchronized(legacyMediaLock) {
            streamPlayer = player
            player.onStreamEvent(STARTED)
        }
        try {
            glass.startVideoStreaming(
                videoFormat,
                audioFormat,
                videoStreamCallback(token, request, player),
                audioStreamCallback(token, request, player),
                streamEventCallback(token, request)
            )
        } catch (error: Exception) {
            if (legacyVideoAllowed(token, request)) stopLegacyVideo()
            throw error
        }
    }

    override fun stopVideoStreaming() {
        if (!oriaMode && echoSessionId == null && legacyVideoRequested) stopLegacyVideo()
    }

    override fun getPreferredLocale(): Locale {
        return glass.userPreferredLocale
    }

    override fun cleanup() {
        manualSpeechHandler = null
        stopMediaForBackground()
        oriaLabRecorder.close()
        invalidateSynthesisTransport()
        managerScope.cancel()

        audioDecoder.releaseForever()
        videoDecoder?.releaseForever()

        mediaPlayer.stop()
        mediaPlayer.release()
    }

    // =======================
    // Release previous resource
    // =======================

    fun releasePreviousPage(routeToRelease: String)
    {
        legacyRequestVersion.incrementAndGet()
        when(routeToRelease){
            AppDestination.Glasses.route ->{}

            AppDestination.Chat.route ->
            {
                stopTranscription()
            }

            AppDestination.Audio.route ->
            {
                if(_isSampleAudioPlaying.value)
                {
                    mediaPlayer.pause()
                    mediaPlayer.seekTo(0)
                    _isSampleAudioPlaying.value = false
                }

                stopAudioStreaming()
            }

            AppDestination.Camera.route ->
            {
                stopVideoStreaming()
            }
        }
    }
    // =======================
    // HEIC to bitmap
    // =======================

    private fun byteToBitmap(data: ByteArray) : Bitmap
    {
        val bitmap =BitmapFactory.decodeByteArray(data,0,data.size)
        val orientation = readHeicExif(data)["Orientation"] ?: return bitmap
        return rotateBitmap(bitmap, orientation)
    }

    private fun readHeicExif(bytes: ByteArray): Map<String, String?> {
        ByteArrayInputStream(bytes).use { input ->
            val exif = ExifInterface(input)

            return mapOf(
                "DateTime" to exif.getAttribute(ExifInterface.TAG_DATETIME),
                "Make" to exif.getAttribute(ExifInterface.TAG_MAKE),
                "Model" to exif.getAttribute(ExifInterface.TAG_MODEL),
                "Orientation" to exif.getAttribute(ExifInterface.TAG_ORIENTATION),
                "GPS Lat" to exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE),
                "GPS Lon" to exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            )
        }
    }
    private fun rotateBitmap(
        source: Bitmap,
        orientation: String
    ): Bitmap {
        val degrees = when(orientation) {
            ORIENTATION_ROTATE_180.toString() -> 180f
            ORIENTATION_ROTATE_90.toString() -> 90f
            ORIENTATION_ROTATE_270.toString() -> 270f
            ORIENTATION_NORMAL.toString() -> 0f
            else -> 0f
        }

        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }
}
