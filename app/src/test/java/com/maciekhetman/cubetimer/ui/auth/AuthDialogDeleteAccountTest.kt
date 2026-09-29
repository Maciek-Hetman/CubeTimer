package com.maciekhetman.cubetimer.ui.auth

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Account deletion from the signed-in profile dialog: a confirmation step, then one guarded request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthDialogDeleteAccountTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val signedIn = AuthState.Authenticated(User(id = "usr_1", email = "cuber@example.com", emailVerified = true))

    private lateinit var authManager: FakeAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        authManager = FakeAuthManager(signedIn)
        viewModel = AuthViewModel(ApplicationProvider.getApplicationContext<Application>(), authManager)
    }

    private fun showProfile(authState: AuthState = signedIn) {
        authManager.state.value = authState
        viewModel.openDialog(AuthDialogType.USER_PROFILE)
        composeTestRule.setContent {
            MaterialTheme {
                val formState by viewModel.formState.collectAsState()
                val state by viewModel.authState.collectAsState()
                AuthDialog(
                    formState = formState,
                    authState = state,
                    viewModel = viewModel,
                    onDismiss = viewModel::dismissDialog
                )
            }
        }
    }

    @Test
    fun signedInProfileOffersDeleteAccount() {
        showProfile()
        composeTestRule.onNodeWithText("Delete account").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun guestProfileHasNoDeleteAccountAction() {
        showProfile(AuthState.Guest)
        composeTestRule.onNodeWithText("Guest Mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete account").assertDoesNotExist()
    }

    @Test
    fun deleteAccountAsksForConfirmationAndStatesTheConsequences() {
        showProfile()

        composeTestRule.onNodeWithText("Delete account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete account?").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Your account and all of its synced data will be permanently deleted from the server. " +
                "This can't be undone."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Your solves and sessions stay on this device.").assertIsDisplayed()
        assertEquals("nothing is deleted before the user confirms", 0, authManager.deleteCalls)
    }

    @Test
    fun cancellingReturnsToTheProfileWithoutDeleting() {
        showProfile()
        composeTestRule.onNodeWithText("Delete account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Account Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete account?").assertDoesNotExist()
        assertEquals(0, authManager.deleteCalls)
    }

    @Test
    fun confirmingDisablesBothButtonsWhileTheRequestRunsThenClosesTheDialog() {
        val gate = CompletableDeferred<AuthResult<Unit>>()
        authManager.gate = gate
        showProfile()
        composeTestRule.onNodeWithText("Delete account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete permanently").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, authManager.deleteCalls)
        composeTestRule.onNodeWithText("Delete permanently").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()

        gate.complete(AuthResult.Success(Unit))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete account?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Account Profile").assertDoesNotExist()
    }

    @Test
    fun aFailedDeletionShowsAReadableErrorAndReenablesTheButtons() {
        authManager.result = AuthResult.Error(AuthException.NetworkError("Account deletion failed: Unable to resolve host \"api.example\""))
        showProfile()
        composeTestRule.onNodeWithText("Delete account").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete permanently").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Network connection failed. Please check your connection.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete account?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete permanently").assertIsEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
    }

    private class FakeAuthManager(initial: AuthState) : AuthManager {
        val state = MutableStateFlow(initial)
        override val authState: StateFlow<AuthState> = state
        override val currentUser: User? get() = (state.value as? AuthState.Authenticated)?.user

        var deleteCalls = 0
        var result: AuthResult<Unit> = AuthResult.Success(Unit)
        var gate: CompletableDeferred<AuthResult<Unit>>? = null

        override suspend fun deleteAccount(): AuthResult<Unit> {
            deleteCalls++
            return gate?.await() ?: result
        }

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun login(email: String, password: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> =
            throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun refreshSession(): AuthResult<User> = throw NotImplementedError()
        override suspend fun logout(): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
