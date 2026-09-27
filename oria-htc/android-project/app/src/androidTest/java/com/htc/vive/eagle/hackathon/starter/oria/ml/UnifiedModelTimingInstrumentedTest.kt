package com.htc.vive.eagle.hackathon.starter.oria.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Synthetic RGB scheduling and real concurrent models; no glasses, codec, radio, policy or audio. */
@RunWith(AndroidJUnit4::class)
class UnifiedModelTimingInstrumentedTest {
    private data class Frame(val id: Long, val observedAtMs: Long, val bitmap: Bitmap, val copyMs: Double)
    private data class Sample(val id: Long, val observedAtMs: Long, val startedAtMs: Long,
                              val endedAtMs: Long, val copyMs: Double, val preprocessMs: Double,
                              val inferenceMs: Double, val postprocessMs: Double?,
                              val serviceMs: Double, val invalidated: Boolean) {
        fun json() = JSONObject().put("id", id).put("observedAtMs", observedAtMs)
            .put("startedAtMs", startedAtMs).put("endedAtMs", endedAtMs)
            .put("copyMs", copyMs).put("queueMs", startedAtMs - observedAtMs)
            .put("preprocessMs", preprocessMs).put("inferenceMs", inferenceMs)
            .put("postprocessMs", postprocessMs ?: JSONObject.NULL).put("serviceMs", serviceMs)
            .put("resultAgeMs", endedAtMs - observedAtMs).put("invalidatedAfterStop", invalidated)
    }
    private class Lane(val name: String, val cadenceMs: Long) {
        val latest = AtomicReference<Frame?>(null)
        val sent = AtomicLong()
        val replaced = AtomicLong()
        val skippedScheduleTicks = AtomicLong()
        val samples = CopyOnWriteArrayList<Sample>()
        val closed = AtomicBoolean(false)
        val warmupMs = AtomicReference<Double?>(null)
    }

    @Test fun compareConcurrentYoloAndDepthCpuWorkers() {
        val args = InstrumentationRegistry.getArguments()
        val configurations = (args.getString("unifiedDepthThreads") ?: "2,4")
            .split(',').map { it.trim().toInt() }.also { require(it.isNotEmpty() && it.all { n -> n in 1..8 }) }
        val iterations = (args.getString("unifiedIterations")?.toIntOrNull() ?: 5).coerceIn(3, 5)
        for (threads in configurations) runConfiguration(threads, iterations)
    }

    private fun runConfiguration(depthThreads: Int, iterations: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = instrumentation.context.assets.open("depth/synthetic_portrait.png").use { BitmapFactory.decodeStream(it) }!!
        val bitmap = Bitmap.createScaledBitmap(original, 467, 832, true)
        if (bitmap !== original) original.recycle()
        val startedAt = now()
        val deadline = startedAt + 10_000
        val yolo = Lane("yolo", 333)
        val depth = Lane("depth", 667)
        val lanes = listOf(yolo, depth)
        val running = AtomicBoolean(true)
        val error = AtomicReference<String?>(null)
        val ready = CountDownLatch(2)
        val startTogether = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val acceptedUntilMs = AtomicLong(deadline - 1_000)
        var feedStartedAt = 0L
        var stoppedAt = 0L
        fun remaining() = (deadline - now()).coerceAtLeast(0)

        fun worker(lane: Lane): Thread = Thread({
            var objectDetector: OnnxObjectDetector? = null
            var depthDetector: OnnxDepthDetector? = null
            var readyPublished = false
            try {
                if (lane === yolo) objectDetector = OnnxObjectDetector(context, useXnnpack = true)
                else depthDetector = OnnxDepthDetector(context, useXnnpack = false, numThreads = depthThreads)
                val warm = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                try {
                    val warmStarted = SystemClock.elapsedRealtimeNanos()
                    if (lane === yolo) checkNotNull(objectDetector).detect(warm)
                    else checkNotNull(depthDetector).detect(warm)
                    lane.warmupMs.set(elapsedMs(warmStarted))
                } finally { warm.recycle() }
                ready.countDown(); readyPublished = true
                check(startTogether.await(remaining(), TimeUnit.MILLISECONDS)) { "Start barrier deadline" }
                while (running.get() && now() < acceptedUntilMs.get()) {
                    val frame = lane.latest.getAndSet(null)
                    if (frame == null) { Thread.sleep(2); continue }
                    val inferStartedAt = now()
                    val inferStartedNs = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (!running.get()) continue
                        val preprocess: Double
                        val inference: Double
                        val postprocess: Double?
                        if (lane === yolo) {
                            val detector = checkNotNull(objectDetector)
                            detector.detect(frame.bitmap)
                            preprocess = detector.lastPreprocessMs
                            inference = detector.lastInferenceMs
                            postprocess = null // This detector does not expose a separate postprocess clock.
                        } else {
                            val result = checkNotNull(depthDetector).detect(frame.bitmap)
                            preprocess = result.preprocessMs
                            inference = result.inferenceMs
                            postprocess = result.postprocessMs
                        }
                        val end = now()
                        lane.samples += Sample(frame.id, frame.observedAtMs, inferStartedAt, end,
                            frame.copyMs, preprocess, inference, postprocess, elapsedMs(inferStartedNs), !running.get())
                    } finally { frame.bitmap.recycle() }
                }
            } catch (failure: Throwable) {
                error.compareAndSet(null, "${lane.name} ${failure.javaClass.simpleName}: ${failure.message}")
                running.set(false)
            } finally {
                if (!readyPublished) ready.countDown()
                try { objectDetector?.close(); depthDetector?.close(); lane.closed.set(true) }
                catch (failure: Throwable) { error.compareAndSet(null, "${lane.name} close: ${failure.message}") }
                finished.countDown()
            }
        }, "OriaUnified-${lane.name}").apply { start() }

