package com.htc.vive.eagle.hackathon.starter.echonav.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.htc.vive.eagle.hackathon.starter.echonav.core.Detection
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Synchronous and serialized. Construct, detect and close on a worker, never on a camera callback. */
class OnnxObjectDetector(
    context: Context,
    useXnnpack: Boolean = false,
    numThreads: Int = 2,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input = FloatArray(3 * 416 * 416)
    private val tensorBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed = false
    val requestedProvider = if (useXnnpack) "XNNPACK with CPU fallback" else "CPU"
    @Volatile var lastPreprocessMs: Double = 0.0
        private set
    @Volatile var lastInferenceMs: Double = 0.0
        private set
    /** Optional 300x6 raw snapshot for EchoTest. Read-only to callers; never reused by inference. */
    @Volatile var lastRawOutput: FloatArray? = null
        private set

    init {
        require(numThreads > 0)
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val manifest = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JSONObject(it.readText()) }
        val digest = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        require(digest == manifest.getString("onnx_sha256")) { "Model asset checksum mismatch" }
        val names = manifest.getJSONArray("classes")
        require((0 until names.length()).map { names.getString(it) } == YoloTensorContract.CLASSES) { "Class mapping mismatch" }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(if (useXnnpack) 1 else numThreads)
            options.setInterOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (useXnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to numThreads.toString()))
            session = environment.createSession(model, options)
        } finally {
            options.close()
        }
        try {
            require(session.inputNames == setOf("images")) { "Unexpected model input name" }
            require(session.outputNames == setOf("output0")) { "Unexpected model output name" }
            val i = session.inputInfo.getValue("images").info as TensorInfo
            val o = session.outputInfo.getValue("output0").info as TensorInfo
            require(i.type == OnnxJavaType.FLOAT && i.shape.contentEquals(longArrayOf(1, 3, 416, 416))) { "Unexpected model input contract" }
            require(o.type == OnnxJavaType.FLOAT && o.shape.contentEquals(longArrayOf(1, 300, 6))) { "Unexpected model output contract" }
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    @Synchronized fun detect(bitmap: Bitmap, captureRawOutput: Boolean = false): List<Detection> {
        check(!closed) { "Detector is closed" }
        lastRawOutput = null
        val start = SystemClock.elapsedRealtimeNanos()
        val transform = LetterboxPreprocessor.preprocess(bitmap, input)
        lastPreprocessMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val output = runTensor(input)
        val detections = YoloTensorContract.decode(output, transform)
        // runTensor already owns a new array. Retain it only while recording; no second inference,
        // allocation, confidence change or additional filtering on the normal detection path.
        if (captureRawOutput) lastRawOutput = output
        return detections
    }

    /** Exact tensor entry point for on-device export parity and provider benchmarks. */
    @Synchronized fun inferTensor(values: FloatArray): FloatArray {
        require(values.size == input.size && values.all { it.isFinite() && it in 0f..1f })
        return runTensor(values)
    }

    private fun runTensor(values: FloatArray): FloatArray {
        check(!closed) { "Detector is closed" }
        tensorBuffer.clear()
        tensorBuffer.put(values)
        tensorBuffer.rewind()
        val start = SystemClock.elapsedRealtimeNanos()
        OnnxTensor.createTensor(environment, tensorBuffer, longArrayOf(1, 3, 416, 416)).use { tensor ->
            session.run(mapOf("images" to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val valuesOut = FloatArray(300 * 6)
                output.floatBuffer.get(valuesOut)
                lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                return valuesOut
            }
        }
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            lastRawOutput = null
            session.close()
        }
        // OrtEnvironment is process-wide and can be shared by other detector instances.
    }

    companion object {
        const val MODEL_ASSET = "echonav/echonav_silmo_fp32.onnx"
        const val MANIFEST_ASSET = "echonav/model_manifest.json"
    }
}
