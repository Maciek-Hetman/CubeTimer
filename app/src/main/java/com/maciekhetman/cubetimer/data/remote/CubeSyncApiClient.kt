package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
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
    suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse

    @Throws(AuthException::class)
    suspend fun linkGoogle(idToken: String, authToken: String? = null)

    @Throws(AuthException::class)
    suspend fun getCurrentUser(authToken: String? = null): UserDto

    @Throws(AuthException::class)
    suspend fun changePassword(request: ChangePasswordRequest, authToken: String? = null)

    @Throws(AuthException::class)
    suspend fun deleteAccount(authToken: String? = null)

    @Throws(AuthException::class)
    suspend fun sync(
        request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest,
        authToken: String? = null
    ): com.maciekhetman.cubetimer.data.remote.dto.SyncResponse

    @Throws(AuthException::class)
    suspend fun snapshot(
        request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest,
        authToken: String? = null
    ): com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse

    @Throws(AuthException::class)
    suspend fun getAdminOverview(authToken: String? = null): com.maciekhetman.cubetimer.data.remote.dto.AdminOverviewDto =
        throw UnsupportedOperationException("Admin API not implemented in test stub")

    @Throws(AuthException::class)
    suspend fun getAdminRequestStats(
        from: String? = null,
        to: String? = null,
        interval: String? = null,
        authToken: String? = null
    ): com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsDto =
        throw UnsupportedOperationException("Admin API not implemented in test stub")

    @Throws(AuthException::class)
    suspend fun getAdminRequestTypeStats(
        from: String? = null,
        to: String? = null,
        interval: String? = null,
        authToken: String? = null
    ): com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeStatsDto =
        throw UnsupportedOperationException("Admin API not implemented in test stub")

    @Throws(AuthException::class)
    suspend fun getAdminErrorLogs(
        before: String? = null,
        authToken: String? = null
    ): com.maciekhetman.cubetimer.data.remote.dto.ErrorLogResponseDto =
        throw UnsupportedOperationException("Admin API not implemented in test stub")
}