        val workers = lanes.map(::worker)
        try {
            check(ready.await(minOf(5_000, remaining()), TimeUnit.MILLISECONDS)) { "Concurrent model initialization exceeded five seconds" }
            check(error.get() == null) { error.get() ?: "Model initialization failed" }
            feedStartedAt = now()
            val nextAt = longArrayOf(feedStartedAt, feedStartedAt)
            var sequence = 0L
            startTogether.countDown()
            while (running.get() && now() < acceptedUntilMs.get() &&
                (yolo.samples.size < iterations * 2 || depth.samples.size < iterations)) {
                val current = now()
                for ((index, lane) in lanes.withIndex()) {
                    if (current < nextAt[index] || !running.get()) continue
                    val observedAt = now()
                    val copyStarted = SystemClock.elapsedRealtimeNanos()
                    val owned = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    val frame = Frame(++sequence, observedAt, owned, elapsedMs(copyStarted))
                    lane.latest.getAndSet(frame)?.let { lane.replaced.incrementAndGet(); it.bitmap.recycle() }
                    lane.sent.incrementAndGet()
                    nextAt[index] += lane.cadenceMs
                    while (nextAt[index] <= now()) { nextAt[index] += lane.cadenceMs; lane.skippedScheduleTicks.incrementAndGet() }
                }
                Thread.sleep(2)
            }
        } catch (failure: Throwable) {
            error.compareAndSet(null, "Schedule ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            running.set(false)
            stoppedAt = now()
            startTogether.countDown()
            lanes.forEach { it.latest.getAndSet(null)?.bitmap?.recycle() }
            if (!finished.await(remaining(), TimeUnit.MILLISECONDS)) {
                error.compareAndSet(null, "Worker native inference outlived ten second configuration budget")
                workers.forEach { if (it.isAlive) it.interrupt() }
            }
            // The source is borrowed only for initialization/warmup copies. Do not recycle
            // under a pathological still-running initializer; the failed instrumentation
            // process must be stopped by the runner instead of racing Bitmap.copy().
            if (finished.count == 0L) bitmap.recycle()
            val report = JSONObject().put("schemaVersion", 1).put("device", Build.MODEL)
                .put("source", "synthetic_portrait_rescaled_once_467x832")
                .put("sdkRadio", false).put("camera", false).put("decoder", false).put("audio", false)
                .put("geometryOrPolicyIncluded", false).put("pixelWidth", 467).put("pixelHeight", 832)
                .put("scope", "Concurrent model contention only; excludes physical video, YUV conversion, geometry, policy, playback and thermal endurance")
                .put("depthProvider", "CPU").put("depthThreads", depthThreads).put("depthModelSha256", OnnxDepthDetector.MODEL_SHA256)
                .put("yoloProvider", "XNNPACK_with_CPU_fallback").put("warmupsPerLane", 1)
                .put("targetDepthIterations", iterations).put("targetYoloIterations", iterations * 2)
                .put("elapsedMs", now() - startedAt).put("feedElapsedMs", if (feedStartedAt > 0) stoppedAt - feedStartedAt else 0)
                .put("budgetMs", 10_000).put("workersStopped", finished.count == 0L)
                .put("error", error.get() ?: JSONObject.NULL)
            for (lane in lanes) {
                val usable = lane.samples.filter { !it.invalidated }
                report.put(lane.name, JSONObject().put("cadenceMs", lane.cadenceMs)
                    .put("sent", lane.sent.get()).put("replaced", lane.replaced.get())
                    .put("skippedScheduleTicks", lane.skippedScheduleTicks.get()).put("processed", lane.samples.size)
                    .put("validCompleted", usable.size).put("warmupMs", lane.warmupMs.get() ?: JSONObject.NULL)
                    .put("detectorClosed", lane.closed.get())
                    .put("resultAgeMs", distribution(usable.map { (it.endedAtMs - it.observedAtMs).toDouble() }))
                    .put("queueMs", distribution(usable.map { (it.startedAtMs - it.observedAtMs).toDouble() }))
                    .put("preprocessMs", distribution(usable.map { it.preprocessMs }))
                    .put("inferenceMs", distribution(usable.map { it.inferenceMs }))
                    .put("postprocessMs", distribution(usable.mapNotNull { it.postprocessMs }))
                    .put("serviceMs", distribution(usable.map { it.serviceMs }))
                    .put("over500Ms", usable.count { it.endedAtMs - it.observedAtMs > 500 })
                    .put("over1500Ms", usable.count { it.endedAtMs - it.observedAtMs > 1500 })
                    .put("samples", JSONArray().apply { lane.samples.forEach { put(it.json()) } }))
            }
            context.filesDir.resolve("depth_validation").apply { check(isDirectory || mkdirs()) }
                .resolve("unified_cpu${depthThreads}.json").writeText(report.toString(2))
        }
        assertNull("See files/depth_validation/unified_cpu${depthThreads}.json", error.get())
        assertTrue("YOLO reached its measured sample target", yolo.samples.count { !it.invalidated } >= iterations * 2)
        assertTrue("Depth reached its measured sample target", depth.samples.count { !it.invalidated } >= iterations)
        assertTrue("Both model owners closed", lanes.all { it.closed.get() })
    }

    private fun distribution(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("count", 0)
        val sorted = values.sorted()
        fun p(q: Double) = sorted[ceil(sorted.size * q).toInt().coerceAtLeast(1) - 1]
        return JSONObject().put("count", sorted.size).put("min", sorted.first()).put("p50", p(.5))
            .put("p95", p(.95)).put("max", sorted.last())
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun elapsedMs(startedNs: Long) = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
}
