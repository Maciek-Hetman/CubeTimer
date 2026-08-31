package com.maciekhetman.cubetimer.data.remote

import android.util.Log
import com.maciekhetman.cubetimer.data.auth.SessionExpirationListener
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.RefreshRequest
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp Authenticator that transparently refreshes expired access tokens upon HTTP 401 Unauthorized.
 * Uses mutex synchronization and double-checked token validation to prevent thundering herd race conditions.
 */
class TokenAuthenticator(
    private val tokenStorage: TokenStorage,
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
    var sessionExpirationListener: SessionExpirationListener? = null
) : Authenticator {

    private val refreshLock = Any()

    // Isolated unauthenticated OkHttpClient for synchronous token refresh calls
    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // 1. Guard against infinite retry loops
        if (responseCount(response) >= MAX_RETRIES) {
            Log.w(TAG, "Max retry limit ($MAX_RETRIES) reached on 401 response. Aborting refresh.")
            return null
        }

        // 2. Guard against refreshing for authentication endpoints themselves
        val path = response.request.url.encodedPath
        if (isAuthEndpoint(path)) {
            Log.d(TAG, "401 received on auth endpoint ($path). Skipping refresh.")
            return null
        }

        val failedAuthorization = response.request.header(AuthInterceptor.HEADER_AUTHORIZATION)
        val failedToken = failedAuthorization?.removePrefix("Bearer ")?.trim()

        // 3. Synchronize refresh to prevent thundering herd and token family invalidation
        synchronized(refreshLock) {
            val currentAccessToken = tokenStorage.getAccessToken()

            // Double-checked locking: If another thread already refreshed the token, reuse it
            if (!currentAccessToken.isNullOrBlank() && currentAccessToken != failedToken) {
                Log.d(TAG, "Token was refreshed by a concurrent request. Retrying failed call with new token.")
                return response.request.newBuilder()
                    .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $currentAccessToken")
                    .build()
            }

            // Retrieve persistent refresh token
            val storedRefreshToken = tokenStorage.getRefreshToken()
            if (storedRefreshToken.isNullOrBlank()) {
                Log.w(TAG, "No refresh token available in storage. Purging auth data.")
                tokenStorage.clearAuthData()
                sessionExpirationListener?.onSessionExpired()
                return null
            }

            // Execute synchronous refresh request against POST /v1/auth/refresh
            return try {
                val refreshResult = performTokenRefresh(storedRefreshToken)
                when (refreshResult) {
                    is RefreshResult.Success -> {
                        val session = refreshResult.session
                        tokenStorage.saveAuthSession(
                            accessToken = session.accessToken,
                            refreshToken = session.refreshToken,
                            userId = session.user.id,
                            userEmail = session.user.email,
                            userRole = session.user.userRole,
                            emailVerified = session.user.emailVerified,
                            displayName = session.user.displayName
                        )
                        Log.i(TAG, "Token refresh succeeded. Retrying original request.")
                        response.request.newBuilder()
                            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer ${session.accessToken}")
                            .build()
                    }
                    is RefreshResult.ExpiredOrInvalid -> {
                        Log.w(TAG, "Refresh token rejected by server (HTTP ${refreshResult.statusCode}). Clearing session.")
                        tokenStorage.clearAuthData()
                        sessionExpirationListener?.onSessionExpired()
                        null
                    }
                    is RefreshResult.NetworkError -> {
                        Log.e(TAG, "Network error during token refresh. Not clearing tokens.", refreshResult.exception)
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during token refresh.", e)
                null
            }
        }
    }

    private fun performTokenRefresh(refreshToken: String): RefreshResult {
        val refreshUrl = baseUrl.trimEnd('/') + "/v1/auth/refresh"
        val requestBodyJson = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val body = requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(refreshUrl)
            .post(body)
            .header(AuthInterceptor.HEADER_DEVICE_ID, tokenStorage.getDeviceId())
            .header(AuthInterceptor.HEADER_SYNC_PROTOCOL, AuthInterceptor.PROTOCOL_VERSION_2)
            .header(AuthInterceptor.HEADER_CONTENT_TYPE, AuthInterceptor.CONTENT_TYPE_JSON)
            .build()

        return try {
            val rawResponse = refreshClient.newCall(request).execute()
            rawResponse.use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val authSession = json.decodeFromString(AuthResponse.serializer(), responseBody)
                        RefreshResult.Success(authSession)
                    }
                    401, 403, 409 -> {
                        RefreshResult.ExpiredOrInvalid(resp.code, responseBody)
                    }
                    else -> {
                        RefreshResult.ExpiredOrInvalid(resp.code, responseBody)
                    }
                }
            }
        } catch (e: IOException) {
            RefreshResult.NetworkError(e)
        }
    }

    private fun isAuthEndpoint(path: String): Boolean {
        return path.endsWith("/v1/auth/refresh") ||
               path.endsWith("/v1/auth/login") ||
               path.endsWith("/v1/auth/register") ||
               path.endsWith("/v1/auth/logout") ||
               path.endsWith("/v1/auth/password/forgot") ||
               path.endsWith("/v1/auth/password/reset") ||
               path.endsWith("/v1/auth/email/verify") ||
               path.endsWith("/v1/auth/email/resend")
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRIES = 3
    }

    private sealed interface RefreshResult {
        data class Success(val session: AuthResponse) : RefreshResult
        data class ExpiredOrInvalid(val statusCode: Int, val body: String) : RefreshResult
        data class NetworkError(val exception: IOException) : RefreshResult
    }
}
