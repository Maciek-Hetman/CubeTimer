package com.maciekhetman.cubetimer.data.auth

/**
 * Callback invoked when refresh token validation fails permanently,
 * indicating that the user's session has expired and credentials were purged.
 */
fun interface SessionExpirationListener {
    fun onSessionExpired()
}
