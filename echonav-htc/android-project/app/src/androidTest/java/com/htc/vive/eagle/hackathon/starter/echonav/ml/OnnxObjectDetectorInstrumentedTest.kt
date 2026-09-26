package com.htc.vive.eagle.hackathon.starter.echonav.ml

import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class OnnxObjectDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixtures get() = JSONObject(instrumentation.context.assets.open("ml/fixtures.json").bufferedReader().use { it.readText() }).getJSONArray("fixtures")

    private fun floats(asset: String): FloatArray {
        val bytes = instrumentation.context.assets.open("ml/$asset").use { it.readBytes() }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    /** Full bipartite matching, independent of TopK order; tolerances fixed in parity_protocol.json. */
    private fun parityReport(expected: FloatArray, actual: FloatArray): JSONObject {
        assertEquals(1800, actual.size)
        assertTrue(actual.all { it.isFinite() })
        val edges = Array(300) { i -> (0 until 300).filter { j ->
            expected[i * 6 + 5] == actual[j * 6 + 5] &&
                abs(expected[i * 6 + 4] - actual[j * 6 + 4]) <= .001f &&
                (0..3).all { k -> abs(expected[i * 6 + k] - actual[j * 6 + k]) <= 1f }
        } }
        val matched = IntArray(300) { -1 }
        fun find(i: Int, seen: BooleanArray): Boolean {
            for (j in edges[i]) {
                if (seen[j]) continue
                seen[j] = true
                if (matched[j] == -1 || find(matched[j], seen)) { matched[j] = i; return true }
            }
            return false
        }
        for (i in 0 until 300) find(i, BooleanArray(300))
        val matchedReference = matched.filter { it >= 0 }.toSet()
        val missingReference = (0 until 300).filter { it !in matchedReference }
        val missingActual = (0 until 300).filter { matched[it] == -1 }
        fun row(values: FloatArray, i: Int) = JSONArray((0..5).map { values[i * 6 + it].toDouble() })
        val missing = JSONArray()
        for (i in missingReference) {
            val nearest = (0 until 300).filter { expected[i * 6 + 5] == actual[it * 6 + 5] }.sortedBy { j ->
                (0..3).maxOf { k -> abs(expected[i * 6 + k] - actual[j * 6 + k]) } +
                    abs(expected[i * 6 + 4] - actual[j * 6 + 4]) / .001f
            }.take(3)
            missing.put(JSONObject().put("reference_row_index", i).put("reference_row", row(expected, i))
                .put("above_application_threshold", expected[i * 6 + 4] >= .70f)
                .put("nearest_same_class", JSONArray(nearest.map { j ->
                    JSONObject().put("actual_row_index", j).put("actual_row", row(actual, j))
                        .put("maximum_coordinate_difference_px", (0..3).maxOf { k -> abs(expected[i * 6 + k] - actual[j * 6 + k]) }.toDouble())
                        .put("score_difference", abs(expected[i * 6 + 4] - actual[j * 6 + 4]).toDouble())
                })))
        }
        var thresholdFlips = 0
        var maxXy = 0f
        var maxScore = 0f
        for (j in 0 until 300) {
            val i = matched[j]
            if (i < 0) continue
            if ((expected[i * 6 + 4] >= .70f) != (actual[j * 6 + 4] >= .70f)) thresholdFlips++
            maxXy = maxOf(maxXy, (0..3).maxOf { k -> abs(expected[i * 6 + k] - actual[j * 6 + k]) })
            maxScore = maxOf(maxScore, abs(expected[i * 6 + 4] - actual[j * 6 + 4]))
        }
        return JSONObject().put("passed", missingReference.isEmpty() && missingActual.isEmpty() && thresholdFlips == 0)
            .put("max_coordinate_tolerance_px", 1).put("max_score_tolerance", .001)
            .put("matched_rows", matchedReference.size).put("unmatched_reference", missing)
            .put("unmatched_actual", JSONArray(missingActual.map { j ->
                JSONObject().put("actual_row_index", j).put("actual_row", row(actual, j))
                    .put("above_application_threshold", actual[j * 6 + 4] >= .70f)
            })).put("threshold_flips", thresholdFlips)
            .put("unmatched_application_reference", missingReference.count { expected[it * 6 + 4] >= .70f })
            .put("unmatched_application_actual", missingActual.count { actual[it * 6 + 4] >= .70f })
            .put("max_coordinate_error_matched_px", maxXy.toDouble()).put("max_score_error_matched", maxScore.toDouble())
    }

    private fun saveFloats(name: String, values: FloatArray) {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        val folder = File(instrumentation.targetContext.filesDir, "ml_validation").apply { mkdirs() }
        File(folder, name).writeBytes(bytes.array())
    }

    private fun runProvider(useXnnpack: Boolean): JSONObject {
        val detector = OnnxObjectDetector(instrumentation.targetContext, useXnnpack)
        detector.use {
            val results = JSONArray()
            var passed = true
            val provider = if (useXnnpack) "xnnpack" else "cpu"
            for (i in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(i)
                val name = fixture.getString("name")
                val expectedInput = floats("$name.f32")
                val expectedOutput = floats("$name.output.f32")
                val bitmap = instrumentation.context.assets.open("ml/$name.png").use { BitmapFactory.decodeStream(it) }!!
                try {
                    val androidInput = FloatArray(expectedInput.size)
                    LetterboxPreprocessor.preprocess(bitmap, androidInput)
                    val pixelDifference = expectedInput.indices.maxOf { k -> abs(expectedInput[k] - androidInput[k]) }
                    val pixelsPass = pixelDifference <= .000001f
                    val actualOutput = detector.inferTensor(expectedInput)
                    val parity = parityReport(expectedOutput, actualOutput)
                    // Persist before assertions, including every raw row: diagnosis must survive a failing run.
                    saveFloats("${provider}_${name}.output.f32", actualOutput)
                    save("${provider}_${name}.parity.json", parity.put("fixture", name).put("provider", provider))
                    if (!pixelsPass) saveFloats("${provider}_${name}.input.f32", androidInput)
                    val detections = detector.detect(bitmap)
                    val expectedDetections = YoloTensorContract.decode(expectedOutput, LetterboxTransform(bitmap.width, bitmap.height))
                    val countPass = expectedDetections.size == detections.size
                    passed = passed && pixelsPass && parity.getBoolean("passed") && countPass
                    results.put(JSONObject().put("fixture", name).put("preprocess_ms", detector.lastPreprocessMs)
                        .put("inference_ms", detector.lastInferenceMs).put("detections_above_070", detections.size)
                        .put("expected_detections_above_070", expectedDetections.size).put("detection_count_passed", countPass)
                        .put("preprocessing_passed", pixelsPass).put("max_input_difference", pixelDifference.toDouble())
                        .put("parity", parity))
                } finally { bitmap.recycle() }
            }
            val name = fixtures.getJSONObject(0).getString("name")
            val input = floats("$name.f32")
            repeat(3) { detector.inferTensor(input) }
            val timings = (0 until 20).map { detector.inferTensor(input); detector.lastInferenceMs }.sorted()
            return JSONObject().put("provider_requested", detector.requestedProvider).put("num_threads", 2)
                .put("device_model", Build.MODEL).put("android", Build.VERSION.RELEASE).put("abi", Build.SUPPORTED_ABIS.first())
                .put("fixtures", results).put("warmup_runs", 3).put("samples", 20)
                .put("inference_p50_ms", timings[timings.size / 2])
                .put("inference_p95_ms", timings[ceil(timings.size * .95).toInt() - 1])
                .put("inference_max_ms", timings.last()).put("timings_ms", JSONArray(timings))
                .put("status", if (passed) "PASSED" else "FAILED").put("validation_protocol_version", 1)
                .put("scope", "model/preprocessing smoke parity and short inference-only benchmark; no live latency or thermal validation")
        }
    }

    private fun save(name: String, report: JSONObject) {
        val folder = File(instrumentation.targetContext.filesDir, "ml_validation").apply { mkdirs() }
        File(folder, name).writeText(report.toString(2))
        val compact = report.toString()
        val log = if (compact.length <= 3500) compact else JSONObject().put("artifact", "files/ml_validation/$name")
            .put("status", report.optString("status", "see artifact")).put("json_characters", compact.length).toString()
        println("ECHONAV_ML_REPORT $log")
    }

    @Test fun cpuParityAndShortBenchmark() {
        val report = runProvider(false)
        save("onnx_cpu.json", report)
        assertEquals("CPU parity failed: see files/ml_validation/onnx_cpu.json and per-fixture raw outputs", "PASSED", report.getString("status"))
    }

    @Test fun xnnpackParityAndShortBenchmarkIfAvailable() {
        // Only provider unavailability is optional; a created session must pass the same parity checks.
        try {
            OnnxObjectDetector(instrumentation.targetContext, true).close()
        } catch (error: ai.onnxruntime.OrtException) {
            save("onnx_xnnpack.json", JSONObject().put("status", "UNAVAILABLE").put("reason", error.message))
            Assume.assumeNoException(error)
            return
        }
        val report = runProvider(true)
        save("onnx_xnnpack.json", report)
        assertEquals("XNNPACK parity failed: see files/ml_validation/onnx_xnnpack.json", "PASSED", report.getString("status"))
    }

    /** Opt-in measured workload; fixtures have synthetic offer times, never real camera timestamps. */
    @Test fun sustainedXnnpackModelBenchmark() {
        val requested = InstrumentationRegistry.getArguments().getString("mlEnduranceSeconds")?.toIntOrNull()
        Assume.assumeTrue("Set runner argument mlEnduranceSeconds=600 for the endurance run", requested != null)
        val durationSeconds = requested!!
        require(durationSeconds in 10..3600)
        val names = listOf("ultralytics_bus", "sdk_sample_0", "ultralytics_zidane", "sdk_sample_1")
        val bitmaps = names.map { name ->
            instrumentation.context.assets.open("ml/$name.png").use { BitmapFactory.decodeStream(it) }!!
        }
        val app = instrumentation.targetContext
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun resources(elapsedMs: Long): JSONObject {
            val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val runtime = Runtime.getRuntime()
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return JSONObject().put("elapsed_ms", elapsedMs).put("pss_kb", memory.totalPss)
                .put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
                .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
                .put("thermal_status", power.currentThermalStatus)
                .put("battery_temperature_celsius", (battery?.getIntExtra("temperature", -1) ?: -1) / 10.0)
                .put("battery_level", battery?.getIntExtra("level", -1) ?: -1)
        }
        val report = JSONObject().put("scope", "Sustained model + bitmap preprocessing only; public/SDK fixtures, synthetic 4Hz offer clock; no glasses capture, codec, SDK or audio")
            .put("provider_requested", "XNNPACK with CPU fallback").put("num_threads", 2)
            .put("device_model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("duration_requested_seconds", durationSeconds).put("target_hz", 4)
            .put("freshness_limit_ms", 500).put("fixture_cycle", JSONArray(names))
            .put("thermal_status_scale", "Android PowerManager: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown")
        val samples = JSONArray()
        val resourceSamples = JSONArray()
        val latencies = mutableListOf<Double>()
        val inferences = mutableListOf<Double>()
        val preprocs = mutableListOf<Double>()
        var stale = 0
        var skippedSlots = 0L
        var valid = 0
        var maxWithoutFreshMs = 0.0
        var runStartNs = 0L
        var status = "COMPLETED"
        try {
            OnnxObjectDetector(app, true).use { detector ->
                repeat(3) { detector.detect(bitmaps[it % bitmaps.size]) }
                resourceSamples.put(resources(0))
                runStartNs = SystemClock.elapsedRealtimeNanos()
                val endNs = runStartNs + durationSeconds * 1_000_000_000L
                val periodNs = 250_000_000L
                var nextSlot = 0L
                var nextResourceMs = 10_000L
                var lastFreshNs = runStartNs
                while (SystemClock.elapsedRealtimeNanos() < endNs) {
                    val scheduledNs = runStartNs + nextSlot * periodNs
                    val waitNs = scheduledNs - SystemClock.elapsedRealtimeNanos()
                    if (waitNs > 0) SystemClock.sleep((waitNs + 999_999) / 1_000_000)
                    val dispatchNs = SystemClock.elapsedRealtimeNanos()
                    if (dispatchNs >= endNs) break
                    // Skip offers lost during overload, retaining only the latest fixture offer.
                    val slot = max(nextSlot, (dispatchNs - runStartNs) / periodNs)
                    skippedSlots += slot - nextSlot
                    val offeredNs = runStartNs + slot * periodNs
                    val index = (slot % bitmaps.size).toInt()
                    val detections = detector.detect(bitmaps[index])
                    val finishedNs = SystemClock.elapsedRealtimeNanos()
                    val elapsedMs = (finishedNs - runStartNs) / 1_000_000
                    val ageMs = (finishedNs - offeredNs) / 1_000_000.0
                    val fresh = ageMs <= 500
                    maxWithoutFreshMs = maxOf(maxWithoutFreshMs, (finishedNs - lastFreshNs) / 1_000_000.0)
                    if (fresh) { valid++; lastFreshNs = finishedNs } else stale++
                    latencies.add(ageMs)
                    inferences.add(detector.lastInferenceMs)
                    preprocs.add(detector.lastPreprocessMs)
                    samples.put(JSONObject().put("slot", slot).put("fixture", names[index]).put("elapsed_ms", elapsedMs)
                        .put("dispatch_delay_ms", (dispatchNs - offeredNs) / 1_000_000.0)
                        .put("offer_to_result_ms", ageMs).put("preprocess_ms", detector.lastPreprocessMs)
                        .put("inference_ms", detector.lastInferenceMs).put("fresh", fresh)
                        .put("detections_above_070", detections.size))
                    nextSlot = slot + 1
                    if (elapsedMs >= nextResourceMs) {
                        val snapshot = resources(elapsedMs)
                        resourceSamples.put(snapshot)
                        save("onnx_xnnpack_endurance_progress.json", JSONObject().put("elapsed_ms", elapsedMs)
                            .put("samples", samples.length()).put("fresh_results", valid).put("stale_results", stale)
                            .put("skipped_offer_slots", skippedSlots).put("resource_snapshot", snapshot))
                        nextResourceMs += 10_000
                        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                            status = "INTERRUPTED_CRITICAL_THERMAL_STATUS"
                            break
                        }
                    }
                }
                resourceSamples.put(resources((SystemClock.elapsedRealtimeNanos() - runStartNs) / 1_000_000)
                    .put("phase", "end_before_detector_close"))
            }
        } catch (failure: Throwable) {
            status = "FAILED"
            report.put("failure", failure.toString())
            throw failure
        } finally {
            val elapsedMs = if (runStartNs == 0L) 0L else (SystemClock.elapsedRealtimeNanos() - runStartNs) / 1_000_000
            fun p(values: List<Double>, quantile: Double): Double? = values.sorted().let { sorted ->
                if (sorted.isEmpty()) null else sorted[(ceil(sorted.size * quantile).toInt() - 1).coerceAtLeast(0)]
            }
            resourceSamples.put(resources(elapsedMs).put("phase", "after_detector_close"))
            report.put("status", status).put("elapsed_ms", elapsedMs).put("completed_results", samples.length())
                .put("fresh_results", valid).put("stale_results", stale).put("skipped_offer_slots", skippedSlots)
                .put("fresh_result_hz", if (elapsedMs > 0) valid * 1000.0 / elapsedMs else 0.0)
                .put("longest_without_fresh_result_ms", maxWithoutFreshMs)
                .put("offer_to_result_p50_ms", p(latencies, .50)).put("offer_to_result_p95_ms", p(latencies, .95))
                .put("offer_to_result_max_ms", latencies.maxOrNull()).put("inference_p95_ms", p(inferences, .95))
                .put("preprocess_p95_ms", p(preprocs, .95)).put("samples", samples).put("resources", resourceSamples)
                .put("real_video_freshness_validated", false).put("live_glasses_chain_validated", false)
            save("onnx_xnnpack_endurance.json", report)
            bitmaps.forEach { it.recycle() }
        }
        assertEquals("Endurance stopped early; see saved report", "COMPLETED", status)
        assertTrue("Run did not cover requested duration", report.getLong("elapsed_ms") >= durationSeconds * 1000L)
    }
}
