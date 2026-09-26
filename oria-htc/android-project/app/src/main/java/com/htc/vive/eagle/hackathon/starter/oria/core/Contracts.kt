package com.htc.vive.eagle.hackathon.starter.oria.core

/** Coordinates in the upright, unmirrored camera image, normalized to [0, 1]. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

data class Detection(val classId: Int, val confidence: Float, val box: Box)

/** observedAtMs is the correlated phone reception time, never decision/replay wall time. */
data class DetectionFrame(
    val sessionId: Long,
    val frameId: Long,
    val observedAtMs: Long,
    val detections: List<Detection>,
)
