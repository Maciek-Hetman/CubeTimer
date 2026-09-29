package com.maciekhetman.cubetimer.data.sync

import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.MutationOutcomeDto
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Shared fixtures for the sync-engine robustness suites ([SyncEngineSnapshotBootstrapTest],
 * [SyncEngineRejectedRequestTest], [SyncEngineCursorExpiredRuleTest]).
 */
internal const val ROBUSTNESS_OWNER_ID = "robust-user-1"

internal val robustnessUser = User(
    id = ROBUSTNESS_OWNER_ID,
    email = "robust@example.com",
    userRole = UserRole.USER,
    emailVerified = true,
    displayName = "Robust User"
)

/** A scripted `CubeSyncApiClient`: records every request and answers through swappable handlers. */
internal class ScriptedSyncApiClient : CubeSyncApiClient {
    val syncRequests = mutableListOf<SyncRequest>()
    val snapshotRequests = mutableListOf<SnapshotRequest>()

    var syncHandler: (SyncRequest) -> SyncResponse = ::acceptAll
    var snapshotHandler: (SnapshotRequest) -> SnapshotResponse = { SnapshotResponse(cursor = it.cursor) }

    /** Ids of every mutation sent, in request order. */
    fun sentMutationIds(): List<String> = syncRequests.flatMap { r -> r.mutations.map { it.id } }

    override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse {
        syncRequests += request
        return syncHandler(request)
    }

    override suspend fun snapshot(request: SnapshotRequest, authToken: String?): SnapshotResponse {
        snapshotRequests += request
        return snapshotHandler(request)
    }

    override suspend fun register(request: RegisterRequest): StatusResponse = throw NotImplementedError()
    override suspend fun resendVerificationEmail(email: String): StatusResponse = throw NotImplementedError()
    override suspend fun verifyEmail(token: String): AuthResponse = throw NotImplementedError()
    override suspend fun login(request: LoginRequest): AuthResponse = throw NotImplementedError()
    override suspend fun refreshToken(refreshToken: String): AuthResponse = throw NotImplementedError()
    override suspend fun logout(refreshToken: String) = Unit
    override suspend fun requestPasswordReset(email: String): StatusResponse = throw NotImplementedError()
    override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse = throw NotImplementedError()
    override suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse = throw NotImplementedError()
    override suspend fun linkGoogle(request: GoogleAuthRequest, authToken: String?) = Unit
    override suspend fun getCurrentUser(authToken: String?): UserDto = throw NotImplementedError()
    override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?) = Unit
    override suspend fun deleteAccount(authToken: String?) = Unit
}

/** What a healthy server answers: every mutation accepted at version 1, cursor advanced. */
internal fun acceptAll(request: SyncRequest): SyncResponse = SyncResponse(
    outcomes = request.mutations.map { MutationOutcomeDto(mutationId = it.id, status = "accepted", version = 1L) },
    nextCursor = request.cursor + 1,
    hasMore = false
)

internal class StubTokenStorage(private val deviceId: String = "robust-device") : TokenStorage {
    override val accessTokenFlow = MutableStateFlow<String?>("robust-access-token")
    override fun getAccessToken(): String? = "robust-access-token"
    override fun setAccessToken(token: String?) {}
    override fun getRefreshToken(): String? = "robust-refresh-token"
    override fun setRefreshToken(token: String?) {}
    override fun getUserId(): String? = ROBUSTNESS_OWNER_ID
    override fun getUserEmail(): String? = "robust@example.com"
    override fun getUserRole(): String? = "user"
    override fun isUserEmailVerified(): Boolean = true
    override fun getDisplayName(): String? = "Robust User"
    override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
    override fun saveUser(user: User) {}
    override fun clearAuthData() {}
    override fun clearAll() {}
    override fun getCachedUser(): User? = null
    override fun getDeviceId(): String = deviceId
}

internal class StubAuthManager(initialState: AuthState = AuthState.Authenticated(robustnessUser)) : AuthManager {
    private val authStateFlow = MutableStateFlow(initialState)
    override val authState: StateFlow<AuthState> = authStateFlow
    override val currentUser: User? get() = (authState.value as? AuthState.Authenticated)?.user

    override suspend fun initialize() {}
    override suspend fun register(email: String, password: String) = throw NotImplementedError()
    override suspend fun login(email: String, password: String) = throw NotImplementedError()
    override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String) = throw NotImplementedError()
    override suspend fun verifyEmail(token: String) = throw NotImplementedError()
    override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
    override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
    override suspend fun resetPassword(token: String, newPassword: String) = throw NotImplementedError()
    override suspend fun refreshSession() = throw NotImplementedError()
    override suspend fun logout() = throw NotImplementedError()
    override suspend fun adoptGuestData(userId: String) {}
}

/**
 * Inserts a local solve and queues its upsert mutation (id [mutationId], defaulting to
 * `mut-<solveId>`), the way the repositories do. [order] fixes the queue position: outbox rows
 * are sent oldest `client_time` first.
 */
internal suspend fun CubeDatabase.enqueueSolve(
    solveId: String,
    order: Int,
    mutationId: String = "mut-$solveId",
    durationMs: Long = 10_000L + order,
    ownerId: String = ROBUSTNESS_OWNER_ID,
    json: Json = NetworkModule.json,
    insertSolve: Boolean = true
) {
    val solve = SolveEntity(
        id = solveId,
        ownerId = ownerId,
        durationMs = durationMs,
        solvedAt = "2026-08-30T09:00:00.000Z",
        version = 0L
    )
    if (insertSolve) solveDao().insert(solve)
    syncOutboxDao().enqueue(
        SyncOutboxEntity(
            id = mutationId,
            ownerId = ownerId,
            entityType = "solve",
            entityId = solveId,
            action = "upsert",
            baseVersion = 0L,
            payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
            clientTime = Instant.parse("2026-08-30T09:00:00Z").plusSeconds(order.toLong()).toString(),
            status = "pending"
        )
    )
}
