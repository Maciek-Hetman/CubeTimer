package com.maciekhetman.cubetimer.ui.auth

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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

/**
 * Verification and password reset are completed through the link the server emails (it opens the
 * web client), so the dialogs point at that link instead of asking for a token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthDialogEmailLinkFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var authManager: FakeAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        authManager = FakeAuthManager()
        viewModel = AuthViewModel(ApplicationProvider.getApplicationContext<Application>(), authManager)
    }

    private fun show(dialogType: AuthDialogType, email: String = "cuber@example.com") {
        viewModel.onEmailChanged(email)
        viewModel.openDialog(dialogType)
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
    fun verificationDialogPointsAtTheEmailedLinkAndAsksForNoToken() {
        show(AuthDialogType.EMAIL_VERIFICATION)

        composeTestRule.onNodeWithText("Verify your email").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "We sent a verification link to cuber@example.com. Open it to verify your account, then sign in here."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Verification Token").assertDoesNotExist()
    }

    @Test
    fun registeringLandsOnTheVerificationDialog() {
        show(AuthDialogType.REGISTER, email = "new@example.com")
        viewModel.onPasswordChanged("SecurePassword123!")
        viewModel.onConfirmPasswordChanged("SecurePassword123!")

        composeTestRule.onNodeWithText("Register").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Verify your email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Account created.").assertIsDisplayed()
    }

    @Test
    fun resendEmailRequestsANewLinkAndConfirms() {
        show(AuthDialogType.EMAIL_VERIFICATION)

        composeTestRule.onNodeWithText("Resend email").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("cuber@example.com"), authManager.resendEmails)
        composeTestRule.onNodeWithText("Verification email sent. Check your inbox.").assertIsDisplayed()
    }

    @Test
    fun resendEmailIsDisabledWhileTheRequestRuns() {
        val gate = CompletableDeferred<AuthResult<Unit>>()
        authManager.resendGate = gate
        show(AuthDialogType.EMAIL_VERIFICATION)

        composeTestRule.onNodeWithText("Resend email").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Resend email").assertIsNotEnabled()

        gate.complete(AuthResult.Success(Unit))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Resend email").assertIsEnabled()
    }

    @Test
    fun aFailedResendShowsAReadableError() {
        authManager.resendResult = AuthResult.Error(AuthException.RateLimited())
        show(AuthDialogType.EMAIL_VERIFICATION)

        composeTestRule.onNodeWithText("Resend email").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Too many attempts. Please try again in a few moments.").assertIsDisplayed()
    }

    @Test
    fun signingInWithAnUnverifiedEmailOffersToResendTheLink() {
        authManager.loginResult = AuthResult.Error(AuthException.EmailNotVerified())
        show(AuthDialogType.LOGIN)
        viewModel.onPasswordChanged("ValidPassword123!")

        // "Sign In" is both the dialog title and its button.
        composeTestRule.onNode(hasText("Sign In") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Verify your email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Email is not verified. Please verify your account.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Resend email").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun signInFromTheVerificationDialogReturnsToTheLoginForm() {
        show(AuthDialogType.EMAIL_VERIFICATION)

        composeTestRule.onNodeWithText("Sign In").performClick()
        composeTestRule.waitForIdle()

        assertEquals(AuthDialogType.LOGIN, viewModel.formState.value.dialogType)
        assertEquals("cuber@example.com", viewModel.formState.value.email)
    }

    @Test
    fun requestingAResetLandsOnCheckYourEmailWithoutTokenOrPasswordFields() {
        show(AuthDialogType.FORGOT_PASSWORD)

        composeTestRule.onNodeWithText("Send Link").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("cuber@example.com"), authManager.resetRequests)
        composeTestRule.onNodeWithText("Check your email").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "If an account exists for cuber@example.com, we sent a link to reset your password. " +
                "Open it to choose a new password, then sign in here."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Reset Token").assertDoesNotExist()
        composeTestRule.onNodeWithText("Set Password").assertDoesNotExist()
    }

    @Test
    fun sendAgainReturnsToTheForgotPasswordForm() {
        show(AuthDialogType.RESET_PASSWORD)

        composeTestRule.onNodeWithText("Send again").performClick()
        composeTestRule.waitForIdle()

        assertEquals(AuthDialogType.FORGOT_PASSWORD, viewModel.formState.value.dialogType)
        composeTestRule.onNodeWithText("Forgot Password").assertIsDisplayed()
    }

    private class FakeAuthManager : AuthManager {
        override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Guest)
        override val currentUser: User? = null

        val resendEmails = mutableListOf<String>()
        var resendResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var resendGate: CompletableDeferred<AuthResult<Unit>>? = null
        val resetRequests = mutableListOf<String>()
        var loginResult: AuthResult<User> = AuthResult.Success(User(id = "usr_1", email = "cuber@example.com"))

        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> {
            resendEmails += email
            return resendGate?.await() ?: resendResult
        }

        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> {
            resetRequests += email
            return AuthResult.Success(Unit)
        }

        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = loginResult

        override suspend fun initialize() = Unit
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> =
            throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun refreshSession(): AuthResult<User> = throw NotImplementedError()
        override suspend fun logout(): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun deleteAccount(): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
