package com.maciekhetman.cubetimer.ui.auth

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** Changing the password from the signed-in profile dialog: a form, then one guarded request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthDialogChangePasswordTest {

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

    /** The dialog's title and its primary button share the text; this is the button. */
    private fun submitButton(): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText("Change password") and hasClickAction())

    private fun openChangePassword() {
        composeTestRule.onNodeWithText("Change password").performClick()
        composeTestRule.waitForIdle()
    }

    private fun fillForm(current: String = "OldPassword123!", new: String = "NewPassword456!", confirm: String = new) {
        composeTestRule.onNodeWithText("Current password").performTextInput(current)
        composeTestRule.onNodeWithText("New password (min 10 characters)").performTextInput(new)
        composeTestRule.onNodeWithText("Confirm new password").performTextInput(confirm)
        composeTestRule.waitForIdle()
    }

    @Test
    fun signedInProfileOffersChangePassword() {
        showProfile()
        composeTestRule.onNodeWithText("Change password").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun guestProfileHasNoChangePasswordAction() {
        showProfile(AuthState.Guest)
        composeTestRule.onNodeWithText("Guest Mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Change password").assertDoesNotExist()
    }

    @Test
    fun theActionOpensAFormWithThreeFieldsAndSendsNothingYet() {
        showProfile()

        openChangePassword()

        composeTestRule.onNodeWithText("Current password").assertIsDisplayed()
        composeTestRule.onNodeWithText("New password (min 10 characters)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm new password").assertIsDisplayed()
        submitButton().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
        assertEquals(0, authManager.changeCalls)
    }

    @Test
    fun cancellingReturnsToTheProfileWithoutChangingAnything() {
        showProfile()
        openChangePassword()

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Account Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current password").assertDoesNotExist()
        assertEquals(0, authManager.changeCalls)
    }

    @Test
    fun invalidInputShowsFieldErrorsAndSendsNothing() {
        showProfile()
        openChangePassword()
        fillForm(new = "short", confirm = "different")

        submitButton().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Password must be at least 10 characters").assertIsDisplayed()
        composeTestRule.onNodeWithText("Passwords do not match").assertIsDisplayed()
        assertEquals(0, authManager.changeCalls)
    }

    @Test
    fun submittingDisablesBothButtonsWhileTheRequestRunsThenReturnsToTheProfileWithTheSuccessMessage() {
        val gate = CompletableDeferred<AuthResult<Unit>>()
        authManager.gate = gate
        showProfile()
        openChangePassword()
        fillForm()

        submitButton().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, authManager.changeCalls)
        assertEquals("OldPassword123!", authManager.lastCurrent)
        assertEquals("NewPassword456!", authManager.lastNew)
        submitButton().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()

        gate.complete(AuthResult.Success(Unit))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Account Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password changed.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current password").assertDoesNotExist()
    }

    @Test
    fun whenTheSessionWasLostTheSuccessLandsOnSignInWithAnExplanation() {
        authManager.onChange = { authManager.state.value = AuthState.Guest }
        showProfile()
        openChangePassword()
        fillForm()

        submitButton().performClick()
        composeTestRule.waitForIdle()

        // "Sign In" is both the dialog's title and its button.
        composeTestRule.onAllNodesWithText("Sign In").assertCountEquals(2)
        composeTestRule.onNodeWithText("Account Profile").assertDoesNotExist()
        composeTestRule.onNodeWithText("Password changed. Sign in again with your new password.").assertIsDisplayed()
        composeTestRule.onNodeWithText("cuber@example.com").assertIsDisplayed()
    }

    @Test
    fun aWrongCurrentPasswordShowsTheFieldErrorAndReenablesTheButtons() {
        authManager.result = AuthResult.Error(AuthException.InvalidCredentials())
        showProfile()
        openChangePassword()
        fillForm()

        submitButton().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Current password is incorrect.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Incorrect email or password.").assertDoesNotExist()
        submitButton().assertIsEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun anAccountWithoutAPasswordShowsAnExplanationBanner() {
        authManager.result = AuthResult.Error(
            AuthException.ApiError(errorCode = "password_not_set", message = "internal detail", httpStatusCode = 409)
        )
        showProfile()
        openChangePassword()
        fillForm()

        submitButton().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "This account has no password yet. Use \"Forgot password?\" on the sign-in screen to set one."
        ).assertIsDisplayed()
        submitButton().assertIsEnabled()
    }

    private class FakeAuthManager(initial: AuthState) : AuthManager {
        val state = MutableStateFlow(initial)
        override val authState: StateFlow<AuthState> = state
        override val currentUser: User? get() = (state.value as? AuthState.Authenticated)?.user

        var changeCalls = 0
        var lastCurrent: String? = null
        var lastNew: String? = null
        var result: AuthResult<Unit> = AuthResult.Success(Unit)
        var gate: CompletableDeferred<AuthResult<Unit>>? = null
        var onChange: (() -> Unit)? = null

        override suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> {
            changeCalls++
            lastCurrent = currentPassword
            lastNew = newPassword
            val outcome = gate?.await() ?: result
            onChange?.invoke()
            return outcome
        }

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun login(email: String, password: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun logout(): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
