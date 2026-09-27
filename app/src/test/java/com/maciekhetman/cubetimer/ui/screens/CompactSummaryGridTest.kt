package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactSummaryGridTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bestAveragesAreComputedOffTheMainThreadAndShown() {
        // Fast start, slow finish: the best Ao5 (8.00) is not the current one (12.00), so the only
        // way "8.00" appears is via the best-average computation, which now runs off the main thread.
        val solves = (0 until 12).map { i ->
            SolveTime(timeInMillis = if (i < 5) 8_000L else 12_000L, timestamp = 1_000L * i)
        }

        composeTestRule.setContent {
            MaterialTheme {
                CompactSummaryGrid(solves = solves)
            }
        }

        composeTestRule.onNodeWithText("12.00").assertExists()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("8.00").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
