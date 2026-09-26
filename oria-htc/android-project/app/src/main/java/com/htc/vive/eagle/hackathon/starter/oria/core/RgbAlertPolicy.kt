package com.htc.vive.eagle.hackathon.starter.oria.core

import kotlin.math.max
import kotlin.math.min

/** Camera-relative directions, ported from Oria's CorridorZone. */
enum class RgbZone(val voiceSuffix: String) {
    LEFT("avant-gauche"), CENTER("devant"), RIGHT("avant-droite");

    companion object {
        fun fromCenterX(x: Float): RgbZone = when {
            x < 0.39f -> LEFT
            x > 0.61f -> RIGHT
            else -> CENTER
        }
    }
}

enum class RgbCategory(val classId: Int, val label: String, val alertable: Boolean,
                       val confidenceThreshold: Float, val priorityBias: Float) {
    PERSON(0, "Piéton", true, 0.84f, 1f),
    VEHICLE(1, "Véhicule", true, 0.82f, 1.5f),
    BIKE_SCOOTER(2, "Deux-roues", true, 0.82f, 1.35f),
    POLE(3, "Poteau", true, 0.86f, 0.85f),
    TRAFFIC_LIGHT(4, "Feu", false, 1f, 0.2f),
    TRAFFIC_SIGN(5, "Panneau", false, 1f, 0.2f);

    companion object {
        fun fromClassId(id: Int): RgbCategory? = entries.firstOrNull { it.classId == id }
    }
}

/** Pure portions of OriaApp.swift: zones, visual qualification and time boundaries.
 * All scores are unitless. Neither box area nor image position is a metric distance.
 */
object RgbAlertPolicy {
    fun validDetection(detection: Detection): Boolean {
        val b = detection.box
        return RgbCategory.fromClassId(detection.classId) != null &&
            detection.confidence.isFinite() && detection.confidence in 0f..1f &&
            listOf(b.left, b.top, b.right, b.bottom).all { it.isFinite() && it in 0f..1f } &&
            b.right > b.left && b.bottom > b.top
    }

    /** Initial thresholds copied from qualifiesForVisualOnlySemanticCue in Swift. */
    fun qualifies(detection: Detection, confidenceHysteresis: Float = 0f): Boolean {
        if (!validDetection(detection)) return false
        val category = RgbCategory.fromClassId(detection.classId) ?: return false
        if (!category.alertable || detection.confidence < category.confidenceThreshold - confidenceHysteresis) {
            return false
        }
        val area = detection.box.area
        val bottom = detection.box.bottom
        val center = RgbZone.fromCenterX(detection.box.centerX) == RgbZone.CENTER
        return when (category) {
            RgbCategory.VEHICLE -> bottom >= 0.5f && (area >= 0.035f || (center && area >= 0.026f))
            RgbCategory.BIKE_SCOOTER -> bottom >= 0.52f && (area >= 0.032f || (center && area >= 0.024f))
            RgbCategory.PERSON -> bottom >= 0.5f && (area >= 0.026f || (center && area >= 0.02f))
            RgbCategory.POLE -> bottom >= 0.45f && (area >= 0.014f || (center && area >= 0.01f))
            RgbCategory.TRAFFIC_LIGHT, RgbCategory.TRAFFIC_SIGN -> false
        }
    }

    /** RGB adaptation: category bias + Swift visual cues + confidence, with no metre unit. */
    fun visualPriority(detection: Detection): Float {
        val category = RgbCategory.fromClassId(detection.classId) ?: return Float.NEGATIVE_INFINITY
        val box = detection.box
        val sizeCue = min(0.8f, box.area * 7.5f)
        val bottomCue = max(0f, box.bottom - 0.48f) * 1.35f
        val centerCue = if (RgbZone.fromCenterX(box.centerX) == RgbZone.CENTER) 0.14f else 0f
        return category.priorityBias + sizeCue + bottomCue + centerCue + detection.confidence * 0.25f
    }

    fun intersectionOverUnion(a: Box, b: Box): Float {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = width * height
        val union = a.area + b.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    fun isFresh(observedAtMs: Long, nowMs: Long, lifetimeMs: Long): Boolean =
        observedAtMs <= nowMs && nowMs - observedAtMs <= lifetimeMs

    fun hasExpired(observedAtMs: Long, nowMs: Long, lifetimeMs: Long): Boolean =
        nowMs >= observedAtMs && nowMs - observedAtMs > lifetimeMs
}
