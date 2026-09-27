package com.htc.vive.eagle.hackathon.starter.oria.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.vive.eagle.hackathon.starter.oria.core.DepthObstacleGeometry
import com.htc.vive.eagle.hackathon.starter.oria.core.DepthObstaclePolicy
import com.htc.vive.eagle.hackathon.starter.oria.ml.OnnxDepthDetector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded phone-local replay. No HTC radio, live glasses, microphone or voice API is called. */
@RunWith(AndroidJUnit4::class)
class DepthPipelineLatencyInstrumentedTest {
    private data class Attempt(
        val frameId: Long, val ptsUs: Long, val receivedAtMs: Long, val deliveredAtMs: Long,
        val workerStartedAtMs: Long, val resultAtMs: Long, val conversionMs: Double,
        val preprocessMs: Double?, val inferenceMs: Double?, val postprocessMs: Double?,
        val geometryMs: Double?, val workerServiceMs: Double,
        val status: String, val reason: String, val qualityUsable: Boolean?, val proposalZone: String?,
    ) {
        fun json() = JSONObject().put("frameId", frameId).put("ptsUs", ptsUs)
            .put("receivedAtMs", receivedAtMs).put("deliveredAtMs", deliveredAtMs)
            .put("workerStartedAtMs", workerStartedAtMs).put("resultAtMs", resultAtMs)
            .put("receiveToDeliveredMs", deliveredAtMs - receivedAtMs).put("conversionMs", conversionMs)
            .put("queueMs", workerStartedAtMs - deliveredAtMs).put("resultAgeMs", resultAtMs - receivedAtMs)
            .put("preprocessMs", preprocessMs ?: JSONObject.NULL).put("inferenceMs", inferenceMs ?: JSONObject.NULL)
            .put("postprocessMs", postprocessMs ?: JSONObject.NULL).put("geometryMs", geometryMs ?: JSONObject.NULL)
            .put("workerServiceMs", workerServiceMs).put("status", status).put("reason", reason)
            .put("qualityUsable", qualityUsable ?: JSONObject.NULL).put("proposalZone", proposalZone ?: JSONObject.NULL)
    }

    @Test fun compareFixedAndDemandSamplingWithoutRadioOrVoice() {
        val args = InstrumentationRegistry.getArguments()
        val modes = (args.getString("depthSampling") ?: "fixed,on_demand").split(',').map { it.trim() }
        require(modes.isNotEmpty() && modes.distinct().size == modes.size && modes.all { it in setOf("fixed", "on_demand") })
        val seconds = (args.getString("depthPipelineSeconds")?.toLongOrNull() ?: 8).coerceIn(2, 8)
        val threads = (args.getString("depthThreads")?.toIntOrNull() ?: 2).also { require(it in 1..8) }
        val fixedIntervalMs = (args.getString("depthFixedIntervalMs")?.toLongOrNull() ?: 333L).coerceIn(33L, 1_000L)
        for (mode in modes) runVariant(mode, seconds, threads, fixedIntervalMs)
    }

