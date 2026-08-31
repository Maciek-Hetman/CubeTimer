package com.maciekhetman.cubetimer.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request payload for POST /v1/auth/register
 */
@Serializable
data class RegisterRequest(
    @SerialName("email")
    val email: String,
    @SerialName("password")
    val password: String
)

/**
 * Request payload for POST /v1/auth/login
 */
@Serializable
data class LoginRequest(
    @SerialName("email")
    val email: String,
    @SerialName("password")
    val password: String
)

/**
 * Request payload for POST /v1/auth/refresh
 */
@Serializable
data class RefreshRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

/**
 * Request payload for POST /v1/auth/logout
 */
@Serializable
data class LogoutRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

/**
 * Request payload for POST /v1/auth/email/verify
 */
@Serializable
data class VerifyEmailRequest(
    @SerialName("token")
    val token: String
)

/**
 * Request payload for POST /v1/auth/email/resend
 */
@Serializable
data class ResendVerificationEmailRequest(
    @SerialName("email")
    val email: String
)

/**
 * Request payload for POST /v1/auth/password/forgot
 */
@Serializable
data class PasswordResetRequest(
    @SerialName("email")
    val email: String
)

/**
 * Request payload for POST /v1/auth/password/reset
 */
@Serializable
data class PasswordResetConfirmRequest(
    @SerialName("token")
    val token: String,
    @SerialName("new_password")
    val newPassword: String
)

/**
 * Request payload for POST /v1/auth/federated/google and POST /v1/auth/link/google
 */
@Serializable
data class GoogleAuthRequest(
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("client_id")
    val clientId: String? = null,
    @SerialName("nonce")
    val nonce: String? = null,
    @SerialName("code")
    val code: String? = null,
    @SerialName("redirect_uri")
    val redirectUri: String? = null,
    @SerialName("code_verifier")
    val codeVerifier: String? = null
)

/**
 * Request payload for PUT /v1/me/password
 */
@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password")
    val currentPassword: String? = null,
    @SerialName("new_password")
    val newPassword: String
)

/**
 * User representation returned in auth session responses and GET /v1/me.
 */
@Serializable
data class UserDto(
    @SerialName("id")
    val id: String,
    @SerialName("email")
    val email: String,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("user_role")
    val userRole: String = "user",
    @SerialName("email_verified")
    val emailVerified: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Complete Auth Session returned on successful login, verify email, refresh,
 * password reset confirmation, and federated Google sign-in.
 */
@Serializable
data class AuthResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 900L,
    @SerialName("user")
    val user: UserDto
)

/**
 * Token response representation for token-only operations.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 900L
)

/**
 * Generic status response returned by HTTP 202 endpoints (register, resend, forgot).
 */
@Serializable
data class StatusResponse(
    @SerialName("status")
    val status: String
)
