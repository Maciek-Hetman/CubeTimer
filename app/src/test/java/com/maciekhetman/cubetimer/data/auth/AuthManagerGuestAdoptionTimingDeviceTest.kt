package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression suite for guest-data adoption on login: [AuthManagerImpl.adoptGuestData] used to
 * hand-build `SolveSyncPayload` without `timing_device`, so every guest solve timed with a
 * Bluetooth timer was uploaded as `"keyboard"`. The mutations now go through the shared outbox
 * mappers; these tests pin the timing device, the payload field sets (the server decodes with
 * `DisallowUnknownFields`), and the session-before-solve outbox ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthManagerGuestAdoptionTimingDeviceTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var fakeApiClient: FakeCubeSyncApiClient
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    /** Outbox rows for the user as observed from inside `syncTrigger` (null = never triggered). */
    private var outboxAtSyncTrigger: List<SyncOutboxEntity>? = null

    private lateinit var authManager: AuthManagerImpl

    // Same config the production AuthManagerImpl defaults to; used only to read payloads back.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        fakeApiClient = FakeCubeSyncApiClient()
        fakeTokenStorage = FakeTokenStorage()
        outboxAtSyncTrigger = null

        // No `json` override: the production default serializer config is what goes on the wire.
        authManager = AuthManagerImpl(
            apiClient = fakeApiClient,
            tokenStorage = fakeTokenStorage,
            database = database,
            syncTrigger = {
                outboxAtSyncTrigger = database.syncOutboxDao().getAllPendingForOwner(USER_ID)
            },
            ioDispatcher = testDispatcher,
            authScope = testScope,
            autoInitialize = false
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedGuestData() {
        database.sessionDao().insertAll(
            listOf(
                SessionEntity(
                    id = SESSION_ID,
                    ownerId = "guest",
                    name = "30 aug 2026 morning",
                    event = "3x3",
                    kind = "automatic",
                    startedAt = "2026-08-30T08:00:00.000Z",
                    endedAt = "2026-08-30T09:00:00.000Z",
                    archived = false,
                    version = 4L
                )
            )
        )
        database.solveDao().insertAll(
            listOf(
                SolveEntity(
                    id = "sol-bt",
                    ownerId = "guest",
                    sessionId = SESSION_ID,
                    event = "3x3",
                    durationMs = 9_870L,
                    penalty = "none",
                    solvedAt = "2026-08-30T08:05:00.000Z",
                    scramble = "R U R' U'",
                    version = 3L,
                    timingDevice = "external_timer"
                ),
                SolveEntity(
                    id = "sol-touch",
                    ownerId = "guest",
                    sessionId = SESSION_ID,
                    event = "3x3",
                    durationMs = 12_340L,
                    penalty = "plus_two",
                    solvedAt = "2026-08-30T08:10:00.000Z",
                    scramble = "F R U",
                    version = 2L,
                    timingDevice = "keyboard"
                ),
                SolveEntity(
                    id = "sol-cube",
                    ownerId = "guest",
                    sessionId = SESSION_ID,
                    event = "3x3",
                    durationMs = 15_000L,
                    penalty = "dnf",
                    solvedAt = "2026-08-30T08:15:00.000Z",
                    scramble = "L2 D2",
                    version = 1L,
                    timingDevice = "smart_cube"
                )
            )
        )
    }

    private fun payloadObject(mutation: SyncOutboxEntity): JsonObject =
        json.parseToJsonElement(requireNotNull(mutation.payloadJson)).jsonObject

    private fun solveMutationsById(mutations: List<SyncOutboxEntity>): Map<String, SyncOutboxEntity> =
        mutations.filter { it.entityType == "solve" }.associateBy { it.entityId }

    @Test
    fun `adoptGuestData uploads each solve with its real timing_device`() = runTest(testDispatcher) {
        seedGuestData()

        authManager.adoptGuestData(USER_ID)

        val solves = solveMutationsById(database.syncOutboxDao().getAllPendingForOwner(USER_ID))
        assertEquals(setOf("sol-bt", "sol-touch", "sol-cube"), solves.keys)

        fun timingDeviceOf(id: String): String =
            json.decodeFromString(SolveSyncPayload.serializer(), requireNotNull(solves.getValue(id).payloadJson))
                .timingDevice

        assertEquals("external_timer", timingDeviceOf("sol-bt"))
        assertEquals("keyboard", timingDeviceOf("sol-touch"))
        assertEquals("smart_cube", timingDeviceOf("sol-cube"))

        // The raw wire JSON carries it too (not just a decode-side default).
        assertEquals(JsonPrimitive("external_timer"), payloadObject(solves.getValue("sol-bt"))["timing_device"])
    }

    @Test
    fun `adoptGuestData reassigns rows to the user with version 0 and keeps timing_device`() = runTest(testDispatcher) {
        seedGuestData()

        authManager.adoptGuestData(USER_ID)

        assertTrue(database.solveDao().getAllActiveSolvesForOwner("guest").isEmpty())
        assertTrue(database.sessionDao().getAllActiveSessionsForOwner("guest").isEmpty())

        val session = requireNotNull(database.sessionDao().getSessionById(SESSION_ID))
        assertEquals(USER_ID, session.ownerId)
        assertEquals(0L, session.version)

        val solves = database.solveDao().getAllActiveSolvesForOwner(USER_ID).associateBy { it.id }
        assertEquals(3, solves.size)
        solves.values.forEach {
            assertEquals(USER_ID, it.ownerId)
            assertEquals(0L, it.version)
        }
        assertEquals("external_timer", solves.getValue("sol-bt").timingDevice)
        assertEquals("keyboard", solves.getValue("sol-touch").timingDevice)
        assertEquals("smart_cube", solves.getValue("sol-cube").timingDevice)

        // The adoption timestamp written to the rows is the mutations' client_time.
        val mutations = database.syncOutboxDao().getAllPendingForOwner(USER_ID)
        val clientTime = mutations.map { it.clientTime }.distinct().single()
        assertEquals(clientTime, session.updatedAt)
        solves.values.forEach { assertEquals(clientTime, it.updatedAt) }
    }

    @Test
    fun `adoptGuestData enqueues the session before its solves with base version 0 for the user`() = runTest(testDispatcher) {
        seedGuestData()

        authManager.adoptGuestData(USER_ID)

        // The sync engine reads the outbox in this exact order (client_time, then rowid).
        val mutations = database.syncOutboxDao().getPendingMutations(USER_ID)
        assertEquals(4, mutations.size)
        assertEquals("session", mutations[0].entityType)
        assertEquals(SESSION_ID, mutations[0].entityId)
        assertTrue(mutations.drop(1).all { it.entityType == "solve" })

        mutations.forEach {
            assertEquals(USER_ID, it.ownerId)
            assertEquals("upsert", it.action)
            assertEquals(0L, it.baseVersion)
            assertEquals("pending", it.status)
            assertNotNull(it.payloadJson)
        }
        assertEquals(mutations.size, mutations.map { it.id }.toSet().size)
        assertTrue(database.syncOutboxDao().getAllPendingForOwner("guest").isEmpty())
    }

    @Test
    fun `adoptGuestData payloads contain exactly the DTO fields`() = runTest(testDispatcher) {
        seedGuestData()

        authManager.adoptGuestData(USER_ID)

        val mutations = database.syncOutboxDao().getAllPendingForOwner(USER_ID)

        val sessionPayload = payloadObject(mutations.single { it.entityType == "session" })
        assertEquals(
            setOf("id", "name", "event", "kind", "started_at", "ended_at", "archived"),
            sessionPayload.keys
        )

        mutations.filter { it.entityType == "solve" }.forEach { mutation ->
            assertEquals(
                setOf("id", "session_id", "duration_ms", "penalty", "solved_at", "scramble", "event", "timing_device"),
                payloadObject(mutation).keys
            )
        }
    }

    @Test
    fun `adoptGuestData payload values match the pre-mapper hand-built payloads except timing_device`() = runTest(testDispatcher) {
        seedGuestData()
        val guestSession = requireNotNull(database.sessionDao().getSessionById(SESSION_ID))
        val guestSolves = database.solveDao().getAllActiveSolvesForOwner("guest").associateBy { it.id }

        authManager.adoptGuestData(USER_ID)

        val mutations = database.syncOutboxDao().getAllPendingForOwner(USER_ID)

        // Session: byte-for-byte what the old hand-built SessionSyncPayload produced.
        val legacySession = SessionSyncPayload(
            id = guestSession.id,
            name = guestSession.name,
            event = guestSession.event,
            kind = guestSession.kind,
            startedAt = guestSession.startedAt,
            endedAt = guestSession.endedAt,
            archived = guestSession.archived
        )
        assertEquals(
            json.parseToJsonElement(json.encodeToString(SessionSyncPayload.serializer(), legacySession)),
            payloadObject(mutations.single { it.entityType == "session" })
        )

        // Solves: identical to the old payload apart from timing_device, which now carries the row's value.
        for ((id, mutation) in solveMutationsById(mutations)) {
            val guest = guestSolves.getValue(id)
            val legacy = SolveSyncPayload(
                id = guest.id,
                sessionId = guest.sessionId,
                durationMs = guest.durationMs,
                penalty = guest.penalty,
                solvedAt = guest.solvedAt,
                scramble = guest.scramble,
                event = guest.event
            )
            val legacyJson = json.parseToJsonElement(json.encodeToString(SolveSyncPayload.serializer(), legacy)).jsonObject
            val actual = payloadObject(mutation)
            assertEquals(legacyJson - "timing_device", actual - "timing_device")
            assertEquals(guest.timingDevice, actual.getValue("timing_device").jsonPrimitive.content)
        }
    }

    @Test
    fun `adoptGuestData without guest data enqueues nothing`() = runTest(testDispatcher) {
        authManager.adoptGuestData(USER_ID)

        assertTrue(database.syncOutboxDao().getAllPendingForOwner(USER_ID).isEmpty())
    }

    @Test
    fun `login adopts guest bluetooth solves with timing_device before triggering sync`() = runTest(testDispatcher) {
        seedGuestData()
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc",
            refreshToken = "ref",
            user = UserDto(id = USER_ID, email = "cuber@test.com", userRole = "user", emailVerified = true)
        )

        val result = authManager.login("cuber@test.com", "secret-password")

        assertTrue(result is AuthResult.Success)
        val atTrigger = requireNotNull(outboxAtSyncTrigger) { "login must trigger a sync after adoption" }
        assertEquals(4, atTrigger.size)
        assertEquals("session", atTrigger.first().entityType)

        val solves = solveMutationsById(atTrigger)
        assertEquals(
            "external_timer",
            json.decodeFromString(SolveSyncPayload.serializer(), requireNotNull(solves.getValue("sol-bt").payloadJson))
                .timingDevice
        )
        assertEquals(
            "keyboard",
            json.decodeFromString(SolveSyncPayload.serializer(), requireNotNull(solves.getValue("sol-touch").payloadJson))
                .timingDevice
        )
        assertTrue(database.solveDao().getAllActiveSolvesForOwner(USER_ID).all { it.version == 0L })
    }

    private companion object {
        const val USER_ID = "user-adopt-bt"
        const val SESSION_ID = "guest-sess-1"
    }

    private class FakeCubeSyncApiClient : CubeSyncApiClient {
        var loginResponse: AuthResponse? = null

        override suspend fun register(request: RegisterRequest): StatusResponse = StatusResponse("verification_required")
        override suspend fun resendVerificationEmail(email: String): StatusResponse = StatusResponse("accepted")
        override suspend fun verifyEmail(token: String): AuthResponse = throw AuthException.InvalidToken()
        override suspend fun login(request: LoginRequest): AuthResponse =
            loginResponse ?: throw AuthException.InvalidCredentials()
        override suspend fun refreshToken(refreshToken: String): AuthResponse = throw AuthException.InvalidRefreshToken()
        override suspend fun logout(refreshToken: String) {}
        override suspend fun requestPasswordReset(email: String): StatusResponse = StatusResponse("accepted")
        override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse =
            throw AuthException.InvalidToken()
        override suspend fun changePassword(request: ChangePasswordRequest) {}
        override suspend fun deleteAccount() {}
        override suspend fun sync(request: SyncRequest): SyncResponse = SyncResponse()
        override suspend fun snapshot(request: SnapshotRequest): SnapshotResponse = SnapshotResponse()
    }

    private class FakeTokenStorage : TokenStorage {
        private var accessToken: String? = null
        private var refreshToken: String? = null
        private var cachedUser: User? = null

        private val _flow = MutableStateFlow<String?>(null)
        override val accessTokenFlow: StateFlow<String?> = _flow

        override fun getAccessToken(): String? = accessToken
        override fun setAccessToken(token: String?) {
            accessToken = token
            _flow.value = token
        }
        override fun getRefreshToken(): String? = refreshToken
        override fun getUserId(): String? = cachedUser?.id
        override fun getUserEmail(): String? = cachedUser?.email
        override fun getUserRole(): String? = cachedUser?.userRole?.name?.lowercase()
        override fun isUserEmailVerified(): Boolean = cachedUser?.emailVerified ?: false
        override fun getDisplayName(): String? = cachedUser?.displayName
        override fun getCachedUser(): User? = cachedUser
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
            this.refreshToken = refreshToken
            cachedUser = User(
                id = userId,
                email = userEmail,
                displayName = displayName,
                emailVerified = emailVerified,
                userRole = UserRole.fromString(userRole)
            )
        }
        override fun getDeviceId(): String = "test-device-id"
        override fun clearAuthData() {
            setAccessToken(null)
            refreshToken = null
            cachedUser = null
        }
        override fun clearAll() = clearAuthData()
    }
}
