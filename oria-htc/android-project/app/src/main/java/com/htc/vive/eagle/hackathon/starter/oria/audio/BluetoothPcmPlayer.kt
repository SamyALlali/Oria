package com.htc.vive.eagle.hackathon.starter.oria.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class PlaybackResult(val delivery: SpeechDelivery, val detail: String)

/** Keeps the SAME routed track alive between phrases. A preference is never an exclusive route. */
internal class BluetoothPcmPlayer(private val manager: AudioManager,
                                 private val onWarmStateChanged: () -> Unit) : Closeable {
    private class Expired : IllegalStateException("Annonce devenue périmée avant lecture")
    private class Lease(val track: AudioTrack, val device: AudioDeviceInfo, val rate: Int,
                        val channels: Int, val focus: AudioFocusRequest, val version: Long) {
        val frameBytes = channels * 2
        val silence = ByteArray(maxOf(1, rate / 50) * frameBytes) // 20 ms per write.
        var verified = false
        var speaking = false
        var draining = false
        var written = 0L
        var lastHead = 0L
        var wraps = 0L
        var tonePhase = 0.0
        var failure: String? = null
    }
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var current: Lease? = null
    private var keepWarm = false
    private var dangerPattern = DangerSoundPattern.silent()
    private var version = 0L
    private val silenceWorker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oria-audio-silence") }

    init { silenceWorker.scheduleWithFixedDelay({ maintainSilence() }, 0, 10, TimeUnit.MILLISECONDS) }

    fun setSessionActive(active: Boolean) = synchronized(lock) {
        if (keepWarm != active) { keepWarm = active; version++ }
        if (!active) { dangerPattern = DangerSoundPattern.silent(); releaseLocked(current) }
    }

    fun setDangerPattern(pattern: DangerSoundPattern) = synchronized(lock) {
        dangerPattern = if (keepWarm) pattern else DangerSoundPattern.silent()
    }

    fun isWarmReady(): Boolean = synchronized(lock) {
        val lease = current
        keepWarm && lease != null && lease.version == version && lease.verified && lease.failure == null &&
            runCatching { lease.track.routedDevice?.id == lease.device.id }.getOrDefault(false)
    }

    fun stop() = synchronized(lock) {
        // Invalidates track creation that is still outside this lock.
        version++
        releaseLocked(current)
    }

    fun warm(pcm: SpeechPcm, device: AudioDeviceInfo, stillActive: () -> Boolean) {
        ensureTrack(pcm, device) { synchronized(lock) { keepWarm } && stillActive() }
    }

    private fun releaseLocked(lease: Lease?) {
        if (lease == null || current !== lease) return
        current = null
        runCatching { lease.track.setVolume(0f) }
        runCatching { lease.track.pause() }
        runCatching { lease.track.flush() }
        runCatching { lease.track.release() }
        runCatching { manager.abandonAudioFocusRequest(lease.focus) }
        onWarmStateChanged()
    }

    private fun consumedLocked(lease: Lease): Long {
        val head = lease.track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < lease.lastHead) lease.wraps += 1L shl 32
        lease.lastHead = head
        return lease.wraps + head
    }

    private fun silenceLocked(lease: Lease, maxQueuedFrames: Int) {
        val queued = (lease.written - consumedLocked(lease)).coerceAtLeast(0)
        val frames = minOf(lease.silence.size / lease.frameBytes, (maxQueuedFrames - queued).coerceAtLeast(0).toInt())
        if (frames == 0) return
        val count = lease.track.write(lease.silence, 0, frames * lease.frameBytes, AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0) { "Maintien audio refusé ($count)" }
        lease.written += count / lease.frameBytes
    }

    private fun maintainSilence() = synchronized(lock) {
        val lease = current ?: return@synchronized
        if (!keepWarm || lease.version != version || !lease.verified || (lease.speaking && !lease.draining)) return@synchronized
        try {
            check(lease.track.routedDevice?.id == lease.device.id) { "Route VIVE perdue" }
            // Never accumulates an unbounded queue of silence between utterances.
            backgroundLocked(lease, lease.rate * 40 / 1000)
        } catch (e: Exception) {
            lease.failure = e.message ?: "Route VIVE perdue"
            releaseLocked(lease)
        }
    }

    private fun backgroundLocked(lease: Lease, maxQueuedFrames: Int) {
        val queued = (lease.written - consumedLocked(lease)).coerceAtLeast(0)
        val frames = minOf(lease.silence.size / lease.frameBytes,
            (maxQueuedFrames - queued).coerceAtLeast(0).toInt())
        if (frames == 0) return
        val bytes = lease.silence
        val pattern = dangerPattern
        if (!pattern.audible || lease.channels != 2) {
            bytes.fill(0)
        } else {
            val rendered = DangerToneRenderer.render(pattern, lease.rate, frames,
                SystemClock.elapsedRealtime(), lease.tonePhase)
            rendered.pcm16Stereo.copyInto(bytes)
            lease.tonePhase = rendered.nextPhase
        }
        if (!lease.speaking) lease.track.setVolume(if (pattern.audible) 1f else 0f)
        val count = lease.track.write(bytes, 0, frames * lease.frameBytes, AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0) { "Maintien audio refusé ($count)" }
        lease.written += count / lease.frameBytes
    }

    /** Only the single speech worker creates tracks. Main/route callbacks may invalidate them. */
    private fun ensureTrack(pcm: SpeechPcm, device: AudioDeviceInfo, valid: () -> Boolean): Lease {
        val creationVersion = synchronized(lock) {
            check(valid()) { "Préparation audio annulée" }
            current?.let { lease ->
                check(lease.version == version) { "Route d’une ancienne session" }
                check(lease.rate == pcm.sampleRate && lease.channels == pcm.channels) { "Format PCM différent de la route préparée" }
                check(lease.device.id == device.id && lease.track.routedDevice?.id == device.id && lease.verified) { "Route préparée indisponible" }
                return lease
            }
            version
        }
        var owned: Lease? = null
        var built: AudioTrack? = null
        val warmStartedAt = SystemClock.elapsedRealtime()
        var lastDiagnosticAt = 0L
        var lastDiagnostic = "track=not-created targetId=${device.id}"
        val focusLost = AtomicBoolean(false)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    focusLost.set(true)
                    synchronized(lock) {
                        owned?.let { if (current === it) { it.failure = "Priorité audio perdue"; releaseLocked(it) } }
                    }
                }
            }, main).build()
        var focusGranted = false
        try {
            focusGranted = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(focusGranted) { "Priorité audio indisponible" }
            val mask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Format de lecture indisponible" }
            val track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(mask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, pcm.sampleRate * pcm.frameBytes / 10)).build()
            built = track
            val lease = Lease(track, device, pcm.sampleRate, pcm.channels, focus, creationVersion)
            synchronized(lock) {
                check(version == creationVersion && valid() && !focusLost.get()) { "Préparation audio annulée" }
                check(track.state == AudioTrack.STATE_INITIALIZED) { "Sortie audio indisponible" }
                current = lease
                owned = lease
                track.setVolume(0f)
                check(track.setPreferredDevice(device)) { "Sortie VIVE refusée" }
                if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(maxOf(1, pcm.sampleRate / 50))
                track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { routing ->
                    synchronized(lock) {
                        if (current === lease) {
                            val routed = routing.routedDevice
                            if ((routed != null && routed.id != device.id) || (lease.verified && routed == null)) {
                                lease.failure = "Route VIVE perdue"
                                releaseLocked(lease)
                            }
                        }
                    }
                }, main)
                silenceLocked(lease, pcm.sampleRate / 50)
                track.play()
            }
            // This is advance preparation, never an extension of the 500 ms announcement age.
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (true) {
                val verified = synchronized(lock) {
                    check(current === lease && version == creationVersion && valid()) { lease.failure ?: "Préparation audio annulée" }
                    val consumed = consumedLocked(lease)
                    val queued = (lease.written - consumed).coerceAtLeast(0)
                    val routedId = track.routedDevice?.id
                    val threshold = if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames else track.bufferSizeInFrames
                    val bufferFrames = track.bufferSizeInFrames
                    val measuredAt = SystemClock.elapsedRealtime()
                    lastDiagnostic = "elapsedMs=${measuredAt - warmStartedAt} routeId=$routedId targetId=${device.id} " +
                        "head=$consumed written=${lease.written} queued=$queued threshold=$threshold " +
                        "buffer=$bufferFrames playState=${track.playState} rate=${pcm.sampleRate}"
                    if (measuredAt - lastDiagnosticAt >= 250) {
                        Log.i("OriaAudio", "warmup $lastDiagnostic")
                        lastDiagnosticAt = measuredAt
                    }
                    // A selected route alone is insufficient: observe actual playback progression,
                    // then drain startup silence to the bounded steady-state queue before ready.
                    if (routedId == device.id && consumed > 0 && queued <= pcm.sampleRate * 40 / 1000) {
                        lease.verified = true
                        true
                    } else {
                        // A2DP may require more than its reported start threshold. Fill no more than
                        // this track's actual buffer, then drain back to 40 ms before accepting speech.
                        silenceLocked(lease, if (consumed == 0L) bufferFrames else pcm.sampleRate * 40 / 1000)
                        false
                    }
                }
                if (verified) {
                    Log.i("OriaAudio", "warmup_ready $lastDiagnostic")
                    onWarmStateChanged()
                    return lease
                }
                check(SystemClock.elapsedRealtime() < deadline) { "Route VIVE non confirmée" }
                Thread.sleep(10)
            }
        } catch (e: Exception) {
            Log.w("OriaAudio", "warmup_failed reason=${e.message} $lastDiagnostic")
            synchronized(lock) { owned?.let { releaseLocked(it) } }
            if (owned == null) {
                runCatching { built?.release() }
                if (focusGranted) runCatching { manager.abandonAudioFocusRequest(focus) }
            }
            throw e
        }
    }

    fun play(pcm: SpeechPcm, device: AudioDeviceInfo, cancelled: AtomicBoolean,
             canStart: () -> Boolean, canContinue: () -> Boolean): PlaybackResult {
        var vocalStarted = false
        var lease: Lease? = null
        var keepAfter = false
        fun valid() = !cancelled.get() && canContinue()
        try {
            val output = ensureTrack(pcm, device, ::valid)
            lease = output
            synchronized(lock) {
                check(current === output && valid()) { "Lecture annulée" }
                output.speaking = true
                output.draining = false
            }
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (offset < pcm.bytes.size) {
                check(valid()) { "Lecture annulée" }
                check(SystemClock.elapsedRealtime() < deadline) { "Lecture expirée" }
                val count = synchronized(lock) {
                    check(current === output && output.track.routedDevice?.id == device.id) { output.failure ?: "Route VIVE perdue" }
                    check(valid()) { "Lecture annulée" }
                    if (!vocalStarted) {
                        if (!canStart()) throw Expired()
                        output.track.setVolume(1f)
                    }
                    val previouslyStarted = vocalStarted
                    vocalStarted = true // A throwing write has an uncertain partial result.
                    val n = output.track.write(pcm.bytes, offset, minOf(output.silence.size, pcm.bytes.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    if (n <= 0) vocalStarted = previouslyStarted
                    check(n >= 0) { "Écriture audio refusée ($n)" }
                    output.written += n / output.frameBytes
                    n
                }
                offset += count
                if (count == 0) Thread.sleep(5)
            }
            val phraseEnd = synchronized(lock) { output.draining = true; output.written }
            while (true) {
                check(valid()) { "Lecture annulée" }
                val consumed = synchronized(lock) {
                    check(current === output && output.track.routedDevice?.id == device.id) { output.failure ?: "Route VIVE perdue" }
                    consumedLocked(output)
                }
                if (consumed >= phraseEnd) break
                check(SystemClock.elapsedRealtime() < deadline) { "Fin de lecture non confirmée" }
                Thread.sleep(10)
            }
            keepAfter = true
            return PlaybackResult(SpeechDelivery.COMPLETED, "Lecture Android terminée sur ${device.productName} · écoute à vérifier")
        } catch (e: Exception) {
            keepAfter = e is Expired // An expired candidate must not destroy an already warm route.
            return PlaybackResult(if (vocalStarted) SpeechDelivery.INTERRUPTED else if (e is Expired) SpeechDelivery.EXPIRED else SpeechDelivery.NOT_PLAYED,
                e.message ?: "Lecture interrompue")
        } finally {
            synchronized(lock) {
                lease?.let { output ->
                    if (current === output) {
                        output.speaking = false
                        output.draining = false
                        if (!keepAfter || !keepWarm || output.version != version) releaseLocked(output)
                    }
                }
            }
        }
    }

    override fun close() { setSessionActive(false); stop(); silenceWorker.shutdown() }
}
