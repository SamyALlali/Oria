package com.htc.vive.eagle.hackathon.starter.oria.core

import kotlin.math.ceil
import kotlin.math.floor

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

/** Robust image-plane adapter. Calibration and thresholds stay here, outside alert policy. */
class RelativeDepthEvidenceAdapter(
    private val maxAgeMs: Long = 500,
    private val minimumSamples: Int = 12,
    private val minimumConfidence: Float = .35f,
    private val minimumFrameSpan: Float = 1e-4f,
    private val trendDelta: Float = .08f,
) {
    private data class History(val proximity: Float, val observedAtMs: Long)
    private val history = mutableMapOf<Long, History>()

    init {
        require(maxAgeMs > 0 && minimumSamples > 0 && minimumConfidence in 0f..1f)
        require(minimumFrameSpan > 0f && trendDelta > 0f)
    }

    @Synchronized fun reset() = history.clear()

    @Synchronized fun evidence(trackId: Long, box: Box, map: RelativeDepthMap,
                               nowMs: Long): OriaDistanceEvidence =
        analyze(trackId, lowerCentral(box), OriaDepthRegion.DETECTION_LOWER_CENTER, map, nowMs)

    @Synchronized fun frontalObstruction(map: RelativeDepthMap, nowMs: Long): OriaDistanceEvidence =
        analyze(GENERIC_TRACK, Box(.35f, .52f, .65f, .94f), OriaDepthRegion.FRONTAL_CORRIDOR, map, nowMs)

    fun unavailable(reason: OriaDepthUnavailableReason, nowMs: Long, observedAtMs: Long = nowMs,
                    region: OriaDepthRegion = OriaDepthRegion.FRONTAL_CORRIDOR): OriaDistanceEvidence =
        OriaDistanceEvidence(null, null, 0f, (nowMs - observedAtMs).coerceAtLeast(0),
            OriaDepthTrend.UNKNOWN, region, 0, reason)

    private fun analyze(trackId: Long, region: Box, kind: OriaDepthRegion,
                        map: RelativeDepthMap, nowMs: Long): OriaDistanceEvidence {
        val age = (nowMs - map.observedAtMs).coerceAtLeast(0)
        if (nowMs < map.observedAtMs || age > maxAgeMs) {
            history.remove(trackId)
            return OriaDistanceEvidence(null, null, 0f, age, OriaDepthTrend.UNKNOWN, kind, 0,
                OriaDepthUnavailableReason.STALE)
        }
        val global = map.inverseDepth.filter { it.isFinite() }.sorted()
        if (global.size < minimumSamples || global.size * 4 < map.inverseDepth.size * 3) {
            history.remove(trackId)
            return OriaDistanceEvidence(null, null, 0f, age, OriaDepthTrend.UNKNOWN, kind, 0,
                OriaDepthUnavailableReason.INVALID_MAP)
        }
        val low = percentile(global, .10f)
        val high = percentile(global, .90f)
        val span = high - low
        val samples = samples(map, region).sorted()
        if (samples.size < minimumSamples || !span.isFinite() || span < minimumFrameSpan) {
            history.remove(trackId)
            return OriaDistanceEvidence(samples.takeIf { it.isNotEmpty() }?.let { percentile(it, .5f) }, null,
                0f, age, OriaDepthTrend.UNKNOWN, kind, samples.size,
                if (span.isFinite()) OriaDepthUnavailableReason.LOW_CONFIDENCE else OriaDepthUnavailableReason.INVALID_MAP)
        }
        val median = percentile(samples, .5f)
        val iqr = percentile(samples, .75f) - percentile(samples, .25f)
        val expected = expectedSampleCount(map, region).coerceAtLeast(1)
        val coverage = (samples.size.toFloat() / expected).coerceIn(0f, 1f)
        val stability = (1f - iqr / span).coerceIn(0f, 1f)
        val confidence = coverage * stability
        val proximity = ((median - low) / span).coerceIn(0f, 1f)
        if (confidence < minimumConfidence) {
            history.remove(trackId)
            return OriaDistanceEvidence(median, null, confidence, age, OriaDepthTrend.UNKNOWN, kind,
                samples.size, OriaDepthUnavailableReason.LOW_CONFIDENCE)
        }
        val previous = history.put(trackId, History(proximity, map.observedAtMs))
        val trend = if (previous == null || map.observedAtMs <= previous.observedAtMs) OriaDepthTrend.UNKNOWN else {
            val delta = proximity - previous.proximity
            when {
                delta >= trendDelta -> OriaDepthTrend.APPROACHING
                delta <= -trendDelta -> OriaDepthTrend.RECEDING
                else -> OriaDepthTrend.STABLE
            }
        }
        return OriaDistanceEvidence(median, proximity, confidence, age, trend, kind, samples.size)
    }

    private fun lowerCentral(box: Box): Box {
        val left = box.left.coerceIn(0f, 1f)
        val right = box.right.coerceIn(0f, 1f)
        val top = box.top.coerceIn(0f, 1f)
        val bottom = box.bottom.coerceIn(0f, 1f)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        return Box(left + width * .25f, top + height * .55f,
            right - width * .25f, top + height * .90f)
    }

    private fun samples(map: RelativeDepthMap, region: Box): List<Float> {
        val x0 = floor(region.left.coerceIn(0f, 1f) * map.width).toInt().coerceIn(0, map.width - 1)
        val x1 = ceil(region.right.coerceIn(0f, 1f) * map.width).toInt().coerceIn(x0 + 1, map.width)
        val y0 = floor(region.top.coerceIn(0f, 1f) * map.height).toInt().coerceIn(0, map.height - 1)
        val y1 = ceil(region.bottom.coerceIn(0f, 1f) * map.height).toInt().coerceIn(y0 + 1, map.height)
        val result = ArrayList<Float>((x1 - x0) * (y1 - y0))
        for (y in y0 until y1) for (x in x0 until x1) {
            map.inverseDepth[y * map.width + x].takeIf { it.isFinite() }?.let(result::add)
        }
        return result
    }

    private fun expectedSampleCount(map: RelativeDepthMap, region: Box): Int {
        val width = ceil(region.right.coerceIn(0f, 1f) * map.width).toInt() -
            floor(region.left.coerceIn(0f, 1f) * map.width).toInt()
        val height = ceil(region.bottom.coerceIn(0f, 1f) * map.height).toInt() -
            floor(region.top.coerceIn(0f, 1f) * map.height).toInt()
        return width.coerceAtLeast(1) * height.coerceAtLeast(1)
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        val position = fraction.coerceIn(0f, 1f) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val weight = position - lower
        return sorted[lower] * (1f - weight) + sorted[upper] * weight
    }

    companion object { private const val GENERIC_TRACK = Long.MIN_VALUE }
}
