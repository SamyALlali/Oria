package com.htc.vive.eagle.hackathon.starter.oria.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BluetoothSpeechState(val ready: Boolean, val detail: String)
enum class SpeechDelivery { COMPLETED, NOT_PLAYED, INTERRUPTED, EXPIRED }

/** One shared backend for automatic announcements and manual speech. No TTS.speak/SCO fallback. */
class BluetoothSpeechBackend(context: Context,
                             private val onResult: (id: String, result: SpeechDelivery, detail: String) -> Unit) : Closeable {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "oria-bluetooth-voice") }
    private val synth = LocalFrenchSynthesizer(context)
    private val player = BluetoothPcmPlayer(manager) { main.post { refreshState() } }
    private val lock = Any()
    private val cache = LinkedHashMap<String, SpeechPcm>(32, 0.75f, true)
    private var required = emptySet<String>()
    private var active: Request? = null
    private var voiceReady = false
    private var preparationError: String? = null
    @Volatile private var closed = false
    private val sessionActive = AtomicBoolean(false)
    private val sessionVersion = AtomicLong(0)
    private val warmingScheduled = AtomicBoolean(false)
    private var warmingError: String? = null
    private val seenIds = LinkedHashSet<String>()
    private val _state = MutableStateFlow(BluetoothSpeechState(false, "Préparation de la voix française locale…"))
    val state: StateFlow<BluetoothSpeechState> = _state.asStateFlow()
    private data class Request(val id: String, val text: String, val cancelled: AtomicBoolean = AtomicBoolean(false))
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshState()
            scheduleWarmup()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (findDevice() == null) stop()
            refreshState()
        }
    }

    init { manager.registerAudioDeviceCallback(devices, main) }

    /** At most thirteen pinned phrases, with a maximum of thirty-two cached phrases including Chat. */
    fun prepare(texts: Set<String>) {
        synchronized(lock) {
            if (closed) return
            if (texts.size > 13 || texts.any { !validText(it) }) {
                preparationError = "Préparation vocale invalide (13 phrases courtes maximum)"
                refreshState()
                return
            }
            required = texts.toSet()
            preparationError = null
            worker.execute {
                try {
                    synth.awaitReady()
                    synchronized(lock) { voiceReady = true }
                    for (text in texts) {
                        if (synchronized(lock) { closed }) return@execute
                        if (synchronized(lock) { cache[text] } == null) putCache(text, synth.synthesize(text))
                        refreshState()
                    }
                } catch (e: Exception) {
                    synchronized(lock) { preparationError = rootMessage(e) }
                }
                refreshState()
                scheduleWarmup()
            }
        }
        refreshState()
    }

    /** Keep one already routed AudioTrack for the video session; false releases it immediately. */
    fun setSessionActive(active: Boolean) {
        synchronized(lock) {
            if (closed) return
            if (sessionActive.getAndSet(active) != active) sessionVersion.incrementAndGet()
            warmingError = null
        }
        player.setSessionActive(active)
        if (active) scheduleWarmup() else stop()
        refreshState()
    }

    /** Danger tones share the verified route and pause while speech PCM is written. */
    fun setDangerPattern(pattern: DangerSoundPattern) {
        player.setDangerPattern(pattern)
    }

    private fun scheduleWarmup() {
        synchronized(lock) {
            if (closed || !sessionActive.get() || !voiceReady || !required.all { it in cache } ||
                cache.isEmpty() || !warmingScheduled.compareAndSet(false, true)) return
            val expectedVersion = sessionVersion.get()
            worker.execute {
                try {
                    if (closed || !sessionActive.get() || sessionVersion.get() != expectedVersion) return@execute
                    val pcm = synchronized(lock) { cache.values.firstOrNull() } ?: return@execute
                    val device = findDevice() ?: error("Sortie Bluetooth VIVE absente ou ambiguë")
                    player.warm(pcm, device) { !closed && sessionActive.get() && sessionVersion.get() == expectedVersion }
                    synchronized(lock) { if (sessionVersion.get() == expectedVersion) warmingError = null }
                } catch (e: Exception) {
                    synchronized(lock) { if (sessionActive.get() && sessionVersion.get() == expectedVersion) warmingError = rootMessage(e) }
                } finally {
                    warmingScheduled.set(false)
                    refreshState()
                    if (!closed && sessionActive.get() && sessionVersion.get() != expectedVersion) scheduleWarmup()
                }
            }
        }
    }

    /** Predicates run on the worker and must read thread-safe state; they must not mutate the policy. */
    fun submit(id: String, text: String, pan: SpeechPan = SpeechPan.CENTER,
               canStart: () -> Boolean, canContinue: () -> Boolean): Boolean {
        synchronized(lock) {
            if (closed || active != null || !_state.value.ready || !validText(text) || id.isBlank() || id in seenIds) return false
            if (sessionActive.get() && !player.isWarmReady()) return false
            val request = Request(id, text)
            active = request
            seenIds.add(id)
            // Callers supply unique IDs. Retaining a bounded history catches accidental immediate reuse.
            if (seenIds.size > 256) seenIds.remove(seenIds.first())
            worker.execute {
                val result = try {
                    check(!request.cancelled.get()) { "Commande annulée" }
                    val pcm = synchronized(lock) { cache[text] } ?: synth.synthesize(text).also { putCache(text, it) }
                    // Rendering occurs on the worker, before the final freshness guard in play().
                    val rendered = SpeechPcmTransforms.panned(pcm, pan)
                    check(!request.cancelled.get()) { "Commande annulée" }
                    val device = findDevice() ?: error("Sortie Bluetooth VIVE absente ou ambiguë")
                    player.play(rendered, device, request.cancelled, canStart, canContinue)
                } catch (e: Exception) { PlaybackResult(SpeechDelivery.NOT_PLAYED, rootMessage(e)) }
                main.post {
                    synchronized(lock) { if (active === request) active = null }
                    val finalResult = if (request.cancelled.get() && result.delivery == SpeechDelivery.COMPLETED)
                        PlaybackResult(SpeechDelivery.INTERRUPTED, "Arrêt demandé avant confirmation de lecture") else result
                    onResult(request.id, finalResult.delivery, finalResult.detail)
                    refreshState()
                }
            }
            return true
        }
    }

    fun stop() {
        val cancelSynthesis = synchronized(lock) { active?.also { it.cancelled.set(true) } != null }
        player.stop()
        if (cancelSynthesis) synth.cancel()
        refreshState()
    }

    private fun findDevice(): AudioDeviceInfo? = runCatching {
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
            device.isSink && device.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) &&
                device.productName.toString().contains("VIVE", ignoreCase = true) &&
                device.productName.toString().contains("Eagle", ignoreCase = true)
        }.singleOrNull()
    }.getOrNull()

    private fun putCache(text: String, pcm: SpeechPcm) = synchronized(lock) {
        require(pcm.channels == 2) { "Le cache vocal nécessite le format stéréo préparé" }
        cache.values.firstOrNull()?.let { reference ->
            require(reference.sampleRate == pcm.sampleRate && reference.channels == pcm.channels) {
                "Format de voix différent des phrases préparées"
            }
        }
        cache[text] = pcm
        while (cache.size > 32) {
            val victim = cache.keys.firstOrNull { it !in required } ?: cache.keys.first()
            cache.remove(victim)
        }
    }

    private fun refreshState() {
        val device = findDevice()
        synchronized(lock) {
            val prepared = voiceReady && required.all { it in cache }
            _state.value = when {
                closed -> BluetoothSpeechState(false, "Voix fermée")
                preparationError != null -> BluetoothSpeechState(false, "Voix indisponible : $preparationError")
                !prepared -> BluetoothSpeechState(false, "Préparation de la voix française locale (${required.count { it in cache }}/${required.size})…")
                device == null -> BluetoothSpeechState(false, "Sortie Bluetooth VIVE absente ou ambiguë")
                sessionActive.get() && warmingError != null -> BluetoothSpeechState(false, "Route audio indisponible : $warmingError · arrêter puis redémarrer")
                sessionActive.get() && !player.isWarmReady() -> BluetoothSpeechState(false,
                    if (warmingScheduled.get()) "Préparation de la route Bluetooth VIVE…"
                    else "Route Bluetooth interrompue · arrêter puis redémarrer")
                else -> BluetoothSpeechState(true, "Voix française locale · ${device.productName}" +
                    if (sessionActive.get()) " · route prête" else "")
            }
        }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        sessionActive.set(false)
        sessionVersion.incrementAndGet()
        stop()
        player.close()
        manager.unregisterAudioDeviceCallback(devices)
        synth.close()
        worker.shutdown()
        synchronized(lock) { cache.clear() }
        refreshState()
    }

    private fun validText(text: String) = text.isNotBlank() && text.length <= 240
    private fun rootMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
