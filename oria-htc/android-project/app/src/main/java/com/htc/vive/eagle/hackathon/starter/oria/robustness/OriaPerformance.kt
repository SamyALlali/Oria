package com.htc.vive.eagle.hackathon.starter.oria.robustness

import com.htc.vive.eagle.hackathon.starter.oria.dashboard.percentileMillis

data class OriaHealthSample(
    val memoryUsedBytes: Long,
    val memoryLimitBytes: Long,
    val cpuPercent: Double,
    val temperatureCelsius: Double?,
    val batteryPercent: Int?,
    val thermalStatus: Int?,
)

data class OriaPerformanceSnapshot(
    val receivedFrames: Long = 0,
    val decisions: Long = 0,
    val rejectedFrames: Long = 0,
    val cameraToDecisionP50Ms: Long = 0,
    val cameraToDecisionP95Ms: Long = 0,
    val decisionToAudioP50Ms: Long = 0,
    val decisionToAudioP95Ms: Long = 0,
    val usefulFps: Double = 0.0,
    val health: OriaHealthSample? = null,
)

class OriaPerformanceMonitor(private val maximumSamples: Int = 2_048) {
    private val cameraDecision = ArrayDeque<Long>()
    private val decisionAudio = ArrayDeque<Long>()
    private var received = 0L
    private var decisions = 0L
    private var rejected = 0L
    private var beganAtMs: Long? = null
    private var health: OriaHealthSample? = null

    init { require(maximumSamples in 16..100_000) }

    fun reset(atMs: Long) {
        require(atMs >= 0)
        cameraDecision.clear(); decisionAudio.clear()
        received = 0; decisions = 0; rejected = 0; beganAtMs = atMs; health = null
    }

    fun recordReceived() { received++ }

    fun recordDecision(receivedAtMs: Long, decidedAtMs: Long, accepted: Boolean) {
        require(receivedAtMs >= 0 && decidedAtMs >= receivedAtMs)
        if (accepted) {
            decisions++
            append(cameraDecision, decidedAtMs - receivedAtMs)
        } else rejected++
    }

    fun recordAudio(decidedAtMs: Long, androidStartAtMs: Long) {
        require(decidedAtMs >= 0 && androidStartAtMs >= decidedAtMs)
        append(decisionAudio, androidStartAtMs - decidedAtMs)
    }

    fun recordHealth(sample: OriaHealthSample) {
        require(sample.memoryUsedBytes >= 0 && sample.memoryLimitBytes > 0 && sample.cpuPercent >= 0)
        health = sample
    }

    fun snapshot(nowMs: Long): OriaPerformanceSnapshot {
        val began = beganAtMs ?: nowMs
        val elapsed = (nowMs - began).coerceAtLeast(1)
        return OriaPerformanceSnapshot(received, decisions, rejected,
            percentileMillis(cameraDecision, .5), percentileMillis(cameraDecision, .95),
            percentileMillis(decisionAudio, .5), percentileMillis(decisionAudio, .95),
            decisions * 1_000.0 / elapsed, health)
    }

    private fun append(queue: ArrayDeque<Long>, value: Long) {
        queue.addLast(value)
        while (queue.size > maximumSamples) queue.removeFirst()
    }
}

enum class OriaLoadLevel { NORMAL, REDUCED, PROTECTED }

data class OriaLoadProfile(
    val level: OriaLoadLevel,
    val dashboardIntervalMs: Long,
    val depthStride: Int,
    val diagnosticsStride: Int,
    val detectionStride: Int = 1,
    val reason: String,
)

/** Sheds optional work only. The semantic detector always keeps stride 1. */
class OriaLoadController(private val healthySamplesToRecover: Int = 3) {
    private var profile = normal()
    private var healthy = 0

    fun reset(): OriaLoadProfile { profile = normal(); healthy = 0; return profile }

    fun evaluate(sample: OriaHealthSample, cameraDecisionP95Ms: Long): OriaLoadProfile {
        val memoryRatio = sample.memoryUsedBytes.toDouble() / sample.memoryLimitBytes
        val target = when {
            sample.batteryPercent?.let { it <= 10 } == true ||
                sample.temperatureCelsius?.let { it >= 45.0 } == true ||
                sample.thermalStatus?.let { it >= 4 } == true || memoryRatio >= .90 ||
                sample.cpuPercent >= 95 || cameraDecisionP95Ms >= 500 -> protected("charge critique")
            sample.batteryPercent?.let { it <= 20 } == true ||
                sample.temperatureCelsius?.let { it >= 41.0 } == true ||
                sample.thermalStatus?.let { it >= 2 } == true || memoryRatio >= .78 ||
                sample.cpuPercent >= 80 || cameraDecisionP95Ms >= 350 -> reduced("charge élevée")
            else -> normal()
        }
        if (target.level.rank > profile.level.rank) { profile = target; healthy = 0 }
        else if (target.level.rank < profile.level.rank) {
            healthy++
            if (healthy >= healthySamplesToRecover) { profile = target; healthy = 0 }
        } else { profile = target; healthy = 0 }
        check(profile.detectionStride == 1)
        return profile
    }

    private val OriaLoadLevel.rank: Int get() = ordinal
    private fun normal() = OriaLoadProfile(OriaLoadLevel.NORMAL, 250, 1, 1, reason = "profil nominal")
    private fun reduced(reason: String) = OriaLoadProfile(OriaLoadLevel.REDUCED, 500, 2, 2, reason = reason)
    private fun protected(reason: String) = OriaLoadProfile(OriaLoadLevel.PROTECTED, 1_000, 4, 4, reason = reason)
}
