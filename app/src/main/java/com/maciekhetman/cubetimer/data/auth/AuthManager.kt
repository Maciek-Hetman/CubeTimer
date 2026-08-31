package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.StateFlow

/**
 * Main coordinator for authentication state, login, registration, token refresh,
 * guest account adoption, and session cleanup.
 */
interface AuthManager {
    /**
     * Reactive stream of current authentication state.
     */
    val authState: StateFlow<AuthState>

    /**
     * Currently authenticated user, or null if guest / loading.
     */
    val currentUser: User?

    /**
     * Active ownerId string ("guest" or userId).
     */
    val currentOwnerId: String
        get() = currentUser?.id ?: "guest"

    /**
     * Initializes authentication state from persistent storage on startup.
     */
    suspend fun initialize()

    /**
     * Register a new account with email and password.
     */
    suspend fun register(email: String, password: String): AuthResult<Unit>

    /**
     * Authenticate with email and password.
     * Adopts guest data and sets active user session.
     */
    suspend fun login(email: String, password: String): AuthResult<User>

    /**
     * Authenticate using Google Federated Sign-in ID token.
     * Adopts guest data and sets active user session.
     */
    suspend fun loginWithGoogle(idToken: String): AuthResult<User>

    /**
     * Verify an email verification token received via email.
     * Creates session, adopts guest data, and authenticates user.
     */
    suspend fun verifyEmail(token: String): AuthResult<User>

    /**
     * Resend verification email to user.
     */
    suspend fun resendVerificationEmail(email: String): AuthResult<Unit>

    /**
     * Request a password reset email.
     */
    suspend fun requestPasswordReset(email: String): AuthResult<Unit>

    /**
     * Reset password using token received via email.
     * Creates session, adopts guest data, and authenticates user.
     */
    suspend fun resetPassword(token: String, newPassword: String): AuthResult<User>

    /**
     * Refreshes the active session using the stored refresh token.
     */
    suspend fun refreshSession(): AuthResult<User>

    /**
     * Log out current user, close open auto sessions, revoke refresh token,
     * wipe local token storage, and revert to guest state.
     */
    suspend fun logout(): AuthResult<Unit>

    /**
     * Atomically reassigns all guest solves and guest sessions to the newly authenticated user
     * and enqueues sync outbox mutations.
     */
    suspend fun adoptGuestData(userId: String)
}
