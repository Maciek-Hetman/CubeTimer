package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.room.Room
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AuthManagerTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var fakeApiClient: FakeCubeSyncApiClient
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private var syncTriggerCalled = false

    private lateinit var authManager: AuthManagerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApiClient = FakeCubeSyncApiClient()
        fakeTokenStorage = FakeTokenStorage()
        syncTriggerCalled = false

        authManager = AuthManagerImpl(
            apiClient = fakeApiClient,
            tokenStorage = fakeTokenStorage,
            database = database,
            syncTrigger = { syncTriggerCalled = true },
            ioDispatcher = testDispatcher,
            authScope = testScope,
            autoInitialize = false
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `startup without refresh token transitions to Guest`() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = null

        authManager.initialize()

        assertTrue(authManager.authState.value.isGuest)
        assertNull(authManager.currentUser)
    }

    @Test
    fun `startup with valid refresh token refreshes session and transitions to Authenticated`() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "valid-refresh"
        fakeApiClient.refreshResponse = AuthResponse(
            accessToken = "new-acc",
            refreshToken = "new-ref",
            user = UserDto(id = "u-1", email = "u1@test.com", userRole = "user", emailVerified = true)
        )

        authManager.initialize()

        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("u-1", authManager.currentUser?.id)
        assertEquals("new-acc", fakeTokenStorage.storedAccessToken)
        assertEquals("new-ref", fakeTokenStorage.storedRefreshToken)
    }

    @Test
    fun `startup with revoked refresh token clears storage and transitions to Guest`() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "revoked-refresh"
        fakeApiClient.refreshError = AuthException.RefreshTokenReused("Reused")

        authManager.initialize()

        assertTrue(authManager.authState.value.isGuest)
        assertNull(fakeTokenStorage.storedRefreshToken)
    }

    @Test
    fun `startup with network error falls back to cached user in token storage`() = runTest(testDispatcher) {
        fakeTokenStorage.storedRefreshToken = "some-token"
        fakeTokenStorage.storedCachedUser = User(
            id = "cached-u",
            email = "cached@test.com",
            userRole = UserRole.USER,
            emailVerified = true
        )
        fakeApiClient.refreshError = AuthException.NetworkError("Offline")

        authManager.initialize()

        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("cached-u", authManager.currentUser?.id)
    }

    @Test
    fun `login success saves tokens, adopts guest data, triggers sync, and transitions to Authenticated`() = runTest(testDispatcher) {
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-login",
            refreshToken = "ref-login",
            user = UserDto(id = "user-login-1", email = "login@test.com", userRole = "user", emailVerified = true)
        )

        // Seed guest data before login
        database.sessionDao().insert(
            SessionEntity(id = "sess-guest", ownerId = "guest", name = "Guest Session", event = "3x3", kind = "manual", startedAt = "2026-08-30T10:00:00Z")
        )
        database.solveDao().insert(
            SolveEntity(id = "solve-guest", ownerId = "guest", sessionId = "sess-guest", durationMs = 12340L, solvedAt = "2026-08-30T10:01:00Z")
        )

        val result = authManager.login("login@test.com", "Password123!")

        assertTrue(result is AuthResult.Success)
        val user = (result as AuthResult.Success).data
        assertEquals("user-login-1", user.id)
        assertEquals("acc-login", fakeTokenStorage.storedAccessToken)
        assertEquals("ref-login", fakeTokenStorage.storedRefreshToken)
        assertTrue(syncTriggerCalled)

        // Verify guest data adoption in Room
        val adoptedSession = database.sessionDao().getSessionById("sess-guest")
        assertEquals("user-login-1", adoptedSession?.ownerId)
        assertEquals(0L, adoptedSession?.version)

        val adoptedSolve = database.solveDao().getSolveById("solve-guest")
        assertEquals("user-login-1", adoptedSolve?.ownerId)
        assertEquals(0L, adoptedSolve?.version)

        // Verify outbox mutations enqueued
        val mutations = database.syncOutboxDao().getPendingMutations("user-login-1", 10)
        assertEquals(2, mutations.size)
        assertEquals("session", mutations[0].entityType)
        assertEquals("sess-guest", mutations[0].entityId)
        assertEquals("solve", mutations[1].entityType)
        assertEquals("solve-guest", mutations[1].entityId)
    }

    @Test
    fun `login with admin user sets Admin state`() = runTest(testDispatcher) {
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-admin",
            refreshToken = "ref-admin",
            user = UserDto(id = "admin-1", email = "admin@test.com", userRole = "admin", emailVerified = true)
        )

        val result = authManager.login("admin@test.com", "AdminPassword123!")

        assertTrue(result is AuthResult.Success)
        assertTrue(authManager.authState.value.isAdmin)
        assertEquals(UserRole.ADMIN, authManager.currentUser?.userRole)
    }

    @Test
    fun `login failure returns AuthResult Error and stays Guest`() = runTest(testDispatcher) {
        authManager.initialize()
        fakeApiClient.loginError = AuthException.InvalidCredentials("Bad password")

        val result = authManager.login("user@test.com", "WrongPassword")

        assertTrue(result is AuthResult.Error)
        val error = (result as AuthResult.Error).exception
        assertTrue(error is AuthException.InvalidCredentials)
        assertTrue(authManager.authState.value.isGuest)
    }

    @Test
    fun `register success returns AuthResult Success`() = runTest(testDispatcher) {
        authManager.initialize()
        fakeApiClient.registerResponse = StatusResponse(status = "verification_required")

        val result = authManager.register("newuser@test.com", "Password123!")

        assertTrue(result is AuthResult.Success)
        assertTrue(authManager.authState.value.isGuest)
    }

    @Test
    fun `register failure returns AuthResult Error`() = runTest(testDispatcher) {
        authManager.initialize()
        fakeApiClient.registerError = AuthException.EmailAlreadyExists("Taken")

        val result = authManager.register("existing@test.com", "Password123!")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.EmailAlreadyExists)
    }

    @Test
    fun `verifyEmail success adopts guest data and authenticates`() = runTest(testDispatcher) {
        fakeApiClient.verifyEmailResponse = AuthResponse(
            accessToken = "acc-v",
            refreshToken = "ref-v",
            user = UserDto(id = "user-v", email = "v@test.com", userRole = "user", emailVerified = true)
        )

        val result = authManager.verifyEmail("token-123")

        assertTrue(result is AuthResult.Success)
        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("user-v", authManager.currentUser?.id)
    }

    @Test
    fun `resetPassword success adopts guest data and authenticates`() = runTest(testDispatcher) {
        fakeApiClient.resetPasswordResponse = AuthResponse(
            accessToken = "acc-reset",
            refreshToken = "ref-reset",
            user = UserDto(id = "user-reset", email = "reset@test.com", userRole = "user", emailVerified = true)
        )

        val result = authManager.resetPassword("token-456", "NewPassword123!")

        assertTrue(result is AuthResult.Success)
        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("user-reset", authManager.currentUser?.id)
    }

    @Test
    fun `loginWithGoogle adopts guest data and authenticates`() = runTest(testDispatcher) {
        fakeApiClient.googleLoginResponse = AuthResponse(
            accessToken = "acc-g",
            refreshToken = "ref-g",
            user = UserDto(id = "user-g", email = "g@gmail.com", userRole = "user", emailVerified = true)
        )

        val result = authManager.loginWithGoogle("id-token-xyz")

        assertTrue(result is AuthResult.Success)
        assertTrue(authManager.authState.value.isAuthenticated)
        assertEquals("user-g", authManager.currentUser?.id)
    }

    @Test
    fun `logout closes active automatic sessions, revokes token, clears storage, and resets to Guest`() = runTest(testDispatcher) {
        // Authenticate first
        fakeApiClient.loginResponse = AuthResponse(
            accessToken = "acc-out",
            refreshToken = "ref-out",
            user = UserDto(id = "user-out", email = "out@test.com", userRole = "user", emailVerified = true)
        )
        authManager.login("out@test.com", "Password123!")

        // Create an open automatic session and a manual session for user-out
        database.sessionDao().insert(
            SessionEntity(
                id = "sess-auto-1",
                ownerId = "user-out",
                name = "30 Aug 2026 Morning",
                event = "3x3",
                kind = "automatic",
                startedAt = "2026-08-30T09:00:00Z",
                endedAt = null
            )
        )
        database.sessionDao().insert(
            SessionEntity(
                id = "sess-manual-1",
                ownerId = "user-out",
                name = "My Practice",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-08-30T09:00:00Z",
                endedAt = null
            )
        )

        val logoutResult = authManager.logout()

        assertTrue(logoutResult is AuthResult.Success)
        assertTrue(authManager.authState.value.isGuest)
        assertNull(authManager.currentUser)
        assertNull(fakeTokenStorage.storedAccessToken)
        assertNull(fakeTokenStorage.storedRefreshToken)

        // Verify open automatic session was closed
        val closedAutoSession = database.sessionDao().getSessionById("sess-auto-1")
        assertNotNull(closedAutoSession?.endedAt)

        // Verify manual session was untouched
        val manualSession = database.sessionDao().getSessionById("sess-manual-1")
        assertNull(manualSession?.endedAt)
    }

    @Test
    fun `adoptGuestData with multiple solves and sessions enqueues ordered mutations with valid JSON`() = runTest(testDispatcher) {
        val s1 = SessionEntity(id = "sess-1", ownerId = "guest", name = "Session 1", event = "3x3", kind = "manual", startedAt = "2026-08-30T08:00:00Z")
        val s2 = SessionEntity(id = "sess-2", ownerId = "guest", name = "Session 2", event = "2x2", kind = "automatic", startedAt = "2026-08-30T09:00:00Z")
        database.sessionDao().insertAll(listOf(s1, s2))

        val solve1 = SolveEntity(id = "sol-1", ownerId = "guest", sessionId = "sess-1", durationMs = 15000L, solvedAt = "2026-08-30T08:05:00Z", penalty = "plus_two", scramble = "R U R'")
        val solve2 = SolveEntity(id = "sol-2", ownerId = "guest", sessionId = "sess-2", durationMs = 3500L, solvedAt = "2026-08-30T09:05:00Z", penalty = "none", scramble = "F R U")
        val solveOrphan = SolveEntity(id = "sol-orphan", ownerId = "guest", sessionId = null, durationMs = 12000L, solvedAt = "2026-08-30T09:30:00Z", penalty = "dnf", scramble = "U2 R2")
        database.solveDao().insertAll(listOf(solve1, solve2, solveOrphan))

        authManager.adoptGuestData("adopted-user-999")

        // 1. Check Room entities
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(0, database.solveDao().getAllActiveSolvesForOwner("guest").size)

        val adoptedSessions = database.sessionDao().getAllActiveSessionsForOwner("adopted-user-999")
        assertEquals(2, adoptedSessions.size)
        adoptedSessions.forEach { assertEquals(0L, it.version) }

        val adoptedSolves = database.solveDao().getAllActiveSolvesForOwner("adopted-user-999")
        assertEquals(3, adoptedSolves.size)
        adoptedSolves.forEach { assertEquals(0L, it.version) }

        // 2. Check outbox mutations
        val mutations = database.syncOutboxDao().getPendingMutations("adopted-user-999", 50)
        assertEquals(5, mutations.size)

        // First 2 must be sessions
        assertEquals("session", mutations[0].entityType)
        assertEquals("session", mutations[1].entityType)
        // Last 3 must be solves
        assertEquals("solve", mutations[2].entityType)
        assertEquals("solve", mutations[3].entityType)
        assertEquals("solve", mutations[4].entityType)

        // Validate JSON payload deserialization
        val json = Json { ignoreUnknownKeys = true }
        val parsedSessionPayload = json.decodeFromString<SessionSyncPayload>(requireNotNull(mutations[0].payloadJson))
        assertEquals("sess-1", parsedSessionPayload.id)
        assertEquals("Session 1", parsedSessionPayload.name)

        val parsedSolvePayload = json.decodeFromString<SolveSyncPayload>(requireNotNull(mutations[2].payloadJson))
        assertEquals("sol-1", parsedSolvePayload.id)
        assertEquals(15000L, parsedSolvePayload.durationMs)
        assertEquals("plus_two", parsedSolvePayload.penalty)
    }

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

    private class FakeTokenStorage : TokenStorage {
        var storedAccessToken: String? = null
        var storedRefreshToken: String? = null
        var storedUserId: String? = null
        var storedUserEmail: String? = null
        var storedUserRole: String? = null
        var storedEmailVerified: Boolean = false
        var storedDisplayName: String? = null
        var storedCachedUser: User? = null
        var storedDeviceId: String = "test-device-id"

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
        }
        override fun clearAll() {
            clearAuthData()
        }
    }
}
