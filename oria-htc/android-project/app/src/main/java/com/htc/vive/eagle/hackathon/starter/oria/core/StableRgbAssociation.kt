package com.htc.vive.eagle.hackathon.starter.oria.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RgbTrackingMode { LEGACY_IOU, STABLE_RGB_V2 }

/** Geometric continuity only; none of these states establishes a physical identity. */
enum class RgbAssociationStatus { NEW, LEGACY_IOU, UNAMBIGUOUS_IOU, MOTION_RECOVERY, AMBIGUOUS_NEW }

/** A bounded, one-step motion hypothesis, never a synthesized observation. */
internal data class RgbAssociationInput(
    val id: Long,
    val detection: Detection,
    val observedAtMs: Long,
    val previousBox: Box?,
    val previousObservedAtMs: Long?,
)

internal data class RgbAssociationMatch(val trackId: Long, val detectionIndex: Int, val status: RgbAssociationStatus)
internal data class RgbAssociationResult(
    val matches: List<RgbAssociationMatch>,
    val retiredAmbiguousTracks: Set<Long>,
    val ambiguousNewDetections: Set<Int>,
)

/** Conservative alternative to greedy IoU. Constants are provisional RGB geometry gates, not
 * thresholds fitted to a detector class. Candidate mode stays opt-in until broader validation.
 * Ties and competing hypotheses reset confirmation instead of lending it to another object.
 */
internal object StableRgbAssociation {
    private const val MINIMUM_SCORE_SEPARATION = .12f
    private const val MAXIMUM_SHAPE_RATIO = 2f
    private const val MAXIMUM_MOTION_SHAPE_RATIO = 1.5f
    private const val MAXIMUM_PREDICTION_STEPS = 1.5f
    private const val MAXIMUM_TRANSLATION = .25f

    private data class Link(val trackId: Long, val detectionIndex: Int, val iou: Float,
                            val predictedIou: Float?, val score: Float)

    fun associate(tracks: List<RgbAssociationInput>, detections: List<Detection>, nowMs: Long,
                  iouThreshold: Float): RgbAssociationResult {
        val predictions = tracks.associate { it.id to predict(it, nowMs) }
        val links = tracks.flatMap { track ->
            detections.mapIndexedNotNull { index, detection ->
                if (track.detection.classId != detection.classId ||
                    shapeRatio(track.detection.box, detection.box) > MAXIMUM_SHAPE_RATIO) return@mapIndexedNotNull null
                val iou = RgbAlertPolicy.intersectionOverUnion(track.detection.box, detection.box)
                val predictedIou = predictions[track.id]?.let { RgbAlertPolicy.intersectionOverUnion(it, detection.box) }
                val recovery = predictedIou != null && predictedIou >= iouThreshold &&
                    shapeRatio(track.detection.box, detection.box) <= MAXIMUM_MOTION_SHAPE_RATIO
                if (iou < iouThreshold && !recovery) null
                else Link(track.id, index, iou, predictedIou, max(iou, predictedIou ?: 0f))
            }
        }
        val order = compareByDescending<Link> { it.score }.thenBy { it.trackId }.thenBy { it.detectionIndex }
        val byTrack = links.groupBy { it.trackId }.mapValues { it.value.sortedWith(order) }
        val byDetection = links.groupBy { it.detectionIndex }.mapValues { it.value.sortedWith(order) }
        val ambiguousTracks = mutableSetOf<Long>()
        for ((id, candidates) in byTrack) {
            if (candidates.size < 2) continue
            val rawBest = candidates.maxBy { it.iou }
            val motionBest = candidates.maxBy { it.predictedIou ?: 0f }
            // With no motion history, two plausible continuations are explicitly unresolved.
            if (predictions[id] == null || candidates[0].score - candidates[1].score < MINIMUM_SCORE_SEPARATION ||
                (rawBest.iou >= iouThreshold && (motionBest.predictedIou ?: 0f) >= iouThreshold &&
                    rawBest.detectionIndex != motionBest.detectionIndex)) ambiguousTracks += id
        }
        for (candidates in byDetection.values) {
            if (candidates.size > 1 && candidates[0].score - candidates[1].score < MINIMUM_SCORE_SEPARATION) {
                ambiguousTracks += candidates.map { it.trackId }
            }
        }
        val matches = byTrack.values.mapNotNull { candidates ->
            val best = candidates.first()
            if (best.trackId in ambiguousTracks || byDetection.getValue(best.detectionIndex).first().trackId != best.trackId) null
            else RgbAssociationMatch(best.trackId, best.detectionIndex,
                if (best.iou < iouThreshold) RgbAssociationStatus.MOTION_RECOVERY else RgbAssociationStatus.UNAMBIGUOUS_IOU)
        }
        // Do not revive an unresolved old ID on the next frame and transfer its confirmation.
        val matchedIds = matches.map { it.trackId }.toSet()
        val retired = byTrack.keys - matchedIds
        val matchedDetections = matches.map { it.detectionIndex }.toSet()
        return RgbAssociationResult(matches, retired,
            links.filter { it.trackId in retired && it.detectionIndex !in matchedDetections }.map { it.detectionIndex }.toSet())
    }

    private fun predict(track: RgbAssociationInput, nowMs: Long): Box? {
        val previous = track.previousBox ?: return null
        val previousAt = track.previousObservedAtMs ?: return null
        val dt = track.observedAtMs - previousAt
        val age = nowMs - track.observedAtMs
        if (dt <= 0 || age <= 0 || age.toFloat() / dt > MAXIMUM_PREDICTION_STEPS ||
            shapeRatio(previous, track.detection.box) > MAXIMUM_MOTION_SHAPE_RATIO) return null
        val box = track.detection.box
        val steps = age.toFloat() / dt
        val dx = (box.centerX - previous.centerX) * steps
        val dy = (box.centerY - previous.centerY) * steps
        if (abs(dx) > MAXIMUM_TRANSLATION || abs(dy) > MAXIMUM_TRANSLATION) return null
        // Keep size unchanged: extrapolating scale amplifies detector jitter and clipping.
        return Box(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
    }

    private fun shapeRatio(a: Box, b: Box): Float = max(
        max(a.width, b.width) / min(a.width, b.width).coerceAtLeast(.000001f),
        max(a.height, b.height) / min(a.height, b.height).coerceAtLeast(.000001f))
}
