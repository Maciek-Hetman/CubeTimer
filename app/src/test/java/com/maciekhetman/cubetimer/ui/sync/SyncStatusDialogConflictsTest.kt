package com.maciekhetman.cubetimer.ui.sync

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.viewmodel.ConflictSideUi
import com.maciekhetman.cubetimer.viewmodel.ConflictUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncStatusDialogConflictsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private val signedIn = SyncUiState(status = SyncStatusType.SYNCED, isGuest = false, conflictCount = 2)

    private val solveConflict = ConflictUiModel(
        id = "c1",
        title = "Solve",
        local = ConflictSideUi(deleted = false, lines = listOf("3x3 · 12.34", "30 Aug 2026, 10:00")),
        server = ConflictSideUi(deleted = false, lines = listOf("3x3 · DNF (12.34)", "30 Aug 2026, 10:00"))
    )
    private val sessionConflict = ConflictUiModel(
        id = "c2",
        title = "Session",
        local = ConflictSideUi(deleted = true, lines = listOf("Deleted")),
        server = ConflictSideUi(deleted = false, lines = listOf("Practice", "3x3 · Archived"))
    )

    private fun inCard(id: String, text: String) =
        hasText(text) and hasAnyAncestor(hasTestTag("sync_conflict_$id"))

    @Test
    fun rendersConflictComparison() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = signedIn,
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {},
                    conflicts = listOf(solveConflict, sessionConflict)
                )
            }
        }

        composeTestRule.onNodeWithText("2 conflicts need review").assertIsDisplayed()
        composeTestRule.onNodeWithText("Some changes need your review.").assertIsDisplayed()
        composeTestRule.onNode(inCard("c1", "3x3 · 12.34")).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(inCard("c1", "3x3 · DNF (12.34)")).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(inCard("c2", "Deleted")).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(inCard("c2", "Practice")).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(inCard("c2", "This device")).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(inCard("c2", "Server")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun buttonsInvokeCallbacksWithConflictId() {
        val keptLocal = mutableListOf<String>()
        val keptServer = mutableListOf<String>()
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = signedIn,
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {},
                    conflicts = listOf(solveConflict, sessionConflict),
                    onKeepLocal = { keptLocal += it },
                    onKeepServer = { keptServer += it }
                )
            }
        }

        composeTestRule.onNode(inCard("c1", "Keep this device's")).performScrollTo().performClick()
        composeTestRule.onNode(inCard("c2", "Keep server's")).performScrollTo().performClick()

        assertEquals(listOf("c1"), keptLocal)
        assertEquals(listOf("c2"), keptServer)
    }

    @Test
    fun buttonsDisabledWhileResolving() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = signedIn,
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {},
                    conflicts = listOf(solveConflict),
                    resolvingConflictIds = setOf("c1"),
                    conflictErrorMessage = "Couldn't resolve the conflict. Try again."
                )
            }
        }

        composeTestRule.onNode(inCard("c1", "Keep this device's")).assertIsNotEnabled()
        composeTestRule.onNode(inCard("c1", "Keep server's")).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Couldn't resolve the conflict. Try again.").assertIsDisplayed()
    }

    @Test
    fun noConflictSectionWithoutConflicts() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = SyncUiState(status = SyncStatusType.SYNCED, isGuest = false),
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("All data is up to date.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sync_conflict_list").assertDoesNotExist()
    }

    @Test
    fun guestNeverShowsConflicts() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = SyncUiState(status = SyncStatusType.SYNCED, isGuest = true),
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {},
                    conflicts = listOf(solveConflict)
                )
            }
        }

        composeTestRule.onNodeWithTag("sync_conflict_list").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sign In").assertIsDisplayed()
    }

    @Test
    fun reviewHintShownWhileConflictListIsStillLoading() {
        composeTestRule.setContent {
            MaterialTheme {
                SyncStatusDialog(
                    syncState = signedIn,
                    onTriggerSync = {},
                    onDismiss = {},
                    onLoginClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Some changes need your review.").assertIsDisplayed()
        composeTestRule.onNodeWithText("All data is up to date.").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sync_conflict_list").assertDoesNotExist()
    }

    @Test
    fun conflictLabelPluralization() {
        assertEquals("1 conflict needs review", syncConflictLabel(resources, 1))
        assertEquals("3 conflicts need review", syncConflictLabel(resources, 3))
    }
}
