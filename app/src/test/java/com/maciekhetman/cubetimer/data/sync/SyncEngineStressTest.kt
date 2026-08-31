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
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.MutationOutcomeDto
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncEngineStressTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var syncMetadataDao: SyncMetadataDao
    private lateinit var conflictDao: ConflictDao
    private lateinit var fakeApiClient: DynamicFakeSyncApiClient
    private lateinit var fakeTokenStorage: DynamicFakeTokenStorage
    private lateinit var fakeAuthManager: DynamicFakeAuthManager
    private lateinit var conflictResolver: ConflictResolver
    private lateinit var syncEngine: SyncEngineImpl
    private val json: Json = NetworkModule.json

    private val testUserId = "stress-user-999"
    private val testUser = User(
        id = testUserId,
        email = "stress@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "Stress Tester"
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

        fakeApiClient = DynamicFakeSyncApiClient()
        fakeTokenStorage = DynamicFakeTokenStorage(userId = testUserId)
        fakeAuthManager = DynamicFakeAuthManager(AuthState.Authenticated(testUser))
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

    // =========================================================================
    // 1. PAGINATION LOOP STRESS (Multi-Page Batches & 1000+ Items)
    // =========================================================================

    @Test
    fun paginationStress_remoteChanges_1500ItemsAcrossMultiplePages() = runTest {
        // Page 1: 500 sessions, cursor 500, hasMore = true
        val page1Changes = (1..500).map { i ->
            ChangeDto(
                cursor = i.toLong(),
                entity = "session",
                entityId = "stress-sess-$i",
                operation = "upsert",
                version = 1L,
                data = json.encodeToJsonElement(
                    SessionSnapshotDto(
                        id = "stress-sess-$i",
                        name = "Stress Session $i",
                        event = "3x3",
                        kind = "automatic",
                        startedAt = "2026-08-30T08:00:00Z",
                        version = 1L
                    )
                ),
                changedAt = "2026-08-30T08:00:00Z"
            )
        }

        // Page 2: 500 solves, cursor 1000, hasMore = true
        val page2Changes = (1..500).map { i ->
            val solveNum = i
            ChangeDto(
                cursor = (500 + i).toLong(),
                entity = "solve",
                entityId = "stress-solve-$solveNum",
                operation = "upsert",
                version = 1L,
                data = json.encodeToJsonElement(
                    SolveSnapshotDto(
                        id = "stress-solve-$solveNum",
                        sessionId = "stress-sess-$i",
                        durationMs = 12000L + i,
                        penalty = "none",
                        solvedAt = "2026-08-30T08:10:00Z",
                        scramble = "R U R' U'",
                        event = "3x3",
                        version = 1L
                    )
                ),
                changedAt = "2026-08-30T08:10:00Z"
            )
        }

        // Page 3: 500 solves, cursor 1500, hasMore = false
        val page3Changes = (501..1000).map { i ->
            val solveNum = i
            ChangeDto(
                cursor = (500 + i).toLong(),
                entity = "solve",
                entityId = "stress-solve-$solveNum",
                operation = "upsert",
                version = 1L,
                data = json.encodeToJsonElement(
                    SolveSnapshotDto(
                        id = "stress-solve-$solveNum",
                        sessionId = "stress-sess-${i - 500}",
                        durationMs = 15000L + i,
                        penalty = "none",
                        solvedAt = "2026-08-30T08:20:00Z",
                        scramble = "R U R' U'",
                        event = "3x3",
                        version = 1L
                    )
                ),
                changedAt = "2026-08-30T08:20:00Z"
            )
        }

        fakeApiClient.syncResponsesQueue.add(
            SyncResponse(
                outcomes = emptyList(),
                changes = page1Changes,
                nextCursor = 500L,
                hasMore = true
            )
        )
        fakeApiClient.syncResponsesQueue.add(
            SyncResponse(
                outcomes = emptyList(),
                changes = page2Changes,
                nextCursor = 1000L,
                hasMore = true
            )
        )
        fakeApiClient.syncResponsesQueue.add(
            SyncResponse(
                outcomes = emptyList(),
                changes = page3Changes,
                nextCursor = 1500L,
                hasMore = false
            )
        )

        val result = syncEngine.sync(testUserId)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1500, success.changesApplied)
        assertEquals(0, success.mutationsSynced)
        assertEquals(3, fakeApiClient.syncRequestsReceived.size)

        // Verify all 500 sessions exist in Room
        val allSessions = sessionDao.getAllActiveSessionsForOwner(testUserId)
        assertEquals(500, allSessions.size)

        // Verify all 1000 solves exist in Room
        val allSolves = solveDao.getSolvesByEvent(testUserId, "3x3")
        assertEquals(1000, allSolves.size)

        // Verify watermark cursor reached 1500
        val metadata = syncMetadataDao.getMetadata(testUserId)
        assertEquals(1500L, metadata?.cursor)
        assertEquals(SyncStatus.SYNCED, syncEngine.syncStatus.value)
    }

    @Test
    fun paginationStress_localOutbox_1200MutationsAcrossMultipleBatches() = runTest {
        // Enqueue 200 sessions and 1000 solves locally
        val sessionEntities = (1..200).map { i ->
            val entity = SessionEntity(
                id = "local-sess-$i",
                ownerId = testUserId,
                name = "Local Session $i",
                event = "3x3",
                startedAt = "2026-08-30T09:00:00Z",
                version = 0L
            )
            sessionDao.insert(entity)
            SyncOutboxEntity(
                id = "mut-sess-$i",
                ownerId = testUserId,
                entityType = "session",
                entityId = entity.id,
                action = "upsert",
                baseVersion = 0L,
                payloadJson = json.encodeToString(SessionSyncPayload.serializer(), entity.toSyncPayload()),
                clientTime = "2026-08-30T09:00:00Z",
                status = "pending"
            )
        }

        val solveEntities = (1..1000).map { i ->
            val entity = SolveEntity(
                id = "local-solve-$i",
                ownerId = testUserId,
                sessionId = "local-sess-${(i % 200) + 1}",
                durationMs = 10000L + i,
                solvedAt = "2026-08-30T09:05:00Z",
                event = "3x3",
                version = 0L
            )
            solveDao.insert(entity)
            SyncOutboxEntity(
                id = "mut-solve-$i",
                ownerId = testUserId,
                entityType = "solve",
                entityId = entity.id,
                action = "upsert",
                baseVersion = 0L,
                payloadJson = json.encodeToString(SolveSyncPayload.serializer(), entity.toSyncPayload()),
                clientTime = "2026-08-30T09:05:00Z",
                status = "pending"
            )
        }

        val allMutations = sessionEntities + solveEntities
        for (m in allMutations) {
            syncOutboxDao.enqueue(m)
        }

        assertEquals(1200, syncOutboxDao.countPending(testUserId))

        // Dynamic responder: whenever sync request comes with mutations, return accepted outcomes for all received mutations
        fakeApiClient.dynamicSyncHandler = { request ->
            val outcomes = request.mutations.map { m ->
                MutationOutcomeDto(
                    mutationId = m.id,
                    status = "accepted",
                    version = 1L
                )
            }
            SyncResponse(
                outcomes = outcomes,
                changes = emptyList(),
                nextCursor = request.cursor + outcomes.size,
                hasMore = false
            )
        }

        val result = syncEngine.sync(testUserId)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1200, success.mutationsSynced)

        // 1200 items in 500-size batches should take exactly 3 request loops: 500, 500, 200
        assertEquals(3, fakeApiClient.syncRequestsReceived.size)
        assertEquals(500, fakeApiClient.syncRequestsReceived[0].mutations.size)
        assertEquals(500, fakeApiClient.syncRequestsReceived[1].mutations.size)
        assertEquals(200, fakeApiClient.syncRequestsReceived[2].mutations.size)

        // Outbox must be completely drained
        val remainingOutbox = syncOutboxDao.countPending(testUserId)
        assertEquals(0, remainingOutbox)

        // Check that Room entities have their versions bumped to 1
        val sampleSess = sessionDao.getSessionById("local-sess-1")
        assertEquals(1L, sampleSess?.version)

        val sampleSolve = solveDao.getSolveById("local-solve-1000")
        assertEquals(1L, sampleSolve?.version)
    }

    // =========================================================================
    // 2. SNAPSHOT RECOVERY STRESS (HTTP 409 cursor_expired)
    // =========================================================================

    @Test
    fun snapshotRecoveryStress_multiPageSnapshotBootstrapOn409() = runTest {
        var syncCallIndex = 0

        // Page 1 snapshot: 500 sessions, hasMore = true
        val snapSessions = (1..500).map { i ->
            SessionSnapshotDto(
                id = "snap-sess-$i",
                name = "Snap Session $i",
                event = "3x3",
                kind = "automatic",
                startedAt = "2026-08-30T07:00:00Z",
                version = 10L
            )
        }

        // Page 2 snapshot: 750 solves, hasMore = false
        val snapSolves = (1..750).map { i ->
            SolveSnapshotDto(
                id = "snap-solve-$i",
                sessionId = "snap-sess-${(i % 500) + 1}",
                durationMs = 9000L + i,
                penalty = "none",
                solvedAt = "2026-08-30T07:30:00Z",
                event = "3x3",
                version = 10L
            )
        }

        fakeApiClient.dynamicSnapshotHandler = { request ->
            if (request.entity == "session") {
                SnapshotResponse(
                    sessions = snapSessions,
                    solves = emptyList(),
                    cursor = 5000L,
                    hasMore = true,
                    nextEntity = "solve",
                    nextAfterId = "snap-sess-500"
                )
            } else {
                SnapshotResponse(
                    sessions = emptyList(),
                    solves = snapSolves,
                    cursor = 5000L,
                    hasMore = false
                )
            }
        }

        fakeApiClient.dynamicSyncHandler = { request ->
            syncCallIndex++
            if (syncCallIndex == 1) {
                // First sync fails with 409 cursor_expired
                throw AuthException.CursorExpired("Cursor 100 expired on server")
            } else {
                // Resumed sync succeeds with cursor 5000
                SyncResponse(
                    outcomes = emptyList(),
                    changes = emptyList(),
                    nextCursor = request.cursor,
                    hasMore = false
                )
            }
        }

        // Set initial stale cursor
        syncMetadataDao.upsertMetadata(
            com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity(
                ownerId = testUserId,
                cursor = 100L,
                deviceId = "test-device",
                isSyncing = false
            )
        )

        val result = syncEngine.sync(testUserId)
        assertTrue(result is SyncResult.Success)

        // Verify snapshot bootstrap loaded all 500 sessions and 750 solves
        val allSessions = sessionDao.getAllActiveSessionsForOwner(testUserId)
        assertEquals(500, allSessions.size)

        val allSolves = solveDao.getSolvesByEvent(testUserId, "3x3")
        assertEquals(750, allSolves.size)

        // Verify cursor reached 5000
        val metadata = syncMetadataDao.getMetadata(testUserId)
        assertEquals(5000L, metadata?.cursor)
    }

    @Test
    fun snapshotRecoveryStress_preservesPendingOutboxMutationsAcross409Recovery() = runTest {
        // Enqueue 50 local mutations
        for (i in 1..50) {
            val solve = SolveEntity(
                id = "offline-solve-$i",
                ownerId = testUserId,
                durationMs = 13000L + i,
                solvedAt = "2026-08-30T09:00:00Z",
                version = 0L
            )
            solveDao.insert(solve)
            syncOutboxDao.enqueue(
                SyncOutboxEntity(
                    id = "mut-offline-$i",
                    ownerId = testUserId,
                    entityType = "solve",
                    entityId = solve.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
                    clientTime = "2026-08-30T09:00:00Z",
                    status = "pending"
                )
            )
        }

        var syncAttempts = 0
        fakeApiClient.dynamicSnapshotHandler = {
            SnapshotResponse(
                sessions = listOf(
                    SessionSnapshotDto(
                        id = "bootstrap-sess",
                        name = "Recovered Session",
                        event = "3x3",
                        startedAt = "2026-08-30T06:00:00Z",
                        version = 5L
                    )
                ),
                solves = emptyList(),
                cursor = 2000L,
                hasMore = false
            )
        }

        fakeApiClient.dynamicSyncHandler = { request ->
            syncAttempts++
            if (syncAttempts == 1) {
                // 409 API error
                throw AuthException.ApiError(
                    httpStatusCode = 409,
                    errorCode = "cursor_expired",
                    message = "Watermark cursor expired"
                )
            } else {
                // Resumed sync flushes the 50 pending mutations
                val outcomes = request.mutations.map { m ->
                    MutationOutcomeDto(
                        mutationId = m.id,
                        status = "accepted",
                        version = 1L
                    )
                }
                SyncResponse(
                    outcomes = outcomes,
                    changes = emptyList(),
                    nextCursor = request.cursor + outcomes.size,
                    hasMore = false
                )
            }
        }

        val result = syncEngine.sync(testUserId)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(50, success.mutationsSynced)

        // All 50 mutations should be cleared from outbox
        assertEquals(0, syncOutboxDao.countPending(testUserId))

        // Check snapshot entity exists
        assertNotNull(sessionDao.getSessionById("bootstrap-sess"))

        // Check all 50 offline solves updated to version 1
        for (i in 1..50) {
            val solve = solveDao.getSolveById("offline-solve-$i")
            assertEquals(1L, solve?.version)
        }
    }

    // =========================================================================
    // 3. OFFLINE QUEUEING & RE-CONNECTION OUTBOX FLUSH STRESS
    // =========================================================================

    @Test
    fun offlineStress_massMutationsQueuedWhileOffline_flushedOnReconnect() = runTest {
        // Generate 750 mutations (250 sessions + 500 solves)
        for (i in 1..250) {
            val session = SessionEntity(
                id = "mass-sess-$i",
                ownerId = testUserId,
                name = "Mass Session $i",
                event = "3x3",
                startedAt = "2026-08-30T09:00:00Z",
                version = 0L
            )
            sessionDao.insert(session)
            syncOutboxDao.enqueue(
                SyncOutboxEntity(
                    id = "mut-mass-sess-$i",
                    ownerId = testUserId,
                    entityType = "session",
                    entityId = session.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), session.toSyncPayload()),
                    clientTime = "2026-08-30T09:00:00Z",
                    status = "pending"
                )
            )
        }

        for (i in 1..500) {
            val solve = SolveEntity(
                id = "mass-solve-$i",
                ownerId = testUserId,
                sessionId = "mass-sess-${(i % 250) + 1}",
                durationMs = 11000L + i,
                solvedAt = "2026-08-30T09:10:00Z",
                event = "3x3",
                version = 0L
            )
            solveDao.insert(solve)
            syncOutboxDao.enqueue(
                SyncOutboxEntity(
                    id = "mut-mass-solve-$i",
                    ownerId = testUserId,
                    entityType = "solve",
                    entityId = solve.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
                    clientTime = "2026-08-30T09:10:00Z",
                    status = "pending"
                )
            )
        }

        assertEquals(750, syncOutboxDao.countPending(testUserId))

        // Step 1: Simulate Offline / Network unreachable failure
        fakeApiClient.shouldThrowNetworkError = true
        val offlineResult = syncEngine.sync(testUserId)
        assertTrue(offlineResult is SyncResult.Offline)
        assertEquals(SyncStatus.OFFLINE, syncEngine.syncStatus.value)

        // Mutations remain intact in outbox (failed/pending status)
        assertEquals(750, syncOutboxDao.countPending(testUserId))

        // Step 2: Simulate Reconnect
        fakeApiClient.shouldThrowNetworkError = false
        fakeApiClient.dynamicSyncHandler = { request ->
            val outcomes = request.mutations.map { m ->
                MutationOutcomeDto(
                    mutationId = m.id,
                    status = "accepted",
                    version = 1L
                )
            }
            SyncResponse(
                outcomes = outcomes,
                changes = emptyList(),
                nextCursor = request.cursor + outcomes.size,
                hasMore = false
            )
        }

        val reconnectResult = syncEngine.sync(testUserId)
        assertTrue(reconnectResult is SyncResult.Success)
        val success = reconnectResult as SyncResult.Success
        assertEquals(750, success.mutationsSynced)
        assertEquals(SyncStatus.SYNCED, syncEngine.syncStatus.value)

        // Outbox drained to 0
        assertEquals(0, syncOutboxDao.countPending(testUserId))

        // All 750 entities in Room have version 1
        for (i in 1..250) {
            assertEquals(1L, sessionDao.getSessionById("mass-sess-$i")?.version)
        }
        for (i in 1..500) {
            assertEquals(1L, solveDao.getSolveById("mass-solve-$i")?.version)
        }
    }

    @Test
    fun offlineStress_partialBatchResponse_unansweredMutationsResetToPending() = runTest {
        // Enqueue 100 mutations
        for (i in 1..100) {
            val solve = SolveEntity(
                id = "partial-solve-$i",
                ownerId = testUserId,
                durationMs = 12000L + i,
                solvedAt = "2026-08-30T09:00:00Z",
                version = 0L
            )
            solveDao.insert(solve)
            syncOutboxDao.enqueue(
                SyncOutboxEntity(
                    id = "mut-part-$i",
                    ownerId = testUserId,
                    entityType = "solve",
                    entityId = solve.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
                    clientTime = "2026-08-30T09:00:00Z",
                    status = "pending"
                )
            )
        }

        var callCount = 0
        fakeApiClient.dynamicSyncHandler = { request ->
            callCount++
            if (callCount == 1) {
                // Server answers only the first 40 outcomes, drops the remaining 60
                val outcomes = request.mutations.take(40).map { m ->
                    MutationOutcomeDto(mutationId = m.id, status = "accepted", version = 1L)
                }
                SyncResponse(
                    outcomes = outcomes,
                    changes = emptyList(),
                    nextCursor = 40L,
                    hasMore = false
                )
            } else {
                // Second sync call: server answers the remaining 60
                val outcomes = request.mutations.map { m ->
                    MutationOutcomeDto(mutationId = m.id, status = "accepted", version = 1L)
                }
                SyncResponse(
                    outcomes = outcomes,
                    changes = emptyList(),
                    nextCursor = 100L,
                    hasMore = false
                )
            }
        }

        val result1 = syncEngine.sync(testUserId)
        assertTrue(result1 is SyncResult.Success)
        assertEquals(40, (result1 as SyncResult.Success).mutationsSynced)

        // 60 mutations should have been reset to 'pending'
        val remainingPending = syncOutboxDao.getPendingMutations(testUserId)
        assertEquals(60, remainingPending.size)
        assertTrue(remainingPending.all { it.status == "pending" })

        // Second sync completes the remaining 60
        val result2 = syncEngine.sync(testUserId)
        assertTrue(result2 is SyncResult.Success)
        assertEquals(60, (result2 as SyncResult.Success).mutationsSynced)
        assertEquals(0, syncOutboxDao.countPending(testUserId))
    }

    // =========================================================================
    // 4. CONCURRENCY & THREAD SAFETY STRESS
    // =========================================================================

    @Test
    fun adversarial_concurrentSyncCalls_threadSafety() = runTest {
        // Enqueue 50 solves
        for (i in 1..50) {
            val solve = SolveEntity(
                id = "concurrent-solve-$i",
                ownerId = testUserId,
                durationMs = 10000L + i,
                solvedAt = "2026-08-30T09:00:00Z",
                version = 0L
            )
            solveDao.insert(solve)
            syncOutboxDao.enqueue(
                SyncOutboxEntity(
                    id = "mut-conc-$i",
                    ownerId = testUserId,
                    entityType = "solve",
                    entityId = solve.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), solve.toSyncPayload()),
                    clientTime = "2026-08-30T09:00:00Z",
                    status = "pending"
                )
            )
        }

        fakeApiClient.dynamicSyncHandler = { request ->
            val outcomes = request.mutations.map { m ->
                MutationOutcomeDto(mutationId = m.id, status = "accepted", version = 1L)
            }
            SyncResponse(
                outcomes = outcomes,
                changes = emptyList(),
                nextCursor = request.cursor + outcomes.size,
                hasMore = false
            )
        }

        // Fire 10 concurrent coroutines attempting sync simultaneously
        val deferreds = (1..10).map {
            async {
                syncEngine.sync(testUserId)
            }
        }

        val results = deferreds.awaitAll()

        // All 10 must succeed without exceptions
        assertTrue(results.all { it is SyncResult.Success || it is SyncResult.NoOp })

        // Outbox must be 0
        assertEquals(0, syncOutboxDao.countPending(testUserId))
        assertEquals(SyncStatus.SYNCED, syncEngine.syncStatus.value)
    }

    // =========================================================================
    // DYNAMIC TEST FAKES
    // =========================================================================

    private class DynamicFakeSyncApiClient : CubeSyncApiClient {
        val syncRequestsReceived = mutableListOf<SyncRequest>()
        val syncResponsesQueue = mutableListOf<SyncResponse>()
        var dynamicSyncHandler: ((SyncRequest) -> SyncResponse)? = null
        var dynamicSnapshotHandler: ((SnapshotRequest) -> SnapshotResponse)? = null
        var shouldThrowNetworkError = false

        override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse {
            syncRequestsReceived.add(request)
            if (shouldThrowNetworkError) {
                throw IOException("Network unreachable")
            }
            dynamicSyncHandler?.let { return it(request) }
            if (syncResponsesQueue.isNotEmpty()) {
                return syncResponsesQueue.removeAt(0)
            }
            return SyncResponse(nextCursor = request.cursor, hasMore = false)
        }

        override suspend fun snapshot(request: SnapshotRequest, authToken: String?): SnapshotResponse {
            dynamicSnapshotHandler?.let { return it(request) }
            return SnapshotResponse(cursor = request.cursor, hasMore = false)
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
        override suspend fun linkGoogle(idToken: String, authToken: String?) = Unit
        override suspend fun getCurrentUser(authToken: String?): UserDto = throw NotImplementedError()
        override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?) = Unit
        override suspend fun deleteAccount(authToken: String?) = Unit
    }

    private class DynamicFakeTokenStorage(private val userId: String) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>("stress-access-token")
        override fun getAccessToken(): String? = "stress-access-token"
        override fun setAccessToken(token: String?) {}
        override fun getRefreshToken(): String? = "stress-refresh-token"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = userId
        override fun getUserEmail(): String? = "stress@example.com"
        override fun getUserRole(): String? = "user"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "Stress Tester"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "stress-device-uuid"
        override fun isAuthenticated(): Boolean = true
    }

    private class DynamicFakeAuthManager(initialState: AuthState) : AuthManager {
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
