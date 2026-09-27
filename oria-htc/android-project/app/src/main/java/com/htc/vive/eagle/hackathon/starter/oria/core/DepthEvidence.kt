package com.htc.vive.eagle.hackathon.starter.oria.core

enum class OriaDepthUnavailableReason {
    DISABLED, MODEL_MISSING, MODEL_ERROR, STALE, INVALID_MAP, LOW_CONFIDENCE
}

enum class OriaDepthTrend { APPROACHING, STABLE, RECEDING, UNKNOWN }
enum class OriaDepthRegion { DETECTION_LOWER_CENTER, FRONTAL_CORRIDOR }

/** Relative inverse depth: larger values are nearer. It never represents metres. */
data class RelativeDepthMap(
    val width: Int,
    val height: Int,
    val inverseDepth: FloatArray,
    val observedAtMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    init {
        require(width > 0 && height > 0 && inverseDepth.size == width * height)
        require(observedAtMs >= 0 && sourceWidth > 0 && sourceHeight > 0)
    }
}

data class OriaDistanceEvidence(
    val relativeInverseDepth: Float?,
    val relativeProximity: Float?,
    val confidence: Float,
    val ageMs: Long,
    val trend: OriaDepthTrend,
    val region: OriaDepthRegion,
    val sampleCount: Int,
    val unavailableReason: OriaDepthUnavailableReason? = null,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f && ageMs >= 0 && sampleCount >= 0)
        require(relativeInverseDepth?.isFinite() != false)
        require(relativeProximity == null || relativeProximity.isFinite() && relativeProximity in 0f..1f)
        require((unavailableReason == null) == (relativeProximity != null))
    }
    val available: Boolean get() = unavailableReason == null
}
