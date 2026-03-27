package com.orka.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orka.core.testing.hasText
import com.orka.core.testing.waitUntilTextExists
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingCaptureAndNavigationHappyPath() {
        composeRule.waitForIdle()

        if (composeRule.hasText("Finish Setup")) {
            composeRule.onNodeWithText("Finish Setup", useUnmergedTree = true).performClick()
        }

        composeRule.waitUntilTextExists("Analyse")
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Prepare presentation by tomorrow evening")
        composeRule.onNodeWithText("Analyse", useUnmergedTree = true).performClick()

        composeRule.waitUntilTextExists("Confirm task")
        composeRule.onNodeWithText("Confirm", useUnmergedTree = true).performClick()

        composeRule.waitUntilTextExists("Mark Done")
        composeRule.onNodeWithText("Back", useUnmergedTree = true).performClick()

        composeRule.waitUntilTextExists("Analyse")
        composeRule.onNodeWithText("Tasks", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Prepare presentation", useUnmergedTree = true)
            .assert(hasText("Prepare presentation"))

        composeRule.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("Adaptive scheduling")

        composeRule.onNodeWithText("Diagnostics", useUnmergedTree = true).performClick()
        composeRule.onNode(hasText("Scheduler mode:", substring = true), useUnmergedTree = true)
            .assert(hasText("Scheduler mode:", substring = true))
    }
}
