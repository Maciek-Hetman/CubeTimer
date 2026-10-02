package com.maciekhetman.cubetimer.ui.screens

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.model.User
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Cloud Sync row of Settings' "Account" section hints at unresolved sync conflicts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAccountSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val signedInAuth = AuthState.Authenticated(User(id = "usr_1", email = "cuber@example.com"))

    private fun synced(conflictCount: Int) =
        SyncUiState(status = SyncStatusType.SYNCED, isGuest = false, conflictCount = conflictCount)

    private fun render(
        syncUiState: SyncUiState,
        authState: AuthState = signedInAuth,
        onSyncClick: () -> Unit = {},
        onAuthClick: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                AccountSection(
                    syncUiState = syncUiState,
                    onSyncClick = onSyncClick,
                    authState = authState,
                    onAuthClick = onAuthClick
                )
            }
        }
    }

    @Test
    fun showsConflictHintWhenConflictsNeedAttention() {
        render(synced(conflictCount = 2))

        composeTestRule.onNodeWithText("2 conflicts need attention").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cloud Sync").assertIsDisplayed()
        composeTestRule.onNodeWithText("Synced").assertIsDisplayed()
    }

    @Test
    fun conflictHintIsSingularForOneConflict() {
        render(synced(conflictCount = 1))

        composeTestRule.onNodeWithText("1 conflict needs attention").assertIsDisplayed()
    }

    @Test
    fun noConflictHintWithoutConflicts() {
        render(synced(conflictCount = 0))

        composeTestRule.onNodeWithText("Synced").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 conflicts need attention").assertDoesNotExist()
        composeTestRule.onNodeWithText("1 conflict needs attention").assertDoesNotExist()
    }

    @Test
    fun hintIsKeptAlongsideOtherSyncStatuses() {
        render(SyncUiState(status = SyncStatusType.OFFLINE, isGuest = false, pendingCount = 3, conflictCount = 4))

        composeTestRule.onNodeWithText("4 conflicts need attention").assertIsDisplayed()
        composeTestRule.onNodeWithText("Offline (3 pending)").assertIsDisplayed()
    }

    @Test
    fun tappingTheSyncRowInvokesOnSyncClick() {
        var syncClicks = 0
        var authClicks = 0
        render(synced(conflictCount = 2), onSyncClick = { syncClicks++ }, onAuthClick = { authClicks++ })

        composeTestRule.onNodeWithText("2 conflicts need attention").performClick()

        assertEquals(1, syncClicks)
        assertEquals(0, authClicks)
    }

    @Test
    fun accountRowStillShowsTheSignedInEmail() {
        render(synced(conflictCount = 2))

        composeTestRule.onNodeWithText("cuber@example.com").assertIsDisplayed()
    }

    @Test
    fun hintLabelPluralization() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("1 conflict needs attention", syncConflictHint(resources, 1))
        assertEquals("2 conflicts need attention", syncConflictHint(resources, 2))
        assertEquals("12 conflicts need attention", syncConflictHint(resources, 12))
    }
}
