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

/**
 * Opening a verification or password-reset link in the app: nothing is sent to the server until
 * the user confirms, and a link is not usable while someone is signed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthLinkDialogsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val signedIn = AuthState.Authenticated(User(id = "usr_1", email = "cuber@example.com", emailVerified = true))

    private lateinit var authManager: FakeAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        authManager = FakeAuthManager()
        viewModel = AuthViewModel(ApplicationProvider.getApplicationContext<Application>(), authManager)
    }

    private fun open(link: AuthLink, authState: AuthState = AuthState.Guest) {
        authManager.state.value = authState
        viewModel.openEmailLink(link)
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
    fun aVerificationLinkAsksBeforeVerifying() {
        open(AuthLink.VerifyEmail("tok-1"))

        composeTestRule.onNodeWithText("Finish verifying your email and sign in on this device?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verify and sign in").assertIsEnabled()
        assertEquals("nothing is sent before the user confirms", emptyList<String>(), authManager.verifiedTokens)
    }

    @Test
    fun confirmingVerifiesWithTheLinkTokenAndClosesTheDialog() {
        open(AuthLink.VerifyEmail("tok-1"))

        composeTestRule.onNodeWithText("Verify and sign in").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("tok-1"), authManager.verifiedTokens)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun cancellingAVerificationLinkSendsNothing() {
        open(AuthLink.VerifyEmail("tok-1"))

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertEquals(emptyList<String>(), authManager.verifiedTokens)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun verifyingIsSubmittedOnceAndLocksTheDialogWhileItRuns() {
        val gate = CompletableDeferred<AuthResult<User>>()
        authManager.verifyGate = gate
        open(AuthLink.VerifyEmail("tok-1"))

        composeTestRule.onNodeWithText("Verify and sign in").performClick()
        composeTestRule.waitForIdle()
        viewModel.submitVerifyEmail()
        composeTestRule.waitForIdle()

        assertEquals(listOf("tok-1"), authManager.verifiedTokens)
        composeTestRule.onNodeWithText("Verify and sign in").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()

        gate.complete(AuthResult.Success(signedIn.user))
        composeTestRule.waitForIdle()

        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun anExpiredVerificationLinkShowsAReadableErrorAndStaysOpen() {
        authManager.verifyResult = AuthResult.Error(AuthException.InvalidToken("token is invalid or expired"))
        open(AuthLink.VerifyEmail("old"))

        composeTestRule.onNodeWithText("Verify and sign in").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Invalid or expired verification/reset token.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verify and sign in").assertIsEnabled()
    }

    @Test
    fun aVerificationLinkIsNotUsableWhileSignedIn() {
        open(AuthLink.VerifyEmail("tok-1"), authState = signedIn)

        composeTestRule.onNodeWithText("You're signed in as cuber@example.com. Sign out first to use this link.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Verify and sign in").assertDoesNotExist()
        composeTestRule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun aVerificationLinkWaitsWhileTheStoredSessionIsStillBeingRestored() {
        open(AuthLink.VerifyEmail("tok-1"), authState = AuthState.Loading)

        composeTestRule.onNodeWithText("Verify and sign in").assertIsNotEnabled()

        authManager.state.value = AuthState.Guest
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Verify and sign in").assertIsEnabled()
    }

    @Test
    fun aResetLinkAsksForTheNewPasswordAndSendsItWithTheLinkToken() {
        open(AuthLink.ResetPassword("reset-1"))
        composeTestRule.onNodeWithText("Choose a new password").assertIsDisplayed()

        viewModel.onPasswordChanged("NewSecurePassword123!")
        viewModel.onConfirmPasswordChanged("NewSecurePassword123!")
        composeTestRule.onNodeWithText("Set password").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("reset-1" to "NewSecurePassword123!"), authManager.resets)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun aResetLinkRejectsAShortOrMismatchedPasswordWithoutCallingTheServer() {
        open(AuthLink.ResetPassword("reset-1"))

        viewModel.onPasswordChanged("short")
        viewModel.onConfirmPasswordChanged("different")
        composeTestRule.onNodeWithText("Set password").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Password must be at least 10 characters").assertIsDisplayed()
        composeTestRule.onNodeWithText("Passwords do not match").assertIsDisplayed()
        assertEquals(emptyList<Pair<String, String>>(), authManager.resets)
    }

    @Test
    fun aResetLinkIsNotUsableWhileSignedIn() {
        open(AuthLink.ResetPassword("reset-1"), authState = signedIn)

        composeTestRule.onNodeWithText("You're signed in as cuber@example.com. Sign out first to use this link.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Set password").assertDoesNotExist()
    }

    @Test
    fun openingALinkStartsFromCleanPasswordFields() {
        viewModel.onPasswordChanged("typed-into-the-login-form")
        viewModel.onConfirmPasswordChanged("typed-into-the-register-form")

        open(AuthLink.ResetPassword("reset-1"))

        assertEquals("", viewModel.formState.value.password)
        assertEquals("", viewModel.formState.value.confirmPassword)
        assertEquals("reset-1", viewModel.formState.value.token)
    }

    @Test
    fun aLinkArrivingDuringARequestDoesNotReplaceTheDialogInFlight() {
        val gate = CompletableDeferred<AuthResult<User>>()
        authManager.verifyGate = gate
        open(AuthLink.VerifyEmail("tok-1"))
        composeTestRule.onNodeWithText("Verify and sign in").performClick()
        composeTestRule.waitForIdle()

        viewModel.openEmailLink(AuthLink.ResetPassword("reset-2"))

        assertEquals(AuthDialogType.VERIFY_EMAIL_LINK, viewModel.formState.value.dialogType)
        assertEquals("tok-1", viewModel.formState.value.token)
        gate.complete(AuthResult.Success(signedIn.user))
    }

    private class FakeAuthManager : AuthManager {
        val state = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = state
        override val currentUser: User? get() = (state.value as? AuthState.Authenticated)?.user

        val verifiedTokens = mutableListOf<String>()
        var verifyResult: AuthResult<User> = AuthResult.Success(User(id = "usr_1", email = "cuber@example.com"))
        var verifyGate: CompletableDeferred<AuthResult<User>>? = null
        val resets = mutableListOf<Pair<String, String>>()

        override suspend fun verifyEmail(token: String): AuthResult<User> {
            verifiedTokens += token
            return verifyGate?.await() ?: verifyResult
        }

        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> {
            resets += token to newPassword
            return AuthResult.Success(User(id = "usr_1", email = "cuber@example.com"))
        }

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun login(email: String, password: String): AuthResult<User> = throw NotImplementedError()
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> =
            throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun refreshSession(): AuthResult<User> = throw NotImplementedError()
        override suspend fun logout(): AuthResult<Unit> = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
