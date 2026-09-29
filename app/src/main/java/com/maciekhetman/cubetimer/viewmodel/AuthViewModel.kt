package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.ui.auth.AuthDialogType
import com.maciekhetman.cubetimer.ui.auth.AuthFormState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    application: Application,
    private val authManager: AuthManager
) : AndroidViewModel(application) {

    val authState: StateFlow<AuthState> = authManager.authState

    private val _formState = MutableStateFlow(AuthFormState())
    val formState: StateFlow<AuthFormState> = _formState.asStateFlow()

    fun openDialog(type: AuthDialogType) {
        _formState.update {
            it.copy(
                dialogType = type,
                errorMessage = null,
                successMessage = null,
                emailError = null,
                passwordError = null,
                confirmPasswordError = null,
                tokenError = null
            )
        }
    }

    fun dismissDialog() {
        _formState.update { it.copy(dialogType = AuthDialogType.NONE) }
    }

    fun onEmailChanged(value: String) {
        _formState.update { it.copy(email = value, emailError = null, errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _formState.update { it.copy(password = value, passwordError = null, errorMessage = null) }
    }

    fun onConfirmPasswordChanged(value: String) {
        _formState.update { it.copy(confirmPassword = value, confirmPasswordError = null, errorMessage = null) }
    }

    fun onTokenChanged(value: String) {
        _formState.update { it.copy(token = value, tokenError = null, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _formState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun submitLogin() {
        val state = _formState.value
        if (!validateLoginForm(state)) return

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.login(state.email.trim(), state.password)) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.NONE,
                            password = "",
                            confirmPassword = ""
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun submitRegister() {
        val state = _formState.value
        if (!validateRegisterForm(state)) return

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.register(state.email.trim(), state.password)) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.EMAIL_VERIFICATION,
                            successMessage = "Account created! Please check your email for the verification code.",
                            password = "",
                            confirmPassword = ""
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun submitVerifyEmail() {
        val state = _formState.value
        if (state.token.isBlank()) {
            _formState.update { it.copy(tokenError = "Verification token is required") }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.verifyEmail(state.token.trim())) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.NONE,
                            token = ""
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun submitForgotPassword() {
        val state = _formState.value
        if (!validateEmail(state.email)) {
            _formState.update { it.copy(emailError = "Please enter a valid email address") }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.requestPasswordReset(state.email.trim())) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.RESET_PASSWORD,
                            successMessage = "Password reset instructions sent. Enter your token below."
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun submitResetPassword() {
        val state = _formState.value
        if (!validateResetPasswordForm(state)) return

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.resetPassword(state.token.trim(), state.password)) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.NONE,
                            password = "",
                            confirmPassword = "",
                            token = ""
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun submitLogout() {
        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true) }
            authManager.logout()
            _formState.update {
                it.copy(
                    isLoading = false,
                    dialogType = AuthDialogType.NONE,
                    email = "",
                    password = "",
                    confirmPassword = "",
                    token = ""
                )
            }
        }
    }

    fun submitDeleteAccount() {
        if (_formState.value.isLoading) return

        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = authManager.deleteAccount()) {
                is AuthResult.Success -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            dialogType = AuthDialogType.NONE,
                            email = "",
                            password = "",
                            confirmPassword = "",
                            token = ""
                        )
                    }
                }
                is AuthResult.Error -> {
                    _formState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = reportError(result.exception)
                        )
                    }
                }
            }
        }
    }

    fun adoptGuestData() {
        val user = authManager.currentUser ?: return
        viewModelScope.launch {
            _formState.update { it.copy(isLoading = true) }
            authManager.adoptGuestData(user.id)
            _formState.update { it.copy(isLoading = false, successMessage = "Local solves imported successfully") }
        }
    }

    private fun validateEmail(email: String): Boolean {
        val trimmed = email.trim()
        if (trimmed.isBlank()) return false
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return emailRegex.matches(trimmed)
    }

    private fun validateLoginForm(state: AuthFormState): Boolean {
        var valid = true
        if (!validateEmail(state.email)) {
            _formState.update { it.copy(emailError = "Valid email is required") }
            valid = false
        }
        if (state.password.isBlank()) {
            _formState.update { it.copy(passwordError = "Password is required") }
            valid = false
        }
        return valid
    }

    private fun validateRegisterForm(state: AuthFormState): Boolean {
        var valid = true
        if (!validateEmail(state.email)) {
            _formState.update { it.copy(emailError = "Valid email is required") }
            valid = false
        }
        if (state.password.length < 10) {
            _formState.update { it.copy(passwordError = "Password must be at least 10 characters") }
            valid = false
        }
        if (state.password != state.confirmPassword) {
            _formState.update { it.copy(confirmPasswordError = "Passwords do not match") }
            valid = false
        }
        return valid
    }

    private fun validateResetPasswordForm(state: AuthFormState): Boolean {
        var valid = true
        if (state.token.isBlank()) {
            _formState.update { it.copy(tokenError = "Token is required") }
            valid = false
        }
        if (state.password.length < 10) {
            _formState.update { it.copy(passwordError = "Password must be at least 10 characters") }
            valid = false
        }
        if (state.password != state.confirmPassword) {
            _formState.update { it.copy(confirmPasswordError = "Passwords do not match") }
            valid = false
        }
        return valid
    }

    /** Logs the raw failure (never shown to the user) and returns its user-facing message. */
    private fun reportError(ex: AuthException): String {
        Log.w(TAG, "Auth request failed: ${ex::class.simpleName}", ex)
        return mapAuthError(ex)
    }

    /** A fixed, human-readable message per exception type; server-supplied text is never surfaced. */
    fun mapAuthError(ex: AuthException): String = when (ex) {
        is AuthException.InvalidCredentials -> "Incorrect email or password."
        is AuthException.EmailNotVerified -> "Email is not verified. Please verify your account."
        is AuthException.EmailAlreadyExists -> "An account with this email already exists."
        is AuthException.InvalidToken -> "Invalid or expired verification/reset token."
        is AuthException.InvalidRefreshToken -> "Your session has expired. Please log in again."
        is AuthException.RefreshTokenReused -> "Your session was ended for security reasons. Please log in again."
        is AuthException.AccountLinkRequired ->
            "An account with this email already exists. Please log in with your email and password."
        is AuthException.InvalidSocialToken -> "Google sign-in failed. Please try again."
        is AuthException.IdentityAlreadyLinked -> "This Google account is already linked to another user."
        is AuthException.RateLimited -> "Too many attempts. Please try again in a few moments."
        is AuthException.InvalidPassword -> "Password must be between 10 and 128 characters."
        is AuthException.InvalidEmail -> "Please enter a valid email address."
        is AuthException.EmailDeliveryFailed -> "We couldn't send the email right now. Please try again later."
        is AuthException.Forbidden -> "Access denied. You don't have permission to do that."
        is AuthException.Unauthorized -> "Session expired. Please log in again."
        is AuthException.CursorExpired -> "Your data is out of date. Please try again."
        is AuthException.NetworkError -> "Network connection failed. Please check your connection."
        is AuthException.SerializationError -> "The server sent an unexpected response. Please try again later."
        is AuthException.ApiError -> "Something went wrong on the server. Please try again later."
        is AuthException.Unknown -> "Something went wrong. Please try again."
    }

    private companion object {
        const val TAG = "AuthViewModel"
    }
}
