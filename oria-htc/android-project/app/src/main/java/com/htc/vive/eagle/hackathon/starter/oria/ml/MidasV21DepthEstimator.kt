package com.htc.vive.eagle.hackathon.starter.oria.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.htc.vive.eagle.hackathon.starter.oria.core.RelativeDepthMap
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.floor

/** Direct full-frame resize: normalized source coordinates and output coordinates are identical. */
data class MidasV21Transform(val sourceWidth: Int, val sourceHeight: Int) {
    init { require(sourceWidth > 0 && sourceHeight > 0) }
    fun outputX(normalizedX: Float): Float = normalizedX.coerceIn(0f, 1f) * INPUT_SIZE
    fun outputY(normalizedY: Float): Float = normalizedY.coerceIn(0f, 1f) * INPUT_SIZE

    companion object { const val INPUT_SIZE = 256 }
}

/** Deterministic half-pixel bilinear RGB uint8 resize, then RGB / 255 NCHW. */
object MidasV21Preprocessor {
    fun preprocess(bitmap: Bitmap, destination: FloatArray): MidasV21Transform {
        require(!bitmap.isRecycled)
        val size = MidasV21Transform.INPUT_SIZE
        val plane = size * size
        require(destination.size == plane * 3)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until size) {
            val sourceY = ((y + .5) * bitmap.height / size - .5).coerceIn(0.0, bitmap.height - 1.0)
            val y0 = floor(sourceY).toInt()
            val y1 = (y0 + 1).coerceAtMost(bitmap.height - 1)
            val wy = sourceY - y0
            for (x in 0 until size) {
                val sourceX = ((x + .5) * bitmap.width / size - .5).coerceIn(0.0, bitmap.width - 1.0)
                val x0 = floor(sourceX).toInt()
                val x1 = (x0 + 1).coerceAtMost(bitmap.width - 1)
                val wx = sourceX - x0
                val p00 = pixels[y0 * bitmap.width + x0]
                val p01 = pixels[y0 * bitmap.width + x1]
                val p10 = pixels[y1 * bitmap.width + x0]
                val p11 = pixels[y1 * bitmap.width + x1]
                val index = y * size + x
                for (channel in 0..2) {
                    val shift = 16 - channel * 8
                    val a = ((p00 ushr shift) and 255) * (1 - wx) + ((p01 ushr shift) and 255) * wx
                    val b = ((p10 ushr shift) and 255) * (1 - wx) + ((p11 ushr shift) and 255) * wx
                    destination[channel * plane + index] =
                        floor(a * (1 - wy) + b * wy + .5).toFloat().coerceIn(0f, 255f) / 255f
                }
            }
        }
        return MidasV21Transform(bitmap.width, bitmap.height)
    }
}

/** Optional MiDaS v2.1 Small runner. Output is relative inverse depth, never metric distance. */
class MidasV21DepthEstimator(
    context: Context,
    useXnnpack: Boolean = true,
    numThreads: Int = 2,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input = FloatArray(3 * MidasV21Transform.INPUT_SIZE * MidasV21Transform.INPUT_SIZE)
    private val tensorBuffer = ByteBuffer.allocateDirect(input.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed = false
    val requestedProvider = if (useXnnpack) "XNNPACK with CPU fallback" else "CPU"
    @Volatile var lastPreprocessMs = 0.0
        private set
    @Volatile var lastInferenceMs = 0.0
        private set

    init {
        require(numThreads > 0)
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getString("license") == "MIT") { "Unexpected depth model license" }
        val model = materializeVerifiedModel(context, manifest)
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(if (useXnnpack) 1 else numThreads)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (useXnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to numThreads.toString()))
            session = environment.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
        try {
            require(session.inputNames == setOf(INPUT_NAME) && session.outputNames == setOf(OUTPUT_NAME)) {
                "Unexpected MiDaS tensor names"
            }
            val inputInfo = session.inputInfo.getValue(INPUT_NAME).info as TensorInfo
            val outputInfo = session.outputInfo.getValue(OUTPUT_NAME).info as TensorInfo
            require(inputInfo.type == OnnxJavaType.FLOAT &&
                inputInfo.shape.contentEquals(longArrayOf(1, 3, 256, 256))) { "Unexpected MiDaS input contract" }
            require(outputInfo.type == OnnxJavaType.FLOAT &&
                outputInfo.shape.contentEquals(longArrayOf(1, 256, 256))) { "Unexpected MiDaS output contract" }
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    @Synchronized fun estimate(bitmap: Bitmap, observedAtMs: Long): RelativeDepthMap {
        check(!closed) { "Depth estimator is closed" }
        val preprocessStarted = SystemClock.elapsedRealtimeNanos()
        val transform = MidasV21Preprocessor.preprocess(bitmap, input)
        lastPreprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStarted) / 1_000_000.0
        tensorBuffer.clear()
        tensorBuffer.put(input)
        tensorBuffer.rewind()
        val inferenceStarted = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(environment, tensorBuffer, longArrayOf(1, 3, 256, 256)).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val values = FloatArray(256 * 256)
                output.floatBuffer.get(values)
                lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStarted) / 1_000_000.0
                return RelativeDepthMap(256, 256, values, observedAtMs,
                    transform.sourceWidth, transform.sourceHeight)
            }
        }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; session.close() }
    }

    private fun materializeVerifiedModel(context: Context, manifest: JSONObject): File {
        val expectedDigest = manifest.getString("onnx_sha256")
        val expectedBytes = manifest.getLong("onnx_bytes")
        val directory = File(context.codeCacheDir, "oria-depth").apply { mkdirs() }
        val destination = File(directory, "$expectedDigest.onnx")
        if (destination.isFile && destination.length() == expectedBytes && sha256(destination) == expectedDigest) {
            return destination
        }
        val temporary = File(directory, "$expectedDigest.tmp")
        temporary.delete()
        context.assets.open(MODEL_ASSET).use { source ->
            temporary.outputStream().buffered().use { output -> source.copyTo(output) }
        }
        require(temporary.length() == expectedBytes && sha256(temporary) == expectedDigest) {
            temporary.delete(); "Depth model asset checksum mismatch"
        }
        require(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            temporary.delete(); "Cannot publish verified depth model"
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(128 * 1024)
                while (stream.read(buffer) >= 0) Unit
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_ASSET = "oria/depth/midas_v21_small_256.onnx"
        const val MANIFEST_ASSET = "oria/depth/model_manifest.json"
        private const val INPUT_NAME = "0"
        private const val OUTPUT_NAME = "797"
    }
}
