package com.htc.vive.eagle.hackathon.starter.oria.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Bounded synthetic workload, no camera/audio/session start; never a human listening proof. */
@RunWith(AndroidJUnit4::class)
class OnnxDepthDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun image(name: String): Bitmap = instrumentation.context.assets.open("depth/$name.png").use { BitmapFactory.decodeStream(it) }!!
    private fun floats(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("depth/$name.f32").use { it.readBytes() }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
    private fun input(bitmap: Bitmap): FloatArray {
        assertEquals(252, bitmap.width); assertEquals(252, bitmap.height)
        val pixels = IntArray(252 * 252)
        bitmap.getPixels(pixels, 0, 252, 0, 0, 252, 252)
        val means = floatArrayOf(.485f, .456f, .406f); val stds = floatArrayOf(.229f, .224f, .225f)
        return FloatArray(3 * pixels.size) { i ->
            val channel = i / pixels.size
            val value = (pixels[i % pixels.size] ushr (16 - channel * 8)) and 255
            (value / 255f - means[channel]) / stds[channel]
        }
    }
    private fun save(provider: String, report: JSONObject) {
        val folder = File(instrumentation.targetContext.filesDir, "depth_validation").apply { mkdirs() }
        File(folder, "$provider.json").writeText(report.toString(2))
        println("ORIA_DEPTH_REPORT " + JSONObject().put("provider", provider).put("status", report.optString("status"))
            .put("artifact", "files/depth_validation/$provider.json").put("elapsedMs", report.optLong("elapsedMs")))
    }
    private fun runProvider(numThreads: Int, measuredRuns: Int, deadlineMs: Long, label: String, benchmarkWidth: Int, benchmarkHeight: Int) {
        val provider = "cpu_threads_${numThreads}_$label"
        val start = SystemClock.elapsedRealtime()
        val report = JSONObject().put("status", "RUNNING").put("providerRequested", provider)
            .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("modelSha256", OnnxDepthDetector.MODEL_SHA256)
            .put("scope", "Two synthetic fixtures, exact preprocessing and export parity, then bounded full-pipeline timings; no glasses/audio/thermal endurance")
            .put("numThreads", numThreads).put("benchmarkVersion", 2).put("label", label)
            .put("modelShape", "1x3x252x252").put("metric", false)
            .put("benchmarkSourceWidth", benchmarkWidth).put("benchmarkSourceHeight", benchmarkHeight)
            .put("benchmarkImageOrigin", "Synthetic portrait scaled once for benchmark only; no live camera and original parity fixtures unchanged")
        val samples = JSONArray(); report.put("fixtures", samples)
        fun budget() { check(SystemClock.elapsedRealtime() < deadlineMs) { "Depth test exceeded shared 90 second budget" } }
        fun checkpoint() { report.put("elapsedMs", SystemClock.elapsedRealtime() - start); save(provider, report) }
        checkpoint()
        try {
            val createStart = SystemClock.elapsedRealtime()
            OnnxDepthDetector(instrumentation.targetContext, useXnnpack = false, numThreads = numThreads).use { detector ->
                report.put("loadMs", SystemClock.elapsedRealtime() - createStart)
                    .put("graphOptimization", detector.graphOptimization)
                val reference = JSONObject(instrumentation.context.assets.open("depth/fixtures.json").bufferedReader().use { it.readText() })
                val fixtures = reference.getJSONArray("fixtures")
                for (i in 0 until fixtures.length()) {
                    budget()
                    val fixture = fixtures.getJSONObject(i); val name = fixture.getString("name")
                    val bitmap = image(name); val resized = image("$name.input")
                    try {
                        val expectedInput = input(resized)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val prepared = DepthImageProcessing.preprocess(pixels, bitmap.width, bitmap.height)
                        val preDiff = prepared.indices.maxOf { abs(prepared[it] - expectedInput[it]) }.toDouble()
                        val rawReference = floats("$name.raw")
                        val actualRaw = detector.inferTensor(expectedInput)
                        val rawDiff = rawReference.indices.maxOf { abs(actualRaw[it] - rawReference[it]) }.toDouble()
                        val relativeRawDiff = rawDiff / max(1.0, rawReference.maxOf { abs(it) }.toDouble())
                        val rawMs = detector.lastInferenceMs
                        budget()
                        val result = detector.detect(bitmap)
                        val expected = floats("$name.normalized")
                        val diff = if (result.available && result.values.size == expected.size)
                            expected.indices.maxOf { abs(expected[it] - result.values[it]) }.toDouble() else Double.MAX_VALUE
                        val q = fixture.getJSONObject("quality")
                        val qualityPass = result.qualityUsable == (q.getString("status") == "usable") &&
                            result.qualityReason == q.getJSONArray("reasons").optString(0).ifEmpty { null }
                        samples.put(JSONObject().put("fixture", name).put("preprocessMaxAbsError", preDiff)
                            .put("rawRelativeMaxAbsError", relativeRawDiff).put("normalizedMaxAbsError", diff)
                            .put("qualityPassed", qualityPass).put("available", result.available)
                            .put("preprocessMs", result.preprocessMs).put("inferenceMs", result.inferenceMs)
                            .put("postprocessMs", result.postprocessMs).put("rawInferenceMs", rawMs))
                        checkpoint()
                        assertTrue("RGB preprocessing parity", preDiff <= 1e-6)
                        assertTrue("Raw ONNX provider parity", relativeRawDiff <= .001)
                        assertTrue("Normalized depth parity", diff <= .002)
                        assertTrue("RGB quality parity", qualityPass)
                    } finally { bitmap.recycle(); resized.recycle() }
                }
                val original = image(fixtures.getJSONObject(0).getString("name"))
                // This constructs a synthetic camera-size workload only. The model still uses
                // DepthImageProcessing's exact Pillow-compatible resize, never Android scaling.
                val first = try { Bitmap.createScaledBitmap(original, benchmarkWidth, benchmarkHeight, true) }
                    catch (error: Throwable) { original.recycle(); throw error }
                if (first !== original) original.recycle()
                val times = ArrayList<Double>()
                val preprocessing = ArrayList<Double>()
                val postprocessing = ArrayList<Double>()
                val totals = ArrayList<Double>()
                val measured = JSONArray()
                try {
                    budget(); detector.detect(first) // one full-pipeline warm-up after both parity samples
                    repeat(measuredRuns) {
                        budget()
                        val before = SystemClock.elapsedRealtimeNanos()
                        val output = detector.detect(first)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0
                        assertTrue(output.available && output.values.size == 128 * 128)
                        times.add(output.inferenceMs); preprocessing.add(output.preprocessMs)
                        postprocessing.add(output.postprocessMs); totals.add(elapsed)
                        measured.put(JSONObject().put("preprocessMs", output.preprocessMs)
                            .put("inferenceMs", output.inferenceMs).put("postprocessMs", output.postprocessMs)
                            .put("detectWallMs", elapsed))
                    }
                } finally { first.recycle() }
                fun median(values: List<Double>) = values.sorted()[values.size / 2]
                report.put("warmupRuns", 1).put("measuredRuns", measuredRuns).put("timingSamples", measured)
                    .put("inferenceP50Ms", median(times)).put("inferenceMaxMs", times.maxOrNull())
                    .put("preprocessP50Ms", median(preprocessing)).put("postprocessP50Ms", median(postprocessing))
                    .put("detectWallP50Ms", median(totals)).put("detectWallMaxMs", totals.maxOrNull())
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                report.put("pssKb", memory.totalPss).put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                assertTrue("Inference timings finite", times.all { it.isFinite() && it > 0 })
            }
            report.put("status", "PASSED")
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("failure", error.toString())
            throw error
        } finally { checkpoint() }
    }
    @Test fun unsupportedXnnpackFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { OnnxDepthDetector(instrumentation.targetContext, true) }
    }
    /** One deployment, selectable order/repetition: -e depthThreads 1,2,4 -e depthRuns 5.
     * Reverse the order in a second invocation to reveal cache/thermal/order effects.
     */
    @Test fun cpuParityAndBoundedBenchmark() {
        val arguments = InstrumentationRegistry.getArguments()
        val threads = (arguments.getString("depthThreads") ?: "2").split(',').map { it.trim().toInt() }
        require(threads.isNotEmpty() && threads.size <= 3 && threads.distinct().size == threads.size && threads.all { it in listOf(1, 2, 4) })
        val runs = (arguments.getString("depthRuns") ?: "5").toInt()
        require(runs == 3 || runs == 5)
        val label = arguments.getString("depthLabel") ?: "baseline"
        require(label.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        val width = (arguments.getString("depthWidth") ?: "467").toInt()
        val height = (arguments.getString("depthHeight") ?: "832").toInt()
        require(width in 128..2048 && height in 128..2048)
        val deadline = SystemClock.elapsedRealtime() + 90_000
        for (count in threads) runProvider(count, runs, deadline, label, width, height)
    }
}
