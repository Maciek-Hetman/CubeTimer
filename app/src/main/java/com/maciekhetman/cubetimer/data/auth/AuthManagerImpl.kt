package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.ErrorParser
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.RefreshResult
import com.maciekhetman.cubetimer.data.remote.TokenRefresher
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.LogoutRequest
import com.maciekhetman.cubetimer.data.remote.dto.PasswordResetConfirmRequest
import com.maciekhetman.cubetimer.data.remote.dto.PasswordResetRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.VerifyEmailRequest
import com.maciekhetman.cubetimer.data.remote.mapper.toDomain
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class AuthManagerImpl(
    private val apiClient: CubeSyncApiClient,
    private val tokenStorage: TokenStorage,
    private val database: CubeDatabase,
    private val syncTrigger: (suspend () -> Unit)? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    autoInitialize: Boolean = true,
    /**
     * The refresh path shared with the OkHttp `TokenAuthenticator`. The startup restore and
     * [refreshSession] refresh through it so they can never spend the same refresh token as a
     * concurrent 401-triggered refresh. Without one they fall back to [apiClient].
     */
    private val tokenRefresher: TokenRefresher? = null
) : AuthManager, SessionExpirationListener {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUser: User?
        get() = _authState.value.currentUser

    /**
     * Session-restore runs that were requested but haven't finished yet (see [awaitInitialized]).
     * The automatic run is counted before it is launched, so there is no window in which a waiter
     * could see zero while that run has yet to start.
     */
    private val pendingInitializations = MutableStateFlow(0)

    /** Serializes session restores: two of them must never refresh side by side. */
    private val restoreMutex = Mutex()

    /** Number of restores that have run to completion; lets a queued request notice it is moot. */
    private val completedRestores = AtomicLong(0)

    init {
        if (autoInitialize) {
            pendingInitializations.update { it + 1 }
            val requestedAt = completedRestores.get()
            authScope.launch {
                restoreSessionOnce(requestedAt)
            }.invokeOnCompletion {
                // Also runs if the launch is cancelled before it starts, so the count can't leak.
                pendingInitializations.update { it - 1 }
            }
        }
    }

    override suspend fun initialize() {
        pendingInitializations.update { it + 1 }
        try {
            restoreSessionOnce(requestedAt = completedRestores.get())
        } finally {
            pendingInitializations.update { it - 1 }
        }
    }

    /**
     * Returns once every requested [initialize] run - including the automatic one - has finished,
     * i.e. after the startup refresh has succeeded, been rejected (Guest) or failed on the network
     * (cached identity kept). With `autoInitialize = false` and no [initialize] call in flight it
     * returns immediately, even while still [AuthState.Loading]: nothing is restoring a session,
     * so there is nothing to wait for (and nothing that would ever end the wait).
     */
    override suspend fun awaitInitialized() {
        pendingInitializations.first { it == 0 }
    }

    /**
     * Restores the session, unless a restore that was running (or queued ahead of this one) when
     * this one was [requestedAt] has completed since: concurrent initializations coalesce into a
     * single refresh instead of each spending the refresh token. A restore requested after the
     * previous one finished is a new request and refreshes again.
     */
    private suspend fun restoreSessionOnce(requestedAt: Long) {
        restoreMutex.withLock {
            if (completedRestores.get() > requestedAt) return
            restoreSession()
            completedRestores.incrementAndGet()
        }
    }

    private suspend fun restoreSession() = withContext(ioDispatcher) {
        val refreshToken = tokenStorage.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            _authState.value = AuthState.Guest
            return@withContext
        }

        // Offline-first: if we already have a cached identity for this refresh token, surface it
        // immediately so solves/sessions saved while the refresh call is in flight (network calls
        // can take up to the OkHttp timeout) are attributed to the right owner instead of "guest".
        // The refresh below still runs to validate/rotate the token; on definitive rejection we
        // fall back to Guest, and on a network error we simply keep the cached identity.
        val cachedUser = tokenStorage.getCachedUser()
        if (cachedUser != null) {
            publishUser(cachedUser)
        }

        when (attemptRefresh(refreshToken)) {
            is RefreshAttempt.Refreshed -> Unit
            is RefreshAttempt.Rejected -> {
                tokenStorage.clearAuthData()
                _authState.value = AuthState.Guest
            }
            // Network error or transient server failure: keep the cached identity we already
            // surfaced above, if any.
            is RefreshAttempt.Failed -> if (cachedUser == null) {
                _authState.value = AuthState.Guest
            }
        }
    }

    /** How a refresh attempt ended, from the point of view of the session. */
    private sealed interface RefreshAttempt {
        /** The session is valid; [user] has been published as the auth state. */
        data class Refreshed(val user: User) : RefreshAttempt

        /** The server definitively refused the refresh token: the session is over. */
        data class Rejected(val error: AuthException) : RefreshAttempt

        /** Connectivity or a transient server problem: nothing is known to be wrong with the session. */
        data class Failed(val error: AuthException) : RefreshAttempt
    }

    private suspend fun attemptRefresh(refreshToken: String): RefreshAttempt {
        val refresher = tokenRefresher
        return if (refresher != null) {
            refreshThrough(refresher)
        } else {
            refreshThroughApiClient(refreshToken)
        }
    }

    private suspend fun refreshThrough(refresher: TokenRefresher): RefreshAttempt {
        // Captured before waiting for the refresher's lock: if a 401-triggered refresh (or a login)
        // installs an access token in the meantime, the refresher notices and doesn't refresh again.
        val staleAccessToken = tokenStorage.getAccessToken()

        // A plain blocking call on purpose (no runInterruptible): once the request is on the wire the
        // server may already have rotated the refresh token, so it must run through to storing the
        // new one even if this coroutine is cancelled meanwhile - abandoning it would lose the session.
        val result = refresher.refresh(staleAccessToken)

        return when (result) {
            is RefreshResult.Refreshed -> {
                // The refresher already persisted the tokens; saving them again here could
                // overwrite a newer rotation.
                val user = result.session.user.toDomain()
                publishUser(user)
                RefreshAttempt.Refreshed(user)
            }
            is RefreshResult.AlreadyRefreshed -> {
                val user = tokenStorage.getCachedUser()
                if (user != null) {
                    publishUser(user)
                    RefreshAttempt.Refreshed(user)
                } else {
                    RefreshAttempt.Failed(AuthException.Unknown("Session was refreshed but no user is stored"))
                }
            }
            is RefreshResult.NoRefreshToken ->
                RefreshAttempt.Rejected(AuthException.InvalidRefreshToken("No refresh token available"))
            is RefreshResult.Rejected -> RefreshAttempt.Rejected(refreshError(result.statusCode, result.body))
            is RefreshResult.NetworkError -> RefreshAttempt.Failed(
                AuthException.NetworkError("Session refresh failed: ${result.exception.localizedMessage}", result.exception)
            )
            is RefreshResult.Transient -> RefreshAttempt.Failed(refreshError(result.statusCode, result.body))
            is RefreshResult.Failed -> RefreshAttempt.Failed(
                AuthException.Unknown("Session refresh failed: ${result.cause.localizedMessage}", result.cause)
            )
        }
    }

    private suspend fun refreshThroughApiClient(refreshToken: String): RefreshAttempt = try {
        val response = apiClient.refreshToken(refreshToken)
        RefreshAttempt.Refreshed(handleAuthSuccess(response, isNewLogin = false))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthException) {
        if (e.isDefinitiveRefreshRejection()) RefreshAttempt.Rejected(e) else RefreshAttempt.Failed(e)
    } catch (e: Exception) {
        RefreshAttempt.Failed(AuthException.NetworkError("Session refresh failed: ${e.localizedMessage}", e))
    }

    private fun refreshError(statusCode: Int, body: String): AuthException {
        val apiError = ErrorParser.parseApiError(body)?.error
        return ErrorParser.toAuthException(
            errorCode = apiError?.code,
            message = apiError?.message ?: "HTTP Error $statusCode",
            httpStatusCode = statusCode
        )
    }

    /**
     * Whether a failed refresh proves the refresh token itself is dead (revoked, reused, invalid),
     * as opposed to a network or server hiccup. Mirrors the statuses [TokenRefresher] treats as
     * definitive (400/401/403/409) for refreshes that go through [CubeSyncApiClient] instead.
     */
    private fun AuthException.isDefinitiveRefreshRejection(): Boolean = when (this) {
        is AuthException.RefreshTokenReused,
        is AuthException.InvalidRefreshToken,
        is AuthException.InvalidCredentials,
        is AuthException.InvalidToken,
        is AuthException.Unauthorized,
        is AuthException.Forbidden -> true
        is AuthException.ApiError -> httpStatusCode in DEFINITIVE_REFRESH_STATUS_CODES
        else -> false
    }

    override suspend fun register(email: String, password: String): AuthResult<Unit> = withContext(ioDispatcher) {
        try {
            apiClient.register(RegisterRequest(email = email.trim(), password = password))
            AuthResult.Success(Unit)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Registration failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun login(email: String, password: String): AuthResult<User> = withContext(ioDispatcher) {
        try {
            val response = apiClient.login(LoginRequest(email = email.trim(), password = password))
            val user = handleAuthSuccess(response, isNewLogin = true)
            AuthResult.Success(user)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Login failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun loginWithGoogle(
        idToken: String,
        clientId: String,
        nonce: String
    ): AuthResult<User> = withContext(ioDispatcher) {
        try {
            val response = apiClient.loginWithGoogle(
                GoogleAuthRequest(idToken = idToken, clientId = clientId, nonce = nonce)
            )
            val user = handleAuthSuccess(response, isNewLogin = true)
            AuthResult.Success(user)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Google sign-in failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun verifyEmail(token: String): AuthResult<User> = withContext(ioDispatcher) {
        try {
            val response = apiClient.verifyEmail(token.trim())
            val user = handleAuthSuccess(response, isNewLogin = true)
            AuthResult.Success(user)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Email verification failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = withContext(ioDispatcher) {
        try {
            apiClient.resendVerificationEmail(email.trim())
            AuthResult.Success(Unit)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Failed to resend verification email: ${e.localizedMessage}", e))
        }
    }

    override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = withContext(ioDispatcher) {
        try {
            apiClient.requestPasswordReset(email.trim())
            AuthResult.Success(Unit)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Password reset request failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = withContext(ioDispatcher) {
        try {
            val response = apiClient.confirmPasswordReset(token = token.trim(), newPassword = newPassword)
            val user = handleAuthSuccess(response, isNewLogin = true)
            AuthResult.Success(user)
        } catch (e: AuthException) {
            AuthResult.Error(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(AuthException.NetworkError("Password reset confirmation failed: ${e.localizedMessage}", e))
        }
    }

    override suspend fun refreshSession(): AuthResult<User> = withContext(ioDispatcher) {
        val refreshToken = tokenStorage.getRefreshToken()
            ?: return@withContext AuthResult.Error(AuthException.InvalidCredentials("No refresh token available"))

        when (val attempt = attemptRefresh(refreshToken)) {
            is RefreshAttempt.Refreshed -> AuthResult.Success(attempt.user)
            is RefreshAttempt.Rejected -> {
                tokenStorage.clearAuthData()
                _authState.value = AuthState.Guest
                AuthResult.Error(attempt.error)
            }
            // Offline or a server hiccup says nothing about the session: report it, keep the login.
            is RefreshAttempt.Failed -> AuthResult.Error(attempt.error)
        }
    }

    override suspend fun logout(): AuthResult<Unit> = withContext(ioDispatcher) {
        val user = currentUser
        val refreshToken = tokenStorage.getRefreshToken()

        // 1. Close active automatic sessions for the outgoing user. Each close also gets an outbox
        // mutation (owned by that user, so it goes out on their next sync); a bare DAO update would
        // leave the server believing the session is still open.
        if (user != null) {
            val nowIso = CubeTypeConverters.nowIso()
            database.withTransaction {
                val sessionDao = database.sessionDao()
                val openAutoSessions = sessionDao.getAllActiveSessionsForOwner(user.id)
                    .filter { it.kind == "automatic" && it.endedAt == null }
                for (session in openAutoSessions) {
                    val closed = session.copy(endedAt = nowIso, updatedAt = nowIso)
                    sessionDao.update(closed)
                    database.syncOutboxDao().enqueue(closed.toUpsertMutation(clientTime = nowIso, json = json))
                }
            }
        }

        // 2. Best-effort server token revocation
        if (!refreshToken.isNullOrBlank()) {
            try {
                apiClient.logout(refreshToken)
            } catch (_: Exception) {
                // Ignore network errors on logout to allow local logout to complete. This also
                // swallows cancellation on purpose: a cancelled caller must not be left half
                // logged out, so the local steps below always run.
            }
        }

        // 3. Clear token storage
        tokenStorage.clearAuthData()

        // 4. Revert to Guest
        _authState.value = AuthState.Guest

        AuthResult.Success(Unit)
    }

    override fun onSessionExpired() {
        tokenStorage.clearAuthData()
        _authState.value = AuthState.Guest
    }

    private suspend fun handleAuthSuccess(response: AuthResponse, isNewLogin: Boolean): User {
        val user = response.user.toDomain()

        tokenStorage.saveAuthSession(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            userId = user.id,
            userEmail = user.email,
            userRole = response.user.userRole,
            emailVerified = user.emailVerified,
            displayName = user.displayName
        )

        if (isNewLogin) {
            adoptGuestData(user.id)
            syncTrigger?.invoke()
        }

        publishUser(user)

        return user
    }

    private fun publishUser(user: User) {
        _authState.value = if (user.userRole == UserRole.ADMIN) {
            AuthState.Admin(user)
        } else {
            AuthState.Authenticated(user)
        }
    }

    override suspend fun adoptGuestData(userId: String) = withContext(ioDispatcher) {
        database.withTransaction {
            val solveDao = database.solveDao()
            val sessionDao = database.sessionDao()
            val outboxDao = database.syncOutboxDao()

            val guestSolves = solveDao.getAllActiveSolvesForOwner("guest")
            val guestSessions = sessionDao.getAllActiveSessionsForOwner("guest")

            if (guestSolves.isEmpty() && guestSessions.isEmpty()) {
                return@withTransaction
            }

            val nowIso = CubeTypeConverters.nowIso()

            // 1. Reassign ownership in Room
            solveDao.adoptGuestSolves(guestOwnerId = "guest", targetOwnerId = userId, updatedAt = nowIso)
            sessionDao.adoptGuestSessions(guestOwnerId = "guest", targetOwnerId = userId, updatedAt = nowIso)

            // Mutations are built through the shared outbox mappers from each row as it is *after*
            // step 1 (owner = userId, version reset to 0, updated_at = now), so the payloads carry
            // exactly the DTO fields — notably the solve's real timing_device, which a hand-built
            // SolveSyncPayload silently defaulted to "keyboard".
            val outboxMutations = mutableListOf<SyncOutboxEntity>()

            // 2. Enqueue session mutations first (satisfying FK constraints)
            for (session in guestSessions) {
                outboxMutations += session.copy(ownerId = userId, version = 0L, updatedAt = nowIso)
                    .toUpsertMutation(clientTime = nowIso, json = json)
            }

            // 3. Enqueue solve mutations
            for (solve in guestSolves) {
                outboxMutations += solve.copy(ownerId = userId, version = 0L, updatedAt = nowIso)
                    .toUpsertMutation(clientTime = nowIso, json = json)
            }

            // 4. Batch enqueue into outbox
            if (outboxMutations.isNotEmpty()) {
                outboxDao.enqueueAll(outboxMutations)
            }
        }
    }

    companion object {
        private val DEFINITIVE_REFRESH_STATUS_CODES = setOf(400, 401, 403, 409)

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: android.content.Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManagerImpl(
                    apiClient = NetworkModule.provideCubeSyncApiClient(
                        NetworkModule.provideAuthApiService(
                            baseUrl = com.maciekhetman.cubetimer.CubeTimerApplication.BASE_URL,
                            okHttpClient = NetworkModule.provideOkHttpClient()
                        )
                    ),
                    tokenStorage = EncryptedTokenStorage(context.applicationContext),
                    database = CubeDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
}
