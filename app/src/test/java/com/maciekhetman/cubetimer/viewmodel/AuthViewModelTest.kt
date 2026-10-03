package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.ui.auth.AuthDialogType
import com.maciekhetman.cubetimer.ui.auth.AuthLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        fakeAuthManager = FakeAuthManager()
        viewModel = AuthViewModel(application = application, authManager = fakeAuthManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testOpenAndDismissDialog() {
        viewModel.openDialog(AuthDialogType.LOGIN)
        assertEquals(AuthDialogType.LOGIN, viewModel.formState.value.dialogType)

        viewModel.openDialog(AuthDialogType.REGISTER)
        assertEquals(AuthDialogType.REGISTER, viewModel.formState.value.dialogType)

        viewModel.dismissDialog()
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun testLoginValidationRejectsInvalidEmailAndBlankPassword() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("invalid-email")
        viewModel.onPasswordChanged("")

        viewModel.submitLogin()
        advanceUntilIdle()

        assertNotNull(viewModel.formState.value.emailError)
        assertNotNull(viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.loginCallCount)
    }

    @Test
    fun testLoginSuccessClosesDialogAndClearsInputs() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("ValidPassword123!")

        fakeAuthManager.loginResult = AuthResult.Success(
            User(id = "u1", email = "cuber@example.com", emailVerified = true)
        )

        viewModel.submitLogin()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.loginCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
        assertEquals("", viewModel.formState.value.password)
        assertNull(viewModel.formState.value.errorMessage)
    }

    @Test
    fun testLoginFailureSetsErrorMessage() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("WrongPassword123!")

        fakeAuthManager.loginResult = AuthResult.Error(AuthException.InvalidCredentials())

        viewModel.submitLogin()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.loginCallCount)
        assertEquals(AuthDialogType.LOGIN, viewModel.formState.value.dialogType)
        assertEquals("Incorrect email or password.", viewModel.formState.value.errorMessage)
    }

    @Test
    fun testRegisterValidationRejectsShortPasswordAndMismatch() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.REGISTER)
        viewModel.onEmailChanged("newuser@example.com")
        viewModel.onPasswordChanged("short")
        viewModel.onConfirmPasswordChanged("mismatch")

        viewModel.submitRegister()
        advanceUntilIdle()

        assertEquals("Password must be at least 10 characters", viewModel.formState.value.passwordError)
        assertEquals("Passwords do not match", viewModel.formState.value.confirmPasswordError)
        assertEquals(0, fakeAuthManager.registerCallCount)
    }

    @Test
    fun testRegisterSuccessTransitionsToEmailVerificationDialog() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.REGISTER)
        viewModel.onEmailChanged("newuser@example.com")
        viewModel.onPasswordChanged("SecurePassword123!")
        viewModel.onConfirmPasswordChanged("SecurePassword123!")

        fakeAuthManager.registerResult = AuthResult.Success(Unit)

        viewModel.submitRegister()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.registerCallCount)
        assertEquals(AuthDialogType.EMAIL_VERIFICATION, viewModel.formState.value.dialogType)
        assertEquals("newuser@example.com", viewModel.formState.value.email)
        assertNotNull(viewModel.formState.value.successMessage)
    }

    @Test
    fun testForgotPasswordValidationAndSuccess() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.FORGOT_PASSWORD)
        viewModel.onEmailChanged("cuber@example.com")

        fakeAuthManager.requestPasswordResetResult = AuthResult.Success(Unit)

        viewModel.submitForgotPassword()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.requestPasswordResetCallCount)
        assertEquals(AuthDialogType.RESET_PASSWORD, viewModel.formState.value.dialogType)
    }

    @Test
    fun testForgotPasswordFailureStaysOnTheFormWithReadableError() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.FORGOT_PASSWORD)
        viewModel.onEmailChanged("cuber@example.com")
        fakeAuthManager.requestPasswordResetResult = AuthResult.Error(AuthException.RateLimited())

        viewModel.submitForgotPassword()
        advanceUntilIdle()

        assertEquals(AuthDialogType.FORGOT_PASSWORD, viewModel.formState.value.dialogType)
        assertEquals("Too many attempts. Please try again in a few moments.", viewModel.formState.value.errorMessage)
    }

    @Test
    fun testLoginWithUnverifiedEmailOpensTheVerificationDialog() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("ValidPassword123!")
        fakeAuthManager.loginResult = AuthResult.Error(AuthException.EmailNotVerified())

        viewModel.submitLogin()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.EMAIL_VERIFICATION, state.dialogType)
        assertEquals("Email is not verified. Please verify your account.", state.errorMessage)
        assertEquals("cuber@example.com", state.email)
        assertEquals("the password is kept for the sign-in after verifying", "ValidPassword123!", state.password)
        assertFalse(state.isLoading)
    }

    @Test
    fun testUnverifiedLoginDoesNotReopenADismissedDialog() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("ValidPassword123!")
        fakeAuthManager.loginResult = AuthResult.Error(AuthException.EmailNotVerified())

        viewModel.submitLogin()
        viewModel.dismissDialog()
        advanceUntilIdle()

        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun testResendVerificationSendsToTheShownEmailAndConfirms() = testScope.runTest {
        viewModel.onEmailChanged("  newuser@example.com ")
        viewModel.openDialog(AuthDialogType.EMAIL_VERIFICATION)

        viewModel.submitResendVerification()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.resendCallCount)
        assertEquals("newuser@example.com", fakeAuthManager.lastResendEmail)
        assertEquals(AuthDialogType.EMAIL_VERIFICATION, viewModel.formState.value.dialogType)
        assertEquals("Verification email sent. Check your inbox.", viewModel.formState.value.successMessage)
        assertNull(viewModel.formState.value.errorMessage)
        assertFalse(viewModel.formState.value.isLoading)
    }

    @Test
    fun testResendVerificationFailureShowsReadableErrorAndNoSuccess() = testScope.runTest {
        viewModel.onEmailChanged("newuser@example.com")
        viewModel.openDialog(AuthDialogType.EMAIL_VERIFICATION)
        fakeAuthManager.resendResult = AuthResult.Error(AuthException.RateLimited("429 slow down"))

        viewModel.submitResendVerification()
        advanceUntilIdle()

        assertEquals("Too many attempts. Please try again in a few moments.", viewModel.formState.value.errorMessage)
        assertNull(viewModel.formState.value.successMessage)
        assertFalse(viewModel.formState.value.isLoading)
    }

    @Test
    fun testResendVerificationWhileRunningIsNotSubmittedTwice() = testScope.runTest {
        val gate = CompletableDeferred<AuthResult<Unit>>()
        fakeAuthManager.resendGate = gate
        viewModel.onEmailChanged("newuser@example.com")
        viewModel.openDialog(AuthDialogType.EMAIL_VERIFICATION)

        viewModel.submitResendVerification()
        advanceUntilIdle()
        assertTrue(viewModel.formState.value.isLoading)

        viewModel.submitResendVerification()
        advanceUntilIdle()
        assertEquals(1, fakeAuthManager.resendCallCount)

        gate.complete(AuthResult.Success(Unit))
        advanceUntilIdle()
        assertFalse(viewModel.formState.value.isLoading)
    }

    @Test
    fun testResetPasswordSuccessClosesDialog() = testScope.runTest {
        viewModel.openEmailLink(AuthLink.ResetPassword("valid-reset-token"))
        viewModel.onPasswordChanged("NewSecurePassword123!")
        viewModel.onConfirmPasswordChanged("NewSecurePassword123!")

        fakeAuthManager.resetPasswordResult = AuthResult.Success(
            User(id = "u1", email = "cuber@example.com", emailVerified = true)
        )

        viewModel.submitResetPassword()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.resetPasswordCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun testLogoutCallsAuthManager() = testScope.runTest {
        viewModel.submitLogout()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.logoutCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun testAdoptGuestDataCallsAuthManager() = testScope.runTest {
        fakeAuthManager.currentUser = User(id = "u1", email = "test@example.com")
        viewModel.adoptGuestData()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.adoptGuestDataCallCount)
        assertEquals("u1", fakeAuthManager.lastAdoptedUserId)
    }

    @Test
    fun testFailureNeverShowsTheRawServerText() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("ValidPassword123!")
        val raw = AuthException.ApiError("internal_error", "pq: relation \"users\" does not exist", 500)
        fakeAuthManager.loginResult = AuthResult.Error(raw)

        viewModel.submitLogin()
        advanceUntilIdle()

        assertEquals("Something went wrong on the server. Please try again later.", viewModel.formState.value.errorMessage)
    }

    @Test
    fun testFailureLogsTheRawException() = testScope.runTest {
        ShadowLog.clear()
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("cuber@example.com")
        viewModel.onPasswordChanged("ValidPassword123!")
        val raw = AuthException.Unknown("java.lang.IllegalStateException: boom")
        fakeAuthManager.loginResult = AuthResult.Error(raw)

        viewModel.submitLogin()
        advanceUntilIdle()

        val logged = ShadowLog.getLogsForTag("AuthViewModel").mapNotNull { it.throwable }
        assertTrue("raw exception must be logged, got $logged", logged.any { it === raw })
    }

    @Test
    fun testEveryAuthExceptionMapsToAFixedMessage() {
        fun allTypes(raw: String): List<AuthException> = listOf(
            AuthException.InvalidCredentials(raw),
            AuthException.EmailNotVerified(raw),
            AuthException.EmailAlreadyExists(raw),
            AuthException.InvalidToken(raw),
            AuthException.InvalidRefreshToken(raw),
            AuthException.RefreshTokenReused(raw),
            AuthException.RateLimited(raw),
            AuthException.InvalidPassword(raw),
            AuthException.InvalidEmail(raw),
            AuthException.EmailDeliveryFailed(raw),
            AuthException.Unauthorized(raw),
            AuthException.Forbidden(raw),
            AuthException.CursorExpired(raw),
            AuthException.ApiError("code_$raw", raw, 500),
            AuthException.NetworkError(raw),
            AuthException.SerializationError(raw),
            AuthException.Unknown(raw)
        )

        val first = allTypes("RAW-DETAIL-ONE")
        val second = allTypes("RAW-DETAIL-TWO")
        first.zip(second).forEach { (a, b) ->
            val name = a::class.simpleName
            val message = viewModel.mapAuthError(a)
            assertTrue("$name has no message", message.isNotBlank())
            assertFalse("$name leaks its raw text: $message", message.contains("RAW-DETAIL"))
            assertEquals("$name message depends on the raw text", message, viewModel.mapAuthError(b))
        }
    }

    @Test
    fun testDeleteAccountSuccessClosesDialogAndClearsInputs() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.DELETE_ACCOUNT)
        viewModel.onPasswordChanged("leftover")
        fakeAuthManager.deleteAccountResult = AuthResult.Success(Unit)

        viewModel.submitDeleteAccount()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.deleteAccountCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
        assertFalse(viewModel.formState.value.isLoading)
        assertEquals("", viewModel.formState.value.password)
        assertNull(viewModel.formState.value.errorMessage)
    }

    @Test
    fun testDeleteAccountFailureKeepsConfirmationOpenWithReadableError() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.DELETE_ACCOUNT)
        fakeAuthManager.deleteAccountResult = AuthResult.Error(AuthException.NetworkError("Account deletion failed: Unable to resolve host"))

        viewModel.submitDeleteAccount()
        advanceUntilIdle()

        assertEquals(AuthDialogType.DELETE_ACCOUNT, viewModel.formState.value.dialogType)
        assertFalse(viewModel.formState.value.isLoading)
        assertEquals("Network connection failed. Please check your connection.", viewModel.formState.value.errorMessage)
    }

    @Test
    fun testDeleteAccountWhileRunningIsNotSubmittedTwice() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.DELETE_ACCOUNT)
        val gate = CompletableDeferred<AuthResult<Unit>>()
        fakeAuthManager.deleteAccountGate = gate

        viewModel.submitDeleteAccount()
        advanceUntilIdle()
        assertTrue(viewModel.formState.value.isLoading)

        viewModel.submitDeleteAccount()
        advanceUntilIdle()
        assertEquals(1, fakeAuthManager.deleteAccountCallCount)

        gate.complete(AuthResult.Success(Unit))
        advanceUntilIdle()
        assertFalse(viewModel.formState.value.isLoading)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun testReopeningTheProfileAfterAFailedDeletionClearsTheError() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.DELETE_ACCOUNT)
        fakeAuthManager.deleteAccountResult = AuthResult.Error(AuthException.NetworkError())
        viewModel.submitDeleteAccount()
        advanceUntilIdle()
        assertNotNull(viewModel.formState.value.errorMessage)

        viewModel.openDialog(AuthDialogType.USER_PROFILE)

        assertNull(viewModel.formState.value.errorMessage)
        assertEquals(AuthDialogType.USER_PROFILE, viewModel.formState.value.dialogType)
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()

        override var currentUser: User? = null

        var deleteAccountCallCount = 0
        var deleteAccountResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var deleteAccountGate: CompletableDeferred<AuthResult<Unit>>? = null

        override suspend fun deleteAccount(): AuthResult<Unit> {
            deleteAccountCallCount++
            return deleteAccountGate?.await() ?: deleteAccountResult
        }

        var loginCallCount = 0
        var registerCallCount = 0
        var verifyEmailCallCount = 0
        var requestPasswordResetCallCount = 0
        var resetPasswordCallCount = 0
        var logoutCallCount = 0
        var adoptGuestDataCallCount = 0
        var lastAdoptedUserId: String? = null

        var loginResult: AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        var registerResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var verifyEmailResult: AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        var requestPasswordResetResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var resetPasswordResult: AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        var logoutResult: AuthResult<Unit> = AuthResult.Success(Unit)

        override suspend fun initialize() = Unit

        override suspend fun register(email: String, password: String): AuthResult<Unit> {
            registerCallCount++
            return registerResult
        }

        override suspend fun login(email: String, password: String): AuthResult<User> {
            loginCallCount++
            return loginResult
        }

        override suspend fun verifyEmail(token: String): AuthResult<User> {
            verifyEmailCallCount++
            return verifyEmailResult
        }

        var resendCallCount = 0
        var lastResendEmail: String? = null
        var resendResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var resendGate: CompletableDeferred<AuthResult<Unit>>? = null

        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> {
            resendCallCount++
            lastResendEmail = email
            return resendGate?.await() ?: resendResult
        }

        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> {
            requestPasswordResetCallCount++
            return requestPasswordResetResult
        }

        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> {
            resetPasswordCallCount++
            return resetPasswordResult
        }

        override suspend fun logout(): AuthResult<Unit> {
            logoutCallCount++
            return logoutResult
        }

        override suspend fun adoptGuestData(userId: String) {
            adoptGuestDataCallCount++
            lastAdoptedUserId = userId
        }
    }
}
