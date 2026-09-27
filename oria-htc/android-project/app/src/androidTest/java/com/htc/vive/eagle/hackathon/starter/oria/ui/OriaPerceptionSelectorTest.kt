package com.htc.vive.eagle.hackathon.starter.oria.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI semantics only: no controller, model, camera, microphone or audio transport is created. */
class OriaPerceptionSelectorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingModeKeepsOneNamedRadioSelected() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            var obstacles by remember { mutableStateOf(false) }
            OriaUiTheme {
                OriaPerceptionSelector(obstacleMode = obstacles, enabled = true) {
                    changes += it
                    obstacles = it
                }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(2)
        compose.onNodeWithText("Objets").assertIsSelected()
        compose.onNodeWithText("Obstacles caméra · expérimental").assertIsNotSelected().performClick()
        compose.onNodeWithText("Obstacles caméra · expérimental").assertIsSelected().performClick()
        compose.onNodeWithText("Objets").assertIsNotSelected().performClick()
        compose.onNodeWithText("Objets").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test fun busySelectorRetainsReadableChoiceWithoutAnEnabledAction() {
        compose.setContent {
            OriaUiTheme {
                OriaPerceptionSelector(obstacleMode = true, enabled = false) { error("Disabled selection changed") }
            }
        }
        compose.onNodeWithText("Objets").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithText("Obstacles caméra · expérimental").assertIsNotEnabled().assertIsSelected()
    }
}
