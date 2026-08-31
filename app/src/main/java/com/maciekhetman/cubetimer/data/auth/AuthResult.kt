package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.AuthException

/**
 * Result wrapper for authentication operations.
 */
sealed class AuthResult<out T> {
    data class Success<out T>(val data: T) : AuthResult<T>()
    data class Error(val exception: AuthException) : AuthResult<Nothing>()
}
