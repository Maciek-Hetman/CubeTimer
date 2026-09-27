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
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Retrofit interface representing the CubeSync authentication and user profile REST API endpoints.
 */
interface CubeSyncAuthApiService {

    @POST("v1/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<StatusResponse>

    @POST("v1/auth/email/resend")
    suspend fun resendVerificationEmail(
        @Body request: ResendVerificationEmailRequest
    ): Response<StatusResponse>

    @POST("v1/auth/email/verify")
    suspend fun verifyEmail(
        @Body request: VerifyEmailRequest
    ): Response<AuthResponse>

    @POST("v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<AuthResponse>

    @POST("v1/auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshRequest
    ): Response<AuthResponse>

    @POST("v1/auth/logout")
    suspend fun logout(
        @Body request: LogoutRequest
    ): Response<Unit>

    @POST("v1/auth/password/forgot")
    suspend fun forgotPassword(
        @Body request: PasswordResetRequest
    ): Response<StatusResponse>

    @POST("v1/auth/password/reset")
    suspend fun resetPassword(
        @Body request: PasswordResetConfirmRequest
    ): Response<AuthResponse>

    @POST("v1/auth/federated/google")
    suspend fun loginWithGoogle(
        @Body request: GoogleAuthRequest
    ): Response<AuthResponse>

    @POST("v1/auth/link/google")
    suspend fun linkGoogleAccount(
        @Header("Authorization") authorization: String,
        @Body request: GoogleAuthRequest
    ): Response<Unit>

    @GET("v1/me")
    suspend fun getCurrentUser(
        @Header("Authorization") authorization: String? = null
    ): Response<UserDto>

    @PUT("v1/me/password")
    suspend fun changePassword(
        @Header("Authorization") authorization: String? = null,
        @Body request: ChangePasswordRequest
    ): Response<Unit>

    @DELETE("v1/me")
    suspend fun deleteAccount(
        @Header("Authorization") authorization: String? = null
    ): Response<Unit>

    @POST("v1/sync")
    suspend fun sync(
        @Header("Authorization") authorization: String? = null,
        @Body request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
    ): Response<com.maciekhetman.cubetimer.data.remote.dto.SyncResponse>

    @POST("v1/snapshot")
    suspend fun snapshot(
        @Header("Authorization") authorization: String? = null,
        @Body request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
    ): Response<com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse>
}