    private fun runVariant(mode: String, seconds: Long, threads: Int, fixedIntervalMs: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resource = context.resources.getIdentifier("video_sample", "raw", context.packageName)
        assertTrue("Bundled simulator video exists", resource != 0)
        val variantStarted = now()
        val deadline = variantStarted + 30_000
        val session = if (mode == "fixed") 701L else 702L
        val running = AtomicBoolean(true)
        val currentSession = AtomicLong(session)
        val credit = AtomicBoolean(true)
        val latest = AtomicReference<OriaVideoFrame?>(null)
        val sent = AtomicLong()
        val dropped = AtomicLong()
        val rejectedOffers = AtomicLong()
        val invalidatedAfterStop = AtomicLong()
        val error = AtomicReference<String?>(null)
        val decisionLock = Any()
        val videoStatus = AtomicReference(OriaVideoStatus())
        val attempts = CopyOnWriteArrayList<Attempt>()
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val decoderStopped = CountDownLatch(1)
        val feedOffsets = CopyOnWriteArrayList<Long>()
        val rowsAtStop = AtomicLong(-1)
        var decoder: OriaVideoDecoder? = null
        var extractor: MediaExtractor? = null
        var feedStarted = 0L
        var feedEnded = 0L
        var fedPackets = 0
        var completed = false
        var extractorReleased = false
        val modelClosed = AtomicBoolean(false)

        fun remainingMs() = (deadline - now()).coerceAtLeast(0)
        fun saveReport() {
            val video = videoStatus.get()
            val hardware = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.name == video.codecName }?.isHardwareAccelerated
            val valid = attempts.filter { it.status !in setOf("rejected", "invalidated_after_stop") }
            val report = JSONObject().put("schemaVersion", 1)
                .put("source", "bundled_htc_mp4_phone_local_replay")
                .put("sdkRadio", false).put("physicalGlasses", false).put("actualAudio", false)
                .put("voiceRequestsSubmitted", 0).put("policyMeaning", "intentions only; no playback acknowledgement or cooldown consumption")
                .put("samplingMode", mode).put("sampleIntervalMs", if (mode == "fixed") fixedIntervalMs else 0L)
                .put("configuredFixedIntervalMs", fixedIntervalMs)
                .put("modelSha256", OnnxDepthDetector.MODEL_SHA256).put("modelInputSize", 252)
                .put("provider", "CPU").put("threads", threads).put("requestedFeedSeconds", seconds)
                .put("maximumVideoPackets", 240).put("variantDeadlineMs", 30_000)
                .put("variantElapsedMs", now() - variantStarted)
                .put("feedElapsedMs", if (feedStarted > 0) feedEnded - feedStarted else 0)
                .put("inputVideoPackets", fedPackets).put("codec", video.codecName ?: JSONObject.NULL)
                .put("codecHardwareAccelerated", hardware ?: JSONObject.NULL)
                .put("receivedPackets", video.receivedPackets).put("decodedFrames", video.decodedFrames)
                .put("sentBitmaps", sent.get()).put("droppedLatestSlotBitmaps", dropped.get())
                .put("rejectedBitmapOffers", rejectedOffers.get()).put("processed", attempts.size)
                .put("inferred", attempts.count { it.inferenceMs != null })
                .put("staleBeforeInference", attempts.count { it.reason == "stale_before_inference" })
                .put("staleAfterInference", attempts.count { it.reason == "stale_observation" })
                .put("decoderStaleBeforeConversion", video.staleBeforeConversion)
                .put("decoderStaleAfterConversion", video.staleAfterConversion)
                .put("invalidatedAfterStop", invalidatedAfterStop.get())
                .put("freshEvaluations", valid.size).put("error", error.get() ?: JSONObject.NULL)
                .put("completed", completed).put("workerClosed", modelClosed.get())
                .put("workerStopped", finished.count == 0L).put("decoderStopped", decoderStopped.count == 0L)
                .put("extractorReleased", extractorReleased).put("allBitmapsReleased", latest.get() == null && finished.count == 0L)
                .put("feedScheduleDelayMs", distribution(feedOffsets.map { it.toDouble() }))
                .put("receiveToDeliveredMs", distribution(attempts.map { (it.deliveredAtMs - it.receivedAtMs).toDouble() }))
                .put("conversionMs", distribution(attempts.map { it.conversionMs }))
                .put("queueMs", distribution(attempts.map { (it.workerStartedAtMs - it.deliveredAtMs).toDouble() }))
                .put("preprocessMs", distribution(attempts.mapNotNull { it.preprocessMs }))
                .put("inferenceMs", distribution(attempts.mapNotNull { it.inferenceMs }))
                .put("postprocessMs", distribution(attempts.mapNotNull { it.postprocessMs }))
                .put("geometryMs", distribution(attempts.mapNotNull { it.geometryMs }))
                .put("workerServiceMs", distribution(attempts.map { it.workerServiceMs }))
                .put("resultAgeMs", distribution(attempts.map { (it.resultAtMs - it.receivedAtMs).toDouble() }))
                .put("freshResultAgeMs", distribution(valid.map { (it.resultAtMs - it.receivedAtMs).toDouble() }))
                .put("frames", JSONArray().apply { attempts.forEach { put(it.json()) } })
            val directory = context.filesDir.resolve("depth_validation").apply { check(isDirectory || mkdirs()) }
            directory.resolve("pipeline_$mode.json").writeText(report.toString(2))
            Log.i("OriaDepthPipeline", "mode=$mode packets=$fedPackets processed=${attempts.size} error=${error.get()}")
        }

