package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import com.maciekhetman.cubetimer.model.currentUser
import com.maciekhetman.cubetimer.model.isAdmin
import com.maciekhetman.cubetimer.model.isAuthenticated
import com.maciekhetman.cubetimer.model.isGuest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthManagerStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var fakeApiClient: FakeCubeSyncApiClient
    private lateinit var fakeTokenStorage: InMemoryTokenStorage
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var syncTriggerCount = 0

    private lateinit var authManager: AuthManagerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            })
            .build()

        fakeApiClient = FakeCubeSyncApiClient()
        fakeTokenStorage = InMemoryTokenStorage(initialDeviceId = "device-uuid-12345")
        syncTriggerCount = 0

        authManager = AuthManagerImpl(
            apiClient = fakeApiClient,
            tokenStorage = fakeTokenStorage,
            database = database,
            syncTrigger = { syncTriggerCount++ },
            json = json,
            ioDispatcher = testDispatcher,
            authScope = testScope,
            autoInitialize = false
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 1. HEAVY LOAD GUEST ADOPTION: 1,000 SOLVES + 50 SESSIONS
    // =========================================================================

    @Test
    fun testHeavyLoadGuestAdoption1000SolvesAnd50Sessions() = runTest(testDispatcher) {
        val targetUserId = "user_heavy_scale_1000"
        val sessionCount = 50
        val solveCount = 1000

        // 1. Seed 50 guest sessions across various events and kinds
        val events = listOf("3x3", "2x2", "4x4", "5x5", "pyraminx", "megaminx")
        val guestSessions = (1..sessionCount).map { i ->
            val event = events[i % events.size]
            val kind = if (i % 3 == 0) "automatic" else "manual"
            SessionEntity(
                id = "guest-sess-$i",
                ownerId = "guest",
                name = if (kind == "automatic") "30 Aug 2026 Part $i" else "Practice Session $i",
                event = event,
                kind = kind,
                startedAt = String.format("2026-08-30T%02d:%02d:00.000Z", (i / 60) % 24, i % 60),
                endedAt = if (kind == "automatic" && i % 2 == 0) "2026-08-30T23:59:59.000Z" else null,
                archived = (i % 10 == 0),
                version = 5L // preexisting version that should be reset to 0
            )
        }
        database.sessionDao().insertAll(guestSessions)

        // 2. Seed 1,000 guest solves across the sessions (and some unassigned)
        val penalties = listOf("none", "plus_two", "dnf", "none", "none")
        val guestSolves = (1..solveCount).map { i ->
            val assignedSessionId = if (i % 20 == 0) null else "guest-sess-${((i - 1) % sessionCount) + 1}"
            val event = events[i % events.size]
            SolveEntity(
                id = "guest-sol-$i",
                ownerId = "guest",
                sessionId = assignedSessionId,
                event = event,
                durationMs = 7000L + (i * 13L % 25000L),
                penalty = penalties[i % penalties.size],
                solvedAt = String.format("2026-08-30T%02d:%02d:%02d.000Z", (i / 3600) % 24, (i / 60) % 60, i % 60),
                scramble = "R U R' U' move-$i",
                version = 12L // preexisting version that should be reset to 0
            )
        }
        database.solveDao().insertAll(guestSolves)

        // Verify initial guest counts
        assertEquals(50, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(1000, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        // 3. Execute adoption
        authManager.adoptGuestData(targetUserId)

        // 4. Assert Room State
        // Guest ownership should be completely empty
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        // Target user owns all sessions and solves
        val adoptedSessions = database.sessionDao().getAllActiveSessionsForOwner(targetUserId)
        val adoptedSolves = database.solveDao().getAllActiveSolvesForOwner(targetUserId)
        assertEquals(sessionCount, adoptedSessions.size)
        assertEquals(solveCount, adoptedSolves.size)

        // Assert version reset to 0 and ownerId updated across every record
        assertTrue("All adopted sessions must have version = 0L", adoptedSessions.all { it.version == 0L })
        assertTrue("All adopted sessions must belong to target user", adoptedSessions.all { it.ownerId == targetUserId })
        assertTrue("All adopted solves must have version = 0L", adoptedSolves.all { it.version == 0L })
        assertTrue("All adopted solves must belong to target user", adoptedSolves.all { it.ownerId == targetUserId })

        // 5. Assert Outbox State
        val pendingOutbox = database.syncOutboxDao().getAllPendingForOwner(targetUserId, 2000)
        assertEquals("Total outbox mutations must match 50 sessions + 1000 solves", 1050, pendingOutbox.size)

        // Verify all mutations have target owner, action = 'upsert', baseVersion = 0L, status = 'pending'
        assertTrue("All outbox mutations must belong to target owner", pendingOutbox.all { it.ownerId == targetUserId })
        assertTrue("All outbox mutations must have action = 'upsert'", pendingOutbox.all { it.action == "upsert" })
        assertTrue("All outbox mutations must have baseVersion = 0L", pendingOutbox.all { it.baseVersion == 0L })
        assertTrue("All outbox mutations must have status = 'pending'", pendingOutbox.all { it.status == "pending" })

        // 6. Assert FK Order: All 50 session mutations must precede all 1000 solve mutations
        val sessionMutations = pendingOutbox.take(sessionCount)
        val solveMutations = pendingOutbox.drop(sessionCount)

        assertTrue("First 50 mutations must be sessions", sessionMutations.all { it.entityType == "session" })
        assertTrue("Subsequent 1000 mutations must be solves", solveMutations.all { it.entityType == "solve" })

        // 7. Verify JSON Payload Fidelity for every mutation
        val sessionMap = guestSessions.associateBy { it.id }
        for (m in sessionMutations) {
            val payload = json.decodeFromString<SessionSyncPayload>(requireNotNull(m.payloadJson))
            val original = sessionMap[m.entityId]
            assertNotNull("Original session must exist for entityId: ${m.entityId}", original)
            assertEquals(original?.id, payload.id)
            assertEquals(original?.name, payload.name)
            assertEquals(original?.event, payload.event)
            assertEquals(original?.kind, payload.kind)
            assertEquals(original?.startedAt, payload.startedAt)
            assertEquals(original?.endedAt, payload.endedAt)
            assertEquals(original?.archived, payload.archived)
        }

        val solveMap = guestSolves.associateBy { it.id }
        for (m in solveMutations) {
            val payload = json.decodeFromString<SolveSyncPayload>(requireNotNull(m.payloadJson))
            val original = solveMap[m.entityId]
            assertNotNull("Original solve must exist for entityId: ${m.entityId}", original)
            assertEquals(original?.id, payload.id)
            assertEquals(original?.sessionId, payload.sessionId)
            assertEquals(original?.durationMs, payload.durationMs)
            assertEquals(original?.penalty, payload.penalty)
            assertEquals(original?.solvedAt, payload.solvedAt)
            assertEquals(original?.scramble, payload.scramble)
            assertEquals(original?.event, payload.event)
        }
    }

    // =========================================================================
    // 2. CONSECUTIVE TRANSITIONS: LOGIN -> LOGOUT -> GUEST -> LOGIN
    // =========================================================================

    @Test
    fun testConsecutiveLoginLogoutGuestLoginStateTransitionsAndIsolation() = runTest(testDispatcher) {
        val initialDeviceId = fakeTokenStorage.getDeviceId()
        assertEquals("device-uuid-12345", initialDeviceId)

        // Phase 1: Fresh startup as Guest
        authManager.initialize()
        assertTrue(authManager.authState.value.isGuest)
        assertNull(authManager.currentUser)

        // Guest creates 3 sessions and 30 solves
        val guestSessions1 = (1..3).map { i ->
            SessionEntity(
                id = "g-sess-$i",
                ownerId = "guest",
                name = "Guest Session $i",
                event = "3x3",
                startedAt = "2026-08-30T08:00:00Z"
            )
        }
        database.sessionDao().insertAll(guestSessions1)

        val guestSolves1 = (1..30).map { i ->
            SolveEntity(
                id = "g-sol-$i",
                ownerId = "guest",
                sessionId = "g-sess-${((i - 1) % 3) + 1}",
                event = "3x3",
                durationMs = 12000L + i,
                solvedAt = "2026-08-30T08:10:00Z"
            )
        }
        database.solveDao().insertAll(guestSolves1)

        // Phase 2: User A Logs In
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-user-a",
            refreshToken = "ref-user-a",
            user = UserDto(id = "user-a", email = "user.a@test.com", userRole = "user", emailVerified = true)
        )

        val loginAResult = authManager.login("user.a@test.com", "PasswordA123!")
        assertTrue(loginAResult is AuthResult.Success)
        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("user-a", authManager.currentUser?.id)
        assertEquals(1, syncTriggerCount)

        // Verify adoption for User A
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
        assertEquals(3, database.sessionDao().getAllActiveSessionsForOwner("user-a").size)
        assertEquals(30, database.solveDao().getAllActiveSolvesForOwner("user-a").size)
        assertEquals(33, database.syncOutboxDao().getAllPendingForOwner("user-a").size)

        // DeviceId remains stable
        assertEquals(initialDeviceId, fakeTokenStorage.getDeviceId())

        // Phase 3: User A creates an open automatic session and 10 solves while logged in
        val autoSessionA = SessionEntity(
            id = "auto-sess-a",
            ownerId = "user-a",
            name = "30 Aug 2026 Auto",
            event = "3x3",
            kind = "automatic",
            startedAt = "2026-08-30T09:00:00Z",
            endedAt = null
        )
        database.sessionDao().insert(autoSessionA)

        val userASolves = (31..40).map { i ->
            SolveEntity(
                id = "sol-a-$i",
                ownerId = "user-a",
                sessionId = "auto-sess-a",
                event = "3x3",
                durationMs = 11000L + i,
                solvedAt = "2026-08-30T09:05:00Z"
            )
        }
        database.solveDao().insertAll(userASolves)

        // Phase 4: User A Logs Out
        val logoutAResult = authManager.logout()
        assertTrue(logoutAResult is AuthResult.Success)
        assertTrue(authManager.authState.value.isGuest)
        assertNull(authManager.currentUser)
        assertNull(fakeTokenStorage.storedAccessToken)
        assertNull(fakeTokenStorage.storedRefreshToken)

        // Open automatic session for User A must now be closed
        val closedAutoSession = database.sessionDao().getSessionById("auto-sess-a")
        assertNotNull(closedAutoSession?.endedAt)

        // DeviceId is preserved across logout
        assertEquals(initialDeviceId, fakeTokenStorage.getDeviceId())

        // User A's data remains in DB untouched (4 sessions, 40 solves)
        assertEquals(4, database.sessionDao().getAllActiveSessionsForOwner("user-a").size)
        assertEquals(40, database.solveDao().getAllActiveSolvesForOwner("user-a").size)

        // Phase 5: Guest Mode timing after User A logout
        // Guest creates 2 sessions and 15 solves
        val guestSessions2 = (10..11).map { i ->
            SessionEntity(
                id = "g2-sess-$i",
                ownerId = "guest",
                name = "Guest Session $i",
                event = "2x2",
                startedAt = "2026-08-30T10:00:00Z"
            )
        }
        database.sessionDao().insertAll(guestSessions2)

        val guestSolves2 = (100..114).map { i ->
            SolveEntity(
                id = "g2-sol-$i",
                ownerId = "guest",
                sessionId = "g2-sess-10",
                event = "2x2",
                durationMs = 4000L + i,
                solvedAt = "2026-08-30T10:05:00Z"
            )
        }
        database.solveDao().insertAll(guestSolves2)

        assertEquals(2, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(15, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        // Phase 6: User B Logs In
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-user-b",
            refreshToken = "ref-user-b",
            user = UserDto(id = "user-b", email = "user.b@test.com", userRole = "user", emailVerified = true)
        )

        val loginBResult = authManager.login("user.b@test.com", "PasswordB123!")
        assertTrue(loginBResult is AuthResult.Success)
        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("user-b", authManager.currentUser?.id)
        assertEquals(2, syncTriggerCount)

        // User B adopted ONLY the second guest batch (2 sessions, 15 solves)
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
        assertEquals(2, database.sessionDao().getAllActiveSessionsForOwner("user-b").size)
        assertEquals(15, database.solveDao().getAllActiveSolvesForOwner("user-b").size)
        assertEquals(17, database.syncOutboxDao().getAllPendingForOwner("user-b").size)

        // User A's data was completely isolated and unaffected
        assertEquals(4, database.sessionDao().getAllActiveSessionsForOwner("user-a").size)
        assertEquals(40, database.solveDao().getAllActiveSolvesForOwner("user-a").size)
        assertEquals(33, database.syncOutboxDao().getAllPendingForOwner("user-a").size)

        // Phase 7: User B Logs Out, Guest creates 5 solves
        authManager.logout()
        assertTrue(authManager.authState.value.isGuest)

        val guestSolves3 = (200..204).map { i ->
            SolveEntity(
                id = "g3-sol-$i",
                ownerId = "guest",
                sessionId = null,
                event = "3x3",
                durationMs = 13000L + i,
                solvedAt = "2026-08-30T11:00:00Z"
            )
        }
        database.solveDao().insertAll(guestSolves3)
        assertEquals(5, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        // Phase 8: User A Logs In Again
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-user-a-2",
            refreshToken = "ref-user-a-2",
            user = UserDto(id = "user-a", email = "user.a@test.com", userRole = "user", emailVerified = true)
        )
        val loginA2Result = authManager.login("user.a@test.com", "PasswordA123!")
        assertTrue(loginA2Result is AuthResult.Success)
        assertEquals("user-a", authManager.currentUser?.id)

        // User A now has original 40 solves + 5 newly adopted guest solves = 45 solves
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
        assertEquals(45, database.solveDao().getAllActiveSolvesForOwner("user-a").size)
        // User A outbox: 33 previous + 5 new = 38 mutations
        assertEquals(38, database.syncOutboxDao().getAllPendingForOwner("user-a").size)

        // User B data remains intact: 2 sessions, 15 solves
        assertEquals(2, database.sessionDao().getAllActiveSessionsForOwner("user-b").size)
        assertEquals(15, database.solveDao().getAllActiveSolvesForOwner("user-b").size)

        // Verify zero orphan rows
        val allSolves = database.solveDao().getAllSolvesForOwner("user-a") +
                database.solveDao().getAllSolvesForOwner("user-b") +
                database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(60, allSolves.size) // 45 (user-a) + 15 (user-b) + 0 (guest)

        // DeviceId remains stable across the entire multi-user lifecycle
        assertEquals(initialDeviceId, fakeTokenStorage.getDeviceId())
    }

    // =========================================================================
    // 3. OFFLINE AUTH LIFECYCLE & CACHED USER RECOVERY
    // =========================================================================

    @Test
    fun testOfflineStartupWithCachedUserRestoresAuthenticatedState() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "offline-refresh-token"
        fakeTokenStorage.storedCachedUser = User(
            id = "cached-regular-user",
            email = "cached@cubetimer.io",
            displayName = "Offline Cuber",
            emailVerified = true,
            userRole = UserRole.USER
        )
        fakeApiClient.refreshError = AuthException.NetworkError("No internet connection")

        authManager.initialize()

        assertTrue(authManager.authState.value.isAuthenticated)
        assertFalse(authManager.authState.value.isAdmin)
        val user = authManager.currentUser
        assertNotNull(user)
        assertEquals("cached-regular-user", user?.id)
        assertEquals("Offline Cuber", user?.displayName)
        assertEquals(UserRole.USER, user?.userRole)
    }

    @Test
    fun testOfflineStartupWithCachedAdminRestoresAdminState() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "admin-refresh-token"
        fakeTokenStorage.storedCachedUser = User(
            id = "admin-root-user",
            email = "admin@cubetimer.io",
            displayName = "System Administrator",
            emailVerified = true,
            userRole = UserRole.ADMIN
        )
        fakeApiClient.refreshError = AuthException.NetworkError("Host unreachable")

        authManager.initialize()

        assertTrue(authManager.authState.value.isAdmin)
        assertTrue(authManager.authState.value.isAuthenticated)
        val admin = authManager.currentUser
        assertNotNull(admin)
        assertEquals("admin-root-user", admin?.id)
        assertEquals(UserRole.ADMIN, admin?.userRole)
    }

    @Test
    fun testUnrecoverableRefreshTokenErrorWipesCachedCredentialsAndRevertsToGuest() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "compromised-refresh-token"
        fakeTokenStorage.storedCachedUser = User(
            id = "compromised-user",
            email = "user@hacked.com",
            userRole = UserRole.USER
        )
        fakeApiClient.refreshError = AuthException.RefreshTokenReused("Token reuse anomaly detected")

        authManager.initialize()

        assertTrue(authManager.authState.value.isGuest)
        assertNull(authManager.currentUser)
        assertNull(fakeTokenStorage.storedRefreshToken)
        assertNull(fakeTokenStorage.storedCachedUser)
    }

    // =========================================================================
    // 4. ADOPTION EDGE CASES: EMPTY DATA & CONCURRENT CALLS
    // =========================================================================

    @Test
    fun testAdoptGuestDataWhenNoGuestDataExistsIsNoOp() = runTest(testDispatcher) {
        authManager.adoptGuestData("user-with-no-guest-solves")

        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("user-with-no-guest-solves").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("user-with-no-guest-solves").size)
        assertEquals(0, database.syncOutboxDao().getAllPendingForOwner("user-with-no-guest-solves").size)
    }

    @Test
    fun testAdoptGuestDataWithOnlySessionsAndZeroSolves() = runTest(testDispatcher) {
        val s1 = SessionEntity(id = "sess-only-1", ownerId = "guest", name = "Session 1", event = "3x3", startedAt = "2026-08-30T10:00:00Z")
        val s2 = SessionEntity(id = "sess-only-2", ownerId = "guest", name = "Session 2", event = "4x4", startedAt = "2026-08-30T10:05:00Z")
        database.sessionDao().insertAll(listOf(s1, s2))

        authManager.adoptGuestData("user-sessions-only")

        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(2, database.sessionDao().getAllActiveSessionsForOwner("user-sessions-only").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("user-sessions-only").size)

        val outbox = database.syncOutboxDao().getAllPendingForOwner("user-sessions-only")
        assertEquals(2, outbox.size)
        assertTrue(outbox.all { it.entityType == "session" && it.action == "upsert" && it.baseVersion == 0L })
    }

    @Test
    fun testAdoptGuestDataWithOnlySolvesAndZeroSessions() = runTest(testDispatcher) {
        val solve = SolveEntity(id = "sol-only-1", ownerId = "guest", sessionId = null, event = "3x3", durationMs = 14200L, solvedAt = "2026-08-30T10:00:00Z")
        database.solveDao().insert(solve)

        authManager.adoptGuestData("user-solves-only")

        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)
        assertEquals(1, database.solveDao().getAllActiveSolvesForOwner("user-solves-only").size)

        val outbox = database.syncOutboxDao().getAllPendingForOwner("user-solves-only")
        assertEquals(1, outbox.size)
        assertEquals("solve", outbox[0].entityType)
        assertEquals("upsert", outbox[0].action)
        assertEquals(0L, outbox[0].baseVersion)
    }

    // =========================================================================
    // TEST HELPER FAKES
    // =========================================================================

    private class FakeCubeSyncApiClient : CubeSyncApiClient {
        var registerResponse: StatusResponse = StatusResponse("verification_required")
        var registerError: AuthException? = null

        var loginResponse: AuthResponse? = null
        var loginError: AuthException? = null

        var refreshResponse: AuthResponse? = null
        var refreshError: AuthException? = null

        var verifyEmailResponse: AuthResponse? = null
        var verifyEmailError: AuthException? = null

        var resetPasswordResponse: AuthResponse? = null
        var resetPasswordError: AuthException? = null

        var googleLoginResponse: AuthResponse? = null
        var googleLoginError: AuthException? = null

        override suspend fun register(request: RegisterRequest): StatusResponse {
            registerError?.let { throw it }
            return registerResponse
        }

        override suspend fun resendVerificationEmail(email: String): StatusResponse = StatusResponse("accepted")

        override suspend fun verifyEmail(token: String): AuthResponse {
            verifyEmailError?.let { throw it }
            return verifyEmailResponse ?: throw AuthException.InvalidToken()
        }

        override suspend fun login(request: LoginRequest): AuthResponse {
            loginError?.let { throw it }
            return loginResponse ?: throw AuthException.InvalidCredentials()
        }

        override suspend fun refreshToken(refreshToken: String): AuthResponse {
            refreshError?.let { throw it }
            return refreshResponse ?: throw AuthException.InvalidRefreshToken()
        }

        override suspend fun logout(refreshToken: String) {}

        override suspend fun requestPasswordReset(email: String): StatusResponse = StatusResponse("accepted")

        override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse {
            resetPasswordError?.let { throw it }
            return resetPasswordResponse ?: throw AuthException.InvalidToken()
        }

        override suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse {
            googleLoginError?.let { throw it }
            return googleLoginResponse ?: throw AuthException.InvalidSocialToken()
        }

        override suspend fun linkGoogle(idToken: String, authToken: String?) {}
        override suspend fun getCurrentUser(authToken: String?): UserDto = UserDto("u", "e@t.com")
        override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?) {}
        override suspend fun deleteAccount(authToken: String?) {}
        override suspend fun sync(
            request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest,
            authToken: String?
        ): com.maciekhetman.cubetimer.data.remote.dto.SyncResponse =
            com.maciekhetman.cubetimer.data.remote.dto.SyncResponse()
        override suspend fun snapshot(
            request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest,
            authToken: String?
        ): com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse =
            com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse()
    }

    private class InMemoryTokenStorage(
        initialDeviceId: String = "default-test-device-id"
    ) : TokenStorage {
        var storedAccessToken: String? = null
        var storedRefreshToken: String? = null
        var storedUserId: String? = null
        var storedUserEmail: String? = null
        var storedUserRole: String? = null
        var storedEmailVerified: Boolean = false
        var storedDisplayName: String? = null
        var storedCachedUser: User? = null
        var storedDeviceId: String = initialDeviceId

        private val _flow = MutableStateFlow<String?>(storedAccessToken)
        override val accessTokenFlow: StateFlow<String?> = _flow

        override fun getAccessToken(): String? = storedAccessToken
        override fun setAccessToken(token: String?) {
            storedAccessToken = token
            _flow.value = token
        }
        override fun getRefreshToken(): String? = storedRefreshToken
        override fun setRefreshToken(token: String?) { storedRefreshToken = token }
        override fun getUserId(): String? = storedUserId
        override fun getUserEmail(): String? = storedUserEmail
        override fun getUserRole(): String? = storedUserRole
        override fun isUserEmailVerified(): Boolean = storedEmailVerified
        override fun getDisplayName(): String? = storedDisplayName
        override fun getCachedUser(): User? = storedCachedUser
        override fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
            userId: String,
            userEmail: String,
            userRole: String,
            emailVerified: Boolean,
            displayName: String?
        ) {
            setAccessToken(accessToken)
            this.storedRefreshToken = refreshToken
            this.storedUserId = userId
            this.storedUserEmail = userEmail
            this.storedUserRole = userRole
            this.storedEmailVerified = emailVerified
            this.storedDisplayName = displayName
            this.storedCachedUser = User(
                id = userId,
                email = userEmail,
                displayName = displayName,
                emailVerified = emailVerified,
                userRole = UserRole.fromString(userRole)
            )
        }
        override fun saveUser(user: User) {
            this.storedCachedUser = user
        }
        override fun getDeviceId(): String = storedDeviceId
        override fun clearAuthData() {
            setAccessToken(null)
            storedRefreshToken = null
            storedUserId = null
            storedUserEmail = null
            storedUserRole = null
            storedCachedUser = null
            // Note: deviceId is NOT cleared in clearAuthData()
        }
        override fun clearAll() {
            clearAuthData()
            storedDeviceId = UUID.randomUUID().toString()
        }
    }
}
