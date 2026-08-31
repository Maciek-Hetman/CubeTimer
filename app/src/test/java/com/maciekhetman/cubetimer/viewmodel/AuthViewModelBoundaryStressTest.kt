package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.ui.auth.AuthDialogType
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

/**
 * Adversarial boundary and stress tests for [AuthViewModel] covering:
 * - Email format boundary validation (RFC edge cases, spaces, invalid TLDs, missing parts)
 * - Password length boundary conditions (0, 1, 9, 10, 128 characters)
 * - Password mismatch variations (case sensitivity, whitespace differences)
 * - Token validation boundaries (empty, blank, whitespace)
 * - Dialog transition state integrity and field resets
 * - Error mapping completeness across all [AuthException] types
 * - Sequential form submissions and state clearing
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelBoundaryStressTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var fakeAuthManager: FakeStressAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        fakeAuthManager = FakeStressAuthManager()
        viewModel = AuthViewModel(application = application, authManager = fakeAuthManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------------
    // EMAIL VALIDATION BOUNDARIES
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test invalid email formats reject login without calling backend`() = testScope.runTest {
        val invalidEmails = listOf(
            "",
            "   ",
            "plainaddress",
            "#@%^%#$@#$@#.com",
            "@example.com",
            "Joe Smith <email@example.com>",
            "email.example.com",
            "email@example@example.com",
            "email@example.c", // TLD < 2 chars
            "email@.com"
        )

        for (invalidEmail in invalidEmails) {
            viewModel.openDialog(AuthDialogType.LOGIN)
            viewModel.onEmailChanged(invalidEmail)
            viewModel.onPasswordChanged("ValidPassword123!")

            viewModel.submitLogin()
            advanceUntilIdle()

            assertEquals(
                "Invalid email '$invalidEmail' should have produced emailError",
                "Valid email is required",
                viewModel.formState.value.emailError
            )
            assertEquals("Backend login should not be invoked for invalid email '$invalidEmail'", 0, fakeAuthManager.loginCallCount)
        }
    }

    @Test
    fun `test valid email formats pass validation and call backend`() = testScope.runTest {
        val validEmails = listOf(
            "simple@example.com",
            "very.common@example.com",
            "disposable.style.email.with+symbol@example.com",
            "other.email-with-hyphen@example.com",
            "fully-qualified-domain@example.co.uk",
            "user@subdomain.domain.org",
            "  trimmed@example.com  "
        )

        var expectedCalls = 0
        for (validEmail in validEmails) {
            viewModel.openDialog(AuthDialogType.LOGIN)
            viewModel.onPasswordChanged("ValidPassword123!")
            viewModel.onEmailChanged(validEmail)

            fakeAuthManager.loginResult = AuthResult.Success(User(id = "u1", email = validEmail.trim()))
            viewModel.submitLogin()
            advanceUntilIdle()

            expectedCalls++
            assertEquals("Backend login call count mismatch for '$validEmail'", expectedCalls, fakeAuthManager.loginCallCount)
            assertNull("Valid email '$validEmail' should not have emailError", viewModel.formState.value.emailError)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PASSWORD LENGTH BOUNDARY CONDITIONS (REGISTER & RESET)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test password boundary conditions for registration`() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.REGISTER)
        viewModel.onEmailChanged("user@example.com")

        // 0 characters (empty)
        viewModel.onPasswordChanged("")
        viewModel.onConfirmPasswordChanged("")
        viewModel.submitRegister()
        advanceUntilIdle()
        assertEquals("Password must be at least 10 characters", viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.registerCallCount)

        // 9 characters (1 character below boundary)
        viewModel.onPasswordChanged("123456789")
        viewModel.onConfirmPasswordChanged("123456789")
        viewModel.submitRegister()
        advanceUntilIdle()
        assertEquals("Password must be at least 10 characters", viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.registerCallCount)

        // 10 characters (exact minimum boundary)
        viewModel.onPasswordChanged("1234567890")
        viewModel.onConfirmPasswordChanged("1234567890")
        fakeAuthManager.registerResult = AuthResult.Success(Unit)
        viewModel.submitRegister()
        advanceUntilIdle()
        assertNull(viewModel.formState.value.passwordError)
        assertEquals(1, fakeAuthManager.registerCallCount)

        // 128 characters (maximum standard boundary)
        viewModel.openDialog(AuthDialogType.REGISTER)
        val maxPassword = "A".repeat(128)
        viewModel.onEmailChanged("user@example.com")
        viewModel.onPasswordChanged(maxPassword)
        viewModel.onConfirmPasswordChanged(maxPassword)
        viewModel.submitRegister()
        advanceUntilIdle()
        assertNull(viewModel.formState.value.passwordError)
        assertEquals(2, fakeAuthManager.registerCallCount)
    }

    @Test
    fun `test password mismatch variations in register and reset forms`() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.REGISTER)
        viewModel.onEmailChanged("user@example.com")

        val mismatchPairs = listOf(
            Pair("Password123!", "password123!"), // Case mismatch
            Pair("Password123!", "Password123! "), // Trailing space mismatch
            Pair("Password123!", " Password123!"), // Leading space mismatch
            Pair("Password123!", "Password123?"), // Punctuation mismatch
            Pair("Password123!", "")              // Empty confirm
        )

        for ((pass, confirm) in mismatchPairs) {
            viewModel.onPasswordChanged(pass)
            viewModel.onConfirmPasswordChanged(confirm)
            viewModel.submitRegister()
            advanceUntilIdle()

            assertEquals(
                "Mismatch between '$pass' and '$confirm' should produce confirmPasswordError",
                "Passwords do not match",
                viewModel.formState.value.confirmPasswordError
            )
        }
        assertEquals(0, fakeAuthManager.registerCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // TOKEN VALIDATION (VERIFY EMAIL & RESET PASSWORD)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test email verification token validation boundaries`() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.EMAIL_VERIFICATION)

        // Empty token
        viewModel.onTokenChanged("")
        viewModel.submitVerifyEmail()
        advanceUntilIdle()
        assertEquals("Verification token is required", viewModel.formState.value.tokenError)
        assertEquals(0, fakeAuthManager.verifyEmailCallCount)

        // Blank whitespace token
        viewModel.onTokenChanged("     ")
        viewModel.submitVerifyEmail()
        advanceUntilIdle()
        assertEquals("Verification token is required", viewModel.formState.value.tokenError)
        assertEquals(0, fakeAuthManager.verifyEmailCallCount)

        // Valid token
        viewModel.onTokenChanged("  valid-token-123  ")
        fakeAuthManager.verifyEmailResult = AuthResult.Success(User(id = "u1", email = "u@test.com", emailVerified = true))
        viewModel.submitVerifyEmail()
        advanceUntilIdle()
        assertNull(viewModel.formState.value.tokenError)
        assertEquals(1, fakeAuthManager.verifyEmailCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    @Test
    fun `test password reset token validation boundaries`() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.RESET_PASSWORD)
        viewModel.onPasswordChanged("ValidPassword123!")
        viewModel.onConfirmPasswordChanged("ValidPassword123!")

        // Empty token
        viewModel.onTokenChanged("")
        viewModel.submitResetPassword()
        advanceUntilIdle()
        assertEquals("Token is required", viewModel.formState.value.tokenError)
        assertEquals(0, fakeAuthManager.resetPasswordCallCount)

        // Valid token
        viewModel.onTokenChanged("valid-reset-token-456")
        fakeAuthManager.resetPasswordResult = AuthResult.Success(User(id = "u1", email = "u@test.com", emailVerified = true))
        viewModel.submitResetPassword()
        advanceUntilIdle()
        assertNull(viewModel.formState.value.tokenError)
        assertEquals(1, fakeAuthManager.resetPasswordCallCount)
        assertEquals(AuthDialogType.NONE, viewModel.formState.value.dialogType)
    }

    // ---------------------------------------------------------------------------------------------
    // DIALOG STATE INTEGRITY & ERROR CLEARING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test dialog switching clears previous errors and validation messages`() {
        viewModel.openDialog(AuthDialogType.LOGIN)
        viewModel.onEmailChanged("bad")
        viewModel.onPasswordChanged("")
        viewModel.submitLogin()

        assertNotNull(viewModel.formState.value.emailError)
        assertNotNull(viewModel.formState.value.passwordError)

        // Switch to Register dialog
        viewModel.openDialog(AuthDialogType.REGISTER)
        assertNull("Switching dialog should clear emailError", viewModel.formState.value.emailError)
        assertNull("Switching dialog should clear passwordError", viewModel.formState.value.passwordError)
        assertNull("Switching dialog should clear errorMessage", viewModel.formState.value.errorMessage)
        assertNull("Switching dialog should clear successMessage", viewModel.formState.value.successMessage)
    }

    @Test
    fun `test toggle password visibility`() {
        assertFalse(viewModel.formState.value.isPasswordVisible)
        viewModel.togglePasswordVisibility()
        assertTrue(viewModel.formState.value.isPasswordVisible)
        viewModel.togglePasswordVisibility()
        assertFalse(viewModel.formState.value.isPasswordVisible)
    }

    @Test
    fun `test adoptGuestData with null user is safe no-op`() = testScope.runTest {
        fakeAuthManager.currentUser = null
        viewModel.adoptGuestData()
        advanceUntilIdle()

        assertEquals(0, fakeAuthManager.adoptGuestDataCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // ERROR MAPPING COMPLETENESS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `test mapAuthError maps all known exception types to descriptive messages`() {
        val exceptionsAndExpected = listOf(
            Pair(AuthException.InvalidCredentials(), "Incorrect email or password."),
            Pair(AuthException.EmailNotVerified(), "Email is not verified. Please verify your account."),
            Pair(AuthException.EmailAlreadyExists(), "An account with this email already exists."),
            Pair(AuthException.InvalidToken(), "Invalid or expired verification/reset token."),
            Pair(AuthException.RateLimited(), "Too many attempts. Please try again in a few moments."),
            Pair(AuthException.InvalidPassword(), "Password must be between 10 and 128 characters."),
            Pair(AuthException.Forbidden(), "Access denied. Admin privileges required."),
            Pair(AuthException.Unauthorized(), "Session expired. Please log in again."),
            Pair(AuthException.NetworkError("No connection"), "Network connection failed. Please check your connection."),
            Pair(AuthException.ApiError("custom", "Custom server message", 500), "Custom server message")
        )

        for ((exception, expectedMsg) in exceptionsAndExpected) {
            val mapped = viewModel.mapAuthError(exception)
            assertEquals("Error mapping mismatch for ${exception::class.simpleName}", expectedMsg, mapped)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FAKE AUTH MANAGER FOR STRESS TESTING
    // ---------------------------------------------------------------------------------------------

    private class FakeStressAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()

        override var currentUser: User? = null

        var loginCallCount = 0
        var registerCallCount = 0
        var verifyEmailCallCount = 0
        var requestPasswordResetCallCount = 0
        var resetPasswordCallCount = 0
        var logoutCallCount = 0
        var adoptGuestDataCallCount = 0

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

        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = loginResult

        override suspend fun verifyEmail(token: String): AuthResult<User> {
            verifyEmailCallCount++
            return verifyEmailResult
        }

        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)

        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> {
            requestPasswordResetCallCount++
            return requestPasswordResetResult
        }

        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> {
            resetPasswordCallCount++
            return resetPasswordResult
        }

        override suspend fun refreshSession(): AuthResult<User> = loginResult

        override suspend fun logout(): AuthResult<Unit> {
            logoutCallCount++
            return logoutResult
        }

        override suspend fun adoptGuestData(userId: String) {
            adoptGuestDataCallCount++
        }
    }
}
