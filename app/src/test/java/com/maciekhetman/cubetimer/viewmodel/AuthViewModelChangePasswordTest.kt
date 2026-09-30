package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.ui.auth.AuthDialogType
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Changing the password from the profile: validation, one guarded request, and where the dialog goes
 * next depending on whether the auth manager still has a session afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelChangePasswordTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val application: Application = ApplicationProvider.getApplicationContext()
        fakeAuthManager = FakeAuthManager()
        viewModel = AuthViewModel(application = application, authManager = fakeAuthManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fillForm(
        current: String = "OldPassword123!",
        new: String = "NewPassword456!",
        confirm: String = new
    ) {
        viewModel.openDialog(AuthDialogType.CHANGE_PASSWORD)
        viewModel.onCurrentPasswordChanged(current)
        viewModel.onPasswordChanged(new)
        viewModel.onConfirmPasswordChanged(confirm)
    }

    @Test
    fun blankCurrentPassword_isRejectedWithoutARequest() = testScope.runTest {
        fillForm(current = "   ")

        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals("Current password is required", viewModel.formState.value.currentPasswordError)
        assertNull(viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.changePasswordCalls)
        assertFalse(viewModel.formState.value.isLoading)
    }

    @Test
    fun tooShortNewPassword_isRejectedWithTheExistingWording() = testScope.runTest {
        fillForm(new = "short")

        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals("Password must be at least 10 characters", viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.changePasswordCalls)
    }

    @Test
    fun newPasswordLengthBoundaries_are10To128Characters() = testScope.runTest {
        fillForm(new = "a".repeat(9))
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals(0, fakeAuthManager.changePasswordCalls)

        fillForm(new = "a".repeat(129))
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals("Password must be at most 128 characters", viewModel.formState.value.passwordError)
        assertEquals(0, fakeAuthManager.changePasswordCalls)

        fillForm(new = "a".repeat(10))
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals(1, fakeAuthManager.changePasswordCalls)

        fillForm(new = "b".repeat(128))
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals(2, fakeAuthManager.changePasswordCalls)
    }

    @Test
    fun mismatchedConfirmation_isRejectedWithoutARequest() = testScope.runTest {
        fillForm(new = "NewPassword456!", confirm = "NewPassword457!")

        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals("Passwords do not match", viewModel.formState.value.confirmPasswordError)
        assertEquals(0, fakeAuthManager.changePasswordCalls)
    }

    @Test
    fun everyInvalidFieldIsReportedAtOnce() = testScope.runTest {
        fillForm(current = "", new = "short", confirm = "other")

        viewModel.submitChangePassword()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals("Current password is required", state.currentPasswordError)
        assertEquals("Password must be at least 10 characters", state.passwordError)
        assertEquals("Passwords do not match", state.confirmPasswordError)
        assertEquals(0, fakeAuthManager.changePasswordCalls)
    }

    @Test
    fun aValidFormSendsTheCurrentAndTheNewPassword() = testScope.runTest {
        fillForm(current = "OldPassword123!", new = "NewPassword456!")

        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.changePasswordCalls)
        assertEquals("OldPassword123!", fakeAuthManager.lastCurrentPassword)
        assertEquals("NewPassword456!", fakeAuthManager.lastNewPassword)
    }

    @Test
    fun aSecondSubmitWhileTheRequestRuns_isIgnored() = testScope.runTest {
        val gate = CompletableDeferred<AuthResult<Unit>>()
        fakeAuthManager.changePasswordGate = gate
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertTrue(viewModel.formState.value.isLoading)
        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals(1, fakeAuthManager.changePasswordCalls)

        gate.complete(AuthResult.Success(Unit))
        advanceUntilIdle()

        assertFalse(viewModel.formState.value.isLoading)
        assertEquals(1, fakeAuthManager.changePasswordCalls)
    }

    @Test
    fun success_whileStillSignedIn_returnsToTheProfileAndClearsEveryPasswordField() = testScope.runTest {
        fakeAuthManager.signIn(User(id = "u1", email = "cuber@example.com", emailVerified = true))
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.USER_PROFILE, state.dialogType)
        assertEquals("Password changed.", state.successMessage)
        assertNull(state.errorMessage)
        assertEquals("", state.currentPassword)
        assertEquals("", state.password)
        assertEquals("", state.confirmPassword)
        assertFalse(state.isLoading)
    }

    @Test
    fun success_asAnAdmin_alsoReturnsToTheProfile() = testScope.runTest {
        fakeAuthManager.signIn(User(id = "a1", email = "admin@example.com", emailVerified = true), admin = true)
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertEquals(AuthDialogType.USER_PROFILE, viewModel.formState.value.dialogType)
        assertEquals("Password changed.", viewModel.formState.value.successMessage)
    }

    @Test
    fun success_whenTheFollowUpSignInFailed_opensLoginAndAsksToSignInAgain() = testScope.runTest {
        fakeAuthManager.signIn(User(id = "u1", email = "cuber@example.com", emailVerified = true))
        fakeAuthManager.onChangePassword = { fakeAuthManager.signOut() }
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.LOGIN, state.dialogType)
        assertEquals("Password changed. Sign in again with your new password.", state.successMessage)
        assertEquals("the sign-in form is ready for the user's address", "cuber@example.com", state.email)
        assertEquals("", state.currentPassword)
        assertEquals("", state.password)
        assertEquals("", state.confirmPassword)
        assertFalse(state.isLoading)
    }

    @Test
    fun wrongCurrentPassword_isAFieldErrorInTheChangeWordingNotTheLoginOne() = testScope.runTest {
        fakeAuthManager.signIn(User(id = "u1", email = "cuber@example.com", emailVerified = true))
        fakeAuthManager.changePasswordResult = AuthResult.Error(AuthException.InvalidCredentials())
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.CHANGE_PASSWORD, state.dialogType)
        assertEquals("Current password is incorrect.", state.currentPasswordError)
        assertNull("no login wording anywhere", state.errorMessage)
        assertFalse(state.isLoading)
        assertEquals("what was typed is kept for the retry", "NewPassword456!", state.password)
    }

    @Test
    fun editingTheCurrentPassword_clearsItsError() = testScope.runTest {
        fakeAuthManager.changePasswordResult = AuthResult.Error(AuthException.InvalidCredentials())
        fillForm()
        viewModel.submitChangePassword()
        advanceUntilIdle()

        viewModel.onCurrentPasswordChanged("OldPassword123")

        assertNull(viewModel.formState.value.currentPasswordError)
        assertEquals("OldPassword123", viewModel.formState.value.currentPassword)
    }

    @Test
    fun accountWithoutPassword_explainsHowToSetOne() = testScope.runTest {
        fakeAuthManager.changePasswordResult = AuthResult.Error(
            AuthException.ApiError(errorCode = "password_not_set", message = "internal detail", httpStatusCode = 409)
        )
        fillForm()

        viewModel.submitChangePassword()
        advanceUntilIdle()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.CHANGE_PASSWORD, state.dialogType)
        assertEquals(
            "This account has no password yet. Use \"Forgot password?\" on the sign-in screen to set one.",
            state.errorMessage
        )
        assertNull(state.currentPasswordError)
        assertFalse(state.isLoading)
    }

    @Test
    fun otherErrors_goThroughTheSharedMapping() = testScope.runTest {
        val cases = listOf(
            AuthException.NetworkError("boom") to "Network connection failed. Please check your connection.",
            AuthException.InvalidPassword() to "Password must be between 10 and 128 characters.",
            AuthException.RateLimited() to "Too many attempts. Please try again in a few moments.",
            AuthException.ApiError(errorCode = "something_else", message = "internal detail", httpStatusCode = 409) to
                "Something went wrong on the server. Please try again later."
        )
        for ((exception, message) in cases) {
            fakeAuthManager.changePasswordResult = AuthResult.Error(exception)
            fillForm()

            viewModel.submitChangePassword()
            advanceUntilIdle()

            val state = viewModel.formState.value
            assertEquals(message, state.errorMessage)
            assertEquals(AuthDialogType.CHANGE_PASSWORD, state.dialogType)
            assertNull(state.currentPasswordError)
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun aRetryAfterAnErrorClearsTheOldOneWhileTheRequestRuns() = testScope.runTest {
        fakeAuthManager.changePasswordResult = AuthResult.Error(AuthException.InvalidCredentials())
        fillForm()
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals("Current password is incorrect.", viewModel.formState.value.currentPasswordError)

        val gate = CompletableDeferred<AuthResult<Unit>>()
        fakeAuthManager.changePasswordGate = gate
        viewModel.submitChangePassword()
        advanceUntilIdle()

        assertTrue(viewModel.formState.value.isLoading)
        assertNull(viewModel.formState.value.currentPasswordError)
        gate.complete(AuthResult.Success(Unit))
        advanceUntilIdle()
    }

    @Test
    fun openingADialog_clearsTheCurrentPasswordError() = testScope.runTest {
        fakeAuthManager.changePasswordResult = AuthResult.Error(AuthException.InvalidCredentials())
        fillForm()
        viewModel.submitChangePassword()
        advanceUntilIdle()
        assertEquals("Current password is incorrect.", viewModel.formState.value.currentPasswordError)

        viewModel.openDialog(AuthDialogType.USER_PROFILE)

        assertNull(viewModel.formState.value.currentPasswordError)
    }

    @Test
    fun cancelling_returnsToTheProfileAndDropsWhatWasTyped() {
        fillForm()

        viewModel.cancelChangePassword()

        val state = viewModel.formState.value
        assertEquals(AuthDialogType.USER_PROFILE, state.dialogType)
        assertEquals("", state.currentPassword)
        assertEquals("", state.password)
        assertEquals("", state.confirmPassword)
        assertEquals(0, fakeAuthManager.changePasswordCalls)
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val currentUser: User?
            get() = when (val state = _authState.value) {
                is AuthState.Authenticated -> state.user
                is AuthState.Admin -> state.user
                else -> null
            }

        var changePasswordCalls = 0
        var lastCurrentPassword: String? = null
        var lastNewPassword: String? = null
        var changePasswordResult: AuthResult<Unit> = AuthResult.Success(Unit)
        var changePasswordGate: CompletableDeferred<AuthResult<Unit>>? = null

        /** Runs while the request is "on the wire", e.g. to model the session being lost. */
        var onChangePassword: (() -> Unit)? = null

        fun signIn(user: User, admin: Boolean = false) {
            _authState.value = if (admin) AuthState.Admin(user) else AuthState.Authenticated(user)
        }

        fun signOut() {
            _authState.value = AuthState.Guest
        }

        override suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> {
            changePasswordCalls++
            lastCurrentPassword = currentPassword
            lastNewPassword = newPassword
            val result = changePasswordGate?.await() ?: changePasswordResult
            onChangePassword?.invoke()
            return result
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
