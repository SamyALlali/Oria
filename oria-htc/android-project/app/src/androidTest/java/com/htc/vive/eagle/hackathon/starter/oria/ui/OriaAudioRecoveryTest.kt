package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI only: no model, glasses, microphone or audio transport is started. */
class OriaAudioRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedLocalPlaybackOffersOneRecoveryAction() {
        var resumes = 0
        compose.setContent {
            OriaUiTheme {
                OriaAudioRecovery(paused = true, unknown = false, connected = true, busy = false) { resumes++ }
            }
        }
        compose.onNodeWithText("Reprendre les annonces").performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
    }

    @Test fun ambiguousDeliveryDoesNotOfferRecoveryWithoutTransportReset() {
        compose.setContent {
            OriaUiTheme {
                OriaAudioRecovery(paused = true, unknown = true, connected = true, busy = false) {
                    error("An ambiguous delivery cannot be reset through a local retry")
                }
            }
        }
        compose.onNodeWithText("Reprendre les annonces").assertDoesNotExist()
    }

    @Test fun recoveryWaitsForGlassesConnection() {
        compose.setContent {
            OriaUiTheme {
                OriaAudioRecovery(paused = true, unknown = false, connected = false, busy = false) {
                    error("Cannot retry without glasses")
                }
            }
        }
        compose.onNodeWithText("Reprendre les annonces").assertIsNotEnabled()
    }
}
