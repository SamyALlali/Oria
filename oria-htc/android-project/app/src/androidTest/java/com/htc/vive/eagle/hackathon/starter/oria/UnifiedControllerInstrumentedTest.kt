package com.htc.vive.eagle.hackathon.starter.oria

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.vive.eagle.hackathon.starter.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SDK fixture replay through the real Controller. No glasses, audio proof or background claim. */
@RunWith(AndroidJUnit4::class)
class UnifiedControllerInstrumentedTest {
    private val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private fun traces() = files.listFiles().orEmpty().filter { it.name.startsWith("oria-trace-") && it.extension == "jsonl" }
    private fun events(file: File): List<JSONObject> = file.readLines().mapNotNull { line ->
        runCatching { JSONObject(line) }.getOrNull() // A concurrent final line may still be incomplete.
    }
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out after $timeoutMs ms" }
            SystemClock.sleep(50)
        }
    }

    @Test fun simulatorRunsBothModelsAndStopsWithoutSpeech() {
        val prior = traces().map { it.name }.toSet()
        val report = JSONObject().put("scope", "HTC SDK prerecorded simulator, two bounded 4 s Controller sessions; no physical camera, listening or background validation")
            .put("status", "RUNNING")
        var scenario: ActivityScenario<MainActivity>? = null
        var traceFile: File? = null
        try {
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            launched.onActivity { checkNotNull(it.oriaController).connect(true) }
            waitUntil(20_000) {
                var ready = false
                launched.onActivity {
                    val state = checkNotNull(it.oriaController).state.value
                    ready = state.simulator && state.connected && state.modelReady && state.obstacleModelReady
                }
                ready
            }
            waitUntil(2_000) { traces().any { it.name !in prior } }
            traceFile = traces().filter { it.name !in prior }.maxBy { it.lastModified() }
            repeat(2) {
                launched.onActivity { activity ->
                    checkNotNull(activity.oriaController).also { controller ->
                        controller.start()
                        assertTrue(controller.state.value.running)
                        assertFalse(controller.canContinueInBackground())
                    }
                }
                try { SystemClock.sleep(4_000) }
                finally { launched.onActivity { it.oriaController?.stop("Bounded simulator integration test") } }
                SystemClock.sleep(800) // Let any work already in flight complete after stop.
                launched.onActivity {
                    assertFalse(checkNotNull(it.oriaController).state.value.running)
                    assertFalse(checkNotNull(it.oriaController).state.value.audioBusy)
                }
            }
            val rows = events(checkNotNull(traceFile))
            val starts = rows.withIndex().filter { it.value.optString("type") == "start" }
            assertEquals("Exactly two explicitly started sessions", 2, starts.size)
            val measurements = JSONArray()
            for ((index, start) in starts) {
                val id = start.getLong("sessionId")
                val stopIndex = rows.indices.first { it > index && rows[it].optString("type") == "stop" && rows[it].optLong("sessionId", -1) == id }
                val session = rows.subList(index + 1, stopIndex)
                val yolo = session.filter { it.optString("type") == "inference" && it.optLong("sessionId", -1) == id }
                val depth = session.filter { it.optString("type") == "depth_timing" && it.optLong("sessionId", -1) == id }
                assertTrue("YOLO must execute repeatedly in session $id", yolo.size >= 2)
                assertTrue("Depth must execute in the same session $id", depth.isNotEmpty())
                assertTrue((yolo + depth).all { it.optString("source") == "htc_simulator" })
                assertFalse("No result from a stopped session may be published", rows.drop(stopIndex + 1).any {
                    it.optLong("sessionId", -1) == id && it.optString("type") in setOf("inference", "depth_timing")
                })
                measurements.put(JSONObject().put("sessionId", id).put("yoloFrames", yolo.size).put("depthFrames", depth.size))
            }
            assertFalse("Simulator must never submit real speech", rows.any {
                it.optString("type") == "speech_submitted" ||
                    (it.optString("type") in setOf("policy_voice", "depth_voice") && it.optString("action") == "submitted")
            })
            report.put("status", "PASS").put("sessions", measurements).put("trace", checkNotNull(traceFile).name)
        } catch (error: Throwable) {
            report.put("status", "FAIL").put("error", error.toString())
            throw error
        } finally {
            try {
                scenario?.onActivity {
                    it.oriaController?.stop("Simulator integration cleanup")
                    it.oriaController?.disconnect()
                    it.viveClientManager?.setSimulator(false) // Restore physical adapter without opening the real glasses.
                    assertFalse(checkNotNull(it.viveClientManager).isSimulator.value)
                }
            } finally {
                scenario?.close()
                val output = File(files, "unified_validation").apply { mkdirs() }
                File(output, "controller_simulator.json").writeText(report.toString(2))
                println("ORIA_UNIFIED_CONTROLLER_REPORT " + report.toString())
            }
        }
    }
}
