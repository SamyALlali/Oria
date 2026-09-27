package com.htc.vive.eagle.hackathon.starter.oria.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Current-frame relative inverse depth only; never distance or approach speed. */
data class DepthInference(
    val values: FloatArray,
    val width: Int = 128,
    val height: Int = 128,
    val qualityUsable: Boolean,
    val qualityReason: String?,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val postprocessMs: Double,
    val available: Boolean,
    val unavailableReason: String?,
    val p02: Double,
    val p98: Double,
)

/** Serialized worker-only runtime. Caller owns freshness/session cancellation around detect(). */
class OnnxDepthDetector(context: Context, useXnnpack: Boolean = false, numThreads: Int = 4) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tensorBuffer = ByteBuffer.allocateDirect(3 * 252 * 252 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed = false
    val requestedProvider = "CPU"
    val graphOptimization = "ALL_OPT"
    @Volatile var lastInferenceMs: Double = 0.0
        private set

    init {
        require(numThreads in 1..8)
        require(!useXnnpack) { "Depth XNNPACK disabled after Android graph/Resize failures; use CPU" }
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getJSONObject("artifact").getString("sha256") == MODEL_SHA256) { "Depth manifest checksum contract mismatch" }
        require(manifest.getJSONObject("artifact").getLong("bytes") == MODEL_BYTES) { "Depth model size contract mismatch" }
        val file = verifiedModelFile(context)
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(numThreads)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            session = environment.createSession(file.absolutePath, options)
        } finally { options.close() }
        try {
            require(session.inputNames == setOf("pixel_values") && session.outputNames == setOf("predicted_depth"))
            val i = session.inputInfo.getValue("pixel_values").info as TensorInfo
            val o = session.outputInfo.getValue("predicted_depth").info as TensorInfo
            require(i.type == OnnxJavaType.FLOAT && i.shape.contentEquals(longArrayOf(1, 3, 252, 252))) { "Invalid depth input" }
            require(o.type == OnnxJavaType.FLOAT && o.shape.contentEquals(longArrayOf(1, 252, 252))) { "Invalid depth output" }
        } catch (failure: Throwable) { session.close(); throw failure }
    }

    @Synchronized fun detect(bitmap: Bitmap): DepthInference {
        check(!closed) { "Depth detector closed" }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        require(bitmap.width.toLong() * bitmap.height <= 16_777_216) { "Bitmap too large" }
        val start = SystemClock.elapsedRealtimeNanos()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val input = DepthImageProcessing.preprocess(pixels, bitmap.width, bitmap.height)
        val quality = DepthImageProcessing.quality(pixels, bitmap.width, bitmap.height)
        val preprocessMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val raw = runTensor(input)
        val postStart = SystemClock.elapsedRealtimeNanos()
        val normalized = DepthImageProcessing.normalize(DepthImageProcessing.compact(raw))
        val postMs = (SystemClock.elapsedRealtimeNanos() - postStart) / 1_000_000.0
        return DepthInference(normalized.values, qualityUsable = quality.usable, qualityReason = quality.reason,
            preprocessMs = preprocessMs, inferenceMs = lastInferenceMs, postprocessMs = postMs,
            available = normalized.available, unavailableReason = normalized.reason, p02 = normalized.p02, p98 = normalized.p98)
    }

    /** Raw FP32 output for bounded provider and pipeline parity tests. */
    @Synchronized fun inferTensor(input: FloatArray): FloatArray {
        require(input.size == 3 * 252 * 252 && input.all { it.isFinite() && it in -3f..3f })
        return runTensor(input)
    }

    private fun runTensor(input: FloatArray): FloatArray {
        check(!closed) { "Depth detector closed" }
        tensorBuffer.clear(); tensorBuffer.put(input); tensorBuffer.rewind()
        val start = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(environment, tensorBuffer, longArrayOf(1, 3, 252, 252)).use { tensor ->
            session.run(mapOf("pixel_values" to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                require(output.info.shape.contentEquals(longArrayOf(1, 252, 252)))
                val values = FloatArray(252 * 252)
                output.floatBuffer.get(values)
                require(values.all { it.isFinite() }) { "Non-finite depth output" }
                lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                return values
            }
        }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; session.close() }
        // OrtEnvironment is shared with YOLO and intentionally remains alive.
    }

    companion object {
        const val MODEL_ASSET = "oria/depth/depth-anything-v2-small-252-fp32.onnx"
        const val MANIFEST_ASSET = "oria/depth/model_manifest.json"
        const val MODEL_SHA256 = "3467d320122172aa5e28a961ff2a1ee6e9e3d52db6f0fa8b04ab663efa4c0cba"
        const val MODEL_BYTES = 99117601L

        private fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }

        @Synchronized private fun verifiedModelFile(context: Context): File {
            val dir = File(context.noBackupFilesDir, "depth_model").apply { check(isDirectory || mkdirs()) }
            val file = File(dir, "$MODEL_SHA256.onnx")
            if (file.isFile) {
                require(file.length() == MODEL_BYTES && digest(file) == MODEL_SHA256) { "Cached depth model checksum mismatch" }
                return file
            }
            val pending = File.createTempFile("depth-", ".pending", dir)
            try {
                context.assets.open(MODEL_ASSET).use { input -> pending.outputStream().buffered().use { input.copyTo(it) } }
                require(pending.length() == MODEL_BYTES && digest(pending) == MODEL_SHA256) { "Depth asset checksum mismatch" }
                check(pending.renameTo(file)) { "Cannot finalize verified depth model" }
                return file
            } finally { pending.delete() }
        }
    }
}
