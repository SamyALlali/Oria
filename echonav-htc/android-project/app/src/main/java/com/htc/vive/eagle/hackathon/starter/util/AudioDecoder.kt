package com.htc.vive.eagle.hackathon.starter.util

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class AudioDecoder(
    private val audioManager: AudioManager
) {

    // ---- Log ----
    private val log = Logger.instance
    private val logTag = "AudioDecoder"

    // ---------- State ----------
    private enum class State { IDLE, EXECUTING, STOPPING }
    private val state = AtomicReference(State.IDLE)
    private enum class PlayState { BUFFERING, PLAYING }
    @Volatile private var playState = PlayState.BUFFERING

    // ---------- Codec / Track ----------
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val outInfo = MediaCodec.BufferInfo()

    // ---------- Decode Thread ----------
    private val thread = HandlerThread("AudioDecoderThread").apply { start() }
    private val h = Handler(thread.looper)

    // ---------- NEW: Audio Writer Thread ----------
    private val writerThread = HandlerThread("AudioWriterThread").apply { start() }
    private val wh = Handler(writerThread.looper)
    private val writerRunning = AtomicBoolean(false)

    // ---------- Config from stream ----------
    private var asc: ByteArray? = null
    private var cfg: AacConfig? = null
    private var configured = false

    // ---------- Queues ----------
    private val aacQueue = ArrayBlockingQueue<Pair<Long, ByteArray>>(400)
    private val pcmQueue = ArrayBlockingQueue<ByteArray>(400)

    // Bytes still waiting in pcmQueue (not yet written into AudioTrack)
    private val pcmQueuedBytes = AtomicLong(0)

    // Frames written into AudioTrack (for estimating how much AudioTrack still holds)
    private val framesWritten = AtomicLong(0)

    // ---------- Buffer thresholds ----------
    private val lowPcmBufferedMs = 40     // enter BUFFERING if <= this
    private val highPcmBufferedMs = 120   // exit BUFFERING (start/resume) if >= this

    // Write pacing (avoid draining queue in one burst)
    private val maxWriteChunkMs = 20      // write up to ~20ms per writer tick

    // ---------- Timing anchor ----------
    @Volatile private var playbackEnabled = false
    private var anchored = false
    private var audioPts0Us = 0L
    private var frame0 = 0
    private var sampleRate = 0
    private var channelCount = 0

    // ---------------- Public ----------------

    fun start() {
        h.post {
            if (!state.compareAndSet(State.IDLE, State.EXECUTING)) return@post

            safeReleaseLocked()
            resetTimingLocked()

            asc = null
            cfg = null
            configured = false
            playState = PlayState.BUFFERING

            aacQueue.clear()
            pcmQueue.clear()
            pcmQueuedBytes.set(0)
            framesWritten.set(0)

            startWriterLocked()
        }
    }

    fun stop() {
        h.post {
            if (state.get() == State.IDLE) return@post
            state.set(State.STOPPING)

            stopWriterLocked()

            safeReleaseLocked()
            resetTimingLocked()
            state.set(State.IDLE)
        }
    }

    fun releaseForever() {
        stop()
        h.post { thread.quitSafely() }
        h.post { writerThread.quitSafely() }
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        playbackEnabled = enabled
        h.post {
            if (!enabled) {
                playState = PlayState.BUFFERING
                try { audioTrack?.pause() } catch (_: Throwable) {}
            }
        }
    }

    fun onReceivedAudioBuffer(src: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return

        val payload = ByteArray(info.size)
        src.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
            get(payload)
        }

        val ptsUs = info.presentationTimeUs
        val flags = info.flags

        h.post {
            if (state.get() != State.EXECUTING) return@post

            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                asc = payload
                cfg = runCatching { parseAacAsc(payload) }.getOrNull()
                log.w(logTag, "AAC CONFIG(flag): ascLen=${payload.size} cfg=$cfg")
                tryConfigureLocked()
                return@post
            }

            if (asc == null && looksLikeAdts(payload)) {
                asc = buildAscFromAdtsHeader(payload)
                cfg = runCatching { parseAacAsc(asc!!) }.getOrNull()
                log.w(logTag, "AAC CONFIG(from ADTS): cfg=$cfg")
                tryConfigureLocked()
            }

            offerBounded(aacQueue, ptsUs to payload)
            decodeTickLocked()
        }
    }

    fun getClockUs(): Long {
        if (!anchored) return 0L
        val track = audioTrack ?: return 0L
        if (sampleRate <= 0) return 0L

        val framesPlayed = track.playbackHeadPosition - frame0
        val playedUs = framesPlayed * 1_000_000L / sampleRate
        return audioPts0Us + playedUs
    }

    // ---------------- Core decode loop (no render) ----------------

    private fun decodeTickLocked() {
        if (!configured) return
        val c = codec ?: return

        var fed = 0
        while (fed < 6) {
            val (pts, data) = aacQueue.poll() ?: break
            val au = if (looksLikeAdts(data)) stripAdts(data) else data
            feedInputLocked(c, au, pts)
            fed++
        }

        drainDecodedPcmLocked(c)

        // Wake writer (it may be sleeping in BUFFERING)
        wh.post {  }
    }

    // ---------------- Configure ----------------

    private fun tryConfigureLocked() {
        if (configured) return
        val csd0 = asc ?: return
        val cfgLocal = cfg ?: return

        sampleRate = cfgLocal.sampleRate
        channelCount = cfgLocal.channelCount

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            cfgLocal.sampleRate,
            cfgLocal.channelCount
        )
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }

        audioTrack = createAudioTrackBigBuffer(cfgLocal.sampleRate, cfgLocal.channelCount).also {
            framesWritten.set(0)
        }
        audioTrack?.setVolume(1.0f)

        configured = true
        playState = PlayState.BUFFERING

        log.w(
            logTag,
            "Configured AAC: sr=${cfgLocal.sampleRate} ch=${cfgLocal.channelCount} trackBufFrames=${audioTrack?.bufferSizeInFrames}"
        )
    }

    // ---------------- Codec IO ----------------

    private fun feedInputLocked(c: MediaCodec, data: ByteArray, ptsUs: Long) {
        val inIndex = c.dequeueInputBuffer(0)
        if (inIndex < 0) return
        val inBuf = c.getInputBuffer(inIndex) ?: run {
            c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            return
        }
        inBuf.clear()
        inBuf.put(data)
        c.queueInputBuffer(inIndex, 0, data.size, ptsUs, 0)
    }

    private fun drainDecodedPcmLocked(c: MediaCodec) {
        while (true) {
            val outIndex = c.dequeueOutputBuffer(outInfo, 0)
            when {
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (outBuf != null && outInfo.size > 0) {
                        val pcm = ByteArray(outInfo.size)
                        outBuf.position(outInfo.offset)
                        outBuf.limit(outInfo.offset + outInfo.size)
                        outBuf.get(pcm)

                        offerBounded(pcmQueue, pcm)
                        pcmQueuedBytes.addAndGet(pcm.size.toLong())
                        //log.w(logTag, "drainDecodedPcmLocked() pcm.size[${pcm.size}], pcmQueuedBytes[${pcmQueuedBytes.get()}]")
                    }
                    c.releaseOutputBuffer(outIndex, false)

                    if ((outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    log.w(logTag, "OUTPUT_FORMAT_CHANGED fmt=${c.outputFormat}")
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    // ---------------- Writer loop ----------------

    private fun startWriterLocked() {
        if (writerRunning.getAndSet(true)) return
        wh.post { writerLoop() }
    }

    private fun stopWriterLocked() {
        writerRunning.set(false)
    }

    private fun writerLoop() {
        if (!writerRunning.get()) return

        val track = audioTrack
        val cfgLocal = cfg

        if (state.get() != State.EXECUTING || track == null || cfgLocal == null || !configured) {
            wh.postDelayed({ writerLoop() }, 5)
            return
        }

        val bytesPerMs = (cfgLocal.sampleRate * cfgLocal.channelCount * 2) / 1000
        val lowBytes = lowPcmBufferedMs.toLong() * bytesPerMs
        val highBytes = highPcmBufferedMs.toLong() * bytesPerMs

        val buffered = totalBufferedBytes(track, cfgLocal.channelCount)

        if (!playbackEnabled) {
            playState = PlayState.BUFFERING
            try { track.pause() } catch (_: Throwable) {}
            wh.postDelayed({ writerLoop() }, 10)
            return
        }

        when (playState) {
            PlayState.BUFFERING -> {

                try { track.pause() } catch (_: Throwable) {}

                if (buffered >= highBytes) {
                    if (!anchored) {
                        audioPts0Us = outInfo.presentationTimeUs
                        frame0 = track.playbackHeadPosition
                        anchored = true
                    }
                    log.w(logTag, "BUFFERING -> PLAYING buffered=$buffered >= high=$highBytes")
                    playState = PlayState.PLAYING
                    try {
                        track.play()

                        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

                        track.setPreferredDevice(speaker)
                    } catch (_: Throwable) {}
                }

                wh.postDelayed({ writerLoop() }, 5)
            }

            PlayState.PLAYING -> {

                if (buffered <= lowBytes) {
                    log.w(logTag, "PLAYING -> BUFFERING buffered=$buffered <= low=$lowBytes")
                    playState = PlayState.BUFFERING
                    try { track.pause() } catch (_: Throwable) {}
                    wh.postDelayed({ writerLoop() }, 5)
                    return
                }

                val maxWriteBytes = bytesPerMs * maxWriteChunkMs
                var wroteBytes = 0

                while (wroteBytes < maxWriteBytes && writerRunning.get()) {
                    val pcm = pcmQueue.poll() ?: break
                    pcmQueuedBytes.addAndGet(-pcm.size.toLong())

                    val actual = writeFullyBlocking(track, pcm)
                    wroteBytes += actual

                    val bytesPerFrame = cfgLocal.channelCount * 2
                    framesWritten.addAndGet((actual / bytesPerFrame).toLong())

                    if (actual < pcm.size) break
                }

                val delay = if (wroteBytes == 0) 0L else 0L
                wh.postDelayed({ writerLoop() }, delay)
            }
        }
    }

    private fun totalBufferedBytes(track: AudioTrack, channelCount: Int): Long {
        val inQueue = pcmQueuedBytes.get()
        val bytesPerFrame = (channelCount * 2).toLong()
        val headFrames = track.playbackHeadPosition.toLong()
        val inTrackFrames = (framesWritten.get() - headFrames).coerceAtLeast(0)
        val inTrackBytes = inTrackFrames * bytesPerFrame
        return inQueue + inTrackBytes
    }

    // ---------------- AudioTrack ----------------

    private fun createAudioTrackBigBuffer(sampleRate: Int, channelCount: Int): AudioTrack {
        val channelMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> throw IllegalArgumentException("Unsupported channelCount: $channelCount")
        }

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

        val bytesPerMs = (sampleRate * channelCount * 2) / 1000
        val highBytes = bytesPerMs * highPcmBufferedMs
        val bufferSizeBytes = max(minBuf * 4, highBytes * 2)

        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build(),
            bufferSizeBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun writeFullyBlocking(track: AudioTrack, pcm: ByteArray): Int {
        var off = 0
        while (off < pcm.size && writerRunning.get()) {
            val n = if (android.os.Build.VERSION.SDK_INT >= 23) {
                track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(pcm, off, pcm.size - off)
            }
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun <T> offerBounded(q: ArrayBlockingQueue<T>, item: T) {
        if (!q.offer(item)) {
            q.poll()
            q.offer(item)
        }
    }

    private fun safeReleaseLocked() {
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null

        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null

        configured = false
    }

    private fun resetTimingLocked() {
        anchored = false
        audioPts0Us = 0L
        frame0 = 0
    }

    // ---------------- AAC helpers ----------------

    data class AacConfig(val audioObjectType: Int, val sampleRate: Int, val channelCount: Int)

    private fun parseAacAsc(asc: ByteArray): AacConfig {
        require(asc.size >= 2) { "ASC too short" }
        val b0 = asc[0].toInt() and 0xFF
        val b1 = asc[1].toInt() and 0xFF

        val aot = (b0 shr 3) and 0x1F
        val sfIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
        val chCfg = (b1 shr 3) and 0x0F

        val sr = when (sfIndex) {
            0 -> 96000; 1 -> 88200; 2 -> 64000; 3 -> 48000; 4 -> 44100; 5 -> 32000
            6 -> 24000; 7 -> 22050; 8 -> 16000; 9 -> 12000; 10 -> 11025; 11 -> 8000; 12 -> 7350
            else -> error("Unsupported samplingFreqIndex=$sfIndex")
        }
        return AacConfig(aot, sr, chCfg)
    }

    private fun looksLikeAdts(b: ByteArray): Boolean {
        if (b.size < 2) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 0xFF && (b1 and 0xF0) == 0xF0
    }

    private fun buildAscFromAdtsHeader(adts: ByteArray): ByteArray {
        val profile = ((adts[2].toInt() shr 6) and 0x03) + 1
        val sfIndex = (adts[2].toInt() shr 2) and 0x0F
        val chCfg = ((adts[2].toInt() and 0x01) shl 2) or ((adts[3].toInt() shr 6) and 0x03)
        val b1 = (profile shl 3) or (sfIndex shr 1)
        val b2 = ((sfIndex and 1) shl 7) or (chCfg shl 3)
        return byteArrayOf(b1.toByte(), b2.toByte())
    }

    private fun stripAdts(adtsFrame: ByteArray): ByteArray {
        if (adtsFrame.size < 7) return adtsFrame
        val protectionAbsent = (adtsFrame[1].toInt() and 0x01) == 1
        val headerLen = if (protectionAbsent) 7 else 9
        if (adtsFrame.size <= headerLen) return ByteArray(0)
        return adtsFrame.copyOfRange(headerLen, adtsFrame.size)
    }
}
