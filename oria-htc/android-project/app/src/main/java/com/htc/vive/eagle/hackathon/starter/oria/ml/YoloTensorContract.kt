package com.htc.vive.eagle.hackathon.starter.oria.ml

import com.htc.vive.eagle.hackathon.starter.oria.core.Box
import com.htc.vive.eagle.hackathon.starter.oria.core.Detection
import kotlin.math.abs
import kotlin.math.min

/** Fixed-square letterbox. No camera rotation or mirror is inferred here. */
data class LetterboxTransform(val sourceWidth: Int, val sourceHeight: Int, val size: Int = 416) {
    init { require(sourceWidth > 0 && sourceHeight > 0 && size > 0) }
    val scale = min(size.toDouble() / sourceWidth, size.toDouble() / sourceHeight)
    val resizedWidth = Math.rint(sourceWidth * scale).toInt().coerceAtLeast(1)
    val resizedHeight = Math.rint(sourceHeight * scale).toInt().coerceAtLeast(1)
    val left = Math.rint((size - resizedWidth) / 2.0 - 0.1).toInt()
    val top = Math.rint((size - resizedHeight) / 2.0 - 0.1).toInt()

    // The actual raster dimensions are rounded independently. Invert that transform, not
    // the ideal uniform scale: its subpixel error can flip the 0.39/0.61 direction zones.
    // xyxy uses continuous image-edge coordinates; resize half-pixel offsets do not apply here.
    fun toCameraBox(x1: Float, y1: Float, x2: Float, y2: Float): Box = Box(
        ((x1 - left) / resizedWidth).coerceIn(0f, 1f),
        ((y1 - top) / resizedHeight).coerceIn(0f, 1f),
        ((x2 - left) / resizedWidth).coerceIn(0f, 1f),
        ((y2 - top) / resizedHeight).coerceIn(0f, 1f),
    )
}

/** Contract of the shipped YOLO26 end-to-end export: xyxy pixels, score, class. */
object YoloTensorContract {
    const val INPUT_SIZE = 416
    const val MAX_DETECTIONS = 300
    val CLASSES = listOf("person", "vehicle", "bike_scooter", "pole", "traffic_light", "traffic_sign")

    fun decode(values: FloatArray, transform: LetterboxTransform, minimumConfidence: Float = 0.70f): List<Detection> {
        require(values.size == MAX_DETECTIONS * 6) { "Expected [1,300,6] end-to-end output" }
        require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f)
        val detections = ArrayList<Detection>()
        for (row in 0 until MAX_DETECTIONS) {
            val offset = row * 6
            require((0..5).all { values[offset + it].isFinite() }) { "Non-finite model output" }
            val score = values[offset + 4]
            require(score in 0f..1f) { "Invalid model confidence" }
            val classValue = values[offset + 5]
            val classId = classValue.toInt()
            require(classId in CLASSES.indices && abs(classValue - classId) < 0.00001f) { "Invalid model class" }
            if (score < minimumConfidence) continue
            val box = transform.toCameraBox(values[offset], values[offset + 1], values[offset + 2], values[offset + 3])
            if (box.width <= 0f || box.height <= 0f) continue
            detections.add(Detection(classId, score, box))
        }
        // No NMS and no top-eight cut: tracking and visual qualification happen downstream.
        return detections
    }
}
