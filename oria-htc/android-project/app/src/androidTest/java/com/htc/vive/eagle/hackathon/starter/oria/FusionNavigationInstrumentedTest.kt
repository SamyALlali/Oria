package com.htc.vive.eagle.hackathon.starter.oria

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.htc.vive.eagle.hackathon.starter.MainActivity
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationMode
import com.htc.vive.eagle.hackathon.starter.oria.navigation.NavigationPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline real Controller integration; no claim about GPS, glasses microphone or audibility. */
@RunWith(AndroidJUnit4::class)
class FusionNavigationInstrumentedTest {
    private fun awaitPhase(scenario: ActivityScenario<MainActivity>, phase: NavigationPhase) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (true) {
            var actual: NavigationPhase? = null
            scenario.onActivity { actual = it.oriaController!!.navigation.state.value.phase }
            if (actual == phase) return
            check(SystemClock.elapsedRealtime() < deadline) { "Expected $phase, found $actual" }
            SystemClock.sleep(50)
        }
    }

    @Test fun demoRoutePauseResumeArrivalAndStopLeaveNoPerceptionOrAudioRunning() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    val c = it.oriaController!!
                    assertFalse(c.navigation.state.value.networkConsent)
                    c.navigation.search("Destination de démonstration", NavigationMode.SIMULATED)
                }
                awaitPhase(scenario, NavigationPhase.CONFIRMATION)
                scenario.onActivity { it.oriaController!!.navigation.confirmSelected() }
                awaitPhase(scenario, NavigationPhase.ACTIVE)
                scenario.onActivity {
                    val c = it.oriaController!!
                    assertEquals(NavigationMode.SIMULATED, c.navigation.state.value.mode)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.onActivity { assertEquals(NavigationPhase.PAUSED, it.oriaController!!.navigation.state.value.phase) }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity {
                    val c = it.oriaController!!
                    assertEquals("Returning does not silently resume", NavigationPhase.PAUSED, c.navigation.state.value.phase)
                    c.navigation.resume()
                }
                awaitPhase(scenario, NavigationPhase.ACTIVE)
                repeat(8) {
                    scenario.onActivity { it.oriaController!!.navigation.advanceSimulation() }
                    SystemClock.sleep(200) // Distinct monotonic location samples for the pure engine.
                }
                awaitPhase(scenario, NavigationPhase.ARRIVED)
                scenario.onActivity {
                    val c = it.oriaController!!
                    assertFalse(c.state.value.running)
                    assertFalse(c.state.value.audioBusy)
                    c.stop("Fin du test de navigation simulée")
                    assertEquals(NavigationPhase.IDLE, c.navigation.state.value.phase)
                }
                SystemClock.sleep(500)
                scenario.onActivity { assertEquals(NavigationPhase.IDLE, it.oriaController!!.navigation.state.value.phase) }
            } finally {
                scenario.onActivity { it.oriaController?.stop("Nettoyage du test fusion") }
            }
        }
    }
}
