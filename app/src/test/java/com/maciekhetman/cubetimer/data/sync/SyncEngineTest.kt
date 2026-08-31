package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncMetadataDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.MutationOutcomeDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var syncMetadataDao: SyncMetadataDao
    private lateinit var conflictDao: ConflictDao
    private lateinit var fakeApiClient: FakeSyncApiClient
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var syncEngine: SyncEngineImpl
    private val json: Json = NetworkModule.json

    private val testUser = User(
        id = "user-1234",
        email = "speedcuber@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "Speed Cuber"
    )

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        syncOutboxDao = database.syncOutboxDao()
        syncMetadataDao = database.syncMetadataDao()
        conflictDao = database.conflictDao()

        fakeApiClient = FakeSyncApiClient()
        fakeTokenStorage = FakeTokenStorage()
        fakeAuthManager = FakeAuthManager(AuthState.Authenticated(testUser))
        conflictResolver = ConflictResolverImpl(database, conflictDao, solveDao, sessionDao, syncOutboxDao, json)

        syncEngine = SyncEngineImpl(
            apiClient = fakeApiClient,
            tokenStorage = fakeTokenStorage,
            database = database,
            authManager = fakeAuthManager,
            conflictResolver = conflictResolver,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            syncMetadataDao = syncMetadataDao,
            conflictDao = conflictDao,
            json = json
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sync_whenGuest_returnsNoOpWithoutMakingNetworkCalls() = runTest {
        fakeAuthManager.authStateFlow.value = AuthState.Guest

        val result = syncEngine.sync("guest")
        assertTrue(result is SyncResult.NoOp)
        assertEquals(0, fakeApiClient.syncCallCount)
        assertEquals(SyncStatus.UNAUTHENTICATED, syncEngine.syncStatus.value)
    }

    @Test
    fun sync_happyPath_appliesIncomingServerChangesAndAdvancesCursor() = runTest {
        val sessionChange = ChangeDto(
            cursor = 101L,
            entity = "session",
            entityId = "sess-1",
            operation = "upsert",
            version = 1L,
            data = json.encodeToJsonElement(
                SessionSnapshotDto(
                    id = "sess-1",
                    name = "Morning 3x3",
                    event = "3x3",
                    kind = "automatic",
                    startedAt = "2026-08-30T08:00:00Z",
                    version = 1L
                )
            ),
            changedAt = "2026-08-30T08:00:00Z"
        )

        val solveChange = ChangeDto(
            cursor = 102L,
            entity = "solve",
            entityId = "solve-1",
            operation = "upsert",
            version = 1L,
            data = json.encodeToJsonElement(
                SolveSnapshotDto(
                    id = "solve-1",
                    sessionId = "sess-1",
                    durationMs = 12500L,
                    penalty = "none",
                    solvedAt = "2026-08-30T08:15:00Z",
                    scramble = "R U R' U'",
                    event = "3x3",
                    version = 1L
                )
            ),
            changedAt = "2026-08-30T08:15:00Z"
        )

        fakeApiClient.syncResponse = SyncResponse(
            outcomes = emptyList(),
            changes = listOf(sessionChange, solveChange),
            nextCursor = 102L,
            hasMore = false
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(2, success.changesApplied)
        assertEquals(0, success.mutationsSynced)
        assertEquals(SyncStatus.SYNCED, syncEngine.syncStatus.value)

        // Verify session inserted in Room
        val savedSession = sessionDao.getSessionById("sess-1")
        assertNotNull(savedSession)
        assertEquals("Morning 3x3", savedSession?.name)
        assertEquals(1L, savedSession?.version)

        // Verify solve inserted in Room
        val savedSolve = solveDao.getSolveById("solve-1")
        assertNotNull(savedSolve)
        assertEquals(12500L, savedSolve?.durationMs)
        assertEquals("sess-1", savedSolve?.sessionId)

        // Verify watermark cursor updated
        val metadata = syncMetadataDao.getMetadata(testUser.id)
        assertEquals(102L, metadata?.cursor)
    }

    @Test
    fun sync_outboxMutationFlushed_acceptedOutcomeUpdatesVersionAndDeletesOutbox() = runTest {
        val session = SessionEntity(
            id = "sess-local",
            ownerId = testUser.id,
            name = "Local Session",
            event = "3x3",
            startedAt = "2026-08-30T09:00:00Z",
            version = 0L
        )
        sessionDao.insert(session)

        val mutation = SyncOutboxEntity(
            id = "mut-1",
            ownerId = testUser.id,
            entityType = "session",
            entityId = session.id,
            action = "upsert",
            baseVersion = 0L,
            payloadJson = json.encodeToString(SessionSyncPayload.serializer(), session.toSyncPayload()),
            clientTime = "2026-08-30T09:00:00Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(mutation)

        fakeApiClient.syncResponse = SyncResponse(
            outcomes = listOf(
                MutationOutcomeDto(
                    mutationId = "mut-1",
                    status = "accepted",
                    version = 1L
                )
            ),
            changes = emptyList(),
            nextCursor = 50L,
            hasMore = false
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.mutationsSynced)

        // Outbox row should be deleted
        val pending = syncOutboxDao.getPendingMutations(testUser.id)
        assertEquals(0, pending.size)

        // Local entity version should be updated to 1
        val updated = sessionDao.getSessionById("sess-local")
        assertEquals(1L, updated?.version)
    }

    @Test
    fun sync_inFlightRebase_rebasesPendingMutationWhenEntityEditedWhileInFlight() = runTest {
        val solveId = "solve-concurrent"
        val solve = SolveEntity(
            id = solveId,
            ownerId = testUser.id,
            durationMs = 15000L,
            solvedAt = "2026-08-30T09:00:00Z",
            version = 0L
        )
        solveDao.insert(solve)

        // First mutation in-flight
        val inFlightMutation = SyncOutboxEntity(
            id = "mut-first",
            ownerId = testUser.id,
            entityType = "solve",
            entityId = solveId,
            action = "upsert",
            baseVersion = 0L,
            payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
            clientTime = "2026-08-30T09:00:00Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(inFlightMutation)

        // Second mutation created concurrently (user changed penalty to plus_two)
        val updatedSolve = solve.copy(penalty = "plus_two", durationMs = 15000L)
        solveDao.update(updatedSolve)
        val secondMutation = SyncOutboxEntity(
            id = "mut-second",
            ownerId = testUser.id,
            entityType = "solve",
            entityId = solveId,
            action = "upsert",
            baseVersion = 0L, // Still baseVersion 0 initially
            payloadJson = json.encodeToString(SolveSyncPayload.serializer(), updatedSolve.toSyncPayload()),
            clientTime = "2026-08-30T09:01:00Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(secondMutation)

        // Server accepts first mutation and advances version to 1
        fakeApiClient.syncResponse = SyncResponse(
            outcomes = listOf(
                MutationOutcomeDto(
                    mutationId = "mut-first",
                    status = "accepted",
                    version = 1L
                )
            ),
            changes = emptyList(),
            nextCursor = 60L,
            hasMore = false
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)

        // First mutation is deleted, but second mutation should remain and be REBASED with baseVersion = 1L
        val remaining = syncOutboxDao.getPendingMutations(testUser.id)
        assertEquals(1, remaining.size)
        assertEquals("mut-second", remaining[0].id)
        assertEquals(1L, remaining[0].baseVersion)
    }

    @Test
    fun sync_remoteChangeSkippedIfPendingLocalEditExists() = runTest {
        val solveId = "solve-uncommitted"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUser.id,
            durationMs = 10000L,
            solvedAt = "2026-08-30T09:00:00Z",
            version = 1L
        )
        solveDao.insert(localSolve)

        // Local uncommitted mutation exists
        val localMutation = SyncOutboxEntity(
            id = "mut-local",
            ownerId = testUser.id,
            entityType = "solve",
            entityId = solveId,
            action = "upsert",
            baseVersion = 1L,
            payloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            clientTime = "2026-08-30T09:05:00Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(localMutation)

        // Remote sends conflicting/overwriting change for same solve
        val remoteChange = ChangeDto(
            cursor = 70L,
            entity = "solve",
            entityId = solveId,
            operation = "upsert",
            version = 2L,
            data = json.encodeToJsonElement(
                SolveSnapshotDto(
                    id = solveId,
                    durationMs = 99999L,
                    solvedAt = "2026-08-30T09:00:00Z",
                    version = 2L
                )
            )
        )

        // We simulate server change without answering outcome for this mutation
        fakeApiClient.syncResponse = SyncResponse(
            outcomes = emptyList(),
            changes = listOf(remoteChange),
            nextCursor = 70L,
            hasMore = false
        )

        syncEngine.sync(testUser.id)

        // Local solve duration must NOT be overwritten by 99999L
        val saved = solveDao.getSolveById(solveId)
        assertEquals(10000L, saved?.durationMs)
    }

    @Test
    fun sync_conflictOutcome_recordsInConflictDaoAndRemovesFromOutbox() = runTest {
        val solveId = "solve-conflict"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUser.id,
            durationMs = 14000L,
            solvedAt = "2026-08-30T09:00:00Z",
            version = 1L
        )
        solveDao.insert(localSolve)

        val mutation = SyncOutboxEntity(
            id = "mut-conflict-1",
            ownerId = testUser.id,
            entityType = "solve",
            entityId = solveId,
            action = "upsert",
            baseVersion = 1L,
            payloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            clientTime = "2026-08-30T09:00:00Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(mutation)

        fakeApiClient.syncResponse = SyncResponse(
            outcomes = listOf(
                MutationOutcomeDto(
                    mutationId = "mut-conflict-1",
                    status = "conflict",
                    version = 3L,
                    message = "Version mismatch"
                )
            ),
            changes = emptyList(),
            nextCursor = 80L,
            hasMore = false
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.conflictsRecorded)

        // Outbox mutation deleted
        val pending = syncOutboxDao.getPendingMutations(testUser.id)
        assertEquals(0, pending.size)

        // Conflict recorded in ConflictDao
        val conflicts = conflictDao.getAll(testUser.id)
        assertEquals(1, conflicts.size)
        assertEquals(solveId, conflicts[0].entityId)
        assertEquals(3L, conflicts[0].serverVersion)
    }

    @Test
    fun sync_on409CursorExpired_triggersSnapshotBootstrapAndResumesSync() = runTest {
        fakeApiClient.shouldThrowCursorExpiredOnFirstSync = true
        fakeApiClient.snapshotResponse = SnapshotResponse(
            sessions = listOf(
                SessionSnapshotDto(
                    id = "snapshot-sess",
                    name = "Snapshot Session",
                    event = "3x3",
                    startedAt = "2026-08-30T07:00:00Z",
                    version = 10L
                )
            ),
            solves = listOf(
                SolveSnapshotDto(
                    id = "snapshot-solve",
                    sessionId = "snapshot-sess",
                    durationMs = 11111L,
                    solvedAt = "2026-08-30T07:30:00Z",
                    event = "3x3",
                    version = 10L
                )
            ),
            cursor = 500L,
            hasMore = false
        )

        fakeApiClient.syncResponse = SyncResponse(
            outcomes = emptyList(),
            changes = emptyList(),
            nextCursor = 501L,
            hasMore = false
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)

        // Verify snapshot data applied
        val sess = sessionDao.getSessionById("snapshot-sess")
        assertNotNull(sess)
        assertEquals("Snapshot Session", sess?.name)

        val solve = solveDao.getSolveById("snapshot-solve")
        assertNotNull(solve)
        assertEquals(11111L, solve?.durationMs)

        // Verify snapshot watermark cursor
        val metadata = syncMetadataDao.getMetadata(testUser.id)
        assertEquals(501L, metadata?.cursor)
    }

    @Test
    fun sync_onNetworkError_returnsOfflineResultAndSetsStatus() = runTest {
        fakeApiClient.shouldThrowNetworkError = true

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Offline)
        assertEquals(SyncStatus.OFFLINE, syncEngine.syncStatus.value)
    }

    @Test
    fun sync_onUnauthorized_returnsAuthErrorAndSetsStatus() = runTest {
        fakeApiClient.shouldThrowUnauthorized = true

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.AuthError)
        assertEquals(SyncStatus.UNAUTHENTICATED, syncEngine.syncStatus.value)
    }

    // =========================================================================
    // TEST FAKES
    // =========================================================================

    private class FakeSyncApiClient : CubeSyncApiClient {
        var syncCallCount = 0
        var syncResponse: SyncResponse = SyncResponse()
        var snapshotResponse: SnapshotResponse = SnapshotResponse(cursor = 100L)
        var shouldThrowCursorExpiredOnFirstSync = false
        var shouldThrowNetworkError = false
        var shouldThrowUnauthorized = false

        override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse {
            syncCallCount++
            if (shouldThrowNetworkError) throw IOException("No network connection")
            if (shouldThrowUnauthorized) throw AuthException.Unauthorized("Token expired")
            if (shouldThrowCursorExpiredOnFirstSync && syncCallCount == 1) {
                throw AuthException.CursorExpired("Cursor has expired")
            }
            return syncResponse
        }

        override suspend fun snapshot(request: SnapshotRequest, authToken: String?): SnapshotResponse {
            return snapshotResponse
        }

        override suspend fun register(request: com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun login(request: com.maciekhetman.cubetimer.data.remote.dto.LoginRequest) = throw NotImplementedError()
        override suspend fun refreshToken(refreshToken: String) = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = Unit
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(request: com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest) = throw NotImplementedError()
        override suspend fun linkGoogle(idToken: String, authToken: String?) = Unit
        override suspend fun getCurrentUser(authToken: String?) = throw NotImplementedError()
        override suspend fun changePassword(request: com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest, authToken: String?) = Unit
        override suspend fun deleteAccount(authToken: String?) = Unit
    }

    private class FakeTokenStorage : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>("valid-token")
        override fun getAccessToken(): String? = "valid-token"
        override fun setAccessToken(token: String?) {}
        override fun getRefreshToken(): String? = "refresh-token"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = "user-1234"
        override fun getUserEmail(): String? = "user@test.com"
        override fun getUserRole(): String? = "user"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "Test User"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "device-test-uuid"
    }

    private class FakeAuthManager(initialState: AuthState) : AuthManager {
        val authStateFlow = MutableStateFlow(initialState)
        override val authState: StateFlow<AuthState> = authStateFlow
        override val currentUser: User? get() = (authState.value as? AuthState.Authenticated)?.user

        override suspend fun initialize() {}
        override suspend fun register(email: String, password: String) = throw NotImplementedError()
        override suspend fun login(email: String, password: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(idToken: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun refreshSession() = throw NotImplementedError()
        override suspend fun logout() = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) {}
    }
}
