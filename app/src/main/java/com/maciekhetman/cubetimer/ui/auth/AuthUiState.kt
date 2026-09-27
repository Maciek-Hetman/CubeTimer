package com.maciekhetman.cubetimer.ui.auth

/**
 * Types of authentication dialogs displayed in the application modal flow.
 */
enum class AuthDialogType {
    NONE,
    LOGIN,
    REGISTER,
    FORGOT_PASSWORD,
    RESET_PASSWORD,
    EMAIL_VERIFICATION,
    USER_PROFILE
}

/**
 * Form state for authentication inputs, validations, loading flags, and error messages.
 */
data class AuthFormState(
    val dialogType: AuthDialogType = AuthDialogType.NONE,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val token: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val tokenError: String? = null,
    val isPasswordVisible: Boolean = false
)
