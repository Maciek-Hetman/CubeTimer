package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.RefreshRequest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one code path that exchanges the stored refresh token for a new session
 * (`POST /v1/auth/refresh`).
 *
 * The server rotates refresh tokens and treats a second use of an already-rotated token as theft
 * (`refresh_token_reused`), revoking the whole family and forcing a logout. Two callers that each
 * send the same token - the startup restore in `AuthManagerImpl` and the [TokenAuthenticator]
 * reacting to a 401 - would therefore log the user out. Every refresh goes through [refresh]
 * instead, which serializes callers on one lock and re-reads the storage inside it, so a caller
 * that lost the race finds the session already rotated and simply reuses it.
 *
 * Blocking by design (OkHttp's [Authenticator][okhttp3.Authenticator] is synchronous); coroutine
 * callers must run it off the main thread.
 */
class TokenRefresher(
    private val tokenStorage: TokenStorage,
    private val baseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
) {

    private val refreshLock = Any()

    // Isolated unauthenticated OkHttpClient: it must not run through AuthInterceptor/TokenAuthenticator,
    // otherwise a 401 on the refresh call would re-enter the refresh path.
    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Refreshes the session unless somebody else already did.
     *
     * @param staleAccessToken the access token the caller found unusable (the one a 401'd request
     * carried), or `null` if it had none (cold start: the access token is memory-only). Once the
     * lock is held, a stored access token that differs from it means another caller has rotated
     * the session in the meantime and no request is made.
     *
     * On [RefreshResult.Refreshed] the new tokens and user are already persisted; on
     * [RefreshResult.Rejected] and [RefreshResult.NoRefreshToken] the credentials have already
     * been cleared. Callers are left to react (retry the request, publish auth state, notify).
     */
    fun refresh(staleAccessToken: String?): RefreshResult =
        synchronized(refreshLock) { refreshLocked(staleAccessToken) }

    private fun refreshLocked(staleAccessToken: String?): RefreshResult {
        val currentAccessToken = tokenStorage.getAccessToken()
        if (!currentAccessToken.isNullOrBlank() && currentAccessToken != staleAccessToken) {
            return RefreshResult.AlreadyRefreshed(currentAccessToken)
        }

        // Read only now that the lock is held: an earlier holder may have rotated it.
        val storedRefreshToken = tokenStorage.getRefreshToken()
        if (storedRefreshToken.isNullOrBlank()) {
            tokenStorage.clearAuthData()
            return RefreshResult.NoRefreshToken
        }

        return try {
            when (val outcome = requestRefresh(storedRefreshToken)) {
                is HttpOutcome.Success -> {
                    val session = outcome.session
                    tokenStorage.saveAuthSession(
                        accessToken = session.accessToken,
                        refreshToken = session.refreshToken,
                        userId = session.user.id,
                        userEmail = session.user.email,
                        userRole = session.user.userRole,
                        emailVerified = session.user.emailVerified,
                        displayName = session.user.displayName
                    )
                    RefreshResult.Refreshed(session)
                }
                is HttpOutcome.Rejected -> {
                    tokenStorage.clearAuthData()
                    RefreshResult.Rejected(outcome.statusCode, outcome.body)
                }
                is HttpOutcome.Transient -> RefreshResult.Transient(outcome.statusCode, outcome.body)
            }
        } catch (e: IOException) {
            RefreshResult.NetworkError(e)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            RefreshResult.Failed(e)
        }
    }

    private fun requestRefresh(refreshToken: String): HttpOutcome {
        val refreshUrl = baseUrl.trimEnd('/') + "/v1/auth/refresh"
        val requestBodyJson = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val body = requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(refreshUrl)
            .post(body)
            .header(AuthInterceptor.HEADER_DEVICE_ID, tokenStorage.getDeviceId())
            .header(AuthInterceptor.HEADER_SYNC_PROTOCOL, AuthInterceptor.SYNC_PROTOCOL_VERSION)
            .header(AuthInterceptor.HEADER_CONTENT_TYPE, AuthInterceptor.CONTENT_TYPE_JSON)
            .build()

        return refreshClient.newCall(request).execute().use { resp ->
            val responseBody = resp.body.string()
            when (resp.code) {
                200 -> HttpOutcome.Success(json.decodeFromString(AuthResponse.serializer(), responseBody))
                // Definitive auth failures per the CubeSync refresh contract: the refresh token is
                // genuinely invalid, revoked, or reused. Only these clear stored credentials.
                400, 401, 403, 409 -> HttpOutcome.Rejected(resp.code, responseBody)
                // Any other status (5xx, 429, unexpected codes) is a transient server-side
                // condition, not proof the refresh token is invalid. Keep the session so the
                // next request can retry instead of forcing an unnecessary logout.
                else -> HttpOutcome.Transient(resp.code, responseBody)
            }
        }
    }

    private sealed interface HttpOutcome {
        data class Success(val session: AuthResponse) : HttpOutcome
        data class Rejected(val statusCode: Int, val body: String) : HttpOutcome
        data class Transient(val statusCode: Int, val body: String) : HttpOutcome
    }
}

/** Outcome of [TokenRefresher.refresh]. */
sealed interface RefreshResult {
    /** A refresh request succeeded; [session]'s tokens and user are persisted. */
    data class Refreshed(val session: AuthResponse) : RefreshResult

    /** Another caller had already rotated the session; no request was made. */
    data class AlreadyRefreshed(val accessToken: String) : RefreshResult

    /** There was nothing to refresh with; stored credentials were cleared. */
    data object NoRefreshToken : RefreshResult

    /** The server definitively rejected the refresh token (400/401/403/409); credentials were cleared. */
    data class Rejected(val statusCode: Int, val body: String) : RefreshResult

    /** Connectivity failure. The session is kept. */
    data class NetworkError(val exception: IOException) : RefreshResult

    /** Transient server-side failure (5xx, 429, unexpected status). The session is kept. */
    data class Transient(val statusCode: Int, val body: String) : RefreshResult

    /** Anything unexpected (e.g. an undecodable 200 body). The session is kept. */
    data class Failed(val cause: Exception) : RefreshResult
}
