package com.maciekhetman.cubetimer.data.remote

import android.util.Log
import com.maciekhetman.cubetimer.data.auth.SessionExpirationListener
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp Authenticator that transparently refreshes expired access tokens upon HTTP 401 Unauthorized.
 *
 * The refresh itself is delegated to a [TokenRefresher], which is shared with the startup session
 * restore so that only one caller at a time ever spends the refresh token; a 401 that loses that
 * race is retried with the token the winner obtained.
 */
class TokenAuthenticator(
    tokenStorage: TokenStorage,
    baseUrl: String,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true },
    var sessionExpirationListener: SessionExpirationListener? = null,
    private val tokenRefresher: TokenRefresher = TokenRefresher(tokenStorage, baseUrl, json)
) : Authenticator {

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

        // A 401 "invalid_credentials" on an authenticated call (change password) means the password
        // that was submitted is wrong, not that the access token expired: refreshing and resending
        // would only repeat the failed attempt.
        if (isWrongPassword(response)) {
            Log.d(TAG, "401 invalid_credentials on $path. Skipping refresh.")
            return null
        }

        val failedAuthorization = response.request.header(AuthInterceptor.HEADER_AUTHORIZATION)
        val failedToken = failedAuthorization?.removePrefix("Bearer ")?.trim()

        // 3. Refresh through the shared, serialized path (see TokenRefresher).
        return when (val result = tokenRefresher.refresh(staleAccessToken = failedToken)) {
            is RefreshResult.AlreadyRefreshed -> {
                Log.d(TAG, "Token was refreshed by a concurrent request. Retrying failed call with new token.")
                retryWith(response, result.accessToken)
            }
            is RefreshResult.Refreshed -> {
                Log.i(TAG, "Token refresh succeeded. Retrying original request.")
                retryWith(response, result.session.accessToken)
            }
            is RefreshResult.NoRefreshToken -> {
                Log.w(TAG, "No refresh token available in storage. Purged auth data.")
                sessionExpirationListener?.onSessionExpired()
                null
            }
            is RefreshResult.Rejected -> {
                Log.w(TAG, "Refresh token rejected by server (HTTP ${result.statusCode}). Cleared session.")
                sessionExpirationListener?.onSessionExpired()
                null
            }
            is RefreshResult.NetworkError -> {
                Log.e(TAG, "Network error during token refresh. Not clearing tokens.", result.exception)
                null
            }
            is RefreshResult.Transient -> {
                Log.w(
                    TAG,
                    "Transient server error (HTTP ${result.statusCode}) during token refresh. " +
                        "Preserving session for retry."
                )
                null
            }
            is RefreshResult.Failed -> {
                Log.e(TAG, "Unexpected error during token refresh.", result.cause)
                null
            }
        }
    }

    private fun retryWith(response: Response, accessToken: String): Request =
        response.request.newBuilder()
            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $accessToken")
            .build()

    private fun isAuthEndpoint(path: String): Boolean {
        return path.endsWith("/v1/auth/refresh") ||
               path.endsWith("/v1/auth/login") ||
               path.endsWith("/v1/auth/register") ||
               path.endsWith("/v1/auth/logout") ||
               path.endsWith("/v1/auth/password/forgot") ||
               path.endsWith("/v1/auth/password/reset") ||
               path.endsWith("/v1/auth/email/verify") ||
               path.endsWith("/v1/auth/email/resend") ||
               path.endsWith("/v1/auth/federated/google")
    }

    private fun isWrongPassword(response: Response): Boolean {
        // peekBody leaves the body intact for the caller, which still has to map the error.
        val body = try {
            response.peekBody(ERROR_BODY_PEEK_BYTES).string()
        } catch (_: Exception) {
            return false
        }
        return ErrorParser.parseApiError(body)?.error?.code?.lowercase()?.trim() == "invalid_credentials"
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
        private const val ERROR_BODY_PEEK_BYTES = 4096L
    }
}
