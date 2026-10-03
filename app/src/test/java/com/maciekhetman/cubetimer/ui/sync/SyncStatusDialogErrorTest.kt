package com.maciekhetman.cubetimer.ui.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStatusDialogErrorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val hint = "Your changes stay on this device. Sync will try again automatically, or tap Sync Now."

    // What SyncEngineImpl stores for a server failure: technical English, including server text.
    private val rawMessage = "API error (500) [unknown_error]: pq: the database system is shutting down"

    private fun showDialog(state: SyncUiState) {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = state,
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {}
                )
            }
        }
    }

    @Test
    fun errorStateShowsTheLocalizedHintInsteadOfTheRawMessage() {
        showDialog(SyncUiState(status = SyncStatusType.ERROR, isGuest = false, errorMessage = rawMessage))

        composeTestRule.onNodeWithText("A synchronization error occurred.").assertIsDisplayed()
        composeTestRule.onNodeWithText(hint).assertIsDisplayed()
        composeTestRule.onNodeWithText("API error", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("database system", substring = true).assertDoesNotExist()
    }

    @Test
    fun errorStateShowsTheHintEvenWithoutAMessage() {
        showDialog(SyncUiState(status = SyncStatusType.ERROR, isGuest = false))

        composeTestRule.onNodeWithText(hint).assertIsDisplayed()
    }

    @Test
    fun otherStatesShowNoErrorHint() {
        showDialog(SyncUiState(status = SyncStatusType.OFFLINE, isGuest = false, pendingCount = 3))

        composeTestRule.onNodeWithText(
            "Device is offline. Changes will sync automatically when reconnected."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(hint).assertDoesNotExist()
    }
}