        val worker = Thread({
            var detector: OnnxDepthDetector? = null
            val policy = DepthObstaclePolicy()
            var observationIndex = 0L
            try {
                detector = OnnxDepthDetector(context, useXnnpack = false, numThreads = threads)
                policy.start(session, now())
                ready.countDown()
                while (running.get() && now() < deadline) {
                    val frame = latest.getAndSet(null)
                    if (frame == null) { Thread.sleep(2); continue }
                    val workStartedNs = SystemClock.elapsedRealtimeNanos()
                    val workStartedMs = now()
                    try {
                        if (!running.get() || currentSession.get() != frame.sessionId) {
                            invalidatedAfterStop.incrementAndGet(); continue
                        }
                        if (workStartedMs - frame.receivedAtMs !in 0..500) {
                            policy.evaluate(session, observationIndex++, frame.receivedAtMs, null, workStartedMs)
                            attempts += Attempt(frame.frameId, frame.ptsUs, frame.receivedAtMs, frame.deliveredAtMs,
                                workStartedMs, workStartedMs, frame.conversionMs, null, null, null, null,
                                elapsedMs(workStartedNs), "rejected", "stale_before_inference", null, null)
                            continue
                        }
                        val inference = detector.detect(frame.bitmap)
                        val geometryStartedNs = SystemClock.elapsedRealtimeNanos()
                        val geometry = DepthObstacleGeometry.compute(if (inference.available) inference.values else null)
                        val geometryMs = elapsedMs(geometryStartedNs)
                        val resultAt = now()
                        synchronized(decisionLock) {
                            if (!running.get() || currentSession.get() != frame.sessionId) {
                                invalidatedAfterStop.incrementAndGet()
                                attempts += Attempt(frame.frameId, frame.ptsUs, frame.receivedAtMs, frame.deliveredAtMs,
                                    workStartedMs, resultAt, frame.conversionMs, inference.preprocessMs, inference.inferenceMs,
                                    inference.postprocessMs, geometryMs, elapsedMs(workStartedNs), "invalidated_after_stop",
                                    "session_stopped", inference.qualityUsable, null)
                            } else {
                                val decision = policy.evaluate(session, observationIndex++, frame.receivedAtMs,
                                    geometry.zones, resultAt, inference.qualityUsable, inference.qualityReason)
                                attempts += Attempt(frame.frameId, frame.ptsUs, frame.receivedAtMs, frame.deliveredAtMs,
                                    workStartedMs, resultAt, frame.conversionMs, inference.preprocessMs, inference.inferenceMs,
                                    inference.postprocessMs, geometryMs, elapsedMs(workStartedNs), decision.status,
                                    decision.reason, inference.qualityUsable, decision.eligibleAlert?.zone?.name)
                            }
                        }
                    } finally {
                        frame.bitmap.recycle()
                        if (running.get() && currentSession.get() == session) credit.set(true)
                    }
                }
            } catch (failure: Throwable) {
                error.compareAndSet(null, "Worker ${failure.javaClass.simpleName}: ${failure.message}")
                running.set(false)
            } finally {
                ready.countDown()
                policy.stop()
                try { detector?.close(); modelClosed.set(true) }
                catch (failure: Throwable) { error.compareAndSet(null, "Detector close: ${failure.message}") }
                finished.countDown()
            }
        }, "OriaDepthPipelineInference").apply { start() }

