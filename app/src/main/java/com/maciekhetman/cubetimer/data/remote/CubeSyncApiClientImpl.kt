package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.LogoutRequest
import com.maciekhetman.cubetimer.data.remote.dto.PasswordResetConfirmRequest
import com.maciekhetman.cubetimer.data.remote.dto.PasswordResetRequest
import com.maciekhetman.cubetimer.data.remote.dto.RefreshRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.ResendVerificationEmailRequest
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.data.remote.dto.VerifyEmailRequest
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException

class CubeSyncApiClientImpl(
    private val apiService: CubeSyncAuthApiService
) : CubeSyncApiClient {

    override suspend fun register(request: RegisterRequest): StatusResponse =
        executeSafe { apiService.register(request) }

    override suspend fun resendVerificationEmail(email: String): StatusResponse =
        executeSafe { apiService.resendVerificationEmail(ResendVerificationEmailRequest(email)) }

    override suspend fun verifyEmail(token: String): AuthResponse =
        executeSafe { apiService.verifyEmail(VerifyEmailRequest(token)) }

    override suspend fun login(request: LoginRequest): AuthResponse =
        executeSafe { apiService.login(request) }

    override suspend fun refreshToken(refreshToken: String): AuthResponse =
        executeSafe { apiService.refreshToken(RefreshRequest(refreshToken)) }

    override suspend fun logout(refreshToken: String): Unit =
        executeSafeUnit { apiService.logout(LogoutRequest(refreshToken)) }

    override suspend fun requestPasswordReset(email: String): StatusResponse =
        executeSafe { apiService.forgotPassword(PasswordResetRequest(email)) }

    override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse =
        executeSafe { apiService.resetPassword(PasswordResetConfirmRequest(token, newPassword)) }

    override suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse =
        executeSafe { apiService.loginWithGoogle(request) }

    override suspend fun linkGoogle(idToken: String, authToken: String?): Unit =
        executeSafeUnit {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" } ?: ""
            apiService.linkGoogleAccount(authHeader, GoogleAuthRequest(idToken = idToken))
        }

    override suspend fun getCurrentUser(authToken: String?): UserDto =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.getCurrentUser(authHeader)
        }

    override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?): Unit =
        executeSafeUnit {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.changePassword(authHeader, request)
        }

    override suspend fun deleteAccount(authToken: String?): Unit =
        executeSafeUnit {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.deleteAccount(authHeader)
        }

    override suspend fun sync(
        request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest,
        authToken: String?
    ): com.maciekhetman.cubetimer.data.remote.dto.SyncResponse =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.sync(authHeader, request)
        }

    override suspend fun snapshot(
        request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest,
        authToken: String?
    ): com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.snapshot(authHeader, request)
        }

    override suspend fun getAdminOverview(authToken: String?): com.maciekhetman.cubetimer.data.remote.dto.AdminOverviewDto =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.getAdminOverviewStats(authHeader)
        }

    override suspend fun getAdminRequestStats(
        from: String?,
        to: String?,
        interval: String?,
        authToken: String?
    ): com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsDto =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.getAdminRequestStats(authHeader, from, to, interval)
        }

    override suspend fun getAdminRequestTypeStats(
        from: String?,
        to: String?,
        interval: String?,
        authToken: String?
    ): com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeStatsDto =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.getAdminRequestTypeStats(authHeader, from, to, interval)
        }

    override suspend fun getAdminErrorLogs(before: String?, authToken: String?): com.maciekhetman.cubetimer.data.remote.dto.ErrorLogResponseDto =
        executeSafe {
            val authHeader = authToken?.let { if (it.startsWith("Bearer ")) it else "Bearer $it" }
            apiService.getAdminErrorLogs(authHeader, before)
        }

    private suspend fun <T : Any> executeSafe(call: suspend () -> Response<T>): T {
        try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    return body
                }
                throw AuthException.Unknown("Server returned empty body with status ${response.code()}")
            }

            val errorBodyString = response.errorBody()?.string()
            val apiError = ErrorParser.parseApiError(errorBodyString)
            val code = apiError?.error?.code
            val message = apiError?.error?.message ?: response.message().ifBlank { "HTTP Error ${response.code()}" }

            throw ErrorParser.toAuthException(code, message, response.code())
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            throw AuthException.NetworkError("Network request failed: ${e.message}", e)
        } catch (e: SerializationException) {
            throw AuthException.SerializationError("Failed to deserialize response: ${e.message}", e)
        } catch (e: Exception) {
            throw AuthException.Unknown("Unexpected error occurred: ${e.message}", e)
        }
    }

    private suspend fun executeSafeUnit(call: suspend () -> Response<Unit>) {
        try {
            val response = call()
            if (response.isSuccessful) {
                return
            }

            val errorBodyString = response.errorBody()?.string()
            val apiError = ErrorParser.parseApiError(errorBodyString)
            val code = apiError?.error?.code
            val message = apiError?.error?.message ?: response.message().ifBlank { "HTTP Error ${response.code()}" }

            throw ErrorParser.toAuthException(code, message, response.code())
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            throw AuthException.NetworkError("Network request failed: ${e.message}", e)
        } catch (e: SerializationException) {
            throw AuthException.SerializationError("Failed to deserialize response: ${e.message}", e)
        } catch (e: Exception) {
            throw AuthException.Unknown("Unexpected error occurred: ${e.message}", e)
        }
    }
}
