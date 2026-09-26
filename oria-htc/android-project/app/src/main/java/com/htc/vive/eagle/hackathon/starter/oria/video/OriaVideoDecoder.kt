package com.htc.vive.eagle.hackathon.starter.oria.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One codec thread, no playback clock or preview Surface, no inference inside SDK callbacks. */
class OriaVideoDecoder(
    private val sessionId: Long,
    private val rotationDegrees: Int,
    private val mirrored: Boolean,
    private val onFrame: (OriaVideoFrame) -> Boolean,
    private val onStatus: (OriaVideoStatus) -> Unit,
    private val onFatalError: (String) -> Unit,
    private val sampleIntervalMs: Long = 250,
    private val maxBitmapSide: Int = 832,
) : AutoCloseable {
    private data class Packet(val bytes: ByteArray, val ptsUs: Long, val flags: Int, val receivedAtMs: Long)
    private val thread = HandlerThread("OriaVideoCodec").apply { start() }
    private val handler = Handler(thread.looper)
    private val active = AtomicBoolean(true)
    private val queueLock = Any()
    private val deliveryLock = Any()
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0
    private val receivedPackets = AtomicLong()
    private val latestReception = AtomicLong()
    private val receptionIndex = FrameReceptionIndex()
    private var codec: MediaCodec? = null
    private var codecName: String? = null
    private var outputFormat: MediaFormat? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var decodedFrames = 0L
    private var deliveredFrames = 0L
    private var lastDecodedAtMs: Long? = null
    private var lastSampleAtMs = Long.MIN_VALUE / 2
    private var lastStatusAtMs = 0L
    private var staleBeforeConversion = 0L
    private var staleAfterConversion = 0L
    private var lastConversionMs = 0.0


    /** Copies borrowed SDK data synchronously, then returns. Overflow fails the stream, never drops a NAL. */
    fun submit(buffer: ByteBuffer, info: MediaCodec.BufferInfo, receivedAtMs: Long = SystemClock.elapsedRealtime()) {
        if (!active.get() || info.size <= 0) return
        if (info.offset < 0 || info.size > 4 * 1024 * 1024 || info.offset.toLong() + info.size > buffer.limit()) {
            handler.post { fail("Invalid encoded buffer bounds") }
            return
        }
        val bytes = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size); get(bytes) }
        val packet = Packet(bytes, info.presentationTimeUs, info.flags, receivedAtMs)
        val accepted = synchronized(queueLock) {
            if (!active.get()) return@synchronized false
            if (queue.size >= 96 || queuedBytes + bytes.size > 8 * 1024 * 1024) false
            else { queue.addLast(packet); queuedBytes += bytes.size; true }
        }
        if (!accepted) {
            handler.post { fail("Encoded backlog exceeded; restart stream to recover reference frames") }
            return
        }
        receivedPackets.incrementAndGet()
        latestReception.set(receivedAtMs)
    }

    private val pump = object : Runnable {
        override fun run() {
            if (!active.get()) return
            try {
                var fed = 0
                while (active.get() && fed < 8) {
                    val packet = synchronized(queueLock) { queue.peekFirst() } ?: break
                    check(SystemClock.elapsedRealtime() - packet.receivedAtMs <= 1000) { "Encoded backlog older than 1000 ms" }
                    val nals = H264Parameters.nals(packet.bytes)
                    check(nals.isNotEmpty()) { "Video input is not a complete Annex-B packet" }
                    for (nal in nals) {
                        when (nal[0].toInt() and 31) {
                            7 -> {
                                check(sps == null || sps!!.contentEquals(nal)) { "Stream SPS changed; restart required" }
                                sps = nal
                            }
                            8 -> {
                                check(pps == null || pps!!.contentEquals(nal)) { "Stream PPS changed; restart required" }
                                pps = nal
                            }
                        }
                    }
                    if (codec == null && sps != null && pps != null) configure()
                    val hasPicture = nals.any { (it[0].toInt() and 31) in 1..5 }
                    if (hasPicture) {
                        val c = checkNotNull(codec) { "Picture received before SPS/PPS" }
                        drain(c)
                        val inputIndex = c.dequeueInputBuffer(0)
                        if (inputIndex < 0) break // Keep this exact packet until the codec can accept it.
                        val input = checkNotNull(c.getInputBuffer(inputIndex))
                        check(input.capacity() >= packet.bytes.size) { "Encoded packet exceeds codec input capacity" }
                        input.clear()
                        input.put(packet.bytes)
                        check(receptionIndex.record(packet.ptsUs, packet.receivedAtMs)) { "Too many uncorrelated codec frames" }
                        c.queueInputBuffer(inputIndex, 0, packet.bytes.size, packet.ptsUs,
                            packet.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv())
                    }
                    synchronized(queueLock) {
                        // close() may already have cleared the queue from another thread.
                        if (queue.peekFirst() === packet) { queue.removeFirst(); queuedBytes -= packet.bytes.size }
                    }
                    fed++
                }
                codec?.let { drain(it) }
                val now = SystemClock.elapsedRealtime()
                if (now - lastStatusAtMs >= 500) { publish(if (codec == null) "waiting_for_config" else "decoding"); lastStatusAtMs = now }
            } catch (error: Throwable) {
                fail("${error.javaClass.simpleName}: ${error.message}")
            }
            if (active.get()) handler.postDelayed(this, 4)
        }
    }

    init {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(sampleIntervalMs >= 0 && maxBitmapSide in 1..4096)
        publish("waiting_for_config")
        handler.post(pump)
    }


    private fun configure() {
        val config = checkNotNull(H264Parameters.parseSps(checkNotNull(sps)))
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps!!))
            setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps!!))
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        codecName = c.name
        c.configure(format, null, null, 0)
        c.start()
        outputFormat = format
        publish("decoding", "${config.width} × ${config.height}; orientation configured=$rotationDegrees; uncalibrated until mire")
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (active.get()) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                        val receivedAtMs = checkNotNull(receptionIndex.take(info.presentationTimeUs)) {
                            "Decoded frame has no exact PTS reception correlation (${info.presentationTimeUs})"
                        }
                        decodedFrames++
                        val now = SystemClock.elapsedRealtime()
                        lastDecodedAtMs = now
                        if (now - receivedAtMs > 500) { staleBeforeConversion++; continue }
                        if (now - lastSampleAtMs < sampleIntervalMs) continue
                        val image = checkNotNull(c.getOutputImage(index)) { "Codec exposes no YUV Image in buffer mode" }
                        try {
                            val conversionStartedNs = SystemClock.elapsedRealtimeNanos()
                            val bitmap = YuvPixels.toBitmap(image, outputFormat ?: c.outputFormat, maxBitmapSide, rotationDegrees, mirrored)
                            lastConversionMs = (SystemClock.elapsedRealtimeNanos() - conversionStartedNs) / 1_000_000.0
                            var transferred = false
                            try {
                                synchronized(deliveryLock) {
                                    val deliveredAtMs = SystemClock.elapsedRealtime()
                                    if (deliveredAtMs - receivedAtMs > 500) staleAfterConversion++
                                    else if (active.get()) transferred = onFrame(OriaVideoFrame(bitmap, sessionId, decodedFrames,
                                        receivedAtMs, info.presentationTimeUs, image.cropRect.width(), image.cropRect.height(),
                                        rotationDegrees, mirrored, deliveredAtMs, lastConversionMs))
                                }
                                if (transferred) deliveredFrames++
                            } finally {
                                if (!transferred && !bitmap.isRecycled) bitmap.recycle()
                            }
                            lastSampleAtMs = now
                        } finally { image.close() }
                    } finally { c.releaseOutputBuffer(index, false) }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = c.outputFormat
                    publish("decoding", "Output format: ${c.outputFormat}")
                }
                else -> return
            }
        }
    }

    private fun publish(phase: String, detail: String? = null) {
        onStatus(OriaVideoStatus(sessionId, phase, receivedPackets.get(), decodedFrames, deliveredFrames,
            latestReception.get().takeIf { it > 0 }, lastDecodedAtMs, codecName, detail,
            staleBeforeConversion, staleAfterConversion, lastConversionMs))
    }

    private fun fail(message: String) {
        if (!active.compareAndSet(true, false)) return
        synchronized(queueLock) { queue.clear(); queuedBytes = 0 }
        publish("error", message)
        releaseCodec()
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        onFatalError(message)
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) { }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        receptionIndex.clear()
    }

    override fun close() {
        synchronized(deliveryLock) {
            if (!active.getAndSet(false)) return
        }
        synchronized(queueLock) { queue.clear(); queuedBytes = 0 }
        handler.removeCallbacksAndMessages(null)
        handler.post { releaseCodec(); publish("stopped"); thread.quitSafely() }
    }
}
