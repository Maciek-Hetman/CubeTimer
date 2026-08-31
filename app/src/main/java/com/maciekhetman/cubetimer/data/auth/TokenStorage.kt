package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.StateFlow

/**
 * Storage abstraction for access token, refresh token, user session metadata, and device ID.
 */
interface TokenStorage {
    // In-memory access token (short-lived, never saved to disk)
    val accessTokenFlow: StateFlow<String?>
    fun getAccessToken(): String?
    fun setAccessToken(token: String?)

    // Persistent refresh token & user metadata
    fun getRefreshToken(): String?
    fun setRefreshToken(token: String?)
    fun getUserId(): String?
    fun getUserEmail(): String?
    fun getUserRole(): String?
    fun isUserEmailVerified(): Boolean
    fun getDisplayName(): String?

    // Cached User helper
    fun getCachedUser(): User?

    // Combined session saver
    fun saveAuthSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        userEmail: String,
        userRole: String,
        emailVerified: Boolean = true,
        displayName: String? = null
    )

    fun saveUser(user: User)

    // Device identity (persists across logouts)
    fun getDeviceId(): String

    // Session clearing
    fun clearAuthData() // Clears tokens and user metadata, preserves deviceId
    fun clearAll()      // Clears everything including deviceId (factory reset)

    // Auth state query
    fun isAuthenticated(): Boolean = !getRefreshToken().isNullOrBlank()
}