        try {
            check(ready.await(minOf(15_000, remainingMs()), TimeUnit.MILLISECONDS)) { "Model initialization deadline" }
            check(error.get() == null) { error.get() ?: "Worker initialization failed" }
            val inputExtractor = MediaExtractor().also { extractor = it }
            context.resources.openRawResourceFd(resource).use { fd ->
                inputExtractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            val track = (0 until inputExtractor.trackCount).first {
                inputExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            inputExtractor.selectTrack(track)
            val format = inputExtractor.getTrackFormat(track)
            val firstPts = inputExtractor.sampleTime
            val cycleDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 6_433_333L
            val activeDecoder = OriaVideoDecoder(session, 0, false,
                onFrame = { frame ->
                    if (!running.get() || currentSession.get() != frame.sessionId) false
                    else if (mode == "on_demand" && !credit.compareAndSet(true, false)) {
                        rejectedOffers.incrementAndGet(); false
                    } else {
                        latest.getAndSet(frame)?.let { dropped.incrementAndGet(); it.bitmap.recycle() }
                        sent.incrementAndGet()
                        true
                    }
                }, onStatus = { status ->
                    videoStatus.set(status)
                    if (status.phase in setOf("stopped", "error")) decoderStopped.countDown()
                }, onFatalError = { message -> error.compareAndSet(null, "Decoder: $message"); running.set(false) },
                sampleIntervalMs = if (mode == "fixed") fixedIntervalMs else 0L,
                shouldSampleFrame = { running.get() && currentSession.get() == session && (mode == "fixed" || credit.get()) })
                .also { decoder = it }
            for (key in listOf("csd-0", "csd-1")) format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                activeDecoder.submit(ByteBuffer.wrap(bytes), MediaCodec.BufferInfo().apply {
                    set(0, bytes.size, -1, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                })
            }
            val input = ByteBuffer.allocate(4 * 1024 * 1024)
            var cycleBasePts = 0L
            var maxCyclePts = 0L
            feedStarted = now()
            val feedDeadline = minOf(feedStarted + seconds * 1000, deadline - 2_000)
            while (running.get() && error.get() == null && fedPackets < 240 && now() < feedDeadline) {
                if (inputExtractor.sampleTime < 0) {
                    cycleBasePts += maxOf(cycleDurationUs, maxCyclePts + 33_333)
                    maxCyclePts = 0
                    inputExtractor.seekTo(firstPts, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                check(inputExtractor.sampleTime >= 0) { "Replay cannot seek to its first keyframe" }
                val sourcePts = inputExtractor.sampleTime - firstPts
                maxCyclePts = maxOf(maxCyclePts, sourcePts)
                val pts = cycleBasePts + sourcePts
                val due = feedStarted + pts / 1000
                while (running.get() && now() < minOf(due, feedDeadline)) Thread.sleep((minOf(due, feedDeadline) - now()).coerceIn(1, 20))
                if (!running.get() || now() >= feedDeadline) break
                input.clear()
                val size = inputExtractor.readSampleData(input, 0)
                check(size in 1..input.capacity()) { "Invalid replay sample size $size" }
                val bytes = ByteArray(size).also { input.position(0); input.get(it) }
                val packet = normalizeAnnexB(bytes)
                val receivedAt = now()
                feedOffsets += receivedAt - due
                activeDecoder.submit(ByteBuffer.wrap(packet), MediaCodec.BufferInfo().apply { set(0, packet.size, pts, 0) }, receivedAt)
                fedPackets++
                inputExtractor.advance()
            }
            feedEnded = now()
            completed = error.get() == null && now() < deadline
        } catch (failure: Throwable) {
            error.compareAndSet(null, "Main ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            synchronized(decisionLock) {
                running.set(false)
                currentSession.incrementAndGet()
                credit.set(false)
                rowsAtStop.set(attempts.size.toLong())
            }
            decoder?.close() // Serializes with any onFrame callback before dropping the final slot.
            latest.getAndSet(null)?.bitmap?.recycle()
            try { extractor?.release(); extractorReleased = true }
            catch (failure: Throwable) { error.compareAndSet(null, "Extractor close: ${failure.message}") }
            if (feedEnded == 0L) feedEnded = now()
            if (!finished.await(remainingMs(), TimeUnit.MILLISECONDS)) {
                error.compareAndSet(null, "Worker did not stop by the 30 s deadline")
                worker.interrupt() // Native inference cannot be force-closed safely from another thread.
            }
            if (decoder != null) decoderStopped.await(remainingMs(), TimeUnit.MILLISECONDS)
            if (now() > deadline) { completed = false; error.compareAndSet(null, "Variant deadline exceeded") }
            saveReport()
        }
        assertNull("See files/depth_validation/pipeline_$mode.json", error.get())
        assertTrue("Worker detector closed", modelClosed.get())
        assertTrue("Decoder explicitly stopped", decoderStopped.count == 0L)
        assertTrue("Actual model ran", attempts.any { it.inferenceMs != null })
        assertTrue("No post-stop proposal", attempts.drop(rowsAtStop.get().toInt()).all { it.proposalZone == null })
        if (mode == "on_demand") assertEquals("Demand avoids a pending bitmap replacement", 0L, dropped.get())
    }

    private fun normalizeAnnexB(bytes: ByteArray): ByteArray = runCatching {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val length = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            require(length > 0 && length <= bytes.size - offset - 4)
            output.write(byteArrayOf(0, 0, 0, 1)); output.write(bytes, offset + 4, length)
            offset += 4 + length
        }
        require(offset == bytes.size)
        output.toByteArray()
    }.getOrElse { require(H264Parameters.nals(bytes).isNotEmpty()); bytes }

    private fun distribution(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("count", 0)
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[ceil(sorted.size * p).toInt().coerceAtLeast(1) - 1]
        return JSONObject().put("count", sorted.size).put("min", sorted.first()).put("p50", percentile(.50))
            .put("p95", percentile(.95)).put("max", sorted.last())
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun elapsedMs(startedNs: Long) = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
}
