package com.htc.vive.eagle.hackathon.starter.oria.robustness

import org.junit.Assert.*
import org.junit.Test

class OriaPerformanceTest {
    @Test fun measuresBothLatencySegmentsUsefulFpsAndRejections() {
        val monitor = OriaPerformanceMonitor(16)
        monitor.reset(1_000)
        repeat(2) { monitor.recordReceived() }
        monitor.recordDecision(1_000, 1_010, true)
        monitor.recordDecision(1_100, 1_120, true)
        monitor.recordDecision(1_200, 1_250, false)
        monitor.recordAudio(1_010, 1_015)
        monitor.recordAudio(1_120, 1_135)
        val snapshot = monitor.snapshot(2_000)
        assertEquals(2, snapshot.receivedFrames)
        assertEquals(2, snapshot.decisions)
        assertEquals(1, snapshot.rejectedFrames)
        assertEquals(15, snapshot.cameraToDecisionP50Ms)
        assertEquals(19, snapshot.cameraToDecisionP95Ms)
        assertEquals(10, snapshot.decisionToAudioP50Ms)
        assertEquals(14, snapshot.decisionToAudioP95Ms)
        assertEquals(2.0, snapshot.usefulFps, .0001)
    }

    @Test fun virtualThirtyMinuteClockRunIsBoundedAndExplicitlyNotPhysical() {
        val monitor = OriaPerformanceMonitor(64)
        monitor.reset(0)
        var at = 0L
        while (at <= 30 * 60 * 1_000L) {
            monitor.recordReceived()
            monitor.recordDecision(at, at + 80, true)
            if (at % 2_000L == 0L) monitor.recordAudio(at + 80, at + 92)
            at += 250
        }
        val snapshot = monitor.snapshot(30 * 60 * 1_000L)
        assertEquals(7_201, snapshot.decisions)
        assertEquals(80, snapshot.cameraToDecisionP95Ms)
        assertEquals(12, snapshot.decisionToAudioP95Ms)
        assertTrue(snapshot.usefulFps in 4.0..4.01)
    }

    @Test fun loadControllerShedsOptionalWorkBeforeSemanticsAndRecoversWithHysteresis() {
        val controller = OriaLoadController(healthySamplesToRecover = 3)
        val normal = sample(cpu = 20.0)
        val protected = controller.evaluate(sample(cpu = 97.0), 100)
        assertEquals(OriaLoadLevel.PROTECTED, protected.level)
        assertEquals(1, protected.detectionStride)
        assertTrue(protected.depthStride > 1)
        assertTrue(protected.dashboardIntervalMs > 250)
        assertEquals(OriaLoadLevel.PROTECTED, controller.evaluate(normal, 100).level)
        assertEquals(OriaLoadLevel.PROTECTED, controller.evaluate(normal, 100).level)
        assertEquals(OriaLoadLevel.NORMAL, controller.evaluate(normal, 100).level)
        assertEquals(1, controller.evaluate(sample(battery = 8), 100).detectionStride)
        assertEquals(OriaLoadLevel.PROTECTED, controller.evaluate(sample(temperature = 46.0), 100).level)
    }

    private fun sample(cpu: Double = 20.0, battery: Int? = 80,
                       temperature: Double? = 30.0) = OriaHealthSample(
        memoryUsedBytes = 100, memoryLimitBytes = 1_000, cpuPercent = cpu,
        temperatureCelsius = temperature, batteryPercent = battery, thermalStatus = 0)
}
