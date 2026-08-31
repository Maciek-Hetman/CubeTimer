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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun testResetPasswordSuccessClosesDialog() = testScope.runTest {
        viewModel.openDialog(AuthDialogType.RESET_PASSWORD)
        viewModel.onTokenChanged("valid-reset-token")
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

    private class FakeAuthManager : AuthManager {
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
            lastAdoptedUserId = userId
        }
    }
}
