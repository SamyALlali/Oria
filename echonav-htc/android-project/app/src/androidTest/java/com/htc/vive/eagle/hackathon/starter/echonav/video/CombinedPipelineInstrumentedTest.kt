package com.htc.vive.eagle.hackathon.starter.echonav.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.vive.eagle.hackathon.starter.echonav.core.Box
import com.htc.vive.eagle.hackathon.starter.echonav.core.Detection
import com.htc.vive.eagle.hackathon.starter.echonav.core.DetectionFrame
import com.htc.vive.eagle.hackathon.starter.echonav.core.RgbAlertEngine
import com.htc.vive.eagle.hackathon.starter.echonav.core.RgbFrameStatus
import com.htc.vive.eagle.hackathon.starter.echonav.ml.OnnxObjectDetector
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

/** Phone-only integration, bundled replay and a fake confirmed voice sink. Never calls HTC/TTS. */
@RunWith(AndroidJUnit4::class)
class CombinedPipelineInstrumentedTest {
    private data class Attempt(
        val frameId: Long, val ageMs: Long, val status: String, val conversionMs: Double,
        val preprocessMs: Double, val inferenceMs: Double, val detections: Int,
    )

    @Test fun sixtySecondReplayPipelineAndStopGuard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seconds = (InstrumentationRegistry.getArguments().getString("pipelineSeconds")?.toLongOrNull() ?: 60L).coerceIn(20, 60)
        val resource = context.resources.getIdentifier("video_sample", "raw", context.packageName)
        assertTrue("Bundled replay is present", resource != 0)
        val extractor = MediaExtractor()
        context.resources.openRawResourceFd(resource).use { fd -> extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val firstSourcePts = extractor.sampleTime
        val cycleDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 6_433_333L
        val session = 41L
        val currentSession = AtomicLong(session)
        val running = AtomicBoolean(true)
        val latest = AtomicReference<EchoNavVideoFrame?>(null)
        val replaced = AtomicLong()
        val delivered = AtomicLong()
        val invalidatedAfterStop = AtomicLong()
        val errors = AtomicReference<String?>(null)
        val videoStatus = AtomicReference(EchoNavVideoStatus())
        val attempts = CopyOnWriteArrayList<Attempt>()
        val fakeVoice = CopyOnWriteArrayList<JSONObject>()
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val armStopProbe = AtomicBoolean(false)
        val stopProbeTaken = AtomicBoolean(false)
        val resultHeldBeforeDecision = CountDownLatch(1)
        val releaseHeldResult = CountDownLatch(1)
        val report = JSONObject().put("source", "bundled_htc_mp4_replay_on_phone")
            .put("sdkTransport", false).put("actualAudio", false).put("voiceSink", "fake_confirmed")
            .put("provider", "XNNPACK_with_CPU_fallback").put("requestedDurationSeconds", seconds)
        var feedStartedAt = 0L
        var stoppedAt = 0L
        var packetCount = 0L

        fun saveReport() {
            val rows = JSONArray()
            attempts.forEach { a -> rows.put(JSONObject().put("frameId", a.frameId).put("ageMs", a.ageMs)
                .put("status", a.status).put("conversionMs", a.conversionMs).put("preprocessMs", a.preprocessMs)
                .put("inferenceMs", a.inferenceMs).put("detections", a.detections)) }
            val accepted = attempts.filter { it.status == RgbFrameStatus.ACCEPTED.name }
            val elapsed = if (feedStartedAt > 0) (stoppedAt - feedStartedAt).coerceAtLeast(1) else 0L
            val video = videoStatus.get()
            report.put("elapsedReplayMs", elapsed).put("inputVideoPackets", packetCount).put("decoder", video.codecName ?: JSONObject.NULL)
                .put("decodedFrames", video.decodedFrames).put("bitmapsDelivered", delivered.get()).put("latestSlotReplacements", replaced.get())
                .put("decoderStaleBeforeConversion", video.staleBeforeConversion).put("decoderStaleAfterConversion", video.staleAfterConversion)
                .put("decisionAttempts", attempts.size).put("acceptedDecisions", accepted.size)
                .put("acceptedDecisionHz", if (elapsed > 0) accepted.size * 1000.0 / elapsed else 0.0)
                .put("rejectedBeforeInference", attempts.count { it.status == "STALE_BEFORE_INFERENCE" })
                .put("rejectedAfterInference", attempts.count { it.status == RgbFrameStatus.STALE.name })
                .put("invalidatedAfterStop", invalidatedAfterStop.get()).put("error", errors.get() ?: JSONObject.NULL)
                .put("allAttemptAgesMs", distribution(attempts.map { it.ageMs.toDouble() }))
                .put("acceptedAgesMs", distribution(accepted.map { it.ageMs.toDouble() }))
                .put("inferenceMs", distribution(attempts.filter { it.inferenceMs > 0 }.map { it.inferenceMs }))
                .put("preprocessingMs", distribution(attempts.filter { it.preprocessMs > 0 }.map { it.preprocessMs }))
                .put("attempts", rows).put("fakeVoiceEvents", JSONArray(fakeVoice.toList()))
            context.filesDir.resolve("combined-pipeline-report.json").writeText(report.toString(2))
            Log.i("EchoNavCombinedTest", report.toString())
        }

        val worker = Thread({
            var detector: OnnxObjectDetector? = null
            val engine = RgbAlertEngine()
            try {
                detector = OnnxObjectDetector(context, useXnnpack = true)
                engine.start(session, now())
                ready.countDown()
                while (running.get() || latest.get() != null) {
                    val frame = latest.getAndSet(null)
                    if (frame == null) { Thread.sleep(2); continue }
                    try {
                        if (frame.sessionId != currentSession.get()) { invalidatedAfterStop.incrementAndGet(); continue }
                        val beforeAge = now() - frame.receivedAtMs
                        if (beforeAge !in 0..500) {
                            attempts += Attempt(frame.frameId, beforeAge, "STALE_BEFORE_INFERENCE", frame.conversionMs, 0.0, 0.0, 0)
                            continue
                        }
                        val detections = detector.detect(frame.bitmap)
                        // Inject a stop race only after a genuine model inference, never fake its output.
                        if (armStopProbe.get() && stopProbeTaken.compareAndSet(false, true)) {
                            resultHeldBeforeDecision.countDown()
                            check(releaseHeldResult.await(5, TimeUnit.SECONDS)) { "Stop probe was not released" }
                        }
                        if (frame.sessionId != currentSession.get() || !running.get()) {
                            invalidatedAfterStop.incrementAndGet()
                            continue
                        }
                        val decisionAt = now()
                        val evaluation = engine.evaluate(DetectionFrame(session, frame.frameId, frame.receivedAtMs, detections), decisionAt)
                        attempts += Attempt(frame.frameId, decisionAt - frame.receivedAtMs, evaluation.frameStatus.name,
                            frame.conversionMs, detector.lastPreprocessMs, detector.lastInferenceMs, detections.size)
                        evaluation.eligibleAlert?.let { alert ->
                            if (currentSession.get() == session && running.get()) {
                                engine.onSubmitted(alert, now())?.let { ticket ->
                                    check(engine.onConfirmed(ticket, now()))
                                    fakeVoice += JSONObject().put("source", "real_model_on_replay_fake_audio")
                                        .put("text", alert.text).put("frameId", frame.frameId).put("confirmedByFakeSink", true)
                                }
                            }
                        }
                    } finally { frame.bitmap.recycle() }
                }
                engine.stop()
                check(engine.evaluate(DetectionFrame(session, Long.MAX_VALUE, now(), emptyList()), now()).frameStatus == RgbFrameStatus.STOPPED)
            } catch (failure: Throwable) {
                errors.compareAndSet(null, "Worker ${failure.javaClass.simpleName}: ${failure.message}")
                running.set(false)
            } finally {
                ready.countDown()
                detector?.close()
                finished.countDown()
            }
        }, "EchoNavCombinedInference").apply { start() }

        var decoder: EchoNavVideoDecoder? = null
        try {
            assertTrue("Model worker ready", ready.await(15, TimeUnit.SECONDS))
            assertNull("Worker initialization", errors.get())
            decoder = EchoNavVideoDecoder(session, 0, false, onFrame = { frame ->
                if (!running.get() || currentSession.get() != frame.sessionId) false
                else {
                    latest.getAndSet(frame)?.let { replaced.incrementAndGet(); it.bitmap.recycle() }
                    delivered.incrementAndGet()
                    true
                }
            }, onStatus = { videoStatus.set(it) }, onFatalError = { errors.compareAndSet(null, "Decoder: $it"); running.set(false) })
            val activeDecoder = decoder
            for (key in listOf("csd-0", "csd-1")) {
                format.getByteBuffer(key)?.duplicate()?.let { b ->
                    val data = ByteArray(b.remaining()).also { b.get(it) }
                    activeDecoder.submit(ByteBuffer.wrap(data), MediaCodec.BufferInfo().apply { set(0, data.size, -1, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) })
                }
            }
            feedStartedAt = now()
            val deadline = feedStartedAt + seconds * 1000
            var cycleBasePts = 0L
            var maxPtsInCycle = 0L
            val input = ByteBuffer.allocate(4 * 1024 * 1024)
            while (running.get() && errors.get() == null) {
                val currentTime = now()
                if (currentTime >= deadline) armStopProbe.set(true)
                if (resultHeldBeforeDecision.count == 0L || currentTime > deadline + 3000) break
                if (extractor.sampleTime < 0) {
                    cycleBasePts += maxOf(cycleDurationUs, maxPtsInCycle + 33_333)
                    maxPtsInCycle = 0
                    extractor.seekTo(firstSourcePts, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                val sourcePts = extractor.sampleTime - firstSourcePts
                maxPtsInCycle = maxOf(maxPtsInCycle, sourcePts)
                val pts = cycleBasePts + sourcePts
                val due = feedStartedAt + pts / 1000
                while (running.get() && now() < due) Thread.sleep((due - now()).coerceIn(1, 50))
                input.clear()
                val size = extractor.readSampleData(input, 0)
                if (size > 0) {
                    val bytes = ByteArray(size); input.position(0); input.get(bytes)
                    val packet = normalizeAnnexB(bytes)
                    activeDecoder.submit(ByteBuffer.wrap(packet), MediaCodec.BufferInfo().apply { set(0, packet.size, pts, 0) })
                    packetCount++
                }
                extractor.advance()
            }
            val stopProbeWasReached = resultHeldBeforeDecision.count == 0L
            currentSession.incrementAndGet()
            running.set(false)
            activeDecoder.close()
            latest.getAndSet(null)?.bitmap?.recycle()
            stoppedAt = now()
            val voicesAtStop = fakeVoice.size
            releaseHeldResult.countDown()
            assertTrue("Inference worker stops", finished.await(10, TimeUnit.SECONDS))
            assertEquals("No fake voice dispatch from the late result", voicesAtStop, fakeVoice.size)
            report.put("stopProbeReached", stopProbeWasReached).put("noVoiceAfterStop", voicesAtStop == fakeVoice.size)
            report.put("syntheticPolicyProbe", syntheticPolicyProbe())
            saveReport()
            assertNull("Pipeline error, see combined-pipeline-report.json", errors.get())
            assertTrue("Stop race injected after real inference", stopProbeWasReached)
            assertTrue("Old in-flight result invalidated", invalidatedAfterStop.get() >= 1)
            assertTrue("At least twenty fresh decisions, see report", attempts.count { it.status == RgbFrameStatus.ACCEPTED.name } >= 20)
            assertTrue("Every accepted decision respects 500 ms", attempts.filter { it.status == RgbFrameStatus.ACCEPTED.name }.all { it.ageMs in 0..500 })
        } finally {
            running.set(false)
            currentSession.incrementAndGet()
            decoder?.close()
            latest.getAndSet(null)?.bitmap?.recycle()
            releaseHeldResult.countDown()
            if (finished.count > 0) finished.await(10, TimeUnit.SECONDS)
            extractor.release()
            if (stoppedAt == 0L) stoppedAt = now()
            saveReport()
            if (worker.isAlive) worker.interrupt()
        }
    }

    /** Independent synthetic fixture; explicitly excluded from model/replay detection counts. */
    private fun syntheticPolicyProbe(): JSONObject {
        val engine = RgbAlertEngine()
        engine.start(999, 1_000)
        val person = Detection(0, .96f, Box(.40f, .25f, .60f, .95f))
        val first = engine.evaluate(DetectionFrame(999, 1, 1_000, listOf(person)), 1_000)
        check(first.eligibleAlert == null)
        val confirmed = engine.evaluate(DetectionFrame(999, 2, 1_250, listOf(person)), 1_250)
        val alert = checkNotNull(confirmed.eligibleAlert)
        val ticket = checkNotNull(engine.onSubmitted(alert, 1_250))
        check(engine.onConfirmed(ticket, 1_251))
        engine.stop()
        check(engine.onSubmitted(alert, 1_252) == null)
        check(!engine.onConfirmed(ticket, 1_252))
        return JSONObject().put("source", "synthetic_detections_no_model_no_audio")
            .put("text", alert.text).put("confirmedByFakeSink", true).put("lateTicketRejected", true)
    }

    private fun normalizeAnnexB(bytes: ByteArray): ByteArray {
        return runCatching {
            val output = java.io.ByteArrayOutputStream()
            var offset = 0
            while (offset + 4 <= bytes.size) {
                val n = ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
                    ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
                require(n > 0 && n <= bytes.size - offset - 4)
                output.write(byteArrayOf(0, 0, 0, 1)); output.write(bytes, offset + 4, n)
                offset += 4 + n
            }
            require(offset == bytes.size)
            output.toByteArray()
        }.getOrElse { require(H264Parameters.nals(bytes).isNotEmpty()); bytes }
    }

    private fun distribution(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("count", 0)
        val sorted = values.sorted()
        return JSONObject().put("count", sorted.size).put("min", sorted.first())
            .put("p50", sorted[ceil(sorted.size * .5).toInt().coerceAtLeast(1) - 1])
            .put("p95", sorted[ceil(sorted.size * .95).toInt().coerceAtLeast(1) - 1]).put("max", sorted.last())
    }

    private fun now() = SystemClock.elapsedRealtime()
}
