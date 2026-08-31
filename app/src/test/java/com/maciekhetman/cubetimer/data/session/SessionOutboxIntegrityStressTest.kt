package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toDomain
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SessionOutboxIntegrityStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var solvesRepository: SolvesRepository
    private val json: Json = NetworkModule.json

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testAllGuestSessionAndSolveOperationsProduceZeroOutboxRecords() = runTest {
        // 1. Guest Session Lifecycle
        val guestSession = sessionRepository.createManualSession("Guest Session 1", Mode.CUBE_3x3, ownerId = "guest")
        sessionRepository.renameSession(guestSession.id, "Guest Session Renamed", ownerId = "guest")
        sessionRepository.archiveSession(guestSession.id, ownerId = "guest")
        sessionRepository.unarchiveSession(guestSession.id, ownerId = "guest")
        sessionRepository.closeSession(guestSession.id, ownerId = "guest")
        sessionRepository.deleteSession(guestSession.id, ownerId = "guest")

        // 2. Guest Solve Lifecycle
        val guestSolve = SolveTime(
            id = "solve-guest-001",
            timeInMillis = 14500L,
            penalty = Penalty.NONE,
            timestamp = System.currentTimeMillis(),
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3,
            sessionId = guestSession.id
        )
        solvesRepository.saveSolve(guestSolve, ownerId = "guest")
        solvesRepository.updateSolvePenalty(guestSolve, Penalty.PLUS_TWO, ownerId = "guest")
        solvesRepository.deleteSolve(guestSolve, ownerId = "guest")
        solvesRepository.restoreSolves(listOf(guestSolve), ownerId = "guest")
        solvesRepository.clearAllSolves(ownerId = "guest")

        // Verify outbox remains completely empty for guest
        val guestOutbox = database.syncOutboxDao().getPendingMutations("guest")
        assertEquals("Guest operations must NEVER enqueue outbox mutations", 0, guestOutbox.size)
        assertEquals(0, database.syncOutboxDao().countPending("guest"))
    }

    @Test
    fun testAuthenticatedSessionCrudProducesValidSyncOutboxRecords() = runTest {
        val userId = "user-auth-123"

        // 1. Create Manual Session
        val created = sessionRepository.createManualSession("Match Prep", Mode.CUBE_3x3, ownerId = userId)
        var pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(1, pending.size)
        val m1 = pending[0]
        assertEquals("session", m1.entityType)
        assertEquals(created.id, m1.entityId)
        assertEquals("upsert", m1.action)
        assertEquals(0L, m1.baseVersion)
        val p1 = json.decodeFromString<SessionSyncPayload>(m1.payloadJson!!)
        assertEquals(created.id, p1.id)
        assertEquals("Match Prep", p1.name)
        assertEquals("3x3", p1.event)
        assertEquals("manual", p1.kind)
        assertFalse(p1.archived)
        assertNull(p1.endedAt)

        // 2. Rename Session
        sessionRepository.renameSession(created.id, "Main Event Practice", ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(2, pending.size)
        val m2 = pending[1]
        assertEquals("upsert", m2.action)
        val p2 = json.decodeFromString<SessionSyncPayload>(m2.payloadJson!!)
        assertEquals("Main Event Practice", p2.name)

        // 3. Close Session (on open session)
        sessionRepository.closeSession(created.id, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(3, pending.size)
        val m3 = pending[2]
        assertEquals("upsert", m3.action)
        val p3 = json.decodeFromString<SessionSyncPayload>(m3.payloadJson!!)
        assertNotNull(p3.endedAt)

        // 3b. Verify closeSession is idempotent (does not enqueue duplicate mutation if already ended)
        sessionRepository.closeSession(created.id, ownerId = userId)
        assertEquals(3, database.syncOutboxDao().getPendingMutations(userId).size)

        // 4. Archive Session
        sessionRepository.archiveSession(created.id, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(4, pending.size)
        val m4 = pending[3]
        assertEquals("upsert", m4.action)
        val p4 = json.decodeFromString<SessionSyncPayload>(m4.payloadJson!!)
        assertTrue(p4.archived)
        assertNotNull(p4.endedAt)

        // 5. Unarchive Session
        sessionRepository.unarchiveSession(created.id, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(5, pending.size)
        val m5 = pending[4]
        val p5 = json.decodeFromString<SessionSyncPayload>(m5.payloadJson!!)
        assertFalse(p5.archived)

        // 6. Delete Session
        sessionRepository.deleteSession(created.id, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(6, pending.size)
        val m6 = pending[5]
        assertEquals("delete", m6.action)
        assertEquals("session", m6.entityType)
        assertEquals(created.id, m6.entityId)
        assertNull(m6.payloadJson)
    }

    @Test
    fun testAuthenticatedSolveCrudProducesValidSyncOutboxRecords() = runTest {
        val userId = "user-auth-solves"
        val session = sessionRepository.createManualSession("Speed Session", Mode.CUBE_4x4, ownerId = userId)

        val solve = SolveTime(
            id = "solve-auth-4x4-1",
            timeInMillis = 42150L,
            penalty = Penalty.NONE,
            timestamp = 1756550000000L,
            scramble = "Rw U2 Fw' D2",
            mode = Mode.CUBE_4x4,
            sessionId = session.id
        )

        // 1. Save solve
        solvesRepository.saveSolve(solve, ownerId = userId, sessionId = session.id)
        var pending = database.syncOutboxDao().getPendingMutations(userId)
        // Note: 1 session mutation + 1 solve mutation
        assertEquals(2, pending.size)
        val solveUpsert = pending.last()
        assertEquals("solve", solveUpsert.entityType)
        assertEquals(solve.id, solveUpsert.entityId)
        assertEquals("upsert", solveUpsert.action)
        val payload1 = json.decodeFromString<SolveSyncPayload>(solveUpsert.payloadJson!!)
        assertEquals(solve.id, payload1.id)
        assertEquals(session.id, payload1.sessionId)
        assertEquals(42150L, payload1.durationMs)
        assertEquals("none", payload1.penalty)
        assertEquals("4x4", payload1.event)
        assertEquals("Rw U2 Fw' D2", payload1.scramble)

        // 2. Update Penalty
        solvesRepository.updateSolvePenalty(solve, Penalty.PLUS_TWO, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(3, pending.size)
        val penaltyUpsert = pending.last()
        assertEquals("upsert", penaltyUpsert.action)
        val payload2 = json.decodeFromString<SolveSyncPayload>(penaltyUpsert.payloadJson!!)
        assertEquals("plus_two", payload2.penalty)

        // 3. Delete Solve
        solvesRepository.deleteSolve(solve, ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(4, pending.size)
        val solveDelete = pending.last()
        assertEquals("delete", solveDelete.action)
        assertEquals("solve", solveDelete.entityType)
        assertEquals(solve.id, solveDelete.entityId)
        assertNull(solveDelete.payloadJson)

        // 4. Restore Solve
        solvesRepository.restoreSolves(listOf(solve), ownerId = userId)
        pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(5, pending.size)
        val solveRestore = pending.last()
        assertEquals("upsert", solveRestore.action)
        val payload3 = json.decodeFromString<SolveSyncPayload>(solveRestore.payloadJson!!)
        assertEquals(solve.id, payload3.id)
    }

    @Test
    fun testSolveMappersPreserveSessionIdUnderAllConditions() {
        // Case 1: Non-null sessionId
        val solveWithSession = SolveTime(
            id = "s-101",
            timeInMillis = 11200L,
            penalty = Penalty.NONE,
            timestamp = 1756550000000L,
            scramble = "U R U' R'",
            mode = Mode.CUBE_3x3,
            sessionId = "session-target-99"
        )
        val entity1 = solveWithSession.toSolveEntity(ownerId = "u1")
        assertEquals("session-target-99", entity1.sessionId)
        val domain1 = entity1.toSolveTime()
        assertEquals("session-target-99", domain1.sessionId)
        val syncPayload1 = solveWithSession.toSyncPayload()
        assertEquals("session-target-99", syncPayload1.sessionId)
        val entityPayload1 = entity1.toSyncPayload()
        assertEquals("session-target-99", entityPayload1.sessionId)

        // Case 2: Null sessionId
        val solveWithoutSession = SolveTime(
            id = "s-102",
            timeInMillis = 9800L,
            penalty = Penalty.NONE,
            timestamp = 1756550000000L,
            scramble = "F R U R' U' F'",
            mode = Mode.CUBE_2x2,
            sessionId = null
        )
        val entity2 = solveWithoutSession.toSolveEntity(ownerId = "u1")
        assertNull(entity2.sessionId)
        val domain2 = entity2.toSolveTime()
        assertNull(domain2.sessionId)
        val syncPayload2 = solveWithoutSession.toSyncPayload()
        assertNull(syncPayload2.sessionId)
        val entityPayload2 = entity2.toSyncPayload()
        assertNull(entityPayload2.sessionId)

        // Case 3: Override sessionId in toSolveEntity
        val entityOverridden = solveWithoutSession.toSolveEntity(ownerId = "u1", sessionId = "explicit-session-42")
        assertEquals("explicit-session-42", entityOverridden.sessionId)
        val domainOverridden = entityOverridden.toSolveTime()
        assertEquals("explicit-session-42", domainOverridden.sessionId)
    }

    @Test
    fun testGuestAdoptionMaintainsSessionLinkageAndEnqueuesInCorrectOrder() = runTest {
        val targetUserId = "user-adopt-555"

        // 1. Create Guest Session
        val sessionEntity = SessionEntity(
            id = "guest-sess-alpha",
            ownerId = "guest",
            name = "Guest Session Alpha",
            event = "3x3",
            kind = "manual",
            startedAt = Instant.now().toString()
        )
        database.sessionDao().insert(sessionEntity)

        // 2. Create Guest Solves linked to that session
        val solve1 = SolveEntity(
            id = "guest-sol-1",
            ownerId = "guest",
            sessionId = sessionEntity.id,
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = Instant.now().toString(),
            scramble = "R U R'"
        )
        val solve2 = SolveEntity(
            id = "guest-sol-2",
            ownerId = "guest",
            sessionId = sessionEntity.id,
            event = "3x3",
            durationMs = 13000L,
            penalty = "plus_two",
            solvedAt = Instant.now().toString(),
            scramble = "L U L'"
        )
        database.solveDao().upsertAll(listOf(solve1, solve2))

        // Ensure no outbox entries before adoption
        assertEquals(0, database.syncOutboxDao().countPending("guest"))
        assertEquals(0, database.syncOutboxDao().countPending(targetUserId))

        // 3. Adopt Guest Data
        val dummyAuthManager = AuthManagerImpl(
            apiClient = DummyApiClient(),
            tokenStorage = DummyTokenStorage(),
            database = database,
            autoInitialize = false
        )
        dummyAuthManager.adoptGuestData(targetUserId)

        // 4. Verify Database Ownership
        val userSessions = database.sessionDao().getAllActiveSessionsForOwner(targetUserId)
        assertEquals(1, userSessions.size)
        assertEquals("guest-sess-alpha", userSessions[0].id)
        assertEquals(targetUserId, userSessions[0].ownerId)

        val userSolves = database.solveDao().getAllActiveSolvesForOwner(targetUserId)
        assertEquals(2, userSolves.size)
        assertTrue(userSolves.all { it.ownerId == targetUserId })
        assertTrue(userSolves.all { it.sessionId == "guest-sess-alpha" })

        // 5. Verify Outbox Order: Sessions MUST precede Solves to satisfy foreign keys / dependency order
        val outbox = database.syncOutboxDao().getPendingMutations(targetUserId)
        assertEquals(3, outbox.size)
        assertEquals("session", outbox[0].entityType)
        assertEquals("guest-sess-alpha", outbox[0].entityId)

        assertEquals("solve", outbox[1].entityType)
        assertEquals("solve", outbox[2].entityType)
        val solveIds = listOf(outbox[1].entityId, outbox[2].entityId)
        assertTrue(solveIds.contains("guest-sol-1"))
        assertTrue(solveIds.contains("guest-sol-2"))

        // Verify outbox payloads parse cleanly
        val sPayload = json.decodeFromString<SessionSyncPayload>(outbox[0].payloadJson!!)
        assertEquals("guest-sess-alpha", sPayload.id)
        assertEquals("3x3", sPayload.event)

        val solPayload1 = json.decodeFromString<SolveSyncPayload>(outbox[1].payloadJson!!)
        assertEquals("guest-sess-alpha", solPayload1.sessionId)
    }

    @Test
    fun testConcurrentSolveAndSessionCreationIntegrity() = runTest {
        val userId = "user-stress-concurrency"
        val sessionCount = 10
        val solvesPerSession = 10

        coroutineScope {
            val sessionTasks = (1..sessionCount).map { sessionIdx ->
                async {
                    val session = sessionRepository.createManualSession(
                        name = "Stress Session $sessionIdx",
                        mode = Mode.CUBE_3x3,
                        ownerId = userId
                    )
                    val solveTasks = (1..solvesPerSession).map { solveIdx ->
                        async {
                            val solve = SolveTime(
                                id = "stress-solve-$sessionIdx-$solveIdx",
                                timeInMillis = (10000 + solveIdx * 100).toLong(),
                                penalty = Penalty.NONE,
                                timestamp = System.currentTimeMillis(),
                                scramble = "R U R' U'",
                                mode = Mode.CUBE_3x3,
                                sessionId = session.id
                            )
                            solvesRepository.saveSolve(solve, ownerId = userId, sessionId = session.id)
                        }
                    }
                    solveTasks.awaitAll()
                    session
                }
            }
            sessionTasks.awaitAll()
        }

        // Verify total sessions and solves in DB
        val totalSessions = database.sessionDao().getAllActiveSessionsForOwner(userId)
        assertEquals(sessionCount, totalSessions.size)

        val totalSolves = database.solveDao().getAllActiveSolvesForOwner(userId)
        assertEquals(sessionCount * solvesPerSession, totalSolves.size)

        // Verify all outbox records
        val outbox = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(sessionCount + (sessionCount * solvesPerSession), outbox.size)

        val sessionOutbox = outbox.filter { it.entityType == "session" }
        val solveOutbox = outbox.filter { it.entityType == "solve" }
        assertEquals(sessionCount, sessionOutbox.size)
        assertEquals(sessionCount * solvesPerSession, solveOutbox.size)

        // Verify all solve outbox entries have valid sessionId matching their respective session
        for (solveEntry in solveOutbox) {
            val payload = json.decodeFromString<SolveSyncPayload>(solveEntry.payloadJson!!)
            assertNotNull(payload.sessionId)
            assertTrue(totalSessions.any { it.id == payload.sessionId })
        }
    }

    // Minimal Dummy implementations for test
    private class DummyApiClient : CubeSyncApiClient {
        override suspend fun register(request: RegisterRequest): StatusResponse = throw NotImplementedError()
        override suspend fun login(request: LoginRequest): AuthResponse = throw NotImplementedError()
        override suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse = throw NotImplementedError()
        override suspend fun refreshToken(refreshToken: String): AuthResponse = throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResponse = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = Unit
        override suspend fun linkGoogle(idToken: String, authToken: String?) = Unit
        override suspend fun getCurrentUser(authToken: String?): UserDto = throw NotImplementedError()
        override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?) = Unit
        override suspend fun deleteAccount(authToken: String?) = Unit
        override suspend fun sync(
            request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest,
            authToken: String?
        ): com.maciekhetman.cubetimer.data.remote.dto.SyncResponse = throw NotImplementedError()
        override suspend fun snapshot(
            request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest,
            authToken: String?
        ): com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse = throw NotImplementedError()
    }

    private class DummyTokenStorage : TokenStorage {
        private val _flow = MutableStateFlow<String?>(null)
        override val accessTokenFlow: StateFlow<String?> = _flow

        override fun getAccessToken(): String? = null
        override fun setAccessToken(token: String?) { _flow.value = token }
        override fun getRefreshToken(): String? = null
        override fun setRefreshToken(token: String?) = Unit
        override fun getUserId(): String? = null
        override fun getUserEmail(): String? = null
        override fun getUserRole(): String? = null
        override fun isUserEmailVerified(): Boolean = false
        override fun getDisplayName(): String? = null
        override fun getCachedUser(): User? = null
        override fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
            userId: String,
            userEmail: String,
            userRole: String,
            emailVerified: Boolean,
            displayName: String?
        ) = Unit
        override fun saveUser(user: User) = Unit
        override fun getDeviceId(): String = "test-device"
        override fun clearAuthData() = Unit
        override fun clearAll() = Unit
    }
}
