package com.orka.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.orka.core.designsystem.OrkaTheme

fun ComposeContentTestRule.setOrkaContent(content: @Composable () -> Unit) {
    setContent {
        OrkaTheme(content = content)
    }
}

fun ComposeContentTestRule.hasText(text: String): Boolean =
    onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun ComposeContentTestRule.waitUntilTextExists(
    text: String,
    timeoutMillis: Long = 5_000L,
) {
    waitUntil(timeoutMillis) { hasText(text) }
}
