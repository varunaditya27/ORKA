package com.orka.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextClearance
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
    fun onboardingCompletesWhenVisible_andCaptureIsReady() {
        ensureCaptureRouteReady()
        composeRule.onNodeWithText("Analyse", useUnmergedTree = true)
            .assert(hasText("Analyse"))
    }

    @Test
    fun taskLifecycle_createRescheduleComplete_andArchiveReflectsTask() {
        val taskTitle = "E2E task ${System.currentTimeMillis()}"

        createTaskAndOpenDetail(taskTitle)

        composeRule.onNodeWithText("Reschedule +1 day", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Mark Done", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("MARK_DONE")

        composeRule.onNodeWithText("Archive", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("Archive")
        composeRule.waitUntilTextExists(taskTitle)
        composeRule.onNodeWithText(taskTitle, useUnmergedTree = true)
            .assert(hasText(taskTitle))
    }

    @Test
    fun topLevelNavigation_settingsAndDiagnosticsSurfacesRender() {
        ensureCaptureRouteReady()

        composeRule.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("Adaptive scheduling")
        composeRule.onNodeWithText("Dark", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("Light", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("System", useUnmergedTree = true).performClick()

        composeRule.onNodeWithText("Diagnostics", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("Diagnostics")
        composeRule.onNode(hasText("Scheduler mode:", substring = true), useUnmergedTree = true)
            .assert(hasText("Scheduler mode:", substring = true))
        composeRule.onNodeWithText("Refresh", useUnmergedTree = true).performClick()

        composeRule.onNodeWithText("Tasks", useUnmergedTree = true).performClick()
        composeRule.waitUntilTextExists("Tasks")
        composeRule.onNode(hasText("completed |", substring = true), useUnmergedTree = true)
            .assert(hasText("completed |", substring = true))
    }

    private fun ensureCaptureRouteReady() {
        composeRule.waitForIdle()

        if (composeRule.hasText("Finish Setup")) {
            composeRule.onNodeWithText("1. Exact alarms", useUnmergedTree = true)
                .assert(hasText("1. Exact alarms"))
            composeRule.onNodeWithText("Finish Setup", useUnmergedTree = true).performClick()
        }

        composeRule.waitUntilTextExists("Analyse")
    }

    private fun createTaskAndOpenDetail(taskTitle: String) {
        ensureCaptureRouteReady()

        val input = "$taskTitle by tomorrow evening"
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(input)
        composeRule.onNodeWithText("Analyse", useUnmergedTree = true).performClick()

        composeRule.waitUntilTextExists("Confirm task")
        composeRule.onNodeWithText("Confirm", useUnmergedTree = true).performClick()

        composeRule.waitUntilTextExists("Mark Done")
        composeRule.onNodeWithText(taskTitle, useUnmergedTree = true)
            .assert(hasText(taskTitle))
    }
}
