package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.model.AuthException

/**
 * Client abstraction wrapping the Retrofit API service and converting
 * raw HTTP responses into typed models or throwing typed [AuthException]s.
 */
interface CubeSyncApiClient {
    @Throws(AuthException::class)
    suspend fun register(request: RegisterRequest): StatusResponse

    @Throws(AuthException::class)
    suspend fun resendVerificationEmail(email: String): StatusResponse

    @Throws(AuthException::class)
    suspend fun verifyEmail(token: String): AuthResponse

    @Throws(AuthException::class)
    suspend fun login(request: LoginRequest): AuthResponse

    @Throws(AuthException::class)
    suspend fun refreshToken(refreshToken: String): AuthResponse

    @Throws(AuthException::class)
    suspend fun logout(refreshToken: String)

    @Throws(AuthException::class)
    suspend fun requestPasswordReset(email: String): StatusResponse

    @Throws(AuthException::class)
    suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse

    @Throws(AuthException::class)
    suspend fun changePassword(request: ChangePasswordRequest)

    @Throws(AuthException::class)
    suspend fun deleteAccount()

    @Throws(AuthException::class)
    suspend fun sync(
        request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
    ): com.maciekhetman.cubetimer.data.remote.dto.SyncResponse

    @Throws(AuthException::class)
    suspend fun snapshot(
        request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
    ): com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
}
