package com.htc.vive.eagle.hackathon.starter.util

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference

class H264Decoder(
    private val surface: Surface,
) {
    // ---- Log ----
    private val log = Logger.instance
    private val logTag: String = "H264Decoder"

    // ---- State machine ----
    private enum class State { IDLE, STARTING, EXECUTING, STOPPING }
    private val state = AtomicReference(State.IDLE)

    // ---- Codec ----
    private var codec: MediaCodec? = null
    private val outInfo = MediaCodec.BufferInfo()

    // ---- Threading  ----
    private val thread = HandlerThread("H264DecoderThread").apply { start() }
    private val h = Handler(thread.looper)
    private val queue = ArrayBlockingQueue<StreamingPlayer.EncodedFrame>(2000)
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var activeGen = 0L

    // ---- Playback timing ----
    @Volatile private var playbackEnabled = false
    @Volatile private var clock: StreamingPlayer.ClockUs? = null

    // ---- Stream config (extracted from stream) ----
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codecStarted = false
    private val preConfigQueue = ArrayBlockingQueue<StreamingPlayer.EncodedFrame>(300)


    // Public ------------------------------------------------------------

    fun start() {
        val prev = state.getAndSet(State.STARTING)
        if (prev == State.EXECUTING || prev == State.STARTING) return

        val gen = generation.incrementAndGet()
        activeGen = gen

        h.post {

            if (activeGen != gen) return@post
            try {
                resetTimingLocked()
                clearConfigLocked("start")

                state.set(State.EXECUTING)
                h.removeCallbacks(decodeLoopRunnable)
                h.post(decodeLoopRunnable)

            } catch (t: Throwable) {
                log.e(logTag, "start() failed: ${t.message}", t)
                safeReleaseLocked()
                state.set(State.IDLE)
            }
        }
    }

    fun stop() {

        val gen = generation.incrementAndGet()
        activeGen = gen

        h.post {
            if (activeGen != gen) return@post

            state.set(State.STOPPING)
            clearConfigLocked("stop")
            state.set(State.IDLE)

            log.w(logTag, "stop complete gen=$gen")
        }
    }
    
    fun isCodecConfigured() : Boolean
    {
        return codecStarted
    }

    fun getFrameRatio() : Float
    {
        if(!codecStarted) return 1.8f
        val c = codec ?: return 1.8f
        val ratio = (c.outputFormat.getInteger("width")*1f) / (c.outputFormat.getInteger("height") * 1f)
        return ratio
    }

    fun setClock(clock: StreamingPlayer.ClockUs) { this.clock = clock }
    fun setPlaybackEnabled(enabled: Boolean) { playbackEnabled = enabled }

    fun submit(frame: StreamingPlayer.EncodedFrame) {
        val s = state.get()
        if (s != State.EXECUTING && s != State.STARTING) return

        if (!preConfigQueue.offer(frame)) {
            preConfigQueue.poll()
            preConfigQueue.offer(frame)
        }

        val gen = activeGen
        h.post {
            if (activeGen != gen) return@post
            if (state.get() != State.EXECUTING) return@post

            if (!codecStarted) {
                extractSpsPpsFromAnnexB(frame.data)
                tryConfigureCodecLocked()
            }
        }
    }

    fun releaseForever() {
        h.post {
            state.set(State.STOPPING)

            playbackEnabled = false
            clock = null

            queue.clear()
            resetTimingLocked()
            safeReleaseLocked()
            state.set(State.IDLE)
            thread.quitSafely()
        }
    }

    // Internal helpers ------------------------------------------------------

    private fun extractSpsPpsFromAnnexB(packet: ByteArray) {
        log.w(logTag,"submit()  state = [$state], packet len :[${packet.size}]")
        val nals = splitAnnexBNals(packet)
        for (nalWithStartCode in nals) {
            val sc = startCodeLen(nalWithStartCode)
            if (nalWithStartCode.size <= sc) continue

            // NAL starts at header byte (e.g. 0x67, 0x68, 0x65...)
            val nalu = nalWithStartCode.copyOfRange(sc, nalWithStartCode.size)

            val nalType = (nalu[0].toInt() and 0x1F)
            when (nalType) {
                7 -> if (sps == null) {
                    sps = nalu
                    log.w(logTag, "Found SPS (no startcode) len=${nalu.size} head=${
                        nalu.take(8).joinToString(" ") { "%02X".format(it) }
                    }")
                }
                8 -> if (pps == null) {
                    pps = nalu
                    log.w(logTag, "Found PPS (no startcode) len=${nalu.size} head=${
                        nalu.take(8).joinToString(" ") { "%02X".format(it) }
                    }")
                }
            }
        }
    }


    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val is3 = data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()
            val is4 = i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            if (is3 || is4) {
                starts.add(i)
                i += if (is4) 4 else 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()

        val out = ArrayList<ByteArray>(starts.size)
        for (s in starts.indices) {
            val start = starts[s]
            val end = if (s + 1 < starts.size) starts[s + 1] else data.size
            out.add(data.copyOfRange(start, end))
        }
        return out
    }

    private fun startCodeLen(nal: ByteArray): Int {
        return if (nal.size >= 4 &&
            nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()
        ) 4 else 3
    }

    private val decodeLoopRunnable = object : Runnable {
        override fun run() {
            if (state.get() != State.EXECUTING) return
            if (!codecStarted) {
                h.postDelayed(this, 5)
                return
            }

            val c = codec ?: run {
                state.set(State.IDLE)
                return
            }

            var moved = 0
            while (moved < 50) {
                val f = preConfigQueue.poll() ?: break
                queue.offer(f)
                moved++
            }

            try {
                val clk = clock
                if (!playbackEnabled || clk == null || clk.nowUs() == 0L) {
                    log.d(logTag, "Drop frame playbackEnable[$playbackEnabled] , clock[${clk==null}] , nowUs[${clk?.nowUs()}]")

                    drainOutputLocked(c)
                    h.postDelayed(this, 5)
                    return
                }

                var fed = 0
                val maxFeed = 8
                while (fed < maxFeed) {
                    val f = queue.poll() ?: break
                    feedInputFrameLocked(c, f)
                    fed++
                }

                drainOutputLocked(c)
                h.postDelayed(this, if (fed == 0) 2 else 0)

            } catch (t: Throwable) {
                log.e(logTag, "decode loop failed: ${t.message}", t)
                safeReleaseLocked()
                resetTimingLocked()
                state.set(State.IDLE)
            }
        }
    }

    private fun tryConfigureCodecLocked() {
        if (codecStarted) return
        val spsLocal = sps ?: return
        val ppsLocal = pps ?: return

        val spsInfo = parseSps(spsLocal)
        if (spsInfo == null) {
            log.e(logTag, "SPS parse failed. SPS head=${spsLocal.take(16).joinToString(" ") { "%02X".format(it) }}")
            return
        }

        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        fun withStartCode(nalu: ByteArray): ByteArray =
            byteArrayOf(0, 0, 0, 1) + nalu

        val format = MediaFormat.createVideoFormat(mime, spsInfo.width, spsInfo.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(spsLocal)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(ppsLocal)))

        val c = MediaCodec.createDecoderByType(mime)
        codec = c
        c.configure(format, surface, null, 0)
        c.start()

        codecStarted = true
        log.w(logTag, "Codec configured from stream SPS/PPS. width=${spsInfo.width} height=${spsInfo.height} sps=${spsLocal.size} pps=${ppsLocal.size}")
    }

    private fun clearConfigLocked(reason: String) {
        log.w(logTag, "clearConfig reason=$reason gen=$activeGen thread=${Thread.currentThread().name}")
        sps = null
        pps = null
        codecStarted = false
        preConfigQueue.clear()
        queue.clear()
        resetTimingLocked()
        safeReleaseLocked()
    }

    private fun feedInputFrameLocked(c: MediaCodec, frame: StreamingPlayer.EncodedFrame) {
        val inIndex = c.dequeueInputBuffer(0)
        if (inIndex < 0) return


        val inBuf = c.getInputBuffer(inIndex) ?: run {
            c.queueInputBuffer(inIndex, 0, 0, frame.ptsUs, 0)
            return
        }

        inBuf.clear()
        inBuf.put(frame.data)
        c.queueInputBuffer(inIndex, 0, frame.data.size, frame.ptsUs, 0)

    }

    private fun drainOutputLocked(c: MediaCodec) {
        while (true) {
            val outIndex = c.dequeueOutputBuffer(outInfo, 0)
            when {
                outIndex >= 0 -> {
                    c.releaseOutputBuffer(outIndex, true)
                    //val ptsUs = outInfo.presentationTimeUs
                    //renderWithClockLocked(c, outIndex, ptsUs)
                    if ((outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {  val fmt = c.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    log.w(logTag, "OUTPUT_FORMAT_CHANGED width=$w height=$h fmt=$fmt")
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    private fun safeReleaseLocked() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    // ---- Clock-based render mapping ----
    private var basePtsUs: Long = Long.MIN_VALUE
    private var baseTimeUs: Long = 0L

    private fun resetTimingLocked() {
        basePtsUs = Long.MIN_VALUE
        baseTimeUs = 0L
    }

    private fun renderWithClockLocked(c: MediaCodec, outIndex: Int, ptsUs: Long) {
        val clk = clock ?: run {
            c.releaseOutputBuffer(outIndex, false)
            return
        }
        val nowUs = clk.nowUs()
        if (nowUs == 0L) {
            c.releaseOutputBuffer(outIndex, false)
            return
        }

        if (basePtsUs == Long.MIN_VALUE) {
            basePtsUs = ptsUs
            baseTimeUs = nowUs
        }

        val deltaUs = ptsUs - clk.nowUs()
        var renderNs = 0L
        log.w(logTag, "renderWithClockLocked deltaUs= [$deltaUs]")
        when
        {
            deltaUs >0 ->{
                renderNs = System.nanoTime() + deltaUs* 1000
                c.releaseOutputBuffer(outIndex, renderNs)
            }
            deltaUs < -100_000L ->{
                c.releaseOutputBuffer(outIndex, false)
            }
            else ->{
                c.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    data class SpsInfo(
        val width: Int,
        val height: Int,
        val profileIdc: Int,
        val levelIdc: Int
    )

    fun parseSps(spsNal: ByteArray): SpsInfo? {
        // spsNal is NAL payload starting at NAL header byte (type=7)
        if (spsNal.isEmpty()) return null
        val nalType = (spsNal[0].toInt() and 0x1F)
        if (nalType != 7) return null

        // Remove NAL header (1 byte) and convert EBSP->RBSP (remove emulation prevention 0x03)
        val rbsp = ebspToRbsp(spsNal.copyOfRange(1, spsNal.size))

        val br = BitReader(rbsp)

        val profileIdc = br.readBits(8)
        br.readBits(8) // constraint flags + reserved
        val levelIdc = br.readBits(8)
        br.readUE()

        var chromaFormatIdc = 1
        if (profileIdc in setOf(100,110,122,244,44,83,86,118,128,138,139,134,135)) {
            chromaFormatIdc = br.readUE()
            if (chromaFormatIdc == 3) br.readBits(1)
            br.readUE()
            br.readUE()
            br.readBits(1)
            val seqScalingMatrixPresent = br.readBits(1) == 1
            if (seqScalingMatrixPresent) {
                val count = if (chromaFormatIdc != 3) 8 else 12
                repeat(count) {
                    val present = br.readBits(1) == 1
                    if (present) skipScalingList(br, if (it < 6) 16 else 64)
                }
            }
        }

        br.readUE()
        val picOrderCntType = br.readUE()
        if (picOrderCntType == 0) {
            br.readUE()
        } else if (picOrderCntType == 1) {
            br.readBits(1)
            br.readSE()
            br.readSE()
            val numRefFramesInCycle = br.readUE()
            repeat(numRefFramesInCycle) { br.readSE() }
        }

        br.readUE()
        br.readBits(1)

        val picWidthInMbsMinus1 = br.readUE()
        val picHeightInMapUnitsMinus1 = br.readUE()
        val frameMbsOnlyFlag = br.readBits(1) == 1
        if (!frameMbsOnlyFlag) br.readBits(1)

        br.readBits(1)

        var frameCropLeft = 0
        var frameCropRight = 0
        var frameCropTop = 0
        var frameCropBottom = 0
        val frameCroppingFlag = br.readBits(1) == 1
        if (frameCroppingFlag) {
            frameCropLeft = br.readUE()
            frameCropRight = br.readUE()
            frameCropTop = br.readUE()
            frameCropBottom = br.readUE()
        }

        val widthMbs = picWidthInMbsMinus1 + 1
        val heightMapUnits = picHeightInMapUnitsMinus1 + 1
        val frameHeightMbs = (2 - if (frameMbsOnlyFlag) 1 else 0) * heightMapUnits

        var width = widthMbs * 16
        var height = frameHeightMbs * 16

        val cropUnitX: Int
        val cropUnitY: Int
        when (chromaFormatIdc) {
            0 -> { // monochrome
                cropUnitX = 1
                cropUnitY = 2 - if (frameMbsOnlyFlag) 1 else 0
            }
            1 -> { // 4:2:0
                cropUnitX = 2
                cropUnitY = 2 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
            2 -> { // 4:2:2
                cropUnitX = 2
                cropUnitY = 1 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
            else -> { // 4:4:4
                cropUnitX = 1
                cropUnitY = 1 * (2 - if (frameMbsOnlyFlag) 1 else 0)
            }
        }

        if (frameCroppingFlag) {
            width -= (frameCropLeft + frameCropRight) * cropUnitX
            height -= (frameCropTop + frameCropBottom) * cropUnitY
        }

        return SpsInfo(width, height, profileIdc, levelIdc)
    }

    private fun ebspToRbsp(ebsp: ByteArray): ByteArray {
        val out = ByteArray(ebsp.size)
        var outLen = 0
        var zeros = 0
        for (b in ebsp) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v == 0x03) {
                // skip emulation prevention byte
                zeros = 0
                continue
            }
            out[outLen++] = b
            zeros = if (v == 0x00) zeros + 1 else 0
        }
        return out.copyOf(outLen)
    }

    private fun skipScalingList(br: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val delta = br.readSE()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) {
                if (bytePos >= data.size) return v
                val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
                v = (v shl 1) or bit
                bitPos++
                if (bitPos == 8) { bitPos = 0; bytePos++ }
            }
            return v
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBits(1) == 0 && bytePos < data.size) zeros++
            var value = 1
            repeat(zeros) { value = (value shl 1) or readBits(1) }
            return value - 1
        }

        fun readSE(): Int {
            val ue = readUE()
            val sign = if (ue and 1 == 0) -1 else 1
            return sign * ((ue + 1) / 2)
        }
    }
}
