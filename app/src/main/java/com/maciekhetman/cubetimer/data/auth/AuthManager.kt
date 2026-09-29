package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

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
     * Suspends until startup authentication has finished restoring the session, i.e. until
     * [initialize] has run to completion - including its token refresh, whatever the outcome.
     *
     * Leaving [AuthState.Loading] is not enough: the cached identity is published before the
     * refresh, while the in-memory access token only exists once the refresh has returned. Work
     * that talks to the server (sync) must wait for this, or its unauthenticated request races
     * the startup refresh with the same refresh token and trips the server's reuse detection.
     *
     * The default waits for [authState] to leave [AuthState.Loading]; implementations that
     * publish an interim state before initialization completes must override it. May never
     * return if nothing ever initializes, so callers should bound the wait.
     */
    suspend fun awaitInitialized() {
        authState.first { it !is AuthState.Loading }
    }

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
     * Authenticate using a Google Sign-in ID token.
     * [clientId] is the OAuth client ID the token was issued for (its `aud`) and [nonce] the value
     * passed to Google when requesting it; the backend rejects the token if either doesn't match.
     * Adopts guest data and sets active user session.
     */
    suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User>

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
     * Permanently deletes the signed-in user's account and its synced data on the server, then
     * signs out like [logout]. The user's solves and sessions stay on the device as guest data
     * (never synced, pending mutations, conflicts and sync cursor discarded).
     *
     * On failure (offline, rejected by the server) the user stays signed in and nothing changes
     * locally. The default reports failure so implementations that don't support deletion (test
     * fakes) need not override it.
     */
    suspend fun deleteAccount(): AuthResult<Unit> =
        AuthResult.Error(AuthException.Unknown("Account deletion is not supported"))

    /**
     * Atomically reassigns all guest solves and guest sessions to the newly authenticated user
     * and enqueues sync outbox mutations.
     */
    suspend fun adoptGuestData(userId: String)
}
